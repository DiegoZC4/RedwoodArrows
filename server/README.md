# Private shared archery library

PHP 8.2+ with PDO SQLite and Argon2id. This is a **single invite-only library**:
all invited members can see and edit all groupings and images. Accounts identify
the editor/photographer; the stored shooter ID is independent.

Production URL: `https://diegozc.com/redwood/api.php`. Only `public/api.php` and
`public/.htaccess` go under the website's `public_html/redwood/`. The database,
blobs, application code and command-line maintenance tool live in the account's
private `redwood-arrows/` directory, outside every document root. No existing
homepage or unrelated app needs changing.

## Initial setup

1. Create the private directory with mode 0700; copy `app.php` and `cli.php`
   there with mode 0600. Never put them or SQLite under the public web root.
2. Run `php /absolute/private/cli.php init /absolute/private`. Initialization
   refuses to overwrite an existing database. It prints two single-use
   invitations: an owner invitation for Diego and a member invitation for Iris.
   Save these privately, not in logs, source control, a public URL or releases.
3. Install the two public files. `api.php` defaults to the existing Hostinger
   layout; set `REDWOOD_PRIVATE_DIR` server-side for a different host.
4. Verify HTTPS health, anonymous reads/writes returning 401, and that private
   database/blob paths cannot be downloaded. No CORS or cookie authentication
   is enabled. Web/Android requests authenticate using a Bearer header.

`REDWOOD_TEST_HTTP=1` is for disposable loopback tests **only**. Never enable it
in production. The Android production endpoint is fixed HTTPS, with redirects
disabled so credentials cannot be forwarded to another host.

## Accounts and recovery

Each person chooses a username and password in the app when redeeming an
invitation. Passwords use Argon2id; only token hashes are stored server-side.
90-day device tokens can be revoked by signing out; changing the password
revokes all the account's other device sessions. The owner can issue member
invitations in the app. Invitations expire after seven days and work once.

For a replacement invitation use `php cli.php invite /absolute/private member`.
For lost owner credentials, the hosting administrator can issue a new owner
invitation, then revoke the old account's sessions through audited maintenance.
There is no unverified email/password-reset endpoint or open registration.
Library membership is separate from GitHub repository access.

## Private app download

An authenticated member can request `GET ?action=release` for the version, APK
SHA-256, signing-certificate SHA-256 and byte count, then `GET ?action=apk` for
the signed Android beta. Both require the same Bearer header as sync; anonymous
requests return 401. Never put a password or token in a URL. A setup assistant
can log in with the member's account, download and verify the APK, then revoke
that temporary download session with `POST ?action=logout`. Signing in on the
phone creates its own independent encrypted session.

The release lives in the private `release/RedwoodArrows.apk`, not the public web
root. `release/manifest.json` supplies `version`, `package` (`org.redwoodarrows`),
`bytes`, `sha256` and `certificate_sha256`. Both files must be owner-readable
only. The server verifies size and checksum before serving a release. Deploy a
complete staged release directory with an atomic rename; do not replace bytes
under an active filename in place. Keep release downloads private because this
personal beta includes third-party target previews and noncommercial weights.

## Storage, durability and limits

Group records retain the complete saved JSON: stable IDs, target geometry,
calibration, shooter, annotations, per-arrow positions, practice data and view
metadata. Photo blobs are content-addressed SHA-256 JPEGs (maximum 8 MiB,
4096×4096); source camera-roll files are never uploaded directly. The app uploads
its existing resized private JPEG copies. Each grouping permits up to 64 views
and 1 MiB of JSON; the private image library is capped at 2 GiB. A full library
returns an explicit failure and leaves captures queued safely on the phone.

Downloads and uploads are bounded, checksum-verified and retryable. An image
finishes before a grouping can reference it. SQLite transactions enforce
revision compare-and-swap. Repeated identical writes return the original
acknowledgement. Conflicting edits are retained on their phones until a user
chooses; every accepted server revision also stays in `versions` for recovery.
Per-group image names cannot be reassigned to different image bytes.

There is deliberately no remote-delete or automatic garbage-collection endpoint
in the beta. Old versions and images remain until a separately reviewed retention
policy is added. “2D only” new drafts are not uploaded until finalized, and then
contain no photos. Removing references never erases older cloud images.

The app's scan-based outbox discovers all durable local saves, even after process
death between a save and scheduling. A persisted Android network-constrained
job uploads pending work; a 15-minute periodic job pulls the other phone's new
ends. Android may delay either in Doze/battery saver. Foreground opening and
**Sync now** also sync. Force-stopped apps must be reopened. Login is never
required for local capture. The sync lane is separate from camera inference.

## Backup and deployment

`php cli.php backup /absolute/private` creates a consistent SQLite snapshot with
`VACUUM INTO` under its private `backups/` directory. Content-addressed image blobs
are immutable and not garbage-collected: snapshotting SQLite then copying the
blob directory provides a recoverable backup. An initial private database backup
is created during deployment. No recurring backup scheduler is installed here;
configure account backups or add a reviewed schedule for ongoing protection.

Before updates, create a database snapshot and preserve the exact installed
`app.php`/`cli.php` and public endpoint files. Deploy only this app's four files
using temporary filenames and atomic renames. Do not run `rsync --delete` against
the website. `php cli.php status /absolute/private` reports counts and an
integrity check, never credentials or photo contents.

To restore, stop requests to this endpoint, validate the backup with SQLite's
integrity check, restore it plus any referenced blobs, and then reopen service.
Clients must be rechecked after database rollback: server revisions must not
silently rewind behind a client's acknowledged version. Keep the original
database/WAL and local client data intact while investigating.

## Tests

`tests/test_sync_server.py` creates isolated temporary PHP HTTP servers and
invented JPEGs, and cleans them on success/failure. After compiling
`tests/SyncEngineTest.java`, it additionally drives the actual Java client engine
with two independent simulated phone stores. Checks include authentication,
invitation reuse, throttling, unauthorized downloads, corrupt uploads/downloads,
offline edits, lost acknowledgements, simultaneous edits, explicit conflict
resolution, stale-screen writes, coordinate-only ends and exact image/coordinate
round trips. Neither production data nor personal fixtures are used.

This is not end-to-end encryption: TLS protects transfer, and the host account
controls the at-rest SQLite/photos. Keep hosting access and backups private.
