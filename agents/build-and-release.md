# Build and release

Tools, signing, packaging, CI, USB installation, and publication rules. The current build, its checksums, and the latest CI result are in [project status](project-status.md). **Publish a GitHub release or dispatch the prerelease workflow only when the user explicitly asks.** A successful sync or a green CI run is not that request.

## Tools

Use [mise](https://mise.jdx.dev/) and `.tool-versions` for all host tools, including Java. This machine already has mise. Homebrew and Android Studio are not required. Gradle resolves pinned application libraries; a separate Kotlin install is not needed.

```sh
mise install
mise exec -- android --no-metrics sdk install platforms/android-36 build-tools/35.0.0
```

Use Android CLI `android ... sdk install`, not deprecated `sdkmanager`. Accept local SDK licence prompts; CI supplies confirmation. `--no-metrics` disables CLI metrics. Do not run `android init` or add unrelated agent skills. See the [SDK installer reference](https://developer.android.com/tools/agents/android-cli/commands/sdk_install).

| Tool / target | Pin |
| --- | --- |
| Java | Temurin 21.0.12+8; Java bytecode target 17 |
| Gradle | Wrapper 8.13 with official distribution SHA-256 |
| Android tools | Command-line Tools 23.0; Build Tools 35.0.0 |
| Android / Kotlin plugins | AGP 8.13.2; Kotlin 2.3.21 |
| SDK | Minimum 26; compile/target 36 |

Other tools are pinned in `.tool-versions`. AGP can emit an SDK XML-version notice with these tools; it did not block verified builds. Do not upgrade to remove a notice without checking current official compatibility guidance.

## Signing identity

The existing diagnostic key and properties file are external in `~/.config/reading-sync/`, mode 0600. Reuse them for updates. Do not regenerate, overwrite, print, or commit them. Preserve a secure backup; a replacement signer cannot update installed copies.

Gradle reads `~/.config/reading-sync/signing.properties` by default. `READING_SYNC_SIGNING_PROPERTIES` can point to another external file containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Missing configuration fails diagnostic assembly. Never put passwords on a command line or use shell tracing around credentials.

For a new maintainer's own identity only, `mise exec -- python scripts/create-diagnostic-key.py` creates a dedicated key/config and refuses to overwrite existing files. It is not part of the normal update procedure. Official downloadable updates must keep the existing certificate:

```text
678df89d1df3f2ba3d45c6e19bb016550fd837681ac82f8044e83f6ebf4b420d
```

Diagnostic is non-debuggable with `dev.otherguy.booxtracker`. Development uses `dev.otherguy.booxtracker.debug`, another certificate and data directory; it cannot update the diagnostic app. Neither receives privileged provider access.

## Build and checks

```sh
mise exec -- ./gradlew testDebugUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
mise exec -- python scripts/package-artifacts.py
mise exec -- ktlint '**/*.kt' '**/*.kts'
mise exec -- ruff format --check scripts
mise exec -- ruff check scripts
mise exec -- markdownlint-cli2
mise exec -- yamllint --strict .
mise exec -- actionlint
```

Lint each changed Markdown file explicitly, including untracked docs. Run `git diff --check` and check local links. Substantial code changes require parallel code-reviewer/code-simplifier reviews with findings resolved; docs-only changes require direct review. Do not weaken tests or dismiss unexpected stderr. Test counts and lint notices for each build are in [verification](verification.md).

The packaging script verifies APK signatures and writes copied APKs, adjacent SHA-256 files, and `dist/build-info.json`. It records version/package/tool/signature information, never signing passwords. Build outputs and packaged artifacts are read-only generated files. Rebuild/package through their owner; do not edit or rename historical outputs.

Reproducible means pinned tools/wrapper/dependencies/source/signer. Byte-identical repeated APKs are not claimed. Compare each delivered APK with its own checksum. A checksum match proves bytes, not runtime compatibility.

## Compatible updates

Compatible updates require the same application ID and certificate and an increasing versionCode. Local builds can use `-PversionCode=<integer>`. The SQLite and export schemas have their own versions; do not change them just because the app version changes. The ebook metadata cache namespace is a third, separate version. Installation, retained data-directory identity, and account behaviour are separate checks.

Package identity changed in 0.3.0/code 7; it starts fresh and can coexist with `org.readingsync.diagnostic`. Do not migrate private data by hand. If the old app is present, preserve its logs and disable its checks, observation, and sends before running both. The external signing path and `READING_SYNC_*` secrets names stay unchanged despite branding.

## GitHub delivery

Remote: [otherguy/boox-tracker](https://github.com/otherguy/boox-tracker). Check status before committing and never stage unrelated files. Do not force-push `main`.

Two workflows save runner minutes on this public repository. `lint.yml` runs every static check (whitespace, ktlint, ruff, markdownlint, yamllint, actionlint) in one job on pushes to `main` and on pull requests, installing only the linters. `android.yml` runs tests, both lint variants, debug assembly, and artifact/report upload (kept 14 days), only when app, Gradle, tool-version, or editorconfig files change, or by manual dispatch; `setup-gradle` caches Gradle, and only `main` writes the cache. Both cancel a run that a newer push to the same branch or pull request replaces, and both have timeouts. Actions are pinned to release tags at least two weeks old. Neither needs the diagnostic signer. Local passing checks are not proof of GitHub CI; check the runs for the pushed commit, and remember that a docs-only push runs Lint alone.

`lefthook.yml` mirrors both workflows: staged-file linters before a commit, the full lint set and the Gradle checks before a push. Contributors install it with `mise exec -- lefthook install`; see [CONTRIBUTING](../CONTRIBUTING.md#git-hooks).

`prerelease.yml` is a manual workflow that builds/checks signed artifacts and creates a **draft prerelease**. It is the documented publishing path, not authorization to run it. When the user asks for a release, verify the intended source/tag, increasing versionCode, trusted signing configuration, and completed checks before dispatch. Review the draft before publication. A source push alone does not publish an APK.

Repository secrets required by that later workflow:

- `READING_SYNC_KEYSTORE_BASE64`: dedicated diagnostic keystore.
- `READING_SYNC_STORE_PASSWORD`: store password.
- `READING_SYNC_KEY_ALIAS`: configured alias, normally diagnostic.
- `READING_SYNC_KEY_PASSWORD`: key password.

The workflow writes signing files under runner temporary storage with restrictive permissions. It uploads APK/checksum/build metadata, not the key/config. Release permissions use the repository token/context. Never expose signing secrets to untrusted pull-request code. Secret availability has not been established here; do not print them to check.

## USB installation

Install over USB only with the user's go-ahead. BOOX USB Debug Mode is under Home → Apps → top-right menu → App Management on the tested firmware; see [BOOX instructions](https://booxsupport.zendesk.com/hc/en-us/articles/8569441562004-App-Management-Settings). Accept device debugging authorization. This is an installation convenience, not an app requirement.

```sh
mise exec -- adb devices -l
mise exec -- adb -s <physical-serial> install -r dist/boox-tracker-<version>-diagnostic.apk
```

The GoColor7's serial is `6DE7CCBA`; an emulator is often attached too. Recheck devices and explicitly select the physical serial. Before and after the install, compare `versionCode` and the data directory inode (`stat -c '%i' /data/user/0/dev.otherguy.booxtracker`). Verify launch separately. Never uninstall or clear data to make an update pass, and never use an ADB provider query as ordinary-app proof.

The fresh package was initially disabled by `com.onyx`; enabling it allowed launch, with cause unknown. UI XML dumps returned null roots on this BOOX; screenshots were used instead. See [device pitfalls](device-testing.md#boox-settings-and-usb-pitfalls). Normal file-manager/browser sideload installation requires no ADB/root/Android Studio.

## Historical deliveries and future channels

0.1.0–0.2.2 used `org.readingsync.diagnostic`. 0.1.2/code 3 retained logs and improved Activity scrolling; 0.2.0/code 4 added Hardcover; 0.2.1/code 5 added service rows; 0.2.2/code 6 renamed the app. 0.3.0/code 7 changed package/offline matching, 0.3.1/code 8 added folder explanation, 0.3.2/code 9 fixed metadata/cancellation, 0.3.3/code 10 added display tags with one reader allowlist/cache invalidation, 0.3.4/code 11 added Hardcover completion sync, 0.4.0/code 12 added Fable, 0.4.8/code 20 redesigned the Activity tab and bounded the log, and 0.5.0–0.5.2/codes 21–23 added StoryGraph through its website session. Historical hashes, checks, and physical boundaries remain in [verification](verification.md). Previous generated APKs remain under dist.

GitHub Releases is the eventual channel. No updater exists, and uploading an APK cannot update an installed app. Obtainium, F-Droid, Google Play, BOOX store, tag-triggered publication, and silent installation are not selected/built requirements. Website subdomain/hosting are not selected. Other-channel signing can affect updates; retain the existing identity until an explicit decision.

References for later work: [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), [Android signing](https://developer.android.com/studio/publish/app-signing), [alternative distribution](https://developer.android.com/distribute/marketing-tools/alternative-distribution), [Obtainium](https://obtainium.imranr.dev/), [F-Droid FAQ](https://f-droid.org/en/docs/FAQ_-_App_Developers/), [Play testing](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en), and [Android developer verification](https://developer.android.com/developer-verification). Store/testing/developer-verification rules are time-sensitive; recheck official sources if those channels enter scope.
