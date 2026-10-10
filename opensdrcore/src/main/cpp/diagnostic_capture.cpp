#include "diagnostic_capture.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <ctime>

namespace {
void put_le(uint8_t* output, uint64_t value, int size) {
    for (int i = 0; i < size; ++i) output[i] = static_cast<uint8_t>(value >> (i * 8));
}
}

DiagnosticCapture& diagnostic_capture() {
    static DiagnosticCapture capture;
    return capture;
}

uint64_t diagnostic_time_ns() {
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return static_cast<uint64_t>(time.tv_sec) * 1000000000ULL + time.tv_nsec;
}

bool DiagnosticCapture::start(std::size_t capacity) {
    if (capacity < header_size || capacity > 64 * 1024 * 1024) return false;
    std::vector<uint8_t> replacement(capacity);
    std::lock_guard<std::mutex> lock(mutex_);
    if (status_ == active || size_ != 0) return false;
    bytes_.swap(replacement);
    read_ = write_ = size_ = 0;
    iq_samples_ = audio_frames_ = produced_frames_ = 0;
    status_.store(active, std::memory_order_release);
    return true;
}

void DiagnosticCapture::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (status_ == active) status_.store(stopped, std::memory_order_release);
}

void DiagnosticCapture::append(const void* data, std::size_t size) {
    if (size == 0) return;
    const auto first = std::min(size, bytes_.size() - write_);
    std::memcpy(bytes_.data() + write_, data, first);
    std::memcpy(bytes_.data(), static_cast<const uint8_t*>(data) + first, size - first);
    write_ = (write_ + size) % bytes_.size();
    size_ += size;
}

void DiagnosticCapture::copy_out(void* data, std::size_t size) {
    const auto first = std::min(size, bytes_.size() - read_);
    std::memcpy(data, bytes_.data() + read_, first);
    std::memcpy(static_cast<uint8_t*>(data) + first, bytes_.data(), size - first);
    read_ = (read_ + size) % bytes_.size();
    size_ -= size;
}

bool DiagnosticCapture::offer(Kind kind, const void* data, std::size_t size,
                              uint64_t time_ns, uint64_t queued_audio_frames) {
    if (status() != active) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    if (status_ != active) return false;
    // Stop at the first missing packet. Never manufacture a continuous recording
    // by silently omitting samples when a provider stalls or storage fills up.
    if (size > UINT32_MAX || (size != 0 && data == nullptr) ||
        (kind != event && size % 2 != 0) || size + header_size > bytes_.size() - size_) {
        status_.store(overflow, std::memory_order_release);
        return false;
    }
    std::array<uint8_t, header_size> header{};
    put_le(header.data(), kind, 4);
    put_le(header.data() + 4, size, 4);
    put_le(header.data() + 8, time_ns, 8);
    put_le(header.data() + 16, iq_samples_, 8);
    put_le(header.data() + 24, audio_frames_, 8);
    put_le(header.data() + 32, produced_frames_, 8);
    put_le(header.data() + 40, queued_audio_frames, 8);
    append(header.data(), header.size());
    append(data, size);
    if (kind == iq) iq_samples_ += size / 2;
    if (kind == audio) audio_frames_ += size / 2;
    return true;
}

void DiagnosticCapture::produced(std::size_t count) {
    if (status() != active) return;
    std::lock_guard<std::mutex> lock(mutex_);
    if (status_ == active) produced_frames_ += count;
}

std::size_t DiagnosticCapture::drain(uint8_t* destination, std::size_t capacity) {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto count = std::min(size_, capacity);
    if (count > 0) copy_out(destination, count);
    return count;
}
