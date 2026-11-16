import copy
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "build_update_manifest", Path(__file__).with_name("build-update-manifest.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class UpdateManifestTest(unittest.TestCase):
    def setUp(self):
        self.tag = "4.4.8-ba7opf-orekit.15.01"
        name = f"Look4Sat-BA7OPF-Orekit-{self.tag}-release.apk"
        self.release = {
            "tag_name": self.tag,
            "html_url": f"{module.REPOSITORY}/releases/tag/{self.tag}",
            "assets": [
                {
                    "name": name,
                    "browser_download_url": (
                        f"{module.REPOSITORY}/releases/download/{self.tag}/{name}"
                    ),
                    "state": "uploaded",
                    "content_type": "application/vnd.android.package-archive",
                    "size": 21_000_000,
                }
            ],
        }

    def test_new_name_and_version_are_eligible(self):
        manifest = module.build_manifest(self.release)
        self.assertEqual(self.tag, manifest["tag_name"])
        self.assertEqual(self.release["assets"], manifest["assets"])

    def test_legacy_package_asset_is_not_advertised(self):
        release = copy.deepcopy(self.release)
        release["assets"][0]["name"] = f"Look4Sat-FT4-{self.tag}-release.apk"
        with self.assertRaises(ValueError):
            module.build_manifest(release)

    def test_rejects_external_and_wrong_tag_downloads(self):
        for url in (
            self.release["assets"][0]["browser_download_url"].replace(
                module.REPOSITORY, "https://github.com/other/repo"
            ),
            self.release["assets"][0]["browser_download_url"].replace(
                f"/download/{self.tag}/", "/download/old-tag/"
            ),
        ):
            release = copy.deepcopy(self.release)
            release["assets"][0]["browser_download_url"] = url
            with self.assertRaises(ValueError):
                module.build_manifest(release)

    def test_unpublished_and_incomplete_builds_are_rejected(self):
        for flag in ("draft", "prerelease"):
            release = copy.deepcopy(self.release)
            release[flag] = True
            with self.assertRaises(ValueError):
                module.build_manifest(release)
        for change in (
            {"state": "new"},
            {"size": 0},
            {"name": f"Look4Sat-BA7OPF-Orekit-{self.tag}-unsigned.apk"},
        ):
            release = copy.deepcopy(self.release)
            release["assets"][0].update(change)
            with self.assertRaises(ValueError):
                module.build_manifest(release)


if __name__ == "__main__":
    unittest.main()
