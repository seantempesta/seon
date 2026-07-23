(ns seon.host.guard-config-test
  (:require [cljs.test :refer-macros [deftest is]]
            [malli.core :as m]
            [seon.config :as config]
            [seon.config.resolve :as resolve]))

(deftest guard-budgets-resolve-as-config-singleton-facts
  (let [manifest
        {:seon.config/guard
         {:seon.config.guard/agent-eval-fuel 101
          :seon.config.guard/authored-render-fuel 102
          :seon.config.guard/plan-fuel 103
          :seon.config.guard/deadline-ms 104
          :seon.config.guard/output-cap 105}}
        singleton
        (resolve/resolve-config-singleton
         manifest
         {}
         {:seon.hardware/cores 8
          :seon.hardware/system-memory-bytes 17179869184
          :seon.hardware/fd-soft-limit 1024})]
    (is (m/validate :seon.config/manifest manifest))
    (is (= 101 (config/guard-agent-eval-fuel singleton)))
    (is (= 102 (config/guard-authored-render-fuel singleton)))
    (is (= 103 (config/guard-plan-fuel singleton)))
    (is (= 104 (config/guard-deadline-ms singleton)))
    (is (= 105 (config/guard-output-cap singleton)))))

(deftest shipped-guard-defaults-are-resolved-facts
  (let [singleton
        (resolve/resolve-config-singleton
         {}
         {}
         {:seon.hardware/cores 8
          :seon.hardware/system-memory-bytes 17179869184
          :seon.hardware/fd-soft-limit 1024})]
    (is (= 100000000 (config/guard-agent-eval-fuel singleton)))
    (is (= 100000000 (config/guard-authored-render-fuel singleton)))
    (is (= 100000000 (config/guard-plan-fuel singleton)))
    (is (= 600000 (config/guard-deadline-ms singleton)))
    (is (= 1638400 (config/guard-output-cap singleton)))))
