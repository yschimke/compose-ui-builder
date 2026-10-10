import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("installer", Path(__file__).parents[1] / "install-typed-catalog-bundle.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class BundleInstallationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.download = Path(self.directory.name) / "download"
        self.download.mkdir()
        self.output = Path(self.directory.name) / "installed"
        self.source = "a" * 40
        self.archive = "catalog.zip"
        self.pair = {"components.json": b'{"components":[]}', "ui-builder.json": b'{"catalog":{"id":"example"}}'}
        self.make_bundle()

    def make_bundle(self, extra=None, paired=None):
        with zipfile.ZipFile(self.download / self.archive, "w") as bundle:
            for name, value in (paired or self.pair).items():
                bundle.writestr(name, value)
            if extra:
                bundle.writestr(extra, b"unexpected")
        for name, value in self.pair.items():
            (self.download / name).write_bytes(value)
        self.manifest = {"schema": "compose-ui-builder-executable-bundle/v1-experimental", "catalogId": "example", "source": {"commit": self.source}, "runtime": "android", "archive": self.archive, "sha256": {name: hashlib.sha256((self.download / name).read_bytes()).hexdigest() for name in [self.archive, *self.pair]}}
        (self.download / "bundle.json").write_text(json.dumps(self.manifest))

    def install(self, source=None, runtime="android"):
        installer.install(self.download, self.output, source or self.source, runtime)

    def test_verified_generation_installs_and_refuses_overwrite(self):
        self.install()
        self.assertEqual((self.output / "components.json").read_bytes(), self.pair["components.json"])
        with self.assertRaisesRegex(ValueError, "already exist"):
            self.install()

    def test_corrupt_archive_is_refused_before_installation(self):
        with (self.download / self.archive).open("ab") as content:
            content.write(b"corruption")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.install()
        self.assertFalse(self.output.exists())

    def test_mismatched_published_pair_is_refused(self):
        self.make_bundle(paired={**self.pair, "ui-builder.json": b"different generation"})
        with self.assertRaisesRegex(ValueError, "differs"):
            self.install()
        self.assertFalse(self.output.exists())

    def test_paths_cannot_escape_installation(self):
        self.make_bundle(extra="../outside")
        with self.assertRaisesRegex(ValueError, "invalid bundle path"):
            self.install()
        self.assertFalse(self.output.exists())

    def test_wrong_source_is_refused(self):
        with self.assertRaisesRegex(ValueError, "immutable commit"):
            self.install(source="b" * 40)

    def test_wrong_runtime_is_refused(self):
        with self.assertRaisesRegex(ValueError, "different host runtime"):
            self.install(runtime="jvm-linux-x64")
