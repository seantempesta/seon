(ns seon.fn-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.turn :as turn]
            [seon.fn :as seon.fn]
            [seon.fn.analyzer :as analyzer]
            [seon.id :as id]
            [seon.program :as program]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]))

(def ^:private boot-process
  [:seon.db.process/id "seon.db.process/boot"])

(defn- fixture-root []
  (let [root (io/file "tmp" "fn-test" (str (random-uuid)))]
    (.mkdirs root)
    root))

(defn- write-source! [root relative-path source]
  (let [file (io/file root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file source)
    file))

(defn- transact-fixture! [connection rows]
  (let [result (db/transact! connection rows)]
    (is (nil? (:seon.error/kind result))
        (pr-str (select-keys result [:seon.error/kind :seon.error/message])))
    result))

(deftest analyze-form-refuses-an-unresolvable-namespace-reference
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.missing]
            result
            (seon.fn/analyze-form
             @connection
             "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)"
             namespace-ref
             {:seon.fn/sym "sample.missing/f"
              :seon.fn/ns namespace-ref
              :seon.fn/source
              "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)"
              :seon.fn/arglists "([x])"
              :seon.fn/private? false
              :seon.fn/spec "[:=> [:cat :int] :int]"
              :seon.schema.admission/source :agent})]
        (is (= :seon.fn/namespace-unresolvable
               (:seon.error/kind result)))
        (is (= namespace-ref
               (get-in result
                       [:seon.error/data
                        :seon.error/diagnostic-offending])))))))

