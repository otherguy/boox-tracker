# Device-test handoff

The [public guide](../docs/device-testing.md) gives normal setup; this file records personal test context, protocols, and recorded results. The installed build and the order of open checks are in [project status](project-status.md#resume-here). Report **Built**, **Automatically tested**, and **Verified on physical BOOX** separately.

## Recorded Hardcover results

Signed updates from 0.3.1 through 0.4.3 were installed over each other on the GoColor7 by USB with the same certificate and data directory. Hardcover stayed On and connected through 0.3.4. Startup explanation, full ISBN/ASIN/Goodreads popup values, bold labels, OK states, readable dates, and About → Change ebook folder → Back have physical evidence. No folder grant/account approval was changed during the latter checks. Future-service tags were absent, so those have automatic coverage only.

The user confirmed native approval, Exact edition matched, Synced at…, and progress reaching Hardcover on 0.3.1. Their In the Blood screenshots show Currently Reading, 240/480 pages (50%). Preserve this completed manual result. Actual edition ID, raw source fraction, and mutation sequence need an export; the 480-page display cannot prove the edition chosen.

The open checks and their order are in [project status](project-status.md#resume-here).

Update checks opened Boox Tracker around 22:03–22:04 and 22:31–22:32 local time on 2026-10-06. Exclude these foreground intervals from hidden-app proof. The 0.4.0, 0.4.1, and 0.4.3 installs at 11:01, 11:22, 11:37, and 12:36 on 2026-10-07 did not open the app. No offline/reconnect export from the current package has been inspected yet.

## Completion sync

1. Finish a book whose Hardcover record is Currently Reading or absent. Return to the NeoReader library so status `2` and the full fraction persist.
2. Sync and inspect the export: one `hardcover_sync` event with `finished: true`, `finishedAt` equal to the local last-access date, a read mutation with `finished_at`, then a status update to 3. Note the `finished_at` format Hardcover returns in the Library query; the app only checks that it is non-null.
3. On Hardcover, confirm exactly one read entry with that finish date and no duplicate read. This is the only evidence that inserting a read with `finished_at` does not duplicate a read Hardcover creates on its own.
4. Reopen the finished book and page backwards. If NeoReader returns it to status `1`, the next sync must hold with a history conflict and leave the finished read unchanged.

## Fable sync

Fable is built in 0.4.0 through Fable's unofficial app API; see the [Fable plan](plan-20261006-fable-sync.md). In the Blood is on Currently Reading at 50% (checked 2026-10-07). The UK paperback sibling has a stray 0% progress record and is on no list.

1. Turn Fable On, enter the Fable email and password, and press Sign in. Export and confirm the absence of the password, `idToken`, and refresh token. This is the first live check of the Firebase sign-in call.
2. With In the Blood at 50.07% in NeoReader, Sync Now. Expect 50% on the US ebook edition the user shelved (`existingEditionPreserved` if the matched ISBN is another edition), Currently Reading, and no change to the UK sibling.
3. Remove a test book from the Fable library on fable.co, read it in NeoReader, and sync. Expect Currently Reading plus progress. Adding an unshelved book through the multiselect call is not yet verified.
4. Finish a book in NeoReader. Expect 100% and Finished on Fable. Reopen it in NeoReader; the next sync must hold with `fable_status_conflict`.
5. Repeat the offline hidden-app test below with Fable and Hardcover both On. Each service must deliver its own queue.
6. More than one hour after sign-in, sync again. The ID token must refresh without a new sign-in. This is the first live check of the refresh call.
7. Tap the Fable and Hardcover rows. Check the account details (Hardcover's first delivery after updating fills them for the existing sign-in), the current book's match and edition, and Log out's confirmation text. Do not confirm Log out unless you plan to sign in again: it deletes that provider's queued updates.

## Offline collection and hidden-app delivery

Start with the existing BOOX settings and an awake test. There is no background toggle or observation session in 0.3.x. Keep Hardcover On; do not force-stop Boox Tracker. Record Wi-Fi, app-open, book exit, sleep/wake, and boot times. All times in previous records are local UTC+07:00.

### Establish queued data

1. Disable Wi-Fi, read several pages in NeoReader, and return to its library so saved metadata can update.
2. For a deterministic foreground queue test, open Boox Tracker and press Sync Now offline. It should show Pending without asking for Wi-Fi. This proves foreground collection only.
3. Read a second book offline and collect it. The first book's pending item must remain. Restart the app and later reboot normally to check retention without uninstalling/clearing data.

### Establish independent background execution

1. With Wi-Fi off, leave Boox Tracker hidden and read in NeoReader. Begin with an awake 30–60-minute interval. A longer wait may be needed; this is a test window, not a scheduling promise.
2. Turn Wi-Fi on **without opening Boox Tracker**. Check remote progress from another device before touching the app. Record the reconnection time.
3. Then open Boox Tracker and export Activity. Opening automatically collects/sends; inspect earlier scheduled and delivery timestamps plus start/finish visibility flags. A new app-open send cannot prove hidden-app delivery.
4. Separate local collection from delivery: look for a persisted observation/queued item while offline, then a network delivery result. Old diagnostic worker success proves neither current queued delivery nor its timing.
5. If nothing ran, retain the logs/settings/times and diagnose the recorded failure or gap. Do not conclude that a BOOX setting caused it without evidence, or clear data to retry.

### Interpret the result

Pending offline is normal. Authentication, provider, folder, match, and page-basis failures are distinct issues. A queued update can remain held after reconnection if matching/progress protections reject it. Verify both the selected source book and the delivery result.

Sleep and power-off are different states. Queue retention across reboot does not prove immediate post-boot delivery. No mid-sleep event does not identify the reason for a delay. Keep exact gaps; do not assume fifteen-minute execution.

## Book-only fallback

The supplied Savage Son EPUB contains ISBN `9781471197376`, Goodreads `58895717`, and Amazon value `1471197379`. In read-only API research, that ISBN/Goodreads pair had no match. The separately supplied ASIN `B07THCSQ27` matched book `484869`, ebook edition `31807724`, 429 pages. Do not substitute that ASIN into the source file to claim the untouched EPUB matched exactly.

Use the untouched source to test unique title/author fallback. A book-only result should show the amber "⚠ Book matched" row, with the edition and page basis in the row's details popup. No selection dialog or catalogue edit is allowed. If the source has different metadata on BOOX, record it and use the actual identifiers when interpreting the result. See [integration samples](integrations.md#local-matching-samples-2026-10-06).

## BOOX settings and USB pitfalls

Firmware labels vary. Record app freeze/auto-freeze, App Startup, background restrictions, and battery settings for Boox Tracker. Change one setting only if the evidence calls for it. These controls can affect execution but do not guarantee it. The current app has no observation notification or notification permission to enable.

The 0.3.0 package was initially disabled with `lastDisabledCaller: com.onyx`. Enabling it allowed launch; the policy that disabled it is unknown. The old package was absent at that handoff. If restored, preserve its logs and stop its checks/observation/sends before enabling both apps.

ADB is for authorized installation/package/UI checks, not provider-access proof. Explicitly target the physical serial when an emulator is also attached. BOOX UI XML automation returned null roots despite normal wake and resumed Activity; screenshots were usable. Do not infer screen state from a failed XML dump or repeat it indefinitely. See [USB delivery](build-and-release.md#usb-installation).

## Historical diagnostic protocols

The 0.1.x diagnostic milestone is complete for the recorded cases. Its foreground observation service, Read now, and Background checks toggle were removed in 0.3.0. Do not require those controls in current tests. The [diagnostic plan](plan-20261004-reading-sync-poc.md) preserves the original scope.

The controlled protocol compared observations while reading, library exit, font change, a second/finished book, screen-off/wake, independent scheduled reads, and retained export. Observer and polling results were separate from scheduled work. Raw progress/status and timestamps were preserved. Those results established saved-state access, not a live page-turn signal.

## Remaining device checks

This heading is retained for historical links. It refers to completed 0.1.x evidence and optional additional diagnostics, not the current integration checklist above.

| Completed case | Recorded result |
| --- | --- |
| 0.1.1 cold boot, 2026-10-05 | Scheduled reads at 10:48:03 and 11:06:50 before app-open at 11:11; hidden throughout, observation Off |
| Follow-up saved progress | 49.66% found at 11:46 with last access 11:10:39; manual 19:55 confirmation |
| 0.1.2 update | User reported much faster Activity scrolling and retained logs |
| 0.1.2 sleep/wake | Cover close 21:50, wake 22:25, NeoReader until 22:45; reads near close/wake and 22:46 at 50.07%, no middle-of-sleep read |
| Retained export | 283 events / 211 successful queries, seven additional complete independent jobs |

These intervals already contain positive evidence. Do not repeat them to extend 35 minutes to an earlier requested 45. The unfinished 12:11 job has no known termination/power-off time. Regular sleep execution, repeated-boot reliability, and wider firmware compatibility remain unknown. See [verification](verification.md#verified-on-physical-boox).

## First Hardcover build: 2026-10-06

The historical 0.2.0 protocol used optional EPUB access, exact ISBN matching, Read now, and Background checks with observation Off. It is superseded by the [automatic/offline plan](plan-20261006-automatic-offline-sync.md). Native sign-in/manual exact delivery now have user evidence on 0.3.1; book-only and hidden-app delivery remain current tests. No GitHub publication is authorized. Never commit tokens, consent codes, private exports, or ebook contents.
