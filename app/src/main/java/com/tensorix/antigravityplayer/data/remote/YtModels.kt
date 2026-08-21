package com.tensorix.antigravityplayer.data.remote

data class YtSearchResultItem(
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String
)

data class YtSearchResponse(
    val query: String,
    val results: List<YtSearchResultItem>
)

data class YtStreamResponse(
    val id: String,
    val streamUrl: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String
)

data class YtDownloadResponse(
    val id: String,
    val success: Boolean,
    val downloadUrl: String,
    val fileName: String
)
