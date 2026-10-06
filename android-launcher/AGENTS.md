# Android launcher agent guide

These instructions supplement the repository root `AGENTS.md` for `android-launcher/**`.

## Build and test

- Build and test with Android Studio's JBR from this directory: `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`.
- Build output goes to `fiilda.buildRoot` in `local.properties` (outside the iCloud-synced Documents folder); resolve the APK path from there rather than assuming `app/build`.
- Keep logic in pure functions where practical and add a unit test when fixing a bug in that logic.
- Documentation-only changes need no build or device check.

## Devices

- Pick the target with `adb devices -l` and pass `-s <serial>` on every device command. The `Pixel_10_Pro_Fold` AVD is available; a Samsung foldable may also be connected.
- Install with `adb install -r` so launcher data is kept.
- Device checks are risk-based. For changes to persistence, widgets, gestures, or fold/posture layout, check the affected flow on a device or emulator in the affected postures (OPENED and/or CLOSED) and say what was not verified. For small visual or logic changes, tests, a build, and a brief look are enough. Use the [Fold QA skill](../.agents/skills/fiilda-foldable-qa/SKILL.md) only when a full acceptance pass is requested.
- Restore any device or app state you changed for a test (default home app, disabled packages, settings, posture). Do not change system storage thresholds.
- Save screenshots or other evidence under `artifacts/` only when useful or requested, and keep it small; the directory is not tracked by Git.

## References

Read [ARCHITECTURE.md](ARCHITECTURE.md) before changing state ownership or moving source files, and [SEARCH_UI_CONTRACT.md](SEARCH_UI_CONTRACT.md) before changing search, search permissions, or document selection. Update the relevant document when its contract changes.
