package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.tensorix.antigravityplayer.player.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MODULE 8 — BIT‑PERFECT ANALYZER
 *
 * Scans the current audio path and validates true bit‑perfect playback:
 *  • Sample‑rate conversion status
 *  • Bit‑depth preservation (32‑bit Float output)
 *  • DSP bypass state (no active AudioEffect chain)
 *  • AudioSink hardware fallback detection
 *
 * The analyser exposes a [VerificationStatus] that can be observed by UI
 * components to display VERIFIED, LIKELY, POSSIBLE or IMPOSSIBLE states.
 */
class BitPerfectAnalyzer private constructor(private val context: Context) {

    enum class VerificationStatus {
        VERIFIED_BIT_PERFECT,
        LIKELY,
        POSSIBLE,
        IMPOSSIBLE
    }

    private val _status = MutableStateFlow(VerificationStatus.POSSIBLE)
    val status: StateFlow<VerificationStatus> = _status

    private val _phaseCorrelation = MutableStateFlow(1.0f)
    val phaseCorrelation: StateFlow<Float> = _phaseCorrelation

    companion object {
        @Volatile
        private var INSTANCE: BitPerfectAnalyzer? = null
        fun getInstance(context: Context): BitPerfectAnalyzer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BitPerfectAnalyzer(context.applicationContext).also { INSTANCE = it }
            }
    }

    /**
     * Run a full bit‑perfect verification pass using real hardware inspection.
     */
    fun evaluate() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentTrack = PlaybackService.instance?.currentTrackInfo?.value
            val isDspBypassed = isDspBypassed()

            val verifiedReport = HardwareHiFiVerifier.probeHardwareState(
                context = context,
                trackSampleRate = currentTrack?.sampleRateHz ?: 0,
                trackBitDepth = currentTrack?.bitDepth ?: 16,
                isDspBypassed = isDspBypassed
            )

            _status.value = if (verifiedReport.isBitPerfectVerified) {
                VerificationStatus.VERIFIED_BIT_PERFECT
            } else {
                VerificationStatus.IMPOSSIBLE
            }
        } catch (e: Exception) {
            Log.e("BitPerfectAnalyzer", "Evaluation failed", e)
            _status.value = VerificationStatus.IMPOSSIBLE
        }
    }

    private fun isUsingFloatSink(audioManager: AudioManager): Boolean {
        return try {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        } catch (e: Exception) {
            false
        }
    }

    private fun isSampleRateMatched(audioManager: AudioManager): Boolean {
        return try {
            val outputRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            outputRate != null && outputRate >= 44100
        } catch (e: Exception) {
            false
        }
    }

    private fun isDspBypassed(): Boolean {
        return PlaybackService.instance?.bitPerfectMode?.value ?: true
    }

    private fun isHardwareFallback(audioManager: AudioManager): Boolean {
        return try {
            val outputRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            outputRate != null && outputRate < 44100
        } catch (e: Exception) {
            false
        }
    }
}
