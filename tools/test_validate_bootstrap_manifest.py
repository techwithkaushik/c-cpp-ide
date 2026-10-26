import unittest

from tools.validate_bootstrap_manifest import validate_manifest


def artifact(abi="armeabi-v7a", version="0.1.0"):
    return {
        "version": version,
        "abi": abi,
        "downloadUrl": f"https://downloads.example.invalid/{abi}.zip",
        "sha256": "a" * 64,
        "compressedSizeBytes": 1024,
        "maximumExpandedBytes": 4096,
    }


def manifest(artifacts=None):
    return {"schemaVersion": 1, "runtimeVersion": "0.1.0",
            "artifacts": artifacts if artifacts is not None else [artifact()]}


class BootstrapManifestValidationTests(unittest.TestCase):
    def test_accepts_valid_manifest(self):
        self.assertIsNone(validate_manifest(manifest([artifact(), artifact("arm64-v8a")])))

    def test_rejects_unknown_fields(self):
        value = manifest()
        value["unreviewed"] = True
        self.assertIn("only", validate_manifest(value))

    def test_rejects_duplicate_abis(self):
        self.assertIn("Duplicate", validate_manifest(manifest([artifact(), artifact()])))

    def test_rejects_version_mismatch(self):
        self.assertIn("version", validate_manifest(manifest([artifact(version="0.2.0")])))

    def test_rejects_http_url(self):
        value = manifest()
        value["artifacts"][0]["downloadUrl"] = "http://downloads.example.invalid/runtime.zip"
        self.assertIn("HTTPS", validate_manifest(value))

    def test_rejects_bad_digest(self):
        value = manifest()
        value["artifacts"][0]["sha256"] = "abc"
        self.assertIn("sha256", validate_manifest(value))

    def test_rejects_boolean_as_size(self):
        value = manifest()
        value["artifacts"][0]["compressedSizeBytes"] = True
        self.assertIn("compressedSizeBytes", validate_manifest(value))

    def test_rejects_empty_artifacts(self):
        self.assertIn("non-empty", validate_manifest(manifest([])))


if __name__ == "__main__":
    unittest.main()
