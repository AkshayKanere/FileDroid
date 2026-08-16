package com.filedroid

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filedroid.databinding.ActivityFilePickerBinding
import com.filedroid.model.ServerConfig
import java.io.File

class FilePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilePickerBinding
    private lateinit var config: ServerConfig
    private lateinit var adapter: FilePickerAdapter

    private var currentPath: String = Environment.getExternalStorageDirectory().absolutePath
    private val selectedPaths = mutableSetOf<String>()
    private var mode: Int = MODE_SELECT_FILES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = ServerConfig(this)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_SELECT_FILES)

        // Load previously selected paths
        if (mode == MODE_SELECT_FILES) {
            selectedPaths.addAll(config.sharedPaths)
        }

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        navigateTo(currentPath)
    }

    private fun setupToolbar() {
        binding.toolbar.title = if (mode == MODE_PICK_FOLDER) "Select Upload Folder" else "Select Files to Share"
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = FilePickerAdapter(
            items = emptyList(),
            selectedPaths = selectedPaths,
            mode = mode,
            onItemClick = { file ->
                if (file.isDirectory) {
                    if (mode == MODE_PICK_FOLDER) {
                        // In folder pick mode, clicking selects this folder
                        selectedPaths.clear()
                        selectedPaths.add(file.absolutePath)
                    }
                    navigateTo(file.absolutePath)
                }
            },
            onItemChecked = { file, checked ->
                if (checked) {
                    selectedPaths.add(file.absolutePath)
                } else {
                    selectedPaths.remove(file.absolutePath)
                }
            }
        )

        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnCancel.setOnClickListener { finish() }

        binding.btnDone.setOnClickListener {
            if (mode == MODE_PICK_FOLDER) {
                if (selectedPaths.isNotEmpty()) {
                    config.uploadFolder = selectedPaths.first()
                }
            } else {
                config.sharedPaths = selectedPaths
            }
            finish()
        }
    }

    private fun navigateTo(path: String) {
        currentPath = path
        val dir = File(path)

        // Update breadcrumbs
        updateBreadcrumbs(path)

        // List directory
        val files = dir.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

        // In folder pick mode, only show directories
        val filtered = if (mode == MODE_PICK_FOLDER) {
            files.filter { it.isDirectory }
        } else {
            files
        }

        adapter.updateItems(filtered)
    }

    private fun updateBreadcrumbs(path: String) {
        binding.breadcrumbContainer.removeAllViews()

        val root = Environment.getExternalStorageDirectory().absolutePath
        val parts = mutableListOf<Pair<String, String>>()
        parts.add("Storage" to root)

        if (path != root) {
            val relative = path.removePrefix(root).trimStart('/')
            val segments = relative.split("/").filter { it.isNotEmpty() }
            var current = root
            for (seg in segments) {
                current = "$current/$seg"
                parts.add(seg to current)
            }
        }

        for ((index, pair) in parts.withIndex()) {
            val (name, segPath) = pair

            if (index > 0) {
                val separator = TextView(this).apply {
                    text = " › "
                    setTextColor(resources.getColor(R.color.text_secondary, theme))
                }
                binding.breadcrumbContainer.addView(separator)
            }

            val btn = TextView(this).apply {
                text = name
                setPadding(8, 4, 8, 4)
                setTextColor(
                    if (index == parts.lastIndex) resources.getColor(R.color.primary, theme)
                    else resources.getColor(R.color.text_secondary, theme)
                )
                setOnClickListener {
                    navigateTo(segPath)
                }
            }
            binding.breadcrumbContainer.addView(btn)
        }
    }

    override fun onBackPressed() {
        val parent = File(currentPath).parentFile
        val root = Environment.getExternalStorageDirectory().absolutePath
        if (parent != null && currentPath != root && parent.absolutePath.startsWith(root)) {
            navigateTo(parent.absolutePath)
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SELECT_FILES = 0
        const val MODE_PICK_FOLDER = 1
    }
}

class FilePickerAdapter(
    private var items: List<File>,
    private val selectedPaths: MutableSet<String>,
    private val mode: Int,
    private val onItemClick: (File) -> Unit,
    private val onItemChecked: (File, Boolean) -> Unit
) : RecyclerView.Adapter<FilePickerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.cbSelect)
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val name: TextView = view.findViewById(R.id.tvFileName)
        val info: TextView = view.findViewById(R.id.tvFileInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_picker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = items[position]

        holder.name.text = file.name
        holder.checkbox.isChecked = selectedPaths.contains(file.absolutePath)

        if (file.isDirectory) {
            holder.icon.setImageResource(android.R.drawable.ic_menu_agenda)
            val count = file.listFiles()?.count { !it.name.startsWith(".") } ?: 0
            holder.info.text = "$count items"
        } else {
            holder.icon.setImageResource(android.R.drawable.ic_menu_gallery)
            holder.info.text = com.filedroid.util.FileUtils.formatFileSize(file.length())
        }

        // In folder pick mode, hide checkboxes for files
        if (mode == FilePickerActivity.MODE_PICK_FOLDER) {
            holder.checkbox.visibility = View.GONE
        } else {
            holder.checkbox.visibility = View.VISIBLE
        }

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = selectedPaths.contains(file.absolutePath)
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            onItemChecked(file, checked)
        }

        holder.itemView.setOnClickListener {
            onItemClick(file)
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<File>) {
        items = newItems
        notifyDataSetChanged()
    }
}
