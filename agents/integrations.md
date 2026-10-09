# Destination-service research

The [automatic matching/offline plan](plan-20261006-automatic-offline-sync.md) supersedes exact-ISBN-only and catalogue-selection proposals. Findings below are dated evidence, not guarantees about future API availability. Recheck official contracts before a new integration or dependency change.

## Destination summary

| Destination | Implementation / evidence |
| --- | --- |
| Hardcover | Native device OAuth, automatic matching, conservative progress, durable queue; user confirmed manual exact matching and delivery on 0.3.1 |
| Goodreads | 0.6.0 website session: sign-in in an in-app WebView in its own `androidx.webkit` profile, `HttpURLConnection` with the profile's cookies, ISBN matches confirmed by edition pages and keyed to the work, shelf and percentage writes with read-back from the user's editions page, finish date through the review editor; WAF challenge passed in a hidden WebView; no API; built and automatically tested, no device evidence |
| StoryGraph | 0.5.2 website session: sign-in in an in-app WebView, `HttpURLConnection` with the session cookies, automatic matching confirmed by edition pages, currently-reading and read writes, durable queue; no API; sign-in, the first sync past Cloudflare, the ISBN match and a progress write verified on the GoColor7; the spike's later intervals, completion and Log out pending |
| Fable | 0.4.0 email/password sign-in, automatic matching, shelving, percentage progress, durable queue; unofficial app API; physical checks pending |
| Pagebound | 0.7.0 email/password sign-in through Firebase and Pagebound's token exchange, ISBN matching through the website's lookup, Typesense title search, status and digital-read writes, percentage updates in whole steps of 5 with read-back, finish; unofficial website API; built and automatically tested, no device evidence |
| Margins | Explicit identifier display only; Coming Soon. Inquiry sent; no reply/access reported as of 2026-10-05 |

Book-only fallback and hidden-app offline/reconnect delivery are built and automatically tested but lack current physical evidence. A read-only Personal Access Token lookup, local HTTP test, emulator screenshot, and real OAuth write are different kinds of evidence. Do not merge them.

## Hardcover

### Authentication and registration

Shared public OAuth client: `bc5f2c0f-79d7-42b5-b525-6293454d3934`. Every APK uses that app identity; each user approves their own account and receives separate private tokens. Do not ship a client secret or ask other users to register a developer app. Tokens are encrypted on device with Android Keystore, refresh is serialized, and no background work launches approval.

The registration guidance was public Mobile/desktop/CLI with Device Authorization Grant, no redirect URI, and the E-Reader / Sync Client preset: `read:catalog`, `read:library`, `write:library`, `read:me:content`. The user's consent screenshot now shows **Boox Tracker** and catalogue/library/profile scopes. They confirmed approval and progress reaching Hardcover. This validates the manual path on their account/device; it does not establish every scope operation or token-expiry case in production.

The app registration is external configuration. Keep the client ID. Deleting/replacing the registration can revoke user tokens; local branding does not require it. Temporary test credentials and consent codes must never enter source, exports, or agent docs.

### Verified access research: 2026-10-05

The documentation website returned HTTP 403 during research, so official `hardcoverapp/hardcover-docs` source was inspected. Its API guide described beta access, scoped PATs with chosen expiry, and OAuth for multi-user apps. The OAuth source described public native clients, PKCE, Device Authorization Grant, refresh rotation, and no required hosted authentication server. Discovery at `https://api.hardcover.app/.well-known/oauth-authorization-server` advertised authorization-code, refresh-token, device-code, and S256. These were read-only contract checks; physical manual validation followed on 0.3.1.

Primary sources inspected:

