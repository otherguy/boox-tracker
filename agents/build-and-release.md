# Build and release

## Tools

Install [mise](https://mise.jdx.dev/) if it is absent. This machine already has it. All project host dependencies are declared in `.tool-versions`; Homebrew and Android Studio are not required.

```sh
mise install
mise exec -- android --no-metrics sdk install platforms/android-36 build-tools/35.0.0
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug
mise exec -- ruff format --check scripts
mise exec -- ruff check scripts
mise exec -- ktlint '**/*.kt' '**/*.kts'
mise exec -- markdownlint-cli2
mise exec -- actionlint
```

Use the [Android CLI SDK installer](https://developer.android.com/tools/agents/android-cli/commands/sdk_install) bundled with Command-line Tools 23. Review and accept any SDK license prompts during local installation. CI supplies confirmation during package installation. `--no-metrics` disables Android CLI usage metrics. AGP 8.13.2 can emit an SDK XML-version warning with these tools; it did not prevent the verified builds. Do not use `android init` to add unrelated agent skills.

The Gradle wrapper is pinned to 8.13 with the official distribution SHA-256. Kotlin is 2.3.21; compile/target SDK is 36; Build Tools are 35.0.0. Bytecode target is Java 17, while mise provides JDK 21. Gradle manages pinned application libraries. No separate Kotlin installation is required.

## Signing identity

Create a dedicated local diagnostic identity once:

```sh
mise exec -- python scripts/create-diagnostic-key.py
mise exec -- ./gradlew assembleDiagnostic
```

The generator stores `diagnostic.jks` and `signing.properties` in `~/.config/reading-sync/`, with restricted permissions. It refuses to overwrite either file and never prints passwords. Keep a secure backup of both files. Reuse this key for every downloadable update. A replacement key cannot update an existing installation.

Gradle reads that properties file by default. Set `READING_SYNC_SIGNING_PROPERTIES` to use another external file with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` properties. Never pass passwords on a command line, enable shell tracing around secrets, or commit signing files. Missing configuration fails `assembleDiagnostic`.

The diagnostic variant uses `dev.otherguy.booxtracker` and is non-debuggable. Debug uses `dev.otherguy.booxtracker.debug` and a development label. Its signing certificate is separate; it cannot update a diagnostic installation. Neither build receives privileged permissions.

## Artifact verification

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
mise exec -- python scripts/package-artifacts.py
```

The packaging script verifies APK signatures, records certificate SHA-256 fingerprints, and writes copies plus checksum files under `dist/`. `dist/build-info.json` records app versions, tool pins, artifact hashes, and certificate details. APK build outputs remain under `app/build/outputs/apk/`.

Reproducible means the wrapper, tools, dependencies, source, and signing identity are pinned. A repeated build is not claimed to be byte-identical: signing/build metadata can affect bytes. Compare each delivered file with its own checksum.

## GitHub delivery

The repository remote is [otherguy/boox-tracker](https://github.com/otherguy/boox-tracker). GitHub prereleases and releases are on hold until the user confirms Reading Sync is a working app. The manual workflow below is a documented later path, not an instruction to dispatch it now; a source push does not publish the diagnostic APK.

`android.yml` runs lint, tests, and debug assembly on pushes and pull requests. `prerelease.yml` is manual and creates a draft prerelease in the repository running the workflow. Review the draft and publish it in GitHub. Keep its signing secrets restricted to trusted maintainers. Never run signing on untrusted pull-request code.

Configure these repository secrets:

- `READING_SYNC_KEYSTORE_BASE64`: base64 of the dedicated diagnostic keystore.
- `READING_SYNC_STORE_PASSWORD`: its store password.
- `READING_SYNC_KEY_ALIAS`: `diagnostic`, unless explicitly configured otherwise.
- `READING_SYNC_KEY_PASSWORD`: its key password.

The workflow reconstructs signing files under runner temporary storage, with restrictive permissions. It uploads APK, checksums, and build information; never the key or properties file. Its release permission uses the repository's GitHub token. The workflow derives the repository from GitHub context and does not assume an account name.

Increase `versionCode` for each update. The workflow input passes `-PversionCode=<integer>` to Gradle. Local updates can use the same property. Preserve the application ID and certificate.

## Current 0.3.3 delivery

Use `dist/boox-tracker-0.3.3-diagnostic.apk` and its adjacent checksum. Code is 10. It updates 0.3.0–0.3.2 with the same package and certificate. This package was introduced in 0.3.0 with fresh data and can coexist with `org.readingsync.diagnostic`; keep any old app/logs and disable its scheduled/observation/tracker activity first. The signing identity, external configuration, and GitHub secrets remain unchanged. The signed update was installed over USB; its metadata popup was checked; cancelled folder replacement was checked on 0.3.2 on the user's GoColor7. The user confirmed manual exact matching and remote progress on 0.3.1; book-only/background offline delivery remains unverified. See [verification](verification.md#identifier-allowlist-033).

## Historical local diagnostic updates

GitHub prereleases and releases are on hold until the user confirms Reading Sync is a working app. Do not dispatch `prerelease.yml` during this hold. Continue to use ordinary CI and local APK delivery.

The 0.1.2 update has version code `3` and uses the same application ID, signing identity, and database schema as 0.1.0. The packaging script derives filenames from the built APK version metadata; it retains APKs from earlier versions.

Copy `dist/reading-sync-0.1.2-diagnostic.apk` to the BOOX and open it with the normal installer. Install over the existing diagnostic app. Do not uninstall or clear its storage. Check **About** for 0.1.2, open Activity, and confirm that earlier events remain. Export again to verify retained history. The user has completed the 0.1.2 installation and confirmed much faster Activity scrolling and retained logs. The later 0.1.2 export confirms scheduled background reads around sleep/wake and retained history through sharing. These diagnostic results do not lift the GitHub publication hold.

## Future distribution references

The user's eventual channel is GitHub Releases. Normal sideloading can use the device's browser or file manager and Android installer, without ADB/root or an app-store listing. Publishing an asset does not update installed copies. A version-tag build trigger, in-app Check for updates, and optional Obtainium support were proposals; the implemented release workflow is manual and no updater exists. Do not promise silent installs.

References for that later work:

- [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), [Android alternative distribution](https://developer.android.com/distribute/marketing-tools/alternative-distribution), and [Android signing](https://developer.android.com/studio/publish/app-signing).
- [BOOX installation guide](https://help.boox.com/hc/en-us/articles/8569396308884-Download-and-Install-Apps) and [alternative guide](https://help.boox.com/hc/en-us/articles/10701308914964-Download-and-Install-Apps).
- [Obtainium](https://obtainium.imranr.dev/), [source](https://github.com/ImranR98/Obtainium), and [source configuration](https://wiki.obtainium.imranr.dev/sources/).
- [F-Droid developer FAQ](https://f-droid.org/en/docs/FAQ_-_App_Developers/) and [Google Play testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).
- [Android developer verification](https://developer.android.com/developer-verification), [FAQ](https://developer.android.com/developer-verification/guides/faq), and [open-source registration](https://developer.android.com/developer-verification/guides/open-source-app-registration).

Google Play, F-Droid, and the BOOX store are not selected delivery dependencies. Earlier notes mentioned twelve testers for fourteen continuous days for some Play personal accounts and a phased 2026/2027 developer-verification rollout; these are time-sensitive research, not established requirements for this app. Recheck current official guidance if pursuing those channels or broad distribution. F-Droid/channel signing can affect upgrades; preserve the existing identity until an explicit channel decision. No public BOOX-store third-party submission process was established.

## First Hardcover delivery: 2026-10-06

0.2.0/code 4 is packaged as `dist/reading-sync-0.2.0-diagnostic.apk`. Its SHA-256 is `66f152802846423501aa41ecd9315a600c4a0391f8e03442a781bc45377cb8a3`; the adjacent checksum and `dist/build-info.json` are generated by the packaging script. The diagnostic certificate is unchanged from 0.1.2. Install over the existing app and preserve history/settings; never uninstall to pass an update check. New installation/auth/SAF/remote sync are not physically verified yet.

Both variants built; 46 tests and Android lint passed. See [verification](verification.md#first-hardcover-preview-2026-10-06) for automatic evidence and limitations. The existing push/PR workflow is configured; current changes remain local and have not run in GitHub CI. No release or prerelease was published, and the publication hold remains.

## Service UI delivery: 2026-10-06

The current update is 0.2.1/code 5, packaged as `dist/reading-sync-0.2.1-diagnostic.apk` (3,489,986 bytes). SHA-256 is `667e34e45c95d1885bf462f1e7a8a9009a4725a57ef5c92e21a935afaeee8c74`. The package and diagnostic certificate remain unchanged; database and export schemas remain version 1. Install over the existing app and preserve data.

Both builds, 49 tests, and Android lint passed. See [current verification](verification.md#service-ui-update-2026-10-06). The signed diagnostic APK was installed on an API 32 emulator for screenshots; it has not been installed or integration-tested on a physical BOOX in this update. No GitHub release or prerelease was published. The README's product description and download destination do not lift the publication hold.

## Boox Tracker rename delivery: 2026-10-06

The historical rename APK is `dist/boox-tracker-0.2.2-diagnostic.apk`, version 0.2.2/code 6 (3,490,070 bytes), SHA-256 `9d63e236431c7553f8a745f83e5f0d6ce2253443b887f58bae64f6b7c96ea97c`. Packaging and future CI artifacts use the Boox Tracker name. Older `reading-sync-*` artifacts remain historical outputs; do not rename or remove them by hand.

The 0.2.2 rename kept `org.readingsync.diagnostic`, the existing certificate, `~/.config/reading-sync/`, and the `READING_SYNC_*` signing configuration. Stored background-work names and token-vault identifiers also remain unchanged so this branding update does not create duplicate jobs or lose credentials. Both builds and 49 tests passed; see [rename verification](verification.md#boox-tracker-rename-2026-10-06). Install over the existing app; the publication hold remains.

## USB installation

The user authorized USB installation on 2026-10-06. The 0.2.2 diagnostic APK installed over 0.1.2 on the GoColor7; installed version/code and launch were verified. Retained Activity history and Hardcover account operations still need user confirmation.

BOOX exposes USB Debug Mode under Home → Apps → top-right menu → App Management. See the [official BOOX instructions](https://booxsupport.zendesk.com/hc/en-us/articles/8569441562004-App-Management-Settings). Enable it, reconnect, and accept the device's debugging authorization prompt. The generic Android Developer options route is not needed for this BOOX shortcut. No Homebrew dependency is required; adb is included in the mise-managed SDK.

Use `mise exec -- adb devices -l` to identify the authorized device, then explicitly target its serial with `mise exec -- adb -s <serial> install -r dist/boox-tracker-0.2.2-diagnostic.apk`. Keep the update path; do not uninstall or clear data. Verify installed version and launch separately from app behavior. Do not query NeoReader data through ADB as proof of ordinary-app access.
