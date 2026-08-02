#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns eval-sample-store-decomposition-2026-08-02
  "Read-only decomposition of the preserved 2026-08-02 GPQA eval stores."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [datahike.datom :as datom]
            [datahike.index.persistent-set]
            [konserve.impl.storage-layout :as storage-layout]
            [konserve.protocols :as konserve.protocols]
            [konserve.serializers :as serializers]
            [org.replikativ.persistent-sorted-set.fressian :as pss-fress])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path Paths]
           [org.replikativ.persistent_sorted_set ANode Branch]
           [java.util UUID]))

(def run-directory
  "evals/runs/2026-08-02-gpqa-seon-full-198")

(def eval-archive
  (str run-directory
       "/2026-08-02T13-10-48-00-00_gpqa-diamond_"
       "EMVtW2ePz78tqpCwZEo5sk.eval"))

(def preserved-root-prefix
  "tmp/inspect-ai-head-c6d79f817/tmp/inspect-ai")

(def root-names
  ["333214a0f358" "d0cd8fbc1fa3"])

(defn- archive-samples
  []
  ;; Inspect writes method-93 (Zstandard) ZIP members. Python 3.14's stdlib
  ;; can read them; the JDK 26 ZipFile shipped on the same machine cannot.
  ;; Project only the fields this analysis needs and return JSON over stdout,
  ;; leaving the preserved archive byte-for-byte untouched.
  (let [program (str
                 "import json,sys,zipfile\n"
                 "with zipfile.ZipFile(sys.argv[1]) as z:\n"
                 " xs=[]\n"
                 " for n in z.namelist():\n"
                 "  if n.startswith('samples/') and n.endswith('.json'):\n"
                 "   x=json.loads(z.read(n))\n"
                 "   xs.append({'id':x.get('id'),'output':x.get('output')})\n"
                 " print(json.dumps(xs,separators=(',',':')))\n")
        {:keys [exit out err]} (shell/sh "python3" "-c" program eval-archive)]
    (when-not (zero? exit)
      (throw (ex-info "Python could not read the Zstandard eval archive."
                      {:exit exit :stderr err})))
    (json/read-str out :key-fn keyword)))

(defn- sample-episode
  [sample]
  (get-in sample [:output :metadata :seon_episode]))

(defn- sample-row
  [sample]
  (let [episode (sample-episode sample)
        measurement (:seon.store.growth/measurement episode)
        summary-path (:summary_path measurement)]
    {:inspect/sample-id (:id sample)
     :seon.eval.drive/sample-id (:seon.eval.drive/sample-id episode)
     :seon.eval.drive/cluster (:seon.eval.drive/cluster episode)
     :seon.eval.drive/grading-branch
     (:seon.eval.drive/grading-branch episode)
     :seon.eval.drive/transcript-bytes
     (alength (.getBytes ^String (or (:seon.eval.drive/transcript episode) "")
                        StandardCharsets/UTF_8))
     :seon.store.growth/root-name
     (some-> summary-path io/file .getParentFile .getName)
     :seon.store.growth/logical-bytes
     (get-in measurement [:growth :logical_bytes])
     :seon.store.growth/aggregate-logical-bytes
     (get-in measurement [:aggregate_growth_through_sample :logical_bytes])
     :seon.store.growth/overlapping-sample-count
     (count (:overlapping_sample_ids measurement))}))

(defn- path
  [& parts]
  (Paths/get (first parts) (into-array String (rest parts))))

