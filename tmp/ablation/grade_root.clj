(ns ablation.grade-root
  "Grade one finished drive root from its ending database value.

  The in-drive row counts turns from the episode's own run ids and counts
  steering errors from episode receipts. Both undercount what the database
  records: the floor drive ended with two agent runs carrying
  `:seon.cluster.run/error` while reporting one turn and zero steering errors.
  This grader reads the facts the drive left behind, so every variant is graded
  by one derivation."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]))

(def ^:private contracted-symbol
  "my.agents.w1-history-proof-5/cluster-agent-count")

(defn- usages
  [database]
  (->> (db/q '[:find [?usage ...]
               :where [?attempt :seon.ai.attempt/usage-edn ?usage]]
             database)
       (map edn/read-string)))

(defn- agent-runs
  [database]
  (->> (db/q '[:find ?id ?error
               :where
               [?run :seon.cluster.run/id ?id]
               [(get-else $ ?run :seon.cluster.run/error "") ?error]]
             database)
       (remove (fn [[id _]] (str/starts-with? (str id) "bootstrap:")))))

(defn grade
  "One variant's measured row, derived from the ending database value."
  [variant database]
  (let [runs (agent-runs database)
        model-names (db/q '[:find [?model ...]
                            :where [?attempt :seon.ai/model ?model]]
                          database)
        usage-rows (usages database)
        contract (db/q '[:find ?spec .
                         :in $ ?sym
                         :where
                         [?function :seon.fn/sym ?sym]
                         [?function :seon.fn/spec ?spec]]
                       database contracted-symbol)]
    {:minimum-context.result/variant variant
     :minimum-context.result/models (set model-names)
     :minimum-context.result/attempts (count usage-rows)
     :minimum-context.result/provider-prompt-tokens
     (reduce + 0 (keep #(get % "prompt_tokens") usage-rows))
     :minimum-context.result/provider-cache-hit-tokens
     (reduce + 0 (keep #(get % "prompt_cache_hit_tokens") usage-rows))
     :minimum-context.result/provider-completion-tokens
     (reduce + 0 (keep #(get % "completion_tokens") usage-rows))
     :minimum-context.result/provider-reasoning-tokens
     (reduce + 0 (keep #(get-in % ["completion_tokens_details" "reasoning_tokens"])
                       usage-rows))
     :minimum-context.result/agent-runs (count runs)
     :minimum-context.result/runs-with-error
     (count (remove (comp str/blank? second) runs))
     :minimum-context.result/run-errors
     (vec (remove str/blank? (map second runs)))
     :minimum-context.grade/contract-fact contract}))

(defn -main
  "Print the derived grade row for one drive root."
  [& [root cluster-name variant]]
  (let [opened (store/open-store! {:seon.store/dir (str root "/store")})
        branch (registry/cluster-branch cluster-name)
        connection (store/open-branch! opened branch)]
    (try
      (println (pr-str (grade (keyword variant) (deref connection))))
      (finally
        (store/release-branch! connection)
        (store/release-store! opened))))
  (shutdown-agents))
