# Boox Tracker instructions

GitHub prereleases and releases are on hold until the user confirms Boox Tracker is a working app. Local diagnostic APK builds and ordinary CI checks can continue. Do not run the prerelease workflow while this hold applies.

## Start here

Read the public [README](README.md) for user-facing behavior and [project status](agents/project-status.md) for the implementation handoff, [product context](agents/product.md) for requirements and open decisions, and [verification](agents/verification.md) for actual device evidence. Read [integration research](agents/integrations.md) before choosing or implementing a tracker and [design](agents/design.md) before changing the UI. Do not restart the project because historical provider research was uncertain: the installed diagnostic app already reads Metadata on the tested device.

Check `git status --short --branch` before editing. Preserve unrelated changes and staged state. The 0.1.1/0.1.2 implementation and documentation were local, uncommitted changes at the 2026-10-05 handoff; check the actual checkout before assuming they were pushed. Do not force-push based on the historical one-time replacement of the remote's generated licence commit.

Use `rg`/`rg --files` for searches. When the repository has a current code-review graph, use it to locate symbols before broad code searches, then verify callers/tests with `rg`; documentation/configuration lookups do not require the graph. Before treating a secret-backed CLI as unauthenticated, retry through the interactive environment with `TERM=xterm-256color zsh -lic '<command>'`.

Use plain, concise, actionable English. Limit lists to five items and split longer lists into clear groups. Explain errors by location, cause, and fix. Give a short progress update before tools and a self-contained handoff. Keep confirmed requirements, observed device results, research leads, proposals, and unknowns separate.

## Product and source boundaries

Boox Tracker is the app name selected by the user on 2026-10-06. Older device evidence, plans, and artifacts use the former name Reading Sync. Use Boox Tracker in visible labels and public documentation. The approved 0.3.0 plan changes package/namespace to `dev.otherguy.booxtracker` and starts fresh data; the old app and its logs must remain installed. Preserve the external signing identity and configuration. Do not restore obsolete package/work names into the new app.

Boox Tracker is an open-source Android companion that keeps NeoReader as the reader. The intended direction is NeoReader state → companion → enabled trackers. Margins.app, Goodreads, StoryGraph, Fable, and Hardcover are requested destinations. Hardcover is the first implemented preview connector; other destinations are not implemented. The user supplied a public OAuth client ID; see [integration research](agents/integrations.md) and [first-connector plan](agents/plan-20261006-hardcover-first.md). Native device OAuth, read-only provider/EPUB identifier extraction, automatic exact/book-only matching, durable offline delivery, and conservative progress writes are in scope. Physical integration validation is pending.

The product sync flow must detect the currently read book automatically and update its matched tracker record. Do not require a general book selector or ask the user to choose between selected-book sync and whole-library sync again. There must be no source-book selector anywhere in the app, including Diagnostics. Ignore obsolete saved selection state; missing or tied usable access timestamps must not select an arbitrary book. Resolving an uncertain destination edition is separate from deciding which source book is being read. Detection must preserve the documented limit: stored `lastAccess` can lag and does not prove which book is currently open.

Query BOOX data read-only through `ContentResolver.query()` under the app's ordinary UID. The validated URI is `content://com.onyx.content.database.ContentProvider/Metadata`. Never modify BOOX records, open private database files, require root/ADB privileges, or bypass Android permissions. Provider visibility is not an access permission. Do not request broad storage access to work around provider failures. Provider access itself needs no storage grant. The approved app startup requires a persisted readable read-only SAF ebook-folder grant; missing/revoked access blocks the app with Exit / Allow again. Cancelling a folder replacement keeps the previous readable grant. Workers record missing grants without UI or sending.

