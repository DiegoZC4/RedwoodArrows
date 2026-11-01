"""Select on validation, then evaluate exactly one frozen fine-tuned detector."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time

import cv2
import numpy as np
from PIL import Image, ImageOps

from evaluate_impact import (aggregate_scores, commit_suppression, file_hash, load_labels,
                             load_manifest, peaks, score_photo, tiled_inference, write_json)
from recover_impact import MODEL_PATH, local_output


def evaluate(model_path, thresholds, photos, labels, output, cache=None):
    model_hash = file_hash(model_path)
    net = cv2.dnn.readNetFromONNX(str(model_path))
    results = {str(t): [] for t in thresholds}
    for photo in photos:
        started = time.monotonic()
        with Image.open(photo["path"]) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            rgb = np.asarray(image)
            image_size = image.size
        identity=f"{model_hash}:{file_hash(photo['path'])}:{file_hash(Path(__file__).with_name('evaluate_impact.py'))}"
        cache_key=hashlib.sha256(identity.encode()).hexdigest()+".npy"
        cache_file=cache / cache_key if cache else None
        if cache_file and cache_file.exists():
            heat=np.load(cache_file,allow_pickle=False)
            if heat.shape!=(2,round(image_size[1]*600/image_size[0]),600) or heat.dtype!=np.float32 or not np.isfinite(heat).all():
                raise ValueError("Invalid cached model output")
        else:
            heat, _ = tiled_inference(cv2.cvtColor(rgb,cv2.COLOR_RGB2BGR),net,384)
            if cache_file:
                with tempfile.NamedTemporaryFile(dir=cache,suffix='.npy',delete=False) as temporary:
                    temporary_path=Path(temporary.name)
                    np.save(temporary,heat,allow_pickle=False)
                os.replace(temporary_path,cache_file)
        for threshold in thresholds:
            raw = peaks(heat, threshold)
            applied, _ = commit_suppression(raw, (600,heat.shape[1]), 3)
            results[str(threshold)].append({"id":photo["id"], "photo_sha256":file_hash(photo["path"]),
                                            "points":applied.tolist(), "score":score_photo(applied, labels[photo["id"]]),
                                            "image_size":list(image_size), "elapsed_s":time.monotonic()-started})
        print(json.dumps({"model":model_path.name,"photo":photo["id"],"seconds":round(time.monotonic()-started,2)}),flush=True)
    return {"model":str(model_path.resolve()), "sha256":model_hash,
            "thresholds":{t:{"aggregate":aggregate_scores(rows,"score"),"photos":rows} for t,rows in results.items()}}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest",type=Path,required=True)
    parser.add_argument("--labels",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--models",type=Path,nargs="*")
    parser.add_argument("--locked",type=Path)
    parser.add_argument("--exclude-validation-id",nargs="*",default=[])
    parser.add_argument("--heatmap-cache",type=Path)
    parser.add_argument("--threads",type=int,default=4)
    parser.add_argument("--thresholds",type=float,nargs="+",default=[.08,.12,.16,.20])
    args=parser.parse_args()
    if not 1 <= args.threads <= 16:
        parser.error("Use between 1 and 16 evaluation threads")
    out=local_output(args.output)
    if out.exists():
        raise ValueError("Use a new output directory")
    out.mkdir()
    if args.heatmap_cache:
        args.heatmap_cache=local_output(args.heatmap_cache)
        args.heatmap_cache.mkdir(exist_ok=True)
    cv2.setNumThreads(args.threads)
    photos, metadata=load_manifest(args.manifest)
    labels=load_labels(args.labels)
    split="holdout" if args.locked else "validation"
    selected=[p for p in photos if p["split"]==split]
    if args.exclude_validation_id:
        if args.locked:
            raise ValueError("Evaluation exclusions cannot be changed after freezing")
        excluded=set(args.exclude_validation_id)
        if not excluded.issubset({p["id"] for p in selected}):
            raise ValueError("Unknown validation exclusion")
        selected=[p for p in selected if p["id"] not in excluded]
    if not selected or any(p["id"] not in labels or not labels[p["id"]]["complete"] for p in selected):
        raise ValueError("All evaluation photos must have complete adjudicated labels")
    if args.locked:
        if args.models:
            raise ValueError("Held-out comparison accepts only a frozen model")
        locked=json.loads(args.locked.read_text())
        model=Path(locked["model"])
        if file_hash(model)!=locked["sha256"]:
            raise ValueError("Frozen model changed")
        if file_hash(args.manifest)!=locked["manifest_sha256"] or file_hash(args.labels)!=locked["labels_sha256"]:
            raise ValueError("Frozen comparison data changed")
        models=[(MODEL_PATH,[.16]),(model,[locked["threshold"]])]
    else:
        if not args.models:
            raise ValueError("Validation requires candidate checkpoints")
        if not args.thresholds or any(t not in (.08,.12,.16,.20,.25,.30,.40,.50,.60,.70,.80,.90) for t in args.thresholds):
            raise ValueError("Thresholds must use the bounded confidence calibration grid")
        models=[(MODEL_PATH,[.16])]+[(p,args.thresholds) for p in args.models]
    reports=[evaluate(m,t,selected,labels,out,args.heatmap_cache) for m,t in models]
    report={"split":split,"manifest_sha256":file_hash(args.manifest),"labels_sha256":file_hash(args.labels),
            "results":reports,"split_policy":metadata.get("split_policy", "See source manifest"),
            "historical_overlap":metadata.get("historical_overlap", "Original training overlap unknown"),
            "independent_future_session_test":False,
            "excluded_validation_ids":args.exclude_validation_id}
    baseline=reports[0]["thresholds"]["0.16"]["aggregate"]
    if not args.locked:
        eligible=[]
        for model in reports[1:]:
            for threshold,value in model["thresholds"].items():
                score=value["aggregate"]
                if score["tp"]>baseline["tp"] and score["fp"]<=baseline["fp"]:
                    eligible.append((score["f1"],score["tp"],-score["fp"],float(threshold),model,score))
        if eligible:
            best=max(eligible,key=lambda row:row[:4])
            _,_,_,threshold,model,score=best
            freeze={"model":model["model"],"sha256":model["sha256"],"threshold":threshold,
                    "selection_rule":"More true contacts without more false dots on validation, then highest F1",
                    "validation_baseline":baseline,"validation_candidate":score,
                    "excluded_validation_ids":args.exclude_validation_id,
                    "manifest_sha256":report["manifest_sha256"],"labels_sha256":report["labels_sha256"]}
            write_json(out / "locked.json",freeze)
            report["selection"]=freeze
        else:
            report["selection"]=None
    else:
        candidate=reports[1]["thresholds"][str(locked["threshold"])]["aggregate"]
        report["candidate_passes"]=candidate["tp"]>baseline["tp"] and candidate["fp"]<=baseline["fp"]
        report["locked_sha256"]=file_hash(args.locked)
    write_json(out / "report.json",report)
    print(json.dumps({"split":split,"baseline":baseline,
                      "selection":report.get("selection"),"candidate_passes":report.get("candidate_passes")}),flush=True)


if __name__=="__main__":
    main()
