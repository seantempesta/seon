(ns my.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [my.web :as web]
            [seon.schema :as schema]))

(deftest requests-remain-open-and-rigorous
  (testing "fetch accepts one URL and optional GET/HEAD method"
    (is (true? (schema/valid-candidate-value?
                :my.web/fetch-request
                {:my.web/url "https://example.com"
                 :example/extra :ignored})))
    (is (true? (schema/valid-candidate-value?
                :my.web/fetch-request
                {:my.web/url "https://example.com"
                 :my.web/method :head})))
    (is (false? (schema/valid-candidate-value?
                 :my.web/fetch-request
                 {:my.web/url "https://example.com"
                  :my.web/method :post}))))
  (testing "search requires a nonblank query and accepts a lower row limit"
    (is (true? (schema/valid-candidate-value?
                :my.web/search-request
                {:my.web/query "current Clojure release"
                 :my.web/max-results 3
                 :example/extra :ignored})))
    (is (false? (schema/valid-candidate-value?
                 :my.web/search-request
                 {:my.web/query ""})))))

(deftest public-entries-declare-one-io-capability
  (doseq [[entry handler]
          [[#'web/fetch 'seon.web.jvm/fetch]
           [#'web/search 'seon.web.jvm/search]]]
    (is (= :io (:seon.workload (meta entry))))
    (is (= handler (:seon.effect/capability (meta entry))))))
