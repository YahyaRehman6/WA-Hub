package com.algotix.wa_hub

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns every live WhatsApp Web session.
 *
 * Each profile gets its own androidx.webkit [Profile], which carries a separate
 * cookie store, localStorage/IndexedDB, service worker registry and geolocation
 * grants. That is what actually keeps two WhatsApp accounts from seeing each
 * other — clearing cookies between switches would not, because WhatsApp keeps
 * the session in IndexedDB.
 *
 * All live WebViews sit in a single native stage view (see [WaStage]) and are
 * swapped with visibility rather than being added and removed, so background
 * profiles stay laid out, keep their websocket open, and keep reporting unread
 * counts.
 */
class WaHost(
    private val activity: Activity,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {

    private val channel = MethodChannel(messenger, CHANNEL).also { it.setMethodCallHandler(this) }
    private val media = MediaDelegate(activity)

    /** Insertion-ordered so the oldest untouched profile is the first eviction candidate. */
    private val views = LinkedHashMap<String, WebView>()
    private val specs = HashMap<String, ProfileSpec>()
    private val scripts = HashMap<String, ScriptHandler>()

    private val notifications = HostNotifications(activity) { profileId, id, action ->
        val fn = if (action == HostNotifications.CLICK) "__waNotificationClicked" else "__waNotificationDismissed"
        views[profileId]?.evaluateJavascript("window.$fn && window.$fn('$id')", null)
        if (action == HostNotifications.CLICK) emit(profileId, "requestFocus", emptyMap())
    }
    private var stage: WaStage? = null
    private var active: String? = null
    private var liveLimit = 4

    private var foreground = true

    /** Master switch from Settings; a muted profile is skipped on top of it. */
    private var notificationsEnabled = true

    /** Last unread count seen per profile, to detect a *rise*, not a level. */
    private val unreadSeen = HashMap<String, Int>()

    /** When the page last raised a real Notification, to avoid doubling up. */
    private val lastPageNotification = HashMap<String, Long>()

    /**
     * When the page is scanning the chat list itself it raises the better
     * notification — it names the sender — so the host's summary would be a
     * duplicate. The scan announces itself on every pass; the summary stands
     * in only once those passes stop (a frozen or dead renderer).
     */
    private val lastPageTick = HashMap<String, Long>()

    private val chromeFull: String by lazy {
        WebViewCompat.getCurrentWebViewPackage(activity)?.versionName ?: "120.0.0.0"
    }
    private val chromeMajor: String by lazy { chromeFull.substringBefore('.') }
    private val desktopUserAgent: String by lazy {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Safari/537.36"
    }

    data class ProfileSpec(
        val id: String,
        val name: String,
        val forcedWidth: Int,
        val textZoom: Int,
        val muted: Boolean,
    )

    // ---- Stage wiring ----------------------------------------------------

    fun bindStage(view: WaStage) {
        stage = view
        views.values.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            view.addWebView(wv)
        }
        active?.let { showOnly(it) }
    }

    fun unbindStage(view: WaStage) {
        if (stage === view) stage = null
    }

    fun destroy() {
        views.values.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        views.clear()
        scripts.clear()
        KeepAliveService.stop(activity)
        notifications.dispose()
        channel.setMethodCallHandler(null)
    }

    /** Drives whether an unread rise is worth a notification. */
    fun setForeground(value: Boolean) {
        foreground = value
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) =
        media.onActivityResult(requestCode, resultCode, data)

    fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) =
        media.onRequestPermissionsResult(code, perms, results)

    // ---- Method channel --------------------------------------------------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "capabilities" -> result.success(
                mapOf(
                    "multiProfile" to WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
                    "documentStart" to WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
                    "webMessage" to WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
                    "webViewVersion" to chromeFull,
                    "notificationsAllowed" to notifications.areNotificationsAllowed(),
                )
            )

            "attach" -> {
                val spec = specOf(call)
                ensure(spec)
                active = spec.id
                showOnly(spec.id)
                touch(spec.id)
                result.success(null)
            }

            "preload" -> {
                ensure(specOf(call))
                result.success(null)
            }

            "release" -> {
                releaseProfile(call.argument<String>("profileId")!!)
                result.success(null)
            }

            "forget" -> {
                val id = call.argument<String>("profileId")!!
                releaseProfile(id)
                specs.remove(id)
                var deleted = false
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    deleted = runCatching { ProfileStore.getInstance().deleteProfile(profileKey(id)) }
                        .getOrDefault(false)
                }
                result.success(deleted)
            }

            "closeChat" -> {
                // A window the page opened (a call) is the first thing back closes.
                if (closeTopPopup()) {
                    result.success(true)
                    return
                }
                val wv = views[call.argument<String>("profileId")]
                if (wv == null) {
                    result.success(false)
                } else {
                    wv.evaluateJavascript("!!(window.__waCloseChat && window.__waCloseChat())") {
                        result.success(it == "true")
                    }
                }
            }

            "reload" -> {
                val id = call.argument<String>("profileId")!!
                val wv = views[id]
                if (wv != null) {
                    wv.reload()
                } else {
                    // The renderer was reaped; there is nothing to reload, so
                    // rebuild the session from its stored spec instead.
                    specs[id]?.let { spec ->
                        ensure(spec)
                        if (id == active) showOnly(id)
                    }
                }
                result.success(null)
            }

            "home" -> {
                views[call.argument<String>("profileId")]?.loadUrl(WHATSAPP_URL)
                result.success(null)
            }

            "goBack" -> {
                val wv = views[call.argument<String>("profileId")]
                val can = wv?.canGoBack() == true
                if (can) wv!!.goBack()
                result.success(can)
            }

            "canGoBack" ->
                result.success(views[call.argument<String>("profileId")]?.canGoBack() == true)

            "applyLayout" -> {
                val spec = specOf(call)
                specs[spec.id] = spec
                views[spec.id]?.let { wv ->
                    wv.settings.textZoom = spec.textZoom
                    // The forced viewport width is baked into the document-start
                    // script, so the old script must be replaced, not just re-run.
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        scripts.remove(spec.id)?.remove()
                        scripts[spec.id] = installBootScript(wv, spec)
                    }
                    wv.reload()
                }
                result.success(null)
            }

            "thumbnail" -> result.success(
                thumbnail(
                    call.argument<String>("profileId")!!,
                    call.argument<Int>("maxWidth") ?: 480,
                )
            )

            "setNotificationsEnabled" -> {
                notificationsEnabled = call.argument<Boolean>("enabled") ?: true
                if (!notificationsEnabled) {
                    views.keys.forEach { notifications.clearUnreadSummary(it) }
                }
                result.success(null)
            }

            "requestNotifications" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    media.requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    ) { /* Declining is a valid answer; the app works without it. */ }
                }
                result.success(null)
            }

            "flush" -> {
                flush()
                result.success(null)
            }

            "notificationsAllowed" ->
                result.success(notifications.areNotificationsAllowed())

            // Once the runtime prompt has been refused twice Android stops
            // showing it, so the only way back is the settings page itself.
            "isBatteryExempt" -> {
                val pm = activity.getSystemService(android.os.PowerManager::class.java)
                result.success(pm?.isIgnoringBatteryOptimizations(activity.packageName) ?: true)
            }

            // TECNO/HiOS and similar kill backgrounded apps regardless of a
            // foreground service unless the app is exempted from optimisation.
            "requestBatteryExemption" -> {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + activity.packageName)),
                    )
                }
                result.success(null)
            }

            "openNotificationSettings" -> {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                result.success(null)
            }

            "liveProfiles" -> result.success(views.keys.toList())

            "setLiveLimit" -> {
                liveLimit = max(1, call.argument<Int>("limit") ?: 4)
                trim()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun specOf(call: MethodCall) = ProfileSpec(
        id = call.argument<String>("profileId")!!,
        name = call.argument<String>("name") ?: "WhatsApp",
        forcedWidth = call.argument<Int>("forcedWidth") ?: 0,
        textZoom = call.argument<Int>("textZoom") ?: 100,
        muted = call.argument<Boolean>("muted") ?: false,
    )

    // ---- Lifecycle -------------------------------------------------------

    private fun ensure(spec: ProfileSpec) {
        val previous = specs[spec.id]
        specs[spec.id] = spec
        val existing = views[spec.id]
        if (existing != null) {
            // A spec can change between attaches (settings edited while the
            // account was asleep). The forced width is baked into the injected
            // script, so it has to be reinstalled rather than just re-run.
            if (previous != null && previous.forcedWidth != spec.forcedWidth) {
                existing.settings.textZoom = spec.textZoom
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    scripts.remove(spec.id)?.remove()
                    scripts[spec.id] = installBootScript(existing, spec)
                }
                existing.reload()
            }
            return
        }
        trim(reserve = 1)
        val wv = build(spec)
        views[spec.id] = wv
        stage?.addWebView(wv)
        if (spec.id != active) wv.visibility = View.INVISIBLE
        wv.loadUrl(WHATSAPP_URL)
        emit(spec.id, "live", mapOf("live" to true))
        KeepAliveService.update(activity, views.size)
    }

    private fun touch(id: String) {
        views.remove(id)?.let { views[id] = it }
    }

    private fun trim(reserve: Int = 0) {
        while (views.size + reserve > liveLimit) {
            val victim = views.keys.firstOrNull { it != active } ?: return
            releaseProfile(victim)
        }
    }

    private fun releaseProfile(id: String) {
        val wv = views.remove(id) ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.clearHistory()
        scripts.remove(id)?.remove()
        wv.destroy()
        notifications.clearProfile(id)
        emit(id, "live", mapOf("live" to false))
        KeepAliveService.update(activity, views.size)
    }

    /// Cookies are written lazily by Chromium; a process death loses whatever
    /// is still buffered, which reads to the user as being signed out. Flushing
    /// when the app leaves the foreground closes that window.
    fun flush() {
        val store = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { ProfileStore.getInstance() }.getOrNull()
        } else {
            null
        }
        views.keys.forEach { id ->
            val manager = store?.let {
                runCatching { it.getProfile(profileKey(id))?.cookieManager }.getOrNull()
            } ?: CookieManager.getInstance()
            runCatching { manager.flush() }
        }
    }

    /** After onRenderProcessGone only destroy() is safe to call on the view. */
    private fun dropDeadView(id: String, wv: WebView) {
        if (views[id] === wv) views.remove(id)
        (wv.parent as? ViewGroup)?.removeView(wv)
        scripts.remove(id)?.remove()
        runCatching { wv.destroy() }
        emit(id, "live", mapOf("live" to false))
        KeepAliveService.update(activity, views.size)
    }

    private fun showOnly(id: String) {
        views.forEach { (key, wv) ->
            // INVISIBLE, never onPause(): a paused WebView suspends its DOM and
            // JavaScript timers, which would close the websocket and stop
            // messages arriving for every account except the visible one. The
            // whole point of the stage is that background accounts keep running.
            wv.visibility = if (key == id) View.VISIBLE else View.INVISIBLE
            wv.onResume()
        }
        views[id]?.resumeTimers()
    }

    // ---- WebView construction -------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(spec: ProfileSpec): WebView {
        val wv = WebView(activity)

        // Must happen before the WebView is used for anything else.
        var profile: Profile? = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val key = profileKey(spec.id)
            profile = runCatching {
                ProfileStore.getInstance().getOrCreateProfile(key).also {
                    WebViewCompat.setProfile(wv, key)
                }
            }.getOrNull()
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = desktopUserAgent
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // Zoom stays available to the engine; the injected viewport meta
            // decides per page whether it is allowed, so the link screen can be
            // magnified while the linked app stays pinned.
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            textZoom = spec.textZoom
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }

        // The page runs in a separate sandboxed renderer process. By default its
        // priority is waived the moment the view is not visible, which is exactly
        // when the user is over in WhatsApp typing a link code — Android could
        // reap the renderer and the handshake dies silently. Keep it important.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        // A session that is switched away from stays rasterised, so switching
        // back paints the page it already had instead of a blank frame.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
            WebSettingsCompat.setOffscreenPreRaster(wv.settings, true)
        }

        val cookies = profile?.cookieManager ?: CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(wv, true)

        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.setBackgroundColor(0xFF000000.toInt())

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                wv, BRIDGE, setOf(WHATSAPP_ORIGIN)
            ) { _, message, _, _, _ ->
                message.data?.let { onBridgeMessage(spec.id, it) }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scripts[spec.id] = installBootScript(wv, spec)
        }

        wv.webViewClient = Client(spec.id)
        wv.webChromeClient = Chrome(spec.id)
        wv.setDownloadListener(Downloads(spec.id, cookies))
        return wv
    }

    /** Windows the page opened for itself — a call, a preview — over the stage. */
    private val popups = ArrayList<WebView>()

    private fun buildPopup(id: String): WebView {
        val spec = specs[id]
        val wv = WebView(activity)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { WebViewCompat.setProfile(wv, profileKey(id)) }
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = desktopUserAgent
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = spec?.textZoom ?: 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        if (spec != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            installBootScript(wv, spec)
        }
        wv.setBackgroundColor(0xFF000000.toInt())
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                if (isWhatsApp(req.url)) return false
                media.openExternally(req.url)
                closePopup(v)
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            /** A call window asks for the microphone and camera itself. */
            override fun onPermissionRequest(request: PermissionRequest) =
                media.resolveWebPermission(request)

            override fun onCloseWindow(window: WebView) = closePopup(window)
        }
        popups += wv
        stage?.addWebView(wv)
        wv.bringToFront()
        return wv
    }

    private fun closePopup(view: WebView) {
        if (!popups.remove(view)) return
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    /** Back closes the newest window the page opened, if there is one. */
    private fun closeTopPopup(): Boolean {
        val top = popups.lastOrNull() ?: return false
        closePopup(top)
        return true
    }

    private fun installBootScript(wv: WebView, spec: ProfileSpec): ScriptHandler =
        WebViewCompat.addDocumentStartJavaScript(
            wv,
            BootScript.build(chromeMajor, "$chromeMajor.0.0.0", spec.forcedWidth),
            setOf(WHATSAPP_ORIGIN),
        )

    private inner class Client(private val id: String) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val url = req.url
            if (isWhatsApp(url)) return false
            media.openExternally(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            emit(id, "loading", mapOf("url" to url))
        }

        override fun onPageFinished(view: WebView, url: String) {
            emit(id, "loaded", mapOf("url" to url, "canGoBack" to view.canGoBack()))
        }

        /** Android reaped the renderer. The view is unusable; drop it and say so. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            dropDeadView(id, view)
            emit(
                id, "error",
                mapOf(
                    "code" to -1,
                    "message" to "The page was stopped by Android in the background. Reload to reconnect.",
                ),
            )
            return true
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            emit(
                id, "error",
                mapOf(
                    "code" to err.errorCode,
                    "message" to err.description?.toString().orEmpty(),
                )
            )
        }
    }

    private inner class Chrome(private val id: String) : WebChromeClient() {
        private var lastProgress = -1

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // Chromium reports progress many times a second. Every one of those
            // used to cross the method channel and repaint Flutter; the bar is
            // two pixels tall, so steps of 10 are indistinguishable and far
            // cheaper. 100 always goes through so the bar always completes.
            if (newProgress != 100 && newProgress - lastProgress < 10) return
            lastProgress = if (newProgress == 100) -1 else newProgress
            emit(id, "progress", mapOf("value" to newProgress))
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            emit(id, "title", mapOf("title" to title.orEmpty()))
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            media.resolveWebPermission(request)
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: android.webkit.GeolocationPermissions.Callback,
        ) = media.resolveGeolocation(origin, callback)

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean = media.showFileChooser(callback, params)

        /**
         * WhatsApp opens two kinds of window: a shared link, which belongs in
         * the browser, and its own pages — a call is one — which have to stay
         * in the app, on this profile's session. The new view is created here
         * and shown over the stage; where it navigates decides which it was.
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message,
        ): Boolean {
            val child = buildPopup(id)
            (resultMsg.obj as android.webkit.WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            closePopup(window)
        }
    }

    private inner class Downloads(
        private val id: String,
        private val cookies: CookieManager,
    ) : DownloadListener {
        override fun onDownloadStart(
            url: String, userAgent: String, disposition: String, mime: String, length: Long,
        ) {
            if (!url.startsWith("http")) return // blob: is handled in BootScript
            val name = URLUtil.guessFileName(url, disposition, mime)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                addRequestHeader("cookie", cookies.getCookie(url) ?: "")
                addRequestHeader("User-Agent", desktopUserAgent)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
            }
            runCatching {
                activity.getSystemService(DownloadManager::class.java).enqueue(request)
                emit(id, "downloadStarted", mapOf("name" to name))
            }.onFailure {
                emit(id, "downloadFailed", mapOf("name" to name))
            }
        }
    }

    // ---- Bridge ----------------------------------------------------------

    private fun onBridgeMessage(id: String, raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("kind")) {
            "unread" -> {
                val count = json.optInt("count")
                emit(id, "unread", mapOf("count" to count))
                onUnreadChanged(id, count)
            }

            // The phone shell reports whether a conversation is covering the
            // list, so the back gesture knows what it should undo.
            "chatOpen" -> emit(id, "chatOpen", mapOf("open" to json.optBoolean("open")))

            "notifyTick" -> lastPageTick[id] = System.currentTimeMillis()

            "notification" -> {
                lastPageNotification[id] = System.currentTimeMillis()
                val spec = specs[id]
                if (!notificationsEnabled || spec?.muted == true) return
                notifications.post(
                    profileId = id,
                    profileName = spec?.name ?: "WhatsApp",
                    notificationId = json.optString("id"),
                    title = json.optString("title"),
                    body = json.optString("body"),
                    silent = json.optBoolean("silent"),
                )
            }

            "notificationClose" ->
                notifications.cancel(id, json.optString("id"))

            "download" -> {
                val bytes = runCatching {
                    Base64.decode(json.optString("base64"), Base64.DEFAULT)
                }.getOrNull()
                if (bytes == null) {
                    emit(id, "downloadFailed", mapOf("name" to json.optString("name")))
                } else {
                    val saved = media.saveToDownloads(
                        json.optString("name"), json.optString("mime"), bytes
                    )
                    emit(
                        id,
                        if (saved) "downloadSaved" else "downloadFailed",
                        mapOf("name" to json.optString("name")),
                    )
                }
            }

            "downloadTooLarge" -> emit(
                id, "downloadTooLarge",
                mapOf("name" to json.optString("name"), "size" to json.optLong("size")),
            )

            "downloadFailed" -> emit(id, "downloadFailed", mapOf("name" to json.optString("name")))

            "avatar" -> emit(
                id, "avatar",
                mapOf(
                    "base64" to json.optString("base64"),
                    "mime" to json.optString("mime"),
                ),
            )

            "booted" -> emit(id, "booted", emptyMap())
        }
    }

    /**
     * WhatsApp only raises a web Notification when its page is hidden, and only
     * through APIs a document-start script can see. When it does neither — most
     * often because the service worker handled it — a rise in the unread count
     * is still a reliable signal that something arrived.
     */
    private fun onUnreadChanged(id: String, count: Int) {
        val previous = unreadSeen.put(id, count) ?: 0
        if (count <= previous) {
            if (count == 0) notifications.clearUnreadSummary(id)
            return
        }
        // Whatever is on screen in the foreground is being read, not missed.
        if (foreground && id == active) return
        if (!notificationsEnabled || specs[id]?.muted == true) return
        val now = System.currentTimeMillis()
        if (now - (lastPageNotification[id] ?: 0L) < 5_000L) return
        if (now - (lastPageTick[id] ?: 0L) < 15_000L) return
        notifications.postUnreadSummary(id, specs[id]?.name ?: "WhatsApp", count)
    }

    private fun emit(profileId: String, type: String, extra: Map<String, Any?>) {
        val payload = HashMap<String, Any?>(extra.size + 2)
        payload["profileId"] = profileId
        payload["type"] = type
        payload.putAll(extra)
        activity.runOnUiThread { channel.invokeMethod("event", payload) }
    }

    // ---- Thumbnails ------------------------------------------------------

    private fun thumbnail(id: String, maxWidth: Int): ByteArray? {
        val wv = views[id] ?: return null
        if (wv.width <= 0 || wv.height <= 0) return null
        return runCatching {
            val scale = (maxWidth.toFloat() / wv.width).coerceAtMost(1f)
            val w = (wv.width * scale).roundToInt().coerceAtLeast(1)
            val h = (wv.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            wv.draw(canvas)
            val out = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            bmp.compress(format, 70, out)
            bmp.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    companion object {
        const val CHANNEL = "wa_hub/host"
        private const val BRIDGE = "__WAHOST"
        private const val WHATSAPP_URL = "https://web.whatsapp.com/"
        private const val WHATSAPP_ORIGIN = "https://web.whatsapp.com"

        /** ProfileStore reserves the name "Default", so every id is namespaced. */
        fun profileKey(id: String) = "wa-$id"

        fun isWhatsApp(uri: Uri): Boolean {
            val host = uri.host ?: return false
            return host == "whatsapp.com" || host.endsWith(".whatsapp.com") ||
                host == "whatsapp.net" || host.endsWith(".whatsapp.net")
        }
    }
}
