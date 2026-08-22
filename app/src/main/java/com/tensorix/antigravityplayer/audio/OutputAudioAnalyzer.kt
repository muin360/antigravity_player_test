package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MODULE 4 — OUTPUT AUDIO ANALYZER
 *
 * Responsibilities:
 * - Analyzes destination hardware output state strictly from canonical telemetry
 */
data class OutputAnalysisResult(
    val currentOutputDevice: String = "Built-in Audio Endpoint",
    val currentOutputSampleRateHz: Int = 48000,
    val currentOutputBitDepth: Int = 16,
    val outputChannelCount: Int = 2,
    val outputAudioApi: String = "Standard AudioTrack",
    val audioRoute: String = "Standard Output",
    val audioPath: String = "AudioFlinger Mixer",
    val latencyMs: Int = 12,
    val hardwareOffloadStatus: String = "Standard System HAL",
    val outputQualityScore: Int = 85,
    val outputQualityRating: String = "Audited Output",
    val routeDescription: String = "AudioTrack output"
)

@UnstableApi
class OutputAudioAnalyzer(private val context: Context) {

    private val _currentAnalysis = MutableStateFlow(OutputAnalysisResult())
    val currentAnalysis: StateFlow<OutputAnalysisResult> = _currentAnalysis.asStateFlow()

    fun analyzeOutput(
        activeRoute: AudioRouteCapability?,
        trackInfo: AudioTrackInfo?,
        isDspBypass: Boolean = false
    ): OutputAnalysisResult {
        val canonicalSnapshot = AudioVerificationEngine.buildCanonicalSnapshot(
            context = context,
            trackInfo = trackInfo ?: AudioTrackInfo(),
            isDspActive = !isDspBypass,
            activeRoute = activeRoute,
            dspProcessor = com.tensorix.antigravityplayer.player.PlaybackService.instance?.dspProcessor
        )

        val routeType = activeRoute?.routeType ?: AudioOutputRouteType.SPEAKER
        val deviceName = activeRoute?.productName ?: activeRoute?.deviceName ?: routeType.displayName
        val sampleRate = canonicalSnapshot.actualOutput.sampleRate.value
        val bitDepth = canonicalSnapshot.actualOutput.bitDepth.value
        val channels = canonicalSnapshot.actualOutput.channels.value

        val audioApi = canonicalSnapshot.audioApi.value.label

        val latency = when (routeType) {
            AudioOutputRouteType.USB_DAC, AudioOutputRouteType.USB_DEVICE -> 10
            AudioOutputRouteType.WIRED_HEADPHONES, AudioOutputRouteType.WIRED_HEADSET -> 12
            AudioOutputRouteType.BLUETOOTH_A2DP -> 45
            AudioOutputRouteType.HDMI -> 15
            else -> 18
        }

        val offloadStatus = when {
            canonicalSnapshot.directPathActive.value -> "Direct HAL Stream Active"
            canonicalSnapshot.dac.isActive.value -> "OEM Hi-Fi Hardware Offload Active"
            else -> "AudioFlinger System Mixer"
        }

        val isBitPerfect = canonicalSnapshot.bitPerfect.state == BitPerfectState.VERIFIED

        val (score, rating, routeDesc) = when {
            isBitPerfect -> Triple(
                100,
                "Bit-Exact Audio Output",
                "Direct hardware DAC conversion with matched audio clock"
            )
            canonicalSnapshot.directPathActive.value -> Triple(
                95,
                "Direct Hardware AudioTrack",
                "Direct audio track to device HAL"
            )
            else -> Triple(
                85,
                "AudioFlinger Processed Output",
                "Standard system audio mixer at $sampleRate Hz"
            )
        }

        val path = if (isBitPerfect) "Direct Bit-Exact Hardware Stream" else "AudioFlinger System Mixer ($sampleRate Hz)"

        val result = OutputAnalysisResult(
            currentOutputDevice = deviceName,
            currentOutputSampleRateHz = sampleRate,
            currentOutputBitDepth = bitDepth,
            outputChannelCount = channels,
            outputAudioApi = audioApi,
            audioRoute = routeType.displayName,
            audioPath = path,
            latencyMs = latency,
            hardwareOffloadStatus = offloadStatus,
            outputQualityScore = score,
            outputQualityRating = rating,
            routeDescription = routeDesc
        )

        _currentAnalysis.value = result
        return result
    }
}
