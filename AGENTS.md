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
- Every provider row opens that provider's details popup: account details stored at sign-in (never in the Activity log or exports), connected since, the current book's match, edition, progress, and last sync, and Log out. Log out asks once, removes the session, turns the provider Off, and deletes its queued updates, book results, and account details. Each new provider implements `fetchProfile` and `signOut` on `TrackerConnection` to get this.
- Match explanations such as "no exact edition" or "your existing edition" belong in the details popup. The row shows at most a short note: "· same edition" or "· different edition" when the ebook's edition is known. A book matched on another edition is a success and never warns. A tracker that keeps higher progress than NeoReader warns with ⚠ on the sync line.
- Every warning names its issue in full. The book popup lists every current issue first; a provider popup lists only its own, just below its title. Each issue starts with the amber triangle; with no issue the section is absent. `serviceIssues` and `readIssue` in `MainActivity.kt` are the only source of the header warning, so a new issue kind adds its text there. Every ⚠ on a row or in a popup is drawn as the amber triangle.
- Read [integration research](agents/integrations.md) before adding or changing a tracker.

## Queue and background work

- Local collection is unique fifteen-minute WorkManager work without a network constraint. Separate network-constrained delivery work (`APPEND_OR_REPLACE`) drains the queue. Opening the app and Sync Now also collect and attempt delivery.
- The queue holds the latest observation per account, service, and source book. Acknowledge only the revision that was sent. Read remote state before retrying an uncertain write. Another account never receives a queue.
- Do not add an observation service, observer polling, a background toggle, an app-managed wake lock, or forced screen-on.
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
| Trackers | `TrackerConnection.kt` (shared queue and delivery), then `<Service>Auth.kt`, `<Service>Match.kt`, `<Service>Sync.kt`, `<Service>Connection.kt` per service |
| UI | `MainActivity.kt`, `ActivityLogAdapter.kt`; follow the [design](agents/design.md) |

A new tracker uses the same four files and extends `TrackerConnection`. Tests run on Robolectric with one `MockWebServer` fake per service, such as `HardcoverServer` and `FableServer`, and never use the real network. UI tests capture stderr and allow only Robolectric's `Invalid ID 0x00000000.` line.

## Tools and checks

Use `mise` and `.tool-versions` for every tool, including Java. Install SDK parts with `android … sdk install`, not `sdkmanager`. Do not add Homebrew dependencies.

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
mise exec -- python scripts/package-artifacts.py
mise exec -- ktlint '**/*.kt' '**/*.kts'
mise exec -- ruff format --check scripts
mise exec -- ruff check scripts
mise exec -- markdownlint-cli2
mise exec -- actionlint
```

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
