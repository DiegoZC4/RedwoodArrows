"""Apply predeclared accuracy gates to one frozen detector's held-out results."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from evaluate_impact import aggregate_scores, file_hash, write_json
from recover_impact import local_output


def assess(report, manifest, plan, locked):
    if report.get("split") != "holdout" or len(report["results"]) != 2:
        raise ValueError("Release assessment requires one frozen held-out comparison")
    expected = {p["id"]: p for p in manifest["photos"] if p["split"] == "holdout"}
    if not expected or any(not p.get("session") for p in expected.values()):
        raise ValueError("Every held-out photo needs its shooting session")
    baseline, candidate = report["results"]
    if baseline["sha256"] != plan["baseline"]["model_sha256"] or candidate["sha256"] != locked["sha256"]:
        raise ValueError("Model identity differs from the frozen comparison")
    baseline_key, candidate_key = str(plan["baseline"]["threshold"]), str(locked["threshold"])
    if set(baseline["thresholds"]) != {baseline_key} or set(candidate["thresholds"]) != {candidate_key}:
        raise ValueError("Held-out results must contain only the frozen operating thresholds")
    rows = [baseline["thresholds"][baseline_key]["photos"], candidate["thresholds"][candidate_key]["photos"]]
    for model_rows in rows:
        if len(model_rows) != len(expected) or {r["id"] for r in model_rows} != set(expected):
            raise ValueError("Held-out photo coverage changed")
        for row in model_rows:
            if row["photo_sha256"] != expected[row["id"]]["image_sha256"]:
                raise ValueError("Held-out image changed")
            if row["score"] is None or row["score"]["exact_photo"] is None:
                raise ValueError("Release check requires complete unambiguous human labels")
    # Recompute aggregates from the per-photo scores instead of trusting summary fields.
    before, after = [aggregate_scores(r, "score") for r in rows]
    constraints = plan["release_gate"]
    sessions = {}
    for day in sorted({p["session"] for p in expected.values()}):
        day_scores = [aggregate_scores([r for r in model_rows if expected[r["id"]]["session"] == day], "score") for model_rows in rows]
        sessions[day] = {"baseline": day_scores[0], "candidate": day_scores[1],
                         "recall_change": day_scores[1]["recall"] - day_scores[0]["recall"]}
    gates = {
        "more_true_entries": after["tp"] > before["tp"],
        "no_more_false_dots": after["fp"] <= before["fp"],
        "meaningful_f1_gain": after["f1"] - before["f1"] >= constraints["minimum_f1_gain"] - 1e-12,
        "no_loss_of_exact_photos": after["exact_photos"] >= before["exact_photos"],
        "no_session_recall_regression": all(s["recall_change"] >= -constraints["no_session_recall_drop_over"] - 1e-12 for s in sessions.values()),
    }
    return {"baseline": before, "candidate": after, "f1_gain": after["f1"] - before["f1"],
            "sessions": sessions, "accuracy_gates": gates, "accuracy_passes": all(gates.values()),
            "native_checks_still_required": ["points parity", "empty-image check", "median runtime ratio <= 1.25"],
            "installation_approved": False,
            "limitation": manifest.get("historical_overlap", "Original training overlap unknown")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("report", "manifest", "labels", "plan", "locked", "output"):
        parser.add_argument("--" + key, type=Path, required=True)
    args = parser.parse_args()
    report, manifest, plan, locked = [json.loads(path.read_text()) for path in (args.report, args.manifest, args.plan, args.locked)]
    for source in (report, plan, locked):
        if source["manifest_sha256"] != file_hash(args.manifest) or source["labels_sha256"] != file_hash(args.labels):
            raise ValueError("Frozen data changed")
    if report["locked_sha256"] != file_hash(args.locked) or file_hash(Path(locked["model"])) != locked["sha256"]:
        raise ValueError("Frozen candidate changed")
    result = assess(report, manifest, plan, locked)
    result["evidence_sha256"] = {key: file_hash(getattr(args, key)) for key in ("report", "manifest", "labels", "plan", "locked")}
    out = local_output(args.output)
    if out.exists():
        raise ValueError("Do not replace a prior release assessment")
    write_json(out, result)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
