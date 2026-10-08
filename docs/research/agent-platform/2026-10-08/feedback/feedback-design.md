---
type: design
status: proposed
created: 2026-10-08
lane: feedback-design
---

# The reply says what the eval request cost

What exists: each form's reply map carries `:seon.repl/ms` (the kernel arm's evaluation window,
`seon.sci.kernel/record`) and `:seon.repl/alloc-mb` (the same window's allocation; on a virtual thread every
thread's allocation). The eval request around those forms (MCP decode, submit, the turn proc's pass: read, evaluate,
derive, apply, the one recording transaction, the automatic test step, then the print in `seon.mcp/observed`) is
measured in three places and shown in none: `seon.batch/evaluate-batch` already returns `:seon.batch/timings` per stage
and `:seon.batch/total-ms`, and the world's call sink (`seon.batch/sink`, filled by `seon.profile/timed`) already holds
calls, nanoseconds and self nanoseconds per executed definition for the batch's world. Both are dropped by
`seon.turn/evaluate-sources`. Principal approach: fold what the owners already count into one cost value on the
pass's reply, at the turn proc (the one place the whole eval request runs), and print it as one more reply item.
No new timer, no new counter, no new printer.

## 1. Broken first (measured on default, 2026-10-08, JDK 26.0.1, archive a5fc4cb49)

