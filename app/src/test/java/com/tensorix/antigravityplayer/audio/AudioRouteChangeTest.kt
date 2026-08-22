package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@UnstableApi
class AudioRouteChangeTest {

    @Before
    fun setUp() {
        AudioEngine.resetForTesting()
    }

    @Test
    fun `test AudioEngine reconfigureForRouteChange updates state safely`() {
        val context = mock<Context>()
        val route = AudioRouteCapability(
            routeType = AudioOutputRouteType.WIRED_HEADSET,
            deviceName = "Wired Headset (3.5mm)",
            productName = "Headset",
            sampleRates = listOf(44100, 48000, 96000, 192000),
            encodings = listOf(16, 24, 32),
            channelCounts = listOf(2),
            isDirectPlaybackCapable = true,
            canBeExclusive = true
        )

        AudioEngine.reconfigureForRouteChange(context, route)
        assertNull(AudioEngine.snapshot.value)
        assertEquals(BitPerfectState.DISABLED, AudioEngine.getBitPerfectState())
    }

    @Test
    fun `test Speaker route does not trigger exclusive DAC mode`() {
        val context = mock<Context>()
        val route = AudioRouteCapability(
            routeType = AudioOutputRouteType.SPEAKER,
            deviceName = "Built-in Speaker",
            productName = null,
            sampleRates = listOf(48000),
            encodings = listOf(16),
            channelCounts = listOf(2),
            isDirectPlaybackCapable = false,
            canBeExclusive = false
        )

        AudioEngine.reconfigureForRouteChange(context, route)
        assertNull(AudioEngine.snapshot.value)
    }
}
