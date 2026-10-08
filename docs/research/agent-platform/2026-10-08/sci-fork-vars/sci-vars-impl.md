---
type: research
status: implemented-in-fork
created: 2026-10-08
tags: [sci, forks, performance]
---

# Context-owned SCI Var bindings (lane sci-vars)

Lane `sci-vars`, 2026-10-08, implementing [sci-sol.md](sci-sol.md) option 2 (stable Var
handles, binding selected by the executing context) for task
`seon.sci.eval/an-inherited-test-never-sees-the-branch-redefinition-of-its-callee`.
The owner's gate: measure first; if the design costs more than about 10% on Var reads,
stop and report options. It does, for every Var a fork redefines; the lane stopped and
reported. **Owner ruling (2026-10-08): "if evals are sub second I don't care and we can
optimize later so I want correct now and fast later": option A.** It is implemented in
the fork at `seon-env-hook` `cff2cefb` (cards 1-4 of sci-sol.md in one commit, with
`sci.fork-bindings-test`); Seon's integration and deletions are cards
docs/seon/schedule/cards/SCI-1.md to SCI-3.md, and option C is SCI-4.md. Nothing in
Seon's `src/` changed and the gitlink is not bumped.

## Evidence (fork `reference-code/sci`, remote `fork` = seantempesta/sci)

- `seon-env-hook` commit `9ee89114` adds `sci.vars-bench` (bench/sci/vars_bench.clj; run
  `clojure -M:test -i bench/sci/vars_bench.clj -m sci.vars-bench`). It measures, per op,
  an interpreted loop reading a Var, calling a fn through a Var and an empty loop, each in
  an unforked context, a fork inheriting the Var and a fork that redefined it (x, f), plus
  never-redefined Vars (ux, uf); host deref of a held Var; `sci.core/fork` over 5,700 Vars;
  a def in a fork. Median of 5 samples of 1M ops, the second of two runs in one JVM.
- Branch `sci-vars-proto-p1` commit `4ae5a3f3` (timing prototype, never for landing): a def
  through an inherited Var writes the fork's value into a persistent map in the env
  (`:sci/var-roots`, Var identity to root) and flags the Var (the dead `needs-ctx` field);
  `Var.deref` of a flagged Var reads `sci.ctx-store/*ctx*` and looks the Var up there.
  Semantics probe: base `(defn g [] :base) (defn h [] (g)) (def x 1) (defn rx [] x)`, fork
  `(defn g [] :fork) (def x 2)`: child `[(g) (h) x (rx)]` = `[:fork :fork 2 2]`, base
  `[:base :base 1 1]`. So the mechanism fixes the inherited-caller case with no re-analysis.
- Fork suite at `97de2004`: `lein test` 402 tests, 1570 assertions, the 5 known baseline
  failures (`function-results-dont-have-metadata` x2, `error-location-test` x2,
  `error-test/arity-error-test`; recorded since 409888f6 in
  docs/research/agent-platform/landing/fix-sci-fork-vars-2026-09-26.md).

Host load average was about 10 during the runs; three interleaved runs each, ns per loop
iteration (the empty loop is about 9 ns of each):

| Case | Baseline 97de2004 | Prototype |
|---|---|---|
| read loop, Var never redefined | 12-21 | 16-25 (within noise) |
| call loop, Var never redefined | 26-37 | 30-36 (within noise) |
| read loop, Var redefined in some fork (read in any context) | 12-21 | 26-31 |
| read loop, redefined Var read in the redefining fork (hit) | 12-20 | 29-40 |
| call loop, redefined Var | 26-36 | 41-56 |
| allocation per iteration, redefined Var | 48 B read, 128 B call | 80 B, 160 B |
| host deref of a held Var | 0.7-0.9 | 4.1-4.8 (that Var was flagged) |
| fork over 5,700 Vars | 64-172 ns, ~500 B | unchanged |
| def in a fork / redefinition of an inherited Var | 3.6-4.0 / 3.3-4.9 us | unchanged |

