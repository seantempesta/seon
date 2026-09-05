(ns seon.fn-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.cluster.run :as run]
            [seon.fn :as seon.fn]
            [seon.fn.analyzer :as analyzer]
            [seon.program :as program]
            [seon.schema.edn :as schema.edn]
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
            [{:seon.cluster.run.form/source shadow-source
              :seon.cluster.run.form/ns namespace-ref
              :seon.program/row
              {:seon.fn/sym "sample.runtime-batch/shadowed"
               :seon.fn/ns namespace-ref
               :seon.fn/source shadow-source
               :seon.fn/arglists "([x])"
               :seon.fn/private? false
               :seon.schema.admission/source :agent}}
             {:seon.cluster.run.form/source qualified-source
              :seon.cluster.run.form/ns namespace-ref
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
      (is (= [[:seon.fn/sym "sample.capability/leaf"]]
             (:seon.fn/calls (get by-symbol "sample.capability/pure-caller"))))
      (is (= [[:seon.fn/sym "sample.capability/compute-leaf"]
              [:seon.fn/sym "sample.capability/leaf"]]
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
        (is (= [[:seon.fn/sym "sample.core/helper"]]
               (:seon.fn/calls
                (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= [[:seon.fn/sym "sample.core/contracted"]]
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
        (is (= [[:seon.fn/sym "clojure.string/trim"]
                [:seon.fn/sym "sample.core/sample-macro"]]
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

(deftest planned-form-authorship-has-exactly-two-first-party-constructors
  (test-support/with-database
    (fn [connection]
      (is (= #{"seon.cluster.run/plan-tx"
               "seon.cluster.run/system-plan-tx"}
             (set
              (db/q '[:find [?caller-symbol ...]
                      :in $ ?target-symbol
                      :where
                      [?target :seon.fn/sym ?target-symbol]
                      [?caller :seon.fn/calls ?target]
                      [?caller :seon.fn/sym ?caller-symbol]]
                    @connection
                    "seon.cluster.run/plan-tx-for-author")))))))

(deftest settled-form-records-calls-across-every-program-namespace
  (test-support/with-database
    (fn [connection]
      (let [namespace-name 'my.agents.call-edges
            run-id "call-edges-run"
            process "call-edges-process"
            source
            (str "(do (seon.db/q '[:find (count ?function) . "
                 ":where [?function :seon.fn/sym _]]) "
                 "(my.run/complete \"done\"))")]
        (db/transact!
         connection
         [{:seon.ns/name namespace-name
           :seon.ns/source "(ns my.agents.call-edges)"
           :seon.ns/requires [[:seon.ns/name 'my.run]]}
          {:seon.cluster.agent/id "call-edges-agent"
           :seon.cluster.agent/namespace
           [:seon.ns/name namespace-name]}])
        (db/transact!
         connection
         (run/open-tx
          {:seon.cluster.run/id run-id
           :seon.cluster.run/agent
           [:seon.cluster.agent/id "call-edges-agent"]
           :seon.cluster.run/opened-at (java.util.Date.)}))
        (db/transact!
         connection
         (run/claim-tx
          {:seon.cluster.run/id run-id
           :seon.cluster.run/process process
           :seon.cluster.run/live-processes #{process}
           :seon.cluster.run/now (java.util.Date.)}))
        (db/transact!
         connection
         (run/plan-tx
          {:seon.cluster.run/id run-id
           :seon.cluster.run/process process
           :seon.cluster.run/starting-ns [:seon.ns/name namespace-name]
           :seon.cluster.run/plan-digest "call-edges-digest"
           :seon.cluster.run/sources
           [{:seon.cluster.run.form/source source}]}))
        (db/transact!
         connection
         (run/receipt-start-tx
          {:seon.cluster.run/id run-id
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (java.util.Date.)}))
        (db/transact!
         connection
         (run/receipt-settle-tx
          @connection
          {:seon.cluster.run/id run-id
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/result-edn ":done"}))
        (is (empty?
             (db/q '[:find [?attribute ...]
                     :in $ ?form-id
                     :where
                     [?form :seon.cluster.run.form/id ?form-id]
                     [?form ?attribute]
                     [(contains? #{:seon.fn/calls
                                   :seon.fn/keywords
                                   :seon.test/subject}
                                 ?attribute)]]
                   @connection
                   (run/form-identity run-id 0)))
            "ordinary eval rows carry no duplicate program-graph facts")))))

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
           [{:seon.ns/name namespace-name
             :seon.ns/source "(ns sample.settlement-parity)"}
            {:seon.fn/sym "sample.settlement-parity/helper"
             :seon.fn/ns [:seon.ns/name namespace-name]
             :seon.fn/source "(defn helper [value] value)"
             :seon.fn/arglists "([value])"
             :seon.fn/private? false}
            {:seon.cluster.agent/id "settlement-parity-agent"
             :seon.cluster.agent/namespace
             [:seon.ns/name namespace-name]}])
          (db/transact!
           connection
           (run/open-tx
            {:seon.cluster.run/id "settlement-parity-run"
             :seon.cluster.run/agent
             [:seon.cluster.agent/id "settlement-parity-agent"]
             :seon.cluster.run/opened-at (java.util.Date.)}))
          (db/transact!
           connection
           (run/claim-tx
            {:seon.cluster.run/id "settlement-parity-run"
             :seon.cluster.run/process "settlement-parity-process"
             :seon.cluster.run/live-processes
             #{"settlement-parity-process"}
             :seon.cluster.run/now (java.util.Date.)}))
          (db/transact!
           connection
           (run/plan-tx
            {:seon.cluster.run/id "settlement-parity-run"
             :seon.cluster.run/process "settlement-parity-process"
             :seon.cluster.run/starting-ns
             [:seon.ns/name namespace-name]
             :seon.cluster.run/plan-digest "settlement-parity-digest"
             :seon.cluster.run/sources
             [{:seon.cluster.run.form/source source}]}))
          (db/transact!
           connection
           (run/receipt-start-tx
            {:seon.cluster.run/id "settlement-parity-run"
             :seon.cluster.eval/ordinal 0
             :seon.cluster.eval/at (java.util.Date.)}))
          (db/transact!
           connection
           (run/receipt-settle-tx
            @connection
            {:seon.cluster.run/id "settlement-parity-run"
             :seon.cluster.eval/ordinal 0
             :seon.cluster.eval/result-edn ":defined"
             :seon.program/row indexed}))
          (let [edge-facts
                (fn [identity-attribute identity-value]
                  (let [entity
                        (db/q '[:find ?entity .
                                :in $ ?attribute ?value
                                :where [?entity ?attribute ?value]]
                              @connection identity-attribute identity-value)
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
                        (mapv (fn [function-symbol]
                                [:seon.fn/sym function-symbol])
                              (sort calls))})
                     (when (seq keywords)
                       {:seon.fn/keywords (set keywords)})
                     (when subject
                       {:seon.test/subject [:seon.fn/sym subject]}))))
                program-facts
                (edge-facts :seon.fn/sym
                            "sample.settlement-parity/contracted")
                form-facts
                (edge-facts :seon.cluster.run.form/id
                            (run/form-identity "settlement-parity-run" 0))]
            (is (= expected program-facts))
            (is (nil? form-facts)
                "the definition row is the sole owner of graph facts")
            (is (= :agent
                   (db/q '[:find ?author .
                           :in $ ?form-id
                           :where
                           [?form :seon.cluster.run.form/id ?form-id]
                           [?form :seon.cluster.run.form/author ?author]]
                         @connection
                         (run/form-identity
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
                   "(ns audit.unresolved)\n(defn broken [] missing)\n")
    (let [failure
          (try
            (seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]})
            nil
            (catch clojure.lang.ExceptionInfo error error))]
      (is (= :seon.fn/index-refused (:seon.error/kind (ex-data failure))))
      (is (some #(= :unresolved-symbol (::analyzer/type %))
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
      (let [changed-beta (assoc beta-artifact :seon.fn.file/digest "changed")
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
      (is (= [[:seon.fn/sym "artifact.alpha/target"]
              [:seon.fn/sym "clojure.core/str"]
              [:seon.fn/sym "clojure.string/trim"]]
             (:seon.fn/calls beta-caller)))
      (is (= beta-artifact incremental)))
    (testing "the file digest covers exact bytes, including CRLF"
      (is (re-matches #"[0-9a-f]{64}"
                      (:seon.fn.file/digest (get artifacts
                                                 (.getCanonicalPath alpha)))))
      (is (= "(ns artifact.alpha)"
             (:seon.ns/source
              (first (:seon.fn.file/rows
                      (get artifacts (.getCanonicalPath alpha))))))))))

(deftest changed-file-planning-is-conservative-and-explicit
  (let [path "/repo/src/sample.clj"
        namespace-row {:seon.ns/name 'sample
                       :seon.ns/source "(ns sample)"}
        current-row {:seon.fn/sym "sample/value"
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
        plan #(seon.fn/plan-file-change
               (merge {:seon.fn.change/status :modified
                       :seon.fn.change/current-artifact current
                       :seon.fn.change/desired-artifact desired}
                      %))]
    (testing "same identities with cardinality-one updates are upserts"
      (is (= {:seon.fn.change/action :incremental-upsert
              :seon.fn.change/path path
              :seon.fn.change/digest "new"
              :seon.fn.change/artifact desired
              :seon.fn.change/rows
              [{:seon.fn/sym "sample/value"
                :seon.fn/source "(defn value [] 2)"}]
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
              [[{:seon.fn.change/status :deleted
                 :seon.fn.change/desired-artifact nil} :deleted]
               [{:seon.fn.change/status :moved} :moved]
               [{:seon.fn.change/status :schema-resource} :schema-resource]
               [{:seon.fn.change/status :analysis-error} :analysis-error]
               [{:seon.fn.change/stale? true} :stale-artifact]
               [{:seon.fn.change/current-artifact nil} :missing-artifact]
               [{:seon.fn.change/uncertain? true} :uncertain-projection]]]
        (is (some #{reason}
                  (:seon.fn.change/reasons (plan request)))
            (str request))))))

(deftest indexing-uses-a-prebuilt-manifest-without-analysis
  (let [manifest
        {:seon.fn.manifest/roots ["/repo/src"]
         :seon.fn.manifest/digest "digest"
         :seon.fn.manifest/artifacts
         [{:seon.fn.file/path "/repo/src/prebuilt.clj"
           :seon.fn.file/digest "file-digest"
           :seon.fn.file/rows
           [{:seon.ns/name 'prebuilt
             :seon.ns/source "(ns prebuilt)"}
            {:seon.fn/sym "prebuilt/value"
             :seon.fn/ns [:seon.ns/name 'prebuilt]
             :seon.fn/source "(defn value [] 1)"
             :seon.fn/arglists "([])"
             :seon.fn/private? false
             :seon.fn/keywords
             #{:seon.cluster.agent/id :seon.config/agent-overlay}}
            {:seon.test/sym "prebuilt/value-test"
             :seon.test/ns [:seon.ns/name 'prebuilt]
             :seon.test/source "(deftest value-test (value))"
             :seon.fn/calls [[:seon.fn/sym "prebuilt/value"]]
             :seon.test/subject [:seon.fn/sym "prebuilt/value"]}]
           :seon.fn.file/identities
           [[:seon.ns/name 'prebuilt]
            [:seon.fn/sym "prebuilt/value"]
            [:seon.test/sym "prebuilt/value-test"]]}]
         :seon.fn.manifest/identities
         [[:seon.ns/name 'prebuilt]
          [:seon.fn/sym "prebuilt/value"]
          [:seon.test/sym "prebuilt/value-test"]]}
        transactions (atom [])]
    (with-redefs [analyzer/analyze
                  (fn [_]
                    (throw (ex-info "analysis must not run" {})))
                  schema.edn/packaged-forms (constantly {})
                  db/identity-attributes
                  (constantly [:seon.ns/name :seon.fn/sym :seon.test/sym])
                  db/q (fn [& _] nil)
                  db/transact!
                  (fn [_ request]
                    (swap! transactions conj request)
                    {})]
      (let [result (seon.fn/index!
                    {:seon.db/connection (atom :database)
                     :seon.fn/manifest manifest})]
        (is (pos? (:seon.reconcile/operations result)))
        (is (= 1 (count @transactions))
            "one population pays one Datahike commit")
        (let [tx-data (:tx-data (first @transactions))
              identity-op
              (fn [attribute value]
                (some #(when (and (vector? %)
                                  (= [:db/add attribute value]
                                     [(first %) (nth % 2) (nth % 3)]))
                         %)
                      tx-data))
              function-id (second (identity-op :seon.fn/sym
                                               "prebuilt/value"))
              test-id (second (identity-op :seon.test/sym
                                           "prebuilt/value-test"))
              entity-by-id (into {} (keep #(when (map? %) [(:db/id %) %]))
                                 tx-data)]
          (is (string? function-id))
          (is (= function-id
                 (:seon.test/subject (get entity-by-id test-id))))
          (is (= [function-id]
                 (:seon.fn/calls (get entity-by-id test-id))))
          (is (= #{[:db/add function-id :seon.fn/keywords
                    :seon.cluster.agent/id]
                   [:db/add function-id :seon.fn/keywords
                    :seon.config/agent-overlay]}
                 (set (filter #(and (vector? %)
                                    (= :seon.fn/keywords (nth % 2 nil)))
                              tx-data)))
              "keyword pairs remain independent cardinality-many facts"))))
    (let [attempts (atom 0)
          result
          (with-redefs [schema.edn/packaged-forms (constantly {})
                        db/identity-attributes
                        (constantly [:seon.ns/name :seon.fn/sym
                                     :seon.test/sym])
                        db/q (fn [& _] nil)
                        db/transact!
                        (fn [& _]
                          (swap! attempts inc)
                          {:seon.error/kind :seon.db/invalid-transaction})]
            (try
              (seon.fn/index!
               {:seon.db/connection (atom :database)
                :seon.fn/manifest manifest})
              ::committed
              (catch clojure.lang.ExceptionInfo failure
                (ex-data failure))))]
      (is (= :seon.fn/index-refused (:seon.error/kind result)))
      (is (= :seon.fn/population (:seon.fn/index-phase result)))
      (is (= 1 @attempts)
          "the population is one writer admission"))))

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
             (into (mapv #(dissoc % :seon.fn/keywords :seon.fn/calls)
                         (filter #(or (:seon.ns/name %) (:seon.fn/sym %)
                                      (:seon.test/sym %))
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
                       :seon.fn/ns namespace-ref
                       :seon.fn/source (str "(defn " (name (symbol function-symbol))
                                            " [] nil)")
                       :seon.fn/arglists "([])"
                       :seon.fn/private? false}
                (seq calls) (assoc :seon.fn/calls calls)))
            test-row
            (fn [test-symbol references]
              (merge {:seon.test/sym test-symbol
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
                      :seon.fn/ns namespace-ref
                      :seon.fn/source
                      (str "(defn " (name (symbol function-symbol)) " [] nil)")
                      :seon.fn/arglists "([])"
                      :seon.fn/private? false}
                     facts))]
        (db/transact!
         connection
         [{:seon.ns/name 'sample.output
           :seon.ns/source "(ns sample.output)"}
          (function-row "sample.output/ai-projector"
                        {:seon.fn/projection-boundary :seon.render/ai})
          (function-row "sample.output/raw-text"
                        {:seon.fn/projection-boundary :none})
          (function-row "sample.output/sink"
                        {:seon.fn/external-sink :ai-visible-text
                         :seon.fn/projection-boundary :none})
          (function-row "sample.output/unresolved-sink"
                        {:seon.fn/external-sink :ai-visible-text})
          (function-row "sample.output/codec-sink"
                        {:seon.fn/external-sink :codec-storage
                         :seon.fn/projection-boundary :none})
          (function-row "sample.output/projected-root" {})
          (function-row "sample.output/bypass-root" {})
          (function-row "sample.output/unresolved-root" {})
          (function-row "sample.output/codec-root" {})])
        (db/transact!
         connection
         [{:seon.fn/sym "sample.output/projected-root"
           :seon.fn/calls [[:seon.fn/sym "sample.output/ai-projector"]]}
          {:seon.fn/sym "sample.output/ai-projector"
           :seon.fn/calls [[:seon.fn/sym "sample.output/sink"]]}
          {:seon.fn/sym "sample.output/bypass-root"
           :seon.fn/calls [[:seon.fn/sym "sample.output/raw-text"]]}
          {:seon.fn/sym "sample.output/raw-text"
           :seon.fn/calls [[:seon.fn/sym "sample.output/sink"]]}
          {:seon.fn/sym "sample.output/unresolved-root"
           :seon.fn/calls [[:seon.fn/sym "sample.output/unresolved-sink"]]}
          {:seon.fn/sym "sample.output/codec-root"
           :seon.fn/calls [[:seon.fn/sym "sample.output/codec-sink"]]}])
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
            text-boundary (:seon.fn.output/text-boundary report)
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
        (is (true? (:seon.fn.output/text-boundary-target-found?
                    text-boundary))
            "a census with no bounded-text subject is a failure")
        (is (= 2 (count (:seon.fn.output/text-boundary-callers
                         text-boundary)))
            (pr-str text-boundary))
        (is (seq (:seon.fn.output/text-boundary-render-path text-boundary))
            "the render seam must reach the private text bounder")
        (is (seq (:seon.fn.output/text-boundary-admission-path text-boundary))
            "the admission seam must reach the private text bounder")
        (is (empty? (:seon.fn.output/text-boundary-bypasses text-boundary))
            (pr-str text-boundary))
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
  (let [root (fixture-root)]
    (write-source! root "fresh/core.clj"
                   "(ns fresh.core)\n(defn value [] 1)\n")
    (test-support/with-database
      (fn [connection]
        (with-redefs [schema.edn/packaged-forms (constantly {})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"fresh source scratch"
               (seon.fn/index! {:seon.db/connection connection
                                :seon.db/process boot-process
                                :seon.fn/roots [(.getPath root)]}))))))))