Independent measure: `seon.profile/measure` around `seon.mcp/call` from a platform thread (wall, JVM-wide armed calls
and reads), the profile cells' totals for the stage functions, and a JFR `jdk.ObjectAllocationSample` recording
summed per thread (each thread's first sample dropped, since its weight reaches back before the recording). Agent
`feedback-design-probe`, SCI mode. Probe forms are in the appendix.

| Eval request | Reply `:ms` | Request wall | Stages (cells, ms) | Armed calls / reads | Reply `:alloc-mb` | JFR, agent's virtual thread | JFR, platform threads |
|---|---|---|---|---|---|---|---|
| `:ping` (quiet) | 0 | 92-123 ms | turn 47, test step 48, print ~1 | 12,050-12,686 / 125-128 | 0.0 | 110-114 MB | 26-29 MB (other lanes included) |
| `:ping` (JFR on) | 0 | 343 ms | turn 122, test step 208 | 12,173 / 126 | 0.0 | 113 MB | 30 MB |
| `(count (seon.db/q … :seon.fn/sym …))` | 4 | 571 ms | turn 235, test step 303 | 19,166 / 125 | 10.1 | 126 MB | 44 MB |
| `(in-ns 'fd.scratch)` + one contracted `defn` | 0, 1 | 3,822 ms | turn 3,325, test step 488 | 45,619 / 256 | 0.0, 0.7 | 1,173 MB | 111 MB |

So the reply under-reports the eval request by two to four orders of magnitude: `:ms 1` for a 3.8 s request, `:alloc-mb
0.7` for 1.17 GB. The agent cannot see that its defn cost 3.8 s, nor that a `:ping` allocates ~110 MB.

JFR calibration (is the per-virtual-thread sum trustworthy?): over the `:ping` baseline of 114.6 MB, a form allocating
one 100,000,016-byte array measured 216.5 MB (+101.9) and a 200,000,016-byte array 315.8 MB (+201.2). The sampled sum
tracks a known allocation within 2% on a long eval request. Sol (`alloc-sol.md`) is right that a short window may hold
no sample, so JFR is a diagnosis tool, never a reply field or a gate.

Where the `:ping` allocation goes (JFR stacks, innermost Seon frame, agent thread, 112.8 MB): `seon.db/vector-query-call`
82.8 MB (Datahike query execution under `seon.db/with-declarations`, 48 `seon.db/q` calls, 41 ms), context regeneration
(`seon.sci.eval/regenerate-agent-context!`, `seon.sci.eval/base-bindings`) 12.6 MB, pull plans 3 MB, test digest
differences 3 MB. JFR's 64-frame stack depth hides which query; finding it is the next diagnosis step (§7).

Also measured: the reply's per-form `:alloc-mb` was right (100.0 for the 100 MB array) only because the JVM was quiet;
under concurrent lanes the same window reported 0.4 to 23 GB (root). The `:slow` finding fired at 1,718 ms and 5,164 ms
on my own JVM-mode probe forms, correctly, but its fix text sends the agent to the whole-process
`(seon.profile/summary (seon.profile/cells) 5)`, which ranks every lane's work.

## 2. The cost shape

One map, `:seon.repl/request`, printed as the eval request's last reply item (after the forms and the test summary),
built by the turn proc from values it already holds. Every key present is a measurement of this eval request; an
unavailable one is absent, with its reason in `:seon.cost/unmeasured` (data, never a 0 or a JVM total).

```clojure
#:seon.repl{:request
 #:seon.cost{:ms 3822                       ; wall, request accepted by the proc -> reply built (incl. print)
             :stages {:read 0.3 :evaluate 1.4 :derive 2.1 :facts 0.8 :apply 0.0
                      :settle 61.0 :test-step 488.0 :print 1.1}   ; wall ms, each stage once
             :calls 45619                   ; armed calls in the request's worlds (sink sum)
             :reads 256                     ; seon.profile/read-entrances calls among them
             :transactions 2                ; seon.db transact entrances among them
             :cpu-ms 3110                   ; only where every stage ran on a measurable platform thread
             :alloc-mb 1172.7               ; only per Sol option 2 (platform arm extent)
             :unmeasured {:alloc-mb :virtual-thread :cpu-ms :virtual-thread}
             :top [[seon.turn/… 2900 1] …]}} ; only past the bound: top 5 by self ms, [sym self-ms calls]
```

| Key | Source (existing owner) | Exact, sampled or unavailable | Cost per eval request |
|---|---|---|---|
| `:ms` | `System/nanoTime` at proc receipt and at reply | exact wall | 2 clock reads |
| `:stages` read, evaluate, derive, facts, apply, settle | `:seon.batch/timings` (`seon.batch/clock`), already computed | exact wall, summed per stage | 0 new; already paid |
| `:stages :test-step` | nanoTime around `seon.turn/test-step` | exact wall | 2 clock reads |
| `:stages :print` | nanoTime around the reply's print (`seon.mcp/observed` today; the turn's render for model agents) | exact wall | 2 clock reads |
| `:calls`, `:reads`, `:transactions` | the world's call sink (`seon.batch/calls`) plus the test step's world sink | exact counts of armed entrances in the request's worlds; unarmed and other-world work excluded | one pass over executed definitions (hundreds), ~10-50 µs |
| `:top` | the sink's self-nanos slot (slot 2, commit 724024094) | exact self time per armed definition in these worlds | a sort of the executed definitions, only past the bound |
| `:cpu-ms` | `ThreadMXBean/getCurrentThreadCpuTime` | exact on a platform thread; -1 on a virtual thread (probed: JDK 26.0.1 answers -1) | ~1 µs per read |
| `:alloc-mb` | `getCurrentThreadAllocatedBytes` on the arm's platform thread plus adopted spans (`seon.sci.kernel/adopt-arm`) | exact thread attribution per Sol option 2; Datahike writer and unadopted virtual work excluded and named | 2 reads, ~108 ns each (Sol) |

Which threads: today the whole pass (turn, settle, test step) runs on the agent's turn proc virtual thread (`seon.flow/graph-executor`;
probed: `VirtualThread[#4499]` serves every request of the probe agent) and the print on the MCP caller's platform thread.
CPU time and allocation are therefore unavailable for the pass until Sol's option 2 moves the arm extent to a platform
worker; until then both keys are absent and `:unmeasured` says why.

