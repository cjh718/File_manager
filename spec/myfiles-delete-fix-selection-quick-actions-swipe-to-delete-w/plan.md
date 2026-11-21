# MyFiles: delete fix, selection quick-actions, swipe-to-delete with undo, uncapped recents

## Context

Four user requests for the MyFiles Android file manager (Kotlin, Views/XML + ViewBinding, Material 3, single-activity):

1. **Bug: files on internal shared storage can't be deleted.** Root causes found in code:
   - `WRITE_EXTERNAL_STORAGE` is capped at `maxSdkVersion="28"` and never requested on API 29 — but Android 10 legacy storage (`requestLegacyExternalStorage="true"`) needs it for raw-path writes → every delete of another app's file fails on Android 10.
   - On API 30+ the app relies entirely on All-Files access with raw `java.io.File` deletes; there is no SAF/MediaStore consent fallback, and `FileOperations.deleteTreeWithoutFollowingLinks` swallows the exception (`catch (_: Exception)`, FileOperations.kt:421) → generic "Operation failed" with no cause.
   - `canModify()` (MainActivity.kt:699) uses a stale `primaryRoot.readOnly` flag (refreshed only on HOME), and `confirmDelete`/`deleteItems` **silently return** when it's false — the user taps Delete and nothing happens.
   - `prepare()`'s `canRead()` gate (FileOperations.kt:183) wrongly blocks deletes (delete needs write on the parent, not read on the file).
2. **Multi-select quick actions**: show Share/Send, Move, Delete + "More" (remaining actions) when files are selected. Today these are top-toolbar menu items only.
3. **Swipe right on a file → delete, no confirmation, undo within 3 s, rapid consecutive swipes without stoppage.** No gesture/undo infrastructure exists yet.
4. **Recent files**: remove the hardcoded cap of 6 (MainActivity.kt:580, 591); keep ~5 rows visible but scrollable for more.

## Design decisions (defaults chosen)

