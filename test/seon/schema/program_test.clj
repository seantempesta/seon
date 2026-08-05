(ns seon.schema.program-test
  (:require [clojure.test :refer [deftest is testing]]
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
    (is (schema/valid-candidate-value? :seon.program/row deletion))
    (is (schema/valid-candidate-value? :seon.program/rows
                                       [declaration deletion]))
    (is (schema/valid-candidate-value? :seon.fn.file/artifact artifact))
    (is (schema/valid-candidate-value? :seon.fn.manifest/manifest manifest))))

(deftest catalog-render-declarations-resolve
  (let [catalog (schema/entity-catalog)
        program-keys #{:seon.fn/fn :seon.ns/ns :seon.schema/schema}]
    (testing "the namespace family declares both render projections"
      (doseq [row (filter (comp #{:seon.ns/ns} :seon.schema.catalog/key)
                          catalog)]
        (is (contains? row :seon.schema.catalog/render-ai))
        (is (contains? row :seon.schema.catalog/render-html))))
    (testing "families with no built specialist stay bare"
      (doseq [row (filter (comp (disj program-keys :seon.ns/ns)
                                :seon.schema.catalog/key)
                          catalog)]
        (is (not (contains? row :seon.schema.catalog/render-ai)))
        (is (not (contains? row :seon.schema.catalog/render-html)))))
    (testing "every projection the catalog still advertises is loadable"
      (doseq [row catalog
              projection-key [:seon.schema.catalog/render-ai
                              :seon.schema.catalog/render-html]
              :let [projection (get row projection-key)]
              :when projection]
        (is (var? (requiring-resolve projection))
            (str (:seon.schema.catalog/key row)
                 " advertises missing " projection-key " " projection))))))
