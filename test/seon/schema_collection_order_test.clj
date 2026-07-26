(ns seon.schema-collection-order-test
  "Ordered collections must survive a database round trip.

   Datahike materializes `:db.cardinality/many` as a vector on pull, so a
   `[:vector X]` schema LOOKS like it round-trips — the shape returns, the
   order does not. Scalars pull in value order and refs pull in target
   entity-id order. Component refs are NOT an exception: `:seon.error/frames`
   is safe only because its renderers sort by an explicit frame ordinal.

   So order is never a property of the collection type. Where it matters,
   store the position.

   These tests lock both halves: the empirical reason, and the invariant that
   makes the whole class unrepresentable."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.config.resolve]
            [seon.db.internal :as internal]
            [seon.schema :as schema]))

(defn- ordered-collection-form? [form]
  (and (vector? form) (#{:vector :sequential} (first form))))

(defn- form-properties [form]
  (let [maybe (second form)]
    (when (map? maybe) maybe)))

(defn- stored-form [attr]
  (try (internal/resolve-malli-form (schema/schema-definition attr))
       (catch Throwable _ nil)))

;;; --- the invariant ---------------------------------------------------------
;;;
;;; NOTHING in Datahike preserves insertion order for a `:db.cardinality/many`
;;; attribute — not component refs, not anything. `:seon.error/frames` is safe
;;; only because its renderers sort by an explicit frame ordinal. So a stored
;;; `[:vector X]` declaration always promises an order the database will not
;;; keep, and the fix is never a different collection type: it is either a
;;; `[:set X]` declaration or an explicit ordinal attribute.
;;;
;;; `:db.type/tuple` is NOT an escape hatch. It stores one vector value rather
;;; than a queryable many-value relationship. Heterogeneous tuples have the
;;; fixed size declared by `:db/tupleTypes`; homogeneous tuples are variable
;;; length and their transaction validator caps them at 8. The current fork
;;; does not enforce its documented 8-value upper bound for heterogeneous
;;; tuples, which is another reason not to treat tuple as a general collection.

;;; The one exemption is COMPUTED, never a name list: a `:db.secondary/only`
;;; attribute stores a content hash in the primary index while the ordered
;;; vector lives in the Proximum secondary index, so the primary never holds a
;;; collection at all. `:seon/embedding` qualifies by carrying that property,
;;; not by being itself.

(deftest no-stored-attribute-promises-an-order-the-database-cannot-keep
  (testing "no stored attribute is declared as an ordered collection"
    (let [offenders
          (into []
                (keep (fn [attr]
                        (let [form (stored-form attr)]
                          (when (and (ordered-collection-form? form)
                                     (not (:db.secondary/only (form-properties form))))
                            {:attr attr :form form}))))
                (schema/canonical-database-attributes))]
      (is (empty? offenders)
          (str "These stored attributes declare an ORDERED collection. A "
               "`:db.cardinality/many` attribute returns scalars sorted by "
               "VALUE and refs sorted by ENTITY ID, so insertion order is "
               "silently discarded — the shape round-trips, the order does "
               "not.\n\n"
               "Fix each one:\n"
               "  - order is NOT meaningful (almost always) -> `[:set X]`\n"
               "  - order IS meaningful -> keep `[:set X]` and store the "
               "position explicitly on the child, the way "
               "`:seon.error/frames` renderers sort by frame ordinal.\n\n"
               "Do NOT reach for `:db.type/tuple`: it stores the whole vector "
               "as one value, and tuple members are not cardinality-many "
               "facts queryable through the ordinary relationship.\n\n"
               "Offenders: " (pr-str offenders))))))

(deftest corrected-set-attributes-stay-sets
  (testing "attributes that are semantically sets are declared as sets"
    (doseq [attr [:seon.fn/read-attrs
                  :seon.agent.web/allowed-domains
                  :seon.agent.ctx/capabilities
                  :seon.agent.message/to
                  :seon.agent.run/forms
                  :seon.agent.turn/evals
                  :seon.agent.turn/timings
                  :seon.render/children]]
      (is (= :set (first (stored-form attr)))
          (str attr " is a set, not an ordered collection. A scalar "
               "cardinality-many attribute round-trips sorted by value, so "
               "declaring it `[:vector X]` promises an order the database "
               "will not keep.")))))

;;; --- the reason, proven against a real database ----------------------------

(defn- with-db [f]
  (let [cfg {:store {:backend :memory
                     :id (java.util.UUID/randomUUID)}
             :schema-flexibility :write
             :keep-history? false}]
    (d/delete-database cfg)
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try (f conn)
           (finally (d/release conn) (d/delete-database cfg))))))

(deftest scalar-cardinality-many-sorts-by-value
  (testing "a scalar cardinality-many attribute does NOT preserve insertion order"
    (with-db
      (fn [conn]
        (d/transact conn [{:db/ident :order-probe/id
                           :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one
                           :db/unique :db.unique/identity}
                          {:db/ident :order-probe/tags
                           :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/many}])
        (d/transact conn [{:order-probe/id "p"
                           :order-probe/tags ["zebra" "apple" "mango"]}])
        (let [read-back (:order-probe/tags
                         (d/pull (d/db conn) '[:order-probe/tags]
                                 [:order-probe/id "p"]))]
          (is (not= ["zebra" "apple" "mango"] (vec read-back))
              "If this ever passes, Datahike began preserving insertion order
               for scalar cardinality-many and the invariant above can relax.")
          (is (= ["apple" "mango" "zebra"] (vec read-back))
              "scalars come back sorted by value"))))))

(deftest pre-existing-refs-lose-insertion-order
  (testing "referencing entities that already exist re-sorts them by entity id"
    (with-db
      (fn [conn]
        (d/transact conn [{:db/ident :order-probe/id
                           :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one
                           :db/unique :db.unique/identity}
                          {:db/ident :order-probe/key
                           :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one
                           :db/unique :db.unique/identity}
                          {:db/ident :order-probe/refs
                           :db/valueType :db.type/ref
                           :db/cardinality :db.cardinality/many}])
        ;; created A B C, then referenced in reverse
        (d/transact conn [{:order-probe/key "A"}
                          {:order-probe/key "B"}
                          {:order-probe/key "C"}])
        (d/transact conn [{:order-probe/id "e"
                           :order-probe/refs [[:order-probe/key "C"]
                                              [:order-probe/key "B"]
                                              [:order-probe/key "A"]]}])
        (let [read-back (mapv :order-probe/key
                              (:order-probe/refs
                               (d/pull (d/db conn)
                                       '[{:order-probe/refs [:order-probe/key]}]
                                       [:order-probe/id "e"])))]
          (is (= ["A" "B" "C"] read-back)
              "written C B A; returned in entity-id order — this is exactly the
               failure a non-component ordered ref attribute would suffer"))))))
