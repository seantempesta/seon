(ns context-page-probe-2026-09-09
  (:require [clojure.string :as str]
            [seon.bootstrap :as bootstrap]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.operator :as operator]
            [seon.operator.runtime :as runtime]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval])
  (:import [java.io PushbackReader StringReader]))

(defn plan-examples
  "Read only the two forms inside the plan's generated example comments."
  []
  (let [comments ((resolve 'seon.plan/plan-write-examples) "juniper")
        source (->> (str/split-lines comments)
                    (remove #(str/starts-with? % ";; I "))
                    (map #(subs % 3))
                    (str/join "\n"))]
    (with-open [reader (PushbackReader. (StringReader. source))]
      [(read reader) (read reader)])))

(defn exercise-plan!
  "Execute the generated add/remove examples on the scratch fixture only."
  [cluster-name directory]
  (assert (not= "default" cluster-name))
  (let [connection (operator/connection cluster-name)
        handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [sources (mapv repl/source-text (plan-examples))
             results
             (mapv (fn [source]
                     (let [result (sci.eval/evaluate
                                    (assoc handle :seon.db/db @connection
                                           :seon.agent/id "juniper"
                                           :seon.sci.admit/caps
                                           (config/result-caps (config/effective @connection cluster-name))
                                           :seon.cluster.eval/source source
                                           :seon.sci.eval/time-limit-ms 10000))]
                       (assert (not (:seon.cluster.eval/error result))
                               (:seon.eval/value result))
                       {:source source :shown (:seon.eval/value result)
                        :value (:seon.sci.admit/value result)
                        :bytes (alength (.getBytes (:seon.eval/value result) "UTF-8"))})) sources)
             record {:seon.page/plan-examples results}]
         (spit (str directory "/context_page_plan_examples_2026_09_09.edn") (pr-str record))
         (mapv #(select-keys % [:bytes :shown]) results))))))

(defn capture!
  "Capture the reseeded scratch prompt; never call a provider."
  [cluster-name directory]
  (assert (not= "default" cluster-name))
  (let [database @(operator/connection cluster-name)
        handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [rows (evaluation/of-agent database "juniper")
             _ (assert (seq rows))
             _ (assert (not-any? :seon.cluster.eval/error rows))
             sources (mapv :seon.cluster.eval/source rows)
             _ (assert (= (count sources) (count (distinct sources))))
             _ (assert (= 1 (count (filter #{"(help)"} sources))))
             prompt ((resolve 'juniper-fixture-2026-09-06/prompt) cluster-name)
             instructions (bootstrap/help-value database "juniper")
             shown (:seon.eval/value (first rows))
             record {:seon.page/cluster cluster-name
                     :seon.page/basis (db/basis-t database)
                     :seon.page/prompt-bytes (alength (.getBytes prompt "UTF-8"))
                     :seon.page/help instructions
                     :seon.page/help-shown shown
                     :seon.page/help-bytes (alength (.getBytes shown "UTF-8"))
                     :seon.page/help-html (bootstrap/render-help-html instructions)
                     :seon.page/evaluations
                     (mapv #(select-keys % [:seon.cluster.eval/source
                                           :seon.cluster.eval/comment :seon.eval/value :seon.eval/renderer]) rows)
                     :seon.trial/score-status :unavailable
                     :seon.trial/score-error :seon.ai/provider-error}]
         (assert (= 'seon.bootstrap/render-help-ai (:seon.eval/renderer (first rows))))
         (assert (= shown (repl/response (repl/entity-emission (first rows)))))
         (assert (= shown (bootstrap/render-help-ai instructions)))
         (assert (not (str/includes? prompt (str (char 9650)))))
         (spit (str directory "/context_cookbook_final_prompt_2026_09_09.txt") prompt)
         (spit (str directory "/context_page_capture_2026_09_09.edn") (pr-str record))
         (select-keys record [:seon.page/prompt-bytes :seon.page/help-bytes
                              :seon.trial/score-status]))))))
