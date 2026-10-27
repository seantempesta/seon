;; Each run's latest model request, made again from its turn's basis and written as the provider's JSON.
(require '[datahike.api :as d] '[seon.model :as m] '[seon.root :as c] '[seon.context :as ctx] '[clojure.data.json :as json] '[clojure.string :as str])
(defn dump-all [sys dir tag]
  (vec (for [[rid b] (d/q '[:find ?r ?b :where [?e :seon.run/id ?r] [?e :seon.run/branch ?b]] @(:conn sys))
             :let [db (:db @(c/branch-db sys b))
                   [n e] (last (sort (d/q '[:find ?n ?e :in $ ?r :where [?ru :seon.run/id ?r] [?e :seon.turn/run ?ru] [?e :seon.turn/n ?n] [?e :seon.turn/origin :model]] db rid)))]
             :when e
             :let [{:keys [messages]} (ctx/remade db e)
                   body (m/request-body {:kind :openai-compat :base-url "" :model "deepseek-flash" :api-key-env "" :max-tokens 8192} {:messages messages :task "x" :turn n})
                   f (str dir "/" tag "-" (str/replace (str/replace rid "/" "__") "#" "-") "-turn" n ".json")]]
         (do (spit f (json/write-str body)) f))))
