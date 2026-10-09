# Margins sync

Approved 2026-10-09. Research done the same day in the user's signed-in browser session (read-only probes; no token, email, or account value is recorded). The research record is also in [integrations](integrations.md#margins).

## Context

Margins (margins.app, Paratext Inc.) is the last Coming Soon row. The user asked for a full tracker integration in the same shape as the five existing trackers: service row with icon, sign-in, automatic matching, progress and status writes, details popup, docs, and tests. The `help@margins.app` inquiry (2026-10-05) never got a reply, so the integration uses what the website ships.

Margins has no REST API. The website is Next.js on Vercel (its HTML is behind a Vercel bot checkpoint, HTTP 429 for curl; the app never fetches it). Sign-in is Supabase Auth with one-time codes only (no passwords). All data goes through a Rocicorp Zero sync server at `zero.margins.app` (sync protocol v51, custom "synced queries" and custom mutators defined in the site bundle). Supabase PostgREST answers 503 `PGRST002` on every table, GraphQL likewise, no Edge Functions, no Next.js API routes except `/api/health`: Zero is the only data path. The phone app `app.margins.margins` was not inspected (the supplied XAPK `com.margins.app` is an unrelated real-estate app; it sits untracked in the repo root and must not be committed).

Margins' terms (2026-07-11) §5 forbid bots and automated access and §4 forbid using book metadata outside Margins. **The user accepted this risk on 2026-10-09.** Public docs must say the connection is unofficial, undocumented, and can break.

## Decisions (user, 2026-10-09)

- Sign-in popup: email, then the 6-digit code (Supabase OTP with `create_user: false`, so a typo never creates an account). Only the Supabase access and refresh tokens are stored. No phone option.
- WebSocket client: add `com.squareup.okhttp3:okhttp:4.12.0`, the first production network dependency. MockWebServer (already a test dependency, same version) fakes the Zero server with `withWebSocketUpgrade`.
- Progress: the readthrough goes to `in_progress` as an ebook, then one `percentages` reading session per whole step of `PROGRESS_STEP` above the remote value (start = remote %, end = step), the Goodreads/Pagebound rule. Finish sets the readthrough `finished` with the last reading day. Rereads hold.
- Row order: Hardcover, Goodreads, StoryGraph, Fable, Pagebound, Margins.

## Research record

### Stack and auth (verified)

| Item | Verified behaviour |
| --- | --- |
| Supabase project | `https://dhepqjxbathvkcxyuorm.supabase.co`, publishable key `sb_publishable_f9S7rFZNdryEgO9lUr2O6g_ZwrRSas_` (shipped in the bundle; identifies the project, not a credential). Every auth call sends it as `apikey`. |
| `GET /auth/v1/settings` | `external.email` and `external.phone` true, `disable_signup` false, SMS via Twilio, no passkeys. No captcha library in the login chunks, and the OTP call sends `captcha_token: undefined`. |
| `POST /auth/v1/otp` `{email, create_user, data: {}, gotrue_meta_security: {}}` | What the site's `signInWithOtp` sends (`create_user` true on the site; the app sends false). Not exercised; the first device sign-in verifies it, including the error for an unknown email. |
| `POST /auth/v1/verify` `{type: "email", email, token}` | Returns `{access_token, token_type, expires_in, expires_at, refresh_token, user}`; `user.id` is the account id. Not exercised. |
| `POST /auth/v1/token?grant_type=refresh_token` `{refresh_token}` | Standard GoTrue refresh; refresh tokens rotate, so the vault stores the new pair every time. |
| `GET /auth/v1/user` (Bearer) | `{id, email, created_at, last_sign_in_at, app_metadata.providers, ...}`. Verified. |
| `POST /auth/v1/logout?scope=global` (Bearer) | Site's sign-out; best effort on Log out. Not exercised. |
| Access token | ES256 JWT with `kid`, lifetime **604800 s (7 days)**, claims `sub` (user id), `email`, `role: authenticated`, `amr: [{method: otp}]`. |
| `https://zero.margins.app/` and `/keepalive` | 200 `OK` from curl (no bot challenge; a `_4e9a0` cookie is set and can be ignored). `GET /sync/v51/connect` without upgrade is a JSON 404. |

