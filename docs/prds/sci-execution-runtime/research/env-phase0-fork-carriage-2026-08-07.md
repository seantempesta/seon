---
type: research
status: complete
tags: [research, runtime, sci]
---

# Phase 0 falsifier — environment-on-fork carriage, and the interrupt arm

Read end to end before probing: the sealed
[seon.env PRD](../plan/seon-env-prd-2026-08-07.md) and its mechanic's
grounding, [the environment as a value on the SCI ctx](environment-mechanism-sci-2026-08-07.md).
Both were read in full; this lane implements Phase 0 items (a)
"environment-on-fork" and the "Also in scope" item 1 "interrupt-arm probe
(top hypothesis, unprobed)".

## Verdicts

| Probe | Claim under test | Verdict |
|---|---|---|
| A | An environment value on a `sci/fork` travels with interpreted code across any thread; two forks can never observe each other's | **PASS** — 192 evaluations, 576 checked arms, 0 failures, peak 24 concurrent off-thread work units |
| A control | `sci.ctx-store/get-ctx` is a viable carrier | **FAILS as predicted** — 192/192 off-thread reads threw; the audited ambient defect reproduced |
| B | Work handed across a thread from inside an armed eval runs armed | **CONFIRMED UNARMED** — the time limit does not reach it and the fn-entry counter records nothing |

Probe B's confirmation is a defect against the current runtime and is filed as
[the interrupt arm does not cross a thread hop](../../../seon/issues/interrupt-arm-does-not-cross-a-thread-hop.md).

## Method

Load-only JVM evaluation (`clojure -M:dev`), no cluster and no database. Each
probe is a namespace exposing `run`, returning a `:probe/verdict` value — no
test framework, per the Phase 0 instruction that each probe becomes a future
class regression.

Probe inventory (all committed; `tmp/` is gitignored, so they were added with
`git add -f`):

- `tmp/env-probes/RUN.md` — how to run both probes and their argument maps;
- `tmp/env-probes/env_probes/probe_a_env_on_fork.clj`;
- `tmp/env-probes/env_probes/probe_b_interrupt_arm.clj`;
- `tmp/env-probes/probe-a-output.edn`, `tmp/env-probes/probe-b-output.edn` —
  the recorded 2026-08-07 outputs quoted below.

The nested `env_probes/` directory exists only so the namespace name matches
the file path for the edit hook's clj-kondo pass.

Base contexts come from the production constructor `seon.sci.eval/build-base-ctx`
(`src/seon/sci/eval.clj:176`), which installs the one process guard's
`:interrupt-fn`, `:host-interop-observer`, and `:built-in-call-observer`
(`src/seon/sci/kernel.clj:127-135`). `cluster-ctx`
(`src/seon/sci/eval.clj:1431-1462`) was not used: it requires a database value
for `acquire!`, and nothing in either question depends on an acquired
projection.

### Pin note — the fork moved under this lane

The PRD's dependency ledger pins sci `2db3358c`. The vendored fork is at
`a072c8e` — one commit ahead: *"Add :call-preparation-hook read from the
runtime ctx"*, authored 2026-08-07 by the sibling Phase 0 (b) lane. The probes
here ran against `a072c8e`. That commit is what made Probe A's strongest arm
(A3 below) possible; the ledger's pin should be advanced when Phase 0 closes.

## Probe A — environment on fork across thread hops

Four arms. Every fork evaluates the **same source form** — nothing in the
source names a fork, so any difference in the results came from the ctx.

- **A1 — `:interrupt-fn`, sci's own ctx carriage.** Each fork's ctx carries its
  own `:interrupt-fn` appending `[fork-id thread-id virtual?]` to a per-fork
  sink. sci lifts the `:interrupt-fn` off the ctx captured at fn-creation time
  (`reference-code/sci/src/sci/impl/fns.cljc:40,64,152`) and calls it at every
  interpreted fn-body and `loop/recur` entrance
  (`reference-code/sci/doc/interrupt.md:6-9`), so each recorded entrance names
  the fork whose ctx the running closure is evaluating against — on whatever
  thread it is running.
- **A2 — the environment value.** Each fork carries `:seon/environment
  {:probe/fork-id … :probe/token …}` and a per-fork host provider that reads it
  off the fork ctx **at call time** (research verdict (b), third bullet:
  install the leaf per fork as a closure where no Var-identity hook applies).
- **A3 — the shared call-preparation hook.** One host Var installed on the
  **base** ctx and one hook function value shared by every fork. The hook is
  read from the RUNTIME ctx inside the node body (`a072c8e`, contrasted there
  with `:built-in-call-observer` at `analyzer.cljc:1719`, which is lifted from
  the analysis ctx) and supplies `(:seon/environment ctx)` as the call's single
  argument. Nothing is per-fork here except the ctx itself, so this is the
  claim in its strongest form.
- **A4 — negative control.** `sci.ctx-store/*ctx*` is an ordinary Clojure
  dynamic var (`reference-code/sci/src/sci/ctx_store.cljc:9-13`), bound around
  evaluation by `interpreter.cljc:80-83`.

