# Reused components

* **OpenCV 4.12.0** (Apache-2.0): native image processing, line-segment proposals, ellipse fits, homography/pose geometry. https://github.com/opencv/opencv/tree/4.12.0
* **Apache Commons Math 3.6.1** (Apache-2.0): nonlinear least-squares calibration and SVD. https://commons.apache.org/proper/commons-math/
* **Google ARCore Android sample** at `3abfeb18669c2cbb2d07057f135d117ee9d91826` (Apache-2.0 sample code): camera background renderer and shaders, retained with their headers. https://github.com/google-ar/arcore-android-sdk
* **ARCore 1.56.0 runtime client**: distributed under Google's ARCore Additional Terms, NOT an open-source visual-inertial engine. Uses the already-installed Google Play Services for AR. No Cloud Anchors or geospatial services are used.
* **AndroidX annotations 1.3.0** (Apache-2.0).
* **torchvision MobileNetV3-Small** (BSD-3-Clause code): pretrained ImageNet feature backbone, locally fine-tuned on Blender-generated arrow masks with a small project-specific decoder. The 1.1 MB ONNX model runs through existing OpenCV DNN; PyTorch/torchvision are training-only, not APK runtime dependencies. Exact hashes/versions are in `HYBRID_DEPENDENCIES.lock.json`, with the retained notice at `assets/licenses/TORCHVISION.txt`. Check upstream source-dataset terms before public/commercial distribution of derived pretrained weights.
* **htkseason/Archery-Scoring** at `bd9b6fac9be7ceb2f51a438800e5ebeb4abb230a` (Apache-2.0): line equations and target-plane intersection approach adapted in `Geometry.java`. Original source is preserved under `third_party/archery_scoring/` for provenance. The original fixed-camera assumptions, forced four-arrow clustering, and target-edge clipping are not carried over.

Versioned sources, downloaded file hashes, and full notices are recorded in `DEPENDENCIES.lock.json`, `HYBRID_DEPENDENCIES.lock.json` and `assets/licenses/`. The application adds its own calibration, interaction, persistence and capture integration. Its optional hybrid model is now arrow-fine-tuned locally on synthetic data, not downloaded as an already arrow-trained model. It remains experimental with substantial false positives and missed arrows; see `tools/hybrid/README.md`.

Private photographs are not part of the APK or the reusable project.

Host-only storage regression tests use **JSON-java 20240303** (Public Domain),
with the version, download URL and pinned SHA-256 in `deps/test/README.txt`.
Source and license: https://github.com/stleary/JSON-java/tree/20240303 . This
78,332-byte JAR is not an Android runtime dependency and is not bundled in the
APK. The installed app uses Android's built-in `org.json` implementation.

The visual target gallery includes **Maple Leaf Press's proprietary catalog
thumbnails**, not open-source artwork. These small images identify presets in
this personal build; they are not print-ready target sheets and are not used as
measurement/scoring templates. `assets/target-previews/manifest.json` records
the original URLs, hashes and two PDF-image extractions. The maker retains its
artwork/trademark rights; no general redistribution license is asserted. Review
permission before publishing the app. The notice is bundled at
`assets/licenses/MAPLE_LEAF_PREVIEWS.txt`.

The new entry-center pipeline additionally uses Haozhe Ma's **Archery target
FITA 60cm**, version 2, for local training (479 JPEGs and 1,746 point labels).
The publisher identifies the dataset license as **CC BY-NC-SA 4.0**:
https://www.kaggle.com/datasets/haozhema/archery-target-fita-60cm . Attribution
and the archive checksum are retained in `assets/licenses/IMPACT_DATASET.txt`.
The public corpus is training-only; no public-dataset image is presented as an
independent evaluation example. This personal experimental build is not a
public/commercial model release. No training photographs are bundled.

The new decoder predicts entry centers directly, with the existing OpenCV DNN
runtime. SIFT/RANSAC supplies paper-plane registration; ARCore's already-used
visual-inertial poses may supply a prior. New conservative cross-view association
and robust averaging preserve occlusion and ambiguity rather than forcing a
fixed arrow count. Repeated views do not multiply detector confidence.

The continuous flashlight uses the existing ARCore session's `TORCH` / `OFF`
configuration, following Google's [camera flashlight documentation](https://developers.google.com/ar/develop/camera/flash/java).
Camera2 is used only to check flash availability; the app does not open a second
camera or compete with ARCore using the system flashlight switch. Live Y-plane
metering and its debounced/latching policy are small project code, not a new
model, package or runtime dependency.
