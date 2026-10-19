(ns seon.db-overhead-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.batch :as batch]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as support])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLongArray]))

(defn observe
  "Measure one read in its own call sink, retaining its actual value.
  Root can use this same probe for q, pull and my.task/current after adoption."
  {:malli/schema
   [:=> [:cat [:=> [:cat] :seon.schema/value]]
    [:map [:seon.db-overhead/value :seon.schema/value]
     [:seon.db-overhead/us :double]
     [:seon.db-overhead/calls :int]
     [:seon.db-overhead/by-function [:map-of :qualified-symbol :int]]]]}
  [read-value]
  (let [sink (ConcurrentHashMap.)
        started (System/nanoTime)
        value (binding [batch/*world* (assoc batch/*world* :seon.batch/sink sink)]
                (read-value))
        elapsed (/ (- (System/nanoTime) started) 1000.0)
        counts (reduce (fn [counts [[sym _digest] counter]]
                         (update counts sym (fnil + 0) (.get ^AtomicLongArray counter 0)))
                       (sorted-map) sink)]
    {:seon.db-overhead/value value
     :seon.db-overhead/us elapsed
     :seon.db-overhead/calls (reduce + 0 (vals counts))
     :seon.db-overhead/by-function counts}))

(defn observe-write-validation
  "Replay final validation of a held report, without transacting or running tests.
  The report retains both immutable databases; capture it on one's own branch
  before changing the program, then pass the same report after adoption."
  {:malli/schema [:=> [:cat :seon.db/transaction-report] :map]}
  [report]
  (let [projection (db/carried-projection (:db-after report))]
    (assoc (observe #(#'seon.db/validate-write-report projection report))
           :seon.db-overhead/datoms (count (:tx-data report))
           :seon.db-overhead/entities (count (into #{} (map :e) (:tx-data report))))))

(deftest query-preparation-has-bounded-armed-work
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           query '[:find ?key . :where [_ :seon.schema/key ?key]
                   [(= ?key :seon.cluster/name)]]
           {:seon.db-overhead/keys [value calls by-function]}
           (observe #(db/q database query))]
       (is (= :seon.cluster/name value))
       (is (= 1 (get by-function 'seon.db/q)) "the query entrant is armed")
       ;; Armed scratch prototype: 43 before, 35 after on the same value.
       ;; Five calls of margin; the old repeated preparation exceeds it.
       (is (<= calls 40) "a scalar query stays within forty armed calls")))))

(deftest write-plan-selects-storage-once-for-all-values
  (let [projection (schema/declaration-projection)
        attribute :datahike.read/revision
        value {:datahike.read/attributes #{:seon.agent/id}
               :datahike.read/cache-eligible? false}
        encoded (schema.datahike/encode-attribute-value-in projection attribute value)
        decode (:seon.db/decode (#'seon.db/write-attribute-plan projection attribute false))
        {:seon.db-overhead/keys [by-function] decoded :seon.db-overhead/value}
        (observe #(mapv decode (repeat 100 encoded)))]
    (is (= (vec (repeat 100 value)) decoded))
    (is (= 100 (get by-function 'seon.cache/lookup)) "decoding remains armed")
    (is (zero? (get by-function 'seon.schema.datahike/compiled-storage 0))
        "a retained decoder does not traverse the storage schema per value")
    (is (nil? (decode "nil")) "canonical historical values are read without re-admission")
    (doseq [[stored rule] [[1 :seon.schema.datahike/storage-not-string]
                           ["{" :seon.schema.datahike/malformed-edn]]]
      (let [refusal (try (decode stored) (catch clojure.lang.ExceptionInfo error (ex-data error)))]
        (is (= attribute (:seon.schema.datahike/attr refusal)))
        (is (= rule (:seon.schema.datahike/rule refusal)))))))

(deftest declaration-projections-retain-owning-schema-validation
  (let [projection (schema/declaration-projection)
        schemas (#'seon.db/write-entity-schemas projection [:seon.fn/sym])]
    (is (= #{:seon.fn/fn} (set (get schemas :seon.fn/sym))))))

(deftest final-validation-asks-cell-storage-of-written-attributes-only
  ;; A declaration write leaves a new projection whose holder answers nothing
  ;; yet. Default, 2026-10-07: every defn commit then asked all 3,389 forms
  ;; whether they are vector-shaped (143-177 ms of a 190-315 ms validation).
  (support/with-database
   (fn [connection]
     (let [report (support/transacted! connection (support/agent-tx (db/db connection) "cells-probe"))
           after (:db-after report)
           projection (#'schema/with-compiled-cache (db/carried-projection after))
           {:seon.db-overhead/keys [value by-function]}
           (observe #(schema/call-with-projection
                      projection (fn [] (#'seon.db/write-report-error projection report))))
           entities (into #{} (map :e) (:tx-data report))
           read-datoms (into [] (mapcat #(db/datoms after :eavt %)) entities)
           bound (+ (count (:tx-data report)) (count read-datoms))
           asked (get by-function 'seon.schema.datahike/vector-shaped-attr-in? 0)
           plans (get by-function 'seon.db/write-attribute-plan 0)]
       (is (nil? value) "the agent write passes final validation")
       (is (< bound (count (:seon.schema.projection/forms projection)))
           "the bound is below the registry, so asking every form exceeds it")
       (is (<= asked bound)
           "cell storage is asked per attribute the write holds, never per registry form")
       (is (<= plans (* 4 bound))
           "write plans follow attempted datoms and the before/after owning values")
       (let [check (db/stored-value-check after :seon.agent/id :string)]
         (is (pos? (:seon.schema.usage/checked check)))
         (is (zero? (:seon.schema.usage/failing check))
             "registration reconstructs the stored values with the same write plans"))))))

(deftest datoms-chooses-each-attribute-decoder-once
  ;; Default, 2026-10-08: an AEVT read of :seon.fn/calls (107,203 datoms) cost
  ;; 13.6 ms in the read body against 0.9 ms through Datahike, asking every
  ;; datom's attribute and decoding each bridged value under its own projection.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           declarations (#'seon.db/read-declarations database 'seon.db/datoms)
           encoded? #(#'seon.db/edn-encoded? declarations %)
           raw (vec (d/datoms database :eavt))
           bridged (into #{} (comp (map :a) (filter encoded?)) raw)
           {:seon.db-overhead/keys [value by-function]} (observe #(db/datoms database :eavt))]
       (is (seq bridged) "the read holds bridged values")
       (is (= (mapv (fn [datom]
                      {:e (:e datom) :a (:a datom)
                       :v (if (encoded? (:a datom))
                            (schema.datahike/decode-attribute-value-in (schema/declaration-projection) (:a datom) (:v datom))
                            (:v datom))
                       :tx (:tx datom) :added (:added datom)})
                    raw)
              value)
           "native values pass through and bridged values decode as before")
       (is (zero? (get by-function 'seon.schema.datahike/decode-attribute-value-in 0))
           "no bridged value resolves its decoder again")
       (is (<= (get by-function 'seon.schema.datahike/attribute-decoder-in 0) (count bridged))
           "a decoder is chosen once per bridged attribute of the read")))))
