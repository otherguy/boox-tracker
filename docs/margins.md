# Margins setup

Boox Tracker sends saved NeoReader progress to your Margins library as reading sessions and keeps the book's read in progress or finished.

> [!CAUTION]
> Margins has no public API. Boox Tracker uses the same sign-in and sync service as Margins' website. Margins can change or block it at any time, and its terms of use restrict automated access. Use this connection at your own risk.

## Connect

1. Allow read-only access to your ebook folder when Boox Tracker starts.
2. Open a book in NeoReader and return to its library so it can save progress.
3. Turn **Margins On**. In the popup, enter the email of your Margins account and press **Send code**.
4. Margins emails you a sign-in code. Enter it in the popup and press **Sign in**. **Change email** returns to the first step.
5. Press **Sync Now**. Check the match and delivery result below Margins, then check your Margins library.

Margins has no passwords. Boox Tracker signs in only to an existing Margins account: an email without a Margins account is refused, and no account is created. Boox Tracker offers email sign-in only, not Margins' phone sign-in.

Boox Tracker never stores the code. It keeps only Margins' sign-in tokens, encrypted with Android Keystore, and renews them automatically. If Margins ends the session, the row shows **Reconnect required**: turn Margins Off and On and sign in again. **Cancel** turns Margins Off.

## Matching

No book selection or confirmation is required. Boox Tracker tries a `margins:` identifier tag that holds a Margins book ID, then ISBN-13 and ISBN-10, then a `goodreads:` tag, and an ASIN only when nothing else identifies the book. Margins tracks books rather than editions, so several ISBNs of one book are one match. Identifiers that point to different books hold the update.

Margins has no title search. A book without an ISBN, ASIN, Goodreads, or Margins identifier is not matched; the row shows **margins identifier missing**.

## Progress and status

Margins shows each reading session on your profile and in your reading days. Boox Tracker therefore sends NeoReader's saved percentage in whole steps of **5**, counted from 0: at 14% it adds a session from 0% to 10%, and at 23% one from 10% to 20%. Steps in between are not sent separately. The details popup shows the percentage at which the next session is added.

- A book without a read on Margins gets a new read in progress, starting on the day you last read it. A planned read (unread or want to read) becomes the read in progress instead of a second one.
- The first session marks a read without a format as an **ebook**. A read you marked as print or audiobook keeps its format.
- Higher progress on Margins is kept. Progress you logged in pages or Kindle locations is compared as a share of the book. Audiobook progress holds the update. The Margins row shows ⚠ before **Synced at** when Margins keeps higher progress.
- When NeoReader marks the book finished, the read is finished with the day you last read it. When the read has sessions, Margins adds its own last session to the end of the book.
- A book you finished or stopped before is protected: reopening it in NeoReader holds the update instead of starting a reread.
- Boox Tracker does not change ratings, reviews, notes, lists, favourites, or owned and private settings.

Offline updates wait in the same queue as other services. Turning Margins Off pauses its updates without affecting the other services.

## Account details and log out

The Margins row shows **Book matched** when the book was found. Tap the Margins row to see your Margins account (display name and email), when you connected it, which identifier matched the book, and how the current book was updated. Your account details stay on this device and are never included in exports.

**Log out** in that popup ends this device's Margins session, removes your Margins sign-in from this device, turns Margins off, and deletes any Margins updates that have not been sent yet. Your other Margins devices stay signed in. You confirm once before anything is deleted.

## Problems and privacy

Use **Activity → Issues → Details** for errors. Exports exclude sign-in codes, tokens, folder URIs, and full paths. They can include titles, identifiers, progress, and Margins book IDs. Review exports before sharing.
