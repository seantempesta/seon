---
type: research
status: draft
tags: [research, database, schema]
---

# Malli defaults — native support and the renderer use case

**Malli version in seon:** `metosin/malli {:mvn/version "0.20.0"}` (`deps.edn:11,135`).

## §A. What Malli provides natively

Malli ships `malli.transform/default-value-transformer`. The canonical
definition lives at `reference-code/malli/src/malli/transform.cljc:484-520`:

```clojure
(defn default-value-transformer
  ([] (default-value-transformer nil))
  ([{:keys [key default-fn defaults ::add-optional-keys]
     :or {key :default, default-fn (fn [_ x] x)}}]
   (let [get-default (fn [schema more-props]
                       (or (some-> schema m/properties :default/fn m/eval)
                           (some-> more-props :default/fn m/eval)
                           (if-some [e (or (some-> schema m/properties (find key))
                                           (some-> more-props (find key)))]
                             (constantly (val e))
                             (some->> schema m/type (get defaults) ...))))
         ...
         add-defaults {:compile (fn [schema _]
                                  (let [defaults (into {}
                                          (keep (fn [[k {:keys [optional] :as p} v]]
                                            (when (or (not optional) add-optional-keys)
                                              (when-some [f (or (get-default v p) ...)]
                                                [k (fn [] (default-fn schema (f)))]))))
                                          (m/children schema))]
                                    (when (seq defaults)
                                      (fn [x]
                                        (if (map? x)
                                          (reduce-kv
                                           (fn [acc k f]
                                             (if-not (contains? x k)
                                               (assoc acc k (f))  ;; <-- (f) is 0-arity
                                               acc))
                                           x defaults)
                                          x)))))}]
     (transformer {:default-decoder set-default
                   :default-encoder set-default}
                  {:decoders {:map add-defaults}
                   :encoders {:map add-defaults}}))))
```

