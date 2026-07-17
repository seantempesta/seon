(ns seon.db.program-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db.program :as program]))

(def edge-a
  {:seon.ns.require/target :my.dep
   :seon.ns.require/alias 'dep})

(def edge-b
  {:seon.ns.require/target :seon.db
   :seon.ns.require/refers #{'query 'pull}})

(def namespace-row
  {:seon.ns/name :my.app
   :seon.ns/source "(ns my.app (:require [my.dep :as dep]))"
   :seon.ns/require-edges [edge-a edge-b]})

(def function-row
  {:seon.fn/sym "my.app/run"
   :seon.fn/ns [:seon.ns/name :my.app]
   :seon.fn/source "(defn run [] (dep/value))"
   :seon.fn/spec "[:=> [:cat] :int]"
   :seon.fn/doc "Run."
   :seon.fn/arglists "([])"
   :seon.fn/private? false
   :seon.fn/agent-facing? true
   :seon.fn/created-at #inst "2026-07-16T12:00:00.000-00:00"})

(def schema-row
  {:seon.schema/key :my.app/value
   :seon.schema/form ":int"
   :seon.schema/ns [:seon.ns/name :my.app]
   :seon.db.id/generator :seon.db.id.generator/human-readable
   :seon.schema/created-at #inst "2026-07-16T12:00:00.000-00:00"})

(def desired [namespace-row function-row schema-row])

(def current-function
  ["my.app/run"
   "(defn run [] (dep/value))"
   "[:=> [:cat] :int]"
   "Run."
   "([])"
   false
   true])

(def current-schema
  [:my.app/value ":int" :seon.db.id.generator/human-readable])

(def current-namespace
  [:my.app
   "(ns my.app (:require [my.dep :as dep]))"
   {:seon.ns/require-edges
    [(assoc edge-a :db/id 101)
     (assoc edge-b :db/id 102)]}])

(def empty-current
  {:current-functions []
   :boot-functions #{}
   :current-schemas []
   :boot-schemas #{}
   :current-namespaces []
   :boot-program-rows []
   :agent-ids []})

(def converged
  {:current-functions [current-function]
   :boot-functions #{"my.app/run"}
   :current-schemas [current-schema]
   :boot-schemas #{:my.app/value}
   :current-namespaces [current-namespace]
   :boot-program-rows
   [[1 :seon.ns/name :my.app (:seon.ns/source namespace-row)]
    [2 :seon.fn/sym "my.app/run" (:seon.fn/source function-row)]
    [3 :seon.schema/key :my.app/value (:seon.schema/form schema-row)]]
   :agent-ids []})

(defn- query-results [current]
  [(:current-functions current)
   (:boot-functions current)
   (:current-schemas current)
   (:boot-schemas current)
   (:current-namespaces current)
   (:boot-program-rows current)
   (:agent-ids current)])

(defn- compile-program
  ([current] (compile-program current desired))
  ([current desired-program]
   (let [remaining (atom (query-results current))]
     (with-redefs [d/q (fn [_query db-value]
                         (when-not (= ::database db-value)
                           (throw (ex-info "compiler queried another database"
                                           {:database db-value})))
                         (let [result (first @remaining)]
                           (swap! remaining subvec 1)
                           result))]
       (let [tx-data (program/compile-tx-data ::database desired-program)]
         (when (seq @remaining)
           (throw (ex-info "compiler skipped a current population"
                           {:remaining @remaining})))
         tx-data)))))

(deftest real-empty-datahike-value-compiles-the-complete-program
  (let [config {:store {:backend :memory :id (random-uuid)}
                :schema-flexibility :write
                :keep-history? true}]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (is (= (compile-program empty-current)
               (program/compile-tx-data (d/db connection) desired))
            "all authority queries execute against one real immutable value")
        (finally
          (d/release connection)
          (d/delete-database config))))))

(deftest fresh-partial-and-converged-programs-produce-exact-deltas
  (testing "a fresh database receives the complete desired population"
    (is (= [(dissoc namespace-row :seon.ns/require-edges)
            (dissoc function-row :seon.fn/created-at)
            (dissoc schema-row :seon.schema/created-at)
            {:seon.ns/name :my.app
             :seon.ns/require-edges [edge-a edge-b]}]
           (compile-program empty-current))))

  (testing "a converged database creates no transaction"
    (is (= [] (compile-program converged))))

  (testing "a partial database receives only the missing function"
    (is (= [(dissoc function-row :seon.fn/created-at)]
           (compile-program
            (-> converged
                (assoc :current-functions [])
                (assoc :boot-functions #{})))))))

(deftest drift-repairs-fields-components-and-removed-optional-values
  (let [desired-function
        (dissoc function-row :seon.fn/spec :seon.fn/agent-facing?)
        desired-schema (dissoc schema-row :seon.db.id/generator)
        next-edge {:seon.ns.require/target :my.next
                   :seon.ns.require/alias 'next}
        desired-namespace
        (assoc namespace-row :seon.ns/require-edges [next-edge])
        tx (compile-program converged
                            [desired-namespace desired-function desired-schema])]
    (is (= [(dissoc desired-function :seon.fn/created-at)
            (dissoc desired-schema :seon.schema/created-at)
            [:db/retractEntity 101]
            [:db/retractEntity 102]
            {:seon.ns/name :my.app
             :seon.ns/require-edges [next-edge]}
            [:db/retract [:seon.fn/sym "my.app/run"]
             :seon.fn/spec "[:=> [:cat] :int]"]
            [:db/retract [:seon.fn/sym "my.app/run"]
             :seon.fn/agent-facing? true]
            [:db/retract [:seon.schema/key :my.app/value]
             :seon.db.id/generator
             :seon.db.id.generator/human-readable]]
           tx))))

(deftest stale-boot-entities-retract-but-agent-homes-and-runtime-data-survive
  (let [stale
        [[70 :seon.test/sym "my.old/check" "(deftest check (is true))"]
         [40 :seon.ns/name :my.old "(ns my.old)"]
         [60 :seon.schema/key :my.old/value ":string"]
         [50 :seon.fn/sym "my.old/run" "(defn run [] :old)"]
         [80 :seon.ns/name :my.agent.root "(ns my.agent.root)"]]
        tx
        (compile-program
         (assoc converged
                :boot-program-rows
                (into (:boot-program-rows converged) stale)
                :agent-ids ["root"]))
        runtime-authored-current
        ["my.runtime/keep" "(defn keep [] true)" "" "Keep." "([])"
         false false]
        with-runtime
        (compile-program
         (update converged :current-functions conj runtime-authored-current))
        runtime-override
        (compile-program
         (-> converged
             (assoc :boot-functions #{})
             (assoc :boot-schemas #{})
             (assoc :current-functions
                    [(assoc current-function 1 "(defn run [] :runtime)")])
             (assoc :current-schemas
                    [[:my.app/value ":string"
                      :seon.db.id.generator/absent]])))]
    (is (= [[:db.fn/retractEntity 40]
            [:db.fn/retractEntity 50]
            [:db.fn/retractEntity 60]
            [:db.fn/retractEntity 70]]
           tx))
    (is (= [] with-runtime)
        "a current function absent from boot provenance is not swept")
    (is (= [] runtime-override)
        "runtime-authored current function and schema identities are protected")))

(deftest absent-required-population-refuses-removal
  (doseq [[attribute rows]
          [[:seon.ns/name [function-row schema-row]]
           [:seon.fn/sym [namespace-row schema-row]]
           [:seon.schema/key [namespace-row function-row]]]]
    (let [failure
          (try
            (compile-program empty-current rows)
            nil
            (catch clojure.lang.ExceptionInfo exception exception))]
      (is (= attribute (::program/missing-program-population
                        (ex-data failure)))))))

(deftest transaction-output-is-independent-of-input-row-order
  (let [stale [[70 :seon.test/sym "my.old/check" "test"]
               [40 :seon.ns/name :my.old "namespace"]]
        current (assoc converged :boot-program-rows stale)
        reversed-current (assoc current :boot-program-rows (vec (reverse stale)))]
    (is (= (compile-program empty-current desired)
           (compile-program empty-current (vec (reverse desired)))))
    (is (= (compile-program current)
           (compile-program reversed-current)))))
