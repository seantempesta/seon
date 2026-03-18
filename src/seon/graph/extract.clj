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
            [malli.core :as m]
            [seon.graph.scanner :as scanner]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

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
;;; Shape Walker — Recursive Schema to Shape+Entry Entities
;;; ---------------------------------------------------------------------------

(defn- resolve-schema
  "Resolve a Malli schema, derefing through keyword refs.
   Returns [resolved-schema ref-key] where ref-key is the keyword if it was a ref.
   Returns nil if resolution fails."
  [s]
  (try
    (if (m/-ref-schema? s)
      (let [form (m/form s)
            ref-key (when (keyword? form) form)]
        [(m/deref s) ref-key])
      [s nil])
    (catch Exception _
      nil)
    (catch StackOverflowError _
      nil)))

(defn- classify-value-type
  "Classify a resolved Malli schema into a value-type keyword.
   Returns the type keyword (:string, :int, :map, :vector, :enum, etc.)."
  [s]
  (let [t (m/type s)]
    (case t
      (:string :int :double :float :boolean :keyword :symbol :uuid :inst) t
      :enum :enum
      :fn :fn
      :or :or
      :and :and
      :map :map
      :vector :vector
      :set :set
      :seon.db/ref :seon.db/ref
      :seon.flow/dynamic :seon.flow/dynamic
      ;; For ref schemas that resolved to a known type
      :malli.core/schema (let [resolved (resolve-schema s)]
                           (if resolved
                             (classify-value-type (first resolved))
                             :unknown))
      ;; Default: use the type keyword
      (if (keyword? t) t :unknown))))

(defn- shape-id-for-spec
  "Generate a shape ID from a spec keyword."
  [spec-key]
  (str "shape:" (namespace spec-key) "/" (name spec-key)))

(defn- shape-id-for-entries
  "Generate a stable shape ID from a set of entry descriptors.
   Used for inline schemas that don't have a spec-key."
  [entry-descriptors]
  (let [normalized (->> entry-descriptors
                        (sort-by :key)
                        (mapv (fn [{:keys [key optional value-type]}]
                                (str key ":" value-type ":" optional))))]
    (str "shape:inline/" (hash normalized))))

