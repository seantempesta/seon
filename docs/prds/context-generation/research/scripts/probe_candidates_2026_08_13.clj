(require '[datahike.api :as d] '[clojure.data :as data] '[clojure.set :as set] '[clojure.pprint :as pp])
(defn show [l v] (println (str "\n;; === " l " ===")) (pp/pprint v))

(def cfg {:store {:backend :memory :id #uuid "3f2b7c10-2222-4222-8333-444455556666"}
          :keep-history? true :schema-flexibility :write})
(when (d/database-exists? cfg) (d/delete-database cfg))
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :seon.cluster.message/id :db/valueType :db.type/string
                   :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
                  {:db/ident :seon.cluster.message/content :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}
                  {:db/ident :seon.cluster.message/to :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}])
(d/transact conn [{:seon.cluster.message/id "m1" :seon.cluster.message/content "hello"  :seon.cluster.message/to "root"}
                  {:seon.cluster.message/id "m2" :seon.cluster.message/content "second" :seon.cluster.message/to "root"}
                  {:seon.cluster.message/id "m3" :seon.cluster.message/content "third"  :seon.cluster.message/to "other"}])
(def basis-t (:max-tx (d/db conn)))

;; a read surface with a DECLARED database-value argument, exactly like my.message/inbox
(defn inbox [database agent-id]
  (->> (d/q '[:find [?m ...] :in $ ?to :where [?m :seon.cluster.message/to ?to]] database agent-id)
       (map #(d/pull database '[:seon.cluster.message/id :seon.cluster.message/content] %))
       (map (fn [m] {:my.message/id (:seon.cluster.message/id m)
                     :my.message/preview (:seon.cluster.message/content m)}))
       (sort-by :my.message/id) vec))

(show "inbox at basis-t" (inbox (d/as-of (d/db conn) basis-t) "root"))

(d/transact conn [[:db/add [:seon.cluster.message/id "m1"] :seon.cluster.message/content "hello, edited"]])
(d/transact conn [{:seon.cluster.message/id "m4" :seon.cluster.message/content "fourth" :seon.cluster.message/to "root"}])
(d/transact conn [[:db/retractEntity [:seon.cluster.message/id "m2"]]])
(def db (d/db conn))
(show "inbox now" (inbox db "root"))

;;; ---------- candidate A: re-key by identity, hand to clojure.data/diff ----------
(defn index-by [k coll] (into {} (map (juxt k identity)) coll))

(defn diff-A [id-attr before after]
  (data/diff (index-by id-attr before) (index-by id-attr after)))

(show "A) (clojure.data/diff (index-by id before) (index-by id after))"
      (diff-A :my.message/id (inbox (d/as-of db basis-t) "root") (inbox db "root")))

;;; ---------- candidate B: the same triple, classified into a namespaced map ----------
(defn diff-B [id-attr before after]
  (let [b (index-by id-attr before), a (index-by id-attr after)
        bk (set (keys b)), ak (set (keys a))]
    {:seon.db.diff/added   (mapv a (sort (set/difference ak bk)))
     :seon.db.diff/removed (mapv b (sort (set/difference bk ak)))
     :seon.db.diff/changed (into [] (comp (remove #(= (b %) (a %)))
                                          (map #(hash-map :seon.db.diff/before (b %)
                                                          :seon.db.diff/after  (a %))))
                                 (sort (set/intersection ak bk)))}))
(show "B) #:seon.db.diff{:added :changed :removed}"
      (diff-B :my.message/id (inbox (d/as-of db basis-t) "root") (inbox db "root")))

;;; ---------- candidate C: pairing map only; agent filters with core fns ----------
(defn diff-C [id-attr before after]
  (into (sorted-map)
        (merge-with (fn [[x _] [_ y]] [x y])
                    (into {} (map (juxt id-attr (fn [m] [m nil]))) before)
                    (into {} (map (juxt id-attr (fn [m] [nil m]))) after))))
(show "C) {id [before after]} pairing"
      (diff-C :my.message/id (inbox (d/as-of db basis-t) "root") (inbox db "root")))

;;; identity-LESS values: what each candidate does
(show "identity-less scalar: (data/diff 3 4)" (data/diff 3 4))
(show "identity-less nested map: (data/diff {:a {:b 1 :c 2}} {:a {:b 9 :c 2}})"
      (data/diff {:a {:b 1 :c 2}} {:a {:b 9 :c 2}}))
(show "no-change case, A" (diff-A :my.message/id (inbox db "root") (inbox db "root")))
(show "no-change case, B" (diff-B :my.message/id (inbox db "root") (inbox db "root")))

;;; the higher-order (no-eval) call shape
(defn diff* [{:keys [basis-t id-attr db f args]}]
  (let [before (apply f (d/as-of db basis-t) args)
        after  (apply f db args)]
    (assoc (diff-B id-attr before after)
           :seon.db/basis-t basis-t
           :seon.db/current-basis-t (:max-tx db))))
(show "higher-order call: (diff* {:f inbox :args [\"root\"] ...})"
      (diff* {:basis-t basis-t :id-attr :my.message/id :db db :f inbox :args ["root"]}))
