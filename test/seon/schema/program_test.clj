(ns seon.schema.program-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(deftest rows-have-one-canonical-persisted-shape
  (let [attributes (set (schema/canonical-database-attributes))]
    (is (= [:enum :io :compute]
           (schema/schema-definition :seon.fn/workload)))
    (is (contains? attributes :seon.fn/workload))
    (is (contains? attributes :seon.ns.alias/local))
    (is (contains? attributes :seon.ns.refer/target-name)))
  (is (schema/valid-candidate-value?
       :seon.fn/fn
       {:seon.fn/sym "sample/f"
        :seon.schema.admission/source :core
        :seon.fn/ns [:seon.ns/name 'sample]
        :seon.fn/source "(defn ^{:seon.workload :io} f [x] x)"
        :seon.fn/arglists "([x])"
        :seon.fn/private? false
        :seon.fn/workload :io}))
  (is (schema/valid-candidate-value?
       :seon.ns/ns
       {:seon.ns/name 'sample
        :seon.schema.admission/source :core
        :seon.ns/source "(ns sample)"
        :seon.ns/requires #{}
        :seon.ns/aliases #{}
        :seon.ns/imports #{}
        :seon.ns/refers #{}}))
  (is (schema/valid-candidate-value?
       :seon.ns/ns
       {:seon.ns/name 'my.agents.source-less
        :seon.schema.admission/source :agent})
      "an agent namespace is valid without invented source bytes"))

(deftest program-owned-values-validate-at-their-producer
  (let [declaration
        {:seon.fn/sym "sample/f"
         :seon.schema.admission/source :core
         :seon.fn/ns [:seon.ns/name 'sample]
         :seon.fn/source "(defn f [x] x)"
         :seon.fn/arglists "([x])"
         :seon.fn/private? false}
        schema-declaration
        (program/declaration-row
         {:seon.schema/key :sample/value
          :seon.schema/form ":string"}
         :contracted :core)
        deletion
        {:seon.program/delete-identities [[:seon.fn/sym "sample/f"]]
         :seon.program/source "(ns-unmap 'sample 'f)"}
        artifact
        {:seon.fn.file/path "/repo/src/sample.clj"
         :seon.fn.file/digest (apply str (repeat 64 "0"))
         :seon.fn.file/rows [declaration]
         :seon.fn.file/identities [[:seon.fn/sym "sample/f"]]}
        manifest
        {:seon.fn.manifest/roots ["/repo/src"]
         :seon.fn.manifest/digest (apply str (repeat 64 "1"))
         :seon.fn.manifest/artifacts [artifact]
         :seon.fn.manifest/identities [[:seon.fn/sym "sample/f"]]}]
    (is (schema/valid-candidate-value? :seon.program/row declaration))
    (is (= {:seon.schema/key :sample/value
            :seon.schema/form ":string"
            :seon.schema.admission/source :core}
           schema-declaration))
    (is (schema/valid-candidate-value? :seon.program/row
                                       schema-declaration))
    (is (schema/valid-candidate-value? :seon.program/row deletion))
    (is (schema/valid-candidate-value? :seon.program/rows
                                       [declaration deletion]))
    (is (schema/valid-candidate-value? :seon.fn.file/artifact artifact))
    (is (schema/valid-candidate-value? :seon.fn.manifest/manifest manifest))))

(deftest curation-proof-values-have-declared-leaf-shapes
  (let [run-id "proof:sample"
        receipt {:seon.cluster.run/id run-id
                 :seon.cluster.eval/ordinal 0
                 :seon.cluster.run.form/source "(+ 1 1)"
                 :seon.cluster.eval/result-edn "2"
                 :seon.eval.drive/value 2
                 :seon.cluster.eval/error ""
                 :seon.error/kind :seon.eval.drive/absent
                 :seon.cluster.eval/at (java.util.Date.)}
        declaration {:seon.cluster.run/id run-id
                     :seon.cluster.eval/ordinal 0
                     :seon.program/identity [:seon.fn/sym "sample/f"]
                     :seon.program/source-attribute :seon.fn/source
                     :seon.program/source "(defn f [] 1)"}
        terminal {:seon.eval.drive/outcome :completed
                  :seon.eval.drive/run-ids [run-id]}]
    (is (schema/valid-candidate-value?
         :seon.cluster.curate/proof-receipt receipt))
    (is (schema/valid-candidate-value?
         :seon.cluster.curate/receipts [receipt]))
    (is (schema/valid-candidate-value?
         :seon.cluster.curate/declaration declaration))
    (is (schema/valid-candidate-value?
         :seon.cluster.curate/declarations [declaration]))
    (is (schema/valid-candidate-value?
         :seon.eval.drive/terminal-state terminal))
    (is (schema/valid-candidate-value?
         :seon.cluster.curate/terminal terminal))
    (is (not (schema/valid-candidate-value?
              :seon.cluster.curate/proof-receipt
              (dissoc receipt :seon.cluster.eval/at))))
    (is (not (schema/valid-candidate-value?
              :seon.eval.drive/terminal-state
              (assoc terminal :seon.eval.drive/outcome :unknown))))))

(deftest database-shape-render-declarations-resolve
  (let [catalog (schema/entity-catalog)
        program-keys #{:seon.fn/fn :seon.ns/ns :seon.schema/schema}]
    (testing "the namespace family declares both render projections"
      (doseq [row (filter (comp #{:seon.ns/ns} :seon.schema/key)
                          catalog)]
        (is (contains? row :seon.render/ai))
        (is (contains? row :seon.render/html))))
    (testing "families with no built specialist stay bare"
      (doseq [row (filter (comp (disj program-keys :seon.ns/ns)
                                :seon.schema/key)
                          catalog)]
        (is (not (contains? row :seon.render/ai)))
        (is (not (contains? row :seon.render/html)))))
    (testing "every projection the catalog still advertises is loadable"
      (doseq [row catalog
              projection-key [:seon.render/ai :seon.render/html]
              :let [projection (get row projection-key)]
              :when projection]
        (is (var? (requiring-resolve projection))
            (str (:seon.schema/key row)
                 " advertises missing " projection-key " " projection))))))
