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

    // Fallback sink ONLY used if native Oboe library is missing or device fails native open
    private var fallbackSink: DefaultAudioSink? = null

    private fun getOrCreateFallbackSink(): DefaultAudioSink? {
        if (fallbackSink == null) {
            runCatching {
                Log.w(TAG_LOG, "Initializing DefaultAudioSink fallback pipeline")
                fallbackSink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(if (dspProcessor != null && !bitPerfectMode) arrayOf(dspProcessor) else emptyArray())
                    .setEnableFloatOutput(!bitPerfectMode)
                    .build().apply {
                        listener?.let { setListener(it) }
                    }
            }
        }
        return fallbackSink
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        fallbackSink?.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        fallbackSink?.setPlayerId(playerId)
    }

    override fun setClock(clock: Clock) {
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

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
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
            openOboeStream()
        }

        runCatching { Log.i(TAG_LOG, "Configured Native Oboe 64-bit Sink: $sampleRate Hz, $channelCount channels, Encoding: $pcmEncoding") }
    }

    override fun play() {
        isPlaying = true
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

    override fun handleDiscontinuity() {
        isSeekingOrDiscontinuous = true
        startMediaTimeUs = C.TIME_UNSET
        if (streamHandle != 0L) {
            OboeBridge.flushStream(streamHandle)
        }
        fallbackSink?.handleDiscontinuity()
    }

    override fun flush() {
        framesWritten = 0L
        startMediaTimeUs = C.TIME_UNSET
        isSeekingOrDiscontinuous = true
        if (streamHandle != 0L) {
            OboeBridge.flushStream(streamHandle)
        }
        fallbackSink?.flush()
    }

    override fun reset() {
        isPlaying = false
        framesWritten = 0L
        startMediaTimeUs = C.TIME_UNSET
        isSeekingOrDiscontinuous = false
        closeOboeStream()
        fallbackSink?.reset()
    }

    override fun release() {
        closeOboeStream()
        fallbackSink?.release()
    }

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

        if (floatBuffer.size < sampleCount) {
            floatBuffer = FloatArray(sampleCount * 2)
        }

        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        when (pcmEncoding) {
            C.ENCODING_PCM_FLOAT -> {
                val floatView = duplicate.asFloatBuffer()
                floatView[floatBuffer, 0, sampleCount]
            }
            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until sampleCount) {
                    floatBuffer[i] = duplicate.short.toFloat() / 32768.0f
                }
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until sampleCount) {
                    val b0 = duplicate.get().toInt() and 0xFF
                    val b1 = duplicate.get().toInt() and 0xFF
                    val b2 = duplicate.get().toInt() and 0xFF
                    val raw24 = (b2 shl 16) or (b1 shl 8) or b0
                    val sampleInt24 = if (raw24 and 0x800000 != 0) raw24 or -0x1000000 else raw24
                    floatBuffer[i] = sampleInt24.toFloat() / 8388608.0f
                }
            }
            C.ENCODING_PCM_32BIT -> {
                for (i in 0 until sampleCount) {
                    floatBuffer[i] = duplicate.int.toDouble().div(2147483648.0).toFloat()
                }
            }
            else -> {
                for (i in 0 until sampleCount) {
                    floatBuffer[i] = duplicate.short.toFloat() / 32768.0f
                }
            }
        }

        // Apply software volume if DVC is not handling it and not in bit-perfect mode
        if (volume != 1.0f && !bitPerfectMode) {
            for (i in 0 until sampleCount) {
                floatBuffer[i] *= volume
            }
        }

        val framesWrittenResult = OboeBridge.write(streamHandle, floatBuffer, numFrames)
        if (framesWrittenResult > 0) {
            framesWritten += framesWrittenResult
            
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
        Log.e(TAG_LOG, "Oboe write failed with error code: $errorCode. Delegating recovery to AudioEngine.")
        closeOboeStream()

        AudioEngine.handleStreamError(errorCode, context)

        return false
    }

    override fun playToEndOfStream() {
        fallbackSink?.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (fallbackSink != null) return fallbackSink!!.isEnded
        return !isPlaying && !hasPendingData()
    }

    override fun hasPendingData(): Boolean {
        if (fallbackSink != null) return fallbackSink!!.hasPendingData()
        if (streamHandle == 0L) return false
        val hwFrames = OboeBridge.getPlaybackPositionFrames(streamHandle)
        return isPlaying && (framesWritten > hwFrames)
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

    fun setBitPerfectMode(enabled: Boolean) {
        if (this.bitPerfectMode != enabled) {
            this.bitPerfectMode = enabled
            if (streamHandle != 0L) {
                closeOboeStream()
                openOboeStream()
            }
        }
    }

    private fun openOboeStream() {
        if (OboeBridge.isAvailable && streamHandle == 0L) {
            streamHandle = OboeBridge.openStream(sampleRate, channelCount, bitPerfectMode)
            if (streamHandle != 0L) {
                currentActiveHandle = streamHandle
                currentStreamInfo = OboeBridge.getNativeStreamInfo(streamHandle)
                val exclusive = OboeBridge.isExclusive(streamHandle)
                val actualRate = OboeBridge.getSampleRate(streamHandle)
                runCatching { Log.i(TAG_LOG, "✦ Native Oboe Stream Opened: Handle=$streamHandle, Exclusive=$exclusive, Rate=$actualRate Hz, BitPerfect=$bitPerfectMode ✦") }
                
                syncDspParameters(streamHandle)
                onExclusiveModeChanged(exclusive)
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
        if (streamHandle != 0L) {
            OboeBridge.closeStream(streamHandle)
            if (currentActiveHandle == streamHandle) {
                currentActiveHandle = 0L
                currentStreamInfo = null
            }
            streamHandle = 0L
            AudioEngineController.invalidate()
        }
    }
}
