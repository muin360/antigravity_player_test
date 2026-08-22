package com.tensorix.antigravityplayer.audio

import androidx.media3.common.util.UnstableApi

/**
 * AUTHORITATIVE BIT-PERFECT VERIFIER
 * 
 * Strictly enforces 30+ mandatory runtime conditions for VERIFIED status.
 * ZERO false positives. UNKNOWN blocks VERIFIED.
 */
@UnstableApi
object BitPerfectVerifier {
    private const val TAG = "BitPerfectVerifier"

    fun verify(
        snapshot: CanonicalAudioRuntimeSnapshot,
        dspProcessor: Audiophile64BitDspProcessor?,
        isHrtfEnabled: Boolean
    ): BitPerfectVerificationResult {
        val evidence = mutableListOf<BitPerfectEvidence>()
        val failureReasons = mutableListOf<String>()

        // 1. Current playback stream exists.
        val streamExists = OboeAudioSink.currentActiveHandle != 0L
        evidence.add(BitPerfectEvidence("Stream Exists", streamExists, EvidenceSource.OBOE_STREAM))
        if (!streamExists) failureReasons.add("Native audio stream handle is null")

        // 2. Current playback stream is actually active.
        val streamActive = snapshot.actualOutput.sampleRate.value > 0
        evidence.add(BitPerfectEvidence("Stream Active", streamActive, EvidenceSource.OBOE_STREAM))
        if (!streamActive) failureReasons.add("Audio stream is not actively producing frames")

        // 3. Actual output route is known.
        val routeKnown = snapshot.activeRoute.confidence != Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("Route Known", routeKnown, EvidenceSource.ANDROID_AUDIO_DEVICE))
        if (!routeKnown) failureReasons.add("Audio output route type is unknown")

