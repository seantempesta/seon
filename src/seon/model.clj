(ns seon.model
  "The `:model` Flow proc (`:io` workload):
   - Single boundary owning external LLM API calls (`jolt.http.core` OpenAI-compatible `/chat/completions`,
     plus `:scripted` / built-in deterministic synthesis for offline autonomous runs & tests).
   - API keys stay exclusively in this proc; forked agents never hold credentials.
   - Enforces concurrency & rate backpressure purely via `::flow/input-filter` (zero timers/polls)."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [jolt.http.core :as http]))

(defn- sync-model-filter [st]
  (if (>= (count (:in-flight st)) (:max-slots st))
    (assoc st ::flow/input-filter #{:done :ctrl})
    (dissoc st ::flow/input-filter)))

(defn- extract-code-block [^String content]
  (if-let [[_ inner] (re-find #"(?s)```(?:clojure|clj)?\s*\n(.*?)```" content)]
    (str/trim inner)
    (str/trim content)))

(defn http-post!
  "Pure fiber-parking HTTP POST via `jolt.http.core` (`headers` is `[[k v] ...]`)."
  [url headers ^String body]
  (let [u (http/parse-url url)
        s (http/connect-stream (http/tget u :host) (http/effective-port u) (= "https" (http/tget u :protocol)) false nil nil)]
    (try
      (http/s-write s (http/build-request "POST" u headers body))
      (let [r (http/read-response s nil "POST" nil true false)
            bs (if-let [pull (:body-pull r)]
                 (http/concat-bas (loop [acc []] (if-let [b (pull)] (recur (conj acc b)) acc)))
                 (or (:body r) (byte-array 0)))]
        {:status (:status r) :body (String. ^bytes bs "UTF-8")})
      (finally
        (http/s-close s)))))

(defn- openai-chat! [{:keys [base-url model api-key-env]} system-prompt user-prompt]
  (let [api-key (System/getenv (or api-key-env "OPENAI_API_KEY"))]
    (when (str/blank? api-key)
      (throw (ex-info (str "Missing model API key in env var " api-key-env) {:env api-key-env})))
    (let [url (str (str/replace base-url #"/$" "") "/chat/completions")
          body (json/write-str
                {"model" model
                 "temperature" 0.1
                 "messages" [{"role" "system" "content" system-prompt}
                             {"role" "user" "content" user-prompt}]})
          resp (http-post! url
                           [["Authorization" (str "Bearer " api-key)]
                            ["Content-Type" "application/json"]]
                           body)]
      (if (= 200 (:status resp))
        (let [parsed (json/read-str (:body resp) :key-fn keyword)
              txt (get-in parsed [:choices 0 :message :content] "")]
          {:thought "OpenAI-compat model completion"
           :code (extract-code-block txt)})
        (throw (ex-info (str "Model HTTP " (:status resp))
                        {:status (:status resp) :body (:body resp)}))))))

(defn- built-in-agent-synthesis
  [{:keys [agent-id scope role goal parent unread turn]}]
  (let [explicit-code (some (fn [m]
                              (let [b (:seon.agent.message/body m "")]
                                (when (str/starts-with? (str/triml b) "(") b)))
                            unread)
        ns-part (or (namespace agent-id) (name agent-id))]
    (cond
      explicit-code
      {:thought (str "Evaluating supplied Clojure form on turn #" turn)
       :code (str explicit-code "\n(user/ready!)")}

      (some #(#{:test-failure :merge-conflict} (:seon.agent.message/type %)) unread)
      {:thought "Recorded test/merge failure notice; awaiting next directive."
       :code "(user/wait-for-mail!)"}

      (and (= role :coordinator) (= turn 1))
      (let [wa (keyword ns-part "worker-a")
            wb (keyword ns-part "worker-b")
            base-sc (or scope 'seon.generated)]
        {:thought (str "Decomposing coordinator goal '" goal "' into scoped child agents " wa " and " wb)
         :code (pr-str
                `(do
                   (user/spawn-agent! {:id ~wa :scope (quote ~(symbol (str base-sc ".a"))) :role :worker :goal ~(str "Implement helper for " goal)})
                   (user/spawn-agent! {:id ~wb :scope (quote ~(symbol (str base-sc ".b"))) :role :worker :goal ~(str "Verify & message peer for " goal)})
                   (user/wait-for-mail!)))})

      (= role :coordinator)
      (let [reports (filter #(= :report (:seon.agent.message/type %)) unread)]
        (if (seq reports)
          {:thought "Synthesizing worker reports and marking coordinator branch ready for test + merge."
           :code "(do (user/ready!) {:synthesized true})"}
          {:thought "Waiting on remaining child reports."
           :code "(user/wait-for-mail!)"}))

      :else
      (let [fn-ns (or scope (symbol (str "seon.generated." (str/replace ns-part #"[^a-zA-Z0-9]" ""))))
            test-ns (symbol (str fn-ns "-test"))
            fn-sym (symbol (str "solve-" (name agent-id)))
            test-sym (symbol (str "solve-" (name agent-id) "-test"))]
        {:thought (str "Defining real Clojure fn " fn-ns "/" fn-sym " + clojure.test in scope " fn-ns)
         :code (str "(ns " fn-ns ")\n"
                    "(defn " fn-sym " [x] (+ x " (* turn 10) "))\n"
                    "(ns " test-ns " (:require [clojure.test :refer [deftest is]] [" fn-ns " :as sut]))\n"
                    "(deftest " test-sym " (is (= " (+ 5 (* turn 10)) " (sut/" fn-sym " 5))))\n"
                    "(in-ns 'user)\n"
                    (when parent
                      (str "(user/send-message! " parent " :report \"Defined " fn-ns "/" fn-sym " with passing deftest\")\n"))
                    "(user/ready!)")}))))

(defn model-proc
  ([] {:params {:provider "Provider map {:type :scripted|:openai-compat ...}"
                :ctrl-ch "Optional throttle/slot control channel"
                :max-slots "Max concurrent in-flight model requests"}
       :ins {:request "Model completion requests from :agents"}
       :outs {:response "Model completions back to :agents"
              :stream "Streaming status notices for :web"}
       :workload :io
       :doc "External LLM boundary with ::flow/input-filter backpressure."
       :ping-map-fn (fn [st]
                      {:provider-type (get-in st [:provider :type] :built-in)
                       :max-slots (:max-slots st)
                       :latency-ms (:latency-ms st)
                       :in-flight (count (:in-flight st))
                       :backpressuring? (contains? st ::flow/input-filter)
                       :completed (:completed st)})})
  ([{:keys [provider ctrl-ch max-slots latency-ms]}]
   (let [done-ch (a/chan 32)]
     {:provider (or provider {:type :built-in})
      :done-ch done-ch
      :max-slots (or max-slots 4)
      :latency-ms (or latency-ms 120)
      :in-flight #{}
      :completed 0
      ::flow/in-ports (cond-> {:done done-ch} ctrl-ch (assoc :ctrl ctrl-ch))}))
  ([st _] st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :ctrl
       (let [st' (-> st
                     (merge (select-keys msg [:max-slots :latency-ms :provider]))
                     sync-model-filter)]
         [st' {::flow/report [(str "[:model] updated config slots=" (:max-slots st')
                                   " latency=" (:latency-ms st') "ms")]}])

       :request
       (let [{:keys [req-id agent-id]} msg
             done-ch (:done-ch st)
             prov (:provider st)
             delay-ms (:latency-ms st)]
         (a/go
           (let [ans (try
                       (if (= :openai-compat (:type prov))
                         (openai-chat! prov
                                       "Return valid Clojure code fenced in ```clojure ... ``` ending with (user/ready!) or (user/wait-for-mail!)."
                                       (pr-str (select-keys msg [:agent-id :scope :role :goal :unread :turn])))
                         (do (when (pos? delay-ms) (a/<! (a/timeout delay-ms)))
                             (built-in-agent-synthesis msg)))
                       (catch Throwable ex
                         {:thought (str "Model error: " (.getMessage ex))
                          :code "(user/wait-for-mail!)"}))]
             (a/>! done-ch {:req-id req-id :agent-id agent-id :response ans})))
         [(sync-model-filter (update st :in-flight conj req-id))
          {:stream [{:agent-id agent-id :status :thinking :req-id req-id}]}])

       :done
       (let [st' (-> st
                     (update :in-flight disj (:req-id msg))
                     (update :completed inc)
                     sync-model-filter)]
         [st' {:response [msg]
               :stream [{:agent-id (:agent-id msg) :status :done :req-id (:req-id msg)}]}])))))
