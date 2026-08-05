;;; Probe for gc-correctness-cas-opus-2026-08-05.md
;;; OWN scratch store only: tmp/gc-cas-probe/store. Never touches data/.
(ns gc-cas-probe
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [datahike.gc-guard :as guard]
            [datahike.versioning :as versioning]
            [konserve.core :as k]))

(def dir "tmp/gc-cas-probe/store")

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
   :keep-history? true
   :schema-flexibility :write})

(defn run []
  (rm-rf "tmp/gc-cas-probe")
  (d/create-database cfg)
  (let [conn (d/connect cfg)
        store (:store @conn)]
    (d/transact conn [{:db/ident :probe/n
                       :db/valueType :db.type/long
                       :db/cardinality :db.cardinality/one}])
    (dotimes [i 5]
      (d/transact conn (mapv (fn [j] {:probe/n (+ (* i 1000) j)}) (range 400))))
    (let [hist (async/<!! (versioning/branch-history conn))
          cids (mapv #(get-in % [:meta :datahike/commit-id]) hist)
          head-cid (first cids)
          old-cid (last (butlast cids))
          keys-before (set (map :key (k/keys store {:sync? true})))
          swept @(d/gc-storage conn (java.util.Date.))
          keys-after (set (map :key (k/keys store {:sync? true})))
          old-record (k/get store old-cid nil {:sync? true})]
      (println "PROBE RESULTS")
      (prn {:commit-count (count cids)
            :head-cid head-cid
            :old-cid old-cid
            :keys-before (count keys-before)
            :swept (count swept)
            :keys-after (count keys-after)
            :head-record-present? (some? (k/get store :db nil {:sync? true}))
            :old-commit-record-present? (some? old-record)
            :old-commit-eavt-key-present?
            (when old-record
              (some? (k/get store (:eavt-key old-record) nil {:sync? true})))
            :guard-in-flight-when-quiet? (guard/in-flight? (:id (:store cfg)))
            :head-readable-after-gc?
            (try (count (d/q '[:find ?e :where [?e :probe/n]] @conn))
                 (catch Throwable e (str e)))})
      (println :branch-from-old-commit
               (try (d/branch! conn old-cid :resurrected)
                    (let [rb (k/get store :resurrected nil {:sync? true})]
                      {:branch-created? true
                       :eavt-node-present?
                       (some? (k/get store (:eavt-key rb) nil {:sync? true}))
                       :readable?
                       (try (count (d/q '[:find ?e :where [?e :probe/n]]
                                        (d/branch-as-db conn :resurrected)))
                            (catch Throwable e (str (type e) " " (ex-message e))))})
                    (catch Throwable e {:refused (ex-message e)
                                        :type (:type (ex-data e))})))
      (d/release conn))))
