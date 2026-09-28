;; store-structure-opus: bytes, files and ms per ordinary run commit, by attribute shape.
;; One throwaway file store under Seon's creation configuration (128/32, LZ4, fused roots,
;; history); one base written once; each variant commits on its own d/branch! of the base.
;; Run: clojure -M tmp/store-structure/probe.clj
(require '[datahike.api :as d]
         '[clojure.java.io :as io]
         '[seon.cluster.store :as store])

(def dir "/Users/sean/src/seon/tmp/store-structure/probe-store")

(defn dir-stats []
  (let [fs (filter #(.isFile ^java.io.File %) (file-seq (io/file dir)))]
    {:files (count fs)
     :bytes (reduce + (map #(.length ^java.io.File %) fs))
     :disk (reduce + (map #(* 4096 (quot (+ 4095 (.length ^java.io.File %)) 4096)) fs))
     :mtime-bytes (into {} (map (fn [^java.io.File f] [(.getName f) [(.lastModified f) (.length f)]])) fs)}))

(defn attr [ident type & {:as opts}]
  (merge {:db/ident ident :db/valueType type :db/cardinality :db.cardinality/one} opts))

;; The live default schema of these attributes (read from (d/schema @conn) 2026-09-28).
(def schema
  [(attr :seon.agent/id :db.type/string :db/unique :db.unique/identity)
   (attr :seon.namespace/sym :db.type/symbol :db/unique :db.unique/identity)
   (attr :seon.runtime/agent :db.type/ref :db/unique :db.unique/identity)
   (attr :seon.runtime/turns :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true)
   (attr :seon.turn/id :db.type/string :db/unique :db.unique/identity)
   (attr :seon.turn/opened-tx :db.type/ref)
   (attr :seon.turn/closed-tx :db.type/ref)
   (attr :seon.turn/reply-size :db.type/long)
   (attr :seon.turn/agent :db.type/ref)
   (attr :seon.turn/starting-ns :db.type/ref)
   (attr :seon.cluster.eval/id :db.type/string :db/unique :db.unique/identity)
   (attr :seon.cluster.eval/run :db.type/ref)
   (attr :seon.cluster.eval/ordinal :db.type/long)
   (attr :seon.cluster.eval/at :db.type/instant)
   (attr :seon.cluster.eval/source :db.type/string :db/noHistory true)
   (attr :seon.cluster.eval/author :db.type/keyword)
   (attr :seon.cluster.eval/ns :db.type/ref)
   (attr :seon.eval/shown :db.type/string)
   (attr :seon.eval/duration-ms :db.type/long)
   ;; stamps as installed (indexed) and the same values unindexed under probe names
   (attr :seon.db.transaction/process :db.type/string :db/index true)
   (attr :seon.db.transaction/operation :db.type/symbol :db/index true)
   (attr :seon.db.transaction/agent :db.type/string :db/index true)
   (attr :probe.tx/process :db.type/string)
   (attr :probe.tx/operation :db.type/symbol)
   (attr :probe.tx/agent :db.type/string)])

(def agents (mapv #(str "agent-" %) (range 12)))
(def namespaces (mapv #(symbol (str "my.ns" %)) (range 30)))

(defn text [n i] (let [s (str "(defn f" i " [x] (+ x " i ")) ;; ")] (apply str (take n (cycle s)))))

(defn run-data
  "One ordinary run: a turn with `evals` evaluations, closed in the transaction that
  writes it; the stamp only when `stamp?` (a base batch carries one stamp)."
  [variant i evals stamp?]
  (let [agent (agents (mod i (count agents)))
        ns [:seon.namespace/sym (namespaces (mod (quot i 7) (count namespaces)))]
        now (java.util.Date.)
        turn-id (str "turn" i)
        {:keys [tx-refs? eval-at? author? process? stamp-index?]} variant
        stamp-ns (if stamp-index? "seon.db.transaction" "probe.tx")]
    (cond-> [(cond-> {:db/id turn-id :seon.turn/id turn-id
                      :seon.turn/reply-size (+ 40 (mod (* 37 i) 900))
                      :seon.turn/agent [:seon.agent/id agent]
                      :seon.turn/starting-ns ns}
               tx-refs? (assoc :seon.turn/opened-tx :db/current-tx :seon.turn/closed-tx :db/current-tx))
             [:db/add [:seon.runtime/agent [:seon.agent/id agent]] :seon.runtime/turns turn-id]]
      true (into (for [o (range evals)]
                   (cond-> {:seon.cluster.eval/id (str "eval-" i "-" o)
                            :seon.cluster.eval/run turn-id
                            :seon.cluster.eval/ordinal o
                            :seon.cluster.eval/source (text (+ 30 (mod (* 13 (+ i o)) 200)) i)
                            :seon.cluster.eval/ns ns
                            :seon.eval/shown (text (+ 10 (mod (* 7 (+ i o)) 120)) o)
                            :seon.eval/duration-ms (mod (* 11 (+ i o)) 50)}
                     eval-at? (assoc :seon.cluster.eval/at now)
                     author? (assoc :seon.cluster.eval/author :system))))
      stamp? (conj (cond-> {:db/id :db/current-tx
                            (keyword stamp-ns "operation") (symbol "seon.turn" (str "op-" (mod i 5)))
                            (keyword stamp-ns "agent") agent}
                     process? (assoc (keyword stamp-ns "process") "24073@2026-09-28T21:49:15Z"))))))

(def current {:tx-refs? true :eval-at? true :author? true :process? true :stamp-index? true})

(def variants
  (array-map
   :current current
   :no-process (assoc current :process? false)
   :stamps-unindexed (assoc current :stamp-index? false)
   :no-tx-refs (assoc current :tx-refs? false)
   :no-eval-at-author (assoc current :eval-at? false :author? false)
   :all-redundant-gone (assoc current :process? false :tx-refs? false :eval-at? false :author? false)
   :all+unindexed (assoc current :process? false :tx-refs? false :eval-at? false :author? false :stamp-index? false)))

(defn ms-since [t] (/ (- (System/nanoTime) t) 1e6))

(defn measure-variant [cfg base-runs label variant]
  (let [branch (keyword (str "v-" (name label)))
        _ (let [c (d/connect cfg)] (d/branch! c :db branch) (d/release c))
        bconn (d/connect (assoc cfg :branch branch))
        warm 5 measured 20
        start (+ base-runs (* 1000 (inc (.indexOf ^java.util.List (vec (keys variants)) label))))
        _ (doseq [i (range warm)] (d/transact bconn (run-data variant (+ start i) 1 true)))
        before (dir-stats)
        t0 (System/nanoTime)
        _ (doseq [i (range warm (+ warm measured))] (d/transact bconn (run-data variant (+ start i) 1 true)))
        commit-ms (/ (ms-since t0) measured)
        after (dir-stats)
        report (d/transact bconn (run-data variant (+ start 99) 1 true))
        db @bconn
        turn-id (str "turn" (+ start 99))
        read (fn []
               (let [e (d/entity db [:seon.turn/id turn-id])
                     evals (d/q '[:find [?id ...] :in $ ?t :where [?e :seon.cluster.eval/run ?t] [?e :seon.cluster.eval/id ?id]] db (:db/id e))
                     closed (if (:tx-refs? variant)
                              (:db/txInstant (:seon.turn/closed-tx e))
                              ;; the transaction that wrote the turn's identity closed it
                              (let [tx (:tx (first (d/datoms db :eavt (:db/id e) :seon.turn/id)))]
                                (:db/txInstant (d/entity db tx))))]
                 [(count evals) (inst? closed)]))
        _ (dotimes [_ 500] (read))
        t1 (System/nanoTime)
        answer (last (repeatedly 2000 read))
        read-us (/ (- (System/nanoTime) t1) 1000.0 2000)
        new-files (remove #(= (get (:mtime-bytes before) (key %)) (val %)) (:mtime-bytes after))]
    (d/release bconn)
    {:variant label :datoms-per-commit (count (:tx-data report))
     :attrs-per-commit (count (distinct (map :a (:tx-data report))))
     :files-per-commit (/ (- (:files after) (:files before)) (double measured))
     :disk-growth-per-commit (long (/ (- (:disk after) (:disk before)) measured))
     :written-bytes-per-commit (long (/ (reduce + (map (comp second val) new-files)) measured))
     :commit-ms (/ (Math/round (* 10 commit-ms)) 10.0) :read-us (/ (Math/round (* 10 read-us)) 10.0) :read-answer answer}))

(let [cfg (store/datahike-configuration dir)
      _ (d/create-database cfg)
      conn (d/connect cfg)
      _ (d/transact conn schema)
      _ (d/transact conn (vec (concat (for [a agents] {:seon.agent/id a})
                                      (for [n namespaces] {:seon.namespace/sym n}))))
      _ (d/transact conn (vec (for [a agents] {:seon.runtime/agent [:seon.agent/id a]})))
      base-runs 1500
      _ (doseq [chunk (partition-all 50 (range base-runs))]
          (d/transact conn (vec (mapcat #(run-data current % 2 false) chunk))))
      base (dir-stats)
      base-datoms (count (d/datoms @conn :eavt))
      _ (d/release conn)
      results (vec (for [[label variant] variants] (measure-variant cfg base-runs label variant)))]
  (prn {:base {:datoms base-datoms :files (:files base) :disk (:disk base)}})
  (doseq [r results] (prn r))
  (prn {:final-disk (:disk (dir-stats))})
  (d/delete-database cfg)
  (shutdown-agents)
  (System/exit 0))
