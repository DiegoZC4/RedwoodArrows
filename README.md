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

**Take photo** opens an ordinary in-app camera; it does not require ARCore,
motion tracking, a sweep, or arrow detection. It includes an automatic continuous
flashlight. Shooter and distance are editable directly above the shutter: tap or
swipe the name to change person; swipe distance, or tap for exact entry. New
groupings automatically suggest the next saved person in turn and that person's
last distance. With two people this alternates; one person repeats. Cancelled
captures and extra views of the same grouping do not consume a turn. You can
override the suggestion for solo rounds. Unknown or mixed walk-up distances stay
blank. These defaults work offline from the saved library and survive restart.
Set both labels, then one shutter tap saves the untouched camera JPEG and labels
offline. **Photo saved** shows the image and editable labels, with **Next grouping**
and **Another view** (same grouping). There is no annotation or detection step to
complete. People are managed separately under **More → People**; capturing only
selects an existing name. The camera requests a high-quality image around 4 MP
(device-dependent), not sensor RAW/DNG or the phone's maximum sensor resolution.
JPEG bytes are kept unchanged; EXIF orientation is applied only for display and
analysis. No GPS location is requested. A saved-photo receipt appears only after
both image and grouping manifest are committed on the phone.

**Review arrows** is optional. Opening it on an unmarked photo starts dot
suggestions automatically, locally and offline. Correct mistakes and assign
targets, then Save. Shooter and distance can also be corrected in this review;
Save commits them with the annotations. Neither annotations nor label changes
rewrite the original JPEG.
**Add photos** opens Android's
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
moving-phone **Sweep** remains available under
**More → New grouping options**. Photos of different physical ends should not be
selected as one grouping; sync does not infer that they are the same end.

Distance is directly adjustable under each grouping's shooter and in the detail
header: swipe left/right to increase/decrease, then release to save. Yard steps
are 1 through 20, then 5. Tap for an exact distance or meters, hold to clear the
base distance. Vertical swipes still scroll the gallery. Walk-up overrides and
all saved coordinates remain intact. New captures remember each person's latest
saved distance separately; they never borrow the other shooter's distance.
Changing a name before the shutter restores that person's range; correcting the
name on an existing grouping keeps that grouping's actual distance.

To label several rounds, choose a day in the gallery and tap **Select** (or hold
a round). Tap thumbnails to include/exclude them, **All** for the whole day, or
**1,3,5… / 2,4,6…** for alternating rounds in chronological order. **Label selected
rounds** opens one sheet: choose shooter, distance, or both, then Apply. Changes
save offline and sync normally; **Undo labels** restores the previous values.
Photos, coordinates and per-arrow walk-up distances are untouched. If a selected
round changes elsewhere while the editor is open, the batch stops without
overwriting it; reopen the selection to use the current data.

In **Arrows → targets**, dots appear automatically when first reviewing a photo. Use **Add** for
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

**Progress** now opens an offline shooter-comparison graph with separate colored
markers and approximate **95% confidence intervals** on daily points. Tap
**All ranges**, **10 yd**, **15 yd**, or another saved distance directly.
**Daily / Daily + 100** toggles the optional 100-arrow trend without hiding the
daily points. Tap anywhere on that capsule to toggle, including its selected half.
Tap/scrub the graph or use its previous/next arrows for per-shooter values and
coverage. **Details** retains the individual shot plot and older progress tools.
The comparison refreshes when shared labels/positions sync, without changing data.

The plotted measure is within-round angular radial standard deviation in mrad:
each arrow is normalized by its entered distance, then squared deviations from
each round's center are pooled with denominator sum(n−1). It is not standard
error, full group diameter, or bullseye accuracy. All ranges averages the per-range
variances equally; line segments break when the represented range mix changes.
Fixed-distance filtering is best for like-for-like comparisons. The trend uses
the last approximately 100 recorded arrows at each shooting day's end, retains
whole rounds and omits incomplete windows. Its windows overlap and are not
independent evidence of improvement. Days with unknown distances or provisional
owners stay visible with an amber **?** and a **Label rounds** shortcut, but no
invented numerical point. Mixed-distance walk-up rounds are excluded from this
comparison; missing positions/misses are counted
but never placed at zero. Equipment/review filters are under **Filters**. With only one
dated session the Daily graph correctly shows points, not an invented trend.

The intervals resample whole usable rounds 4,096 times, separately within each
distance, retaining equal distance weights in variance. They are the 2.5th and
97.5th percentiles of the resampled SD estimates, not the spread of individual
arrows. **There is no minimum-round cutoff**: short days show error bars too,
with their round counts alongside. The old ten-round gate is removed from both
daily intervals and the optional trend. Small-sample bootstrap intervals are
rough and can underestimate uncertainty; nominal 95% coverage is not guaranteed.
Rounds with fewer than two located arrows do not establish spread. If an included
distance has only one usable round, or every bootstrap estimate is identical,
the point stays visible with a specific **CI unavailable** explanation: resampling
cannot establish between-round uncertainty there. A repeated-distance filter can
still show its own interval. Intervals assume comparable independent rounds and exclude systematic photo
calibration error, missing impacts and persistent between-round dependence.
Computation is offline, repeatable and off the UI thread. More observations can
narrow the uncertainty without automatically lowering the SD. Corrections from
sync refresh the estimate; existing sign-in and groupings are unchanged.

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