Each fork's evaluated source builds one interpreted closure and runs it three
ways — on the evaluating thread, on a fresh raw `Thread`, and on a
`newVirtualThreadPerTaskExecutor` virtual thread — with a 5 ms hold inside the
closure so concurrent forks provably overlap. 24 forks per round, evaluated
concurrently on virtual threads, 8 rounds.

### Result

```clojure
#:probe{:verdict :pass
        :fork-count 24 :rounds 8
        :evaluations 192 :arms-checked 576
        :interrupt-fn-entrances 576
        :peak-concurrent-off-thread-work 24
        :failures []}
```

Every one of the 576 arms matched its own fork's id AND its own fork's random
token, on both the raw thread and the virtual thread. Not one fork's
`:interrupt-fn` sink ever recorded another fork's id, and each fork's sink
recorded entrances from thread ids other than the evaluating thread's
(a sample of the off-thread `[thread-id virtual?]` pairs:
`[[129 true] [673 false] [786 false] [498 false] [323 true] [702 true]]` — both
raw and virtual). Peak overlap of 24 concurrent off-thread work units means the
forks really were interleaved, not serialized.

The control behaved exactly as the audit predicts:

```clojure
:probe/ctx-store-control
#:probe{:on-thread-resolved 192
        :off-thread-total 192
        :off-thread-failures 192
        :off-thread-sample
        #:probe{:virtual? true
                :value #:probe{:ctx-store-error
                               "No context found in: sci.ctx-store/*ctx*. …"}}}
```

192/192 off-thread reads through the ctx store failed; 192/192 on-thread reads
succeeded. This is a fail-closed throw rather than a silent wrong answer only
because Seon never calls `reset-ctx!` — invariant 2 of the research report is
load-bearing and deserves the regression it names.

**Arm-liveness check.** A pass can be vacuous. Re-running with the hook
neutered (`with-redefs` making it return its arguments unchanged) turned every
A3 arm into `ArityException: Wrong number of args (0) passed to
environment-report` — the hook is doing real work, and the zero-argument call
site depends on it.

### Cross-fork carriage — the containment obligation, measured

Recorded as evidence rather than as a pass/fail arm. A closure was built by
evaluating in fork A, then called three ways: directly from the host, from a
host virtual thread, and from **inside fork B's own evaluation** after being
interned as a Var in fork B.

```clojure
:probe/called-from-fork-b
#:probe{:env-id :fork-a
        :hook #:probe{:env-id :fork-a :env-token "56a3d04e-…"}}
:probe/fork-b-own
#:probe{:env-id :fork-b
        :hook #:probe{:env-id :fork-b :env-token "08140a21-…"}}
:probe/fork-a-interrupt-entrances 3
:probe/fork-b-interrupt-entrances 1
```

The escaped closure resolves **fork A's** environment in all three calls,
including when fork B calls it, and fork A's `:interrupt-fn` — not fork B's —
fired for all three. Fork B's own closure resolves fork B.

This is the design working as specified, and it is precisely why the research
report's invariant 3 matters: an escaped live fn object does not leak fork B's
environment into fork A, but it *does* keep fork A's environment alive inside
fork B's turn, along with fork A's interrupt policy. rehydrating the agent's defs, database
blobs, shared atoms and channel payloads must round-trip through **source**
re-evaluated in the receiving fork. A fn object crossing a turn boundary is the
bug class; this probe is the demonstration to point at.

### What Probe A does not prove

- No analyzed-node cache exists here; each `eval-form` re-analyzes. The
  runtime-ctx property of the new hook therefore was not stressed under node
  sharing, which is exactly the scenario that would expose
  `:built-in-call-observer`'s analysis-ctx staleness (PRD "also in scope" 3).
- `sci/add-namespace!` on the **base** after forking is invisible to existing
  forks (`sci/fork` snapshots the env map, `core.cljc:331-337`). Installation
  order is therefore load-bearing for Phase 1 and should be an explicit
  constructor rule, not an accident of boot ordering.
