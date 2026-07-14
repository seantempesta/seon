---
type: prd
status: planned
tags: [prd, flow, database, web, agent]
---

# Local performance graduation roadmap

## Outcome

The complete local system passes one destructive, reproducible acceptance
matrix with explicit latency/resource budgets, then safely retires every
superseded lane whose evidence and data have been preserved and read back.

## Current state

Unit 0 has a green default reset, full operator/writer/pod/offline-Inspect gates,
CLJ+CLJS MCP read-back, browser/static checks, gzip frames, restart, database
budgets, and retained-result proof. Those are a checkpoint, not final
graduation: successor behavior, grown-database budgets, full interaction
journeys, paid/simple-model evidence, downstream packaging, and authorized lane
retirement remain.

## Ordered work

1. Reconcile units 1–8 and refuse the final run until every predecessor
   acceptance matrix and open blocker has an explicit disposition.
2. Define cold/warm distributions and budgets for startup/readiness, REPL/MCP,
   agent turns, database operations, feed/render work, browser interaction,
   event-loop delay, heap/RSS, disk, and idle CPU.
3. Run the destructive fresh/grown/restart/crash/history/downstream/agent/model/
   browser matrix using production doors and retain machine-readable evidence.
4. Investigate every regression at its owner; rerun only affected slices, then
   one non-overlapping final matrix.
5. With explicit owner authorization, stop, preserve, verify, read back, and
   remove eligible legacy processes/worktrees/data; prove current clusters are
   unaffected.

## Graduation

- Units 1–8 are complete with no deferred blocker hidden in this PRD.
- Every defined correctness and performance budget is green across retained
  cold/warm samples, including grown database and simultaneous cluster use.
- The real-browser journey covers root, ordinary agent, canvas controls,
  sessions, database history, reconnect, restart, and failure recovery.
- Released artifacts support the downstream no-source journey.
- Authorized cleanup preserves all required evidence/data and leaves only the
  intended current processes, worktrees, branches, and artifacts.
