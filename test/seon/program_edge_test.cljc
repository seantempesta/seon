(ns seon.program-edge-test
  "Canonical direct-edge facts exercised unchanged on CLJ and CLJS."
  (:require
   #?(:clj [clojure.test :refer [deftest is]]
      :cljs [cljs.test :refer [deftest is]])
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
