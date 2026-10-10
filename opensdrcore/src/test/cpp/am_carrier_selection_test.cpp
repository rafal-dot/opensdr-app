#include "am_test_config.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {
constexpr double kPi = 3.14159265358979323846;

void check(bool condition, const char* message) {
    if (!condition) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}

struct Reception {
    double tone = 0;
    double offset = 0;
    int iq_clip = 0;
};

// Raw, unsigned 8-bit IQ: neither the wanted signal nor the neighbor clips.
Reception receive(AmPipeline& pipeline, int rate, double carrier, double neighbor = 0,
                  double neighbor_hz = 5333.33, double amplitude = 30,
                  int block_samples = 2048, double neighbor_tone_hz = 0) {
    std::vector<int8_t> iq(block_samples * 2);
    std::vector<int16_t> pcm(block_samples * 2);
    double real = 0, imaginary = 0, offset_sum = 0;
    int output_index = 0, measured = 0, blocks = 0, clips = 0;
    for (int start = 0; start < rate * 2; start += block_samples) {
        for (int n = 0; n < block_samples; ++n) {
            const double t = (start + n) / double(rate);
            const double magnitude = amplitude * (1 + .8 * std::cos(2 * kPi * 1000 * t));
            const double phase = 2 * kPi * carrier * t;
            const double other = 2 * kPi * neighbor_hz * t;
            const double other_magnitude = neighbor * (neighbor_tone_hz > 0
                ? 1 + .9 * std::cos(2 * kPi * neighbor_tone_hz * t) : 1);
            iq[n * 2] = static_cast<int8_t>(std::lround(128 + magnitude * std::cos(phase) + other_magnitude * std::cos(other)));
            iq[n * 2 + 1] = static_cast<int8_t>(std::lround(128 + magnitude * std::sin(phase) + other_magnitude * std::sin(other)));
        }
        const auto result = pipeline.process(iq.data(), iq.size(), pcm.data(), pcm.size());
        if (start > rate) {
            offset_sum += result.estimated_carrier_offset_hz;
            clips = std::max(clips, result.iq_clip_percent);
            ++blocks;
        }
        for (int n = 0; n < result.pcm_samples_written; ++n, ++output_index) {
            if (output_index < 48000) continue;
            const double phase = 2 * kPi * 1000 * output_index / 48000;
            real += pcm[n] * std::cos(phase);
            imaginary += pcm[n] * std::sin(phase);
            ++measured;
        }
    }
    check(measured > 0 && blocks > 0, "Carrier test must produce PCM and metrics");
    return {2 * std::hypot(real, imaginary) / measured, offset_sum / blocks, clips};
}

void test_neighbor_does_not_pull_filter() {
    for (int rate : {240000, 480000, 960000}) {
        for (int mode : {1, 2}) {
            auto config = clean_voice();
            config.input_sample_rate_hz = rate;
            config.demod_mode = mode;
            if (mode == 2) config.simple_am_enabled = false;
            for (double sign : {-1., 1.}) {
                AmPipeline clean(config), interfered(config);
                const auto reference = receive(clean, rate, sign * -3000);
                const auto actual = receive(interfered, rate, sign * -3000, 25, sign * 5333.33);
                std::cout << "rate=" << rate << " mode=" << mode << " sign=" << sign
                          << " offset=" << actual.offset << " tone ratio=" << actual.tone / reference.tone << '\n';
                check(actual.iq_clip == 0, "Neighbor regression must not overload IQ");
                check(std::abs(actual.offset - sign * -3000) < 150,
                      "AM must select a real carrier, not the average of two carriers");
                check(actual.tone > reference.tone * .8,
                      "A neighboring carrier must not steer the filter away from wanted speech");
            }
        }
    }
}

void test_station_changes_and_retune() {
    for (int rate : {240000, 480000, 960000}) {
        auto config = clean_voice();
        config.input_sample_rate_hz = rate;
        AmPipeline pipeline(config);
        receive(pipeline, rate, -3000);
        // Both RF 128.800 and literal 128.805 must work, including a weak next station.
        const auto next = receive(pipeline, rate, 2000, 0, 0, 3);
        check(std::abs(next.offset - 2000) < 150 && next.tone > 500,
              "A weak new carrier must replace a disappeared strong station");
        pipeline.note_retune();
        const auto retuned = receive(pipeline, rate, -5500, 0, 0, 3, 510);
        check(std::abs(retuned.offset + 5500) < 150 && retuned.tone > 500,
              "Retune must discard the old carrier and work across USB block boundaries");
    }
}

