# Testing Boox Tracker on a BOOX

Record your model, Android version, firmware, and Boox Tracker version from **Activity → Device Information**. Note action times so you can compare them with Activity. Test on your normal BOOX settings first.

## Install and manual sync

1. If an older Reading Sync diagnostic app is installed, turn its tracker and Background checks Off and stop observation. Keep it installed to retain its logs. Version 0.3.0 installs separately.
2. Open Boox Tracker, read the ebook-folder explanation, and press **Choose folder**. Select the folder that contains your ebooks and allow read access. Cancelled or revoked access blocks setup until you allow a readable folder or exit.
3. Open a known book in NeoReader, return to its library, then press **Sync Now**. Tap the title to inspect raw progress, saved last-access time, and query time.
4. Turn **Hardcover On** and approve sign-in. Verify an exact edition match and the remote page equivalent in your account.
5. Try a book without a matching ISBN edition. Verify book-only matching through another identifier or unique title and author. No selection dialog or catalogue edit should occur.
6. Finish a book in NeoReader and return to its library. After a sync, the Hardcover row should show **Finished** and the book should be Read on Hardcover with the finish date of that day and one read entry.

The provider fraction is not a physical page count. Saved library progress can lag page turns and differ from the in-book percentage. Font changes, a second book, and a finished book can help test those differences. Completed or reread records remain protected. Reading status codes `0`, `1`, and `2` mean not started, reading, and finished; other codes are not guessed.

Book details show full ISBNs and other available identifiers. **Last Access** is NeoReader's saved timestamp; **Read At** is when Boox Tracker queried it. In About, **Change ebook folder** opens the picker. Pressing Back keeps the existing folder if its read permission is still valid.

## Offline collection and reconnect

1. After one successful manual sync, keep Hardcover On and disconnect Wi-Fi. Read the first book, return to the NeoReader library, and press Sync Now. Confirm **Pending** without a request to enable Wi-Fi.
2. Read a second book and repeat. Both pending updates must remain. Close and reopen Boox Tracker; pending items and earlier Activity must remain.
3. Return to NeoReader and leave Boox Tracker hidden. Reconnect to a working network. Do not open Boox Tracker or press Sync Now while delivery is being tested.
4. Check Hardcover from another device. Then reopen Boox Tracker and export. Separate **delivery** entries that ran before app-open from the **foreground** query/sends caused by opening it.
5. Test Off while pending, then On offline for the same account. Off pauses sends; On resumes. A first connection without internet must return Off with a short message.

Account changes must not deliver another account's queue. If testing this, keep notes of which account owned each pending item. Do not share credentials or sign-in codes.

## Independent background collection

1. Keep Hardcover On. Stay in NeoReader for 30–60 minutes with Boox Tracker hidden. Keep the device awake for the first test; do not force-stop the new app.
2. Reopen Boox Tracker and export. Opening it automatically collects and attempts delivery: only entries before that time establish independent work.
3. Inspect **scheduled** query and **delivery** events. For local reads, `appVisibleAtStart` and `appVisible` must be false. For delivery, inspect its start/stop visibility and timestamps separately.
4. Repeat during sleep, then cold boot. Record sleep, wake, power-off, boot, and app-open times. Reboot retention and execution timing are different questions.

The fifteen-minute interval is a request, not a guarantee. Network availability, Android scheduling, sleep, and BOOX restrictions can delay work. A foreground sync, emulator result, or ADB provider query does not establish ordinary-app background execution.

## BOOX settings

Names vary by firmware. Check freeze/auto-freeze, App Startup, background-running restrictions, and battery optimization. Record settings before changing them. If work does not run, change one relevant setting and repeat the same test. These settings can affect execution; they do not guarantee it. A gap alone does not establish its cause.

No observation service or app-managed wake lock is used in 0.3.0. WorkManager manages normal Android scheduling resources.

## Logs and reports

Activity shows the latest 250 events; **Export diagnostics** includes the retained history as text and JSON. Use **All / Issues** and expandable details. Provider denial, unavailable provider, empty library, unknown progress, queued waiting, and tracker errors are separate results.

Compatible signed updates preserve data. The 0.3.0 package change starts a new data store and leaves old logs in the old app. Do not uninstall or clear storage to test retention.

Review exports before sharing: they can contain titles, identifiers, progress, tracker IDs, and device details. See [CONTRIBUTING](../CONTRIBUTING.md#issues-and-device-reports) for reports.
