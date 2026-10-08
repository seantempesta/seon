(ns seon.sci.shown-text-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.repl :as repl]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))


(deftest a-task-steps-result-shows-data-instead-of-its-block-render-source
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                     :seon.boot/cluster-name "shown-text"})
     (support/seed-cluster! connection "shown-text")
     (support/transacted! connection
                        (agent/creation-tx {:seon.agent/id "juniper"
                                            :seon.ns/name 'my.agents.juniper
                                            :seon.cluster/name "shown-text"}))
     (support/assign-task! connection "juniper")
     (let [base (support/fork-cluster-ctx connection "shown-text")
           ctx (:seon.sci.eval/ctx
                (evaluation/fork-for-turn
                 {:seon.sci.eval/ctx base :seon.db/db @connection
                  :seon.db/connection connection :seon.agent/id "juniper"}))
           request {:seon.cluster.eval/source "[(my.task/plan! {:seon.task/steps [\"Render this plan clearly\" \"Verify dependencies\"]}) (my.message/inbox) (my.agent/settings)]"
                    :seon.cluster.eval/ns [:seon.ns/name 'my.agents.juniper]
                    :seon.agent/id "juniper"
                    :seon.sci.eval/ctx ctx
                    :seon.db/db @connection
                    :seon.db/connection connection
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 2000}
           result (support/evaluate request)
           shown (repl/value-text result)]
       (is (nil? (:seon.cluster.eval/error result)))
       (is (= "Render this plan clearly"
              (get-in result [:seon.sci.admit/value 0 :seon.task/current-step])))
       (is (= [] (get-in result [:seon.sci.admit/value 1])))
       (is (not (str/includes? shown "ExceptionInfo")))
       (is (string? shown))
       (is (str/includes? shown "Render this plan clearly"))
       (is (not (str/includes? shown "format-item-ai")))))))
