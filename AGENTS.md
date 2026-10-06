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
