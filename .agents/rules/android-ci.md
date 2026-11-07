# Android CI/CD & Artifact Delivery Guidelines

1. **Pre-Installed Runner SDK:** When configuring GitHub Actions on `ubuntu-latest`, do not introduce `android-actions/setup-android@v3` or invoke `sdkmanager tools`. Use the runner's pre-installed `$ANDROID_HOME` and accept licenses via `yes | sdkmanager --licenses > /dev/null 2>&1 || true`.
2. **Executable Permissions for Gradle Wrapper:** Always enforce `git update-index --chmod=+x gradlew` or run `chmod +x gradlew` within CI steps to avoid permission errors on Linux runners.
3. **Telegram 50MB Direct Binary Delivery Requirement:** Telegram Bot API (`sendDocument`) enforces a strict 50 MB (50,000,000 bytes) limit. All deliverables to Telegram must be direct binary uploads:
   - Packaging optimizations in `app/build.gradle.kts` (`packaging.jniLibs.useLegacyPackaging = true` and `defaultConfig.ndk.abiFilters` for `arm64-v8a`/`armeabi-v7a`) ensure the debug APK is ~20.5–22 MB, well within the 50 MB limit (>28 MB headroom).
   - In CI (`.github/workflows/android-build.yml`), perform an explicit pre-flight check asserting the staged APK exists and its file size is strictly <= 50,000,000 bytes. If it exceeds 50 MB, fail the build immediately (`exit 1`). Do NOT fall back to `sendMessage` text cards.
   - Dispatch the binary file via `sendDocument` using `curl` with retries and timeout flags.
4. **Inspect API Status:** Never execute `curl` to Telegram without verifying the JSON response. Inspect for `"ok": true` and exit with code 1 if rejected to prevent silent CI failures.
