(ns seon.receipt-set-attributes-test
  "A settled evaluation's set-valued facts reach the receipt through the real
  writer: one datom per element, nothing for an empty set (live 2026-09-27: a
  whole set in one :db/add was refused and the settlement lost)."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as cluster.agent]
            [seon.db :as db]
            [seon.test-support :as test-support]
            [seon.turn :as turn]))

(def ^:private process
  "process/receipt-sets")

(deftest set-valued-evaluation-facts-settle-one-datom-per-element
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "receipt-sets"
            agent-id "receipt-sets-agent"
            run-id "receipt-sets-run"
            namespace-name 'my.agents.receipt-sets]
        (test-support/seed-cluster! connection cluster-name)
        (test-support/transacted! connection
                                  (cluster.agent/creation-tx
                                   {:seon.agent/id agent-id :seon.ns/name namespace-name
                                    :seon.cluster/name cluster-name}))
        (test-support/transacted! connection
                                  (turn/open-tx {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id]
                                                 :seon.turn/opened-tx "datomic.tx"}))
        (test-support/transacted! connection
                                  (turn/plan-tx {:seon.turn/id run-id :seon.db.process/id process
                                                 :seon.turn/starting-ns [:seon.ns/name namespace-name]
                                                 :seon.turn/sources [{:seon.cluster.eval/source "(inc 1)"
                                                                      :seon.ns/name namespace-name}]}))
        (test-support/transacted! connection
                                  (turn/receipt-settle-tx
                                   {:seon.turn/id run-id
                                    :seon.cluster.eval/ordinal 0
                                    :seon.eval/shown "{:seon.error/message \"refused\"}"
                                    :seon.cluster.eval/error "refused"
                                    :seon.eval/replaced #{'my.agents.receipt-sets/a 'my.agents.receipt-sets/b}
                                    :seon.cluster.eval/repeated-calls #{}}))
        (is (= {:seon.eval/replaced #{'my.agents.receipt-sets/a 'my.agents.receipt-sets/b}}
               (-> (db/pull @connection [:seon.eval/replaced :seon.cluster.eval/repeated-calls]
                            [:seon.cluster.eval/id (turn/receipt-identity run-id 0)])
                   (select-keys [:seon.eval/replaced :seon.cluster.eval/repeated-calls])
                   (update-vals set)))
            "each element is one datom; an empty set writes nothing")))))
