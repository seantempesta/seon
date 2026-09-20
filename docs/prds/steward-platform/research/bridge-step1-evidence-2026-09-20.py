"""Reproduce the dated step-1 failure inventory from a canonical fast log.

Usage: python3 <this-file> tmp/bridge-step1-after-corrected.log > <inventory.tsv>
Allocation samples: python3 <this-file> --allocations <jfr-print-text>
Categories identify the observed boundary, not an inferred root cause. In
particular, an error in a HEAD file does not implicate its dirty checkout bytes.
"""
import csv
from collections import Counter
from pathlib import Path
import re
import sys


if sys.argv[1] == "--allocations":
    # jfr print --events jdk.ObjectAllocationSample --stack-depth 64 <recording>
    # The dated interval is the test's BEGIN/END, converted from UTC to JFR's
    # recorded -06:00 offset. Sample weights are estimates, not measured bytes.
    owners = Counter()
    for block in Path(sys.argv[2]).read_text().split("jdk.ObjectAllocationSample {"):
        timestamp = re.search(r"startTime = (\S+)", block)
        weight = re.search(r"weight = ([0-9.]+) (\w+)", block)
        if (not timestamp or not weight or 'eventThread = "main"' not in block
                or not "23:47:30.645" <= timestamp[1] < "23:47:33.279"):
            continue
        frames = [line.strip() for line in block.splitlines()
                  if "seon." in line and "(" in line]
        owners[frames[0] if frames else "no Seon frame"] += float(weight[1]) * {
            "bytes": 1, "kB": 1024, "MB": 1048576, "GB": 1073741824}[weight[2]]
    for frame, weight in owners.most_common():
        print(round(weight), frame, sep="\t")
    sys.exit(0)


lines = Path(sys.argv[1]).read_text().splitlines()
writer = csv.writer(sys.stdout, delimiter="\t", lineterminator="\n")
writer.writerow(["event", "test", "log_line", "category", "boundary_path",
                 "location", "observation"])
namespace = ""
for index, line in enumerate(lines):
    if "BEGIN namespace " in line:
        namespace = line.split("BEGIN namespace ", 1)[1]
    match = re.match(r"(FAIL|ERROR) in \(([^)]+)\) \(([^)]+)\)", line)
    if not match:
        continue
    event, test, location = match.groups()
    block = []
    for following in lines[index + 1:]:
        if following.startswith("bin/test:") or re.match(r"(FAIL|ERROR) in ", following):
            break
        block.append(following)
    expected = next((s[10:] for s in block if s.startswith("expected: ")), "")
    actual = next((s[8:] for s in block if s.startswith("actual: ")), "")
    observation = actual if event == "ERROR" else expected
    path = "test/" + namespace.replace(".", "/").replace("-", "_") + ".clj"
    category = "outside-step-1 observed boundary; cause unproved"
    owner = re.search(r"(?:ExceptionInfo: |write: )(seon\.[\w.-]+|my\.[\w.-]+)/[\w!?*-]+ (?:refused|returned)", actual)
    if owner:
        candidate = "src/" + owner[1].replace(".", "/").replace("-", "_") + ".clj"
        if Path(candidate).exists():
            path = candidate
    if test in {"registry-generation-operation-counts",
                "schema-and-contract-declarations-have-bounded-allocation",
                "config-loads-the-packaged-population-in-a-fresh-jvm"}:
        category = "step-1 measurement or construction; corrected"
    elif "seon.turn/disposition refused projection" in actual:
        category = "step-1 caller custody; corrected"
    elif event == "FAIL" and any(s in expected for s in
                                  [":seon.error/kind", ":seon.instrument/contract-violated",
                                   ":seon.error/class"]):
        category = "stale retired-field expectation"
    elif "expected a namespaced symbol, got a string" in actual or (
            "seon.error/kind" in actual and "expected an installed attribute" in actual):
        category = "stale fixture declaration"
    elif test in {"public-walk-is-callable-through-an-agent-sci-eval",
                  "a-selected-render-inherits-the-live-arm-or-owns-one-when-unarmed"}:
        category = "unresolved performance/render boundary; no baseline attribution"
    elif namespace == "seon.db-test" or path == "src/seon/db.clj":
        category = "foreign boundary: database/error conversion (1a)"
    elif namespace == "seon.fn-test" and test in {
            "a-refused-reference-read-refuses-gate-set-derivation",
            "analyze-form-refuses-an-unresolvable-namespace-reference"}:
        category = "foreign boundary: held fn producer; fixture base corrected"
        path = "src/seon/fn.clj"
    elif test == "semantic-admission-explicitly-declares-every-error-facet":
        category = "foreign boundary: error facet census"
        path = "src/seon/sci/admit.clj;src/seon/error.clj"
    elif test == "declared-reference-maps-accept-the-pull-reference-grammar":
        category = "baseline failure: ambiguous-error candidate fixture"
    writer.writerow([event, namespace + "/" + test, index + 1, category,
                     path, location, observation[:600]])
