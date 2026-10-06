# Reading Sync diagnostic APK plan

Approved on 2026-10-04. MIT; Kotlin Views/XML; mise and `.tool-versions`.

- [x] Create one Android module, Gradle wrapper, and retain the approved mockup.
- [x] Implement read-only provider discovery, explicit outcomes, raw metadata, and Diagnostics UI.
- [x] Implement the ten-minute foreground observation session and separate periodic WorkManager checks.
- [x] Persist observations, changes, interruptions, settings, and safe user-requested exports.
- [x] Add focused tests, build both APK variants, verify signing, and document device testing and prereleases.

## Constraints

Query only the Metadata content provider through the ordinary app UID. No provider writes, private databases, storage permissions, network sync, or app-managed wake locks. Scheduled workers keep normal WorkManager wake-lock behavior. Require a visible Stop notification before a session starts. Poll every five seconds. Use a monotonic ten-minute deadline and record late cleanup after sleep. Keep worker and session evidence separate. Preserve logs across compatible signed updates. Store the dedicated signing identity outside the repository. Report only documented device and CI evidence. GitHub prereleases and releases remain on hold until the user confirms Reading Sync is a working app.

## Later scope: 2026-10-06

The user explicitly removed the source-book selector from all UI, including Diagnostics, and authorized the first Hardcover integration through APK delivery. The original selectable-list requirement above is historical and superseded. See [the first Hardcover plan](plan-20261006-hardcover-first.md). Diagnostic provider access, logs, and observation remain; only explicit enabled Hardcover sends write to a tracker, never to BOOX.
