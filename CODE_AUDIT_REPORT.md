# Cold Hard Code Audit Report

═══════════════════════════════════════════════════════
### 1. `OboeAudioSink.kt` — `handleBuffer()` function
File: `app/src/main/java/com/tensorix/antigravityplayer/audio/OboeAudioSink.kt`
═══════════════════════════════════════════════════════

```kotlin
126:     @WorkerThread
127:     override fun handleBuffer(
128:         buffer: ByteBuffer,
129:         presentationTimeUs: Long,
130:         encodedAccessUnitCount: Int
131:     ): Boolean {
132:         if (fallbackSink != null) {
133:             return fallbackSink!!.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
134:         }
135: 
136:         if (streamHandle == 0L) {
137:             openOboeStream()
138:         }
139: 
140:         val initialPosition = buffer.position()
141:         val remaining = buffer.remaining()
142:         if (remaining == 0) return true
143: 
144:         val bytesPerSample = if (pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
145:         val requiredSize = remaining / bytesPerSample
146:         if (floatBuffer.size < requiredSize) {
147:             floatBuffer = FloatArray(requiredSize)
148:         }
149: 
150:         val numFrames = when (pcmEncoding) {
151:             C.ENCODING_PCM_FLOAT -> {
152:                 val frames = remaining / (4 * channelCount)
153:                 val view = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
154:                 view.get(floatBuffer, 0, requiredSize)
155:                 frames
156:             }
157:             C.ENCODING_PCM_16BIT -> {
158:                 val frames = remaining / (2 * channelCount)
159:                 val view = buffer.duplicate().order(ByteOrder.nativeOrder())
160:                 for (i in 0 until requiredSize) {
161:                     floatBuffer[i] = view.short.toFloat() / 32768.0f
162:                 }
163:                 frames
164:             }
165:             else -> return false
166:         }
167: 
168:         // Apply volume scaling if needed
169:         if (volume != 1.0f) {
170:             for (i in 0 until requiredSize) {
171:                 floatBuffer[i] *= volume
172:             }
173:         }
174: 
175:         val framesWrittenResult = OboeBridge.write(streamHandle, floatBuffer, numFrames)
176:         if (framesWrittenResult >= 0) {
177:             framesWritten += framesWrittenResult
178:             
179:             // Critical Fix: Calculate precise consumed bytes to support backpressure
180:             val bytesPerFrame = channelCount * (if (pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2)
181:             val bytesConsumed = framesWrittenResult * bytesPerFrame
182: 
183:             buffer.position((initialPosition + bytesConsumed).coerceAtMost(buffer.limit()))
184:             if (framesWrittenResult < numFrames) {
185:                 Log.w(TAG, "Oboe underrun: wrote $framesWrittenResult of $numFrames frames")
186:             }
187:             return true
188:         }
189: 
190:         Log.e(TAG, "Oboe write failed, consuming buffer to avoid stalling ExoPlayer")
191:         buffer.position(buffer.limit())
192:         return true
193:     }
```

═══════════════════════════════════════════════════════
### 2. `OboeAudioSink.kt` — list ALL implemented interface methods
File: `app/src/main/java/com/tensorix/antigravityplayer/audio/OboeAudioSink.kt`
═══════════════════════════════════════════════════════

