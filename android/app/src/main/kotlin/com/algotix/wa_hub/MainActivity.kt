package com.algotix.wa_hub

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private var host: WaHost? = null
    private var launchChannel: MethodChannel? = null
    private var pendingProfile: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && isDebuggable()) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        super.onCreate(savedInstanceState)
        pendingProfile = intent?.getStringExtra(HostNotifications.EXTRA_PROFILE)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        val waHost = WaHost(this, messenger)
        host = waHost
        flutterEngine.platformViewsController.registry
            .registerViewFactory(WaStage.VIEW_TYPE, WaStage.Factory(waHost))

        launchChannel = MethodChannel(messenger, "wa_hub/launch").also { channel ->
            channel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "consumeLaunchProfile" -> {
                        result.success(pendingProfile)
                        pendingProfile = null
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    /** A notification tap arrives here; tell Flutter which profile to open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(HostNotifications.EXTRA_PROFILE)?.let { profileId ->
            launchChannel?.invokeMethod("openProfile", profileId) ?: run { pendingProfile = profileId }
        }
    }

    override fun onResume() {
        super.onResume()
        host?.setForeground(true)
    }

    override fun onPause() {
        host?.setForeground(false)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (host?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (host?.onRequestPermissionsResult(requestCode, permissions, grantResults) == true) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        host?.destroy()
        host = null
        super.onDestroy()
    }

    private fun isDebuggable() =
        applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}
