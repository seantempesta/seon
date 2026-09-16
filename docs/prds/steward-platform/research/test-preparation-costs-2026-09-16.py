"""Print exact gate phase, readiness and first-task evidence from supplied logs.

Usage: python3 <this-file> tmp/test-preparation-costs/*.log
This reads logs only; it never launches a gate or changes a store.
"""

from pathlib import Path
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
        elif line.startswith(("bin/test: PREPARED cached base",
                              "bin/test: REUSE cached base",
                              "Ran ")) or " failures, " in line:
            print(line)
    if not found_phase:
        raise ValueError(f"No PHASE evidence in {path}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Supply one or more gate log paths.")
    for argument in sys.argv[1:]:
        summarize(argument)
