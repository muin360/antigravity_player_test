# 📱 DEVICE COMPATIBILITY REPORT — Antigravity Player

**Author**: Principal Android Systems Engineer  
**Project**: Antigravity Player ("Poweramp Killer")  
**Module**: Output Routes, USB Audio Class, and Device Matrix  
**Date**: August 2026

---

## 1. Output Pipeline & Route Discovery

The dedicated `AudioCapabilityManager` and `AudioOutputManager` detect and manage the following physical and wireless audio endpoints:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       AUDIO OUTPUT HIERARCHY & ROUTING                      │
└─────────────────────────────────────────────────────────────────────────────┘

    1. USB Audio Class DACs (Highest Priority)
       └── Direct USB Audio Driver (ESS Sabre, AKM, Cirrus Logic, Realtek)
           Sample Rates: 44.1kHz, 48kHz, 88.2kHz, 96kHz, 176.4kHz, 192kHz, 384kHz
           Bit Depths: 16-bit, 24-bit packed, 32-bit Float

    2. Wired Headphones / Headsets (3.5mm / 4.4mm Balanced Jack)
       └── Quad DAC / Hi-Fi Phone DAC (LG QuadDAC, Sony Cirrus, Samsung)
           Sample Rates: 44.1kHz, 48kHz, 96kHz, 192kHz

    3. Bluetooth Audio (A2DP Wireless)
       ├── Sony LDAC (990 kbps / 96 kHz 24-bit Hi-Res Wireless)
       ├── Qualcomm aptX HD (576 kbps / 48 kHz 24-bit)
       ├── Qualcomm aptX Adaptive (96 kHz dynamic)
       ├── Apple / Standard AAC (256 kbps / 44.1 kHz)
       └── Standard SBC (328 kbps / 44.1-48 kHz)

    4. HDMI / Line Out
       └── Multi-channel Lossless PCM (192 kHz 24-bit)

    5. Built-in Phone Speaker / Earpiece
       └── System AudioFlinger Route (48 kHz 16/24-bit)
```

---

## 2. USB Audio Class Scanner Specifications

Using `android.hardware.usb.UsbManager` and `UsbConstants.USB_CLASS_AUDIO`, Antigravity Player parses USB interface descriptors on hotplug:

- **Vendor ID & Product ID Detection**: Identifies popular external audiophile hardware (FiiO, iFi Audio, Moondrop, AudioQuest DragonFly, Hidizs, Shanling, Cayin, Chord Mojo).
- **USB Audio Class 1.0 & 2.0 Compliance**: Checks asynchronous USB audio transfer endpoints for jitter-free clocking.
- **Direct Hardware Communication**: Exposes active sample rates and supported bit depths to the UI diagnostics panel.

---

## 3. Real-Time Hotplug & Lifecycle Safety

- **AudioDeviceCallback**: Live kernel event listeners trigger immediate route reconfiguration without stopping playback.
- **BroadcastReceiver Safety**: Double-hardened with `Context.RECEIVER_NOT_EXPORTED` on Android 14+ (API 34) to prevent security crashes.
- **Null-Safety & Graceful Fallbacks**: Every device property check is guarded with safe-calls (`?`), preventing runtime crashes even if vendor HALs return malformed device descriptors.
