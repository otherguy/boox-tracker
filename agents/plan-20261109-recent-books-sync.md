# Boox Tracker: books read since the last check

Approved by the user on 2026-11-09.

## Problem

Each run queues only the book with the latest `lastAccess`. When you finish book A, go to the library, and open book B before a run sees A's finish, A never gets the finish. A book you leave keeps the last progress that a run saw. Nothing records or warns about the missed state. A long offline period does not cause this, but it makes more of these misses happen before you notice them.

## Decisions

- User, 2026-11-09: each run also queues earlier books that changed since the previous check.
- User, 2026-11-09: a held update for an earlier book warns in the header, the book popup, and the provider popup, the same as a current-book issue.
- User, 2026-11-09: when more than 10 earlier books qualify in one run, the run sends only the current book and records one issue with the count.

These are my calls; check them when you read the plan:

- The `lastAccess` guard and the status filter in the rule below.
- The queue sends the oldest `lastAccess` first, so that feed posts follow reading order.
- When no single latest book exists, the run still queues the earlier books. The current book records the detection issue, as it does now. Without this, a tie would drop the earlier books, because the next run starts from the new snapshot.
- Only held updates of earlier books warn. Notes about a finish date, a streak day, or kept higher progress stay current-book only.
- The books that a run skips because of the cap are not queued later.

## Rule

An earlier book qualifies in a run when all of these are true:

1. A previous snapshot exists. On the first run after install, only the current book is sent.
2. It is not the current (selected) book and has a key. NeoReader records get a unique key or none.
3. Its `readingStatus` is `1` or `2`. A not-started book has no progress to send.
4. Its `lastAccess` is at or after the latest `lastAccess` in the previous snapshot.
5. It is new, or its `progress`, `readingStatus`, or `lastAccess` differs from the previous snapshot.

Condition 4 limits the rule to reading since the previous check. A change to an older book that you did not read does not qualify, for example a change that a cloud sync makes, if BOOX makes such changes. Condition 5 with "at or after" covers the previous check's current book if NeoReader writes `lastAccess` only when it opens a book (not known): that book keeps the previous latest time, and its progress or status changes.

The vacation example: book A was synced at 50%; offline, you finish A, read B to 75%, read all of C, and read D to 20%. The first run after the trip queues A (finished), B (75%), and C (finished), and selects D (20%). This works even if no run happened during the trip, because the previous snapshot is the baseline.

## Collection

- [x] Add `recentBooks(previous, current)` in `ReadingSyncApp.kt` next to `mostRecentlyAccessedBook`. It applies the rule and sorts the books oldest `lastAccess` first.
- [x] `collect` puts the qualifying books in the check as `recent`. With more than 10, it leaves `recent` empty, sets `recentSkipped` to the count, and marks the check as an issue.
- [x] `loggedCheck` replaces each `recent` record with the six-field summary that `selected` uses. Full records stay only in `lastCheck`.

## Sending

- [x] `send` queues each `recent` book, then the selected book. Move the per-book block that exists now (identifier read, state compare, `enqueue`, `queued` event) into one function, then drain once.
- [x] A failure while one earlier book is queued records a `<service>_sync` issue with that book's title and does not stop the other books. Cancellation still stops the run.
- [x] When `selected` is missing, `send` queues the `recent` books and records `fresh_book_detection_required`.
- [x] `drain` sends pending items oldest `lastAccess` first, with a missing time last. `store.pending` keeps its order for its other callers.
- [x] `state()` adds `held`: pending items of other books whose book state has `delivery` set to `error`, with title and reason.

## Display

- [x] `serviceIssues` adds `Progress for “TITLE” was not sent to SERVICE: REASON.` for each held earlier book. As for the current book, it skips a reason that equals the connection error. "Unknown title" replaces a missing title.
- [x] `readIssues` adds `N earlier books changed since the last check. Boox Tracker did not send them.` when `recentSkipped` is above 0. The two read issues can occur together.
- [x] The `held`, `pending`, and `interrupted` events that `drain` writes carry the NeoReader title, and `eventText` shows it on those Activity rows. Successful results already carry the tracker's title.
- [x] `eventText` gives a check with `recentSkipped` its own row text instead of "Progress unreadable".
- [x] Bump the version to 0.8.4 and increase `versionCode` by one.

## Tests

- [x] `recentBooks` cases, through `app.sync`:
  - books read since the previous check, in order;
  - the previous current book changed with the same `lastAccess`;
  - an older book changed with an older `lastAccess` is excluded;
  - status `0` and the selected book are excluded.
- [x] The vacation example through `app.sync` with `FixtureProvider`: offline runs, then delivery online. The Hardcover fake receives the earliest book's finish first.
- [x] Eleven qualifying books: only the current book is queued, one issue event, its Activity row, and the header text.
- [x] A tie on the latest `lastAccess` still queues the earlier books.
- [x] A held earlier book's Activity row names the book.
- [x] `HardcoverTest`: a held earlier book warns in the header, the book popup, and the provider popup. An earlier book that waits offline does not warn.
- [x] A run stopped after its check, and an earlier book that could not be queued, are offered again. A reopened finished earlier book is not sent.

## Documentation

- [x] `AGENTS.md`: the automatic-sync boundary and the `lastAccess` rule in Source data.
- [x] `product.md`: the detection paragraph and "completion sync … for the detected book only".
- [x] `design.md`: the issue list. `README.md`: the detection text in "Automatic book detection" and in how the app reads NeoReader. `docs/hardcover.md`: the same detection text.
- [x] `device-testing.md`: a protocol for an offline finish followed by an immediate book switch.
- [x] `project-status.md` and `verification.md` after the change is built.

## Verification

- [x] Full Gradle, ktlint, ruff, markdownlint, yamllint, and actionlint checks.
- [x] Parallel code-simplifier and code-reviewer reviews; apply their findings.
- [ ] Physical: with Wi-Fi off, finish book A, go to the library, and open book B at once. Then turn Wi-Fi on. Every enabled tracker marks A as Read with the correct date.

## Known effects

- After the update, earlier books that are already held in the queue start to warn in the header.
- When a service cannot queue during a run because it is Off, it does not get that run's earlier books later. The current book has the same limit now.
- Books tied at the latest `lastAccess` are not sent, as before; once the tie is gone, a tied book that did not change is not offered again.
- The cap warning shows in the header only until the next check replaces it; Activity keeps the issue.
- Each service reads an earlier book's ebook identifiers itself, as it does for the current book, so a run with earlier books walks the ebook folder once per book per enabled service.

## Notes

Changes after the code review on 2026-11-09:

- `recentBooks` compares with a stored baseline, `recentBaseline`: the books at the latest access time of the last check whose earlier books every service queued. The snapshot alone moved on at every check, so a run that stopped between the check and the queue (app closed during an app-open sync, a BOOX boot kill, an automatic power-off) lost its earlier books for good. A failed earlier book holds the baseline too, so it is offered again; a check that skipped its books by the cap moves it on.
- An earlier book that a tracker received as finished, and that NeoReader now shows as reading (paging back sets status `1`), is not queued for that tracker. Every tracker holds such an update to protect the finished read, so it would only add a warning that never clears.
- The cap text says "did not send them" instead of "sent only the current book", because a tie sends no current book.
