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

## Fable

1. Turn **Fable On**, enter your Fable email and password in the popup, and press **Sign in**. A wrong password must keep the popup open with a short message. **Cancel** must turn Fable Off.
2. With a known book open in NeoReader, return to its library and press **Sync Now**. On Fable, the book must be on Currently Reading with the percentage rounded down. Higher progress on Fable must stay unchanged.
3. Finish the book in NeoReader. After a sync, Fable must show 100% and the book on Finished.
4. Export and confirm that the export contains no password or token.

## StoryGraph

1. Turn **StoryGraph On**. The popup must show StoryGraph's own sign-in page; sign in there. The popup must close by itself, and the details popup must show your username. **Cancel** must turn StoryGraph Off.
2. With a known book open in NeoReader, return to its library and press **Sync Now**. On StoryGraph, the book must be currently reading with the percentage rounded down. Higher progress on StoryGraph must stay unchanged.
3. Press **Sync Now** again after 35 minutes, after two hours, the next day, after a reboot, and on another Wi-Fi network. None of them may report a browser check.
4. Finish the book in NeoReader. After a sync, StoryGraph must show the book as read with that day's date. Reopening it must hold the update.
5. Export and confirm that the export contains no cookie or user ID.

## Goodreads

1. Turn **Goodreads On**. The popup must show Goodreads' own sign-in page; sign in there with your email or Amazon account. The popup must close by itself, and the details popup must show your name and username. **Cancel** must turn Goodreads Off.
2. With a known book open in NeoReader, return to its library and press **Sync Now**. On Goodreads, the book must be on Currently Reading with the percentage rounded down. Read a few pages, sync again, and confirm that nothing new is posted until the percentage is 5 points higher. Higher progress on Goodreads must stay unchanged.
3. Leave the device online for more than six hours with Boox Tracker hidden, then export. A background renewal run must appear, and Goodreads must stay connected.
4. Finish the book in NeoReader. After a sync, Goodreads must show the book on Read with the finish date of the day you last read it. Reopening it must hold the update.
5. Log out of Goodreads in its details popup. StoryGraph, if connected, must stay connected. Export and confirm that the export contains no cookie, token, or user ID.

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
3. Inspect **scheduled** query and **delivery** events. For local reads, `appVisibleAtStart` and `appVisible` must be false. Each background run has one **run** event with its start time, `appVisibleAtStart`, and duration; inspect delivery runs separately.
4. Repeat during sleep, then cold boot. Record sleep, wake, power-off, boot, and app-open times. Reboot retention and execution timing are different questions.

The fifteen-minute interval is a request, not a guarantee. Network availability, Android scheduling, sleep, and BOOX restrictions can delay work. A foreground sync, emulator result, or ADB provider query does not establish ordinary-app background execution.

## BOOX settings

Names vary by firmware. Check freeze/auto-freeze, App Startup, background-running restrictions, and battery optimization. Record settings before changing them. If work does not run, change one relevant setting and repeat the same test. These settings can affect execution; they do not guarantee it. A gap alone does not establish its cause.

No observation service or app-managed wake lock is used in 0.3.0. WorkManager manages normal Android scheduling resources.

## Logs and reports

Activity shows the retained events; tap an entry for its **Summary** and **JSON** tabs. **Export** writes the same retained history as text and JSON. **Clear** asks once, then deletes every event and leaves one "Activity cleared" entry; export first when you need the history. The log keeps at most 1,000 events and nothing older than 30 days; routine checks and waiting sends from before the last successful sync are removed once they are two days old, so export within two days of a background test. Use **All / Issues**. Provider denial, unavailable provider, empty library, unknown progress, queued waiting, and tracker errors are separate results.

Compatible signed updates preserve data. The 0.3.0 package change starts a new data store and leaves old logs in the old app. Do not uninstall or clear storage to test retention.

Review exports before sharing: they can contain titles, identifiers, progress, tracker IDs, and device details. See [CONTRIBUTING](../CONTRIBUTING.md#issues-and-device-reports) for reports.
