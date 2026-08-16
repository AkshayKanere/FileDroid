package com.filedroid.picker

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.filedroid.R
import com.filedroid.databinding.ActivityMediaPickerBinding
import com.filedroid.picker.tabs.*
import com.google.android.material.tabs.TabLayoutMediator

class MediaPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaPickerBinding
    private val viewModel: MediaPickerViewModel by viewModels()

    private val tabTitles = listOf("Images", "Videos", "Audio", "Docs", "All Files")
    private val tabEmojis = listOf("🖼️", "🎬", "🎵", "📄", "📁")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupViewPager()
        observeSelection()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabTitles.size

            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> ImagesFragment()
                1 -> VideosFragment()
                2 -> AudioFragment()
                3 -> DocumentsFragment()
                4 -> AllFilesFragment()
                else -> ImagesFragment()
            }
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = "${tabEmojis[position]} ${tabTitles[position]}"
        }.attach()
    }

    private fun observeSelection() {
        viewModel.selectedCount.observe(this) { count ->
            if (count > 0) {
                binding.bottomBar.visibility = View.VISIBLE
                binding.tvSelectedCount.text = "$count file${if (count > 1) "s" else ""} selected"
            } else {
                binding.bottomBar.visibility = View.GONE
            }
        }

        binding.btnDone.setOnClickListener {
            val paths = viewModel.getSelectedPaths()
            if (paths.isNotEmpty()) {
                val resultIntent = Intent().apply {
                    putStringArrayListExtra(EXTRA_SELECTED_PATHS, ArrayList(paths))
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        // Try to handle back in AllFilesFragment if it's the current tab
        val currentFragment = supportFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")
        if (currentFragment is AllFilesFragment && currentFragment.onBackPressed()) {
            return
        }
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_SELECTED_PATHS = "selected_paths"
    }
}
