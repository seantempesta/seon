;;; Falsifier for the RESURRECTION race (gc-correctness-cas-opus-2026-08-05.md).
;;; Deterministically interleaves a REAL `d/branch!` from an old commit between
;;; the collector's MARK and its SWEEP, by injecting at konserve.gc/sweep! —
;;; the exact seam datahike.gc/gc-storage! calls after the mark (gc.cljc:146).
;;; OWN scratch store only: tmp/gc-resurrection-probe/store.
(ns gc-resurrection-probe
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [datahike.versioning :as versioning]
            [konserve.core :as k]
            [konserve.gc :as kgc]))

(def dir "tmp/gc-resurrection-probe/store")

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.exists f)
      (when (.isDirectory f) (run! rm-rf (.listFiles f)))
      (.delete f))))

(def cfg
  {:store {:backend :file :path (.getCanonicalPath (io/file dir))
           :id (java.util.UUID/nameUUIDFromBytes
                (.getBytes (.getCanonicalPath (io/file dir)) "UTF-8"))}
   :writer {:backend :self}
   :keep-history? false
   :schema-flexibility :write})

(defn run []
  (rm-rf "tmp/gc-resurrection-probe")
  (d/create-database cfg)
  (let [conn (d/connect cfg)
        store (:store @conn)]
    (d/transact conn [{:db/ident :probe/n
                       :db/valueType :db.type/long
                       :db/cardinality :db.cardinality/one}])
    ;; OVERWRITE the same 400 entities each round so old index nodes become
    ;; genuinely unreachable from the head (append-only inserts would keep
    ;; every old leaf reachable through persistent structure sharing).
    (d/transact conn (mapv (fn [j] {:db/id (+ 1000 j) :probe/n j}) (range 400)))
    (dotimes [i 5]
      (d/transact conn (mapv (fn [j] {:db/id (+ 1000 j)
                                      :probe/n (+ (* (inc i) 1000) j)})
                             (range 400))))
    (let [hist (async/<!! (versioning/branch-history conn))
          cids (mapv #(get-in % [:meta :datahike/commit-id]) hist)
          ;; cids are head-first; pick a MIDDLE commit that genuinely holds data
          old-cid (nth cids 2)
          before (count (d/q '[:find ?e :where [?e :probe/n]]
                             (d/commit-as-db conn old-cid)))
          real-sweep! kgc/sweep!
          branch-result (atom nil)
          swept
          (with-redefs [kgc/sweep!
                        (fn [& args]
                          ;; MARK is done, SWEEP has not started: create a branch
                          ;; from the old commit, exactly as `bin/seon start`
                          ;; forks a cluster from a published commit id.
                          (when-not @branch-result
                            (reset! branch-result
                                    (try (d/branch! conn old-cid :resurrected) :ok
                                         (catch Throwable e
                                           {:refused (ex-message e)
                                            :type (:type (ex-data e))}))))
                          (apply real-sweep! args))]
            @(d/gc-storage conn (java.util.Date.)))
          resurrected (k/get store :resurrected nil {:sync? true})]
      (println "RESURRECTION PROBE")
      (prn {:source-commit-datoms-before-gc before
            :swept (count swept)
            :branch-during-mark-sweep-window @branch-result
            :branches (k/get store :branches nil {:sync? true})
            :resurrected-head-present? (some? resurrected)
            :resurrected-readable?
            (try (count (d/q '[:find ?e :where [?e :probe/n]]
                             (d/branch-as-db conn :resurrected)))
                 (catch Throwable e (str (type e) " / " (ex-message e))))
            :main-head-readable?
            (try (count (d/q '[:find ?e :where [?e :probe/n]] @conn))
                 (catch Throwable e (str e)))})
      ;; Re-read through a FRESH connection: the live connection's LRU node
      ;; cache can mask a swept node until the process restarts.
      (d/release conn)
      (let [conn2 (d/connect cfg)]
        (println :after-reconnect
                 (try {:resurrected
                       (count (d/q '[:find ?e :where [?e :probe/n]]
                                   (d/branch-as-db conn2 :resurrected)))
                       :resurrected-values
                       (sort (take 3 (d/q '[:find [?n ...] :where [_ :probe/n ?n]]
                                          (d/branch-as-db conn2 :resurrected))))
                       :main (count (d/q '[:find ?e :where [?e :probe/n]] @conn2))}
                      (catch Throwable e (str (type e) " / " (ex-message e)))))
        (d/release conn2)))))
