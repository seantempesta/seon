(do
 (require 'seon.test-support)
 (vec (for [mode ["sci" "jvm"] n [1 10 100]]
   (let [a (System/getProperty "seon.pass-cost.agent")
         response ((#'seon.pass-probe/mcp-pass
                    {"code" (clojure.string/join "\n" (repeat n "(+ 1 1)"))
                     "agent" a "namespace" (str "scratch.s0probe-" a) "cluster" "default" "mode" mode}))
         values (mapv seon.test-support/reply (:seon.pass-probe/shown response))]
     {:mode mode :forms n :complete? (:seon.pass-probe/complete? response)
      :error? (:seon.pass-probe/error? response)
      :values-correct? (= (vec (repeat n 2)) (mapv :seon.repl/value values))
      :diagnostics (mapv :seon.repl/diagnostics (filter #(contains? % :seon.repl/diagnostics) values))}))))
