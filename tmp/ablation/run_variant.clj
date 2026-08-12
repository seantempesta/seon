(ns ablation.run-variant
  "Run one paid DeepSeek Flash drive with an intercepted ablation prompt."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.ai.tokens :as tokens]
            [seon.cluster :as cluster]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.registry :as registry]
            [seon.context :as context]
            [seon.db :as db]
            [seon.eval.drive :as drive])
  (:import [java.util UUID]))

(def ^:private variants-file "tmp/ablation/generated/variants.edn")
(def ^:private agent-id "w1-history-proof-5")
(def ^:private contracted-symbol
  "my.agents.w1-history-proof-5/cluster-agent-count")

(defn- variant
  [variant-name]
  (let [variants (edn/read-string (slurp variants-file))]
    (or (some #(when (= variant-name (:minimum-context.variant/name %)) %)
              variants)
        (throw (ex-info "Unknown ablation variant."
                        {:minimum-context.variant/name variant-name})))))

(defn- intercepted-prompt
  [prompt-text]
  (fn [database _request]
    {:seon.cluster.prompt/text prompt-text
     :seon.context/contributions
     [{:seon.render.block/name :walk
       :seon.context.contribution/position 0
       :seon.context.contribution/text prompt-text
       :seon.context.contribution/hash (context/contribution-hash prompt-text)
       :seon.context.contribution/tokens (tokens/estimate prompt-text)}]
     :seon.db/db database}))

(defn- contract-fact
  [database]
  (db/q '[:find ?spec .
          :in $ ?sym
          :where
          [?function :seon.fn/sym ?sym]
          [?function :seon.fn/spec ?spec]]
        database contracted-symbol))

(defn- clean-call-receipt
  [database]
  (db/q '[:find ?receipt .
          :in $ ?sym
          :where
          [?function :seon.fn/sym ?sym]
          [?form :seon.fn/calls ?function]
          [?form :seon.cluster.run.form/run ?run]
          [?form :seon.cluster.run.form/ordinal ?ordinal]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/ordinal ?ordinal]
          [?receipt :seon.cluster.eval/result-edn _]
          (not [?receipt :seon.cluster.eval/error _])]
        database contracted-symbol))

(defn- contract-query-receipt
  [database]
  (db/q '[:find ?receipt .
          :where
          [?query :seon.fn/sym "seon.db/q"]
          [?form :seon.fn/calls ?query]
          [?form :seon.fn/keywords :seon.fn/spec]
          [?form :seon.cluster.run.form/run ?run]
          [?form :seon.cluster.run.form/ordinal ?ordinal]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/ordinal ?ordinal]
          [?receipt :seon.cluster.eval/result-edn _]
          (not [?receipt :seon.cluster.eval/error _])]
        database))

(defn- usage
  [attempt]
  (some-> (:seon.ai.attempt/usage-edn attempt) edn/read-string))

