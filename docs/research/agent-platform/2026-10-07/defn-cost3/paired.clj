(do (require 'seon.pass-probe) (mapv (fn [_] (seon.pass-probe/run-mcp "default" "sci" 1 #{1} true)) (range 3)))
