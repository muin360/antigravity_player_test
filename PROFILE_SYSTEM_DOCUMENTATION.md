# 🎛️ PROFILE SYSTEM & DYNAMIC PROFILE ENGINE SPECIFICATION

## Overview
The Antigravity Profile System provides automatic, route-aware audio optimization across 16 built-in and user-customizable audiophile profiles.

---

## Profile Mapping Matrix

| Output Route Connected | Auto-Activated Profile | DSP Chain Configuration | Target Sound Signature |
|---|---|---|---|
| **USB DAC Connected** | `USB DAC Direct` | Bit-Perfect DSP Bypass | 100% Uncolored Bitstream Purity |
| **Wired Headset (3.5mm)** | `IEM Pure Reference` | Harmon Target Curve EQ + ReplayGain | Balanced Micro-Dynamic Detail |
| **Bluetooth LDAC Connected**| `LDAC Hi-Res` | High-Frequency Exciter + Soft Limiter | Wireless Hi-Res Clarity |
| **Built-in Speaker** | `Dynamic Bass Sub-Bass`| Low-Shelf Acoustic Bass Boost | Room Resonance & Fullness |
| **Studio Mastering** | `Studio Monitor Flat` | Ruler-Flat Phase-Aligned Biquad Filters | Precision Reference Monitoring |
| **Car Bluetooth / AUX** | `Car Audio Acoustics` | Midrange Vocal Lift + High-Pass Filter | Road Noise Compensation |

---

## Dynamic Profile Switching Logic
```kotlin
AudioDeviceCallback.onAudioDevicesAdded() ➔ Detect Route ➔ AutoSwitchProfileForRoute() ➔ Smooth Crossfade
```
Transitions between profiles occur seamlessly without pops, clicks, or stream interruptions.
