(ns result-binding-isolation-2026-09-06
  (:require [sci.core :as sci]
            [seon.operator.runtime :as runtime]
            [seon.sci.eval :as evaluation]))

; MCP JVM load-file proof: same result symbol, two separate SCI forks.
; Observed 2026-09-06: left=:left, right=:right, base binding=false,
; host namespace unchanged=true. No database writes or model requests.
(let [base (:seon.sci.eval/ctx
            (get @runtime/running-instances "lab-run-inspection"))
      left (sci/fork base)
      right (sci/fork base)
      host-before (find-ns 'result)]
  (evaluation/bind-result! left 314159 :left)
  (evaluation/bind-result! right 314159 :right)
  {:left (sci/eval-string* left "result/e314159")
   :right (sci/eval-string* right "result/e314159")
   :base-has-binding? (boolean (sci/resolve base 'result/e314159))
   :host-namespace-unchanged? (identical? host-before (find-ns 'result))})
