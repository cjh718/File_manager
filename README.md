# MyFiles for Android

MyFiles is a native, offline file manager for shared phone storage. It is built with Kotlin, Android Views and Material 3, with light and dark themes.

## Included

- Internal-storage usage overview
- Shortcuts to Downloads, DCIM, Movies, Music, Documents and Recordings
- Recent shared files from MediaStore
- Folder browsing with folders-first sorting
- Search within the current folder
- Name, modification-date and size sorting
- Open and share actions using Android's app chooser
- New-folder creation, rename, details and confirmed deletion
- FileProvider-based access for other apps without exposing file paths

## Requirements

- Android 8.0 (API 26) or newer
- Android 11+: enable **All files access** when Android opens MyFiles's permission settings
- Android 10 and earlier: allow the requested storage permission

MyFiles only sees shared storage, not another app's private data. It has no account, ads, analytics or Internet permission. This prototype uses broad storage access because its core purpose is file management; Google Play distribution requires a separate all-files-access policy declaration.

## Build

Install JDK 17 and Android SDK 35, then run:

```bash
./gradlew assembleDebug
```

The installable debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with `adb install app-debug.apk`, or copy it to a phone and open it. Android may ask you to allow installation from the browser or file manager because this is a sideloaded prototype.

## Notes

The Recent list comes from Android's MediaStore and shows up to six readable shared files. Quick-access folders depend on the manufacturer's folder names. Search intentionally covers the open folder rather than recursively scanning the entire device.

The bundled Manrope font is licensed under the SIL Open Font License; its license is included in `app/src/main/assets/OFL-Manrope.txt`.
