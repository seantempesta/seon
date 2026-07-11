#!/usr/bin/env python3
"""Rung-1 namespace-movement oracle over an extracted transcript.

Checks (all must hold for GREEN, exit 0):
  1. movement    — ok evals of (in-ns 'my.units) AND (in-ns 'my.convert)
  2. schema      — an ok register! of :my.units/name after first entering my.units
  3. require     — an ok bare (require '[clojure.string ...]) eval
  4. redefine    — >=2 ok (defn to-feet ...) evals; the LAST carries 3.28084;
                   no parallel my.convert-v2 / to-feet-v2 style fork
  5. report      — a message/user or complete carrying 42.5 and 139.44
Prints one line per check; last line is the verdict (min-drive.sh tails it).
"""
import re, sys

txt = open(sys.argv[1]).read()

# eval blocks: "── eval eid=... ok?=<bool> ... source:\n<source...>" up to blank/next marker
evals = []
for m in re.finditer(r'── eval eid=\S+ id=\S+ ok\?=(\w+).*?\n  source:\n(.*?)(?=\n  (?:result-edn|error|output):)', txt, re.S):
    evals.append((m.group(1) == 'true', m.group(2)))

def ok_evals(pat):
    return [i for i, (ok, src) in enumerate(evals) if ok and re.search(pat, src)]

checks = {}

# in-ns is THE movement verb, but (ns my.units …) also declares-and-moves —
# accept both; the observer reads WHICH was used off the transcript.
units_moves   = ok_evals(r"\(in-ns\s+'my\.units\)|\(ns\s+my\.units[\s)]")
convert_moves = ok_evals(r"\(in-ns\s+'my\.convert\)|\(ns\s+my\.convert[\s)]")
checks['movement'] = bool(units_moves) and bool(convert_moves)

regs = ok_evals(r'register!\s+:my\.units/name')
checks['schema-after-move'] = bool(regs) and bool(units_moves) and regs[0] >= units_moves[0]

checks['bare-require'] = bool(ok_evals(r"\(require\s+'\[clojure\.string"))

defs = ok_evals(r'\(defn\s+to-feet')
last_src = evals[defs[-1]][1] if defs else ''
checks['redefine-in-place'] = len(defs) >= 2 and '3.28084' in last_src
checks['no-parallel-fork'] = not re.search(r'to-feet-v2|to-feet2|my\.convert-v2|my\.convert2', txt)

# The report may be a literal string OR a computed (str …) — score the
# DELIVERED message: the transcript's `▶ to user` rows carry the rendered
# content (runtime-written, so the values are real, never model-typed).
delivered = ' '.join(re.findall(r'▶ to user .*', txt))
checks['report-values'] = ('42.5' in delivered) and \
                          re.search(r'139\.4[34]?', delivered) is not None

for k, v in checks.items():
    print(f"{k}: {'PASS' if v else 'FAIL'}")

if all(checks.values()):
    print("GREEN: ns-movement verified (moved, registered, required, redefined in place, reported)")
    sys.exit(0)
else:
    bad = [k for k, v in checks.items() if not v]
    print(f"RED: failed {bad}")
    sys.exit(1)
