;; Submit this single form through MCP eval_clj, root/default, JVM mode.
;; Supplemental smaller-buffer run: replace variants with [[128 32] [128 16] [128 32]].
;; Only immutable snapshots are read from default; every commit targets a unique scratch store.
(let [history-length 240
      variants [[128 32] [128 64] [128 128] [128 32]]
      conn (seon.cluster.boot/connection "default")
      live @conn
      end-id #uuid "6ad1678c-f986-5e7c-bf6d-6cffdda72745"
      cids (loop [cid end-id n history-length out ()]
             (if (zero? n) (vec (conj out cid))
               (let [parents (get-in (datahike.writing/read-head (:store live) cid)
                                    [:meta :datahike/parents])
                     parent (if (= 1 (count parents)) (first parents)
                              (first (filter #(= :cluster-default
                                                  (get-in (datahike.writing/read-head (:store live) %)
                                                          [:config :branch])) parents)))]
                 (recur parent (dec n) (conj out cid)))))
      snapshots (mapv #(datahike.api/commit-as-db conn %) cids)
      pairs [[:eavt :eavt] [:aevt :aevt] [:avet :avet]
             [:temporal-eavt :eavt] [:temporal-aevt :aevt] [:temporal-avet :avet]]
      metrics (fn [m] (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes :seon.cost/jvm-window]))
      scratch (str (java.nio.file.Files/createTempDirectory "commit-cost-2-" (make-array java.nio.file.attribute.FileAttribute 0)))
      open-store (fn [config]
                   (datahike.store/add-cache-and-handlers
                    (konserve.store/connect-store (:store config) {:sync? true}) config))
      close-store (fn [config store] (konserve.store/release-store (:store config) store {:sync? true}))
      file-names (fn [path] (set (.list (clojure.java.io/file path))))
      size (fn [path names] (reduce + 0 (map #(.length (clojure.java.io/file path %)) names)))
      codec-size (fn [store value]
                   (let [out (java.io.ByteArrayOutputStream.)
                         codec (konserve.compressor/lz4-compressor (get (:serializers store) :FressianSerializer))]
                     (konserve.protocols/-serialize codec out (:write-handlers store) value)
                     (.size out)))
      root-pairs [[:eavt-key :eavt-root] [:aevt-key :aevt-root] [:avet-key :avet-root]
                  [:temporal-eavt-key :temporal-eavt-root] [:temporal-aevt-key :temporal-aevt-root]
                  [:temporal-avet-key :temporal-avet-root]]]
  (try
    (let [delta-measure
          (seon.profile/measure
           (fn [] (mapv (fn [[a b]]
                          (into {} (map (fn [[k index]]
                                          [k (datahike.api/datoms-diff
                                              (assoc a index (get a k))
                                              (assoc b index (get b k)) index [])]) pairs)))
                        (partition 2 1 snapshots))))
          changes (:seon.profile/value delta-measure)
          expected (peek snapshots)
          query '[:find ?e ?sym :where [?e :seon.fn/sym ?sym]]
          query-value (datahike.api/q query expected)
          ids (vec (take 32 (sort (map first query-value))))
          selector [:db/id :seon.fn/sym :seon.fn/doc]
          pull-value (mapv #(datahike.api/pull expected selector %) ids)
          read-work (fn [db] {:q (datahike.api/q query db)
                             :pull (mapv #(datahike.api/pull db selector %) ids)
                             :history (mapv #(vector (:e %) (:a %) (:v %) (:tx %) (:added %)) (datahike.api/datoms (datahike.api/history db) :eavt (first ids)))})
          expected-reads (read-work expected)
          answers
          (mapv
           (fn [[bf buffer]]
             (let [path (str scratch "/" bf "-" buffer "-" (random-uuid))
                   config (-> (:config (first snapshots))
                              (assoc :branch :replay :store-cache-bytes 64000000
                                     :store {:backend :file :path path :id (random-uuid)
                                             :config {:sync-blob? true :defer-immutable-sync? true
                                                      :compressor {:type :lz4}}}
                                     :index-config {:branching-factor bf :diff-buf-size buffer})
                              (update :writer dissoc :write-fn-map))
                   store (datahike.store/add-cache-and-handlers
                          (konserve.store/create-store (:store config) {:sync? true}) config)]
               (try
                 (let [initial-measure
                       (seon.profile/measure
                        (fn []
                          (let [base (reduce (fn [db [k index]]
                                               (let [datoms (get (first snapshots) k)]
                                                 (assoc db k (datahike.index/init-index
                                                              :datahike.index/persistent-set store datoms index nil
                                                              {:indexed (when (= index :avet) (set (map :a datoms)))}))))
                                             (assoc (first snapshots) :store store :config config) pairs)]
                            (datahike.writing/commit! base #{} true))))
                       base (:seon.profile/value initial-measure)
                       base-files (file-names path)
                       head-file (konserve.impl.defaults/key->store-key :replay)
                       [after costs]
                       (reduce
                        (fn [[before costs] [n delta source]]
                          (let [old-files (file-names path)
                                measurement
                                (seon.profile/measure
                                 (fn []
                                   (let [next-db
                                         (reduce (fn [db [k index]]
                                                   (let [[removed added] (get delta k)]
                                                     (assoc db k
                                                            (reduce #(datahike.index/-temporal-insert %1 %2 index 0)
                                                                    (reduce #(datahike.index/-remove %1 %2 index 0)
                                                                            (get before k) removed) added))))
                                                 (assoc source :store store :config config) pairs)]
                                     (datahike.writing/commit! next-db nil true
                                                              (get-in before [:meta :datahike/commit-id])))))
                                after (:seon.profile/value measurement)
                                fresh (clojure.set/difference (file-names path) old-files)
                                row {:commit n :files (inc (count fresh))
                                     :bytes (+ (size path fresh) (size path [head-file]))
                                     :measure (metrics measurement)}]
                            [after (conj costs row)]))
                        [base []] (map vector (range 1 (inc history-length)) changes (rest snapshots)))
                       final-record (datahike.writing/read-head store :replay)
                       previous-record (datahike.writing/read-head store (first (get-in final-record [:meta :datahike/parents])))
                       changed-roots (filter (fn [[k _]] (not= (get-in final-record [:merkle-roots k])
                                                             (get-in previous-record [:merkle-roots k]))) root-pairs)
                       fusion {:fused (codec-size store final-record)
                               :counterfactual (+ (codec-size store (apply dissoc final-record (map second root-pairs)))
                                                  (reduce + (map #(codec-size store (get final-record (second %))) changed-roots)))
                               :additional-objects (count changed-roots)}
                       cold-reads
                       (mapv (fn [_]
                               (let [cold (open-store config)]
                                 (try
                                   (let [m (seon.profile/measure
                                            (fn [] (read-work
                                                    (datahike.writing/stored->db
                                                     (datahike.writing/read-head cold :replay) cold
                                                     {:secondary-indices? false}))))]
                                     {:measure (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes]) :correct? (= expected-reads (:seon.profile/value m))
                                      :cache (datahike.index.interface/node-cache-stats (:cache (:storage cold)))})
                                   (finally (close-store config cold))))) (range 5))
                       _ (konserve.core/assoc store :replay-measurements costs {:sync? true})
                       correct (into {} (map (fn [[k _]] [k (= (map #(vector (:e %) (:a %) (:v %) (:tx %) (:added %)) (get expected k)) (map #(vector (:e %) (:a %) (:v %) (:tx %) (:added %)) (get after k)))]) pairs))]
                   {:variant [bf buffer] :path path :config (:config store)
                    :initial (metrics initial-measure) :base-files (count base-files) :base-bytes (size path base-files)
                    :correct correct :fusion fusion :cold-reads cold-reads
                    :windows (mapv (fn [rows]
                                     {:first (:commit (first rows)) :last (:commit (last rows))
                                      :bytes (reduce + (map :bytes rows)) :files (reduce + (map :files rows))
                                      :ms (reduce + (map #(get-in % [:measure :seon.profile/ms]) rows))
                                      :alloc-bytes (reduce + (map #(get-in % [:measure :seon.profile/alloc-bytes] 0) rows))})
                                   (partition-all 40 costs))
                    :max-commit-ms (apply max (map #(get-in % [:measure :seon.profile/ms]) costs))})
                 (finally (close-store config store))))) variants)]
      {:scratch scratch :commits history-length :start (first cids) :end (peek cids)
       :delta-measure (metrics delta-measure)
       :delta-counts (into {} (map (fn [[k _]] [k (mapv (fn [window] (reduce + (map #(reduce + (map count (get % k))) window))) (partition-all 40 changes))]) pairs))
       :query query :pull-selector selector :pull-ids ids :query-count (count query-value)
       :pull-count (count pull-value) :variants answers})
    (finally (run! datahike.api/release-materialized-db snapshots))))
