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

    fun update(result: HiFiActivationResult) {
        _isHiFiActive.value = result.isHiFiConfirmed 
            || result.isLowLatencyPath 
            || result.isExclusiveModeActive
        
        _hifiLabel.value = when {
            result.isHiFiConfirmed -> "HI-FI"
            result.isExclusiveModeActive -> "HI-RES"
            result.isLowLatencyPath -> "HD"
            else -> "HI-FI"
        }

        _hifiDetail.value = when {
            result.isHiFiConfirmed -> "${result.activeOem} DAC"
            result.isExclusiveModeActive -> "Exclusive Mode"
            result.isLowLatencyPath -> "Direct Path"
            else -> ""
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
