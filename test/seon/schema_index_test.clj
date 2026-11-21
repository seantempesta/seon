(ns seon.schema-index-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.code :as code]
            [seon.schema :as schema]
            [seon.schema.index :as six]))

(deftest entity-index-multi-facet-inheritance-and-reaching-test
  (let [forms {:seon.demo/base-id    [:keyword {:seon.db/identity true}]
               :seon.demo/base-label :string
               :seon.demo/base       [:map {:seon.db/attributes true
                                            :seon.render/hiccup 'seon.demo/render-base}
                                      [:seon.demo/base-id :seon.demo/base-id]
                                      [:seon.demo/base-label :seon.demo/base-label]]
               :seon.demo/extra      :int
               :seon.demo/child      [:and {:seon.db/attributes true}
                                      :seon.demo/base
                                      [:map [:seon.demo/extra :seon.demo/extra]]]
               :seon.demo/tag-name   [:keyword {:seon.db/identity true}]
               :seon.demo/tag        [:map {:seon.db/attributes true}
                                      [:seon.demo/tag-name :seon.demo/tag-name]]
               :seon.demo/ref-base   [:seon.store/ref {:seon.db/to [:seon.demo/base]}]
               :seon.demo/holder     [:map {:seon.db/attributes true}
                                      [:seon.demo/ref-base :seon.demo/ref-base]]}
        ix (six/build-entity-index forms)]
    (testing "inheritance, parents, extends?, and inherited render property"
      (is (= [:seon.demo/base] (six/parents-of ix :seon.demo/child)))
      (is (six/extends? ix :seon.demo/child :seon.demo/base))
      (is (not (six/extends? ix :seon.demo/base :seon.demo/child)))
      (is (= 'seon.demo/render-base (six/schema-property ix :seon.demo/child :seon.render/hiccup))))

    (testing "targets and reaching traverse inheritance"
      (is (= [:seon.demo/base] (six/targets ix :seon.demo/ref-base)))
      (is (= [:seon.demo/ref-base] (six/reaching ix :seon.demo/base)))
      (is (= [:seon.demo/ref-base] (six/reaching ix :seon.demo/child))))

    (testing "child beats parent in most-specific, and multi-facet entity surfaces all facets"
      (let [child-ent {:seon.demo/base-id :b1 :seon.demo/base-label "hi" :seon.demo/extra 7}
            multi-ent (assoc child-ent :seon.demo/tag-name :t1)
            facets (six/facets-of ix multi-ent)]
        (is (= :seon.demo/child (six/most-specific ix child-ent)))
        (is (= #{:seon.demo/base :seon.demo/child :seon.demo/tag}
               (set (map :schema facets))))))))

(deftest infer-result-schema-uses-entity-index-test
  (is (= :seon.health/report (code/infer-result-schema {:ok? true :problems [] :counts {} :branch :main})))
  (is (= :seon.bench.run/entity (code/infer-result-schema {:seon.bench.run/id (random-uuid)})))
  (is (= :seon.fix/entity (code/infer-result-schema [:seon.fix/id :seon.fix/demo])))
  (is (schema/multi-segment-schema-key? :seon.var/name))
  (is (schema/multi-segment-schema-key? :malli/schema))
  (is (schema/multi-segment-schema-key? :git/sha))
  (is (not (schema/multi-segment-schema-key? :random/shallow))))