- `setListener(listener: AudioSink.Listener)` → **REAL**
- `setPlayerId(playerId: PlayerId?)` → **STUB**
- `setClock(clock: Clock)` → **STUB**
- `supportsFormat(format: Format): Boolean` → **REAL**
- `getFormatSupport(format: Format): Int` → **REAL**
- `getCurrentPositionUs(sourceEnded: Boolean): Long` → **REAL**
- `configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?)` → **REAL**
- `play()` → **REAL**
- `handleDiscontinuity()` → **STUB**
- `handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean` → **REAL**
- `playToEndOfStream()` → **STUB**
- `isEnded(): Boolean` → **STUB**
- `hasPendingData(): Boolean` → **STUB**
- `setPlaybackParameters(playbackParameters: PlaybackParameters)` → **REAL**
- `getPlaybackParameters(): PlaybackParameters` → **REAL**
- `setSkipSilenceEnabled(skipSilenceEnabled: Boolean)` → **STUB**
- `getSkipSilenceEnabled(): Boolean` → **STUB**
- `setAudioAttributes(audioAttributes: AudioAttributes)` → **REAL**
- `getAudioAttributes(): AudioAttributes` → **REAL**
- `setAudioSessionId(audioSessionId: Int)` → **STUB**
- `setAuxEffectInfo(auxEffectInfo: AuxEffectInfo)` → **STUB**
- `setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?)` → **STUB**
- `setOutputStreamOffsetUs(outputStreamOffsetUs: Long)` → **STUB**
- `enableTunnelingV21()` → **STUB**
- `disableTunneling()` → **STUB**
- `setVolume(volume: Float)` → **REAL**
- `pause()` → **REAL**
- `flush()` → **REAL**
- `reset()` → **REAL**
- `release()` → **REAL**

═══════════════════════════════════════════════════════
### 3. `OboeBridge.kt` — paste the COMPLETE file
File: `app/src/main/java/com/tensorix/antigravityplayer/audio/OboeBridge.kt`
═══════════════════════════════════════════════════════

```kotlin
1: package com.tensorix.antigravityplayer.audio
2: 
3: import android.util.Log
4: 
5: object OboeBridge {
6:     private const val TAG = "OboeBridge"
7:     
8:     var isAvailable: Boolean = false
9:         private set
10: 
11:     init {
12:         try {
13:             System.loadLibrary("antigravity_oboe")
14:             isAvailable = true
15:             Log.i(TAG, "Oboe library loaded successfully")
16:         } catch (e: UnsatisfiedLinkError) {
17:             Log.e(TAG, "Failed to load Oboe library: ${e.message}")
18:             isAvailable = false
19:         }
20:     }
21: 
22:     external fun openStream(sampleRate: Int, channelCount: Int): Long
23:     external fun write(handle: Long, audioData: FloatArray, numFrames: Int): Int
24:     external fun closeStream(handle: Long)
25:     external fun getSampleRate(handle: Long): Int
26:     external fun isExclusive(handle: Long): Boolean
27: }
```

═══════════════════════════════════════════════════════
### 4. `oboe_bridge.cpp` — paste these exact functions:
File: `app/src/main/cpp/oboe_bridge.cpp`
═══════════════════════════════════════════════════════

#### `openStream` JNI function:
```cpp
27: JNIEXPORT jlong JNICALL
28: Java_com_tensorix_antigravityplayer_audio_OboeBridge_openStream(JNIEnv *env, jobject thiz, jint sampleRate, jint channelCount) {
29:     OboeStreamWrapper *wrapper = new OboeStreamWrapper();
30:     oboe::AudioStreamBuilder builder;
31: 
32:     builder.setDirection(oboe::Direction::Output)
33:            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
34:            ->setSharingMode(oboe::SharingMode::Exclusive)
35:            ->setFormat(oboe::AudioFormat::Float)
36:            ->setSampleRate(sampleRate)
37:            ->setChannelCount(channelCount)
38:            ->setCallback(wrapper)
39:            ->setUsage(oboe::Usage::Media)
40:            ->setContentType(oboe::ContentType::Music);
41: 
42:     // Try AAudio first, then OpenSLES if it fails or is requested.
43:     // Oboe does this by default if we don't setAudioApi.
44: 
45:     oboe::Result result = builder.openStream(&wrapper->stream);
46:     if (result != oboe::Result::OK) {
47:         LOGE("Failed to open Oboe stream in Exclusive mode: %s. Retrying in Shared mode.", oboe::convertToText(result));
48:         builder.setSharingMode(oboe::SharingMode::Shared);
49:         result = builder.openStream(&wrapper->stream);
50:     }
51: 
52:     if (result == oboe::Result::OK) {
53:         result = wrapper->stream->requestStart();
54:         if (result != oboe::Result::OK) {
55:             LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
56:             wrapper->stream->close();
57:             delete wrapper;
58:             return 0;
59:         }
60: 
61:         LOGI("Oboe Stream Opened:");
62:         LOGI("  API: %s", oboe::convertToText(wrapper->stream->getAudioApi()));
63:         LOGI("  Sharing Mode: %s", (wrapper->stream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared"));
64:         LOGI("  Sample Rate: %d", wrapper->stream->getSampleRate());
65:         LOGI("  Channels: %d", wrapper->stream->getChannelCount());
66:         LOGI("  Format: %s", oboe::convertToText(wrapper->stream->getFormat()));
67:         LOGI("  Performance Mode: %s", oboe::convertToText(wrapper->stream->getPerformanceMode()));
68:         LOGI("  Buffer Size: %d frames", wrapper->stream->getBufferSizeInFrames());
69: 
70:         return reinterpret_cast<jlong>(wrapper);
71:     } else {
72:         LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
73:         delete wrapper;
74:         return 0;
75:     }
76: }
```

