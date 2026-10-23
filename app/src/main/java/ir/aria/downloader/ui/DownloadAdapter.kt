package ir.aria.downloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ir.aria.downloader.R
import ir.aria.downloader.databinding.ItemDownloadBinding
import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.model.DownloadStatus
import ir.aria.downloader.net.MimeTypes
import ir.aria.downloader.util.Formats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadAdapter(
    private val onClick: (DownloadItem) -> Unit,
    private val onAction: (DownloadItem) -> Unit,
    private val onLongClick: (DownloadItem) -> Unit,
    private val isSelected: (Long) -> Boolean,
    private val selectionMode: () -> Boolean
) : ListAdapter<DownloadItem, DownloadAdapter.VH>(DIFF) {

    companion object {
        const val PAYLOAD_SPEED = "speed"

        private val DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem) =
                oldItem == newItem

            override fun getChangePayload(oldItem: DownloadItem, newItem: DownloadItem): Any? {
                val onlySpeed = oldItem.copy(speedBps = 0) == newItem.copy(speedBps = 0)
                return if (onlySpeed) PAYLOAD_SPEED else null
            }
        }

        private val DATE = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
    }

    inner class VH(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            val context = binding.root.context
            binding.title.text = item.fileName
            bindSubtitle(item)
            bindProgress(item)
            bindAction(item)

            binding.typeIcon.text = iconFor(item)
            binding.engineBadge.visibility = if (item.isMedia) View.VISIBLE else View.GONE
            binding.checkBox.visibility = if (selectionMode()) View.VISIBLE else View.GONE
            binding.checkBox.isChecked = isSelected(item.id)
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected(item.id)) R.color.aria_surface2 else R.color.aria_surface
                )
            )
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
            binding.actionButton.setOnClickListener { onAction(item) }
            binding.actionButton.isEnabled = item.status != DownloadStatus.COMPLETED
        }

        fun bindSpeedOnly(item: DownloadItem) {
            bindSubtitle(item)
        }

        private fun bindSubtitle(item: DownloadItem) {
            val context = binding.root.context
            binding.subtitle.text = when (item.status) {
                DownloadStatus.QUEUED ->
                    context.getString(R.string.queued) + qualitySuffix(item) + " • " +
                        Formats.bytes(item.totalBytes)
                DownloadStatus.RUNNING -> {
                    val base = if (item.totalBytes > 0) {
                        item.progressPercent.toString() + "% • " +
                            Formats.bytes(item.downloadedBytes) + " / " + Formats.bytes(item.totalBytes)
                    } else {
                        context.getString(R.string.connecting) + " • " + Formats.bytes(item.downloadedBytes)
                    }
                    val speed = Formats.speed(item.speedBps)
                    if (speed.isNotEmpty()) "$base • $speed" else base
                }
                DownloadStatus.PAUSED -> (if (item.pausedByUser) {
                    context.getString(R.string.paused)
                } else {
                    context.getString(R.string.waiting_wifi)
                }) + " • " + Formats.bytes(item.downloadedBytes) + " / " + Formats.bytes(item.totalBytes)
                DownloadStatus.FAILED -> context.getString(R.string.failed) + " • " +
                    (item.errorMessage ?: "")
                DownloadStatus.COMPLETED -> {
                    val date = item.completedAt?.let { DATE.format(Date(it)) } ?: ""
                    context.getString(R.string.completed) + qualitySuffix(item) + " • " +
                        Formats.bytes(item.downloadedBytes) + if (date.isNotEmpty()) " • $date" else ""
                }
                DownloadStatus.CANCELED -> context.getString(R.string.canceled)
            }
        }

        private fun qualitySuffix(item: DownloadItem): String = when {
            !item.isMedia -> ""
            item.preset == "audio" -> " • " + binding.root.context.getString(R.string.quality_audio)
            item.height > 0 -> " • ${item.height}p"
            else -> ""
        }

        private fun bindProgress(item: DownloadItem) {
            val progress = binding.progress
            when {
                item.status == DownloadStatus.COMPLETED -> {
                    progress.isIndeterminate = false
                    progress.progress = 100
                    progress.progressTintList = ContextCompat.getColorStateList(
                        binding.root.context, R.color.aria_green
                    )
                }
                item.status == DownloadStatus.RUNNING && item.totalBytes <= 0 -> {
                    progress.isIndeterminate = true
                }
                item.status == DownloadStatus.FAILED -> {
                    progress.isIndeterminate = false
                    progress.progress = item.progressPercent
                    progress.progressTintList = ContextCompat.getColorStateList(
                        binding.root.context, R.color.aria_red
                    )
                }
                else -> {
                    progress.isIndeterminate = false
                    progress.progress = item.progressPercent
                    progress.progressTintList = ContextCompat.getColorStateList(
                        binding.root.context, R.color.aria_blue
                    )
                }
            }
        }

        private fun bindAction(item: DownloadItem) {
            val icon = when (item.status) {
                DownloadStatus.RUNNING, DownloadStatus.QUEUED -> R.drawable.ic_pause
                DownloadStatus.FAILED -> R.drawable.ic_retry
                DownloadStatus.PAUSED -> R.drawable.ic_play
                else -> R.drawable.ic_play
            }
            binding.actionButton.setImageResource(icon)
            binding.actionButton.visibility =
                if (item.status == DownloadStatus.COMPLETED) View.INVISIBLE else View.VISIBLE
            binding.actionButton.contentDescription =
                if (item.status == DownloadStatus.RUNNING) {
                    binding.root.context.getString(R.string.pause)
                } else {
                    binding.root.context.getString(R.string.resume)
                }
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SPEED)) {
            holder.bindSpeedOnly(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }
}
