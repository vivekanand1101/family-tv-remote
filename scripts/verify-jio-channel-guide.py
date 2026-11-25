#!/usr/bin/env python3
"""Verify the curated shortcuts against Jio's public channel-guide API.

This is a development-time check only. The mobile app keeps a static catalogue
and does not call Jio or any other cloud service at runtime.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CHANNELS_SOURCE = ROOT / "mobile" / "src" / "channels.ts"
GUIDE_URL = "https://www.jio.com/jcms-api/master-lookups?" + urlencode(
    {"filters[label][$eq]": "AIRFIBER_CHANNELS"}
)
NAME_ALIASES: dict[str, set[str]] = {}


def load_shortcuts() -> list[dict[str, object]]:
    source = CHANNELS_SOURCE.read_text(encoding="utf-8")
    shortcuts: list[dict[str, object]] = []
    for body in re.findall(r"\{ id:.*?\}", source):
        strings = {
            key: match.group(1)
            for key in ("id", "name", "contentId")
            if (match := re.search(rf"{key}: '([^']*)'", body))
        }
        number = re.search(r"channelNumber: (\d+)", body)
        if len(strings) != 3 or number is None:
            raise RuntimeError(f"Could not parse channel entry: {body}")
        shortcuts.append({**strings, "channelNumber": int(number.group(1))})
    return shortcuts


def load_guide() -> tuple[str, list[dict[str, object]]]:
    request = Request(GUIDE_URL, headers={"Accept": "application/json", "User-Agent": "Family-TV-Remote/1.0"})
    with urlopen(request, timeout=30) as response:
        payload = json.load(response)
    attributes = payload["data"][0]["attributes"]
    return attributes["updatedAt"], attributes["value"]["channelsData"]["data"]


def image_content_id(channel: dict[str, object]) -> str | None:
    match = re.search(r"/(\d+)_\d+_", str(channel.get("image", "")))
    return match.group(1) if match else None


def main() -> int:
    shortcuts = load_shortcuts()
    updated_at, guide = load_guide()
    by_number = {int(channel["channelNumber"]): channel for channel in guide}
    failures: list[str] = []

    print(f"Jio guide updated: {updated_at}")
    print(f"Guide entries: {len(guide)}; curated shortcuts: {len(shortcuts)}\n")
    for shortcut in shortcuts:
        channel_id = str(shortcut["id"])
        number = int(shortcut["channelNumber"])
        guide_channel = by_number.get(number)
        if guide_channel is None:
            failures.append(f"{channel_id}: channel {number} is absent")
            continue

        expected_content_id = str(shortcut["contentId"])
        actual_content_id = image_content_id(guide_channel)
        allowed_names = NAME_ALIASES.get(channel_id, {str(shortcut["name"])})
        guide_name = str(guide_channel["name"])
        status = "OK"
        if actual_content_id != expected_content_id:
            failures.append(
                f"{channel_id}: content ID {expected_content_id} != guide {actual_content_id}"
            )
            status = "FAIL"
        if guide_name not in allowed_names:
            failures.append(
                f"{channel_id}: name {shortcut['name']!r} != guide {guide_name!r}"
            )
            status = "FAIL"
        print(f"{status:4}  {number:4}  {expected_content_id:6}  {guide_name}")

    if failures:
        print("\nGuide verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("\nAll curated shortcuts match Jio's current guide by channel number and content ID.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
