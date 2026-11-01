"""Export explicitly completed Android photo reviews, entirely offline.

Run with .venv-impact/Scripts/python.exe tools/export_training_reviews.py
    --files <extracted Android files directory> --output private/<fresh directory>

The output must be new and under this repository's ignored private/ or build/
directories (build/ is useful for synthetic fixtures). Inputs are read-only.
Any sibling <view_id>.pending marker blocks its journal until the phone finishes
both saves. Missing, incomplete, stale, or conflicting reviews never copy photos.
Images retain their exact JPEG bytes; labels use EXIF-upright 600px coordinates.
No split is inferred, and no training or deployment is performed. Choose splits
later using all group_keys/round_keys, including links through duplicate photos.
Until Android records an explicit round_key, a group is the round/end boundary.
An empty export has an empty manifest and cannot be evaluated or trained on.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import hashlib
import io
import json
import math
from pathlib import Path
import re
import stat
import struct
import warnings

from PIL import Image, ImageOps


ROOT = Path(__file__).resolve().parents[1]
REVIEW_WIDTH = 600
INT32_MAX = 2**31 - 1
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
RESERVED = re.compile(r"(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)", re.I)


class InvalidReview(ValueError):
    """A stable reason code, never an exception containing private source data."""


def _fail(reason: str) -> None:
    raise InvalidReview(reason)


def _identifier(value) -> str:
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value) or RESERVED.match(value):
        _fail("unsafe_identity")
    return value


def _photo_name(value) -> str:
    if (not isinstance(value, str) or not value or len(value) > 240 or
            value in (".", "..") or value[-1] in " ." or
            re.search(r'[<>:"/\\|?*\x00-\x1f]', value) or RESERVED.match(value) or
            Path(value).suffix.lower() not in (".jpg", ".jpeg")):
        _fail("unsafe_photo_path")
    return value


def _no_links(path: Path) -> None:
    # Reparse points include Windows junctions, not just symbolic links.
    for part in (path, *path.parents):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if (stat.S_ISLNK(info.st_mode) or
                getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT):
            _fail("unsafe_path")


def _safe_path(path: Path, root: Path, directory: bool = False) -> Path:
    try:
        _no_links(path)
        resolved = path.resolve(strict=True)
        if not resolved.is_relative_to(root):
            _fail("unsafe_path")
        mode = resolved.stat().st_mode
        if not (stat.S_ISDIR(mode) if directory else stat.S_ISREG(mode)):
            _fail("unsafe_path")
        return resolved
    except FileNotFoundError:
        _fail("missing_file")
    except OSError:
        _fail("unreadable_source")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail("invalid_json")
        result[key] = value
    return result


def _json_float(value):
    number = float(value)
    if not math.isfinite(number):
        _fail("invalid_json")
    return number


def _read_json(path: Path, root: Path) -> dict:
    path = _safe_path(path, root)
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"),
                           object_pairs_hook=_unique_object, parse_float=_json_float,
                           parse_constant=lambda _: _fail("invalid_json"))
    except (ValueError, UnicodeError, RecursionError):
        _fail("invalid_json")
    except OSError:
        _fail("unreadable_source")
    if not isinstance(value, dict):
        _fail("invalid_json")
    return value


def _integer(value, reason: str, minimum: int = 0, maximum: int = 2**63 - 1) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        _fail(reason)
    return value


def _number(value, reason: str) -> float:
    if type(value) not in (int, float):
        _fail(reason)
    try:
        result = float(value)
    except OverflowError:
        _fail(reason)
    if not math.isfinite(result):
        _fail(reason)
    return 0.0 if result == 0 else result


def _digest(value, reason: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        _fail(reason)
    return value


def _dimensions(document: dict) -> tuple[int, int]:
    width = _integer(document.get("width"), "invalid_dimensions", 1, INT32_MAX)
    height = _integer(document.get("height"), "invalid_dimensions", 1, INT32_MAX)
    if not 60 <= round(height * REVIEW_WIDTH / width) <= 4800:
        _fail("invalid_dimensions")
    return width, height


def _points(rows, width: int, height: int, aliases: bool = False) -> list[dict]:
    if not isinstance(rows, list) or len(rows) > INT32_MAX:
        _fail("invalid_points")
    points = {}
    for row in rows:
        if not isinstance(row, dict):
            _fail("invalid_points")
        arrow = _integer(row.get("arrow"), "invalid_arrow_id", 1, INT32_MAX)
        x, y = (_number(row.get(axis), "invalid_points") for axis in ("x", "y"))
        if not 0 <= x < width or not 0 <= y < height:
            _fail("out_of_bounds")
        if not isinstance(row.get("source"), str):
            _fail("invalid_point_source")
        if "detector_score" in row:
            _number(row["detector_score"], "invalid_detector_score")
        if arrow in points:
            previous = points[arrow]
            if (previous["x"], previous["y"]) != (x, y):
                _fail("conflicting_arrow_id")
            if not aliases:
                _fail("duplicate_arrow_id")
        else:
            points[arrow] = dict(row, x=x, y=y)
    return [points[arrow] for arrow in sorted(points)]


def review_fingerprint(image_sha256: str, width: int, height: int, points: list[dict]) -> str:
    """Android's v1 byte protocol; callers validate the point schema separately."""
    digest = hashlib.sha256(("redwood-dot-review-v1\n" + image_sha256 + "\n").encode("ascii"))
    digest.update(struct.pack(">iii", width, height, len(points)))
    for point in sorted(points, key=lambda item: item["arrow"]):
        x, y = (0.0 if point[axis] == 0 else float(point[axis]) for axis in ("x", "y"))
        digest.update(struct.pack(">idd", point["arrow"], x, y))
    return digest.hexdigest()


