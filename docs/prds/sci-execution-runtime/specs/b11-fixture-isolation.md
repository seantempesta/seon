---
type: prd
status: active
tags: [prd, database]
---

# B11 — containment fixture isolation + first-failure capture

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. **Stopping
early to report is FREE.** If source contradicts this spec, stop and
report.

Authority: `docs/prds/sci-execution-runtime/research/w10-intermittents-investigation-2026-07-22.md`
§B11 — the hypothesis ladder (primary: macOS group-reap outliving the
absence proof; secondary: shared `tmp/seon-containment` socket dir) and
the falsifier design are grounded there.

## Goal (diagnostics unit — NOT a correctness fix)

1. **Fixture isolation**: the B11 test (`test/seon/dev/process_test.clj:370,527`)
   creates unique process/log dirs but falls back to the shared
   repository-global containment socket dir (`process.clj:957`). Give
   the fixture an explicitly unique containment socket directory (the
   mechanism already honors one — find how `spawn-detached!` resolves
   it and use the existing knob; if none exists, STOP AND REPORT
   rather than adding one to production code).
2. **First-failure capture**: when the test observes
   `containment-uncertain`, it must retain the investigation's evidence
   set before cleanup: foreign process record, owner log, result/
   terminal files, PID/PPID/PGID/state, the group-absence probe
   result, and elapsed time inside the wait. Write them to the
   fixture's own dir with a clear README-style header so a future
   session can act without rerunning.
3. Also sweep the stale `tmp/seon-containment/*.sock` entries the
   investigation found IF and only if the owning mechanism has an
   existing cleanup path that should have removed them — report what
   you find; do not hand-delete state you don't understand.

## Owned paths (touch nothing else)

- `test/seon/dev/process_test.clj`
Protected: `script/seon/dev/detach.py` and `process.clj` (the
correctness fix waits for captured evidence — that is the point of this
unit). No commits; the detached-process test IS allowed to run (it
launches its own contained children — that is its normal operation, not
a cluster lifecycle op).

## Gates

The focused B11 test green in a loop of at least 10 runs (record the
count and any capture triggered); then `bin/seon test operator` once
(baseline 296/1656 area — record after).
