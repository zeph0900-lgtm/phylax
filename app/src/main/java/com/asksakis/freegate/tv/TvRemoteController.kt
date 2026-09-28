package com.asksakis.freegate.tv

import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.content.Context
import org.json.JSONObject
import java.util.ArrayDeque

/** One Android event owner. Web keys never fall through to WebView spatial navigation. */
class TvRemoteController(
    private val web: WebView,
    private val openSettings: () -> Unit,
    private val exit: () -> Unit,
) {
    private companion object { const val MAX_QUEUED_KEYS = 6 }

    private val queue = ArrayDeque<String>()
    private var busy = false
    private var disposed = false
    private var epoch = 0

    fun install() {
        epoch++
        queue.clear()
        busy = false
        val script = web.context.assets.open("tv_viewer.js").bufferedReader().use { it.readText() }
        web.evaluateJavascript(script, null)
        web.isFocusableInTouchMode = true
        web.requestFocus()
    }

    fun dispatch(event: KeyEvent): Boolean {
        val command = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> "ok"
            KeyEvent.KEYCODE_MENU -> "menu"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "play"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0 || command in setOf("up", "down", "left", "right")) {
                send(command)
            }
        }
        return true
    }

    fun back() = send("back")

    fun dispose() {
        disposed = true
        epoch++
        queue.clear()
    }

    private fun send(command: String) {
        if (disposed || queue.size >= MAX_QUEUED_KEYS) return
        queue.addLast(command)
        drain()
    }

    private fun drain() {
        if (busy || disposed || queue.isEmpty()) return
        busy = true
        val generation = epoch
        val command = queue.removeFirst()
        val script = "window.PhylaxTV ? window.PhylaxTV.key(${JSONObject.quote(command)}) : 'unready'"
        web.evaluateJavascript(script) { raw ->
            if (disposed || generation != epoch) return@evaluateJavascript
            busy = false
            when (raw) {
                "\"settings\"" -> {
                    queue.clear()
                    openSettings()
                    return@evaluateJavascript
                }
                "\"exit\"" -> exit()
                "\"ime\"" -> {
                    val ime = web.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    ime.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
                }
                "\"unready\"" -> if (command == "back" || command == "menu") openSettings()
            }
            drain()
        }
    }
}
