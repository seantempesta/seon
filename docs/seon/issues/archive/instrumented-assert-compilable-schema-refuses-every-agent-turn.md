---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, instrumentation, runtime, agent]
---

# Instrumented `assert-compilable-schema!` refuses every agent turn

## Problem

In a development cluster (`:seon.config/on-core-error :panic`, which arms
instrumentation on every contracted public var), NO agent can ever execute a
form. The `:resume` branch of `seon.cluster.loop/turn` begins with
`seon.sci.eval/acquire!`, which calls `activate-program-schemas!` →
`seon.schema/activate-projection!`. That function passes `(get compiled-forms k)`
to `seon.schema.internal/assert-compilable-schema!`, whose declared input
schema is `[:cat :map :keyword :seon.schema/definition :map]`.

`compiled-forms` is the BOUND population: `bound-forms` has already replaced
core-predicate symbols with the predicate FUNCTION objects. A bound form is
therefore not an EDN-readable Malli form, `:seon.schema/definition`
(`::malli-form`) rejects it, and the armed `:panic` reporter throws.

The throw escapes the turn transform, becomes a core fault on the agent
graph's error channel, and the run is left OPEN, HELD, PLANNED, with no
receipt — a wedge that no later pass can clear, because every later pass dies
at the same call.

## Evidence

Isolated scratch cluster `seam-reaudit4-20260729` under
`tmp/seam-reaudit4-operator-root`, source at `06a8e8626` (i.e. INCLUDING the
claimed instrumentation class fix `b69310347`).

One form through the production entry point:

```clojure
(let [db @(conn) ctx (sci.core/fork (seon.sci.eval/base))]
  (try {:acquired (seon.sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db db})}
       (catch Throwable t {:threw (ex-message t)
                           :data (select-keys (ex-data t) [:seon.error/kind])})))
⟹ {:threw "seon.schema.internal/assert-compilable-schema! violated its
;;             contract (invalid-input): [nil nil [\"must be a parseable,
;;             EDN-readable Malli form\"]]",
;;     :data #:seon.error{:kind :seon.instrument/contract-violated}}
```

The recorded `:seon.instrument/args` on the durable fact shows the offending
value is a bound form carrying function objects, e.g.

```text
:seon.flow/active-work [:fn {:gen/gen {…}} {:seon.sci.admit/opaque
                        "seon.flow$atom_reference_QMARK_"}]
```

Live consequence, driving one agent through a real trigger with a stubbed
provider: two runs opened, two plans frozen, ZERO receipts, four
`:seon.instrument/contract-violated` facts with
`:seon.error/proc :seon.cluster.agent/turn`, and both runs left open, held and
planned. `(count (seon.instrument/instrumented))` = 357, and
`#'seon.schema.internal/assert-compilable-schema!` is among them.

Not an artifact of one JVM: after `kill -9` and a fresh operator boot of the
same root (`bin/seon-fresh --seon-root … start seam-reaudit4-20260729`, new
PID 77445), the first turn produced the same
`:seon.instrument/contract-violated` fact again.

Removing the wrappers (`seon.instrument/remove!` — 1 wrapper stripped from the
relevant path) makes the identical drive complete correctly, which is how the
rest of the refusal-seam audit was able to run at all.

## Owner

`seon.schema.internal/assert-compilable-schema!`'s declared contract and its
one production caller `seon.schema/activate-projection!` in `src/seon/schema.cljc`.
The value that caller passes is a COMPILED/BOUND form, not a
`:seon.schema/definition`. Either the contract must name the bound-form shape
it actually receives, or the caller must pass the unbound EDN form it claims
to. Widening to `:any` is not acceptable.

## Acceptance

- `seon.sci.eval/acquire!` completes against a boot-populated database with
  instrumentation armed at `:panic`.
- A live dev cluster drives one agent turn end to end through `:resume` with
  zero `:seon.instrument/contract-violated` facts.
- A recurring test exercises the acquisition path with instrumentation ARMED —
  the current green gate (549 tests / 2314 assertions) passes precisely
  because no test covers that combination.
- No run is left open, held and planned with no receipt after an agent pass.

## Resolution

Activation legitimately consumes bound schema forms. Predicate binding is the
construction that prevents Malli from creating a second SCI evaluator while
compiling the acquired database population, so moving the assertion before
binding would have checked a different value from the one activation compiles.

`seon.schema.internal/assert-compilable-schema!` now names that activation-time
boundary as `::bound-definition`. The schema admits the ordinary outer Malli
forms whose nested predicate and generator slots may already contain function
objects; it does not widen the boundary to `:any`. Source declarations retain
their separate `:seon.schema/definition` contract and remain EDN-readable.

The source-definition predicate now compiles a decoded declaration after
binding its core predicate symbols. This validates the same source meaning that
activation will compile while preserving the EDN round-trip requirement.

## Proof

`an-instrumented-dev-cluster-completes-one-agent-turn` starts a real scratch
cluster under `tmp/instrumented-acquire-test/`, stubs only the hosted model
response, arms `seon.instrument/apply!` at `:panic`, and proves that
`assert-compilable-schema!` is wrapped. It then transacts a message to the root
agent and observes one settled receipt, a closed run, released process custody,
and zero `:seon.instrument/contract-violated` facts. The test always removes
instrumentation and stops and deletes its scratch cluster.

The regression alone passes 1 test and 7 assertions. The focused schema,
instrumentation, and SCI eval gate passes 21 tests and 89 assertions. After the
terminal-refusal settlement fence landed at `6ab646eb6`, the full `bin/test`
gate passes 552 tests and 2,360 assertions, with zero failures and zero errors.
The full gate runs both the settlement-fence regressions and the instrumented
scratch-cluster turn, proving there is no interaction between those boundaries.
