# Reading Sync diagnostic APK plan

Approved on 2026-10-04. MIT; Kotlin Views/XML; mise and `.tool-versions`.

- [x] Create one Android module, Gradle wrapper, and retain the approved mockup.
- [x] Implement read-only provider discovery, explicit outcomes, raw metadata, and Diagnostics UI.
- [x] Implement the ten-minute foreground observation session and separate periodic WorkManager checks.
- [x] Persist observations, changes, interruptions, settings, and safe user-requested exports.
- [x] Add focused tests, build both APK variants, verify signing, and document device testing and prereleases.

## Constraints

Query only the Metadata content provider through the ordinary app UID. No provider writes, private databases, storage permissions, network sync, or app-managed wake locks. Scheduled workers keep normal WorkManager wake-lock behavior. Require a visible Stop notification before a session starts. Poll every five seconds. Use a monotonic ten-minute deadline and record late cleanup after sleep. Keep worker and session evidence separate. Preserve logs across compatible signed updates. Store the dedicated signing identity outside the repository. No remote is configured; do not claim publication or physical BOOX verification.
