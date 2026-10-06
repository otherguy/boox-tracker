# First Hardcover build implementation plan

> Use the executing-plans workflow to complete this scope in the existing checkout. The user requested implementation through APK delivery without approval stops. Preserve all existing uncommitted diagnostic and documentation changes.

**Goal:** Deliver a signed update that detects the latest saved NeoReader book, connects to Hardcover, matches its ISBN, and sends progress.

**Architecture:** Keep native Kotlin, Android Views, SQLite diagnostics, and WorkManager. Use Android HTTPS, Keystore, and read-only Storage Access Framework access. Observation remains local. Manual sync and enabled scheduled checks use the same connector.

**Tech stack:** Existing pinned Android/Kotlin/AndroidX toolchain through mise. No new runtime dependency.

**Spec:** User instructions in this conversation and [product context](product.md). The newest instruction removes every source-book selector and authorizes the first Hardcover integration.

## Global constraints

- Never write BOOX records, bypass permissions, or request broad storage access. Persist only a user-granted read permission for an EPUB folder.
- Preserve logs, settings, package identity, and signing certificate. Never log or export credentials or full file paths.
- No book selector. Latest usable `lastAccess` identifies saved activity; it cannot prove which book is currently open. Ambiguous or missing activity must stop sync.
- Match exact ISBN to one Hardcover edition. Missing or ambiguous matches require correction of metadata/catalogue; never create catalogue entries automatically.
- GitHub publication remains on hold. Build and package locally; distinguish automatic checks from physical device verification.

## Tasks

### Automatic detection

- [x] Replace `selectBook(snapshot, key)` with `mostRecentlyAccessedBook(snapshot)` in `ReadingSyncApp.kt` and `MainActivity.kt`. Remove chooser buttons and transient selections. Ignore obsolete persisted selection.
- [x] Add failing real-provider/UI regression tests for saved selection and unusable access timestamps in `MainActivityTest.kt` and the provider fixture trait in `MetadataTest.kt`.
- [x] Return fresh query details from `Diagnostics.collect()` for manual/worker sync. Failed or skipped queries must never send a cached snapshot.

### EPUB identifiers

- [x] Add `BookIdentifiers.kt`: validate ISBN checksums, read bounded ZIP container/OPF metadata, support EPUB 2 schemes and EPUB 3 prefixes, reject external entities and unsafe ZIP paths.
- [x] Add read-only folder access and unambiguous filename resolution through `DocumentsContract`. Prefer a valid provider ISBN; never infer an ISBN from an OPF filename or attribute id.
- [x] Test synthetic EPUBs, invalid ISBNs, duplicates, malformed container/OPF data, and ambiguity. Read local sample metadata without copying the books into fixtures.

### Hardcover connection and updates

- [x] Add native HTTPS client and device-code OAuth flow using public client `bc5f2c0f-79d7-42b5-b525-6293454d3934`. Respect polling interval, slow-down, expiry, cancellation, and refresh rotation.
- [x] Encrypt credentials with an Android Keystore AES/GCM key and atomic private-file storage. No plaintext fallback. Disconnect clears local credentials and disables sync.

#### Catalogue and progress

- [x] Query exact editions and the current user's library/read state with the published GraphQL schema. Calculate approximate edition pages from the raw fraction and matched edition page count.
- [x] Create a currently-reading library entry/read when needed; advance one unfinished read only. Keep higher remote progress; stop on completed/reread/edition/status conflicts. Never write rating, review, history dates, or catalogue records.
- [x] Test through a local HTTP server: device polling, refresh rotation, API errors, exact matching, first send, repeat send, conflicts, and mutation failures. No real account writes in automatic tests.

### Shipping integration

- [x] Wire connection, folder permission, sync opt-in, and Sync now into Diagnostics. Native folder picker is storage consent, not book selection. Use static labels and large controls.
- [x] Run the connector only after fresh manual/scheduled reads. Local worker queries need no network constraint. Keep five-second observation polls local.
- [x] Record safe sync outcomes and pending retry evidence in the retained Activity log. Exports omit tokens, device codes, URLs with secrets, full paths, and unrelated provider fields.
- [x] Bump to 0.2.0/code 4, update public and agent docs, and document conservative conflict/completion behavior.

### Verification and delivery

- [x] Format Kotlin and run unit tests, Android lint for both variants, and both APK builds. Run Python, workflow, Markdown, and diff checks. Lint each changed Markdown file explicitly.
- [x] Run code-reviewer and code-simplifier reviews in parallel. Fix verified findings and repeat the affected checks.
- [x] Package APKs through `scripts/package-artifacts.py`, verify the retained signing certificate and SHA-256, and deliver the diagnostic APK.
- [x] Give physical sign-in/matching/progress instructions, then remind the user to test independent scheduled sync with observation off and Reading Sync hidden. Do not claim new physical verification.

## Notes

The first connector keeps higher remote progress and holds finished/reread cases. This conservative default avoids rewriting reading history while the user tests the integration. Title/author fallback, automatic completion, reread creation, and catalogue editing remain outside this build.

The first review found four reproducible defects: explicit non-ISBN numeric schemes, zero-progress edition conflicts, shortened server polling intervals, and writes after cancellation. Each new regression failed on the prior implementation and passed after repair. A follow-up found unreadable credentials hidden behind stale UI status; this is now represented explicitly without repeated log events. Polling schedule tests use a controlled clock with real local HTTP requests. Test-owned WorkManager databases are explicitly closed through its supported test helper; warnings are not suppressed.

The 0.2.1 UI follows the user's 2026-10-06 correction: there is no Connect button. Turning Hardcover On checks the saved connection, silently enables it if present, or starts device sign-in and enables after approval. Off cancels pending sign-in or stops sends. Disabled services do not produce a header warning; only enabled service faults and NeoReader read issues do. This replaces the initial separate connection/enable controls.
