(ns seon.fn-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [my.program :as my.program]
            [seon.cluster :as cluster]
            [seon.cluster.store :as store]
            [datahike.api :as d]
            [seon.test.selection :as selection]
            [seon.db :as db]
            [seon.error :as error]
            [seon.turn :as turn]
            [seon.fn :as seon.fn]
            [seon.fn.analyzer :as analyzer]
            [seon.fs :as fs]
            [seon.cluster.source :as source]
            [seon.id :as id]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
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

(defn- relative-file [file]
  (fs/relative-path (fs/source-directory) (.getCanonicalPath (io/file file))))

(defn- transact-fixture! [connection rows]
  (test-support/transacted! connection rows))

(defn- analyzed-test-row [database test-symbol]
  (test-support/program-row database [:seon.test/sym test-symbol]
                            (str "(clojure.test/deftest " (name test-symbol) " (clojure.test/is true))")))

(deftest analyze-form-refuses-an-unresolvable-namespace-reference
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.missing]
            result
            (seon.fn/analyze-form
             @connection
             "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)"
             namespace-ref
             {:seon.fn/sym (quote sample.missing/f)
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
      (test-support/transacted! connection
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
              {:seon.fn/sym (quote sample.runtime-batch/shadowed)
               :seon.fn/ns namespace-ref
               :seon.fn/source shadow-source
               :seon.fn/arglists "([x])"
               :seon.fn/private? false
               :seon.schema.admission/source :agent}}
             {:seon.cluster.eval/source qualified-source
              :seon.cluster.eval/ns namespace-ref
              :seon.program/row
              {:seon.fn/sym (quote sample.runtime-batch/qualified)
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
              (set (:seon.fn/calls row)))]
        (is (= 1 @calls) "all defining sources enter kondo together")
        (is (contains? (called-symbols shadow-row) (quote clojure.core/identity)))
        (is (not (contains? (called-symbols shadow-row) (quote clojure.core/map)))
            "a let-bound map is a local, never a clojure.core/map call")
        (is (= #{(quote seon.fn/tests-reaching)}
               (called-symbols qualified-row))
            "a qualified namespace absent from the stored ns row is synthesized")
        (is (not (contains? (called-symbols shadow-row)
                            (quote seon.fn/tests-reaching)))
            "analysis facts stay inside their defining source span")))))

