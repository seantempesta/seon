;; Pure-data measurement; submit through eval_clj with agent root, cluster default,
;; mode jvm and namespace seon.cluster.registry. Defines no Vars, reloads no code,
;; and performs only dry-run GC. The candidate is a lexical copy of the new fold.
;; The per-branch version uses the SAME fold with fresh seen sets to count repeated
;; leaf work; legacy query equality is measured separately on the captured DBs.
(require 'datahike.db.interface)
(let [connection (seon.cluster.boot/connection "default")
      branches (konserve.core/get (:store @connection) :branches nil {:sync? true})
      databases (into {} (map (fn [b] [b (d/branch-as-db connection b)])) branches)
      include-history? true
      phase (volatile! :current)
      visits (volatile! {:current 0 :history 0})
      datoms (volatile! {:current 0 :history 0})
      candidate (fn [branches] (letfn [(walk [^org.replikativ.persistent_sorted_set.ANode node storage attributes state]
            (if (instance? org.replikativ.persistent_sorted_set.Branch node)
              (let [^org.replikativ.persistent_sorted_set.Branch node node
                    keys (.keys node)
                    slots (.slots node)]
                (reduce
                 (fn [[seen digests :as state] i]
                   (let [lower (when (pos? i) (:a (nth keys (dec i))))
                         upper (:a (nth keys i))
                         address (.address node i)
                         slot (when slots (aget ^objects slots i))
                         diff (when slot (.-diff ^org.replikativ.persistent_sorted_set.Slot slot))
                         identity [attributes address diff]]
                     (if (or (and address (contains? seen identity))
                             (not-any? #(and (or (nil? lower) (not (neg? (compare % lower))))
                                             (not (pos? (compare % upper)))) attributes))
                       state
                       (walk (.child node storage i) storage attributes
                             [(cond-> seen address (conj identity)) digests]))))
                 state (range (.len node))))
              (do (vswap! visits update @phase inc) (vswap! datoms update @phase + (.len node)) (update state 1 into
                      (comp (filter #(contains? attributes (:a %))) (map :v))
                      (.keys node)))))
          (index-blobs [state [index-kind index] attributes]
            (let [^org.replikativ.persistent_sorted_set.PersistentSortedSet index index
                  _ (vreset! phase index-kind)
                  address (.-_address index)
                  identity [attributes address nil]]
              (if (and address (contains? (first state) identity))
                state
                (walk (.root index) (.-_storage index) attributes
                      (update state 0 #(cond-> % address (conj identity))))))) ]
    (second
     (reduce
      (fn [state branch]
        (let [database (get databases branch)]
          (try
            (let [attributes (set (blob-digest-attributes database))
                  attributes (if (get-in database [:config :attribute-refs?])
                               (into #{} (keep #(datahike.db.interface/-ref-for database %)) attributes)
                               attributes)
                  indices (cond-> [[:current (:aevt database)]]
                            (and include-history? (get-in database [:config :keep-history?]))
                            (conj [:history (:temporal-aevt database)]))]
              (reduce #(index-blobs %1 %2 attributes) state indices))
            (finally nil))))
      [#{} #{}] branches))))
      before-start (System/nanoTime)
      before (into #{} (mapcat (fn [[_ database]]
                               (mapcat (fn [attribute]
                                         (d/q '[:find [?digest ...] :in $ ?attribute :where [_ ?attribute ?digest]]
                                              (d/history database) attribute))
                                       (blob-digest-attributes database)))) databases)
      before-ms (/ (- (System/nanoTime) before-start) 1e6)
      unshared-visits (do (doseq [branch branches] (candidate #{branch})) @visits)
      unshared-datoms @datoms
      _ (do (vreset! visits {:current 0 :history 0}) (vreset! datoms {:current 0 :history 0}))
      after-start (System/nanoTime)
      after (candidate branches)
      after-ms (/ (- (System/nanoTime) after-start) 1e6)]
  (try
    (let [_ (spit "/Users/sean/src/seon/tmp/astra-referenced-blobs/commits.edn"
                      (pr-str (into {} (map (fn [[branch database]] [branch (d/commit-id database)])) databases)))
          answer {:branches (count branches) :references-before (count before)
                  :references-after (count after) :equal (= before after)
                  :before-ms before-ms :after-ms after-ms
                  :history-queries-before (reduce + (map #(count (blob-digest-attributes %)) (vals databases)))
                  :history-queries-after 0
                  :leaf-visits-unshared unshared-visits :leaf-datoms-unshared unshared-datoms
                  :leaf-visits-after @visits :leaf-datoms-after @datoms}]
      (spit "/Users/sean/src/seon/tmp/astra-referenced-blobs/proof.edn" (pr-str answer))
      (let [candidate-proof
            @(d/gc-storage
              connection (java.util.Date. 0)
              {:datahike.gc/sweep-opts {:konserve.gc/dry-run? true}
               :datahike.gc/reachable-extension
               (fn [{:datahike.gc/keys [store reachable]}]
                 (let [listing (konserve.core/keys store {:sync? true})
                       cutoff (System/currentTimeMillis)
                       select-candidates
                       (fn [digests]
                         (into #{} (comp (filter (fn [{:keys [key last-write] :as m}]
                                                  (and (not (contains? (into (conj reachable :branches) digests) key))
                                                       (< (.getTime ^java.util.Date (or last-write (:konserve.core/timestamp m))) cutoff))))
                                          (map :key)) listing))
                       old-candidates (select-candidates before)
                       new-candidates (select-candidates after)]
                   (spit "/Users/sean/src/seon/tmp/astra-referenced-blobs/candidates.edn"
                         (pr-str {:equal (= old-candidates new-candidates)
                                  :before (count old-candidates) :after (count new-candidates)
                                  :listed-keys (count listing)}))
                   before))})]
        (assoc answer :gc-dry-run-candidates (count candidate-proof))))
    (finally (doseq [database (vals databases)] (d/release-materialized-db database)))))
