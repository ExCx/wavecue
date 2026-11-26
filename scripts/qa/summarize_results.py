#!/usr/bin/env python3
"""Summarize instrumented-test results for CI.

Reads the JUnit XML written by ``connectedDebugAndroidTest`` and prints one
GitHub annotation per failed test, plus a compact summary. Annotations are
readable through the public checks API even when the raw job log is not, which
is what makes a red CI run debuggable from a headless session.

If no XML was produced at all, the captured Gradle log is echoed as an
annotation instead, so a build/install failure is still visible.
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET


def emit(level: str, title: str, message: str) -> None:
    message = " ".join((message or "").split())
    if len(message) > 3500:
        message = message[:3500] + " …"
    # The workflow-command format is read by the Actions runner and turned into
    # a check annotation; plain stdout still works when run locally.
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::{level} title={title}::{message}")
    else:
        print(f"[{level}] {title}: {message}")


def main() -> int:
    patterns = [
        "app/build/outputs/androidTest-results/connected/**/*.xml",
        "app/build/outputs/androidTest-results/**/*.xml",
    ]
    files: list[str] = []
    for pattern in patterns:
        files.extend(glob.glob(pattern, recursive=True))
    files = sorted(set(files))

    total = failures = errors = skipped = 0
    failed_cases = 0

    for path in files:
        try:
            root = ET.parse(path).getroot()
        except Exception as exc:  # noqa: BLE001 - report, never crash the reporter
            emit("warning", "unreadable result file", f"{path}: {exc}")
            continue
        total += int(root.get("tests", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))
        for case in root.iter("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                failed_cases += 1
                name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
                detail = bad.get("message") or bad.text or "no detail"
                emit("error", f"instrumented failure: {name}", detail)
            # JUnit also records a skipped element for @Ignore
            _ = case.findall("skipped")

    print(
        f"instrumented results: files={len(files)} tests={total} "
        f"failures={failures} errors={errors} skipped={skipped}"
    )

    if not files:
        emit(
            "error",
            "no instrumented test results",
            "connectedDebugAndroidTest produced no JUnit XML; inspect the runner log.",
        )
        log = "build/connected.log"
        if os.path.exists(log):
            tail = open(log, encoding="utf-8", errors="replace").read().splitlines()[-80:]
            emit("error", "gradle tail", "\n".join(tail))
        return 1

    return 1 if failed_cases or failures or errors else 0


if __name__ == "__main__":
    sys.exit(main())
