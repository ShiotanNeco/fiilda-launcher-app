# Repository Agent Instructions

These instructions apply to the whole repository and are tool-neutral. A nearer nested `AGENTS.md` adds rules for its subtree; system, developer, and user instructions always take priority.

## Working style

- Classify the request first. Investigation, review, or proposal work is read-only unless the user asks for edits, builds, installs, or device actions.
- For implementation: make the change, run verification proportional to the risk, and report plainly what was verified and what was not.
- Delegating to sub-agents or a separate reviewer is optional. Use it when it genuinely helps (large or high-risk changes), not as a required ritual. Keep small fixes small.

## Safety

- Preserve user changes and unrelated work in progress. Do not run destructive operations (hard reset, force-push, deleting files or data you did not create) without explicit permission.
- Never clear app data or uninstall the app on a device unless the user explicitly asks.
- Commit or push only when asked.

## Code principles

- Simplicity first. This is a personal, single-user app: do not add compatibility layers for older builds or downgrades, keep one storage format per concept, and delete dead code instead of keeping aliases.
- Comments explain a non-obvious reason in a sentence or two. If code needs a long justification, simplify the code instead.
- Never delete user data because something is temporarily unavailable. A missing app, widget provider, or profile, or a failed query, is not proof of removal; delete only on a confirmed event (for example a package-removed callback or an ID the framework has released).
- Keep ordinary lifecycle paths cheap: no full reloads on every resume, and no disk writes or heavy queries on the main thread when avoidable. Back performance claims with a measurement.

## GitHub publishing

Publishing (pushing to the public repository, creating a release, posting an issue or PR comment) is outward-facing and hard to undo. Do it only when the user asks for that specific action in the current conversation, and show what will be published first.

### Repositories

- `ShiotanNeco/fiilda-launcher` (private): full development history. Its old commits contain the owner's personal email, so this repository must never be made public.
- `ShiotanNeco/fiilda-launcher-app` (public): the published source (`android-launcher/`, `AGENTS.md`, `.agents/`), the bilingual README, the MIT `LICENSE`, issue forms, and Releases. Everything pushed here is public immediately.

### Never publish

- Signing material: `~/.fiilda/` (the release keystore and `release-signing.properties`), any `*.jks`/`*.keystore`, `local.properties`, passwords or tokens. The keystore stays only on the owner's Mac; never print, copy, or move it.
- Personal data: the owner's email (commit as `89955888+ShiotanNeco@users.noreply.github.com`; check `git config user.email` before committing), absolute paths under `/Users/...`, device serials, IP addresses, account names, notification or calendar contents in screenshots.
- Non-source files: `launcher-prototype/` (its device frame images have unknown licenses), PV assets, `android-launcher/artifacts/`, `.codex/`, build outputs (`build/`, `.gradle/`, `*.apk` in Git).
- Third-party images, icons, or code whose license is unknown.

Before every public push, scan the change: `git grep -nI -e "/Users/" -e "@gmail" -e storePassword`, list added binaries, and confirm the author email in `git log`.

### Releasing a version

1. Bump `versionCode` (+1) and `versionName` (e.g. `02.5` → `02.6`) in `android-launcher/app/build.gradle.kts`, and add a `### vXX` entry to `更新履歴` in `android-launcher/README.md`.
2. Run `:app:testDebugUnitTest` and `:app:assembleRelease`. Verify the APK with `apksigner verify --print-certs`: the certificate SHA-256 must start with `7c7ecdb9`. A different certificate means the signing setup is wrong; stop, because users could not update over the previous release.
3. Commit, push the code, and tag `vXX` (`git tag -a vXX`).
4. Publish with `gh release create vXX FiiLDA-Launcher-vXX.apk --repo ShiotanNeco/fiilda-launcher-app --latest`. Write the notes in English first, then Japanese, in plain words for non-developers. Say whether users can install over the previous version.
5. Download the published APK again and confirm its SHA-256 matches the local file.

Debug builds run interpreted and are much slower; judge smoothness on a release or `:app:assembleProfiling` build, never a debug build.

### Issues and pull requests

- Read and summarize new issues and PRs for the owner in Japanese. Do not reply, label, close, or merge unless asked.
- When asked for a reply, write a bilingual draft (Japanese, then English) and let the owner edit it. Post only after the owner says to post that text.
- Review contributed code like any change: check it against these rules (no secrets, no personal data, no unknown-license assets), build and test it, and report before merging.
