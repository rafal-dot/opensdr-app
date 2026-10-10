#include "am_carrier_tracker.h"

#include <cmath>
#include <complex>
#include <cstdlib>
#include <initializer_list>
#include <iostream>
#include <utility>

namespace {
constexpr double kPi = 3.14159265358979323846;

void check(bool condition, const char* message) {
    if (!condition) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}

struct Signal {
    double frequency;
    double amplitude;
    double tone = 0;
    double depth = 0;
    double phase = 0;
};

void feed(AmCarrierTracker& tracker, int rate, std::initializer_list<Signal> signals, double seconds = .5) {
    for (int n = 0; n < int(rate * seconds); ++n) {
        const double t = n / double(rate);
        std::complex<double> sample{0, 0};
        for (const auto& signal : signals) {
            const double amplitude = signal.amplitude *
                (1 + signal.depth * std::cos(2 * kPi * signal.tone * t + .37));
            sample += std::polar(amplitude, 2 * kPi * signal.frequency * t + signal.phase);
        }
        tracker.observe(sample.real(), sample.imag());
    }
}

void test_outside_am_is_not_a_candidate() {
    for (int rate : {48000, 96000, 192000}) {
        for (double sign : {-1., 1.}) {
            // Include partial FFT bins and a sideband barely inside the limit.
            for (const auto& pair : {std::pair{6700., 2200.}, {7500., 2200.},
                                     {9900., 3500.}, {7011.7, 521.4}}) {
                AmCarrierTracker tracker;
                tracker.configure(rate, 6500);
                const Signal outside{sign * pair.first, 30, pair.second, .95, .43};
                feed(tracker, rate, {outside});
                check(!tracker.locked() && tracker.frequency_hz() == 0,
                      "Neither sideband of an outside AM carrier may become a tuning candidate");
                feed(tracker, rate, {outside, {-3000, 3, 1000, .5, .61}});
                check(tracker.locked() && std::abs(tracker.frequency_hz() + 3000) < 5,
                      "The weak wanted carrier must survive outside-AM sideband rejection");
            }
        }
    }
}

void test_nearby_real_carrier_is_not_vetoed() {
    for (int rate : {48000, 96000, 192000}) {
        for (double sign : {-1., 1.}) {
            AmCarrierTracker tracker;
            tracker.configure(rate, 6500);
            feed(tracker, rate, {{sign * 6490, 3, 1000, .5, 1.1},
                                  {sign * 7500, 30, 2200, .8, .4}});
            check(tracker.locked() && std::abs(tracker.frequency_hz() - sign * 6490) < 5,
                  "A valid edge carrier must not be masked just because a strong neighbor is nearby");
            tracker.reset();
            // These symmetric lines have unrelated phases, not an AM pair.
            feed(tracker, rate, {{sign * 6490, 3, 0, 0, 1.1},
                                  {sign * 7500, 30, 0, 0, .4},
                                  {sign * 8510, 3, 0, 0, 2.1}});
            check(tracker.locked() && std::abs(tracker.frequency_hz() - sign * 6490) < 5,
                  "Power/frequency symmetry alone must not reject an independent real carrier");
        }
    }
}

void test_lock_is_revalidated_and_reset() {
    for (int rate : {48000, 96000, 192000}) {
        AmCarrierTracker tracker;
        tracker.configure(rate, 6500);
        feed(tracker, rate, {{5300, 12}});
        check(tracker.locked(), "Initial isolated line must acquire");
        feed(tracker, rate, {{7500, 30, 2200, .8}, {-3000, 3, 1000, .5}});
        check(tracker.locked() && std::abs(tracker.frequency_hz() + 3000) < 5,
              "The incumbent must be revalidated when outside-carrier context becomes visible");
        tracker.reset();
        feed(tracker, rate, {{5300, 3, 1000, .5}});
        check(tracker.locked() && std::abs(tracker.frequency_hz() - 5300) < 5,
              "Reset must not retain a sideband veto for a later real carrier");
    }
}

void expect_carrier(const AmCarrierTracker& tracker, double frequency, const char* message) {
    check(tracker.locked() && std::abs(tracker.frequency_hz() - frequency) < 5, message);
}

void test_channel_priority_and_neighbor_protection() {
    for (int rate : {48000, 96000, 192000}) {
        for (double sign : {-1., 1.}) {
            AmCarrierTracker tracker;
            tracker.configure(rate, 6500, {true, float(sign * -3000), float(sign * 2000)});
            feed(tracker, rate, {{sign * -3000, 3, 1000, .6}, {sign * 5333, 40}});
            expect_carrier(tracker, sign * -3000, "A stronger adjacent channel must not win initial selection");
            feed(tracker, rate, {{sign * -3000, 3, 1000, .6}, {sign * 5333, 80}});
            expect_carrier(tracker, sign * -3000, "An adjacent channel must not steal a valid channel lock");
        }
    }
}

void test_persistent_challenger_replaces_a_weak_line() {
    for (int rate : {48000, 96000, 192000}) {
        // Nominal and literal candidates both need an escape from a persistent spur.
        for (const auto& pair : {std::pair{2000., -3000.}, {-3000., 2000.}, {-3000., -2350.}}) {
            AmCarrierTracker tracker;
            tracker.configure(rate, 6500, {true, -3000, 2000});
            feed(tracker, rate, {{pair.first, 2}});
            expect_carrier(tracker, pair.first, "Isolated initial line must be acquired");
            feed(tracker, rate, {{pair.first, 2}, {pair.second, 30, 1000, .6}}, .12);
            expect_carrier(tracker, pair.first, "A brief challenger must not move an existing lock");
            feed(tracker, rate, {{pair.first, 2}, {pair.second, 30, 1000, .6}}, .20);
            expect_carrier(tracker, pair.second, "A persistent better carrier must replace a stale weak line");
            feed(tracker, rate, {{pair.first, 2}, {pair.second, 2, 1000, .6}});
            expect_carrier(tracker, pair.second, "Similar powers must not immediately reverse the switch");
        }
    }
}

void test_short_challenges_do_not_accumulate() {
    for (int rate : {48000, 96000, 192000}) {
        AmCarrierTracker tracker;
        tracker.configure(rate, 6500, {true, -3000, 2000});
        feed(tracker, rate, {{-3000, 3, 1000, .6}});
        for (int n = 0; n < 5; ++n) {
            feed(tracker, rate, {{-3000, 3, 1000, .6}, {2000, 30}}, .08);
            feed(tracker, rate, {{-3000, 3, 1000, .6}}, .12);
            expect_carrier(tracker, -3000, "Separate short challenges must never accumulate confirmation");
        }
        feed(tracker, rate, {{2000, 1, 1000, .5}}, .16);
        expect_carrier(tracker, 2000, "A disappeared carrier must allow fast acquisition of a weak replacement");
    }
}

void test_targeted_search_keeps_wide_fallback_and_retunes() {
    for (int rate : {48000, 96000, 192000}) {
        for (double offset : {-6490., 6490.}) {
            AmCarrierTracker tracker;
            tracker.configure(rate, 6500, {true, -3000, 2000});
            feed(tracker, rate, {{offset, 30, 1000, .6}});
            expect_carrier(tracker, offset, "Target preference must not narrow the +/-6500 Hz search");
            feed(tracker, rate, {{offset, 30}, {-3000, 3, 1000, .6}});
            expect_carrier(tracker, -3000, "A valid expected carrier must displace a stronger fallback neighbor");
            tracker.set_targets({true, float(offset), float(offset)});
            check(!tracker.locked(), "Retuning must forget the old confirmation state");
            feed(tracker, rate, {{offset, 3, 1000, .6}, {-3000, 30}});
            expect_carrier(tracker, offset, "Retuning must use the new target, not the old preference");
        }
    }
}

void test_fallback_switch_needs_longer_confirmation() {
    for (int rate : {48000, 96000, 192000}) {
        AmCarrierTracker tracker;
        tracker.configure(rate, 6500, {true, -3000, 2000});
        feed(tracker, rate, {{-6490, 2}});
        feed(tracker, rate, {{-6490, 2}, {6490, 30, 1000, .6}}, .20);
        expect_carrier(tracker, -6490, "Out-of-target challenges need more than the normal switch delay");
        feed(tracker, rate, {{-6490, 2}, {6490, 30, 1000, .6}}, .20);
        expect_carrier(tracker, 6490, "A sustained dominant fallback must eventually replace a weak line");
    }
}
}

int main() {
    test_outside_am_is_not_a_candidate();
    test_nearby_real_carrier_is_not_vetoed();
    test_lock_is_revalidated_and_reset();
    test_channel_priority_and_neighbor_protection();
    test_persistent_challenger_replaces_a_weak_line();
    test_short_challenges_do_not_accumulate();
    test_targeted_search_keeps_wide_fallback_and_retunes();
    test_fallback_switch_needs_longer_confirmation();
    std::cout << "AM carrier context tests passed\n";
}
