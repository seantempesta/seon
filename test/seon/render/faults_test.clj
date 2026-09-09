(ns seon.render.faults-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.render :as render]
            [seon.render.web :as web]
            [seon.test-support :as support]))

(deftest pulled-fault-concern-uses-the-entity-pair
  (support/with-database
   (fn [connection]
     (let [effective (config/defaults)
           caps (config/result-caps effective)
           ctx (support/fork-cluster-ctx connection)
           fault (assoc
                  (error/normalize
                   {:seon.error/source {:seon.error/kind :seon.ai/no-credential
                                        :seon.error/message "No credential configured."}
                    :seon.error/id "fault-render-probe"
                    :seon.error/at (java.util.Date. 0)
                    :seon.error/process "fault-render-probe"
                    :seon.sci.admit/caps caps
                    :seon.config.error/max-evidence-bytes
                    (:seon.config.error/max-evidence-bytes effective)})
                  :seon.instrument/fn "seon.ai/complete"
                  :seon.error/run [:seon.turn/id "fault-render-turn"])
           written (db/transact!
                    connection
                    [{:seon.turn/id "fault-render-turn"}
                     fault])
           pulled (db/pull @connection '[* {:seon.error/run [:db/id :seon.turn/id]}]
                           [:seon.error/id "fault-render-probe"])
           request {:seon.db/db @connection
                    :seon.db/connection connection
                    :seon.sci.eval/ctx ctx
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.config/on-core-error :panic
                    :seon.render/profile (render/agent-render-profile effective)
                    :seon.render/captured-calls (atom {})
                    :seon.render/captured-invocations (atom {})}
           experiment (fn [output]
                        (#'web/selected-unit-experiment
                         request :seon.error/_agent output [pulled]
                         [:seon.error/id "fault-render-probe"]
                         {:seon.render.data/path [] :seon.render.data/offset 0}))
           ai (get-in (experiment :seon.render/ai)
                      [:seon.render/previews 'seon.error/render-faults-ai])
           html (get-in (experiment :seon.render/html)
                        [:seon.render/previews 'seon.error/render-faults-html])]
       (is (not (:seon.error/kind written)))
       (is (int? (:db/id pulled)) "the subject is a real pulled entity")
       (is (string? ai)
           (pr-str (#'render/invoke-selected
                    (assoc request :seon.render/value [pulled]
                           :seon.render.walk/attribute :seon.error/agent
                           :seon.render/output :seon.render/ai)
                    'seon.error/render-faults-ai)))
       (is (= :seon.ai/no-credential (:seon.error/kind (edn/read-string ai))))
       (doseq [expected ["Faults (1)" "No credential configured."
                         "seon.ai/no-credential" "seon.ai/complete"
                         "1970" "fault-render-turn" "Inspect durable evidence"]]
         (is (str/includes? (pr-str html) expected) expected))
       (is (not (str/includes? (pr-str html) "items, depth")))
       (is (not (str/includes? ai "items, depth")))))))
