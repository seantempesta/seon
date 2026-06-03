(ns seon.server.wire-props-test
  "Generative / property tests for the JVM wire-server contract.

   Two properties are exercised:

   1. Transit value roundtrip fidelity. The whole wire depends on
      `seon.server.transit/{write-str,read-str}` preserving Clojure types
      across the boundary (keywords, namespaced keywords, strings, longs,
      doubles distinct from ints, instants, sets, vectors, maps, nested
      maps, ratios, BigInts). For generated values of those types we assert
      `(= (read-str (write-str x)) x)` AND that the class is preserved
      (a double must not silently degrade to a long, etc.).

   2. `handle-op` correctness against a fresh in-memory datahike conn. These
      call `seon.server.wire/handle-op` DIRECTLY (no UDS subprocess) so the
      generative suite stays fast. For generated small entity sets:
        - transact then `q` returns what was written;
        - transact then `pull` / `entity-pull` returns the entity;
        - `pull-many` matches per-eid `pull`;
        - basis-t strictly increases across successful transacts;
        - a read op (`q`) does not change basis-t.

   Generators are kept small (1-8 entities, ~50 trials) deliberately — the
   point is contract coverage, not load.

   Property tests drive `clojure.test.check/quick-check` from inside plain
   `deftest`s (rather than `defspec`) so clj-kondo resolves every symbol; the
   `passing?` helper turns a quick-check result map into a single assertion
   that prints the shrunk counterexample on failure."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.server.store :as store]
            [seon.server.transit :as transit]
            [seon.server.wire :as wire]))

(set! *warn-on-reflection* true)

(defn- T [v] (transit/write-str v))

(defn- check
  "Run a property `n` times and return the quick-check result map."
  [n property]
  (tc/quick-check n property))

(defn- passing?
  "Assert a quick-check result passed, surfacing the shrunk counterexample."
  [result]
  (is (true? (:pass? result))
      (str "shrunk counterexample: "
           (pr-str (get-in result [:shrunk :smallest]))
           " (failed after " (:num-tests result) " tests)")))

;;; ---------------------------------------------------------------------------
;;; 1. Transit value roundtrip fidelity
;;; ---------------------------------------------------------------------------

(def gen-scalar
  "A generated scalar covering the documented wire scalar types."
  (gen/one-of
   [gen/keyword
    gen/keyword-ns
    gen/string-alphanumeric
    gen/large-integer                       ; longs
    (gen/double* {:infinite? false :NaN? false})
    (gen/fmap #(java.util.Date. (long %)) gen/large-integer) ; instants
    gen/boolean
    (gen/fmap bigint (gen/such-that #(not (zero? %)) gen/large-integer))
    (gen/fmap (fn [[n d]] (/ n d))           ; ratios (kept non-integral)
              (gen/such-that (fn [[n d]] (and (not (zero? d))
                                              (not (zero? (mod n d)))))
                             (gen/tuple gen/large-integer
                                        (gen/such-that (complement zero?)
                                                       gen/large-integer))))]))

(def gen-wire-value
  "Scalars plus one level of collection nesting: sets, vectors, maps,
   and nested maps. Recursion is bounded so generation stays cheap."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/vector inner 0 4)
       (gen/set inner)
       (gen/map gen/keyword-ns inner {:max-elements 4})]))
   gen-scalar))

(defn- roundtrips?
  "The wire fidelity property for one value: decode of encode equals the
   original, and the top-level class is preserved (this is what catches a
   double silently becoming a long)."
  [v]
  (let [r (transit/read-str (transit/write-str v))]
    (and (= v r)
         (= (class v) (class r)))))

(deftest transit-roundtrip-fidelity
  (testing "every documented wire value type roundtrips with class preserved"
    (passing?
     (check 200
            (prop/for-all [v gen-wire-value]
              (roundtrips? v))))))

(deftest transit-double-distinct-from-int
  (testing "doubles survive as Double, not Long — the float/int hazard"
    (doseq [d [1.0 0.0 -0.0 2.0 1.0e10 3.14]]
      (let [r (transit/read-str (transit/write-str d))]
        (is (instance? Double r) (str d " -> " (class r)))
        (is (= d r)))))
  (testing "longs survive as Long, never promoted to Double"
    (doseq [n [0 1 -1 42 9999999999]]
      (let [r (transit/read-str (transit/write-str n))]
        (is (instance? Long r) (str n " -> " (class r)))
        (is (= n r))))))

;;; ---------------------------------------------------------------------------
;;; 2. handle-op correctness against an in-memory conn
;;; ---------------------------------------------------------------------------

