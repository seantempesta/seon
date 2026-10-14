---
title: "Predicates plan (Opus): a context's projection binds each predicate as the context resolves it"
type: research
status: independent plan for the predicates design round (lane predicates-plan-opus, HEAD e5dd2abd5); not read: other plans
---

# A context's projection binds each predicate as the context resolves it

What exists: `seon.schema/projection-registry` compiles each registry entry once, with each `[:fn sym]` predicate
replaced by its callable (`seon.schema/compilable-form`); the callable comes from the projection's
`:seon.schema.projection/predicate-functions` or else `seon.schema/runtime-predicate`, the JVM Var. Malli holds whatever
callable it is handed: `malli.core/-fn-schema` calls `malli.core/eval` on its child, and a non-symbol IFn takes the
`:else` branch unchanged, so a held Var (JVM or SCI) is late-bound by Malli itself. A branch's projection is built from
its forms with the same process-wide Vars. Then `seon.sci.eval/arm-contract!` and `seon.turn.batch/write-entity` patch
only the contract form's own predicates (`seon.sci.eval/context-predicates`, holding the *value*). So a predicate
reached through a key runs main's code. The approach: the *projection a context uses* is the one that binds predicates
as that context resolves them, held by that context, and it binds the SCI Var, not its value.

## 1. One owner

**The SCI context owns "how a predicate resolves for this evaluation"**, as it owns how every symbol resolves.
Concretely, one function `seon.sci.eval/context-bound-projection` `[ctx base]` returns `base` with every predicate
symbol that `ctx` holds apart from the base context bound to the Var `(sci/resolve ctx sym)`. Those are the
predicates the context defines (by evaluation) or interprets (as an affected caller, `interpret-affected!`, or a lazy
member, `bind-lazy-members!`); the set is `(::interpreted snapshot)` plus the evaluated Vars, intersected with the
population's predicate symbols. With nothing held apart it returns `base` itself (`identical?`): main and every branch
that interprets no predicate pay nothing and compile nothing.

Where it lives: in the context's own acquisition (`::kernel/program-snapshot`, already per context, already carrying
`:seon.schema/projection`), as `{:base <projection> :bindings <map> :bound <projection>}`. It never goes in
`seon.env` state: `advance-context-projection!`'s state is cluster-shared (lesson 1 of branch-predicates-impl).
`seon.sci.kernel/context-projection` returns `:bound`. This is the one reader every consumer goes through:

- **Arming** (`arm-contract!`, called by `seon.sci.arm/arm!`, `install-function-contract!` and `install-selected!`)
  arms against `context-bound-projection` over the projection it was handed (the arming projection of the batch).
- **Instrumentation**: `seon.instrument/wrap-interpreted` receives that same projection, unchanged.
- **The writer's contract facts** (`seon.turn.batch/write-entity` → `seon.fn/contract-facts-row`) read the same
  projection, so a predicate defined only on the branch (`f40.named-predicate/even-int?`, with no JVM Var) is
  bound and compiles (lesson 3).
