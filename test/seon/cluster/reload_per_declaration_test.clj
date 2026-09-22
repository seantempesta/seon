(ns seon.cluster.reload-per-declaration-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.fn :as source]
            [seon.id :as id]
            [seon.test-support :as support]))

(defn- with-program
  {:malli/schema [:=> [:cat [:=> [:cat :seon.db/connection] :nil]] :nil]}
  [check]
  (let [root (io/file "tmp" (str "reload-rules-" (id/id)))]
    (.mkdirs (io/file root "src"))
    (try
      (doseq [[file text]
              {"base.clj" "(ns sample.reload.base)\n(defn value [] 1)\n(def literal-value 7)\n(def literal-data {:a [1 true nil]})\n(def dynamic-value (str \"x\"))\n(defn inlined {:inline (fn [x] x)} [x] x)\n(defmacro expanded [] 1)\n(defprotocol P (p [this]))"
               "impl.clj" "(ns sample.reload.impl (:require [sample.reload.base :as base]))\n(defrecord R [] base/P (p [_] (base/value)))"
               "caller.clj" "(ns sample.reload.caller (:require [sample.reload.base :as base] [sample.reload.impl :as impl]))\n(defn result [] (+ (base/expanded) (base/p (impl/->R))))"
               "unrelated.clj" "(ns sample.reload.unrelated)\n(defn value [] 0)"}]
        (spit (io/file root "src" file) text))
      (support/with-database
       (fn [connection]
         (support/transacted! connection
                              (source/rows {:seon.fn/root (.getCanonicalPath root)
                                            :seon.fn/roots ["src"]}))
         (is (= 'clojure.core/defn
                (db/q '[:find ?head . :where
                        [?e :seon.fn/sym sample.reload.base/value]
                        [?e :seon.fn/defined-by ?head]] (db/db connection))))
         (check connection)))
      (finally (support/delete-recursively! root))))
  nil)

(deftest ^{:seon.test/long "Canonical fixture cold acquisition and namespace analysis previously measured 12 seconds in publication-delta-test."
           :seon.test/long-ms 20000}
  ordinary-defn-reloads-only-its-namespace
  (with-program
    (fn [connection]
      (is (= '#{sample.reload.base}
             (cluster/development-namespaces (db/db connection)
                                             [[:seon.fn/sym 'sample.reload.base/value]])))
      nil)))

(deftest ^{:seon.test/long "Canonical fixture cold acquisition and namespace analysis previously measured 12 seconds in publication-delta-test."
           :seon.test/long-ms 20000}
  macros-reload-exact-dependents
  (with-program
    (fn [connection]
      (is (= '#{sample.reload.base sample.reload.impl sample.reload.caller}
             (cluster/development-namespaces (db/db connection)
                                             [[:seon.fn/sym 'sample.reload.base/expanded]])))
      nil)))

(deftest ^{:seon.test/long "Canonical fixture cold acquisition and namespace analysis previously measured 12 seconds in publication-delta-test."
           :seon.test/long-ms 20000}
  protocols-reload-implementers-and-callers
  (with-program
    (fn [connection]
      (is (= '#{sample.reload.base sample.reload.impl sample.reload.caller}
             (cluster/development-namespaces (db/db connection)
                                             [[:seon.fn/sym 'sample.reload.base/p]])))
      (is (= '#{sample.reload.impl sample.reload.caller}
             (cluster/development-namespaces (db/db connection)
                                             [[:seon.fn/sym 'sample.reload.impl/->R]])))
      nil)))

(deftest declaration-reload-rules-retain-unknown-evidence
  (let [rule @#'cluster/declaration-reload-rule]
    (doseq [head '[clojure.core/defmacro clojure.core/defprotocol
                   clojure.core/deftype clojure.core/defrecord
                   clojure.core/definterface clojure.core/definline clojure.core/def]]
      (is (= :seon.reload/compiled-into-callers (rule head :seon.reload/unknown))))
    (doseq [head [nil 'clojure.core/defn 'sample/defining-macro]]
      (is (= :seon.reload/unknown (rule head :seon.reload/unknown))))))

(deftest ^{:seon.test/long "Canonical fixture cold acquisition and namespace analysis previously measured 12 seconds in publication-delta-test."
           :seon.test/long-ms 20000}
  inline-and-literal-def-facts-drive-reload
  (with-program
    (fn [connection]
      (let [database (db/db connection)]
        (is (= #{['sample.reload.base/value false] ['sample.reload.base/inlined true]}
               (set (db/q '[:find ?sym ?inline :where
                            [?e :seon.fn/sym ?sym] [?e :seon.fn/inline? ?inline]
                            [(contains? #{sample.reload.base/value sample.reload.base/inlined} ?sym)]] database)))
            "ordinary inline absence is stored as an analyzed false")
        (is (= '#{sample.reload.base/literal-value sample.reload.base/literal-data}
               (set (db/q '[:find [?sym ...] :where [?e :seon.fn/constant? true]
                            [?e :seon.fn/sym ?sym]] database)))
            "literal defs are declarations; a computed initializer is not a constant")
        (doseq [sym '[sample.reload.base/inlined sample.reload.base/literal-value
                     sample.reload.base/literal-data]]
          (is (= '#{sample.reload.base sample.reload.impl sample.reload.caller}
                 (cluster/development-namespaces database [[:seon.fn/sym sym]]))))
      nil))))

(deftest ^{:seon.test/long "Canonical fixture cold acquisition and namespace analysis previously measured 12 seconds in publication-delta-test."
           :seon.test/long-ms 20000}
  retired-or-missing-inline-facts-widen
  (with-program
    (fn [connection]
      (let [before (db/db connection)]
        (support/transacted! connection
                             [[:db/retract [:seon.fn/sym 'sample.reload.base/value]
                               :seon.fn/inline? false]])
        (is (= '#{sample.reload.base sample.reload.impl sample.reload.caller}
               (cluster/development-namespaces before (db/db connection)
                                               [[:seon.fn/sym 'sample.reload.base/value]]))))
      nil)))
