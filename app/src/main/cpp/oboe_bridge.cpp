#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <vector>
#include <memory>
#include <mutex>
#include "dsp/audiophile_dsp.h"
#include "resampler/audiophile_resampler.h"
#include "dsd/dsd_engine.h"

#define LOG_TAG "OboeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

class OboeStreamWrapper : public oboe::AudioStreamErrorCallback {
public:
    oboe::AudioStream *stream = nullptr;
    antigravity::AudiophileDsp dsp;
    antigravity::AudiophileResampler resampler;
    antigravity::DsdEngine dsd;

    int32_t configuredSampleRate = 48000;
    int32_t configuredChannelCount = 2;
    std::vector<float> resampleBuffer;
    std::mutex streamMutex;

    OboeStreamWrapper() = default;
    ~OboeStreamWrapper() {
        close();
    }

    void close() {
        std::lock_guard<std::mutex> lock(streamMutex);
        if (stream) {
            stream->stop();
            stream->close();
            stream = nullptr;
        }
    }

    void flush() {
        std::lock_guard<std::mutex> lock(streamMutex);
        if (stream) {
            auto state = stream->getState();
            if (state == oboe::StreamState::Started) {
                stream->requestPause();
                stream->flush();
                stream->requestStart();
            } else {
                stream->flush();
            }
        }
        resampleBuffer.clear();
    }

    void pause() {
        std::lock_guard<std::mutex> lock(streamMutex);
        if (stream && stream->getState() == oboe::StreamState::Started) {
            stream->requestPause();
        }
    }

    void start() {
        std::lock_guard<std::mutex> lock(streamMutex);
        if (stream && (stream->getState() == oboe::StreamState::Paused || stream->getState() == oboe::StreamState::Open)) {
            stream->requestStart();
        }
    }

    int64_t getPlaybackPositionFrames() {
        std::lock_guard<std::mutex> lock(streamMutex);
        if (!stream) return 0;

        int64_t framePosition = 0;
        int64_t timeNanoseconds = 0;
        auto result = stream->getTimestamp(CLOCK_MONOTONIC, &framePosition, &timeNanoseconds);
        if (result == oboe::Result::OK && timeNanoseconds > 0) {
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            int64_t nowNs = static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
            int64_t deltaNs = nowNs - timeNanoseconds;
            if (deltaNs >= 0 && stream->getSampleRate() > 0) {
                int64_t extrapolated = framePosition + (deltaNs * stream->getSampleRate() / 1000000000LL);
                return std::max<int64_t>(0, std::min<int64_t>(extrapolated, stream->getFramesWritten()));
            }
            return std::max<int64_t>(0, std::min<int64_t>(framePosition, stream->getFramesWritten()));
        }

        auto framesRead = stream->getFramesRead();
        if (framesRead > 0) {
            return std::max<int64_t>(0, std::min<int64_t>(framesRead, stream->getFramesWritten()));
        }
        int32_t bufferSize = stream->getBufferSizeInFrames();
        return std::max<int64_t>(0, stream->getFramesWritten() - static_cast<int64_t>(bufferSize));
    }

