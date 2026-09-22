package com.algotix.wa_hub

/**
 * JavaScript injected into every web.whatsapp.com document *before* the page's own
 * scripts run (androidx.webkit DOCUMENT_START_SCRIPT).
 *
 * It does four jobs:
 *   1. Presents the WebView as desktop Chrome, so WhatsApp serves the web app
 *      instead of the "get the mobile app" wall. Touch signals are left authentic —
 *      a touchscreen laptop is a coherent identity, and stripping them breaks
 *      WhatsApp's own gesture handling.
 *   2. Supplies a Notification API, which Android's WebView does not implement at
 *      all, and relays it to the host so real Android notifications appear.
 *   3. Rescues `blob:` downloads (how WhatsApp hands over media), which a plain
 *      DownloadListener cannot read.
 *   4. Optionally pins the layout viewport for the desktop two-pane layout.
 *
 * Messages go out over a WEB_MESSAGE_LISTENER bound to the WhatsApp origin only,
 * rather than addJavascriptInterface, which would expose the bridge to every frame.
 */
object BootScript {

    const val MAX_BLOB_BYTES = 32 * 1024 * 1024

    fun build(chromeMajor: String, chromeFull: String, forcedWidth: Int): String =
        TEMPLATE
            .replace("%CHROME_MAJOR%", chromeMajor)
            .replace("%CHROME_FULL%", chromeFull)
            .replace("%FORCED_WIDTH%", forcedWidth.toString())
            .replace("%MAX_BLOB%", MAX_BLOB_BYTES.toString())

