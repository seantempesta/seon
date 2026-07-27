(ns seon.render.value-projection-fixture
  "Literal cross-runtime fixture for schema-aware value drill parity.")

(def rows
  {:seon.schema/schema-rows
   #{[:projection.test/id ":int"]
     [:projection.test/shape
      "[:map [:projection.test/id :projection.test/id]]"]}
   :seon.schema/function-contract-rows #{}})

(defn- request [page-size]
  {:seon.render.value/path []
   :seon.render.value/offset 0
   :seon.render.value/effective-limits
   {:seon.config.render/value-max-path-segments 32
    :seon.config.render/value-max-path-bytes 4096
    :seon.config.render/value-max-realized-items 32
    :seon.config.render/value-max-depth 3
    :seon.config.render/value-max-string 80
    :seon.config.render/value-shape-sample 8
    :seon.render.value/page-size page-size}})

(def values
  [{:projection.test/id 1}
   {:projection.test/id "wrong"}
   (array-map :projection.test/id 1 :projection.test/extra 2)])

(def requests [(request 8) (request 8) (request 1)])

(def expected-bytes
  ["{:seon.render.value/ok? true, :seon.render.value/availability :available, :seon.render.value/projection {:seon.render.value/path [], :seon.render.value/offset 0, :seon.render.value/page-size 8, :seon.render.value/summary \"map\", :seon.render.value/truncated? false, :seon.render.value/more? false, :seon.render.value/tree {:seon.render.value/map-entries [[:projection.test/id 1]]}, :seon.render.value/schemas [{:seon.schema/key :projection.test/shape, :seon.schema/entity? false, :seon.render.value/status :valid}]}}"
   "{:seon.render.value/ok? true, :seon.render.value/availability :available, :seon.render.value/projection {:seon.render.value/summary \"map\", :seon.render.value/truncated? false, :seon.render.value/schemas [{:seon.schema/key :projection.test/shape, :seon.schema/entity? false, :seon.render.value/status :invalid}], :seon.render.value/offset 0, :seon.render.value/more? false, :seon.render.value/path [], :seon.render.value/explanation {:seon.render.value/humanized {:projection.test/id [\"should be an integer\"]}, :seon.render.value/error-value {:projection.test/id \"wrong\"}}, :seon.render.value/page-size 8, :seon.render.value/tree {:seon.render.value/map-entries [[:projection.test/id \"wrong\"]]}}}"
   "{:seon.render.value/ok? true, :seon.render.value/availability :available, :seon.render.value/projection {:seon.render.value/path [], :seon.render.value/offset 0, :seon.render.value/page-size 1, :seon.render.value/summary \"map\", :seon.render.value/truncated? true, :seon.render.value/more? false, :seon.render.value/tree {:seon.render.value/map-entries [[:projection.test/id 1]], :seon.render.value/elided-keys :more}, :seon.render.value/schemas [{:seon.schema/key :projection.test/shape, :seon.schema/entity? false, :seon.render.value/status :shape-only}]}}"])

(def expected-fingerprint -1403865203)

(def expected-shape-rows-bytes
  "[{:seon.schema/key :projection.test/shape, :seon.schema/required-attrs #{:projection.test/id}, :seon.schema/entity? false}]")

(def nested-rows
  {:seon.schema/schema-rows
   #{[:projection.test/id ":int"]
     [:projection.test/nested
      "[:map {:seon.db/entity false :projection.test/a 1 :projection.test/b 2 :projection.test/c 3 :projection.test/d 4 :projection.test/e 5 :projection.test/f 6 :projection.test/g 7 :projection.test/h 8 :projection.test/i 9 :projection.test/labels #{:projection.test/z :projection.test/a}} [:projection.test/id {:optional true} :projection.test/id]]"]}
   :seon.schema/function-contract-rows #{}})

(def expected-nested-fingerprint -1917308117)
