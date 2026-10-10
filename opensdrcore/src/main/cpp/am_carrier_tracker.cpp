#include "am_carrier_tracker.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kMatchHz = 180.0f;
constexpr int kConfirmFrames = 2;
constexpr int kReleaseFrames = 3;
constexpr float kMaxSidebandSeparationHz = 3500.0f;
constexpr float kTargetToleranceHz = 1500.0f;
constexpr float kPrimaryWeight = 4.0f;
constexpr float kSwitchPowerRatio = 4.0f;
constexpr float kMinimumSwitchPowerRatio = 2.0f;
constexpr float kFallbackSwitchPowerRatio = 16.0f;
constexpr int kSwitchFrames = 5;
constexpr int kFallbackSwitchFrames = 8;
}

void AmCarrierTracker::configure(int sample_rate_hz, float search_half_width_hz, AmCarrierTargets targets) {
    // About 21 ms of IQ, at most 25 FFTs/s, with <=47 Hz bins at supported rates.
    // Use the actual stage rate: subsampling without another anti-alias filter
    // would fold distant stations into the carrier search band.
    std::size_t size = 64;
    while (size < 4096 && sample_rate_hz / float(size) > 47.0f) size *= 2;
    bin_hz_ = sample_rate_hz / float(size);
    search_half_width_hz_ = std::min(search_half_width_hz, sample_rate_hz * .45f);
    search_bins_ = std::min(int(std::ceil(search_half_width_hz_ / bin_hz_)), int(size / 2) - 5);
    // Inspect both sidebands of an outside carrier without allowing that carrier
    // to become a tuning candidate. This uses the same FFT, not a wider search.
    context_half_width_hz_ = std::min(search_half_width_hz_ + 2 * kMaxSidebandSeparationHz,
                                    sample_rate_hz * .45f);
    context_bins_ = std::min(int(std::ceil(context_half_width_hz_ / bin_hz_)), int(size / 2) - 5);
    frame_period_ = std::max(size, std::size_t(std::lround(sample_rate_hz * .040)));
    spectrum_.resize(size);
    powers_.resize(size);
    window_.resize(size);
    reversed_.resize(size);
    roots_.resize(size / 2);
    noise_scratch_.resize(2 * search_bins_ + 1);
    peaks_.resize(2 * context_bins_ + 1);
    window_center_rotations_.resize(peaks_.size());
    for (int bin = -context_bins_; bin <= context_bins_; ++bin) {
        // Refer every FFT bin's phase to the same window midpoint, including
        // fractional-bin tones, before testing the AM sideband phase relation.
        window_center_rotations_[bin + context_bins_] =
            std::polar(1.0f, kPi * bin * float(size - 1) / float(size));
    }
    float window_sum = 0;
    for (std::size_t n = 0; n < size; ++n) {
        window_[n] = .5f - .5f * std::cos(2 * kPi * n / (size - 1));
        window_sum += window_[n];
        std::size_t reversed = 0;
        for (std::size_t bits = size, value = n; bits > 1; bits >>= 1, value >>= 1) {
            reversed = (reversed << 1) | (value & 1);
        }
        reversed_[n] = reversed;
        if (n < size / 2) roots_[n] = std::polar(1.0f, -2 * kPi * n / size);
    }
    minimum_line_power_ = .25f * window_sum * window_sum;
    set_targets(targets);
}

void AmCarrierTracker::set_targets(AmCarrierTargets targets) {
    targets_ = std::isfinite(targets.primary_hz) && std::isfinite(targets.alternate_hz)
        ? targets : AmCarrierTargets{};
    reset();
}

void AmCarrierTracker::reset() {
    frame_position_ = 0;
    locked_ = false;
    frequency_hz_ = 0;
    pending_frequency_hz_ = 0;
    pending_frames_ = 0;
    missing_frames_ = 0;
}

void AmCarrierTracker::observe(float i, float q) {
    if (spectrum_.empty()) return;
    if (frame_position_ < spectrum_.size()) {
        spectrum_[reversed_[frame_position_]] = std::complex<float>(i, q) * window_[frame_position_];
    }
    if (++frame_position_ == spectrum_.size()) analyze();
    if (frame_position_ == frame_period_) frame_position_ = 0;
}