    private val TEMPLATE = """
(function () {
  'use strict';
  if (window.__waHostBooted) { return; }
  window.__waHostBooted = true;

  var HOST = window.__WAHOST;
  var send = function (payload) {
    try { HOST.postMessage(JSON.stringify(payload)); } catch (e) {}
  };
  var define = function (target, prop, value) {
    try {
      Object.defineProperty(target, prop, {
        get: function () { return value; },
        configurable: true
      });
    } catch (e) {}
  };

  /* ---- 1. Present as desktop Chrome --------------------------------- */

  define(navigator, 'platform', 'Win32');
  define(navigator, 'vendor', 'Google Inc.');

  var brands = [
    { brand: 'Not(A:Brand', version: '24' },
    { brand: 'Chromium', version: '%CHROME_MAJOR%' },
    { brand: 'Google Chrome', version: '%CHROME_MAJOR%' }
  ];
  define(navigator, 'userAgentData', {
    brands: brands,
    mobile: false,
    platform: 'Windows',
    getHighEntropyValues: function () {
      return Promise.resolve({
        architecture: 'x86',
        bitness: '64',
        brands: brands,
        fullVersionList: brands,
        mobile: false,
        model: '',
        platform: 'Windows',
        platformVersion: '15.0.0',
        uaFullVersion: '%CHROME_FULL%',
        wow64: false
      });
    },
    toJSON: function () {
      return { brands: brands, mobile: false, platform: 'Windows' };
    }
  });

  /* ---- 2. Notification API ------------------------------------------ */

  var live = {};
  var seq = 0;

  window.__waNoted = {};
  function HostNotification(title, options) {
    options = options || {};
    /* A tag means "this replaces the last one with the same tag" — without
       it every message stacked a new Android notification. */
    var id = options.tag ? ('tag:' + String(options.tag)) : ('n' + (++seq));
    this.title = String(title == null ? '' : title);
    this.body = String(options.body == null ? '' : options.body);
    this.tag = String(options.tag == null ? '' : options.tag);
    this.icon = String(options.icon == null ? '' : options.icon);
    this.data = options.data;
    this.onclick = null;
    this.onclose = null;
    this.onerror = null;
    this.onshow = null;
    this.__id = id;
    live[id] = this;
    /* WhatsApp raises its own notification for some messages. The unread scan
       must not raise a second one for the same chat. */
    try { window.__waNoted[this.title] = Date.now(); } catch (e) {}
    send({
      kind: 'notification',
      id: id,
      title: this.title,
      body: this.body,
      tag: this.tag,
      silent: !!options.silent
    });
    var self = this;
    setTimeout(function () {
      if (typeof self.onshow === 'function') { self.onshow(); }
    }, 0);
  }

  HostNotification.prototype.close = function () {
    if (!live[this.__id]) { return; }
    delete live[this.__id];
    send({ kind: 'notificationClose', id: this.__id });
    if (typeof this.onclose === 'function') { this.onclose(); }
  };
  HostNotification.prototype.addEventListener = function (type, fn) {
    if (type === 'click') { this.onclick = fn; }
    else if (type === 'close') { this.onclose = fn; }
    else if (type === 'show') { this.onshow = fn; }
    else if (type === 'error') { this.onerror = fn; }
  };
  HostNotification.prototype.removeEventListener = function (type) {
    if (type === 'click') { this.onclick = null; }
    else if (type === 'close') { this.onclose = null; }
    else if (type === 'show') { this.onshow = null; }
    else if (type === 'error') { this.onerror = null; }
  };
  HostNotification.requestPermission = function (cb) {
    var p = Promise.resolve('granted');
    if (typeof cb === 'function') { p.then(cb); }
    return p;
  };
  define(HostNotification, 'permission', 'granted');
  HostNotification.maxActions = 2;
  window.Notification = HostNotification;

  /* Desktop Chrome exposes window.chrome; the WebView does not, and WhatsApp
     reads its absence as "not Chrome" when it decides whether this browser
     can place calls. */
  try {
    if (!window.chrome) {
      window.chrome = {
        app: {
          isInstalled: false,
          getDetails: function () { return null; },
          getIsInstalled: function () { return false; },
          runningState: function () { return 'cannot_run'; },
          InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
          RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
        },
        runtime: { OnInstalledReason: {}, PlatformOs: {}, connect: function () {}, sendMessage: function () {} },
        csi: function () { return {}; },
        loadTimes: function () { return {}; }
      };
    }
  } catch (e) {}

  /* The host calls these when the user taps or dismisses the Android notification. */
  window.__waNotificationClicked = function (id) {
    var n = live[id];
    if (n && typeof n.onclick === 'function') {
      try { n.onclick({ preventDefault: function () {}, notification: n }); } catch (e) {}
    }
    try { window.focus(); } catch (e) {}
  };
  window.__waNotificationDismissed = function (id) {
    var n = live[id];
    if (!n) { return; }
    delete live[id];
    if (typeof n.onclose === 'function') { try { n.onclose(); } catch (e) {} }
  };

  /* WhatsApp may route through the service worker instead. */
  try {
    if (window.ServiceWorkerRegistration && ServiceWorkerRegistration.prototype) {
      ServiceWorkerRegistration.prototype.showNotification = function (title, options) {
        new HostNotification(title, options);
        return Promise.resolve();
      };
      ServiceWorkerRegistration.prototype.getNotifications = function () {
        return Promise.resolve([]);
      };
    }
  } catch (e) {}

  try {
    if (navigator.permissions && navigator.permissions.query) {
      var realQuery = navigator.permissions.query.bind(navigator.permissions);
      navigator.permissions.query = function (desc) {
        if (desc && desc.name === 'notifications') {
          return Promise.resolve({
            state: 'granted', status: 'granted',
            onchange: null,
            addEventListener: function () {}, removeEventListener: function () {}
          });
        }
        return realQuery(desc);
      };
    }
  } catch (e) {}

  /* ---- 3. blob: downloads ------------------------------------------- */

  document.addEventListener('click', function (ev) {
    var node = ev.target;
    var anchor = null;
    while (node && node !== document) {
      if (node.tagName === 'A' && node.hasAttribute('download')) { anchor = node; break; }
      node = node.parentNode;
    }
    if (!anchor) { return; }
    var href = anchor.getAttribute('href') || '';
    if (href.lastIndexOf('blob:', 0) !== 0) { return; }

    ev.preventDefault();
    ev.stopPropagation();

    var name = anchor.getAttribute('download') || 'whatsapp-file';
    fetch(href).then(function (res) {
      return res.blob();
    }).then(function (blob) {
      if (blob.size > %MAX_BLOB%) {
        send({ kind: 'downloadTooLarge', name: name, size: blob.size });
        return;
      }
      var reader = new FileReader();
      reader.onload = function () {
        var raw = String(reader.result);
        var comma = raw.indexOf(',');
        send({
          kind: 'download',
          name: name,
          mime: blob.type || 'application/octet-stream',
          base64: comma < 0 ? '' : raw.slice(comma + 1)
        });
      };
      reader.onerror = function () {
        send({ kind: 'downloadFailed', name: name });
      };
      reader.readAsDataURL(blob);
    }).catch(function () {
      send({ kind: 'downloadFailed', name: name });
    });
  }, true);

  /* ---- 4. Layout ------------------------------------------------------ */

  var forced = %FORCED_WIDTH%;
  var phone = (forced === 0);

  /* Zoom is allowed only before the account is linked.
     The link screen carries a QR code and an 8-digit code worth magnifying;
     once the app itself is up, a stray pinch just leaves it at an arbitrary
     scale with no affordance to get back, so it is pinned. */
  var isLinked = function () {
    return !!(document.getElementById('pane-side') || document.getElementById('side'));
  };
  var viewportFor = function (linked) {
    if (!linked) {
      return phone
        ? 'width=device-width, initial-scale=1, maximum-scale=4, user-scalable=yes'
        : 'width=' + forced + ', user-scalable=yes';
    }
    return phone
      ? 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'
      : 'width=' + forced + ', user-scalable=no';
  };

  var pinViewport = function () {
    var head = document.head;
    if (!head) { return; }
    var tag = head.querySelector('meta[name="viewport"]');
    if (!tag) {
      tag = document.createElement('meta');
      tag.setAttribute('name', 'viewport');
      head.appendChild(tag);
    }
    var wanted = viewportFor(isLinked());
    if (tag.getAttribute('content') !== wanted) {
      tag.setAttribute('content', wanted);
    }
  };

  /* ---- 5. Phone shell --------------------------------------------------
     WhatsApp Web has no mobile breakpoint: it is a three-column desktop
     layout (nav rail | chat list | conversation) that simply clips when the
     viewport is narrow. This rebuilds it as a phone would have it — the list
     owns the screen, a conversation covers it, and the rail becomes a bottom
     bar that gets out of the way inside a chat.

     The columns are found structurally (#side's parent is the list column,
     its parent is the flex frame) because WhatsApp's class names are
     obfuscated and change between releases. Only the element ids #side,
     #pane-side and #main are stable, and those are what this leans on. */

  var MOBILE_CSS = [
    /* Android's WebView paints a blue tap flash on every tapped element,
       and WhatsApp draws a focus ring on the control that was just tapped;
       neither exists in the phone app. */
    '*{-webkit-tap-highlight-color:transparent !important;}',
    '*:focus{outline:none !important;box-shadow:none !important;}',
    '*:focus-visible{outline:none !important;}',
    'html,body{overflow:hidden !important;overscroll-behavior:none !important;',
    '  min-width:0 !important;}',
    /* Measured: an ancestor of the app frame carries a ~748px minimum, so
       width:100% resolved to 748 inside a 392px viewport and everything ran off
       the right edge. Releasing the floor on the whole ancestor chain is what
       makes the layout actually fit the phone. */
    '[data-wa-anc]{min-width:0 !important;width:100% !important;max-width:100vw !important;}',
    '[data-wa="frame"]{min-width:0 !important;width:100vw !important;max-width:100vw !important;}',
    /* No sideways movement anywhere. pan-y keeps vertical scrolling and pinch
       zoom and only takes horizontal panning from the browser, so WhatsApp's
       own swipe-to-reply still receives its touch events. */
    '#app,[data-wa="list"],[data-wa="convo"],[data-wa="overlay"],#side,#pane-side,#main{',
    '  overflow-x:hidden !important;overscroll-behavior-x:none !important;',
    '  touch-action:pan-y pinch-zoom !important;}',
    /* Scrollbars are a desktop affordance; on a phone the gutter is just a
       dead strip down the right edge. */
    '#main ::-webkit-scrollbar,#pane-side ::-webkit-scrollbar{width:0 !important;height:0 !important;}',

    /* --- nav rail becomes the bottom tab bar --- */
    '[data-wa="rail"]{position:fixed !important;left:0 !important;right:0 !important;',
    '  bottom:0 !important;top:auto !important;width:100vw !important;',
    '  max-width:100vw !important;height:64px !important;min-height:64px !important;',
    '  max-height:64px !important;min-width:0 !important;display:flex !important;',
    '  flex-direction:row !important;align-items:center !important;',
    '  justify-content:space-around !important;padding:0 !important;margin:0 !important;',
    '  z-index:450 !important;border:none !important;overflow:hidden !important;}',
    /* The buttons sit three wrappers deep. display:contents lifts those
       wrappers out of the box tree so the icons themselves become the bar's
       flex items and space-around can distribute them evenly — measured at
       exactly 49px between every pair. */
    '[data-wa="rail"] > *,[data-wa="rail"] > * > *,',
    '[data-wa="rail"] > * > * > *{display:contents !important;}',
    '[data-wa="rail"] hr{display:none !important;}',
    /* The tab icons are drawn for a 64px desktop rail; on a phone bar they
       read as too small, so they are scaled up within the wider bar. */
    '[data-wa="rail"] button,[data-wa="rail"] [role="button"]{',
    '  transform:scale(1.22) !important;transform-origin:center !important;}',

    /* --- the one primary column owns the screen --- */
    '[data-wa="list"]{flex:0 0 100% !important;width:100% !important;',
    '  max-width:100% !important;min-width:0 !important;',
    '  height:calc(100% - 64px) !important;}',
    '[data-wa="convo"]{position:fixed !important;top:0 !important;left:0 !important;',
    '  right:0 !important;bottom:0 !important;width:100vw !important;',
    '  height:100% !important;max-width:none !important;z-index:400 !important;}',
    '[data-wa="overlay"]{left:0 !important;right:0 !important;width:100% !important;',
    '  max-width:none !important;}',
    /* Hit-tested: a transparent skeleton pane nested inside an overlay layer
       drew the faint vertical line where the nav rail used to be. */
    '[data-wa="overlay"] > *{border-left:none !important;border-right:none !important;}',
    /* A tab other than Chats renders into an absolutely positioned panel slot
       that is only 177px wide and stacks *above* the chat list. On a desktop
       column the two sit side by side; at 392px they collide, which is why
       Calls/Status/Channels showed a cramped panel with the list bleeding
       through. The live panel takes the screen and the list stands down. */
    /* The slot also carries margin-left:64px, reserving space for the nav rail
       that is now a bottom bar — which left a dead strip down the left and
       pushed the panel's right edge off screen. */
    /* Every slot gets the phone geometry up front, so a drawer that opens
       on the Chats tab ("New chat", "Archived") never paints in the 177px
       desktop slot while it waits for the classifier's next pass. */
    '[data-wa="overlay"] > *{width:100vw !important;max-width:100vw !important;',
    '  min-width:0 !important;flex:0 0 100% !important;margin:0 !important;',
    '  left:0 !important;right:0 !important;}',
    /* WhatsApp keeps panels mounted after you leave them, so an idle one would
       keep painting over the chat list. */
    '[data-wa="panel-idle"]{display:none !important;}',
    'body.wa-panel-open [data-wa="list"]{display:none !important;}',
    /* Contact info, Message info and Search open as overlay slots while a
       conversation is up; the conversation is fixed at z-index 500 and the
       overlay's stacking context sits at 300, so the drawer was invisible.
       While a drawer is live over a conversation the overlay is lifted above
       it and the drawer takes the screen. */
    /* Measured: the media viewer is fixed at z-index 500 in the same
       stacking context, so the conversation sits at 400 and a lifted drawer
       at 420 — under the viewer, over the conversation. */
    'body.wa-chat-open.wa-panel-open [data-wa="overlay"]{z-index:420 !important;}',
    'body.wa-chat-open [data-wa="panel"]{position:fixed !important;top:0 !important;left:0 !important;',
    '  width:100vw !important;height:100% !important;z-index:420 !important;display:block !important;}',
    'body:not(.wa-chat-open) [data-wa="convo"]{display:none !important;}',
    'body.wa-chat-open [data-wa="rail"]{display:none !important;}',
    'body.wa-chat-open [data-wa="list"]{height:100% !important;}',

    /* --- conversation header --- */
    /* Measured: the name block got 123px while four action icons took 216px,
       truncating the contact to "Taha B...". The mobile app keeps search in
       the overflow menu, so dropping it here returns that room to the name. */
    '#main header [aria-label*="Search" i],#main header [title*="Search" i]{display:none !important;}',
    '#main header > div:first-child{flex:1 1 auto !important;min-width:0 !important;}',
    '#main header div:has(> button[aria-label*="Search" i]),#main header span:has(> button[aria-label*="Search" i]){display:none !important;}',
    /* The conversation itself. Measured on the phone: #main renders at
       calc(100% - 6px) (a desktop resize gutter) and its header, footer and
       message pane inherit it through width:100% — even with #main forced
       wider, percentages still resolve against that calc(), so the children
       are sized from the viewport; each message row carries
       the desktop's 62px/57px side padding so a reply sat 116px from the
       right edge, and message text is 14.2px. The phone app runs bubbles to
       ~10px from either edge, up to ~85% wide, at 16px. */
    '#main > *{width:100vw !important;max-width:100vw !important;}',
    '#main .focusable-list-item{padding-left:12px !important;padding-right:10px !important;}',
    '#main .focusable-list-item > div{max-width:85% !important;}',
    '#main [role="row"] .selectable-text{font-size:16px !important;line-height:22px !important;}',
    /* Hover tooltips ("Calls", "Status"…) are for a mouse; on a touch screen
       they pop up under your finger after every tap on the bottom bar. */
    '[role="tooltip"]{display:none !important;}',
    /* No calling. WhatsApp's call engine passes shared memory between the page
       and its worker, which a browser only allows on a cross-origin isolated
       page; whatsapp.com does not ask for isolation and an app cannot grant it
       on its behalf. Measured: the engine loads, its init RPC never answers and
       the call rings for ever. The buttons go rather than stand there promising
       something that cannot happen. */
    '#main header button[aria-label*="call" i],',
    '#main header button[aria-label*="browser doesn" i],',
    '#main header button[aria-label*="went wrong" i]{display:none !important;}',
    '#main header span:has(> button[aria-label*="call" i]),',
    '#main header span:has(> button[aria-label*="browser doesn" i]),',
    '#main header span:has(> button[aria-label*="went wrong" i]){display:none !important;}',
    /* The media viewer's header holds a sender block and a toolbar block of
       eleven 40px buttons side by side; at 393px the toolbar was a 520px
       block starting at x=-145 with four buttons off screen. Pinch does the
       zooming on a phone, the sender block gives way, and the remaining nine
       icons spread across one line. The row is the one div with 8+ direct
       children holding those buttons; its ancestors hold the same buttons
       but only one or two children, and must not be restyled. */
    'div:has(> div:nth-child(8)):has(> div button[aria-label="Download"]):has(> div button[aria-label="Close"]){width:100% !important;max-width:100% !important;margin:0 !important;padding:0 !important;box-sizing:border-box !important;justify-content:space-around !important;gap:0 !important;}',
    'div:has(> div:nth-child(8)):has(> div button[aria-label="Close"]) > div:has(button[aria-label="Zoom in"]),',
    'div:has(> div:nth-child(8)):has(> div button[aria-label="Close"]) > div:has(button[aria-label="Zoom out"]){display:none !important;}',
    'div:has(> div > div:nth-child(8)):has(> div > div button[aria-label="Close"]){flex:1 1 auto !important;min-width:0 !important;width:100% !important;margin:0 !important;}',
    'div:has(> div > div > div:nth-child(8)):has(> div > div > div button[aria-label="Close"]) > div:first-child:not(:has(button[aria-label="Close"])){display:none !important;}',
    /* Body-level dialogs carry a 748px floor; release it before first paint so
       the tick's fitDialogs only has the inner boxes left to pin. */
    '[role="dialog"],[aria-modal="true"]{min-width:0 !important;max-width:100vw !important;box-sizing:border-box !important;}',
    /* Media is the one dialog with a tab strip. Measured on the phone: the
       title, the three tabs and four action buttons shared a single 64px row
       (title wrapped to two lines, tabs sat under the buttons at x=197..261),
       and the box floated centred at 80% height with a scrim strip above and
       below. Rebuilt as a phone page: full height, title + actions on one
       row, the tab strip on its own row, the grid taking the rest. */
    '[role="dialog"]:has([role="tablist"]) > div{height:100% !important;max-height:none !important;min-height:0 !important;border-radius:0 !important;}',
    '[role="dialog"]:has([role="tablist"]) > div > div{height:100% !important;}',
    '[role="dialog"] div:has(> div > div > [role="tablist"]){display:flex !important;flex-direction:column !important;height:100% !important;max-height:none !important;}',
    '[role="dialog"] div:has(> div > div > [role="tablist"]) > *{flex:0 0 auto !important;}',
    '[role="dialog"] div:has(> div > div > [role="tablist"]) > *:last-child{flex:1 1 0 !important;height:auto !important;min-height:0 !important;}',
    '[role="dialog"] div:has(> div > [role="tablist"]){flex-wrap:wrap !important;height:auto !important;}',
    '[role="dialog"] div:has(> div > [role="tablist"]) > div:first-child{flex:1 1 0 !important;min-width:0 !important;}',
    '[role="dialog"] div:has(> div > [role="tablist"]) > div:last-child{flex:0 0 auto !important;width:auto !important;}',
    '[role="dialog"] div:has(> [role="tablist"]){order:3 !important;flex:0 0 100% !important;width:100% !important;margin:0 !important;}',
    '[role="dialog"] [role="tablist"]{display:flex !important;width:100% !important;}',
    '[role="dialog"] [role="tab"]{flex:1 1 0 !important;min-width:0 !important;}'
  ].join('');

  var styleEl = null;
  var ensureStyle = function () {
    if (styleEl && styleEl.isConnected) { return; }
    if (!document.head) { return; }
    styleEl = document.getElementById('wa-phone-shell');
    if (!styleEl) {
      styleEl = document.createElement('style');
      styleEl.id = 'wa-phone-shell';
      styleEl.textContent = MOBILE_CSS;
      document.head.appendChild(styleEl);
    }
  };

  var frameEl = null;
  /* Tagging is per-child rather than keyed on #side, because #side only exists
     on the Chats tab. Keying on it meant every other tab (Calls, Status,
     Channels, Communities, profile) kept stale tags, rendered as a narrow
     column and let the chat list show through behind it.

     The primary column is identified by a percentage flex-basis with no grow —
     that is the signature of the desktop second column, and it holds whichever
     tab is showing. Zero-width leftovers have an auto basis and must stay
     untagged, or several columns each become 100% wide and stack to ~1178px. */
  var tagPanes = function () {
    if (!frameEl || !frameEl.isConnected) {
      var side = document.getElementById('side');
      if (!side || !side.parentElement || !side.parentElement.parentElement) { return; }
      frameEl = side.parentElement.parentElement;
      frameEl.setAttribute('data-wa', 'frame');
      for (var a = frameEl.parentElement; a && a !== document.documentElement; a = a.parentElement) {
        a.setAttribute('data-wa-anc', '1');
      }
    }
    for (var i = 0; i < frameEl.children.length; i++) {
      var ch = frameEl.children[i];
      if (ch.getAttribute('data-wa')) { continue; }
      if (ch.tagName === 'HEADER') { ch.setAttribute('data-wa', 'rail'); continue; }
      var cs = getComputedStyle(ch);
      if (cs.position === 'absolute') { ch.setAttribute('data-wa', 'overlay'); continue; }
      if (parseFloat(cs.flexGrow) >= 1) { ch.setAttribute('data-wa', 'convo'); continue; }
      if (cs.flexBasis && cs.flexBasis.indexOf('%') > 0) { ch.setAttribute('data-wa', 'list'); }
    }
  };

  /* WhatsApp focuses the composer the moment a conversation opens, which on a
     phone throws the keyboard up over the messages you were trying to read.
     The keyboard should appear when you tap a field, and not before. The same
     auto-focus happens on the You tab (its search box), on "New call" and
     when the Media page opens.

     Blurring the field does not work: WhatsApp Web refocuses the composer
     whenever it loses focus with nothing else focused (measured: ~45 focus
     events a second against a blur guard), and Android keeps the keyboard up
     after a blur anyway. Instead a guarded field keeps its focus and gets
     inputmode="none" — Chrome's own "focused, no virtual keyboard" state —
     and the first tap on the field restores its inputmode; that tap is what
     raises the keyboard. */
  var isField = function (a) {
    return !!a && (a.isContentEditable || a.tagName === 'INPUT' || a.tagName === 'TEXTAREA');
  };
  /* Returns the editing host, not the paragraph inside it: every descendant
     of a contenteditable reports isContentEditable, and the shield lives on
     the host — a tap on the composer's text lands on a <p> inside it. */
  var fieldAt = function (t) {
    var host = null;
    while (t && t !== document.body) {
      if (t.hasAttribute && t.hasAttribute('data-wa-im')) { return t; }
      if (isField(t)) { host = t; }
      t = t.parentElement;
    }
    return host;
  };
  /* A tap on a control that exists to open a field (the Search icons) means
     the keyboard is wanted for the field that follows. */
  var opensField = function (t) {
    while (t && t !== document.body) {
      if (t.getAttribute('role') === 'textbox') { return true; }
      if (/search/i.test(t.getAttribute('aria-label') || '')) { return true; }
      t = t.parentElement;
    }
    return false;
  };
  var chatGuard = false;   /* set while a conversation is opening */
  var guardUntil = 0;      /* any other tap guards the next 900ms */
  var guarded = function () { return chatGuard || Date.now() < guardUntil; };
  var shield = function (a) {
    if (!isField(a) || a.hasAttribute('data-wa-im')) { return; }
    a.setAttribute('data-wa-im', a.getAttribute('inputmode') || '');
    a.setAttribute('inputmode', 'none');
  };
  var unshield = function (a) {
    if (!a || !a.hasAttribute('data-wa-im')) { return; }
    var was = a.getAttribute('data-wa-im');
    if (was) { a.setAttribute('inputmode', was); } else { a.removeAttribute('inputmode'); }
    a.removeAttribute('data-wa-im');
  };
  var unshieldAll = function () {
    var all = document.querySelectorAll('[data-wa-im]');
    for (var i = 0; i < all.length; i++) { unshield(all[i]); }
  };
  document.addEventListener('focusin', function (e) {
    if (!guarded()) { return; }
    shield(e.target);
    /* A picker's search box takes focus inside the tap that opened the picker,
       so Chrome has already asked for the keyboard and inputmode alone will not
       send it away — that field is let go of. The composer is never blurred:
       WhatsApp only grabs it back, which is the loop this shield replaced. */
    var el = e.target;
    if (isField(el) && !(el.closest && el.closest('#main footer'))) {
      setTimeout(function () {
        if (document.activeElement === el) { try { el.blur(); } catch (x) {} }
      }, 0);
    }
  }, true);
  /* A tap on the composer usually lands on its placeholder or padding, not
     on the contenteditable itself, so a shielded field whose box contains
     the touch point counts as the tapped field. */
  var shieldedAt = function (e) {
    var p = e.touches && e.touches[0] ? e.touches[0] : e;
    if (typeof p.clientX !== 'number') { return null; }
    var all = document.querySelectorAll('[data-wa-im]');
    for (var i = 0; i < all.length; i++) {
      var r = all[i].getBoundingClientRect();
      if (p.clientX >= r.left - 8 && p.clientX <= r.right + 8 &&
          p.clientY >= r.top - 8 && p.clientY <= r.bottom + 8) { return all[i]; }
    }
    return null;
  };
  var noteUserIntent = function (e) {
    var f = fieldAt(e.target) || shieldedAt(e);
    if (f || opensField(e.target)) {
      chatGuard = false; guardUntil = 0;
      if (f) {
        /* The keyboard has to come up on this very tap, so the shield is off
           and the field refocused inside the tap's own activation window —
           waiting to see whether the viewport shrank cost the user 400ms of
           nothing happening. */
        var shielded = f.hasAttribute('data-wa-im');
        unshield(f);
        if (shielded) { try { f.blur(); f.focus(); } catch (x) {} }
      } else {
        unshieldAll();
      }
      return;
    }
    /* While the user is typing, a tap (Send, an emoji) must not start a
       guard that would shield the composer they are using. */
    var opening = '';
    var n = e.target;
    while (n && n !== document.body) {
      opening = (n.getAttribute && n.getAttribute('aria-label')) || '';
      if (opening) { break; }
      n = n.parentElement;
    }
    var picker = /emoji|sticker|gif|attach/i.test(opening);
    var cur = document.activeElement;
    if (isField(cur) && !cur.hasAttribute('data-wa-im')) {
      /* The composer keeps focus after the keyboard is dismissed, so a tap
         anywhere — the emoji button included — is a gesture on a page with an
         editable focused, and Chrome brings the keyboard straight back. Opening
         a picker lets the composer go; every other tap leaves typing alone. */
      if (!picker) { return; }
      try { cur.blur(); } catch (x) {}
    }
    /* A picker mounts a second or two after the tap and focuses its own search
       box on the way in; the guard has to outlast that. */
    guardUntil = Date.now() + (picker ? 3000 : 900);
  };
  document.addEventListener('touchstart', noteUserIntent, true);
  document.addEventListener('mousedown', noteUserIntent, true);

  /* Measured: the bottom bar (z-index 450) paints above body-level dialogs
     (z-index 400), so a tab tap under an open Media page switched the tab
     underneath while the page stayed on top — the "stuck dialog" that hid
     Channels and Communities. A tab tap now dismisses the page first. */
  document.addEventListener('touchstart', function (e) {
    var t = e.target;
    if (t && t.closest && t.closest('[data-wa="rail"]')) { closeDialog(); reclassifyAt = Date.now() + 120; }
  }, true);

  /* Which bottom-bar tab is live. aria-pressed is WhatsApp's own signal and
     survives panels being kept mounted, unlike "has rendered text". */
  var activeTab = function () {
    var rail = document.querySelector('[data-wa="rail"]');
    if (!rail) { return ''; }
    var btns = rail.querySelectorAll('button,[role="button"],a');
    for (var i = 0; i < btns.length; i++) {
      if (btns[i].getAttribute('aria-pressed') === 'true') {
        return btns[i].getAttribute('aria-label') || '';
      }
    }
    return '';
  };

  var backControl = function (panel) {
    var all = panel.querySelectorAll('button,[role="button"]');
    for (var i = 0; i < all.length; i++) {
      if (/^(back|close)$/i.test(all[i].getAttribute('aria-label') || '')) {
        var r = all[i].getBoundingClientRect();
        if (r.width > 0 && r.height > 0) { return all[i]; }
      }
    }
    return null;
  };

  var classifyPanels = function () {
    var label = activeTab();
    var old = document.querySelectorAll('[data-wa="panel"],[data-wa="panel-idle"]');
    for (var i = 0; i < old.length; i++) { old[i].removeAttribute('data-wa'); }
    document.body.classList.remove('wa-panel-open');

    var slots = [];
    var overlays = document.querySelectorAll('[data-wa="overlay"]');
    for (var o = 0; o < overlays.length; o++) {
      for (var j = 0; j < overlays[o].children.length; j++) {
        var k = overlays[o].children[j];
        var t = k.innerText;
        if (t && t.trim().length > 2 && k.getBoundingClientRect().height > 200) {
          slots.push(k);
        }
      }
    }
    if (!slots.length) { return; }
    /* Several panels can be mounted at once; the live one is whichever is
       actually painted on top. On the Chats tab only a drawer counts — "New
       chat", "Archived", "Starred" all open over the list and carry a Back
       control; a tab panel WhatsApp left mounted does not, and must not be
       taken for one. */
    var onChats = (label === 'Chats' || label === '');
    var W = window.innerWidth, H = window.innerHeight, active = null;
    var probes = [0.12, 0.35, 0.6, 0.85];
    if (document.getElementById('main')) {
      /* The fixed conversation covers every probe point, so the drawer is
         the last slot that has content and a Back/Close control. */
      for (var s2 = slots.length - 1; s2 >= 0 && !active; s2--) {
        if (backControl(slots[s2])) { active = slots[s2]; }
      }
      probes = [];
    }
    for (var f = 0; f < probes.length && !active; f++) {
      var n = document.elementFromPoint(Math.round(W * probes[f]), Math.round(H * 0.45));
      while (n && n !== document.body) {
        if (slots.indexOf(n) >= 0) {
          if (!onChats || backControl(n)) { active = n; }
          break;
        }
        n = n.parentElement;
      }
    }
    for (var i3 = 0; i3 < slots.length; i3++) {
      slots[i3].setAttribute('data-wa', slots[i3] === active ? 'panel' : 'panel-idle');
    }
    if (active) { document.body.classList.add('wa-panel-open'); }
    setTimeout(containScrollers, 400);
  };

  var lastTab = null;
  var chatOpen = null;
  var syncShell = function () {
    var open = !!document.getElementById('main');
    if (open === chatOpen) { return; }
    var opening = open && chatOpen === false;
    chatOpen = open;
    ensureStyle();
    tagPanes();
    document.body.classList.toggle('wa-chat-open', open);
    reclassifyAt = Date.now() + 120;
    setTimeout(containScrollers, 400);
    chatGuard = open;
    if (!open) { unshieldAll(); }
    /* WhatsApp grabs the composer for a moment after a chat opens; once that
       moment has passed nothing but a tap moves focus, so the shield lifts and
       the composer behaves like any field — tap, keyboard, no delay. */
    if (open) {
      setTimeout(function () {
        if (chatGuard) { chatGuard = false; unshieldAll(); }
      }, 1800);
    }
    send({ kind: 'chatOpen', open: open });
  };

  /* Reading aria-pressed is a handful of attribute lookups and costs no layout;
     the classifier does force one, so it only runs when the tab really changed. */
  /* Re-run when the tab changes, and keep re-running while a non-Chats tab has
     no live panel yet: aria-pressed flips a few frames before WhatsApp renders
     the panel's content, so classifying once at the flip finds empty slots and
     would then never look again. */
  /* A drawer opens and closes without any tab change, so the classifier
     also runs when an untagged slot shows content, when the live panel has
     collapsed (WhatsApp hid or unmounted it), and once shortly after any
     tap — the moment a drawer can appear. */
  var reclassifyAt = 0;
  document.addEventListener('touchend', function () { reclassifyAt = Date.now() + 120; }, true);
  var untaggedSlot = function () {
    var overlays = document.querySelectorAll('[data-wa="overlay"]');
    for (var o = 0; o < overlays.length; o++) {
      for (var j = 0; j < overlays[o].children.length; j++) {
        var k = overlays[o].children[j];
        if (k.hasAttribute('data-wa') || (k.textContent || '').length < 3) { continue; }
        if (k.getBoundingClientRect().height > 200) { return true; }
      }
    }
    return false;
  };
  var syncTabs = function () {
    var label = activeTab();
    var current = document.querySelector('[data-wa="panel"]');
    /* A closed drawer leaves its slot mounted at full height but emptied,
       so "no content" is what marks the live panel as gone. */
    var stale = current && (!current.isConnected || current.getBoundingClientRect().height < 100 ||
      (current.textContent || '').trim().length < 3);
    var missing = label && label !== 'Chats' && !current;
    var due = reclassifyAt && Date.now() >= reclassifyAt;
    var fresh = !current && untaggedSlot();
    if (label === lastTab && !stale && !missing && !due && !fresh) { return; }
    reclassifyAt = 0;
    lastTab = label;
    classifyPanels();
    clampUntil = Date.now() + 1600;
  };

  /* Menus and dropdowns are anchored for a desktop viewport; the "Start call"
     menu on the Calls tab runs to x=465 on a 392px screen. Walking the overlay
     layers costs layout, so it only runs briefly after a tap or a tab change —
     the moments a popup can appear. */
  var clampUntil = 0;
  document.addEventListener('touchend', function () { clampUntil = Date.now() + 1600; }, true);
  var clampPopups = function () {
    if (Date.now() > clampUntil) { return; }
    var W = window.innerWidth, PAD = 6;
    var overlays = document.querySelectorAll('[data-wa="overlay"]');
    for (var o = 0; o < overlays.length; o++) {
      if (overlays[o].querySelector('[data-wa="panel"]')) { continue; } // the panel is not a popup
      var queue = [overlays[o]], guard = 0;
      while (queue.length && guard++ < 600) {
        var el = queue.shift();
        for (var i = 0; i < el.children.length; i++) { queue.push(el.children[i]); }
        if (el === overlays[o]) { continue; }
        if (el.__waShift) { el.style.transform = ''; el.__waShift = 0; }
        var r = el.getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0) { continue; }
        if (r.width > W - PAD * 2) { continue; }
        if (r.right <= W - PAD) { continue; }
        var dx = (W - PAD) - r.right;
        if (r.left + dx < PAD) { dx = PAD - r.left; }
        if (!dx) { continue; }
        el.style.transform = 'translateX(' + Math.round(dx) + 'px)';
        el.__waShift = 1;
      }
    }
  };

  var startShell = function () {
    if (!document.body) { setTimeout(startShell, 20); return; }
    pinViewport();
    if (document.head) {
      new MutationObserver(pinViewport).observe(document.head, { childList: true });
    }
    if (!phone) { return; }
    ensureStyle();
    tagPanes();
    syncShell();
    var ticks = 0;
    var lastH = window.innerHeight;
    setInterval(function () {
      ticks++;
      /* While the keyboard is opening or closing the viewport is mid-flight and
         the main thread is the one animating it. The passes that measure the
         page stand down for that moment — they were the stutter between the
         tap on the composer and the keyboard arriving. */
      var h = window.innerHeight;
      var settling = h !== lastH;
      lastH = h;
      if (settling) {
        syncShell();
        syncTabs();
        return;
      }
      ensureStyle();
      tagPanes();
      pinViewport();
      syncShell();
      syncTabs();
      clampPopups();
      fitDialogs();
      fitWide();
      fitPicker();
      fitViewer();
      if (ticks === 6 || ticks === 25) { containScrollers(); }
      hideBanners();
      notifyUnread();
      grabAvatar();
    }, 400);
  };
  startShell();

  /* The account's own profile photo, so the shell can show the real avatar
     instead of a coloured placeholder. Read through fetch rather than a canvas
     because WhatsApp serves avatars from blob: URLs that would taint a canvas
     and make toDataURL throw. */
  var lastAvatar = '';
  var grabAvatar = function () {
    var img = document.querySelector('[data-wa="rail"] img') ||
              document.querySelector('#side header img') ||
              document.querySelector('header img');
    if (!img || !img.src || img.src === lastAvatar) { return; }
    var src = img.src;
    lastAvatar = src;
    fetch(src).then(function (r) {
      return r.ok ? r.blob() : null;
    }).then(function (b) {
      if (!b || b.size === 0 || b.size > 400000) { return; }
      var fr = new FileReader();
      fr.onload = function () {
        var raw = String(fr.result);
        var comma = raw.indexOf(',');
        if (comma < 0) { return; }
        send({ kind: 'avatar', mime: b.type || 'image/jpeg',
               base64: raw.slice(comma + 1) });
      };
      fr.readAsDataURL(b);
    }).catch(function () { lastAvatar = ''; });
  };

  /* Media, and some other views, are not tab panels: WhatsApp renders them
     as body-level dialogs outside the app frame, with a hard min-width of
     748px. On a 392px screen that leaves the close button at x=692 — off the
     phone — so the dialog cannot be dismissed and it masks every tab behind
     it. Any box inside a dialog that exceeds the viewport is pinned to it.
     Runs on the tick only while a dialog exists, and stops once two passes
     change nothing; a new dialog element starts the count again. */
  /* Promotions and desktop-only hints have no place in a phone app: the
     "Get WhatsApp for Windows" strip under the chat list, and the "see older
     messages on your phone" rows at the top of a conversation. The banner is
     the first ancestor of the text that spans the screen. */
  /* Scroll chaining: at the end of the message list the gesture used to carry
     on into the pane behind it and the whole screen moved. Only real scrollers
     are contained — something to scroll, and an overflow that scrolls it.
     Containing every div instead (the first attempt) stopped the chat list
     scrolling at all: an overflow:hidden wrapper counts as a scroll container,
     so the gesture was trapped in a box that had nothing to scroll. */
  var containScrollers = function () {
    var roots = [document.getElementById('pane-side'), document.getElementById('main'),
                 document.querySelector('[data-wa="panel"]')];
    for (var r = 0; r < roots.length; r++) {
      var root = roots[r];
      if (!root) { continue; }
      var all = root.querySelectorAll('div');
      /* The scroller can sit deep in WhatsApp's tree, past any small cap;
         the cheap test in the loop keeps getComputedStyle to a handful. */
      for (var i = 0; i < all.length && i < 4000; i++) {
        var e = all[i];
        if (e.hasAttribute('data-wa-scroll')) { continue; }
        if (e.scrollHeight <= e.clientHeight + 20 || e.clientHeight < 120) { continue; }
        var oy = getComputedStyle(e).overflowY;
        if (oy !== 'auto' && oy !== 'scroll') { continue; }
        e.setAttribute('data-wa-scroll', '1');
        e.style.setProperty('overscroll-behavior', 'contain', 'important');
      }
    }
  };

  /* The media viewer sizes itself for a desktop window. Measured on the
     phone: the picture is laid out full width, then a wrapper carries
     transform: scale(0.47) — WhatsApp's own fit-to-window zoom, computed
     against a stage it believes is desktop-sized — so it painted 240px wide in
     a 392px screen, with the strip of other media running off the bottom.

     Their zoom is left alone; it recomputes on resize. The strip is pinned to
     the bottom edge, the stage it measures is given the room this screen has,
     and one resize event lets WhatsApp fit the picture to it — so pinching
     still works, and the arrows keep stepping through.

     The picture is found by asking what is painted in the middle of the stage:
     the viewer also keeps the neighbouring image in the DOM, at times larger
     than the visible one, and fitting that one left the visible one untouched. */
  var fitViewer = function () {
    var anchor = document.querySelector('button[aria-label="Go to message"]');
    if (!anchor) { return; }
    var W = window.innerWidth, H = window.innerHeight;

    /* The strip of other media in this chat sits on the bottom edge. */
    var stripH = 0;
    var imgs = document.querySelectorAll('img');
    for (var j = 0; j < imgs.length; j++) {
      var q = imgs[j].getBoundingClientRect();
      if (q.width < 30 || q.width > 130 || q.top < H * 0.55) { continue; }
      var host = imgs[j].parentElement;
      while (host && host.getBoundingClientRect().width < W * 0.6) { host = host.parentElement; }
      if (!host) { break; }
      stripH = Math.min(96, Math.round(host.getBoundingClientRect().height) || 76);
      if (!host.hasAttribute('data-wa-strip')) {
        host.setAttribute('data-wa-strip', '1');
        host.style.setProperty('position', 'fixed', 'important');
        host.style.setProperty('left', '0px', 'important');
        host.style.setProperty('right', '0px', 'important');
        host.style.setProperty('bottom', '0px', 'important');
        host.style.setProperty('top', 'auto', 'important');
        host.style.setProperty('width', '100vw', 'important');
        host.style.setProperty('max-width', '100vw', 'important');
        host.style.setProperty('z-index', '5', 'important');
      }
      break;
    }

    var bar = anchor.getBoundingClientRect();
    var top = bar.height ? Math.round(bar.bottom + 8) : 0;
    var room = H - top - stripH - 8;
    var mid = Math.round(top + room / 2);

    /* Whatever is painted in the middle of the stage is the picture on show. */
    var media = null;
    var probes = [mid, mid - 60, mid + 60];
    for (var t = 0; t < probes.length && !media; t++) {
      var hit = document.elementFromPoint(Math.round(W / 2), probes[t]);
      if (!hit) { continue; }
      if (hit.tagName === 'IMG' || hit.tagName === 'VIDEO') { media = hit; }
      else if (hit.querySelector) { media = hit.querySelector('img,video'); }
    }
    if (!media || media.hasAttribute('data-wa-fitted')) { return; }
    var r0 = media.getBoundingClientRect();
    if (r0.width < 80 || r0.height < 80) { return; }
    media.setAttribute('data-wa-fitted', '1');

    var e = media.parentElement;
    for (var d = 0; d < 8 && e && e !== document.body; d++) {
      var er = e.getBoundingClientRect();
      if (er.height >= H - 4 && er.width >= W - 4) { break; }   /* the viewer layer */
      e.style.setProperty('max-width', 'none', 'important');
      e.style.setProperty('max-height', 'none', 'important');
      e.style.setProperty('width', '100%', 'important');
      e.style.setProperty('height', room + 'px', 'important');
      e = e.parentElement;
    }

    /* WhatsApp recomputes the fit when the window changes size. */
    setTimeout(function () {
      try { window.dispatchEvent(new Event('resize')); } catch (x) {}
    }, 60);
  };

  /* The emoji and sticker picker is a desktop popup: measured on the phone it
     mounted at the top of the screen, 482px tall, covering the conversation.
     On a phone it belongs where a keyboard is — along the bottom, with the
     conversation still visible above it. Runs in the same after-a-tap window
     as the other popup work. */
  var fitPicker = function () {
    if (Date.now() > clampUntil) { return; }
    var W = window.innerWidth, H = window.innerHeight;
    var pick = null;
    var boxes = document.querySelectorAll('div');
    for (var i = 0; i < boxes.length && i < 3000; i++) {
      var e = boxes[i];
      if (e.querySelectorAll('img.emoji').length < 24) { continue; }
      var q = e.getBoundingClientRect();
      if (q.height < 180 || q.width < 150) { continue; }
      if (!pick || q.height < pick.getBoundingClientRect().height) { pick = e; }
    }
    if (!pick || pick.hasAttribute('data-wa-picker')) { return; }
    pick.setAttribute('data-wa-picker', '1');
    var set = function (el, pairs) {
      for (var k in pairs) { el.style.setProperty(k, pairs[k], 'important'); }
    };
    set(pick, {
      position: 'fixed', left: '0px', right: '0px', bottom: '0px', top: 'auto',
      width: '100vw', 'max-width': '100vw', height: 'min(58vh, 430px)',
      'max-height': '58vh', margin: '0px', 'border-radius': '14px 14px 0 0',
      'z-index': '900'
    });
    /* Its own wrapper is anchored for a desktop popup. */
    var host = pick.parentElement;
    for (var d = 0; d < 3 && host && host !== document.body; d++) {
      var cs = getComputedStyle(host);
      if (cs.position === 'absolute' || cs.position === 'fixed') {
        set(host, {
          position: 'fixed', left: '0px', right: '0px', bottom: '0px', top: 'auto',
          width: '100vw', 'max-width': '100vw', height: 'auto', transform: 'none',
          margin: '0px', 'z-index': '900'
        });
      }
      host = host.parentElement;
    }
  };

  var bannerAt = 0;
  var hideBanners = function () {
    var now = Date.now();
    if (now < bannerAt) { return; }
    bannerAt = now + 1500;
    /* Measured: each hint is a short block (393x65 / 393x86 in a chat, a
       0px-tall button wrapper under the chat list) sitting directly inside a
       tall list container. Walk up until the parent is that container and
       hide the block — never anything 200px or taller. */
    var roots = [document.getElementById('side'), document.getElementById('main')];
    var pat = /WhatsApp for Windows|Get WhatsApp|older messages|chat history/i;
    for (var r = 0; r < roots.length; r++) {
      var root = roots[r];
      if (!root) { continue; }
      var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      var n;
      while ((n = walker.nextNode())) {
        var t = n.textContent;
        if (!t || t.length < 12 || !pat.test(t)) { continue; }
        var e = n.parentElement;
        while (e && e.parentElement && e.parentElement !== root &&
               e.parentElement.getBoundingClientRect().height < 400) {
          e = e.parentElement;
        }
        if (!e || e === root || e.hasAttribute('data-wa-hid')) { continue; }
        if (e.getBoundingClientRect().height >= 200) { continue; }
        e.setAttribute('data-wa-hid', '1');
        e.style.setProperty('display', 'none', 'important');
      }
    }
  };

  /* The emoji/sticker picker is a 560px desktop panel anchored for a wide
     window; at 393px it opened at x=-203 with its first columns off screen.
     Any positioned box inside the app that is wider than the screen (and is
     not a dialog, which fitDialogs owns) is pinned to the screen. Runs in
     the same after-tap window as the popup clamp. */
  var fitWide = function () {
    if (Date.now() > clampUntil) { return; }
    var W = window.innerWidth;
    var all = document.querySelectorAll('div');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.offsetWidth <= W + 2 || e.__waFit) { continue; }
      if (e.getAttribute('data-wa') || e.closest('[role="dialog"]')) { continue; }
      var pos = getComputedStyle(e).position;
      if (pos !== 'absolute' && pos !== 'fixed') { continue; }
      e.__waFit = true;
      e.setAttribute('data-wa-fit', '1');
      e.style.setProperty('width', '100vw', 'important');
      e.style.setProperty('max-width', '100vw', 'important');
      e.style.setProperty('min-width', '0', 'important');
      e.style.setProperty('left', '0', 'important');
      e.style.setProperty('right', 'auto', 'important');
      e.style.setProperty('box-sizing', 'border-box', 'important');
    }
  };

  /* WhatsApp Web never raised a notification here even with the page hidden
     and permission granted (measured: the title went to "(3)" with no
     Notification constructed). So while the page is hidden the chat list is
     read directly: a row whose unread badge grew raises one notification
     with the sender and the preview, closed again once the badge is gone. */
  var seenUnread = {};
  var liveNotes = {};
  var noteAt = 0;
  /* Tapping the notification opens that conversation, the way the phone app
     does. The row is looked up at tap time: the list recycles its rows. */
  var openChat = function (name) {
    var rows = document.querySelectorAll('#pane-side [role="row"]');
    for (var i = 0; i < rows.length; i++) {
      var t = rows[i].querySelector('span[title]');
      if (t && t.getAttribute('title') === name) { rows[i].click(); return true; }
    }
    return false;
  };
  var notifyUnread = function () {
    var now = Date.now();
    if (now < noteAt) { return; }
    noteAt = now + 2000;
    /* The host posts its own fallback summary unless it hears from this
       scan, which is the better notification: it names the sender. */
    send({ kind: 'notifyTick' });
    var rows = document.querySelectorAll('#pane-side [role="row"]');
    var current = {};
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      var badge = row.querySelector('[aria-label*="unread" i]');
      if (!badge) { continue; }
      var m = /(\d+)/.exec(badge.getAttribute('aria-label') || '');
      var count = m ? parseInt(m[1], 10) : 1;
      var titled = row.querySelectorAll('span[title]');
      var name = titled[0] ? titled[0].getAttribute('title') : 'WhatsApp';
      var preview = titled[1] ? titled[1].getAttribute('title') :
        (count === 1 ? 'New message' : count + ' new messages');
      current[name] = count;
      var wasNoted = (window.__waNoted[name] || 0) > Date.now() - 30000;
      if (document.hidden && !wasNoted && count > (seenUnread[name] || 0)) {
        try {
          var note = new window.Notification(name, { body: preview, tag: 'chat:' + name });
          note.onclick = (function (who) {
            return function () { openChat(who); };
          })(name);
          liveNotes[name] = note;
        } catch (e) {}
      }
    }
    for (var k in liveNotes) {
      if (!current[k]) { try { liveNotes[k].close(); } catch (e2) {} delete liveNotes[k]; }
    }
    seenUnread = current;
  };

  var fitAt = 0;
  var fitDialogs = function () {
    var now = Date.now();
    if (now < fitAt) { return; }
    fitAt = now + 700;
    var W = window.innerWidth;
    var dialogs = document.querySelectorAll('[role="dialog"],[aria-modal="true"]');
    for (var d = 0; d < dialogs.length; d++) {
      var dlg = dialogs[d];
      var all = dlg.querySelectorAll('*');
      /* A dialog WhatsApp keeps mounted (Media) can be reopened with new
         content; a changed node count reopens the pass. */
      if (dlg.__waN !== all.length) { dlg.__waN = all.length; dlg.__waClean = 0; }
      if ((dlg.__waClean || 0) >= 2) { continue; }
      var nodes = [dlg];
      for (var i = 0; i < all.length && i < 1500; i++) { nodes.push(all[i]); }
      /* Read every rect first, then write: interleaving a style write with
         the next rect read forces a layout per node — on the Media grid
         that was thousands of layouts a tick, and the page stalled for
         seconds (video tiles would not even open). Each box is fitted once;
         a caption that still pokes past its tile is clipped by the tile
         and is not worth another pass. */
      var todo = [];
      for (var j = 0; j < nodes.length; j++) {
        var e = nodes[j];
        if (e.__waFit) { continue; }
        var r = e.getBoundingClientRect();
        if (r.width <= W + 2 && r.right <= W + 2) { continue; }
        todo.push([e, r.width > W + 2, getComputedStyle(e).position]);
      }
      for (var k = 0; k < todo.length; k++) {
        var el = todo[k][0];
        el.__waFit = true;
        el.style.setProperty('min-width', '0', 'important');
        el.style.setProperty('max-width', '100vw', 'important');
        el.style.setProperty('box-sizing', 'border-box', 'important');
        if (todo[k][1]) { el.style.setProperty('width', '100%', 'important'); }
        if (todo[k][2] === 'absolute' || todo[k][2] === 'fixed') {
          el.style.setProperty('left', '0', 'important');
          el.style.setProperty('right', 'auto', 'important');
        }
      }
      dlg.__waClean = todo.length ? 0 : (dlg.__waClean || 0) + 1;
    }
  };

  /* The back gesture must dismiss a dialog before anything else; a modal that
     back cannot close is a trap on a phone. */
  var closeDialog = function () {
    var H = window.innerHeight, W = window.innerWidth;
    var btns = document.querySelectorAll('button,[role="button"]');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      if (!/^(close|back)$/i.test(b.getAttribute('aria-label') || '')) { continue; }
      if (b.closest('#main') || b.closest('[data-wa="rail"]')) { continue; }
      var r = b.getBoundingClientRect();
      if (!r.width || !r.height) { continue; }
      /* Only a control that is actually painted on top counts: the media
         viewer's Close sits above Contact info's, which sits above the
         conversation — each back press peels one layer. */
      var top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      if (!top || (top !== b && !b.contains(top))) { continue; }
      var e = b.parentElement, big = false;
      while (e && e !== document.body) {
        var pos = getComputedStyle(e).position;
        if (pos === 'fixed' || pos === 'absolute') {
          var q = e.getBoundingClientRect();
          big = q.height >= H * 0.5 && q.width >= W * 0.6;
          break;
        }
        e = e.parentElement;
      }
      if (!big) { continue; }
      b.click();
      reclassifyAt = Date.now() + 120;
      return true;
    }
    /* A page-sized dialog without a Close control (rare) still takes Escape. */
    var all = document.querySelectorAll('[role="dialog"],[aria-modal="true"]');
    for (var d = 0; d < all.length; d++) {
      if (all[d].getBoundingClientRect().height < H * 0.5) { continue; }
      var fire = function (type) {
        document.dispatchEvent(new KeyboardEvent(type, {
          key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true
        }));
      };
      fire('keydown'); fire('keyup');
      return true;
    }
    return false;
  };

  /* Called by the host when the back gesture is used. Guarded: with no
     conversation open, Escape drives WhatsApp's list navigation and *opens*
     one, which made back do the opposite of what it says. */
  window.__waCloseChat = function () {
    if (closeDialog()) { return true; }
    /* An open emoji/sticker picker goes first; WhatsApp closes it on Escape. */
    var pickers = document.querySelectorAll('[data-wa-fit]');
    for (var pk = 0; pk < pickers.length; pk++) {
      if (pickers[pk].offsetHeight > 100) {
        var esc = function (type) {
          document.dispatchEvent(new KeyboardEvent(type, {
            key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true
          }));
        };
        esc('keydown'); esc('keyup');
        return true;
      }
    }
    if (!document.getElementById('main')) {
      var tab = activeTab();
      if (tab && tab !== 'Chats') {
        var btns = document.querySelectorAll('[data-wa="rail"] button');
        for (var i = 0; i < btns.length; i++) {
          if (btns[i].getAttribute('aria-label') === 'Chats') { btns[i].click(); return true; }
        }
      }
      return false;
    }
    var fire = function (type) {
      document.dispatchEvent(new KeyboardEvent(type, {
        key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true
      }));
    };
    fire('keydown'); fire('keyup');
    setTimeout(function () {
      if (document.getElementById('main')) {
        try { history.back(); } catch (e) {}
      }
    }, 160);
    return true;
  };

  /* ---- Unread badge -------------------------------------------------- */

  var lastCount = -1;
  var readTitle = function () {
    var m = /^\((\d+)\)/.exec(document.title || '');
    var n = m ? parseInt(m[1], 10) : 0;
    if (n !== lastCount) {
      lastCount = n;
      send({ kind: 'unread', count: n });
    }
  };
  var watchTitle = function () {
    if (!document.querySelector('title')) { setTimeout(watchTitle, 200); return; }
    readTitle();
    new MutationObserver(readTitle).observe(
      document.querySelector('title'), { childList: true, characterData: true, subtree: true }
    );
  };
  watchTitle();

  send({ kind: 'booted' });
})();
"""
}
