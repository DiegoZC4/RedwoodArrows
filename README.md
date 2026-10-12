# Redwood Arrows — private beta

An offline-first Android 12+ (arm64) archery grouping tracker. Photographs,
calibration, shooter, practice context and estimated 2D positions save on the
phone first. Optional authenticated sync shares the same private library on
diegozc.com. Signing in does not assign the photographed end to that account:
choose the actual shooter separately.

## Try it

1. Download `RedwoodArrows.apk` from this **private repository's Releases** on
   your Android phone. Permit installation from your browser when Android asks.
2. Open **Shared library → Join library**. Choose your own username and password
   (12+ characters), and paste your private one-use invitation. Invitations
   expire after seven days. Existing accounts use **Sign in** instead.
3. Let the first sync finish to download existing shooter identities, groupings
   and photos. Pick the shooter, target and distance; scan or import a grouping.
   Subsequent captures and corrections work without signal. Android resumes
   syncing when a network is available; **Sync now** is also available.

Android may defer background jobs in battery saver/Doze or after a force-stop.
Open the app to resume promptly. Do not uninstall/clear storage with unsynced
shots. Signing out retains offline photos and coordinates on the unlocked phone.
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

Use a **private** GitHub repository for this beta. The exported source contains
no personal photos, annotations, accounts, invitations, database, dependency
cache or signing key. Manufacturer target artwork is omitted; geometric ring
previews remain, and animal/game faces display an unavailable-artwork notice.
Model weights and their notices are included for noncommercial testing;
see `THIRD_PARTY.md` and `assets/licenses/` before any public/commercial release.
Do not claim the full app/data/models have one blanket open-source license.

The workflow uses manually triggered GitHub Actions and stores the APK as a
build artifact. It does **not** publish a release or change your server.
Add these repository Actions secrets:

- `REDWOOD_KEYSTORE_BASE64`: base64 of the existing private signing keystore.
- `REDWOOD_STORE_PASSWORD`, `REDWOOD_KEY_PASSWORD`, `REDWOOD_KEY_ALIAS`.

Use the **same signing key** for every update. A different key cannot upgrade
an installed app. Never put a key/password in the repository or upload it as an
artifact. After building, create a private GitHub release and attach its APK so
Iris can download it with access to that repository.

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
accounts, Argon2id password hashing, hashed revocable 90-day bearer tokens,
HTTPS, private photo downloads, checksums, optimistic concurrency and revision
history. The Android token is encrypted by Android Keystore; no cloud secret is
embedded in the app. Shared-library members can see and edit all its groupings.

See `server/README.md` for installation, backup, recovery and limits. Server-side
data belongs outside the web root; never commit it. This service is not
end-to-end encrypted: the hosting account administrator can read stored data.

Automated tests use invented images and two independent stores. Device-specific
camera behavior and Android job scheduling still need on-phone beta testing.
