(ns seon.agent-situation-test
  "The live help situation and its declared agent render."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.bootstrap :as bootstrap]
            [seon.agent]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.env :as env]
            [seon.test-support :as support]
            [seon.program :as program]))

(defn- seed-situation!
  [connection]
  ;; The fixture's own cluster. Its turn bound is the agent's own override:
  ;; the world also carries boot's config entity, and the cluster-default read
  ;; does not select between config entities.
  (support/seed-cluster! connection "situation")
  (support/transacted!
          connection
          (into [(program/declaration-row
            (db/carried-projection @connection)
            {:seon.ns/name 'my.agents.situation :seon.ns/source "(ns my.agents.situation)"
             :seon.ns/requires #{'my.turn 'my.message}}
            :all :agent)]
                cat
                [(support/agent-tx @connection "situation" 'my.agents.situation)
                 [{:seon.turn/id "situation-run" :seon.turn/agent [:seon.agent/id "situation"] :seon.turn/opened-tx "datomic.tx" :seon.turn/starting-ns [:seon.ns/name 'my.agents.situation]}
                  {:seon.message/id "unread" :seon.message/to [:seon.agent/id "situation"] :seon.message/content "Read me"}]]))
  (is (= 2 (:seon.config.run/max-episode-runs
             (seon.agent/settings! {:seon.config.run/max-episode-runs 2} connection "situation")))))

(deftest situation-is-the-live-derived-control-surface
  (support/with-database
    (fn [connection]
      (seed-situation! connection)
      (let [situation (bootstrap/situation @connection "situation")
            stored (db/pull @connection '[*]
                            [:seon.agent/id "situation"])]
        (is (= {:seon.agent/id "situation"
                :seon.agent/namespace-ref
                [:seon.ns/name 'my.agents.situation]
                :seon.agent/unread-message-count 1
                :seon.agent/open-run-ref
                [:seon.turn/id "situation-run"]
                :seon.turn/turns-remaining 2
                :seon.agent/protocol-namespaces
                ['my.message 'my.turn]}
               situation))
        (is (= '(seon.bootstrap/help-value)
               (macroexpand '(seon.bootstrap/help))))
        (is (= {:seon.repl/comment
                "; A new run just opened. Why am I awake — do I have messages?"
                :seon.repl/form '(help)}
               (agent/situation-form situation)))
        (is (not-any? #(contains? stored %)
                      [:seon.agent/namespace-ref
                       :seon.agent/unread-message-count
                       :seon.agent/open-run-ref
                       :seon.agent/protocol-namespaces])
            "every situation member is derived, never stored on the agent")
        (testing "the situation shape owns real orientation prose"
          (let [text (agent/render-situation-ai situation)]
            (is (string? text))
            (is (.contains text "You are agent situation"))
            (is (.contains text "my.task/send-back!"))))))))

(deftest the-agent-id-supplier-reads-only-the-turn-environment
  (is (= "situation"
         (env/supplied-agent-id
          (env/environment {:seon.boot/cluster-name "situation"
                            :seon.agent/id "situation"}))))
  (is (= "situation"
         (:seon.env/agent-environment
          (env/supplied-agent-id
           (env/environment {:seon.boot/cluster-name "situation"}))))))
