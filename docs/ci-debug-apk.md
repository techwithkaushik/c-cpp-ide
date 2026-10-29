# CI debug APK and stable signing key

## Download an APK for device testing

The **Android CI** workflow uploads the debug APK as the `cpp-ide-debug-apk` artifact after a successful build.

1. Open the repository's **Actions** tab.
2. Open a successful **Android CI** run for the branch you want to test.
3. In **Artifacts**, download `cpp-ide-debug-apk`.
4. Extract the ZIP and install the APK on the Android device.

Artifacts are retained for 14 days. A successful CI build verifies compilation and configured unit tests; it does not replace testing on a physical device.

## Reusable debug key stored in the repository

The workflow reads `ci/debug-keystore.b64`, decodes it to `$HOME/.android/debug.keystore`, and validates the alias before building. The file is intentionally a **debug/testing key**, not a release key.

**Security trade-off:** this repository file is public, so anyone who can access the repository can obtain the debug private key and sign a debug APK with the same identity. This is acceptable only for development/testing builds where that risk is understood. Never reuse this key for production or release signing. If the repository or app later handles sensitive distribution, move the key to a private Actions secret or a dedicated signing service.

The current debug keystore uses alias `androiddebugkey` and password `android`, matching the standard Android debug signing convention. Do not rotate or regenerate this file if you want updates to remain installable over existing builds signed with this identity.
