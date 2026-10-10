#include "diagnostic_capture.h"
#include <array>
#include <cstdlib>
#include <iostream>
#include <thread>
#include <vector>

static void check(bool condition) {
    if (!condition) { std::cerr << "Diagnostic capture check failed\n"; std::abort(); }
}

static uint64_t le(const uint8_t* bytes, int count) {
    uint64_t value = 0;
    for (int i = 0; i < count; ++i) value |= uint64_t(bytes[i]) << (8 * i);
    return value;
}

static void framing_and_wrap() {
    DiagnosticCapture capture;
    const std::array<uint8_t, 4> iq{0, 255, 127, 128};
    std::array<uint8_t, 128> output{};
    check(capture.start(128));
    check(!capture.start(128));
    check(capture.offer(DiagnosticCapture::iq, iq.data(), 4, 101));
    check(capture.drain(output.data(), output.size()) == 52);
    check(le(output.data(), 4) == 1 && le(output.data() + 4, 4) == 4);
    check(le(output.data() + 8, 8) == 101 && le(output.data() + 16, 8) == 0);
    check(output[48] == 0 && output[49] == 255 && output[51] == 128);
    capture.produced(96);
    check(capture.offer(DiagnosticCapture::audio, iq.data(), 4, 102, 1234));
    check(capture.offer(DiagnosticCapture::iq, iq.data(), 4, 103));
    check(capture.drain(output.data(), output.size()) == 104);
    check(le(output.data() + 16, 8) == 2 && le(output.data() + 32, 8) == 96);
    check(le(output.data() + 40, 8) == 1234 && le(output.data() + 52 + 24, 8) == 2);
    capture.stop();
    check(capture.status() == DiagnosticCapture::stopped);
    check(!capture.offer(DiagnosticCapture::iq, iq.data(), 4, 104));
    check(capture.start(128));
    check(capture.offer(DiagnosticCapture::iq, iq.data(), 4, 105));
    check(capture.drain(output.data(), 20) == 20);
    check(capture.drain(output.data() + 20, output.size() - 20) == 32);
    check(le(output.data() + 16, 8) == 0);
}

static void overflow_is_terminal() {
    DiagnosticCapture capture;
    std::array<uint8_t, 4> samples{1, 2, 3, 4};
    std::array<uint8_t, 128> output{};
    check(capture.start(64));
    check(capture.offer(DiagnosticCapture::iq, samples.data(), samples.size(), 1));
    check(!capture.offer(DiagnosticCapture::audio, samples.data(), samples.size(), 2));
    capture.stop();
    check(capture.status() == DiagnosticCapture::overflow);
    check(!capture.start(64)); // A new session must not erase undrained data.
    check(capture.drain(output.data(), output.size()) == 52);
    check(!capture.offer(DiagnosticCapture::iq, samples.data(), samples.size(), 3));
    check(capture.start(64));
    check(!capture.offer(DiagnosticCapture::iq, samples.data(), 3, 4));
}

static void concurrent_producers() {
    DiagnosticCapture capture;
    check(capture.start(4 * 1024 * 1024));
    auto producer = [&](DiagnosticCapture::Kind kind) {
        uint8_t samples[2]{0x55, 0xaa};
        for (int i = 0; i < 10000; ++i) check(capture.offer(kind, samples, 2, i));
    };
    std::thread iq(producer, DiagnosticCapture::iq);
    std::thread audio(producer, DiagnosticCapture::audio);
    iq.join(); audio.join();
    capture.stop();
    std::vector<uint8_t> data(4 * 1024 * 1024);
    const auto size = capture.drain(data.data(), data.size());
    check(size == 20000 * 50);
    uint64_t iqCount = 0, audioCount = 0;
    for (std::size_t offset = 0; offset < size; offset += 50) {
        check(le(data.data() + offset + 16, 8) == iqCount);
        check(le(data.data() + offset + 24, 8) == audioCount);
        if (le(data.data() + offset, 4) == DiagnosticCapture::iq) ++iqCount; else ++audioCount;
    }
    check(iqCount == 10000 && audioCount == 10000);
}

int main() {
    framing_and_wrap();
    overflow_is_terminal();
    concurrent_producers();
    std::cout << "Diagnostic capture tests passed\n";
}