void test_am_acquisition_near_expanded_edges() {
    for (int rate : {240000, 480000, 960000}) {
        for (int mode : {1, 2}) {
            auto config = clean_voice();
            config.input_sample_rate_hz = rate;
            config.demod_mode = mode;
            if (mode == 2) config.simple_am_enabled = false;
            AmPipeline reference_pipeline(config);
            const auto reference = receive(reference_pipeline, rate, -3000);
            for (double offset : {-6490., 6490.}) {
                AmPipeline pipeline(config);
                const auto actual = receive(pipeline, rate, offset);
                check(actual.iq_clip == 0, "Expanded search test must not overload IQ");
                check(std::abs(actual.offset - offset) < 50,
                      "AM must acquire carriers near both +/-6500 Hz search edges");
                check(actual.tone > reference.tone * .8,
                      "Expanded acquisition filters must preserve off-center AM speech");
            }
        }
    }
}

void test_squelch_update_preserves_carrier() {
    for (int mode : {1, 2}) {
        auto config = clean_voice();
        config.demod_mode = mode;
        AmPipeline pipeline(config);
        receive(pipeline, 240000, -3000);
        config.squelch_close_hang_ms = 600;
        check(pipeline.update_config(config), "Squelch timing update must succeed");
        const auto updated = receive(pipeline, 240000, -3000, 40, 5333.33, 10);
        check(std::abs(updated.offset + 3000) < 150 && updated.tone > 500,
              "Squelch-only update must preserve the station despite a louder neighbor");
    }
}

void test_modulated_outside_neighbor() {
    for (int rate : {240000, 480000, 960000}) {
        auto config = clean_voice();
        config.input_sample_rate_hz = rate;
        AmPipeline reference_pipeline(config);
        const auto reference = receive(reference_pipeline, rate, -3000, 0, 0, 3);
        for (double sign : {-1., 1.}) {
            for (double outside : {6700., 7500., 8300., 9900.}) {
                const double tone = outside > 9000 ? 3500 : 2200;
                AmPipeline pipeline(config);
                const auto actual = receive(pipeline, rate, sign * -3000,
                    40, sign * outside, 3, 2048, tone);
                std::cout << "Modulated neighbor rate=" << rate << " outside=" << sign * outside
                          << " offset=" << actual.offset << " tone ratio=" << actual.tone / reference.tone << '\n';
                check(actual.iq_clip == 0, "Modulated neighbor regression must not overload IQ");
                check(std::abs(actual.offset - sign * -3000) < 50,
                      "An outside transmitter's sideband must not be selected as the AM carrier");
                check(actual.tone > reference.tone * .85,
                      "Rejecting the outside neighbor must preserve weak wanted speech");
            }
        }
    }
}

void test_acquisition_anti_aliasing() {
    for (int rate : {240000, 480000, 960000}) {
        auto config = clean_voice();
        config.input_sample_rate_hz = rate;
        AmPipeline reference_pipeline(config);
        const auto reference = receive(reference_pipeline, rate, -3000, 0, 0, 3);
        for (double sign : {-1., 1.}) {
            AmPipeline pipeline(config);
            const auto actual = receive(pipeline, rate, -3000, 50, sign * (rate / 5.0 - 3000), 3);
            check(actual.iq_clip == 0 && std::abs(actual.offset + 3000) < 50,
                  "The wider acquisition path must not alias distant carriers into the search band");
            check(std::abs(actual.tone / reference.tone - 1) < .15,
                  "Anti-alias filtering must protect the wanted audio");
        }
    }
}

