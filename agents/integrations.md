# Destination-service research

## Current automatic matching evidence: 2026-10-06

The [0.3.0 approved plan](plan-20261006-automatic-offline-sync.md) supersedes exact-ISBN-only matching proposals below. Hardcover exposes book/edition identities, default editions, external mappings, and search. Native OAuth authentication and mutation privileges still need physical validation; read-only PAT queries are separate evidence.

Live authenticated read-only lookups resolved In the Blood ISBN 9781398508255/139850825X to book 510350 and edition 33373487 (480 pages). Goodreads 58438630 mapped to book 510350 and a different edition, so it supplies work identity only. The book has no default ebook edition and default physical 30462394 with 480 pages.

Savage Son ISBN 9781471197376/1471197379 and Goodreads 58895717 were absent. ASIN B07THCSQ27 matched book 484869 and ebook edition 31807724 (429 pages). Its default ebook is that edition; default physical 30425587 also has 429 pages. Exact normalized title and author search distinguished Savage Son from omnibus results. No catalogue edits or mutations were performed during those research lookups.

The live progress input accepts pages/seconds and nullable edition ID; no direct percentage mutation exists. 0.3.0 resolves a verified positive-page-count basis, preserving an existing remote edition. URL/tag types follow [Calibre conventions](https://github.com/RobBrazier/calibre-plugins/blob/main/plugins/hardcover/README.md). Schema/search sources: [schema](https://github.com/hardcoverapp/hardcover-docs/blob/main/schema.graphql), [search](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/guides/Searching.mdx). Search fields use matching weights. No token is stored in this document.

All five destinations are requested product scope. The 0.2.0 Hardcover preview implements native device-code authentication, read-only EPUB identifiers, exact ISBN edition matching, and conservative progress updates. Other connectors are not implemented. Hardcover's official documentation source, published schema, and live OAuth discovery were checked on 2026-10-05; StoryGraph's public API roadmap was also checked. Other findings remain historical research supplied by the user, not freshly verified API contracts. Validate real account operations before calling the preview physically verified. Do not infer that a screenshot, repository description, or successful third-party workflow proves our app can do the same.

## Hardcover

The user is preparing Hardcover as the first integration. On 2026-10-06, they confirmed an account and supplied the public OAuth client ID `bc5f2c0f-79d7-42b5-b525-6293454d3934`. The recommended registration is a public "Mobile, desktop, or CLI" app with Device Authorization Grant enabled, no redirect URIs, and the E-Reader / Sync Client preset: `read:catalog`, `read:library`, `write:library`, and `read:me:content`. The form and official preset source were inspected when giving these settings; the saved registration has not been independently verified. No access/refresh token has been obtained or authenticated operation tested. The user subsequently authorized implementation through APK delivery without further approval stops. The first build uses exact ISBN matching, approximate edition pages, keep-higher conflicts, and holds completed/reread cases. See [the implementation plan](plan-20261006-hardcover-first.md).

The public client ID identifies the application and is shared by all installations of its APK. Each user approves their own account/session; account access uses separate private tokens stored on that device. The official OAuth source was rechecked on 2026-10-06 and documents this multi-user flow. Boox Tracker is now the app's visible name; the developer registration's consent-screen name is external configuration and has not been changed here. Update that existing registration's name rather than deleting it or replacing its ID; deleting an OAuth app revokes all issued user tokens. Do not change the shared ID as part of local branding work.

### Verified access research: 2026-10-05

The documentation website returned HTTP 403, so the official `hardcoverapp/hardcover-docs` source was read instead. Its Getting Started guide still calls the API beta. It documents scoped Personal Access Tokens with user-selected expiry and recommends OAuth for applications used by other people. Do not carry forward the earlier token-only assumption or assume the old yearly token lifetime applies to newly issued tokens.

The OAuth guide documents public native clients, authorization code with PKCE, and Device Authorization Grant. Device sign-in can show a code on BOOX for approval on another device; public clients require no embedded client secret or project-hosted authentication server. A read-only request to `https://api.hardcover.app/.well-known/oauth-authorization-server` confirmed advertised authorization-code, refresh-token, and device-code grants with `S256`. A public client was later registered by the user; no account was authorized or real authenticated request tested in this workspace. These are contract/discovery checks, not proof of a working Reading Sync login.

The published schema contains `insert_user_book_read` and `update_user_book_read`, using `DatesReadInput` with `edition_id`, `progress_pages`, and `progress_seconds`; this input has no direct percentage field. Its progress-writing guide is still a draft placeholder. Validate the current authenticated schema and chosen edition's progress conversion before promising exact percentage writes. BOOX fraction units remain unrelated to physical pages. The implemented first build matches exact ISBN automatically and holds missing/ambiguous editions; it has no source-book selector or catalogue chooser.

Primary source references:

- [Getting Started source](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/Getting-Started.mdx)
- [OAuth source](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/OAuth.mdx)
- [Native/device sign-in source](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/OAuth/Getting-Started-Device.mdx)
- [Published GraphQL schema](https://github.com/hardcoverapp/hardcover-docs/blob/main/schema.graphql)
- [Live OAuth discovery](https://api.hardcover.app/.well-known/oauth-authorization-server)

Validate authentication/token lifetime, rate limits, book-versus-edition identifiers, progress representation, reading records/completion, and Goodreads-ID matching. A Goodreads ID is not a Hardcover identifier. The first build converts the raw fraction to approximate matched-edition pages, rounded HALF_UP, and holds completion. Device fraction units are not physical pages.

References:

- [API/playground](https://api.hardcover.app/)
- [API documentation](https://docs.hardcover.app/)
- [Account API page](https://hardcover.app/account/api)
- [Developer API roadmap](https://roadmap.hardcover.app/feature-requests/posts/developer-api)
- [Billiam/hardcoverapp.koplugin](https://github.com/Billiam/hardcoverapp.koplugin), a reference for API shapes and edition/page mapping; inspect its licence before reuse

[sleepdebt/boox-hardcover](https://github.com/sleepdebt/boox-hardcover) is also relevant for matching/status/progress, but its desktop ADB access is a different architecture. The inspected revision/licence limits are in [provider research](research.md).

## Goodreads

User-confirmed input: most EPUBs contain ISBNs, and slightly fewer contain Goodreads IDs. Two local EPUB samples expose different OPF identifier representations, recorded under [matching samples](#local-matching-samples-2026-10-06). The parser now supports both inspected ISBN representations using synthetic EPUB tests. Goodreads mapping is not implemented. File inspection on this computer does not prove the Android app can read those files on BOOX.

Historical research reported that new public API keys stopped being issued in December 2020, and that unofficial integrations use authenticated website sessions/cookies. Do not plan around obtaining a new legacy API key or describe website-session automation as a supported developer API without evidence.

Validate the identifier representation, usable authentication/reconnection route, CSRF handling, challenges/session expiry, current endpoints, and permitted progress/status operations. No login or cookie extraction flow has been demonstrated for Reading Sync.

References: [Goodreads developer group](https://www.goodreads.com/group/show/8095-goodreads-developers) and [Lyfts/ShelfSync](https://github.com/Lyfts/ShelfSync). The latter is an implementation lead; inspect current source/licence before reuse.

## StoryGraph

Historical research did not identify a generally available public developer API. Its official [API roadmap](https://roadmap.thestorygraph.com/features/posts/an-api) still showed **Long-term** when checked on 2026-10-05. Unofficial website/session clients have different capabilities. An earlier Python-wrapper inspection found progress functionality with incomplete/planned completion handling; recheck current source. A discussed native Kobo integration does not serve this BOOX/NeoReader flow.

A user-supplied screenshot of an existing KOReader integration showed expired authentication and a manual workflow: log in through a browser, inspect its cookie store, copy `_storygraph_session` and `remember_user_token`, paste them into that tool, and re-enable sending. This establishes that reference tool's workflow only. It does not establish automatic cookie extraction or convenient browser sign-in in Reading Sync. Cookie names are research inputs, not credentials to log or embed.

Reconnect is a UI proposal. Its implementation must match an authentication mechanism actually validated for this service. Check progress/completion operations, expiry, session challenges, and reconnect behavior before promising support.

References:

- [StoryGraph app](https://app.thestorygraph.com/)
- [API roadmap](https://roadmap.thestorygraph.com/features/posts/an-api)
- [BrunoJurkovic/storygraph-wrapper](https://github.com/BrunoJurkovic/storygraph-wrapper)
- [cernoh/storygraph.koreader](https://github.com/cernoh/storygraph.koreader)
- [Lyfts/ShelfSync](https://github.com/Lyfts/ShelfSync)

## Fable

Historical research found a ShelfSync connector using email/password followed by access/refresh tokens. An upstream description called this an official API; that does not establish public developer access or a supported third-party contract.

Validate current endpoints, authentication requirements, progress/status operations, refresh, expiry, and reconnect behavior. No Fable account or token flow is implemented or tested here.

References: [Fable](https://fable.co/) and [Lyfts/ShelfSync](https://github.com/Lyfts/ShelfSync).

## Margins.app

The API inquiry email **was sent** to `help@margins.app`. The user reports **no reply** as of 2026-10-05. This supersedes any older drafted/unsent description. No API credentials, integration access, or approval have been reported. Do not send further messages without user instruction.

The inquiry asked about public/private/beta access; reading progress/status and time/session history; Goodreads-ID matching; authentication; documentation; and limits. Await the reply rather than treating Margins as a working connector.

Historical research did not identify a confirmed public API or a suitable established unofficial client. Earlier terms review raised automated-access restrictions. Recheck current terms and obtain a supported integration route before implementing access. Margins remains a requested destination with pending access, not a removed feature or working integration.

References: [Margins](https://margins.app/) and [terms](https://margins.app/terms).

## Matching and update policy

ISBN-first matching has been discussed; the user also wants title/author matching later for readers without identifiers. Goodreads IDs are useful input but do not resolve cross-service catalogue or edition matching. The two inspected OPF representations are evidence for those samples only. The authorized first build uses read-only SAF folder access if provider ISBN is absent, bounded container/OPF parsing, exact ISBN-10/13 matching, and raw-fraction to edition-pages conversion. It updates only the most recent detected saved book, not historical library backfill. It keeps higher remote progress and holds completion, known different editions, completed/paused read history, and rereads. Wider matching/lifecycle behavior remains undecided.

The user confirmed automatic current-book sync on 2026-10-06: detect the book being read, resolve its tracker record, and update that record. Remove all source-book selectors, including the existing Diagnostics chooser. Catalogue-match resolution is a separate operation. The source provider has not exposed a verified live open-book signal; the detection implementation must account for delayed saved state and uncertainty in `lastAccess`.

### Local matching samples: 2026-10-06

The user supplied two local EPUBs for matching research. Inspection read only `META-INF/container.xml` and the referenced OPF metadata. The files are ignored by Git; do not commit or upload the ebooks. Use minimal synthetic metadata fixtures for automated tests, without copying book content or private UUIDs.

| Local sample | Inspected metadata | Catalogue case |
| --- | --- | --- |
| `In the Blood - Jack Carr.epub` | EPUB 3.0; title `In the Blood`; creator `Jack Carr`; `dc:identifier` text `isbn:9781398508255` and `goodreads:58438630` | User reports the ISBN was absent from Hardcover and they added it to [the book's editions](https://hardcover.app/books/in-the-blood-2022). The added edition has not been verified through the API. |
| `Savage Son - Jack Carr.epub` | EPUB 2.0; title `Savage Son`; creator `Jack Carr`; `dc:identifier` with `opf:scheme="ISBN"` and text `9781471197376`; another with `opf:scheme="GOODREADS"` and text `58895717` | User reports book 3 in the series, with this ISBN absent from Hardcover and not yet added. Keep it as a reported missing-edition case; API absence has not been independently verified. |

An identifier reader must inspect both the identifier text and its namespace-qualified scheme attribute. A package identifier or attribute name such as `eisbn` does not prove its value is an ISBN: the inspected EPUB 3 sample's `eisbn` identifier contains a UUID. The tested BOOX provider exposes an `ISBN` column, but its populated values remain unverified; the repository now preserves that read-only field and prefers a valid ISBN from it before trying EPUB access.

These cases separate a present local ISBN from a missing remote edition. A missing edition must remain unresolved rather than selecting an unrelated edition or creating catalogue records automatically. Title/author search and a destination-edition resolution UI remain possible later fallbacks; neither is implemented. There is no source-book chooser.

One tested finished record changed from status `2`/100% to `1`/99.31% after backward page turns and exit. Preserve that source evidence; do not assume remote completion is irreversible or choose a reread policy from it. Multiple library records can represent similar book names, so title alone is not a proven identity.

Proposed behavior for ambiguous matches is to show Choose book, hold only the affected update, and continue unrelated books/services. This is not implemented. Failed/expired services were proposed to pause independently while retaining user intent to enable them. See [product proposals](product.md#screen-and-identity) for the unresolved UX contract.

Existing diagnostic export schemas and privacy boundaries must remain safe when credentials or tracker activity are later added. Passwords, cookies, access/refresh tokens, and unrelated source blobs must not enter logs or exports.
