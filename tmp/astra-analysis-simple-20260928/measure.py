"""Snapshot the explicitly supplied disposable root; follows no directory links."""
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1])
store = root / "data/store"
files = [Path(directory) / name for directory, _, names in os.walk(store) for name in names]
complete = sorted((root / "data/source/analysis-cache/dependencies").glob("*/complete"))
result = {
    "store_bytes": sum(path.stat().st_size for path in files),
    "store_files": len(files),
    "du_kib": int(subprocess.check_output(["du", "-sk", str(store)]).split()[0]),
    "caches": [
        {"path": str(path.parent), "inode": path.stat().st_ino, "mtime_ns": path.stat().st_mtime_ns}
        for path in complete
    ],
}
Path(sys.argv[2]).write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result))
