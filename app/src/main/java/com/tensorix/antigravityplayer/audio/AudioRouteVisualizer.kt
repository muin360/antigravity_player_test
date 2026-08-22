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

    @androidx.media3.common.util.UnstableApi
    fun updateRouteVisualization(
        activeRoute: AudioRouteCapability?,
        trackInfo: AudioTrackInfo?,
        isDspBypass: Boolean = false
    ): AudioRouteVisualizationData {
        val snapshot = AudioVerificationEngine.buildCanonicalSnapshot(
            context = context,
            trackInfo = trackInfo ?: AudioTrackInfo(),
            isDspActive = !isDspBypass,
            activeRoute = activeRoute,
            dspProcessor = com.tensorix.antigravityplayer.player.PlaybackService.instance?.dspProcessor
        )

        val deviceName = snapshot.activeRoute.value.displayName
        val codec = snapshot.source.encoding.value
        val sampleRate = "${snapshot.source.sampleRate.value / 1000.0}kHz"
        val bitDepth = "${snapshot.source.bitDepth.value}-bit"

        val hardware = snapshot.dac.modelName.value
        val bitPerfect = snapshot.bitPerfect.state == BitPerfectState.VERIFIED
        val impact = if (bitPerfect) "Bit-Exact Stream (Zero DSP/Resampling)" else snapshot.bitPerfect.evidence
        
        val endpoint = when (snapshot.activeRoute.value) {
            AudioOutputRouteType.USB_DAC, AudioOutputRouteType.USB_DEVICE -> "USB Audio Device"
            AudioOutputRouteType.WIRED_HEADPHONES, AudioOutputRouteType.WIRED_HEADSET -> "3.5mm Headset / Headphones"
            AudioOutputRouteType.BLUETOOTH_A2DP -> "Bluetooth A2DP Device"
            else -> "Built-in Device Speaker"
        }

        val chain = "$codec $bitDepth/$sampleRate ➔ Media3 Decoder ➔ ${snapshot.dspState.value} ➔ ${snapshot.audioApi.value.label} ➔ $hardware ➔ $endpoint"

        val nodes = listOf(
            RouteNode("Source Track", "Source", "$codec $bitDepth / $sampleRate", true, "Source Stream"),
            RouteNode("Decoder Engine", "Media3", "32-bit Float PCM Stream", true, "Decoded"),
            RouteNode("DSP Core", "Audio Engine", snapshot.dspState.value, snapshot.dspState.value == "OFF", snapshot.dspState.value),
            RouteNode("Audio Thread", "AudioFlinger", snapshot.sharingMode.value, snapshot.directPathActive.value, if (snapshot.directPathActive.value) "Direct Path" else "Mixer"),
            RouteNode("Hardware DAC", "HAL", hardware, bitPerfect, if (snapshot.dac.isActive.value) "Hi-Fi Active" else "Standard HAL"),
            RouteNode("Transducer", "Endpoint", endpoint, true, "Analog Output")
        )

        val result = AudioRouteVisualizationData(
            currentRouteName = deviceName,
            currentHardware = hardware,
            processingChain = chain,
            qualityImpact = impact,
            qualityFidelityScore = if (bitPerfect) 100 else 85,
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
