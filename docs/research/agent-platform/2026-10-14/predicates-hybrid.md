---
title: "Predicates hybrid: the context's rebound Vars are the bindings; the base holder derives the bound projection once"
type: research
status: hybrid of predicates-plan-opus (9b7850bcd) and predicates-plan-astra (98c7502ae); lane predicates-hybrid, HEAD 9b7850bcd
---

# Predicates hybrid

Both plans agree on the shape: one per-context *bound projection* that binds a predicate to the SCI Var the context
holds, read by arming, instrumentation, the writer's contract facts and interpreted validation; Malli late-binds the
IFn itself (`malli.core/-fn-schema` calls `malli.core/eval` on the child once, and a Var is returned unchanged by its
`:else` branch, so `(f x)` reads the Var at call time); a predicate's first move from compiled to interpreted
recompiles the keys naming it through the existing reverse closure of `seon.schema/projection-with-declarations` and
re-arms the armed dependents; later redefinitions of the same Var cost nothing; both `seon.sci.eval/context-predicates`
merges are deleted; main pays nothing. The hybrid keeps that and corrects one premise of Opus's plan with a probe.

## What the probe settled

Opus: a branch's first `defn` of a main predicate "creates a new Var, in a fresh namespace object". Astra: `sci.core/fork`
preserves Var identity with contextual bindings. On HEAD, as agent `lane-predicates-hybrid` (SCI, own branch), after
`(in-ns 'seon.edit) (defn valid-form-operation? … false)`:
`{:same-var? true, :after-generation nil, :contextual-binding? true, :before-var-answers false, :compiled-answers false,
:registry-validates? true}` (`result/eccd73fa3f60a`). The base context holds a *copied* SCI Var for every compiled
first-party function (`seon.sci.eval/copy-host-var`, `sci.core/copy-var*`), born before the fork so the fork does not
own it (`sci.impl.vars/owns?`); `sci.impl.evaluator/eval-def` keeps `prev` and `bindRoot` goes through
`sci.impl.vars/update-binding!`, which writes the context's own binding in its env's `:sci/var-bindings` and leaves the
Var object shared. The same path serves an affected caller interpreted by `seon.sci.eval/bind-lazy-members!`
(`sci/bind-root!` on the held Var under `with-ctx`). The projection still binds the JVM Var
(`seon.schema/runtime-predicate`), which is the whole defect. Opus's "larger option" condition ("SCI eval rebinds the
base's Var per context on defn instead of creating a new one") is therefore already met; see the recommendation.

## 1. Owner: the SCI context's env, read by one function

**"How a predicate resolves for this evaluation" is owned by the context's env**, exactly as every other symbol's
resolution is: the predicates this context holds apart from the base are the keys of `(:sci/var-bindings @(:env ctx))`
plus the Vars born in its generation (`:sci/generation` of the Var's meta equals the env's), intersected with the
population's predicate symbols. No new fact, no snapshot entry, no `seon.env` state (lesson 1), no set of
"interpreted symbols" to maintain: SCI already records it.