- **Deferred delete** for swipe: row leaves the list immediately, actual `fileOperations.delete` runs only after a 3 s window; UNDO cancels pending deletes. No trash folder needed. App killed/rotated inside the window = file never deleted (implicit undo) — acceptable and safe.
- Swipe works on **files and folders** (undo protects folders; recursive cost lands after the window). Disabled in selection mode, on read-only roots, and without write permission. Not attached in FolderPickerActivity.
- Quick actions = **bottom action bar** (Share, Move, Delete, More). Toolbar in selection mode keeps "N selected" + close + Select all; the four duplicate toolbar menu items are removed. "More" = bottom sheet: Copy, Select all, Rename, Details, Open with, Add to Quick Access (gated by single/multi selection and permissions).
- Batch Delete from the bar **keeps its confirmation dialog** ("no confirmation" applies to swipe only).
- Share button stays visible but **disabled** when a folder is selected (stable bar layout; today's toolbar hides it).
- Each swipe commits on its **own** 3 s timer; the Snackbar aggregates ("N files deleted — Undo" restores all still-pending). Commits call `delete()` **per item** because `prepare()` rejects the whole batch if any one source is invalid (FileOperations.kt:245–256).

## Work items

### 1. FileOperations: surface delete failures, exempt DELETE from canRead
File: `app/src/main/java/com/folio/files/FileOperations.kt` (stays JVM-pure — no Android types; it is unit-tested on the JVM).
- Add `Error.PERMISSION_DENIED`.
- `deleteTreeWithoutFollowingLinks` (409–424): return `ExecutionFailure?` instead of `Boolean`; catch ladder: `AccessDeniedException` / `SecurityException` (superclass of `RecoverableSecurityException`) → `PERMISSION_DENIED` with message; other exceptions → `DELETE_FAILED` with message. Directory child failure propagates unchanged.
- `deleteItem()` (312–318): pass `failure.error` + `failure.message` into `failed(...)` (`ItemResult.message` already exists).
- Update the two other call sites: `moveItem()` (298) keeps `COPIED_BUT_SOURCE_DELETE_FAILED` but includes the real message; `copyThroughStaging()` finally (344) ignores the result.
- `prepare()` (183): `operation != Operation.DELETE && !source.canRead()`.

### 2. Permission flow + visible blocked-delete + error reasons in UI
Files: `AndroidManifest.xml`, `MainActivity.kt`, `res/values/strings.xml`.
- Manifest: `WRITE_EXTERNAL_STORAGE` maxSdkVersion 28 → **29**.
- `requestStorageAccess()` (337): request WRITE when `SDK_INT <= Q` (was P).
- New `hasWriteAccess()`: ≥R → `Environment.isExternalStorageManager()`; else WRITE granted. `canModify(file)` becomes `primaryRoot.contains(file) && hasWriteAccess()` (live check, immune to stale `readOnly`).
- `onResume` (166–173): also `refreshStorageRoots()` when the app container is visible; in `refreshStorageRoots()` (463–492) move the HOME-only guard after root reassignment and call `renderToolbar()` so both screens re-render with fresh permissions.
- Replace silent returns with `toast(R.string.delete_not_permitted)` in `confirmDelete(item)` (1013), `confirmDelete(items)` (1027), `deleteItems()` (879).
- `showOperationResult()` (903–944): failure bullets become `"• name — reason"` where reason = `item.message ?: string-for-error` (small `when` over `PERMISSION_DENIED`, `MISSING_SOURCE`, `UNREADABLE_SOURCE`, `SOURCE_OUTSIDE_PRIMARY`, fallback `error?.name`). Benefits delete/copy/move alike.

### 3. Recent files: uncapped, 5 visible rows, inner scroll
Files: `MainActivity.kt`, `res/layout/view_home.xml`.
- `queryRecentFiles()` (569–601): remove `QUERY_ARG_LIMIT 6` and the `result.size < 6` guard. Add SQL selection `DATA LIKE '<primaryRoot.canonicalPath>/%'` so other-volume rows are skipped in SQL (no row limit). Keep `isFile && canRead && primaryRoot.contains` filter.
- `setupLists()` (204): remove `isNestedScrollingEnabled = false` from `recent_list`.
- `view_home.xml` (235–240): `recent_list` height `wrap_content` → **380dp** (5 × 76dp row minHeight). Empty/progress logic unchanged.
- Add `recentJob: Job?` tracking in `loadRecentFiles()` (mirrors `loadJob`) to prevent out-of-order submits.
- Perf note: uncapped query stats every row on IO; `AsyncListDiffer` diffs off-main. If a huge library janks in testing, report to the user — do not silently reintroduce a cap.

### 4. Bottom selection quick-action bar + "More" sheet
Files: `res/layout/activity_main.xml`, new `res/layout/view_selection_bar.xml`, new `res/layout/sheet_selection_more.xml`, new `SelectionMoreSheet.kt`, `res/menu/menu_main.xml`, new drawables `ic_share/ic_move/ic_delete`, `MainActivity.kt`, strings (+ `values-night` colors if needed).
- `activity_main.xml`: add `<include android:id="@+id/selection_bar" ... visibility="gone"/>` as **last child of `app_container`** (vertical LinearLayout) — showing it shrinks the content frame, so it never covers rows; works on HOME and BROWSER; FAB stays above it.
- Bar: 4 equal-weight Material text buttons with icons — `bar_share`, `bar_move`, `bar_delete`, `bar_more`.
- `menu_main.xml`: remove `action_selection_share/copy/move/delete`; `selection_group` keeps `action_select_all`. Remove their toolbar handler branches (245–260).
- Wire bar clicks to existing handlers: `shareFiles(selectedItems().map { it.file })`, `beginTransfer(selectedItems(), move = true)`, `confirmDelete(selectedItems())`, More → `SelectionMoreSheet`.
- New `renderSelectionBar(selected)` called from `renderToolbar()` (701–744) — every selection mutation and screen switch already funnels through it (incl. back-press). Visible = `selectionMode && selected.isNotEmpty()`; Share enabled = no directories; Move/Delete enabled = `selected.all { canModify(it.file) }`; More always enabled.
- `SelectionMoreSheet` (BottomSheetDialogFragment): rows Copy / Select all / Rename / Details / Open with / Add to Quick Access; gating from `selectedItems()` snapshot (`singleOrNull()` for single-item actions; Rename needs `canModify`; Open with = files only; Quick Access = folders in primary). Change the needed MainActivity methods from `private` to `internal`. Each click runs the existing handler and dismisses; empty selection → dismiss.

### 5. Swipe-right deferred delete with 3 s aggregate undo
Files: `activity_main.xml` (root `FrameLayout` → `CoordinatorLayout` for Snackbar), new `SwipeToDeleteCallback.kt`, `MainActivity.kt`, colors in `values/` + `values-night/`, strings/plurals.
- `SwipeToDeleteCallback : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT)` with `canSwipe: (FileItem) -> Boolean` (`!selectionMode && canModify(file)`) and `onSwiped`. `getMovementFlags` returns 0 when not swipeable; read item via `bindingAdapterPosition` (guard `NO_POSITION`). `onChildDraw`: red reveal (`#C62828` light / `#B3261E` night) + white `ic_delete`, alpha ramped by dX. Attach to `file_list` and `recent_list` in `setupLists()`; FolderPicker untouched.
- Pending state in MainActivity:
  ```kotlin
  private data class PendingDelete(val item: FileItem, var job: Job?, var committing: Boolean = false)
  private val pendingDeletes = LinkedHashMap<String, PendingDelete>() // key = selectionKey(file)
  ```
  Keys stay in the map through the committing IO phase. **Hide pending paths in every reload path**: snapshot `pendingDeletes.keys` before IO in `loadFiles()` (609–611) and `queryRecentFiles()`/`loadRecentFiles()` and filter results. Swipe removal also removes from `allItems` (BROWSER) or submits a filtered copy of `recentAdapter.currentList` (HOME) — never mutate `currentList` in place.
- `enqueueDeferredDelete(item)`: remove row → `pendingDeletes[key] = PendingDelete(item, lifecycleScope.launch { delay(3_000); commitPendingDelete(key) })` → `updateUndoSnackbar()`.
- `commitPendingDelete(key)`: mark committing; `withContext(Dispatchers.IO + NonCancellable) { fileOperations.delete(listOf(file)) }` (per-item); `scanChangedFiles`; remove key; `refreshAfterChange()` (failure → row reappears); on `PERMISSION_DENIED` → consent fallback (item 6), else `showOperationResult`; update snackbar.
- `undoPendingDeletes()`: cancel + remove all non-committing entries, `refreshAfterChange()` (files still on disk → rows restored), dismiss snackbar, toast "Restored N items".
- `updateUndoSnackbar()`: single → `Deleted "name"`; multiple → plural `N files deleted`; action UNDO; `setDuration(3500)`; re-show on each enqueue so the window covers the latest swipe; on BROWSER with FAB visible `setAnchorView(newFolderButton)`. Each item still commits on its own timer — consecutive swipes never block.
- Not persisted across rotation/process death (implicit undo; documented).

### 6. Scoped-storage consent fallback (edge denials only, API 29/30+)
Files: new `DeleteFallback.kt`, `MainActivity.kt`.
- Helper: resolve `MediaStore.Files` content URIs for paths (`DATA` selection → `ContentUris.withAppendedId`); `MediaStore.createDeleteRequest(resolver, uris).intentSender` (≥R).
- `deleteConsentLauncher = registerForActivityResult(StartIntentSenderForResult)` alongside existing launchers.
- `requestDeleteConsent(files)`: ≥30 → launch system delete-consent dialog for the failed paths; API 29 → try `contentResolver.delete(mediaUri)`, catch `RecoverableSecurityException` → launch `e.userAction.actionIntent.intentSender` (only place RSE is referenced — outside the JVM engine). No MediaStore row / unsupported → clear toast.
- Callback RESULT_OK: per path on IO — `!exists()` counts as deleted (system already removed it); else retry `fileOperations.delete(listOf(file))` (consent grants one-time raw-delete allowance). Then scan + refresh + result. Cancel → toast, rows stay. Never re-request consent for the same path twice in one operation; `Android/data`/`obb` surface the clear error (undeletable even with consent).
- Wire into `deleteItems()` failure path (when all failures are `PERMISSION_DENIED`) and `commitPendingDelete`.

### 7. Docs
- `README.md`: update feature list (quick-action bar, swipe-delete + undo, consent fallback) and the "up to six" Recent note (line 61).

## Build environment & verification

Container is **ARM64 with no JDK/Android SDK** (sudo available, network OK). Google ships no linux-aarch64 aapt2/build-tools, so:
1. `sudo apt-get update && sudo apt-get install -y openjdk-17-jdk-headless qemu-user unzip` (qemu-user = x86_64 emulation fallback for aapt2).
2. Download Android cmdline-tools, `sdkmanager "platforms;android-35" "build-tools;34.0.0"` (+ licenses), write `local.properties` (gitignored).
3. `./gradlew testDebugUnitTest assembleDebug`. If aapt2 fails on architecture: extract the x86_64 aapt2 from the AGP maven jar and point `android.aapt2FromMavenOverride` (gradle.properties, local only — do not commit) at a wrapper script running it under `qemu-x86_64`.
4. **JVM tests** (`app/src/test/java/com/folio/files/FileOperationsTest.kt`): existing tests pass unchanged; add:
   - delete of a child in a non-writable parent → `FAILED` + `PERMISSION_DENIED` + non-null message (restore writability in teardown),
   - DELETE succeeds on a non-readable file (`assumeTrue(!file.canRead())` to skip when root),
   - move partial (`COPIED_BUT_SOURCE_DELETE_FAILED`) carries a message.
   - Optional: extract pending-delete bookkeeping into a pure `PendingDeletes` class + test (undo skips committing entries).
5. **Deliver**: commit (conventional style), `openkbs storage upload app/build/outputs/apk/debug/app-debug.apk media/folio/MyFiles.apk` (the site's download button already points at `/media/folio/MyFiles.apk`), `openkbs site deploy` if site files changed.
6. **Manual device checks** (user installs the APK; no emulator here):
   - Android 10: WRITE prompt now appears; Download/DCIM deletes work. Android 11+: All-Files wall; revoke while backgrounded → buttons re-render on resume; other-app media denial → system consent dialog.
   - Swipe: red reveal, row out, snackbar 3 s, file gone after; 5 rapid swipes → "5 files deleted", UNDO restores all pending; undo after one committed restores only the rest; folder swipe; swipe while search-filtered; navigate away mid-window; selection mode blocks swipe; HOME recent swipe.
   - Bar: HOME + BROWSER; folder selected → Share disabled; no permission → Move/Delete disabled + visible toast (never silent); More sheet gating; back press hides bar; bar never covers rows.
   - Recents: >5 files scroll inside home scroll; empty state; large-library load time.

## Risks / notes

- Whole-batch rejection in `prepare()` is why swipe commits are per-item (toolbar multi-delete keeps batch semantics).
- Fixed 380dp recents leaves blank space with <5 files and shows fewer rows at large font scales — matches the explicit "maintain view of 5" requirement.
- `canModify` semantics change (live permission check) also affects the single-item popup and transfers — desired consistency; re-test read-only dual-app roots.
- Rotation mid-commit: the delete IO finishes (NonCancellable) but post-IO scan/refresh may be skipped; the next reload filters missing files, so no stale rows persist.
- If the ARM64 build proves impossible even with qemu, deliver code + tests and say plainly the APK must be built locally (`./gradlew assembleDebug` per README).