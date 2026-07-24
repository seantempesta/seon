(ns seon.host.sample
  "Acquire guarded evaluation and result-projection policy for JVM sessions."
  (:require [seon.host.context :as context]
            [seon.render.value :as render.value]))

(def ^:private sampling-policy-query
  '[:find [?path-segments ?path-bytes ?realized ?depth ?string ?shape ?items
           ?database-edn-cap ?repair-level ?repair-delimiters?
           ?repair-def-vs-defn? ?repair-undeclared-var? ?repair-max-fixes
           ?repair-budget-ms]
    :in $ ?id
    :where
    [?config :seon.config/id ?id]
    [?config :seon.config.render/value-max-path-segments ?path-segments]
    [?config :seon.config.render/value-max-path-bytes ?path-bytes]
    [?config :seon.config.render/value-max-realized-items ?realized]
    [?config :seon.config.render/value-max-depth ?depth]
    [?config :seon.config.render/value-max-string ?string]
    [?config :seon.config.render/value-shape-sample ?shape]
    [?config :seon.config.render/value-max-items ?items]
    [(get-else $ ?config :seon.config.render/database-edn-cap 16384)
     ?database-edn-cap]
    [(get-else $ ?config :seon.config.repair/level :symbols) ?repair-level]
    [(get-else $ ?config :seon.config.repair.class/delimiters? true)
     ?repair-delimiters?]
    [(get-else $ ?config :seon.config.repair.class/def-vs-defn? true)
     ?repair-def-vs-defn?]
    [(get-else $ ?config :seon.config.repair.class/undeclared-var? true)
     ?repair-undeclared-var?]
    [(get-else $ ?config :seon.config.repair/max-fixes-per-form 1)
     ?repair-max-fixes]
    [(get-else $ ?config :seon.config.repair/budget-ms 50) ?repair-budget-ms]])

(defn acquire-sampling-policy!
  "Acquire result projection and repair policy at an invocation database value."
  {:malli/schema [:=> [:cat :any :seon.db/db] :map]}
  [writer database]
  (let [row (context/query-writer-at! writer database
                                      sampling-policy-query ["cluster"])
        policy (when (and (vector? row) (= 14 (count row)))
                 (zipmap
                  [:seon.config.render/value-max-path-segments
                   :seon.config.render/value-max-path-bytes
                   :seon.config.render/value-max-realized-items
                   :seon.config.render/value-max-depth
                   :seon.config.render/value-max-string
                   :seon.config.render/value-shape-sample
                   :seon.render.value/page-size
                   :seon.config.render/database-edn-cap
                   :seon.config.repair/level
                   :seon.config.repair.class/delimiters?
                   :seon.config.repair.class/def-vs-defn?
                   :seon.config.repair.class/undeclared-var?
                   :seon.config.repair/max-fixes-per-form
                   :seon.config.repair/budget-ms]
                  row))
        sampling-limits
        (apply dissoc policy
               [:seon.config.render/database-edn-cap
                :seon.config.repair/level
                :seon.config.repair.class/delimiters?
                :seon.config.repair.class/def-vs-defn?
                :seon.config.repair.class/undeclared-var?
                :seon.config.repair/max-fixes-per-form
                :seon.config.repair/budget-ms])]
    (if (and (pos-int? (:seon.config.render/database-edn-cap policy))
             (contains? #{:off :safe-syntax :symbols :aggressive}
                        (:seon.config.repair/level policy))
             (every? boolean?
                     ((juxt :seon.config.repair.class/delimiters?
                            :seon.config.repair.class/def-vs-defn?
                            :seon.config.repair.class/undeclared-var?)
                      policy))
             (pos-int? (:seon.config.repair/max-fixes-per-form policy))
             (pos-int? (:seon.config.repair/budget-ms policy))
             (render.value/effective-limits-within? sampling-limits
                                                    sampling-limits))
      policy
      (throw (ex-info "The invocation database lacks a complete result-projection policy."
                      {:seon.error/kind :core-bug})))))

(def ^:private guard-policy-query
  '[:find [?agent-interpreter-step-budget ?authored-interpreter-step-budget
           ?plan-interpreter-step-budget ?deadline-ms ?output-cap]
    :in $ ?id
    :where
    [?config :seon.config/id ?id]
    [?config :seon.config.guard/agent-eval-interpreter-step-budget
     ?agent-interpreter-step-budget]
    [?config :seon.config.guard/authored-render-interpreter-step-budget
     ?authored-interpreter-step-budget]
    [?config :seon.config.guard/plan-interpreter-step-budget
     ?plan-interpreter-step-budget]
    [?config :seon.config.guard/deadline-ms ?deadline-ms]
    [?config :seon.config.guard/output-cap ?output-cap]])

(defn acquire-guard-policy!
  "Acquire the SCI circuit-breaker policy at an invocation database value."
  {:malli/schema [:=> [:cat :any :seon.db/db] :map]}
  [writer database]
  (let [row (context/query-writer-at! writer database
                                      guard-policy-query ["cluster"])
        policy (when (and (vector? row) (= 5 (count row)))
                 (zipmap
                  [:seon.config.guard/agent-eval-interpreter-step-budget
                   :seon.config.guard/authored-render-interpreter-step-budget
                   :seon.config.guard/plan-interpreter-step-budget
                   :seon.config.guard/deadline-ms
                   :seon.config.guard/output-cap]
                  row))]
    (if (and (= 5 (count policy))
             (every? pos-int? (vals policy)))
      policy
      (throw
       (ex-info "The invocation database lacks a complete SCI guard policy."
                {:seon.error/kind :core-bug})))))
