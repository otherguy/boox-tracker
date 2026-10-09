# Project status and handoff

Updated 2026-10-09 after 0.7.0, which adds Pagebound and changes Goodreads to the same whole-step rule; it is committed at `7247556` and installed on the GoColor7; its Pagebound device checks are open. 0.6.0 added Goodreads at `1b52866`; 0.6.3 was installed before 0.7.0. App name: **Boox Tracker**. Reading Sync is the historical name. This file holds the current state and next steps; [AGENTS.md](../AGENTS.md) holds only durable rules. Read [product decisions](product.md), the [Fable plan](plan-20261006-fable-sync.md), the [StoryGraph plan](plan-20261007-storygraph-sync.md), and [verification](verification.md) before changing behavior. Public product copy is in [README](../README.md).

## Resume here

State on 2026-10-07:

- **Source:** Hardcover (automatic matching, offline queue, completion sync), Fable, StoryGraph, Goodreads, and Pagebound are built. Pagebound (0.7.0/code 29, [plan](plan-20261008-pagebound-sync.md)) is committed at `7247556`; it also moves Goodreads to posts in whole steps of 5 counted from 0. Goodreads (0.6.0/code 25, [plan](plan-20261008-goodreads-sync.md)) is pushed on `main` at `1b52866`; [Android checks](https://github.com/otherguy/boox-tracker/actions/runs/37792333565) were running when it was installed. 0.5.2 (StoryGraph, `5088fca`), the bold Hardcover code and 0.5.3 (Syncing spinner) are pushed on `main` at `dd4ef52`, and [GitHub Android checks passed](https://github.com/otherguy/boox-tracker/actions/runs/37771908812) for it on a re-run; the first attempt failed at Gradle configuration because the runner could not resolve `kotlin-stdlib` from any repository, with the build scripts unchanged apart from the version fields. 0.4.8 is pushed at `92089e5`, and [GitHub Android checks passed](https://github.com/otherguy/boox-tracker/actions/runs/37650190310) for it. 0.4.7 is pushed at `bb73e8e`, and [GitHub Android checks passed](https://github.com/otherguy/boox-tracker/actions/runs/37609064626) for that commit. Checks [failed](https://github.com/otherguy/boox-tracker/actions/runs/37583646420) for `a72c541` on a test race fixed in 0.4.5 (see [verification](verification.md#provider-rows-and-ci-diagnostics-045)).
- **Device:** The signed 0.7.0/code 29 APK (SHA-256 `a7208364…b26840ee`) was installed over 0.6.3/code 28 by USB at 10:29 device time on 2026-10-09 and reinstalled at 10:34 and 10:38 with the sign-in popup changes, with the data inode `129824` unchanged, the package enabled, and no uninstall or data clear. Started by ADB, MainActivity resumed with no crash logged. The screen showed Hardcover, Goodreads, StoryGraph, and Fable still On with "same edition" at 53.35%, Pagebound Off and Not connected between Fable and Margins, and Margins Coming soon (`dist/screenshots/boox-tracker-0.7.0-boox-sync.png`); that app-open is foreground evidence only. Before that, signed 0.6.3/code 28 (renewal start delay and timeout state) was installed over 0.6.2 by USB at 00:44 on 2026-10-09, data inode `129824` unchanged; it started with no crash, and the Goodreads renewal work kept its next run at about 05:20 (see [verification](verification.md#renewal-start-delay-and-timeout-state-063)). Before that, signed 0.6.2/code 27 (Clear in Activity) was installed over 0.6.1 by USB at 00:28 on 2026-10-09, data inode `129824` unchanged; it started with no crash, and Clear was not tapped (see [verification](verification.md#clear-activity-062)). Before that, signed 0.6.1/code 26 was installed over 0.6.0 by USB at 22:27 on 2026-10-08, data inode `129824` unchanged; it fits Goodreads' desktop sign-in page to the popup (see [verification](verification.md#sign-in-page-width-061)), and the user then signed in to Goodreads there; the first sync matched In the Blood on the shelved Kindle edition ("same edition"), read back 50%, and held the post under the 5-point step until 55% (see [verification](verification.md#sign-in-page-width-061)). Before that, signed 0.6.0/code 25 was installed over 0.5.3 by USB at 21:25 on 2026-10-08 (device time), data inode `129824` unchanged, started by ADB with no crash logged; Goodreads shows Off and Not connected, and the other three services stayed On. Before that, signed 0.5.3/code 24 was installed on the GoColor7 by USB, reinstalled at 18:39 on 2026-10-08 (device time) after the spinner fixes, over 0.5.2 (18:04), 0.5.1 (17:37) and 0.5.0 (17:25), each started by ADB with no crash logged. The Syncing spinner and one-line label were seen mid-sync; StoryGraph shows "same edition" since the edition switch. The user signed in to StoryGraph on 0.5.1; 0.5.2's app-open sync matched In the Blood by ISBN and wrote 52% to the shelved hardcover, confirmed on StoryGraph (see [verification](verification.md#storygraph-tracker-050-popup-keyboard-051-page-attributes-052)). In the Blood is at 52.23% in NeoReader. Before that, signed 0.4.8/code 20 was installed over 0.4.7 at 18:15 on 2026-10-07 and started by ADB with no crash logged. The user then checked the new Activity tab and popup on the device and reported that it looks right. Before that, 0.4.7/code 19 was installed at 17:37 and opened by ADB. In the Blood is at 52.23%. Hardcover keeps 254 of 480 pages and warns with the amber triangle; Fable is level at 52% with no warning. The ebook file was replaced at 13:56 that day, and NeoReader keeps a second record for it (see [verification](verification.md#source-identifiers-and-display)).
- **Hardcover account:** In the Blood has one read, 7080312 (start date 2026-09-14 as edited by the user, 254 pages, Currently Reading). The user deleted the undated duplicate 7080314 after the 0.4.4 test and switched their edition to Kindle edition 30462394 (ISBN 9781982181680), which now matches the ebook's ISBN; the read uses the same edition. Data directory inode `129824` is unchanged; no uninstall or data clear was issued.
- **Fable account:** on 2026-10-07 the user removed the US ebook of In the Blood from their Fable shelves and changed the book's ISBN on the BOOX to get a different Fable edition. The Fable row then showed a pending match; no Fable result for the new ISBN has been inspected. The UK paperback sibling had a stray 0% record on no list.
- **StoryGraph account:** In the Blood is currently reading at 52% on StoryGraph on the Kindle edition (ISBN 9781982181680, the ebook's own ISBN) since 18:19 on 2026-10-08, when the user had the edition switched from the hardcover that the 2026-10-07 write test had shelved. The 18:25 delivery after the next page turn reported "same edition" at 52.93%. Cloudflare accepted the app's `HttpURLConnection` requests with the WebView's cookies minutes after sign-in; the spike's later intervals are open. See [StoryGraph device checks](device-testing.md#storygraph-sync).
- **CI and hooks:** two CI races exposed by 0.6.0 are fixed at `e85e8c5`, whose [Android checks passed](https://github.com/otherguy/boox-tracker/actions/runs/37794372757). CI is now split into a Lint workflow and a path-filtered Android checks workflow, both cancelling superseded runs, and `lefthook.yml` mirrors them; see [build and release](build-and-release.md#github-delivery).
- **Clear activity:** an outlined Clear button beside Export deletes every Activity event after one confirmation and leaves one "Activity cleared" event ([plan](plan-20261008-clear-activity.md)). Built, automatically tested, and installed on the GoColor7 as 0.6.2/code 27; pushed on `main` at `439d898`, where [Android checks](https://github.com/otherguy/boox-tracker/actions/runs/37817672555) and [Lint](https://github.com/otherguy/boox-tracker/actions/runs/37817672458) passed (see [verification](verification.md#clear-activity-062)).
- **Renewal fixes:** 0.6.3/code 28 starts the Goodreads renewal job one interval after the last renewal instead of at sign-in, and a renewal timeout records where the hidden browser stopped. The 23:20 timeout on 2026-10-08 was that job's first run, right after sign-in; its cause is unknown (see [verification](verification.md#renewal-start-delay-and-timeout-state-063)).
- **Publication:** GitHub releases and prereleases are on hold until the user explicitly asks. A successful sync does not lift the hold.

Next physical checks, in order:

1. [Pagebound sync](device-testing.md#pagebound-sync) on 0.7.0: sign-in, the first sync of In the Blood (edition set, no post below 55%), one post at 55% together with Goodreads, completion, and Log out. Every Pagebound write is unexercised until then.
2. [StoryGraph transport spike](device-testing.md#storygraph-sync), continued: a sync the next day and one from another country. The sign-in, the first syncs, a sync after a reboot, and a manual sync at 19:17 (about an hour and a half after sign-in) passed on 2026-10-08. A challenge later re-plans the StoryGraph transport before anything else is released.
3. [Goodreads sync](device-testing.md#goodreads-sync) on 0.6.0: sign-in, the first sync of In the Blood (the user shelved the Kindle edition and posted 50% themselves on 2026-10-08; its ISBN 9781982181680 confirms that edition, so the row should read "same edition", and 52% is under the 5-point step, so the first sync should post nothing), the 5-point step, a renewal run after six hidden hours, the finish date, and Log out with StoryGraph still connected. The hidden-WebView challenge path has no device evidence; no challenge was seen during research.
4. [Fable sync](device-testing.md#fable-sync), including the first reading day that has no "I read today" tap: after the sync, the day must show as read on Fable's streak. The first device sign-in and the first sync more than one hour later are the first live checks of Firebase sign-in and refresh.
5. [Completion sync](device-testing.md#completion-sync) on Hardcover.
6. [Hidden-app offline collection and delivery](device-testing.md#offline-collection-and-hidden-app-delivery).
7. [Book-only fallback](device-testing.md#book-only-fallback) and queue retention through restart and reboot.

Opening the app collects and sends in the foreground; inspect earlier scheduled and delivery entries separately. Do not rebuild the provider layer or repeat completed 0.1.x diagnostic sessions.

## Built: 0.7.0 / code 29

| Item | Current value |
| --- | --- |
| Toolkit | One native Kotlin module, Android Views/XML, AndroidX; MIT |
| Diagnostic package / namespace | `dev.otherguy.booxtracker` |
| Development package | `dev.otherguy.booxtracker.debug`; separate data and signing |
| SDK | Minimum 26; compile/target 36; tested physical device is API 32 |
| Data / export schemas | Both version 1; not the application versionCode |
| Ebook identity cache | Namespace version 2; older parsed metadata is read again |
| Diagnostic APK | `dist/boox-tracker-0.7.0-diagnostic.apk`, 3,766,947 bytes |
| Diagnostic APK SHA-256 | `a7208364ac9092fdcfa09540e8c65f4ab193b46213fab2a5706f09bcb26840ee` |
| Diagnostic certificate SHA-256 | `678df89d1df3f2ba3d45c6e19bb016550fd837681ac82f8044e83f6ebf4b420d` |
| Signing configuration | External `~/.config/reading-sync/signing.properties`; reuse it |

Adjacent checksum files, the debug APK, `dist/build-info.json`, and screenshots are generated outputs. Do not edit them. See [build and release](build-and-release.md) for commands, secrets names, and update rules.

### Source and identifiers

Ordinary-UID `ContentResolver.query()` reads `content://com.onyx.content.database.ContentProvider/Metadata`. Queries run off the main thread and close cursors. Provider absence, denial, query failure, empty library, and unusable progress stay distinct. Raw fractions/status codes are retained. A percentage is calculated only from a valid fraction. Unknown progress is never 0%.

The most recent unique usable `lastAccess` selects the saved book automatically. Missing/tied times stop detection. Stored activity can lag the book currently open. Database title/authors serve every ebook format exposed by the provider. Bounded read-only EPUB parsing supplies embedded identifiers; PDF/MOBI/other embedded metadata parsers are not implemented. Their database identifiers/title/author remain usable.

`BookIdentifiers.kt` is the single identifier allowlist: ISBN, ASIN, Goodreads, distinct Hardcover edition/book/slug identities, and explicit StoryGraph/Fable/Margins tags. Fable matching uses `fable:` UUIDs and StoryGraph matching uses `storygraph:` edition UUIDs; Margins tags are display-only bounded opaque IDs, not validated API contracts. Unknown tags are ignored. The UI labels reader results without another allowlist. ASIN is used by the current Hardcover edition lookup.

### Hardcover and delivery

Native device-code OAuth uses public client `bc5f2c0f-79d7-42b5-b525-6293454d3934`. All installs share the client ID; each approval obtains separate private account tokens. Keystore encryption and serialized refresh protect credentials. No client secret is shipped. Background work never opens approval UI.

Matching tries explicit Hardcover identities, ISBN-13/ISBN-10/ASIN, accessible Goodreads mappings, then one normalized title/alternative-title plus author match. Multiple editions of one book establish book identity. Conflicts and ambiguity hold the update with a visible error, without dialogs. Successful matches are cached by source identity and metadata fingerprint, with a one-hour expiry.

Progress uses a verified positive page-count basis: existing active remote edition, exact source edition, default ebook, then default physical. Existing remote editions are preserved. Conversion is approximate pages, rounded HALF_UP. Higher remote progress and history are protected. Missing page counts, reread/status/history conflicts remain held. A finished source (status `2`, full fraction) completes the read with a last-access finish date and sets the book to Read; an already-Read remote book is reported current. There are no catalogue edits or automatic rereads.

SQLite persists the latest queued revision per account/service/source book before delivery and retains all observations. Sending is serialized. Only the revision sent is acknowledged; newer observations survive. Uncertain writes read remote state before another mutation. Separate unique WorkManager jobs collect locally every requested fifteen minutes without a network constraint, and deliver with a network constraint using `APPEND_OR_REPLACE`. Android/BOOX determine actual execution times.

An already-connected account can enable offline. First-time connection failure returns Off. Enabled services stay On while offline; waiting is neutral. Off pauses pending work, and the same account resumes it. Account keys and authenticated identity checks prevent cross-account sends. Opening the app and Sync Now collect fresh data and attempt delivery.

### Fable

Fable is built in 0.4.0 through its unofficial app API; see the [Fable plan](plan-20261006-fable-sync.md) and [contract](integrations.md#fable). Email/password sign-in stores only Firebase tokens. Matching, shelving, floored percentage writes with read-back, and holds use the same queue as Hardcover through `TrackerConnection`. No physical evidence exists yet; see [Fable device checks](device-testing.md#fable-sync).

### StoryGraph

StoryGraph is built in 0.5.0 through its website session; see the [StoryGraph plan](plan-20261007-storygraph-sync.md) and [contract](integrations.md#storygraph). Sign-in is StoryGraph's own page in a WebView inside the popup; the cookies stay in the WebView cookie store, and `HttpURLConnection` requests carry them with the WebView's User-Agent. Matching confirms fuzzy search hits by the edition page's `ISBN/UID`; sync marks the book currently reading, writes the floored percentage, confirms by re-reading the page, or marks the book read. A Cloudflare challenge or a sign-in redirect ends the session and asks for a reconnect through the switch. No physical evidence exists yet; the transport spike comes first. See [StoryGraph device checks](device-testing.md#storygraph-sync).

### Goodreads

Goodreads is built in 0.6.0 through its website session; see the [Goodreads plan](plan-20261008-goodreads-sync.md) and [contract](integrations.md#goodreads). Sign-in is Goodreads' own page in a WebView in the separate `goodreads` `androidx.webkit` profile, so its cookies never mix with StoryGraph's. Matching confirms ISBN search hits by the edition's `__NEXT_DATA__` ISBN-13 and keys every match to its work. The sync reads shelf and progress from `/review/user_works/<workId>`, keeps a shelved sibling edition, posts the percentage in whole steps of 5 counted from 0 (from 0.7.0), and at completion shelves Read and sets the finish date through the review editor's Server Action. A WAF challenge gets one hidden-WebView load and one retry; a six-hour WorkManager job renews the session. No physical evidence exists yet.

### Pagebound

Pagebound is built in 0.7.0 through its website's unofficial API; see the [Pagebound plan](plan-20261008-pagebound-sync.md) and [contract](integrations.md#pagebound). Sign-in is the email/password popup shared with Fable; only the Pagebound token and the Firebase refresh token are stored. Matching uses Pagebound's ISBN lookup, then catalogue title search. The sync adds or moves the book to Reading as a digital read, sets an empty edition, posts progress in whole steps of 5 with read-back, and marks the book Finished at completion. No physical evidence exists yet.

### UI, folder access, and logs

Sync/Activity, a book/progress/library-count/read-time header, a black Sync Now button, green check/amber warning, and bordered metadata/About popups are built. Margins shows Coming Soon. When the header warns, the book popup lists every current issue in full first, and each provider popup starts with its own issues; every ⚠ is drawn as the amber triangle. Match and delivery states are separate and keyed to the current book; last success survives a later failed attempt. Disabled services do not cause warnings.

Startup requires a persisted readable read-only SAF ebook-folder grant. It explains the purpose before Choose folder opens the picker. No grant produces Exit / Allow again after cancellation. Cancelling About's replacement preserves a readable old grant; missing/revoked grants still block access. Workers log missing access without opening UI or sending.

The popup merges database and ebook identifiers off the main thread. It shows full values, bold Title Case keys, ISBN/ASIN, explicit OK/error states, and distinct human-readable Last Access/Read At. Absent service tags are omitted. `progressProblem: null` in diagnostic JSON means no parse error; it is not unknown progress.

Activity uses recycled, fully tappable rows in plain words with grouped repeats, an All/Issues segmented filter, and a two-tab popup (Summary with bold labels, monospaced JSON formatted on first open). The event log is bounded (user decision, 2026-10-07): at most 1,000 events, none older than 30 days, and routine events from before the last successful sync removed after 48 hours; the count cap removes routine events first, and sync state is never pruned. Estimated size is about 2 MB after one month and after one year, against about 8 MB and 86 MB without the bound (see [verification](verification.md#activity-redesign-and-bounded-log-048)). The app's observation service, explicit permissions/actions, Read Now, diagnostic sections, and background toggle were removed in 0.3.0. The merged APK still inherits WorkManager's generic foreground-service declaration and scheduling permissions; current workers are not promoted to foreground work. See [permission details](research.md#android-references).

## Automatically tested

The 0.7.0 run on 2026-10-09 passed 276 unit tests with zero failures and empty stderr, both Android lint variants, both APK assemblies, ktlint, ruff, markdownlint, yamllint, actionlint, and packaging; details are in [verification](verification.md#pagebound-tracker-and-step-rule-070). The 0.6.0 run on 2026-10-08 passed 256 unit tests with zero failures, both Android lint variants, both APK assemblies, and ktlint; details are in [verification](verification.md#goodreads-tracker-060). The 0.4.8 reports contain **181 tests, zero failures/errors, and empty stderr**. Both APKs and both Android lint variants passed. ktlint, ruff, actionlint, and Markdown checks passed. Both required reviews returned findings; the applied ones have tests (see [verification](verification.md#activity-redesign-and-bounded-log-048)). Packaging verified signatures; delivered APK hashes were checked separately.

Tests exercise shipping provider, SQLite, SAF, UI, OAuth/connector, and worker paths with synthetic ebook metadata and a local HTTP server. They cover matching, edition fallback, history protection, multi-book/account queues, reopening storage, uncertain writes, revision races, offline toggles, folder gating/recreation, and Activity rendering. Fable tests use a local fake of the response shapes observed on 2026-10-06. They do not establish actual BOOX scheduling or production catalogue/API behavior. See [the evidence record](verification.md#fable-sync-040).

## Verified on physical BOOX

One device: ONYX GoColor7, Android 12/API 32, build `2026-05-19_23-44_4.2-rel_0519_c76f35ce8`, incremental `9661`. NeoReader version is not recorded. Do not generalize to all Go 7 devices/firmware.

| Case | Evidence |
| --- | --- |
| Ordinary-app provider access | 0.1.x exports: 786 records / 45 columns, raw progress/status and useful observer callbacks |
| Independent local scheduling | Historical app-hidden reads, including cold boot and wake; no exact cadence or regular sleep guarantee |
| Historical logs/Activity | Reboot/update/export retention and much faster scrolling reported on 0.1.2 |
| Native sign-in and manual exact sync | User confirmed on 0.3.1: Exact edition matched, Synced at…, progress reached Hardcover; In the Blood screenshot shows 240/480 pages, 50% |
| Current updates/UI | Signed 0.3.1–0.5.0 USB updates; startup explanation, merged metadata, and readable-grant picker cancellation checked. 0.4.4 opened and synced Hardcover once; 0.4.5 three-line rows and the icon tap checked; 0.4.6 edition notes, ⚠ rows, and the Fable kept value checked; 0.4.7 amber row triangle and the issue sections of the book, Hardcover, and Fable popups checked; 0.4.8 Activity tab and popup checked by the user |

The remote screenshots do not prove a selected edition ID, exact raw fraction, or mutation sequence. Those need an export. The 0.3.3 metadata screenshot is `dist/screenshots/boox-tracker-0.3.3-boox-metadata.png`. Future-service tags were absent from this book, so their rendering/rejection has automatic proof only. Data inode `129824` stayed unchanged across the new-package updates; this alone does not prove reboot queue retention.

The old `org.readingsync.diagnostic` package was absent from installed/known lists during the 0.3.0 handoff; no uninstall or data clear was issued. If it is present again, preserve its logs and disable old sends/checks/observation before running both apps. The 0.3.0 package was initially disabled with `lastDisabledCaller: com.onyx`; enabling it allowed launch. The responsible BOOX policy is unknown.

Foreground checks around 22:03–22:04 and 22:31–22:32 local time on 2026-10-06 are excluded from independent background evidence. Opening this app collects/sends automatically. Earlier diagnostic worker success does not prove the new network-constrained delivery path.

## Missing evidence and next work

- Validate Pagebound on the device: sign-in, the first sync, one post per step, completion, Log out, and an export without tokens; record each write's response.
- Validate Goodreads on the device: sign-in through the popup, the first sync, the 5-point step, a hidden renewal run, the finish date, Log out with StoryGraph still connected, and an export without cookies.
- Finish the StoryGraph transport spike (Sync Now after 35 minutes, two hours, a night, a reboot, and on another network) before any StoryGraph release; then an unshelved book, completion, the reopen hold, Log out, and the explicit tag.
- Validate Fable sign-in, sync, shelving, completion, and the first token refresh on the device.
- Validate hidden-app offline collection and delivery after reconnection; inspect events before app-open sends.
- Validate book-only fallback, using the supplied Savage Son case without catalogue edits.
- Validate queue retention through restart/reboot and real offline toggle/account behavior; automatic coverage already exists.
- Inspect a current export for matching/page-basis/delivery evidence. Keep private exports and ebook files outside Git.
- Continue ordinary use under recorded BOOX settings. Treat sleep/reboot gaps as evidence, without guessing their cause.

The Margins connector, rereads, additional embedded-format parsers, statistics-provider access, website, and updater are not implemented or decided. No ordered implementation plan exists for these beyond the current physical-validation checklist. Margins' inquiry was sent; no reply/access was reported as of 2026-10-05.

## Documentation map

| Document | Use |
| --- | --- |
| [Agent instructions](../AGENTS.md) | Durable guardrails, code map, commands |
| [Product](product.md) | Decisions in force and unresolved scope |
| [Design](design.md) | Mockup, actual UI, e-ink rules |
| [Integrations](integrations.md) / [provider research](research.md) | Dated findings, samples, licences, unknown contracts |
| [Verification](verification.md) / [device testing](device-testing.md) | Separate automatic/device proof and next protocol |
| [Build and release](build-and-release.md) | Tools, signing, packaging, CI, publication rules |
| [Pagebound plan](plan-20261008-pagebound-sync.md) | Latest approved scope, its notes, and its pending physical checks |
| [Goodreads plan](plan-20261008-goodreads-sync.md) | Approved scope, its notes, and its pending physical checks |
| [StoryGraph plan](plan-20261007-storygraph-sync.md) | Approved scope, its notes, and its pending physical checks |
| [Fable plan](plan-20261006-fable-sync.md) | Completed scope with pending physical checks |
| [Automatic/offline plan](plan-20261006-automatic-offline-sync.md) / [completion plan](plan-20261006-completion-sync.md) | Completed scopes with pending physical checks |
| [Diagnostic plan](plan-20261004-reading-sync-poc.md) / [first connector plan](plan-20261006-hardcover-first.md) | Historical approved scopes, superseded requirements |
