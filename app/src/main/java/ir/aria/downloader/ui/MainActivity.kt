package ir.aria.downloader.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.View
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.aria.downloader.BuildConfig
import ir.aria.downloader.R
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.data.FileSaver
import ir.aria.downloader.engine.ExtractorEngine
import ir.aria.downloader.util.Logs
import ir.aria.downloader.databinding.ActivityMainBinding
import ir.aria.downloader.databinding.DialogDetailsBinding
import ir.aria.downloader.databinding.DialogRenameBinding
import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.model.DownloadStatus
import ir.aria.downloader.service.DownloadService
import ir.aria.downloader.service.NotificationSupport
import ir.aria.downloader.util.Formats
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class Filter { ALL, ACTIVE, COMPLETED, PAUSED, FAILED }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: DownloadRepository
    private lateinit var adapter: DownloadAdapter

    private var allItems: List<DownloadItem> = emptyList()
    private val selected = linkedSetOf<Long>()
    private var filter = Filter.ALL
    private var query = ""
    private var lastServiceEnsure = 0L

    private val addLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* the list refreshes itself through the repository flow */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) showNotificationPermissionHelp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = DownloadRepository.get(this)

        setupToolbar()
        setupList()
        setupFilters()
        setupSearch()
        setupSelectionBar()

        binding.fab.setOnClickListener { openAddScreen() }
        binding.emptyAddButton.setOnClickListener { openAddScreen() }

        observeQueue()
        requestPermissionsIfNeeded()
        Logs.d(this, "ui", "main screen opened")
        checkPreviousCrash()
        checkEngineOnceAfterInstall()
    }

    override fun onStart() {
        super.onStart()
        ensureServiceRunning()
    }

    override fun onDestroy() {
        if (isFinishing) Logs.endSession(this)
        super.onDestroy()
    }

    private fun ensureServiceRunning() {
        val hasQueued = repo.items.value.any { it.status == DownloadStatus.QUEUED }
        if (!hasQueued) return
        // The flow emits on every progress tick; never spam start commands.
        val now = System.currentTimeMillis()
        if (now - lastServiceEnsure < 3_000) return
        lastServiceEnsure = now
        DownloadService.send(this, DownloadService.ACTION_START)
    }

    // ------------------------------------------------------------------ setup

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                R.id.action_pause_all -> {
                    DownloadService.send(this, DownloadService.ACTION_PAUSE_ALL)
                    Toast.makeText(this, R.string.pause_all, Toast.LENGTH_SHORT).show(); true
                }
                R.id.action_resume_all -> {
                    DownloadService.send(this, DownloadService.ACTION_RESUME_ALL)
                    Toast.makeText(this, R.string.resume_all, Toast.LENGTH_SHORT).show(); true
                }
                R.id.action_clear_completed -> {
                    confirmClearCompleted(); true
                }
                R.id.action_log -> {
                    showLogDialog(); true
                }
                R.id.action_about -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.about)
                        .setMessage(R.string.about_text)
                        .setPositiveButton(R.string.done, null)
                        .show()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupList() {
        adapter = DownloadAdapter(
            onClick = { item ->
                if (selectionMode()) toggleSelection(item.id)
                else if (item.status == DownloadStatus.COMPLETED) openItem(item)
                else showDetails(item)
            },
            onAction = { item -> toggleItem(item) },
            onLongClick = { item -> enterSelection(item.id) },
            isSelected = { id -> selected.contains(id) },
            selectionMode = { selectionMode() }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
    }

    private fun setupFilters() {
        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chipActive -> Filter.ACTIVE
                R.id.chipCompleted -> Filter.COMPLETED
                R.id.chipPaused -> Filter.PAUSED
                R.id.chipFailed -> Filter.FAILED
                else -> Filter.ALL
            }
            clearSelection()
            refreshList()
        }
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim().orEmpty()
                refreshList()
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun setupSelectionBar() {
        binding.selectionClose.setOnClickListener { clearSelection() }
        binding.selectionPause.setOnClickListener {
            for (id in selected) DownloadService.send(this, DownloadService.ACTION_PAUSE, id)
            clearSelection()
        }
        binding.selectionResume.setOnClickListener {
            for (id in selected) DownloadService.send(this, DownloadService.ACTION_RESUME, id)
            clearSelection()
        }
        binding.selectionDelete.setOnClickListener { confirmDelete(selected.toList()) }
    }

    // ------------------------------------------------------------------ data

    private fun observeQueue() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.items.collect { items ->
                    allItems = items
                    selected.retainAll(items.map { it.id }.toSet())
                    if (selected.isEmpty() && selectionMode()) exitSelection()
                    refreshList()
                    updateHeader(items)
                    updateSelectionUi()
                    if (items.any { it.status == DownloadStatus.QUEUED }) ensureServiceRunning()
                }
            }
        }
    }

    private fun refreshList() {
        val filtered = allItems
            .asSequence()
            .filter { item ->
                when (filter) {
                    Filter.ALL -> true
                    Filter.ACTIVE -> item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.QUEUED
                    Filter.COMPLETED -> item.status == DownloadStatus.COMPLETED
                    Filter.PAUSED -> item.status == DownloadStatus.PAUSED
                    Filter.FAILED -> item.status == DownloadStatus.FAILED
                }
            }
            .filter { item ->
                query.isEmpty() ||
                    item.fileName.contains(query, true) ||
                    item.url.contains(query, true) ||
                    (item.title?.contains(query, true) == true)
            }
            .sortedWith(compareBy({ statusRank(it.status) }, { -it.createdAt }))
            .toList()
        adapter.submitList(filtered)
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun statusRank(status: DownloadStatus): Int = when (status) {
        DownloadStatus.RUNNING -> 0
        DownloadStatus.QUEUED -> 1
        DownloadStatus.PAUSED -> 2
        DownloadStatus.FAILED -> 3
        DownloadStatus.CANCELED -> 4
        DownloadStatus.COMPLETED -> 5
    }

    private fun updateHeader(items: List<DownloadItem>) {
        val summary = repo.summary()
        val showCard = summary.activeCount > 0 || summary.queuedCount > 0
        binding.aggregateCard.visibility = if (showCard) View.VISIBLE else View.GONE
        if (showCard) {
            binding.aggregateTitle.text = if (summary.activeCount > 0) {
                getString(R.string.downloads_running, summary.activeCount)
            } else {
                getString(R.string.queued)
            }
            binding.aggregateSpeed.text = Formats.speed(summary.aggregateSpeed)
            val progress = summary.aggregateProgress
            if (progress != null) {
                binding.aggregateProgress.isIndeterminate = false
                binding.aggregateProgress.progress = progress
            } else {
                binding.aggregateProgress.isIndeterminate = true
            }
        }

        val completedBytes = items.filter { it.status == DownloadStatus.COMPLETED }
            .sumOf { it.downloadedBytes }
        binding.statsText.text = getString(R.string.stats, items.size, Formats.bytes(completedBytes))
        binding.storageText.text = getString(R.string.storage_free, Formats.bytes(freeStorage()))
    }

    private fun freeStorage(): Long = try {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        -1L
    }

    // ------------------------------------------------------------------ selection

    private fun selectionMode(): Boolean = selected.isNotEmpty()

    private fun enterSelection(id: Long) {
        selected.add(id)
        binding.selectionBar.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun toggleSelection(id: Long) {
        if (!selected.remove(id)) selected.add(id)
        if (selected.isEmpty()) exitSelection() else updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        exitSelection()
    }

    private fun exitSelection() {
        selected.clear()
        binding.selectionBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        binding.selectionBar.visibility = if (selectionMode()) View.VISIBLE else View.GONE
        binding.selectionCount.text = getString(R.string.selected_n, selected.size)
    }

    // ------------------------------------------------------------------ actions

    private fun toggleItem(item: DownloadItem) {
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                DownloadService.send(this, DownloadService.ACTION_PAUSE, item.id)
            DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                DownloadService.send(this, DownloadService.ACTION_RESUME, item.id)
            else -> Unit
        }
    }

    private fun openItem(item: DownloadItem) {
        val intent = FileSaver.openIntent(this, item)
        if (intent == null) {
            Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetails(item: DownloadItem) {
        val dialogBinding = DialogDetailsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialogBinding.detailsTitle.text = item.fileName
        dialogBinding.detailsStatus.text = statusText(item)
        dialogBinding.detailsSize.text = buildString {
            append(Formats.bytes(item.downloadedBytes))
            if (item.totalBytes > 0) append(" / " + Formats.bytes(item.totalBytes))
            val speed = Formats.speed(item.speedBps)
            if (speed.isNotEmpty()) append(" • " + speed)
            val duration = Formats.duration(item.durationSec)
            if (duration.isNotEmpty()) append(" • " + duration)
        }
        dialogBinding.detailsUrl.text = item.url
        dialogBinding.detailsError.visibility =
            if (item.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        dialogBinding.detailsError.text = item.errorMessage
        if (!item.extractor.isNullOrBlank()) {
            dialogBinding.detailsExtractor.visibility = View.VISIBLE
            dialogBinding.detailsExtractor.text = getString(R.string.extractor_label, item.extractor)
        }
        if (!item.warning.isNullOrBlank()) {
            dialogBinding.detailsWarning.visibility = View.VISIBLE
            dialogBinding.detailsWarning.text = getString(R.string.warning_label, item.warning)
        }
        if (item.status == DownloadStatus.RUNNING && item.totalBytes <= 0) {
            dialogBinding.detailsProgress.isIndeterminate = true
        } else {
            dialogBinding.detailsProgress.progress = item.progressPercent
        }

        dialogBinding.btnPrimary.text = when (item.status) {
            DownloadStatus.COMPLETED -> getString(R.string.open)
            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> getString(R.string.pause)
            else -> getString(R.string.resume)
        }
        dialogBinding.btnPrimary.setOnClickListener {
            if (item.status == DownloadStatus.COMPLETED) openItem(item) else toggleItem(item)
            dialog.dismiss()
        }
        dialogBinding.btnRename.setOnClickListener {
            dialog.dismiss()
            showRename(item)
        }
        dialogBinding.btnCopy.setOnClickListener {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("url", item.url))
            Toast.makeText(this, R.string.copy_link, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialogBinding.btnDelete.setOnClickListener {
            dialog.dismiss()
            confirmDelete(listOf(item.id))
        }
        dialog.show()
    }

    private fun statusText(item: DownloadItem): String = when (item.status) {
        DownloadStatus.QUEUED -> getString(R.string.queued)
        DownloadStatus.RUNNING -> getString(R.string.active) + " • " + item.progressPercent + "%"
        DownloadStatus.PAUSED -> getString(R.string.paused)
        DownloadStatus.FAILED -> getString(R.string.failed)
        DownloadStatus.COMPLETED -> getString(R.string.completed)
        DownloadStatus.CANCELED -> getString(R.string.canceled)
    }

    private fun showRename(item: DownloadItem) {
        val dialogBinding = DialogRenameBinding.inflate(layoutInflater)
        val input: EditText = dialogBinding.renameInput
        input.setText(item.fileName)
        val dot = item.fileName.lastIndexOf('.')
        if (dot > 0) input.setSelection(0, dot)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.done) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    repo.renameItem(item.id, newName)
                    Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(ids: List<Long>) {
        val checkBox = CheckBox(this).apply {
            text = getString(R.string.delete_file_too)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.aria_text))
            setPadding(48, 24, 48, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setView(checkBox)
            .setPositiveButton(R.string.delete) { _, _ ->
                for (id in ids) {
                    DownloadService.send(this, DownloadService.ACTION_DELETE, id, checkBox.isChecked)
                }
                clearSelection()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearCompleted() {
        val checkBox = CheckBox(this).apply {
            text = getString(R.string.delete_file_too)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.aria_text))
            setPadding(48, 24, 48, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_completed)
            .setView(checkBox)
            .setPositiveButton(R.string.delete) { _, _ ->
                DownloadService.send(
                    this,
                    DownloadService.ACTION_CLEAR_COMPLETED,
                    deleteFile = checkBox.isChecked
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAddScreen() {
        addLauncher.launch(Intent(this, AddActivity::class.java))
    }

    // ------------------------------------------------------------------ permissions

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = getSharedPreferences("aria_settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notification_permission_requested", false)) {
                // Ask once per installation instead of showing the same system dialog on every
                // launch. The settings screen always provides a way back to system settings.
                prefs.edit().putBoolean("notification_permission_requested", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101
            )
        }
    }

    private fun showNotificationPermissionHelp() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notifications)
            .setMessage(R.string.permission_notif)
            .setPositiveButton(R.string.notifications_open_settings) { _, _ ->
                NotificationSupport.openSystemSettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Tells the user about the previous run when it did not end normally. Java crashes come with
     * a stack trace; a native crash (no stack trace at all) is detected through the session flag,
     * and the on-device log explains what the app was doing.
     */
    private fun checkPreviousCrash() {
        val crash = Logs.pendingCrash(this)
        val report = crash ?: run {
            if (!Logs.hadPreviousUncleanSession) return
            getString(R.string.crash_body) + "\n\n" + Logs.read(this).take(2500)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.crash_title) + " • " + BuildConfig.VERSION_NAME)
            .setMessage(report.take(3000))
            .setPositiveButton(R.string.crash_send) { _, _ ->
                copyToClipboard(if (crash != null) crash else Logs.read(this))
                Logs.acknowledgeCrash(this)
            }
            .setNegativeButton(R.string.crash_ignore) { _, _ -> Logs.acknowledgeCrash(this) }
            .create()
        dialog.show()
    }

    /**
     * Runs the extraction engine once after installation, in the background. It warms Python up
     * and makes sure the very first real download cannot be the first thing that touches it;
     * the result (good or bad) is written to the on-device log.
     */
    private fun checkEngineOnceAfterInstall() {
        val prefs = getSharedPreferences("aria_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("engine_checked", false)) return
        // set before the call: if the engine takes the process down, we must not retry at every
        // launch and turn the app into an endless crash loop
        prefs.edit().putBoolean("engine_checked", true).apply()
        lifecycleScope.launch {
            val result = runCatching { ExtractorEngine.get(this@MainActivity).selfTest() }
                .getOrElse { "selfTest exception: " + it.javaClass.simpleName }
            Logs.d(this@MainActivity, "engine", "first launch check: " + result.replace("\n", " | "))
        }
    }

    private fun showLogDialog() {
        val text = Logs.read(this)
        val scroll = ScrollView(this)
        val view = TextView(this).apply {
            this.text = if (text.isBlank()) getString(R.string.log_empty) else text
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(view)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.log_report) + " • " + BuildConfig.VERSION_NAME)
            .setView(scroll)
            .setPositiveButton(R.string.copy) { _, _ -> copyToClipboard(view.text.toString()) }
            .setNeutralButton(R.string.engine_test) { _, _ -> runEngineTest() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun runEngineTest() {
        Toast.makeText(this, R.string.engine_test_running, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = try {
                ExtractorEngine.get(this@MainActivity).selfTest()
            } catch (t: Throwable) {
                "موتور اجرا نشد: " + t.javaClass.simpleName
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.engine_test)
                .setMessage(result)
                .setPositiveButton(R.string.copy) { _, _ -> copyToClipboard(result) }
                .setNegativeButton(R.string.close, null)
                .show()
        }
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
}
