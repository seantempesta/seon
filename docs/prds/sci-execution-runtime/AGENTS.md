---
type: reference
status: active
tags: [prd, agent, architecture]
---

# Sci execution-runtime chunk runbook

`roadmap.md` owns the exploration: variants B (sci-JIT Bun child) and C
(JVM sci host), ordered B1 -> B2 -> C1 -> C2 -> decision gate. Evidence
lives in the source-cleanup research docs; the reproducible harness is
`tmp/sci-probe/`.

Rules: experiments stay in the harness or a branch cluster — production
`seon.eval`/`seon.execution` are untouched until the decision gate; sci
enters deps.edn only when B1 needs it, pinned to the JIT commit; no
second eval registry — defs persist through the one program-graph
corpus; the js-bound tier rule must be computed from program
requires/interop, never a hand-maintained list.
