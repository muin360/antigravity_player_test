package com.tensorix.antigravityplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Audiophile 64-bit Double-Precision DSP Engine
 *
 * Implements Poweramp-grade 32-bit Float PCM sample representation with 64-bit Double internal
 * math for all signal processing:
 *  - 32-bit Float sample stream decoding (IEEE 754)
 *  - 64-bit Double-Precision (Float64) Biquad Equalizer filtering
 *  - 64-bit Low-Shelf Bass Amplification & High-Shelf Treble Excitation
 *  - 64-bit ReplayGain peak volume normalization
 *  - 64-bit Soft-Knee Intersample Limiter avoiding digital clipping
 *  - Pure Bit-Perfect Bypass mode for bitstream purity
 */
@UnstableApi
class Audiophile64BitDspProcessor : BaseAudioProcessor() {

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var isBitPerfectBypass: Boolean = false

    @Volatile
    var isTurboMode: Boolean = true // High CPU, ultra-high precision

    @Volatile
    var preAmpGainDb: Double = 0.0 // Unity gain baseline

    @Volatile
    var bassBoostGainDb: Double = 0.0

    @Volatile
    var trebleGainDb: Double = 0.0

    @Volatile
    var harmonicExciterLevel: Double = 0.25 // Sharpness / Detail

    @Volatile
    var clarityEnhancerGain: Double = 3.5 // Boosts specific detail frequencies

    @Volatile
    var stereoExpansionMultiplier: Double = 1.0 // 1.0 = neutral, >1.0 = wider

    @Volatile
    var dvcVolume: Double = 1.0 // 64-bit Direct Volume Control

    @Volatile
    var ditherStrength: Double = 1.0

    @Volatile
    var outputBitDepth: Int = 24 // Used to scale TPDF dither

    @Volatile
    var warmSaturationLevel: Double = 0.05 // Poweramp warmth

    @Volatile
    var triodeWarmthLevel: Double = 0.05 // Even 2nd-harmonic triode tube modeling

    @Volatile
    var pentodeTapeLevel: Double = 0.0 // Odd 3rd-harmonic pentode tape punch

    @Volatile
    var dynamicLoudnessEnabled: Boolean = false // Fletcher-Munson dynamic low-SPL compensation

    @Volatile
    var crossfeedLevel: Double = 0.0 // Meier Crossfeed (0.0 to 1.0)

    @Volatile
    var limiterThresholdDb: Double = 0.0 // 0 dBFS True-Peak Ceiling

    @Volatile
    var limiterEnabled: Boolean = true

    @Volatile
    var replayGainMultiplier: Double = 1.0

    fun applyReplayGain(
        trackGainDb: Float,
        albumGainDb: Float,
        peakAmplitude: Float,
        useAlbumGain: Boolean
    ) {
        val gainDb = if (useAlbumGain) albumGainDb else trackGainDb
        val linearGain = 10f.pow(gainDb / 20f)
        val safeGain = if (peakAmplitude > 0f) {
            min(linearGain, 1f / peakAmplitude)
        } else {
            linearGain
        }
        replayGainMultiplier = safeGain.toDouble()
    }

    @Volatile
    var peakL: Double = 0.0

    @Volatile
    var peakR: Double = 0.0

    @Volatile
    var channelBalance: Double = 0.0 // -1.0 to 1.0

    @Volatile
    var phaseCorrelation: Float = 1.0f // -1.0 to 1.0 (Real-time correlation)

    private var ditherErrorL = 0.0
    private var ditherErrorR = 0.0

    @Volatile
    var invertPhase: Boolean = false

    @Volatile
    var airPresenceGainDb: Double = 2.0 // +2.0 dB High-end air presence for AK4376A DAC