#### `onErrorAfterClose` implementation:
```cpp
20:     void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override {
21:         LOGE("Oboe stream error: %s", oboe::convertToText(error));
22:     }
```

═══════════════════════════════════════════════════════
### 5. `PlaybackService.kt` — exact block where `OboeAudioSink` is created and injected (with 5 lines before and after)
File: `app/src/main/java/com/tensorix/antigravityplayer/player/PlaybackService.kt`
═══════════════════════════════════════════════════════

```kotlin
308:                     val currentConfig = outputConfigManager?.getConfigForDevice(activeRouteType) ?: OutputDeviceConfig()
309:                     val isBitPerfect = _bitPerfectMode.value
310: 
311:                     Log.d("AntigravityAudioAudit", "Building Sink: activeRoute=$activeRouteType, bitPerfect=$isBitPerfect, hiFiEnabled=${_hiFiEnabled.value}")
312: 
313:                     if (com.tensorix.antigravityplayer.audio.OboeBridge.isAvailable && !isBitPerfect) {
314:                         try {
315:                             Log.i("AntigravityAudioAudit", "Using OboeAudioSink for High-Performance path")
316:                             return com.tensorix.antigravityplayer.audio.OboeAudioSink(
317:                                 context = context,
318:                                 dspProcessor = dspProcessor,
319:                                 bitPerfectMode = false,
320:                                 onExclusiveModeChanged = { exclusive ->
321:                                     _oboeMode.value = if (exclusive) "EXCLUSIVE" else "SHARED"
322:                                     Log.i("AntigravityAudioAudit", "Oboe Mode: ${_oboeMode.value}")
323:                                     com.tensorix.antigravityplayer.audio.HiFiBadgeState.updateOboeMode(exclusive)
324:                                     com.tensorix.antigravityplayer.audio.HiFiBadgeState.updateExclusive(exclusive)
325:                                 }
326:                             )
327:                         } catch (e: Exception) {
328:                             Log.e("AntigravityAudioAudit", "OboeAudioSink initialization failed, falling back to Default: ${e.message}")
329:                         }
330:                     }
331: 
332:                     dspProcessor.isTurboMode = _hiFiEnabled.value
333:                     dspProcessor.ditherStrength = if (currentConfig.ditherEnabled) 1.0 else 0.0
334:                     dspProcessor.outputBitDepth = currentConfig.bitDepth
335:                     
```

═══════════════════════════════════════════════════════
### 6. `VendorDacManager.kt` — COMPLETE `activateHardwareDac()` function
File: `app/src/main/java/com/tensorix/antigravityplayer/audio/VendorDacManager.kt`
═══════════════════════════════════════════════════════

