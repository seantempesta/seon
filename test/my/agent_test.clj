(ns my.agent-test
  (:require [clojure.test :refer [deftest is]]
            [seon.agent :as agent]
            [seon.cluster.agent :as cluster.agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as sci]
            [seon.test-support :as support]))

(deftest settings-are-one-owned-component-with-a-derived-render-pair
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "settings-owner"))
     (agent/settings! {:seon.config.eval/time-limit-ms 1234
                       :seon.config.agent/turn-completion-backstop-ms 5678}
                      connection "settings-owner")
     (let [database @connection
           settings (agent/settings database "settings-owner")
           component (get-in (db/pull database
                                     [{:seon.agent/settings [:db/id]}]
                                     [:seon.agent/id "settings-owner"])
                             [:seon.agent/settings :db/id])
           matches (schema/matching-shapes-in (schema/projection-from-database database) settings)]
       (is (= {:seon.config.eval/time-limit-ms 1234
               :seon.config.agent/turn-completion-backstop-ms 5678}
              settings))
       (is (some #(= 'seon.agent/render-settings-ai (:seon.render/ai %)) matches))
       (is (not-any? #(= 'seon.agent/render-settings-ai (:seon.render/ai %))
                     (schema/matching-shapes-in (schema/projection-from-database database)
                                               {:my.plan.item/title "Unrelated"})))
       (is (integer? component))))))

(deftest settings-updates-preserve-one-component-and-agent-isolation
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agents-tx @connection ["settings-owner" "other-owner"]))
     (is (= {:seon.config.ai/model "example-model"
             :seon.config.run/max-episode-runs 4}
            (agent/settings! {:seon.config.ai/model "example-model"
                              :seon.config.run/max-episode-runs 4}
                             connection "settings-owner")))
     (let [component #(get-in (db/pull @connection
                                       [{:seon.agent/settings [:db/id]}]
                                       [:seon.agent/id "settings-owner"])
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

(defn- agent-value
  "Evaluate `source` as `agent-id` in `handle`'s context; its value or refusal."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :seon.agent/id :string] :any]}
  [handle agent-id source]
  (let [result (sci/evaluate
                {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                 :seon.db/db (db/db (:seon.db/connection handle))
                 :seon.agent/id agent-id
                 :seon.cluster.eval/ns [:seon.ns/name 'user]
                 :seon.cluster.eval/source source
                 :seon.sci.eval/time-limit-ms 5000
                 :seon.sci.admit/caps (config/result-caps config/defaults)})]
    (or (:seon.cluster.eval/error result) (:seon.sci.admit/value result))))

(defn- held-branch
  "Evaluate (my.agent/branch) for `agent-id` in `handle`'s context; its value or refusal."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :seon.agent/id] :any]}
  [handle agent-id]
  (agent-value handle agent-id (pr-str (list 'my.agent/branch {:seon.agent/id agent-id}))))

(deftest branch-mode-is-the-acquisition-decision-not-a-cluster-label
  ;; The member branch inherits default's cluster entities. Before the fix the mode
  ;; compared the agent entity's branch with the label branch of an arbitrary
  ;; cluster entity, so an agent live on the member's branch read :isolated.
  (let [parent (support/execution-handle db/*conn*)
        connection (:seon.db/connection parent)
        parent-branch (get-in @connection [:config :branch])
        created! #(support/transacted! connection
                                       (cluster.agent/creation-tx
                                        {:seon.agent/id % :seon.ns/name (symbol (str "my.agents." %))
                                         :seon.cluster/name "default"}))
        _ (run! created! ["mode-live" "mode-isolated"])
        live (cluster.agent/acquire-context! parent "mode-live")
        isolated (cluster.agent/acquire-context! parent "mode-isolated" {:seon.agent/isolate? true})]
    (try
      (is (= {:seon.agent/branch parent-branch :seon.agent/mode :live}
             (held-branch live "mode-live")))
      (is (= {:seon.agent/branch (:seon.agent/branch isolated) :seon.agent/mode :isolated}
             (held-branch isolated "mode-isolated")))
      (is (not= parent-branch (:seon.agent/branch isolated)))
      (finally (cluster.agent/release-context! isolated)))))

(deftest settings!-declares-the-dials-it-takes
  ;; The operations query is an AVET seek on input refs: settings! is found on
  ;; the settings component only because its contract names that schema.
  (let [parent (support/execution-handle db/*conn*)
        connection (:seon.db/connection parent)
        _ (support/transacted! connection
                               (cluster.agent/creation-tx
                                {:seon.agent/id "dial-owner" :seon.ns/name 'my.agents.dial-owner
                                 :seon.cluster/name "default"}))
        handle (cluster.agent/acquire-context! parent "dial-owner" {:seon.agent/isolate? true})]
    (try
      (is (contains? (set (map symbol (db/q '[:find [?sym ...]
                                              :where [?arity :seon.fn.arity/input-refs :seon.config/agent-overlay]
                                                     [?function :seon.fn/arities ?arity]
                                                     [?function :seon.fn/sym ?sym]]
                                            @connection)))
                     'my.agent/settings!))
      (let [value (agent-value handle "dial-owner"
                               "(my.agent/settings! {:seon.config.eval/time-limit-ms 1234})")]
        (is (= 1234 (:seon.config.eval/time-limit-ms value)) (pr-str value))
        (is (= value (agent/settings (db/db (:seon.db/connection handle)) "dial-owner"))))
      (finally (cluster.agent/release-context! handle)))))