(defn- result
  [variant-name variant-value episode database]
  (let [attempts (:seon.eval.drive/model-attempts episode)
        usages (keep usage attempts)
        models (into #{} (map :seon.ai/model) attempts)
        ;; A drive with no usage document made no usable provider call. The
        ;; 2026-08-12 HALF re-drive booted a cluster, ran three turns, and
        ;; wrote a graded row of zeroes because DEEPSEEK_API_KEY was absent
        ;; from the drive shell. A row of zeroes must never be mistaken for a
        ;; measurement.
        _ (assert (seq usages)
                  (str "No attempt recorded a usage document; the drive made "
                       "no usable provider call."))
        steering-errors
        (count (filter #(or (seq (:seon.cluster.eval/error %))
                            (not= :seon.eval.drive/absent
                                  (:seon.error/kind %)))
                       (:seon.eval.drive/receipts episode)))
        contract (contract-fact database)
        call-receipt (clean-call-receipt database)
        query-receipt (contract-query-receipt database)
        success? (and (some? contract)
                      (some? call-receipt)
                      (some? query-receipt)
                      (zero? steering-errors)
                      (= :completed
                         (get-in episode [:seon.eval.drive/terminal
                                          :seon.eval.drive/outcome])))]
    (assert (= #{"deepseek-v4-flash"} models)
            (str "Model policy violated: " (pr-str models)))
    {:minimum-context.result/variant variant-name
     :minimum-context.result/model "deepseek-v4-flash"
     :minimum-context.result/prompt-tokens-estimated
     (:minimum-context.variant/prompt-tokens variant-value)
     :minimum-context.result/provider-prompt-tokens
     (reduce + 0 (keep #(get % "prompt_tokens") usages))
     :minimum-context.result/provider-cache-hit-tokens
     (reduce + 0 (keep #(get % "prompt_cache_hit_tokens") usages))
     :minimum-context.result/turns-to-completion
     (count (:seon.eval.drive/run-ids episode))
     :minimum-context.result/steering-errors steering-errors
     :minimum-context.grade/contract-fact contract
     :minimum-context.grade/call-receipt call-receipt
     :minimum-context.grade/contract-query-receipt query-receipt
     :minimum-context.grade/success? success?
     :minimum-context.result/episode-id (:seon.eval.drive/id episode)
     :minimum-context.result/message-id (:seon.eval.drive/message-id episode)
     :minimum-context.result/ending-commit (:seon.eval.drive/ending-commit episode)}))

(defn -main
  "Run one isolated paid Flash drive and write its fact-space grades."
  [& [variant-argument root-argument]]
  (let [variant-name (keyword (or variant-argument ""))
        _ (when (empty? (System/getenv "DEEPSEEK_API_KEY"))
            (throw (ex-info
                    "DEEPSEEK_API_KEY is absent; the drive would spend a
                     cluster boot and record nothing."
                    {:seon.config.ai/credential "DEEPSEEK_API_KEY"})))
        selected (variant variant-name)
        root (or root-argument
                 (str "tmp/ablation/drive-roots/" (name variant-name) "-"
                      (subs (str (UUID/randomUUID)) 0 8) "/clusters"))
        root-file (io/file root)
        _ (when (.exists root-file)
            (throw (ex-info "Drive root must be fresh."
                            {:seon.boot/root root})))
        _ (cluster/refresh-source! root)
        cluster-name (str "minimum-context-" (name variant-name))
        instance (cluster/start!
                  {:seon.boot/cluster-name cluster-name
                   :seon.boot/root root
                   :seon.config/manifest
                   {:seon.config.ai/model "deepseek-v4-flash"
                    :seon.config.ai/thinking :disabled
                    :seon.config.run/max-episode-runs 3}})
        grading-branch (volatile! nil)]
    (try
      (let [prompt-text (slurp (:minimum-context.variant/prompt-file selected))
            episode-id (str (name variant-name) "-"
                            (subs (str (UUID/randomUUID)) 0 8))
            episode
            (with-redefs [prompt/prompt (intercepted-prompt prompt-text)]
              (drive/run-episode!
               instance
               {:seon.eval.drive/id episode-id
                :seon.eval.drive/objective
                (:minimum-context.variant/task selected)
                :seon.eval.drive/agent-ids [agent-id]
                :seon.eval.drive/run-cap 3
                :seon.eval.drive/remote-timeout-ms 720000}))
            _ (vreset! grading-branch
                       (:seon.eval.drive/grading-branch episode))
            report (result variant-name selected episode
                           @(:seon.boot/cluster-connection instance))
            result-directory (io/file "tmp/ablation/results")
            result-file (io/file result-directory
                                 (str (name variant-name) ".edn"))]
        (.mkdirs result-directory)
        (spit result-file (str (pr-str report) "\n"))
        (println (.getPath result-file))
        (println (pr-str report)))
      (finally
        (when-let [branch @grading-branch]
          (registry/retire-branch!
           {:seon.store/store (:seon.store/store instance)
            :seon.store/branch branch}))
        (cluster/stop! instance)))))
