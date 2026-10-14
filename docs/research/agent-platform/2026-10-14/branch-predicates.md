---
title: "Branch predicates: a registered schema predicate runs main's code on a branch"
type: research
status: dated evidence and recommendation (lane branch-predicates, HEAD 108c2e077); needs root to schedule
---

# Branch predicates: a registered schema predicate runs main's code on a branch

Lane branch-predicates, 2026-10-14, HEAD 108c2e077. Evidence only; the recommendation needs root to schedule it,
because it spans `seon.schema` (host-bound) and `seon.sci.eval` (a critical-path file the chain lanes hold).

## Finding, reproduced

Agent `lane-branch-predicates` (SCI, its own branch) redefined `seon.edit/valid-form-operation?` to answer `false`,
then called two interpreted functions whose contracts differ only in how they name that predicate:

| contract input | result on the branch | whose predicate ran |
|---|---|---|
| `:my.edit/form-request` (registry reference) | `true` (admitted) | main's compiled Var |
| `[:fn seon.edit/valid-form-operation?]` (named directly) | `:seon.instrument/contract-error` | the branch's |

`(seon.sci.eval/context-predicates ctx '[:fn seon.edit/valid-form-operation?])` answered the branch's function. The
compiled `seon.edit/form` admitted the same request as well (expected: compiled code sees a change only after merge).
So one predicate symbol resolves two ways in one context, and the choice depends on whether a contract inlines it or
reaches it through the registry. This is lane done-path's finding (commit 108c2e077) reduced to one predicate.

## Why

- `seon.schema/runtime-predicate` resolves a `[:fn sym]` predicate to the JVM Var (`requiring-resolve`); every
  projection binds it through `seon.schema/projection-registry`, which compiles each registry entry once with that
  Var. A branch's projection is built from the branch's forms with the same process-wide bindings.
- `seon.sci.eval/arm-contract!` patches only the contract form: it merges `seon.sci.eval/context-predicates` (the
  predicate symbols written literally in the contract, resolved through `sci/resolve`) into the projection with
  `seon.schema/with-predicate-functions`. `seon.schema/contract-product` uses those for the contract form's own
  `[:fn …]` nodes, but a referenced key such as `:my.edit/form-request` comes compiled from
  `:seon.schema.projection/registry`, holding main's Var. `seon.turn.batch` does the same merge.
- `seon.schema/projection-with-declarations` treats a changed predicate binding as no change to any declaration:
  `affected-keys` derive only from changed forms and admissions, so even a projection advanced with new bindings
  would retain the old compiled entries (inspection; not reproduced).
- Hypothesis, not measured: `seon.schema/contract-product` caches products by contract form in the projection's
  `:seon.schema.projection/compiled` holder, which `with-predicate-functions` keeps; an unchanged contract form
  compiled earlier with main's Var would be returned to a branch that redefined its predicate.

## Count

Registered predicates, all qualified symbols (none is a function literal; `seon.schema/register-core-predicate!`
only asserts at load that the symbol names the Var):

- Schema EDN (`resources/seon/schemas/*.edn`): 98 `[:fn …]` sites; **79 first-party** (70 distinct predicates,
  `seon.*`/`my.*`), 19 library (`clojure.core/ifn?` 12, `malli.core/schema?` 2, one each of `tagged-literal?`,
  `seq?`, `var?`, `bytes?`, `clojure.string/blank?`).
- Inline in `:malli/schema` contracts under `src/`: 34 first-party sites, 13 distinct predicates.
- Of the 70, most are host-object type checks (`seon.flow/channel?`, `seon.db/connection-object?`, …) that a branch
  has no reason to redefine. The ones with domain logic a branch plausibly changes: `seon.edit/valid-form-operation?`,
  `seon.fs/content?`, `seon.fs/write-precondition?`, the `seon.error/*-complete?`/`ordered-*`/`*-valid?` family (9),
  `seon.program/edn-round-trip-symbol?`, `seon.task/symbol-name?`, `seon.schema/malli-form?`,
  `seon.schema/pull-selector?`. The same gap applies to a predicate that is unchanged but is an affected caller of a
  changed function (done-path's case: `single-form` changed, `valid-form-operation?` interpreted because it calls it).

Registration sites: `src/seon/{db,blob,error,print.cljc,schema,flow,ai,call_preparation,await,env,cluster,
cluster/store,sci/kernel,test/runner,sci/admit,sci/eval,render/hiccup,render/var,render/web,turn/world}.clj`,
`src/my/{edit,shell,fs}.clj` (`rg register-core-predicate! src`).

## Options

1. **The context's projection binds predicates as the context resolves them** (recommended). Where a branch
   context's projection is installed (`seon.sci.eval/advance-context-projection!` and its callers in the install
   path), its `:seon.schema.projection/predicate-functions` become `context-predicates` for the predicate symbols
   that context interprets, holding the SCI Var (an `IFn`) rather than its value, the same "retain the Var" rule
   `runtime-predicate` states, so a later redefinition on the branch needs no recompile.
   `seon.schema/projection-with-declarations` then counts a changed binding as a change to every key whose form
   names it (one scan of the population's predicate symbols, already cached as `::declaration-predicates`), and the
   existing reverse closure recompiles their referrers and dependent function contracts.
   Then `arm-contract!`'s and `seon.turn.batch`'s merges of `context-predicates` are deleted: one resolution, one
   owner. Cost: zero on main and on every branch that interprets no predicate (equal bindings, no work); otherwise
   proportional to the keys naming the changed predicates and their referrers, once per program change that starts
   interpreting a predicate; per call, one SCI Var deref. Size: about 15 lines in `seon.schema`, 10 in
   `seon.sci.eval`, minus the two merges.
2. **Recompile at arming only.** Extend `context-predicates` to walk the contract's transitive registry references
   and, when any binding differs, arm against a projection rebuilt with those bindings. Smaller and confined to
   `seon.sci.eval`, but it covers armed contracts only (not `seon.schema/projection-validator` or Malli calls from
   interpreted code with the context's projection), keeps two resolution paths, and rebuilds per armed function
   unless it is cached by `[projection bindings]`.
3. **Resolve through the current SCI context at call time** (compile `[:fn sym]` to a function reading
   `sci.ctx-store/get-ctx` on each call). This needs no recompile, but it is ambient state, so it would also change
   compiled host code validating on a branch's behalf, against "compiled code sees a change only after merge".
   Rejected.

## Red row (for the slice that implements option 1)

A row in `seon.schema-test` (or the SCI eval tests): on a test branch whose context redefines
`seon.edit/valid-form-operation?` to answer `false`, an interpreted function with contract
`[:=> [:cat :my.edit/form-request] :boolean]` refuses a request main's predicate admits, asserted on the refusal's
`:seon.error/type` `:seon.instrument/contract-error`; the same row with the predicate inlined already refuses, so it
stays green throughout. A second row: done-path's case, where `single-form` changes and `valid-form-operation?` is
only an affected caller.

## Also observed

- `seon.db/declaration-content-key` self time 10.6–15.7 ms on the branch against 1.7 ms on main (the reply's
  `:seon.cost/diff`, `:unknown :few-calls`): 6–9× worse, one probe, not investigated.
- Inlining `{:gen/gen my.edit/form-request-generator}` in a `seon.edit` definition made the REPL auto-require
  `my.edit` from `seon.edit`, which is a cyclic require; it was refused with a clear diagnostic, yet the Var stayed bound
  in the context. That behaves as documented; it is noted here as friction.
