# Genesis2ND — public Android build runner

This repository is a deliberately limited public mirror of the Genesis Android project. It exists to run the Android release build on GitHub's free standard public-repository runners while the primary Genesis repository remains private.

Only Android application source, Android build configuration, Android JVM tests, and the release workflow belong here. Do not mirror `.github/genesis-dev/` queues/results, device telemetry, credentials, signing-key material, or Phone A/Phone B private data.

## Build

The workflow is manual: **Actions → Genesis Android APK (public runner) → Run workflow**. Supply a unique request ID.

The workflow requires these repository Actions secrets, containing the exact existing Genesis release signing identity:

- `GENESIS_KEYSTORE_BASE64`
- `GENESIS_KEYSTORE_PASSWORD`
- `GENESIS_KEY_ALIAS`
- `GENESIS_KEY_PASSWORD`

Do not generate a replacement key for an in-place Genesis update. A different signing certificate will not update the already-installed Genesis app. These secrets are not committed to this repository and are never printed by the workflow.

The workflow builds package `app.genesis.mobile` from the mirrored Android source and verifies the APK signature and package/version metadata before uploading an Actions artifact. Public standard GitHub-hosted runners are free; release/artifact visibility is public in this repository.

## Source synchronization

The Android files are mirrored from `tryyy8/....` branch `genesis-dev-bridge`. Keep the mirror limited to `android/` build inputs and this repository's workflow/documentation. The private Genesis controller remains authoritative.
