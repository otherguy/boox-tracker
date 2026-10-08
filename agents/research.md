# Provider research

Historical 0.1.x exports established provider access and progress limits. Tracker sends do not change the BOOX access boundary. Current product/background decisions are in [product](product.md); detailed evidence is in [verification](verification.md).

The Metadata URI began as a community lead and is now verified through the installed app's ordinary UID on the user's GoColor7 firmware. It is not a universal firmware contract:

```text
content://com.onyx.content.database.ContentProvider/Metadata
```

The [verification record](verification.md#verified-on-physical-boox) contains the actual model/build, 45 discovered columns, raw examples, null states, useful observer callbacks, and independent scheduled reads. It supersedes the supplied exploration history's uncertainty about whether Metadata can be read at all. Do not replace the working source layer with private-database or ADB access.

## Inspected sources

- [Tukks/onyxbooxsync.koplugin](https://github.com/Tukks/onyxbooxsync.koplugin/tree/9a0e8b3d0484ccdbf6e5919f9442e8047bc9a2b2): MIT license file. Its Android provider helper contains application-side queries as well as writes. Boox Tracker does not use its write behavior.
- [eszter007/boox-kosync](https://github.com/eszter007/boox-kosync/tree/9a4df2f9b2cdea414fd49bd9972d575c1dfc40c1): MIT license file. Its application uses `ContentResolver`, a foreground observer, and polling. It references `extraAttributes.current_page_position_v2`; its meaning must be tested per firmware.
- [sleepdebt/boox-hardcover](https://github.com/sleepdebt/boox-hardcover/tree/f1ae43468d80d55aaaa7b06268d297be7ca6c6ca): `pyproject.toml` declares MIT; no standalone license text was present in the inspected tree. It queries through ADB and documents normalized fractions and lifecycle persistence. Those observations cannot prove application-UID access or persistence timing on another firmware.

- [Lyfts/ShelfSync](https://github.com/Lyfts/ShelfSync) `shelfsync/lib/goodreads/api.lua` and `provider.lua` (MIT, read on 2026-10-08): Goodreads through a replayed desktop-browser cookie header, without progress read-back. [Lyfts/goodreads-cookie-refresher](https://github.com/Lyfts/goodreads-cookie-refresher) `refresher/app.py` keeps a Selenium Chrome session signed in to pass the AWS WAF challenge. Their findings are in [integrations](integrations.md#goodreads).

Boox Tracker uses an independent minimal implementation. No tracker code or source snippets were copied from these projects. AndroidX dependencies retain their own Apache licenses.

The supplied exploration history additionally named [BearChao/apple-books-boox-sync](https://github.com/BearChao/apple-books-boox-sync) as a metadata/progress/status reference. It is an Apple Books/ADB integration; its source and licence were not inspected during this POC. It does not establish our installed-app access or product architecture.

Historical source locations for deeper investigation, not current-path guarantees:

| Source | Inspected revision / path |
| --- | --- |
| boox-kosync | `9a4df2f9b2cdea414fd49bd9972d575c1dfc40c1`; `android/app/src/main/java/org/koreader/backgroundonyxsynckoreader/contentprovider/OnyxMetadataRepository.java` |
| boox-hardcover | `f1ae43468d80d55aaaa7b06268d297be7ca6c6ca`; `src/boox_hardcover/onyx.py`, `src/boox_hardcover/adb.py` |

Prior upstream reports included Go 7 testing for onyxbooxsync.koplugin, Poke 3/Android 10 for boox-kosync, and Go Color 7/incremental 9661 for boox-hardcover. Those remain upstream reports, not this project's compatibility matrix. Re-resolve revisions, paths, permissions, and licences before reuse. No fork or required KOReader reader was selected.

## Metadata interpretation

`name`, `uuid`, `nativeAbsolutePath`, `progress`, `readingStatus`, `lastAccess`, `extraAttributes`, and `hashTag` are present among the tested provider's columns. Their presence does not imply usable values for every record. `nativeAbsolutePath` is source metadata, not proof the app can read the referenced EPUB. `uuid` is a source identifier; its statistics join is still a hypothesis. `hashTag` semantics are unresolved.

The implemented source identity uses available identifiers/path digest, with ambiguity handled explicitly. Automatic detection uses the unique most recent usable saved lastAccess; tied/missing usable times do not choose a book. It can lag NeoReader's open book. The confirmed progress contract is raw preservation plus `100 × numerator / denominator` for valid fractions. Timestamp interpretation is separate from raw `lastAccess` and query time. Observed times are consistent with Unix milliseconds on the tested records, not a promise for every firmware.

The database exposes `title`, `authors`, and `ISBN`, but a present column can still be null. The metadata popup now uses the same database/ebook identifier repository as sync; a null provider ISBN does not establish that the EPUB lacks one. Shared extraction is off the main thread. Non-EPUB formats use database metadata; no embedded PDF/MOBI parser is built. Keep one identifier allowlist in the reader, not another in the popup.

Provider success with zero rows, access denied, null cursor, failed query, and records with unusable progress remain distinct. Diagnostic `progressProblem: null` means no fraction error. Show OK for valid progress, but never convert missing/unreadable/malformed/zero-denominator/out-of-range progress to 0%. Raw reading-status codes remain visible.

Library exit coincided with changes in controlled tests; a later wake/read exposed changes without a reported exit. Neither every-page persistence nor exit-only persistence is proved. In-book 48.24% versus provider 47.23%, then 48.51% versus 47.52%, demonstrates a discrepancy on the tested book. Its integer library tag was 47%. Do not infer a universal offset or rounding rule. Font 28 → 42 left one inspected fraction unchanged.

Reading status codes on the tested device are `0` not started, `1` reading, and `2` finished; the user confirmed them on 2026-10-06 against a full read-only row listing (see [verification](verification.md#provider-and-progress)). Every code `2` record carried a full fraction and every code `0` record had null progress. Codes outside these three remain visible and are not interpreted. `extraAttributes.current_page_position_v2` was a source-research lead for detailed resume state; its book-specific meaning and presence in this device's safe exported values are not established. Detailed position synchronization is outside the confirmed product direction.

## Statistics-provider lead

The supplied history named an optional second URI:

```text
content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel
```

Boox Tracker does not query or declare visibility for this provider. No ordinary-UID access, column availability, event meaning, time units, or linkage has been verified on the device. Successful Metadata queries do not prove any of these.

| Candidate field | Historical interpretation requiring validation |
| --- | --- |
| `docId` | Possible join to metadata `uuid` |
| `eventTime` | Event timestamp, reportedly Unix milliseconds |
| `durationTime` | Duration, reportedly milliseconds |
| `currPage`, `lastPage` | Position/page-related fields, units unknown |
| `readingProgress` | Numeric progress, scale unknown |
| `type` | Event classification, mappings below are hypotheses |

Prior reverse-engineering notes suggested `type` codes `0` opening, `1` reading duration, `2` annotation activity, and `6` finishing. Do not calculate reading-time/session statistics or assert a join from these notes without a separately scoped read-only provider test.

## Android references

- [Package visibility](https://developer.android.com/training/package-visibility/declaring): the authority declaration makes provider discovery possible; it grants no provider access permission.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): historical diagnostic sessions used `dataSync`; the current app has no observation service.
- [Periodic WorkManager requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work): fifteen minutes is the minimum periodic interval; actual timing is best-effort.
- [Wake behavior](https://developer.android.com/develop/background-work/background-tasks/awake?hl=en): WorkManager/system scheduling can acquire wake locks. Boox Tracker adds no app-managed wake lock or forced screen-on behavior.

The app source manifest requests Internet and network-state access for tracker sync. Its observation service and explicit foreground/notification permissions were removed in 0.3.0. The merged diagnostic APK still inherits WorkManager's WAKE_LOCK, RECEIVE_BOOT_COMPLETED, generic FOREGROUND_SERVICE permission, and SystemForegroundService declaration. Current application code does not promote workers to foreground work. Do not claim the final APK lacks all foreground-service declarations. Local collection has no network constraint; separate delivery does. No broad storage, usage-stats, root, shell, battery-exemption, or vendor-signature permission is requested. Read-only ebook access is a separate persisted SAF grant; package visibility grants no provider access permission.

The supplied background-setting leads are [BOOX app management](https://help.boox.com/hc/en-us/articles/10701262170644-App-Management-Settings) and an [alternative BOOX guide](https://help.boox.com/hc/en-us/articles/8569441562004-App-Management-Settings). App freeze, auto-start, battery/background restrictions, and notification controls may affect execution. Firmware labels vary. Record existing settings, change one variable only when a test calls for it, and do not infer a cause or execution guarantee from a gap. Physical procedures remain in [device testing](device-testing.md).

The fresh 0.3.0 package was disabled by `com.onyx`; enabling it allowed launch, but the responsible policy is unknown. Historical scheduled provider reads after boot/near wake do not prove current network delivery or regular sleep execution. ADB installs/UI checks remain separate from ordinary-UID access evidence.
