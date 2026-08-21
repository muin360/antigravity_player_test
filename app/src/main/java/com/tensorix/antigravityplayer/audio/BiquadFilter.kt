package com.tensorix.antigravityplayer.audio

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 64-bit Double Precision Direct Form II Transposed Biquad Filter
 * Numerical stability for audiophile parametric equalization without rounding noise.
 */
class BiquadFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        // Audiophile denormal protection against CPU Kryo stalls at ultra-low amplitudes
        if (z1 != 0.0 && kotlin.math.abs(z1) < 1.0e-15) z1 = 0.0
        if (z2 != 0.0 && kotlin.math.abs(z2) < 1.0e-15) z2 = 0.0
        return y
    }

    fun setPeakingEq(f0: Double, q: Double, gainDb: Double, fs: Double) {
        if (gainDb == 0.0) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW0) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha / a) / a0
    }

    fun setLowShelf(f0: Double, q: Double, gainDb: Double, fs: Double) {
        if (gainDb == 0.0) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * f0 / fs
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / q - 1.0) + 2.0)
        val cosW0 = cos(w0)

        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha
        b0 = (a * ((a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha)) / a0
        b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
        b2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha)) / a0
        a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
        a2 = ((a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha) / a0
    }

    fun setHighShelf(f0: Double, q: Double, gainDb: Double, fs: Double) {
        if (gainDb == 0.0) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * f0 / fs
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / q - 1.0) + 2.0)
        val cosW0 = cos(w0)

        val a0 = (a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha
        b0 = (a * ((a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha)) / a0
        b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
        b2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha)) / a0
        a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
        a2 = ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha) / a0
    }

    fun setHighPass(f0: Double, q: Double, fs: Double) {
        val w0 = 2.0 * Math.PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val a0 = 1.0 + alpha
        b0 = (1.0 + cosW0) / 2.0 / a0
        b1 = -(1.0 + cosW0) / a0
        b2 = (1.0 + cosW0) / 2.0 / a0
        a1 = -2.0 * cosW0 / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setLowPass(f0: Double, q: Double, fs: Double) {
        val w0 = 2.0 * Math.PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val a0 = 1.0 + alpha
        b0 = (1.0 - cosW0) / 2.0 / a0
        b1 = (1.0 - cosW0) / a0
        b2 = (1.0 - cosW0) / 2.0 / a0
        a1 = -2.0 * cosW0 / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setAllPass(f0: Double, q: Double, fs: Double) {
        val w0 = 2.0 * Math.PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val a0 = 1.0 + alpha
        b0 = (1.0 - alpha) / a0
        b1 = -2.0 * cosW0 / a0
        b2 = (1.0 + alpha) / a0
        a1 = -2.0 * cosW0 / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setNotch(f0: Double, q: Double, fs: Double) {
        val w0 = 2.0 * Math.PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val a0 = 1.0 + alpha
        b0 = 1.0 / a0
        b1 = -2.0 * cosW0 / a0
        b2 = 1.0 / a0
        a1 = -2.0 * cosW0 / a0
        a2 = (1.0 - alpha) / a0
    }
}
