(ns seon.fn
  "Build-time indexing of the Clojure program graph without evaluation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.fn.analyzer :as analyzer]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn])
  (:import [java.nio.file Files]
           [java.security MessageDigest]))

(schema.edn/load! {})

(def source-roots
  "The Clojure source roots admitted to the program graph."
  ["src" "test"])

(defn- project-root
  []
  (let [resource (io/resource "seon/fn.clj")]
    (when-not (= "file" (.getProtocol resource))
      (throw
       (ex-info
        "Program indexing requires a source checkout."
        {:seon.error/kind ::index-refused
         ::resource (str resource)})))
    (-> resource
        .toURI
        io/file
        .getParentFile
        .getParentFile
        .getParentFile
        .getCanonicalFile)))

(defn- rooted-file
  [root]
  (let [file (io/file root)]
    (.getCanonicalFile
     (if (.isAbsolute file)
       file
       (io/file (project-root) root)))))

(defn- source-file?
  [file]
  (and (.isFile ^java.io.File file)
       (or (str/ends-with? (.getName ^java.io.File file) ".clj")
           (str/ends-with? (.getName ^java.io.File file) ".cljc"))))

(defn- source-files
  [roots]
  (let [files
        (into []
              (mapcat (fn [root]
                        (->> (file-seq (rooted-file root))
                             (filter source-file?)
                             (sort-by (fn [file]
                                        (.getCanonicalPath
                                         ^java.io.File file))))))
              roots)]
    files))

(defn- many-or-component-attributes
  [attributes]
  (into #{}
        (keep (fn [{:db/keys [ident cardinality isComponent]}]
                (when (or (= :db.cardinality/many cardinality) isComponent)
                  ident)))
        (schema.datahike/malli->datahike-schema (sort attributes))))

