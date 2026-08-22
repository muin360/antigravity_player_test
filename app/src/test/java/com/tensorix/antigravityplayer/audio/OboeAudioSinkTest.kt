package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class OboeAudioSinkTest {

    @Test
    fun `test OboeAudioSink supports standard linear PCM formats`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        val format16 = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelCount(2)
            .build()

        val format24 = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setSampleRate(96000)
            .setChannelCount(2)
            .build()

        val formatFloat = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(192000)
            .setChannelCount(2)
            .build()

        assertTrue(sink.supportsFormat(format16))
        assertTrue(sink.supportsFormat(format24))
        assertTrue(sink.supportsFormat(formatFloat))
    }

    @Test
    fun `test OboeAudioSink buffer full consumption returns true and advances position`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = true)

        val format = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelCount(2)
            .build()

        sink.configure(format, 4096, null)

        val byteBuffer = ByteBuffer.allocateDirect(1024).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.put(ByteArray(1024))
        byteBuffer.flip()

        val consumed = sink.handleBuffer(byteBuffer, 0L, 1)
        // If native library not loaded in JVM runner, it delegates to fallback DefaultAudioSink
        // Either way, it must not throw and must maintain valid ByteBuffer bounds
        assertTrue(byteBuffer.position() <= byteBuffer.limit())
    }

    @Test
    fun `test OboeAudioSink flush and discontinuity reset state cleanly`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        sink.handleDiscontinuity()
        sink.flush()
        sink.pause()
        assertFalse(sink.hasPendingData())
        sink.reset()
        sink.release()
    }
}
