(ns seon.rule.seed
  "Bootstrap EDN fallback for canonical minimal native `:seon.rule/*` rules when an ephemeral
   test store has no stored `:seon.rule/id` entities yet."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.rule :as rules]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge rules-file-cache (atom nil))

(defn ^:seon.flow/edge starter-rules
  "Loads canonical `:seon.rule/*` starter rules (preferring branch `*base-rules-override*`,
   then mtime-cached `resources/seon/rules.edn` on disk, then classpath resource)."
  {:malli/schema [:=> [:cat] [:vector :map]] :cost/ticks 5000000}
  []
  (or (when (seq rules/*base-rules-override*) (vec rules/*base-rules-override*))
      (let [f (io/file store/checkout-root "resources/seon/rules.edn")]
        (if (.isFile f)
          (let [mt (.lastModified f)
                cached @rules-file-cache]
            (if (and cached (= mt (:mtime cached)))
              (:rules cached)
              (let [v (edn/read-string (slurp f))
                    rs (if (vector? v) v [])]
                (reset! rules-file-cache {:mtime mt :rules rs})
                rs)))
          (if-let [res (io/resource "seon/rules.edn")]
            (let [v (edn/read-string (slurp res))]
              (if (vector? v) v []))
            [])))))
