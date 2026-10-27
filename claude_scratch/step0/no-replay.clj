;; Live check for :seon.config.ai/replay-reasoning (owner ruling 2026-10-24): DeepSeek flash,
;; thinking on, the eval tool, and turn 2 sent without turn 1's reasoning_content. Records the status.
(require '[seon.model :as m] '[clojure.data.json :as json] '[seon.config :as config])
(def provider {:kind :openai-compat :base-url "https://api.deepseek.com" :model "deepseek-flash" :api-key-env "DEEPSEEK_API_KEY" :max-tokens 2048 :thinking :enabled})
(defn post [msgs] (let [r (#'m/http-post (str (:base-url provider) "/chat/completions")
                                         [["Authorization" (str "Bearer " (config/secret (:api-key-env provider)))] ["Content-Type" "application/json"] ["Accept" "text/event-stream"]]
                                         (json/write-str (m/request-body provider {:messages msgs :task "step0/no-replay" :turn 1})))]
                    [(:status r) (if (= 200 (:status r)) (m/parse-reply (:body r) 0 2048) (:body r))]))
(def m0 [{:role "system" :content (slurp (str (System/getenv "SEON_CHECKOUT") "/docs/agent.md"))} {:role "user" :content "Task: work out which Clojure form computes the 20th Fibonacci number, then evaluate it with the eval tool; after its result, answer in prose."}])
(def r1 (post m0))
(def c1 (second r1))
(def call (first (:tool-calls c1)))
(def m1 (conj m0 {:role "assistant" :content (or (:text c1) "") :tool-calls [(select-keys call [:id :code])]} ; reasoning left out
              {:role "tool" :tool-call-id (:id call) :content "{:handle result/e1, :value 3}"}))
(def r2 (post m1))
(def out {:turn1-status (first r1) :turn1-reasoning-chars (count (:reasoning c1)) :turn1-call (:code call)
          :turn2-without-reasoning-status (first r2) :turn2 (let [x (second r2)] (if (map? x) (select-keys x [:text :finish-reason :prompt-tokens]) x))})
(spit (str (System/getenv "SEON_CHECKOUT") "/notes/step0/no-replay.edn") (pr-str out))
out
