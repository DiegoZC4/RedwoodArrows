# Redwood Arrows

**[Download the latest Android app](https://github.com/DiegoZC4/RedwoodArrows/releases/latest/download/RedwoodArrows.apk)** · [Release notes](https://github.com/DiegoZC4/RedwoodArrows/releases/latest)

To update, download and open the APK, then choose **Update**. **Do not uninstall**
or clear storage: your existing sign-in, photos and groupings stay saved. In the
app, **More → Download app update** opens the same permanent link. From version
0.21 onward, opening the app also checks GitHub quietly and shows an **Update**
button beside the sync status when a newer installer is available. No GitHub
account or new sync login is needed. See [UPDATING.md](UPDATING.md) for assistants.

Update checks fetch only public release metadata, not the APK or your library
data. They run off the UI thread on opening/resuming the app and when a validated
internet connection returns while it is open; successful results are cached for
15 minutes. Offline use stays quiet. There is no background polling, forced
update, or automatic APK download. Tap **Update**, then confirm the normal
Android installation; do not uninstall. Same/older versions, prereleases,
incomplete uploads and APK links outside this repository are not offered.

An offline-first Android 12+ (arm64) archery grouping tracker. Photographs,
calibration, shooter, practice context and estimated 2D positions save on the
phone first. Optional authenticated sync shares the same private library on
diegozc.com. Signing in does not assign the photographed end to that account:
choose the actual shooter separately.

**Take photo** opens the in-app camera with the existing automatic continuous
flashlight. One shutter tap saves the photo and opens **Arrows → targets**; no
sweep, arrow labels or review is required to sync the photo. Arrow-dot suggestions
start automatically on the saved photo, locally and offline; capture never waits
for them. Correct mistakes and assign targets, then Save. **Add photos** opens Android's
photo picker directly (with a Files fallback), without whole-library access.
Select up to 12 views of the **same unchanged grouping** at once. For another
end, start again from the gallery. Within a grouping, the same two buttons append
more views instead of making another end; adding the identical source file again
is skipped. Camera-roll originals are never changed. Imported copies are oriented
correctly, limited to 2048 pixels on their longest edge, and stripped of EXIF
location metadata; the capture date is retained when available.

Photo intake keeps these photo copies for automatic shared sync, independently
of the **2D only** preference for new analyzed sweeps. Existing 2D-only groupings
are not converted or altered. Signed-in phones queue saved photos offline and
sync when connected, including groupings with no arrow positions yet. The newly
imported photo opened in the editor also gets automatic suggestions. The ordinary
moving-phone **Sweep** remains available; detailed capture options are under
**More → New grouping options**. Photos of different physical ends should not be
selected as one grouping; sync does not infer that they are the same end.

Distance is directly adjustable under each grouping's shooter and in the detail
header: swipe left/right to increase/decrease, then release to save. Yard steps
are 1 through 20, then 5. Tap for an exact distance or meters, hold to clear the
base distance. Vertical swipes still scroll the gallery. Walk-up overrides and
all saved coordinates remain intact. The next-end distance is remembered
separately; correcting an old end never changes it.

In **Arrows → targets**, dots appear automatically after capture. Use **Add** for
missed shaft-entry centers (drag dots to refine), and **Remove** for false positives.
**Retry dots** is only a fallback. Reopening a saved photo does not rerun detection
or resurrect removed dots, and late results cannot overwrite edits made while
detection runs. Tap **All**, or **None** then select
individual arrow dots, and tap **Target** followed by their intended bullseye.
Repeat for another subset if you switched bullseyes during the end. Field/Hunter
faces get automatic ring outlines; **Mark rings/corners** is the manual fallback.
The app never infers intent from which bullseye an arrow landed closest to.
**Undo** reverses edits; **Save** stores each arrow relative to its assigned face
and queues the correction for sync. Leaving without saving keeps the captured
photo but discards annotation changes. Existing groupings have **Assign arrows
to targets** too. All faces in a grouping use its selected printed-size preset.

Multiple faces remain one grouping and one logical photo, with disjoint arrow
subsets and target-specific calibrations in the existing sync format. Identical
JPEG aliases deduplicate in cloud storage. When reassigning an arrow, its older
target alignment in other photos is excluded until confirmed again; its pixel
marks remain available. Unselected arrows retain their existing alignments.

## Try it

1. Download the APK above and permit installation from your browser when Android
   asks. Android 12+ and an arm64 phone are required. This is an experimental app.
2. On a **first installation only**, open **Shared library → Sign in** using your supplied account. Do not use
   **Join library** for an account that already exists. New invitees without an
   account choose **Join library**, pick a username and password (12+ characters),
   and paste their private one-use invitation; invitations expire in seven days.
3. Let the first sync finish to download existing shooter identities, groupings
   and photos. Pick the shooter, target and distance; scan or import a grouping.
   Subsequent captures and corrections work without signal. Android resumes
   syncing when a network is available; **Sync now** is also available.

Android may defer background jobs in battery saver/Doze or after a force-stop.
Opening/resuming the app or regaining a validated connection now retries
immediately. While open, the app checks for shared edits once per minute;
temporary failures retry after 15 seconds with capped backoff. These foreground
checks stop when you leave the app; Android still schedules background work.
Download progress and specific connection errors appear in the existing sync
status. A bounded, app-private diagnostic receipt excludes credentials, photos
and labels. Unchanged syncs do not rebuild the gallery while you are labeling.
Do not uninstall/clear storage with unsynced
shots. Signing out retains offline photos and coordinates on the unlocked phone.
Sign-in has no routine time limit and survives normally signed, in-place app
updates. Do not uninstall/reinstall to update: that erases the device's sign-in
key. A new phone, clearing storage, explicit sign-out or security revocation can
still require sign-in. Keep the package ID, signing identity, `sync-auth`
preferences and `redwood-sync-v1` Keystore alias stable across releases.
The server keeps prior accepted versions and images for recovery; choosing
“2D only” affects new ends, not a request to erase old cloud photos.

If both phones change one grouping offline, neither overwrites the other.
**Shared library → Review edit conflicts** lets you select the version to keep.
Different new groupings merge automatically. Pictures of the same physical end
taken as separate new groupings on different phones are **not** automatically
merged by sync; their IDs differ. Existing multi-view capture stays one end.

This detector is experimental: the reserved test found 19/36 known entries and
7 false detections; matched entries averaged 1.85 pixels at 600-pixel width.
Review the grouping plots. Neither detector confidence nor cross-view agreement
is a physical measurement-accuracy guarantee.

## Build and share updates

This public repository and its downloads contain
no personal photos, annotations, accounts, invitations, database, dependency
cache or signing key. Manufacturer target artwork is omitted; geometric ring
previews remain, and animal/game faces display an unavailable-artwork notice.
Model weights and their attribution/terms are included for noncommercial
experimentation; see `THIRD_PARTY.md` and `assets/licenses/` before reuse.
Do not claim the full app/data/models have one blanket open-source license.

Pushing a version tag matching the manifest (for example `v0.20.0`) runs tests,
builds the same-signed APK and publishes it with `SHA256SUMS.txt` as the latest
GitHub release. The download link above stays the same across versions. Manual
workflow runs produce a test artifact without publishing a release. Neither
path changes the sync server or its accounts. Signing uses these encrypted
repository Actions secrets:

- `REDWOOD_KEYSTORE_BASE64`: base64 of the existing private signing keystore.
- `REDWOOD_STORE_PASSWORD`, `REDWOOD_KEY_PASSWORD`, `REDWOOD_KEY_ALIAS`.

Use the **same signing key** for every update. A different key cannot upgrade
an installed app. Never put a key/password in the repository or upload it as an
artifact. The workflow refuses a different signing identity or mismatched
version tag. Increase `versionCode` for each future update and preserve the
package/auth identifiers above. No per-release account setup or email is needed.

Local build requires JDK 21+, Python 3, Android SDK platform 34, build-tools
36.1.0 and zip. Set `ANDROID_HOME`, `JAVA_HOME`, `REDWOOD_KEYSTORE`,
`REDWOOD_STORE_PASSWORD`, `REDWOOD_KEY_PASSWORD` and `REDWOOD_KEY_ALIAS`, then
run `bash tools/build_beta.sh`. Dependencies are fetched from official Maven
repositories and checked against pinned SHA-256 hashes. Output must remain
below 100 MB including extracted payload.

For the isolated sync tests install PHP 8.2+ with PDO SQLite/Argon2id, plus Pillow
in a Python environment, then run `bash tools/test_sync.sh` (set `REDWOOD_PYTHON`
to that environment's Python). The CI workflow runs these before packaging.

## Server

`server/` is the matching PHP 8.2+ / PDO SQLite service. It uses invitation-only
accounts, Argon2id password hashing, hashed revocable non-expiring device tokens,
HTTPS, private photo downloads, checksums, optimistic concurrency and revision
history. The Android token is encrypted by Android Keystore; no cloud secret is
embedded in the app. Shared-library members can see and edit all its groupings.

See `server/README.md` for installation, backup, recovery and limits. Server-side
data belongs outside the web root; never commit it. This service is not
end-to-end encrypted: the hosting account administrator can read stored data.

Automated tests use invented images and two independent stores. Device-specific
camera behavior and Android job scheduling still need on-phone beta testing.
