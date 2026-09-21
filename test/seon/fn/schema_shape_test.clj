(ns seon.fn.schema-shape-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.call-preparation :as preparation]
            [seon.db :as db]
            [seon.fn.schema-shape :as shape]
            [seon.schema :as schema]
            [seon.test-support :as support]))

; Acquire the canonical published base in fixture setup, before individual
; test bounds. Each test still receives its own ordinary fixture branch.
(use-fixtures :once
  (fn [run-tests]
    (support/with-database (fn [_connection] nil))
    (run-tests)))

(defn- rows-in
  {:malli/schema [:=> [:cat :map] [:sequential :map]]}
  [row]
  (filter #(and (map? %) (:seon.schema.shape/form %))
          (tree-seq coll? seq row)))

(defn canonical-rows
  "Encode the real fixture's canonical schema names and function contracts.
  This exercises the current writer even when the fast base predates the reset."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :map]]}
  [database]
  (let [projection (db/carried-projection database)
        forms (shape/prepare-forms projection)
        options (:seon.schema.projection/compile-options projection)
        predicates (schema/predicate-functions-in projection)
        registry (:seon.schema.projection/registry projection)
        contracts (:seon.schema.projection/function-contracts projection)
        _ (when-not (seq contracts)
            (throw (ex-info "The canonical fixture has no function contracts." {})))
        compiled (concat (map #(m/schema % options) (keys forms))
                         (map #(mr/schema registry %) (keys contracts)))]
    (vec (vals
     (reduce
      (fn [rows compiled]
        (reduce (fn [rows row] (assoc rows (:seon.schema.shape/fingerprint row) row))
                rows (rows-in (shape/shape-row compiled forms predicates))))
      {} compiled)))))

(deftest canonical-fixture-shape-strings-stay-small
  (support/with-database
   (fn [connection]
     (let [rows (canonical-rows (db/db connection))
           strings (map :seon.schema.shape/form rows)
           sizes (map #(alength (.getBytes ^String % java.nio.charset.StandardCharsets/UTF_8)) strings)]
       (println "canonical authored shape strings"
                {:rows (count rows) :utf8-bytes (reduce + sizes) :maximum-bytes (apply max sizes)})
       (is (> (count rows) 1000) "The full canonical population must be present.")
       (is (< (apply max sizes) 4096))
       (is (< (reduce + sizes) 1000000))
       (is (every? (fn [row]
                     (or (empty? (:seon.schema.shape/entries row))
                         (= [(first (edn/read-string (:seon.schema.shape/form row)))]
                            (edn/read-string (:seon.schema.shape/form row))))) rows))))))

(deftest structural-rows-store-each-subtree-only-once
  (let [form [:map [:sample/a [:map [:sample/leaf :int]]]
                   [:sample/b [:map [:sample/leaf :int]]]
                   [:sample/c [:vector [:map [:sample/leaf :string]]]]]
        row (shape/shape-row (m/schema form))]
    (is (= form (shape/row-form row)))
    (is (every? (fn [row]
                  (let [form (edn/read-string (:seon.schema.shape/form row))]
                    (or (not (vector? form)) (= 1 (count form)))))
                (rows-in row)))
    (is (m/validate (shape/row-form row)
                    {:sample/a {:sample/leaf 1} :sample/b {:sample/leaf 2}
                     :sample/c [{:sample/leaf "yes"}]}))
    (is (not (m/validate (shape/row-form row)
                         {:sample/a {:sample/leaf "wrong"} :sample/b {:sample/leaf 2}
                          :sample/c []})))))

(deftest authored-identity-follows-references-without-inferring-equivalence
  (let [row (fn [forms form]
              (shape/shape-row
               (m/schema form {:registry (mr/composite-registry forms (m/default-schemas))}) forms))
        before {:sample/value :int :sample/request [:map [:sample/value :sample/value]]}
        after (assoc before :sample/value :string)
        named (row before :sample/request)
        inline (row before [:map [:sample/value :sample/value]])]
    (is (= ":sample/request" (:seon.schema.shape/form named)))
    (is (nil? (:seon.schema.shape/entries named)))
    (is (not= (:seon.schema.shape/fingerprint named)
              (:seon.schema.shape/fingerprint inline)))
    (is (not= (:seon.schema.shape/fingerprint named)
              (:seon.schema.shape/fingerprint (row after :sample/request))))
    (is (= (:seon.schema.shape/fingerprint named)
           (:seon.schema.shape/fingerprint (row (assoc before :sample/unrelated :boolean)
                                                :sample/request))))))

(deftest local-recursive-registry-remains-authored
  (let [form [:schema {:registry {:sample/node [:or :int [:vector [:ref :sample/node]]]}}
              [:ref :sample/node]]
        row (shape/shape-row (m/schema form))]
    (is (= form (shape/row-form row)))
    (is (nil? (:seon.schema.shape/children row)))
    (is (m/validate (shape/row-form row) [1 [2]]))))

(deftest stored-shape-reconstruction-preserves-the-contract
  (support/with-database
   (fn [connection]
     (let [form [:map [:sample/value [:vector [:map [:sample/count :int]]]]]
           row (shape/shape-row (m/schema form))
           _ (support/transacted! connection [row])
           database (db/db connection)
           entity (db/q database '[:find ?e . :in $ ?fingerprint
                                   :where [?e :seon.schema.shape/fingerprint ?fingerprint]]
                        (:seon.schema.shape/fingerprint row))
           compiled (shape/compiled-in database entity)]
       (is (= form (shape/database-form database entity)))
       (is (m/validate compiled {:sample/value [{:sample/count 2}]}))
       (is (not (m/validate compiled {:sample/value [{:sample/count "wrong"}]})))
       (is (not (m/validate compiled {})))))))

(deftest fingerprint-reuse-compares-owned-edges
  (let [row (shape/shape-row (m/schema [:map [:sample/value :int]]))
        changed (assoc-in row [:seon.schema.shape/entries 0 :seon.schema.map-entry/key-edn]
                          ":sample/other")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"distinct normalized forms"
                         (shape/assert-consistent! [row changed])))))

(deftest named-map-defaults-derive-from-the-carried-registry
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           current (preparation/snapshot database (db/carried-projection database))
           entries (:seon.schema.shape/supplied-map-entries current)]
       (is (seq (:seon.call-preparation/supplied-defaults current)))
       (is (seq entries))
       (is (some #(= [:my.agent/settings-request :seon.agent/id]
                     (subvec % 0 2)) entries))))))

