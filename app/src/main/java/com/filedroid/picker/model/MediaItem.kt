package com.filedroid.picker.model

data class MediaItem(
    val path: String,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val mimeType: String,
    val duration: Long = 0L,      // ms, for audio/video
    val artist: String = "",       // for audio
    val album: String = "",        // for audio
    val width: Int = 0,            // for images/video
    val height: Int = 0            // for images/video
)
