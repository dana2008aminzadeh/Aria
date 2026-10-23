package ir.aria.downloader.ui

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.aria.downloader.BuildConfig
import ir.aria.downloader.R
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.data.Settings
import ir.aria.downloader.databinding.ActivitySettingsBinding
import ir.aria.downloader.engine.ExtractorEngine
import ir.aria.downloader.net.ProxyUrlValidator
import ir.aria.downloader.service.DownloadService
import ir.aria.downloader.service.NotificationSupport
import ir.aria.downloader.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings
    private lateinit var repo: DownloadRepository

    private val presetKeys = arrayOf("best", "1080", "720", "480", "360", "audio")

    private val pickCookies = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: return@withContext false
                    val looksRight = text.contains("# Netscape HTTP Cookie File") ||
                        text.contains("# HTTP Cookie File") ||
                        (text.contains('\t') && text.lines().any { it.count { c -> c == '\t' } >= 6 })
                    if (!looksRight) return@withContext false
                    File(filesDir, "cookies.txt").writeText(text)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(
                this@SettingsActivity,
                if (ok) R.string.cookies_imported else R.string.cookies_invalid,
                Toast.LENGTH_LONG
            ).show()
            refreshCookiesStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = DownloadRepository.get(this)
        settings = repo.settings

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.concurrencySeek.max = Settings.MAX_CONCURRENCY - 1
        binding.concurrencySeek.progress = settings.concurrency - 1
        updateConcurrencyLabel(settings.concurrency)

        binding.concurrencySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 1
                updateConcurrencyLabel(value)
                if (fromUser) {
                    settings.concurrency = value
                    notifyService()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.fragmentSeek.max = Settings.MAX_FRAGMENT_CONCURRENCY - 1
        binding.fragmentSeek.progress = settings.fragmentConcurrency - 1
        updateFragmentLabel(settings.fragmentConcurrency)
        binding.fragmentSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 1
                updateFragmentLabel(value)
                if (fromUser) settings.fragmentConcurrency = value
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.wifiOnlySwitch.isChecked = settings.wifiOnly
        binding.wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            settings.wifiOnly = checked
            notifyService()
        }

        binding.notificationsSwitch.isChecked = settings.notificationsEnabled
        binding.notificationsSwitch.setOnCheckedChangeListener { _, checked ->
            settings.notificationsEnabled = checked
            notifyService()
            refreshNotificationStatus()
        }
        binding.notificationSettingsButton.setOnClickListener {
            NotificationSupport.openSystemSettings(this)
        }

        binding.autoRetrySwitch.isChecked = settings.autoRetry
        binding.autoRetrySwitch.setOnCheckedChangeListener { _, checked ->
            settings.autoRetry = checked
        }

        setupPresetPicker()

        binding.proxyInput.setText(settings.proxyUrl)
        binding.proxySave.setOnClickListener {
            val raw = binding.proxyInput.text?.toString().orEmpty()
            try {
                val normalized = ProxyUrlValidator.normalize(raw)
                settings.proxyUrl = normalized
                binding.proxyInput.setText(normalized)
                binding.proxyInput.setSelection(normalized.length)
                notifyService()
                Toast.makeText(
                    this,
                    if (normalized.isEmpty()) R.string.proxy_cleared else R.string.proxy_saved,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, R.string.proxy_invalid, Toast.LENGTH_LONG).show()
            }
        }

        binding.cookiesImport.setOnClickListener {
            pickCookies.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
        }
        binding.cookiesDelete.setOnClickListener {
            File(filesDir, "cookies.txt").delete()
            Toast.makeText(this, R.string.cookies_removed, Toast.LENGTH_SHORT).show()
            refreshCookiesStatus()
        }

        binding.versionText.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        binding.storageInfo.text = getString(R.string.storage_free, Formats.bytes(freeStorage()))

        refreshCookiesStatus()
        loadEngineInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationStatus()
    }

    private fun refreshNotificationStatus() {
        val systemEnabled = NotificationSupport.canPostResultNotifications(this)
        binding.notificationStatus.text = getString(
            when {
                !systemEnabled -> R.string.notifications_system_disabled
                settings.notificationsEnabled -> R.string.notifications_status_enabled
                else -> R.string.notifications_status_disabled
            }
        )
        binding.notificationSettingsButton.visibility = if (systemEnabled) View.GONE else View.VISIBLE
    }

    private fun setupPresetPicker() {
        val labels = listOf(
            getString(R.string.quality_best),
            getString(R.string.quality_1080),
            getString(R.string.quality_720),
            getString(R.string.quality_480),
            getString(R.string.quality_360),
            getString(R.string.quality_audio)
        )
        binding.presetSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        val index = presetKeys.indexOf(settings.defaultPreset).coerceAtLeast(0)
        binding.presetSpinner.setText(labels[index], false)
        binding.presetSpinner.setOnItemClickListener { _, _, position, _ ->
            settings.defaultPreset = presetKeys[position]
            Toast.makeText(this, R.string.preset_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshCookiesStatus() {
        val file = File(filesDir, "cookies.txt")
        val has = file.exists() && file.length() > 0
        binding.cookiesStatus.text = getString(
            if (has) R.string.cookies_present else R.string.cookies_absent
        )
        binding.cookiesDelete.isEnabled = has
    }

    private fun loadEngineInfo() {
        lifecycleScope.launch {
            binding.engineInfo.text = "…"
            val info = ExtractorEngine.get(this@SettingsActivity).info()
            binding.engineInfo.text = buildString {
                append(getString(R.string.engine_ytdlp)).append(" ").append(info.ytDlp)
                append(" • ").append(getString(R.string.engine_python)).append(" ").append(info.python)
            }
        }
    }

    private fun updateConcurrencyLabel(value: Int) {
        binding.concurrencyLabel.text = getString(R.string.concurrency_value, value)
    }

    private fun updateFragmentLabel(value: Int) {
        binding.fragmentLabel.text = getString(R.string.fragment_concurrency_value, value)
    }

    private fun notifyService() {
        DownloadService.send(this, DownloadService.ACTION_SETTINGS_CHANGED)
    }

    private fun freeStorage(): Long = try {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        -1L
    }

    @Suppress("unused")
    private fun show(view: View) {
        view.visibility = View.VISIBLE
    }
}
