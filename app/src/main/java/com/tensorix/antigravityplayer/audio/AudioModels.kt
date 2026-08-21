package com.tensorix.antigravityplayer.audio

import android.media.AudioDeviceInfo

enum class AudioOutputRouteType(val displayName: String) {
    SPEAKER("Built-in Speaker"),
    WIRED_HEADPHONES("Wired Headphones (3.5mm)"),
    WIRED_HEADSET("Wired Headset (3.5mm w/ Mic)"),
    USB_DAC("USB Audio DAC"),
    USB_DEVICE("USB Audio Device"),
    BLUETOOTH_A2DP("Bluetooth Audio (A2DP)"),
    HDMI("HDMI Audio Output"),
    LINE_OUT("Analog / Digital Line Out"),
    BUILT_IN_EARPIECE("Built-in Earpiece"),
    OTHER("Other Audio Device")
}

enum class ListeningMode(val displayName: String, val badge: String, val description: String) {
    REFERENCE("Reference Mode", "STUDIO", "Pure neutral uncolored studio mastering baseline (0.0 dB gain, 0.0 dBFS true-peak)"),
    AUDIOPHILE("Audiophile Mode", "64-BIT DSP", "64-bit double precision biquads, triode tube warmth, Meier crossfeed, and +3.5 dB dynamic headroom"),
    DYNAMIC("Dynamic Mode", "PUNCH & AIR", "Fletcher-Munson dynamic loudness, +3.5 dB clarity enhancement, and +2.0 dB high-end air presence")
}

enum class BitPerfectState(val label: String) {
    ACTIVE_DIRECT("Bit-Perfect (Direct Hardware Passthrough)"),
    BYPASS_DSP("Bit-Perfect (DSP Bypassed, AudioSink Float)"),
    DSP_ACTIVE("Processed (DSP / Parametric EQ Active)"),
    AUDIOFLINGER_MIXED("AudioFlinger Mixer (System Resampled)"),
    UNSUPPORTED("Standard Android Audio Pipeline")
}

enum class AudioOutputApi(val label: String) {
    AAUDIO("AAudio (High Performance)"),
    AUDIOTRACK("Java AudioTrack (Stable)"),
    OPENSL_ES("OpenSL ES (Legacy High Performance)"),
    OFFLOAD("Hardware Offload (Direct)")
}

data class OutputDeviceConfig(
    val api: AudioOutputApi = AudioOutputApi.AAUDIO,
    val sampleRate: Int = 48000,
    val bitDepth: Int = 24,
    val bufferSizeMultiplier: Int = 2, // 1 = small, 2 = normal, 4 = large
    val exclusiveMode: Boolean = false,
    val ditherEnabled: Boolean = true
)

data class UsbDacInfo(
    val deviceName: String,
    val manufacturerName: String?,
    val productName: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val interfaceCount: Int,
    val isAudioClassCompliant: Boolean,
    val supportedSampleRates: List<Int> = emptyList(),
    val supportedBitDepths: List<Int> = emptyList()
)

data class SignalPathStage(
    val stageName: String,
    val title: String,
    val description: String,
    val isBitPerfect: Boolean,
    val badge: String? = null
)

data class AudioRouteCapability(
    val routeType: AudioOutputRouteType,
    val deviceName: String,
    val productName: String? = null,
    val sampleRates: List<Int> = emptyList(),
    val encodings: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
    val isDirectPlaybackCapable: Boolean = false,
    val canBeExclusive: Boolean = false
)

data class AudioOutputState(
    val activeRoute: AudioRouteCapability? = null,
    val availableRoutes: List<AudioRouteCapability> = emptyList(),
    val connectedUsbDacs: List<UsbDacInfo> = emptyList(),
    val currentPlaybackSampleRate: Int = 0,
    val currentPlaybackBitDepth: Int = 0,
    val playbackPath: String = "Unknown",
    val bitPerfectState: BitPerfectState = BitPerfectState.UNSUPPORTED,
    val bitPerfectPossible: Boolean = false,
    val resamplingRequired: Boolean = false,
    val signalPathStages: List<SignalPathStage> = emptyList(),
    val deviceLimitations: List<String> = emptyList(),
    val latencyMs: Int = 0
)

