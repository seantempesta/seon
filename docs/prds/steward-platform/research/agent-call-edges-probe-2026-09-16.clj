; Evaluate these forms separately through MCP JVM mode on default.
; Read each turn's closed-tx before submitting the next source.
; The recorded run used agent-call-edges-live-proof; use a fresh scratch
; identity for another run. This script never changes default's lifecycle.
(comment
  (def edge-instance (get @seon.operator.runtime/running-instances "default"))
  (def edge-connection (seon.operator/connection "default"))
  (def edge-request
    {:seon.turn.loop/cluster (:seon.turn.loop/cluster edge-instance)
     :seon.agent/routing (:seon.agent/routing edge-instance)
     :seon.agent/id "agent-call-edges-live-proof"})

  (seon.db/transact!
   edge-connection
   [{:seon.agent/id "agent-call-edges-live-proof"
     :seon.agent/namespace {:seon.ns/name 'my.agents.agent-call-edges-live-proof}
     :seon.agent/settings
     {:seon.config/agent [:seon.agent/id "agent-call-edges-live-proof"]
      :seon.config.ai/no-provider true}}])
  (seon.cluster.agent/arm! edge-request)

  (seon.turn/virtual-turn!
   (assoc edge-request :seon.cluster.reply/text
          "(defn target {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"))
  ; Observed turn e03045b2b832, closed transaction 536871660.
  (seon.db/pull (seon.db/db edge-connection) [:seon.turn/closed-tx]
                [:seon.turn/id "e03045b2b832"])

  (seon.turn/virtual-turn!
   (assoc edge-request :seon.cluster.reply/text
          "(clojure.test/deftest target-test (clojure.test/is (= 3 (target 2))))"))
  ; Observed turn 4c7924597d69, closed transaction 536871673.
  (seon.db/pull (seon.db/db edge-connection) [:seon.turn/closed-tx]
                [:seon.turn/id "4c7924597d69"])
  (seon.db/pull
   (seon.db/db edge-connection)
   '[:db/id :seon.test/sym {:seon.fn/calls [:seon.fn/sym]}]
   [:seon.test/sym "my.agents.agent-call-edges-live-proof/target-test"])
  (def edge-before
    (seon.test/reach-digest
     (seon.db/db edge-connection)
     "my.agents.agent-call-edges-live-proof/target-test"))

  (seon.turn/virtual-turn!
   (assoc edge-request :seon.cluster.reply/text
          "(defn target {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))"))
  ; Observed turn 3df996866291, closed transaction 536871681.
  (seon.db/pull (seon.db/db edge-connection) [:seon.turn/closed-tx]
                [:seon.turn/id "3df996866291"])
  {:seon.test/before edge-before
   :seon.test/after
   (seon.test/reach-digest
    (seon.db/db edge-connection)
    "my.agents.agent-call-edges-live-proof/target-test")}
  (seon.cluster.agent/disarm! edge-request))
