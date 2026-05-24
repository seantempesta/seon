---
type: research
status: active
tags: [research, schema, malli, db]
---

# Malli schema references for identity attrs (id-shape unification)

## TL;DR

The id-shape `[:string {:min 14 :max 14}]` is duplicated across 9 sites (4 tx-meta scalars in `seon.db.cljs` and 5 identity attrs in `seon.agent.cljs`). **Recommendation: register one canonical `:seon.db/id` in `seon.schema.cljc` and reference it via the `[:and {props} :seon.db/id]` form for identity attrs, or bare `:seon.db/id` for non-identity scalars.** The `:and`-wrapping pattern works today with **zero bridge changes** — the existing `form->datahike-value-type` already recurses through `:and`, `form-properties` already reads outer-form properties for `:seon.db/identity`, and live REPL probes confirm correct validation, correct datahike schema generation (`:db/unique :db.unique/identity` flows through), and successful upsert behavior. The bare-keyword-ref pattern `[<kw> {props}]` that one might intuitively try is **rejected by Malli at schema construction** — it is not a supported form. `[:ref ...]` and `[:schema ...]` work in Malli but are unsupported by the bridge (would require new clauses). `:and` is the lightest, already-supported, and already-idiomatic-in-this-codebase choice.

## 1. Malli schema-reference capabilities (verified)

### Schema forms surveyed

Read `/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc`:

- `-and-schema` at line 825 — requires `>=1` children; all must validate. With one child, behaves as identity wrapper; outer properties are preserved via `m/properties`.
- `-ref-schema` / `-schema-schema` / `-lazy` at lines 235-241 — wrap a schema reference. Support outer properties.
- Bare keyword reference (e.g. `:probe/id`): resolved through the registry by `-lookup` at line 320. Has no syntactic slot for additional properties — it's an atomic form.
- `-reference?` at line 237 — `(or (string? ?schema) (qualified-ident? ?schema) (var? ?schema))`. Any qualified keyword can stand in as a reference.

### REPL probes (live pod, 2026-05-23)

```clojure
(schema/register! :probe/id [:string {:min 14 :max 14}])

;; (A) Bare-keyword wrap with outer props — FAILS at schema construction
(m/schema [:probe/id {:seon.db/identity true}])
;; => Execution error: :malli.core/invalid-schema
;; Malli does not accept [<reg-keyword> {props}] as a wrapping form.

;; (B) Bare keyword reference — WORKS but no props slot
(m/form (m/schema :probe/id))        ; => :probe/id
(m/properties (m/schema :probe/id))  ; => nil   (props live on the *resolved* schema, not the reference)
(m/validate :probe/id "12345678901234") ; => true
(m/validate :probe/id "short")          ; => false

;; (C) :ref wrap — WORKS, outer props preserved
(m/properties (m/schema [:ref {:seon.db/identity true} :probe/id]))
;; => {:seon.db/identity true}
(m/validate    [:ref {:seon.db/identity true} :probe/id] "12345678901234") ; => true
(m/validate    [:ref {:seon.db/identity true} :probe/id] "short")          ; => false

;; (D) :schema wrap — WORKS, outer props preserved (same behavior as :ref for our use)
(m/properties (m/schema [:schema {:seon.db/identity true} :probe/id]))
;; => {:seon.db/identity true}

;; (E) :and wrap — WORKS, outer props preserved, validation strict
(m/properties (m/schema [:and {:seon.db/identity true} :probe/id]))
;; => {:seon.db/identity true}
(m/validate    [:and {:seon.db/identity true} :probe/id] "12345678901234") ; => true
(m/validate    [:and {:seon.db/identity true} :probe/id] "short")          ; => false
```

Conclusion: for attaching extra properties to a referenced schema, the supported forms are `:ref`, `:schema`, or `:and`. Bare-keyword wrapping does not exist as syntax.

## 2. Current bridge behavior (verified)

Read `src/seon/db.cljs:803-1015`. Relevant functions:

- `form-properties` (line 819-830): reads the first map-typed child from the raw schema form. **Does not** recurse through registry indirections — it sees only the outer form's props. This is correct for our use: `:seon.db/identity` lives on the outer wrap, not the canonical `:seon.db/id`.
- `resolve-malli-form` (line 845-877): follows keyword indirections through the registry until it reaches a non-keyword form OR a built-in `IntoSchema`. Special-cases `:seon.db/ref` (maps directly to `:db.type/ref` without following its `[:or ...]` registration).
- `form->datahike-value-type` (line 900-947): switches on the head of the resolved form. Has explicit handlers for `:enum`, `:and` (recurses on first child — line 924), and `:or` (line 929). For `:ref` and `:schema` heads there is **no handler** — those would throw `:seon.db/unbridgeable-malli-form`.
- `malli->datahike-attr` (line 968-1005): extracts props from `raw-form` via `form-properties`, then `(:seon.db/identity props)` adds `:db/unique :db.unique/identity`; `(:seon.db/component props)` adds `:db/isComponent true`.

