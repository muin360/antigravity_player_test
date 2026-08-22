#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <unordered_map>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cinttypes>
#include "dsp/audiophile_dsp.h"
#include "resampler/audiophile_resampler.h"
#include "dsd/dsd_engine.h"

#define LOG_TAG "OboeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

class OboeStreamWrapper : public oboe::AudioStreamErrorCallback, public std::enable_shared_from_this<OboeStreamWrapper> {
public:
    uint64_t generationId = 0;
    std::atomic<bool> isActive{true};

    oboe::AudioStream *stream = nullptr;
    antigravity::AudiophileDsp dsp;
    antigravity::AudiophileResampler resampler;
    antigravity::DsdEngine dsd;

    int32_t configuredSampleRate = 48000;
    int32_t configuredChannelCount = 2;

    std::vector<float> pcmFloatScratchBuffer;
    std::vector<float> resampleBuffer;

    std::mutex lifecycleMutex;

    std::atomic<int64_t> atomicFramesWritten{0};
    std::atomic<int64_t> atomicTimestampUs{0};
    std::atomic<int64_t> atomicPositionFrames{0};
    std::atomic<int32_t> consecutiveTimeouts{0};

    OboeStreamWrapper(uint64_t gen) : generationId(gen) {
        pcmFloatScratchBuffer.reserve(16384);
        resampleBuffer.reserve(16384);
    }

    ~OboeStreamWrapper() {
        closeInternal();
    }

    void closeInternal() {
        isActive.store(false, std::memory_order_release);
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (stream) {
            stream->stop();
            stream->close();
            stream = nullptr;
        }
    }

    void flush() {
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (stream && isActive.load(std::memory_order_acquire)) {
            auto state = stream->getState();
            if (state == oboe::StreamState::Started) {
                stream->requestPause();
                stream->flush();
                stream->requestStart();
            } else {
                stream->flush();
            }
        }
        atomicFramesWritten.store(0, std::memory_order_release);
        atomicTimestampUs.store(0, std::memory_order_release);
        atomicPositionFrames.store(0, std::memory_order_release);
        consecutiveTimeouts.store(0, std::memory_order_release);
        resampler.reset();
    }

    void pause() {
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (stream && isActive.load(std::memory_order_acquire) && stream->getState() == oboe::StreamState::Started) {
            stream->requestPause();
        }
    }

    void start() {
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (stream && isActive.load(std::memory_order_acquire)) {
            auto state = stream->getState();
            if (state == oboe::StreamState::Paused || state == oboe::StreamState::Open || state == oboe::StreamState::Flushed) {
                stream->requestStart();
            }
        }
    }

    int64_t getPlaybackPositionFrames() {
        if (!isActive.load(std::memory_order_acquire)) return atomicPositionFrames.load(std::memory_order_relaxed);

        oboe::AudioStream *s = stream;
        if (!s) return atomicPositionFrames.load(std::memory_order_relaxed);

        int64_t framePosition = 0;
        int64_t timeNanoseconds = 0;
        auto result = s->getTimestamp(CLOCK_MONOTONIC, &framePosition, &timeNanoseconds);
        if (result == oboe::Result::OK && timeNanoseconds > 0) {
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            int64_t nowNs = static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
            int64_t deltaNs = nowNs - timeNanoseconds;
            if (deltaNs >= 0 && s->getSampleRate() > 0) {
                int64_t extrapolated = framePosition + (deltaNs * s->getSampleRate() / 1000000000LL);
                int64_t pos = std::max<int64_t>(0, std::min<int64_t>(extrapolated, atomicFramesWritten.load(std::memory_order_relaxed)));
                atomicPositionFrames.store(pos, std::memory_order_relaxed);
                return pos;
            }
            int64_t pos = std::max<int64_t>(0, std::min<int64_t>(framePosition, atomicFramesWritten.load(std::memory_order_relaxed)));
            atomicPositionFrames.store(pos, std::memory_order_relaxed);
            return pos;
        }

        auto framesRead = s->getFramesRead();
        if (framesRead > 0) {
            int64_t pos = std::max<int64_t>(0, std::min<int64_t>(framesRead, atomicFramesWritten.load(std::memory_order_relaxed)));
            atomicPositionFrames.store(pos, std::memory_order_relaxed);
            return pos;
        }

        int32_t bufferSize = s->getBufferSizeInFrames();
        int64_t pos = std::max<int64_t>(0, atomicFramesWritten.load(std::memory_order_relaxed) - static_cast<int64_t>(bufferSize));
        atomicPositionFrames.store(pos, std::memory_order_relaxed);
        return pos;
    }

