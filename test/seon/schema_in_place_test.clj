(ns seon.schema-in-place-test
  "Every schema difference adopts in place on the branch's connection.

  Owner ruling 2026-09-23: \"A schema change should not require a from scratch
  boot. Period.\" One regression per class — add, accretive change,
  non-accretive change with data, retirement with data, and a retirement
  refused because a program row still writes the attribute — each through the
  schema population owner `seon.cluster/accrete-schema-population!`.

  The store is a fresh memory store the same owner populates from the packaged
  declarations — the schema population's own input — rather than the canonical
  fixture, so the regression does not depend on a published base
  (`docs/seon/issues/test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster]
            [seon.fn]
            [seon.db :as db]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as support]))

(def ^:private fixture-forms
  "The declarations the branch is brought TO."
  {:schema.in-place/id [:string {:seon.db/identity true}]
   :schema.in-place/value :int
   :schema.in-place/indexed [:string {:seon.db/index true}]
   :schema.in-place/added :string
   :schema.in-place/history :string
   :schema.in-place/key [:string {:seon.db/identity true}]
   :schema.in-place/row
   [:map {:seon.db/attributes true :seon.program/partition :seon.data}
    [:schema.in-place/id :schema.in-place/id]
    [:schema.in-place/value {:optional true} :schema.in-place/value]
    [:schema.in-place/indexed {:optional true} :schema.in-place/indexed]
    [:schema.in-place/added {:optional true} :schema.in-place/added]
    [:schema.in-place/history {:optional true} :schema.in-place/history]
    [:schema.in-place/key {:optional true} :schema.in-place/key]]})

(def ^:private installed-schema
  "The declarations the branch was forked WITH."
  [{:db/ident :schema.in-place/id :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :schema.in-place/value :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :schema.in-place/indexed :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :schema.in-place/gone :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :schema.in-place/history :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/noHistory true}
   {:db/ident :schema.in-place/key :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/value}])

(def ^:private population
  (delay (schema/declaration-projection
          (merge (schema.edn/packaged-forms) fixture-forms))))

(defn- adopt!
  "Run the one schema population owner with the fixture declarations handed."
  [connection]
  (schema/call-with-projection
   @population
   #(@#'seon.cluster/accrete-schema-population! connection nil false)))

(defn- values
  [connection attribute]
  (set (db/q [:find '[?v ...] :where ['_ attribute '?v]] (db/db connection))))

(defn- with-population
  "Run `body` on a fresh memory store carrying the packaged population and
   the installed fixture declarations, released and deleted afterwards."
  [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :keep-history? true
                       :schema-flexibility :write}]
    (d/create-database configuration)
    (try
      (let [connection (d/connect configuration)]
        (try
          (schema/call-with-projection
           (schema/declaration-projection (schema.edn/packaged-forms))
           #(@#'seon.cluster/accrete-schema-population! connection nil false))
          (support/transacted! connection installed-schema)
          (body connection)
          (finally (d/release connection))))
      (finally (d/delete-database configuration)))))

(defn- adopted-fixture
  [body]
  (with-population
    (fn [connection]
      (support/transacted!
       connection
       [{:schema.in-place/id "a" :schema.in-place/value "one"
         :schema.in-place/indexed "i" :schema.in-place/gone "g"
         :schema.in-place/history "h" :schema.in-place/key "k"}
        {:schema.in-place/id "b" :schema.in-place/value "two"}])
      (body connection (adopt! connection)))))

(deftest a-missing-declaration-is-added-in-place
  (adopted-fixture
   (fn [connection _]
     (is (= :db.type/string
            (get-in (:schema (db/db connection))
                    [:schema.in-place/added :db/valueType])))
     (support/transacted! connection [{:schema.in-place/id "a"
                                       :schema.in-place/added "new"}])
     (is (= #{"new"} (values connection :schema.in-place/added))))))

(deftest an-accretive-change-keeps-its-data
  (adopted-fixture
   (fn [connection _]
     (is (true? (get-in (:schema (db/db connection))
                        [:schema.in-place/indexed :db/index])))
     (is (= #{"i"} (values connection :schema.in-place/indexed))
         "an added index keeps the attribute's datoms and backfills AVET")
     (is (= 1 (count (db/datoms (db/db connection) :avet
                                :schema.in-place/indexed "i"))))
     (let [installed (:schema (db/db connection))]
       (is (not (contains? (get installed :schema.in-place/history) :db/noHistory))
           "a dropped :db/noHistory is retracted from the attribute in place")
       (is (= :db.unique/identity (get-in installed [:schema.in-place/key :db/unique]))
           "a unique cardinality-one attribute switches uniqueness in place"))
     (is (= #{"h"} (values connection :schema.in-place/history)))
     (is (= #{"k"} (values connection :schema.in-place/key))
         "both keep their data"))))

(deftest a-non-accretive-change-replaces-the-attribute-and-drops-its-data
  (adopted-fixture
   (fn [connection _]
     (let [database (db/db connection)]
       (is (= :db.type/long
              (get-in (:schema database) [:schema.in-place/value :db/valueType]))
           "the value type changed in place")
       (is (empty? (values connection :schema.in-place/value))
           "the data behind the old type is dropped")
       (is (= #{"a" "b"} (values connection :schema.in-place/id))
           "and the entities keep every other attribute"))
     (support/transacted! connection [{:schema.in-place/id "a"
                                       :schema.in-place/value 42}])
     (is (= #{42} (values connection :schema.in-place/value))
         "the replaced attribute accepts values of its new type"))))

(deftest a-retired-attribute-is-retracted-with-its-data
  (adopted-fixture
   (fn [connection _]
     (let [database (db/db connection)]
       (is (not (contains? (:schema database) :schema.in-place/gone))
           "an attribute no declaration names leaves the installed schema")
       (is (nil? (:db/id (db/pull database [:db/id] [:db/ident :schema.in-place/gone]))))
       (is (= #{"i"} (values connection :schema.in-place/indexed))
           "and its entity keeps its other attributes")))))

(deftest a-retirement-refuses-while-a-program-row-writes-the-attribute
  (with-population
    (fn [connection]
      (let [writer 'schema.in-place/write!
            source "(defn write! [connection] (seon.db/transact! connection [{:schema.in-place/gone \"g\"}]))"
            projection @population
            namespace-row (program/declaration-row
                           projection
                           {:seon.ns/name 'schema.in-place
                            :seon.ns/source "(ns schema.in-place)"} :all :agent)]
        (support/transacted!
         connection
         [{:schema.in-place/id "a" :schema.in-place/gone "g"} namespace-row])
        ;; The production source-row producer with a complete namespace row
        ;; (`support/program-fn-row` supplies a namespace without its
        ;; definition digest, which `seon.fn/source-rows` now refuses).
        (support/transacted!
         connection
         [(some #(when (= writer (:seon.fn/sym %)) %)
                (seon.fn/source-rows
                 (db/db connection) (program/shapes-in projection) namespace-row
                 source (set (keys (:seon.schema.projection/forms projection)))))])
        (let [basis (db/basis-t (db/db connection))
              refusal (support/refusal-data #(adopt! connection))
              message (pr-str refusal)]
          (is (str/includes? message (str writer))
              "the refusal names the surviving writer")
          (is (str/includes? message ":seon.fn/writes"))
          (is (= basis (db/basis-t (db/db connection)))
              "nothing committed")
          (is (contains? (:schema (db/db connection)) :schema.in-place/gone)
              "the attribute stays installed")
          (is (= #{"g"} (values connection :schema.in-place/gone))
              "with its data"))))))

(deftest an-adopted-branch-reopens-with-no-declaration-change
  (adopted-fixture
   (fn [connection _]
     (is (= [] (@#'seon.cluster/declaration-changes (db/db connection) @population))
         "after adoption the installed schema equals the declared schema")
     (let [basis (db/basis-t (db/db connection))]
       (adopt! connection)
       (is (= basis (db/basis-t (db/db connection)))
           "and a converged reopen issues no transaction")))))

(deftest a-replacement-purges-the-data-then-retracts-and-reinstalls-the-attribute
  ;; Datahike refuses an in-place `:db/valueType` update and refuses
  ;; retracting an attribute that still carries current datoms
  ;; (`reference-code/datahike/src/datahike/db/transaction.cljc:137`), so the
  ;; change is expressed in that order in one transaction. On this temporal
  ;; store the data is purged, history included, so the new type's first write
  ;; to the same entity never meets an old-type history datom.
  (with-population
    (fn [connection]
      (support/transacted! connection [{:schema.in-place/id "a" :schema.in-place/value "one"}])
      (support/transacted! connection [{:schema.in-place/id "a" :schema.in-place/value "uno"}])
      (let [entity (:e (first (d/datoms (db/db connection) :aevt :schema.in-place/value)))
            tx-data (@#'seon.cluster/declaration-changes (db/db connection) @population)
            at (.indexOf ^java.util.List tx-data [:db/retractEntity :schema.in-place/value])]
        (is (<= 0 at))
        (is (= [[:db.purge/attribute entity :schema.in-place/value]]
               (filterv #(and (vector? %) (= :schema.in-place/value (nth % 2 nil))) tx-data))
            "each carrying entity's attribute is purged once, never the entity")
        (is (< (.indexOf ^java.util.List tx-data [:db.purge/attribute entity :schema.in-place/value]) at)
            "before the attribute is retracted")
        (is (= {:db/ident :schema.in-place/value :db/valueType :db.type/long
                :db/cardinality :db.cardinality/one}
               (get tx-data (inc at)))
            "and the declaration is reinstalled right after it")
        (adopt! connection)
        (is (empty? (d/datoms (d/history (db/db connection)) :aevt :schema.in-place/value))
            "no datom of the old type survives, in history either")
        (support/transacted! connection [{:schema.in-place/id "a" :schema.in-place/value 7}])
        (is (= #{7} (values connection :schema.in-place/value)))))))

(deftest a-drop-names-the-files-whose-program-rows-carried-it
  (with-population
    (fn [connection]
      (support/transacted!
       connection
       [{:seon.fn.file/relative-path "src/schema/in_place.clj"
         :seon.fn.file/digest (apply str (repeat 64 "0"))}])
      (support/transacted!
       connection
       [{:schema.in-place/id "a" :schema.in-place/value "one" :schema.in-place/gone "g"
         :seon.fn/file [:seon.fn.file/relative-path "src/schema/in_place.clj"]}
        {:schema.in-place/id "b" :schema.in-place/value "two"}])
      (let [database (db/db connection)
            dropped (@#'seon.cluster/dropped-attributes database @population)]
        (is (= #{:schema.in-place/value :schema.in-place/gone} (set dropped)))
        (is (= {:seon.db/datom-count 3
                :seon.fn/changed-paths #{"src/schema/in_place.clj"}}
               (@#'seon.cluster/dropped-summary database dropped))
            "every dropped datom is counted and only the carrying file is re-derived")))))

(deftest a-tightened-form-drops-only-the-values-it-refuses
  ;; `[:string]` to `[:string {:min 3}]` changes no Datahike declaration, so
  ;; the attribute is kept; the current values the new form refuses are the
  ;; problematic data, and only they are dropped.
  (with-population
    (fn [connection]
      (adopt! connection)
      (support/transacted! connection [{:schema.in-place/id "a" :schema.in-place/history "h"}
                                       {:schema.in-place/id "b" :schema.in-place/history "hello"}])
      (let [tightened (schema/declaration-projection
                       (assoc (merge (schema.edn/packaged-forms) fixture-forms)
                              :schema.in-place/history [:string {:min 3}]))
            database (db/db connection)
            declarations (into {} (map (juxt :db/ident identity))
                               (@#'seon.cluster/declared-attributes tightened))
            tx-data (seon.cluster/attribute-change-tx
                     database [:schema.in-place/history] declarations tightened)]
        (is (= [[:db/retract (:db/id (db/pull database [:db/id] [:schema.in-place/id "a"]))
                 :schema.in-place/history "h"]]
               tx-data))
        (schema/call-with-projection
         tightened #(support/transacted! connection tx-data))
        (is (= #{"hello"} (values connection :schema.in-place/history)))))))
