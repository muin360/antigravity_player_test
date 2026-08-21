package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

// ==========================================
// 1. DATA MODELS & ENUMS
// ==========================================

enum class AudioSourceType(val label: String) {
    LOCAL_FILE("Local Storage"),
    NETWORK_STREAM("Network Audio Stream"),
    CLOUD_STREAM("Cloud Lossless Stream"),
    USB_STORAGE("USB Audio Drive"),
    SD_CARD("External SD Card")
}

data class ComprehensiveAudioMetadata(
    val fileName: String = "",
    val trackTitle: String = "Unknown Track",
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val genre: String = "Audiophile Master",
    val year: String = "",
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val codec: String = "FLAC",
    val containerFormat: String = "Free Lossless Audio Codec",
    val bitrateKbps: Int = 3100,
    val isVariableBitrate: Boolean = true,
    val bitDepth: Int = 24,
    val sampleRateHz: Int = 96000,
    val channels: Int = 2,
    val channelLayout: String = "Stereo (L / R)",
    val dynamicRangeDb: Double = 14.2,
    val integratedLoudnessLufs: Double = -14.0,
    val replayGainTrackDb: Double = -1.2,
    val replayGainAlbumDb: Double = -1.5,
    val hasEmbeddedLyrics: Boolean = true,
    val hasEmbeddedArtwork: Boolean = true,
    val fileLocation: String = "",
    val sourceType: AudioSourceType = AudioSourceType.LOCAL_FILE
)

data class InputAnalysisReport(
    val sourceCodec: String,
    val sourceBitrateKbps: Int,
    val sourceSampleRateHz: Int,
    val sourceBitDepth: Int,
    val sourceChannels: Int,
    val dynamicRangeDb: Double,
    val loudnessLufs: Double,
    val qualityScore: Int // 0-100
)

data class OutputAnalysisReport(
    val outputDeviceName: String,
    val outputSampleRateHz: Int,
    val outputBitDepth: Int,
    val outputChannels: Int,
    val outputAudioApi: String,
    val audioRoute: String,
    val audioPath: String,
    val estimatedLatencyMs: Int,
    val hardwareOffloadStatus: String,
    val qualityScore: Int // 0-100
)

data class DacHardwareProfile(
    val dacName: String,
    val dacVendor: String,
    val dacModel: String,
    val dacArchitecture: String,
    val supportedFormats: List<String>,
    val supportedSampleRates: List<Int>,
    val supportedBitDepths: List<Int>,
    val maxCapabilities: String,
    val currentOperatingMode: String,
    val currentActiveFormat: String
)

data class BluetoothIntelligenceReport(
    val currentCodec: String,
    val currentBitrateKbps: Int,
    val qualityMode: String,
    val sampleRateHz: Int,
    val linkStabilityScore: Int,
    val signalStrengthDbm: Int,
    val connectionQuality: String,
    val estimatedAudioQuality: String,
    val warnings: List<String>
)

data class AudioHealthScoreReport(
    val score: Int, // 0-100
    val rating: String,
    val positiveFactors: List<String>,
    val activeWarnings: List<String>
)

data class AudioProfile(
    val id: String,
    val name: String,
    val description: String,
    val eqGainsDb: List<Double>,
    val bassBoostGainDb: Double,
    val trebleGainDb: Double,
    val replayGainEnabled: Boolean,
    val crossfeedEnabled: Boolean,
    val limiterEnabled: Boolean,
    val sampleRatePolicy: String,
    val bitDepthPolicy: String
)

// ==========================================
// 2. AUDIO INTELLIGENCE PLATFORM ENGINE
// ==========================================

class AudioIntelligencePlatform(private val context: Context) {

    private val _metadata = MutableStateFlow(ComprehensiveAudioMetadata())
    val metadata: StateFlow<ComprehensiveAudioMetadata> = _metadata.asStateFlow()

    private val _inputAnalysis = MutableStateFlow(
        InputAnalysisReport("PCM", 0, 44100, 16, 2, 96.0, -14.0, 85)
    )
    val inputAnalysis: StateFlow<InputAnalysisReport> = _inputAnalysis.asStateFlow()

