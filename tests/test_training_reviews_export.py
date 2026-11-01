"""Synthetic-only tests; never read private photos or invoke model training.

Run: .venv-impact/Scripts/python.exe -m unittest discover -s tests -p test_training_reviews_export.py -v
"""

import copy
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import struct
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from PIL import Image, ImageOps


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import evaluate_impact as impact
import export_training_reviews as exporter


def fingerprint(journal):
    """Independent fixture encoder for the Android wire contract."""
    points = sorted(journal["latest"]["points"], key=lambda point: point["arrow"])
    payload = b"redwood-dot-review-v1\n" + journal["image_sha256"].encode("ascii") + b"\n"
    payload += struct.pack(">iii", journal["width"], journal["height"], len(points))
    for point in points:
        payload += struct.pack(">idd", point["arrow"],
                               float(point["x"]) or 0.0, float(point["y"]) or 0.0)
    return hashlib.sha256(payload).hexdigest()


def jpeg(size=(1200, 803), color=(20, 70, 130), orientation=None):
    output = io.BytesIO()
    with Image.new("RGB", size, color) as image:
        exif = Image.Exif()
        if orientation is not None:
            exif[274] = orientation
        image.save(output, "JPEG", exif=exif)
    return output.getvalue()


def write_json(path, document):
    path.write_text(json.dumps(document, allow_nan=False), encoding="utf-8")


def point(arrow=1, x=300.25, y=200.5, source="impact-model:fixture"):
    return {"arrow": arrow, "x": x, "y": y, "source": source, "detector_score": .82}


def observations(points):
    return [{"arrow": p["arrow"], "kind": "impact", "a": [p["x"], p["y"]],
             "source": p["source"], **({"detector_score": p["detector_score"]}
                                      if "detector_score" in p else {})} for p in points]