- **Validation from interpreted code**: code that asks its context for the projection gets `:bound`. The checked
  writer's attribute validation and every compiled caller keep the database's carried projection (main's Vars). This
  is the existing rule: a host-bound caller keeps its compiled Var and sees a change only after merge, and it is
  stated in that docstring, not added as an exception.

Malli composition: no new registry kind. The bound projection is an ordinary `seon.schema/projection-registry`
generation (`malli.registry/simple-registry` sealed over `malli.registry/lazy-registry` and
`malli.registry/composite-registry`); only its predicate callables differ. Late binding is Malli's own
(`malli.core/-fn-schema` holds the IFn; `malli.core/-loaded-qualified-value` already keeps the JVM Var for the same
reason).

## 2. How a later redefinition reaches armed functions

There are two events, and they are handled differently.

- **The same Var is redefined again** (the common case: an agent iterating on a predicate). There is no work.
  The projection holds the SCI Var, and its `invoke` reads the Var's current root in the executing context. Checked at
  the REPL: a second `defn` of `seon.edit/valid-form-operation?` on my branch answered the *same* Var (`identical?`
  true), and a validator compiled earlier over `[:fn that-var]` followed the new body.
- **A predicate first becomes held apart from the base** (its first `defn` on the branch creates a new Var, which I
  checked: `identical?` to the base's is false, in a fresh namespace object; or it is first interpreted as an affected
  caller). Then the bindings map changes, and two things follow:
  1. **Recompile** (`seon.schema/with-bound-predicates`, as in the impl patch): the keys whose form names a rebound
     predicate seed the existing reverse closure in `projection-with-declarations`, which recompiles their referrers
     and dependent function contracts. The keys are found through an inverse index `predicate → keys/contracts`, a
     `seon.cache` kind keyed by the forms value (built in the same single walk as `::declaration-predicates`). This
     replaces the patch's scan of all 3,811 forms per call. My hypothesis is that the scan, plus `sci/resolve` over
     all 109 held bindings at every arming, is most of the measured 1,225 ms; it is not profiled.
  2. **Re-arm** the context's already-armed functions whose contract reaches a recompiled key
     (`function-dependents-of` on the bound projection, intersected with the context's armed set, minus the functions
     this same write reinstalls anyway through `reinterpret-inherited-callers!` and `interpret-affected!`). Re-arming is
     `arm-contract!` again from the Var's held contract.

  Cost is proportional to the keys naming the predicate, plus their referrers, plus the armed dependents, and it is paid
  once per predicate per context. Measured for `valid-form-operation?`: 2 keys (`:my.edit/form-request`,
  `:seon.edit/request`) and 7 function dependents. Per call, an SCI Var invoke replaces a JVM Var invoke (it was below
  the noise of my interpreted loop).

Why re-arm rather than late-binding the whole projection inside each wrapper: re-arming is rare and local (it happens
when a binding changes), and it keeps `wrap-interpreted`'s compiled validators. Late binding would add a projection
lookup on every call of every armed function. I rejected binding contextual SCI Vars for all ~70 predicates in every
context's projection (no recompile, ever) because it puts a contextual Var lookup on main's SCI validation path, and
it does not cover the `defn` case, which makes a new Var. That option becomes the better one only if SCI eval ever
rebinds the base's Var per context on `defn` instead of creating a new one. That decision belongs to the
critical-path owner and would also remove `reinterpret-inherited-callers!`; I name it, but do not plan it.

No cluster-shared mutable state per agent: the bound projection is a value in the context's own snapshot, and the
inverse index is a content-keyed `seon.cache` kind over immutable forms. I deliberately do *not* key a shared cache by
bindings. On my branch the predicate and its affected caller both resolved to Vars distinct from the base's, and
whether a held Var is context-owned or shared is an SCI-eval detail this design should not depend on.

## 3. What it deletes, what stays

It deletes `seon.sci.eval/context-predicates`, the `with-predicate-functions`/`context-predicates` merge in
`arm-contract!`, the same merge in `seon.turn.batch/write-entity`, and the docstring clauses describing them.
`BEHAVIOR-SPEC` §1's citation becomes `seon.sci.eval/context-bound-projection`. On main, nothing changes and nothing
is compiled: bindings are empty and `base` is returned itself.

## 4. Phases (HEAD boots after each)

1. **seon.schema (host-bound, `--test`): a rebound predicate recompiles what names it.** This phase adds
   `seon.schema/with-bound-predicates`, the inverse index, and the rule that `projection-with-declarations` counts a
   replaced binding as a change and never drops a *supplied* binding (I suspect the latter is the cause of lesson 3:
   a binding that only an inline contract names is dropped as unused; this is a hypothesis). Red proof, new row in
   `seon.schema-test`: `a-rebound-predicate-recompiles-the-keys-naming-it`. Over a projection with
   `{:t/k [:fn t/p?] :t/r [:map [:a :t/k]]}`, advancing with `{'t/p? (constantly false)}` makes
   `(m/validate :t/r {:a 1} {:registry …})` false. Red on HEAD: the retained entry still answers true.
2. **seon.sci.eval + seon.turn.batch (critical path, chain lanes' window): one reader.** This phase adds
   `context-bound-projection` and the snapshot entry, points `seon.sci.kernel/context-projection` at it, has
   `arm-contract!` and `write-entity` read it, and deletes `context-predicates` and both merges. Red proof: the
   impl patch's `seon.turn.batch-test/a-registered-predicate-runs-as-the-branch-defines-it`, with both rows (the
   predicate redefined; `single-form` redefined so the predicate is an affected caller). Each row asserts
   `{:seon.error/type :seon.instrument/contract-error :seon.instrument/check :input}`. It must stay green:
   `a-contract-naming-a-predicate-defined-on-the-branch-is-admitted-and-armed`.
3. **Re-arm on a binding change.** Red proof, a third row of the same table: arm `bp.registered-predicate/takes` in
   one request, redefine the predicate in the next, then call `takes`, which is refused with the same error data (red
   after phase 2, since `takes` stays armed under the old projection). Cost proof:
   `seon.sci.acquisition-cost-test/a-context-recompiles-only-for-a-predicate-it-interprets` (in the patch), asserting
   on calls of `seon.schema/projection-with-declarations`: zero on main, zero on a branch that holds no predicate,
   positive on the first redefinition, zero after it. A `seon.profile/measure` on the predicate-redefinition request
   compares parent and change in the same run.

## 5. Checked vs assumed

Checked in SCI mode as agent `lane-predicates-plan-opus` on its own branch, HEAD e5dd2abd5:
- The context projection binds `seon.edit/valid-form-operation?` to a `clojure.lang.Var` (109 bindings).
- The problem reproduces. After the branch's `defn` answering `false`, `(m/validate :my.edit/form-request req {:registry …})`
  is true, while the branch Var and `[:fn branch-var]` answer false.
- The first `defn` creates a new Var; a later `defn` rebinds the same Var, and a validator compiled earlier follows it.
- The reverse closure of `:my.edit/form-request` is 2 keys and 7 function dependents (the forms number 3,811).

Assumed, not measured: where the patch's 1,225 ms goes; that dropping an unused supplied binding is lesson 3's
cause; that the context's armed set is derivable from the snapshot without a new fact.

Observed along the way, to file:
- A one-`defn` request took 1.9–2.4 s, with `:test-step` at 1.3–1.9 s for one 19–28 ms test.
- `seon.db/declaration-content-key` self time grew with each redefinition (3.9 → 8.5 → 22 ms, against 1.7 ms on main),
  the same signal branch-predicates reported.
- `sci/resolve` is not auto-required as an alias (`Unable to resolve symbol: sci/resolve`); `sci.core/resolve` works.
