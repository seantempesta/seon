(ns seon.context-capture-test
  (:require [clojure.test :refer [deftest is]]
            [seon.context :as context]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(deftest context-contributions-reference-existing-evaluations
  (test-support/with-database
   (fn [connection]
     (let [run-id "context-reference-run"
           evaluation-ids ["context-reference-e0" "context-reference-e1"]
           evaluation-refs (set (map #(vector :seon.cluster.eval/id %)
                                     evaluation-ids))
           seed (db/transact!
                 connection
                 (into [{:seon.turn/id run-id}]
                       (map-indexed
                        (fn [ordinal id]
                          {:seon.cluster.eval/id id
                           :seon.cluster.eval/run [:seon.turn/id run-id]
                           :seon.cluster.eval/ordinal ordinal
                           :seon.cluster.eval/result-edn (pr-str ordinal)})
                        evaluation-ids)))
           _ (is (nil? (:seon.error/kind seed)))
           rendered {:seon.db/db @connection
                     :seon.cluster.prompt/text "existing evaluated context"
                     :seon.context/contributions
                     [{:seon.render.block/name :example/context
                       :seon.context.contribution/position 0
                       :seon.context.contribution/text "existing evaluated context"
                       :seon.context.contribution/hash
                       (context/contribution-hash "existing evaluated context")
                       :seon.context.contribution/tokens 7
                       :seon.context.contribution/evaluations evaluation-refs}]}
           transaction (context/capture-tx
                        {:seon.turn/id run-id
                         :seon.cluster.prompt/rendered-context rendered})
           capture-id (:seon.context.capture/id (first transaction))
           committed (db/transact! connection transaction)
           selected (db/pull
                     @connection
                     [{:seon.context.capture/contributions
                       [:seon.context.contribution/position
                        {:seon.context.contribution/evaluations
                         [:seon.cluster.eval/id :seon.cluster.eval/ordinal]}]}]
                     [:seon.context.capture/id capture-id])
           contribution (first (:seon.context.capture/contributions selected))]
       (is (nil? (:seon.error/kind committed)))
       (is (= 0 (:seon.context.contribution/position contribution)))
       (is (= (set evaluation-ids)
              (set (map :seon.cluster.eval/id
                        (:seon.context.contribution/evaluations contribution)))))
       (is (= 2 (db/q '[:find (count ?e) .
                        :where [?e :seon.cluster.eval/id]] @connection))
           "capturing context references both original evaluations without copying them")
       (is (not-any? #(or (:seon.cluster.eval/source %)
                         (:seon.cluster.eval/result-edn %))
                     (:seon.context.capture/contributions (first transaction)))
           "contributions carry references rather than source or result copies")))))