(defn- walk-schema
  "Recursively walk a Malli schema, producing shape + entry entities.
   Returns {:shapes [...] :entries [...]} with all entities for this tree.

   Arguments:
   - schema-obj: resolved Malli Schema object (must be :map type)
   - spec-key: keyword if from register!, nil if inline
   - namespace-str: owning namespace
   - visited: set of already-visited spec keys (cycle prevention)"
  [schema-obj spec-key namespace-str visited]
  (let [children (m/children schema-obj)
        acc (atom {:shapes [] :entries []})
        entry-descriptors (atom [])
        entry-refs (atom [])]
    (doseq [[k props child-schema] children]
      (when (and (keyword? k) (namespace k))
        (let [;; Resolve through refs
              [resolved ref-key] (or (resolve-schema child-schema) [child-schema nil])
              ;; Check for collection wrapping
              resolved-type (when resolved (m/type resolved))
              collection (when (#{:vector :set} resolved-type) resolved-type)
              ;; If collection, get inner type
              inner (if collection
                      (let [[inner-resolved inner-ref] (or (resolve-schema (first (m/children resolved)))
                                                           [(first (m/children resolved)) nil])]
                        {:schema inner-resolved :ref-key inner-ref})
                      {:schema resolved :ref-key ref-key})
              inner-schema (:schema inner)
              inner-ref-key (:ref-key inner)
              ;; Classify the actual value type
              vtype (if inner-schema (classify-value-type inner-schema) :unknown)
              ;; Detect injectable (:default/fn on entry props)
              injectable (boolean (:default/fn props))
              optional (boolean (:optional props))
              ;; Build entry descriptor for ID computation
              descriptor {:key k :optional optional :value-type vtype}]
          (swap! entry-descriptors conj descriptor)
          ;; If the value is a :map, recursively create shape
          (let [nested-shape-ref
                (when (= :map vtype)
                  (let [;; Determine the nested spec-key (if inner was a ref)
                        nested-spec-key (or inner-ref-key
                                            (when (and inner-schema (m/-ref-schema? child-schema))
                                              (let [f (m/form child-schema)]
                                                (when (keyword? f) f))))
                        ;; Cycle check: skip if we've already visited this spec
                        cycle? (and nested-spec-key (contains? visited nested-spec-key))]
                    (when-not cycle?
                      (let [nested-visited (if nested-spec-key
                                             (conj visited nested-spec-key)
                                             visited)
                            nested-result (walk-schema inner-schema nested-spec-key
                                                       namespace-str nested-visited)
                            nested-shape (first (:shapes nested-result))]
                        ;; Accumulate nested shapes and entries
                        (swap! acc update :shapes into (:shapes nested-result))
                        (swap! acc update :entries into (:entries nested-result))
                        ;; Return the shape ID for ref linking
                        (when nested-shape
                          (:seon.shape/id nested-shape))))))]
            ;; Compute entry ID based on parent shape + key
            ;; We'll fix the ID once we know the shape ID
            (swap! entry-refs conj
                   (cond-> {:seon.entry/key k
                            :seon.entry/optional optional
                            :seon.entry/injectable injectable
                            :seon.entry/value-type vtype}
                     nested-shape-ref (assoc :seon.entry/value-shape
                                             [:seon.shape/id nested-shape-ref])
                     collection (assoc :seon.entry/collection collection)))))))
    ;; Compute shape ID
    (let [shape-id (if spec-key
                     (shape-id-for-spec spec-key)
                     (shape-id-for-entries @entry-descriptors))
          ;; Finalize entry IDs
          entries (mapv (fn [entry]
                          (assoc entry :seon.entry/id
                                 (str shape-id "|" (namespace (:seon.entry/key entry))
                                      "/" (name (:seon.entry/key entry)))))
                        @entry-refs)
          ;; Build shape entity
          shape (cond-> {:seon.shape/id shape-id
                         :seon.shape/namespace namespace-str
                         :seon.shape/entries (mapv (fn [e]
                                                     [:seon.entry/id (:seon.entry/id e)])
                                                   entries)}
                  spec-key (assoc :seon.shape/spec-key spec-key))]
      ;; Add this shape + its entries to accumulator
      (swap! acc update :shapes #(into [shape] %))
      (swap! acc update :entries into entries)
      @acc)))

(defn- walk-registered-schema
  "Walk a single registered schema by keyword.
   Returns {:shapes [...] :entries [...]} or nil on failure."
  [spec-key namespace-str visited]
  (try
    (let [s (m/schema spec-key)
          [resolved _] (or (resolve-schema s) [s nil])]
      (when (= :map (m/type resolved))
        (walk-schema resolved spec-key namespace-str (conj visited spec-key))))
    (catch Exception e
      (log/debug "Skipping unresolvable schema" {:key spec-key :error (.getMessage e)})
      nil)
    (catch StackOverflowError _
      (log/debug "Skipping self-referential schema" {:key spec-key})
      nil)))

(defn- walk-inline-schema
  "Walk an inline :malli/schema form to extract input and output shapes.
   Returns {:input-shape-id str-or-nil :output-shape-id str-or-nil
            :shapes [...] :entries [...]}"
  [schema-form namespace-str visited]
  (let [;; Parse [:=> [:cat input-spec ...] output-spec]
        arrow-form (cond
                     (and (vector? schema-form)
                          (= :=> (first schema-form)))
                     schema-form

                     (and (vector? schema-form)
                          (= :function (first schema-form)))
                     (some #(when (and (vector? %) (= :=> (first %))) %)
                           (rest schema-form))

                     :else nil)
        acc {:shapes [] :entries [] :input-shape-id nil :output-shape-id nil}]
    (if-not arrow-form
      acc
      (let [input-form (second arrow-form)
            output-form (nth arrow-form 2 nil)
            ;; Process input: [:cat input-schema ...]
            input-schema (when (and (vector? input-form)
                                    (= :cat (first input-form)))
                           (second input-form))
            ;; Walk input
            input-result (when input-schema
                           (try
                             (let [s (m/schema input-schema)
                                   [resolved ref-key] (or (resolve-schema s) [s nil])]
                               (when (= :map (m/type resolved))
                                 (let [spec-key (or ref-key
                                                    (when (keyword? input-schema) input-schema))]
                                   (when-not (contains? visited spec-key)
                                     (walk-schema resolved spec-key namespace-str
                                                  (if spec-key (conj visited spec-key) visited))))))
                             (catch Exception e
                               (log/debug "Failed to walk input schema" {:form input-schema :error (.getMessage e)})
                               nil)))
            ;; Walk output
            output-result (when output-form
                            (try
                              (let [s (m/schema output-form)
                                    [resolved ref-key] (or (resolve-schema s) [s nil])]
                                (when (= :map (m/type resolved))
                                  (let [spec-key (or ref-key
                                                     (when (keyword? output-form) output-form))]
                                    (when-not (contains? visited spec-key)
                                      (walk-schema resolved spec-key namespace-str
                                                   (if spec-key (conj visited spec-key) visited))))))
                              (catch Exception e
                                (log/debug "Failed to walk output schema" {:form output-form :error (.getMessage e)})
                                nil)))]
        {:shapes (into (or (:shapes input-result) [])
                       (or (:shapes output-result) []))
         :entries (into (or (:entries input-result) [])
                        (or (:entries output-result) []))
         :input-shape-id (when input-result
                           (:seon.shape/id (first (:shapes input-result))))
         :output-shape-id (when output-result
                            (:seon.shape/id (first (:shapes output-result))))}))))

(defn- extract-shapes
  "Walk all registered specs and fn-schemas to produce shape + entry entities.
   Returns {:shapes [...] :entries [...] :fn-shape-links {qn {:input id :output id}}}."
  [specs fn-schemas namespace-str]
  (let [all-shapes (atom [])
        all-entries (atom [])
        seen-shape-ids (atom #{})
        fn-shape-links (atom {})
        visited #{}
        ;; 1. Walk named specs (from register! calls)
        _ (doseq [spec specs]
            (let [spec-key (:seon.spec/key spec)
                  base-type (:seon.spec/base-type spec)]
              (when (= :map base-type)
                (when-let [result (walk-registered-schema spec-key namespace-str visited)]
                  (doseq [shape (:shapes result)]
                    (when-not (@seen-shape-ids (:seon.shape/id shape))
                      (swap! seen-shape-ids conj (:seon.shape/id shape))
                      (swap! all-shapes conj shape)))
                  (doseq [entry (:entries result)]
                    (when-not (@seen-shape-ids (:seon.entry/id entry))
                      (swap! seen-shape-ids conj (:seon.entry/id entry))
                      (swap! all-entries conj entry)))))))
        ;; 2. Walk fn-schemas (inline :malli/schema metadata)
        _ (doseq [[qn schema-form] fn-schemas]
            (let [result (walk-inline-schema schema-form namespace-str visited)]
              ;; Add shapes and entries (dedup by ID)
              (doseq [shape (:shapes result)]
                (when-not (@seen-shape-ids (:seon.shape/id shape))
                  (swap! seen-shape-ids conj (:seon.shape/id shape))
                  (swap! all-shapes conj shape)))
              (doseq [entry (:entries result)]
                (when-not (@seen-shape-ids (:seon.entry/id entry))
                  (swap! seen-shape-ids conj (:seon.entry/id entry))
                  (swap! all-entries conj entry)))
              ;; Record fn -> shape links
              (when (or (:input-shape-id result) (:output-shape-id result))
                (swap! fn-shape-links assoc qn
                       (cond-> {}
                         (:input-shape-id result)
                         (assoc :input (:input-shape-id result))
                         (:output-shape-id result)
                         (assoc :output (:output-shape-id result)))))))]
    {:shapes @all-shapes
     :entries @all-entries
     :fn-shape-links @fn-shape-links}))

(defn- link-fns-to-shapes
  "Add :seon.fn/input-shape and :seon.fn/output-shape refs to function entities."
  [fns fn-shape-links]
  (let [now (java.util.Date.)]
    (mapv (fn [fn-entity]
            (let [qn (:seon.fn/qualified-name fn-entity)
                  links (get fn-shape-links qn)]
              (cond-> fn-entity
                (:input links)
                (assoc :seon.fn/input-shape [:seon.shape/id (:input links)])
                (:output links)
                (assoc :seon.fn/output-shape [:seon.shape/id (:output links)])
                links
                (assoc :seon.fn/updated-at now))))
          fns)))

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
     ::functions  - Function entities (with spec + shape links)
     ::specs      - Spec entities (with cross-refs)
     ::vars       - Var entities
     ::call-edges - Call graph edges
     ::ns-deps    - Namespace dependency edges
     ::shapes     - Shape entities (recursive schema index)
     ::entries    - Entry entities (keys within shapes)"
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
                   (:seon.spec/namespace (first specs)))

        ;; 5. Extract shapes from registered specs + fn-schemas
        shape-data (when ns-str
                     (extract-shapes specs fn-schemas ns-str))

        ;; 6. Link fns to shapes
        shape-linked-fns (if shape-data
                           (link-fns-to-shapes linked-fns (:fn-shape-links shape-data))
                           linked-fns)]
    {::ns-name ns-str
     ::namespaces ns-entities
     ::functions shape-linked-fns
     ::specs specs
     ::vars vars
     ::call-edges call-edges
     ::ns-deps ns-deps
     ::shapes (or (:shapes shape-data) [])
     ::entries (or (:entries shape-data) [])}))

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
