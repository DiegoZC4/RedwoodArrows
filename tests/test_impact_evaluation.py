"""Focused offline evaluator tests; no real photos, network, or ONNX inference.

Run: .venv-impact/Scripts/python.exe -m unittest discover -s tests -p test_impact_evaluation.py -v
"""

import argparse
import itertools
import math
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import evaluate_impact as impact


class CoordinateTests(unittest.TestCase):
    def test_positions_include_edges_and_round_ties_even(self):
        self.assertEqual(impact.positions(600, 384), [0, 216])
        self.assertEqual(impact.positions(705, 384), [0, 160, 321])
        self.assertEqual(impact.positions(707, 384), [0, 162, 323])
        self.assertEqual(impact.positions(384, 384), [0])
        self.assertEqual(impact.positions(60, 384), [0])
        for length in range(60, 4801, 37):
            starts = impact.positions(length, 384)
            self.assertEqual(starts[0], 0)
            self.assertEqual(starts[-1], max(0, length - 384))
            self.assertTrue(all(0 < b - a <= 320 for a, b in zip(starts, starts[1:])))

    def test_independent_height_scaling_matches_java(self):
        original = (4000, 2999)
        review = impact.review_size(original)
        self.assertEqual(review, (600, 450))
        points = np.array([[599.5, 449.1, .8], [10.25, 20.1, .3]])
        mapped = impact.map_points(points, review, original)
        self.assertAlmostEqual(mapped[0, 1], 449.1 * 2999 / 450)
        self.assertNotAlmostEqual(mapped[0, 1], 449.1 * 4000 / 600)
        np.testing.assert_allclose(impact.map_points(mapped, original, review), points)
        np.testing.assert_array_equal(mapped[:, 2], points[:, 2])
        self.assertEqual(impact.map_points([], review, original).shape, (0, 3))

    def test_review_height_ties_and_invalid_aspects(self):
        self.assertEqual(impact.review_size((1200, 125)), (600, 62))
        self.assertEqual(impact.review_size((1200, 127)), (600, 64))
        for shape in ((0, 100), (600, 59), (600, 4801)):
            with self.assertRaises(ValueError):
                impact.review_size(shape)

    def test_float32_blend_window(self):
        window = impact.blend_window(384)
        self.assertEqual(window.dtype, np.float32)
        for y, x in ((0, 0), (4, 90), (193, 174), (383, 192)):
            a = np.float32(max(.1, .5 - .5 * math.cos(2 * math.pi * y / 383)))
            b = np.float32(max(.1, .5 - .5 * math.cos(2 * math.pi * x / 383)))
            self.assertEqual(window[y, x], np.float32(a * b))
        self.assertTrue(np.all(window > 0))

    def test_scale_heatmaps_use_half_pixel_centers(self):
        source = np.broadcast_to(np.arange(450, dtype=np.float32), (2, 120, 450)).copy()
        resized = impact.ensemble_heatmaps([source], (600, 160))
        for x in (8, 101, 403, 590):
            expected = (x + .5) * 450 / 600 - .5
            self.assertAlmostEqual(float(resized[0, 60, x]), expected, places=5)
        np.testing.assert_array_equal(resized[0], resized[1])


class EchoNet:
    """Returns RGB red/green probabilities to expose preprocessing and stitching."""

    def __init__(self):
        self.tiles = []

    def setInput(self, blob):
        self.blob = blob
        self.tiles.append(blob.copy())

    def forward(self):
        return self.blob[:, :2].copy()


