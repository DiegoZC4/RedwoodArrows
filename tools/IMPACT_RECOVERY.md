# Trainable Impact Detector Recovery

This is a local reconstruction of Diego's shipped detector, not a new accuracy
release or a replacement for the installed app. No contact with Diego, photo
upload, account access, APK installation, or shared-library edit is required.

## What Is Recovered

The source is `assets/impact/impact-center.onnx`, SHA-256
`dd6b558f936e89b427330ea7bcbada2d2149b048befaf732000215d2417451d8`.
It was also verified against the ONNX asset in the original installed
`org.redwoodarrows` APK, version `0.33.0-precise-dots`.

`recover_impact.py` uses onnx2torch to recover the two-network ensemble as a
PyTorch graph: 128 convolutions, 256 trainable tensors, and 4,145,860 trainable
scalar parameters. Both the entry-center and shaft channels are preserved.
Input remains a single RGB float32 `[1,3,384,384]` tile with values in `[0,1]`;
output remains `[1,2,384,384]` probabilities. Existing 600-pixel review resizing,
tiling, blending, peak extraction, and suppression live in `evaluate_impact.py`.

Batch normalization was folded into convolutions before export. Its original
running statistics, original training-mode behavior, loss functions, optimizer
state, augmentations, data sampling, and labeled training dataset are NOT
recovered. This graph can be fine-tuned with a new training recipe; it cannot
claim to exactly resume the original training process. Batch size is fixed at
one; use gradient accumulation rather than silently changing the input contract.

## Setup And Verification

Use an isolated Python 3.12 environment. The tested Windows CPU packages are:

```powershell
python -m pip install torch==2.14.1+cpu torchvision==0.29.1+cpu --index-url https://download.pytorch.org/whl/cpu
python -m pip install -r tools/impact_recovery_requirements.txt
python -m unittest discover -s tests -p 'test_impact*.py' -v
python tools/verify_impact_recovery.py --output build/impact-recovery/my-check
```

For local photo parity, add `--manifest <local-manifest.json>` using the format
documented in `evaluate_impact.py`. Only entries whose split is exactly
`development` are opened. No labels are read and heldout photographs are not
opened. Use a new empty output directory for every verification run.

The verifier requires:

- Five synthetic cases agree with original ONNX Runtime and OpenCV DNN outputs
  within `1e-4` absolute probability. Optional full-photo checks include the same
  raw and app-applied peaks within `0.01` review pixel.
- Saving/loading the original checkpoint preserves every tensor exactly.
- Exporting unchanged weights recreates the original ONNX file byte for byte.
- A synthetic optimizer step receives finite gradients for every parameter,
  changes both ensemble heads, and lowers a deliberately artificial test loss.
- The updated checkpoint reloads identically; updated ONNX runs consistently in
  both ONNX Runtime and OpenCV DNN. The original local weights are then restored.

`report.json` records the gates, environment, hashes, and errors. A failed gate
returns a nonzero process status. Reports with photo hashes stay in ignored local
output directories, not in a reusable source handoff.

## Verified Local Result: 2026-10-23

The final verification passed on Windows CPU with the pinned environment:

- 81 unit/regression tests passed: 35 existing evaluator tests and 46 recovery
  and verification tests, including an independent reviewer's failure cases.
- Five synthetic cases and six full development photographs (34 tiles) matched
  the original detector. Maximum probability difference versus OpenCV was
  `0.0000213385`; the same raw and app-applied dots were returned, with maximum
  coordinate difference `0.000007721` review pixel. These measure conversion
  agreement, NOT the distance from a predicted dot to a real arrow entry.
- Original checkpoint tensors reloaded exactly; unchanged ONNX export was
  byte-identical to the source. All 256 parameter tensors received finite
  gradients; 244 changed in the single synthetic step, including both heads.
  Artificial MSE fell from `0.03590015` to `0.03547233`.
- The changed checkpoint reloaded with zero output difference. Its exported
  ONNX agreed within `0.000002087` with OpenCV. Test weight changes were then
  discarded in memory; original assets and both installed apps remain unchanged.

See `RECOVERY_EVALUATION.json` at the repository root for the sanitized summary.
The full local report and recovered checkpoints are under the ignored
`private/recovery-20261023/verification-03/` directory. No detection-accuracy
training was performed in this recovery step, and no phone speed claim is made.

## Local Artifacts And Training API

A successful verification produces:

- `recovered-original.pt`: tensor-only checkpoint plus primitive metadata,
  loaded with `torch.load(..., weights_only=True)`. Reconstruct the architecture
  from the pinned source ONNX before loading it.
- `recovered-original.onnx`: the unchanged baseline, not an accuracy improvement.
- `smoke-only-not-for-deployment.pt` and `.onnx`: synthetic gradient-test artifacts.
  These optimize toward an artificial uniform target. NEVER install or evaluate
  them as an improved arrow detector.

```python
from pathlib import Path
import sys
sys.path.insert(0, "tools")
from recover_impact import RecoveredImpact

model = RecoveredImpact()
model.load_checkpoint(Path("build/impact-recovery/my-check/recovered-original.pt"))
model.train()
# Use model.parameters() with a separately designed, validated training recipe.
# Preserve uncertainty masks; unknown/occluded entries are not negative labels.
model.save_checkpoint(Path("build/my-experiment/checkpoint.pt"), purpose="local-fine-tune")
model.export_onnx(Path("build/my-experiment/impact-center.onnx"))
```

Export replaces only the mapped convolution tensors in the original ONNX graph,
preserving its operators, normalization constants, input/output names, shapes,
opset, and metadata. Exact ONNX output-tensor identities and original parameter
values verify each mapping. Unknown models and unsupported parameter layouts
fail closed. Outputs are restricted to this repository's `build/` and `private/`
directories, so these helpers cannot overwrite the shipped assets.

## Before Any Detection Update

Reconstruction and a falling synthetic loss do not establish better detection.
Design a training-only split and loss, retain ambiguity/occlusion handling, and
reserve genuinely fresh-session photos for final evaluation. The earlier seven
test photos have already been inspected and must not be relabeled as a pristine
holdout for later tuning. Historical overlap with Diego's training data remains
unknown. A new model needs measured recall/false-positive comparisons and native
Android parity/latency checks before installation, plus an updated model card
and checksum. No automation should silently mark predicted points as reviewed.

The existing model attribution and noncommercial/share-alike terms continue to
apply. No personal photos, private labels, keys, or credentials belong in a source
handoff ZIP. See `assets/licenses/IMPACT_DATASET.txt` and `THIRD_PARTY.md`.

## References

- [onnx2torch converter and supported operations](https://github.com/ENOT-AutoDL/onnx2torch)
- [ONNX model representation](https://onnx.ai/onnx/intro/concepts.html)
- [Official PyTorch installation instructions](https://pytorch.org/get-started/locally/)