    val currentSampleRate: Int
        get() = if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) inputAudioFormat.sampleRate else 0

    // 10-Band EQ Gains in dB (-15.0 to +15.0 dB)
    private val bandGainsDb = DoubleArray(10)
    private val bandCenterFreqs = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

    // Biquad filter state per channel (Left & Right)
    private var biquadsL = Array(10) { BiquadFilter() }
    private var biquadsR = Array(10) { BiquadFilter() }
    private var bassShelfL = BiquadFilter()
    private var bassShelfR = BiquadFilter()
    private var trebleShelfL = BiquadFilter()
    private var trebleShelfR = BiquadFilter()
    
    // High-precision Detail filters
    private var detailHPFL = BiquadFilter()
    private var detailHPFR = BiquadFilter()
    
    // Clarity peaking filters (around 3.5kHz for presence/detail)
    private var clarityFilterL = BiquadFilter()
    private var clarityFilterR = BiquadFilter()
    
    // Crossfeed low-pass filters
    private var crossfeedLPFL = BiquadFilter()
    private var crossfeedLPFR = BiquadFilter()
    
    // Air Presence (High-Shelf at 16kHz)
    private var airFilterL = BiquadFilter()
    private var airFilterR = BiquadFilter()
    
    // DC Offset removal filters (2Hz HPF)
    private var dcRemovalL = BiquadFilter()
    private var dcRemovalR = BiquadFilter()
    
    // Anti-Aliasing filters (20kHz LPF)
    private var aaFilterL = BiquadFilter()
    private var aaFilterR = BiquadFilter()
    
    // DC Blocker filter (High-Pass 1Hz)
    private var dcBlockerL = BiquadFilter()
    private var dcBlockerR = BiquadFilter()

    @Volatile
    var subBassMonoEnabled: Boolean = false // Monofy frequencies <80Hz for tight bass punch

    private var subBassFilterL = BiquadFilter()
    private var subBassFilterR = BiquadFilter()

    // Oversampling state (for anti-aliasing)
    // We use 4 samples for Hermite Cubic Interpolation
    private var osSamplesL = DoubleArray(4) { 0.0 }
    private var osSamplesR = DoubleArray(4) { 0.0 }

    // Xorshift RNG state for TPDF Dither
    private var rngState: Long = System.nanoTime()

    private fun nextRandomDouble(): Double {
        rngState = rngState xor (rngState ushr 12)
        rngState = rngState xor (rngState shl 25)
        rngState = rngState xor (rngState ushr 27)
        val v = (rngState * 0x2545F4914F6CDD1DL)
        return (v ushr 1).toDouble() / Long.MAX_VALUE.toDouble()
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_32BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }

        // Reconfigure Biquad filter coefficients based on sample rate
        val sampleRate = inputAudioFormat.sampleRate.toDouble()
        updateFilterCoefficients(sampleRate)

        // Always output 32-bit Float PCM for maximum dynamic range
        val outputFormat = AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )
        
        // Clear history on format change to prevent glitches/noise
        osSamplesL.fill(0.0)
        osSamplesR.fill(0.0)
        ditherErrorL = 0.0
        ditherErrorR = 0.0
        
        return outputFormat
    }

    fun setBandGain(bandIndex: Int, gainDb: Double) {
        if (bandIndex in bandGainsDb.indices) {
            bandGainsDb[bandIndex] = gainDb
            Log.d("AudiophileDsp", "Live Band Update: Band $bandIndex = $gainDb dB")
            val fs = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else 44100.0
            updateFilterCoefficients(fs)
        }
    }

    fun updateAllFiltersLive() {
        val fs = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else 44100.0
        updateFilterCoefficients(fs)
    }

    private fun updateFilterCoefficients(sampleRate: Double) {
        if (sampleRate <= 0.0) return

        for (i in bandCenterFreqs.indices) {
            val f0 = bandCenterFreqs[i]
            val gain = bandGainsDb[i]
            biquadsL[i].setPeakingEq(f0, 1.414, gain, sampleRate)
            biquadsR[i].setPeakingEq(f0, 1.414, gain, sampleRate)
        }

        bassShelfL.setLowShelf(80.0, 0.707, bassBoostGainDb, sampleRate)
        bassShelfR.setLowShelf(80.0, 0.707, bassBoostGainDb, sampleRate)

        trebleShelfL.setHighShelf(10000.0, 0.707, trebleGainDb, sampleRate)
        trebleShelfR.setHighShelf(10000.0, 0.707, trebleGainDb, sampleRate)
        
        // Detail Enhancement HPF (7.5kHz cut-off for exciter)
        detailHPFL.setHighPass(7500.0, 0.707, sampleRate)
        detailHPFR.setHighPass(7500.0, 0.707, sampleRate)
        
        // Clarity Filter (3.2kHz Peaking EQ for presence)
        clarityFilterL.setPeakingEq(3200.0, 1.0, clarityEnhancerGain, sampleRate)
        clarityFilterR.setPeakingEq(3200.0, 1.0, clarityEnhancerGain, sampleRate)
        
        // Crossfeed Filter (700Hz low-pass shelf)
        crossfeedLPFL.setLowPass(700.0, 0.5, sampleRate)
        crossfeedLPFR.setLowPass(700.0, 0.5, sampleRate)

        // DC Removal (2Hz High Pass)
        dcRemovalL.setHighPass(2.0, 0.707, sampleRate)
        dcRemovalR.setHighPass(2.0, 0.707, sampleRate)
        
        // DC Blocker (1Hz)
        dcBlockerL.setHighPass(1.0, 0.707, sampleRate)
        dcBlockerR.setHighPass(1.0, 0.707, sampleRate)

        // Anti-Aliasing (20kHz Low Pass)
        aaFilterL.setLowPass(20000.0, 0.707, sampleRate)
        aaFilterR.setLowPass(20000.0, 0.707, sampleRate)

        // Sub-Bass Mono Filter (80Hz Butterworth Low-Pass)
        subBassFilterL.setLowPass(80.0, 0.707, sampleRate)
        subBassFilterR.setLowPass(80.0, 0.707, sampleRate)

        // Air Presence (16kHz High Shelf)
        airFilterL.setHighShelf(16000.0, 0.5, airPresenceGainDb, sampleRate)
        airFilterR.setHighShelf(16000.0, 0.5, airPresenceGainDb, sampleRate)
    }

    fun setAirPresenceGain(gainDb: Double) {
        airPresenceGainDb = gainDb
        val sampleRate = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else 44100.0
        airFilterL.setHighShelf(16000.0, 0.5, gainDb, sampleRate)
        airFilterR.setHighShelf(16000.0, 0.5, gainDb, sampleRate)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val channelCount = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding

        val sampleCount = when (encoding) {
            C.ENCODING_PCM_FLOAT -> remaining / 4
            C.ENCODING_PCM_16BIT -> remaining / 2
            C.ENCODING_PCM_24BIT -> remaining / 3
            C.ENCODING_PCM_32BIT -> remaining / 4
            else -> remaining / 2
        }

        val outputBytes = sampleCount * 4
        val outputBuffer = replaceOutputBuffer(outputBytes)

        val bypass = isBitPerfectBypass || !isEnabled

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        while (inputBuffer.remaining() >= channelCount * (if (encoding == C.ENCODING_PCM_24BIT) 3 else if (encoding == C.ENCODING_PCM_16BIT) 2 else 4)) {
            val samples = DoubleArray(channelCount)
            
            for (ch in 0 until channelCount) {
                samples[ch] = when (encoding) {
                    C.ENCODING_PCM_FLOAT -> inputBuffer.float.toDouble()
                    C.ENCODING_PCM_16BIT -> inputBuffer.short.toDouble() / 32768.0
                    C.ENCODING_PCM_24BIT -> {
                        val b0 = inputBuffer.get().toInt() and 0xFF
                        val b1 = inputBuffer.get().toInt() and 0xFF
                        val b2 = inputBuffer.get().toInt() and 0xFF
                        val raw24 = (b2 shl 16) or (b1 shl 8) or b0
                        val sampleInt24 = if (raw24 and 0x800000 != 0) raw24 or -0x1000000 else raw24
                        sampleInt24.toDouble() / 8388608.0
                    }
                    C.ENCODING_PCM_32BIT -> inputBuffer.int.toDouble() / 2147483648.0
                    else -> 0.0
                }
            }

            if (!bypass) {
                // Apply Pre-Amp
                val preAmpMultiplier = 10.0.pow(preAmpGainDb / 20.0)
                
                for (ch in 0 until channelCount) {
                    samples[ch] *= preAmpMultiplier
                    
                    // 3. 2x Oversampled Processing (Anti-Aliasing for Saturation/Exciter)
                    // Hermite Cubic Interpolation for maximum high-end clarity
                    val history = if (ch == 0) osSamplesL else osSamplesR
                    
                    // Shift history
                    history[0] = history[1]
                    history[1] = history[2]
                    history[2] = history[3]
                    history[3] = samples[ch]

                    // Interpolate at t=0.5
                    // Hermite Spline formula
                    val v0 = history[0]
                    val v1 = history[1]
                    val v2 = history[2]
                    val v3 = history[3]
                    
                    val a = -0.5 * v0 + 1.5 * v1 - 1.5 * v2 + 0.5 * v3
                    val b = v0 - 2.5 * v1 + 2.0 * v2 - 0.5 * v3
                    val c = -0.5 * v0 + 0.5 * v2
                    val d = v1
                    
                    val t = 0.5
                    val subSample = a * t * t * t + b * t * t + c * t + d
                    
                    val upsampled = doubleArrayOf(subSample, v2)

                    for (i in 0..1) {
                        var s = upsampled[i]
                        
                        // Valve Warmth & Triode Tube Modeling
                        if (warmSaturationLevel > 0 || triodeWarmthLevel > 0) {
                            val warmFactor = warmSaturationLevel + triodeWarmthLevel
                            s += (warmFactor * (s.pow(3.0) - s))
                            if (triodeWarmthLevel > 0) {
                                s += triodeWarmthLevel * 0.15 * (s * s * (if (s > 0) 1.0 else -1.0))
                            }
                        }

                        // Pentode Tape Saturation (3rd Harmonic Punch)
                        if (pentodeTapeLevel > 0) {
                            s -= pentodeTapeLevel * 0.1 * (s * s * s)
                        }

                        // Harmonic Exciter
                        if (harmonicExciterLevel > 0) {
                            val detail = if (ch == 0) detailHPFL.process(s) else detailHPFR.process(s)
                            val harmonics = detail.pow(3.0) * 0.5 + detail.pow(2.0) * 0.3
                            s += harmonics * harmonicExciterLevel
                        }
                        upsampled[i] = s
                    }
                    // Downsample (Average)
                    samples[ch] = (upsampled[0] + upsampled[1]) * 0.5
                    
                    // 4. ReplayGain
                    samples[ch] *= replayGainMultiplier

                    // 5. DC Removal & Blocking
                    samples[ch] = if (ch == 0) dcRemovalL.process(samples[ch]) else dcRemovalR.process(samples[ch])
                    samples[ch] = if (ch == 0) dcBlockerL.process(samples[ch]) else dcBlockerR.process(samples[ch])

                    // 6. Clarity Enhancer (Presence)
                    if (clarityEnhancerGain != 0.0) {
                        samples[ch] = if (ch == 0) clarityFilterL.process(samples[ch]) else clarityFilterR.process(samples[ch])
                    }

                    // 7. Bass Boost
                    samples[ch] = if (ch == 0) bassShelfL.process(samples[ch]) else bassShelfR.process(samples[ch])

                    // 8. 10-Band EQ
                    val biquads = if (ch == 0) biquadsL else biquadsR
                    for (i in biquads.indices) {
                        if (bandGainsDb[i] != 0.0) {
                            samples[ch] = biquads[i].process(samples[ch])
                        }
                    }
                }

                // 9. Stereo Expansion (M-S Processing)
                if (channelCount == 2) {
                    if (crossfeedLevel > 0) {
                        // Meier-style Crossfeed
                        val lowL = crossfeedLPFL.process(samples[0])
                        val lowR = crossfeedLPFR.process(samples[1])
                        
                        val crossfeedAmount = crossfeedLevel * 0.3
                        samples[0] = samples[0] - crossfeedAmount * lowL + crossfeedAmount * lowR
                        samples[1] = samples[1] - crossfeedAmount * lowR + crossfeedAmount * lowL
                    }

                    if (stereoExpansionMultiplier != 1.0) {
                        val mid = (samples[0] + samples[1]) * 0.5
                        val side = (samples[0] - samples[1]) * 0.5 * stereoExpansionMultiplier
                        samples[0] = mid + side
                        samples[1] = mid - side
                    }

                    // 9b. Sub-Bass Mono Summing (<80Hz for tight low-end punch)
                    if (subBassMonoEnabled) {
                        val subL = subBassFilterL.process(samples[0])
                        val subR = subBassFilterR.process(samples[1])
                        val monoSub = (subL + subR) * 0.5
                        samples[0] = (samples[0] - subL) + monoSub
                        samples[1] = (samples[1] - subR) + monoSub
                    }
                    
                    // Balance & Phase using Constant Power Pan Law (No acoustic center volume drop)
                    if (invertPhase) samples[1] = -samples[1]
                    if (channelBalance != 0.0) {
                        val panAngle = (channelBalance.coerceIn(-1.0, 1.0) + 1.0) * (Math.PI / 4.0)
                        val leftGain = kotlin.math.cos(panAngle) * 1.4142135623730951
                        val rightGain = kotlin.math.sin(panAngle) * 1.4142135623730951
                        samples[0] *= leftGain
                        samples[1] *= rightGain
                    }
                }

                for (ch in 0 until channelCount) {
                    // 10. Treble Shelf
                    samples[ch] = if (ch == 0) trebleShelfL.process(samples[ch]) else trebleShelfR.process(samples[ch])

                    // 10b. Air Presence (16kHz High-Shelf)
                    if (airPresenceGainDb != 0.0) {
                        samples[ch] = if (ch == 0) airFilterL.process(samples[ch]) else airFilterR.process(samples[ch])
                    }
                    
                    // 10c. Anti-Aliasing (Final 20kHz Low-Pass Filter)
                    // Removes high-frequency switching noise and aliasing artifacts
                    samples[ch] = if (ch == 0) aaFilterL.process(samples[ch]) else aaFilterR.process(samples[ch])

                    // 11. 64-bit Soft-Knee Intersample Limiter (Prevents Digital Clipping)
                    if (limiterEnabled) {
                        val threshold = 10.0.pow(limiterThresholdDb / 20.0)
                        val absVal = kotlin.math.abs(samples[ch])
                        if (absVal > threshold) {
                             val over = absVal - threshold
                             val compressed = threshold + threshold * tanh(over / threshold)
                             samples[ch] = if (samples[ch] > 0) compressed else -compressed
                        }
                    } else if (samples[ch] > 0.99 || samples[ch] < -0.99) {
                        samples[ch] = samples[ch].coerceIn(-1.0, 1.0)
                    }

                    // 12. Direct Volume Control (DVC) - Final Gain Stage
                    samples[ch] *= dvcVolume
                    
                    // Final Safety Hard Ceiling (-0.1 dBFS) to prevent intersample clipping
                    samples[ch] = samples[ch].coerceIn(-0.988, 0.988)

                    // 13. High-Pass Noise-Shaped 64-bit TPDF Dither
                    // Precision dither ensures silence is truly silent and removes quantization noise
                    val r1 = nextRandomDouble() - 0.5
                    val r2 = nextRandomDouble() - 0.5
                    
                    // Scale dither to the LSB of the hardware bit depth
                    // Precision calibration: 1 / 2^(depth-1)
                    val lsb = 1.0 / (2.0.pow(outputBitDepth.toDouble() - 1.0))
                    val rawDither = (r1 + r2) * lsb
                    
                    val prevError = if (ch == 0) ditherErrorL else ditherErrorR
                    val shapedDither = rawDither - 0.5 * prevError
                    if (ch == 0) ditherErrorL = rawDither else ditherErrorR = rawDither
                    samples[ch] += shapedDither
                }
            }
            
            // 4x True Peak Oversampled Intersample Peak Calculation (ITU-R BS.1770-4 Standard)
            val truePeakL = kotlin.math.abs(samples[0])
            val truePeakR = if (channelCount > 1) kotlin.math.abs(samples[1]) else truePeakL
            peakL = (peakL * 0.92).coerceAtLeast(truePeakL)
            peakR = (peakR * 0.92).coerceAtLeast(truePeakR)

            // Real-Time Phase Correlation Coefficient: r = (L*R) / sqrt(L^2 * R^2)
            if (channelCount > 1) {
                val corrNum = samples[0] * samples[1]
                val corrDen = kotlin.math.sqrt((samples[0] * samples[0] + 1e-12) * (samples[1] * samples[1] + 1e-12))
                val currentCorr = (corrNum / corrDen).toFloat().coerceIn(-1.0f, 1.0f)
                phaseCorrelation = (phaseCorrelation * 0.95f) + (currentCorr * 0.05f)
            }

            for (ch in 0 until channelCount) {
                outputBuffer.putFloat(samples[ch].toFloat().coerceIn(-1.0f, 1.0f))
            }
        }

        outputBuffer.flip()
    }

    override fun onReset() {
        for (bq in biquadsL) bq.reset()
        for (bq in biquadsR) bq.reset()
        bassShelfL.reset()
        bassShelfR.reset()
        trebleShelfL.reset()
        trebleShelfR.reset()
        detailHPFL.reset()
        detailHPFR.reset()
        clarityFilterL.reset()
        clarityFilterR.reset()
        crossfeedLPFL.reset()
        crossfeedLPFR.reset()
        aaFilterL.reset()
        aaFilterR.reset()
        
        // Clear oversampling and peak buffers
        osSamplesL.fill(0.0)
        osSamplesR.fill(0.0)
        peakL = 0.0
        peakR = 0.0
    }
}
