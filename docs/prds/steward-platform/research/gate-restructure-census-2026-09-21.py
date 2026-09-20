"""Read complete historical logs; missing durations are never zero samples.

Run from the repository root with python3. No JVM is launched.
"""
from collections import Counter
from pathlib import Path
import hashlib
import math
import re
import statistics
import subprocess

for name in ("gate-step1-2026-09-21.log", "gate-1a-three-suites-2026-09-20.log"):
    path = Path("tmp/orchestrator") / name
    raw = path.read_bytes()
    lines = raw.decode().splitlines()
    begins, ends, samples = [], [], []
    for line in lines:
        if " BEGIN worker=" in line:
            begins.append(line)
        if " END worker=" in line:
            ends.append(line)
            match = re.search(r"END worker=(\S+) elapsed-ms=(\d+) task=(\S+)", line)
            if match:
                samples.append((match[1], match[3], int(match[2])))
    print(name, len(lines), len(raw), hashlib.sha256(raw).hexdigest())
    print("BEGIN/END/timed", len(begins), len(ends), len(samples))
    for worker in ("pool-1", "pool-2", "pool-3", "serial"):
        values = sorted(ms for w, task, ms in samples if w == worker)
        print(worker, "count", len(values), "sum", sum(values),
              "median", statistics.median(values) if values else None,
              "p90", values[math.ceil(.9 * len(values)) - 1] if values else None,
              "max", max(values) if values else None)
    print("coverage", Counter(re.search(r"task=(\S+)", x)[1] for x in begins)
          == Counter(re.search(r"task=(\S+)", x)[1] for x in ends))
    print("terminal", lines[-5:])

subprocess.run(["bb", "-e", r'''
(require '[clojure.edn :as edn])
(let [manifest (edn/read-string
                (slurp "target/test-published-bases/e8cb1a8c76cfe6b393cf4b1a167ff815b1dbd56ef90d15c2373fa7fa53635411/base/manifest.edn"))
      indexed (set (keep :seon.test/sym
                         (mapcat :seon.fn.file/rows (:seon.fn.manifest/artifacts manifest))))
      tasks (map second (re-seq #"BEGIN worker=serial task=(\S+)"
                               (slurp "tmp/orchestrator/gate-step1-2026-09-21.log")))]
  (prn {:indexed (count indexed) :types (frequencies (map type indexed))
        :tasks (count tasks) :string-matches (count (filter indexed tasks))
        :symbol-matches (count (filter indexed (map symbol tasks)))
        :missing (vec (remove indexed (map symbol tasks)))}))
'''], check=True, timeout=30)
