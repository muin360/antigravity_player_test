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

@UnstableApi
class OboeAudioSink(
    private val context: Context,
    private val dspProcessor: Audiophile64BitDspProcessor? = null,
    private val bitPerfectMode: Boolean = false,
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

    private val fallbackSink: DefaultAudioSink? by lazy {
        if (!OboeBridge.isAvailable || bitPerfectMode) {
             DefaultAudioSink.Builder(context)
                .setAudioProcessors(if (dspProcessor != null) arrayOf(dspProcessor) else emptyArray())
                .setEnableFloatOutput(!bitPerfectMode)
                .build()
        } else null
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
        return if (fallbackSink != null) {
            fallbackSink!!.supportsFormat(format)
        } else {
            Util.isEncodingLinearPcm(format.pcmEncoding)
        }
    }

    override fun getFormatSupport(format: Format): Int {
        return if (fallbackSink != null) {
            fallbackSink!!.getFormatSupport(format)
        } else {
            if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (fallbackSink != null) return fallbackSink!!.getCurrentPositionUs(sourceEnded)
        return (framesWritten * 1_000_000L) / sampleRate.coerceAtLeast(1)
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
        
        Log.i(TAG_LOG, "Configuring Native Oboe 64-bit Sink: $sampleRate Hz, $channelCount channels, Encoding: $pcmEncoding")
    }

    override fun play() {
        isPlaying = true
        fallbackSink?.play()
    }

    override fun handleDiscontinuity() {
        fallbackSink?.handleDiscontinuity()
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
        if (framesWrittenResult >= 0) {
            framesWritten += framesWrittenResult
            
            val bytesPerFrame = channelCount * bytesPerSample
            val bytesConsumed = framesWrittenResult * bytesPerFrame

            buffer.position((initialPosition + bytesConsumed).coerceAtMost(buffer.limit()))
            if (framesWrittenResult < numFrames) {
                // Partial write, return true to let ExoPlayer know some was consumed
                // ExoPlayer will call handleBuffer again with the remaining buffer
                return true
            }
            return true
        }

        // P0 Blocker 6: Controlled Error Handling
        val errorCode = framesWrittenResult
        Log.e(TAG_LOG, "Oboe write failed with error code: $errorCode. Initiating recovery.")
        
        // Mark stream as invalid
        closeOboeStream()
        
        // Determine if recoverable. 
        // In Oboe/AAudio, most write errors are non-recoverable without stream recreation.
        // We return false here to signal to ExoPlayer that the buffer was NOT consumed.
        // ExoPlayer will retry, and our streamHandle being 0 will trigger openOboeStream().
        
        // Update BitPerfect state in global snapshot if possible (via service)
        PlaybackService.instance?.let { service ->
            // Trigger a re-evaluation of the bit-perfect state
            service.audioOutputManager?.forceRefresh()
        }

        return false
    }

    override fun playToEndOfStream() {
        fallbackSink?.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (fallbackSink != null) return fallbackSink!!.isEnded
        return !isPlaying && framesWritten > 0
    }

    override fun hasPendingData(): Boolean {
        if (fallbackSink != null) return fallbackSink!!.hasPendingData()
        return isPlaying && streamHandle != 0L
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

    override fun pause() {
        isPlaying = false
        fallbackSink?.pause()
    }

    override fun flush() {
        framesWritten = 0
        fallbackSink?.flush()
    }

    override fun reset() {
        isPlaying = false
        framesWritten = 0
        closeOboeStream()
        fallbackSink?.reset()
    }

    override fun release() {
        closeOboeStream()
        fallbackSink?.release()
    }

    private fun openOboeStream() {
        if (OboeBridge.isAvailable && streamHandle == 0L) {
            streamHandle = OboeBridge.openStream(sampleRate, channelCount, bitPerfectMode)
            if (streamHandle != 0L) {
                currentActiveHandle = streamHandle
                currentStreamInfo = OboeBridge.getNativeStreamInfo(streamHandle)
                val exclusive = OboeBridge.isExclusive(streamHandle)
                val actualRate = OboeBridge.getSampleRate(streamHandle)
                Log.i(TAG_LOG, "✦ Native Oboe Stream Opened: Handle=$streamHandle, Exclusive=$exclusive, Rate=$actualRate Hz ✦")
                
                syncDspParameters(streamHandle)
                onExclusiveModeChanged(exclusive)
            }
        }
    }

    private fun syncDspParameters(handle: Long) {
        val dsp = dspProcessor ?: return
        try {
            OboeBridge.setDspEnabled(handle, dsp.isEnabled)
            OboeBridge.setBitPerfectBypass(handle, dsp.isBitPerfectBypass)
            OboeBridge.setPreAmpGainDb(handle, dsp.preAmpGainDb)
            OboeBridge.setBassBoostGainDb(handle, dsp.bassBoostGainDb)
            OboeBridge.setTrebleGainDb(handle, dsp.trebleGainDb)
            OboeBridge.setHarmonicExciterLevel(handle, dsp.harmonicExciterLevel)
            OboeBridge.setClarityEnhancerGain(handle, dsp.clarityEnhancerGain)
            OboeBridge.setStereoExpansionMultiplier(handle, dsp.stereoExpansionMultiplier)
            OboeBridge.setDvcVolume(handle, dsp.dvcVolume)
            OboeBridge.setDitherStrength(handle, dsp.ditherStrength)
            OboeBridge.setOutputBitDepth(handle, dsp.outputBitDepth)
            OboeBridge.setWarmSaturationLevel(handle, dsp.warmSaturationLevel)
            OboeBridge.setTriodeWarmthLevel(handle, dsp.triodeWarmthLevel)
            OboeBridge.setPentodeTapeLevel(handle, dsp.pentodeTapeLevel)
            OboeBridge.setCrossfeedLevel(handle, dsp.crossfeedLevel)
            OboeBridge.setLimiterEnabled(handle, dsp.limiterEnabled)
            OboeBridge.setLimiterThresholdDb(handle, dsp.limiterThresholdDb)
            OboeBridge.setSubBassMonoEnabled(handle, dsp.subBassMonoEnabled)
            OboeBridge.setChannelBalance(handle, dsp.channelBalance)
            OboeBridge.setInvertPhase(handle, dsp.invertPhase)
            OboeBridge.setAirPresenceGainDb(handle, dsp.airPresenceGainDb)

            // Sync 10-band Graphic EQ & HRTF Spatial Audio from EqualizerEngine
            PlaybackService.instance?.equalizerEngine?.let { eq ->
                eq.bandLevels.value.forEachIndexed { index, level ->
                    OboeBridge.setBandGain(handle, index, level.toDouble() / 100.0)
                }
                OboeBridge.setHrtfSpatialEnabled(handle, eq.hrtfSpatialEnabled.value)
                OboeBridge.setHrtfRoomSize(handle, eq.hrtfRoomSize.value.toDouble())
            }

            // Sync Active AutoEQ PEQ Bands if enabled
            PlaybackService.instance?.autoEqEngine?.let { autoEq ->
                if (autoEq.isAutoEqEnabled.value) {
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
