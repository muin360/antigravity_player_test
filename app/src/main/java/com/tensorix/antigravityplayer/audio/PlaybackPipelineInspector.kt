package com.tensorix.antigravityplayer.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MODULE 3 — PLAYBACK PIPELINE INSPECTOR
 *
 * Responsibilities:
 * - Inspects and renders the entire 10-stage audio processing pipeline in real time
 * - Detects sample rate conversion (resampling)
 * - Detects bit depth conversion (e.g. 24-bit PCM to 32-bit Float)
 * - Monitors Parametric EQ, ReplayGain 2.0, DSP filter modifications, and Limiter activity
 */
data class PipelineStageDetail(
    val stageNumber: Int,
    val stageName: String,
    val title: String,
    val description: String,
    val isBypassed: Boolean,
    val isBitPerfect: Boolean,
    val statusBadge: String
)

data class PipelineTransformationFlags(
    val isResamplingActive: Boolean = false,
    val isBitDepthConverted: Boolean = true,
    val isLoudnessProcessed: Boolean = true,
    val isDspModified: Boolean = true,
    val isEqActive: Boolean = true,
    val isLimiterActive: Boolean = true,
    val isReplayGainActive: Boolean = true,
    val transformationSummary: String = "32-bit Float Passthrough ➔ 64-bit Double DSP ➔ Hardware DAC"
)

data class PlaybackPipelineReport(
    val stages: List<PipelineStageDetail> = emptyList(),
    val flags: PipelineTransformationFlags = PipelineTransformationFlags(),
    val overallPathDescription: String = "Track ➔ Decoder ➔ AudioTrack ➔ AudioFlinger ➔ Audio HAL"
)

class PlaybackPipelineInspector(private val context: Context) {

    private val _currentReport = MutableStateFlow(PlaybackPipelineReport())
    val currentReport: StateFlow<PlaybackPipelineReport> = _currentReport.asStateFlow()

