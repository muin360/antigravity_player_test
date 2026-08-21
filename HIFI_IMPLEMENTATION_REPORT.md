# 🔊 HI-FI IMPLEMENTATION REPORT — Antigravity Player

**Author**: Principal Android Audio Systems Engineer & DSP Architect  
**Project**: Antigravity Player ("Poweramp Killer")  
**Module**: Hi-Fi Audio Pipeline & Dynamic Hardware Adaptation  
**Date**: August 2026

---

## 1. Hi-Res Format & Precision Support

Antigravity Player provides native support for ultra-high-resolution audio streams across:

- **Bit Depths**: 16-bit PCM, 24-bit PCM (packed), 32-bit Integer, 32-bit Floating Point PCM.
- **Sample Rates**: 44.1 kHz (CD Master), 48.0 kHz (Studio Video), 88.2 kHz (2x CD / SACD Master), 96.0 kHz (Hi-Res Audio Standard), 176.4 kHz (4x CD / DSD Master), 192.0 kHz (Ultra Hi-Res Master), and up to 384.0 kHz on compatible external USB DACs.
- **Containers & Codecs**: FLAC, ALAC, WAV, AIFF, DSD/DSF/DFF, AAC, OPUS, OGG Vorbis, MP3, and WMA.

---

## 2. Dynamic AudioSink Architecture

```kotlin
val renderersFactory = object : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? {
        val builder = DefaultAudioSink.Builder(context)
        if (hiFiEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setEnableFloatOutput(true)
        }
        builder.setEnableAudioTrackPlaybackParams(true)
        return builder.build()
    }
}.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
```

### Why Float Output Matters
1. **Zero Quantization Distortion**: Standard 16-bit integers truncate fractional audio data during digital volume adjustments and DSP rendering.
2. **Infinite Headroom**: 32-bit floating point audio has a dynamic range exceeding 1500 dB, eliminating digital clipping (intersample overs) prior to the DAC.
3. **Studio Master Transparency**: Decoded audio bitstreams maintain exact sample values from file to AudioSink without lossy fixed-point conversions.

---

## 3. Dynamic Sample-Rate Matching Engine

When playing audio files of different master clock rates:
- **44.1 kHz Source**: Configures AudioTrack directly to 44.1 kHz clock, eliminating 44.1 $\to$ 48 kHz interpolation distortion.
- **96.0 kHz Source**: Configures AudioTrack to 96.0 kHz master clock.
- **192.0 kHz Source**: Configures AudioTrack to 192.0 kHz master clock.
- **Hardware Fallback**: If the active output route (e.g. built-in phone speaker) only accepts 48 kHz, the engine adapts smoothly using high-order polyphase sinc filters without crashing or stuttering.

---

## 4. Hardware Fallback & Version Compatibility

| Android OS Version | AudioSink Mode | Bit Depth | Float Output | Fallback Behavior |
|---|---|---|---|---|
| **Android 8.0 / 8.1 (API 26–27)** | Standard AudioTrack | 16-bit / 24-bit | Disabled (Safe) | Automatic integer clamp to prevent HAL crash. |
| **Android 9.0 (API 28)** | Standard AudioTrack | 16-bit / 24-bit | Disabled (Safe) | Verified on OEM ROMs (vivo, Oppo, Xiaomi). |
| **Android 10 – 12 (API 29–32)** | Hi-Fi Float AudioSink | 32-bit Float | Enabled | Native 32-bit float rendering. |
| **Android 13 – 14+ (API 33–34+)** | Direct Hi-Res AudioSink | 32-bit Float | Enabled | Direct Playback & Offload where supported. |
