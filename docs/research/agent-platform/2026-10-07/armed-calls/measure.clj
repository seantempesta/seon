;; Run through MCP JVM mode as armed-calls-measure, cluster default.
;; The installed probe creates its own agents, uses platform-thread allocation
;; readings, and retires each hosted agent in finally. It changes no host Vars.
(require 'seon.pass-probe)
(seon.pass-probe/run-mcp "default" "sci" 1 #{1} true)

;; Through MCP SCI as armed-calls, cluster default, on its own branch:
(in-ns 'armed-calls.scratch)
(defn pf {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))
(pf 41)
(mapv pf (range 10))
(reduce + (map pf (range 100)))
