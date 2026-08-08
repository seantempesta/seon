---
type: issue
status: open
severity: blocker
tags: [issue, schema, runtime, agent, live-drive]
---

# Admit a component value under its own attribute's declared shape

## Problem

Every component attribute in the registry is declared
`[<collection> {:seon.db/component true} :seon.db/ref]`, and
`:seon.db/ref` is `[:or :int :string [:tuple :keyword
:seon.db/lookup-ref-value]]`. A component's own entity map — the value
the producer actually builds, and the value Datahike's transaction data
grammar expects for a component — satisfies none of those three arms.

So a row that carries its components is refused by its own declared
shape. Under instrumentation that is not theoretical: the function that
builds the row throws, and the agent's form dies.

This is the second half of
[Let an agent define a contracted function](an-agent-cannot-define-a-contracted-function.md).
That note's first half — `declaration-row` omitting
`:seon.schema.admission/source` — is fixed. With it fixed, the same agent
`defn` now fails one frame later, at `seon.program/with-contract-facts`,
because the row it returns carries `:seon.fn/arities` and `:seon.fn/ast`
component entities.

## Evidence

Fresh cluster `declrow` in isolated root `tmp/lanes/declrow`, booted
2026-08-08 from a freshly published `current-src` that already contains the
admission-source fix.

```clojure
;; an agent's first defn
(ev/evaluate {… :seon.cluster.run.form/source
              "(defn ^{:malli/schema [:=> [:cat :int] :int]} probe-fn [x] x)"})
;; => "seon.program/with-contract-facts violated its contract (invalid-output)"

;; an agent's first ns form with an alias
(ev/evaluate {… :seon.cluster.run.form/source
              "(ns probe.alias (:require [clojure.set :as set]))"})
;; => "seon.program/declaration-row violated its contract (invalid-output)"

;; the same form with no alias now settles
(ev/evaluate {… :seon.cluster.run.form/source
              "(ns probe.sample (:require clojure.set))"})   ; => ok
```

Direct, at the registry:

```clojure
(schema/valid-candidate-value? :seon.db/ref
  {:seon.ns.alias/local 'set :seon.ns.alias/target-ns 'clojure.set})
;; => false
(schema/valid-candidate-value? :seon.fn/fn
  {:seon.fn/sym "a/b" :seon.schema.admission/source :agent
   :seon.fn/ns [:seon.ns/name 'a] :seon.fn/source "x"
   :seon.fn/arities [{:seon.fn.arity/order 0}]})
;; => false
```

46 declaration sites carry `:seon.db/component true`, in
`seon.fn.ast`, `seon.fn.arity`, `seon.fn.argument`, `seon.fn.binding`,
`seon.fn.binding.child`, `seon.fn.binding.entry`, `seon.ns`,
`seon.activation`, `seon.bootstrap.plan`, `seon.cluster.run`,
`seon.context.capture`, `seon.maintenance.result`,
`seon.maintenance.receipt`, `seon.schema.shape`, `seon.source`, and
`seon.test.result`. Every one has the identical shape, which is why this
is one class and not 46 defects.

## Why this needs a ruling before a fix

Three resolutions are available and they differ in what the registry
means afterwards, so this belongs to the owner rather than to a repair
lane. Simplest viable first; the recommendation is marked.

1. **A component ref admits the component entity, derived (RECOMMENDED).**
   `:seon.db/component true` is already declared in the enclosing
   properties, so the registry can compile that child position as
   "existing ref or the component's own entity" without a hand list.
   Guarantee: every component-bearing row validates as the tx-data it is.
   Cost: one change at the schema-registry owner. Risk: the widened child
   position participates in `matching-shapes-in`, so shape selection must
   be re-checked. Gives up nothing.
2. **Declare each component collection's element shape explicitly**
   (`[:or :seon.db/ref :seon.fn.arity/row]` and so on). Guarantee: the
   strongest — the component's own attributes are validated too. Cost: 46
   edits, several of them recursive (`:seon.fn.ast/child`). Risk: a new
   component attribute silently reverts to the broken shape, which is the
   hand-list failure mode.
3. **Separate the tx-data shape from the persisted shape.** Persisted
   `:seon.fn/fn` genuinely holds refs; `declaration-row` and
   `with-contract-facts` genuinely return nested entities. Today one
   schema name covers both. Guarantee: each value is described by the
   shape it actually has. Cost: highest — a second family of declarations
   across every component-bearing entity. Gives up the single name.

## Acceptance

- An agent defines a function with a complete `:malli/schema` and the form
  settles a receipt with the Var; the next form calls it.
- An agent evaluates `(ns … (:require [x :as y]))` and the form settles.
- One class regression asserts that a row carrying its component entities
  validates its own declared shape, for a component attribute of each
  collection kind (`:and`, `:vector`, `:set`), so a new component
  attribute cannot reintroduce the class.

## Owner

The schema registry (`resources/seon/schemas/seon.db.edn`,
`src/seon/schema.clj`, `src/seon/schema/form.cljc`), with
`seon.program/with-contract-facts` and `seon.program/declaration-row` as
the reporting call sites.
