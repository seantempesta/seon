(ns acme.execution
  "ACME's production entry for each isolated Bun execution child."
  (:require [acme.brand]
            [acme.context]
            [acme.helpers]
            [acme.notes]
            [acme.overrides]
            [acme.widget]
            [seon.execution.runtime :as runtime]))

(defn -main
  "Start an ACME execution child with its compiled functions loaded."
  {:malli/schema [:=> [:cat [:* :any]] :any]}
  [& args]
  (apply runtime/-main args))
