package com.filedroid.picker.adapters

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.filedroid.R
import com.filedroid.databinding.ItemDateHeaderBinding
import com.filedroid.databinding.ItemMediaGridBinding
import com.filedroid.picker.model.MediaItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MediaGridAdapter(
    private val showDuration: Boolean = false,
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: ((MediaItem) -> Unit)? = null,
    private val isSelected: (String) -> Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    sealed class ListItem {
        data class Header(val title: String) : ListItem()
        data class Media(val item: MediaItem) : ListItem()
    }

    private val items = mutableListOf<ListItem>()
    private val thumbExecutor = Executors.newFixedThreadPool(4)

    fun submitItems(mediaItems: List<MediaItem>) {
        items.clear()
        val grouped = groupByDate(mediaItems)
        for ((header, group) in grouped) {
            items.add(ListItem.Header(header))
            group.forEach { items.add(ListItem.Media(it)) }
        }
        notifyDataSetChanged()
    }

    private fun groupByDate(mediaItems: List<MediaItem>): LinkedHashMap<String, MutableList<MediaItem>> {
        val sorted = mediaItems.sortedByDescending { it.dateModified }
        val groups = LinkedHashMap<String, MutableList<MediaItem>>()

        val cal = Calendar.getInstance()
        val today = Calendar.getInstance()

        for (item in sorted) {
            cal.timeInMillis = item.dateModified * 1000
            val label = when {
                isSameDay(cal, today) -> "Today"
                isYesterday(cal, today) -> "Yesterday"
                isThisWeek(cal, today) -> "This Week"
                isThisMonth(cal, today) -> "This Month"
                else -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(item.dateModified * 1000))
            }
            groups.getOrPut(label) { mutableListOf() }.add(item)
        }
        return groups
    }

    private fun isSameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(a: Calendar, b: Calendar): Boolean {
        val yesterday = b.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(a, yesterday)
    }

    private fun isThisWeek(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.WEEK_OF_YEAR) == b.get(Calendar.WEEK_OF_YEAR)

    private fun isThisMonth(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.Header -> TYPE_HEADER
        is ListItem.Media -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val binding = ItemDateHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            HeaderViewHolder(binding)
        } else {
            val binding = ItemMediaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            MediaViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item.title)
            is ListItem.Media -> (holder as MediaViewHolder).bind(item.item)
        }
    }

    override fun getItemCount() = items.size

    inner class HeaderViewHolder(private val binding: ItemDateHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.tvHeader.text = title
        }
    }

    inner class MediaViewHolder(private val binding: ItemMediaGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            val selected = isSelected(item.path)
            binding.checkbox.isChecked = selected
            binding.viewOverlay.visibility = if (selected) View.VISIBLE else View.GONE

            if (showDuration && item.duration > 0) {
                binding.tvDuration.visibility = View.VISIBLE
                binding.tvDuration.text = formatDuration(item.duration)
            } else {
                binding.tvDuration.visibility = View.GONE
            }

            // Load thumbnail asynchronously
            binding.ivThumbnail.tag = item.path
            binding.ivThumbnail.setImageDrawable(null)
            thumbExecutor.execute {
                try {
                    val mime = item.mimeType
                    val bitmap = if (mime.startsWith("video/")) {
                        // Use MediaMetadataRetriever for video thumbnails
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(item.path)
                            retriever.getFrameAtTime(
                                1_000_000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            )
                        } finally {
                            retriever.release()
                        }
                    } else {
                        // Decode image with inSampleSize
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(item.path, opts)
                        val targetSize = 256
                        var sampleSize = 1
                        if (opts.outWidth > targetSize || opts.outHeight > targetSize) {
                            val halfW = opts.outWidth / 2
                            val halfH = opts.outHeight / 2
                            while (halfW / sampleSize >= targetSize && halfH / sampleSize >= targetSize) {
                                sampleSize *= 2
                            }
                        }
                        BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                        })
                    }
                    binding.ivThumbnail.post {
                        if (binding.ivThumbnail.tag == item.path && bitmap != null) {
                            binding.ivThumbnail.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore thumbnail loading failures
                }
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
            binding.root.setOnLongClickListener {
                onItemLongClick?.invoke(item)
                true
            }
            binding.checkbox.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "$min:${sec.toString().padStart(2, '0')}"
        }
    }

    fun getAllMediaPaths(): List<String> {
        return items.filterIsInstance<ListItem.Media>().map { it.item.path }
    }
}
