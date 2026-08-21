# 🔌 DAC INFORMATION & HARDWARE ANALYSIS SPECIFICATION

## Overview
The DAC Information Center inspects both internal mobile SoC DACs (Qualcomm WCD, Cirrus Logic, MediaTek Hi-Res, AKM) and external USB Audio Class 1.0/2.0 DACs.

---

## USB Audio Class & Hardware Inspection

1. **USB Audio Class 1.0/2.0 Descriptor Scanner**:
   - Uses `android.hardware.usb.UsbManager` to read device descriptors, endpoints, interface classes, vendor IDs, and product IDs.
   - Detects audiophile DACs from AudioQuest (DragonFly), FiiO, iFi Audio, Chord Electronics, Cayin, Moondrop, and Questyle.

2. **Supported Sample Rate Spectrum**:
   - $44.1\text{ kHz}, 48.0\text{ kHz}, 88.2\text{ kHz}, 96.0\text{ kHz}$
   - $176.4\text{ kHz}, 192.0\text{ kHz}, 352.8\text{ kHz}, 384.0\text{ kHz}$ (DXD Master)

3. **Bit Depth Capabilities**:
   - $16\text{-bit}, 24\text{-bit}, 32\text{-bit Float / Integer}$