def photo_family_key(view: dict) -> str:
    session, source = view.get("session", ""), view.get("source_sha256", "")
    if not isinstance(session, str) or not isinstance(source, str):
        _fail("invalid_photo_family")
    if source:
        _digest(source, "invalid_photo_family")
    return (session if session.startswith("photo-assign:") else
            "sha:" + source if source else "view:" + _identifier(view.get("id")))


@dataclass
class Group:
    document: dict
    families: dict[str, list[dict]]


def _group(path: Path, files: Path) -> Group:
    document = _read_json(path / "group.json", files)
    if document.get("id") != path.name:
        _fail("group_identity_mismatch")
    _integer(document.get("created"), "invalid_group_created_ms")
    views = document.get("views")
    if "photo_reviews" in document and (not isinstance(document["photo_reviews"], dict) or len(document["photo_reviews"]) > 64):
        _fail("invalid_json")
    if not isinstance(views, list):
        _fail("invalid_group_views")
    families, ids = {}, set()
    for view in views:
        if not isinstance(view, dict):
            _fail("invalid_group_views")
        view_id = _identifier(view.get("id"))
        if view_id in ids:
            _fail("duplicate_view_id")
        ids.add(view_id)
        families.setdefault(photo_family_key(view), []).append(view)
    return Group(document, families)


def _current_points(family: list[dict], width: int, height: int) -> list[dict]:
    rows = []
    for view in family:
        if _dimensions(view) != (width, height):
            _fail("stale_dimensions")
        observations = view.get("observations")
        if not isinstance(observations, list):
            _fail("invalid_group_points")
        for observation in observations:
            if not isinstance(observation, dict):
                _fail("invalid_group_points")
            if observation.get("kind") != "impact" or observation.get("b") is not None:
                _fail("non_impact_family")
            xy = observation.get("a")
            if not isinstance(xy, list) or len(xy) != 2:
                _fail("invalid_group_points")
            row = {"arrow": observation.get("arrow"), "x": xy[0], "y": xy[1],
                   "source": observation.get("source", "manual")}
            if "detector_score" in observation:
                row["detector_score"] = observation["detector_score"]
            rows.append(row)
    return _points(rows, width, height, aliases=True)


