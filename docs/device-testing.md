# Physical BOOX testing

Record model, Android version, BOOX firmware/build, NeoReader version if visible, and current background settings. The app shows the device/build fields available through Android. Keep a short note of page turns, book exits, font changes, and screen transitions with clock times. The app cannot observe those user actions directly.

## Reading evidence

1. Copy the diagnostic APK to the BOOX and open it through the normal file manager/installer. Allow installation from that source when Android requests it. Open Reading Sync, select a known book, and press **Read now**. Record the provider outcome, columns, raw fraction, status, and last access.
2. Start **10-minute observation**. Allow its notification. Switch to NeoReader and turn several pages. Note the action times. Keep the visible Stop notification available.
3. Leave the book. Return to Reading Sync and compare observation timestamps and changes. Do not conclude that progress persists on every page turn from a later manual query alone.
4. Change font size in NeoReader. Repeat the observation and compare the raw numerator/denominator with the calculated percentage. The fraction may represent normalized units rather than physical pages.

## Background and retention evidence

1. Repeat with a second book and a finished book. Inspect raw status values without assuming a code mapping. Select another record even if it has no stable identifier; such selection applies only to the displayed snapshot.
2. Start a session, turn the screen off, and wake it. Repeat with sleep extending beyond ten minutes. Inspect actual gaps, late cleanup, and interruption entries. No wake lock or screen-on setting should be added to make the result look successful.
3. Stop the observation session, enable **Background checks**, and leave Reading Sync out of the foreground for at least 30–60 minutes. Do not force-stop it. Inspect actual `scheduled` query events, start/end visibility, and observation-service flags. Repeat overnight if no execution appears. Enabling the toggle is not proof of execution.
4. Reopen Reading Sync and export diagnostics from Activity. Check that earlier evidence remains in both files. For update testing, install a higher-version diagnostic APK with the same certificate without uninstalling first, then confirm the log remains.

## BOOX settings

Firmware names and locations vary. Inspect app freeze/auto-freeze, App Startup, background-running restrictions, battery optimization, and notification settings for Reading Sync. Start with the existing settings and record them. If execution is blocked, change one relevant setting and repeat the test. Unfreeze the app if BOOX marks it frozen. Allow its quiet observation notification.

These controls may affect execution; they do not guarantee callbacks, progress persistence, or worker timing. Android force-stop and BOOX freeze can prevent jobs until the app is allowed to run again. Screen-off gaps alone do not establish why work was delayed.

## Interpretation

`success` with zero records means the provider returned an empty cursor. Permission denial, provider absence, null cursors, and query failure are different outcomes. Successful access with missing/unreadable progress still does not establish usable progress.

Observer registration success does not prove NeoReader emits notifications. Polls can find changes that receive no observer callback. A query reads persisted provider state; it cannot reveal an unpersisted live reading position.

A scheduled query counts as ordinary-background evidence only when Reading Sync was out of the foreground and its observation service was inactive throughout the test interval. Start and end flags help identify overlap, but cannot prove there was no brief overlap between samples. Use the separate test procedure.

Report results under **Built**, **Automatically tested**, and **Verified on physical BOOX**. Include the exported evidence and exact firmware. ADB provider queries, emulator runs, and compilation do not establish ordinary-app access on the BOOX.
