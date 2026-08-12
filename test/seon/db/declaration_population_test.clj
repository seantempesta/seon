(ns ^{:seon.test/platform
       "Moving part: one declaration population per operation, read side."}
    seon.db.declaration-population-test
  "The class regression for per-attribute declaration resolution at DB reads.

  Every `seon.db` read that may contain an EDN-backed attribute asks the
  declarations whether an attribute is EDN-encoded. The repaired read derives
  the exact projection from its database value when none was handed and does
  not read packaged schema resources. Before that repair, the question
  re-read all 152 resources once per attribute, pulled key, and datom; it
  wedged two suites and cost one `seon.config/effective` 84,664 resource reads
  (2026-08-07), all of them inside `db/pull '[*]`.

  The class is dead when ONE read operation performs AT MOST ONE resolution,
  whatever its attribute, key, or datom count. These tests explicitly clear
  the runner's packaged projection, count reads at the one resource seam, and
  exercise real EDN decoding so a future per-item shape cannot pass vacuously.

  Issue: docs/seon/issues/db-read-decoding-resolves-declarations-per-attribute.md"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]))

;; A deliberately wide row. `:seon.cluster.registry/from` is EDN-backed (a
;; mixed union), so decoding really happens and the test cannot pass by
;; skipping the walk entirely.
(def ^:private narrow-attributes
  [:seon.cluster.agent/id
   :seon.cluster.registry/from])

(def ^:private wide-attributes
  (into narrow-attributes
        [:seon.cluster.message/id
         :seon.cluster.message/to
         :seon.cluster.message/content
         :seon.cluster.message/at
         :seon.cluster.run/id
         :seon.cluster.run/opened-at
         :seon.cluster.run/plan-digest]))

(defn- resource-reads
  "Schema resource reads performed while calling `thunk`, and its value."
  [thunk]
  (let [reads (atom 0)
        read-one @#'schema.edn/read-schema-resource]
    (with-redefs [schema.edn/read-schema-resource
                  (fn [resource] (swap! reads inc) (read-one resource))]
      (let [value (thunk)]
        [@reads value]))))

(defn- reads-of
  [thunk]
  (first (resource-reads thunk)))

(def ^:private carrier-symbols
  '[*candidate-forms-overlay* *projection* *projection-state* *packaged-forms*])

(defn- without-handed-projection
  "Call `thunk` after explicitly clearing every schema projection carrier."
  [thunk]
  (with-bindings
    (into {} (map (fn [sym] [(ns-resolve 'seon.schema sym) nil]))
          carrier-symbols)
    (thunk)))

(defn- one-population-reads
  []
  (reads-of schema.edn/packaged-forms))

(defn- with-database
  "Call `body` with a bare in-memory connection and NO population supplied."
  [attributes body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (db/transact! connection
                    (schema.datahike/malli->datahike-schema attributes))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(deftest a-read-resolves-the-declaration-population-at-most-once
  (let [one (one-population-reads)]
    (testing "one explicit packaged acquisition reads every schema resource"
      (is (pos? one)
          "the acquisition measurement must read resources, or it is vacuous"))
    (without-handed-projection
     (fn []
       (with-database
        wide-attributes
        (fn [connection]
          (db/transact! connection
                        [{:seon.cluster.agent/id "agent-a"
                          :seon.cluster.registry/from :core
                          :seon.cluster.message/id "message-1"
                          :seon.cluster.message/to "agent-a"
                          :seon.cluster.message/content "hello"
                          :seon.cluster.message/at (java.util.Date.)
                          :seon.cluster.run/id "run-1"
                          :seon.cluster.run/opened-at (java.util.Date.)
                          :seon.cluster.run/plan-digest
                          (apply str (repeat 64 "a"))}])
          (dotimes [index 8]
            (db/transact! connection
                          [{:seon.cluster.message/id (str "extra-" index)
                            :seon.cluster.message/content "x"}]))
          (let [database (db/db connection)
                agent-ref [:seon.cluster.agent/id "agent-a"]]
            (doseq [[operation thunk]
                    [["pull '[*]" #(db/pull database '[*] agent-ref)]
                     ["pull-many '[*]"
                      #(db/pull-many database '[*] [agent-ref])]
                     ["entity" #(db/entity database agent-ref)]
                     ["q decoding a find element"
                      #(db/q '[:find ?from .
                               :where [_ :seon.cluster.registry/from ?from]]
                             database)]
                     ["datoms :eavt" #(db/datoms database :eavt)]]]
              (testing operation
                (is (<= (reads-of thunk) one)
                    (str operation
                         " must resolve declarations at most once, never once "
                         "per attribute, pulled key, or datom")))))))))))

(deftest a-read-that-decodes-nothing-resolves-nothing
  (testing "a query with no decodable find element reads no schema resource"
    (with-database
      narrow-attributes
      (fn [connection]
        (db/transact! connection [{:seon.cluster.agent/id "agent-a"}])
        (let [database (db/db connection)]
          (is (zero?
               (reads-of
                #(db/q '[:find ?e :where [?e :seon.cluster.agent/id _]]
                       database)))))))))

(deftest edn-backed-attributes-still-round-trip
  (testing "the value written through the encode seam decodes at every read"
    (with-database
      narrow-attributes
      (fn [connection]
        (let [report (db/transact! connection
                                   [{:seon.cluster.agent/id "agent-a"
                                     :seon.cluster.registry/from :core}])]
          (is (nil? (:seon.error/kind report))
              (str "the EDN-backed write must commit: " report)))
        (let [database (db/db connection)
              agent-ref [:seon.cluster.agent/id "agent-a"]]
          (is (= :core
                 (:seon.cluster.registry/from
                  (db/pull database '[*] agent-ref))))
          (is (= :core
                 (:seon.cluster.registry/from
                  (db/entity database agent-ref))))
          (is (= :core
                 (db/q '[:find ?from .
                         :where [_ :seon.cluster.registry/from ?from]]
                       database)))
          (is (contains? (set (map :v (db/datoms database :eavt))) :core)))))))
