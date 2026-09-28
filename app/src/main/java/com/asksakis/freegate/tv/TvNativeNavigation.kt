package com.asksakis.freegate.tv

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.asksakis.freegate.R

/** Ordered navigation for native setup/connection screens, never the WebView or IME. */
object TvNativeNavigation {
    fun prepare(controls: List<View>) {
        controls.forEach {
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            it.foreground = it.context.getDrawable(R.drawable.tv_focus_ring)
        }
    }

    fun focusFirst(controls: List<View>) {
        controls.firstOrNull { it.isShown && it.isEnabled }?.requestFocus()
    }

    fun dispatch(event: KeyEvent, controls: List<View>): Boolean {
        val confirm = event.keyCode in setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
        )
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> 0
        }
        val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!confirm && direction == 0 && !horizontal) return false
        if (horizontal || event.action != KeyEvent.ACTION_DOWN) return true
        val available = controls.filter { it.isShown && it.isEnabled }
        if (available.isEmpty()) return true
        val index = available.indexOfFirst { it.hasFocus() }
        val target = if (index < 0) available.first() else available[index]
        if (confirm) {
            if (event.repeatCount != 0) return true
            target.requestFocus()
            if (target is EditText) {
                val keyboard = target.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                keyboard.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
            } else {
                // Activate once on key-down. Navigation may replace the view before key-up.
                target.performClick()
            }
        } else {
            val next = if (index < 0) 0 else (index + direction).coerceIn(0, available.lastIndex)
            available[next].requestFocus()
        }
        return true
    }
}