(defn inline-default-contracts
  "Audit actual canonical contracts for former structural default matches.
  Names, files and spans come from program facts; no source-name inference."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :map]]}
  [database]
  (let [projection (db/carried-projection database)
        registry (:seon.schema.projection/registry projection)
        predicates (schema/predicate-functions-in projection)
        defaults (db/q database '[:find ?key ?name
                                  :where [?row :seon.call-preparation/key ?key]
                                         [?row :seon.call-preparation/schema ?schema]
                                         [?schema :seon.schema/key ?name]])
        declarations (db/q database '[:find ?sym ?path ?span
                                      :where [?f :seon.fn/sym ?sym]
                                             [?f :seon.fn/file ?file]
                                             [?file :seon.fn.file/relative-path ?path]
                                             [?f :seon.fn/form-span ?span]])
        expanded (fn [compiled]
                   (schema/canonical-definition (m/form (m/deref-recursive compiled)) predicates))
        shallow (fn [compiled]
                  (let [compiled (m/deref-all compiled)]
                    [(m/type compiled) (m/properties compiled)
                     (if (= :map (m/type compiled))
                       (set (map first (m/entries compiled)))
                       (count (m/children compiled)))]))
        candidates (mapv (fn [[default-key schema-name]]
                           (let [compiled (mr/schema registry schema-name)]
                             [default-key schema-name (shallow compiled) (expanded compiled)])) defaults)]
    (vec
     (for [[sym path span] (sort-by first declarations)
           :let [contract (mr/schema registry sym)]
           :when (and contract (seq (get (:seon.schema.projection/function-contracts projection) sym)))
           [ordinal arity] (map-indexed vector (m/-function-schema-arities contract))
           :let [input (:input (m/-function-info arity))]
           [index slot] (map-indexed vector (m/children input))
           :let [slot (if (= :catn (m/type input)) (nth slot 2) slot)
                 resolved (m/deref-all slot)
                 slots (cons [nil slot]
                             (when (= :map (m/type resolved))
                               (keep (fn [[entry-key properties child]]
                                       (when-not (:optional properties) [entry-key child]))
                                     (m/children resolved))))]
           [entry child] slots
           [default-key schema-name skeleton definition] candidates
           :when (and (or (nil? entry) (= entry default-key))
                      (not= schema-name (m/form child))
                      (= skeleton (shallow child))
                      (= definition (expanded child)))]
       {:seon.fn/sym sym :seon.fn.file/relative-path path :seon.fn/form-span span
        :seon.fn.arity/order ordinal :seon.fn.argument/index index
        :seon.call-preparation/key default-key :seon.call-preparation/schema-key schema-name
        :seon.schema.shape/form (pr-str (schema/canonical-definition (m/form child) predicates))}))))
