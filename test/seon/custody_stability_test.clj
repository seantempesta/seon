(ns seon.custody-stability-test
  "Standing database checks for the current custody-returning surface."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.test-support :as test-support]))

(def ^:private custody-output-schema-keys
  #{:seon.store/store
    :seon.store/branch-connection
    :seon.sci.eval/ctx})

(def ^:private expected-custody-returning-functions
  #{["seon.cluster.store/open-branch!" :seon.store/branch-connection]
    ["seon.cluster.store/open-store!" :seon.store/store]
    ["seon.sci.eval/build-base-ctx" :seon.sci.eval/ctx]
    ["seon.sci.eval/cluster-ctx" :seon.sci.eval/ctx]})

(def ^:private custody-returning-query
  '[:find ?function-symbol ?schema-key
    :in $ [?schema-key ...]
    :where
    [?schema :seon.schema/key ?schema-key]
    [?arity :seon.fn.arity/output-refs ?schema]
    [?function :seon.fn/arities ?arity]
    [?function :seon.fn/private? false]
    [?function :seon.fn/sym ?function-symbol]])

(deftest public-custody-returning-surface-is-derived-and-exact
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            public-contracted-functions
            (d/q '[:find (count ?function) .
                   :where
                   [?function :seon.fn/private? false]
                   [?function :seon.fn/arities]]
                 db)
            actual
            (d/q custody-returning-query db custody-output-schema-keys)]
        (is (pos? public-contracted-functions)
            "a missing program graph is failure, never a healthy empty census")
        ;; Exact is deliberate: a maximum would let one of today's custody
        ;; returners disappear silently, while this snapshot makes both a fifth
        ;; function and any removal demand an explicit custody review.
        (is (= expected-custody-returning-functions actual)
            (pr-str {:seon.custody-stability/actual actual}))))))
