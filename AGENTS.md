# Boox Tracker agent guide

Boox Tracker is an open-source (MIT) Android companion app for BOOX e-readers. NeoReader stays the reader. The app reads NeoReader's saved library metadata and sends reading progress one way to the trackers the user enables. Product copy is in the [README](README.md); the contributor process is in [CONTRIBUTING](CONTRIBUTING.md).

Current state, versions, device results, and next steps are in [project status](agents/project-status.md). Do not record them here.

## Hard boundaries

- Read BOOX data only with `ContentResolver.query()` under the app's ordinary UID, from `content://com.onyx.content.database.ContentProvider/Metadata`. Never modify BOOX records, open private database files, require root or ADB privileges, bypass Android permissions, or request broad storage access. This access works on the tested firmware; build on it instead of researching it again.
- A persisted, readable, read-only SAF grant for the ebook folder is mandatory. Without it, the app blocks with Exit / Allow again, and workers record the failure without opening UI or sending.
- Sync is automatic: detect the latest saved book, match it, and update its tracker record. Never add a source-book selector, destination chooser, match confirmation, Connect button, or library backfill. Never edit tracker catalogues.
- Writes are conservative. Keep higher remote progress and an existing remote edition. Protect completed reads, rereads, and reading history. Hold the update when evidence conflicts or is ambiguous.
- KOReader projects are references, not dependencies. Never write positions back into NeoReader.
- Do not publish GitHub releases, dispatch the prerelease workflow, or send inquiries to tracker services unless the user explicitly asks.

## Source data

- Keep raw progress fractions and `readingStatus` codes. On the tested device, `0` is not started, `1` is reading, and `2` is finished. Do not interpret other codes.
- Calculate a percentage only from a valid fraction. Null, missing, malformed, zero-denominator, unreadable, and out-of-range progress stay distinct. Unknown progress is never 0%.
- Do not add an offset to match NeoReader's in-book percentage. Fraction units are not physical pages.
- The latest unique usable `lastAccess` selects the book. It shows the latest saved activity, not the open book. Missing or tied times stop detection.
- Keep provider unavailable, permission denied, failed query, empty library, and unusable progress distinct. Query off the main thread and close cursors.
- Database metadata covers every format the provider exposes; only EPUB has an embedded parser. A null database ISBN does not mean the ebook has none.

## Trackers

