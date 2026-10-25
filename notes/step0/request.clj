;; One real request, made again from its turn's basis (seon.context/remade) and written as the
;; provider's JSON body (seon.model/request-body), the help page's system message shortened.
(require '[datahike.api :as d] '[seon.model :as m] '[seon.root :as c] '[seon.context :as ctx] '[clojure.data.json :as json])
(defn request-json [sys run n out]
  (let [b (:seon.run/branch (d/pull @(:conn sys) [:seon.run/branch] [:seon.run/id run]))
        db (:db @(c/branch-db sys b))
        e (d/q '[:find ?e . :in $ ?r ?n :where [?ru :seon.run/id ?r] [?e :seon.turn/run ?ru] [?e :seon.turn/n ?n] [?e :seon.turn/origin :model]] db run n)
        {:keys [messages]} (ctx/remade db e)
        body (m/request-body {:kind :openai-compat :base-url "" :model "deepseek-flash" :api-key-env "" :max-tokens 8192} {:messages messages :task "x" :turn n})]
    (spit out (json/write-str (update body :messages (fn [ms] (mapv #(cond-> % (= "system" (:role %)) (assoc :content "<docs/agent.md, the help page>")) ms)))))
    (mapv (fn [msg] [(:role msg) (count (:content msg)) (count (:tool_calls msg))]) (:messages body))))
