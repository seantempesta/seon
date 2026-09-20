;; Run with clojure -M docs/prds/steward-platform/research/error-result-blob-probe-2026-09-23.clj
;; Pure encoding probe; opens no database or cluster.
(require 'seon.error 'seon.sci.eval 'seon.print 'seon.blob)
(doseq [[label value] [[:map {:probe/value [1 2 3]}]
                       [:atom (atom 1)]
                       [:function identity]
                       [:object (Object.)]]]
  (println label (boolean (seon.blob/store-faithful-edn value))))
