package com.asksakis.freegate.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.asksakis.freegate.BuildConfig
import com.asksakis.freegate.R
import com.asksakis.freegate.utils.PersistentLogcatWriter
import com.asksakis.freegate.utils.UpdateChecker
import kotlinx.coroutines.launch

/**
 * Settings → About. Combines release/identity info (version + "what's new" +
 * check-for-updates) with the external link rows (source, issues, third-party,
 * sponsor). External links open with a chooser intent so the user picks their
 * browser per tap — no silent default-handler grant.
 */
class AboutSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_about, rootKey)

        findPreference<AboutHeroPreference>("about_hero")?.apply {
            setVersionLabel("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            // The sponsor link now lives on Buy Me a Coffee's own button under the
            // wordmark, so the plain list row for it is gone.
            onSponsorClick = { openExternally(URL_SPONSOR) }
        }

        // The in-app updater is compiled out of the fdroid flavor (F-Droid
        // updates flow through its own repo), so hide the row entirely instead
        // of showing a tap-target that doesn't do anything.
        val checkUpdates = findPreference<Preference>("check_updates")
        if (!BuildConfig.ENABLE_UPDATE_CHECK) {
            checkUpdates?.isVisible = false
        } else {
            checkUpdates?.setOnPreferenceClickListener {
                checkForUpdatesManually()
                true
            }
        }

        bindLink("about_source", URL_SOURCE)
        bindLink("about_issues", URL_ISSUES)
        bindLink("about_changelog", URL_CHANGELOG)
        bindLink("about_third_party", URL_THIRD_PARTY)

        findPreference<Preference>("about_share_logs")?.setOnPreferenceClickListener {
            shareDebugLogs()
            true
        }
    }

    /**
     * Bundle the retained logs and hand them to a share target. The archive is built off
     * the main thread because it reads and rewrites up to a day of log files, and the
     * result can legitimately be nothing: Android does not always let an app read its own
     * logcat, so say that rather than opening an empty share sheet.
     */
    private fun shareDebugLogs() {
        val pref = findPreference<Preference>("about_share_logs")
        pref?.isEnabled = false
        pref?.summary = getString(R.string.about_collecting_logs)
        viewLifecycleOwner.lifecycleScope.launch {
            val intent = PersistentLogcatWriter.buildShareIntent(requireContext())
            pref?.isEnabled = true
            pref?.summary = getString(R.string.about_share_logs_summary)
            if (intent == null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.about_no_logs),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            runCatching {
                startActivity(Intent.createChooser(intent, getString(R.string.about_share_chooser)))
            }.onFailure {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.about_share_failed, it.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Wire a single preference key to "open this URL externally" via a chooser
     * — `ACTION_VIEW` + `Intent.createChooser` so the user picks their browser
     * once per tap instead of silently inheriting the system default handler.
     */
    private fun bindLink(prefKey: String, url: String) {
        findPreference<Preference>(prefKey)?.setOnPreferenceClickListener {
            openExternally(url)
            true
        }
    }

    /** Hand a URL to whichever browser the user picks, per tap. */
    private fun openExternally(url: String) {
        runCatching {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(Intent.createChooser(viewIntent, getString(R.string.about_open_with)))
        }
    }

    /**
     * Trigger the in-app update checker manually. Mirrors what
     * AdvancedSettingsFragment used to do before this row moved here.
     */
    private fun checkForUpdatesManually() {
        val updateChecker = UpdateChecker(requireContext())

        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            setPadding(0, 16, 0, 0)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            addView(progressBar)
        }
        val progressDialog = com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
            .setTitle(R.string.update_checking)
            .setView(layout)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val updateInfo = updateChecker.checkForUpdates(force = true)
            progressDialog.dismiss()

            when {
                updateInfo != null -> updateChecker.showUpdateDialog(
                    requireActivity() as AppCompatActivity,
                    updateInfo,
                )
                updateChecker.lastErrorMessage != null -> Toast.makeText(
                    context,
                    getString(R.string.update_check_failed, updateChecker.lastErrorMessage ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
                else -> Toast.makeText(
                    context,
                    getString(R.string.update_latest),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private companion object {
        const val URL_SOURCE = "https://github.com/sfortis/phylax"
        const val URL_ISSUES = "https://github.com/sfortis/phylax/issues"
        const val URL_CHANGELOG = "https://github.com/sfortis/phylax/releases"
        const val URL_THIRD_PARTY = "https://github.com/sfortis/phylax/blob/main/THIRD_PARTY_NOTICES.md"
        const val URL_SPONSOR = "https://www.buymeacoffee.com/sfortis"
    }
}
