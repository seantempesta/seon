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
             :seon.agent/ctx
             (filter #(= :root-role (:seon.agent.ctx/name %)))
             first)]
    (is (= render-text (:seon.render/ai root-role)))
    (is (string? (:seon.render/ai root-role)))
    (is (not-any? nil? (vals root-role))
        "a resolved block carries values or omits keys; never present nil")))

(deftest clusters-resolve-independent-boot-only-writer-values
  (let [alpha-values
        {:seon.config.database.writer/transaction-queue-size 4096
         :seon.config.database.writer/commit-queue-size 2048
         :seon.config.database.writer/commit-wait-time-ms 2}
        beta-values
        {:seon.config.database.writer/transaction-queue-size 16384
         :seon.config.database.writer/commit-queue-size 8192
         :seon.config.database.writer/commit-wait-time-ms 9}
        alpha-manifest {:seon.config/database alpha-values}
        beta-manifest {:seon.config/database beta-values}
        alpha-envelope
        (resolve/resolve-envelope alpha-manifest fixed-hardware 1)
        beta-envelope
        (resolve/resolve-envelope beta-manifest fixed-hardware 2)]
    (is (= alpha-values
           (select-keys alpha-envelope (keys alpha-values))))
    (is (= beta-values
           (select-keys beta-envelope (keys beta-values))))
    (is (not= (select-keys alpha-envelope (keys alpha-values))
              (select-keys beta-envelope (keys beta-values))))
    (is (empty?
         (select-keys
          (resolve/resolve-config-singleton
           alpha-manifest {} fixed-hardware)
          (keys alpha-values)))
        "writer construction is never persisted into the database singleton")))
