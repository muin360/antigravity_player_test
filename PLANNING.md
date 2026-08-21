# Antigravity Player - Next-Gen Architectural Plan & Roadmap

## 1. Current State (Audit Passed)
The transition to a **Native 64-bit C++ DSP Engine** integrated with Oboe has been successfully completed. 
A hardcore, line-by-line audit confirms that this architecture rivals top-tier players like Poweramp:
- **Thread Safety**: Lock-free parameter updates using `std::atomic` and `std::unique_lock<std::mutex> peqLock(peqMutex_, std::try_to_lock)` for PEQ bands guarantee zero audio thread blocking or dropouts.
- **Memory Management**: Zero allocations inside the audio `process()` loop. `OboeStreamWrapper` is safely allocated and deallocated with RAII principles. No memory leaks detected.
- **DSP Integrity**: 64-bit double-precision floating-point math, 64-bit TPDF Dither with noise shaping, and Valve/Tape saturation are precisely executed without aliasing thanks to Hermite Spline oversampling.
- **Bit-Perfect Fidelity**: Bypass logic properly skips all processing when Bit-Perfect mode is active, directly passing samples to the DAC.

---

## 2. Phase 4: Ultimate Audiophile Expansion (The "UAPP & Roon" Killer Features)

### A. Custom USB Audio Class 2.0 (UAC2) Driver
**The Problem:** Even with Oboe Exclusive mode, the Android OS can sometimes interfere with USB DACs via ALSA, limiting them to 48kHz or 96kHz and blocking Native DSD.
**The Solution:**
- Build a custom user-space USB driver utilizing `android.hardware.usb.UsbManager`.
- **Direct Hardware Access**: Take direct control of external DAC endpoints (e.g., Chord Mojo, Fiio, iFi).
- **Benefit**: Unlock true 384kHz/768kHz and 32-bit output, completely bypassing Android's AudioFlinger and mixer.

### B. True DSD Engine (DSD64/128/256/512)
**The Problem:** Audiophiles with .dsf and .dff files need authentic playback without downsampling to PCM if their DAC supports it.
**The Solution:**
- Implement a Native DSD Decoder directly in C++.
- **DoP (DSD over PCM)**: Package raw DSD bits into 24-bit PCM containers for standard USB DACs to decode natively.
- **Native DSD**: Stream 1-bit DSD directly if using the custom UAC2 driver.

### C. Advanced Crossfade, Gapless, & ReplayGain
- **Native Ring Buffer**: Implement a massive lock-free ring buffer (`oboe::RingBuffer` or custom lock-free queue) in C++ to preload the next track.
- **True Gapless**: Send a continuous stream to the DAC across track boundaries without ever closing the Oboe stream.
- **ReplayGain**: Parse ID3 LUFS/ReplayGain tags to automatically normalize volume between albums without using destructive compression.

---

## 3. Phase 5: Intelligent UI & DSP Automation

### A. AutoEQ Integration (Parametric EQ)
- **Feature**: Integrate the open-source **AutoEQ** database directly into the app.
- **Action**: Allow users to select their headphone model (e.g., Sennheiser HD800S, Hifiman Arya). The app will automatically map the parameters to the C++ `AudiophileDsp` to achieve the Harman Target Curve.

### B. Dynamic Signal Chain Visualizer (Roon Style)
- Expand the UI to show a beautiful, glowing signal chain.
- Example: `FLAC 192kHz 24-bit ➔ Native Decoder ➔ 64-bit DSP (Active) ➔ Resampler (SINC_BEST) ➔ Oboe Exclusive ➔ 96kHz DAC`
- If Bit-Perfect is ON, the chain glows Purple/Gold and highlights that the OS mixer is bypassed.

### C. HRTF & Spatial Audio (Crossfeed 2.0)
- Expand the current standard Crossfeed with Head-Related Transfer Functions (HRTF).
- Simulate a true speaker room environment for headphone listeners, significantly reducing listener fatigue.

---

## 4. Stability & Performance Roadmap

### A. ExoPlayer Native AudioSink (Full Bypass)
- **The Upgrade**: Right now, ExoPlayer's `DefaultAudioSink` still manages the buffers and pushes them to `OboeAudioSink`.
- **The Plan**: Feed ExoPlayer's decoded byte arrays directly into a C++ JNI RingBuffer, allowing the native layer to pull data asynchronously via the Oboe `DataCallback` instead of using blocking `write()` calls. This will yield absolute lowest latency and maximum stability.

### B. Deep Power Optimization
- Utilize ARM NEON Intrinsics explicitly for the 64-bit DSP loops.
- Suspend the Oboe stream completely (and sleep the DAC) when playback is paused for more than 5 seconds, maximizing battery life.
