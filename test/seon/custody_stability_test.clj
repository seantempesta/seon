(ns seon.custody-stability-test
  "Standing database checks for the current custody-returning surface."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
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

(def ^:private namespace-assertions-query
  '[:find ?namespace-name ?source-tx
    :where
    [?namespace :seon.ns/name ?namespace-name]
    [?namespace :seon.ns/source _ ?source-tx]])

(defn- loaded-core-namespaces
  [db]
  (into (sorted-map)
        (keep
         (fn [[namespace-name source-tx]]
           (let [admission
                 (schema/admission-from-asserting-transaction db source-tx)]
             (when (and (= :core (:seon.schema.admission/source admission))
                        (find-ns namespace-name))
               [namespace-name (find-ns namespace-name)]))))
        (d/q namespace-assertions-query db)))

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

(deftest acquired-first-party-reachability-is-exactly-public
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            host-namespaces (loaded-core-namespaces db)
            expected
            (into (sorted-map)
                  (map (fn [[namespace-name host-namespace]]
                         [namespace-name
                          (into (sorted-set)
                                (keys (ns-publics host-namespace)))]))
                  host-namespaces)
            private
            (into (sorted-map)
                  (keep
                   (fn [[namespace-name host-namespace]]
                     (let [names
                           (into (sorted-set)
                                 (comp
                                  (filter (comp :private meta val))
                                  (map key))
                                 (ns-interns host-namespace))]
                       (when (seq names) [namespace-name names]))))
                  host-namespaces)
            installed
            (select-keys
             (sci/namespace-interns (eval/cluster-ctx db connection))
             (keys host-namespaces))
            leaked-private
            (into (sorted-map)
                  (keep
                   (fn [[namespace-name private-names]]
                     (let [leaked
                           (into (sorted-set)
                                 (filter (get installed namespace-name))
                                 private-names)]
                       (when (seq leaked) [namespace-name leaked]))))
                  private)]
        (is (seq host-namespaces)
            "a missing loaded program graph is failure, never a healthy census")
        (is (seq private)
            "the census must exercise first-party namespaces with private Vars")
        (is (= expected installed)
            (pr-str {:seon.custody-stability/expected expected
                     :seon.custody-stability/installed installed}))
        (is (empty? leaked-private)
            (pr-str {:seon.custody-stability/leaked-private
                     leaked-private}))))))
