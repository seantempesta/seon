(ns seon.error.instrument
  "Phase A item 8 — Malli instrumentation error envelope + renderer.

   Bridges Malli's reporter callback to seon's eval-result envelope so
   the agent sees structured, programmatically-readable schema-violation
   data — not a stringified `pr-str` of a Java/JS exception.

   Three public fns:

     `report-fn`            — handed to (mi/instrument! {:report …}).
                              Throws ex-info with the envelope as ex-data;
                              the throw propagates through cljs.js, lands in
                              `eval`'s catch, gets flattened into
                              `(:seon.error/data error)` by seon.error/->map.

     `render-malli-error`   — string formatter used by format-eval-row to
                              produce the multi-line `;; ERROR …` block
                              the agent reads in recent-evals.

     `instrument-error?`    — predicate; true when an error map carries an
                              instrumentation envelope. Lets renderers and
                              programmatic callers branch without parsing
                              the kind keyword directly.

   Envelope shape (lives under `:seon.error/data` after error/->map
   flattening):

     {:seon.error/kind :seon.error.kind/malli-instrument-input  ; or /output, /arity, /guard
      :seon.error.malli/fn-sym 'seon.db/transact!
      :seon.error.malli/schema [:=> [:cat …] …]
      :seon.error.malli/path   [:seon.db/tx-data 0 :seon.agent.message/at]
      :seon.error.malli/leaf-type :malli.core/missing-key
      :seon.error.malli/expected \"…\"
      :seon.error.malli/got-edn  \"…\"
      :seon.error.malli/got-type \"map\"
      :seon.error.malli/humanized {…}                   ; me/humanize output
      :seon.error.malli/hint     \"did you mean …?\"     ; optional
      :seon.error.malli/arg-index 0                     ; :invalid-input only
      :seon.error.malli/return-value-edn \"…\"           ; :invalid-output only
      :seon.error.malli/arity 3                         ; :invalid-arity only
      :seon.error.malli/arities #{{:min 1 :max 2}}      ; :invalid-arity only
     }

   Full design + REPL probes in
   research/instrumentation-error-envelope-2026-05-24.md."
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.error :as me]
   [seon.ai.tokens :as tokens]
   [seon.schema :as schema]))

;; ============================================================
;; Schema registrations for the envelope keys.
;;
;; Per the research caveat (Q4): `{:optional true}` is only meaningful
;; inside a parent `:map`. These registrations are scalar shapes;
;; optionality is enforced at the consumer (the renderer treats absent
;; keys as "skip that row"). Same pattern as :seon.agent.message/hops.
;; ============================================================

(schema/register! :seon.error/kind             :keyword)
(schema/register! :seon.error.malli/fn-sym     :symbol)
(schema/register! :seon.error.malli/schema     [:vector :any])
(schema/register! :seon.error.malli/leaf-schema :any)
(schema/register! :seon.error.malli/path       [:vector :any])
(schema/register! :seon.error.malli/explain-path [:vector :any])
(schema/register! :seon.error.malli/leaf-type  :keyword)
(schema/register! :seon.error.malli/expected   :string)
(schema/register! :seon.error.malli/got-edn    :string)
(schema/register! :seon.error.malli/got-type   :string)
;; humanize returns either a map (path-keyed) for nested errors or a
;; vector of strings for top-level scalar misses (e.g. ["invalid type"]).
;; :or accommodates both without falling back to :any.
(schema/register! :seon.error.malli/humanized  [:or :map [:vector :any]])
(schema/register! :seon.error.malli/hint       :string)
(schema/register! :seon.error.malli/errors     [:vector :map])
(schema/register! :seon.error.malli/arg-index  :int)
(schema/register! :seon.error.malli/return-value-edn :string)
(schema/register! :seon.error.malli/arity      :int)
(schema/register! :seon.error.malli/arities    [:set :map])

(def ^:private kind-set
  "All instrumentation-flavored kinds, for predicate use."
  #{:seon.error.kind/malli-instrument-input
    :seon.error.kind/malli-instrument-output
    :seon.error.kind/malli-instrument-arity
    :seon.error.kind/malli-instrument-guard})

(defn instrument-error?
  "True when `env` looks like an envelope produced by `report-fn`."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [env]
  (and (map? env)
       (contains? kind-set (:seon.error/kind env))))

(defn pr-str-readable
  "pr-str a value with fn-objects replaced by readable placeholders.

   Fn-objects become `:seon.error.malli/fn`
   placeholders so the result round-trips through `read-string`.

   Malli schemas frequently embed fns (e.g. `[:fn some?]` ends up with
   the unreadable form `[:fn #object[…]]` after `m/form`); a naive
   pr-str of an envelope-with-schemas produces an unreadable string,
   which broke the renderer's read-string back to a map. Walking and
   stubbing fns at write-time means we don't depend on the reader's
   tag-handling and we get a faithful round-trip."
  {:malli/schema [:=> [:cat :any] :string]}
  [v]
  (pr-str
    (walk/postwalk
      (fn [x] (if (fn? x) :seon.error.malli/fn x))
      v)))