The per-form `:seon.repl/ms` stays: it is the form's own evaluation, and next to `:request` it reads correctly
("my form took 1 ms, the request 3.8 s"). The bounds (`:seon.config.eval/bounds`) gain request measures: `:slow` judges
`:seon.cost/ms`, and its fix names the request's `:top` instead of the whole-process summary.

## 3. What disappears

- The JVM-wide fallback in `seon.sci.kernel/record` (`jvm-allocated-bytes`, `::jvm-allocated-at-start`; slice 1) and in
  `seon.profile/measure` (`:alloc-scope :jvm`, routed): a number that includes other lanes is deleted, not labelled (Sol option 1).
- `seon.batch/allocated-bytes` and the form's `:seon.batch/allocated-bytes`, a third copy of the same counter read
  (always absent on the virtual thread SCI runs on).
- `seon.profile/transaction-observation`'s separate `:seon.batch/stamped-ns` clock for the request's ms: the request
  cost already holds it. (Its stored hot list stays: it is the over-floor durable record.)
- The `:slow` fix text pointing at `(seon.profile/summary (seon.profile/cells) 5)`.
- `seon.profile/begin`/`explain` windows in callers that only wanted one request's calls (`seon.pass-cost-test` reads
  JVM-wide cells; the request's `:calls` is the per-world answer).

## 4. Ranked options

1. **Fold at the turn proc, reply-only (recommended).** `seon.turn/step` already holds the batch (timings, sink) and runs the
   test step; it builds `:seon.cost/…` and offers it on the reply with `:seon.turn.loop/tests`. `seon.cluster.agent/evaluate-source!`
   passes it through; MCP adds `:print` and prints the map. Guarantee: every number is this request's, from the owner that
   counted it. Cost: ~6 clock reads plus one sink pass, under 0.1 ms. Sacrifice: not stored, so a later context cannot
   requery an old request's cost; the over-floor transaction stamp remains the durable record.
