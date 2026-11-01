"""Local point-supervised fine-tuning; never writes packaged model assets.

Only explicitly training-split photos are opened. Validation and evaluation are
separate commands so checkpoints cannot be selected from a held-out result.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import tempfile
import time

import cv2
import numpy as np
from PIL import Image, ImageOps
import torch

from evaluate_impact import file_hash, load_labels, load_manifest, tiled_inference, write_json
from recover_impact import RecoveredImpact, MODEL_PATH, local_output


def supervision(shape, clear, uncertain, sigma=1.8):
    """Gaussian contacts, with ambiguity excluded from both positive and negative loss."""
    yy, xx = np.indices(shape, dtype=np.float32)
    target = np.zeros(shape, dtype=np.float32)
    valid = np.ones(shape, dtype=np.float32)
    for x, y in clear:
        target = np.maximum(target, np.exp(-((xx-x)**2 + (yy-y)**2) / (2*sigma*sigma)))
    for x, y in uncertain:
        valid[(xx-x)**2 + (yy-y)**2 <= 8**2] = 0
    return target, valid


def crop_example(example, rng):
    rgb, target, valid, shaft, clear, *extra = example
    h, w = target.shape
    # Most crops include a real contact; random background crops discourage extra dots.
    if clear and rng.random() < .8:
        px, py = rng.choice(clear)
        x = round(px - rng.uniform(72, 312))
        y = round(py - rng.uniform(72, 312))
    else:
        x, y = rng.randint(0, w-384), rng.randint(0, h-384)
    x, y = max(0, min(w-384, x)), max(0, min(h-384, y))
    values = [v[y:y+384, x:x+384].copy() for v in (rgb, target, valid, shaft, *extra)]
    if rng.random() < .5:
        values = [np.flip(v, 1).copy() for v in values]
    if rng.random() < .5:
        values = [np.flip(v, 0).copy() for v in values]
    turns = rng.randrange(4)
    values = [np.rot90(v, turns).copy() for v in values]
    values[0] = np.clip(values[0] * rng.uniform(.85, 1.15) + rng.uniform(-.035, .035), 0, 1)
    image = torch.from_numpy(np.ascontiguousarray(values[0].transpose(2, 0, 1)[None]))
    return image, *(torch.from_numpy(np.ascontiguousarray(v)) for v in values[1:])


def point_loss(output, target, valid, shaft):
    center = output[0, 0]
    positive = valid * (target > .02)
    negative = valid * (target <= .02)
    pos = (((center-target)**2) * positive).sum() / positive.sum().clamp_min(1)
    # Hard negative mining is bounded, and uncertainty never becomes a negative.
    negative_errors = (center**2 * negative).flatten()
    neg = torch.topk(negative_errors, min(4096, negative_errors.numel())).values.mean()
    shaft_keep = ((output[0, 1] - shaft)**2).mean()
    return pos + .6*neg + .25*shaft_keep


def anchored_point_loss(output, target, valid, shaft, teacher_center):
    center = output[0,0]
    positive = valid * (target > .02)
    negative = valid * (target <= .02)
    pos = ((center-.7*target)**2 * positive).sum() / positive.sum().clamp_min(1)
    excess = (torch.relu(center-teacher_center)**2 * negative).flatten()
    change = ((center-teacher_center)**2 * negative).flatten()
    hard_excess = torch.topk(excess,min(4096,excess.numel())).values.mean()
    hard_change = torch.topk(change,min(4096,change.numel())).values.mean()
    shaft_keep = ((output[0,1]-shaft)**2).mean()
    return pos + 6*hard_excess + .5*hard_change + .5*shaft_keep


def train(args):
    output = local_output(args.output)
    if output.exists():
        raise ValueError("Use a new experiment directory; do not overwrite a prior run")
    output.mkdir()
    torch.set_num_threads(args.threads)
    torch.manual_seed(args.seed)
    rng = random.Random(args.seed)
    photos, metadata = load_manifest(args.manifest)
    labels = load_labels(args.labels)
    training = [p for p in photos if p["split"] == "training"]
    if not training:
        raise ValueError("No explicitly training-split photos")
    source_hashes, examples = [], []
    baseline = cv2.dnn.readNetFromONNX(str(MODEL_PATH))
    cv2.setNumThreads(args.threads)
    teacher_cache = local_output(args.teacher_cache) if args.teacher_cache else None
    if teacher_cache:
        teacher_cache.mkdir(exist_ok=True)
    for photo in training:
        annotation = labels.get(photo["id"])
        if annotation is None or not annotation["complete"]:
            raise ValueError(f"Missing complete labels: {photo['id']}")
        with Image.open(photo["path"]) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            size = (600, round(image.height * 600 / image.width))
            if min(size) < 384:
                raise ValueError("Training image is smaller than one tile")
            rgb = cv2.resize(np.asarray(image), size, interpolation=cv2.INTER_AREA)
        target, valid = supervision(rgb.shape[:2], annotation["clear"], annotation["uncertain"])
        identity = f"{file_hash(MODEL_PATH)}:{file_hash(photo['path'])}:{file_hash(Path(__file__).with_name('evaluate_impact.py'))}:training-resize-v1"
        cache_file = teacher_cache / (hashlib.sha256(identity.encode()).hexdigest()+".npy") if teacher_cache else None
        if cache_file and cache_file.exists():
            teacher = np.load(cache_file, allow_pickle=False)
            if teacher.shape != (2, *rgb.shape[:2]) or teacher.dtype != np.float32 or not np.isfinite(teacher).all():
                raise ValueError("Invalid cached training teacher output")
        else:
            teacher, _ = tiled_inference(cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), baseline, 384)
            if cache_file:
                with tempfile.NamedTemporaryFile(dir=teacher_cache, suffix=".npy", delete=False) as temporary:
                    temporary_path = Path(temporary.name)
                    np.save(temporary, teacher, allow_pickle=False)
                os.replace(temporary_path, cache_file)
        examples.append((rgb.astype(np.float32)/255, target, valid, teacher[1], annotation["clear"], teacher[0]))
        source_hashes.append({"id": photo["id"], "sha256": file_hash(photo["path"])})
        if len(examples) % 10 == 0 or len(examples) == len(training):
            print(json.dumps({"prepared_training_photos":len(examples),"total":len(training)}),flush=True)
    model = RecoveredImpact()
    # Adapt high-resolution contact features while retaining the pretrained backbone.
    for name, parameter in model.named_parameters():
        parameter.requires_grad_(any(key in name for key in ("/decoders/3/", "/finish/", "/head/")))
    params = [p for p in model.parameters() if p.requires_grad]
    if not params:
        raise ValueError("No trainable refinement parameters")
    optimizer = torch.optim.AdamW(params, lr=args.learning_rate, weight_decay=.01)
    config = {"schema": 1, "source_model_sha256": file_hash(MODEL_PATH),
              "manifest_sha256": file_hash(args.manifest), "labels_sha256": file_hash(args.labels),
              "seed": args.seed, "steps": args.steps, "save_steps": args.save_steps,
              "learning_rate": args.learning_rate, "trainable_parameters": sum(p.numel() for p in params),
              "training_photos": source_hashes, "split_notes": metadata,
              "loss": args.loss,
              "uncertainty": "8px exclusion; no arrow-count quota", "selection": "Separate validation, never training loss alone"}
    write_json(output / "training-config.json", config)
    print(json.dumps({"training_photos": len(examples), "trainable_parameters": config["trainable_parameters"]}), flush=True)
    model.train()
    losses, started = [], time.monotonic()
    for step in range(1, args.steps+1):
        example = examples[rng.randrange(len(examples))]
        image, target, valid, shaft, teacher_center = crop_example(example, rng)
        optimizer.zero_grad(set_to_none=True)
        prediction = model(image)
        loss = (anchored_point_loss(prediction,target,valid,shaft,teacher_center) if args.loss=="anchored"
                else point_loss(prediction, target, valid, shaft))
        if not torch.isfinite(loss):
            raise RuntimeError("Non-finite training loss")
        loss.backward()
        norm = torch.nn.utils.clip_grad_norm_(params, 5., error_if_nonfinite=True)
        optimizer.step()
        losses.append(float(loss.detach()))
        if step % 20 == 0:
            print(json.dumps({"step": step, "loss_last20": float(np.mean(losses[-20:])),
                              "gradient_norm": float(norm), "elapsed_s": round(time.monotonic()-started, 1)}), flush=True)
        if step in args.save_steps:
            model.save_checkpoint(output / f"step-{step}.pt", purpose="local-photo-fine-tune-not-yet-validated")
            model.export_onnx(output / f"step-{step}.onnx")
    write_json(output / "training-result.json", {"losses": losses, "elapsed_s": time.monotonic()-started,
                                                "deployed": False, "accuracy_not_established": True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--labels", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--steps", type=int, default=240)
    parser.add_argument("--save-steps", nargs="+", type=int, default=[80, 160, 240])
    parser.add_argument("--learning-rate", type=float, default=.0002)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--seed", type=int, default=20261025)
    parser.add_argument("--loss", choices=["point","anchored"], default="point")
    parser.add_argument("--teacher-cache", type=Path, help="Private baseline output cache; never reads held-out photos")
    args = parser.parse_args()
    if not 0 < args.learning_rate <= .001 or not 0 < args.steps <= 2000:
        parser.error("Out-of-range training settings")
    if not args.save_steps or any(s < 1 or s > args.steps for s in args.save_steps):
        parser.error("Checkpoint steps must be within training run")
    train(args)


if __name__ == "__main__":
    main()
