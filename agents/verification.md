# Verification record

Updated 2026-10-07 for Boox Tracker 0.4.0/code 12 (Fable). Version sections record the evidence available at each handoff; a pending result in an older section is not the current status. Use the matrix below and [device checklist](device-testing.md#current-033-checks). No compatibility or scheduling guarantee follows from compilation, emulator rendering, local HTTP tests, ADB access, or one firmware result.

## Current evidence summary

| Category | Confirmed evidence | Limits / next proof |
| --- | --- | --- |
| Built | 0.4.0 debug/signed APKs: Hardcover with completion sync, and Fable through its unofficial app API | Goodreads/StoryGraph/Margins, rereads, website, updater and statistics are not implemented |
| Automatically tested | 121 tests, zero failures/errors/skips, empty stderr; both builds/lint variants, static checks and both reviews passed | Ten existing lint notices per variant; synthetic source/local HTTP do not prove firmware or production scheduling |
| Physical provider/background | Historical ordinary-UID reads, independent scheduled reads after boot/near wake, logs retained on GoColor7/API 32 firmware below | No exact cadence, regular sleep, repeat-boot, or wider-device guarantee; new delivery needs separate proof |
| Physical Hardcover | User confirmed native approval, exact matching, Synced at…, and remote 240/480 pages (50%) on 0.3.1 | Chosen edition/raw fraction/mutation sequence await export; book-only and hidden-app offline/reconnect remain pending |
| Physical current UI/update | Signed 0.3.1–0.3.3 updates; prompt, merged identifiers, readable-grant cancellation checked | Future tags and revoked-grant/recreation cases have automatic evidence only; unchanged data inode is not queue-reboot proof |
| Source / publication | Current source local on feat/automatic-offline-sync; CI configured, only 0.1.0 remote success recorded | No current-source remote CI; releases/prereleases still held |

APK bytes/checksum and existing test reports were rechecked during the documentation refresh. No new app build, device operation, or remote workflow was performed for that docs-only work. Foreground checks around 22:03–22:04 and 22:31–22:32 on 2026-10-06 must be excluded from independent background evidence. Opening the app collects/sends automatically.

The merged diagnostic manifest was also inspected: WorkManager adds generic FOREGROUND_SERVICE/SystemForegroundService, WAKE_LOCK and RECEIVE_BOOT_COMPLETED. The source manifest has no observation service or explicit notification/foreground permission, and current code has no foreground-worker promotion. The removed observation feature does not imply that the final APK has no framework foreground-service declaration.

## Regression findings and pitfalls

### Source, identifiers, and display

- A null provider ISBN does not mean no EPUB ISBN exists. The shipping popup now reads the same merged repository as sync off the main thread.
- `progressProblem: null` means no fraction error; valid progress shows OK. Missing/unreadable/invalid progress stays explicit and never becomes 0%.
- Reader extraction is the single tag allowlist; UI labels do not add another. Future tags are display-only, and ASIN is currently used by Hardcover.
- Ebook cache namespace changes invalidate parsed metadata without deleting settings/logs. Application, database/export, and cache versions are separate.
- Stored lastAccess can lag book switches; provider/library percentages can differ from in-book display. No source selector or percentage offset resolves that uncertainty.

### Queue, permissions, and lifecycle

- Persist per-account/service/book revisions before delivery. Acknowledge only the sent revision so newer observations survive; reconcile remote state after uncertain writes.
- APPEND_OR_REPLACE retains a delivery enqueue during worker completion. A global pending book or KEEP-only enqueue can lose required work.
- Offline waiting is neutral; On persists for connected accounts, Off pauses sends, and account identity failures cannot reuse another account's state.
- Explain folder choice before launching the picker. Cancelling replacement revalidates the existing grant; denial/revocation still gates. Activity-owned validation state prevents recreation from losing the prompt.
- Preserve current remote edition/history/page basis. A missing basis is a held update, not permission to guess pages or overwrite completed history.

### Test and device handling

- Await rendered book state before UI taps; provider-call counts can advance before snapshot rendering. Use explicit latches/clock control, not arbitrary sleeps.
- Test denial must throw before cursor allocation. A throw inside cursor construction can leak a resource before production code receives it.
- Capture/assert expected Robolectric zero-resource-ID diagnostics; reject other stderr. Close test WorkManager/SQLite resources instead of suppressing warnings.
- Activity checks bound operation/row counts and state retention; reported faster scrolling is qualitative physical evidence, not a benchmark.
- BOOX XML dumps returned null roots while screenshots worked. Fresh package disabling by com.onyx was observed, but its cause is unknown. Installation/UI ADB use is not ordinary-UID provider proof.

## Fable sync 0.4.0

**Built:** debug and signed diagnostic APKs, version 0.4.0/code 12, on `main` as local uncommitted changes. Package, diagnostic certificate (`678df89d…f4b420d`), app database/export schemas, ebook cache namespace, Hardcover vault file and Keystore alias, and OAuth client ID are unchanged. Artifact `dist/boox-tracker-0.4.0-diagnostic.apk`, 3,538,746 bytes, SHA-256:

```text
6154bfce8cfddcad667bed4d2eeb4a5dbccb69a3a807e1ee5fcd735180081679
```

Fable is the second tracker; see the [Fable plan](plan-20261006-fable-sync.md) and [contract](integrations.md#fable). The queue, delivery loop, and stored state moved to a shared `TrackerConnection`; Hardcover keys and event kinds are unchanged. `sync()` and the delivery worker isolate each service. A manual or scheduled send failure is now recorded as `<service>_sync` instead of `hardcover_operation`/`worker_failed`, and a scheduled run with such a failure stops as `failed`.

**API evidence, 2026-10-06:** with the user's browser session, read-only calls confirmed profile, search, book detail, editions, list membership, and progress read-back. User-authorized writes on In the Blood established: decimal percentages fail with 400; an integer write returns 201 and is read back; writes do not shelve; lower values are accepted; 100% moves the book to Finished; a later 50% write keeps Finished. A multiselect move off Finished returned 200 while book detail still read Finished seconds later. On 2026-10-07 list membership and book detail both showed Currently Reading at 50%, so the move had applied and detail status lagged. The UK sibling has a 0% record on no list. Firebase sign-in and refresh were not exercised.

**Automatically tested:** 121 tests, zero failures/errors/skips, empty stderr, including 36 new Fable tests against a local fake of the observed response shapes. Both builds and lint variants passed; lint has zero errors and ten dependency/tool notices per variant. ktlint and markdownlint passed. The code-reviewer and code-simplifier reviews both returned findings; all were applied with tests, as listed in the plan notes.

**Verified on physical BOOX:** nothing yet. The 0.4.0 APK was not installed. See [Fable device checks](device-testing.md#fable-sync).

## Completion sync 0.3.4

**Built:** debug and signed diagnostic APKs, version 0.3.4/code 11. Package, diagnostic certificate, app database/export schemas, ebook cache namespace, and OAuth client ID are unchanged. Artifact `dist/boox-tracker-0.3.4-diagnostic.apk`, SHA-256:

```text
78874fed5996db78bb34e5ea8db9676675946c4d2c0d7bb3e6eb0daec71e3ec5
```

Provider status `2` with a full fraction now completes the matched Hardcover read with full pages and a finish date, then sets the book to Read. The finish date is the device-local date of `lastAccess`, then the queued read time, then delivery time. An already-Read remote book returns `already_current` without writes. A not-yet-Read book with exactly one finished read, from an interrupted earlier write or the user, only receives the status update. The logged `progressPages` is the value written, including a higher remote count. Status `2` with a partial fraction holds as `source_finish_progress_mismatch`; status `1` at 100% holds as `source_status_not_finished`; other codes hold as `source_status_unsupported`. Rereads remain protected. The Hardcover row shows Finished instead of a percentage after a completed send.

**Automatically tested:** 85 tests, zero failures/errors/skips, empty stderr. New regressions failed first on the old status gate, then passed: new finished book, finishing an existing read, already-Read remote, partial-progress hold, read-time fallback, interrupted-write retry, remote already at full or higher pages, and queue delivery after a status change following a held full-progress send. Date tests run in a fixed Asia/Bangkok zone with literal expected dates. The full Gradle test/lint/assemble gate, ktlint, ruff, markdownlint, actionlint, and both scoped reviews passed; the reviews' retry and full-pages findings were fixed with tests before the final build. Android lint retains ten dependency/tool-version notices per variant.

**Verified on physical BOOX:** the signed 0.3.4 update installed over 0.3.3 via USB on 2026-10-06; package metadata reports code 11/version 0.3.4 with the same data directory, and no uninstall/data-clear was issued. A read-only ADB listing the same day established the status-code meanings above. Launch, completion, and reopen behaviour are not yet checked; a real finish with one read entry and the expected date on Hardcover, and the reopen-after-finish hold, are the pending checks in [device testing](device-testing.md#completion-sync). Hardcover's own behaviour for `update_user_book(status_id: 3)` is unverified.

## Identifier allowlist 0.3.3

**Built:** debug and signed diagnostic APKs, version 0.3.3/code 10. Package, diagnostic certificate, app database/export schemas, and OAuth client ID are unchanged. Artifact `dist/boox-tracker-0.3.3-diagnostic.apk`, SHA-256:

```text
5e9df530dc178f4af9668fa53bf48b90cdcabf4268ca19a5fd05e1d159472bd2
```

The reader remains the only identifier-tag allowlist. The popup adds labels, with no second restriction. ISBN, ASIN, Goodreads, and Hardcover edition/book/slug identifiers are retained. Explicit StoryGraph/Fable/Margins tags accept bounded opaque identifiers for display only; those services remain Coming Soon. Ebook identity cache namespace 2 bypasses older metadata so newly recognized tags can be read without deleting logs or settings.

**Automatically tested:** 76 tests, zero failures/errors/skips, empty stderr. Existing parser, real SAF/cache, and shipping popup tests were extended. The parser regression failed before support for the planned-service tags. An old cached-identifier fixture failed before the cache-version change; it now reads the EPUB once and reuses the metadata cache. Tests also reject unrelated tags and path/URL/whitespace values under the planned-service schemes. The full Gradle test/build/lint gate, Kotlin lint, Python format/lint, Markdown lint, Actions lint, whitespace/link checks, and both scoped reviews passed. Android lint has zero errors and ten existing dependency/tool notices per variant. Signatures and checksums were verified by packaging.

**Verified on physical BOOX:** the signed update installed over 0.3.2 via USB. Package metadata confirms code 10/version 0.3.3 and the same data inode `129824`; no uninstall/data-clear was issued. Launch and the real-book popup were checked. Present ISBN/ASIN/Goodreads values remain visible; absent Hardcover and future-service tags have no rows. Screenshot: `dist/screenshots/boox-tracker-0.3.3-boox-metadata.png`. The physical book has no future-service identifiers, so their display and unrelated-tag rejection have automatic coverage only. This foreground check opened the app around 22:31–22:32 local time; it is not independent background evidence. The previous manual sync result remains separate. Book-only/background offline delivery still needs the current device test. No source push, GitHub workflow, release, or prerelease was performed.

## Metadata and folder cancellation 0.3.2

**Built:** debug and signed diagnostic APKs, version 0.3.2/code 9. Package, certificate, SQLite schema, export schema, and OAuth client ID are unchanged. Diagnostic artifact `dist/boox-tracker-0.3.2-diagnostic.apk`, SHA-256:

```text
61226f4ed285bbb31a3d04576e8c145a415fe3599927a1cb6a71ee67f51f2b61
```

**Automatically tested:** 76 tests, zero failures/errors/skips, empty stderr. The ISBN popup regression failed before the fix because it displayed only the null database ISBN; the shipping popup now uses the sync repository's merged database/ebook identifiers off the main thread. Synthetic EPUB tests check ISBN, Goodreads, ASIN, Hardcover slug, native label spans, and readable dates. The folder cancellation regression failed when a readable existing grant incorrectly produced the access prompt. It now covers valid and revoked grants through About and the native picker-result callback. A separate latch test reproduced the missing access prompt when the Activity recreated during cancelled-picker validation; it passes with activity-owned validation state. The test provider's denial path was repaired to throw before cursor allocation, removing a resource leak without filtering its warning. The metadata test waits for the rendered book before tapping, avoiding an early provider-call/snapshot race.

The full Gradle build/test/lint gate, Kotlin lint, Python format/lint, Markdown lint, Actions lint, whitespace/link checks, and both scoped reviews passed. Android lint has zero errors and ten existing dependency/tool notices per variant. The packaging script verified signatures and the retained diagnostic certificate. Source remains local; no GitHub workflow or release was dispatched.

**Verified on physical BOOX:** the signed update installed over 0.3.1 on the GoColor7 via USB on 2026-10-06. Package state reports code 9/version 0.3.2; data inode stayed `129824`. No data-clear or uninstall was issued. After normal wake, the installed ordinary-UID app read NeoReader. Its bordered popup visibly shows full ISBN, ASIN and Goodreads values, bold Title Case labels, NeoReader Database: OK, Progress State: OK, raw status, and readable distinct Last Access/Read At. Absent Hardcover tags produce no line. Screenshot: `dist/screenshots/boox-tracker-0.3.2-boox-metadata.png`. About → Change ebook folder opened the native picker; Back returned through the picker to Sync without an access prompt. The existing connection stayed On and displayed its prior match/sync state. This verifies the popup and readable-grant cancellation on the device; revoked-grant/recreation cases have automatic coverage only.

The update checks opened the app around 22:03–22:04 local time. Those foreground reads are not hidden-app evidence. Prior user-confirmed manual progress delivery remains separate; book-only fallback and offline hidden-app reconnect delivery are still pending in [the current device checks](device-testing.md#current-033-checks). Releases/prereleases remain on hold.

## Ebook-folder prompt 0.3.1

**Built:** debug and signed diagnostic APKs, version 0.3.1/code 8. Package and diagnostic certificate are unchanged from 0.3.0. Artifact `dist/boox-tracker-0.3.1-diagnostic.apk`, SHA-256:

```text
8ae1ea74e29a65dab2fade2e2df68e4a75e28c2986d0089b0ee7d981b60f1326
```

**Automatically tested:** 73 tests, zero failures/errors/skips. The startup regression failed on 0.3.0 because it launched ACTION_OPEN_DOCUMENT_TREE before confirmation. It now verifies the explanation precedes the picker, the positive action opens it, cancellation still blocks provider reads, and Exit finishes the activity. The full Gradle build/test/lint gate, formatting/static checks, and both scoped reviews passed. Android lint retains ten dependency/tool-version notices per variant. Prompt copy is inspected visually; tests do not assert exact wording.

**Verified on physical BOOX:** the signed update installed over 0.3.0 on the user's GoColor7 via USB on 2026-10-06. Package state confirms version 0.3.1/code 8 and the unchanged data inode; no data-clear or uninstall command was issued. Launch succeeded. A screenshot shows the bordered Ebook folder access explanation with Exit / Choose folder, before the picker: `dist/screenshots/boox-tracker-0.3.1-boox-folder-prompt.png`. UI XML automation returned a null root on this device, so this screen was checked visually. Folder selection and account approval were left to the user.

The user subsequently reported that the Hardcover row shows **Exact edition matched**, followed by **Synced at…**, and confirmed that progress reached Hardcover. Their Hardcover screenshots show **In the Blood** by Jack Carr as **Currently Reading**, with **240 of 480 pages (50%)**. This confirms the user-initiated connection/matching/progress-delivery path on the physical BOOX. The selected edition ID, exact raw provider fraction, and mutation sequence have not yet been inspected in an export; do not infer them from the rounded remote display. Book-only fallback and hidden-app offline delivery remain pending.

Their supplied consent-screen image shows the Boox Tracker app name and catalogue/library/profile scopes. Its sign-in code is not copied into project records. The next controlled test keeps Hardcover On, queues a changed observation offline, then reconnects with Boox Tracker hidden and checks remote progress from another device before reopening/exporting. App-open sends must remain separate from earlier delivery events.

## Automatic matching and offline sync 0.3.0

**Built:** debug and signed diagnostic APKs, version 0.3.0/code 7. Diagnostic package/namespace is `dev.otherguy.booxtracker`, debug adds `.debug`; new package data starts fresh and the old app/logs remain untouched. Artifact `dist/boox-tracker-0.3.0-diagnostic.apk` is 3,504,002 bytes, SHA-256:

```text
53d556562ec2b6b07cc9a569566c2646a052869f80faaae8eec63e2a5d43e598
```

The packaging script verified signatures and generated adjacent checksums/build metadata. Diagnostic certificate remains `678df89d1df3f2ba3d45c6e19bb016550fd837681ac82f8044e83f6ebf4b420d`. No signing identity or token was put into source. The public OAuth client ID remains unchanged. SQLite and export schema are version 1.

**Automatically tested:** 73 tests, zero failures/errors/skips, empty test stderr. Full required Gradle gate passed: `testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic`. Android lint has zero errors and ten dependency/tool-version notices per variant. Kotlin formatting/lint, Python format/lint, Markdown lint, actionlint, whitespace and relative document-link checks passed. Both code-reviewer and code-simplifier reviews returned clear final results after repairs. CI is configured but these local changes have not been pushed or run on GitHub. Publication remains on hold.

Real application paths with local HTTP, synthetic metadata/EPUBs, SAF providers, and SQLite cover identifier tags/URLs/ISBN conversion, title-author uniqueness, Goodreads work mappings, conflicting IDs, multiple editions, page-count fallback, existing edition/history protection, higher remote progress, device OAuth/refresh, per-book/account queues, database reopening, exact revision acknowledgement, failed account lookup, Off/On races, revoked grants, scheduled/foreground paths, metadata/folder popups, no selector, and bounded Activity rendering/retained scroll. The tests initialize native WorkManager's test harness before normal application startup, rather than replacing the connector/store/worker paths.

Observed regressions before fixes included PDF metadata incorrectly requiring EPUB setup, missing-ISBN and multi-edition holds, an earlier On defeating Disconnect, and failed Identity after token issuance retaining another account's cached identity. Queue tests simulate a processed remote write with a failed response; retry reads the existing remote read and avoids a duplicate. New observations during delivery survive the old revision's acknowledgement. These are automatic evidence, not proof of actual device scheduling or Hardcover mutations.

**Emulator checked:** the final signed APK installed and launched on API 32. Native startup folder picker granted read access; signed reinstall retained the grant and earlier Activity events. Sync/Activity, black Sync Now, Coming soon rows, no lower diagnostic/observation controls, provider-unavailable warning, and bordered About were inspected. Screenshots are `dist/screenshots/boox-tracker-0.3.0.png`, `boox-tracker-0.3.0-about.png`, and `boox-tracker-0.3.0-activity.png`. The emulator has no NeoReader or account fixture; no sample book/account was injected. These images establish rendering only.

**Verified on physical BOOX:** the signed 0.3.0/code 7 APK installed over USB on the user's GoColor7 on 2026-10-06. Android package state confirms the version, and the process and resumed MainActivity confirm launch. The first launch failed because the package was disabled (`enabled=3`, `lastDisabledCaller: com.onyx`); enabling this package allowed launch. This does not establish which BOOX policy disabled it or guarantee background execution. The old `org.readingsync.diagnostic` package was absent from installed/known package listings; no uninstall or data-clear command was issued. No account operation was performed during this installation.

Earlier ordinary-app provider/scheduled-read evidence below applies to the recorded GoColor7 firmware only. Real native sign-in, exact/book-only progress delivery, reboot queue retention, and hidden-app offline/reconnect work remain pending at this 0.3.0 handoff; later results are recorded above. Use [the current device checklist](device-testing.md#current-033-checks). If the old app is restored, disable its Background checks/tracker/observation before enabling it alongside the new installation; keep its logs. Opening 0.3.0 automatically collects/sends, so only pre-app-open scheduled/delivery events establish independent execution.

## Boox Tracker rename: 2026-10-06

**Built:** debug and signed diagnostic 0.2.2/code 6 APKs. The diagnostic artifact is `dist/boox-tracker-0.2.2-diagnostic.apk` (3,490,070 bytes), SHA-256:

```text
9d63e236431c7553f8a745f83e5f0d6ce2253443b887f58bae64f6b7c96ea97c
```

APK metadata confirms the diagnostic label Boox Tracker and development label Boox Tracker (dev), with the same package IDs and certificate as 0.2.1. Only branding, version metadata, public documentation, and future artifact names change. Stored work names, Keystore alias, external signing configuration, database/export schemas, and the Hardcover client ID remain unchanged. No new signing identity was created.

**Automatically tested:** 49 tests, zero failures/errors/skips, empty test stderr. Both builds and Android lint passed; lint has zero errors and ten dependency/tool notices per variant. Formatting, static checks, Markdown lint, and document-link checks passed. No copy-specific tests were added. The earlier substantive changes had both reviews; this rename received direct review.

**Emulator checked:** the signed diagnostic APK installed over 0.2.1 on the API 32 emulator. The visible title reads Boox Tracker, with no former app name in the screen labels. The screenshot is `dist/screenshots/boox-tracker-0.2.2.png`. This is an emulator installation/rendering check, not proof of BOOX or real account behavior.

**Physical BOOX:** on 2026-10-06, the user authorized installation over USB. After enabling BOOX USB Debug Mode, the GoColor7 was available to ADB. The checksum-verified signed APK installed successfully with `adb install -r` over version 0.1.2/code 3; no uninstall or data-clear command was used. Package metadata afterward reports 0.2.2/code 6. Android returned successful cold launch of MainActivity, and the app process remained running. Device properties confirm GoColor7, API 32, and build `2026-05-19_23-44_4.2-rel_0519_c76f35ce8`. This verifies physical installation and launch only. Visual layout, retained history, fresh provider reads in this version, and real Hardcover account operations still need user validation. ADB was used for installation/package/launch checks, not as evidence of ordinary-app provider access. The ordinary CI configuration and held prerelease workflow use the new artifact names; no workflow, release, or source push was performed. The external OAuth registration's consent-screen name has not been changed.

## Service UI update: 2026-10-06

**Built:** debug and signed diagnostic 0.2.1/code 5 APKs passed the full Gradle checks. The diagnostic artifact is `dist/reading-sync-0.2.1-diagnostic.apk` (3,489,986 bytes), SHA-256:

```text
667e34e45c95d1885bf462f1e7a8a9009a4725a57ef5c92e21a935afaeee8c74
```

Package, non-debuggable diagnostic configuration, certificate, database schema, and export schema are unchanged from 0.2.0. The packaging script verified signatures and generated checksums. No signing identity was regenerated.

**Automatically tested:** 49 tests, zero failures/errors/skips, empty test stderr. All required Gradle, Kotlin, Python, Markdown, workflow, document-link, and whitespace checks passed. Android lint has zero errors and the same ten dependency/tool notices per variant. Both parallel reviews and scoped follow-ups returned no actionable findings.

The added coverage exercises the actual service switch and production connection code with local HTTP: saved credentials enable without starting sign-in, absent or unreadable credentials start sign-in, approval enables sync, Off cancels pending approval, and a later On cannot be overwritten by delayed cancellation. A UI regression confirms that a disabled service fault does not warn, an enabled fault does warn, and a denied provider query still warns. The query-time assertion uses the stored read-start timestamp independently of NeoReader's last access. Timestamp and cancellation regressions were observed before repair. UI tests capture and assert the known Robolectric zero-ID resource warnings; unexpected diagnostics fail the tests.

One full run reported zero JUnit failures but an uncaught SQLite schema-creation exception in test stderr. The persistence test created a second helper while application recovery opened its store. The test now awaits startup recovery and uses the application's store before closing/reopening for retention assertions. The focused test and full suite passed afterward with empty stderr; production schema handling was not weakened.

**Emulator checked:** the signed diagnostic APK was installed and displayed on an API 32 Android emulator. `dist/screenshots/reading-sync-0.2.1.png` shows the service layout with Hardcover Off, no warning, and future providers Coming soon. A 720 × 1280 screen with font scale 1.3 keeps service names readable by placing switches below their text. No provider or account records were fabricated; the emulator has no NeoReader. This establishes layout on the tested emulator only.

**Verified on physical BOOX:** no new 0.2.1 installation, sign-in, folder grant, catalogue match, or remote account update has been verified. The earlier diagnostic evidence below remains separate. Install over the existing diagnostic app, check retained history, and validate one manual Hardcover sync. Then test scheduled sync with Reading Sync hidden and observation off, and export before manual actions. No GitHub publication or remote CI run was performed; the release hold remains.

## First Hardcover preview: 2026-10-06

Built 0.2.0/code 4 locally with the pinned mise toolchain. Both debug and signed diagnostic builds passed. The diagnostic APK is `dist/reading-sync-0.2.0-diagnostic.apk` (3,393,599 bytes), SHA-256:

```text
66f152802846423501aa41ecd9315a600c4a0391f8e03442a781bc45377cb8a3
```

The package remains `org.readingsync.diagnostic`, is non-debuggable, and uses certificate `678df89d1df3f2ba3d45c6e19bb016550fd837681ac82f8044e83f6ebf4b420d`. Version code increases from 3 to 4. Database and export schema remain version 1; no destructive migration or log deletion is introduced. Internet is added for OAuth/Hardcover; no broad storage permission is declared. Debug remains a separate package and cannot update the diagnostic installation. Packaging verified signatures and both artifact checksum files.

**Automatically tested:** 46 tests, zero failures/errors/skips, empty test stderr. The full shipping checks passed:

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
```

Coverage groups:

- Seven fraction/metadata tests preserve raw states, errors, cursor closure, identity, and SQLite data.
- Seven local background tests cover unique scheduling, cancellation, observation stop/deadline, context, interruptions, and exports.
- Eight Activity tests cover recycled rows/history and automatic latest-book detection across manual, scheduled, and observation reads. Old saved choices are ignored; missing usable timestamps do not choose an arbitrary book.
- Eight ISBN/EPUB/SAF tests cover ISBN checksums/conversion, EPUB 2/3 identifiers, unsafe/malformed/external-entity metadata, raw-fraction page conversion, read-only document streams, ambiguity, metadata caching, and provider ISBN preference.
- Sixteen Hardcover tests use real local HTTP requests and the production connector/worker: first/repeat/advance sends, missing/ambiguous editions, higher-progress/history/edition conflicts, mutation/GraphQL failures, device pending/slowdown/expiry, encrypted vault, refresh rotation and rejection, disconnect, fresh scheduled reads, cancellation, and unreadable-credential state.

Four review regressions failed on the prior implementation before repairs: numeric non-ISBN schemes, zero-progress edition conflicts, shortened server intervals, and writes after cancellation. A fifth reproduced hidden unreadable credentials. All passed after repair. Both parallel reviews and scoped re-reviews returned no remaining actionable findings. Test-only MockWebServer 4.12.0 replaces the unavailable JDK HTTP-server API in the Android test compiler; no production HTTP dependency was added. WorkManager's test database is closed with its supported helper instead of suppressing leak warnings.

Android lint: zero errors, ten dependency/tool-version notices for each variant. The newly added label warnings were fixed with a resource. Pinned tested versions remain. Kotlin, Python, Markdown, workflow, local-document-link, and whitespace checks passed. CI remains configured on push/pull request; this local 0.2.0 state has not been pushed or remotely tested. No prerelease/release workflow was run.

**Verified on physical BOOX:** no new 0.2.0 installation, Keystore/OAuth, SAF picker, ISBN catalogue match, or remote account update has been tested. Prior provider/background evidence below remains valid only for its recorded firmware/cases. Local HTTP and software-key tests establish app behavior, not Android Keystore behavior or Hardcover account permissions on the user's device. Follow [the first connector device test](device-testing.md#first-hardcover-build-2026-10-06). After manual sync works, test ordinary scheduled sync separately with observation off, Reading Sync hidden, and exports taken before manual actions.

## Historical diagnostic evidence scope

Local checks for 0.1.2 passed on 2026-10-05. Provider/background findings below come from the user's 0.1.0 installation, exports, and timed actions on 2026-10-04, followed by 0.1.1/0.1.2 exports on 2026-10-05. They confirm independent scheduled reads after boot, retained logs, saved progress, and reads near cover close/after wake. The user confirmed faster Activity scrolling after 0.1.2, without a timed benchmark. Regular sleep execution remains unverified. Observation and its controls were removed in 0.3.0; their records are retained as historical evidence.

## Built

This section records the historical 0.1.2 diagnostic build. The current artifact is in [Identifier allowlist 0.3.3](#identifier-allowlist-033).

Both variants built with the tool versions in `.tool-versions`:

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
mise exec -- python scripts/package-artifacts.py
```

The diagnostic APK is non-debuggable, targets API 36, and requires API 26 or higher. Its application ID is `org.readingsync.diagnostic`, version name `0.1.2`, version code `3`. The debug build has a separate application ID and development certificate. The update changes Activity rendering: recycled rows, on-demand details formatted off the main thread, grouping off the main thread, and collection of log updates only while the screen is started. Activity no longer parses the library snapshot. It preserves provider progress calculation, JSON schema, database version, permissions, and background query/scheduling behavior. Expanded groups and scroll anchors survive live updates; filter changes start at the top. Group identity survives filtering and screen recreation.

APK Signature Scheme v2 verification passed for both files. Each delivered file was rehashed and matched its adjacent `.apk.sha256` file. The diagnostic certificate SHA-256 is:

```text
678df89d1df3f2ba3d45c6e19bb016550fd837681ac82f8044e83f6ebf4b420d
```

The dedicated identity is outside the repository in `~/.config/reading-sync/`. The key and properties file have mode `0600`. Retain a secure backup to sign compatible updates. `dist/build-info.json` contains public signature details and artifact hashes.

The delivered update is `dist/reading-sync-0.1.2-diagnostic.apk` (3,362,675 bytes), with SHA-256:

```text
e33ed852a9029c0f67dc74dc0fee0eece81d7e16707bfef9d9c70359793d46a3
```

Direct checks of the old and new APKs verified the same package and certificate, version code increasing from 2 to 3, and version name 0.1.2. The new APK is not debuggable and declares no Internet or broad storage permission. This establishes the signing and package requirements for an update; it does not replace the physical installation and log-retention check.

## Automatically tested

This section records the historical 0.1.2 suite, not the current 76-test total.

Twenty tests passed, with zero failures, errors, or skipped tests:

- Three fraction tests: raw preservation, calculated percentages, large units, malformed/null/zero/out-of-range handling.
- Four metadata tests: missing columns, unknown status, cursor closure, safe fields, provider errors versus empty cursors, local persistence, deadline boundaries.
- Seven background tests: unique work and cancellation, context fields, export privacy, change detection, interruption recovery, non-cooperative provider cancellation, foreground Start/Stop, and automatic session expiry.
- Six Activity tests: bounded row work with full export; grouped evidence and filters; recycling/live-update scroll stability; expanded group growth, event-window trimming, filter round trips, and recreation; scrolled filter reset; empty-filter state without deletion.

These tests use Robolectric API 34 and a fixture provider. Activity tests load the real SQLite store and shipping Activity/adapter. The initial regression failed before the fix: 250 entries created 254 buttons before scrolling. The replacement test loads 275 retained events, keeps all in export, and bounds rendered controls to fewer than 32 while displaying the latest 250. Collapsed details stay empty. A negative control that removed group-key retention closed expanded details and failed its regression; the implementation was restored. These are deterministic work/state checks, not BOOX timing measurements. The foreground service and scheduling tests call the app's shipping classes. They do not test NeoReader or Android execution on BOOX firmware.

Kotlin formatting/lint, Ruff formatting/lint, Markdown lint, and Actions lint passed. Fresh Android lint runs passed for both variants with zero errors and nine dependency-version notices per variant. The pinned compatible dependencies were retained. Test-owned database handles are closed; the final tests emitted no resource-leak warnings. The Activity harness captures the verified Robolectric `CppAssetManager2` zero-resource-ID warning and rejects other stderr output; final test reports have empty stderr.

The initial implementation's reviews covered ambiguous record identity, query cancellation, safe exports, observation deadlines, callback registration, selection state, and activity details. The prerelease signing step was checked with synthetic Unicode and leading-space values through Java Properties loading. No real secrets were printed. Independent read-only reviews of 0.1.1 were clear. For 0.1.2, review found expanded-group identity loss during growth/filtering; the model now retains keys across overlapping events and screen recreation, with regression coverage. Final code review and simplification review returned no remaining actionable findings.

## Verified on physical BOOX

The user installed the non-debuggable 0.1.0 diagnostic APK through the normal file installer. Play Protect reported no issues. Provider queries ran under the ordinary application UID without root, ADB privileges, storage access, or vendor permissions. Testing and source evidence are limited to this device and firmware:

| Field | Observed value |
| --- | --- |
| Manufacturer / model | ONYX GoColor7 |
| Android | 12, API 32 |
| BOOX build | `2026-05-19_23-44_4.2-rel_0519_c76f35ce8` |
| Incremental build | `9661` |
| Security patch | `2025-03-01` |
| NeoReader version | Not recorded |
| App | 0.1.0, version code 1, diagnostic variant |

Times below are local, UTC+07:00. Source exports are retained by the user outside the repository. This report omits book titles, identifiers, paths, and provider blobs. The last inspected 2026-10-04 cumulative export contained 226 events and 186 successful queries with no recorded query failures; subsequent fresh manual-query and display comparisons were reported by the user. The 2026-10-05 exports contain 253 events / 201 successful queries at 11:11, 262 / 204 at 19:55, and 283 / 211 at 22:46. There are no recorded query failures, but an unfinished scheduled job and earlier observation interruption remain in the retained evidence.

### Provider and progress

The provider returned 786 library records and 45 columns. Candidate fields `name`, `uuid`, `nativeAbsolutePath`, `progress`, `readingStatus`, `lastAccess`, and `extraAttributes` were present. Their availability does not imply non-null values for every record. An inspected snapshot had 772 null progress values and 14 valid fractions. Raw status codes `0`, `1`, and `2` occurred. These are record counts, not proof of 786 distinct books.

A read-only ADB projection on 2026-10-06 returned 786 rows: 772 with code `0`, all with null progress and only 11 with a `lastAccess`; 6 with code `1` and partial fractions, four of them at 1 to 11 of several hundred units; and 8 with code `2`, every one at a full fraction such as `910/910` or `10000/10000`. The user confirmed the meanings: `0` not started, `1` reading, `2` finished. Five works appeared twice, once as an untouched code `0` file and once as a different file carrying the real state, so a file record is not a unique work. The ADB query is desktop evidence of provider contents, not app-UID evidence.

Discovered columns:

```text
name, title, authors, publisher, language, ISBN, description, location,
nativeAbsolutePath, nocasePath, size, encoding, lastAccess, lastModified,
progress, favorite, rating, tags, series, extraAttributes, type, cloudId,
uniqueCloudId, parentId, readingStatus, hashTag, storageId, fetchSource,
coverUrl, ordinal, downloadInfo, encryptionType, digest, drmType,
fileOriginSize, fileSyncStatus, userDataSyncStatus, status, uuid, extraInfo,
id, guid, idString, createdAt, updatedAt
```

Older stored fractions included `356/923`, `1045/1045`, and `1103/1103`. On later access, inspected records exposed fractions with a denominator of `10000`. Finished records changed from their older fractions to `10000/10000` while remaining at 100%. These formats coexist; the numerator must not be assumed to be a physical page number. No allowlisted `current_page_position_v2` value was captured, which does not establish that the underlying blob lacks it.

In one controlled reading session, the provider stayed at `4060/10000` through successful observations after the final page turn. An observer query at 13:07:19, corresponding to the user's library exit, found `4149/10000`. In a second book, backward page turns left observed progress at `10000/10000` until library exit; the 13:22:38 observer query then returned `9931/10000` and status changed from `2` to `1`. A finished book opened without page turns remained at 100% with status `2`. The association of `1` with in-progress and `2` with finished is observed for these records, not a universal status contract.

Changing font size from 28 to 42 without page turns left `4149/10000` unchanged through library exit. Most-recent selection followed stored `lastAccess`; one newly opened book became the most recent record only on exit. It cannot reliably identify the book currently open.

The user later confirmed a fresh query at an unchanged position: NeoReader displayed 48.24% while the provider returned `4723/10000`, or 47.23%. One page turn changed the in-book display to 48.51% and the provider to `4752/10000`, or 47.52%. The library's whole-percent tag displayed 47%, consistent with the provider-derived value. The app value advances, but the in-book percentage calculation and the library's rounding rule remain unknown. The user confirmed that NeoReader retained the same position across cold boot.

### Observation and scheduled work

Observer-triggered queries found metadata changes while Reading Sync was hidden. Observation sessions also recorded polling gaps, delayed cleanup after sleep, and one unresolved session detected on a later process start. The cause of that interruption was not recorded. Foreground observation does not prove ordinary worker execution.

The cumulative export contained five successful scheduled queries with both app-visible and observation-active flags false at start and finish:

| Query time | User's reported context | Result |
| --- | --- | --- |
| 11:52:13 | Observation had ended; app hidden | 786 records |
| 12:07:23 | Cover closed since about 12:03; observation off | 786 records |
| 13:41:27 | Reading in NeoReader; app hidden; observation off | 786 records |
| 14:24:35 | Reading resumed in NeoReader; observation off | 786 records |
| 14:39:57 | Just after reported wake from sleep; observation off | 786 records |

At 14:39, the provider had advanced from `4318/10000` to `4416/10000` without a reported library exit. This permits additional persistence triggers beyond the controlled library-exit cases; it does not isolate reading, sleep, or wake as the cause.

Execution was irregular with BOOX settings unchanged. A 45-minute cover-closed interval did not power the device off, but no subsequent query was recorded until Reading Sync was opened. No query was recorded between 14:39:57 and 22:12:48. The user reported a later automatic power-off and power-on at about 22:02. The exact shutdown time is unknown, so the entire gap cannot be attributed to power-off. Reset monotonic uptime supports the reported reboot.

The 22:12 worker used the retained periodic-work ID and queried successfully after reboot, but Reading Sync was visible. This shows retained work and logs; that execution alone does not establish automatic independent background execution after boot. Other workers with foreground-app or observation-service overlap are also excluded from independent background evidence.

### Cold boot and upgrade retention: 2026-10-05

The new JSON and text export identify app 0.1.1/code 2, diagnostic variant, on the same GoColor7 firmware. The user reported cold boot at 10:35, direct entry into NeoReader at 10:36, reading until 11:10, then opening Reading Sync and exporting at 11:11 before Read now. Background checks remained enabled and observation remained off. Recorded wall time and monotonic uptime place this boot at about 10:35:51, consistent with the reported time.

Two scheduled jobs completed before Reading Sync was opened:

| Job start | Query completed | Query duration | Records / columns | Raw progress / percentage |
| --- | --- | --- | --- | --- |
| 10:48:01.659 | 10:48:03.644 | 1.869 s | 786 / 45 | `4752/10000` / 47.52% |
| 11:06:48.559 | 11:06:50.595 | 1.939 s | 786 / 45 | `4752/10000` / 47.52% |

Each job has matching start, successful query, and completed stop events. All query start/finish app-visible and observation-active flags are false; job start/stop flags are also false. There is no observation or manual query after this boot before export. These executions confirm independent scheduled provider access after cold boot while the user was in NeoReader on this firmware. The roughly 35-minute test already contains positive evidence; it does not need repetition merely to reach the originally requested 45 minutes.

The recorded gap between query starts is 1,126,898 ms, about 18 minutes 47 seconds, rather than exactly fifteen minutes. The earlier 10:32 scheduled query and 10:34 manual query belong to a different boot, estimated at 10:31:55, and are excluded from this test.

Both reads found unchanged selected metadata, including raw status `1` and last access at 2026-10-04 23:55:45.914. The latest snapshot was read at 11:06:50.508; the export was created at 11:11:07.572. Successful scheduled access does not establish current live progress or isolate its persistence trigger. The user did not report a library exit during this test. A subsequent library exit followed by Read now can check whether the persisted fraction changes.

The export retains earlier manual, observation, and scheduled events from 2026-10-04, beginning at 11:33:54.212, in its 253-event history. This confirms earlier log retention through the reported 0.1.1 update and this reboot. The user subsequently confirmed retained logs after installing 0.1.2; that report is separate from the pre-update export below.

### Saved progress and Activity update: 2026-10-05

The follow-up JSON and text export were created at 19:55:40.206 from 0.1.1/code 2, before the user installed 0.1.2. The history contains the earlier 253 events before the 11:11 export time and nine later events. The previous source file is no longer available at its supplied path, so this comparison uses the earlier inspected evidence and the retained current history rather than claiming a byte-for-byte match between the two files.

| Query completed | Source | Result | Selected raw progress / percentage | Selected last access |
| --- | --- | --- | --- | --- |
| 11:46:03.448 | Scheduled | Success, 786 records / 45 columns | `4966/10000` / 49.66% | 11:10:39.380 |
| 19:54:20.839 | Scheduled | Success, 786 records / 45 columns | `4966/10000` / 49.66% | 11:10:39.380 |
| 19:55:21.782 | Manual, Read now | Success, 786 records / 45 columns | `4966/10000` / 49.66% | 11:10:39.380 |

These reads select the same record as the earlier cold-boot queries. At 11:46, the worker found progress advanced from 47.52% to 49.66%, along with changed last-access and extra-attribute fields. Its raw status stayed `1`. Both later scheduled queries have false app-visible and observation-active flags at start and finish, matching successful job start/query/completed-stop records. The manual query confirmed the same values; it did not expose another change.

The last-access value of 11:10:39 agrees with the user's reported reading stop near 11:10. The first observed changed fraction was read at 11:46. This supports access to saved progress without a manual query, but the provider's own last-access value is not a timestamp of the companion's query or proof of the exact write time. The observations do not establish persistence on every page turn or that library exit is the only trigger.

A scheduled job started at 12:11:39.801 without a recorded query result or stop. At 19:54:18.733, the app detected that unfinished run; a new job then queried successfully. Monotonic uptime places the later boot at about 19:53:47. The export does not record when the earlier run ended or the device powered off, so the long gap cannot be attributed entirely to sleep, power-off, or a specific termination cause. Sleep/power-on times for this interval have been requested from the user and remain unknown.

After this export, the user reported successful installation of 0.1.2, much faster Activity scrolling, and retained logs. The scrolling fix and update retention are therefore confirmed by direct device observation. This export itself remains 0.1.1 evidence; the later 0.1.2 export below establishes worker and sharing behavior separately.

### Sleep and wake on 0.1.2: 2026-10-05

The latest JSON and text export identify 0.1.2/code 3, diagnostic variant, on the same GoColor7 firmware, and were created at 22:46:44.106. The user reported closing the cover at 21:50, opening it at 22:25, and remaining in NeoReader until 22:45. The device had fallen asleep again by the end, but had not powered off. The later twenty-minute interval therefore cannot be treated as continuously awake.

The export retains 283 events and 211 successful queries. After the earlier 262-event history, it adds seven complete scheduled start/query/completed-stop groups from 20:09 through 22:46. All seven queries returned 786 records and 45 columns. Query start/finish and job start/stop app-visible and observation-active flags are false. No new manual query, observation event, query failure, or interruption was recorded in this interval. Query durations ranged from 1.434 to 6.223 seconds. The completed export also confirms sharing and retained earlier history on 0.1.2.

The three reads around the reported sleep/wake test selected the same record:

| Query completed | User's reported context | Raw progress / percentage | Provider last access |
| --- | --- | --- | --- |
| 21:50:29.143 | Near cover close at 21:50 | `4966/10000` / 49.66% | 11:10:39.380 |
| 22:25:10.055 | Just after cover open at 22:25 | `4966/10000` / 49.66% | 21:50:36.992 |
| 22:46:05.074 | After leaving NeoReader near 22:45; app still hidden during query | `5007/10000` / 50.07% | 22:45:37.793 |

No query was recorded between the near-close and wake reads. Their start-to-start gap was 2,080,778 ms, about 34 minutes 41 seconds. The next gap was 1,255,446 ms, about 20 minutes 55 seconds. These observations confirm independent scheduled access near wake and later persisted progress, but do not prove sustained execution while asleep. Minute-level cover-close timing cannot establish whether the 21:50:29 read occurred during sleep or its transition. No exact interval, sleep restriction, or reason for each delay can be inferred from these timestamps alone.

Wall time minus monotonic uptime is stable at approximately 21:49:54.621 across all three reads, supporting no reboot during the reported 21:50–22:45 interval. Compared with earlier evening readings, uptime indicates a boot just before this interval; the user did not supply that initial power-on time. Keep it separate from the user's report of no power-off during the sleep test.

At wake, last access and extra-attribute fields had changed while the raw fraction remained unchanged. The 22:46 worker then found changed progress, last access, and extra attributes; raw status stayed `1`. The provider's last-access value is its own metadata, not the time of the companion query or proof of the exact write time. This is consistent with earlier observations of saved metadata on library exit, without establishing exit as the only persistence trigger.

The user reported 51.8% inside the book and 50% in the library overview. The queried 50.07% agrees with the library's whole-percent tag. Keep the provider-derived fraction without an offset; the in-book calculation and library rounding rule remain unknown.

### Remaining checks

The manual follow-up, 0.1.2 installation, faster Activity scrolling, retained logs, independent 0.1.2 scheduled reads, and export are confirmed. The controlled sleep/wake test is recorded; no repeat is needed merely to extend its 35-minute duration to 45 minutes. The diagnostic milestone has sufficient evidence for the tested cases. All/Issues and expanded details have automatic coverage but no explicit physical-test report. Further overnight/repeat-boot testing is optional evidence gathering, not a promise of regular sleep execution. See the [remaining device checks](device-testing.md#remaining-device-checks).

Compatibility with other BOOX models or firmware, reliable execution across repeated boots and long sleep, an exact fifteen-minute cadence, and the exact relationship between provider and in-book progress remain unverified. One successful cold-boot interval does not guarantee every later execution.

## GitHub

The configured remote is [otherguy/boox-tracker](https://github.com/otherguy/boox-tracker). The initial source commit `a795e98c0e77f8b9af61d3033ac5c36e8b5aa6f0` passed the [Android checks workflow](https://github.com/otherguy/boox-tracker/actions/runs/37175084972); its successful conclusion and matching commit were rechecked during the 0.1.1 work. This CI result covers 0.1.0, not the local 0.1.1/0.1.2 changes.

No release existed at the recorded initial GitHub check. No release/prerelease was created during later local work, and publication remains on hold. Current source remains local on feat/automatic-offline-sync at the original 0.1.0 HEAD; no 0.3.x remote CI run is recorded. The manual workflow and [signing instructions](build-and-release.md#github-delivery) remain a future path, not authorization to dispatch it.
