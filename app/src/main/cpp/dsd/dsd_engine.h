#pragma once

#include <vector>
#include <cstdint>

namespace antigravity {

enum class DsdMode {
    NATIVE_DSD = 0,
    DSD_OVER_PCM = 1, // DoP
    DSD_TO_PCM = 2    // High-Res Decimation
};

class DsdEngine {
public:
    DsdEngine();
    ~DsdEngine() = default;

    void configure(DsdMode mode, int32_t dsdRate = 2822400); // 2.8224 MHz (DSD64)

    // Converts raw DSD 1-bit stream bytes into DoP 24/32-bit PCM frames
    int32_t convertDsdToDoP(const uint8_t *dsdBytesL, const uint8_t *dsdBytesR, int32_t numBytes, std::vector<int32_t> &dopOutFrames);

    // Decimates raw DSD 1-bit stream bytes to 32-bit Float PCM (88.2kHz / 176.4kHz / 352.8kHz)
    int32_t decimateDsdToPcm(const uint8_t *dsdBytesL, const uint8_t *dsdBytesR, int32_t numBytes, std::vector<float> &pcmOut);

    void reset();

private:
    DsdMode mode_ = DsdMode::DSD_TO_PCM;
    int32_t dsdRate_ = 2822400;
    uint8_t dopMarker_ = 0x05;

    // Multistage decimation FIR state
    std::vector<double> firHistoryL_;
    std::vector<double> firHistoryR_;
};

} // namespace antigravity
