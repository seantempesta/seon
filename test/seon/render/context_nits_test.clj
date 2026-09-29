(ns seon.render.context-nits-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.render.transcript :as transcript]
            [seon.test-support :as support]))

(deftest generated-runtime-and-notes-reads-return-useful-values
  (support/with-database
   (fn [connection]
     (is (:db-after
          (db/transact! connection
            (into (support/agents-tx @connection ["root" "juniper"])
              [{:seon.message/id "trigger" :seon.message/content "Read the orders."
                :seon.message/from [:seon.agent/id "root"]
                :seon.message/to [:seon.agent/id "juniper"]}
               {:seon.agent/id "juniper"
                :seon.agent/runtime
                {:seon.runtime/agent [:seon.agent/id "juniper"]
                 :seon.runtime/trigger [:seon.message/id "trigger"]}}]))))
     (let [runtime-unit {:seon.db/db @connection
                         :seon.render/value
                         (db/pull @connection '[*] [:seon.runtime/agent [:seon.agent/id "juniper"]])}]
       (is (= {:seon.message/id "trigger" :seon.message/content "Read the orders."
               :seon.message/from {:seon.agent/id "root"}}
              (:seon.runtime/trigger (transcript/render-runtime-ai runtime-unit))))))))
