"""Offline impact-center experiments. No photos, labels, or models leave this machine.

Examples (run from the repository with .venv-impact/Scripts/python.exe):
  tools/evaluate_impact.py --cache-only
  tools/evaluate_impact.py --labels private/detection-20261022/consensus.json \
      --thresholds .08 .12 .16 .20 --commit-radius 3 --overlays
  tools/evaluate_impact.py --labels private/detection-20261022/consensus.json \
      --thresholds .12 --commit-radius 3 --freeze baseline-t0.12-r3
  tools/evaluate_impact.py --split holdout --labels private/detection-20261022/consensus.json \
      --locked private/detection-20261022/runs/CHOSEN/locked.json --overlays

Manifest: a list, or {"coordinate_width":600,"photos":[{"id":...,"path":...,
"split":"development"|"holdout"}]}. Relative photo paths are relative to the
manifest directory. Labels: {"coordinate_width":600,"photos":[{"id":...,
"points":[{"x":1,"y":2,"confidence":"clear"|"uncertain"}]}]}. Plain [x,y]
pairs are also clear. An explicit empty points list means a negative photo;
missing labels never do. Use one adjudicated reference, not concatenated annotators.
Optional "ambiguous" points are ignored regions. "complete":false excludes a photo
from scoring. Uncertain labels are neither truths nor false negatives; only
unmatched predictions within 6px of them are ignored. These are ignore regions,
not extra matching targets: multiple unmatched detections may be ignored there.
Exact-photo success is not claimed for ambiguous or incompletely labeled photos.

Baseline ports ImpactVision.java, including Java rounding, float32 accumulation,
stable peak ties, and subpixel refinement. Input is PIL EXIF-upright RGB followed
by OpenCV INTER_AREA, not a resized annotation preview. Native JPEG decoders and
CPU kernels can differ numerically; this does not assert bitwise Android parity.
Commit suppression models a fresh photo, without preexisting manual marks. The
historical app radius is 7.2 review pixels; --commit-radius 3 tests the separate
close-entry fix. Both raw and applied predictions/metrics are always retained.

Thresholds are bounded to the predeclared .08/.12/.16/.20 set. Variants are fixed:
baseline; hflip (600px original/flip mean); scales (450/600/750px mean); shaft
(baseline heatmap, require mean shaft support >=.25 along one of 24 rays for
peaks below the model-card threshold); endpoint (baseline heatmap, weak peaks
need forward 4..40px mean shaft >=.5, reverse 4..16px mean <=half forward,
and >=90% in-image samples in both directions; 24 directions, +/-1px width).
Endpoint leaves peaks at/above the card threshold unchanged. Its fixed rule is
development-cycle two, not a trained classifier or a per-photo adjustment.
Use --require-cache for strictly cached experiments, failing on any cache miss.
No automatic threshold or model tuning.
Only development can compare inference options or write a locked configuration.
Heldout inference changes require --locked, which also runs the original baseline.
Cache-only and unlabeled runs obey the same split guard.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import heapq
import json
import math
import os
from pathlib import Path
import platform
import sys
import tempfile
import time

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageOps, __version__ as PIL_VERSION


ROOT = Path(__file__).resolve().parents[1]
REVIEW_WIDTH = 600
TOLERANCE = 6.0
PIPELINE = "impact-java-offline-v1"
HARNESS_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
THRESHOLDS = (.08, .12, .16, .20)
VARIANTS = ("baseline", "hflip", "scales", "shaft", "endpoint")
DEVELOPMENT = {"development", "dev", "train", "training", "validation", "val"}
ENDPOINT_RULE = {"angles": 24, "forward_start": 4, "forward_stop": 40,
                 "reverse_start": 4, "reverse_stop": 16, "step": 2,
                 "perpendicular_offsets": [-1, 0, 1], "min_forward": .5,
                 "max_reverse_fraction": .5, "min_coverage": .9,
                 "high_confidence_policy": "preserve card-threshold peaks"}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_hash(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def json_bytes(value) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def private_output(path: Path) -> Path:
    path = path.resolve()
    if not any(path.is_relative_to((ROOT / folder).resolve()) for folder in ("private", "build")):
        raise ValueError(f"Personal outputs must be under {ROOT / 'private'} or {ROOT / 'build'}")
    return path


def write_json(path: Path, value) -> None:
    path = private_output(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def review_size(image_size: tuple[int, int], width: int = REVIEW_WIDTH) -> tuple[int, int]:
    w, h = image_size
    if w <= 0 or h <= 0 or width <= 0:
        raise ValueError("Invalid image dimensions")
    if not 60 <= round(h * REVIEW_WIDTH / w) <= 4800:
        raise ValueError("Unsupported photograph aspect ratio")
    return width, round(h * width / w)


def positions(length: int, size: int) -> list[int]:
    """Math.rint is ties-to-even, unlike Math.round or rounding half upward."""
    if size <= 64 or length <= 0:
        raise ValueError("Invalid tile dimensions")
    if length <= size:
        return [0]
    n = math.ceil((length - size) / (size - 64)) + 1
    return [round(i * (length - size) / (n - 1)) for i in range(n)]


def blend_window(size: int) -> np.ndarray:
    ramp = np.array([max(.1, .5 - .5 * math.cos(2 * math.pi * i / (size - 1)))
                     for i in range(size)], dtype=np.float32)
    return ramp[:, None] * ramp[None, :]


def point_array(points, columns: int = 3) -> np.ndarray:
    array = np.asarray(points, dtype=np.float64)
    if array.size == 0:
        return np.empty((0, columns), dtype=np.float64)
    if array.ndim != 2 or array.shape[1] < 2 or not np.isfinite(array).all():
        raise ValueError("Points must be finite x,y rows")
    return array


def map_points(points, source_size: tuple[int, int], target_size: tuple[int, int]) -> np.ndarray:
    """ImpactVision's edge-coordinate mapping, including independently rounded h."""
    result = point_array(points).copy()
    result[:, :2] *= np.array(target_size, dtype=np.float64) / np.array(source_size, dtype=np.float64)
    return result


