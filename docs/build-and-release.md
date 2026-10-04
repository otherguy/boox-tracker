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

The diagnostic variant uses `org.readingsync.diagnostic` and is non-debuggable. Debug uses `org.readingsync.diagnostic.debug` and a development label. Its signing certificate is separate; it cannot update a diagnostic installation. Neither build receives privileged permissions.

## Artifact verification

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
mise exec -- python scripts/package-artifacts.py
```

The packaging script verifies APK signatures, records certificate SHA-256 fingerprints, and writes copies plus checksum files under `dist/`. `dist/build-info.json` records app versions, tool pins, artifact hashes, and certificate details. APK build outputs remain under `app/build/outputs/apk/`.

Reproducible means the wrapper, tools, dependencies, source, and signing identity are pinned. A repeated build is not claimed to be byte-identical: signing/build metadata can affect bytes. Compare each delivered file with its own checksum.

## GitHub delivery

The repository remote is [otherguy/boox-tracker](https://github.com/otherguy/boox-tracker). Use the manual workflow below to prepare a draft prerelease; a source push does not publish the diagnostic APK.

`android.yml` runs lint, tests, and debug assembly on pushes and pull requests. `prerelease.yml` is manual and creates a draft prerelease in the repository running the workflow. Review the draft and publish it in GitHub. Keep its signing secrets restricted to trusted maintainers. Never run signing on untrusted pull-request code.

Configure these repository secrets:

- `READING_SYNC_KEYSTORE_BASE64`: base64 of the dedicated diagnostic keystore.
- `READING_SYNC_STORE_PASSWORD`: its store password.
- `READING_SYNC_KEY_ALIAS`: `diagnostic`, unless explicitly configured otherwise.
- `READING_SYNC_KEY_PASSWORD`: its key password.

The workflow reconstructs signing files under runner temporary storage, with restrictive permissions. It uploads APK, checksums, and build information; never the key or properties file. Its release permission uses the repository's GitHub token. The workflow derives the repository from GitHub context and does not assume an account name.

Increase `versionCode` for each update. The workflow input passes `-PversionCode=<integer>` to Gradle. Local updates can use the same property. Preserve the application ID and certificate.
