package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller alias forwarding to the Single Authoritative AudioEngine.
 */
@UnstableApi
object AudioEngineController {

    val snapshot: StateFlow<CanonicalAudioRuntimeSnapshot?>
        get() = AudioEngine.snapshot

    val activeRoute: StateFlow<AudioRouteCapability?>
        get() = AudioEngine.activeRoute

    val bitPerfectState: StateFlow<BitPerfectState>
        get() = AudioEngine.bitPerfectState

    fun updateSnapshot(context: Context, trackInfo: AudioTrackInfo, isDspActive: Boolean) {
        AudioEngine.updateSnapshot(context, trackInfo, isDspActive)
    }

    fun setSnapshot(snapshot: CanonicalAudioRuntimeSnapshot?) {
        AudioEngine.setSnapshot(snapshot)
    }

    fun invalidate() {
        AudioEngine.invalidate()
    }

    fun getBitPerfectState(): BitPerfectState {
        return AudioEngine.getBitPerfectState()
    }

    fun reconfigureForRouteChange(context: Context, newRoute: AudioRouteCapability?) {
        AudioEngine.reconfigureForRouteChange(context, newRoute)
    }
}