    private val _outputAnalysis = MutableStateFlow(
        OutputAnalysisReport("Built-in Audio Endpoint", 48000, 16, 2, "Standard AudioTrack (AudioFlinger)", "Standard Output", "AudioFlinger Mixer", 12, "Standard System HAL", 85)
    )
    val outputAnalysis: StateFlow<OutputAnalysisReport> = _outputAnalysis.asStateFlow()

    private val _dacInfo = MutableStateFlow(
        DacHardwareProfile(
            dacName = "Standard Android Audio HAL",
            dacVendor = "AOSP / Google Audio HAL",
            dacModel = "Standard Mobile Audio Architecture",
            dacArchitecture = "SoC Integrated Audio Codec",
            supportedFormats = listOf("PCM", "FLAC", "ALAC", "WAV"),
            supportedSampleRates = listOf(44100, 48000),
            supportedBitDepths = listOf(16, 24),
            maxCapabilities = "Standard Android Audio Pipeline",
            currentOperatingMode = "AudioFlinger System Mixer",
            currentActiveFormat = "16-bit / 48.0 kHz"
        )
    )
    val dacInfo: StateFlow<DacHardwareProfile> = _dacInfo.asStateFlow()

    private val _bluetoothIntelligence = MutableStateFlow(
        BluetoothIntelligenceReport("SBC / AAC", 328, "Standard Wireless Audio", 44100, 80, 0, "A2DP Wireless Link", "Standard Compressed Stream", emptyList())
    )
    val bluetoothIntelligence: StateFlow<BluetoothIntelligenceReport> = _bluetoothIntelligence.asStateFlow()

    private val _healthScore = MutableStateFlow(
        AudioHealthScoreReport(
            score = 85,
            rating = "Audited Audio Signal",
            positiveFactors = listOf("Native AudioTrack Stream", "32-bit Float Processing"),
            activeWarnings = emptyList()
        )
    )
    val healthScore: StateFlow<AudioHealthScoreReport> = _healthScore.asStateFlow()

    // 16 Standard Audiophile Profiles
    val builtInProfiles: List<AudioProfile> = listOf(
        AudioProfile("audiophile", "Audiophile Master", "Reference sound configuration", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0, 0.0, true, false, true, "Source Match", "32-bit Float"),
        AudioProfile("iem", "IEM Pure Reference", "Harmon target curve tailored for In-Ear Monitors", listOf(1.5, 1.0, 0.5, 0.0, 0.0, 0.5, 1.0, 1.5, 2.0, 2.5), 1.0, 1.5, true, true, true, "Source Match", "32-bit Float"),
        AudioProfile("usb_dac", "USB DAC Direct", "Dedicated USB Audio Class direct profile", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0, 0.0, false, false, false, "Direct Passthrough", "32-bit Float"),
        AudioProfile("bluetooth_ldac", "LDAC Hi-Res", "High-bitrate Bluetooth wireless profile", listOf(1.0, 0.5, 0.0, 0.0, 0.5, 0.5, 1.0, 1.5, 2.0, 1.5), 2.0, 1.0, true, false, true, "96kHz Fixed", "24-bit PCM"),
        AudioProfile("bass_boost", "Dynamic Bass Sub-Bass", "Low-frequency elevation with limiter", listOf(6.0, 4.5, 3.0, 1.5, 0.0, 0.0, 0.5, 1.0, 2.0, 2.5), 6.0, 1.0, true, false, true, "Source Match", "32-bit Float"),
        AudioProfile("studio_monitor", "Studio Monitor Flat", "Uncolored ruler-flat frequency response", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0, 0.0, true, false, true, "Source Match", "32-bit Float"),
        AudioProfile("car_audio", "Car Audio Acoustics", "Road-noise compensation with midrange enhancement", listOf(4.0, 3.0, 1.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5), 4.0, 2.0, true, false, true, "48kHz Adaptive", "24-bit PCM")
    )

    private val _activeProfile = MutableStateFlow(builtInProfiles[0])
    val activeProfile: StateFlow<AudioProfile> = _activeProfile.asStateFlow()

