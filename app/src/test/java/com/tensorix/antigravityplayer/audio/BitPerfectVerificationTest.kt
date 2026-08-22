package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

@UnstableApi
class BitPerfectVerificationTest {

    @Test
    fun `test Bit-Perfect NOT VERIFIED when DSP is ON`() {
        val verified = checkBitPerfect(isDspActive = true)
        assertEquals(false, verified)
    }

    @Test
    fun `test Bit-Perfect NOT VERIFIED when rate mismatch`() {
        val verified = checkBitPerfect(trackRate = 44100, outputRate = 48000)
        assertEquals(false, verified)
    }

    @Test
    fun `test Bit-Perfect NOT VERIFIED when non-unity volume`() {
        val verified = checkBitPerfect(volume = 0.8)
        assertEquals(false, verified)
    }

    @Test
    fun `test Bit-Perfect NOT VERIFIED when dither ON`() {
        val verified = checkBitPerfect(dither = 1.0)
        assertEquals(false, verified)
    }

    @Test
    fun `test Bit-Perfect VERIFIED when all conditions satisfy`() {
        val verified = checkBitPerfect()
        assertEquals(true, verified)
    }

    private fun checkBitPerfect(
        isDspActive: Boolean = false,
        trackRate: Int = 44100,
        outputRate: Int = 44100,
        isDirectActive: Boolean = true,
        isHardwareBitPerfectVerified: Boolean = true,
        volume: Double = 1.0,
        dither: Double = 0.0,
        isSpatialOff: Boolean = true
    ): Boolean {
        val isMatchSR = trackRate == outputRate
        val isVolUnity = volume >= 0.999 && volume <= 1.001
        val isDitherOff = dither < 0.001
        
        return !isDspActive && isMatchSR && isDirectActive && isHardwareBitPerfectVerified && isVolUnity && isDitherOff && isSpatialOff
    }
}
