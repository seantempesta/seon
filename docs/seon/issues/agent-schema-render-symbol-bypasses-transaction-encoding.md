---
type: issue
status: open
severity: blocker
tags: [issue, schema, render, database, class/n13, wave/rebirth-gap]
---

# Agent schema render symbol bypasses transaction encoding

## Problem

An agent can define a contracted render function and can register an ordinary
schema, but it cannot commit that qualified function symbol as the schema's
`:seon.render/ai` declaration. The terminal transaction hands the logical
symbol directly to Datahike, whose installed storage attribute is a string.
This makes the ruled agent-authored declared-render path unpublishable.

## Evidence

The isolated rebirth capability probe registered all eight plan attributes,
committed three evolving plan transactions, defined
`my.agents.rebirth/render-plan-ai` with a coherent contract, then evaluated:

```clojure
(seon.schema/register!
 :probe.rebirth.plan/plan
 '[:map
   {:seon.db/entity true
    :seon.render/ai my.agents.rebirth/render-plan-ai}
   [:probe.rebirth.plan/id :probe.rebirth.plan/id]
   [:probe.rebirth.plan/title :probe.rebirth.plan/title]
   [:probe.rebirth.plan/agent :probe.rebirth.plan/agent]
   [:probe.rebirth.plan/items
    [:vector
     [:map
      [:probe.rebirth.plan.item/id :probe.rebirth.plan.item/id]
      [:probe.rebirth.plan.item/text :probe.rebirth.plan.item/text]
      [:probe.rebirth.plan.item/status :probe.rebirth.plan.item/status]
      [:probe.rebirth.plan.item/completed-at
       {:optional true}
       :probe.rebirth.plan.item/completed-at]]]]])
```

The contract-coherence gate accepted the declaration. Settlement then failed
at Datahike with:

```text
Bad entity value my.agents.rebirth/render-plan-ai ...
value does not match schema definition. Must be conform to: string?
```

The complete reproduction is
[`tmp/rebirth/probe.clj`](../../../tmp/rebirth/probe.clj), and the exact receipt
is in
[`tmp/rebirth/scratch-root-4/rebirth-evidence.edn`](../../../tmp/rebirth/scratch-root-4/rebirth-evidence.edn).

The declaration contract is logically `[:or :qualified-symbol :string]`
(`resources/seon/schemas/seon.render.edn:4-5`). Ordinary `seon.db/transact!`
encodes logical values before Datahike (`src/seon/db.clj:1437-1453`). Runtime
program rows are instead assembled inside
`seon.cluster.run/receipt-settle-call`: `row-tx` returns the raw declaration
row (`src/seon/cluster/run.clj:1355-1404`), after the outer transaction encoder
has already run, just like the already-documented receipt read-evidence seam
at `src/seon/cluster/run.clj:1502-1519`.

## Owner

`seon.cluster.run/row-tx` owns runtime declaration transaction data, composed
with the one `seon.schema.datahike/encode-transaction-in` logical-to-storage
encoder. The fix belongs inside the transaction function after the candidate
projection exists; no renderer-specific string coercion and no second codec.

## Acceptance

- An agent-authored schema whose `:seon.render/ai`, `:seon.render/html`, or
  `:seon.render/form` property is a coherent qualified symbol commits in the
  same terminal transaction as its receipt.
- Raw Datahike stores the declared encoded representation; `seon.db` reads the
  logical qualified symbol.
- The existing contract-coherence refusal remains loud for a missing or
  incompatible render function.
- The rebirth probe publishes the current-plan declaration and renders the
  current plan without a production workaround.