- [Getting Started](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/Getting-Started.mdx)
- [OAuth](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/OAuth.mdx)
- [Device sign-in](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/OAuth/Getting-Started-Device.mdx)
- [GraphQL schema](https://github.com/hardcoverapp/hardcover-docs/blob/main/schema.graphql)
- [Search](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/guides/Searching.mdx)

The live read input accepts pages/seconds with nullable edition ID; no direct percentage mutation was found. `insert_user_book_read` and `update_user_book_read` use `DatesReadInput`. The progress-writing guide was a draft placeholder. The app converts valid BOOX fractions to approximate pages with a verified positive page basis, rounded HALF_UP. This does not promise exact physical-page position or identical displayed percentages.

### Implemented matching and progress policy

Resolve explicit Hardcover identity first, then ISBN-13/ISBN-10/ASIN, accessible Goodreads `book_mappings`, and one distinct normalized title/alternative-title plus author. Multiple matching editions of one book establish book identity. Conflicting identifiers or ambiguous books hold without a dialog. Cache by stable source identity and metadata fingerprint; invalidate changes and expire after one hour.

Use an active remote read's edition first. Otherwise use exact matched edition, default ebook, then default physical. Verify book ownership and positive pages. Preserve the remote edition even if source differs; the row shows "✅ Book matched · different edition" without a warning. A remote basis without pages holds; a pageless source edition can fall through to a valid default. Keep higher remote progress/history. A finished source completes the read and sets Read ([completion plan](plan-20261006-completion-sync.md)); rereads, paused/completed conflicts, and catalogue edits are held or excluded.

Verified on the user's account on 2026-10-07: setting a book to Currently Reading makes Hardcover create a read of its own, with today's start date and no progress. Our first sync of In the Blood on 2026-10-06 added the book with `insert_user_book` (status 2) and then inserted its own undated read, which left read 7080312 (started 2026-10-06, 0 pages) and read 7080314 (no dates, 240 pages), both on edition 33373487. Every later sync held with `hardcover_read_history_conflict`. Since 0.4.4 the sync re-reads the reads after it adds a book or moves it to Currently Reading and advances Hardcover's read instead of inserting another. Two open reads on one edition with exactly one dated are treated as that pair: progress goes to the dated read, the higher progress of both is kept, and the undated read is left unchanged (user decision). Other multi-read histories still hold.

Tag/URL types follow the inspected [Calibre conventions](https://github.com/RobBrazier/calibre-plugins/blob/main/plugins/hardcover/README.md): `hardcover-edition` is an edition ID, `hardcover-id` a numeric book ID, `hardcover-slug` a book slug. `hardcover` is normalized to a book ID for numeric values or a slug otherwise. Accept supported book/edition URLs. Never treat a Goodreads number as a Hardcover ID.

`BookIdentifiers.kt` is the single reader allowlist. The popup labels its output without a second allowlist. `amazon`/`mobi-asin` normalize to ASIN. Explicit StoryGraph/Fable/Margins values accept `[A-Za-z0-9][A-Za-z0-9._-]{0,299}`. Fable matching uses only values that are Fable book UUIDs; StoryGraph and Margins values are display-only. URLs, paths, whitespace and unrelated tags are rejected; no remote mapping contract is claimed for StoryGraph or Margins. Ebook cache keys use `ebook.identity.2.<digest>` so older extraction results are bypassed without deleting history.

### Local matching samples: 2026-10-06

The user supplied two EPUBs. Read-only inspection used container/OPF metadata; never commit/upload these files or copy book contents/private UUIDs into fixtures. Tests use minimal synthetic metadata. Inspect both identifier text and namespace-qualified scheme attributes: an OPF attribute named `eisbn` can contain a UUID rather than an ISBN.

| Sample | Local metadata | Read-only catalogue evidence |
| --- | --- | --- |
| In the Blood — Jack Carr | EPUB 3 prefixes: ISBN `9781398508255`, Goodreads `58438630` | ISBN-13 and ISBN-10 `139850825X` → book `510350`, edition `33373487`, 480 pages after user added the missing ISBN |
| Savage Son — Jack Carr | EPUB 2 scheme attributes: ISBN `9781471197376`, Goodreads `58895717`; Amazon value `1471197379` | ISBN-13/10 and Goodreads absent in inspected lookup; unique title/author distinguishes the book from omnibus results |

For In the Blood, Goodreads mapped to the same book through a different edition. That establishes book identity, not an exact source edition. The book had no default ebook edition; default physical was `30462394`, 480 pages. The user-linked edition `30462409` and their later 240/480 screenshot do not establish which edition the app selected. Inspect a delivery export for that.

For Savage Son, the separately supplied ASIN `B07THCSQ27` matched book `484869`, ebook/default ebook edition `31807724`, 429 pages. Default physical `30425587` also had 429 pages. The actual sample's Amazon value `1471197379` is syntactically ten characters but did not identify that edition in the inspected lookup. Do not silently replace it. The untouched sample is a useful book-only fallback test, still physically pending.

No catalogue edits or progress mutations were performed during PAT research. Later native OAuth/manual progress reached Hardcover on 0.3.1. Their book screenshot shows Currently Reading, 240/480 pages, 50%; raw fraction, chosen edition ID, and mutation sequence await export. UnknownHostException was reported before offline queuing; it is not an authentication verdict or a reason to prompt for Wi-Fi.

Additional leads: [API/playground](https://api.hardcover.app/), [account API page](https://hardcover.app/account/api), [developer roadmap](https://roadmap.hardcover.app/feature-requests/posts/developer-api), and [Billiam/hardcoverapp.koplugin](https://github.com/Billiam/hardcoverapp.koplugin). Inspect source/licence before reuse. [sleepdebt/boox-hardcover](https://github.com/sleepdebt/boox-hardcover) uses desktop ADB; its architecture does not prove app-UID access.

## Goodreads

Implemented in 0.6.0 under the [Goodreads plan](plan-20261008-goodreads-sync.md). Goodreads stopped issuing public API keys in December 2020. The connector drives Goodreads' website through a signed-in browser session, as the MIT-licensed [ShelfSync](https://github.com/Lyfts/ShelfSync) KOReader plugin does; no code is copied. ShelfSync replays a cookie header copied from a desktop browser, says Goodreads cannot read progress back, and relies on a self-hosted Chrome container ([goodreads-cookie-refresher](https://github.com/Lyfts/goodreads-cookie-refresher)) that reloads Goodreads every 20 minutes to pass the AWS WAF challenge. Boox Tracker keeps the session in a WebView profile, reads progress back from the user's editions page, and lets a hidden WebView pass the challenge. The user accepted the unofficial-access risk on 2026-10-08. Public docs state that the connection is unofficial, bot-protected, and can break.

### Verified with the user's account on 2026-10-08

The user signed in on `www.goodreads.com` in the built-in browser. All probes were GET requests from that session; nothing was written by the app. The user posted one progress update (page 240 of 480) on In the Blood themselves so its formats could be read. No cookie, token, or account value is recorded here. Classic pages carry `<meta name="csrf-token">`.

| Call | Verified behaviour |
| --- | --- |
| `GET /user/sign_in` (unauthenticated `curl`, browser User-Agent) | 200, no WAF challenge. Sets `ccsid`, `locale`, HttpOnly `_session_id2`. Email, Amazon, and Apple sign-in links all go to `https://www.goodreads.com/ap/signin?…` (Amazon's sign-in on the Goodreads host); the return URL is `/ap-handler/sign-in`, then `/`. |
| `GET /` signed in (275 KB) | Site header links `/review/list/<uid>?ref=nav_mybooks` (the viewer's numeric id), `/user/show/<uid>-<slug>`, and `/user/sign_out?ref=nav_profile_signout` with `data-method="POST"`. Server-rendered Currently Reading widget: per book `/book/show/<id>` and, after an update, `div.gr-progressBar[aria-label="Reading progress: 240/480 (50%)"]`; without an update only an "Update progress" button. Its book limit is unknown, so it is not used. |
| Cookie names visible to page scripts after sign-in | `ccsid, locale, session-id, session-id-time, ubid-main, session-token, x-main, lc-main, csm-hit, id_pk, id_pkel, likely_has_account, logged_out_browsing_page_count`. No `aws-waf-token` was set during the probes. |
| `GET /review/user_works/<workId>` (~80 KB, classic) | `Editions in My Books of '<title>'`, the site header (account id, sign-out link, CSRF meta). One `<tr itemtype="http://schema.org/Book">` per shelved edition of the work: `<div id="<bookId>" class="u-anchorTarget">`, `span[itemprop=name]` title, format, `div.wtrDown.wtrRight[data-exclusive-shelf='currently-reading']`, and for a currently-reading edition the progress form with `user_status[page]` value 240, "of 480", `user_status[percent]` value 50, and `user_status[book_id]`. An unshelved work gives an empty table. The page's script shows the shelf write: `POST /shelf/add_to_shelf` with `book_id`, `name`, `a` (empty to add) and `authenticity_token`. |
| `GET /book/show/<bookId>` (~290 KB, Next.js) | `<script id="__NEXT_DATA__">` JSON: `props.pageProps.apolloState`; `ROOT_QUERY["getBookByLegacyId({\"legacyId\":\"<id>\"})"].__ref` → Book with `legacyId`, `title`, `titleComplete`, `details.isbn13`, `details.isbn`, `details.asin`, `details.numPages`, `primaryContributorEdge.node` → Contributor `name`, `work.__ref` → Work with `legacyId` (the work id) and `viewerShelvingsUrl: "/review/user_works/<workId>"`. `pageProps` also holds a `jwtToken`; nothing from the page is logged. JSON-LD repeats `name`, `isbn`, and `numberOfPages`. |
| `GET /search?q=<isbn13>` (~207 KB) | One result for the matching edition: `<span data-testid="book-item-title"><a href="/book/show/<id>">title</a></span>` followed by `data-testid="name"` contributor spans. A Kindle ASIN found nothing. Both ISBNs of In the Blood (UK paperback and US Kindle) are editions of one work. |
| `GET /review/show/<reviewId>` | `.readingTimeline__text` rows "Started Reading", "Shelved", "Shelved as: to-read", "Finished Reading", and progress rows `page 240 … <a href="/user_status/show/<id>">50.0%</a>` (a percent update shows only the link). Not used. |
| `GET /user_status/list/<uid>` | Newest first: "`<name>` is on page 240 of 480 of `<book link>`" or "is 52% done with …". Not used. |
| `GET /user/show/<uid>` | Title `"<Name> (<username>) (36 books) \| Goodreads"`. |
| `GET /review/edit/<bookId>` (~135 KB, Next.js app router) | 18 `self.__next_f.push([1,"…"])` scripts whose strings join into the flight data. The first `"readingSessions":[…]` (in the work's `viewerShelvings`) holds full dates `{year, month, day, __typename: "NullableDate"}`, `state: "READING"`, `endedDate: null`; the form's `initialReadingSessions` only references those dates. Form props: `isAlreadyOwned`, `initialPrivateNotes`, `initialShelfName`, `review: null`, and default shelves `to-read`, `currently-reading`, `read`, `did-not-finish`. 29 chunk scripts; reading them from the last one back, skipping polyfills, webpack, and not-found, the second one held `createServerReference("<42-hex id>", …, "submitReviewFormAction")`. |

Not observed: the signed-out home page; `data-exclusive-shelf` and the editions-page row for `read`, `to-read`, and `did-not-finish` editions; the widget after a percent-only update; the responses of `add_to_shelf`, `user_status.json`, the Server Action POST, and `POST /user/sign_out`; whether shelving Read sets a finish date; the WAF challenge page, its status, and the token lifetime; rate limits.

ShelfSync documents the writes and quirks the connector uses: `POST /user_status.json` with `user_status[book_id]`, `user_status[percent]`, `user_status[body]`, and the `X-CSRF-Token` and `X-Requested-With` headers; GET requests need browser navigation headers or Goodreads loops a self-redirect; the session bootstrap redirects to the same URL with a `Set-Cookie` that the next hop must carry; a signed-in search with one exact hit answers 200 with a `Location`; a WAF challenge is HTTP 202 with `x-amzn-waf-action`; a stale `jwt_token` cookie fails requests; the Server Action POST sends `Next-Action`, a URL-encoded `Next-Router-State-Tree`, `Accept: text/x-component`, and the body `[payload, "/review/edit/[id]"]`.

### Implemented Goodreads matching and progress policy

Matching tries `goodreads:` tags, then ISBN-13 and ISBN-10 through `/search`, each hit confirmed by its book page's ISBN-13; all evidence must share one work id. Then one normalized title and author match; several works hold. ASIN is not searched. Sync reads the account, CSRF token, shelf, and percentage from `/review/user_works/<workId>`. A shelved edition of the work receives the progress; editions on different shelves, Read, Did Not Finish, and unknown shelves hold a reading source. To-read or unshelved moves to Currently Reading. The floored percentage is posted only when it is at least 5 points ahead, or when the shelf changed, then read back from the editions page. Completion shelves Read, confirms it, and then sets the end date of the read that the shelf write finished (the open session, or the one the editor showed open before the write) through the Server Action, never adding a session, never with an existing review, and only reporting a failure.

## StoryGraph

Implemented in 0.5.0 under the [StoryGraph plan](plan-20261007-storygraph-sync.md). StoryGraph has no public API; the official [API roadmap](https://roadmap.thestorygraph.com/features/posts/an-api) showed Long-term on 2026-10-05. The connector drives StoryGraph's own website (a Rails and Turbo application) through a signed-in browser session, as the MIT-licensed [ShelfSync](https://github.com/Lyfts/ShelfSync) and [storygraph.koreader](https://github.com/cernoh/storygraph.koreader) (licence TBD) plugins do; no code is copied. StoryGraph's [terms](https://app.thestorygraph.com/terms-of-service) forbid reverse engineering site software and say nothing about automated use; `robots.txt` is empty. Cloudflare is the stronger signal of intent: it answered every non-browser client tested from the Mac with `403` and `cf-mitigated: challenge`, even unauthenticated page loads with a browser User-Agent. The user accepted this risk on 2026-10-07. Public docs state that the connection is unofficial, bot-protected, and can break.

### Verified with the user's account on 2026-10-07

The user signed in on app.thestorygraph.com in the built-in browser. Read-only probes used that session; the user authorized the writes on their live read, which was left at its real position. No cookie, token, or account values are recorded here. Every page carries `<meta name="csrf-token">` and `<meta name="csrf-param" content="authenticity_token">`.

| Call | Verified behaviour |
| --- | --- |
| `GET /users/sign_in` | Sign-in form (`authenticity_token`, `user[email]`, `user[password]`; the reference clients also send `user[remember_me]`, but the page shows no such control in the app's WebView). While signed in it redirects to `/`. |
| `POST /users/sign_in` | 303 to `/`; sets HttpOnly `_storygraph_session` and `remember_user_token` (reference clients; the POST was not captured). Lifetimes unknown. |
| `GET /` | Inline script contains `userId: '<uuid>'` once (the stable account id). Navigation links `/profile/<username>`; a `POST /users/sign_out` form (`_method=delete`) marks a signed-in page. |
| `GET /search?search_term=<term>&button=` with header `turbo-frame: search_results` | 2–8 KB fragment: `<a class="book-list-option" id="search_result_book_<uuid>" href="/books/<uuid>">` with `<h1>` title and `<h2>` author. The search is fuzzy: an unknown ISBN still returns one unrelated hit, so a hit is evidence only after its book page shows the same `ISBN/UID`. Known ISBN-13, ISBN-10 and ASIN each returned their edition. A title search lists one edition per work. |
| `GET /books/<uuid>` (~125–135 KB) | `<h3>` title. `.edition-info[data-book-id] p` lines `ISBN/UID: …` (ISBN-13, ISBN-10, ASIN or `None`), `Format`, `Language`, `Publisher`, `Edition Pub Date`. `button.read-status-label` text is this edition's status: absent, `to read`, `currently reading`, `read`, `paused`, `did not finish`, `rereading`. When another edition is shelved: `<a href="/books/<other>">You want to read another edition</a>` or "…currently reading another edition". Status forms `form[action="/update-status.js?book_id=<uuid>&status=<s>"]` with only `authenticity_token`. While currently reading: `.progress-tracker-pane[data-book-id]` with `form[action="/update-progress"]`: `read_status[progress_minutes]`, `read_status[progress_number]` (empty, `min` is the current percent), `read_status[progress_type]` select with `pages` and `percentage` options (`pages` is selected until a write, then the last used), hidden `read_status[last_reached_pages]`, `read_status[book_num_of_pages]`, `read_status[last_reached_percent]`, `book_id`, `on_book_page=true`, `commit=Save`; a bar `style="width: 51%"`. |
| `GET /books/<uuid>/editions` (~780 KB, paginated) | `.book-pane[data-book-id]` per edition with `form[action="/switch-editions"]` on the others. Not used: too heavy, and switching editions is never done. |
| `POST /update-status.js?book_id=<uuid>&status=currently-reading`, body `authenticity_token`, headers `X-CSRF-Token`, `X-Requested-With: XMLHttpRequest`, `Accept: text/javascript` | 200 `text/javascript`, ~108 KB jQuery body replacing `.action-menu[data-book-id=…]`. The book page then shows `currently reading` and the progress form; a journal entry was created. |
| `POST /update-progress` (form: `read_status[progress_number]=50`, `read_status[progress_type]=percentage`, `read_status[book_num_of_pages]=459`, `read_status[last_reached_pages]=0`, `read_status[last_reached_percent]=0`, `read_status[progress_minutes]=`, `book_id`, `on_book_page=true`, `commit=Save`, `authenticity_token`), same headers | 200 `text/javascript`, ~12 KB body replacing the pane with `width: 50%`. Read-back: hidden `last_reached_percent=50`, `last_reached_pages=230` (derived by StoryGraph), `progress_type` selected `percentage`. A second write of 51 read back as 51 / 234. |
| `GET /currently-reading/<username>` (~127 KB) | Every current read with the same hidden inputs and bar. Not used. |
| `POST /update-status.js?…&status=read` | Documented by both reference clients; not exercised. A finished book shows the label `read`, `Finished <date>`, read-instance links, and a `rereading` option. |
| `POST /users/sign_out` (`_method=delete`) | Form on every page; not exercised. |

Not observed: rate-limit headers, cookie lifetimes, whether `cf_clearance` is bound to the TLS fingerprint, the `read` write, the `rereading` and `paused` transitions, and decimals or values above 100 in `progress_number`.

### Verified on the GoColor7 on 2026-10-08

The first sync after the device sign-in reached StoryGraph through `HttpURLConnection` with the WebView's cookies and User-Agent: `/`, `/search` and `/books/<uuid>` all answered without a challenge, minutes after the sign-in. That is the first data point of the transport spike; the later intervals remain open.

The raw page HTML writes `data-book-id` without quotes on the page's own blocks (`edition-info`, `action-menu`, `progress-tracker-pane`): 24 bare against 16 quoted values on one book page. Browser DOM probes and `outerHTML` had normalised them to quoted, so the 0.5.0 parser, which accepted only quoted attributes, never found an edition's `ISBN/UID` or progress form on the device and reported `storygraph_book_not_found`. 0.5.2 accepts bare and single-quoted values, and the fixtures carry the bare form.

The user's ebook carries its own ISBN (9781398508255, the UK edition) in its EPUB metadata next to the ISBN NeoReader shows (9781982181680, the Kindle edition the user set). StoryGraph holds both as separate editions of one work. 0.5.2 treats confirmed editions that share a normalized title and author key as one book and matches the first in ISBN order; a shelved sibling still receives the progress through the "another edition" link. NeoReader's library title for this book is "Terminal List #05 – In the Blood" (the EPUB's own title is "In the Blood"), and the matcher receives the library title first, so the title and author fallback cannot find this book on StoryGraph; its ISBN evidence is the only path.

### Implemented StoryGraph matching and progress policy

Sign-in is StoryGraph's own sign-in page in a WebView inside the popup, so the app never sees the password and Cloudflare sees a browser. The cookies stay in the WebView cookie store; the state table holds only whether a session was captured, why it stopped, and the WebView's User-Agent, which every later request sends. Requests use `HttpURLConnection` without redirects. A `403` with `cf-mitigated: challenge` or a "Just a moment" body is `storygraph_browser_check_required`; a redirect to `/users/sign_in` or a `401` is `storygraph_session_expired`. Both mark the session so the row shows Reconnect required and name the cause; neither retries on its own. Turning the service Off and On reopens the WebView, which passes the check or signs in again, and the queued update is sent afterwards. Every send first reads `/`: a signed-out page ends the session; a signed-in page without `userId` holds with `storygraph_identity_unavailable` and does not end it.

Match an explicit `storygraph:` UUID whose page loads, then each ISBN-13 through the search fragment with the first three hits confirmed by their page's `ISBN/UID` (the ISBN-13 or its ISBN-10 form), then the ISBN-10 search, then ASIN only without ISBN evidence, then one normalized title and author match among the search hits. Several confirmed editions that share a normalized title and author key are one book, matched on the first in ISBN order; confirmed editions of different books, several title matches, and nothing found hold. An unshelved edition whose page names the edition the user shelved hands progress to that edition (`existingEditionPreserved`); editions are never switched.

A finished source marks the book read unless it already is (then `already_current`); a did-not-finish or rereading edition holds. A reading source holds on read, did-not-finish and rereading editions; an unshelved, to-read or paused edition is marked currently reading first and confirmed by re-reading the page. The hidden `last_reached_percent` is the remote progress: higher is kept, equal is current, lower receives the floored percentage with the page's other hidden values echoed, always in percentage mode, and is confirmed by re-reading the page. Log out posts the sign-out form once, best effort, then clears the cookie store.

## Pagebound

Implemented in 0.7.0 under the [Pagebound plan](plan-20261008-pagebound-sync.md). Pagebound has no public API: `/api`, `/developers`, `/docs`, and OpenAPI paths on pagebound.co return the site's 404 page, and `robots.txt` allows everything. Its website is a Vike/React app that talks to a private Rails API at `https://prod-pagebound-api.onrender.com/api/v1` behind Cloudflare on Render, signs in through Firebase Auth (project `pagebound-430920`), and searches the catalogue through Typesense with a search-only key. The MIT-licensed [ShelfSync](https://github.com/Lyfts/ShelfSync) added Pagebound on 2026-09-29; it stores the user's password and matches by title only. No code is copied. Pagebound's [terms](https://support.pagebound.co/terms_of_use) forbid collecting data from the service by automated means and sending automated queries. The user accepted this risk on 2026-10-08. Public docs state that the connection is unofficial and can break.

### Verified in the user's Pagebound session on 2026-10-08

The user signed in on pagebound.co in the built-in browser and moved In the Blood to Reading at 50% (240 of 480 pages, format digital) themselves. Read-only probes used that session; nothing was written by the probes. No token, email, or account value is recorded here.

All API calls carry `Authorization: Bearer <token>`. Account endpoints answer **500 with an empty body** to a missing, malformed, or wrongly signed token, never 401; catalogue reads (`/books`, `/search`, `/editions`) answer 200 whatever the header. The token is an HS256 JWT whose payload holds only `user_id`; it has no expiry. Dates are `M/D/YYYY` without zero padding.

| Call | Verified behaviour |
| --- | --- |
| `POST https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=<PAGEBOUND_FIREBASE_KEY>` `{email, password, returnSecureToken: true}` | Standard Firebase; returns `idToken`, `refreshToken`, `expiresIn`. Not exercised (needs the password); the first device sign-in verifies it. Key `AIzaSyDCfBJ51pRZgHueBfBz0KNDiPNev1ClnGg`, project `pagebound-430920`, shipped in the site bundle. |
| `POST /auth/firebase_auth` `{id_token}` | Returns `token` and `user` (per the bundle and ShelfSync). Not exercised. Render cold starts can take a minute: use 30 s connect / 90 s read for this call. |
| `POST https://securetoken.googleapis.com/v1/token?key=…` | Firebase refresh, as `FableHttp.refresh`. Used when the API rejects the token (an empty 500, or 401/403), then the exchange is repeated. |
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

### Implemented Pagebound matching and progress policy

Sign-in posts the typed email and password to Firebase, exchanges the ID token at `/auth/firebase_auth` with the literal `Bearer null` the website sends, and stores the Pagebound token and the Firebase refresh token in a Keystore-encrypted vault. The Pagebound token has no expiry. An empty 500 (or a 401/403) on the account request that starts every send renews it once through Firebase; a second refusal, or a dead refresh token, clears the vault, which shows Reconnect required. Errors on any other request, including an empty 500, stay ordinary failures and never renew the session. API calls wait up to 90 seconds because the Render host sleeps.

Match a `pagebound:` book UUID whose page loads, then each ISBN-13 and its ISBN-10 through `/search?type=ISBN`, then one Typesense hit whose normalized title, with or without its trailing series parenthesis, and author match. ISBNs on different books, several title matches, and nothing found hold. Several ISBNs on one book choose the Kindle or ebook edition. The account is `user.uuid` from `/auth/get_authed_user`.

Finished and DNF hold a reading source; a finished source on Finished is current; `has_ever_finished` on any other status holds as a reread. A book not in the library is added with `POST /user_books` as `current` (or `finished`), format `digital`, the matched edition and its page count. TBR, Interested, and none move through `PUT /user_books/<uuid>` with format `digital`; Paused moves through `update_status`, keeping its read's format. An empty `edition_id` is set to the matched edition. Progress follows the Goodreads step rule: `POST /reading_updates` with `total_progress` = the percentage rounded down to a multiple of 5, `progress_method: percent`, `no_broadcast: false`, only when the step is above `user_book.progress`, then read back from `/user_books/<uuid>`; an update Pagebound did not show is not posted again. Completion is `PUT /user_books/<uuid>` with `status: finished` and the last reading day, without a reading update.

## Fable

Implemented in 0.4.0 under the [Fable plan](plan-20261006-fable-sync.md). Fable has no published developer API. The connector uses Fable's app API, as the MIT-licensed [ShelfSync](https://github.com/Lyfts/ShelfSync) KOReader plugin does. ShelfSync calls this an official API; that does not establish supported third-party access. Fable's [terms](https://fable.co/terms) prohibit access through automated means and scripts. The user accepted this risk on 2026-10-06. Public docs state that the connection is unofficial and can break.

Authentication is Firebase Identity Toolkit with Fable's public web API key, observed in Fable's site and kept in `FableAuth.kt`. It identifies Fable's Firebase project; it is not a credential.

### From ShelfSync source and Firebase documentation

These calls were not exercised in the 2026-10-06 browser session. The first device sign-in and the first refresh after one hour verify them.

| Call | Documented behaviour |
| --- | --- |
| `POST https://www.googleapis.com/identitytoolkit/v3/relyingparty/verifyPassword?key=…` with email, password, `returnSecureToken` | `idToken`, `refreshToken`, `expiresIn` 3600; errors as `error.message`, for example `INVALID_LOGIN_CREDENTIALS`. Legacy path; the current equivalent is `identitytoolkit.googleapis.com/v1/accounts:signInWithPassword` |
| `POST https://securetoken.googleapis.com/v1/token?key=…` with a form refresh grant | `id_token`, `refresh_token`, `expires_in`. Firebase refresh tokens have no timed expiry; they end on password/email change, disabled user, or revocation |

### Verified with the user's account on 2026-10-06

The user signed in on fable.co in the built-in browser. Read-only probes and user-authorized writes used that session. No account, list, or token values are recorded here.

| Call | Verified behaviour |
| --- | --- |
| `https://api.fable.co` with `Authorization: JWT <idToken>` | 403 without a token |
| `GET /api/settings/profile/` | `id` is the account UUID |
| `GET /api/books/search/?auto=…&include=out_of_catalog&type=book&limit=20&offset=0` | ISBN-13, ISBN-10, and ASIN queries return the exact record with `isbn` equal to the query. Goodreads IDs return nothing. Title queries return other authors and editions |
| `GET /api/books/{id}` | Includes `family_id` and the viewer's shelf `status`. Shelves are per edition |
| `GET /api/books/{id}/editions/` | Every edition in the family with `display_isbn`, `page_count`, `is_current_book`, and `format.category` |
| `GET /api/books/{id}/reading_progress` | `current_percentage`, `current_page`, `page_count`, `status` (`unread`, `reading`, `finished`), `selected_mode` |
| `POST /api/books/{id}/reading_progress` with `status: reading`, `social_accounts: []`, integer `current_percentage`, `selected_mode: percentage` | 201. Decimals fail with 400. Fable derives pages from the edition. The write does not shelve the book, accepts lower values, and at 100% sets status `finished` and moves the book to Finished. A later lower write leaves it on Finished |
| `GET /api/v2/users/{account}/book_lists…` and `/book_lists/{list}/books` | Four per-account system lists; list membership |
| `POST /api/v2/users/{account}/book_lists/book` multiselect | 200. A move from Finished to Currently Reading applied, but book detail showed it only later; read shelves from list membership. Adding an unshelved book is not yet verified |
| `RemoveFromLibrary` on the same endpoint | 200; the progress record remains |

No rate-limit headers were observed.

### Account details for the provider popup

`GET /api/settings/profile/` returned these keys on 2026-10-06, among others: `id`, `username`, `display_name`, `email`, `pic`, `signed_up_at`, `subscription_tier`, `followers_count`, `following_count`, `timezone`. Their values were not recorded. The app stores `username`, `display_name`, `email`, `signed_up_at`, and `subscription_tier` on the device for the details popup only.

Hardcover's documented "my information" query uses `me { id username name pro … }`; the app asks `me { username name pro }`. `email` needs the `read:me:email` scope, which the app does not request, so Hardcover shows no email. `created_at` is in the users schema but not in the documented query; the app asks for it separately and leaves it out when refused. Neither query has run against a real account yet.

### Reading streak, verified 2026-10-07

Fable's reading streak is a per-day record, separate from progress. Progress writes on 2026-10-06 created no streak day; the user's "I read today" tap in the Fable app on 2026-10-07 did, linked to In the Blood, and also moved progress to 52%. The streak screen is native to the app; the website only reads streak stats. `GET https://api.fable.co/api/` lists the API routes, including the `v2/reading` family.

| Call | Verified behaviour |
| --- | --- |
| `GET /api/v2/reading/?timezone=<IANA zone>` | Currently-reading screen: `streaks.current_streak`, `streaks.days_of_the_week` (`date`, `day_name`, `value`), `streaks.streak_book_ids`, and books with `reading_progress`. Without `timezone` it returns 400. Days are local dates |
| `GET /api/v2/reading/streaks/history?limit=&offset=` | Paged days (60 in total) with `date`, `day_name`, `value` |
| `POST /api/v2/reading/streaks/history` JSON `{date: "YYYY-MM-DD", day_name: "Wednesday", value: true, book_ids: [<book UUID>]}` | 201, empty body. Without `book_ids` it returns 400 `{"book_ids":["This field is required."]}`. Repeating it for an already-marked day with the same book created no second entry and left progress unchanged. Marking a day that is not yet marked, dates in the past, and a different book on an already-marked day are not yet verified; a 201 alone does not prove the day was marked |
| `GET /api/users/{account}/stats/charts/monthly_streaks/?year=` | Streak days per month with their books, best streak, and total |

The first test write's response was lost to a bug in the test script; the read-back showed no change. A resend without `book_ids` was rejected, and the write with `book_ids` was accepted. All three targeted today, which the user had already marked.

### Implemented Fable matching and progress policy

Match an explicit `fable:` UUID, then exact ISBN-13, ISBN-10 (only if its ISBN-13 found nothing), ASIN (only without ISBN results), then title and author. Title matches compare normalized titles and word-order-independent author names. Several records are accepted only inside one family; the eBook edition with pages is preferred. Conflicts and ambiguity hold.

Read shelves from system-list membership, because book detail status can lag. A family edition on Currently Reading, then Want to Read, receives progress (`existingEditionPreserved`). A Finished or Did Not Finish edition holds a reading source. Higher remote progress is kept. Page-mode progress holds. Writes shelve to Currently Reading first, post the floored percentage, and confirm it by reading it back. Completion posts 100% and confirms or sets the Finished shelf.

## Margins.app

The inquiry **was sent** to `help@margins.app`; **no reply/access** was reported as of 2026-10-05. No credentials or approval are available. Do not resend/follow up without user instruction.

It asked about public/private/beta access, progress/status/time history, Goodreads-ID matching, authentication, documentation, and limits. No confirmed public API or suitable client was established; earlier terms research raised automated-access restrictions. Recheck current terms and supported access before implementation. `margins:` display support is not an integration. Leads: [Margins](https://margins.app/) and [terms](https://margins.app/terms).

## Matching and update policy

The current product requires no questions, book chooser, or match confirmation. Missing exact editions can use a safe book match/page basis; conflicts and ambiguity remain errors. Title/author fallback is already built, not future-only work. Completion of the detected book is built; rereads remain held. Stored provider lastAccess can lag; matching a destination does not improve source detection.

The order of open physical checks is in [project status](project-status.md#resume-here). Margins API/authentication, rereads, and wider lifecycle policy remain separate work. Credentials, private exports, full paths, and unrelated provider blobs stay out of source/logs/exports. See [product](product.md) and [device checklist](device-testing.md).
