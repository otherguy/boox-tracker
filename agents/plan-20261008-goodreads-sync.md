# Goodreads sync

Approved 2026-10-08. The research record is also recorded in [integrations](integrations.md#goodreads). The implementer re-checks `git status` first: another session committed in this repository while this plan was researched.

## Context

Goodreads is the last "Coming Soon" tracker with a brand mark in the app. Goodreads closed its public API in 2020. The reference client [Lyfts/ShelfSync](https://github.com/Lyfts/ShelfSync) (MIT) replays website requests with a cookie header the user copies from a desktop browser, says Goodreads cannot read progress back, and needs a self-hosted Chrome container to survive the AWS WAF bot challenge. This plan removes all three limits:

- The user signs in on Goodreads' own site in an in-app WebView, as with StoryGraph. The app reads the cookie header from the WebView cookie store at request time (`StoryGraphAuth.kt:52` already does this). No cookie is copied or exported.
- Goodreads' classic Rails pages do expose progress: the home page's Currently Reading widget, the review timeline, and the status list (verified today, see Research).
- The AWS WAF challenge is JavaScript that a real browser engine solves. The app loads goodreads.com in a hidden WebView on a challenge and every 6 hours while online, so the session and the `aws-waf-token` cookie stay fresh without a desktop.

## Decisions (user, 2026-10-08)

- Goodreads uses its website through a browser session, like StoryGraph; public docs must say so. Sign-in is Goodreads' own page in the popup.
- A WAF challenge is solved by a hidden WebView load, then the request is retried once. This is a Goodreads-only exception to the StoryGraph rule that a challenge ends the session; record it in `AGENTS.md` and `agents/product.md`.
- A separate network-constrained periodic WorkManager job refreshes the session every 6 hours while Goodreads is connected.
- Each WebView service gets its own cookie store through `androidx.webkit` multi-profile (`ProfileStore`); Log out clears only that profile. StoryGraph stays on the default profile.
- Progress is written only when it moved at least 5 percentage points past what Goodreads holds, or the status changes, or the book is finished. Every write creates a public status update, so this throttle is a Goodreads-only rule.
- An edition the user already shelved of the same work receives the progress and keeps its edition. Read and Did Not Finish hold. To-read or unshelved is moved to Currently Reading.
- Finish: shelve as Read, then set the finish date through the Next.js review editor's Server Action as ShelfSync does. The date step is best effort: its failure never fails the shelf write.
- Version 0.6.0 / code 25.

## Research record (verified 2026-10-08 in the user's signed-in session; no account values recorded)

Every GET below was a page read; nothing was written. Replace `<uid>`, `<bookId>`, `<workId>`, `<reviewId>` with the real ids at runtime.

| Page | Verified content |
| --- | --- |
| `GET /user/sign_in` (unauthenticated, plain `curl` with a browser User-Agent) | 200, no WAF challenge. Sets `ccsid`, `locale`, HttpOnly `_session_id2`. Email, Amazon and Apple sign-in links all point at `https://www.goodreads.com/ap/signin?…` (Amazon's auth pages on the goodreads.com host); the return URL is `/ap-handler/sign-in`, then `/`. Google and Facebook leave the host. |
| `GET /` signed in | `<meta name="csrf-token">`; nav links `/user/show/<uid>-<name>` (first one is the viewer) and `/user/sign_out?ref=nav_profile_signout`; `<section class="currentlyReadingShelf">` server-rendered: per book `a[href="/book/show/<bookId>-slug"]`, `.gr-book__title`, `.gr-book__author`, and when progress exists `div.gr-progressBar[aria-label="Reading progress: 240/480 (50%)"]` with `<progress value="240" max="480">` and a `/user_status/show/<id>` link. Without progress only an "Update progress" button. The signed-out `/` is a landing page with none of this. |
| `GET /` cookie names visible to JS after sign-in | `ccsid, locale, session-id, session-id-time, ubid-main, session-token, x-main, lc-main, csm-hit, id_pk, id_pkel, likely_has_account, logged_out_browsing_page_count`. HttpOnly ones (`at-main`, `sess-at-main`, `_session_id2`, `srb_*`) are not visible to JS; `CookieManager.getCookie` returns them (the StoryGraph capture relies on the same for `_storygraph_session`). No `aws-waf-token` was set in this session, so the challenge is not issued on every visit. |
| `GET /review/list/<uid>?shelf=currently-reading` (own account) | Classic table `#books tbody tr#review_<reviewId>`: `td.cover div[data-resource-id=<bookId>]`, `td.title a[href="/book/show/<bookId>-slug"]` (title plus `<span class="darkGreyText">(Series, #n)</span>`), `td.author`, hidden `td.isbn`, `td.isbn13`, `td.asin`, `td.num_pages` (`480 <span>pp</span>`), `td.shelves #shelfList<uid>_<bookId>` with one `a.shelfLink` per shelf (the exclusive shelf name is among them) and `shelfChooser.summon(event, {bookId, chosen: ["currently-reading"]})`, `td.date_started .date_started_value`, `td.date_read` (`not set` or a date) with `reading_session_id`, `td.read_count`, `td.format a[href="/work/editions/<workId>"]`, `td.actions a[href="/review/show/<reviewId>"]`. `search[query]=<text>` filters the list by title; `shelf=all` lists every shelf. |
| `GET /review/show/<reviewId>` | `.readingTimeline` rows `<div class="readingTimeline__text">` such as `September 14, 2026 – <span> Started Reading </span>`, `October 8, 2026 – Shelved`, `… – Shelved as: <a>to-read</a>`, `… – Finished Reading`, and progress rows `October 8, 2026 – <div class="u-inlineBlock"> page 240 </div> … <a href="…/user_status/show/<id>">50.0%</a>` (a percent-only update shows just the link, e.g. `18.0%`). Each row ends with an HTML comment. |
| `GET /user_status/list/<uid>` | Newest first, 30 per page: `span.user_status_header` "`<name>` is on page 240 of 480 of `<a href="https://www.goodreads.com/book/show/<bookId>-slug">`" or "is 52% done with …", then a `/user_status/show/<id>` link with the time. |
| `GET /book/show/<bookId>` (Next.js) | `<script type="application/ld+json">` Book with `name`, `isbn` (13 digits), `numberOfPages`, `bookFormat`, `author[].name`. The Apollo cache script (`getBookByLegacyId`, ~90 KB) has `viewerShelvings` (empty array when unshelved, otherwise refs), `viewerShelvingsUrl: "/review/user_works/<workId>"`, `editions.webUrl: "…/work/editions/<workId>"`, and `work.__ref`. |
| `GET /review/user_works/<workId>` | Classic. `Editions in My Books of '<title>'`: `table.tableList tr[itemtype=…/Book]` per shelved edition with `a.bookTitle[href="/book/show/<bookId>-slug"]`, `span[itemprop=name]`, the format, the status as a "View shelf" link `a[href="/review/list/<uid>?shelf=<name>"]`, and `/review/edit/<bookId>`. Empty table when no edition of the work is shelved. The page's inline script shows the shelf write: `POST /shelf/add_to_shelf` with `book_id`, `name`, `a` (`""` to add, `remove` to remove) and `authenticity_token`. |
| `GET /search?q=<isbn13>` | Next.js results, `[data-testid="book-item-title"] a[href="/book/show/<bookId>"]` and `[data-testid="name"]` for the author. An exact ISBN gave one result for the matching edition; a Kindle ASIN gave none, so ASIN is not a Goodreads search key. ShelfSync reports a `Location` header on exact hits; handle both. A title query lists one representative edition per work and omnibus sets. |
| `GET /review/edit/<bookId>` (Next.js) | Flight data with `viewerShelving.shelf.name`, `readingSessions[{id, bookId (kca), startedDate{year,month,day}, endedDate, state: READING or COMPLETED}]`; 28 `/_next/static/chunks/*.js` scripts; the `submitReviewFormAction` id is inside the chunks, not inline. |

Not observed: the signed-out `/` page itself (only `/user/sign_in` was fetched unauthenticated; the landing-page claim is from the home page's own `logged_out_browsing_page_count` cookie and ShelfSync, so the capture check must not rely on it); the `user_works` "View shelf" link for `read` and `did-not-finish` editions (seen only for `currently-reading`); the widget label after a percent-only update (the sample was a page update); the `add_to_shelf` and `user_status.json` responses; the WAF challenge and its token lifetime; `date_read` after shelving Read.

ShelfSync (`shelfsync/lib/goodreads/api.lua`, read today) documents the writes and quirks this plan reuses: `POST /user_status.json` with `user_status[book_id]`, `user_status[percent]` (or `[page]`), `user_status[body]`, header `X-CSRF-Token` from `/`'s meta tag plus `X-Requested-With: XMLHttpRequest`; GET requests need browser navigation headers (`Accept`, `Accept-Language`, `Referer`, `Sec-Fetch-*`, `Upgrade-Insecure-Requests`) or goodreads.com loops a self-redirect; Goodreads' session bootstrap answers with `Set-Cookie` plus a same-URL redirect that must carry the new cookie; a WAF challenge is HTTP 202 with header `x-amzn-waf-action`; a stale `jwt_token` cookie (5-minute life) makes requests fail, which a real cookie store avoids by expiring it; the finish-date write is a Next.js Server Action `POST /review/edit/<bookId>` with headers `Next-Action: <id>`, `Next-Router-State-Tree`, `Accept: text/x-component`, body `[payload, "/review/edit/[id]"]`, where the action id is found by downloading the page's chunk scripts backwards and matching `createServerReference("<hex>", …, "submitReviewFormAction")`. No code is copied.

## Design

### Files

Same shape as StoryGraph (`AGENTS.md` code map):

- `GoodreadsAuth.kt`: `GoodreadsSession` (profile cookies, User-Agent, capture, mark, clear), `GoodreadsHttp` (requests, redirect following inside `https://www.goodreads.com`, response classification, challenge retry through the refresher), `account()`.
- `GoodreadsPages.kt`: regex parsers for the home page (signed-in, user id, CSRF, currently-reading widget), shelf rows, `user_works` rows, review timeline, search results, book page JSON-LD and Apollo fields, review editor flight state and action id.
- `GoodreadsMatch.kt`: `goodreads:` tag, ISBN-13, ISBN-10 through `/search`, confirmed by the hit's JSON-LD `isbn`; then one normalized title and author match. Reuse `normalized`, `authorKey`, `authorNames`, `isbn10` and the one-hour cache pattern from `StoryGraphMatch.kt`.
- `GoodreadsSync.kt`: shelf policy, the 5-point rule, writes, read-back, finish and finish date.
- `GoodreadsConnection.kt`: `TrackerConnection` subclass with `awaitingCredentials`, `signingIn`, `sessionCaptured`, `setEnabled`, `deliver` guard, as `StoryGraphConnection.kt`.
- `GoodreadsBrowser.kt`: the WebView profile seam and the hidden refresh load (see below).
- `Background.kt`: `GoodreadsRefreshWorker` and `scheduleGoodreadsRefresh` / `cancelGoodreadsRefresh`.

### Session and profile (`GoodreadsBrowser.kt`)

- Add `androidx.webkit:webkit:1.17.1`. Interface `WebProfile` with `supported`, `cookieHeader(url)`, `accept(url, setCookies)`, `flush()`, `clear()`, `attach(webView)`. Production: `WebViewFeature.isFeatureSupported(MULTI_PROFILE)`; `ProfileStore.getInstance().getOrCreateProfile("goodreads")` obtained once on the main thread; `profile.cookieManager` (any thread) for cookies; `WebViewCompat.setProfile(webView, "goodreads")` before the first load; `clear()` is `cookieManager.removeAllCookies` plus `profile.webStorage.deleteAllData()` (`deleteProfile` throws for a profile loaded in this process). Tests inject a fake with its own in-memory cookie map per profile name (the Robolectric `CookieManager` shadow is one shared store, so a fake backed by it could not show that Log out leaves StoryGraph's cookies alone).
- Unsupported WebView: the switch returns Off with `goodreads_webview_profiles_unsupported` on the row ("needs a newer Android System WebView").
- State keys: `goodreads.session` (`true` or the problem code), `goodreads.userAgent`, `goodreads.connectionError`, `goodreads.refreshedAt`, plus the shared `goodreads.account/profile/connectedAt/enabled/book.*/match.*`.
- Hidden refresh load, `refresh(url)`: on `Dispatchers.Main` create `WebView(app)` with the profile, JavaScript and DOM storage on, no window; load the URL; after each `onPageFinished` evaluate `!!document.querySelector('a[href*="/user/sign_out"]')`; stop when true (`ok`), when the page is the sign-in page (`signed_out`), or after 30 s (`timeout`); flush cookies; destroy. Record one `goodreads_refresh` event (`outcome`, `durationMs`, `trigger`), never a cookie. `signed_out` marks `goodreads_session_expired`.

### Sign-in popup (`MainActivity.kt`)

Clone `storyGraphSignInDialog()`: profile attached before `loadUrl("https://www.goodreads.com/user/sign_in")`; main-frame navigation allowed for hosts ending in `goodreads.com`, `amazon.com`, `google.com`, `apple.com`, `facebook.com`; capture on a finished load of `/` on `www.goodreads.com`. Because the signed-out `/` is also a landing page, `sessionCaptured` fetches `/` through `GoodreadsHttp` first and requires the sign-out link; a home page without it resets `signingIn` without recording an error or storing anything, so the popup keeps waiting. Only a signed-in home stores the User-Agent and `goodreads.session = "true"` (the opposite order from `StoryGraphSession.capture`), then the user id, enables the service, schedules the refresh job and the first sync. Text: "Sign in on Goodreads' own website. Boox Tracker never sees or remembers your password; it keeps the browser session on this device." Docs note that Google may refuse to sign in inside an embedded browser; email and Amazon sign-in stay on goodreads.com.

### HTTP (`GoodreadsHttp`)

- `HttpURLConnection` as `StoryGraphAuth.fetch`, `instanceFollowRedirects=false`, 2 MiB limit; headers from the research record; User-Agent stored at capture; cookie header from the profile at request time; `Set-Cookie` written back through `accept`. Follow GET redirects (3xx, or 200 with `Location`) up to 5 hops, only inside `https://www.goodreads.com`.
- Classification: 202 or `x-amzn-waf-action` → challenge; 401, or a redirect to `/user/sign_in` or `/ap/signin`, or a home page without the sign-out link → `goodreads_session_expired`; other non-2xx → `HttpProblem(status, null, "goodreads")`.
- Challenge: call `refresh(url)` once, then retry the request once. A second challenge throws `goodreads_browser_check_required` and marks the session; the row says "Goodreads asked for a browser check that the app could not pass; turn Goodreads off and on." No further automatic retry.

### Refresh job (`Background.kt`)

`GOODREADS_REFRESH_WORK_NAME`, `PeriodicWorkRequestBuilder<GoodreadsRefreshWorker>(6, HOURS)` with `NetworkType.CONNECTED`, `ExistingPeriodicWorkPolicy.KEEP`, enqueued at a captured sign-in and at app start when `goodreads.session == "true"`; cancelled by Log out. The worker skips unless connected and online, calls `refresh("https://www.goodreads.com/")`, stores `goodreads.refreshedAt`, and records one `run` event like the other workers. `DeliveryWorker` also refreshes before draining when `refreshedAt` is older than 6 hours. The App's `recover()` list gains `goodreads`.

### Matching (`GoodreadsMatch.kt`)

1. `goodreads` tags (positive integers, already allowlisted in `BookIdentifiers.kt:55`): `GET /book/show/<id>`, evidence = (work id, normalized title, author key).
2. ISBN-13 then ISBN-10: `GET /search?q=<isbn>`; take up to 3 hits (or the `Location` target); confirm by the hit's JSON-LD `isbn` (normalized, ISBN-10 converted) and add its work id.
3. No ASIN search (verified empty). Hardcover's Goodreads mappings are not consulted (no Hardcover dependency).
4. Evidence from different works → `goodreads_identifier_conflict`. None → title and author search, keep hits whose normalized title and author set match, exclude omnibus hits by exact normalized title; several works → `goodreads_book_ambiguous`.
5. Result: `bookId` (edition), `workId`, `matchKind` (`edition` for identifier evidence, `book` for title), cached one hour under `goodreads.match.<digest>`.

### Shelf policy and read-back (`GoodreadsSync.kt`)

1. `account()` from `/` must equal the stored account (`goodreads_account_changed`).
2. `GET /review/user_works/<workId>`: the user's shelved editions of the work with their exclusive shelf. Target = the shelved edition if one exists (`existingEditionPreserved` when it differs from the matched edition), else the matched edition. Two shelved editions with different statuses hold (`goodreads_status_conflict`).
3. Status `read` or `did-not-finish` holds with `goodreads_status_conflict`, except a finished source on a `read` edition, which reports `already_current`. `to-read` or unshelved: `POST /shelf/add_to_shelf` `name=currently-reading`, confirmed by re-reading `user_works`.
4. Remote progress from the home page widget, three cases: no entry for the target `bookId` → the shelf write did not take (`goodreads_shelf_not_applied`); an entry with only the "Update progress" button (the state of every book the app shelves itself, seen today) → remote 0; an entry with a bar → the percent inside the `aria-label` parentheses, with `value/max` only as a fallback, because `max` depends on a page count the edition may lack. A widget that cannot be parsed at all → `goodreads_page_unrecognized`. The review timeline (`shelf row → reviewId → /review/show`) is the fallback parser for the details popup and tests, not the sync path.
5. Progress rule: `remote > percent` → `kept_higher_remote_progress` (⚠ on the row); `percent - remote < 5` and the shelf unchanged → `already_current` (`unchanged: true`, no write); otherwise `POST /user_status.json` with `user_status[percent]=<floored percent>`, `user_status[body]=""`, then re-read the widget and require the percent (`goodreads_progress_not_applied`).
6. Finish (`readingStatus 2`, full fraction): `add_to_shelf name=read`, confirm through `user_works`, then the date: `GET /review/edit/<bookId>`, parse the flight state and the action id from the chunks (cached per chunk set), POST the Server Action with the current session's `endedDate` set to `readingDay(book, readAt)`, verify by re-reading the editor. Any failure in the date step records `finishDateError` in the result; the result is still `sent` and acknowledged. The popup shows `Finish date: not set (<reason>)` and the row warns `Goodreads marked the book Read but the finish date could not be set: <reason>.`
7. Result JSON for `resultFields`: `title`, `bookId`, `matchedBookId`, `workId`, `matchKind`, `existingEditionPreserved`, `rawProgress`, `percent`, `remotePercent`, `finished`, `shelfBefore`, `shelfAfter` (`currently_reading`, `read`), `finishDate`, `finishDateError`, `outcome`.

### UI (`MainActivity.kt`)

- Replace the Coming Soon row with `showGoodreads` using `serviceRow` and `updateGoodreadsSignIn`, in the current row order (Hardcover, Goodreads, StoryGraph, Fable, Margins).
- `serviceIssues`, `connectionText` and `resultFields`: `goodreads_*` codes (`session_expired`, `browser_check_required`, `webview_profiles_unsupported`, `status_conflict`, `book_not_found`, `book_ambiguous`, `identifier_conflict`, `progress_not_applied`, `shelf_not_applied`, `page_unrecognized`, `account_changed`), the shelf labels `currently_reading`/`read`/`to_read`/`did_not_finish`, the finish-date line, and the `goodreads_refresh` event text in `ActivityLogAdapter.eventText`.
- `ids.xml`: `goodreads_sign_in_web`, `goodreads_sign_in_status`. `Export.kt` notes: Goodreads receives the whole percentage through its website session; its cookies are never exported. `ReadingSyncApp.kt`: field, `connections`, construction. `serviceName` needs no change.

### Tests

- `GoodreadsServer.kt`: `MockWebServer` with sanitised fixtures under `app/src/test/resources/goodreads/` (home signed in/out with widget variants, shelf row, `user_works` rows, review timeline, search results, book page JSON-LD plus a minimal Apollo script, review editor flight and a chunk with `createServerReference`, `add_to_shelf`, `user_status.json`, 202 challenge with `x-amzn-waf-action`, sign-in redirect, `Set-Cookie` rotation). Switches: `challengeOnce`, `challengeAlways`, `sessionValid`, `failProgressOnce`, `editorActionMissing`.
- `GoodreadsPagesTest` (plain JUnit), `GoodreadsMatchTest`, `GoodreadsTest` (sync: shelve, 5-point skip, kept higher, finish with and without date, challenge solved by the fake refresher, challenge unsolved held, account change, status conflict; connection: capture on signed-in home, silent wait on signed-out home, cancel, Log out clears only the Goodreads profile, export has no cookie or user id; screen: row, popup WebView URL and host allowlist, window flags). `BackgroundTest`: refresh job enqueued after sign-in, cancelled at Log out, worker skips offline. The fake `WebProfile` and fake refresher record calls; Robolectric runs no page JavaScript.

### Docs

`docs/goodreads.md` (model: `docs/storygraph.md`, with the throttle, the feed side effect, the hidden refresh and the Google sign-in caveat), README table and footnote, `docs/service-artwork.md`, `docs/device-testing.md`, `CONTRIBUTING.md` if it lists services; `AGENTS.md` (trackers bullet: website session, Amazon sign-in on goodreads.com, hidden WebView may solve a challenge for Goodreads only, 6-hour refresh job, per-service WebView profiles; code map; `GoodreadsServer`), `agents/product.md` (decisions above, dated), `agents/integrations.md` (the research record), `agents/design.md`, `agents/project-status.md`, `agents/verification.md`, `agents/device-testing.md`.

## Checklist

### Plumbing

- [x] `app/build.gradle.kts`: `androidx.webkit:webkit:1.17.1`, 0.6.0 / code 25.
- [x] `GoodreadsBrowser.kt`: `WebProfile`, production profile, fake for tests, `refresh(url)`.
- [x] `ReadingSyncApp.kt` field, `connections`, `recover()`; `Background.kt` refresh worker and scheduling; `Export.kt` notes.

### Auth and popup

- [x] `GoodreadsAuth.kt`: session, http, classification, challenge retry, `account()`.
- [x] `GoodreadsConnection.kt`: flags, capture with the silent signed-out wait, `setEnabled`, `signOut` (best-effort `GET /user/sign_out` on the server when connected, as StoryGraph posts its sign-out; then clear the profile in every case), refresh scheduling and cancellation.
- [x] `MainActivity.kt`: row, popup, `updateGoodreadsSignIn`, texts, `ids.xml`.
- [x] Tests for capture, silent wait, cancel, expiry, challenge, unsupported WebView.

### Pages, match, sync

- [x] Fixtures and `GoodreadsPagesTest`.
- [x] `GoodreadsMatch.kt` and tests.
- [x] `GoodreadsSync.kt` and tests, including the finish-date action.
- [x] `resultFields`, popup lines, `eventText`, UI tests, stderr allowlist if needed.

### Docs and gates

- [x] Public docs and agent docs listed above.
- [x] `mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic`, ktlint, ruff, markdownlint, actionlint, `package-artifacts.py`.
- [x] `code-simplifier` and `code-reviewer` agents on the change; apply findings.

### Physical (GoColor7, WebView 151)

- [ ] Install 0.6.0 over 0.5.3 without data loss; StoryGraph stays connected after the Goodreads profile is created.
- [ ] Sign in through the popup with email; the popup closes; the details popup shows the account; a `goodreads_refresh` run event appears after the job's first slot.
- [ ] First sync: In the Blood matched by ISBN-13, the shelved Kindle edition kept, progress written and read back from the widget; a second sync within 5 points is `already_current` with no new status update on the site.
- [ ] Challenge: force one if possible (many requests in a short time or a new network); otherwise record that none occurred over the test window. Measure the `aws-waf-token` lifetime if one is set.
- [ ] Finish on a test book: Read shelf and the finish date on the site; then a finish with the editor action disabled (fixture flag or airplane mode between the two writes) still shelves Read and warns.
- [ ] Log out: Goodreads profile cookies gone, StoryGraph still connected; export has no cookie, token or user id.
- [ ] Hidden-app delivery with four services On; refresh job survives a reboot.

## Verification

Gates in `AGENTS.md` (unit tests, lint, assemble, ktlint, ruff, markdownlint, actionlint, packaging) must be green at zero failures. Device checks are recorded in `agents/verification.md` at the three levels (Built, Automatically tested, Verified on physical BOOX). The token lifetime measurement decides whether 6 hours stays; record the result in `agents/integrations.md`.

## Risks and notes

- The hidden WebView runs with no window. JavaScript timers may be throttled in an invisible WebView; the 30-second budget covers the challenge's few seconds of work. Robolectric cannot test it, so it is device-only evidence.
- `ProfileStore` needs WebView 112+. Older devices get the row message and no Goodreads; no fallback to the default profile, because that would re-create the shared-store Log out problem.
- Goodreads book ids are edition ids; `user_works` is the work-level view that makes "existing edition" reliable without a title heuristic.
- `jwt_token` expires in the cookie store; if a device sync still fails with a stale token, strip `jwt_token` from the outgoing header (ShelfSync's `GOODREADS_SKIP_COOKIES`).
- The 5-point rule compares against the value Goodreads holds, so it needs no extra stored state and self-heals after a user's manual update.
- The Server Action id discovery downloads chunk scripts (several hundred KB each) once per Goodreads build; cache the id per chunk-path set in `goodreads.editorAction`.

## Notes

- Read-back uses `/review/user_works/<workId>` instead of the home page widget. Found during implementation: its rows carry `data-exclusive-shelf` and the progress form's prefilled `user_status[percent]` for every shelved edition, with the account, sign-out link, and CSRF token, and no list limit. The widget's book limit is unknown.
- The book page is read from its `__NEXT_DATA__` Apollo cache (ISBN-13, contributors, work `legacyId`), not JSON-LD.
- A send renews a stale session in `GoodreadsConnection.deliver`, not in `DeliveryWorker`, so foreground and background sends share it. The renewal worker writes a `run` event; a failed renewal writes a `goodreads_renewal` issue. The worker skips while Goodreads is Off.
- StoryGraph's sign-in popup became the shared `webSignInDialog`; StoryGraph's behaviour and tests are unchanged.
- Server sign-out is a POST: the recorded home page marks the link `data-method="POST"`.
- A shelved edition that an identifier confirmed counts as the same edition, so In the Blood's Kindle ISBN reports "same edition" even when a `goodreads:` tag names the hardcover.
- WebView's `removeAllCookies` with a callback needs a Looper thread (Chromium `AwCookieManager`), so the Goodreads profile clears on the main thread. StoryGraph's `clear()` had the same pattern; at the user's request it was fixed in this change, with a Looper-enforcing cookie shadow and a failing-first test.
- Review findings applied and kept are listed in [verification](verification.md#goodreads-tracker-060).
- Not committed. Commit, push, and device install wait for the user.
