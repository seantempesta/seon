(do
  (require 'seon.pass-probe 'seon.mcp 'clojure.data.json 'clojure.string 'datahike.query)
  (let [agent-id (System/getProperty "seon.pass-cost.agent" "pass-cost-probe")
        call (fn [mode n]
               (seon.mcp/call
                (clojure.data.json/write-str
                 {"name" "eval_clj"
                  "arguments" {"code" (clojure.string/join "\n" (repeat n "(+ 1 1)"))
                               "agent" agent-id "cluster" "default" "mode" mode
                               "namespace" (str "scratch.s0probe-" agent-id)}})))
        _ (call "sci" 1)
        world (assoc (#'seon.pass-probe/agent-world "default" agent-id)
                     :seon.pass-probe/stages '#{seon.turn/terminal-agent-ordinal seon.config/effective
                                               seon.sci.kernel/public-functions-in seon.db/q})]
    (when (Boolean/getBoolean "seon.pass-cost.clear-query-cache")
      (datahike.query/clear-query-cache!))
    (vec
     (for [mode ["sci" "jvm"] n [1 10 100]]
       (do
         (call mode n)
         {:mode mode :n n
          :rows (mapv (fn [_]
                        (let [connection (:seon.db/connection world)
                              before (seon.db/commit-id @connection)
                              measured (#'seon.pass-probe/mcp!
                                        "default" world mode
                                        (clojure.string/join "\n" (repeat n "(+ 1 1)")) {})]
                          (assoc measured :commit-changed? (not= before (seon.db/commit-id @connection)))))
                      (range 3))})))))