def _geometry(points: list[dict]) -> list[tuple]:
    return [(point["arrow"], point["x"], point["y"]) for point in points]


def _jpeg_bytes(path: Path, files: Path, expected: str) -> bytes:
    path = _safe_path(path, files)
    try:
        data = path.read_bytes()
    except OSError:
        _fail("unreadable_source")
    if hashlib.sha256(data).hexdigest() != expected:
        _fail("stale_image")
    return data


def _upright_size(data: bytes) -> tuple[int, int]:
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as image:
                if image.format != "JPEG":
                    _fail("invalid_jpeg")
                image.load()
                with ImageOps.exif_transpose(image) as upright:
                    return upright.size
    except InvalidReview:
        raise
    except (OSError, ValueError, SyntaxError, Image.DecompressionBombError,
            Image.DecompressionBombWarning):
        _fail("invalid_jpeg")


@dataclass
class Review:
    group_id: str
    view_id: str
    width: int
    height: int
    points: list[dict]
    image_bytes: bytes
    metadata: dict


_NO_EMBEDDED = object()


def _review(path: Path, group_id: str, view_id: str, groups: dict[str, Group], files: Path, embedded=_NO_EMBEDDED) -> Review:
    try:
        path.with_suffix(".pending").lstat()
    except FileNotFoundError:
        pass
    except OSError:
        _fail("unreadable_source")
    else:
        _fail("pending_review")
    journal = _read_json(path, files) if embedded is _NO_EMBEDDED else embedded
    if not isinstance(journal, dict):
        _fail("invalid_json")
    if type(journal.get("schema")) is not int or journal["schema"] != 1:
        _fail("unsupported_schema")
    if journal.get("group_id") != group_id or journal.get("view_id") != view_id:
        _fail("journal_identity_mismatch")
    latest = journal.get("latest")
    if not isinstance(latest, dict):
        _fail("invalid_latest")
    if latest.get("complete") is not True:
        _fail("incomplete_review" if latest.get("complete") is False else "missing_explicit_completion")
    if group_id not in groups:
        _fail("missing_or_invalid_group")
    group = groups[group_id]
    family = next((family for family in group.families.values()
                   if any(view["id"] == view_id for view in family)), None)
    if family is None:
        _fail("missing_view")
    if family[0]["id"] != view_id:
        _fail("noncanonical_review")
    photo_file = _photo_name(journal.get("photo_file"))
    if photo_file != _photo_name(family[0].get("file")):
        _fail("photo_identity_mismatch")
    image_hash = _digest(journal.get("image_sha256"), "invalid_image_sha256")
    width, height = _dimensions(journal)
    created = _integer(journal.get("group_created_ms"), "invalid_group_created_ms")
    captured = _integer(journal.get("captured_at_ms"), "invalid_captured_at_ms")
    if (created != group.document["created"] or
            captured != _integer(family[0].get("captured_at_ms", 0), "invalid_captured_at_ms")):
        _fail("capture_identity_mismatch")
    _integer(journal.get("revision"), "invalid_revision")
    _integer(latest.get("saved_at_ms"), "invalid_saved_at_ms")
    points = _points(latest.get("points"), width, height)
    fingerprint = _digest(latest.get("fingerprint"), "invalid_fingerprint")
    if review_fingerprint(image_hash, width, height, points) != fingerprint:
        _fail("corrupt_fingerprint")
    baseline = journal.get("baseline")
    if not isinstance(baseline, dict) or baseline.get("kind") not in ("detector-output", "review-start"):
        _fail("invalid_baseline")
    _integer(baseline.get("observed_at_ms"), "invalid_observed_at_ms")
    _points(baseline.get("points"), width, height)
    if "model_sha256" in baseline:
        _digest(baseline["model_sha256"], "invalid_model_sha256")
    if "threshold" in baseline:
        threshold = _number(baseline["threshold"], "invalid_threshold")
        if not 0 <= threshold <= 1:
            _fail("invalid_threshold")
    if _geometry(_current_points(family, width, height)) != _geometry(points):
        _fail("stale_points")
    image_data = None
    for view in family:
        data = _jpeg_bytes(files / "groups" / group_id / _photo_name(view.get("file")), files, image_hash)
        if image_data is None:
            image_data = data
    if _upright_size(image_data) != (width, height):
        _fail("stale_dimensions")
    round_key = group.document.get("round_key")
    if not isinstance(round_key, str) or not round_key:
        round_key = "group:" + group_id
    metadata = {"journal_path": path.relative_to(files).as_posix(), "embedded_in_group": embedded is not _NO_EMBEDDED, "group_id": group_id,
                "round_key": round_key, "photo_family_key": photo_family_key(family[0]),
                "family_view_ids": [view["id"] for view in family], "journal": journal}
    return Review(group_id, view_id, width, height, points, image_data, metadata)


