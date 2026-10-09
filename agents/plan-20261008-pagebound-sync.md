# Pagebound sync

Approved 2026-10-08. The research record is also recorded in [integrations](integrations.md#pagebound).

## Context

Pagebound (pagebound.co) is a social reading tracker that was not on the roadmap. The user asked for a full tracker integration: a new service row with icon, sign-in, automatic matching, progress and status writes, details popup, docs, and tests, in the same shape as the four existing trackers. ShelfSync (MIT) added Pagebound on 2026-09-29 and proves the API path; no code is copied.

Pagebound has no public API. Its website (a Vike/React SPA) talks to a private Rails API at `https://prod-pagebound-api.onrender.com/api/v1` with a bearer token, signs in through Firebase Auth, and searches its catalogue through a public search-only Typesense key. Its terms forbid automated data access and queries, as Fable's do. **The user accepted this risk on 2026-10-08.** Public docs must say the connection is unofficial and can break.

## Decisions (user, 2026-10-08)

- Sign-in is an email/password popup: Firebase `signInWithPassword` with Pagebound's public web key, then `POST /auth/firebase_auth` for the Pagebound token. Only the Firebase refresh token and the Pagebound token are stored, never the password (ShelfSync stores it; this project does not). Accounts created with Google or Apple must set a password on Pagebound first; docs say so.
- Progress is posted only in whole steps of 5 from 0: the app sends `floor(percent / 5) * 5` when that value is above what Pagebound holds. At 14% it posts 10%; at 23% it posts 20% (one post, 15 is skipped). A status change or a finish is always sent. **Goodreads changes to the same rule** (currently "at least 5 points ahead of the remote value").
- Every update appears in the user's feed and journey; `no_broadcast` is always `false`, as the website sends it.
- Format is `digital` for every reading instance the app creates. An existing instance keeps the format the user set.
- `edition_id` on the library entry is set only when it is empty and the ISBN identified an edition; an existing edition is kept.
- Row order: Hardcover, Goodreads, StoryGraph, Fable, Pagebound, Margins. Version 0.7.0 / code 29.

## Research record (verified 2026-10-08 in the user's signed-in browser session; no token, email, or account value is recorded)

All API calls carry `Authorization: Bearer <token>`. Account endpoints answer **500 with an empty body** to a missing, malformed, or wrongly signed token, never 401; catalogue reads (`/books`, `/search`, `/editions`) answer 200 whatever the header. The token is an HS256 JWT whose payload holds only `user_id`; it has no expiry. Dates are `M/D/YYYY` without zero padding.

| Call | Verified behaviour |
| --- | --- |
| `POST https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=<PAGEBOUND_FIREBASE_KEY>` `{email, password, returnSecureToken: true}` | Standard Firebase; returns `idToken`, `refreshToken`, `expiresIn`. Not exercised (needs the password); the first device sign-in verifies it. Key `AIzaSyDCfBJ51pRZgHueBfBz0KNDiPNev1ClnGg`, project `pagebound-430920`, shipped in the site bundle. |
| `POST /auth/firebase_auth` `{id_token}` | Returns `token` and `user` (per the bundle and ShelfSync). Not exercised. Render cold starts can take a minute: use 30 s connect / 90 s read for this call. |
| `POST https://securetoken.googleapis.com/v1/token?key=…` | Firebase refresh, as `FableHttp.refresh`. Used only when the API answers 401/403, then the exchange is repeated. |
| `GET /auth/get_authed_user` | `user.{id, uuid, username, email, points, current_level, paid_subscriber, …}`, `preferences.created_at`. Account id is `user.uuid`. |
| `GET /search?q=<isbn>&type=ISBN` | `{edition: {id, isbn13, isbn10, format, pages, title, publisher_name, book_uuid, …}}` for ISBN-13 and ISBN-10; `{edition: null}` for unknown values. Only `type=ISBN` exists (ASIN, Goodreads, title give 400). Works without auth. |
| `GET /editions?book_uuid=<uuid>&page=1` (+`&isbn=`) | `{editions: [...], total_pages, book}`; edition formats `Hardcover`, `Paperback`, `Kindle`, `Audiobook`, `Ebook`. |
| `POST https://hztadco4ku1vqi6lp.a1.typesense.net/multi_search?x-typesense-api-key=<key>` `{searches: [{collection: "books", q, query_by: "title,author_name", per_page}]}` | Fuzzy hits with `document.{id, uuid, title, author_name}`; no ISBN or Goodreads field. `filter_by: "author_name:=…"` works. The NeoReader title "Terminal List #05 – In the Blood Jack Carr" ranks the right book first among 8 hits. Key `SgSrp2Vx4V4wjJAwnME6uWUufNdi9BxM` is search-only (`/collections` is 401). |
| `GET /books/<uuid>` | `book.{id, uuid, title, author_name, page_count, goodreads_id, user_book}`; `user_book` is null when not in the library, else `{id, uuid, status, progress, current_page, total_page_count, edition_id, has_ever_finished, current_reading_instance, reading_instances[]}`. Reading instance: `{id, current, finished, format, tracking_mode, progress_method, total_page_count, started_reading_at_date, finished_reading_at_date}`. |
| `GET /user_books/<uuid>` | The library entry alone (same fields plus `user` and `book`). Read-back path. |
| `GET /reading_updates?user_book_id=<id>` | Past updates with `total_progress`, `session_pages`, `date`, `reading_instance_id`, `link`. The user's 50% manual update shows `total_pages_read: null`, `session_pages: 240`: Pagebound derives pages from a percent update. |
| `GET /users/<username>/book_journey?book_uuid=<uuid>&page=1` | The journey feed (`activity_items`). Not used. |
| `POST /user_books` `{user_book: {status, book_id (numeric), edition_id, owned, muted}, shelf_ids: [], date, started_reading_at, finished_reading_at, challenge_year, format, tracking_mode, total_page_count, total_minutes}` | Adds a book to the library with a reading instance (bundle; ShelfSync). Not exercised. |
| `POST /user_books/<uuid>/update_status` `{status}` | Status only (bundle). Not exercised. |
| `PUT /user_books/<uuid>` `{user_book: {...}, status, date, started_reading_at, finished_reading_at, format, tracking_mode, total_page_count, ...}` | The site's "finish" is this with `status: "finished"`, `date`, `finished_reading_at`; the editions page sets `{user_book: {edition_id}}` alone. Not exercised. |
| `PUT /reading_instances/<id>` `{started_reading_at, format, tracking_mode, total_page_count, total_minutes}` | Edits the current instance. Not used (format is left alone). |
| `POST /reading_updates` `{reading_update: {user_book_id, date, total_progress, total_pages_read: null, reading_instance_id}, user_book: {current_page: null, total_page_count, current_minute: null, total_minutes: null}, no_broadcast: false, reading_update_id: null, progress_method: "percent"}` | Returns `user_book` and `reading_update.link`; invalidates the streak widget, so it counts for the streak. At 100% the site does not post an update; it finishes the book instead. Not exercised. |

Status values: `current`, `finished`, `dnf`, `paused`, `tbr`, `interested`, `none`. Formats: `print`, `audio`, `digital`, `tandem`. `tracking_mode`: `pages`, `minutes`; `progress_method`: `pages`, `percent`, `minutes`.

Not observed: every write's response, rate limits, whether `total_progress` below the current value is accepted, Cloudflare behaviour on POST from `HttpURLConnection` (the API sits behind Cloudflare on Render; plain GETs passed from `curl`). App Store listing: Pagebound: Social Book Tracker, id6751526412, by Pagebound LLC.

## Design

### Files (same shape as Fable; `AGENTS.md` code map)

- `PageboundAuth.kt`: `PAGEBOUND_FIREBASE_KEY`, `PAGEBOUND_TYPESENSE_KEY`, `PageboundHttp` (Firebase sign-in and refresh as `FableHttp`, `exchange(idToken)`, `get/post/put(token, path, body)` with `Bearer`, Typesense `search(q)`, 30 s connect / 90 s read on every Pagebound API call because Render cold starts hit the first call of a sync, 2 MiB limit, `HttpProblem(status, code, "pagebound")`), `PageboundAuth` (as `FableAuth` on `TokenVault(app, "pagebound")`: `OAuthTokens(access = Pagebound token, refresh = Firebase refresh token, expiresAt = Long.MAX_VALUE)`; because a rejected token is a 500 with an empty body, `authorized` refreshes Firebase and exchanges again once when `GET /auth/get_authed_user` answers 500 with an empty body, then retries; a second 500 is `pagebound_session_expired`; 401/403 are treated the same way in case the API changes; dead Firebase refresh codes clear the vault), `pageboundAccountId(user)` = `user.uuid`.
- `PageboundMatch.kt`: `pagebound:` tag (book UUID; `GET /books/<uuid>` must load), then each ISBN-13 and its ISBN-10 through `/search?type=ISBN` (evidence = `book_uuid`, edition id, format, pages), no ASIN, then one normalized title and author match among the first five Typesense hits (reuse `normalized`, `authorKey`, `authorNames` from `HardcoverMatch.kt`/`FableMatch.kt`), each hit confirmed by `GET /books/<uuid>` title and author. Evidence from different books → `pagebound_identifier_conflict`; several title matches → `pagebound_book_ambiguous`; none → `pagebound_book_not_found`. Several ISBNs that resolve to editions of one book are one match; the chosen edition is the one whose `format` is `Kindle` or `Ebook`, else the first in ISBN order (In the Blood: EPUB ISBN → paperback 1734779, NeoReader ISBN → Kindle 1679532, so Kindle). Result `{bookId (numeric), bookUuid, editionId?, editions: [ids], pages?, matchKind: edition|book, title, fingerprint, matchedAt}`, cached one hour under `pagebound.match.<digest>` as the others.
- `PageboundSync.kt`: policy below; `PAGEBOUND_PROGRESS_STEP = 5`; shared step helper (see Goodreads change).
- `PageboundConnection.kt`: as `FableConnection` (`awaitingCredentials`, `signingIn`, `signIn(email, password)`, `cancelSignIn`, `setEnabled`, `fetchProfile` from `/auth/get_authed_user` → `username`, `email`, `createdAt` = `preferences.created_at`, `membership` = `current_level` plus "Paid" when `paid_subscriber`; `signOut` clears the vault; Firebase has no revocation).
- `BookIdentifiers.kt`: `pagebound` tag accepting a UUID (`uuidPattern`), display label "Pagebound".
- `ReadingSyncApp.kt`: field, `connections` (after fable), construction, `recover()` list. `DiagnosticsStore` needs no change. `Export.kt` notes: Pagebound receives the whole percentage in steps of 5 through its app API; its tokens are never exported.
- `MainActivity.kt`: generalize `fableSignInDialog`/`updateFableSignIn`/`fableReady` into a shared email sign-in dialog parameterised by service (as `webSignInDialog` is shared), then `showPagebound`, `updatePageboundSignIn`, `pagebound_*` codes in `serviceIssues` and `connectionText`, the tag label, `resultFields` lines (status, format, edition, next update at), `ids.xml` entries. Row after Fable, before Margins. `ActivityLogAdapter.serviceName` needs no change ("Pagebound").
- `app/build.gradle.kts`: 0.7.0 / code 29. No new dependency.
- Icon: download the App Store artwork `https://is1-ssl.mzstatic.com/image/thumb/Purple211/v4/ca/43/18/ca43185f-41a0-a57c-e63c-d072dea0b2b7/AppIcon-0-0-1x_U007epad-0-1-85-220.png/512x512bb.jpg` unchanged to `app/src/main/res/drawable-nodpi/service_pagebound.jpg`; record listing, URL, path and SHA-256 in `docs/service-artwork.md`; concept colour row in `agents/design.md`.

### Sync policy (`PageboundSync.kt`)

1. `GET /auth/get_authed_user`; `user.uuid` must equal the stored account (`pagebound_account_changed`).
2. Match, then `GET /books/<bookUuid>`; `book.user_book` is the library entry (null = not in library).
3. Status: `finished` or `dnf` holds a reading source with `pagebound_status_conflict`; a finished source on `finished` reports `already_current`. `has_ever_finished` on any status other than `finished` with a reading source is a reread: hold with `pagebound_reread_held` (never start a reread). `paused` (a reading instance exists, so the user's format survives) → `POST /user_books/<uuid>/update_status {status: "current"}`. `tbr`, `interested`, `none` (no instance; `update_status` has no format field and would create a `print` instance) → `PUT /user_books/<uuid>` with `status: "current"`, `date: today`, `started_reading_at: today`, `format: "digital"`, `tracking_mode: "pages"`, `total_page_count: pages (edition, else book.page_count, else null)`, as the site's own status modal does. Not in library → `POST /user_books` with `user_book: {status: "current", book_id, edition_id (matched or null), owned: false, muted: false}`, `shelf_ids: []`, `date: today`, `started_reading_at: today`, `format: "digital"`, `tracking_mode: "pages"`, `total_page_count` as above. After every status write, re-read and require `status == "current"` and a `current_reading_instance` (`pagebound_shelf_not_applied`); when the app created the instance and its `format` is not `digital`, `PUT /reading_instances/<id> {format: "digital"}` once and re-read.
4. Edition: when `user_book.edition_id` is null and the match has an edition, `PUT /user_books/<uuid> {user_book: {edition_id}}`; `existingEditionPreserved` when a different edition is set. The row note compares `edition_id` with the match's `editions`.
5. Progress: `remote = user_book.progress`. `remote > percent` → `kept_higher_remote_progress` (⚠). `step = floor(percent / 5) * 5`. Post only when `step > remote`, whether or not the status changed; a status change with `step <= remote` returns `sent` with no reading update (so a fresh add at 3% posts nothing and never posts 0%); no status change with `step <= remote` → `already_current` with `nextUpdateAt = remote - remote % 5 + 5`. The post is `POST /reading_updates` with `total_progress: step`, `reading_instance_id: current_reading_instance.id`, `date: today`, `progress_method: "percent"`, `user_book.total_page_count` echoed, `no_broadcast: false`; confirm by `GET /user_books/<uuid>` and require `progress == step` (`pagebound_progress_not_applied`). The "posted" guard from `GoodreadsSync` (hold a value whose earlier post did not show) is reused because each post is public.
6. Finish (`readingStatus 2`, full fraction): `PUT /user_books/<uuid> {status: "finished", date: today, finished_reading_at: readingDay(book, readAt)}`; confirm `status == "finished"`. No reading update at 100%, as the site does.
7. Result JSON: `title`, `bookUuid`, `bookId`, `userBookUuid`, `editionId`, `matchedEditionId`, `matchKind`, `existingEditionPreserved`, `rawProgress`, `percent`, `step`, `remotePercent`, `nextUpdateAt`, `finished`, `statusBefore`, `statusAfter`, `format`, `outcome`.

### Goodreads step change

`GoodreadsSync.kt:106-110`: replace "percent - remote < step" with the same floor rule: post `step = floor(percent / 5) * 5` only when `step > remote`; a shelf change with `step <= remote` is `sent` without a post (so a shelf change at 3% no longer posts 3%); otherwise `already_current` with `nextUpdateAt = remote - remote % 5 + 5`. The posted guard and the read-back compare against `step`, not `percent`. Put the helpers (`stepPercent(percent)`, `nextStep(remote)`) in `FableSync.kt` next to `flooredPercent`, used by both. Update `GoodreadsTest` (`nextUpdateAt` expectations at lines 132 and 139), `AGENTS.md:33`, `README.md:39`, `docs/goodreads.md:29`, `docs/device-testing.md:36`, and `agents/product.md:33`.

### Tests

- `PageboundServer.kt`: one `MockWebServer` for the API, Firebase identity, secure token, and Typesense hosts (route by path as `FableServer` does), with switches `sessionValid`, `coldStartOnce`, `failProgressOnce`, `ignoreStatus`, `rejectFinish`; request recording and write helpers.
- `PageboundMatchTest`: tag, ISBN-13, ISBN-10 fallback, title with confirmation, conflict, ambiguity, cache.
- `PageboundTest`: sign-in exchange and token storage (no password stored, export has no token or email), empty-500 refresh-and-exchange retry and the second-500 expiry, add to library with `digital` and page count, existing instance format untouched, `tbr` moved through the PUT with `digital`, `paused` moved through `update_status`, step rule (14→10, 23→20, equal skipped with `nextUpdateAt`, fresh add at 3% posts nothing), kept higher, posted guard, finish, reread held on `tbr` and `current`, account change, edition set only when null and the Kindle edition preferred, Log out, screen row and popup, service issues.
- `GoodreadsTest`: the new step expectations.

### Docs

`docs/pagebound.md` (model `docs/fable.md`: caution block, Connect with the Google/Apple password note, Matching, Progress and status with the 5-step rule and feed note, Account details and log out, Problems and privacy), README table row and footnote ⁴ plus a provider paragraph and the privacy paragraph (add Goodreads there too; the 0.6.0 change missed it), `docs/service-artwork.md`, `docs/device-testing.md`; `AGENTS.md` (trackers bullet: Pagebound uses its app API by the user's decision, step rule for Pagebound and Goodreads, code map, `PageboundServer`), `agents/product.md` (decisions above), `agents/integrations.md` (summary row, research record above, implemented policy, ShelfSync note), `agents/research.md` (inspected-sources bullet), `agents/design.md`, `agents/project-status.md`, `agents/verification.md`, `agents/device-testing.md`, and the copied plan in `agents/`.

## Checklist

### Plumbing

- [x] Copy this plan to `agents/plan-20261008-pagebound-sync.md`; `app/build.gradle.kts` 0.7.0 / code 29; icon and `docs/service-artwork.md`.
- [x] `BookIdentifiers.kt` tag; `ReadingSyncApp.kt`; `Export.kt`.

### Auth and popup

- [x] `PageboundAuth.kt`, `PageboundConnection.kt`; shared email sign-in dialog in `MainActivity.kt`; row, popup, texts, `ids.xml`.
- [x] Tests for sign-in, refresh retry, cancel, Log out, no stored password.

### Match, sync, step rule

- [x] `PageboundMatch.kt` and tests.
- [x] Step helpers in `FableSync.kt`; `GoodreadsSync.kt` on the new rule; `GoodreadsTest` updated.
- [x] `PageboundSync.kt` and tests; `resultFields`, popup lines, `eventText`, UI tests.

### Docs and gates

- [x] Public and agent docs listed above.
- [x] `mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic`, ktlint, ruff, markdownlint, yamllint, actionlint, `package-artifacts.py`.
- [ ] `code-simplifier` and `code-reviewer` agents on the change; apply findings.

### Physical (GoColor7)

- [ ] Install 0.7.0 over 0.6.3 without data loss; the other four services stay connected.
- [ ] Sign in with email; the details popup shows the account; the first sync matches In the Blood by ISBN (Kindle edition 1679532 or UK paperback 1734779), sets the empty `edition_id`, and reports `already_current` at 52% because Pagebound holds 50% (next update at 55%).
- [ ] After reading past 55%: one post of 55% on the journey; format stays `digital`; the streak day is marked.
- [ ] Goodreads on the same book posts 55% at the same time (new rule).
- [ ] Finish on a test book: status Finished with today's date; a second sync reports `already_current`.
- [ ] Log out: tokens gone, export has no token or email.

## Verification

Gates in `AGENTS.md` must be green at zero failures. Device checks are recorded in `agents/verification.md` at the three levels (Built, Automatically tested, Verified on physical BOOX). The write endpoints are unexercised until the first device sync; record each response shape in `agents/integrations.md`.

## Risks

- All write calls are inferred from the site bundle and ShelfSync, not exercised. The first device sync is the verification.
- Render cold starts: a send may time out on the first request after idle. The queue retries; the exchange gets the long timeout.
- Cloudflare in front of the API could challenge non-browser clients on POST. None seen on GET. If it happens, the StoryGraph "challenge ends the session" rule applies and the plan is revisited.
- The Typesense key and the Firebase key are the site's own client keys; rotation breaks title matching or sign-in respectively, not ISBN matching.

## Notes

- The repository was at 0.6.3/code 28 when implementation started, so the version is 0.7.0/code 29.
- Fable's email sign-in moved into a shared `PasswordConnection` (end of `TrackerConnection.kt`) and a shared request helper `httpRequest` in `FableAuth.kt`; the popup in `MainActivity.kt` became one `passwordSignInDialog` for both services. Fable's behaviour and tests are unchanged apart from the draft field's name.
- Only the account request (`/auth/get_authed_user`, the first call of every send) can mark a token as rejected; an empty 500 or a 401/403 on any other request is an ordinary failure, so a write or search error never renews the session or repeats the send. A token refused again right after renewal clears the vault and holds with `pagebound_session_expired`, which the row shows as Reconnect required (code review).
- `preferences.created_at` is not the account's creation date (it was 2026-10-08 on an account with books from 2018), so the details popup shows no "Account created" line. Membership is Free or Paid subscriber; `current_level` is a points level, not a membership.
- Title search hits are not re-read through `/books/<uuid>`: the Typesense document is the catalogue record, and the sync reads the book page next anyway.
- The result uses the existing `shelfBefore`/`shelfAfter` keys instead of `statusBefore`/`statusAfter`, so the popup's Shelf line works unchanged. Goodreads and Pagebound add `posted`, the step they sent, which the Activity row and popup show instead of NeoReader's percentage.
- The app does not correct a created read whose format Pagebound did not store as `digital`: the write sends `digital`, and the result records the read's `format`. The first device sync verifies it.
- `PageboundMatch.kt` is covered inside `PageboundTest`; a separate `PageboundMatchTest` would repeat the same server. The ISBN-10 fallback has no separate test.
- The step constant is the shared `PROGRESS_STEP` with `stepPercent`, `nextStep`, and `belowNextStep` in `FableSync.kt`, used by Goodreads and Pagebound, instead of a separate `PAGEBOUND_PROGRESS_STEP`.
- Review findings applied with tests: a second refusal ends the session; a search failure is reported as `pagebound_search_http_<status>`, not as a session problem; an edition Pagebound does not keep is written once and reported in the popup; Fable and Pagebound record the reason when a send ends the session, so the row shows one issue instead of two (this also fixes Fable); membership is shown only when Pagebound reports it. Not changed: the posted guard is still cleared after any HTTP error, as for Goodreads, because the retry reads Pagebound's progress first and posts again only when the first post did not apply. The reread hold also covers a finished source on a book Pagebound shows as read before, by the rule that rereads are protected.