(defn- sha-256
  [source-bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") source-bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- file-digest
  [file]
  (sha-256 (Files/readAllBytes (.toPath ^java.io.File file))))

(defn- exact-source [entry]
  (let [text (slurp (::analyzer/filename entry))
        line-starts
        (loop [matcher (re-matcher #"\n" text) starts [0]]
          (if (.find matcher)
            (recur matcher (conj starts (inc (.start matcher))))
            starts))
        row (::analyzer/row entry)
        col (::analyzer/col entry)
        end-row (::analyzer/end-row entry)
        end-col (::analyzer/end-col entry)]
    (when-not (every? some? [row col end-row end-col])
      (throw (ex-info "Static declaration has no exact source span."
                      {:seon.error/kind ::index-refused
                       ::analysis-entry entry})))
    (subs text
          (+ (nth line-starts (dec row)) (dec col))
          (+ (nth line-starts (dec end-row)) (dec end-col)))))

(defn- read-jvm-form [source]
  (binding [*read-eval* false]
    (read {:read-cond :allow :features #{:clj}}
          (java.io.PushbackReader. (java.io.StringReader. source)))))

(defn- import-bindings [spec]
  (cond
    (symbol? spec)
    [[(symbol (name spec)) spec]]

    (and (sequential? spec) (symbol? (first spec)))
    (map (fn [class-name]
           [class-name (symbol (str (first spec) "." class-name))])
         (rest spec))

    :else []))

(defn- namespace-ref
  [namespace-name]
  [:seon.ns/name namespace-name])

(defn- namespace-context [entry]
  (let [form (read-jvm-form (exact-source entry))]
    (reduce
     (fn [context clause]
       (case (first clause)
         :require
         (reduce
          (fn [context spec]
            (if-not (and (vector? spec) (symbol? (first spec)))
              context
              (let [target (first spec)
                    options (apply hash-map (rest spec))
                    require-alias (or (:as options) (:as-alias options))
                    renames (or (:rename options) {})
                    referred (if (vector? (:refer options))
                               (:refer options) [])]
                (cond-> (update context :requires conj target)
                  require-alias (assoc-in [:aliases require-alias] target)
                  (seq referred)
                  (update :refers into
                          (map (fn [target-name]
                                 [(get renames target-name target-name)
                                  (symbol (str target) (str target-name))]))
                          referred)))))
          context
          (rest clause))

         :import
         (update context :imports into (mapcat import-bindings (rest clause)))

         context))
     {:aliases {} :refers {} :imports {} :requires #{}}
     (filter seq? (drop 2 form)))))

(defn- qualify-schema-symbols [form {:keys [aliases refers]}]
  (walk/postwalk
   (fn [value]
     (if-not (symbol? value)
       value
       (if-let [symbol-ns (namespace value)]
         (if-let [target (get aliases (symbol symbol-ns))]
           (symbol (str target) (name value))
           value)
         (or (get refers value)
             (when (ns-resolve 'clojure.core value)
               (symbol "clojure.core" (name value)))
             value))))
   form))

(defn- namespace-row [entry]
  (let [namespace-name (::analyzer/name entry)
        {:keys [aliases refers imports requires]} (namespace-context entry)]
    (cond-> {:seon.ns/name namespace-name
             :seon.ns/source (exact-source entry)}
      (::analyzer/doc entry) (assoc :seon.ns/doc (::analyzer/doc entry))
      (seq requires)
      (assoc :seon.ns/requires (into #{} (map namespace-ref) requires))
      (seq aliases)
      (assoc :seon.ns/aliases
             (into #{} (map (fn [[local target]]
                              {:seon.ns.alias/local local
                               :seon.ns.alias/target-ns target})) aliases))
      (seq refers)
      (assoc :seon.ns/refers
             (into #{} (map (fn [[local target]]
                              {:seon.ns.refer/local local
                               :seon.ns.refer/target-ns (symbol (namespace target))
                               :seon.ns.refer/target-name (symbol (name target))})) refers))

      (seq imports)
      (assoc :seon.ns/imports
             (into #{} (map (fn [[local target]]
                              {:seon.ns.import/local local
                               :seon.ns.import/target-class target})) imports)))))

(defn- first-party-function-symbols
  [analysis]
  (into #{}
        (comp
         (filter #(and (seq (::analyzer/arglist-strs %))
                       (not (::analyzer/macro %))))
         (map #(str (symbol (str (::analyzer/ns %))
                            (str (::analyzer/name %))))))
        (::analyzer/var-definitions analysis)))

