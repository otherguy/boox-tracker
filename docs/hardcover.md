# Hardcover setup

Boox Tracker sends saved NeoReader progress to your Hardcover account. Turn Hardcover On to connect. You do not need an API key or developer registration.

## Connect

1. Allow read-only access to your ebook folder when Boox Tracker starts. Select a folder below the storage root; subfolders are included.
2. Open a book in NeoReader and return to its library so it can save progress.
3. Turn **Hardcover On**. A popup shows a sign-in code. Press **Open Hardcover sign-in**, or open `hardcover.app/link` on your reader, phone, or computer. Sign in, enter the code, and approve the connection. The popup closes when approval completes; **Cancel** turns Hardcover Off.
4. Press **Sync Now**. Check the match and delivery result below Hardcover, then check your account.

A saved connection can be enabled offline. Updates stay queued until delivery is possible. A first connection needs internet to obtain and approve its code; a failed attempt returns the switch to Off. Services that are On stay On while offline. Boox Tracker does not prompt you to enable Wi-Fi.

All distributed builds use the application's public OAuth client ID. Each user approves their own account and each device stores its own private tokens. See the [Hardcover OAuth guide](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/OAuth.mdx).

## Automatic matching

No book selection or confirmation is required. Boox Tracker detects the latest saved book and tries Hardcover identifiers, ISBN-13/ISBN-10, Amazon/ASIN, Goodreads mappings, then title and author. It accepts one distinct book; conflicting identifiers or ambiguous titles hold the update.

Database metadata works for all formats that NeoReader exposes. For EPUBs, the app also reads embedded metadata from your allowed folder. It does not infer identifiers from filenames or upload ebook contents.

Supported tags include `isbn:`, `goodreads:`, `amazon:`, `hardcover-edition:` for edition IDs, `hardcover-id:` for book IDs, and `hardcover:` / `hardcover-slug:` for book slugs. Hardcover book and edition URLs are also accepted. A Goodreads mapping identifies a book, not an exact source edition. Match results are cached for up to one hour and invalidated when source metadata changes.

Tap the book title to see available identifiers. `asin:` and `amazon:` values can identify Hardcover editions. The details can also display explicit `storygraph:`, `fable:`, and `margins:` identifier tags when present. Hardcover does not use them. Fable uses `fable:` tags that hold a Fable book ID; see the [Fable guide](fable.md). StoryGraph and Margins remain Coming Soon. Unrelated tags are ignored.

| Result | Meaning |
| --- | --- |
| ✅ Exact edition matched | Progress uses the matched source edition. |
| ⚠ Book matched | The book is identified; another valid Hardcover edition supplies the page basis. Tap the row for the edition and page count used. |
| ❌ Specific error | Matching, account access, or safe progress delivery cannot proceed. |

An existing Hardcover read's edition is preserved. Otherwise, the app uses the exact matched edition with pages, the default ebook edition, then the default physical edition. It verifies that the edition belongs to the matched book. Missing usable page counts hold the update.

## Progress and offline delivery

The raw NeoReader fraction becomes approximate pages, rounded to the nearest whole page. These fraction units are not physical pages. Hardcover may show a different percentage because its edition page count and rounding differ from NeoReader.

Boox Tracker keeps higher remote progress. It can start a Want to Read book or advance an existing unfinished read. When NeoReader marks the detected book finished, Boox Tracker records full progress and the finish date on that read and sets the book to Read. The finish date is the day of NeoReader's last saved access. A book already marked Read on Hardcover is left unchanged. It does not change catalogue records, ratings, reviews, historical dates, paused reads, or rereads; reopening a finished book keeps the existing finished read protected.

Delivery is shown separately as **Pending**, **Synced at…**, or a failure. The last successful time and progress remain visible after a failed attempt for that book. A result for another book is not shown as the current book's success.

The device retains the latest pending update for each account and book, including when you switch books, restart, or reboot. Local collection requests a fifteen-minute schedule without a network requirement. Separate delivery work waits for a network and retries temporary failures. Android and BOOX decide when work can run.

Turn Hardcover Off to pause sends and retain pending items. Turn it On for the same account to resume. Another account cannot receive the previous account's queue.

Tap the Hardcover row to see your account (username, name, membership), when you connected it, and how the current book was matched, including the edition and page count used. **Log out** there removes your Hardcover sign-in from this device, turns Hardcover off, and deletes any Hardcover updates that have not been sent yet; you confirm once first. Hardcover does not share your email with Boox Tracker. Background work never opens sign-in.

## Problems and privacy

Use **Activity → Issues → Details** for errors. A missing or tied NeoReader last-access time cannot establish one current book. Returning to the NeoReader library can save state. For conflicting identifiers, ambiguous matches, or missing page counts, correct the ebook metadata or Hardcover catalogue yourself; Boox Tracker never asks you to choose a substitute.

For folder errors, select the correct folder through **About → Change ebook folder**. Revoked access blocks collection and sends. If multiple EPUBs have the same filename, use a folder with one copy.

Tokens are encrypted with Android Keystore. Exports exclude credentials, sign-in codes, folder URIs, full paths, and unrelated provider data. They can include titles, identifiers, progress, tracker IDs, and device details. Review exports before sharing. See [device testing](device-testing.md) for the offline background test.
