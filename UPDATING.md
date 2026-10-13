# Updating

From version 0.21, the app checks GitHub on opening and offers an **Update** button when a newer version is available. Tap it, open the APK, and confirm **Update**. Do not uninstall or clear app data. Sign-in survives updates.

For older versions or a manual download: [RedwoodArrows.apk](https://github.com/DiegoZC4/RedwoodArrows/releases/latest/download/RedwoodArrows.apk). Install this once to enable automatic update notices. Checks are quiet offline and cached for 15 minutes; installers download only after you tap.

For an AI/helper: use the latest GitHub release, not an old email's version or checksum. Verify `SHA256SUMS.txt` and the APK signer below, then install in place (`adb install -r RedwoodArrows.apk` if using ADB). No sync password, GitHub token, or account reset is needed to update.

- Package: `org.redwoodarrows`; Android 12+, arm64.
- Signing certificate SHA-256: `9e25bd67646760cbff2ee26d6ca36b4a106b095e16230482e37343c481e47df6`.
- Photos, groupings, credentials, signing keys and database are not in this public repository.
