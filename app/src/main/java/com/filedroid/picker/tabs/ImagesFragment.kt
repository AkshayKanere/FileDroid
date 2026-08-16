package com.filedroid.picker.tabs

import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.filedroid.databinding.FragmentMediaGridBinding
import com.filedroid.picker.MediaPickerViewModel
import com.filedroid.picker.PreviewActivity
import com.filedroid.picker.adapters.MediaGridAdapter
import com.filedroid.picker.model.MediaItem
import kotlinx.coroutines.*

class ImagesFragment : Fragment() {

    private var _binding: FragmentMediaGridBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaPickerViewModel by activityViewModels()
    private lateinit var adapter: MediaGridAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MediaGridAdapter(
            showDuration = false,
            onItemClick = { item -> viewModel.toggle(item.path) },
            onItemLongClick = { item -> openPreview(item) },
            onGroupSelectToggle = { groupTitle, select ->
                val paths = adapter.getGroupPaths(groupTitle)
                if (select) viewModel.selectAll(paths) else viewModel.deselectAll(paths)
            },
            isSelected = { path -> viewModel.isSelected(path) }
        )

        val spanCount = 3
        val layoutManager = GridLayoutManager(requireContext(), spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == 0) spanCount else 1
            }
        }

        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter

        viewModel.selectedPaths.observe(viewLifecycleOwner) {
            adapter.notifySelectionChanged()
        }

        loadImages()
    }

    private fun loadImages() {
        binding.progressBar.visibility = View.VISIBLE
        scope.launch {
            val images = withContext(Dispatchers.IO) { queryImages() }
            if (_binding == null) return@launch
            binding.progressBar.visibility = View.GONE
            if (images.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                adapter.submitItems(images)
            }
        }
    }

    private fun queryImages(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        requireContext().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            while (cursor.moveToNext()) {
                items.add(MediaItem(
                    path = cursor.getString(pathCol) ?: continue,
                    name = cursor.getString(nameCol) ?: "image",
                    size = cursor.getLong(sizeCol),
                    dateModified = cursor.getLong(dateCol),
                    mimeType = cursor.getString(mimeCol) ?: "image/*",
                    width = cursor.getInt(widthCol),
                    height = cursor.getInt(heightCol)
                ))
            }
        }
        return items
    }

    private fun openPreview(item: MediaItem) {
        startActivity(android.content.Intent(requireContext(), PreviewActivity::class.java).apply {
            putExtra(PreviewActivity.EXTRA_PATH, item.path)
            putExtra(PreviewActivity.EXTRA_NAME, item.name)
            putExtra(PreviewActivity.EXTRA_MIME, item.mimeType)
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        adapter.shutdown()
        _binding = null
    }
}
