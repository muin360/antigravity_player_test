# 📜 RAW EVIDENCE: AUDIOFLINGER & AUDIO POLICY DUMPSYS

**Capture Target:** Physical Device Attached via ADB (`f864ca9c`)  
**Target Hardware:** Vivo X21A (Qualcomm Snapdragon SDM660, Funtouch OS)  
**Commands Executed:**  
1. `adb shell dumpsys media.audio_flinger`  
2. `adb shell dumpsys media.audio_policy`  

---

## 1. 🧵 ACTIVE PLAYBACK THREADS (AudioFlinger Dumpsys)

### Output Thread 1: Primary Output (MixerThread)
```text
Output thread 0xecc95700, name AudioOut_D, tid 916, type 0 (MIXER):
  I/O handle: 13
  Standby: yes
  Sample rate: 48000 Hz
  HAL frame count: 960
  AudioStreamOut: 0xeeaac500 flags 0x2 (AUDIO_OUTPUT_FLAG_PRIMARY)
  Frames written: 3691605120
  Suspended frames: 0
  Hal stream dump:

 pcm_device_id = 0
 sample_rate = 48000
 format = 3
 usecase = (0:name:deep-buffer-playback)
 flag = 2 
 devices = 4
 config.rate = 48000
```

### Output Thread 2: Fast Output (MixerThread)
```text
Output thread 0xec903380, name AudioOut_15, tid 918, type 0 (MIXER):
  I/O handle: 21
  Standby: yes
  Sample rate: 48000 Hz
  HAL frame count: 192
  AudioStreamOut: 0xeeaac870 flags 0x4 (AUDIO_OUTPUT_FLAG_FAST)
  Frames written: 127643520
  Suspended frames: 0
  PipeSink frames written: 127643520
  Hal stream dump:
 pcm_device_id = 13
 sample_rate = 48000
 format = 3
 usecase = (1:name:low-latency-playback)
 flag = 4 
 devices = 4
 config.rate = 48000
```

### Output Thread 3: AFE Proxy Output (MixerThread)
```text
Output thread 0xec603740, name AudioOut_25, tid 919, type 0 (MIXER):
  I/O handle: 37
  Standby: yes
  Sample rate: 48000 Hz
  HAL frame count: 768
  HAL format: 0x1 (AUDIO_FORMAT_PCM_16_BIT)
  HAL buffer size: 3072 bytes
  Channel count: 2
  Channel mask: 0x00000003 (front-left, front-right)
  Processing format: 0x1 (AUDIO_FORMAT_PCM_16_BIT)
  Processing frame size: 4 bytes
  Pending config events: none
  Output device: 0 (AUDIO_DEVICE_NONE)
  Input device: 0 (AUDIO_DEVICE_NONE)
  Audio source: 0 (default)
  Normal frame count: 768
  AudioStreamOut: 0xeeaac668 flags 0 (AUDIO_OUTPUT_FLAG_NONE)
  Frames written: 0
  Suspended frames: 0
  Hal stream dump:

 primary_output: 
 standby = 1
 pcm_device_id = 0
 sample_rate = 48000
 format = 1
 usecase = (49:name:afe-proxy-playback)
 flag = 0 
 devices = 10000
 config.rate = 48000
```

---

## 2. 🔍 THREAD TYPE & STREAM EXTRACTION

| Parameter | Exact Raw String Returned by System |
|---|---|
| **Thread 1 Type** | `type 0 (MIXER)` |
| **Thread 1 Name** | `AudioOut_D, tid 916` |
| **Thread 1 Flags** | `flags 0x2 (AUDIO_OUTPUT_FLAG_PRIMARY)` |
| **Thread 1 Sample Rate** | `Sample rate: 48000 Hz` |
| **Thread 1 Format** | `format = 3` (`AUDIO_FORMAT_PCM_8_24_BIT`) |
| **Thread 1 Usecase** | `usecase = (0:name:deep-buffer-playback)` |
| **Thread 1 PCM Device ID** | `pcm_device_id = 0` |
| **Thread 1 Output Device** | `devices = 4` (`AUDIO_DEVICE_OUT_WIRED_HEADSET`) |
| **DirectOutputThread Active?** | `UNVERIFIED (0 instances found in dumpsys media.audio_flinger)` |
| **OffloadThread Active?** | `UNVERIFIED (0 instances found in dumpsys media.audio_flinger)` |

---

## 3. 📑 AUDIO POLICY MANAGER OUTPUT PROFILE (Dumpsys)

```text
Outputs dump:
  - Output 13 dump:
   Latency: 80
   Flags 00000002
   ID: 1
   Sampling rate: 48000
   Devices 00000004
```

```text
  - outputs:
      output 0:
      - name: primary output
      - Profiles:
          Profile 0:
              - format: AUDIO_FORMAT_PCM_32_BIT
      - flags: 0x0002 (AUDIO_OUTPUT_FLAG_PRIMARY)
```
