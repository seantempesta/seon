(ns seon.sci.shown-text-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.render :as render]
            [seon.repl :as repl]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))


(deftest a-plan-item-result-shows-data-instead-of-its-block-render-source
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                     :seon.boot/cluster-name "shown-text"})
     (support/seed-cluster! connection "shown-text")
     (support/transacted! connection
                        (agent/creation-tx {:seon.agent/id "juniper"
                                            :seon.ns/name 'my.agents.juniper
                                            :seon.cluster/name "shown-text"}))
     (support/transacted!
      connection
      [{:my.plan/agent [:seon.agent/id "juniper"]
         :my.plan/objective "Keep generated source out of values"
         :my.plan/current-step "current-item"
         :my.plan/steps
         #{{:db/id "current-item"
            :my.plan.item/id "juniper/render-plan"
            :my.plan.item/title "Render this plan clearly"
            :my.plan.item/done-when "The item is shown as data."}
           {:my.plan.item/id "juniper/verify"
            :my.plan.item/title "Verify dependencies"
            :my.plan.item/needs #{"current-item"}}}}])
     (let [base (support/fork-cluster-ctx connection "shown-text")
           ctx (:seon.sci.eval/ctx
                (evaluation/fork-for-turn
                 {:seon.sci.eval/ctx base :seon.db/db @connection
                  :seon.db/connection connection :seon.agent/id "juniper"}))
           request {:seon.cluster.eval/source "[(my.plan/current) (my.plan/blocked) (my.message/inbox) (my.agent/settings)]"
                    :seon.cluster.eval/ns [:seon.ns/name 'my.agents.juniper]
                    :seon.agent/id "juniper"
                    :seon.sci.eval/ctx ctx
                    :seon.db/db @connection
                    :seon.db/connection connection
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic}
           result (render/call-with-walk-context
                   request #(evaluation/evaluate request))
           shown (repl/value-text result)]
       (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
       (is (= "juniper/render-plan"
              (get-in result [:seon.sci.admit/value 0 :my.plan.item/id])))
       (is (= ["juniper/render-plan"]
              (get-in result [:seon.sci.admit/value 1 0 :my.plan/needs])))
       (is (= [] (get-in result [:seon.sci.admit/value 2])))
       (is (not (str/includes? shown "ExceptionInfo")) shown)
       (is (string? shown))
       (is (str/includes? shown "juniper/render-plan") shown)
       (is (str/includes? shown "Render this plan clearly") shown)
       (is (not (str/includes? shown "format-item-ai")) shown)))))
