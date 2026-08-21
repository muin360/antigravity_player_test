package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi

@UnstableApi
object AudioVerificationEngine {

    fun buildRuntimeSnapshot(
        context: Context,
        trackInfo: AudioTrackInfo,
        isDspActive: Boolean,
        activeRoute: AudioRouteCapability?,
    ): AudioRuntimeSnapshot {
        
        // 1. Source Format (from metadata)
        val sourceFormat = AudioFormatSnapshot(
            sampleRate = AudioEvidence(trackInfo.sampleRateHz, EvidenceSource.SOURCE_METADATA, Confidence.VERIFIED),
            bitDepth = AudioEvidence(trackInfo.bitDepth, EvidenceSource.SOURCE_METADATA, Confidence.VERIFIED),
            channels = AudioEvidence(trackInfo.channels, EvidenceSource.SOURCE_METADATA, Confidence.VERIFIED),
            encoding = AudioEvidence(trackInfo.codec, EvidenceSource.SOURCE_METADATA, Confidence.VERIFIED)
        )

        // 2. Decoded Format (Media3 typically decodes to 32-bit float or 16-bit PCM)
        // We assume 32-bit float for our high-performance pipeline
        val decodedFormat = AudioFormatSnapshot(
            sampleRate = AudioEvidence(trackInfo.sampleRateHz, EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE),
            bitDepth = AudioEvidence(32, EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE),
            channels = AudioEvidence(trackInfo.channels, EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE),
            encoding = AudioEvidence("PCM_FLOAT", EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE)
        )

        // 3. Processing Format (DSP internal)
        val processingFormat = AudioFormatSnapshot(
            sampleRate = AudioEvidence(trackInfo.sampleRateHz, EvidenceSource.OBOE_STREAM, Confidence.INFERRED),
            bitDepth = AudioEvidence(64, EvidenceSource.OBOE_STREAM, Confidence.INFERRED), // Internal 64-bit double
            channels = AudioEvidence(trackInfo.channels, EvidenceSource.OBOE_STREAM, Confidence.INFERRED),
            encoding = AudioEvidence("FLOAT64", EvidenceSource.OBOE_STREAM, Confidence.INFERRED)
        )

        // 4. Requested Output Format
        val requestedOutputFormat = AudioFormatSnapshot(
            sampleRate = AudioEvidence(trackInfo.sampleRateHz, EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED),
            bitDepth = AudioEvidence(trackInfo.bitDepth, EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED),
            channels = AudioEvidence(trackInfo.channels, EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED),
            encoding = AudioEvidence("PCM", EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED)
        )

        // 5. Actual Output Format (Hardware HAL)
        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(context, trackInfo.sampleRateHz, trackInfo.bitDepth, !isDspActive)
        
        val nativeInfo = OboeAudioSink.currentStreamInfo
        val actualOutputFormat = if (nativeInfo != null) {
            AudioFormatSnapshot(
                sampleRate = AudioEvidence(nativeInfo.sampleRate, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
                bitDepth = AudioEvidence(if (nativeInfo.format.contains("24")) 24 else if (nativeInfo.format.contains("Float")) 32 else 16, EvidenceSource.OBOE_STREAM, Confidence.HIGH_CONFIDENCE),
                channels = AudioEvidence(nativeInfo.channelCount, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
                encoding = AudioEvidence(nativeInfo.format, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED)
            )
        } else {
            AudioFormatSnapshot(
                sampleRate = AudioEvidence(verifiedReport.actualOutputSampleRate, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
                bitDepth = AudioEvidence(24, EvidenceSource.HAL_PARAMETER, Confidence.INFERRED),
                channels = AudioEvidence(2, EvidenceSource.HAL_PARAMETER, Confidence.INFERRED),
                encoding = AudioEvidence(verifiedReport.actualAudioSinkType, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE)
            )
        }

        // 6. Path & Route
        val routeEvidence = AudioEvidence(
            activeRoute?.routeType ?: AudioOutputRouteType.SPEAKER,
            EvidenceSource.ANDROID_AUDIO_DEVICE,
            if (activeRoute != null) Confidence.VERIFIED else Confidence.INFERRED
        )

        val apiEvidence = if (nativeInfo != null) {
            AudioEvidence(AudioOutputApi.AAUDIO, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED)
        } else {
            AudioEvidence(AudioOutputApi.AUDIOTRACK, EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED)
        }

        // 7. Bit-Perfect Determination
        val dspState = if (isDspActive) "ACTIVE" else "OFF"
        val actualRate = actualOutputFormat.sampleRate.value
        val resamplerState = if (trackInfo.sampleRateHz != actualRate) "ACTIVE" else "OFF"
        
        var bitPerfectState = BitPerfectState.UNAVAILABLE
        var evidence = ""

        val isDirectActive = nativeInfo?.sharingMode == "EXCLUSIVE" || verifiedReport.isDirectOutputActive
        
        if (!isDspActive && resamplerState == "OFF" && isDirectActive && verifiedReport.isBitPerfectEligible) {
            bitPerfectState = BitPerfectState.VERIFIED
            evidence = "Verified Exclusive/Direct Path + Match SR + DSP Off"
        } else if (isDirectActive) {
            bitPerfectState = if (isDspActive) BitPerfectState.ELIGIBLE else BitPerfectState.ACTIVE_UNVERIFIED
            evidence = if (isDspActive) "Eligible (Direct supported, but DSP ON)" else "Direct Active (Verification pending)"
        }

        return AudioRuntimeSnapshot(
            sourceFormat = sourceFormat,
            decodedFormat = decodedFormat,
            processingFormat = processingFormat,
            requestedOutputFormat = requestedOutputFormat,
            actualOutputFormat = actualOutputFormat,
            activeRoute = routeEvidence,
            audioApi = apiEvidence,
            sharingMode = AudioEvidence(nativeInfo?.sharingMode ?: "UNKNOWN", if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.UNKNOWN, if (nativeInfo != null) Confidence.VERIFIED else Confidence.UNKNOWN),
            performanceMode = AudioEvidence(nativeInfo?.performanceMode ?: "LOW_LATENCY", if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
            directPlaybackActive = AudioEvidence(isDirectActive, if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
            resamplerState = AudioEvidence(resamplerState, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
            dspState = AudioEvidence(dspState, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            bitPerfectState = bitPerfectState,
            bitPerfectEvidence = evidence
        )
    }
}
