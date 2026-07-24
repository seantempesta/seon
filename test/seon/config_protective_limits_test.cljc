(ns seon.config-protective-limits-test
  "Resolved R27 configuration facts for portable and JVM claimant ceilings."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [clojure.string :as str]
   [malli.core :as m]
   [seon.config.resolve :as resolve]))

(def fixed-hardware
  {:seon.hardware/cores 8
   :seon.hardware/system-memory-bytes (* 32 1024 1024 1024)
   :seon.hardware/fd-soft-limit 2048})

(def expected-defaults
  {:seon.config.llm-retry/maximum-wait-ms 20000
   :seon.config.llm-retry/maximum-total-wait-ms 60000
   :seon.config.llm-retry/default-retries 4
   :seon.config.shell/default-timeout-ms 30000
   :seon.config.web/default-timeout-ms 30000
   :seon.config.web/maximum-response-bytes 2000000
   :seon.config.web/default-preview-tokens 2000
   :seon.config.web/maximum-redirects 5
   :seon.config.web/default-link-count 25
   :seon.config.web/default-search-results 10
   :seon.config.web/maximum-search-results 20
   :seon.config.web/maximum-html-characters 1000000
   :seon.config.web/maximum-html-nesting-depth 3000
   :seon.config.claim-driver/invocation-deadline-ms 120000
   :seon.config.claim-driver/invocation-result-maximum-bytes 1048576})

(deftest protective-defaults-resolve-as-singleton-facts
  (let [singleton (resolve/resolve-config-singleton {} {} fixed-hardware)]
    (is (= expected-defaults
           (select-keys singleton (keys expected-defaults))))
    (is (= (select-keys expected-defaults resolve/llm-retry-attributes)
           (resolve/llm-retry-configuration singleton)))
    (is (= (select-keys expected-defaults resolve/shell-attributes)
           (resolve/shell-configuration singleton)))
    (is (= (select-keys expected-defaults resolve/web-capability-attributes)
           (resolve/web-capability-configuration singleton)))))

(deftest attempt-timeout-process-fallback-is-portable
  (is (= 120000 (resolve/llm-attempt-timeout-ms {})))
  (is (= 42000
         (resolve/llm-attempt-timeout-ms
          {"SEON_LLM_ATTEMPT_TIMEOUT_MS" "42000"})))
  (is (= 120000
         (resolve/llm-attempt-timeout-ms
          {"SEON_LLM_ATTEMPT_TIMEOUT_MS" "not-positive"}))))

(deftest explicit-sections-override-without-hidden-call-site-fallbacks
  (let [manifest
        {:seon.config/llm-retry
         {:seon.config.llm-retry/maximum-wait-ms 11
          :seon.config.llm-retry/maximum-total-wait-ms 22
          :seon.config.llm-retry/default-retries 2}
         :seon.config/shell
         {:seon.config.shell/default-timeout-ms 33}
         :seon.config/web
         {:seon.config.web/default-timeout-ms 44
          :seon.config.web/maximum-response-bytes 55
          :seon.config.web/default-preview-tokens 66
          :seon.config.web/maximum-redirects 2
          :seon.config.web/default-link-count 3
          :seon.config.web/default-search-results 4
          :seon.config.web/maximum-search-results 5
          :seon.config.web/maximum-html-characters 77
          :seon.config.web/maximum-html-nesting-depth 88}
         :seon.config/claim-driver
         {:seon.config.claim-driver/invocation-deadline-ms 99
          :seon.config.claim-driver/invocation-result-maximum-bytes 111}}
        singleton
        (resolve/resolve-config-singleton manifest {} fixed-hardware)]
    (is (m/validate :seon.config/manifest manifest))
    (is (= 11 (:seon.config.llm-retry/maximum-wait-ms singleton)))
    (is (= 33 (:seon.config.shell/default-timeout-ms singleton)))
    (is (= 55 (:seon.config.web/maximum-response-bytes singleton)))
    (is (= 111
           (:seon.config.claim-driver/invocation-result-maximum-bytes
            singleton)))))

(deftest every-new-leaf-documents-its-units-and-governing-key
  (doseq [[attribute schema]
          (merge resolve/llm-retry-dial-schemas
                 resolve/shell-dial-schemas
                 resolve/web-capability-dial-schemas
                 (select-keys
                  resolve/claim-driver-dial-schemas
                  [:seon.config.claim-driver/invocation-deadline-ms
                   :seon.config.claim-driver/invocation-result-maximum-bytes]))]
    (let [description (get-in schema [1 :description])]
      (is (string? description) (str attribute))
      (is (re-find #"Default|default" description) (str attribute))
      (is (str/includes? description (str attribute))
          (str attribute)))))
