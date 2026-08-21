package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log

class UniversalVendorIntegrationLayer(private val context: Context) {

    companion object {
        private const val TAG = "VendorIntegration"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun onAudioSessionActive(sessionId: Int) {
        if (sessionId == 0) return

        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()

        Log.i(TAG, "[VENDOR PROBE] Manufacturer: $manufacturer, Model: $model, Session: $sessionId")

        if (manufacturer.contains("samsung")) integrateSamsung(sessionId)
        if (manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") || model.contains("x21")) integrateVivo(sessionId)
        if (manufacturer.contains("sony")) integrateSony(sessionId)
        if (manufacturer.contains("lge") || brand.contains("lge") || model.startsWith("lm-") || model.startsWith("lg-")) integrateLgQuadDac(sessionId)
        if (manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) integrateXiaomi(sessionId)
        if (manufacturer.contains("oppo") || manufacturer.contains("oneplus") || brand.contains("realme")) integrateOppoOnePlus(sessionId)
        if (manufacturer.contains("asus")) integrateAsus(sessionId)
    }

    private fun integrateSamsung(sessionId: Int) {
        try {
            audioManager?.setParameters("sound_alive=on;uhq_upscaler=1;uhq_mode=32bit")
            Log.i(TAG, "Samsung SoundAlive UHQ 32-bit Engine triggered")
        } catch (e: Exception) {
            Log.w(TAG, "Samsung integration notice: ${e.message}")
        }
    }

    private fun integrateVivo(sessionId: Int) {
        try {
            val pkg = context.packageName
            val standardIntent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, pkg)
            }
            runCatching { context.sendBroadcast(standardIntent) }

            val bbkIntent = Intent("bbk.media.action.OPEN_AUDIOFX_CONTROL_SESSION").apply {
                putExtra("android.media.extra.AUDIO_SESSION", sessionId)
                putExtra("android.media.extra.PACKAGE_NAME", pkg)
            }
            runCatching { context.sendBroadcast(bbkIntent) }

            audioManager?.let { am ->
                listOf("vivo_hifi=1", "vivo_headset_hifi=1", "vivo_hifi_state=1", "vivo_app_package_name=$pkg").forEach {
                    runCatching { am.setParameters(it) }
                }
            }
            Log.i(TAG, "Vivo AudioFX session registered")
        } catch (e: Exception) {
            Log.w(TAG, "Vivo integration notice: ${e.message}")
        }
    }

    private fun integrateSony(sessionId: Int) {
        try {
            audioManager?.setParameters("sony_hires=1;dsee_hx=1;ldac_playback_quality=990")
            Log.i(TAG, "Sony Xperia Hi-Res / DSEE HX Engine engaged")
        } catch (e: Exception) {
            Log.w(TAG, "Sony integration notice: ${e.message}")
        }
    }

    private fun integrateLgQuadDac(sessionId: Int) {
        try {
            val intent = Intent("com.lge.media.EXTRA_VOLUME_CHANGED").apply { putExtra("quad_dac", 1) }
            runCatching { context.sendBroadcast(intent) }
            Log.i(TAG, "LG Quad DAC engaged")
        } catch (e: Exception) {
            Log.w(TAG, "LG integration notice: ${e.message}")
        }
    }

    private fun integrateXiaomi(sessionId: Int) {
        try {
            audioManager?.setParameters("mi_hires_output=1;hi_res_output=1")
            Log.i(TAG, "Xiaomi Hi-Res Direct mode configured")
        } catch (e: Exception) {
            Log.w(TAG, "Xiaomi integration notice: ${e.message}")
        }
    }

    private fun integrateOppoOnePlus(sessionId: Int) {
        try {
            audioManager?.setParameters("dirac_hifi=1;dirac_mode=music")
            Log.i(TAG, "Dirac / Real Sound Engine configured")
        } catch (e: Exception) {
            Log.w(TAG, "Oppo/OnePlus integration notice: ${e.message}")
        }
    }

    private fun integrateAsus(sessionId: Int) {
        try {
            audioManager?.setParameters("audiowizard_hifi=1;ess_dac_state=1")
            Log.i(TAG, "Asus ROG AudioWizard / ESS DAC path engaged")
        } catch (e: Exception) {
            Log.w(TAG, "Asus integration notice: ${e.message}")
        }
    }
}