```kotlin
42:     fun activateHardwareDac(context: Context, forceExclusive: Boolean = false): HiFiActivationResult {
43:         val manufacturer = Build.MANUFACTURER.lowercase()
44:         val brand = Build.BRAND.lowercase()
45:         val model = Build.MODEL.lowercase()
46:         val hardware = Build.HARDWARE.lowercase()
47: 
48:         Log.i(TAG, "🚀 [UNIVERSAL DAC PROBE] Manufacturer: $manufacturer, Brand: $brand, Model: $model")
49: 
50:         val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
51: 
52:         if (forceExclusive) {
53:             audioManager?.setParameters("direct_pcm=1")
54:             audioManager?.setParameters("audio_stream_direct=true")
55:             audioManager?.setParameters("hifi_mode=1")
56:         }
57: 
58:         // Try ALL known OEM HiFi activation parameters
59:         val oemParams = mapOf(
60:             "VIVO" to listOf("hifi_state=on", "hifi_dac_enable=1", "hifi_mode=1"),
61:             "SAMSUNG" to listOf("hifi_mode=on", "udp_on=1", "upscaling_mode=1"),
62:             "ONEPLUS" to listOf("hifi_dac=on", "hifi=on", "dac_mode=hifi"),
63:             "XIAOMI" to listOf("hifi_enable=1", "mi_hifi=1", "hifi_audio=on"),
64:             "SONY" to listOf("audio_output_format=hi-res", "hires_mode=on"),
65:             "LG" to listOf("hifi_dac=on", "quadbeat_hifi=1"),
66:             "MOTOROLA" to listOf("hifi_enable=true"),
67:             "AOSP" to listOf("af.fast_track_multiplier=1", "audio_hw_sync_for_session=1")
68:         )
69: 
70:         for ((oem, params) in oemParams) {
71:             for (param in params) {
72:                 try {
73:                     audioManager?.setParameters(param)
74:                 } catch (e: Exception) {
75:                     Log.e(TAG, "Failed to set $oem param $param: ${e.message}")
76:                 }
77:             }
78:         }
79: 
80:         // 1. Vivo / iQOO Hi-Fi DAC Activation
81:         if (manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") || model.contains("x21")) {
82:             activateVivoHiFi(context)
83:         }
84: 
85:         // 2. LG Quad DAC
86:         if (manufacturer.contains("lge") || brand.contains("lge")) {
87:             activateLgQuadDac(context)
88:         }
89: 
90:         // 3. Samsung UHQ
91:         if (manufacturer.contains("samsung") || brand.contains("samsung")) {
92:             activateSamsungUhq(context)
93:         }
94: 
95:         // 4. Sony Xperia Hi-Res
96:         if (manufacturer.contains("sony") || brand.contains("sony")) {
97:             activateSonyHiRes(context)
98:         }
99: 
100:         // 9. Qualcomm Snapdragon Direct PCM
101:         activateQualcommDirectParameters(context)
102: 
103:         // 10. Universal AudioSystem HAL setParameters injection
104:         injectUniversalAudioSystemParameters(context)
105: 
106:         // Verification phase
107:         var hifiConfirmed = false
108:         var confirmedParam = ""
109:         val checkParams = listOf("hifi_state", "hifi_dac", "hifi_mode", "hifi")
110:         for (p in checkParams) {
111:             try {
112:                 val value = audioManager?.getParameters(p)
113:                 if (!value.isNullOrBlank() && !value.contains("off", true) && !value.contains("0")) {
114:                     hifiConfirmed = true
115:                     confirmedParam = "$p=$value"
116:                 }
117:             } catch (e: Exception) { }
118:         }
119: 
120:         // Sync with authoritative HardwareHiFiVerifier
121:         val verifiedReport = HardwareHiFiVerifier.probeHardwareState(context)
122:         isVivoHiFiActive = verifiedReport.isVendorHiFiActive || hifiConfirmed
123:         isQualcommDirectActive = verifiedReport.isDirectOutputSupported
124:         detectedDacNameInternal = verifiedReport.activeDacName
125: 
126:         val sampleRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
127:         val framesPerBuffer = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 1024
128:         val isLowLatency = framesPerBuffer <= 256
129:         val isWired = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
130:             it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
131:             it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
132:         } ?: false
133: 
134:         return HiFiActivationResult(
135:             isHiFiConfirmed = isVivoHiFiActive,
136:             activeOem = manufacturer.uppercase(),
137:             confirmedParameter = confirmedParam,
138:             outputSampleRate = sampleRate,
139:             isLowLatencyPath = isLowLatency,
140:             isWiredConnected = isWired,
141:             isExclusiveModeActive = false
142:         )
143:     }
```

