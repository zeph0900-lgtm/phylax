package com.asksakis.freegate.ui.settings

import com.asksakis.freegate.R
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.asksakis.freegate.notifications.FrigateConfigFetcher
import com.asksakis.freegate.utils.FrigateNameFormatter
import com.asksakis.freegate.utils.NetworkUtils
import kotlinx.coroutines.launch

/**
 * Per-camera opt-in for motion notifications. One [SwitchPreferenceCompat] per Frigate
 * camera; the checked set is stored in the `motion_notify_cameras` StringSet, which the
 * alert service treats as strict opt-in (only listed cameras notify, empty = feature off).
 *
 * This deliberately does NOT share the "empty means all" semantics of the review camera
 * filter ([NotificationCamerasFragment]): motion is off by default, opt-in only, so an
 * empty set means "no motion notifications" rather than "all cameras".
 */
class NotificationMotionCamerasFragment : PreferenceFragmentCompat() {

    private lateinit var networkUtils: NetworkUtils
    private lateinit var prefs: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        networkUtils = NetworkUtils.getInstance(requireContext())
        prefs = preferenceManager.sharedPreferences!!

        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val placeholder = Preference(requireContext()).apply {
            title = getString(R.string.loading_cameras)
            summary = getString(R.string.fetching_frigate_config)
            isSelectable = false
        }
        preferenceScreen.addPreference(placeholder)

        loadCameras()
    }

    private fun loadCameras() {
        val baseUrl = networkUtils.getUrl().trimEnd('/').takeIf { it.isNotBlank() }
        if (baseUrl == null) {
            showError(getString(R.string.set_frigate_url_first))
            return
        }
        lifecycleScope.launch {
            val cameras = FrigateConfigFetcher(requireContext()).fetchCameraNames(baseUrl)
            if (cameras.isEmpty()) {
                showError(getString(R.string.couldnt_fetch_cameras))
                return@launch
            }
            renderScreen(cameras)
        }
    }

    private fun renderScreen(cameras: List<String>) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        val selected = prefs.getStringSet(KEY_MOTION_CAMERAS, emptySet()).orEmpty()

        val hint = Preference(ctx).apply {
            order = -1
            isSelectable = false
            isIconSpaceReserved = false
            title = getString(R.string.motion_notifications_title)
            summary = getString(R.string.motion_notifications_summary)
        }
        screen.addPreference(hint)

        for (camera in cameras) {
            val switch = SwitchPreferenceCompat(ctx).apply {
                key = "motion_camera:$camera"
                title = FrigateNameFormatter.pretty(camera)
                // Single source of truth is the StringSet; per-switch persistence off so a
                // server/filter swap can't leave stale per-key booleans behind.
                isPersistent = false
                isChecked = camera in selected
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, newValue ->
                    val checked = newValue as Boolean
                    val updated = prefs.getStringSet(KEY_MOTION_CAMERAS, emptySet())
                        .orEmpty().toMutableSet()
                    if (checked) updated += camera else updated -= camera
                    prefs.edit().putStringSet(KEY_MOTION_CAMERAS, updated).apply()
                    true
                }
            }
            screen.addPreference(switch)
        }

        preferenceScreen = screen
    }

    private fun showError(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        screen.addPreference(
            Preference(ctx).apply {
                title = getString(R.string.unable_load_cameras)
                summary = message
                isSelectable = false
            },
        )
        preferenceScreen = screen
    }

    private companion object {
        const val KEY_MOTION_CAMERAS = "motion_notify_cameras"
    }
}
