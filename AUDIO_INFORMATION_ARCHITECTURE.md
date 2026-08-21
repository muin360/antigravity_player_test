# 🎧 AUDIO INFORMATION ARCHITECTURE SPECIFICATION

## Overview
The Antigravity Audio Information Engine extracts, analyzes, and presents verified technical metadata for every audio track without approximation or guesswork.

---

## Technical Metadata Matrix

| Metadata Property | Extraction Method | Range / Valid Values | Audiophile Significance |
|---|---|---|---|
| **Codec** | Header Magic Bytes + MediaMetadataRetriever | FLAC, ALAC, WAV, DSD, AIFF, MP3, AAC, OGG | Identifies lossy vs. lossless compression |
| **Bit Depth** | MediaExtractor PCM Bit Depth Inspection | 16-bit, 24-bit, 32-bit Float | Dictates signal-to-noise ratio ($96\text{dB} - 1500\text{dB}$) |
| **Sample Rate** | Stream Clock Header | $44.1\text{kHz} - 384\text{kHz}$ DXD | Determines Nyquist frequency bandwidth |
| **Bitrate** | Frame Size / Duration Calculator | $128\text{ kbps} - 9216\text{ kbps}$ | Indicates stream density and bitstream throughput |
| **Bitrate Mode** | Frame-by-Frame VBR Analysis | CBR (Constant) / VBR (Variable) | Measures variable-bitrate optimization |
| **Dynamic Range** | EBU R128 Peak-to-Loudness delta | $4.0\text{ dB} - 24.0\text{ dB}$ | Quantifies musical punch and lack of brickwall limiting |
| **Integrated Loudness**| ITU-R BS.1770-4 Gating | $-36.0\text{ LUFS} - -6.0\text{ LUFS}$ | Standard for ReplayGain 2.0 volume matching |
| **Channel Layout** | AudioTrack Channel Mask | Mono (1.0), Stereo (2.0), 5.1 Surround | Validates binaural downmix / direct stereo fidelity |

---

## Real-Time Inspection Pipeline
```
Raw Audio File ➔ Header Parser ➔ MetadataRetriever ➔ AudioInformationEngine ➔ StateFlow UI HUD
```
1. **Zero Guesswork**: If metadata is missing from the stream, the engine reads raw container headers directly.
2. **Real-Time Delivery**: State is delivered via Kotlin StateFlow directly to the Audiophile Information Panel and Now Playing sheet.
