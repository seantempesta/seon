(ns seon.fn-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-kondo.core]
            [clj-kondo.impl.cache]
            [clj-kondo.impl.core]
            [malli.core :as m]
            [my.program :as my.program]
            [seon.cluster :as cluster]
            [seon.cluster.store :as store]
            [datahike.api :as d]
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

(defn- transact-fixture! [connection entities]
  (test-support/transacted! connection entities))

(defn- analyzed-test-entity
  ([database test-symbol] (analyzed-test-entity database test-symbol {}))
  ([database test-symbol overrides]
   (test-support/program-entity database [:seon.test/sym test-symbol]
                             (str "(clojure.test/deftest " (name test-symbol) " (clojure.test/is true))")
                             overrides)))

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
        (is (true? (:seon.fn/namespace-unresolvable result)))
        (is (= namespace-ref
               (get-in result
                       [:seon.error/offending])))))))

(deftest an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch
  ;; One namespace form per run of one namespace (lane fix-turn-hang-2026-09-25):
  ;; a per-form namespace form from the stored entity lost the batch's own
  ;; `require`, so `schema/register!` read as the unanalyzed namespace `schema`
  ;; and the whole batch refused as a host-binding fault.
  (test-support/with-database
    (fn [connection]
      (test-support/transacted!
       connection
       {:tx-data [(test-support/namespace-entity @connection 'sample.alias-batch)
                  (test-support/namespace-entity @connection 'sample.alias-other)]})
      (let [here [:seon.ns/name 'sample.alias-batch]
            other [:seon.ns/name 'sample.alias-other]
            entity (fn [namespace-ref sym source]
                  {:seon.fn/sym sym :seon.fn/ns namespace-ref :seon.fn/source source
                   :seon.fn/arglists "([])" :seon.fn/private? false
                   :seon.schema.admission/source :agent})
            aliased "(defn registers [] (schema/register! ::item-id :int))"
            elsewhere "(defn elsewhere [] (seon.fn/tests-reaching nil 'x/y))"
            results (seon.fn/analyze-forms
                     @connection
                     [{:seon.cluster.eval/source "(require '[seon.schema :as schema])"
                       :seon.cluster.eval/ns here}
                      {:seon.cluster.eval/source aliased :seon.cluster.eval/ns here
                       :seon.program/row (entity here 'sample.alias-batch/registers aliased)}
                      {:seon.cluster.eval/source elsewhere :seon.cluster.eval/ns other
                       :seon.program/row (entity other 'sample.alias-other/elsewhere elsewhere)}])]
        (is (vector? results) (pr-str results))
        (is (contains? (set (:seon.fn/calls (second (nth results 1)))) 'seon.schema/register!)
            "the alias an earlier form required resolves to its namespace")
        (is (contains? (set (:seon.fn/calls (second (nth results 2)))) 'seon.fn/tests-reaching)
            "a form of another namespace keeps its own namespace")))))

(deftest a-static-member-call-is-a-class-member-never-a-required-namespace
  ;; Live 2026-09-26 (HEAD 67b02c99f, fresh branch): the let below refused
  ;; "Cannot classify host binding: clj-kondo holds no analysis of System":
  ;; the synthesized namespace form required every qualified symbol's prefix,
  ;; so kondo read `System/nanoTime` as a Var of an unanalyzed namespace. A
  ;; prefix names a namespace only when the program declares one; otherwise it
  ;; is a class member, as the Clojure compiler resolves it.
  (test-support/with-database
    (fn [connection]
      (test-support/transacted!
       connection {:tx-data [(test-support/namespace-entity @connection 'sample.static-member)]})
      (let [here [:seon.ns/name 'sample.static-member]
            source (str "(let [t0 (System/nanoTime)] "
                        "(defn f \"d\" {:malli/schema [:=> [:cat :int] :int]} [x] "
                        "(seon.fn/tests-reaching nil 'x/y) (Math/abs x) (java.util.UUID/randomUUID) (inc x)) "
                        "(quot (- (System/nanoTime) t0) 1000000))")
            [_ entity] (seon.fn/analyze-form
                     @connection source here
                     {:seon.fn/sym 'sample.static-member/f :seon.fn/ns here :seon.fn/source source
                      :seon.fn/arglists "([x])" :seon.fn/private? false
                      :seon.schema.admission/source :agent})]
        (is (map? entity) (pr-str entity))
        (is (contains? (set (:seon.fn/calls entity)) 'seon.fn/tests-reaching)
            "a prefix the program declares as a namespace still resolves its call")
        (is (not-any? #(#{"System" "Math" "java.util.UUID"} (namespace %)) (:seon.fn/calls entity))
            "a class member is never a call edge")))))

(deftest defining-forms-share-one-form-local-kondo-batch
  (test-support/with-database
    (fn [connection]
      (test-support/transacted! connection
                                {:tx-data [(test-support/namespace-entity @connection 'sample.runtime-batch)]})
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
            shadow-entity (second (first results))
            qualified-entity (second (second results))
            called-symbols
            (fn [entity]
              (set (:seon.fn/calls entity)))]
        (is (= 1 @calls) "all defining sources enter kondo together")
        (is (contains? (called-symbols shadow-entity) (quote clojure.core/identity)))
        (is (not (contains? (called-symbols shadow-entity) (quote clojure.core/map)))
            "a let-bound map is a local, never a clojure.core/map call")
        (is (= #{(quote seon.fn/tests-reaching) (quote clojure.core/defn)}
               (called-symbols qualified-entity))
            "a qualified namespace absent from the stored ns entity is synthesized")
        (is (not (contains? (called-symbols shadow-entity)
                            (quote seon.fn/tests-reaching)))
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
        entities (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})
        by-symbol (into {} (keep (fn [entity]
                                  (when-let [sym (:seon.fn/sym entity)]
                                    [sym entity]))) entities)
        leaf (get by-symbol (quote sample.capability/leaf))]
    (testing "the owner entity carries handler, schema, and workload together"
      (is (= (quote sample.capability/leaf) (:seon.fn/sym leaf)))
      (is (= 'sample.capability/handler
             (:seon.effect/capability leaf)))
      (is (string? (:seon.fn/spec leaf)))
      (is (= :io (:seon.fn/workload leaf))))
    (testing "call edges alone reveal the capability owner"
      ;; A macro use is a call (20a87b1d1): the defining `defn` is an edge.
      (is (= #{(quote sample.capability/leaf) (quote clojure.core/defn)}
             (:seon.fn/calls (get by-symbol (quote sample.capability/pure-caller)))))
      (is (= #{(quote sample.capability/compute-leaf) (quote sample.capability/leaf)
               (quote clojure.core/defn)}
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
        entities (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})
        leaf (first (filter #(= (quote sample.capability/leaf)
                                (:seon.fn/sym %))
                            entities))]
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

(deftest static-index-preserves-the-jvm-entity-contract
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
    (let [entities (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})
          by-id (into {} (map (juxt program/row-identity identity)) entities)
          namespace-entity (get by-id [:seon.ns/name 'sample.core])]
      (testing "top-level source is analyzed and never evaluated"
        (is (contains? by-id [:seon.fn/sym (quote sample.core/contracted)])))
      (testing "functions, tests, records, and types keep JVM parity"
        (is (= #{(quote sample.core/sample-macro)
                 (quote sample.core/helper) (quote sample.core/contracted)
                 (quote sample.core/->Pair) (quote sample.core/map->Pair)
                 (quote sample.core/->Cell)}
               (into #{} (keep :seon.fn/sym) entities)))
        (is (= #{(quote sample.core/example-test) (quote sample.core/generated-test)}
               (into #{} (keep :seon.test/sym) entities)))
        (is (true? (:seon.fn/private?
                    (get by-id [:seon.fn/sym (quote sample.core/helper)]))))
        (is (= "([f])" (:seon.fn/arglists
                         (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        ;; Malli's own predicate schemas stay themselves: `clojure.core/fn?`
        ;; names no schema and refused the whole projection (2026-09-25).
        (let [spec (:seon.fn/spec
                    (get by-id [:seon.fn/sym (quote sample.core/contracted)]))]
          (is (= "[:=> [:cat fn?] string?]" spec))
          (is (m/function-schema (edn/read-string spec))))
        (is (= :compute (:seon.fn/workload
                         (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        (is (= :ai-visible-text
               (:seon.fn/external-sink
                (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        (is (= :none
               (:seon.fn/projection-boundary
                (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        ;; A macro use and a value handed on are calls (20a87b1d1): the
        ;; defining macro and the contract's predicate values are edges.
        (is (= '#{sample.core/helper clojure.core/defn clojure.core/string?
                  clojure.core/fn?}
               (:seon.fn/calls
                (get by-id [:seon.fn/sym (quote sample.core/contracted)]))))
        (is (= '#{sample.core/contracted clojure.test/deftest clojure.core/identity}
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
            "ordinary function entities carry no false macro assertion")
        (is (nil? (:seon.fn/spec
                   (get by-id [:seon.fn/sym (quote sample.core/sample-macro)])))
            "macro entities do not claim runtime function contracts")
        (is (= '#{clojure.string/trim clojure.core/defn sample.core/sample-macro}
               (:seon.fn/calls
                (get by-id [:seon.fn/sym (quote sample.core/helper)])))
            "a macro use is a call: its expansion runs in the caller")
        (is (not (contains? (set (:seon.fn/references
                                  (get by-id [:seon.fn/sym (quote sample.core/helper)])))
                            (quote sample.core/sample-macro)))
            "only a quoted symbol is a reference")
        (let [usages (::analyzer/var-usages
                      (analyzer/analyze {::analyzer/paths [(.getCanonicalPath (io/file root "sample/core.clj"))]}))
              macros (filter ::analyzer/macro usages)]
          (is (seq macros))
          (doseq [usage macros]
            (is (some? (#'seon.fn/call-target usage))
                (pr-str (select-keys usage [::analyzer/to ::analyzer/name])))))
        (is (= "(defrecord Pair [left right])"
               (:seon.fn/source
                (get by-id [:seon.fn/sym (quote sample.core/map->Pair)]))))
      (testing "namespace context is exact source data"
        (is (= #{(quote clojure.string) (quote clojure.test.check.clojure-test) (quote clojure.test)}
               (:seon.ns/requires namespace-entity)))
        (is (contains? (:seon.ns/aliases namespace-entity)
                       {:seon.ns.alias/local 'str
                        :seon.ns.alias/target-ns 'clojure.string}))
        (is (contains? (:seon.ns/refers namespace-entity)
                       {:seon.ns.refer/local 'deftest
                        :seon.ns.refer/target-ns 'clojure.test
                        :seon.ns.refer/target-name 'deftest}))
        (is (contains? (:seon.ns/imports namespace-entity)
                        {:seon.ns.import/local 'Date
                        :seon.ns.import/target-class 'java.util.Date})))))))

(deftest publication-is-first-party-only
  (let [root (fixture-root)]
    (write-source! root "first/party.clj"
                   "(ns first.party (:require [clojure.string :as str]))\n(defn trim [x] (str/trim x))")
    (let [entities (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})]
      (is (= #{'first.party} (into #{} (keep :seon.ns/name) entities)))
      (is (= #{(quote first.party/trim)} (into #{} (keep :seon.fn/sym) entities)))
      (is (not-any? #(= (quote clojure.string/trim) (:seon.fn/sym %)) entities))))
  (is (= ["src" "test"] seon.fn/source-roots)))

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
    (let [entities (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})
          indexed
          (first (filter #(= (quote sample.settlement-parity/contracted)
                             (:seon.fn/sym %))
                         entities))
          expected
          (select-keys indexed
                       [:seon.fn/calls :seon.fn/keywords
                        :seon.test/subject])]
      (test-support/with-database
        (fn [connection]
          (test-support/transacted!
                       connection
                       ;; The indexed entity carries its file ref, so the emitted file entities
                       ;; are admitted first exactly as publication admits them.
                       (into (filterv :seon.fn.file/relative-path entities)
                             cat
                             [[(test-support/namespace-entity @connection namespace-name)
                               (test-support/program-fn-entity (db/db connection) (quote sample.settlement-parity/helper) "(defn helper [value] value)")]
                              (test-support/agent-tx @connection "settlement-parity-agent" namespace-name)]))
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
            (is (some? (:db-after settlement))
                (pr-str (select-keys settlement
                                     [:seon.error/message
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
                "the definition entity is the sole owner of graph facts")
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
        (is (seq unresolved) "External namespace names survive without manufactured target entities.")))))

(deftest contracted-entities-carry-queryable-facts-in-their-spec-transaction
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
                    [?arity :seon.fn.arity/argument-count]]
                  db)
            ;; An entity's contract graph is asserted atomically when it is
            ;; first written. Re-analysis later rewrites the analysis and
            ;; arity components without re-asserting an unchanged spec
            ;; (Datahike keeps an unchanged datom's original transaction), so
            ;; the WANTED invariant compares first assertions, from history.
            first-assertion-transactions
            (db/q '[:find ?function (min ?spec-tx) (min ?arities-tx)
                           (min ?analysis-tx)
                    :where
                    [?function :seon.fn/spec _ ?spec-tx true]
                    [?function :seon.fn/arities _ ?arities-tx true]
                    [?function :seon.program/analyzed-source-digest _
                     ?analysis-tx true]]
                  (db/history db))
            arity-assertion-transactions
            (db/q '[:find ?arity ?arity-tx ?count-tx
                    :where
                    [_ :seon.fn/arities ?arity ?arity-tx]
                    [?arity :seon.fn.arity/argument-count _ ?count-tx]]
                  db)
            argument-assertion-transactions
            (db/q '[:find ?argument ?argument-tx ?index-tx ?binding-tx
                    :where
                    [_ :seon.fn.arity/arguments ?argument ?argument-tx]
                    [?argument :seon.fn.argument/index _ ?index-tx]
                    [?argument :seon.fn.argument/binding _ ?binding-tx]]
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
          (is (seq first-assertion-transactions))
          ;; A retained store keeps a declaration's history across adoptions:
          ;; a function first published without a contract gains spec and
          ;; arities together later (seon.turn/previous-ending-namespace,
          ;; 85b459845 then c83911c82), after its first analysis.
          (is (every? (fn [[_ spec-tx arities-tx analysis-tx]]
                        (and (= spec-tx arities-tx) (<= analysis-tx spec-tx)))
                      first-assertion-transactions))
          (is (every? (fn [[_ arity-tx count-tx]] (= arity-tx count-tx))
                      arity-assertion-transactions))
          (is (every? (fn [[_ argument-tx index-tx binding-tx]]
                        (= argument-tx index-tx binding-tx))
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
                             (not [?arity :seon.fn.arity/argument-count])))]
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
      (is (true? (:seon.fn/index-refused (ex-data failure))))
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
          (is (true? (:seon.fn/index-refused (ex-data failure)))))))
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
        ;; which root holds it, so the file entity they mint — root fact
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
    (testing "one-file analysis records every call target (ruling 42b)"
      (is (= #{(quote clojure.core/str) (quote artifact.alpha/target) (quote clojure.string/trim)
               (quote clojure.core/defn)}
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
        namespace-entity {:seon.ns/name 'sample
                       :seon.ns/source "(ns sample)"}
        current-entity {:seon.fn/sym (quote sample/value)
                     :seon.schema.admission/source :core
                     :seon.fn/ns [:seon.ns/name 'sample]
                     :seon.fn/source "(defn value [] 1)"
                     :seon.fn/arglists "([])"
                     :seon.fn/private? false}
        desired-entity (assoc current-entity :seon.fn/source "(defn value [] 2)")
        current {:seon.fn.file/relative-path path
                 :seon.fn.file/digest "old"
                 :seon.fn.file/rows [namespace-entity current-entity]
                 :seon.fn.file/identities
                 [[:seon.ns/name 'sample] [:seon.fn/sym (quote sample/value)]]}
        desired {:seon.fn.file/relative-path path
                 :seon.fn.file/digest "new"
                 :seon.fn.file/rows [namespace-entity desired-entity]
                 :seon.fn.file/identities
                 [[:seon.ns/name 'sample] [:seon.fn/sym (quote sample/value)]]}
        base-request {:seon.fn.change/status :modified
                      :seon.fn.change/current-artifact current
                      :seon.fn.change/desired-artifact desired}
        plan #(seon.fn/plan-file-change (assoc (merge base-request %) :seon.schema/projection (seon.schema/handed-projection)))]
    (testing "same identities with cardinality-one updates are upserts"
      (is (= {:seon.fn.change/action :incremental-upsert
              :seon.fn.change/relative-path path
              :seon.fn.change/digest "new"
              :seon.fn.change/artifact desired
              :seon.fn.change/rows
              [desired-entity]
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
      (let [added-entity (assoc desired-entity
                             :seon.fn/sym (quote sample/new-value)
                             :seon.fn/source "(defn new-value [] 3)")
            result
            (plan {:seon.fn.change/desired-artifact
                   (-> desired
                       (update :seon.fn.file/rows conj added-entity)
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
                  (:seon.fn.change/reasons (seon.fn/plan-file-change (assoc request :seon.schema/projection (seon.schema/handed-projection)))))
            (str request))))))

(deftest indexing-uses-a-prebuilt-manifest-without-analysis
  ;; WANTED: `index!` handed a manifest takes its entities from that value and
  ;; analyses nothing. The manifest covers only the fixture root and is
  ;; indexed as that one changed path into the member's world; the source file
  ;; is deleted before indexing, so the entities can only have come from the
  ;; manifest (no analyzer Var is redefined, and no whole-checkout manifest is
  ;; built: that was 20 s against the 5 s bound).
  (let [root (fixture-root)]
    (try
      (write-source!
       root "prebuilt.clj"
       (str "(ns prebuilt (:require [clojure.test :refer [deftest is]]))\n"
            "(defn value {:malli/schema [:=> [:cat] :int]} [] 1)\n"
            "(deftest ^{:seon.test/subject prebuilt/value} value-test (is (= 1 (value))))\n"))
      (let [manifest (seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]})
            paths (into #{} (map :seon.fn.file/relative-path)
                        (:seon.fn.manifest/artifacts manifest))]
        (test-support/delete-recursively! root)
        (is (= 1 (count paths)))
        (test-support/with-database
          (fn [connection]
            (let [before @connection
                  result (seon.fn/index!
                          {:seon.db/connection connection
                           :seon.db/process boot-process
                           :seon.source/previous-database before
                           :seon.fn/changed-paths paths
                           :seon.fn/manifest manifest})
                  database @connection
                  function-id (:db/id (db/pull database [:db/id]
                                                [:seon.fn/sym (quote prebuilt/value)]))
                  test-entity (db/pull database
                                    [:seon.test/subject :seon.fn/calls]
                                    [:seon.test/sym (quote prebuilt/value-test)])]
              (is (pos? (:seon.reconcile/operations result)) (pr-str result))
              (is (= (inc (:max-tx before)) (:max-tx database))
                  "the real writer admits the population in one transaction")
              (is (integer? function-id))
              (is (= 'prebuilt/value (:seon.test/subject test-entity)))
              (is (contains? (set (:seon.fn/calls test-entity)) 'prebuilt/value))))))
      (finally (test-support/delete-recursively! root)))))

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
       (is (string? (:seon.db.write.attempt/request-id (ex-data failure))))
       (is (= refusal (dissoc (ex-data failure)
                             :seon.fn/index-phase :seon.fn/index-refused)))
       (is (str/includes? (ex-message failure) "publication-refusal"))
       (is (str/includes? (ex-message failure) ":seon.schedule/zone-id"))
       (is (str/includes? (ex-message failure) ":seon.error/unknown"))))))

(deftest retracted-program-identities-leave-no-tombstones
  (test-support/with-database
   (fn [connection]
     (let [entity (test-support/program-fn-entity (db/db connection) (symbol "seon.fn" "retired-row-fixture") "(defn retired-row-fixture [] true)")
           entity-ref [:seon.fn/sym (:seon.fn/sym entity)]]
       (test-support/transacted! connection [entity])
       (let [before (db/db connection)]
         (is (string? (:seon.db.write.attempt/request-id (db/transact! connection
                               [[:db/retract entity-ref :seon.schema.admission/source :agent]]))))
         (is (= (:max-tx before) (:max-tx (db/db connection))))
         (is (string? (:seon.db.write.attempt/request-id (db/transact! connection [{:seon.fn/sym 'seon.fn/new-bare-entity}]))))
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
             "  {:sample.keys/refused true\n"
             "   ::error/message reason\n"
             "   ::local true})\n"
             "(defn built [n] (keyword \"seon.error\" (str \"kind\" n)))\n"
             "(defn destructured [{:sample.keys/keys [depth] :keys [plain]}]\n"
             "  [depth plain])\n"
             "(deftest refusal-test\n"
             "  (is (true? (:sample.keys/refused (refuse \"why\")))))\n")]
    (write-source! root "sample/keys.clj" source)
    (write-source! root "seon/error.clj" "(ns seon.error)\n(def message :m)\n")
    (let [entities (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})
          by-id (into {} (map (juxt program/row-identity identity)) entities)
          used (fn [program-identity]
                 (:seon.fn/keywords (get by-id program-identity)))]
      (testing "literal qualified keywords land on the declaration that reads them"
        (is (= #{:sample.keys/local :sample.keys/refused
                 :seon.error/message}
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
      (testing "test entities carry their own keyword usage"
        (is (= #{:sample.keys/refused}
               (used [:seon.test/sym (quote sample.keys/refusal-test)]))
            "a test's keywords are the ones it reads, never its subject's"))
      (testing "the indexed facts answer the motivating query"
        (test-support/with-database
          (fn [connection]
            (test-support/transacted!
                         connection
                         ;; Declaration entities refer to their file entity, so the file entities
                         ;; are admitted with them exactly as publication admits them.
                         ;; Calls stay: they name the aliases a declaration's digest
                         ;; resolves through (seon.program/definition-digest), so a
                         ;; entity without them is another definition to the writer.
                         (into (mapv #(dissoc % :seon.fn/keywords)
                                     (filter #(and (or (:seon.fn.file/relative-path %) (:seon.ns/name %)
                                                       (:seon.fn/sym %) (:seon.test/sym %))
                                                   ;; The stub `seon.error` only resolves the alias; the
                                                   ;; installed namespace and its entities stay as they are.
                                                   (not= "seon/error.clj" (:seon.fn.file/relative-path %))
                                                   (not= 'seon.error (:seon.ns/name %))
                                                   (not= "seon.error" (some-> (:seon.fn/sym %) namespace)))
                                             entities))
                               (mapcat (fn [entity]
                                         (map (fn [used]
                                                [:db/add (program/row-identity entity)
                                                 :seon.fn/keywords used])
                                              (:seon.fn/keywords entity))))
                               entities))
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
                                                     :sample.keys/refused)))
                "a test reading the keyword is never a function consumer")
            (is (= [] (seon.fn/functions-using @connection
                                               :sample.keys/never-written)))))))))

(deftest fixture-analysis-never-writes-the-checkouts-dependency-cache
  ;; The class: clj-kondo keys its dependency cache by NAMESPACE NAME, so a
  ;; fixture file declaring a first-party namespace used to overwrite that
  ;; namespace's entry with a stub, and every later analysis in the same
  ;; worker JVM refused the real vars as unresolved (batch 47's
  ;; adoption-entities red). The analyzer now decides by ownership: only the
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
            function-entity
            (fn [function-symbol calls]
              (test-support/program-fn-entity (db/db connection) function-symbol (str "(defn " (name (symbol function-symbol)) " [] nil)")
                                           (cond-> {} (seq calls) (assoc :seon.fn/calls calls))))
            test-entity
            (fn [test-symbol references]
              (analyzed-test-entity (db/db connection) test-symbol references))]
        (test-support/transacted!
                     connection
                     [(test-support/namespace-entity @connection 'sample.reach)
                      (function-entity (quote sample.reach/target) nil)
                      (function-entity (quote sample.reach/bridge)
                                    #{(quote sample.reach/target)})
                      (function-entity (quote sample.reach/direct)
                                    #{(quote sample.reach/target)})
                      (function-entity (quote sample.reach/untested) nil)])
        (test-support/transacted!
                     connection
                     [(test-entity (quote sample.reach/direct)
                                {:seon.fn/calls
                                 #{(quote sample.reach/target)}
                                 :seon.test/pass-count 0
                                 :seon.test/fail-count 1
                                 :seon.test/error-count 0})
                      (test-entity (quote sample.reach/indirect)
                                {:seon.fn/calls
                                 #{(quote sample.reach/bridge)}
                                 :seon.test/pass-count 1
                                 :seon.test/fail-count 0
                                 :seon.test/error-count 0})
                      (test-entity (quote sample.reach/property)
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
        (is (not-any? #(= "sample.reach" (namespace %))
                      (:seon.fn/calls
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

;; Class: a program attribute is a stored attribute. The contract-finding report
;; entity declared a program partition, so `program/program-attributes` named four
;; attributes no store installs; a read over that set retained evidence whose
;; witness `d/datoms` refuses, and root's graph panicked at every turn start
;; (2026-09-26, default log 16:06:58Z and 16:14:33Z).
(deftest every-program-attribute-is-installed
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           installed (:schema database)]
       (is (= [] (vec (sort (remove #(contains? installed %)
                                    (program/program-attributes
                                     (db/carried-projection database)))))))))))

(deftest contract-findings-ranks-each-incomplete-function-contract
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            function-entity
            (fn [function-symbol source]
              (test-support/program-fn-entity database function-symbol source))]
        (transact-fixture!
         connection
         [(test-support/namespace-entity @connection 'sample.contract-findings)
          (function-entity
           'sample.contract-findings/any-input
           "(defn any-input {:malli/schema [:=> [:cat :any] :int]} [x] 1)")
          (function-entity
           'sample.contract-findings/bare-map-output
           "(defn bare-map-output {:malli/schema [:=> [:cat] :map]} [] {})")
          (function-entity
           'sample.contract-findings/missing-spec
           "(defn missing-spec [] nil)")
          (function-entity
           'sample.contract-findings/fully-declared
           "(defn fully-declared {:malli/schema [:=> [:cat :int] [:map [:sample.contract-findings/value :int]]]} [x] {:sample.contract-findings/value x})")
          (merge
           (function-entity
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
       (into [(test-support/namespace-entity @connection 'sample.gates)]
             (map (fn [s]
                    (test-support/program-fn-entity (db/db connection) s (str "(defn " (name (symbol s)) " [] nil)"))))
             [(quote sample.gates/a) (quote sample.gates/b) (quote sample.gates/unrelated)]))
      (transact-fixture!
       connection
       [[:db/add [:seon.fn/sym (quote sample.gates/a)] :seon.fn/calls
         (quote sample.gates/b)]
        [:db/add [:seon.fn/sym (quote sample.gates/b)] :seon.fn/calls
         (quote sample.gates/a)]
        (analyzed-test-entity (db/db connection) (quote sample.gates/direct) {:seon.fn/calls #{(quote sample.gates/a)}})
        (analyzed-test-entity (db/db connection) (quote sample.gates/subject) {:seon.test/subject (quote sample.gates/b)})
        (analyzed-test-entity (db/db connection) (quote sample.gates/pending) {:seon.test/subject (quote sample.gates/a)})
        (analyzed-test-entity (db/db connection) (quote sample.gates/future) {:seon.test/subject (quote sample.gates/new)})])
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
        (is (= (count symbols)
               (count (filter (fn [query]
                                (some #{'(declared-edge ?caller ?target)} query))
                              @queries)))
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
       (into [(test-support/namespace-entity @connection 'sample.shape)]
             (map (fn [s]
                    (test-support/program-fn-entity (db/db connection) s (str "(defn " (name (symbol s)) " [] nil)"))))
             [(quote sample.shape/none) (quote sample.shape/one) (quote sample.shape/many)]))
      (transact-fixture!
       connection
       [(analyzed-test-entity (db/db connection) (quote sample.shape/only-test) {:seon.fn/calls #{(quote sample.shape/one)}})
        (analyzed-test-entity (db/db connection) (quote sample.shape/edge-test) {:seon.fn/calls #{(quote sample.shape/many)}})
        (analyzed-test-entity (db/db connection) (quote sample.shape/subject-test) {:seon.test/subject (quote sample.shape/many)})
        (analyzed-test-entity (db/db connection) (quote sample.shape/pending-test) {:seon.test/subject (quote sample.shape/many)})])
      (let [database (db/db connection)
            test-sym? (schema/projection-validator
                       (db/carried-projection database) :seon.test/sym)
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
              refusal (db/projection-fallback 'seon.db/q)
              refused (with-redefs
                        [db/q (fn [& arguments]
                                (if (and (identical? thread (Thread/currentThread))
                                         (some #{'(declared-edge ?caller ?target)} (first arguments)))
                                  refusal
                                  (apply query arguments)))]
                        [(seon.fn/tests-reaching database (symbol "sample.shape" "many"))
                         (seon.fn/gate-set database (symbol "sample.shape" "many"))
                         (seon.fn/gate-sets database [(symbol "sample.shape" "many")])
                         (seon.fn/gate-sets {:seon.db/db database
                                             :seon.fn/seeds #{(symbol "sample.shape" "many")}})])]

          (is (= :seon.schema/projection (:seon.schema/expected-value refusal))
              "the injected read is the database owner's complete refusal")
          (is (= [refusal refusal refusal refusal] refused)
              "a refused declared-reference read refuses every selection arity")
          (is (every? map? refused)
              "a refusal never becomes a shortened gate set"))))))

(deftest output-path-report-finds-the-shortest-bypass
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.output]
            function-entity
            (fn [function-symbol facts]
              (test-support/program-fn-entity (db/db connection) function-symbol (str "(defn " (name (symbol function-symbol)) " [] nil)")
                                           facts))]
        (transact-fixture!
         connection
         [(test-support/namespace-entity @connection 'sample.output)
          (function-entity (quote sample.output/sink)
                        {:seon.fn/external-sink :ai-visible-text
                         :seon.fn/projection-boundary :none})
          (function-entity (quote sample.output/unresolved-sink)
                        {:seon.fn/external-sink :ai-visible-text})
          (function-entity (quote sample.output/codec-sink)
                        {:seon.fn/external-sink :codec-storage
                         :seon.fn/projection-boundary :none})
          (function-entity (quote sample.output/ai-projector)
                        {:seon.fn/projection-boundary :seon.render/ai
                         :seon.fn/calls #{(quote sample.output/sink)}})
          (function-entity (quote sample.output/raw-text)
                        {:seon.fn/projection-boundary :none
                         :seon.fn/calls #{(quote sample.output/sink)}})
          (function-entity (quote sample.output/projected-root)
                        {:seon.fn/calls #{(quote sample.output/ai-projector)}})
          (function-entity (quote sample.output/bypass-root)
                        {:seon.fn/calls #{(quote sample.output/raw-text)}})
          (function-entity (quote sample.output/unresolved-root)
                        {:seon.fn/calls #{(quote sample.output/unresolved-sink)}})
          (function-entity (quote sample.output/codec-root)
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
          (is (true? (:seon.fn/index-refused (ex-data failure))))
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
           "One complete canonical program population into a fresh memory store verifies exclusive component ownership."
           :seon.test/long-ms 180000}
  fresh-population-flattens-with-its-supplied-declarations
  ;; WANTED: the fresh store is the fixture's custody (`with-database`
  ;; `:fresh-store?`), so the population writes its own world; a store made
  ;; beside the member's branch was refused as a foreign branch.
  (test-support/with-database
   {:seon.test-support/fresh-store? true}
   (fn [connection]
     (schema/call-with-projection
      (schema/declaration-projection (schema.edn/packaged-forms))
      (fn []
        ;; This is the complete-publication seam: physical attributes and
        ;; process facts exist, while canonical schema entities arrive with
        ;; the program transaction they describe.
        (#'cluster/accrete-schema-population! connection nil false)
        (is (empty? (db/q '[:find ?key :where [_ :seon.schema/key ?key]]
                          (db/db connection))))
        (is (= :db.type/ref
               (get-in @connection [:schema :seon.fn/arities :db/valueType])))
        (let [result (seon.fn/index!
                      {:seon.db/connection connection
                       :seon.db/process boot-process
                       :seon.fn/manifest @test-support/source-manifest})]
          (is (pos? (:seon.reconcile/operations result)) (pr-str result)))
        (let [database (db/db connection)
              component-attributes
              (into []
                    (keep (fn [[attribute declaration]]
                            (when (:db/isComponent declaration) attribute)))
                    (:schema database))]
          (is (some? (db/q '[:find ?key . :where [_ :seon.schema/key ?key]] database))
              "the population wrote its schema entities with the program transaction")
          (is (seq component-attributes)
              "the installed schema declares the component relationships checked")
          (is (empty?
               (db/q '[:find ?child ?first ?second
                       :in $ [?name ...]
                       :where [?first ?name ?child]
                              [?second ?name ?child]
                              [(not= ?first ?second)]] database component-attributes))
              "flattening preserves exclusive ownership of component children")))))))

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
            evaluate (production-callers database (quote seon.sci.eval/evaluate))
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
          (let [entities (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})
                admitted (db/transact! connection entities)]
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
                                (merge (select-keys cluster [:seon.sci.admit/caps])
                                       {:seon.cluster.eval/source (str "(clojure.test/deftest " metadata name " (clojure.test/is true))")
                                        :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                                        :seon.sci.eval/ctx ctx :seon.sci.eval/time-limit-ms 10000
                                        :seon.db/db @connection :seon.db/connection connection}))
                    entity (:seon.program/row evaluation)]
                (is (:seon.test/sym entity) (pr-str evaluation))
                (is (= expected (:seon.test/fixture-observation entity)) (pr-str entity))
                (when entity
                  (let [admitted (db/transact! connection [(dissoc entity :seon.sci.eval/evaluated?)])]
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
            (body connection file (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})))))
      (finally (test-support/delete-recursively! root)))))

(deftest publication-writes-one-definition-digest-per-declaration-family
  (let [root (fixture-root)
        relative-path "sample/definition_digest.clj"
        source (fn [token]
                 (str "(ns sample.definition-digest (:require [clojure.test :refer [deftest is]]))\n"
                      "(defn chosen [] " token ")\n"
                      "(deftest chosen-test (is (= " token " (chosen))))\n"))
        entities-for (fn [token]
                   (write-source! root relative-path (source token))
                   (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]}))
        first-entities (entities-for "1")
        identical-entities (entities-for "1")
        edited-entities (entities-for "2")
        digest-for (fn [entities identity]
                     (:seon.program/definition-digest
                      (some #(when (= identity (program/row-identity %)) %) entities)))
        function-identity [:seon.fn/sym 'sample.definition-digest/chosen]
        first-digest (digest-for first-entities function-identity)]
    (try
      (is (= 64 (count first-digest)))
      (is (every? (set "0123456789abcdef") first-digest))
      (is (= first-digest (digest-for identical-entities function-identity)))
      (is (not= first-digest (digest-for edited-entities function-identity)))
      (test-support/with-database
        (fn [connection]
          (let [schema-entity (first (test-support/schema-entities
                                   (db/db connection) [[:sample.definition-digest/value :string]]))
                entities (conj first-entities schema-entity)]
            (test-support/transacted!
             connection (seon.fn/reconcile-tx (db/db connection) entities []))
            (let [database (db/db connection)
                  families [[:seon.fn/sym 'sample.definition-digest/chosen]
                            [:seon.test/sym 'sample.definition-digest/chosen-test]
                            [:seon.ns/name 'sample.definition-digest]
                            [:seon.schema/key :sample.definition-digest/value]]
                  digests (mapv #(db/pull database
                                          [:seon.program/definition-digest] %)
                                families)]
              (is (= 4 (count (filter :seon.program/definition-digest digests))))
              (is (every? #(let [digest (:seon.program/definition-digest %)]
                             (and (= 64 (count digest))
                                  (every? (set "0123456789abcdef") digest)))
                          digests))))))
      (finally (test-support/delete-recursively! root)))))

(deftest indexed-declarations-carry-exact-file-bytes
  (with-provenance-file
    "sample/provenance.clj"
    (str "(ns sample.provenance (:require [clojure.test :refer [deftest is]]))\n"
         "; Unicode before the forms: café 🪴\n"
         "(defn chosen [x] x)\n(deftest example (is (= 1 (chosen 1))))\n")
    (fn [connection file entities]
      (let [declarations (filter #(or (:seon.fn/source %) (:seon.test/source %)) entities)
            bytes (java.nio.file.Files/readAllBytes (.toPath file))]
        (is (= 2 (count declarations)))
        (doseq [entity declarations]
          (let [[start end :as span] (:seon.fn/form-span entity)]
            (is (= [:seon.fn.file/relative-path (relative-file file)] (:seon.fn/file entity)))
            (is (= 2 (count span)))
            (when (= 2 (count span))
              (is (= (or (:seon.fn/source entity) (:seon.test/source entity))
                     (String. (java.util.Arrays/copyOfRange bytes (int start) (int end))
                              java.nio.charset.StandardCharsets/UTF_8))))
            (is (= (:seon.fn/file entity)
                   (:seon.fn/file (program/declaration-row (seon.schema/handed-projection) entity :all :core))))))
        (let [report (db/transact! connection (seon.fn/reconcile-tx (db/db connection) entities []))]
          (is (:db-after report) (pr-str (select-keys report [:seon.error/message])))
          (when (:db-after report)
            (doseq [entity declarations]
              (is (= (relative-file file)
                     (get-in (db/pull (:db-after report)
                                      [{:seon.fn/file [:seon.fn.file/relative-path]}]
                                      (program/row-identity entity))
                             [:seon.fn/file :seon.fn.file/relative-path]))))))))))

(deftest a-contract-finding-records-the-authored-form-never-a-bound-callable
  ;; A compiled slot carries the Var its `[:fn sym]` predicate was bound to;
  ;; the finding named `#'clojure.core/ifn?` and the report refused its own
  ;; return value (contracts-out slice 1 red, 2026-09-27).
  ;; WANTED fixture: a core function entity is file-backed (seon.fn.edn requires
  ;; `:seon.fn/file` unless agent-admitted), and an agent contract may not name
  ;; clojure.core/ifn? until program-graph edges prove it pure, so the entity is
  ;; indexed from a real file through publication's entities.
  (with-provenance-file
    "sample/authored_findings.clj"
    (str "(ns sample.authored-findings)\n"
         "(defn maybe-callable {:malli/schema [:=> [:cat] [:maybe [:fn clojure.core/ifn?]]]} [] nil)\n")
    (fn [connection _file entities]
      (transact-fixture! connection (seon.fn/reconcile-tx (db/db connection) entities []))
      (let [findings (seon.fn/contract-findings (db/db connection))
            mine (filterv #(= 'sample.authored-findings/maybe-callable (:seon.fn/sym %)) findings)]
        (is (sequential? findings) "the report validates its own return value")
        (is (= [[:maybe [:fn 'clojure.core/ifn?]]]
               (mapv :seon.fn.contract/form
                     (filter #(= :seon.fn.contract.finding/maybe (:seon.fn.contract/finding %)) mine))))
        (is (not-any? #(some (fn [value] (or (var? value) (fn? value)))
                             (tree-seq coll? seq (:seon.fn.contract/form %)))
                      findings)
            "every recorded form is authored data")))))

(def ^:private reconcile-in
  "`seon.fn/reconcile-tx` with the operation's derived row shapes in hand.

  The public arity resolves the authored resources and derives their shapes,
  exactly as `index!` does for a caller that supplied no population; this is
  the same seam `index!` itself calls with the shapes it derived once."
  @#'seon.fn/reconcile-tx-in)

(deftest refused-index-reads-never-become-publication-entities
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           refusal
           {:seon.error/at (java.util.Date.)
             :seon.error/layer :seon.db/reading
             :seon.error/operation 'seon.db/q
             :seon.error/message "The program index read was refused."
             :seon.error/member :seon.fn/sym
             :seon.error/expected :seon.db/readable-database
             :seon.error/offending :seon.fn/sym
             :seon.error/data (merge {:seon.fn/index-phase :seon.fn/published-rows} {:seon.error/layer :database-read})}
           entity (test-support/program-fn-entity
                database 'sample.refused-index/read
                "(defn read [] true)")
           shapes (program/shapes-in (seon.schema/build-projection (schema.edn/packaged-forms)))
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
             (reconcile-in shapes database [entity] []))]
       (is (= refusal index-result)
           "the published-entity query refusal is the publication result")
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
             "error keys are never presented as a vector of program entities"))))))

(deftest incremental-tempid-rewrite-follows-the-installed-attribute-type
  (let [target-namespace 'sample.attribute-aware.target
        owner-namespace 'sample.attribute-aware.owner
        identity-pair [:seon.ns/name target-namespace]]
    (test-support/with-database
     {:seon.test-support/extra-schema
      [{:db/ident ::identity-value
        :db/valueType :db.type/tuple
        :db/tupleTypes [:db.type/keyword :db.type/symbol]
        :db/cardinality :db.cardinality/one}
       {:db/ident ::identity-ref
        :db/valueType :db.type/ref
        :db/cardinality :db.cardinality/one}]}
     (fn [connection]
       (let [declared {::identity-value [:tuple :keyword :symbol] ::identity-ref :seon.db/ref}]
         (test-support/transacted!
          connection
          (seon.schema/canonical-schema-entities
           (reduce-kv #(seon.schema/projection-with-schema %1 %2 %3 {:seon.schema.admission/source :agent})
                      (db/carried-projection @connection) declared)
           declared)))
       (let [database @connection
             projection (db/carried-projection database)
             forms (-> (:seon.schema.projection/forms projection)
                       (update :seon.ns/ns conj
                               [::identity-value {:optional true}
                                ::identity-value]
                               [::identity-ref {:optional true}
                                ::identity-ref]))
             entity-projection (seon.schema/build-projection forms)
             ;; Declarations through their owner, which derives each digest.
             entities (mapv #(program/declaration-row entity-projection % :all :agent)
                        [{:seon.ns/name target-namespace
                          :seon.ns/source (pr-str (list 'ns target-namespace))}
                         {:seon.ns/name owner-namespace
                          :seon.ns/source (pr-str (list 'ns owner-namespace))
                          ::identity-value identity-pair
                          ::identity-ref identity-pair}])
             tx-data (reconcile-in (program/shapes-in entity-projection)
                                   database entities [])
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
  ;; started was stripped from every entity `seon.fn/artifact` built until a
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
                 entities (:seon.fn.file/rows (seon.fn/build-artifact request))
                 file-entity (first (filter :seon.fn.file/relative-path entities))]
             (testing "the population in hand decides what the artifact owns"
               (is (some? file-entity) "the file genuinely indexed")
               (is (= (.getPath root) (:seon.fn.file/relative-root file-entity))
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
               (let [emitted (assoc file-entity ::declared-after "carried")]
                 (is (nil? (::declared-after
                            (program/canonical-row
                             (program/shapes-in (seon.schema/build-projection packaged)) emitted)))
                     "an undeclared attribute is not a program entity attribute")
                 (is (= "carried" (::declared-after
                                   (program/canonical-row
                                    (program/shapes-in (seon.schema/build-projection declared)) emitted)))
                     "declaring it on :seon.fn.file/file is the whole
                      requirement — no restart, no second list")
                 (testing "and the next index of the same file writes it"
                   (let [report (db/transact!
                                 connection
                                 (reconcile-in
                                  (program/shapes-in (seon.schema/build-projection declared))
                                  (db/db connection)
                                  [(program/canonical-row
                                    (program/shapes-in (seon.schema/build-projection declared)) emitted)]
                                  []))]
                     (is (:db-after report)
                         (pr-str (select-keys report [:seon.error/message
                                                      :seon.error/message])))
                     (is (= "carried"
                            (::declared-after
                             (db/pull (:db-after report) '[*]
                                      [:seon.fn.file/relative-path (relative-file path)])))
                         "the attribute reaches the database through the
                          ordinary admission path")))))))))
      (finally (test-support/delete-recursively! root)))))

(deftest static-findings-are-replaced-with-their-program-entities
  (with-provenance-file
    "sample/lint.clj" "(ns sample.lint)\n(defn chosen [unused] 1)\n"
    (fn [connection file entities]
      (let [findings (filter :seon.lint/id entities)
            finding (first findings)]
        (is (= 1 (count findings)))
        (is (= :unused-binding (:seon.lint/type finding)))
        (is (= [:seon.fn/sym (quote sample.lint/chosen)] (:seon.lint/fn finding)))
        (let [report (db/transact! connection (seon.fn/reconcile-tx (db/db connection) entities []))]
          (is (:db-after report) (pr-str (select-keys report [:seon.error/message])))
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
                                                      (mapv program/row-identity entities)))]
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
;;; Analysis attributes — :seon.fn/writes and :seon.fn/call-arities
;;; --------------------------------------------------------------------------

(def ^:private writer-fixture-source
  (str "(ns sample.attributes\n"
       "  (:require [seon.db :as db]))\n"
       "\n"
       "(defn helper [value] value)\n"
       "\n"
       "(defn record-agent!\n"
       "  [connection id]\n"
       "  (db/transact! connection [{:seon.agent/id id\n"
       "                             :sample.attributes/undeclared true\n"
       "                             :seon.ns/name (helper 'sample.attributes)}]))\n"
       "\n"
       "(defn reads-only\n"
       "  [database]\n"
       "  (db/q '[:find ?e :where [?e :seon.agent/id _]] database))\n"))

(defn- attribute-entities
  ([] (attribute-entities writer-fixture-source))
  ([source]
   (let [root (fixture-root)]
     (write-source! root "sample/attributes.clj" source)
     (into {}
           (keep (fn [entity]
                   (when-let [function-symbol (:seon.fn/sym entity)]
                     [function-symbol entity])))
           (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})))))

(deftest writes-attribute-names-every-attribute-a-declaration-transacts
  (let [entities (attribute-entities)]
    (testing "the attributes inside a transact! call's own span, as keyword values"
      (is (= #{:seon.agent/id :seon.ns/name :sample.attributes/undeclared}
             (:seon.fn/writes (get entities (quote sample.attributes/record-agent!))))))
    (testing "the analyzer retains an observed write even before its schema is declared"
      (is (contains? (:seon.fn/writes (get entities (quote sample.attributes/record-agent!)))
                     :sample.attributes/undeclared)))
    (testing "reading an attribute is not writing it"
      (is (nil? (:seon.fn/writes (get entities (quote sample.attributes/reads-only))))
          "the read names :seon.agent/id but transacts nothing")
      (is (contains? (:seon.fn/keywords (get entities (quote sample.attributes/reads-only)))
                     :seon.agent/id)
          "the conflating keyword attribute still carries it"))
    (testing "a declaration that never reaches the write seam carries no set"
      (is (nil? (:seon.fn/writes (get entities (quote sample.attributes/helper))))))))

(deftest call-arities-refine-exactly-the-stored-call-edges
  (let [entities (attribute-entities)
        writer (get entities (quote sample.attributes/record-agent!))
        reader (get entities (quote sample.attributes/reads-only))]
    ;; A macro use is a call (20a87b1d1): the defining `defn` form is one.
    (is (= #{[(quote seon.db/transact!) 2] [(quote sample.attributes/helper) 1]
             [(quote clojure.core/defn) 3]}
           (:seon.fn/call-arities writer)))
    (is (= #{[(quote seon.db/q) 2] [(quote clojure.core/defn) 3]}
           (:seon.fn/call-arities reader)))
    (testing "every refined callee is a target the reach index already carries"
      (doseq [entity (vals entities)]
        (is (= (into #{} (map first) (:seon.fn/call-arities entity))
               (set (:seon.fn/calls entity)))
            (str (:seon.fn/sym entity)
                 " refines exactly its :seon.fn/calls targets"))))
    (is (= #{[(quote clojure.core/defn) 3]}
           (:seon.fn/call-arities (get entities (quote sample.attributes/helper))))
        "a declaration calling nothing else carries only its defining form's tuple")))

(deftest static-index-and-runtime-admission-agree-on-analysis-attributes
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.attributes]
            static (get (attribute-entities) (quote sample.attributes/record-agent!))
            definition
            (str "(defn record-agent!\n"
                 "  [connection id]\n"
                 "  (seon.db/transact! connection"
                 " [{:seon.agent/id id\n"
                 "     :sample.attributes/undeclared true\n"
                 "     :seon.ns/name (helper 'sample.attributes)}]))")
            program-entity (test-support/program-fn-entity (db/db connection) (quote sample.attributes/record-agent!) definition)]
        (transact-fixture!
         connection
         [(test-support/namespace-entity @connection 'sample.attributes)
          {:seon.ns/name 'seon.db}])
        (transact-fixture!
         connection
         [(test-support/revision @connection [:seon.fn/sym (quote seon.db/transact!)]
                                 {:seon.fn/arglists "([request])"})
          (test-support/program-fn-entity (db/db connection) (quote sample.attributes/helper) "(defn helper [value] value)")])
        (let [[_ admitted]
              (first (seon.fn/analyze-forms
                      (db/db connection)
                      [{:seon.cluster.eval/source definition
                        :seon.cluster.eval/ns namespace-ref
                        :seon.program/row program-entity}]))]
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
           entity (test-support/program-fn-entity (db/db connection) caller source)
           _ (test-support/transacted! connection [(test-support/namespace-entity @connection 'sample.prepared)])
           analyzed (seon.fn/analyze-forms
                     (db/db connection)
                     [{:seon.cluster.eval/source source
                       :seon.cluster.eval/ns [:seon.ns/name 'sample.prepared]
                       :seon.program/row entity}])
           admitted (second (first analyzed))]
       (is (= #{['my.message/inbox 0] ['clojure.core/defn 3]} (:seon.fn/call-arities admitted)))
       (test-support/transacted! connection [admitted])
       (is (empty? (:seon.fn/arity-mismatches (seon.fn/arity-mismatches (db/db connection)))))
       (let [basis (:max-tx @connection)
             refusal (db/transact! connection
                                   [[:db/add [:seon.fn/sym caller]
                                     :seon.fn/call-arities ['my.message/inbox 2]]])]
         (is (string? (:seon.db.write.attempt/request-id refusal)) (pr-str refusal))
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
                 (filter (fn [[_ tuples]] (and (= 1 (count tuples))
                                               (nat-int? (nth (first tuples) 2)))))
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
           [(test-support/namespace-entity @connection 'sample.mismatch)
            (test-support/program-fn-entity (db/db connection) caller "(defn caller [] nil)" {:seon.fn/call-arities #{[callee admitted]}})])
          (let [basis (:max-tx @connection)
                refusal (db/transact!
                         connection
                         [{:seon.fn/sym caller
                           :seon.fn/call-arities #{[callee refused]}}])]
            (is (string? (:seon.db.write.attempt/request-id refusal))
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

(deftest re-index-replaces-analysis-attributes-exactly
  (with-provenance-file
    "sample/attributes.clj" writer-fixture-source
    (fn [connection file entities]
      (let [target (symbol "sample.attributes" "record-agent!")
            before (some #(when (= target (:seon.fn/sym %)) %) entities)
            narrowed (str/replace writer-fixture-source
                                  "\n                             :seon.ns/name (helper 'sample.attributes)}]))"
                                  "}]))")]
        (is (contains? (:seon.fn/writes before) :seon.ns/name))
        (test-support/transacted! connection (seon.fn/reconcile-tx (db/db connection) entities []))
        (spit file narrowed)
        ;; Re-indexed through publication's entities against the member's world
        ;; (checkout analysis cache, database first-party set), never a hand
        ;; first-party list.
        (let [desired (seon.fn/program-entities
                       {:seon.fn/roots [(.getPath (.getParentFile (.getParentFile file)))]
                        :seon.source/previous-database (db/db connection)})
              after (some #(when (= target (:seon.fn/sym %)) %) desired)]
          (is (not (contains? (:seon.fn/writes after) :seon.ns/name)))
          (test-support/transacted!
            connection (seon.fn/reconcile-tx (db/db connection) desired (mapv program/row-identity entities)))
          (let [stored (db/pull (db/db connection) [:seon.fn/call-arities :seon.fn/writes]
                                [:seon.fn/sym target])]
            (is (= #{:seon.agent/id :sample.attributes/undeclared}
                   (set (:seon.fn/writes stored))))
            (is (= (:seon.fn/call-arities after) (set (:seon.fn/call-arities stored))))))))))

(deftest every-indexed-file-carries-the-root-the-indexer-walked
  ;; The canonical fixture population is walked from seon.fn/source-roots, so
  ;; analyzed files are selected through declaration refs. Publication input
  ;; entities also include resources and dependencies, which are not lint inputs.
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            files (into #{} (map first)
                        (db/q '[:find ?p :where [_ :seon.fn/file ?f]
                                 [?f :seon.fn.file/relative-path ?p]] database))
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

(deftest process-owner-is-a-queryable-program-namespace
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           target 'seon.cluster.process/subprocess-remaining-ms
           entity (db/pull database
                        [:seon.fn/sym :seon.fn/private?
                         {:seon.fn/file [:seon.fn.file/relative-root
                                         :seon.fn.file/relative-path]}]
                        [:seon.fn/sym target])
           callers (my.program/callers
                    {:seon.db/db database :seon.program/subject target})]
       (is (= target (:seon.fn/sym entity))
           "the canonical fixture indexes process functions")
       (is (true? (:seon.fn/private? entity))
           "private process functions are program facts too")
       (is (= "src" (get-in entity [:seon.fn/file
                                  :seon.fn.file/relative-root])))
       (is (= "src/seon/cluster/process.clj"
              (get-in entity [:seon.fn/file
                           :seon.fn.file/relative-path])))
       (is (some #(= "seon.cluster.process" (namespace %))
                 (:seon.program/callers callers))
           (pr-str callers))))))

(deftest the-walked-root-travels-with-its-file-relative-to-the-publication
  (let [root (fixture-root)]
    (try
      (let [file (write-source! root "sample/rooted.clj"
                                "(ns sample.rooted)\n(defn chosen [x] x)\n")
            supplied (.getPath root)
            file-entity (fn [artifact]
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
                               (seon.fn/program-entities {:seon.fn/roots [supplied]})))))
            "the walk stores the normalized root relative to the publication")
        (is (= supplied (:seon.fn.file/relative-root (file-entity (artifact {:seon.fn/roots [supplied]}))))
            "the changed-path seam answers the same root by directory ancestry")
        (is (nil? (:seon.fn.file/relative-root (file-entity (artifact {}))))
            "a file under no declared source root carries no root"))
      (finally (test-support/delete-recursively! root)))))

(deftest the-indexer-emits-no-attribute-the-program-entity-schema-drops
  ;; The 2026-09-16 class, twice in one day (7cfe02790, 925ca19fe): the
  ;; analyzer derived an owned attribute, and the canonical entity silently dropped
  ;; it because a second literal list did not name it. This compares the entities
  ;; the analysis emits with the entities the artifact carries, so a dropped attribute
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
        ;; The analysis publication runs: it reads the checkout's clj-kondo
        ;; cache, so seon.db is analyzed rather than unknown (a9449b7c3).
        analysis (analyzer/analyze {::analyzer/paths [path]
                                    ::analyzer/cache-root
                                    (str (io/file analyzer/config-directory ".cache"))})
        contexts (#'seon.fn/source-contexts [file])
        first-party (#'seon.fn/first-party-function-symbols analysis)
        emitted (get (#'seon.fn/analysis-entities-by-file
                      analysis first-party contexts
                      (schema/handed-projection)
                      (schema/handed-projection))
                     path)
        kept (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})
        attributes (fn [entities] (into #{} (mapcat keys) entities))
        emitted-attributes (attributes emitted)
        kept-attributes (attributes kept)]
    (is (seq emitted) "the analysis emitted declaration entities")
    (is (<= 10 (count emitted-attributes))
        (str "the analysis emitted a rich entity set, not an empty one: "
             (pr-str (sort emitted-attributes))))
    (is (contains? emitted-attributes :seon.fn/writes)
        "the write attribute is among the emitted attributes")
    (is (empty? (remove kept-attributes emitted-attributes))
        (str "attributes the analysis emitted and the canonical entity dropped: "
             (pr-str (sort (remove kept-attributes emitted-attributes)))))
    (doseq [entity emitted
            :when (program/row-identity entity)
            :let [canonical (set (keys (program/canonical-row entity)))
                  carried (into []
                                (keep (fn [[attribute value]]
                                        (when-not (or (nil? value)
                                                      (and (coll? value) (empty? value)))
                                          attribute)))
                                entity)]]
      (is (empty? (remove canonical carried))
          (str "the canonical entity dropped "
               (pr-str (vec (remove canonical carried)))
               " from " (pr-str (program/row-identity entity)))))))

(defn- analyzed-namespace-contexts
  "Each fixture source's namespace context, from one publication analysis."
  [sources]
  (let [root (.getCanonicalPath (fixture-root))
        analysis (analyzer/analyze
                  {::analyzer/sources (into {} (map (fn [[path text]] [(str root "/" path) text]))
                                            sources)})]
    (into {}
          (map (fn [entry]
                 [(::analyzer/name entry)
                  (try (#'seon.fn/namespace-context entry)
                       (catch clojure.lang.ExceptionInfo refusal (ex-data refusal)))]))
          (::analyzer/namespace-definitions analysis))))

(deftest namespace-context-reads-clauses-from-the-publication-analysis
  ;; A file's ns clauses come from the clj-kondo analysis publication already
  ;; ran, never a second read: 78161085b read them through the reader's
  ;; clojure.core.specs.alpha conform, 320 MB and 124 ms per 441-file pass.
  (let [contexts
        (analyzed-namespace-contexts
         {"clauses.clj"
          (str "(ns sample.clauses (:require [clojure [edn :as e] set] clojure.walk"
               " [a.b :as-alias ab] [clojure.test :refer [deftest is]])"
               " (:import [java.io File] java.util.UUID))\n"
               "(require '[clojure.data :as data])\n")
          "prefix.clj" "(ns sample.prefix (:require [clojure [edn :as e]]))\n"
          "renamed.clj" "(ns sample.renamed (:require [clojure.test :refer [deftest] :rename {deftest dt}]))\n"})
        context (get contexts 'sample.clauses)]
    (is (= '#{clojure.edn clojure.set clojure.walk clojure.test clojure.data} (:requires context))
        ":as-alias creates no load edge; prefix members, bare libs and a top-level require do")
    (is (= '{e clojure.edn ab a.b data clojure.data} (:aliases context)))
    (is (= '{deftest clojure.test/deftest is clojure.test/is} (:refers context)))
    (is (= '{File java.io.File UUID java.util.UUID} (:imports context))
        "a bare dotted import binds its simple class name")
    (is (= '{e clojure.edn} (:aliases (get contexts 'sample.prefix)))
        "a prefix list whose only member carries options")
    (is ((schema/projection-validator (schema/handed-projection) :seon.fn/index-refused-error)
         (get contexts 'sample.renamed))
        "clj-kondo records no renamed local: a :rename is refused by name, never mis-bound")))

(deftest a-top-level-require-rename-is-refused-by-name
  ;; F8 (docs/research/agent-platform/review-landings-2026-09-24.md): a
  ;; top-level require's :rename was dropped, so the entity bound no local `join`.
  (let [contexts (analyzed-namespace-contexts
                  {"renamed.clj"
                   (str "(ns sample.top-renamed)\n"
                        "(require '[clojure.set :refer [union] :rename {union join}])\n"
                        "(defn f [] (join #{1} #{2}))\n")})
        refusal (get contexts 'sample.top-renamed)]
    (is ((schema/projection-validator (schema/handed-projection) :seon.fn/index-refused-error)
         refusal)
        (pr-str refusal))
    (is (= 2 (get-in refusal [:seon.fn/analysis-entry :seon.fn.analyzer/row]))
        "the refusal names the require's own :rename")))

(deftest a-file-deleted-after-listing-is-the-declared-refusal
  ;; 2026-09-24 fault entity 15: `full-source-refresh!` listed a dependent file,
  ;; another lane deleted it, and the read's NoSuchFileException was recorded
  ;; as a core fault. A deletion during a refresh is an expected outcome: the
  ;; declared analysis refusal the operator request returns as refused.
  (let [file (write-source! (fixture-root) "deleted/sample.clj" "(ns deleted.sample)\n")
        _ (io/delete-file file)
        failure (try (#'seon.fn/source-contexts [file]) nil
                     (catch clojure.lang.ExceptionInfo failure failure))
        refusal (ex-data failure)]
    (is ((schema/projection-validator (schema/handed-projection) :seon.fn/index-refused-error)
         refusal)
        (pr-str refusal))
    (is (= :analysis (:seon.cluster.source/phase refusal)))
    (is (= :refused (:seon.cluster.boot/disposition refusal))
        "the operator request returns it as refused, never a core fault")
    (is (= [(.getPath file)] (:seon.source/changed-paths refusal)))
    (is (instance? java.nio.file.NoSuchFileException (ex-cause failure))
        "the whole cause is kept")))

(deftest a-file-changed-after-capture-analyzes-to-the-captured-spans
  ;; The 2026-09-16 publication class: one analysis read each file TWICE, once
  ;; to capture the text spans are sliced from and once inside clj-kondo, so a
  ;; concurrent edit between the two reads produced spans past the captured
  ;; text (`Range [109022, 114047) out of bounds for length 114007`). `analyze` takes the captured text now and clj-kondo
  ;; reads it from a private mirror, so there is exactly one read of the live
  ;; file per analysis and the entities describe the captured bytes by
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
    (testing "every entity names the source file, never its mirror"
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
)
      (finally (test-support/delete-recursively! root)))))

(deftest implementation-bodies-contribute-edges-and-test-reach
  (with-provenance-file
    "sample/call_implementations.clj"
    (slurp (io/resource "test/fixtures/call_graph_fidelity/implementations.txt"))
    (fn [connection _ entities]
      (test-support/transacted! connection (seon.fn/reconcile-tx (db/db connection) entities []))
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
            ;; WANTED world: publication's own entities (`seon.fn/program-entities`), whose
            ;; analysis reads the checkout's clj-kondo cache and whose
            ;; first-party set is the member database's program entities, never a
            ;; hand list (an empty one made seon.effect unanalyzed, a9449b7c3).
            entities (seon.fn/program-entities
                  {:seon.fn/roots [(.getPath (.getParentFile (.getParentFile file)))]
                   :seon.schema/projection (schema/build-projection forms)
                   :seon.source/previous-database (db/db connection)})]
        (test-support/transacted! connection (seon.fn/reconcile-tx (db/db connection) entities []))
        (let [database @connection
              owner (:db/id (db/pull database [:db/id]
                                     [:seon.fn/sym (quote sample.call-declarations/capability)]))
              handler (:db/id (db/pull database [:db/id]
                                       [:seon.fn/sym (symbol "sample.call-declarations" "handler")]))
              declared-only (:db-after (d/with database [[:db/retract owner :seon.fn/calls (symbol "sample.call-declarations" "handler")]]))]
          (is (contains? (#'seon.fn/declared-reference-edges declared-only) [owner handler])
              "The owning capability preserves its handler without a lexical call.")
          (is (= (into #{} (comp (filter #(= handler (second %)))
                                 (map #(vector (first %))))
                       (#'seon.fn/declared-reference-edges declared-only))
                 (db/q '[:find ?caller :in $ % ?target
                         :where (call-edge ?caller ?target)]
                       declared-only @#'seon.fn/test-reach-rules handler)))
          (is (= [(quote sample.call-declarations/reaches-declarations)]
                 (filterv #(= "sample.call-declarations" (namespace %))
                          (seon.fn/tests-reaching declared-only (symbol "sample.call-declarations" "handler"))))))
        (let [database @connection
              selected (fn [target]
                         (set (filter #(= "sample.call-declarations" (namespace %))
                                      (seon.fn/tests-reaching database target))))]
          (is (= #{'sample.call-declarations/reaches-leaf}
                 (selected 'sample.call-declarations/leaf))
              "A descriptive operation symbol does not select diagnostic readers.")
          (is (= #{'sample.call-declarations/reaches-leaf 'sample.call-declarations/reaches-shared}
                 (selected 'sample.call-declarations/shared))
              "A real shared dependency retains both callers' tests.")
          (doseq [[reader target] [['sample.call-declarations/diagnostic-reader 'sample.call-declarations/leaf]
                                   ['sample.call-declarations/renderer-reader 'sample.call-declarations/render-target]]]
            (let [entity (db/pull database '[:seon.fn/calls :seon.fn/references]
                               [:seon.fn/sym reader])]
              (is (not (contains? (into (set (:seon.fn/calls entity)) (:seon.fn/references entity)) target)))))
          (is (= #{'sample.call-declarations/reaches-declarations}
                 (selected 'sample.call-declarations/render-target))
              "The declared invoker remains selected; a renderer-existence reader does not."))
        (doseq [[caller target] [["capability" "handler"] ["graph" "step"]
                                 ["render-owner" "render-target"] ["task-owner" "task-target"]]]
          (let [caller (symbol "sample.call-declarations" caller)
                target (symbol "sample.call-declarations" target)
                entity (db/pull @connection
                             '[:seon.fn/call-arities :seon.fn/calls]
                             [:seon.fn/sym caller])]
            (is (contains? (set (:seon.fn/calls entity)) target))
            (is (not-any? #(= target (first %)) (:seon.fn/call-arities entity)))
            (is (= [(quote sample.call-declarations/reaches-declarations)]
                   (filterv #(= "sample.call-declarations" (namespace %))
                            (seon.fn/tests-reaching @connection target))))))))))
(deftest invocation-metadata-survives-admission-and-exact-replacement
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection [(test-support/namespace-entity @connection 'sample.invocation)])
     (let [database (db/db connection)
           projection (db/carried-projection database)
           shapes (program/shapes-in projection)
           analyze (fn [source]
                     (seon.fn/source-rows (db/db connection) shapes
                       (test-support/namespace-entity (db/db connection) 'sample.invocation) source
                       (set (keys (:seon.schema.projection/forms projection)))))
           source "(defn invoke {:seon.fn/invokes #{:seon.render/ai}} [value] ((requiring-resolve (:seon.render/ai value)) value))"
           entities (analyze source)]
       (is (= #{:seon.render/ai} (:seon.fn/invokes (first entities))))
       (test-support/transacted! connection entities)
       (let [current (db/pull (db/db connection) '[*] [:seon.fn/sym 'sample.invocation/invoke])
             desired (first (analyze "(defn invoke [value] (:seon.render/ai value))"))]
         (is (not (contains? desired :seon.fn/invokes)))
         (test-support/transacted! connection (program/exact-replacement-tx-in shapes current desired))
         (is (nil? (:seon.fn/invokes
                    (db/pull (db/db connection) '[:seon.fn/invokes]
                             [:seon.fn/sym 'sample.invocation/invoke])))))
       (doseq [invalid [nil false :seon.render/ai #{:unqualified}]]
         (let [refusal (test-support/refusal-data
                        #(analyze (str "(defn invalid {:seon.fn/invokes " (pr-str invalid) "} [x] x)")))]
           (is (true? (:seon.fn/index-refused refusal)) (pr-str refusal))))))))

(deftest value-uses-are-calls-and-quoted-symbols-select-nothing
  ;; Selection by call reach (owner 2026-09-26): a function handed on as a
  ;; value (`apply`, `partial`, `comp`), a syntax-quoted body a macro expands
  ;; and the macro use itself exercise their target; a quoted symbol only
  ;; names it, in a function body or in a test (the host test quoting the
  ;; symbol its hosted agent defines selected itself without bound).
  (with-provenance-file
    "sample/call_references.clj"
    (slurp (io/resource "test/fixtures/call_graph_fidelity/references.txt"))
    (fn [connection _ entities]
      (test-support/transacted! connection (seon.fn/reconcile-tx (db/db connection) entities []))
      (let [entity (fn [caller]
                  (db/pull @connection '[:seon.fn/calls :seon.fn/call-arities :seon.fn/references]
                           [:seon.fn/sym (symbol "sample.call-references" caller)]))]
        (doseq [[caller target] [["applied" "apply-target"] ["partialled" "partial-target"]
                                 ["composed" "comp-target"] ["expanded" "macro-target"]]]
          (let [target (symbol "sample.call-references" target)
                edges (entity caller)]
            (is (contains? (set (:seon.fn/calls edges)) target))
            (is (not (contains? (set (:seon.fn/references edges)) target)))
            (is (not-any? #(= target (first %)) (:seon.fn/call-arities edges)))
            (is (= [(quote sample.call-references/reaches-references)]
                   (seon.fn/tests-reaching @connection target)))))
        (let [target (quote sample.call-references/resolve-target)
              edges (entity "resolved")]
          (is (contains? (set (:seon.fn/references edges)) target))
          (is (not (contains? (set (:seon.fn/calls edges)) target)))
          (is (= [] (seon.fn/tests-reaching @connection target))
              "a quoted symbol in a function body names its target; it selects nothing"))
        (is (contains? (set (:seon.fn/references
                             (db/pull @connection '[:seon.fn/references]
                                      [:seon.test/sym (quote sample.call-references/quotes-only)])))
                       (quote sample.call-references/apply-target))
            "the test's quoted mention is stored")
        (is (not (contains? (set (seon.fn/tests-reaching @connection (quote sample.call-references/apply-target)))
                            (quote sample.call-references/quotes-only)))
            "a test quoting a symbol is not a test of it")
        (let [affected (fn [target] (:seon.fn/affected (seon.fn/reverse-closure {:seon.db/db @connection
                                                                                 :seon.fn/seeds #{target}})))]
          (is (contains? (affected (quote sample.call-references/apply-target))
                         (quote sample.call-references/applied))
              "a caller of a changed definition is in its closure")
          (is (not (contains? (affected (quote sample.call-references/resolve-target))
                              (quote sample.call-references/resolved)))
              "a quoted mention reaches nothing: the closure an override interprets is call reach"))))))

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
      (fn [connection _ entities]
        (let [declarations
              (filter #(or (:seon.fn/sym %) (:seon.test/sym %)) entities)
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
                        (seon.fn/reconcile-tx (db/db connection) entities []))]
            (is (:db-after report)
                (pr-str (select-keys report [:seon.error/message
                                             :seon.error/message]))))
          (let [bad (symbol ":clj-kondo/unknown-namespace" "visitFile")
                entity (test-support/program-fn-entity
                     (db/db connection)
                     'sample.readable-edges/refused
                     "(defn refused [] true)"
                     {:seon.fn/calls #{bad}})
                refusal (db/transact! connection [entity])]
            (is (string? (:seon.db.write.attempt/request-id refusal))
                (pr-str refusal))
            (is (= :seon.fn/calls (:seon.db/attribute refusal))
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
                      (into [] (keep :seon.fn/sym) (seon.fn/program-entities {:seon.fn/manifest manifest}))})
            target (quote sample.cross-implementation/target)]
        (test-support/with-database
          (fn [connection]
            (test-support/transacted!
             connection
             (seon.fn/reconcile-tx (db/db connection)
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
                                      (seon.fn/reconcile-tx (db/db connection) (:seon.fn.file/rows partial) []))
            (is (not (contains? (set (seon.fn/tests-reaching @connection target))
                                (quote sample.cross-implementation/unrelated)))
                "a file's unresolved mention calls nothing, so it selects nothing"))))
      (finally (test-support/delete-recursively! root)))))

(defn- assert-scoped-reference-selection [database]
  (let [root (fixture-root)]
    (try
      (doseq [[path source] (edn/read-string
                            (slurp (io/resource "test/fixtures/call_graph_fidelity/scoped.edn")))]
        (write-source! root path source))
      (let [manifest (seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]})
            artifacts (:seon.fn.manifest/artifacts manifest)
            entities (vec (mapcat :seon.fn.file/rows artifacts))
            tx (seon.fn/reconcile-tx database entities [])
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
        (is (= #{(quote sample.scoped-tests/direct) (quote sample.scoped-tests/indirect)}
               (set (db/q '[:find [?symbol ...]
                            :in $ ?target
                            :where
                            [?caller :seon.fn/calls ?target]
                            [?caller :seon.test/sym ?symbol]] projected (symbol "sample.scoped-target" "target"))))
            "an invocation and a value handed to `partial` are both calls")
        (is (= expected selected))
        (is (= 1 @queries) "one declaration relation per bulk operation")
        (doseq [[target tests] expected]
          (is (= tests (seon.fn/tests-reaching projected target))
              "call reach selects what the conservative closure selects here")))
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
        (is (true? (:seon.fn/index-refused refusal)))
        (is (seq (:seon.fn/findings refusal))))
      (finally (test-support/delete-recursively! root)))))

(deftest ^{:seon/tasks #{"bd7cb669b45d"}} host-binding-is-a-declaration-fact-not-a-caller-closure
  (let [root (fixture-root)
        text (str "(ns sample.host-bound)\n"
                  "(defn clock [] (System/currentTimeMillis))\n"
                  "(defn constructor [] (java.util.Date.))\n"
                  "(defn literal [] java.io.File)\n"
                  "(defn allowed [] (String. \"ok\"))\n"
                  "(defn throwable [] (Throwable. \"ok\"))\n"
                  "(defn admitted-import [] (import 'java.lang.String))\n"
                  "(defn refused-import [] (import 'java.util.Date))\n"
                  "(defn implementation [] (reify Runnable (run [_] nil)))\n"
                  "(defn caller [] (clock))\n"
                  "(defn hinted [^java.io.File f] (.getName f))\n"
                  "(defn quoted [] '(clojure.core/reify java.io.File))\n"
                  "(defprotocol P (method [_]))\n"
                  "(defrecord Record [])\n")]
    (try
      (write-source! root "sample/host_bound.clj" text)
      (let [entities (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]})
            facts (into {} (keep (fn [entity]
                                  (when-let [sym (:seon.fn/sym entity)]
                                    [sym (:seon.fn/host-bound? entity)]))) entities)]
        ;; task bd7cb669b45d: the interpreter resolves every class, so class
        ;; usage (clock, constructor, literal, hinted) is interpretable
        (is (= {'sample.host-bound/clock false
                'sample.host-bound/constructor false
                'sample.host-bound/literal false
                'sample.host-bound/allowed false
                'sample.host-bound/throwable false
                'sample.host-bound/admitted-import true
                'sample.host-bound/refused-import true
                'sample.host-bound/implementation true
                'sample.host-bound/caller false
                'sample.host-bound/hinted false
                'sample.host-bound/quoted false
                'sample.host-bound/method false
                'sample.host-bound/->Record true
                'sample.host-bound/map->Record true}
               facts)))
      (finally (test-support/delete-recursively! root)))))

(deftest a-dependency-macro-sci-cannot-expand-is-host-bound
  ;; SCI hands a copied JVM macro its own `&env`; `go` reads it as compiler locals.
  ;; The macro fact is the dependency's clj-kondo analysis, whatever this JVM has
  ;; loaded (review F7): unanalyzed, the calls refuse by name.
  (let [root (fixture-root)
        text (str "(ns sample.env-macro (:require [clojure.core.async :as async]))\n"
                  "(defn looping [c] (async/go-loop [] (when (async/<! c) (recur))))\n"
                  "(defn channel [] (async/chan 1))\n"
                  "(defn plain [x] (when x (inc x)))\n")
        ;; The fixture owns its cache: the entities default is the checkout's shared
        ;; cache, whose (filled) state this test must never depend on.
        cache (str (io/file root ".clj-kondo" ".cache"))
        entities #(try (seon.fn/program-entities {:seon.fn/roots [(.getPath root)] ::analyzer/cache-root cache})
                   (catch clojure.lang.ExceptionInfo refusal (ex-data refusal)))
        jar (str/replace (.getPath (io/resource "clojure/core/async.clj")) #"^file:|!/.*$" "")]
    (try
      (write-source! root "sample/env_macro.clj" text)
      (let [refusal (entities)]
        (is (= '[clojure.core.async/<! clojure.core.async/chan clojure.core.async/go-loop]
               (:seon.fn/unresolved-targets refusal))
            (pr-str refusal))
        (is (str/includes? (str (:seon.error/message refusal)) seon.fn/dependency-analysis-command)))
      ;; The fill the command performs, for this fixture's own cache.
      (clj-kondo.core/run! {:lint [jar] :dependencies true :parallel true
                            :config-dir (str (io/file root ".clj-kondo"))
                            :cache-dir cache
                            :config {:output {:canonical-paths true}}})
      (is (= {'sample.env-macro/looping true
              'sample.env-macro/channel false
              'sample.env-macro/plain false}
             (into {} (keep (fn [entity]
                              (when-let [sym (:seon.fn/sym entity)]
                                [sym (:seon.fn/host-bound? entity)])))
                   (entities))))
      ;; A submitted batch reads the dependency analysis it is given, a value:
      ;; lacking core.async it refuses by the declared schema, holding the
      ;; filled cache's entry it classifies the macro, whatever the checkout holds.
      (test-support/with-database
        (fn [connection]
          (test-support/transacted! connection {:tx-data [(test-support/namespace-entity @connection 'sample.env-batch)]})
          (let [here [:seon.ns/name 'sample.env-batch]
                source "(defn looping [c] (async/go-loop [] (when (async/<! c) (recur))))"
                requests [{:seon.cluster.eval/source "(require '[clojure.core.async :as async])" :seon.cluster.eval/ns here}
                          {:seon.cluster.eval/source source :seon.cluster.eval/ns here
                           :seon.program/row {:seon.fn/sym 'sample.env-batch/looping :seon.fn/ns here :seon.fn/source source
                                              :seon.fn/arglists "([c])" :seon.fn/private? false
                                              :seon.schema.admission/source :agent}}]
                refusal (try (seon.fn/analyze-forms @connection requests {})
                             (catch clojure.lang.ExceptionInfo refused refused))
                async (clj-kondo.impl.cache/from-cache-1
                       (clj-kondo.impl.core/resolve-cache-dir (str (io/file root ".clj-kondo")) true cache)
                       :clj 'clojure.core.async)]
            (is (= :seon.fn/dependency-analysis-missing-error
                   (:seon.error/declared-schema (meta (ex-data refusal)))) (pr-str refusal))
            (is (m/validate :seon.fn/dependency-analysis-missing-error (ex-data refusal)
                            {:registry (:seon.schema.projection/registry (db/carried-projection @connection))}))
            (is (= '[clojure.core.async/<! clojure.core.async/go-loop] (:seon.fn/unresolved-targets (ex-data refusal))))
            (is (true? (:seon.fn/host-bound?
                        (second (second (seon.fn/analyze-forms @connection requests {'clojure.core.async async})))))))))
      (finally (test-support/delete-recursively! root)))))

(deftest canonical-program-has-complete-host-binding-facts
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            total (db/q '[:find (count ?e) . :where [?e :seon.fn/sym]] database)
            census (into {} (db/q '[:find ?bound (count ?e)
                                    :where [?e :seon.fn/sym]
                                           [?e :seon.fn/host-bound? ?bound]] database))]
        (is (pos? total))
        (is (= total (reduce + (vals census))))
        (is (pos? (get census true 0)))
        (is (pos? (get census false 0)))
        (is (true? (:seon.fn/host-bound?
                    (db/pull database [:seon.fn/host-bound?]
                             [:seon.fn/sym 'seon.render.value/bounded-text])))
            "a proxy is an operation SCI cannot interpret")
        (is (false? (:seon.fn/host-bound?
                     (db/pull database [:seon.fn/host-bound?]
                              [:seon.fn/sym 'seon.id/sha-256])))
            "class usage alone is interpretable (task bd7cb669b45d)")
        (is (false? (:seon.fn/host-bound?
                     (db/pull database [:seon.fn/host-bound?]
                              [:seon.fn/sym 'seon.id/id]))))))))

(deftest file-entities-read-in-batches-not-per-entity
  ;; Each armed read costs ~0.1 ms of owner overhead, so a pull per entity made a
  ;; whole-program read seconds (lane fix-f4-batched-index-entities).
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            paths ["src/seon/id.clj" "src/seon/program.cljc"]
            reads (atom [])
            entities (binding [db/*read-evidence-sink* reads]
                   (seon.fn/file-entities database paths :seon.fn/file))]
        (is (< 16 (count entities)) "more entities than the read bound, so per-entity reads cannot pass")
        (is (< (count @reads) 16) "reads are per path and ref depth, not per entity")
        (is (= entities (into [] (mapcat #(seon.fn/published-index-entities database [(program/row-identity %)]))
                          entities))
            "a batch reads exactly the entities each identity reads alone")))))

(deftest index-entities-name-a-data-entity-by-its-identity
  ;; A namespace's steward is an agent (`:seon.program/partition :seon.data`)
  ;; whose runtime points back to it; inlining it recursed without end.
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (test-support/transacted! connection
                                ((requiring-resolve 'seon.cluster.agent/creation-tx)
                                 {:seon.agent/id "steward-cycle"
                                  :seon.cluster/name "test"
                                  :seon.ns/name 'my.agents.steward-cycle}))
      (let [entities (seon.fn/published-index-entities
                  (db/db connection) [[:seon.ns/name 'my.agents.steward-cycle]])]
        (is (= [[:seon.agent/id "steward-cycle"]] (map :seon.ns/steward entities)))))))

(deftest computed-and-point-free-defs-are-declarations-their-referrers-keep
  ;; P2-A: clj-kondo reports every def/defonce; a computed initializer has no
  ;; arglists. Changing a literal def to a computed one once dropped its entity
  ;; while referrers still named it.
  (let [root (fixture-root)
        source (fn [handle]
                 (str "(ns sample.value-def)\n"
                      "(declare pending)\n"
                      "(def ^:private handle " handle ")\n"
                      "(def point-free (comp inc inc))\n"
                      "(defonce registry (atom {}))\n"
                      "(defn user-of [] [(:a handle) (point-free 1) @registry])\n"))
        entities-for (fn [handle]
                   (write-source! root "sample/value_def.clj" (source handle))
                   (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]}))
        by-sym (fn [entities] (into {} (keep #(when-let [s (:seon.fn/sym %)] [s %])) entities))]
    (try
      (let [literal (entities-for "{:a 1}")
            computed (entities-for "(assoc {} :a 1)")
            found (by-sym computed)]
        (is (= '#{sample.value-def/handle sample.value-def/point-free
                  sample.value-def/registry sample.value-def/user-of}
               (set (keys found)))
            "every def is an entity; `declare` interns no body")
        (is (true? (:seon.fn/constant? (get (by-sym literal) 'sample.value-def/handle))))
        (is (= {:seon.fn/arglists "()" :seon.fn/calls #{'clojure.core/assoc 'clojure.core/def}
                :seon.fn/defined-by 'clojure.core/def :seon.fn/private? true}
               (select-keys (get found 'sample.value-def/handle)
                            [:seon.fn/arglists :seon.fn/calls :seon.fn/constant?
                             :seon.fn/defined-by :seon.fn/private? :seon.fn/spec]))
            "a computed def has no arities or contract; its initializer's calls are its edges")
        (is (= '#{clojure.core/comp clojure.core/inc clojure.core/def}
               (:seon.fn/calls (get found 'sample.value-def/point-free)))
            "a value handed on is a call")
        (is (= 'clojure.core/defonce (:seon.fn/defined-by (get found 'sample.value-def/registry))))
        (test-support/with-database
          (fn [connection]
            (test-support/transacted! connection (seon.fn/reconcile-tx (db/db connection) literal []))
            (test-support/transacted!
             connection
             (seon.fn/reconcile-tx (db/db connection) computed (mapv program/row-identity literal)))
            (let [database (db/db connection)]
              (is (= {:seon.fn/source "(def ^:private handle (assoc {} :a 1))"}
                     (db/pull database [:seon.fn/source :seon.fn/constant?]
                              [:seon.fn/sym 'sample.value-def/handle]))
                  "the literal-to-computed change adopts in place")
              (is (contains? (set (:seon.fn/calls
                                   (db/pull database [:seon.fn/calls]
                                            [:seon.fn/sym 'sample.value-def/user-of])))
                             'sample.value-def/handle)
                  "a def's value used in a body is a call edge (20a87b1d1)")))))
      (finally (test-support/delete-recursively! root)))))

(deftest a-caller-files-findings-name-its-value-defs-entities
  ;; The *boot-progress!* class (boot 2026-09-26): an unchanged caller file's
  ;; reanalyzed findings attribute a lint to its computed private def, so that
  ;; def's entity must already be the analyzer's own entity. A callee signature change
  ;; selects the caller; its findings then reconcile against that entity.
  (let [prefix (str "pfd" (id/id))
        root (io/file "tmp" (str "fn-test-" prefix))
        directory (str "src/" prefix)
        callee (fn [body] (str "(ns " prefix ".callee)\n(defn produce " body ")\n"))
        request {:seon.fn/root (.getCanonicalPath root) :seon.fn/roots ["src"]}]
    (try
      (spit (doto (io/file root directory "callee.clj") io/make-parents) (callee "[] 1"))
      (spit (io/file root directory "caller.clj")
            (str "(ns " prefix ".caller (:require [" prefix ".callee :as callee]))\n"
                 "(def ^:private progress (constantly nil))\n"
                 "(defn run [] (callee/produce))\n"))
      (test-support/with-database
        (fn [connection]
          (test-support/transacted! connection (seon.fn/analyze-entities request))
          (let [value-def (symbol (str prefix ".caller") "progress")
                published (db/db connection)
                _ (spit (io/file root directory "callee.clj") (callee "([] 1) ([x] x)"))
                selected (assoc request :seon.source/previous-database published
                                        :seon.fn/changed-paths #{(str directory "/callee.clj")})
                result (test-support/refusal-data
                        #(seon.fn/index! (assoc selected :seon.db/connection connection
                                                         :seon.program/rows (seon.fn/analyze-entities selected))))
                database (db/db connection)
                entity (db/pull database [:db/id :seon.fn/defined-by] [:seon.fn/sym value-def])]
            (is (= 'clojure.core/def (:seon.fn/defined-by entity)) "the computed def is the analyzer's entity")
            (is (= test-support/committed result) (pr-str result))
            (is (some #(= (:db/id entity) (get-in % [:seon.lint/fn :db/id]))
                      (db/q '[:find [(pull ?lint [{:seon.lint/fn [:db/id]}]) ...] :in $ ?path
                              :where [?file :seon.fn.file/relative-path ?path] [?lint :seon.lint/file ?file]]
                            database (str directory "/caller.clj")))
                "the caller's reanalyzed finding names the value def's entity"))))
      (finally (test-support/delete-recursively! root)))))

(deftest an-analyzer-launch-loaded-as-other-bytes-reanalyzes-everything
  ;; The loaded analyzer's identity derives from what this JVM loaded, never
  ;; from the prior head's entities alone (seon.cluster/launched-analyzer).
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            launched-analyzer #'cluster/launched-analyzer
            files (vec (distinct (map #(nth % 2) (#'cluster/analyzer-declarations database))))
            stored (source/stored-path-digests database files)
            head (#'cluster/analyzer-digest database)
            other (apply str (repeat 64 "0"))]
        (is (contains? stored "src/seon/fn.clj") "the indexer's own file is an analyzer file")
        (is (= {:seon.source/analyzer-digest head}
               (launched-analyzer database (assoc stored "src/seon/cluster.clj" other)))
            "launch bytes the head published, and any file outside the analyzer, stay incremental")
        (let [ahead (launched-analyzer database (assoc stored "src/seon/fn.clj" other))]
          (is (not= head (:seon.source/analyzer-digest ahead))
              "an analyzer file loaded as other bytes matches no seal, so every file reanalyzes")
          (is (= other (get (:seon.cluster/launched-analyzer ahead) "src/seon/fn.clj"))
              "and the publication seals the entities it analyzed with those bytes"))))))