def offset(a: float, b: float, c: float) -> float:
    left, middle, right = (math.log(max(float(v), 1e-7)) for v in (a, b, c))
    denominator = left - 2 * middle + right
    return max(-.5, min(.5, .5 * (left - right) / denominator)) if denominator < -1e-7 else 0.0


def peaks(heatmap: np.ndarray, threshold: float) -> np.ndarray:
    center = np.ascontiguousarray(heatmap[0] if heatmap.ndim == 3 else heatmap, dtype=np.float32)
    if center.ndim != 2 or not np.isfinite(center).all() or not math.isfinite(threshold):
        raise ValueError("Invalid peak input")
    h, w = center.shape
    maxima = cv2.dilate(center, np.ones((7, 7), dtype=np.uint8))
    # Java compares float probabilities to a double threshold, not a float32 one.
    indices = np.flatnonzero((center >= maxima - np.float32(1e-7)) &
                             (center.astype(np.float64) >= threshold))
    flat = center.ravel()
    indices = indices[np.argsort(-flat[indices], kind="stable")]
    hits = []
    for index in indices:
        y, x = divmod(int(index), w)
        if any((x - old[0]) ** 2 + (y - old[1]) ** 2 <= 9 for old in hits):
            continue
        px, py = float(x), float(y)
        if 0 < x < w - 1 and 0 < y < h - 1:
            px += offset(center[y, x - 1], center[y, x], center[y, x + 1])
            py += offset(center[y - 1, x], center[y, x], center[y + 1, x])
        hits.append((px, py, float(center[y, x])))
        if len(hits) == 64:
            break
    return point_array(hits)


def commit_suppression(points, image_size: tuple[int, int], radius: float = 7.2) -> tuple[np.ndarray, list[int]]:
    """Sequential app suppression, strictly < radius, measured in original pixels."""
    if not math.isfinite(radius) or radius < 0:
        raise ValueError("Commit radius must be finite and nonnegative")
    points = point_array(points)
    original = map_points(points, review_size(image_size), image_size)
    kept = []
    for i, point in enumerate(original):
        if not (0 <= point[0] < image_size[0] and 0 <= point[1] < image_size[1]):
            continue
        if any(math.hypot(*(point[:2] - original[j, :2])) < radius * image_size[0] / REVIEW_WIDTH
               for j in kept):
            continue
        kept.append(i)
    return points[kept].copy(), kept


def validate_card(card: dict, model_hash: str) -> None:
    required = {"schema": 2, "status": "trained_experimental", "onnx_file": "impact-center.onnx",
                "review_width": 600, "tile_overlap": 64, "peak_radius": 3,
                "channels": ["impact_center", "shaft"]}
    if (any(card.get(k) != v for k, v in required.items()) or
            card.get("architecture") not in ("redwood-mobilenetv3-impact-shaft-v2", "redwood-mobilenetv3-impact-shaft-v2-mean2") or
            card.get("input_size") not in (256, 320, 384, 512) or
            not 0 < card.get("operating_threshold", 0) < 1):
        raise ValueError("Unsupported impact model or operating point")
    if model_hash != card.get("onnx_sha256"):
        raise ValueError("Impact model checksum mismatch")


def tiled_inference(bgr: np.ndarray, net, size: int, width: int = 600, flipped: bool = False) -> tuple[np.ndarray, int]:
    w, h = review_size((bgr.shape[1], bgr.shape[0]), width)
    small = cv2.resize(bgr, (w, h), interpolation=cv2.INTER_AREA)
    if flipped:
        small = np.ascontiguousarray(small[:, ::-1])
    cw, ch = max(w, size), max(h, size)
    canvas = np.full((ch, cw, 3), 127, dtype=np.uint8)
    canvas[:h, :w] = small
    total = np.zeros((2, ch, cw), dtype=np.float32)
    weights = np.zeros((ch, cw), dtype=np.float32)
    window = blend_window(size)
    tiles = 0
    for top in positions(ch, size):
        for left in positions(cw, size):
            tile = canvas[top:top + size, left:left + size]
            blob = cv2.dnn.blobFromImage(tile, 1 / 255.0, (size, size), (0, 0, 0), True, False)
            net.setInput(blob)
            output = net.forward()
            if output.shape != (1, 2, size, size) or output.dtype != np.float32:
                raise ValueError(f"Unexpected impact network output: {output.shape}, {output.dtype}")
            if not np.isfinite(output).all() or float(output.min()) < -.001 or float(output.max()) > 1.001:
                raise ValueError("Invalid impact probability")
            weights[top:top + size, left:left + size] += window
            total[:, top:top + size, left:left + size] += output[0] * window
            tiles += 1
    heat = total[:, :h, :w] / np.maximum(weights[:h, :w], np.float32(1e-8))
    if flipped:
        heat = heat[:, :, ::-1]
    return np.ascontiguousarray(heat), tiles


