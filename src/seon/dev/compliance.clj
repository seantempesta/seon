(ns seon.dev.compliance
  "Convention compliance checking for Clojure namespaces.

   Analyzes namespaces for adherence to docs/conventions.md patterns:
   - Missing :malli/schema metadata on public functions
   - Incomplete arg/return specs (bare keywords, :any, or missing input/output)
   - Missing docstrings
   - Unregistered schema refs / naming-convention drift

   The compliance bar (rule relaxed 2026-06-08): every public fn must FULLY
   SPEC + VALIDATE all args and its return via :malli/schema. TWO shapes are
   sanctioned, both compliant:
     1. map-in / map-out — one namespaced-keyword map in, one out
        ([:=> [:cat ::foo-request] ::foo-response]). Preferred for API surfaces.
     2. named positional — each slot fully specced via :catn inside a :=> or
        :function schema ([:=> [:catn [::a ::a] [::b ::b]] ::resp]). Fine for
        data-processing fns / mimicking a well-known API. Multi-arity is allowed
        when every arity is fully specced (a :function schema).
   The VIOLATION is an unspecced or bare-keyword argument (or :any anywhere, or
   a missing input/output) — NOT a positional one. A positional fn with a
   complete :catn/:cat schema is fully compliant.

   This enables the development hook to detect convention violations
   in real-time and provide feedback to developers.

   Example usage:
     (require '[seon.dev.compliance :as compliance])

     ;; Analyze a namespace
     (compliance/analyze-namespace {::compliance/namespace 'seon.dev.context})
     ;; => {::compliant? false
     ;;     ::violations [{::fn-name \"record-edit!\" ::violation-type :incomplete-spec ...}]
     ;;     ::public-fns 13
     ;;     ::with-schema 0
     ;;     ::with-complete-specs 0}

     ;; Check a single function
     (compliance/check-function {::compliance/var #'seon.dev.context/record-edit!})
     ;; => {::fn-name \"record-edit!\"
     ;;     ::has-schema? false
     ;;     ::has-docstring? true
     ;;     ::complete-spec? false
     ;;     ::violations [...]}

     ;; Format violations for display
     (compliance/format-violations {::compliance/violations [...] ::compliance/max-length 500})"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

;; Primitive types
(schema/register! ::namespace
                  [:or :symbol
                   [:fn {:description "Namespace symbol (e.g., seon.dev.context)"}
                    #(instance? clojure.lang.Namespace %)]])

(schema/register! ::var
                  [:fn {:description "Clojure var"}
                   var?])

(schema/register! ::fn-name
                  [:string {:min 1
                            :description "Function name"}])

(schema/register! ::violation-type
                  [:enum
                   :no-malli-schema      ; Missing :malli/schema metadata
                   :incomplete-spec      ; Args/return not fully specced (bare kw, :any, or missing input/output)
                   :no-docstring         ; Missing docstring
                   :unregistered-schema  ; Schema ref in metadata not in registry
                   :wrong-naming])       ; Schema doesn't follow fn-name-request/response convention

(schema/register! ::violation
                  [:map
                   [::fn-name ::fn-name]
                   [::violation-type ::violation-type]
                   [::message {:optional true} :string]
                   ;; Carried for fix generation / detailed reporting (optional).
                   [::arglists {:optional true} [:sequential :any]]
                   [::ns-str {:optional true} :string]
                   [::unregistered-refs {:optional true} [:sequential :keyword]]
                   [::schema-refs {:optional true} [:sequential :keyword]]])

(schema/register! ::violations
                  [:vector ::violation])

(schema/register! ::max-length
                  [:int {:min 1
                         :description "Maximum output length in characters"}])

;; Request/Response schemas

(schema/register! ::analyze-namespace-request
                  [:map
                   [::namespace ::namespace]])

(schema/register! ::analyze-namespace-response
                  [:map
                   [::compliant? :boolean]
                   [::violations ::violations]
                   [::public-fns :int]
                   [::with-schema :int]
                   [::with-complete-specs :int]])

(schema/register! ::check-function-request
                  [:map
                   [::var ::var]])

(schema/register! ::check-function-response
                  [:map
                   [::fn-name ::fn-name]
                   [::has-schema? :boolean]
                   [::has-docstring? :boolean]
                   [::complete-spec? :boolean]
                   [::violations ::violations]])

(schema/register! ::format-violations-request
                  [:map
                   [::violations ::violations]
                   [::max-length {:optional true} ::max-length]
                   [::with-fixes {:optional true} :boolean]])

(schema/register! ::format-violations-response
                  [:map
                   [::formatted :string]])

(schema/register! ::generate-fix-request
                  [:map
                   [::var ::var]])

(schema/register! ::generate-fix-response
                  [:map
                   [::fix-code {:optional true} :string]
                   [::compliant? :boolean]])

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:const default-max-length
  "Default maximum output length for formatted violations."
  500)

;;; ---------------------------------------------------------------------------
;;; Private Helpers
;;; ---------------------------------------------------------------------------

(defn- function-var?
  "Check if a var is a function (not a macro, protocol, etc.)."
  [v]
  (and (var? v)
       (fn? (var-get v))
       (not (:macro (meta v)))))

(def ^:private malli-base-types
  "The set of Malli base-type keywords from the default registry. A leaf
   keyword in a schema form is a concrete type iff it is one of these (and not
   :any) — otherwise it must be a registered seon schema."
  (set (keys (m/default-schemas))))

(def ^:private literal-ops
  "Schema ops whose remaining children are literal values, not sub-schemas.
   Their presence alone marks the element complete (e.g. [:enum :a :b]).
   NOTE: :ref / :schema are deliberately EXCLUDED — their child IS a schema
   reference and must be validated (e.g. [:ref ::foo] requires ::foo registered)."
  #{:enum :=})

(defn- schema-element-complete?
  "Recursively check that a Malli schema form element is fully specced.

   Complete means: NO :any, NO bare/unregistered keyword, every nested
   sub-schema also complete.
     - qualified keyword  -> must be schema/registered?
     - simple keyword     -> a Malli base type AND not :any
     - vector [op & args] -> :enum/:= and friends are literal-bearing (complete);
                             otherwise every child sub-schema must be complete
     - anything else      -> a literal value (enum member, count, etc.) -> complete"
  [form]
  (cond
    (qualified-keyword? form) (schema/registered? form)
    (keyword? form)           (and (not= :any form)
                                   (contains? malli-base-types form))
    (vector? form)            (if (contains? literal-ops (first form))
                                true
                                (every? (fn [child]
                                          (cond
                                            (map? child)     true ; properties map
                                            (keyword? child) (schema-element-complete? child)
                                            (vector? child)  (schema-element-complete? child)
                                            :else            true)) ; literal label/value
                                        (rest form)))
    :else                     true))

(defn- arrow-complete?
  "Check that a single :=> arrow [:=> input output] (props optional) fully
   specs both its input and its output."
  [arrow]
  (let [parts (rest arrow)
        parts (if (map? (first parts)) (rest parts) parts)
        [input output] parts]
    (and (some? input)
         (some? output)
         (schema-element-complete? input)
         (schema-element-complete? output))))

(defn- complete-spec?
  "Check whether a :malli/schema form fully specs all args and the return.

   Accepts the two sanctioned function-schema shapes:
     - :=>       single arity (map-in/map-out OR positional :cat/:catn)
     - :function multi-arity; every arity must be fully specced
   Returns false for nil, non-function-schema forms, or any incomplete arity."
  [schema-form]
  (boolean
   (when (vector? schema-form)
     (case (first schema-form)
       :=>       (arrow-complete? schema-form)
       :function (let [arrows (filter #(and (vector? %) (= :=> (first %)))
                                      (rest schema-form))]
                   (and (seq arrows) (every? arrow-complete? arrows)))
       false))))

(defn- has-malli-schema?
  "Check if function has :malli/schema metadata."
  [var-meta]
  (contains? var-meta :malli/schema))

(defn- has-docstring?
  "Check if function has a docstring."
  [var-meta]
  (string? (:doc var-meta)))

(defn- extract-schema-refs
  "Extract all schema keyword references from a :malli/schema form.

   Given: [:=> [:cat ::request-type] ::response-type]
   Returns: [::request-type ::response-type]

   Walks the schema form to find all qualified keywords."
  [schema-form]
  (cond
    (qualified-keyword? schema-form)
    [schema-form]

    (vector? schema-form)
    (mapcat extract-schema-refs schema-form)

    (map? schema-form)
    (mapcat extract-schema-refs (vals schema-form))

    :else []))

(defn- check-schema-refs-registered
  "Check if all schema refs in :malli/schema are registered.

   Returns vector of unregistered schema keywords, or empty if all registered."
  [schema-form]
  (let [refs (extract-schema-refs schema-form)]
    (filterv (complement schema/registered?) refs)))

(defn- map-in-schema?
  "True iff the :malli/schema is map-in/map-out shaped: a single :=> arity
   whose input is [:cat <ref>] with exactly one schema reference. The
   fn-name-request/fn-name-response naming convention only applies to this
   shape — positional :cat/:catn fns name their slots, not a request/response
   pair, so the naming check must NOT run for them."
  [schema-form]
  (boolean
   (when (and (vector? schema-form) (= :=> (first schema-form)))
     (let [parts (rest schema-form)
           parts (if (map? (first parts)) (rest parts) parts)
           input (first parts)]
       (and (vector? input)
            (= :cat (first input))
            (= 1 (count (rest input)))
            (qualified-keyword? (second input)))))))

(defn- check-naming-convention
  "Check if schema refs follow fn-name-request/fn-name-response pattern.

   For function 'foo' in namespace 'ns', expects:
   - ::foo-request (or similar) for input
   - ::foo-response (or similar) for output

   Note: Function name suffixes like ? or ! are stripped when checking.
   So 'clojure-file?' matches 'clojure-file-request'.

   Returns vector of violations if naming doesn't follow convention."
  [fn-name ns-str schema-form]
  (let [refs (extract-schema-refs schema-form)
        ;; Strip trailing punctuation (?, !) from fn-name for matching
        base-fn-name (str/replace fn-name #"[?!]+$" "")]
    ;; Only check if there are refs to check
    (when (seq refs)
      ;; Check if at least one ref follows the convention (contains base fn-name)
      (let [matching-refs (filter #(str/includes? (str %) base-fn-name) refs)]
        (when (empty? matching-refs)
          ;; None of the refs contain the function name
          refs)))))

(defn- check-var
  "Check a single var for convention violations.
   Returns a map with compliance info and violations.

   Performs both shallow checks (metadata presence) and deep checks
   (schema refs registered, naming convention)."
  [v]
  (let [m (meta v)
        fn-name (str (:name m))
        ns-str (str (:ns m))
        arglists (:arglists m)
        has-schema (has-malli-schema? m)
        has-doc (has-docstring? m)
        schema-form (:malli/schema m)
        ;; Compliant iff the :malli/schema fully specs all args + the return
        ;; (map-in/map-out OR positional :cat/:catn; no bare kw, no :any, no
        ;; missing input/output). Only meaningful when a schema is present.
        complete (and has-schema (complete-spec? schema-form))

        ;; Deep checks (only if schema exists)
        unregistered-refs (when has-schema
                           (check-schema-refs-registered schema-form))
        ;; Naming convention only applies to map-in/map-out schemas — positional
        ;; :cat/:catn fns name slots, not a request/response pair.
        wrong-naming-refs (when (and has-schema
                                     (empty? unregistered-refs)
                                     (map-in-schema? schema-form))
                           (check-naming-convention fn-name ns-str schema-form))

        violations (cond-> []
                     (not has-schema)
                     (conj {::fn-name fn-name
                            ::violation-type :no-malli-schema
                            ::message (str fn-name " is missing :malli/schema metadata")})

                     ;; Schema present but does not fully spec args/return.
                     (and has-schema (not complete))
                     (conj {::fn-name fn-name
                            ::violation-type :incomplete-spec
                            ::message (str fn-name " has an incomplete :malli/schema — every arg and the"
                                          " return must be fully specced (no bare keyword, no :any, no"
                                          " missing input/output) via map-in/map-out or positional :cat/:catn")})

                     (not has-doc)
                     (conj {::fn-name fn-name
                            ::violation-type :no-docstring
                            ::message (str fn-name " is missing a docstring")})

                     (seq unregistered-refs)
                     (conj {::fn-name fn-name
                            ::violation-type :unregistered-schema
                            ::message (str fn-name " references unregistered schemas: "
                                          (str/join ", " (map str unregistered-refs)))
                            ::unregistered-refs unregistered-refs
                            ::arglists arglists
                            ::ns-str ns-str})

                     (seq wrong-naming-refs)
                     (conj {::fn-name fn-name
                            ::violation-type :wrong-naming
                            ::message (str fn-name " schema refs don't follow naming convention (expected "
                                          fn-name "-request/" fn-name "-response)")
                            ::schema-refs wrong-naming-refs
                            ::arglists arglists
                            ::ns-str ns-str}))]
    ;; Add arglists and ns-str to all violations for fix generation
    {::fn-name fn-name
     ::has-schema? has-schema
     ::has-docstring? has-doc
     ::complete-spec? complete
     ::violations (mapv #(assoc % ::arglists arglists ::ns-str ns-str) violations)}))

(defn- truncate
  "Truncate string to max-len chars, adding marker if truncated."
  [s max-len]
  (if (and s (> (count s) max-len))
    (let [;; Reserve space for truncation marker, but ensure we keep at least 1 char
          keep-len (max 1 (- max-len 12))]
      (str (subs s 0 keep-len) "[truncated]"))
    (or s "")))

(defn- extract-param-names
  "Extract parameter names from function arglists.

   Given: ([x y opts])
   Returns: [x y opts]

   For multiple arities, returns the largest arity's params."
  [arglists]
  (when (seq arglists)
    (->> arglists
         (map vec)
         (sort-by count >)
         first)))

(defn- param-optional?
  "Heuristic: params named opts, options, config are likely optional."
  [param-name]
  (let [s (str param-name)]
    (or (str/ends-with? s "opts")
        (str/ends-with? s "options")
        (str/ends-with? s "config")
        (= s "opts"))))

(defn- generate-request-schema
  "Generate a request-schema registration skeleton from params.

   Emits a concrete-type placeholder (:string) per slot — NOT :any (which is a
   convention violation). The dev replaces each placeholder with a registered
   schema or concrete type.

   Example output:
     (schema/register! ::foo-request
       [:map [::input :string] [::opts {:optional true} :string]]) ; replace :string with real specs"
  [ns-str fn-name params]
  (let [schema-key (keyword ns-str (str fn-name "-request"))
        map-entries (for [param params]
                      (let [key (keyword ns-str (str param))
                            optional? (param-optional? param)]
                        (if optional?
                          (str "[" key " {:optional true} :string]")
                          (str "[" key " :string]"))))]
    (str "(schema/register! " schema-key "\n"
         "  [:map " (str/join "\n        " map-entries) "]) ; replace :string with real specs")))

(defn- generate-positional-schema
  "Generate a positional :catn function-schema skeleton from params.

   Each slot is a fully-namespaced named slot; the dev replaces the :string
   placeholder with a registered schema or concrete type.

   Example output:
     {:malli/schema [:=> [:catn [::a :string] [::b :string]] ::foo-response]}"
  [ns-str fn-name params]
  (let [resp-key (keyword ns-str (str fn-name "-response"))
        slots (for [param params]
                (str "[" (keyword ns-str (str param)) " :string]"))]
    (str "{:malli/schema [:=> [:catn " (str/join " " slots) "] " resp-key "]}"
         " ; replace :string with real specs")))

(defn- generate-response-schema
  "Generate a response-schema registration skeleton.

   Emits a concrete-type placeholder (:string), NOT :any.

   Example output:
     (schema/register! ::foo-response
       [:map [::result :string]]) ; replace :string with a real spec"
  [ns-str fn-name]
  (let [schema-key (keyword ns-str (str fn-name "-response"))
        result-key (keyword ns-str "result")]
    (str "(schema/register! " schema-key "\n"
         "  [:map [" result-key " :string]]) ; replace :string with a real spec")))

(defn- generate-metadata-form
  "Generate the :malli/schema metadata form.

   Example output:
     {:malli/schema [:=> [:cat ::foo-request] ::foo-response]}"
  [ns-str fn-name]
  (let [req-key (keyword ns-str (str fn-name "-request"))
        resp-key (keyword ns-str (str fn-name "-response"))]
    (str "{:malli/schema [:=> [:cat " req-key "] " resp-key "]}")))

(defn- generate-spec-suggestion
  "Suggest a complete :malli/schema for a fn, offering BOTH sanctioned shapes:
   map-in/map-out (preferred for API surfaces) and positional :catn (fine for
   data-processing fns). The dev picks one and replaces :string placeholders."
  [fn-name ns-str params]
  (let [params (or (seq params) ['input])]
    (str "Add a complete :malli/schema. Pick ONE shape:\n\n"
         "  (A) map-in / map-out (preferred for API surfaces):\n"
         "    " (generate-request-schema ns-str fn-name params) "\n"
         "    " (generate-response-schema ns-str fn-name) "\n"
         "    " (generate-metadata-form ns-str fn-name) "\n\n"
         "  (B) named positional :catn (fine for data-processing fns):\n"
         "    " (generate-response-schema ns-str fn-name) "\n"
         "    " (generate-positional-schema ns-str fn-name params))))

(defn- generate-fix-suggestion
  "Generate a fix suggestion for a non-compliant function.

   Returns a string suggesting how to make the fn compliant. For missing or
   incomplete specs, suggests adding a complete :malli/schema (either sanctioned
   shape). For unregistered refs, lists the schemas to register. Never suggests
   forcing a positional fn into map-in shape — positional-with-:catn is fine."
  [fn-name ns-str arglists violations]
  (let [params (extract-param-names arglists)
        has-no-schema? (some #(= :no-malli-schema (::violation-type %)) violations)
        has-incomplete? (some #(= :incomplete-spec (::violation-type %)) violations)
        has-unregistered? (some #(= :unregistered-schema (::violation-type %)) violations)]
    (cond
      ;; Missing schema entirely, or present but incomplete — suggest a full spec.
      (or has-no-schema? has-incomplete?)
      (str fn-name " needs a complete spec:\n\n"
           (generate-spec-suggestion fn-name ns-str params))

      ;; Has unregistered refs - show what to register
      has-unregistered?
      (let [unregistered (::unregistered-refs (first (filter #(= :unregistered-schema (::violation-type %)) violations)))]
        (str fn-name " references unregistered schemas:\n"
             "  Missing: " (str/join ", " (map str unregistered)) "\n\n"
             "Register them:\n"
             (str/join "\n"
                       (map (fn [k]
                              (str "  (schema/register! " k " [:map ...])"))
                            unregistered))))

      ;; Default - just return message
      :else
      (str fn-name ": " (str/join ", " (map ::message violations))))))

(defn- format-violations-with-fixes
  "Format violations with actionable fix suggestions."
  [violations]
  (let [by-fn (group-by ::fn-name violations)]
    (str/join "\n\n"
              (for [[fn-name fn-violations] by-fn]
                (let [v (first fn-violations)
                      ns-str (::ns-str v)
                      arglists (::arglists v)]
                  (generate-fix-suggestion fn-name
                                           (or ns-str "ns")
                                           arglists
                                           fn-violations))))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn analyze-namespace
  "Analyze a namespace for convention compliance.

   Checks all public functions in the namespace for:
   - :malli/schema metadata
   - complete arg/return specs (map-in/map-out OR positional :cat/:catn; no
     bare keyword, no :any, no missing input/output)
   - docstrings

   Request keys:
     ::namespace - Symbol or namespace object to analyze

   Response keys:
     ::compliant?           - Boolean, true if ALL public functions are compliant
     ::violations           - Vector of violation maps
     ::public-fns           - Count of public functions analyzed
     ::with-schema          - Count of functions with :malli/schema
     ::with-complete-specs  - Count of functions whose args + return are fully specced

   Example:
     (analyze-namespace {::namespace 'seon.dev.context})
     ;; => {::compliant? false
     ;;     ::violations [{::fn-name \"record-edit!\" ...}]
     ;;     ::public-fns 13
     ;;     ::with-schema 0
     ;;     ::with-complete-specs 0}"
  {:malli/schema [:=> [:cat ::analyze-namespace-request] ::analyze-namespace-response]}
  [{::keys [namespace]}]
  (let [ns-sym (if (instance? clojure.lang.Namespace namespace)
                 (ns-name namespace)
                 namespace)
        _ (require ns-sym)  ; Ensure namespace is loaded
        publics (ns-publics ns-sym)
        fn-vars (filter function-var? (vals publics))
        results (map check-var fn-vars)
        all-violations (vec (mapcat ::violations results))]
    {::compliant? (empty? all-violations)
     ::violations all-violations
     ::public-fns (count fn-vars)
     ::with-schema (count (filter ::has-schema? results))
     ::with-complete-specs (count (filter ::complete-spec? results))}))

(defn check-function
  "Check a single function for convention compliance.

   Examines the var's metadata for:
   - :malli/schema metadata
   - complete arg/return specs (map-in/map-out OR positional :cat/:catn)
   - docstring

   Request keys:
     ::var - The var to check (e.g., #'seon.dev.context/record-edit!)

   Response keys:
     ::fn-name        - Function name as string
     ::has-schema?    - Has :malli/schema metadata
     ::has-docstring? - Has docstring
     ::complete-spec? - All args + return fully specced (no bare kw, no :any)
     ::violations     - Vector of specific violations

   Example:
     (check-function {::var #'seon.dev.context/record-edit!})
     ;; => {::fn-name \"record-edit!\"
     ;;     ::has-schema? false
     ;;     ::has-docstring? true
     ;;     ::complete-spec? false
     ;;     ::violations [{::violation-type :no-malli-schema ...}]}"
  {:malli/schema [:=> [:cat ::check-function-request] ::check-function-response]}
  [{::keys [var]}]
  (if (function-var? var)
    (check-var var)
    {::fn-name (str (:name (meta var)))
     ::has-schema? false
     ::has-docstring? false
     ::complete-spec? false
     ::violations [{::fn-name (str (:name (meta var)))
                    ::violation-type :no-malli-schema
                    ::message (str (meta var) " is not a function var")}]}))

(defn format-violations
  "Format violations for hook feedback display.

   Produces a human-readable summary of convention violations,
   suitable for display in hook output.

   Request keys:
     ::violations - Vector of violation maps
     ::max-length - Optional max output length (default: 500)
     ::with-fixes - If true, include copy-pasteable fix code

   Response keys:
     ::formatted - Formatted string for display

   Example:
     (format-violations {::violations [{::fn-name \"foo\"
                                        ::violation-type :no-malli-schema
                                        ::message \"foo is missing :malli/schema\"}]
                         ::max-length 200})
     ;; => {::formatted \"Convention violations:\\n- foo: missing :malli/schema\"}"
  {:malli/schema [:=> [:cat ::format-violations-request] ::format-violations-response]}
  [{::keys [violations max-length with-fixes]}]
  (let [max-len (or max-length default-max-length)]
    (if (empty? violations)
      {::formatted "All functions comply with conventions."}
      (if with-fixes
        ;; With fixes - use detailed fix generation
        (let [formatted (format-violations-with-fixes violations)]
          {::formatted (truncate formatted max-len)})
        ;; Without fixes - brief summary
        (let [;; Group violations by function
              by-fn (group-by ::fn-name violations)
              ;; Format each function's violations
              lines (for [[fn-name fn-violations] by-fn]
                      (let [format-violation (fn [v]
                                               (case (::violation-type v)
                                                 :no-malli-schema "missing :malli/schema"
                                                 :incomplete-spec "incomplete arg/return spec"
                                                 :no-docstring "missing docstring"
                                                 :unregistered-schema
                                                 (str "unregistered schemas: "
                                                      (str/join ", " (map str (::unregistered-refs v))))
                                                 :wrong-naming "schema naming convention"
                                                 (str (::violation-type v))))
                            type-strs (map format-violation fn-violations)]
                        (str "- " fn-name ": " (str/join ", " type-strs))))
              header "Convention violations:"
              body (str/join "\n" lines)
              formatted (str header "\n" body)]
          {::formatted (truncate formatted max-len)})))))

(defn generate-fix
  "Generate fix suggestion code for a non-compliant function.

   Returns copy-pasteable code to fix convention violations including:
   - Schema registration calls
   - Function metadata
   - Map-in signature

   Request keys:
     ::var - The var to generate fixes for

   Response keys:
     ::compliant? - True if function is already compliant
     ::fix-code   - Generated fix code (only if not compliant)

   Example:
     (generate-fix {::var #'seon.schema/register!})
     ;; => {::compliant? false
     ;;     ::fix-code \"register! needs:\\n\\nSchema registrations:...\"}"
  {:malli/schema [:=> [:cat ::generate-fix-request] ::generate-fix-response]}
  [{::keys [var]}]
  (let [check-result (check-var var)
        violations (::violations check-result)]
    (if (empty? violations)
      {::compliant? true}
      (let [fn-name (::fn-name check-result)
            v (first violations)
            ns-str (::ns-str v)
            arglists (::arglists v)
            fix-code (generate-fix-suggestion fn-name
                                              (or ns-str "ns")
                                              arglists
                                              violations)]
        {::compliant? false
         ::fix-code fix-code}))))

;;; ---------------------------------------------------------------------------
;;; Convenience Functions
;;; ---------------------------------------------------------------------------

;; Request/Response schemas for compliance-summary
(schema/register! ::compliance-summary-request
                  [:map
                   [::namespace ::namespace]])

(schema/register! ::compliance-summary-response
                  [:map
                   [::summary :string]
                   [::compliant-count :int]
                   [::total-count :int]])

(defn compliance-summary
  "Get a brief summary suitable for logging.

   Request keys:
     ::namespace - Symbol or namespace object to analyze

   Response keys:
     ::summary         - One-line summary string
     ::compliant-count - Number of compliant functions
     ::total-count     - Total number of public functions

   Example:
     (compliance-summary {::namespace 'seon.ai.gemini})
     ;; => {::summary \"5/5 compliant (5 with schema, 5 fully specced)\"
     ;;     ::compliant-count 5
     ;;     ::total-count 5}"
  {:malli/schema [:=> [:cat ::compliance-summary-request] ::compliance-summary-response]}
  [{::keys [namespace]}]
  (let [result (analyze-namespace {::namespace namespace})
        {::keys [public-fns with-schema with-complete-specs violations]} result
        compliant (- public-fns (count (distinct (map ::fn-name violations))))]
    {::summary (format "%d/%d compliant (%d with schema, %d fully specced)"
                       compliant public-fns with-schema with-complete-specs)
     ::compliant-count compliant
     ::total-count public-fns}))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  (require '[seon.dev.compliance :as compliance])

  ;; Analyze a namespace
  (compliance/analyze-namespace {::namespace 'seon.dev.context})

  ;; Analyze a compliant namespace
  (compliance/analyze-namespace {::namespace 'seon.ai.gemini})

  ;; Check a specific function
  (compliance/check-function {::var #'seon.dev.context/record-edit!})
  (compliance/check-function {::var #'seon.ai.gemini/ask})

  ;; Format violations
  (let [result (compliance/analyze-namespace {::namespace 'seon.dev.context})]
    (compliance/format-violations {::violations (::violations result)
                                   ::max-length 200}))

  ;; Get summary
  (compliance/compliance-summary {::namespace 'seon.dev.context})
  (compliance/compliance-summary {::namespace 'seon.ai.gemini})

  ;; Inspect a var's :malli/schema to see what complete-spec? evaluates
  (meta #'seon.ai.gemini/ask)
  ;; => {...:arglists ([{::keys [prompt model timeout thinking-level system-instruction api-key]}])...}

  (meta #'seon.dev.context/record-edit!)
  ;; => {...:arglists ([db file-path ns-sym] [db file-path ns-sym opts])...}

  nil)