### Zero sync protocol (verified from the browser with a self-made client group)

Protocol source: `packages/zero-protocol/src/*.ts` in `rocicorp/mono` (client protocol version 51; zero-cache 1.9 accepts up to 51; protocol 52 switches to binary poke chunks, so the app must send `v51` and never the `f` flags parameter).

1. Connect `wss://zero.margins.app/sync/v51/connect?clientID=<c>&clientGroupID=<g>&userID=<sub>&baseCookie=&ts=<ms>&lmid=<n>&wsid=<r>&profileID=<r>` with header `Sec-WebSocket-Protocol: encodeURIComponent(base64(utf8(JSON {"authToken": "<access token>"})))`. The server echoes the protocol.
2. Receive `["connected", {wsid}]`. Send `["initConnection", {desiredQueriesPatch: [{op: "put", hash, name, args, ttl: 60000}, ...], clientSchema: {tables: {...}}, activeClients: [clientID]}]` as the first text frame (the site sends it in the header when it fits in 8 KB; the message form works and keeps headers small).
3. `clientSchema.tables` is keyed by **server table name** (`catalog.works`, `userspace.readthroughs`, ...) with `{columns: {name: {type}}, primaryKey: [...]}`; types `string | number | boolean | json`. Every table must be replicated or the server answers `["error", {kind: "SchemaVersionNotSupported", message}]` naming the replicated tables and closes (`public.languages`, `public.notification_*` are not replicated). A subset is fine. Hold on this error, never retry.
4. The `hash` is an opaque client-chosen id (the server echoed `probe-isbn-uk`). Custom query `args` is an array: `[arg]` or `[]`.
5. Answers arrive as `pokeStart` / `pokePart` / `pokeEnd` (JSON): `pokePart.gotQueriesPatch` lists `{op: "put", hash}` for answered queries and `{op: "del", hash}` for failed ones; `rowsPatch` rows are `{op: "put", tableName: "<server name>", value: {...}}`; `desiredQueriesPatches`, `lastMutationIDChanges`, `mutationsPatch` also appear. Validation failures arrive as `["transformError", [{error: "app", id: <hash>, name, message, details}]]`. A connection is complete when every requested hash is in a got patch or an error. The server sends `["pong", {}]` and expects `["ping", {}]` on long connections; the app's connections are short.
6. Push: `["push", {clientGroupID, mutations: [{type: "custom", id: <n>, clientID, name, args: [payload], timestamp}], pushVersion: 1, requestID, timestamp}]`; expect `["pushResponse", {mutations: [{id: {clientID, id}, result: {} | {error: "app", message, details} | {error: "alreadyProcessed"}}]}]` or `["error", {kind: "PushFailed" | "InvalidPush" | "MutationRateLimited", ...}]`. Mutation ids are per `clientID`, monotonic from 1; `lmid` in the URL is the last confirmed id for that client. **Not exercised: the browser probe of a push with an empty payload was blocked by the session's permission classifier.** The first implementation step is a spike (below).
7. Custom mutators run server-side with `ctx.subject.authenticatedUserId` from the token; input is validated (Effect Schema, strict structs) before anything runs, so a bad payload is a `transformError`-style app error and no write.

### Catalogue and library model (verified rows)

