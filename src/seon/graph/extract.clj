(ns seon.graph.extract
  "Unified code graph extraction pipeline.

   Single entry point that runs both edamame (for schemas/defs) and clj-kondo
   (for fns/calls/deps), merges results, and links specs to functions.

   Usage:
     (require '[seon.graph.extract :as extract])

     ;; Extract from source string
     (extract/extract-graph {::source (slurp \"src/seon/foo.clj\")
                             ::file-path \"src/seon/foo.clj\"})

     ;; Extract from file
     (extract/extract-graph-from-file {::file-path \"src/seon/foo.clj\"})"
  (:require [clj-kondo.core :as clj-kondo]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [seon.graph.scanner :as scanner]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::source
                  [:string {:min 1 :description "Clojure source code string"}])

(schema/register! ::file-path
                  [:string {:min 1 :description "File path for clj-kondo context"}])

(schema/register! ::ns-name
                  [:string {:description "Primary namespace name extracted"}])

;;; ---------------------------------------------------------------------------
;;; clj-kondo Analysis
;;; ---------------------------------------------------------------------------

(def ^:private kondo-config
  "clj-kondo config for graph extraction. No cache needed — alias resolution
   works from the ns form alone."
  {:output {:analysis {:var-definitions {:meta true}
                       :var-usages true
                       :namespace-usages true
                       :namespace-definitions true
                       :keywords true}
            :format :edn}
   :cache false})

(defn- run-kondo
  "Run clj-kondo on source string, return raw analysis map."
  [source file-path]
  (let [file-path (or file-path "<stdin>")
        result (with-in-str source
                 (clj-kondo/run! {:lint ["-"]
                                  :filename file-path
                                  :config kondo-config}))]
    (:analysis result)))

;;; ---------------------------------------------------------------------------
;;; Entity Extraction from clj-kondo
;;; ---------------------------------------------------------------------------

(defn- kondo-var-def->fn-entity
  "Convert a clj-kondo var-definition (with arglists) to a :seon.fn/* entity."
  [vd now]
  (let [ns-str (str (:ns vd))
        name-str (str (:name vd))]
    (cond-> {:seon.fn/qualified-name (str ns-str "/" name-str)
             :seon.fn/namespace ns-str
             :seon.fn/name name-str
             :seon.fn/private (boolean (:private vd))
             :seon.fn/updated-at now}
      (:arglist-strs vd) (assoc :seon.fn/arglists (pr-str (vec (:arglist-strs vd))))
      (:row vd)          (assoc :seon.fn/row (:row vd))
      (:doc vd)          (assoc :seon.fn/doc (:doc vd)))))

(defn- kondo-var-def->var-entity
  "Convert a clj-kondo var-definition (no arglists, plain def) to a :seon.var/* entity.
   Used as fallback when edamame didn't produce a var entity for this name."
  [vd now]
  (let [ns-str (str (:ns vd))
        name-str (str (:name vd))]
    {:seon.var/qualified-name (str ns-str "/" name-str)
     :seon.var/namespace ns-str
     :seon.var/name name-str
     :seon.var/private (boolean (:private vd))
     :seon.var/value-type :unknown
     :seon.var/updated-at now}))

(defn- extract-fn-entities
  "Extract function entities from clj-kondo var-definitions.
   Functions are those with :arglist-strs or defined-by clojure.core/defn."
  [analysis now]
  (let [var-defs (or (:var-definitions analysis) [])]
    (->> var-defs
         (filter (fn [vd]
                   (or (seq (:arglist-strs vd))
                       (= 'clojure.core/defn (:defined-by vd))
                       (= 'clojure.core/defn- (:defined-by vd)))))
         (mapv #(kondo-var-def->fn-entity % now)))))

(defn- extract-kondo-var-entities
  "Extract plain def var entities from clj-kondo (those without arglists).
   Returns map of qualified-name -> basic var entity."
  [analysis now]
  (let [var-defs (or (:var-definitions analysis) [])]
    (->> var-defs
         (remove (fn [vd]
                   (or (seq (:arglist-strs vd))
                       (= 'clojure.core/defn (:defined-by vd))
                       (= 'clojure.core/defn- (:defined-by vd)))))
         (map #(kondo-var-def->var-entity % now))
         (into {} (map (juxt :seon.var/qualified-name identity))))))

(defn- extract-call-edges
  "Extract call graph edges from clj-kondo var-usages."
  [analysis]
  (->> (or (:var-usages analysis) [])
       (map (fn [vu]
              (let [from-ns (str (:from vu))
                    from-var (when (:from-var vu) (str (:from-var vu)))
                    to-ns (str (:to vu))
                    to-name (str (:name vu))]
                (cond-> {:seon.call/from-fn (if from-var
                                              (str from-ns "/" from-var)
                                              from-ns)
                         :seon.call/to-fn (str to-ns "/" to-name)}
                  (:row vu) (assoc :seon.call/row (:row vu))))))
       vec))

(defn- extract-ns-deps
  "Extract namespace dependency edges from clj-kondo namespace-usages."
  [analysis]
  (->> (or (:namespace-usages analysis) [])
       (map (fn [nu]
              (cond-> {:seon.ns.dep/from-ns (str (:from nu))
                       :seon.ns.dep/to-ns (str (:to nu))}
                (:alias nu) (assoc :seon.ns.dep/alias (str (:alias nu))))))
       distinct
       vec))

(defn- extract-ns-entities
  "Extract namespace entities from clj-kondo namespace-definitions."
  [analysis]
  (->> (or (:namespace-definitions analysis) [])
       (mapv (fn [nd]
               (cond-> {:seon.ns/name (str (:name nd))}
                 (:filename nd) (assoc :seon.ns/file (:filename nd))
                 (:doc nd)      (assoc :seon.ns/doc (:doc nd)))))))

;;; ---------------------------------------------------------------------------
;;; Spec Cross-References
;;; ---------------------------------------------------------------------------

(defn- extract-spec-references
  "Walk a spec's definition form and collect all qualified keywords.
   These are cross-references to other specs."
  [spec-entity]
  (let [definition (:seon.spec/definition spec-entity)
        refs (atom #{})]
    (when definition
      (try
        (let [form (edn/read-string definition)]
          (walk/postwalk
           (fn [x]
             (when (and (keyword? x) (namespace x))
               (swap! refs conj x))
             x)
           form))
        (catch Exception _)))
    (let [found @refs
          ;; Exclude only the spec's own key — contains-keys ARE references
          own-key (:seon.spec/key spec-entity)]
      (vec (remove #(= % own-key) found)))))

(defn- enrich-specs-with-references
  "Add :seon.spec/references to each spec entity."
  [specs]
  (mapv (fn [spec]
          (let [refs (extract-spec-references spec)]
            (if (seq refs)
              (assoc spec :seon.spec/references refs)
              spec)))
        specs))

;;; ---------------------------------------------------------------------------
;;; Function-to-Spec Linking (moved from scanner.clj)
;;; ---------------------------------------------------------------------------

(defn- extract-spec-keys-from-schema
  "Parse a :malli/schema form to extract input and output spec keyword references.
   Returns [input-key output-key] where either may be nil.

   Handles:
     [:=> [:cat ::request] ::response]           -> [::request ::response]
     [:function [:=> [:cat ::req] ::resp] ...]   -> [::req ::resp] (first arity)"
  [schema-form]
  (let [;; Unwrap [:function ...] to get first [:=> ...] form
        arrow-form (cond
                     (and (vector? schema-form)
                          (= :=> (first schema-form)))
                     schema-form

                     (and (vector? schema-form)
                          (= :function (first schema-form)))
                     (some #(when (and (vector? %) (= :=> (first %))) %)
                           (rest schema-form))

                     :else nil)]
    (when arrow-form
      ;; [:=> [:cat input-spec ...] output-spec]
      (let [input-form (second arrow-form)
            output-spec (nth arrow-form 2 nil)
            ;; Extract first keyword from [:cat ...] as input spec
            input-spec (when (and (vector? input-form)
                                  (= :cat (first input-form)))
                         (let [first-arg (second input-form)]
                           (when (keyword? first-arg) first-arg)))]
        [(when (and input-spec (namespace input-spec)) input-spec)
         (when (and (keyword? output-spec) (namespace output-spec)) output-spec)]))))

(defn- link-fns-to-specs
  "Link function entities to their input/output spec entities.

   Primary: Parse :malli/schema metadata from defn forms to extract spec references.
   Fallback: Naming convention (fn `seon.foo/bar` -> specs `::bar-request`/`::bar-response`).

   The graph stores only facts (spec refs). Derived state like 'is this a renderer?'
   is computed at query time by checking if output spec contains :seon.render/html."
  [fns specs fn-schemas]
  (let [spec-by-key (into {} (map (juxt :seon.spec/key identity)) specs)
        now (java.util.Date.)]
    (mapv (fn [fn-entity]
            (let [qn (:seon.fn/qualified-name fn-entity)
                  ;; Primary: extract from :malli/schema metadata
                  schema-form (get fn-schemas qn)
                  [meta-input meta-output] (when schema-form
                                             (extract-spec-keys-from-schema schema-form))
                  ;; Fallback: naming convention
                  conv-input (keyword (str qn "-request"))
                  conv-output (keyword (str qn "-response"))
                  ;; Use metadata keys if they match known specs, else fall back
                  input-key (if (and meta-input (spec-by-key meta-input))
                              meta-input
                              (when (spec-by-key conv-input) conv-input))
                  output-key (if (and meta-output (spec-by-key meta-output))
                               meta-output
                               (when (spec-by-key conv-output) conv-output))]
              (cond-> (assoc fn-entity :seon.fn/updated-at now)
                input-key
                (assoc :seon.fn/input-spec [:seon.spec/key input-key])

                output-key
                (assoc :seon.fn/output-spec [:seon.spec/key output-key]))))
          fns)))

;;; ---------------------------------------------------------------------------
;;; Merge: edamame vars + kondo vars
;;; ---------------------------------------------------------------------------

(defn- merge-var-entities
  "Merge edamame vars (authoritative, has value-type) with kondo vars (fallback).
   Edamame wins when both have an entity for the same qualified name."
  [edamame-vars kondo-var-map]
  (let [edamame-by-qn (into {} (map (juxt :seon.var/qualified-name identity)) edamame-vars)
        ;; Start with edamame vars, then add any kondo-only vars
        merged-map (merge kondo-var-map edamame-by-qn)]
    (vec (vals merged-map))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn extract-graph
  "Extract a complete code graph from source code.
   Runs both edamame (for schemas/defs) and clj-kondo (for fns/calls/deps).
   Merges results and links specs to functions.

   Request keys:
     ::source    - Required. Clojure source string
     ::file-path - Optional. File path for clj-kondo context

   Response keys:
     ::ns-name    - Primary namespace name
     ::namespaces - Namespace entities
     ::functions  - Function entities (with spec links)
     ::specs      - Spec entities (with cross-refs)
     ::vars       - Var entities
     ::call-edges - Call graph edges
     ::ns-deps    - Namespace dependency edges"
  [{::keys [source file-path]}]
  (let [now (java.util.Date.)
        ;; 1. Run edamame scanner → specs + vars + ns markers
        scan-results (scanner/scan-source {::scanner/source source})
        edamame-specs (filterv :seon.spec/key scan-results)
        edamame-vars (filterv :seon.var/qualified-name scan-results)
        edamame-ns-markers (filterv :seon.ns/name scan-results)

        ;; 2. Run clj-kondo → fns, calls, ns deps, ns entities
        analysis (run-kondo source file-path)
        kondo-fns (extract-fn-entities analysis now)
        kondo-var-map (extract-kondo-var-entities analysis now)
        call-edges (extract-call-edges analysis)
        ns-deps (extract-ns-deps analysis)
        kondo-ns-entities (extract-ns-entities analysis)

        ;; 3. Merge
        ;; Namespace: combine kondo ns-entities with edamame ns markers (dynamic? flag)
        ns-entities (let [kondo-by-name (into {} (map (juxt :seon.ns/name identity)) kondo-ns-entities)
                          edamame-by-name (into {} (map (juxt :seon.ns/name identity)) edamame-ns-markers)]
                      (vec (vals (merge-with merge kondo-by-name edamame-by-name))))

        ;; Vars: edamame authoritative (has value-type), kondo as fallback
        vars (merge-var-entities edamame-vars kondo-var-map)

        ;; Specs: enrich with cross-references
        specs (enrich-specs-with-references edamame-specs)

        ;; 3b. Extract :malli/schema metadata from defn forms
        fn-schemas (scanner/scan-fn-schemas {::scanner/source source})

        ;; 4. Link fns to specs (metadata-first, naming convention fallback)
        linked-fns (link-fns-to-specs kondo-fns specs fn-schemas)

        ;; Determine primary ns name
        ns-str (or (:seon.ns/name (first ns-entities))
                   (:seon.fn/namespace (first linked-fns))
                   (:seon.spec/namespace (first specs)))]
    {::ns-name ns-str
     ::namespaces ns-entities
     ::functions linked-fns
     ::specs specs
     ::vars vars
     ::call-edges call-edges
     ::ns-deps ns-deps}))

(defn extract-graph-from-file
  "Convenience: extract graph from a file path.

   Request keys:
     ::file-path - Path to a Clojure source file

   Returns same shape as extract-graph."
  [{::keys [file-path]}]
  (extract-graph {::source (slurp file-path)
                  ::file-path file-path}))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Extract from a real file
  (def g (extract-graph-from-file {::file-path "src/seon/health/workout/render.clj"}))
  (::ns-name g)
  (count (::functions g))
  (count (::specs g))
  (count (::call-edges g))
  (count (::ns-deps g))
  (count (::vars g))

  ;; Check spec linking
  (->> (::functions g)
       (filter :seon.fn/input-spec))

  ;; Check spec references
  (->> (::specs g)
       (filter :seon.spec/references))

  nil)
