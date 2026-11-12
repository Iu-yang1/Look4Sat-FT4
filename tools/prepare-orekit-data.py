#!/usr/bin/env python3
"""Build the offline astronomy ZIP with JPL's original ASC2EPH converter.

NASA/JPL coefficients are copied without modifying the ephemeris algorithm.
The converter's documented NRECL and T1/T2 settings select byte-sized records
and the 2000-2100 interval, with padding for time-scale/interpolation boundaries.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import urllib.error
import zipfile

JPL = "https://ssd.jpl.nasa.gov/ftp/eph/planets"
SOURCES = {
    "asc2eph.f": f"{JPL}/fortran/asc2eph.f",
    "header.440": f"{JPL}/ascii/de440/header.440",
    "ascp01950.440": f"{JPL}/ascii/de440/ascp01950.440",
    "ascp02050.440": f"{JPL}/ascii/de440/ascp02050.440",
    "testpo.440": f"{JPL}/ascii/de440/testpo.440",
    "finals2000A.all": "https://datacenter.iers.org/data/9/finals2000A.all",
}
START = dt.datetime(2000, 1, 1, tzinfo=dt.timezone.utc)
END = dt.datetime(2101, 1, 1, tzinfo=dt.timezone.utc)


def download(url, path):
    if path.is_file():
        return
    temporary = path.with_suffix(path.suffix + ".part")
    try:
        with (
            urllib.request.urlopen(url, timeout=120) as source,
            temporary.open("wb") as out,
        ):
            shutil.copyfileobj(source, out, length=128 * 1024)
    except urllib.error.URLError:
        # Windows curl uses the operating system trust store, including managed CAs.
        subprocess.run(
            [
                "curl.exe",
                "--fail",
                "--location",
                "--retry",
                "2",
                "--output",
                str(temporary),
                url,
            ],
            check=True,
        )
    temporary.replace(path)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def convert_ephemeris(cache, compiler):
    source = (cache / "asc2eph.f").read_text(encoding="ascii")
    source = source.replace(
        "C      PARAMETER ( NRECL = 4 )", "       PARAMETER ( NRECL = 4 )"
    )
    # Select records beyond both ends, then advertise the narrower supported range.
    start_jd = START.timestamp() / 86400 + 2440587.5 - 64
    end_jd = END.timestamp() / 86400 + 2440587.5 + 64
    source = source.replace("T1   = -99999999.d0", f"T1   = {start_jd:.1f}d0")
    source = source.replace("T2   =  99999999.d0", f"T2   = {end_jd:.1f}d0")
    (cache / "asc2eph-configured.f").write_text(source, encoding="ascii")
    compiler = compiler.resolve()
    environment = os.environ | {
        "PATH": str(compiler.parent) + os.pathsep + os.environ.get("PATH", "")
    }
    subprocess.run(
        [
            str(compiler),
            "-std=legacy",
            "-ffixed-line-length-none",
            str(cache / "asc2eph-configured.f"),
            "-o",
            str(cache / "asc2eph.exe"),
        ],
        env=environment,
        check=True,
    )
    combined = cache / "converter-input.txt"
    with combined.open("wb") as out:
        for name in ("header.440", "ascp01950.440", "ascp02050.440"):
            with (cache / name).open("rb") as stream:
                shutil.copyfileobj(stream, out)
    native = cache / "JPLEPH"
    if native.exists():
        native.unlink()  # Exact generated converter output, never a user-selected directory.
    with (
        combined.open("rb") as source_stream,
        (cache / "converter.log").open("wb") as log,
    ):
        subprocess.run(
            [str(cache / "asc2eph.exe")],
            stdin=source_stream,
            stdout=log,
            stderr=subprocess.STDOUT,
            cwd=cache,
            env=environment,
            check=True,
        )
    converter_log = (cache / "converter.log").read_text(
        encoding="ascii", errors="replace"
    )
    if "ERROR" in converter_log:
        raise RuntimeError("JPL converter reported an error; inspect converter.log")
    if not native.is_file() or native.stat().st_size < 1_000_000:
        raise RuntimeError(
            "JPL converter did not produce a valid-sized native ephemeris"
        )
    return native, start_jd, end_jd


def package_files(cache, native):
    eop_rows = [
        row
        for row in (cache / "finals2000A.all").read_text(encoding="ascii").splitlines()
        if len(row) >= 68
        and row[18:27].strip()
        and row[37:46].strip()
        and row[58:68].strip()
    ]
    # Blank future placeholder rows are not Earth-orientation measurements or predictions.
    filtered_eop = cache / "finals2000A.valid.all"
    filtered_eop.write_text("\n".join(eop_rows) + "\n", encoding="ascii")
    return {
        "lnxp2000.440": native,
        "finals2000A.all": filtered_eop,
        "UTC-TAI.history": Path(
            "core/data/src/main/resources/orekit/UTC-TAI.history"
        ).resolve(),
        "NASA-JPL-ASC2EPH.txt": cache / "asc2eph.f",
    }


def package_manifest(cache, files, start_jd, end_jd):
    valid_eop = []
    for row in files["finals2000A.all"].read_text(encoding="ascii").splitlines():
        if len(row) >= 68 and row[58:68].strip():
            valid_eop.append(float(row[7:15]))
    if not valid_eop:
        raise RuntimeError("No valid Earth orientation records")
    return {
        "format": 1,
        "version": "de440-2000-2100-"
        + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d"),
        "coverageStartMillis": int(START.timestamp() * 1000),
        "coverageEndMillis": int(END.timestamp() * 1000),
        "eopStartMillis": int((min(valid_eop) - 40587) * 86400000),
        "eopEndMillis": int((max(valid_eop) - 40587) * 86400000),
        "sources": SOURCES,
        "sourceSha256": {name: digest(cache / name) for name in SOURCES},
        "preparation": {
            "converter": "NASA/JPL ASC2EPH",
            "NRECL": 4,
            "T1": start_jd,
            "T2": end_jd,
            "eopFiltering": "Remove date-only placeholder rows without polar motion or UT1",
        },
        "files": [
            {"name": name, "bytes": path.stat().st_size, "sha256": digest(path)}
            for name, path in files.items()
        ],
    }


def write_package(output, files, manifest):
    with zipfile.ZipFile(
        output / "orekit-data.zip", "w", compression=zipfile.ZIP_DEFLATED
    ) as archive:
        for name, path in files.items():
            archive.write(path, name)
        archive.writestr(
            "manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
        )
    (output / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "zip": str(output / "orekit-data.zip"),
                "bytes": (output / "orekit-data.zip").stat().st_size,
                "sha256": digest(output / "orekit-data.zip"),
            }
        ),
        flush=True,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=Path("build/astro-data-sources"))
    parser.add_argument("--output", type=Path, default=Path("dist/orekit-data"))
    args = parser.parse_args()
    cache, output = args.cache.resolve(), args.output.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    for name, url in SOURCES.items():
        print(f"Preparing {name}", flush=True)
        download(url, cache / name)
    native, start_jd, end_jd = convert_ephemeris(cache, args.compiler)
    files = package_files(cache, native)
    manifest = package_manifest(cache, files, start_jd, end_jd)
    write_package(output, files, manifest)


if __name__ == "__main__":
    main()
