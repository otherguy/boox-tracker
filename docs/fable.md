# Fable setup

Boox Tracker sends saved NeoReader progress to your Fable account and keeps the book on the right Fable shelf.

> [!CAUTION]
> Fable has no public developer API. Boox Tracker uses the same service endpoints as Fable's own apps. Fable can change or block them at any time, and its terms of use restrict automated access. Use this connection at your own risk.

## Connect

1. Allow read-only access to your ebook folder when Boox Tracker starts.
2. Open a book in NeoReader and return to its library so it can save progress.
3. Turn **Fable On**. In the popup, enter your Fable email and password and press **Sign in**.
4. Press **Sync Now**. Check the match and delivery result below Fable, then check your Fable library.

If you created your Fable account with Google or Apple, set a password for the account in the Fable app first.

Boox Tracker never stores your password. It keeps only Fable's sign-in tokens, encrypted with Android Keystore. If Fable ends the session, for example after a password change, the row shows **Reconnect required**: turn Fable Off and On and sign in again. If Fable rejects the email or password, the popup stays open with a short message so you can try again. **Cancel** turns Fable Off.

## Matching

No book selection or confirmation is required. Boox Tracker tries a `fable:` identifier tag that holds a Fable book ID, then ISBN-13, ISBN-10, and ASIN, then title and author. Each Fable book record is one edition. Several matching records are accepted only when they are editions of the same book; otherwise the update holds.

If you already shelved another edition of the book on Fable, Boox Tracker updates that edition. Tap the Fable row to see which edition receives your progress.

## Progress and shelves

Fable stores whole percentages. Boox Tracker sends NeoReader's saved percentage rounded down, so 50.07% becomes 50%.

- An unshelved or Want to Read book moves to **Currently Reading** before the first progress update.
- When progress goes up, Boox Tracker also marks the last day you read the book for your Fable reading streak, like tapping **I read today** in the Fable app. An update that waited offline marks the day you last read, not the day it was sent. If you read on several days while offline, only the last of them is marked. If Fable rejects the streak day, your progress still syncs, and the Fable row shows **Streak day not marked** with an amber warning.
- Higher progress on Fable is kept.
- When NeoReader marks the book finished, Fable receives 100% and the book moves to **Finished**.
- A book on Finished or Did Not Finish on Fable is protected: reopening it in NeoReader holds the update instead of changing it.
- Boox Tracker does not change ratings, reviews, or other lists.

Offline updates wait in the same queue as other services. Turning Fable Off pauses its updates without affecting Hardcover.

## Account details and log out

Tap the Fable row to see your Fable account (username, name, email, membership, sign-up date), when you connected it, and how the current book was matched and updated. Your account details stay on this device and are never included in exports.

**Log out** in that popup removes your Fable sign-in from this device, turns Fable off, and deletes any Fable updates that have not been sent yet. You confirm once before anything is deleted.

## Problems and privacy

Use **Activity → Issues → Details** for errors. Exports exclude passwords, tokens, folder URIs, and full paths. They can include titles, identifiers, progress, and Fable book IDs. Review exports before sharing.
