package com.filedroid.picker.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.filedroid.databinding.ItemDocumentRowBinding
import com.filedroid.picker.model.MediaItem
import com.filedroid.util.FileUtils
import java.text.SimpleDateFormat
import java.util.*

class DocumentListAdapter(
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: ((MediaItem) -> Unit)? = null,
    private val isSelected: (String) -> Boolean
) : RecyclerView.Adapter<DocumentListAdapter.ViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    fun submitList(list: List<MediaItem>) {
        items.clear()
        items.addAll(list.sortedByDescending { it.dateModified })
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemDocumentRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDocumentRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvName.text = item.name
        holder.binding.checkbox.isChecked = isSelected(item.path)
        holder.binding.tvIcon.text = getDocIcon(item.name)

        val size = FileUtils.formatFileSize(item.size)
        val date = dateFormat.format(Date(item.dateModified * 1000))
        holder.binding.tvMeta.text = "$size • $date"

        holder.binding.root.setOnClickListener { onItemClick(item) }
        holder.binding.root.setOnLongClickListener { onItemLongClick?.invoke(item); true }
        holder.binding.checkbox.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    fun getAllPaths(): List<String> = items.map { it.path }

    private fun getDocIcon(name: String): String {
        val ext = name.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "pdf" -> "📕"
            "doc", "docx" -> "📝"
            "xls", "xlsx" -> "📊"
            "ppt", "pptx" -> "📊"
            "txt" -> "📄"
            "csv" -> "📋"
            "json", "xml" -> "📋"
            "html", "htm" -> "🌐"
            "md" -> "📝"
            "zip", "rar", "7z", "tar", "gz" -> "📦"
            else -> "📄"
        }
    }
}
