# Signal Encoding: JS <-> Clojure Keyword Boundary

## 1. Problem Analysis

### Current Approach

The transform layer (`seon.web.reactive.transform`) strips namespaces from keywords when creating signal names:

```clojure
;; In render hiccup, agent writes:
[:input {:field ::gs/exercise}]  ; :seon.getting-started/exercise

;; Transform produces:
[:input {:name "exercise" :data-bind:exercise true}]
```

The namespace is lost. On the way back, `fn-schema-key-map` in `routes.clj` inspects the target function's Malli schema to reconstruct qualified keywords:

```clojure
;; Signal arrives as: {"exercise" "Pull-up"}
;; fn-schema-key-map reads the fn's [:=> [:cat [:map ...]]] schema
;; Finds :seon.getting-started/exercise in the map entries
;; Maps "exercise" -> :seon.getting-started/exercise
```

### Why It's Fragile

1. **Name collisions.** Two different qualified keys with the same `name` part (e.g. `:seon.ctx/user-input` and `:seon.getting-started/user-input`) become the same signal name `"user-input"`. The schema lookup picks one arbitrarily.

2. **Schema required for reconstruction.** Every function receiving signals MUST have a Malli schema with explicit `:map` entries. Functions with open maps, `[:map-of :keyword :any]`, or no schema get wrong keys.

3. **camelCase round-trip is lossy.** Datastar converts `data-bind:user-input` to signal `userInput` (camelCase). The server runs `camel->kebab` to get back `user-input`. But `data-bind:userInput` also produces `userInput`. The reverse mapping is ambiguous for multi-hyphen names.

4. **Two separate namespace-reconstruction paths.** `fn-schema-key-map` handles function calls. `render/namespace-web-params` handles page rendering. They use different logic. Neither handles cross-namespace keys.

## 2. What Datastar Actually Sends

**POST body format:** When `@post('/some/url')` fires, Datastar sends ALL non-local signals (those not prefixed with `_`) as a JSON body with `Content-Type: application/json`.

```json
{"exercise": "Pull-up", "sets": 3, "reps": 10, "weight": 20}
```

**Signal naming rules:**
- `data-signals:foo-bar="1"` creates signal `fooBar` (hyphen -> camelCase)
- `data-signals:form.baz="2"` creates nested signal `form.baz`
- `data-signals="{fooBar: 1}"` creates signal `fooBar` directly
- `data-bind:foo-bar` binds to signal `fooBar`

**Key insight: Datastar supports dot-notation for nesting.** `data-signals:form.baz="2"` creates `{form: {baz: 2}}`. This is the escape hatch.

## 3. Proposed Design: Dot-Nested Signals

Use Datastar's native dot-notation nesting to preserve namespace identity.

### Encoding: Clojure keyword -> signal name

```
:seon.getting-started/exercise  ->  signal path: seon.getting-started.exercise
:seon.ctx/user-input            ->  signal path: seon.ctx.userInput
```

The rule: replace `/` with `.`, keep namespace dots as-is, apply Datastar's kebab->camel on the name part.

### HTML output

```html
<!-- Current (broken) -->
<input data-bind:exercise name="exercise">
<div data-signals='{"exercise": ""}'>

<!-- Proposed -->
<input data-bind:seon.getting-started.exercise name="seon.getting-started/exercise">
<div data-signals='{"seon": {"getting-started": {"exercise": ""}}}'>
```

Or using key syntax shorthand:
```html
<div data-signals:seon.getting-started.exercise="''"></div>
<input data-bind:seon.getting-started.exercise />
```

### What Datastar sends to server

```json
{
  "seon": {
    "getting-started": {
      "exercise": "Pull-up",
      "sets": 3,
      "reps": 10
    },
    "ctx": {
      "userInput": "hello"
    }
  }
}
```

### Decoding: JSON -> Clojure keywords

Walk the nested JSON object, reconstructing qualified keywords:

```clojure
(defn decode-signals [json-body]
  ;; Flatten nested structure back to qualified keywords
  ;; {"seon" {"getting-started" {"exercise" "Pull-up"}}}
  ;; -> {:seon.getting-started/exercise "Pull-up"}
  ...)
```

The walk reconstructs the path: keys at depth N are namespace segments, the leaf key is the keyword name. The `/` separator goes before the last segment.

### Concrete algorithm

```clojure
(defn flatten-signals
  "Flatten nested signal map to qualified keywords.
   {\"seon\" {\"getting-started\" {\"exercise\" \"Pull-up\"}}}
   -> {:seon.getting-started/exercise \"Pull-up\"}"
  ([m] (flatten-signals [] m))
  ([path m]
   (if (map? m)
     (into {}
       (mapcat (fn [[k v]]
                 (flatten-signals (conj path k) v)))
       m)
     ;; Leaf: path segments are namespace, last is name
     (let [ns-parts (butlast path)
           nm (last path)]
       [[(keyword (str/join "." ns-parts) (camel->kebab nm))
         m]]))))
```

### Encoding for HTML (transform.clj)

