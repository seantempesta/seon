(ns gc-history-probe
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.test-support :as test-support]))

(def probe-schema
  [{:db/ident :audit.blob/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.cluster.eval/result-blob
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn run-probe
  "Prove current-reference survival and history-only collection in a real store."
  [& _]
  (let [root (str "tmp/audit-20260801b/gc-store-" (random-uuid))
        store-dir (str (io/file root "store"))]
    (.mkdirs (io/file root))
    (let [opened (store/open-store! {:seon.store/dir store-dir})
          connection (:seon.store/connection opened)]
      (try
        (d/transact connection probe-schema)
        (let [current-content "blob whose reference remains current"
              history-content "blob whose reference survives only in history"
              current-digest (blob/put! connection current-content)
              history-digest (blob/put! connection history-content)
              asserted
              (d/transact
               connection
               [{:audit.blob/id "current"
                 :seon.cluster.eval/result-blob current-digest}
                {:audit.blob/id "history"
                 :seon.cluster.eval/result-blob history-digest}])
              asserted-t (:max-tx (:db-after asserted))]
          (d/transact
           connection
           [[:db/retract
             [:audit.blob/id "history"]
             :seon.cluster.eval/result-blob
             history-digest]])
          (let [current-digests
                (set
                 (d/q '[:find [?digest ...]
                        :where [_ :seon.cluster.eval/result-blob ?digest]]
                      @connection))
                historical-digests
                (set
                 (d/q '[:find [?digest ...]
                        :where [_ :seon.cluster.eval/result-blob ?digest]]
                      (d/history @connection)))
                as-of-digests
                (set
                 (d/q '[:find [?digest ...]
                        :where [_ :seon.cluster.eval/result-blob ?digest]]
                      (d/as-of @connection asserted-t)))
                swept (registry/collect! opened)
                current-after (blob/get connection current-digest)
                history-after (blob/get connection history-digest)]
            (prn
             {:audit/current-digests current-digests
              :audit/historical-digests historical-digests
              :audit/as-of-digests as-of-digests
              :audit/swept swept
              :audit/current-blob-survived? (= current-content current-after)
              :audit/history-blob-survived? (= history-content history-after)})))
        (finally
          (store/release-store! opened)
          (test-support/delete-recursively! root))))))

(apply run-probe *command-line-args*)
