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
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Add screen: paste any text (one or many links, playlist links, batch patterns),
 * then probes every media item and asks for its exact format and output before queueing.
 */
class AddActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFILL = "ir.aria.downloader.extra.PREFILL"
        const val EXTRA_PLATFORM_HINT = "ir.aria.downloader.extra.PLATFORM_HINT"
    }


    private lateinit var binding: ActivityAddBinding
    private lateinit var repo: DownloadRepository
    private var previewJob: Job? = null
    private var busy = false

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
        val prefill = intent?.getStringExtra(EXTRA_PREFILL)
        if (!prefill.isNullOrBlank()) appendText(prefill)
        val hint = intent?.getStringExtra(EXTRA_PLATFORM_HINT)
        if (!hint.isNullOrBlank()) Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
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
                Log.e("AriaAdd", "preview failed", t)
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
        Log.d("AriaAdd", "add pressed, " + raw.length + " chars")

        lifecycleScope.launch {
            try {
                // All the heavy work (pattern expansion, yt-dlp probe, spotify http) must run
                // off the main thread: it used to block the UI thread and could take the app down.
                val report = withContext(Dispatchers.IO) {
                    val links = UrlExtractor.parse(raw)
                    if (links.isEmpty()) return@withContext null
                    Log.d("AriaAdd", "parsed " + links.size + " link(s)")
                    val engine = ExtractorEngine.get(this@AddActivity)
                    val ingestor = LinkIngestor(engine)
                    ingestor.ingest(
                        links = links,
                        preset = repo.settings.defaultPreset,
                        chooser = { request -> chooseFormat(request) }
                    ) { status ->
                        Log.d("AriaAdd", "status: " + status)
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
                Log.d(
                    "AriaAdd",
                    "queued " + added.size + " of " + report.requests.size +
                        " (warnings=" + report.warnings.size + ")"
                )
                if (added.isNotEmpty()) {
                    DownloadService.send(this@AddActivity, DownloadService.ACTION_START)
                }
                finishBusy()
                showResult(added.size, report)
            } catch (t: Throwable) {
                Log.e("AriaAdd", "add flow failed", t)
                finishBusy()
                showFailure(t)
            }
        }
    }

    private suspend fun chooseFormat(
        request: LinkIngestor.ChoiceRequest
    ): ExtractorEngine.FormatSelection? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            if (isFinishing || isDestroyed) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val dialog = try {
                FormatChooserDialog.show(
                    context = this@AddActivity,
                    title = request.displayTitle.ifBlank { request.probe.title },
                    formats = request.probe.formats,
                    audioOnly = request.audioOnly
                ) { selection ->
                    if (continuation.isActive) continuation.resume(selection)
                }
            } catch (t: Throwable) {
                Log.e("AriaAdd", "format chooser failed", t)
                if (continuation.isActive) continuation.resume(null)
                null
            }
            continuation.invokeOnCancellation { dialog?.dismiss() }
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
        val details = Log.getStackTraceString(error)
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
