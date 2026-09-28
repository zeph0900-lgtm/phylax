package com.asksakis.freegate.tv

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import com.asksakis.freegate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TvNativeNavigationTest {
    @Test
    fun firstRunConfirmWorksWithoutInitialFocusAndDoesNotDoubleClick() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Freegate)
        controller.setup().visible()
        activity.setContentView(R.layout.fragment_home)
        activity.findViewById<View>(R.id.setup_empty_state).visibility = View.VISIBLE
        val add = activity.findViewById<View>(R.id.setup_add_server)
        val help = activity.findViewById<View>(R.id.setup_docs)
        val controls = listOf(add, help)
        TvNativeNavigation.prepare(controls)
        var clicks = 0
        add.setOnClickListener { clicks++ }
        TvNativeNavigation.focusFirst(controls)
        assertTrue(add.hasFocus())
        assertTrue(TvNativeNavigation.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER), controls))
        TvNativeNavigation.dispatch(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1), controls)
        TvNativeNavigation.dispatch(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER), controls)
        assertEquals(1, clicks)
        TvNativeNavigation.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN), controls)
        assertTrue(help.hasFocus())
        TvNativeNavigation.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN), controls)
        assertTrue(help.hasFocus())
        TvNativeNavigation.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP), controls)
        assertTrue(add.hasFocus())
        controller.pause().stop().destroy()
    }

    @Test
    fun setupFormSkipsHiddenAdvancedAndDisabledActions() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Freegate)
        controller.setup().visible()
        activity.setContentView(R.layout.fragment_setup)
        val ids = listOf(R.id.setup_url, R.id.setup_name, R.id.setup_username, R.id.setup_password,
            R.id.setup_advanced_toggle, R.id.setup_internal_url, R.id.setup_test_button, R.id.setup_save_button)
        val controls = ids.map { activity.findViewById<View>(it) }
        TvNativeNavigation.prepare(controls)
        TvNativeNavigation.focusFirst(controls)
        assertTrue(controls.first() is EditText)
        assertTrue(controls.first().hasFocus())
        repeat(4) { TvNativeNavigation.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN), controls) }
        assertTrue(controls[4].hasFocus())
        TvNativeNavigation.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN), controls)
        assertTrue(controls[6].hasFocus())
        controls[6].isEnabled = false
        controls[4].requestFocus()
        TvNativeNavigation.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN), controls)
        assertTrue(controls[7].hasFocus())
        assertFalse(TvNativeNavigation.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), controls))
        controller.pause().stop().destroy()
    }
}
