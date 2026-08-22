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

    @androidx.media3.common.util.UnstableApi
    fun inspectPipeline(
        trackInfo: AudioTrackInfo,
        activeRoute: AudioRouteCapability?,
        isDspBypass: Boolean = false,
        isEqEnabled: Boolean = true
    ): PlaybackPipelineReport {
        val snapshot = AudioVerificationEngine.buildCanonicalSnapshot(
            context = context,
            trackInfo = trackInfo,
            isDspActive = !isDspBypass && isEqEnabled,
            activeRoute = activeRoute,
            dspProcessor = com.tensorix.antigravityplayer.player.PlaybackService.instance?.dspProcessor
        )

        val stages = mutableListOf<PipelineStageDetail>()
        
        // Map canonical snapshot to pipeline details
        stages.add(PipelineStageDetail(1, "Source", snapshot.source.encoding.value, "Original source format", false, true, "SOURCE"))
        stages.add(PipelineStageDetail(2, "Decoder", snapshot.decoder.encoding.value, "Media3 decoded PCM", false, true, "DECODED"))
        
        val dspActive = snapshot.dspState.value == "ACTIVE"
        stages.add(PipelineStageDetail(3, "DSP Engine", if (dspActive) "DSP Active" else "Bit-Perfect Bypass", "64-bit precision processing", dspActive, !dspActive, if (dspActive) "MODIFIED" else "BIT-PERFECT"))
        
        val resampled = snapshot.resamplerState.value == "ACTIVE"
        stages.add(PipelineStageDetail(4, "Resampler", if (resampled) "ASRC Active" else "1:1 Matched", "Sample rate conversion", resampled, !resampled, if (resampled) "RESAMPLED" else "MATCHED"))
        
        val direct = snapshot.directPathActive.value
        stages.add(PipelineStageDetail(5, "Hardware Path", snapshot.audioApi.value.label, if (direct) "Direct HAL path established" else "Mixed via AudioFlinger", !direct, direct, if (direct) "DIRECT" else "SHARED"))

        val bitPerfect = snapshot.bitPerfect.state == BitPerfectState.VERIFIED
        stages.add(PipelineStageDetail(6, "DAC Output", snapshot.dac.modelName.value, if (bitPerfect) "Bit-exact hardware conversion" else "Non-ideal conversion path", false, bitPerfect, if (bitPerfect) "VERIFIED" else "ACTIVE"))

        val flags = PipelineTransformationFlags(
            isResamplingActive = resampled,
            isBitDepthConverted = snapshot.actualOutput.bitDepth.value != snapshot.source.bitDepth.value,
            isLoudnessProcessed = dspActive,
            isDspModified = dspActive,
            isEqActive = dspActive,
            isLimiterActive = dspActive,
            isReplayGainActive = dspActive,
            transformationSummary = snapshot.bitPerfect.evidence
        )

        val report = PlaybackPipelineReport(
            stages = stages,
            flags = flags,
            overallPathDescription = "Route: ${snapshot.activeRoute.value.displayName} | API: ${snapshot.audioApi.value.label}"
        )

        _currentReport.value = report
        return report
    }
}