class TilingTests(unittest.TestCase):
    def setUp(self):
        cv2.setNumThreads(2)
        self.bgr = np.random.default_rng(4729).integers(0, 256, (62, 600, 3), dtype=np.uint8)

    def test_rgb_input_padding_and_overlap(self):
        net = EchoNet()
        heat, tiles = impact.tiled_inference(self.bgr, net, 384)
        self.assertEqual(tiles, 2)
        self.assertEqual(heat.shape, (2, 62, 600))
        expected = self.bgr[:, :, [2, 1]].transpose(2, 0, 1).astype(np.float32) * np.float32(1 / 255)
        np.testing.assert_allclose(heat, expected, rtol=0, atol=1.2e-7)
        np.testing.assert_allclose(net.tiles[0][0, :, 100, 10], np.float32(127 / 255), rtol=0, atol=1e-7)
        np.testing.assert_allclose(net.tiles[1][0, :, 0, 0], self.bgr[0, 216, ::-1] / 255, rtol=0, atol=1e-7)

    def test_flip_is_restored_in_unpadded_image_coordinates(self):
        normal, _ = impact.tiled_inference(self.bgr, EchoNet(), 384)
        flipped, _ = impact.tiled_inference(self.bgr, EchoNet(), 384, flipped=True)
        np.testing.assert_allclose(flipped, normal, rtol=0, atol=1.2e-7)
        net = EchoNet()
        impact.tiled_inference(self.bgr, net, 384, width=300, flipped=True)
        small = cv2.resize(self.bgr, (300, 31), interpolation=cv2.INTER_AREA)
        np.testing.assert_allclose(net.tiles[0][0, :, 0, 0], small[0, 299, ::-1] / 255, rtol=0, atol=1e-7)
        np.testing.assert_allclose(net.tiles[0][0, :, 0, 300], np.float32(127 / 255), rtol=0, atol=1e-7)

    def test_malformed_network_outputs_fail(self):
        for bad in (np.zeros((1, 1, 384, 384), np.float32),
                    np.full((1, 2, 384, 384), np.nan, np.float32),
                    np.full((1, 2, 384, 384), 1.01, np.float32)):
            net = EchoNet()
            with patch.object(net, "forward", return_value=bad), self.assertRaises(ValueError):
                impact.tiled_inference(self.bgr, net, 384)


class PeakTests(unittest.TestCase):
    def test_log_quadratic_subpixel_peak(self):
        yy, xx = np.mgrid[:31, :31]
        heat = np.exp(-((xx - 15.25) ** 2 + (yy - 14.65) ** 2) / 8).astype(np.float32)
        hits = impact.peaks(heat, .2)
        self.assertEqual(len(hits), 1)
        np.testing.assert_allclose(hits[0, :2], [15.25, 14.65], atol=2e-7)
        self.assertEqual(hits[0, 2], float(heat[15, 15]))

    def test_peak_threshold_is_double_precision(self):
        heat = np.zeros((9, 9), np.float32)
        heat[4, 4] = np.float32(.16)
        self.assertLess(float(heat[4, 4]), .16)
        self.assertEqual(len(impact.peaks(heat, .16)), 0)
        self.assertEqual(len(impact.peaks(heat, float(heat[4, 4]))), 1)

    def test_stable_row_major_ties_and_64_limit(self):
        heat = np.zeros((12, 12), np.float32)
        heat[1, 9] = heat[9, 1] = .75
        np.testing.assert_array_equal(impact.peaks(heat, .5)[:, :2], [[9, 1], [1, 9]])
        self.assertEqual(len(impact.peaks(np.ones((100, 100), np.float32), .5)), 64)

    def test_border_disables_both_axis_offsets(self):
        heat = np.zeros((9, 9), np.float32)
        heat[0, 3:6] = [.3, .8, .5]
        np.testing.assert_array_equal(impact.peaks(heat, .7)[0, :2], [4, 0])

    def test_flat_offset_and_empty_heatmap(self):
        self.assertEqual(impact.offset(.5, .5, .5), 0)
        self.assertEqual(impact.offset(0, 0, 0), 0)
        self.assertEqual(impact.peaks(np.zeros((8, 8), np.float32), .2).shape, (0, 3))
        self.assertLessEqual(abs(impact.offset(.9, 1, .99)), .5)

    def test_peak_radius_distinct_from_commit_radius(self):
        heat = np.zeros((25, 25), np.float32)
        heat[10, 10] = .9
        heat[10, 14] = .8
        raw = impact.peaks(heat, .2)
        self.assertEqual(len(raw), 2)
        self.assertEqual(len(impact.commit_suppression(raw, (600, 600), 7.2)[0]), 1)
        self.assertEqual(len(impact.commit_suppression(raw, (600, 600), 3)[0]), 2)

    def test_commit_is_strict_and_does_not_resort(self):
        points = [[0, 0, .2], [3, 0, .9], [5, 0, .8], [600, 0, .9]]
        applied, kept = impact.commit_suppression(points, (600, 600), 3)
        self.assertEqual(kept, [0, 1])
        np.testing.assert_array_equal(applied, np.asarray(points)[:2])

    def test_commit_measures_original_pixels_with_rounded_height(self):
        points = [[10, 10, .9], [10, 17.2, .8]]
        _, kept = impact.commit_suppression(points, (4000, 2999), 7.2)
        self.assertEqual(kept, [0])


