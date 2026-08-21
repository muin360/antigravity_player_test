package com.tensorix.antigravityplayer.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MODULE 7 — AUDIO ROUTE VISUALIZER
 *
 * Responsibilities:
 * - Visualizes the live end-to-end audio route topology
 * - Maps current hardware, processing chain, and endpoint transducers
 * - Evaluates real-time quality impact (Bit-Perfect vs DSP vs Compressed)
 */
data class RouteNode(
    val nodeName: String,
    val nodeType: String,
    val hardwareDetail: String,
    val isBitPerfect: Boolean,
    val qualityFidelity: String
)

data class AudioRouteVisualizationData(
    val currentRouteName: String = "Built-in Audio Endpoint",
    val currentHardware: String = "Standard Android Audio HAL",
    val processingChain: String = "Track ➔ Decoder ➔ AudioTrack ➔ AudioFlinger ➔ Audio HAL",
    val qualityImpact: String = "Standard Android Output",
    val qualityFidelityScore: Int = 80,
    val routeNodes: List<RouteNode> = emptyList()
)

class AudioRouteVisualizer(private val context: Context) {

    private val _routeData = MutableStateFlow(AudioRouteVisualizationData())
    val routeData: StateFlow<AudioRouteVisualizationData> = _routeData.asStateFlow()

    fun updateRouteVisualization(
        activeRoute: AudioRouteCapability?,
        trackInfo: AudioTrackInfo?,
        isDspBypass: Boolean = false
    ): AudioRouteVisualizationData {
        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(
            context = context,
            trackSampleRate = trackInfo?.sampleRateHz ?: 0,
            trackBitDepth = trackInfo?.bitDepth ?: 16,
            isDspBypassed = isDspBypass
        )

        val routeType = activeRoute?.routeType ?: AudioOutputRouteType.WIRED_HEADPHONES
        val deviceName = activeRoute?.productName ?: activeRoute?.deviceName ?: routeType.displayName
        val codec = trackInfo?.codec ?: "PCM"
        val sampleRate = if (trackInfo != null && trackInfo.sampleRateHz > 0) "${trackInfo.sampleRateHz / 1000.0}kHz" else "${verifiedReport.actualOutputSampleRate / 1000.0}kHz"
        val bitDepth = "${trackInfo?.bitDepth ?: 16}-bit"

        val hardware = verifiedReport.activeDacName
        val impact = if (verifiedReport.isBitPerfectVerified) "Bit-Exact Stream (Zero DSP/Resampling)" else "Processed Stream (${verifiedReport.audioThreadType.displayName})"
        val endpoint = when (routeType) {
            AudioOutputRouteType.USB_DAC, AudioOutputRouteType.USB_DEVICE -> "USB Audio Device"
            AudioOutputRouteType.WIRED_HEADPHONES, AudioOutputRouteType.WIRED_HEADSET -> "3.5mm Headset / Headphones"
            AudioOutputRouteType.BLUETOOTH_A2DP -> "Bluetooth A2DP Device"
            else -> "Built-in Device Speaker"
        }

        val chain = "$codec $bitDepth/$sampleRate ➔ Media3 Decoder ➔ ${if (isDspBypass) "Bypass" else "64-bit DSP"} ➔ ${verifiedReport.audioThreadType.name} ➔ $hardware ➔ $endpoint"

        val nodes = listOf(
            RouteNode("Source Track", "Source", "$codec $bitDepth / $sampleRate", true, "Source Stream"),
            RouteNode("Decoder Engine", "Media3", "32-bit Float PCM Stream", true, "Decoded"),
            RouteNode("DSP Core", "Audio Engine", if (isDspBypass) "DSP Bypassed" else "64-bit DSP Active", isDspBypass, if (isDspBypass) "Bypassed" else "Processed"),
            RouteNode("Audio Thread", "AudioFlinger", verifiedReport.audioThreadType.displayName, verifiedReport.isDirectOutputSupported, if (verifiedReport.isDirectOutputSupported) "Direct Path" else "Mixer"),
            RouteNode("Hardware DAC", "HAL", hardware, verifiedReport.isBitPerfectVerified, if (verifiedReport.isVendorHiFiActive) "Hi-Fi Active" else "Standard HAL"),
            RouteNode("Transducer", "Endpoint", endpoint, true, "Analog Output")
        )

        val result = AudioRouteVisualizationData(
            currentRouteName = deviceName,
            currentHardware = hardware,
            processingChain = chain,
            qualityImpact = impact,
            qualityFidelityScore = if (verifiedReport.isBitPerfectVerified) 100 else 85,
            routeNodes = nodes
        )

        _routeData.value = result
        return result
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
