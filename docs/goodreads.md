# Goodreads setup

Boox Tracker sends saved NeoReader progress to your Goodreads account, keeps the book on the right Goodreads shelf, and sets the date you finished it.

> [!CAUTION]
> Goodreads has no public API for new apps. Boox Tracker signs in to Goodreads' website in a browser window inside the app and then uses the same page requests as the website. Goodreads protects its site with a bot check and can change or block these requests at any time. Use this connection at your own risk.

## Connect

1. Allow read-only access to your ebook folder when Boox Tracker starts.
2. Open a book in NeoReader and return to its library so it can save progress.
3. Turn **Goodreads On**. The popup shows Goodreads' own website. Sign in there with your email or your Amazon account.
4. Press **Sync Now**. Check the match and delivery result below Goodreads, then check the book on Goodreads.

Boox Tracker never sees or remembers your password. It keeps the browser session that Goodreads sets, in a private browser profile on this device that only Goodreads uses. Sign-in with Google, Apple, or Facebook can refuse to work inside an app; use your email or Amazon account if it does.

Goodreads checks for automated visitors. When Goodreads asks for that check, Boox Tracker loads Goodreads once in a hidden browser window so the check can pass, then tries again. While Goodreads is connected and a network is available, the hidden window also loads Goodreads about every six hours to keep the session current. If the check still fails or Goodreads ends the session, the row shows **Reconnect required**: turn Goodreads Off and On and sign in again. **Cancel** in the popup turns Goodreads Off.

This needs an Android System WebView that supports separate browser profiles. On an older WebView the switch turns Off again and the row says so; update Android System WebView and try again.

## Matching

No book selection or confirmation is required. Boox Tracker tries a `goodreads:` identifier tag that holds a Goodreads book ID, then ISBN-13 and ISBN-10, then title and author. An ISBN counts only when the found edition's own page shows the same ISBN. Goodreads search does not find ebook ASINs, so ASIN tags are not used. Identifiers that point to different books, or several Goodreads books with the same title and author, hold the update.

If you already shelved another edition of the book on Goodreads, Boox Tracker updates that edition. Tap the Goodreads row to see which edition receives your progress.

## Progress and shelves

Goodreads posts each progress update to your friends' update feeds. Boox Tracker therefore sends NeoReader's saved percentage in whole steps of **5**, counted from 0: at 14% it sends 10%, and at 23% it sends 20%. Steps in between are not sent separately. The details popup shows the percentage at which the next update is sent.

- An unshelved or Want to Read book moves to **Currently Reading** with its current progress.
- Higher progress on Goodreads is kept. The Goodreads row then shows ⚠ before **Synced at**; tap the row to see the progress Goodreads kept.
- When NeoReader marks the book finished, the book moves to **Read**, and its read gets the day you last read it in NeoReader as the finish date. The date change is not posted to the update feed.
- If Goodreads does not accept the finish date, the book stays on Read and the row shows **⚠ Finish date not set** with the reason. A book that already has a review keeps its review; Boox Tracker does not set the date for it.
- A book on Read or Did Not Finish is protected: reopening it in NeoReader holds the update instead of changing it.
- Boox Tracker does not change ratings, reviews, notes, other shelves, or editions, and it never adds a read.

Offline updates wait in the same queue as other services. Turning Goodreads Off pauses its updates without affecting other services.

## Account details and log out

The Goodreads row shows **Book matched · same edition** when progress goes to your ebook's edition and **Book matched · different edition** when it goes to another edition you shelved. **Book matched** alone means the book was found by title and author, so your ebook's edition is not known.

Tap the Goodreads row to see your Goodreads name and username, when you connected it, and how the current book was matched and updated. Your account details stay on this device and are never included in exports.

**Log out** in that popup signs out of Goodreads, removes the Goodreads browser profile's data from this device, turns Goodreads off, stops the six-hourly check, and deletes any Goodreads updates that have not been sent yet. You confirm once before anything is deleted. Other services' sessions are not affected.

## Problems and privacy

Use **Activity → Issues → Details** for errors. Exports exclude session cookies, folder URIs, and full paths. They can include titles, identifiers, progress, and Goodreads book IDs. Review exports before sharing.
