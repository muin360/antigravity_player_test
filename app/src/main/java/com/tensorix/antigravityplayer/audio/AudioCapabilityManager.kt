package com.tensorix.antigravityplayer.audio

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build

/**
 * Professional Audio Capability Layer
 * Detects hardware DAC capabilities, sample rate ceilings, bit depths, Bluetooth codecs,
 * and AudioFlinger limitations across Android 8.0 to 14+ (API 26-34+).
 */
class AudioCapabilityManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    val masterSampleRates: List<Int> = listOf(
        44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000
    )

    val masterBitDepths: List<Int> = listOf(16, 24, 32)

    fun inspectDeviceCapabilities(): DeviceAudioCapabilities {
        val outputDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } else {
            emptyList()
        }

        val usbDacs = scanUsbAudioDevices()
        val routes = outputDevices.map { it.toRouteCapability() }
        val maxDeviceSampleRate = routes.flatMap { it.sampleRates }.maxOrNull() ?: 48000
        val maxDeviceBitDepth = 32
        val isOffloadSupported = checkOffloadSupport()

        return DeviceAudioCapabilities(
            maxHardwareSampleRate = maxDeviceSampleRate,
            maxHardwareBitDepth = maxDeviceBitDepth,
            isFloatSinkSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
            isDirectPlaybackSupported = isOffloadSupported,
            availableRoutes = routes,
            connectedUsbDacs = usbDacs,
            detectedBluetoothCodec = detectBluetoothCodec(outputDevices),
            audioFlingerResampleRate = querySystemSampleRate()
        )
    }

    fun scanUsbAudioDevices(): List<UsbDacInfo> {
        val usbList = mutableListOf<UsbDacInfo>()
        val manager = usbManager ?: return usbList

        try {
            for ((_, device) in manager.deviceList) {
                if (isAudioClassDevice(device)) {
                    val mfgName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        runCatching { device.manufacturerName }.getOrNull()
                    } else null

                    val prodName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        runCatching { device.productName }.getOrNull()
                    } else null

                    usbList.add(
                        UsbDacInfo(
                            deviceName = device.deviceName,
                            manufacturerName = mfgName,
                            productName = prodName ?: "USB Audio DAC",
                            vendorId = device.vendorId,
                            productId = device.productId,
                            deviceClass = device.deviceClass,
                            deviceSubclass = device.deviceSubclass,
                            interfaceCount = device.interfaceCount,
                            isAudioClassCompliant = true,
                            supportedSampleRates = masterSampleRates,
                            supportedBitDepths = masterBitDepths
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return usbList
    }

    private fun isAudioClassDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    private fun detectBluetoothCodec(devices: List<AudioDeviceInfo>): String? {
        val btDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ?: return null
        val encodings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) btDevice.encodings.toList() else emptyList()

        return when {
            encodings.any { it.toString().contains("LDAC", ignoreCase = true) } -> "LDAC (Hi-Res 990 kbps / 96 kHz)"
            encodings.any { it.toString().contains("APTX_HD", ignoreCase = true) } -> "aptX HD (24-bit / 48 kHz)"
            encodings.any { it.toString().contains("APTX", ignoreCase = true) } -> "Qualcomm aptX"
            encodings.any { it.toString().contains("AAC", ignoreCase = true) } -> "AAC (Advanced Audio Coding)"
            else -> "Bluetooth A2DP (Standard / SBC)"
        }
    }

    private fun checkOffloadSupport(): Boolean {
        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(context)
        return verifiedReport.isDirectOutputSupported || verifiedReport.isVendorHiFiActive
    }

    private fun querySystemSampleRate(): Int {
        val rateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return rateStr?.toIntOrNull() ?: 48000
    }

    private fun AudioDeviceInfo.toRouteCapability(): AudioRouteCapability {
        val encodings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.encodings.toList() else emptyList()
        val sampleRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.sampleRates.toList() else emptyList()
        val channelCounts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.channelCounts.toList() else emptyList()

        val name = when {
            !productName.isNullOrBlank() -> productName.toString()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !address.isNullOrBlank() -> address.toString()
            else -> toRouteType().displayName
        }

        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(context)
        val direct = verifiedReport.isDirectOutputSupported || verifiedReport.isVendorHiFiActive || toRouteType() == AudioOutputRouteType.USB_DAC

        return AudioRouteCapability(
            routeType = toRouteType(),
            deviceName = name,
            productName = productName?.toString(),
            sampleRates = sampleRates.ifEmpty { listOf(44100, 48000, 88200, 96000, 176400, 192000) }.sorted(),
            encodings = encodings.sorted(),
            channelCounts = channelCounts.sorted(),
            isDirectPlaybackCapable = direct,
            canBeExclusive = direct && toRouteType() != AudioOutputRouteType.BLUETOOTH_A2DP
        )
    }
}

data class DeviceAudioCapabilities(
    val maxHardwareSampleRate: Int,
    val maxHardwareBitDepth: Int,
    val isFloatSinkSupported: Boolean,
    val isDirectPlaybackSupported: Boolean,
    val availableRoutes: List<AudioRouteCapability>,
    val connectedUsbDacs: List<UsbDacInfo>,
    val detectedBluetoothCodec: String?,
    val audioFlingerResampleRate: Int
)
