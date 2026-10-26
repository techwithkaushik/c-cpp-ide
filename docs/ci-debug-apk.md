# CI debug APK and stable signing key

## Download an APK for device testing

The **Android CI** workflow uploads the debug APK as the `cpp-ide-debug-apk` artifact after a successful build.

1. Open the repository's **Actions** tab.
2. Open a successful **Android CI** run for the branch you want to test.
3. In **Artifacts**, download `cpp-ide-debug-apk`.
4. Extract the ZIP and install the APK on the Android device.

Artifacts are retained for 14 days. A successful CI build verifies compilation and configured unit tests; it does not replace testing on a physical device.

## Keep the debug signing key stable across CI runs

Do **not** commit `debug.keystore` or its Base64 encoding to this public repository. Anyone who can obtain the private key could sign a different APK with the same debug identity. Instead, store the reusable key as a GitHub Actions repository secret.

Create a debug keystore once on a trusted machine with JDK `keytool`:

```bash
keytool -genkeypair -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

Encode it as one Base64 line (Linux):

```bash
base64 -w0 debug.keystore
```

In GitHub, open **Settings → Secrets and variables → Actions → New repository secret**:
- Name: `ANDROID_DEBUG_KEYSTORE_BASE64`
- Value: the complete Base64 output above

Keep the original keystore and its passwords private and backed up securely. Do not put the Base64 value in commits, issues, logs, or chat.

The workflow restores this key to `$HOME/.android/debug.keystore` before Gradle assembles the debug APK. Once the secret is configured, future CI APKs use the same debug signing identity. Until it is configured, CI emits a warning and Gradle may generate a runner-specific key, so signatures will not be stable across runs.

This is a **debug/testing** key only. Do not use it as a production release key.
