#pragma once

#include "biquad_filter.h"
#include <vector>
#include <array>
#include <atomic>
#include <mutex>
#include <cstdint>

namespace antigravity {

struct PeqBand {
    bool enabled = true;
    FilterType type = FilterType::PEAKING_EQ;
    double frequency = 1000.0;
    double q = 1.414;
    double gainDb = 0.0;
    BiquadFilter filterL;
    BiquadFilter filterR;
};

class AudiophileDsp {
public:
    AudiophileDsp();
    ~AudiophileDsp() = default;

    void setSampleRate(double sampleRate);
    void process(float *audioData, int32_t numFrames, int32_t channelCount);

    // Live Parameter Mutators
    void setEnabled(bool enabled);
    void setBitPerfectBypass(bool bypass);
    void setPreAmpGainDb(double gainDb);
    void setBandGain(int bandIndex, double gainDb);
    void setBassBoostGainDb(double gainDb);
    void setTrebleGainDb(double gainDb);
    void setHarmonicExciterLevel(double level);
    void setClarityEnhancerGain(double gainDb);
    void setStereoExpansionMultiplier(double multiplier);
    void setDvcVolume(double volume);
    void setDitherStrength(double strength);
    void setOutputBitDepth(int bitDepth);
    void setWarmSaturationLevel(double level);
    void setTriodeWarmthLevel(double level);
    void setPentodeTapeLevel(double level);
    void setCrossfeedLevel(double level);
    void setLimiterEnabled(bool enabled);
    void setLimiterThresholdDb(double thresholdDb);
    void setSubBassMonoEnabled(bool enabled);
    void setChannelBalance(double balance);
    void setInvertPhase(bool invert);
    void setAirPresenceGainDb(double gainDb);
    void setHrtfSpatialEnabled(bool enabled);
    void setHrtfRoomSize(double roomSize);

    // Parametric EQ (PEQ)
    void clearPeqBands();
    void addPeqBand(FilterType type, double frequency, double q, double gainDb);
    void updatePeqBand(size_t index, FilterType type, double frequency, double q, double gainDb);

    // Telemetry
    double getPeakL() const { return peakL_.load(); }
    double getPeakR() const { return peakR_.load(); }
    float getPhaseCorrelation() const { return phaseCorrelation_.load(); }
    bool isBitPerfectBypass() const { return isBitPerfectBypass_.load(); }

    void reset();

private:
    void updateFilters();
    double nextRandomDouble();

    std::atomic<bool> isEnabled_{true};
    std::atomic<bool> isBitPerfectBypass_{false};
    std::atomic<double> preAmpGainDb_{0.0};
    std::atomic<double> bassBoostGainDb_{0.0};
    std::atomic<double> trebleGainDb_{0.0};
    std::atomic<double> harmonicExciterLevel_{0.25};
    std::atomic<double> clarityEnhancerGain_{3.5};
    std::atomic<double> stereoExpansionMultiplier_{1.0};
    std::atomic<double> dvcVolume_{1.0};
    std::atomic<double> ditherStrength_{1.0};
    std::atomic<int> outputBitDepth_{24};
    std::atomic<double> warmSaturationLevel_{0.05};
    std::atomic<double> triodeWarmthLevel_{0.05};
    std::atomic<double> pentodeTapeLevel_{0.0};
    std::atomic<double> crossfeedLevel_{0.0};
    std::atomic<bool> limiterEnabled_{true};
    std::atomic<double> limiterThresholdDb_{0.0};
    std::atomic<bool> subBassMonoEnabled_{false};
    std::atomic<double> channelBalance_{0.0};
    std::atomic<bool> invertPhase_{false};
    std::atomic<double> airPresenceGainDb_{2.0};
    std::atomic<bool> hrtfSpatialEnabled_{false};
    std::atomic<double> hrtfRoomSize_{0.5};

    std::atomic<double> peakL_{0.0};
    std::atomic<double> peakR_{0.0};
    std::atomic<float> phaseCorrelation_{1.0f};

    double sampleRate_ = 48000.0;
    std::array<double, 10> bandGainsDb_{};
    static constexpr std::array<double, 10> bandCenterFreqs_ = {
        31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    };

    // Filter Chains
    std::array<BiquadFilter, 10> biquadsL_;
    std::array<BiquadFilter, 10> biquadsR_;
    BiquadFilter bassShelfL_;
    BiquadFilter bassShelfR_;
    BiquadFilter trebleShelfL_;
    BiquadFilter trebleShelfR_;
    BiquadFilter detailHPFL_;
    BiquadFilter detailHPFR_;
    BiquadFilter clarityFilterL_;
    BiquadFilter clarityFilterR_;
    BiquadFilter crossfeedLPFL_;
    BiquadFilter crossfeedLPFR_;
    BiquadFilter airFilterL_;
    BiquadFilter airFilterR_;
    BiquadFilter dcRemovalL_;
    BiquadFilter dcRemovalR_;
    BiquadFilter dcBlockerL_;
    BiquadFilter dcBlockerR_;
    BiquadFilter aaFilterL_;
    BiquadFilter aaFilterR_;
    BiquadFilter subBassFilterL_;
    BiquadFilter subBassFilterR_;
    BiquadFilter hrtfHeadShadowL_;
    BiquadFilter hrtfHeadShadowR_;
    BiquadFilter hrtfPinnaNotchL_;
    BiquadFilter hrtfPinnaNotchR_;

    std::mutex peqMutex_;
    std::vector<PeqBand> peqBands_;

    // Oversampling history (Hermite Spline)
    std::array<double, 4> osSamplesL_{};
    std::array<double, 4> osSamplesR_{};

    // HRTF ITD (Interaural Time Delay) Circular Buffers
    static constexpr size_t HRTF_BUFFER_SIZE = 2048;
    std::array<double, HRTF_BUFFER_SIZE> itdBufferL_{};
    std::array<double, HRTF_BUFFER_SIZE> itdBufferR_{};
    size_t itdWriteIdx_ = 0;

    // Dither State
    double ditherErrorL_ = 0.0;
    double ditherErrorR_ = 0.0;
    uint64_t rngState_ = 0x853c49e6748fea9bULL;
};

} // namespace antigravity
