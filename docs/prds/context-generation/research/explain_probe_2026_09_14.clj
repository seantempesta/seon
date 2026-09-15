(ns explain-probe-2026-09-14
  "Ask the model, out of band, what it finds confusing in a stored context.

  One paid call per ask!: the exact provider prompt at a turn id (the same
  fold the loop used) followed by an operator question answered in prose.
  Results are saved as EDN beside this file. Owner's idea, 2026-09-14."
  (:require [clojure.java.io :as io]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.db :as db]
            [seon.operator.runtime :as runtime]
            [seon.render :as render]
            [seon.schema :as schema]))

(defn- checked [result]
  (when (:seon.error/kind result)
    (throw (ex-info (str "probe failed: " (pr-str result)) result)))
  result)

(defn ask!
  "Send the context at `turn-id` plus `question` to deepseek-flash; save to `path`."
  [cluster-name turn-id question path]
  (when (.exists (io/file path))
    (throw (ex-info "result exists; choose a new path" {:path path})))
  (let [handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [database (db/db (:seon.db/connection handle))
             ; The captured prompt is the exact bytes the provider received for
             ; this turn; it is the honest input for "what did you see". The
             ; rebuild from saved evaluations is the fallback for turns with no
             ; capture (virtual turns).
             captured (db/q '[:find ?text . :in $ ?id :where [?t :seon.turn/id ?id]
                              [?c :seon.context.capture/run ?t]
                              [?c :seon.context.capture/prompt ?text]]
                            database turn-id)
             prompt (or captured
                        (:seon.cluster.prompt/text
                         (checked (render/acquire-context!
                                   (merge handle {:seon.db/db database :seon.agent/id "juniper"
                                                  :seon.turn/id turn-id
                                                  :seon.sci.eval/time-limit-ms
                                                  (:seon.config.eval/time-limit-ms handle)})))))
             full (str prompt "\n\n;; OPERATOR QUESTION (answer in plain prose, not in forms; this is out of band):\n;; " question "\n")
             dials (checked (config/effective database cluster-name))
             target (:seon.ai/primary
                     (checked (ai/targets database
                                          (assoc dials :seon.config.ai/model "deepseek-flash"
                                                 :seon.config.ai/thinking :disabled
                                                 :seon.config.ai/max-tokens 4096
                                                 :seon.config.ai/timeout-ms 180000))))
             completion (ai/complete (assoc target :seon.ai/prompt full))]
         (spit path (pr-str {:turn turn-id :question question :source (if captured :captured :rebuilt)
                             :prompt-bytes (alength (.getBytes ^String prompt "UTF-8"))
                             :usage (:seon.ai/usage completion)
                             :text (:seon.ai/text completion)
                             :error (select-keys completion [:seon.error/kind :seon.error/message])}))
         (:seon.ai/text completion))))))
