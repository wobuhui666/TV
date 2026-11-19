#!/usr/bin/env python3
"""Collect full Android lint findings and gate regressions in the calendar task."""

import argparse
from collections import Counter
import importlib.util
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
BASELINE_REPORT = REPOSITORY_ROOT / ".github/testing/airing-calendar-lint-baseline.xml"
CURRENT_REPORT = "app/build/reports/lint-results-leanbackArm64_v8aDebug.xml"
OUTPUT_REPORT = "artifacts/airing-calendar/lint/comparison.json"
ERROR_SEVERITIES = {"Error", "Fatal"}
VALID_SEVERITIES = ERROR_SEVERITIES | {"Warning", "Information", "Hint", "Ignore"}
TASK_PREFIXES = (
    "app/src/main/java/com/fongmi/android/tv/calendar/",
    "app/src/leanback/java/com/fongmi/android/tv/ui/calendar/",
)
TASK_FILES = {
    "app/src/main/java/com/fongmi/android/tv/api/TmdbLibraryApi.java",
    "app/src/main/java/com/fongmi/android/tv/bean/LibraryMedia.java",
    "app/src/leanback/AndroidManifest.xml",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/AiringCalendarActivity.kt",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LibraryDetailActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/library/LibraryDetailScreen.kt",
    "app/src/leanback/java/com/fongmi/android/tv/ui/library/LibraryDetailState.kt",
    "app/src/leanback/res/layout/activity_airing_calendar.xml",
    "app/src/leanback/res/values/strings_calendar.xml",
    "app/src/leanback/res/values-zh-rCN/strings_calendar.xml",
    "app/src/leanback/res/values-zh-rTW/strings_calendar.xml",
    "app/src/leanback/res/values/strings_library_detail.xml",
    "app/src/leanback/res/values-zh-rCN/strings_library_detail.xml",
    "app/src/leanback/res/values-zh-rTW/strings_library_detail.xml",
}


def comparator():
    filename = Path(__file__).with_name("compare-lint-reports.py")
    spec = importlib.util.spec_from_file_location("airing_calendar_lint_comparator", filename)
    if spec is None or spec.loader is None:
        raise ValueError("Cannot load the repository's lint comparison helper")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_report(filename):
    if not filename.is_file():
        raise ValueError(f"Lint report is missing: {filename}")
    root = ET.parse(filename).getroot()
    if root.tag != "issues" or not root.get("format") or not root.get("by", "").startswith("lint "):
        raise ValueError("Expected a complete Android lint XML issues report")
    for issue in root.findall("issue"):
        if not issue.get("id") or issue.get("severity") not in VALID_SEVERITIES or "message" not in issue.attrib:
            raise ValueError("Android lint report contains an incomplete finding")
    return root


def touches_task(issue):
    # Secondary locations matter, especially for translations and manifest findings.
    for location in issue["locations"]:
        filename = location["file"].replace("\\", "/")
        if filename in TASK_FILES or filename.startswith(TASK_PREFIXES):
            return True
    return False


def compare(current_report, current_root):
    baseline_document = validate_report(BASELINE_REPORT)
    validate_report(current_report)
    helper = comparator()
    baseline = helper.read_report(BASELINE_REPORT, REPOSITORY_ROOT)
    current = helper.read_report(current_report, current_root)
    added = helper.new_entries(current, baseline)
    new_errors = [issue for issue in added if issue["severity"] in ERROR_SEVERITIES]
    task_errors = [issue for issue in new_errors if touches_task(issue)]
    return {
        "baselineProvenance": {
            "kind": "historical-report",
            "date": baseline_document.get("baselineDate"),
            "head": baseline_document.get("baselineHead"),
            "workspace": baseline_document.get("baselineWorkspace"),
            "cleanCommitBaseline": False,
            "lint": baseline_document.get("by"),
            "coverage": "Historical Error/Fatal findings only; warning history is not bundled.",
        },
        "baselineCounts": dict(Counter(issue["severity"] for issue in baseline)),
        "currentCounts": dict(Counter(issue["severity"] for issue in current)),
        "newErrors": new_errors,
        "newTaskScopeErrors": task_errors,
        "newOutOfScopeErrors": [issue for issue in new_errors if not touches_task(issue)],
        "newWarnings": [issue for issue in added if issue["severity"] == "Warning"],
        "warningComparison": "Current warnings are unmatched because the historical bundle contains errors only; they are not attributed regressions.",
        "passedTaskScope": not task_errors,
        "fullLintPassed": not any(issue["severity"] in ERROR_SEVERITIES for issue in current),
        "taskScope": {"packages": list(TASK_PREFIXES), "files": sorted(TASK_FILES)},
    }


def resolve_path(filename, root):
    path = Path(filename)
    return path if path.is_absolute() else root / path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--current-report", default=CURRENT_REPORT)
    parser.add_argument("--current-root", default=os.environ.get("GITHUB_WORKSPACE", str(REPOSITORY_ROOT)))
    parser.add_argument("--output", default=OUTPUT_REPORT)
    arguments = parser.parse_args()
    current_root = Path(arguments.current_root).resolve()
    try:
        summary = compare(resolve_path(arguments.current_report, current_root), current_root)
    except (OSError, ValueError, ET.ParseError) as error:
        raise SystemExit(f"Cannot verify calendar lint: {error}") from error
    output = resolve_path(arguments.output, current_root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({
        "currentCounts": summary["currentCounts"],
        "newTaskScopeErrors": len(summary["newTaskScopeErrors"]),
        "newOutOfScopeErrors": len(summary["newOutOfScopeErrors"]),
        "passedTaskScope": summary["passedTaskScope"],
        "fullLintPassed": summary["fullLintPassed"],
        "comparison": str(output),
    }, ensure_ascii=False))
    if not summary["passedTaskScope"]:
        raise SystemExit("Android lint found errors introduced in the calendar/follow task")


if __name__ == "__main__":
    main()
