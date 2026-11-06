#!/usr/bin/env python3
"""Fail on new Android lint errors while preserving reports of existing debt."""

import argparse
from collections import Counter
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def read_report(filename, source_root):
    root = ET.parse(filename).getroot()
    if root.tag != "issues":
        raise ValueError("Expected an Android lint XML issues report")
    source_root = str(Path(source_root).resolve()) + "/"
    entries = []
    for issue in root.findall("issue"):
        locations = []
        for location in issue.findall("location"):
            filename = location.get("file", "").replace(source_root, "")
            locations.append({"file": filename, "line": location.get("line")})
        entries.append({
            "id": issue.get("id"),
            "severity": issue.get("severity"),
            "message": issue.get("message", "").replace(source_root, ""),
            "locations": locations,
        })
    return entries


def fingerprint(issue):
    # Compare occurrences without line numbers: insertions can move existing findings.
    # Keep the count so a second occurrence of an existing error still fails the gate.
    return (issue["id"], issue["severity"], issue["message"],
            tuple(location["file"] for location in issue["locations"]))


def new_entries(current, baseline):
    remaining = Counter(fingerprint(issue) for issue in baseline)
    added = []
    for issue in current:
        key = fingerprint(issue)
        if remaining[key]:
            remaining[key] -= 1
        else:
            added.append(issue)
    return added


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline-report", required=True)
    parser.add_argument("--current-report", required=True)
    parser.add_argument("--baseline-root", required=True)
    parser.add_argument("--current-root", required=True)
    parser.add_argument("--baseline-sha", required=True)
    parser.add_argument("--current-sha", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    baseline = read_report(args.baseline_report, args.baseline_root)
    current = read_report(args.current_report, args.current_root)
    added = new_entries(current, baseline)
    new_errors = [issue for issue in added if issue["severity"] in ("Error", "Fatal")]
    summary = {
        "baselineSha": args.baseline_sha,
        "currentSha": args.current_sha,
        "baselineCounts": dict(Counter(issue["severity"] for issue in baseline)),
        "currentCounts": dict(Counter(issue["severity"] for issue in current)),
        "newErrors": new_errors,
        "newWarnings": [issue for issue in added if issue["severity"] == "Warning"],
        "passed": not new_errors,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    if new_errors:
        raise SystemExit("Android lint found errors introduced after the baseline commit")


if __name__ == "__main__":
    main()
