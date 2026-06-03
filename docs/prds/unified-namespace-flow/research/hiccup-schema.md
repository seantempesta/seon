---
type: research
status: active
tags: [research, schema, prd]
---

# Recursive Hiccup Schema in Malli

## Question

Can we replace `(schema/register! ::html :any)` in `seon.render` with a proper recursive Malli schema that validates hiccup at runtime?

## Answer: Yes

Malli natively supports recursive schemas via `:schema` with a local `:registry` and `:ref` for recursive positions. The Malli README includes a hiccup schema example. We adapted it for Seon's needs (sequence children from `map`/`for`, CSS selector keywords like `:main#morph`).

## Schema Definition

```clojure
(schema/register! ::html
  [:schema {:registry
            {"hiccup"
             [:or
              ;; Primitives — valid hiccup children
              :string :int :double :boolean :nil
              ;; Hiccup node — vector starting with keyword tag
              [:and vector?
               [:cat keyword?
                [:? [:map-of :keyword :any]]
                [:* [:or
                     [:schema [:ref "hiccup"]]
                     [:and seq? [:sequential [:schema [:ref "hiccup"]]]]]]]]
              ;; Fragment — sequence of hiccup (from map/for at top level)
              [:and seq? [:sequential [:schema [:ref "hiccup"]]]]]}}
   "hiccup"])

```

## How It Works

Malli's recursive schema mechanism:

1. **`:schema`** wrapper introduces a local registry scoped to this schema definition.
2. **`:ref`** creates a lazy reference that prevents infinite expansion (without `:ref`, Malli eagerly expands and stack overflows).
3. **`"hiccup"`** is a string key in the local registry (not a namespaced keyword) to avoid collisions with the global registry.
4. The outer `[:schema ... "hiccup"]` promotes `"hiccup"` as the entry point.

The schema handles three cases:

- **Primitives**: strings, numbers, booleans, nil (valid hiccup children).
- **Nodes**: vectors like `[:div {:class "foo"} [:span "hi"]]` -- keyword tag, optional attr map, recursive children.
- **Fragments**: non-vector sequences (from `map`/`for`) containing hiccup elements. Distinguished from nodes by `seq?` vs `vector?`.

## What Validates

| Input | Result | Why |
|-------|--------|-----|
| `[:div "hello"]` | valid | Basic element |
| `[:div {:class "foo"} [:span "hi"]]` | valid | Nested with attrs |
| `[:main#morph [:div.container "hi"]]` | valid | CSS selector keywords are just keywords |
| `[:div (list [:span "a"] [:span "b"])]` | valid | Sequence children from `map` |
| `[:p "Count: " 5 " items"]` | valid | Mixed text and number children |
| `[:br]` | valid | Empty element |
| `[:div nil [:span "hi"]]` | valid | nil child (conditional rendering) |
| `"just a string"` | valid | Bare primitive is valid hiccup |
| `{:div "hello"}` | **invalid** | Maps are not hiccup |
| `["div" "hello"]` | **invalid** | First element must be keyword |
| `[42 "hello"]` | **invalid** | First element must be keyword |

## Performance

Validation is linear in tree size. Benchmarked on M-series Mac:

| Scenario | Time per validation |
|----------|-------------------|
| Simple element `[:div "hello"]` | ~0.06 ms |
| Realistic page (table with 20 rows, nested sections) | ~0.4 ms |
| Wide element (100 children) | ~0.25 ms |
| Deep nesting (50 levels) | ~1.3 ms |
| Deep nesting (100 levels) | ~2.4 ms |

Sub-millisecond for realistic pages. No performance concern for runtime instrumentation.

## Generator Support

Malli's `gen/recursive-gen` handles recursive schemas automatically. However, the `[:and vector? [:cat ...]]` pattern conflicts with generation because `:cat` produces sequences, not vectors. Two options:

**Option A (recommended): Custom gen/schema + gen/fmap on the :node branch.**

```clojure
[:and
 {:gen/schema :keyword
  :gen/fmap (fn [k] [k "generated"])}
 vector?
 [:cat keyword? ...]]

```

This produces simple but valid hiccup for generative tests. In practice, hiccup is an *output* type -- render functions produce it, nothing consumes generated hiccup as input -- so generation quality matters less than validation accuracy.

**Option B: Skip generation entirely.** Render response schemas like `[:map [:seon.render/html ::html]]` need generators only if render functions themselves are generatively tested. Since render functions take domain data (with its own generators) and produce hiccup, testing the output schema is less valuable than testing input/output contracts.

## The `:any` in Attribute Maps

The schema uses `[:map-of :keyword :any]` for HTML attributes. This is the one remaining `:any` in the schema. HTML attribute values are genuinely polymorphic:

- Strings: `{:class "foo"}`
- Booleans: `{:disabled true}`
- Vectors: `{:class [:foo :bar]}` (Hiccup class shorthand)
- Maps: `{:style {:color "red"}}` (inline styles)
- Numbers: `{:tabindex 0}`
- Data attributes: `{:data-on-click "$$get('/foo')"}`

Enumerating all valid attribute value types would be incomplete and fragile. This `:any` is acceptable because it's the HTML embedding layer, not Seon domain data. The attrs map is already constrained to keyword keys.

## Reagent-Style Component References

Seon does **not** currently use `[component-fn {args}]` Reagent-style references in render output. All render functions return resolved hiccup (no function references). If this changes, the schema would need an additional branch:

```clojure
;; NOT needed now, but for reference:
[:component [:cat fn? [:? :map] [:* [:ref "hiccup"]]]]

```

Since `fn?` values cannot be serialized or validated structurally, validating post-resolution hiccup (current approach) is the right call.

## Integration Plan

1. Register the recursive schema in `seon.render` as `::html` (replacing `:any`).
2. All existing render response schemas (`[:map [:seon.render/html :any]]`) automatically pick up the new type via the global registry.
3. No changes needed in render functions -- they already produce valid hiccup.
4. Add `{:gen/schema :keyword, :gen/fmap (fn [k] [k "generated"])}` to the node branch so generative tests on render response schemas don't fail.

## Files Affected

- `src/seon/render.clj` -- change `(schema/register! ::html :any)` to recursive schema
- No other files need changes (all reference `:seon.render/html` via registry)
