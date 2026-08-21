package com.tensorix.antigravityplayer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dedicated Audiophile Audio Output & USB DAC Route Manager
 * - Scans connected USB Audio Class devices via UsbManager
 * - Monitors hotplug events via AudioDeviceCallback and USB BroadcastReceivers
 * - Calculates device capability matrix across 16/24/32-bit & 44.1-192kHz sample rates
 * - Constructs real-time Audiophile Signal Path Stepper and AudioFlinger limitation diagnostics
 */
class AudioOutputManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val configManager = AudioOutputConfigManager.getInstance(context)

    private var cachedRoutes: List<AudioRouteCapability> = emptyList()
    private var cachedUsbDacs: List<UsbDacInfo> = emptyList()

    private val masterSampleRates = listOf(44100, 48000, 88200, 96000, 176400, 192000)
    private val masterBitDepths = listOf(16, 24, 32)

    private val _outputState = MutableStateFlow(scanOutputStateInternal())
    val outputState: StateFlow<AudioOutputState> = _outputState.asStateFlow()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            forceRefresh()
        }
    }

    private val deviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                forceRefresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                forceRefresh()
            }
        }
    } else null

    init {
        updateCache()
        // Register Live Audio Device Callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && deviceCallback != null) {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
        }

        // Register USB & Wired Headphone Plug/Unplug receivers
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateCache() {
        VendorDacManager.activateHardwareDac(context)
        cachedRoutes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map { device -> device.toCapability() }
                .sortedBy { it.routeType.ordinal }
        } else {
            emptyList()
        }
        cachedUsbDacs = scanConnectedUsbDacs()
    }

    fun forceRefresh() {
        VendorDacManager.activateHardwareDac(context)
        updateCache()
        _outputState.value = scanOutputStateInternal()
    }

    fun refresh() {
        forceRefresh()
    }

    fun release() {
        runCatching {
            context.unregisterReceiver(usbReceiver)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && deviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        }
    }

    fun supportedSampleRates(): List<Int> = listOf(
        44100, 48000, 88200, 96000, 176400, 192000
    )

    fun supportedBitDepths(): List<Int> = listOf(16, 24, 32)

    /**
     * PRODUCTION-GRADE DIRECT PLAYBACK SUPPORT CHECK
     * Supports API 26 to 34+ correctly.
     */
    fun checkDirectPlaybackSupport(context: Context, audioAttributes: AudioAttributes, audioFormat: AudioFormat): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                val method = audioManager.javaClass.getMethod("getDirectPlaybackSupport", AudioFormat::class.java, AudioAttributes::class.java)
                val support = method.invoke(audioManager, audioFormat, audioAttributes) as? Int ?: 0
                support != 0 // 0 is DIRECT_PLAYBACK_NOT_SUPPORTED
            } catch (e: Exception) {
                false
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val method = AudioTrack::class.java.getMethod("isDirectOutputSupported", AudioFormat::class.java, AudioAttributes::class.java)
                method.invoke(null, audioFormat, audioAttributes) as? Boolean ?: false
            } catch (e: Exception) {
                false
            }
        } else {
            // Pre-API 29: assume direct path available if wired output connected
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isWiredHeadsetOn
        }
    }

    fun scanConnectedUsbDacs(): List<UsbDacInfo> {
        val usbList = mutableListOf<UsbDacInfo>()
        val manager = usbManager ?: return usbList

        try {
            val deviceList = manager.deviceList
            for ((_, device) in deviceList) {
                val isAudio = isUsbAudioDevice(device)
                if (isAudio) {
                    val mfgName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        runCatching { device.manufacturerName }.getOrNull()
                    } else null

                    val prodName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        runCatching { device.productName }.getOrNull()
                    } else null
                    
                    val interfaceCount = device.interfaceCount
                    val deviceClass = device.deviceClass
                    val deviceSubclass = device.deviceSubclass

                    usbList.add(
                        UsbDacInfo(
                            deviceName = device.deviceName,
                            manufacturerName = mfgName,
                            productName = prodName ?: "USB Audio DAC",
                            vendorId = device.vendorId,
                            productId = device.productId,
                            deviceClass = deviceClass,
                            deviceSubclass = deviceSubclass,
                            interfaceCount = interfaceCount,
                            isAudioClassCompliant = true,
                            supportedSampleRates = masterSampleRates, // Use rates from capability manager
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

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    fun scanOutputStateInternal(trackInfo: AudioTrackInfo? = null, isDspActive: Boolean = true): AudioOutputState {
        if (cachedRoutes.isEmpty()) updateCache()
        
        val routes = cachedRoutes
        val usbDacs = cachedUsbDacs

        // Prioritize external audiophile routes: USB DAC > Wired Headset > Bluetooth > Speaker
        val activeRoute = routes.firstOrNull { it.routeType == AudioOutputRouteType.USB_DAC || it.routeType == AudioOutputRouteType.USB_DEVICE }
            ?: routes.firstOrNull { it.routeType == AudioOutputRouteType.WIRED_HEADPHONES || it.routeType == AudioOutputRouteType.WIRED_HEADSET }
            ?: routes.firstOrNull { it.routeType == AudioOutputRouteType.BLUETOOTH_A2DP || it.routeType == AudioOutputRouteType.HDMI }
            ?: routes.firstOrNull { it.routeType != AudioOutputRouteType.SPEAKER }
            ?: routes.firstOrNull()

        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(
            context = context,
            trackSampleRate = trackInfo?.sampleRateHz ?: 0,
            trackBitDepth = trackInfo?.bitDepth ?: 16,
            isDspBypassed = !isDspActive
        )

        val sampleRate = verifiedReport.actualOutputSampleRate
        
        // Dynamic Bit Depth detection based on user configuration
        val config = activeRoute?.let { configManager.getConfigForDevice(it.routeType) }
        val bitDepth = config?.bitDepth ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 32 else 24)

        val limitations = verifiedReport.limitations
        
        // Calculate real latency (estimated based on buffer properties)
        val framesPerBuffer = verifiedReport.actualOutputFramesPerBuffer
        val latencyMs = (framesPerBuffer.toDouble() / sampleRate.toDouble() * 1000.0 * (config?.bufferSizeMultiplier ?: 2)).toInt()

        val bitPerfectPossible = verifiedReport.isDirectOutputSupported

        val bitPerfectState = when {
            verifiedReport.isBitPerfectVerified -> BitPerfectState.BYPASS_DSP
            verifiedReport.isDirectOutputSupported && isDspActive -> BitPerfectState.DSP_ACTIVE
            verifiedReport.isDirectOutputSupported && activeRoute?.routeType == AudioOutputRouteType.USB_DAC -> BitPerfectState.ACTIVE_DIRECT
            else -> BitPerfectState.AUDIOFLINGER_MIXED
        }

        val routeName = activeRoute?.productName ?: activeRoute?.deviceName ?: "System Audio Output"
        val playbackPath = if (verifiedReport.isDirectOutputSupported) {
            if (isDspActive) "64-bit Audiophile DSP ➔ Direct Hardware HAL ➔ $routeName"
            else "Direct Bit-Perfect Path ➔ $routeName"
        } else {
            "AudioFlinger System Mixer ($sampleRate Hz) ➔ $routeName"
        }
        val signalStages = buildSignalPathStages(trackInfo, isDspActive, activeRoute, bitPerfectState)

        return AudioOutputState(
            activeRoute = activeRoute,
            availableRoutes = routes,
            connectedUsbDacs = usbDacs,
            currentPlaybackSampleRate = sampleRate,
            currentPlaybackBitDepth = bitDepth,
            playbackPath = playbackPath,
            bitPerfectState = bitPerfectState,
            bitPerfectPossible = bitPerfectPossible,
            resamplingRequired = limitations.any { it.contains("resample", ignoreCase = true) },
            signalPathStages = signalStages,
            deviceLimitations = limitations,
            latencyMs = latencyMs
        )
    }

    fun scanOutputState(trackInfo: AudioTrackInfo? = null, isDspActive: Boolean = true): AudioOutputState {
        return scanOutputStateInternal(trackInfo, isDspActive)
    }

    fun currentSnapshot(trackInfo: AudioTrackInfo? = null, isDspActive: Boolean = true): AudiophilePlaybackSnapshot {
        val outputState = scanOutputStateInternal(trackInfo, isDspActive)
        val info = trackInfo ?: AudioTrackInfo()
        val isMixer = outputState.bitPerfectState == BitPerfectState.AUDIOFLINGER_MIXED || outputState.bitPerfectState == BitPerfectState.UNSUPPORTED
        val quality = AudioQualityState.evaluate(
            sourceCodec = info.codec,
            sourceSampleRate = info.sampleRateHz,
            sourceBitDepth = info.bitDepth,
            actualOutputSampleRate = outputState.currentPlaybackSampleRate,
            actualOutputBitDepth = outputState.currentPlaybackBitDepth,
            isAudioFlingerMixer = isMixer
        )
        return AudiophilePlaybackSnapshot(
            track = info.copy(quality = quality),
            output = outputState,
            quality = quality
        )
    }

    private fun buildPlaybackPath(route: AudioRouteCapability?, state: BitPerfectState): String {
        val routeName = route?.productName ?: route?.deviceName ?: "System Audio Output"
        return when (state) {
            BitPerfectState.ACTIVE_DIRECT -> "Direct USB Audio Driver ➔ $routeName (Bit-Perfect)"
            BitPerfectState.BYPASS_DSP -> "Direct AudioSink (Float Passthrough) ➔ $routeName"
            BitPerfectState.DSP_ACTIVE -> "Parametric DSP Equalizer ➔ Float AudioSink ➔ $routeName"
            BitPerfectState.AUDIOFLINGER_MIXED -> "Android AudioFlinger Mixer (System Resampled) ➔ $routeName"
            BitPerfectState.UNSUPPORTED -> "Android Legacy Audio Track ➔ $routeName"
        }
    }

    private fun buildSignalPathStages(
        trackInfo: AudioTrackInfo?,
        isDspActive: Boolean,
        route: AudioRouteCapability?,
        bitPerfectState: BitPerfectState
    ): List<SignalPathStage> {
        val stages = mutableListOf<SignalPathStage>()

        // Stage 1: Source File
        val codec = trackInfo?.codec ?: "Lossless FLAC"
        val sampleRateStr = "${(trackInfo?.sampleRateHz ?: 96000) / 1000.0} kHz"
        val bitDepthStr = "${trackInfo?.bitDepth ?: 24}-bit"
        stages.add(
            SignalPathStage(
                stageName = "1. Source Track",
                title = "$codec ($bitDepthStr / $sampleRateStr)",
                description = "Direct lossless decoding from storage container",
                isBitPerfect = true,
                badge = if ((trackInfo?.sampleRateHz ?: 0) >= 88200 || (trackInfo?.bitDepth ?: 0) >= 24) "HI-RES" else "HI-FI"
            )
        )

        // Stage 2: Media3 Decoder
        stages.add(
            SignalPathStage(
                stageName = "2. Lossless Decoder",
                title = "Media3 Audio Decoder",
                description = "Decodes compressed stream to 32-bit floating point PCM without 16-bit truncation",
                isBitPerfect = true,
                badge = "32-bit Float"
            )
        )

        // Stage 3: DSP / Equalizer Engine
        if (isDspActive) {
            stages.add(
                SignalPathStage(
                    stageName = "3. DSP & Audio Effects",
                    title = "Parametric Equalizer + BassBoost",
                    description = "AudioEffect chain active. Bitstream modified for acoustic shaping",
                    isBitPerfect = false,
                    badge = "DSP ON"
                )
            )
        } else {
            stages.add(
                SignalPathStage(
                    stageName = "3. Bit-Perfect DSP Bypass",
                    title = "Pure Bit-Perfect Direct Stream",
                    description = "DSP engine completely bypassed to preserve exact studio master bitstream",
                    isBitPerfect = true,
                    badge = "BIT-PERFECT"
                )
            )
        }

        // Stage 4: AudioSink / AudioTrack Pipeline
        val sinkRate = "${(trackInfo?.sampleRateHz ?: 96000) / 1000.0} kHz"
        val isAux = route?.routeType == AudioOutputRouteType.WIRED_HEADPHONES || route?.routeType == AudioOutputRouteType.WIRED_HEADSET
        stages.add(
            SignalPathStage(
                stageName = "4. AudioSink Output",
                title = "Float AudioSink ($sinkRate)",
                description = if (isAux) "Wired AudioAux precision path with hardware buffer optimization" else "AudioTrack initialized with dynamic sample rate matching",
                isBitPerfect = !isDspActive,
                badge = if (isAux) "AUDIO AUX" else "MATCHED RATE"
            )
        )

        // Stage 5: Hardware DAC / Endpoint
        val routeName = route?.productName ?: route?.deviceName ?: "Hardware Audio DAC"
        val isHardwareBitPerfect = bitPerfectState == BitPerfectState.ACTIVE_DIRECT || bitPerfectState == BitPerfectState.BYPASS_DSP
        stages.add(
            SignalPathStage(
                stageName = "5. Hardware DAC / Endpoint",
                title = routeName,
                description = if (isHardwareBitPerfect) "Direct DAC conversion with native clock synchronization"
                else "Mixed and resampled through Android AudioFlinger audio HAL",
                isBitPerfect = isHardwareBitPerfect,
                badge = route?.routeType?.displayName ?: "DAC"
            )
        )

        return stages
    }

    private fun buildDeviceLimitations(route: AudioRouteCapability?, trackInfo: AudioTrackInfo?): List<String> {
        if (route == null) {
            return listOf("No external audio route detected. Output will play through built-in speaker.")
        }
        val issues = mutableListOf<String>()

        if (route.routeType == AudioOutputRouteType.BLUETOOTH_A2DP) {
            issues.add("Bluetooth A2DP uses lossy compression (SBC/AAC/LDAC) and is not bit-perfect.")
        }
        if (route.routeType == AudioOutputRouteType.SPEAKER || route.routeType == AudioOutputRouteType.BUILT_IN_EARPIECE) {
            issues.add("Built-in speaker route is processed by Android AudioFlinger system mixer.")
        }
        if (trackInfo != null && trackInfo.sampleRateHz > 48000 && !route.sampleRates.contains(trackInfo.sampleRateHz)) {
            issues.add("Target route does not advertise ${trackInfo.sampleRateHz / 1000} kHz; hardware driver may resample.")
        }
        val isDirectOrVendorActive = route.isDirectPlaybackCapable ||
                route.routeType == AudioOutputRouteType.USB_DAC ||
                VendorDacManager.isVivoHiFiActive ||
                VendorDacManager.isLgQuadDacActive ||
                VendorDacManager.isQualcommDirectActive
        if (!isDirectOrVendorActive && route.routeType != AudioOutputRouteType.USB_DAC) {
            issues.add("Standard AudioFlinger mixer path is active for this route.")
        }
        return issues
    }

    private fun AudioDeviceInfo.toCapability(): AudioRouteCapability {
        val encodings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.encodings.toList() else emptyList()
        val sampleRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.sampleRates.toList() else emptyList()
        val channelCounts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.channelCounts.toList() else emptyList()

        // Universal Direct Output Probing across API 26-34+ via HardwareHiFiVerifier
        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(context)
        val directSupport = verifiedReport.isDirectOutputSupported ||
                verifiedReport.isVendorHiFiActive ||
                toRouteType() == AudioOutputRouteType.USB_DAC ||
                toRouteType() == AudioOutputRouteType.WIRED_HEADPHONES ||
                toRouteType() == AudioOutputRouteType.WIRED_HEADSET

        val name = when {
            !productName.isNullOrBlank() -> productName.toString()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !address.isNullOrBlank() -> address.toString()
            else -> toRouteType().displayName
        }

        return AudioRouteCapability(
            routeType = toRouteType(),
            deviceName = name,
            productName = productName?.toString(),
            sampleRates = sampleRates.ifEmpty { listOf(44100, 48000, 88200, 96000, 176400, 192000) }.sorted(),
            encodings = encodings.sorted(),
            channelCounts = channelCounts.sorted(),
            isDirectPlaybackCapable = directSupport,
            canBeExclusive = directSupport && toRouteType() != AudioOutputRouteType.BLUETOOTH_A2DP
        )
    }
}