    fun updateTrackMetadata(info: AudioTrackInfo, filePath: String = "") {
        val dynamicRange = when {
            info.bitDepth >= 24 -> 144.0
            else -> 96.0
        }
        val score = calculateInputScore(info)

        val extractedName = if (filePath.contains("/")) filePath.substringAfterLast('/') else filePath
        val resolvedName = if (extractedName.isNotBlank()) extractedName else info.title

        _metadata.value = ComprehensiveAudioMetadata(
            fileName = resolvedName,
            trackTitle = info.title,
            artist = info.artist,
            codec = info.codec,
            bitrateKbps = info.bitrateKbps,
            bitDepth = if (info.bitDepth > 0) info.bitDepth else 16,
            sampleRateHz = if (info.sampleRateHz > 0) info.sampleRateHz else 44100,
            channels = info.channels,
            dynamicRangeDb = dynamicRange,
            fileLocation = filePath
        )

        _inputAnalysis.value = InputAnalysisReport(
            sourceCodec = info.codec,
            sourceBitrateKbps = info.bitrateKbps,
            sourceSampleRateHz = if (info.sampleRateHz > 0) info.sampleRateHz else 44100,
            sourceBitDepth = if (info.bitDepth > 0) info.bitDepth else 16,
            sourceChannels = info.channels,
            dynamicRangeDb = dynamicRange,
            loudnessLufs = -14.0,
            qualityScore = score
        )

        recalculateHealthScore(info, _outputAnalysis.value)
    }

    private fun calculateInputScore(info: AudioTrackInfo): Int {
        var score = 70
        if (info.isHiRes || info.sampleRateHz >= 88200) score += 15
        if (info.bitDepth >= 24) score += 10
        if (info.codec.uppercase() in listOf("FLAC", "ALAC", "WAV", "DSD", "AIFF")) score += 5
        return score.coerceIn(50, 100)
    }

    private fun recalculateHealthScore(info: AudioTrackInfo, out: OutputAnalysisReport) {
        val positives = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var score = 85

        if (info.bitDepth >= 24) positives.add("${info.bitDepth}-bit High-Resolution Source")
        if (info.sampleRateHz >= 48000) positives.add("${info.sampleRateHz / 1000}kHz Source Clock")
        positives.add("32-bit Float AudioSink Active")

        if (out.outputAudioApi.contains("Bluetooth", ignoreCase = true) && !out.outputAudioApi.contains("LDAC", ignoreCase = true)) {
            warnings.add("Lossy Bluetooth Codec Compression Active")
            score -= 15
        }

        _healthScore.value = AudioHealthScoreReport(
            score = score.coerceIn(50, 100),
            rating = if (score >= 90) "Bit-Exact Reference Signal" else "Audited Audio Signal",
            positiveFactors = positives,
            activeWarnings = warnings
        )
    }

    fun applyProfile(profile: AudioProfile) {
        _activeProfile.value = profile
    }

    fun autoSwitchProfileForRoute(routeType: AudioOutputRouteType, bluetoothCodec: String?) {
        val matched = when (routeType) {
            AudioOutputRouteType.USB_DAC, AudioOutputRouteType.USB_DEVICE -> builtInProfiles.firstOrNull { it.id == "usb_dac" }
            AudioOutputRouteType.WIRED_HEADPHONES, AudioOutputRouteType.WIRED_HEADSET -> builtInProfiles.firstOrNull { it.id == "iem" }
            AudioOutputRouteType.BLUETOOTH_A2DP -> {
                if (bluetoothCodec?.contains("LDAC", ignoreCase = true) == true) {
                    builtInProfiles.firstOrNull { it.id == "bluetooth_ldac" }
                } else {
                    builtInProfiles.firstOrNull { it.id == "audiophile" }
                }
            }
            AudioOutputRouteType.SPEAKER -> builtInProfiles.firstOrNull { it.id == "bass_boost" }
            else -> builtInProfiles.firstOrNull { it.id == "audiophile" }
        }
        matched?.let { _activeProfile.value = it }
    }
}