═══════════════════════════════════════════════════════
### 7. `PlaybackService.kt` — COMPLETE `reloadAudioPipeline()` function
File: `app/src/main/java/com/tensorix/antigravityplayer/player/PlaybackService.kt`
═══════════════════════════════════════════════════════

```kotlin
245:     internal fun reloadAudioPipeline() {
246:         audioScope.launch {
247:             val currentPlayer = player ?: return@launch
248:             val currentMediaItems = buildList {
249:                 val count = currentPlayer.mediaItemCount
250:                 for (i in 0 until count) {
251:                     currentPlayer.getMediaItemAt(i)?.let { add(it) }
252:                 }
253:             }
254:             val currentIndex = currentPlayer.currentMediaItemIndex
255:             val currentPosition = currentPlayer.currentPosition
256:             val playWhenReady = currentPlayer.playWhenReady
257: 
258:             currentPlayer.stop()
259:             val newPlayer = createExoPlayerInstance()
260:             withContext(Dispatchers.Main) {
261:                 mediaSession?.setPlayer(newPlayer)
262:                 currentPlayer.release()
263:                 player = newPlayer
264:                 if (currentMediaItems.isNotEmpty()) {
265:                     newPlayer.setMediaItems(currentMediaItems, currentIndex.coerceAtLeast(0), currentPosition)
266:                     newPlayer.prepare()
267:                     newPlayer.playWhenReady = playWhenReady
268:                 }
269:                 refreshAudiophileState()
270:                 showPlaybackNotification("Antigravity Player", if (playWhenReady) "Playing" else "Ready")
271:             }
272:             val hifiRes = VendorDacManager.activateHardwareDac(applicationContext)
273:             withContext(Dispatchers.Main) {
274:                 com.tensorix.antigravityplayer.audio.HiFiBadgeState.update(hifiRes)
275:             }
276:         }
277:     }
```

═══════════════════════════════════════════════════════
### 8. COMPLETE list of AudioSink interface methods required by Media3 1.3.1 and actual implementation lines from `OboeAudioSink.kt`
File: `app/src/main/java/com/tensorix/antigravityplayer/audio/OboeAudioSink.kt`
═══════════════════════════════════════════════════════

1. `setListener(listener: AudioSink.Listener)`:
```kotlin
51:     override fun setListener(listener: AudioSink.Listener) {
52:         this.listener = listener
53:         fallbackSink?.setListener(listener)
54:     }
```

2. `setPlayerId(playerId: PlayerId?)`:
```kotlin
56:     override fun setPlayerId(playerId: PlayerId?) {
57:         fallbackSink?.setPlayerId(playerId)
58:     }
```

3. `setClock(clock: Clock)`:
```kotlin
60:     override fun setClock(clock: Clock) {
61:         fallbackSink?.setClock(clock)
62:     }
```

4. `supportsFormat(format: Format): Boolean`:
```kotlin
64:     override fun supportsFormat(format: Format): Boolean {
65:         return if (fallbackSink != null) {
66:             fallbackSink!!.supportsFormat(format)
67:         } else {
68:             Util.isEncodingLinearPcm(format.pcmEncoding) && 
69:             (format.pcmEncoding == C.ENCODING_PCM_FLOAT || format.pcmEncoding == C.ENCODING_PCM_16BIT)
70:         }
71:     }
```

