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

    private val capabilityManager = AudioCapabilityManager(context)

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

    fun updatePlaybackFormat(trackInfo: AudioTrackInfo, isBitPerfect: Boolean) {
        val caps = capabilityManager.inspectDeviceCapabilities()
        val optimalSampleRate = matchOptimalSampleRate(trackInfo.sampleRateHz, caps.maxHardwareSampleRate)
        val optimalBitDepth = if (caps.isFloatSinkSupported) 32 else 24

        _engineState.value = HiFiEngineState(
            sourceFormat = trackInfo.codec,
            sourceBitDepth = trackInfo.bitDepth,
            sourceSampleRate = trackInfo.sampleRateHz,
            activeOutputSampleRate = optimalSampleRate,
            activeOutputBitDepth = optimalBitDepth,
            isFloatOutputActive = caps.isFloatSinkSupported,
            isBitPerfectActive = isBitPerfect && (optimalSampleRate == trackInfo.sampleRateHz),
            audioEngineName = "Antigravity Audiophile Media3 Core"
        )
    }

    private fun matchOptimalSampleRate(sourceRate: Int, maxHardwareRate: Int): Int {
        if (sourceRate <= 0) return 48000
        return when {
            sourceRate <= maxHardwareRate -> sourceRate
            sourceRate >= 384000 && maxHardwareRate >= 384000 -> 384000
            sourceRate >= 352800 && maxHardwareRate >= 352800 -> 352800
            sourceRate >= 192000 && maxHardwareRate >= 192000 -> 192000
            sourceRate >= 176400 && maxHardwareRate >= 176400 -> 176400
            sourceRate >= 96000 && maxHardwareRate >= 96000 -> 96000
            sourceRate >= 88200 && maxHardwareRate >= 88200 -> 88200
            sourceRate == 44100 -> 44100
            else -> 48000
        }
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
