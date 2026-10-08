(ns seon.render.faults-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.render :as render]
            [seon.test-support :as support]))

(deftest pulled-fault-concern-uses-the-entity-pair
  (support/with-database
   (fn [connection]
     (let [effective config/defaults
           caps (config/result-caps effective)
           ctx (support/fork-cluster-ctx connection)
           _ (support/transacted! connection (into (support/agent-tx @connection "fault-render-agent")
                                               [{:seon.turn/id "fault-render-turn"
                                                 :seon.turn/agent [:seon.agent/id "fault-render-agent"]
                                                 :seon.turn/opened-tx "datomic.tx"}]))
           recording (error/recording
                      (db/db connection)
                      {:seon.error/source {:seon.error/at (java.util.Date. 0) :seon.error/layer :seon.render.faults-test/fixture :seon.error/operation 'seon.render.faults-test/pulled-fault-concern-uses-the-entity-pair :seon.render/refused-member :seon.render/output
                                           :seon.error/message "No credential configured."
                                           :seon.error/data {:seon.instrument/fn 'seon.ai/complete}}
                       :seon.error/id "fault-render-probe" :seon.error/at (java.util.Date. 0)
                       :seon.error/process "fault-render-probe" :seon.sci.admit/caps caps
                       :seon.config.error/max-evidence-bytes 16384
                       :seon.config.error/recurrence-limit 100
                       :seon.turn/id "fault-render-turn"})
           written (db/transact! connection (:seon.db/tx-data recording))
           pulled (db/pull (db/db connection)
                           '[* {:seon.error/fn [:db/id :seon.fn/sym]}
                             {:seon.error/occurrences [* {:seon.error.occurrence/turn [:seon.turn/id]}]}]
                           (:seon.error/ref recording))
           request {:seon.db/db (db/db connection) :seon.db/connection connection
                    :seon.sci.eval/ctx ctx :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.render/profile (render/agent-render-profile effective)
                    :seon.render/value pulled
                    :seon.render.call/id [:fault-test/entity]}
           ai (render/render-call (assoc request :seon.render/output :seon.render/ai))
           html (render/render-call (assoc request :seon.render/output :seon.render/html))]
       (is (:db-after written))
       (is (int? (:db/id pulled)))
       (is (str/includes? (str ai) "Occurrences: 1"))
       (doseq [expected ["No credential configured." "seon.ai/complete" "1970"
                         "fault-render-turn" "Inspect evidence" "Occurrences: 1"]]
         (is (str/includes? (pr-str html) expected)))))))