Supported today (CHANGELOG line 52, PR #1209): **`:default/fn` works both as
a map-entry property and as a schema property**. Two flavors:

- `:default` — literal value. Inserted as-is when the key is absent.
- `:default/fn` — symbol/form passed through `m/eval`; called with **zero
  arguments**. Its return value is the default.

Both are per-map-entry **or** per-schema. By default, `:optional true`
entries are skipped (don't default-fill); pass
`::mt/add-optional-keys true` to include them (CHANGELOG line 398).

The transformer-level `default-fn` ctor option (line 486, default
`(fn [_ x] x)`) is a 2-arity post-processor receiving `(schema, value)` —
not the entity. It can rewrite the produced default but cannot see the
surrounding map.

## §B. REPL-verified probe results

```clojure
;; literal :default
(m/decode [:map [:foo {:default 42} :int]] {}      (mt/default-value-transformer))
;; => {:foo 42}
(m/decode [:map [:foo {:default 42} :int]] {:foo 7} (mt/default-value-transformer))
;; => {:foo 7}  ;; existing value preserved

;; :default/fn — function gets evaluated, called 0-arity
(m/decode [:map [:foo {:default/fn (fn [] 42)} :any]] {} (mt/default-value-transformer))
;; => {:foo 42}

;; CRITICAL: does :default/fn see the surrounding entity?
(m/decode [:map [:foo {:default/fn (fn [& args] {:got args})} :any]]
          {:other "data"} (mt/default-value-transformer))
;; => {:other "data", :foo {:got nil}}    ;; <-- args is nil. zero-arity call.

;; ctor-level :default-fn — 2-arity (schema, value); no entity either
(m/decode [:map [:foo {:default/fn (fn [] :inner)} :any]]
          {:other "X"}
          (mt/default-value-transformer
           {:default-fn (fn [schema v] {:saw-schema (m/form schema) :inner v})}))
;; => {:other "X", :foo {:saw-schema [:map [:foo #:default{...} :any]] :inner :inner}}
;; The schema seen is the OUTER :map schema, not the entity instance.
```

**Conclusion**: Malli supports literal and 0-arity computed defaults at
per-map-entry granularity. **It does NOT pass the surrounding entity to
the computed default.** Sean's spec ("taking the surrounding data,
producing the value") is *not* satisfied by the built-in transformer.

## §C. Mapping to our renderer use case

Three options ranked by build cost:

1. **Pure-native, but the render-fn must close over nothing.** If the
   render produces a *static* default (constant string, fixed hiccup),
   `:default/fn` works as-is. Useless for `(str "User-" from ": " text)`.

2. **Thin custom transformer (~20 lines)** — copy the shape of
   `add-defaults` (transform.cljc:497-515) but invoke `f` with the
   in-flight entity `x` instead of zero-arity:

   ```clojure
   [k (fn [x] (f x))]   ;; instead of (fn [] (f))
   ```

   Declare via a seon-side property key (e.g. `:seon.render/default-fn`)
   so we don't collide with Malli's `:default/fn` semantics. Boundary
   call: `(m/decode schema entity (seon.schema/compute-defaults-transformer))`.
   Introspection: `(m/properties (m/-get schema k))` per entry — exactly
   the pattern Malli's own transformer uses.

3. **Schema walker (~50 lines)** — `m/walk` the schema, collect entries
   with `:seon.render/default-fn`, apply at the boundary. More flexible
   (multiple kinds of "affordance" beyond defaults) but heavier.

**Recommendation**: option 2. The custom transformer composes with
Malli's transformer pipeline, reuses `m/properties` / `m/children`, and
is the minimum delta from what's already idiomatic.

## §D. Implication for `schema/register!` extension

The renderer redesign proposal §C wanted `:seon.render/default` as a
kwarg to `schema/register!`. With map-entry-level properties, that kwarg
goes away. A user-message schema is just:

```clojure
(schema/register! :seon.user-message/message
  [:map
   [:seon.user-message/text :string]
   [:seon.user-message/from :string]
   [:seon.render/ai   {:seon.render/default-fn 'my.ns/render-user-message-ai}   :string]
   [:seon.render/html {:seon.render/default-fn 'my.ns/render-user-message-html} :seon.render/hiccup]])
```

`schema/register!` stays single-arity. At the boundary:
`(m/decode schema entity (seon.schema/compute-defaults-transformer))`.
The transformer reads `:seon.render/default-fn`, resolves the symbol via
`requiring-resolve` (or `m/eval`), and invokes it with the entity.
**Confirmed by REPL evidence**: Malli's own `add-defaults` already
iterates `(m/children schema)` and reads per-entry properties — we're
mirroring that exact pattern with a different property key and an
entity-aware call site.

## §E. The `:seon.render/hiccup` schema

No `:seon.render/hiccup` Malli schema exists in `seon/src/`. There is a
hiccup-producing `seon.render` function and ctx `::render-fn` slots
described as "Function (ctx-value) -> hiccup" (`seon/src/seon/ctx.clj:95`,
`seon/src/seon/render.clj:611`), but the *shape* is untyped.

A plausible recursive Malli definition (matching Datastar/Hiccup convention):

```clojure
(schema/register! :seon.render/hiccup
  [:schema {:registry {::node [:or
                                :string
                                :nil
                                [:cat :keyword [:? :map] [:* [:ref ::node]]]]}}
   [:ref ::node]])
```

Refining this against the actual `seon.web.reactive.transform/transform-hiccup`
constraints is a follow-up.

## §F. Open questions

- Property-key collision: should we name ours `:seon.render/default-fn` to
  avoid clashing with `:default/fn`, or hijack `:default/fn` and accept
  Malli's semantic drift later? (Recommend the seon-namespaced key.)
- Should the symbol resolver be `requiring-resolve` (CLJ) or `m/eval`
  (sci-friendly)? Production paths bypass sci; `requiring-resolve` is
  cheaper and clearer for renderer fns that live in real namespaces.
- Hiccup schema strictness — do we want a "valid Datastar-safe hiccup"
  predicate, or just a structural recogniser?
