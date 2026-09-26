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
                        :seon.sci.eval/time-limit-ms 2000})
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

(deftest a-definition-digests-with-the-namespace-row-its-settlement-stores
  ;; A `require` in form N writes the namespace's context row in the same
  ;; settlement as a definition in form N+1; the stored row predates both.
  (support/with-database
   (fn [connection]
     (support/transacted! connection [(support/namespace-row @connection 'sample.repl-settled)])
     (let [ctx (support/fork-cluster-ctx connection)
           request {:seon.sci.eval/ctx ctx
                    :seon.schema/projection (schema/handed-projection)
                    :seon.cluster.eval/ns [:seon.ns/name 'sample.repl-settled]
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 2000}
           context-row (:seon.program/row
                        (eval/evaluate (assoc request :seon.cluster.eval/source
                                              "(require '[clojure.string :as string])")))
           row (:seon.program/row
                (eval/evaluate (assoc request
                                      :seon.cluster.eval/source
                                      "(defn ^{:malli/schema [:=> [:cat :string] :string]} shout [x] (string/upper-case x))"
                                      :seon.sci.eval/settled-namespaces {'sample.repl-settled context-row})))
           projection (db/carried-projection @connection)
           shapes (program/shapes-in projection)]
       (is (= 'sample.repl-settled (:seon.ns/name context-row)) (pr-str context-row))
       (is (= (db/definition-digest-of projection row context-row)
              (:seon.program/definition-digest row)))
       (let [report (db/transact! connection [(program/canonical-row shapes context-row)
                                              (program/canonical-row shapes row)])]
         (is (:db-after report) (pr-str report)))))))