def _skip(skipped: list[dict], reason: str, group_id=None, view_id=None) -> None:
    row = {"reason": reason}
    if group_id is not None:
        row["group_id"] = group_id
    if view_id is not None:
        row["view_id"] = view_id
    skipped.append(row)


def _children(path: Path, files: Path, optional: bool = False) -> list[Path]:
    try:
        return sorted(_safe_path(path, files, directory=True).iterdir(), key=lambda item: item.name)
    except InvalidReview as error:
        if optional and str(error) == "missing_file":
            return []
        raise
    except OSError:
        _fail("unreadable_source")


def _collect(files: Path, skipped: list[dict]) -> list[Review]:
    groups = {}
    for path in _children(files / "groups", files):
        group_id = None
        try:
            group_id = _identifier(path.name)
            _safe_path(path, files, directory=True)
            groups[group_id] = _group(path, files)
        except InvalidReview as error:
            _skip(skipped, str(error), group_id)
    reviews, seen = [], set()
    try:
        directories = _children(files / "training-reviews", files, optional=True)
    except InvalidReview as error:
        _skip(skipped, str(error))
        directories = []
    for directory in directories:
        group_id = None
        try:
            group_id = _identifier(directory.name)
            paths = _children(directory, files)
        except InvalidReview as error:
            _skip(skipped, str(error), group_id)
            continue
        for path in paths:
            if path.suffix != ".json":
                continue
            view_id = None
            try:
                view_id = _identifier(path.stem)
                if group_id in groups and view_id in groups[group_id].document.get("photo_reviews", {}):
                    continue
                seen.add((group_id, view_id))
                reviews.append(_review(path, group_id, view_id, groups, files))
            except InvalidReview as error:
                _skip(skipped, str(error), group_id, view_id)
    for group_id, group in groups.items():
        embedded = group.document.get("photo_reviews", {})
        if not isinstance(embedded, dict):
            _skip(skipped, "invalid_json", group_id)
            embedded = {}
        for key, record in embedded.items():
            view_id = None
            try:
                view_id = _identifier(key)
                seen.add((group_id, view_id))
                path = files / "training-reviews" / group_id / (view_id + ".json")
                _no_links(path)
                reviews.append(_review(path, group_id, view_id, groups, files, record))
            except InvalidReview as error:
                _skip(skipped, str(error), group_id, view_id)
        for family in group.families.values():
            view_id = family[0]["id"]
            if (group_id, view_id) not in seen:
                _skip(skipped, "missing_review", group_id, view_id)
    return reviews


def _output_path(output: Path, files: Path) -> Path:
    output = output.absolute()
    _no_links(output)
    output = output.resolve()
    if not any(output.is_relative_to((ROOT / folder).resolve()) for folder in ("private", "build")):
        raise ValueError("Output must be under the repository's ignored private/ or build/ directory")
    if output.is_relative_to(files) or files.is_relative_to(output):
        raise ValueError("Output must not overlap the extracted source directory")
    if output.exists():
        raise FileExistsError("Output already exists; choose a fresh private directory")
    return output


def _write_json(path: Path, document: dict) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as destination:
        json.dump(document, destination, indent=2, sort_keys=True, allow_nan=False)
        destination.write("\n")


