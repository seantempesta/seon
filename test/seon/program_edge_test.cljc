(ns seon.program-edge-test
  "Canonical direct-edge facts exercised unchanged on CLJ and CLJS."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   #?(:clj [sci.core :as sci])
   #?(:clj [seon.host.eval :as host.eval])
   #?(:clj [seon.host.record :as host.record])
   [seon.program.edge :as edge]))

(def resolution
  {::edge/namespace 'fixture.edge
   ::edge/aliases {'db 'seon.db}
   ::edge/refers {'fetch 'seon.agent.web/fetch}
   ::edge/current-vars #{'subject 'helper}
   ::edge/core-vars #{'keyword}
   ::edge/known-namespaces
   #{'fixture.edge 'clojure.core 'seon.db 'seon.agent.web
     'seon.packages.js.lodash}
   ::edge/macro-symbols #{}
   ::edge/effects
   {'fixture.edge/helper :pure
    'clojure.core/keyword :pure
    'seon.db/transact! :idempotent
    'seon.db/query :read
    'seon.db/pull :read
    'seon.agent.web/fetch :external
    'seon.packages.js.lodash/get :external}})

(def fixture-form
  '(defn subject [x]
     (let [local helper]
       (local x))
     (db/transact!
      {:seon.db/tx-data
       [{:demo/id x :demo/name "name"}
        [:db/add [:demo/id x] :demo/score 1]]})
     (db/query
      '[:find ?name
        :where [?entity :demo/name ?name]])
     (db/pull '[*] [:demo/id x])
     (keyword "demo" x)
     (fetch {:seon.agent.web/url "https://example.test"})
     (seon.packages.js.lodash/get x "key")))

(def expected-calls
  #{"fixture.edge/helper"
    "clojure.core/keyword"
    "seon.db/transact!"
    "seon.db/query"
    "seon.db/pull"
    "seon.agent.web/fetch"
    "seon.packages.js.lodash/get"})

(def expected-effects
  {"fixture.edge/helper" :pure
   "clojure.core/keyword" :pure
   "seon.db/transact!" :idempotent
   "seon.db/query" :read
   "seon.db/pull" :read
   "seon.agent.web/fetch" :external
   "seon.packages.js.lodash/get" :external})

(defn- analyzed-bundle [form]
  (edge/analyze-function
   {::edge/function-symbol "fixture.edge/subject"
    ::edge/form form
    ::edge/resolution resolution}))

(defn- tx-bundle [tx-data]
  (let [function-ref [:seon.fn/sym "fixture.edge/subject"]
        function-map
        (first
         (filter #(and (map? %)
                       (= "fixture.edge/subject" (:seon.fn/sym %))
                       (contains? % ::edge/generation))
                 tx-data))
        values
        (fn [attribute]
          (into #{}
                (keep (fn [operation]
                        (when (and (vector? operation)
                                   (= :db/add (first operation))
                                   (= function-ref (second operation))
                                   (= attribute (nth operation 2 nil)))
                          (nth operation 3 nil))))
                tx-data))
        required-by-terminal
        (reduce
         (fn [required operation]
           (if (and (vector? operation)
                    (= :db/add (first operation))
                    (string? (second operation))
                    (.startsWith (second operation)
                                 "seon.program.edge/terminal:")
                    (= ::edge/required-bindings (nth operation 2 nil)))
             (update required (second operation)
                     (fnil conj #{}) (nth operation 3))
             required))
         {} tx-data)
        terminals
        (into []
              (keep (fn [row]
                      (when (and (map? row)
                                 (contains? row ::edge/terminal-symbol))
                        (-> row
                            (dissoc :db/id)
                            (assoc ::edge/required-bindings
                               (get required-by-terminal
                                    (:db/id row)
                                    #{}))))))
              tx-data)]
    {::edge/function-symbol "fixture.edge/subject"
     ::edge/generation (::edge/generation function-map)
     ::edge/calls (values ::edge/calls)
     ::edge/read-attributes (values ::edge/read-attributes)
     ::edge/written-attributes (values ::edge/written-attributes)
     ::edge/all-at-basis? (true? (::edge/all-at-basis? function-map))
     ::edge/uncertainties (values ::edge/uncertainties)
     ::edge/terminals terminals}))

#?(:clj
   (defn- tee-tx [_bundle]
     (host.record/tee-tx-data
      {:seon.host.record/forms [fixture-form]
       :seon.host.record/source (pr-str fixture-form)
       :seon.host.record/ns-sym 'fixture.edge
       :seon.host.record/resolution resolution
       :seon.host.record/var-meta {}
       :seon.host.record/new-schema-keys #{}
       :seon.host.record/at (java.util.Date.)})))

#?(:clj
   (deftest host-resolution-snapshots-retained-sci-aliases-and-refers
     (let [ctx
           (sci/init
            {:namespaces
             {'fixture.target
              {'fetch
               (sci/new-var 'fetch (fn [_] :ok)
                            {:ns 'fixture.target
                             :seon.capability/effect :external})}}})]
       (sci/eval-string*
        ctx
        "(ns fixture.edge (:require [fixture.target :as target :refer [fetch]]))")
       (let [snapshot
             ((var-get #'host.eval/namespace-resolution) ctx 'fixture.edge)]
         (is (= {'target 'fixture.target} (::edge/aliases snapshot)))
         (is (= {'fetch 'fixture.target/fetch} (::edge/refers snapshot)))
         (is (= :external
                (get (::edge/effects snapshot)
                     'fixture.target/fetch)))))))

(deftest direct-edge-bundle-is-exact-on-this-tier
  (let [bundle (analyzed-bundle fixture-form)]
    (is (= expected-calls (::edge/calls bundle)))
    (is (= #{:demo/name} (::edge/read-attributes bundle)))
    (is (= #{:demo/id :demo/name :demo/score}
           (::edge/written-attributes bundle)))
    (is (true? (::edge/all-at-basis? bundle)))
    (is (= #{:constructed-keyword} (::edge/uncertainties bundle)))
    (is (= expected-effects
           (into {} (map (juxt ::edge/terminal-symbol ::edge/effect))
                 (::edge/terminals bundle))))))

#?(:clj
   (deftest persisted-terminal-connections-reconstruct-the-edge-bundle
     (let [bundle (analyzed-bundle fixture-form)
           persisted (tx-bundle (tee-tx bundle))
           terminals (into {} (map (juxt ::edge/terminal-symbol identity))
                           (::edge/terminals persisted))
           pulled
           (-> persisted
               (dissoc ::edge/function-symbol ::edge/terminals)
               (assoc :seon.fn/sym (::edge/function-symbol persisted)
                      ::edge/terminal-refs (::edge/terminals persisted)))
           reconstructed (first (edge/reconstruct-bundles [pulled]))]
       (is (= expected-calls (set (keys terminals))))
       (doseq [target expected-calls]
         (is (= #{target}
                (::edge/required-bindings (get terminals target)))))
       (is (= (dissoc bundle ::edge/terminals)
              (dissoc persisted ::edge/terminals)))
       (is (= (set (::edge/terminals bundle))
              (set (::edge/terminals persisted))))
       (is (= bundle reconstructed))
       (is (= (edge/program-graph-digest [bundle])
              (edge/program-graph-digest [reconstructed]))))))

(deftest graph-digest-changes-with-any-edge-change
  (let [first-bundle (analyzed-bundle fixture-form)
        changed-form
        (->> fixture-form
             (map (fn [value]
                    (if (and (seq? value)
                             (= 'db/query (first value)))
                      '(db/query
                        '[:find ?title
                          :where [?entity :demo/title ?title]])
                      value)))
             (apply list))
        changed-bundle (analyzed-bundle changed-form)]
    (is (not= (::edge/read-attributes first-bundle)
              (::edge/read-attributes changed-bundle)))
    (is (not= (::edge/generation first-bundle)
              (::edge/generation changed-bundle)))
    (is (not= (edge/program-graph-digest [first-bundle])
              (edge/program-graph-digest [changed-bundle])))))

(deftest unresolved-value-symbols-become-edge-uncertainty
  (let [bundle
        (analyzed-bundle
         '(defn subject []
            (keyword forms)))]
    (is (= #{"clojure.core/keyword"} (::edge/calls bundle)))
    (is (contains? (::edge/uncertainties bundle) :unresolved-symbol))))
