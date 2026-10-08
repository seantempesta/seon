---
title: slow-projection findings
type: report
status: active
created: 2026-10-09
---

# slow-projection: why `seon.schema/call-with-projection-state` led health

Lane slow-projection, 2026-10-09, default at HEAD f54e73c83 (pid 40778, booted 06:32 local).

## Symptom

Before the 06:33 restart, `bin/seon status` ranked `seon.schema/call-with-projection-state` first: 261 calls,
mean 5.78 s, self-mean 5.72 s, max 206 s, total 1,509 s (tasks `seon.profile/scope-wrappers-report-callback-time-as-work`
= c4ece4f4eec9, `seon.schema/the-projection-travels-ambiently` = d27179bab996).

## What the function does

It is a `binding` of three dynamic Vars around a callback. Its own work is a few microseconds. Its profile
cell's self time is its elapsed time minus the counted (armed) callees on that thread
(`seon.profile/exited`), so everything the callback does in unarmed code, waiting included, is its "self".

## Measured cause

1. **Flow proc threads were counted as calls.** `seon.flow/graph-executor`'s thread factory ran every
   proc loop's whole life inside the armed wrapper. A thread dump on the fresh JVM (17 calls in flight)
   showed all 17 inside it as virtual threads parked in `clojure.core.async.flow.impl/proc` (impl.clj
   run loop, `alts!!` on its inputs, a `CountDownLatch` await). Probe: one graph-executor thread that
   sleeps 1 s added exactly 1 call and 1,001 ms self to the cell, and the thread saw itself entered in
   the wrapper's cell (`:seon.profile/entered`). So each completed call is a stopped graph's proc
   thread lifetime: the 206 s max is a graph that lived 206 s, and test agents' graphs (seconds each)
   produced the 5.7 s mean. It is idle waiting, not work. Histogram on the fresh JVM: 19 of 106 calls
   over 1 s; p50 34 ms.
2. **Eval-request wrappers carry the unarmed rest of the request.** `seon.sci.eval` (evaluation),
   `seon.turn` (the pass) and `seon.cluster.agent` (source submission) wrap a whole request. One warm
   `(+ 1 2)` on a branch agent: 3 calls, 58 ms self of a 61 ms request. This is real request time, but
   not this function's: it is unattributed work of the callback.

Expected cost of the function itself: one binding push and pop, about 1 µs per call. The reported
5.7 s mean is about six orders of magnitude over it, all callback time.

## Fixed (commit 0094f293b)

`seon.schema/projection-state-bindings` and `seon.schema/projection-bindings` return the Var-binding map
once; the two `call-with-*` wrappers and `seon.flow/graph-executor` install it with `with-bindings`, so a
proc thread lives in its projection world without being one armed call. Proof:
`seon.flow-test/a-proc-thread-lives-in-its-projection-world-without-one-armed-call` (tagged to
`seon.profile/scope-wrappers-report-callback-time-as-work`; at HEAD the live probe showed the thread
entered in the wrapper's cell), plus `seon.flow-world-test` and the two flow join tests: check run
93504c67c7ba, 17 pass, 0 fail. Takes effect at the next restart.

## Still open

- Class 2 above remains: the eval-request wrappers still rank with their callbacks' unarmed time.
  `seon.cluster/projection-executor` also wraps every io command (model streams included) in the armed
  wrapper, so each io task's waits count as its self time. Both close with
  `seon.schema/the-projection-travels-ambiently` (deleting the ambient Vars) or with the profile change
  `seon.profile/scope-wrappers-report-callback-time-as-work` proposes (do not rank callback-taking
  wrappers as work). After the restart the residual should be calls of tens of ms, not seconds.
- Out of scope, filed as `seon.sci.eval/a-trivial-form-makes-fifty-thousand-armed-calls`: a warm `(+ 1 2)`
  on a branch agent answers 53,428 to 53,514 armed calls (61 to 95 ms); a shared profile window
  pointed at per-Var `own-binding?`, `sci-var?` and `ctx?` walks (about 31,700 calls each).
