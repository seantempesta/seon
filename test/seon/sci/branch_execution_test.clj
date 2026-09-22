(ns seon.sci.branch-execution-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.id :as id]
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

(defn- declared!
  "Transact one analyzed declaration through the turn's declaration writer."
  {:malli/schema [:=> [:cat :seon.db/connection :qualified-symbol :string] :seon.program/row]}
  [connection function-symbol source]
  (let [row (support/program-fn-row (db/db connection) function-symbol source)]
    (support/transacted! connection (#'turn/row-tx (db/db connection) {} row))
    row))

(deftest ^{:seon.test/long "Two complete SCI acquisitions of the canonical fixture program (measured 985 ms for one derivation in this run's memo test) plus one analyzed declaration."
           :seon.test/long-ms 8000}
  a-data-only-commit-reuses-the-acquired-program-and-a-program-row-reacquires
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [live (support/fork-cluster-ctx connection)
             before (db/db connection)
             acquisition (eval/acquire! {:seon.sci.eval/ctx live :seon.db/db before})
             environment @(:env live)
             digest (id/sha-256 [(.getBytes "program-revision-probe" "UTF-8")])
             _ (support/transacted! connection
                 [{:seon.dev.mcp.artifact/id digest
                   :seon.dev.mcp.artifact/digest digest}])
             data (db/db connection)
             reused (measured #(eval/acquire! {:seon.sci.eval/ctx live :seon.db/db data}))]
         (is (not= (db/commit-id before) (db/commit-id data))
             "the artifact row is a real commit")
         (is (identical? acquisition (:value reused))
             "a commit writing no program row returns the acquired program")
         (is (identical? environment @(:env live))
             "and installs nothing into the context")
         (is (identical? data (:seon.db/db (eval/acquired-program live)))
             "the acquired value advances to the program-equal commit")
         (let [_ (declared! connection 'seon.sci.eval-test/program-revision-probe
                   "(defn ^{:malli/schema [:=> [:cat] :int]} program-revision-probe [] 1)")
               changed (db/db connection)]
           (is (not (eval/acquired-database? live changed))
               "a program row changes the acquired program's identity")
           (let [reacquired (measured #(eval/acquire! {:seon.sci.eval/ctx live :seon.db/db changed}))]
             (is (not (identical? acquisition (:value reacquired))))
             (is (not (identical? environment @(:env live))))
             (is (identical? changed (:seon.db/db (eval/acquired-program live))))
             (is (= 1 (sci/eval-string* live "(seon.sci.eval-test/program-revision-probe)")))
             (println "PROGRAM-REVISION-PROOF"
                      (pr-str {:reused-acquire-ms (:ms reused)
                               :program-reacquire-ms (:ms reacquired)}))))))))))

(deftest ^{:seon.test/long "One complete base-context derivation of the canonical fixture program (measured 452-1,192 ms on default) and one memoized copy."
           :seon.test/long-ms 8000}
  a-base-context-is-memoized-by-its-program-and-each-caller-gets-its-own-fork
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           first-call (measured #(eval/base-ctx database))
           second-call (measured #(eval/base-ctx database))
           a (:value first-call)
           b (:value second-call)]
       (is (identical? (:seon.sci.eval/acquisition a) (:seon.sci.eval/acquisition b))
           "the second call reads the derivation the first made")
       (is (not (identical? (:env a) (:env b))))
       (is (not (identical? (:seon.sci.kernel/program-snapshot a)
                            (:seon.sci.kernel/program-snapshot b))))
       (sci/eval-string* a "(def base-memo-probe 1)")
       (is (some? (sci/resolve a 'user/base-memo-probe)))
       (is (nil? (sci/resolve b 'user/base-memo-probe))
           "a definition in one caller's context is invisible to another")
       (println "BASE-CTX-MEMO-PROOF"
                (pr-str {:derive-ms (:ms first-call) :memoized-ms (:ms second-call)}))))))

(deftest ^{:seon.test/long "One complete SCI acquisition of the canonical fixture program plus one analyzed declaration."
           :seon.test/long-ms 8000}
  an-override-of-a-declaration-the-indexer-marks-host-bound-refuses-by-name
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [live (support/fork-cluster-ctx connection)
             _ (eval/acquire! {:seon.sci.eval/ctx live :seon.db/db (db/db connection)})
             subject 'seon.render.hiccup/escape
             indexed (db/pull (db/db connection) '[*] [:seon.fn/sym subject])]
         (is (true? (:seon.fn/host-bound? indexed))
             "the indexer marks the declaration's own class usage")
         (is (not (#{'clojure.core/reify 'clojure.core/proxy 'clojure.core/definterface
                     'clojure.core/deftype 'clojure.core/defrecord}
                   (:seon.fn/defined-by indexed)))
             "the form head alone would not have refused it")
         (let [row (declared! connection subject
                     "(defn ^{:malli/schema [:=> [:cat :string] :string]} escape [text] text)")
               refused (eval/install-row! {:seon.sci.eval/ctx live
                                           :seon.db/db (db/db connection)
                                           :seon.program/row row})]
           (is (= subject (:seon.sci.eval/refused-function refused)))
           (is ((schema/projection-validator
                 (db/carried-projection (db/db connection))
                 :seon.sci.eval/interpretation-error) refused)))))))))

(deftest ^{:seon.test/long "One complete SCI acquisition of the canonical fixture program shared by two concurrent acquirers (measured 985 ms per derivation) plus one analyzed declaration."
           :seon.test/long-ms 8000}
  two-concurrent-acquisitions-of-one-program-derive-it-once
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [a (support/fork-cluster-ctx connection)
             b (support/fork-cluster-ctx connection)
             _ (declared! connection 'seon.sci.eval-test/concurrent-acquisition-probe
                 "(defn ^{:malli/schema [:=> [:cat] :int]} concurrent-acquisition-probe [] 2)")
             changed (db/db connection)
             start (promise)
             acquiring (fn [ctx]
                         (future @start
                                 (eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db changed})))
             in-a (acquiring a)
             in-b (acquiring b)
             _ (deliver start true)
             from-a (deref in-a 6000 ::unfinished)
             from-b (deref in-b 6000 ::unfinished)]
         (is (not= ::unfinished from-a) "the first acquirer finished within its bound")
         (is (not= ::unfinished from-b) "the second acquirer finished within its bound")
         (is (identical? from-a from-b)
             "both acquirers read the one derivation of the program")
         (is (not (identical? (:env a) (:env b))) "each context keeps its own environment")
         (is (= 2 (sci/eval-string* a "(seon.sci.eval-test/concurrent-acquisition-probe)")))
         (is (= 2 (sci/eval-string* b "(seon.sci.eval-test/concurrent-acquisition-probe)")))))))))
