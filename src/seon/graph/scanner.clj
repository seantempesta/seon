(ns seon.graph.scanner
  "Static source scanner for spec/schema extraction.
   Parses schema/register! calls from Clojure source using edamame.

   Walks parsed forms looking for `(schema/register! <key> <schema>)` patterns
   and produces `:seon.spec/*` entities for Datalevin ingestion.

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
     ;; => vector of all spec entities"
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
   [:map [::exercise ...] [::sets ...]] -> [:seon.health.workout/exercise ...]
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
      (let [ns-name (extract-ns-name first-pass)
            aliases (extract-aliases first-pass)]
        (when ns-name
          (let [auto-resolve (build-auto-resolve ns-name (or aliases {}))
                opts {:all true
                      :auto-resolve auto-resolve
                      :readers (fn [_tag] identity)
                      :regex #(re-pattern (str %))}]
            (try
              {:ns-name ns-name
               :forms (e/parse-string-all source-str opts)}
              (catch Exception _
                ;; If second pass fails, use first pass results
                {:ns-name ns-name
                 :forms first-pass}))))))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn scan-file
  "Scan a Clojure source file for schema/register! calls.

   Parses the file with edamame, resolves auto-namespaced keywords using
   the file's ns declaration, and extracts spec entities.

   Request keys:
     ::file-path - Path to a .clj, .cljs, or .cljc file

   Returns vector of spec entity maps.

   Example:
     (scan-file {::file-path \"src/seon/flow/pool.clj\"})"
  [{::keys [file-path]}]
  (let [file (io/file file-path)]
    (if-not (.exists file)
      []
      (let [source (slurp file)
            parsed (parse-source source)]
        (if-not parsed
          []
          (let [{:keys [ns-name forms]} parsed
                register-calls (find-register-calls forms)
                now (java.util.Date.)]
            (mapv (fn [[spec-key schema-form]]
                    (let [contains-keys (extract-contains-keys schema-form)]
                      (cond-> {:seon.spec/key spec-key
                               :seon.spec/namespace ns-name
                               :seon.spec/definition (pr-str schema-form)
                               :seon.spec/base-type (extract-base-type schema-form)
                               :seon.spec/updated-at now}
                        (seq contains-keys)
                        (assoc :seon.spec/contains-keys contains-keys))))
                  register-calls)))))))

(defn scan-directory
  "Scan all Clojure source files in a directory for schema/register! calls.

   Recursively finds .clj, .cljs, .cljc files and extracts spec entities
   from each.

   Request keys:
     ::dir-path - Directory to scan recursively

   Returns vector of all spec entities found.

   Example:
     (scan-directory {::dir-path \"src/\"})"
  [{::keys [dir-path]}]
  (let [dir (io/file dir-path)]
    (if-not (.isDirectory dir)
      []
      (->> (file-seq dir)
           (filter (fn [^File f]
                     (and (.isFile f)
                          (let [name (.getName f)]
                            (or (str/ends-with? name ".clj")
                                (str/ends-with? name ".cljs")
                                (str/ends-with? name ".cljc"))))))
           (mapcat (fn [^File f]
                     (try
                       (scan-file {::file-path (.getAbsolutePath f)})
                       (catch Exception _
                         []))))
           vec))))

;;; ---------------------------------------------------------------------------
;;; Function-to-Spec Linking
;;; ---------------------------------------------------------------------------

(defn link-fns-to-specs
  "Link function entities to their input/output spec entities by naming convention.

   For a function `seon.foo/bar`, looks for:
   - Input spec:  `:seon.foo/bar-request`
   - Output spec: `:seon.foo/bar-response`

   If the output spec contains `:seon.render/html` or `:seon.render/ai` in its
   `:seon.spec/contains-keys`, the function is a render function and gets
   `:seon.fn/render-input-keys` populated from the input spec's contains-keys.

   Arguments:
     fns   - Vector of fn entity maps (with :seon.fn/qualified-name)
     specs - Vector of spec entity maps (with :seon.spec/key)

   Returns vector of fn entity maps with added keys:
     :seon.fn/input-spec        - Lookup ref [:seon.spec/key ...] when matched
     :seon.fn/output-spec       - Lookup ref [:seon.spec/key ...] when matched
     :seon.fn/render-input-keys - Vector of keywords from input spec (render fns only)
     :seon.fn/updated-at        - Current timestamp"
  [fns specs]
  (let [spec-by-key (into {} (map (juxt :seon.spec/key identity)) specs)
        now (java.util.Date.)
        render-keys #{:seon.render/html :seon.render/ai}]
    (mapv (fn [fn-entity]
            (let [qn (:seon.fn/qualified-name fn-entity)
                  ;; Derive expected spec keys from qualified name
                  input-key (keyword (str qn "-request"))
                  output-key (keyword (str qn "-response"))
                  input-spec (get spec-by-key input-key)
                  output-spec (get spec-by-key output-key)
                  ;; Check if output spec contains render keys
                  output-contains (set (:seon.spec/contains-keys output-spec))
                  is-render? (and output-spec
                                  (some render-keys output-contains))
                  input-contains (:seon.spec/contains-keys input-spec)]
              (cond-> (assoc fn-entity :seon.fn/updated-at now)
                input-spec
                (assoc :seon.fn/input-spec [:seon.spec/key input-key])

                output-spec
                (assoc :seon.fn/output-spec [:seon.spec/key output-key])

                (and is-render? (seq input-contains))
                (assoc :seon.fn/render-input-keys (vec input-contains)))))
          fns)))

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
