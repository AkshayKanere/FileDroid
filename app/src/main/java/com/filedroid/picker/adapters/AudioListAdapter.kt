package com.filedroid.picker.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.filedroid.databinding.ItemAudioRowBinding
import com.filedroid.picker.model.MediaItem
import com.filedroid.util.FileUtils

class AudioListAdapter(
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: ((MediaItem) -> Unit)? = null,
    private val isSelected: (String) -> Boolean
) : RecyclerView.Adapter<AudioListAdapter.ViewHolder>() {

    private val items = mutableListOf<MediaItem>()

    fun submitList(list: List<MediaItem>) {
        items.clear()
        items.addAll(list.sortedByDescending { it.dateModified })
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemAudioRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAudioRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvName.text = item.name
        holder.binding.checkbox.isChecked = isSelected(item.path)

        val parts = mutableListOf<String>()
        if (item.artist.isNotEmpty() && item.artist != "<unknown>") parts.add(item.artist)
        if (item.duration > 0) parts.add(formatDuration(item.duration))
        parts.add(FileUtils.formatFileSize(item.size))
        holder.binding.tvMeta.text = parts.joinToString(" • ")

        holder.binding.root.setOnClickListener { onItemClick(item) }
        holder.binding.root.setOnLongClickListener { onItemLongClick?.invoke(item); true }
        holder.binding.checkbox.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    fun getAllPaths(): List<String> = items.map { it.path }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }
}
