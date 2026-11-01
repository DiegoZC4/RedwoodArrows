from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from assess_impact_release import assess
from evaluate_impact import score_photo


def fixture():
    truth = {"complete": True, "clear": [[10, 10], [20, 20]], "uncertain": []}
    def rows(points):
        return [{"id": key, "photo_sha256": key, "score": score_photo(points, truth)} for key in ("a", "b")]
    baseline = {"sha256": "old", "thresholds": {"0.16": {"photos": rows([[10, 10]])}}}
    candidate = {"sha256": "new", "thresholds": {"0.2": {"photos": rows([[10, 10], [20, 20]])}}}
    manifest = {"photos": [{"id": key, "image_sha256": key, "split": "holdout", "session": key} for key in ("a", "b")]}
    plan = {"baseline": {"model_sha256": "old", "threshold": .16}, "release_gate": {"minimum_f1_gain": .02, "no_session_recall_drop_over": .05}}
    return {"split": "holdout", "results": [baseline, candidate]}, manifest, plan, {"sha256": "new", "threshold": .2}


class ReleaseTest(unittest.TestCase):
    def test_improvement_passes_accuracy_but_not_installation(self):
        result = assess(*fixture())
        self.assertTrue(result["accuracy_passes"])
        self.assertFalse(result["installation_approved"])
        self.assertEqual(result["candidate"]["tp"], 4)

    def test_extra_false_dots_reject(self):
        args = fixture()
        args[0]["results"][1]["thresholds"]["0.2"]["photos"][0]["score"]["fp"] = 1
        self.assertFalse(assess(*args)["accuracy_passes"])

    def test_one_bad_session_rejects_even_if_overall_improves(self):
        args = fixture()
        score = args[0]["results"][1]["thresholds"]["0.2"]["photos"][0]["score"]
        score.update(tp=0, fn=2, exact_photo=False)
        self.assertFalse(assess(*args)["accuracy_gates"]["no_session_recall_regression"])

    def test_missing_photo_rejects(self):
        args = fixture()
        args[0]["results"][1]["thresholds"]["0.2"]["photos"].pop()
        with self.assertRaisesRegex(ValueError, "coverage"):
            assess(*args)

    def test_changed_frozen_threshold_rejects(self):
        args = fixture()
        args[3]["threshold"] = .3
        with self.assertRaisesRegex(ValueError, "thresholds"):
            assess(*args)

    def test_small_improvement_rejects(self):
        args = fixture()
        args[2]["release_gate"]["minimum_f1_gain"] = .8
        self.assertFalse(assess(*args)["accuracy_gates"]["meaningful_f1_gain"])

    def test_validation_cannot_be_release_test(self):
        args = fixture()
        args[0]["split"] = "validation"
        with self.assertRaisesRegex(ValueError, "held-out"):
            assess(*args)


if __name__ == "__main__":
    unittest.main()
