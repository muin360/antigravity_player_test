package com.tensorix.antigravityplayer.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tensorix.antigravityplayer.data.Song
import com.tensorix.antigravityplayer.data.remote.YtSearchResultItem
import com.tensorix.antigravityplayer.ui.theme.CardBackground
import com.tensorix.antigravityplayer.ui.theme.DarkBackground
import com.tensorix.antigravityplayer.ui.theme.PrimaryCyan
import com.tensorix.antigravityplayer.ui.theme.SecondaryViolet
import com.tensorix.antigravityplayer.ui.theme.SurfaceDark
import com.tensorix.antigravityplayer.ui.theme.TextPrimary
import com.tensorix.antigravityplayer.ui.theme.TextSecondary

@Composable
fun YtSearchScreen(
    currentSong: Song?,
    searchResults: List<YtSearchResultItem>,
    isSearching: Boolean,
    downloadProgress: Int = -1,
    downloadingTrackId: String? = null,
    onSearchSubmit: (String) -> Unit,
    onStreamTrack: (YtSearchResultItem) -> Unit,
    onDownloadTrack: (YtSearchResultItem) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Search Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search online tracks on YouTube...", color = TextSecondary) },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = PrimaryCyan) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                        }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryCyan,
                    unfocusedBorderColor = SurfaceDark,
                    focusedContainerColor = SurfaceDark.copy(alpha = 0.6f),
                    unfocusedContainerColor = SurfaceDark.copy(alpha = 0.3f),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { if (query.isNotBlank()) onSearchSubmit(query) },
                enabled = !isSearching
            ) {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PrimaryCyan, strokeWidth = 2.dp)
                } else {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = PrimaryCyan)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "YOUTUBE · STREAM & DOWNLOAD",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
            color = PrimaryCyan
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (searchResults.isEmpty() && !isSearching) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = SurfaceDark,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Search for any song to stream or download",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "▶ Stream instantly  ⬇ Save to device",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(searchResults, key = { index, item -> "${item.id}_$index" }) { index, item ->
                    val isCurrent = currentSong?.youtubeId == item.id
                    val isDownloading = downloadingTrackId == item.id

                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isCurrent) CardBackground.copy(alpha = 0.8f) else CardBackground.copy(alpha = 0.3f))
                                .clickable { onStreamTrack(item) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceDark),
                                contentAlignment = Alignment.Center
                            ) {
                                if (item.thumbnailUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model = item.thumbnailUrl,
                                        contentDescription = item.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = PrimaryCyan,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    ),
                                    color = if (isCurrent) PrimaryCyan else TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${item.artist} • ${formatYtDuration(item.durationSeconds)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Download button
                            IconButton(
                                onClick = { onDownloadTrack(item) },
                                enabled = !isDownloading
                            ) {
                                if (isDownloading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = SecondaryViolet,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = "Download",
                                        tint = SecondaryViolet
                                    )
                                }
                            }

                            // Play/Stream button
                            IconButton(onClick = { onStreamTrack(item) }) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play Stream",
                                    tint = PrimaryCyan
                                )
                            }
                        }

                        // Download progress bar
                        if (isDownloading && downloadProgress in 0..100) {
                            val animatedProgress by animateFloatAsState(
                                targetValue = downloadProgress / 100f,
                                label = "downloadProgress"
                            )
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = SecondaryViolet,
                                trackColor = SurfaceDark,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatYtDuration(seconds: Long): String {
    val totalSeconds = seconds.coerceAtLeast(0L)
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format(java.util.Locale.getDefault(), "%d:%02d", mins, secs)
}