class MatchingTests(unittest.TestCase):
    def test_augmenting_path_beats_greedy_nearest_pair(self):
        matches = impact.maximum_matching([[1, 0], [-2, 0]], [[0, 0], [6, 0]])
        self.assertEqual({(m["prediction"], m["truth"]) for m in matches}, {(0, 1), (1, 0)})

    def test_minimum_distance_among_maximum_matchings(self):
        matches = impact.maximum_matching([[0, 0], [4, 0]], [[1, 0], [3, 0]])
        self.assertEqual(sum(m["distance_px"] for m in matches), 2)

    def test_one_to_one_and_inclusive_boundary(self):
        self.assertEqual(len(impact.maximum_matching([[0, 0], [0, 0]], [[0, 0]])), 1)
        self.assertEqual(len(impact.maximum_matching([[0, 0]], [[6, 0]])), 1)
        self.assertEqual(len(impact.maximum_matching([[0, 0]], [[6.000001, 0]])), 0)
        self.assertEqual(impact.maximum_matching([], [[1, 1]]), [])

    def test_matches_exhaustive_small_graphs(self):
        rng = np.random.default_rng(4729)
        for _ in range(30):
            pred = rng.uniform(0, 15, (4, 2))
            truth = rng.uniform(0, 15, (4, 2))
            distances = np.linalg.norm(pred[:, None] - truth[None, :], axis=2)
            best = (0, 0.)
            for assignment in itertools.product(range(-1, 4), repeat=4):
                chosen = [(i, j) for i, j in enumerate(assignment) if j >= 0]
                if len({j for _, j in chosen}) != len(chosen) or any(distances[i, j] > 6 for i, j in chosen):
                    continue
                objective = (-len(chosen), sum(distances[i, j] for i, j in chosen))
                best = min(best, objective)
            actual = impact.maximum_matching(pred, truth)
            self.assertEqual(len(actual), -best[0])
            self.assertAlmostEqual(sum(m["distance_px"] for m in actual), best[1], places=10)

    def test_clear_match_precedes_uncertain_ignores(self):
        labels = {"clear": [[10, 10]], "uncertain": [[10, 10]], "complete": True}
        score = impact.score_photo([[10, 10, .9], [11, 10, .8], [50, 50, .7]], labels)
        self.assertEqual((score["tp"], score["fp"], score["fn"], score["ignored"]), (1, 1, 0, 1))
        self.assertEqual(score["ignored_indices"], [1])
        self.assertIsNone(score["exact_photo"])

    def test_uncertainty_cannot_reward_tp_or_create_fn(self):
        labels = {"clear": [], "uncertain": [[10, 10]], "complete": True}
        for predictions in ([], [[10, 10, .9]]):
            score = impact.score_photo(predictions, labels)
            self.assertEqual((score["tp"], score["fp"], score["fn"]), (0, 0, 0))
            self.assertIsNone(score["recall"])
            self.assertIsNone(score["exact_photo"])

    def test_empty_missing_and_incomplete_are_not_interchangeable(self):
        self.assertIsNone(impact.score_photo([], None))
        self.assertIsNone(impact.score_photo([], {"clear": [], "uncertain": [], "complete": False}))
        score = impact.score_photo([], {"clear": [], "uncertain": [], "complete": True})
        self.assertTrue(score["exact_photo"])
        self.assertIsNone(score["f1"])
        self.assertIsNone(score["mean_error_px"])
        summary = impact.aggregate_scores([{"metrics": score}, {"metrics": None}], "metrics")
        self.assertEqual(summary["exact_eligible_photos"], 1)
        self.assertEqual(summary["unscored_photos"], 1)


