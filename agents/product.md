# Product context

Confirmed context as of 2026-10-06, current app 0.4.0/code 12. The [approved automatic/offline plan](plan-20261006-automatic-offline-sync.md) is implemented. [Verification](verification.md) separates implementation from physical evidence. Historical plans do not override these decisions.

## Confirmed direction

Boox Tracker is an open-source Android companion: NeoReader saved metadata → companion → enabled trackers. Keep NeoReader as the reader. Hardcover is implemented and user-confirmed manual exact sync works. Fable is implemented in 0.4.0 through its unofficial app API under the [Fable plan](plan-20261006-fable-sync.md); physical checks are pending. Goodreads, StoryGraph, and Margins remain Coming Soon. No two-way NeoReader writes, hosted sync backend, source-book chooser, destination-match confirmation, or catalogue editing is approved.

The user expects the ebook reader to be offline most of the time. Collect and queue locally without asking for Wi-Fi. A connected account can enable offline and stays On during offline periods. First-time connection failure returns Off with an inline message. Off pauses sends without deleting pending items; the same account can resume. A different account must never receive earlier-account updates. Background work never launches approval UI.

Use the latest unique usable saved `lastAccess` for automatic detection. Missing or tied timestamps stop the update rather than selecting an arbitrary book. This detects the latest saved activity, not a verified live open-book state. A book switch in NeoReader may not appear until metadata persists. No library-wide backfill is included.

### Identifiers and automatic matching

Extract allowlisted identifiers from provider metadata for all formats and bounded EPUB metadata when readable. Non-EPUB formats use database identifiers/title/authors; embedded parsers for those formats are not built. Database titles take priority in the header; use Unknown title when absent, not the filename.

Resolve explicit Hardcover edition IDs, book IDs, slugs/URLs first, then ISBN-13/ISBN-10 and ASIN, accessible Goodreads mappings, then one normalized title/recorded alternative-title plus author match. Multiple matching editions of one book establish book identity. Conflicting IDs or ambiguous results hold the update with a visible error. Never ask the user to select or confirm a match. Revalidate changed source metadata; successful match caches expire after one hour.

The shared identifier reader is the only tag allowlist. The UI labels its results. ISBN, ASIN, Goodreads, and Hardcover edition/book/slug values are used or displayed. Fable matching uses `fable:` tags that hold a Fable book UUID. Explicit StoryGraph/Margins tags are display-only bounded IDs; they do not establish future API support. Unknown tags are ignored. Keep ASIN because Hardcover can match editions with it, not because a Goodreads connector exists.

### Progress and history

BOOX fraction units are not physical pages. Use its calculated percentage without an offset. Hardcover's current progress input accepts pages/seconds, so percentage delivery needs a verified positive edition page count. Prefer an existing active remote read's edition; otherwise exact source edition, default ebook, then default physical. Verify that the basis belongs to the matched book. Preserve an existing edition even if it differs from the source; explain that with the amber match state. If the existing basis has no usable page count, hold instead of changing the interpretation of history.

Convert the fraction to approximate pages, rounded HALF_UP. Preserve higher remote progress, completed/paused/reread history, ratings, and dates. Missing page counts or protected history hold the update. Do not create catalogue records or alter a user's ebook to force a match.

Completion sync was approved on 2026-10-06 for the detected book only. When NeoReader saves status `2` with a full fraction, the app writes full pages and a finish date to the read, then sets the Hardcover status to Read. The finish date is the device-local date of the provider last-access time, falling back to the query date. No start date is written. A remote book already marked Read is left unchanged and reported as current. A not-yet-Read book with exactly one finished read, whether from an interrupted earlier write or the user, only receives the status update; that read keeps its pages and date. Equal or higher remote pages do not stop a finish; the higher count is kept. Status `2` with a partial fraction, or status `1` at 100%, holds until NeoReader's state is consistent. Rereads remain held: reopening a finished book can return it to status `1`, and a remote finished read then protects history as before.

### Durable collection and delivery

Persist the latest observation per account/service/source book before delivery, while retaining full observation logs. Switching books cannot replace another book's pending item. Acknowledge only the revision sent; retain newer observations. Read remote state before retrying an uncertain write to avoid duplicate reads or reduced progress. Retry transient network/server failures with backoff.

Local collection is always on: unique fifteen-minute WorkManager requests without a network constraint. Separate unique network-constrained work serializes delivery. Opening the app and pressing Sync Now collect fresh state and attempt delivery. Timing is best-effort; no exact fifteen-minute, sleep, or post-boot execution guarantee is established. The observation service and user Background checks toggle are removed.

