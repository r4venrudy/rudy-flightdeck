"""Independent standard-library validation of generated report/profile contracts."""
import json
import math
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "build/examples")
report = json.loads((root / "report.json").read_text(encoding="utf-8"))
assert report["schema"] == "rudy-flightdeck/v1"
assert report["summary"]["complete"] is True
assert len(report["summary"]["sha256"]) == 64
assert report["metrics"]["MONITOR"]["count"] > 0
assert report["metrics"]["FILE_WRITE"]["bytes"] >= 32768
assert report["monitorEdges"]
assert not any("flightdeck-synthetic-" in row["resource"] for row in report["resources"])
profile = json.loads((root / "speedscope.json").read_text(encoding="utf-8"))
assert profile["$schema"] == "https://www.speedscope.app/file-format-schema.json"
for item in profile["profiles"]:
    assert item["type"] == "sampled" and item["unit"] in ("none", "bytes")
    assert len(item["samples"]) == len(item["weights"])
    assert sum(item["weights"]) == item["endValue"] - item["startValue"]
    assert all(isinstance(w, int) and w > 0 for w in item["weights"])
    assert all(isinstance(i, int) and 0 <= i < len(profile["shared"]["frames"]) for sample in item["samples"] for i in sample)

def finite(value):
    if isinstance(value, float):
        assert math.isfinite(value)
    elif isinstance(value, dict):
        for child in value.values():
            finite(child)
    elif isinstance(value, list):
        for child in value:
            finite(child)

finite(report)
finite(profile)
html = (root / "report.html").read_text(encoding="utf-8")
assert "Content-Security-Policy" in html and 'type="application/json"' in html
assert "innerHTML" not in html
print("Independent JSON, privacy, weighted-profile, and HTML contract checks passed.")
