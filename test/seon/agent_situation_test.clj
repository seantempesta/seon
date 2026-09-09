(ns seon.agent-situation-test
  "The live help situation and its declared agent render."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.env :as env]
            [seon.test-support :as support]))

(defn- seed-situation!
  [connection]
  (db/transact!
   connection
   [{:seon.ns/name 'my.run}
    {:seon.ns/name 'my.message}
    {:seon.ns/name 'my.agents.situation
     :seon.ns/requires [[:seon.ns/name 'my.run]
                        [:seon.ns/name 'my.message]]}
    {:seon.agent/id "situation"
     :seon.agent/namespace [:seon.ns/name 'my.agents.situation]}
    {:seon.turn/id "situation-run"
     :seon.turn/agent [:seon.agent/id "situation"]
     :seon.turn/opened-at #inst "2026-08-12T12:00:00.000-00:00"
     :seon.turn/starting-ns [:seon.ns/name 'my.agents.situation]}
    {:seon.cluster.message/id "unread"
     :seon.cluster.message/to [:seon.agent/id "situation"]
     :seon.cluster.message/content "Read me"
     :seon.cluster.message/at #inst "2026-08-12T12:00:01.000-00:00"}]))

(deftest help-is-the-live-derived-control-surface
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
                :seon.turn/turns-remaining 0
                :seon.agent/protocol-namespaces
                ['my.message 'my.run]}
               situation))
        (is (= '(seon.bootstrap/situation)
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
            (is (.contains text "my.run/complete"))))))))

(deftest the-agent-id-supplier-reads-only-the-turn-environment
  (is (= "situation"
         (env/supplied-agent-id
          (env/environment {:seon.boot/cluster-name "situation"
                            :seon.agent/id "situation"}))))
  (is (= :seon.env/agent-id-absent
         (:seon.error/kind
          (env/supplied-agent-id
           (env/environment {:seon.boot/cluster-name "situation"}))))))