    int64_t getPlaybackTimestampUs() {
        if (!isActive.load(std::memory_order_acquire)) return atomicTimestampUs.load(std::memory_order_relaxed);

        oboe::AudioStream *s = stream;
        if (!s || s->getSampleRate() <= 0) return atomicTimestampUs.load(std::memory_order_relaxed);

        int64_t frames = getPlaybackPositionFrames();
        int64_t us = (frames * 1000000LL) / s->getSampleRate();
        atomicTimestampUs.store(us, std::memory_order_relaxed);
        return us;
    }

    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override {
        LOGW("Oboe stream reported disconnect/error: %s (gen=%llu)", oboe::convertToText(error), static_cast<unsigned long long>(generationId));
        isActive.store(false, std::memory_order_release);
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (stream == audioStream) {
            stream = nullptr;
        }
    }
};

// Safe Thread-Safe Registry for Stream Lifecycle Protection
namespace {
    std::mutex gRegistryMutex;
    std::unordered_map<int64_t, std::shared_ptr<OboeStreamWrapper>> gStreamRegistry;
    std::atomic<uint64_t> gGenerationSequence{1};

    std::shared_ptr<OboeStreamWrapper> getStream(int64_t handle, uint64_t expectedGen = 0) {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        auto it = gStreamRegistry.find(handle);
        if (it != gStreamRegistry.end() && it->second) {
            if (expectedGen == 0 || it->second->generationId == expectedGen) {
                return it->second;
            }
        }
        return nullptr;
    }

    int64_t registerStream(const std::shared_ptr<OboeStreamWrapper> &stream) {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        auto handle = reinterpret_cast<int64_t>(stream.get());
        gStreamRegistry[handle] = stream;
        return handle;
    }

