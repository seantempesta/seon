(ns debug-prompt-proof-2026-09-14
  "Read-only reconstruction proof against every stored provider capture."
  (:require [clojure.edn :as edn]
            [seon.ai.tokens :as tokens]
            [seon.cluster.prompt :as prompt]
            [seon.db :as db]
            [seon.render :as render]))

(defn prove!
  "Write exact byte comparisons and independent estimator residuals to path."
  [request path]
  (let [database (:seon.db/db request)
        rows (sort-by first
                      (db/q '[:find ?opened (pull ?t [:seon.turn/id
                                                       {:seon.turn/attempts [:seon.ai/model :seon.ai.attempt/ordinal :seon.ai.attempt/usage-edn]}])
                              :in $ ?agent-id
                              :where [?a :seon.agent/id ?agent-id] [?a :seon.agent/runtime ?r]
                                     [?r :seon.runtime/turns ?t] [?t :seon.turn/id _ ?opened]]
                            database (:seon.agent/id request)))
        observations
        (vec
         (mapcat
          (fn [ordinal [_ row]]
            (when (seq (:seon.turn/attempts row))
              (let [turn-id (:seon.turn/id row)
                    acquired (render/acquire-context! (assoc request :seon.turn/id turn-id))
                    text (:seon.cluster.prompt/text acquired)
                    capture (db/q '[:find (pull ?c [:seon.context.capture/prompt :seon.context.capture/basis-t]) .
                                    :in $ ?turn-id :where [?t :seon.turn/id ?turn-id]
                                    [?c :seon.context.capture/run ?t]] database turn-id)]
                (assert (string? text) (pr-str acquired))
                (for [attempt (sort-by :seon.ai.attempt/ordinal (:seon.turn/attempts row))]
                  (let [usage (edn/read-string (:seon.ai.attempt/usage-edn attempt))
                        estimated (tokens/estimate text (prompt/model-calibration database (:seon.ai/model attempt)))
                        billed (get usage "prompt_tokens")
                        completion (get usage "completion_tokens")
                        residual (abs (- estimated billed))]
                    {:seon.turn/id turn-id
                     ::ordinal ordinal ::attempt (:seon.ai.attempt/ordinal attempt)
                     ::bytes (alength (.getBytes ^String text "UTF-8"))
                     ::captured-bytes (some-> (:seon.context.capture/prompt capture) (.getBytes "UTF-8") alength)
                     ::exact? (= text (:seon.context.capture/prompt capture))
                     ::basis-equal? (= (db/q '[:find ?tx . :in $ ?id :where [_ :seon.turn/id ?id ?tx]] database turn-id)
                                       (:seon.context.capture/basis-t capture))
                     ::prior-estimate (tokens/estimate text) ::rebuilt-estimate estimated
                     ::billed billed ::completion completion ::residual residual
                     ::within-requested-tolerance? (<= residual (+ 64 completion))})))))
          (range) rows))
        report {::observations observations
                ::attempts (count observations)
                ::exact (count (filter ::exact? observations))
                ::within-requested-tolerance (count (filter ::within-requested-tolerance? observations))
                ::largest-estimator-residual (apply max (map ::residual observations))}]
    (spit path (pr-str report))
    (assert (seq observations) "No provider observations: proof unavailable.")
    (assert (every? #(and (::exact? %) (::basis-equal? %)) observations) "Reconstruction differs from a captured provider prompt.")
    (dissoc report ::observations)))
