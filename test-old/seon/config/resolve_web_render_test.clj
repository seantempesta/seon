(ns seon.config.resolve-web-render-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.config.resolve :as resolve]
            [seon.schema :as schema]))

(def ^:private hardware
  {:seon.hardware/cores 8
   :seon.hardware/system-memory-bytes 17179869184
   :seon.hardware/fd-soft-limit 1024})

(deftest web-render-dials-are-native-config-facts
  (let [defaults (resolve/resolve-config-singleton {} {} hardware)
        override
        {:seon.config.web-render/heartbeat-interval-ms 23000
         :seon.config.web-render/mailbox-depth 2
         :seon.config.web-render/maximum-connections 12000
         :seon.config.web-render/request-executor-size 300
         :seon.config.web-render/database-pool-size 24
         :seon.config.web-render/database-call-timeout-ms 180000
         :seon.config.web-render/interest-reconnect-backoff-ms 2000
         :seon.config.web-render/data-page-size 125
         :seon.config.web-render/maximum-request-body-bytes 8388608}
        resolved
        (resolve/resolve-config-singleton
         {:seon.config/web-render override} {} hardware)]
    (testing "defaults reproduce the ported pod policies and protective caps"
      (is (= {:seon.config.web-render/heartbeat-interval-ms 15000
              :seon.config.web-render/mailbox-depth 1
              :seon.config.web-render/maximum-connections 10000
              :seon.config.web-render/request-executor-size 256
              :seon.config.web-render/database-pool-size 16
              :seon.config.web-render/database-call-timeout-ms 120000
              :seon.config.web-render/interest-reconnect-backoff-ms 1000
              :seon.config.web-render/data-page-size 100
              :seon.config.web-render/maximum-request-body-bytes 4194304}
             (resolve/web-render-configuration defaults))))
    (testing "manifest overrides become the same flat singleton facts"
      (is (= override (resolve/web-render-configuration resolved))))
    (testing "every dial carries provenance-bearing schema documentation"
      (doseq [attribute resolve/web-render-attributes]
        (is (string?
             (-> (schema/schema-definition attribute)
                 second
                 :description)))))))
