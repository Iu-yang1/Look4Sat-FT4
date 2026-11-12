#!/usr/bin/env python3
"""Build the mirror-readable update.json asset from a GitHub release response."""

import argparse
import json
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit

REPOSITORY = "https://github.com/Iu-yang1/Look4Sat-FT4"
TAG_PATTERN = re.compile(r"[vV]?[0-9]+(?:\.[0-9]+)+(?:-[A-Za-z0-9]+(?:\.[0-9]+)*)?")


def build_manifest(release):
    tag = release.get("tag_name", "")
    if not TAG_PATTERN.fullmatch(tag):
        raise ValueError("Invalid release tag")
    if release.get("draft") or release.get("prerelease"):
        raise ValueError("Only public stable releases can be used for updates")
    if release.get("html_url") != f"{REPOSITORY}/releases/tag/{tag}":
        raise ValueError("Release does not belong to the fork")
    assets = []
    for asset in release.get("assets", []):
        name = asset.get("name", "")
        normalized = name.lower()
        if not normalized.endswith(".apk"):
            continue
        if "look4sat" not in normalized or "ft4" not in normalized:
            continue
        if any(word in normalized for word in ("debug", "unsigned", "unaligned")):
            continue
        url = asset.get("browser_download_url", "")
        parsed = urlsplit(url)
        prefix = f"/Iu-yang1/Look4Sat-FT4/releases/download/{tag}/"
        if (
            parsed.scheme != "https"
            or parsed.netloc != "github.com"
            or parsed.query
            or parsed.fragment
            or unquote(parsed.path) != prefix + name
            or "/" in name
            or "\\" in name
        ):
            raise ValueError(f"Invalid APK URL: {name}")
        if (
            asset.get("state") != "uploaded"
            or not 100_000 <= asset.get("size", 0) <= 250_000_000
        ):
            raise ValueError(f"APK upload incomplete: {name}")
        assets.append(
            {
                key: asset[key]
                for key in (
                    "name",
                    "browser_download_url",
                    "content_type",
                    "state",
                    "size",
                )
            }
        )
    if not assets:
        raise ValueError("Release has no eligible APK assets")
    return {
        "tag_name": tag,
        "html_url": release["html_url"],
        "name": release.get("name") or tag,
        "body": release.get("body") or "",
        "draft": False,
        "prerelease": False,
        "assets": assets,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-json", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    release = json.loads(args.release_json.read_text(encoding="utf-8-sig"))
    manifest = build_manifest(release)
    args.output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
