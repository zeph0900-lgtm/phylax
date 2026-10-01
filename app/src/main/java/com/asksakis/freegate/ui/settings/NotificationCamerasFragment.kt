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
 * Flat camera-filter screen. One [SwitchPreferenceCompat] per Frigate camera. Empty
 * selection still means "all cameras" (the AlertFilter pass-through), so switching
 * everything off doesn't accidentally silence notifications — we treat that identically
 * to the "no selection" default. When at least one is on, only those cameras pass.
 */
class NotificationCamerasFragment : PreferenceFragmentCompat() {

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
            val cameras = FrigateConfigFetcher(requireContext())
                .fetchCameraNames(baseUrl)
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
        val selected = prefs.getStringSet("notify_cameras", emptySet()).orEmpty()
        // Cache the total so the parent screen can render "All cameras" when
        // the saved set is either empty or full — both states mean "no filter".
        prefs.edit().putInt("notify_cameras_total", cameras.size).apply()

        val switches = mutableListOf<SwitchPreferenceCompat>()
        val selectAll = Preference(ctx).apply {
            isIconSpaceReserved = false
            order = -1
            applySelectAllSummary(selected, cameras)
        }
        screen.addPreference(selectAll)

        for (camera in cameras) {
            val switch = SwitchPreferenceCompat(ctx).apply {
                key = "notify_camera:$camera"
                title = FrigateNameFormatter.pretty(camera)
                // Authoritative state lives in the `notify_cameras` StringSet —
                // see the zones fragment for the dual-persistence pitfall this
                // avoids. Disabling per-switch persistence keeps the StringSet
                // as the single source of truth across server filter swaps.
                isPersistent = false
                isChecked = camera in selected
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, newValue ->
                    val checked = newValue as Boolean
                    val updated = prefs.getStringSet("notify_cameras", emptySet())
                        .orEmpty().toMutableSet()
                    if (checked) updated += camera else updated -= camera
                    prefs.edit().putStringSet("notify_cameras", updated).apply()
                    selectAll.applySelectAllSummary(updated, cameras)
                    true
                }
            }
            screen.addPreference(switch)
            switches += switch
        }

        selectAll.setOnPreferenceClickListener {
            val current = prefs.getStringSet("notify_cameras", emptySet()).orEmpty()
            val isAllSelected = current.size >= cameras.size && cameras.isNotEmpty()
            val updated: Set<String> = if (isAllSelected) emptySet() else cameras.toSet()
            prefs.edit().putStringSet("notify_cameras", updated).apply()
            for (s in switches) {
                val cam = s.key.removePrefix("notify_camera:")
                s.isChecked = cam in updated
            }
            selectAll.applySelectAllSummary(updated, cameras)
            true
        }

        preferenceScreen = screen
    }

    /**
     * Header row that doubles as a select-all / deselect-all toggle. Mirrors
     * the zones picker: empty means "muted", full means "no filter", partial
     * shows the count.
     */
    private fun Preference.applySelectAllSummary(selected: Set<String>, allCameras: List<String>) {
        val isAll = allCameras.isNotEmpty() && selected.size >= allCameras.size
        val isEmpty = selected.isEmpty()
        title = getString(if (isAll) R.string.deselect_all else R.string.select_all)
        summary = when {
            isEmpty -> getString(R.string.no_cameras_muted)
            isAll -> getString(R.string.all_cameras_notify)
            else -> getString(R.string.camera_selection_count, selected.size, allCameras.size)
        }
    }

    private fun showError(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        val msg = Preference(ctx).apply {
            title = getString(R.string.unable_load_cameras)
            summary = message
            isSelectable = false
        }
        screen.addPreference(msg)
        preferenceScreen = screen
    }
}
