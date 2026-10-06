# Approved visual direction

The user selected the revised first concept: the first layout, the second concept's amber warning triangle, and coloured service icons. Other generated concepts were alternatives. The mockup is a visual direction, not proof of implemented services or signed-in accounts.

![Approved Reading Sync mockup](reading-sync-approved-mockup.png)

## Layout

The intended product has a white background, large black Boox Tracker title, About at top right, Sync/Activity tabs, and a strong black underline under the active tab. A concise status and last-check area includes Sync now. Flat service rows use horizontal separators, an icon, bold name, status text, and toggle; Reconnect appears in the affected row. A latest-progress area shows the book, percentage, and NeoReader observation timestamp.

The implemented 0.3.0 layout uses Sync/Activity. The header shows database title/progress, library count, and query time with a green circular check or amber warning triangle, plus black Sync Now. Tap the book for bordered metadata. Services show exact/book-only/error matching separately from delivery; coming services remain disabled. No book selector, standalone Read Now, lower diagnostic/progress sections, background toggle, or observation controls exist. About is bordered and includes folder replacement; Device Information is in Activity.

The metadata popup uses bold Title Case labels, with ISBN and ASIN uppercase. Show the full ISBN and existing identifier tags from the same database/ebook reader used for sync; omit absent service-tag rows. NeoReader Database and Progress State show OK only when their checks pass, otherwise the specific state or error. Last Access is NeoReader's saved timestamp; Read At is our query timestamp. Format both with the device's date/time settings, retaining raw unsupported timestamps and explicit missing/unreadable states. Identifier file reads must stay off the main thread. Cancelling About's folder replacement keeps the existing grant when it is still readable; missing or revoked access still blocks the app.

The shared reader is the single tag allowlist. UI labels do not filter tags separately. It accepts ISBN, ASIN, Goodreads, Hardcover edition/book/slug, and explicit `storygraph:`, `fable:`, `margins:` identifiers. The last three accept bounded opaque IDs for display only; no future tracker lookup or send is implemented. Unknown tags and unrelated metadata are discarded before display. ASIN remains useful for the implemented Hardcover edition lookup.

The approved mockup's service states are illustrative. Never add fabricated reading/account records to a distributable APK or claim a preview image is physical evidence. Signed emulator screenshots are under dist/screenshots; NeoReader is absent on that emulator.

## Colour and icons

| Element | Approved concept treatment |
| --- | --- |
| Warning | Amber triangle with black outline/exclamation mark |
| Hardcover | Dark teal book outline |
| Goodreads | Dark brown lowercase g |
| StoryGraph | Deep indigo/purple books or bars |
| Fable | Dark green leaf |
| Margins | Dark slate-blue document |

These are concept treatments, not a verified official brand-asset collection or complete design-token specification. Essential text and switches stay black/white. Labels, icon shapes, and switch position must communicate meaning without colour.

The mockup's service rows illustrated Hardcover and Goodreads enabled/synced, StoryGraph enabled with expired sign-in, Fable off/unconnected, and Margins unavailable with a disabled off toggle. Its book, percentage, and times were also illustrative. Do not present these as real accounts or ship them as real records.

## E-ink interaction

- Use strong contrast, readable black body text, flat surfaces, and clear separators. Essential text must not be pale.
- Keep touch targets at least 48 × 48 dp where feasible; the current primary buttons use 56 dp minimum height. Colour must remain supplementary on monochrome devices.
- Keep transitions static. Avoid spinners, animated switches, fading, marquees, and rapidly updating timers.
- Avoid gradients, shadows, decorative charts, unnecessary cover imagery, and large decorative filled regions. Retain black action buttons from the approved concept.
- Avoid unnecessary redraws and unexpected log movement while the user reads it. Further UI work must preserve useful diagnostic evidence and state.

Prior catalogue research cited 300 PPI monochrome and 150 PPI colour rendering for Go Color 7. This was a design lead, not a measured property or generation identification of the user's device. Layout must work on both monochrome and colour e-ink.

References for later validation: [BOOX Go 7 series](https://shop.boox.com/products/go7), [BOOX Go Color 7](https://shop.boox.com/products/gocolor7), and [Android accessibility](https://developer.android.com/codelabs/jetpack-compose-accessibility). The implemented toolkit is native Views/XML; the accessibility reference does not require migration to Compose.

## Historical 0.2.1 service layout: 2026-10-06

The 0.2.1 UI uses the approved title/About header, static active-tab underline, status/action row, separated service rows, and latest saved progress summary. Tabs remain Diagnostics and Activity. Hardcover displays real connection and sync state, with a static opt-in switch. Turning it On enables an existing connection or starts sign-in; turning it Off cancels sign-in or stops sends. Disconnect remains in setup. Goodreads, StoryGraph, Fable, and Margins display Coming soon with disabled off switches. These rows do not imply API access or implemented connections.

Service artwork is downloaded, unmodified Apple App Store artwork, bundled locally. Its colours and shapes differ from the concept icons. In particular, the published StoryGraph artwork is black on white; it is not recoloured. See [artwork sources and rights](../docs/service-artwork.md).

Read now, provider outcome/count, expandable raw metadata and device information, EPUB folder setup, observation, and Background checks remain below the progress summary. Activity retains recycled rows and lazy details. No source-book selector, account fixtures, additional service integrations, or publication has been added. Service switches sit beside the service details; on narrow screens with large fonts, they move below the text to keep names readable. The header warns only for enabled services that need attention or a current NeoReader read issue. Disabled services do not cause a warning.
