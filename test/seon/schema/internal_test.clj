(ns seon.schema.internal-test
  "The compiled-schema readers of entity composition."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.registry :as mr]
            [malli.util :as mu]
            [seon.schema :as schema]
            [seon.schema.internal :as internal]))

(deftest a-merge-variant-extends-the-error-base
  (let [registry (mr/composite-registry
                  (m/default-schemas) (mu/schemas)
                  (mr/fast-registry
                   {:seon.error/base [:map [:seon.error/type :keyword]]
                    :x/variant [:merge :seon.error/base [:map [:x/member :int]]]}))
        variant (m/schema :x/variant {:registry registry})]
    (is (internal/extends-schema? variant :seon.error/base))
    (is (= #{:seon.error/type :x/member} (set (map first (internal/entity-entries variant)))))))

(deftest a-render-input-naming-a-merge-variant-accepts-it
  (let [registry (mr/composite-registry
                  internal/default-schemas
                  (mr/fast-registry
                   {:x/base [:map [:x/type :keyword]]
                    :x/variant [:merge :x/base [:map [:x/member :int]]]
                    :x/other [:merge :x/base [:map [:x/other :int]]]}))
        compile #(m/schema % {:registry registry})]
    (is (true? (#'schema/schema-accepts-schema? (compile [:or :x/variant :x/other]) (compile :x/variant))))))
