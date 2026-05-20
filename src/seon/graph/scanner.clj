(ns seon.graph.scanner
  "Static source scanner for spec/schema and var extraction.
   Parses schema/register! calls and def forms from Clojure source using edamame.

   Walks parsed forms looking for `(schema/register! <key> <schema>)` patterns
   and produces `:seon.spec/*` entities for Datahike ingestion. Also extracts
   `(def ...)` forms as `:seon.var/*` entities with value-type inference.

   Function extraction (defn) and fn-to-spec linking are handled by
   seon.graph.extract, which uses clj-kondo for authoritative function data.

   Usage:
     (require '[seon.graph.scanner :as scanner])

     ;; Scan a single file
     (scanner/scan-file {::file-path \"src/seon/flow/pool.clj\"})
     ;; => [{:seon.spec/key :seon.flow.pool/port
     ;;       :seon.spec/namespace \"seon.flow.pool\"
     ;;       :seon.spec/definition \"[:int {:min 7900, :max 7999, ...}]\"
     ;;       :seon.spec/base-type :int
     ;;       :seon.spec/updated-at #inst \"...\"}]

     ;; Scan entire directory
     (scanner/scan-directory {::dir-path \"src/\"})
     ;; => vector of all spec + var entities"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [edamame.core :as e]
            [seon.schema :as schema])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::file-path
                  [:string {:min 1 :description "Path to a Clojure source file"}])

(schema/register! ::source
                  [:string {:min 1 :description "Clojure source code string to scan"}])

(schema/register! ::dir-path
                  [:string {:min 1 :description "Directory path to scan recursively"}])

(schema/register! ::specs
                  [:vector [:map
                            [:seon.spec/key :keyword]
                            [:seon.spec/namespace :string]
                            [:seon.spec/definition :string]
                            [:seon.spec/base-type :keyword]
                            [:seon.spec/updated-at inst?]]])

;;; ---------------------------------------------------------------------------
;;; Private Implementation
;;; ---------------------------------------------------------------------------

(defn- extract-ns-name
  "Extract namespace name from the first (ns ...) form in parsed forms."
  [forms]
  (some (fn [form]
          (when (and (list? form)
                     (= 'ns (first form))
                     (symbol? (second form)))
            (str (second form))))
        forms))

(defn- extract-aliases
  "Extract require aliases from the (ns ...) form.
   Returns map like {'schema 'seon.schema}."
  [forms]
  (let [ns-form (some (fn [form]
                        (when (and (list? form) (= 'ns (first form)))
                          form))
                      forms)]
    (when ns-form
      (let [require-form (some (fn [clause]
                                 (when (and (sequential? clause)
                                            (= :require (first clause)))
                                   clause))
                               (drop 2 ns-form))]
        (when require-form
          (into {}
                (keep (fn [spec]
                        (when (and (vector? spec) (>= (count spec) 3))
                          (let [pairs (partition 2 (rest spec))]
                            (some (fn [[k v]]
                                    (when (= :as k)
                                      [(symbol (name v)) (first spec)]))
                                  pairs)))))
                (rest require-form)))))))

(defn- build-auto-resolve
  "Build edamame auto-resolve function from ns name and aliases."
  [ns-name aliases]
  (fn [alias]
    (if (= alias :current)
      (symbol ns-name)
      (if-let [resolved (get aliases alias)]
        (symbol (name resolved))
        (symbol (name alias))))))

(defn- register-call?
  "Returns true if form is a (schema/register! ...) or (seon.schema/register! ...) call."
  [form]
  (and (list? form)
       (symbol? (first form))
       (let [sym-name (name (first form))
             sym-ns (namespace (first form))]
         (and (= "register!" sym-name)
              (or (= "schema" sym-ns)
                  (= "seon.schema" sym-ns))))))

(defn extract-base-type
  "Extract the base type from a schema form.
   [:map ...] -> :map, [:vector ...] -> :vector, :string -> :string, etc."
  [schema-form]
  (cond
    (keyword? schema-form) schema-form
    (and (vector? schema-form) (keyword? (first schema-form))) (first schema-form)
    (and (vector? schema-form) (= :fn (first schema-form))) :fn
    :else :unknown))

(defn extract-contains-keys
  "For :map schemas, extract qualified keyword keys.
   [:map [::a ...] [::b ...]] -> [:my.ns/a :my.ns/b]
   Only includes qualified keywords."
  [schema-form]
  (when (and (vector? schema-form)
             (= :map (first schema-form)))
    (let [entries (rest schema-form)
          ;; Skip props map if present
          entries (if (and (seq entries) (map? (first entries)))
                    (rest entries)
                    entries)]
      (into []
            (comp
             (filter vector?)
             (map first)
             (filter keyword?)
             (filter namespace))
            entries))))

(defn extract-optional-keys
  "For :map schemas, extract qualified keyword keys marked {:optional true}.
   [:map [::a ::a] [::b {:optional true} ::b]] -> [:ns/b]
   Only includes qualified keywords with :optional true in props."
  [schema-form]
  (when (and (vector? schema-form)
             (= :map (first schema-form)))
    (let [entries (rest schema-form)
          entries (if (and (seq entries) (map? (first entries)))
                    (rest entries)
                    entries)]
      (into []
            (comp
             (filter vector?)
             (filter (fn [entry]
                       (and (>= (count entry) 3)
                            (map? (second entry))
                            (:optional (second entry)))))
             (map first)
             (filter keyword?)
             (filter namespace))
            entries))))

(defn- find-register-calls
  "Walk all parsed forms and collect register! call data.
   Returns vector of [key schema-form] pairs."
  [forms]
  (let [results (atom [])]
    (walk/postwalk
     (fn [form]
       (when (register-call? form)
         (let [args (rest form)]
           (when (and (>= (count args) 2)
                      (keyword? (first args)))
             (swap! results conj [(first args) (second args)]))))
       form)
     forms)
    @results))

(defn- def-form?
  "Returns true if form is a (def ...) form but NOT a (defn ...) or (defn- ...) form."
  [form]
  (and (list? form)
       (symbol? (first form))
       (= 'def (first form))))

(defn- infer-value-type
  "Infer the type of a def's value form.
   Returns a keyword classifying the value."
  [value-form]
  (cond
    (vector? value-form)  :vector
    (map? value-form)     :map
    (set? value-form)     :set
    (string? value-form)  :string
    (number? value-form)  :number
    (keyword? value-form) :keyword
    (boolean? value-form) :boolean
    (list? value-form)    :expr
    :else                 :unknown))

(defn- extract-def
  "Extract var entity from a (def ...) form.
   Returns map with :seon.var/* keys, or nil if form is malformed."
  [form ns-str now]
  (let [sym (second form)
        rest-forms (drop 2 form)
        [doc-str value-form] (if (and (string? (first rest-forms))
                                      (> (count rest-forms) 1))
                               [(first rest-forms) (second rest-forms)]
                               [nil (first rest-forms)])]
    (when (symbol? sym)
      (cond-> {:seon.var/qualified-name (str ns-str "/" sym)
               :seon.var/namespace ns-str
               :seon.var/name (str sym)
               :seon.var/private (boolean (:private (meta sym)))
               :seon.var/value-type (infer-value-type value-form)
               :seon.var/updated-at now}
        doc-str (assoc :seon.var/doc doc-str)))))

(defn- find-def-forms
  "Walk all parsed forms and collect (def ...) var entities.
   Excludes defn/defn- forms (those are handled by clj-kondo in extract.clj)."
  [forms ns-str now]
  (let [results (atom [])]
    (walk/postwalk
     (fn [form]
       (when (def-form? form)
         (when-let [entity (extract-def form ns-str now)]
           (swap! results conj entity)))
       form)
     forms)
    @results))

(defn- defn-form?
  "Returns true if form is a (defn ...) or (defn- ...) form."
  [form]
  (and (list? form)
       (symbol? (first form))
       (#{'defn 'defn-} (first form))))

(defn- extract-defn-malli-schema
  "Extract :malli/schema metadata from a defn form.
   Returns [qualified-name schema-form] or nil.

   Handles the defn attr-map position:
     (defn name docstring? attr-map? [args] body)"
  [form ns-str]
  (when (defn-form? form)
    (let [parts (rest form)
          sym (first parts)
          parts (rest parts)
          ;; Skip docstring if present
          parts (if (string? (first parts)) (rest parts) parts)
          ;; Check for attr-map
          attr-map (when (map? (first parts)) (first parts))
          schema (get attr-map :malli/schema)]
      (when schema
        [(str ns-str "/" sym) schema]))))

(defn- find-fn-schemas
  "Walk all parsed forms and collect :malli/schema metadata from defn forms.
   Returns map of qualified-name -> schema-form."
  [forms ns-str]
  (let [results (atom {})]
    (walk/postwalk
     (fn [form]
       (when-let [[qn schema] (extract-defn-malli-schema form ns-str)]
         (swap! results assoc qn schema))
       form)
     forms)
    @results))

(defn- parse-source
  "Parse Clojure source string with edamame, handling reader macros.
   Does a two-pass approach: first pass extracts ns info for auto-resolve,
   second pass parses with proper keyword resolution."
  [source-str]
  (let [;; First pass: parse with default auto-resolve to get ns info
        default-opts {:all true
                      :auto-resolve (fn [alias]
                                      (if (= alias :current)
                                        'unknown
                                        (symbol (name alias))))
                      :readers (fn [_tag] identity)
                      :regex #(re-pattern (str %))}
        first-pass (try (e/parse-string-all source-str default-opts)
                        (catch Exception _ nil))]
    (when first-pass
      (let [ns-str (extract-ns-name first-pass)
            aliases (extract-aliases first-pass)]
        (when ns-str
          (let [auto-resolve (build-auto-resolve ns-str (or aliases {}))
                opts {:all true
                      :auto-resolve auto-resolve
                      :readers (fn [_tag] identity)
                      :regex #(re-pattern (str %))}]
            (try
              {:ns-str ns-str
               :forms (e/parse-string-all source-str opts)}
              (catch Exception _
                ;; If second pass fails, use first pass results
                {:ns-str ns-str
                 :forms first-pass}))))))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn scan-source
  "Scan a Clojure source string for schema/register! calls and def forms.
   Returns specs, vars, and ns markers. Function extraction is handled by
   seon.graph.extract using clj-kondo.

   Request keys:
     ::source - Clojure source code string

   Returns vector of spec entity maps, var entity maps, and ns marker maps.

   Example:
     (scan-source {::source (slurp \"src/seon/flow/pool.clj\")})"
  [{::keys [source]}]
  (let [parsed (parse-source source)]
    (if-not parsed
      []
      (let [{:keys [ns-str forms]} parsed
            register-calls (find-register-calls forms)
            now (java.util.Date.)
            has-ctx-spec? (some (fn [[spec-key _]]
                                  (str/ends-with? (name spec-key) "*ctx*"))
                                register-calls)
            spec-entities (mapv (fn [[spec-key schema-form]]
                                  (let [contains-keys (extract-contains-keys schema-form)
                                        opt-keys (extract-optional-keys schema-form)]
                                    (cond-> {:seon.spec/key spec-key
                                             :seon.spec/namespace ns-str
                                             :seon.spec/definition (pr-str schema-form)
                                             :seon.spec/base-type (extract-base-type schema-form)
                                             :seon.spec/updated-at now}
                                      (seq contains-keys)
                                      (assoc :seon.spec/contains-keys contains-keys)
                                      (seq opt-keys)
                                      (assoc :seon.spec/optional-keys opt-keys))))
                                register-calls)
            var-entities (find-def-forms forms ns-str now)]
        (cond-> (into spec-entities var-entities)
          has-ctx-spec?
          (conj {:seon.ns/name ns-str
                 :seon.ns/dynamic? true}))))))

(defn scan-fn-schemas
  "Scan source for :malli/schema metadata on defn forms.
   Returns map of qualified-fn-name -> schema-form.

   Request keys:
     ::source - Clojure source code string

   Example:
     (scan-fn-schemas {::source (slurp \"src/seon/ctx.clj\")})
     ;; => {\"seon.ctx/get-ctx\" [:=> [:cat :seon.ctx/get-ctx-request] :seon.ctx/get-ctx-response] ...}"
  [{::keys [source]}]
  (let [parsed (parse-source source)]
    (if-not parsed
      {}
      (find-fn-schemas (:forms parsed) (:ns-str parsed)))))

(defn scan-file
  "Scan a Clojure source file for schema/register! calls and def forms.

   Parses the file with edamame, resolves auto-namespaced keywords using
   the file's ns declaration, and extracts spec and var entities.

   Request keys:
     ::file-path - Path to a .clj, .cljs, or .cljc file

   Returns vector of spec and var entity maps.

   Example:
     (scan-file {::file-path \"src/seon/flow/pool.clj\"})"
  [{::keys [file-path]}]
  (let [file (io/file file-path)]
    (if-not (.exists file)
      []
      (scan-source {::source (slurp file)}))))

(defn scan-directory
  "Scan all Clojure source files in a directory for schema/register! calls.

   Recursively finds .clj, .cljs, .cljc files and extracts spec and var
   entities from each.

   Request keys:
     ::dir-path - Directory to scan recursively

   Returns vector of all spec and var entities found.

   Example:
     (scan-directory {::dir-path \"src/\"})"
  [{::keys [dir-path]}]
  (let [dir (io/file dir-path)]
    (if-not (.isDirectory dir)
      []
      (->> (file-seq dir)
           (filter (fn [^File f]
                     (and (.isFile f)
                          (let [fn-name (.getName f)]
                            (or (str/ends-with? fn-name ".clj")
                                (str/ends-with? fn-name ".cljs")
                                (str/ends-with? fn-name ".cljc"))))))
           (mapcat (fn [^File f]
                     (try
                       (scan-file {::file-path (.getAbsolutePath f)})
                       (catch Exception _
                         []))))
           vec))))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Scan a single file
  (scan-file {::file-path "src/seon/flow/pool.clj"})

  ;; Scan the graph directory
  (scan-directory {::dir-path "src/seon/graph/"})

  ;; Scan entire project
  (def all-specs (scan-directory {::dir-path "src/"}))
  (count all-specs)
  (->> all-specs (map :seon.spec/base-type) frequencies)

  nil)
