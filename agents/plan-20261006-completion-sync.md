# Boox Tracker: completion sync

Approved by the user on 2026-10-06 after rejecting a Sync All backfill button. Scope: when NeoReader marks the detected book finished, mark it Read on the enabled tracker. Hardcover is the only tracker. See [product context](product.md#progress-and-history) and [device testing](device-testing.md#completion-sync).

## Source rules

- [x] Accept provider status `2` only with a full fraction; hold `source_finish_progress_mismatch` otherwise.
- [x] Keep status `1` at 100% held as `source_status_not_finished`; other codes hold as `source_status_unsupported`.
- [x] Finish date is the device-local date of `lastAccess`, then the queued read time, then delivery time.

## Hardcover writes

- [x] Reuse matching, edition basis, and page rules; write full pages and `finished_at` on the single unfinished read, then set `status_id` 3.
- [x] Report `already_current` without writes when the remote book is already Read.
- [x] Resume an interrupted completion: a not-yet-Read book with one finished read gets only the status update, independent of the recomputed date.
- [x] Finishing never short-circuits on equal or higher remote pages; the higher page count is kept on the finished read.
- [x] Keep paused, multiple, and finished remote reads protected for a status `1` source.
- [x] Show Finished in the Hardcover row's last-success line.

## Verification

- [x] Tests for new finished book, finishing an existing read, already-Read remote, partial-progress hold, read-time fallback, and queue delivery after a status change.
- [x] Full Gradle, ktlint, ruff, markdownlint, actionlint gates and parallel reviews.
- [ ] Physical: one real finish on the GoColor7 with a single read entry and correct date on Hardcover.
- [ ] Physical: reopening a finished book holds instead of altering history.

## Notes

Hardcover documentation does not say whether inserting a user book as Read creates a read on its own. The implementation always creates or updates the read itself before changing the status, so the physical check must confirm a single read entry.
