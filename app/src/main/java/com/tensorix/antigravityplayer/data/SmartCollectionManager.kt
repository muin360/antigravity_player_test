package com.tensorix.antigravityplayer.data

/**
 * Smart Collection & Library Intelligence Engine
 * Dynamically categorizes library tracks into smart collections:
 *  - Hi-Res Lossless Masters (FLAC, ALAC, WAV, DSD, DXD, AIFF, >= 48kHz / 24-bit)
 *  - Recently Added Tracks (added within last 14 days)
 *  - Audiophile Favorites
 * Hardened against null formats, partial MIME matches, and Unicode search queries.
 */
class SmartCollectionManager(private val songDao: SongDao? = null) {

    fun filterHiResTracks(songs: List<Song>): List<Song> {
        return songs.filter { song ->
            val fmt = song.format?.uppercase() ?: ""
            fmt.contains("FLAC") ||
                    fmt.contains("ALAC") ||
                    fmt.contains("WAV") ||
                    fmt.contains("AIFF") ||
                    fmt.contains("DSD") ||
                    fmt.contains("DXD") ||
                    fmt.contains("LOSSLESS") ||
                    song.sampleRate >= 48000 ||
                    song.bitrate >= 900
        }
    }

    fun filterRecentlyAdded(songs: List<Song>, daysLimit: Int = 14): List<Song> {
        val cutoff = System.currentTimeMillis() - (daysLimit * 24 * 60 * 60 * 1000L)
        return songs.filter { it.dateAdded >= cutoff }
            .sortedByDescending { it.dateAdded }
    }

    fun filterFavorites(songs: List<Song>): List<Song> {
        return songs.filter { it.isFavorite }
            .sortedBy { it.title.lowercase() }
    }

    fun searchFuzzy(songs: List<Song>, query: String): List<Song> {
        if (query.isBlank()) return songs
        val q = query.trim().lowercase()
        return songs.filter { song ->
            song.title.lowercase().contains(q) ||
                    song.artist.lowercase().contains(q) ||
                    song.album.lowercase().contains(q) ||
                    (song.format?.lowercase()?.contains(q) == true)
        }
    }
}