class EndpointTests(unittest.TestCase):
    def test_one_sided_long_shaft_passes(self):
        shaft = np.zeros((101, 101), np.float32)
        shaft[49:52, 54:91] = .9
        profile = impact.endpoint_profile(shaft, 50, 50)
        self.assertEqual(profile["best_angle_degrees"], 0)
        self.assertGreater(profile["forward"], .8)
        self.assertEqual(profile["reverse"], 0)
        self.assertTrue(profile["endpoint_consistent"])

    def test_bilateral_middle_of_shaft_fails(self):
        shaft = np.zeros((101, 101), np.float32)
        shaft[49:52, 10:91] = .9
        self.assertFalse(impact.endpoint_profile(shaft, 50, 50)["endpoint_consistent"])

    def test_weak_or_short_support_fails(self):
        for stop, value in ((91, .4), (63, .9)):
            shaft = np.zeros((101, 101), np.float32)
            shaft[49:52, 54:stop] = value
            self.assertFalse(impact.endpoint_profile(shaft, 50, 50)["endpoint_consistent"])

    def test_missing_reverse_pixels_cannot_fake_an_endpoint(self):
        shaft = np.zeros((101, 101), np.float32)
        shaft[49:52, 0:46] = .9
        profile = impact.endpoint_profile(shaft, 5, 50)
        self.assertLess(profile["reverse_coverage"], .9)
        self.assertFalse(profile["endpoint_consistent"])

    def test_reflection_and_vertical_rotation(self):
        shaft = np.zeros((101, 101), np.float32)
        shaft[49:52, 54:91] = .9
        for transformed, angle in ((shaft[:, ::-1].copy(), 180), (shaft.T.copy(), 90)):
            profile = impact.endpoint_profile(transformed, 50, 50)
            self.assertEqual(profile["best_angle_degrees"], angle)
            self.assertTrue(profile["endpoint_consistent"])

    def test_high_confidence_preserved_without_endpoint_evidence(self):
        points = [[20, 20, .08], [40, 40, .16], [60, 60, .3]]
        kept, diagnostics = impact.endpoint_filter(points, np.zeros((101, 101), np.float32), .16)
        np.testing.assert_array_equal(kept, np.array(points)[1:])
        self.assertEqual([p["accepted"] for p in diagnostics], [False, True, True])
        self.assertEqual(impact.endpoint_filter([], np.zeros((10, 10), np.float32), .16)[0].shape, (0, 3))