- The probe used a plain `:seon/environment` map, not the host record type the
  PRD requires for containment. The containment claim ("no installed function
  returns the environment") is unprobed here and is a Phase 1 admission-seam
  question.

## Probe B — the interrupt arm across a thread hop

The PRD's top unprobed hypothesis, stated there as: *"The `:interrupt-fn` arm
is a ThreadLocal on the process guard; work handed across a thread by agent
code plausibly runs unarmed."*

The mechanism, from source: `seon.sci.kernel/new-guard`
(`src/seon/sci/kernel.clj:45-47`) holds its arm state in a plain
`(ThreadLocal.)` — not an `InheritableThreadLocal`, and virtual threads do not
inherit it either. Every part of the guard's `interrupt-fn` sits inside
`(when-let [armed (.get thread-arm)] …)` (`src/seon/sci/kernel.clj:50-64`): the
fn-entry counter, the deadline test, and `sci.interrupt/interrupt!` at
`:60`. `own-arm` sets that ThreadLocal on the calling thread only
(`src/seon/sci/kernel.clj:190-204`) and schedules the one deadline task there.
The `:interrupt-fn` *value* crosses the thread with the code (Probe A proved
it fires off-thread); the *arm* does not.

Four arms, one shared ctx:

```clojure
#:probe{:verdict :confirmed-unarmed
        :summary
        #:probe{:fn-entries-same-thread 20002
                :fn-entries-cross-thread 0
                :detached-survived-deadline? true
                :detached-interrupted? false
                :control-interrupted-on-arming-thread? true}}
```

- **B1 — armed baseline, same thread.** A 20,000-iteration interpreted
  `loop/recur` under a 60 s arm records `:seon.eval/fn-entries 20002`,
  `:seon.eval/outcome :ok`.
- **B2 — the identical workload awaited on a virtual thread.** Same arm, same
  source, handed to `newVirtualThreadPerTaskExecutor` by a host function and
  awaited. The workload returned the correct value (20000) on thread id 841,
  `virtual? true` — and the arm recorded **`:seon.eval/fn-entries 0`**. Twenty
  thousand interpreted body entrances were invisible to the governing arm. The
  recorded `:seon.eval/fn-entries` diagnostic reads "blocked in a host call"
  for an eval that in fact burned twenty thousand interpreted entrances on
  another thread.
- **B3 — an unbounded interpreted loop detached to a virtual thread under a
  300 ms time limit.** The parent eval returned `:spawned` immediately. 750 ms
  later the loop had ticked 25,986 times; at 1500 ms — five times the limit —
  it had ticked 52,426 times and `Future.isDone` was still false. It was never
  interrupted (`:probe/spawned-interrupted? nil`, no error). The probe stops it
  with its own host flag, which is the only reason it terminates at all.
- **B4 — control, the same unbounded loop on the arming thread.** Interrupted
  after 310 ms against the 300 ms limit, `:seon.eval/outcome :time`,
  `:seon.eval/fn-entries 3108328`, `interrupted? true`. The limit works
  perfectly — on exactly one thread.

**Consequence.** Any agent-reachable path that hands work to another thread
escapes the one time limit entirely, and the eval's recorded diagnostics
under-report it to zero. Fixing this is the same move the rest of the design
makes: the arm must ride the ctx/fork and the submission like everything else,
so the receiving thread arms itself from the environment it was handed rather
than from a ThreadLocal it never inherited. Filed as
[interrupt-arm-does-not-cross-a-thread-hop.md](../../../seon/issues/interrupt-arm-does-not-cross-a-thread-hop.md).

Note that `arm`'s re-entrancy rule (`src/seon/sci/kernel.clj:224-252`) refuses
a *different* ctx already armed on a thread and inherits an *identical* one.
A cross-thread arm design must decide what happens when work from fork A lands
on a thread already serving fork B — Probe A's cross-fork result shows that a
single escaped closure can make that concrete.

## Recommendations for Phase 1

1. Graduate Probe A as the isolation class regression, keeping the
   arm-liveness check (a neutered-hook run must fail) so the regression cannot
   go green vacuously.
2. Make the environment a required constructor argument on the fork, and make
   base-ctx installation order explicit: anything installed on the base after
   a fork exists is invisible to that fork.
3. Treat the interrupt arm as an environment member, and give it the same
   "missing is a flat error at the door" rule the PRD sets for everything else.
4. Advance the sci pin in the PRD's dependency ledger from `2db3358c` to
   `a072c8e` once Phase 0 (b) reports.
5. Add the `sci.ctx-store/*ctx*` root-is-nil regression the research report
   names; this lane's control shows the failure is honest today and would
   become a silent wrong-cluster answer the moment anyone calls `reset-ctx!`.

## Reported friction

- **`tmp/` is gitignored, but Phase 0 requires committed probes.** The probe
  files had to be force-added. If probes are the durable Phase 0 artifact,
  they want a real home (a `test/` namespace or a probes package) rather than
  an ignored directory plus `-f`.
- **The namespace-name/file-path lint blocks a flat probe directory.** Writing
  `tmp/env-probes/probe_a_env_on_fork.clj` with a two-segment namespace is
  refused by the edit hook, forcing the redundant `tmp/env-probes/env_probes/`
  nesting. Not wrong, just an odd shape a reader will trip on.
- **Virtual threads have an empty `.getName`.** Any diagnostic that identifies
  a thread by name renders `""` for every virtual thread — useless in exactly
  the concurrency the runtime is built on. These probes report `.threadId` and
  `.isVirtual` instead; runtime fault and diagnostic renders should do the
  same. (Related standing guidance: thread dumps that omit virtual threads
  lie.)
- **`:seon.eval/fn-entries` silently under-reports.** Probe B's B2 arm shows
  `0` for an eval that ran 20,000 interpreted entrances. The vocabulary table
  says a low entry count "reads as blocked in a host call" — with cross-thread
  work that reading is wrong, and the diagnostic will mislead whoever next
  reads it. Worth a note wherever that value is rendered.
