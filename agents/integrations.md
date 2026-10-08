# Destination-service research

The [automatic matching/offline plan](plan-20261006-automatic-offline-sync.md) supersedes exact-ISBN-only and catalogue-selection proposals. Findings below are dated evidence, not guarantees about future API availability. Recheck official contracts before a new integration or dependency change.

## Destination summary

| Destination | Implementation / evidence |
| --- | --- |
| Hardcover | Native device OAuth, automatic matching, conservative progress, durable queue; user confirmed manual exact matching and delivery on 0.3.1 |
| Goodreads | IDs extracted and used through Hardcover mappings; no Goodreads connector/authentication/sends |
| StoryGraph | 0.5.0 website session: sign-in in an in-app WebView, `HttpURLConnection` with the session cookies, automatic matching confirmed by edition pages, currently-reading and read writes, durable queue; no API; Cloudflare transport spike pending on the device |
| Fable | 0.4.0 email/password sign-in, automatic matching, shelving, percentage progress, durable queue; unofficial app API; physical checks pending |
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

Goodreads IDs are extracted from provider/EPUB metadata and used through Hardcover external mappings where available. That is implemented Hardcover matching, not a Goodreads integration. No Goodreads sign-in, cookies, API sends, or catalogue writes exist.

Historical research reported that new public API keys stopped in December 2020, and unofficial integrations used authenticated website sessions/cookies. This is not a currently verified supported developer contract. Before implementation, establish a usable authentication route, current endpoints, permitted progress/status operations, expiry/challenges, and reconnect behavior. Do not assume ASIN is required for Goodreads merely because Hardcover uses it.

Leads: [developer group](https://www.goodreads.com/group/show/8095-goodreads-developers) and [Lyfts/ShelfSync](https://github.com/Lyfts/ShelfSync). Inspect current source/licence before reuse.

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

The order of open physical checks is in [project status](project-status.md#resume-here). Goodreads and Margins APIs/authentication, rereads, and wider lifecycle policy remain separate work. Credentials, private exports, full paths, and unrelated provider blobs stay out of source/logs/exports. See [product](product.md) and [device checklist](device-testing.md).
