# Building Boox Tracker

## Toolchain

Install [mise](https://mise.jdx.dev/). The repository's [.tool-versions](../.tool-versions) pins Java, the Android command-line tools, Gradle, and the check tools. Android Studio is optional.

From the repository root:

```sh
mise install
mise exec -- android --no-metrics sdk install platforms/android-36 build-tools/35.0.0
```

Review and accept the Android SDK licence prompts. Use the pinned Android CLI rather than the deprecated `sdkmanager`. Gradle downloads the pinned Kotlin plugin and application libraries; no separate Kotlin installation is needed.

## Development build

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` through the BOOX file manager. It appears as **Boox Tracker (dev)**, uses package `dev.otherguy.booxtracker.debug`, and can be installed alongside the diagnostic app. It uses a development certificate and cannot update a diagnostic installation.

See [CONTRIBUTING](../CONTRIBUTING.md#development-setup) for the formatting and other repository checks.

## Signed diagnostic build

To create your own signing identity, run this once:

```sh
mise exec -- python scripts/create-diagnostic-key.py
```

The script creates `diagnostic.jks` and `signing.properties` in `~/.config/reading-sync/`. It restricts file permissions, refuses to overwrite an existing identity, and does not print passwords. Back up these files securely and reuse them for your updates.

Version 0.3.0 uses a new package, `dev.otherguy.booxtracker`. It installs separately from `org.readingsync.diagnostic`; old logs remain in the old app. The external signing configuration path stays unchanged. Stop the old app’s observation/background activity and turn its service Off before enabling the new app.

Gradle reads that configuration by default. To use another external properties file, set `READING_SYNC_SIGNING_PROPERTIES`; it must define `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Keep credentials outside the repository and out of command arguments and logs.

Build and package both variants:

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
mise exec -- python scripts/package-artifacts.py
```

The diagnostic APK uses package `dev.otherguy.booxtracker` and is not debuggable. Your own certificate cannot update an APK signed by the project. Use the debug variant for development alongside an existing diagnostic installation.

## Artifacts and updates

The packaging script verifies signatures and writes versioned APKs, SHA-256 checksum files, and public build information to `dist/`. The diagnostic filename follows `boox-tracker-<version>-diagnostic.apk`. Build outputs also remain under `app/build/outputs/apk/`.

Keep the same package and certificate for compatible updates, and increase `versionCode`. A local build can override it with `-PversionCode=<integer>`. Installing over the existing app preserves its data; uninstalling or clearing storage removes it.

The toolchain, wrapper, and dependencies are pinned. Byte-identical builds are not guaranteed; verify each APK against its own checksum.

Published downloads, when available, are listed on [GitHub Releases](https://github.com/otherguy/boox-tracker/releases). GitHub Actions produces development build artifacts on pushes and pull requests. Development artifacts use the debug package and certificate.

## Hardcover development

The native connector uses a public device-code OAuth client. The public client ID is not a secret. No client secret, account password, or token belongs in Gradle properties or GitHub Actions.

For your own fork, register a public Mobile/Desktop/CLI app in Hardcover with Device Authorization Grant and scopes `read:catalog read:library write:library read:me:content`. Set your public client ID in `HardcoverAuth.kt`. Native device sign-in needs no redirect URI or hosted backend. See the [official OAuth guide source](https://github.com/hardcoverapp/hardcover-docs/blob/main/src/content/docs/api/OAuth.mdx).

Unit tests use synthetic EPUBs and a local HTTP server. MockWebServer 4.12.0 is a test dependency only. Production networking uses Android HTTPS, with no runtime HTTP library. Account authorization and real mutations require physical validation; CI never receives account credentials or writes to an account.
