# MyFiles for Android

MyFiles is a native, offline file manager for shared phone storage. It is built with Kotlin, Android Views and Material 3, with light and dark themes and bundled Roboto typography.

## Included

- Internal-storage usage overview
- Customizable Quick Access shortcuts with add, remove, reorder and reset controls
- Shortcuts to Downloads, DCIM, Movies, Music, Documents and Recordings by default
- Recent shared files from MediaStore
- Folder browsing with folders-first sorting
- Search within the current folder and name/date/size sorting
- Long-press multi-select with a bottom quick-action bar (Share, Move, Delete, More) and confirmed batch Delete
- Swipe a file or folder right to delete it instantly, with a 3-second Undo and no confirmation; swipe as many rows as you like in a row
- Single-item and batch Copy/Move with a primary-storage destination picker
- Open using Android’s default-app flow, or choose an app explicitly with Open with
- Single and multiple file sharing using read-only FileProvider grants
- Shared-profile Downloads discovery on supported Android 13+ devices
- User-confirmed content-URI imports into `Download/MyFiles imports`
- New-folder creation, rename, details and confirmed deletion

## Requirements

- Android 8.0 (API 26) or newer
- Android 11+: enable **All files access** when Android opens the MyFiles permission settings
- Android 10 and earlier: allow the requested storage permission

MyFiles only sees shared storage exposed to it by Android, not another app’s private data. It has no account, ads, analytics or Internet permission. This prototype uses broad storage access because its core purpose is file management; Google Play distribution requires a separate all-files-access policy declaration.

## Dual apps and shared profiles

On Android 13/API 33 and newer, MyFiles uses `StorageManager.getStorageVolumesIncludingSharedProfiles()` to look for readable Downloads folders shared by another profile. Any such folders appear separately under **Dual app Downloads** and are treated as read-only: files can be opened, shared or copied into primary storage, but cannot be renamed, moved or deleted from the shared profile.

Clone behavior varies by phone manufacturer. Android does not expose every cloned app’s storage, and All files access does not unlock another profile’s isolated storage, Chrome’s private data or `Android/data`. If no shared Downloads folder appears, try cloned Chrome’s **Open with → MyFiles** or **Share → MyFiles** action. MyFiles asks for confirmation and imports a copy when the source app supplies a readable `content://` grant. If the phone does not permit that transfer, direct access is unsupported rather than something another permission can fix.

## File operations

Copy and Move destinations are confined to primary storage. Existing destination names are never overwritten, and folders cannot be copied or moved into themselves. Moves copy first and delete the source only after a successful copy when a direct rename is unavailable. Batch operations report partial failures with the reason for each affected file. Share accepts files only; directories cannot be attached as recursive shares.

Swipe-to-delete removes the row immediately but only deletes the file after a 3-second undo window; Undo restores every swipe that has not committed yet. If the app is closed or rotated inside the window, nothing is deleted. When Android itself refuses a raw delete (for example media owned by another app), MyFiles asks through Android's system delete-consent dialog instead of failing silently.

Normal **Open** launches Android’s `ACTION_VIEW` flow directly so an existing default viewer is honored. Android may offer **Just once/Always** when no default exists. **Open with** deliberately displays the chooser. The exact default-app controls and how defaults are cleared are controlled by Android or the phone manufacturer.

Quick Access customization is stored locally in SharedPreferences. Removing a shortcut never deletes its folder. Custom entries are confined to primary storage, and Reset restores the original six shortcuts.

## Build & test yourself (no AI credits)

AI credits are spent only when you chat with the assistant. Building, testing and uploading the APK are ordinary commands — run them yourself and they cost no credits.

### In the studio terminal (this workspace is pre-configured)

Edit the code, then run one command:

```bash
./ship.sh               # build + unit tests, then upload to the download link
./ship.sh --fast        # skip unit tests for quicker iteration
./ship.sh --build-only  # just build the APK; install it manually, no upload
```

`./ship.sh` uploads to the same download page (<https://d241ahnq6f1f6p.cloudfront.net>) and refreshes the CDN, so you re-download on the phone to test. Sideloading reinstalls fine without a version bump.

### On your own computer (fastest loop)

Install Android Studio (it bundles JDK 17 and the Android SDK), open this project, connect your phone via USB with **Developer options → USB debugging** enabled, and press **Run ▶** to install directly — no upload or download needed.

Or build from a terminal (JDK 17 + Android SDK 35):

```bash
./gradlew testDebugUnitTest assembleDebug
```

The installable debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r app-debug.apk`, or copy it to the phone and open it. Android may ask you to allow installation from the browser or file manager because this is a sideloaded prototype.

> The studio workspace runs on ARM64 and uses a pre-configured aapt2 workaround; a normal computer needs no workaround. If the workspace is ever reset, the build setup may need restoring — ask the assistant once, or use Android Studio locally.

## Notes

The Recent list comes from Android’s MediaStore and loads ten files at a time, fetching the next page as you scroll; it shows five rows at a time and scrolls for more. Quick-access folders depend on the manufacturer’s folder names. Search intentionally covers the open folder rather than recursively scanning the entire device.

The bundled Roboto font is licensed under the SIL Open Font License; its license is included in `app/src/main/assets/OFL-Roboto.txt`.
