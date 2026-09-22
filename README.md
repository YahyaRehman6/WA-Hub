# WA Hub

Several WhatsApp accounts on one Android phone, each in its own isolated session,
switched the way you switch browser tabs.

## How the isolation works

Each account is bound to its own [`androidx.webkit.Profile`][profile]. A Profile
carries a separate cookie store, localStorage, IndexedDB, service worker registry
and geolocation grants — so two accounts cannot see each other's session.

This matters more than it might sound: WhatsApp Web keeps its login in
**IndexedDB**, not in cookies. Clearing cookies between switches — the usual
trick — would not separate the accounts at all.

`WaHost` checks `WebViewFeature.MULTI_PROFILE` at startup and reports the result
to Flutter. On a device whose WebView is too old to support it, the app says so
on the welcome screen rather than quietly sharing one login between accounts.

Requires Android System WebView **112+**. Verified against 151.0.7922.199.

Verified on device, not just in theory: two accounts — one regular WhatsApp and
one WhatsApp Business — stay logged in simultaneously, each with its own service
worker. Shared storage would have evicted the first login when the second one
arrived.

## How switching works

All live sessions are children of a single native `FrameLayout` (`WaStage`),
which is the only Flutter platform view in the app. Switching toggles child
visibility instead of adding and removing views.

That buys three things:

- Background accounts stay laid out and keep running, so their websocket stays
  open and unread counts keep arriving from every account at once.
- Switching is instant — no reload, no lost scroll position.
- No platform-view z-ordering bugs. One view per account would let an off-screen
  WebView paint over the Flutter UI.

A least-recently-used cap (default 4) releases the coldest session when too many
are open. Releasing a session does not touch its storage, so it comes back
logged in.

## Making WhatsApp Web behave

WhatsApp refuses to serve the web app to a mobile browser, and Android's WebView
is missing pieces the page expects. `BootScript` is injected at document start —
before the page's own scripts run — via `addDocumentStartJavaScript`, and:

1. Presents the WebView as desktop Chrome (`navigator.userAgentData`, `platform`,
   `vendor`), while leaving touch signals authentic — a touchscreen laptop is a
   coherent identity, and stripping touch breaks WhatsApp's own gestures.
2. Supplies a `Notification` API, which Android's WebView does not implement at
   all, relayed to real Android notifications with one channel per account.
3. Rescues `blob:` downloads. WhatsApp hands media over as a blob URL, which a
   plain `DownloadListener` cannot read.