    int64_t getPlaybackTimestampUs() {
        std::lock_guard<std::mutex> lock(streamMutex);
        if (!stream || stream->getSampleRate() <= 0) return 0;

        int64_t framePosition = 0;
        int64_t timeNanoseconds = 0;
        auto result = stream->getTimestamp(CLOCK_MONOTONIC, &framePosition, &timeNanoseconds);
        int64_t frames = 0;
        if (result == oboe::Result::OK && timeNanoseconds > 0) {
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            int64_t nowNs = static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
            int64_t deltaNs = nowNs - timeNanoseconds;
            if (deltaNs >= 0 && stream->getSampleRate() > 0) {
                int64_t extrapolated = framePosition + (deltaNs * stream->getSampleRate() / 1000000000LL);
                frames = std::max<int64_t>(0, std::min<int64_t>(extrapolated, stream->getFramesWritten()));
            } else {
                frames = std::max<int64_t>(0, std::min<int64_t>(framePosition, stream->getFramesWritten()));
            }
        } else {
            auto framesRead = stream->getFramesRead();
            if (framesRead > 0) {
                frames = std::max<int64_t>(0, std::min<int64_t>(framesRead, stream->getFramesWritten()));
            } else {
                int32_t bufferSize = stream->getBufferSizeInFrames();
                frames = std::max<int64_t>(0, stream->getFramesWritten() - static_cast<int64_t>(bufferSize));
            }
        }
        return (frames * 1000000LL) / stream->getSampleRate();
    }



    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override {
        LOGE("Oboe stream error reported by HAL: %s. Notifying single authority.", oboe::convertToText(error));
        std::lock_guard<std::mutex> lock(streamMutex);
        if (stream) {
            stream = nullptr;
        }
        // Do NOT secretly reopen stream in native layer. AudioEngine owns recovery.
    }
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_openStream(JNIEnv *env, jobject thiz, jint sampleRate, jint channelCount, jboolean bitPerfectMode, jint deviceId) {
    auto *wrapper = new OboeStreamWrapper();
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
           ->setErrorCallback(wrapper)
           ->setUsage(oboe::Usage::Media)
           ->setContentType(oboe::ContentType::Music);

    if (deviceId > 0) {
        builder.setDeviceId(deviceId);
    }

    if (bitPerfectMode) {
        // For bit-perfect, we insist on Exclusive mode first
        builder.setSharingMode(oboe::SharingMode::Exclusive);
    }

    oboe::Result result = builder.openStream(&wrapper->stream);

    // If not bit-perfect and exclusive failed, try shared
    if (!bitPerfectMode && result != oboe::Result::OK) {
        LOGW("Failed to open Oboe stream in Exclusive mode: %s. Retrying in Shared mode.", oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(&wrapper->stream);
    }

    if (result == oboe::Result::OK && wrapper->stream) {
        result = wrapper->stream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
            delete wrapper;
            return 0;
        }

        int32_t actualRate = wrapper->stream->getSampleRate();
        int32_t actualChannels = wrapper->stream->getChannelCount();
        wrapper->dsp.setSampleRate(static_cast<double>(actualRate));
        wrapper->resampler.configure(sampleRate, actualRate, actualChannels, antigravity::ResampleQuality::SINC_BEST);

        LOGI("✦ [OBOE HI-FI STREAM ENGAGED] ✦");
        LOGI("  API: %s", oboe::convertToText(wrapper->stream->getAudioApi()));
        LOGI("  Sharing Mode: %s", (wrapper->stream->getSharingMode() == oboe::SharingMode::Exclusive ? "EXCLUSIVE (Bit-Perfect Direct)" : "SHARED"));
        LOGI("  Bit-Perfect Requested: %s", (bitPerfectMode ? "YES" : "NO"));
        LOGI("  Input Sample Rate: %d Hz -> Output: %d Hz", sampleRate, actualRate);
        LOGI("  Channels: %d", actualChannels);
        LOGI("  Format: 32-bit Float PCM (64-bit Native DSP Math)");
        LOGI("  Buffer Size: %d frames", wrapper->stream->getBufferSizeInFrames());

        return reinterpret_cast<jlong>(wrapper);
    } else {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        delete wrapper;
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_write(JNIEnv *env, jobject thiz, jlong handle, jfloatArray audioData, jint numFrames) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (!wrapper || !wrapper->stream || numFrames <= 0) return -1;

    jfloat *data = env->GetFloatArrayElements(audioData, nullptr);
    if (!data) return -1;

    int32_t channelCount = wrapper->configuredChannelCount;
    bool isBypass = wrapper->dsp.isBitPerfectBypass();
    const float *sendData = data;
    int32_t framesToSend = numFrames;

    if (!isBypass) {
        // 1. Process 64-bit Native C++ Audiophile DSP
        wrapper->dsp.process(data, numFrames, channelCount);

        // 2. High-Precision Resampling if hardware rate differs
        if (!wrapper->resampler.isPassThrough()) {
            int32_t resampledFrames = wrapper->resampler.process(data, numFrames, wrapper->resampleBuffer);
            if (resampledFrames > 0) {
                sendData = wrapper->resampleBuffer.data();
                framesToSend = resampledFrames;
            }
        }
    } else {
        // Fast path for Bit-Perfect mode: zero DSP modification
        if (!wrapper->resampler.isPassThrough()) {
            int32_t resampledFrames = wrapper->resampler.process(data, numFrames, wrapper->resampleBuffer);
            if (resampledFrames > 0) {
                sendData = wrapper->resampleBuffer.data();
                framesToSend = resampledFrames;
            }
        }
    }

    // 3. Native Direct Bounded Write (20ms timeout to prevent stalls)
    std::lock_guard<std::mutex> lock(wrapper->streamMutex);
    if (!wrapper->stream) {
        env->ReleaseFloatArrayElements(audioData, data, 0);
        return -1;
    }

    int64_t timeoutNanos = 20 * 1000000LL; // 20ms bounded timeout
    oboe::ResultWithValue<int32_t> result = wrapper->stream->write(sendData, framesToSend, timeoutNanos);
    int32_t written = result.value();

    if (result.error() != oboe::Result::OK) {
        LOGW("Oboe write error: %s", oboe::convertToText(result.error()));
        env->ReleaseFloatArrayElements(audioData, data, 0);
        return (result.error() == oboe::Result::ErrorTimeout) ? 0 : -1;
    }

    env->ReleaseFloatArrayElements(audioData, data, 0);

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

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_closeStream(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) {
        delete wrapper;
        LOGI("Oboe Stream Terminated cleanly");
    }
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_flushStream(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->flush();
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_pauseStream(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->pause();
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_startStream(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->start();
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPlaybackPositionFrames(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    return wrapper ? static_cast<jlong>(wrapper->getPlaybackPositionFrames()) : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPlaybackTimestampUs(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    return wrapper ? static_cast<jlong>(wrapper->getPlaybackTimestampUs()) : 0L;
}

JNIEXPORT jint JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getSampleRate(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    return (wrapper && wrapper->stream) ? wrapper->stream->getSampleRate() : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_isExclusive(JNIEnv *env, jobject thiz, jlong handle) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    return (wrapper && wrapper->stream && wrapper->stream->getSharingMode() == oboe::SharingMode::Exclusive) ? JNI_TRUE : JNI_FALSE;
}

// ---------------- DSP JNI CONTROLS ----------------

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDspEnabled(JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setBitPerfectBypass(JNIEnv *env, jobject thiz, jlong handle, jboolean bypass) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setBitPerfectBypass(bypass == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setPreAmpGainDb(JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setPreAmpGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setBandGain(JNIEnv *env, jobject thiz, jlong handle, jint bandIndex, jdouble gainDb) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setBandGain(bandIndex, gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setBassBoostGainDb(JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setBassBoostGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setTrebleGainDb(JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setTrebleGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setHarmonicExciterLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setHarmonicExciterLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setClarityEnhancerGain(JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setClarityEnhancerGain(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setStereoExpansionMultiplier(JNIEnv *env, jobject thiz, jlong handle, jdouble multiplier) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setStereoExpansionMultiplier(multiplier);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDvcVolume(JNIEnv *env, jobject thiz, jlong handle, jdouble volume) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setDvcVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDitherStrength(JNIEnv *env, jobject thiz, jlong handle, jdouble strength) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setDitherStrength(strength);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setOutputBitDepth(JNIEnv *env, jobject thiz, jlong handle, jint bitDepth) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setOutputBitDepth(bitDepth);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setWarmSaturationLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setWarmSaturationLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setTriodeWarmthLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setTriodeWarmthLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setPentodeTapeLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setPentodeTapeLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setCrossfeedLevel(JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setCrossfeedLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setLimiterEnabled(JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setLimiterEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setLimiterThresholdDb(JNIEnv *env, jobject thiz, jlong handle, jdouble thresholdDb) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setLimiterThresholdDb(thresholdDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setSubBassMonoEnabled(JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setSubBassMonoEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setChannelBalance(JNIEnv *env, jobject thiz, jlong handle, jdouble balance) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
    if (wrapper) wrapper->dsp.setChannelBalance(balance);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setInvertPhase(JNIEnv *env, jobject thiz, jlong handle, jboolean invert) {
    auto *wrapper = reinterpret_cast<OboeStreamWrapper *>(handle);
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

