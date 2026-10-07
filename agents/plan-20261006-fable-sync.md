# Boox Tracker: Fable sync

Approved by the user on 2026-10-06. Scope: add Fable as the second tracker with the same automatic detection, matching, conservative writes, and offline queue rules as Hardcover. Fable has no public developer API. The user accepted that the connector uses Fable's app API and that Fable's terms restrict automated access. See [integration research](integrations.md#fable) for the verified contract and [device testing](device-testing.md#fable-sync) for the physical checks.

## Decisions

- Store only the Firebase ID and refresh tokens in the Keystore-encrypted vault. Never store the password. A dead refresh token clears the session and shows Reconnect required; queued items wait.
- Shelve books on Fable: Currently Reading before the first progress write, Finished on completion.
- Share the queue, delivery loop, and stored state through `TrackerConnection` for both services.
- Send the whole percentage rounded down, because Fable accepts integers only.

## Shared base

- [x] `TrackerConnection` holds state, send, drain, and failure recording; Hardcover keys and event kinds are unchanged.
- [x] Service-named token vault file and Keystore alias, queue keys, and `<service>_http_<status>` failure reasons. Hardcover keeps `hardcover.credentials` and `reading-sync-hardcover`.
- [x] `sync()` and the delivery worker isolate each service, so one service's failure cannot stop the other.
- [x] Interruption recovery covers `fable.active`.

## Fable connector

- [x] Firebase email/password sign-in, refresh before expiry and once after 401/403, session cleared only for dead refresh tokens.
- [x] Matching: explicit `fable:` UUID, then exact ISBN-13, ISBN-10, ASIN search results, then title and author within one edition family. Conflicts and ambiguity hold. One-hour cache keyed by source and metadata fingerprint.
- [x] Shelf state from system-list membership, not book detail status. An edition the user shelved keeps receiving progress.
- [x] Holds: Finished or Did Not Finish family edition with a reading source, page-mode progress, more than 20 editions, unconfirmed shelf or progress, account change.
- [x] Higher remote progress is kept; equal progress on Currently Reading is current.
- [x] Completion writes 100% and confirms the Finished shelf, shelving it when Fable does not.

## UI

- [x] Fable row with the shared summary; toggle On without a session shows the inline email/password form.
- [x] Drafts survive screen rebuilds in memory only; the password draft clears on submit and Off.
- [x] A rejected sign-in returns Off with a plain message. Only an enabled Fable can warn.

## Verification

- [x] 36 new tests: auth, matching, writes, list paging, sign-in cancellation, queue and worker isolation, recovery, and two UI flows. 121 tests, lint, ktlint, and markdownlint pass.
- [x] Parallel code-reviewer and code-simplifier reviews.
- [ ] Physical: sign in on the GoColor7; export contains no password or token.
- [ ] Physical: In the Blood at 50.07% reaches the shelved edition as 50% on Currently Reading.
- [ ] Physical: shelve from an empty library state.
- [ ] Physical: finish a book; Fable shows Finished at 100%.
- [ ] Physical: hidden-app offline delivery for Fable with Hardcover unaffected.
- [ ] Physical: the first refresh after one hour works without a new sign-in.

## Notes

- The Firebase sign-in and refresh calls come from ShelfSync source and Firebase documentation. Live browser traffic on 2026-10-06 confirmed only the api.fable.co calls. The first device sign-in verifies the sign-in contract.
- Identifier ties inside one family use the lowest Fable ID, not an eBook preference. Title matches still prefer the eBook edition with pages.
- ISBN-10 is searched only when its ISBN-13 has no exact result; ASINs only when no ISBN matched. This keeps request counts low; an ISBN/ASIN conflict is not detected.
- Review fixes: the first sync after sign-in runs as its own job, so Off after sign-in pauses instead of discarding the new session (applied to Hardcover too). Scheduled runs record `failed` when a service send fails. List paging follows `next`. System-list IDs are read on every send, not cached. The password field is excluded from saved view state. A shared delivery failure, such as a revoked folder, is logged once per run.
- A 2026-10-07 check found In the Blood on Currently Reading at 50%. The multiselect move off Finished during the API tests had applied; book detail showed it late. The UK paperback sibling keeps a stray 0% record and is on no list.
- 2026-10-07: sign-in moved from the inline form into a popup for both services (0.4.1). A rejected Fable sign-in now keeps the popup open with the reason instead of returning Off; Cancel turns Fable Off.
- 2026-10-07: progress increases also mark the reading day on Fable's streak (0.4.2); see [reading streak](integrations.md#reading-streak-verified-2026-10-07).