def runtime_environment() -> dict:
    return {"python": platform.python_version(), "opencv": cv2.__version__, "numpy": np.__version__,
            "pillow": PIL_VERSION, "platform": platform.platform(), "machine": platform.machine(),
            "opencv_build_sha256": sha256(cv2.getBuildInformation().encode()),
            "threads": 2, "backend": "DNN_BACKEND_OPENCV", "target": "DNN_TARGET_CPU", "opencl": False}


class HeatmapCache:
    def __init__(self, model_path: Path, card_path: Path, cache_dir: Path, refresh: bool = False):
        cv2.setNumThreads(2)
        cv2.setRNGSeed(0)
        cv2.ocl.setUseOpenCL(False)
        self.model_bytes = model_path.read_bytes()
        self.model_hash = sha256(self.model_bytes)
        self.card = read_json(card_path)
        self.card_hash = file_hash(card_path)
        validate_card(self.card, self.model_hash)
        self.environment = runtime_environment()
        self.cache_dir = private_output(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.refresh = refresh
        self.require_cache = False
        self.net = None
        self.model_load_seconds = 0.0

    def get(self, bgr: np.ndarray, photo_hash: str, width: int, flipped: bool) -> tuple[np.ndarray, dict]:
        started = time.perf_counter()
        descriptor = {"pipeline": PIPELINE, "model_sha256": self.model_hash, "card_sha256": self.card_hash,
                      "photo_sha256": photo_hash, "upright_bgr_sha256": sha256(bgr.tobytes()),
                      "image_shape": list(bgr.shape), "width": width, "flipped": flipped,
                      "environment": self.environment}
        key = sha256(json_bytes(descriptor))
        path = self.cache_dir / (key + ".npz")
        w, h = review_size((bgr.shape[1], bgr.shape[0]), width)
        hit = path.exists() and not self.refresh
        if hit:
            with np.load(path, allow_pickle=False) as saved:
                heat = saved["heatmap"]
                metadata = json.loads(str(saved["metadata"].item()))
            if (metadata.get("descriptor") != descriptor or heat.shape != (2, h, w) or
                    heat.dtype != np.float32 or not np.isfinite(heat).all() or
                    metadata.get("heat_sha256") != sha256(heat.tobytes())):
                raise ValueError(f"Invalid heatmap cache {path}; use --refresh-cache")
        else:
            if self.require_cache:
                raise ValueError(f"Required heatmap cache missing: {path}; inference is disabled")
            if self.net is None:
                loading = time.perf_counter()
                self.net = cv2.dnn.readNetFromONNX(np.frombuffer(self.model_bytes, dtype=np.uint8))
                if self.net.empty():
                    raise ValueError("Impact model could not be loaded")
                self.net.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
                self.net.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
                self.model_load_seconds = time.perf_counter() - loading
            computing = time.perf_counter()
            heat, tiles = tiled_inference(bgr, self.net, self.card["input_size"], width, flipped)
            metadata = {"descriptor": descriptor, "compute_seconds": time.perf_counter() - computing,
                        "tiles": tiles, "heat_sha256": sha256(heat.tobytes())}
            with tempfile.NamedTemporaryFile(dir=self.cache_dir, suffix=".npz", delete=False) as temporary:
                temporary_path = Path(temporary.name)
                np.savez_compressed(temporary, heatmap=heat, metadata=np.array(json_bytes(metadata).decode()))
            try:
                os.replace(temporary_path, path)
            finally:
                temporary_path.unlink(missing_ok=True)
        return heat, {"key": key, "width": width, "flipped": flipped, "cache_hit": hit,
                      "access_seconds": time.perf_counter() - started,
                      "recorded_compute_seconds": metadata["compute_seconds"], "tiles": metadata["tiles"]}


@dataclass(frozen=True)
class Candidate:
    variant: str
    threshold: float
    commit_radius: float

    @property
    def name(self) -> str:
        return f"{self.variant}-t{self.threshold:g}-r{self.commit_radius:g}"

    def inputs(self) -> list[tuple[int, bool]]:
        if self.variant == "hflip":
            return [(600, False), (600, True)]
        if self.variant == "scales":
            return [(450, False), (600, False), (750, False)]
        return [(600, False)]


def ensemble_heatmaps(heatmaps: list[np.ndarray], target_size: tuple[int, int]) -> np.ndarray:
    mapped = []
    for heat in heatmaps:
        if (heat.shape[2], heat.shape[1]) != target_size:
            heat = np.stack([cv2.resize(channel, target_size, interpolation=cv2.INTER_LINEAR) for channel in heat])
        mapped.append(heat)
    if len(mapped) == 1:
        return mapped[0]
    total = mapped[0].copy()
    for heat in mapped[1:]:
        total += heat
    return total / np.float32(len(mapped))


def shaft_support(shaft: np.ndarray, x: float, y: float) -> float:
    angles = np.arange(24, dtype=np.float64) * (2 * math.pi / 24)
    distances = np.arange(4, 25, 2, dtype=np.float64)
    sides = np.array([-1., 0., 1.])
    dx, dy = np.cos(angles)[:, None, None], np.sin(angles)[:, None, None]
    xs = x + dx * distances[None, :, None] - dy * sides
    ys = y + dy * distances[None, :, None] + dx * sides
    sampled = cv2.remap(shaft, xs.reshape(24, -1).astype(np.float32),
                        ys.reshape(24, -1).astype(np.float32), cv2.INTER_LINEAR,
                        borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    return float(sampled.mean(axis=1).max())


def endpoint_profile(shaft: np.ndarray, x: float, y: float) -> dict:
    """Profile one-sided long support against short reverse support on the same ray."""
    rule = ENDPOINT_RULE
    angles = np.arange(rule["angles"], dtype=np.float64) * (2 * math.pi / rule["angles"])
    dx, dy = np.cos(angles)[:, None, None], np.sin(angles)[:, None, None]
    sides = np.asarray(rule["perpendicular_offsets"], dtype=np.float64)

    def sample(start, stop, sign):
        distances = sign * np.arange(start, stop + 1, rule["step"], dtype=np.float64)
        xs = x + dx * distances[None, :, None] - dy * sides
        ys = y + dy * distances[None, :, None] + dx * sides
        valid = (xs >= 0) & (ys >= 0) & (xs <= shaft.shape[1] - 1) & (ys <= shaft.shape[0] - 1)
        sampled = cv2.remap(shaft, xs.reshape(rule["angles"], -1).astype(np.float32),
                            ys.reshape(rule["angles"], -1).astype(np.float32), cv2.INTER_LINEAR,
                            borderMode=cv2.BORDER_CONSTANT, borderValue=0)
        return sampled.mean(axis=1), valid.mean(axis=(1, 2))

    forward, forward_coverage = sample(rule["forward_start"], rule["forward_stop"], 1)
    reverse, reverse_coverage = sample(rule["reverse_start"], rule["reverse_stop"], -1)
    best = int(np.argmax(forward))
    f, r = float(forward[best]), float(reverse[best])
    supported = (f >= rule["min_forward"] and r <= rule["max_reverse_fraction"] * f and
                 forward_coverage[best] >= rule["min_coverage"] and reverse_coverage[best] >= rule["min_coverage"])
    return {"best_angle_degrees": best * 360 / rule["angles"], "forward": f, "reverse": r,
            "forward_coverage": float(forward_coverage[best]), "reverse_coverage": float(reverse_coverage[best]),
            "endpoint_consistent": bool(supported), "forward_by_angle": forward.tolist(),
            "reverse_by_angle": reverse.tolist()}


def endpoint_filter(points, shaft: np.ndarray, operating_threshold: float) -> tuple[np.ndarray, list[dict]]:
    points = point_array(points)
    kept, diagnostics = [], []
    for index, (x, y, confidence) in enumerate(points):
        profile = endpoint_profile(shaft, x, y)
        preserve = confidence >= operating_threshold
        accepted = preserve or profile["endpoint_consistent"]
        diagnostics.append({"unfiltered_index": index, "point": [x, y, confidence],
                            "high_confidence_preserved": bool(preserve), "accepted": bool(accepted), **profile})
        if accepted:
            kept.append(index)
    return points[kept].copy(), diagnostics


def maximum_matching(predictions, truths, tolerance: float = TOLERANCE) -> list[dict]:
    """Maximum cardinality first, then minimum total Euclidean error.

    Unit-capacity min-cost flow uses shortest augmenting paths with potentials.
    Unlike greedy nearest-pair matching it can reassign earlier matches. Stable
    graph/node order breaks exact ties reproducibly without perturbing distances.
    """
    pred, truth = point_array(predictions), point_array(truths, 2)
    if not math.isfinite(tolerance) or tolerance < 0:
        raise ValueError("Matching tolerance must be finite and nonnegative")
    n, m = len(pred), len(truth)
    if not n or not m:
        return []
    source, sink = n + m, n + m + 1
    graph = [[] for _ in range(sink + 1)]

    def edge(a, b, cost):
        forward = [b, len(graph[b]), 1, cost]
        graph[a].append(forward)
        graph[b].append([a, len(graph[a]) - 1, 0, -cost])
        return forward

    for i in range(n):
        edge(source, i, 0.0)
    for j in range(m):
        edge(n + j, sink, 0.0)
    pairs = []
    for i in range(n):
        for j in range(m):
            distance = math.hypot(*(pred[i, :2] - truth[j, :2]))
            if distance <= tolerance:
                pairs.append((i, j, distance, edge(i, n + j, distance)))
    potential = [0.] * len(graph)
    while True:
        distances = [math.inf] * len(graph)
        previous = [None] * len(graph)
        distances[source] = 0.
        queue = [(0., source)]
        while queue:
            distance, node = heapq.heappop(queue)
            if distance > distances[node]:
                continue
            for index, (target, _, capacity, cost) in enumerate(graph[node]):
                if not capacity:
                    continue
                reduced = max(0., cost + potential[node] - potential[target])
                proposed = distance + reduced
                if proposed < distances[target]:
                    distances[target] = proposed
                    previous[target] = (node, index)
                    heapq.heappush(queue, (proposed, target))
        if previous[sink] is None:
            break
        for node, distance in enumerate(distances):
            if math.isfinite(distance):
                potential[node] += distance
        node = sink
        while node != source:
            parent, index = previous[node]
            forward = graph[parent][index]
            forward[2] = 0
            graph[node][forward[1]][2] = 1
            node = parent
    return [{"prediction": i, "truth": j, "distance_px": d} for i, j, d, e in pairs if e[2] == 0]


def metric_values(tp: int, fp: int, fn: int, errors: list[float]) -> dict:
    return {"tp": tp, "fp": fp, "fn": fn,
            "precision": tp / (tp + fp) if tp + fp else None,
            "recall": tp / (tp + fn) if tp + fn else None,
            "f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else None,
            "mean_error_px": float(np.mean(errors)) if errors else None,
            "median_error_px": float(np.median(errors)) if errors else None,
            "p95_error_px": float(np.percentile(errors, 95)) if errors else None}


def score_photo(predictions, labels: dict | None) -> dict | None:
    if labels is None or not labels["complete"]:
        return None
    predictions = point_array(predictions)
    matches = maximum_matching(predictions, labels["clear"])
    matched = {match["prediction"] for match in matches}
    ignored = [i for i, point in enumerate(predictions) if i not in matched and
               any(math.hypot(*(point[:2] - np.asarray(p))) <= TOLERANCE for p in labels["uncertain"])]
    fp = [i for i in range(len(predictions)) if i not in matched and i not in ignored]
    matched_truths = {match["truth"] for match in matches}
    fn = [i for i in range(len(labels["clear"])) if i not in matched_truths]
    errors = [match["distance_px"] for match in matches]
    return {**metric_values(len(matches), len(fp), len(fn), errors), "matches": matches,
            "fp_indices": fp, "fn_indices": fn, "ignored_indices": ignored, "ignored": len(ignored),
            "exact_photo": not fp and not fn if not labels["uncertain"] else None}


def aggregate_scores(records: list[dict], field: str) -> dict:
    scored = [record[field] for record in records if record[field] is not None]
    tp, fp, fn = (sum(score[key] for score in scored) for key in ("tp", "fp", "fn"))
    errors = [match["distance_px"] for score in scored for match in score["matches"]]
    eligible = [score["exact_photo"] for score in scored if score["exact_photo"] is not None]
    return {**metric_values(tp, fp, fn, errors), "photos": len(records), "scored_photos": len(scored),
            "unscored_photos": len(records) - len(scored), "ignored": sum(s["ignored"] for s in scored),
            "exact_photos": sum(eligible), "exact_eligible_photos": len(eligible),
            "exact_photo_rate": sum(eligible) / len(eligible) if eligible else None}


def load_manifest(path: Path) -> tuple[list[dict], dict]:
    document = read_json(path)
    metadata = document if isinstance(document, dict) else {}
    if metadata.get("coordinate_width", 600) != 600:
        raise ValueError("Manifest coordinates must have width 600")
    rows = metadata.get("photos") if isinstance(document, dict) else document
    if not isinstance(rows, list) or not rows:
        raise ValueError("Manifest must contain a nonempty photos list")
    photos, ids, paths = [], set(), {}
    for row in rows:
        photo_id, split = row["id"], row["split"]
        if not isinstance(photo_id, str) or not photo_id or photo_id in ids or not isinstance(split, str):
            raise ValueError("Manifest photo IDs must be unique nonempty strings, with a split")
        source = Path(row["path"])
        source = source.resolve() if source.is_absolute() else (path.parent / source).resolve()
        if source in paths and paths[source] != split:
            raise ValueError("The same photo path crosses development/holdout splits")
        ids.add(photo_id)
        paths[source] = split
        photos.append({"id": photo_id, "split": split, "path": source})
    return photos, {key: value for key, value in metadata.items() if key != "photos"}


def load_labels(path: Path) -> dict[str, dict]:
    document = read_json(path)
    if isinstance(document, dict):
        if document.get("coordinate_width", document.get("review_width", 600)) != 600:
            raise ValueError("Labels must use 600px-wide EXIF-upright coordinates")
        rows = document["photos"]
    else:
        rows = document
    result = {}
    for row in rows:
        photo_id = row["id"]
        if photo_id in result:
            raise ValueError(f"Duplicate labels for {photo_id}; adjudicate annotators first")
        if "points" not in row:
            raise ValueError(f"Missing explicit points list for {photo_id}")
        labels = {"clear": [], "uncertain": [], "complete": row.get("complete", True)}
        if not isinstance(labels["complete"], bool):
            raise ValueError("Label completeness must be boolean")
        for point, force_uncertain in [(p, False) for p in row["points"]] + [(p, True) for p in row.get("ambiguous", [])]:
            if isinstance(point, dict):
                xy = [float(point["x"]), float(point["y"])]
                status = point.get("status", point.get("confidence", "clear"))
                if point.get("ambiguous", False):
                    force_uncertain = True
                if status not in ("clear", "certain", "confirmed", "uncertain", "ambiguous"):
                    raise ValueError(f"Unrecognized label confidence/status {status!r} in {photo_id}")
                force_uncertain |= status in ("uncertain", "ambiguous")
            else:
                if len(point) != 2:
                    raise ValueError("Plain label points must be [x,y]")
                xy = [float(point[0]), float(point[1])]
            if not all(math.isfinite(value) for value in xy) or not 0 <= xy[0] < 600 or xy[1] < 0:
                raise ValueError(f"Invalid review coordinate in {photo_id}: {xy}")
            labels["uncertain" if force_uncertain else "clear"].append(xy)
        result[photo_id] = labels
    return result


def overlay(image: Image.Image, raw: np.ndarray, applied: np.ndarray, kept: list[int],
            labels: dict | None, score: dict | None, title: str, path: Path) -> None:
    w, h = review_size(image.size)
    base = Image.fromarray(cv2.resize(np.asarray(image), (w, h), interpolation=cv2.INTER_AREA))
    canvas = Image.new("RGB", (w, h + 56), "white")
    canvas.paste(base, (0, 56))
    draw = ImageDraw.Draw(canvas)
    draw.text((8, 5), title, fill="black")
    draw.text((8, 23), "Green: truth  Cyan: TP  Red: FP  Amber: uncertain/ignored", fill="black")
    draw.text((8, 39), "Gray X: suppressed  Blue: unscored prediction  Analytical only", fill="black")
    if labels:
        for x, y in labels["clear"]:
            draw.ellipse((x - 6, y + 50, x + 6, y + 62), outline="#14a65a", width=2)
        for x, y in labels["uncertain"]:
            draw.rectangle((x - 6, y + 50, x + 6, y + 62), outline="#ffb000", width=2)
    for i, (x, y, _) in enumerate(raw):
        if i not in kept:
            draw.line((x - 3, y + 53, x + 3, y + 59), fill="#a0a0a0", width=2)
            draw.line((x - 3, y + 59, x + 3, y + 53), fill="#a0a0a0", width=2)
    for i, (x, y, _) in enumerate(applied):
        color = "#1677ff" if score is None else "#00d9e6"
        if score and i in score["fp_indices"]:
            color = "#ff3344"
        if score and i in score["ignored_indices"]:
            color = "#ffb000"
        draw.line((x - 3, y + 56, x + 3, y + 56), fill=color, width=2)
        draw.line((x, y + 53, x, y + 59), fill=color, width=2)
    private_output(path).parent.mkdir(parents=True, exist_ok=True)
    canvas.save(path)


def candidates_for(args, cache: HeatmapCache, manifest_hash: str, protocol_hash: str | None) -> tuple[list[Candidate], dict | None]:
    baseline = Candidate("baseline", cache.card["operating_threshold"], 7.2)
    if args.locked:
        if args.variants != ["baseline"] or args.thresholds is not None or args.commit_radius != 7.2 or args.freeze:
            raise ValueError("--locked cannot be combined with inference/commit overrides or --freeze")
        locked = read_json(args.locked)
        if (locked.get("pipeline") != PIPELINE or locked.get("model_sha256") != cache.model_hash or
                locked.get("card_sha256") != cache.card_hash or locked.get("manifest_sha256") != manifest_hash or
                locked.get("protocol_sha256") != protocol_hash or locked.get("fit_split") not in DEVELOPMENT or
                locked.get("harness_sha256") != HARNESS_SHA256):
            raise ValueError("Locked configuration does not match this development protocol, manifest, model, or harness")
        chosen = Candidate(**locked["candidate"])
        if chosen.variant not in VARIANTS or chosen.threshold not in THRESHOLDS or not 0 <= chosen.commit_radius <= 7.2:
            raise ValueError("Invalid locked candidate")
        return list(dict.fromkeys([baseline, chosen])), locked
    thresholds = args.thresholds if args.thresholds is not None else [baseline.threshold]
    candidates = list(dict.fromkeys(Candidate(v, t, args.commit_radius) for v in args.variants for t in thresholds))
    if args.split not in DEVELOPMENT:
        if args.freeze or any(c.variant != "baseline" or c.threshold != baseline.threshold for c in candidates):
            raise ValueError("Holdout changes require --locked from development; no heldout tuning is permitted")
        if args.commit_radius not in (3., 7.2):
            raise ValueError("Unfrozen holdout permits only the original 7.2px or predeclared 3px commit fix")
    if args.freeze and (args.cache_only or not args.labels or args.freeze not in {c.name for c in candidates}):
        raise ValueError("--freeze requires a scored development candidate name from this run")
    return candidates, None


def parser() -> argparse.ArgumentParser:
    cli = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    cli.add_argument("--manifest", type=Path, default=ROOT / "private/detection-20261022/manifest.json")
    cli.add_argument("--labels", type=Path, help="One adjudicated reference JSON; omit for predictions/cache only")
    cli.add_argument("--split", default="development", help="Exact manifest split; default development")
    cli.add_argument("--variants", nargs="+", choices=VARIANTS, default=["baseline"])
    cli.add_argument("--thresholds", nargs="+", type=float, choices=THRESHOLDS, help="Default model-card operating point")
    cli.add_argument("--commit-radius", type=float, default=7.2, help="Review-pixel app suppression radius (default 7.2; fix 3)")
    cli.add_argument("--model", type=Path, default=ROOT / "assets/impact/impact-center.onnx")
    cli.add_argument("--card", type=Path, default=ROOT / "assets/impact/model-card.json")
    cli.add_argument("--cache-dir", type=Path, default=ROOT / "private/detection-20261022/heatmaps")
    cli.add_argument("--output-dir", type=Path, help="New run directory under private/ or build/; default timestamped")
    cli.add_argument("--cache-only", action="store_true", help="Cache requested inputs without extracting/scoring peaks")
    cli.add_argument("--refresh-cache", action="store_true", help="Recompute rather than reuse disk caches")
    cli.add_argument("--require-cache", action="store_true", help="Fail on a cache miss; never run ONNX inference")
    cli.add_argument("--overlays", action="store_true", help="Save PIL review overlays; never edits source photographs")
    cli.add_argument("--freeze", metavar="CANDIDATE", help="Write locked.json for this scored development candidate")
    cli.add_argument("--locked", type=Path, help="Use one previously locked candidate plus the historical baseline")
    cli.add_argument("--protocol", type=Path, default=ROOT / "private/detection-20261022/protocol.json")
    return cli


def run(args) -> Path:
    started = time.perf_counter()
    if not math.isfinite(args.commit_radius) or not 0 <= args.commit_radius <= 7.2:
        raise ValueError("Commit radius must be between 0 and 7.2 review pixels")
    all_photos, manifest_metadata = load_manifest(args.manifest)
    photos = [photo for photo in all_photos if photo["split"] == args.split]
    if not photos:
        raise ValueError(f"No photos with split {args.split!r}")
    manifest_hash = file_hash(args.manifest)
    protocol_hash = file_hash(args.protocol) if args.protocol.is_file() else None
    cache = HeatmapCache(args.model, args.card, args.cache_dir, args.refresh_cache)
    if args.require_cache and args.refresh_cache:
        raise ValueError("--require-cache cannot be combined with --refresh-cache")
    cache.require_cache = args.require_cache
    candidates, locked = candidates_for(args, cache, manifest_hash, protocol_hash)
    labels = load_labels(args.labels) if args.labels and not args.cache_only else {}
    if args.labels and not args.cache_only and any(photo["id"] not in labels for photo in photos):
        raise ValueError("Reference is missing selected photos; omit --labels until annotations are complete")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S-%fZ")
    output = private_output(args.output_dir or ROOT / "private/detection-20261022/runs" / stamp)
    if (output / "report.json").exists() or (output / "locked.json").exists():
        raise ValueError("Output already contains a report/lock; choose a new run directory")
    output.mkdir(parents=True, exist_ok=True)
    records = {candidate.name: [] for candidate in candidates}
    inventory = []
    for photo_index, photo in enumerate(photos):
        decoding = time.perf_counter()
        photo_hash = file_hash(photo["path"])
        if locked and args.split not in DEVELOPMENT and photo_hash in locked["fit_photo_sha256"]:
            raise ValueError("Heldout photo content overlaps the locked development photos")
        with Image.open(photo["path"]) as source:
            image = ImageOps.exif_transpose(source).convert("RGB")
        bgr = cv2.cvtColor(np.asarray(image), cv2.COLOR_RGB2BGR)
        shape = review_size(image.size)
        label = labels.get(photo["id"])
        if label and any(p[1] >= shape[1] for kind in ("clear", "uncertain") for p in label[kind]):
            raise ValueError(f"Label outside EXIF-upright review dimensions for {photo['id']}")
        decode_seconds = time.perf_counter() - decoding
        heatmaps, accesses = {}, {}
        for candidate in candidates:
            for key in candidate.inputs():
                if key not in heatmaps:
                    print(f"[{photo_index + 1}/{len(photos)}] {photo['id']} {key[0]}px flip={key[1]}", flush=True)
                    heatmaps[key], accesses[key] = cache.get(bgr, photo_hash, *key)
        inventory.append({"id": photo["id"], "split": photo["split"], "path": str(photo["path"]),
                          "sha256": photo_hash, "upright_size": list(image.size), "review_size": list(shape),
                          "decode_seconds": decode_seconds, "heatmaps": list(accesses.values())})
        if args.cache_only:
            image.close()
            continue
        for candidate in candidates:
            processing = time.perf_counter()
            heat = ensemble_heatmaps([heatmaps[key] for key in candidate.inputs()], shape)
            raw = peaks(heat, candidate.threshold)
            endpoint_diagnostics = None
            if candidate.variant == "endpoint":
                raw, endpoint_diagnostics = endpoint_filter(raw, heat[1], cache.card["operating_threshold"])
            supports = [shaft_support(heat[1], p[0], p[1]) for p in raw] if candidate.variant == "shaft" else []
            if supports:
                raw = raw[[p[2] >= cache.card["operating_threshold"] or support >= .25
                           for p, support in zip(raw, supports)]]
            applied, kept = commit_suppression(raw, image.size, candidate.commit_radius)
            post_seconds = time.perf_counter() - processing
            record = {"id": photo["id"], "raw_points": raw.tolist(), "applied_points": applied.tolist(),
                      "original_applied_points": map_points(applied, shape, image.size).tolist(),
                      "raw_count": len(raw), "applied_count": len(applied), "suppressed_count": len(raw) - len(applied),
                      "kept_raw_indices": kept, "label_counts": None if label is None else
                      {"clear": len(label["clear"]), "uncertain": len(label["uncertain"]), "complete": label["complete"]},
                      "raw_metrics": score_photo(raw, label), "applied_metrics": score_photo(applied, label),
                      "endpoint_diagnostics": endpoint_diagnostics,
                      "runtime": {"postprocess_seconds": post_seconds,
                                  "recorded_inference_seconds": sum(accesses[k]["recorded_compute_seconds"] for k in candidate.inputs()),
                                  "inference_cache_keys": [accesses[k]["key"] for k in candidate.inputs()]}}
            records[candidate.name].append(record)
            if args.overlays:
                filename = f"{photo_index:03d}-{sha256(photo['id'].encode())[:12]}.png"
                overlay(image, raw, applied, kept, label, record["applied_metrics"],
                        f"{photo['id']}  {candidate.name}  raw={len(raw)} applied={len(applied)}",
                        output / "overlays" / candidate.name / filename)
        image.close()
    summaries = {}
    for candidate in ([] if args.cache_only else candidates):
        rows = records[candidate.name]
        summaries[candidate.name] = {"candidate": asdict(candidate), "raw": aggregate_scores(rows, "raw_metrics"),
                                    "applied": aggregate_scores(rows, "applied_metrics"),
                                    "raw_count": sum(r["raw_count"] for r in rows),
                                    "applied_count": sum(r["applied_count"] for r in rows),
                                    "recorded_inference_seconds": sum(r["runtime"]["recorded_inference_seconds"] for r in rows),
                                    "postprocess_seconds": sum(r["runtime"]["postprocess_seconds"] for r in rows)}
    report = {"schema": 1, "pipeline": PIPELINE, "created_at_utc": stamp, "split": args.split,
              "manifest_sha256": manifest_hash, "manifest_metadata": manifest_metadata,
              "protocol_sha256": protocol_hash, "labels_sha256": file_hash(args.labels) if args.labels and not args.cache_only else None,
              "model_sha256": cache.model_hash, "card_sha256": cache.card_hash,
              "harness_sha256": HARNESS_SHA256, "environment": cache.environment,
              "coordinate_width": 600, "matching_tolerance_px": 6, "cache_only": args.cache_only,
              "require_cache": args.require_cache,
              "endpoint_rule": ENDPOINT_RULE if any(c.variant == "endpoint" for c in candidates) else None,
              "locked_sha256": file_hash(args.locked) if args.locked else None,
              "photos": inventory, "summary": summaries, "results": records,
              "runtime": {"wall_seconds": time.perf_counter() - started,
                          "model_load_seconds": cache.model_load_seconds,
                          "cache_hits": sum(h["cache_hit"] for p in inventory for h in p["heatmaps"]),
                          "computed_heatmaps": sum(not h["cache_hit"] for p in inventory for h in p["heatmaps"])},
              "notes": ["Matching is maximum-cardinality, minimum-distance, within 6 review pixels.",
                        "No truths or FN from uncertainty; ignored predictions cannot reward TP.",
                        "Undefined ratios/localization are null, not artificially perfect scores.",
                        "Recorded inference timings come from original cache fills, not cache-read speed.",
                        "Variants share heatmaps; report wall time is not a per-candidate benchmark.",
                        "Desktop CPU timing is not phone latency. No bitwise native parity claim.",
                        "Commit simulation assumes a fresh photo with no existing/manual observations."]}
    write_json(output / "report.json", report)
    if args.freeze:
        chosen = next(candidate for candidate in candidates if candidate.name == args.freeze)
        if summaries[chosen.name]["applied"]["scored_photos"] != len(photos):
            raise ValueError("Cannot lock on missing/incomplete development labels")
        write_json(output / "locked.json", {"pipeline": PIPELINE, "fit_split": args.split,
                                            "candidate": asdict(chosen), "model_sha256": cache.model_hash,
                                            "card_sha256": cache.card_hash, "manifest_sha256": manifest_hash,
                                            "protocol_sha256": protocol_hash, "created_at_utc": stamp,
                                            "harness_sha256": HARNESS_SHA256,
                                            "fit_photo_sha256": [p["sha256"] for p in inventory],
                                            "development_report_sha256": file_hash(output / "report.json"),
                                            "labels_sha256": report["labels_sha256"]})
    for name, summary in summaries.items():
        score = summary["applied"]
        print(f"{name}: raw={summary['raw_count']} applied={summary['applied_count']} "
              f"scored={score['scored_photos']} TP={score['tp']} FP={score['fp']} FN={score['fn']} "
              f"precision={score['precision']} recall={score['recall']} F1={score['f1']}", flush=True)
    print(f"Report: {output / 'report.json'}", flush=True)
    if args.freeze:
        print(f"Locked: {output / 'locked.json'}", flush=True)
    return output / "report.json"


def main() -> int:
    cli = parser()
    try:
        run(cli.parse_args())
    except (ValueError, KeyError, TypeError, OSError, cv2.error) as error:
        cli.exit(2, f"error: {error}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
