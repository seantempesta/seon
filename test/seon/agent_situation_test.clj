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
    {:seon.cluster.agent/id "situation"
     :seon.cluster.agent/namespace [:seon.ns/name 'my.agents.situation]}
    {:seon.cluster.run/id "situation-run"
     :seon.cluster.run/agent [:seon.cluster.agent/id "situation"]
     :seon.cluster.run/opened-at #inst "2026-08-12T12:00:00.000-00:00"
     :seon.cluster.run/opening-commit-id
     #uuid "11111111-1111-1111-1111-111111111111"
     :seon.cluster.run/starting-ns [:seon.ns/name 'my.agents.situation]}
    [:db/add [:seon.cluster.agent/id "situation"]
     :seon.cluster.agent/run [:seon.cluster.run/id "situation-run"]]
    {:seon.cluster.message/id "unread"
     :seon.cluster.message/to [:seon.cluster.agent/id "situation"]
     :seon.cluster.message/content "Read me"
     :seon.cluster.message/at #inst "2026-08-12T12:00:01.000-00:00"}]))

(deftest help-is-the-live-derived-control-surface
  (support/with-database
    (fn [connection]
      (seed-situation! connection)
      (let [situation (bootstrap/situation @connection "situation")
            stored (db/pull @connection '[*]
                            [:seon.cluster.agent/id "situation"])]
        (is (= {:seon.cluster.agent/id "situation"
                :seon.cluster.agent/namespace-ref
                [:seon.ns/name 'my.agents.situation]
                :seon.cluster.agent/unread-message-count 1
                :seon.cluster.agent/open-run-ref
                [:seon.cluster.run/id "situation-run"]
                :seon.cluster.run/turns-remaining 0
                :seon.cluster.agent/protocol-namespaces
                ['my.message 'my.run]}
               situation))
        (is (= '(seon.bootstrap/situation)
               (macroexpand '(seon.bootstrap/help))))
        (is (= {:seon.repl/comment
                "; A new run just opened. Why am I awake — do I have messages?"
                :seon.repl/form '(help)}
               (agent/situation-form situation)))
        (is (not-any? #(contains? stored %)
                      [:seon.cluster.agent/namespace-ref
                       :seon.cluster.agent/unread-message-count
                       :seon.cluster.agent/open-run-ref
                       :seon.cluster.agent/protocol-namespaces])
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
                            :seon.cluster.agent/id "situation"}))))
  (is (= :seon.env/agent-id-absent
         (:seon.error/kind
          (env/supplied-agent-id
           (env/environment {:seon.boot/cluster-name "situation"}))))))
