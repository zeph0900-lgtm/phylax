package com.asksakis.freegate.ui.export

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.asksakis.freegate.download.DownloadHandler
import com.asksakis.freegate.notifications.FrigateConfigFetcher
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.NetworkUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Downloads an arbitrary time range from Frigate recordings.
 *
 * Frigate's recording clip endpoint is used deliberately here instead of creating a
 * persistent Export entry on the NVR: the selected range is streamed as MP4 straight
 * into the app's existing DownloadHandler, so the user gets a normal local file while
 * Frigate's /exports directory stays untouched.
 */
class ClipExportFragment : Fragment() {

    private lateinit var cameraSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var downloadButton: Button
    private lateinit var loading: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var downloadHandler: DownloadHandler

    private var rootView: View? = null
    private var cameraNames: List<String> = emptyList()

    // End slightly in the past so Frigate has had time to finish the last recording segment.
    private var endMillis: Long = System.currentTimeMillis() - 10_000L
    private var startMillis: Long = endMillis - 5 * 60_000L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        rootView = scroll

        TextView(context).apply {
            text = "下載任意時間影片"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also(root::addView)

        TextView(context).apply {
            text = "選擇攝影機與開始、結束時間。影片會直接下載到你在 Phylax『下載』設定指定的位置，不會建立永久 Export。"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(18))
        }.also(root::addView)

        TextView(context).apply {
            text = "攝影機"
            textSize = 16f
        }.also(root::addView)

