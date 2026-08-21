# 🛠️ ENGINEERING FIX PLAN — DIRECT PCM & BIT-PERFECT OPTIMIZATION

**Target Goal:** Maximize probability of engaging `DirectOutputThread` / `direct_pcm` on Qualcomm SDM660 (Vivo X21A) & USB DACs  
**Architecture Layer:** Media3 ExoPlayer AudioSink + Android AudioTrack HAL Integration  

---

## 1. 🏗️ Architectural Fix Strategy

```
                              [Audio Source File (FLAC / WAV / MP3)]
                                                ↓
                                   [ExoPlayer / Media3 Engine]
                                                ↓
                       ┌────────────────────────┴────────────────────────┐
                       ▼                                                 ▼
             [Standard Mode (DSP Active)]                    [Bit-Perfect / Hi-Fi Direct Mode]
             - Float Output: Enabled (32-bit Float)          - Float Output: DISABLED (Integer PCM 16/24/32)
             - AudioProcessors: [dspProcessor]               - AudioProcessors: emptyArray() (Zero DSP hooks)
             - AudioEffect Session: Attached                 - AudioEffect Session: DETACHED (Zero effect chains)
             - AudioAttributes: USAGE_MEDIA                  - AudioAttributes: FLAG_LOW_LATENCY / FLAG_DIRECT
                       ↓                                                 ↓
             [AudioFlinger MixerThread]                       [AudioPolicy: Matches direct_pcm MixPort]
             - 48000 Hz System Mixer                         - Direct Track to Qualcomm HAL / AK4376A DAC
```

---

## 2. 📝 Exact Implementation Changes

### Modification 1: Dynamic Integer PCM vs Float Output in `PlaybackService.kt`
- **Rationale:** Qualcomm HAL `direct_pcm` in `audio_policy_configuration.PD1728.xml` requires `AUDIO_FORMAT_PCM_16_BIT`, `PCM_8_24_BIT`, `PCM_24_BIT_PACKED`, or `PCM_32_BIT`. Float output must be bypassed in Bit-Perfect mode.
- **Code Change in `PlaybackService.kt`:**
  ```kotlin
  val builder = DefaultAudioSink.Builder(context)
      .setAudioProcessors(if (_bitPerfectMode.value) emptyArray() else arrayOf(dspProcessor))
  
  // Enable Float output ONLY when DSP is active; use integer PCM for Direct HAL matching
  if (!_bitPerfectMode.value && isHiFiSupported(this@PlaybackService)) {
      builder.setEnableFloatOutput(true)
  } else {
      builder.setEnableFloatOutput(false)
  }
  ```

---

### Modification 2: AudioEffect Isolation in Bit-Perfect Mode
- **Rationale:** AudioEffects force AudioPolicy into `MixerThread`. When Bit-Perfect is active, prevent session broadcast and detach equalizer.
- **Code Change in `PlaybackService.kt`:**
  ```kotlin
  if (!_bitPerfectMode.value) {
      val intent = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
          putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, currentSessionId)
          putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
      }
      runCatching { sendBroadcast(intent) }
      equalizerEngine?.attachToAudioSession(currentSessionId)
  } else {
      equalizerEngine?.release()
  }
  ```

---

### Modification 3: Native Qualcomm Direct PCM Parameter Broadcast
- **Rationale:** When starting playback, invoke Qualcomm HAL parameters with proper timing before the AudioTrack is opened.
- **Code Change in `VendorDacManager.kt`:**
  ```kotlin
  fun configureDirectPlayback(context: Context, sampleRate: Int, channels: Int, bitDepth: Int) {
      val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
      runCatching {
          audioManager.setParameters("direct_pcm=1")
          audioManager.setParameters("vivo_hifi_state=1")
          audioManager.setParameters("vivo_headset_hifi=1")
          audioManager.setParameters("sampling_rate=$sampleRate")
          audioManager.setParameters("audio_stream_direct=true")
      }
  }
  ```
