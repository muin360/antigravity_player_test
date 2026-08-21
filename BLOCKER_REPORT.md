# 🛑 COMPREHENSIVE AUDIO HAL BLOCKER REPORT

**Target Platform:** Qualcomm Snapdragon SDM660 (Vivo X21A / PD1728)  
**Audio Architecture:** Qualcomm Audio HAL v2.0 + Asahi Kasei AK4376A DAC + Android AudioPolicy  
**Auditor:** Principal Android Audio HAL & Qualcomm Systems Engineer  

---

## 1. 🔍 Summary of Empirical Hardware Discoveries

From raw inspection of `/vendor/etc/audio_policy_configuration/audio_policy_configuration.PD1728.xml` and `/vendor/etc/audio_output_policy.conf`:

```xml
<!-- Qualcomm Direct PCM MixPort Definition in Vivo PD1728 HAL Configuration -->
<mixPort name="direct_pcm" role="source" flags="AUDIO_OUTPUT_FLAG_DIRECT">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
             samplingRates="8000,11025,12000,16000,22050,24000,32000,44100,48000,64000,88200,96000,128000,176400,192000"
             channelMasks="AUDIO_CHANNEL_OUT_MONO,AUDIO_CHANNEL_OUT_STEREO..."/>
    <profile name="" format="AUDIO_FORMAT_PCM_8_24_BIT"
             samplingRates="... 44100,48000,64000,88200,96000... 192000,352800,384000" .../>
    <profile name="" format="AUDIO_FORMAT_PCM_24_BIT_PACKED"
             samplingRates="... 44100,48000,64000,88200,96000... 192000,352800,384000" .../>
    <profile name="" format="AUDIO_FORMAT_PCM_32_BIT"
             samplingRates="... 44100,48000,64000,88200,96000... 192000,352800,384000" .../>
</mixPort>
```

**Key Discovery:**
1. The hardware HAL **DOES** support `direct_pcm` with `AUDIO_OUTPUT_FLAG_DIRECT` for integer PCM formats (`PCM_16_BIT`, `PCM_24_BIT_PACKED`, `PCM_8_24_BIT`, `PCM_32_BIT`) across sample rates from $44.1\text{kHz}$ up to $384\text{kHz}$.
2. `AUDIO_FORMAT_PCM_FLOAT` is **NOT supported** by the `direct_pcm` mixPort on this Qualcomm HAL.
3. The direct routes to `Wired Headset` and `Speaker` exist, but application-level configurations were preventing AudioPolicy from selecting this mixPort.

---

## 2. 📋 Granular Blocker Breakdown

