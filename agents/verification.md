# Verification record

Updated 2026-10-08 for Boox Tracker 0.6.0/code 25 (Goodreads), pushed at `1b52866` and installed on the GoColor7. Version sections record the evidence available at each handoff; a pending result in an older section is not the current status. Use the matrix below; the open checks and their order are in [project status](project-status.md#resume-here). No compatibility or scheduling guarantee follows from compilation, emulator rendering, local HTTP tests, ADB access, or one firmware result.

## Current evidence summary

| Category | Confirmed evidence | Limits / next proof |
| --- | --- | --- |
| Built | 0.5.3 debug/signed APKs: Hardcover with completion sync and duplicate-read handling, Fable through its unofficial app API with reading-streak days, StoryGraph through its website session with a WebView sign-in, sign-in popups, provider details popups with Log out, three-row provider rows with edition notes and a kept-progress warning, every current issue named in full at the top of the book and provider popups, a redesigned Activity tab with a bounded event log, a Syncing spinner on the Sync Now button, the Hardcover sign-in code in bold | Goodreads/Margins, rereads, website, updater and statistics are not implemented; StoryGraph's plain-client transport is unverified against Cloudflare |
| Automatically tested | 214 tests, zero failures/errors; both builds/lint variants, static checks and both reviews passed | Ten existing lint notices per variant; synthetic source/local HTTP do not prove firmware or production scheduling |
| Physical provider/background | Historical ordinary-UID reads, independent scheduled reads after boot/near wake, logs retained on GoColor7/API 32 firmware below | No exact cadence, regular sleep, repeat-boot, or wider-device guarantee; new delivery needs separate proof |
| Physical Hardcover | User confirmed native approval, exact matching, Synced at…, and remote 240/480 pages (50%) on 0.3.1 | Chosen edition/raw fraction/mutation sequence await export; book-only and hidden-app offline/reconnect remain pending |
| Physical StoryGraph | On 0.5.2 at 18:04 on 2026-10-08: WebView sign-in captured the session, the plain client passed Cloudflare, the ISBN confirmed an edition, the shelved hardcover received 52% (read back on StoryGraph as 52% / 239 pages), and the details popup showed the account and match; later syncs that evening, including one after a reboot and one at 19:17 about an hour and a half after sign-in, passed (user reports), so the session survives a reboot | A sync the next day, one from another country, an unshelved book, completion, the reopen hold, Log out, and hidden-app delivery remain open |
| Physical current UI/update | Signed 0.3.1–0.5.3 USB updates with the same data inode; prompt, merged identifiers, readable-grant cancellation checked | 0.4.x launch, the Hardcover and Fable sign-in popups, and Fable are not yet checked; future tags and revoked-grant/recreation cases have automatic evidence only; unchanged data inode is not queue-reboot proof |
| Source / publication | `main` pushed at `bb73e8e` (0.4.7); [Android checks passed](https://github.com/otherguy/boox-tracker/actions/runs/37609064626) for that commit on 2026-10-07; [Android checks passed](https://github.com/otherguy/boox-tracker/actions/runs/37650190310) for 0.4.8 at `92089e5`; 0.5.2 and 0.5.3 pushed on `main` at `dd4ef52` on 2026-10-08; [Android checks passed](https://github.com/otherguy/boox-tracker/actions/runs/37771908812) on a re-run after a first attempt that could not resolve `kotlin-stdlib` on the runner | No release or prerelease exists; one needs an explicit user request |

APK bytes/checksum and existing test reports were rechecked during the documentation refresh. No new app build, device operation, or remote workflow was performed for that docs-only work. Foreground checks around 22:03–22:04 and 22:31–22:32 on 2026-10-06 must be excluded from independent background evidence. Opening the app collects/sends automatically.

The merged diagnostic manifest was also inspected: WorkManager adds generic FOREGROUND_SERVICE/SystemForegroundService, WAKE_LOCK and RECEIVE_BOOT_COMPLETED. The source manifest has no observation service or explicit notification/foreground permission, and current code has no foreground-worker promotion. The removed observation feature does not imply that the final APK has no framework foreground-service declaration.

## Regression findings and pitfalls

### Source, identifiers, and display

- A null provider ISBN does not mean no EPUB ISBN exists. The shipping popup now reads the same merged repository as sync off the main thread.
- `progressProblem: null` means no fraction error; valid progress shows OK. Missing/unreadable/invalid progress stays explicit and never becomes 0%.
- Reader extraction is the single tag allowlist; UI labels do not add another. Tags for services without an integration are display-only. Fable matches `fable:` UUIDs, and Hardcover and Fable both use ASIN.
- Ebook cache namespace changes invalidate parsed metadata without deleting settings/logs. Application, database/export, and cache versions are separate.
- Stored lastAccess can lag book switches; provider/library percentages can differ from in-book display. No source selector or percentage offset resolves that uncertainty.
- When an ebook file is replaced at the same path, NeoReader keeps the old Metadata record and adds a new one with its own progress. On the GoColor7 on 2026-10-07, a rewrite that added 151 bytes left the old record at 5293/10000 and the new one at 5194/10000. Boox Tracker had already sent the old value, so both trackers kept it, and the app warned. This was correct behaviour, not a sync defect. The program that replaced the file is unknown.

### Queue, permissions, and lifecycle

- Persist per-account/service/book revisions before delivery. Acknowledge only the sent revision so newer observations survive; reconcile remote state after uncertain writes.
- APPEND_OR_REPLACE retains a delivery enqueue during worker completion. A global pending book or KEEP-only enqueue can lose required work.
- Offline waiting is neutral; On persists for connected accounts, Off pauses sends, and account identity failures cannot reuse another account's state.
- Explain folder choice before launching the picker. Cancelling replacement revalidates the existing grant; denial/revocation still gates. Activity-owned validation state prevents recreation from losing the prompt.
- Preserve current remote edition/history/page basis. A missing basis is a held update, not permission to guess pages or overwrite completed history.

### Test and device handling

- The app's startup coroutine recovers and prunes the event log on a background thread. `TestReadingSyncApp` finishes that recovery before a test starts; without it, `RetentionTest` raced the prune and failed on CI after 0.6.0 added a service to recovery (`1b52866`, `edc73dc`; fixed in `e85e8c5`). A screen test that taps a provider row waits until the app-open sync and the profile fetch stop rebuilding the rows; the Fable popup test timed out on CI once the Goodreads row moved its row down.
- Await rendered book state before UI taps; provider-call counts can advance before snapshot rendering. Use explicit latches/clock control, not arbitrary sleeps.
- Test denial must throw before cursor allocation. A throw inside cursor construction can leak a resource before production code receives it.
- Capture/assert expected Robolectric zero-resource-ID diagnostics; reject other stderr. Close test WorkManager/SQLite resources instead of suppressing warnings.
- Activity checks bound operation/row counts and state retention; reported faster scrolling is qualitative physical evidence, not a benchmark.
- BOOX XML dumps returned null roots while screenshots worked. Fresh package disabling by com.onyx was observed, but its cause is unknown. Installation/UI ADB use is not ordinary-UID provider proof.

## Goodreads tracker 0.6.0

**Built:** debug and signed diagnostic APKs, version 0.6.0/code 25, committed and pushed on `main` at `1b52866`. New dependency `androidx.webkit:webkit:1.17.1` (published 2026-09-23) for the separate `goodreads` WebView profile. Package, certificate (`678df89d…4b420d`), schemas, ebook cache namespace, and Keystore alias are unchanged. Artifact `dist/boox-tracker-0.6.0-diagnostic.apk`, 3,714,983 bytes, SHA-256 `80775c6bef99b523603da0eb258b9aa9179844d58e6c0acf5e117eb003308b0e`. The merged manifest has the same permissions as before: INTERNET, ACCESS_NETWORK_STATE, and WorkManager's WAKE_LOCK, RECEIVE_BOOT_COMPLETED, and FOREGROUND_SERVICE.

Goodreads is the fourth tracker, under the [Goodreads plan](plan-20261008-goodreads-sync.md) and the [contract](integrations.md#goodreads). Goodreads has no API for new apps; the user accepted driving its website through a browser session on 2026-10-08. Sign-in is Goodreads' own page in the bordered WebView popup, which StoryGraph now shares as `webSignInDialog`. Requests use `HttpURLConnection` with the Goodreads profile's cookies and the WebView's User-Agent. Deviations from the approved plan are in its Notes.

**API evidence, 2026-10-08:** the user signed in on Goodreads in the built-in browser and posted one progress update (page 240 of 480) on In the Blood. Read-only GET probes recorded the home page header and widget, the editions page with its exclusive shelf and prefilled progress form, the book page's `__NEXT_DATA__`, ISBN search, the review timeline and status list, and the review editor's flight data; reading the editor's chunk scripts found the `submitReviewFormAction` id. The EPUB's `goodreads:58438630` tag, its UK ISBN, and the Kindle ISBN NeoReader shows are three editions of work 91709220. No write was made by the app or the probes.

**Automatically tested:** 256 tests, zero failures/errors, empty stderr. `GoodreadsServer` is a `MockWebServer` fake built from sanitised excerpts of the observed pages, with a fake profile that keeps its own cookie jar and a fake hidden browser. The tests cover the parsers (including a flight row split across two pushes), matching by tag, ISBN-13, ISBN-10, the 200-with-Location search answer, works, title, and cache; shelving, the 5-point step, kept higher progress, a shelved sibling edition, conflicts and unknown shelves, completion with the finish date (including a date Goodreads stamped at the shelf write), a rejected date and an existing review that keep the Read shelf, unconfirmed writes, a challenge passed once and one that is not, renewal before a stale send, Log out leaving the default cookie store alone, the renewal worker, the sign-in popup's silent wait on a signed-out home page, an unsupported WebView, and the row and popup texts. Both lint variants, both assemblies, ktlint, ruff, actionlint, and markdownlint passed. The code-simplifier review made six behaviour-preserving changes (shared User-Agent constant, request defaults, a shared web sign-in status line). The code review returned thirteen findings; applied with tests: an editions page with shelf markup but no recognised row, and a page-only progress form, hold instead of reading "unshelved" or 0%; a value whose post Goodreads did not show is not posted again; the editor's reads come from the form's own `initialReadingSessions` with flight references resolved by read id, a referenced review counts as a review, and the date read-back requires every other read and the notes unchanged; a 202 without `x-amzn-waf-action` is not a challenge; the profile is created before the popup attaches it; a capture check stores no renewal time; the renewal job skips while Goodreads is Off; the popup allows Goodreads subdomains; a failed capture and Cancel have a screen test. Not changed: the review proposed a GET sign-out, but the recorded home page marks the link `data-method="POST"`; a later `already_current` result replacing a stored `finishDateError`, and a swallowed error in the editor read before the Read write, are recorded as known limits.

The host JVM's `HttpURLConnection` drops `Sec-Fetch-*` and `Origin` request headers, so tests check only the headers it keeps; Android's client sends them. Robolectric's cookie shadow does not enforce WebView's rule that `removeAllCookies` with a callback runs on a Looper thread; Chromium's `AwCookieManager` throws otherwise, so the Goodreads profile clears on the main thread. StoryGraph's `StoryGraphSession.clear()` called it from the I/O thread that Log out and Cancel run on, so StoryGraph Log out would have thrown before deleting anything; it now runs the removal on the main thread too. `LooperCheckingCookieManager` is a test shadow that enforces the rule, and a StoryGraph test logs out from an I/O thread: it failed with the shipping code and passes with the fix.

**Verified on physical BOOX:** the signed 0.6.0/code 25 APK, committed and pushed on `main` at `1b52866`, was installed over 0.5.3/code 24 by USB at 21:25 device time on 2026-10-08 with the data inode `129824` unchanged, the package not disabled, and no uninstall or data clear. Started by ADB, MainActivity resumed with no crash logged. The screen showed the Goodreads row available (Off, Not connected), StoryGraph, Hardcover, and Fable still On with "same edition" at 53.35%, and Margins Coming soon; that app-open is foreground evidence only. The [Goodreads device checks](device-testing.md#goodreads-sync) are open; the hidden-WebView challenge path and the renewal job have no device evidence. [Android checks](https://github.com/otherguy/boox-tracker/actions/runs/37792333565) for `1b52866` were still running at install time.

## Syncing spinner and bold Hardcover code 0.5.3

**Built:** debug and signed diagnostic APKs, version 0.5.3/code 24, pushed on `main` at `dd4ef52`. Package, certificate, schemas, ebook cache namespace, vault files, and Keystore alias are unchanged. Artifact `dist/boox-tracker-0.5.3-diagnostic.apk`, 3,596,018 bytes, SHA-256 `a4c8090bf3b3f4ebbe5e918ac89dec63b0c338f6a872d96ec5008241ec45f18f`. An earlier 0.5.3 build (SHA-256 `2077e252…`, installed at 18:35) wrapped the label and never started the spinner; the committed build replaces it.

While a sync runs, the Sync Now button reads "Syncing" with a twelve-spoke spinner that steps one spoke every 100 ms, at the wider of its two widths so the header never moves; by the user's decision on 2026-10-08 it is the one animated control (see [design](design.md#e-ink-rules)). The spinner lives in `SyncSpinner.kt` and starts as soon as the button is on screen. The Hardcover sign-in popup shows the code in bold.

**Automatically tested:** 214 tests, zero failures/errors/skips, empty stderr. New screen tests assert the busy state's label, disabled state, running spinner, one-line label and unchanged width, and that the replaced button's spinner stops; and that the Hardcover code carries a bold span while the instructions do not. The spinner test's running assertion was shown to fail without the attach fix. Both builds and lint variants passed with the ten pre-existing dependency notices per variant; ktlint and markdownlint passed.

**Verified on physical BOOX:** the signed 0.5.3/code 24 APK was installed over 0.5.2 by USB at 18:35 device time on 2026-10-08 and, after the two fixes, reinstalled at 18:39 with the data inode `129824` unchanged and no uninstall or data clear; each start by ADB ran with no crash logged. On the first build the mid-sync frame showed "Synci / ng" on two lines and a still spinner, which the user also reported. On the reinstalled build the frame 1.5 s after the app-open shows "Syncing" on one line with the spinner, at exactly the width of "Sync Now" in the frame after the sync (`dist/screenshots/boox-tracker-0.5.3-boox-syncing.png`); the sync finished within two seconds, so the stepping itself is not captured in frames. The same app-open syncs showed StoryGraph at "Book matched · same edition" after the edition switch on StoryGraph, with Hardcover, StoryGraph and Fable all at 52.93% from the 18:25 delivery. The bold Hardcover code has not been viewed on the device.

## StoryGraph tracker 0.5.0, popup keyboard 0.5.1, page attributes 0.5.2

**Built:** debug and signed diagnostic APKs, versions 0.5.0/code 21, 0.5.1/code 22 and 0.5.2/code 23, committed on `main` at `5088fca` and pushed with 0.5.3 at `dd4ef52`. 0.5.2 differs from 0.5.1 in three ways: the page parser reads bare and single-quoted attribute values, because StoryGraph writes `data-book-id` bare on its own blocks (the 0.5.0 fixtures came from the browser DOM, which had normalised them to quoted); confirmed editions that share a normalized title and author key count as one book, matched on the first in ISBN order, because an EPUB can carry a different ISBN of the same work than NeoReader shows; and the popup explanation is the user's text ("Sign in on StoryGraph's own website. Boox Tracker never sees or remembers your password; it keeps the browser session on this device."). Artifact `dist/boox-tracker-0.5.2-diagnostic.apk`, 3,593,850 bytes, SHA-256 `177584e1774213e3ec0590de4772f50fcf89b4775855d4e4e12448a0199f2e41`. Package, certificate, schemas, ebook cache namespace, vault files, and Keystore alias are unchanged. 0.5.1 differs from 0.5.0 by one line: the StoryGraph sign-in dialog clears `FLAG_ALT_FOCUSABLE_IM` after it is shown, because AppCompat sets that flag on a dialog whose custom view has no text editor at show time, and a WebView has none until its page focuses a field; with the flag the input method targets the activity window behind the popup and the page's fields cannot raise the keyboard. Artifact `dist/boox-tracker-0.5.1-diagnostic.apk`, 3,592,502 bytes, SHA-256 `046b7f691c1105b1db3315169cb22c65bb97afcb488bbecf7b028f91104bfa29`. The 0.5.0 artifact `dist/boox-tracker-0.5.0-diagnostic.apk`, 3,592,418 bytes, SHA-256:

```text
b95830e4fbc4f46e418c74d44bf5d470148115f079d2e0daeb753ba34dde9b15
```

StoryGraph is the third tracker, under the [StoryGraph plan](plan-20261007-storygraph-sync.md) and the [contract](integrations.md#storygraph). StoryGraph has no API; the user accepted driving its website through a browser session on 2026-10-07. Sign-in is StoryGraph's own page in a WebView inside the bordered popup, the cookies stay in the WebView cookie store, and `HttpURLConnection` requests carry them with the WebView's User-Agent. Matching confirms the fuzzy search's hits by the edition page's `ISBN/UID`; sync marks the book currently reading, writes the floored percentage, confirms by re-reading the page, or marks the book read. A Cloudflare challenge or a sign-in redirect marks the session: the row shows Reconnect required, the popup names the cause once, and Off then On reopens the WebView.

**API evidence, 2026-10-07:** the user signed in on app.thestorygraph.com in the built-in browser. Read-only probes recorded the sign-in form, the home page's user id and username, the fuzzy search fragment (an unknown ISBN returns an unrelated hit), a to-read, a read, and an unshelved book page whose sibling is shelved, and the editions page. With the user's approval, three writes went to their live read: shelve currently reading (200, a journal entry appeared), then progress 50% and 51% (both 200, read back from the hidden inputs as 50 and 51 percent with 230 and 234 derived pages). Marking a book read, sign-out, the paused and rereading transitions, cookie lifetimes, and whether Cloudflare accepts a non-browser client carrying the session cookies were not exercised. Every non-browser client tried from the Mac was challenged (`403`, `cf-mitigated: challenge`), even unauthenticated page loads with a browser User-Agent.

**Automatically tested:** 213 tests, zero failures/errors/skips, empty stderr (0.5.2 adds a parser test for bare `data-book-id` values and a matcher test for two ISBNs of one work, both written failing first; 0.5.1 extends the sign-in popup test to assert the dialog window carries no `FLAG_ALT_FOCUSABLE_IM`; that assertion failed on 0.5.0 with flag 131072 and passes on 0.5.1), including 30 new StoryGraph tests against a MockWebServer fake assembled from sanitised excerpts of the real pages. They cover the parsers; the matcher (explicit UUID, an ISBN hit confirmed by its edition page, a fuzzy hit rejected, ISBN-10 and ASIN editions, conflicting identifiers, title and author, the cache); the sync (shelve then write with the exact form fields and headers, kept higher, already current, to-read and paused shelved first, the shelved sibling edition, read, did-not-finish and rereading holds, completion, unconfirmed writes, unknown remote progress, source rules, account change, disabled service, expired session and challenge marking with the queued item kept, cookie write-back, server-side sign-out and log out, recovery); and the screen (the switch opens StoryGraph's page in the WebView and a captured session connects and syncs with no cookie or account in the export; a failed capture keeps the popup open and Cancel discards the page's session; a browser check shows Reconnect required with the issue named once, and Off then On reconnects and sends the queued update; the row's edition note, kept-progress warning, details popup and Log out). Both builds and lint variants passed with the ten pre-existing dependency notices per variant; ktlint, markdownlint, ruff and actionlint passed. Both required reviews ran. The simplification review's cleanups were applied: one transport, the origin derived from the session, set-based match evidence, shared parsers and shared test helpers. The code review's findings were fixed with tests: the WebView's User-Agent is read on the main thread and the capture runs detached so a busy screen cannot drop it; a reconnect-pending delivery repeats the stored session problem instead of masking it; unknown remote progress holds instead of reading as zero; at most three search hits are confirmed; two different status labels on one page hold; Cancel discards a session the page established; cookie removal flushes in its callback; only main-frame navigations off the origin are blocked. The emulator renders the StoryGraph row as a live three-line row (`dist/screenshots/boox-tracker-0.5.0-emulator-sync.png`).

**Verified on physical BOOX:** the signed 0.5.0/code 21 APK installed over 0.4.8 by USB at 17:25 device time on 2026-10-08 with the data inode `129824` unchanged; no uninstall or data clear was issued. Started by ADB at 17:25, the process ran with MainActivity resumed and no crash logged; that app-open is foreground evidence only. On 0.5.0 the user turned StoryGraph On: the popup opened StoryGraph's sign-in page, but tapping its email or password field did not raise the keyboard, and the page showed no Remember me control. `dumpsys window` showed the popup window with `ALT_FOCUSABLE_IM` and `dumpsys input_method` showed the activity's decor view as the served view, which is the cause recorded under Built. The signed 0.5.1/code 22 APK was then installed over 0.5.0 by USB at 17:37 device time with the data inode `129824` unchanged, again without an uninstall or data clear, and started by ADB with no crash logged (foreground evidence only). On 0.5.1 the popup raised the keyboard and the user signed in: the popup closed by itself and the session was captured. The first sync reached StoryGraph through the plain client (no challenge on `/`, `/search` or `/books/<uuid>`) and held with `storygraph_book_not_found`, which `dist/screenshots/boox-tracker-0.5.1-boox-storygraph-not-found.png` shows; the cause was the bare `data-book-id` attributes recorded under Built. The signed 0.5.2/code 23 APK was installed over 0.5.1 by USB at 18:04 device time with the data inode `129824` unchanged and started by ADB. Its app-open sync matched In the Blood by ISBN, found that edition unshelved, followed the "another edition" link to the user's shelved hardcover, and wrote 52% (NeoReader 52.23%) over StoryGraph's 51%. The row shows "✅ Book matched · different edition" and "Synced at 18:04 · 52.23%" with no warning (`boox-tracker-0.5.2-boox-sync.png`); the details popup shows `@otherguy`, Connected since Oct 8, 2026, Match "Exact edition, by its identifiers", Edition "The edition you shelved on StoryGraph, not the one your ebook matched", Progress 52%, Shelf Currently Reading, Last sync "Sent at 18:04" (`boox-tracker-0.5.2-boox-storygraph-details.png`). StoryGraph's own book page, read in the built-in browser, shows currently reading, `last_reached_percent` 52 and `last_reached_pages` 239 of 459. These are foreground results from an ADB-started app-open; they verify sign-in, transport, matching, the edition hop, and the progress write and read-back, not background delivery. The spike's later intervals, an unshelved book, completion, the reopen hold, Log out, and hidden-app delivery remain open. The transport spike gates everything else: sign in through the popup, then Sync Now after 35 minutes, two hours, a night, a reboot and on another Wi-Fi network without a browser check; see [device checks](device-testing.md#storygraph-sync).

## Activity redesign and bounded log 0.4.8

**Built:** debug and signed diagnostic APKs, version 0.4.8/code 20. Package, certificate, and SQLite/export schemas are unchanged; no migration was added. Artifact `dist/boox-tracker-0.4.8-diagnostic.apk`, 3,564,938 bytes, SHA-256:

```text
f7adf2654ad458ee5cbabd27b075236dec54a55db75291374905f0e266624732
```

Activity rows are fully tappable and read in plain words with a mark, the source, and the device-format time; repeats of one unchanged check or one issue share a row. A row opens a popup with Summary and monospaced JSON tabs of fixed height. The event log is bounded (user decision): 1,000 events, 30 days, and routine events from before the last successful sync after 48 hours, with routine events removed first at the cap. A worker run writes one `run` event, `*_sync_start` is gone, `queued` is written once per revision, and check events keep a six-field book summary unless the check is an issue. Exports keep only the newest file pair.

Size estimate from representative events built from the code paths (not measured on the device; the release build cannot be read over ADB, and `diskstats` did not list the package): a check run wrote about 2.4 KB (the check event alone 1.75 KB, of which the column list is 0.5 KB and the book record 0.7 KB). At 96 runs a day that is about 7 MB a month and 85 MB a year, and about 115 MB a year offline with two pending trackers. Bounded, the events stay near 0.6 MB (1.3 MB if every event were a sync result), plus about 0.7 MB of state, so about 2 MB after one month and after one year.

**Automatically tested:** 181 tests, zero failures/errors, empty stderr. New tests failed first: count and age caps, routine-first cap, sync-relative pruning and the 48-hour floor, state untouched, an unreadable row, the slim and the issue check event, the full check returned to the sync, one `run` event and the kept marker of a newer run, `queued` once per revision, pruning after a delivery, export cleanup, row text for every event kind, grouping across interleaved checks, the popup tabs, monospace, and fixed pane height. Both builds and lint variants, ktlint, ruff, actionlint, and markdownlint passed. Both required reviews ran. Applied: grouping that survives interleaved checks, guarded pruning at startup, the full record for issue checks, routine-first cap, keeping a newer run's marker, row keys with kind and run, "stopped" for cancelled runs, start and end visibility, a TalkBack word for each mark, and the simplifications. Not applied: debouncing list refreshes (Robolectric's paused looper does not advance delays), and protection against a wall clock that jumps forward.

**Emulator checked:** the debug APK on API 32 showed the segmented filter, grouped rows, and both popup tabs at the same size. Screenshots are `dist/screenshots/boox-tracker-0.4.8-activity.png`, `-activity-popup.png`, and `-activity-json.png`; the emulator has no NeoReader, so they show provider-unavailable rows only.

**Verified on physical BOOX:** signed 0.4.8 installed over 0.4.7 via USB at 18:15 on 2026-10-07 (code 20, data inode `129824` unchanged, no uninstall or data clear). The app started by ADB while the screen was dozing; its process ran and no crash was logged. The user then opened the Activity tab and its popup on the device and reported that they look right. Pruning has not been checked on the device. Retention evidence needs an export after the next successful sync: at most 1,000 observations and no `start`, `stop`, or `*_sync_start` events after it.

## Issues in popups 0.4.7

**Built:** debug and signed diagnostic APKs, version 0.4.7/code 19. Package, certificate, and schemas are unchanged. Artifact `dist/boox-tracker-0.4.7-diagnostic.apk`, 3,551,830 bytes, SHA-256:

```text
cf82be850325a0be1dc5f4c5ef5d76b1b45346ae128cd1414521aae269c9058c
```

Every warning now names its issue in full (user decision): the book popup lists every current issue first, and a provider popup lists its own just below its title. Each issue starts with the amber triangle; without an issue the section is absent. Every ⚠ on a row is drawn as the header's amber triangle, and the row text is unchanged. One issue list drives the header triangle and both popups.

**Automatically tested:** 148 tests, zero failures/errors. UI tests cover the drawn row triangle; two issues in the book popup and only Hardcover's in its popup; Fable's sign-in, streak, and kept-progress issues; a NeoReader permission failure and an undetected book; and no issue section without an issue. Each new expectation failed before the change. Both builds and lint variants, ktlint, and markdownlint passed. Both required reviews ran. Applied: the Fable kept text names the whole percent that Fable compared, so it cannot read "46%, more than 46%"; simpler issue code; one shared test helper; behaviour-based no-issue checks. Not applied: changing the sign-in popups, which have no ⚠.

**Verified on physical BOOX:** signed 0.4.7 installed over 0.4.6 via USB at 17:37 on 2026-10-07 (code 19, data inode `129824` unchanged, app not disabled, no uninstall or data clear); [CI passed](https://github.com/otherguy/boox-tracker/actions/runs/37609064626) for `bb73e8e` after the install. NeoReader was at 52.23% (5223/10000). Fable holds 52%, equal to the whole percent sent, so its row had no warning and its popup no issue section. Hardcover holds 254 of 480 pages, more than the 251 that 52.23% gives, so its sync line showed the amber triangle at text size without changing the row height. The book popup and the Hardcover popup each showed that one issue first with the amber triangle: "Hardcover has 254 of 480 pages; NeoReader's 52.23% is 251 pages. Boox Tracker does not lower progress on a tracker."

## Edition notes and kept-progress warning 0.4.6

**Built:** debug and signed diagnostic APKs, version 0.4.6/code 18. Package, certificate, and schemas are unchanged. Artifact `dist/boox-tracker-0.4.6-diagnostic.apk`, 3,550,962 bytes, SHA-256:

```text
426180200693bb350b07323a3676b2e4c96140e9778c3b49f394776f8fe20339
```

The match line now tells whether progress goes to the ebook's own edition (user decision): "✅ Book matched · same edition", "✅ Book matched · different edition", or "✅ Book matched" when the ebook's edition is unknown. "✅ Exact edition matched" is gone. Hardcover knows the ebook's edition from an exact identifier match; Fable knows it from an identifier match, and a shelved sibling edition is a different edition. Row and popup use one rule. The Hardcover popup now names "Another Hardcover edition; your ebook's edition has no page count" for that fallback, where it said "Your ebook's edition". When the latest sent update found the tracker ahead of NeoReader, the sync line starts with ⚠ and the header shows the amber triangle (user decision); the popup shows the kept value.

Two sync results gained edition facts. A finished book already Read on Hardcover now reports the exact source edition and the user's edition. A Fable book whose sibling edition is on Finished now names that sibling as the record, so it shows "different edition"; this changes no write, because that case returns "already current" or holds.

The local `dist/boox-tracker-0.4.5-diagnostic.apk` was overwritten by an interim build of this change that still carried version 0.4.5. The APK installed on the GoColor7 was pulled back over ADB and still matches the 0.4.5 hash recorded below.

**Automatically tested:** 147 tests, zero failures/errors/skips, empty stderr. UI tests cover "same edition" with no warning, "different edition" for a shelved Fable paperback with no warning, ⚠ and the header warning for kept Hardcover progress, and the popup's no-page-count edition text. Sync tests cover the edition facts for an already-Read Hardcover book and a Finished Fable sibling. Each new expectation failed before its change. Both builds and lint variants passed with zero lint errors and ten existing notices. Both required reviews ran. Applied: one edition rule for row and popup, the two sync results, stale doc wording, a test label, and removal of two checks for text the app no longer has. Not applied: showing the kept value on the row, by the user's decision to warn only.

**Verified on physical BOOX:** after [CI passed](https://github.com/otherguy/boox-tracker/actions/runs/37598390114) for `fe9c924`, signed 0.4.6 installed over 0.4.5 via USB at 16:12 on 2026-10-07 (code 18, data inode `129824` unchanged, app not disabled, no uninstall or data clear). Opened by ADB, the screen showed Hardcover "✅ Book matched · different edition" and Fable "✅ Book matched · same edition", both with "⚠ Synced at 14:01 · 51.66%", and the amber header triangle. The Fable popup showed "Progress: 52% · kept, higher than NeoReader": Fable holds 52% and NeoReader's 51.66% sends 51%. The rows still show the 14:01 results because the book has not changed since then.

## Provider rows and CI diagnostics 0.4.5

**Built:** debug and signed diagnostic APKs, version 0.4.5/code 17. Package, certificate, and schemas are unchanged. Artifact `dist/boox-tracker-0.4.5-diagnostic.apk`, 3,550,778 bytes, SHA-256:

```text
71d432b094d746144ab5f109af5e91bd751fdeb92dffcfe22c58beea3ea9585f
```

On the GoColor7, the Fable row showed "Pending matching" and then "Pending" on a separate line after the user changed the book's ISBN, so the row grew to four lines. Every implemented provider row now has exactly three lines (user decision): the name, one status line, and one sync line. Each line is a single ellipsized row. The status line is sign-in state, Off, a reconnect or held reason, a rejected streak day, the match, or "Not matched yet". The sync line is the sign-in hint, Off's reason, "Pending" or "Not sent" with the last sync, or "Synced at …" / "Not synced yet", with queued updates last. The whole row (icon, name, and status lines) opens the details popup (user request); before, only the text column did, the status line was selectable, and no part of the row accepted taps while a sync ran, including the sync that starts each time the app opens. The row now accepts taps during a sync and names its action for TalkBack.

On 0.4.4 the Hardcover row showed "⚠ Book matched" with the amber header warning after the user changed the ebook's ISBN to 9781982181680. The popup showed that the 14:01 sync matched that ISBN to an exact Hardcover edition and kept the different edition already on the user's Hardcover read. The user then switched their Hardcover edition; at 14:45 the website's page data showed the user book and read 7080312 both on Kindle edition 30462394 (ISBN 9781982181680, 480 pages, 254 pages read). The row stays on the 14:01 result because the app sends only when NeoReader's progress or the book's identifiers change. A book matched on another edition is now a success: "✅ Book matched · different edition", with no warning (user decision). When Hardcover or Fable keeps higher remote progress, the popup now shows the remote value; it showed NeoReader's lower 248 pages.

GitHub CI failed on `a72c541` with a 10-second timeout in the Hardcover details-popup test, twice on the same runner image. The test passed locally with UTC and C locale, CPU load, four visible processors, and a forced class order. An emulated x86-64 Linux container failed unrelated socket shutdowns, so it could not reproduce CI. The code review found a race: the test waited for stored account details, then tapped a row drawn while the app-open sync still ran, and that row had no tap handler. A 300 ms delay on the fake account-details response reproduced the same timeout every time; what slows that step on CI is not known. The row now accepts taps during a sync, which removes the race. The test names the wait that times out and prints the rows, stored state, popup, and requests, and CI uploads test results after a failed run, so a different cause would show in the next failed run.

**Automatically tested:** 145 tests, zero failures/errors/skips, empty stderr, in three full runs. Fable UI tests assert the three lines for a book that is not matched yet and for a synced book, and open the popup by touching the icon and a status line through the window. The Hardcover popup test asserts the new match text, no warning, and kept remote progress. A new test holds the sync's last request, taps the Hardcover row while the screen is busy, and expects the popup; it failed before the fix. Both builds and lint variants passed with zero lint errors and ten existing notices. Both required reviews ran twice. Applied: the whole-row tap, last sent time and sign-in problem in the popup, the code-request text, the switch description, the during-sync tap fix with its test, the TalkBack action name, a fast failure in the test tap helper, stale amber wording, and the simplifications. Not applied: showing the kept remote value on the row, because the row reports the NeoReader value that was read and the popup explains the kept value.

**Verified on physical BOOX:** the 0.4.4 screenshots above were taken by ADB on 2026-10-07 at 14:43; opening the app sent nothing because the book was unchanged. After [CI passed](https://github.com/otherguy/boox-tracker/actions/runs/37592716492) for `d8cf6f7`, signed 0.4.5 installed over 0.4.4 via USB at 15:42 (code 17, data inode `129824` unchanged, app not disabled, no uninstall or data clear). Opened by ADB, the screen showed three-line rows, Hardcover "✅ Book matched · different edition" with "Synced at 14:01 · 51.66%", Fable "✅ Exact edition matched", and the green header check. A tap on the Hardcover icon opened the popup with "Progress: 254 of 480 pages · kept, higher than NeoReader".

## Hardcover duplicate reads 0.4.4

**Built:** debug and signed diagnostic APKs, version 0.4.4/code 16. Package, certificate, and schemas are unchanged. Artifact `dist/boox-tracker-0.4.4-diagnostic.apk`, 3,549,982 bytes, SHA-256:

```text
77f2d13610d25f9acb7bc694bbc805b75b6e9956350b1d8894471960d35ff59f
```

On the GoColor7, 0.4.3 showed `hardcover_read_history_conflict` for In the Blood. The user's logged-in Hardcover page showed two reads on edition 33373487: 7080312 (started 2026-10-06, 0 pages) and 7080314 (no dates, 240 pages). Hardcover creates a dated read when a book becomes Currently Reading; the first sync on 2026-10-06 then inserted its own undated read, so every later sync held. The sync now re-reads the reads after it adds a book or moves it to Currently Reading and advances Hardcover's read instead of inserting one. Two open reads on one edition with exactly one dated are treated as that pair: progress goes to the dated read, the higher progress of both is kept, and the undated read is left unchanged. A failed finish on the pair resumes with only the status update.

**Automatically tested:** 144 tests, zero failures/errors/skips, empty stderr. New tests cover a new book and a Want to Read book advancing Hardcover's read, the pair advancing the dated read and keeping higher progress, finishing with the pair and resuming a failed finish, a new finished book, two created reads holding, a percentage-only read in the pair holding, and other multi-read histories holding. Both builds and lint variants passed with zero lint errors. Both required reviews ran; the code review's stuck-finish and percentage-only findings were fixed with tests, and the simplification review's cleanups were applied.

**Verified on physical BOOX and Hardcover:** 0.4.4 installed over 0.4.3 via USB on 2026-10-07 at 12:56 local time (code 16, data inode `129824` unchanged, app not disabled, no uninstall or data clear). Opening the app synced In the Blood at 52.93%: the row showed "Exact edition matched · Synced at 12:56", and Hardcover then showed read 7080312 at 254 pages (52.92%) with its start date kept and read 7080314 unchanged at 240 pages. The user then deleted the undated read on Hardcover; the page afterwards showed only read 7080312 at 254 pages.

## Provider details popup 0.4.3

**Built:** debug and signed diagnostic APKs, version 0.4.3/code 15. Package, certificate, and schemas are unchanged. Artifact `dist/boox-tracker-0.4.3-diagnostic.apk`, 3,548,262 bytes, SHA-256:

```text
fd0495c7da270fa9cbaf1084c91c6c89eb6c73e1ac0329145a21b20bf5885dbb
```

Tapping a provider row opens its details popup: account (username and name, email for Fable, connected since, account created, membership), Sync On/Off, queued updates, and the current book's match, edition, progress, Fable shelf and streak day, NeoReader progress, and last sync. Account details are fetched in the background after sign-in, or after the next delivery for older sign-ins, and stored in the state table only; they never reach events or exports. Log out confirms once, then removes the session, turns the provider Off, and deletes its queued updates, book results, match cache, and account details in one transaction. Rows no longer show the edition line; Fable no longer warns for the match kind, and its `matchKind` now reports the match itself. Hardcover keeps "⚠ Book matched" with the amber warning. The unused per-service `status` state value was removed.

**Automatically tested:** 135 tests, zero failures/errors/skips, empty stderr. New tests cover account details at sign-in and on a later delivery for both providers, a failed account-details fetch that still delivers, Log out deleting only that provider's data, the Fable popup and Log out confirmation end to end, the popup before a first successful update, and the Hardcover popup for a kept edition. Both builds and lint variants passed with zero lint errors. Both required reviews ran. The code review found five issues, fixed with tests: invented match text before a first success, contradictory Hardcover edition text, sign-in waiting for the profile fetch, an old account's details surviving a new sign-in, and prefix deletes for exact keys. The simplification review's cleanups were applied.

**Verified on physical BOOX:** the signed 0.4.3 update installed over 0.4.1 via USB on 2026-10-07 at 12:36 local time; package metadata reports code 15/version 0.4.3, data inode `129824` unchanged, app not disabled, no uninstall or data clear. 0.4.2 was never installed. The app was not opened, so the popups, account queries, and streak write are not yet checked on the device.

## Fable reading streak 0.4.2

**Built:** debug and signed diagnostic APKs, version 0.4.2/code 14, committed as `4a48219`, not installed. Package, certificate, schemas, and vault files are unchanged. Artifact `dist/boox-tracker-0.4.2-diagnostic.apk`, 3,542,438 bytes, SHA-256:

```text
30b2dfd0989684ac474bb93a1f2b352741f338bd8bf6eee89ee04dc60a74c9a0
```

When a Fable send raises progress, it first marks the reading day on Fable's streak with the target book; see the [contract](integrations.md#reading-streak-verified-2026-10-07). The day is the device-local date of NeoReader's last saved access, else the queued read time. A rejected streak call (any non-temporary HTTP error) is recorded as `streakError`, warns on the row and header, and does not block progress; temporary failures retry the whole send. The finish-date logic moved into a shared `readingDay` helper used by Hardcover and Fable, with unchanged results.

**API evidence, 2026-10-07:** with the user's approval, three test writes targeted today, which the user had already marked in the Fable app: the first response was lost to a test-script bug, a write without `book_ids` was rejected with 400, and a write with `book_ids` returned 201 without a second entry or a progress change. Marking an unmarked day, a past date, or a different book on a marked day is not verified.

**Automatically tested:** 129 tests, zero failures/errors/skips, empty stderr. New tests cover the streak write before progress with the right date, weekday and book; no streak write for equal or higher remote progress; the sibling edition as the streak book; the read-time fallback; 400 and 403 streak rejections that still send progress; and a temporary streak failure that retries before any progress write. Both builds and lint variants passed with zero lint errors. Both required reviews ran; their findings were fixed with tests. At the user's choice, a rejected streak day on an enabled Fable shows the amber header warning and "Streak day not marked" with the reason on the row; a UI test covers it through the app-open sync.

**Verified on physical BOOX:** nothing yet.

## Sign-in popups 0.4.1

**Built:** debug and signed diagnostic APKs, version 0.4.1/code 13. Package, certificate, schemas, ebook cache namespace, and vault files are unchanged. Artifact `dist/boox-tracker-0.4.1-diagnostic.apk`, 3,541,058 bytes, SHA-256:

```text
feb971d748125caeec5159f49f502da45afcb06508afa3cd9281a9422bb68854
```

Hardcover and Fable sign-in moved from inline sections below the service rows into bordered popups, at the user's request on 2026-10-07. Cancel or Back cancels sign-in and turns the switch Off; outside taps do nothing. The popups are built once and updated in place across screen updates. A rejected Fable email or password now keeps the popup open with the reason instead of returning Off. Hardcover connection failures still close the popup and return Off with a message on the row. The Hardcover code is selectable so it can be copied into a browser on the device. A cancelled popup stays referenced until its connection stops signing in, so a screen update cannot reopen it during the cancel.

**Automatically tested:** 124 tests, zero failures/errors/skips, empty stderr. New and rewritten tests cover the Hardcover code popup, its Cancel, Back, leaving and returning to the app, and screen recreation; the Fable popup surviving a screen update with typed text intact; a rejected sign-in keeping the popup open; and Cancel turning Fable Off. Both builds and lint variants passed with zero lint errors and ten dependency/tool notices per variant; ktlint and markdownlint passed. Both required reviews ran. The simplification review's cleanups and the code review's three findings (a short window where Cancel could reopen a popup, a non-selectable Hardcover code, and a Fable test that skipped its screen update) were fixed with tests before the final build.

**Verified on physical BOOX:** a first 0.4.1 build installed over 0.4.0 at 11:22 on 2026-10-07; the final build above reinstalled over it by USB at 11:37. Package metadata reports code 13/version 0.4.1, data inode `129824` unchanged, app not disabled. No uninstall or data clear. The app was not opened; the popups are not yet checked on the device.

## Fable sync 0.4.0

**Built:** debug and signed diagnostic APKs, version 0.4.0/code 12, committed on `main` as `0b435be` with docs in `84f3c0a`, and pushed on 2026-10-07. [Android checks passed](https://github.com/otherguy/boox-tracker/actions/runs/37569442407) for `84f3c0a`. Package, diagnostic certificate (`678df89d…f4b420d`), app database/export schemas, ebook cache namespace, Hardcover vault file and Keystore alias, and OAuth client ID are unchanged. Artifact `dist/boox-tracker-0.4.0-diagnostic.apk`, 3,538,746 bytes, SHA-256:

```text
6154bfce8cfddcad667bed4d2eeb4a5dbccb69a3a807e1ee5fcd735180081679
```

Fable is the second tracker; see the [Fable plan](plan-20261006-fable-sync.md) and [contract](integrations.md#fable). The queue, delivery loop, and stored state moved to a shared `TrackerConnection`; Hardcover keys and event kinds are unchanged. `sync()` and the delivery worker isolate each service. A manual or scheduled send failure is now recorded as `<service>_sync` instead of `hardcover_operation`/`worker_failed`, and a scheduled run with such a failure stops as `failed`.

**API evidence, 2026-10-06:** with the user's browser session, read-only calls confirmed profile, search, book detail, editions, list membership, and progress read-back. User-authorized writes on In the Blood established: decimal percentages fail with 400; an integer write returns 201 and is read back; writes do not shelve; lower values are accepted; 100% moves the book to Finished; a later 50% write keeps Finished. A multiselect move off Finished returned 200 while book detail still read Finished seconds later. On 2026-10-07 list membership and book detail both showed Currently Reading at 50%, so the move had applied and detail status lagged. The UK sibling has a 0% record on no list. Firebase sign-in and refresh were not exercised.

**Automatically tested:** 121 tests, zero failures/errors/skips, empty stderr, including 36 new Fable tests against a local fake of the observed response shapes. Both builds and lint variants passed; lint has zero errors and ten dependency/tool notices per variant. ktlint and markdownlint passed. The code-reviewer and code-simplifier reviews both returned findings; all were applied with tests, as listed in the plan notes.

**Verified on physical BOOX:** the signed 0.4.0 update installed over 0.3.4 via USB on 2026-10-07 at 11:01 local time. Package metadata reports code 12/version 0.4.0, the same data directory inode `129824`, and the app is not disabled. No uninstall or data clear was issued. Launch, Fable sign-in, and sync are not yet checked; see [Fable device checks](device-testing.md#fable-sync).

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

The update checks opened the app around 22:03–22:04 local time. Those foreground reads are not hidden-app evidence. Prior user-confirmed manual progress delivery remains separate; book-only fallback and offline hidden-app reconnect delivery are still pending in [the device checks](device-testing.md). Releases/prereleases remain on hold.

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

Earlier ordinary-app provider/scheduled-read evidence below applies to the recorded GoColor7 firmware only. Real native sign-in, exact/book-only progress delivery, reboot queue retention, and hidden-app offline/reconnect work remain pending at this 0.3.0 handoff; later results are recorded above. The current device checklist is in [device testing](device-testing.md). If the old app is restored, disable its Background checks/tracker/observation before enabling it alongside the new installation; keep its logs. Opening 0.3.0 automatically collects/sends, so only pre-app-open scheduled/delivery events establish independent execution.

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

This section records the historical 0.1.2 diagnostic build. The current artifact is in [project status](project-status.md).

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

Later pushes also passed: `2b294dc` (0.3.4 handoff docs) on 2026-10-06 and `84f3c0a` (0.4.0 Fable) [on 2026-10-07](https://github.com/otherguy/boox-tracker/actions/runs/37569442407).

No release or prerelease has been created. The manual workflow and [signing instructions](build-and-release.md#github-delivery) remain a future path, not authorization to dispatch it.
