# 🚀 FINAL ACTION LIST — STEP-BY-STEP CODE CHANGES

**Target Files:**
1. [PlaybackService.kt](file:///c:/Code/AntigravityPlayer/AntigravityPlayer/app/src/main/java/com/tensorix/antigravityplayer/player/PlaybackService.kt)
2. [VendorDacManager.kt](file:///c:/Code/AntigravityPlayer/AntigravityPlayer/app/src/main/java/com/tensorix/antigravityplayer/audio/VendorDacManager.kt)

---

## Step 1: Update `PlaybackService.kt` AudioSink Builder

### Current Implementation (Lines 227–233):
```kotlin
val builder = DefaultAudioSink.Builder(context)
    .setAudioProcessors(arrayOf(dspProcessor))

if (isHiFiSupported(this@PlaybackService)) {
    builder.setEnableFloatOutput(true)
}
```

### Actionable Fix:
```kotlin
val isBitPerfect = _bitPerfectMode.value

val builder = DefaultAudioSink.Builder(context)
    .setAudioProcessors(if (isBitPerfect) emptyArray() else arrayOf(dspProcessor))

// Float output is only used when DSP is active. Integer PCM (16/24/32) is required for Direct HAL matching.
if (!isBitPerfect && isHiFiSupported(this@PlaybackService)) {
    builder.setEnableFloatOutput(true)
} else {
    builder.setEnableFloatOutput(false)
}
```

---

## Step 2: Update `PlaybackService.kt` AudioEffect & Session Management

### Current Implementation (Lines 276–287):
```kotlin
val currentSessionId = exoPlayer.audioSessionId
if (currentSessionId != 0) {
    val intent = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
        putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, currentSessionId)
        putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
    }
    runCatching { sendBroadcast(intent) }
    logRuntimeAudioDiagnostics(currentSessionId, audioAttributes)
    if (!_bitPerfectMode.value) {
        equalizerEngine?.attachToAudioSession(currentSessionId)
    }
}
```

### Actionable Fix:
```kotlin
val currentSessionId = exoPlayer.audioSessionId
if (currentSessionId != 0) {
    if (!_bitPerfectMode.value) {
        val intent = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, currentSessionId)
            putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        }
        runCatching { sendBroadcast(intent) }
        equalizerEngine?.attachToAudioSession(currentSessionId)
    } else {
        // Explicitly release any existing AudioEffect instance to ensure AudioPolicy does not hook into session
        equalizerEngine?.release()
    }
    logRuntimeAudioDiagnostics(currentSessionId, audioAttributes)
}
```

---

## Step 3: Implement Pre-Playback Hardware Tuning in `VendorDacManager.kt`

### Add Function to `VendorDacManager.kt`:
```kotlin
fun prepareHardwareForDirectPlayback(context: Context, sampleRate: Int) {
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

---

## Step 4: Apply Changes & Test Runtime Dumpsys
1. Apply the 3 edits to `PlaybackService.kt` and `VendorDacManager.kt`.
2. Compile with `./gradlew assembleDebug`.
3. Capture new Logcat and AudioFlinger dumpsys to observe if `direct_pcm` engages.