(def ^:private schema-tx
  "String-identity entity with a long attr and a keyword attr. Declared up
   front because the store uses :schema-flexibility :write."
  [{:db/ident :ent/id   :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :ent/n    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :ent/tag  :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(defn- fresh-conn!
  "A fresh, isolated in-memory datahike conn with the test schema installed.
   Uniqueness via gensym so concurrent specs don't share a process-global
   :memory store (see test-util's note on config-for's per-name id)."
  []
  (let [cfg (store/config-for
             {:seon.server.store/db-name (keyword "test" (str (gensym "props")))
              :seon.server.store/backend :memory})]
    (when-not (d/database-exists? cfg) (d/create-database cfg))
    (let [conn (d/connect cfg)]
      (wire/handle-op conn {"op" "transact" "tx-data" (T schema-tx)})
      conn)))

(defn- ok? [resp] (true? (get resp "ok")))

(def gen-entity
  "One entity: a string id, a long, and a keyword tag."
  (gen/let [id  gen/string-alphanumeric
            n   gen/large-integer
            tag gen/keyword-ns]
    {:ent/id id :ent/n n :ent/tag tag}))

(def gen-entity-set
  "1-8 entities with DISTINCT ids (string identity collapses dupes, which
   would make per-id assertions ambiguous)."
  (gen/let [ents (gen/vector gen-entity 1 8)]
    (->> ents
         (reduce (fn [acc e] (assoc acc (:ent/id e) e)) {})
         vals
         vec)))

(deftest transact-then-query-returns-written
  (passing?
   (check 50
          (prop/for-all [ents gen-entity-set]
            (let [conn (fresh-conn!)
                  tx   (wire/handle-op conn {"op" "transact" "tx-data" (T ents)})]
              (and (ok? tx)
                   (every?
                    (fn [{:keys [:ent/id :ent/n :ent/tag]}]
                      (let [r (wire/handle-op
                               conn
                               {"op" "q"
                                "query" (T '[:find ?n ?tag :in $ ?id :where
                                             [?e :ent/id ?id]
                                             [?e :ent/n ?n]
                                             [?e :ent/tag ?tag]])
                                "args" [(T id)]})
                            rows (transit/read-str (get r "result"))]
                        (and (ok? r) (= #{[n tag]} rows))))
                    ents)))))))

(deftest transact-then-pull-returns-entity
  (passing?
   (check 50
          (prop/for-all [ents gen-entity-set]
            (let [conn (fresh-conn!)
                  tx   (wire/handle-op conn {"op" "transact" "tx-data" (T ents)})]
              (and (ok? tx)
                   (every?
                    (fn [{:keys [:ent/id] :as ent}]
                      (let [pr (wire/handle-op
                                conn
                                {"op" "pull"
                                 "selector" (T '[:ent/id :ent/n :ent/tag])
                                 "eid" (T [:ent/id id])})
                            ep (wire/handle-op
                                conn
                                {"op" "entity-pull"
                                 "ref" (T [:ent/id id])
                                 "selector" (T '[:ent/id :ent/n :ent/tag])})
                            pm (transit/read-str (get pr "result"))
                            em (transit/read-str (get ep "result"))
                            want (select-keys ent [:ent/id :ent/n :ent/tag])]
                        (and (ok? pr) (ok? ep)
                             (= want (select-keys pm [:ent/id :ent/n :ent/tag]))
                             (= want (select-keys em [:ent/id :ent/n :ent/tag])))))
                    ents)))))))

(deftest pull-many-matches-per-eid-pull
  (passing?
   (check 50
          (prop/for-all [ents gen-entity-set]
            (let [conn (fresh-conn!)
                  _    (wire/handle-op conn {"op" "transact" "tx-data" (T ents)})
                  eids (mapv (fn [{:keys [:ent/id]}] [:ent/id id]) ents)
                  sel  '[:ent/id :ent/n :ent/tag]
                  many (-> (wire/handle-op conn {"op" "pull-many"
                                                 "selector" (T sel)
                                                 "eids" (mapv T eids)})
                           (get "result") transit/read-str)
                  singles (mapv (fn [eid]
                                  (-> (wire/handle-op conn {"op" "pull"
                                                            "selector" (T sel)
                                                            "eid" (T eid)})
                                      (get "result") transit/read-str))
                                eids)]
              (= singles many))))))

(deftest transact-basis-t-strictly-increases
  (passing?
   (check 50
          (prop/for-all [batches (gen/vector gen-entity-set 1 5)]
            (let [conn (fresh-conn!)]
              (loop [[b & more] batches
                     prev nil]
                (if (nil? b)
                  true
                  (let [r  (wire/handle-op conn {"op" "transact" "tx-data" (T b)})
                        bt (get r "basis-t")]
                    (if (and (ok? r)
                             (integer? bt)
                             (or (nil? prev) (> (long bt) (long prev))))
                      (recur more bt)
                      false)))))))))

(deftest read-does-not-change-basis-t
  (passing?
   (check 50
          (prop/for-all [ents gen-entity-set]
            (let [conn   (fresh-conn!)
                  tx     (wire/handle-op conn {"op" "transact" "tx-data" (T ents)})
                  bt-tx  (get tx "basis-t")
                  q1     (wire/handle-op conn {"op" "q"
                                               "query" (T '[:find ?e :where [?e :ent/id _]])})
                  q2     (wire/handle-op conn {"op" "q"
                                               "query" (T '[:find ?e :where [?e :ent/id _]])})]
              (and (ok? tx) (ok? q1) (ok? q2)
                   (= bt-tx (get q1 "basis-t") (get q2 "basis-t"))))))))
