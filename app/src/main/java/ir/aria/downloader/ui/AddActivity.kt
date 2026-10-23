package ir.aria.downloader.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.aria.downloader.R
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.databinding.ActivityAddBinding
import ir.aria.downloader.engine.ExtractorEngine
import ir.aria.downloader.engine.LinkIngestor
import ir.aria.downloader.net.UrlExtractor
import ir.aria.downloader.service.DownloadService
import ir.aria.downloader.util.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add screen: paste any text (one or many links, playlist links, batch patterns),
 * pick a quality preset, then the [LinkIngestor] turns everything into queue items.
 */
class AddActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddBinding
    private lateinit var repo: DownloadRepository
    private var previewJob: Job? = null
    private var busy = false

    private val presetKeys = arrayOf("best", "1080", "720", "480", "360", "audio")

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* system status is visible and recoverable in Settings */ }

    private val openTextFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (!text.isNullOrBlank()) appendText(text)
            else Toast.makeText(this, R.string.invalid_urls, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureStoragePermission()
        binding = ActivityAddBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = DownloadRepository.get(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.fileButton.setOnClickListener { openTextFile.launch(arrayOf("text/*", "application/txt")) }
        binding.clearButton.setOnClickListener { binding.urlInput.setText("") }
        binding.startButton.setOnClickListener { startDownloads() }

        setupQualityPicker()

        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                schedulePreview(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        preloadFromIntent(intent)
        requestNotificationPermissionOnce()
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("aria_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notification_permission_requested", false)) return
        prefs.edit().putBoolean("notification_permission_requested", true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun presetLabels(): List<String> = listOf(
        getString(R.string.quality_best),
        getString(R.string.quality_1080),
        getString(R.string.quality_720),
        getString(R.string.quality_480),
        getString(R.string.quality_360),
        getString(R.string.quality_audio)
    )

    private fun setupQualityPicker() {
        val labels = presetLabels()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        binding.qualitySpinner.setAdapter(adapter)
        val saved = repo.settings.defaultPreset
        val index = presetKeys.indexOf(saved).coerceAtLeast(0)
        binding.qualitySpinner.setText(labels[index], false)
        binding.qualitySpinner.setOnItemClickListener { _, _, position, _ ->
            repo.settings.defaultPreset = presetKeys[position]
        }
    }

    private fun selectedPreset(): String {
        val text = binding.qualitySpinner.text?.toString().orEmpty()
        val index = presetLabels().indexOf(text)
        return if (index >= 0) presetKeys[index] else "best"
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 102
        )
    }

    private fun preloadFromIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) appendText(text)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
                val combined = buildString {
                    texts?.forEach { if (it != null) append(it.toString()).append('\n') }
                }
                if (combined.isNotBlank()) appendText(combined)
            }
        }
    }

    private fun appendText(text: String) {
        val current = binding.urlInput.text?.toString().orEmpty()
        val merged = if (current.isBlank()) text else "$current\n$text"
        binding.urlInput.setText(merged)
        binding.urlInput.setSelection(merged.length)
    }

    private fun pasteFromClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = manager.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.invalid_urls, Toast.LENGTH_SHORT).show()
        } else {
            appendText(text)
        }
    }

    private fun schedulePreview(text: String) {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(220)
            try {
                val urls = withContext(Dispatchers.Default) { UrlExtractor.parse(text) }
                binding.previewText.text =
                    if (urls.isEmpty()) "" else getString(R.string.links_found, urls.size)
            } catch (t: Throwable) {
                // The preview is cosmetic: it must never be able to take the screen down.
                Logs.e(this@AddActivity, "add", "preview failed", t)
                binding.previewText.text = ""
            }
        }
    }

    private fun startDownloads() {
        if (busy) return
        val raw = binding.urlInput.text?.toString().orEmpty()
        busy = true
        binding.startButton.isEnabled = false
        binding.progressText.visibility = View.VISIBLE
        binding.progressText.text = getString(R.string.preparing)
        Logs.d(this, "add", "add pressed, " + raw.length + " chars")

        lifecycleScope.launch {
            try {
                // All the heavy work (pattern expansion, yt-dlp probe, spotify http) must run
                // off the main thread: it used to block the UI thread and could take the app down.
                val report = withContext(Dispatchers.IO) {
                    val links = UrlExtractor.parse(raw)
                    if (links.isEmpty()) return@withContext null
                    Logs.d(this@AddActivity, "add", "parsed " + links.size + " link(s)")
                    val engine = ExtractorEngine.get(this@AddActivity)
                    val ingestor = LinkIngestor(engine)
                    ingestor.ingest(links, selectedPreset()) { status ->
                        Logs.d(this@AddActivity, "add", "status: " + status)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) binding.progressText.text = status
                        }
                    }
                }

                if (report == null) {
                    finishBusy()
                    Toast.makeText(this@AddActivity, R.string.invalid_urls, Toast.LENGTH_LONG).show()
                    return@launch
                }

                val added = withContext(Dispatchers.IO) { repo.addRequests(report.requests) }
                Logs.d(
                    this@AddActivity, "add",
                    "queued " + added.size + " of " + report.requests.size +
                        " (warnings=" + report.warnings.size + ")"
                )
                if (added.isNotEmpty()) {
                    DownloadService.send(this@AddActivity, DownloadService.ACTION_START)
                }
                finishBusy()
                showResult(added.size, report)
            } catch (t: Throwable) {
                Logs.e(this@AddActivity, "add", "add flow failed", t)
                finishBusy()
                showFailure(t)
            }
        }
    }

    /** Always tells the user exactly what happened, instead of silently closing the screen. */
    private fun showResult(addedCount: Int, report: LinkIngestor.Report) {
        val message = StringBuilder()
        message.append(getString(R.string.ingest_report, addedCount))
        if (report.warnings.isNotEmpty()) {
            message.append("\n\n")
            message.append(report.warnings.joinToString("\n• ", prefix = "• "))
        }
        val hasWarnings = report.warnings.isNotEmpty()

        if (addedCount > 0 && !hasWarnings) {
            Toast.makeText(this, getString(R.string.added_n, addedCount), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (addedCount > 0) R.string.added_title else R.string.nothing_added)
            .setMessage(message.toString())
            .setPositiveButton(R.string.done) { _, _ -> if (addedCount > 0) finish() }
            .setNegativeButton(R.string.copy) { _, _ ->
                copyToClipboard(message.toString())
                if (addedCount > 0) finish()
            }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun showFailure(error: Throwable) {
        val details = Logs.stack(error)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_failed_title)
            .setMessage(error.javaClass.simpleName + "\n" + (error.message ?: ""))
            .setPositiveButton(R.string.copy) { _, _ -> copyToClipboard(details) }
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
    }

    private fun copyToClipboard(text: String) {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("aria", text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishBusy() {
        busy = false
        binding.startButton.isEnabled = true
        binding.progressText.visibility = View.GONE
    }
}
