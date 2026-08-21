package com.tensorix.antigravityplayer.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HiFiBadgeState {
    private val _isHiFiActive = MutableStateFlow(false)
    val isHiFiActive: StateFlow<Boolean> = _isHiFiActive.asStateFlow()

    private val _hifiLabel = MutableStateFlow("HI-FI")
    val hifiLabel: StateFlow<String> = _hifiLabel.asStateFlow()

    private val _hifiDetail = MutableStateFlow("")
    val hifiDetail: StateFlow<String> = _hifiDetail.asStateFlow()

    fun updateFromSnapshot(snapshot: AudioRuntimeSnapshot) {
        _isHiFiActive.value = snapshot.bitPerfectState == BitPerfectState.VERIFIED ||
                snapshot.bitPerfectState == BitPerfectState.ACTIVE_UNVERIFIED ||
                snapshot.bitPerfectState == BitPerfectState.ELIGIBLE

        _hifiLabel.value = when (snapshot.bitPerfectState) {
            BitPerfectState.VERIFIED -> "BIT-PERFECT"
            BitPerfectState.ACTIVE_UNVERIFIED -> "DIRECT"
            BitPerfectState.ELIGIBLE -> "HI-RES"
            else -> if (snapshot.actualOutputFormat.sampleRate.value >= 88200) "HD" else "HI-FI"
        }

        _hifiDetail.value = when (snapshot.bitPerfectState) {
            BitPerfectState.VERIFIED -> "Verified Direct Path"
            BitPerfectState.ACTIVE_UNVERIFIED -> "Exclusive Mode"
            BitPerfectState.ELIGIBLE -> "DSP Engine Active"
            else -> "${snapshot.activeRoute.value.displayName}"
        }
    }

    fun updateExclusive(exclusive: Boolean) {
        _isHiFiActive.value = true
        _hifiLabel.value = if (exclusive) "HI-FI" else "HD"
        _hifiDetail.value = if (exclusive) "Direct DAC" else "Oboe Mixed"
    }

    fun updateOboeMode(exclusive: Boolean) {
        _isHiFiActive.value = true
        _hifiLabel.value = if (exclusive) "HI-FI" else "HD"
        _hifiDetail.value = if (exclusive) "Direct DAC" else "Oboe Mixed"
    }
}
