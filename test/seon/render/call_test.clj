(ns seon.render.call-test
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.render :as render]
            [seon.test-support :as support]))

(deftest retained-calls-accept-keyword-and-vector-identities
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           captured (atom {})
           base {:seon.db/db @connection
                 :seon.sci.eval/ctx ctx
                 :seon.render/value 7
                 :seon.sci.admit/caps (config/result-caps (config/defaults))
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
