(ns seon.cluster.root-seed-digest-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.program :as program]
            [seon.render.value :as value]
            [seon.sci.reader :as reader]
            [seon.test-support :as support]))

(deftest root-seed-declarations-come-from-source
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "root-seed-digest")
      (is (nil? (db/pull @connection [:seon.agent/id]
                         [:seon.agent/id "root"])))
      (is (nil? (db/pull @connection [:seon.ns/name]
                         [:seon.ns/name 'my.agents.root])))
      (cluster/seed-root-agent!
       connection "root-seed-digest" cluster/boot-process-identity)
      (let [database (db/db connection)
            namespace-row (db/pull database
                                   '[* {:seon.ns/refers
                                        [:seon.ns.refer/local
                                         :seon.ns.refer/target-ns
                                         :seon.ns.refer/target-name]}]
                                   [:seon.ns/name 'my.agents.root])
            row (program/canonical-row
                 (program/shapes-in (db/carried-projection database))
                 (value/transacted namespace-row database))
            digest (:seon.program/definition-digest row)]
        (is (= 'my.agents.root (:seon.ns/name row)))
        (is (= '(ns my.agents.root
                  (:require [my.message] [my.turn] [seon.db]
                            [seon.bootstrap :refer [help dir doc]]
                            [clojure.test :refer [deftest is]]))
               (some-> (:seon.ns/source row) edn/read-string)))
        (is (= #{'my.message 'my.turn 'seon.db 'seon.bootstrap 'clojure.test}
               (:seon.ns/requires row)))
        (is (and (string? digest) (re-matches #"[0-9a-f]{64}" digest)))
        (is (= (program/definition-digest row) digest))
        (is (= "root" (:seon.agent/id
                        (db/pull database [:seon.agent/id]
                                 [:seon.agent/id "root"]))))
        (is (some? (db/q '[:find ?turn . :where
                          [?agent :seon.agent/id "root"]
                          [?turn :seon.turn/agent ?agent]] database)))))))

(deftest declaration-digest-ignores-reader-positions
  (support/with-database
    (fn [connection]
      (let [projection (db/carried-projection (db/db connection))
            source "(ns sample.reader-position (:require [clojure.test :refer [is]]))"
            events (mapv (fn [text]
                           (first (reader/read
                                   {:seon.sci.reader/text text
                                    :seon.config.eval.result/max-source (count text)})))
                         [source (str "\n\n" source)])
            rows (mapv #(program/declaration-row projection % :contracted :agent)
                       events)]
        (is (= [1 3] (mapv :seon.sci.reader/line events)))
        (is (every? #(= source (:seon.ns/source %)) rows))
        (is (apply = (map :seon.program/definition-digest rows)))
        (is (every? #(= (program/definition-digest %)
                       (:seon.program/definition-digest %)) rows))))))