### REPL probe — bridge behavior under each pattern

```clojure
(schema/register! :probe/id [:string {:min 14 :max 14}])
(schema/register! :probe.agent.bare/id   :probe/id)
(schema/register! :probe.agent.ref/id    [:ref    {:seon.db/identity true} :probe/id])
(schema/register! :probe.agent.schema/id [:schema {:seon.db/identity true} :probe/id])
(schema/register! :probe.agent.and/id    [:and    {:seon.db/identity true} :probe/id])

(db/malli->datahike-attr :probe.agent.bare/id)
;; => {:db/ident :probe.agent.bare/id, :db/valueType :db.type/string, :db/cardinality :db.cardinality/one}
;;    (correct type, BUT no identity — no place to put it)

(db/malli->datahike-attr :probe.agent.ref/id)
;; => FAIL: "Cannot map Malli type to datahike type: [:ref {:seon.db/identity true} :probe/id]"

(db/malli->datahike-attr :probe.agent.schema/id)
;; => FAIL: "Cannot map Malli type to datahike type: [:schema {:seon.db/identity true} :probe/id]"

(db/malli->datahike-attr :probe.agent.and/id)
;; => {:db/ident       :probe.agent.and/id
;;     :db/valueType   :db.type/string
;;     :db/cardinality :db.cardinality/one
;;     :db/unique      :db.unique/identity}     ;; <-- IDENTITY PROPERLY DERIVED
```

End-to-end with `seon.db/malli->datahike-schema` + real datahike `:memory` store: schema vector installs, upsert via `:probe.thing/id` works correctly (two transacts with the same id mutate one entity, not create two).

## 3. Design options

### (a) Bare keyword reference — `:seon.db/id`

```clojure
;; canonical:
(schema/register! :seon.db/id [:string {:min 14 :max 14}])

;; non-identity scalar (tx-meta):
(schema/register! ::agent-id :seon.db/id)            ;; works today

;; identity attr — DOES NOT WORK
(schema/register! :seon.agent/id :seon.db/id)        ;; type ok, but no identity flag
```

- **Pro:** zero ceremony for the tx-meta cases.
- **Con:** no way to attach `:seon.db/identity`. Would force a parallel mechanism (e.g. an out-of-band identity-attr registry) which fragments the source of truth.

### (b) `:and`-wrap — `[:and {:seon.db/identity true} :seon.db/id]`

```clojure
(schema/register! :seon.db/id    [:string {:min 14 :max 14}])
(schema/register! ::agent-id     :seon.db/id)
(schema/register! :seon.agent/id [:and {:seon.db/identity true} :seon.db/id])
```

- **Pro:** works today (bridge already handles `:and`); validation is strict; identity prop flows through; pattern is uniform with the existing `:seon.db/ref` reference style for non-identity cases.
- **Con:** the `:and` head is mildly indirect ("why is an id schema an `:and`?"). Mitigated by docstring or a tiny helper.

### (c) `[:and base [:string {props}]]` — wrap the canonical inside a constraint stub

```clojure
(schema/register! :seon.agent/id [:and :seon.db/id [:string {:seon.db/identity true}]])
```

- **Pro:** no bridge change needed (bridge takes first child for type).
- **Con:** heavier syntax, restates the type, the `[:string ...]` is a constraint placeholder that obscures intent. Worse than (b).

### (d) Bridge enhancement — support `:ref` / `:schema` heads

Add two clauses to `form->datahike-value-type`:

```clojure
(#{:ref :schema} head)
(form->datahike-value-type (resolve-malli-form (first (form-children resolved-form))))
```

This would enable `[:ref {:seon.db/identity true} :seon.db/id]` and `[:schema {:seon.db/identity true} :seon.db/id]` as additional patterns. Both already work for `form-properties` (outer-prop reading) and `m/validate` (correct strictness).

- **Pro:** `:ref` reads more naturally than `:and` for "this is the same shape as".
- **Con:** opens up another way to do the same thing. Adds branch surface for a marginal readability gain. `:ref` in Malli is semantically "an indirect/lazy reference for recursion" — using it as a property-attachment vehicle is slightly off-label.

