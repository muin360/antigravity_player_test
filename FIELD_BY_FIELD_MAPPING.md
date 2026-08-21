# 📋 FIELD-BY-FIELD TRUTH MAPPING TABLE

**Target Screen:** `AudiophileInfoScreen.kt`  
**Scope:** Complete inventory of all fields across Modules 1 through 13.  

---

| Module | UI Field Label | Current UI Value | Current Code Location | Runtime Source | Source Classification | Verification Method | Confidence Level | Required Replacement Value |
|---|---|---|---|---|---|---|---|---|
| **Module 1** | Codec | `FLAC / WAV / MP3` | `AudiophileInfoScreen.kt:228` | `track.codec` | **MediaExtractor** | File Header Inspection | 🟢 Verified | `track.codec` |
| **Module 1** | Sample Rate | `44.1 kHz` | `AudiophileInfoScreen.kt:229` | `track.sampleRateHz` | **MediaExtractor** | Header Parse | 🟢 Verified | `"${track.sampleRateHz / 1000.0} kHz"` |
| **Module 1** | Bit Depth | `16-bit / 24-bit` | `AudiophileInfoScreen.kt:230` | `track.bitDepth` | **MediaExtractor** | Header Parse | 🟢 Verified | `"${track.bitDepth}-bit"` |
| **Module 1** | Bitrate | `3100 kbps (Lossless)` | `AudiophileInfoScreen.kt:231` | `track.bitrateKbps` | **MediaExtractor** | File Size / Duration | 🟡 Fallback | `if (track.bitrateKbps > 0) "${track.bitrateKbps} kbps" else "Lossless PCM"` |
| **Module 1** | Dynamic Range | `144 dB (Master)` | `AudiophileInfoScreen.kt:236` | `track.bitDepth >= 24` | **Hardcoded String** | Theoretical bit math | 🔴 Hardcoded | `if (track.bitDepth >= 24) "144 dB (Theoretical)" else "96 dB"` |
| **Module 2** | Top Badge | `INTEGRITY: 98%` | `AudiophileInfoScreen.kt:264-272` | Synthetic formula | **Synthetic Formula** | None | 🔴 Synthetic | `"SIGNAL DECODED"` |
| **Module 2** | Dynamics | `144 dB` | `AudiophileInfoScreen.kt:285` | `track.bitDepth >= 24` | **Hardcoded String** | Theoretical bit math | 🔴 Hardcoded | `"${track.bitDepth * 6} dB (Max Quantization)"` |
| **Module 2** | Subtitle | `✓ Master-Grade Signal` | `AudiophileInfoScreen.kt:289-293` | `track.isHiRes` | **Hardcoded String** | Metadata check | 🟡 Estimated | `if (track.isHiRes) "High-Resolution Source" else "Standard Resolution"` |
| **Module 3** | Signal Stages | `10-Stage Path` | `AudiophileInfoScreen.kt:204-213` | `output.signalPathStages` | **PlaybackPipelineInspector** | Runtime Pipeline | 🟢 Verified | Verified real-time stages |
| **Module 4** | Top Badge | `DIRECT HAL ACTIVE` | `AudiophileInfoScreen.kt:336-341` | `output.activeRoute` \|\| `isVivoHiFiActive` | **Optimistic Fallback** | AudioPolicy | 🔴 Mismatch | `if (hardwareReport.isDirectOutputSupported) "DIRECT HAL ACTIVE" else "AUDIOFLINGER MIXER"` |
| **Module 4** | Active Route | `Built-in Speaker / Headset` | `AudiophileInfoScreen.kt:348-353` | `output.activeRoute?.productName` | **AudioDeviceInfo (HAL)** | `AudioManager.getDevices()` | 🟢 Verified | `output.activeRoute?.productName ?: "Built-in Audio"` |
| **Module 4** | Path Subtitle | `Direct AudioTrack HAL` | `AudiophileInfoScreen.kt:355` | `isDirectPlaybackCapable` | **Calculated** | `AudioTrack` probe | 🟢 Verified | `if (hardwareReport.isDirectOutputSupported) "Direct AudioTrack HAL" else "AudioFlinger System Mixer"` |
| **Module 4** | Output Rate | `44.1 kHz` | `AudiophileInfoScreen.kt:363` | `output.currentPlaybackSampleRate` | **Track Rate (Mistake)** | `AudioManager` probe | 🔴 Mismatch | `"${hardwareReport.actualOutputSampleRate / 1000.0} kHz"` |
| **Module 4** | Output Depth | `32-bit Float` | `AudiophileInfoScreen.kt:364` | `output.currentPlaybackBitDepth` | **AudioSink** | `DefaultAudioSink` config | 🟢 Verified | `if (output.currentPlaybackBitDepth == 32) "32-bit Float" else "${output.currentPlaybackBitDepth}-bit"` |
| **Module 4** | Buffer Latency| `12 ms` | `AudiophileInfoScreen.kt:365` | `output.latencyMs` | **Hardcoded Fallback** | Unmeasured | 🟡 Fallback | `if (output.latencyMs > 0) "${output.latencyMs} ms" else "UNAVAILABLE (HAL)"` |
| **Module 5** | Top Badge | `VIVO HI-FI ARMED` | `AudiophileInfoScreen.kt:395` | `isVivoHiFiActive` | **Optimistic Flag** | HAL parameter | 🔴 Mismatch | `if (hardwareReport.isVendorHiFiActive) "VIVO HI-FI ACTIVE" else "STANDARD AUDIO HAL"` |
| **Module 5** | DAC Name | `Vivo AK4376A / ESS Sabre` | `AudiophileInfoScreen.kt:401` | `detectedDacChipsetName` | **Build Heuristic** | Build Model / Sysfs | 🟡 Heuristic | `hardwareReport.activeDacName` |
| **Module 5** | Architecture | `32-Bit Multi-bit Delta-Sigma`| `AudiophileInfoScreen.kt:407` | `currentPlaybackBitDepth` | **Estimated String** | None | 🟡 Estimated | `if (hardwareReport.isVendorHiFiActive) "Dedicated Hi-Fi Hardware DAC" else "SoC Integrated Audio Codec"` |
| **Module 5** | Supported Rates| `16 / 24 / 32-bit` | `AudiophileInfoScreen.kt:416` | Hardcoded string | **Hardcoded** | None | 🔴 Hardcoded | `"44.1k - 192kHz (HAL)"` |
| **Module 6** | Active Codec | `LDAC / aptX-HD` | `AudiophileInfoScreen.kt:451` | `track.sampleRateHz > 48k` | **Estimated Heuristic** | Bluetooth A2DP probe | 🟡 Estimated | `AudioCapabilityManager.detectBluetoothCodec(context)` |
| **Module 6** | Link Stability| `98% (Optimal)` | `AudiophileInfoScreen.kt:468` | Hardcoded string | **Hardcoded** | None | 🔴 Hardcoded | `"UNAVAILABLE (No RSSI)"` |
| **Module 7** | Top Badge | `100% BIT-EXACT` | `AudiophileInfoScreen.kt:494` | Hardcoded string | **Hardcoded** | None | 🔴 Hardcoded | `if (hardwareReport.isBitPerfectVerified) "BIT-PERFECT ACTIVE" else "RESAMPLED / MIXED"` |
| **Module 7** | Active Hardware| `ESS Sabre DAC` | `AudiophileInfoScreen.kt:500` | Hardcoded string | **Hardcoded** | None | 🔴 Hardcoded | `"Route: ${output.activeRoute?.deviceName ?: "Default"}"` |
| **Module 7** | Fidelity Banner| `✓ 100% Studio Master` | `AudiophileInfoScreen.kt:513` | Hardcoded string | **Hardcoded** | None | 🔴 Hardcoded | `if (hardwareReport.isBitPerfectVerified) "Bit-Exact Stream (Zero DSP/Resampling)" else "Standard Mixed Signal Path"` |
| **Module 8** | Top Badge | `VERIFIED BIT-PERFECT` | `AudiophileInfoScreen.kt:546` | `isBitPerfectMode` (User Switch) | **User Switch Override**| `HardwareHiFiVerifier` | 🔴 Mismatch | `if (hardwareReport.isBitPerfectVerified) "VERIFIED BIT-PERFECT" else "PROCESSED / RESAMPLED"` |
| **Module 8** | Status Title | `Direct Crystal Clock Synced` | `AudiophileInfoScreen.kt:556` | `isBitPerfectMode` (User Switch) | **Hardcoded String** | None | 🔴 Hardcoded | `if (hardwareReport.isBitPerfectVerified) "Direct Hardware Audio Clock Active" else "AudioFlinger Software Clock Active"` |
| **Module 8** | Subtitle | `Verified: Clock matched (96kHz)`| `AudiophileInfoScreen.kt:562` | Hardcoded string with 96kHz | **Hardcoded Fake Metric**| None | 🔴 Hardcoded | `if (hardwareReport.isBitPerfectVerified) "Clock Matched: ${track.sampleRateHz}Hz == ${hardwareReport.actualOutputSampleRate}Hz" else "Clock Inactive (Mixed at ${hardwareReport.actualOutputSampleRate}Hz)"` |
| **Module 8** | Clock Match | `Bit-Exact (96kHz)` | `AudiophileInfoScreen.kt:571` | Hardcoded string | **Hardcoded** | None | 🔴 Hardcoded | `if (track.sampleRateHz == hardwareReport.actualOutputSampleRate) "Matched (${track.sampleRateHz / 1000}kHz)" else "Resampled to ${hardwareReport.actualOutputSampleRate / 1000}kHz"` |
| **Module 8** | Verification | `Certified` | `AudiophileInfoScreen.kt:574` | `isBitPerfectMode` (User Switch) | **User Switch Override**| None | 🔴 Hardcoded | `if (hardwareReport.isBitPerfectVerified) "VERIFIED" else "NOT VERIFIED"` |
| **Module 9** | Routing | `Direct Output` | `AudiophileInfoScreen.kt:624` | Hardcoded string | **Hardcoded** | None | 🔴 Hardcoded | `if (hardwareReport.isDirectOutputSupported) "Direct Output" else "AudioFlinger Mixer"` |
| **Module 11** | Health Score | `HEALTH: 100/100` | `AudiophileInfoScreen.kt:698-700` | `if (isBitPerfectMode) 100 else 98` | **Synthetic Formula** | None | 🔴 Synthetic | `"SIGNAL AUDITED"` |
| **Module 11** | Distortion | `< 0.00008% THD+N` | `AudiophileInfoScreen.kt:711` | Hardcoded string | **Hardcoded Fake Metric**| None | 🔴 Hardcoded | `"Distortion: UNAVAILABLE (No Hardware Sensor)"` |
| **Module 12** | Active Plugins| `5 PLUGINS ACTIVE` | `AudiophileInfoScreen.kt:748` | `if (isBitPerfectMode) 0 else 5` | **Hardcoded Count** | DSP processor inspection | 🟡 Estimated | `"${if (_bitPerfectMode.value) 0 else PlaybackService.instance?.dspProcessor?.activePluginCount ?: 0} ACTIVE"` |
