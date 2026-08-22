#pragma once

#include <vector>
#include <cstdint>
#include <cmath>

namespace antigravity {

enum class ResampleQuality {
    HERMITE_FAST = 0,
    SINC_FAST = 1,
    SINC_BEST = 2
};

class AudiophileResampler {
public:
    AudiophileResampler();
    ~AudiophileResampler() = default;

    void configure(int32_t inSampleRate, int32_t outSampleRate, int32_t channelCount, ResampleQuality quality = ResampleQuality::SINC_BEST);
    
    // Resamples input float buffer and outputs to outBuffer. Returns number of output frames produced.
    int32_t process(const float *inData, int32_t inFrames, std::vector<float> &outBuffer);

    void reset();

    bool isPassThrough() const { return inSampleRate_ == outSampleRate_ || inSampleRate_ <= 0 || outSampleRate_ <= 0; }
    int32_t getInputSampleRate() const { return inSampleRate_; }
    int32_t getOutputSampleRate() const { return outSampleRate_; }

private:
    double sinc(double x) const;
    double blackmanNutall(double x, int numTaps) const;
    void initPolyphaseTable();

    static constexpr int32_t NUM_PHASES = 64;
    static constexpr int32_t MAX_TAPS = 64;

    int32_t inSampleRate_ = 48000;
    int32_t outSampleRate_ = 48000;
    int32_t channelCount_ = 2;
    ResampleQuality quality_ = ResampleQuality::SINC_BEST;

    double timePos_ = 0.0;
    std::vector<float> historyBuffer_;
    std::vector<float> workBuffer_;
    static constexpr int32_t MAX_HISTORY_FRAMES = 128;

    std::vector<std::vector<double>> polyphaseTable_;
};

} // namespace antigravity