5. `getFormatSupport(format: Format): Int`:
```kotlin
73:     override fun getFormatSupport(format: Format): Int {
74:         return if (fallbackSink != null) {
75:             fallbackSink!!.getFormatSupport(format)
76:         } else {
77:             if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
78:         }
79:     }
```

6. `getCurrentPositionUs(sourceEnded: Boolean): Long`:
```kotlin
81:     override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
82:         if (fallbackSink != null) return fallbackSink!!.getCurrentPositionUs(sourceEnded)
83:         val positionUs = (framesWritten * 1_000_000L) / sampleRate
84:         return positionUs
85:     }
```

7. `configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?)`:
```kotlin
87:     override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
88:         if (fallbackSink != null) {
89:             fallbackSink!!.configure(inputFormat, specifiedBufferSize, outputChannels)
90:             return
91:         }
92: 
93:         val newSampleRate = inputFormat.sampleRate
94:         val newChannelCount = inputFormat.channelCount
95:         val newEncoding = inputFormat.pcmEncoding
96: 
97:         if (streamHandle != 0L && (sampleRate != newSampleRate || channelCount != newChannelCount)) {
98:             closeOboeStream()
99:         }
100: 
101:         sampleRate = newSampleRate
102:         channelCount = newChannelCount
103:         pcmEncoding = newEncoding
104:         val channels = channelCount.coerceAtLeast(1)
105:         val samplesPerFrame = channels
106:         val estimatedSamples = when (pcmEncoding) {
107:             C.ENCODING_PCM_FLOAT -> (specifiedBufferSize / 4).coerceAtLeast(samplesPerFrame)
108:             else -> (specifiedBufferSize / 2).coerceAtLeast(samplesPerFrame)
109:         }
110:         if (floatBuffer.size < estimatedSamples) {
111:             floatBuffer = FloatArray(estimatedSamples)
112:         }
113:         
114:         Log.i(TAG, "Configuring Oboe Sink: $sampleRate Hz, $channelCount channels, Encoding: $pcmEncoding")
115:     }
```

8. `play()`:
```kotlin
117:     override fun play() {
118:         isPlaying = true
119:         fallbackSink?.play()
120:     }
```

9. `handleDiscontinuity()`:
```kotlin
122:     override fun handleDiscontinuity() {
123:         fallbackSink?.handleDiscontinuity()
124:     }
```

10. `handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean`:
```kotlin
127:     @WorkerThread
128:     override fun handleBuffer(
129:         buffer: ByteBuffer,
130:         presentationTimeUs: Long,
131:         encodedAccessUnitCount: Int
132:     ): Boolean {
133:         if (fallbackSink != null) {
134:             return fallbackSink!!.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
135:         }
136: 
137:         if (streamHandle == 0L) {
138:             openOboeStream()
139:         }
140: 
141:         val initialPosition = buffer.position()
142:         val remaining = buffer.remaining()
143:         if (remaining == 0) return true
144: 
145:         val bytesPerSample = if (pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
146:         val requiredSize = remaining / bytesPerSample
147:         if (floatBuffer.size < requiredSize) {
148:             floatBuffer = FloatArray(requiredSize)
149:         }
150: 
151:         val numFrames = when (pcmEncoding) {
152:             C.ENCODING_PCM_FLOAT -> {
153:                 val frames = remaining / (4 * channelCount)
154:                 val view = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
155:                 view.get(floatBuffer, 0, requiredSize)
156:                 frames
157:             }
158:             C.ENCODING_PCM_16BIT -> {
159:                 val frames = remaining / (2 * channelCount)
160:                 val view = buffer.duplicate().order(ByteOrder.nativeOrder())
161:                 for (i in 0 until requiredSize) {
162:                     floatBuffer[i] = view.short.toFloat() / 32768.0f
163:                 }
164:                 frames
165:             }
166:             else -> return false
167:         }
168: 
169:         // Apply volume scaling if needed
170:         if (volume != 1.0f) {
171:             for (i in 0 until requiredSize) {
172:                 floatBuffer[i] *= volume
173:             }
174:         }
175: 
176:         val framesWrittenResult = OboeBridge.write(streamHandle, floatBuffer, numFrames)
177:         if (framesWrittenResult >= 0) {
178:             framesWritten += framesWrittenResult
179:             
180:             // Critical Fix: Calculate precise consumed bytes to support backpressure
181:             val bytesPerFrame = channelCount * (if (pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2)
182:             val bytesConsumed = framesWrittenResult * bytesPerFrame
183: 
184:             buffer.position((initialPosition + bytesConsumed).coerceAtMost(buffer.limit()))
185:             if (framesWrittenResult < numFrames) {
186:                 Log.w(TAG, "Oboe underrun: wrote $framesWrittenResult of $numFrames frames")
187:             }
188:             return true
189:         }
190: 
191:         Log.e(TAG, "Oboe write failed, consuming buffer to avoid stalling ExoPlayer")
192:         buffer.position(buffer.limit())
193:         return true
194:     }
```

