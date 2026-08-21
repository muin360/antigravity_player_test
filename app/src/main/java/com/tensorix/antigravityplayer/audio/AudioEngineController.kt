package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single Authoritative Controller for the Antigravity Audio Engine.
 * Coordinates between Native Oboe, Media3, and Hardware Verification.
 */
@UnstableApi
object AudioEngineController {

    private val _snapshot = MutableStateFlow<CanonicalAudioRuntimeSnapshot?>(null)
    val snapshot: StateFlow<CanonicalAudioRuntimeSnapshot?> = _snapshot.asStateFlow()

    fun updateSnapshot(context: Context, trackInfo: AudioTrackInfo, isDspActive: Boolean) {
        val outputManager = com.tensorix.antigravityplayer.player.PlaybackService.instance?.audioOutputManager
        val state = outputManager?.scanOutputState(trackInfo, isDspActive)
        _snapshot.value = state?.canonicalSnapshot
    }

    fun getBitPerfectState(): BitPerfectState {
        return _snapshot.value?.bitPerfect?.state ?: BitPerfectState.UNKNOWN
    }
}
