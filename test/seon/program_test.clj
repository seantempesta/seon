(ns seon.program-test
  "Recurring proof for the one build/runtime declaration contract."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.run :as run]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.sci.reader :as reader]
            [seon.test-support :as test-support]))

(defn- one-event
  [source]
  (let [events (reader/read {:seon.sci.reader/text source
                             :seon.sci.reader/ns 'sample})]
    (is (vector? events) (str "reader refused " source))
    (is (= 1 (count events)) (str "reader split " source))
    (first events)))

(defn- refusal-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- parsed-contract
  [function-symbol spec forms]
  (let [projection (schema/build-projection forms
                                            {(symbol function-symbol) spec})]
    (program/contract-facts
     {:seon.program/function-symbol function-symbol
      :seon.program/spec (pr-str spec)
      :seon.program/compile-options
      (:seon.schema.projection/compile-options projection)
      :seon.program/predicate-functions
      (:seon.schema.projection/predicate-functions projection)
      :seon.program/schema-keys (set (keys forms))})))

(defn- nested-maps
  [value]
  (filter map? (tree-seq coll? seq value)))

(deftest function-contracts-compile-once-into-complete-query-facts
  (let [forms {:sample/key :keyword
               :sample/value :int
               :sample/enum-value :string}
        spec
        [:function
         [:=> [:cat [:map-of :sample/key :sample/value]] :sample/value]
         [:=> [:cat [:repeat {:min 1 :max 2} :sample/value]]
          [:enum :sample/enum-value :other]]]
        facts (parsed-contract "sample/complete" spec forms)
        arities (:seon.fn/arities facts)
        nodes (nested-maps (:seon.fn/ast facts))
        map-of-node (first (filter #(= ":map-of"
                                      (:seon.fn.ast/type %))
                                   nodes))
        enum-node (first (filter #(= ":enum" (:seon.fn.ast/type %))
                                 nodes))]
    (testing "one compiled contract yields Malli's exact ordered arities"
      (is (= [{:seon.fn.arity/order 0
               :seon.fn.arity/arity "1"
               :seon.fn.arity/min 1
               :seon.fn.arity/max 1}
              {:seon.fn.arity/order 1
               :seon.fn.arity/arity ":varargs"
               :seon.fn.arity/min 1
               :seon.fn.arity/max 2}]
             (mapv #(select-keys %
                                 [:seon.fn.arity/order
                                  :seon.fn.arity/arity
                                  :seon.fn.arity/min
                                  :seon.fn.arity/max])
                   arities))))
    (testing "the complete Malli AST vocabulary preserves key and enum values"
      (is (= {:seon.fn.ast/type ":malli.core/schema"
              :seon.fn.ast/ref [:seon.schema/key :sample/key]}
             (select-keys (:seon.fn.ast/key map-of-node)
                          [:seon.fn.ast/type :seon.fn.ast/ref])))
      (is (= [":sample/enum-value" ":other"]
             (mapv :seon.fn.ast.entry/value-edn
                   (sort-by :seon.fn.ast.entry/order
                            (:seon.fn.ast/values enum-node))))))
    (testing "role refs come only from RefSchema observations"
      (is (= #{[:seon.schema/key :sample/key]
               [:seon.schema/key :sample/value]}
             (:seon.fn.arity/input-refs (first arities))))
      (is (= #{[:seon.schema/key :sample/value]}
             (:seon.fn.arity/output-refs (first arities))))
      (is (= #{[:seon.schema/key :sample/value]}
             (:seon.fn.arity/input-refs (second arities))))
      (is (nil? (:seon.fn.arity/output-refs (second arities)))
          "an enum scalar equal to a schema key is not a reference"))
    (testing "the expansion is deterministic"
      (is (= facts (parsed-contract "sample/complete" spec forms))))))