void test_am_pipeline_rejects_outside_carriers() {
    for (int rate : {240000, 480000, 960000}) {
        for (int mode : {1, 2}) {
            auto config = clean_voice();
            config.input_sample_rate_hz = rate;
            config.demod_mode = mode;
            for (double offset : {-6600., 6600.}) {
                AmPipeline pipeline(config);
                std::vector<int8_t> iq(4096);
                std::vector<int16_t> pcm(4096);
                int observed_offset = 0;
                for (int start = 0; start < rate / 2; start += 2048) {
                    for (int n = 0; n < 2048; ++n) {
                        const double phase = 2 * kPi * offset * (start + n) / rate;
                        iq[n * 2] = static_cast<int8_t>(std::lround(128 + 30 * std::cos(phase)));
                        iq[n * 2 + 1] = static_cast<int8_t>(std::lround(128 + 30 * std::sin(phase)));
                    }
                    observed_offset = pipeline.process(iq.data(), iq.size(), pcm.data(), pcm.size())
                        .estimated_carrier_offset_hz;
                }
                // Sync AM may still make its independent +/-350 Hz fine correction.
                check(std::abs(observed_offset) < 500,
                      "Pipeline must not acquire an unmodulated carrier outside +/-6500 Hz");
            }
        }
    }
}

void feed_tracker(AmCarrierTracker& tracker, int rate, double seconds,
                  double carrier, double amplitude, double neighbor = 0,
                  double modulation_scale = 1) {
    for (int n = 0; n < int(seconds * rate); ++n) {
        const double t = n / double(rate);
        const double envelope = amplitude * (1 + modulation_scale *
            (.4 * std::cos(2 * kPi * 173 * t) + .3 * std::cos(2 * kPi * 1091 * t)));
        const double phase = 2 * kPi * carrier * t;
        tracker.observe(envelope * std::cos(phase) + neighbor * std::cos(2 * kPi * 5333 * t),
                        envelope * std::sin(phase) + neighbor * std::sin(2 * kPi * 5333 * t));
    }
}

void test_search_boundaries() {
    for (int rate : {48000, 96000, 192000}) {
        for (double sign : {-1., 1.}) {
            for (double offset : {6300., 6495., 6505., 6700.}) {
                AmCarrierTracker tracker;
                tracker.configure(rate, 6500);
                // No sidebands: measure rejection of the carrier itself.
                feed_tracker(tracker, rate, .25, sign * offset, 10, 0, 0);
                if (offset < 6500) {
                    check(tracker.locked() && std::abs(tracker.frequency_hz() - sign * offset) < 5,
                          "Carrier search must include interpolated peaks just inside +/-6500 Hz");
                } else {
                    check(!tracker.locked() && tracker.frequency_hz() == 0,
                          "Carrier search must reject peaks beyond +/-6500 Hz");
                }
            }
        }
    }
}

void test_confirmation_hold_and_reset() {
    for (int rate : {48000, 96000, 192000}) {
        AmCarrierTracker tracker;
        tracker.configure(rate, 6500);
        feed_tracker(tracker, rate, .025, -3000, 10);
        check(!tracker.locked(), "One FFT must not establish a carrier lock");
        tracker.reset();
        feed_tracker(tracker, rate, .25, -3000, 10);
        check(tracker.locked() && std::abs(tracker.frequency_hz() + 3000) < 50,
              "Persistent AM carrier must be confirmed within 250 ms");
        feed_tracker(tracker, rate, 1, -3000, 10, 40);
        check(tracker.locked() && std::abs(tracker.frequency_hz() + 3000) < 50,
              "A louder neighbor must not steal an existing carrier lock");
        feed_tracker(tracker, rate, .5, 0, 0);
        check(!tracker.locked(), "Silence must expire lock confidence");
        const auto held = tracker.frequency_hz();
        feed_tracker(tracker, rate, .5, 0, 0);
        check(tracker.frequency_hz() == held, "Silence must not sweep the filter back to zero");
        feed_tracker(tracker, rate, .25, 2100, 1);
        check(tracker.locked() && std::abs(tracker.frequency_hz() - 2100) < 50,
              "A weak replacement with oscillator error must be acquired");
        tracker.reset();
        check(!tracker.locked() && tracker.frequency_hz() == 0,
              "Reset must clear stale frame and carrier state");
        feed_tracker(tracker, rate, .25, 6490, 10);
        check(tracker.locked() && std::abs(tracker.frequency_hz() - 6490) < 50,
              "Search must include valid carriers near the last partial FFT bin");
    }
}

