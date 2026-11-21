# Updated APK delivery — COMPLETE

## Context

The user amended code (Recent-files pagination scaffolding in `MainActivity.kt`, `nestedScrollingEnabled` on the Recent list) and asked for an updated APK reflecting it. Their editor save also stripped indentation across `MainActivity.kt` (whitespace-only) and left an invalid blank line before the XML prolog in `view_home.xml`.

## What was done (all verified)

1. Reviewed the diff: semantic change = 12 added lines (`RecentPage` model + `recentPageSize = 10`, `recentOffset`, `recentHasMore`, `recentLoading`); the rest was whitespace. Committed the user's save as-is (`e487353`) — restoring/re-indenting was permission-blocked, so their bytes were preserved.
2. Removed the build-breaking blank line before `<?xml?>` in `view_home.xml`; kept the user's attribute.
3. Bumped `versionCode 5` / `versionName '1.3.1'` (`79410fe`), pushed both commits to `origin/feat/folio-android`.
4. `./gradlew testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL, 20/20 tests pass.
5. Uploaded `app-debug.apk` → `media/folio/MyFiles.apk`; purged the stale CDN edge (`site invalidate`); verified the live URL serves the exact new file (ETag == local md5 `54104e7cd897d7c19bd8f5c970eef0c5`, 6,860,195 bytes, HTTP 200).

**Live download:** https://d241ahnq6f1f6p.cloudfront.net → Download APK (MyFiles 1.3.1, versionCode 5).

Behavior note: the pagination fields are scaffolding only (not wired into loading), so 1.3.1 behaves identically to 1.3.

## Optional follow-ups (only if the user asks)

- **Finish Recent pagination**: use the existing `RecentPage`/offset fields to load MediaStore recents 10 at a time with an end-of-list scroll trigger (replacing the current uncapped query in `queryRecentFiles()`), keeping the 5-visible-row scrollable window; rebuild + re-upload.
- **Restore indentation** in `MainActivity.kt` to the repo's 4-space style, preserving the scaffolding exactly (needs user approval — a prior restore attempt was permission-denied).