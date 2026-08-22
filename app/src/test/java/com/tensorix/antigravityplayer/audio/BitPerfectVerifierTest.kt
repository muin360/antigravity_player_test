package com.tensorix.antigravityplayer.audio

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@UnstableApi
class BitPerfectVerifierTest {

    @Before
    fun setUp() {
        OboeAudioSink.currentActiveHandle = 12345L
    }

    @Test
    fun `POSITIVE TEST - All 35 conditions satisfied yields VERIFIED`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp()
        
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.VERIFIED, result.state)
        assertEquals(0, result.failureReasons.size)
    }

    @Test
    fun `NEGATIVE TEST 1 - DSP Engine active`() {
        val snapshot = createBaseSnapshot().copy(dspState = AudioEvidence("ACTIVE", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED))
        val dsp = createBaseDsp(isEnabled = true, isBitPerfectBypass = false)
        
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("DSP Engine is active") })
    }

    @Test
    fun `NEGATIVE TEST 2 - Software volume non-unity`() {
        val dsp = createBaseDsp(volume = 0.8)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("Software digital volume attenuation is active") })
    }

    @Test
    fun `NEGATIVE TEST 3 - Preamp gain non-unity`() {
        val dsp = createBaseDsp(preamp = 2.5)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("Preamp gain is active") })
    }

    @Test
    fun `NEGATIVE TEST 4 - ReplayGain non-unity`() {
        val dsp = createBaseDsp()
        whenever(dsp.replayGainMultiplier).thenReturn(0.7)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("ReplayGain modification is active") })
    }

    @Test
    fun `NEGATIVE TEST 5 - Dither active`() {
        val dsp = createBaseDsp(dither = 1.0)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("TPDF dither modification is active") })
    }

    @Test
    fun `NEGATIVE TEST 6 - Limiter enabled`() {
        val dsp = createBaseDsp()
        whenever(dsp.limiterEnabled).thenReturn(true)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("True-peak limiter is active") })
    }

    @Test
    fun `NEGATIVE TEST 7 - Crossfeed active`() {
        val dsp = createBaseDsp()
        whenever(dsp.crossfeedLevel).thenReturn(0.5)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("Meier crossfeed is active") })
    }

    @Test
    fun `NEGATIVE TEST 8 - Channel balance non-zero`() {
        val dsp = createBaseDsp()
        whenever(dsp.channelBalance).thenReturn(-0.5)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("Channel balance attenuation is active") })
    }

    @Test
    fun `NEGATIVE TEST 9 - HRTF Spatial Audio enabled`() {
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), createBaseDsp(), isHrtfEnabled = true)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("Spatial Audio processing is active") })
    }

    @Test
    fun `NEGATIVE TEST 10 - Sample rate mismatch`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(48000, 24, 2, "PCM")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("Sample rate mismatch") })
    }

    @Test
    fun `NEGATIVE TEST 11 - Channel count mismatch`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(44100, 16, 1, "PCM")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("Channel count mismatch") })
    }

    @Test
    fun `NEGATIVE TEST 12 - Route UNKNOWN`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.OTHER, EvidenceSource.UNKNOWN, Confidence.UNKNOWN)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
    }

    @Test
    fun `NEGATIVE TEST 13 - Route Bluetooth A2DP`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.BLUETOOTH_A2DP, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
    }

    @Test
    fun `NEGATIVE TEST 14 - Route Built-in Speaker`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.SPEAKER, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
    }

    @Test
    fun `NEGATIVE TEST 15 - Resampler active`() {
        val snapshot = createBaseSnapshot().copy(
            resamplerState = AudioEvidence("ACTIVE", EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("Resampler is actively altering audio clock") })
    }

    @Test
    fun `NEGATIVE TEST 16 - Mixer path active`() {
        val snapshot = createBaseSnapshot().copy(
            mixerPathActive = AudioEvidence(true, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            directPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("mixer path is active") })
    }

    @Test
    fun `NEGATIVE TEST 17 - Stream handle is null or closed`() {
        OboeAudioSink.currentActiveHandle = 0L

        val snapshot = createBaseSnapshot().copy(nativeStream = null)
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.FAILED, result.state)
        assertTrue(result.failureReasons.any { it.contains("stream handle is null or closed") })
    }

    @Test
    fun `NEGATIVE TEST 18 - Critical telemetry is UNKNOWN while direct path is active yields ACTIVE_UNVERIFIED`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(0, 0, 0, "Unknown", Confidence.UNKNOWN)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false)
        
        assertEquals(BitPerfectState.ACTIVE_UNVERIFIED, result.state)
        assertTrue(result.failureReasons.any { it.contains("unknown or unverified") })
    }

    private fun createBaseSnapshot(): CanonicalAudioRuntimeSnapshot {
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
            nativeStream = NativeStreamSnapshot(
                handle = 12345L,
                state = "Started",
                isStarted = true,
                sampleRate = 44100,
                channelCount = 2,
                nativeFormat = "Float",
                sharingMode = "EXCLUSIVE",
                performanceMode = "LOW_LATENCY",
                audioApi = "AAudio",
                deviceId = 1,
                framesWritten = 1000L,
                underrunCount = 0,
                bufferSizeInFrames = 192,
                confidence = Confidence.VERIFIED
            ),
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
        val dsp = mock<Audiophile64BitDspProcessor>()
        whenever(dsp.isEnabled).thenReturn(isEnabled)
        whenever(dsp.isBitPerfectBypass).thenReturn(isBitPerfectBypass)
        whenever(dsp.dvcVolume).thenReturn(volume)
        whenever(dsp.preAmpGainDb).thenReturn(preamp)
        whenever(dsp.ditherStrength).thenReturn(dither)
        whenever(dsp.limiterEnabled).thenReturn(false)
        whenever(dsp.crossfeedLevel).thenReturn(0.0)
        whenever(dsp.replayGainMultiplier).thenReturn(1.0)
        whenever(dsp.channelBalance).thenReturn(0.0)
        return dsp
    }
}
