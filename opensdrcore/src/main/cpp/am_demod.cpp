#include "am_demod.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kCarrierRecoveryAlpha = 0.0025f;
constexpr float kAdvancedSyncRecoveryAlpha = 0.0040f;
constexpr float kEnvelopeMagnitudeScale = 120.0f;
constexpr float kCarrierPresenceAttackAlpha = 0.030f;
// Carrier presence should be much more stable than the instantaneous envelope.
// A fast release here makes squelch and continuity logic twitch between words.
constexpr float kCarrierPresenceReleaseAlpha = 0.0015f;
constexpr float kAdvancedSyncLockAttackAlpha = 0.030f;
constexpr float kAdvancedSyncLockReleaseAlpha = 0.006f;
}

float demodulate_am_envelope(
    float magnitude,
    float* carrier_level,
    float* carrier_presence_level) {
    const float attack = magnitude > *carrier_level ? 0.0020f : 0.0002f;
    *carrier_level += (magnitude - *carrier_level) * attack;
    *carrier_level = std::max(*carrier_level, 1.0f);
    const float carrier_presence_alpha = magnitude > *carrier_presence_level
        ? kCarrierPresenceAttackAlpha
        : kCarrierPresenceReleaseAlpha;
    *carrier_presence_level += (magnitude - *carrier_presence_level) * carrier_presence_alpha;
    *carrier_presence_level = std::max(*carrier_presence_level, 1.0f);
    return (magnitude - *carrier_level) * kEnvelopeMagnitudeScale;
}

float demodulate_am_synchronous(
    bool advanced_sync_enabled,
    float filtered_i,
    float filtered_q,
    float magnitude,
    float* carrier_level,
    float* carrier_presence_level,
    float* carrier_recovery_i_lp,
    float* carrier_recovery_q_lp,
    float* sync_lock_quality,
    float* sync_phase_error) {
    const float recovery_alpha = advanced_sync_enabled ? kAdvancedSyncRecoveryAlpha : kCarrierRecoveryAlpha;
    *carrier_recovery_i_lp += (filtered_i - *carrier_recovery_i_lp) * recovery_alpha;
    *carrier_recovery_q_lp += (filtered_q - *carrier_recovery_q_lp) * recovery_alpha;
    const float carrier_magnitude = std::max(
        std::sqrt(*carrier_recovery_i_lp * *carrier_recovery_i_lp + *carrier_recovery_q_lp * *carrier_recovery_q_lp),
        1e-6f);
    if (carrier_magnitude < 4.0f) {
        *sync_lock_quality += (0.0f - *sync_lock_quality) * kAdvancedSyncLockReleaseAlpha;
        return demodulate_am_envelope(magnitude, carrier_level, carrier_presence_level);
    }
    const float carrier_i = *carrier_recovery_i_lp / carrier_magnitude;
    const float carrier_q = *carrier_recovery_q_lp / carrier_magnitude;
    const float projected = filtered_i * carrier_i + filtered_q * carrier_q;
    const float quadrature = -filtered_i * carrier_q + filtered_q * carrier_i;
    const float normalized_phase_error = std::clamp(std::fabs(quadrature) / std::max(magnitude, 1.0f), 0.0f, 1.0f);
    *sync_phase_error += (normalized_phase_error - *sync_phase_error) * (advanced_sync_enabled ? 0.030f : 0.015f);
    const float target_lock_quality = std::clamp(
        (carrier_magnitude - 6.0f) / 26.0f,
        0.0f,
        1.0f) * std::clamp(1.0f - *sync_phase_error, 0.0f, 1.0f);
    *sync_lock_quality += (target_lock_quality - *sync_lock_quality) * (
        target_lock_quality > *sync_lock_quality
            ? kAdvancedSyncLockAttackAlpha
            : kAdvancedSyncLockReleaseAlpha);
    const float carrier_attack = magnitude > *carrier_level ? 0.0030f : 0.0003f;
    *carrier_level += (magnitude - *carrier_level) * carrier_attack;
    *carrier_level = std::max(*carrier_level, 1.0f);
    const float carrier_presence_alpha = magnitude > *carrier_presence_level
        ? kCarrierPresenceAttackAlpha
        : kCarrierPresenceReleaseAlpha;
    *carrier_presence_level += (magnitude - *carrier_presence_level) * carrier_presence_alpha;
    *carrier_presence_level = std::max(*carrier_presence_level, 1.0f);
    const float sync_audio = (projected - *carrier_level) * kEnvelopeMagnitudeScale;
    if (!advanced_sync_enabled) {
        return sync_audio;
    }
    const float envelope_audio = (magnitude - *carrier_level) * kEnvelopeMagnitudeScale;
    const float sync_mix = std::clamp(0.25f + *sync_lock_quality * 0.75f, 0.25f, 1.0f);
    return sync_audio * sync_mix + envelope_audio * (1.0f - sync_mix);
}