One reader, `seon.sci.eval/context-bound-projection` `[ctx base]`: `base` with each such predicate bound to its SCI Var
(`seon.schema/with-bound-predicates`, the impl patch's function). With nothing rebound it answers `base` itself
(`identical?`). It never derefs: the binding is the Var (Astra: "resolve interpreted predicates to their SCI Vars").
Every consumer reads it:

- `seon.sci.eval/arm-contract!` (so `seon.sci.arm/arm!`, `install-function-contract!`, `install-selected!`) arms under
  `(context-bound-projection ctx arming-projection)`; `seon.instrument/wrap-interpreted` gets that projection unchanged.
- `seon.turn.batch/write-entity` → `seon.fn/contract-facts-row` reads the same, computed under the request's
  `sci.ctx-store/with-ctx` (Astra's writer-handoff point: a contextual SCI Var read outside its context answers the root,
  i.e. main).
- `seon.sci.kernel/context-projection` answers the bound projection, so interpreted `m/validate` with the context's
  registry sees the branch (the probe's `:registry-validates? true` becomes false).
- Compiled callers and the checked writer's attribute validation keep the database's carried projection and main's JVM
  Vars: a host-bound caller sees a change only after merge. Stated in `context-bound-projection`'s docstring.

Composition with Malli: no new registry kind. The bound projection is an ordinary `seon.schema/projection-registry`
generation (`malli.registry/lazy-registry` over `composite-registry`, sealed by `simple-registry`) whose predicate
callables differ for the rebound symbols.

**What both plans missed.** Because the rebound Var is the *shared* object with a contextual binding, the bound projection
is a function of `(base, bindings)` alone and is context-independent: two agents that each rebind
`seon.edit/valid-form-operation?` have equal bindings maps (same symbol → same Var) and share one derived projection in
`base`'s own holder (`seon.schema/projection-cache-value`, key `[::bound-predicates bindings]`), each reading its own
binding when it executes. Lesson 1's leak was a leaked *value*; a contextual Var cannot leak. A branch-only predicate
(`f40.named-predicate/even-int?`, a Var born in the fork) keys by its own Var, so it shares with nobody. Opus's plan
proposed exactly this holder but reasoned from new Vars; Astra kept per-context custody. Neither needed to.

## 2. A later redefinition, and its cost

- **Same Var redefined again** (an agent iterating; or an affected caller re-interpreted): no work. The binding map's
  Var identities are unchanged, `with-bound-predicates` answers the held projection, armed validators call the Var and
  read the context's current binding. Both plans agree; the probe confirms the mechanism.
- **A predicate first held apart** (its first `defn`, or its first interpretation as an affected caller of a changed
  function, done-path's case): the bindings map gains a key. `projection-with-declarations` counts a supplied binding
  that replaces a held one, *or that no held binding has*, as a change to every key and contract whose form names it
  (one scan of the population's predicate symbols, run only on a binding change: O(forms) per transition, Astra), seeds
  the existing reverse closure and recompiles referrers and dependent contracts; the base holder retains the result.
  Then `seon.sci.arm/arm!`, after `interpret-affected!`, re-arms this context's already-armed functions whose contract
  reaches a recompiled key: `(function-dependents-of bound affected-keys)` ∩ `@(::kernel/installed-functions ctx)`
  (the armed set exists; Opus's assumption holds), minus what the write reinstalls anyway, each by `arm-contract!` from
  the Var's held contract. Cost: keys naming the predicate + referrers + armed dependents, once per predicate per
  context (measured for `valid-form-operation?`: 2 keys, 7 dependents). Per call afterwards: one SCI Var invoke with a
  contextual lookup instead of a JVM Var invoke, in the branch only.
- **Opus's inverse index** (`predicate → keys/contracts`, a `seon.cache` kind) is *not* in a phase. The brief's 1,225 ms
  is unprofiled; my requests show `:test-step` 1,174 ms of a 1,880 ms redefinition request, `:apply` 245, `:settle` 208
  (`result/e97e047f0beea`), so the predicate work is not where the time is. Phase 3 measures; the index is the step if
  the scan shows in the profile (AGENTS: count before designing).

## 3. Deleted; compiled on main

Deleted: `seon.sci.eval/context-predicates`, its merge in `arm-contract!`, the same merge in `seon.turn.batch/write-entity`,
their docstring clauses; BEHAVIOR-SPEC §1 cites `seon.sci.eval/context-bound-projection`. `runtime-predicate`,
`register-core-predicate!` and `compilable-form` stay as they are. On main `:sci/var-bindings` is empty, the bound
projection is `base` itself, nothing compiles and validation calls the JVM Var: zero cost. A branch that rebinds no
predicate is the same.

## 4. Phases (HEAD boots after each; each commits through `bin/lane commit … --test`)

1. **`seon.schema`: a rebound or new supplied binding recompiles what names it** (host-bound; files `src/seon/schema.clj`,
   `test/seon/schema_test.clj`). Adds `with-bound-predicates` and the rule in `projection-with-declarations`. Red proofs:
   `seon.schema-test/a-rebound-predicate-recompiles-the-keys-naming-it` (Opus): over `{:t/k [:fn t/p?] :t/r [:map [:a :t/k]]}`,
   advancing with `{'t/p? (constantly false)}` makes `(m/validate :t/r {:a 1} {:registry …})` false (red: the retained
   entry answers true). Two rows in `an-incremental-projection-equals-its-full-build` (Astra): a changed binding reached
   transitively, and a binding for a symbol *no* held binding and no JVM Var has, named only by an inline contract, equal
   to a full build with the same bindings. The second row is lesson 3's regression; its cause (the patch admitted that
   binding, the writer still answered `:seon.schema/unresolved-predicate`) is unverified by either plan, and the row
   decides it rather than a hypothesis.
2. **One reader; the merges go** (critical path: `src/seon/sci/eval.clj`, `src/seon/sci/kernel.clj`,
   `src/seon/turn/batch.clj`, `test/seon/turn/batch_test.clj`; one short window in the chain lanes' schedule, root
   batches the restart). Adds `context-bound-projection` deriving from the env, points `context-projection`,
   `arm-contract!` and `write-entity` at it, deletes `context-predicates` and both merges. Red proof: the patch's
   `seon.turn.batch-test/a-registered-predicate-runs-as-the-branch-defines-it`, both rows (predicate redefined;
   `single-form` redefined so the predicate is an affected caller), asserting
   `{:seon.error/type :seon.instrument/contract-error :seon.instrument/check :input}`. Stays green:
   `a-contract-naming-a-predicate-defined-on-the-branch-is-admitted-and-armed`. Add Astra's isolation row: a sibling
   agent's call of the same taker on its own branch still answers `true` (asserted on `:seon.repl/value`).
3. **Re-arm on the transition** (`src/seon/sci/arm.clj`, `test/seon/turn/batch_test.clj`,
   `test/seon/sci/acquisition_cost_test.clj`; `arm.clj` already calls `#'eval/arm-contract!`, so `eval.clj` is not
   touched). Red proof: a third row of the same table: arm `bp.registered-predicate/takes` in one request, redefine the
   predicate in the next, call `takes`, refused with the same error data (red after phase 2: `takes` stays armed under
   the old registry). Cost proof: `seon.sci.acquisition-cost-test/a-context-recompiles-only-for-a-predicate-it-interprets`
   (patch), counting `seon.schema/projection-with-declarations` calls: zero on main, zero on a branch holding no
   predicate, positive on the first redefinition, zero after; plus `seon.profile/measure` of the redefinition request on
   parent and change in one run. A measured regression is not committed.

## 5. Where the plans differed, and the choice

| Question | Opus | Astra | Hybrid |
|---|---|---|---|
| What a branch `defn` does to the Var | new Var; bindings set = interpreted ∪ evaluated | same Var, contextual binding | Astra, by the probe; so the bindings are read from the env, not maintained |
| Where the bound projection lives | context snapshot, `{:base :bindings :bound}` | context snapshot, copied at fork | derived on read, retained in the base's holder by bindings (shareable, see §1) |
| Finding keys naming a predicate | inverse index now | scan per transition, index only if measured | Astra; profile first |
| Re-arm placement | after a binding change, from the snapshot | at `arm!` after `interpret-affected!`, intersected with installed | Astra's placement, `::kernel/installed-functions` as the set |
| Writer | reads the bound projection | reads it under the request's ctx | Astra: the contextual read needs the context |

## 6. Recommendation on the larger option (for the owner, not a phase)

Opus's named option, "a branch defn rebinds main's Var for that context instead of creating a new Var", is **already
how our SCI fork behaves** for every compiled first-party function (probe above). The remaining larger step would be to
bind the base context's SCI Var, not the JVM Var, for all ~70 registered predicates in main's own projection, so that no
recompile or re-arm ever happens: validation would read whichever context executes. It is not better now, for three
reasons. (1) It puts a contextual lookup on main's validation path: `sci.impl.vars/setContextual` is set on a Var's
first rebinding in any context, and every invoke of that Var afterwards consults `sci.ctx-store/*ctx*`, process-wide.
(2) It changes the law: a compiled host function called from a branch (its body linked to the JVM Var, its contract
validating through the SCI Var under the branch's `*ctx*`) would validate with the branch's predicate while its body
calls main's, and the writer thread, with no `*ctx*`, would answer main. (3) It needs every predicate Var in the base
(today only copied namespaces are) and a decision on what `reinterpret-inherited-callers!` becomes. What it would take:
a ruling that host-bound contract validation may follow the executing context, a measured per-call cost of a contextual
SCI Var invoke on main's hot validators, and one owner's cut across `seon.schema/runtime-predicate`,
`seon.sci.eval/build-base-ctx` and `reinterpret-inherited-callers!`. Revisit if phase 3 shows the transition's re-arm
dominating ordinary branch work.

## Checked, assumed, observed

Checked (SCI, own branch, HEAD 9b7850bcd): the base holds a `sci.lang.Var` for `seon.edit/valid-form-operation?` with no
generation; the fork's `defn` keeps it and writes `:sci/var-bindings` (`result/eccd73fa3f60a`); the projection binds a
`clojure.lang.Var` and `:my.edit/form-request` still validates true after the redefinition (the defect, reproduced a
third time). Read, not run: `eval-def`, `owns?`, `update-binding!`, `fork`, `-fn-schema`, `eval`, `projection-with-declarations`,
`arm!`, `interpret-affected!`, `bind-lazy-members!`, `copy-host-var`. Assumed: that the fork's `:sci/var-bindings` is
copied into a test's fork of the context (`fork` copies the env map; not probed); lesson 3's cause (phase 1 row).
Observed, to file: the redefinition request 1,880 ms with `:test-step` 1,174 ms for one 16 ms test; `seon.db/declaration-content-key`
self time 10.9 ms on the branch against 1.7 ms on main (third report of this signal). My branch keeps the probe's
`valid-form-operation?` redefinition; it is disposable.
