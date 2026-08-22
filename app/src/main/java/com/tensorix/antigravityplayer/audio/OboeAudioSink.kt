package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.annotation.WorkerThread
import com.tensorix.antigravityplayer.player.PlaybackService
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Production-Safe High-Performance Audiophile Oboe AudioSink for Media3 / ExoPlayer.
 * 
 * Complies strictly with the Media3 AudioSink contract:
 * - Precise hardware-clock position tracking via Oboe/AAudio getTimestamp
 * - Real flush, seek, and discontinuity semantics
 * - Accurate partial-write consumption (returns true ONLY when buffer is fully consumed)
 * - Dynamic Bit-Perfect Exclusive / Shared path negotiation
 * - Graceful fallback to DefaultAudioSink if hardware cannot provide native stream
 */
@UnstableApi
class OboeAudioSink(
    private val context: Context,
    private val dspProcessor: Audiophile64BitDspProcessor? = null,
    private var bitPerfectMode: Boolean = false,
    private val onExclusiveModeChanged: (Boolean) -> Unit = {}
) : AudioSink {

    companion object {
        private const val TAG_LOG = "OboeAudioSink"
        @Volatile
        @JvmStatic
        var currentActiveHandle: Long = 0L
            internal set
        
        @Volatile
        @JvmStatic
        var currentStreamInfo: OboeBridge.NativeStreamInfo? = null
            internal set
    }

    private var streamHandle: Long = 0L
    private var volume = 1.0f
    private var sampleRate = 48000
    private var channelCount = 2
    private var pcmEncoding = C.ENCODING_PCM_FLOAT
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var framesWritten = 0L
    private var listener: AudioSink.Listener? = null
    private var audioAttributes = AudioAttributes.DEFAULT
    private var isPlaying = false
    private var floatBuffer = FloatArray(8192)

    private var startMediaTimeUs: Long = C.TIME_UNSET
    private var isSeekingOrDiscontinuous: Boolean = false
    private var preferredDevice: AudioDeviceInfo? = null

    private var playerId: PlayerId? = null
    private var clock: Clock? = null
    private var skipSilenceEnabled: Boolean = false
    private var lastInputFormat: Format? = null
    private var lastSpecifiedBufferSize: Int = 0
    private var lastOutputChannels: IntArray? = null
    private var isFallbackActive: Boolean = false

    // Fallback sink ONLY used if native Oboe library is missing or device fails native open
    private var fallbackSink: DefaultAudioSink? = null

    private fun getOrCreateFallbackSink(): DefaultAudioSink? {
        if (fallbackSink == null) {
            runCatching {
                Log.w(TAG_LOG, "Initializing and fully pre-configuring DefaultAudioSink fallback pipeline")
                val sink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(if (dspProcessor != null && !bitPerfectMode) arrayOf(dspProcessor) else emptyArray())
                    .setEnableFloatOutput(!bitPerfectMode)
                    .build()

                listener?.let { sink.setListener(it) }
                playerId?.let { sink.setPlayerId(it) }
                clock?.let { sink.setClock(it) }
                sink.setAudioAttributes(audioAttributes)
                sink.setPreferredDevice(preferredDevice)
                sink.setVolume(volume)
                sink.setPlaybackParameters(playbackParameters)
                sink.setSkipSilenceEnabled(skipSilenceEnabled)

                lastInputFormat?.let { fmt ->
                    sink.configure(fmt, lastSpecifiedBufferSize, lastOutputChannels)
                }
                if (isPlaying) {
                    sink.play()
                }
                fallbackSink = sink
                isFallbackActive = true
            }
        }
        return fallbackSink
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        fallbackSink?.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        this.playerId = playerId
        fallbackSink?.setPlayerId(playerId)
    }

    override fun setClock(clock: Clock) {
        this.clock = clock
        fallbackSink?.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (fallbackSink != null) return fallbackSink!!.supportsFormat(format)
        return Util.isEncodingLinearPcm(format.pcmEncoding)
    }

    override fun getFormatSupport(format: Format): Int {
        if (fallbackSink != null) return fallbackSink!!.getFormatSupport(format)
        return if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (fallbackSink != null) return fallbackSink!!.getCurrentPositionUs(sourceEnded)
        if (streamHandle == 0L) return C.TIME_UNSET

        val nativeTimestampUs = OboeBridge.getPlaybackTimestampUs(streamHandle)
        val baseTimeUs = if (startMediaTimeUs != C.TIME_UNSET) startMediaTimeUs else 0L
        return baseTimeUs + nativeTimestampUs
    }

    private var timeSinkConfiguredMs: Long = 0L
    private var timeNativeOpenedMs: Long = 0L
    private var timeNativeStartedMs: Long = 0L
    private var isFirstFrameWritten: Boolean = false

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        timeSinkConfiguredMs = android.os.SystemClock.elapsedRealtime()
        isFirstFrameWritten = false

        lastInputFormat = inputFormat
        lastSpecifiedBufferSize = specifiedBufferSize
        lastOutputChannels = outputChannels

        if (fallbackSink != null) {
            fallbackSink!!.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }

        val newSampleRate = inputFormat.sampleRate
        val newChannelCount = inputFormat.channelCount
        val newEncoding = inputFormat.pcmEncoding

        if (streamHandle != 0L && (sampleRate != newSampleRate || channelCount != newChannelCount)) {
            closeOboeStream()
        }

        sampleRate = newSampleRate
        channelCount = newChannelCount.coerceAtLeast(1)
        pcmEncoding = newEncoding

        val estimatedSamples = when (pcmEncoding) {
            C.ENCODING_PCM_FLOAT -> (specifiedBufferSize / 4).coerceAtLeast(channelCount)
            C.ENCODING_PCM_32BIT -> (specifiedBufferSize / 4).coerceAtLeast(channelCount)
            C.ENCODING_PCM_24BIT -> (specifiedBufferSize / 3).coerceAtLeast(channelCount)
            else -> (specifiedBufferSize / 2).coerceAtLeast(channelCount)
        }
        if (floatBuffer.size < estimatedSamples) {
            floatBuffer = FloatArray(estimatedSamples * 2)
        }

        if (streamHandle == 0L && OboeBridge.isAvailable) {
            openOboeStream(preferredDevice?.id ?: 0)
        }

        if (streamHandle == 0L) {
            // Native open failed during configure; pre-configure fallback sink immediately!
            val fallback = getOrCreateFallbackSink()
            fallback?.configure(inputFormat, specifiedBufferSize, outputChannels)
        }

        runCatching { Log.i(TAG_LOG, "Configured Native Oboe 64-bit Sink: $sampleRate Hz, $channelCount channels, Encoding: $pcmEncoding") }
    }

    override fun play() {
        isPlaying = true
        timeNativeStartedMs = android.os.SystemClock.elapsedRealtime()
        if (streamHandle != 0L) {
            OboeBridge.startStream(streamHandle)
        }
        fallbackSink?.play()
    }

    override fun pause() {
        isPlaying = false
        if (streamHandle != 0L) {
            OboeBridge.pauseStream(streamHandle)
        }
        fallbackSink?.pause()
    }

    private var isDraining = false

    override fun handleDiscontinuity() {
        isSeekingOrDiscontinuous = true
        startMediaTimeUs = C.TIME_UNSET
        isDraining = false
        runCatching { Log.i("SEEK", "handleDiscontinuity: resetting media clock and flushing stream") }
        if (streamHandle != 0L) {
            OboeBridge.flushStream(streamHandle)
        }
        fallbackSink?.handleDiscontinuity()
    }

    override fun flush() {
        framesWritten = 0L
        startMediaTimeUs = C.TIME_UNSET
        isSeekingOrDiscontinuous = true
        isDraining = false
        runCatching { Log.i("SEEK", "flush: resetting framesWritten=0 and flushing native stream") }
        if (streamHandle != 0L) {
            OboeBridge.flushStream(streamHandle)
        }
        fallbackSink?.flush()
    }

    override fun reset() {
        isPlaying = false
        isDraining = false
        framesWritten = 0L
        startMediaTimeUs = C.TIME_UNSET
        isSeekingOrDiscontinuous = false
        closeOboeStream()
        currentActiveHandle = 0L
        currentStreamInfo = null
        fallbackSink?.reset()
    }

    override fun release() {
        closeOboeStream()
        currentActiveHandle = 0L
        currentStreamInfo = null
        fallbackSink?.release()
    }

    private var directByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
    private var streamGeneration: Long = 0L

    @WorkerThread
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (fallbackSink != null) {
            return fallbackSink!!.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        if (streamHandle == 0L) {
            openOboeStream()
            if (streamHandle == 0L) {
                // Native stream could not be opened; fallback to DefaultAudioSink
                return getOrCreateFallbackSink()?.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) ?: false
            }
        }

        if (startMediaTimeUs == C.TIME_UNSET || isSeekingOrDiscontinuous) {
            startMediaTimeUs = presentationTimeUs
            isSeekingOrDiscontinuous = false
        }

        val initialPosition = buffer.position()
        val remaining = buffer.remaining()
        if (remaining == 0) return true

        val bytesPerSample = when (pcmEncoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            else -> 2
        }

        val sampleCount = remaining / bytesPerSample
        val numFrames = sampleCount / channelCount
        if (numFrames == 0) return true

        val framesWrittenResult = if (buffer.isDirect) {
            OboeBridge.writeDirect(
                handle = streamHandle,
                generation = streamGeneration,
                directBuffer = buffer,
                offsetBytes = initialPosition,
                numBytes = remaining,
                numFrames = numFrames,
                pcmEncoding = pcmEncoding,
                isBitPerfect = bitPerfectMode
            )
        } else {
            if (directByteBuffer.capacity() < remaining) {
                directByteBuffer = ByteBuffer.allocateDirect(remaining * 2).order(ByteOrder.LITTLE_ENDIAN)
            }
            directByteBuffer.clear()
            val slice = buffer.slice()
            directByteBuffer.put(slice)
            directByteBuffer.flip()

            OboeBridge.writeDirect(
                handle = streamHandle,
                generation = streamGeneration,
                directBuffer = directByteBuffer,
                offsetBytes = 0,
                numBytes = remaining,
                numFrames = numFrames,
                pcmEncoding = pcmEncoding,
                isBitPerfect = bitPerfectMode
            )
        }

        if (framesWrittenResult > 0) {
            framesWritten += framesWrittenResult

            if (!isFirstFrameWritten) {
                isFirstFrameWritten = true
                val now = runCatching { android.os.SystemClock.elapsedRealtime() }.getOrDefault(System.currentTimeMillis())
                val openDelta = (timeNativeOpenedMs - timeSinkConfiguredMs).coerceAtLeast(0)
                val startDelta = (timeNativeStartedMs - timeNativeOpenedMs).coerceAtLeast(0)
                val writeDelta = (now - timeNativeStartedMs).coerceAtLeast(0)
                val totalStartup = (now - timeSinkConfiguredMs).coerceAtLeast(0)
                runCatching { Log.i("STARTUP_TIMING", "sinkConfigure=${timeSinkConfiguredMs}ms nativeOpen=+${openDelta}ms nativeStart=+${startDelta}ms firstWrite=+${writeDelta}ms total=${totalStartup}ms") }
            }

            val bytesPerFrame = channelCount * bytesPerSample
            val bytesConsumed = framesWrittenResult * bytesPerFrame

            buffer.position((initialPosition + bytesConsumed).coerceAtMost(buffer.limit()))
            // Return true ONLY when buffer is fully consumed per Media3 contract
            return !buffer.hasRemaining()
        } else if (framesWrittenResult == 0) {
            // Buffer was not consumed this cycle, retry later
            return false
        }

        // Error code returned by Oboe
        val errorCode = framesWrittenResult
        runCatching { Log.w(TAG_LOG, "Oboe write returned error code $errorCode. Delegating recovery to AudioEngine.") }
        closeOboeStream()

        AudioEngine.handleStreamError(errorCode, context)

        return false
    }

    override fun playToEndOfStream() {
        isDraining = true
        fallbackSink?.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (fallbackSink != null) return fallbackSink!!.isEnded
        if (streamHandle == 0L) return true
        return (isDraining || !isPlaying) && !hasPendingData()
    }

    override fun hasPendingData(): Boolean {
        if (fallbackSink != null) return fallbackSink!!.hasPendingData()
        if (streamHandle == 0L) return false
        val hwFrames = OboeBridge.getPlaybackPositionFrames(streamHandle)
        return framesWritten > hwFrames
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        fallbackSink?.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return fallbackSink?.playbackParameters ?: playbackParameters
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        fallbackSink?.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return fallbackSink?.getSkipSilenceEnabled() ?: false
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        fallbackSink?.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes {
        return fallbackSink?.getAudioAttributes() ?: audioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        fallbackSink?.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        fallbackSink?.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        this.preferredDevice = audioDeviceInfo
        fallbackSink?.setPreferredDevice(audioDeviceInfo)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        fallbackSink?.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun enableTunnelingV21() {
        fallbackSink?.enableTunnelingV21()
    }

    override fun disableTunneling() {
        fallbackSink?.disableTunneling()
    }

    override fun setVolume(volume: Float) {
        this.volume = if (bitPerfectMode) 1.0f else volume
        fallbackSink?.setVolume(this.volume)
        if (streamHandle != 0L && !bitPerfectMode) {
            OboeBridge.setDvcVolume(streamHandle, volume.toDouble())
        }
    }

    @Synchronized
    fun reconfigureRoute(newRoute: AudioRouteCapability? = null, preferredDevice: AudioDeviceInfo? = null): Boolean {
        val oldHandle = streamHandle
        val oldDeviceId = currentStreamInfo?.deviceId ?: 0
        val targetDeviceId = preferredDevice?.id ?: 0
        runCatching { Log.i(TAG_LOG, "[OBOE_RECONFIG] oldHandle=$oldHandle oldDevice=$oldDeviceId newRoute=${newRoute?.routeType?.displayName} newDevice=$targetDeviceId") }

        this.preferredDevice = preferredDevice

        // 1. Pause and flush current stream if active
        if (streamHandle != 0L) {
            OboeBridge.pauseStream(streamHandle)
            OboeBridge.flushStream(streamHandle)
            closeOboeStream()
        }

        // 2. Clear fallback if we're trying to re-engage native stream on new route
        if (fallbackSink != null) {
            fallbackSink?.pause()
            fallbackSink?.flush()
            fallbackSink?.reset()
            fallbackSink = null
            isFallbackActive = false
        }

        // 3. Open on new physical device
        if (OboeBridge.isAvailable) {
            openOboeStream(targetDeviceId)
        }

        if (streamHandle != 0L) {
            if (isPlaying) {
                OboeBridge.startStream(streamHandle)
            }
            runCatching { Log.i(TAG_LOG, "[OBOE_RECONFIG] Successfully re-engaged native Oboe stream on new route (Handle=$streamHandle, DeviceId=${currentStreamInfo?.deviceId})") }
            return true
        } else {
            runCatching { Log.w(TAG_LOG, "[OBOE_RECONFIG] Native Oboe open failed on new route; pre-configuring fallback sink") }
            val fallback = getOrCreateFallbackSink()
            if (isPlaying) {
                fallback?.play()
            }
            return false
        }
    }

    @Synchronized
    fun recoverFromError(errorCode: Int): Boolean {
        runCatching { Log.w(TAG_LOG, "[RECOVERY] Initiating controlled sink recovery for errorCode=$errorCode") }
        closeOboeStream()

        if (OboeBridge.isAvailable) {
            openOboeStream(preferredDevice?.id ?: 0)
        }

        if (streamHandle != 0L) {
            if (isPlaying) {
                OboeBridge.startStream(streamHandle)
            }
            runCatching { Log.i(TAG_LOG, "[RECOVERY] Successfully recovered via native Oboe stream reopen (Handle=$streamHandle)") }
            return true
        } else {
            runCatching { Log.w(TAG_LOG, "[RECOVERY] Native reopen failed; falling back to DefaultAudioSink") }
            val fallback = getOrCreateFallbackSink()
            if (isPlaying) {
                fallback?.play()
            }
            return false
        }
    }

    fun setBitPerfectMode(enabled: Boolean) {
        if (this.bitPerfectMode != enabled) {
            this.bitPerfectMode = enabled
            if (streamHandle != 0L) {
                closeOboeStream()
                openOboeStream(preferredDevice?.id ?: 0)
                if (streamHandle != 0L && isPlaying) {
                    OboeBridge.startStream(streamHandle)
                }
            }
        }
    }

    private fun openOboeStream(deviceId: Int = 0) {
        if (OboeBridge.isAvailable && streamHandle == 0L) {
            val targetDevice = if (deviceId > 0) deviceId else (preferredDevice?.id ?: 0)
            val handle = OboeBridge.openStream(sampleRate, channelCount, bitPerfectMode, targetDevice)
            if (handle != 0L) {
                timeNativeOpenedMs = android.os.SystemClock.elapsedRealtime()
                streamHandle = handle
                streamGeneration = OboeBridge.getStreamGeneration(handle)
                currentActiveHandle = handle
                currentStreamInfo = OboeBridge.getNativeStreamInfo(handle)
                val exclusive = OboeBridge.isExclusive(handle)
                val actualRate = OboeBridge.getSampleRate(handle)
                runCatching { Log.i(TAG_LOG, "✦ Native Oboe Stream Opened: Handle=$streamHandle, Gen=$streamGeneration, Exclusive=$exclusive, Rate=$actualRate Hz, DeviceId=$targetDevice, BitPerfect=$bitPerfectMode ✦") }

                syncDspParameters(streamHandle)
                onExclusiveModeChanged(exclusive)
                AudioEngine.invalidate()
            }
        }
    }

    private fun syncDspParameters(handle: Long) {
        val dsp = dspProcessor ?: return
        try {
            val isBypass = bitPerfectMode || dsp.isBitPerfectBypass
            OboeBridge.setDspEnabled(handle, !isBypass && dsp.isEnabled)
            OboeBridge.setBitPerfectBypass(handle, isBypass)
            OboeBridge.setPreAmpGainDb(handle, if (isBypass) 0.0 else dsp.preAmpGainDb)
            OboeBridge.setBassBoostGainDb(handle, if (isBypass) 0.0 else dsp.bassBoostGainDb)
            OboeBridge.setTrebleGainDb(handle, if (isBypass) 0.0 else dsp.trebleGainDb)
            OboeBridge.setHarmonicExciterLevel(handle, if (isBypass) 0.0 else dsp.harmonicExciterLevel)
            OboeBridge.setClarityEnhancerGain(handle, if (isBypass) 0.0 else dsp.clarityEnhancerGain)
            OboeBridge.setStereoExpansionMultiplier(handle, if (isBypass) 1.0 else dsp.stereoExpansionMultiplier)
            OboeBridge.setDvcVolume(handle, if (isBypass) 1.0 else dsp.dvcVolume)
            OboeBridge.setDitherStrength(handle, if (isBypass) 0.0 else dsp.ditherStrength)
            OboeBridge.setOutputBitDepth(handle, dsp.outputBitDepth)
            OboeBridge.setWarmSaturationLevel(handle, if (isBypass) 0.0 else dsp.warmSaturationLevel)
            OboeBridge.setTriodeWarmthLevel(handle, if (isBypass) 0.0 else dsp.triodeWarmthLevel)
            OboeBridge.setPentodeTapeLevel(handle, if (isBypass) 0.0 else dsp.pentodeTapeLevel)
            OboeBridge.setCrossfeedLevel(handle, if (isBypass) 0.0 else dsp.crossfeedLevel)
            OboeBridge.setLimiterEnabled(handle, !isBypass && dsp.limiterEnabled)
            OboeBridge.setLimiterThresholdDb(handle, dsp.limiterThresholdDb)
            OboeBridge.setSubBassMonoEnabled(handle, !isBypass && dsp.subBassMonoEnabled)
            OboeBridge.setChannelBalance(handle, if (isBypass) 0.0 else dsp.channelBalance)
            OboeBridge.setInvertPhase(handle, !isBypass && dsp.invertPhase)
            OboeBridge.setAirPresenceGainDb(handle, if (isBypass) 0.0 else dsp.airPresenceGainDb)

            // Sync 10-band Graphic EQ & HRTF Spatial Audio from EqualizerEngine
            PlaybackService.instance?.equalizerEngine?.let { eq ->
                eq.bandLevels.value.forEachIndexed { index, level ->
                    OboeBridge.setBandGain(handle, index, if (isBypass) 0.0 else level.toDouble() / 100.0)
                }
                OboeBridge.setHrtfSpatialEnabled(handle, !isBypass && eq.hrtfSpatialEnabled.value)
                OboeBridge.setHrtfRoomSize(handle, if (isBypass) 0.0 else eq.hrtfRoomSize.value.toDouble())
            }

            // Sync Active AutoEQ PEQ Bands if enabled
            PlaybackService.instance?.autoEqEngine?.let { autoEq ->
                if (autoEq.isAutoEqEnabled.value && !isBypass) {
                    autoEq.activeProfile.value?.let { profile ->
                        OboeBridge.clearPeqBands(handle)
                        profile.bands.forEach { band ->
                            OboeBridge.addPeqBand(
                                handle = handle,
                                type = band.filterType,
                                frequency = band.frequencyHz,
                                q = band.qFactor,
                                gainDb = band.gainDb
                            )
                        }
                    }
                } else {
                    OboeBridge.clearPeqBands(handle)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG_LOG, "DSP parameter initial sync error: ${e.message}")
        }
    }

    private fun closeOboeStream() {
        val handleToClose = streamHandle
        if (handleToClose != 0L) {
            streamHandle = 0L
            streamGeneration = 0L
            if (currentActiveHandle == handleToClose) {
                currentActiveHandle = 0L
                currentStreamInfo = null
            }
            OboeBridge.closeStream(handleToClose)
            AudioEngine.invalidate()
        }
    }
}
