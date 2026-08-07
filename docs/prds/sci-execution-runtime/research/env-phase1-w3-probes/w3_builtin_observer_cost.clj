(ns w3-builtin-observer-cost
  "Price the built-in-call-observer's move from the analysis ctx to the runtime
  ctx (W3). On the pin the wrapping node exists only when an observer is
  present at analysis time; on branch seon-env-hook it always exists for a
  built-in callee and reads the observer from the executing ctx.

  Run against BOTH the pin and the branch, same machine, same session:
    clojure -Sdeps '{:deps {org.babashka/sci {:local/root \"tmp/w3-sci-pin\"}}}' \\
      -M:dev -e \"(load-file \\\"tmp/env-probes/w3_builtin_observer_cost.clj\\\") (prn (w3-builtin-observer-cost/run))\""
  (:require [sci.core :as sci]))

(defn- bench [prog n]
  (dotimes [_ 200000] (prog))
  (let [t0 (System/nanoTime)]
    (dotimes [_ n] (prog))
    (Math/round (/ (double (- (System/nanoTime) t0)) n))))

(defn run
  "ns/call for a built-in call, with and without an observer installed."
  []
  (let [n 2000000
        no-observer (sci/init {})
        with-observer (sci/init {:built-in-call-observer (fn [_])})]
    {:built-in-call-no-observer
     (bench (sci/eval-string* no-observer "(fn [] (gensym \"x\"))") n)
     :built-in-call-with-observer
     (bench (sci/eval-string* with-observer "(fn [] (gensym \"x\"))") n)
     :specialized-call-no-observer
     (bench (sci/eval-string* no-observer "(fn [] (+ 1 2))") n)
     :specialized-call-with-observer
     (bench (sci/eval-string* with-observer "(fn [] (+ 1 2))") n)}))