- Match from explicit service IDs, then ISBN-13, ISBN-10, ASIN, and mapped identifiers, then one normalized title and author match. Several editions of one book establish the book. Conflicts and ambiguity hold the update with a visible error.
- `BookIdentifiers.kt` is the only identifier-tag allowlist. The metadata popup labels its output and does not filter again. Tags for services without an integration are display-only.
- The service switch drives the connection. On opens that service's sign-in popup when no session exists; Cancel or Back in the popup turns the switch Off. Off cancels sign-in or pauses sends and keeps queued items. A failed Hardcover connection returns Off with a message on the row; a rejected Fable email or password keeps the popup open with the reason. Only enabled services can show a warning.
- Fable uses Fable's unofficial app API by the user's decision; public docs must say so. Never store the Fable password, only its tokens.
- Pagebound uses its website's unofficial API by the user's decision; public docs must say so. Sign-in is the email/password popup that Fable shares through `PasswordConnection`; store only the Pagebound token and the Firebase refresh token, never the password. Pagebound rejects a token with HTTP 500 and an empty body; only that answer to the account request renews the token once through Firebase, and a second refusal ends the session. Reading instances the app creates are `digital`; an existing read keeps its format. An empty edition is set to the matched edition; a chosen edition is kept. A book finished before holds instead of starting a reread.
- Margins has no API and uses its website's Supabase sign-in and Rocicorp Zero sync server by the user's decision; public docs must say so. Sign-in is the email one-time-code popup of `CodeConnection` with `create_user: false`; store only the Supabase access and refresh tokens, never the code. Zero is spoken directly over an OkHttp WebSocket in `MarginsZero.kt`: protocol 51 with JSON pokes, a client schema limited to replicated tables, one persisted client group per account and schema, and a new client per connection. A refused schema or protocol holds and is never retried. `isInLibrary` is always false. A finished or stopped read holds instead of starting a reread, and a read the user gave a format keeps it. Margins has no title search, so it matches only by identifiers.
- StoryGraph has no API and uses its website through a browser session by the user's decision; public docs must say so. Sign-in is StoryGraph's own page in a WebView; the cookies stay in the WebView cookie store and are never copied, logged, or exported. Requests send the User-Agent stored at sign-in. A Cloudflare challenge or a sign-in redirect ends the session and asks for a reconnect; never retry it automatically.
- Goodreads has no API for new apps and uses its website through a browser session by the user's decision; public docs must say so. Sign-in is Goodreads' own page in a WebView in the `goodreads` `androidx.webkit` profile, so Log out clears only that profile; a WebView without multi-profile support keeps the switch Off. Cookies stay in that profile and are never copied, logged, or exported. Goodreads alone may answer an AWS WAF challenge: one hidden-WebView load of the home page, then one retry; a second challenge or a signed-out page ends the session. A capture requires the signed-in header, because the signed-out home page is a landing page.
- Goodreads, Pagebound, and Margins receive progress in whole steps of `PROGRESS_STEP` counted from 0 (`stepPercent`), only when the step is above the value the tracker holds, because each post appears in a feed. One sync posts one step, never the steps it skipped. Goodreads' finish date goes through the review editor's Server Action and only changes the read that the Read shelf finished; it never adds a read, never posts with an existing review, and its failure never fails the Read shelf.
- Every provider row opens that provider's details popup: account details stored at sign-in (never in the Activity log or exports), connected since, the current book's match, edition, progress, and last sync, and Log out. Log out asks once, removes the session, turns the provider Off, and deletes its queued updates, book results, and account details. Each new provider implements `fetchProfile` and `signOut` on `TrackerConnection` to get this.
- Match explanations such as "no exact edition" or "your existing edition" belong in the details popup. The row shows at most a short note: "· same edition" or "· different edition" when the ebook's edition is known. A book matched on another edition is a success and never warns. A tracker that keeps higher progress than NeoReader warns with ⚠ on the sync line.
- Every warning names its issue in full. The book popup lists every current issue first; a provider popup lists only its own, just below its title. Each issue starts with the amber triangle; with no issue the section is absent. `serviceIssues` and `readIssue` in `MainActivity.kt` are the only source of the header warning, so a new issue kind adds its text there. Every ⚠ on a row or in a popup is drawn as the amber triangle.
- Read [integration research](agents/integrations.md) before adding or changing a tracker.

## Queue and background work

- Local collection is unique fifteen-minute WorkManager work without a network constraint. Separate network-constrained delivery work (`APPEND_OR_REPLACE`) drains the queue. Opening the app and Sync Now also collect and attempt delivery.
- A connected Goodreads session adds unique six-hour network-constrained renewal work that loads Goodreads in a hidden WebView; a send renews first when the last renewal is older than six hours. Log out cancels the work.
- The queue holds the latest observation per account, service, and source book. Acknowledge only the revision that was sent. Read remote state before retrying an uncertain write. Another account never receives a queue.
- Do not add an observation service, observer polling, a background toggle, an app-managed wake lock, or forced screen-on.
- The event log is diagnostics only and bounded: at most 1,000 events, none older than 30 days, and routine events (finished runs, unchanged or changed checks, queue entries, waiting sends) from before the last successful sync are pruned once they are 48 hours old. Issues and sync results stay until the caps, and the count cap removes the oldest routine events first. `DiagnosticsStore.prune` never touches the `state` table, which holds the queue, snapshot, results, and settings. Clear in Activity asks once, then deletes every event and writes one `activity_cleared` event in the same transaction; it also never touches `state`. Write one `run` event per worker run, not start and stop markers, and keep the provider's column list out of check events. Only an issue check keeps the full book record; other checks keep a six-field summary.
- A worker run with no recorded stop is an issue only when the device has not restarted since it began: `startRun` stores `Settings.Global.BOOT_COUNT`, and recovery records a run from an earlier boot as the routine `run_stopped_by_restart`.
- A worker result is independent background evidence only if the app was hidden for the whole interval. Check the visibility flags and compare with app-open times. A foreground sync, an app-open run, or an ADB query is not that evidence. No exact interval or post-boot timing is guaranteed.

## Upgrades, privacy, and signing

