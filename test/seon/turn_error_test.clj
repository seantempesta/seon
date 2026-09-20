(ns seon.turn-error-test
  (:require [clojure.test :refer [deftest is]]
            [seon.schema]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest system-turn-preserves-missing-agent-as-a-declared-refusal
  (support/with-database
   (fn [connection]
     (let [result (turn/system-turn
                   {:seon.turn.loop/cluster
                    (support/cluster-handle
                     {:seon.db/connection connection :seon.cluster/name "system-refusal"
                      :seon.db.process/id "system-refusal-test"
                      :seon.sci.eval/ctx (support/fork-cluster-ctx connection)})
                    :seon.agent/id "absent-system-agent"
                    :seon.turn/write? false})]
       (is ((seon.schema/projection-validator
             (seon.schema/handed-projection) :seon.turn/refused-error) result))
       (is (= :seon.turn/agent-namespace-missing (:seon.turn/rule result)))
       (is (not (contains? result :seon.render.walk/units)))))))
