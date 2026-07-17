(ns seon.instrument-inject-test
  "The one eval-boundary dependency-injection contract."
  (:require
   [cljs.test :refer [deftest is use-fixtures]]
   [malli.core :as m]
   [seon.config :as config]
   [seon.db :as db]
   [seon.instrument :as inst]))

(def configuration (config/resolve-config-singleton {}))

(defn probe-injects
  "Return the optional dependencies received by the function body."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.agent/id {:optional true} :string]
      [:seon.config/configuration
       {:optional true}
       :seon.config/singleton]
      [:probe/x :int]]]
    [:map
     [:probe/got-id {:optional true} :string]
     [:probe/got-configuration? :boolean]
     [:probe/x :int]]]}
  [{id :seon.agent/id
    operation-configuration :seon.config/configuration
    x :probe/x}]
  (cond-> {:probe/got-configuration? (some? operation-configuration)
           :probe/x x}
    id (assoc :probe/got-id id)))

(defn probe-required-configuration
  "Return an ordinary required configuration argument unchanged."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.config/configuration :seon.config/singleton]]]
    :seon.config/singleton]}
  [{operation-configuration :seon.config/configuration}]
  operation-configuration)

(defn probe-no-inject
  "Return the size of a request that declares no dependency."
  {:malli/schema [:=> [:cat [:map [:probe/x :int]]] :int]}
  [request]
  (count request))

(def ^:private target-syms
  '#{seon.instrument-inject-test/probe-injects
     seon.instrument-inject-test/probe-required-configuration
     seon.instrument-inject-test/probe-no-inject})

(def ^:private targets
  [{::inst/sym 'seon.instrument-inject-test/probe-injects
    ::inst/schema-form (:malli/schema (meta #'probe-injects))}
   {::inst/sym 'seon.instrument-inject-test/probe-required-configuration
    ::inst/schema-form (:malli/schema (meta #'probe-required-configuration))}
   {::inst/sym 'seon.instrument-inject-test/probe-no-inject
    ::inst/schema-form (:malli/schema (meta #'probe-no-inject))}])

(defn- instrument-probes! []
  (inst/instrument-delta!
   {::inst/changed-syms target-syms
    ::inst/targets targets}))

(defn- uninstrument-probes! []
  (inst/instrument-delta!
   {::inst/changed-syms target-syms
    ::inst/targets []}))

(use-fixtures :once {:before instrument-probes! :after uninstrument-probes!})

(def valid-id "INJECTtest0001")

(defn- with-operation [operation-configuration thunk]
  (db/without-agent
   (fn []
     (db/with-agent
      valid-id
      (fn []
        (db/with-tx-context
         {:seon.config/configuration operation-configuration}
         thunk))))))

(deftest declared-absent-dependencies-come-from-one-operation
  (let [result (with-operation configuration
                 #(probe-injects {:probe/x 1}))]
    (is (= valid-id (:probe/got-id result)))
    (is (true? (:probe/got-configuration? result)))
    (is (= 1 (:probe/x result)))))

(deftest caller-provided-agent-id-remains-inspectable
  (let [result (with-operation
                 configuration
                 #(probe-injects {:probe/x 2
                                  :seon.agent/id "OTHERagent0002"}))]
    (is (= "OTHERagent0002" (:probe/got-id result)))
    (is (true? (:probe/got-configuration? result)))))

(deftest context-only-configuration-cannot-be-substituted
  (let [error
        (try
          (with-operation
            configuration
            #(probe-injects
              {:probe/x 3
               :seon.config/configuration
               (assoc configuration :seon.agent.web/policy :open)}))
          nil
          (catch :default exception exception))]
    (is (= :seon.config/configuration
           (::inst/injectable-key (ex-data error))))
    (is (= :agent (:seon.error/kind (ex-data error))))))

(deftest operation-prefilled-configuration-is-idempotent
  (let [result
        (with-operation
          configuration
          #(probe-injects {:probe/x 4
                           :seon.config/configuration configuration}))]
    (is (true? (:probe/got-configuration? result)))
    (is (= 4 (:probe/x result)))))

(deftest missing-context-only-configuration-is-a-core-error
  (let [error
        (try
          (db/with-agent valid-id #(probe-injects {:probe/x 5}))
          nil
          (catch :default exception exception))]
    (is (= :seon.config/configuration
           (::inst/injectable-key (ex-data error))))
    (is (= :core-bug (:seon.error/kind (ex-data error))))))

(deftest required-configuration-remains-an-ordinary-argument
  (is (= configuration
         (probe-required-configuration
          {:seon.config/configuration configuration}))))

(deftest function-without-dependencies-is-untouched
  (is (= 1 (with-operation configuration #(probe-no-inject {:probe/x 9})))))

(deftest declared-injectables-select-only-optional-registry-keys
  (is (= #{:seon.agent/id :seon.config/configuration}
         (inst/declared-injectables
          (m/schema
           [:=>
            [:cat
             [:map
              [:seon.agent/id {:optional true} :string]
              [:seon.config/configuration
               {:optional true}
               :seon.config/singleton]]]
            :string]))))
  (is (= #{}
         (inst/declared-injectables
          (m/schema
           [:=>
            [:cat
             [:map
              [:seon.config/configuration :seon.config/singleton]]]
            :string])))
      "a required argument is explicit, not injectable")
  (is (= #{}
         (inst/declared-injectables
          (m/schema [:=> [:cat [:map [:probe/x :int]]] :string]))))
  (is (= #{}
         (inst/declared-injectables
          (m/schema [:=> [:cat :int] :string])))))