def export_reviews(files: Path, output: Path) -> dict:
    """Validate all records and conflicts before creating any photo copies."""
    files = Path(files).absolute()
    _no_links(files)
    files = files.resolve(strict=True)
    if not files.is_dir():
        raise ValueError("--files must name an extracted Android files directory")
    output = _output_path(Path(output), files)
    skipped = []
    reviews = _collect(files, skipped)
    by_image = defaultdict(list)
    for review in reviews:
        by_image[review.metadata["journal"]["image_sha256"]].append(review)
    accepted = []
    for image_hash, entries in sorted(by_image.items()):
        # Arrow numbers belong to each group; compare coordinate multisets, not
        # IDs or rounded/rescaled positions. Retain every journal's own IDs.
        signatures = {tuple(sorted((point["x"], point["y"]) for point in entry.points))
                      for entry in entries}
        if len(signatures) != 1:
            for entry in entries:
                _skip(skipped, "conflicting_duplicate_image", entry.group_id, entry.view_id)
            continue
        accepted.append((image_hash, entries))
    manifest = {"coordinate_width": REVIEW_WIDTH, "photos": [], "labels": "labels.json",
                "review_metadata": "review-metadata.json", "split_policy": "unassigned",
                "round_key_policy": "explicit group round_key, otherwise group:<group_id>"}
    labels = {"coordinate_width": REVIEW_WIDTH, "photos": []}
    metadata = {"schema": 1, "coordinate_space": "original-exif-upright-pixels", "photos": []}
    # mkdir and every file open are exclusive, including a race with another export.
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    if accepted:
        (output / "photos").mkdir(mode=0o700)
    for image_hash, entries in accepted:
        first = entries[0]
        photo_id, relative = "sha256-" + image_hash, "photos/" + image_hash + ".jpg"
        with (output / relative).open("xb") as destination:
            destination.write(first.image_bytes)
        review_height = round(first.height * REVIEW_WIDTH / first.width)
        manifest["photos"].append({"id": photo_id, "path": relative, "split": "unassigned",
                                   "image_sha256": image_hash, "width": first.width, "height": first.height,
                                   "group_keys": sorted({entry.group_id for entry in entries}),
                                   "round_keys": sorted({entry.metadata["round_key"] for entry in entries})})
        labels["photos"].append({"id": photo_id, "complete": True, "points": [
            {"x": point["x"] * REVIEW_WIDTH / first.width,
             "y": point["y"] * review_height / first.height, "confidence": "clear"}
            for point in sorted(first.points, key=lambda point: (point["x"], point["y"]))]})
        metadata["photos"].append({"id": photo_id, "image_sha256": image_hash,
                                   "width": first.width, "height": first.height,
                                   "reviews": [entry.metadata for entry in entries]})
    report = {"schema": 1, "exported_photos": len(accepted), "eligible_reviews": len(reviews),
              "exported_reviews": sum(len(entries) for _, entries in accepted),
              "skipped": skipped, "skip_counts": dict(sorted(Counter(row["reason"] for row in skipped).items()))}
    _write_json(output / "labels.json", labels)
    _write_json(output / "review-metadata.json", metadata)
    _write_json(output / "export-report.json", report)
    # Publish the manifest last; interrupted exports must not look ready to use.
    _write_json(output / "manifest.json", manifest)
    return report


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    result.add_argument("--files", required=True, type=Path, help="Extracted Android files directory (read-only)")
    result.add_argument("--output", required=True, type=Path, help="Fresh directory under private/ or build/")
    return result


def main(argv=None) -> int:
    arguments = parser().parse_args(argv)
    try:
        report = export_reviews(arguments.files, arguments.output)
    except (OSError, ValueError) as error:
        # Do not echo filesystem errors that may disclose private source paths.
        reason = str(error) if isinstance(error, ValueError) else type(error).__name__
        print("Export failed: " + reason)
        return 2
    print(json.dumps({key: report[key] for key in ("exported_photos", "exported_reviews", "skip_counts")},
                     sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
