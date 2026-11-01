#!/usr/bin/env python3
"""Probe public segment lookups; export only status and time-span counts."""

import argparse
import concurrent.futures
import json
import math
from pathlib import Path
import urllib.error
import urllib.request


MAX_RESPONSE_BYTES = 1024 * 1024


def timestamp(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError("Invalid marker timestamp")
    return value


def span_counts(segments, start_key, end_key, allow_open_end=False):
    complete = 0
    open_end = 0
    for segment in segments:
        if not isinstance(segment, dict):
            raise ValueError("Invalid marker object")
        start = timestamp(segment.get(start_key))
        end = segment.get(end_key)
        if end is None and allow_open_end:
            open_end += 1
        elif timestamp(end) > start:
            complete += 1
        else:
            raise ValueError("Marker end must follow its start")
    return complete, open_end


def validate_response(provider, data):
    if not isinstance(data, dict):
        raise ValueError("Expected metadata object")
    if provider == "theintrodb":
        if data.get("type") != "tv" or data.get("season") != 1 or data.get("episode") != 1:
            raise ValueError("Unexpected episode metadata")
        segments = []
        for kind in ("intro", "credits", "recap", "preview"):
            values = data.get(kind, [])
            if not isinstance(values, list):
                raise ValueError("Expected marker array")
            segments.extend(values)
        return span_counts(segments, "start_ms", "end_ms", allow_open_end=True)
    if provider == "introdb":
        if not any(kind in data for kind in ("intro", "outro", "recap")):
            raise ValueError("Missing marker fields")
        return span_counts([data[kind] for kind in ("intro", "outro", "recap") if data.get(kind) is not None],
                           "start_ms", "end_ms")
    items = data.get("items")
    if not isinstance(items, list):
        raise ValueError("Expected version list")
    segments = []
    for item in items:
        if not isinstance(item, dict) or item.get("video_type") != "tv":
            raise ValueError("Unexpected video metadata")
        if item.get("base_video_season", {}).get("season_number") != 1 or item.get("base_video_episode", {}).get("episode_number") != 1:
            raise ValueError("Unexpected episode metadata")
        markers = item.get("marker", [])
        if not isinstance(markers, list):
            raise ValueError("Expected marker list")
        segments.extend(markers)
    return span_counts(segments, "time_start", "time_end")


def probe(provider, url, payload=None):
    headers = {"Accept": "application/json", "User-Agent": "TV-Community-Skip-Contract-Check/1.0"}
    body = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers=headers)
    result = {"provider": provider, "available": False, "contractVerified": False}
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            result["httpStatus"] = response.status
            content = response.read(MAX_RESPONSE_BYTES + 1)
        if len(content) > MAX_RESPONSE_BYTES:
            raise ValueError("Response exceeds metadata limit")
        complete, open_end = validate_response(provider, json.loads(content))
        result.update(available=True, contractVerified=True, completeSpans=complete, openEndedSpans=open_end)
    except urllib.error.HTTPError as error:
        result["httpStatus"] = error.code
        result["availabilityReason"] = "no_coverage" if error.code == 404 else "http_error"
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        result["availabilityReason"] = "network_error"
        result["errorType"] = type(error).__name__
    except (ValueError, TypeError, AttributeError) as error:
        result.update(available=True, contractError=type(error).__name__)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="artifacts/community-skip/provider-smoke.json")
    arguments = parser.parse_args()
    checks = [
        ("theintrodb", "https://api.theintrodb.org/v4/media?tmdb_id=1396&season=1&episode=1&duration_ms=3500000&details=true", None),
        ("introdb", "https://api.introdb.app/segments?imdb_id=tt0903747&season=1&episode=1", None),
        ("theotherdb", "https://playback.theotherdb.org/api/metadata", {
            "platform_type": "tmdb_id_tv", "platform_value": "226045", "season_number": 1, "episode_number": 1,
            "includes": ["base_video_list", "base_video_season", "base_video_episode", "marker"], "page": 1, "page_size": 30,
        }),
    ]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        futures = [executor.submit(probe, *check) for check in checks]
        results = [future.result() for future in futures]
    report = {"providers": results, "passed": not any("contractError" in result for result in results)}
    output = Path(arguments.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if not report["passed"]:
        raise SystemExit("A responding provider returned an incompatible segment schema")


if __name__ == "__main__":
    main()
