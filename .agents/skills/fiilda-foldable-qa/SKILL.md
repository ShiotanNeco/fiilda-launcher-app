---
name: fiilda-foldable-qa
description: Verify FiiLDA Android launcher UI, gestures, navigation, persistence, and widget changes on a Pixel 10 Pro Fold AVD in OPENED and CLOSED postures, with fresh APK-linked evidence and state restoration. Use for device acceptance testing, not documentation-only edits or instruction audits.
---

# FiiLDA Fold QA

## Scope and handoff

Locate the repository root containing `android-launcher`, and read its [Android instructions](../../../android-launcher/AGENTS.md). Paths below are relative to that root. This skill covers AVD acceptance; it cannot establish physical-device behavior.

The parent or implementation owner supplies the built APK's absolute path, SHA-256, test/build results, and installation result. That owner installs the update; QA does not rebuild, replace, or edit the APK or source. Reuse the supplied artifact for both postures. Confirm the installed `com.fiilda.launcher` APK matches the supplied hash; if it does not, return to the install owner before testing.

## Prepare

1. Inspect the requested change and existing WIP. Define the affected actions and any posture/orientation behavior that must remain unchanged.
2. List ADB targets, select the Pixel 10 Pro Fold AVD by verified identity, and record its actual AVD name/profile, serial, supported posture mapping, display IDs, resolutions, and starting posture. Numeric posture IDs, display IDs, and resolutions are discovered values, never constants copied from an earlier run.
3. Use explicit-serial device commands (`adb -s <serial> ...`) sequentially. Record affected launcher preferences, device settings, and foreground screen. Establish how to restore them before mutation. QA may exercise temporary state changes needed by the requested flow; it must not clear data or uninstall without explicit user authorization.
4. Create a uniquely named run directory under `android-launcher/artifacts/`. Record APK identity, installation match, baseline state, and target identity there. Limit captured data to what the test needs.

## Exercise and capture

1. Test the requested flow in OPENED and CLOSED, verifying active display and resolution after each transition. Exercise relevant tap, long-press, drag, navigation, and cancellation paths. Include affected inner portrait/landscape cases when orientation matters.
2. For persistence changes, verify save, cancellation/no-op, and restart behavior as applicable. Preserve unrelated data. For behavior explicitly excluded from the change, check that it remains unchanged.
3. Capture fresh screenshots and UI hierarchy evidence on the active display for each posture. Inspect the actual captures; do not treat file creation or an outer layout bound as visual acceptance. Record unavailable evidence and its effect on confidence.
4. Record pass/fail/blocked separately for each posture and action. Separate build results, AVD UI results, and physical-device, MediaSession, or performance gaps; an AVD screenshot does not prove these other claims.

## Restore and report

Restore affected app/device settings, starting foreground screen, and original posture even if a test fails. Do not force OPENED as the final state. Verify restoration and state any remaining difference. Keep the tested APK installed unless a different final artifact was explicitly requested; restoring UI/settings does not imply downgrading the app.

The run report must include the APK absolute path and SHA-256, installed-artifact match, device/profile/serial/display/posture measurements, tested actions and per-posture results, evidence paths, unverified behavior, and restoration result. Previous captures are not evidence for this run. Send actionable defects to the implementation owner and recheck affected paths after receiving a corrected artifact.

## Environment failures

- If ADB is unavailable or its execution is denied, use the authorized execution path or report device QA blocked. Do not substitute a successful build for device verification.
- After a posture change, a blank display or missing UI hierarchy may indicate sleep or keyguard. Inspect power/display state, wake the AVD if needed, then recapture before judging the app.
- On insufficient storage, diagnose capacity and installation failure first. Do not clear user data or automatically change system storage thresholds. Any exceptional workaround must remain within the task's existing authorization, be scoped to the test AVD, and restore the exact previous setting (including absence) in a cleanup path.
