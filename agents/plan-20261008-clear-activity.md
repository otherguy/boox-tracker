# Clear activity

Approved 2026-10-08.

## Context

The Activity tab has no way to empty the event log; events leave only through retention pruning (`DiagnosticsStore.prune`). The user wants a manual **Clear** button next to **Export**, with a confirmation popup, that removes all activity events.

## Decisions (user, 2026-10-08)

- Clear uses an outlined style (white, black border). Export stays the only black button.
- After a clear, the log keeps one marker event, "Activity cleared", with the time and the number of deleted events.
- The `state` table is never touched. Header warnings come from state, so a clear does not change them.

## Steps

- [x] Tests first: `RetentionTest` for `clearEvents`, `MainActivityTest` for Cancel and Clear.
- [x] `DiagnosticsStore.clearEvents()` and a shared `vacuumAfter(deleted)` helper used by `prune`.
- [x] `Diagnostics.clearActivity()`: delete and marker in one transaction, then vacuum.
- [x] `OutlineButton` style, `clear_activity` button before Export, `clear_activity` string.
- [x] Confirmation popup in `MainActivity` ("Clear activity?", one sentence, Clear / Cancel).
- [x] Row text "Activity cleared" with "N events deleted"; summary field "Events deleted".
- [x] Docs: `AGENTS.md`, `agents/design.md`, `README.md`, `docs/device-testing.md`, `agents/project-status.md`.
- [x] Gradle checks, ktlint, markdownlint, emulator check, review agents.

## Notes

- The clear runs through `runDetached` in the app scope, so it finishes when the screen closes. A failure is recorded as an `activity_clear_failed` issue event instead of crashing the app (code review, 2026-10-09).
