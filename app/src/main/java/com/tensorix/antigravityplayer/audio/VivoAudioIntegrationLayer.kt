package com.tensorix.antigravityplayer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production-Safe Vivo Audio Integration Layer
 *
 * Implements legitimate Android framework & Vivo Funtouch OS audio integration:
 * 1. Dynamic Wired Headset State Tracking (android.intent.action.HEADSET_PLUG)
 * 2. AudioEffect Lifecycle (ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION / CLOSE)
 * 3. Vivo AudioFX Session Intents (bbk.media.action.OPEN_AUDIOFX_CONTROL_SESSION / CLOSE)
 * 4. Legitimate AudioManager / AudioSystem HAL parameter queries & configuration
 * 5. Funtouch OS Hi-Fi Settings Provider detection
 */
class VivoAudioIntegrationLayer(private val context: Context) {

    companion object {
        private const val TAG = "VivoAudioIntegration"
        
        // Vivo Custom Broadcast Actions discovered from AudioEffect.apk
        const val ACTION_BBK_OPEN_AUDIOFX_SESSION = "bbk.media.action.OPEN_AUDIOFX_CONTROL_SESSION"
        const val ACTION_BBK_CLOSE_AUDIOFX_SESSION = "bbk.media.action.CLOSE_AUDIOFX_CONTROL_SESSION"
        const val ACTION_VIVO_HIFI_STATE_CHANGED = "com.vivo.action.HIFI_STATE_CHANGED"
        const val ACTION_VIVO_HIFI_APP_STATE_CHANGED = "com.vivo.action.HIFI_APP_STATE_CHANGED"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _isWiredHeadsetConnected = MutableStateFlow(false)
    val isWiredHeadsetConnected: StateFlow<Boolean> = _isWiredHeadsetConnected.asStateFlow()

    private val _isHeadsetWithMic = MutableStateFlow(false)
    val isHeadsetWithMic: StateFlow<Boolean> = _isHeadsetWithMic.asStateFlow()

    private val _activeAudioSessionId = MutableStateFlow(0)
    val activeAudioSessionId: StateFlow<Int> = _activeAudioSessionId.asStateFlow()

    private val _isPlaybackActive = MutableStateFlow(false)
    val isPlaybackActive: StateFlow<Boolean> = _isPlaybackActive.asStateFlow()

    private val _vivoHifiSettingEnabled = MutableStateFlow(false)
    val vivoHifiSettingEnabled: StateFlow<Boolean> = _vivoHifiSettingEnabled.asStateFlow()

    private var headsetReceiver: BroadcastReceiver? = null

    init {
        registerHeadsetReceiver()
        checkInitialHeadsetState()
        queryVivoHifiSettings()
    }

    private fun registerHeadsetReceiver() {
        if (headsetReceiver != null) return

        headsetReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
                    val state = intent.getIntExtra("state", 0) // 0 = unplugged, 1 = plugged
                    val mic = intent.getIntExtra("microphone", 0) // 0 = no mic, 1 = with mic
                    val name = intent.getStringExtra("name") ?: "Headset"
                    
                    val isConnected = (state == 1)
                    _isWiredHeadsetConnected.value = isConnected
                    _isHeadsetWithMic.value = (mic == 1)
                    
                    Log.i(TAG, "🎧 [HEADSET EVENT] State: $state (Connected: $isConnected), Mic: $mic, Device Name: $name")
                    
                    // Re-trigger legitimate audio parameters on headset connection change
                    if (isConnected && _activeAudioSessionId.value != 0) {
                        applyLegitimateVivoParameters(_activeAudioSessionId.value)
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        runCatching { context.registerReceiver(headsetReceiver, filter) }
    }

    private fun checkInitialHeadsetState() {
        val isVivo = isVivoDevice()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
            val hasHeadset = devices.any { 
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || 
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES 
            }
            _isWiredHeadsetConnected.value = hasHeadset
        } else {
            @Suppress("DEPRECATION")
            _isWiredHeadsetConnected.value = audioManager?.isWiredHeadsetOn == true
        }
        Log.i(TAG, "📱 [INITIAL PROBE] IsVivo: $isVivo, Initial Headset Connected: ${_isWiredHeadsetConnected.value}")
    }

    fun queryVivoHifiSettings(): Boolean {
        return try {
            val cr = context.contentResolver
            val musicHifi = Settings.System.getInt(cr, "hifi_settings_music", -1)
            val videoHifi = Settings.System.getInt(cr, "hifi_settings_video", -1)
            val isEnabled = (musicHifi == 1 || videoHifi == 1)
            _vivoHifiSettingEnabled.value = isEnabled
            Log.i(TAG, "⚙️ [VIVO SETTINGS] hifi_settings_music=$musicHifi, hifi_settings_video=$videoHifi -> Enabled=$isEnabled")
            isEnabled
        } catch (e: Exception) {
            Log.w(TAG, "Could not query Vivo system settings: ${e.message}")
            false
        }
    }

    /**
     * Called when ExoPlayer / Media3 AudioSink creates or acquires a new AudioSessionId.
     */
    fun onAudioSessionOpened(sessionId: Int) {
        if (sessionId == 0) return
        _activeAudioSessionId.value = sessionId
        _isPlaybackActive.value = true

        Log.i(TAG, "▶️ [SESSION OPEN] AudioSessionId: $sessionId, Package: ${context.packageName}")

        // 1. Standard Android Framework AudioEffect Control Session
        val standardIntent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        }
        runCatching { context.sendBroadcast(standardIntent) }

        // 2. Vivo-Specific AudioFX Session Broadcast
        val bbkIntent = Intent(ACTION_BBK_OPEN_AUDIOFX_SESSION).apply {
            putExtra("android.media.extra.AUDIO_SESSION", sessionId)
            putExtra("android.media.extra.PACKAGE_NAME", context.packageName)
        }
        runCatching { context.sendBroadcast(bbkIntent) }

        // 3. Apply legitimate HAL parameters for Vivo Funtouch OS
        applyLegitimateVivoParameters(sessionId)
    }