Component costs (one JVM, 10M ops each): `sci.ctx-store/*ctx*` deref 4.7 ns (it grows
with the size of the thread's Clojure binding frame), a plain `ThreadLocal` get 0.9 ns,
`*ctx*` then env then map get 18 ns, the same from a ctx already in hand 15 ns (the env
record's extmap keyword lookup and the identity-hash map get dominate), an object-array
slot read about 1 ns.

**Expected cost per read of the persistent design: O(1) but +10-15 ns on a redefined Var
(about +60-100% on a read-bound loop, +40-60% on a call through a Var), within noise for
Vars no fork redefined.** That exceeds the 10% bar for exactly the Vars an agent works on.

Two further facts raise the stake. Every Var flagged by any fork pays in every context,
base included (one flag per Var object). And Seon re-arms each contracted interpreted
function with `sci.core/bind-root!` in `seon.sci.eval/fork-cluster-ctx`, so under this
design every contracted function would be flagged in every agent's fork: the slow path
would become the common path unless arming moves to the base once.

## Options, priced

| Option | Read of a redefined Var | Read of other Vars | Fork | Change size | Sacrifice |
|---|---|---|---|---|---|
| A. Persistent override map, active ctx from `*ctx*` (the prototype, completed) | +10-15 ns | ~0 (one volatile flag) | O(1) | Smallest: Var deref/meta/root writes, `owned-var`, `eval-def`, `init-var!`, fork bumps the parent's generation | Fails the 10% bar on redefined Vars; host code must bind `*ctx*` to read a branch's value (`(sci/resolve child s)` then `@v` reads the base without it) |
| B. A with the binding table cached in a `ThreadLocal` set by `with-ctx` | est. +5-7 ns (ThreadLocal 0.9 + identity get ~5) | ~0 | O(1) | A plus a conveyance rule for futures and `bound-fn` | Still about +30% on a read-bound loop; a thread SCI did not enter reads the base silently |
| C. Dense slots: each Var gets a lineage slot index; each ctx holds an object array of bindings, shared at fork and copied on its first write; analyzed Var nodes read the slot through the ctx argument, and an SCI fn body runs with the active ctx (read once per call at entry) | est. ~1 ns | ~1 ns | O(1), first write in a fork copies O(V) (5,700 refs, ~23 KB, a few us) | Largest: analyzer Var read and call nodes, fn entry, Var deref for host reads; close to the upstream draft #1084 dense mode | est. +1-5 ns per SCI fn call (ThreadLocal 0.9 or `*ctx*` 4.7 at entry, +3-15% on a call loop); inherited bodies then run under the active ctx's interrupt-fn and observers (a semantic change Seon probably wants) |
| D. Keep Seon's re-analysis interim (`seon.sci.eval/reinterpret-reach!`) | 0 | 0 | O(1) | None | O(E + S) per changed definition, incomplete for closures and held Var handles (sci-sol.md option 1) |

Recommendation for the owner's ruling: **C**, prototyped first on a branch to replace its
estimates with measurements before cards 1-4 are re-cut, because it is the only option
whose read cost stays near the 10% bar for the Vars agents redefine. A is the smallest
and correct but buys correctness with a 40-100% slowdown on exactly those Vars; it is
acceptable only if the owner rules that cost proportional to the redefined set is fine.

## Implemented (fork `cff2cefb`)

The prototype completed: `sci.impl.vars/Binding` (root, meta, watches) per context under
`:sci/var-bindings`; `sci.impl.vars/owns?`; reads (deref, getRawRoot, hasRoot, meta,
isMacro, dynamic?) and writes (bindRoot, unbind, alterMeta/resetMeta, add/removeWatch)
of `sci.lang/Var` select the executing context; `sci.core/fork` renews both generations;
`sci.impl.utils/owned-var` and the copies in eval-def and metadata writes are deleted.
`sci.fork-bindings-test`: 10 deftests (Seon's case through a direct call and
`clojure.test/test-var`, a `bind-root!` re-arm, self reference, value/handle/closure,
parent/sibling/grandchild snapshot, unmap/reintern, declare, dynamic binding, metadata,
concurrent siblings, host invocation), red at 97de2004 (19 failures, 1 error), green at
`cff2cefb`. `lein test`: 414 tests, 1611 assertions, only the 5 baseline failures.
Timing at `cff2cefb` matched the prototype rows above; fork over 5,700 Vars 86-92 ns /
856 B (the parent's generation swap); def in a fork 4.0-4.4 us. The +32 B per read of a
written Var is the MapEntry of the `*ctx*` thread-binding lookup.

A live Seon eval request (`:ping`, a `defn`) through it was not measured: that needs
SCI-1's integration in `src/`, outside this lane; SCI-1's proof carries it.

## Card 5 (Seon integration; superseded by SCI-1 and SCI-2, which name the exact changes)

- `seon.sci.eval/fork-cluster-ctx`: arm contracted functions once in the base, not by
  `sci.core/bind-root!` per fork (under A/B that loop would flag every contracted Var).
- `seon.sci.eval/transfer-evaluated-roots!` and `seon.sci.eval/install-evaluated-rows!`:
  install a redefinition into the same Var identity in the receiving context through the
  SCI root-write seam (no `sci.core/intern` of a fresh Var); new declarations transfer
  their Var object, so closures that captured it agree with the destination.
- Host-side reads of SCI Vars (`deref`, `meta`, `clojure.test/test-var` on a resolved SCI
  Var, the test runner's `:test` dispatch in `seon.test.runner`) run inside
  `sci.ctx-store/with-ctx` of the context whose binding they mean.
- Re-examine every Seon reader of `:sci/generation` (Var identity no longer changes on a
  fork's write, so "owned by this generation" stops meaning "a different Var"):
  `seon.sci.eval/turn-interns`, `seon.sci.eval/regenerate-agent-context!` and the
  generation save/restore in `seon.sci.eval/acquire!`.
- Proof: extend `seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition`
  with the model-agent step shape the task records (main proof, agent branch redefinition,
  a second eval request), asserting the test's executed digest and completion; then
  revert the interim in `seon.test/clean-request-handle` (the world back over the agent's
  live base) and keep it green.

## Card 6 (deletion; superseded by SCI-3)

Delete `seon.sci.eval/stale-reach`, `seon.sci.eval/reinterpret-reach!` and
`seon.sci.eval/reinterpret-inherited-callers!` with their callers' copy-before-analysis
steps; keep compiled-to-SCI interpretation of host-bound callers, evidence and arming.
Proofs: `seon.repl-differential-test/an-inherited-caller-sees-a-same-pass-redefinition`,
`seon.repl-differential-test/another-writer-on-the-cluster-head-neither-hides-nor-freezes-a-callee`,
the inherited-test proof above and
`seon.sci.eval-test/a-branch-change-leaves-its-compiled-callers-compiled`, plus a count
showing caller source is no longer re-evaluated and initializers run once.

## Risks

- Host reads without a bound context see the base's value under every option; Seon code
  that derefs SCI Vars from JVM code must be audited (card 5).
- The fork snapshot needs the parent to lose ownership at fork (its generation bumps), so
  a parent's later redefinitions also go through the per-context path.
- Unmapped Vars keep their override entries until the lineage is dropped (bounded by
  redefinitions; no reclamation).
- Watches stay shared on the Var object and fire for every context's write.
- A concurrent fork racing a parent's root write can leak that one write into the fork.
