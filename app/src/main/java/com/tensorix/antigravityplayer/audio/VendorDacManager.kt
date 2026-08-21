package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Vendor-Specific DAC & Hardware HAL Integrator (Poweramp / UAPP Grade)
 */
object VendorDacManager {

    private const val TAG = "VendorDacManager"

    @Volatile
    var isLgQuadDacActive: Boolean = false
        private set

    @Volatile
    var isVivoHiFiActive: Boolean = false
        private set

    @Volatile
    var isSamsungUhqActive: Boolean = false
        private set

    @Volatile
    var isSonyHiResActive: Boolean = false
        private set

    @Volatile
    var isQualcommDirectActive: Boolean = false
        private set

    @Volatile
    var detectedDacChipsetName: String = "Internal Audio HAL"
        private set

    fun activateHardwareDac(context: Context, forceExclusive: Boolean = false): HiFiActivationResult {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        val hardware = Build.HARDWARE.lowercase()

        Log.i(TAG, "🚀 [UNIVERSAL DAC PROBE] Manufacturer: $manufacturer, Brand: $brand, Model: $model")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (forceExclusive) {
            audioManager?.setParameters("direct_pcm=1")
            audioManager?.setParameters("audio_stream_direct=true")
            audioManager?.setParameters("hifi_mode=1")
        }

        // Try ALL known OEM HiFi activation parameters
        val oemParams = mapOf(
            "VIVO" to listOf("hifi_state=on", "hifi_dac_enable=1", "hifi_mode=1"),
            "SAMSUNG" to listOf("hifi_mode=on", "udp_on=1", "upscaling_mode=1"),
            "ONEPLUS" to listOf("hifi_dac=on", "hifi=on", "dac_mode=hifi"),
            "XIAOMI" to listOf("hifi_enable=1", "mi_hifi=1", "hifi_audio=on"),
            "SONY" to listOf("audio_output_format=hi-res", "hires_mode=on"),
            "LG" to listOf("hifi_dac=on", "quadbeat_hifi=1"),
            "MOTOROLA" to listOf("hifi_enable=true"),
            "AOSP" to listOf("af.fast_track_multiplier=1", "audio_hw_sync_for_session=1")
        )

        for ((oem, params) in oemParams) {
            for (param in params) {
                try {
                    audioManager?.setParameters(param)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set $oem param $param: ${e.message}")
                }
            }
        }

        // 1. Vivo / iQOO Hi-Fi DAC Activation
        if (manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") || model.contains("x21")) {
            activateVivoHiFi(context)
        }

        // 2. LG Quad DAC
        if (manufacturer.contains("lge") || brand.contains("lge")) {
            activateLgQuadDac(context)
        }

        // 3. Samsung UHQ
        if (manufacturer.contains("samsung") || brand.contains("samsung")) {
            activateSamsungUhq(context)
        }

        // 4. Sony Xperia Hi-Res
        if (manufacturer.contains("sony") || brand.contains("sony")) {
            activateSonyHiRes(context)
        }

        // 9. Qualcomm Snapdragon Direct PCM
        activateQualcommDirectParameters(context)

        // 10. Universal AudioSystem HAL setParameters injection
        injectUniversalAudioSystemParameters(context)

        // Verification phase
        var hifiConfirmed = false
        var confirmedParam = ""
        val checkParams = listOf("hifi_state", "hifi_dac", "hifi_mode", "hifi")
        for (p in checkParams) {
            try {
                val value = audioManager?.getParameters(p)
                if (!value.isNullOrBlank() && !value.contains("off", true) && !value.contains("0")) {
                    hifiConfirmed = true
                    confirmedParam = "$p=$value"
                }
            } catch (e: Exception) { }
        }

        // Sync with authoritative HardwareHiFiVerifier
        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(context)
        isVivoHiFiActive = verifiedReport.isVendorHiFiActive || hifiConfirmed
        isQualcommDirectActive = verifiedReport.isDirectOutputSupported
        detectedDacNameInternal = verifiedReport.activeDacName

        val sampleRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val framesPerBuffer = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 1024
        val isLowLatency = framesPerBuffer <= 256
        val isWired = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        } ?: false

