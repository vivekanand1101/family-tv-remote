#!/usr/bin/env python3
"""Assert that Family TV Companion starts promptly after Sony TV boot."""

from __future__ import annotations

import os
import re
import subprocess
import sys

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
TARGET = os.environ.get("TV_ADB_TARGET", "192.168.1.100:5555")
# Sony does not deliver LOCKED_BOOT_COMPLETED until roughly 30 seconds into boot.
MAX_START_SECONDS = float(os.environ.get("MAX_COMPANION_START_SECONDS", "45"))


def adb(*args: str) -> str:
    return subprocess.check_output([ADB, "-s", TARGET, *args], text=True)


def duration_seconds(value: str) -> float:
    units = {"d": 86400, "h": 3600, "m": 60, "s": 1, "ms": 0.001}
    total = 0.0
    for amount, unit in re.findall(r"(\d+)(ms|[dhms])", value):
        total += int(amount) * units[unit]
    return total


uptime = float(adb("shell", "cat", "/proc/uptime").split()[0])
services = adb("shell", "dumpsys", "activity", "services", "com.vivekanand.familytvcompanion")
block = re.search(
    r"ServiceRecord\{[^\n]*com\.vivekanand\.familytvcompanion/\.CompanionService\}(.*?)(?:\n\s*\* ServiceRecord|\Z)",
    services,
    re.S,
)
if not block:
    print("FAIL: Family TV Companion service is not running")
    sys.exit(1)
created = re.search(r"createTime=-(\S+)", block.group(1))
if not created:
    print("FAIL: could not determine companion service start time")
    sys.exit(1)
service_age = duration_seconds(created.group(1))
started_after_boot = max(0.0, uptime - service_age)
logs = adb("logcat", "-d", "-v", "raw", "-s", "FamilyTvCompanion:I", "*:S")
ready_values = [int(value) / 1000 for value in re.findall(r"Companion ready at elapsedRealtime=(\d+)", logs)]
ready_after_boot = ready_values[-1] if ready_values else started_after_boot
print(f"TV uptime: {uptime:.1f}s")
print(f"Companion service created: {started_after_boot:.1f}s after boot")
print(f"Companion network server ready: {ready_after_boot:.1f}s after boot")
if ready_after_boot > MAX_START_SECONDS:
    print(f"FAIL: companion became ready later than {MAX_START_SECONDS:.0f}s target")
    sys.exit(1)
print("PASS: companion became available promptly")
