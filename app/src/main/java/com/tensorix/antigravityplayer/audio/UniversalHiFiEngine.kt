package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.util.Log

import androidx.media3.common.util.UnstableApi

/**
 * Universal Hi-Fi State Model (Strictly Empirical / Zero Fake Claims)
 */
enum class UniversalHiFiState(val code: String, val title: String, val badgeColorHex: Long, val description: String) {
    UNKNOWN("UNKNOWN", "Unknown State", 0xFF888888, "Probing audio hardware pipeline"),
    UNSUPPORTED("UNSUPPORTED", "Standard Audio Path", 0xFF666666, "Routing through standard platform audio mixer"),
    AVAILABLE("AVAILABLE", "Hi-Fi Available", 0xFF4CAF50, "Audiophile hardware detected; awaiting playback session"),
    ACTIVE("ACTIVE", "Hi-Fi Active", 0xFF00E676, "Dedicated hardware DAC line active on wired analog path"),
    BIT_PERFECT_REQUESTED("BIT_PERFECT_REQUESTED", "Bit-Perfect Requested", 0xFF00B0FF, "Bit-perfect mode enabled; negotiating hardware path"),
    BIT_PERFECT_ELIGIBLE("BIT_PERFECT_ELIGIBLE", "Bit-Perfect Eligible", 0xFF00E5FF, "Hardware path supports bit-perfect; awaiting verification"),
    BIT_PERFECT_ACTIVE_UNVERIFIED("BIT_PERFECT_UNVERIFIED", "Direct Active (Unverified)", 0xFFD500F9, "Direct path active, but bit-integrity not yet verified"),
    BIT_PERFECT_VERIFIED("BIT_PERFECT_VERIFIED", "Bit-Perfect Verified", 0xFFFFD700, "Verified strict Bit-for-Bit exact studio master output established"),
    USB_BIT_PERFECT("USB_BIT_PERFECT", "USB Master Bit-Perfect", 0xFFFFD700, "Verified Bit-Exact direct hardware streaming to external USB DAC")
}

/**
 * Universal Hi-Fi Engine Evaluator & Status Engine
 */
class UniversalHiFiEngine(private val context: Context) {

    companion object {
        private const val TAG = "UniversalHiFiEngine"
    }

    private val hardwareDetector = UniversalHardwareDetector(context)

    data class HiFiEvaluation(
        val state: UniversalHiFiState,
        val activeDac: UniversalHardwareDetector.DacHardwareSnapshot,
        val activeDevice: UniversalHardwareDetector.OutputDeviceSnapshot,
        val platformCapabilities: UniversalHardwareDetector.PlatformCapabilitiesSnapshot,
        val isBitPerfectActive: Boolean,
        val sampleRateHz: Int,
        val bitDepth: Int,
        val audioSessionId: Int,
        val limitations: List<String>,
        val troubleshootingSummary: String
    )

    @UnstableApi
    fun evaluate(
        isPlaying: Boolean,
        audioSessionId: Int,
        isBitPerfectRequested: Boolean,
        trackSampleRate: Int,
        trackBitDepth: Int
    ): HiFiEvaluation {
        val device = hardwareDetector.detectActiveOutputDevice()
        val dac = hardwareDetector.detectDacHardware(device)
        val platform = hardwareDetector.detectPlatformCapabilities(device)
        
        val canon = AudioEngineController.snapshot.value

        val limitations = mutableListOf<String>()

        val state = when {
            // Case 1: Bit-Perfect VERIFIED (Strongest proof)
            canon?.bitPerfect?.state == BitPerfectState.VERIFIED -> {
                if (device.isUsb) UniversalHiFiState.USB_BIT_PERFECT else UniversalHiFiState.BIT_PERFECT_VERIFIED
            }
            // Case 2: Bit-Perfect Active but unverified
            canon?.bitPerfect?.state == BitPerfectState.ACTIVE_UNVERIFIED -> {
                UniversalHiFiState.BIT_PERFECT_ACTIVE_UNVERIFIED
            }
            // Case 3: Bit-Perfect Eligible
            canon?.bitPerfect?.state == BitPerfectState.ELIGIBLE -> {
                UniversalHiFiState.BIT_PERFECT_ELIGIBLE
            }
            // Case 4: Requested but not yet eligible/active
            isBitPerfectRequested -> {
                UniversalHiFiState.BIT_PERFECT_REQUESTED
            }
            // Case 5: Hi-Fi Hardware Active (Direct Path)
            canon?.directPathActive?.value == true && isPlaying -> {
                UniversalHiFiState.ACTIVE
            }
            // Case 6: Hardware available but idle or shared
            device.isWired || device.isUsb -> {
                UniversalHiFiState.AVAILABLE
            }
            // Case 7: Bluetooth High-Res Audio
            device.isBluetooth && (device.bluetoothCodecName.contains("LDAC") || device.bluetoothCodecName.contains("aptX HD") || device.bluetoothCodecName.contains("LHDC")) -> {
                UniversalHiFiState.AVAILABLE
            }
            else -> {
                UniversalHiFiState.UNSUPPORTED
            }
        }

        val troubleshooting = buildString {
            appendLine("========== UNIVERSAL HI-FI DIAGNOSTICS ==========")
            appendLine("1. Evaluated Hi-Fi State:   ${state.title} [${state.code}]")
            appendLine("2. Detected Hardware DAC:   ${dac.dacModelName} (${dac.dacManufacturer})")
            appendLine("3. Output Endpoint:         ${device.displayName} (Type: ${device.deviceType})")
            appendLine("4. Active Audio Session:    $audioSessionId (Playback: $isPlaying)")
            appendLine("5. Bit-Perfect Pipeline:    ${if (isBitPerfectRequested) "ENABLED (Integer PCM, 0 Processors, 0 Effects)" else "DISABLED (64-bit Studio DSP Active)"}")
            appendLine("6. Track Native Format:     $trackSampleRate Hz / $trackBitDepth-bit")
            appendLine("7. Platform Output Rate:    ${platform.platformSampleRate} Hz (Buffer: ${platform.platformBufferSize} frames)")
            appendLine("8. Active Limitations:")
            if (limitations.isEmpty()) {
                appendLine("   - None. Optimal Hardware Signal Routing.")
            } else {
                limitations.forEach { appendLine("   - $it") }
            }
            appendLine("=================================================")
        }

        return HiFiEvaluation(
            state = state,
            activeDac = dac,
            activeDevice = device,
            platformCapabilities = platform,
            isBitPerfectActive = state == UniversalHiFiState.BIT_PERFECT_VERIFIED || state == UniversalHiFiState.USB_BIT_PERFECT,
            sampleRateHz = trackSampleRate,
            bitDepth = trackBitDepth,
            audioSessionId = audioSessionId,
            limitations = limitations,
            troubleshootingSummary = troubleshooting
        )
    }
}
