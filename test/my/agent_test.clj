(ns my.agent-test
  (:require [clojure.test :refer [deftest is]]
            [my.agent :as agent]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest settings-are-one-owned-component-with-a-derived-render-pair
  (support/with-database
   (fn [connection]
     (db/transact! connection
                   [{:seon.cluster.agent/id "settings-owner"
                     :seon.agent/settings
                     {:seon.config.eval/time-limit-ms 1234
                      :seon.config.agent/turn-completion-backstop-ms 5678}}])
     (let [database @connection
           settings (agent/settings database "settings-owner")
           component (get-in (db/pull database
                                     [{:seon.agent/settings [:db/id]}]
                                     [:seon.cluster.agent/id "settings-owner"])
                             [:seon.agent/settings :db/id])
           matches (schema/matching-shapes-in (schema/projection-from-database database) settings)]
       (is (= {:seon.config.eval/time-limit-ms 1234
               :seon.config.agent/turn-completion-backstop-ms 5678}
              settings))
       (is (some #(= 'my.agent/render-settings-ai (:seon.render/ai %)) matches))
       (is (not-any? #(= 'my.agent/render-settings-ai (:seon.render/ai %))
                     (schema/matching-shapes-in (schema/projection-from-database database)
                                               {:my.plan.item/title "Unrelated"})))
       (db/transact! connection
                     [[:db.fn/retractEntity [:seon.cluster.agent/id "settings-owner"]]])
       (is (nil? (db/pull @connection '[*] component)))))))

(deftest settings-updates-preserve-one-component-and-agent-isolation
  (support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.cluster.agent/id "settings-owner"}
                              {:seon.cluster.agent/id "other-owner"}])
     (is (= {:seon.config.ai/model "example-model"
             :seon.config.run/max-episode-runs 4}
            (agent/settings! {:seon.config.ai/model "example-model"
                              :seon.config.run/max-episode-runs 4}
                             connection "settings-owner")))
     (let [component #(get-in (db/pull @connection
                                       [{:seon.agent/settings [:db/id]}]
                                       [:seon.cluster.agent/id "settings-owner"])
                               [:seon.agent/settings :db/id])
           first-id (component)]
       (is (integer? first-id))
       (is (= {:seon.config.ai/model "example-model"
               :seon.config.run/max-episode-runs 4
               :seon.config.eval/time-limit-ms 1234}
              (agent/settings! {:seon.config.eval/time-limit-ms 1234}
                               connection "settings-owner")))
       (is (= first-id (component)))
       (is (= {} (agent/settings @connection "other-owner")))))))
