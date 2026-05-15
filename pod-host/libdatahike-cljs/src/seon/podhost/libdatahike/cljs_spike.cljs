(ns seon.podhost.libdatahike.cljs-spike
  "CLJS-1: smallest possible datahike-on-CLJS smoke test.

   Creates a `:memory` datahike DB, transacts schema + a few entities,
   queries by attribute, prints the result. The artifact is the test:
   running `node out/spike.js` must print the expected set."
  (:require [datahike.api :as d]
            [cljs.core.async :refer [<!]])
  (:require-macros [cljs.core.async :refer [go]]))

(def cfg
  {:store              {:backend :memory
                        :id #uuid "1d50b780-0000-0000-0000-000000000001"}
   :schema-flexibility :write
   :keep-history?      false})

(def schema
  [{:db/ident       :name
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :rank
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}])

(def seed
  [{:name "Alpha"      :rank 1}
   {:name "Seon"      :rank 2}
   {:name "Datahike"  :rank 3}])

(def expected
  #{["Alpha" 1] ["Seon" 2] ["Datahike" 3]})

(defn -main* []
  (println "[spike] creating :memory db with cfg" (pr-str cfg))
  (go
    ;; All datahike-CLJS calls must be async — :sync? true is JVM-only.
    (<! (d/create-database cfg))
    (let [conn (<! (d/connect cfg {:sync? false}))]
      (<! (d/transact! conn schema))
      (let [tx (<! (d/transact! conn seed))]
        (println "[spike] transact returned" (count (:tx-data tx)) "datoms"))
      (let [rows (d/q '[:find ?name ?rank
                        :where
                        [?e :name ?name]
                        [?e :rank ?rank]]
                      @conn)
            ok?  (= rows expected)]
        (println "[spike] query result:" (pr-str rows))
        (println "[spike] expected:    " (pr-str expected))
        (println (if ok? "[spike] PASS" "[spike] FAIL"))
        (when-not ok? (js/process.exit 1))))))

(defn main [& _args]
  (-main*))
