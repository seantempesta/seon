---
type: research
status: in-progress
tags: [research, agent]
---

# Instrumentation error envelope — what to hand the agent

**Question:** When Malli function instrumentation catches an input/output validation failure on a Seon function call, what's the most helpful data structure to return to the agent that called it?

**Status:** Verified against the live pod (Malli 0.20.x on shadow-cljs). All payload shapes below are quoted verbatim from `m/-instrument` + `m/explain` + `me/humanize` + `me/with-spell-checking`. Recommendation is in §Q10.

---

## TL;DR

Malli already gives us everything we need; we just have to wire its `:report` callback into `seon.error/->map` and add a tiny renderer.

1. **The wire payload from Malli's `:report`** is a 2-arg call `(report type data)` where `type` is one of `:malli.core/invalid-input` / `:invalid-output` / `:invalid-arity` / `:invalid-guard`, and `data` is `{:input <schema> :output <schema> :args <vec> :value <ret> :schema <:=> ...>}`. `malli.instrument/-strument!` adds `:fn-name 'ns/sym` to the data. Verbatim probe in §Q1.

2. **The actionable info per failure** comes from running `m/explain` against `input` (for `::invalid-input`) or `output` (for `::invalid-output`) with the corresponding `args`/`value`. That returns `{:schema … :value … :errors ({:path … :in … :schema … :value … :type …})}`. The per-error `:in` (path into the value), `:schema` (the leaf schema that failed), `:type` (e.g. `::missing-key`, `::extra-key`), and the leaf `:value` are exactly what an LLM needs to localize the bug.

3. **For free hints, use `me/with-spell-checking` + `me/humanize`** on the explanation. On closed maps it produces `{:seon.db/tx-datas ["should be spelled :seon.db/tx-data"]}` — a perfect "did you mean" hint. (Verbatim probe in §Q7.) For non-closed maps it falls back to `["missing required key"]` / `["should be a string"]` / `["disallowed key"]`.

4. **The canonical agent-facing map** is a tagged envelope under `:seon.error/data` (so it flows through the existing `seon.error/->map` cause-chain flattening). See §Q4. Wire fields are all `:seon.error.malli/*` namespaced.

5. **The rendered string** is a 5-7 line block that fits in `recent-evals-section` and gives the agent fn-sym + arg index + the explain leaf + a hint + a truncated value preview. See §Q5.

6. **The programmable read API** is `(:seon.error/data result)` then case-dispatch on `:seon.error/kind`. See §Q6.

7. **Truncation policy:** `pr-str` then trim to 200 chars per value, 2 KB overall. See §Q8.

8. **Implementation sketch** in §Q9: ~40 lines. The reporter callback closes over a per-instrument context (fn-sym, arg-index of the failing element via `(:in error)`) and constructs the envelope using `m/explain` against the inner schema.

9. **Opinionated recommendation in §Q10** — ship the envelope in §Q4 + renderer in §Q5 + the three free hint patterns (`spell-check`, `missing-required`, `wrong-type-with-coercion`). Defer adversarial hints to v2.

---

## Q1 — What does Malli's reporter actually receive?

The reporter is called from `malli.core/-instrument-f` as `(report ::invalid-input  {:input <schema> :args <vec>  :schema <fn-schema>})` and `(report ::invalid-output {:output <schema> :value <ret> :args <vec> :schema <fn-schema>})`. Verified in `reference-code/malli/src/malli/core.cljc:2211-2220` and live in the pod:

```clojure
;; Probe — direct -instrument with capture
(let [captured (atom nil)
      schema [:=> [:cat :int :int] :int]
      f (m/-instrument {:schema schema
                        :scope #{:input :output}
                        :report (fn [type data] (reset! captured {:type type :data data}))}
                       (fn [a b] (+ a b)))]
  (try (f 1 "bad") (catch :default _ nil))
  @captured)
;; => {:type :malli.core/invalid-output
;;     :data {:output :int
;;            :value  "1bad"
;;            :args   [1 "bad"]
;;            :schema [:=> [:cat :int :int] :int]}}

```

Note: with `:scope #{:input :output}` and an input-bad call, output validation fires too (because the wrapped fn still ran and returned `"1bad"`). For `::invalid-input` to fire instead we'd need to short-circuit — Malli reports input first (line 2215) but doesn't `throw`, just calls `report` and continues. The default `:report` is `m/-fail!` (line 3128), which DOES throw, so under normal use only ONE report ever fires per failed call.

**Key shape:** `:input` is the **inner** `[:cat …]` schema (or `:catn`), not the outer `[:=> …]`. `:args` is always a vector of the actual args. `:value` is the return value (only on `::invalid-output`).

**`:fn-name` enrichment:** `malli.instrument/-strument!` wraps `report` to add `:fn-name (symbol (name n) (name s))`:

```clojure
;; from instrument.cljc:30
(update :report (fn [r] (fn [t data] (r t (assoc data :fn-name (symbol (name n) (name s)))))))

```

So when wiring our reporter we should expect `:fn-name 'seon.db/transact!` on every payload — we DON'T have to manage that.

---

## Q2 — `malli.dev.pretty` — what's already there?

`malli.dev.pretty` is a complete pretty-error implementation built on `malli.dev.virhe` (an ANSI/EDN pretty-printer). The pieces relevant to us:

- `(-printer)` returns a printer with terminal-only colors and a title.
- It defines `defmethod v/-format` for **exactly** the four reporter types we care about: `::m/invalid-input`, `::m/invalid-output`, `::m/invalid-guard`, `::m/invalid-arity` — plus `::m/explain` for generic explanations (`malli/dev/pretty.cljc:41-84`).
- The data model is **a hiccup-ish doc tree** with `[:group …]`, `:break`, and `v/-block` for labeled sections. It targets ANSI for terminals, not for inline plaintext.
- It uses `me/with-spell-checking` and `me/humanize` to turn the explain into a `{path [reason]}` map.

**Verdict:** we don't want pretty's ANSI output, but we want its **structure**: per-type formatter that knows what fields to label (`Invalid function arguments`, `Function Var`, `Input Schema`, `Errors`). The four templates in pretty.cljc are our template for the renderer in §Q5 — minus colors, minus the `cljdoc` link, minus the `(:break)` ceremony.

The important takeaway: `malli.dev.pretty` does NOT expose an intermediate "agent-friendly map" — it goes straight from reporter payload to terminal string. We have to build that intermediate map ourselves. But we can crib the field choices verbatim.

---

## Q3 — Anti-patterns to avoid

Confirmed by walking the V0 `seon.error/->map` + reading `format-eval-row`:

- **Stack traces in the eval log** — `seon.error/->map` truncates to 4 KB; instrumentation throws don't carry useful frames (the throw site is `m/-fail!` deep inside the instrument wrapper). Drop the stack from the rendered row; keep it under `:seon.error/stack` for programmatic inspection only.
- **Full-schema paste** — schemas like `:seon.agent/sessions` resolve to `[:vector {…} :seon.db/ref]` etc. They can be 200+ chars when fully inlined. Render the **schema key** (`:seon.db/tx-data`) and the **leaf schema** that failed (e.g. `:seon.db/ref`), not the outer composite.
- **Full-value paste** — a failed `db/transact!` arg can be a 50 KB tx-data vector. Truncate (see §Q8). Keep the path (`[:seon.db/tx-data 17 :seon.message/at]`) so the agent can drill in.
- **Humanize-only output** — `me/humanize` gives `{:age ["should be an integer"]}`. That's the leaf message but loses the actual bad value (`"thirty"`) and the expected schema. Emit BOTH the leaf error map AND the humanized line.
- **Wall-of-text** — V0 today dumps the whole ex-data via `pr-str` into one line. The renderer must be multi-line and aligned, like Clojure's own ex-info pprint.
- **Dumbed-down language** — "input doesn't match" is useless. The agent is a peer dev: say `arg 0 :seon.db/tx-data missing required key` with the registered schema key.

---

## Q4 — The canonical map shape

The envelope nests under `:seon.error/data` (Sean's existing `seon.error/->map` flattens all cause-chain ex-data into this key). Everything else is `:seon.error.malli/*` namespaced.

```clojure
{:seon.error/kind         :seon.error.kind/malli-instrument-input   ; or /output, /arity, /guard
 :seon.error.malli/fn-sym 'seon.db/transact!                        ; fully-qualified symbol from malli.instrument
 :seon.error.malli/schema [:=> [:cat :seon.db/transact-request] :seon.db/transact-response]
                                                                    ; the registered fn schema; round-trip via m/form so it's data
 :seon.error.malli/leaf-schema :seon.db/ref                         ; the deepest schema that failed (registered key
                                                                    ; when available, else the inline form)
 :seon.error.malli/path   [:seon.db/tx-data 17 :seon.message/at]    ; :in from m/explain leaf — path INTO the value
 :seon.error.malli/explain-path [0 :seon.db/tx-data 17 :seon.message/at]
                                                                    ; :path from m/explain leaf — path INTO the schema
 :seon.error.malli/leaf-type :malli.core/missing-key                ; :type from m/explain leaf (nil if a plain type miss)
 :seon.error.malli/expected "string"                                ; pr-str of the leaf schema, truncated
 :seon.error.malli/got-edn  "{:seon.db/typo true}"                  ; pr-str of the leaf value, truncated
 :seon.error.malli/got-type "cljs.core/PersistentArrayMap"          ; string class/type of the leaf value
 :seon.error.malli/humanized {:seon.db/tx-data ["missing required key"]}
                                                                    ; me/humanize output — the human one-liner
 :seon.error.malli/hint   "did you mean :seon.db/tx-data?"          ; optional; absent when no hint applies
 :seon.error.malli/errors [{...} {...}]                             ; ALL leaf errors (we surface the FIRST in path/leaf-*
                                                                    ; but agent code can iterate the full vector)
 :seon.error.malli/arg-index 0                                      ; for ::invalid-input — which positional arg failed (from :in)
 ;; ::invalid-output only:
 :seon.error.malli/return-value-edn "{:foo true}"                   ; pr-str, truncated
 ;; ::invalid-arity only:
 :seon.error.malli/arity     3
 :seon.error.malli/arities   #{{:min 1 :max 2}}}

```

**Why these field choices:**

- `:seon.error/kind` — matches the existing `:seon.error/kind` convention used elsewhere (e.g. timeouts).
- `:fn-sym` — agent's first question: "which of MY function calls failed?"
- `:schema` (fn schema) — kept as **data**, not pr-str. Agent can `m/explain` against it programmatically.
- `:leaf-schema` — when the failing leaf is a registered key like `:seon.db/ref`, store the keyword so the agent can `(m/deref :seon.db/ref)` to see the actual constraint. When inline, store the form.
- `:path` vs `:explain-path` — `:in` is what an agent indexes the **value** with; `:path` from explain is what they'd index the **schema** with. Both useful; `:in` is the one we render.
- `:humanized` — the readable line(s). Keep the whole map (path-keyed) so multi-error renders correctly.
- `:errors` — preserved for programmatic iteration. Renderer only shows the first.

**Registered Malli schema for the envelope** (lives in `seon.error`):

```clojure
(schema/register! :seon.error/kind :keyword)
(schema/register! :seon.error.malli/fn-sym :symbol)
(schema/register! :seon.error.malli/schema [:vector :any])      ; m/form is a vector
(schema/register! :seon.error.malli/leaf-schema :any)           ; keyword or form
(schema/register! :seon.error.malli/path [:vector :any])
(schema/register! :seon.error.malli/explain-path [:vector :any])
(schema/register! :seon.error.malli/leaf-type [:maybe :keyword])
(schema/register! :seon.error.malli/expected :string)
(schema/register! :seon.error.malli/got-edn :string)
(schema/register! :seon.error.malli/got-type :string)
(schema/register! :seon.error.malli/humanized :map)
(schema/register! :seon.error.malli/hint {:optional true} :string)
(schema/register! :seon.error.malli/errors [:vector :map])
(schema/register! :seon.error.malli/arg-index {:optional true} :int)
(schema/register! :seon.error.malli/return-value-edn {:optional true} :string)
(schema/register! :seon.error.malli/arity {:optional true} :int)
(schema/register! :seon.error.malli/arities {:optional true} [:set :map])

```

(Caveat: substrate doesn't allow standalone `{:optional true}` on `register!` — `:optional` is only meaningful inside a parent `:map`. So we'd register these as plain types and the optionality is enforced via map-schema membership at the consumer. Same pattern as existing `:seon.message/agent`.)

---

## Q5 — The rendered string (what the agent SEES)

This lands as `:seon.eval/error` on the failed eval and renders inside `recent-evals-section`. Sample for a `db/transact!` call where arg 0 is missing `:seon.db/tx-data`:

```
;; ERROR  malli/instrument-input  seon.db/transact!  arg 0
;; expected   :seon.db/transact-request    at  [:seon.db/tx-data]
;; got        {:seon.db/typo true}         (cljs.core/PersistentArrayMap)
;; reason     missing required key
;; hint       did you mean :seon.db/tx-data?

```

For an output failure on a fn that returned the wrong shape:

```
;; ERROR  malli/instrument-output  seon.user/foo
;; expected   :int                          at  []
;; got        "42"                          (string)
;; reason     should be an integer

```

For an arity failure:

```
;; ERROR  malli/instrument-arity  seon.user/foo  arity 3
;; expected   arity 1..2  (schema [:=> [:cat :int :int] :int])
;; got        (1 2 3)

```

**Why this layout:**

- First line tags the **kind** (`-input` / `-output` / `-arity` / `-guard`) + the **fn-sym** + (for input) the **arg index**. An LLM scanning a long eval log finds the right error fast.
- Columns: `expected`, `got`, `reason`, `hint` — same shape every time so the model can pattern-match against thousands of these.
- The `at <path>` puts the failing key right after `expected` so the eye doesn't scan twice.
- Value preview uses parens `(type)` instead of a separate line — denser.
- No stack trace. No "more information" link. No emoji.
- Strings on the left column are fixed-width 10 chars (`expected   `, `got        `, etc.) so multi-error renders align.

For **multi-leaf errors** (`{:name 42 :age "x"}` failing both keys), render one block per leaf, separated by blank line within the `;; ERROR` block:

```
;; ERROR  malli/instrument-input  seon.foo/bar  arg 0
;; expected   :string    at  [:name]    got  42         (number)    reason  should be a string
;; expected   :int       at  [:age]     got  "x"        (string)    reason  should be an integer

```

(Compressed to one line per leaf when total chars < ~120; multi-line otherwise.)

---

## Q6 — Programmatic read API

The agent reads structured errors via the existing `result` verb (which returns the live `:seon.eval/result` or, on failure, the error map). The shape is:

```clojure
;; Inside the agent's REPL — programmatic introspection
(let [r   (result :K9p2x4nB7q)              ; live failed-eval result
      err (:seon.error/data r)]             ; flattened ex-data envelope
  (case (:seon.error/kind err)
    :seon.error.kind/malli-instrument-input
    {:fix-target  (:seon.error.malli/fn-sym err)
     :fix-arg     (:seon.error.malli/arg-index err)
     :fix-path    (:seon.error.malli/path err)
     :leaf-schema (:seon.error.malli/leaf-schema err)
     ;; deref the registered leaf schema to see the actual constraint
     :constraint  (m/form (m/deref (:seon.error.malli/leaf-schema err)))}

    :seon.error.kind/malli-instrument-output
    {:fix-target (:seon.error.malli/fn-sym err)
     :bad-return (:seon.error.malli/return-value-edn err)}

    :seon.error.kind/malli-instrument-arity
    {:fix-target (:seon.error.malli/fn-sym err)
     :wrong-arity (:seon.error.malli/arity err)
     :expected   (:seon.error.malli/arities err)}

    ;; fallback
    {:unknown-kind (:seon.error/kind err)}))

```

This makes the error **programmable**: an agent can write its own diagnostic functions, can grep its own eval log for a specific `fn-sym`, can build a fix-suggestion loop. The substrate hands the LLM a typed shape — the LLM doesn't have to parse a free-form string to act on it.

---

## Q7 — Hint generation

`malli.error/with-spell-checking` gives us hints for free on **closed maps**:

```clojure
(let [exp (m/explain [:map {:closed true} [:seon.db/tx-data :any]]
                     {:seon.db/tx-datas []})]
  (-> exp me/with-spell-checking me/humanize))
;; => {:seon.db/tx-datas ["should be spelled :seon.db/tx-data"]}

```

On **open maps** the spell-check is a no-op:

```clojure
(let [exp (m/explain [:map [:seon.db/tx-data :any]] {:seon.db/tx-datas []})]
  (-> exp me/with-spell-checking me/humanize))
;; => {:seon.db/tx-data ["missing required key"]}   ; doesn't notice :tx-datas exists

```

This means we get hint-for-free **only if Seon's input schemas are closed**, which they should be anyway for `transact!`/`query`/etc. (a typo'd key is a bug, not future-proofing). For open maps we run our OWN spell-check by adding `{:closed true}` to the explain-pass temporarily, OR by writing a tiny similarity check using `me/-most-similar-to`.

**Three hint patterns to ship in v1:**

1. **Spell-check on missing-key** — when leaf is `::missing-key`, pull all the keys present in the value at the same path level, run `me/-most-similar-to` against the schema's expected keys, suggest the closest match: `"did you mean :seon.db/tx-data?"`. This is the highest-ROI hint.

2. **Type-coercion hint on type mismatch** — when leaf-schema is `:int` and value is a numeric string: `"use (js/parseInt x) to convert string→int"`. When leaf-schema is `:keyword` and value is a string: `"use (keyword x)"`. When leaf is `:set` and value is a vector: `"use (set x)"`. Hardcode 5-6 of these; cover 80% of the cases.

3. **Optional vs absent on `:malli.core/missing-key`** — point at the canonical `{:optional true}` pattern: `"key must be present; mark optional in schema if absent is valid"`. (Useful when the agent is the one DEFINING the schema, not just calling.)

**Five more candidates** (DON'T ship in v1, list for Sean):

4. Vector-vs-set order — when leaf is `:set` and value is sorted distinct vec, suggest `set`.
5. Lookup-ref shape — when leaf-schema is `:seon.db/ref` and value is a string, suggest `[:seon.<entity>/id "<that-string>"]`.
6. Long-int vs int — JS number vs `:int`; CLJS has no Long.
7. Date vs inst-string — `:inst` failures where value is an ISO string, suggest `(js/Date. s)`.
8. EDN-string vs map — common LLM mistake to pass `pr-str`'d data.

Don't over-engineer. Sean picks.

---

## Q8 — Truncation policy

Three layers, applied in order:

1. **Per-value pr-str:** `(pr-str v)` → if length > 200 chars, trim to 197 + `"..."`. Applies to `got-edn`, `return-value-edn`.
2. **Per-collection cap:** when the value is a vec/map, also bound visible elements: pr-str a `(take 5 …)` for vectors and `(into {} (take 5 …))` for maps, append `"... (N total)"`.
3. **Total envelope cap:** after constructing the full map, if `(pr-str map)` exceeds 2 KB (matches `seon.agent/truncate-edn`), drop `:seon.error.malli/errors` (it's the redundant raw vector) before truncating further.

For uninspectable values (functions, opaque JS objects):

```clojure
(defn- got-edn [v]
  (cond
    (fn? v)         (str "#fn[" (or (.-name v) "anonymous") "]")
    (= "object" (goog/typeOf v)) (str "#js[" (.. v -constructor -name) "]")
    :else           (let [s (pr-str v)]
                      (if (> (count s) 200) (str (subs s 0 197) "...") s))))

```

`got-type` uses `(if (some? v) (.. v -constructor -name) "nil")` (CLJS) or `(.-name (type v))` — gives `"PersistentArrayMap"`, `"String"`, etc. **Don't** use `(type v)` directly in pr-str because it produces unhelpful function-printable output.

---

## Q9 — Implementation sketch

A single namespace, `seon.error.instrument`, with two public fns: `report-fn` (the callback to hand to `mi/instrument!`) and `render-malli-error` (string formatter for `format-eval-row`).

```clojure
(ns seon.error.instrument
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [seon.error :as serror]
            [seon.schema :as schema]))

;; --- Schema registration (see §Q4) -----------------------------------------

(schema/register! :seon.error/kind :keyword)
(schema/register! :seon.error.malli/fn-sym :symbol)
;; ... (rest from §Q4)

;; --- Truncation helpers ----------------------------------------------------

(defn- got-type [v]
  (cond
    (nil? v)    "nil"
    (string? v) "string"
    (boolean? v) "boolean"
    (number? v) "number"
    (keyword? v) "keyword"
    (symbol? v) "symbol"
    (map? v)    "map"
    (vector? v) "vector"
    (set? v)    "set"
    (seq? v)    "seq"
    (fn? v)     "fn"
    :else (try (.. v -constructor -name) (catch :default _ "unknown"))))

(defn- truncate-pr [v limit]
  (let [s (pr-str v)]
    (if (> (count s) limit) (str (subs s 0 (- limit 3)) "...") s)))

;; --- Hint inference --------------------------------------------------------

(def ^:private coercion-hints
  {:int     "use (js/parseInt x 10) to convert string→int"
   :keyword "use (keyword x) to convert string→keyword"
   :symbol  "use (symbol x)"
   :set     "use (set x) to convert vector→set"})

(defn- hint-for [{:keys [type schema value]} value-keys]
  (cond
    (= type :malli.core/missing-key)
    (when-let [similar (and (seq value-keys)
                            (first (me/-most-similar-to value-keys schema #{schema})))]
      (str "did you mean " (pr-str schema) "?"))

    (and (= type :malli.core/extra-key) ;; spell-checked case
         (= type :malli.core/extra-key))
    nil  ;; spell-check already filled the message

    (and (string? value) (get coercion-hints schema))
    (get coercion-hints schema)))

;; --- Envelope builder ------------------------------------------------------

(defn- explain-payload
  "Given the Malli report-payload, run explain against the relevant
   sub-schema and produce the agent-facing envelope."
  [report-type {:keys [input output args value schema fn-name arity arities]}]
  (let [[explain-schema explain-value kind arg-i]
        (case report-type
          :malli.core/invalid-input
          [input args :seon.error.kind/malli-instrument-input nil]
          :malli.core/invalid-output
          [output value :seon.error.kind/malli-instrument-output nil]
          :malli.core/invalid-arity
          [nil nil :seon.error.kind/malli-instrument-arity nil]
          :malli.core/invalid-guard
          [nil nil :seon.error.kind/malli-instrument-guard nil])
        exp (when explain-schema (m/explain explain-schema explain-value))
        leafs (vec (:errors exp))
        first-leaf (first leafs)
        ;; Arg-index for ::invalid-input: first element of :in is the
        ;; positional arg (since input is a [:cat …])
        arg-index (when (and (= kind :seon.error.kind/malli-instrument-input)
                             first-leaf)
                    (first (:in first-leaf)))
        humanized (when exp
                    (-> exp
                        (cond-> (= (first (m/form explain-schema)) :map)
                          (update-in [:schema] #(m/schema [:map {:closed true} (rest %)])))
                        me/with-spell-checking
                        me/humanize))
        leaf-value (when first-leaf
                     (get-in explain-value (:in first-leaf)))
        present-keys (when (and first-leaf (map? (get-in explain-value (butlast (:in first-leaf)))))
                       (keys (get-in explain-value (butlast (:in first-leaf)))))
        leaf-schema (some-> first-leaf :schema m/form
                            (as-> $ (if (keyword? $) $ $)))]
    (cond-> {:seon.error/kind kind}
      fn-name    (assoc :seon.error.malli/fn-sym fn-name)
      schema     (assoc :seon.error.malli/schema (m/form schema))
      first-leaf (assoc :seon.error.malli/leaf-schema (m/form (:schema first-leaf))
                        :seon.error.malli/path (vec (:in first-leaf))
                        :seon.error.malli/explain-path (vec (:path first-leaf))
                        :seon.error.malli/leaf-type (:type first-leaf)
                        :seon.error.malli/expected (truncate-pr (m/form (:schema first-leaf)) 200)
                        :seon.error.malli/got-edn (truncate-pr leaf-value 200)
                        :seon.error.malli/got-type (got-type leaf-value))
      humanized  (assoc :seon.error.malli/humanized humanized)
      (seq leafs) (assoc :seon.error.malli/errors (mapv #(into {} %) leafs))
      arg-index  (assoc :seon.error.malli/arg-index arg-index)
      (= kind :seon.error.kind/malli-instrument-output)
      (assoc :seon.error.malli/return-value-edn (truncate-pr value 200))
      (= kind :seon.error.kind/malli-instrument-arity)
      (assoc :seon.error.malli/arity arity
             :seon.error.malli/arities (or arities #{}))
      (and first-leaf (hint-for first-leaf present-keys))
      (assoc :seon.error.malli/hint (hint-for first-leaf present-keys)))))

(defn report-fn
  "The callback handed to `mi/instrument! {:report report-fn}`. Throws
   an ex-info whose ex-data IS the agent-facing envelope, so it flows
   through seon.error/->map and lands under :seon.error/data."
  [type data]
  (throw (ex-info (str type) (explain-payload type data))))

;; --- Renderer for recent-evals tile ---------------------------------------

(defn- pad [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

(defn render-malli-error
  "Format the envelope into the multi-line string used in the eval row."
  [{:keys [:seon.error/kind
           :seon.error.malli/fn-sym
           :seon.error.malli/arg-index
           :seon.error.malli/expected
           :seon.error.malli/path
           :seon.error.malli/got-edn
           :seon.error.malli/got-type
           :seon.error.malli/humanized
           :seon.error.malli/hint
           :seon.error.malli/arity
           :seon.error.malli/arities] :as env}]
  (let [tag (case kind
              :seon.error.kind/malli-instrument-input  "malli/instrument-input"
              :seon.error.kind/malli-instrument-output "malli/instrument-output"
              :seon.error.kind/malli-instrument-arity  "malli/instrument-arity"
              :seon.error.kind/malli-instrument-guard  "malli/instrument-guard"
              "malli/instrument")
        header (str ";; ERROR  " tag "  " fn-sym
                    (when (some? arg-index) (str "  arg " arg-index))
                    (when arity (str "  arity " arity)))
        reason (some-> humanized first val first)
        body (cond-> [header]
               expected   (conj (str ";; " (pad "expected" 10) expected
                                     (when (seq path) (str "    at  " (pr-str path)))))
               got-edn    (conj (str ";; " (pad "got" 10) got-edn "    (" got-type ")"))
               reason     (conj (str ";; " (pad "reason" 10) reason))
               hint       (conj (str ";; " (pad "hint" 10) hint))
               arities    (conj (str ";; " (pad "expected" 10) "arities " (pr-str arities))))]
    (str/join "\n" body)))

```

**Wiring into `format-eval-row`** (`seon.agent`):

```clojure
;; In format-eval-row, when err is non-blank, attempt to parse it as
;; an EDN-encoded envelope. If it deserializes to a map with
;; :seon.error/kind starting with :seon.error.kind/malli-instrument-,
;; route through render-malli-error; otherwise fall through to the
;; existing (str ";; ERROR " err) plain path.

```

Because `:seon.eval/error` is `:string` today, the envelope has to be `pr-str`'d at write time and `read-string`'d at render time. Two options: (a) keep `:seon.eval/error` as `:string` but pr-str the envelope into it, (b) add a new `:seon.eval/error-data :map` attr and render from it directly. **(b) is cleaner** — agents can `(:seon.eval/error-data eval)` programmatically without a `read-string` round-trip. The existing `:seon.eval/error :string` can become the rendered string (denormalized for cheap display); both populated from the envelope at write time.

**Tests against the live pod** (would add `test/seon/error/instrument_test.cljs`):

```clojure
(deftest input-type-miss
  (let [env (explain-payload :malli.core/invalid-input
              {:input [:cat :int :int] :args [1 "bad"]
               :fn-name 'seon.user/foo
               :schema [:=> [:cat :int :int] :int]})]
    (is (= :seon.error.kind/malli-instrument-input (:seon.error/kind env)))
    (is (= 'seon.user/foo (:seon.error.malli/fn-sym env)))
    (is (= 1 (:seon.error.malli/arg-index env)))
    (is (= :int (:seon.error.malli/leaf-schema env)))
    (is (= "\"bad\"" (:seon.error.malli/got-edn env)))))

(deftest missing-key-with-spell-check
  (let [env (explain-payload :malli.core/invalid-input
              {:input [:cat [:map [:seon.db/tx-data :any]]]
               :args [{:seon.db/tx-datas []}]
               :fn-name 'seon.db/transact!
               :schema [:=> [:cat [:map [:seon.db/tx-data :any]]] :any]})]
    (is (= :malli.core/missing-key (:seon.error.malli/leaf-type env)))
    (is (str/includes? (:seon.error.malli/hint env) ":seon.db/tx-data"))))

(deftest output-miss
  (let [env (explain-payload :malli.core/invalid-output
              {:output :int :value "42" :args []
               :fn-name 'seon.user/foo
               :schema [:=> [:cat] :int]})]
    (is (= :seon.error.kind/malli-instrument-output (:seon.error/kind env)))
    (is (= "\"42\"" (:seon.error.malli/return-value-edn env)))))

```

---

## Q10 — Recommendation (what to ship in v1)

**Ship:**

1. `seon.error.instrument/report-fn` as the `:report` callback wired into `mi/instrument! {:report}` at boot (or whatever existing call site instruments substrate fns).
2. The envelope shape in §Q4, registered via `schema/register!` so the bridge accepts it and validation gate fires on misuse.
3. A new attr `:seon.eval/error-data :map` alongside the existing `:seon.eval/error :string`. eval-batch! populates BOTH when the failure is a Malli reporter throw: `error-data` gets the envelope, `error` gets `(render-malli-error envelope)` (denormalized for cheap render-time access).
4. `seon.agent/format-eval-row` reads `:seon.eval/error-data` first; falls back to `:seon.eval/error` for non-Malli errors (timeouts, generic throws, etc.).
5. Three hints: spell-check (on closed maps), missing-required-key spell-check (on open maps via opt-in `me/-most-similar-to`), wrong-type coercion (5 hard-coded mappings: `:int`, `:keyword`, `:symbol`, `:set`, `:inst` from string).

**Defer to v2:**

- Multi-leaf renderer optimization (the one-line-per-leaf compression in §Q5). v1 always renders one leaf (the first), with a footer `"; +N more errors"` when `(count errors) > 1`. The full `:errors` is in the data envelope for programmatic access.
- Lookup-ref / sequence-coercion hints (#4-8 in §Q7).
- Output-side spell-check (rare in practice — Seon fns are map-out, but the agent rarely defines them with wrong shapes).
- Cross-eval pattern detection ("this is the 3rd `:seon.db/typo` mistake on `seon.db/transact!` this session"). That's a section function over the eval log, not an error envelope concern.

**Reason to be opinionated here:** the V0 path today is `;; ERROR <stringified-ex>` — useless. Anything structured is a massive win. Sean's directive ("helpful not just everything or one thing") maps directly to: ship the envelope, ship the renderer, defer everything that requires per-fn schema annotations or cross-eval state.

---

## PLATFORM-FLAGs

**FLAG-1:** `seon.eval/eval-batch!` currently writes `:seon.eval/error :string` and not `:seon.eval/error-data :map`. Adding the new attr requires a schema registration + a small change to the batch reducer. Verify with Platform that this attr is in-scope for the same patch.

**FLAG-2:** `mi/instrument!` is called somewhere at substrate boot (need to confirm where — likely in `seon.client` or via a dev integrant component). If instrumentation isn't installed today, the report-fn never fires. Worth a quick check whether substrate fns ARE instrumented under V0 — if not, this whole PRD is "wire instrumentation AND ship envelope" instead of "ship envelope".

**FLAG-3:** `:seon.error/data` is the existing flatten target in `seon.error/->map`. Verify a `(throw (ex-info "..." envelope))` from `report-fn` round-trips through `eval-batch!` correctly — the envelope keys should land at `(:seon.error/data err-map)` because `->map` calls `(apply merge {} (ex-data-chain e))`. Quick REPL test: `(serror/->map (try (throw (ex-info "x" {:seon.error/kind :test})) (catch :default e e)))` should produce `{:seon.error/data {:seon.error/kind :test} ...}`.

---

## Open questions back to Sean

1. **Throw or return?** `report-fn` here throws; the alternative is to call `report-fn` from a `:report` that JUST records and lets the wrapped fn proceed with bad input. Malli's default (`m/-fail!`) throws. Throwing matches the agent's mental model ("my call failed → I get an error back"). Returning would mean instrumentation is silent advisory, which contradicts the whole point. **Recommend: throw.**

2. **Which fns get instrumented?** All public seon fns? Just substrate? Just `seon.db/*`? Sean's call. The envelope itself doesn't care; the question is what coverage we want.

3. **Should hints be data or string?** Currently `:seon.error.malli/hint :string`. Alternative: `:seon.error.malli/hint {:seon.error.hint/kind :spell-check :seon.error.hint/suggest :seon.db/tx-data}` — agents can branch on hint kind programmatically. **Recommend: keep string for v1, promote to data in v2 if usage shows up.**

4. **Closed-map opt-in?** Q7 noted spell-check only works on closed maps. Should `report-fn` re-walk the input schema to close every `:map` before re-running `me/explain` for hint purposes? Cost: one extra walk per failure. Benefit: spell-check on every map even if the schema author didn't mark it closed. **Recommend: yes, do it (cheap, only fires on failure).**

5. **Output envelope size budget?** Sean's "concise" — is 2 KB right? Modern agents see 200K-1M tokens; one error block at 2 KB is nothing. But the eval log is a sliding window of 20 evals → 40 KB if all errored, still nothing. **Recommend: 2 KB hard cap, no truncation hint needed.**