## 4. Recommendation — option (b), `:and`-wrap

Register one canonical `:seon.db/id` in `seon.schema.cljc` alongside `:seon.db/ref`. Use bare-keyword reference for non-identity scalars; use `[:and {:seon.db/identity true} :seon.db/id]` for identity attrs. Rationale:

1. **Works today, zero bridge change.** The existing `:and` handler at `db.cljs:924` already recurses for type derivation; `form-properties` already reads outer props for `:seon.db/identity`. Live-probed.
2. **One source of truth for the id shape.** Change `[:string {:min 14 :max 14}]` once in `seon.schema.cljc` and all 9 sites update.
3. **Uniform with existing `:seon.db/ref` precedent.** The codebase already declares canonical scalars in `seon.schema.cljc` (see `:seon.db/ref` at line 92-97) and references them bare from attr registrations. We are extending that pattern to identity attrs, not inventing a new one.
4. **No bridge surface expansion.** Adding `:ref`/`:schema` clauses would create three ways to attach props (`:and`, `:ref`, `:schema`) where one suffices. Friction = signal; one pattern wins.
5. **Strict validation preserved.** Probed: `(m/validate [:and {...} :seon.db/id] "short")` returns false. The `:and` does not relax constraints.

### Minor wrinkle and mitigation

The `:and` head reads as "intersection of constraints," not "alias with property." A one-line helper in `seon.schema.cljc` would document intent without adding a bridge concept:

```clojure
(defn identity-of
  "Identity-attr declaration that references a canonical shape schema.
   Expands to [:and {:seon.db/identity true} shape-key]."
  [shape-key]
  [:and {:seon.db/identity true} shape-key])
```

Then identity attr sites become:

```clojure
(schema/register! :seon.agent/id (schema/identity-of :seon.db/id))
```

The helper is optional; if Sean prefers the literal `:and` form everywhere for transparency, omit the helper and write the vector directly.

## 5. Concrete patch sketch

### Step 1 — Register `:seon.db/id` in `src/seon/schema.cljc`

Insert after the `:seon.db/ref` registration block (line 97) and before the "Registration API" header (line 100):

```clojure
;; Register :seon.db/id — the canonical seon entity-id shape.
;; All entity identity attrs (`:seon.agent/id`, `:seon.session/id`, etc.)
;; and tx-meta scalars (`::agent-id`, `::session-id`, etc.) reference this
;; instead of re-declaring `[:string {:min 14 :max 14}]`. To change the
;; id length, change this one schema.
;;
;; For identity attrs (those that need `:db/unique :db.unique/identity`),
;; wrap with :and to attach the marker:
;;   [:and {:seon.db/identity true} :seon.db/id]
;; The bridge in seon.db recurses through :and for type derivation while
;; reading outer-form properties for `:seon.db/identity`.
(defonce ^:private _id-type
  (swap! *schemas assoc :seon.db/id [:string {:min 14 :max 14}]))
```

### Step 2 — Optional helper in `src/seon/schema.cljc`

After `register-all!` (line 142), add:

```clojure
(defn identity-of
  "Convenience for declaring an identity attribute whose shape is a
   reference to another registered schema.

     (register! :seon.agent/id (identity-of :seon.db/id))
       ;; equivalent to
     (register! :seon.agent/id [:and {:seon.db/identity true} :seon.db/id])"
  [shape-key]
  [:and {:seon.db/identity true} shape-key])
```

### Step 3 — Update `src/seon/db.cljs` lines 377-385

Before:

```clojure
;; Identity-attr length constraints for the tx-meta scalars. Bumped
;; 12 → 14 alongside the id-shape change (locked 2026-05-23).
(schema/register! ::agent-id        [:string {:min 14 :max 14}])
(schema/register! ::session-id      [:string {:min 14 :max 14}])
(schema/register! ::turn-id         [:string {:min 14 :max 14}])
(schema/register! ::eval-id         [:string {:min 14 :max 14}])
```

After:

```clojure
;; tx-meta scalars reference the canonical :seon.db/id shape. To change
;; the id length, edit :seon.db/id in seon.schema.
(schema/register! ::agent-id   :seon.db/id)
(schema/register! ::session-id :seon.db/id)
(schema/register! ::turn-id    :seon.db/id)
(schema/register! ::eval-id    :seon.db/id)
```

### Step 4 — Update `src/seon/agent.cljs` lines 108, 133, 145, 192, 199

