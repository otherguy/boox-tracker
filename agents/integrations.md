# Destination-service research

Updated 2026-10-06 for Boox Tracker 0.3.3. The [automatic matching/offline plan](plan-20261006-automatic-offline-sync.md) supersedes exact-ISBN-only and catalogue-selection proposals. Findings below are dated evidence, not guarantees about future API availability. Recheck official contracts before a new integration or dependency change.

## Current automatic matching evidence: 2026-10-06

| Destination | Implementation / evidence |
| --- | --- |
| Hardcover | Native device OAuth, automatic matching, conservative progress, durable queue; user confirmed manual exact matching and delivery on 0.3.1 |
| Goodreads | IDs extracted and used through Hardcover mappings; no Goodreads connector/authentication/sends |
| StoryGraph | Explicit identifier display only; Coming Soon, no connector |
| Fable | Explicit identifier display only; Coming Soon, no connector |
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

Use an active remote read's edition first. Otherwise use exact matched edition, default ebook, then default physical. Verify book ownership and positive pages. Preserve the remote edition even if source differs, with an amber explanation. A remote basis without pages holds; a pageless source edition can fall through to a valid default. Keep higher remote progress/history. A finished source completes the read and sets Read ([completion plan](plan-20261006-completion-sync.md)); rereads, paused/completed conflicts, and catalogue edits are held or excluded.

Tag/URL types follow the inspected [Calibre conventions](https://github.com/RobBrazier/calibre-plugins/blob/main/plugins/hardcover/README.md): `hardcover-edition` is an edition ID, `hardcover-id` a numeric book ID, `hardcover-slug` a book slug. `hardcover` is normalized to a book ID for numeric values or a slug otherwise. Accept supported book/edition URLs. Never treat a Goodreads number as a Hardcover ID.

`BookIdentifiers.kt` is the single reader allowlist. The popup labels its output without a second allowlist. `amazon`/`mobi-asin` normalize to ASIN. Future explicit StoryGraph/Fable/Margins values accept `[A-Za-z0-9][A-Za-z0-9._-]{0,299}` for display only. URLs, paths, whitespace and unrelated tags are rejected; no remote mapping contract is claimed for them. Ebook cache keys use `ebook.identity.2.<digest>` so older extraction results are bypassed without deleting history.

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

No generally available public developer API was established. The official [API roadmap](https://roadmap.thestorygraph.com/features/posts/an-api) showed Long-term on 2026-10-05. Unofficial website/session clients have different capabilities and require fresh source/contract checks.

A user-supplied KOReader screenshot showed manually copied browser cookies and expired authentication. That proves that reference workflow only, not automatic cookie extraction or native app authentication here. No StoryGraph login/send path exists. A displayed `storygraph:` tag is not proof of a supported API identifier.

Leads: [app](https://app.thestorygraph.com/), [storygraph-wrapper](https://github.com/BrunoJurkovic/storygraph-wrapper), [storygraph.koreader](https://github.com/cernoh/storygraph.koreader), and [ShelfSync](https://github.com/Lyfts/ShelfSync). Validate progress/completion, challenges, expiry, reconnect, and licences before choosing an approach.

## Fable

Historical ShelfSync research found email/password authentication followed by access/refresh tokens. An upstream description called it an official API; this does not establish public third-party access. No Fable authentication, mapping, progress, or token flow is implemented/tested here. The explicit `fable:` tag is display-only.

Leads: [Fable](https://fable.co/) and [ShelfSync](https://github.com/Lyfts/ShelfSync). Validate endpoints, supported access, progress/status, refresh, and expiry before implementing.

## Margins.app

The inquiry **was sent** to `help@margins.app`; **no reply/access** was reported as of 2026-10-05. No credentials or approval are available. Do not resend/follow up without user instruction.

It asked about public/private/beta access, progress/status/time history, Goodreads-ID matching, authentication, documentation, and limits. No confirmed public API or suitable client was established; earlier terms research raised automated-access restrictions. Recheck current terms and supported access before implementation. `margins:` display support is not an integration. Leads: [Margins](https://margins.app/) and [terms](https://margins.app/terms).

## Matching and update policy

The current product requires no questions, book chooser, or match confirmation. Missing exact editions can use a safe book match/page basis; conflicts and ambiguity remain errors. Title/author fallback is already built, not future-only work. Completion of the detected book is built; rereads remain held. Stored provider lastAccess can lag; matching a destination does not improve source detection.

Next validation: book-only fallback and hidden-app offline/reconnect delivery. Other service APIs/authentication, rereads, and wider lifecycle policy remain separate work. Credentials, private exports, full paths, and unrelated provider blobs stay out of source/logs/exports. See [product](product.md) and [device checklist](device-testing.md).
