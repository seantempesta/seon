(ns seon.custody-stability-test
  "Standing database checks for the current custody-returning surface."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

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

(def ^:private relocated-root-vars
  {'seon.cluster/running-instances 'running-instances
   'seon.cluster/root-store-holder 'root-store-holder
   'seon.cluster.store/held-flocks 'held-flocks})

(def ^:private foreign-context-form-generator
  (gen/fmap
   (fn [[suffix value]]
     [(str "(+ " value " 1)")
      (str "(def foreign-value-" suffix " " value ")")
      (str "(defn foreign-function-" suffix " [] " value ")")
      (str "(do (in-ns 'foreign.generated-" suffix ") "
           "(def local-value " value "))")
      (str "(ns-unmap 'user 'foreign-value-" suffix ")")
      "@@#'seon.cluster/running-instances"
      "@@#'seon.cluster/root-store-holder"
      "@@#'seon.cluster.store/held-flocks"
      "(resolve 'seon.operator.runtime/running-instances)"
      "(seon.db/q '[:find (count ?e) . :where [?e :seon.cluster/name]])"])
   (gen/tuple (gen/choose 0 1000000)
              (gen/choose -1000000 1000000))))

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

(defn- var-root
  [value]
  (when (or (instance? clojure.lang.Var value)
            (sci.utils/var? value))
    (try
      {:seon.custody-stability/bound? true
       :seon.custody-stability/value @value}
      (catch Throwable _
        {:seon.custody-stability/bound? false}))))

(defn- context-snapshot
  [ctx]
  (let [namespace-state (sci/namespace-state ctx)]
    {:seon.custody-stability/namespace-state namespace-state
     :seon.custody-stability/var-roots
     (into (sorted-map)
           (mapcat
            (fn [[namespace-name namespace-map]]
              (keep
               (fn [[intern-name value]]
                 (when-let [root (var-root value)]
                   [[namespace-name intern-name] root]))
               namespace-map)))
           namespace-state)}))

(defn- evaluate-in
  [ctx source]
  (eval/evaluate
   {:seon.cluster.run.form/source source
    :seon.cluster.run.form/ns [:seon.ns/name 'user]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps caps
    :seon.sci.eval/time-limit-ms 5000
    :seon.config/on-core-error :panic}))

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
            installed-all (sci/namespace-interns
                           (eval/cluster-ctx db connection))
            installed
            (select-keys
             installed-all
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
        (is (not (contains? host-namespaces 'seon.operator.runtime))
            "operator custody has no indexed program-graph namespace row")
        (is (not (contains? installed-all 'seon.operator.runtime))
            "the operator-owned namespace is never installed into SCI")
        (doseq [[consumer-symbol referred-name] relocated-root-vars
                :let [consumer-namespace (symbol (namespace consumer-symbol))
                      root-var (ns-resolve consumer-namespace referred-name)]]
          (is (= 'seon.operator.runtime
                 (some-> root-var meta :ns ns-name))
              (pr-str {:seon.custody-stability/consumer consumer-symbol
                       :seon.custody-stability/root-var root-var})))
        (is (= expected installed)
            (pr-str {:seon.custody-stability/expected expected
                     :seon.custody-stability/installed installed}))
        (is (empty? leaked-private)
            (pr-str {:seon.custody-stability/leaked-private
                     leaked-private}))))))

(deftest foreign-context-integrity-is-invariant-under-agent-evaluation
  (let [property
        (prop/for-all
         [sources foreign-context-form-generator]
         (test-support/with-database
           (fn [connection-a]
             (test-support/with-database
               (fn [connection-b]
                 (let [ctx-a (eval/cluster-ctx @connection-a connection-a)
                       ctx-b (eval/cluster-ctx @connection-b connection-b)
                       before (context-snapshot ctx-b)]
                   (every?
                    true?
                    (map (fn [source]
                           (evaluate-in ctx-a source)
                           (= before (context-snapshot ctx-b)))
                         sources))))))))
        check (tc/quick-check 10 property :seed 4303020260802)]
    (is (:result check) (pr-str check))))
