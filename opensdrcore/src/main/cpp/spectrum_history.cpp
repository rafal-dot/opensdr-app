#include "spectrum_history.h"
#include <algorithm>

void SpectrumHistory::suspend() {
    std::lock_guard<std::mutex> lock(mutex_);
    ++context_.epoch;
    context_.active = false;
}

void SpectrumHistory::resume(uint32_t center_hz, uint32_t sample_rate, uint64_t now_ms) {
    std::lock_guard<std::mutex> lock(mutex_);
    ++context_.epoch;
    context_.center_hz = center_hz;
    context_.sample_rate = sample_rate;
    context_.active = true;
    ready_ms_ = now_ms + 100; // Do not label buffered/transitional IQ as the new band.
}

SpectrumHistory::Context SpectrumHistory::context() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return context_;
}

bool SpectrumHistory::append(const Context& observed, uint64_t time_ms, uint32_t audio_delay_ms,
                             const std::array<int32_t, bins>& levels) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!observed.active || !context_.active || observed.epoch != context_.epoch || time_ms < ready_ms_) return false;
    if (size_ > 0) {
        const auto& last = frames_[(next_ + capacity - 1) % capacity];
        if (time_ms <= last.time_ms || time_ms / 100 == last.time_ms / 100) return false;
    }
    auto& frame = frames_[next_];
    frame.sequence = next_sequence_++;
    frame.time_ms = time_ms;
    frame.context = context_;
    frame.audio_delay_ms = std::min(audio_delay_ms, uint32_t{1000});
    for (std::size_t i = 0; i < bins; ++i) frame.levels[i] = static_cast<uint8_t>(std::clamp(levels[i], 0, 100));
    next_ = (next_ + 1) % capacity;
    size_ = std::min(size_ + 1, capacity);
    return true;
}

std::vector<SpectrumHistory::Frame> SpectrumHistory::since(uint64_t sequence, uint64_t now_ms) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<Frame> result;
    for (std::size_t i = 0; i < size_; ++i) {
        const auto& frame = frames_[(next_ + capacity - size_ + i) % capacity];
        if (frame.sequence > sequence && (now_ms <= frame.time_ms || now_ms - frame.time_ms <= retention_ms))
            result.push_back(frame);
    }
    return result;
}

std::vector<uint8_t> encode_spectrum_history(const std::vector<SpectrumHistory::Frame>& frames) {
    std::vector<uint8_t> result;
    result.reserve(16 + frames.size() * (40 + SpectrumHistory::bins));
    const auto write = [&result](uint64_t value, int bytes) {
        for (int i = 0; i < bytes; ++i) result.push_back(static_cast<uint8_t>(value >> (i * 8)));
    };
    write(0x53504831, 4); // SPH1
    write(SpectrumHistory::bins, 4);
    write(frames.size(), 4);
    write(0, 4);
    for (const auto& frame : frames) {
        write(frame.sequence, 8);
        write(frame.time_ms, 8);
        write(frame.context.epoch, 8);
        write(frame.context.center_hz, 8);
        write(frame.context.sample_rate, 4);
        write(frame.audio_delay_ms, 4);
        result.insert(result.end(), frame.levels.begin(), frame.levels.end());
    }
    return result;
}
