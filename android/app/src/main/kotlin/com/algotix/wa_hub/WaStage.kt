package com.algotix.wa_hub

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * The single platform view Flutter embeds. Every live WhatsApp session is a child
 * of this frame; switching profiles toggles visibility instead of adding and
 * removing views, so background sessions stay laid out and keep running.
 *
 * One stage rather than one platform view per profile also sidesteps Android's
 * platform-view z-ordering, where an off-screen WebView can otherwise draw over
 * the Flutter UI.
 */
class WaStage(context: Context, private val host: WaHost) : PlatformView {

    private val frame = FrameLayout(context).apply {
        setBackgroundColor(0xFF000000.toInt())
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    init {
        host.bindStage(this)
    }

    fun addWebView(view: View) {
        if (view.parent === frame) return
        (view.parent as? ViewGroup)?.removeView(view)
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun getView(): View = frame

    override fun dispose() {
        host.unbindStage(this)
    }

    class Factory(private val host: WaHost) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context, viewId: Int, args: Any?): PlatformView =
            WaStage(context, host)
    }

    companion object {
        const val VIEW_TYPE = "wa_hub/stage"
    }
}
