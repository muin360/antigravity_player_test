# 🗺️ RUNTIME SOURCE ARCHITECTURE MAP

**Target System:** Antigravity Player Zero-Trust Audio Pipeline  
**Purpose:** Explicit mapping of native Android Audio APIs, HAL strings, and driver endpoints to UI State Flows.  

---

## 1. 📡 Native Hardware & HAL Source Registry

```
┌───────────────────────────────────────────────┬─────────────────────────────────────────────────┬──────────────────────────────────────────┐
│ Native Android / Linux Driver Endpoint        │ Exact API / JNI Signature                       │ Target UI Consumers                      │
├───────────────────────────────────────────────┼─────────────────────────────────────────────────┼──────────────────────────────────────────┤
│ 1. AudioFlinger Primary Sample Rate           │ AudioManager.getProperty(                       │ Module 4 Output Rate,                    │
│                                               │   AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)     │ Module 8 Clock Comparison                │
├───────────────────────────────────────────────┼─────────────────────────────────────────────────┼──────────────────────────────────────────┤
│ 2. AudioFlinger Buffer Frame Size             │ AudioManager.getProperty(                       │ Module 4 Buffer Latency Calculation,     │
│                                               │   AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) Module 11 Jitter Buffer Bounds           │
├───────────────────────────────────────────────┼─────────────────────────────────────────────────┼──────────────────────────────────────────┤
│ 3. Audio HAL Routing Endpoints                │ AudioManager.getDevices(                        │ Module 4 Active Route Name,              │
│                                               │   AudioManager.GET_DEVICES_OUTPUTS)             │ Module 6 Bluetooth A2DP Sink             │
├───────────────────────────────────────────────┼─────────────────────────────────────────────────┼──────────────────────────────────────────┤
│ 4. Qualcomm Direct PCM HAL Parameter          │ AudioManager.getParameters("direct_pcm")        │ Module 4 Top Badge,                      │
│                                               │                                                 │ Module 7 Direct Sink Indicator           │
├───────────────────────────────────────────────┼─────────────────────────────────────────────────┼──────────────────────────────────────────┤
│ 5. Vivo Hi-Fi Kernel Driver State             │ AudioManager.getParameters("vivo_hifi_state")   │ Module 5 Top Badge,                      │
│                                               │ Settings.System.getInt("vivo_hifi_state")       │ Module 11 Hardware DAC Power State       │
├───────────────────────────────────────────────┼─────────────────────────────────────────────────┼──────────────────────────────────────────┤
│ 6. AudioTrack Direct Support                  │ AudioTrack.isDirectOutputSupported(             │ Module 8 Bit-Perfect Verification Badge, │
│                                               │   AudioFormat, AudioAttributes)                 │ Module 9 Direct Routing Profile          │
├───────────────────────────────────────────────┼─────────────────────────────────────────────────┼──────────────────────────────────────────┤
│ 7. Asahi Kasei / ESS Sabre Sysfs Nodes        │ File("/sys/class/asahi_kasei/ak4376/hifi_state")│ Module 5 DAC Architecture Name           │
├───────────────────────────────────────────────┼─────────────────────────────────────────────────┼──────────────────────────────────────────┤
│ 8. Real-time 64-bit DSP True Peak Meter       │ Audiophile64BitDspProcessor.truePeakDbfs        │ Module 13 Signal Meter Oscilloscope,     │
│                                               │                                                 │ Module 11 True-Peak Headroom Guard       │
└───────────────────────────────────────────────┴─────────────────────────────────────────────────┴──────────────────────────────────────────┘
```

---

## 2. 🔄 Runtime Execution & State Ingestion Flow

```
[Hardware Layer]
  ├── Linux ALSA Kernel (/dev/snd/pcmC0D0p)
  ├── Qualcomm SDM660 Audio HAL (audio.primary.sdm660.so)
  └── Vivo Funtouch OS Hi-Fi Service
           │
           ▼
[Android Framework Layer]
  ├── AudioManager (System Sample Rate: 48000 Hz, Output Devices)
  ├── AudioAttributes (USAGE_MEDIA, DIRECT_PCM: 0x2000)
  └── AudioTrack.isDirectOutputSupported (Direct Output Validation)
           │
           ▼
[Antigravity Telemetry Engine]
  ├── HardwareHiFiVerifier.kt (Probes all 8 endpoints with ZERO simulation)
  └── Emits: HardwareVerificationReport
           │
           ▼
[UI Layer — AudiophileInfoScreen.kt]
  ├── If isDirectOutputSupported == true  ➔ Display "DIRECT HAL ACTIVE"
  ├── If isDirectOutputSupported == false ➔ Display "AUDIOFLINGER MIXER"
  ├── If isBitPerfectVerified == true     ➔ Display "VERIFIED BIT-PERFECT"
  ├── If isBitPerfectVerified == false    ➔ Display "PROCESSED / RESAMPLED"
  └── If metric is unreadable             ➔ Display "UNKNOWN / UNAVAILABLE"
```

---

## 3. 🎯 Truth Enforcement Guarantee

Every displayed parameter in `AudiophileInfoScreen.kt` is bound 1:1 to this registry. Any metric lacking an active HAL sensor is strictly marked as **`UNKNOWN`**, **`NOT VERIFIED`**, or **`UNAVAILABLE`**.
