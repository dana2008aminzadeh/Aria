package ir.aria.downloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.aria.downloader.R
import ir.aria.downloader.data.FileSaver
import ir.aria.downloader.databinding.ActivityDiagnosticsBinding
import ir.aria.downloader.diag.DiagLog
import ir.aria.downloader.engine.ExtractorEngine
import ir.aria.downloader.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diag-build only log viewer/exporter (Settings -> «لاگ تشخیصی»).
 *
 * The tester feeds the diag build links from every service, reproduces what misbehaves, then
 * shares the one exported `.log` from here (or saves it into `Download/AllDown/`). Everything
 * the app does is already in that file: see [DiagLog] and DIAGNOSTICS.md.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val handler = Handler(Looper.getMainLooper())
    private var shownOnce = false

    private val followRunnable = object : Runnable {
        override fun run() {
            if (binding.diagFollowCheck.isChecked) {
                refresh(scrollToEnd = true)
                handler.postDelayed(this, 2_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        DiagLog.i("DiagUI", "diagnostics screen opened enabled=" + DiagLog.enabled)

        if (!DiagLog.enabled) {
            binding.diagLogText.text = getString(R.string.diag_disabled)
            return
        }

        binding.diagRefreshButton.setOnClickListener {
            DiagLog.d("DiagUI", "manual refresh")
            refresh(scrollToEnd = true)
        }
        binding.diagShareButton.setOnClickListener { shareLog() }
        binding.diagSaveButton.setOnClickListener { saveLogToDownloads() }
        binding.diagCopyButton.setOnClickListener { copyTail() }
        binding.diagClearButton.setOnClickListener { confirmClear() }
        binding.diagEngineButton.setOnClickListener { runEngineTest() }
        binding.diagFollowCheck.setOnCheckedChangeListener { _, checked ->
            DiagLog.d("DiagUI", "follow=$checked")
            handler.removeCallbacks(followRunnable)
            if (checked) handler.post(followRunnable)
        }
        refresh(scrollToEnd = true)
    }

    override fun onResume() {
        super.onResume()
        if (DiagLog.enabled && binding.diagFollowCheck.isChecked) {
            handler.removeCallbacks(followRunnable)
            handler.postDelayed(followRunnable, 2_000L)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(followRunnable)
        super.onPause()
    }

    private fun refresh(scrollToEnd: Boolean) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { DiagLog.tailText() }
            val size = DiagLog.logFileSize()
            if (isFinishing || isDestroyed) return@launch
            binding.diagStats.text = getString(
                R.string.diag_stats, Formats.bytes(size), DiagLog.logFilePath() ?: "-"
            )
            binding.diagLogText.text = text.ifEmpty { getString(R.string.diag_empty) }
            if (scrollToEnd || !shownOnce) {
                shownOnce = true
                binding.diagScroll.post {
                    binding.diagScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun shareLog() {
        DiagLog.i("DiagUI", "share clicked")
        lifecycleScope.launch {
            val export = withContext(Dispatchers.IO) { DiagLog.buildExportFile(this@DiagnosticsActivity) }
            val uri = FileProvider.getUriForFile(
                this@DiagnosticsActivity, packageName + ".fileprovider", export
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, export.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.diag_share)))
        }
    }

    private fun saveLogToDownloads() {
        DiagLog.i("DiagUI", "save-to-downloads clicked")
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val export = DiagLog.buildExportFile(this@DiagnosticsActivity)
                FileSaver.save(this@DiagnosticsActivity, export, export.name, "text/plain", emptySet())
            }.onSuccess { saved ->
                DiagLog.i("DiagUI", "log saved to Download/" + FileSaver.SUB_FOLDER + "/" + saved.displayName)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DiagnosticsActivity,
                        getString(R.string.diag_saved, saved.displayName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { error ->
                DiagLog.e("DiagUI", "save log failed", error)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DiagnosticsActivity, R.string.error_open_file, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun copyTail() {
        DiagLog.i("DiagUI", "copy tail clicked")
        lifecycleScope.launch {
            // Clipboard transactions have a size ceiling: copy the most recent slice and say so.
            val text = withContext(Dispatchers.IO) { DiagLog.tailText(maxChars = 90_000) }
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            runCatching { manager.setPrimaryClip(ClipData.newPlainText("alldown-diag", text)) }
            Toast.makeText(this@DiagnosticsActivity, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diag_clear)
            .setMessage(R.string.diag_clear_confirm)
            .setPositiveButton(R.string.diag_clear) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    DiagLog.clear(this@DiagnosticsActivity)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DiagnosticsActivity, R.string.diag_cleared, Toast.LENGTH_SHORT).show()
                        refresh(scrollToEnd = true)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runEngineTest() {
        DiagLog.i("DiagUI", "engine self-test clicked")
        binding.diagEngineButton.isEnabled = false
        Toast.makeText(this, R.string.diag_engine_testing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = ExtractorEngine.get(this@DiagnosticsActivity).selfTest()
            DiagLog.i("DiagUI", "engine self-test result:\n" + result)
            binding.diagEngineButton.isEnabled = true
            Toast.makeText(this@DiagnosticsActivity, result.lineSequence().firstOrNull() ?: "", Toast.LENGTH_SHORT).show()
            refresh(scrollToEnd = true)
        }
    }
}
