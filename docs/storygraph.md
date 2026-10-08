# StoryGraph setup

Boox Tracker sends saved NeoReader progress to your StoryGraph account and keeps the book on the right StoryGraph status.

> [!CAUTION]
> StoryGraph has no public API. Boox Tracker signs in to StoryGraph's website in a browser window inside the app and then uses the same page requests as the website. StoryGraph protects its site against automated access and can change or block these requests at any time. Use this connection at your own risk.

## Connect

1. Allow read-only access to your ebook folder when Boox Tracker starts.
2. Open a book in NeoReader and return to its library so it can save progress.
3. Turn **StoryGraph On**. The popup shows StoryGraph's own website. Sign in there.
4. Press **Sync Now**. Check the match and delivery result below StoryGraph, then check the book on StoryGraph.

Boox Tracker never sees or remembers your password. It keeps the browser session that StoryGraph sets, in the app's private browser storage on this device. If StoryGraph ends the session, the row shows **Reconnect required**: turn StoryGraph Off and On and sign in again. If StoryGraph asks for a browser check, the same steps pass it in the popup. **Cancel** turns StoryGraph Off.

## Matching

No book selection or confirmation is required. Boox Tracker tries a `storygraph:` identifier tag that holds a StoryGraph book ID, then ISBN-13, ISBN-10, and ASIN, then title and author. StoryGraph's search answers an unknown ISBN with a similar book, so an identifier counts only when the found edition's own page shows the same ISBN or ID. Several StoryGraph books with the same title and author hold the update.

If you already shelved another edition of the book on StoryGraph, Boox Tracker updates that edition. Tap the StoryGraph row to see which edition receives your progress.

## Progress and status

StoryGraph stores progress as whole percentages or pages. Boox Tracker sends NeoReader's saved percentage rounded down, so 50.07% becomes 50%, and StoryGraph works out the page from it. StoryGraph's progress form then shows percentages for that book.

- An unshelved, To Read, or Paused book is marked **currently reading** before the first progress update.
- Higher progress on StoryGraph is kept. The StoryGraph row then shows ⚠ before **Synced at**; tap the row to see the progress StoryGraph kept.
- When NeoReader marks the book finished, the book is marked **read** on StoryGraph with that day's date.
- A book marked read, did not finish, or rereading on StoryGraph is protected: reopening it in NeoReader holds the update instead of changing it.
- Boox Tracker does not change ratings, reviews, journal entries, tags, or editions.

Offline updates wait in the same queue as other services. Turning StoryGraph Off pauses its updates without affecting other services.

## Account details and log out

The StoryGraph row shows **Book matched · same edition** when progress goes to your ebook's edition and **Book matched · different edition** when it goes to another edition you shelved. **Book matched** alone means the book was found by title and author, so your ebook's edition is not known.

Tap the StoryGraph row to see your StoryGraph username, when you connected it, and how the current book was matched and updated. Your account details stay on this device and are never included in exports.

**Log out** in that popup signs out of StoryGraph, removes the browser session from this device, turns StoryGraph off, and deletes any StoryGraph updates that have not been sent yet. You confirm once before anything is deleted.

## Problems and privacy

Use **Activity → Issues → Details** for errors. Exports exclude session cookies, folder URIs, and full paths. They can include titles, identifiers, progress, and StoryGraph book IDs. Review exports before sharing.