(deftest function-contract-role-refs-follow-local-registries
  (let [forms {:sample/value :int}
        spec [:=> {:registry {:local/value :sample/value}}
              [:cat :local/value]
              :sample/value]
        facts (parsed-contract "sample/local-registry" spec forms)
        arity (first (:seon.fn/arities facts))
        direct-ref-nodes
        (filter :seon.fn.ast/ref (nested-maps (:seon.fn/ast facts)))]
    (is (= #{[:seon.schema/key :sample/value]}
           (:seon.fn.arity/input-refs arity)))
    (is (= #{[:seon.schema/key :sample/value]}
           (:seon.fn.arity/output-refs arity)))
    (is (= #{[:seon.schema/key :sample/value]}
           (into #{} (map :seon.fn.ast/ref) direct-ref-nodes)))))

(deftest function-contract-redefinition-replaces-component-facts-exactly
  (test-support/with-database
    (fn [connection]
      (let [function-symbol "sample/redefined"
            old-spec [:function
                      [:=> [:cat :int] :int]
                      [:=> [:cat :int :int] :int]]
            new-spec [:=> [:cat :string] :string]
            old-row
            (merge {:seon.fn/sym function-symbol
                    :seon.fn/spec (pr-str old-spec)}
                   (parsed-contract function-symbol old-spec {}))
            new-row
            (merge {:seon.fn/sym function-symbol
                    :seon.fn/spec (pr-str new-spec)}
                   (parsed-contract function-symbol new-spec {}))]
        (db/transact! connection [old-row])
        (let [current (db/pull @connection '[*]
                              [:seon.fn/sym function-symbol])
              old-components
              (into #{(get-in current [:seon.fn/ast :db/id])}
                    (map :db/id)
                    (:seon.fn/arities current))]
          (db/transact! connection
                      (program/exact-replacement-tx current new-row))
          (let [redefined
                (db/pull @connection
                        [:seon.fn/spec
                         {:seon.fn/arities
                          [:seon.fn.arity/order :seon.fn.arity/min
                           :seon.fn.arity/max]}]
                        [:seon.fn/sym function-symbol])]
            (is (= (pr-str new-spec) (:seon.fn/spec redefined)))
            (is (= [{:seon.fn.arity/order 0
                     :seon.fn.arity/min 1
                     :seon.fn.arity/max 1}]
                   (:seon.fn/arities redefined)))
            (is (every? #(empty? (db/datoms @connection :eavt %))
                        old-components))))))))

(deftest identical-runtime-redeclaration-builds-no-datoms
  (test-support/with-database
    (fn [connection]
      (let [function-symbol "sample/idempotent"
            spec [:=> [:cat :int] :int]
            row (merge {:seon.fn/sym function-symbol
                        :seon.fn/ns [:seon.ns/name 'sample]
                        :seon.fn/source
                        "(defn idempotent {:malli/schema [:=> [:cat :int] :int]} [x] x)"
                        :seon.fn/arglists "([x])"
                        :seon.fn/private? false
                        :seon.fn/spec (pr-str spec)}
                       (parsed-contract function-symbol spec {}))
            row-tx (ns-resolve 'seon.cluster.run 'row-tx)]
        (db/transact! connection [{:seon.ns/name 'sample
                                   :seon.ns/source "(ns sample)"}])
        (db/transact! connection (row-tx @connection {} row))
        (let [before (db/pull @connection '[*]
                              [:seon.fn/sym function-symbol])
              replacement (row-tx @connection {} row)]
          (is (empty? replacement))
          (is (= before
                 (db/pull @connection '[*]
                          [:seon.fn/sym function-symbol]))))))))

(deftest changed-runtime-redeclaration-builds-a-real-replacement
  (test-support/with-database
    (fn [connection]
      (let [function-symbol "sample/redefined"
            spec [:=> [:cat :int] :int]
            original
            (merge {:seon.fn/sym function-symbol
                    :seon.fn/ns [:seon.ns/name 'sample]
                    :seon.fn/source
                    "(defn redefined {:malli/schema [:=> [:cat :int] :int]} [x] x)"
                    :seon.fn/arglists "([x])"
                    :seon.fn/private? false
                    :seon.fn/spec (pr-str spec)}
                   (parsed-contract function-symbol spec {}))
            changed
            (assoc original :seon.fn/source
                   "(defn redefined {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
            row-tx (ns-resolve 'seon.cluster.run 'row-tx)
            declared-content (ns-resolve 'seon.cluster.run 'declared-content)]
        (db/transact! connection [{:seon.ns/name 'sample
                                   :seon.ns/source "(ns sample)"}])
        (db/transact! connection (row-tx @connection {} original))
        (let [current (db/pull @connection '[*]
                               [:seon.fn/sym function-symbol])
              replacement (row-tx @connection {} changed)]
          (is (not= (declared-content @connection current)
                    (declared-content @connection changed))
              "declared content receives the database before the row")
          (is (seq replacement))
          (db/transact! connection replacement)
          (is (= (:seon.fn/source changed)
                 (:seon.fn/source
                  (db/pull @connection [:seon.fn/source]
                           [:seon.fn/sym function-symbol])))))))))

(deftest reader-events-have-one-canonical-declaration-row
  (let [cases
        [{:label "contracted function"
          :source
          "(defn ^{:malli/schema [:=> [:cat :int] :int]} plus-one [x] (inc x))"
          :expected
          {:seon.fn/sym "sample/plus-one"
           :seon.fn/ns [:seon.ns/name 'sample]
           :seon.fn/source
           "(defn ^{:malli/schema [:=> [:cat :int] :int]} plus-one [x] (inc x))"
           :seon.fn/arglists "([x])"
           :seon.fn/private? false
           :seon.fn/spec "[:=> [:cat :int] :int]"}}
         {:label "private uncontracted function"
          :source "(defn- helper [x] x)"
          :expected
          {:seon.fn/sym "sample/helper"
           :seon.fn/ns [:seon.ns/name 'sample]
           :seon.fn/source "(defn- helper [x] x)"
           :seon.fn/arglists "([x])"
           :seon.fn/private? true}}
         {:label "schema"
          :source "(seon.schema/register! ::amount [:int {:min 0}])"
          :expected
          {:seon.schema/key :sample/amount
           :seon.schema/form "[:int {:min 0}]"}}
         {:label "test"
          :source "(clojure.test/deftest smoke (clojure.test/is true))"
          :expected
          {:seon.test/sym "sample/smoke"
           :seon.test/ns [:seon.ns/name 'sample]
           :seon.test/source
           "(clojure.test/deftest smoke (clojure.test/is true))"}}]]
    (doseq [{:keys [label source expected]} cases]
      (testing label
        (let [event (one-event source)]
          ;; Expected data is literal. It is not produced by another path that
          ;; shares `seon.program`'s canonicalizer.
          (is (= expected (program/declaration-row event :all)))
          (if (= "private uncontracted function" label)
            (is (nil? (program/declaration-row event :contracted)))
            (is (= expected
                   (program/declaration-row event :contracted)))))))))

(deftest declaration-admission-refuses-ambiguous-or-incomplete-rows
  (testing "one event cannot claim two declaration identity families"
    (let [data
          (refusal-data
           #(program/canonical-row
             {:seon.fn/sym "sample/f"
              :seon.fn/ns [:seon.ns/name 'sample]
              :seon.fn/source "(defn f [] 1)"
              :seon.fn/arglists "([])"
              :seon.fn/private? false
              :seon.test/sym "sample/f"
              :seon.test/ns [:seon.ns/name 'sample]
              :seon.test/source "(deftest f)"}))]
      (is (= :seon.program/declaration-refused (:seon.error/kind data)))
      (is (= [[:seon.fn/sym "sample/f"]
              [:seon.test/sym "sample/f"]]
             (:seon.program/identities data)))))
  (testing "a recognized family without its reader-required data is loud"
    (doseq [event [{:seon.schema/key :sample/missing-form}
                   {:seon.test/sym "sample/missing-source"
                    :seon.test/ns [:seon.ns/name 'sample]}]]
      (let [data (refusal-data #(program/declaration-row event :all))]
        (is (= :seon.program/declaration-refused
               (:seon.error/kind data)))
        (is (= [(program/row-identity event)]
               (:seon.program/identities data)))))))

(deftest optional-attributes-are-replaced-exactly
  (let [current {:seon.fn/sym "sample/f"
                 :seon.fn/ns [:seon.ns/name 'sample]
                 :seon.fn/source "(defn f [] 1)"
                 :seon.fn/arglists "([])"
                 :seon.fn/private? false
                 :seon.fn/doc "old"
                 :seon.fn/spec "[:=> [:cat] :int]"
                 :seon.fn/calls [[:seon.fn/sym "sample/old"]]
                 :seon.fn/workload :compute}
        desired {:seon.fn/sym "sample/f"
                 :seon.fn/ns [:seon.ns/name 'sample]
                 :seon.fn/source "(defn f [] 2)"
                 :seon.fn/arglists "([])"
                 :seon.fn/private? false}]
    (is (= #{:seon.fn/source :seon.fn/doc :seon.fn/spec
             :seon.fn/calls :seon.fn/workload}
           (set (program/changed-attributes current desired)))))
  (is (= {:seon.test/sym "sample/property"
          :seon.test/ns [:seon.ns/name 'sample]
          :seon.test/source "(deftest property)"
          :seon.fn/calls [[:seon.fn/sym "sample/helper"]]
          :seon.test/subject [:seon.fn/sym "sample/subject"]}
         (program/canonical-row
          {:seon.test/sym "sample/property"
           :seon.test/ns [:seon.ns/name 'sample]
           :seon.test/source "(deftest property)"
           :seon.fn/calls [[:seon.fn/sym "sample/helper"]]
           :seon.test/subject [:seon.fn/sym "sample/subject"]
           :unowned/value :ignored}))))

(deftest schema-row-properties-survive-and-retract-exactly
  (let [current {:seon.schema/key :sample/error
                 :seon.schema/form "[:map {:seon.error/class true}]"
                 :seon.schema.admission/source :agent
                 :seon.error/class true
                 :seon.render/ai 'sample/render-ai}
        desired (dissoc current :seon.render/ai)]
    (is (= current (program/canonical-row current)))
    (is (= [:seon.render/ai]
           (program/changed-attributes current desired)))))

(deftest runtime-schema-declarations-project-namespaced-properties
  (let [event (one-event
               "(seon.schema/register! ::error [:map {:seon.error/class true :seon.render/ai sample/render-ai} [:seon.error/message :seon.error/message]])")
        row (program/declaration-row
             (assoc event :seon.schema.admission/source :agent)
             :contracted)]
    (is (= true (:seon.error/class row)))
    (is (= 'sample/render-ai (:seon.render/ai row)))
    (is (= :agent (:seon.schema.admission/source row)))))

(deftest arbitrary-qualified-deftest-is-not-a-test-declaration
  (let [source (str "(ns sample (:require [clojure.test :refer [deftest]] "
                    "[foo :as foo]))\n"
                    "(foo/deftest impostor)\n"
                    "(deftest real-test)")
        events (reader/read {:seon.sci.reader/text source})]
    (is (vector? events))
    (is (= #{"sample/real-test"}
           (into #{} (keep :seon.test/sym) events)))))

(deftest typed-cross-namespace-deletion-retracts-function-and-test
  (test-support/with-database
    (fn [connection]
      (let [now (java.util.Date.)
            namespace-name 'my.agents.registration-test
            namespace-ref [:seon.ns/name namespace-name]
            function-sym "my.agents.registration-test/same-name"
            deletion
            (program/deletion-row
             (one-event
              "(ns-unmap 'my.agents.registration-test 'same-name)"))
            settlement
            {:seon.cluster.run/id "registration-delete"
             :seon.cluster.eval/ordinal 0
             :seon.cluster.eval/result-edn "nil"
             :seon.cluster.eval/ns
             [:seon.ns/name 'my.agents.someone-else]
             :seon.program/row deletion}]
        (db/transact!
         connection
         [{:seon.ns/name namespace-name
           :seon.ns/source "(ns my.agents.registration-test)"}
          {:seon.ns/name 'my.agents.someone-else
           :seon.ns/source "(ns my.agents.someone-else)"}
          {:seon.cluster.agent/id "registration-test"
           :seon.cluster.agent/namespace namespace-ref}
          {:seon.fn/sym function-sym
           :seon.fn/ns namespace-ref
           :seon.fn/source "(defn same-name [] 1)"
           :seon.fn/arglists "([])"
           :seon.fn/private? false
           :seon.fn/spec "[:=> [:cat] :int]"}
          {:seon.test/sym function-sym
           :seon.test/ns namespace-ref
           :seon.test/source "(clojure.test/deftest same-name)"}])
        (db/transact!
         connection
         (run/open-tx {:seon.cluster.run/id "registration-delete"
                       :seon.cluster.run/agent
                       [:seon.cluster.agent/id "registration-test"]
                       :seon.cluster.run/opened-at now}))
        (db/transact!
         connection
         (run/receipt-start-tx
          {:seon.cluster.run/id "registration-delete"
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at now}))
        (is (= {:seon.program/delete-identities
                [[:seon.fn/sym function-sym]
                 [:seon.test/sym function-sym]]
                :seon.program/source
                "(ns-unmap 'my.agents.registration-test 'same-name)"
                :seon.program/ns namespace-ref}
               deletion))
        (db/transact! connection (run/receipt-settle-tx settlement))
        (is (nil? (db/pull @connection [:db/id]
                          [:seon.fn/sym function-sym])))
        (is (nil? (db/pull @connection [:db/id]
                          [:seon.test/sym function-sym])))))))

(deftest schema-unregister-is-one-global-typed-deletion
  (let [event (one-event
               "(seon.schema/unregister! :shared.schema/amount)")]
    (is (= :shared.schema/amount
           (:seon.sci.reader/schema-unregister-key event)))
    (is (= {:seon.program/delete-identities
            [[:seon.schema/key :shared.schema/amount]]
            :seon.program/source
            "(seon.schema/unregister! :shared.schema/amount)"}
           (program/deletion-row event)))))