## Screen and identity

Sync / Activity follow the [approved e-ink design](design.md). The header contains database title, provider percentage, library count, read time, green circular check or amber triangle, and black Sync Now with white text. Tap the book for a bordered metadata popup. There are no lower diagnostic/latest-progress sections or Read Now button. Device Information is in Activity; About has a border and folder replacement.

Each enabled service displays exact-edition/book-only/error matching separately from Pending/Synced at/specific failure. Keep the current book's last successful time and progress independently of the latest attempt. A previous book's success cannot appear as success for the current book. Normal offline waiting is neutral. Disabled services do not cause warnings. Service toggles initiate connection when needed; no dedicated Connect button.

Readable persisted read-only SAF ebook-folder access is mandatory. Explain what to select before the picker opens: Exit / Choose folder. Cancellation with no valid grant shows Exit / Allow again. Cancelling a replacement keeps an existing readable grant. Missing/revoked access blocks foreground work and causes a recorded background failure without UI or sending. Provider access itself needs no storage grant; this folder requirement is the approved product setup policy.

The popup shows full available identifier values and omits absent service tags. Labels are bold Title Case, with ISBN/ASIN unchanged. NeoReader Database and Progress State show OK only for successful checks. Diagnostic `progressProblem: null` means no parsing error. Last Access is provider metadata; Read At is the query timestamp, formatted with local date/time preferences. Unsupported timestamps retain their raw value/state.

Package/namespace changed in 0.3.0/code 7 to `dev.otherguy.booxtracker`; development adds `.debug`. This intentionally starts fresh data and does not migrate old credentials/logs. Preserve any old app/data if present and disable its sends before running both. The old package was absent during the USB handoff; no agent uninstall/data clear was issued. Reuse the external diagnostic signer and shared public OAuth client ID. Compatible 0.3.x updates retain settings/logs. SQLite and export schemas remain 1; ebook cache namespace is separately versioned at 2. Activity displays 250 recent events, export retains all history, and no deletion policy is approved.

## Findings that constrain the product

| Finding | Product limit |
| --- | --- |
| Ordinary-app Metadata access works on one GoColor7 firmware | Do not assume other devices/firmware work or replace it with privileged access |
| Most library records are code `0` with null progress; codes `0`/`1`/`2` mean not started/reading/finished on the tested device | Keep unknown/missing/error states distinct; never substitute 0%; do not interpret other codes |
| The same work can appear as two provider records, one untouched and one with real state | Key sync on the matched remote book and the record with usable state, never on file count |
| In-book percentages differ from provider/library values | Do not add an offset or claim exact physical pages |
| Saved metadata sometimes changes on exit, and also after a later wake/read | Do not claim every page turn persists or exit is the only trigger |
| Font size 28 → 42 left one inspected fraction unchanged | Useful one-book evidence, not a universal pagination rule |
| Independent historical worker reads succeeded after boot/near wake | New queued network delivery still needs its own hidden-app test |
| Manual native OAuth/exact sync reached Hardcover | Book-only, offline queue/reconnect, and scheduling proof remain separate |

Exact timed evidence, device/build fields, status examples, and export counts belong in [verification](verification.md), not public README. Completed diagnostic checks need no repetition merely to reach an older requested duration.

## Open decisions and next work

The next approved work is physical validation in [device testing](device-testing.md): book-only matching, offline multi-book retention, and hidden-app reconnect delivery. Core implementation and automatic coverage are built. Do not describe unverified execution as a missing implementation, or treat a manual send as proof of scheduled delivery.

| Area | Missing implementation or decision |
| --- | --- |
| Other services | Fable built on an unofficial API. Goodreads/StoryGraph/Margins: supported APIs/authentication undecided. Margins inquiry sent, no reply/access reported as of 2026-10-05 |
| Reading lifecycle | Rereads and any wider conflict policy. A Sync All backfill button was considered and rejected on 2026-10-06 in favour of completion sync for the detected book, which is now built |
| Sources | Embedded non-EPUB metadata parsers and separately scoped statistics-provider access, if needed |
| Website | Proposed project/download site under an unspecified otherguy.dev subdomain; hosting not selected |
| History and distribution | Retention/deletion policy, updater, optional Obtainium/store channels |

GitHub Releases is the eventual APK channel, but prereleases/releases remain on hold until explicit user confirmation. Existing CI configuration is not a current remote run. Current changes are local; a future source push and CI run must be verified separately. Use mise and `.tool-versions` for all host tools, including Java; no Homebrew dependency is required.
