# Self-service build & test (no AI credits)

## Context

The user edits the code themselves and wants to build/test the APK without spending AI credits each time. Key fact: **credits are consumed only by AI chat** — building, testing, and uploading are ordinary commands that cost zero AI credits when the user runs them. This workspace is already provisioned to build (JDK 17, Android SDK 35, and the ARM64 aapt2 qemu workaround in `~/.gradle/gradle.properties`), so the user can run the whole loop themselves in the studio terminal.

Goal: give them a one-command script + clear docs so they never need to trigger the AI just to test a change.

## Changes

### 1. New `ship.sh` (repo root, executable)
A small bash script bundling the exact chain the AI runs, so the user runs one command in the studio terminal after editing:
- `./gradlew testDebugUnitTest assembleDebug --console=plain` (build + tests)
- `openkbs storage upload app/build/outputs/apk/debug/app-debug.apk media/folio/MyFiles.apk`
- `openkbs site invalidate /media/folio/MyFiles.apk` (purge stale CDN edge)
- Print the live download URL: `https://d241ahnq6f1f6p.cloudfront.net`
- Flags: `--fast` skips unit tests ( quicker iteration); `--build-only` builds the APK without uploading (for manual/USB install).
- `set -euo pipefail`, `cd` to script dir; echo the built version (parse `aapt2 dump badging` is optional — keep simple, just print APK path + URL).
- No version bump in the script: sideloading reinstalls fine at the same versionCode; bumping is only needed for a "release" the AI ships.

### 2. README — replace/extend the `## Build` section
Add a "Build & test yourself (no AI credits)" explanation:
- Credits = AI chat only; running these commands yourself is free.
- **In the studio terminal** (this workspace, pre-configured): edit code, then `./ship.sh` (or `./ship.sh --fast`), then re-download on the phone from the same link. `./ship.sh --build-only` to just produce the APK.
- **On your own computer** (fastest dev loop): install Android Studio (bundles JDK 17 + SDK), open the project, connect the phone via USB with Developer options → USB debugging on, press **Run ▶** to install directly — no upload/download needed. Or `./gradlew assembleDebug` and copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.
- Note: the studio workspace's ARM64 aapt2 workaround is already configured; a normal computer needs no workaround. If the workspace is ever reset, the build setup may need restoring (ask the AI once, or use Android Studio locally).

## Verification
- `bash -n ship.sh` (syntax check) and run `./ship.sh --build-only` once to confirm it builds green in this workspace.
- Confirm README renders the new section.
- Do NOT upload during verification (avoid touching the live link); the user runs the full `./ship.sh` themselves when ready.