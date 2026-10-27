;; Step 0 of notes/design-agent-view-2026-10-24.md: 3 DeepSeek turns, thinking ON, one eval tool,
;; tool_choice auto, every reasoning_content replayed, a trailing screen message replaced each turn.
(require '[seon.model :as m] '[clojure.data.json :as json] '[seon.config :as config] '[clojure.string :as str])
(def provider {:kind :openai-compat :base-url "https://api.deepseek.com" :model "deepseek-flash" :api-key-env "DEEPSEEK_API_KEY" :max-tokens 8192 :thinking :enabled})
(def system (slurp "/Users/sean/src/seon-next/.worktrees/agent-conversation/docs/agent.md"))
(def task "Task: using the eval tool, compute (reduce + (range 10)), then (* 6 7), then (str \"done\"), one eval call per turn. After the third result, reply with prose only.")
(defn screen [n] {:role "user" :content (pr-str {:by "screen" :code "(my.task/status)" :response {:handle (symbol "result" (str "e" (+ 100 n))) :value {:turn n :proofs {:red 1}}}})})
(defn ev [code] (try (pr-str (load-string code)) (catch Throwable e (str "#error " (ex-message e)))))
(def log (atom []))
(loop [conv [{:role "system" :content system} {:role "user" :content task}] n 1]
  (when (<= n 4)
    (let [req {:messages (conj conv (screen n)) :task "step0/check" :turn n}
          body (assoc (m/request-body provider req) :tool_choice "auto")
          r (#'m/http-post (str (:base-url provider) "/chat/completions")
                           [["Authorization" (str "Bearer " (config/secret (:api-key-env provider)))] ["Content-Type" "application/json"] ["Accept" "text/event-stream"]]
                           (json/write-str body))
          c (if (= 200 (:status r)) (m/parse-reply (:body r) 0 8192) r)
          calls (:tool-calls c)
          conv' (into (conj conv (cond-> {:role "assistant" :content (or (:text c) "")}
                                   (:reasoning c) (assoc :reasoning (:reasoning c))
                                   (seq calls) (assoc :tool-calls (mapv #(select-keys % [:id :code]) calls))))
                      (map (fn [k] {:role "tool" :tool-call-id (:id k) :content (pr-str {:handle (symbol "result" (str "e" n)) :value (read-string (ev (:code k)))})}) calls))]
      (swap! log conj {:turn n :status (:status r) :body-if-error (when (not= 200 (:status r)) (:body r))
                       :usage (select-keys c [:prompt-tokens :completion-tokens :reasoning-tokens :cache-hit-tokens :cache-miss-tokens :finish-reason])
                       :calls (mapv :code calls) :text (:text c) :reasoning-chars (count (:reasoning c))})
      (when (= n 3) (spit "/Users/sean/src/seon-next/.worktrees/agent-conversation/notes/step0/request-turn3.json" (json/write-str (update body :messages (fn [ms] (mapv #(cond-> % (= "system" (:role %)) (assoc :content "<docs/agent.md>")) ms))))))
      (when (and (= 200 (:status r)) (seq calls)) (recur conv' (inc n))))))
(spit "/Users/sean/src/seon-next/.worktrees/agent-conversation/notes/step0/usage.edn" (with-out-str (clojure.pprint/pprint @log)))
@log
