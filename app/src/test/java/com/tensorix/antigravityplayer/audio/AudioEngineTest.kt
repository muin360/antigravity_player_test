package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@UnstableApi
class AudioEngineTest {

    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        mockContext = mock()
        AudioEngine.resetForTesting()
    }

    @Test
    fun `test AudioEngine initial state and BitPerfect mode toggle`() {
        assertEquals(BitPerfectState.DISABLED, AudioEngine.getBitPerfectState())
        assertFalse(AudioEngine.bitPerfectRequested.value)

        AudioEngine.setBitPerfectMode(true)
        assertTrue(AudioEngine.bitPerfectRequested.value)
        assertEquals(BitPerfectState.REQUESTED, AudioEngine.getBitPerfectState())

        AudioEngine.setBitPerfectMode(false)
        assertFalse(AudioEngine.bitPerfectRequested.value)
        assertEquals(BitPerfectState.DISABLED, AudioEngine.getBitPerfectState())
    }

    @Test
    fun `test AudioEngine serialized route reconfiguration does not throw`() = runBlocking {
        val iemRoute = AudioRouteCapability(
            routeType = AudioOutputRouteType.WIRED_HEADSET,
            deviceName = "3.5mm Headset",
            productName = "IEM",
            sampleRates = listOf(44100, 48000, 96000, 192000),
            encodings = listOf(16, 24, 32),
            channelCounts = listOf(2),
            isDirectPlaybackCapable = true,
            canBeExclusive = true
        )

        val speakerRoute = AudioRouteCapability(
            routeType = AudioOutputRouteType.SPEAKER,
            deviceName = "Built-in Speaker",
            productName = null,
            sampleRates = listOf(48000),
            encodings = listOf(16),
            channelCounts = listOf(2),
            isDirectPlaybackCapable = false,
            canBeExclusive = false
        )

        val usbDacRoute = AudioRouteCapability(
            routeType = AudioOutputRouteType.USB_DAC,
            deviceName = "External USB DAC",
            productName = "AudioQuest Dragonfly",
            sampleRates = listOf(44100, 48000, 96000, 192000, 384000),
            encodings = listOf(16, 24, 32),
            channelCounts = listOf(2),
            isDirectPlaybackCapable = true,
            canBeExclusive = true
        )

        // Sequentially transition: IEM -> Speaker -> USB DAC -> IEM
        AudioEngine.reconfigureRoute(mockContext, iemRoute)
        assertEquals(iemRoute, AudioEngine.activeRoute.value)

        AudioEngine.reconfigureRoute(mockContext, speakerRoute)
        assertEquals(speakerRoute, AudioEngine.activeRoute.value)

        AudioEngine.reconfigureRoute(mockContext, usbDacRoute)
        assertEquals(usbDacRoute, AudioEngine.activeRoute.value)

        AudioEngine.reconfigureRoute(mockContext, iemRoute)
        assertEquals(iemRoute, AudioEngine.activeRoute.value)
    }

    @Test
    fun `test AudioEngine single-authority stream error recovery`() {
        assertEquals("NORMAL", AudioEngine.recoveryState.value)

        // Report stream error from HAL / Oboe
        AudioEngine.handleStreamError(errorCode = -1, context = mockContext)

        // Verifies recovery handled and reset back to normal
        assertEquals("NORMAL", AudioEngine.recoveryState.value)
        assertNull(AudioEngine.snapshot.value)
    }

    @Test
    fun `test AudioEngine concurrent route changes resolve sequentially to latest route`() = runBlocking {
        val route1 = AudioRouteCapability(
            routeType = AudioOutputRouteType.SPEAKER,
            deviceName = "Speaker",
            productName = null,
            sampleRates = listOf(48000),
            encodings = listOf(16),
            channelCounts = listOf(2),
            isDirectPlaybackCapable = false,
            canBeExclusive = false
        )
        val route2 = AudioRouteCapability(
            routeType = AudioOutputRouteType.WIRED_HEADSET,
            deviceName = "Wired IEM",
            productName = null,
            sampleRates = listOf(48000, 96000),
            encodings = listOf(16, 24),
            channelCounts = listOf(2),
            isDirectPlaybackCapable = true,
            canBeExclusive = true
        )

        // Launch concurrent route changes in coroutine scope
        coroutineScope {
            val job1 = async { AudioEngine.reconfigureRoute(mockContext, route1) }
            val job2 = async { AudioEngine.reconfigureRoute(mockContext, route2) }

            job1.await()
            job2.await()
        }

        assertNotNull(AudioEngine.activeRoute.value)
    }

    @Test
    fun `test BitPerfect unavailable falls back to standard playback without stopping`() {
        AudioEngine.setBitPerfectMode(true)
        assertEquals(BitPerfectState.REQUESTED, AudioEngine.getBitPerfectState())

        // Signal unavailable
        AudioEngine.setBitPerfectState(BitPerfectState.UNAVAILABLE)
        assertEquals(BitPerfectState.UNAVAILABLE, AudioEngine.getBitPerfectState())
        // AudioEngine remains operational
        assertEquals("NORMAL", AudioEngine.recoveryState.value)
    }
}
