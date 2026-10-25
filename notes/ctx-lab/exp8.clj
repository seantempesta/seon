;; Experiment 8: what holding ctx costs in memory (3,000 evals), and pull's :as (Datahike's alias) for short keys
(defn collect! [] (jolt.scheme/eval-string "(begin (heap-reserve-ratio 0.0) (collect 4 4) (heap-reserve-ratio 1.0))"))
(def E8 (let [_ (binding [*patching* true] (reset! ctx nil)) _ (collect!) without (seon.lib/real-mb)
              _ (load!) _ (collect!) with (seon.lib/real-mb)]
          {:mb-without without :mb-with with
           :alias (try (keys (d/pull (agent/db) [[:seon.agent/key :as :key] [:seon.eval/_agent :as :evals :limit 2]] (me)))
                       (catch Exception e (ex-message e)))}))