    /**
     * Called when ExoPlayer / Media3 AudioSink releases or tears down the AudioSessionId.
     */
    fun onAudioSessionClosed(sessionId: Int) {
        if (sessionId == 0) return
        _isPlaybackActive.value = false

        Log.i(TAG, "⏹️ [SESSION CLOSE] AudioSessionId: $sessionId, Package: ${context.packageName}")

        // 1. Standard Android Framework Close Broadcast
        val standardIntent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        }
        runCatching { context.sendBroadcast(standardIntent) }

        // 2. Vivo-Specific AudioFX Close Broadcast
        val bbkIntent = Intent(ACTION_BBK_CLOSE_AUDIOFX_SESSION).apply {
            putExtra("android.media.extra.AUDIO_SESSION", sessionId)
            putExtra("android.media.extra.PACKAGE_NAME", context.packageName)
        }
        runCatching { context.sendBroadcast(bbkIntent) }

        _activeAudioSessionId.value = 0
    }

    /**
     * Applies legitimate AudioManager parameters for Qualcomm/Vivo audio HAL.
     */
    fun applyLegitimateVivoParameters(sessionId: Int) {
        val am = audioManager ?: return
        val pkg = context.packageName

        try {
            val paramsToInject = listOf(
                "vivo_hifi=1",
                "vivo_headset_hifi=1",
                "vivo_hifi_state=1",
                "vivo_app_package_name=$pkg",
                "hifi_mode=1",
                "hifi_state=1"
            )
            for (p in paramsToInject) {
                runCatching { am.setParameters(p) }
            }
            
            // Read back parameters to objectively verify HAL acknowledgment
            val hifiState = am.getParameters("vivo_hifi_state") ?: ""
            val hifi = am.getParameters("vivo_hifi") ?: ""
            val headsetHifi = am.getParameters("vivo_headset_hifi") ?: ""
            Log.i(TAG, "🔍 [HAL PARAMETERS PROBE] vivo_hifi_state='$hifiState', vivo_hifi='$hifi', vivo_headset_hifi='$headsetHifi'")
        } catch (e: Exception) {
            Log.w(TAG, "AudioManager parameter injection notice: ${e.message}")
        }
    }

    fun isVivoDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        return manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") || model.contains("x21")
    }

    fun unregister() {
        headsetReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            headsetReceiver = null
        }
    }
}
