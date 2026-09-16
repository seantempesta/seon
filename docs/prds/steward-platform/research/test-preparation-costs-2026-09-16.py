"""Print exact gate phase, readiness and first-task evidence from supplied logs.

Usage: python3 <this-file> tmp/test-preparation-costs/*.log
This reads logs only; it never launches a gate or changes a store.
"""

from pathlib import Path
import hashlib
import sys


def summarize(path):
    print(f"\n{path}")
    first_tasks = set()
    found_phase = False
    for line in Path(path).read_text().splitlines():
        if line.startswith("bin/test: PHASE "):
            print(line)
            found_phase = True
        elif line.startswith("bin/test: WORKER READY "):
            print(line)
        elif " END worker=" in line:
            worker = line.split(" END worker=", 1)[1].split()[0]
            if worker not in first_tasks:
                print(line)
                first_tasks.add(worker)
        elif line.startswith("bin/test: SOURCE") and not line.startswith("bin/test: SOURCE WARNING"):
            print(line)
        elif line.startswith(("bin/test: PUBLISH cached base",
                              "bin/test: PREPARED cached base",
                              "bin/test: REUSE cached base",
                              "Ran ")) or " failures, " in line:
            print(line)
    if not found_phase:
        raise ValueError(f"No PHASE evidence in {path}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Supply one or more gate log paths.")
    if sys.argv[1] == "--fingerprint":
        base = Path(sys.argv[2])
        digest = hashlib.sha256()
        files = sorted(path for path in base.rglob("*") if path.is_file())
        if not files:
            raise ValueError(f"No published base files in {base}")
        for path in files:
            digest.update(str(path.relative_to(base)).encode())
            digest.update(b"\0")
            digest.update(hashlib.sha256(path.read_bytes()).digest())
        print(f"{base}: files={len(files)} sha256={digest.hexdigest()}")
    else:
        for argument in sys.argv[1:]:
            summarize(argument)