        return HiFiActivationResult(
            isHiFiConfirmed = isVivoHiFiActive,
            activeOem = manufacturer.uppercase(),
            confirmedParameter = confirmedParam,
            outputSampleRate = sampleRate,
            isLowLatencyPath = isLowLatency,
            isWiredConnected = isWired,
            isExclusiveModeActive = false
        )
    }
    
    private var detectedDacNameInternal: String = "Internal Audio HAL"
    val activeDacName: String get() = detectedDacNameInternal

    fun prepareHardwareForDirectPlayback(context: Context, sampleRate: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.setParameters("direct_pcm=1")
            audioManager.setParameters("vivo_hifi_state=1")
            audioManager.setParameters("vivo_headset_hifi=1")
            audioManager.setParameters("sampling_rate=$sampleRate")
            audioManager.setParameters("audio_stream_direct=true")
        }
        // Note: setParameters() সব device-এ কাজ করে না — runCatching দিয়ে safely handle করা হচ্ছে
    }

    private fun activateVivoHiFi(context: Context): Boolean {
        // Permission check ছাড়া Settings.System.putString() crash করতে পারে
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(context)) {
                // User-কে permission দিতে বলো, silent crash না করে
                Log.w("VendorDacManager", "WRITE_SETTINGS permission নেই। " +
                    "Vivo Settings → Sound & Vibration → Hi-Fi থেকে manually enable করুন।")
                return false
            }
        }
        return runCatching {
            Settings.System.putString(
                context.contentResolver,
                "vivo_hifi_music_app_list",
                context.packageName
            )
            true
        }.getOrDefault(false)
    }

    private fun activateLgQuadDac(context: Context) {
        try {
            if (Settings.System.canWrite(context)) {
                val cr = context.contentResolver
                Settings.System.putInt(cr, "quad_dac_state", 1)
            }
            Log.i(TAG, "LG Quad DAC Armed")
        } catch (e: Exception) {
            Log.w(TAG, "LG Quad DAC trigger notice: ${e.message}")
        }
    }

    private fun activateSamsungUhq(context: Context) {
        try {
            if (Settings.System.canWrite(context)) {
                val cr = context.contentResolver
                Settings.System.putInt(cr, "sound_alive_uhq_upscaler", 1)
            }
            Log.i(TAG, "Samsung UHQ Armed")
        } catch (e: Exception) {
            Log.w(TAG, "Samsung UHQ trigger notice: ${e.message}")
        }
    }

    private fun activateSonyHiRes(context: Context) {
        try {
            if (Settings.System.canWrite(context)) {
                val cr = context.contentResolver
                Settings.System.putInt(cr, "sony_hires_audio_enabled", 1)
            }
            Log.i(TAG, "Sony Hi-Res Armed")
        } catch (e: Exception) {
            Log.w(TAG, "Sony Hi-Res trigger notice: ${e.message}")
        }
    }

    private fun activateQualcommDirectParameters(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.setParameters("direct_pcm=1")
            audioManager?.setParameters("audio_stream_direct=true")
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun injectUniversalAudioSystemParameters(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.setParameters("hifi=1")
            audioManager?.setParameters("hifi_mode=on")
            audioManager?.setParameters("direct_pcm=1")
            audioManager?.setParameters("bit_perfect=1")
        } catch (e: Exception) {
            // Ignored
        }
    }

    fun deactivate(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val offParams = listOf(
            "hifi_state=off", "hifi_dac=off", "hifi_mode=off", "hifi=off", 
            "hifi_enable=0", "mi_hifi=0", "upscaling_mode=0"
        )
        for (p in offParams) {
            try {
                audioManager?.setParameters(p)
            } catch (e: Exception) {
                Log.e(TAG, "Deactivation error: ${e.message}")
            }
        }
    }

    fun getDirectBufferSize(sampleRate: Int, channelCount: Int, bytesPerSample: Int): Int {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
            if (bytesPerSample >= 4) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        )
        val multiplier = if (sampleRate >= 88200) 4 else 2
        return if (minBufferSize > 0) (minBufferSize * multiplier).coerceAtLeast(minBufferSize) else 0
    }
}

data class HiFiActivationResult(
    val isHiFiConfirmed: Boolean,
    val activeOem: String,
    val confirmedParameter: String,
    val outputSampleRate: Int,
    val isLowLatencyPath: Boolean,
    val isWiredConnected: Boolean,
    var isExclusiveModeActive: Boolean
)

enum class HiFiStatus { ACTIVE, INACTIVE, UNKNOWN }
