# Boox Tracker: automatic matching and offline sync

Approved by the user on 2026-10-06. Supersedes edition-selection proposals and the diagnostic observation UI. Publication remains on hold.

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
- [x] Remove diagnostic sections, Read Now, expansion, background toggle and observation service/permissions.
- [x] Mandatory persisted read-only ebook folder with Exit/Allow again; background errors have no UI; About can replace folder.
- [x] Device information in Activity; bordered updated About; retained Activity performance/export/artwork/static transitions.
- [x] Package/namespace dev.otherguy.booxtracker, debug suffix, 0.3.0/code 7, existing signer/client ID, fresh data; keep old installation.

## Verification and delivery

- [x] Identifier and matching regressions, including non-EPUB and no selectors.
- [x] Edition fallback/page conversion/remote history protection.
- [x] Durable multi-book queue, reconnect, interruptions, revisions, Off/On/account isolation.
- [x] Folder/UI/background/Activity tests; mise build/static checks; parallel reviews; screenshots and signed checksum APK.
- [ ] Real BOOX sign-in and sync, then offline/reconnect hidden-app delivery; report physical evidence separately.
- [x] Update public and agent documents; no GitHub publication.

## Notes

- Match cache expires after one hour in addition to invalidation by metadata fingerprint.
- An existing remote edition without pages remains held to avoid reinterpreting remote progress. A pageless exact source edition can fall through to valid defaults.
- Delivery uses unique APPEND_OR_REPLACE work so an enqueue during worker completion cannot lose the next network-triggered attempt.
- Final local checks passed: 73 tests, both builds, both Android lint variants, all static checks, both reviews, signed packaging and API 32 screenshots. No physical BOOX was connected; the remaining physical checklist stays unchecked. No release or prerelease was created.
- Subsequent USB handoff installed and launched signed 0.3.0/code 7 on the user's GoColor7. The newly installed package was disabled by `com.onyx`; enabling it allowed launch. No data clear or uninstall was performed. Account sync and hidden-app offline delivery remain unchecked.
- The user requested an explanation before the startup folder picker. Version 0.3.1/code 8 implements Exit / Choose folder before the picker and keeps Exit / Allow again after cancellation. The startup regression and all 73 tests passed. Both builds, lint/static checks, and scoped reviews passed. The signed update and explanation were checked on the physical GoColor7; account sync remains pending.
- Version 0.3.2/code 9 updates metadata with full merged identifiers, existing service tags, bold labels, readable timestamps, and explicit OK/error states. It keeps the saved readable grant when folder replacement is cancelled, including during Activity recreation. All 76 tests and required gates/reviews passed; the signed update, popup, and picker cancellation were checked on the GoColor7. The user confirmed foreground exact matching and remote progress on 0.3.1. Book-only/background offline delivery stays unchecked.
- The user subsequently reported Exact edition matched and Synced at… in the Hardcover row, and confirmed remote progress. Their In the Blood screenshots show Currently Reading and 240/480 pages (50%). This is physical evidence for the user-initiated exact match/send path; actual edition IDs/raw fraction await an export. Book-only fallback and hidden-app offline resumption remain pending, so the combined physical checklist stays unchecked.

- Version 0.3.3/code 10 keeps the reader as the only identifier allowlist, per the user correction. The popup adds display labels for explicit StoryGraph/Fable/Margins tags; those integrations remain Coming Soon. Cache namespace 2 refreshes older parsed metadata. All 76 tests and required gates/reviews passed; the signed update and present/absent metadata rows were checked on the GoColor7. Future-service tags have automatic coverage only; hidden-app offline delivery remains pending.
