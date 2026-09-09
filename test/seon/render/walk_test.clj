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
  ([database ctx distance]
   (request database ctx distance :seon.render/ai))
  ([database ctx distance output]
  {:seon.db/db database
   :seon.sci.eval/ctx ctx
   :seon.render.walk/lookup [:seon.agent/id agent-id]
   :seon.render/output output
   :seon.render/distance distance
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 5000
   :seon.config/on-core-error :record}))

(defn- seed-agent-and-transcript!
  [connection]
  (db/transact!
   connection
   [{:seon.ns/name agent-namespace}
    {:seon.agent/id agent-id
     :seon.agent/namespace [:seon.ns/name agent-namespace]}
    {:seon.turn/id "render-walk-run"
     :seon.turn/agent [:seon.agent/id agent-id]
     :seon.turn/opened-at (at 0)}
    {:seon.cluster.eval/id "render-walk-eval"
     :seon.cluster.eval/run [:seon.turn/id "render-walk-run"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 1)
     :seon.cluster.eval/ns [:seon.ns/name agent-namespace]
     :seon.cluster.eval/result-edn "42"
     :seon.cluster.eval/source "(+ 20 22)"}]))

(deftest every-identifiable-neighbour-uses-its-declared-lookup-ref
  (support/with-database
   (fn [connection]
     (seed-agent-and-transcript! connection)
     (db/transact!
      connection
      [{:db/id "identityless-run"
        :seon.turn/agent [:seon.agent/id agent-id]}])
     (let [database @connection
           identity-attributes (db/populated-identity-attributes database)
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
         (is (some #(= [:seon.turn/id "render-walk-run"]
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
             "every error-valued survivor is a distance-cap marker, not a renderer failure")
         (is (every? #(true? (:seon.render.walk/elided
                              (:seon.error/value %)))
                     error-valued-units)
             "every distance-cap error carries its declared class marker"))
       (is (some #(str/includes? (str (:seon.render/output %)) "42") units)
           "the seeded transcript reaches database reads and print emission")
       (is (zero? @database-projection-resolutions)
           "the walk reuses its context-carried projection without rebuilding")
       (is (zero? @schema-resource-reads)
           "the exact-basis database projection reads no schema resources")
       (is (not (str/includes? (str warnings)
                               "DECLARATION POPULATION FALLBACK"))
           "the complete traversal stays under its supplied projection")))))

(defn- elision-observations
  [acquisition]
  (->> (vals (:seon.render.walk/members acquisition))
       (mapcat :seon.render.walk/connections)
       (keep :seon.error/value)
       (filter #(= ::walk/elided (:seon.error/kind %)))
       vec))

(deftest a-truncated-connection-is-reported-whichever-bound-cut-it
  ;; THE PROJECT'S NAMED FAILURE CLASS, created by a fix: the presentation
  ;; width answered `Integer/MAX_VALUE` for a request carrying no profile
  ;; while the PULL still stopped at its own query-work limit, so the
  ;; observation could never fire and a truncated connection read as
  ;; complete (measured 2026-09-07: width 1 -> 7 elisions, no profile -> 0,
  ;; width 100000 -> 0). Two bounds cut here and both are reported.
  (support/with-database
   (fn [connection]
     (seed-agent-and-transcript! connection)
     (db/transact!
      connection
      (mapv (fn [ordinal]
              {:seon.cluster.eval/id (str "render-walk-eval-" ordinal)
               :seon.cluster.eval/run [:seon.turn/id "render-walk-run"]
               :seon.cluster.eval/ordinal ordinal
               :seon.cluster.eval/at (at (+ 10 ordinal))
               :seon.cluster.eval/ns [:seon.ns/name agent-namespace]
               :seon.cluster.eval/result-edn "42"
               :seon.cluster.eval/source "(+ 20 22)"})
            [1 2]))
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           narrow (assoc caps :seon.config.eval.result/max-collection 2)]
       (testing "the pull's own cut is reported with NO profile supplied"
         (let [observations
               (elision-observations
                (walk/root-acquisition
                 (assoc (request database ctx 2)
                        :seon.sci.admit/caps narrow)))]
           (is (seq observations)
               "a connection the pull truncated read as complete")
           (is (every? #(= :seon.config.eval.result/max-collection
                           (get-in % [:seon.error/data :seon.print/bound-by]))
                       observations)
               (pr-str observations))
           (is (every? #(str/includes? (:seon.error/message %)
                                       "max-collection")
                       observations)
               "and the message names the bound that made the cut")))
       (testing "a tighter presentation width names the render profile"
         (let [observations
               (elision-observations
                (walk/root-acquisition
                 (assoc (request database ctx 2)
                        :seon.render/profile
                        {:seon.render.profile/id :seon.render.profile/agent
                         :seon.render.profile/max-children 1})))]
           (is (seq observations))
           (is (every? #(= :seon.render.profile/max-children
                           (get-in % [:seon.error/data :seon.print/bound-by]))
                       observations)
               (pr-str observations))))))))

(deftest html-neighborhood-emits-no-traversal-only-elision-units
  (support/with-database
   (fn [connection]
     (seed-agent-and-transcript! connection)
     (let [units (walk/neighborhood
                  (request @connection
                           (support/fork-cluster-ctx connection)
                           2
                           :seon.render/html))]
       (is (pos? (count units)) "the census must inspect a real neighborhood")
       (is (empty? (filter #(= ::walk/elided
                               (get-in % [:seon.error/value
                                          :seon.error/kind]))
                           units))
           "HTML emits only units with renderable content")))))
