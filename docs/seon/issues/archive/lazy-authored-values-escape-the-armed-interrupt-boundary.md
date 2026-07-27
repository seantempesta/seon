---
type: issue
status: superseded
severity: blocker
tags: [issue, runtime, agent]
---

# Realize authored values deeply before disarming the interrupt

## Problem

`seon.sci.eval/evaluate` returns the value SCI produced and cancels the
time-limit timer before returning. A lazy value therefore leaves the armed
boundary unrealized, and whoever realizes it later runs authored code with **no
`:interrupt-fn`, no `time-limit`, and no `:compute` permit**.

This is a correctness hole in containment, not a performance question. The
owner's requirement for authored render is explicit: *"If an agent writes hiccup
with an infinite loop we are to detect it and to kill it and tell the agent they
fucked up without it crashing the system or locking everything up."* An eager
infinite loop is caught. The lazy form of the same mistake is not.

## Evidence

Measured 2026-07-26 by the `render-design` investigation; full conditions in
[[../../prds/sci-execution-runtime/research/jvm-render-design-2026-07-26]].

- The probe returned `clojure.lang.LazySeq` with **zero** authored callback
  invocations inside evaluation, and one invocation when the value was realized
  **outside** it.
- `seon.sci.eval/evaluate` returns the raw value and cancels the timer in its
  `finally` before returning (`src/seon/sci/eval.clj:108-129`).
- Both later realizers are outside the boundary: the canvas structural check
  (`src/seon/render/canvas.cljc:209-277`) and the HTML serializer
  (`src/seon/ui/html.cljc:292-312`). Hiccup accepts seq children by design.
- For contrast, the eager case is contained correctly: an infinite loop in an
  authored canvas render was killed after 55 ms at 9,639,035 function entries,
  every consumer received the error morph, and the server stayed healthy.

Adjacent gap, same fault path: the driver's terminal receipt records only
duration and drops `:seon.eval/fn-entries` and `:seon.eval/allocated-bytes`
(`src/seon/agent/driver.clj:151-164`). So even when a renderer *is* killed,
there is no end-to-end path to an agent-visible fault carrying the
spin-versus-blocked diagnostic — 9.6M entries in 55 ms reads as a spin, 12
entries reads as blocked in a host call, and neither reaches the agent.

## Owner

`seon.sci.eval` owns the armed boundary and is the only correct place to close
this. The realizers are consumers and must not each grow their own guard — that
would be a second containment mechanism, which this program is deleting.

## Acceptance

- A deep realization walk runs **inside** the armed boundary, before the timer
  is cancelled, over every supported collection including lazy seqs, and is
  subject to the same `time-limit` and output cap as the evaluation itself.
- An authored value containing an infinite lazy seq is killed with the
  `time-limit` outcome and never reaches a renderer.
- The realization is a **total** operation at one choke point, not a per-call-site
  check: a value that cannot be realized within the limit is a flat
  `:seon/error`, never a partially realized structure.
- The terminal receipt carries `fn-entries` and allocated bytes so the agent's
  fault message can distinguish a spin from a blocked host call.
- One recurring regression under `test/` claimed by `bin/test-writer` asserts
  that no lazy value crosses the boundary unrealized.

Related: [[../../prds/sci-execution-runtime/research/jvm-render-design-2026-07-26]],
[[http-kit-streaming-writes-have-an-unbounded-socket-queue]].

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
