package ir.aria.downloader.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
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
import ir.aria.downloader.databinding.ItemRecentLinkBinding
import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.model.DownloadStatus
import ir.aria.downloader.net.MimeTypes
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
    private var lastServiceEnsure = 0L
    private var lastRecentKey = ""

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
        setupSelectionBar()

        binding.emptyAddButton.setOnClickListener { openAddScreen() }

        setupHome()
        updateNavSelection()
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
        binding.headerMenu.setOnClickListener { view -> showHeaderMenu(view) }
        binding.headerPremium.setOnClickListener { showAbout() }
    }

    private fun showHeaderMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.settings)
        popup.menu.add(0, 2, 1, R.string.pause_all)
        popup.menu.add(0, 3, 2, R.string.resume_all)
        popup.menu.add(0, 4, 3, R.string.clear_completed)
        popup.menu.add(0, 5, 4, R.string.about)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                2 -> {
                    DownloadService.send(this, DownloadService.ACTION_PAUSE_ALL)
                    Toast.makeText(this, R.string.pause_all, Toast.LENGTH_SHORT).show(); true
                }
                3 -> {
                    DownloadService.send(this, DownloadService.ACTION_RESUME_ALL)
                    Toast.makeText(this, R.string.resume_all, Toast.LENGTH_SHORT).show(); true
                }
                4 -> { confirmClearCompleted(); true }
                5 -> { showAbout(); true }
                else -> false
            }
        }
        popup.show()
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
        binding.shortcutDirect.setOnClickListener { openAddScreen(platformHint = "لینک مستقیم را وارد کنید") }
        binding.shortcutYoutube.setOnClickListener { openAddScreen(platformHint = "لینک یوتیوب را وارد کنید") }
        binding.shortcutSpotify.setOnClickListener { openAddScreen(platformHint = "لینک اسپاتیفای را وارد کنید") }
        binding.shortcutInstagram.setOnClickListener { openAddScreen(platformHint = "لینک اینستاگرام را وارد کنید") }
        binding.shortcutPornhub.setOnClickListener { openAddScreen(platformHint = "لینک Pornhub را وارد کنید") }
        binding.navHome.setOnClickListener {
            setFilter(Filter.ALL)
            binding.homeScroll.smoothScrollTo(0, 0)
        }
        binding.navQueue.setOnClickListener { setFilter(Filter.ACTIVE, scrollToQueue = true) }
        binding.navDownloads.setOnClickListener { setFilter(Filter.COMPLETED, scrollToQueue = true) }
        binding.navAdd.setOnClickListener { openAddScreen() }
        binding.navProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.queueSeeAll.setOnClickListener { setFilter(Filter.ALL, scrollToQueue = true) }
        binding.recentSeeAll.setOnClickListener { setFilter(Filter.COMPLETED, scrollToQueue = true) }
    }

    private fun setFilter(newFilter: Filter, scrollToQueue: Boolean = false) {
        filter = newFilter
        clearSelection()
        refreshList()
        updateNavSelection()
        binding.queueTitle.setText(
            when (newFilter) {
                Filter.COMPLETED -> R.string.nav_downloads
                else -> R.string.queue_title
            }
        )
        if (scrollToQueue) {
            binding.homeScroll.post {
                binding.homeScroll.smoothScrollTo(0, binding.queueSection.top)
            }
        }
    }

    private fun updateNavSelection() {
        val active = ContextCompat.getColor(this, R.color.aria_primary)
        val dim = ContextCompat.getColor(this, R.color.aria_text_dim)

        fun style(icon: ImageView, label: TextView, isActive: Boolean) {
            val color = if (isActive) active else dim
            icon.imageTintList = ColorStateList.valueOf(color)
            label.setTextColor(color)
        }

        style(binding.navHomeIcon, binding.navHomeLabel, filter == Filter.ALL)
        style(binding.navQueueIcon, binding.navQueueLabel, filter == Filter.ACTIVE)
        style(binding.navDownloadsIcon, binding.navDownloadsLabel, filter == Filter.COMPLETED)
        style(binding.navProfileIcon, binding.navProfileLabel, false)
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
                    updateRecentLinks(items)
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

    /** Fills the horizontal "لینک‌های اخیر" row with the most recent items. */
    private fun updateRecentLinks(items: List<DownloadItem>) {
        val recent = items.sortedByDescending { it.createdAt }.take(6)
        binding.recentSection.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        // The repository flow emits on every progress tick; only rebuild the row
        // when the visible content actually changed.
        val key = recent.joinToString("|") { item ->
            val bytes = if (item.totalBytes > 0) item.totalBytes else item.downloadedBytes
            "${item.id}:${item.fileName}:${Formats.bytes(bytes)}"
        }
        if (key == lastRecentKey) return
        lastRecentKey = key
        binding.recentLinksRow.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in recent) {
            val card = ItemRecentLinkBinding.inflate(inflater, binding.recentLinksRow, false)
            card.recentTitle.text = item.title?.takeIf { it.isNotBlank() } ?: item.fileName
            card.recentDomain.text = hostOf(item.url)
            val bytes = if (item.totalBytes > 0) item.totalBytes else item.downloadedBytes
            card.recentSize.text = if (bytes > 0) Formats.bytes(bytes) else getString(R.string.unknown_size)
            card.recentIcon.text = iconFor(item)
            val badge = PlatformBadge.of(item.url)
            card.recentBadge.text = badge.symbol
            card.recentBadge.setBackgroundResource(badge.background)
            card.recentBadge.setTextColor(ContextCompat.getColor(this, badge.textColor))
            card.root.setOnClickListener {
                val current = allItems.firstOrNull { it.id == item.id } ?: item
                if (current.status == DownloadStatus.COMPLETED) openItem(current) else showDetails(current)
            }
            binding.recentLinksRow.addView(card.root)
        }
    }

    private fun hostOf(url: String): String = try {
        Uri.parse(url).host?.removePrefix("www.").orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun iconFor(item: DownloadItem): String =
        when (MimeTypes.categoryOf(item.mimeType, item.fileName)) {
            MimeTypes.Category.VIDEO -> "🎬"
            MimeTypes.Category.AUDIO -> "🎵"
            MimeTypes.Category.IMAGE -> "🖼️"
            MimeTypes.Category.PDF -> "📕"
            MimeTypes.Category.ARCHIVE -> "📦"
            MimeTypes.Category.APP -> "📱"
            MimeTypes.Category.TEXT -> "📝"
            MimeTypes.Category.OTHER -> "📄"
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

    private fun openAddScreen(prefill: String? = null, platformHint: String? = null) {
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
