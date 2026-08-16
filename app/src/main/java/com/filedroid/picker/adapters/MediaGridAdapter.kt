package com.filedroid.picker.adapters

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.filedroid.R
import com.filedroid.databinding.ItemMediaGridBinding
import com.filedroid.picker.model.MediaItem
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MediaGridAdapter(
    private val showDuration: Boolean = false,
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: ((MediaItem) -> Unit)? = null,
    private val onGroupSelectToggle: ((String, Boolean) -> Unit)? = null,
    private val isSelected: (String) -> Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val PAYLOAD_SELECTION = "selection"
    }

    sealed class ListItem {
        data class Header(val title: String, val paths: List<String>) : ListItem()
        data class Media(val item: MediaItem) : ListItem()
    }

    private val items = mutableListOf<ListItem>()
    private val thumbExecutor = Executors.newFixedThreadPool(2) // 2 threads to reduce CPU pressure

    // Scale cache to device memory — use 1/8th of max heap
    private val thumbCache: android.util.LruCache<String, Bitmap> = run {
        val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = (maxMem / 8) * 1024  // 1/8th of max heap in bytes
        object : android.util.LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
        }
    }

    fun submitItems(mediaItems: List<MediaItem>) {
        items.clear()
        // Don't evict thumbCache — reuse cached bitmaps for smooth scrolling
        val grouped = groupByDate(mediaItems)
        for ((header, group) in grouped) {
            val paths = group.map { it.path }
            items.add(ListItem.Header(header, paths))
            group.forEach { items.add(ListItem.Media(it)) }
        }
        notifyDataSetChanged()
    }

    /** Release thread pool and bitmap cache. Call in onDestroyView. */
    fun shutdown() {
        thumbExecutor.shutdownNow()
        thumbCache.evictAll()
    }

    /** Call this instead of notifyDataSetChanged when only selection changed */
    fun notifySelectionChanged() {
        for (i in items.indices) {
            notifyItemChanged(i, PAYLOAD_SELECTION)
        }
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
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_date_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val binding = ItemMediaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            MediaViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ListItem.Media -> (holder as MediaViewHolder).bind(item.item, false)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            // Only update selection state, NOT thumbnail
            when (val item = items[position]) {
                is ListItem.Header -> (holder as HeaderViewHolder).updateSelection(item)
                is ListItem.Media -> (holder as MediaViewHolder).updateSelection(item.item)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount() = items.size

    // ---- Header ViewHolder with group select-all ----

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvHeader: TextView = view.findViewById(R.id.tvHeader)
        private val tvCount: TextView = view.findViewById(R.id.tvCount)
        private val cbSelectGroup: CheckBox = view.findViewById(R.id.cbSelectGroup)

        fun bind(header: ListItem.Header) {
            tvHeader.text = header.title
            tvCount.text = "${header.paths.size} items"
            updateSelection(header)

            cbSelectGroup.setOnClickListener {
                val allSelected = header.paths.all { isSelected(it) }
                onGroupSelectToggle?.invoke(header.title, !allSelected)
            }
        }

        fun updateSelection(header: ListItem.Header) {
            val allSelected = header.paths.isNotEmpty() && header.paths.all { isSelected(it) }
            val someSelected = header.paths.any { isSelected(it) }
            cbSelectGroup.isChecked = allSelected
            // Indeterminate-like: if some but not all selected, still show checked for visual feedback
            if (someSelected && !allSelected) {
                cbSelectGroup.isChecked = false
            }
        }
    }

    // ---- Media ViewHolder with cached thumbnails ----

    inner class MediaViewHolder(private val binding: ItemMediaGridBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem, selectionOnly: Boolean) {
            updateSelection(item)

            if (showDuration && item.duration > 0) {
                binding.tvDuration.visibility = View.VISIBLE
                binding.tvDuration.text = formatDuration(item.duration)
            } else {
                binding.tvDuration.visibility = View.GONE
            }

            // Load thumbnail — check cache first
            binding.ivThumbnail.tag = item.path
            val cachedBitmap = thumbCache.get(item.path)
            if (cachedBitmap != null) {
                binding.ivThumbnail.setImageBitmap(cachedBitmap)
                binding.ivThumbnail.setBackgroundColor(0)
            } else {
                // Show placeholder while loading (not blank) — use divider color for theme awareness
                binding.ivThumbnail.setImageDrawable(null)
                binding.ivThumbnail.setBackgroundColor(
                    binding.root.context.getColor(com.filedroid.R.color.divider)
                )
                if (!thumbExecutor.isShutdown) {
                    thumbExecutor.execute {
                        try {
                            val bitmap = loadThumbnail(item)
                            if (bitmap != null) {
                                thumbCache.put(item.path, bitmap)
                                binding.ivThumbnail.post {
                                    if (binding.ivThumbnail.tag == item.path) {
                                        binding.ivThumbnail.setImageBitmap(bitmap)
                                        binding.ivThumbnail.setBackgroundColor(0)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }

            // Tap thumbnail = preview, tap anywhere else / checkbox = toggle selection
            // No long-press — it interferes with scrolling on touch devices
            binding.ivThumbnail.isClickable = true
            binding.ivThumbnail.setOnClickListener { onItemLongClick?.invoke(item) }
            binding.checkbox.setOnClickListener { onItemClick(item) }
            // Tap on info area or overlay = select
            binding.root.setOnClickListener { onItemClick(item) }
        }

        fun updateSelection(item: MediaItem) {
            val selected = isSelected(item.path)
            binding.checkbox.isChecked = selected
            binding.viewOverlay.visibility = if (selected) View.VISIBLE else View.GONE
        }

        private fun loadThumbnail(item: MediaItem): Bitmap? {
            return if (item.mimeType.startsWith("video/")) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(item.path)
                    retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            } else {
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
                BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            }
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "$min:${sec.toString().padStart(2, '0')}"
        }
    }

    /** Get all media paths grouped by header title */
    fun getGroupPaths(groupTitle: String): List<String> {
        return items.filterIsInstance<ListItem.Header>()
            .firstOrNull { it.title == groupTitle }?.paths ?: emptyList()
    }

    fun getAllMediaPaths(): List<String> {
        return items.filterIsInstance<ListItem.Media>().map { it.item.path }
    }
}