2. **Fold and store on the run.** As 1, plus the map on the `:seon.turn/id` entity. Guarantee: queryable history ("every
   request over 1 s"). Cost: the test step runs after the recording transaction, so storing needs a second transaction
   per request (rule: under 100 ms, durable) and a rewritten index leaf per commit (`transaction-observation` docstring
   measured 0.8-2.3 GB/day when stamped on every commit). Rejected as default; store only past the bound, as today.
3. **Measure at the MCP entrance only.** Wall plus print around `sci-run`. Smallest code, but stages and per-world calls
   are unreachable there, model agents never see it, and MCP grows logic it must not own. Rejected.

## 5. Slices (about 15 minutes each; each proof asserts reply DATA through `seon.mcp/call`)

Order ruled by root after Sol landed (owner approved platform threads for evaluation): allocation honesty first, then
the platform-worker arm, then the request cost map.

| # | Slice | Files (owner today) | Proof |
|---|---|---|---|
| 1 | Sol option 1: delete the JVM-wide fallback in `seon.sci.kernel/record`; a virtual-thread evaluation records -1 and the reply omits `:alloc-mb` | `src/seon/sci/kernel.clj`, `resources/seon/schemas/seon.eval.edn`, `test/seon/eval_alloc_test.clj` (this lane) | `seon.eval-alloc-test/an-unmeasured-evaluation-reply-shows-no-allocation`. `seon.profile/measure`'s `:alloc-scope :jvm` is the same defect in `src/seon/profile.clj` (held by health-self): routed |
| 2 | Sol option 2: the arm extent (arm acquisition, bindings, evaluate, print observation, record, stop) on a platform worker via `clojure.core.async.flow/futurize` + `bound-fn*`, with actual-exit ownership; `:alloc-mb` and `:cpu-ms` return with declared scope. Opus | `src/seon/sci/eval.clj`, `src/seon/sci/kernel.clj` | restore a positive allocation test: the 100 MB array reads 100.0 ± 1 with a concurrent allocating thread running (`seon.sci.kernel/alloc-mb-on-a-virtual-thread-measures-the-whole-jvm`'s done-when), cross-checked against the JFR per-thread sum (§1 method) |
| 3 | The pass reply carries `:seon.cost/ms` and `:seon.cost/stages` (batch timings + test step); MCP adds `:print` and prints `:seon.repl/request` | `src/seon/turn.clj` (held by codec-print), `src/seon/cluster/agent.clj`, `src/seon/mcp.clj`, the reply schema resource | new `seon.request-cost-test/a-reply-carries-its-request-cost`: for `:ping`, `:seon.cost/ms` ≥ the sum of `:stages` and ≥ the form's `:ms`; a defn request's stages include `:settle` and `:test-step` |
| 4 | `:calls`, `:reads`, `:transactions` from the batch sink plus the test step's sink | `src/seon/turn.clj`, `src/seon/batch.clj` | row in the same test: three `(seon.db/pull …)` forms report `:reads` ≥ 3 more than `:ping`; a second concurrent agent's work leaves `:calls` unchanged |
| 5 | `:top` past the bound and the `:slow` finding on `:seon.cost/ms` with the request's `:top` in place of the whole-process summary | `src/seon/repl.clj`, `resources/seon/schemas/seon.eval.edn` (bounds) | a contracted function sleeping 1.1 s: the finding names it first; no other agent's function appears |

## 6. Accuracy contract

- Wall ms: exact (nanoTime). Stage sums are ≤ the total; the remainder (proc queue wait, submit, MCP decode) is shown as
  the difference, never hidden in a stage.
- Calls, reads, transactions, self time: exact for armed definitions in the request's worlds; unarmed library work is
  inside its armed caller's self time. A future started outside the arm is not counted (task
  `seon.sci.kernel/sci-pmap-escapes-the-arm`).
- CPU and allocation: exact thread attribution only on a platform thread; absent otherwise. Never a JVM-wide total.
- JFR allocation samples: diagnosis only (sampled; ±2% on 100-200 MB here; a short window can hold no sample).

## 7. Findings recorded

- A `:ping` eval request allocates ~110 MB on the agent thread, 83 MB of it inside `seon.db/vector-query-call` across
  48 `seon.db/q` calls (~41 ms), plus ~25 MB of platform-thread work. Belongs to the existing
  `seon.turn/each-form-of-a-pass-costs-megabytes` (which measured per form, not the fixed per-request cost); the
  owning query is the next probe (JFR stack depth 64 hides it; rerun with `-XX:FlightRecorderOptions:stackdepth=256`
  or the sink's per-definition self time once slice 4 lands).
- A one-defn eval request in a new namespace took 3.8 s and 1.17 GB (turn 3.3 s). Over one second: belongs to the
  turn's owner; same evidence.
- The default JVM still runs the pre-724024094 `seon.profile/measure` (`:called`, no `:ranked-by-self`): the self-time
  slot exists in source only until root's next restart.
- `seon.repl/a-slow-reply-names-nothing` and `seon.sci.kernel/alloc-mb-on-a-virtual-thread-measures-the-whole-jvm`
  are the existing tasks slices 5 and 1-2 close.

## Appendix: probe forms (JVM mode, default)

```clojure
;; independent wall, calls, reads; stage totals from the profile cells
(def fd-cells #(into {} (for [held (seon.profile/cells)]
                          [[(:seon.profile/sym held) (:seon.profile/digest held)]
                           [(.sum (:seon.profile/entry-counter held)) (.sum (:seon.profile/total-counter held))]])))
;; JFR allocation by thread: enable jdk.ObjectAllocationSample with throttle 1000000/s, run seon.mcp/call,
;; dump, sum "weight" per (getJavaThreadId, isVirtual) after dropping each thread's first sample
;; (a sample's weight is the thread's allocation since its previous sample).
```
