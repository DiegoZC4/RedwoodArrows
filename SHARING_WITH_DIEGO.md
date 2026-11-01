# Shared Reviewed Library

Redwood Review (`org.redwoodarrows.review`) now supports a separate, private
Reviewed collection at the existing `https://diegozc.com/redwood/api.php` endpoint.
Both people use their existing Redwood accounts in the new app. The original
`org.redwoodarrows` app and its original server library remain separate.

## What Syncs

- Original retained JPEGs, including additional views and target aliases.
- Arrow marks and corrections, intended-target assignments and calibration.
- Shooter identities, shooting distances, counts and measurement context.
- Photo-review checkmarks, explicit unchecking, and original/corrected review
  points for future training. Review records are bound to exact JPEG bytes and
  arrow coordinates; a stale review cannot certify changed dots.

Capture stays offline-first. Online capture queues upload immediately; Android
jobs retry after disconnection and pull in the background. Foreground sync also
refreshes the shared library. This is not an instant push-notification service;
Android can delay background work in Doze or battery saver.

## Server Setup Required

The additive server update was deployed and verified on October 31, 2026, after
database, immutable-photo and code backups. The original library and existing
sessions were preserved. Initial reviewed-data upload is coordinated with Iris;
deployment alone does not copy her offline library. The
Review app refuses login/upload if the endpoint does not explicitly identify
the Reviewed collection. It never silently falls back to the original library.

For a new hosting installation, the operator should:

1. Back up the existing database and immutable photo blobs using the current
   documented procedure. Preserve the installed `app.php` and `cli.php` too.
2. Deploy the updated `server/app.php` and `server/cli.php` to their existing
   private locations using temporary files and atomic renames. The public
   `api.php` entrypoint and its configured private directory do not change.
3. Run `php cli.php enable-review /absolute/existing/private/directory`.
4. Check `api.php?collection=review&action=health`: it must report
   `collection: review` and `review_records: true`. The ordinary health endpoint
   must still report `collection: original`.

`enable-review` is idempotent. It adds `review_groups` and `review_versions`;
it does not migrate, update, hide or delete any original grouping or version.
Both collections use the existing accounts and content-addressed photo blobs.
All accepted revisions and old photos remain retained. There is no new delete
endpoint and no garbage collection.

## Phone Rollout

1. Back up Iris's current Review data. Update in place with the same signing key;
   do not uninstall or clear storage.
2. After the server update, Iris opens **Shared library**, signs in with her own
   existing account, and lets the initial upload finish. Her reviewed library
   seeds the new collection without changing the original one.
3. Diego installs the provided Review APK alongside his original app and signs
   in with his own existing account. He receives the same photos, corrections,
   calibration and checkmarks. Both use Review for future shared captures.
4. The original app still opens the original library, including Diego's history.
   New Reviewed captures do not alter that older collection.

Concurrent edits to the same grouping remain explicit conflicts, not silent
last-writer-wins replacements. Different rounds sync independently.

## Source And Builds

The source includes Claude's UI changes and the local review workflow. Build with
`tools/build_review.ps1`; use `-Offline` for a network-disabled experiment.
The PowerShell build requires JDK 17+, Android SDK platform 35/build-tools 35.0.0,
and the locked runtime dependencies. On a fresh checkout, first run
`python tools/fetch_runtime.py --tests`, then
`./tools/build_review.ps1 -SdkRoot <sdk-path> -JdkRoot <jdk-path>`.
Run the build once before adding `-Test -Adb <adb-path> -Device <serial>` so a
local signing identity exists for the touch-test APK. A newly generated identity
does not replace the private identity used for the supplied APK.
Release checks for the original APK remain disabled in the Review package.
The supplied APK uses the existing local Review signing identity. Never publish
the signing key; rebuilding with a different key cannot update that installation
in place. Source, installer and photos are different artifacts: private photos,
accounts, backups and signing material do not belong in a public Git repository.

## Detector Outcome

The October 31 experiment finished: six trained checkpoints were compared on 72
validation photos from separate shooting days. None found more true entries
without increasing false dots. The best F1 candidate found 292/427 entries with
65 false dots; the current detector found 293/427 with 75 false dots. Complete
photos fell from 10 to 9. The candidate was not deployed, the 38-photo holdout
was left unused, and existing saved dots were not changed. Aggregate evidence is
in `REVIEW_TRAINING_20261031.json`.

## Verification

- Original collection: 70 two-phone integration checks.
- Reviewed collection: 80 two-phone integration checks, including shared reviews.
- Server: 80 checks, including original-history isolation and authentication.
- Old-server guard: four attempted operations sent only capability probes,
  never credentials, saves, uploads or downloads.
- Review export: 52 tests, 51 passed; one real-symlink test skipped on Windows.
- Full copied library: 238 groupings, 270 portable reviews, 270 unique JPEGs;
  unchanged measurements/checkmarks and all 955 source files unchanged.
- Android host/device suites and 36 touch/render checks passed.

The HTTP integration suite uses disposable local PHP databases and invented
photos. On Windows, build device tests first, then run
`tools/test_sync_windows.ps1 -Adb <adb> -Device <serial>`. The PHP runtime is a
local test dependency, not part of the handoff or phone APK.

The existing GitHub workflow still builds the original package. Do not create a
version tag expecting it to publish Redwood Review. The supplied signed APK is
the ready-to-install Review artifact; no GitHub release has been published.

### macOS/Linux verification

With the locked dependencies and Android 34 SDK installed, run
`bash tools/test_sync.sh` and `bash tools/validate_review.sh`. The latter compiles
all app/test sources, runs the additional Review host checks, and verifies that
an older server cannot receive reviewed-library writes. Neither command installs
an app or touches production data. The training-review export tests are
`python -m unittest discover -s tests -p test_training_reviews_export.py`.

Keep the handoff's Review signing identity for all future in-place Review updates.
Do not replace it with the original app's signing key or a newly generated key.
