(ns seon.code
  "Gate for agent-authored Clojure source.

   `check` is a pure predicate over a source string. It returns
   `{::passed? bool ::reasons [...]}` describing whether the source defines
   a single public function that follows Seon's contract:

     1. Top-level form is `(defn name ...)` or `(defn- name ...)`
     2. Exactly one arity, with a single map-destructured argument
     3. All destructured keys are namespaced keywords
     4. `:malli/schema` metadata is present on the name or in the attr-map

   `::source` itself is schemed as a string that must parse as a single
   form — instrumentation rejects un-parseable input at the boundary, so
   the body of `check` only deals with structural rules.

   No I/O, no eval, no side effects. Same code runs on both CLJ and CLJS.
   This is the seed of the agent-side `define!` gate; persistence and eval
   are layered on top later."
  (:require [edamame.core :as e]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Parser
;;; ---------------------------------------------------------------------------

(def ^:private parse-opts
  "Edamame options shared by `parse` and the `::source` schema predicate.
   `:auto-resolve` collapses `::keys` / `::ns/foo` so we can structurally
   inspect destructuring without needing the original ns context."
  {:all          false
   :readers      (fn [_tag] identity)
   :auto-resolve (fn [alias]
                   (if (= alias :current)
                     'seon.code.unknown
                     (symbol (name alias))))
   :regex        #(re-pattern (str %))})

(defn- parse-ok? [s]
  (try
    (e/parse-string s parse-opts)
    true
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn parse
  "Read a single Clojure form from `s`. Returns the form, or throws on
   parse error. Use this in code that has already validated `::source`
   via instrumentation — the throw means the boundary check is missing."
  [s]
  (e/parse-string s parse-opts))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(schema/register! ::source
                  [:and
                   [:string {:min 1}]
                   [:fn {:error/message "must parse as a single Clojure form"}
                    parse-ok?]])

(schema/register! ::passed? :boolean)

(schema/register! ::reason
                  [:enum
                   ::parse-error
                   ::not-defn
                   ::multi-arity
                   ::wrong-arity
                   ::not-map-binding
                   ::not-namespaced
                   ::missing-malli-schema])

(schema/register! ::reasons [:vector ::reason])

(schema/register! ::check-request
                  [:map [::source ::source]])

(schema/register! ::check-response
                  [:map
                   [::passed? ::passed?]
                   [::reasons {:optional true} ::reasons]])

;;; ---------------------------------------------------------------------------
;;; Structural analysis
;;; ---------------------------------------------------------------------------

(defn- defn-form? [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? #{'defn 'defn-} (first form))
       (symbol? (fnext form))))

(defn- split-defn
  "Returns {:name :name-meta :attr-map :tail} where :tail is the seq starting
   at the first arity vector (single-arity) or first arity list (multi-arity).
   Assumes `defn-form?` already passed."
  [form]
  (let [[_ fn-name & more] form
        [_doc more] (if (string? (first more))
                      [(first more) (next more)]
                      [nil more])
        [attr-map more] (if (map? (first more))
                          [(first more) (next more)]
                          [nil more])]
    {:name      fn-name
     :name-meta (meta fn-name)
     :attr-map  attr-map
     :tail      more}))

(defn- has-schema?
  "True if `:malli/schema` is present on the fn name's metadata or in the
   attr-map between docstring and args."
  [{:keys [name-meta attr-map]}]
  (boolean (or (some-> name-meta (contains? :malli/schema))
               (some-> attr-map (contains? :malli/schema)))))

(defn- key-namespaced?
  "A destructure key is acceptable when it is a namespaced keyword.
   Auto-resolved `::keys` reads as a namespaced keyword already
   (`:seon.code.unknown/keys` under our parse-opts), so it passes."
  [k]
  (and (keyword? k) (some? (namespace k))))

(defn- meta-key? [k]
  (and (keyword? k) (contains? #{"keys" "or" "as"} (name k))))

(defn- keys-entry-ok?
  "Validate a destructure entry whose key is `:keys`-shaped (i.e. `(name k)`
   is \"keys\"). If the key itself is namespaced (`::ns/keys`), the symbols in
   the vector inherit that namespace and pass. If the key is plain `:keys`,
   each vector entry must already be a namespaced keyword."
  [k v]
  (and (vector? v)
       (if (namespace k)
         true
         (every? key-namespaced? v))))

(defn- destructure-keys-namespaced?
  "Walk a map destructure binding and confirm every matched key is
   namespaced. `:strs` and `:syms` are rejected outright (string/symbol
   keys can't be namespaced under our contract)."
  [binding]
  (cond
    (not (map? binding)) false
    (or (contains? binding :strs)
        (contains? binding :syms)) false
    :else
    (every?
     (fn [[k v]]
       (cond
         ;; :keys-style entry — check by inheritance or vector contents
         (and (keyword? k) (= "keys" (name k))) (keys-entry-ok? k v)
         ;; :or and :as are scaffolding, not key matches
         (meta-key? k) true
         ;; Inline binding {sym ::ns/key} — v is the matched key, must be ns'd
         :else (key-namespaced? v)))
     binding)))

(defn- analyze-arity [tail]
  (let [head (first tail)]
    (cond
      (vector? head)          {:kind :single :args head}
      (seq?    head)          {:kind :multi}
      :else                   {:kind :none})))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn check
  "Check whether `::source` defines a single public function that satisfies
   Seon's map-in/map-out + `:malli/schema` contract.

   Returns `{::passed? true}` on success, or
   `{::passed? false ::reasons [...]}` listing every rule that failed."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{::keys [source]}]
  (let [form    (try (parse source)
                     (catch #?(:clj Exception :cljs :default) _ ::parse-error))
        reasons (volatile! [])]
    (cond
      (= ::parse-error form)
      {::passed? false ::reasons [::parse-error]}

      (not (defn-form? form))
      {::passed? false ::reasons [::not-defn]}

      :else
      (let [parts  (split-defn form)
            arity  (analyze-arity (:tail parts))]
        (case (:kind arity)
          :multi (vswap! reasons conj ::multi-arity)
          :none  (vswap! reasons conj ::wrong-arity)
          :single
          (let [args (:args arity)]
            (if-not (= 1 (count args))
              ;; arity wrong — skip the binding-shape check, it'd just add noise
              (vswap! reasons conj ::wrong-arity)
              (cond
                (not (map? (first args)))
                (vswap! reasons conj ::not-map-binding)

                (not (destructure-keys-namespaced? (first args)))
                (vswap! reasons conj ::not-namespaced)))))
        (when-not (has-schema? parts)
          (vswap! reasons conj ::missing-malli-schema))
        (if (seq @reasons)
          {::passed? false ::reasons @reasons}
          {::passed? true})))))
