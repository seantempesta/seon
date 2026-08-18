---
type: issue
status: resolved
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
⟹ "seon.program/with-contract-facts violated its contract (invalid-output)"

;; an agent's first ns form with an alias
(ev/evaluate {… :seon.cluster.run.form/source
              "(ns probe.alias (:require [clojure.set :as set]))"})
⟹ "seon.program/declaration-row violated its contract (invalid-output)"

;; the same form with no alias now settles
(ev/evaluate {… :seon.cluster.run.form/source
              "(ns probe.sample (:require clojure.set))"})   ; => ok
```

Direct, at the registry:

```clojure
(schema/valid-candidate-value? :seon.db/ref
  {:seon.ns.alias/local 'set :seon.ns.alias/target-ns 'clojure.set})
⟹ false
(schema/valid-candidate-value? :seon.fn/fn
  {:seon.fn/sym "a/b" :seon.schema.admission/source :agent
   :seon.fn/ns [:seon.ns/name 'a] :seon.fn/source "x"
   :seon.fn/arities [{:seon.fn.arity/order 0}]})
⟹ false
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

## 2026-08-08 — resolved: option 1 landed, derived at the registry

Owner ruling: option 1, approved verbatim. `e7bafc957`.

### What was built

`seon.schema.form/widen-component-children` compiles a component
attribute's child position as `[:or <declared child> :seon.db/component-entity]`,
DERIVED from the `:seon.db/component true` property the form already
carries, and `seon.schema/compilable-form` applies it. That function is
the former `bind-predicates`, renamed because it is now THE preparation
choke point every compile path passes through — a new compile site
cannot forget the widening, which is what makes the class dead rather
than merely fixed at 46 sites.

`:seon.db/component-entity` is declared once in
`resources/seon/schemas/seon.db.edn` as a non-empty `:map-of`
`:qualified-keyword` → `:seon.schema/value`. Only the COMPILED shape
widens: `declaration-population`, `canonical-definition`, and the
Datahike bridge (`seon.schema.datahike/malli->datahike-attr-in`, which
reads raw forms) all keep reading the narrow declaration, so nothing
persisted or generated changes.

### The stated risk, discharged

`matching-shapes-in` re-checked against the live population:
`:seon.db/component-entity` is a `:map-of`, so it is never a shape row
(`(contains? (:seon.schema.projection/shape-rows p)
:seon.db/component-entity)` → `false`). Selection still picks each row's
own family — a `:seon.fn/fn` row matches `:seon.fn/fn`, a `:seon.ns/ns`
row matches `:seon.ns/ns`, and a bare component entity
(`{:seon.fn.arity/order 0}`) matches NOTHING. Confinement is pinned too:
`:seon.db/ref` itself, and a non-component ref attribute such as
`:seon.fn.arity/input`, still refuse an entity map, and an empty map is
refused everywhere.

### Regression

`seon.schema-test/a-component-bearing-row-validates-its-own-declared-shape`
DERIVES its subjects from the declaration population rather than listing
them, asserts every declared collection kind is covered (`:and`,
`:vector`, `:set`), and validates each component attribute against both a
carried entity and a persisted ref. A component attribute declared
tomorrow joins the test automatically.

### Live proof

Cluster `comp` in isolated root `tmp/lanes/component-ref`, booted from a
freshly published `current-src` containing this change. Zero
`declaration-row` and zero `with-contract-facts` contract violations
exist anywhere on the cluster (six distinct error signatures, none of
this class). The receipts are recorded in
[Let an agent define a contracted function](an-agent-cannot-define-a-contracted-function.md).