    void unregisterStream(int64_t handle) {
        std::shared_ptr<OboeStreamWrapper> toClose;
        {
            std::lock_guard<std::mutex> lock(gRegistryMutex);
            auto it = gStreamRegistry.find(handle);
            if (it != gStreamRegistry.end()) {
                toClose = it->second;
                gStreamRegistry.erase(it);
            }
        }
        if (toClose) {
            toClose->closeInternal();
        }
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_openStream(JNIEnv *env, jobject thiz, jint sampleRate, jint channelCount, jboolean bitPerfectMode, jint deviceId) {
    uint64_t gen = gGenerationSequence.fetch_add(1, std::memory_order_relaxed);
    auto wrapper = std::make_shared<OboeStreamWrapper>(gen);
    wrapper->configuredSampleRate = sampleRate;
    wrapper->configuredChannelCount = channelCount;
    wrapper->dsp.setSampleRate(static_cast<double>(sampleRate));

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(bitPerfectMode ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setSampleRate(sampleRate)
           ->setChannelCount(channelCount)
           ->setErrorCallback(wrapper.get())
           ->setUsage(oboe::Usage::Media)
           ->setContentType(oboe::ContentType::Music);

    if (deviceId > 0) {
        builder.setDeviceId(deviceId);
    }

    if (bitPerfectMode) {
        builder.setSharingMode(oboe::SharingMode::Exclusive);
    }

    oboe::Result result = builder.openStream(&wrapper->stream);

    // If not bit-perfect and exclusive failed, try shared
    if (!bitPerfectMode && result != oboe::Result::OK) {
        LOGW("Exclusive open failed: %s. Retrying in Shared mode.", oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(&wrapper->stream);
    }

    if (result == oboe::Result::OK && wrapper->stream) {
        result = wrapper->stream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
            wrapper->closeInternal();
            return 0;
        }

        int32_t actualRate = wrapper->stream->getSampleRate();
        int32_t actualChannels = wrapper->stream->getChannelCount();
        wrapper->dsp.setSampleRate(static_cast<double>(actualRate));
        
        // Use high-performance sinc mode for optimal latency and CPU
        wrapper->resampler.configure(sampleRate, actualRate, actualChannels, antigravity::ResampleQuality::SINC_FAST);

        LOGI("✦ [OBOE HI-FI STREAM ENGAGED] ✦ API=%s Mode=%s Gen=%llu DeviceId=%d Rate=%d->%dHz",
             oboe::convertToText(wrapper->stream->getAudioApi()),
             (wrapper->stream->getSharingMode() == oboe::SharingMode::Exclusive ? "EXCLUSIVE" : "SHARED"),
             static_cast<unsigned long long>(gen),
             wrapper->stream->getDeviceId(),
             sampleRate,
             actualRate);

        return registerStream(wrapper);
    } else {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        wrapper->closeInternal();
        return 0;
    }
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getStreamGeneration(JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    return wrapper ? static_cast<jlong>(wrapper->generationId) : 0L;
}

// ---------------- HIGH-PERFORMANCE ZERO-ALLOCATION DIRECT JNI WRITE ----------------

JNIEXPORT jint JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_writeDirect(
    JNIEnv *env,
    jobject thiz,
    jlong handle,
    jlong generation,
    jobject directBuffer,
    jint offsetBytes,
    jint numBytes,
    jint numFrames,
    jint pcmEncoding,
    jboolean isBitPerfect
) {
    if (numFrames <= 0 || numBytes <= 0 || !directBuffer) return 0;

    auto wrapper = getStream(handle, static_cast<uint64_t>(generation));
    if (!wrapper || !wrapper->isActive.load(std::memory_order_acquire)) {
        return -1; // Stale stream or closed generation
    }

    auto *rawAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(directBuffer));
    if (!rawAddress) return -1;

    const uint8_t *srcBytes = rawAddress + offsetBytes;
    int32_t channelCount = wrapper->configuredChannelCount;
    int32_t totalSamples = numFrames * channelCount;

    // Ensure preallocated float scratch buffer has sufficient capacity
    if (static_cast<int32_t>(wrapper->pcmFloatScratchBuffer.size()) < totalSamples) {
        wrapper->pcmFloatScratchBuffer.resize(totalSamples * 2);
    }

    float *scratch = wrapper->pcmFloatScratchBuffer.data();

    // Fast SIMD-friendly Native PCM Unpacking
    switch (pcmEncoding) {
        case 4: // C.ENCODING_PCM_FLOAT
            std::memcpy(scratch, srcBytes, totalSamples * sizeof(float));
            break;

        case 2: // C.ENCODING_PCM_16BIT
        default: {
            const int16_t *src16 = reinterpret_cast<const int16_t *>(srcBytes);
            for (int32_t i = 0; i < totalSamples; ++i) {
                scratch[i] = static_cast<float>(src16[i]) * (1.0f / 32768.0f);
            }
            break;
        }

        case 3: // C.ENCODING_PCM_24BIT (packed 3-byte little endian)
        case 21: {
            const uint8_t *b = srcBytes;
            for (int32_t i = 0; i < totalSamples; ++i) {
                int32_t b0 = b[0];
                int32_t b1 = b[1];
                int32_t b2 = b[2];
                b += 3;
                int32_t raw24 = (b2 << 16) | (b1 << 8) | b0;
                if (raw24 & 0x800000) raw24 |= ~0xFFFFFF; // sign extend
                scratch[i] = static_cast<float>(raw24) * (1.0f / 8388608.0f);
            }
            break;
        }

        case 22: { // C.ENCODING_PCM_32BIT (signed 32-bit int)
            const int32_t *src32 = reinterpret_cast<const int32_t *>(srcBytes);
            for (int32_t i = 0; i < totalSamples; ++i) {
                scratch[i] = static_cast<float>(static_cast<double>(src32[i]) * (1.0 / 2147483648.0));
            }
            break;
        }
    }

    const float *sendData = scratch;
    int32_t framesToSend = numFrames;

    if (!isBitPerfect) {
        // 1. Process 64-bit Native C++ Audiophile DSP
        wrapper->dsp.process(scratch, numFrames, channelCount);

        // 2. High-Precision Resampling if hardware rate differs
        if (!wrapper->resampler.isPassThrough()) {
            int32_t resampledFrames = wrapper->resampler.process(scratch, numFrames, wrapper->resampleBuffer);
            if (resampledFrames > 0) {
                sendData = wrapper->resampleBuffer.data();
                framesToSend = resampledFrames;
            }
        }
    } else {
        // Bit-Perfect direct path: strict bypass
        if (!wrapper->resampler.isPassThrough()) {
            int32_t resampledFrames = wrapper->resampler.process(scratch, numFrames, wrapper->resampleBuffer);
            if (resampledFrames > 0) {
                sendData = wrapper->resampleBuffer.data();
                framesToSend = resampledFrames;
            }
        }
    }

    // 3. Write to Oboe Stream without holding heavy global lock
    oboe::AudioStream *activeStream = wrapper->stream;
    if (!activeStream || !wrapper->isActive.load(std::memory_order_acquire)) {
        return -1;
    }

    int64_t timeoutNanos = 20 * 1000000LL; // 20ms bounded timeout
    oboe::ResultWithValue<int32_t> result = activeStream->write(sendData, framesToSend, timeoutNanos);
    
    if (result.error() != oboe::Result::OK) {
        if (result.error() == oboe::Result::ErrorTimeout) {
            int32_t timeouts = wrapper->consecutiveTimeouts.fetch_add(1, std::memory_order_relaxed) + 1;
            if (timeouts > 50) { // Stream stalled
                wrapper->isActive.store(false, std::memory_order_release);
                return -1;
            }
            return 0; // Transient timeout, retry
        }
        LOGW("Oboe write error: %s", oboe::convertToText(result.error()));
        wrapper->isActive.store(false, std::memory_order_release);
        return -1;
    }

    wrapper->consecutiveTimeouts.store(0, std::memory_order_relaxed);
    int32_t written = result.value();
    if (written > 0) {
        wrapper->atomicFramesWritten.fetch_add(written, std::memory_order_relaxed);
    }

    // Return consumed input frames count
    if (wrapper->resampler.isPassThrough()) {
        return written;
    } else {
        double ratio = static_cast<double>(framesToSend) / static_cast<double>(numFrames);
        if (ratio <= 0.0) return numFrames;
        jint inputConsumed = static_cast<jint>(std::round(static_cast<double>(written) / ratio));
        return std::min<jint>(numFrames, std::max<jint>(0, inputConsumed));
    }
}

JNIEXPORT jint JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_write(JNIEnv *env, jobject thiz, jlong handle, jfloatArray audioData, jint numFrames) {
    if (numFrames <= 0 || !audioData) return 0;

    auto wrapper = getStream(handle);
    if (!wrapper || !wrapper->isActive.load(std::memory_order_acquire)) return -1;

    jfloat *data = env->GetFloatArrayElements(audioData, nullptr);
    if (!data) return -1;

    int32_t channelCount = wrapper->configuredChannelCount;
    bool isBypass = wrapper->dsp.isBitPerfectBypass();
    const float *sendData = data;
    int32_t framesToSend = numFrames;

    if (!isBypass) {
        wrapper->dsp.process(data, numFrames, channelCount);
        if (!wrapper->resampler.isPassThrough()) {
            int32_t resampledFrames = wrapper->resampler.process(data, numFrames, wrapper->resampleBuffer);
            if (resampledFrames > 0) {
                sendData = wrapper->resampleBuffer.data();
                framesToSend = resampledFrames;
            }
        }
    } else {
        if (!wrapper->resampler.isPassThrough()) {
            int32_t resampledFrames = wrapper->resampler.process(data, numFrames, wrapper->resampleBuffer);
            if (resampledFrames > 0) {
                sendData = wrapper->resampleBuffer.data();
                framesToSend = resampledFrames;
            }
        }
    }

    oboe::AudioStream *activeStream = wrapper->stream;
    if (!activeStream || !wrapper->isActive.load(std::memory_order_acquire)) {
        env->ReleaseFloatArrayElements(audioData, data, 0);
        return -1;
    }

    int64_t timeoutNanos = 20 * 1000000LL;
    oboe::ResultWithValue<int32_t> result = activeStream->write(sendData, framesToSend, timeoutNanos);
    env->ReleaseFloatArrayElements(audioData, data, 0);

    if (result.error() != oboe::Result::OK) {
        if (result.error() == oboe::Result::ErrorTimeout) {
            int32_t timeouts = wrapper->consecutiveTimeouts.fetch_add(1, std::memory_order_relaxed) + 1;
            if (timeouts > 50) {
                wrapper->isActive.store(false, std::memory_order_release);
                return -1;
            }
            return 0;
        }
        wrapper->isActive.store(false, std::memory_order_release);
        return -1;
    }

    wrapper->consecutiveTimeouts.store(0, std::memory_order_relaxed);
    int32_t written = result.value();
    if (written > 0) {
        wrapper->atomicFramesWritten.fetch_add(written, std::memory_order_relaxed);
    }

    if (wrapper->resampler.isPassThrough()) {
        return written;
    } else {
        double ratio = static_cast<double>(framesToSend) / static_cast<double>(numFrames);
        if (ratio <= 0.0) return numFrames;
        jint inputConsumed = static_cast<jint>(std::round(static_cast<double>(written) / ratio));
        return std::min<jint>(numFrames, std::max<jint>(0, inputConsumed));
    }
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_closeStream(JNIEnv *env, jobject thiz, jlong handle) {
    unregisterStream(handle);
    LOGI("Oboe Stream unmapped and closed cleanly");
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_flushStream(JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->flush();
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_pauseStream(JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->pause();
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_startStream(JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->start();
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPlaybackPositionFrames(JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    return wrapper ? static_cast<jlong>(wrapper->getPlaybackPositionFrames()) : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPlaybackTimestampUs(JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    return wrapper ? static_cast<jlong>(wrapper->getPlaybackTimestampUs()) : 0L;
}

JNIEXPORT jint JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getSampleRate(JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    return (wrapper && wrapper->stream) ? wrapper->stream->getSampleRate() : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_isExclusive(JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    return (wrapper && wrapper->stream && wrapper->stream->getSharingMode() == oboe::SharingMode::Exclusive) ? JNI_TRUE : JNI_FALSE;
}

// ---------------- DSP JNI CONTROLS ----------------

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDspEnabled(JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setBitPerfectBypass(JNIEnv *env, jobject thiz, jlong handle, jboolean bypass) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setBitPerfectBypass(bypass == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setPreAmpGainDb(JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setPreAmpGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setBandGain(JNIEnv *env, jobject thiz, jlong handle, jint bandIndex, jdouble gainDb) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setBandGain(bandIndex, gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setBassBoostGainDb(JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setBassBoostGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setTrebleGainDb(JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setTrebleGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setHarmonicExciterLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setHarmonicExciterLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setClarityEnhancerGain(JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setClarityEnhancerGain(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setStereoExpansionMultiplier(JNIEnv *env, jobject thiz, jlong handle, jdouble multiplier) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setStereoExpansionMultiplier(multiplier);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDvcVolume(JNIEnv *env, jobject thiz, jlong handle, jdouble volume) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setDvcVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDitherStrength(JNIEnv *env, jobject thiz, jlong handle, jdouble strength) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setDitherStrength(strength);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setOutputBitDepth(JNIEnv *env, jobject thiz, jlong handle, jint bitDepth) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setOutputBitDepth(bitDepth);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setWarmSaturationLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setWarmSaturationLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setTriodeWarmthLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setTriodeWarmthLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setPentodeTapeLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setPentodeTapeLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setCrossfeedLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setCrossfeedLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setLimiterEnabled(JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setLimiterEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setLimiterThresholdDb(JNIEnv *env, jobject thiz, jlong handle, jdouble thresholdDb) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setLimiterThresholdDb(thresholdDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setSubBassMonoEnabled(JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setSubBassMonoEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setChannelBalance(JNIEnv *env, jobject thiz, jlong handle, jdouble balance) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setChannelBalance(balance);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setInvertPhase(JNIEnv *env, jobject thiz, jlong handle, jboolean invert) {
    auto wrapper = getStream(handle);
    if (wrapper) wrapper->dsp.setInvertPhase(invert == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setAirPresenceGainDb(JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setAirPresenceGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_clearPeqBands(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.clearPeqBands();
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_addPeqBand(JNIEnv *env, jobject thiz, jlong handle, jint type, jdouble frequency, jdouble q, jdouble gainDb) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) {
        wrapper->dsp.addPeqBand(static_cast<antigravity::FilterType>(type), frequency, q, gainDb);
    }
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_updatePeqBand(JNIEnv *env, jobject thiz, jlong handle, jint index, jint type, jdouble frequency, jdouble q, jdouble gainDb) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper && index >= 0) {
        wrapper->dsp.updatePeqBand(static_cast<size_t>(index), static_cast<antigravity::FilterType>(type), frequency, q, gainDb);
    }
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setResamplerQuality(JNIEnv *env, jobject thiz, jlong handle, jint quality) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper && wrapper->stream) {
        wrapper->resampler.configure(
            wrapper->configuredSampleRate,
            wrapper->stream->getSampleRate(),
            wrapper->stream->getChannelCount(),
            static_cast<antigravity::ResampleQuality>(quality)
        );
    }
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setHrtfSpatialEnabled(JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setHrtfSpatialEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setHrtfRoomSize(JNIEnv *env, jobject thiz, jlong handle, jdouble roomSize) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setHrtfRoomSize(roomSize);
}

// ---------------- DSD JNI CONTROLS ----------------

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDsdMode(JNIEnv *env, jobject thiz, jlong handle, jint mode, jint dsdRate) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) {
        wrapper->dsd.configure(static_cast<antigravity::DsdMode>(mode), dsdRate);
    }
}

// ---------------- TELEMETRY JNI ----------------

JNIEXPORT jdouble JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPeakL(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    return wrapper ? wrapper->dsp.getPeakL() : 0.0;
}

JNIEXPORT jdouble JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPeakR(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    return wrapper ? wrapper->dsp.getPeakR() : 0.0;
}

JNIEXPORT jfloat JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPhaseCorrelation(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    return wrapper ? wrapper->dsp.getPhaseCorrelation() : 1.0f;
}

JNIEXPORT jobject JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getNativeStreamInfo(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (!wrapper || !wrapper->stream) return nullptr;

    auto *stream = wrapper->stream;

    jclass infoClass = env->FindClass("com/tensorix/antigravityplayer/audio/OboeBridge$NativeStreamInfo");
    if (!infoClass) return nullptr;

    jmethodID constructor = env->GetMethodID(infoClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;IILjava/lang/String;ZJI)V");
    if (!constructor) return nullptr;

    jstring api = env->NewStringUTF(oboe::convertToText(stream->getAudioApi()));
    jstring sharing = env->NewStringUTF(stream->getSharingMode() == oboe::SharingMode::Exclusive ? "EXCLUSIVE" : "SHARED");
    jstring performance = env->NewStringUTF(oboe::convertToText(stream->getPerformanceMode()));
    jstring formatStr = env->NewStringUTF(oboe::convertToText(stream->getFormat()));
    jstring stateStr = env->NewStringUTF(oboe::convertToText(stream->getState()));

    bool isStarted = (stream->getState() == oboe::StreamState::Started);
    int64_t framesWritten = stream->getFramesWritten();
    int32_t xruns = 0;
    auto xrunResult = stream->getXRunCount();
    if (xrunResult) {
        xruns = xrunResult.value();
    }

    jobject info = env->NewObject(infoClass, constructor,
                                  api,
                                  sharing,
                                  performance,
                                  static_cast<jint>(stream->getSampleRate()),
                                  static_cast<jint>(stream->getChannelCount()),
                                  formatStr,
                                  static_cast<jint>(stream->getBufferSizeInFrames()),
                                  static_cast<jint>(stream->getDeviceId()),
                                  stateStr,
                                  isStarted ? JNI_TRUE : JNI_FALSE,
                                  static_cast<jlong>(framesWritten),
                                  static_cast<jint>(xruns));

    return info;
}

}

