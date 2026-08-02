#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns store-attribute-census-2026-08-02
  "Attribute-level physical-byte census for the preserved GPQA eval stores."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [datahike.datom :as datom]
            [org.replikativ.persistent-sorted-set.fressian :as pss-fress])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [org.replikativ.persistent_sorted_set ANode Branch]))

;; This is a read-only consumer of the validated object decoder. The caller
;; first clones the two preserved operator roots to the default directory below;
;; no Datahike connection is opened and no transaction is performed.
(when-not (System/getenv "SEON_DECOMPOSITION_LIBRARY")
  (throw (ex-info "Set SEON_DECOMPOSITION_LIBRARY=1 before running."
                  {:script *file*})))
(load-file
 "docs/prds/sci-execution-runtime/research/scripts/eval-sample-store-decomposition-2026-08-02.clj")

(def decomposition-ns
  (find-ns 'eval-sample-store-decomposition-2026-08-02))

(defn- decomposition-var
  [sym]
  (or (ns-resolve decomposition-ns sym)
      (throw (ex-info "Missing decomposition helper." {:symbol sym}))))

(def serialized-datom-size
  (memoize
   (fn [serializer value]
     ((decomposition-var 'serialized-size) serializer value))))

(def source-root-prefix
  "tmp/inspect-ai-head-c6d79f817/tmp/inspect-ai")

(def copied-root-prefix
  (or (System/getenv "SEON_STORE_CENSUS_ROOT")
      "tmp/store-census-20260802"))

(def root-names
  ["333214a0f358" "d0cd8fbc1fa3"])

(defn- copied-root-name
  [root-name]
  (str root-name "-copy"))

(defn- copied-store-directory
  [root-name]
  (str copied-root-prefix "/" (copied-root-name root-name)
       "/data/clusters/store"))

(defn- logical-file-bytes
  [directory]
  (reduce + 0
          (map #(Files/size %)
               ((decomposition-var 'regular-files) directory))))

(defn- object-headers
  [root-name]
  (let [regular-files (decomposition-var 'regular-files)
        read-header (decomposition-var 'read-object-header)]
    (->> (regular-files (copied-store-directory root-name))
         (filter #(.endsWith (str %) ".ksv"))
         (mapv read-header))))

(defn- analyze-objects-with-values
  "Decode only database records and index nodes, retaining their values here.

  The validated decomposition script deliberately emits summaries. This
  consumer retains decoded values without changing that hashed instrument."
  [serializer objects]
  (let [read-object-value (decomposition-var 'read-object-value)
        db-record? (decomposition-var 'db-record?)
        db-summary (decomposition-var 'db-summary)]
    (reduce
     (fn [analysis object]
       (let [value (read-object-value serializer object)]
         (cond
           (db-record? value)
           (update analysis :records conj
                   (assoc (db-summary serializer object value)
                          :datahike.record/value value))

           (instance? ANode value)
           (update analysis :nodes conj
                   (assoc object :datahike.node/value value))

           :else analysis)))
     {:records [] :nodes []}
     objects)))

(defn- record-addresses
  [records field]
  (reduce set/union #{} (map field records)))

(defn- reachable-commits
  [commit-by-id head-ids]
  ((decomposition-var 'reachable-commits) commit-by-id head-ids))

(defn- selected-state
  [root-name rows]
  (let [serializer ((decomposition-var 'datahike-serializer))
        objects (object-headers root-name)
        analysis (analyze-objects-with-values serializer objects)
        records (:records analysis)
        commits (filterv #(= :commit (:datahike.record/kind %)) records)
        heads (filterv #(= :branch-head (:datahike.record/kind %)) records)
        commit-by-id (into {} (map (juxt :datahike.record/key identity)) commits)
        head-by-key (into {} (map (juxt :datahike.record/key identity)) heads)
        node-by-address
        (into {} (map (juxt :konserve.object/key identity)) (:nodes analysis))
        branch-keys
        (into #{}
              (mapcat (fn [row]
                        [(keyword (:seon.eval.drive/cluster row))
                         (keyword (:seon.eval.drive/grading-branch row))]))
              rows)
        sample-chains
        (mapv (fn [row]
                (reachable-commits
                 commit-by-id
                 (keep (comp :datahike.record/commit-id head-by-key)
                       [(keyword (:seon.eval.drive/cluster row))
                        (keyword (:seon.eval.drive/grading-branch row))])))
              rows)
        base-commit-ids (reduce set/intersection sample-chains)
        selected-commit-ids
        (set/difference (reduce set/union #{} sample-chains)
                        base-commit-ids)
        base-records (keep commit-by-id base-commit-ids)
        selected-records (vec (keep commit-by-id selected-commit-ids))
        selected-heads (vec (keep head-by-key branch-keys))
        base-max-tx (apply max (map :datahike.record/max-tx base-records))
        base-addresses
        (set/union
         (record-addresses base-records :datahike.record/current-addresses)
         (record-addresses base-records :datahike.record/temporal-addresses))
        current-addresses
        (set/difference
         (record-addresses selected-records
                           :datahike.record/current-addresses)
         base-addresses)
        temporal-addresses
        (set/difference
         (record-addresses selected-records
                           :datahike.record/temporal-addresses)
         base-addresses)
        selected-addresses (set/union current-addresses temporal-addresses)
        current-only (set/difference current-addresses temporal-addresses)
        temporal-only (set/difference temporal-addresses current-addresses)
        shared (set/intersection current-addresses temporal-addresses)
        live-current-addresses
        (set/intersection
         selected-addresses
         (record-addresses selected-heads
                           :datahike.record/current-addresses))
        live-temporal-addresses
        (set/intersection
         selected-addresses
         (record-addresses selected-heads
                           :datahike.record/temporal-addresses))]
    {:root-name root-name
     :serializer serializer
     :objects objects
     :analysis analysis
     :commit-by-id commit-by-id
     :head-by-key head-by-key
     :node-by-address node-by-address
     :rows rows
     :base-commit-ids base-commit-ids
     :base-max-tx base-max-tx
     :selected-commit-ids selected-commit-ids
     :selected-records selected-records
     :selected-heads selected-heads
     :selected-addresses selected-addresses
     :current-only current-only
     :temporal-only temporal-only
     :shared shared
     :live-current-addresses live-current-addresses
     :live-temporal-addresses live-temporal-addresses}))

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

(defn- roots-datoms
  [record fields]
  (mapcat node-datoms (keep (:datahike.record/value record) fields)))

(defn- origin
  [base-max-tx value]
  (if (> (:tx value) base-max-tx) :sample-generated :inherited))

(defn- byte-allocation
  "Allocate one exact physical-byte pool by same-codec datom weights.

  The allocation is exact in total, deterministic, and attributes framing to
  the datoms that require the object. It does not claim byte-address identity."
  [serialized-size serializer base-max-tx byte-count datoms]
  (let [weights
        (reduce
         (fn [result value]
           (update result [(:a value) (origin base-max-tx value)]
                   (fnil + 0)
                   (serialized-size serializer value)))
         {}
         datoms)
        total-weight (reduce + 0 (vals weights))]
    (if (zero? total-weight)
      {[:storage/index-structure :structural] byte-count}
      (let [floors
            (into {}
                  (map (fn [[allocation-key weight]]
                         [allocation-key
                          (quot (* byte-count weight) total-weight)]))
                  weights)
            remainder (- byte-count (reduce + 0 (vals floors)))
            order
            (sort-by
             (fn [[allocation-key weight]]
               [(- (mod (* byte-count weight) total-weight))
                (str allocation-key)])
             weights)]
        (reduce (fn [result [allocation-key _]]
                  (update result allocation-key inc))
                floors
                (take remainder order))))))

(defn- add-pool
  [totals serialized-size serializer base-max-tx role family byte-count datoms]
  (reduce-kv
   (fn [result [attribute value-origin] byte-total]
     (update result [attribute value-origin role family]
             (fnil + 0) byte-total))
   totals
   (byte-allocation serialized-size serializer base-max-tx byte-count datoms)))

(def current-root-fields
  [:eavt-root :aevt-root :avet-root])

(def temporal-root-fields
  [:temporal-eavt-root :temporal-aevt-root :temporal-avet-root])

(defn- record-allocation
  [totals state role record]
  (let [{:keys [serializer base-max-tx]} state
        serialized-size serialized-datom-size
        totals
        (update totals
                [:storage/record-envelope :structural role :envelope]
                (fnil + 0)
                (:datahike.record/envelope-bytes record))
        totals
        (add-pool totals serialized-size serializer base-max-tx role :current
                  (:datahike.record/current-fused-index-bytes record)
                  (roots-datoms record current-root-fields))]
    (add-pool totals serialized-size serializer base-max-tx role :history
              (:datahike.record/temporal-fused-index-bytes record)
              (roots-datoms record temporal-root-fields))))

(defn- node-family
  [state address]
  (cond
    (contains? (:temporal-only state) address) :history
    (contains? (:current-only state) address) :current
    (contains? (:shared state) address) :current-shared
    :else :structure))

(defn- node-role
  [state address family]
  (cond
    (= :history family)
    (if (contains? (:live-temporal-addresses state) address)
      :live :snapshot)

    (contains? #{:current :current-shared} family)
    (if (contains? (:live-current-addresses state) address)
      :live :snapshot)

    :else :snapshot))

(defn- state-allocation
  [state]
  (let [{:keys [serializer base-max-tx node-by-address]} state
        serialized-size serialized-datom-size
        with-records
        (-> (reduce #(record-allocation %1 state :snapshot %2)
                    {}
                    (:selected-records state))
            (as-> totals
                (reduce #(record-allocation %1 state :live %2)
                        totals
                        (:selected-heads state))))]
    (reduce
     (fn [totals address]
       (let [node (get node-by-address address)
             family (node-family state address)
             role (node-role state address family)]
         (if node
           (add-pool totals serialized-size serializer base-max-tx role family
                     (:konserve.object/total-bytes node)
                     (node-datoms (:datahike.node/value node)))
           (update totals
                   [:storage/missing-node :structural role :structure]
                   (fnil + 0)
                   0))))
     with-records
     (:selected-addresses state))))

(defn- combine-allocations
  [allocations]
  (apply merge-with + allocations))

(defn- summarize-attribute
  [sample-count [[attribute value-origin] entries]]
  (let [by-role-family
        (into {}
              (map (fn [[[role family] byte-total]]
                     [[role family] byte-total]))
              entries)
        current-bytes
        (reduce + 0 (for [[[_role family] byte-total] entries
                          :when (contains? #{:current :current-shared} family)]
                      byte-total))
        history-bytes
        (reduce + 0 (for [[[_role family] byte-total] entries
                          :when (= :history family)]
                      byte-total))
        live-bytes
        (reduce + 0 (for [[[role _family] byte-total] entries
                          :when (= :live role)]
                      byte-total))
        snapshot-bytes
        (reduce + 0 (for [[[role _family] byte-total] entries
                          :when (= :snapshot role)]
                      byte-total))
        total (+ current-bytes history-bytes
                 (get by-role-family [:live :envelope] 0)
                 (get by-role-family [:snapshot :envelope] 0)
                 (get by-role-family [:live :structure] 0)
                 (get by-role-family [:snapshot :structure] 0))]
    {:attribute attribute
     :origin value-origin
     :bytes total
     :bytes-per-sample (/ total (double sample-count))
     :current-bytes current-bytes
     :history-bytes history-bytes
     :live-bytes live-bytes
     :snapshot-bytes snapshot-bytes
     :live-current-bytes (get by-role-family [:live :current] 0)
     :live-history-bytes (get by-role-family [:live :history] 0)
     :snapshot-current-bytes (get by-role-family [:snapshot :current] 0)
     :snapshot-history-bytes (get by-role-family [:snapshot :history] 0)}))

(defn- ranked-census
  [sample-count allocation]
  (->> allocation
       (group-by (fn [[[attribute value-origin _ _] _]]
                   [attribute value-origin]))
       (map (fn [[attribute-origin entries]]
              (summarize-attribute
               sample-count
               [attribute-origin
                (map (fn [[[_attribute _origin role family] byte-total]]
                       [[role family] byte-total])
                     entries)])))
       (sort-by (juxt (comp - :bytes) (comp str :attribute) :origin))
       vec))

(defn- child-addresses
  [node]
  (if (instance? Branch node)
    (seq (.addresses ^Branch node))
    []))

(defn- tree-datoms
  [node-by-address root]
  (loop [nodes [root]
         seen #{}
         result []]
    (if-let [node (peek nodes)]
      (let [nodes (pop nodes)
            addresses (remove seen (child-addresses node))
            children (keep (comp :datahike.node/value node-by-address) addresses)]
        (recur (into nodes children)
               (into seen addresses)
               (into result (node-datoms node))))
      (distinct result))))

(defn- string-stats
  [stats value]
  (if-not (string? (:v value))
    stats
    (let [text ^String (:v value)
          character-count (count text)
          utf8 (alength (.getBytes text StandardCharsets/UTF_8))]
      (-> stats
          (update :datoms (fnil inc 0))
          (update :characters (fnil + 0) character-count)
          (update :utf8-bytes (fnil + 0) utf8)
          (update :maximum-characters (fnil max 0) character-count)
          (update :over-4096 (fnil + 0)
                  (if (> character-count 4096) 1 0))
          (update :over-65536 (fnil + 0)
                  (if (> character-count 65536) 1 0))))))

(defn- sample-head-string-census
  [state]
  (reduce
   (fn [totals row]
     (let [head (or (get (:head-by-key state)
                         (keyword (:seon.eval.drive/cluster row)))
                    (get (:head-by-key state)
                         (keyword (:seon.eval.drive/grading-branch row))))
           root (get-in head [:datahike.record/value :eavt-root])]
       (reduce
        (fn [result value]
          (if (and (> (:tx value) (:base-max-tx state))
                   (string? (:v value)))
            (update result (:a value) string-stats value)
            result))
        totals
        (tree-datoms (:node-by-address state) root))))
   {}
   (:rows state)))

(defn- sample-fork-evidence
  [state row]
  (let [sample-key (keyword (:seon.eval.drive/cluster row))
        grade-key (keyword (:seon.eval.drive/grading-branch row))
        sample-head (get (:head-by-key state) sample-key)
        grade-head (get (:head-by-key state) grade-key)
        selected (:selected-commit-ids state)
        boundary-parents
        (into #{}
              (comp
               (mapcat #(get-in (get (:commit-by-id state) %)
                                [:datahike.record/parents]))
               (filter (:base-commit-ids state)))
              selected)]
    {:inspect-sample-id (:inspect/sample-id row)
     :seon-sample-id (:seon.eval.drive/sample-id row)
     :sample-branch sample-key
     :grading-branch grade-key
     :fork-parent-commit-ids (sort boundary-parents)
     :fork-parent-max-tx (:base-max-tx state)
     :archive-ending-commit (:seon.eval.drive/ending-commit row)
     :sample-head-commit-id (:datahike.record/commit-id sample-head)
     :sample-head-max-tx (:datahike.record/max-tx sample-head)
     :grading-head-commit-id (:datahike.record/commit-id grade-head)
     :grading-head-max-tx (:datahike.record/max-tx grade-head)}))

(defn- physical-bytes
  [allocation]
  (reduce + 0 (vals allocation)))

(defn- merge-string-stats
  [left right]
  {:datoms (+ (get left :datoms 0) (get right :datoms 0))
   :characters (+ (get left :characters 0) (get right :characters 0))
   :utf8-bytes (+ (get left :utf8-bytes 0) (get right :utf8-bytes 0))
   :maximum-characters
   (max (get left :maximum-characters 0)
        (get right :maximum-characters 0))
   :over-4096 (+ (get left :over-4096 0) (get right :over-4096 0))
   :over-65536 (+ (get left :over-65536 0) (get right :over-65536 0))})

(defn- merge-string-censuses
  [censuses]
  (apply merge-with merge-string-stats censuses))

(defn- archive-config-census
  [samples]
  (let [attempts
        (mapcat #(get-in % [:output :metadata :seon_episode
                            :seon.eval.drive/model-attempts])
                samples)
        settings
        (keep (fn [attempt]
                (some-> (:seon.ai.attempt/settings-edn attempt)
                        edn/read-string))
              attempts)]
    {:attempts (count attempts)
     :settings-rows (count settings)
     :blob-thresholds
     (frequencies (map :seon.config.eval.result/blob-threshold settings))
     :thinking-settings
     (frequencies (map :seon.config.ai/thinking settings))
     :attempts-with-reasoning
     (count (filter #(or (seq (:seon.ai.attempt/reasoning %))
                         (:seon.ai.attempt/reasoning-blob %))
                    attempts))}))

(defn -main
  "Print the exact-total, codec-weighted per-attribute census as EDN."
  [& _]
  (let [archive-samples (decomposition-var 'archive-samples)
        sample-row (decomposition-var 'sample-row)
        samples (archive-samples)
        rows
        (mapv (fn [sample]
                (assoc (sample-row sample)
                       :seon.eval.drive/ending-commit
                       (get-in sample [:output :metadata :seon_episode
                                       :seon.eval.drive/ending-commit])))
              samples)
        states
        (mapv (fn [root-name]
                (selected-state
                 root-name
                 (filterv #(= root-name
                              (:seon.store.growth/root-name %))
                          rows)))
              root-names)
        allocations (mapv state-allocation states)
        allocation (combine-allocations allocations)
        sample-count (count rows)
        census (ranked-census sample-count allocation)
        string-census
        (merge-string-censuses (map sample-head-string-census states))
        one-sample-row (first rows)
        one-sample-state
        (first (filter #(= (:seon.store.growth/root-name one-sample-row)
                           (:root-name %))
                       states))
        result
        {:dependency/ledger
      {:datahike-sha "256b714d97a0e8f952b01a47c693eff2976ccee7"
       :konserve-sha "737697d9205e5e8f0bc08a666e4c97dad55e9dbe"
       :persistent-sorted-set-version "0.4.137"
       :record-writer
       "reference-code/datahike/src/datahike/writing.cljc:48-180,477-552"
       :index-codec
       (str "reference-code/datahike/src/datahike/index/"
            "persistent_set.cljc:526-566")
       :node-codec
       (str "reference-code/persistent-sorted-set/src-clojure/org/"
            "replikativ/persistent_sorted_set/fressian.cljc")}
      :artifact/source-roots
      (mapv #(str source-root-prefix "/" %) root-names)
      :artifact/copied-roots
      (mapv #(str copied-root-prefix "/" (copied-root-name %)) root-names)
      :artifact/source-root-logical-bytes
      (into {}
            (map (fn [root-name]
                   [root-name
                    (logical-file-bytes
                     (str source-root-prefix "/" root-name))]))
            root-names)
      :artifact/copied-root-logical-bytes
      (into {}
            (map (fn [root-name]
                   [root-name
                    (logical-file-bytes
                     (str copied-root-prefix "/"
                          (copied-root-name root-name)))]))
            root-names)
      :eval/sample-count sample-count
      :eval/physical-bytes (physical-bytes allocation)
      :eval/physical-bytes-per-sample
      (/ (physical-bytes allocation) (double sample-count))
      :eval/sample-fork-evidence
      (sample-fork-evidence one-sample-state one-sample-row)
      :eval/census census
      :eval/sample-head-string-census
      (->> string-census
           (map (fn [[attribute stats]] (assoc stats :attribute attribute)))
           (sort-by (juxt (comp - :utf8-bytes) (comp str :attribute)))
           vec)
      :eval/archive-config-census (archive-config-census samples)
      :eval/root-evidence
      (mapv (fn [state allocation]
              {:root-name (:root-name state)
               :samples (count (:rows state))
               :base-max-tx (:base-max-tx state)
               :selected-commits (count (:selected-commit-ids state))
               :selected-heads (count (:selected-heads state))
               :selected-nodes (count (:selected-addresses state))
               :shared-current-temporal-nodes (count (:shared state))
               :physical-bytes (physical-bytes allocation)})
            states allocations)}
        output-path (System/getenv "SEON_CENSUS_OUTPUT")]
    (if output-path
      (do
        (spit output-path (pr-str result))
        (prn {:output output-path
              :samples sample-count
              :physical-bytes (:eval/physical-bytes result)
              :physical-bytes-per-sample
              (:eval/physical-bytes-per-sample result)
              :attributes (count census)
              :string-attributes (count string-census)}))
      (prn result)))
  (shutdown-agents))

(apply -main *command-line-args*)
