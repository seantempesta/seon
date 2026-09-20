;; Run with clojure -M docs/prds/steward-platform/research/sci-program-shared-program-facets-2026-09-21.clj
(require 'seon.sci.eval 'seon.sci.kernel 'seon.sci.admit
         '[seon.program :as program]
         '[my.program :as my.program]
         '[seon.env :as env]
         '[seon.error :as error]
         '[seon.schema :as schema]
         '[seon.schema.edn :as schema.edn])
(println :loads)
(let [projection (schema/declaration-projection (schema.edn/packaged-forms))
      read-failure (#'my.program/read-result
                    {:seon.program/subject 'sample/f}
                    'my.program/callers
                    #(throw (ex-info "The supplied read failed." {})))
      environment (env/environment {:seon.boot/cluster-name "sci-program-facet-probe"})
      context-failure (#'my.program/supplied-context environment)
      declaration-failure
      (try
        (#'program/declaration-refused!
         "A declaration has no source." [[:seon.fn/sym 'sample/f]]
         {:seon.program/missing-attributes [:seon.fn/source]})
        (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
      observed
      {:sci-program/read-facets (error/facets projection read-failure)
       :sci-program/context-facets (error/facets projection context-failure)
       :sci-program/declaration-facets (error/facets projection declaration-failure)
       :sci-program/read-member
       (get-in read-failure [:seon.error/data :seon.error/diagnostic-member])
       :sci-program/context-member
       (get-in context-failure [:seon.error/data :seon.error/diagnostic-member])
       :sci-program/declaration-members (:seon.program/identity-attributes declaration-failure)}]
  (assert ((schema/projection-validator projection :seon.env/environment) environment))
  (assert (empty? (:sci-program/read-facets observed)))
  (assert (empty? (:sci-program/context-facets observed)))
  (assert (contains? (:sci-program/declaration-facets observed)
                     :seon.program/declaration-refused-error))
  (prn observed))
