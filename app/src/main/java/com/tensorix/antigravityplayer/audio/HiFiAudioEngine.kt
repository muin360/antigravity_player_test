package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * HiFiAudioEngine — Audiophile Audio Pipeline Coordinator
 * Handles dynamic sample-rate matching (44.1k - 192kHz+), 32-bit Float PCM rendering,
 * and resilient hardware fallback across all Android versions.
 */
@UnstableApi
class HiFiAudioEngine(private val context: Context) {

    private val _engineState = MutableStateFlow(HiFiEngineState())
    val engineState: StateFlow<HiFiEngineState> = _engineState.asStateFlow()

    fun buildAudiophileAudioSink(
        enableFloat: Boolean,
        enableTrackPlaybackParams: Boolean
    ): DefaultAudioSink {
        val builder = DefaultAudioSink.Builder(context)

        val isFloatAllowed = enableFloat && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        if (isFloatAllowed) {
            builder.setEnableFloatOutput(true)
        }

        builder.setEnableAudioTrackPlaybackParams(enableTrackPlaybackParams)
        return builder.build()
    }

    fun updatePlaybackFormat(trackInfo: AudioTrackInfo) {
        val snapshot = AudioEngineController.snapshot.value ?: return
        
        _engineState.value = HiFiEngineState(
            sourceFormat = trackInfo.codec,
            sourceBitDepth = trackInfo.bitDepth,
            sourceSampleRate = trackInfo.sampleRateHz,
            activeOutputSampleRate = snapshot.actualOutput.sampleRate.value,
            activeOutputBitDepth = snapshot.actualOutput.bitDepth.value,
            isFloatOutputActive = snapshot.actualOutput.encoding.value.contains("Float"),
            isBitPerfectActive = snapshot.bitPerfect.state == BitPerfectState.VERIFIED,
            audioEngineName = "Antigravity Audiophile Media3 Core"
        )
    }
}

data class HiFiEngineState(
    val sourceFormat: String = "PCM",
    val sourceBitDepth: Int = 16,
    val sourceSampleRate: Int = 44100,
    val activeOutputSampleRate: Int = 44100,
    val activeOutputBitDepth: Int = 16,
    val isFloatOutputActive: Boolean = false,
    val isBitPerfectActive: Boolean = false,
    val audioEngineName: String = "Antigravity Audiophile Engine"
)
