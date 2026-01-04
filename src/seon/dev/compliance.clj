(ns seon.dev.compliance
  "Convention compliance checking for Clojure namespaces.

   Analyzes namespaces for adherence to CONVENTIONS.md patterns:
   - Missing :malli/schema metadata on public functions
   - Positional arguments instead of map-in pattern
   - Missing docstrings

   This enables the development hook to detect convention violations
   in real-time and provide feedback to developers.

   Example usage:
     (require '[seon.dev.compliance :as compliance])

     ;; Analyze a namespace
     (compliance/analyze-namespace {::compliance/namespace 'seon.dev.context})
     ;; => {::compliant? false
     ;;     ::violations [{::fn-name \"record-edit!\" ::violation-type :no-map-in ...}]
     ;;     ::public-fns 13
     ;;     ::with-schema 0
     ;;     ::with-map-in 0}

     ;; Check a single function
     (compliance/check-function {::compliance/var #'seon.dev.context/record-edit!})
     ;; => {::fn-name \"record-edit!\"
     ;;     ::has-schema? false
     ;;     ::has-docstring? true
     ;;     ::uses-map-in? false
     ;;     ::violations [...]}

     ;; Format violations for display
     (compliance/format-violations {::compliance/violations [...] ::compliance/max-length 500})"
  (:require [clojure.string :as str]
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
                   :no-map-in            ; Not using map-in pattern
                   :no-docstring         ; Missing docstring
                   :unregistered-schema  ; Schema ref in metadata not in registry
                   :wrong-naming])       ; Schema doesn't follow fn-name-request/response convention

(schema/register! ::violation
                  [:map
                   [::fn-name ::fn-name]
                   [::violation-type ::violation-type]
                   [::message {:optional true} :string]])

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
                   [::with-map-in :int]])

(schema/register! ::check-function-request
                  [:map
                   [::var ::var]])

(schema/register! ::check-function-response
                  [:map
                   [::fn-name ::fn-name]
                   [::has-schema? :boolean]
                   [::has-docstring? :boolean]
                   [::uses-map-in? :boolean]
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

(defn- uses-map-in?
  "Check if arglists indicate map-in pattern.

   Map-in pattern: single argument that is a map destructuring form.
   Examples that match:
     ([{::keys [foo bar]}])         - namespaced keys destructuring
     ([{:keys [foo bar]}])          - regular keys destructuring
     ([{:as opts}])                 - just :as binding

   Note: Multi-arity is allowed if ALL arities use map-in."
  [arglists]
  (when (seq arglists)
    (every? (fn [arglist]
              (and (= 1 (count arglist))
                   (map? (first arglist))))
            arglists)))

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
        uses-map (uses-map-in? arglists)
        schema-form (:malli/schema m)

        ;; Deep checks (only if schema exists)
        unregistered-refs (when has-schema
                           (check-schema-refs-registered schema-form))
        wrong-naming-refs (when (and has-schema (empty? unregistered-refs))
                           (check-naming-convention fn-name ns-str schema-form))

        violations (cond-> []
                     (not has-schema)
                     (conj {::fn-name fn-name
                            ::violation-type :no-malli-schema
                            ::message (str fn-name " is missing :malli/schema metadata")})

                     (not uses-map)
                     (conj {::fn-name fn-name
                            ::violation-type :no-map-in
                            ::message (str fn-name " does not use map-in pattern (single map argument)")})

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
     ::uses-map-in? uses-map
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

(defn- generate-schema-key
  "Generate schema key for a param in the function's namespace."
  [ns-str fn-name param-name]
  (keyword ns-str (str fn-name "-" (str param-name))))

(defn- generate-request-schema
  "Generate a request schema registration from params.

   Example output:
     (schema/register! ::foo-request
       [:map [::input :any] [::opts {:optional true} :map]])"
  [ns-str fn-name params]
  (let [schema-key (keyword ns-str (str fn-name "-request"))
        map-entries (for [param params]
                      (let [key (keyword ns-str (str param))
                            optional? (param-optional? param)]
                        (if optional?
                          (str "[" key " {:optional true} :any]")
                          (str "[" key " :any]"))))]
    (str "(schema/register! " schema-key "\n"
         "  [:map " (str/join "\n        " map-entries) "])")))

(defn- generate-response-schema
  "Generate a response schema registration.

   Example output:
     (schema/register! ::foo-response
       [:map [::result :any]])"
  [ns-str fn-name]
  (let [schema-key (keyword ns-str (str fn-name "-response"))
        result-key (keyword ns-str "result")]
    (str "(schema/register! " schema-key "\n"
         "  [:map [" result-key " :any]])")))

(defn- generate-metadata-form
  "Generate the :malli/schema metadata form.

   Example output:
     {:malli/schema [:=> [:cat ::foo-request] ::foo-response]}"
  [ns-str fn-name]
  (let [req-key (keyword ns-str (str fn-name "-request"))
        resp-key (keyword ns-str (str fn-name "-response"))]
    (str "{:malli/schema [:=> [:cat " req-key "] " resp-key "]}")))

(defn- generate-map-in-signature
  "Generate the map-in signature from params.

   Example output:
     [{::keys [input opts]}]"
  [ns-str params]
  (let [keys-str (str/join " " (map #(keyword ns-str (str %)) params))]
    (str "[{::keys [" (str/join " " (map str params)) "]}]")))

(defn- generate-fix-suggestion
  "Generate a complete fix suggestion for a non-compliant function.

   Returns a string with:
   1. Schema registrations to add
   2. Metadata to add to function
   3. New signature to use"
  [fn-name ns-str arglists violations]
  (let [params (extract-param-names arglists)
        has-no-schema? (some #(= :no-malli-schema (::violation-type %)) violations)
        has-no-map-in? (some #(= :no-map-in (::violation-type %)) violations)
        has-unregistered? (some #(= :unregistered-schema (::violation-type %)) violations)]
    (cond
      ;; Missing everything - generate full fix
      (and has-no-schema? has-no-map-in? params)
      (str fn-name " needs:\n\n"
           "Schema registrations:\n"
           "  " (generate-request-schema ns-str fn-name params) "\n"
           "  " (generate-response-schema ns-str fn-name) "\n\n"
           "Function metadata:\n"
           "  " (generate-metadata-form ns-str fn-name) "\n"
           "  " (generate-map-in-signature ns-str params))

      ;; Has schema but not map-in
      (and has-no-map-in? params (not has-no-schema?))
      (str fn-name " needs map-in pattern:\n"
           "  " (generate-map-in-signature ns-str params))

      ;; Missing schema only
      has-no-schema?
      (str fn-name " needs:\n\n"
           "Schema registrations:\n"
           "  " (generate-request-schema ns-str fn-name (or params ['input])) "\n"
           "  " (generate-response-schema ns-str fn-name) "\n\n"
           "Function metadata:\n"
           "  " (generate-metadata-form ns-str fn-name))

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
   - map-in pattern (single map argument with destructuring)
   - docstrings

   Request keys:
     ::namespace - Symbol or namespace object to analyze

   Response keys:
     ::compliant?   - Boolean, true if ALL public functions are compliant
     ::violations   - Vector of violation maps
     ::public-fns   - Count of public functions analyzed
     ::with-schema  - Count of functions with :malli/schema
     ::with-map-in  - Count of functions using map-in pattern

   Example:
     (analyze-namespace {::namespace 'seon.dev.context})
     ;; => {::compliant? false
     ;;     ::violations [{::fn-name \"record-edit!\" ...}]
     ;;     ::public-fns 13
     ;;     ::with-schema 0
     ;;     ::with-map-in 0}"
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
     ::with-map-in (count (filter ::uses-map-in? results))}))

(defn check-function
  "Check a single function for convention compliance.

   Examines the var's metadata for:
   - :malli/schema metadata
   - map-in argument pattern
   - docstring

   Request keys:
     ::var - The var to check (e.g., #'seon.dev.context/record-edit!)

   Response keys:
     ::fn-name        - Function name as string
     ::has-schema?    - Has :malli/schema metadata
     ::has-docstring? - Has docstring
     ::uses-map-in?   - Uses [{::keys [...]}] pattern
     ::violations     - Vector of specific violations

   Example:
     (check-function {::var #'seon.dev.context/record-edit!})
     ;; => {::fn-name \"record-edit!\"
     ;;     ::has-schema? false
     ;;     ::has-docstring? true
     ;;     ::uses-map-in? false
     ;;     ::violations [{::violation-type :no-malli-schema ...}
     ;;                   {::violation-type :no-map-in ...}]}"
  {:malli/schema [:=> [:cat ::check-function-request] ::check-function-response]}
  [{::keys [var]}]
  (if (function-var? var)
    (check-var var)
    {::fn-name (str (:name (meta var)))
     ::has-schema? false
     ::has-docstring? false
     ::uses-map-in? false
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
                                                 :no-map-in "not using map-in"
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
     ;; => {::summary \"5/5 compliant (5 with schema, 5 with map-in)\"
     ;;     ::compliant-count 5
     ;;     ::total-count 5}"
  {:malli/schema [:=> [:cat ::compliance-summary-request] ::compliance-summary-response]}
  [{::keys [namespace]}]
  (let [result (analyze-namespace {::namespace namespace})
        {::keys [public-fns with-schema with-map-in violations]} result
        compliant (- public-fns (count (distinct (map ::fn-name violations))))]
    {::summary (format "%d/%d compliant (%d with schema, %d with map-in)"
                       compliant public-fns with-schema with-map-in)
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

  ;; Check uses-map-in? detection
  (meta #'seon.ai.gemini/ask)
  ;; => {...:arglists ([{::keys [prompt model timeout thinking-level system-instruction api-key]}])...}

  (meta #'seon.dev.context/record-edit!)
  ;; => {...:arglists ([xtdb-node file-path ns-sym] [xtdb-node file-path ns-sym opts])...}

  nil)