(defn- call-targets-by-caller
  [analysis first-party-functions]
  (reduce
   (fn [calls usage]
     (let [caller
           (when (and (::analyzer/from usage) (::analyzer/from-var usage))
             (str (symbol (str (::analyzer/from usage))
                          (str (::analyzer/from-var usage)))))
           target
           (when (and (::analyzer/to usage) (::analyzer/name usage))
             (str (symbol (str (::analyzer/to usage))
                          (str (::analyzer/name usage)))))]
       (if (and (contains? usage ::analyzer/arity)
                (contains? first-party-functions caller)
                (contains? first-party-functions target))
         (update calls caller (fnil conj #{}) target)
         calls)))
   {}
   (::analyzer/var-usages analysis)))

(defn- var-row [analysis calls-by-caller entry]
  (let [namespace-name (::analyzer/ns entry)
        qualified (symbol (str namespace-name) (str (::analyzer/name entry)))
        metadata (::analyzer/meta entry)
        namespace-entry
        (first (filter #(= namespace-name (::analyzer/name %))
                       (::analyzer/namespace-definitions analysis)))
        source (exact-source entry)]
    (cond
      (::analyzer/test entry)
      {:seon.test/sym (str qualified)
       :seon.test/ns [:seon.ns/name namespace-name]
       :seon.test/source source}

      (and (seq (::analyzer/arglist-strs entry))
           (not (::analyzer/macro entry)))
      (cond-> {:seon.fn/sym (str qualified)
               :seon.fn/ns [:seon.ns/name namespace-name]
               :seon.fn/source source
               :seon.fn/arglists (str "(" (str/join " " (::analyzer/arglist-strs entry)) ")")
               :seon.fn/private? (boolean (::analyzer/private entry))}
        (::analyzer/doc entry) (assoc :seon.fn/doc (::analyzer/doc entry))
        (:malli/schema metadata)
        (assoc :seon.fn/spec
               (pr-str (schema/canonical-definition
                        (qualify-schema-symbols
                         (:malli/schema metadata)
                        (namespace-context namespace-entry))
                        {})))
        (seq (get calls-by-caller (str qualified)))
        (assoc :seon.fn/calls
               (mapv (fn [target] [:seon.fn/sym target])
                     (sort (get calls-by-caller (str qualified)))))
        (contains? #{:io :compute} (:seon.workload metadata))
        (assoc :seon.fn/workload (:seon.workload metadata)))

      :else nil)))

(defn- blocking-findings
  [analysis]
  (filterv #(= :error (::analyzer/level %))
           (::analyzer/findings analysis)))

(defn- assert-clean-analysis!
  [analysis]
  (when (seq (blocking-findings analysis))
    (throw (ex-info "Static program analysis found blocking errors."
                    {:seon.error/kind ::index-refused
                     ::findings (::analyzer/findings analysis)}))))

(defn- analysis-rows-by-file
  [analysis first-party-functions]
  (let [calls-by-caller
        (call-targets-by-caller analysis first-party-functions)]
    (reduce
     (fn [rows entry]
       (if-let [row (if (::analyzer/ns entry)
                      (var-row analysis calls-by-caller entry)
                      (namespace-row entry))]
         (update rows (::analyzer/filename entry) (fnil conj []) row)
         rows))
     {}
     (concat (::analyzer/namespace-definitions analysis)
             (::analyzer/var-definitions analysis)))))

(defn- artifact
  [file rows]
  (let [canonical-path (.getCanonicalPath ^java.io.File file)
        canonical-rows
        (mapv (fn [row]
                (cond-> (program/canonical-row row)
                  (seq (:seon.fn/calls row))
                  (assoc :seon.fn/calls (:seon.fn/calls row))))
              rows)]
    {:seon.fn.file/path canonical-path
     :seon.fn.file/digest (file-digest file)
     :seon.fn.file/rows canonical-rows
     :seon.fn.file/identities
     (->> canonical-rows
          (keep program/row-identity)
          (sort-by pr-str)
          vec)}))

(defn build-artifact
  "Build one deterministic first-party file projection."
  {:malli/schema
   [:=>
    [:cat [:map {:closed true}
           [:seon.fn.file/path [:string {:min 1}]]
           [:seon.fn.file/first-party-functions
            [:vector [:string {:min 1}]]]]]
    [:map]]}
  [{path :seon.fn.file/path
    known-functions :seon.fn.file/first-party-functions}]
  (let [file (rooted-file path)]
    (when-not (source-file? file)
      (throw (ex-info "A file artifact requires one existing Clojure file."
                      {:seon.error/kind ::index-refused
                       :seon.fn.file/path (.getCanonicalPath file)})))
    (let [canonical-path (.getCanonicalPath file)
          analysis (analyzer/analyze {::analyzer/paths [canonical-path]})
          first-party-functions
          (into (set known-functions)
                (first-party-function-symbols analysis))]
      (assert-clean-analysis! analysis)
      (artifact file
                (get (analysis-rows-by-file analysis first-party-functions)
                     canonical-path
                     [])))))

(defn artifact-by-path
  "The manifest artifact carrying one canonical file path."
  {:malli/schema
   [:=>
    [:catn
     [:manifest :map]
     [:canonical-path [:string {:min 1}]]]
    [:maybe :map]]}
  [manifest canonical-path]
  (some #(when (= canonical-path (:seon.fn.file/path %)) %)
        (:seon.fn.manifest/artifacts manifest)))

(defn manifest-function-symbols
  "Sorted first-party function symbols contributed by a manifest."
  {:malli/schema [:=> [:catn [:manifest :map]]
                  [:vector [:string {:min 1}]]]}
  [manifest]
  (->> (:seon.fn.manifest/identities manifest)
       (filter #(= :seon.fn/sym (first %)))
       (map second)
       distinct
       sort
       vec))

(defn- manifest-data
  [roots artifacts]
  (let [artifacts (vec (sort-by :seon.fn.file/path artifacts))]
    {:seon.fn.manifest/roots roots
     :seon.fn.manifest/digest
     (sha-256 (.getBytes
               (pr-str (mapv (juxt :seon.fn.file/path
                                   :seon.fn.file/digest)
                             artifacts))
               java.nio.charset.StandardCharsets/UTF_8))
     :seon.fn.manifest/artifacts artifacts
     :seon.fn.manifest/identities
     (->> artifacts
          (mapcat :seon.fn.file/identities)
          (sort-by pr-str)
          vec)}))

(defn replace-manifest-artifacts
  "Replace file artifacts and recompute one deterministic manifest."
  {:malli/schema
   [:=>
    [:catn
     [:manifest :map]
     [:desired-artifacts [:vector :map]]]
    :map]}
  [manifest desired-artifacts]
  (when-let [duplicate-path
             (some (fn [[path n]] (when (> n 1) path))
                   (frequencies (map :seon.fn.file/path desired-artifacts)))]
    (throw (ex-info "Manifest replacement carries a duplicate file path."
                    {:seon.error/kind ::index-refused
                     :seon.fn.file/path duplicate-path})))
  (let [desired-by-path
        (into {} (map (juxt :seon.fn.file/path identity)) desired-artifacts)
        retained
        (remove #(contains? desired-by-path (:seon.fn.file/path %))
                (:seon.fn.manifest/artifacts manifest))]
    (manifest-data (:seon.fn.manifest/roots manifest)
                   (concat retained desired-artifacts))))

(defn build-manifest
  "Build deterministic artifacts for the complete first-party program."
  {:malli/schema
   [:=> [:cat [:map {:closed true}
              [:seon.fn/roots :seon.fn/roots]]]
    [:map]]}
  [request]
  (let [roots (:seon.fn/roots request)
        files (source-files roots)
        paths (mapv #(.getCanonicalPath ^java.io.File %) files)
        analysis (analyzer/analyze {::analyzer/paths paths})
        _ (assert-clean-analysis! analysis)
        first-party-functions (first-party-function-symbols analysis)
        rows-by-file (analysis-rows-by-file analysis first-party-functions)
        artifacts
        (mapv (fn [file]
                (artifact file
                          (get rows-by-file
                               (.getCanonicalPath ^java.io.File file)
                               [])))
              files)]
    (manifest-data
     (mapv #(.getCanonicalPath ^java.io.File (rooted-file %)) roots)
     artifacts)))

(defn- row-by-identity
  [rows]
  (into {} (map (juxt program/row-identity identity)) rows))

(defn- changed-row-attributes
  [current desired]
  (into #{}
        (filter #(not= (get current %) (get desired %)))
        (into (set (keys current)) (keys desired))))

(defn- scalar-upsert-rows
  [current-rows desired]
  (into
   []
   (keep
    (fn [desired-row]
      (let [[identity-attribute identity-value :as program-identity]
            (program/row-identity desired-row)
            current-row (get current-rows program-identity)
            changed (changed-row-attributes current-row desired-row)]
        (when (seq changed)
          (assoc (select-keys desired-row changed)
                 identity-attribute identity-value)))))
   (:seon.fn.file/rows desired)))

(defn- full-rebuild
  [reasons details]
  (merge {:seon.fn.change/action :full-rebuild
          :seon.fn.change/reasons (vec (distinct reasons))}
         details))

(defn plan-file-change
  "Classify one file change as safe upserts or a clean rebuild."
  {:malli/schema
   [:=>
    [:cat [:map
           [:seon.fn.change/status
            [:enum :added :modified :deleted :moved :schema-resource
             :analysis-error]]
           [:seon.fn.change/current-artifact {:optional true} :map]
           [:seon.fn.change/desired-artifact {:optional true} :map]
           [:seon.fn.change/stale? {:optional true} :boolean]
           [:seon.fn.change/uncertain? {:optional true} :boolean]
           [:seon.fn.change/findings {:optional true} [:vector :map]]]]
    [:map]]}
  [{status :seon.fn.change/status
    current :seon.fn.change/current-artifact
    desired :seon.fn.change/desired-artifact
    stale? :seon.fn.change/stale?
    uncertain? :seon.fn.change/uncertain?
    findings :seon.fn.change/findings}]
  (let [current-identities (set (:seon.fn.file/identities current))
        desired-identities (set (:seon.fn.file/identities desired))
        current-rows (row-by-identity (:seon.fn.file/rows current))
        desired-rows (row-by-identity (:seon.fn.file/rows desired))
        shared-identities (set/intersection current-identities
                                            desired-identities)
        added-identities (set/difference desired-identities
                                         current-identities)
        changed-attributes
        (into #{}
              (mapcat (fn [program-identity]
                        (changed-row-attributes
                         (get current-rows program-identity)
                         (get desired-rows program-identity))))
              shared-identities)
        unsafe-attributes
        (many-or-component-attributes
         (into #{} (mapcat keys) (concat (vals current-rows)
                                         (vals desired-rows))))
        added-many-or-component?
        (some (fn [program-identity]
                (some #(contains? (get desired-rows program-identity) %)
                      unsafe-attributes))
              added-identities)
        reasons
        (cond-> []
          (contains? #{:deleted :moved :schema-resource :analysis-error} status)
          (conj status)
          (and (= :modified status) (nil? current))
          (conj :missing-artifact)
          (nil? desired)
          (conj :missing-desired-artifact)
          stale? (conj :stale-artifact)
          uncertain? (conj :uncertain-projection)
          (and current desired
               (not= (:seon.fn.file/path current)
                     (:seon.fn.file/path desired)))
          (conj :file-move)
          (seq (set/difference current-identities desired-identities))
          (conj :removed-identity)
          (seq added-identities)
          (conj :added-identity)
          (seq (set/intersection changed-attributes
                                 unsafe-attributes))
          (conj :component-or-cardinality-many-change)
          (contains? changed-attributes :seon.fn/spec)
          (conj :function-contract-change)
          added-many-or-component?
          (conj :component-or-cardinality-many-addition)
          (some (fn [program-identity]
                  (let [before (get current-rows program-identity)
                        after (get desired-rows program-identity)]
                    (some #(and (contains? before %)
                                (not (contains? after %)))
                          (changed-row-attributes before after))))
                shared-identities)
          (conj :attribute-retraction))]
    (if (seq reasons)
      (full-rebuild
       reasons
       (cond-> {:seon.fn.change/current-path
                (:seon.fn.file/path current)
                :seon.fn.change/desired-path
                (:seon.fn.file/path desired)
                :seon.fn.change/removed-identities
                (->> (set/difference current-identities desired-identities)
                     (sort-by pr-str)
                     vec)
                :seon.fn.change/added-identities
                (->> added-identities (sort-by pr-str) vec)
                :seon.fn.change/changed-attributes
                (vec (sort changed-attributes))}
         (seq findings) (assoc :seon.fn.change/findings findings)))
      {:seon.fn.change/action :incremental-upsert
       :seon.fn.change/path (:seon.fn.file/path desired)
       :seon.fn.change/digest (:seon.fn.file/digest desired)
       ;; The artifact is the complete analyzed file projection used to plan
       ;; the next edit. It must not be confused with the transaction delta.
       :seon.fn.change/artifact desired
       ;; Publish the exact scalar delta, not the whole analyzed row. Replaying
       ;; an unchanged namespace row would recreate anonymous component
       ;; children even though the planner had proved those fields unchanged.
       :seon.fn.change/rows
       (scalar-upsert-rows current-rows desired)
       :seon.fn.change/identities (:seon.fn.file/identities desired)})))

(defn rows
  "Canonical program rows discovered statically from exact JVM source."
  {:malli/schema [:=> [:cat :seon.fn/index-request] [:vector :map]]}
  [request]
  (into []
        (mapcat :seon.fn.file/rows)
        (:seon.fn.manifest/artifacts
         (or (:seon.fn/manifest request)
             (when (seq (:seon.fn/roots request))
               (build-manifest request))
             (throw
              (ex-info "Program rows require a manifest or source roots."
                       {:seon.error/kind ::index-refused}))))))

(defn- assert-one-row-per-identity!
  [desired]
  (when-let [duplicate
             (some (fn [[program-identity n]]
                     (when (> n 1) program-identity))
                   (frequencies (map program/row-identity desired)))]
    (throw
     (ex-info
      "Source indexing refused a duplicate program identity."
      {:seon.error/kind ::index-refused
       ::identity duplicate}))))

(defn- assert-populated!
  [desired]
  (doseq [identity-attr [:seon.ns/name :seon.fn/sym]]
    (when-not (some identity-attr desired)
      (throw
       (ex-info
        (str "Source indexing produced no " identity-attr
             " rows; refusing a partial program graph.")
        {:seon.error/kind ::index-refused
         ::missing-population identity-attr})))))

(defn- add-contract-facts
  [program-rows]
  (let [schema-forms
        (into (sorted-map)
              (keep (fn [{schema-key :seon.schema/key
                          form-string :seon.schema/form}]
                      (when schema-key
                        [schema-key (edn/read-string form-string)])))
              program-rows)
        function-contracts
        (into (sorted-map)
              (keep (fn [{function-symbol :seon.fn/sym
                          spec :seon.fn/spec}]
                      (when spec
                        [(symbol function-symbol) (edn/read-string spec)])))
              program-rows)
        projection (schema/build-projection schema-forms function-contracts)
        compile-options (:seon.schema.projection/compile-options projection)
        predicate-functions
        (:seon.schema.projection/predicate-functions projection)
        schema-keys (set (keys schema-forms))]
    (mapv (fn [row]
            (program/with-contract-facts
             {:seon.program/row row
              :seon.program/compile-options compile-options
              :seon.program/predicate-functions predicate-functions
              :seon.program/schema-keys schema-keys}))
          program-rows)))

(defn backfill-contract-facts!
  "Backfill every contracted function missing either parsed component root.

   All missing graphs commit in one transaction. A converged call performs no
   transaction; ordinary producers remain responsible for new and changed
   rows so their specs and parsed facts are atomic."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.store/branch-connection :seon.store/branch-connection]
      [:seon.db/process {:optional true} :seon.db/ref]]]
    :seon.reconcile/result]}
  [{connection :seon.store/branch-connection process :seon.db/process}]
  (let [db @connection
        projection (schema/projection-from-database db)
        compile-options (:seon.schema.projection/compile-options projection)
        predicate-functions
        (:seon.schema.projection/predicate-functions projection)
        schema-keys (set (keys (:seon.schema.projection/forms projection)))
        missing
        (db/q '[:find ?function ?function-symbol ?spec
               :where
               [?function :seon.fn/sym ?function-symbol]
               [?function :seon.fn/spec ?spec]
               (or (not [?function :seon.fn/arities])
                   (not [?function :seon.fn/ast]))]
             db)
        tx-data
        (into
         []
         (mapcat
          (fn [[function function-symbol spec]]
            (let [current (db/pull db [:seon.fn/arities :seon.fn/ast]
                                  function)
                  parsed
                  (program/contract-facts
                   {:seon.program/function-symbol function-symbol
                    :seon.program/spec spec
                    :seon.program/compile-options compile-options
                    :seon.program/predicate-functions predicate-functions
                    :seon.program/schema-keys schema-keys})]
              (concat
               (keep (fn [attribute]
                       (when (contains? current attribute)
                         [:db.fn/retractAttribute function attribute]))
                     [:seon.fn/arities :seon.fn/ast])
               [(assoc parsed :db/id function)]))))
         (sort-by second missing))]
    (when (seq tx-data)
      (d/transact connection
                  (cond-> {:tx-data tx-data}
                    process (assoc :tx-meta {:seon.db/process process}))))
    {:seon.reconcile/converged? (empty? tx-data)
     :seon.reconcile/operations (count missing)}))

(defn- desired-program-rows
  [request]
  (let [source-rows (rows request)
        canonical-schemas
        (schema/canonical-schema-rows (schema.edn/packaged-forms))
        canonical-keys (into #{} (map :seon.schema/key) canonical-schemas)
        source-only
        (remove (fn [row]
                  (contains? canonical-keys (:seon.schema/key row)))
                source-rows)
        source-namespace-names
        (into #{} (keep :seon.ns/name) source-only)
        required-namespace-names
        (into #{}
              (comp
               (mapcat #(or (:seon.ns/requires %) []))
               (map second))
              source-only)
        external-namespace-rows
        (->> required-namespace-names
             (remove source-namespace-names)
             (sort-by str)
             (mapv (fn [namespace-name]
                     {:seon.ns/name namespace-name})))]
    (doseq [{schema-key :seon.schema/key
             form-string :seon.schema/form}
            (filter :seon.schema/key source-only)]
      (when-not (and form-string
                     (schema/malli-form? (edn/read-string form-string)))
        (throw
         (ex-info "Source indexing refused a non-Malli schema declaration."
                  {:seon.error/kind ::index-refused
                   :seon.schema/key schema-key}))))
    (add-contract-facts
     (mapv #(assoc % :seon.schema.admission/source :core)
           (into (into (vec source-only) external-namespace-rows)
                 canonical-schemas)))))

(defn index!
  "Populate one fresh source scratch branch from static analysis."
  {:malli/schema [:=> [:cat :seon.fn/index-request] :seon.reconcile/result]}
  [{connection :seon.store/branch-connection process :seon.db/process :as request}]
  (let [program-rows (desired-program-rows request)
        _ (assert-one-row-per-identity! program-rows)
        _ (assert-populated! program-rows)
        existing (some (fn [identity-attribute]
                         (db/q '[:find ?entity .
                                :in $ ?attribute
                                :where [?entity ?attribute]]
                              @connection identity-attribute))
                       [:seon.ns/name :seon.fn/sym :seon.test/sym])]
    (when existing
      (throw (ex-info "Program indexing requires a fresh source scratch branch."
                      {:seon.error/kind ::index-refused
                       ::existing-program-entity existing})))
    (let [namespaces (filterv :seon.ns/name program-rows)
          namespace-bases
          (mapv #(dissoc % :seon.ns/requires) namespaces)
          namespace-relations
          (into []
                (keep (fn [row]
                        (when (seq (:seon.ns/requires row))
                          (select-keys row
                                       [:seon.ns/name :seon.ns/requires]))))
                namespaces)
          declarations (filterv #(not (:seon.ns/name %)) program-rows)
          declaration-bases (mapv #(dissoc % :seon.fn/calls) declarations)
          call-rows
          (into []
                (keep (fn [row]
                        (when (seq (:seon.fn/calls row))
                          (select-keys row [:seon.fn/sym :seon.fn/calls]))))
                declarations)
          transact! (fn [tx-data]
                      (when (seq tx-data)
                        (d/transact connection
                                    (cond-> {:tx-data tx-data}
                                      process (assoc :tx-meta
                                                     {:seon.db/process process})))))]
      ;; Datahike processes tx-data in order. Every identity therefore exists
      ;; before a requires lookup ref resolves it, including the shared
      ;; name-only rows for external namespaces.
      (transact! (into namespace-bases namespace-relations))
      (transact! declaration-bases)
      (transact! call-rows)
      {:seon.reconcile/converged? false
       :seon.reconcile/operations (count program-rows)})))
