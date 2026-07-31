;; Section 1b — decompose the per-transact cost.
;;
;; The first run measured ~123 ms median per small commit on the file store.
;; This isolates the three candidate contributors:
;;   1. Seon's write door (`schema.datahike/encode-transaction` + Integer->Long)
;;   2. Datahike's transaction machinery (index update, history)
;;   3. konserve's file backend (node serialization + fsync per commit)
;; by running the SAME shape against an in-memory Datahike store with the same
;; schema, and by timing the encode step alone.
(require '[datahike.api :as d]
         '[seon.cluster.store :as store]
         '[seon.schema.datahike :as schema.datahike])

(def inst (get @@(ns-resolve 'seon.cluster 'running-instances) "bench"))
(def bench-store (:seon.store/store inst))
;; the branch is already connected in this JVM from db.clj (open-branch!
;; refuses a second connection to one branch by design)
(def file-conn (or (resolve 'user/conn) (store/open-branch! bench-store :bench-db)))
(def file-conn (if (var? file-conn) @file-conn file-conn))

(defn quantiles [samples]
  (let [sorted (vec (sort samples))
        n (count sorted)
        at (fn [q] (nth sorted (min (dec n) (long (* q n)))))
        total (reduce + 0 sorted)]
    {:n n
     :median-ms (/ (Math/round (/ (at 0.5) 1000.0)) 1000.0)
     :p95-ms (/ (Math/round (/ (at 0.95) 1000.0)) 1000.0)
     :sustained-per-s (Math/round (/ (* 1e9 n) (double total)))}))

(defn timed [warm n f]
  (dotimes [i warm] (f i))
  (mapv (fn [i] (let [s (System/nanoTime)] (f (+ warm i)) (- (System/nanoTime) s)))
        (range n)))

(def root-eid (d/q '[:find ?e . :where [?e :seon.cluster.agent/id "root"]]
                   @file-conn))

(defn message-row [to i]
  [{:seon.cluster.message/id (str "d2-msg-" i)
    :seon.cluster.message/to to
    :seon.cluster.message/content (str "benchmark message " i)
    :seon.cluster.message/at (java.util.Date.)}])

;;; 1. the encode step alone --------------------------------------------------
(def one-row [{:seon.cluster.message/id "encode-probe"
               :seon.cluster.message/to root-eid
               :seon.cluster.message/content "x"
               :seon.cluster.message/at (java.util.Date.)}])
(println :ENCODE
         (pr-str (quantiles (timed 1000 5000
                                   (fn [_] (schema.datahike/encode-transaction one-row))))))

;;; 2. the same shape on an IN-MEMORY store with the same schema --------------
(def memory-config {:store {:backend :memory :id (str (random-uuid))}
                    :schema-flexibility :write
                    :keep-history? true})
(d/create-database memory-config)
(def mem-conn (d/connect memory-config))
;; install the same schema the file branch carries
(def installed-schema
  (into [] (map (fn [[_ attribute]] (dissoc attribute :db/id)))
        (d/schema @file-conn)))
(d/transact mem-conn installed-schema)
(d/transact mem-conn [{:seon.cluster.agent/id "root"}])
(def mem-root (d/q '[:find ?e . :where [?e :seon.cluster.agent/id "root"]] @mem-conn))
(println :MEM-SCHEMA-ATTRS (count installed-schema))

(println :MEM-TRANSACT
         (pr-str (quantiles (timed 50 500
                                   (fn [i] (store/transact! mem-conn (message-row mem-root i)))))))

;;; 3. file store: raw d/transact, no Seon door -------------------------------
(println :FILE-RAW
         (pr-str (quantiles (timed 20 200
                                   (fn [i] (d/transact file-conn (message-row root-eid (+ 700000 i))))))))

;;; 4. does it scale with database size? small vs the grown branch ------------
;; a FRESH file branch (schema only, ~20k datoms) vs the branch grown to ~50k
(println :FILE-DOOR-AGAIN
         (pr-str (quantiles (timed 20 200
                                   (fn [i] (store/transact! file-conn (message-row root-eid (+ 800000 i))))))))
(println :FILE-DATOMS (count (d/datoms @file-conn :eavt)))

;;; 5. history off? measure a no-history in-memory store for the delta --------
(def nohistory-config {:store {:backend :memory :id (str (random-uuid))}
                       :schema-flexibility :write
                       :keep-history? false})
(d/create-database nohistory-config)
(def nh-conn (d/connect nohistory-config))
(d/transact nh-conn installed-schema)
(d/transact nh-conn [{:seon.cluster.agent/id "root"}])
(def nh-root (d/q '[:find ?e . :where [?e :seon.cluster.agent/id "root"]] @nh-conn))
(println :MEM-NOHISTORY
         (pr-str (quantiles (timed 50 500
                                   (fn [i] (store/transact! nh-conn (message-row nh-root i)))))))

(println :DB2-DONE)
