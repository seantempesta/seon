(ns seon.rule.seed
  "Bootstrap EDN fallback for starter `:seon.rule/*` rules when an ephemeral test store runs without `resources/seon/rules.edn` on disk."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.rule :as rules]))

(defn ^:seon.flow/edge starter-rules
  "Loads canonical `:seon.rule/*` starter rules (preferring branch `*base-rules-override*`, then `resources/seon/rules.edn`)."
  {:malli/schema [:=> [:cat] [:vector :map]] :cost/ticks 5000000}
  []
  (or (when (seq rules/*base-rules-override*) (vec rules/*base-rules-override*))
      (if-let [res (io/resource "seon/rules.edn")]
        (let [v (edn/read-string (slurp res))]
          (if (vector? v) v []))
        [])))