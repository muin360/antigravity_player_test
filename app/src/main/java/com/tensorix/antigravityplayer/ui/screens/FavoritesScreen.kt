package com.tensorix.antigravityplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tensorix.antigravityplayer.data.Playlist
import com.tensorix.antigravityplayer.data.Song
import com.tensorix.antigravityplayer.ui.components.SongInfoDialog
import com.tensorix.antigravityplayer.ui.components.SongListItem
import com.tensorix.antigravityplayer.ui.theme.DarkBackground
import com.tensorix.antigravityplayer.ui.theme.PrimaryCyan
import com.tensorix.antigravityplayer.ui.theme.TextPrimary
import com.tensorix.antigravityplayer.ui.theme.TextSecondary

@Composable
fun FavoritesScreen(
    favoriteSongs: List<Song>,
    currentSong: Song?,
    playlists: List<Playlist>,
    onSongClick: (Song, List<Song>) -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onAddToPlaylist: (Long, Long) -> Unit
) {
    var selectedSongForInfo by remember { mutableStateOf<Song?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.Favorite, contentDescription = null, tint = PrimaryCyan)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Favorite Tracks (${favoriteSongs.size})",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )

            if (favoriteSongs.isNotEmpty()) {
                Button(
                    onClick = { onSongClick(favoriteSongs.first(), favoriteSongs) },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play All", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (favoriteSongs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No favorite songs yet. Tap the heart icon on any song to add it here.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(favoriteSongs, key = { index, song -> "${song.id}_${song.filePath}_$index" }) { _, song ->
                    SongListItem(
                        song = song,
                        isPlaying = currentSong?.id == song.id,
                        playlists = playlists,
                        onSongClick = { onSongClick(song, favoriteSongs) },
                        onFavoriteToggle = { onFavoriteToggle(song) },
                        onAddToPlaylist = { playlistId -> onAddToPlaylist(playlistId, song.id) },
                        onShowInfo = { selectedSongForInfo = song }
                    )
                }
            }
        }
    }

    if (selectedSongForInfo != null) {
        SongInfoDialog(
            song = selectedSongForInfo,
            onDismiss = { selectedSongForInfo = null }
        )
    }
}
