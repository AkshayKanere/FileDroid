package com.filedroid.picker.tabs

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filedroid.R
import com.filedroid.databinding.FragmentMediaListBinding
import com.filedroid.picker.MediaPickerViewModel
import com.filedroid.util.FileUtils
import java.io.File

class AllFilesFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaPickerViewModel by activityViewModels()

    private var currentDir: File = Environment.getExternalStorageDirectory()
    private lateinit var adapter: FileListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FileListAdapter(
            onItemClick = { file ->
                if (file.isDirectory) {
                    currentDir = file
                    loadDirectory()
                } else {
                    val pos = adapter.indexOf(file)
                    viewModel.toggle(file.absolutePath)
                    if (pos >= 0) adapter.notifyItemChanged(pos) else adapter.notifyDataSetChanged()
                }
            },
            isSelected = { path -> viewModel.isSelected(path) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewModel.selectedPaths.observe(viewLifecycleOwner) {
            // Efficient partial update instead of full redraw
            for (i in 0 until adapter.itemCount) {
                adapter.notifyItemChanged(i, "selection")
            }
        }

        loadDirectory()
    }

    private fun loadDirectory() {
        val files = currentDir.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

        adapter.submitList(files)
        binding.tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    fun onBackPressed(): Boolean {
        val storageRoot = Environment.getExternalStorageDirectory()
        if (currentDir.absolutePath != storageRoot.absolutePath) {
            currentDir = currentDir.parentFile ?: storageRoot
            loadDirectory()
            return true
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---- Simple File List Adapter ----
    inner class FileListAdapter(
        private val onItemClick: (File) -> Unit,
        private val isSelected: (String) -> Boolean
    ) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

        private val files = mutableListOf<File>()

        fun submitList(list: List<File>) {
            files.clear()
            files.addAll(list)
            notifyDataSetChanged()
        }

        fun indexOf(file: File): Int = files.indexOfFirst { it.absolutePath == file.absolutePath }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val icon: TextView = view.findViewById(R.id.tvIcon)
            val name: TextView = view.findViewById(R.id.tvName)
            val meta: TextView = view.findViewById(R.id.tvMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_document_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.name.text = file.name

            if (file.isDirectory) {
                holder.icon.text = "📁"
                holder.meta.text = "${file.listFiles()?.size ?: 0} items"
                holder.checkbox.visibility = View.INVISIBLE
            } else {
                holder.icon.text = getFileIcon(file.name)
                holder.meta.text = FileUtils.formatFileSize(file.length())
                holder.checkbox.visibility = View.VISIBLE
                holder.checkbox.isChecked = isSelected(file.absolutePath)
            }

            holder.itemView.setOnClickListener { onItemClick(file) }
            holder.checkbox.setOnClickListener {
                if (!file.isDirectory) onItemClick(file)
            }
        }

        override fun getItemCount() = files.size

        private fun getFileIcon(name: String): String {
            val ext = name.substringAfterLast(".", "").lowercase()
            return when {
                ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> "🖼️"
                ext in setOf("mp4", "mkv", "avi", "mov", "3gp") -> "🎬"
                ext in setOf("mp3", "wav", "flac", "aac", "ogg", "m4a") -> "🎵"
                ext in setOf("pdf") -> "📕"
                ext in setOf("doc", "docx", "md", "txt") -> "📝"
                ext in setOf("xls", "xlsx", "csv") -> "📊"
                ext in setOf("zip", "rar", "7z", "tar", "gz") -> "📦"
                ext in setOf("apk") -> "📱"
                else -> "📄"
            }
        }
    }
}