4. Optionally pins the layout viewport for the desktop two-pane layout.
5. In the phone layout, keeps the page behaving like a phone app:
   - Body-level dialogs (Media, and the banner inside the Meta AI chat) carry a
     748px floor; `fitDialogs` pins any box wider than the screen to it, and
     Media is rebuilt as a full-height page — title and actions on one row,
     the Media/Docs/Links tab strip on its own row, the grid taking the rest.
   - The bottom bar paints above dialogs (z-index 450 vs 400), so a tab tap
     dismisses an open page first; the back gesture dismisses a page-sized
     dialog before it closes a conversation, and only then goes further back.
   - Hover tooltips (`role="tooltip"`) are hidden — on a touch screen they
     appeared under your finger after every tab tap.
   - The keyboard appears only when a field is tapped. WhatsApp focuses the
     composer on chat open, the You tab's search on tab switch, and "New
     call"'s search on open; a field focused while guarded gets
     `inputmode="none"` (focused, no keyboard) until it is tapped. Blurring
     instead does not work: WhatsApp refocuses the composer on every blur
     (~45 focus events/second measured) and Android keeps the keyboard up.
   - Drawers become pages. "New chat", "Archived" and friends open in an
     overlay slot on the Chats tab; Contact info, Message info and Search open
     in one while a conversation is up (where the fixed conversation used to
     cover them). A slot with content and a Back/Close control is the live
     page: full width, list hidden, lifted above the conversation. Back
     closes, in order: a dialog, a drawer, the conversation, then any other
     tab returns to Chats; only Chats itself leaves the app.
   - Microphone capture needs `MODIFY_AUDIO_SETTINGS` in the manifest —
     without it Chromium fails `getUserMedia` with `NotReadableError` before
     it ever touches `AudioRecord`, and WhatsApp reports "Microphone not
     found" for voice messages.
   - Notifications come from the page: with the page hidden and permission
     granted WhatsApp Web still never constructed a `Notification` (measured),
     so while hidden the chat list is read directly and a row whose unread
     badge grew raises one, closed again once the badge is gone.
   - The emoji/sticker picker (a 560px desktop panel that opened at x=-203)
     and any other positioned box wider than the screen are pinned to it;
     the keyboard is committed once it settles rather than on every animation
     frame; desktop promos ("Get WhatsApp for Windows", "see older messages on
     your phone") are removed.
   - The conversation runs edge to edge: `#main` and its header, footer and
     message pane drop the desktop's 6px gutter, rows drop their 62px/57px
     side padding (bubbles now sit ~10px from either edge, up to 85% wide),
     and message text is 16px.

The bridge is an origin-scoped `addWebMessageListener`, not
`addJavascriptInterface`, so it is reachable only from `https://web.whatsapp.com`.

`MediaDelegate` covers the rest of what the page asks the device for: attaching
files, taking a photo, recording a voice note, sharing location, and saving media.

## Layout

WhatsApp Web is a desktop layout on a phone screen, and its width is not a
matter of taste — it was measured against the real logged-in app over Chrome
DevTools:

| Viewport | Content extent | Result  |
| -------- | -------------- | ------- |
| 480–720  | 748px          | clipped |
| 800      | 800px          | fits    |
| 1024     | 1024px         | fits    |

The layout has an intrinsic floor near **748px** — the chat list holds ~336px
and the conversation pane ~412px — with no declared `min-width` anywhere. Below
that, content is cut off on the right by an ancestor with `overflow: hidden`,
and `scrollWidth` still reports no overflow, so nothing scrolls to reveal it.
A "device width" option is therefore not possible, however natural it sounds.

The page is zoomed to fit the screen afterwards, so a narrower viewport means
larger text. Each account picks a point on that trade-off — Compact 800,
Balanced 900, Wide 1024 — plus a text size. 800 is the default because it is
the largest text WhatsApp will render without cutting itself off.

## Rendering

The stage uses **texture-layer composition** (`initSurfaceAndroidView`), not
hybrid composition. With hybrid composition the WebView lived in the Android
view hierarchy, so every frame was composited twice: once by Flutter's Impeller
pass and again by Android's Skia pipeline. `dumpsys gfxinfo` measured it:

| | Hybrid composition | Texture layer |
| --- | --- | --- |
| Janky frames | 27.7% | Android pipeline idle |
| Slow issue draw commands | 540 | — |
| GPU 50th percentile | 7ms | 7ms |
| Android frames rendered | 1962 | ~1 |

The GPU was never the bottleneck at 7ms a frame; the cost was in *issuing* draw
commands twice. Moving composition into Flutter removed the second pass.

The trade-off is text input — hybrid composition has historically been the safer
mode for a WebView's soft keyboard. Typing is the whole point of this app, so
`WaStage._useTextureLayer` exists as a single flag to switch back.

## Linking an account

WhatsApp shows a QR code you cannot scan with the phone displaying it. Use
**Link with phone number** instead, then enter the code in WhatsApp under
Settings → Linked devices. The app says this in the new-account sheet.

## Layout of the code

```
lib/
  design/tokens.dart          Skin, type scale, motion, accent palette
  models/profile.dart         WaProfile, WaLayout, WaRuntime
  state/profiles_controller.dart  Account list, active account, live state
  native/wa_bridge.dart       Method channel to WaHost
  native/wa_stage.dart        The single platform view
  ui/                         shell, rail, switcher, editor, welcome, pieces

android/app/src/main/kotlin/com/algotix/wa_hub/
  WaHost.kt                   Session registry, profile binding, WebView clients
  WaStage.kt                  The native stage platform view
  BootScript.kt               Injected document-start JavaScript
  MediaDelegate.kt            File chooser, permissions, downloads
  HostNotifications.kt        Per-account notification channels
  MainActivity.kt             Wiring, activity results, notification intents
```

## Icon

Generated from `whatsapp_profiles.png` into a full adaptive set rather than a
single bitmap: a black background layer, the mark as the foreground kept inside
the 66dp safe zone, and the same mark again as the `monochrome` layer so Android
13+ themed icons tint correctly. Legacy square and round bitmaps cover launchers
that ignore adaptive icons, and the mark is reused for the notification
silhouette and the launch screen.

Regenerate with the extraction described in the git history: the source has a
faint rim around its rounded square, so the glyph is separated by an alpha ramp
(luminance 150→210) plus an 8% border clear, not a hard threshold.

## Running it

```sh
flutter pub get
flutter run
```

Release build:

```sh
flutter build apk --release
```

The release build is currently signed with the debug key (Flutter's default). Add
a real signing config before distributing it.

## Calls

Calls do not work, and the buttons say so. WhatsApp's voip stack runs its WASM
in a worker with shared memory, which a browser only permits on a cross-origin
isolated page; its gate is `SharedArrayBuffer`, then `Atomics`, then
`RTCPeerConnection`, and a WebView has only the last two.

Measured, in order:

- Borrowing a real `SharedArrayBuffer` constructor from WebAssembly shared
  memory (`new WebAssembly.Memory({shared:true}).buffer.constructor`) does
  enable the buttons — and every call then fails with "Something went wrong",
  because the shared memory still cannot cross into the worker.
- Re-serving WhatsApp's document with `Cross-Origin-Opener-Policy` and
  `Cross-Origin-Embedder-Policy` through `shouldInterceptRequest` does not
  isolate the page: WhatsApp's own service worker answers the navigation
  (`workerStart` 7.8ms), and intercepting the worker's fetch through the
  per-profile `ServiceWorkerController` did not change `crossOriginIsolated`.

So the honest state is the one a mobile browser shows: "Your browser doesn't
support calls". A button that looks alive and always fails is worse.

## Known limits

- Sessions run only while the app is alive. Android may reclaim the process in
  the background, which drops notifications until the app is reopened. A
  foreground service would fix this and is not implemented. Cookies are flushed
  to disk whenever the app leaves the foreground, so a kill costs no logins.
- Blob downloads are capped at 32 MB, because they cross the bridge base64-encoded.
- Portrait only.

[profile]: https://developer.android.com/reference/androidx/webkit/Profile