- The visible name is Boox Tracker and the package is `dev.otherguy.booxtracker`. Development builds add `.debug` and cannot update the signed build. Reading Sync and `org.readingsync.diagnostic` are historical names; do not restore them.
- Updates keep the application ID and certificate and increase `versionCode`. Settings and logs survive compatible updates. Never uninstall or clear data to make an update work.
- The SQLite schema, the export schema, and the ebook identity cache namespace each have their own version, separate from `versionCode`. Bump the cache namespace when the extracted fields change.
- Inspect the merged APK manifest before stating permissions. WorkManager adds foreground-service, wake-lock, and boot declarations that the app's own manifest does not contain.
- Never put credentials, tokens, cookies, signing passwords, private exports, ebook contents, or full directory paths into source, logs, exports, or docs.
- The signing key and its properties file stay outside the repository in `~/.config/reading-sync/`. Reuse them. Never regenerate, print, or commit them.

## Code map

One native Kotlin module with Android Views/XML and AndroidX. Keep it small. Use existing code and platform features before adding a dependency.

| Area | Files |
| --- | --- |
| NeoReader source | `Metadata.kt`, `BookIdentifiers.kt`, `EbookFolder.kt` |
| Storage and export | `DiagnosticsStore.kt` (SQLite `events` and `state` tables), `Export.kt` |
| App and workers | `ReadingSyncApp.kt`, `Background.kt` |
| Trackers | `TrackerConnection.kt` (shared queue and delivery, and `PasswordConnection` for email sign-in), then `<Service>Auth.kt`, `<Service>Match.kt`, `<Service>Sync.kt`, `<Service>Connection.kt` per service; `GoodreadsBrowser.kt` holds the WebView profile and the hidden renewal |
| UI | `MainActivity.kt`, `ActivityLogAdapter.kt` (row text in `eventText`, grouping in `activityEntries`); follow the [design](agents/design.md) |

A new tracker uses the same four files and extends `TrackerConnection`; StoryGraph and Goodreads add `<Service>Pages.kt` for their HTML parsing, and Margins adds `MarginsZero.kt` for its sync protocol. OkHttp is the only production network library and is used only for that WebSocket. Tests run on Robolectric with one `MockWebServer` fake per service, such as `HardcoverServer`, `FableServer`, `StoryGraphServer`, `GoodreadsServer`, `PageboundServer`, and `MarginsServer` (its Zero fake is a MockWebServer WebSocket), and never use the real network. The host JVM drops `Sec-Fetch-*` and `Origin` request headers, so tests cannot see them. WebView cookie methods that take a callback need a Looper thread; call them on the main thread, and use `LooperCheckingCookieManager` in tests, because Robolectric does not enforce this. UI tests capture stderr and allow only Robolectric's `Invalid ID 0x00000000.` line.

## Tools and checks

Use `mise` and `.tool-versions` for every tool, including Java. Install SDK parts with `android … sdk install`, not `sdkmanager`. Do not add Homebrew dependencies.

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
mise exec -- python scripts/package-artifacts.py
mise exec -- ktlint '**/*.kt' '**/*.kts'
mise exec -- ruff format --check scripts
mise exec -- ruff check scripts
mise exec -- markdownlint-cli2
mise exec -- yamllint --strict .
mise exec -- actionlint
```

`lefthook.yml` runs these linters on staged files before a commit and the full set, with the Gradle checks when app or build files changed, before a push; install it with `mise exec -- lefthook install`. Keep the hooks, the Lint workflow, and the Android checks workflow running the same commands, and add a new linter to all three.

`package-artifacts.py` writes the signed APKs, their checksums, and `dist/build-info.json`. Do not edit `dist/` by hand. Debug builds and CI need no signing key; `assembleDiagnostic` and packaging do. [Build and release](agents/build-and-release.md) covers signing and CI.

## Documentation

- README, CONTRIBUTING, and `docs/` are public. Keep test timelines, evidence, research, handoff state, and publication gates in `agents/`.
- Report evidence at three separate levels: Built, Automatically tested, and Verified on physical BOOX. Keep confirmed requirements, observations, research leads, proposals, and unknowns apart.
- Approved plans are `agents/plan-YYYYMMDD-name.md` files with checkboxes. A completed plan is history; [product context](agents/product.md) holds the decisions in force.

| Document | Holds |
| --- | --- |
| [Project status](agents/project-status.md) | Current state, artifacts, and next steps |
| [Product context](agents/product.md) | Confirmed requirements and open decisions |
| [Design](agents/design.md) | Approved layout and e-ink rules |
| [Integrations](agents/integrations.md) and [research](agents/research.md) | Tracker contracts, provider findings, unverified leads |
| [Verification](agents/verification.md) and [device testing](agents/device-testing.md) | Recorded evidence and test protocols |
| [Build and release](agents/build-and-release.md) | Tools, signing, CI, publication |
