#!/usr/bin/env python3
"""Build bundled (2020-2050) or complete DE440 astronomy packages.

The bundled subset uses JPL's original ASC2EPH converter. The complete package
contains JPL's unmodified Linux binary. Neither profile changes coefficients.
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
COMMON_SOURCES = {
    "testpo.440": f"{JPL}/ascii/de440/testpo.440",
    "finals2000A.all": "https://datacenter.iers.org/data/9/finals2000A.all",
}
SUBSET_SOURCES = {
    "asc2eph.f": f"{JPL}/fortran/asc2eph.f",
    "header.440": f"{JPL}/ascii/de440/header.440",
    "ascp01950.440": f"{JPL}/ascii/de440/ascp01950.440",
    "ascp02050.440": f"{JPL}/ascii/de440/ascp02050.440",
    **COMMON_SOURCES,
}
FULL_SOURCES = {
    "linux_p1550p2650.440": f"{JPL}/Linux/de440/linux_p1550p2650.440",
    **COMMON_SOURCES,
}


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


def convert_ephemeris(cache, compiler, start, end):
    source = (cache / "asc2eph.f").read_text(encoding="ascii")
    source = source.replace(
        "C      PARAMETER ( NRECL = 4 )", "       PARAMETER ( NRECL = 4 )"
    )
    # Select records beyond both ends, then advertise the narrower supported range.
    start_jd = start.timestamp() / 86400 + 2440587.5 - 64
    end_jd = end.timestamp() / 86400 + 2440587.5 + 64
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


def package_files(cache, native, profile):
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
    files = {
        ("lnxp2020.440" if profile == "bundled" else "lnxp1550.440"): native,
        "finals2000A.all": filtered_eop,
        "UTC-TAI.history": Path(
            "core/data/src/main/resources/orekit/UTC-TAI.history"
        ).resolve(),
    }
    if profile == "bundled":
        files["NASA-JPL-ASC2EPH.txt"] = cache / "asc2eph.f"
    return files


def package_manifest(cache, files, profile, start, end, preparation):
    sources = SUBSET_SOURCES if profile == "bundled" else FULL_SOURCES
    valid_eop = []
    for row in files["finals2000A.all"].read_text(encoding="ascii").splitlines():
        if len(row) >= 68 and row[58:68].strip():
            valid_eop.append(float(row[7:15]))
    if not valid_eop:
        raise RuntimeError("No valid Earth orientation records")
    return {
        "format": 1,
        "version": f"de440-{profile}-{start.year}-{end.year - 1}-"
        + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d"),
        "distribution": profile,
        "coverageStartMillis": int(start.timestamp() * 1000),
        "coverageEndMillis": int(end.timestamp() * 1000),
        "eopStartMillis": int((min(valid_eop) - 40587) * 86400000),
        "eopEndMillis": int((max(valid_eop) - 40587) * 86400000),
        "sources": sources,
        "sourceSha256": {name: digest(cache / name) for name in sources},
        "preparation": preparation
        | {
            "eopFiltering": "Remove date-only placeholder rows without polar motion or UT1"
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
    parser.add_argument("--profile", choices=("bundled", "full"), default="full")
    parser.add_argument("--compiler", type=Path)
    parser.add_argument("--cache", type=Path, default=Path("build/astro-data-sources"))
    parser.add_argument("--output", type=Path, default=Path("dist/orekit-data"))
    args = parser.parse_args()
    cache, output = args.cache.resolve(), args.output.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    sources = SUBSET_SOURCES if args.profile == "bundled" else FULL_SOURCES
    for name, url in sources.items():
        print(f"Preparing {name}", flush=True)
        download(url, cache / name)
    if args.profile == "bundled":
        if args.compiler is None:
            parser.error("--compiler is required for the bundled subset")
        start = dt.datetime(2020, 1, 1, tzinfo=dt.timezone.utc)
        end = dt.datetime(2051, 1, 1, tzinfo=dt.timezone.utc)
        native, start_jd, end_jd = convert_ephemeris(cache, args.compiler, start, end)
        preparation = {
            "converter": "NASA/JPL ASC2EPH",
            "NRECL": 4,
            "T1": start_jd,
            "T2": end_jd,
        }
    else:
        start = dt.datetime(1550, 1, 1, tzinfo=dt.timezone.utc)
        end = dt.datetime(2650, 1, 1, tzinfo=dt.timezone.utc)
        native = cache / "linux_p1550p2650.440"
        preparation = {
            "converter": "None; unmodified NASA/JPL Linux DE440 binary",
            "ephemerisRenamedTo": "lnxp1550.440",
            "coverage": "Conservative UTC bounds inside the original TDB record range",
        }
    files = package_files(cache, native, args.profile)
    manifest = package_manifest(cache, files, args.profile, start, end, preparation)
    write_package(output, files, manifest)


if __name__ == "__main__":
    main()
