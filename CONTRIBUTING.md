# Contributing to Boox Tracker

Boox Tracker is a native Kotlin Android app. The current preview focuses on reading NeoReader metadata, background diagnostics, and local logs. Tracker integrations are planned.

## Issues and device reports

Use [GitHub Issues](https://github.com/otherguy/boox-tracker/issues) for bugs, compatibility reports, and feature proposals. For a device problem, include:

- BOOX model, Android version, firmware/build, and Boox Tracker version.
- Steps to reproduce, expected behavior, and actual behavior.
- Whether the device was awake, asleep, or rebooted, and whether the app was hidden, Hardcover was enabled, and the device had a working network.
- Relevant action times and a reviewed diagnostic export, if you choose to share one.

Exports can contain book titles, identifiers, progress, and device information. Remove information you do not want public. Do not attach credentials, signing files, or private provider databases.

The [device-testing guide](docs/device-testing.md) describes controlled checks. A successful build or emulator test does not establish BOOX firmware compatibility.

## Development setup

Follow the [build guide](docs/build-and-release.md). Use `mise` and `.tool-versions`, including for Java. The debug build has a separate package and can be installed alongside the diagnostic app.

Run the repository checks before submitting changes:

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug
mise exec -- ktlint '**/*.kt' '**/*.kts'
mise exec -- ruff format --check scripts
mise exec -- ruff check scripts
mise exec -- markdownlint-cli2
mise exec -- actionlint
```

GitHub Actions runs these checks for pushes and pull requests. Signing credentials are not needed for the debug build or these checks.

## Pull requests

- Keep changes focused. Describe the problem, the change, and how you tested it.
- Discuss new integrations or substantial behavior changes in an issue first. Identify the proposed API, authentication method, and book-matching approach.
- Keep NeoReader access read-only. Do not require root, ADB privileges, private database access, or permission bypasses.
- Preserve raw metadata, retained logs, and the distinction between foreground collection, scheduled collection, and queued delivery. Add focused regression tests for behavior changes.
- Keep the interface readable on monochrome and colour e-ink. Use static transitions and large touch targets.

Do not commit generated APKs, caches, diagnostic exports, passwords, tokens, or signing keys. New dependencies and reused code must have compatible open-source licences.