void AmCarrierTracker::transform() {
    const auto size = spectrum_.size();
    for (std::size_t width = 2; width <= size; width *= 2) {
        const auto half = width / 2;
        const auto stride = size / width;
        for (std::size_t start = 0; start < size; start += width) {
            for (std::size_t n = 0; n < half; ++n) {
                const auto even = spectrum_[start + n];
                const auto odd = spectrum_[start + n + half] * roots_[n * stride];
                spectrum_[start + n] = even + odd;
                spectrum_[start + n + half] = even - odd;
            }
        }
    }
    for (std::size_t n = 0; n < size; ++n) powers_[n] = std::norm(spectrum_[n]);
}

float AmCarrierTracker::power_at(int bin) const {
    const int size = static_cast<int>(powers_.size());
    return powers_[(bin + size) % size];
}

AmCarrierTracker::Peak AmCarrierTracker::peak_at(int bin, float minimum_power) const {
    const float left = power_at(bin - 1), middle = power_at(bin), right = power_at(bin + 1);
    if (middle < minimum_power || middle <= left || middle < right) return {};
    const float line_power = left + middle + right;
    float surrounding_power = line_power;
    for (int n = 2; n <= 4; ++n) surrounding_power += power_at(bin - n) + power_at(bin + n);
    // Narrowband noise can be coherent over adjacent IQ samples, but normally
    // lacks a persistent, concentrated spectral line over this longer window.
    // Allow close AM sidebands: at 100% modulation the carrier contains only
    // 2/3 of the power, even for a perfectly clean low-frequency audio tone.
    if (line_power < surrounding_power * .60f) return {};
    const float log_left = std::log(std::max(left, 1e-20f));
    const float log_middle = std::log(std::max(middle, 1e-20f));
    const float log_right = std::log(std::max(right, 1e-20f));
    const float curvature = log_left - 2 * log_middle + log_right;
    const float fraction = curvature < -1e-6f
        ? std::clamp(.5f * (log_left - log_right) / curvature, -.5f, .5f) : 0;
    const float frequency = (bin + fraction) * bin_hz_;
    if (std::fabs(frequency) > context_half_width_hz_) return {};
    const int size = static_cast<int>(spectrum_.size());
    const auto phase = spectrum_[(bin + size) % size] *
        window_center_rotations_[bin + context_bins_] / std::sqrt(middle);
    return {frequency, line_power, phase};
}

bool AmCarrierTracker::is_modulation_sideband(const Peak& candidate) const {
    for (const auto& carrier : peaks_) {
        if (carrier.power < candidate.power * 3.0f) continue;
        const float separation = std::fabs(candidate.frequency_hz - carrier.frequency_hz);
        if (separation < bin_hz_ * 3 || separation > kMaxSidebandSeparationHz + bin_hz_ * .25f) continue;
        const float mirror_hz = 2 * carrier.frequency_hz - candidate.frequency_hz;
        const int mirror_bin = static_cast<int>(std::lround(mirror_hz / bin_hz_));
        for (int bin = std::max(-context_bins_, mirror_bin - 1);
             bin <= std::min(context_bins_, mirror_bin + 1); ++bin) {
            const auto& mirror = peaks_[bin + context_bins_];
            if (std::fabs(mirror.frequency_hz - mirror_hz) > bin_hz_ * .25f ||
                mirror.power < candidate.power * .4f || mirror.power > candidate.power * 2.5f ||
                carrier.power < mirror.power * 3.0f) continue;
            // Real AM has symmetric sidebands whose phases sum to twice the
            // carrier phase. Proximity to a stronger station alone is NOT a veto.
            const auto phase_relation = candidate.phase * mirror.phase *
                std::conj(carrier.phase * carrier.phase);
            if (phase_relation.real() > .90f) return true;
        }
    }
    return false;
}

