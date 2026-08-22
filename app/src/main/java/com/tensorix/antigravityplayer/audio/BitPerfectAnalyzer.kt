package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.tensorix.antigravityplayer.player.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import androidx.media3.common.util.UnstableApi

/**
 * MODULE 8 — BIT‑PERFECT ANALYZER
 *
 * Scans the current audio path and validates true bit‑perfect playback.
 * Delegates to BitPerfectVerifier for authoritative verification.
 */
@UnstableApi
class BitPerfectAnalyzer private constructor(private val context: Context) {

    enum class VerificationStatus {
        VERIFIED_BIT_PERFECT,
        ACTIVE_UNVERIFIED,
        POSSIBLE,
        IMPOSSIBLE
    }

    private val _status = MutableStateFlow(VerificationStatus.POSSIBLE)
    val status: StateFlow<VerificationStatus> = _status

    private val _verificationResult = MutableStateFlow<BitPerfectVerificationResult?>(null)
    val verificationResult: StateFlow<BitPerfectVerificationResult?> = _verificationResult

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
            val service = PlaybackService.instance ?: return
            val snapshot = AudioEngineController.snapshot.value ?: return
            val dsp = service.dspProcessor
            val hrtfEnabled = service.equalizerEngine?.hrtfSpatialEnabled?.value ?: false

            val result = BitPerfectVerifier.verify(snapshot, dsp, hrtfEnabled)
            _verificationResult.value = result

            _status.value = when (result.state) {
                BitPerfectState.VERIFIED -> VerificationStatus.VERIFIED_BIT_PERFECT
                BitPerfectState.ACTIVE_UNVERIFIED -> VerificationStatus.ACTIVE_UNVERIFIED
                BitPerfectState.ELIGIBLE, BitPerfectState.REQUESTED -> VerificationStatus.POSSIBLE
                else -> VerificationStatus.IMPOSSIBLE
            }
        } catch (e: Exception) {
            Log.e("BitPerfectAnalyzer", "Evaluation failed", e)
            _status.value = VerificationStatus.IMPOSSIBLE
        }
    }
}
