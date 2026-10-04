# Reading Sync

A read-only diagnostic Android companion for BOOX readers using NeoReader. This milestone collects evidence about provider access, metadata, progress persistence, foreground observation, and ordinary scheduled checks.

Use **Diagnostics → Read now** to query NeoReader through the installed application's ordinary UID. Root, ADB privileges, storage access, and external accounts are not used.

- [Build and release](docs/build-and-release.md)
- [Physical BOOX test checklist](docs/device-testing.md)
- [Provider research and evidence limits](docs/research.md)
- [Approved mockup](docs/reading-sync-approved-mockup.png)
- [Implementation plan](agents/plan-20261004-reading-sync-poc.md)

## Behavior

The app discovers provider columns. It preserves null, missing, unreadable, and raw field states. Reading-status codes stay raw. A calculated percentage is separate from the raw fraction; its units are not assumed to be physical pages.

**Observation** is a ten-minute foreground service with a quiet Stop notification, a content observer, and five-second polling. It holds no wake lock. Sleep can delay callbacks and cleanup. The first callback after the deadline stops the session before another query.

**Background checks** use unique periodic WorkManager work with a requested fifteen-minute interval. WorkManager retains its normal system-managed wake locks. The first check has a fifteen-minute requested delay. Execution is best-effort, requires no network, and can be delayed by Android or BOOX settings. The toggle defaults off; disabling it cancels future work.

Events retain execution source, start/end context, timestamps, query duration, gaps, changes, and errors. Activity shows the latest 250 events and groups unchanged observations for display. Export retains the full log. Same-identity signed upgrades preserve app data; uninstalling or clearing storage deletes it.

Export uses the Android share sheet only on request. Full paths are replaced with filenames and digests. Arbitrary exception messages and unrelated provider blobs are omitted from export. The allowlisted extra-attribute position is a raw diagnostic value with no page interpretation. Cloud backup is disabled.

See the [local verification record](docs/verification.md) for build, test, and signing results.

## Verification boundaries

Compilation and local automated tests cannot establish BOOX compatibility. Provider fixtures exercise application code; they are not firmware evidence. A foreground-service success is not evidence that ordinary scheduled background execution works. No physical BOOX verification has been performed during development.
