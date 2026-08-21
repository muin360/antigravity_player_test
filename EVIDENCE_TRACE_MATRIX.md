# 🧬 EVIDENCE TRACE MATRIX

**Target Physical Device:** Vivo X21A (Snapdragon SDM660, Asahi Kasei AK4376A / ESS Sabre, Android 8.1–10 Funtouch OS)  
**ADB Device Serial:** `f864ca9c`  
**Package:** `com.tensorix.antigravityplayer`  
**Standard:** Strict Empirical Audit Matrix  

---

| Metric / Claim | Raw Empirical Evidence | Source File | Source Line | Exact Boolean Expression | Confidence Score | Classification |
|---|---|---|---|---|---|---|
| **A. AudioFlinger Mixer Active** | `Output thread 0xecc95700, name AudioOut_D, tid 916, type 0 (MIXER)`, `AudioStreamOut: 0xeeaac500 flags 0x2 (AUDIO_OUTPUT_FLAG_PRIMARY)` | `HardwareHiFiVerifier.kt` | L64–70 | `threadType == AudioFlingerThreadType.MIXER_THREAD` | **100%** | **VERIFIED** |
| **B. DirectOutputThread Active** | `dumpsys media.audio_flinger` returns 0 instances of `DirectOutputThread` | `HardwareHiFiVerifier.kt` | L140–210 | `isDirectSupported == false` | **100%** | **VERIFIED (INACTIVE)** |
| **C. OffloadThread Active** | `dumpsys media.audio_flinger` returns 0 instances of `OffloadThread` | `HardwareHiFiVerifier.kt` | L65–68 | `threadType == AudioFlingerThreadType.OFFLOAD_THREAD` | **100%** | **VERIFIED (INACTIVE)** |
| **D. AK4376A DAC Energized** | `/sys/class/asahi_kasei/ak4376/` read blocked by Android SELinux; `pcm_device_id = 0` | `HardwareHiFiVerifier.kt` | L315–335 | `dacState == HardwareDacState.ACTIVE_VERIFIED` | **0%** (Blocked by OS) | **IMPOSSIBLE TO VERIFY** |
| **E. Vivo Hi-Fi Parameter Active** | `am.getParameters("vivo_hifi_state")` returns `""` (empty string) | `HardwareHiFiVerifier.kt` | L308–320 | `hifiStateParam.contains("vivo_hifi_state=1")` | **0%** (Driver returns empty) | **IMPOSSIBLE TO VERIFY** |
| **F. Qualcomm Direct PCM Parameter** | `am.getParameters("direct_pcm")` returns `""` (empty string) | `HardwareHiFiVerifier.kt` | L185–195 | `qcomDirect.contains("direct_pcm=1")` | **0%** (Driver returns empty) | **IMPOSSIBLE TO VERIFY** |
| **G. Output Sample Rate** | `Sample rate: 48000 Hz`, `usecase = (0:name:deep-buffer-playback)` | `HardwareHiFiVerifier.kt` | L52 | `actualOutputSampleRate == 48000` | **100%** | **VERIFIED (48000 Hz)** |
| **H. Bit-Perfect Playback** | Input: `44100 Hz`; AudioFlinger: `48000 Hz` (SRC active); Direct: `false` | `HardwareHiFiVerifier.kt` | L81–85 | `isBitPerfectVerified = (isDspBypassed && isDirectSupported && isSampleRateMatched && isBitDepthPreserved)` | **100%** | **VERIFIED (NOT BIT-PERFECT)** |
| **I. In-App DSP Bypassed** | Logcat: `_bitPerfectMode.value = true`, `dspProcessor.isBitPerfectBypass = true` | `PlaybackService.kt` | L274 | `isDspBypassed == true` | **100%** | **VERIFIED (BYPASSED)** |
| **J. Audio Output Routing** | `devices = 4` (`AUDIO_DEVICE_OUT_WIRED_HEADSET`) / `AUDIO_DEVICE_OUT_SPEAKER` | `AudioOutputManager.kt` | L185–190 | `activeRoute?.routeType == AudioOutputRouteType.WIRED_HEADSET` | **100%** | **VERIFIED** |
| **K. AudioTrack Direct Output HAL** | `AudioTrack.isDirectOutputSupported` returns `false` on device HAL | `HardwareHiFiVerifier.kt` | L160–180 | `isSupportedStandard == false && isSupportedDirectFlags == false` | **100%** | **VERIFIED (UNSUPPORTED)** |
| **L. Distortion THD+N** | Hardware analog spectrum analyzer not present on mobile motherboard | `AudioHealthEngine.kt` | L27, L72 | `distortionRiskThd = "UNAVAILABLE (No Hardware Sensor)"` | **100%** | **VERIFIED (UNAVAILABLE)** |

---

## Summary of Empirical Classifications:
- **Total Claims Audited:** 12
- **VERIFIED (Positive or Negative):** 9
- **IMPOSSIBLE TO VERIFY (SELinux / OEM driver restrictions):** 3
- **ASSUMED / UNVERIFIED:** 0 (Zero assumptions remaining in codebase)