### Blocker 1: ExoPlayer Float Output Forces AudioPolicy into `MixerThread`
- **File:** [PlaybackService.kt:L231](file:///c:/Code/AntigravityPlayer/AntigravityPlayer/app/src/main/java/com/tensorix/antigravityplayer/player/PlaybackService.kt#L231)
- **Current Code:**
  ```kotlin
  if (isHiFiSupported(this@PlaybackService)) {
      builder.setEnableFloatOutput(true)
  }
  ```
- **Why It Blocks Direct Output:**
  The `direct_pcm` mixPort in `audio_policy_configuration.PD1728.xml` only defines `PCM_16_BIT`, `PCM_24_BIT_PACKED`, `PCM_8_24_BIT`, and `PCM_32_BIT`. `PCM_FLOAT` is omitted. When `enableFloatOutput(true)` is requested, Android `AudioPolicyManager::getOutputForAttr` fails to match `direct_pcm` and falls back to `primary output` / `deep_buffer` (`MIXER` at 48000 Hz).
- **Classification:** **(A) Fixable in App Code**
- **Fix:** Disable `setEnableFloatOutput(true)` when Bit-Perfect / Direct Mode is requested, enabling integer PCM (16-bit / 24-bit / 32-bit integer).

---

### Blocker 2: `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` Broadcast Invites System DSP Hooks
- **File:** [PlaybackService.kt:L278–282](file:///c:/Code/AntigravityPlayer/AntigravityPlayer/app/src/main/java/com/tensorix/antigravityplayer/player/PlaybackService.kt#L278-L282)
- **Current Code:**
  ```kotlin
  val intent = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
      putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, currentSessionId)
      putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
  }
  runCatching { sendBroadcast(intent) }
  ```
- **Why It Blocks Direct Output:**
  Broadcasting `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` instructs OEM audio effect daemons (Vivo DeepField, Waves MaxxAudio, Dirac, Qualcomm Snapdragon Audio+) to instantiate an `EffectChain` on the `audioSessionId`. In Android AudioFlinger, any session containing active AudioEffects is fundamentally ineligible for a `DirectOutputThread` or `OffloadThread`, forcing immediate routing to `AudioOut_D` (`MixerThread`).
- **Classification:** **(A) Fixable in App Code**
- **Fix:** Suppress broadcasting `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` and detach all `Equalizer` / `AudioEffect` instances whenever Bit-Perfect / Hi-Fi Direct Mode is enabled.

---

### Blocker 3: Raw HAL Flag `0x2000` Injected into Java `AudioAttributes` Bitmask
- **File:** [PlaybackService.kt:L210](file:///c:/Code/AntigravityPlayer/AntigravityPlayer/app/src/main/java/com/tensorix/antigravityplayer/player/PlaybackService.kt#L210)
- **Current Code:**
  ```kotlin
  .setFlags(if (_hiFiEnabled.value) 0x2000 /* AUDIO_OUTPUT_FLAG_DIRECT_PCM */ else 0)
  ```
- **Why It Blocks Direct Output:**
  `0x2000` is the native Qualcomm HAL flag (`AUDIO_OUTPUT_FLAG_DIRECT_PCM` in `system/audio.h`). In the Android Java framework, `AudioAttributes.Builder.setFlags()` expects framework flags (`FLAG_HW_AV_SYNC = 0x1`, `FLAG_LOW_LATENCY = 0x100`, `FLAG_DEEP_BUFFER = 0x200`). Passing `0x2000` to the Java builder has no effect on Android AudioPolicy's direct output selection.
- **Classification:** **(A) Fixable in App Code**
- **Fix:** Request `AudioAttributes.FLAG_LOW_LATENCY` (`0x100`) or configure `AudioTrack.Builder.setPerformanceMode(PERFORMANCE_MODE_LOW_LATENCY)` / `setOffloadedPlayback(true)`.

---

### Blocker 4: AudioProcessor Chain Active in `DefaultAudioSink` During Direct Playback
- **File:** [PlaybackService.kt:L227–228](file:///c:/Code/AntigravityPlayer/AntigravityPlayer/app/src/main/java/com/tensorix/antigravityplayer/player/PlaybackService.kt#L227-L228)
- **Current Code:**
  ```kotlin
  val builder = DefaultAudioSink.Builder(context)
      .setAudioProcessors(arrayOf(dspProcessor))
  ```
- **Why It Blocks Direct Output:**
  Even when `dspProcessor.isBitPerfectBypass = true`, passing audio processors into ExoPlayer's `DefaultAudioSink` forces ExoPlayer's internal pipeline into PCM modification mode, which disallows bitstream pass-through and compressed offload.
- **Classification:** **(A) Fixable in App Code**
- **Fix:** Pass an empty `arrayOf()` when Bit-Perfect mode is active.

---

### Blocker 5: SELinux Restricting Direct Kernel Sysfs DAC Inspection
- **File:** [HardwareHiFiVerifier.kt:L320](file:///c:/Code/AntigravityPlayer/AntigravityPlayer/app/src/main/java/com/tensorix/antigravityplayer/audio/HardwareHiFiVerifier.kt#L320)
- **Current Implementation:** Attempts to read `/sys/class/asahi_kasei/ak4376/hifi_state`.
- **Why It Blocks Verification:**
  Android SELinux policy `neverallow` rules block untrusted app domains (`untrusted_app`) from accessing `/sys/class/` hardware sysfs nodes.
- **Classification:** **(B) Requires Root**
- **Fix:** Handled honestly in app UI by reporting `UNKNOWN_HAL_RESTRICTED` without fabricating data.

---

### Blocker 6: OEM Settings Provider Permissions on Vivo Funtouch OS
- **File:** [VendorDacManager.kt:L185–215](file:///c:/Code/AntigravityPlayer/AntigravityPlayer/app/src/main/java/com/tensorix/antigravityplayer/audio/VendorDacManager.kt#L185-L215)
- **Current Implementation:** Attempts to write `Settings.System.putInt(cr, "vivo_hifi_state", 1)`.
- **Why It Blocks Verification:**
  Android 6.0+ requires `android.permission.WRITE_SETTINGS` or system signature to modify system settings.
- **Classification:** **(B) Requires Root or System Permission**
- **Fix:** Request `Settings.ACTION_MANAGE_WRITE_SETTINGS` intent if needed, and rely on `AudioManager.setParameters("vivo_hifi_state=1")`.
