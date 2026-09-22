(ns seon.render.call-test
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.test-support :as support]))

(deftest declared-producer-refusals-stop-context-rendering
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           failure (config/effective database "absent-render-config")
           profile (render/request-profile
                    {:seon.db/db failure
                     :seon.schema/projection (db/carried-projection database)})
           request {:seon.db/db database
                    :seon.db/connection connection
                    :seon.agent/id "absent-render-agent"
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                    :seon.config/on-core-error :record
                    :seon.render/profile (render/agent-render-profile config/defaults)}
           opening (render/acquire-context!
                    (assoc request :seon.turn/id "absent-render-turn"))
           history (render/acquire-context! request)]
       (is (= :seon.render/profile (:seon.render/refused-member profile)))
       (is (= :seon.config/cluster (:seon.config/error-key profile)))
       (is (= (:seon.error/message failure) (:seon.error/message profile)))
       (is (= :seon.turn/opened-tx (:seon.render.web/refused-member opening)))
       (is (true? (:seon.turn/missing-opening-datom opening)))
       (is (= :seon.render.history/entries (:seon.render.web/refused-member history)))
       (is (= "The history lookup does not name an agent." (:seon.error/message history)))))))

(deftest retained-calls-accept-keyword-and-vector-identities
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           captured (atom {})
           base {:seon.db/db @connection
                 :seon.sci.eval/ctx ctx
                 :seon.render/value 7
                 :seon.sci.admit/caps (config/result-caps config/defaults)
                 :seon.sci.eval/time-limit-ms 2000
                 :seon.config/on-core-error :panic
                 :seon.render/output :seon.render/ai
                 :seon.render/captured-calls captured}
           keyword-id :seon.render.web/root-acquisition
           first-output (render/render-call (assoc base :seon.render.call/id keyword-id))
           retained @captured]
       (is (string? first-output) (pr-str first-output))
       (is (map? (get retained keyword-id)))
       (doseq [call-id [keyword-id [:seon.render/ai [:seon.agent/id "juniper"]]]]
         (is (= first-output
                (render/render-call
                 (assoc base
                        :seon.render.call/id call-id
                        :seon.render/retained-calls retained
                        :seon.render/candidate-call-ids (set [keyword-id call-id]))))))))))
