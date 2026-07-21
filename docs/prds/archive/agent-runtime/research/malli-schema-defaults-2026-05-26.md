---
type: research
status: draft
tags: [research, schema]
---

# Malli schema properties + defaults — canonical patterns for Seon

## TL;DR

Malli already has the primitives Seon needs. Three load-bearing facts:

1. **Schema properties are arbitrary maps.** The map literal in position 2 of a vector schema (`[:string {:min 1 :seon.render/ai 'foo}]`) becomes the schema's `m/properties`. Malli reserves no keys — namespaced keys like `:seon.render/ai` are fine. Read via `(m/properties (m/schema :seon.eval))`.
2. **`mt/default-value-transformer` fills `:default` literal values OR `:default/fn`-computed values** for keys that are MISSING from a map at decode time.
3. **The right pattern for Seon's "default render fn per kind, override per row" is to put the symbol on the entity-schema's properties, and look it up at render time with `(or (:seon.render/ai entity) (-> kind m/schema m/properties :seon.render/ai))`.** No decode-time fill needed — pure lookup at render time is simpler, stores less data, and behaves identically.

What Seon should change: stop stamping `:seon.render/ai 'sym` and `:seon.render/html 'sym` on every `:seon.eval` / `:seon.fn` / `:seon.message` row (see `src/seon/eval.cljs:733-734`, `src/seon/agent.cljs:453,627`, `src/seon/client.cljs:685-700`). Instead declare them ONCE as schema properties on the entity-schema for that kind, fall through to the schema-level default in the renderer.

---

## A. Schema properties — Malli's arbitrary-metadata escape hatch

Malli schemas are `[type properties? & children]`. The optional second-position map is the schema's properties — `m/-into-schema` stores it verbatim and `m/-properties` returns it untouched. The whole machinery is in `reference-code/malli/src/malli/core.cljc`:

- Line 39: protocol method `(-properties [this] "returns original schema properties")`
- Line 421: `(defn -set-properties [schema properties] ...)` — rebuilds the schema with new props
- Line 2582: public `(defn properties [?schema] ...)` — the API consumers call

Malli reserves no property keys for its own use beyond a documented set (`:registry`, `:default`, `:gen/*`, `:json-schema/*`, `:error/*`, etc.). Anything else is opaque payload. The whole codebase reads custom props the same way:

```clojure
;; From tips.md "Trimming strings" example
(let [{:string/keys [trim]} (m/properties schema)]
  (when trim #(cond-> % (string? %) str/trim)))

```

The `:string/trim` key is application-defined; Malli neither knows nor cares. Same shape works for `:seon.render/ai`.

**Reading on Seon's registry:** schemas registered via `seon.schema/register!` land in a mutable registry composited with `(m/default-schemas)` (`src/seon/schema.cljc:42-46`). After registration, `(m/schema :seon.eval)` returns the live `Schema` instance and `(m/properties (m/schema :seon.eval))` returns the props map. No special API needed — the public `m/properties` is THE accessor.

### What `register!` accepts today

Seon's `register!` (`src/seon/schema.cljc:119-134`) is `(swap! *schemas assoc k v)`. The `v` is the Malli schema FORM — whatever you'd write inline. So a property-bearing schema is registered exactly as:

```clojure
(schema/register! ::eval
  [:map {:seon.render/ai   'seon.handlers.eval/render-ai
         :seon.render/html 'seon.handlers.eval/render-html}
   [::id ::id]
   [::source :string]
   ...])

```

No changes to `register!` required. The properties travel with the schema.

---

## B. Defaults — `:default`, `:default/fn`, and the transformer that reads them

`mt/default-value-transformer` lives at `reference-code/malli/src/malli/transform.cljc:484-520`. The core lookup function:

```clojure
(let [get-default (fn [schema more-props]
                    (or (some-> schema m/properties :default/fn m/eval)
                        (some-> more-props :default/fn m/eval)
                        (if-some [e (or (some-> schema m/properties (find key))
                                        (some-> more-props (find key)))]
                          (constantly (val e))
                          (some->> schema m/type (get defaults) (#(constantly (% schema)))))))

```

Reading priority is (a) `:default/fn` on the value-schema (b) `:default/fn` on the entry properties (c) the configured `:default` key (default `:default`) on the value-schema (d) the entry's `:default` (e) a type-keyed fallback table passed as `:defaults`.

Two operating modes, both wired in the same transformer:

