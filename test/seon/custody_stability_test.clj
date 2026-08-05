(ns seon.custody-stability-test
  "Standing database checks for the current custody-returning surface."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(def ^:private custody-output-schema-keys
  #{:seon.store/store
    :seon.db/connection
    :seon.sci.eval/ctx})

(def ^:private expected-custody-returning-functions
  #{["seon.cluster.store/open-branch!" :seon.db/connection]
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
        (db/q namespace-assertions-query db)))

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

(deftest indexed-custody-returning-surface-is-derived-and-exact
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            contracted-functions
            (db/q '[:find (count ?function) .
                   :where
                   [?function :seon.fn/arities]]
                 db)
            actual
            (db/q custody-returning-query db custody-output-schema-keys)]
        (is (pos? contracted-functions)
            "a missing program graph is failure, never a healthy empty census")
        ;; Exact is deliberate: a maximum would let one of today's custody
        ;; returners disappear silently, while this snapshot makes both a fifth
        ;; function and any removal demand an explicit custody review.
        (is (= expected-custody-returning-functions actual)
            (pr-str {:seon.custody-stability/actual actual}))))))

(deftest acquired-first-party-reachability-is-public-plus-every-indexed-function
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            host-namespaces (loaded-core-namespaces db)
            indexed-function-names
            (reduce
             (fn [by-namespace [function-symbol namespace-name]]
               (update by-namespace namespace-name (fnil conj #{})
                       (symbol (name (symbol function-symbol)))))
             {}
             (db/q '[:find ?function-symbol ?namespace-name
                     :where
                     [?function :seon.fn/sym ?function-symbol]
                     [?function :seon.fn/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   db))
            expected
            (into (sorted-map)
                  (map (fn [[namespace-name host-namespace]]
                         [namespace-name
                          (into (sorted-set)
                                (concat
                                 (keys (ns-publics host-namespace))
                                 (get indexed-function-names
                                      namespace-name)))]))
                  host-namespaces)
            installed-all (sci/namespace-interns
                           (eval/cluster-ctx db connection))
            installed
            (select-keys
             installed-all
             (keys host-namespaces))]
        (is (seq host-namespaces)
            "a missing loaded program graph is failure, never a healthy census")
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
                     :seon.custody-stability/installed installed}))))))

(deftest non-function-private-custody-roots-and-operator-state-stay-unreachable
  (test-support/with-database
    (fn [connection]
      (let [ctx (eval/cluster-ctx @connection connection)
            forbidden
            ['seon.cluster/running-instances
             'seon.cluster/root-store-holder
             'seon.cluster.store/held-flocks
             'seon.sci.eval/generator-ctx]
            installed (sci/namespace-interns ctx)]
        (doseq [qualified forbidden
                :let [namespace-name (symbol (namespace qualified))
                      local-name (symbol (name qualified))]]
          (is (nil? (get-in installed [namespace-name local-name]))
              (pr-str {:seon.custody-stability/forbidden qualified})))
        (is (nil? (:seon.sci.admit/value
                   (evaluate-in
                    ctx
                    "(resolve 'seon.operator.runtime/running-instances)"))))
        (doseq [source ["@@#'seon.cluster/running-instances"
                        "@@#'seon.cluster/root-store-holder"
                        "@@#'seon.cluster.store/held-flocks"
                        "@@#'seon.sci.eval/generator-ctx"]]
          (is (some? (:seon.cluster.eval/error (evaluate-in ctx source)))
              source))))))

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

(deftest cross-cluster-write-isolation
  (test-support/with-database
    (fn [connection-a]
      (test-support/with-database
        (fn [connection-b]
          (let [ctx-a (eval/cluster-ctx @connection-a connection-a)
                _ (sci/intern ctx-a 'user 'own-connection connection-a)
                _ (sci/intern ctx-a 'user 'foreign-connection connection-b)
                own
                (evaluate-in
                 ctx-a
                 (str "(seon.db/transact! own-connection "
                      "[{:seon.cluster.message/id \"custody-own\"}])"))
                ambient
                (evaluate-in
                 ctx-a
                 (str "(seon.db/transact! "
                      "[{:seon.cluster.message/id \"custody-ambient\"}])"))
                foreign
                (evaluate-in
                 ctx-a
                 (str "(seon.db/transact! foreign-connection "
                      "[{:seon.cluster.message/id \"custody-foreign\"}])"))
                message-ids
                (fn [connection]
                  (set
                   (db/q
                    '[:find [?id ...]
                      :where [_ :seon.cluster.message/id ?id]]
                    @connection)))]
            (is (nil? (:seon.cluster.eval/error own)))
            (is (nil? (:seon.cluster.eval/error ambient)))
            (is (= :seon.db/foreign-connection
                   (get-in foreign
                           [:seon.sci.admit/value :seon.error/kind])))
            (is (= #{"custody-own" "custody-ambient"}
                   (message-ids connection-a)))
            (is (empty? (message-ids connection-b)))))))))
