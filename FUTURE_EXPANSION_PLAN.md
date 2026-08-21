# 🚀 FUTURE EXPANSION PLAN — Antigravity Player

**Author**: Principal Audio Systems Architect & DSP Engineer  
**Project**: Antigravity Player ("Poweramp Killer")  
**Module**: Modular Extensibility & Audio DSP Roadmap  
**Date**: August 2026

---

## 1. Modular DSP Architecture Overview

Antigravity Player is architected with clean interfaces and dependency inversion, allowing new professional audio modules to be introduced without touching the core playback pipeline.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          ANTIGRAVITY MODULAR DSP CHAIN                      │
└─────────────────────────────────────────────────────────────────────────────┘

 [Source Track] ➔ [Hi-Res Decoder] ➔ [ReplayGain Normalizer] ➔ [Parametric EQ Engine]
                                                                        │
 ┌──────────────────────────────────────────────────────────────────────┴─────┐
 │                                                                            │
 ▼                                                                            ▼
[Crossfeed (Binaural Studio)] ➔ [Loudness Compensation] ➔ [Room Correction / Spatial Audio]
                                                                        │
 ┌──────────────────────────────────────────────────────────────────────┘
 │
 ▼
[Float AudioSink (Matched Sample Rate)] ➔ [USB Exclusive Mode Driver] ➔ [Hardware DAC]
```

---

## 2. Roadmap of Future Audiophile Modules

### 1. ReplayGain 2.0 & EBU R128 Loudness Normalization
- **Purpose**: Eliminates sudden volume jumps between tracks from different albums without compressing dynamic range.
- **Integration**: Can be inserted as an audio processor before the `AudioSink`, applying track and album gain tags embedded in FLAC/MP3 metadata.

### 2. Crossfeed Engine (Binaural Headphone Listening)
- **Purpose**: Simulates loudspeaker stereo acoustic blending in headphones, reducing headphone listening fatigue on vintage stereo recordings (e.g., Beatles, 1960s hard-panned jazz).
- **Techniques**: Chu Moy or Jan Meier crossfeed filtering with configurable delay (200–400 $\mu s$) and low-pass cutoffs.

### 3. USB Audio Exclusive Mode (Direct Kernel USB Access)
- **Purpose**: Direct user-space USB Audio Class 2.0 driver completely bypassing the Android OS audio server, directly configuring the USB DAC's asynchronous clock.
- **Feasibility**: Can use Android `UsbDeviceConnection` with asynchronous isochronous USB transfers (`libusb` / native C++ engine).

### 4. Room Correction & Headphone Convolution Filters
- **Purpose**: AutoEQ / Convolution impulse response (FIR/IIR) loading to calibrate frequency response curves for over 5,000 specific headphone models (Sennheiser HD650, Sony WH-1000XM5, Hifiman, Moondrop).

### 5. Spatial Audio & 3D Object Rendering
- **Purpose**: HRTF (Head-Related Transfer Function) spatial audio virtualization with low-latency head-tracking support for binaural acoustic immersion.
