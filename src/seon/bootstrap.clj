(ns seon.bootstrap
  "The system-authored bootstrap run shared by every new agent.")

(defn run-id
  "The deterministic id of `agent-id`'s system-authored bootstrap run."
  {:malli/schema [:=> [:cat :seon.cluster.agent/id]
                  :seon.cluster.run/id]}
  [agent-id]
  (str "bootstrap:" agent-id))
