# Boox Tracker: automatic matching and offline sync

Approved by the user on 2026-10-06. Completed in 0.3.0–0.3.3 (codes 7–10). Supersedes edition-selection proposals and the diagnostic observation UI. Open physical checks are tracked in [project status](project-status.md).

## Automatic matching

- [x] Preserve provider and EPUB ISBN, Goodreads, ASIN and service identifiers; non-EPUB uses database metadata.
- [x] Support Hardcover edition IDs, book IDs, slugs and URLs.
- [x] Resolve explicit IDs, ISBN/ASIN, Goodreads mappings, then unique normalized title and author; hold conflicts without dialogs.
- [x] Cache and revalidate matches by stable source identity and identifier metadata.
- [x] Keep other services Coming Soon.

## Progress and status

- [x] Prefer existing read edition, exact match, default ebook, then default physical edition; require valid page count and book ownership.
- [x] Convert raw fraction; keep higher remote progress and protect completed/reread history.
- [x] Show exact/book-only/error matching separately from pending/last successful delivery, keyed to current book.
- [x] Treat offline waiting as normal; errors only for enabled services or provider failures.

## Offline queue

- [x] Persist latest observation per account/service/book while keeping full local activity.
- [x] Unique unconstrained 15-minute collection plus network-constrained delivery; foreground/manual collect and deliver.
- [x] Serialize and acknowledge exact revisions; retry transient failures and reconcile uncertain writes.
- [x] Existing account enables offline; failed initial sign-in returns Off; no background approval UI.
- [x] Off pauses pending sends; account changes cannot cross-send; no credentials in logs/exports.

## UI and identity

- [x] Sync/Activity header with DB title, fraction percentage, count, read time, status icon and black Sync Now; tap for bordered metadata.
- [x] Remove diagnostic sections, Read Now, expansion, background toggle and app-owned observation service/permissions.
- [x] Mandatory persisted read-only ebook folder with Exit/Allow again; background errors have no UI; About can replace folder.
- [x] Device information in Activity; bordered updated About; retained Activity performance/export/artwork/static transitions.
- [x] Package/namespace dev.otherguy.booxtracker, debug suffix, 0.3.0/code 7, existing signer/client ID, fresh data; keep old installation.

## Verification and delivery

- [x] Identifier and matching regressions, including non-EPUB and no selectors.
- [x] Edition fallback/page conversion/remote history protection.
- [x] Durable multi-book queue, reconnect, interruptions, revisions, Off/On/account isolation.
- [x] Folder/UI/background/Activity tests; mise build/static checks; parallel reviews; screenshots and signed checksum APK.
- [x] Update public and agent documents; no GitHub publication.

## Physical verification

- [x] Real native BOOX sign-in and user-initiated exact-edition sync; user confirmed remote progress on 0.3.1.
- [ ] Real book-only sync using the automatic fallback path.
- [ ] Offline collection, retained multi-book queue across restart/reboot, and reconnection delivery with the app hidden.

Automatic checks cover these paths; unchecked items require physical evidence, not missing implementation. Inspect actual edition/raw-progress/delivery events in an export. Opening the app sends automatically, so only earlier hidden-app executions prove that case. Use [the current protocol](device-testing.md#offline-collection-and-hidden-app-delivery).

## Notes

### Matching and queue decisions

- Match cache expires after one hour as well as invalidating on metadata fingerprint changes.
- Existing remote editions without pages remain held. Pageless exact source editions can fall through to verified defaults.
- Unique delivery uses APPEND_OR_REPLACE so enqueue during worker completion retains another network-triggered attempt.
- The identifier reader is the single allowlist; the popup supplies labels only. Future-service tags are display-only, not connectors. Fable later matches `fable:` UUIDs ([Fable plan](plan-20261006-fable-sync.md)).
- Ebook cache namespace 2 refreshes extracted fields even if source modification time is unchanged; it is not a schema migration.

### Delivery history

| Version / code | Changes and evidence |
| --- | --- |
| 0.3.0 / 7 | Approved matching, queue, UI/package scope; 73 local tests/checks/reviews, signed API 32 screenshots. Later USB install/launch on GoColor7; com.onyx had disabled the package, cause unknown |
| 0.3.1 / 8 | Explanation before picker, Exit / Choose folder; 73 tests/checks/reviews; signed update and prompt visually checked. User subsequently confirmed native approval/exact match/manual remote progress |
| 0.3.2 / 9 | Full merged identifiers, bold keys, readable dates, explicit states, readable-grant cancellation/recreation fixes; 76 tests/checks/reviews; popup and cancelled replacement checked on BOOX |
| 0.3.3 / 10 | One reader allowlist, explicit future display tags, cache namespace 2; 76 tests/checks/reviews; signed update and real-book popup checked |

The user's Hardcover screenshots show In the Blood, Currently Reading, 240/480 pages (50%). They do not identify the actual selected edition or raw source fraction. Book-only and hidden-app offline delivery remain unchecked. Future-service rows have automatic coverage only because those tags were absent from the tested book.

The old package was absent from installed/known lists; no uninstall/data clear was issued. If present again, preserve its logs and disable old sends/checks/observation. Compatible 0.3.x updates retained the same data inode; that is not reboot-queue proof. Foreground update checks around 22:03–22:04 and 22:31–22:32 are excluded from background evidence.

### Remaining scope

Merged APK permission checks distinguish app declarations from dependencies: WorkManager still contributes generic FOREGROUND_SERVICE/SystemForegroundService, WAKE_LOCK and boot scheduling. Observation and its explicit permissions are gone; current workers do not enter foreground mode. Removing a feature does not remove all library declarations.

At completion the source was local on `feat/automatic-offline-sync`; it was later merged and pushed to `main`. No release/prerelease was created or authorized. Other service connectors, rereads (completion is in the [completion plan](plan-20261006-completion-sync.md)), embedded non-EPUB parsers, website, statistics provider, updater, and retention policy need separate scope. See [product decisions](product.md#open-decisions-and-next-work).