11. `playToEndOfStream()`:
```kotlin
195:     override fun playToEndOfStream() {
196:         fallbackSink?.playToEndOfStream()
197:     }
```

12. `isEnded(): Boolean`:
```kotlin
199:     override fun isEnded(): Boolean {
200:         return fallbackSink?.isEnded ?: true
201:     }
```

13. `hasPendingData(): Boolean`:
```kotlin
203:     override fun hasPendingData(): Boolean {
204:         return fallbackSink?.hasPendingData() ?: false
205:     }
```

14. `setPlaybackParameters(playbackParameters: PlaybackParameters)`:
```kotlin
207:     override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
208:         this.playbackParameters = playbackParameters
209:         fallbackSink?.setPlaybackParameters(playbackParameters)
210:     }
```

15. `getPlaybackParameters(): PlaybackParameters`:
```kotlin
212:     override fun getPlaybackParameters(): PlaybackParameters {
213:         return fallbackSink?.getPlaybackParameters() ?: playbackParameters
214:     }
```

16. `setSkipSilenceEnabled(skipSilenceEnabled: Boolean)`:
```kotlin
216:     override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
217:         fallbackSink?.setSkipSilenceEnabled(skipSilenceEnabled)
218:     }
```

17. `getSkipSilenceEnabled(): Boolean`:
```kotlin
220:     override fun getSkipSilenceEnabled(): Boolean {
221:         return fallbackSink?.getSkipSilenceEnabled() ?: false
222:     }
```

18. `setAudioAttributes(audioAttributes: AudioAttributes)`:
```kotlin
224:     override fun setAudioAttributes(audioAttributes: AudioAttributes) {
225:         this.audioAttributes = audioAttributes
226:         fallbackSink?.setAudioAttributes(audioAttributes)
227:     }
```

19. `getAudioAttributes(): AudioAttributes`:
```kotlin
229:     override fun getAudioAttributes(): AudioAttributes {
230:         return fallbackSink?.getAudioAttributes() ?: audioAttributes
231:     }
```

20. `setAudioSessionId(audioSessionId: Int)`:
```kotlin
233:     override fun setAudioSessionId(audioSessionId: Int) {
234:         fallbackSink?.setAudioSessionId(audioSessionId)
235:     }
```

21. `setAuxEffectInfo(auxEffectInfo: AuxEffectInfo)`:
```kotlin
237:     override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
238:         fallbackSink?.setAuxEffectInfo(auxEffectInfo)
239:     }
```

22. `setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?)`:
```kotlin
241:     override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
242:         fallbackSink?.setPreferredDevice(audioDeviceInfo)
243:     }
```

23. `setOutputStreamOffsetUs(outputStreamOffsetUs: Long)`:
```kotlin
245:     override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
246:         fallbackSink?.setOutputStreamOffsetUs(outputStreamOffsetUs)
247:     }
```

24. `enableTunnelingV21()`:
```kotlin
249:     override fun enableTunnelingV21() {
250:         fallbackSink?.enableTunnelingV21()
251:     }
```

