#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

// Process-wide receiver tap. Producers only copy into bounded, preallocated RAM;
// the Android writer drains packets and owns all filesystem operations.
class DiagnosticCapture {
public:
    static constexpr std::size_t header_size = 48;
    enum Kind : uint32_t { iq = 1, audio = 2, event = 3 };
    enum Status : int { stopped = 0, active = 1, overflow = 2 };

    bool start(std::size_t capacity);
    void stop();
    int status() const { return status_.load(std::memory_order_acquire); }
    bool offer(Kind kind, const void* data, std::size_t size, uint64_t time_ns,
               uint64_t queued_audio_frames = 0);
    void produced(std::size_t count);
    std::size_t drain(uint8_t* destination, std::size_t capacity);

private:
    void append(const void* data, std::size_t size);
    void copy_out(void* data, std::size_t size);
    std::atomic<int> status_{stopped};
    std::mutex mutex_;
    std::vector<uint8_t> bytes_;
    std::size_t read_ = 0, write_ = 0, size_ = 0;
    uint64_t iq_samples_ = 0, audio_frames_ = 0, produced_frames_ = 0;
};

DiagnosticCapture& diagnostic_capture();
uint64_t diagnostic_time_ns();
