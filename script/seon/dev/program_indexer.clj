(ns seon.dev.program-indexer
  "JVM compile-time producer for first-party program graph artifacts."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.db :as datahike-db]
            [malli.core :as m]
            [seon.db.program :as program]
            [seon.dev.test-roots :as test-roots]
            [seon.ns.source :as ns.source]
            [seon.program.edge :as edge]
            [seon.schema :as schema])
  (:import [java.io File StringReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

(def ^:private production-roots
  "The AOT roots frozen by `build.clj`; their require closure is the surviving
   first-party JVM program. Agent identity's surviving schema owner is also a
   root because fresh-database genesis requires it before any agent context is
   rendered."
  '[seon.db.server
    seon.host
    seon.web.server
    seon.agent.ctx
    seon.agent.home
    seon.render])

(def ^:private eof (Object.))

(defn- relative-path [root path]
  (str (.relativize
        (.toPath (.getCanonicalFile (io/file root)))
        (.toPath (.getCanonicalFile (io/file path))))))

(defn- bytes->hex [byte-values]
  (apply str (map #(format "%02x" (bit-and 0xff %)) byte-values)))

(defn- digest [text]
  (let [message-digest (MessageDigest/getInstance "SHA-256")]
    (.update message-digest
             (.getBytes (str text) StandardCharsets/UTF_8))
    (bytes->hex (.digest message-digest))))

(defn- source-file? [^File file]
  (and (.isFile file)
       (or (str/ends-with? (.getName file) ".clj")
           (str/ends-with? (.getName file) ".cljc"))))

(defn- first-ns-form [source]
  (with-open [reader
              (clojure.lang.LineNumberingPushbackReader.
               (StringReader. source))]
    (binding [*read-eval* false]
      (loop []
        (let [form (read {:eof eof
                          :read-cond :allow
                          :features #{:clj}}
                         reader)]
          (cond
            (identical? eof form) nil
            (and (seq? form) (= 'ns (first form))) form
            :else (recur)))))))

(defn- source-files [root]
  (->> (file-seq (io/file root "src"))
       (filter source-file?)
       (sort-by #(.getCanonicalPath ^File %))
       vec))

(defn- source-description [root ^File file]
  (let [source (slurp file)
        ns-form (first-ns-form source)
        namespace (second ns-form)
        info (ns.source/namespace-info-from-source source)]
    (when-not (symbol? namespace)
      (throw
       (ex-info "First-party source must declare one namespace."
                {:seon.dev.program-indexer/path
                 (.getCanonicalPath file)})))
    {:seon.dev.program-indexer/file file
     :seon.dev.program-indexer/resource
     (relative-path root file)
     :seon.dev.program-indexer/namespace namespace
     :seon.dev.program-indexer/source source
     :seon.dev.program-indexer/namespace-info info}))

(defn- production-namespace-closure [descriptions]
  (let [by-namespace
        (into {}
              (map (juxt :seon.dev.program-indexer/namespace identity))
              descriptions)]
    (loop [pending (seq production-roots)
           selected #{}]
      (if-let [namespace (first pending)]
        (if (contains? selected namespace)
          (recur (next pending) selected)
          (let [description (get by-namespace namespace)]
            (when-not description
              (throw
               (ex-info "A first-party JVM require has no indexed source."
                        {:seon.dev.program-indexer/namespace namespace})))
            (let [required
                  (into []
                        (comp
                         (remove :seon.ns.require/as-alias?)
                         (map :seon.ns.require/target)
                         (filter by-namespace))
                        (get-in description
                                [:seon.dev.program-indexer/namespace-info
                                 :seon.ns/require-edges]))]
              (recur (concat required (next pending))
                     (conj selected namespace)))))
        selected))))

(defn- read-forms [namespace source]
  (let [reader-namespace
        (or (find-ns namespace) (create-ns namespace))
        require-edges (ns.source/require-edges-from-source source)]
    (binding [*ns* reader-namespace]
      (doseq [{target :seon.ns.require/target
               alias-symbol :seon.ns.require/alias} require-edges
              :when alias-symbol]
        (create-ns target)
        (when-not (= target
                     (some-> (get (ns-aliases *ns*) alias-symbol)
                             ns-name))
          (clojure.core/alias alias-symbol target))))
    (with-open [reader
                (clojure.lang.LineNumberingPushbackReader.
                 (StringReader. source))]
      (binding [*read-eval* false
                *ns* reader-namespace]
      (loop [forms []]
        (let [[form form-source]
              (read+string {:eof eof
                            :read-cond :allow
                            :features #{:clj}}
                           reader)]
          (if (identical? eof form)
            forms
            (recur (conj forms
                         {:seon.dev.program-indexer/form form
                          :seon.dev.program-indexer/source
                          form-source})))))))))

(defn- function-form? [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? #{"defn" "defn-"} (name (first form)))
       (symbol? (second form))))

(defn- test-form? [form]
  (and (seq? form)
       (symbol? (first form))
       (= "deftest" (name (first form)))
       (symbol? (second form))))

(defn- var-spec [var-value]
  (when-let [malli-schema (:malli/schema (meta var-value))]
    (try
      (-> malli-schema m/schema m/form pr-str)
      (catch Throwable _
        nil))))

(defn- function-row
  [namespace {:seon.dev.program-indexer/keys [form source]}]
  (let [function-name (second form)
        qualified-symbol (symbol (str namespace) (str function-name))
        var-value (ns-resolve namespace function-name)
        metadata (meta var-value)
        spec (when-not (true? (:private metadata))
               (var-spec var-value))]
    (cond->
     {:seon.fn/sym (str qualified-symbol)
      :seon.fn/ns [:seon.ns/name namespace]
      :seon.fn/source source
      :seon.fn/fn-var? true
      :seon.fn/arglists (pr-str (or (:arglists metadata) '()))
      :seon.fn/doc (or (:doc metadata) "")
      :seon.fn/private? (boolean (:private metadata))}
      spec (assoc :seon.fn/spec spec))))

(defn- namespace-row
  [{:seon.dev.program-indexer/keys
    [namespace source namespace-info]}]
  (cond->
   {:seon.ns/name namespace
    :seon.ns/source source
    :seon.ns/require-edges
    (:seon.ns/require-edges namespace-info)}
    (:seon.ns/doc namespace-info)
    (assoc :seon.ns/doc (:seon.ns/doc namespace-info))

    (:seon.ns/summary namespace-info)
    (assoc :seon.ns/summary (:seon.ns/summary namespace-info))))

(defn- described-functions [descriptions]
  (into []
        (mapcat
         (fn [{:seon.dev.program-indexer/keys [namespace source]}]
           (into []
                 (comp
                  (filter (comp function-form?
                                :seon.dev.program-indexer/form))
                  (map (fn [described-form]
                         {:seon.dev.program-indexer/namespace namespace
                          :seon.dev.program-indexer/form
                          (:seon.dev.program-indexer/form described-form)
                          :seon.dev.program-indexer/row
                          (function-row namespace described-form)})))
                 (read-forms namespace source))))
        descriptions))

(defn- aliases [namespace-info]
  (::ns.source/aliases
   (ns.source/edges->require-info
    (:seon.ns/require-edges namespace-info))))

(defn- refers [namespace-info]
  (let [require-info
        (ns.source/edges->require-info
         (:seon.ns/require-edges namespace-info))]
    (into {}
          (concat
           (mapcat
            (fn [[target names]]
              (map (fn [referred]
                     [referred (symbol (str target) (str referred))])
                   names))
            (::ns.source/refers require-info))
           (mapcat
            (fn [target]
              (map (fn [referred]
                     [referred (symbol (str target) (str referred))])
                   (keys (ns-publics target))))
            (::ns.source/refer-all require-info))))))

(defn- macro-symbols [namespaces]
  (into #{}
        (mapcat
         (fn [namespace]
           (keep (fn [[name var-value]]
                   (when (:macro (meta var-value))
                     (symbol (str namespace) (str name))))
                 (ns-interns namespace))))
        (conj (set namespaces) 'clojure.core)))

(defn- capability-effects [functions]
  (into {}
        (keep
         (fn [{:seon.dev.program-indexer/keys [namespace form]}]
           (let [function-name (second form)
                 effect
                 (or (:seon.capability/effect (meta function-name))
                     (some-> (ns-resolve namespace function-name)
                             meta
                             :seon.capability/effect))]
             (when effect
               [(symbol (str namespace) (str function-name)) effect]))))
        functions))

(defn- edge-resolution
  [description current-vars known-namespaces macros effects]
  {::edge/namespace
   (:seon.dev.program-indexer/namespace description)
   ::edge/aliases (aliases
                   (:seon.dev.program-indexer/namespace-info description))
   ::edge/refers (refers
                  (:seon.dev.program-indexer/namespace-info description))
   ::edge/current-vars current-vars
   ::edge/core-vars (set (keys (ns-publics 'clojure.core)))
   ::edge/known-namespaces known-namespaces
   ::edge/macro-symbols macros
   ::edge/effects effects})

(defn- terminal-row [terminal]
  {::edge/terminal-symbol (::edge/terminal-symbol terminal)
   ::edge/effect (::edge/effect terminal)
   ::edge/required-bindings (::edge/required-bindings terminal)
   ::edge/terminal-generation (::edge/terminal-generation terminal)})

(defn- indexed-function-rows [descriptions]
  (let [functions (described-functions descriptions)
        by-namespace
        (group-by :seon.dev.program-indexer/namespace functions)
        known-namespaces
        (conj (set (map :seon.dev.program-indexer/namespace descriptions))
              'clojure.core)
        macros (macro-symbols known-namespaces)
        effects (capability-effects functions)
        description-by-namespace
        (into {}
              (map (juxt :seon.dev.program-indexer/namespace identity))
              descriptions)]
    (into []
          (map
           (fn [{:seon.dev.program-indexer/keys [namespace form row]}]
             (let [current-vars
                   (into #{}
                         (map (comp second
                                    :seon.dev.program-indexer/form))
                         (get by-namespace namespace))
                   bundle
                   (try
                     (edge/analyze-function
                      {::edge/function-symbol (:seon.fn/sym row)
                       ::edge/form form
                       ::edge/resolution
                       (edge-resolution
                        (description-by-namespace namespace)
                        current-vars known-namespaces macros effects)})
                     (catch Throwable exception
                       (throw
                        (ex-info
                         "Program edge analysis failed for indexed source."
                         {:seon.fn/sym (:seon.fn/sym row)}
                         exception))))]
               (cond->
                (assoc row
                       ::edge/generation (::edge/generation bundle)
                       ::edge/all-at-basis?
                       (::edge/all-at-basis? bundle))
                 (seq (::edge/calls bundle))
                 (assoc ::edge/calls (::edge/calls bundle))

                 (seq (::edge/read-attributes bundle))
                 (assoc ::edge/read-attributes
                        (::edge/read-attributes bundle))

                 (seq (::edge/written-attributes bundle))
                 (assoc ::edge/written-attributes
                        (::edge/written-attributes bundle))

                 (seq (::edge/uncertainties bundle))
                 (assoc ::edge/uncertainties
                        (::edge/uncertainties bundle))

                 (seq (::edge/terminals bundle))
                 (assoc ::edge/terminal-refs
                        (mapv terminal-row (::edge/terminals bundle)))))))
          functions)))

(defn- test-rows [root]
  (into []
        (mapcat
         (fn [^File file]
           (let [source (slurp file)
                 namespace (second (first-ns-form source))]
             (into []
                   (comp
                    (filter (comp test-form?
                                  :seon.dev.program-indexer/form))
                    (map
                     (fn [{:seon.dev.program-indexer/keys [form source]}]
                       {:seon.test/sym
                        (str (symbol (str namespace) (str (second form))))
                        :seon.test/ns [:seon.ns/name namespace]
                        :seon.test/source source})))
                   (read-forms namespace source))))
        (test-roots/writer-test-files root))))

(defn- test-source-descriptions [root]
  (mapv
   (fn [^File file]
     {:seon.dev.program-indexer/resource
      (relative-path root file)
      :seon.dev.program-indexer/source (slurp file)})
   (test-roots/writer-test-files root)))

(defn- base-projection [rows]
  (let [core-transaction
        {:seon.db/user {:seon.agent/id "root"}
         :seon.db/process
         {:seon.db.process/id :seon.db.process/boot}}
        schema-rows
        (into []
              (keep (fn [{:seon.schema/keys [key form]}]
                      (when (and key form)
                        [key form core-transaction])))
              rows)
        contract-rows
        (into []
              (keep (fn [{:seon.fn/keys [sym spec]}]
                      (when (and sym spec)
                        [sym spec core-transaction])))
              rows)
        source-rows
        (into []
              (keep (fn [{:seon.fn/keys [sym source]}]
                      (when (and sym source)
                        [sym source core-transaction])))
              rows)]
    (schema/projection-pure-data
     (schema/projection-from-rows
      {:seon.schema/schema-rows schema-rows
       :seon.schema/function-contract-rows contract-rows
       :seon.schema/function-source-rows source-rows
       :seon.schema/artifact-exports #{}}))))

(defn- atomic-spit! [path value]
  (let [path (.toPath (io/file path))
        temporary (.toPath (io/file (str path "." (random-uuid) ".tmp")))]
    (Files/createDirectories (.getParent path)
                             (make-array
                              java.nio.file.attribute.FileAttribute 0))
    (spit (str temporary) (str (pr-str value) "\n"))
    (Files/move temporary path
                (into-array
                 java.nio.file.CopyOption
                 [StandardCopyOption/REPLACE_EXISTING
                  StandardCopyOption/ATOMIC_MOVE]))
    value))

(defn index
  "Index the surviving JVM program and return all four artifact values."
  [{:seon.dev.program-indexer/keys
    [root config-manifest-digest page-rows]}]
  (doseq [namespace production-roots]
    (require namespace))
  (let [descriptions (mapv #(source-description root %) (source-files root))
        selected-namespaces (production-namespace-closure descriptions)
        selected
        (->> descriptions
             (filter
              (comp selected-namespaces
                    :seon.dev.program-indexer/namespace))
             (sort-by (comp str
                            :seon.dev.program-indexer/namespace))
             vec)
        namespace-rows (mapv namespace-row selected)
        function-rows (indexed-function-rows selected)
        schema-rows
        (schema/canonical-schema-rows
         (java.util.Date/from java.time.Instant/EPOCH))
        test-rows (test-rows root)
        desired (into namespace-rows
                      (concat function-rows schema-rows test-rows))
        rows
        (program/compile-tx-data
         (datahike-db/empty-db {} {:schema-flexibility :write})
         desired)
        row-artifact {:seon.dev.artifact/program-rows rows}
        row-artifact-digest (digest (str (pr-str row-artifact) "\n"))
        projection (base-projection rows)
        initialization
        {:seon.execution/artifact-digest row-artifact-digest
         :seon.db.initialization/config-manifest-digest
         config-manifest-digest
         :seon.db.initialization/page-rows page-rows
         :seon.db/attributes (schema/canonical-database-attributes)
         :seon.db/program rows
         :seon.db/initial-data []}
        pages (program/compile-initialization-pages initialization)
        source-descriptions
        (into selected (test-source-descriptions root))
        source-artifact
        {:seon.dev.artifact/program-sources
         (into (sorted-map)
               (map (juxt :seon.dev.program-indexer/resource
                          :seon.dev.program-indexer/source))
               source-descriptions)}]
    {:seon.dev.artifact/program-sources source-artifact
     :seon.dev.artifact/program-rows row-artifact
     :seon.dev.artifact/base-projection
     {:seon.dev.artifact/base-projection projection
      :seon.db.initialization/fingerprint
      (:seon.db.initialization/fingerprint (first pages))}
     :seon.dev.artifact/page-plan
     {:seon.dev.artifact/page-plan
      {:seon.execution/artifact-digest row-artifact-digest
       :seon.db.initialization/config-manifest-digest
       config-manifest-digest
       :seon.db/initialization-pages pages}}
     :seon.dev.program-indexer/inventory
     {:seon.ns/count (count namespace-rows)
      :seon.fn/count (count function-rows)
      :seon.schema/count (count schema-rows)
      :seon.test/count (count test-rows)
      :seon.db.initialization/page-count (count pages)
      :seon.db.initialization/fact-row-count (count rows)}}))

(defn publish!
  "Publish the four deterministic program artifacts beside the client output."
  [{:seon.dev.program-indexer/keys
    [program-source-path program-row-path base-projection-path page-plan-path]
    :as request}]
  (let [artifacts (index request)]
    (atomic-spit! program-source-path
                  (:seon.dev.artifact/program-sources artifacts))
    (atomic-spit! program-row-path
                  (:seon.dev.artifact/program-rows artifacts))
    (atomic-spit! base-projection-path
                  (:seon.dev.artifact/base-projection artifacts))
    (atomic-spit! page-plan-path
                  (:seon.dev.artifact/page-plan artifacts))
    (:seon.dev.program-indexer/inventory artifacts)))

(defn -main
  [& [root program-source-path program-row-path base-projection-path
       page-plan-path config-manifest-digest page-rows]]
  (when-not (and root program-source-path program-row-path
                 base-projection-path page-plan-path
                 (re-matches #"[0-9a-f]{64}"
                             (or config-manifest-digest ""))
                 (pos-int? (parse-long page-rows)))
    (throw
     (ex-info "JVM program indexer arguments are incomplete."
              {:seon.dev.program-indexer/root root
               :seon.dev.program-indexer/config-manifest-digest
               config-manifest-digest
               :seon.dev.program-indexer/page-rows page-rows})))
  (println
   (pr-str
    (publish!
     {:seon.dev.program-indexer/root root
      :seon.dev.program-indexer/program-source-path program-source-path
      :seon.dev.program-indexer/program-row-path program-row-path
      :seon.dev.program-indexer/base-projection-path base-projection-path
      :seon.dev.program-indexer/page-plan-path page-plan-path
      :seon.dev.program-indexer/config-manifest-digest
      config-manifest-digest
      :seon.dev.program-indexer/page-rows (parse-long page-rows)}))))
