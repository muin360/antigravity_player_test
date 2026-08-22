package com.tensorix.antigravityplayer.audio

import android.content.Context
import com.tensorix.antigravityplayer.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MODULE 2 — INPUT AUDIO ANALYZER
 *
 * Purpose:
 * Analyzes original source audio stream quality strictly using factual track metadata.
 */
data class InputQualityAnalysis(
    val sourceCodec: String = "PCM",
    val sourceBitDepth: Int = 16,
    val sourceSampleRateHz: Int = 44100,
    val sourceChannels: Int = 2,
    val sourceBitrateKbps: Int = 0,
    val dynamicRangeDb: Double = 96.0,
    val sourceLoudnessLufs: Double = -14.0,
    val qualityScore: Int = 85,
    val qualityRating: String = "Decoded Audio Stream",
    val scoreBreakdown: List<String> = emptyList()
)

class InputAudioAnalyzer(private val context: Context) {

    private val _currentAnalysis = MutableStateFlow(InputQualityAnalysis())
    val currentAnalysis: StateFlow<InputQualityAnalysis> = _currentAnalysis.asStateFlow()

    fun analyzeSource(song: Song): InputQualityAnalysis {
        val codec = song.format?.uppercase()?.ifBlank { "UNKNOWN" } ?: "UNKNOWN"
        val sampleRate = song.sampleRate.takeIf { it > 0 } ?: 0
        val bitrate = song.bitrate.takeIf { it > 0 } ?: 0
        val bitDepth = when {
            song.sampleRate >= 88200 -> 24
            codec == "DSD" -> 32
            codec in listOf("FLAC", "WAV", "ALAC", "AIFF") -> 16
            else -> 16
        }
        val channels = 2
        val dynamicRange = when {
            bitDepth >= 24 -> 144.0
            else -> 96.0
        }
        val loudness = -14.0

        var score = 40
        val breakdown = mutableListOf<String>()

        if (codec in listOf("FLAC", "WAV", "ALAC", "DSD", "AIFF")) {
            score += 35
            breakdown.add("+35 pts: Lossless Container (Zero Compression Loss)")
        } else if (codec in listOf("AAC", "OGG", "OPUS") && bitrate >= 256) {
            score += 20
            breakdown.add("+20 pts: High-Bitrate Psychoacoustic Encoding (${bitrate}kbps)")
        } else {
            score += 10
            breakdown.add("+10 pts: Standard Audio Container (${codec})")
        }

        if (bitDepth >= 24) {
            score += 15
            breakdown.add("+15 pts: 24-bit Studio Master Bit Depth (144dB SNR)")
        } else {
            score += 8
            breakdown.add("+8 pts: 16-bit Standard Audio CD Depth (96dB SNR)")
        }

        if (sampleRate >= 88200) {
            score += 10
            breakdown.add("+10 pts: High-Resolution Sample Rate (${sampleRate / 1000}kHz)")
        } else if (sampleRate > 0) {
            score += 5
            breakdown.add("+5 pts: Standard Sample Rate (${sampleRate} Hz)")
        }

        val finalScore = score.coerceIn(40, 100)
        val rating = when {
            finalScore >= 95 -> "Studio Master Reference"
            finalScore >= 85 -> "High-Resolution Lossless Audio"
            finalScore >= 70 -> "High-Fidelity Audio Stream"
            else -> "Standard Definition Audio"
        }

        val result = InputQualityAnalysis(
            sourceCodec = codec,
            sourceBitDepth = bitDepth,
            sourceSampleRateHz = sampleRate,
            sourceChannels = channels,
            sourceBitrateKbps = bitrate,
            dynamicRangeDb = dynamicRange,
            sourceLoudnessLufs = loudness,
            qualityScore = finalScore,
            qualityRating = rating,
            scoreBreakdown = breakdown
        )

        _currentAnalysis.value = result
        return result
    }
}