void test_noise_and_short_bursts() {
    AmCarrierTracker tracker;
    tracker.configure(48000, 6500, {true, -3000, 2000});
    unsigned random = 12345;
    for (int n = 0; n < 48000 * 3; ++n) {
        random = random * 1664525 + 1013904223;
        const float i = (int(random >> 16) / 32768.0f - 1) * 20;
        random = random * 1664525 + 1013904223;
        const float q = (int(random >> 16) / 32768.0f - 1) * 20;
        tracker.observe(i, q);
        check(!tracker.locked(), "Broadband noise must not create a carrier lock");
    }
    tracker.reset();
    feed_tracker(tracker, 48000, .025, -3000, 40);
    feed_tracker(tracker, 48000, .25, 0, 0);
    check(!tracker.locked() && tracker.frequency_hz() == 0,
          "An isolated short carrier burst must not steer the filter");
}

void test_deep_low_frequency_modulation() {
    for (int rate : {48000, 96000, 192000}) {
        for (double tone : {80., 120., 173., 250., 1000.}) {
            AmCarrierTracker tracker;
            tracker.configure(rate, 6500);
            for (int n = 0; n < rate / 2; ++n) {
                const double t = n / double(rate);
                const double envelope = 20 * (1 + .98 * std::cos(2 * kPi * tone * t));
                tracker.observe(envelope * std::cos(2 * kPi * -3000 * t),
                                envelope * std::sin(2 * kPi * -3000 * t));
            }
            check(tracker.locked() && std::abs(tracker.frequency_hz() + 3000) < 50,
                  "Deep low-pitched AM must not be rejected as a broad noise peak");
        }
    }
}

void test_targeted_pipeline_audio_and_settings() {
    for (int rate : {240000, 480000, 960000}) {
        auto config = clean_voice();
        config.input_sample_rate_hz = rate;
        config.carrier_targets = {true, -3000, 2000};
        AmPipeline reference_pipeline(config), pipeline(config), neighbor_pipeline(config);
        const auto reference = receive(reference_pipeline, rate, -3000);
        receive(pipeline, rate, 2000, 0, 0, 2);
        const auto recovered = receive(pipeline, rate, -3000, 2, 2000);
        check(recovered.iq_clip == 0 && std::abs(recovered.offset + 3000) < 50 &&
                  recovered.tone > reference.tone * .8,
              "A stale weak line must not suppress a later wanted AM transmission");
        const auto adjacent = receive(neighbor_pipeline, rate, -3000, 40, 5333.33);
        check(adjacent.iq_clip == 0 && std::abs(adjacent.offset + 3000) < 50 &&
                  adjacent.tone > reference.tone * .8,
              "Target preference must recover voice despite a stronger adjacent carrier at startup");

        // A settings-only update must retain both tuning context and the lock.
        config.squelch_close_hang_ms = 600;
        check(pipeline.update_config(config), "Updating squelch with carrier targets must succeed");
        const auto updated = receive(pipeline, rate, -3000, 40, 5333.33, 10);
        check(std::abs(updated.offset + 3000) < 50 && updated.tone > 500,
              "Settings updates must preserve targeted carrier selection");

        config.carrier_targets = {true, 2000, 2000};
        pipeline.note_retune(config.carrier_targets);
        check(pipeline.update_config(config), "Config after retune must use the same new targets");
        const auto retuned = receive(pipeline, rate, 2000, 40, -3000, 10);
        check(std::abs(retuned.offset - 2000) < 50 && retuned.tone > 500,
              "Live retune must replace channel preference as well as the hardware frequency");

        config.carrier_targets = {true, -3000, 2000};
        AmPipeline stable(config);
        receive(stable, rate, 2000);
        for (bool speech_filter : {false, true}) {
            config.squelch_close_hang_ms += 10;
            config.am_speech_filter_enabled = speech_filter;
            check(stable.update_config(config), "Settings-only update must succeed");
            const auto held = receive(stable, rate, 2000, 12, -3000, 10);
            check(std::abs(held.offset - 2000) < 50,
                  "Settings must preserve a stable alternate lock even when cold acquisition would prefer nominal");
        }
    }
}
}

int main() {
    test_modulated_outside_neighbor();
    test_acquisition_anti_aliasing();
    test_am_acquisition_near_expanded_edges();
    test_am_pipeline_rejects_outside_carriers();
    test_search_boundaries();
    test_neighbor_does_not_pull_filter();
    test_station_changes_and_retune();
    test_squelch_update_preserves_carrier();
    test_confirmation_hold_and_reset();
    test_noise_and_short_bursts();
    test_deep_low_frequency_modulation();
    test_targeted_pipeline_audio_and_settings();
}