Server tables and columns the app needs (from the bundle's `createSchema`; keep this map in `MarginsZero.kt`):

- `catalog.works` (`work_id`, `original_title`, `original_language`, `original_publication_date`, `num_pages`, `num_seconds`, `num_locations`, goodreads rating counts), `catalog.work_titles` (`title_id`, `work_id`, `title`, `subtitle`, `language`, `is_primary`), `catalog.work_contributors` (`work_contributor_id`, `work_id`, `contributor_id`, `position`), `catalog.contributor_names` (`name_id`, `contributor_id`, `name`, `language`, `is_primary`), `catalog.contributors` (`contributor_id`, `num_works`), `catalog.work_covers` (`cover_id`, `work_id`, `url`, `is_primary`, `width_px`, `height_px`, `background_color_hex`).
- `catalog.work_isbn13s` (`isbn13` hyphenated, `work_id`, `cover_id`), `catalog.work_asins` (`asin`, `work_id`), `catalog.goodreads_edition_ids` (`goodreads_edition_id` number, `goodreads_work_id`, `work_id`), `catalog.goodreads_work_ids` (`goodreads_work_id`, `work_id`).
- `userspace.works` (client name `user_works`: `user_id`, `work_id`, `position`, `is_owned`, `is_private`, `preferred_cover_id`, `created_at`), `userspace.readthroughs` (`readthrough_id`, `user_id`, `work_id`, `status`, `is_audio`, `is_ebook`, `is_print`, `touched_at` ms, `num_pages`, `num_locations`, `num_seconds`, `rating`, `preferred_cover_id`, `start_date`, `end_date` (`YYYY-MM-DD`, `Unknown`, or null), `review`, `notes`, `is_private`, `created_at`, languages), `userspace.reading_sessions_v2` (client name `reading_sessions`: `reading_session_id`, `user_id`, `readthrough_id`, `session_date` ms at UTC midnight, `session_utc_offset_in_seconds`, `start_time`, `created_at`, `seconds_read`, `start_position`, `end_position`, `progress`, `unit`, `is_audio`, `is_ebook`, `is_print`, `is_private`, `pages_equivalent`, `time_equivalent_in_seconds`), `userspace.reading_day_activity`, `userspace.library_summary`, `userspace.profiles` (`user_id`, `display_name`, `joined_at`, `created_at`, `pfp_url`, counts), `userspace.want_to_read`, `userspace.social_connections`.

Readthrough `status`: `unread`, `want_to_read`, `in_progress`, `finished`, `stopped`. Session `unit`: `pages`, `kindle_locations`, `percentages`, `audiobook_seconds_remaining`, `audiobook_seconds_listened`. Margins' own arithmetic (`readingSessionEquivalents`): pages for a `percentages` session = `pageCount × progress/100` rounded half-even to hundredths; time = `pages × 60` seconds.

Queries (custom, by name; validators from the bundle):

| Query | Args | Verified result |
| --- | --- | --- |
| `workByISBN13` | `[string]`; ISBN-10 (`139850825X`), plain and hyphenated ISBN-13 all accepted (server normalizes) | One `catalog.work_isbn13s` row plus its cover. Both ISBNs of In the Blood (UK 9781398508255, US 9781982181680) → work `6c4f76ff-8b23-4b27-aa56-a128f600768f`. The placeholder `9780000000002` also returned a row (some record carries it), so ISBN evidence is not infallible. |
| `workByASIN` | `[string]` | `B07THCSQ27` → work `f8d67efb-8c95-4fd2-9366-7b67d7181a78` (Savage Son). |
| `workByGoodreadsEditionID` / `workByGoodreadsWorkID` | `[number]` | 58438630 → In the Blood's work; its `goodreads_work_id` 91709220 likewise. |
| `workById` | `[string work id]` (or `{id, locales}` structs) | `catalog.works` with titles, descriptions, covers, contributors and names, series, tags. |
| `libraryReadthroughs` | `[userId]` (a UUID; `[]` fails validation) | All the user's readthroughs ordered by `touched_at`, each with its work (covers, primary titles, contributors) and up to 50 newest `reading_sessions`. 41 rows for the user. |
| `libraryBooks` / `libraryWorks` | `[userId]` | `userspace.works` rows with readthroughs. |
| `librarySummary`, `profile`, `homeSignedInUser` | `[userId]` | `library_summary` and `profiles` rows. |
| `libraryMembershipReadthroughs` | `[{userId, workIds: [...]}]` | The readthroughs of the listed works only, each with its reading sessions (verified on a finished read with one session). The per-work read the sync uses. |
| Client group reuse | same `clientGroupID`, new `clientID`, with or without `clientSchema` | Verified: a second and third connection to one group answered; the group's earlier desired queries stay registered and are answered again, so the app uses fixed hashes per query kind (replaced on each sync) and `del` ops for hashes it no longer wants. |
| 18-table `clientSchema` subset | the tables listed above | Verified with `workById`, `profile`, `libraryMembershipReadthroughs`, `workByISBN13`: the server still streams rows of tables outside the subset (descriptions, series, tags); the client ignores tables it does not know. |

No title or author search query exists in the Zero query list (catalogue text search is a native feature of the phone app), so there is no title/author fallback.

Mutators (custom, by flat name; payload is `args[0]`; `touchedAt`/`createdAt` are ISO instants, `todayDate`/`sessionDate` are `YYYY-MM-DD`, ids are client-generated UUIDs):

| Mutator | Payload (strict) | Server behaviour (from the bundle) |
| --- | --- | --- |
| `librarySetReadthroughStatus` | `{workId, readthroughId, newReadthroughId, status: unread\|want_to_read\|in_progress\|finished\|stopped, startsNewReadthrough, isInLibrary, touchedAt, todayDate, readDateMode?: today\|unknown}` | With `startsNewReadthrough` it adopts an existing `unread`/`want_to_read` row or inserts `newReadthroughId` (adding `userspace.works` when `isInLibrary` is false); `in_progress` sets `start_date` to `todayDate`; `finished` on an `in_progress` read sets `end_date`, and when the latest session has an `end_position` it inserts a closing session to the end of the book itself; it throws when another in-progress read of the work began earlier or when the start date is after today. |
| `libraryAddReadingProgress` | `{id, sessionDate, sessionUtcOffsetInSeconds, startTime: null, secondsRead: null, readthroughId, startPosition, endPosition, progress, unit?: percentages\|pages\|…, isAudio, isEbook, isPrint, pagesEquivalent, timeEquivalentInSeconds, createdAt, readthroughUpdate?: {isAudio, isEbook, isPrint, pageCount, locationCount, audiobookDuration}, sessionToTrim?}` | Inserts the `reading_sessions` row as given, updates the readthrough's format flags and counts from `readthroughUpdate`, touches it, and updates `reading_day_activity`. Throws when the readthrough is missing. |
| `markReadthroughStatus`, `addReadthroughs`, `updateReadthroughs`, `updateReadthroughDates` | Batch forms | Not used. |

Not observed: any push response, rate limits (`MutationRateLimited` exists), the OTP error for an unknown email, token lifetime on refresh.

## Design

### Files (`AGENTS.md` code map; same four-file shape plus the protocol client)

- `MarginsAuth.kt`: `MARGINS_SUPABASE_URL`, `MARGINS_SUPABASE_KEY`; `MarginsHttp` (`requestCode(email)` → `/auth/v1/otp` with `create_user: false`; `verify(email, code)` → `/auth/v1/verify`; `refresh(token)`; `user(token)`; `logout(token)`; all through `httpRequest` from `FableAuth.kt` with `apikey`, errors as `HttpProblem(status, <error_code from the JSON>, "margins")`); `MarginsAuth` on `TokenVault(app, "margins")` with `OAuthTokens(access, refresh, expiresAt from expires_at)`: `authorized { token, userId }` refreshes within 60 s of expiry or on a Zero `Unauthorized`/`AuthInvalidated` error once; dead refresh (`refresh_token_not_found`, `invalid_grant`) clears the vault → `margins_session_expired`. Account id = JWT `sub` (decode the payload; no signature check needed locally).
- `MarginsZero.kt`: the protocol client on OkHttp: `MarginsZero(url, http: OkHttpClient)` with `query(token, userId, clientGroupId, queries: List<ZeroQuery>): ZeroRows` (connect, init, collect rows per hash until every hash is got or errored, close) and `push(token, userId, clientGroupId, clientId, mutationId, name, payload): JSONObject` (connect with the same desired queries empty, push, await `pushResponse`, close); `MARGINS_CLIENT_SCHEMA` constant with the tables above; `SyncProblem` codes `margins_schema_changed` (SchemaVersionNotSupported, held), `margins_protocol_unsupported` (VersionNotSupported), `margins_query_failed_<name>` (transformError), `margins_push_rejected` (app error, with the message in the result), `margins_push_failed` (PushFailed/InvalidPush, retryable), `margins_rate_limited` (retryable). The `clientGroupID` is persisted per account and schema (`margins.clientGroup.<account>`, stored with a digest of `MARGINS_CLIENT_SCHEMA`; a schema change in an update starts a new group) so the server keeps one CVR; each sync uses a fresh `clientID` (lmid 0, mutation ids from 1) and fixed query hashes (`match-isbn-<n>`, `read`, ...) with `del` ops for hashes it no longer wants. Timeouts 15 s connect, 30 s read; a socket closed without an answer is an `IOException` (retried by the queue).
- `MarginsMatch.kt`: `margins:` tag (tighten `BookIdentifiers.kt` to `uuidPattern`; `workById` must return the work), then every ISBN-13 and ISBN-10 through `workByISBN13`, then `goodreads:` edition ids through `workByGoodreadsEditionID`, then ASIN through `workByASIN` only without ISBN or Goodreads evidence. All evidence must name one `work_id` (`margins_identifier_conflict` otherwise); nothing → `margins_book_not_found`; no title fallback (no search query). Then `workById` for the primary title, contributors and `num_pages`. Result `{workId, title, author, pages?, isbn13s: [...], matchKind: edition|book, fingerprint, matchedAt}`, cached one hour under `margins.match.<digest>` as the others. One Zero connection serves all match queries (distinct hashes).
- `MarginsSync.kt`: policy below, reusing `sourceStatus`, `readingDay`, `stepPercent`, `nextStep`, `belowNextStep` from `FableSync.kt`/`ReadingSyncApp.kt`.
- `MarginsConnection.kt`: a new `CodeConnection` abstract class next to `PasswordConnection` in `TrackerConnection.kt`: `awaitingEmail` → `requestCode(email)` → `awaitingCode` → `verify(code)`; Cancel/Back and Off cancel; a rejected email keeps the email step with the reason, a rejected code keeps the code step; `fetchProfile` → `name` = `profiles.display_name` (via the `profile` query), `email` and `createdAt` from `/auth/v1/user`; `signOut` posts logout best effort, clears the vault and the client-group id.
- `MainActivity.kt`: `codeSignInDialog(service)` modelled on `passwordSignInDialog` (email field + Send code; then code field + Sign in; status line), `showMargins`/`updateMarginsSignIn`, `margins_*` codes in `serviceIssues` and `connectionText`, `resultFields` lines (readthrough status, unit, session posted), `ids.xml` entries `margins_email`, `margins_code`, `margins_sign_in_status`. The row replaces the Coming Soon row; remove `coming_soon` from `strings.xml` when nothing else uses it.
- `ReadingSyncApp.kt`: field, `connections` after pagebound, construction, `recover()`. `Export.kt` notes: Margins receives progress as percentage reading sessions in whole steps; its tokens are never exported. `DiagnosticsStore` unchanged.
- `app/build.gradle.kts`: 0.8.0 / code 30 (the repository is at 0.7.0 / code 29); `implementation("com.squareup.okhttp3:okhttp:4.12.0")`. Check the merged manifest and lint after adding it (OkHttp adds no permissions).
- Icon: `service_margins.jpg` already exists (`docs/service-artwork.md`); keep it.

### Sync policy (`MarginsSync.kt`)

1. `auth.authorized`: fresh token; the JWT `sub` must equal the stored account (`margins_account_changed`).
2. Match (cached), then one Zero query connection: `libraryMembershipReadthroughs({userId, workIds: [workId]})` for the work's readthroughs with their sessions. Membership in `userspace.works` is not needed (`isInLibrary: false` lets the server look it up). `libraryReadthroughs(userId)` pulls the whole library (41 reads, 62 works with covers for this account) and is not used.
3. Status: pick the `in_progress` readthrough of the work if one exists. Otherwise, if any readthrough is `finished` or `stopped`: a finished source on a `finished` read → `already_current`; a reading source → `margins_reread_held` (rereads are protected). Otherwise (none, `unread`, `want_to_read`): `librarySetReadthroughStatus` with `status: in_progress` (or `finished` for a finished source), `startsNewReadthrough: true`, `readthroughId` = the unread/want-to-read row or `newReadthroughId`, `newReadthroughId` = new UUID, `isInLibrary: false` always (true makes the mutator throw when the `userspace.works` row is missing; false looks it up and inserts only when absent), `touchedAt` now, `todayDate` = `readingDay`, `readDateMode: today`; re-query and require the status (`margins_status_not_applied`).
4. Progress: `remote` = the newest session (by `session_date`, `created_at`) with an `end_position`: `percentages` → `end_position`; `pages` with a known page count (readthrough `num_pages`, else `works.num_pages`) → `floor(end × 100 / pages)`; `kindle_locations` with `num_locations` likewise; audiobook units, or pages/locations without a count → hold `margins_progress_unit_unsupported` (⚠ in the popup). No session → 0. `remote > percent` → `kept_higher_remote_progress` (⚠). `belowNextStep`/`stepPercent` as Goodreads and Pagebound. Post `libraryAddReadingProgress` with `startPosition: remote`, `endPosition: step`, `progress: step − remote`, `unit: percentages`, `isEbook: true`, `pagesEquivalent` = pages × progress/100 rounded half-even to hundredths when pages are known (else null), `timeEquivalentInSeconds` = `floor(pagesEquivalent × 60)` (else null), `sessionDate` = `readingDay`, `sessionUtcOffsetInSeconds` from the device zone, `readthroughUpdate: {isEbook: true, isAudio: false, isPrint: false, pageCount: pages or null, locationCount: null, audiobookDuration: null}`. Posted guard as Goodreads/Pagebound (`margins.book.posted.<readthrough>`), then re-query and require a session with `end_position == step` (`margins_progress_not_applied`).
5. Finish (`readingStatus 2`, full fraction): `librarySetReadthroughStatus` with `status: finished`, `startsNewReadthrough: false`, `readthroughId` = the in-progress read, `newReadthroughId` = new UUID (the server uses it for its closing session), `todayDate` = `readingDay`; no 100% session of our own; confirm `finished` on re-query.
6. Result JSON: `title`, `workId`, `readthroughId`, `matchKind`, `rawProgress`, `percent`, `step`, `remotePercent`, `remoteUnit`, `nextUpdateAt`, `finished`, `shelfBefore`, `shelfAfter`, `posted`, `outcome`, `finishDate`.

### Tests

- `MarginsServer.kt`: one `MockWebServer`: Supabase paths (`/auth/v1/otp`, `/verify`, `/token`, `/user`, `/logout`) and `/sync/v51/connect` answered with `MockResponse().withWebSocketUpgrade(listener)`; the listener checks the `Sec-WebSocket-Protocol` token, parses `initConnection`/`changeDesiredQueries`/`push`, answers queries from in-memory fixtures (`works`, `isbn13s`, `asins`, `goodreadsEditions`, `readthroughs`, `sessions`, `profile`) as `pokeStart`/`pokePart`/`pokeEnd` with `gotQueriesPatch`, applies the two mutators to the fixtures (adopt/insert readthrough, append session, finish), records every push, and has switches `unknownEmail`, `wrongCode`, `invalidRefresh`, `schemaRejected`, `authInvalidOnce`, `pushAppError`, `ignoreProgress`, `ignoreStatus`, `rateLimitedOnce`, `dropOnce`.
- `MarginsTest`: code sign-in (email step, code step, wrong code keeps the popup, cancel, no password or code stored, export has no token or email), refresh on expiry and on `AuthInvalidated`, dead refresh → Reconnect required, match by ISBN-13, ISBN-10, Goodreads edition, ASIN, conflict, not found, cache, new readthrough from none and from want-to-read, in-progress reuse, finished/stopped reread hold, step rule (14→10, 23→20, equal skipped with `nextUpdateAt`, fresh add at 3% posts nothing), remote in pages converted, audio unit held, kept higher, posted guard, finish without a session, schema change held, push app error held, rate limit retried, client group persisted and client id fresh per sync, account change, Log out, screen row and popup, service issues.
- `PageboundTest` row-order assertion (`:314-316`) and `MainActivityTest` margins tag stay valid; `BookIdentifiersTest` changes for the UUID rule.

### Docs

`docs/margins.md` (model `docs/pagebound.md`: caution block naming the terms, sign-in with the code, matching incl. "no title fallback", progress rule and the social sessions note, account details and log out, problems and privacy), README table row (Enabled⁵, 0.8.0) and footnote, provider and privacy paragraphs, `docs/service-artwork.md` (connection now implied), `docs/device-testing.md`, `docs/hardcover.md:24` tag note; `AGENTS.md` (trackers bullet: Margins uses its Zero sync protocol and Supabase OTP by the user's decision, UUID tag, step rule list, code map with `MarginsZero.kt`, `MarginsServer`, OkHttp note), `agents/product.md` (decisions and acceptance), `agents/integrations.md` (summary row, this research record, implemented policy), `agents/research.md`, `agents/design.md` (code popup), `agents/project-status.md`, `agents/verification.md`, `agents/device-testing.md`, `CONTRIBUTING.md:3`.

## Checklist

### Spike (first)

- [ ] From the user's signed-in browser session, with the user's approval, push `libraryAddReadingProgress` with `args: [{}]` (fails validation, writes nothing) and record the exact `pushResponse`/`error` shape in `agents/integrations.md`. This session's permission classifier denied that probe; if it is denied again, the user can run it from their own DevTools, or the first device sync verifies the push path as it did for Pagebound. If pushes are refused for non-browser clients, stop and report.

### Plumbing

- [x] Copy this plan to `agents/plan-20261009-margins-sync.md`; `app/build.gradle.kts` 0.8.0 / code 30 and the OkHttp dependency; lint and merged-manifest check.
- [x] `BookIdentifiers.kt` UUID rule; `ReadingSyncApp.kt`; `Export.kt`.

### Auth and popup

- [x] `MarginsAuth.kt`, `CodeConnection` in `TrackerConnection.kt`, `MarginsConnection.kt`, `codeSignInDialog` in `MainActivity.kt`, row, popup, texts, `ids.xml`.
- [x] Tests for both sign-in steps, refresh, cancel, Log out, nothing stored but tokens.

### Protocol, match, sync

- [x] `MarginsZero.kt` and `MarginsServer.kt` with the WebSocket fake.
- [x] `MarginsMatch.kt` and tests.
- [x] `MarginsSync.kt` and tests; `resultFields`, popup lines, `eventText`, UI tests.

### Docs and gates

- [x] Public and agent docs listed above.
- [x] `mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic`, ktlint, ruff, markdownlint, yamllint, actionlint, `package-artifacts.py`.
- [x] `code-simplifier` and `code-reviewer` agents on the change; apply findings.

### Physical (GoColor7)

- [x] Install 0.8.0 over 0.7.0 without data loss; the other five services stay connected.
- [x] Sign in with the email code; the details popup shows the account; the first sync matches In the Blood by ISBN to work `6c4f76ff…`, creates or adopts the readthrough as in progress, and posts the current step as a percentages session (the library's in-progress read has no sessions, so remote is 0).
- [ ] After the next step: one more session from the previous step; the profile day activity shows it.
- [ ] Finish on a test book: readthrough finished with today's date and Margins' own closing session; a second sync reports `already_current`.
- [x] Log out: tokens gone, export has no token or email.

## Verification

Gates in `AGENTS.md` must be green at zero failures. Device checks go to `agents/verification.md` at the three levels (Built, Automatically tested, Verified on physical BOOX). The push path is unexercised until the spike or the first device sync; record every response shape in `agents/integrations.md`.

## Risks

- The push path is inferred from the protocol source and the bundle; the spike or the first device sync verifies it. If zero-cache rejects pushes from this client, the integration becomes read-only and the plan is revisited.
- Protocol drift: a Zero upgrade past protocol 51 on the server, or a schema change, ends the integration with `margins_schema_changed`/`margins_protocol_unsupported` held and visible; the client schema subset limits exposure to the tables used.
- ISBN evidence is not infallible (a placeholder ISBN returned a work); two ISBNs on different works already hold, and the popup shows the matched title.
- OTP delivery limits and `MutationRateLimited` are unknown; both are reported, never retried in a loop.
- OkHttp is the first production dependency; it is MIT-compatible (Apache 2.0) and already present in tests.

## Notes

- The empty-payload push spike was not run: the session's permission classifier refused it. The push path stays unexercised until the user runs it or the first device sync does.
- Query hashes are derived from the query name and arguments (as Zero's own client does) instead of fixed hashes with `del` ops, because a reused hash with new arguments is not known to be re-evaluated by the server.
- `CodeConnection` extends `PasswordConnection`: the shared sign-in job became `signInStep`, used by the password sign-in, the code request, and the code check. `setEnabled` became open so the code step resets on every switch change.
- `workById` runs in the sync's read connection together with `libraryMembershipReadthroughs`, not in the match connection, so a cached match costs one connection per sync before any write.
- Margins tracks works, not editions: `matchKind` is always `book`, the result adds `matchedBy` (`tag`, `isbn`, `goodreads`, `asin`), the popup shows "Book, by its ISBN" without an Edition line, and the row has no edition note.
- `readthroughUpdate` is sent only for a read without a format, so a read the user marked print or audiobook keeps its format and its page, location, and duration counts.
- A book without any identifier holds with `margins_identifier_missing` before any request.
- Log out uses `scope=local`, so only this device's Margins session ends.
- The WebSocket request sends `Origin: https://margins.app`, as Margins' website does (user decision after the first build); the supplied `app.margins.margins` XAPK is a React Native app whose compiled bundle names `zero.margins.app` but does not show its payloads as text.
- The sign-in popup does not repeat that Margins has no public API (user request); the public docs keep that caution.
- The matcher class is `MarginsMatch` because ktlint's filename rule requires a single-class file to be named after its class.
- Code review findings applied with tests: the posted-step mark is set just before the session leaves the device and cleared after a refused or rate-limited push, so an Off or a failure before sending no longer holds the step; a failed or dropped push after sending stays uncertain and holds on retry; progress in pages or Kindle locations counts only against the read's own count, because the work's count may be another edition's; the wait for the server is interruptible; a successful push response also waits for the confirming poke within the deadline; a cancelled poke is ignored during a push; a group reset after the push left the device is not retried; Change email runs off the main thread; the email is forgotten once the code is accepted. `readthroughUpdate` now sends the same page count as `pagesEquivalent`.
