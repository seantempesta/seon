(require '[seon.db :as db] '[seon.cluster.boot :as boot])
(do
(require '[seon.fn :as functions] '[seon.id :as id] '[seon.program :as program]
 '[seon.cluster.store :as store] '[seon.cluster.registry :as registry]
 '[datahike.index.interface :as idx] '[konserve.impl.defaults :as defaults] '[clojure.java.io :as io])
(defn identify-lint [rows]
 (second (reduce
  (fn [[counts out] row]
    (let [owner (if-let [f (:seon.lint/fn row)] (symbol (second f)) (second (:seon.lint/file row)))
          signature [owner (:seon.lint/type row) (:seon.lint/message row)]
          ordinal (get counts signature 0)]
      [(assoc counts signature (inc ordinal)) (conj out (assoc row :seon.lint/id (id/id [signature ordinal])))]))
  [{} []] (sort-by (juxt :seon.lint/row :seon.lint/col) rows))))
(defn old-lint-identities [rows]
 (mapv (fn [row] (let [owner (if-let [f (:seon.lint/fn row)] (symbol (second f)) (second (:seon.lint/file row)))]
  (assoc row :seon.lint/id (id/id [owner (:seon.lint/type row) (:seon.lint/row row) (:seon.lint/col row)])))) rows))
(def lint-base (db/db (boot/connection "default")))
(def lint-rows (vec (functions/published-index-entities lint-base
                        (mapv #(vector :seon.lint/id %) (take 100 (sort (db/q '[:find [?id ...] :where [_ :seon.lint/id ?id]] lint-base)))))))
{:rows (count lint-rows) :sample (select-keys (first lint-rows) [:seon.lint/type :seon.lint/fn :seon.lint/file])})

(do
(defn measure-lint [n]
 (let [held (:seon.store/store (#'boot/selected-instance "default"))
       prefix (str "astra-lint-" (id/id))
       mark (fn [database seen]
              (let [nodes (reduce (fn [out a] (into out (idx/-mark (get database a) seen))) #{}
                                  [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])
                    files (filter #(.isFile %) (map #(io/file (:seon.store/dir held) (defaults/key->store-key %)) nodes))]
                {:nodes (count nodes) :bytes (reduce + (map #(.length %) files))}))
       original (subvec lint-rows 0 n)
       results
       (mapv
        (fn [candidate?]
          (let [b (keyword (str prefix "-" candidate?))
                _ (registry/branch! {:seon.store/store held :seon.store/branch b
                                     :seon.cluster.registry/from (get-in (db/committed-value-identity lint-base) [:datahike.value/commit-id])})
                conn (store/open-branch! held b)]
            (try
              (let [identify (if candidate? identify-lint old-lint-identities)
                    seeded (identify original)
                    seed (db/transact! conn (functions/reconcile-tx (db/db conn) seeded (mapv program/row-identity original)))
                    _ (assert (:db-after seed))
                    seen (atom #{}) _ (mark (db/db conn) seen)
                    desired (identify (mapv #(update % :seon.lint/row inc) original))
                    tx (functions/reconcile-tx (db/db conn) desired (mapv program/row-identity seeded))
                    t0 (System/nanoTime)
                    report (db/transact! conn tx)
                    ms (/ (- (System/nanoTime) t0) 1e6)
                    _ (assert (:db-after report))
                    found (functions/published-index-entities (:db-after report) (mapv program/row-identity desired))]
                (assert (= (set desired) (set found)))
                {:n n :candidate? candidate? :commits 1 :ms ms :datoms (count (:tx-data report))
                 :storage (mark (:db-after report) seen)
                 :answer (set (map #(dissoc % :seon.lint/id) found))})
              (finally (store/release-branch! conn)
                       (registry/retire-branch! {:seon.store/store held :seon.store/branch b})))))
        [false true])]
   (assert (apply = (map :answer results)))
   (mapv #(dissoc % :answer) results)))
(mapv measure-lint [1 10 100]))

