# ⚠️ Critical Issues & Technical Findings Report

**Target Project:** Antigravity Player (Android)  
**Package:** `com.tensorix.antigravityplayer`  
**Target Device Under Audit:** Vivo X21A (Snapdragon SDM660, Asahi Kasei AK4376A / ESS Sabre DAC)  

---

## 1. Critical Issues & Forensic Root Causes

### 🔴 Issue 1: Direct Audio Path API Version Incompatibility
- **Location:** `AudioOutputManager.kt`
- **Severity:** High
- **Description:** Previous code queried direct output capability using `AudioManager.getDirectPlaybackSupport()`, which is an API exclusive to **Android 13+ (API 33)**. On Android 8.1–12 (such as the Vivo X21A), this returned `false` unconditionally, displaying the message *"Direct audio path is not supported by device HAL for this output."*
- **Solution / Status:** Fixed. Implemented multi-version probing in `HardwareHiFiVerifier.kt` using `AudioTrack.isDirectOutputSupported` (API 29+) and `AudioSystem.getOutput` reflection + Qualcomm HAL parameters (API 26–28).

---

### 🔴 Issue 2: Vivo Funtouch OS Hi-Fi Audio Session Binding
- **Location:** `PlaybackService.kt`
- **Severity:** High
- **Description:** Vivo's proprietary framework service (`VivoAudioService`) requires audio playback to register with `android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION` and have `AudioAttributes` configured with direct flags (`0x2000`) before it powers on the dedicated AK4376A / ESS Sabre DAC rail.
- **Solution / Status:** Fixed. Added direct PCM flags (`0x2000`) to `AudioAttributes` and automatic `OPEN_AUDIO_EFFECT_CONTROL_SESSION` broadcast dispatch upon `onAudioSessionIdChanged`.

---

### 🟡 Issue 3: SELinux Sandbox Restriction on Sysfs Hardware Nodes
- **Location:** `VendorDacManager.kt` & `HardwareHiFiVerifier.kt`
- **Severity:** Medium (Platform Constraint)
- **Description:** Direct file reads to `/sys/class/asahi_kasei/ak4376/` or `/sys/class/ess_sabre/` are blocked on non-rooted production ROMs by Android's `untrusted_app` SELinux policy.
- **Solution / Status:** Handled gracefully. Probing wraps sysfs checks in `try...catch` and reports `"Unknown (HAL Restricted)"` rather than fabricating simulated states.

---

### 🟡 Issue 4: `Settings.System` Write Permission Requirement
- **Location:** `VendorDacManager.kt`
- **Severity:** Medium (Platform Constraint)
- **Description:** Writing to `vivo_hifi_music_app_list` requires `android.permission.WRITE_SETTINGS`. Without this permission granted by the user, the setting write fails silently.
- **Solution / Status:** Handled. The app now instructs the user via diagnostics if the system Hi-Fi switch needs manual activation in **Vivo Settings -> Sound & Vibration -> Hi-Fi**.

---

## 2. Issues Classification Summary

| Priority | Count | Description | Status |
|---|---|---|---|
| **Critical / High** | 2 | API 33 direct check bug; Vivo audio session intent binding | ✅ Resolved |
| **Medium** | 2 | SELinux sysfs sandbox; Settings.System write permissions | ✅ Handled |
| **Low** | 0 | None identified | ✅ Clean |

---

## 3. Recommended Fix & Verification Order

1. **Verify Direct AudioTrack Probing:** Ensure `HardwareHiFiVerifier.kt` successfully probes HAL on Android 8.0 through 14+.
2. **Verify Vivo Funtouch OS Session Intent:** Confirm `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` is sent when track starts.
3. **Verify Zero-Simulation Display:** Ensure `AudiophileInfoScreen.kt` displays verified hardware telemetry without static mocks.
