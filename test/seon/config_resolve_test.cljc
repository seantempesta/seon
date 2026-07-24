(ns seon.config-resolve-test
  "Portable contracts for pure manifest-to-singleton resolution."
  (:require
   #?(:clj [clojure.test :refer [deftest is]]
      :cljs [cljs.test :refer [deftest is]])
   [seon.config.resolve :as resolve]))

(def ^:private fixed-hardware
  {:seon.hardware/cores 8
   :seon.hardware/system-memory-bytes (* 32 1024 1024 1024)
   :seon.hardware/fd-soft-limit 2048})

(deftest root-context-preserves-literal-render-text
  (let [render-text "; root role\n; Preserve this exact literal."
        singleton
        (resolve/resolve-config-singleton
         {:seon.config/agent-context
          {:seon.agent/ctx
           [{:seon.agent.ctx/name :canvas
             :seon.agent.ctx/priority 35}]}
          :seon.config/root-context
          {:seon.agent/ctx
           [{:seon.agent.ctx/name :root-role
             :seon.agent.ctx/priority 15
             :seon.render/ai render-text}]}}
         {}
         fixed-hardware)
        root-role
        (->> singleton
             :seon.config/root-context
             first
             :seon.agent/ctx
             (filter #(= :root-role (:seon.agent.ctx/name %)))
             first)]
    (is (= render-text (:seon.render/ai root-role)))
    (is (string? (:seon.render/ai root-role)))
    (is (not-any? nil? (vals root-role))
        "a resolved block carries values or omits keys; never present nil")))
