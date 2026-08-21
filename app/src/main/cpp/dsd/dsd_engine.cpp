#include "dsd_engine.h"
#include <algorithm>
#include <cmath>

namespace antigravity {

DsdEngine::DsdEngine() {
    reset();
}

void DsdEngine::reset() {
    dopMarker_ = 0x05;
    firHistoryL_.assign(64, 0.0);
    firHistoryR_.assign(64, 0.0);
}

void DsdEngine::configure(DsdMode mode, int32_t dsdRate) {
    mode_ = mode;
    dsdRate_ = dsdRate > 0 ? dsdRate : 2822400;
    reset();
}

int32_t DsdEngine::convertDsdToDoP(const uint8_t *dsdBytesL, const uint8_t *dsdBytesR, int32_t numBytes, std::vector<int32_t> &dopOutFrames) {
    if (!dsdBytesL || !dsdBytesR || numBytes < 2) return 0;

    // DoP Standard: 16-bit DSD payload wrapped in 24-bit PCM word (shifted to 32-bit int)
    // Frame format: [Marker (8-bit) | DSD payload (16-bit) | 0x00 (8-bit)]
    // Marker toggles between 0x05 and 0xFA every 16-bit word
    int32_t words16 = numBytes / 2;
    dopOutFrames.resize(words16 * 2); // Stereo

    for (int32_t i = 0; i < words16; ++i) {
        uint16_t sampleL = (static_cast<uint16_t>(dsdBytesL[i * 2]) << 8) | dsdBytesL[i * 2 + 1];
        uint16_t sampleR = (static_cast<uint16_t>(dsdBytesR[i * 2]) << 8) | dsdBytesR[i * 2 + 1];

        uint8_t marker = dopMarker_;
        dopMarker_ = (dopMarker_ == 0x05) ? 0xFA : 0x05; // Toggle marker

        int32_t dopWordL = (static_cast<int32_t>(marker) << 24) | (static_cast<int32_t>(sampleL) << 8);
        int32_t dopWordR = (static_cast<int32_t>(marker) << 24) | (static_cast<int32_t>(sampleR) << 8);

        dopOutFrames[i * 2] = dopWordL;
        dopOutFrames[i * 2 + 1] = dopWordR;
    }

    return words16;
}

int32_t DsdEngine::decimateDsdToPcm(const uint8_t *dsdBytesL, const uint8_t *dsdBytesR, int32_t numBytes, std::vector<float> &pcmOut) {
    if (!dsdBytesL || !dsdBytesR || numBytes <= 0) return 0;

    // 8x bit-level decimation FIR kernel (1 byte = 8 bits -> 1 PCM sample at 352.8 kHz / 88.2 kHz)
    // Sinc4 decimation filter approximation for 1-bit PDM to PCM
    static constexpr double firCoeffs[8] = {
        0.03125, 0.09375, 0.15625, 0.21875, 0.21875, 0.15625, 0.09375, 0.03125
    };

    pcmOut.resize(numBytes * 2); // Stereo

    for (int32_t i = 0; i < numBytes; ++i) {
        uint8_t byteL = dsdBytesL[i];
        uint8_t byteR = dsdBytesR[i];

        double accL = 0.0;
        double accR = 0.0;

        for (int bit = 0; bit < 8; ++bit) {
            double bitValL = ((byteL >> (7 - bit)) & 1) ? 1.0 : -1.0;
            double bitValR = ((byteR >> (7 - bit)) & 1) ? 1.0 : -1.0;

            accL += bitValL * firCoeffs[bit];
            accR += bitValR * firCoeffs[bit];
        }

        // Soft-clip decimation filter output
        pcmOut[i * 2] = static_cast<float>(std::clamp(accL * 0.95, -1.0, 1.0));
        pcmOut[i * 2 + 1] = static_cast<float>(std::clamp(accR * 0.95, -1.0, 1.0));
    }

    return numBytes;
}

} // namespace antigravity
