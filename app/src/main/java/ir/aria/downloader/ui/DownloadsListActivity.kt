package ir.aria.downloader.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ir.aria.downloader.R
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.data.FileSaver
import ir.aria.downloader.databinding.ActivityDownloadsListBinding
import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.model.DownloadStatus
import ir.aria.downloader.service.DownloadService
import kotlinx.coroutines.launch

/** Separate page for the download queue (MODE_QUEUE) or finished downloads (MODE_COMPLETED). */
class DownloadsListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "ir.aria.downloader.extra.LIST_MODE"
        const val MODE_QUEUE = "queue"
        const val MODE_COMPLETED = "completed"
        const val MODE_ALL = "all"
    }

    private lateinit var binding: ActivityDownloadsListBinding
    private lateinit var repo: DownloadRepository
    private lateinit var adapter: DownloadAdapter
    private var allItems: List<DownloadItem> = emptyList()
    private var query = ""
    private var mode = MODE_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ALL
        repo = DownloadRepository.get(this)
        binding.toolbar.title = getString(
            when (mode) {
                MODE_QUEUE -> R.string.nav_queue
                MODE_COMPLETED -> R.string.nav_downloads
                else -> R.string.downloads
            }
        )
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = DownloadAdapter(
            onClick = { item ->
                if (item.status == DownloadStatus.COMPLETED) openItem(item)
                else toggleItem(item)
            },
            onAction = { item -> toggleItem(item) },
            onLongClick = { item -> toggleItem(item) },
            isSelected = { false },
            selectionMode = { false }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString()?.trim().orEmpty(); refresh()
            }
            override fun afterTextChanged(e: android.text.Editable?) = Unit
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.items.collect { items -> allItems = items; refresh() }
            }
        }
    }

    private fun refresh() {
        val filtered = allItems.filter { item ->
            when (mode) {
                MODE_QUEUE -> item.status != DownloadStatus.COMPLETED
                MODE_COMPLETED -> item.status == DownloadStatus.COMPLETED
                else -> true
            }
        }.filter { item ->
            query.isEmpty() || item.fileName.contains(query, true) || item.url.contains(query, true) ||
                (item.title?.contains(query, true) == true)
        }.sortedByDescending { it.createdAt }
        adapter.submitList(filtered)
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

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
            Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show(); return
        }
        try { startActivity(intent) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show()
        }
    }
}
