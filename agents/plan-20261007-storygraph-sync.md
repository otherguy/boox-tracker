# StoryGraph sync

Approved 2026-10-07. StoryGraph becomes the third tracker through its website session; the user accepted the unofficial-access risk the same day and public docs disclose it. The research record is in [integrations](integrations.md#storygraph).

## Decisions

- Sign-in is StoryGraph's own sign-in page in an in-app WebView inside the bordered popup. The app never sees the password and keeps only the browser session cookies, in the WebView cookie store.
- Requests use `HttpURLConnection` with those cookies and the WebView's User-Agent. A device spike gates the sync work: if Cloudflare challenges the plain client, re-plan around a WebView transport instead of patching.
- Matching: explicit `storygraph:` UUID, then ISBN-13, ISBN-10, ASIN through the search fragment, each hit confirmed by the edition's `ISBN/UID`, then one normalized title and author match. Ambiguity holds.
- Writes: shelve Currently Reading first, post the floored percentage, confirm by reading the page back; a finished source marks the book Read. Read, Did Not Finish and Rereading editions hold a reading source. Never switch editions.
- Scope: percentage mode only; username only in the details popup; server-side sign-out at Log out; version 0.5.0 / code 21.

## Shared and plumbing

- [x] `BookIdentifiers.kt`: shared `uuidPattern`; `FableMatch.kt` uses it and exposes `authorKey`. `FableSync.kt`: `sourceStatus(book)` extracted.
- [x] `ReadingSyncApp.kt` connection, `connections`, `recover()`; `ActivityLogAdapter.kt` service name; `Export.kt` notes and summary.

## Auth and popup

- [x] `StoryGraphAuth.kt`: request and response types, `urlConnectionTransport`, `StoryGraphSession`, `StoryGraphHttp` with response classification and Set-Cookie write-back.
- [x] `StoryGraphConnection.kt`: flags, `connected`, `account`, `fetchProfile`, `signOut`, `sessionCaptured`, `setEnabled`, `deliver` guard.
- [x] `MainActivity.kt`: row, popup, `updateStoryGraphSignIn`, `fixedPane`, `connectionText`, `serviceIssues`, `storyGraphReport`; `ids.xml`.
- [x] Tests for sign-in, cancel, expiry and challenge marking; debug build.
- [x] Physical: sign in through the popup; the popup closes and `@username` shows in the details popup (0.5.1, 2026-10-08). The first sync passed Cloudflare (0.5.2).
- [x] Physical (transport spike): Sync Now 35 min and more after sign-in, a later manual sync, and a sync after a reboot passed (2026-10-08).
- [ ] Physical (transport spike, continued): a sync a few hours on, one the next day, and one from another country; none records `storygraph_browser_check_required`.

## Match and parsing

- [x] Fixtures: sanitised excerpts of the real pages under `app/src/test/resources/storygraph/`.
- [x] `StoryGraphPages.kt` and `StoryGraphPagesTest.kt`.
- [x] `StoryGraphMatch.kt` and `StoryGraphMatchTest.kt`.

## Sync

- [x] `StoryGraphSync.kt` and sync tests; `StoryGraphServer` grows with the sync routes.

## UI

- [x] `resultFields` shelf map, details popup, UI tests, stderr allowlist if needed.

## Docs

- [x] `docs/storygraph.md`, README, `docs/hardcover.md`, `docs/service-artwork.md`, `docs/device-testing.md`, `CONTRIBUTING.md`.
- [x] `agents/integrations.md`, `product.md`, `design.md`, `project-status.md`, `verification.md`, `device-testing.md`, `AGENTS.md`.

## Version and release checks

- [x] `app/build.gradle.kts`: 0.5.0 / code 21.
- [x] Gates, ktlint, ruff, markdownlint, actionlint, both reviews, packaging, emulator screenshot.
- [x] Device install over 0.4.8 (0.5.0 at 17:25 and 0.5.1 at 17:37 on 2026-10-08, inode unchanged, launched by ADB without a crash).
- [x] Physical: In the Blood matched by ISBN and the shelved hardcover received 52% with the read-back (2026-10-08); the keyboard works in the popup (0.5.1).
- [ ] Physical: an unshelved test book is shelved and written; finish and reopen; Off/On with a live session; Cancel; Log out; export has no cookie, token or user id; hidden-app offline delivery with three services On.

## Notes

- Fixtures are sanitised excerpts of the real pages with placeholders, assembled by the fake server, not whole saved pages: a 130 KB page per state would have put the user's library into the repository.
- A paused edition is marked currently reading before its progress is compared; the user confirmed this on 2026-10-08. The result reports `shelfBefore: paused`; the public guide says a paused book is resumed.
- Both reviews ran on 2026-10-08 and their findings were applied; see [verification](verification.md#storygraph-tracker-050). The reviews also suggested moving the sign-in job handling and the matcher preambles into code shared with Fable; left as is, because this change does not need that refactor.
- The emulator shows the row only; the sign-in page and every write are device checks.
- The first full gate run stopped at a then-failing screen test, so the assemble tasks only ran in the final run.
- 2026-10-08 on the device: the popup's fields could not raise the keyboard because AppCompat marks a dialog without a text editor as no input-method target; 0.5.1 / code 22 clears `FLAG_ALT_FOCUSABLE_IM` after showing the dialog, with a Robolectric test on the window flags. The sign-in page shows no Remember me control; the docs no longer mention one.
- The explicit `storygraph:` tag path is untested on the device; the user deferred it on 2026-10-08.
- 2026-10-08 on the device: sign-in captured the session and the first sync passed Cloudflare, then held with `storygraph_book_not_found`. The raw HTML writes `data-book-id` bare, which the quoted-only attribute parser missed; the browser DOM had hidden that. 0.5.2 / code 23 reads bare values, treats confirmed sibling editions with one title and author as one book (the ebook carries the UK ISBN next to the Kindle ISBN NeoReader shows), and carries the user's new popup text.
