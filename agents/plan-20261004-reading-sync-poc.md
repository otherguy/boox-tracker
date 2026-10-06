# Reading Sync diagnostic APK plan

Approved on 2026-10-04. Completed historical diagnostic milestone. MIT; Kotlin Views/XML; mise and `.tool-versions`. The current app is Boox Tracker 0.3.3; use [the automatic/offline plan](plan-20261006-automatic-offline-sync.md), not the removed controls below.

- [x] Create one Android module, Gradle wrapper, and retain the approved mockup.
- [x] Implement read-only provider discovery, explicit outcomes, raw metadata, and Diagnostics UI.
- [x] Implement the ten-minute foreground observation session and separate periodic WorkManager checks.
- [x] Persist observations, changes, interruptions, settings, and safe user-requested exports.
- [x] Add focused tests, build both APK variants, verify signing, and document device testing and prereleases.

## Constraints

Query only the Metadata content provider through the ordinary app UID. No provider writes, private databases, storage permissions, network sync, or app-managed wake locks. Scheduled workers keep normal WorkManager wake-lock behavior. Require a visible Stop notification before a session starts. Poll every five seconds. Use a monotonic ten-minute deadline and record late cleanup after sleep. Keep worker and session evidence separate. Preserve logs across compatible signed updates. Store the dedicated signing identity outside the repository. Report only documented device and CI evidence. GitHub prereleases and releases remain on hold until the user confirms Reading Sync is a working app.

## Later scope: 2026-10-06

The user explicitly removed the source-book selector from all UI, including Diagnostics, and authorized the first Hardcover integration through APK delivery. The original selectable-list requirement above is historical and superseded. See [the first Hardcover plan](plan-20261006-hardcover-first.md). At the first 0.2.0 handoff, provider access/logs/observation remained while enabled Hardcover writes were added. The later approved 0.3.0 plan removed observation, the book selector, Read now, and Background checks controls. Always-on local collection and separate network delivery are now built; BOOX access remains read-only.

## Findings and current next step

The installed ordinary-UID app read the Metadata library on the tested GoColor7 firmware. Controlled exits, font changes, finished books, observer callbacks, independent scheduled reads after boot/near wake, and retained logs were inspected. Null fields, normalized fractions, delayed lastAccess, provider/in-book discrepancies, irregular scheduling, and unknown interruption causes constrain conclusions. See [verification](verification.md#verified-on-physical-boox).

Do not repeat completed sessions just to meet an old duration or generalize one firmware to the whole family. Manual Hardcover exact delivery now has user evidence on 0.3.1. The remaining test is current-package offline/hidden-app delivery and book-only matching, documented in [device testing](device-testing.md). Releases/prereleases are still held.
