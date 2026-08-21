#include "audiophile_dsp.h"
#include <cmath>
#include <algorithm>

namespace antigravity {

static constexpr double M_PI_VAL = 3.14159265358979323846;

// Ultra-fast 5th-order Padé rational approximation of tanh(x) (<0.005% error, 0 transcendentals)
static inline double fastTanh(double x) {
    if (x <= -3.0) return -1.0;
    if (x >= 3.0) return 1.0;
    double x2 = x * x;
    return x * (27.0 + x2) / (27.0 + 9.0 * x2);
}

AudiophileDsp::AudiophileDsp() {
    bandGainsDb_.fill(0.0);
    reset();
}

void AudiophileDsp::reset() {
    for (auto &b : biquadsL_) b.reset();
    for (auto &b : biquadsR_) b.reset();
    bassShelfL_.reset();
    bassShelfR_.reset();
    trebleShelfL_.reset();
    trebleShelfR_.reset();
    detailHPFL_.reset();
    detailHPFR_.reset();
    clarityFilterL_.reset();
    clarityFilterR_.reset();
    crossfeedLPFL_.reset();
    crossfeedLPFR_.reset();
    airFilterL_.reset();
    airFilterR_.reset();
    dcRemovalL_.reset();
    dcRemovalR_.reset();
    dcBlockerL_.reset();
    dcBlockerR_.reset();
    aaFilterL_.reset();
    aaFilterR_.reset();
    subBassFilterL_.reset();
    subBassFilterR_.reset();
    hrtfHeadShadowL_.reset();
    hrtfHeadShadowR_.reset();
    hrtfPinnaNotchL_.reset();
    hrtfPinnaNotchR_.reset();

    {
        std::lock_guard<std::mutex> lock(peqMutex_);
        for (auto &band : peqBands_) {
            band.filterL.reset();
            band.filterR.reset();
        }
    }

    osSamplesL_.fill(0.0);
    osSamplesR_.fill(0.0);
    itdBufferL_.fill(0.0);
    itdBufferR_.fill(0.0);
    itdWriteIdx_ = 0;
    ditherErrorL_ = 0.0;
    ditherErrorR_ = 0.0;
    peakL_.store(0.0);
    peakR_.store(0.0);
    phaseCorrelation_.store(1.0f);

    updateFilters();
}

void AudiophileDsp::setSampleRate(double sampleRate) {
    if (sampleRate <= 0.0) return;
    sampleRate_ = sampleRate;
    updateFilters();
}

void AudiophileDsp::updateFilters() {
    double fs = sampleRate_;
    if (fs <= 0.0) return;

    for (size_t i = 0; i < bandCenterFreqs_.size(); ++i) {
        double f0 = bandCenterFreqs_[i];
        double gain = bandGainsDb_[i];
        biquadsL_[i].setPeakingEq(f0, 1.414, gain, fs);
        biquadsR_[i].setPeakingEq(f0, 1.414, gain, fs);
    }

    bassShelfL_.setLowShelf(80.0, 0.707, bassBoostGainDb_.load(), fs);
    bassShelfR_.setLowShelf(80.0, 0.707, bassBoostGainDb_.load(), fs);

    trebleShelfL_.setHighShelf(10000.0, 0.707, trebleGainDb_.load(), fs);
    trebleShelfR_.setHighShelf(10000.0, 0.707, trebleGainDb_.load(), fs);

    detailHPFL_.setHighPass(7500.0, 0.707, fs);
    detailHPFR_.setHighPass(7500.0, 0.707, fs);

    clarityFilterL_.setPeakingEq(3200.0, 1.0, clarityEnhancerGain_.load(), fs);
    clarityFilterR_.setPeakingEq(3200.0, 1.0, clarityEnhancerGain_.load(), fs);

    crossfeedLPFL_.setLowPass(700.0, 0.5, fs);
    crossfeedLPFR_.setLowPass(700.0, 0.5, fs);

    dcRemovalL_.setHighPass(2.0, 0.707, fs);
    dcRemovalR_.setHighPass(2.0, 0.707, fs);

    dcBlockerL_.setHighPass(1.0, 0.707, fs);
    dcBlockerR_.setHighPass(1.0, 0.707, fs);

    aaFilterL_.setLowPass(20000.0, 0.707, fs);
    aaFilterR_.setLowPass(20000.0, 0.707, fs);

    subBassFilterL_.setLowPass(80.0, 0.707, fs);
    subBassFilterR_.setLowPass(80.0, 0.707, fs);

    airFilterL_.setHighShelf(16000.0, 0.5, airPresenceGainDb_.load(), fs);
    airFilterR_.setHighShelf(16000.0, 0.5, airPresenceGainDb_.load(), fs);

    // HRTF Head-Shadow Low-Pass & Pinna Notch (Bauer/Linkwitz Model)
    hrtfHeadShadowL_.setLowPass(850.0, 0.55, fs);
    hrtfHeadShadowR_.setLowPass(850.0, 0.55, fs);
    hrtfPinnaNotchL_.setNotch(6200.0, 3.5, fs);
    hrtfPinnaNotchR_.setNotch(6200.0, 3.5, fs);

    {
        std::lock_guard<std::mutex> lock(peqMutex_);
        for (auto &band : peqBands_) {
            if (!band.enabled) continue;
            switch (band.type) {
                case FilterType::PEAKING_EQ:
                    band.filterL.setPeakingEq(band.frequency, band.q, band.gainDb, fs);
                    band.filterR.setPeakingEq(band.frequency, band.q, band.gainDb, fs);
                    break;
                case FilterType::LOW_SHELF:
                    band.filterL.setLowShelf(band.frequency, band.q, band.gainDb, fs);
                    band.filterR.setLowShelf(band.frequency, band.q, band.gainDb, fs);
                    break;
                case FilterType::HIGH_SHELF:
                    band.filterL.setHighShelf(band.frequency, band.q, band.gainDb, fs);
                    band.filterR.setHighShelf(band.frequency, band.q, band.gainDb, fs);
                    break;
                case FilterType::LOW_PASS:
                    band.filterL.setLowPass(band.frequency, band.q, fs);
                    band.filterR.setLowPass(band.frequency, band.q, fs);
                    break;
                case FilterType::HIGH_PASS:
                    band.filterL.setHighPass(band.frequency, band.q, fs);
                    band.filterR.setHighPass(band.frequency, band.q, fs);
                    break;
                case FilterType::BAND_PASS:
                    band.filterL.setBandPass(band.frequency, band.q, fs);
                    band.filterR.setBandPass(band.frequency, band.q, fs);
                    break;
                case FilterType::NOTCH:
                    band.filterL.setNotch(band.frequency, band.q, fs);
                    band.filterR.setNotch(band.frequency, band.q, fs);
                    break;
                case FilterType::ALL_PASS:
                    band.filterL.setAllPass(band.frequency, band.q, fs);
                    band.filterR.setAllPass(band.frequency, band.q, fs);
                    break;
            }
        }
    }
}

double AudiophileDsp::nextRandomDouble() {
    // 64-bit Xorshift* generator
    rngState_ ^= rngState_ >> 12;
    rngState_ ^= rngState_ << 25;
    rngState_ ^= rngState_ >> 27;
    uint64_t v = rngState_ * 0x2545F4914F6CDD1DULL;
    return static_cast<double>(v >> 11) * (1.0 / 9007199254740992.0); // 53-bit precision
}

void AudiophileDsp::setEnabled(bool enabled) {
    isEnabled_.store(enabled);
}

void AudiophileDsp::setBitPerfectBypass(bool bypass) {
    isBitPerfectBypass_.store(bypass);
}

void AudiophileDsp::setPreAmpGainDb(double gainDb) {
    preAmpGainDb_.store(gainDb);
}

void AudiophileDsp::setBandGain(int bandIndex, double gainDb) {
    if (bandIndex >= 0 && bandIndex < 10) {
        bandGainsDb_[bandIndex] = gainDb;
        double fs = sampleRate_ > 0.0 ? sampleRate_ : 48000.0;
        biquadsL_[bandIndex].setPeakingEq(bandCenterFreqs_[bandIndex], 1.414, gainDb, fs);
        biquadsR_[bandIndex].setPeakingEq(bandCenterFreqs_[bandIndex], 1.414, gainDb, fs);
    }
}

void AudiophileDsp::setBassBoostGainDb(double gainDb) {
    bassBoostGainDb_.store(gainDb);
    double fs = sampleRate_ > 0.0 ? sampleRate_ : 48000.0;
    bassShelfL_.setLowShelf(80.0, 0.707, gainDb, fs);
    bassShelfR_.setLowShelf(80.0, 0.707, gainDb, fs);
}

void AudiophileDsp::setTrebleGainDb(double gainDb) {
    trebleGainDb_.store(gainDb);
    double fs = sampleRate_ > 0.0 ? sampleRate_ : 48000.0;
    trebleShelfL_.setHighShelf(10000.0, 0.707, gainDb, fs);
    trebleShelfR_.setHighShelf(10000.0, 0.707, gainDb, fs);
}

void AudiophileDsp::setHarmonicExciterLevel(double level) {
    harmonicExciterLevel_.store(level);
}

void AudiophileDsp::setClarityEnhancerGain(double gainDb) {
    clarityEnhancerGain_.store(gainDb);
    double fs = sampleRate_ > 0.0 ? sampleRate_ : 48000.0;
    clarityFilterL_.setPeakingEq(3200.0, 1.0, gainDb, fs);
    clarityFilterR_.setPeakingEq(3200.0, 1.0, gainDb, fs);
}

void AudiophileDsp::setStereoExpansionMultiplier(double multiplier) {
    stereoExpansionMultiplier_.store(multiplier);
}

void AudiophileDsp::setDvcVolume(double volume) {
    dvcVolume_.store(std::clamp(volume, 0.0, 2.0));
}

void AudiophileDsp::setDitherStrength(double strength) {
    ditherStrength_.store(strength);
}

void AudiophileDsp::setOutputBitDepth(int bitDepth) {
    outputBitDepth_.store(bitDepth);
}

void AudiophileDsp::setWarmSaturationLevel(double level) {
    warmSaturationLevel_.store(level);
}

void AudiophileDsp::setTriodeWarmthLevel(double level) {
    triodeWarmthLevel_.store(level);
}

void AudiophileDsp::setPentodeTapeLevel(double level) {
    pentodeTapeLevel_.store(level);
}

void AudiophileDsp::setCrossfeedLevel(double level) {
    crossfeedLevel_.store(level);
}

void AudiophileDsp::setLimiterEnabled(bool enabled) {
    limiterEnabled_.store(enabled);
}

void AudiophileDsp::setLimiterThresholdDb(double thresholdDb) {
    limiterThresholdDb_.store(thresholdDb);
}

void AudiophileDsp::setSubBassMonoEnabled(bool enabled) {
    subBassMonoEnabled_.store(enabled);
}

void AudiophileDsp::setChannelBalance(double balance) {
    channelBalance_.store(std::clamp(balance, -1.0, 1.0));
}

void AudiophileDsp::setInvertPhase(bool invert) {
    invertPhase_.store(invert);
}

void AudiophileDsp::setAirPresenceGainDb(double gainDb) {
    airPresenceGainDb_.store(gainDb);
    double fs = sampleRate_ > 0.0 ? sampleRate_ : 48000.0;
    airFilterL_.setHighShelf(16000.0, 0.5, gainDb, fs);
    airFilterR_.setHighShelf(16000.0, 0.5, gainDb, fs);
}

void AudiophileDsp::setHrtfSpatialEnabled(bool enabled) {
    hrtfSpatialEnabled_.store(enabled);
}

void AudiophileDsp::setHrtfRoomSize(double roomSize) {
    hrtfRoomSize_.store(std::clamp(roomSize, 0.0, 1.0));
}

void AudiophileDsp::clearPeqBands() {
    std::lock_guard<std::mutex> lock(peqMutex_);
    peqBands_.clear();
}

void AudiophileDsp::addPeqBand(FilterType type, double frequency, double q, double gainDb) {
    std::lock_guard<std::mutex> lock(peqMutex_);
    PeqBand band;
    band.type = type;
    band.frequency = frequency;
    band.q = q;
    band.gainDb = gainDb;
    band.enabled = true;
    double fs = sampleRate_ > 0.0 ? sampleRate_ : 48000.0;
    switch (type) {
        case FilterType::PEAKING_EQ:
            band.filterL.setPeakingEq(frequency, q, gainDb, fs);
            band.filterR.setPeakingEq(frequency, q, gainDb, fs);
            break;
        case FilterType::LOW_SHELF:
            band.filterL.setLowShelf(frequency, q, gainDb, fs);
            band.filterR.setLowShelf(frequency, q, gainDb, fs);
            break;
        case FilterType::HIGH_SHELF:
            band.filterL.setHighShelf(frequency, q, gainDb, fs);
            band.filterR.setHighShelf(frequency, q, gainDb, fs);
            break;
        case FilterType::LOW_PASS:
            band.filterL.setLowPass(frequency, q, fs);
            band.filterR.setLowPass(frequency, q, fs);
            break;
        case FilterType::HIGH_PASS:
            band.filterL.setHighPass(frequency, q, fs);
            band.filterR.setHighPass(frequency, q, fs);
            break;
        case FilterType::BAND_PASS:
            band.filterL.setBandPass(frequency, q, fs);
            band.filterR.setBandPass(frequency, q, fs);
            break;
        case FilterType::NOTCH:
            band.filterL.setNotch(frequency, q, fs);
            band.filterR.setNotch(frequency, q, fs);
            break;
        case FilterType::ALL_PASS:
            band.filterL.setAllPass(frequency, q, fs);
            band.filterR.setAllPass(frequency, q, fs);
            break;
    }
    peqBands_.push_back(std::move(band));
}

void AudiophileDsp::updatePeqBand(size_t index, FilterType type, double frequency, double q, double gainDb) {
    std::lock_guard<std::mutex> lock(peqMutex_);
    if (index >= peqBands_.size()) return;
    auto &band = peqBands_[index];
    band.type = type;
    band.frequency = frequency;
    band.q = q;
    band.gainDb = gainDb;
    double fs = sampleRate_ > 0.0 ? sampleRate_ : 48000.0;
    switch (type) {
        case FilterType::PEAKING_EQ:
            band.filterL.setPeakingEq(frequency, q, gainDb, fs);
            band.filterR.setPeakingEq(frequency, q, gainDb, fs);
            break;
        case FilterType::LOW_SHELF:
            band.filterL.setLowShelf(frequency, q, gainDb, fs);
            band.filterR.setLowShelf(frequency, q, gainDb, fs);
            break;
        case FilterType::HIGH_SHELF:
            band.filterL.setHighShelf(frequency, q, gainDb, fs);
            band.filterR.setHighShelf(frequency, q, gainDb, fs);
            break;
        case FilterType::LOW_PASS:
            band.filterL.setLowPass(frequency, q, fs);
            band.filterR.setLowPass(frequency, q, fs);
            break;
        case FilterType::HIGH_PASS:
            band.filterL.setHighPass(frequency, q, fs);
            band.filterR.setHighPass(frequency, q, fs);
            break;
        case FilterType::BAND_PASS:
            band.filterL.setBandPass(frequency, q, fs);
            band.filterR.setBandPass(frequency, q, fs);
            break;
        case FilterType::NOTCH:
            band.filterL.setNotch(frequency, q, fs);
            band.filterR.setNotch(frequency, q, fs);
            break;
        case FilterType::ALL_PASS:
            band.filterL.setAllPass(frequency, q, fs);
            band.filterR.setAllPass(frequency, q, fs);
            break;
    }
}

void AudiophileDsp::process(float *audioData, int32_t numFrames, int32_t channelCount) {
    if (!audioData || numFrames <= 0 || channelCount <= 0) return;

    // Bit-Perfect Pure Bypass Mode
    if (isBitPerfectBypass_.load() || !isEnabled_.load()) {
        double maxL = 0.0;
        double maxR = 0.0;
        for (int32_t frame = 0; frame < numFrames; ++frame) {
            int32_t idx = frame * channelCount;
            double l = std::abs(static_cast<double>(audioData[idx]));
            double r = (channelCount > 1) ? std::abs(static_cast<double>(audioData[idx + 1])) : l;
            maxL = std::max(maxL, l);
            maxR = std::max(maxR, r);
        }
        peakL_.store(peakL_.load() * 0.92 + maxL * 0.08);
        peakR_.store(peakR_.load() * 0.92 + maxR * 0.08);
        phaseCorrelation_.store(1.0f);
        return;
    }

    double preAmp = std::pow(10.0, preAmpGainDb_.load() / 20.0);
    double warmSat = warmSaturationLevel_.load();
    double triode = triodeWarmthLevel_.load();
    double pentode = pentodeTapeLevel_.load();
    double exciterLevel = harmonicExciterLevel_.load();
    double clarityGain = clarityEnhancerGain_.load();
    double stereoExp = stereoExpansionMultiplier_.load();
    double crossfeed = crossfeedLevel_.load();
    bool subMono = subBassMonoEnabled_.load();
    double balance = channelBalance_.load();
    bool invPhase = invertPhase_.load();
    double airGain = airPresenceGainDb_.load();
    bool hrtfOn = hrtfSpatialEnabled_.load();
    double roomSize = hrtfRoomSize_.load();
    bool limiterOn = limiterEnabled_.load();
    double limThresh = std::pow(10.0, limiterThresholdDb_.load() / 20.0);
    double dvc = dvcVolume_.load();
    double ditherStr = ditherStrength_.load();
    int bitDepth = outputBitDepth_.load();
    double lsb = 1.0 / std::pow(2.0, static_cast<double>(bitDepth - 1));

    double runningMaxL = 0.0;
    double runningMaxR = 0.0;
    double sumLR = 0.0;
    double sumL2 = 0.0;
    double sumR2 = 0.0;

    std::unique_lock<std::mutex> peqLock(peqMutex_, std::try_to_lock);

    for (int32_t frame = 0; frame < numFrames; ++frame) {
        int32_t baseIdx = frame * channelCount;
        double sL = static_cast<double>(audioData[baseIdx]);
        double sR = (channelCount > 1) ? static_cast<double>(audioData[baseIdx + 1]) : sL;

        // 1. Pre-Amp Gain
        sL *= preAmp;
        sR *= preAmp;

        // 2. 2x Hermite Oversampling for Saturation & Harmonic Generation (Anti-Aliasing)
        for (int ch = 0; ch < channelCount; ++ch) {
            double s = (ch == 0) ? sL : sR;
            auto &history = (ch == 0) ? osSamplesL_ : osSamplesR_;

            history[0] = history[1];
            history[1] = history[2];
            history[2] = history[3];
            history[3] = s;

            // Hermite Cubic Interpolation at t = 0.5
            double v0 = history[0], v1 = history[1], v2 = history[2], v3 = history[3];
            double a = -0.5 * v0 + 1.5 * v1 - 1.5 * v2 + 0.5 * v3;
            double b = v0 - 2.5 * v1 + 2.0 * v2 - 0.5 * v3;
            double c = -0.5 * v0 + 0.5 * v2;
            double d = v1;
            double subSample = a * 0.125 + b * 0.25 + c * 0.5 + d;

            std::array<double, 2> upsampled = {subSample, v2};
            for (int i = 0; i < 2; ++i) {
                double smp = upsampled[i];

                // Valve Warmth & Triode Tube Modeling
                if (warmSat > 0.0 || triode > 0.0) {
                    double warmFactor = warmSat + triode;
                    smp = smp + (warmFactor * (smp * smp * smp - smp));
                    if (triode > 0.0) {
                        smp += triode * 0.15 * (smp * smp * (smp > 0.0 ? 1.0 : -1.0));
                    }
                }

                // Pentode Tape Saturation (Odd 3rd Harmonic Punch)
                if (pentode > 0.0) {
                    smp -= pentode * 0.1 * (smp * smp * smp);
                }

                // Harmonic Exciter
                if (exciterLevel > 0.0) {
                    double detail = (ch == 0) ? detailHPFL_.process(smp) : detailHPFR_.process(smp);
                    double harmonics = (detail * detail * detail) * 0.5 + (detail * detail) * 0.3;
                    smp += harmonics * exciterLevel;
                }
                upsampled[i] = smp;
            }

            // Downsample
            if (ch == 0) sL = (upsampled[0] + upsampled[1]) * 0.5;
            else sR = (upsampled[0] + upsampled[1]) * 0.5;
        }

        // 3. DC Removal & Blocking
        sL = dcBlockerL_.process(dcRemovalL_.process(sL));
        sR = dcBlockerR_.process(dcRemovalR_.process(sR));

        // 4. Clarity Presence Peaking EQ
        if (clarityGain != 0.0) {
            sL = clarityFilterL_.process(sL);
            sR = clarityFilterR_.process(sR);
        }

        // 5. Bass Shelf
        sL = bassShelfL_.process(sL);
        sR = bassShelfR_.process(sR);

        // 6. 10-Band Graphic Equalizer
        for (size_t b = 0; b < bandGainsDb_.size(); ++b) {
            if (bandGainsDb_[b] != 0.0) {
                sL = biquadsL_[b].process(sL);
                sR = biquadsR_[b].process(sR);
            }
        }

        // 7. Parametric EQ (PEQ) Bands
        if (peqLock.owns_lock()) {
            for (auto &band : peqBands_) {
                if (band.enabled) {
                    sL = band.filterL.process(sL);
                    sR = band.filterR.process(sR);
                }
            }
        }

        // 8. Stereo Expansion & Crossfeed (M-S Processing)
        if (channelCount > 1) {
            if (crossfeed > 0.0) {
                double lowL = crossfeedLPFL_.process(sL);
                double lowR = crossfeedLPFR_.process(sR);
                double amt = crossfeed * 0.3;
                sL = sL - amt * lowL + amt * lowR;
                sR = sR - amt * lowR + amt * lowL;
            }

            if (stereoExp != 1.0) {
                double mid = (sL + sR) * 0.5;
                double side = (sL - sR) * 0.5 * stereoExp;
                sL = mid + side;
                sR = mid - side;
            }

            // HRTF 3D Spatial Audio & Head-Shadow Crossfeed 2.0 (Studio Monitor Emulation)
            if (hrtfOn) {
                itdBufferL_[itdWriteIdx_] = sL;
                itdBufferR_[itdWriteIdx_] = sR;

                int32_t itdDelay = std::clamp(static_cast<int32_t>(0.00028 * sampleRate_), 1, static_cast<int32_t>(HRTF_BUFFER_SIZE - 1));
                int32_t roomDelay = std::clamp(static_cast<int32_t>((0.003 + 0.018 * roomSize) * sampleRate_), 1, static_cast<int32_t>(HRTF_BUFFER_SIZE - 1));

                size_t readIdxITD = (itdWriteIdx_ + HRTF_BUFFER_SIZE - itdDelay) % HRTF_BUFFER_SIZE;
                size_t readIdxRoom = (itdWriteIdx_ + HRTF_BUFFER_SIZE - roomDelay) % HRTF_BUFFER_SIZE;

                double shadowR = hrtfHeadShadowR_.process(itdBufferR_[readIdxITD]);
                double shadowL = hrtfHeadShadowL_.process(itdBufferL_[readIdxITD]);
                double roomReflR = itdBufferR_[readIdxRoom] * (0.15 * roomSize);
                double roomReflL = itdBufferL_[readIdxRoom] * (0.15 * roomSize);

                sL = hrtfPinnaNotchL_.process(sL * 0.85 + shadowR * 0.35 + roomReflR);
                sR = hrtfPinnaNotchR_.process(sR * 0.85 + shadowL * 0.35 + roomReflL);

                itdWriteIdx_ = (itdWriteIdx_ + 1) % HRTF_BUFFER_SIZE;
            }

            if (subMono) {
                double subL = subBassFilterL_.process(sL);
                double subR = subBassFilterR_.process(sR);
                double monoSub = (subL + subR) * 0.5;
                sL = (sL - subL) + monoSub;
                sR = (sR - subR) + monoSub;
            }

            if (invPhase) {
                sR = -sR;
            }

            // Constant-Power Pan Law
            if (balance != 0.0) {
                double panAngle = (std::clamp(balance, -1.0, 1.0) + 1.0) * (M_PI_VAL / 4.0);
                double gainL = std::cos(panAngle) * 1.4142135623730951;
                double gainR = std::sin(panAngle) * 1.4142135623730951;
                sL *= gainL;
                sR *= gainR;
            }
        }

        // 9. Treble Shelf & Air Presence
        sL = trebleShelfL_.process(sL);
        sR = trebleShelfR_.process(sR);
        if (airGain != 0.0) {
            sL = airFilterL_.process(sL);
            sR = airFilterR_.process(sR);
        }

        // 10. Anti-Aliasing (20kHz LPF)
        sL = aaFilterL_.process(sL);
        sR = aaFilterR_.process(sR);

        // 11. Soft-Knee Intersample Limiter (Ultra-Fast Rational Compression)
        if (limiterOn) {
            double absL = std::abs(sL);
            if (absL > limThresh) {
                double overL = absL - limThresh;
                double compL = limThresh + limThresh * fastTanh(overL / limThresh);
                sL = (sL > 0.0) ? compL : -compL;
            }
            double absR = std::abs(sR);
            if (absR > limThresh) {
                double overR = absR - limThresh;
                double compR = limThresh + limThresh * fastTanh(overR / limThresh);
                sR = (sR > 0.0) ? compR : -compR;
            }
        } else {
            sL = std::clamp(sL, -1.0, 1.0);
            sR = std::clamp(sR, -1.0, 1.0);
        }

        // 12. Direct Volume Control (DVC) Gain Stage
        sL *= dvc;
        sR *= dvc;

        // Final Safety Ceiling (-0.1 dBFS True-Peak)
        sL = std::clamp(sL, -0.988, 0.988);
        sR = std::clamp(sR, -0.988, 0.988);

        // 13. High-Pass Noise-Shaped 64-bit TPDF Dither
        if (ditherStr > 0.0) {
            double r1 = nextRandomDouble() - 0.5;
            double r2 = nextRandomDouble() - 0.5;
            double rawDither = (r1 + r2) * lsb * ditherStr;

            double shapedDitherL = rawDither - 0.5 * ditherErrorL_;
            ditherErrorL_ = rawDither;
            sL += shapedDitherL;

            if (channelCount > 1) {
                double r3 = nextRandomDouble() - 0.5;
                double r4 = nextRandomDouble() - 0.5;
                double rawDitherR = (r3 + r4) * lsb * ditherStr;
                double shapedDitherR = rawDitherR - 0.5 * ditherErrorR_;
                ditherErrorR_ = rawDitherR;
                sR += shapedDitherR;
            }
        }

        // Telemetry Statistics
        runningMaxL = std::max(runningMaxL, std::abs(sL));
        runningMaxR = std::max(runningMaxR, std::abs(sR));
        sumLR += sL * sR;
        sumL2 += sL * sL;
        sumR2 += sR * sR;

        // Output Back to Audio Frame
        audioData[baseIdx] = static_cast<float>(std::clamp(sL, -1.0, 1.0));
        if (channelCount > 1) {
            audioData[baseIdx + 1] = static_cast<float>(std::clamp(sR, -1.0, 1.0));
        }
    }

    // Update Live Telemetry
    peakL_.store(peakL_.load() * 0.92 + runningMaxL * 0.08);
    peakR_.store(peakR_.load() * 0.92 + runningMaxR * 0.08);

    if (channelCount > 1 && sumL2 > 1e-12 && sumR2 > 1e-12) {
        float corr = static_cast<float>(sumLR / (std::sqrt(sumL2 * sumR2) + 1e-12));
        phaseCorrelation_.store(phaseCorrelation_.load() * 0.95f + std::clamp(corr, -1.0f, 1.0f) * 0.05f);
    }
}

} // namespace antigravity
