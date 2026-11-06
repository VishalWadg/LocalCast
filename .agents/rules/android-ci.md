# Android CI/CD & Artifact Delivery Guidelines

1. **Pre-Installed Runner SDK:** When configuring GitHub Actions on `ubuntu-latest`, do not introduce `android-actions/setup-android@v3` or invoke `sdkmanager tools`. Use the runner's pre-installed `$ANDROID_HOME` and accept licenses via `yes | sdkmanager --licenses > /dev/null 2>&1 || true`.
2. **Executable Permissions for Gradle Wrapper:** Always enforce `git update-index --chmod=+x gradlew` or run `chmod +x gradlew` within CI steps to avoid permission errors on Linux runners.
3. **Telegram 50MB Delivery Constraint:** Telegram Bot API (`sendDocument`) has a strict 50 MB limit. When sending compiled artifacts to Telegram:
   - Check file size before upload.
   - If <= 50 MB, dispatch via `sendDocument`.
   - If > 50 MB, fall back to `sendMessage` providing an HTML-formatted release card with direct GitHub Release CDN download links.
4. **Inspect API Status:** Never execute `curl` to Telegram without verifying the JSON response. Inspect for `"ok": true` and exit with code 1 if rejected to prevent silent CI failures.
