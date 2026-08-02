;; Measure the reductions proposed by the 2026-08-02 store census.
;; Run from the repository root:
;;
;;   clojure -M:dev \
;;     docs/prds/sci-execution-runtime/research/scripts/store-census-reductions-2026-08-02.clj
;;
;; Every cell creates a UUID-named private file store below tmp/. It never
;; opens, mutates, collects, or deletes an operator store or an eval archive.

(require '[clojure.java.io :as io]
         '[datahike.api :as d]
         '[datahike.datom :as datom]
         '[konserve.core :as k]
         '[org.replikativ.persistent-sorted-set.fressian :as pss-fress]
         '[seon.cluster.loop :as loop]
         '[seon.cluster.store :as seon-store])

(import '[java.io File]
        '[java.util Date UUID]
        '[org.replikativ.persistent_sorted_set ANode])

(def ^:private parent-row-count 200)
(def ^:private child-commit-count 20)
(def ^:private threshold-commit-count 67)

(def ^:private current-root-keys
  [:eavt-root :aevt-root :avet-root])

(def ^:private temporal-root-keys
  [:temporal-eavt-root :temporal-aevt-root :temporal-avet-root])

(def ^:private measured-payload-attributes
  [:seon.cluster.eval/result-edn
   :seon.context.capture/prompt
   :seon.cluster.run.form/source
   :seon.cluster.eval/output
   :seon.cluster.message/content
   :seon.ai.attempt/settings-edn
   :seon.ai.attempt/usage-edn])

(def ^:private representative-payload-sizes
  ;; Mean string sizes where the census exposed them, rounded to characters.
  ;; Settings and usage are deliberately small controls; this rig proves the
  ;; physical noHistory mechanism, while the census supplies eval-scale bytes.
  {:seon.cluster.eval/result-edn 716
   :seon.context.capture/prompt 17389
   :seon.cluster.run.form/source 120
   :seon.cluster.eval/output 416
   :seon.cluster.message/content 843
   :seon.ai.attempt/settings-edn 512
   :seon.ai.attempt/usage-edn 128})

(def ^:private threshold-result-sizes
  ;; 67 is the measured average retained commit count per sample. The six
  ;; oversized values reproduce the archived proportion over 4,096
  ;; (444 / 5,368 = 5.54 per 67) and include the observed 13,256 maximum.
  (vec (concat (repeat 61 59) [4100 5000 6000 8000 10000 13256])))

(defn- directory-stats
  [path]
  (let [files (filter #(.isFile ^File %) (file-seq (io/file path)))]
    {:store.reduction/objects (count files)
     :store.reduction/bytes
     (reduce + 0 (map #(.length ^File %) files))}))

(defn- stage
  [path operation]
  (assoc (directory-stats path) :store.reduction/operation operation))

(defn- growth
  [before after]
  (- (:store.reduction/bytes after)
     (:store.reduction/bytes before)))

(defn- configuration
  [path fuse-index-roots?]
  (cond-> (seon-store/datahike-configuration path)
    (false? fuse-index-roots?) (dissoc :fuse-index-roots?)
    true (assoc :index-config {:diff-buf-size 256}
                :keep-history? true
                :writer {:backend :self :commit-wait-time 0})))

(defn- node-datoms
  [node]
  (if-not (instance? ANode node)
    []
    (into []
          (filter datom/datom?)
          (tree-seq
           (fn [value]
             (and (not (datom/datom? value)) (coll? value)))
           (fn [value]
             (cond
               (map? value) (mapcat identity value)
               (coll? value) (seq value)
               :else nil))
           (pss-fress/node->map node)))))

(defn- inherited-root-datoms
  [stored-db parent-max-tx]
  (let [fields (concat current-root-keys temporal-root-keys)]
    (->> fields
         (keep stored-db)
         (mapcat node-datoms)
         (filter #(<= (:tx %) parent-max-tx))
         count)))

(def ^:private fork-schema
  [{:db/ident :store.reduction.parent/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :store.reduction.parent/payload
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn- parent-rows
  []
  (mapv (fn [index]
          {:store.reduction.parent/id (str "parent-" index)
           :store.reduction.parent/payload
           (str index "-" (apply str (repeat 252 \p)))})
        (range parent-row-count)))

(defn- child-row
  [index]
  {:store.reduction.parent/id (str "child-" index)
   :store.reduction.parent/payload (str "child payload " index)})

(defn- measure-fork-cell!
  [root fuse-index-roots?]
  (let [label (if fuse-index-roots? "fork-fused" "fork-unfused")
        path (.getCanonicalPath (io/file root label))
        config (configuration path fuse-index-roots?)]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (let [created (stage path :created)]
          (d/transact connection fork-schema)
          (let [schema-installed (stage path :schema-installed)]
            (d/transact connection (parent-rows))
            (let [parent-populated (stage path :parent-populated)
                  parent-commit-id (d/commit-id @connection)
                  parent-max-tx (:max-tx @connection)]
              (d/branch! connection parent-commit-id :child)
              (let [forked (stage path :forked)]
                (d/release connection)
                (let [child (d/connect (assoc config :branch :child))]
                  (try
                    (dotimes [index child-commit-count]
                      (d/transact child [(child-row index)]))
                    (let [child-committed (stage path :child-committed)
                          child-commit-id (d/commit-id @child)
                          stored-db
                          (k/get (:store @child) child-commit-id nil
                                 {:sync? true})]
                      {:store.reduction/label (keyword label)
                       :store.reduction/options
                       (select-keys (:config @child)
                                    [:keep-history? :fuse-index-roots?
                                     :index-config :commit-graph?])
                       :store.reduction/stages
                       [created schema-installed parent-populated forked
                        child-committed]
                       :store.reduction/fork-growth-bytes
                       (growth parent-populated forked)
                       :store.reduction/child-growth-bytes
                       (growth forked child-committed)
                       :store.reduction/parent-commit-id parent-commit-id
                       :store.reduction/child-commit-id child-commit-id
                       :store.reduction/parent-max-tx parent-max-tx
                       :store.reduction/inherited-datoms-in-final-fused-roots
                       (inherited-root-datoms stored-db parent-max-tx)})
                    (finally
                      (d/release child))))))))
        (finally
          (when (d/database-exists? config)
            ;; The parent may already have been released before the child open.
            ;; Datahike release is idempotent for an absent connection handle.
            (try (d/release connection) (catch Throwable _ nil))))))))

(defn- string-attribute
  [attribute no-history?]
  (cond-> {:db/ident attribute
           :db/valueType :db.type/string
           :db/cardinality :db.cardinality/one}
    no-history? (assoc :db/noHistory true)))

(defn- reduction-schema
  [created-at? no-history?]
  (into
   [{:db/ident :store.reduction.schema/key
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
    {:db/ident :store.reduction.schema/form
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :store.reduction.sample/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}]
   (concat
    (when created-at?
      [{:db/ident :seon.schema/created-at
        :db/valueType :db.type/instant
        :db/cardinality :db.cardinality/one}])
    (map #(string-attribute % no-history?) measured-payload-attributes))))

(defn- schema-rows
  [created-at?]
  (let [now (Date.)]
    (mapv
     (fn [index]
       (cond->
        {:store.reduction.schema/key
         (keyword "store.reduction.schema" (str "schema-" index))
         :store.reduction.schema/form
         (pr-str [:map [:value [:string {:min 1}]]])}
         created-at? (assoc :seon.schema/created-at now)))
     (range parent-row-count))))

(defn- sized-value
  [attribute index]
  (let [size (get representative-payload-sizes attribute)
        prefix (str (name attribute) "-" index "-")]
    (str prefix (apply str (repeat (max 0 (- size (count prefix))) \x)))))

(defn- sample-row
  [index]
  (into {:store.reduction.sample/id (str "sample-" index)}
        (map (fn [attribute]
               [attribute (sized-value attribute index)]))
        measured-payload-attributes))

(defn- measure-schema-cell!
  [root {:store.reduction/keys [label created-at? no-history?]}]
  (let [path (.getCanonicalPath (io/file root (name label)))
        config (configuration path true)]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (let [created (stage path :created)]
          (d/transact connection (reduction-schema created-at? no-history?))
          (let [schema-installed (stage path :schema-installed)]
            (d/transact connection (schema-rows created-at?))
            (let [parent-populated (stage path :parent-populated)
                  parent-commit-id (d/commit-id @connection)]
              (d/branch! connection parent-commit-id :child)
              (let [forked (stage path :forked)]
                (d/release connection)
                (let [child (d/connect (assoc config :branch :child))]
                  (try
                    (dotimes [index child-commit-count]
                      (d/transact child [(sample-row index)]))
                    (let [child-committed (stage path :child-committed)]
                      {:store.reduction/label label
                       :store.reduction/created-at? created-at?
                       :store.reduction/no-history? no-history?
                       :store.reduction/stages
                       [created schema-installed parent-populated forked
                        child-committed]
                       :store.reduction/final-bytes
                       (:store.reduction/bytes child-committed)
                       :store.reduction/parent-growth-bytes
                       (growth schema-installed parent-populated)
                       :store.reduction/fork-growth-bytes
                       (growth parent-populated forked)
                       :store.reduction/child-growth-bytes
                       (growth forked child-committed)})
                    (finally
                      (d/release child))))))))
        (finally
          (try (d/release connection) (catch Throwable _ nil)))))))

(defn- exact-result-edn
  [target-size index]
  (let [prefix (str index "-")
        content (str prefix
                     (apply str
                            (repeat (max 0 (- target-size 2 (count prefix)))
                                    \r)))
        serialized (pr-str content)]
    (when-not (= target-size (count serialized))
      (throw (ex-info "Could not construct exact result size."
                      {:target target-size :actual (count serialized)})))
    serialized))

(defn- threshold-schema
  []
  [{:db/ident :seon.config.eval.result/blob-threshold
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.render.value/max-collection
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :store.reduction.result/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.cluster.eval/result-edn
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/noHistory true}
   {:db/ident :seon.cluster.eval/result-blob
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.cluster.eval/result-size
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(defn- measure-threshold-cell!
  [root threshold]
  (let [label (keyword (str "threshold-" threshold))
        path (.getCanonicalPath (io/file root (name label)))
        config (configuration path true)]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (let [created (stage path :created)]
          (d/transact connection (threshold-schema))
          (let [schema-installed (stage path :schema-installed)]
            (d/transact
             connection
             [{:seon.config.eval.result/blob-threshold (long threshold)
               :seon.render.value/max-collection 8}])
            (let [config-installed (stage path :config-installed)
                blob-count (atom 0)]
            (doseq [[index result-size]
                    (map-indexed vector threshold-result-sizes)]
              (let [result-edn (exact-result-edn result-size index)
                    settlement
                    (#'loop/settlement-result
                     {:seon.store/branch-connection connection
                      :seon.sci.admit/caps
                      {:seon.sci.admit/max-depth 64
                       :seon.sci.admit/max-collection 8192
                       :seon.sci.admit/max-string 262144
                       :seon.sci.admit/max-nodes 65536}}
                     {:seon.cluster.eval/result-edn result-edn})
                    _ (when (:seon.cluster.eval/result-blob settlement)
                        (swap! blob-count inc))
                    row
                    (assoc
                     (select-keys
                      settlement
                      [:seon.cluster.eval/result-edn
                       :seon.cluster.eval/result-blob
                       :seon.cluster.eval/result-size])
                     :store.reduction.result/id (str "result-" index))]
                (d/transact connection [row])))
            (let [results-committed (stage path :results-committed)]
              {:store.reduction/label label
               :store.reduction/threshold threshold
               :store.reduction/commits threshold-commit-count
               :store.reduction/blob-count @blob-count
               :store.reduction/stages
               [created schema-installed config-installed results-committed]
               :store.reduction/growth-bytes
               (growth config-installed results-committed)
               :store.reduction/final-bytes
               (:store.reduction/bytes results-committed)}))))
        (finally
          (d/release connection))))))

(defn- byte-deltas
  [rows baseline-label]
  (let [baseline (some #(when (= baseline-label (:store.reduction/label %)) %)
                       rows)
        baseline-bytes (:store.reduction/final-bytes baseline)]
    (mapv (fn [row]
            (assoc row :store.reduction/bytes-vs-baseline
                   (- (:store.reduction/final-bytes row) baseline-bytes)))
          rows)))

(defn -main
  "Print physical before/after cells for each proposed store reduction."
  [& _]
  (let [root (.getCanonicalPath
              (io/file "tmp" (str "store-census-reductions-" (UUID/randomUUID))))
        fork-cells [(measure-fork-cell! root true)
                    (measure-fork-cell! root false)]
        schema-cells
        (byte-deltas
         (mapv #(measure-schema-cell! root %)
               [{:store.reduction/label :schema-baseline
                 :store.reduction/created-at? true
                 :store.reduction/no-history? false}
                {:store.reduction/label :schema-no-created-at
                 :store.reduction/created-at? false
                 :store.reduction/no-history? false}
                {:store.reduction/label :schema-no-history
                 :store.reduction/created-at? true
                 :store.reduction/no-history? true}
                {:store.reduction/label :schema-both
                 :store.reduction/created-at? false
                 :store.reduction/no-history? true}])
         :schema-baseline)
        threshold-cells
        (mapv #(measure-threshold-cell! root %)
              [64 256 1024 4096 65536])
        result
        {:dependency/ledger
         {:datahike-sha "0e8601d7f2f68c01070e13a95483bc82be04cabc"
          :konserve-sha "737697d9205e5e8f0bc08a666e4c97dad55e9dbe"
          :persistent-sorted-set-sha
          "e1a17bbe767c7801e67407c81f64efabfd2f1601"
          :record-writer
          "reference-code/datahike/src/datahike/writing.cljc:48-180,477-552"
          :branch-copy
          "reference-code/datahike/src/datahike/versioning.cljc:268-289"
          :no-history-write
          "reference-code/datahike/src/datahike/db/transaction.cljc:439-466,538-560"}
         :store.reduction/root root
         :store.reduction/fork-cells fork-cells
         :store.reduction/schema-cells schema-cells
         :store.reduction/threshold-cells threshold-cells}
        output-path (System/getenv "SEON_STORE_REDUCTION_OUTPUT")]
    (when output-path
      (spit output-path (pr-str result)))
    (prn result))
  (shutdown-agents))

(apply -main *command-line-args*)
