(ns seon.fn.shape-dependents-test
  "A schema edit re-derives the stored shapes of everything reaching it
  (docs/seon/issues/a-schema-edit-leaves-dependent-shape-fingerprints-stale.md).

  A reference shape's fingerprint carries its references' definition
  fingerprints, so editing `:seon.flow/work-launcher` moves the fingerprint of
  `:seon.env/environment`, which reaches it. The partial publication must then
  hand the environment's schema row and every arity consuming it one
  fingerprint, or call preparation refuses a supplier as incoherent."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as support]))

(defn- environment-fingerprints
  "Every `:seon.env/environment` shape fingerprint inside `row`."
  [row]
  (into #{}
        (comp (filter map?)
              (filter #(= :seon.env/environment (:seon.schema.shape/type %)))
              (map :seon.schema.shape/fingerprint))
        (tree-seq coll? seq row)))

(deftest a-schema-edit-re-derives-its-dependents-shapes
  (let [database @(:seon.db/connection (support/execution-handle nil))
        forms (update (schema.edn/packaged-forms) :seon.flow/work-launcher
                      #(into [:map {:description "A shape-dependents probe edit."}] (rest %)))
        rows (#'seon.fn/desired-rows
              {:seon.source/previous-database database
               :seon.fn/changed-paths #{"resources/seon/schemas/seon.flow.edn"}
               :seon.schema.projection/forms forms
               :seon.program/rows []}
              (constantly nil))
        by-identity (into {} (map (juxt #(or (some->> (:seon.schema/key %) (vector :seon.schema/key))
                                             (some->> (:seon.fn/sym %) (vector :seon.fn/sym)))
                                        identity))
                          rows)
        environment (get by-identity [:seon.schema/key :seon.env/environment])
        supplier (get by-identity [:seon.fn/sym 'seon.db/supplied-connection])
        consumer (get by-identity [:seon.fn/sym 'seon.env/scope])]
    (is (some? environment) "the dependent schema row is re-derived")
    (is (some? supplier) "a supplier consuming the dependent is re-derived")
    (is (some? consumer) "a consumer of the dependent is re-derived")
    (is (= 1 (count (into #{} (mapcat environment-fingerprints) rows)))
        "every re-derived row gives the environment one fingerprint")
    (is (= (environment-fingerprints (:seon.schema/shape environment))
           (environment-fingerprints (:seon.fn/arities supplier))
           (environment-fingerprints (:seon.fn/arities consumer)))
        "the supplier's argument matches the consumer's environment")))

(deftest a-publication-re-derives-shapes-an-earlier-one-left-stale
  ;; An earlier publication's drift: the environment's schema row holds a shape
  ;; whose fingerprint is not the environment's definition fingerprint.
  (support/with-database
   (fn [connection]
     (let [before @connection
           foreign (:db/id (:seon.schema/shape (db/pull before [{:seon.schema/shape [:db/id]}]
                                                        [:seon.schema/key :seon.flow/work-launcher])))
           _ (support/transacted! connection [[:db/add [:seon.schema/key :seon.env/environment]
                                               :seon.schema/shape foreign]])
           rows (#'seon.fn/desired-rows
                 {:seon.source/previous-database @connection
                  :seon.fn/changed-paths #{}
                  :seon.program/rows []}
                 (constantly nil))
           environment (some #(when (= :seon.env/environment (:seon.schema/key %)) %) rows)
           supplier (some #(when (= 'seon.db/supplied-connection (:seon.fn/sym %)) %) rows)]
       (is (some? environment) "the drifted schema row is re-derived without an edit")
       (is (= (environment-fingerprints (:seon.schema/shape environment))
              (environment-fingerprints (:seon.fn/arities supplier)))
           "its consumers are re-derived to the same fingerprint")))))
