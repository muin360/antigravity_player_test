# 📶 BLUETOOTH AUDIO INTELLIGENCE SPECIFICATION

## Overview
Detects, monitors, and evaluates Bluetooth wireless audio codecs and connection stability in real time.

---

## Supported Bluetooth Codec Hierarchy

| Codec | Bitrate Bandwidth | Max Sample Rate | Max Bit Depth | Audiophile Rating |
|---|---|---|---|---|
| **LDAC** | $990\text{ kbps} / 660\text{ kbps} / 330\text{ kbps}$ | $96.0\text{ kHz}$ | 24-bit | ⭐⭐⭐⭐⭐ Near-Lossless Reference |
| **aptX HD** | $576\text{ kbps}$ | $48.0\text{ kHz}$ | 24-bit | ⭐⭐⭐⭐ Studio Wireless |
| **aptX Adaptive**| $279\text{ kbps} - 420\text{ kbps}$ (Dynamic) | $96.0\text{ kHz}$ | 24-bit | ⭐⭐⭐⭐ Dynamic Low Latency |
| **LC3** | $345\text{ kbps}$ (LE Audio) | $48.0\text{ kHz}$ | 32-bit Float | ⭐⭐⭐⭐ Modern High Efficiency |
| **AAC** | $256\text{ kbps} - 320\text{ kbps}$ | $44.1\text{ kHz}$ | 16-bit | ⭐⭐⭐ Apple / Standard Hi-Fi |
| **SBC** | $328\text{ kbps}$ (Sub-band) | $44.1\text{ kHz}$ | 16-bit | ⭐⭐ Standard Fallback |

---

## Degradation & Downgrade Warnings
* **Active Alert**: If a device falls back from LDAC/aptX HD to SBC, the Audiophile Information Panel flags `Lossy Bluetooth Codec Compression Active`, notifying the user to check Bluetooth Developer Options for bitrate selection.