25. `disableTunneling()`:
```kotlin
253:     override fun disableTunneling() {
254:         fallbackSink?.disableTunneling()
255:     }
```

26. `setVolume(volume: Float)`:
```kotlin
257:     override fun setVolume(volume: Float) {
258:         this.volume = volume
259:         fallbackSink?.setVolume(volume)
260:     }
```

27. `pause()`:
```kotlin
262:     override fun pause() {
263:         isPlaying = false
264:         fallbackSink?.pause()
265:     }
```

28. `flush()`:
```kotlin
267:     override fun flush() {
268:         framesWritten = 0
269:         fallbackSink?.flush()
270:     }
```

29. `reset()`:
```kotlin
272:     override fun reset() {
273:         isPlaying = false
274:         framesWritten = 0
275:         closeOboeStream()
276:         fallbackSink?.reset()
277:     }
```

30. `release()`:
```kotlin
279:     override fun release() {
280:         closeOboeStream()
281:         fallbackSink?.release()
282:     }
```

═══════════════════════════════════════════════════════
### Question Answers
═══════════════════════════════════════════════════════

**Q1: Does `handleBuffer()` have any return path that does NOT call `OboeBridge.write()`?**
**YES**
Lines from `OboeAudioSink.kt`:
```kotlin
132:         if (fallbackSink != null) {
133:             return fallbackSink!!.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
134:         }
```
```kotlin
142:         if (remaining == 0) return true
```
```kotlin
165:             else -> return false
```

---

**Q2: Does `configure()` close and reopen the Oboe stream when `sampleRate` changes?**
**NO** (It closes the stream, but does not reopen it in `configure()`. Reopening is deferred to `handleBuffer()`.)
Lines from `OboeAudioSink.kt`:
```kotlin
97:         if (streamHandle != 0L && (sampleRate != newSampleRate || channelCount != newChannelCount)) {
98:             closeOboeStream()
99:         }
```
Stream opening occurs in `handleBuffer()`:
```kotlin
136:         if (streamHandle == 0L) {
137:             openOboeStream()
138:         }
```

---

**Q3: Is there any code path where the app could reach `OboeAudioSink` without `OboeBridge.isAvailable` being true?**
**NO**
Lines from `PlaybackService.kt`:
```kotlin
313:                     if (com.tensorix.antigravityplayer.audio.OboeBridge.isAvailable && !isBitPerfect) {
314:                         try {
315:                             Log.i("AntigravityAudioAudit", "Using OboeAudioSink for High-Performance path")
316:                             return com.tensorix.antigravityplayer.audio.OboeAudioSink(
```
Lines from `OboeAudioSink.kt`:
```kotlin
43:         if (!OboeBridge.isAvailable || bitPerfectMode) {
44:              DefaultAudioSink.Builder(context)
45:                 .setAudioProcessors(if (dspProcessor != null) arrayOf(dspProcessor) else emptyArray())
46:                 .setEnableFloatOutput(!bitPerfectMode)
47:                 .build()
48:         } else null
```
```kotlin
285:         if (OboeBridge.isAvailable && streamHandle == 0L) {
286:             streamHandle = OboeBridge.openStream(sampleRate, channelCount)
```

---

**Q4: What happens if `OboeBridge.write()` returns -1?**
Exact error handling code from `OboeAudioSink.kt` (lines 190-192):
```kotlin
190:         Log.e(TAG, "Oboe write failed, consuming buffer to avoid stalling ExoPlayer")
191:         buffer.position(buffer.limit())
192:         return true
```

---

**Q5: Is `reloadAudioPipeline()` guaranteed to run off main thread?**
**YES**
Dispatcher declaration from `PlaybackService.kt` (line 86):
```kotlin
86:     private val audioScope = CoroutineScope(Dispatchers.IO + Job())
```
Usage in `reloadAudioPipeline()` from `PlaybackService.kt` (line 246):
```kotlin
246:         audioScope.launch {
```