(deftest ordinary-form-analysis-keeps-call-edges-without-a-declaration
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.evaluation-edges]
            function-symbol (quote sample.evaluation-edges/observed)
            test-symbol (quote sample.evaluation-edges/observed-test)
            source "(do (seon.db/q '[:find ?e :where [?e :seon.agent/id]]) (my.turn/wait {:my.turn/note \"Waiting for input.\"}))"
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
              calls (set (:seon.fn/calls facts))]
          (is (contains? calls (quote seon.db/q)))
          (is (contains? calls (quote my.turn/wait)))
          (is (nil? row) "an ordinary evaluation does not invent a declaration")
          (is (empty? definition-facts) "declaration edges have one owner")
          (is (= [facts nil]
                 (seon.fn/analyze-form database source namespace-ref nil)))
          (is (not (contains? (set (:seon.fn/calls (first (nth results 3))))
                              (quote clojure.core/map)))
              "local calls do not acquire a program edge")
          (is (not (contains? calls function-symbol))
              "a neighboring test's call stays in its source span")
          (transact-fixture! connection [analyzed-function])
          (transact-fixture! connection [analyzed-test])
          (is (= [test-symbol]
                 (seon.fn/tests-reaching (db/db connection) function-symbol)))
          (is (contains? (set (seon.fn/tests-reaching (db/db connection) (quote my.turn/wait)))
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
                             [{:seon.fn/sym (quote sample/one)}
                              {:seon.fn/sym (quote sample/two)}
                              {:seon.fn/sym (quote sample/three)}]))
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
        leaf (get by-symbol (quote sample.capability/leaf))]
    (testing "the owner row carries handler, schema, and workload together"
      (is (= (quote sample.capability/leaf) (:seon.fn/sym leaf)))
      (is (= 'sample.capability/handler
             (:seon.effect/capability leaf)))
      (is (string? (:seon.fn/spec leaf)))
      (is (= :io (:seon.fn/workload leaf))))
    (testing "call edges alone reveal the capability owner"
      (is (= #{(quote sample.capability/leaf)}
             (:seon.fn/calls (get by-symbol (quote sample.capability/pure-caller)))))
      (is (= #{(quote sample.capability/compute-leaf) (quote sample.capability/leaf)}
             (:seon.fn/calls (get by-symbol (quote sample.capability/mixed-caller))))))
    (testing "a pure blocking helper remains capability-free"
      (is (= :io
             (:seon.fn/workload
              (get by-symbol (quote sample.capability/blocking-helper)))))
      (is (nil? (:seon.effect/capability
                 (get by-symbol (quote sample.capability/blocking-helper))))))))

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
        leaf (first (filter #(= (quote sample.capability/leaf)
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
        (is (contains? by-id [:seon.fn/sym (quote sample.core/contracted)])))
      (testing "functions, tests, records, and types keep JVM parity"
        (is (= #{(quote sample.core/sample-macro)
                 (quote sample.core/helper) (quote sample.core/contracted)
                 (quote sample.core/->Pair) (quote sample.core/map->Pair)
                 (quote sample.core/->Cell)}
               (into #{} (keep :seon.fn/sym) rows)))
        (is (= #{(quote sample.core/example-test) (quote sample.core/generated-test)}
               (into #{} (keep :seon.test/sym) rows)))
        (is (true? (:seon.fn/private?
                    (get by-id [:seon.fn/sym (quote sample.core/helper)]))))
        (is (= "([f])" (:seon.fn/arglists
                         (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        (is (= "[:=> [:cat clojure.core/fn?] clojure.core/string?]"
               (:seon.fn/spec
                (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        (is (= :compute (:seon.fn/workload
                         (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        (is (= :ai-visible-text
               (:seon.fn/external-sink
                (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        (is (= :none
               (:seon.fn/projection-boundary
                (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        (is (= #{(quote sample.core/helper)}
               (:seon.fn/calls
                (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        (is (= #{(quote sample.core/contracted)}
               (:seon.fn/calls
                (get by-id [:seon.test/sym (quote sample.core/example-test)]))))
        (is (= "Macro doc."
               (:seon.fn/doc
                (get by-id [:seon.fn/sym (quote sample.core/sample-macro)]))))
        (is (= "([x])"
               (:seon.fn/arglists
                (get by-id [:seon.fn/sym (quote sample.core/sample-macro)]))))
        (is (= "(defmacro sample-macro \"Macro doc.\" [x] x)"
               (:seon.fn/source
                (get by-id [:seon.fn/sym (quote sample.core/sample-macro)]))))
        (is (false? (:seon.fn/private?
                     (get by-id [:seon.fn/sym (quote sample.core/sample-macro)]))))
        (is (true? (:seon.fn/macro?
                    (get by-id [:seon.fn/sym (quote sample.core/sample-macro)]))))
        (is (not (contains?
                  (get by-id [:seon.fn/sym (quote sample.core/helper)])
                  :seon.fn/macro?))
            "ordinary function rows carry no false macro assertion")
        (is (nil? (:seon.fn/spec
                   (get by-id [:seon.fn/sym (quote sample.core/sample-macro)])))
            "macro rows do not claim runtime function contracts")
        (is (= #{(quote clojure.string/trim)}
               (:seon.fn/calls
                (get by-id [:seon.fn/sym (quote sample.core/helper)])))
            "macro expansion is not a runtime call")
        (is (contains? (set (:seon.fn/references
                             (get by-id [:seon.fn/sym (quote sample.core/helper)])))
                       (quote sample.core/sample-macro))
            "macro expansion retains a dependency without a runtime arity")
        (let [usages (::analyzer/var-usages
                      (analyzer/analyze {::analyzer/paths [(.getCanonicalPath (io/file root "sample/core.clj"))]}))
              macros (filter ::analyzer/macro usages)]
          (is (seq macros))
          (doseq [usage macros]
            (is (nil? (#'seon.fn/call-target usage))
                (pr-str (select-keys usage [::analyzer/to ::analyzer/name])))))
        (is (= "(defrecord Pair [left right])"
               (:seon.fn/source
                (get by-id [:seon.fn/sym (quote sample.core/map->Pair)]))))
      (testing "namespace context is exact source data"
        (is (= #{(quote clojure.string) (quote clojure.test.check.clojure-test) (quote clojure.test)}
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
      (is (= #{(quote first.party/trim)} (into #{} (keep :seon.fn/sym) rows)))
      (is (not-any? #(= (quote clojure.string/trim) (:seon.fn/sym %)) rows))))
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
           :seon.ns/requires #{(quote my.turn)}}
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
                                    [?evaluation :seon.fn/calls ?symbol]]
                                  (db/db connection) (turn/receipt-identity run-id 0)))]
              (is (contains? calls (quote seon.db/q)) (pr-str calls))
              (is (contains? calls (quote my.turn/wait)) (pr-str calls)))))))))

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
          (first (filter #(= (quote sample.settlement-parity/contracted)
                             (:seon.fn/sym %))
                         rows))
          expected
          (select-keys indexed
                       [:seon.fn/calls :seon.fn/keywords
                        :seon.test/subject])]
      (test-support/with-database
        (fn [connection]
          (test-support/transacted!
                       connection
                       ;; The indexed row carries its file ref, so the emitted file rows
                       ;; are admitted first exactly as publication admits them.
                       (into (filterv :seon.fn.file/relative-path rows)
                        [{:seon.ns/name namespace-name
                         :seon.ns/source "(ns sample.settlement-parity)"}
                        (test-support/program-fn-row (db/db connection) (quote sample.settlement-parity/helper) "(defn helper [value] value)")
                        {:seon.agent/id "settlement-parity-agent"
                         :seon.agent/namespace
                         [:seon.ns/name namespace-name]}]))
          (test-support/transacted!
                       connection
                       (turn/open-tx
                        {:seon.turn/id "settlement-parity-run" :seon.turn/agent [:seon.agent/id "settlement-parity-agent"] :seon.turn/opened-tx "datomic.tx"}))

          (test-support/transacted!
                       connection
                       (turn/plan-tx
                        {:seon.turn/id "settlement-parity-run" :seon.db.process/id (second boot-process) :seon.turn/starting-ns [:seon.ns/name namespace-name] :seon.turn/sources [{:seon.cluster.eval/source source}]}))
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
                                [?entity :seon.fn/calls ?symbol]]
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
                                [?entity :seon.test/subject ?symbol]]
                              @connection entity)]
                    (merge
                     (when (seq calls)
                       {:seon.fn/calls
                        (set calls)})
                     (when (seq keywords)
                       {:seon.fn/keywords (set keywords)})
                     (when subject
                       {:seon.test/subject subject}))))
                program-facts
                (edge-facts :seon.fn/sym
                            (quote sample.settlement-parity/contracted))
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

(deftest requires-preserve-namespace-symbols-without-minting-targets
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            requires (db/q '[:find ?namespace ?required
                             :where [?namespace :seon.ns/requires ?required]] database)
            unresolved (db/q '[:find ?required
                               :where [_ :seon.ns/requires ?required]
                                      (not [?target :seon.ns/name ?required])] database)]
        (is (seq requires))
        (is (every? (comp symbol? second) requires))
        (is (seq unresolved) "External namespace names survive without manufactured target rows.")))))

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
                   [?function :seon.program/analyzed-source-digest]]
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
            (db/q '[:find ?function ?spec-tx ?arities-tx ?analysis-tx
                   :where
                   [?function :seon.fn/spec _ ?spec-tx]
                   [?function :seon.fn/arities _ ?arities-tx]
                   [?function :seon.program/analyzed-source-digest _ ?analysis-tx]]
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
                   (or-join [?schema-key ?arity ?role]
                     (and [?arity :seon.fn.arity/input-refs ?schema-key]
                          [(ground :input) ?role])
                     (and [?arity :seon.fn.arity/output-refs ?schema-key]
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
          (is (every? (fn [[_ spec-tx arities-tx analysis-tx]]
                        (= spec-tx arities-tx analysis-tx))
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
                          [?function :seon.program/analyzed-source-digest]]
                        @connection)))]
        (is (= 2 (count functions)))
        (test-support/transacted!
                     connection
                     (into []
                           (mapcat (fn [function]
                                     [[:db.fn/retractAttribute function :seon.fn/arities]]))
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
                        (not [?function :seon.program/analyzed-source-digest])
                        (and [?function :seon.fn/arities ?arity]
                             (not [?arity :seon.fn.arity/argument-count]))
                        (and [?function :seon.fn/arities ?arity]
                             (not [?arity :seon.fn.arity/return-schema])))]
                    @connection))))))))

(deftest publication-refuses-a-required-artifact-load-finding
  (let [root (fixture-root)]
    (write-source! root "audit/unresolved.clj"
                   "(ns audit.unresolved (:require [clojure.set :as sets]))\n(defn broken [] missing)\n(defn also-broken [] another-missing)\n")
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
                  (::seon.fn/findings (ex-data failure))))
      (is (= 2 (count (::seon.fn/findings (ex-data failure)))))
      (doseq [finding (::seon.fn/findings (ex-data failure))]
        (is (str/includes? (ex-message failure)
                           (str (::analyzer/filename finding) ":"
                                (::analyzer/row finding) ":"
                                (::analyzer/col finding))))
        (is (str/includes? (ex-message failure)
                           (::analyzer/message finding)))))))

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
        (is (some #{[:seon.fn/sym (quote my.web/fetch)]}
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
        artifacts (into {} (map (juxt :seon.fn.file/relative-path identity))
                        (:seon.fn.manifest/artifacts manifest))
        beta-artifact (get artifacts (relative-file beta))
        beta-caller
        (first (filter #(= (quote artifact.beta/caller) (:seon.fn/sym %))
                       (:seon.fn.file/rows beta-artifact)))
        incremental
        ;; The same file under the SAME roots: both seams ask one function
        ;; which root holds it, so the file row they mint — root fact
        ;; included — is identical.
        (seon.fn/build-artifact
         {:seon.fn/source-path (.getPath beta)
          :seon.fn/roots (:seon.fn/roots request)
          :seon.fn.file/first-party-functions
          [(quote artifact.alpha/target)]})
        file-root (fn [artifact]
                    (->> (:seon.fn.file/rows artifact)
                         (filter :seon.fn.file/relative-path)
                         first
                         :seon.fn.file/relative-root))]
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
      (is (= #{(relative-file alpha) (relative-file beta)}
             (set (keys artifacts))))
      (is (re-matches #"[0-9a-f]{64}"
                      (:seon.fn.manifest/digest manifest))))
    (testing "pure manifest helpers find files and derive function context"
      (is (= beta-artifact
             (seon.fn/artifact-by-path manifest (.getCanonicalPath beta))))
      (is (nil? (seon.fn/artifact-by-path manifest "/absent.clj")))
      (is (= [(quote artifact.alpha/target) (quote artifact.beta/caller)]
             (seon.fn/manifest-function-symbols manifest))))
    (testing "artifact replacement recomputes one deterministic manifest"
      (let [changed-beta (assoc beta-artifact :seon.fn.file/digest (id/digest 64 ["changed"]))
            changed (seon.fn/replace-manifest-artifacts manifest [changed-beta])]
        (is (= changed-beta
               (seon.fn/artifact-by-path changed (.getCanonicalPath beta))))
        (is (= (:seon.fn.manifest/relative-roots manifest)
               (:seon.fn.manifest/relative-roots changed)))
        (is (= (:seon.fn.manifest/identities manifest)
               (:seon.fn.manifest/identities changed)))
        (is (not= (:seon.fn.manifest/digest manifest)
                  (:seon.fn.manifest/digest changed)))
        (is (= (sort (map :seon.fn.file/relative-path
                          (:seon.fn.manifest/artifacts changed)))
               (map :seon.fn.file/relative-path
                    (:seon.fn.manifest/artifacts changed))))))
    (testing "one-file analysis records every call target (ruling 42b)"
      (is (= #{(quote clojure.core/str) (quote artifact.alpha/target) (quote clojure.string/trim)}
             (:seon.fn/calls beta-caller)))
      (is (= (.getPath root) (file-root beta-artifact)))
      (is (= (file-root beta-artifact) (file-root incremental))
          "the walk and the changed-path seam write the same root fact")
      (is (= beta-artifact incremental)))
    (testing "the file digest covers exact bytes, including CRLF"
      (is (re-matches #"[0-9a-f]{64}"
                      (:seon.fn.file/digest (get artifacts
                                                 (relative-file alpha)))))
      (is (= "(ns artifact.alpha)"
             (:seon.ns/source
              (first (filter :seon.ns/name
                             (:seon.fn.file/rows
                              (get artifacts (relative-file alpha)))))))))))

(deftest changed-file-planning-is-conservative-and-explicit
  (let [path "/repo/src/sample.clj"
        namespace-row {:seon.ns/name 'sample
                       :seon.ns/source "(ns sample)"}
        current-row {:seon.fn/sym (quote sample/value)
                     :seon.schema.admission/source :core
                     :seon.fn/ns [:seon.ns/name 'sample]
                     :seon.fn/source "(defn value [] 1)"
                     :seon.fn/arglists "([])"
                     :seon.fn/private? false}
        desired-row (assoc current-row :seon.fn/source "(defn value [] 2)")
        current {:seon.fn.file/relative-path path
                 :seon.fn.file/digest "old"
                 :seon.fn.file/rows [namespace-row current-row]
                 :seon.fn.file/identities
                 [[:seon.ns/name 'sample] [:seon.fn/sym (quote sample/value)]]}
        desired {:seon.fn.file/relative-path path
                 :seon.fn.file/digest "new"
                 :seon.fn.file/rows [namespace-row desired-row]
                 :seon.fn.file/identities
                 [[:seon.ns/name 'sample] [:seon.fn/sym (quote sample/value)]]}
        base-request {:seon.fn.change/status :modified
                      :seon.fn.change/current-artifact current
                      :seon.fn.change/desired-artifact desired}
        plan #(seon.fn/plan-file-change (merge base-request %))]
    (testing "same identities with cardinality-one updates are upserts"
      (is (= {:seon.fn.change/action :incremental-upsert
              :seon.fn.change/relative-path path
              :seon.fn.change/digest "new"
              :seon.fn.change/artifact desired
              :seon.fn.change/rows
              [desired-row]
              :seon.fn.change/identities
              [[:seon.ns/name 'sample] [:seon.fn/sym (quote sample/value)]]}
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
                                   #{(quote sample/other)})})))))
    (testing "any added identity rebuilds because old callers may resolve it"
      (let [added-row (assoc desired-row
                             :seon.fn/sym (quote sample/new-value)
                             :seon.fn/source "(defn new-value [] 3)")
            result
            (plan {:seon.fn.change/desired-artifact
                   (-> desired
                       (update :seon.fn.file/rows conj added-row)
                       (update :seon.fn.file/identities conj
                               [:seon.fn/sym (quote sample/new-value)]))})]
        (is (some #{:added-identity} (:seon.fn.change/reasons result)))
        (is (= [[:seon.fn/sym (quote sample/new-value)]]
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
                                            [:seon.fn/sym (quote prebuilt/value)]))
              test-row (db/pull database
                                [:seon.test/subject :seon.fn/calls]
                                [:seon.test/sym (quote prebuilt/value-test)])]
          (is (pos? (:seon.reconcile/operations result)))
          (is (= (inc (:max-tx before)) (:max-tx database))
              "the real writer admits the population in one transaction")
          (is (integer? function-id))
          (is (= 'prebuilt/value (:seon.test/subject test-row)))
          (is (contains? (set (:seon.fn/calls test-row)) 'prebuilt/value)))))
    (let [failure (test-support/refusal-data
                   #(#'seon.fn/require-committed!
                     {:seon.error/kind :seon.db/invalid-transaction}
                     :seon.fn/population))]
      (is (= :seon.db/invalid-transaction (:seon.error/kind failure)))
      (is (= :seon.fn/population (:seon.fn/index-phase failure))))))

(deftest publication-refusals-preserve-the-entity-key-and-value
  (test-support/with-database
   (fn [connection]
     (let [refusal (db/transact! connection
                                [{:seon.schedule/id "publication-refusal"
                                  :seon.schedule/expression "0 4 * * *"}])
           failure (try
                     (#'seon.fn/require-committed! refusal :seon.fn/population)
                     nil
                     (catch Exception failure failure))]
       (is (= :seon.db/invalid-write (:seon.error/kind (ex-data failure))))
       (is (= refusal (dissoc (ex-data failure)
                             :seon.fn/index-phase :seon.fn/index-refused)))
       (is (str/includes? (ex-message failure) "publication-refusal"))
       (is (str/includes? (ex-message failure) ":seon.schedule/zone-id"))
       (is (str/includes? (ex-message failure) ":seon.error/unknown"))))))

(deftest retracted-program-identities-leave-no-tombstones
  (test-support/with-database
   (fn [connection]
     (let [row (test-support/program-fn-row (db/db connection) (symbol "seon.fn" "retired-row-fixture") "(defn retired-row-fixture [] true)")
           entity-ref [:seon.fn/sym (:seon.fn/sym row)]]
       (test-support/transacted! connection [row])
       (let [before (db/db connection)]
         (is (= :seon.db/invalid-write
                (:seon.error/kind
                 (db/transact! connection
                               [[:db/retract entity-ref :seon.schema.admission/source :agent]]))))
         (is (= (:max-tx before) (:max-tx (db/db connection))))
         (is (= :seon.db/invalid-write
                (:seon.error/kind
                 (db/transact! connection [{:seon.fn/sym 'seon.fn/new-bare-row}]))))
         (is (= (:max-tx before) (:max-tx (db/db connection)))))
       (test-support/transacted!
        connection (seon.fn/reconcile-tx (db/db connection) [] [entity-ref]))
       (let [retired (db/pull (db/db connection) '[*] entity-ref)]
         (is (nil? retired)))))))

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
               (used [:seon.fn/sym (quote sample.keys/refuse)]))
            "::kw, ::alias/kw, and :fully/qualified resolve to one honest form"))
      (testing "unqualified keywords stay out; qualified ones are kept verbatim"
        (is (= #{:sample.keys/depth :sample.keys/keys}
               (used [:seon.fn/sym (quote sample.keys/destructured)]))
            (str ":keys and :plain never enter the index. The namespaced "
                 ":sample.keys/keys marker does, because it IS written "
                 "literally — the fact is source usage, not declaredness")))
      (testing "a keyword built at runtime is invisible to static analysis"
        (is (nil? (used [:seon.fn/sym (quote sample.keys/built)]))
            "the honest boundary: only literal keyword usage is a fact"))
      (testing "test rows carry their own keyword usage"
        (is (= #{:sample.keys/refused :seon.error/kind}
               (used [:seon.test/sym (quote sample.keys/refusal-test)]))
            "a test's keywords are the ones it reads, never its subject's"))
      (testing "the indexed facts answer the motivating query"
        (test-support/with-database
          (fn [connection]
            (test-support/transacted!
                         connection
                         ;; Declaration rows refer to their file row, so the file rows
                         ;; are admitted with them exactly as publication admits them.
                         (into (mapv #(dissoc % :seon.fn/keywords :seon.fn/calls)
                                     (filter #(or (:seon.fn.file/relative-path %) (:seon.ns/name %)
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
                                  [:seon.fn/sym (quote sample.keys/destructured)])))))
            (is (= [(quote sample.keys/refuse)]
                   (filterv #(str/starts-with? % "sample.keys/")
                            (seon.fn/functions-using @connection
                                                     :seon.error/kind)))
                "a test reading the keyword is never a function consumer")
            (is (= [] (seon.fn/functions-using @connection
                                               :sample.keys/never-written)))))))))

(deftest fixture-analysis-never-writes-the-checkouts-dependency-cache
  ;; The class: clj-kondo keys its dependency cache by NAMESPACE NAME, so a
  ;; fixture file declaring a first-party namespace used to overwrite that
  ;; namespace's entry with a stub, and every later analysis in the same
  ;; worker JVM refused the real vars as unresolved (batch 47's
  ;; adoption-rows red). The analyzer now decides by ownership: only the
  ;; checkout's own declared source reads or writes the checkout's cache.
  (let [root (fixture-root)
        entry (io/file ".clj-kondo/.cache/v1/clj/seon.error.transit.json")
        unresolved (fn [findings]
                     (filterv #(= :unresolved-var
                                  (:seon.fn.analyzer/type %))
                              findings))]
    ;; The real entry is written by real source, exactly as a build writes it.
    (analyzer/analyze {::analyzer/paths ["src/seon/error.clj"
                                         "src/seon/schema.clj"
                                         "src/seon/await.clj"]})
    (is (.isFile entry) "analyzing the checkout's own source populates the cache")
    (let [before (slurp entry)]
      (write-source! root "seon/error.clj" "(ns seon.error)\n(def message :m)\n")
      (analyzer/analyze {::analyzer/paths [(.getPath root)]})
      (is (= before (slurp entry))
          "a fixture namespace outside the declared source roots leaves the
           checkout's cache entry byte-identical")
      (is (empty? (unresolved (::analyzer/findings
                               (analyzer/analyze
                                {::analyzer/paths ["src/seon/await.clj"]}))))
          "a real file calling seon.error's vars still resolves them"))))

(deftest tests-reaching-follows-calls-and-explicit-subjects
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.reach]
            function-row
            (fn [function-symbol calls]
              (cond-> (test-support/program-fn-row (db/db connection) function-symbol (str "(defn " (name (symbol function-symbol)) " [] nil)"))
                (seq calls) (assoc :seon.fn/calls calls)))
            test-row
            (fn [test-symbol references]
              (merge (analyzed-test-row (db/db connection) test-symbol)
                     references))]
        (test-support/transacted!
                     connection
                     [{:seon.ns/name 'sample.reach
                       :seon.ns/source "(ns sample.reach)"}
                      (function-row (quote sample.reach/target) nil)
                      (function-row (quote sample.reach/bridge)
                                    #{(quote sample.reach/target)})
                      (function-row (quote sample.reach/direct)
                                    #{(quote sample.reach/target)})
                      (function-row (quote sample.reach/untested) nil)])
        (test-support/transacted!
                     connection
                     [(test-row (quote sample.reach/direct)
                                {:seon.fn/calls
                                 #{(quote sample.reach/target)}
                                 :seon.test/pass-count 0
                                 :seon.test/fail-count 1
                                 :seon.test/error-count 0})
                      (test-row (quote sample.reach/indirect)
                                {:seon.fn/calls
                                 #{(quote sample.reach/bridge)}
                                 :seon.test/pass-count 1
                                 :seon.test/fail-count 0
                                 :seon.test/error-count 0})
                      (test-row (quote sample.reach/property)
                                {:seon.test/subject
                                 (quote sample.reach/bridge)
                                 :seon.test/pass-count 1
                                 :seon.test/fail-count 0
                                 :seon.test/error-count 0})])
        (is (not= (:db/id (db/pull @connection [:db/id]
                                   [:seon.fn/sym (quote sample.reach/direct)]))
                  (:db/id (db/pull @connection [:db/id]
                                   [:seon.test/sym (quote sample.reach/direct)])))
            "function and test identities stay distinct at the same name")
        (is (= [(quote sample.reach/direct)
                (quote sample.reach/indirect)
                (quote sample.reach/property)]
               (seon.fn/tests-reaching @connection (quote sample.reach/target))))
        (is (nil? (:seon.fn/calls
                   (db/pull @connection [:seon.fn/calls]
                            [:seon.test/sym (quote sample.reach/property)])))
            "the schema-property test reaches its subject without a call edge")
        (is (= [(quote sample.reach/target)]
               (filterv #(= "sample.reach" (namespace %))
                        (seon.fn/currently-failing-functions @connection)))
            "red latest-result facts derive failing functions through calls")
        (is (= [(quote sample.reach/direct) (quote sample.reach/untested)]
               (filterv #(= "sample.reach" (namespace %))
                        (seon.fn/functions-without-tests @connection)))
            "absence is derived from the same test-reach graph")
        (is (= []
               (seon.fn/tests-reaching @connection (quote sample.reach/absent))))))))

(deftest contract-findings-ranks-each-incomplete-function-contract
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            function-row
            (fn [function-symbol source]
              (test-support/program-fn-row database function-symbol source))]
        (transact-fixture!
         connection
         [{:seon.ns/name 'sample.contract-findings
           :seon.ns/source "(ns sample.contract-findings)"}
          (function-row
           'sample.contract-findings/any-input
           "(defn any-input {:malli/schema [:=> [:cat :any] :int]} [x] 1)")
          (function-row
           'sample.contract-findings/bare-map-output
           "(defn bare-map-output {:malli/schema [:=> [:cat] :map]} [] {})")
          (function-row
           'sample.contract-findings/missing-spec
           "(defn missing-spec [] nil)")
          (function-row
           'sample.contract-findings/fully-declared
           "(defn fully-declared {:malli/schema [:=> [:cat :int] [:map [:sample.contract-findings/value :int]]]} [x] {:sample.contract-findings/value x})")
          (merge
           (function-row
            'sample.contract-findings/caller
            "(defn caller {:malli/schema [:=> [:cat] :int]} [] 1)")
           {:seon.fn/calls #{'sample.contract-findings/any-input}})])
        (let [findings
              (->> (seon.fn/contract-findings (db/db connection))
                   (filterv #(= "sample.contract-findings"
                                (namespace (:seon.fn/sym %)))))]
          (is (= [{:seon.fn/sym 'sample.contract-findings/any-input
                   :seon.fn.contract/position
                   [:seon.fn.contract.position/input 0]
                   :seon.fn.contract/form :any
                   :seon.fn.contract/finding
                   :seon.fn.contract.finding/any
                   :seon.fn.contract/caller-count 1}
                  {:seon.fn/sym 'sample.contract-findings/bare-map-output
                   :seon.fn.contract/position
                   :seon.fn.contract.position/output
                   :seon.fn.contract/form :map
                   :seon.fn.contract/finding
                   :seon.fn.contract.finding/bare-map
                   :seon.fn.contract/caller-count 0}
                  {:seon.fn/sym 'sample.contract-findings/missing-spec
                   :seon.fn.contract/position
                   :seon.fn.contract.position/contract
                   :seon.fn.contract/form :seon.fn.contract/missing
                   :seon.fn.contract/finding
                   :seon.fn.contract.finding/missing-spec
                   :seon.fn.contract/caller-count 0}]
                 findings))
          (is (not-any? #(= 'sample.contract-findings/fully-declared
                            (:seon.fn/sym %))
                        findings)))))))

(deftest gate-set-walks-only-indexed-callers-once
  (test-support/with-database
    (fn [connection]
      (transact-fixture!
       connection
       (into [{:seon.ns/name 'sample.gates :seon.ns/source "(ns sample.gates)"}]
             (map (fn [s]
                    (test-support/program-fn-row (db/db connection) s (str "(defn " (name (symbol s)) " [] nil)"))))
             [(quote sample.gates/a) (quote sample.gates/b) (quote sample.gates/unrelated)]))
      (transact-fixture!
       connection
       [[:db/add [:seon.fn/sym (quote sample.gates/a)] :seon.fn/calls
         (quote sample.gates/b)]
        [:db/add [:seon.fn/sym (quote sample.gates/b)] :seon.fn/calls
         (quote sample.gates/a)]
        (merge (analyzed-test-row (db/db connection) (quote sample.gates/direct)) {:seon.fn/calls #{(quote sample.gates/a)}})
        (merge (analyzed-test-row (db/db connection) (quote sample.gates/subject)) {:seon.test/subject (quote sample.gates/b)})
        (merge (analyzed-test-row (db/db connection) (quote sample.gates/pending)) {:seon.test/subject (quote sample.gates/a)})
        (merge (analyzed-test-row (db/db connection) (quote sample.gates/future)) {:seon.test/subject (quote sample.gates/new)})])
      (let [database (db/db connection)
            queries (atom [])
            scan-counts (atom [])
            scans (atom [])
            query db/q
            datoms db/datoms
            thread (Thread/currentThread)
            symbols [(quote sample.gates/a) (quote sample.gates/b) (quote sample.gates/new)
                     (quote sample.gates/unrelated) (quote sample.gates/absent)]
            results
            (with-redefs
              [db/q (fn [& arguments]
                      (when (identical? thread (Thread/currentThread))
                        (swap! queries conj (first arguments)))
                      (apply query arguments))
               db/datoms (fn [& arguments]
                           (when (identical? thread (Thread/currentThread))
                             (swap! scans conj (vec (rest arguments))))
                           (apply datoms arguments))]
              (mapv (fn [s]
                      (reset! scans [])
                      (let [result (seon.fn/gate-set database s)]
                        (swap! scan-counts conj (count @scans))
                        (is (= (count @scans) (count (distinct @scans)))
                            "a cycle never repeats an incoming-edge lookup")
                        (is (every? (fn [[index attribute target]]
                                      (and (= :avet index)
                                           (#{:seon.fn/calls :seon.fn/references :seon.test/subject} attribute)
                                           (qualified-symbol? target)))
                                    @scans)
                            "reverse reads use the declared symbol indexes")
                        result))
                    symbols))]
        (is (= [[(quote sample.gates/direct) (quote sample.gates/pending) (quote sample.gates/subject)]
                [(quote sample.gates/direct) (quote sample.gates/pending) (quote sample.gates/subject)]
                [(quote sample.gates/future)] [] []]
               results))
        (is (every? pos? @scan-counts)
            "even an absent definition can have surviving value referrers")
        (is (<= (count @queries) (* 5 (count symbols)))
            "each identity uses at most five nonrecursive selection queries")
        (is (= (count symbols) (count (filter (fn [query] (some #{'%} query)) @queries)))
            "each operation acquires its declared-reference relation once")
        (doseq [seeds [#{(symbol "sample.gates" "a")}
                        #{(symbol "sample.gates" "a") (symbol "sample.gates" "b")}
                        #{(symbol "sample.gates" "a") (symbol "sample.gates" "b")
                          (symbol "sample.gates" "new")}]]
          (let [started (System/nanoTime)
                union
                (with-redefs
                  [db/datoms (fn [& arguments]
                               (when (identical? thread (Thread/currentThread))
                                 (swap! scans conj (vec (rest arguments))))
                               (apply datoms arguments))]
                  (reset! scans [])
                  (seon.fn/gate-sets {:seon.db/db database :seon.fn/seeds seeds}))]
            (is (= (vec (sort (cond-> [(symbol "sample.gates" "direct")
                                      (symbol "sample.gates" "pending")
                                      (symbol "sample.gates" "subject")]
                               (seeds (symbol "sample.gates" "new"))
                               (conj (symbol "sample.gates" "future"))))) union))
            (is (= (count @scans) (count (distinct @scans)))
                "overlapping seeds share one visited set, including through a cycle")
            (println "gate-sets union seeds=" (count seeds)
                     "indexed-reads=" (count @scans)
                     "elapsed-ms=" (/ (- (System/nanoTime) started) 1000000.0))))
        (transact-fixture!
         connection
         [[:db/retract [:seon.fn/sym (quote sample.gates/b)] :seon.fn/calls
           (quote sample.gates/a)]])
        (is (= [(quote sample.gates/direct) (quote sample.gates/pending)]
               (seon.fn/gate-set (db/db connection) (quote sample.gates/a)))
            "edge removal is visible without updating any retained closure")
        (is (= (first results) (seon.fn/gate-set database (quote sample.gates/a)))
            "the old immutable value keeps its own reach")))))

(deftest a-refused-reference-read-refuses-gate-set-derivation
  (test-support/with-database
    (fn [connection]
      (transact-fixture!
       connection
       (into [{:seon.ns/name 'sample.shape :seon.ns/source "(ns sample.shape)"}]
             (map (fn [s]
                    (test-support/program-fn-row (db/db connection) s (str "(defn " (name (symbol s)) " [] nil)"))))
             [(quote sample.shape/none) (quote sample.shape/one) (quote sample.shape/many)]))
      (transact-fixture!
       connection
       [(merge (analyzed-test-row (db/db connection) (quote sample.shape/only-test)) {:seon.fn/calls #{(quote sample.shape/one)}})
        (merge (analyzed-test-row (db/db connection) (quote sample.shape/edge-test)) {:seon.fn/calls #{(quote sample.shape/many)}})
        (merge (analyzed-test-row (db/db connection) (quote sample.shape/subject-test)) {:seon.test/subject (quote sample.shape/many)})
        (merge (analyzed-test-row (db/db connection) (quote sample.shape/pending-test)) {:seon.test/subject (quote sample.shape/many)})])
      (let [database (db/db connection)
            test-sym? (schema/call-with-projection
                       (db/carried-projection database)
                       #(schema/candidate-validator :seon.test/sym))
            gate-sets (into {}
                            (map (juxt identity #(seon.fn/gate-set database %)))
                            [(quote sample.shape/none) (quote sample.shape/one)
                             (quote sample.shape/many)])]
        (is (= {(quote sample.shape/none) []
                (quote sample.shape/one) [(quote sample.shape/only-test)]
                (quote sample.shape/many) [(quote sample.shape/edge-test)
                                     (quote sample.shape/pending-test)
                                     (quote sample.shape/subject-test)]}
               gate-sets)
            "none, one and many reaching tests")
        (doseq [[function-symbol gate] gate-sets]
          (is (vector? gate)
              (str function-symbol " gates a vector"))
          (is (every? test-sym? gate)
              (str function-symbol " gates only values the declared "
                   ":seon.test/sym element schema accepts: "
                   (pr-str (vec (remove test-sym? gate)))))
          (is (= gate (vec (sort gate)))
              (str function-symbol " gates a sorted vector")))
        ;; The class: a flat database refusal concatenated into the selection
        ;; splices its map entries, and each entry reads as a two-element
        ;; vector the declared element schema refuses.
        (let [query db/q
              thread (Thread/currentThread)
              ;; A degrading cluster hands a read this flat value; the dev
              ;; dial throws the same diagnostic, so the selection must never
              ;; concatenate it either way.
              refusal (dissoc (error/diagnostic
                       {:seon.db/invalid-read true
                        :seon.error/kind :seon.db/invalid-read
                        :seon.error/message
                        "seon.db/q cannot read uninstalled attribute :sample.shape/uninstalled."
                        :seon.error/diagnostic-layer :database-read
                        :seon.error/diagnostic-operation 'seon.db/q
                        :seon.error/diagnostic-member :sample.shape/uninstalled
                        :seon.error/diagnostic-expected :seon.db/installed-attribute
                        :seon.error/diagnostic-offending :sample.shape/uninstalled
                        :seon.error/diagnostic-cause :seon.db/uninstalled-attribute
                        :seon.error/diagnostic-evidence
                        {:seon.fn/sym (quote sample.shape/many)}}) :seon.error/kind)
              refused (with-redefs
                        [db/q (fn [& arguments]
                                (if (and (identical? thread (Thread/currentThread))
                                         (= @#'seon.fn/declared-reference-rules (last arguments)))
                                  refusal
                                  (apply query arguments)))]
                        [(seon.fn/tests-reaching database (symbol "sample.shape" "many"))
                         (seon.fn/gate-set database (symbol "sample.shape" "many"))
                         (seon.fn/gate-sets database [(symbol "sample.shape" "many")])
                         (seon.fn/gate-sets {:seon.db/db database
                                             :seon.fn/seeds #{(symbol "sample.shape" "many")}})])]

          (is (true? (:seon.db/invalid-read refusal))
              "the injected read is a flat database refusal")
          (is (= [refusal refusal refusal refusal] refused)
              "a refused declared-reference read refuses every selection arity")
          (is (every? map? refused)
              "a refusal never becomes a shortened gate set"))))))

(deftest output-path-report-finds-the-shortest-bypass
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.output]
            function-row
            (fn [function-symbol facts]
              (merge (test-support/program-fn-row (db/db connection) function-symbol (str "(defn " (name (symbol function-symbol)) " [] nil)"))
                     facts))]
        (transact-fixture!
         connection
         [{:seon.ns/name 'sample.output
           :seon.ns/source "(ns sample.output)"}
          (function-row (quote sample.output/sink)
                        {:seon.fn/external-sink :ai-visible-text
                         :seon.fn/projection-boundary :none})
          (function-row (quote sample.output/unresolved-sink)
                        {:seon.fn/external-sink :ai-visible-text})
          (function-row (quote sample.output/codec-sink)
                        {:seon.fn/external-sink :codec-storage
                         :seon.fn/projection-boundary :none})
          (function-row (quote sample.output/ai-projector)
                        {:seon.fn/projection-boundary :seon.render/ai
                         :seon.fn/calls #{(quote sample.output/sink)}})
          (function-row (quote sample.output/raw-text)
                        {:seon.fn/projection-boundary :none
                         :seon.fn/calls #{(quote sample.output/sink)}})
          (function-row (quote sample.output/projected-root)
                        {:seon.fn/calls #{(quote sample.output/ai-projector)}})
          (function-row (quote sample.output/bypass-root)
                        {:seon.fn/calls #{(quote sample.output/raw-text)}})
          (function-row (quote sample.output/unresolved-root)
                        {:seon.fn/calls #{(quote sample.output/unresolved-sink)}})
          (function-row (quote sample.output/codec-root)
                        {:seon.fn/calls #{(quote sample.output/codec-sink)}})])
        (let [paths
              (->> (:seon.fn.output/paths
                    (seon.fn/output-path-report @connection))
                   (filter #(= "sample.output" (namespace (:seon.fn.output/source %)))))
              by-source
              (into {}
                    (map (juxt :seon.fn.output/source identity))
                    paths)]
          (is (= {:seon.fn.output/classification :projected
                  :seon.fn.output/path
                  [(quote sample.output/projected-root)
                   (quote sample.output/ai-projector)
                   (quote sample.output/sink)]}
                 (select-keys
                  (get by-source (quote sample.output/projected-root))
                  [:seon.fn.output/classification :seon.fn.output/path])))
          (is (= {:seon.fn.output/classification :bypass
                  :seon.fn.output/first-bypass (quote sample.output/raw-text)
                  :seon.fn.output/path
                  [(quote sample.output/bypass-root)
                   (quote sample.output/raw-text)
                   (quote sample.output/sink)]}
                 (select-keys
                  (get by-source (quote sample.output/bypass-root))
                  [:seon.fn.output/classification
                   :seon.fn.output/first-bypass
                   :seon.fn.output/path])))
          (is (= :unresolved
                 (:seon.fn.output/classification
                  (get by-source (quote sample.output/unresolved-root)))))
          (is (= :codec
                 (:seon.fn.output/classification
                  (get by-source (quote sample.output/codec-root))))))))))

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
                            [:seon.fn/sym (quote valid.core/value)]))))))))

(deftest indexing-refuses-an-already-populated-branch
  (test-support/with-database
    (fn [connection]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"fresh source scratch"
           (seon.fn/index! {:seon.db/connection connection
                           :seon.db/process boot-process
                           :seon.source/database @connection}))))))

(deftest ^{:seon.test/long
           "One complete canonical program population into a fresh memory store verifies transaction-local shape sharing."
           :seon.test/long-ms 180000}
  fresh-population-flattens-with-its-supplied-declarations
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :commit-graph? false
                       :keep-history? true
                       :schema-flexibility :write}
        projection (schema/declaration-projection (schema.edn/packaged-forms))
        manifest @test-support/source-manifest
        caller (Thread/currentThread)
        compiled (atom nil)
        compile-population @#'seon.fn/compile-index-transaction]
    (d/create-database configuration)
    (try
      (with-open [held (test-support/closeable (d/connect configuration) d/release)]
        (let [connection @held]
          (schema/call-with-projection
           projection
           (fn []
             ;; This is the complete-publication seam: physical attributes and
             ;; process facts exist, while canonical schema rows arrive with
             ;; the program transaction they describe.
             (#'cluster/accrete-schema-population! connection nil false)
             (is (empty? (db/q '[:find ?key :where [_ :seon.schema/key ?key]]
                                  (db/db connection))))
             (is (= :db.type/ref
                    (get-in @connection [:schema :seon.fn/arities :db/valueType])))
             (with-redefs-fn
               {#'seon.fn/compile-index-transaction
                (fn [supplied rows identities]
                  (let [result (compile-population supplied rows identities)]
                    (when (identical? caller (Thread/currentThread))
                      (reset! compiled result))
                    result))}
               #(let [result (seon.fn/index!
                              {:seon.db/connection connection
                               :seon.db/process boot-process
                               :seon.fn/manifest manifest})]
                  (is (pos? (:seon.reconcile/operations result)) (pr-str result))))
             (let [entities (:seon.fn/index-entities @compiled)
                   shape-ids (into #{}
                                   (keep (fn [[_ eid attribute]]
                                           (when (= :seon.schema.shape/fingerprint attribute)
                                             eid)))
                                   (:seon.fn/index-identity-operations @compiled))
                   shape-rows (filter #(shape-ids (:db/id %)) entities)
                   nested-shapes
                   (for [entity entities
                         value (rest (tree-seq coll? seq entity))
                         :when (and (map? value) (:seon.schema.shape/fingerprint value))]
                     value)
                   database (db/db connection)
                   component-attributes
                   (into []
                         (keep (fn [[attribute declaration]]
                                 (when (:db/isComponent declaration) attribute)))
                         (:schema database))
                   fingerprints (db/q '[:find ?fingerprint
                                        :where [_ :seon.schema.shape/fingerprint ?fingerprint]]
                                      database)]
               (is (seq shape-ids) "the subject includes real shared contract shapes")
               (is (empty? nested-shapes)
                   "identified shapes are emitted once, never repeated inside declarations")
               (is (= (count shape-ids) (count shape-rows) (count fingerprints)))
               (is (seq (db/q '[:find ?shape
                                :where [?a :seon.fn.arity/input-schema ?shape]
                                       [?b :seon.fn.arity/input-schema ?shape]
                                       [(not= ?a ?b)]] database))
                   "distinct arities retain their shared shape reference")
               (is (seq component-attributes)
                   "the installed schema declares the component relationships checked")
               (is (empty?
                    (db/q '[:find ?child ?first ?second
                            :in $ [?name ...]
                            :where [?first ?name ?child]
                                   [?second ?name ?child]
                                   [(not= ?first ?second)]] database component-attributes))
                   "flattening preserves exclusive ownership of component children"))))))
      (finally
        (d/delete-database configuration)))))

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
                  [?caller :seon.fn/calls ?callee-symbol]
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
                          [?caller :seon.fn/calls ?callee-symbol]
                          [(namespace ?callee-symbol) ?callee-namespace]
                          [(= ?callee-namespace "sci.core")]
                          [(name ?callee-symbol) ?callee-name]
                          [(clojure.string/starts-with? ?callee-name "eval")]
                          [?caller :seon.fn/sym ?caller-symbol]
                          [?caller :seon.fn/ns ?namespace]]
                        database)
            production (into #{}
                             (keep (fn [[caller-symbol _ namespace-eid]]
                                     (when-not (contains? tests namespace-eid)
                                       caller-symbol)))
                             edges)
            owning (into #{}
                         (map namespace)
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
            evaluate (production-callers database (quote seon.sci.eval/evaluate-for-install))
            sources (production-callers database
                                        (quote seon.turn/evaluate-sources))
            previews (production-callers database
                                         (quote seon.turn/preview-sources))]
        (testing "the turn's edge to the evaluator is visible to the graph"
          (is (contains? evaluate (quote seon.turn/evaluate-sources))
              (pr-str evaluate)))
        (testing "no second function evaluates agent source"
          (is (= #{(quote seon.turn/resume-turn)
                   (quote seon.turn/system-turn)
                   (quote seon.turn/preview-sources)}
                 sources)
              (pr-str sources)))
        (testing "the page and system turn reuse the same evaluation path"
          (is (= #{(quote seon.render.web/render-source-call) (quote seon.turn/system-turn)} previews)
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
                                    [:seon.test/sym (quote sample.observation/observed)]))))
            (is (nil? (:seon.test/fixture-observation
                        (db/pull @connection [:seon.test/fixture-observation]
                                 [:seon.test/sym (quote sample.observation/ordinary)])))))
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
                                               [:seon.test/sym (symbol (str namespace-name) name)])))))))))))
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
            (is (= [:seon.fn.file/relative-path (relative-file file)] (:seon.fn/file row)))
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
              (is (= (relative-file file)
                     (get-in (db/pull (:db-after report)
                                      [{:seon.fn/file [:seon.fn.file/relative-path]}]
                                      (program/row-identity row))
                             [:seon.fn/file :seon.fn.file/relative-path]))))))))))

(def ^:private reconcile-in
  "`seon.fn/reconcile-tx` with the operation's derived row shapes in hand.

  The public arity resolves the authored resources and derives their shapes,
  exactly as `index!` does for a caller that supplied no population; this is
  the same seam `index!` itself calls with the shapes it derived once."
  @#'seon.fn/reconcile-tx-in)

(deftest refused-index-reads-never-become-publication-rows
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           refusal
           (error/diagnostic
            {:seon.error/at (java.util.Date.)
             :seon.error/layer :seon.db/reading
             :seon.error/operation 'seon.db/q
             :seon.error/message "The program index read was refused."
             :seon.error/diagnostic-layer :database-read
             :seon.error/diagnostic-operation 'seon.db/q
             :seon.error/diagnostic-member :seon.fn/sym
             :seon.error/diagnostic-expected :seon.db/readable-database
             :seon.error/diagnostic-offending :seon.fn/sym
             :seon.error/diagnostic-cause :seon.db/invalid-read
             :seon.error/diagnostic-evidence
             {:seon.fn/index-phase :seon.fn/published-rows}})
           row (test-support/program-fn-row
                database 'sample.refused-index/read
                "(defn read [] true)")
           shapes (program/shapes-in (schema.edn/packaged-forms))
           query db/q
           pull db/pull
           transact! db/transact!
           submitted (atom [])
           index-result
           (with-redefs [db/q (fn [& arguments]
                                (if (identical? database (second arguments))
                                  refusal
                                  (apply query arguments)))
                         db/transact! (fn [& arguments]
                                        (swap! submitted conj (last arguments))
                                        (apply transact! arguments))]
             (seon.fn/index!
              {:seon.db/connection connection
               :seon.source/database database
               :seon.source/previous-database database}))
           reconcile-result
           (with-redefs [db/pull (fn [& arguments]
                                   (if (identical? database (first arguments))
                                     refusal
                                     (apply pull arguments)))]
             (reconcile-in shapes database [row] []))]
       (is (= refusal index-result)
           "the published-row query refusal is the publication result")
       (is (= refusal reconcile-result)
           "the exact-replacement read refusal is the reconciliation result")
       (is (empty? @submitted)
           "a refused publication read submits no transaction data")
       (doseq [result [index-result reconcile-result]]
         (is (and (map? result)
                  (contains? result :seon.error/at)
                  (contains? result :seon.error/layer)
                  (contains? result :seon.error/operation)))
         (is (not (vector? result))
             "error keys are never presented as a vector of program rows"))))))

(deftest incremental-tempid-rewrite-follows-the-installed-attribute-type
  (let [target-namespace 'sample.attribute-aware.target
        owner-namespace 'sample.attribute-aware.owner
        identity-pair [:seon.ns/name target-namespace]]
    (test-support/with-database
     {:seon.test-support/extra-schema
      [{:db/ident ::identity-value
        :db/valueType :db.type/tuple
        :db/tupleTypes [:db.type/keyword :db.type/symbol]
        :db/cardinality :db.cardinality/one
        :seon.schema/key ::identity-value
        :seon.schema/form "[:tuple :keyword :symbol]"
        :seon.schema.admission/source :agent}
       {:db/ident ::identity-ref
        :db/valueType :db.type/ref
        :db/cardinality :db.cardinality/one
        :seon.schema/key ::identity-ref
        :seon.schema/form ":seon.db/ref"
        :seon.schema.admission/source :agent}]}
     (fn [connection]
       (let [database (db/carry-derived-projection @connection)
             projection (db/carried-projection database)
             forms (-> (:seon.schema.projection/forms projection)
                       (update :seon.ns/ns conj
                               [::identity-value {:optional true}
                                ::identity-value]
                               [::identity-ref {:optional true}
                                ::identity-ref]))
             rows [{:seon.ns/name target-namespace}
                   {:seon.ns/name owner-namespace
                    ::identity-value identity-pair
                    ::identity-ref identity-pair}]
             tx-data (reconcile-in (program/shapes-in forms)
                                   database rows [])
             target-tempid
             (some (fn [[operation tempid attribute value]]
                     (when (and (= :db/add operation)
                                (= :seon.ns/name attribute)
                                (= target-namespace value))
                       tempid))
                   tx-data)
             owner-tempid
             (some (fn [[operation tempid attribute value]]
                     (when (and (= :db/add operation)
                                (= :seon.ns/name attribute)
                                (= owner-namespace value))
                       tempid))
                   tx-data)
             owner-entity
             (some #(when (and (map? %) (= owner-tempid (:db/id %))) %)
                   tx-data)]
         (is (string? target-tempid) "the target has a transaction tempid")
         (is (= identity-pair (::identity-value owner-entity))
             "the tuple value remains the exact identity-shaped value")
         (is (= target-tempid (::identity-ref owner-entity))
             "the genuine ref is rewritten to the target's transaction tempid")
         (let [report (test-support/transacted! connection tx-data)
               stored
               (db/pull (:db-after report)
                        [::identity-value {::identity-ref [:seon.ns/name]}]
                        [:seon.ns/name owner-namespace])]
           (is (= identity-pair (::identity-value stored)))
           (is (= target-namespace
                  (get-in stored [::identity-ref :seon.ns/name])))))))))

(deftest an-attribute-declared-after-this-jvm-started-is-indexed-without-a-restart
  ;; THE CLASS, at the indexer. `seon.program/shapes` cached the declarations
  ;; in a process-level defonce, so an attribute declared AFTER this JVM
  ;; started was stripped from every row `seon.fn/artifact` built until a
  ;; restart: the runner lane declared :seon.test/long-ms, the analyzer lifted
  ;; it, and canonical-row dropped it (issue
  ;; program-shapes-cache-strips-attributes-declared-after-the-jvm-started).
  ;; Ownership now follows the population the operation is HANDED. The
  ;; synthetic attribute is installed through the fixture's extra schema, so
  ;; the write below is the real admission path, not a bypass.
  (let [root (fixture-root)]
    (try
      (let [file (write-source! root "sample/late.clj"
                                "(ns sample.late)\n(defn chosen [x] x)\n")
            path (.getCanonicalPath file)]
        (test-support/with-database
         {:seon.test-support/extra-schema
          [{:db/ident ::declared-after
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}]}
         (fn [connection]
           (let [packaged (schema.edn/packaged-forms)
                 declared (update packaged :seon.fn.file/file conj
                                  [::declared-after {:optional true} :string])
                 request {:seon.fn/source-path path
                          :seon.fn.file/first-party-functions []
                          :seon.fn/roots [(.getPath root)]}
                 undeclared-root
                 (update packaged :seon.fn.file/file
                         (fn [definition]
                           (into []
                                 (remove #(and (vector? %)
                                               (= :seon.fn.file/relative-root (first %))))
                                 definition)))
                 rows (:seon.fn.file/rows (seon.fn/build-artifact request))
                 file-row (first (filter :seon.fn.file/relative-path rows))]
             (testing "the population in hand decides what the artifact owns"
               (is (some? file-row) "the file genuinely indexed")
               (is (= (.getPath root) (:seon.fn.file/relative-root file-row))
                   "the authored declarations keep the emitted source root")
               (is (not-any?
                    :seon.fn.file/relative-root
                    (:seon.fn.file/rows
                     (seon.fn/build-artifact
                      (assoc request :seon.schema.projection/forms
                             undeclared-root))))
                   "a supplied population that stops declaring it drops it, so
                    nothing here answers from a process-lifetime cache"))
             (testing "a declaration added after this JVM started is kept"
               (let [emitted (assoc file-row ::declared-after "carried")]
                 (is (nil? (::declared-after
                            (program/canonical-row
                             (program/shapes-in packaged) emitted)))
                     "an undeclared attribute is not a program row attribute")
                 (is (= "carried" (::declared-after
                                   (program/canonical-row
                                    (program/shapes-in declared) emitted)))
                     "declaring it on :seon.fn.file/file is the whole
                      requirement — no restart, no second list")
                 (testing "and the next index of the same file writes it"
                   (let [report (db/transact!
                                 connection
                                 (reconcile-in
                                  (program/shapes-in declared)
                                  (db/db connection)
                                  [(program/canonical-row
                                    (program/shapes-in declared) emitted)]
                                  []))]
                     (is (:db-after report)
                         (pr-str (select-keys report [:seon.error/kind
                                                      :seon.error/message])))
                     (is (= "carried"
                            (::declared-after
                             (db/pull (:db-after report) '[*]
                                      [:seon.fn.file/relative-path (relative-file path)])))
                         "the attribute reaches the database through the
                          ordinary admission path")))))))))
      (finally (test-support/delete-recursively! root)))))

(deftest static-findings-are-replaced-with-their-program-rows
  (with-provenance-file
    "sample/lint.clj" "(ns sample.lint)\n(defn chosen [unused] 1)\n"
    (fn [connection file rows]
      (let [findings (filter :seon.lint/id rows)
            finding (first findings)]
        (is (= 1 (count findings)))
        (is (= :unused-binding (:seon.lint/type finding)))
        (is (= [:seon.fn/sym (quote sample.lint/chosen)] (:seon.lint/fn finding)))
        (let [report (db/transact! connection (seon.fn/reconcile-tx (db/db connection) rows []))]
          (is (:db-after report) (pr-str (select-keys report [:seon.error/kind :seon.error/message])))
          (when (:db-after report)
            (is (= {:seon.lint/fn {:seon.fn/sym (quote sample.lint/chosen)}}
                   (db/pull (:db-after report) [{:seon.lint/fn [:seon.fn/sym]}]
                            [:seon.lint/id (:seon.lint/id finding)])))
            (spit file "(ns sample.lint)\n(defn chosen [used] used)\n")
            (let [artifact (seon.fn/build-artifact
                            {:seon.fn/source-path (.getCanonicalPath file)
                             :seon.fn.file/first-party-functions [(quote sample.lint/chosen)]})
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
                                    [?f :seon.fn.file/relative-path ?file]]
                                  (:db-after corrected-report) (relative-file file))))
                (is (nil? (db/pull (:db-after corrected-report)
                                [:seon.lint/id :seon.lint/type :seon.lint/fn]
                                [:seon.lint/id (:seon.lint/id finding)]))
                    "obsolete findings retract completely")))))))))

;;; --------------------------------------------------------------------------
;;; Analysis facets — :seon.fn/writes and :seon.fn/call-arities
;;; --------------------------------------------------------------------------

(def ^:private writer-fixture-source
  (str "(ns sample.facets\n"
       "  (:require [seon.db :as db]))\n"
       "\n"
       "(defn helper [value] value)\n"
       "\n"
       "(defn record-agent!\n"
       "  [connection id]\n"
       "  (db/transact! connection [{:seon.agent/id id\n"
       "                             :sample.facets/undeclared true\n"
       "                             :seon.ns/name (helper 'sample.facets)}]))\n"
       "\n"
       "(defn reads-only\n"
       "  [database]\n"
       "  (db/q '[:find ?e :where [?e :seon.agent/id _]] database))\n"))

(defn- facet-rows
  ([] (facet-rows writer-fixture-source))
  ([source]
   (let [root (fixture-root)]
     (write-source! root "sample/facets.clj" source)
     (into {}
           (keep (fn [row]
                   (when-let [function-symbol (:seon.fn/sym row)]
                     [function-symbol row])))
           (seon.fn/rows {:seon.fn/roots [(.getPath root)]})))))

(deftest writes-facet-names-every-attribute-a-declaration-transacts
  (let [rows (facet-rows)]
    (testing "the attributes inside a transact! call's own span, as keyword values"
      (is (= #{:seon.agent/id :seon.ns/name}
             (:seon.fn/writes (get rows (quote sample.facets/record-agent!))))))
    (testing "an undeclared keyword has no :seon.schema/key row to name"
      (is (not (contains? (:seon.fn/writes (get rows (quote sample.facets/record-agent!)))
                          :sample.facets/undeclared))))
    (testing "reading an attribute is not writing it"
      (is (nil? (:seon.fn/writes (get rows (quote sample.facets/reads-only))))
          "the read names :seon.agent/id but transacts nothing")
      (is (contains? (:seon.fn/keywords (get rows (quote sample.facets/reads-only)))
                     :seon.agent/id)
          "the conflating keyword facet still carries it"))
    (testing "a declaration that never reaches the write seam carries no set"
      (is (nil? (:seon.fn/writes (get rows (quote sample.facets/helper))))))))

(deftest call-arities-refine-exactly-the-stored-call-edges
  (let [rows (facet-rows)
        writer (get rows (quote sample.facets/record-agent!))
        reader (get rows (quote sample.facets/reads-only))]
    (is (= #{[(quote seon.db/transact!) 2] [(quote sample.facets/helper) 1]}
           (:seon.fn/call-arities writer)))
    (is (= #{[(quote seon.db/q) 2]} (:seon.fn/call-arities reader)))
    (testing "every refined callee is a target the reach index already carries"
      (doseq [row (vals rows)]
        (is (= (into #{} (map first) (:seon.fn/call-arities row))
               (set (:seon.fn/calls row)))
            (str (:seon.fn/sym row)
                 " refines exactly its :seon.fn/calls targets"))))
    (is (nil? (:seon.fn/call-arities (get rows (quote sample.facets/helper))))
        "a declaration with no call edge carries no tuple")))

(deftest static-index-and-runtime-admission-agree-on-analysis-facets
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.facets]
            static (get (facet-rows) (quote sample.facets/record-agent!))
            definition
            (str "(defn record-agent!\n"
                 "  [connection id]\n"
                 "  (seon.db/transact! connection"
                 " [{:seon.agent/id id\n"
                 "     :sample.facets/undeclared true\n"
                 "     :seon.ns/name (helper 'sample.facets)}]))")
            program-row (test-support/program-fn-row (db/db connection) (quote sample.facets/record-agent!) definition)]
        (transact-fixture!
         connection
         [{:seon.ns/name 'sample.facets}
          {:seon.ns/name 'seon.db}])
        (transact-fixture!
         connection
         [{:seon.fn/sym (quote seon.db/transact!)
           :seon.fn/ns [:seon.ns/name 'seon.db]
           :seon.fn/arglists "([request])"
           :seon.fn/private? false
           :seon.schema.admission/source :core}
          (test-support/program-fn-row (db/db connection) (quote sample.facets/helper) "(defn helper [value] value)")])
        (let [[_ admitted]
              (first (seon.fn/analyze-forms
                      (db/db connection)
                      [{:seon.cluster.eval/source definition
                        :seon.cluster.eval/ns namespace-ref
                        :seon.program/row program-row}]))]
          (is (seq (:seon.fn/writes static))
              "the fixture must actually write, or parity is vacuous")
          (is (= (:seon.fn/writes static) (:seon.fn/writes admitted))
              "one derivation, two admission paths")
          (is (= (:seon.fn/call-arities static)
                 (:seon.fn/call-arities admitted))))))))

(deftest prepared-source-counts-are-admitted-and-real-mismatches-refuse
  (test-support/with-database
   (fn [connection]
     (let [caller 'sample.prepared/inbox
           source "(defn inbox [] (my.message/inbox))"
           row (assoc (test-support/program-fn-row (db/db connection) caller source)
                      :seon.schema.admission/source :agent
                      :seon.fn/source source
                      :seon.fn/arglists "([])"
                      :seon.fn/private? false)
           _ (test-support/transacted! connection [{:seon.ns/name 'sample.prepared}])
           analyzed (seon.fn/analyze-forms
                     (db/db connection)
                     [{:seon.cluster.eval/source source
                       :seon.cluster.eval/ns [:seon.ns/name 'sample.prepared]
                       :seon.program/row row}])
           admitted (second (first analyzed))]
       (is (= #{['my.message/inbox 0]} (:seon.fn/call-arities admitted)))
       (test-support/transacted! connection [admitted])
       (is (empty? (:seon.fn/arity-mismatches (seon.fn/arity-mismatches (db/db connection)))))
       (let [basis (:max-tx @connection)
             refusal (db/transact! connection
                                   [[:db/add [:seon.fn/sym caller]
                                     :seon.fn/call-arities ['my.message/inbox 2]]])]
         (is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))
         (is (= [{:seon.fn/caller caller
                  :seon.fn/callee 'my.message/inbox
                  :seon.fn/call-arity 2
                  :seon.fn/declared-arities [{:seon.fn.arity/min 1 :seon.fn.arity/max 1}]
                  :seon.fn/prepared-arities [{:seon.fn.arity/min 0 :seon.fn.arity/max 1}]}]
                (get-in refusal [:seon.error/data :seon.fn/arity-mismatches])))
         (is (= basis (:max-tx @connection))))))))

(deftest arity-mismatch-refuses-the-final-write
  (test-support/with-database
    (fn [connection]
      (let [contracted
            (->> (db/q '[:find ?function-symbol ?minimum ?maximum
                         :where
                         [?function :seon.fn/sym ?function-symbol]
                         [?function :seon.fn/sym seon.id/digest]
                         [?function :seon.fn/arities ?arity]
                         [?arity :seon.fn.arity/min ?minimum]
                         [(get-else $ ?arity :seon.fn.arity/max -1) ?maximum]]
                       (db/db connection))
                 (group-by first))
            [callee declarations]
            (->> contracted
                 (filter (fn [[_ rows]] (and (= 1 (count rows))
                                               (nat-int? (nth (first rows) 2)))))
                 (sort-by key)
                 first)]
        (is (some? callee)
            "the canonical population must install contract arities, or this
             check reports absence as health")
        (let [[_ minimum maximum] (first declarations)
              admitted minimum
              refused (if (nat-int? maximum) (inc maximum) (dec minimum))
              caller (quote sample.mismatch/caller)]
          (transact-fixture!
           connection
           [{:seon.ns/name 'sample.mismatch}
            (merge (test-support/program-fn-row (db/db connection) caller "(defn caller [] nil)") {:seon.fn/call-arities #{[callee admitted]}})])
          (let [basis (:max-tx @connection)
                refusal (db/transact!
                         connection
                         [{:seon.fn/sym caller
                           :seon.fn/call-arities #{[callee refused]}}])]
            (is (= :seon.db/invalid-write (:seon.error/kind refusal))
                (pr-str refusal))
            (is (= [{:seon.fn/caller caller
                     :seon.fn/callee callee
                     :seon.fn/call-arity (long refused)
                     :seon.fn/declared-arities
                     [{:seon.fn.arity/min minimum :seon.fn.arity/max maximum}]
                     :seon.fn/prepared-arities
                     [{:seon.fn.arity/min minimum :seon.fn.arity/max maximum}]}]
                   (get-in refusal [:seon.error/data :seon.fn/arity-mismatches])))
            (is (= basis (:max-tx @connection)))
            (let [report (seon.fn/arity-mismatches (db/db connection))]
              (is (empty? (:seon.fn/arity-mismatches report)))
              (is (pos-int? (:seon.fn/arity-checked report)))
              (is (nat-int? (:seon.fn/arity-unchecked report))))))))))

(deftest re-index-replaces-analysis-facets-exactly
  (with-provenance-file
    "sample/facets.clj" writer-fixture-source
    (fn [connection file rows]
      (let [target (symbol "sample.facets" "record-agent!")
            before (some #(when (= target (:seon.fn/sym %)) %) rows)
            narrowed (str/replace writer-fixture-source
                                  "\n                             :seon.ns/name (helper 'sample.facets)}]))"
                                  "}]))")]
        (is (contains? (:seon.fn/writes before) :seon.ns/name))
        (test-support/transacted! connection (seon.fn/reconcile-tx (db/db connection) rows []))
        (spit file narrowed)
        (let [artifact (seon.fn/build-artifact
                         {:seon.fn/source-path (.getCanonicalPath file)
                          :seon.fn.file/first-party-functions []})
              desired (:seon.fn.file/rows artifact)
              after (some #(when (= target (:seon.fn/sym %)) %) desired)]
          (is (not (contains? (:seon.fn/writes after) :seon.ns/name)))
          (test-support/transacted!
            connection (seon.fn/reconcile-tx (db/db connection) desired (mapv program/row-identity rows)))
          (let [stored (db/pull (db/db connection) [:seon.fn/call-arities :seon.fn/writes]
                                [:seon.fn/sym target])]
            (is (= #{:seon.agent/id} (set (:seon.fn/writes stored))))
            (is (= (:seon.fn/call-arities after) (set (:seon.fn/call-arities stored))))))))))

(deftest every-indexed-file-carries-the-root-the-indexer-walked
  ;; The canonical fixture population is walked from seon.fn/source-roots, so
  ;; the root is a fact on every file entity in it and the set of roots IS the
  ;; declared roots. Scoped to that population deliberately: the changed-path
  ;; seam indexes any file it is handed, and a file under no declared root
  ;; carries no root rather than a fabricated one.
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            files (into #{} (map first)
                        (db/q '[:find ?p :where [?f :seon.fn.file/relative-path ?p]] database))
            rooted (into #{} (map first)
                         (db/q '[:find ?p :where [?f :seon.fn.file/relative-path ?p]
                                 [?f :seon.fn.file/relative-root _]] database))
            roots (into #{} (db/q '[:find [?root ...] :where
                                    [?f :seon.fn.file/relative-root ?root]] database))]
        (is (seq files) "the canonical population holds indexed files")
        (is (empty? (remove rooted files)) (pr-str (remove rooted files)))
        (is (= (set seon.fn/source-roots) roots))))))

(deftest a-declaration-answers-its-source-root-through-its-file
  ;; "Is this declaration test-only?" is this join and nothing else: no name
  ;; rule, no path parsing, and never the ABSENCE of a root.
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            root-of (fn [sym]
                      (db/q '[:find [?root ...] :in $ ?sym :where
                              [?f :seon.fn/sym ?sym] [?f :seon.fn/file ?file]
                              [?file :seon.fn.file/relative-root ?root]]
                            database sym))]
        (is (= ["src"] (root-of (quote seon.fn/build-manifest))))
        (is (= ["test"] (root-of (quote seon.test-support/with-database))))))))

(deftest operator-state-is-a-queryable-program-namespace
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           target 'seon.operator.state/subprocess-remaining-ms
           row (db/pull database
                        [:seon.fn/sym :seon.fn/private?
                         {:seon.fn/file [:seon.fn.file/relative-root
                                         :seon.fn.file/relative-path]}]
                        [:seon.fn/sym target])
           callers (my.program/callers
                    {:seon.db/db database :seon.program/subject target})]
       (is (= target (:seon.fn/sym row))
           "the canonical fixture indexes operator-state functions")
       (is (true? (:seon.fn/private? row))
           "private operator functions are program facts too")
       (is (= "src" (get-in row [:seon.fn/file
                                  :seon.fn.file/relative-root])))
       (is (= "src/seon/operator/state.clj"
              (get-in row [:seon.fn/file
                           :seon.fn.file/relative-path])))
       (is (some #(= "seon.operator.state" (namespace %))
                 (:seon.program/callers callers))
           (pr-str callers))))))

(deftest the-walked-root-travels-with-its-file-relative-to-the-publication
  (let [root (fixture-root)]
    (try
      (let [file (write-source! root "sample/rooted.clj"
                                "(ns sample.rooted)\n(defn chosen [x] x)\n")
            supplied (.getPath root)
            file-row (fn [artifact]
                       (first (filter :seon.fn.file/relative-path
                                      (:seon.fn.file/rows artifact))))
            artifact (fn [request]
                       (seon.fn/build-artifact
                        (merge {:seon.fn/source-path (.getCanonicalPath file)
                                :seon.fn.file/first-party-functions []}
                               request)))]
        (is (= supplied
               (:seon.fn.file/relative-root
                (first (filter :seon.fn.file/relative-path
                               (seon.fn/rows {:seon.fn/roots [supplied]})))))
            "the walk stores the normalized root relative to the publication")
        (is (= supplied (:seon.fn.file/relative-root (file-row (artifact {:seon.fn/roots [supplied]}))))
            "the changed-path seam answers the same root by directory ancestry")
        (is (nil? (:seon.fn.file/relative-root (file-row (artifact {}))))
            "a file under no declared source root carries no root"))
      (finally (test-support/delete-recursively! root)))))

(deftest the-indexer-emits-no-attribute-the-program-row-schema-drops
  ;; The 2026-09-16 class, twice in one day (7cfe02790, 925ca19fe): the
  ;; analyzer derived an owned facet, and the canonical row silently dropped
  ;; it because a second literal list did not name it. This compares the rows
  ;; the analysis emits with the rows the artifact carries, so a dropped facet
  ;; is a red regression rather than an absent fact nobody notices.
  (let [root (fixture-root)
        source (str "(ns indexed.sample\n"
                    "  (:require [seon.db :as db]))\n"
                    "(defn write!\n"
                    "  \"Writes one declared attribute.\"\n"
                    "  {:malli/schema [:=> [:cat :seon.db/connection] :map]}\n"
                    "  [connection]\n"
                    "  (db/transact! connection [{:seon.agent/id \"a\"}]))\n"
                    "(defn- helper [] (write! nil))\n")
        file (write-source! root "indexed/sample.clj" source)
        path (.getCanonicalPath file)
        analysis (analyzer/analyze {::analyzer/paths [path]})
        contexts (#'seon.fn/source-contexts [file])
        first-party (#'seon.fn/first-party-function-symbols analysis)
        emitted (get (#'seon.fn/analysis-rows-by-file
                      analysis first-party contexts
                      (set (keys (schema.edn/packaged-forms))))
                     path)
        artifact (seon.fn/build-artifact
                  {:seon.fn/source-path (.getPath file)
                   :seon.fn.file/first-party-functions []})
        kept (:seon.fn.file/rows artifact)
        attributes (fn [rows] (into #{} (mapcat keys) rows))
        emitted-attributes (attributes emitted)
        kept-attributes (attributes kept)]
    (is (seq emitted) "the analysis emitted declaration rows")
    (is (<= 10 (count emitted-attributes))
        (str "the analysis emitted a rich row set, not an empty one: "
             (pr-str (sort emitted-attributes))))
    (is (contains? emitted-attributes :seon.fn/writes)
        "the write facet is among the emitted attributes")
    (is (empty? (remove kept-attributes emitted-attributes))
        (str "attributes the analysis emitted and the canonical row dropped: "
             (pr-str (sort (remove kept-attributes emitted-attributes)))))
    (doseq [row emitted
            :when (program/row-identity row)
            :let [canonical (set (keys (program/canonical-row row)))
                  carried (into []
                                (keep (fn [[attribute value]]
                                        (when-not (or (nil? value)
                                                      (and (coll? value) (empty? value)))
                                          attribute)))
                                row)]]
      (is (empty? (remove canonical carried))
          (str "the canonical row dropped "
               (pr-str (vec (remove canonical carried)))
               " from " (pr-str (program/row-identity row)))))))

(deftest a-span-past-the-captured-source-is-the-typed-refusal
  ;; The 2026-09-16 publication class: `source-contexts` captures each file's
  ;; bytes and the analyzer reads the same files again, so a concurrent edit
  ;; between those two reads leaves a span addressing characters the captured
  ;; text does not have. The seam threw a bare index exception
  ;; (`Range [109022, 114047) out of bounds for length 114007`), which told
  ;; publication nothing about which file changed. The span read is total now:
  ;; a span that fits reads the captured source, and a span that does not is
  ;; the typed refusal naming the file, the span, and the two digests.
  (let [root (fixture-root)
        captured-source "(ns changing.sample)\n(defn one [] 1)\n"
        file (write-source! root "changing/sample.clj" captured-source)
        path (.getCanonicalPath file)
        contexts (#'seon.fn/source-contexts [file])
        _ (spit file (str captured-source "(defn two [] 2)\n(defn three [] 3)\n"))
        analysis (analyzer/analyze {::analyzer/paths [path]})
        entry-named (fn [declaration]
                      (first (filter #(= declaration (::analyzer/name %))
                                     (::analyzer/var-definitions analysis))))
        refusal-of (fn [read-span entry]
                     (try (read-span contexts entry)
                          (catch clojure.lang.ExceptionInfo failure
                            (ex-data failure))))
        refusal (refusal-of #'seon.fn/exact-source (entry-named 'three))
        span-refusal (refusal-of #'seon.fn/exact-form-span (entry-named 'three))]
    (testing "a declaration the captured text still holds reads exactly"
      (is (= "(defn one [] 1)"
             (#'seon.fn/exact-source contexts (entry-named 'one)))))
    (doseq [[reader data] [["exact-source" refusal]
                           ["exact-form-span" span-refusal]]]
      (testing reader
        (is (= :seon.fn/index-refused (:seon.error/kind data))
            (pr-str data))
        (is (= :seon.fn/source-changed-during-analysis
               (:seon.error/diagnostic-cause data)))
        (is (= path (:seon.fn/source-path data)))
        (is (= (count captured-source) (:seon.fn.file/captured-length data)))
        (is (= [4 1] (take 2 (:seon.error/diagnostic-offending data))))
        (is (string? (:seon.fn.file/captured-digest data)))
        (is (string? (:seon.fn.file/current-digest data)))
        (is (not= (:seon.fn.file/captured-digest data)
                  (:seon.fn.file/current-digest data))
            "the refusal names a captured digest the file no longer carries")))))

(deftest a-file-changed-after-capture-analyzes-to-the-captured-spans
  ;; The class behind that refusal: one analysis read each file TWICE, once to
  ;; capture the text spans are sliced from and once inside clj-kondo, so a
  ;; concurrent edit between the two reads produced rows for text the capture
  ;; no longer matched. `analyze` takes the captured text now and clj-kondo
  ;; reads it from a private mirror, so there is exactly one read of the live
  ;; file per analysis and the rows describe the captured bytes by
  ;; construction — whatever the editor does meanwhile.
  (let [root (fixture-root)
        captured-source "(ns changing.analyzed)\n(defn one [] 1)\n(defn two [] 2)\n"
        file (write-source! root "changing/analyzed.clj" captured-source)
        path (.getCanonicalPath file)
        contexts (#'seon.fn/source-contexts [file])
        ;; the concurrent editor, between the capture and the analysis
        _ (spit file "(ns changing.analyzed)\n(defn three [] 3)\n")
        analysis (analyzer/analyze
                  {::analyzer/sources {path (:text (get contexts path))}})
        definitions (::analyzer/var-definitions analysis)
        named (fn [declaration]
                (first (filter #(= declaration (::analyzer/name %)) definitions)))]
    (testing "the analysis describes the captured text, not the edited file"
      (is (= ['one 'two] (mapv ::analyzer/name definitions)))
      (is (nil? (named 'three))))
    (testing "every row names the source file, never its mirror"
      (is (= [path] (distinct (mapv ::analyzer/filename definitions)))))
    (testing "every declaration slices exactly out of the captured text"
      (is (= "(defn one [] 1)" (#'seon.fn/exact-source contexts (named 'one))))
      (is (= "(defn two [] 2)" (#'seon.fn/exact-source contexts (named 'two)))))
    (testing "the live file really did change under the analysis"
      (is (= "(ns changing.analyzed)\n(defn three [] 3)\n" (slurp file))))))

(deftest relocated-artifacts-preserve-path-identity
  (let [root (fixture-root)
        a (io/file root "a")
        b (io/file root "b")
        text "(ns relocated.sample)\n(defn value [unused] 1)\n"]
    (try
      (doseq [directory [a b]]
        (write-source! directory "src/relocated/sample.clj" text))
      (let [build (fn [directory]
                    (seon.fn/build-manifest
                     {:seon.fn/root (.getCanonicalPath directory)
                      :seon.fn/roots ["src"]}))
            before (build a)
            after (build b)
            snapshot (fn [directory]
                       (source/snapshot
                        {:seon.fn/root (.getCanonicalPath directory)
                         :seon.source/roots ["src"]}))]
        (is (= (dissoc before :seon.fn.manifest/root)
               (dissoc after :seon.fn.manifest/root)))
        (is (= (snapshot a) (snapshot b)))
        (is (= ["src"] (:seon.fn.manifest/relative-roots before)))
        (is (= ["src/relocated/sample.clj"]
               (mapv :seon.fn.file/relative-path (:seon.fn.manifest/artifacts before))))
        (is (= (seon.fn/artifact-by-path after "src/./relocated/sample.clj")
               (seon.fn/artifact-by-path after
                 (.getCanonicalPath (io/file b "src/relocated/sample.clj"))))))
      (finally (test-support/delete-recursively! root)))))

(deftest indexing-resolves-its-declaration-world-once-per-operation
  ;; THE COUNT IS THE ACCEPTANCE, never wall time. The declaration population
  ;; cannot change while one indexing operation runs, so an operation resolves
  ;; it ONCE and derives its shapes ONCE, whatever the row count. Both were
  ;; call-time fetches before: `packaged-forms` twice per FILE (18 ms a call,
  ;; ~12 s per 337-file publication) and `shapes-in` once per ROW
  ;; (the-indexer-resolves-its-declaration-world-per-file-and-per-row).
  (let [root (fixture-root)
        files ["src/counted/one.clj" "src/counted/two.clj"
               "src/counted/three.clj" "src/counted/four.clj"]
        source (fn [namespace-name]
                 (str "(ns counted." namespace-name ")\n"
                      "(defn a [] 1)\n(defn b [] 2)\n(defn c [] 3)\n"))
        directory (.getCanonicalPath root)
        ;; `with-redefs` replaces a Var root for the WHOLE JVM, and this one is
        ;; shared with every other test and with a development cluster's own
        ;; publication thread. Counting only the calling thread's calls is what
        ;; makes the count a property of THIS operation rather than of whatever
        ;; else the worker happened to be doing (AGENTS.md 5, "own nothing
        ;; global").
        counted (fn [body]
                  (let [caller (Thread/currentThread)
                        population-calls (atom 0)
                        shape-calls (atom 0)
                        mine? (fn [] (identical? caller (Thread/currentThread)))
                        resolve-population schema.edn/packaged-forms
                        derive-shapes program/shapes-in]
                    (with-redefs
                     [schema.edn/packaged-forms
                      (fn [& arguments]
                        (when (mine?) (swap! population-calls inc))
                        (apply resolve-population arguments))
                      program/shapes-in
                      (fn [& arguments]
                        (when (mine?) (swap! shape-calls inc))
                        (apply derive-shapes arguments))]
                      (let [value (body)]
                        {:value value
                         :seon.schema.edn/packaged-forms-calls @population-calls
                         :seon.program/shapes-in-calls @shape-calls}))))]
    (try
      (doseq [path files]
        (write-source! root path
                       (source (str/replace (last (str/split path #"/"))
                                            ".clj" ""))))
      (testing "one complete manifest is one operation, whatever the row count"
        (let [{:keys [value] :as counts}
              (counted #(seon.fn/build-manifest {:seon.fn/root directory
                                                 :seon.fn/roots ["src"]}))
              rows (reduce + (map (comp count :seon.fn.file/rows)
                                  (:seon.fn.manifest/artifacts value)))]
          (is (= (count files)
                 (count (:seon.fn.manifest/artifacts value)))
              "the probe analysed every fixture file")
          (is (< (count files) rows)
              "and there are strictly more rows than files, so a per-row or
               per-file fetch could not pass by coincidence")
          (is (= 1 (:seon.schema.edn/packaged-forms-calls counts))
              "the declaration population is resolved once for the operation")
          (is (= 1 (:seon.program/shapes-in-calls counts))
              "and its shapes are derived once, not once per row")))
      (testing "one file artifact is one operation"
        (let [counts (counted
                      #(seon.fn/build-artifact
                        {:seon.fn/source-path (first files)
                         :seon.fn/root directory
                         :seon.fn/roots ["src"]
                         :seon.fn.file/first-party-functions []}))]
          (is (= 1 (:seon.schema.edn/packaged-forms-calls counts))
              "not once for the declared-attribute set and again for the rows")
          (is (= 1 (:seon.program/shapes-in-calls counts)))))
      (testing "a caller that already resolved its population re-reads nothing"
        (let [population (schema.edn/packaged-forms)
              counts (counted
                      (fn []
                        (mapv #(seon.fn/build-artifact
                                {:seon.fn/source-path %
                                 :seon.fn/root directory
                                 :seon.fn/roots ["src"]
                                 :seon.schema.projection/forms population
                                 :seon.fn.file/first-party-functions []})
                              files)))]
          (is (= 0 (:seon.schema.edn/packaged-forms-calls counts))
              "the supplied population is the operation's world — this is what
               makes an incremental publication read the resources once")
          (is (= (count files) (:seon.program/shapes-in-calls counts))
              "and each artifact derives its shapes exactly once")))
      (finally (test-support/delete-recursively! root)))))
(deftest implementation-bodies-contribute-edges-and-test-reach
  (with-provenance-file
    "sample/call_implementations.clj"
    (slurp (io/resource "test/fixtures/call_graph_fidelity/implementations.txt"))
    (fn [connection _ rows]
      (test-support/transacted! connection (seon.fn/reconcile-tx @connection rows []))
      (doseq [[caller target] [[(quote sample.call-implementations/operate) (quote sample.call-implementations/protocol-target)]
                               [(quote sample.call-implementations/dispatch) (quote sample.call-implementations/multi-target)]]]
        (is (= #{target}
               (set (filter #{target}
                            (:seon.fn/calls
                             (db/pull @connection '[:seon.fn/calls]
                                      [:seon.fn/sym caller]))))))
        (is (= [(quote sample.call-implementations/reaches-implementations)]
               (seon.fn/tests-reaching @connection target)))))))
(deftest declared-function-values-contribute-edges-without-arities
  (with-provenance-file
    "sample/call_declarations.clj"
    (slurp (io/resource "test/fixtures/call_graph_fidelity/declarations.txt"))
    (fn [connection file _]
      (let [forms (assoc (schema.edn/packaged-forms)
                         :sample.call-declarations/rendered
                         [:map {:seon.render/ai (symbol "sample.call-declarations" "render-target")}])
            artifact (seon.fn/build-artifact
                      {:seon.fn/source-path (.getPath file)
                       :seon.fn.file/first-party-functions []
                       :seon.schema.projection/forms forms})
            rows (:seon.fn.file/rows artifact)]
        (test-support/transacted! connection (seon.fn/reconcile-tx @connection rows []))
        (let [database @connection
              owner (:db/id (db/pull database [:db/id]
                                     [:seon.fn/sym (quote sample.call-declarations/capability)]))
              handler (:db/id (db/pull database [:db/id]
                                       [:seon.fn/sym (symbol "sample.call-declarations" "handler")]))
              declared-only (:db-after (d/with database [[:db/retract owner :seon.fn/calls (symbol "sample.call-declarations" "handler")]]))]
          (is (= #{[owner handler]}
                 (set (filter #(= handler (second %))
                              (#'seon.fn/declared-reference-edges declared-only))))
              "the declaration's owner, not every mention of its attribute, reaches the handler")
          (is (= #{[owner]}
                 (db/q '[:find ?caller :in $ % ?target
                         :where (call-edge ?caller ?target)]
                       declared-only @#'seon.fn/test-reach-rules handler)))
          (is (= [(quote sample.call-declarations/reaches-declarations)]
                 (seon.fn/tests-reaching declared-only (symbol "sample.call-declarations" "handler")))))
        (doseq [[caller target] [["capability" "handler"] ["graph" "step"]
                                 ["render-owner" "render-target"] ["task-owner" "task-target"]]]
          (let [caller (symbol "sample.call-declarations" caller)
                target (symbol "sample.call-declarations" target)
                row (db/pull @connection
                             '[:seon.fn/call-arities :seon.fn/calls]
                             [:seon.fn/sym caller])]
            (is (contains? (set (:seon.fn/calls row)) target))
            (is (not-any? #(= target (first %)) (:seon.fn/call-arities row)))
            (is (= [(quote sample.call-declarations/reaches-declarations)]
                   (seon.fn/tests-reaching @connection target)))))))))
(deftest unresolved-call-shapes-preserve-reference-edges-and-reach
  (with-provenance-file
    "sample/call_references.clj"
    (slurp (io/resource "test/fixtures/call_graph_fidelity/references.txt"))
    (fn [connection _ rows]
      (test-support/transacted! connection (seon.fn/reconcile-tx @connection rows []))
      (doseq [[caller target] [["applied" "apply-target"] ["partialled" "partial-target"]
                               ["composed" "comp-target"] ["expanded" "macro-target"]
                               ["resolved" "resolve-target"]]]
        (let [caller (symbol "sample.call-references" caller)
              target (symbol "sample.call-references" target)
              row (db/pull @connection
                           '[:seon.fn/call-arities :seon.fn/references]
                           [:seon.fn/sym caller])]
          (is (contains? (set (:seon.fn/references row)) target))
          (is (not-any? #(= target (first %)) (:seon.fn/call-arities row)))
          (is (= [(quote sample.call-references/reaches-references)]
                 (seon.fn/tests-reaching @connection target))))))))

(deftest analyzer-edges-are-readable-symbols-or-no-edge
  (let [source
        (str "(ns sample.readable-edges\n"
             "  (:import [java.nio.file FileVisitResult SimpleFileVisitor]))\n"
             "(defn probe [value]\n"
             "  #_{:clj-kondo/ignore [:unresolved-namespace]}\n"
             "  (::missing/text value)\n"
             "  (proxy [SimpleFileVisitor] []\n"
             "    (visitFile [file _attributes] FileVisitResult/CONTINUE)\n"
             "    (postVisitDirectory [directory _exception] FileVisitResult/CONTINUE))\n"
             "  `(try ~value (catch Throwable failure# failure#)))\n")]
    (with-provenance-file
      "sample/readable_edges.clj"
      source
      (fn [connection _ rows]
        (let [declarations
              (filter #(or (:seon.fn/sym %) (:seon.test/sym %)) rows)
              edge-members
              (mapcat #(concat (:seon.fn/calls %)
                               (:seon.fn/references %)
                               (map first (:seon.fn/call-arities %)))
                      declarations)]
          (is (seq declarations) "the production analyzer emitted a declaration")
          (is (seq edge-members) "the fixture exercised real resolved edges")
          (is (every? #(and (symbol? %)
                            (= % (edn/read-string (pr-str %))))
                      edge-members)
              (pr-str edge-members))
          (let [report (db/transact!
                        connection
                        (seon.fn/reconcile-tx (db/db connection) rows []))]
            (is (:db-after report)
                (pr-str (select-keys report [:seon.error/kind
                                             :seon.error/message]))))
          (let [bad (symbol ":clj-kondo/unknown-namespace" "visitFile")
                row (assoc (test-support/program-fn-row
                            (db/db connection)
                            'sample.readable-edges/refused
                            "(defn refused [] true)")
                           :seon.fn/calls #{bad})
                refusal (db/transact! connection [row])]
            (is (= :seon.db/invalid-write (:seon.error/kind refusal))
                (pr-str refusal))
            (is (= :seon.fn/calls
                   (get-in refusal
                           [:seon.error/data :seon.error/diagnostic-member]))
                (pr-str refusal))))))))

(deftest cross-file-implementations-keep-edges-or-widen
  (let [root (fixture-root)]
    (try
      (write-source! root "src/sample/cross_dispatch.clj"
                     (slurp (io/resource "test/fixtures/call_graph_fidelity/dispatch.txt")))
      (let [implementation
            (write-source! root "src/sample/cross_implementation.clj"
                           (slurp (io/resource "test/fixtures/call_graph_fidelity/implementation.txt")))
            directory (.getCanonicalPath root)
            manifest (seon.fn/build-manifest {:seon.fn/root directory :seon.fn/roots ["src"]})
            partial (seon.fn/build-artifact
                     {:seon.fn/root directory :seon.fn/roots ["src"]
                      :seon.fn/source-path (.getCanonicalPath implementation)
                      :seon.fn.file/first-party-functions
                      (seon.fn/manifest-function-symbols manifest)})
            target (quote sample.cross-implementation/target)]
        (test-support/with-database
          (fn [connection]
            (test-support/transacted!
             connection
             (seon.fn/reconcile-tx @connection
                                  (vec (mapcat :seon.fn.file/rows (:seon.fn.manifest/artifacts manifest))) []))
            (is (contains?
                 (set (:seon.fn/calls
                       (db/pull @connection '[:seon.fn/calls]
                                [:seon.fn/sym (quote sample.cross-dispatch/operation)]))) target))
            (is (= [(quote sample.cross-implementation/reaches-operation)]
                   (seon.fn/tests-reaching @connection target)))
            (is (some #(contains? (set (:seon.fn/unresolved-references %)) target)
                      (:seon.fn.file/rows partial)))
            (test-support/transacted! connection
                                      (seon.fn/reconcile-tx @connection (:seon.fn.file/rows partial) []))
            (is (contains? (set (seon.fn/tests-reaching @connection target))
                           (quote sample.cross-implementation/unrelated))))))
      (finally (test-support/delete-recursively! root)))))

(defn- assert-scoped-reference-selection [database]
  (let [root (fixture-root)]
    (try
      (doseq [[path source] (edn/read-string
                            (slurp (io/resource "test/fixtures/call_graph_fidelity/scoped.edn")))]
        (write-source! root path source))
      (let [manifest (seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]})
            artifacts (:seon.fn.manifest/artifacts manifest)
            rows (vec (mapcat :seon.fn.file/rows artifacts))
            tx (seon.fn/reconcile-tx database rows [])
            projected (:db-after (d/with database tx))
            expected {(quote sample.scoped-target/target) [(quote sample.scoped-tests/direct)
                                                      (quote sample.scoped-tests/indirect)]
                      (quote sample.scoped-reference/target) [(quote sample.scoped-tests/fallback)]
                      (quote sample.scoped-file/target) [(quote sample.scoped-uncertain/local-test)]
                      (quote sample.scoped-missing/absent) []}
            queries (atom 0)
            thread (Thread/currentThread)
            derive-edges @#'seon.fn/declared-reference-edges
            selected (with-redefs-fn
                       {#'seon.fn/declared-reference-edges
                        (fn [db]
                          (when (identical? thread (Thread/currentThread))
                            (swap! queries inc))
                          (derive-edges db))}
                       #(seon.fn/gate-sets projected (keys expected)))]
        (is (= #{(quote sample.scoped-tests/indirect)}
               (set (db/q '[:find [?symbol ...]
                            :in $ ?target
                            :where
                            [?caller :seon.fn/references ?target]
                            [?caller :seon.test/sym ?symbol]] projected (symbol "sample.scoped-target" "target"))))
            "the separate reference-only caller really is stored")
        (is (= #{(quote sample.scoped-tests/direct)}
               (set (db/q '[:find [?symbol ...]
                            :in $ ?target
                            :where
                            [?caller :seon.fn/calls ?target]
                            [?caller :seon.test/sym ?symbol]] projected (symbol "sample.scoped-target" "target"))))
            "the resolved caller coexists with the reference-only caller")
        (is (= expected selected))
        (is (= 1 @queries) "one declaration relation per bulk operation")
        (doseq [[target tests] expected]
          (is (= tests (seon.fn/tests-reaching projected target)))
          (is (= (set tests)
                 (set (db/q '[:find [?caller-symbol ...]
                              :in $ % ?target-symbol
                              :where
                              [?target :seon.fn/sym ?target-symbol]
                              (call-edge ?caller ?target)
                              [?caller :seon.test/sym ?caller-symbol]]
                            projected (var-get (ns-resolve 'seon.fn 'test-reach-rules)) target)))
              "coverage rules use the same scoped incoming relation")
          (let [path (some (fn [artifact]
                             (when (some #(= target (:seon.fn/sym %))
                                         (:seon.fn.file/rows artifact))
                               (:seon.fn.file/relative-path artifact))) artifacts)]
            (is (= tests (selection/reaching-tests artifacts (if path [path] [])))))))
      (finally (test-support/delete-recursively! root)))))

(deftest reference-selection-includes-resolved-and-reference-only-callers
  (test-support/with-database
    (fn [connection] (assert-scoped-reference-selection (db/db connection)))))

(deftest malformed-source-preserves-analysis-findings
  (let [root (fixture-root)]
    (try
      (let [file (write-source! root "broken/core.clj" "(ns broken.core)\n(defn broken [")
            analysis (analyzer/analyze {::analyzer/paths [(.getCanonicalPath file)]})
            refusal (try (seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]})
                         nil (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
        (is (some #(= :syntax (::analyzer/type %)) (::analyzer/findings analysis)))
        (is (= :seon.fn/index-refused (:seon.error/kind refusal)))
        (is (seq (:seon.fn/findings refusal))))
      (finally (test-support/delete-recursively! root)))))