class InputGuardTests(unittest.TestCase):
    def setUp(self):
        (ROOT / "build").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "build", prefix="impact-tests-")
        self.directory = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_agent_label_schema_does_not_promote_uncertain_points(self):
        path = self.directory / "labels.json"
        impact.write_json(path, {"coordinate_width": 600, "photos": [
            {"id": "synthetic", "points": [{"x": 20, "y": 30, "confidence": "clear"},
                                             {"x": 40, "y": 50, "confidence": "uncertain"}]}]})
        labels = impact.load_labels(path)["synthetic"]
        self.assertEqual(labels["clear"], [[20, 30]])
        self.assertEqual(labels["uncertain"], [[40, 50]])

    def test_outputs_cannot_escape_private_or_build(self):
        with self.assertRaises(ValueError):
            impact.private_output(ROOT / "tools" / "personal-output.json")
        with self.assertRaises(ValueError):
            impact.private_output(ROOT / "private" / ".." / "report.json")

    def test_holdout_changes_require_lock_even_without_labels(self):
        fake_cache = argparse.Namespace(card={"operating_threshold": .16})
        for options in (["--split", "holdout", "--variants", "hflip", "--cache-only"],
                        ["--split", "holdout", "--thresholds", ".12"]):
            args = impact.parser().parse_args(options)
            with self.assertRaises(ValueError):
                impact.candidates_for(args, fake_cache, "manifest", None)
        for radius in ("7.2", "3"):
            args = impact.parser().parse_args(["--split", "holdout", "--commit-radius", radius])
            candidates, lock = impact.candidates_for(args, fake_cache, "manifest", None)
            self.assertEqual(candidates, [impact.Candidate("baseline", .16, float(radius))])
            self.assertIsNone(lock)

    def test_threshold_grid_is_bounded(self):
        with patch("sys.stderr"), self.assertRaises(SystemExit):
            impact.parser().parse_args(["--thresholds", ".11"])

    def test_heatmap_cache_roundtrip_and_input_invalidation(self):
        cache = impact.HeatmapCache(ROOT / "assets/impact/impact-center.onnx",
                                    ROOT / "assets/impact/model-card.json", self.directory / "cache")
        cache.net = EchoNet()
        bgr = np.random.default_rng(4729).integers(0, 256, (62, 600, 3), dtype=np.uint8)
        first, cold = cache.get(bgr, "synthetic-photo", 600, False)
        with patch.object(cache.net, "forward", side_effect=AssertionError("cache must not infer")):
            second, warm = cache.get(bgr, "synthetic-photo", 600, False)
        np.testing.assert_array_equal(first, second)
        self.assertFalse(cold["cache_hit"])
        self.assertTrue(warm["cache_hit"])
        self.assertEqual(cold["recorded_compute_seconds"], warm["recorded_compute_seconds"])
        _, reflected = cache.get(bgr, "synthetic-photo", 600, True)
        self.assertNotEqual(cold["key"], reflected["key"])
        bgr[0, 0, 0] ^= 1
        _, changed = cache.get(bgr, "synthetic-photo", 600, False)
        self.assertNotEqual(changed["key"], cold["key"])
        self.assertFalse(changed["cache_hit"])
        cache.require_cache = True
        with patch.object(cache.net, "forward", side_effect=AssertionError("must not infer")), self.assertRaises(ValueError):
            cache.get(bgr, "uncached-photo", 600, False)

    def test_report_overlay_and_locked_holdout_with_fake_inference(self):
        class FakeCache:
            def __init__(self, *args):
                self.card = {"operating_threshold": .16}
                self.model_hash, self.card_hash = "test-model", "test-card"
                self.environment = {"test": True}
                self.model_load_seconds = 0.

            def get(self, bgr, photo_hash, width, flipped):
                heat = np.zeros((2, 62, 600), np.float32)
                heat[0, 30, 100], heat[0, 30, 104] = .9, .8
                return heat, {"key": photo_hash, "cache_hit": True, "recorded_compute_seconds": 1.,
                              "access_seconds": .001, "width": width, "flipped": flipped, "tiles": 2}

        for i in range(2):
            impact.Image.new("RGB", (600, 62), (i, 0, 0)).save(self.directory / f"photo{i}.png")
        manifest = self.directory / "manifest.json"
        reference = self.directory / "labels.json"
        impact.write_json(manifest, {"coordinate_width": 600, "photos": [
            {"id": "dev", "path": "photo0.png", "split": "development"},
            {"id": "test", "path": "photo1.png", "split": "holdout"}]})
        impact.write_json(reference, {"coordinate_width": 600, "photos": [
            {"id": name, "points": [[100, 30], [104, 30]]} for name in ("dev", "test")]})
        common = ["--manifest", str(manifest), "--labels", str(reference),
                  "--protocol", str(self.directory / "no-protocol.json")]
        args = impact.parser().parse_args(common + ["--output-dir", str(self.directory / "dev"),
                    "--commit-radius", "3", "--freeze", "baseline-t0.16-r3", "--overlays"])
        with patch.object(impact, "HeatmapCache", FakeCache), patch("builtins.print"):
            report_path = impact.run(args)
        report = impact.read_json(report_path)
        self.assertEqual(report["summary"]["baseline-t0.16-r3"]["applied"]["tp"], 2)
        overlay_path = next((self.directory / "dev/overlays").rglob("*.png"))
        with impact.Image.open(overlay_path) as rendered:
            self.assertEqual(rendered.size, (600, 118))
        holdout_args = impact.parser().parse_args(common + ["--split", "holdout", "--output-dir",
                    str(self.directory / "test"), "--locked", str(self.directory / "dev/locked.json")])
        with patch.object(impact, "HeatmapCache", FakeCache), patch("builtins.print"):
            holdout = impact.read_json(impact.run(holdout_args))
        summaries = holdout["summary"]
        self.assertEqual(summaries["baseline-t0.16-r7.2"]["raw_count"], 2)
        self.assertEqual(summaries["baseline-t0.16-r7.2"]["applied"]["fn"], 1)
        self.assertEqual(summaries["baseline-t0.16-r3"]["applied"]["fn"], 0)
        locked = impact.read_json(self.directory / "dev/locked.json")
        locked["harness_sha256"] = "changed-source"
        impact.write_json(self.directory / "dev/locked.json", locked)
        with self.assertRaises(ValueError):
            impact.candidates_for(holdout_args, FakeCache(), impact.file_hash(manifest), None)


if __name__ == "__main__":
    unittest.main()