class TrainingReviewsExportTest(unittest.TestCase):
    def setUp(self):
        (ROOT / "build").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="training-review-tests-", dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.files = self.directory / "files"
        (self.files / "groups").mkdir(parents=True)
        self.counter = 0

    def fixture(self, group_id="group1", view_id="view1", points=None, data=None, kind="detector-output"):
        points = copy.deepcopy([point()] if points is None else points)
        data = jpeg() if data is None else data
        with Image.open(io.BytesIO(data)) as image:
            with ImageOps.exif_transpose(image) as upright:
                width, height = upright.size
        group_dir = self.files / "groups" / group_id
        review_dir = self.files / "training-reviews" / group_id
        group_dir.mkdir(parents=True)
        review_dir.mkdir(parents=True)
        photo_path = group_dir / (view_id + ".jpg")
        photo_path.write_bytes(data)
        view = {"id": view_id, "file": photo_path.name, "width": width, "height": height,
                "session": "", "captured_at_ms": 1100, "observations": observations(points)}
        group = {"schema": 4, "id": group_id, "created": 1000, "views": [view]}
        journal = {"schema": 1, "group_id": group_id, "view_id": view_id, "photo_file": photo_path.name,
                   "image_sha256": hashlib.sha256(data).hexdigest(), "width": width, "height": height,
                   "group_created_ms": 1000, "captured_at_ms": 1100, "revision": 2,
                   "baseline": {"kind": kind, "observed_at_ms": 1200, "points": copy.deepcopy(points)},
                   "latest": {"saved_at_ms": 1300, "complete": True, "points": points}}
        journal["latest"]["fingerprint"] = fingerprint(journal)
        fixture = SimpleNamespace(group=group, journal=journal, group_dir=group_dir,
                                  group_path=group_dir / "group.json", photo_path=photo_path,
                                  journal_path=review_dir / (view_id + ".json"), data=data)
        self.save(fixture)
        return fixture

    def save(self, fixture, resign=False):
        if resign:
            fixture.journal["latest"]["fingerprint"] = fingerprint(fixture.journal)
        write_json(fixture.group_path, fixture.group)
        write_json(fixture.journal_path, fixture.journal)

    def alias(self, fixture, view_id="alias", points=None):
        view = copy.deepcopy(fixture.group["views"][0])
        view.update(id=view_id, file=view_id + ".jpg")
        if points is not None:
            view["observations"] = observations(points)
        fixture.group["views"].append(view)
        (fixture.group_dir / view["file"]).write_bytes(fixture.data)
        return view

    def export(self):
        self.counter += 1
        output = self.directory / ("export-" + str(self.counter))
        report = exporter.export_reviews(self.files, output)
        return output, report

    def read(self, output, filename):
        return json.loads((output / filename).read_text(encoding="utf-8"))

    def assert_skipped(self, reason):
        output, report = self.export()
        self.assertEqual(report["exported_photos"], 0)
        self.assertIn(reason, report["skip_counts"])
        self.assertEqual(list(output.rglob("*.jpg")), [])
        self.assertEqual(self.read(output, "manifest.json")["photos"], [])
        self.assertEqual(self.read(output, "labels.json")["photos"], [])
        return output, report

    def test_export_preserves_bytes_and_evaluator_coordinate_contract(self):
        fixture = self.fixture(points=[point(x=600.25, y=100.25)], data=jpeg((1200, 125)))
        output, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        self.assertEqual(report["skipped"], [])
        manifest, _ = impact.load_manifest(output / "manifest.json")
        self.assertEqual(manifest[0]["split"], "unassigned")
        self.assertEqual(manifest[0]["path"].read_bytes(), fixture.data)
        self.assertEqual(manifest[0]["id"], "sha256-" + fixture.journal["image_sha256"])
        labels = impact.load_labels(output / "labels.json")[manifest[0]["id"]]
        self.assertTrue(labels["complete"])
        self.assertEqual(labels["clear"], [[600.25 * 600 / 1200, 100.25 * 62 / 125]])
        self.assertNotEqual(labels["clear"][0][1], 100.25 * .5)
        row = self.read(output, "labels.json")["photos"][0]
        self.assertEqual(row["points"][0]["confidence"], "clear")
        self.assertFalse(Path(self.read(output, "manifest.json")["photos"][0]["path"]).is_absolute())

    def test_explicit_complete_empty_review_is_a_negative_example(self):
        self.fixture(points=[])
        output, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        labels = next(iter(impact.load_labels(output / "labels.json").values()))
        self.assertEqual(labels, {"clear": [], "uncertain": [], "complete": True})

    def test_synced_embedded_review_exports_without_a_sidecar(self):
        fixture = self.fixture()
        fixture.group["photo_reviews"] = {"view1": copy.deepcopy(fixture.journal)}
        self.save(fixture)
        fixture.journal_path.unlink()
        output, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        self.assertEqual(report["skipped"], [])

    def test_synced_uncheck_wins_over_a_stale_checked_sidecar(self):
        fixture = self.fixture()
        shared = copy.deepcopy(fixture.journal)
        shared["latest"]["complete"] = False
        fixture.group["photo_reviews"] = {"view1": shared}
        self.save(fixture)
        self.assert_skipped("incomplete_review")

    def test_pending_local_commit_blocks_embedded_review(self):
        fixture = self.fixture()
        fixture.group["photo_reviews"] = {"view1": copy.deepcopy(fixture.journal)}
        self.save(fixture)
        fixture.journal_path.with_suffix(".pending").write_text("pending")
        self.assert_skipped("pending_review")

    def test_malformed_embedded_review_never_falls_back_to_old_labels(self):
        fixture = self.fixture()
        fixture.group["photo_reviews"] = {"view1": None}
        self.save(fixture)
        self.assert_skipped("invalid_json")

    def test_embedded_and_sidecar_do_not_double_count_a_review(self):
        fixture = self.fixture()
        fixture.group["photo_reviews"] = {"view1": copy.deepcopy(fixture.journal)}
        self.save(fixture)
        output, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        self.assertEqual(report["skipped"], [])

    def test_missing_flag_is_not_inferred_from_model_marks(self):
        fixture = self.fixture()
        del fixture.journal["latest"]["complete"]
        self.save(fixture)
        with patch.object(exporter, "_jpeg_bytes", side_effect=AssertionError("Must not open the photo")):
            self.assert_skipped("missing_explicit_completion")

    def test_only_boolean_true_is_complete(self):
        fixture = self.fixture(points=[])
        for flag in (False, None, 0, 1, "true", "false", [], {}):
            with self.subTest(flag=flag):
                fixture.journal["latest"]["complete"] = flag
                self.save(fixture)
                self.assert_skipped("incomplete_review" if flag is False else "missing_explicit_completion")

    def test_missing_journal_does_not_infer_review(self):
        fixture = self.fixture()
        fixture.journal_path.unlink()
        self.assert_skipped("missing_review")

    def test_missing_points_cannot_become_a_negative_example(self):
        fixture = self.fixture(points=[])
        del fixture.journal["latest"]["points"]
        self.save(fixture)
        self.assert_skipped("invalid_points")

    def test_pending_marker_blocks_old_complete_even_with_unchanged_points(self):
        fixture = self.fixture()
        pending = fixture.journal_path.with_suffix(".pending")
        pending.write_bytes(b"")
        with patch.object(exporter, "_jpeg_bytes", side_effect=AssertionError("Must not open the photo")):
            self.assert_skipped("pending_review")
        self.assertEqual(pending.read_bytes(), b"")
        pending.unlink()
        _, report = self.export()
        self.assertEqual(report["exported_photos"], 1)

    def test_pending_directory_and_corrupt_journal_still_block(self):
        fixture = self.fixture()
        fixture.journal_path.with_suffix(".pending").mkdir()
        fixture.journal_path.write_text("broken journal", encoding="utf-8")
        self.assert_skipped("pending_review")

    def test_pending_marker_is_scoped_to_its_view(self):
        fixture = self.fixture()
        (fixture.journal_path.parent / "other.pending").write_bytes(b"")
        _, report = self.export()
        self.assertEqual(report["exported_photos"], 1)

    def test_stale_moved_added_removed_or_renumbered_points(self):
        fixture = self.fixture()
        original = copy.deepcopy(fixture.group["views"][0]["observations"])
        variants = [[dict(original[0], a=[301, 200.5])], [],
                    original + observations([point(2, 100, 100)]), [dict(original[0], arrow=2)]]
        for changed in variants:
            with self.subTest(changed=changed):
                fixture.group["views"][0]["observations"] = changed
                self.save(fixture)
                self.assert_skipped("stale_points")

    def test_resigned_old_geometry_still_must_match_current_group(self):
        fixture = self.fixture()
        fixture.journal["latest"]["points"][0]["x"] += 1
        self.save(fixture, resign=True)
        self.assert_skipped("stale_points")

    def test_stale_image_uses_saved_bytes_not_source_digest(self):
        fixture = self.fixture()
        fixture.group["views"][0]["source_sha256"] = fixture.journal["image_sha256"]
        self.save(fixture)
        fixture.photo_path.write_bytes(jpeg(color=(180, 0, 0)))
        self.assert_skipped("stale_image")

    def test_source_digest_can_differ_from_saved_jpeg_hash(self):
        fixture = self.fixture()
        fixture.group["views"][0]["source_sha256"] = "f" * 64
        self.save(fixture)
        _, report = self.export()
        self.assertEqual(report["exported_photos"], 1)

    def test_metadata_only_edits_do_not_invalidate_review(self):
        fixture = self.fixture()
        fixture.group.update(shooter={"id": "someone-else"}, target_type="changed",
                             diameter_cm=55, practice={"arrows_shot": 99, "unlocated_misses": 4})
        view = fixture.group["views"][0]
        view["calibration"] = {"reviewed": False, "h_image_to_radius": [1] * 9}
        view["observations"][0]["source"] = "target-assigned:manual"
        view["observations"][0]["association_warning"] = "Shooter changed"
        self.save(fixture)
        _, report = self.export()
        self.assertEqual(report["exported_photos"], 1)

    def test_same_photo_aliases_dedupe_exact_ids_and_merge_disjoint_ids(self):
        fixture = self.fixture(points=[point(1, -0.0, 20)])
        fixture.group["views"][0].update(session="photo-assign:family", source_sha256="a" * 64)
        alias = self.alias(fixture, points=[point(1, 0.0, 20), point(2, 70, 80)])
        alias["source_sha256"] = "b" * 64
        fixture.journal["latest"]["points"].append(point(2, 70, 80))
        self.save(fixture, resign=True)
        output, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        self.assertEqual(len(self.read(output, "labels.json")["photos"][0]["points"]), 2)
        review = self.read(output, "review-metadata.json")["photos"][0]["reviews"][0]
        self.assertEqual(review["family_view_ids"], ["view1", "alias"])
        self.assertEqual(review["photo_family_key"], "photo-assign:family")

    def test_source_hash_family_dedupes_aliases_without_assignment_session(self):
        fixture = self.fixture()
        fixture.group["views"][0].update(session="camera-session", source_sha256="a" * 64)
        self.alias(fixture)["session"] = "other-camera-session"
        self.save(fixture)
        _, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        self.assertEqual(report["skipped"], [])

    def test_assignment_session_precedes_source_hash_and_plain_session_is_not_family(self):
        fixture = self.fixture()
        canonical = fixture.group["views"][0]
        canonical.update(session="photo-assign:first", source_sha256="a" * 64)
        other = self.alias(fixture, points=[point(2, 70, 80)])
        other["session"] = "photo-assign:second"
        self.save(fixture)
        _, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        self.assertEqual(report["skip_counts"], {"missing_review": 1})
        for view in fixture.group["views"]:
            view.update(session="shared-camera-session", source_sha256="")
        self.save(fixture)
        _, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        self.assertEqual(report["skip_counts"], {"missing_review": 1})

    def test_changed_alias_image_invalidates_entire_family(self):
        fixture = self.fixture()
        fixture.group["views"][0]["session"] = "photo-assign:family"
        alias = self.alias(fixture)
        self.save(fixture)
        (fixture.group_dir / alias["file"]).write_bytes(jpeg(color=(0, 200, 0)))
        self.assert_skipped("stale_image")

    def test_inconsistent_duplicate_arrow_ids_are_rejected(self):
        fixture = self.fixture()
        fixture.group["views"][0]["session"] = "photo-assign:family"
        self.alias(fixture, points=[point(x=301)])
        self.save(fixture)
        self.assert_skipped("conflicting_arrow_id")

    def test_journal_points_must_have_unique_ids(self):
        fixture = self.fixture()
        fixture.journal["latest"]["points"] *= 2
        self.save(fixture, resign=True)
        self.assert_skipped("duplicate_arrow_id")

    def test_noncanonical_journal_is_not_accepted(self):
        fixture = self.fixture()
        fixture.group["views"][0]["session"] = "photo-assign:family"
        self.alias(fixture)
        fixture.journal["view_id"] = "alias"
        fixture.journal["photo_file"] = "alias.jpg"
        fixture.journal_path.unlink()
        fixture.journal_path = fixture.journal_path.with_name("alias.json")
        self.save(fixture)
        self.assert_skipped("noncanonical_review")

    def test_shaft_only_mixed_and_disguised_shaft_families_are_rejected(self):
        fixture = self.fixture(points=[])
        shaft = {"arrow": 2, "kind": "shaft", "a": [10, 20], "b": [20, 30], "source": "manual"}
        for rows in ([shaft], observations([point()]) + [shaft], [dict(shaft, kind="impact")]):
            with self.subTest(rows=rows):
                fixture.group["views"][0]["observations"] = rows
                self.save(fixture)
                self.assert_skipped("non_impact_family")

    def test_out_of_bounds_latest_baseline_and_group_points(self):
        fixture = self.fixture()
        original = copy.deepcopy(fixture.journal)
        for x, y in ((-1, 5), (1200, 5), (5, -1), (5, 803)):
            with self.subTest(x=x, y=y):
                fixture.journal = copy.deepcopy(original)
                fixture.journal["latest"]["points"][0].update(x=x, y=y)
                self.save(fixture, resign=True)
                self.assert_skipped("out_of_bounds")
        fixture.journal = copy.deepcopy(original)
        fixture.journal["baseline"]["points"][0]["x"] = 1200
        self.save(fixture)
        self.assert_skipped("out_of_bounds")
        fixture.journal = original
        fixture.group["views"][0]["observations"][0]["a"] = [1200, 1]
        self.save(fixture)
        self.assert_skipped("out_of_bounds")

    def test_invalid_point_numbers_and_arrow_ids(self):
        fixture = self.fixture()
        original = copy.deepcopy(fixture.journal)
        for key, value, reason in (("x", True, "invalid_points"), ("y", "20", "invalid_points"),
                                   ("arrow", 0, "invalid_arrow_id"), ("arrow", -1, "invalid_arrow_id"),
                                   ("arrow", True, "invalid_arrow_id"), ("arrow", 2**31, "invalid_arrow_id"),
                                   ("arrow", 1.0, "invalid_arrow_id"), ("source", None, "invalid_point_source")):
            with self.subTest(key=key, value=value):
                fixture.journal = copy.deepcopy(original)
                fixture.journal["latest"]["points"][0][key] = value
                self.save(fixture)
                self.assert_skipped(reason)

    def test_corrupt_fingerprint_is_rejected(self):
        fixture = self.fixture()
        fixture.journal["latest"]["fingerprint"] = "0" * 64
        self.save(fixture)
        self.assert_skipped("corrupt_fingerprint")

    def test_fingerprint_big_endian_sorted_and_negative_zero_normalized(self):
        self.assertEqual(exporter.review_fingerprint("a" * 64, 600, 800,
                                                    [{"arrow": 1, "x": -0.0, "y": 12.5}]),
                         "8a05fe00b6d86e63b88864332cf9609e6b52f282f831d56d27c6fdda9166667c")
        fixture = self.fixture(points=[point(3, 4.5, -0.0), point(1, -0.0, 2.25)])
        journal = fixture.journal
        actual = exporter.review_fingerprint(journal["image_sha256"], 1200, 803, journal["latest"]["points"])
        self.assertEqual(actual, fingerprint(journal))
        normalized = [point(1, 0, 2.25), point(3, 4.5, 0)]
        self.assertEqual(actual, exporter.review_fingerprint(journal["image_sha256"], 1200, 803, normalized))
        _, report = self.export()
        self.assertEqual(report["exported_photos"], 1)

    def test_invalid_and_stale_dimensions(self):
        fixture = self.fixture()
        original = copy.deepcopy(fixture.journal)
        for key, value in (("width", 0), ("height", -1), ("width", True), ("height", 803.0),
                           ("width", 2**31), ("height", 1), ("height", None)):
            with self.subTest(key=key, value=value):
                fixture.journal = copy.deepcopy(original)
                fixture.journal[key] = value
                self.save(fixture)
                self.assert_skipped("invalid_dimensions")
        fixture.journal = original
        fixture.group["views"][0]["width"] = 1199
        self.save(fixture)
        self.assert_skipped("stale_dimensions")

    def test_exif_upright_dimensions_not_raw_jpeg_dimensions(self):
        fixture = self.fixture(data=jpeg((90, 60), orientation=6), points=[point(x=40, y=70)])
        output, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        manifest = self.read(output, "manifest.json")["photos"][0]
        self.assertEqual((manifest["width"], manifest["height"]), (60, 90))
        self.assertEqual((output / manifest["path"]).read_bytes(), fixture.data)
        label = self.read(output, "labels.json")["photos"][0]["points"][0]
        self.assertEqual((label["x"], label["y"]), (400, 700))
        fixture.group["views"][0].update(width=90, height=60, observations=[])
        fixture.journal.update(width=90, height=60)
        fixture.journal["latest"]["points"] = []
        fixture.journal["baseline"]["points"] = []
        self.save(fixture, resign=True)
        self.assert_skipped("stale_dimensions")

    def test_corrupt_or_non_jpeg_image_is_rejected_even_if_hash_matches(self):
        fixture = self.fixture()
        png = io.BytesIO()
        with Image.new("RGB", (1200, 803)) as image:
            image.save(png, "PNG")
        for data in (b"not a jpeg", png.getvalue(), fixture.data[:100]):
            with self.subTest(data_length=len(data)):
                fixture.photo_path.write_bytes(data)
                fixture.journal["image_sha256"] = hashlib.sha256(data).hexdigest()
                self.save(fixture, resign=True)
                self.assert_skipped("invalid_jpeg")

    def test_duplicate_image_dedupes_labels_but_retains_all_groups_and_provenance(self):
        first = self.fixture(group_id="group1", points=[point(1, 200, 100), point(2, 50, 30)])
        second = self.fixture(group_id="group2", view_id="other", points=[point(7, 50, 30), point(9, 200, 100)])
        second.group["round_key"] = "explicit-round-2"
        self.save(second)
        output, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        self.assertEqual(report["exported_reviews"], 2)
        row = self.read(output, "manifest.json")["photos"][0]
        self.assertEqual(row["group_keys"], ["group1", "group2"])
        self.assertEqual(row["round_keys"], ["explicit-round-2", "group:group1"])
        self.assertEqual(len(list((output / "photos").iterdir())), 1)
        reviews = self.read(output, "review-metadata.json")["photos"][0]["reviews"]
        self.assertEqual([review["journal"] for review in reviews], [first.journal, second.journal])

    def test_duplicate_photo_conflicts_exclude_every_review_not_first_or_last_wins(self):
        self.fixture(group_id="group1")
        self.fixture(group_id="group2", points=[point(x=300.2500000001)])
        self.fixture(group_id="group3")
        self.assert_skipped("conflicting_duplicate_image")
        unrelated = self.fixture(group_id="group4", data=jpeg(color=(40, 0, 0)))
        output, report = self.export()
        self.assertEqual(report["exported_photos"], 1)
        self.assertEqual(report["skip_counts"], {"conflicting_duplicate_image": 3})
        self.assertEqual(self.read(output, "manifest.json")["photos"][0]["image_sha256"],
                         unrelated.journal["image_sha256"])

    def test_duplicate_photo_empty_vs_nonempty_and_multiplicity_conflict(self):
        self.fixture(group_id="group1")
        second = self.fixture(group_id="group2", points=[])
        self.assert_skipped("conflicting_duplicate_image")
        second.journal["latest"]["points"] = [point(1), point(2)]
        second.group["views"][0]["observations"] = observations(second.journal["latest"]["points"])
        self.save(second, resign=True)
        self.assert_skipped("conflicting_duplicate_image")

    def test_review_start_and_detector_output_are_preserved_without_conflation(self):
        detector = self.fixture()
        detector.journal["baseline"].update(model_sha256="a" * 64, threshold=.16,
                                           points=[point(9, 80, 90)])
        self.save(detector)
        start = self.fixture(group_id="group2", kind="review-start", data=jpeg(color=(200, 0, 0)))
        output, _ = self.export()
        by_group = {review["group_id"]: review for photo in self.read(output, "review-metadata.json")["photos"]
                    for review in photo["reviews"]}
        self.assertEqual(by_group["group1"]["journal"]["baseline"], detector.journal["baseline"])
        self.assertEqual(by_group["group2"]["journal"]["baseline"], start.journal["baseline"])
        self.assertNotIn("model_sha256", by_group["group2"]["journal"]["baseline"])
        self.assertEqual(by_group["group1"]["journal"]["latest"]["points"][0]["x"], 300.25)

    def test_malformed_provenance_is_skipped_without_copying(self):
        fixture = self.fixture()
        original = copy.deepcopy(fixture.journal)
        for key, value, reason in (("kind", "ai-reviewed", "invalid_baseline"),
                                   ("observed_at_ms", True, "invalid_observed_at_ms"),
                                   ("model_sha256", "unknown", "invalid_model_sha256"),
                                   ("threshold", "0.16", "invalid_threshold"),
                                   ("threshold", 2.0, "invalid_threshold"),
                                   ("points", None, "invalid_points")):
            with self.subTest(key=key, value=value):
                fixture.journal = copy.deepcopy(original)
                fixture.journal["baseline"][key] = value
                self.save(fixture)
                self.assert_skipped(reason)

    def test_schema_revision_and_timestamp_types_are_checked(self):
        fixture = self.fixture()
        original = copy.deepcopy(fixture.journal)
        for key, value, reason in (("schema", 2, "unsupported_schema"), ("schema", True, "unsupported_schema"),
                                   ("revision", -1, "invalid_revision"), ("revision", True, "invalid_revision"),
                                   ("group_created_ms", "1000", "invalid_group_created_ms"),
                                   ("captured_at_ms", -1, "invalid_captured_at_ms")):
            with self.subTest(key=key, value=value):
                fixture.journal = copy.deepcopy(original)
                fixture.journal[key] = value
                self.save(fixture)
                self.assert_skipped(reason)
        fixture.journal = original
        fixture.journal["latest"]["saved_at_ms"] = None
        self.save(fixture)
        self.assert_skipped("invalid_saved_at_ms")

    def test_unsafe_photo_names_never_escape_sources_or_leak_into_report(self):
        fixture = self.fixture()
        for name in ("../outside.jpg", "..\\outside.jpg", "/outside.jpg", "C:\\outside.jpg",
                     "image.jpg:stream", "NUL.jpg", "photo.jpg ", "sub/photo.jpg", "\\\\host\\photo.jpg"):
            with self.subTest(name=name):
                fixture.journal["photo_file"] = name
                self.save(fixture)
                output, _ = self.assert_skipped("unsafe_photo_path")
                self.assertNotIn(name, (output / "export-report.json").read_text())

    def test_unsafe_alias_path_blocks_canonical_review(self):
        fixture = self.fixture()
        fixture.group["views"][0]["session"] = "photo-assign:family"
        self.alias(fixture)["file"] = "../outside.jpg"
        self.save(fixture)
        self.assert_skipped("unsafe_photo_path")

    def test_identity_mismatches_and_missing_view_are_rejected(self):
        fixture = self.fixture()
        original = copy.deepcopy(fixture.journal)
        for key, value in (("group_id", "../other"), ("view_id", "other"), ("photo_file", "other.jpg"),
                           ("group_created_ms", 999), ("captured_at_ms", 999)):
            with self.subTest(key=key):
                fixture.journal = copy.deepcopy(original)
                fixture.journal[key] = value
                self.save(fixture)
                reason = ("photo_identity_mismatch" if key == "photo_file" else
                          "capture_identity_mismatch" if key.endswith("_ms") else "journal_identity_mismatch")
                self.assert_skipped(reason)
        fixture.journal = original
        fixture.group["views"] = []
        self.save(fixture)
        self.assert_skipped("missing_view")

    def test_invalid_group_identity_and_duplicate_views(self):
        fixture = self.fixture()
        fixture.group["id"] = "other"
        self.save(fixture)
        self.assert_skipped("group_identity_mismatch")
        fixture.group["id"] = "group1"
        fixture.group["views"] *= 2
        self.save(fixture)
        self.assert_skipped("duplicate_view_id")

    def test_strict_json_rejects_duplicate_keys_nan_and_infinity(self):
        fixture = self.fixture()
        serialized = json.dumps(fixture.journal)
        for text in (serialized.replace('"complete": true', '"complete": false, "complete": true'),
                     serialized.replace('"x": 300.25', '"x": NaN'),
                     serialized.replace('"x": 300.25', '"x": 1e999')):
            with self.subTest(text=text[:30]):
                fixture.journal_path.write_text(text, encoding="utf-8")
                self.assert_skipped("invalid_json")

    def test_missing_photo_is_safely_skipped(self):
        fixture = self.fixture()
        fixture.photo_path.unlink()
        self.assert_skipped("missing_file")

    def test_sources_are_immutable_and_repeated_exports_are_stable(self):
        self.fixture()
        before = {path.relative_to(self.files): (path.read_bytes(), path.stat().st_mtime_ns)
                  for path in self.files.rglob("*") if path.is_file()}
        first, _ = self.export()
        second, _ = self.export()
        after = {path.relative_to(self.files): (path.read_bytes(), path.stat().st_mtime_ns)
                 for path in self.files.rglob("*") if path.is_file()}
        self.assertEqual(before, after)
        for filename in ("manifest.json", "labels.json", "review-metadata.json", "export-report.json"):
            self.assertEqual((first / filename).read_bytes(), (second / filename).read_bytes())

    def test_never_overwrites_existing_directory_or_file(self):
        self.fixture()
        output, _ = self.export()
        before = {path.relative_to(output): path.read_bytes() for path in output.rglob("*") if path.is_file()}
        with self.assertRaises(FileExistsError):
            exporter.export_reviews(self.files, output)
        self.assertEqual(before, {path.relative_to(output): path.read_bytes()
                                  for path in output.rglob("*") if path.is_file()})
        occupied = self.directory / "occupied"
        occupied.write_bytes(b"keep this")
        with self.assertRaises(FileExistsError):
            exporter.export_reviews(self.files, occupied)
        self.assertEqual(occupied.read_bytes(), b"keep this")

    def test_output_must_be_private_and_separate_from_source(self):
        self.fixture()
        for output in (ROOT / "tools" / "do-not-create", self.files / "export", self.directory):
            with self.subTest(output=output), self.assertRaises(ValueError):
                exporter.export_reviews(self.files, output)
        self.assertFalse((ROOT / "tools" / "do-not-create").exists())
        self.assertFalse((self.files / "export").exists())

    def test_reparse_points_and_links_are_rejected(self):
        fixture = self.fixture()
        original = Path.lstat
        def lstat(path, *args, **kwargs):
            if path == fixture.photo_path:
                return SimpleNamespace(st_mode=stat.S_IFREG, st_file_attributes=stat.FILE_ATTRIBUTE_REPARSE_POINT)
            return original(path, *args, **kwargs)
        with patch.object(Path, "lstat", lstat):
            self.assert_skipped("unsafe_path")

    def test_real_symlink_photo_is_not_followed(self):
        fixture = self.fixture()
        outside = self.directory / "outside.jpg"
        outside.write_bytes(fixture.data)
        fixture.photo_path.unlink()
        try:
            fixture.photo_path.symlink_to(outside)
        except OSError as error:
            self.skipTest("Symlink creation unavailable: " + type(error).__name__)
        self.assert_skipped("unsafe_path")

    def test_cli_exports_reports_skips_and_refuses_existing_output(self):
        self.fixture()
        output = self.directory / "cli-output"
        command = [sys.executable, "-B", str(ROOT / "tools/export_training_reviews.py"),
                   "--files", str(self.files), "--output", str(output)]
        environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        result = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["exported_photos"], 1)
        repeated = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, text=True, check=False)
        self.assertEqual(repeated.returncode, 2)
        self.assertIn("Export failed", repeated.stdout)


if __name__ == "__main__":
    unittest.main()
