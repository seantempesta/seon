(ns seon.schema.internal-test
  "The compiled-schema readers of entity composition."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.registry :as mr]
            [malli.util :as mu]
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
