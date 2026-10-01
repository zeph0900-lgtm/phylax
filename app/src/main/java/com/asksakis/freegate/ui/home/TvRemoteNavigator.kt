package com.asksakis.freegate.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import android.webkit.WebView

/**
 * Android TV remote-control bridge for Frigate's WebView UI.
 *
 * D-pad uses spatial focus navigation by default. Holding OK switches to a virtual
 * cursor fallback for controls that Frigate renders without useful focus semantics.
 */
object TvRemoteNavigator {

    private const val LONG_PRESS_REPEAT_THRESHOLD = 4
    private const val SCRIPT_ASSET = "tv_remote.js"

    @Volatile
    private var scriptCache: String? = null

    fun isTelevision(context: Context): Boolean {
        val type = context.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        return type == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun install(webView: WebView) {
        if (!isTelevision(webView.context)) return

        var okLongPressHandled = false

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.setOnKeyListener { _, keyCode, event ->
            when {
                isOkKey(keyCode) -> {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (
                                event.repeatCount >= LONG_PRESS_REPEAT_THRESHOLD &&
                                !okLongPressHandled
                            ) {
                                okLongPressHandled = true
                                toggleCursor(webView)
                            }
                            true
                        }

                        KeyEvent.ACTION_UP -> {
                            if (!okLongPressHandled) {
                                activate(webView)
                            }
                            okLongPressHandled = false
                            true
                        }

                        else -> true
                    }
                }

                keyCode == KeyEvent.KEYCODE_MENU &&
                    event.action == KeyEvent.ACTION_DOWN -> {
                    toggleCursor(webView)
                    true
                }

                event.action == KeyEvent.ACTION_DOWN -> {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> move(webView, "left")
                        KeyEvent.KEYCODE_DPAD_RIGHT -> move(webView, "right")
                        KeyEvent.KEYCODE_DPAD_UP -> move(webView, "up")
                        KeyEvent.KEYCODE_DPAD_DOWN -> move(webView, "down")
                        else -> return@setOnKeyListener false
                    }
                    true
                }

                else -> false
            }
        }
    }

    fun onPageReady(webView: WebView) {
        if (!isTelevision(webView.context)) return

        webView.requestFocus()
        webView.evaluateJavascript(loadScript(webView.context), null)
    }

    private fun isOkKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A

    private fun move(webView: WebView, direction: String) {
        call(webView, "move('$direction')")
    }

    private fun activate(webView: WebView) {
        call(webView, "activate()")
    }

    private fun toggleCursor(webView: WebView) {
        call(webView, "toggleCursor()")
    }

    private fun call(webView: WebView, expression: String) {
        webView.evaluateJavascript(
            "window.__phylaxTvRemote && window.__phylaxTvRemote.$expression;",
            null,
        )
    }

    private fun loadScript(context: Context): String {
        scriptCache?.let { return it }

        return synchronized(this) {
            scriptCache ?: context.assets.open(SCRIPT_ASSET)
                .bufferedReader()
                .use { it.readText() }
                .also { scriptCache = it }
        }
    }
}
