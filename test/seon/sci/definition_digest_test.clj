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
  ;; The contract makes `definition-entities` replace `:seon.fn/spec` after
  ;; analysis, the change that left the analysed digest stale.
  (support/with-database
   (fn [connection]
     (support/transacted! connection [(support/namespace-entity @connection 'sample.repl-digest)])
     (let [ctx (support/fork-cluster-ctx connection)
           evaluation (eval/evaluate
                       {:seon.sci.eval/ctx ctx
                        :seon.schema/projection (schema/handed-projection)
                        :seon.cluster.eval/ns [:seon.ns/name 'sample.repl-digest]
                        :seon.cluster.eval/source
                        "(defn ^{:malli/schema [:=> [:cat :int] :int]} probe [x] (inc x))"
                        :seon.sci.admit/caps (config/result-caps config/defaults)
                        :seon.sci.eval/time-limit-ms 2000})
           entity (:seon.program/row evaluation)
           database @connection
           projection (db/carried-projection database)]
       (is (= 'sample.repl-digest/probe (:seon.fn/sym entity)) (pr-str evaluation))
       (is (= (db/definition-digest-of projection entity
                                       (db/pull database '[*] [:seon.ns/name 'sample.repl-digest]))
              (:seon.program/definition-digest entity))
           "the final entity carries the owner's digest")
       (let [report (db/transact! connection [(program/canonical-row (program/shapes-in projection) entity)])]
         (is (:db-after report) (pr-str report)))))))

(deftest a-definition-digests-with-the-namespace-entity-its-settlement-stores
  ;; A `require` in form N writes the namespace's context entity in the same
  ;; settlement as a definition in form N+1; the stored entity predates both.
  (support/with-database
   (fn [connection]
     (support/transacted! connection [(support/namespace-entity @connection 'sample.repl-settled)])
     (let [ctx (support/fork-cluster-ctx connection)
           request {:seon.sci.eval/ctx ctx
                    :seon.schema/projection (schema/handed-projection)
                    :seon.cluster.eval/ns [:seon.ns/name 'sample.repl-settled]
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 2000}
           context-entity (:seon.program/row
                        (eval/evaluate (assoc request :seon.cluster.eval/source
                                              "(require '[clojure.string :as string])")))
           entity (:seon.program/row
                (eval/evaluate (assoc request
                                      :seon.cluster.eval/source
                                      "(defn ^{:malli/schema [:=> [:cat :string] :string]} shout [x] (string/upper-case x))"
                                      :seon.sci.eval/settled-namespaces {'sample.repl-settled context-entity})))
           projection (db/carried-projection @connection)
           shapes (program/shapes-in projection)]
       (is (= 'sample.repl-settled (:seon.ns/name context-entity)) (pr-str context-entity))
       (is (= (db/definition-digest-of projection entity context-entity)
              (:seon.program/definition-digest entity)))
       (let [report (db/transact! connection [(program/canonical-row shapes context-entity)
                                              (program/canonical-row shapes entity)])]
         (is (:db-after report) (pr-str report)))))))