(defn- regular-files
  [directory]
  (with-open [stream (Files/walk (path directory)
                                 (into-array java.nio.file.FileVisitOption []))]
    (->> (iterator-seq (.iterator stream))
         (filter #(Files/isRegularFile
                   ^Path %
                   (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
         vec)))

(defn- read-exactly
  [input size]
  (let [octets (.readNBytes input (int size))]
    (when-not (= size (alength octets))
      (throw (ex-info "Short Konserve object read."
                      {:expected size :actual (alength octets)})))
    octets))

(defn- deserialize
  [serializer octets]
  (with-open [input (ByteArrayInputStream. octets)]
    (konserve.protocols/-deserialize serializer (atom {}) input)))

(defn- read-object-header
  [^Path object-path]
  (with-open [input (Files/newInputStream object-path
                                          (make-array java.nio.file.OpenOption 0))]
    (let [header (read-exactly input storage-layout/header-size)
          serializer-id (bit-and (aget header 1) 0xff)
          serializer (get serializers/byte->serializer serializer-id)
          meta-size (storage-layout/read-meta-size header)
          metadata (deserialize serializer (read-exactly input meta-size))
          total-bytes (Files/size object-path)]
      {:konserve.object/path (str object-path)
       :konserve.object/total-bytes total-bytes
       :konserve.object/header-bytes storage-layout/header-size
       :konserve.object/meta-bytes meta-size
       :konserve.object/value-bytes
       (- total-bytes storage-layout/header-size meta-size)
       :konserve.object/key (:key metadata)
       :konserve.object/value-type (:type metadata)
       :konserve.object/immutable? (:immutable? metadata)})))

(defn- private-value
  [namespace-symbol value-symbol]
  (var-get (ns-resolve namespace-symbol value-symbol)))

(declare key-shape)

(defn- datahike-serializer
  []
  (let [node-read-handlers (pss-fress/read-handlers {:default-bf 512})
        root-read-handler
        (pss-fress/root-read-handler
         {:resolve-storage (constantly nil)
          :resolve-cmp
          (fn [metadata]
            (datom/index-type->cmp-quick (:index-type metadata) false))
          :default-bf 512})
        read-handlers
        (merge node-read-handlers
               {pss-fress/set-tag root-read-handler}
               (private-value 'datahike.index.persistent-set
                              'datom-read-handler)
               {"datahike.index.PersistentSortedSet" root-read-handler
                "datahike.index.PersistentSortedSet.Leaf"
                (get node-read-handlers pss-fress/leaf-tag)
                "datahike.index.PersistentSortedSet.Branch"
                (get node-read-handlers pss-fress/branch-tag)})
        write-handlers
        (merge pss-fress/write-handlers
               pss-fress/root-write-handlers
               (private-value 'datahike.index.persistent-set
                              'datom-write-handler))]
    (serializers/fressian-serializer read-handlers write-handlers)))

(defn- read-object-value
  [serializer object]
  (let [object-path (path (:konserve.object/path object))
        offset (+ (:konserve.object/header-bytes object)
                  (:konserve.object/meta-bytes object))]
    (with-open [input (Files/newInputStream
                       object-path
                       (make-array java.nio.file.OpenOption 0))]
      (.skipNBytes input offset)
      (deserialize serializer
                   (read-exactly input (:konserve.object/value-bytes object))))))

(defn- serialized-size
  [serializer value]
  (with-open [output (ByteArrayOutputStream.)]
    (konserve.protocols/-serialize serializer output (atom {}) value)
    (.size output)))

(def current-root-fields
  [:eavt-root :aevt-root :avet-root])

(def temporal-root-fields
  [:temporal-eavt-root :temporal-aevt-root :temporal-avet-root])

(def current-index-fields
  [:eavt-key :aevt-key :avet-key :eavt-root :aevt-root :avet-root])

(def temporal-index-fields
  [:temporal-eavt-key :temporal-aevt-key :temporal-avet-key
   :temporal-eavt-root :temporal-aevt-root :temporal-avet-root])

(def merkle-root-fields
  [:eavt-key :aevt-key :avet-key
   :temporal-eavt-key :temporal-aevt-key :temporal-avet-key])

(defn- db-record?
  [value]
  (and (map? value)
       (contains? value :schema-meta-key)
       (contains? value :max-tx)
       (contains? value :eavt-key)))

(defn- without-history
  [record]
  (-> (apply dissoc record temporal-index-fields)
      (update :merkle-roots #(apply dissoc % (drop 3 merkle-root-fields)))
      (assoc-in [:config :keep-history?] false)))

(defn- without-indexes
  [record]
  (-> (apply dissoc record (concat current-index-fields temporal-index-fields))
      (assoc :merkle-roots {})))

(defn- root-addresses
  [record root-fields]
  (into #{}
        (mapcat (fn [field]
                  (let [root (get record field)]
                    (if (instance? Branch root)
                      (.addresses ^Branch root)
                      []))))
        root-fields))

(defn- datom-stats
  [node]
  (if-not (instance? ANode node)
    {:datoms 0 :string-value-bytes 0}
    (reduce
     (fn [totals value]
       (if (datom/datom? value)
         (-> totals
             (update :datoms inc)
             (update :string-value-bytes
                     +
                     (if (string? (:v value))
                       (alength (.getBytes ^String (:v value)
                                          StandardCharsets/UTF_8))
                       0)))
         totals))
     {:datoms 0 :string-value-bytes 0}
     (tree-seq
      (fn [value]
        (and (not (datom/datom? value))
             (coll? value)))
      (fn [value]
        (cond
          (map? value) (mapcat identity value)
          (coll? value) (seq value)
          :else nil))
      (pss-fress/node->map node)))))

(defn- combined-datom-stats
  [record root-fields]
  (reduce
   (fn [totals root]
     (merge-with + totals (datom-stats root)))
   {:datoms 0 :string-value-bytes 0}
   (keep record root-fields)))

(defn- db-summary
  [serializer object record]
  (let [actual-size (serialized-size serializer record)
        no-history-size (serialized-size serializer (without-history record))
        envelope-size (serialized-size serializer (without-indexes record))
        file-overhead (+ (:konserve.object/header-bytes object)
                         (:konserve.object/meta-bytes object))]
    {:datahike.record/key (:konserve.object/key object)
     :datahike.record/kind
     (if (keyword? (:konserve.object/key object)) :branch-head :commit)
     :datahike.record/commit-id (get-in record [:meta :datahike/commit-id])
     :datahike.record/parents (set (get-in record [:meta :datahike/parents]))
     :datahike.record/max-tx (:max-tx record)
     :datahike.record/total-bytes (:konserve.object/total-bytes object)
     :datahike.record/value-bytes (:konserve.object/value-bytes object)
     :datahike.record/reserialized-bytes actual-size
     :datahike.record/reserialization-match?
     (= actual-size (:konserve.object/value-bytes object))
     :datahike.record/envelope-bytes (+ file-overhead envelope-size)
     :datahike.record/current-fused-index-bytes
     (- no-history-size envelope-size)
     :datahike.record/temporal-fused-index-bytes
     (- actual-size no-history-size)
     :datahike.record/current-addresses
     (root-addresses record current-root-fields)
     :datahike.record/temporal-addresses
     (root-addresses record temporal-root-fields)
     :datahike.record/current-datom-stats
     (combined-datom-stats record current-root-fields)
     :datahike.record/temporal-datom-stats
     (combined-datom-stats record temporal-root-fields)}))

(defn- analyze-objects
  [objects]
  (let [serializer (datahike-serializer)]
    (reduce
     (fn [analysis object]
       (let [value (read-object-value serializer object)]
         (cond
           (= :binary (:konserve.object/value-type object))
           (update analysis :binary conj object)

           (db-record? value)
           (update analysis :records conj (db-summary serializer object value))

           (instance? ANode value)
           (update analysis :nodes conj
                   (assoc object :datahike.node/datom-stats
                          (datom-stats value)))

           (and (map? value) (contains? value :schema))
           (update analysis :schema-meta conj object)

           :else
           (update analysis :other conj
                   (assoc object :konserve.object/decoded-class
                          (symbol (.getName (class value))))))))
     {:binary [] :records [] :nodes [] :schema-meta [] :other []}
     objects)))

(defn- root-store-directory
  [root-name]
  (str preserved-root-prefix "/" root-name "/data/clusters/store"))

(defn- object-headers
  [root-name]
  (->> (regular-files (root-store-directory root-name))
       (filter #(.endsWith (str %) ".ksv"))
       (mapv read-object-header)))

(defn- key-shape
  [logical-key]
  (cond
    (keyword? logical-key) :keyword
    (instance? UUID logical-key) :uuid
    (string? logical-key) :string
    (vector? logical-key) :vector
    :else (keyword (.getName (class logical-key)))))

(defn- sum-bytes
  [objects]
  (reduce + 0 (map :konserve.object/total-bytes objects)))

(defn- header-summary
  [root-name objects]
  {:seon.store/root-name root-name
   :konserve.object/count (count objects)
   :konserve.object/bytes (sum-bytes objects)
   :konserve.object/by-value-type
   (into (sorted-map)
         (map (fn [[value-type xs]]
                [value-type {:objects (count xs) :bytes (sum-bytes xs)}]))
         (group-by :konserve.object/value-type objects))
   :konserve.object/by-key-shape
   (into (sorted-map)
         (map (fn [[shape xs]]
                [shape {:objects (count xs) :bytes (sum-bytes xs)}]))
         (group-by (comp key-shape :konserve.object/key) objects))})

(defn- percentile
  [fraction values]
  (when (seq values)
    (let [ordered (vec (sort values))]
      (nth ordered (min (dec (count ordered))
                        (long (Math/floor (* fraction (count ordered)))))))))

(defn- sample-summary
  [rows]
  (let [logical-growth (keep :seon.store.growth/logical-bytes rows)
        transcript-bytes (map :seon.eval.drive/transcript-bytes rows)]
    {:samples (count rows)
     :root-counts (frequencies (map :seon.store.growth/root-name rows))
     :measurement-scope :overlapping-shared-store-interval
     :logical-growth
     {:median (percentile 0.5 logical-growth)
      :mean (when (seq logical-growth)
              (/ (reduce + logical-growth) (double (count logical-growth))))
      :minimum (when (seq logical-growth) (apply min logical-growth))
      :maximum (when (seq logical-growth) (apply max logical-growth))}
     :derived-transcript-bytes
     {:total (reduce + 0 transcript-bytes)
      :median (percentile 0.5 transcript-bytes)
      :mean (/ (reduce + 0 transcript-bytes) (double (count transcript-bytes)))}}))

(defn- inspect-branch?
  [branch]
  (let [branch-name (name branch)]
    (or (.startsWith branch-name "inspect-sample-")
        (.startsWith branch-name "inspect-grade-"))))

(defn- reachable-commits
  [commit-by-id head-ids]
  (loop [pending (seq head-ids)
         reached #{}]
    (if-let [commit-id (first pending)]
      (if (or (nil? commit-id) (contains? reached commit-id))
        (recur (next pending) reached)
        (let [parent-ids (:datahike.record/parents
                          (get commit-by-id commit-id))]
          (recur (concat (next pending) parent-ids)
                 (conj reached commit-id))))
      reached)))

(defn- record-addresses
  [records field]
  (reduce set/union #{} (map field records)))

(defn- sum-field
  [field rows]
  (reduce + 0 (map field rows)))

(defn- bytes-at-addresses
  [node-by-address addresses]
  (reduce + 0
          (keep (fn [address]
                  (:konserve.object/total-bytes
                   (get node-by-address address)))
                addresses)))

(defn- datom-stats-at-addresses
  [node-by-address addresses]
  (reduce
   (fn [totals address]
     (merge-with + totals
                 (get-in node-by-address
                         [address :datahike.node/datom-stats]
                         {:datoms 0 :string-value-bytes 0})))
   {:datoms 0 :string-value-bytes 0}
   addresses))

(defn- records-for
  [commit-by-id commit-ids]
  (keep commit-by-id commit-ids))

(defn- record-buckets
  [records]
  {:records (count records)
   :bytes (sum-field :datahike.record/total-bytes records)
   :envelope-bytes (sum-field :datahike.record/envelope-bytes records)
   :current-fused-index-bytes
   (sum-field :datahike.record/current-fused-index-bytes records)
   :temporal-fused-index-bytes
   (sum-field :datahike.record/temporal-fused-index-bytes records)
   :reserialization-mismatches
   (count (remove :datahike.record/reserialization-match? records))
   :current-inline-datom-stats
   (reduce #(merge-with + %1 %2)
           {:datoms 0 :string-value-bytes 0}
           (map :datahike.record/current-datom-stats records))
   :temporal-inline-datom-stats
   (reduce #(merge-with + %1 %2)
           {:datoms 0 :string-value-bytes 0}
           (map :datahike.record/temporal-datom-stats records))})

(defn- distribution
  [values]
  {:samples (count values)
   :minimum (when (seq values) (apply min values))
   :median (percentile 0.5 values)
   :mean (when (seq values)
           (/ (reduce + 0 values) (double (count values))))
   :maximum (when (seq values) (apply max values))})

(defn- root-growth
  [root-name]
  (json/read-str
   (slurp (str preserved-root-prefix "/" root-name "/store-growth.json"))
   :key-fn keyword))

(defn- analyze-root
  [root-name sample-rows]
  (let [objects (object-headers root-name)
        analysis (analyze-objects objects)
        records (:records analysis)
        commit-records (filter #(= :commit (:datahike.record/kind %)) records)
        branch-records (filter #(= :branch-head (:datahike.record/kind %)) records)
        commit-by-id (into {} (map (juxt :datahike.record/key identity)) commit-records)
        branch-by-key (into {} (map (juxt :datahike.record/key identity)) branch-records)
        node-by-address
        (into {} (map (juxt :konserve.object/key identity)) (:nodes analysis))
        sample-branch-keys
        (mapv (comp keyword :seon.eval.drive/cluster) sample-rows)
        selected-branch-keys
        (into (set sample-branch-keys)
              (map (comp keyword :seon.eval.drive/grading-branch))
              sample-rows)
        sample-chains
        (mapv (fn [row]
                (reachable-commits
                 commit-by-id
                 (keep (comp :datahike.record/commit-id branch-by-key)
                       [(keyword (:seon.eval.drive/cluster row))
                        (keyword
                         (:seon.eval.drive/grading-branch row))])))
              sample-rows)
        base-commit-ids (if (seq sample-chains)
                          (reduce set/intersection sample-chains)
                          #{})
        selected-reachable (reduce set/union #{} sample-chains)
        selected-commit-ids (set/difference selected-reachable base-commit-ids)
        all-inspect-branch-keys
        (into #{} (comp (map :datahike.record/key) (filter inspect-branch?))
              branch-records)
        extra-branch-keys
        (set/difference all-inspect-branch-keys selected-branch-keys)
        extra-reachable
        (reachable-commits
         commit-by-id
         (keep (comp :datahike.record/commit-id branch-by-key)
               extra-branch-keys))
        extra-commit-ids
        (set/difference extra-reachable selected-reachable base-commit-ids)
        base-records (records-for commit-by-id base-commit-ids)
        selected-records (vec (records-for commit-by-id selected-commit-ids))
        extra-records (vec (records-for commit-by-id extra-commit-ids))
        selected-heads (vec (keep branch-by-key selected-branch-keys))
        extra-heads (vec (keep branch-by-key extra-branch-keys))
        base-addresses
        (set/union
         (record-addresses base-records :datahike.record/current-addresses)
         (record-addresses base-records :datahike.record/temporal-addresses))
        selected-current-addresses
        (set/difference
         (record-addresses selected-records :datahike.record/current-addresses)
         base-addresses)
        selected-temporal-addresses
        (set/difference
         (record-addresses selected-records :datahike.record/temporal-addresses)
         base-addresses)
        selected-addresses
        (set/union selected-current-addresses selected-temporal-addresses)
        selected-current-only
        (set/difference selected-current-addresses selected-temporal-addresses)
        selected-temporal-only
        (set/difference selected-temporal-addresses selected-current-addresses)
        selected-shared
        (set/intersection selected-current-addresses selected-temporal-addresses)
        extra-addresses
        (set/difference
         (set/union
          (record-addresses extra-records :datahike.record/current-addresses)
          (record-addresses extra-records :datahike.record/temporal-addresses))
         base-addresses selected-addresses)
        selected-node-bytes (bytes-at-addresses node-by-address selected-addresses)
        selected-record-buckets (record-buckets selected-records)
        selected-head-buckets (record-buckets selected-heads)
        selected-physical-bytes
        (+ (:bytes selected-record-buckets)
           (:bytes selected-head-buckets)
           selected-node-bytes)
        history-removable-bytes
        (+ (:temporal-fused-index-bytes selected-record-buckets)
           (:temporal-fused-index-bytes selected-head-buckets)
           (bytes-at-addresses node-by-address selected-temporal-only))
        extra-physical-bytes
        (+ (sum-field :datahike.record/total-bytes extra-records)
           (sum-field :datahike.record/total-bytes extra-heads)
           (bytes-at-addresses node-by-address extra-addresses))
        growth (root-growth root-name)
        aggregate-growth (get-in growth [:aggregate_growth :logical_bytes])
        commits-per-sample
        (mapv #(count (set/difference % base-commit-ids)) sample-chains)]
    {:seon.store/root-name root-name
     :eval/samples (count sample-rows)
     :eval/transaction-count (distribution commits-per-sample)
     :eval/selected-commit-records selected-record-buckets
     :eval/selected-branch-heads selected-head-buckets
     :eval/selected-index-nodes
     {:objects (count selected-addresses)
      :bytes selected-node-bytes
      :current-only
      {:objects (count selected-current-only)
       :bytes (bytes-at-addresses node-by-address selected-current-only)
       :datom-stats (datom-stats-at-addresses
                     node-by-address selected-current-only)}
      :temporal-only
      {:objects (count selected-temporal-only)
       :bytes (bytes-at-addresses node-by-address selected-temporal-only)
       :datom-stats (datom-stats-at-addresses
                     node-by-address selected-temporal-only)}
      :shared-current-and-temporal
      {:objects (count selected-shared)
       :bytes (bytes-at-addresses node-by-address selected-shared)
       :datom-stats (datom-stats-at-addresses node-by-address selected-shared)}
      :missing-addresses
      (count (remove node-by-address selected-addresses))}
     :eval/physical-reconstruction
     {:bytes selected-physical-bytes
      :per-sample-bytes (/ selected-physical-bytes
                           (double (count sample-rows)))
      :history-removable-bytes history-removable-bytes
      :history-removable-fraction
      (/ history-removable-bytes (double selected-physical-bytes))
      :history-off-counterfactual-bytes
      (- selected-physical-bytes history-removable-bytes)}
     :artifact/whole-root
     {:summary-completed-samples (:completed_samples growth)
      :baseline-bytes (get-in growth [:baseline :logical_bytes])
      :aggregate-growth-bytes aggregate-growth
      :latest-bytes (get-in growth [:latest :logical_bytes])
      :extra-inspect-branches (count extra-branch-keys)
      :extra-commit-records (count extra-records)
      :extra-reconstructed-bytes extra-physical-bytes
      :growth-not-reconstructed
      (- aggregate-growth selected-physical-bytes extra-physical-bytes)}
     :artifact/object-inventory
     (assoc (header-summary root-name objects)
            :db-records (count records)
            :index-nodes (count (:nodes analysis))
            :schema-meta (count (:schema-meta analysis))
            :binary-blobs (count (:binary analysis))
            :other (mapv #(select-keys % [:konserve.object/key
                                          :konserve.object/total-bytes
                                          :konserve.object/decoded-class])
                         (:other analysis)))}))

(defn -main
  "Print the read-only byte decomposition as one EDN value."
  [& _]
  (let [samples (mapv sample-row (archive-samples))
        roots (mapv (fn [root-name]
                      (analyze-root
                       root-name
                       (filterv #(= root-name
                                    (:seon.store.growth/root-name %))
                                samples)))
                    root-names)
        selected-physical-bytes
        (sum-field #(get-in % [:eval/physical-reconstruction :bytes]) roots)
        history-removable-bytes
        (sum-field #(get-in % [:eval/physical-reconstruction
                               :history-removable-bytes])
                   roots)
        selected-commit-records
        (sum-field #(get-in % [:eval/selected-commit-records :records]) roots)
        selected-commit-bytes
        (sum-field #(get-in % [:eval/selected-commit-records :bytes]) roots)
        selected-branch-head-bytes
        (sum-field #(get-in % [:eval/selected-branch-heads :bytes]) roots)
        selected-node-bytes
        (sum-field #(get-in % [:eval/selected-index-nodes :bytes]) roots)
        commit-envelope-bytes
        (sum-field #(get-in % [:eval/selected-commit-records
                               :envelope-bytes])
                   roots)
        branch-envelope-bytes
        (sum-field #(get-in % [:eval/selected-branch-heads
                               :envelope-bytes])
                   roots)
        current-fused-bytes
        (reduce + 0
                (mapcat
                 (fn [root]
                   [(get-in root [:eval/selected-commit-records
                                  :current-fused-index-bytes])
                    (get-in root [:eval/selected-branch-heads
                                  :current-fused-index-bytes])])
                 roots))
        temporal-fused-bytes
        (reduce + 0
                (mapcat
                 (fn [root]
                   [(get-in root [:eval/selected-commit-records
                                  :temporal-fused-index-bytes])
                    (get-in root [:eval/selected-branch-heads
                                  :temporal-fused-index-bytes])])
                 roots))
        current-only-node-bytes
        (sum-field #(get-in % [:eval/selected-index-nodes
                               :current-only :bytes])
                   roots)
        temporal-only-node-bytes
        (sum-field #(get-in % [:eval/selected-index-nodes
                               :temporal-only :bytes])
                   roots)
        shared-node-bytes
        (sum-field #(get-in % [:eval/selected-index-nodes
                               :shared-current-and-temporal :bytes])
                   roots)
        transcript-bytes
        (reduce + 0 (map :seon.eval.drive/transcript-bytes samples))
        extra-reconstructed-bytes
        (sum-field #(get-in % [:artifact/whole-root
                               :extra-reconstructed-bytes])
                   roots)
        aggregate-growth-bytes
        (sum-field #(get-in % [:artifact/whole-root
                               :aggregate-growth-bytes])
                   roots)]
    (prn {:dependency/ledger
          {:datahike-sha "256b714d97a0e8f952b01a47c693eff2976ccee7"
           :konserve-sha "737697d9205e5e8f0bc08a666e4c97dad55e9dbe"
           :datahike-record-write
           "reference-code/datahike/src/datahike/writing.cljc:479-552"
           :datahike-index-codec
           (str "reference-code/datahike/src/datahike/index/"
                "persistent_set.cljc:526-566")
           :konserve-layout
           "reference-code/konserve/src/konserve/impl/storage_layout.cljc:10-72"
           :konserve-filename
           "reference-code/konserve/src/konserve/impl/defaults.cljc:44-45"
           :blob-write "src/seon/blob.clj:20-30"}
          :artifact/eval-archive eval-archive
          :artifact/preserved-roots
          (mapv #(str preserved-root-prefix "/" %) root-names)
          :eval/sample-summary (sample-summary samples)
          :eval/selected-198-decomposition
          {:physical-bytes selected-physical-bytes
           :per-sample-physical-bytes
           (/ selected-physical-bytes (double (count samples)))
           :transactions selected-commit-records
           :transactions-per-sample
           (/ selected-commit-records (double (count samples)))
           :commit-records
           {:bytes selected-commit-bytes
            :envelope-bytes commit-envelope-bytes}
           :branch-head-records
           {:bytes selected-branch-head-bytes
            :envelope-bytes branch-envelope-bytes}
           :fused-index-roots
           {:current-bytes current-fused-bytes
            :temporal-bytes temporal-fused-bytes}
           :persistent-index-nodes
           {:bytes selected-node-bytes
            :current-only-bytes current-only-node-bytes
            :temporal-only-bytes temporal-only-node-bytes
            :shared-current-and-temporal-bytes shared-node-bytes}
           :history
           {:removable-bytes history-removable-bytes
            :fraction-of-selected-physical
            (/ history-removable-bytes (double selected-physical-bytes))
            :history-off-counterfactual-bytes
            (- selected-physical-bytes history-removable-bytes)}
           :blob-content
           {:konserve-binary-objects 0
            :bytes 0}
           :transcript
           {:archive-output-bytes transcript-bytes
            :fraction-of-selected-physical
            (/ transcript-bytes (double selected-physical-bytes))}}
          :artifact/reconciliation
          {:reported-root-growth-bytes aggregate-growth-bytes
           :selected-198-reconstructed-bytes selected-physical-bytes
           :five-extra-episodes-reconstructed-bytes extra-reconstructed-bytes
           :unreconstructed-bytes
           (- aggregate-growth-bytes
              selected-physical-bytes
              extra-reconstructed-bytes)}
          :store/roots roots}))
  (shutdown-agents))

(when-not (System/getenv "SEON_DECOMPOSITION_LIBRARY")
  (apply -main *command-line-args*))
