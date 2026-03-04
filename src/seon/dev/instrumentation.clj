(ns seon.dev.instrumentation
  "Malli function instrumentation with agent-friendly error messages.

   Wraps malli.instrument to provide rich, structured error messages when
   functions are called with invalid arguments. Instead of cryptic schema
   validation failures, agents see:
   - Which argument failed and what was expected
   - The full schema expanded with descriptions
   - The function's return type
   - An example valid call (generated)
   - The function's docstring

   ## Lifecycle

   Managed as an Integrant component (:seon.dev/instrumentation).
   Survives `(user/reset)` via suspend/resume. Automatically re-instruments
   after code reload via `refresh!`.

   ## Usage

   ```clojure
   ;; Manual start (normally via Integrant)
   (start! {})

   ;; After code reload
   (refresh!)

   ;; Stop
   (stop!)
   ```"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.instrument :as mi]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Expansion
;;; ---------------------------------------------------------------------------

(defn- expand-schema
  "Recursively expand a schema into a human-readable string.
   Depth-limited to prevent infinite recursion on recursive schemas."
  ([schema] (expand-schema schema 0))
  ([schema depth]
   (if (> depth 2)
     (str (m/form schema))
     (try
       (let [s (try (m/deref schema) (catch Throwable _ schema))
             t (m/type s)]
         (case t
           :map
           (let [entries (m/entries s)]
             (str "[:map"
                  (when (seq entries)
                    (str "\n"
                         (->> entries
                              (map (fn [[k vs]]
                                     (let [props (m/properties vs)
                                           opt? (:optional props)
                                           child (first (m/children vs))
                                           desc (:description (m/properties child))
                                           leaf (if (and (< depth 2) (keyword? (m/form child)))
                                                  (expand-schema child (inc depth))
                                                  (str (m/form child)))]
                                       (str "  " k
                                            (if opt? " (optional)" " (required)")
                                            " -- " leaf
                                            (when desc (str "  ; " desc))))))
                              (str/join "\n"))))
                  "]"))

           :enum
           (str "[:enum " (str/join " " (m/children s)) "]")

           :maybe
           (str "[:maybe " (expand-schema (first (m/children s)) (inc depth)) "]")

           :vector
           (str "[:vector " (expand-schema (first (m/children s)) (inc depth)) "]")

           :cat
           (str "[:cat "
                (->> (m/children s)
                     (map #(expand-schema % (inc depth)))
                     (str/join " "))
                "]")

           :=>
           (let [[input output] (m/children s)]
             (str "[:=> " (expand-schema input (inc depth))
                  " " (expand-schema output (inc depth)) "]"))

           ;; Default: just show the form
           (str (m/form s))))
       (catch Throwable _
         (str (try (m/form schema) (catch Throwable _ "?"))))))))

;;; ---------------------------------------------------------------------------
;;; Error Formatting Helpers
;;; ---------------------------------------------------------------------------

(defn- resolve-fn-context
  "Given a fn-name symbol, resolve the var and extract docstring + schema info."
  [fn-name]
  (try
    (when-let [v (resolve fn-name)]
      (let [m (meta v)]
        {:doc (:doc m)
         :schema (:malli/schema m)}))
    (catch Throwable _ nil)))

(defn- generate-example
  "Try to generate an example input for the given schema.
   Accepts :cat (input schema), :=> (extracts input), or :function (first arity's input)."
  [schema]
  (try
    (let [s (m/schema schema)
          t (m/type s)
          input-s (case t
                    :function (first (m/children (first (m/children s))))
                    :=> (first (m/children s))
                    s)]
      (mg/generate input-s {:size 3}))
    (catch Throwable _ nil)))

(defn- schema-ref-name
  "Get the keyword ref name from a :cat child, if it's a registered schema ref
   (namespaced keyword). Returns nil for primitive type keywords like :string, :int."
  [input pos]
  (try
    (let [children (m/children (m/schema input))]
      (when-let [child (nth children pos nil)]
        (let [form (m/form child)]
          (when (and (keyword? form) (namespace form))
            form))))
    (catch Throwable _ nil)))

(defn- human-type
  "Describe a value's type in human terms."
  [v]
  (cond
    (nil? v) "nil"
    (keyword? v) "keyword"
    (string? v) "string"
    (integer? v) "integer"
    (float? v) "float"
    (boolean? v) "boolean"
    (map? v) "map"
    (vector? v) "vector"
    (sequential? v) "sequence"
    (symbol? v) "symbol"
    (set? v) "set"
    :else (str (type v))))

(defn- format-input-errors
  "Use m/explain to get per-argument errors from a :cat input schema."
  [input args]
  (try
    (let [explanation (m/explain (m/schema input) (vec args))]
      (when explanation
        (->> (:errors explanation)
             (map (fn [err]
                    (let [in-path (:in err)
                          pos (first in-path)
                          nested-keys (rest in-path)
                          bad-val (:value err)
                          expected (m/form (:schema err))]
                      (if (seq nested-keys)
                        ;; Nested error inside an arg (e.g. wrong value for a map key)
                        (str "  Arg " pos " > " (str/join " > " (map pr-str nested-keys))
                             " — expected " (pr-str expected)
                             ", got " (pr-str bad-val) " (" (human-type bad-val) ")")
                        ;; Top-level arg type mismatch
                        (let [ref-name (schema-ref-name input pos)
                              ref-type (when ref-name
                                         (try (name (m/type (m/deref (m/schema ref-name))))
                                              (catch Throwable _ nil)))]
                          (str "  Arg " pos ": expected "
                               (if ref-name
                                 (str ref-name " (" (or ref-type "schema") ")")
                                 (pr-str expected))
                               ", got " (pr-str bad-val)
                               " (" (human-type bad-val) ")"))))))
             (str/join "\n"))))
    (catch Throwable _ nil)))

(defn- format-output-errors
  "Use m/explain to get specific output validation errors."
  [output value]
  (try
    (let [explanation (m/explain (m/schema output) value)]
      (when explanation
        (->> (:errors explanation)
             (take 5)
             (map (fn [err]
                    (let [in-path (:in err)
                          bad-val (:value err)
                          expected (m/form (:schema err))]
                      (str "  " (str/join " > " (map pr-str in-path))
                           " — expected " (pr-str expected)
                           ", got " (pr-str bad-val)))))
             (str/join "\n"))))
    (catch Throwable _ nil)))

(defn- format-arity-entry
  "Format a single arity entry. Handles both {:min N :max N} maps
   (from :=> schemas) and plain integers or :varargs (from :function schemas)."
  [entry]
  (cond
    (map? entry)
    (let [{:keys [min max]} entry]
      (if (= min max)
        (str min " arg" (when (not= 1 min) "s"))
        (str min "-" max " args")))

    (= :varargs entry)
    "variadic"

    (integer? entry)
    (str entry " arg" (when (not= 1 entry) "s"))

    :else
    (str entry)))

(defn- format-arities
  "Format arities set as human-readable string.
   Arities may be a set of {:min N :max N} maps (from :=> schemas)
   or plain integers/:varargs (from :function schemas)."
  [arities]
  (->> arities
       (sort-by #(cond (= :varargs %) Integer/MAX_VALUE
                       (map? %) (:min %)
                       :else %))
       (map format-arity-entry)
       (str/join " or ")))

(defn- truncate
  "Truncate a string to max-len characters, appending ... if truncated."
  [s max-len]
  (let [s (str s)]
    (if (> (count s) max-len)
      (str (subs s 0 max-len) "...")
      s)))

;;; ---------------------------------------------------------------------------
;;; Error Formatting
;;; ---------------------------------------------------------------------------

(defn- format-error
  "Format a rich error message for an instrumentation failure.
   Each error type (input, output, arity) gets specific diagnostics."
  [type {:keys [fn-name args input output value] :as data}]
  (let [ctx (resolve-fn-context fn-name)
        fn-schema (:schema ctx)
        resolved-input
        (when fn-schema
          (try
            (let [s (m/schema fn-schema)
                  t (m/type s)]
              (cond
                (= :=> t) (first (m/children s))
                (= :function t) (first (m/children (first (m/children s))))
                :else nil))
            (catch Throwable _ nil)))
        example (when resolved-input (generate-example resolved-input))]
    (str "\n"
         "== INVALID " (case type
                         ::m/invalid-input "INPUT"
                         ::m/invalid-output "OUTPUT"
                         ::m/invalid-arity "ARITY"
                         (name type))
         " ==\n"
         "x (" fn-name
         (when (seq args)
           (str " " (str/join " " (map pr-str args))))
         ")\n"
         "\n"
         (case type
           ::m/invalid-input
           (str "-- What went wrong --\n"
                (or (format-input-errors input args)
                    (str "  Args " (pr-str (vec args)) " do not match input schema"))
                "\n\n"
                "-- Expected input --\n"
                "  " (expand-schema (m/schema input)) "\n")

           ::m/invalid-output
           (str "-- What went wrong --\n"
                "  Return value does not match output schema\n"
                "  Got: " (truncate (pr-str value) 200) "\n"
                (when-let [details (format-output-errors output value)]
                  (str "\n-- Errors --\n" details "\n"))
                "\n-- Expected output --\n"
                "  " (expand-schema (m/schema output)) "\n")

           ::m/invalid-arity
           (str "-- What went wrong --\n"
                "  Called with " (:arity data) " args\n"
                "  Accepts: " (format-arities (:arities data)) "\n")

           (str "-- What went wrong --\n  " type "\n"))
         (when example
           (str "\n-- Example call --\n"
                "  (" fn-name " " (pr-str example) ")\n"))
         (when (:doc ctx)
           (str "\n-- Docstring --\n"
                "  " (:doc ctx) "\n")))))

;;; ---------------------------------------------------------------------------
;;; Reporter
;;; ---------------------------------------------------------------------------

(defn agent-reporter
  "Custom reporter for malli instrumentation that throws ExceptionInfo
   with a rich, agent-friendly error message."
  [type data]
  (let [message (format-error type data)]
    (throw (ex-info message (assoc data :type type)))))

;;; ---------------------------------------------------------------------------
;;; Lifecycle
;;; ---------------------------------------------------------------------------

(defn start!
  "Collect function schemas from all loaded namespaces and instrument them.
   Per-namespace error catching ensures one broken schema doesn't block all.
   Returns map with instrumentation counts."
  [_opts]
  (let [errors (atom [])
        nses (all-ns)]
    ;; Collect per-namespace with error catching
    (doseq [ns nses]
      (try
        (mi/collect! {:ns ns})
        (catch Throwable e
          (swap! errors conj {:ns (str ns) :error (.getMessage e)})
          (log/debug "Skipping schema collection for namespace"
                     {:ns (str ns) :error (.getMessage e)}))))
    ;; Instrument all collected schemas
    (let [instrumented (count (mi/instrument! {:report agent-reporter}))
          error-count (count @errors)]
      (log/info "Malli instrumentation started"
                {:instrumented instrumented :errors error-count})
      (when (pos? error-count)
        (log/debug "Namespaces with schema collection errors" {:errors @errors}))
      {:instrumented instrumented :errors error-count})))

(defn stop!
  "Remove instrumentation from all functions."
  []
  (let [unstrumented (count (mi/unstrument!))]
    (log/info "Malli instrumentation stopped" {:unstrumented unstrumented})
    {:unstrumented unstrumented}))

(defn refresh!
  "Re-collect and re-instrument after code reload."
  []
  (mi/unstrument!)
  (start! {}))
