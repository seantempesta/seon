(ns seon.render.page-settings-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(deftest settings-without-overrides-use-the-declared-pair
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "empty-settings")
     (let [created (db/transact! connection
                    (agent/creation-tx {:seon.agent/id "root"
                                        :seon.ns/name 'my.agents.root
                                        :seon.cluster/name "empty-settings"}))]
       (is (:db-after created)))
     (let [database @connection
           component (:seon.agent/settings
                      (db/pull database '[{:seon.agent/settings [*]}] [:seon.agent/id "root"]))
           ctx (support/fork-cluster-ctx connection "empty-settings")]
       (is (:seon.config/agent component))
       (doseq [[output expected] [[:seon.render/ai 'seon.agent/render-settings-ai]
                                  [:seon.render/html 'seon.agent/render-settings-html]]]
         (let [decision (render/selection {:seon.db/db database :seon.sci.eval/ctx ctx
                                           :seon.sci.admit/caps (config/result-caps (support/effective-config))
                                           :seon.sci.eval/time-limit-ms 10000
                                           :seon.render/value component :seon.render/output output
                                           ;; A component renders as its attribute's value.
                                           :seon.render.walk/attribute :seon.agent/settings})]
           (is (= expected (:seon.render.selection/selected decision)))))))))

(deftest effective-settings-work-through-sci
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "page-settings"})
     (support/seed-cluster! connection "page-settings")
     (doseq [transaction
             [(agent/creation-tx {:seon.agent/id "juniper" :seon.ns/name 'my.agents.juniper
                                  :seon.cluster/name "page-settings"})
              [{:seon.config/agent [:seon.agent/id "juniper"]
                :seon.config.eval/time-limit-ms 1234 :seon.config.ai/no-provider true}]]]
       (let [result (db/transact! connection transaction)]
         (assert (:db-after result) (pr-str result))))
     (let [base (support/fork-cluster-ctx connection "page-settings")
           ctx (:seon.sci.eval/ctx
                (evaluation/fork-for-turn
                 {:seon.sci.eval/ctx base :seon.db/db @connection
                  :seon.db/connection connection :seon.agent/id "juniper"}))
           request {:seon.sci.eval/ctx ctx :seon.agent/id "juniper"
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 10000}
           evaluate (fn [source]
                      (support/evaluate (assoc request :seon.db/db @connection
                                                 :seon.cluster.eval/source source)))
           result (evaluate "(seon.agent/effective-settings)")
           groups (:seon.sci.admit/value result)
           values (apply merge groups)]
       (is (not (:seon.cluster.eval/error result)))
       (is (= ["seon.config.ai" "seon.config.eval" "seon.config.run"]
              (mapv (comp namespace ffirst) (take 3 groups))))
       (is (= 1234 (:seon.config.eval/time-limit-ms values)))
       (is (= (:seon.config.ai/model (config/effective @connection "page-settings"))
              (:seon.config.ai/model values)))
       ;; Turn accounting left the settings groups in `0dca8534e`:
       ;; `:my.agent/turns-left` is `:seon.wake/context-inert` and is read at
       ;; its owner on demand, never mirrored into a settings group.
       (is (not (find values :my.agent/turns-left)))
       (is (nat-int? (:seon.sci.admit/value (evaluate "(seon.turn/turns-left)"))))
       (is (not (str/includes? (:seon.eval/shown result) "seon.render/ambiguous")))
       (is (not-any? #(find values %) [:seon.config.ai/api-key-variable
                                      :seon.config.ai/endpoint
                                      :seon.config.ai/chars-per-token-prior]))
       (is (:db-after (db/transact! connection
                       [{:seon.config/agent [:seon.agent/id "juniper"]
                         :seon.config.agent/show-all-settings true}])))
       (let [full (apply merge (:seon.sci.admit/value (evaluate "(seon.agent/effective-settings)")))]
         (is (= "DEEPSEEK_API_KEY" (:seon.config.ai/api-key-variable full)))
         (is (true? (:seon.config.agent/show-all-settings full))))))))
