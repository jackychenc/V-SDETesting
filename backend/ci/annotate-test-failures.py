#!/usr/bin/env python3
"""Emit GitHub Actions ::error:: annotations for every failing JUnit test class.

Parses build/test-results/test/TEST-*.xml and, for each <testcase> with a <failure>/<error>,
prints the class#method + the exception message/type + the first stack frame inside our code
(com.vgc.tms.*) — the smoking gun. Per-class output keeps M1 / RBAC / security failures
disambiguated (the class name is in each line) while the full suite still runs as one gate,
so nothing outside a package glob is silently skipped. Annotations are readable via the PUBLIC
check-runs API even when logs (403) / artifacts (401) are not, so agents can self-diagnose red CI.
"""
import glob
import sys
import xml.etree.ElementTree as ET

found = False
for path in sorted(glob.glob("build/test-results/test/TEST-*.xml")):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    if int(root.get("failures", "0")) == 0 and int(root.get("errors", "0")) == 0:
        continue
    cls = root.get("name")
    for tc in root.iter("testcase"):
        for fe in list(tc.findall("failure")) + list(tc.findall("error")):
            found = True
            msg = (fe.get("message") or fe.get("type") or "").strip().replace("\n", " ")
            frame = next(
                (ln.strip() for ln in (fe.text or "").splitlines() if "com.vgc.tms" in ln),
                "",
            )
            frame = frame[3:].strip() if frame.startswith("at ") else frame
            print(f"::error::{cls}#{tc.get('name')} :: {msg[:240]}")
            if frame:
                print(f"::error::  at {frame[:200]}")

if not found:
    # tests failed but no per-testcase failure/error surfaced (e.g. context-load / worker crash) —
    # don't exit silently; point the reader at the raw log tail.
    print("::error::tests failed but no <failure>/<error> found in result XML — see console log tail")
sys.exit(0)
