---
type: issue
status: open
severity: performance
created: 2026-09-23
tags: [issue, turn, performance, sub-second]
---

# A one-form resume turn takes seconds

## Problem and evidence

Resuming a turn whose only form is `(+ 1 2)` takes 3,555–6,151 ms on a scratch root at HEAD 0f7321802 (turn-stability lane, `docs/prds/agent-platform/landing/lane-turn-stability-2026-09-23.md`; evidence `tmp/turn-stability/evidence/`). Phases: `read-evidence` 2.1 s, `installation-covers-program-change?` 1.3 s, `acquire!` 1.5 s, evaluate 1.2 s. AGENTS.md: anything not sub-second is a defect; a one-form evaluation has no input proportional to the program.

## Owners

`seon.db` (read evidence; 255ecd14c narrowed index checks to changed attributes — re-measure), `seon.sci.eval` (acquisition and installation check; M9 03bd7cfc9 — re-measure). Schedule row 24al.
