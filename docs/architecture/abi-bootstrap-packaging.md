# ABI-specific bootstrap artifacts

The ABI APK pipeline accepts only project-owned, architecture-matched bootstrap archives. Do not copy Termux's upstream bootstrap packages: their package identity and prefix are not guaranteed to be relocatable into this app.

## Required inputs

Place the reviewed, reproducibly built artifacts in the CI workspace (or provision them before the packaging job) using this exact layout:

```text
bootstrap-artifacts/
  armeabi-v7a/
    bootstrap.zip
    bootstrap.json
  arm64-v8a/
    bootstrap.zip
    bootstrap.json
  x86/
    bootstrap.zip
    bootstrap.json
  x86_64/
    bootstrap.zip
    bootstrap.json
```

Each `bootstrap.json` must contain:

```json
{
  "abi": "arm64-v8a",
  "version": "0.1.0",
  "sha256": "<64-character SHA-256 of bootstrap.zip>"
}
```

The `abi` value must exactly match the directory name. The CI verifies the SHA-256 before staging the archive into the corresponding Android flavor. It then inspects every built APK to confirm that the embedded metadata declares the APK's expected ABI.

## APK mapping

| Gradle flavor | Android ABI | APK task |
| --- | --- | --- |
| `arm32` | `armeabi-v7a` | `:app:assembleArm32Debug` |
| `arm64` | `arm64-v8a` | `:app:assembleArm64Debug` |
| `x86` | `x86` | `:app:assembleX86Debug` |
| `x64` | `x86_64` | `:app:assembleX64Debug` |

Each APK's native ABI filters and bundled bootstrap are configured together. The CI deliberately fails the packaging job if any required archive or metadata file is absent or if its digest/ABI is inconsistent; it must never silently publish an APK with a missing or mismatched bootstrap.

## Important current limitation

The repository currently contains the bootstrap metadata validator and downloader, but does **not** contain the four project-owned runtime archives or a validated source builder for them. The packaging pipeline therefore refuses to publish ABI APKs until those actual artifacts are produced and reviewed. A metadata file or empty ZIP is not a valid runtime. The project must build/test the Android-compatible execution layer and C17/C++20 smoke tests per ABI before publishing the bundles.
