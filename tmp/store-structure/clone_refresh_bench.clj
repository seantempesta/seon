;; store-structure-opus: the read-evidence refresh's history work (seon.db
;; index-evidence-current: one d/since over d/history at the read basis, then the
;; first datom per retained pattern via dbi/search), over the APFS clone's
;; :cluster-default head, under whichever Datahike this JVM loads. Patterns are
;; sampled deterministically from the head's own datoms.
;; Run: clojure -M tmp/store-structure/clone_refresh_bench.clj <label>
(require '[datahike.api :as d]
         '[datahike.db.interface :as dbi]
         '[seon.cluster.store :as store])

(let [label (first *command-line-args*)
      cfg (-> (store/datahike-configuration "/Users/sean/src/seon/tmp/store-structure/store-clone")
              (assoc :branch :cluster-default)
              (assoc-in [:store :id] #uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7")
              (dissoc :keep-history? :fuse-index-roots? :index-config))
      conn (d/connect cfg)
      db @conn
      eavt (vec (d/datoms db :eavt))
      step (max 1 (quot (count eavt) 3000))
      sample (vec (take-nth step eavt))
      attrs (vec (distinct (map :a sample)))
      patterns (vec (concat (map (fn [x] [(:e x) (:a x) nil]) sample)        ; entity + attribute
                            (map (fn [a] [nil a nil]) attrs)                 ; attribute only
                            (map (fn [x] [(:e x) (:a x) (:v x)]) (take 500 sample))))
      bases (mapv #(- (:max-tx db) %) [5 50 500])
      check (fn [basis]
              (let [changes (d/since (d/history db) basis)]
                (mapv #(some-> (first (dbi/search changes %)) ((juxt :e :a :v :tx :added))) patterns)))
      answers (mapv check bases)
      timed (fn [basis]
              (dotimes [_ 3] (check basis))
              (let [xs (sort (repeatedly 9 #(let [t (System/nanoTime)] (check basis) (/ (- (System/nanoTime) t) 1e6))))]
                (/ (Math/round (* 10 (nth xs 4))) 10.0)))]
  (prn {:label label :commit (d/commit-id db) :patterns (count patterns)
        :found (mapv #(count (remove nil? %)) answers)
        :answers-hash (hash answers)
        :median-ms-by-basis-age (zipmap [5 50 500] (mapv timed bases))})
  (d/release conn)
  (shutdown-agents)
  (System/exit 0))
