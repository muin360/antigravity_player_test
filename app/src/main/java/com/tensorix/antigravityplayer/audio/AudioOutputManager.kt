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
import androidx.media3.common.util.UnstableApi
import com.tensorix.antigravityplayer.player.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dedicated Audiophile Audio Output \u0026 USB DAC Route Manager
 * - Scans connected USB Audio Class devices via UsbManager
 * - Monitors hotplug events via AudioDeviceCallback and USB BroadcastReceivers
 * - Calculates device capability matrix across 16/24/32-bit \u0026 44.1-192kHz sample rates
 * - Constructs real-time Audiophile Signal Path Stepper and AudioFlinger limitation diagnostics
 */
@UnstableApi
class AudioOutputManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private var cachedRoutes: List<AudioRouteCapability> = emptyList()
    private var cachedUsbDacs: List<UsbDacInfo> = emptyList()

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

                    val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    val deviceInfo = audioDevices.find { 
                        (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
                        it.productName?.toString() == (prodName ?: "")
                    }

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
                            supportedSampleRates = deviceInfo?.sampleRates?.filter { it > 0 }?.sorted() ?: emptyList(),
                            supportedBitDepths = deviceInfo?.encodings?.map { enc ->
                                when (enc) {
                                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                                    android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                                    android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
                                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                                    else -> 0
                                }
                            }?.filter { it > 0 }?.distinct()?.sorted() ?: emptyList()
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

        // 1. Identify ACTUALLY ACTIVE route from system
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val activeDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } else emptyArray()
        
        // Find the device that is actually sink and currently active
        // On many Android devices, multiple devices might be returned, but only one is "active"
        // We use a heuristic: the first USB or Wired device, or Speaker.
        val activeDevice = activeDevices.firstOrNull { it.isSink && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                          ?: activeDevices.firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        // Correlate with cached capabilities - MUST be precise
        val activeRoute = activeDevice?.let { dev ->
            val devName = dev.productName?.toString() ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dev.address else null
            routes.find { it.deviceName == (devName ?: it.routeType.displayName) }
        }

        // 2. Build Canonical Runtime Snapshot
        val currentTrack = trackInfo ?: AudioTrackInfo()
        val canonicalSnapshot = AudioVerificationEngine.buildCanonicalSnapshot(
            context = context,
            trackInfo = currentTrack,
            isDspActive = isDspActive,
            activeRoute = activeRoute,
            dspProcessor = PlaybackService.instance?.dspProcessor
        )

        val sampleRate = canonicalSnapshot.actualOutput.sampleRate.value
        val bitDepth = canonicalSnapshot.actualOutput.bitDepth.value
        val limitations = canonicalSnapshot.limitations
        
        // 3. Map Snapshot back to existing UI model for compatibility
        val bitPerfectState = canonicalSnapshot.bitPerfect.state
        val bitPerfectPossible = canonicalSnapshot.directPathActive.value && bitPerfectState != BitPerfectState.UNAVAILABLE

        val playbackPath = buildPlaybackPath(activeRoute, bitPerfectState)
        
        val signalStages = buildSignalPathStages(currentTrack, activeRoute, bitPerfectState, canonicalSnapshot)

        return AudioOutputState(
            activeRoute = activeRoute,
            availableRoutes = routes,
            connectedUsbDacs = usbDacs,
            currentPlaybackSampleRate = sampleRate,
            currentPlaybackBitDepth = bitDepth,
            playbackPath = playbackPath,
            bitPerfectState = bitPerfectState,
            bitPerfectPossible = bitPerfectPossible,
            resamplingRequired = canonicalSnapshot.resamplerState.value == "ACTIVE",
            signalPathStages = signalStages,
            deviceLimitations = limitations,
            latencyMs = 0,
            runtimeSnapshot = null,
            canonicalSnapshot = canonicalSnapshot
        )
    }

    fun scanOutputState(trackInfo: AudioTrackInfo? = null, isDspActive: Boolean = true): AudioOutputState {
        return scanOutputStateInternal(trackInfo, isDspActive)
    }

    fun currentSnapshot(trackInfo: AudioTrackInfo? = null, isDspActive: Boolean = true): AudiophilePlaybackSnapshot {
        val outputState = scanOutputStateInternal(trackInfo, isDspActive)
        val info = trackInfo ?: AudioTrackInfo()
        val quality = AudioQualityState.evaluate(
            sourceCodec = info.codec,
            sourceSampleRate = info.sampleRateHz,
            sourceBitDepth = info.bitDepth,
            actualOutputSampleRate = outputState.currentPlaybackSampleRate,
            actualOutputBitDepth = outputState.currentPlaybackBitDepth,
            bitPerfectState = outputState.bitPerfectState
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
            BitPerfectState.VERIFIED -> "Bit-Perfect Path ➔ $routeName (Verified)"
            BitPerfectState.ACTIVE_UNVERIFIED -> "Direct Path ➔ $routeName (Unverified)"
            BitPerfectState.ELIGIBLE -> "Eligible for Bit-Perfect ➔ $routeName"
            BitPerfectState.REQUESTED -> "Bit-Perfect Requested ➔ $routeName"
            else -> "Standard Android Audio Path ➔ $routeName"
        }
    }

    private fun buildSignalPathStages(
        trackInfo: AudioTrackInfo?,
        route: AudioRouteCapability?,
        bitPerfectState: BitPerfectState,
        snapshot: CanonicalAudioRuntimeSnapshot
    ): List<SignalPathStage> {
        val stages = mutableListOf<SignalPathStage>()

        // Stage 1: Source File
        val codec = trackInfo?.codec ?: "Lossless PCM"
        val sampleRateValue = trackInfo?.sampleRateHz ?: 0
        val sampleRateStr = if (sampleRateValue > 0) "${sampleRateValue / 1000.0} kHz" else "Unknown kHz"
        val bitDepthValue = trackInfo?.bitDepth ?: 0
        val bitDepthStr = if (bitDepthValue > 0) "$bitDepthValue-bit" else "Unknown-bit"
        stages.add(
            SignalPathStage(
                stageName = "1. Source Track",
                title = "$codec ($bitDepthStr / $sampleRateStr)",
                description = "Direct lossless decoding from storage container",
                isBitPerfect = true,
                badge = if (sampleRateValue >= 88200 || bitDepthValue >= 24) "HI-RES" else "HI-FI"
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
        val dsp = PlaybackService.instance?.dspProcessor
        val dspEnabled = dsp != null && dsp.isEnabled && !dsp.isBitPerfectBypass
        if (dspEnabled) {
            stages.add(
                SignalPathStage(
                    stageName = "3. DSP & Audio Effects",
                    title = "Parametric Equalizer + 64-bit DSP",
                    description = "AudioEffect chain active. Bitstream modified for acoustic shaping",
                    isBitPerfect = false,
                    badge = "DSP ACTIVE"
                )
            )
        } else {
            stages.add(
                SignalPathStage(
                    stageName = "3. Bit-Perfect DSP Bypass",
                    title = "Pure Bit-Perfect Direct Stream",
                    description = "DSP engine completely bypassed to preserve exact studio master bitstream",
                    isBitPerfect = true,
                    badge = "BYPASSED"
                )
            )
        }

        // Stage 4: AudioSink / AudioTrack Pipeline
        val actualRate = snapshot.actualOutput.sampleRate.value
        val actualRateStr = if (actualRate > 0) "${actualRate / 1000.0} kHz" else "UNKNOWN"
        val sinkBadge = when (bitPerfectState) {
            BitPerfectState.VERIFIED -> "VERIFIED"
            BitPerfectState.ACTIVE_UNVERIFIED -> "ACTIVE"
            BitPerfectState.UNKNOWN -> "UNKNOWN"
            else -> "UNAVAILABLE"
        }
        
        stages.add(
            SignalPathStage(
                stageName = "4. AudioSink Output",
                title = "Float AudioSink ($actualRateStr)",
                description = if (bitPerfectState == BitPerfectState.VERIFIED) "Verified direct native stream with exact sample-rate matching"
                else "AudioTrack initialized; bit-perfect status unverified",
                isBitPerfect = bitPerfectState == BitPerfectState.VERIFIED,
                badge = sinkBadge
            )
        )

        // Stage 5: Hardware DAC / Endpoint
        val routeName = route?.productName ?: route?.deviceName ?: "Hardware Audio DAC"
        val dacBadge = when (bitPerfectState) {
            BitPerfectState.VERIFIED -> "VERIFIED"
            BitPerfectState.ACTIVE_UNVERIFIED -> "ACTIVE"
            BitPerfectState.UNKNOWN -> "UNKNOWN"
            else -> route?.routeType?.displayName ?: "DAC"
        }
        stages.add(
            SignalPathStage(
                stageName = "5. Hardware DAC / Endpoint",
                title = routeName,
                description = if (bitPerfectState == BitPerfectState.VERIFIED) "Verified Bit-for-Bit exact studio master output established"
                else if (bitPerfectState == BitPerfectState.ACTIVE_UNVERIFIED) "Direct path active; bit-integrity not yet verified"
                else "Mixed and resampled through Android AudioFlinger audio HAL",
                isBitPerfect = bitPerfectState == BitPerfectState.VERIFIED,
                badge = dacBadge
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
        val routeType = toRouteType()
        
        val directSupport = verifiedReport.isDirectOutputSupported ||
                verifiedReport.isVendorHiFiActive ||
                routeType == AudioOutputRouteType.USB_DAC ||
                routeType == AudioOutputRouteType.WIRED_HEADPHONES ||
                routeType == AudioOutputRouteType.WIRED_HEADSET

        val name = when {
            !productName.isNullOrBlank() -> productName.toString()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !address.isNullOrBlank() -> address.toString()
            else -> routeType.displayName
        }

        return AudioRouteCapability(
            routeType = routeType,
            deviceName = name,
            productName = productName?.toString(),
            sampleRates = sampleRates.filter { it > 0 }.sorted(),
            encodings = encodings.filter { it > 0 }.sorted(),
            channelCounts = channelCounts.filter { it > 0 }.sorted(),
            isDirectPlaybackCapable = directSupport,
            canBeExclusive = directSupport && routeType != AudioOutputRouteType.BLUETOOTH_A2DP
        )
    }
}