void AmCarrierTracker::analyze() {
    transform();
    for (int bin = -search_bins_; bin <= search_bins_; ++bin) {
        noise_scratch_[bin + search_bins_] = power_at(bin);
    }
    auto median = noise_scratch_.begin() + noise_scratch_.size() / 2;
    std::nth_element(noise_scratch_.begin(), median, noise_scratch_.end());
    const float minimum_power = std::max(minimum_line_power_, *median * 12.0f);
    for (int bin = -context_bins_; bin <= context_bins_; ++bin) {
        peaks_[bin + context_bins_] = peak_at(bin, minimum_power);
    }
    Peak candidate, incumbent;
    for (int bin = -search_bins_; bin <= search_bins_; ++bin) {
        const Peak& peak = peaks_[bin + context_bins_];
        if (peak.power <= 0 || std::fabs(peak.frequency_hz) > search_half_width_hz_ ||
            is_modulation_sideband(peak)) continue;
        if (better_candidate(peak, candidate)) candidate = peak;
        if (locked_ && std::fabs(peak.frequency_hz - frequency_hz_) <= kMatchHz &&
            peak.power > incumbent.power) incumbent = peak;
    }
    select(candidate, incumbent);
}

float AmCarrierTracker::target_weight(float frequency_hz) const {
    if (!targets_.enabled) return 0;
    if (std::fabs(frequency_hz - targets_.primary_hz) <= kTargetToleranceHz) return kPrimaryWeight;
    if (std::fabs(frequency_hz - targets_.alternate_hz) <= kTargetToleranceHz) return 1;
    return 0;
}

bool AmCarrierTracker::better_candidate(const Peak& candidate, const Peak& current) const {
    if (current.power <= 0) return true;
    const float candidate_weight = target_weight(candidate.frequency_hz);
    const float current_weight = target_weight(current.frequency_hz);
    // A valid channel candidate beats an unrelated neighbor. The full search
    // remains a fallback for oscillator errors, not a competing adjacent channel.
    if ((candidate_weight > 0) != (current_weight > 0)) return candidate_weight > 0;
    return candidate.power * std::max(1.0f, candidate_weight) >
        current.power * std::max(1.0f, current_weight);
}

bool AmCarrierTracker::can_replace(const Peak& candidate, const Peak& incumbent) const {
    if (!targets_.enabled || candidate.power <= 0 ||
        std::fabs(candidate.frequency_hz - incumbent.frequency_hz) <= kMatchHz) return false;
    const float candidate_weight = target_weight(candidate.frequency_hz);
    const float incumbent_weight = target_weight(incumbent.frequency_hz);
    if ((candidate_weight > 0) != (incumbent_weight > 0)) return candidate_weight > 0;
    const float ratio = candidate_weight > 0 ? kSwitchPowerRatio : kFallbackSwitchPowerRatio;
    return candidate.power >= incumbent.power * kMinimumSwitchPowerRatio &&
        candidate.power * std::max(1.0f, candidate_weight) >=
        incumbent.power * std::max(1.0f, incumbent_weight) * ratio;
}

void AmCarrierTracker::select(Peak candidate, Peak incumbent) {
    int confirmation_frames = kConfirmFrames;
    if (incumbent.power > 0) {
        // Smooth ONE line, never the average of two carriers. A challenger must
        // remain preferable for ~200 ms (~320 ms outside the expected windows).
        frequency_hz_ += (incumbent.frequency_hz - frequency_hz_) * .25f;
        missing_frames_ = 0;
        if (!can_replace(candidate, incumbent)) {
            pending_frames_ = 0;
            return;
        }
        confirmation_frames = target_weight(candidate.frequency_hz) > 0
            ? kSwitchFrames : kFallbackSwitchFrames;
    } else {
        if (++missing_frames_ >= kReleaseFrames) locked_ = false;
    }
    if (candidate.power <= 0) {
        pending_frames_ = 0;
        // Hold the last tuning through noise instead of repeatedly sweeping it.
        return;
    }
    if (pending_frames_ == 0 || std::fabs(candidate.frequency_hz - pending_frequency_hz_) > kMatchHz) {
        pending_frequency_hz_ = candidate.frequency_hz;
        pending_frames_ = 1;
    } else {
        pending_frequency_hz_ += (candidate.frequency_hz - pending_frequency_hz_) * .5f;
        ++pending_frames_;
    }
    if (pending_frames_ >= confirmation_frames) {
        frequency_hz_ = pending_frequency_hz_;
        locked_ = true;
        missing_frames_ = 0;
        pending_frames_ = 0;
    }
}