Before (5 sites):

```clojure
(schema/register! :seon.agent/id   [:string {:min 14 :max 14 :seon.db/identity true}])
(schema/register! :seon.message/id [:string {:min 14 :max 14 :seon.db/identity true}])
(schema/register! :seon.eval/id    [:string {:min 14 :max 14 :seon.db/identity true}])
(schema/register! :seon.session/id [:string {:min 14 :max 14 :seon.db/identity true}])
(schema/register! :seon.turn/id    [:string {:min 14 :max 14 :seon.db/identity true}])
```

After:

```clojure
(schema/register! :seon.agent/id   [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.message/id [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.eval/id    [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.session/id [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.turn/id    [:and {:seon.db/identity true} :seon.db/id])
```

(or use the `identity-of` helper if added in Step 2.)

## 6. Migration plan

### Order of operations

1. **Land the canonical first.** Step 1 (registering `:seon.db/id` in `seon.schema.cljc`) must be in place before any reference to it. Because `seon.schema` is loaded by virtually every namespace, this means landing Step 1 as its own commit — any concurrent agent working on `seon.db.cljs` or `seon.agent.cljs` will pick it up on hot-reload.
2. **Steps 3 and 4 can land together or separately.** Both depend only on `:seon.db/id` being registered. They are independent of each other.
3. **No data migration.** The on-disk datahike schema entries are derived from these Malli registrations at install-schema time. Since the derived `{:db/ident ..., :db/valueType :db.type/string, :db/cardinality :db.cardinality/one, :db/unique :db.unique/identity}` map is byte-identical before and after, install-schema will recognize the existing attrs as unchanged. Running stores keep working.

### Load-time gotchas

- `seon.schema.cljc` uses `defonce` for its initial swaps — adding a new `defonce ^:private _id-type` is safe across reloads.
- The bridge guard `seon.db.datahike.schema/malli-map->datahike-schema` (mentioned in CLAUDE.md) requires referenced schemas to be registered before the entity schema that uses them. Since `:seon.db/id` is registered in `seon.schema.cljc`'s top-level load (alongside `:seon.db/ref`), it is guaranteed to be present before any `:seon.agent/id` registration in `seon.agent.cljs` runs.
- Hot-reload of `seon.db.cljs` or `seon.agent.cljs` after Step 1 is non-destructive: re-running the modified `register!` calls overwrites the previous entries with the new vector form. Subsequent `install-schema!` calls produce the same datahike map.

### Verification after migration

In the live pod:

```clojure
;; Each of these should print the same map shape, with :db/unique present
;; for identity attrs and absent for tx-meta scalars.
(db/malli->datahike-attr :seon.agent/id)
;; => {... :db/valueType :db.type/string ... :db/unique :db.unique/identity}
(db/malli->datahike-attr :seon.db/agent-id)
;; => {... :db/valueType :db.type/string ...}   ; no :db/unique

;; Validation still strict
(m/validate :seon.agent/id "12345678901234") ; => true
(m/validate :seon.agent/id "short")          ; => false

;; Existing test suites for agent / db should pass without changes.
```

To change the id length later (the original motivation): edit `:seon.db/id` in `seon.schema.cljc`. All 9 sites pick it up on reload.

## 7. Open questions / risks

- **`:seon.db/ref` precedent.** `:seon.db/ref` is special-cased in `resolve-malli-form` (line 867) to map directly to `:db.type/ref` instead of following its `[:or ...]` registration. `:seon.db/id` is **not** special — it resolves naturally through `:string`. No special-case needed.
- **Helper visibility.** If `identity-of` lives in `seon.schema`, it's available to every namespace that already requires `seon.schema`. No surprise dependency.
- **Future shape schemas.** This pattern generalizes: any canonical shape (e.g. a future `:seon.db/short-id`, `:seon.db/long-text`) can register once and be referenced with bare keyword (non-identity) or `:and` wrap (identity). No further bridge work required.
- **Why not `:ref`/`:schema`?** Considered in §3(d). Rejected because (i) `:and` already works, (ii) `:ref` is semantically about lazy/recursive references in Malli, (iii) adding three equivalent patterns expands choice without payoff.
- **Risk: a future Malli upgrade changes `:and` semantics.** Low. `:and` has been stable since Malli 0.1; it's one of the most foundational combinators. The 0.20.0 we're on (per Memory) implements it exactly as observed.
- **Lint / kondo.** No change to public API surface, no new macros. `clj-kondo` should remain silent.
