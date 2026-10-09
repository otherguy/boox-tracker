# Pagebound setup

Boox Tracker sends saved NeoReader progress to your Pagebound library and keeps the book on Reading or Finished.

> [!CAUTION]
> Pagebound has no public API. Boox Tracker uses the same service endpoints as Pagebound's website. Pagebound can change or block them at any time, and its terms of use restrict automated access. Use this connection at your own risk.

## Connect

1. Allow read-only access to your ebook folder when Boox Tracker starts.
2. Open a book in NeoReader and return to its library so it can save progress.
3. Turn **Pagebound On**. In the popup, enter your Pagebound email and password and press **Sign in**.
4. Press **Sync Now**. Check the match and delivery result below Pagebound, then check your Pagebound library.

If you joined Pagebound with Google or Apple, set a password for your account on Pagebound first.

Boox Tracker never stores your password. It keeps only Pagebound's sign-in tokens, encrypted with Android Keystore. If Pagebound ends the session, for example after a password change, the row shows **Reconnect required**: turn Pagebound Off and On and sign in again. If Pagebound rejects the email or password, the popup stays open with a short message so you can try again. **Cancel** turns Pagebound Off.

Pagebound's server sleeps when nobody uses it. The first update after a quiet period can take up to a minute.

## Matching

No book selection or confirmation is required. Boox Tracker tries a `pagebound:` identifier tag that holds a Pagebound book ID, then ISBN-13 and ISBN-10, then title and author. Pagebound has no ASIN lookup. When the ebook's ISBNs name several editions of one book, the Kindle or ebook edition is preferred. Identifiers that point to different books, or several Pagebound books with the same title and author, hold the update.

When your library entry has no edition yet, Boox Tracker sets it to the edition the ISBN found. An edition you already chose is kept. Tap the Pagebound row to see which edition your entry uses.

## Progress and status

Pagebound shows each progress update in your feed and on the book's journey. Boox Tracker therefore sends NeoReader's saved percentage in whole steps of **5**, counted from 0: at 14% it sends 10%, and at 23% it sends 20%. Steps in between are not sent separately. The details popup shows the percentage at which the next update is sent.

- A book that is not in your library is added as **Reading** with the format **digital** and the edition's page count.
- A TBR, Interested, or unset book moves to **Reading** as a new digital read. A Paused book resumes its read and keeps the format you set.
- An existing read keeps the format you set.
- Higher progress on Pagebound is kept. The Pagebound row then shows ⚠ before **Synced at**; tap the row to see the progress Pagebound kept.
- When NeoReader marks the book finished, the book moves to **Finished** with the day you last read it as the finish date.
- A book on Finished or DNF is protected: reopening it in NeoReader holds the update. A book you finished before is not started again; the update holds instead of starting a reread.
- Boox Tracker does not change ratings, reviews, shelves, or owned and muted settings.

Offline updates wait in the same queue as other services. Turning Pagebound Off pauses its updates without affecting the other services.

## Account details and log out

The Pagebound row shows **Book matched · same edition** when your entry uses the ebook's edition and **Book matched · different edition** when it uses another edition. **Book matched** alone means the book was found by title and author, so your ebook's edition is not known.

Tap the Pagebound row to see your Pagebound account (username, email, membership), when you connected it, and how the current book was matched and updated. Your account details stay on this device and are never included in exports.

**Log out** in that popup removes your Pagebound sign-in from this device, turns Pagebound off, and deletes any Pagebound updates that have not been sent yet. You confirm once before anything is deleted.

## Problems and privacy

Use **Activity → Issues → Details** for errors. Exports exclude passwords, tokens, folder URIs, and full paths. They can include titles, identifiers, progress, and Pagebound book IDs. Review exports before sharing.
