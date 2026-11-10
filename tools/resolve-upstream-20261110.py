#!/usr/bin/env python3
"""Resolve the reviewed 2026-11-10 upstream diff3 conflict hunks.

The git merge engine has already combined non-overlapping changes. These
decisions apply only to overlapping hunks. Never replace whole source files.
"""
from __future__ import annotations
from pathlib import Path
import subprocess

SPECIAL = {
    "core/data/src/main/java/com/rtbishop/look4sat/core/data/repository/QsoRepository.kt": "upstream",
    "core/data/src/test/java/com/rtbishop/look4sat/core/data/repository/LoTWRepositoryTest.kt": "imports",
    "core/domain/src/test/java/com/rtbishop/look4sat/core/domain/repository/LoTWGridSyncTest.kt": "imports",
    "core/domain/src/main/java/com/rtbishop/look4sat/core/domain/model/AwardCalculator.kt": "award",
}
EXPECTED = {
    "core/domain/src/main/java/com/rtbishop/look4sat/core/domain/model/AwardCalculator.kt": 4
}


def resolve_chunk(name: str, old: str, new: str, index: int) -> str:
    special = SPECIAL.get(name)
    if special == "upstream":
        return new
    if special == "imports":
        lines = old.splitlines(keepends=True)
        for line in new.splitlines(keepends=True):
            if line not in lines:
                lines.append(line)
        return "".join(lines)
    if special == "award":
        if index == 0:
            assert "all: List<GridQso>" in old
            assert "waipaCandidatesForGrid:" in new
            return new.rstrip("\n") + ",\n        all: List<GridQso> = qsosByGrid.values.flatten()\n"
        if index == 1:
            assert "calculateWaipa" in old and "calculateWaipa" in new
            return "            calculateWaipa(qsosByGrid, waipaProvinceForGrid, waipaCandidatesForGrid, waipaManualOverrides, all),\n"
        if index == 2:
            assert "all: List<GridQso>" in old and "manualOverrides:" in new
            return new.rstrip("\n") + ",\n        all: List<GridQso>\n"
        if index == 3:
            assert "withGridless" in old and "resolvedWaipaQsos" in new
            return ("        val mapped = byGrid.values.flatten().toHashSet()\n"
                    "        val withGridless = byGrid + (\"\" to all.filterNot { it in mapped })\n"
                    "        val keys = resolvedWaipaQsos(withGridless, provinceForGrid, candidatesForGrid, manualOverrides)\n"
                    "            .mapNotNull { it.second }.toSet()\n")
        raise ValueError(f"Unexpected award conflict index {index}")
    # Keep custom fork behavior in the competing hunk. The non-conflicting
    # upstream changes outside the hunk are already staged by git-ort.
    return old


def resolve_diff3(name: str, content: str) -> tuple[str, int]:
    lines = content.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    seen = 0
    while i < len(lines):
        if not lines[i].startswith("<<<<<<< "):
            out.append(lines[i])
            i += 1
            continue
        i += 1
        ours: list[str] = []
        while i < len(lines) and not lines[i].startswith(("||||||| ", "=======")):
            ours.append(lines[i]); i += 1
        if i >= len(lines):
            raise ValueError(f"{name}: malformed conflict")
        if lines[i].startswith("||||||| "):
            i += 1
            while i < len(lines) and not lines[i].startswith("======="):
                i += 1
        if i >= len(lines) or not lines[i].startswith("======="):
            raise ValueError(f"{name}: missing separator")
        i += 1
        theirs: list[str] = []
        while i < len(lines) and not lines[i].startswith(">>>>>>> "):
            theirs.append(lines[i]); i += 1
        if i >= len(lines):
            raise ValueError(f"{name}: missing end marker")
        i += 1
        out.append(resolve_chunk(name, "".join(ours), "".join(theirs), seen))
        seen += 1
    result = "".join(out)
    if any(s.startswith(("<<<<<<< ", "=======", ">>>>>>> ")) for s in result.splitlines()):
        raise ValueError(f"{name}: unresolved marker")
    return result, seen


def main() -> None:
    files = subprocess.check_output(["git", "diff", "--name-only", "--diff-filter=U"], text=True).splitlines()
    if not files:
        raise ValueError("No conflicts present; upstream merge audit assumptions changed")
    print(f"Conflict files: {len(files)}")
    for name in files:
        file = Path(name)
        if name == ".github/workflows/publish-release.yml":
            if file.exists():
                file.unlink()
            subprocess.run(["git", "rm", "-f", "--ignore-unmatch", name], check=True)
            print(f"Kept custom release workflow replacement (removed historical {name})")
            continue
        result, count = resolve_diff3(name, file.read_text(encoding="utf-8"))
        if name in EXPECTED and count != EXPECTED[name]:
            raise ValueError(f"{name}: expected {EXPECTED[name]} conflict hunks, got {count}")
        if count == 0:
            raise ValueError(f"{name}: no readable conflicts")
        file.write_text(result, encoding="utf-8", newline="")
        print(f"Resolved {name}: {count} hunk(s), policy={SPECIAL.get(name, 'fork')}")
    for name, fragments in {
        "feature/radar/src/main/java/com/rtbishop/look4sat/feature/radar/TransceiversPage.kt":
            ["CW_CALCULATOR_FREQUENCY_STEPS", "100L to", "250L to", "adjustCalculatorOffset"],
        "feature/radar/src/main/java/com/rtbishop/look4sat/feature/radar/CwConsole.kt":
            ["frequencyStepHz", "onFrequencyStepChange"],
        "core/data/src/main/java/com/rtbishop/look4sat/core/data/framework/L4sController.kt":
            ["effectiveRxMode", "setTrackingFrequencies"],
        "core/data/src/main/java/com/rtbishop/look4sat/core/data/framework/RadioTrackingService.kt":
            ["l4sTrackingTarget", "setTrackingFrequencies"],
        "core/data/src/main/java/com/rtbishop/look4sat/core/data/repository/SettingsRepo.kt":
            ["catRts", "catDtr"],
        "feature/radar/src/main/java/com/rtbishop/look4sat/feature/radar/RadarViewModel.kt":
            ["matchedRadarObserverPosition", "expandMatchedRadarLocalVisibilityWindow"],
        "core/domain/src/main/java/com/rtbishop/look4sat/core/domain/model/AwardCalculator.kt":
            ["waipaCandidatesForGrid", "waipaManualOverrides", "withGridless"],
    }.items():
        value = Path(name).read_text(encoding="utf-8")
        for fragment in fragments:
            if fragment not in value:
                raise ValueError(f"Required integration missing in {name}: {fragment}")
    print("Reviewed CW, L4S CAT, radar, and WAIPA features retained")


if __name__ == "__main__":
    main()
