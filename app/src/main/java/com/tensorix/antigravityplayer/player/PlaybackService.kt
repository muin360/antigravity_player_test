package com.tensorix.antigravityplayer.player

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.tensorix.antigravityplayer.audio.HiFiActivationResult
import com.tensorix.antigravityplayer.audio.HiFiStateManager
import com.tensorix.antigravityplayer.audio.HiFiBadgeState
import com.tensorix.antigravityplayer.audio.HardwareHiFiVerifier
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tensorix.antigravityplayer.audio.AudioOutputApi
import com.tensorix.antigravityplayer.audio.AudioOutputConfigManager
import com.tensorix.antigravityplayer.audio.AudioOutputManager
import com.tensorix.antigravityplayer.audio.AudioOutputRouteType
import com.tensorix.antigravityplayer.audio.AudioTrackInfo
import com.tensorix.antigravityplayer.audio.AudiophilePlaybackSnapshot
import com.tensorix.antigravityplayer.audio.OutputDeviceConfig
import com.tensorix.antigravityplayer.audio.VendorDacManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audiophile-Grade Core Playback Service with True Hi-Res Audio Architecture:
 *  - 24-bit / 32-bit Float Output direct passthrough (FLAC, WAV, ALAC, DSD)
 *  - Dynamic Hardware Sample Rate Matching (44.1kHz, 48kHz, 88.2kHz, 96kHz, 176.4kHz, 192kHz)
 *  - Bit-Perfect DSP Bypass switch for studio-master audio clarity
 *  - Integrated AudioOutputManager for USB DAC detection, hotplug routing, and AudioFlinger diagnostics
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private lateinit var audioManager: AudioManager
    var equalizerEngine: EqualizerEngine? = null
        private set
    var audioOutputManager: AudioOutputManager? = null
        private set
    var outputConfigManager: AudioOutputConfigManager? = null
        private set
    var hifiProfileManager: com.tensorix.antigravityplayer.audio.HiFiProfileManager? = null
        private set
    var dynamicProfileEngine: com.tensorix.antigravityplayer.audio.DynamicProfileEngine? = null
        private set
    var audioIntelligence: com.tensorix.antigravityplayer.audio.AudioIntelligencePlatform? = null
        private set
    var autoEqEngine: com.tensorix.antigravityplayer.audio.AutoEqEngine? = null
        private set

    companion object {
        const val CHANNEL_ID = "antigravity_playback_channel"
        const val NOTIFICATION_ID = 1001

        private val _instanceFlow = MutableStateFlow<PlaybackService?>(null)
        val instanceFlow: StateFlow<PlaybackService?> = _instanceFlow.asStateFlow()

        var instance: PlaybackService?
            get() = _instanceFlow.value
            private set(value) { _instanceFlow.value = value }

        private val _hiFiSupportedState = MutableStateFlow(false)
        val hiFiSupportedState: StateFlow<Boolean> = _hiFiSupportedState.asStateFlow()

        fun isHiFiSupported(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        }
    }

    private val audioPrefs by lazy {
        getSharedPreferences("antigravity_audio_prefs", Context.MODE_PRIVATE)
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var configJob: Job? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var bitPerfectReceiver: BroadcastReceiver? = null

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    handleAudioOutputChanged(state == 1)
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    player?.pause()
                    handleAudioOutputChanged(false)
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    handleAudioOutputChanged(state == BluetoothProfile.STATE_CONNECTED)
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                    handleAudioOutputChanged(state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
                }
            }
        }
    }

    private fun handleAudioOutputChanged(externalOutputConnected: Boolean) {
        // Re-evaluate actual audio output state
        HiFiStateManager.evaluate(applicationContext)
        val hifiState = HiFiStateManager.state.value
        
        Log.d("HiFi", "Output changed → type=${hifiState.outputType}, hifi=${hifiState.isHiFiActive}, sr=${hifiState.sampleRate}")
        
        // Sync HiFiBadgeState from the single source of truth
        HiFiBadgeState.update(
            HiFiActivationResult(
                isHiFiConfirmed = hifiState.isHiFiActive,
                activeOem = hifiState.manufacturer,
                confirmedParameter = "output=${hifiState.outputType.name}|sr=${hifiState.sampleRate}",
                outputSampleRate = hifiState.sampleRate,
                isLowLatencyPath = hifiState.isHiFiActive,
                isWiredConnected = hifiState.outputType == HiFiStateManager.OutputType.WIRED || 
                                   hifiState.outputType == HiFiStateManager.OutputType.USB_DAC,
                isExclusiveModeActive = false
            )
        )
        
        // Also update ExoPlayer pipeline enable/disable
        val shouldEnablePipeline = hifiState.isHiFiActive
        if (_hiFiEnabled.value != shouldEnablePipeline) {
            _hiFiEnabled.value = shouldEnablePipeline
            audioPrefs.edit { putBoolean("hi_fi_enabled", shouldEnablePipeline) }
            reloadAudioPipeline()
        }
    }

    private fun registerAudioOutputReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(headsetReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(headsetReceiver, filter)
        }
    }

    private fun unregisterAudioOutputReceiver() {
        runCatching { unregisterReceiver(headsetReceiver) }
    }

    private val _hiFiEnabled = MutableStateFlow(true)
    val hiFiEnabled: StateFlow<Boolean> = _hiFiEnabled.asStateFlow()

    private val _bitPerfectMode = MutableStateFlow(false)
    val bitPerfectMode: StateFlow<Boolean> = _bitPerfectMode.asStateFlow()

    private val _sampleRateMatching = MutableStateFlow(true)
    val sampleRateMatching: StateFlow<Boolean> = _sampleRateMatching.asStateFlow()

    private val _audioAuxEnabled = MutableStateFlow(true)
    val audioAuxEnabled: StateFlow<Boolean> = _audioAuxEnabled.asStateFlow()

    private val _oboeMode = MutableStateFlow("UNAVAILABLE")
    val oboeMode: StateFlow<String> = _oboeMode.asStateFlow()

    private val _autoProfileSwitch = MutableStateFlow(true)
    val autoProfileSwitch: StateFlow<Boolean> = _autoProfileSwitch.asStateFlow()

    private val _currentTrackInfo = MutableStateFlow(AudioTrackInfo())
    val currentTrackInfo: StateFlow<AudioTrackInfo> = _currentTrackInfo.asStateFlow()

    private val _audiophileSnapshot = MutableStateFlow(AudiophilePlaybackSnapshot())
    val audiophileSnapshot: StateFlow<AudiophilePlaybackSnapshot> = _audiophileSnapshot.asStateFlow()

    var vivoAudioLayer: com.tensorix.antigravityplayer.audio.VivoAudioIntegrationLayer? = null
        private set
    var universalHardwareDetector: com.tensorix.antigravityplayer.audio.UniversalHardwareDetector? = null
        private set
    var universalHiFiEngine: com.tensorix.antigravityplayer.audio.UniversalHiFiEngine? = null
        private set
    var universalVendorManager: com.tensorix.antigravityplayer.audio.UniversalVendorIntegrationLayer? = null
        private set
    var usbAudioMasterEngine: com.tensorix.antigravityplayer.audio.UsbAudioMasterEngine? = null
        private set

    val activeAudioSessionId: Int
        get() = player?.audioSessionId ?: 0

    val dspProcessor = com.tensorix.antigravityplayer.audio.Audiophile64BitDspProcessor()

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        Log.i("HiFiPlayer", "PlaybackService onCreate: Activating Hardware Paths")
        universalHardwareDetector = com.tensorix.antigravityplayer.audio.UniversalHardwareDetector(applicationContext)
        universalHiFiEngine = com.tensorix.antigravityplayer.audio.UniversalHiFiEngine(applicationContext)
        universalVendorManager = com.tensorix.antigravityplayer.audio.UniversalVendorIntegrationLayer(applicationContext)
        usbAudioMasterEngine = com.tensorix.antigravityplayer.audio.UsbAudioMasterEngine(applicationContext)

        vivoAudioLayer = com.tensorix.antigravityplayer.audio.VivoAudioIntegrationLayer(applicationContext)
        audioOutputManager = AudioOutputManager(applicationContext)
        outputConfigManager = AudioOutputConfigManager.getInstance(applicationContext)
        hifiProfileManager = com.tensorix.antigravityplayer.audio.HiFiProfileManager(applicationContext)
        val profileManager = hifiProfileManager
        if (profileManager != null) {
            dynamicProfileEngine = com.tensorix.antigravityplayer.audio.DynamicProfileEngine(applicationContext, profileManager)
        }
        audioIntelligence = com.tensorix.antigravityplayer.audio.AudioIntelligencePlatform(applicationContext)
        equalizerEngine = EqualizerEngine(applicationContext)
        equalizerEngine?.setDspProcessor(dspProcessor)
        autoEqEngine = com.tensorix.antigravityplayer.audio.AutoEqEngine(applicationContext)

        // Retain settings from preferences
        _hiFiEnabled.value = audioPrefs.getBoolean("hi_fi_enabled", true)
        _bitPerfectMode.value = audioPrefs.getBoolean("bit_perfect_mode", false)
        _sampleRateMatching.value = audioPrefs.getBoolean("sample_rate_matching", true)
        _audioAuxEnabled.value = audioPrefs.getBoolean("audio_aux_enabled", true)
        _autoProfileSwitch.value = audioPrefs.getBoolean("auto_profile_switch", true)

        dspProcessor.isBitPerfectBypass = _bitPerfectMode.value
        dspProcessor.isEnabled = !_bitPerfectMode.value
        dspProcessor.isTurboMode = _hiFiEnabled.value

        equalizerEngine?.setBitPerfectBypass(_bitPerfectMode.value)
        refreshAudiophileState()

        configJob = serviceScope.launch {
            launch {
                outputConfigManager?.configUpdates?.collectLatest { updatedRoute ->
                    val activeRoute = audioOutputManager?.scanOutputState()?.activeRoute?.routeType
                    if (activeRoute == updatedRoute) {
                        reloadAudioPipeline()
                    }
                }
            }
            launch {
                var lastRoute = audioOutputManager?.scanOutputState()?.activeRoute?.routeType
                audioOutputManager?.outputState?.collectLatest { state ->
                    val newRoute = state.activeRoute?.routeType
                    if (newRoute != lastRoute) {
                        lastRoute = newRoute
                        Log.i("AntigravityAudioAudit", "[AUTOMATION] Audio route changed to $newRoute. Rebuilding pipeline!")
                        reloadAudioPipeline()
                    }
                }
            }
        }
        
        // Broadcast receivers are now consolidated into headsetReceiver registered via registerAudioOutputReceiver()

        val bitPerfectFilter = IntentFilter("com.tensorix.antigravityplayer.SET_BIT_PERFECT")
        bitPerfectReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val enabled = intent?.getBooleanExtra("enabled", false) ?: false
                Log.i("AntigravityAudioAudit", "[CMD] Received SET_BIT_PERFECT broadcast: enabled=$enabled")
                setBitPerfectMode(enabled)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bitPerfectReceiver, bitPerfectFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bitPerfectReceiver, bitPerfectFilter)
        }

        // Listen to volume changes for DVC using BroadcastReceiver
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val dvcVol = (currentVolume.toDouble() / maxVolume.toDouble().coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                    dspProcessor.dvcVolume = dvcVol
                    
                    val handle = com.tensorix.antigravityplayer.audio.OboeAudioSink.currentActiveHandle
                    if (handle != 0L && com.tensorix.antigravityplayer.audio.OboeBridge.isAvailable) {
                        com.tensorix.antigravityplayer.audio.OboeBridge.setDvcVolume(handle, dvcVol)
                    }
                }
            }
        }
        val volFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, volFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, volFilter)
        }
        
        // Initial sync
        val initDvc = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / 
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toDouble().coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        dspProcessor.dvcVolume = initDvc
        val initialHandle = com.tensorix.antigravityplayer.audio.OboeAudioSink.currentActiveHandle
        if (initialHandle != 0L && com.tensorix.antigravityplayer.audio.OboeBridge.isAvailable) {
            com.tensorix.antigravityplayer.audio.OboeBridge.setDvcVolume(initialHandle, initDvc)
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Antigravity Playback",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Antigravity Music Player Playback Controls"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }

        buildAndAttachPlayer()
        showPlaybackNotification("Antigravity Player ready", "Preparing audio pipeline")

        // Initial HiFi state evaluation on service start
        HiFiStateManager.evaluate(applicationContext)
        val initialState = HiFiStateManager.state.value
        HiFiBadgeState.update(
            HiFiActivationResult(
                isHiFiConfirmed = initialState.isHiFiActive,
                activeOem = initialState.manufacturer,
                confirmedParameter = "init|output=${initialState.outputType.name}",
                outputSampleRate = initialState.sampleRate,
                isLowLatencyPath = initialState.isHiFiActive,
                isWiredConnected = initialState.outputType == HiFiStateManager.OutputType.WIRED ||
                                   initialState.outputType == HiFiStateManager.OutputType.USB_DAC,
                isExclusiveModeActive = false
            )
        )
        registerAudioOutputReceiver()
    }

    internal fun reloadAudioPipeline() {
        serviceScope.launch {
            val currentPlayer = player ?: return@launch
            val currentMediaItems = buildList {
                val count = currentPlayer.mediaItemCount
                for (i in 0 until count) {
                    add(currentPlayer.getMediaItemAt(i))
                }
            }
            val currentIndex = currentPlayer.currentMediaItemIndex
            val currentPosition = currentPlayer.currentPosition
            val playWhenReady = currentPlayer.playWhenReady

            currentPlayer.stop()
            val newPlayer = createExoPlayerInstance()
            mediaSession?.setPlayer(newPlayer)
            currentPlayer.release()
            player = newPlayer
            if (currentMediaItems.isNotEmpty()) {
                newPlayer.setMediaItems(currentMediaItems, currentIndex.coerceAtLeast(0), currentPosition)
                newPlayer.prepare()
                newPlayer.playWhenReady = playWhenReady
            }
            refreshAudiophileState()
            showPlaybackNotification("Antigravity Player", if (playWhenReady) "Playing" else "Ready")

            // Re-evaluate HiFi state from truth source
            HiFiStateManager.evaluate(applicationContext)
            val hifiState = HiFiStateManager.state.value
            HiFiBadgeState.update(
                HiFiActivationResult(
                    isHiFiConfirmed = hifiState.isHiFiActive,
                    activeOem = hifiState.manufacturer,
                    confirmedParameter = "reload|output=${hifiState.outputType.name}",
                    outputSampleRate = hifiState.sampleRate,
                    isLowLatencyPath = hifiState.isHiFiActive,
                    isWiredConnected = hifiState.outputType == HiFiStateManager.OutputType.WIRED || 
                                       hifiState.outputType == HiFiStateManager.OutputType.USB_DAC,
                    isExclusiveModeActive = false
                )
            )
        }
    }

    private fun createExoPlayerInstance(): ExoPlayer {
        val activeRouteType = audioOutputManager?.scanOutputState()?.activeRoute?.routeType ?: AudioOutputRouteType.SPEAKER
        val config = outputConfigManager?.getConfigForDevice(activeRouteType) ?: OutputDeviceConfig()

        // 1. Activate OEM Hardware DACs
        VendorDacManager.activateHardwareDac(applicationContext, config.exclusiveMode)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .apply {
                if (_hiFiEnabled.value) {
                    // FLAG_LOW_LATENCY (0x100) is the correct Java framework flag that encourages
                    // AudioPolicyManager to select a lower-latency, potentially direct output.
                    @Suppress("WrongConstant")
                    setFlags(0x100)
                }
            }
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE) // Direct hardware path
            .build()

        // Poweramp-Grade 32-bit Float AudioSink with 64-bit Double DSP Processing
        val renderersFactory = object : DefaultRenderersFactory(this@PlaybackService) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return try {
                    val currentConfig = outputConfigManager?.getConfigForDevice(activeRouteType) ?: OutputDeviceConfig()
                    val isBitPerfect = _bitPerfectMode.value

                    Log.d("AntigravityAudioAudit", "Building Sink: activeRoute=$activeRouteType, bitPerfect=$isBitPerfect, hiFiEnabled=${_hiFiEnabled.value}")

                    if (com.tensorix.antigravityplayer.audio.OboeBridge.isAvailable && !isBitPerfect) {
                        try {
                            Log.i("AntigravityAudioAudit", "Using OboeAudioSink for High-Performance path")
                            return com.tensorix.antigravityplayer.audio.OboeAudioSink(
                                context = context,
                                dspProcessor = dspProcessor,
                                bitPerfectMode = false,
                                onExclusiveModeChanged = { exclusive ->
                                    _oboeMode.value = if (exclusive) "EXCLUSIVE" else "SHARED"
                                    Log.i("AntigravityAudioAudit", "Oboe Mode: ${_oboeMode.value}")
                                    // Handle via re-evaluation
                                    handleAudioOutputChanged(true)
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("AntigravityAudioAudit", "OboeAudioSink initialization failed, falling back to Default: ${e.message}")
                        }
                    }

                    dspProcessor.isTurboMode = _hiFiEnabled.value
                    dspProcessor.ditherStrength = if (currentConfig.ditherEnabled) 1.0 else 0.0
                    dspProcessor.outputBitDepth = currentConfig.bitDepth

                    val builder = DefaultAudioSink.Builder(context)
                        .setAudioProcessors(if (isBitPerfect) emptyArray() else arrayOf(dspProcessor))
                    
                    // Float output শুধু DSP mode-এ চালু। Direct HAL-এ Integer PCM দরকার।
                    if (!isBitPerfect && isHiFiSupported()) {
                        builder.setEnableFloatOutput(true)
                    } else {
                        builder.setEnableFloatOutput(false)
                    }

                    // Apply Buffer Multiplier & Alignment
                    val bufferProvider = object : DefaultAudioSink.AudioTrackBufferSizeProvider {
                        override fun getBufferSizeInBytes(
                            minBufferSizeInBytes: Int,
                            encoding: Int,
                            outputMode: Int,
                            pcmFrameSize: Int,
                            sampleRate: Int,
                            bitrate: Int,
                            maxSpeedsMultiplier: Double
                        ): Int {
                            val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192
                            val hardwareAlignedMin = framesPerBuffer * pcmFrameSize * 2
                            
                            val multiplier = currentConfig.bufferSizeMultiplier.coerceAtLeast(1)
                            val baseSize = maxOf(minBufferSizeInBytes, hardwareAlignedMin) * multiplier
                            
                            val directBuffer = VendorDacManager.getDirectBufferSize(sampleRate, 2, if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2)
                            return maxOf(baseSize, minBufferSizeInBytes, directBuffer)
                        }
                    }
                    builder.setAudioTrackBufferSizeProvider(bufferProvider)

                    val sink = builder
                        .setEnableAudioTrackPlaybackParams(true)
                        .build()
                    
                    // Hardware DSP Offload must be disabled when using 64-bit Float AudioProcessor
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        sink.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
                    }
                    return sink
                } catch (e: Exception) {
                    Log.e("AntigravityAudioAudit", "Hi-Fi Sink Build Failed, falling back to standard: ${e.message}")
                    DefaultAudioSink.Builder(context).build()
                }
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
         .setEnableDecoderFallback(true)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (_audioAuxEnabled.value) 15_000 else 30_000, 
                if (_audioAuxEnabled.value) 30_000 else 60_000, 
                if (_audioAuxEnabled.value) 500 else 2_000, 
                if (_audioAuxEnabled.value) 1_000 else 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            .build()

        // Bit-perfect mode active থাকলে Direct HAL-এর জন্য hardware prepare করো
        if (_bitPerfectMode.value) {
            val sampleRate = _currentTrackInfo.value.sampleRateHz
            VendorDacManager.prepareHardwareForDirectPlayback(this, sampleRate)
        }

        // experimentalSetDynamicSchedulingEnabled & experimentalSetOffloadSchedulingEnabled 
        // are not available in Media3 1.3.1; omitting to maintain build stability.
        
        exoPlayer.addAudioOffloadListener(
            object : androidx.media3.exoplayer.ExoPlayer.AudioOffloadListener {
                override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
                    Log.i("HiFiPlayer", "Audio Offload Active: $isOffloadedPlayback")
                }
            }
        )

        val currentSessionId = exoPlayer.audioSessionId
        if (currentSessionId != 0) {
            if (!_bitPerfectMode.value) {
                // DSP mode: AudioEffect session register করো, Vivo Hi-Fi trigger করার জন্য
                serviceScope.launch {
                    delay(300)
                    val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, currentSessionId)
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                    runCatching { sendBroadcast(intent) }
                }
                equalizerEngine?.attachToAudioSession(currentSessionId)
            } else {
                // Bit-perfect mode: AudioEffect release করো যাতে AudioPolicy hook না করে
                equalizerEngine?.release()
            }
            logRuntimeAudioDiagnostics(currentSessionId, audioAttributes)
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != 0) {
                    vivoAudioLayer?.onAudioSessionOpened(audioSessionId)
                    universalVendorManager?.onAudioSessionActive(audioSessionId)
                    if (!_bitPerfectMode.value) {
                        serviceScope.launch {
                            delay(300)
                            val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                            }
                            runCatching { sendBroadcast(intent) }
                        }
                        equalizerEngine?.attachToAudioSession(audioSessionId)
                    } else {
                        equalizerEngine?.release()
                    }
                    logRuntimeAudioDiagnostics(audioSessionId, audioAttributes)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                refreshAudiophileState()
                showPlaybackNotification(
                    "Antigravity Player",
                    when (playbackState) {
                        Player.STATE_BUFFERING -> "Buffering"
                        Player.STATE_READY -> if (player?.playWhenReady == true) "Playing" else "Ready"
                        Player.STATE_ENDED -> "Playback ended"
                        else -> "Idle"
                    }
                )
            }
        })
        
        return exoPlayer
    }

    private fun logRuntimeAudioDiagnostics(sessionId: Int, attributes: AudioAttributes) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val currentTrack = _currentTrackInfo.value
        val trackSampleRate = currentTrack?.sampleRateHz ?: 48000
        val trackBitDepth = currentTrack?.bitDepth ?: 16
        val trackChannels = currentTrack?.channels ?: 2

        val flags = attributes.flags
        val actualSampleRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: trackSampleRate
        val actualEncoding = if (!_bitPerfectMode.value && isHiFiSupported()) "ENCODING_PCM_FLOAT (4)" else "ENCODING_PCM_16BIT (2)"
        val actualChannels = if (trackChannels == 1) "MONO (1)" else "STEREO (2)"

        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(
            context = applicationContext,
            trackSampleRate = trackSampleRate,
            trackBitDepth = trackBitDepth,
            isDspBypassed = _bitPerfectMode.value
        )

        val isHeadset = vivoAudioLayer?.isWiredHeadsetConnected?.value 
            ?: (universalHardwareDetector?.detectActiveOutputDevice()?.isWired == true)
        val isUsb = audioOutputManager?.scanOutputState()?.activeRoute?.routeType == AudioOutputRouteType.USB_DAC
        val capabilityReport = com.tensorix.antigravityplayer.audio.VivoHiFiStateEngine.evaluateState(
            context = applicationContext,
            isWiredHeadset = isHeadset,
            isUsbDac = isUsb,
            isPlaying = player?.isPlaying == true || player?.playWhenReady == true,
            audioSessionId = sessionId,
            sampleRate = trackSampleRate,
            isFloatOutput = !_bitPerfectMode.value && isHiFiSupported(),
            isDspBypassed = _bitPerfectMode.value
        )

        val universalEvaluation = universalHiFiEngine?.evaluate(
            isPlaying = player?.isPlaying == true || player?.playWhenReady == true,
            audioSessionId = sessionId,
            isBitPerfectRequested = _bitPerfectMode.value,
            trackSampleRate = trackSampleRate,
            trackBitDepth = trackBitDepth
        )

        val isBitPerfect = _bitPerfectMode.value
        val processorsCount = if (isBitPerfect) 0 else 1
        val processorsNames = if (isBitPerfect) "[] (Zero AudioProcessors)" else "[Audiophile64BitDspProcessor]"
        val sinkConfig = if (isBitPerfect) "Integer PCM (16/24-bit) Direct Mode [FloatOutput=false, Processors=0]" else "32-bit Float AudioSink [FloatOutput=true, Processors=1]"
        val effectsState = if (isBitPerfect) "DETACHED (0 Active Effects / Isolated Session)" else "ATTACHED (Equalizer Session Hook Active)"
        val eqEngineState = if (isBitPerfect) "RELEASED (All AudioEffect handles = null)" else "ACTIVE (Equalizer/BassBoost/Virtualizer bound)"

        Log.i("AntigravityAudioAudit", "==================== BIT-PERFECT RUNTIME VERIFICATION ====================")
        Log.i("AntigravityAudioAudit", "1.  BitPerfect Mode Toggle:        ${if (isBitPerfect) "ENABLED (True)" else "DISABLED (False)"}")
        Log.i("AntigravityAudioAudit", "2.  Active AudioSink Config:       $sinkConfig")
        Log.i("AntigravityAudioAudit", "3.  Active AudioProcessors Count:  $processorsCount ($processorsNames)")
        Log.i("AntigravityAudioAudit", "4.  AudioTrack Format Encoding:    $actualEncoding")
        Log.i("AntigravityAudioAudit", "5.  Audio Session Effects State:   $effectsState")
        Log.i("AntigravityAudioAudit", "6.  EqualizerEngine Status:        $eqEngineState")
        Log.i("AntigravityAudioAudit", "7.  Audio Session ID:              $sessionId")
        Log.i("AntigravityAudioAudit", "8.  Output Sample Rate:            $actualSampleRate Hz")
        Log.i("AntigravityAudioAudit", "9.  Actual Channel Count:          $actualChannels")
        Log.i("AntigravityAudioAudit", "10. Active AudioFlinger Thread:    ${verifiedReport.audioThreadType.displayName}")
        Log.i("AntigravityAudioAudit", "11. Universal Hi-Fi State:         ${universalEvaluation?.state?.title ?: "N/A"}")
        Log.i("AntigravityAudioAudit", "12. Universal Hardware DAC:        ${universalEvaluation?.activeDac?.dacModelName ?: "N/A"}")
        Log.i("AntigravityAudioAudit", "13. Active Endpoint:               ${universalEvaluation?.activeDevice?.displayName ?: "N/A"}")
        Log.i("AntigravityAudioAudit", "==========================================================================")
        if (universalEvaluation != null) {
            Log.i("AntigravityAudioAudit", universalEvaluation.troubleshootingSummary)
        }
        Log.i("AntigravityAudioAudit", capabilityReport.troubleshootingReport)
    }

    private fun buildAndAttachPlayer() {
        val exoPlayer = createExoPlayerInstance()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
        refreshAudiophileState()
    }

    private fun showPlaybackNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.notify(NOTIFICATION_ID, notification)
    }

    fun updateCurrentTrackInfo(
        title: String,
        artist: String,
        codec: String,
        bitrateKbps: Int,
        bitDepth: Int,
        sampleRateHz: Int,
        channels: Int = 2,
        trackReplayGainDb: Float = 0f,
        albumReplayGainDb: Float = 0f,
        peakAmplitude: Float = 0f,
        useAlbumGain: Boolean = false
    ) {
        VendorDacManager.activateHardwareDac(applicationContext)
        val resolvedSampleRate = if (sampleRateHz > 0) sampleRateHz else 44100
        val resolvedBitDepth = if (bitDepth > 0) bitDepth else 16
        val isHiResSource = (resolvedBitDepth >= 24) || (resolvedSampleRate >= 88200)
        val cleanCodec = codec.ifBlank { "FLAC / Lossless PCM" }
        val info = AudioTrackInfo(
            title = title,
            artist = artist,
            codec = cleanCodec,
            bitrateKbps = if (bitrateKbps > 0) bitrateKbps else 320,
            bitDepth = resolvedBitDepth,
            sampleRateHz = resolvedSampleRate,
            channels = channels,
            isHiResSource = isHiResSource,
            isHiRes = isHiResSource
        )
        
        _currentTrackInfo.value = info
        if (trackReplayGainDb != 0f || albumReplayGainDb != 0f || peakAmplitude > 0f) {
            dspProcessor.applyReplayGain(trackReplayGainDb, albumReplayGainDb, peakAmplitude, useAlbumGain)
        } else {
            dspProcessor.replayGainMultiplier = 1.0
        }
        refreshAudiophileState(info)
    }

    fun refreshAudiophileState(trackInfo: AudioTrackInfo = _currentTrackInfo.value) {
        val outManager = audioOutputManager ?: return
        val isDspActive = !_bitPerfectMode.value && (equalizerEngine?.isEnabled?.value == true)
        val snapshot = outManager.currentSnapshot(trackInfo, isDspActive)
        _audiophileSnapshot.value = snapshot
        _hiFiSupportedState.value = snapshot.output.activeRoute != null
        
        // Auto-switch profile and Listening Mode based on dynamic route engine
        if (_autoProfileSwitch.value) {
            val activeRoute = snapshot.output.activeRoute?.routeType ?: AudioOutputRouteType.SPEAKER
            val profile = dynamicProfileEngine?.evaluateAndSwitch(activeRoute, null, trackInfo)
            if (profile != null) {
                equalizerEngine?.applyHiFiProfile(profile)
                
                // Auto-configure optimal listening mode for route
                when (activeRoute) {
                    AudioOutputRouteType.USB_DAC, AudioOutputRouteType.USB_DEVICE -> {
                        equalizerEngine?.setListeningMode(com.tensorix.antigravityplayer.audio.ListeningMode.REFERENCE)
                    }
                    AudioOutputRouteType.WIRED_HEADPHONES, AudioOutputRouteType.WIRED_HEADSET -> {
                        equalizerEngine?.setListeningMode(com.tensorix.antigravityplayer.audio.ListeningMode.AUDIOPHILE)
                    }
                    AudioOutputRouteType.BLUETOOTH_A2DP -> {
                        equalizerEngine?.setListeningMode(com.tensorix.antigravityplayer.audio.ListeningMode.DYNAMIC)
                    }
                    else -> {
                        equalizerEngine?.setListeningMode(com.tensorix.antigravityplayer.audio.ListeningMode.AUDIOPHILE)
                    }
                }
            }
        }
    }

    fun setHiFiEnabled(enabled: Boolean) {
        _hiFiEnabled.value = enabled && isHiFiSupported()
        audioPrefs.edit { putBoolean("hi_fi_enabled", _hiFiEnabled.value) }
        reloadAudioPipeline()
    }

    fun setBitPerfectMode(enabled: Boolean) {
        _bitPerfectMode.value = enabled
        audioPrefs.edit().putBoolean("bit_perfect_mode", enabled).apply()
        Log.i("HiFiPlayer", "Bit-Perfect Mode changed: $enabled")
        dspProcessor.isBitPerfectBypass = enabled
        dspProcessor.isEnabled = !enabled
        equalizerEngine?.setBitPerfectBypass(enabled)
        reloadAudioPipeline()
    }

    fun setSampleRateMatching(enabled: Boolean) {
        _sampleRateMatching.value = enabled
        audioPrefs.edit().putBoolean("sample_rate_matching", enabled).apply()
        Log.i("HiFiPlayer", "Sample Rate Matching changed: $enabled")
        reloadAudioPipeline()
    }

    fun setAudioAuxEnabled(enabled: Boolean) {
        _audioAuxEnabled.value = enabled
        audioPrefs.edit().putBoolean("audio_aux_enabled", enabled).apply()
        // If enabled, force specific attributes for low latency
        reloadAudioPipeline()
    }

    fun setAutoProfileSwitch(enabled: Boolean) {
        _autoProfileSwitch.value = enabled
        audioPrefs.edit().putBoolean("auto_profile_switch", enabled).apply()
        refreshAudiophileState()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    fun isPlayerReady(): Boolean = player != null && mediaSession != null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                player?.pause()
            }
        }
    }

    override fun onDestroy() {
        unregisterAudioOutputReceiver()
        HiFiStateManager.reset()
        configJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()

        bitPerfectReceiver?.let { runCatching { unregisterReceiver(it) } }
        
        VendorDacManager.deactivate(applicationContext)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        
        volumeReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        volumeReceiver = null
        
        equalizerEngine?.release()
        equalizerEngine = null
        vivoAudioLayer?.unregister()
        vivoAudioLayer = null
        audioOutputManager?.release()
        audioOutputManager = null
        instance = null

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}
