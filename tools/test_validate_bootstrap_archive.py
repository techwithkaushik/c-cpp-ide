import importlib.util
import os
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "tools" / "validate-bootstrap-archive.py"
SPEC = importlib.util.spec_from_file_location("bootstrap_validator", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def make_valid_archive(path):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("bin/sh", b"placeholder")
        archive.writestr("bin/clang", b"placeholder")
        archive.writestr("bin/clang++", b"placeholder")
        archive.writestr("lib/libc.so", b"placeholder")
        archive.writestr("include/stdio.h", b"placeholder")


class BootstrapArchiveValidatorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.archive_path = os.path.join(self.temp.name, "bootstrap.zip")

    def tearDown(self):
        self.temp.cleanup()

    def test_accepts_expected_archive_layout(self):
        make_valid_archive(self.archive_path)
        self.assertEqual([], MODULE.validate(self.archive_path))

    def test_rejects_path_traversal(self):
        make_valid_archive(self.archive_path)
        with zipfile.ZipFile(self.archive_path, "a") as archive:
            archive.writestr("../outside", b"bad")
        errors = MODULE.validate(self.archive_path)
        self.assertTrue(any("unsafe archive path" in e for e in errors))

    def test_rejects_absolute_path(self):
        make_valid_archive(self.archive_path)
        with zipfile.ZipFile(self.archive_path, "a") as archive:
            archive.writestr("/tmp/outside", b"bad")
        errors = MODULE.validate(self.archive_path)
        self.assertTrue(any("unsafe archive path" in e for e in errors))

    def test_rejects_missing_compiler(self):
        with zipfile.ZipFile(self.archive_path, "w") as archive:
            archive.writestr("bin/sh", b"shell")
            archive.writestr("bin/clang", b"clang")
            archive.writestr("lib/libc.so", b"lib")
            archive.writestr("include/stdio.h", b"header")
        errors = MODULE.validate(self.archive_path)
        self.assertTrue(any("bin/clang++" in e for e in errors))

    def test_rejects_symlink_entries(self):
        make_valid_archive(self.archive_path)
        info = zipfile.ZipInfo("bin/evil")
        info.create_system = 3
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(self.archive_path, "a") as archive:
            archive.writestr(info, b"../../outside")
        errors = MODULE.validate(self.archive_path)
        self.assertTrue(any("symbolic links are not allowed" in e for e in errors))

    def test_rejects_invalid_zip(self):
        Path(self.archive_path).write_bytes(b"not a zip")
        errors = MODULE.validate(self.archive_path)
        self.assertTrue(any("cannot read bootstrap ZIP" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
