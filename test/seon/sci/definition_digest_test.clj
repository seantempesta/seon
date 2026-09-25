(ns seon.sci.definition-digest-test
  "A definition evaluated at the REPL settles with the digest the writer recomputes."
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.test-support :as support]))

(deftest a-repl-definition-is-admitted-with-the-digest-the-writer-recomputes
  ;; The contract makes `definition-rows` replace `:seon.fn/spec` after
  ;; analysis, the change that left the analysed digest stale.
  (support/with-database
   (fn [connection]
     (support/transacted! connection [(support/namespace-row @connection 'sample.repl-digest)])
     (let [ctx (support/fork-cluster-ctx connection)
           evaluation (eval/evaluate
                       {:seon.sci.eval/ctx ctx
                        :seon.schema/projection (schema/handed-projection)
                        :seon.cluster.eval/ns [:seon.ns/name 'sample.repl-digest]
                        :seon.cluster.eval/source
                        "(defn ^{:malli/schema [:=> [:cat :int] :int]} probe [x] (inc x))"
                        :seon.sci.admit/caps (config/result-caps config/defaults)
                        :seon.sci.eval/time-limit-ms 2000
                        :seon.config/on-core-error :panic})
           row (:seon.program/row evaluation)
           database @connection
           projection (db/carried-projection database)]
       (is (= 'sample.repl-digest/probe (:seon.fn/sym row)) (pr-str evaluation))
       (is (= (db/definition-digest-of projection row
                                       (db/pull database '[*] [:seon.ns/name 'sample.repl-digest]))
              (:seon.program/definition-digest row))
           "the final row carries the owner's digest")
       (let [report (db/transact! connection [(program/canonical-row (program/shapes-in projection) row)])]
         (is (:db-after report) (pr-str report)))))))
