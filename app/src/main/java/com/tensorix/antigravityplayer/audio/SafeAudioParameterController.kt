package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.tensorix.antigravityplayer.util.CrashDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, vendor-gated dispatcher for AudioManager parameters.
 * Eliminates HAL crashes, binder hangs, and unhandled runtime exceptions.
 */
object SafeAudioParameterController {

    private const val TAG = "SafeAudioParam"

    enum class TargetVendor {
        VIVO,
        SAMSUNG,
        SONY,
        LG,
        QUALCOMM,
        GENERIC
    }

    sealed class ParameterResult {
        object Success : ParameterResult()
        object UnsupportedVendor : ParameterResult()
        object AlreadyApplied : ParameterResult()
        data class Failed(val reason: String) : ParameterResult()
    }

    private val parameterCache = ConcurrentHashMap<String, String>()
    private val manufacturer: String get() = (Build.MANUFACTURER ?: "").lowercase()
    private val brand: String get() = (Build.BRAND ?: "").lowercase()
    private val hardware: String get() = (Build.HARDWARE ?: "").lowercase()
    private val board: String get() = (Build.BOARD ?: "").lowercase()

    fun isVendorMatch(vendor: TargetVendor): Boolean {
        return when (vendor) {
            TargetVendor.VIVO -> manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo")
            TargetVendor.SAMSUNG -> manufacturer.contains("samsung") || brand.contains("samsung")
            TargetVendor.SONY -> manufacturer.contains("sony") || brand.contains("sony")
            TargetVendor.LG -> manufacturer.contains("lge") || brand.contains("lge")
            TargetVendor.QUALCOMM -> hardware.contains("qcom") || hardware.contains("qualcomm") || board.contains("qcom")
            TargetVendor.GENERIC -> true
        }
    }

    suspend fun setParameterAsync(
        context: Context,
        vendor: TargetVendor,
        key: String,
        value: String
    ): ParameterResult = withContext(Dispatchers.IO) {
        setParameter(context, vendor, key, value)
    }

    fun setParameter(
        context: Context,
        vendor: TargetVendor,
        key: String,
        value: String
    ): ParameterResult {
        if (!isVendorMatch(vendor)) {
            return ParameterResult.UnsupportedVendor
        }

        val fullParam = "$key=$value"
        if (parameterCache[key] == value) {
            return ParameterResult.AlreadyApplied
        }

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                return ParameterResult.Failed("AudioManager unavailable")
            }

            audioManager.setParameters(fullParam)
            parameterCache[key] = value
            Log.d(TAG, "Applied audio parameter: $fullParam for vendor $vendor")
            ParameterResult.Success
        } catch (t: Throwable) {
            CrashDiagnostics.record(
                subsystem = "AUDIO_PARAMETER",
                stage = "setParameters($fullParam)",
                throwable = t
            )
            ParameterResult.Failed(t.message ?: "Unknown error")
        }
    }

    fun getParameter(
        context: Context,
        key: String
    ): String? {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.getParameters(key)
        } catch (t: Throwable) {
            CrashDiagnostics.record(
                subsystem = "AUDIO_PARAMETER",
                stage = "getParameters($key)",
                throwable = t
            )
            null
        }
    }

    fun clearCache() {
        parameterCache.clear()
    }
}
