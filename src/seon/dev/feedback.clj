(ns seon.dev.feedback
  "REPL-side feedback utilities for the unified hook.

   Provides REPL introspection and generative testing capabilities:
   - Query Malli function schemas registered in a namespace
   - Extract schema references for context building
   - Run generative tests on schema-annotated functions

   This namespace is Phase 1 of the unified dev hook - no XTDB storage yet.
   That will be added in Phase 2."
  (:require [clojure.walk :as walk]
            [malli.core :as m]
            [malli.generator :as mg]))

;;; ---------------------------------------------------------------------------
;;; Schema Introspection
;;; ---------------------------------------------------------------------------

(defn namespace-schemas
  "Get all function schemas registered for a namespace.

   Returns a map of {fn-sym {:schema schema :ns ns :name name}} for all
   functions registered via `m/=>` in the given namespace.

   Returns nil if no schemas are registered for the namespace.

   Example:
     (namespace-schemas 'seon.trading.core)
     => {process-order {:schema [:=> [:cat :int] :string], :ns seon.trading.core, :name process-order}}"
  [ns-sym]
  ;; m/function-schemas stores schemas under namespace symbol directly
  ;; (not under :clj key as some docs suggest)
  (get (m/function-schemas) ns-sym))

(defn extract-schema-refs
  "Extract referenced schema keywords from a schema.

   Walks the schema form and collects all namespaced keywords that represent
   custom schema references (not built-in Malli types).

   Returns a set of keywords.

   Examples:
     (extract-schema-refs [:=> [:cat :user/id] :order/result])
     => #{:user/id :order/result}

     (extract-schema-refs [:map [:id :uuid] [:name :string]])
     => #{}  ; :uuid and :string are built-in types"
  [schema]
  (let [refs (atom #{})
        ;; Built-in types we should skip - these aren't registry refs
        builtin-types (set (keys (m/type-schemas)))
        ;; Get the raw form if it's a parsed schema, otherwise use as-is
        form (if (m/schema? schema)
               (m/form schema)
               schema)]
    (walk/postwalk
     (fn [x]
       ;; Namespaced keyword that's not a built-in type = registry ref
       (when (and (keyword? x)
                  (namespace x)
                  (not (contains? builtin-types x)))
         (swap! refs conj x))
       x)
     form)
    @refs))

;;; ---------------------------------------------------------------------------
;;; Generative Testing
;;; ---------------------------------------------------------------------------

(defn check-function
  "Run generative tests on a single function.

   Uses Malli's `mg/check` to generate random inputs based on the function's
   schema and verify the function produces valid outputs.

   Arguments:
     ns-sym  - Namespace symbol (e.g., 'seon.trading.core)
     fn-sym  - Function symbol (e.g., 'process-order)
     opts    - Optional map with:
               :num-tests - Number of tests to run (default: 10)

   Returns:
     nil         - If all tests pass
     {:fn fn-sym
      :error result} - If any test fails, with the shrunk counter-example

   Returns nil if the function has no registered schema."
  [ns-sym fn-sym & [{:keys [num-tests] :or {num-tests 10}}]]
  (when-let [schema-data (get (namespace-schemas ns-sym) fn-sym)]
    (when-let [var (ns-resolve ns-sym fn-sym)]
      (let [result (try
                     (mg/check (:schema schema-data)
                               @var
                               {:num-tests num-tests})
                     (catch Exception e
                       {:error {:type :check-exception
                                :message (ex-message e)}}))]
        ;; mg/check returns nil on success, or a map with :shrunk on failure
        (when result
          {:fn fn-sym
           :error result})))))

(defn check-namespace
  "Check all schema-annotated functions in a namespace.

   Runs generative tests on every function in the namespace that has a
   Malli function schema registered via `m/=>`.

   Arguments:
     ns-sym - Namespace symbol
     opts   - Optional map passed to check-function:
              :num-tests - Number of tests per function (default: 10)

   Returns:
     Vector of failure maps, each with {:fn fn-sym :error result}.
     Empty vector if all tests pass.

   Example:
     (check-namespace 'seon.trading.core {:num-tests 5})
     => []  ; all pass

     (check-namespace 'seon.broken-ns)
     => [{:fn some-fn :error {:shrunk {:smallest [...]}}}]"
  [ns-sym & [opts]]
  (let [schemas (namespace-schemas ns-sym)]
    (if (nil? schemas)
      []
      (->> (for [[fn-sym _] schemas]
             (check-function ns-sym fn-sym opts))
           (remove nil?)
           (into [])))))

;;; ---------------------------------------------------------------------------
;;; Utility Functions
;;; ---------------------------------------------------------------------------

(defn schema-fns
  "Get the set of all function symbols with schemas in a namespace.

   Example:
     (schema-fns 'seon.trading.core)
     => #{process-order validate-input create-signal}"
  [ns-sym]
  (set (keys (namespace-schemas ns-sym))))

(defn function-schema
  "Get the Malli schema for a specific function.

   Returns the schema in Malli form, or nil if no schema registered.

   Example:
     (function-schema 'seon.trading.core 'process-order)
     => [:=> [:cat :int] :string]"
  [ns-sym fn-sym]
  (when-let [schema-data (get (namespace-schemas ns-sym) fn-sym)]
    (m/form (:schema schema-data))))

(defn function-info
  "Get comprehensive info about a schema-annotated function.

   Returns a map with:
     :fn         - Function symbol
     :ns         - Namespace symbol
     :schema     - Malli schema in form notation
     :schema-refs - Set of referenced schemas
     :var-meta   - Selected var metadata (file, line)

   Returns nil if the function has no registered schema."
  [ns-sym fn-sym]
  (when-let [schema-data (get (namespace-schemas ns-sym) fn-sym)]
    (let [var (ns-resolve ns-sym fn-sym)
          schema (:schema schema-data)]
      {:fn fn-sym
       :ns ns-sym
       :schema (m/form schema)
       :schema-refs (extract-schema-refs schema)
       :var-meta (when var
                   (select-keys (meta var) [:file :line]))})))