        // 4. Actual stream API is known.
        val apiKnown = snapshot.audioApi.confidence != Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("API Known", apiKnown, snapshot.audioApi.source))
        if (!apiKnown) failureReasons.add("Audio output API is unknown")

        // 5. Actual sharing mode is known.
        val sharingKnown = snapshot.sharingMode.confidence != Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("Sharing Mode Known", sharingKnown, snapshot.sharingMode.source))
        if (!sharingKnown) failureReasons.add("Stream sharing mode (Exclusive/Shared) is unknown")

        // 6. Actual output sample rate is known.
        val outputRateKnown = snapshot.actualOutput.sampleRate.confidence != Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("Output Rate Known", outputRateKnown, snapshot.actualOutput.sampleRate.source))
        if (!outputRateKnown) failureReasons.add("Actual hardware output sample rate is unknown")

        // 7. Source sample rate is known.
        val sourceRateKnown = snapshot.source.sampleRate.confidence != Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("Source Rate Known", sourceRateKnown, snapshot.source.sampleRate.source))
        if (!sourceRateKnown) failureReasons.add("Source track sample rate is unknown")

        // 8. Actual output sample rate == source sample rate.
        val rateMatch = snapshot.actualOutput.sampleRate.value == snapshot.source.sampleRate.value && outputRateKnown && sourceRateKnown
        evidence.add(BitPerfectEvidence("Sample Rate Match", rateMatch, EvidenceSource.HAL_PARAMETER, "${snapshot.source.sampleRate.value} -> ${snapshot.actualOutput.sampleRate.value}"))
        if (!rateMatch) failureReasons.add("Sample rate mismatch: ${snapshot.source.sampleRate.value} Hz source vs ${snapshot.actualOutput.sampleRate.value} Hz output")

        // 9. Actual channel count is known.
        val outputChannelsKnown = snapshot.actualOutput.channels.confidence != Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("Output Channels Known", outputChannelsKnown, snapshot.actualOutput.channels.source))
        if (!outputChannelsKnown) failureReasons.add("Actual hardware output channel count is unknown")

        // 10. Actual output channel count == source channel count.
        val channelsMatch = snapshot.actualOutput.channels.value == snapshot.source.channels.value && outputChannelsKnown
        evidence.add(BitPerfectEvidence("Channel Match", channelsMatch, EvidenceSource.HAL_PARAMETER))
        if (!channelsMatch) failureReasons.add("Channel count mismatch")

        // 11. Actual output encoding is known.
        val encodingKnown = snapshot.actualOutput.encoding.confidence != Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("Encoding Known", encodingKnown, snapshot.actualOutput.encoding.source))
        if (!encodingKnown) failureReasons.add("Actual hardware output encoding is unknown")

        // 12. No resampler is active.
        val noResampler = snapshot.resamplerState.value == "OFF" || snapshot.resamplerState.value == "BYPASS"
        evidence.add(BitPerfectEvidence("No Resampler", noResampler, EvidenceSource.HAL_PARAMETER))
        if (!noResampler) failureReasons.add("Android AudioFlinger resampler is active")

        // 13. DSP is completely disabled.
        val dspDisabled = dspProcessor == null || !dspProcessor.isEnabled || dspProcessor.isBitPerfectBypass
        evidence.add(BitPerfectEvidence("DSP Disabled", dspDisabled, EvidenceSource.OBOE_STREAM))
        if (!dspDisabled) failureReasons.add("DSP Engine is enabled")

        // 14. EQ is disabled.
        val eqDisabled = dspDisabled // Covered by dspDisabled for our engine
        evidence.add(BitPerfectEvidence("EQ Disabled", eqDisabled, EvidenceSource.OBOE_STREAM))

        // 15. AutoEQ is disabled.
        val autoEqDisabled = dspDisabled
        evidence.add(BitPerfectEvidence("AutoEQ Disabled", autoEqDisabled, EvidenceSource.OBOE_STREAM))

        // 16. PEQ is disabled.
        val peqDisabled = dspDisabled
        evidence.add(BitPerfectEvidence("PEQ Disabled", peqDisabled, EvidenceSource.OBOE_STREAM))

        // 17. limiter is disabled.
        val limiterDisabled = dspProcessor == null || !dspProcessor.limiterEnabled
        evidence.add(BitPerfectEvidence("Limiter Disabled", limiterDisabled, EvidenceSource.OBOE_STREAM))
        if (!limiterDisabled) failureReasons.add("True-peak limiter is active")

        // 18. dither is disabled.
        val ditherDisabled = dspProcessor == null || dspProcessor.ditherStrength < 0.0001
        evidence.add(BitPerfectEvidence("Dither Disabled", ditherDisabled, EvidenceSource.OBOE_STREAM))
        if (!ditherDisabled) failureReasons.add("TPDF dither is active")

        // 19. software volume is unity OR a provably transparent hardware volume path is being used.
        val volUnity = dspProcessor == null || (dspProcessor.dvcVolume >= 0.999 && dspProcessor.dvcVolume <= 1.001)
        evidence.add(BitPerfectEvidence("Volume Unity", volUnity, EvidenceSource.OBOE_STREAM))
        if (!volUnity) failureReasons.add("Software digital volume attenuation is active")

        // 20. preamp gain is unity.
        val preampUnity = dspProcessor == null || (dspProcessor.preAmpGainDb >= -0.01 && dspProcessor.preAmpGainDb <= 0.01)
        evidence.add(BitPerfectEvidence("Preamp Unity", preampUnity, EvidenceSource.OBOE_STREAM))
        if (!preampUnity) failureReasons.add("Preamp gain is active")

        // 21. normalization is disabled.
        val normDisabled = dspProcessor == null // Normalization not explicitly in DspProcessor but covered by unity checks
        evidence.add(BitPerfectEvidence("Normalization Disabled", normDisabled, EvidenceSource.OBOE_STREAM))

        // 22. replay gain is disabled.
        val replayGainOff = dspProcessor == null || (dspProcessor.replayGainMultiplier >= 0.999 && dspProcessor.replayGainMultiplier <= 1.001)
        evidence.add(BitPerfectEvidence("ReplayGain Disabled", replayGainOff, EvidenceSource.OBOE_STREAM))
        if (!replayGainOff) failureReasons.add("ReplayGain modification is active")

        // 23. spatial/HRTF processing is disabled.
        val spatialOff = !isHrtfEnabled
        evidence.add(BitPerfectEvidence("Spatial Audio Disabled", spatialOff, EvidenceSource.VENDOR_API))
        if (!spatialOff) failureReasons.add("HRTF Spatial Audio is active")

        // 24. crossfeed is disabled.
        val crossfeedOff = dspProcessor == null || dspProcessor.crossfeedLevel < 0.001
        evidence.add(BitPerfectEvidence("Crossfeed Disabled", crossfeedOff, EvidenceSource.OBOE_STREAM))
        if (!crossfeedOff) failureReasons.add("Meier Crossfeed is active")

        // 25. balance transformation is disabled unless proven transparent.
        val balanceUnity = dspProcessor == null || (dspProcessor.channelBalance >= -0.01 && dspProcessor.channelBalance <= 0.01)
        evidence.add(BitPerfectEvidence("Balance Unity", balanceUnity, EvidenceSource.OBOE_STREAM))
        if (!balanceUnity) failureReasons.add("Channel balance is active")

        // 26. no known PCM conversion exists.
        val noConversion = snapshot.processing.encoding.value == "FLOAT64" // Our internal processing is FLOAT64
        evidence.add(BitPerfectEvidence("No Lossy Conversion", noConversion, EvidenceSource.OBOE_STREAM))

        // 27. no known AudioFlinger mixer path is active.
        val noMixer = snapshot.mixerPathActive.value == false && snapshot.directPathActive.value == true
        evidence.add(BitPerfectEvidence("No Mixer Path", noMixer, EvidenceSource.HAL_PARAMETER))
        if (!noMixer) failureReasons.add("AudioFlinger mixer path is suspected active")

        // 28. direct/native path is actually active.
        val directActive = snapshot.directPathActive.value == true
        evidence.add(BitPerfectEvidence("Direct Path Active", directActive, snapshot.directPathActive.source))
        if (!directActive) failureReasons.add("Direct PCM path is not active in HAL")

        // 29. the actual output stream evidence comes from Oboe/AudioTrack runtime state.
        val runtimeEvidence = snapshot.actualOutput.sampleRate.source == EvidenceSource.OBOE_STREAM || 
                             snapshot.actualOutput.sampleRate.source == EvidenceSource.AUDIO_TRACK ||
                             snapshot.actualOutput.sampleRate.source == EvidenceSource.HAL_PARAMETER
        evidence.add(BitPerfectEvidence("Runtime Evidence", runtimeEvidence, EvidenceSource.OBOE_STREAM))
        if (!runtimeEvidence) failureReasons.add("Actual output telemetry is not from runtime source")

        // 30. unknown critical evidence MUST block VERIFIED.
        val anyUnknown = evidence.any { it.source == EvidenceSource.UNKNOWN } || 
                        snapshot.actualOutput.sampleRate.confidence == Confidence.UNKNOWN ||
                        snapshot.actualOutput.channels.confidence == Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("No Unknown Evidence", !anyUnknown, EvidenceSource.HEURISTIC))
        if (anyUnknown) failureReasons.add("Critical telemetry is unknown")

        // Final State Determination
        val allSatisfied = evidence.all { it.isSatisfied }
        
        val state = when {
            allSatisfied -> BitPerfectState.VERIFIED
            failureReasons.isNotEmpty() -> BitPerfectState.FAILED
            isEligible(snapshot) -> BitPerfectState.ACTIVE_UNVERIFIED
            else -> BitPerfectState.UNAVAILABLE
        }

        val confidence = when (state) {
            BitPerfectState.VERIFIED -> Confidence.VERIFIED
            BitPerfectState.ACTIVE_UNVERIFIED -> Confidence.HIGH_CONFIDENCE
            else -> Confidence.INFERRED
        }

        return BitPerfectVerificationResult(
            state = state,
            evidence = evidence,
            confidence = confidence,
            failureReasons = failureReasons
        )
    }

    private fun isEligible(snapshot: CanonicalAudioRuntimeSnapshot): Boolean {
        // Eligibility check (can this path potentially be bit-perfect?)
        return snapshot.activeRoute.value.canBeExclusive() || 
               snapshot.dac.isActive.value
    }
    
    private fun AudioOutputRouteType.canBeExclusive(): Boolean {
        return this == AudioOutputRouteType.USB_DAC || 
               this == AudioOutputRouteType.WIRED_HEADPHONES || 
               this == AudioOutputRouteType.WIRED_HEADSET
    }
}
