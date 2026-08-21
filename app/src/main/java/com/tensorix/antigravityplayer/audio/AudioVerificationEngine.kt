package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi

@UnstableApi
object AudioVerificationEngine {

    fun buildCanonicalSnapshot(
        context: Context,
        trackInfo: AudioTrackInfo,
        isDspActive: Boolean,
        activeRoute: AudioRouteCapability?,
        dvcVolume: Double = 1.0,
        ditherStrength: Double = 0.0
    ): CanonicalAudioRuntimeSnapshot {
        val hardwareReport = HardwareHiFiVerifier.probeHardwareState(context, trackInfo.sampleRateHz, trackInfo.bitDepth, !isDspActive)
        val nativeInfo = OboeAudioSink.currentStreamInfo
        
        // 1. Source Format
        val source = AudioFormatSnapshot(
            sampleRate = AudioEvidence(trackInfo.sampleRateHz, EvidenceSource.SOURCE_METADATA, Confidence.VERIFIED),
            bitDepth = AudioEvidence(trackInfo.bitDepth, EvidenceSource.SOURCE_METADATA, Confidence.VERIFIED),
            channels = AudioEvidence(trackInfo.channels, EvidenceSource.SOURCE_METADATA, Confidence.VERIFIED),
            encoding = AudioEvidence(trackInfo.codec, EvidenceSource.SOURCE_METADATA, Confidence.VERIFIED)
        )

        // 2. Decoder Format
        val decoder = AudioFormatSnapshot(
            sampleRate = AudioEvidence(trackInfo.sampleRateHz, EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE),
            bitDepth = AudioEvidence(32, EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE), // Media3 float decoding
            channels = AudioEvidence(trackInfo.channels, EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE),
            encoding = AudioEvidence("PCM_FLOAT", EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE)
        )

        // 3. Processing Format
        val processing = AudioFormatSnapshot(
            sampleRate = AudioEvidence(trackInfo.sampleRateHz, EvidenceSource.OBOE_STREAM, Confidence.INFERRED),
            bitDepth = AudioEvidence(64, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED), // Our engine uses Float64
            channels = AudioEvidence(trackInfo.channels, EvidenceSource.OBOE_STREAM, Confidence.INFERRED),
            encoding = AudioEvidence("FLOAT64", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED)
        )

        // 4. Requested Output
        val requested = AudioFormatSnapshot(
            sampleRate = AudioEvidence(trackInfo.sampleRateHz, EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED),
            bitDepth = AudioEvidence(trackInfo.bitDepth, EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED),
            channels = AudioEvidence(2, EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED),
            encoding = AudioEvidence("PCM", EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED)
        )

        // 5. Actual Output (The Truth)
        val actual = if (nativeInfo != null) {
            AudioFormatSnapshot(
                sampleRate = AudioEvidence(nativeInfo.sampleRate, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
                bitDepth = AudioEvidence(if (nativeInfo.format.contains("24")) 24 else if (nativeInfo.format.contains("Float")) 32 else 16, EvidenceSource.OBOE_STREAM, Confidence.HIGH_CONFIDENCE),
                channels = AudioEvidence(nativeInfo.channelCount, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
                encoding = AudioEvidence(nativeInfo.format, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED)
            )
        } else {
            AudioFormatSnapshot(
                sampleRate = AudioEvidence(hardwareReport.actualOutputSampleRate, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
                bitDepth = AudioEvidence(24, EvidenceSource.HAL_PARAMETER, Confidence.INFERRED),
                channels = AudioEvidence(2, EvidenceSource.HAL_PARAMETER, Confidence.INFERRED),
                encoding = AudioEvidence(hardwareReport.actualAudioSinkType, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE)
            )
        }

        // 6. Path & Route
        val routeEvidence = AudioEvidence(
            activeRoute?.routeType ?: AudioOutputRouteType.SPEAKER,
            EvidenceSource.ANDROID_AUDIO_DEVICE,
            if (activeRoute != null) Confidence.VERIFIED else Confidence.INFERRED
        )

        val apiEvidence = AudioEvidence(
            if (nativeInfo != null) AudioOutputApi.AAUDIO else AudioOutputApi.AUDIOTRACK,
            if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.AUDIO_TRACK,
            Confidence.VERIFIED
        )

        val directActive = nativeInfo?.sharingMode == "EXCLUSIVE" || hardwareReport.isDirectOutputActive
        val resamplerActive = trackInfo.sampleRateHz != actual.sampleRate.value
        
        // 7. DAC State
        val detector = UniversalHardwareDetector(context)
        val dacInfo = detector.detectActiveOutputDevice()
        val dacProfile = detector.detectDacHardware(dacInfo)
        
        val dacState = DacRuntimeState(
            modelName = AudioEvidence(dacProfile.dacModelName, EvidenceSource.HEURISTIC, dacProfile.confidence),
            vendor = AudioEvidence(dacProfile.dacManufacturer, EvidenceSource.HEURISTIC, dacProfile.confidence),
            isActive = AudioEvidence(hardwareReport.isVendorHiFiActive, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
            maxSampleRate = AudioEvidence(dacProfile.maxSampleRateHz, EvidenceSource.HEURISTIC, dacProfile.confidence),
            maxBitDepth = AudioEvidence(dacProfile.maxBitDepth, EvidenceSource.HEURISTIC, dacProfile.confidence),
            confidence = dacProfile.confidence
        )

        // 8. Bit-Perfect State Machine
        var bitPerfectStatus = BitPerfectState.UNAVAILABLE
        var evidenceStr = ""
        
        val isHardwareEligible = hardwareReport.isBitPerfectEligible
        val isStreamVerified = nativeInfo?.sharingMode == "EXCLUSIVE" && !resamplerActive && !isDspActive
        
        // PCM Modification checks
        val isVolumeUnity = dvcVolume >= 0.999 && dvcVolume <= 1.001
        val isDitherOff = ditherStrength < 0.001
        
        if (isStreamVerified && hardwareReport.isBitPerfectVerified && isVolumeUnity && isDitherOff) {
            bitPerfectStatus = BitPerfectState.VERIFIED
            evidenceStr = "Verified Exclusive Direct Path + Clock Match + Unity Gain"
        } else if (directActive) {
            bitPerfectStatus = if (isDspActive) BitPerfectState.ELIGIBLE else BitPerfectState.ACTIVE_UNVERIFIED
            evidenceStr = when {
                isDspActive -> "DSP active; eligibility maintained"
                !isVolumeUnity -> "Non-unity software volume active"
                !isDitherOff -> "TPDF Dither active"
                else -> "Direct path active; awaiting param verification"
            }
        }

        return CanonicalAudioRuntimeSnapshot(
            source = source,
            decoder = decoder,
            processing = processing,
            requestedOutput = requested,
            actualOutput = actual,
            activeRoute = routeEvidence,
            audioApi = apiEvidence,
            sharingMode = AudioEvidence(nativeInfo?.sharingMode ?: "SHARED", if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED),
            performanceMode = AudioEvidence(nativeInfo?.performanceMode ?: "LOW_LATENCY", if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
            directPathActive = AudioEvidence(directActive, if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
            mixerPathActive = AudioEvidence(!directActive, EvidenceSource.HEURISTIC, Confidence.INFERRED),
            resamplerState = AudioEvidence(if (resamplerActive) "ACTIVE" else "OFF", EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
            dspState = AudioEvidence(if (isDspActive) "ACTIVE" else "OFF", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            dac = dacState,
            bitPerfect = BitPerfectRuntimeState(bitPerfectStatus, isHardwareEligible, evidenceStr, if (bitPerfectStatus == BitPerfectState.VERIFIED) Confidence.VERIFIED else Confidence.INFERRED),
            confidence = if (bitPerfectStatus == BitPerfectState.VERIFIED) Confidence.VERIFIED else Confidence.HIGH_CONFIDENCE,
            limitations = hardwareReport.limitations
        )
    }
}