data class AudioQualityState(
    val isLossless: Boolean = false,
    val isHiResSource: Boolean = false,
    val isHiResOutput: Boolean = false,
    val isBitPerfect: Boolean = false
) {
    companion object {
        fun evaluate(
            sourceCodec: String,
            sourceSampleRate: Int,
            sourceBitDepth: Int,
            actualOutputSampleRate: Int,
            actualOutputBitDepth: Int,
            isAudioFlingerMixer: Boolean
        ): AudioQualityState {
            val cleanCodec = sourceCodec.uppercase()
            val isLossless = cleanCodec.contains("FLAC") ||
                    cleanCodec.contains("ALAC") ||
                    cleanCodec.contains("WAV") ||
                    cleanCodec.contains("AIFF") ||
                    cleanCodec.contains("APE") ||
                    cleanCodec.contains("DSF") ||
                    cleanCodec.contains("DFF") ||
                    cleanCodec.contains("DSD") ||
                    cleanCodec.contains("LOSSLESS")

            val isHiResSource = (sourceBitDepth >= 24) || (sourceSampleRate >= 88200)
            val isHiResOutput = (actualOutputSampleRate >= 88200) && (actualOutputBitDepth >= 24)
            val isBitPerfect = (sourceSampleRate == actualOutputSampleRate) &&
                    (sourceBitDepth == actualOutputBitDepth) &&
                    !isAudioFlingerMixer

            return AudioQualityState(
                isLossless = isLossless,
                isHiResSource = isHiResSource,
                isHiResOutput = isHiResOutput,
                isBitPerfect = isBitPerfect
            )
        }
    }
}

data class AudioTrackInfo(
    val title: String = "Unknown Track",
    val artist: String = "Unknown Artist",
    val codec: String = "Unknown",
    val bitrateKbps: Int = 0,
    val bitDepth: Int = 16,
    val sampleRateHz: Int = 44100,
    val channels: Int = 2,
    val isHiResSource: Boolean = (bitDepth >= 24 || sampleRateHz >= 88200),
    val isHiRes: Boolean = (bitDepth >= 24 || sampleRateHz >= 88200),
    val quality: AudioQualityState = AudioQualityState.evaluate(
        sourceCodec = codec,
        sourceSampleRate = sampleRateHz,
        sourceBitDepth = bitDepth,
        actualOutputSampleRate = 48000,
        actualOutputBitDepth = 16,
        isAudioFlingerMixer = true
    )
)

data class AudiophilePlaybackSnapshot(
    val track: AudioTrackInfo = AudioTrackInfo(),
    val output: AudioOutputState = AudioOutputState(),
    val quality: AudioQualityState = AudioQualityState()
)

internal fun AudioDeviceInfo.toRouteType(): AudioOutputRouteType = when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioOutputRouteType.WIRED_HEADPHONES
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioOutputRouteType.WIRED_HEADSET
    AudioDeviceInfo.TYPE_AUX_LINE -> AudioOutputRouteType.WIRED_HEADPHONES // Treat AUX as wired headphones
    AudioDeviceInfo.TYPE_USB_DEVICE -> AudioOutputRouteType.USB_DEVICE
    AudioDeviceInfo.TYPE_USB_HEADSET -> AudioOutputRouteType.USB_DAC
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputRouteType.USB_DEVICE
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioOutputRouteType.BLUETOOTH_A2DP
    AudioDeviceInfo.TYPE_HDMI -> AudioOutputRouteType.HDMI
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutputRouteType.BUILT_IN_EARPIECE
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputRouteType.SPEAKER
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioOutputRouteType.LINE_OUT
    else -> AudioOutputRouteType.OTHER
}
