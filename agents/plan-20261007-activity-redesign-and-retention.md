# Plan: Activity redesign and bounded event retention

Approved 2026-10-07. User decisions: after a successful sync, routine events older than the sync are deleted; hard limits are 1,000 events and 30 days; events from the last 48 hours are never pruned by a sync; check events drop the column list and keep a six-field book summary. The event log is diagnostics only; the `state` table is never pruned.

## Write less

- [x] Slim the `query` event: no `columns`, `selected` reduced to key, title, authors, percentage, status, and last access, `changes` capped at 25 with `changeCount`. `lastCheck` and the sync path keep the full record.
- [x] One `run` event per worker run with `startedAt`, `appVisibleAtStart`, and `durationMs`, instead of `start` and `stop`.
- [x] Drop `*_sync_start`; `<service>.active` records the key and start time.
- [x] Record `queued` only when a new revision is created.

## Prune

- [x] `DiagnosticsStore.prune(now, lastSync)`: count cap, age cap, routine events before the last successful sync outside the 48-hour floor; legacy `start`, `stop`, and `*_sync_start` count as routine; `VACUUM` after large deletes.
- [x] Prune after every worker run, at process start, and after an acknowledged delivery; `recover()` accepts the legacy plain run id.
- [x] Export keeps only the latest export files.

## Activity tab

- [x] Header: segmented All / Issues, Export, Device information; no "Latest 250 events" line.
- [x] Rows: glyph, plain-language title and detail, device-format time, chevron; the whole row is the tap target.
- [x] Group consecutive identical issues; hide completed runs.
- [x] Popup: Summary tab with bold keys and JSON tab with monospace raw JSON, stable pane height, lazy JSON formatting.

## Finish

- [x] Tests: retention, write reduction, row text, popup.
- [x] Docs: README, docs, AGENTS.md, product, design, status, verification; version 0.4.8 / code 20.
- [x] Gates, both reviews, packaging, emulator screenshots, device install. The user checked the Activity tab on the device.

## Notes

- Another session released 0.4.7 (code 19) on 2026-10-07 while this plan was in progress, so this work ships as 0.4.8 (code 20).
- Merging `start` and `stop` into one `run` event and dropping `*_sync_start` were not among the three retention questions; they follow the "only necessary logs" requirement, and the run event keeps the start time, start visibility, and duration.
