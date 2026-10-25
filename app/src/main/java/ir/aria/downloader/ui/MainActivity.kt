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
import ir.aria.downloader.R
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.data.FileSaver
import ir.aria.downloader.databinding.ActivityMainBinding
import ir.aria.downloader.databinding.DialogDetailsBinding
import ir.aria.downloader.databinding.DialogRenameBinding
import ir.aria.downloader.diag.DiagLog
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

        setupHeader()
        setupList()
        setupFilters()
        setupSearch()
        setupSelectionBar()

        binding.emptyAddButton.setOnClickListener { openAddScreen() }

        setupHome()
        binding.shortcutGrid.post { binding.shortcutGrid.startLayoutAnimation() }
        observeQueue()
        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        ensureServiceRunning()
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

    private fun setupHeader() {
        binding.headerMenu.setOnClickListener { view ->
            // little spin so the button itself feels alive
            view.animate().rotationBy(90f).setDuration(220).start()
            showHeaderMenu()
        }
        binding.headerPremium.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }
    }

    /** Animated clay menu (replaces the plain system popup). */
    private fun showHeaderMenu() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_main_menu)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.attributes = window.attributes.apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                y = (64 * resources.displayMetrics.density).toInt()
                x = (12 * resources.displayMetrics.density).toInt()
            }
            window.setWindowAnimations(R.style.DialogMenuAnim)
        }
        fun close(action: () -> Unit) { dialog.dismiss(); action() }
        dialog.findViewById<View>(R.id.menuSubscription).setOnClickListener {
            close { startActivity(Intent(this, SubscriptionActivity::class.java)) }
        }
        dialog.findViewById<View>(R.id.menuSettings).setOnClickListener {
            close { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        dialog.findViewById<View>(R.id.menuPauseAll).setOnClickListener {
            close {
                DownloadService.send(this, DownloadService.ACTION_PAUSE_ALL)
                Toast.makeText(this, R.string.pause_all, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.findViewById<View>(R.id.menuResumeAll).setOnClickListener {
            close {
                DownloadService.send(this, DownloadService.ACTION_RESUME_ALL)
                Toast.makeText(this, R.string.resume_all, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.findViewById<View>(R.id.menuClearCompleted).setOnClickListener {
            close { confirmClearCompleted() }
        }
        dialog.findViewById<View>(R.id.menuAbout).setOnClickListener { close { showAbout() } }
        dialog.show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(R.string.about_text)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun setupHome() {
        binding.quickDownloadButton.setOnClickListener {
            val text = binding.quickUrlInput.text?.toString()?.trim().orEmpty()
            DiagLog.i("Main", "quick download clicked, input " + text.length + " chars: '" + text.take(200) + "'")
            if (text.isBlank()) {
                // Try clipboard first, like the mockup's paste-first flow.
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = manager.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
                if (clip.contains("http")) {
                    openAddScreen(clip)
                } else {
                    openAddScreen()
                }
            } else {
                openAddScreen(text)
            }
        }
        binding.groupAddButton.setOnClickListener { openAddScreen() }
        val shortcuts = listOf(
            binding.shortcutDirect to "لینک مستقیم را وارد کنید",
            binding.shortcutYoutube to "لینک یوتیوب را وارد کنید",
            binding.shortcutSpotify to "لینک اسپاتیفای را وارد کنید",
            binding.shortcutInstagram to "لینک اینستاگرام را وارد کنید",
            binding.shortcutTiktok to "لینک TikTok را وارد کنید",
            binding.shortcutX to "لینک X را وارد کنید",
            binding.shortcutFacebook to "لینک فیس‌بوک را وارد کنید",
            binding.shortcutSoundcloud to "لینک SoundCloud را وارد کنید",
            binding.shortcutPornhub to "لینک Pornhub را وارد کنید",
            binding.shortcutAparat to "لینک آپارات را وارد کنید",
            binding.shortcutPinterest to "لینک Pinterest را وارد کنید",
            binding.shortcutTwitch to "لینک Twitch را وارد کنید",
            binding.shortcutVimeo to "لینک Vimeo را وارد کنید",
            binding.shortcutReddit to "لینک Reddit را وارد کنید"
        )
        for ((view, hint) in shortcuts) {
            view.setOnClickListener {
                DiagLog.d("Main", "shortcut tapped: $hint")
                view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    openAddScreen(platformHint = hint)
                }.start()
            }
        }
        binding.navHome.setOnClickListener {
            binding.homeScroll.smoothScrollTo(0, 0)
        }
        binding.seeAllButton.setOnClickListener { openDownloadsList(DownloadsListActivity.MODE_ALL) }
        binding.navQueue.setOnClickListener { openDownloadsList(DownloadsListActivity.MODE_QUEUE) }
        binding.navDownloads.setOnClickListener { openDownloadsList(DownloadsListActivity.MODE_COMPLETED) }
        binding.navAdd.setOnClickListener { openAddScreen() }
        binding.navProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setFilter(newFilter: Filter) {
        DiagLog.d("Main", "filter -> $newFilter")
        filter = newFilter
        val chipId = when (newFilter) {
            Filter.ALL -> R.id.chipAll
            Filter.ACTIVE -> R.id.chipActive
            Filter.COMPLETED -> R.id.chipCompleted
            Filter.PAUSED -> R.id.chipPaused
            Filter.FAILED -> R.id.chipFailed
        }
        binding.filterChips.check(chipId)
        clearSelection()
        refreshList()
        // The whole home page scrolls (hero included); bring the queue into view.
        binding.homeScroll.post {
            val y = binding.queueTitle.top - (8 * resources.displayMetrics.density).toInt()
            binding.homeScroll.smoothScrollTo(0, y.coerceAtLeast(0))
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
            .sortedByDescending { it.createdAt }
            .take(5)
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
        DiagLog.i("Main", "toggle #" + item.id + " status=" + item.status + " '" + item.fileName + "'")
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                DownloadService.send(this, DownloadService.ACTION_PAUSE, item.id)
            DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                DownloadService.send(this, DownloadService.ACTION_RESUME, item.id)
            else -> Unit
        }
    }

    private fun openItem(item: DownloadItem) {
        DiagLog.i("Main", "open item #" + item.id + " '" + item.fileName + "' status=" + item.status)
        val intent = FileSaver.openIntent(this, item)
        if (intent == null) {
            DiagLog.w("Main", "openItem: no intent (file missing?) #" + item.id + " uri=" + item.fileUri)
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
        DiagLog.i(
            "Main",
            "details #" + item.id + " status=" + item.status + " engine=" + item.engine +
                " size=" + item.downloadedBytes + "/" + item.totalBytes +
                " error='" + item.errorMessage + "' url=" + DiagLog.redactUrl(item.url)
        )
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
        DiagLog.i("Main", "delete requested ids=$ids")
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

    private fun openDownloadsList(mode: String) {
        DiagLog.d("Main", "open downloads list mode=$mode")
        startActivity(
            Intent(this, DownloadsListActivity::class.java)
                .putExtra(DownloadsListActivity.EXTRA_MODE, mode)
        )
    }

    private fun openAddScreen(prefill: String? = null, platformHint: String? = null) {
        DiagLog.d("Main", "open add screen hint='$platformHint' prefillLen=" + (prefill?.length ?: 0))
        val intent = Intent(this, AddActivity::class.java).apply {
            if (!prefill.isNullOrBlank()) putExtra(AddActivity.EXTRA_PREFILL, prefill)
            if (!platformHint.isNullOrBlank()) putExtra(AddActivity.EXTRA_PLATFORM_HINT, platformHint)
        }
        addLauncher.launch(intent)
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
