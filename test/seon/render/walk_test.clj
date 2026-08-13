(ns seon.render.walk-test
  "Class regressions for the bounded neighbourhood traversal."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as support]))

(def ^:private caps (config/result-caps (config/defaults)))
(def ^:private agent-id "render-walk-agent")
(def ^:private agent-namespace 'my.agents.render-walk)

(defn- at
  [offset]
  (java.util.Date. (long (+ 1786400000000 offset))))

(defn- request
  [database ctx distance]
  {:seon.db/db database
   :seon.sci.eval/ctx ctx
   :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
   :seon.render/output :seon.render/ai
   :seon.render/distance distance
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 5000
   :seon.config/on-core-error :record})

(defn- seed-agent-and-transcript!
  [connection]
  (db/transact!
   connection
   [{:seon.ns/name agent-namespace}
    {:seon.cluster.agent/id agent-id
     :seon.cluster.agent/namespace [:seon.ns/name agent-namespace]}
    {:seon.cluster.run/id "render-walk-run"
     :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
     :seon.cluster.run/opened-at (at 0)}
    {:seon.cluster.run.form/id "render-walk-form"
     :seon.cluster.run.form/run [:seon.cluster.run/id "render-walk-run"]
     :seon.cluster.run.form/ordinal 0
     :seon.cluster.run.form/source "(+ 20 22)"
     :seon.cluster.run.form/ns [:seon.ns/name agent-namespace]}
    {:seon.cluster.eval/id "render-walk-eval"
     :seon.cluster.eval/run [:seon.cluster.run/id "render-walk-run"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 1)
     :seon.cluster.eval/ns [:seon.ns/name agent-namespace]
     :seon.cluster.eval/result-edn "42"}]))

(defn- declared-identity-attributes
  [projection]
  (->> (:seon.schema.projection/shape-rows projection)
       vals
       (keep :seon.entity/id-attr)
       distinct
       (sort-by str)
       vec))

(deftest every-identifiable-neighbour-uses-its-declared-lookup-ref
  (support/with-database
   (fn [connection]
     (seed-agent-and-transcript! connection)
     (db/transact!
      connection
      [{:db/id "identityless-run"
        :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]}])
     (let [database @connection
           projection (schema/projection-from-database database)
           identity-attributes (declared-identity-attributes projection)
           units (walk/neighborhood
                  (request database (support/fork-cluster-ctx connection) 1))
           numeric-lookups (->> units
                                (keep :seon.render.walk/lookup)
                                (filter number?)
                                distinct
                                vec)
           identities-at
           (fn [eid]
             (let [entity (db/pull database '[*] eid)]
               (keep (fn [attribute]
                       (when (contains? entity attribute)
                         [attribute (get entity attribute)]))
                     identity-attributes)))]
       (testing "the reverse-ref run is addressed by its declared identity"
         (is (some #(= [:seon.cluster.run/id "render-walk-run"]
                       (:seon.render.walk/lookup %))
                   units)))
       (testing "a raw eid survives only when the entity has no identity"
         (is (= 1 (count numeric-lookups)))
         (is (every? #(empty? (identities-at %)) numeric-lookups)))))))

(deftest one-basis-projection-covers-the-complete-walk
  (support/with-database
   (fn [connection]
     (seed-agent-and-transcript! connection)
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           database-projection-resolutions (atom 0)
           schema-resource-reads (atom 0)
           projection-from-database schema/projection-from-database
           read-schema-resource @#'schema.edn/read-schema-resource
           warnings (java.io.StringWriter.)
           units
           (binding [*err* warnings]
             (with-redefs
               [schema/projection-from-database
               (fn [& arguments]
                 (swap! database-projection-resolutions inc)
                 (apply projection-from-database arguments))
               schema.edn/read-schema-resource
               (fn [resource]
                 (swap! schema-resource-reads inc)
                 (read-schema-resource resource))]
               (schema/call-with-projection-state
                (atom {})
                #(walk/neighborhood (request database ctx 2)))))]
       (let [error-valued-units (filterv :seon.error/value units)]
         (is (seq error-valued-units)
             "the distance-two walk reaches structural distance-cap markers")
         (is (every? #(= :seon.render.walk/elided
                         (:seon.error/kind (:seon.error/value %)))
                     error-valued-units)
             "every error-valued survivor is a distance-cap marker, not a renderer failure"))
       (is (some #(str/includes? (str (:seon.render/output %)) "42") units)
           "the seeded transcript reaches database reads and print emission")
       (is (zero? @database-projection-resolutions)
           "the walk reuses its context-carried projection without rebuilding")
       (is (zero? @schema-resource-reads)
           "the exact-basis database projection reads no schema resources")
       (is (not (str/includes? (str warnings)
                               "DECLARATION POPULATION FALLBACK"))
           "the complete traversal stays under its supplied projection")))))