The statistics-provider URI in [research](agents/research.md#statistics-provider-lead) is not implemented or device-verified. KOReader projects are references; do not make KOReader a product dependency or add two-way position writes into NeoReader.

The user approved [automatic matching and offline sync](agents/plan-20261006-automatic-offline-sync.md) through signed APK delivery. This supersedes the earlier exact-edition-only and observation requirements. Never show source-book or destination-match selection/confirmation dialogs. Resolve Hardcover IDs/slugs/URLs, ISBN/ASIN, accessible Goodreads mappings, then one unique normalized title/author match; hold conflicting or ambiguous evidence. Preserve an existing remote edition and higher progress; hold completion/rereads/history conflicts. Do not edit catalogues or backfill the full library. Other trackers, automatic completion/rereads, website, and updater remain outside this scope.

Margins' API inquiry was already sent to `help@margins.app`; no reply or access approval was reported as of 2026-10-05. Do not resend or follow up without user instruction.

## Data and execution rules

- Keep raw progress and status codes. Calculate a percentage only for a valid fraction. Null, missing, malformed, zero-denominator, unreadable, and out-of-range progress must stay distinguishable; unknown is not 0%.
- Use the provider-derived percentage without adding an offset to match NeoReader's in-book display. Its fraction units are not physical pages. Most-recent selection uses stored `lastAccess`, not proof of the book currently open. Keep query time separate from NeoReader's timestamp.
- Distinguish provider unavailable, permission denied, failed query, successful empty library, and successful records with unusable progress. Query off the main thread, close cursors, and preserve useful failure evidence.
- Local collection is always enabled as unique fifteen-minute WorkManager work without network constraints. Separate network-constrained delivery drains the durable account/service/book queue. Foreground/manual collection attempts delivery. No observation service, observer polls, user background toggle, app-managed wake lock, or forced screen-on behavior remains.
- Count a worker as independent background evidence only with Boox Tracker hidden throughout the reported interval; inspect start/finish visibility and pre-app-open timestamps. Opening the app automatically collects and attempts delivery. A foreground service, app-open-triggered worker, or successful ADB query does not prove that case. No exact interval or post-boot guarantee has been established.

## UI, privacy, and persistence

Follow the [approved design](agents/design.md): white background, black labels, strong separators, amber warning triangle, large touch targets, and static transitions. Colour is supplementary to text and icon shapes. Avoid animations, spinners, fading, marquees, rapidly updating timers, and unnecessary redraws. Tabs are Sync and Activity. The header shows database title/progress, count, read time, status icon, and black Sync Now. Tapping the book shows bordered metadata. Device information is in Activity. About is bordered and can replace the mandatory ebook folder. Before the startup folder picker, explain which folder to select and why read access is required. Open the picker only after the user presses Choose folder; do not launch it automatically.

Services already connected can enable offline and queue. First-time connection failure returns Off with an inline message. Normal offline waiting is neutral. Off pauses pending items; the same account can resume; another account cannot receive them. Service toggles start connection setup when needed. Do not add a separate Connect button. After approval, the user-requested On state enables sync; Off cancels pending sign-in or stops sends. Warn only for enabled services that need attention or current NeoReader read issues. Disabled services do not cause warnings.

Keep one identifier-tag allowlist in `BookIdentifiers.kt`. The metadata popup displays the reader's results and supplies labels; do not add a second UI allowlist. Only ISBN, ASIN, Goodreads, Hardcover edition/ID/slug, and explicit StoryGraph/Fable/Margins identifier tags are accepted. Future-service tags are display-only until their integrations exist; do not claim API support from tag extraction.

Preserve logs and settings across compatible upgrades. The SQLite database and export JSON schema are both version 1; the application versionCode is separate from both schema versions. Activity displays the latest 250 events; export retains the full log. No retention/deletion policy has been approved. Never clear data or uninstall to make an upgrade check pass.

Do not put credentials, cookies, tokens, signing passwords, private reading logs, full directory paths, or unrelated provider blobs in source or reports. Use the export allowlist, keep filenames/path digests separate from full paths, and share only user-requested exports. Reuse the external diagnostic signing identity; never regenerate or print it during an update. Generated artifacts, caches, dependencies, and secrets are read-only; run their owning generator. Use `trash` for deletions.

## Implementation and tools

The current app is one native Kotlin module with Android Views/XML and AndroidX. Keep it small; reuse existing code and platform features before introducing abstractions or dependencies. MIT is recorded in `LICENSE.md`. The existing remote is `git@github.com:otherguy/boox-tracker.git`.

Ask before replacing substantial working code or choosing a material product/architecture policy. In product Plan mode, interview one question at a time, persist the approved plan under `agents/plan-YYYYMMDD-name.md`, and update its checkboxes as work lands. Do not broaden a diagnostic task into integration work. Use names that describe current behavior and evergreen comments; avoid speculative compatibility, abstractions, or unrelated refactoring. Compatible signed updates and retained history are explicit requirements here.

| Owner | Responsibility |
| --- | --- |
| `Metadata.kt` | Provider access, safe fields, fraction interpretation, book identity/detection |
| `DiagnosticsStore.kt` | SQLite state and event persistence |
| `ReadingSyncApp.kt` | Shared diagnostics, query coordination, lifecycle context |
| `Background.kt` | Automatic local collection and constrained delivery workers |
| `MainActivity.kt` | Sync and Activity UI, visible-screen refresh |
| `ActivityLogAdapter.kt` | Grouped Activity entries, recycled rows, lazy details |
| `Export.kt` | Safe JSON/text export through the share sheet |
| `EbookFolder.kt` | Mandatory readable persisted SAF grant |
| `BookIdentifiers.kt` | Identifier schemes, ISBN validation, bounded EPUB metadata, read-only SAF folder resolution |
| `HardcoverAuth.kt` | HTTPS, device OAuth, serialized refresh, Keystore token vault |
| `HardcoverMatch.kt` | Automatic matching and bounded cache |
| `HardcoverSync.kt` | Verified edition basis and conservative GraphQL updates |
| `HardcoverConnection.kt` | Connection UI state, opt-in, fresh sends, safe tracker logs |

Use `mise` and `.tool-versions` for project tools, including Java. Do not introduce Homebrew dependencies unless a concrete blocker requires one. Use the pinned Android CLI `android ... sdk install`, not the deprecated `sdkmanager`. See [build instructions](agents/build-and-release.md) for tool versions and setup.

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
mise exec -- python scripts/package-artifacts.py
mise exec -- ktlint '**/*.kt' '**/*.kts'
mise exec -- ruff format --check scripts
mise exec -- ruff check scripts
mise exec -- markdownlint-cli2
mise exec -- actionlint
```

Lint each changed Markdown file explicitly, including untracked files. Diagnose every failure and do not weaken checks. Use tests for real behavior and regressions, not message copy or framework behavior. After substantial code work, run code-reviewer and code-simplifier reviews in parallel, incorporate verified in-scope findings, and stop if either review fails or returns no result. Documentation-only work needs direct review, Markdown lint, and link checks.

## Documentation ownership

README, CONTRIBUTING, and `docs/` are for users and contributors. Keep personal test timelines, agent instructions, exact handoff state, research leads, and publication gates in `agents/`. Preparing public documentation does not lift the release hold.

| Agent document | Purpose |
| --- | --- |
| [Project status](agents/project-status.md) | Preserved implementation, artifact, and device-test handoff |
| [Product context](agents/product.md) | Confirmed requirements, proposals, and open decisions |
| [Design](agents/design.md) | Approved mockup and e-ink requirements |
| [Integration research](agents/integrations.md) | Tracker/API leads and inquiry status |
| [Provider research](agents/research.md) | Provider/source/licence findings and unverified leads |
| [Verification record](agents/verification.md) | Automatic and physical evidence, firmware limits |
| [Device-test handoff](agents/device-testing.md) | Controlled test protocol and remaining personal checks |
| [Build and release](agents/build-and-release.md) | Signing, delivery workflow, and publication hold |
| [Diagnostic plan](agents/plan-20261004-reading-sync-poc.md) | Approved first-milestone scope |

Use the [public build guide](docs/build-and-release.md) and [testing guide](docs/device-testing.md) for contributor-facing commands and instructions.

## Resume point: 2026-10-06

The current implementation is 0.3.3/code 10 on `feat/automatic-offline-sync`, package `dev.otherguy.booxtracker` (`.debug` for development). Read [project status](agents/project-status.md) and [verification](agents/verification.md#identifier-allowlist-033) for build/device evidence and remaining physical tests. The reader is the single identifier allowlist; the popup supplies labels only. Explicit StoryGraph/Fable/Margins tags can be displayed without implementing those integrations. Versioned ebook metadata caching rereads identifiers after this update. All 76 tests and required gates/reviews passed; the signed update and metadata popup were checked on the GoColor7. The prior 0.3.2 popup/folder-cancellation fixes remain. The user confirmed exact matching and remote progress on 0.3.1. Book-only fallback and hidden-app offline delivery remain unverified; do not infer them from foreground reads or app updates.

README is public product copy with Hardcover enabled since 0.2.0 and other providers Coming Soon. Existing CI is configured; local changes have not been pushed or run remotely. Publication remains on hold. After one successful manual device sync, remind the user to test offline reading/reconnection with the new app hidden. Opening it causes foreground collection, so inspect earlier scheduled/delivery entries separately.

## Previous diagnostic handoff: 2026-10-05

- 0.1.2/code 3 is built locally as `dist/reading-sync-0.1.2-diagnostic.apk`; its package is `org.readingsync.diagnostic`. The user installed it on 2026-10-05 and confirmed much faster Activity scrolling and retained logs. Debug uses `.debug` and cannot update the diagnostic installation. Reuse the existing certificate and increase versionCode for later updates.
- Twenty tests passed (including six Activity regression tests), Android lint had zero errors and nine dependency-version notices per variant, formatting/lint passed, and both change reviews returned no actionable findings. GitHub CI passed for committed 0.1.0; that does not prove CI ran on the local 0.1.1/0.1.2 changes.
- 0.1.0 was physically tested on ONYX GoColor7, Android 12/API 32, build `2026-05-19_23-44_4.2-rel_0519_c76f35ce8`, incremental `9661`. Provider access, useful observer callbacks, retained logs across reboot, and independent scheduled reads are confirmed on that firmware only.
- The 2026-10-05 0.1.1 cold-boot export confirms independent scheduled reads before the app opened. The latest 0.1.2 export at 22:46 retains 283 events / 211 successful queries and adds seven complete scheduled jobs with visibility/session flags false. Around cover close at 21:50 and wake at 22:25, reads ran at 21:50:29 and 22:25:10, with none between; at 22:46:05 the worker found `5007/10000` (50.07%), consistent with the reported library 50% versus in-book 51.8%. Uptime is stable through that sleep/wake interval; the user reports no power-off and another sleep before returning to the app. Sharing and retained history on 0.1.2 are confirmed. The earlier 12:11 unfinished job still has no known termination/power-off time. Do not repeat completed diagnostic checks just to meet an earlier requested duration. Regular sleep execution remains unverified.
- The diagnostic milestone has sufficient device evidence for these cases. Hardcover was subsequently chosen and authorized on 2026-10-06. Longer sleep/repeat-boot tests are optional; do not present timing or compatibility guarantees. The website subdomain/hosting, exact EPUB identifier format, initial-sync policy, completion/reread/conflict rules, statistics provider, retention policy, and updater are still undecided or unimplemented. See [product decisions](agents/product.md#open-decisions-and-next-work).
