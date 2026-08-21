#include "biquad_filter.h"
#include <algorithm>

namespace antigravity {

static constexpr double M_PI_VAL = 3.14159265358979323846;

BiquadFilter::BiquadFilter() {
    reset();
}

void BiquadFilter::reset() {
    z1_ = 0.0;
    z2_ = 0.0;
    b0_ = 1.0;
    b1_ = 0.0;
    b2_ = 0.0;
    a1_ = 0.0;
    a2_ = 0.0;
}

void BiquadFilter::setPeakingEq(double frequency, double q, double gainDb, double sampleRate) {
    if (sampleRate <= 0.0) return;
    frequency = std::clamp(frequency, 10.0, sampleRate * 0.499);
    q = std::max(q, 0.01);

    double A = std::pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    double alpha = std::sin(w0) / (2.0 * q);
    double cosW0 = std::cos(w0);

    double b0 = 1.0 + alpha * A;
    double b1 = -2.0 * cosW0;
    double b2 = 1.0 - alpha * A;
    double a0 = 1.0 + alpha / A;
    double a1 = -2.0 * cosW0;
    double a2 = 1.0 - alpha / A;

    b0_ = b0 / a0;
    b1_ = b1 / a0;
    b2_ = b2 / a0;
    a1_ = a1 / a0;
    a2_ = a2 / a0;
}

void BiquadFilter::setLowShelf(double frequency, double q, double gainDb, double sampleRate) {
    if (sampleRate <= 0.0) return;
    frequency = std::clamp(frequency, 10.0, sampleRate * 0.499);
    q = std::max(q, 0.01);

    double A = std::pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    double cosW0 = std::cos(w0);
    double sinW0 = std::sin(w0);
    double alpha = sinW0 / (2.0 * q);
    double sqrtA = std::sqrt(A);

    double b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha);
    double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0);
    double b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha);
    double a0 = (A + 1.0) + (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha;
    double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0);
    double a2 = (A + 1.0) + (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha;

    b0_ = b0 / a0;
    b1_ = b1 / a0;
    b2_ = b2 / a0;
    a1_ = a1 / a0;
    a2_ = a2 / a0;
}

void BiquadFilter::setHighShelf(double frequency, double q, double gainDb, double sampleRate) {
    if (sampleRate <= 0.0) return;
    frequency = std::clamp(frequency, 10.0, sampleRate * 0.499);
    q = std::max(q, 0.01);

    double A = std::pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    double cosW0 = std::cos(w0);
    double sinW0 = std::sin(w0);
    double alpha = sinW0 / (2.0 * q);
    double sqrtA = std::sqrt(A);

    double b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha);
    double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0);
    double b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha);
    double a0 = (A + 1.0) - (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha;
    double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0);
    double a2 = (A + 1.0) - (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha;

    b0_ = b0 / a0;
    b1_ = b1 / a0;
    b2_ = b2 / a0;
    a1_ = a1 / a0;
    a2_ = a2 / a0;
}

void BiquadFilter::setLowPass(double frequency, double q, double sampleRate) {
    if (sampleRate <= 0.0) return;
    frequency = std::clamp(frequency, 10.0, sampleRate * 0.499);
    q = std::max(q, 0.01);

    double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    double cosW0 = std::cos(w0);
    double alpha = std::sin(w0) / (2.0 * q);

    double b0 = (1.0 - cosW0) / 2.0;
    double b1 = 1.0 - cosW0;
    double b2 = (1.0 - cosW0) / 2.0;
    double a0 = 1.0 + alpha;
    double a1 = -2.0 * cosW0;
    double a2 = 1.0 - alpha;

    b0_ = b0 / a0;
    b1_ = b1 / a0;
    b2_ = b2 / a0;
    a1_ = a1 / a0;
    a2_ = a2 / a0;
}

void BiquadFilter::setHighPass(double frequency, double q, double sampleRate) {
    if (sampleRate <= 0.0) return;
    frequency = std::clamp(frequency, 10.0, sampleRate * 0.499);
    q = std::max(q, 0.01);

    double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    double cosW0 = std::cos(w0);
    double alpha = std::sin(w0) / (2.0 * q);

    double b0 = (1.0 + cosW0) / 2.0;
    double b1 = -(1.0 + cosW0);
    double b2 = (1.0 + cosW0) / 2.0;
    double a0 = 1.0 + alpha;
    double a1 = -2.0 * cosW0;
    double a2 = 1.0 - alpha;

    b0_ = b0 / a0;
    b1_ = b1 / a0;
    b2_ = b2 / a0;
    a1_ = a1 / a0;
    a2_ = a2 / a0;
}

void BiquadFilter::setBandPass(double frequency, double q, double sampleRate) {
    if (sampleRate <= 0.0) return;
    frequency = std::clamp(frequency, 10.0, sampleRate * 0.499);
    q = std::max(q, 0.01);

    double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    double cosW0 = std::cos(w0);
    double alpha = std::sin(w0) / (2.0 * q);

    double b0 = alpha;
    double b1 = 0.0;
    double b2 = -alpha;
    double a0 = 1.0 + alpha;
    double a1 = -2.0 * cosW0;
    double a2 = 1.0 - alpha;

    b0_ = b0 / a0;
    b1_ = b1 / a0;
    b2_ = b2 / a0;
    a1_ = a1 / a0;
    a2_ = a2 / a0;
}

void BiquadFilter::setNotch(double frequency, double q, double sampleRate) {
    if (sampleRate <= 0.0) return;
    frequency = std::clamp(frequency, 10.0, sampleRate * 0.499);
    q = std::max(q, 0.01);

    double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    double cosW0 = std::cos(w0);
    double alpha = std::sin(w0) / (2.0 * q);

    double b0 = 1.0;
    double b1 = -2.0 * cosW0;
    double b2 = 1.0;
    double a0 = 1.0 + alpha;
    double a1 = -2.0 * cosW0;
    double a2 = 1.0 - alpha;

    b0_ = b0 / a0;
    b1_ = b1 / a0;
    b2_ = b2 / a0;
    a1_ = a1 / a0;
    a2_ = a2 / a0;
}

void BiquadFilter::setAllPass(double frequency, double q, double sampleRate) {
    if (sampleRate <= 0.0) return;
    frequency = std::clamp(frequency, 10.0, sampleRate * 0.499);
    q = std::max(q, 0.01);

    double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    double cosW0 = std::cos(w0);
    double alpha = std::sin(w0) / (2.0 * q);

    double b0 = 1.0 - alpha;
    double b1 = -2.0 * cosW0;
    double b2 = 1.0 + alpha;
    double a0 = 1.0 + alpha;
    double a1 = -2.0 * cosW0;
    double a2 = 1.0 - alpha;

    b0_ = b0 / a0;
    b1_ = b1 / a0;
    b2_ = b2 / a0;
    a1_ = a1 / a0;
    a2_ = a2 / a0;
}

} // namespace antigravity
