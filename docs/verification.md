# Local verification record

Verified on 2026-10-04 in the macOS development workspace. No physical BOOX was used.

## Built

Both variants built with the tool versions in `.tool-versions`:

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
mise exec -- python scripts/package-artifacts.py
```

The diagnostic APK is non-debuggable, targets API 36, and requires API 26 or higher. Its application ID is `org.readingsync.diagnostic`, version name `0.1.0`, version code `1`. The debug build has a separate application ID and development certificate.

APK Signature Scheme v2 verification passed for both files. Each delivered file was rehashed and matched its adjacent `.apk.sha256` file. The diagnostic certificate SHA-256 is:

```text
678df89d1df3f2ba3d45c6e19bb016550fd837681ac82f8044e83f6ebf4b420d
```

The dedicated identity is outside the repository in `~/.config/reading-sync/`. The key and properties file have mode `0600`. Retain a secure backup to sign compatible updates. `dist/build-info.json` contains public signature details and artifact hashes.

## Automatically tested

Fourteen tests passed, with zero failures, errors, or skipped tests:

- Three fraction tests: raw preservation, calculated percentages, large units, malformed/null/zero/out-of-range handling.
- Four metadata tests: missing columns, unknown status, cursor closure, safe fields, provider errors versus empty cursors, local persistence, deadline boundaries.
- Seven background tests: unique work and cancellation, context fields, export privacy, change detection, interruption recovery, non-cooperative provider cancellation, foreground Start/Stop, and automatic session expiry.

These tests use Robolectric API 34 and a fixture provider. The foreground service and scheduling tests call the app's shipping classes. They do not test NeoReader or Android execution on BOOX firmware.

Kotlin formatting/lint, Ruff formatting/lint, Markdown lint, and Actions lint passed. Fresh Android lint runs passed for both variants with zero errors and nine dependency-version notices per variant. The pinned compatible dependencies were retained. Test-owned database handles are closed; the final tests emitted no resource-leak warnings.

Read-only code review and simplification review completed. Review fixes cover ambiguous record identity, query cancellation, safe exports, observation deadlines, callback registration, selection state, and activity details. The prerelease signing step was checked with synthetic Unicode and leading-space values through Java Properties loading. No real secrets were printed.

## Physical BOOX and GitHub

Physical BOOX verification: **not performed**. Ordinary-UID provider access, firmware columns, progress persistence timing, observer notifications, screen-off behavior, and scheduled execution remain unknown. Follow [device testing](device-testing.md) and retain the export as evidence.

GitHub workflow execution and publication: **not performed**. At the time of this local verification, the checkout had no remote or commits. The supplied workflows were statically checked. Configure the intended repository and signing secrets, then use the documented [draft prerelease path](build-and-release.md#github-delivery).
