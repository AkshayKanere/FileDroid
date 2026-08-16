package com.filedroid.picker.tabs

import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.filedroid.databinding.FragmentMediaListBinding
import com.filedroid.picker.MediaPickerViewModel
import com.filedroid.picker.PreviewActivity
import com.filedroid.picker.adapters.AudioListAdapter
import com.filedroid.picker.model.MediaItem
import kotlinx.coroutines.*

class AudioFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaPickerViewModel by activityViewModels()
    private lateinit var adapter: AudioListAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AudioListAdapter(
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

        loadAudio()
    }

    private fun loadAudio() {
        binding.progressBar.visibility = View.VISIBLE
        scope.launch {
            val audio = withContext(Dispatchers.IO) { queryAudio() }
            if (_binding == null) return@launch
            binding.progressBar.visibility = View.GONE
            if (audio.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                adapter.submitList(audio)
            }
        }
    }

    private fun queryAudio(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )

        requireContext().contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)

            while (cursor.moveToNext()) {
                items.add(MediaItem(
                    path = cursor.getString(pathCol) ?: continue,
                    name = cursor.getString(nameCol) ?: "audio",
                    size = cursor.getLong(sizeCol),
                    dateModified = cursor.getLong(dateCol),
                    mimeType = cursor.getString(mimeCol) ?: "audio/*",
                    duration = cursor.getLong(durCol),
                    artist = cursor.getString(artistCol) ?: "",
                    album = cursor.getString(albumCol) ?: ""
                ))
            }
        }
        return items
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}