        cameraSpinner = Spinner(context)
        cameraSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("正在讀取攝影機…"),
        )
        root.addView(cameraSpinner, matchWrap())

        loading = ProgressBar(context).apply {
            isIndeterminate = true
        }
        root.addView(loading, wrapWrap())

        TextView(context).apply {
            text = "時間範圍"
            textSize = 16f
            setPadding(0, dp(18), 0, dp(4))
        }.also(root::addView)

        startButton = Button(context).apply {
            setOnClickListener {
                pickDateTime(startMillis) {
                    startMillis = it
                    refreshTimeLabels()
                }
            }
        }
        root.addView(startButton, matchWrap())

        endButton = Button(context).apply {
            setOnClickListener {
                pickDateTime(endMillis) {
                    endMillis = it
                    refreshTimeLabels()
                }
            }
        }
        root.addView(endButton, matchWrap())

        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        listOf(5 to "前 5 分", 15 to "前 15 分", 30 to "前 30 分").forEach { (minutes, label) ->
            presetRow.addView(
                Button(context).apply {
                    text = label
                    setOnClickListener {
                        endMillis = System.currentTimeMillis() - 10_000L
                        startMillis = endMillis - minutes * 60_000L
                        refreshTimeLabels()
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        root.addView(presetRow, matchWrap())

        downloadButton = Button(context).apply {
            text = "下載 MP4"
            isEnabled = false
            setOnClickListener { startDownload() }
        }
        root.addView(downloadButton, matchWrap())

        statusText = TextView(context).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(statusText, matchWrap())

        refreshTimeLabels()

        downloadHandler = DownloadHandler(
            context = context.applicationContext,
            scope = lifecycleScope,
            clientCertManager = ClientCertManager.getInstance(context),
            callbacks = object : DownloadHandler.Callbacks {
                override fun onDownloadStarted(fileName: String) {
                    statusText.text = "正在下載：$fileName"
                    downloadButton.isEnabled = false
                    Toast.makeText(context, "已開始下載", Toast.LENGTH_SHORT).show()
                }

                override fun onDownloadCompleted(fileName: String, file: File) {
                    statusText.text = "下載完成：$fileName"
                    downloadButton.isEnabled = cameraNames.isNotEmpty()
                    rootView?.let { rootForSnackbar ->
                        Snackbar.make(rootForSnackbar, "下載完成：$fileName", Snackbar.LENGTH_LONG)
                            .setAction("開啟") { DownloadHandler.openFile(context, file) }
                            .show()
                    }
                }

                override fun onDownloadFailed(fileName: String, error: String) {
                    statusText.text = "下載失敗：$error"
                    downloadButton.isEnabled = cameraNames.isNotEmpty()
                    Toast.makeText(context, "下載失敗：$error", Toast.LENGTH_LONG).show()
                }
            },
        )

        loadCameras()
        return scroll
    }

    override fun onDestroyView() {
        rootView = null
        super.onDestroyView()
    }

    private fun loadCameras() {
        val context = requireContext()
        val baseUrl = NetworkUtils.getInstance(context).bestKnownBaseUrl()
        if (baseUrl.isNullOrBlank()) {
            loading.visibility = View.GONE
            statusText.text = "找不到目前 Frigate 伺服器，請先回首頁確認連線。"
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val names = FrigateConfigFetcher(context).fetchCameraNames(baseUrl)
            if (!isAdded) return@launch
            loading.visibility = View.GONE
            cameraNames = names
            if (names.isEmpty()) {
                cameraSpinner.adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("無法取得攝影機"),
                )
                statusText.text = "無法從 Frigate /api/config 取得攝影機，請確認伺服器連線與登入狀態。"
                downloadButton.isEnabled = false
            } else {
                cameraSpinner.adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    names,
                )
                statusText.text = "已讀取 ${names.size} 台攝影機"
                downloadButton.isEnabled = true
            }
        }
    }

    private fun startDownload() {
        val context = requireContext()
        val camera = cameraNames.getOrNull(cameraSpinner.selectedItemPosition)
        if (camera == null) {
            Toast.makeText(context, "請先選擇攝影機", Toast.LENGTH_SHORT).show()
            return
        }
        if (startMillis >= endMillis) {
            Toast.makeText(context, "開始時間必須早於結束時間", Toast.LENGTH_LONG).show()
            return
        }
        if (endMillis > System.currentTimeMillis() + 1_000L) {
            Toast.makeText(context, "結束時間不能在未來", Toast.LENGTH_LONG).show()
            return
        }

        val baseUrl = NetworkUtils.getInstance(context).bestKnownBaseUrl()
        if (baseUrl.isNullOrBlank()) {
            Toast.makeText(context, "找不到目前 Frigate 伺服器", Toast.LENGTH_LONG).show()
            return
        }

        val startSec = startMillis / 1_000L
        val endSec = endMillis / 1_000L
        val encodedCamera = Uri.encode(camera)
        val url = "${baseUrl.trimEnd('/')}/api/$encodedCamera/start/$startSec/end/$endSec/clip.mp4"
        val safeCamera = camera.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "${safeCamera}_${fileStamp.format(Date(startMillis))}_${fileStamp.format(Date(endMillis))}.mp4"

        statusText.text = "向 Frigate 取得影片…"
        downloadButton.isEnabled = false
        downloadHandler.handleWebViewDownload(
            url = url,
            userAgent = WebSettings.getDefaultUserAgent(context),
            contentDisposition = "attachment; filename=\"$fileName\"",
            mimetype = "video/mp4",
            currentPageUrl = baseUrl,
        )
    }

    private fun pickDateTime(initialMillis: Long, onPicked: (Long) -> Unit) {
        val initial = Calendar.getInstance().apply { timeInMillis = initialMillis }
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    timeInMillis = initialMillis
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        selected.set(Calendar.HOUR_OF_DAY, hour)
                        selected.set(Calendar.MINUTE, minute)
                        selected.set(Calendar.SECOND, 0)
                        selected.set(Calendar.MILLISECOND, 0)
                        onPicked(selected.timeInMillis)
                    },
                    initial.get(Calendar.HOUR_OF_DAY),
                    initial.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun refreshTimeLabels() {
        val f = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
        startButton.text = "開始：${f.format(Date(startMillis))}"
        endButton.text = "結束：${f.format(Date(endMillis))}"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun wrapWrap() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
