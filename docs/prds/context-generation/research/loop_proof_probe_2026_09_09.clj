(ns loop-proof-probe-2026-09-09
  (:require [clojure.string :as str]
            [seon.ai :as ai]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.operator.runtime :as runtime]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.turn :as turn]))

(defn snapshot
  "Measure saved bytes and no-provider/wake facts in an explicit cluster."
  [cluster-name agent-id]
  (let [handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))
        database @(:seon.db/connection handle)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [saved (evaluation/of-agent database agent-id)
             text (str/join "\n\n" (map repl/render-ai saved))
             encoded (.getBytes text "UTF-8")]
         {:seon.test/evaluations (count saved)
          :seon.test/sources (mapv :seon.cluster.eval/source saved)
          :seon.test/bytes (alength encoded)
          :seon.test/sha256 (schema/sha-256 [encoded])
          :seon.test/unanswered (count (turn/unanswered-wakes database agent-id {}))
          :seon.test/answer-t (turn/latest-answering-turn-t database agent-id)
          :seon.test/no-provider
          (:seon.config.ai/no-provider
           (merge (config/effective database cluster-name)
                  (ai/agent-overlay database agent-id)))
          :seon.test/attempts
          (count (db/q '[:find [?attempt ...] :where
                         [?attempt :seon.ai.attempt/id]] database))})))))

(defn prepare-crash
  "Start bounded side-effect/loop/side-effect forms in the owned scratch cluster."
  []
  (let [handle (:seon.turn.loop/cluster (get @runtime/running-instances "loop-proof"))
        connection (:seon.db/connection handle)
        routing (:seon.cluster.agent/routing (get @runtime/running-instances "loop-proof"))]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (assert (true? (:seon.config.ai/no-provider
                       (config/effective @connection "loop-proof"))))
       (db/transact!
        connection
        [{:seon.cluster.agent/id "crash-proof"
          :seon.cluster.agent/namespace {:seon.ns/name 'my.agents.crash-proof}
          :seon.agent/settings
          {:seon.config.ai/no-provider true
           :seon.config.eval/time-limit-ms 120000
           :seon.config.agent/turn-completion-backstop-ms 240000}}])
       (agent/arm! {:seon.turn.loop/cluster handle
                    :seon.cluster.agent/routing routing
                    :seon.cluster.agent/id "crash-proof"})
       (turn/virtual-turn!
        {:seon.turn.loop/cluster handle
         :seon.cluster.agent/routing routing
         :seon.cluster.agent/id "crash-proof"
         :seon.cluster.reply/text
         "(seon.db/transact! [{:seon.cluster.agent/id \"loop-proof-marker\"}])\n(loop [] (recur))\n(seon.db/transact! [{:seon.cluster.agent/id \"loop-proof-after\"}])"})))))

(defn crash-state
  "Observe interruption, closure, and side-effect facts after scratch boot."
  []
  (let [handle (:seon.turn.loop/cluster (get @runtime/running-instances "loop-proof"))
        database @(:seon.db/connection handle)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       {:seon.test/markers
        (db/q '[:find ?id ?t :in $ [?id ...] :where
                [?e :seon.cluster.agent/id ?id ?t]]
              database ["loop-proof-marker" "loop-proof-after"])
        :seon.test/turns
        (db/q '[:find (pull ?turn [:seon.turn/id :seon.turn/closed-at])
                :where [?agent :seon.cluster.agent/id "crash-proof"]
                [?turn :seon.turn/agent ?agent]] database)
        :seon.test/evaluations
        (mapv #(select-keys % [:seon.cluster.eval/id :seon.cluster.eval/ordinal
                               :seon.cluster.eval/interrupted-at :seon.eval/value
                               :seon.cluster.eval/error])
              (evaluation/of-agent database "crash-proof"))}))))
