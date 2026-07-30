(ns seon.program-test
  "Recurring proof for the one build/runtime declaration contract."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.run :as run]
            [seon.program :as program]
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
                 :seon.fn/workload :compute}
        desired {:seon.fn/sym "sample/f"
                 :seon.fn/ns [:seon.ns/name 'sample]
                 :seon.fn/source "(defn f [] 2)"
                 :seon.fn/arglists "([])"
                 :seon.fn/private? false}]
    (is (= #{:seon.fn/source :seon.fn/doc
             :seon.fn/spec :seon.fn/workload}
           (set (program/changed-attributes current desired))))))

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
             :seon.sci.eval/program-row deletion}]
        (d/transact
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
        (d/transact
         connection
         (run/open-tx {:seon.cluster.run/id "registration-delete"
                       :seon.cluster.run/agent
                       [:seon.cluster.agent/id "registration-test"]
                       :seon.cluster.run/opened-at now}))
        (d/transact
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
        (d/transact connection (run/receipt-settle-tx settlement))
        (is (nil? (d/pull @connection [:db/id]
                          [:seon.fn/sym function-sym])))
        (is (nil? (d/pull @connection [:db/id]
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
