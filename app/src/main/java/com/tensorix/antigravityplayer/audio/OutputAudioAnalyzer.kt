package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MODULE 4 — OUTPUT AUDIO ANALYZER
 *
 * Responsibilities:
 * - Analyzes live destination hardware output state
 * - Evaluates sample rate, 32-bit float bit depth, and channel count
 * - Calculates output latency, hardware offload status, and output quality score (0-100)
 */
data class OutputAnalysisResult(
    val currentOutputDevice: String = "Built-in Audio Endpoint",
    val currentOutputSampleRateHz: Int = 48000,
    val currentOutputBitDepth: Int = 16,
    val outputChannelCount: Int = 2,
    val outputAudioApi: String = "Standard AudioTrack (AudioFlinger)",
    val audioRoute: String = "Standard Output",
    val audioPath: String = "AudioFlinger Mixer",
    val latencyMs: Int = 12,
    val hardwareOffloadStatus: String = "Standard System HAL",
    val outputQualityScore: Int = 85,
    val outputQualityRating: String = "Audited Output",
    val routeDescription: String = "AudioTrack output"
)

class OutputAudioAnalyzer(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _currentAnalysis = MutableStateFlow(OutputAnalysisResult())
    val currentAnalysis: StateFlow<OutputAnalysisResult> = _currentAnalysis.asStateFlow()

    fun analyzeOutput(
        activeRoute: AudioRouteCapability?,
        trackInfo: AudioTrackInfo?,
        isDspBypass: Boolean = false
    ): OutputAnalysisResult {
        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(
            context = context,
            trackSampleRate = trackInfo?.sampleRateHz ?: 0,
            trackBitDepth = trackInfo?.bitDepth ?: 16,
            isDspBypassed = isDspBypass
        )

        val routeType = activeRoute?.routeType ?: AudioOutputRouteType.WIRED_HEADPHONES
        val deviceName = activeRoute?.productName ?: activeRoute?.deviceName ?: routeType.displayName
        val sampleRate = verifiedReport.actualOutputSampleRate
        val bitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 32 else 24
        val channels = 2

        val audioApi = verifiedReport.actualAudioSinkType

        val latency = when (routeType) {
            AudioOutputRouteType.USB_DAC, AudioOutputRouteType.USB_DEVICE -> 10
            AudioOutputRouteType.WIRED_HEADPHONES, AudioOutputRouteType.WIRED_HEADSET -> 12
            AudioOutputRouteType.BLUETOOTH_A2DP -> 45
            AudioOutputRouteType.HDMI -> 15
            else -> 18
        }

        val offloadStatus = when {
            verifiedReport.isVendorHiFiActive -> "Vivo Hi-Fi Hardware Offload Active"
            verifiedReport.isDirectOutputSupported -> "Direct PCM Hardware Track"
            else -> "AudioFlinger System Mixer"
        }

        val (score, rating, routeDesc) = when {
            verifiedReport.isBitPerfectVerified -> Triple(
                100,
                "Bit-Exact Audio Output",
                "Direct hardware DAC conversion with matched audio clock"
            )
            verifiedReport.isDirectOutputSupported -> Triple(
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

        val path = if (verifiedReport.isBitPerfectVerified) "Direct Bit-Exact Hardware Stream" else "AudioFlinger System Mixer ($sampleRate Hz)"

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
