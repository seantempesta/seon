(ns seon.sci.branch-execution-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.sci.eval-test]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- evaluate!
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/connection :symbol :string] :seon.sci.eval/evaluation]}
  [ctx connection namespace-name source]
  (let [result (eval/evaluate
                {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                 :seon.db/connection connection
                 :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                 :seon.cluster.eval/source source
                 :seon.sci.eval/time-limit-ms 5000
                 :seon.sci.admit/caps (config/result-caps config/defaults)
                 :seon.config/on-core-error :panic})]
    (when (:seon.cluster.eval/error result)
      (println "REALITIES-EVAL-REFUSAL" (pr-str (select-keys result [:seon.cluster.eval/error :seon.cluster.eval/triage-edn])))
      (throw (ex-info "Agent evaluation refused." result)))
    result))

(defn- declare!
  "The evaluator's analyzed row goes through the turn's declaration writer."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/connection :symbol :string] :seon.program/row]}
  [ctx connection namespace-name source]
  (let [result (evaluate! ctx connection namespace-name source)
        row (:seon.program/row result)]
    (when-not row (throw (ex-info "No declaration was produced." result)))
    (support/transacted! connection
      (#'turn/row-tx (db/db connection) {} row))
    row))

(defn- on-branch
  "Use the canonical fixture's store and one captured commit."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.db/database-value :keyword [:fn ifn?]] :nil]}
  [connection captured branch body]
  (d/branch! connection (d/commit-id captured) branch)
  (try
    (let [child (d/connect (assoc (:config captured) :branch branch))]
      (try
        (db/carry-connection-projection-state!
          child (eval/projection-state @child (db/carried-projection captured)))
        (db/call-with-custody {} #(body child))
        (finally (d/release child))))
    (finally (d/delete-branch! connection branch))))

(defn- measured
  {:malli/schema [:=> [:cat [:fn ifn?]] :map]}
  [f]
  (let [start (System/nanoTime) value (f)]
    {:value value :ms (/ (- (System/nanoTime) start) 1e6)}))

(deftest ^{:seon.test/long "Measured 14,651 ms: canonical fixture, three complete SCI acquisitions, two analyzed declarations and branch cleanup; individual acquisition 1,681 ms."
           :seon.test/long-ms 20000}
  branch-definitions-and-unchanged-callers-share-one-context
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [_ (support/seed-cluster! connection "realities-proof")
           captured (db/db connection)
           live (support/fork-cluster-ctx connection)
           _ (eval/acquire! {:seon.sci.eval/ctx live :seon.db/db captured})
           leaf 'seon.sci.eval-test/cut?
           caller 'seon.sci.eval-test/ok?
           leaf-row (db/pull captured '[*] [:seon.fn/sym leaf])
           caller-before (db/pull captured '[*] [:seon.fn/sym caller])
           live-env @(:env live)
           direct "(seon.sci.eval-test/cut? {})"
           indirect "(not (seon.sci.eval-test/ok? {}))"
           edited "(defn ^{:malli/schema [:=> [:cat :map] :boolean]} cut? [evaluation] true)"]
       (is (= :core (:seon.schema.admission/source leaf-row)))
       (is (= :core (:seon.schema.admission/source caller-before)))
       (is (some? (:seon.program/definition-digest leaf-row)))
       (is (identical? @(find-var caller) @(sci/resolve live caller))
           "the unchanged caller begins as the actual compiled JVM root")
       (on-branch connection captured :realities-proof-a
        (fn [a]
          (on-branch connection captured :realities-proof-b
           (fn [b]
             (let [a-ctx (eval/fork-cluster-ctx live (db/db a) a)
                   b-ctx (eval/fork-cluster-ctx live (db/db b) b)
                   _ (declare! a-ctx a 'seon.sci.eval-test edited)
                   acquisition (measured #(eval/acquire!
                                           {:seon.sci.eval/ctx a-ctx :seon.db/db (db/db a)}))
                   _ (eval/acquire! {:seon.sci.eval/ctx b-ctx :seon.db/db (db/db b)})
                   a-direct (measured #(:seon.sci.admit/value
                                        (evaluate! a-ctx a 'user direct)))
                   a-indirect (measured #(:seon.sci.admit/value
                                          (evaluate! a-ctx a 'user indirect)))
                   b-calls (measured #(:seon.sci.admit/value
                                      (evaluate! b-ctx b 'user (str "[" direct " " indirect "]"))))
                   closure (functions/reverse-closure
                             {:seon.db/db (db/db a) :seon.fn/seeds #{leaf}})]
               (is (= caller-before (db/pull (db/db a) '[*] [:seon.fn/sym caller])))
               (is (true? (:value a-direct)))
               (is (true? (:value a-indirect)))
               (is (= [false false] (:value b-calls)))
               (is (identical? live-env @(:env live)))
               (is (false? (sci/eval-string* live indirect)))
               (is (= (:seon.fn/tests closure)
                      (functions/gate-sets {:seon.db/db (db/db a) :seon.fn/seeds #{leaf}})))
               (is (contains? (:seon.fn/affected closure) caller))
               (is (= (count (:seon.fn/affected closure))
                      (:seon.sci.eval/interpreted-count (:value acquisition))))
               (is (pos? (:seon.sci.eval/interpreted-count (:value acquisition))))
               (is (empty? (:seon.test/acquisition-refusals (eval/acquired-program a-ctx))))
               (println "REALITIES-PROOF"
                 (pr-str {:acquisition-ms (:ms acquisition)
                          :a-direct a-direct :a-indirect a-indirect :b-calls b-calls
                          :interpreted-count (:seon.sci.eval/interpreted-count (:value acquisition))
                          :function-count (count (db/q '[:find [?s ...] :where [_ :seon.fn/sym ?s]] captured))}))
               (let [host-row (declare! a-ctx a 'seon.env
                                "(defn ^{:malli/schema [:=> [:cat :map] :map]} ->Environment [members] members)")
                     refused (eval/install-row! {:seon.sci.eval/ctx a-ctx
                                                 :seon.db/db (db/db a)
                                                 :seon.program/row host-row})]
                 (is (= 'seon.env/->Environment (:seon.sci.eval/refused-function refused)))
                 (is ((schema/projection-validator
                        (db/carried-projection (db/db a))
                        :seon.sci.eval/interpretation-error) refused))
                 (is (nil? (sci/resolve a-ctx 'seon.env/->Environment)))
                 (is (some? (sci/resolve b-ctx 'seon.env/->Environment)))
                 (println "REALITIES-HOST-REFUSAL" (pr-str refused))))))))))))))
