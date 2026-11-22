(ns seon.rule.seed
  "Bootstrap EDN fallback for starter `:seon.rule/*` rules when an ephemeral test store runs without `resources/seon/rules.edn` on disk."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn ^:seon.flow/edge starter-rules
"Loads the canonical `:seon.rule/*` starter rules vector from `resources/seon/rules.edn` via classpath resource."
{:malli/schema [:=> [:cat] [:vector :map]] :cost/ticks 5000000}
[]
(if-let [res (io/resource "seon/rules.edn")]
  (let [v (edn/read-string (slurp res))]
    (if (vector? v) v []))
  []))