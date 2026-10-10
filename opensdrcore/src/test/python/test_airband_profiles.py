"""Check the actual bundled profiles used by the simplified airband client."""

import json
import unittest
from pathlib import Path


PROFILES = Path(__file__).resolve().parents[2] / "main" / "assets" / "am_profiles"


class AirbandProfilesTest(unittest.TestCase):
    def test_default_profile_enables_reversible_simple_am_only(self):
        for path in PROFILES.glob("*.json"):
            profile = json.loads(path.read_text())
            self.assertEqual(
                profile["id"] == "low_cpu_clean_voice",
                profile["pipeline"].get("simpleAmEnabled", False),
            )

    def test_bundled_profiles_are_valid_and_have_unique_ids(self):
        profiles = [json.loads(path.read_text()) for path in PROFILES.glob("*.json")]
        self.assertEqual(len(profiles), len({profile["id"] for profile in profiles}))
        self.assertEqual(
            ["low_cpu_clean_voice"],
            [profile["id"] for profile in profiles if profile.get("default")],
        )

    def test_airband_profiles_reset_all_legacy_dsp_overrides(self):
        required = {
            "preferredSampleRateHz", "amBandwidthHz", "frequencyCorrectionHz",
            "squelchOpenDebounceMs", "squelchCloseHangMs", "hardSquelchMuteEnabled",
            "audioBoostPercent", "voiceFocusEnabled", "postLevelerEnabled",
            "noiseBlankerEnabled", "denoiseMode", "demodMode", "notchMode",
            "sidebandMode", "advancedSyncEnabled", "qualitySquelchEnabled",
            "speechEnhancerMode",
        }
        preserved = {"ppmCorrection", "biasTeeEnabled", "gainMode", "tunerGain", "squelchMode", "squelchLevel"}
        for name in ("low_cpu_clean_voice", "low_cpu_wide_voice"):
            with self.subTest(profile=name):
                defaults = json.loads((PROFILES / (name + ".json")).read_text())["defaults"]
                self.assertTrue(required <= defaults.keys())
                self.assertFalse(preserved & defaults.keys())
                self.assertEqual(240000, defaults["preferredSampleRateHz"])
                self.assertEqual(1, defaults["demodMode"])
                self.assertEqual(0, defaults["sidebandMode"])
                for key in ("noiseBlankerEnabled", "denoiseMode", "notchMode",
                            "advancedSyncEnabled", "qualitySquelchEnabled", "speechEnhancerMode"):
                    self.assertFalse(defaults[key], key)


if __name__ == "__main__":
    unittest.main()
