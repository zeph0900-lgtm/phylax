package com.asksakis.freegate.ui.export

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Dedicated launcher entry for arbitrary-time Frigate clip downloads.
 *
 * The Phylax home screen intentionally has no drawer/hamburger button, so exposing
 * this tool as its own launcher entry keeps it reachable without modifying the stable
 * Home WebView/navigation chrome.
 */
class ClipExportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = FrameLayout(this).apply {
            id = android.view.View.generateViewId()
        }
        setContentView(container)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(container.id, ClipExportFragment())
                .commit()
        }
    }
}
