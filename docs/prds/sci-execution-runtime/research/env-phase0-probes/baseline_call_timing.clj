(ns baseline-call-timing
  "Companion timing for the runtime-ctx hook probe: the cost of one direct
  host-Var call from evaluated code. Run this on the UNEDITED sci pin and on
  branch `seon-env-hook-probe` to price the hook's wrapping node.

  Run:
    clojure -M:dev -e \"(load-file \\\"tmp/env-probes/baseline_call_timing.clj\\\") (prn (baseline-call-timing/run))\""
  (:require [sci.core :as sci]))

(defn- plain-fn [x] (inc x))

(defn run
  "Return ns/call for a direct host-Var call with no hook installed."
  []
  (let [ctx (sci/init {:namespaces {'my {'plain (sci/new-var 'plain plain-fn
                                                             {:ns (sci/create-ns 'my)})}}})
        prog (sci/eval-string* ctx "(fn [] (my/plain 1))")
        _ (dotimes [_ 200000] (prog))
        n 2000000
        t0 (System/nanoTime)
        _ (dotimes [_ n] (prog))]
    {:ns-per-call (Math/round (/ (double (- (System/nanoTime) t0)) n))}))