;; ============================================================
;; Truncation + type helpers
;; ============================================================

(defn- got-type
  "Stable string name of a value's runtime type. Avoids JVM `Class.getName`
   so the shape is the same on both runtimes if this ns ever crosses."
  [v]
  (cond
    (nil? v) "nil"
    (string? v) "string"
    (boolean? v) "boolean"
    (number? v) "number"
    (keyword? v) "keyword"
    (symbol? v) "symbol"
    (map? v) "map"
    (vector? v) "vector"
    (set? v) "set"
    (list? v) "list"
    (seq? v) "seq"
    (fn? v) "fn"
    :else
    #?(:clj  (.getName (class v))
       :cljs (try (.. v -constructor -name)
                  (catch :default _ "unknown")))))


;; ============================================================
;; Hint inference
;; ============================================================

(def ^:private coercion-hints
  "Leaf-schema → suggested coercion when the value is a string mismatched
   to a non-string schema. Five common types; expand as patterns emerge."
  {:int     "use (js/parseInt x 10) to convert string→int"
   :keyword "use (keyword x) to convert string→keyword"
   :symbol  "use (symbol x)"
   :set     "use (set x) to convert vector→set"
   :inst    "use (js/Date.) or coerce via #inst"})

(defn- hint-for
  "Compute a one-line hint for a leaf error. Returns nil when no hint
   pattern matches; the renderer skips the line then."
  [{:keys [type schema value in]} present-keys]
  (cond
    (= type :malli.core/missing-key)
    ;; The missing key is the LAST :in segment (malli's :map explain
    ;; conj's the key onto in; the leaf's :schema is the whole map
    ;; schema, never the keyword). Spell-check against the keys the
    ;; caller actually passed: same NAME + different NAMESPACE is the
    ;; classic wrong-ns call, so name the exact mistake.
    (let [missing (peek (vec in))]
      (when (and (seq present-keys) (keyword? missing))
        (if-let [near (some (fn [k]
                              (when (and (keyword? k)
                                         (= (name k) (name missing))
                                         (not= k missing))
                                k))
                            present-keys)]
          (str "you passed " (pr-str near)
               " — the key is " (pr-str missing))
          (str "did you mean " (pr-str missing) "?"))))

    (and (string? value) (get coercion-hints schema))
    (get coercion-hints schema)))

;; ============================================================
;; Envelope builder
;; ============================================================

