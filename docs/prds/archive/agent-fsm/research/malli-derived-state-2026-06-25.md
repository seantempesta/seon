---
type: research
status: active
tags: [research, schema, agent]
---

# Malli representation of DERIVED / COMPUTED state

How to spec a value that is a function of other primitives (the agent `state`
enum, `turns-remaining`, `ms-remaining`, open-todo / unread-inbound counts)
WITHOUT registering a fake stored attribute that is "DERIVED — never transacted".

## TL;DR

- **Malli has NO first-class "derived / virtual / computed key" concept.** A
  `:map` schema is only explicit + optional + `:default` entries
  (`core.cljc:1210-1354`); there is no entry whose value is declared as a
  function of its siblings. Derivation is **composition**, not a built-in.
- **The idiomatic answer is to spec the DERIVATION FUNCTION, not a phantom
  attr.** Write `derive-state` as a `:=>` function schema whose INPUT is the
  primitives (or the entity) and whose OUTPUT is the enum:
  `[:=> [:cat ::agent-record] :seon.agent/state]`. The enum is registered as a
  **value shape** (a plain `:enum`), referenced as the function's output — never
  attached to the `:seon.agent` entity map and never transacted. That kills the
  "registered-but-never-transacted" smell: the keyword stops being an attribute
  and becomes a return type.
- A `:=>` **`:guard`** child (`core.cljc:2138-2202`, child #2, validated as
  `[args value]` at `2219`) lets the SPEC ITSELF state the invariant
  `state = f(terminated-at, open-run, paused-at)` as a `:fn` over the
  (inputs, output) pair — the schema documents the derivation rule, and
  instrumentation checks it on every call.
- For validating a fully-built snapshot, use `:multi` dispatching on the
  derived `state` (`core.cljc:1861-1938`) so each state demands the primitives
  that justify it (`:paused` ⇒ must carry `paused-at`). `:fn`
  (`core.cljc:1761-1808`) handles a single cross-field invariant inline.
- A transformer CAN compute a field during decode (`default-value-transformer`
  / `:default/fn`, `transform.cljc:484-520`) but the default thunk gets no
  sibling access — only a custom `:map` decoder does. Use this ONLY if you want
  `state` to materialize as a real (but ephemeral) map key for the inspector;
  it is heavier than the function-schema route and not needed for validation.

## What Malli actually offers (cited)

### `:map` — no computed/virtual keys (`core.cljc:1210-1354`)

`-map-schema` builds entries from an `entry-parser`; each entry is
`[key {:optional ..} schema]`. The only non-literal behaviour is `:default`
entries (`-default-entry-schema`, line 1228) and `closed` (line 1255). There is
no hook for "this key's value is computed from sibling keys". So a derived value
is NOT expressible as a map entry — confirming the smell: putting
`:seon.agent/state` in the `:seon.agent` record (`agent.cljs:320`) forces it to
look storable.

### `:fn` — arbitrary predicate over one value (`core.cljc:1761-1808`)

`(eval (first children))` (line 1772) → the child is a function form, validated
as a predicate (`-safe-pred`, line 1780). Cross-field invariants on a single map
value live here, e.g. `[:fn (fn [m] (consistent? m))]`.

### `:=>` function schema — INPUT `:cat` → OUTPUT, plus a `:guard` (`core.cljc:2138-2231`)

- Children are `[input output guard?]` (`-check-children! :=> ... 2 3`,
  line 2149); input must be `:cat`/`:catn` (line 2154).
- `-function-info` (2195) exposes `{:input :output :guard ...}`;
  `-instrument-f` (2203-2221) wraps the fn so **input, output, AND guard are
  validated on every call**. The guard is checked as `(validate-guard [args value])`
  (line 2219) — i.e. a `:fn` over the `[arglist return]` pair. This is the
  mechanism that lets a spec encode `state = f(primitives)`: the guard relates
  the inputs to the derived output.

### `:function` — multi-arity wrapper (`core.cljc:2233-2305`)

