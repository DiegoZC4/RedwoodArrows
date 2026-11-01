"""Numerical parity and synthetic training checks, not a detection accuracy test.

Optional real-photo checks read development photos only, without annotations.
Heldout images are not opened. Synthetic optimization artifacts must never be
deployed: their deliberately artificial target is only a gradient-flow test.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import time

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps
import torch

import evaluate_impact as evaluation
from recover_impact import (INPUT_SHAPE, MODEL_PATH, MODEL_SHA256, ROOT, RecoveredImpact,
                            TorchNetAdapter, file_hash, local_output)


PROBABILITY_ATOL = 1e-4
PEAK_ATOL_PIXELS = .01


def configure_runtimes() -> None:
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    torch.manual_seed(4729)
    cv2.setNumThreads(2)
    cv2.ocl.setUseOpenCL(False)


class OrtNetAdapter:
    def __init__(self, path: Path):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        self.blob = None

    def setInput(self, blob: np.ndarray) -> None:
        self.blob = np.ascontiguousarray(blob, dtype=np.float32)

    def forward(self) -> np.ndarray:
        return self.session.run(["probability"], {"rgb": self.blob})[0]


def opencv_net(path: Path):
    net = cv2.dnn.readNetFromONNX(str(path))
    net.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
    net.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
    return net


def differences(reference: np.ndarray, actual: np.ndarray) -> dict:
    if reference.shape != actual.shape or not np.isfinite(reference).all() or not np.isfinite(actual).all():
        return {"passed": False, "reason": "shape or finite-value mismatch"}
    error = np.abs(reference.astype(np.float64) - actual.astype(np.float64))
    return {"max_abs": float(error.max()), "mean_abs": float(error.mean()),
            "passed": bool(float(error.max()) <= PROBABILITY_ATOL)}


def compare_peaks(reference: np.ndarray, actual: np.ndarray) -> dict:
    if len(reference) != len(actual):
        return {"passed": False, "reference_count": len(reference), "actual_count": len(actual)}
    if not len(reference):
        return {"passed": True, "count": 0, "max_coordinate_error_px": 0.0}
    distances = np.linalg.norm(reference[:, None, :2] - actual[None, :, :2], axis=2)
    nearest = np.argmin(distances, axis=1)
    maximum = float(distances[np.arange(len(reference)), nearest].max())
    return {"passed": bool(len(set(nearest.tolist())) == len(reference) and maximum <= PEAK_ATOL_PIXELS),
            "count": len(reference), "max_coordinate_error_px": maximum}


def synthetic_cases():
    for name, value in (("black", 0.0), ("white", 1.0), ("padding_gray", 127 / 255)):
        yield name, np.full(INPUT_SHAPE, value, dtype=np.float32)
    yield "seeded_noise", np.random.default_rng(4729).random(INPUT_SHAPE, dtype=np.float32)
    yy, xx = np.mgrid[:384, :384]
    pattern = ((xx // 13 + yy // 17) % 2).astype(np.float32)
    yield "edges_and_ramp", np.stack((pattern, 1 - pattern, xx.astype(np.float32) / 383))[None]


def verify_tiles(model: RecoveredImpact, model_path: Path) -> list[dict]:
    native, reference = opencv_net(model_path), OrtNetAdapter(model_path)
    records = []
    for name, blob in synthetic_cases():
        native.setInput(blob)
        reference.setInput(blob)
        cv = native.forward()
        onnx = reference.forward()
        with torch.no_grad():
            recovered = model(torch.from_numpy(blob)).numpy()
        record = {"name": name, "torch_vs_onnxruntime": differences(onnx, recovered),
                  "torch_vs_opencv": differences(cv, recovered)}
        record["passed"] = all(record[key]["passed"] for key in ("torch_vs_onnxruntime", "torch_vs_opencv"))
        records.append(record)
        print(json.dumps({"synthetic_parity": record}), flush=True)
    return records


def verify_photos(model: RecoveredImpact, model_path: Path, manifest: Path) -> list[dict]:
    photos, _ = evaluation.load_manifest(manifest)
    selected = [row for row in photos if row["split"] == "development"]
    if not selected:
        raise ValueError("Manifest has no explicitly development-only photos")
    native, reference = opencv_net(model_path), OrtNetAdapter(model_path)
    recovered = TorchNetAdapter(model)
    records = []
    for row in selected:
        with Image.open(row["path"]) as image:
            rgb = np.array(ImageOps.exif_transpose(image).convert("RGB"))
        bgr = np.ascontiguousarray(rgb[:, :, ::-1])
        cv, tiles = evaluation.tiled_inference(bgr, native, 384)
        onnx, _ = evaluation.tiled_inference(bgr, reference, 384)
        torch_map, _ = evaluation.tiled_inference(bgr, recovered, 384)
        cv_peaks, torch_peaks = evaluation.peaks(cv, .16), evaluation.peaks(torch_map, .16)
        applied_cv, _ = evaluation.commit_suppression(cv_peaks, (rgb.shape[1], rgb.shape[0]), 7.2)
        applied_torch, _ = evaluation.commit_suppression(torch_peaks, (rgb.shape[1], rgb.shape[0]), 7.2)
        record = {"id": row["id"], "tiles": tiles, "photo_sha256": file_hash(row["path"]),
                  "torch_vs_onnxruntime": differences(onnx, torch_map),
                  "torch_vs_opencv": differences(cv, torch_map),
                  "raw_peaks": compare_peaks(cv_peaks, torch_peaks),
                  "app_applied_peaks": compare_peaks(applied_cv, applied_torch)}
        record["passed"] = all(record[key]["passed"] for key in
                               ("torch_vs_onnxruntime", "torch_vs_opencv", "raw_peaks", "app_applied_peaks"))
        records.append(record)
        print(json.dumps({"photo_parity": record}), flush=True)
    return records


def synthetic_training_check(model: RecoveredImpact, output: Path) -> dict:
    blob = np.random.default_rng(4730).random(INPUT_SHAPE, dtype=np.float32)
    tensor = torch.from_numpy(blob)
    original = {name: value.detach().clone() for name, value in model.state_dict().items()}
    try:
        model.train()
        optimizer = torch.optim.SGD(model.parameters(), lr=.001)
        # Deliberately not an entry-point label: demonstrate differentiability only.
        target = torch.full((1, 2, 384, 384), .25, dtype=torch.float32)
        predictions = model(tensor)
        loss = torch.nn.functional.mse_loss(predictions, target)
        loss_before = float(loss.detach())
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        gradients = []
        for name, parameter in model.named_parameters():
            grad = parameter.grad
            gradients.append({"name": name, "finite": bool(grad is not None and torch.isfinite(grad).all()),
                              "nonzero": bool(grad is not None and torch.count_nonzero(grad))})
        if not all(row["finite"] for row in gradients):
            raise ValueError("Some recovered parameters did not receive finite gradients")
        optimizer.step()
        model.eval()
        with torch.no_grad():
            after = model(tensor).numpy()
            loss_after = float(torch.nn.functional.mse_loss(torch.from_numpy(after), target))
        changed = [name for name, value in model.named_parameters() if not torch.equal(value, original[name])]
        checkpoint = model.save_checkpoint(output / "smoke-only-not-for-deployment.pt", "synthetic-gradient-test-only")
        exported = model.export_onnx(output / "smoke-only-not-for-deployment.onnx")
        reference, native = OrtNetAdapter(exported), opencv_net(exported)
        reference.setInput(blob)
        native.setInput(blob)
        exported_ort, exported_cv = reference.forward(), native.forward()
        restored = RecoveredImpact()
        restored.load_checkpoint(checkpoint)
        with torch.no_grad():
            restored_output = restored(tensor).numpy()
        result = {"purpose": "Connectivity/serialization test, NOT a detection accuracy experiment",
                  "synthetic_targets_only": True, "real_photos_used_for_optimizer": False,
                  "loss_before": loss_before, "loss_after": loss_after,
                  "loss_decreased": bool(loss_after < loss_before),
                  "gradient_tensors": len(gradients),
                  "finite_gradient_tensors": sum(g["finite"] for g in gradients),
                  "nonzero_gradient_tensors": sum(g["nonzero"] for g in gradients),
                  "changed_parameter_tensors": len(changed),
                  "both_ensemble_heads_changed": all(any(
                      b.weight == f"models.{i}.head.weight" and f"graph.{b.module}.weight" in changed
                      for b in model.bindings) for i in (0, 1)),
                  "checkpoint_reload": differences(after, restored_output),
                  "updated_onnxruntime": differences(after, exported_ort),
                  "updated_opencv": differences(after, exported_cv),
                  "checkpoint_sha256": file_hash(checkpoint), "export_sha256": file_hash(exported)}
        result["passed"] = bool(result["loss_decreased"] and len(changed) > 0
                                and result["both_ensemble_heads_changed"]
                                and all(result[key]["passed"] for key in
                                        ("checkpoint_reload", "updated_onnxruntime", "updated_opencv")))
        return result
    finally:
        model.load_state_dict(original)
        model.zero_grad(set_to_none=True)
        model.eval()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, help="Optional photo manifest; only development entries are read")
    parser.add_argument("--output", type=Path, default=ROOT / "build/impact-recovery" /
                        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    args = parser.parse_args()
    output = local_output(args.output / "report.json").parent
    if any(output.iterdir()):
        raise ValueError("Use a new empty output directory to avoid overwriting recovery artifacts")
    configure_runtimes()
    started = time.perf_counter()
    report = {"schema": 1, "purpose": "Recover and verify trainability, not improve detection yet",
              "probability_absolute_tolerance": PROBABILITY_ATOL, "peak_tolerance_review_px": PEAK_ATOL_PIXELS,
              "source_model_sha256": MODEL_SHA256, "real_labels_read": False, "heldout_photos_read": False,
              "phone_changed": False, "passed": False,
              "source_code_sha256": {name: file_hash(ROOT / "tools" / name) for name in
                                     ("recover_impact.py", "verify_impact_recovery.py", "evaluate_impact.py")}}
    try:
        model = RecoveredImpact()
        report["model"] = model.describe()
        print(json.dumps({"recovered_model": report["model"]}), flush=True)
        report["synthetic_parity"] = verify_tiles(model, MODEL_PATH)
        report["photo_parity"] = verify_photos(model, MODEL_PATH, args.manifest) if args.manifest else []
        if not all(row["passed"] for row in report["synthetic_parity"] + report["photo_parity"]):
            raise ValueError("Parity gate failed; no optimizer step will run")
        recovered_state = model.save_checkpoint(output / "recovered-original.pt")
        recovered_onnx = model.export_onnx(output / "recovered-original.onnx")
        report["original_roundtrip"] = {"checkpoint_sha256": file_hash(recovered_state),
                                       "onnx_sha256": file_hash(recovered_onnx),
                                       "onnx_byte_identical": file_hash(recovered_onnx) == MODEL_SHA256}
        restored = RecoveredImpact()
        restored.load_checkpoint(recovered_state)
        state_matches = all(torch.equal(value, restored.state_dict()[name]) for name, value in model.state_dict().items())
        report["original_roundtrip"]["checkpoint_tensors_identical"] = state_matches
        if not report["original_roundtrip"]["onnx_byte_identical"] or not state_matches:
            raise ValueError("Unchanged weights did not round-trip exactly")
        report["synthetic_training"] = synthetic_training_check(model, output)
        report["original_weights_restored"] = all(torch.equal(value, restored.state_dict()[name])
                                                   for name, value in model.state_dict().items())
        report["source_asset_unchanged"] = file_hash(MODEL_PATH) == MODEL_SHA256
        report["passed"] = bool(report["synthetic_training"]["passed"] and report["original_weights_restored"]
                                and report["source_asset_unchanged"])
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        report["elapsed_seconds"] = time.perf_counter() - started
        evaluation.write_json(output / "report.json", report)
        print(json.dumps({"passed": report["passed"], "report": str(output / "report.json")}), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