    fun inspectPipeline(
        trackInfo: AudioTrackInfo,
        activeRoute: AudioRouteCapability?,
        isDspBypass: Boolean = false,
        isEqEnabled: Boolean = true
    ): PlaybackPipelineReport {
        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(
            context = context,
            trackSampleRate = trackInfo.sampleRateHz,
            trackBitDepth = trackInfo.bitDepth,
            isDspBypassed = isDspBypass
        )

        val codec = trackInfo.codec
        val sampleRate = if (trackInfo.sampleRateHz > 0) trackInfo.sampleRateHz else 44100
        val bitDepth = if (trackInfo.bitDepth > 0) trackInfo.bitDepth else 16
        val sampleRateKhz = "${sampleRate / 1000.0} kHz"
        val routeName = activeRoute?.productName ?: activeRoute?.deviceName ?: "System Audio Output"

        val stages = mutableListOf<PipelineStageDetail>()

        // 1. Source Track
        stages.add(
            PipelineStageDetail(
                stageNumber = 1,
                stageName = "Source Track",
                title = "$codec ($bitDepth-bit / $sampleRateKhz)",
                description = "Audio stream decoded from source",
                isBypassed = false,
                isBitPerfect = true,
                statusBadge = "SOURCE"
            )
        )

        // 2. Decoder
        stages.add(
            PipelineStageDetail(
                stageNumber = 2,
                stageName = "Audio Decoder Engine",
                title = "Lossless Media3 Decoder",
                description = "Unpacks audio frames into native IEEE 754 32-bit Float PCM stream",
                isBypassed = false,
                isBitPerfect = true,
                statusBadge = "32-BIT FLOAT PCM"
            )
        )

        // 3. Audio Engine / Clock
        stages.add(
            PipelineStageDetail(
                stageNumber = 3,
                stageName = "Audio Clock State",
                title = if (verifiedReport.actualOutputSampleRate == sampleRate) "Sample Rate Matched ($sampleRateKhz)" else "Sample Rate Mismatch (${verifiedReport.actualOutputSampleRate / 1000.0} kHz)",
                description = if (verifiedReport.actualOutputSampleRate == sampleRate) "Native clock matched" else "AudioFlinger resamples $sampleRate Hz to ${verifiedReport.actualOutputSampleRate} Hz",
                isBypassed = false,
                isBitPerfect = verifiedReport.actualOutputSampleRate == sampleRate,
                statusBadge = if (verifiedReport.actualOutputSampleRate == sampleRate) "MATCHED" else "RESAMPLED"
            )
        )

        // 4. Parametric EQ
        stages.add(
            PipelineStageDetail(
                stageNumber = 4,
                stageName = "Parametric Equalizer",
                title = if (isDspBypass) "Bit-Perfect Bypass Active" else "10-Band Equalizer",
                description = if (isDspBypass) "Equalizer bypassed" else "Active DSP frequency processing",
                isBypassed = isDspBypass,
                isBitPerfect = isDspBypass,
                statusBadge = if (isDspBypass) "BYPASSED" else "ACTIVE"
            )
        )

        // 5. ReplayGain
        stages.add(
            PipelineStageDetail(
                stageNumber = 5,
                stageName = "ReplayGain Normalizer",
                title = "Volume Normalizer",
                description = if (isDspBypass) "Gain normalization bypassed" else "Gain normalization active",
                isBypassed = isDspBypass,
                isBitPerfect = isDspBypass,
                statusBadge = if (isDspBypass) "BYPASSED" else "NORMALIZED"
            )
        )

        // 6. DSP Engine
        stages.add(
            PipelineStageDetail(
                stageNumber = 6,
                stageName = "DSP Engine",
                title = "Soft-Knee Limiter",
                description = if (isDspBypass) "DSP limiter bypassed" else "Dynamic anti-clipping active",
                isBypassed = isDspBypass,
                isBitPerfect = isDspBypass,
                statusBadge = if (isDspBypass) "BYPASSED" else "ACTIVE"
            )
        )

        // 7. Audio Sink API
        stages.add(
            PipelineStageDetail(
                stageNumber = 7,
                stageName = "Audio Architecture Interface",
                title = verifiedReport.actualAudioSinkType,
                description = "Audio buffer stream transfer",
                isBypassed = false,
                isBitPerfect = true,
                statusBadge = "AUDIO SINK"
            )
        )

        // 8. Audio HAL & Thread
        stages.add(
            PipelineStageDetail(
                stageNumber = 8,
                stageName = "Audio HAL & Thread",
                title = verifiedReport.audioThreadType.displayName,
                description = if (verifiedReport.isDirectOutputSupported) "Direct hardware track active" else "AudioFlinger software mixer thread (${verifiedReport.actualOutputSampleRate} Hz)",
                isBypassed = false,
                isBitPerfect = verifiedReport.isDirectOutputSupported,
                statusBadge = if (verifiedReport.isDirectOutputSupported) "DIRECT PATH" else "SYSTEM MIXER"
            )
        )

        // 9. Hardware DAC
        stages.add(
            PipelineStageDetail(
                stageNumber = 9,
                stageName = "Hardware DAC",
                title = verifiedReport.activeDacName,
                description = "Digital-to-Analog conversion",
                isBypassed = false,
                isBitPerfect = verifiedReport.isBitPerfectVerified,
                statusBadge = if (verifiedReport.isBitPerfectVerified) "BIT-PERFECT" else "PROCESSED"
            )
        )

        // 10. Output Endpoint
        stages.add(
            PipelineStageDetail(
                stageNumber = 10,
                stageName = "Audio Endpoint",
                title = routeName,
                description = "Acoustic transducer output",
                isBypassed = false,
                isBitPerfect = true,
                statusBadge = "OUTPUT READY"
            )
        )

        val flags = PipelineTransformationFlags(
            isResamplingActive = !verifiedReport.isDirectOutputSupported && sampleRate != verifiedReport.actualOutputSampleRate,
            isBitDepthConverted = bitDepth != 32,
            isLoudnessProcessed = !isDspBypass,
            isDspModified = !isDspBypass,
            isEqActive = isEqEnabled && !isDspBypass,
            isLimiterActive = !isDspBypass,
            isReplayGainActive = !isDspBypass,
            transformationSummary = if (verifiedReport.isBitPerfectVerified) "Bit-Exact Direct Passthrough" else "AudioFlinger Mixed Output (${verifiedReport.actualOutputSampleRate} Hz)"
        )

        val report = PlaybackPipelineReport(
            stages = stages,
            flags = flags,
            overallPathDescription = "$codec $bitDepth/$sampleRateKhz ➔ Decoder ➔ ${if (isDspBypass) "Bypass" else "DSP"} ➔ ${verifiedReport.audioThreadType.displayName} ➔ $routeName"
        )

        _currentReport.value = report
        return report
    }
}
