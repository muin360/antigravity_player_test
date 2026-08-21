# 📱 OUTPUT ANALYSIS & ROUTE INSPECTION SPECIFICATION

## Overview
Output Audio Analysis evaluates the destination audio route, output hardware capabilities, and transmission latency in real time.

---

## Route Priority Matrix

```
[External USB DAC] (Highest Priority: Direct Hardware Passthrough, Bit-Perfect 384kHz)
       ↓
[Wired Headphones / Headset (3.5mm / 4.4mm Balanced)] (High Priority: Studio Output)
       ↓
[Bluetooth LDAC / aptX HD] (High-Resolution Wireless)
       ↓
[HDMI / Line Out] (Direct Multichannel)
       ↓
[Built-in Speaker] (Acoustically Compensated)
```

---

## Output Metrics & Quality Scoring
* **Quality Score ($0-100$)**:
  * **95–100**: Bit-Perfect Direct USB DAC / Wired 3.5mm Lossless.
  * **85–94**: Bluetooth LDAC (990 kbps) / 24-bit PCM.
  * **70–84**: Standard Bluetooth AAC / aptX.
  * **$< 70$**: Standard SBC / Resampled system mixer.
* **Latency Telemetry**: Low-latency rendering pipeline constrained to $15\text{ms} - 25\text{ms}$ buffer duration.
