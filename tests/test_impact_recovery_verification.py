"""Verification gates using synthetic data only; no personal photos are read."""

from pathlib import Path
import sys
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import verify_impact_recovery as verify


class ComparisonTests(unittest.TestCase):
    def test_probability_tolerance(self):
        source = np.zeros((2, 5, 5), dtype=np.float32)
        self.assertTrue(verify.differences(source, source + 5e-5)["passed"])
        self.assertFalse(verify.differences(source, source + 2e-4)["passed"])

    def test_invalid_outputs_fail(self):
        source = np.zeros((2, 5, 5), dtype=np.float32)
        for actual in (np.zeros((2, 5)), np.full_like(source, np.nan), np.full_like(source, np.inf)):
            self.assertFalse(verify.differences(source, actual)["passed"])

    def test_peak_order_is_not_semantic(self):
        points = np.array([[1, 2, .9], [3, 5, .8]])
        self.assertTrue(verify.compare_peaks(points, points[::-1])["passed"])
        shifted = points.copy()
        shifted[0, 0] += .001
        self.assertTrue(verify.compare_peaks(points, shifted)["passed"])
        shifted[0, 0] += .02
        self.assertFalse(verify.compare_peaks(points, shifted)["passed"])

    def test_peak_matching_is_one_to_one(self):
        source = np.array([[1, 2, .9], [1.001, 2, .8]])
        actual = np.array([[1, 2, .9], [100, 50, .8]])
        self.assertFalse(verify.compare_peaks(source, actual)["passed"])

    def test_empty_and_missing_peaks(self):
        empty = np.empty((0, 3))
        point = np.array([[1, 2, .9]])
        self.assertTrue(verify.compare_peaks(empty, empty)["passed"])
        self.assertFalse(verify.compare_peaks(point, empty)["passed"])
        self.assertFalse(verify.compare_peaks(empty, point)["passed"])

    def test_synthetic_inputs_are_repeatable_and_in_contract(self):
        first, second = list(verify.synthetic_cases()), list(verify.synthetic_cases())
        self.assertEqual(len(first), 5)
        self.assertEqual(len({name for name, _ in first}), 5)
        for (name, value), (again, repeated) in zip(first, second):
            self.assertEqual(name, again)
            self.assertEqual(value.shape, (1, 3, 384, 384))
            self.assertEqual(value.dtype, np.float32)
            self.assertTrue(np.isfinite(value).all())
            self.assertTrue(np.all((0 <= value) & (value <= 1)))
            np.testing.assert_array_equal(value, repeated)


class DevelopmentOnlyTests(unittest.TestCase):
    def test_no_development_photos_fails_before_runtime_or_image_access(self):
        rows = [{"id": "test", "split": "holdout", "path": Path("must-not-be-opened.jpg")}]
        with patch.object(verify.evaluation, "load_manifest", return_value=(rows, {})), \
                patch.object(verify, "opencv_net") as native, patch.object(verify.Image, "open") as opened:
            with self.assertRaisesRegex(ValueError, "development-only"):
                verify.verify_photos(None, Path("unused.onnx"), Path("manifest.json"))
            native.assert_not_called()
            opened.assert_not_called()

    def test_only_explicit_development_images_are_read(self):
        rows = [{"id": name, "split": split, "path": Path(name + ".jpg")} for name, split in
                (("dev", "development"), ("test", "holdout"), ("validation", "validation"))]
        heat = np.zeros((2, 20, 20), dtype=np.float32)
        image = Image.new("RGB", (80, 60))
        with patch.object(verify.evaluation, "load_manifest", return_value=(rows, {})), \
                patch.object(verify, "opencv_net"), patch.object(verify, "OrtNetAdapter"), \
                patch.object(verify, "TorchNetAdapter"), \
                patch.object(verify.Image, "open", return_value=image) as opened, \
                patch.object(verify.evaluation, "tiled_inference", return_value=(heat, 1)), \
                patch.object(verify, "file_hash", return_value="synthetic-test-only") as hashed:
            result = verify.verify_photos(None, Path("unused.onnx"), Path("manifest.json"))
        opened.assert_called_once_with(Path("dev.jpg"))
        hashed.assert_called_once_with(Path("dev.jpg"))
        self.assertEqual([row["id"] for row in result], ["dev"])
        self.assertTrue(result[0]["passed"])


if __name__ == "__main__":
    unittest.main()