- **`:default-decoder`** — for a non-map slot, replaces `nil` input with the default. Triggered by `set-default` compiler: `(fn [x] (if (nil? x) (default-fn schema (f)) x))`.
- **`:map` decoder** — for map slots, walks each entry; when the key is ABSENT (not nil — checked with `contains?`), adds it with the computed default.

Crucially: **defaults fill only when the key is missing.** Present-with-value entries are untouched. That's exactly the "override per row" semantic — if an entity transacts `:seon.render/ai 'my.custom/fn`, the transformer (if used) leaves it alone; only entities lacking the key get the schema default.

### Canonical shapes

```clojure
;; Literal default value
[::role {:optional true :default :user} :keyword]

;; Computed default — fn must be eval-able (quoted form or symbol)
[::id {:default/fn '(fn [_] (str "id-" (random-uuid)))} :string]

;; Default that depends on other keys (sibling values) — requires the
;; dependent-default-transformer from tips.md, NOT the stock one
[::cost {:default-fn '(fn [m] (* (:qty m) (:price m)))} number?]

```

`:default/fn` values go through `m/eval` (see line 488), which in JVM is `sci`-or-`clojure.core/eval` and in CLJS is `sci`. The fn body must be a quoted form (sci-evalable) or a symbol that resolves in the eval registry — same constraint Seon already accepts for `:seon.render/ai 'foo` (symbol stored, resolved via `seon.eval/lookup-value`).

### Schema-property default vs entry-property default

Both legal. The transformer checks the value-schema's own properties FIRST (`some-> schema m/properties (find key)`), then the entry properties as fallback. For Seon's case — a default that's per ENTITY-KIND not per-attr — the value-schema (i.e. the `:map` schema for `:seon.eval`) is the right home.

---

## C. Does Seon NEED the transformer?

No. The render-time codepath in `src/seon/render.cljs:198` already reads the symbol straight off the entity:

```clojure
(let [sym (:seon.render/ai entity)
      f   (or (eval/lookup-value sym) default/pretty-ai)
      ...])

```

Adding a one-step fallback to the schema is trivial:

```clojure
(let [sym (or (:seon.render/ai entity)
              (some-> (entity-kind entity) m/schema m/properties :seon.render/ai))
      f   (or (eval/lookup-value sym) default/pretty-ai)
      ...])

```

Where `entity-kind` picks the kind keyword (e.g. presence of `:seon.eval/id` → `:seon.eval`). This is strictly cheaper than `(m/decode entity-schema entity (mt/default-value-transformer))` per render call (transformer compile is cached, but the per-row walk still happens), and stores LESS data — the symbol isn't duplicated across 10,000 eval rows in the DB.

**Recommendation: lookup at render time, no decode-time fill.** Use the transformer only if Seon ever needs the symbol materialized into a map for downstream consumers that can't do the fallback themselves (the inspector's `:aevt :seon.render/ai` walk in `render.cljs:168` is one such consumer — see "Open question" below).

---

## D. Audit — where Seon currently writes per-row what should be schema-level

Concrete sites stamping `:seon.render/ai` / `:seon.render/html` on every row:

| File:line | Entity | Currently stamps |
|-----------|--------|-----------------|
| `src/seon/eval.cljs:733-734` | every `:seon.eval` | `'seon.handlers.eval/render-ai`, `'…/render-html` |
| `src/seon/agent.cljs:453,627` | every `:seon.message` | `'seon.handlers.message/render-ai`, `'…/render-html` |
| `src/seon/client.cljs:685-686` | every `:seon.fn` | `'seon.handlers.fn/render-ai`, `'…/render-html` |
| `src/seon/client.cljs:693-694` | every `:seon.schema` | `'seon.handlers.schema/render-ai`, `'…/render-html` |
| `src/seon/client.cljs:699-700` | every `:seon.ns` | `'seon.handlers.ns/render-ai`, `'…/render-html` |

Every one of these is a CONSTANT — the same symbol for every row of that kind. All five are candidates for schema-level defaults.

### Audit — `:any` and `:maybe` violations vs CLAUDE.md policy

CLAUDE.md bans `:any` and `[:maybe X]` on PERSISTED schemas. Violations found:

- `src/seon/log.cljs:49,136,148,312` — `:seon.log/data :any`. Persisted log payload, genuinely heterogeneous. Either narrow with a closed `:or` union or document a justified exception in the schema's properties (`:seon.exception/reason "heterogeneous-log-payload"`).
- `src/seon/agent_view.cljs:38-39` — `:seon.db/conn :any`, `:seon.db/db :any`. NOT persisted (runtime handle); the `:any` is fine but should be promoted to a registered `:seon.db/conn` simple-schema like `:seon.db/db` already is in `render.cljs:44`.
- `src/seon/render.cljs:75` — `[:seon.render/hiccup :any]` inside `:seon.render/html-response`. A schema for hiccup IS registered three lines above (`:seon.render/hiccup`); this should reference it: `[:map [:seon.render/hiccup :seon.render/hiccup]]`. Same file violates its own work.
- `src/seon/db.clj:278-279,311,328,345,362` — `:malli/schema` on public db fns uses `:any`. These are JVM-side and pre-date the agent-runtime work; flagging for the convergence pass but not in scope here.
- `src/seon/handler.cljs:104` — `[:tuple :keyword [:maybe :any]]` for `:seon.handler/key`. Persisted? Check — if so, replace with `{:optional true}` shape. If runtime-only, leave.
- `src/seon/repl.clj:44` — `[:maybe :string]` for an extracted name. Probably persisted; should be `{:optional true} :string`.

None of these block the render-fn-on-schema change. They're the residue of a not-yet-finished `:any`/`:maybe` sweep (see `MEMORY.md` "Open Work").

---

## E. The `m/properties` resolution path

For Seon, the resolution chain at render time is:

1. `(:seon.render/ai entity)` — wins if present (override case).
2. If absent, look up the entity's KIND. Two options:
   - **Kind keyword stored on the entity** (`:seon.entity/kind :seon.eval`). Cleanest — explicit, queryable. Adds one attr per entity but indexed, cheap.
   - **Infer kind from an identity-attr** (`(:seon.eval/id entity)` → `:seon.eval`). Free at storage cost but requires a lookup table.
3. `(some-> kind m/schema m/properties :seon.render/ai)` — schema-level default.
4. `default/pretty-ai` — final fallback.

`m/schema` accepts a registered keyword and returns the live `Schema`; `m/properties` is `O(1)` (just deref the field). Net cost per render: one keyword lookup + one props read. Negligible.

---

## F. Recommendations

1. **Declare per-kind defaults as schema properties:**

   ```clojure
   (schema/register! :seon.eval
     [:map {:seon.render/ai   'seon.handlers.eval/render-ai
            :seon.render/html 'seon.handlers.eval/render-html}
      [:seon.eval/id ...]
      ...])

   ```

   Stop stamping these on each row (the five sites in section D).

2. **Renderer reads with fallback:** modify `src/seon/render.cljs:198` and the inspector walker at `:168` to `(or row-attr (schema-default))`. The inspector's `:aevt :seon.render/ai` index walk is the one wrinkle (see "Open question").

3. **DO NOT use `mt/default-value-transformer` to fill these at decode time.** Lookup-on-render is strictly cheaper and stores less.

4. **DO use `:default/fn`** for the other Seon use case mentioned in MEMORY.md: namespace bootstrap. That use case (rebuilding non-serializable values from `::resume? true`) is exactly what `:default/fn` is for, and the dependent-default-transformer recipe from `reference-code/malli/docs/tips.md:230` is the right pattern.

5. **Reference, don't inline:** fix `src/seon/render.cljs:75` to reference `:seon.render/hiccup` instead of `:any`. Apply the same audit across every `:map [:foo :any]` site.

6. **Promote `:seon.db/conn` to a registered simple-schema** so `:any` disappears from `agent_view.cljs:38`.

---

## Open question for Sean

The inspector walks `(d/datoms db :aevt :seon.render/ai)` to find renderable entities (`render.cljs:168`). If we stop stamping `:seon.render/ai` on each row, that index walk goes away — there's no `:aevt` slice for an attr nobody writes.

Two options:

- **(a) Stamp `:seon.entity/kind` on every entity and walk `(d/datoms db :aevt :seon.entity/kind)`** to find renderable rows. Filter by "kind has schema-level `:seon.render/ai` prop". Generalizes — `:seon.entity/kind` is useful for many things (audit, dispatch, debug).
- **(b) Keep stamping `:seon.render/ai`** but treat it purely as an index marker — same symbol every time, but the renderer still resolves through the schema for consistency. Wasteful but minimal change.

I lean (a) — kind is a generally useful entity attr and it eliminates duplication. But it depends on whether Sean wants every entity to declare its kind explicitly, or whether identity-attr inference (`:seon.eval/id` present → kind is `:seon.eval`) is good enough. The latter is zero-extra-data but requires every kind to have a unique identity attr namespace.