```clojure
(defn keyword->signal-path
  "Convert qualified keyword to Datastar signal path.
   :seon.getting-started/exercise -> \"seon.getting-started.exercise\""
  [kw]
  (let [ns (namespace kw)
        nm (name kw)]
    (if ns
      (str ns "." nm)
      nm)))
```

## 4. Schema Validation

With proper qualified keywords recovered, validation is straightforward:

```clojure
(defn validate-and-call [action-fn signals]
  (let [schema (:malli/schema (meta action-fn))]
    (when schema
      ;; Extract the input map schema from [:=> [:cat [:map ...]] ...]
      (let [input-schema (-> schema second second)
            valid? (m/validate input-schema signals)]
        (when-not valid?
          (throw (ex-info "Invalid input"
                   {:errors (m/humanize (m/explain input-schema signals))})))))
    (action-fn signals)))
```

No more `fn-schema-key-map`. The signals arrive with correct keys. Validation is a direct schema check.

## 5. Alternative Considered: Transit

**Transit** (cognitect/transit-clj) encodes Clojure types as tagged JSON. It would give perfect round-tripping of keywords, symbols, sets, etc.

**Why not:**
- Not a current dependency (would need transit-clj + transit-js)
- Datastar's signal system is JS-native; Transit would only work for POST bodies, not `data-bind` or `data-signals` attributes
- Datastar expects to read/write signals as plain JS values. Transit-encoded signal values would break `data-text="$signal"` expressions
- Overkill: we only need keyword namespacing, not full Clojure type preservation

Transit could be useful later for a raw API layer, but for the Datastar reactive UI, dot-nesting is the right fit because it works WITH Datastar rather than around it.

## 6. Alternative Considered: Double-Underscore Encoding

```
:seon.getting-started/exercise -> seon__getting_started___exercise
```

**Why not:**
- Ugly, error-prone (is that 2 or 3 underscores?)
- Doesn't leverage Datastar's native nesting
- Signal names become long and unreadable in HTML/devtools
- Still needs a custom encode/decode layer, but gets nothing from Datastar

## 7. Migration Path

### Phase 1: Add encode/decode layer (no breaking changes)

1. Add `keyword->signal-path` and `flatten-signals` to a new `seon.web.reactive.encoding` namespace
2. Update `transform.clj` to use `keyword->signal-path` for `:field` and `data-signals`
3. Update `function-call-handler` to use `flatten-signals` instead of `fn-schema-key-map`
4. Both old (flat) and new (nested) signal formats should work during migration

### Phase 2: Add schema validation

1. After signal decoding, validate the full input map against the function's Malli schema
2. Return 422 with humanized errors on validation failure
3. Remove `fn-schema-key-map` entirely

### Phase 3: Clean up

1. Remove `camel->kebab` from `extract-signals` (dot-path handles namespacing)
2. Remove `namespace-web-params` re-namespacing logic from render
3. Update all renderers to use fully qualified `:field` keys (already the case)

### Files to change

- `src/seon/web/reactive/encoding.clj` — NEW: encode/decode functions
- `src/seon/web/reactive/transform.clj` — use `keyword->signal-path`
- `src/seon/web/reactive/actions.clj` — use `flatten-signals`
- `src/seon/ns/routes.clj` — remove `fn-schema-key-map`, add validation
- `src/seon/render.clj` — remove `namespace-web-params` hack

## 8. Edge Cases

**Unqualified keywords:** `:foo` has no namespace. Signal path is just `"foo"`. Decoded as `:foo`. Works fine.

**Cross-namespace signals:** A form might have fields from different namespaces (e.g. `:seon.ctx/user-input` and `:seon.getting-started/exercise`). With dot-nesting, each gets its own path. The flat JSON nests correctly:
```json
{"seon": {"ctx": {"userInput": ""}, "getting-started": {"exercise": ""}}}
```

**Hyphen in namespace:** `getting-started` is a valid namespace segment. Datastar's kebab->camel conversion applies to signal keys but dot-notation segments are preserved as-is. Need to verify: does `data-signals:seon.getting-started.exercise` preserve the hyphen in `getting-started` or camelCase it? If Datastar camelCases each segment, we need the JSON object form instead of key syntax.

**This needs REPL testing before implementation.** The critical question: does `data-signals:a.b-c.d` produce `{a: {bC: {d: ...}}}` or `{a: {"b-c": {d: ...}}}`? If the former, we must use the JSON value form `data-signals='{"seon": {"getting-started": {"exercise": ""}}}'` which preserves exact keys.

## 9. Trade-offs

| Aspect | Current (strip namespace) | Proposed (dot-nesting) |
|--------|--------------------------|----------------------|
| Correctness | Lossy, collisions possible | Lossless round-trip |
| Schema dependency | Required for reconstruction | Optional (for validation only) |
| HTML verbosity | Short signal names | Longer paths in attributes |
| Datastar compatibility | Works but fragile | Uses native nesting feature |
| Debugging | Hard (which namespace?) | Clear (full path visible) |
| Migration risk | N/A | Low (additive, backward compatible) |

The main cost is longer HTML attributes. `data-bind:exercise` becomes `data-bind:seon.getting-started.exercise`. This is acceptable — correctness matters more than brevity, and the full path aids debugging.
