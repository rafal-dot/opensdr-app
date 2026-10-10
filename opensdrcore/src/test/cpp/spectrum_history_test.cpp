#include "spectrum_history.h"
#include <atomic>
#include <cassert>
#include <thread>

int main() {
    SpectrumHistory history;
    std::array<int32_t, SpectrumHistory::bins> levels{};
    levels.fill(42);
    history.resume(128803000, 240000, 1000);
    const auto first = history.context();
    assert(!history.append(first, 1050, 100, levels)); // Settling.
    assert(history.append(first, 1100, 100, levels));
    assert(!history.append(first, 1150, 100, levels)); // One row per 100 ms bucket.
    const auto initial = history.since(0, 1200);
    assert(initial.size() == 1 && initial[0].sequence == 1);
    assert(initial[0].levels[42] == 42 && initial[0].context.center_hz == 128803000);
    assert(history.since(0, 1200).size() == 1); // Reading is non-destructive.
    assert(history.since(1, 1200).empty());

    history.suspend();
    assert(!history.append(first, 1300, 0, levels));
    history.resume(118300500, 960000, 1400);
    assert(!history.append(first, 1600, 0, levels)); // Cross-retune USB block.
    const auto second = history.context();
    assert(history.append(second, 1600, 2000, levels));
    const auto retuned = history.since(0, 1600);
    assert(retuned.size() == 2 && retuned[0].context.epoch != retuned[1].context.epoch);
    assert(retuned[1].context.sample_rate == 960000 && retuned[1].audio_delay_ms == 1000);
    const auto encoded = encode_spectrum_history(retuned);
    assert(encoded.size() == 16 + 2 * (40 + 256));
    assert(encoded[0] == 0x31 && encoded[3] == 0x53 && encoded[8] == 2);
    assert(encoded[16] == 1 && encoded[56] == 42);

    for (uint64_t t = 1700; t < 60000; t += 100) assert(history.append(second, t, 0, levels));
    const auto recent = history.since(0, 60000);
    assert(recent.size() <= SpectrumHistory::capacity);
    assert(recent.front().time_ms >= 38000);
    for (std::size_t i = 1; i < recent.size(); ++i) assert(recent[i].sequence > recent[i - 1].sequence);
    history.suspend();
    assert(!history.since(0, 60000).empty()); // Stop does not erase the last seconds.
    assert(history.since(0, 83000).empty());

    SpectrumHistory concurrent;
    concurrent.resume(100000000, 240000, 0);
    const auto context = concurrent.context();
    std::atomic<bool> finished{false};
    std::thread writer([&] {
        for (uint64_t t = 100; t < 100000; t += 100) concurrent.append(context, t, 0, levels);
        finished = true;
    });
    while (!finished) {
        const auto frames = concurrent.since(0, 0);
        assert(frames.size() <= SpectrumHistory::capacity);
        for (std::size_t i = 1; i < frames.size(); ++i) assert(frames[i].sequence > frames[i - 1].sequence);
    }
    writer.join();
}
