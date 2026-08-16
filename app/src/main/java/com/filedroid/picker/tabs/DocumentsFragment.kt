package com.filedroid.picker.tabs

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.filedroid.databinding.FragmentMediaListBinding
import com.filedroid.picker.MediaPickerViewModel
import com.filedroid.picker.PreviewActivity
import com.filedroid.picker.adapters.DocumentListAdapter
import com.filedroid.picker.model.MediaItem
import kotlinx.coroutines.*
import java.io.File

class DocumentsFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaPickerViewModel by activityViewModels()
    private lateinit var adapter: DocumentListAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private val DOC_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "json", "xml", "html", "htm", "md",
            "zip", "rar", "7z", "tar", "gz", "apk"
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DocumentListAdapter(
            onItemClick = { item -> viewModel.toggle(item.path) },
            onItemLongClick = { item ->
                startActivity(android.content.Intent(requireContext(), PreviewActivity::class.java).apply {
                    putExtra(PreviewActivity.EXTRA_PATH, item.path)
                    putExtra(PreviewActivity.EXTRA_NAME, item.name)
                    putExtra(PreviewActivity.EXTRA_MIME, item.mimeType)
                })
            },
            isSelected = { path -> viewModel.isSelected(path) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewModel.selectedPaths.observe(viewLifecycleOwner) {
            adapter.notifyDataSetChanged() // List adapter — no heavy thumbnails
        }

        loadDocuments()
    }

    private fun loadDocuments() {
        binding.progressBar.visibility = View.VISIBLE
        scope.launch {
            val docs = withContext(Dispatchers.IO) { scanDocuments() }
            if (_binding == null) return@launch
            binding.progressBar.visibility = View.GONE
            if (docs.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                adapter.submitList(docs)
            }
        }
    }

    private fun scanDocuments(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val root = Environment.getExternalStorageDirectory()

        fun scan(dir: File, depth: Int) {
            if (depth > 5 || items.size > 500) return
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.name.startsWith(".")) continue
                if (file.isDirectory) {
                    scan(file, depth + 1)
                } else {
                    val ext = file.extension.lowercase()
                    if (ext in DOC_EXTENSIONS) {
                        items.add(MediaItem(
                            path = file.absolutePath,
                            name = file.name,
                            size = file.length(),
                            dateModified = file.lastModified() / 1000,
                            mimeType = getMimeForExt(ext)
                        ))
                    }
                }
            }
        }

        scan(root, 0)
        return items
    }

    private fun getMimeForExt(ext: String): String = when (ext) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "html", "htm" -> "text/html"
        "md" -> "text/markdown"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}
