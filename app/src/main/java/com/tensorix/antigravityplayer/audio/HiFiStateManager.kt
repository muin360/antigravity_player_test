package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HiFiStateManager {
    
    enum class OutputType { SPEAKER, WIRED, BLUETOOTH, USB_DAC }
    
    data class HiFiState(
        val isHiFiActive: Boolean,
        val outputType: OutputType,
        val sampleRate: Int,
        val manufacturer: String
    )
    
    private val _state = MutableStateFlow(
        HiFiState(false, OutputType.SPEAKER, 48000, Build.MANUFACTURER)
    )
    val state: StateFlow<HiFiState> = _state.asStateFlow()
    
    fun evaluate(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        val isWired = audioManager.isWiredHeadsetOn
        @Suppress("DEPRECATION")
        val isBluetooth = audioManager.isBluetoothA2dpOn
        
        // USB DAC detection (Android 6+)
        val isUsbDac = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
        } else false
        
        val outputType = when {
            isUsbDac -> OutputType.USB_DAC
            isWired  -> OutputType.WIRED
            isBluetooth -> OutputType.BLUETOOTH
            else -> OutputType.SPEAKER
        }
        
        val nativeSampleRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: 48000
        
        // HiFi is only valid on wired or USB DAC paths
        val isHiFi = outputType == OutputType.WIRED || outputType == OutputType.USB_DAC
        
        _state.value = HiFiState(
            isHiFiActive = isHiFi,
            outputType = outputType,
            sampleRate = nativeSampleRate,
            manufacturer = Build.MANUFACTURER.uppercase()
        )
    }
    
    fun reset() {
        _state.value = HiFiState(false, OutputType.SPEAKER, 48000, Build.MANUFACTURER)
    }
}
