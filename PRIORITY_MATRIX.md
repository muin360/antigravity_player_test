# 📊 PRIORITY & FEASIBILITY MATRIX

**Evaluation Metric:** Probability of achieving direct hardware output and bit-exact playback  
**Target Device:** Qualcomm Snapdragon SDM660 (Vivo X21A / PD1728)  

---

| Fix / Engineering Action | Feasibility Tier | Implementation Effort | Probability of Success | Expected Impact |
|---|---|---|---|---|
| **1. Disable Float Output in Bit-Perfect Mode** | **(A) App Code** | Low (5 mins) | **95%** | Enables AudioPolicy matching against `direct_pcm` integer formats (`PCM_16_BIT`, `PCM_24_BIT_PACKED`). |
| **2. Suppress `ACTION_OPEN_AUDIO_EFFECT` Broadcast** | **(A) App Code** | Low (5 mins) | **90%** | Prevents system equalizer daemons (Waves, DeepField) from hijacking audio session to `MixerThread`. |
| **3. Empty AudioProcessor Array in Direct Mode** | **(A) App Code** | Low (5 mins) | **90%** | Allows pure bitstream transfer through ExoPlayer AudioSink without forced internal conversion. |
| **4. External USB Audio Class 2.0 DAC Direct Mode** | **(A) App Code** | Already Supported | **100%** | 100% Bit-Perfect direct hardware output bypassing Android AudioFlinger up to 384kHz DXD. |
| **5. Pre-playback Qualcomm HAL Parameter Injection** | **(A) App Code** | Low (10 mins) | **75%** | Signals Qualcomm kernel audio driver before AudioTrack initialization. |
| **6. Write to System Settings (`vivo_hifi_state`)** | **(B) Requires Permission** | Medium | **40%** | Requires user granting `WRITE_SETTINGS` via system settings prompt. |
| **7. Reading `/sys/class/asahi_kasei/ak4376/`** | **(B) Requires Root** | N/A | **0% (App Code)** / **100% (Root)** | Android SELinux blocks non-root access to kernel sysfs nodes. |
| **8. Patching AudioPolicy XML (`direct_pcm`)** | **(C) Requires Custom ROM** | High | **100% (ROM Mod)** | Modifying `/vendor/etc/audio_policy_configuration.xml` requires system partition write. |

---

## 🎯 Strategic Recommendation

1. **Immediate Action:** Apply **Fixes 1, 2, and 3** in `PlaybackService.kt`. These remove the exact software blockers that forced AudioPolicy to choose `MixerThread` instead of `direct_pcm`.
2. **Honest Telemetry Standard:** Maintain strict empirical reporting in `AudiophileInfoScreen.kt` so that if `direct_pcm` engages, it is displayed with 100% proof.
