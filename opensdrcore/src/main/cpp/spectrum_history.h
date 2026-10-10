#pragma once

#include <array>
#include <cstdint>
#include <mutex>
#include <vector>

// Completed FFTs only. Independent of the Activity, polling cadence and PCM FIFO.
class SpectrumHistory {
public:
    static constexpr std::size_t bins = 256;
    static constexpr std::size_t capacity = 256;
    static constexpr uint64_t retention_ms = 22000; // 20 s display + audio delay.
    struct Context {
        uint64_t epoch = 0;
        uint32_t center_hz = 0;
        uint32_t sample_rate = 0;
        bool active = false;
    };
    struct Frame {
        uint64_t sequence = 0;
        uint64_t time_ms = 0;
        Context context;
        uint32_t audio_delay_ms = 0;
        std::array<uint8_t, bins> levels{};
    };

    void suspend();
    void resume(uint32_t center_hz, uint32_t sample_rate, uint64_t now_ms);
    Context context() const;
    bool append(const Context& observed, uint64_t time_ms, uint32_t audio_delay_ms,
                const std::array<int32_t, bins>& levels);
    std::vector<Frame> since(uint64_t sequence, uint64_t now_ms) const;

private:
    mutable std::mutex mutex_;
    Context context_;
    uint64_t ready_ms_ = 0;
    uint64_t next_sequence_ = 1;
    std::array<Frame, capacity> frames_{};
    std::size_t next_ = 0;
    std::size_t size_ = 0;
};

// Versioned LE wire format: 16-byte header, 40-byte metadata + 256 levels per row.
std::vector<uint8_t> encode_spectrum_history(const std::vector<SpectrumHistory::Frame>& frames);
