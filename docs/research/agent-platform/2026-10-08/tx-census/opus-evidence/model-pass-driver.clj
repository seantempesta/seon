(do
  (require 'seon.pass-probe 'seon.test-support)
  (let [P (fn [s] @(ns-resolve 'seon.pass-probe s))
        plan [[:ordinary 0] [:ordinary 1] [:defn 1] [:after-defn 1]]
        agent-id (str "census-model-" (seon.id/id))
        ledger (atom []) timings (atom [])]
    (seon.test-support/with-provider
     ledger (seon.test-support/reply-queue (mapv (fn [[k i]] ((P 'fenced) ((P 'texts) k i))) plan)) timings
     (fn [server]
       (tx-census.probe/start!)
       (((P 'mcp-pass) {"agent" agent-id "cluster" "default"
                        "code" (str "(my.agent/settings! " (seon.id/printed (merge (seon.test-support/provider-config server)
                                                                                 {:seon.config.ai/no-provider false
                                                                                  :seon.config.run/max-episode-runs 1})) ")")}))
       (let [setup (tx-census.probe/stop!)
             world (assoc ((P 'agent-world) "default" agent-id) :seon.pass-probe/ledger ledger)]
         (try
           {:setup setup
            :passes (mapv (fn [[kind _]]
                            (tx-census.probe/start!)
                            (let [r (((P 'model-pass) world))]
                              {:kind kind :complete? (:seon.pass-probe/complete? r) :turn (:seon.pass-probe/turn r)
                               :census (tx-census.probe/stop!)}))
                          plan)}
           (finally (some-> (:seon.pass-probe/host ((P 'agent-world) "default" agent-id)) seon.cluster.host/retire!))))))))
