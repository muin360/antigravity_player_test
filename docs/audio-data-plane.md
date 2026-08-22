# Antigravity Player — Audio Data-Plane & Control-Plane Architecture

## 1. Architectural Separation

The Antigravity audio system is strictly divided into two non-interfering planes:

```
+-----------------------------------------------------------------------------------+
|                                  CONTROL PLANE                                    |
|                                                                                   |
|  [Route Events / USB / IEM] ----> [AudioOutputManager] ----> [AudioEngine]        |
|                                                                     |             |
|                                                                     v             |
|  [Vendor Probe (Async IO)] <----- [BitPerfectVerifier] <---- [ReconfigureRoute]   |
+---------------------------------------------------------------------+-------------+
                                                                      |
                                                          Non-blocking|commands
                                                                      v
+-----------------------------------------------------------------------------------+
|                                   DATA PLANE                                      |
|                                                                                   |
|  [Media3 Decoder] ---> [ByteBuffer] ---> [OboeAudioSink] ---> [JNI writeDirect]    |
|                                                                     |             |
|                                                                     v             |
|  [Android AAudio HAL] <--- [Oboe Stream] <--- [Native DSP/ASRC] <--- [PCM Scratch]|
+-----------------------------------------------------------------------------------+
```

---

## 2. Data Plane Hot Path
- **Execution Thread**: Audio Render Worker Thread (`Media3`).
- **Memory Allocation Policy**: ZERO dynamic heap allocations per buffer ($O(1)$ pre-allocated native memory).
- **JNI Bridge**: `OboeBridge.writeDirect(handle, generationId, directBuffer, offset, numBytes, numFrames, pcmEncoding, isBitPerfect)`.
- **Direct Native Unpack**: 16-bit, 24-bit, 32-bit integer, and 32-bit Float PCM unpacked directly into pre-allocated `pcmFloatScratchBuffer` in native C++.
- **Processing Hierarchy**:
  1. **Bit-Perfect Mode Active**: Bypasses all DSP filtering, applies strict passthrough resampler (when sample rates match), and writes directly to exclusive Oboe stream.
  2. **DSP Mode Active**: 64-bit double precision Biquad filtering $\rightarrow$ Tube saturation $\rightarrow$ Crossfeed $\rightarrow$ Sinc resampler (if hardware rate differs) $\rightarrow$ Padé soft-knee limiter $\rightarrow$ DVC volume $\rightarrow$ Oboe write.
- **Locking Policy**: Lock-minimized write path. Lifecycle lock is only taken during stream open, close, or flush. Real-time write checks atomic generation token and active stream pointer.

---

## 3. Control Plane
- **Single Operational Authority**: `AudioEngine`.
- **Route Orchestration**:
  - `AudioOutputManager` receives raw hardware hotplug events (`ACTION_HEADSET_PLUG`, `ACTION_USB_DEVICE_ATTACHED`, `AudioDeviceCallback`).
  - Coalesces rapid oscillations using an 80ms debounce.
  - Resolves target physical `deviceId` and triggers `AudioEngine.reconfigureRoute()`.
- **Non-Destructive Reconfiguration**:
  - Reconfiguration is serialized by `AudioEngine.routeMutex`.
  - Flushes and closes old stream $\rightarrow$ increments stream `generationId` $\rightarrow$ re-opens native stream on target `deviceId` $\rightarrow$ resumes playback.
  - `ExoPlayer` instance, active `MediaItem`, queue, index, position, and `playWhenReady` remain 100% preserved.
- **Asynchronous Vendor Probe**:
  - Vendor-specific HAL probing (Vivo, Samsung, Qualcomm) runs on `Dispatchers.IO` in the background and NEVER blocks audio initiation or the playback thread.

---

## 4. Recovery & Fallback State Machine

```
              +--------------------------+
              |          NORMAL          |
              +--------------------------+
                           |
                     (Stream Error)
                           v
              +--------------------------+
              |        RECOVERING        |
              +--------------------------+
                           |
             +-------------+-------------+
             |                           |
       (Native Reopen)             (Reopen Failed)
             v                           v
+--------------------------+  +--------------------------+
|          NORMAL          |  |     FALLBACK_ACTIVE      |
|     (Native Oboe)        |  |    (DefaultAudioSink)    |
+--------------------------+  +--------------------------+
```

- Exactly one sink is active at any time (`NATIVE_ACTIVE` xor `FALLBACK_ACTIVE`).
- Fallback sink (`DefaultAudioSink`) is pre-configured with all format, clock, listener, device, and volume parameters before first PCM buffer write.
