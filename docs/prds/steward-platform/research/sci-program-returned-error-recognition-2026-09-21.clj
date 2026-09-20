;; Run with clojure -M docs/prds/steward-platform/research/sci-program-returned-error-recognition-2026-09-21.clj
(require 'seon.sci.eval 'seon.sci.kernel 'seon.sci.admit 'seon.program
         '[seon.error :as error]
         '[seon.schema :as schema]
         '[seon.schema.edn :as schema.edn])
(println :loads)
(let [projection (schema/declaration-projection (schema.edn/packaged-forms))
      original {:seon.error/kind :probe/refused
                :seon.error/message [:seon.ns/name 'user]}
      complete-base (-> original
                        (dissoc :seon.error/kind)
                        (assoc :seon.error/at (java.util.Date.)
                               :seon.error/layer :probe/evaluation
                               :seon.error/operation 'probe/evaluate
                               :seon.sci.kernel/guard-observation
                               {:seon.error.evidence/attribute :seon.eval/duration-ms
                                :seon.error.evidence/value 0}))
      valid (assoc complete-base :seon.error/message "Evaluation was refused.")
      original-facets (error/facets projection original)
      malformed-facets (error/facets projection complete-base)
      valid-facets (error/facets projection valid)]
  (assert (:seon.error/kind original))
  (assert (empty? original-facets))
  (assert (empty? malformed-facets))
  (assert (contains? valid-facets :seon.sci.kernel/error))
  (assert (string? (#'seon.sci.eval/failure-text original)))
  (prn {:sci-program/original-selects-evaluation-error true
        :sci-program/original-facets original-facets
        :sci-program/base-members-with-malformed-message-facets malformed-facets
        :sci-program/valid-message-facets valid-facets
        :sci-program/derived-failure-text (#'seon.sci.eval/failure-text original)}))
