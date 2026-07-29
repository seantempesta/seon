---
type: issue
status: open
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
;; => {:threw "seon.schema.internal/assert-compilable-schema! violated its
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
