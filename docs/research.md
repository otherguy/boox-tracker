# Provider research

The Metadata URI is a community lead, not a firmware contract:

```text
content://com.onyx.content.database.ContentProvider/Metadata
```

## Inspected sources

- [Tukks/onyxbooxsync.koplugin](https://github.com/Tukks/onyxbooxsync.koplugin/tree/9a0e8b3d0484ccdbf6e5919f9442e8047bc9a2b2): MIT license file. Its Android provider helper contains application-side queries as well as writes. Reading Sync does not use its write behavior.
- [eszter007/boox-kosync](https://github.com/eszter007/boox-kosync/tree/9a4df2f9b2cdea414fd49bd9972d575c1dfc40c1): MIT license file. Its application uses `ContentResolver`, a foreground observer, and polling. It references `extraAttributes.current_page_position_v2`; its meaning must be tested per firmware.
- [sleepdebt/boox-hardcover](https://github.com/sleepdebt/boox-hardcover/tree/f1ae43468d80d55aaaa7b06268d297be7ca6c6ca): `pyproject.toml` declares MIT; no standalone license text was present in the inspected tree. It queries through ADB and documents normalized fractions and lifecycle persistence. Those observations cannot prove application-UID access or persistence timing on another firmware.

Reading Sync uses an independent minimal implementation. No tracker code or source snippets were copied from these projects. AndroidX dependencies retain their own Apache licenses.

## Android references

- [Package visibility](https://developer.android.com/training/package-visibility/declaring): the authority declaration makes provider discovery possible; it grants no provider access permission.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): `dataSync` covers local fetching. `shortService` has a shorter limit and is not suitable for ten minutes.
- [Periodic WorkManager requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work): fifteen minutes is the minimum periodic interval; actual timing is best-effort.
- [Wake behavior](https://developer.android.com/develop/background-work/background-tasks/awake?hl=en): WorkManager/system scheduling can acquire wake locks. The observation service does not.

The app manifest requests foreground-service and notification permissions. WorkManager adds normal wake-lock, boot-rescheduling, and network-state permissions. Network state is a library scheduling capability; this app has no network constraint or Internet permission. It requests no storage, usage-stats, root, shell, battery-exemption, or vendor-signature permission.
