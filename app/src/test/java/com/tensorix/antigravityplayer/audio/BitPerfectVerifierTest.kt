package com.tensorix.antigravityplayer.audio

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class BitPerfectVerifierTest {

    @Test
    fun `BIT-PERFECT POSITIVE TEST - All conditions satisfied`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp()
        
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.VERIFIED, result.state)
        assertEquals(0, result.failureReasons.size)
    }

    @Test
    fun `NEGATIVE TEST 1 - DSP ON`() {
        val snapshot = createBaseSnapshot().copy(dspState = AudioEvidence("ACTIVE", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED))
        val dsp = createBaseDsp(isEnabled = true, isBitPerfectBypass = false)
        
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assert(result.failureReasons.contains("DSP Engine is enabled"))
    }

    @Test
    fun `NEGATIVE TEST 2 - EQ ON`() {
        val dsp = createBaseDsp(isEnabled = true, isBitPerfectBypass = false)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, false)
        assertEquals(BitPerfectState.FAILED, result.state)
    }

    @Test
    fun `NEGATIVE TEST 6 - software volume 0_9`() {
        val dsp = createBaseDsp(volume = 0.9)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, false)
        assertEquals(BitPerfectState.FAILED, result.state)
        assert(result.failureReasons.contains("Software digital volume attenuation is active"))
    }

    @Test
    fun `NEGATIVE TEST 7 - preamp != 0`() {
        val dsp = createBaseDsp(preamp = 1.5)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, false)
        assertEquals(BitPerfectState.FAILED, result.state)
        assert(result.failureReasons.contains("Preamp gain is active"))
    }

    @Test
    fun `NEGATIVE TEST 9 - rate mismatch`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(48000, 24, 2, "PCM")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), false)
        assertEquals(BitPerfectState.FAILED, result.state)
        assert(result.failureReasons.any { it.contains("Sample rate mismatch") })
    }

    @Test
    fun `NEGATIVE TEST 10 - route UNKNOWN`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.SPEAKER, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.UNKNOWN)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), false)
        assertEquals(BitPerfectState.FAILED, result.state)
        assert(result.failureReasons.contains("Audio output route type is unknown"))
    }

    @Test
    fun `NEGATIVE TEST 11 - stream UNKNOWN`() {
        // Technically handled by handle == 0 or other unknown fields
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(0, 0, 0, "Unknown", Confidence.UNKNOWN)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), false)
        assertEquals(BitPerfectState.FAILED, result.state)
        assert(result.failureReasons.any { it.contains("unknown") })
    }

    @Test
    fun `NEGATIVE TEST 13 - exclusive only`() {
        // If other conditions fail, exclusive alone isn't enough
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(48000, 24, 2, "PCM") // Mismatch
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), false)
        assertEquals(BitPerfectState.FAILED, result.state)
    }

    private fun createBaseSnapshot(): CanonicalAudioRuntimeSnapshot {
        // Mock Oboe active handle
        // Note: BitPerfectVerifier.verify checks OboeAudioSink.currentActiveHandle
        // Since it's a static volatile, we can't easily mock it without reflection or setting it
        // For unit test, we'll assume it's set or use reflection if needed.
        // Let's set it via reflection for the test.
        val field = OboeAudioSink.Companion::class.java.getDeclaredField("currentActiveHandle")
        field.isAccessible = true
        field.set(OboeAudioSink.Companion, 12345L)

        return CanonicalAudioRuntimeSnapshot(
            source = createFormat(44100, 16, 2, "FLAC"),
            decoder = createFormat(44100, 32, 2, "PCM_FLOAT"),
            processing = createFormat(44100, 64, 2, "FLOAT64"),
            requestedOutput = createFormat(44100, 16, 2, "PCM"),
            actualOutput = createFormat(44100, 16, 2, "PCM"),
            activeRoute = AudioEvidence(AudioOutputRouteType.USB_DAC, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.VERIFIED),
            audioApi = AudioEvidence(AudioOutputApi.AAUDIO, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            sharingMode = AudioEvidence("EXCLUSIVE", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            performanceMode = AudioEvidence("LOW_LATENCY", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            directPathActive = AudioEvidence(true, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            mixerPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            resamplerState = AudioEvidence("OFF", EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            dspState = AudioEvidence("OFF", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            dac = DacRuntimeState(
                modelName = AudioEvidence("Test DAC", EvidenceSource.HEURISTIC, Confidence.HIGH_CONFIDENCE),
                vendor = AudioEvidence("Test Vendor", EvidenceSource.HEURISTIC, Confidence.HIGH_CONFIDENCE),
                isActive = AudioEvidence(true, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
                maxSampleRate = AudioEvidence(192000, EvidenceSource.HEURISTIC, Confidence.HIGH_CONFIDENCE),
                maxBitDepth = AudioEvidence(32, EvidenceSource.HEURISTIC, Confidence.HIGH_CONFIDENCE),
                confidence = Confidence.HIGH_CONFIDENCE
            ),
            bitPerfect = BitPerfectRuntimeState(BitPerfectState.UNKNOWN, true, null, Confidence.UNKNOWN),
            confidence = Confidence.UNKNOWN,
            limitations = emptyList()
        )
    }

    private fun createFormat(rate: Int, depth: Int, channels: Int, enc: String, conf: Confidence = Confidence.VERIFIED): AudioFormatSnapshot {
        return AudioFormatSnapshot(
            sampleRate = AudioEvidence(rate, EvidenceSource.SOURCE_METADATA, conf),
            bitDepth = AudioEvidence(depth, EvidenceSource.SOURCE_METADATA, conf),
            channels = AudioEvidence(channels, EvidenceSource.SOURCE_METADATA, conf),
            encoding = AudioEvidence(enc, EvidenceSource.SOURCE_METADATA, conf)
        )
    }

    private fun createBaseDsp(
        isEnabled: Boolean = false,
        isBitPerfectBypass: Boolean = true,
        volume: Double = 1.0,
        preamp: Double = 0.0,
        dither: Double = 0.0
    ): Audiophile64BitDspProcessor {
        val dsp = org.mockito.kotlin.mock<Audiophile64BitDspProcessor>()
        org.mockito.kotlin.whenever(dsp.isEnabled).thenReturn(isEnabled)
        org.mockito.kotlin.whenever(dsp.isBitPerfectBypass).thenReturn(isBitPerfectBypass)
        org.mockito.kotlin.whenever(dsp.dvcVolume).thenReturn(volume)
        org.mockito.kotlin.whenever(dsp.preAmpGainDb).thenReturn(preamp)
        org.mockito.kotlin.whenever(dsp.ditherStrength).thenReturn(dither)
        org.mockito.kotlin.whenever(dsp.limiterEnabled).thenReturn(false)
        org.mockito.kotlin.whenever(dsp.crossfeedLevel).thenReturn(0.0)
        org.mockito.kotlin.whenever(dsp.replayGainMultiplier).thenReturn(1.0)
        org.mockito.kotlin.whenever(dsp.channelBalance).thenReturn(0.0)
        return dsp
    }
}
