# Device-test handoff

The [public guide](../docs/device-testing.md) gives normal setup; this file records personal test context, protocols, and recorded results. The installed build and the order of open checks are in [project status](project-status.md#resume-here). Report **Built**, **Automatically tested**, and **Verified on physical BOOX** separately.

## Recorded Hardcover results

Signed updates from 0.3.1 through 0.4.6 were installed over each other on the GoColor7 by USB with the same certificate and data directory. Hardcover stayed On and connected through 0.3.4. Startup explanation, full ISBN/ASIN/Goodreads popup values, bold labels, OK states, readable dates, and About → Change ebook folder → Back have physical evidence. No folder grant/account approval was changed during the latter checks. Future-service tags were absent, so those have automatic coverage only.

The user confirmed native approval, Exact edition matched, Synced at…, and progress reaching Hardcover on 0.3.1. Their In the Blood screenshots show Currently Reading, 240/480 pages (50%). Preserve this completed manual result. Actual edition ID, raw source fraction, and mutation sequence need an export; the 480-page display cannot prove the edition chosen.

The open checks and their order are in [project status](project-status.md#resume-here).

Update checks opened Boox Tracker around 22:03–22:04 and 22:31–22:32 local time on 2026-10-06. Exclude these foreground intervals from hidden-app proof. The 0.4.0, 0.4.1, and 0.4.3 installs at 11:01, 11:22, 11:37, and 12:36 on 2026-10-07 did not open the app. 0.4.4 was opened by ADB at 12:56 to test the duplicate-read fix and at 14:43 to read the Hardcover popup; 0.4.5 was opened by ADB at 15:42 and 0.4.6 at 16:12 after their installs; 0.5.0 was installed and opened by ADB at 17:25 on 2026-10-08, 0.5.1 at 17:37 after the keyboard fix, 0.5.2 at 18:04, and 0.5.3 at 18:35 and again at 18:39 after the spinner fixes. These are foreground evidence only. No offline/reconnect export from the current package has been inspected yet.

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

## StoryGraph sync

StoryGraph is built in 0.5.0 through its website session; see the [StoryGraph plan](plan-20261007-storygraph-sync.md). In the Blood is currently reading at 51% on StoryGraph (written through the user's browser session on 2026-10-07, matching the BOOX). The device sign-in on 0.5.1 captured the session, and the first sync passed Cloudflare with the plain client; it then held with `storygraph_book_not_found` because the parser rejected the page's bare `data-book-id` attributes (fixed in 0.5.2, with sibling editions of one work now accepted). On 0.5.0 the popup opened StoryGraph's page but its fields could not raise the keyboard: the dialog window carried `FLAG_ALT_FOCUSABLE_IM`, which AppCompat sets when the custom view has no text editor at show time, so the input method targeted the activity window behind the popup. 0.5.1 clears the flag after showing the dialog.

1. Done on 2026-10-08 (0.5.1): StoryGraph On, sign-in on StoryGraph's page in the popup, popup closed by itself, `@otherguy` in the details popup. The page shows no Remember me control; how long the session lasts is part of the spike. The popup must close by itself; the details popup must show `@username`; Activity must show the connection event. Export and confirm the absence of any cookie value and the user id.
2. Done on 2026-10-08 (0.5.2 app-open sync): In the Blood matched by ISBN, the shelved hardcover received 52% over StoryGraph's 51%, row "Book matched · different edition", StoryGraph page read back 52% / 239 pages. At 18:19 the read was switched to the Kindle edition on StoryGraph (its `/switch-editions` form, through the browser session); a Sync Now at 18:20 sent nothing because the source state had not changed, so the row kept the 18:04 result. The 18:25 delivery after the next page turn showed "same edition" at 52.93%.
3. Transport spike: Sync Now after 35 minutes, two hours, the next day, after a reboot, and on another network. Each must record no `storygraph_browser_check_required`. Done on 2026-10-08: the app-open syncs at 18:04–18:39 (35 minutes and more after the sign-in), a manual Sync Now later that evening at 52.93%, a sync after a reboot, which also shows that the session survives a reboot, and a manual sync at 19:17 device time, about an hour and a half after the sign-in (user reports). Open: a sync later in the night or the next day, and one from another country through the user's travel router (same SSID, different public IP; the IP is what matters). A challenge on any of them stops further StoryGraph work until the transport is re-planned.
4. Read an unshelved book in NeoReader and sync. Expect currently reading plus the floored percentage, read back from the page.
5. Finish a book in NeoReader. Expect the book marked read with that day's date. Reopen it; the next sync must hold with `storygraph_status_conflict`.
6. With a live session, turn StoryGraph Off and On: no popup should stay open (the page redirects home and the popup closes). Cancel on a fresh popup must turn StoryGraph Off. Log out must show the sign-in form on the next On.
7. Repeat the offline hidden-app test below with all three services On. Each service must deliver its own queue.
8. Explicit `storygraph:` tag: add the shelved edition's UUID to the EPUB in calibre, send it to the device over the same path, open it in NeoReader, and sync. The book popup must show a StoryGraph row and the StoryGraph row must read "same edition". The user deferred this on 2026-10-08; the tag path has automatic coverage only. The device's EPUB is calibre's current export (title "Terminal List #05 – In the Blood", ISBN 9781982181680, ASIN B09841ZBHY), not the older export that was in the repository folder.

## Goodreads sync

Goodreads is built in 0.6.0 through its website session; see the [Goodreads plan](plan-20261008-goodreads-sync.md). In the Blood is on Currently Reading on Goodreads on the Kindle edition 58467253 (ISBN 9781982181680), with one update at page 240 of 480 (50%) that the user posted on 2026-10-08. The UK paperback 60174472 and the hardcover 58438630 (the older EPUB export's `goodreads:` tag) belong to the same work 91709220. No challenge was seen during the research probes.

1. Done on 2026-10-08 (0.6.1): sign-in with email in the popup, popup closed by itself, row "Book matched · same edition", details popup with the account. Goodreads On: the popup must show Goodreads' sign-in page; sign in with email or Amazon. The popup must close by itself; the details popup must show the name and `@username`; Activity must show the connection event. Then confirm that StoryGraph is still connected.
2. First half done on 2026-10-08 (0.6.1, 23:20): at 53.35% the details popup showed 50% held with "the next update is sent at 55%" and no post. Open: read past 55% and check one new post. From 0.7.0 the post is the multiple of 5 below NeoReader's percentage, not the floored percentage: at 57% Goodreads must show 55%. Sync Now with In the Blood at about 52.9% in NeoReader. Expect "Book matched · same edition" on the shelved Kindle edition and no new post on Goodreads, because 52 is less than 5 points above 50; the details popup must say the next update is sent at 55%. Read past 55% and sync: Goodreads must show the floored percentage, and the status list must have exactly one new update.
3. Leave the device online with Boox Tracker hidden for more than six hours. Export: a `run` event from source `renewal` must appear, and no `goodreads_renewal` issue. From 0.6.3 the job's first run comes six hours after sign-in, not at sign-in. A `goodreads_renewal` timeout records `pageState` (`no_page`, `page_finished`, `loading`, or `checking`); record it with the time. Record any `goodreads_browser_check_required` with its time and network.
4. Finish a test book in NeoReader on one day and sync on a later day. Goodreads must show it on Read with the finish date of the reading day, not the sync day. Reopen it; the next sync must hold with `goodreads_status_conflict`.
5. Cancel on a fresh popup must turn Goodreads Off. Log out must clear the Goodreads session (the next On shows the sign-in page), keep StoryGraph connected, and stop the renewal job. Export and confirm the absence of any cookie value, token, and the user id.

## Pagebound sync

Pagebound is built in 0.7.0 through its website's unofficial API; see the [Pagebound plan](plan-20261008-pagebound-sync.md). In the Blood is on Reading on Pagebound with one update at 50% (240 of 480 pages, format digital, started 2026-09-14) that the user posted on 2026-10-08. Its library entry has no edition set. The Kindle edition 1679532 (ISBN 9781982181680, the ISBN NeoReader shows) and the UK paperback 1734779 (ISBN 9781398508255, the EPUB's own) belong to the same book. None of the write endpoints has run against Pagebound yet; record each response in [integrations](integrations.md#pagebound).

1. Pagebound On: the email popup must open; sign in. The popup must close by itself, and the details popup must show `@username`, the email, and Membership Free. Activity must show the connection event. A wrong password must keep the popup open with "Email or password not accepted"; Cancel must turn Pagebound Off. Record how long the first sign-in took (the API host sleeps).
2. Sync Now with In the Blood above 50% and below 55% in NeoReader. Expect "Book matched · same edition" (the Kindle edition is preferred), the empty edition set to 1679532 on Pagebound, the read still digital, and no new update on the journey; the details popup must say the next update is sent at 55%.
3. Read past 55% and sync: the journey must show exactly one new update at 55%, and the streak widget must count the day. Goodreads must post 55% at the same sync.
4. Finish a test book in NeoReader on one day and sync on a later day. Pagebound must show it Finished with the reading day. Reopen it; the next sync must hold with `pagebound_status_conflict`.
5. Log out must clear the session (the next On shows the popup) and keep the other services connected. Export and confirm the absence of any token, the email, and the account UUID.

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

Use the untouched source to test unique title/author fallback. A title/author result should show "✅ Book matched" without an edition note or warning, with the edition and page basis in the row's details popup. No selection dialog or catalogue edit is allowed. If the source has different metadata on BOOX, record it and use the actual identifiers when interpreting the result. See [integration samples](integrations.md#local-matching-samples-2026-10-06).

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
