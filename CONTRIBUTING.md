# Contributing to Boox Tracker

Boox Tracker is a native Kotlin Android app. It reads NeoReader metadata, keeps local logs, and sends progress to Hardcover, Goodreads, StoryGraph, Fable, and Pagebound.

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
mise exec -- yamllint --strict .
mise exec -- actionlint
```

GitHub Actions runs the same checks for pull requests and pushes to `main`. The Lint workflow runs every time; the Android checks workflow runs when app, Gradle, or tool-version files change. A newer push to the same pull request cancels the older run. Signing credentials are not needed for the debug build or these checks.

## Git hooks

The repository uses [lefthook](https://github.com/evilmartians/lefthook) to run the CI checks before they reach GitHub. `mise install` installs the pinned version with the other tools; then enable the hooks once in your clone:

```sh
mise install
mise exec -- lefthook install
```

- **Before a commit:** whitespace, ktlint, ruff, markdownlint, yamllint, and actionlint run on the staged files they apply to. They take a few seconds.
- **Before a push:** every linter runs over the repository, and the Gradle tests, Android lint, and debug build run when the pushed commits change app or build files.

The hook commands are in `lefthook.yml` and match the workflows in `.github/workflows/`. Fix a failure and commit again; `ktlint --format` and `ruff format` correct most style findings. To skip the hooks for one commit, for example a work-in-progress commit on your own branch, set `LEFTHOOK=0`:

```sh
LEFTHOOK=0 git commit -m "Work in progress"
```

CI still runs every check on your pull request. If you change a check, change it in `lefthook.yml` and the workflow together.

## Pull requests

- Keep changes focused. Describe the problem, the change, and how you tested it.
- Discuss new integrations or substantial behavior changes in an issue first. Identify the proposed API, authentication method, and book-matching approach.
- Keep NeoReader access read-only. Do not require root, ADB privileges, private database access, or permission bypasses.
- Preserve raw metadata, retained logs, and the distinction between foreground collection, scheduled collection, and queued delivery. Add focused regression tests for behavior changes.
- Keep the interface readable on monochrome and colour e-ink. Use static transitions and large touch targets.

Do not commit generated APKs, caches, diagnostic exports, passwords, tokens, or signing keys. New dependencies and reused code must have compatible open-source licences.
