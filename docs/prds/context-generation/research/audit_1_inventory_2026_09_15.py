#!/usr/bin/env python3
"""Reproduce audit-1's dated commit manifest and per-file line churn."""
import collections
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[4]
BASE = "c15d37d551ff15322b53619aa696f35314124d0f"
TIP = "1f18b99fc47f19c8a65a642b472b044b05dc1ea7"
CUTOFF = "2026-09-14 00:00:00 -0600"

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True)

def inventory():
    commits = []
    totals = collections.defaultdict(lambda: [0, 0, 0])
    manifest = git("log", "--reverse", "--since=" + CUTOFF,
                   "--format=%H%x09%s", TIP).splitlines()
    assert manifest, "No commits: audit scope unavailable"
    for line in manifest:
        commit, subject = line.split("\t", 1)
        paths = []
        for row in git("show", "--format=", "--numstat",
                       "--no-renames", commit).splitlines():
            added, removed, path = row.split("\t", 2)
            paths.append(path)
            if added.isdigit():
                totals[path][0] += int(added)
                totals[path][1] += int(removed)
                totals[path][2] += 1
        commits.append({"commit": commit, "subject": subject, "paths": paths})
    expected = git("rev-list", "--reverse", BASE + ".." + TIP).splitlines()
    assert [row["commit"] for row in commits] == expected, "Cutoff scope drift"
    return {"base": BASE, "tip": TIP, "cutoff": CUTOFF,
            "commits": commits,
            "files": [{"path": path, "added": a, "removed": d,
                       "net": a - d, "commits": count}
                      for path, (a, d, count) in sorted(totals.items())]}

if __name__ == "__main__":
    print(json.dumps(inventory(), indent=2))
