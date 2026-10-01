package com.asksakis.freegate.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.asksakis.freegate.R

/**
 * Top-level Settings screen. Each category preference navigates to its own child
 * fragment via the nav graph — back/up behave naturally, slide animation, ActionBar
 * title flips automatically (destination label).
 *
 * The server picker is intentionally NOT exposed here: it lives at the top of the
 * Connection screen so server selection and the per-server settings stay in one
 * place and stop competing for the user's mental model.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_root, rootKey)
        findPreference<ListPreference>("app_language")?.apply {
            summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue ->
                val language = newValue.toString()
                val locales = if (language == "system") {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(language)
                }
                AppCompatDelegate.setApplicationLocales(locales)
                true
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val actionId = when (preference.key) {
            "cat_connection" -> R.id.action_settings_to_connection
            "cat_notifications" -> R.id.action_settings_to_notifications
            "cat_downloads" -> R.id.action_settings_to_downloads
            "cat_advanced" -> R.id.action_settings_to_advanced
            "cat_about" -> R.id.action_settings_to_about
            else -> return super.onPreferenceTreeClick(preference)
        }
        findNavController().navigate(actionId)
        return true
    }
}
