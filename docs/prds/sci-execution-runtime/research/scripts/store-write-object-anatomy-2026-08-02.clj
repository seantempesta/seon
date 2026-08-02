;; Enumerate the Konserve objects behind Datahike payload amplification.
;; Run from the repository root with `clojure -M:dev` and this script path.
;; Every database is a fresh file store below the repository's tmp/ directory.
;; The script never opens, deletes, or collects an operator store.

(require '[clojure.java.io :as io]
         '[datahike.api :as d]
         '[datahike.writing :as writing]
         '[konserve.core :as k]
         '[konserve.impl.defaults :as defaults]
         '[org.replikativ.persistent-sorted-set.fressian :as pss-fress])

(import '[datahike.datom Datom]
        '[java.io File]
        '[java.util UUID]
        '[org.replikativ.persistent_sorted_set Branch Leaf PersistentSortedSet])

(def ^:private measured-transactions 40)
(def ^:private training-payload-bytes (* 4 1024))
(def ^:private validation-payload-bytes (* 16 1024))

(def ^:private schema
  [{:db/ident :store.anatomy/k
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :store.anatomy/payload
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn- directory-stats
  [path]
  (let [files (filter #(.isFile ^File %) (file-seq (io/file path)))]
    {:store.anatomy/objects (count files)
     :store.anatomy/bytes (reduce + 0 (map #(.length ^File %) files))}))

(defn- configuration
  [path {:store.anatomy/keys [keep-history? fuse-index-roots? diff-buf-size]}]
  (cond->
   {:store {:backend :file
            :path path
            :id (UUID/nameUUIDFromBytes (.getBytes ^String path "UTF-8"))}
    :writer {:backend :self :commit-wait-time 0}
    :keep-history? keep-history?
    :schema-flexibility :write}
    fuse-index-roots? (assoc :fuse-index-roots? true)
    (some? diff-buf-size) (assoc :index-config {:diff-buf-size diff-buf-size})))

(defn- payload
  [size]
  (apply str (repeat size \x)))

(defn- object-file
  [path store-key]
  (io/file path (defaults/key->store-key store-key)))

(declare payload-occurrences)

(defn- payload-occurrences
  "Count payload-bearing Datoms recursively in one stored value."
  [expected value]
  (cond
    (instance? Datom value)
    (if (= expected (.-v ^Datom value)) 1 0)

    (or (instance? Leaf value) (instance? Branch value))
    (payload-occurrences expected (pss-fress/node->map value))

    ;; A detached PSS serializes only its address/count/config descriptor.
    ;; Walking its Seqable interface would count lazily restored Datoms that
    ;; are not bytes inside this Konserve object.
    (instance? PersistentSortedSet value)
    0

    (map? value)
    (reduce + 0 (map #(payload-occurrences expected %) (vals value)))

    (sequential? value)
    (reduce + 0 (map #(payload-occurrences expected %) value))

    (set? value)
    (reduce + 0 (map #(payload-occurrences expected %) value))

    :else 0))

(defn- object-class
  [branch store-key value]
  (cond
    (= store-key branch) :store.anatomy/branch-head
    (and (map? value)
         (writing/stored-db? value)
         (= store-key (get-in value [:meta :datahike/commit-id])))
    :store.anatomy/commit-record
    (instance? Leaf value) :store.anatomy/index-leaf
    (instance? Branch value) :store.anatomy/index-branch
    (and (map? value)
         (contains? value :schema)
         (contains? value :rschema))
    :store.anatomy/schema-meta
    :else :store.anatomy/other))

(def ^:private fused-root-keys
  [:eavt-root :aevt-root :avet-root
   :temporal-eavt-root :temporal-aevt-root :temporal-avet-root])

(defn- root-payload-occurrences
  [expected value]
  (when (and (map? value) (writing/stored-db? value))
    (into (sorted-map)
          (keep (fn [root-key]
                  (when-let [root (get value root-key)]
                    [root-key (payload-occurrences expected root)])))
          fused-root-keys)))

(defn- object-row
  [path branch expected [store-key value]]
  (let [file (object-file path store-key)
        root-occurrences (root-payload-occurrences expected value)]
    (cond->
     {:store.anatomy/key store-key
      :store.anatomy/class (object-class branch store-key value)
      :store.anatomy/file (.getName file)
      :store.anatomy/bytes (.length file)
      :store.anatomy/payload-occurrences (payload-occurrences expected value)}
      (seq root-occurrences)
      (assoc :store.anatomy/root-payload-occurrences root-occurrences))))

(defn- event-writes
  [event]
  (case (:api-op event)
    :multi-assoc (vec (:kvs event))
    :assoc [[(:key event) (:value event)]]
    []))

(defn- enumerate-store
  [path store branch expected]
  (->> (k/keys store {:sync? true})
       (map (fn [listed-key]
              (let [store-key (if (map? listed-key)
                                (:key listed-key)
                                listed-key)]
                [store-key (k/get store store-key nil {:sync? true})])))
       (map #(object-row path branch expected %))
       (sort-by (juxt :store.anatomy/class
                      (comp str :store.anatomy/key)))
       vec))

(defn- summarize-objects
  [objects]
  (->> objects
       (group-by :store.anatomy/class)
       (map (fn [[object-type rows]]
              [object-type {:store.anatomy/objects (count rows)
                      :store.anatomy/bytes
                      (reduce + 0 (map :store.anatomy/bytes rows))
                      :store.anatomy/payload-occurrences
                      (reduce + 0 (map :store.anatomy/payload-occurrences rows))}]))
       (into (sorted-map))))

(defn- measure-cell!
  [root label options payload-size transaction-count enumerate?]
  (let [path (.getCanonicalPath (io/file root label))
        config (configuration path options)
        expected (payload payload-size)]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (d/transact connection schema)
        (let [baseline (directory-stats path)
              store (:store @connection)
              events (atom [])]
          (k/add-write-hook! store ::anatomy #(swap! events conj %))
          (dotimes [index transaction-count]
            (d/transact connection
                        [{:store.anatomy/k (long index)
                          :store.anatomy/payload expected}]))
          (k/remove-write-hook! store ::anatomy)
          (let [after (directory-stats path)
                branch (get-in @connection [:config :branch])
                final-event (last @events)
                final-writes (event-writes final-event)
                final-write-objects
                (mapv #(object-row path branch expected %) final-writes)
                all-objects (when enumerate?
                              (enumerate-store path store branch expected))]
            (cond->
             {:store.anatomy/label label
              :store.anatomy/path path
              :store.anatomy/options options
              :store.anatomy/payload-bytes payload-size
              :store.anatomy/transactions transaction-count
              :store.anatomy/baseline baseline
              :store.anatomy/after after
              :store.anatomy/growth-bytes
              (- (:store.anatomy/bytes after)
                 (:store.anatomy/bytes baseline))
              :store.anatomy/final-write final-write-objects
              :store.anatomy/final-write-summary
              (summarize-objects final-write-objects)}
              enumerate?
              (assoc :store.anatomy/objects all-objects
                     :store.anatomy/object-summary
                     (summarize-objects all-objects)))))
        (finally
          (d/release connection))))))

(defn- retained-copy-coefficient
  "Payload Datom copies retained after N shallow-root commits.

  The non-indexed cardinality-one value enters current EAVT/AEVT and temporal
  EAVT/AEVT. Each immutable commit keeps all four growing roots; the mutable
  branch head keeps the final four roots."
  [transactions]
  (let [index-copies 4
        triangular (quot (* transactions (inc transactions)) 2)]
    (* index-copies (+ triangular transactions))))

(defn- one-commit-anatomy!
  [root]
  (mapv
   (fn [[label options]]
     (measure-cell! root label options 4096 1 true))
   [["one-optimized-history"
     {:store.anatomy/keep-history? true
      :store.anatomy/fuse-index-roots? true
      :store.anatomy/diff-buf-size 256}]
    ["one-legacy-history"
     {:store.anatomy/keep-history? true
      :store.anatomy/fuse-index-roots? false
      :store.anatomy/diff-buf-size 0}]
    ["one-optimized-no-history"
     {:store.anatomy/keep-history? false
      :store.anatomy/fuse-index-roots? true
      :store.anatomy/diff-buf-size 256}]]))

(defn -main
  "Measure and print the predictive store-object anatomy."
  [& _arguments]
  (let [root (.getCanonicalPath
              (io/file "tmp" (str "store-write-object-anatomy-" (UUID/randomUUID))))
        optimized {:store.anatomy/keep-history? true
                   :store.anatomy/fuse-index-roots? true
                   :store.anatomy/diff-buf-size 256}
        zero (measure-cell! root "sequence-zero" optimized 0
                            measured-transactions false)
        training (measure-cell! root "sequence-4k" optimized
                                training-payload-bytes
                                measured-transactions false)
        coefficient (retained-copy-coefficient measured-transactions)
        serialized-bytes-per-payload-byte
        (/ (double (- (:store.anatomy/growth-bytes training)
                      (:store.anatomy/growth-bytes zero)))
           (* training-payload-bytes coefficient))
        predicted-growth
        (long (Math/round
               (+ (:store.anatomy/growth-bytes zero)
                  (* validation-payload-bytes coefficient
                     serialized-bytes-per-payload-byte))))
        prediction {:store.anatomy/target-payload-bytes validation-payload-bytes
                    :store.anatomy/transactions measured-transactions
                    :store.anatomy/retained-copy-coefficient coefficient
                    :store.anatomy/serialized-bytes-per-payload-byte
                    serialized-bytes-per-payload-byte
                    :store.anatomy/predicted-growth-bytes predicted-growth}]
    ;; This print intentionally precedes the first transaction at the held-out
    ;; size. The following cell is the validation, not an input to the model.
    (prn {:store.anatomy/prediction-before-measurement prediction})
    (flush)
    (let [validation (measure-cell! root "sequence-16k-held-out" optimized
                                    validation-payload-bytes
                                    measured-transactions true)
          actual (:store.anatomy/growth-bytes validation)
          error (- actual predicted-growth)
          result {:store.anatomy/dependency-ledger
                  {:store.anatomy/datahike
                   "256b714d97a0e8f952b01a47c693eff2976ccee7"
                   :store.anatomy/konserve
                   "737697d9205e5e8f0bc08a666e4c97dad55e9dbe"
                   :store.anatomy/persistent-sorted-set
                   "e1a17bbe767c7801e67407c81f64efabfd2f1601"}
                  :store.anatomy/root root
                  :store.anatomy/model
                  {:store.anatomy/serialized-root-copies-per-payload 4
                   :store.anatomy/retained-copy-equation
                   "4 * (N*(N+1)/2 + N)"
                   :store.anatomy/retained-copy-coefficient coefficient}
                  :store.anatomy/training
                  {:store.anatomy/zero zero
                   :store.anatomy/training-4k training}
                  :store.anatomy/prediction prediction
                  :store.anatomy/validation validation
                  :store.anatomy/prediction-error
                  {:store.anatomy/bytes error
                   :store.anatomy/percent
                   (* 100.0 (/ (Math/abs (double error)) actual))}
                  :store.anatomy/one-commit-anatomy
                  (one-commit-anatomy! root)}]
      (prn result)
      (prn
       {:store.anatomy/summary
        {:store.anatomy/root root
         :store.anatomy/predicted-growth-bytes predicted-growth
         :store.anatomy/actual-growth-bytes actual
         :store.anatomy/prediction-error-bytes error
         :store.anatomy/prediction-error-percent
         (* 100.0 (/ (Math/abs (double error)) actual))
         :store.anatomy/final-retained-objects
         (:store.anatomy/object-summary validation)
         :store.anatomy/one-commit-write-summaries
         (mapv :store.anatomy/final-write-summary
               (:store.anatomy/one-commit-anatomy result))}})))
  (shutdown-agents))

(apply -main *command-line-args*)
