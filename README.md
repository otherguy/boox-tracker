# Boox Tracker

Keep your reading progress up to date across your book-tracking services while you read in NeoReader on BOOX.

Boox Tracker is an open-source Android companion. Keep your preferred reader, connect your trackers, and let your reading progress follow you.

## Read on BOOX, track where you want

- **Automatic book detection.** Boox Tracker finds the latest saved book in your NeoReader library.
- **Progress updates.** Send progress now or let background work collect it while you read.
- **Simple provider controls.** Turn a provider On to connect it. Turn it Off to stop updates.
- **Clear activity history.** See what was read, what was sent, and which updates need attention. Tap an entry for its details. Export diagnostics when you need help.
- **Made for e-ink.** Large touch targets, high-contrast text, static controls, and service icons that work alongside clear labels.

## Providers

| Provider | Status | Available since |
| --- | --- | --- |
| [Hardcover](https://hardcover.app) | Enabled | 0.2.0 |
| [Goodreads](https://www.goodreads.com) | Coming Soon | — |
| [StoryGraph](https://www.thestorygraph.com) | Enabled² | 0.5.2 |
| [Fable](https://fable.co) | Enabled¹ | 0.4.0 |
| [Margins](https://margins.app) | Coming Soon | — |

¹ Fable has no public developer API. Boox Tracker uses the same endpoints as Fable's apps, so the connection can stop working if Fable changes them.

² StoryGraph has no public API. Boox Tracker signs in to StoryGraph's website inside the app and uses the same page requests as the site, which StoryGraph protects against automated access, so the connection can stop working if StoryGraph changes it.

Each provider has its own switch. Coming Soon providers cannot be enabled. Reading information remains available without a tracker account.

Hardcover matches automatically using identifiers or title and author. It converts saved progress to approximate edition pages, marks the book Read when NeoReader marks it finished, keeps higher remote progress, and protects earlier completed reads and reading history. Offline updates stay queued across book changes and restarts. See the [Hardcover guide](docs/hardcover.md).

Fable matches the same way, sends the whole percentage, and keeps the book on Currently Reading or Finished. Sign in with your Fable email and password; only Fable's sign-in tokens are stored. See the [Fable guide](docs/fable.md).

StoryGraph matches the same way, sends the whole percentage, and marks the book currently reading or read. Sign in on StoryGraph's own website inside the app; Boox Tracker keeps the browser session and never sees or remembers your password. See the [StoryGraph guide](docs/storygraph.md).

## Download and get started

[APK downloads and updates → GitHub Releases](https://github.com/otherguy/boox-tracker/releases)

1. Open the APK on your BOOX. Allow installation from that source if Android asks.
2. Allow read-only access to your ebook folder at startup.
3. Open a book in NeoReader, return to its library, then turn Hardcover, Fable, or StoryGraph **On** in Boox Tracker. Approve the Hardcover sign-in code, enter your Fable email and password, or sign in on StoryGraph's page.
4. Press **Sync Now** and check your tracker. Background collection is automatic; offline updates wait until delivery is possible.

Install updates over the existing app to keep settings and Activity history. Development builds install separately as **Boox Tracker (dev)**.

## How it works

Boox Tracker reads NeoReader's saved library information without changing it. It uses saved access times to detect the latest book and reads its progress from the library provider. The allowed ebook folder supplies additional EPUB metadata; other formats use NeoReader's database title, author, and identifiers.

Saved library progress can lag behind page turns or differ from the percentage inside the book. Returning to NeoReader's library can help it save the latest state. Background checks request a fifteen-minute interval; Android and BOOX control when they run, especially during sleep.

The **Sync** tab shows the detected book, saved progress, library count, and each service’s match and delivery state. Tap the book for metadata. **Activity** lists foreground reads, background checks, and deliveries in plain words; tap an entry for a summary and its raw record. Repeated unchanged checks and repeated issues share one entry. See the [device-testing guide](docs/device-testing.md) for help with background behaviour.

## Compatibility and privacy

Requires Android 8.0 or later on a BOOX device with NeoReader. Access to saved reading information depends on the device's firmware. Root, ADB privileges, and broad storage access are not required.

Your ebooks stay on your device. Connected, enabled providers receive the information needed to match and update the detected book; Boox Tracker does not upload your EPUBs or backfill your whole library.

Hardcover and Fable credentials use Android Keystore encryption. Your Fable password is never stored. StoryGraph's sign-in is a browser session kept in the app's private WebView cookie store; Boox Tracker never sees your StoryGraph password. Ebook folder access is read-only and limited to a folder you allow. Logs stay local until you choose to export them. The log is bounded for months of offline use: it keeps at most 1,000 events and nothing older than 30 days, and routine checks from before the last successful sync are removed once they are two days old. Sync state and queued updates are never removed this way. Exports omit full directory paths and credentials, but can include book titles, identifiers, progress, and device information. Review them before sharing.

## Build from source

Use [mise](https://mise.jdx.dev/) and the tools pinned in [.tool-versions](.tool-versions):

```sh
mise install
mise exec -- android --no-metrics sdk install platforms/android-36 build-tools/35.0.0
mise exec -- ./gradlew testDebugUnitTest lintDebug assembleDebug
```

Accept the Android SDK licence prompts when requested. The development APK is `app/build/outputs/apk/debug/app-debug.apk`.

Boox Tracker uses Kotlin, native Android Views/XML, and AndroidX. The [build guide](docs/build-and-release.md) covers signing, diagnostic builds, and checksums.

## Contributing

Help add providers, improve the e-ink interface, fix bugs, or test another BOOX device. Open an [issue](https://github.com/otherguy/boox-tracker/issues) or [pull request](https://github.com/otherguy/boox-tracker/pulls), and read [CONTRIBUTING](CONTRIBUTING.md) for setup and checks.

## Licence

[MIT](LICENSE.md). [Service artwork](docs/service-artwork.md) and trademarks belong to their respective owners.
