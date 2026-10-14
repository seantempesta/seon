---
title: "A REPL value def's init re-runs per program change, without the evaluation's bindings"
type: research
status: finding and options; nothing installed (stopped: the fix is re-acquisition design)
date: 2026-10-14
basis: "default pid 10712 running de1f48c23; HEAD 76985e26b; agent lane-install-row, branch :agent-a3357c1a9c97"
---

# Install-row re-runs (lane lane-install-row)

What exists: since de1f48c23 every top-level REPL `def` is a program row. A branch's program context is
`seon.sci.eval/program-context`, one `seon.cache` entry per program content (`program-content-key`). When the branch
changes the loaded program, `seon.sci.eval/derive-overlay-ctx` forks the loaded base with `::custody {}` and
`seon.sci.eval/interpret-overlay!` interprets the branch's whole plan through `seon.sci.eval/install-selected!` →
`seon.sci.eval/install-row!`. The finding is in `reflevels-opus.md`, "Findings during the probe" item 1.

## Reproduction (SCI mode, own branch, one form per request)

```clojure
;; r1
(ns probe.ir1) (def d (seon.db/db)) (def n (seon.db/basis-t d))   ; agent's d is a datahike.db.DB
;; r2
(def L2 (seon.ref/brief d 'seon.edit/single-form))                ; agent's L2 is a brief: no error
;; r3  (+ 1 2)       -> error delivered, :seon.error.occurrence/count 1
;; r4  (+ 1 3)       -> no error (count unchanged)
;; r5  (defn other2 [] 2) -> error delivered again
;; r6  (+ 1 4)       -> error delivered, count 3
```

The error: `seon.sci.eval/install-row!` "Cannot interpret probe.ir1/L2: seon.ref/brief refused argument 0 `database`:
expected :seon.db/database-value …, got a map". Signature `9eb599f1…`.

JVM mode, the branch's program context (`seon.sci.eval/base-ctx` over `seon.db/branch-database`):
`probe.ir1/d` is a `clojure.lang.PersistentHashMap`. It is `seon.db/missing-connection-error`'s refusal, "no cluster
connection is bound on this thread". `probe.ir1/n` is the same map: `seon.db/basis-t` passed the refusal through,
and nothing reported it. Timings: the requests took 58–440 ms. A program-changing request took 267–440 ms
(test-step 116–198 ms, 3 writer transactions against 1).

## Root cause: three links

1. **The init runs without the evaluation's bindings, by design of the shared value.** A program context is keyed
   by content and shared by every branch and commit with that program, so it holds no custody
   (`derive-overlay-ctx`: `::custody {}`). A value def's init that reads an ambient binding (`(seon.db/db)`, the
   bound connection, agent or task) gets a different value there than the agent got. Clojure loading the same file
   does the same: `(def d (seon.db/db))` in `src/` would also fail at load. So the def is the agent's mistake, with
   Clojure's semantics (the "sacrifice" ruled in de1f48c23).
2. **The bad value is silent at its producer.** `seon.db/db` returns its refusal instead of throwing (the reads
   exception in CLAUDE.md "Nothing crashes", until the errors spec's S-reads). So `d` installs "successfully" as a map,
   `n` silently holds a map, and the refusal surfaces one hop later, on `L2`, at the first contracted callee. Thrown,
   it would refuse `probe.ir1/d` by name with the true cause.
3. **Every program change re-runs every branch value row's init.** `program-content-key` includes the plan's
   digest, so any program change (r5's unrelated `defn`) derives a new overlay from the loaded base. That overlay
   re-interprets the whole plan, `L2` included, although neither `L2`'s source nor anything it reads changed. Each
   derivation records the refusal once per consumer: `seon.sci.eval/record-refusals-once!` is keyed by
   `[program-revisions target]`, which changes with the program. Two consumers per change were observed (count +2:
   the agent's acquisition and the test step's world). The count is accurate: the work really is repeated. The
   repetition is the defect, against "each eval request reacquires from the branch head, reusing content-valid
   derivations". The same whole-plan re-interpretation shows as 37 `install-row!` calls per request in
   `ctx-cost.md`.

Not a cause: a non-program reply re-derives nothing (cache hit) and delivers the error only once its count has moved.
That matches "deliver every error".

## Options (owner / Opus decision: link 3 is the one REPL path's re-acquisition)

1. **Derive an overlay incrementally, reusing content-valid interpretations (recommended).** Key each interpreted
   row's installed root by its definition digest plus the digests of the rows its `:seon.fn/calls` closure reaches,
   and derive a program's overlay from the nearest held overlay of the same branch rather than from the loaded base.
   Then an unchanged value row's init runs once per content, not once per program change, and its refusal records
   once. Cost follows changed rows (r5 would interpret `other2` only), which also targets the 37-call
   `install-row!` cost. Guarantee: equal content gives the identical Var root. Sacrifice: a design round on the
   overlay (Opus plus Astra), with SCI context forking per generation. Proof: a `seon.profile/measure` on a
   program-changing request (install-row! calls ∝ changed rows), plus a deftest showing that r5 leaves the
   occurrence count unchanged.
2. **Convert `seon.db/db` (and the read family) to throw now (S-reads for this one function).** This fixes link 2:
   the refusal names `d` with "no connection bound", the true cause, and `n` no longer holds a silent map. It does
   not stop the re-runs. It is small, but it is the errors spec's phase and converts its callers. Recommended
   alongside 1, in the errors lane.
3. **Deliver the program-context refusal in the defining reply.** Today the error first appears one reply after
   `L2` is defined (r3, not r2), although r2's test step already derives the new program context. Interpreting the
   request's own new rows in that derivation and returning its refusal beside r2's value tells the agent at the
   moment it can act ("this def cannot be reproduced from source: its init read a binding"). It changes no
   semantics. Small, but it touches the settle/test-step order.

Rejected: binding the program context's custody to the acquiring agent. The context is a content-keyed value shared
across branches, and a database value is never bound. Also rejected: filtering repeat occurrences in
`record-acquisition-refusals!` (a fence beside the re-run).

## Task to file (the task file is held by lane s1a-rename; root files it)

```clojure
#:seon.task{:name "a-program-change-reruns-every-branch-value-rows-init"
            :plan "Every program change on a branch derives a new overlay from the loaded base (`seon.sci.eval/program-content-key` holds the plan digest; `seon.sci.eval/derive-overlay-ctx`), so `seon.sci.eval/interpret-overlay!` re-runs every branch value row's init through `seon.sci.eval/install-row!` and `seon.sci.eval/record-refusals-once!` records its refusal again per consumer. Reproduction: (def d (seon.db/db)) (def L2 (seon.ref/brief d 'seon.edit/single-form)), then an unrelated (defn other2 [] 2) raises L2's install refusal count by 2. Done when a program change interprets only the rows whose content or reach changed, and the unrelated defn leaves the occurrence count unchanged. Read `docs/research/agent-platform/2026-10-14/install-row-reruns.md`."}
```

## Cleanup

Agent branch `:agent-a3357c1a9c97` (agent `lane-install-row`, namespace `probe.ir1`) is disposable and holds the
reproduction. It was left for root to retire.
