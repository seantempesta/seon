(ns seon.render.page-settings-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.id :as id]
            [seon.repl :as repl]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(deftest effective-settings-and-authored-plan-examples-work-through-sci
  (load-file "docs/prds/context-generation/research/context_page_probe_2026_09_09.clj")
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "page-settings"})
     (support/seed-cluster! connection "page-settings")
     (doseq [transaction
             [(agent/creation-tx {:seon.agent/id "juniper" :seon.ns/name 'my.agents.juniper
                                  :seon.cluster/name "page-settings"})
              [{:seon.config/agent [:seon.agent/id "juniper"]
                :seon.config.eval/time-limit-ms 1234 :seon.config.ai/no-provider true}
               {:my.plan/agent [:seon.agent/id "juniper"]
                :my.plan/objective "Verify the examples"
                :my.plan/steps [{:my.plan.item/id "existing" :my.plan.item/title "Existing step"
                                 :my.plan.item/position 0}]}]]]
       (let [result (db/transact! connection transaction)]
         (assert (:db-after result) (pr-str result))))
     (let [base (support/fork-cluster-ctx connection "page-settings")
           ctx (:seon.sci.eval/ctx
                (evaluation/fork-for-turn
                 {:seon.sci.eval/ctx base :seon.db/db @connection
                  :seon.db/connection connection :seon.agent/id "juniper"}))
           request {:seon.sci.eval/ctx ctx :seon.agent/id "juniper"
                    :seon.sci.admit/caps (config/result-caps (config/defaults))
                    :seon.sci.eval/time-limit-ms 10000 :seon.config/on-core-error :panic}
           evaluate (fn [source]
                      (evaluation/evaluate (assoc request :seon.db/db @connection
                                                 :seon.cluster.eval/source source)))
           result (evaluate "(seon.agent/effective-settings)")
           groups (:seon.sci.admit/value result)
           values (apply merge groups)
           examples ((resolve 'context-page-probe-2026-09-09/plan-examples))
           step-id (id/id "Verify customer totals" 8)]
       (is (not (:seon.cluster.eval/error result)) (:seon.eval/shown result))
       (is (= ["seon.config.ai" "seon.config.ai.retry" "seon.config.eval" "seon.config.run"]
              (mapv (comp namespace ffirst) (take 4 groups))))
       (is (= 1234 (:seon.config.eval/time-limit-ms values)))
       (is (= (:seon.config.ai/model (config/effective @connection "page-settings"))
              (:seon.config.ai/model values)))
       (is (nat-int? (:my.agent/turns-left values)))
       (is (not (str/includes? (:seon.eval/shown result) "seon.render/ambiguous")))
       (let [added (evaluate (repl/source-text (first examples)))
             step (db/pull @connection [:my.plan.item/id :my.plan.item/title :my.plan.item/position]
                           [:my.plan.item/id step-id])]
         (is (not (:seon.cluster.eval/error added)) (:seon.eval/shown added))
         (is (= {:my.plan.item/id step-id :my.plan.item/title "Verify customer totals"
                 :my.plan.item/position 1} step))
         (is (= 8 (count step-id))))
       (let [removed (evaluate (repl/source-text (second examples)))]
         (is (not (:seon.cluster.eval/error removed)) (:seon.eval/shown removed))
         (is (nil? (db/pull @connection [:my.plan.item/id] [:my.plan.item/id step-id])))
         (is (= 1 (db/q '[:find (count ?step) . :where [_ :my.plan/steps ?step]] @connection))))))))