(defn explain-payload
  "Convert a Malli reporter payload into the agent-facing envelope.

   Takes `type` + `data` map per malli.dev's
   reporter contract. Pure data; no side effects."
  {:malli/schema [:=> [:cat :any :map] :map]}
  [report-type {:keys [input output args value schema fn-name arity arities]
                :as _data}]
  (let [[explain-schema explain-value kind]
        (case report-type
          :malli.core/invalid-input
          [input args :seon.error.kind/malli-instrument-input]
          :malli.core/invalid-output
          [output value :seon.error.kind/malli-instrument-output]
          :malli.core/invalid-arity
          [nil nil :seon.error.kind/malli-instrument-arity]
          :malli.core/invalid-guard
          [nil nil :seon.error.kind/malli-instrument-guard]
          ;; Fallback for unknown report types — preserve as opaque kind.
          [nil nil (keyword "seon.error.kind"
                            (str "malli-" (name (or report-type :unknown))))])
        exp        (when explain-schema (m/explain explain-schema explain-value))
        leafs      (vec (:errors exp))
        first-leaf (first leafs)
        leaf-value (when first-leaf
                     (get-in explain-value (:in first-leaf)))
        present-keys (when (and first-leaf
                                (map? (get-in explain-value
                                              (butlast (:in first-leaf)))))
                       (keys (get-in explain-value
                                     (butlast (:in first-leaf)))))
        arg-index  (when (and (= kind :seon.error.kind/malli-instrument-input)
                              first-leaf
                              (number? (first (:in first-leaf))))
                     (first (:in first-leaf)))
        humanized  (when exp
                     (try (me/humanize exp)
                          (catch #?(:clj Throwable :cljs :default) _ nil)))
        hint       (when first-leaf (hint-for first-leaf present-keys))]
    (cond-> {:seon.error/kind kind}
      fn-name    (assoc :seon.error.malli/fn-sym fn-name)
      schema     (assoc :seon.error.malli/schema (m/form schema))
      first-leaf (assoc :seon.error.malli/leaf-schema (m/form (:schema first-leaf))
                        :seon.error.malli/path (vec (:in first-leaf))
                        :seon.error.malli/explain-path (vec (:path first-leaf))
                        :seon.error.malli/leaf-type (:type first-leaf)
                        :seon.error.malli/expected (tokens/bounded-pr-str (m/form (:schema first-leaf)) 50)
                        :seon.error.malli/got-edn (tokens/bounded-pr-str leaf-value 50)
                        :seon.error.malli/got-type (got-type leaf-value))
      humanized  (assoc :seon.error.malli/humanized humanized)
      (seq leafs) (assoc :seon.error.malli/errors (mapv #(into {} %) leafs))
      ;; The FULL args vector — malli hands it over on EVERY report type
      ;; (core.cljc:2210-2220); it was destructured and discarded here.
      ;; Push-button re-invocation wants all args, not just the failing
      ;; leaf. fn-stubbed THEN clipped (plain bounded-pr-str would print
      ;; unreadable #object[…] for fn-valued args); the generous budget
      ;; (vs got-edn's 50) is deliberate — under budget it round-trips
      ;; through read-string. `seon.error/record!` lifts this onto the
      ;; persisted error datom as `:seon.error/args-edn`.
      args       (assoc :seon.error/args-edn
                        (tokens/clip-str (pr-str-readable args) 200))
      arg-index  (assoc :seon.error.malli/arg-index arg-index)
      (= kind :seon.error.kind/malli-instrument-output)
      (assoc :seon.error.malli/return-value-edn (tokens/bounded-pr-str value 50))
      (= kind :seon.error.kind/malli-instrument-arity)
      (cond->
        arity   (assoc :seon.error.malli/arity arity)
        arities (assoc :seon.error.malli/arities arities))
      hint (assoc :seon.error.malli/hint hint))))

;; ============================================================
;; Reporter callback — what mi/instrument! invokes on a schema fail
;; ============================================================

(defn report-fn
  "Reporter for `(malli.instrument/instrument! {:report …})`.

   Throws
   an ex-info whose ex-data IS the envelope, so the throw flows
   through cljs.js → raw-eval reject → eval catch → seon.error/->map
   flattening, landing under `:seon.error/data` in the eval result."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [type data]
  (throw (ex-info (str type) (explain-payload type data))))

;; ============================================================
;; Renderer — string for format-eval-row
;; ============================================================

(defn- pad
  "Right-pad `s` to `n` chars with spaces. ASCII-only."
  [s n]
  (let [s (str s)]
    (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

(defn render-malli-error
  "Format the envelope into the multi-line ;; ERROR block.

   Returns
   a string (no trailing newline). Renders missing keys gracefully —
   if a column's source key is absent, that line is omitted."
  {:malli/schema [:=> [:cat :map] :string]}
  [{kind   :seon.error/kind
    fn-sym :seon.error.malli/fn-sym
    arg-i  :seon.error.malli/arg-index
    expected :seon.error.malli/expected
    path     :seon.error.malli/path
    got-edn  :seon.error.malli/got-edn
    got-type :seon.error.malli/got-type
    humanized :seon.error.malli/humanized
    hint     :seon.error.malli/hint
    arity    :seon.error.malli/arity
    arities  :seon.error.malli/arities
    :as _env}]
  (let [;; `humanize` returns whatever shape the explanation built:
        ;;   - top-level miss → `["invalid type"]` (vector of strings)
        ;;   - keyed misses   → `{:key ["msg"]}` (map of key → vec of strings)
        ;;   - nested         → recursive maps/vectors with strings at leaves
        ;; Walk the tree-seq and grab the first string we find — that's
        ;; the reason string for the first leaf, which is what we surface
        ;; in the `reason` column.
        first-reason (fn [x]
                       (->> (tree-seq coll? seq x)
                            (filter string?)
                            first))
        tag    (case kind
                 :seon.error.kind/malli-instrument-input  "malli/instrument-input"
                 :seon.error.kind/malli-instrument-output "malli/instrument-output"
                 :seon.error.kind/malli-instrument-arity  "malli/instrument-arity"
                 :seon.error.kind/malli-instrument-guard  "malli/instrument-guard"
                 "malli/instrument")
        header (str ";; ERROR  " tag
                    (when fn-sym (str "  " fn-sym))
                    (when (some? arg-i) (str "  arg " arg-i))
                    (when arity (str "  arity " arity)))
        ;; Extract a single reason string from whatever shape
        ;; humanize produced (vec/map/nested).
        reason (when humanized (first-reason humanized))
        body   (cond-> [header]
                 expected (conj (str ";; " (pad "expected" 10) expected
                                     (when (seq path)
                                       (str "    at  " (pr-str path)))))
                 got-edn  (conj (str ";; " (pad "got" 10) got-edn
                                     "    (" got-type ")"))
                 reason   (conj (str ";; " (pad "reason" 10) reason))
                 hint     (conj (str ";; " (pad "hint" 10) hint))
                 arities  (conj (str ";; " (pad "expected" 10)
                                     "arities " (pr-str arities))))]
    (str/join "\n" body)))
