# 🎼 AUDIO ARCHITECTURE REPORT — Antigravity Player

**Author**: Principal Android Audio Systems Engineer & Software Architect  
**Project**: Antigravity Player ("Poweramp Killer")  
**Platform**: Native Android (Kotlin, Min SDK 26, Target SDK 34, Jetpack Compose, Media3 ExoPlayer)  
**Date**: August 2026

---

## 1. Executive Summary

Antigravity Player is an audiophile-grade native Android music player designed to surpass legacy mobile music player architectures (MediaPlayer, default AudioTrack wrappers) and match the fidelity, signal transparency, and hardware directness of professional audio players such as **Poweramp**, **Neutron Music Player**, and **USB Audio Player Pro (UAPP)**.

This report provides a comprehensive technical audit of the current audio subsystem, identifies the historical bottlenecks of Android audio architecture (`AudioFlinger`, fixed HAL sample rates, integer truncation), and documents the production architecture implemented to guarantee **Hi-Res Audio (16-bit to 32-bit float, 44.1kHz to 192kHz+)**, dynamic hardware sample-rate matching, and USB DAC routing.

---

## 2. Playback Engine Audit & Technology Matrix

| Layer / Technology | Status | Implementation in Antigravity Player |
|---|---|---|
| **MediaPlayer** | ❌ **Rejected / Obsolete** | High latency, lack of float audio support, no dynamic AudioSink configuration, poor format support. |
| **ExoPlayer (Media3 1.3.1)** | ✅ **Primary Core Decoder** | Used for high-efficiency demuxing and decoding across FLAC, ALAC, WAV, AIFF, DSD/DSF, AAC, OPUS, OGG, and MP3. |
| **AudioTrack (Native / JNI)** | ✅ **Active Hardware Sink** | Configured dynamically via `DefaultAudioSink` with `AudioFormat.ENCODING_PCM_FLOAT` (Android 10+) and `AudioAttributes.USAGE_MEDIA`. |
| **AAudio / OpenSL ES** | 🔄 **Direct Driver Target** | Targeted via Android low-latency HAL offload paths for USB DAC and wired audio endpoints. |
| **AudioEffect Chain** | ✅ **DSP Engine** | Bound to ExoPlayer `audioSessionId` for 10-band Parametric Equalizer, BassBoost, Virtualizer, and LoudnessEnhancer. Features a master **Bit-Perfect DSP Bypass switch** to completely decouple effects when pure studio bitstreams are requested. |
| **MediaSessionService** | ✅ **Service Lifecycle** | Foreground execution with `C.WAKE_MODE_LOCAL`, Android 8.0+ `NotificationChannel`, and a 15MB buffer cap (`DefaultLoadControl`) preventing Out-Of-Memory (OOM) killer terminations. |

---

## 3. Bottlenecks, Risks, & Limitations in Standard Android Audio

### 1. The AudioFlinger Mixer Bottleneck
In standard Android implementations, all audio streams pass through the system audio server (`AudioFlinger`), which:
- Resamples all PCM streams to a fixed HAL primary output rate (typically 48kHz / 16-bit).
- Converts 44.1kHz CD audio into 48kHz via software sinc interpolation, introducing aliasing and inter-modulation distortion.
- Truncates 24-bit studio masters down to 16-bit fixed-point integers.

### 2. Android 8/9 Float Output Fragility
- While `AudioFormat.ENCODING_PCM_FLOAT` was introduced in API 21, many vendor HALs (e.g., older Qualcomm, MediaTek, and OEM ROMs like vivo, Oppo, Samsung on Android 8/9) crash with `AudioSink.InitializationException` if floating-point rendering is forced.
- **Antigravity Resolution**: Dynamically gated float output on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`, gracefully falling back to PCM 16/24-bit on legacy devices.

### 3. Scoped Storage & Content URI Latency
- Android 10+ scoped storage restricts raw POSIX file descriptor access, requiring `content://` MediaStore URIs.
- **Antigravity Resolution**: Implemented dual-path URI resolvers in `LibraryScanner.kt` and `MusicController.kt`, utilizing `ContentUris` with fallback caching to avoid filesystem stalls.

---

## 4. Architectural Upgrade Vectors

1. **Hi-Res Audio Pipeline**: 32-bit Floating Point decoding with dynamic sample-rate matching up to 192kHz.
2. **AudioCapabilityManager**: Deep hardware DAC scanning via `UsbManager` and `AudioManager`.
3. **Dedicated Output Pipeline**: Live hotplug detection with USB Audio Class discovery and route priorities.
4. **Pure Bit-Perfect DSP Bypass**: 100% bitstream preservation when EQ is disabled.
5. **Audiophile Signal Path HUD**: Real-time signal flow visualization for studio-grade audio transparency.
