package com.filedroid.picker

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import androidx.appcompat.app.AppCompatActivity
import com.filedroid.databinding.ActivityPreviewBinding
import java.io.File

class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: File(path).name
        val mime = intent.getStringExtra(EXTRA_MIME) ?: ""

        binding.toolbar.title = name
        binding.toolbar.setNavigationOnClickListener { finish() }

        val uri = Uri.fromFile(File(path))

        when {
            mime.startsWith("image/") -> {
                binding.ivPreview.visibility = View.VISIBLE
                // Downsample large images to avoid OOM
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                val maxDim = 2048
                while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
                val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bmp != null) binding.ivPreview.setImageBitmap(bmp) else binding.ivPreview.setImageURI(uri)
            }
            mime.startsWith("video/") -> {
                binding.videoPreview.visibility = View.VISIBLE
                binding.videoPreview.setVideoURI(uri)
                val mc = MediaController(this)
                mc.setAnchorView(binding.videoPreview)
                binding.videoPreview.setMediaController(mc)
                binding.videoPreview.start()
            }
            mime.startsWith("audio/") -> {
                binding.videoPreview.visibility = View.VISIBLE
                binding.tvAudioLabel.visibility = View.VISIBLE
                binding.tvAudioLabel.text = "🎵 $name"
                binding.videoPreview.setVideoURI(uri)
                binding.videoPreview.start()
            }
            else -> {
                binding.tvUnsupported.visibility = View.VISIBLE
                binding.tvUnsupported.text = "Preview not available for this file type"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.videoPreview.isPlaying) binding.videoPreview.pause()
    }

    override fun onDestroy() {
        binding.videoPreview.stopPlayback()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "preview_path"
        const val EXTRA_NAME = "preview_name"
        const val EXTRA_MIME = "preview_mime"
    }
}