Groups several `:=>` arities; same instrument story. Use only if `derive-state`
needs multiple arities (it doesn't).

### `:multi` — dispatch validation (`core.cljc:1861-1938`)

`(eval (:dispatch properties))` (line 1879) → dispatch can be the derived
accessor itself; each branch is a full schema. Validates "a snapshot in state X
must look like Y". Good for the **state-snapshot** record where each state
implies different required primitives.

### Transformers can COMPUTE during decode (`transform.cljc:484-520`)

`default-value-transformer` reads `:default/fn` (line 488, `m/eval`'d) and
`add-defaults` (497) fills MISSING map keys via a thunk `(fn [] ...)` (line 504)
— but the thunk receives no map, so it can't see siblings. To compute from
siblings you'd write a custom `{:decoders {:map {:compile (fn [schema _] (fn [m] ...))}}}`
transformer in the same shape as `add-defaults` (lines 497-515), reducing over
the whole map. Possible, but it MATERIALIZES the value — only do this for a
display projection, never to satisfy validation.

## Recommended pattern for our case

Decouple three things that the current code conflates (the smell): the enum's
VALUE shape, the agent ENTITY record, and the DB attribute.

### 1. Register the enum as a VALUE shape, not an entity attribute

Registering a keyword in Malli does NOT make it a datom — what makes
`:seon.agent/state` storable is (a) its presence in the `:seon.db/entity` map
`:seon.agent` (`agent.cljs:315-326`) and (b) `set-state!` transacting it. Remove
it from BOTH. Keep a registered shape, but name it as a return type:

```clojure
;; A VALUE shape — the four behavioral classes. Referenced as a function
;; OUTPUT, never as a :seon.db/entity attr, never transacted.
(schema/register! ::state [:enum :running :idle :paused :terminated])
```

(If callers still want `:seon.agent/state` as the keyword, fine — but it must
NOT appear inside the `:seon.agent` entity map. The keyword being registered is
not the smell; living in the entity record + being transacted is.)

### 2. Spec the DERIVATION FUNCTION (the idiomatic answer to Q3)

The derivation is the spec'd unit. Its output is `::state`; a `:guard`
documents the rule. The primitives are the named input:

```clojure
;; The primitives state is a function of. These ARE real datoms.
(schema/register! ::derive-input
  [:map
   [:seon.agent/terminated-at        {:optional true} :inst]
   [:seon.agent.run/open?            :boolean]            ; derived upstream: any open run?
   [:seon.agent.run/paused-at        {:optional true} :inst]])

(defn derive-state
  "Agent state DERIVED from primitives — never stored.
   :terminated if terminated-at; else :idle if no open run;
   else :paused if the open run is paused-at; else :running."
  {:malli/schema
   [:=> [:cat ::derive-input] ::state
    ;; :guard — the spec ITSELF asserting state = f(primitives).
    ;; Validated as [args return] on every call (core.cljc:2219).
    [:fn (fn [[{:seon.agent/keys [terminated-at]
                :seon.agent.run/keys [open? paused-at]} state]]
           (= state (cond terminated-at      :terminated
                          (not open?)        :idle
                          paused-at          :paused
                          :else              :running)))]]}
  [{:seon.agent/keys [terminated-at]
    :seon.agent.run/keys [open? paused-at]}]
  (cond terminated-at :terminated
        (not open?)   :idle
        paused-at     :paused
        :else         :running))
```

The guard is redundant with the body BY DESIGN — that redundancy is the spec
documenting the derivation rule and catching a body that drifts from it. (If the
duplication grates, drop the guard and let `::state` as the output be the
contract; the `:=>` output alone still removes the phantom attr — the guard is
the optional "express the rule in the schema" upgrade.)

### 3. The state-snapshot as a derived projection (Q2 + Q4)

A snapshot bundles all derived values. Spec it as a registered VALUE record
(map-out of a function), validated but never transacted (no `:seon.db/entity`):

```clojure
(schema/register! ::snapshot
  [:map
   [::state            ::state]
   [:seon.agent/id     :seon.agent/id]
   [::turns-remaining  :int]
   [::ms-remaining     {:optional true} :int]
   [::total-turns      :int]
   [::open-todos       :int]
   [::unread-inbound   :int]])

(defn state-snapshot
  "Full derived view of an agent — pure fn of the db value, never stored."
  {:malli/schema [:=> [:cat ::snapshot-request] ::snapshot]}
  [{:seon.agent/keys [id]}]
  ...)
```

For inspector/fingerprint validation (Q4): validate the snapshot with `m/validate`
against `::snapshot`. If different states require different primitives, promote
`::snapshot` to a `:multi {:dispatch ::state}` so each branch demands its
justifying datoms (`:paused` ⇒ `[::paused-at :inst]`). `m/parse` against the
`:multi` even tags the result with the matched state.

## Why this kills the smell

- `::state` is an `:enum` referenced ONLY as a function output / snapshot value
  — Malli registers it as a SHAPE, and nothing in `seon.db`'s
  malli→datahike bridge ever sees it as an entity attr, so it cannot become a
  datom. No "registered but never transacted" attr exists, because it is not
  registered as an attr.
- `derive-state` / `state-snapshot` are ordinary spec'd functions
  (map-in/value-out), instrumented like everything else; the `:=>` output (and
  optional `:guard`) make the derivation rule machine-checked.
- Matches the reactive-context principle: state is a derived view of the DB at
  read time; the only datoms are the real primitives
  (`terminated-at`, run `paused-at`, run open/closed, turn log) — each a genuine
  data + control axis.

## Migration notes (for the implementer)

- Remove `[:seon.agent/state :seon.agent/state]` from the `:seon.agent` entity
  map (`agent.cljs:320`) and stop `set-state!` transacting it
  (`agent.cljs:356-380`). The four transitions become writes to the REAL
  primitives (e.g. `terminated-at`, run open/paused), not a state datom.
- `current-state` (`agent.cljs:347-354`) becomes `(derive-state ...)` over the
  pulled primitives instead of `(:seon.agent/state entity)`.
- Inspector reads (`web/inspector.cljs:123,356,478,921`, `client.cljs:322,2119`,
  `ctx.cljs:1816`) call `derive-state` / `state-snapshot` rather than reading the
  attr. Confirm the enum value set: code today uses
  `[:active :idle :terminated]` (`agent.cljs:90`); the FSM decision is
  `:running/:idle/:paused/:terminated` — reconcile `:active`→`:running` and add
  `:paused` when you cut over.
