# 🎼 AUDIO PIPELINE & 7-STAGE SIGNAL FLOW DOCUMENTATION

## Overview
The Antigravity Audio Pipeline delivers transparent, studio-grade audio processing modeled after Poweramp and USB Audio Player Pro.

---

## 7-Stage Live Signal Flow Architecture

```
[1. Source File]
       ↓ (Lossless FLAC / ALAC / WAV 24-bit/96kHz)
[2. Decoder Engine]
       ↓ (Decoded to IEEE 754 32-bit Single-Precision Float PCM)
[3. HiFi Engine & Clock Coordinator]
       ↓ (Dynamic Sample-Rate Matching 44.1k - 384kHz)
[4. 64-bit Double Precision DSP Chain]
       ↓ (10-Band Biquad EQ + Low-Shelf Bass + Treble + ReplayGain + Soft Limiter)
[5. Audio Framework Interface (AAudio / Float AudioTrack)]
       ↓ (Direct AudioSink with Low-Latency Buffer Cap)
[6. Android Audio HAL]
       ↓ (Direct Hardware Passthrough / Mixer Bypass)
[7. DAC & Output Endpoint]
       ↓ (External USB DAC / 3.5mm Headphone Jack / Bluetooth LDAC)
```

---

## Processing Stage Invariants
* **Zero Quantization Distortion**: Conversion to 64-bit Double occurs before any equalizer or gain calculation.
* **Intersample Peak Limiting**: Soft-knee polynomial saturation prevents harsh digital clipping when high-gain EQ is active.
* **Bit-Perfect Bypass Mode**: When enabled, Stages 4 completely unhooks, passing the decoded bitstream directly to Stage 5 without modifying a single bit.