(deftest defining-forms-share-one-form-local-kondo-batch
  (test-support/with-database
    (fn [connection]
      (db/transact! connection
                    {:tx-data [{:seon.ns/name 'sample.runtime-batch}]})
      (let [namespace-ref [:seon.ns/name 'sample.runtime-batch]
            shadow-source
            "(defn shadowed [x] (identity (let [map identity] (map x))))"
            qualified-source
            "(defn qualified [] (seon.fn/tests-reaching nil \"x\"))"
            requests
            [{:seon.cluster.eval/source shadow-source
              :seon.cluster.eval/ns namespace-ref
              :seon.program/row
              {:seon.fn/sym "sample.runtime-batch/shadowed"
               :seon.fn/ns namespace-ref
               :seon.fn/source shadow-source
               :seon.fn/arglists "([x])"
               :seon.fn/private? false
               :seon.schema.admission/source :agent}}
             {:seon.cluster.eval/source qualified-source
              :seon.cluster.eval/ns namespace-ref
              :seon.program/row
              {:seon.fn/sym "sample.runtime-batch/qualified"
               :seon.fn/ns namespace-ref
               :seon.fn/source qualified-source
               :seon.fn/arglists "([])"
               :seon.fn/private? false
               :seon.schema.admission/source :agent}}]
            analyze analyzer/analyze
            calls (atom 0)
            results
            (with-redefs [analyzer/analyze
                          (fn [request]
                            (swap! calls inc)
                            (analyze request))]
              (seon.fn/analyze-forms @connection requests))
            shadow-row (second (first results))
            qualified-row (second (second results))
            called-symbols
            (fn [row]
              (into #{} (map second) (:seon.fn/calls row)))]
        (is (= 1 @calls) "all defining sources enter kondo together")
        (is (contains? (called-symbols shadow-row) "clojure.core/identity"))
        (is (not (contains? (called-symbols shadow-row) "clojure.core/map"))
            "a let-bound map is a local, never a clojure.core/map call")
        (is (= #{"seon.fn/tests-reaching"}
               (called-symbols qualified-row))
            "a qualified namespace absent from the stored ns row is synthesized")
        (is (not (contains? (called-symbols shadow-row)
                            "seon.fn/tests-reaching"))
            "analysis facts stay inside their defining source span")))))

(deftest ordinary-form-analysis-keeps-call-edges-without-a-declaration
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.evaluation-edges]
            function-symbol "sample.evaluation-edges/observed"
            test-symbol "sample.evaluation-edges/observed-test"
            source "(do (seon.db/q '[:find ?e :where [?e :seon.agent/id]]) (my.turn/wait))"
            definition (str "(defn observed [] " source ")")
            test-source "(clojure.test/deftest observed-test (observed))"
            function-row {:seon.fn/sym function-symbol
                          :seon.fn/ns namespace-ref
                          :seon.fn/source definition
                          :seon.fn/arglists "([])"
                          :seon.fn/private? false
                          :seon.schema.admission/source :agent}
            test-row {:seon.test/sym test-symbol
                      :seon.test/ns namespace-ref
                      :seon.test/source test-source
                      :seon.schema.admission/source :agent}]
        (transact-fixture! connection [{:seon.ns/name (second namespace-ref)}])
        (let [database (db/db connection)
              results
              (seon.fn/analyze-forms
               database
               [{:seon.cluster.eval/source definition
                 :seon.cluster.eval/ns namespace-ref
                 :seon.program/row function-row}
                {:seon.cluster.eval/source test-source
                 :seon.cluster.eval/ns namespace-ref
                 :seon.program/row test-row}
                {:seon.cluster.eval/source source
                 :seon.cluster.eval/ns namespace-ref}
                {:seon.cluster.eval/source
                 "(let [map identity] (map :sample/value))"
                 :seon.cluster.eval/ns namespace-ref}])
              [definition-facts analyzed-function] (nth results 0)
              [_ analyzed-test] (nth results 1)
              [facts row] (nth results 2)
              calls (into #{} (map second) (:seon.fn/calls facts))]
          (is (contains? calls "seon.db/q"))
          (is (contains? calls "my.turn/wait"))
          (is (nil? row) "an ordinary evaluation does not invent a declaration")
          (is (empty? definition-facts) "declaration edges have one owner")
          (is (= [facts nil]
                 (seon.fn/analyze-form database source namespace-ref nil)))
          (is (not (contains? (into #{} (map second)
                                   (:seon.fn/calls (first (nth results 3))))
                              "clojure.core/map"))
              "local calls do not acquire a program edge")
          (is (not (contains? calls function-symbol))
              "a neighboring test's call stays in its source span")
          (transact-fixture! connection [analyzed-function])
          (transact-fixture! connection [analyzed-test])
          (is (= [test-symbol]
                 (seon.fn/tests-reaching (db/db connection) function-symbol)))
          (is (contains? (set (seon.fn/tests-reaching (db/db connection) "my.turn/wait"))
                         test-symbol)
              "the same analyzed rows supply transitive reachability"))))))

(deftest progress-observation-cannot-change-index-transaction-shapes
  (let [commit-phase! (deref (ns-resolve 'seon.fn 'commit-index-phase!))
        transactions-with
        (fn [progress!]
          (let [transactions (atom [])]
            (with-redefs [db/transact!
                          (fn [_ request]
                            (swap! transactions conj request)
                            {})]
              (commit-phase! ::connection boot-process progress!
                             :seon.fn/declarations
                             [{:seon.fn/sym "sample/one"}
                              {:seon.fn/sym "sample/two"}
                              {:seon.fn/sym "sample/three"}]))
            @transactions))
        silent (transactions-with nil)
        observed-lines (atom [])
        observed (transactions-with #(swap! observed-lines conj %))]
    (is (= silent observed)
        "a progress callback cannot split or otherwise change writer work")
    (is (= 1 (count observed)))
    (is (= ["declarations: 3/3"] @observed-lines))))

(deftest program-population-store-uses-measured-index-fanout
  (is (= {:branching-factor 4096
          :diff-buf-size 256}
         (:index-config
          (store/datahike-configuration "tmp/fn-test/population-store")))
      "4096-way persistent-set nodes bound one population's durable writes"))

(defn- capability-fixture!
  [root capability-source]
  (write-source!
   root "seon/effect.clj"
   (str "(ns seon.effect)\n"
        "(defn request! [owner request] [owner request])\n"))
  (write-source! root "sample/capability.clj" capability-source)
  root)

(defn- capability-refusal
  [capability-source]
  (let [root (capability-fixture! (fixture-root) capability-source)]
    (try
      (seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]})
      nil
      (catch clojure.lang.ExceptionInfo error error))))

(deftest capability-metadata-is-one-program-graph-contract
  (let [root
        (capability-fixture!
         (fixture-root)
         (str "(ns sample.capability\n"
              "  (:require [seon.effect :as effect]))\n"
              "(defn- handler\n"
              "  {:malli/schema [:=> [:cat :map :map] :map]}\n"
              "  [request effective] (assoc request :effective effective))\n"
              "(defn leaf\n"
              "  {:malli/schema [:=> [:cat :map] :map]\n"
              "   :seon.workload :io\n"
              "   :seon.effect/capability sample.capability/handler}\n"
              "  [request] (effect/request! #'leaf request))\n"
              "(defn pure-caller [request] (leaf request))\n"
              "(defn blocking-helper {:seon.workload :io} [request] request)\n"
              "(defn compute-leaf {:seon.workload :compute} [request] request)\n"
              "(defn mixed-caller [request]\n"
              "  [(compute-leaf request) (leaf request)])\n"))
        rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
        by-symbol (into {} (keep (fn [row]
                                  (when-let [sym (:seon.fn/sym row)]
                                    [sym row]))) rows)
        leaf (get by-symbol "sample.capability/leaf")]
    (testing "the owner row carries handler, schema, and workload together"
      (is (= "sample.capability/leaf" (:seon.fn/sym leaf)))
      (is (= 'sample.capability/handler
             (:seon.effect/capability leaf)))
      (is (string? (:seon.fn/spec leaf)))
      (is (= :io (:seon.fn/workload leaf))))
    (testing "call edges alone reveal the capability owner"
      (is (= #{[:seon.fn/sym "sample.capability/leaf"]}
             (:seon.fn/calls (get by-symbol "sample.capability/pure-caller"))))
      (is (= #{[:seon.fn/sym "sample.capability/compute-leaf"]
               [:seon.fn/sym "sample.capability/leaf"]}
             (:seon.fn/calls (get by-symbol "sample.capability/mixed-caller")))))
    (testing "a pure blocking helper remains capability-free"
      (is (= :io
             (:seon.fn/workload
              (get by-symbol "sample.capability/blocking-helper"))))
      (is (nil? (:seon.effect/capability
                 (get by-symbol "sample.capability/blocking-helper")))))))

(deftest quoted-private-handler-symbol-is-indexed-as-the-runtime-symbol
  (let [root
        (capability-fixture!
         (fixture-root)
         (str "(ns sample.capability\n"
              "  (:require [seon.effect :as effect]))\n"
              "(defn- handler\n"
              "  {:malli/schema [:=> [:cat :map :map] :map]}\n"
              "  [request effective] (assoc request :effective effective))\n"
              "(defn leaf\n"
              "  {:malli/schema [:=> [:cat :map] :map]\n"
              "   :seon.workload :io\n"
              "   :seon.effect/capability 'sample.capability/handler}\n"
              "  [request] (effect/request! #'leaf request))\n"))
        rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
        leaf (first (filter #(= "sample.capability/leaf"
                                (:seon.fn/sym %))
                            rows))]
    (is (= 'sample.capability/handler
           (:seon.effect/capability leaf)))))

(deftest capability-indexing-refuses-every-malformed-declaration
  (let [base
        (fn [handler owner]
          (str "(ns sample.capability\n"
               "  (:require [seon.effect :as effect]))\n"
               handler "\n" owner "\n"))
        handler
        (str "(defn- handler\n"
             "  {:malli/schema [:=> [:cat :map :map] :map]}\n"
             "  [request effective] (assoc request :effective effective))")
        owner
        (fn [metadata body]
          (str "(defn leaf\n  " metadata "\n"
               "  [request] " body ")"))
        refusal-rule
        (fn [source]
          (:seon.fn/capability-rule
           (some-> source capability-refusal ex-data)))]
    (is (= :marker-without-workload
           (refusal-rule
            (base handler
                  (owner
                   "{:malli/schema [:=> [:cat :map] :map]\n   :seon.effect/capability sample.capability/handler}"
                   "(effect/request! #'leaf request)")))))
    (is (= :capability-workload-not-io
           (refusal-rule
            (base handler
                  (owner
                   "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :compute\n   :seon.effect/capability sample.capability/handler}"
                   "(effect/request! #'leaf request)")))))
    (is (= :missing-handler
           (refusal-rule
            (base handler
                  (owner
                   "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/missing}"
                   "(effect/request! #'leaf request)")))))
    (is (= :public-handler
           (refusal-rule
            (base
             (str "(defn handler\n"
                  "  {:malli/schema [:=> [:cat :map :map] :map]}\n"
                  "  [request effective] (assoc request :effective effective))")
             (owner
              "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/handler}"
              "(effect/request! #'leaf request)")))))
    (is (= :unschemaed-handler
           (refusal-rule
            (base
             "(defn- handler [request effective] (assoc request :effective effective))"
             (owner
              "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/handler}"
              "(effect/request! #'leaf request)")))))
    (is (= :capability-handler
           (refusal-rule
            (base
             (str "(defn- handler\n"
                  "  {:malli/schema [:=> [:cat :map :map] :map]\n"
                  "   :seon.workload :io\n"
                  "   :seon.effect/capability sample.capability/handler}\n"
                  "  [request effective] (assoc request :effective effective))")
             (owner
              "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/handler}"
              "(effect/request! #'leaf request)")))))
    (is (= :unmarked-request
           (refusal-rule
            (base handler
                  "(defn leaf [request] (effect/request! #'leaf request))"))))
    (is (= :capability-without-request
           (refusal-rule
            (base handler
                  (owner
                   "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/handler}"
                   "request")))))))

(deftest static-index-preserves-the-jvm-row-contract
  (let [root (fixture-root)
        source
        (str "(ns sample.core\n"
             "  (:require [clojure.test :refer [deftest]]\n"
             "            [clojure.test.check.clojure-test :refer [defspec]]\n"
             "            [clojure.string :as str])\n"
             "  (:import [java.util Date]))\n"
             "(defmacro sample-macro \"Macro doc.\" [x] x)\n"
             "(defn ^:private helper [x] (sample-macro (str/trim x)))\n"
             "(defn ^{:malli/schema [:=> [:cat fn?] string?]\n"
             "         :seon.workload :compute\n"
             "         :seon.fn/external-sink :ai-visible-text\n"
             "         :seon.fn/projection-boundary :none}\n"
             "  contracted \"Exact doc.\" [f] (helper (f)))\n"
             "(defrecord Pair [left right])\n"
             "(deftype Cell [value])\n"
             "(deftest example-test (contracted identity))\n"
             "(defspec generated-test 10 true)\n"
             "(throw (ex-info \"top-level source must never run\" {}))\n")]
    (write-source! root "sample/core.clj" source)
    (let [rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
          by-id (into {} (map (juxt program/row-identity identity)) rows)
          namespace-row (get by-id [:seon.ns/name 'sample.core])]
      (testing "top-level source is analyzed and never evaluated"
        (is (contains? by-id [:seon.fn/sym "sample.core/contracted"])))
      (testing "functions, tests, records, and types keep JVM parity"
        (is (= #{"sample.core/sample-macro"
                 "sample.core/helper" "sample.core/contracted"
                 "sample.core/->Pair" "sample.core/map->Pair"
                 "sample.core/->Cell"}
               (into #{} (keep :seon.fn/sym) rows)))
        (is (= #{"sample.core/example-test" "sample.core/generated-test"}
               (into #{} (keep :seon.test/sym) rows)))
        (is (true? (:seon.fn/private?
                    (get by-id [:seon.fn/sym "sample.core/helper"]))))
        (is (= "([f])" (:seon.fn/arglists
                         (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= "[:=> [:cat clojure.core/fn?] clojure.core/string?]"
               (:seon.fn/spec
                (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= :compute (:seon.fn/workload
                         (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= :ai-visible-text
               (:seon.fn/external-sink
                (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= :none
               (:seon.fn/projection-boundary
                (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= #{[:seon.fn/sym "sample.core/helper"]}
               (:seon.fn/calls
                (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= #{[:seon.fn/sym "sample.core/contracted"]}
               (:seon.fn/calls
                (get by-id [:seon.test/sym "sample.core/example-test"]))))
        (is (= "Macro doc."
               (:seon.fn/doc
                (get by-id [:seon.fn/sym "sample.core/sample-macro"]))))
        (is (= "([x])"
               (:seon.fn/arglists
                (get by-id [:seon.fn/sym "sample.core/sample-macro"]))))
        (is (= "(defmacro sample-macro \"Macro doc.\" [x] x)"
               (:seon.fn/source
                (get by-id [:seon.fn/sym "sample.core/sample-macro"]))))
        (is (false? (:seon.fn/private?
                     (get by-id [:seon.fn/sym "sample.core/sample-macro"]))))
        (is (true? (:seon.fn/macro?
                    (get by-id [:seon.fn/sym "sample.core/sample-macro"]))))
        (is (not (contains?
                  (get by-id [:seon.fn/sym "sample.core/helper"])
                  :seon.fn/macro?))
            "ordinary function rows carry no false macro assertion")
        (is (nil? (:seon.fn/spec
                   (get by-id [:seon.fn/sym "sample.core/sample-macro"])))
            "macro rows do not claim runtime function contracts")
        (is (= #{[:seon.fn/sym "clojure.string/trim"]
                 [:seon.fn/sym "sample.core/sample-macro"]}
               (:seon.fn/calls
                (get by-id [:seon.fn/sym "sample.core/helper"])))
            "macro calls remain first-party graph edges")
        (is (= "(defrecord Pair [left right])"
               (:seon.fn/source
                (get by-id [:seon.fn/sym "sample.core/map->Pair"]))))
      (testing "namespace context is exact source data"
        (is (= #{[:seon.ns/name 'clojure.test]
                 [:seon.ns/name 'clojure.test.check.clojure-test]
                 [:seon.ns/name 'clojure.string]}
               (:seon.ns/requires namespace-row)))
        (is (contains? (:seon.ns/aliases namespace-row)
                       {:seon.ns.alias/local 'str
                        :seon.ns.alias/target-ns 'clojure.string}))
        (is (contains? (:seon.ns/refers namespace-row)
                       {:seon.ns.refer/local 'deftest
                        :seon.ns.refer/target-ns 'clojure.test
                        :seon.ns.refer/target-name 'deftest}))
        (is (contains? (:seon.ns/imports namespace-row)
                        {:seon.ns.import/local 'Date
                        :seon.ns.import/target-class 'java.util.Date})))))))

(deftest publication-is-first-party-only
  (let [root (fixture-root)]
    (write-source! root "first/party.clj"
                   "(ns first.party (:require [clojure.string :as str]))\n(defn trim [x] (str/trim x))")
    (let [rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})]
      (is (= #{'first.party} (into #{} (keep :seon.ns/name) rows)))
      (is (= #{"first.party/trim"} (into #{} (keep :seon.fn/sym) rows)))
      (is (not-any? #(= "clojure.string/trim" (:seon.fn/sym %)) rows))))
  (is (= ["src" "test"] seon.fn/source-roots)))

(deftest settled-form-records-calls-across-every-program-namespace
  (test-support/with-database
    (fn [connection]
      (let [namespace-name 'my.agents.call-edges
            process "call-edges-process"
            source "(do (seon.db/q '[:find (count ?function) . :where [?function :seon.fn/sym _]]) (my.turn/wait {:my.turn/note \"Waiting.\"}))"]
        (test-support/seed-cluster! connection "call-edges")
        (transact-fixture!
         connection
         [{:seon.ns/name namespace-name
           :seon.ns/source "(ns my.agents.call-edges)"
           :seon.ns/requires [[:seon.ns/name 'my.turn]]}
          {:seon.agent/id "call-edges-direct"
           :seon.agent/namespace [:seon.ns/name namespace-name]}
          {:seon.agent/id "call-edges-fold"
           :seon.agent/namespace [:seon.ns/name namespace-name]}])
        (let [ctx (test-support/fork-cluster-ctx connection)
              cluster (test-support/cluster-handle
                       {:seon.db/connection connection
                        :seon.cluster/name "call-edges"
                        :seon.db.process/id process
                        :seon.sci.eval/ctx ctx})]
          (doseq [run-id ["call-edges-direct" "call-edges-fold"]]
            (transact-fixture!
             connection
             (turn/open-tx
              {:seon.turn/id run-id
               :seon.turn/agent [:seon.agent/id run-id]
               :seon.turn/opened-tx "datomic.tx"}))
            (transact-fixture!
             connection
             (turn/plan-tx
              {:seon.turn/id run-id :seon.db.process/id process
               :seon.turn/starting-ns [:seon.ns/name namespace-name]
               :seon.turn/sources [{:seon.cluster.eval/source source}]}))
            (if (= run-id "call-edges-direct")
              (let [database (db/db connection)
                    captured (atom [])
                    evaluation
                    (binding [db/*read-evidence-sink* captured]
                      (sci.eval/evaluate
                       (merge (select-keys cluster [:seon.sci.admit/caps
                                                   :seon.config/on-core-error])
                              {:seon.cluster.eval/source source
                               :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                               :seon.sci.eval/ctx ctx
                               :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms cluster)
                               :seon.db/db database :seon.db/connection connection})))]
                (is (= {:my.turn/disposition :wait :my.turn/note "Waiting."}
                       (:seon.sci.admit/value evaluation)) (pr-str evaluation))
                (transact-fixture!
                 connection
                 (turn/receipt-settle-tx
                  (db/db connection)
                  {:seon.turn/id run-id :seon.cluster.eval/ordinal 0
                   :seon.eval/shown (:seon.eval/shown evaluation)
                   :seon.cluster.eval/read-evidence (db/read-evidence @captured)
                   :seon.cluster.eval/read-basis-transaction (db/basis-t database)})))
              (is (= [:closed 1]
                     (#'turn/resume-turn
                      {:seon.turn.loop/cluster cluster
                       :seon.turn.loop/work {:seon.agent/id run-id
                                             :seon.turn/id run-id
                                             :seon.cluster.eval/ordinal 0}
                       :seon.turn.loop/now (java.util.Date.)
                       :seon.turn.loop/report (fn [outcome n] [outcome n])}))))
            (let [calls (set (db/q '[:find [?symbol ...]
                                    :in $ ?id
                                    :where [?evaluation :seon.cluster.eval/id ?id]
                                    [?evaluation :seon.fn/calls ?callee]
                                    [?callee :seon.fn/sym ?symbol]]
                                  (db/db connection) (turn/receipt-identity run-id 0)))]
              (is (contains? calls "seon.db/q") (pr-str calls))
              (is (contains? calls "my.turn/wait") (pr-str calls)))))))))

(deftest settled-agent-form-has-static-index-edge-parity
  (let [root (fixture-root)
        namespace-name 'sample.settlement-parity
        source
        (str "(defn ^{:malli/schema [:=> [:cat :int] :map]\n"
             "         :seon.test/subject sample.settlement-parity/helper}\n"
             "  contracted [value]\n"
             "  {:sample.settlement-parity/value (helper value)})")
        file-source
        (str "(ns sample.settlement-parity)\n"
             "(defn helper [value] value)\n"
             source "\n")]
    (write-source! root "sample/settlement_parity.clj" file-source)
    (let [rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
          indexed
          (first (filter #(= "sample.settlement-parity/contracted"
                             (:seon.fn/sym %))
                         rows))
          expected
          (select-keys indexed
                       [:seon.fn/calls :seon.fn/keywords
                        :seon.test/subject])]
      (test-support/with-database
        (fn [connection]
          (db/transact!
           connection
           ;; The indexed row carries its file ref, so the emitted file rows
           ;; are admitted first exactly as publication admits them.
           (into (filterv :seon.fn.file/path rows)
            [{:seon.ns/name namespace-name
             :seon.ns/source "(ns sample.settlement-parity)"}
            {:seon.fn/sym "sample.settlement-parity/helper"
             :seon.schema.admission/source :core
             :seon.fn/ns [:seon.ns/name namespace-name]
             :seon.fn/source "(defn helper [value] value)"
             :seon.fn/arglists "([value])"
             :seon.fn/private? false}
            {:seon.agent/id "settlement-parity-agent"
             :seon.agent/namespace
             [:seon.ns/name namespace-name]}]))
          (db/transact!
           connection
           (turn/open-tx
            {:seon.turn/id "settlement-parity-run" :seon.turn/agent [:seon.agent/id "settlement-parity-agent"] :seon.turn/opened-tx "datomic.tx"}))

          (db/transact!
           connection
           (turn/plan-tx
            {:seon.turn/id "settlement-parity-run" :seon.db.process/id (second boot-process) :seon.turn/starting-ns [:seon.ns/name namespace-name] :seon.turn/sources [{:seon.cluster.eval/source source}]}))
          (db/transact!
           connection
           (turn/receipt-start-tx
            {:seon.turn/id "settlement-parity-run"
             :seon.cluster.eval/ordinal 0
             :seon.cluster.eval/at (java.util.Date.)}))
          (let [settlement
                (db/transact!
                 connection
                 (turn/receipt-settle-tx
                  @connection
                  {:seon.turn/id "settlement-parity-run"
                   :seon.cluster.eval/ordinal 0
                   :seon.eval/shown ":defined"
                   :seon.program/row indexed}))]
            (is (nil? (:seon.error/kind settlement))
                (pr-str (select-keys settlement
                                     [:seon.error/kind :seon.error/message
                                      :seon.error/data]))))
          (let [edge-facts
                (fn [identity-attribute identity-value]
                  (let [entity
                        (db/q '[:find ?entity .
                                :in $ ?attribute ?value
                                :where [?entity ?attribute ?value]]
                              @connection identity-attribute identity-value)
                        _ (when-not entity
                            (throw (ex-info "Expected fixture entity was not installed"
                                            {:seon.test/identity [identity-attribute identity-value]})))
                        calls
                        (db/q '[:find [?symbol ...]
                                :in $ ?entity
                                :where
                                [?entity :seon.fn/calls ?target]
                                [?target :seon.fn/sym ?symbol]]
                              @connection entity)
                        keywords
                        (db/q '[:find [?keyword ...]
                                :in $ ?entity
                                :where
                                [?entity :seon.fn/keywords ?keyword]]
                              @connection entity)
                        subject
                        (db/q '[:find ?symbol .
                                :in $ ?entity
                                :where
                                [?entity :seon.test/subject ?target]
                                [?target :seon.fn/sym ?symbol]]
                              @connection entity)]
                    (merge
                     (when (seq calls)
                       {:seon.fn/calls
                        (into #{} (map (fn [function-symbol]
                                         [:seon.fn/sym function-symbol])) calls)})
                     (when (seq keywords)
                       {:seon.fn/keywords (set keywords)})
                     (when subject
                       {:seon.test/subject [:seon.fn/sym subject]}))))
                program-facts
                (edge-facts :seon.fn/sym
                            "sample.settlement-parity/contracted")
                form-facts
                (edge-facts :seon.cluster.eval/id
                            (turn/receipt-identity "settlement-parity-run" 0))]
            (is (= expected program-facts))
            (is (nil? form-facts)
                "the definition row is the sole owner of graph facts")
            (is (= :agent
                   (db/q '[:find ?author .
                           :in $ ?form-id
                           :where
                           [?form :seon.cluster.eval/id ?form-id]
                           [?form :seon.cluster.eval/author ?author]]
                         @connection
                         (turn/receipt-identity
                          "settlement-parity-run" 0)))
                "the authored form and its queryable edges settle together")))))))

(deftest requires-resolve-totally
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            requires
            (db/q '[:find ?namespace ?required ?required-name
                   :where
                   [?namespace :seon.ns/requires ?required]
                   [?required :seon.ns/name ?required-name]]
                 db)
            required-eids
            (into #{} (map second) requires)
            name-only-eids
            (db/q '[:find [?namespace ...]
                   :where
                   [?namespace :seon.ns/name]
                   (not [?namespace :seon.ns/source])]
                 db)
            name-only-eids (set name-only-eids)
            external-eids-by-name
            (reduce
             (fn [by-name [_ required required-name]]
               (cond-> by-name
                 (contains? name-only-eids required)
                 (update required-name (fnil conj #{}) required)))
             {}
             requires)]
        (is (seq requires))
        (is (every? (fn [[_ required required-name]]
                      (and (integer? required)
                           (symbol? required-name)))
                    requires))
        (is (some name-only-eids required-eids)
            "external requires are shared name-only namespace rows")
        (is (every? #(= 1 (count %)) (vals external-eids-by-name))
            "each external namespace name resolves to exactly one eid")
        (is (empty?
             (db/q '[:find ?namespace ?required
                    :where
                    [?namespace :seon.ns/requires ?required]
                    (not [?required :seon.ns/name])]
                  db)))))))

(deftest contracted-rows-carry-queryable-facts-in-their-spec-transaction
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            contracted
            (db/q '[:find [?function ...]
                   :where [?function :seon.fn/spec]]
                 db)
            complete
            (db/q '[:find [?function ...]
                   :where
                   [?function :seon.fn/spec]
                   [?function :seon.fn/arities]
                   [?function :seon.fn/ast]]
                 db)
            arities
            (db/q '[:find [?arity ...]
                    :where [_ :seon.fn/arities ?arity]]
                  db)
            complete-arities
            (db/q '[:find [?arity ...]
                    :where
                    [_ :seon.fn/arities ?arity]
                    [?arity :seon.fn.arity/argument-count]
                    [?arity :seon.fn.arity/return-schema]]
                  db)
            assertion-transactions
            (db/q '[:find ?function ?spec-tx ?arities-tx ?ast-tx
                   :where
                   [?function :seon.fn/spec _ ?spec-tx]
                   [?function :seon.fn/arities _ ?arities-tx]
                   [?function :seon.fn/ast _ ?ast-tx]]
                 db)
            arity-assertion-transactions
            (db/q '[:find ?function ?spec-tx ?arity-tx ?count-tx ?return-tx
                    :where
                    [?function :seon.fn/spec _ ?spec-tx]
                    [?function :seon.fn/arities ?arity ?arity-tx]
                    [?arity :seon.fn.arity/argument-count _ ?count-tx]
                    [?arity :seon.fn.arity/return-schema _ ?return-tx]]
                  db)
            argument-assertion-transactions
            (db/q '[:find ?function ?spec-tx ?argument-tx ?index-tx
                           ?binding-tx ?schema-tx
                    :where
                    [?function :seon.fn/spec _ ?spec-tx]
                    [?function :seon.fn/arities ?arity]
                    [?arity :seon.fn.arity/arguments ?argument ?argument-tx]
                    [?argument :seon.fn.argument/index _ ?index-tx]
                    [?argument :seon.fn.argument/binding _ ?binding-tx]
                    [?argument :seon.fn.argument/schema _ ?schema-tx]]
                  db)
            functions-by-role
            (db/q '[:find ?role ?function-symbol
                   :in $ ?schema-key
                   :where
                   [?schema :seon.schema/key ?schema-key]
                   (or-join [?schema ?arity ?role]
                     (and [?arity :seon.fn.arity/input-refs ?schema]
                          [(ground :input) ?role])
                     (and [?arity :seon.fn.arity/output-refs ?schema]
                          [(ground :output) ?role]))
                   [?function :seon.fn/arities ?arity]
                   [?function :seon.fn/sym ?function-symbol]]
                 db :seon.schema/value)]
        (testing "the complete contracted population is backfilled"
          (is (seq contracted))
          (is (= (set contracted) (set complete)))
          (is (= (set arities) (set complete-arities))))
        (testing "spec and every parsed root assert atomically"
          (is (= (count contracted) (count assertion-transactions)))
          (is (every? (fn [[_ spec-tx arities-tx ast-tx]]
                        (= spec-tx arities-tx ast-tx))
                      assertion-transactions))
          (is (every? (fn [[_ spec-tx arity-tx count-tx return-tx]]
                        (= spec-tx arity-tx count-tx return-tx))
                      arity-assertion-transactions))
          (is (every? (fn [[_ spec-tx argument-tx index-tx binding-tx
                            schema-tx]]
                        (= spec-tx argument-tx index-tx binding-tx schema-tx))
                      argument-assertion-transactions)))
        (testing "one query answers both directions for a given schema"
          (is (seq (filter (comp #{:input} first) functions-by-role)))
          (is (seq (filter (comp #{:output} first) functions-by-role))))))))

(deftest parsed-contract-backfill-is-one-transaction-and-idempotent
  (test-support/with-database
    (fn [connection]
      (let [functions
            (take 2
                  (sort
                   (db/q '[:find [?function ...]
                          :where
                          [?function :seon.fn/spec]
                          [?function :seon.fn/arities]
                          [?function :seon.fn/ast]]
                        @connection)))]
        (is (= 2 (count functions)))
        (db/transact!
         connection
         (into []
               (mapcat (fn [function]
                         [[:db.fn/retractAttribute function :seon.fn/arities]
                          [:db.fn/retractAttribute function :seon.fn/ast]]))
               functions))
        (let [before (:max-tx @connection)
              first-result
              (seon.fn/backfill-contract-facts!
               {:seon.db/connection connection
                :seon.db/process boot-process})
              after-first (:max-tx @connection)
              second-result
              (seon.fn/backfill-contract-facts!
               {:seon.db/connection connection
                :seon.db/process boot-process})
              after-second (:max-tx @connection)]
          (is (= {:seon.reconcile/converged? false
                  :seon.reconcile/operations 2}
                 first-result))
          (is (= (inc before) after-first)
              "all missing graphs commit in one transaction")
          (is (= {:seon.reconcile/converged? true
                  :seon.reconcile/operations 0}
                 second-result))
          (is (= after-first after-second)
              "the converged second run writes nothing")
          (is (empty?
               (db/q '[:find ?function
                      :where
                      [?function :seon.fn/spec]
                      (or-join [?function]
                        (not [?function :seon.fn/arities])
                        (not [?function :seon.fn/ast])
                        (and [?function :seon.fn/arities ?arity]
                             (not [?arity :seon.fn.arity/argument-count]))
                        (and [?function :seon.fn/arities ?arity]
                             (not [?arity :seon.fn.arity/return-schema])))]
                    @connection))))))))

(deftest publication-refuses-a-required-artifact-load-finding
  (let [root (fixture-root)]
    (write-source! root "audit/unresolved.clj"
                   "(ns audit.unresolved (:require [clojure.set :as sets]))\n(defn broken [] missing)\n")
    (let [analysis (analyzer/analyze {::analyzer/paths [(.getPath root)]})
          _ (is (some #(= :warning (::analyzer/level %))
                      (::analyzer/findings analysis))
                "the real source fixture produces an unrelated warning")
          failure
          (try
            (seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]})
            nil
            (catch clojure.lang.ExceptionInfo error error))]
      (is (= :seon.fn/index-refused (:seon.error/kind (ex-data failure))))
      (is (some #(= :unresolved-symbol (::analyzer/type %))
                (::seon.fn/findings (ex-data failure))))
      (is (every? #(= :error (::analyzer/level %))
                  (::seon.fn/findings (ex-data failure)))))))

(deftest source-context-is-derived-once-per-file-population
  (let [root (fixture-root)
        _ (write-source!
           root "sample/once.clj"
           (str "(ns sample.once)\n"
                "(defn one [] 1)\n"
                "(defn two [] 2)\n"
                "(defn three [] 3)\n"))
        read-form-var (ns-resolve 'seon.fn 'read-jvm-form)
        read-form (var-get read-form-var)
        reads (atom 0)]
    (with-redefs-fn
      {read-form-var
       (fn [source]
         (swap! reads inc)
         (read-form source))}
      #(seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]}))
    (is (= 1 @reads)
        "the namespace form is parsed once, not once per declaration")))

(deftest publication-veto-is-exactly-the-cant-load-finding-classes
  (let [root (fixture-root)
        source-file
        (write-source! root "audit/valid.clj"
                       "(ns audit.valid)\n(defn value [] 1)\n")
        request {:seon.fn/roots [(.getPath root)]}
        base-analysis
        (analyzer/analyze
         {::analyzer/paths [(.getCanonicalPath source-file)]})
        finding
        (fn [finding-type]
          {::analyzer/filename (.getCanonicalPath source-file)
           ::analyzer/row 2
           ::analyzer/col 1
           ::analyzer/level :error
           ::analyzer/type finding-type
           ::analyzer/message (str "synthetic " (name finding-type))})]
    (doseq [finding-type
            [:syntax :unresolved-symbol :unresolved-namespace
             :unresolved-var :private-call :invalid-arity]]
      (testing (name finding-type)
        (let [failure
              (with-redefs
                [analyzer/analyze
                 (fn [_]
                   (assoc base-analysis ::analyzer/findings
                          [(finding finding-type)]))]
                (try
                  (seon.fn/build-manifest request)
                  nil
                  (catch clojure.lang.ExceptionInfo error error)))]
          (is (= :seon.fn/index-refused
                 (:seon.error/kind (ex-data failure)))))))
    (testing "an elevated non-load finding remains visible as a warning"
      (with-redefs
        [analyzer/analyze
         (fn [_]
           (assoc base-analysis ::analyzer/findings
                  [(finding :unused-binding)]))]
        (let [manifest (seon.fn/build-manifest request)]
          (is (= [:warning]
                 (mapv ::analyzer/level
                       (:seon.fn.manifest/findings manifest)))))))))

(deftest my-web-cascade-class-warns-without-vetoing-publication
  (let [root (fixture-root)
        source-file
        (write-source! root "my/web.clj"
                       "(ns my.web)\n(defn fetch [] :ok)\n")
        request {:seon.fn/roots [(.getPath root)]}
        base-analysis
        (analyzer/analyze
         {::analyzer/paths [(.getCanonicalPath source-file)]})
        cascade
        (mapv
         (fn [ordinal]
           {::analyzer/filename (.getCanonicalPath source-file)
            ::analyzer/row 2
            ::analyzer/col (inc ordinal)
            ::analyzer/level :error
            ::analyzer/type :unused-binding
            ::analyzer/message (str "my.web cascade " ordinal)})
         (range 44))]
    (with-redefs
      [analyzer/analyze
       (fn [_]
         (assoc base-analysis ::analyzer/findings cascade))]
      (let [manifest (seon.fn/build-manifest request)
            findings (:seon.fn.manifest/findings manifest)]
        (is (= 44 (count findings)))
        (is (every? #(= :warning (::analyzer/level %)) findings))
        (is (some #{[:seon.fn/sym "my.web/fetch"]}
                  (:seon.fn.manifest/identities manifest)))))))

(deftest file-artifacts-and-manifests-are-byte-digested-and-deterministic
  (let [root (fixture-root)
        alpha-source
        (str "(ns artifact.alpha)\r\n"
             "(defn target [] 1)\r\n")
        beta-source
        (str "(ns artifact.beta\n"
             "  (:require [artifact.alpha :as alpha]\n"
             "            [clojure.string :as str]))\n"
             "(defn caller [x] (str/trim (str (alpha/target) x)))\n")
        alpha (write-source! root "artifact/alpha.clj" alpha-source)
        beta (write-source! root "artifact/beta.clj" beta-source)
        request {:seon.fn/roots [(.getPath root)]}
        manifest (seon.fn/build-manifest request)
        repeated (seon.fn/build-manifest request)
        artifacts (into {} (map (juxt :seon.fn.file/path identity))
                        (:seon.fn.manifest/artifacts manifest))
        beta-artifact (get artifacts (.getCanonicalPath beta))
        beta-caller
        (first (filter #(= "artifact.beta/caller" (:seon.fn/sym %))
                       (:seon.fn.file/rows beta-artifact)))
        incremental
        (seon.fn/build-artifact
         {:seon.fn.file/path (.getPath beta)
          :seon.fn.file/first-party-functions
          ["artifact.alpha/target"]})]
    (testing "the complete manifest is stable and partitions every file"
      (is (= manifest repeated))
      (is (every? #(= :core (:seon.schema.admission/source %))
                  (mapcat :seon.fn.file/rows
                          (:seon.fn.manifest/artifacts manifest)))
          "every analyzed declaration enters the manifest with its provenance")
      (is (every? set?
                  (keep :seon.fn/calls
                        (mapcat :seon.fn.file/rows
                                (:seon.fn.manifest/artifacts manifest))))
          "cardinality-many call facts retain their declared transaction shape")
      (is (= #{(.getCanonicalPath alpha) (.getCanonicalPath beta)}
             (set (keys artifacts))))
      (is (re-matches #"[0-9a-f]{64}"
                      (:seon.fn.manifest/digest manifest))))
    (testing "pure manifest helpers find files and derive function context"
      (is (= beta-artifact
             (seon.fn/artifact-by-path manifest (.getCanonicalPath beta))))
      (is (nil? (seon.fn/artifact-by-path manifest "/absent.clj")))
      (is (= ["artifact.alpha/target" "artifact.beta/caller"]
             (seon.fn/manifest-function-symbols manifest))))
    (testing "artifact replacement recomputes one deterministic manifest"
      (let [changed-beta (assoc beta-artifact :seon.fn.file/digest (id/digest 64 ["changed"]))
            changed (seon.fn/replace-manifest-artifacts manifest [changed-beta])]
        (is (= changed-beta
               (seon.fn/artifact-by-path changed (.getCanonicalPath beta))))
        (is (= (:seon.fn.manifest/roots manifest)
               (:seon.fn.manifest/roots changed)))
        (is (= (:seon.fn.manifest/identities manifest)
               (:seon.fn.manifest/identities changed)))
        (is (not= (:seon.fn.manifest/digest manifest)
                  (:seon.fn.manifest/digest changed)))
        (is (= (sort (map :seon.fn.file/path
                          (:seon.fn.manifest/artifacts changed)))
               (map :seon.fn.file/path
                    (:seon.fn.manifest/artifacts changed))))))
    (testing "one-file analysis records every call target (ruling 42b)"
      (is (= #{[:seon.fn/sym "artifact.alpha/target"]
               [:seon.fn/sym "clojure.core/str"]
               [:seon.fn/sym "clojure.string/trim"]}
             (:seon.fn/calls beta-caller)))
      (is (= beta-artifact incremental)))
    (testing "the file digest covers exact bytes, including CRLF"
      (is (re-matches #"[0-9a-f]{64}"
                      (:seon.fn.file/digest (get artifacts
                                                 (.getCanonicalPath alpha)))))
      (is (= "(ns artifact.alpha)"
             (:seon.ns/source
              (first (filter :seon.ns/name
                             (:seon.fn.file/rows
                              (get artifacts (.getCanonicalPath alpha)))))))))))

(deftest changed-file-planning-is-conservative-and-explicit
  (let [path "/repo/src/sample.clj"
        namespace-row {:seon.ns/name 'sample
                       :seon.ns/source "(ns sample)"}
        current-row {:seon.fn/sym "sample/value"
                     :seon.schema.admission/source :core
                     :seon.fn/ns [:seon.ns/name 'sample]
                     :seon.fn/source "(defn value [] 1)"
                     :seon.fn/arglists "([])"
                     :seon.fn/private? false}
        desired-row (assoc current-row :seon.fn/source "(defn value [] 2)")
        current {:seon.fn.file/path path
                 :seon.fn.file/digest "old"
                 :seon.fn.file/rows [namespace-row current-row]
                 :seon.fn.file/identities
                 [[:seon.ns/name 'sample] [:seon.fn/sym "sample/value"]]}
        desired {:seon.fn.file/path path
                 :seon.fn.file/digest "new"
                 :seon.fn.file/rows [namespace-row desired-row]
                 :seon.fn.file/identities
                 [[:seon.ns/name 'sample] [:seon.fn/sym "sample/value"]]}
        base-request {:seon.fn.change/status :modified
                      :seon.fn.change/current-artifact current
                      :seon.fn.change/desired-artifact desired}
        plan #(seon.fn/plan-file-change (merge base-request %))]
    (testing "same identities with cardinality-one updates are upserts"
      (is (= {:seon.fn.change/action :incremental-upsert
              :seon.fn.change/path path
              :seon.fn.change/digest "new"
              :seon.fn.change/artifact desired
              :seon.fn.change/rows
              [desired-row]
              :seon.fn.change/identities
              [[:seon.ns/name 'sample] [:seon.fn/sym "sample/value"]]}
             (plan {}))))
    (testing "removed identity and cardinality-many changes rebuild"
      (is (contains?
           (set (:seon.fn.change/reasons
                 (plan {:seon.fn.change/desired-artifact
                        (update desired :seon.fn.file/rows pop)
                        :seon.fn.change/uncertain? true})))
           :uncertain-projection))
      (is (some #{:removed-identity}
                (:seon.fn.change/reasons
                 (plan {:seon.fn.change/desired-artifact
                        (-> desired
                            (update :seon.fn.file/rows pop)
                            (update :seon.fn.file/identities pop))}))))
      (is (some #{:component-or-cardinality-many-change}
                (:seon.fn.change/reasons
                 (plan {:seon.fn.change/desired-artifact
                        (update-in desired [:seon.fn.file/rows 1]
                                   assoc :seon.fn/calls
                                   [[:seon.fn/sym "sample/other"]])})))))
    (testing "any added identity rebuilds because old callers may resolve it"
      (let [added-row (assoc desired-row
                             :seon.fn/sym "sample/new-value"
                             :seon.fn/source "(defn new-value [] 3)")
            result
            (plan {:seon.fn.change/desired-artifact
                   (-> desired
                       (update :seon.fn.file/rows conj added-row)
                       (update :seon.fn.file/identities conj
                               [:seon.fn/sym "sample/new-value"]))})]
        (is (some #{:added-identity} (:seon.fn.change/reasons result)))
        (is (= [[:seon.fn/sym "sample/new-value"]]
               (:seon.fn.change/added-identities result)))))
    (testing "unsafe event and artifact states name their fallback reason"
      (doseq [[request reason]
              [[(dissoc (assoc base-request :seon.fn.change/status :deleted)
                        :seon.fn.change/desired-artifact) :deleted]
               [(assoc base-request :seon.fn.change/status :moved) :moved]
               [(assoc base-request :seon.fn.change/status :schema-resource) :schema-resource]
               [(assoc base-request :seon.fn.change/status :analysis-error) :analysis-error]
               [(assoc base-request :seon.fn.change/stale? true) :stale-artifact]
               [(dissoc base-request :seon.fn.change/current-artifact) :missing-artifact]
               [(assoc base-request :seon.fn.change/uncertain? true) :uncertain-projection]]]
        (is (some #{reason}
                  (:seon.fn.change/reasons (seon.fn/plan-file-change request)))
            (str request))))))

(deftest indexing-uses-a-prebuilt-manifest-without-analysis
  (let [root (fixture-root)
        _ (write-source!
           root "prebuilt.clj"
           (str "(ns prebuilt (:require [clojure.test :refer [deftest is]]))\n"
                "(defn value {:malli/schema [:=> [:cat] :int]} [] 1)\n"
                "(deftest ^{:seon.test/subject prebuilt/value} value-test (is (= 1 (value))))\n"))
        manifest (seon.fn/build-manifest
                  {:seon.fn/roots (conj seon.fn/source-roots (.getPath root))})]
    (test-support/with-database
      (fn [connection]
        (let [before @connection
              result
              (with-redefs [analyzer/analyze
                            (fn [_] (throw (ex-info "analysis must not run" {})))]
                (seon.fn/index!
                 {:seon.db/connection connection
                  :seon.db/process boot-process
                  :seon.source/previous-database before
                  :seon.fn/manifest manifest}))
              database @connection
              function-id (:db/id (db/pull database [:db/id]
                                            [:seon.fn/sym "prebuilt/value"]))
              test-row (db/pull database
                                [:seon.test/subject :seon.fn/calls]
                                [:seon.test/sym "prebuilt/value-test"])]
          (is (pos? (:seon.reconcile/operations result)))
          (is (= (inc (:max-tx before)) (:max-tx database))
              "the real writer admits the population in one transaction")
          (is (integer? function-id))
          (is (= function-id (:db/id (:seon.test/subject test-row))))
          (is (some #(= function-id (:db/id %)) (:seon.fn/calls test-row))))))
    (let [failure (test-support/refusal-data
                   #(#'seon.fn/require-committed!
                     {:seon.error/kind :seon.db/invalid-transaction}
                     :seon.fn/population))]
      (is (= :seon.fn/index-refused (:seon.error/kind failure)))
      (is (= :seon.fn/population (:seon.fn/index-phase failure))))))

(deftest keyword-usage-is-indexed-per-declaration
  (let [root (fixture-root)
        source
        (str "(ns sample.keys\n"
             "  (:require [clojure.test :refer [deftest is]]\n"
             "            [seon.error :as-alias error]))\n"
             "(defn refuse [reason]\n"
             "  {:seon.error/kind :sample.keys/refused\n"
             "   ::error/message reason\n"
             "   ::local true})\n"
             "(defn built [n] (keyword \"seon.error\" (str \"kind\" n)))\n"
             "(defn destructured [{:sample.keys/keys [depth] :keys [plain]}]\n"
             "  [depth plain])\n"
             "(deftest refusal-test\n"
             "  (is (= :sample.keys/refused (:seon.error/kind (refuse \"why\")))))\n")]
    (write-source! root "sample/keys.clj" source)
    (write-source! root "seon/error.clj" "(ns seon.error)\n(def message :m)\n")
    (let [rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
          by-id (into {} (map (juxt program/row-identity identity)) rows)
          used (fn [program-identity]
                 (:seon.fn/keywords (get by-id program-identity)))]
      (testing "literal qualified keywords land on the declaration that reads them"
        (is (= #{:sample.keys/local :sample.keys/refused
                 :seon.error/kind :seon.error/message}
               (used [:seon.fn/sym "sample.keys/refuse"]))
            "::kw, ::alias/kw, and :fully/qualified resolve to one honest form"))
      (testing "unqualified keywords stay out; qualified ones are kept verbatim"
        (is (= #{:sample.keys/depth :sample.keys/keys}
               (used [:seon.fn/sym "sample.keys/destructured"]))
            (str ":keys and :plain never enter the index. The namespaced "
                 ":sample.keys/keys marker does, because it IS written "
                 "literally — the fact is source usage, not declaredness")))
      (testing "a keyword built at runtime is invisible to static analysis"
        (is (nil? (used [:seon.fn/sym "sample.keys/built"]))
            "the honest boundary: only literal keyword usage is a fact"))
      (testing "test rows carry their own keyword usage"
        (is (= #{:sample.keys/refused :seon.error/kind}
               (used [:seon.test/sym "sample.keys/refusal-test"]))
            "a test's keywords are the ones it reads, never its subject's"))
      (testing "the indexed facts answer the motivating query"
        (test-support/with-database
          (fn [connection]
            (db/transact!
             connection
             ;; Declaration rows refer to their file row, so the file rows
             ;; are admitted with them exactly as publication admits them.
             (into (mapv #(dissoc % :seon.fn/keywords :seon.fn/calls)
                         (filter #(or (:seon.fn.file/path %) (:seon.ns/name %)
                                      (:seon.fn/sym %) (:seon.test/sym %))
                                 rows))
                   (mapcat (fn [row]
                             (map (fn [used]
                                    [:db/add (program/row-identity row)
                                     :seon.fn/keywords used])
                                  (:seon.fn/keywords row))))
                   rows))
            ;; Keywords transact as explicit datoms: inside a map, Datahike
            ;; reads a two-element collection whose first element is a
            ;; unique-identity keyword as a lookup ref and refuses the entity.
            (is (= #{:sample.keys/depth :sample.keys/keys}
                   (set (:seon.fn/keywords
                         (db/pull @connection [:seon.fn/keywords]
                                  [:seon.fn/sym "sample.keys/destructured"])))))
            (is (= ["sample.keys/refuse"]
                   (filterv #(str/starts-with? % "sample.keys/")
                            (seon.fn/functions-using @connection
                                                     :seon.error/kind)))
                "a test reading the keyword is never a function consumer")
            (is (= [] (seon.fn/functions-using @connection
                                               :sample.keys/never-written)))))))))

(deftest tests-reaching-follows-calls-and-explicit-subjects
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.reach]
            function-row
            (fn [function-symbol calls]
              (cond-> {:seon.fn/sym function-symbol
                       :seon.schema.admission/source :core
                       :seon.fn/ns namespace-ref
                       :seon.fn/source (str "(defn " (name (symbol function-symbol))
                                            " [] nil)")
                       :seon.fn/arglists "([])"
                       :seon.fn/private? false}
                (seq calls) (assoc :seon.fn/calls calls)))
            test-row
            (fn [test-symbol references]
              (merge {:seon.test/sym test-symbol
                      :seon.schema.admission/source :core
                      :seon.test/ns namespace-ref
                      :seon.test/source (str "(deftest "
                                             (name (symbol test-symbol)) ")")}
                     references))]
        (db/transact!
         connection
         [{:seon.ns/name 'sample.reach
           :seon.ns/source "(ns sample.reach)"}
          (function-row "sample.reach/target" nil)
          (function-row "sample.reach/bridge"
                        [[:seon.fn/sym "sample.reach/target"]])
          (function-row "sample.reach/direct"
                        [[:seon.fn/sym "sample.reach/target"]])
          (function-row "sample.reach/untested" nil)])
        (db/transact!
         connection
         [(test-row "sample.reach/direct"
                    {:seon.fn/calls
                     [[:seon.fn/sym "sample.reach/target"]]
                     :seon.test/pass-count 0
                     :seon.test/fail-count 1
                     :seon.test/error-count 0})
          (test-row "sample.reach/indirect"
                    {:seon.fn/calls
                     [[:seon.fn/sym "sample.reach/bridge"]]
                     :seon.test/pass-count 1
                     :seon.test/fail-count 0
                     :seon.test/error-count 0})
          (test-row "sample.reach/property"
                    {:seon.test/subject
                     [:seon.fn/sym "sample.reach/bridge"]
                     :seon.test/pass-count 1
                     :seon.test/fail-count 0
                     :seon.test/error-count 0})])
        (is (not= (:db/id (db/pull @connection [:db/id]
                                   [:seon.fn/sym "sample.reach/direct"]))
                  (:db/id (db/pull @connection [:db/id]
                                   [:seon.test/sym "sample.reach/direct"])))
            "function and test identities stay distinct at the same name")
        (is (= ["sample.reach/direct"
                "sample.reach/indirect"
                "sample.reach/property"]
               (seon.fn/tests-reaching @connection "sample.reach/target")))
        (is (nil? (:seon.fn/calls
                   (db/pull @connection [:seon.fn/calls]
                            [:seon.test/sym "sample.reach/property"])))
            "the schema-property test reaches its subject without a call edge")
        (is (= ["sample.reach/target"]
               (filterv #(str/starts-with? % "sample.reach/")
                        (seon.fn/currently-failing-functions @connection)))
            "red latest-result facts derive failing functions through calls")
        (is (= ["sample.reach/direct" "sample.reach/untested"]
               (filterv #(str/starts-with? % "sample.reach/")
                        (seon.fn/functions-without-tests @connection)))
            "absence is derived from the same test-reach graph")
        (is (= []
               (seon.fn/tests-reaching @connection "sample.reach/absent")))))))

(deftest output-path-report-finds-the-shortest-bypass
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.output]
            function-row
            (fn [function-symbol facts]
              (merge {:seon.fn/sym function-symbol
                      :seon.schema.admission/source :core
                      :seon.fn/ns namespace-ref
                      :seon.fn/source
                      (str "(defn " (name (symbol function-symbol)) " [] nil)")
                      :seon.fn/arglists "([])"
                      :seon.fn/private? false}
                     facts))]
        (transact-fixture!
         connection
         [{:seon.ns/name 'sample.output
           :seon.ns/source "(ns sample.output)"}
          (function-row "sample.output/sink"
                        {:seon.fn/external-sink :ai-visible-text
                         :seon.fn/projection-boundary :none})
          (function-row "sample.output/unresolved-sink"
                        {:seon.fn/external-sink :ai-visible-text})
          (function-row "sample.output/codec-sink"
                        {:seon.fn/external-sink :codec-storage
                         :seon.fn/projection-boundary :none})
          (function-row "sample.output/ai-projector"
                        {:seon.fn/projection-boundary :seon.render/ai
                         :seon.fn/calls [[:seon.fn/sym "sample.output/sink"]]})
          (function-row "sample.output/raw-text"
                        {:seon.fn/projection-boundary :none
                         :seon.fn/calls [[:seon.fn/sym "sample.output/sink"]]})
          (function-row "sample.output/projected-root"
                        {:seon.fn/calls [[:seon.fn/sym "sample.output/ai-projector"]]})
          (function-row "sample.output/bypass-root"
                        {:seon.fn/calls [[:seon.fn/sym "sample.output/raw-text"]]})
          (function-row "sample.output/unresolved-root"
                        {:seon.fn/calls [[:seon.fn/sym "sample.output/unresolved-sink"]]})
          (function-row "sample.output/codec-root"
                        {:seon.fn/calls [[:seon.fn/sym "sample.output/codec-sink"]]})])
        (let [paths
              (->> (:seon.fn.output/paths
                    (seon.fn/output-path-report @connection))
                   (filter #(str/starts-with?
                             (:seon.fn.output/source %)
                             "sample.output/")))
              by-source
              (into {}
                    (map (juxt :seon.fn.output/source identity))
                    paths)]
          (is (= {:seon.fn.output/classification :projected
                  :seon.fn.output/path
                  ["sample.output/projected-root"
                   "sample.output/ai-projector"
                   "sample.output/sink"]}
                 (select-keys
                  (get by-source "sample.output/projected-root")
                  [:seon.fn.output/classification :seon.fn.output/path])))
          (is (= {:seon.fn.output/classification :bypass
                  :seon.fn.output/first-bypass "sample.output/raw-text"
                  :seon.fn.output/path
                  ["sample.output/bypass-root"
                   "sample.output/raw-text"
                   "sample.output/sink"]}
                 (select-keys
                  (get by-source "sample.output/bypass-root")
                  [:seon.fn.output/classification
                   :seon.fn.output/first-bypass
                   :seon.fn.output/path])))
          (is (= :unresolved
                 (:seon.fn.output/classification
                  (get by-source "sample.output/unresolved-root"))))
          (is (= :codec
                 (:seon.fn.output/classification
                  (get by-source "sample.output/codec-root")))))))))

(deftest every-indexed-outward-path-crosses-its-total-render-projection
  (test-support/with-database
    (fn [connection]
      (let [report (seon.fn/output-path-report @connection)
            paths (:seon.fn.output/paths report)
            totals (:seon.fn.output/totals report)
            visible-paths
            (filterv #(contains? #{:ai-visible-text :html-response}
                                 (:seon.fn.output/external-sink %))
                     paths)
            offenders
            (filterv #(contains? #{:bypass :unresolved}
                                 (:seon.fn.output/classification %))
                     visible-paths)]
        (is (pos? (:seon.fn.output/sinks totals))
            "a census with no identity-bearing sink subjects is a failure")
        (is (seq visible-paths)
            "the class check must exercise agent- or human-visible paths")
        (is (every? #(= :projected (:seon.fn.output/classification %))
                    visible-paths)
            (pr-str offenders))))))

(deftest blocking-analysis-keeps-the-fresh-branch-unpublished
  (let [root (fixture-root)]
    (write-source! root "valid/core.clj" "(ns valid.core)\n(defn value [] 1)")
    (write-source! root "broken/core.clj" "(ns broken.core)\n(defn broken [")
    (test-support/with-database
      (fn [connection]
        (let [before (:max-tx @connection)
              failure (try
                        (seon.fn/index! {:seon.db/connection connection
                                         :seon.db/process boot-process
                                         :seon.fn/roots [(.getPath root)]})
                        nil
                        (catch clojure.lang.ExceptionInfo error error))]
          (is (= :seon.fn/index-refused (:seon.error/kind (ex-data failure))))
          (is (some #(= (.getCanonicalPath (io/file root "broken/core.clj"))
                        (:seon.fn.analyzer/filename %))
                    (:seon.fn/findings (ex-data failure))))
          (is (= before (:max-tx @connection)))
          (is (nil? (db/pull @connection [:db/id]
                            [:seon.fn/sym "valid.core/value"]))))))))

(deftest indexing-refuses-an-already-populated-branch
  (test-support/with-database
    (fn [connection]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"fresh source scratch"
           (seon.fn/index! {:seon.db/connection connection
                           :seon.db/process boot-process
                           :seon.source/database @connection}))))))

;;; ---------------------------------------------------------------------------
;;; ONE EVALUATION POINT — the census's own regression (ruling 71)
;;;
;;; The census that produced these assertions
;;; (docs/prds/context-generation/research/eval-points-and-caches-census-2026-09-07.md
;;; §5.3) also named the trap they exist to close: a checker written over
;;; `:seon.fn/calls` alone reported health while the busiest evaluator in the
;;; system was invisible, because `evaluate-sources` resolved its evaluator
;;; from a config fact. The indirection is gone, so the edges below are the
;;; subject; every one of them asserts a NON-EMPTY result first, because an
;;; absent subject is the failure class this repository keeps meeting.
;;; ---------------------------------------------------------------------------

(defn- test-namespace-eids
  "Every namespace some test declares itself to belong to.

  Derived from `:seon.test/ns`, never from a name ending in `-test`."
  [database]
  (set (db/q '[:find [?ns ...] :where [_ :seon.test/ns ?ns]] database)))

(defn- production-callers
  "The qualified symbols of every non-test function calling `callee-symbol`."
  [database callee-symbol]
  (let [tests (test-namespace-eids database)]
    (into #{}
          (keep (fn [[caller-symbol namespace-eid]]
                  (when-not (contains? tests namespace-eid) caller-symbol)))
          (db/q '[:find ?caller-symbol ?namespace
                  :in $ ?callee-symbol
                  :where
                  [?callee :seon.fn/sym ?callee-symbol]
                  [?caller :seon.fn/calls ?callee]
                  [?caller :seon.fn/sym ?caller-symbol]
                  [?caller :seon.fn/ns ?namespace]]
                database callee-symbol))))

(deftest sci-evaluation-has-one-first-party-owning-namespace
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            tests (test-namespace-eids database)
            edges (db/q '[:find ?caller-symbol ?callee-symbol ?namespace
                          :where
                          [?callee :seon.fn/sym ?callee-symbol]
                          [(clojure.string/starts-with? ?callee-symbol
                                                        "sci.core/eval")]
                          [?caller :seon.fn/calls ?callee]
                          [?caller :seon.fn/sym ?caller-symbol]
                          [?caller :seon.fn/ns ?namespace]]
                        database)
            production (into #{}
                             (keep (fn [[caller-symbol _ namespace-eid]]
                                     (when-not (contains? tests namespace-eid)
                                       caller-symbol)))
                             edges)
            owning (into #{}
                         (map #(first (str/split % #"/")))
                         production)]
        (testing "the census inspected a subject that exists"
          (is (seq edges) "no first-party function calls sci.core/eval* at all")
          (is (seq tests) "no namespace declares a test, so the filter is blind"))
        (testing "only one namespace turns a form into running code"
          (is (= #{"seon.sci.eval"} owning) (pr-str production)))))))

(deftest agent-source-reaches-the-evaluator-through-one-visible-path
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            evaluate (production-callers database "seon.sci.eval/evaluate-for-install")
            sources (production-callers database
                                        "seon.turn/evaluate-sources")
            previews (production-callers database
                                         "seon.turn/preview-sources")]
        (testing "the turn's edge to the evaluator is visible to the graph"
          (is (contains? evaluate "seon.turn/evaluate-sources")
              (pr-str evaluate)))
        (testing "no second function evaluates agent source"
          (is (= #{"seon.turn/resume-turn"
                   "seon.turn/system-turn"
                   "seon.turn/preview-sources"}
                 sources)
              (pr-str sources)))
        (testing "the page and system turn reuse the same evaluation path"
          (is (= #{"seon.render.web/render-source-call" "seon.turn/system-turn"} previews)
              (pr-str previews)))))))

(deftest fixture-observations-survive-static-and-runtime-admission
  (let [root (fixture-root)
        namespace-name 'sample.observation
        reason "Observe a real external fixture lifecycle."
        source (str "(ns sample.observation (:require [clojure.test :refer [deftest is]]))\n"
                    "(deftest ^{:seon.test/fixture-observation " (pr-str reason) "} observed (is true))\n"
                    "(deftest ordinary (is true))\n")]
    (try
      (write-source! root "sample/observation.clj" source)
      (test-support/with-database
        (fn [connection]
          (test-support/seed-cluster! connection "default")
          (let [rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
                admitted (db/transact! connection rows)]
            (is (:db-after admitted) (pr-str admitted))
            (is (= reason (:seon.test/fixture-observation
                           (db/pull @connection [:seon.test/fixture-observation]
                                    [:seon.test/sym "sample.observation/observed"]))))
            (is (nil? (:seon.test/fixture-observation
                        (db/pull @connection [:seon.test/fixture-observation]
                                 [:seon.test/sym "sample.observation/ordinary"])))))
          (let [ctx (test-support/fork-cluster-ctx connection)
                cluster (test-support/cluster-handle
                         {:seon.db/connection connection :seon.cluster/name "default"
                          :seon.db.process/id "observation-probe" :seon.sci.eval/ctx ctx})]
            (doseq [[name metadata expected] [["runtime-observed" (str "^{:seon.test/fixture-observation " (pr-str reason) "} ") reason]
                                             ["runtime-ordinary" "" nil]]]
              (let [evaluation (sci.eval/evaluate
                                (merge (select-keys cluster [:seon.sci.admit/caps :seon.config/on-core-error])
                                       {:seon.cluster.eval/source (str "(clojure.test/deftest " metadata name " (clojure.test/is true))")
                                        :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                                        :seon.sci.eval/ctx ctx :seon.sci.eval/time-limit-ms 10000
                                        :seon.db/db @connection :seon.db/connection connection}))
                    row (:seon.program/row evaluation)]
                (is (:seon.test/sym row) (pr-str evaluation))
                (is (= expected (:seon.test/fixture-observation row)) (pr-str row))
                (when row
                  (let [admitted (db/transact! connection [(dissoc row :seon.sci.eval/evaluated?)])]
                    (is (:db-after admitted) (pr-str admitted))
                    (is (= expected (:seon.test/fixture-observation
                                      (db/pull @connection [:seon.test/fixture-observation]
                                               [:seon.test/sym (str namespace-name "/" name)])))))))))))
      (finally (test-support/delete-recursively! root)))))

(defn- with-provenance-file [relative-path source body]
  (let [root (fixture-root)]
    (try
      (let [file (write-source! root relative-path source)]
        (test-support/with-database
          (fn [connection]
            (body connection file (seon.fn/rows {:seon.fn/roots [(.getPath root)]})))))
      (finally (test-support/delete-recursively! root)))))

(deftest indexed-declarations-carry-exact-file-bytes
  (with-provenance-file
    "sample/provenance.clj"
    (str "(ns sample.provenance (:require [clojure.test :refer [deftest is]]))\n"
         "; Unicode before the forms: café 🪴\n"
         "(defn chosen [x] x)\n(deftest example (is (= 1 (chosen 1))))\n")
    (fn [connection file rows]
      (let [declarations (filter #(or (:seon.fn/source %) (:seon.test/source %)) rows)
            bytes (java.nio.file.Files/readAllBytes (.toPath file))]
        (is (= 2 (count declarations)))
        (doseq [row declarations]
          (let [[start end :as span] (:seon.fn/form-span row)]
            (is (= [:seon.fn.file/path (.getCanonicalPath file)] (:seon.fn/file row)))
            (is (= 2 (count span)))
            (when (= 2 (count span))
              (is (= (or (:seon.fn/source row) (:seon.test/source row))
                     (String. (java.util.Arrays/copyOfRange bytes (int start) (int end))
                              java.nio.charset.StandardCharsets/UTF_8))))
            (is (= (:seon.fn/file row)
                   (:seon.fn/file (program/declaration-row row :all :core))))))
        (let [report (db/transact! connection (seon.fn/reconcile-tx (db/db connection) rows []))]
          (is (:db-after report) (pr-str (select-keys report [:seon.error/kind :seon.error/message])))
          (when (:db-after report)
            (doseq [row declarations]
              (is (= (.getCanonicalPath file)
                     (get-in (db/pull (:db-after report)
                                      [{:seon.fn/file [:seon.fn.file/path]}]
                                      (program/row-identity row))
                             [:seon.fn/file :seon.fn.file/path]))))))))))

(deftest static-findings-are-replaced-with-their-program-rows
  (with-provenance-file
    "sample/lint.clj" "(ns sample.lint)\n(defn chosen [unused] 1)\n"
    (fn [connection file rows]
      (let [findings (filter :seon.lint/id rows)
            finding (first findings)]
        (is (= 1 (count findings)))
        (is (= :unused-binding (:seon.lint/type finding)))
        (is (= [:seon.fn/sym "sample.lint/chosen"] (:seon.lint/fn finding)))
        (let [report (db/transact! connection (seon.fn/reconcile-tx (db/db connection) rows []))]
          (is (:db-after report) (pr-str (select-keys report [:seon.error/kind :seon.error/message])))
          (when (:db-after report)
            (is (= {:seon.lint/fn {:seon.fn/sym "sample.lint/chosen"}}
                   (db/pull (:db-after report) [{:seon.lint/fn [:seon.fn/sym]}]
                            [:seon.lint/id (:seon.lint/id finding)])))
            (spit file "(ns sample.lint)\n(defn chosen [used] used)\n")
            (let [artifact (seon.fn/build-artifact
                            {:seon.fn.file/path (.getCanonicalPath file)
                             :seon.fn.file/first-party-functions ["sample.lint/chosen"]})
                  corrected (:seon.fn.file/rows artifact)
                  corrected-report
                  (db/transact! connection
                                (seon.fn/reconcile-tx (db/db connection) corrected
                                                      (mapv program/row-identity rows)))]
              (is (empty? (filter :seon.lint/id corrected)))
              (is (:db-after corrected-report))
              (when (:db-after corrected-report)
                (is (empty? (db/q '[:find ?e :in $ ?file
                                    :where [?e :seon.lint/file ?f]
                                    [?f :seon.fn.file/path ?file]]
                                  (:db-after corrected-report) (.getCanonicalPath file))))
                (is (= {:seon.lint/id (:seon.lint/id finding)}
                       (db/pull (:db-after corrected-report)
                                [:seon.lint/id :seon.lint/type :seon.lint/fn]
                                [:seon.lint/id (:seon.lint/id finding)]))
                    "only the stable identity survives exact replacement")))))))))
