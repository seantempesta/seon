(ns seon.schema.projection-acquisition-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.datahike :as bridge]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Compares indexed acquisition with the original whole-population Datalog joins on the canonical fixture."
           :seon.test/long-ms 10000}
  indexed-projection-rows-preserve-the-query-result
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)]
        (doseq [[identity-attribute value-attribute identity-tx?]
                [[:seon.schema/key :seon.schema/form true]
                 [:seon.fn/sym :seon.fn/spec false]
                 [:seon.fn/sym :seon.fn/source false]]]
          (let [query (if identity-tx?
                        '[:find ?identity ?value ?tx :in $ ?identity-attr ?value-attr
                          :where [?e ?identity-attr ?identity ?tx] [?e ?value-attr ?value]]
                        '[:find ?identity ?value ?tx :in $ ?identity-attr ?value-attr
                          :where [?e ?identity-attr ?identity] [?e ?value-attr ?value ?tx]])
                expected (d/q query database identity-attribute value-attribute)]
            (is (seq expected))
            (is (= expected
                   (set (#'schema/projection-rows database identity-attribute
                                                   value-attribute identity-tx?))))))
        (is (= (into {}
                     (map (fn [[attribute identity source]]
                            [[attribute identity] {:seon.schema.admission/source source}]))
                     (d/q '[:find ?attribute ?identity ?source
                            :in $ [?attribute ...]
                            :where [?e ?attribute ?identity]
                                   [?e :seon.schema.admission/source ?source]]
                          database [:seon.schema/key :seon.fn/sym]))
               (#'schema/projection-admissions database)))))))

(deftest shared-schema-descendants-are-validated-once-per-role
  (support/with-database
    (fn [connection]
      (let [projection (db/carried-projection (db/db connection))
            forms (assoc (:seon.schema.projection/forms projection)
                         ::leaf :string
                         ::left [:or ::leaf :int]
                         ::right [:or ::leaf :boolean]
                         ::root [:tuple ::left ::right])
            visits (atom {})
            original bridge/assert-storable-schema!
            built (with-redefs [bridge/assert-storable-schema!
                                (fn [identity compiled]
                                  (when (#{::leaf ::left ::right ::root} identity)
                                    (swap! visits update identity (fnil inc 0)))
                                  (original identity compiled))]
                    (schema/build-projection
                     forms {'seon.schema.projection-acquisition-test/contract
                            [:=> [:cat ::root] ::root]}))
            options (:seon.schema.projection/compile-options built)]
        (is (= {::leaf 3 ::left 3 ::right 3 ::root 3} @visits))
        (is (seq (:seon.schema.projection/shape-rows built)))
        (is (= (:seon.schema.projection/shape-rows projection)
               (:seon.schema.projection/shape-rows built)))
        (is (m/validate ::root ["x" true] options))
        (is (not (m/validate ::root [[] true] options)))))))

(deftest core-reference-sharing-preserves-agent-output-refusal
  (support/with-database
    (fn [connection]
      (let [projection (db/carried-projection (db/db connection))
            forms (:seon.schema.projection/forms projection)
            admissions (zipmap (keys forms)
                               (repeat {:seon.schema.admission/source :core}))
            sym 'seon.schema.projection-acquisition-test/nilable
            contracts {sym [:=> [:cat] [:maybe :string]]}
            options {:seon.schema/schema-admissions admissions}
            built (schema/build-projection forms contracts options)
            refused (try
                      (schema/build-projection
                       forms contracts
                       (assoc options :seon.schema/function-admissions
                              {sym {:seon.schema.admission/source :agent}}))
                      nil
                      (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
        (is (= contracts (:seon.schema.projection/function-contracts built)))
        (is (= :seon.schema/nilable-return (:seon.schema/error refused)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (schema/build-projection
                      forms {sym [:=> [:cat ::absent] :string]} options)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (schema/build-projection
                      (assoc forms ::invalid-error
                             [:and :seon.error/base [:map [::unregistered :string]]])
                      {}
                      (assoc-in options
                                [:seon.schema/schema-admissions ::invalid-error]
                                {:seon.schema.admission/source :core}))))))))

(deftest stored-declarations-reuse-only-identical-supplied-definitions
  (support/with-database
    (fn [connection]
      (let [forms (assoc (:seon.schema.projection/forms
                          (db/carried-projection (db/db connection))) ::retained :int)
            supplied (schema/declaration-projection forms)
            built (schema/build-projection forms {} {:seon.schema/projection supplied})
            changed (schema/build-projection (assoc forms ::retained :string) {}
                                             {:seon.schema/projection supplied})
            root #(mr/schema (:seon.schema.projection/registry %) ::retained)]
        (is (identical? (root supplied) (root built)))
        (is (m/validate (root built) 1))
        (is (not (m/validate (root built) "one")))
        (is (not (identical? (root supplied) (root changed))))
        (is (m/validate (root changed) "one"))
        (is (not (m/validate (root changed) 1)))))))
