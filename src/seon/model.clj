(ns seon.model
  "The `:model` Flow proc (`:io` workload) — Real OpenAI-Compat / DeepSeek Streaming Wire Protocol:
   Combines `main`'s Jolt-native `jolt.http.core` fiber transport & single `repl` tool design with
   `reference-code/clj-agent`'s cleanest provider lessons:
   - `StringBuilder` SSE accumulation (`O(n)`, zero `O(n²)` concat on long reasoning/tool streams).
   - `not-empty` check on `:reasoning_content` vs `:content` (prevents empty `\"\"` deltas swallowing text).
   - Fallback tool-call delta indexing `(or (:index tc) (:id tc) (count acc))`.
   - DeepSeek `reasoning_content` replay on prior assistant messages + `prompt_cache_hit_tokens` usage.
   - Present-only optional params (`:temperature` omitted by default -> provider default avoids loops).
   - Live `:reasoning-token` / `:token` streaming straight into `:web`'s `:stream` channel (60+ fps!).
   - API keys stay exclusively in this proc; forked agents never hold credentials."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [jolt.http-client :as http]
            [jolt.http.sse :as sse]
            [seon.model.wire :as wire]
            [seon.error :as error]
            [seon.model.messages :as messages]))

(defn- sync-model-filter [st]
  (if (>= (count (:in-flight st)) (:max-slots st))
    (assoc st ::flow/input-filter #{:done :ctrl})
    (dissoc st ::flow/input-filter)))

(def tool-name messages/tool-name)

(def eval-tool messages/eval-tool)

(def default-system-prompt messages/default-system-prompt)

(defn build-wire-messages [& args] (apply messages/build-wire-messages args))

(defn estimate-message-tokens [& args] (apply messages/estimate-message-tokens args))

(defn vision-capable-model? [& args] (apply messages/vision-capable-model? args))

(defn build-agent-wire-messages [& args] (apply messages/build-agent-wire-messages args))

(defn build-request-body [& args] (apply messages/build-request-body args))

(defn make-stream-state "Stable `seon.model` facade over `seon.model.wire/make-stream-state`." [& args] (apply wire/make-stream-state args))

(defn parse-sse-line "Stable `seon.model` facade over `seon.model.wire/parse-sse-line`." [& args] (apply wire/parse-sse-line args))

(defn process-sse-chunk "Stable `seon.model` facade over `seon.model.wire/process-sse-chunk`."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:tuple :map [:maybe :map]]], :cost/ticks 500000}
  [& args] (apply wire/process-sse-chunk args))

(defn finalize-stream-state "Stable `seon.model` facade over `seon.model.wire/finalize-stream-state`." [& args] (apply wire/finalize-stream-state args))

(defn http-post! "Stable `seon.model` facade over `seon.model.wire/http-post!`."
  {:seon.flow/edge true, :malli/schema [:=> [:cat [:* :any]] :any]}
  [& args] (apply wire/http-post! args))

(defn- built-in-agent-synthesis
  [{:keys [agent-id scope router? goal parent unread turn]}]
  (let [explicit-code (some (fn [m]
                              (let [b (:seon.agent.message/body m "")]
                                (when (str/starts-with? (str/triml b) "(") b)))
                            unread)
        ns-part (or (namespace agent-id) (name agent-id))]
    (cond
      explicit-code
      {:thought (str "Evaluating supplied Clojure form on turn #" turn)
       :tool-calls [{:id (str "tc-" turn "-0")
                     :intent "Evaluate directive form and mark branch ready"
                     :code (str explicit-code "\n(user/ready!)")}]}

      (some #(#{:test-failure :merge-conflict} (:seon.agent.message/type %)) unread)
      {:thought "Recorded test/merge failure notice; awaiting next directive."
       :tool-calls [{:id (str "tc-" turn "-0") :intent "Wait for updated directive" :code "(user/wait-for-mail!)"}]}

      (and router? (= turn 1))
      (let [wa (keyword ns-part "worker-a")
            wb (keyword ns-part "worker-b")
            base-sc (or scope 'seon.generated)]
        {:thought (str "Decomposing coordinator goal '" goal "' into child agents " wa " and " wb)
         :tool-calls [{:id (str "tc-" turn "-0")
                       :intent "Spawn scoped workers and await reports"
                       :code (pr-str
                              `(do
                                 (user/spawn-agent! {:id ~wa :scope (quote ~(symbol (str base-sc ".a"))) :goal ~(str "Implement helper for " goal)})
                                 (user/spawn-agent! {:id ~wb :scope (quote ~(symbol (str base-sc ".b"))) :goal ~(str "Verify & message peer for " goal)})
                                 (user/wait-for-mail!)))}]})

      router?
      (let [reports (filter #(= :report (:seon.agent.message/type %)) unread)]
        (if (seq reports)
          {:thought "Synthesizing worker reports and marking coordinator branch ready for test + merge."
           :tool-calls [{:id (str "tc-" turn "-0") :intent "Mark coordinator ready" :code "(do (user/ready!) {:synthesized true})"}]}
          {:thought "Waiting on remaining child reports."
           :tool-calls [{:id (str "tc-" turn "-0") :intent "Wait for worker reports" :code "(user/wait-for-mail!)"}]}))

      :else
      (let [fn-ns (or scope (symbol (str "seon.generated." (str/replace ns-part #"[^a-zA-Z0-9]" ""))))
            test-ns (symbol (str fn-ns "-test"))
            fn-sym (symbol (str "solve-" (name agent-id)))
            test-sym (symbol (str "solve-" (name agent-id) "-test"))]
        {:thought (str "Defining Clojure fn " fn-ns "/" fn-sym " + clojure.test in " fn-ns)
         :tool-calls [{:id (str "tc-" turn "-0")
                       :intent (str "Define " fn-ns "/" fn-sym " with passing deftest")
                       :code (str "(ns " fn-ns ")\n"
                                  "(defn " fn-sym " {:malli/schema [:=> [:cat :int] :int]} [x] (+ x " (* turn 10) "))\n"
                                  "(ns " test-ns " (:require [clojure.test :refer [deftest is]] [" fn-ns " :as sut]))\n"
                                  "(deftest " test-sym " (is (= " (+ 5 (* turn 10)) " (sut/" fn-sym " 5))))\n"
                                  "(in-ns 'user)\n"
                                  (when parent
                                    (str "(user/send-message! " parent " :report \"Defined " fn-ns "/" fn-sym " with passing deftest\")\n"))
                                  "(user/ready!)")}]}))))

(defonce ^:private ^:seon.flow/edge multimodal-fallback-logged (atom #{}))

(defn- messages-have-image-parts? [messages]
  (boolean
   (some (fn [m]
           (let [c (:content m)]
             (and (sequential? c)
                  (some (fn [p]
                          (and (map? p)
                               (or (= "image_url" (:type p))
                                   (= :image_url (:type p))
                                   (contains? p :image_url))))
                        c))))
         messages)))

(defn strip-image-parts
  "Converts multimodal `{:role ... :content [...]}` messages into text-only messages by removing
   `image_url` parts and joining `:text` parts cleanly into a string."
  {:malli/schema [:=> {:examples [[[[{:role "user"
                                      :content [{:type "text" :text "Check card"}
                                                {:type "image_url" :image_url {:url "data:image/jpeg;base64,AA=="}}
                                                {:type "text" :text "DOM ok"}]}]]
                                   [{:role "user" :content "Check card\nDOM ok"}]]
                                  [[[{:role "user" :content "plain text"}]]
                                   [{:role "user" :content "plain text"}]]]}
                  [:cat [:sequential :map]] [:vector :map]]
   :cost/ticks 2000000 :cost/bytes 1048576}
  [messages]
  (mapv (fn [m]
          (let [c (:content m)]
            (if-not (sequential? c)
              m
              (let [texts (keep (fn [p]
                                  (cond
                                    (and (map? p)
                                         (or (= "image_url" (:type p))
                                             (= :image_url (:type p))
                                             (contains? p :image_url)))
                                    nil

                                    (and (map? p) (some? (:text p)))
                                    (let [s (str (:text p))]
                                      (when-not (str/blank? s) s))

                                    (string? p)
                                    (when-not (str/blank? p) p)

                                    :else nil))
                                c)]
                (assoc m :content (str/join "\n" texts))))))
        messages))

(defn multimodal-unsupported-error?
  "Returns true when HTTP `status` (400/422) and response `body-str` indicate the model endpoint
   does not accept multimodal `image_url` content parts."
  {:malli/schema [:=> {:examples [[[400 "{\"error\":\"unknown variant `image_url`, expected `text`\"}"] true]
                                  [[422 "This model does not support image inputs"] true]
                                  [[400 "context_length_exceeded"] false]
                                  [[500 "image_url unsupported"] false]]}
                  [:cat [:maybe :int] [:maybe :string]] :boolean]
   :cost/ticks 500000 :cost/bytes 131072}
  [status body-str]
  (boolean
   (and (integer? status)
        (contains? #{400 422} (long status))
        (string? body-str)
        (let [lo (str/lower-case body-str)]
          (some #(str/includes? lo %)
                ["image_url"
                 "multimodal"
                 "vision"
                 "unknown variant"
                 "invalid content"
                 "does not support image"])))))

(defonce ^:private ^:seon.flow/edge multimodal-unsupported-endpoints (atom #{}))

(defn ^:seon.flow/edge openai-stream-chat!
"Streams an OpenAI-compat / DeepSeek `/chat/completions` call over `jolt.http.core`, invoking
 `(on-delta {:kind :token|:reasoning-token :text s})` per chunk and returning `finalize-stream-state`.
 Automatically strips `image_url` parts and retries once on HTTP 400/422 multimodal-unsupported
 errors or transport drops on multimodal requests, caching proven text-only endpoints in memory."
{:malli/schema [:function [:=> [:cat :map [:maybe :string] :any :string :any] :any]
                [:=> [:cat :map [:maybe :string] :any :string :any :any] :any]]
 :cost/ticks 100000000}
([prov system-prompt history user-prompt on-delta]
 (openai-stream-chat! prov system-prompt history user-prompt nil on-delta))
([prov system-prompt history user-prompt wire-messages on-delta]
 (let [api-key-env (or (:api-key-env prov) "DEEPSEEK_API_KEY")
       api-key (or (:api-key prov) (System/getenv api-key-env))]
   (when (str/blank? api-key)
     (throw (ex-info (str "Missing model API key in env var " api-key-env)
                     {:error :seon.model/missing-api-key :env api-key-env})))
   (let [base-url (or (:base-url prov) "https://api.deepseek.com")
         url (str (str/replace base-url #"/+$" "") "/chat/completions")
         raw-req (build-request-body (cond-> prov wire-messages (assoc :messages wire-messages))
                                     system-prompt history user-prompt)
         endpoint-k [base-url (or (:model prov) (:model raw-req))]
         init-req (if (and (contains? @multimodal-unsupported-endpoints endpoint-k)
                           (messages-have-image-parts? (:messages raw-req)))
                    (update raw-req :messages strip-image-parts)
                    raw-req)]
     (loop [attempt 0
            req-map init-req]
       (let [req-body (json/write-str req-map)
             post-res (try
                        {:ok? true
                         :resp (http/post url {:headers {"Authorization" (str "Bearer " api-key)
                                                         "Content-Type" "application/json"
                                                         "Accept" "text/event-stream"}
                                               :body req-body
                                               :as :stream
                                               :throw false})}
                        (catch Throwable ex
                          {:ok? false :ex ex}))]
         (if-not (:ok? post-res)
           (if (and (zero? attempt)
                    (messages-have-image-parts? (:messages req-map)))
             (do
               (when-not (contains? @multimodal-fallback-logged endpoint-k)
                 (swap! multimodal-fallback-logged conj endpoint-k)
                 (binding [*out* *err*]
                   (println (str "[:model] endpoint " url " (model=" (second endpoint-k)
                                 ") dropped connection on image_url parts (" (.getMessage ^Throwable (:ex post-res))
                                 "); stripping image parts and retrying text-only."))))
               (recur 1 (update req-map :messages strip-image-parts)))
             (throw ^Throwable (:ex post-res)))
           (let [resp (:resp post-res)
                 status (long (or (:status resp) 0))
                 body (:body resp)]
             (if (not= 200 status)
               (let [body-str (cond
                                (nil? body) ""
                                (string? body) body
                                :else (with-open [in ^java.io.InputStream body] (slurp in :encoding "UTF-8")))]
                 (if (and (zero? attempt)
                          (messages-have-image-parts? (:messages req-map))
                          (multimodal-unsupported-error? status body-str))
                   (do
                     (swap! multimodal-unsupported-endpoints conj endpoint-k)
                     (when-not (contains? @multimodal-fallback-logged endpoint-k)
                       (swap! multimodal-fallback-logged conj endpoint-k)
                       (binding [*out* *err*]
                         (println (str "[:model] endpoint " url " (model=" (second endpoint-k)
                                       ") rejected image_url parts (HTTP " status
                                       "); stripping image parts and retrying text-only."))))
                     (recur 1 (update req-map :messages strip-image-parts)))
                   (let [hdrs (:headers resp)
                         retry-after (some (fn [[k v]]
                                             (when (= "retry-after" (str/lower-case (str k)))
                                               (try (* 1000 (parse-long (str/trim (str v)))) (catch Throwable _ nil))))
                                           hdrs)]
                     (throw (ex-info (str "Model HTTP " status ": " (subs body-str 0 (min 300 (count body-str))))
                                     {:error (if (= 429 status) :seon.model/rate-limited :seon.model/provider-error)
                                      :status status
                                      :retry-after-ms retry-after
                                      :headers hdrs
                                      :body body-str})))))
               (do
                 (when (pos? attempt)
                   (swap! multimodal-unsupported-endpoints conj endpoint-k))
                 (let [st-vol (volatile! (make-stream-state))]
                   (when body
                     (with-open [rdr (io/reader body :encoding "UTF-8")]
                       (sse/read-events!
                        rdr
                        {:on-event (fn [ev]
                                     (when-let [parsed (wire/decode-sse-data (:data ev))]
                                       (let [[st1 delta] (process-sse-chunk parsed @st-vol)]
                                         (vreset! st-vol st1)
                                         (when (and delta on-delta) (on-delta delta)))))})))
                   (finalize-stream-state @st-vol))))))))))))

(defn model-proc
  {:malli/schema [:function [:=> [:cat] :any] [:=> [:cat :any] :any] [:=> [:cat :any :any] :any] [:=> [:cat :any :any :any] :any]]}
  ([] {:params {:provider "Provider map {:type :built-in|:openai-compat ...}"
                :ctrl-ch "Optional throttle/slot control channel"
                :max-slots "Max concurrent in-flight model requests"}
       :ins {:request "Model completion requests from :agents"}
       :outs {:response "Model completions back to :agents" :stream "Live token & reasoning streaming deltas for :web (60+ fps!)" :tx "First-class :seon.error/* transaction ops to :transactor"}
       :workload :io
       :doc "Streaming OpenAI-compat / DeepSeek boundary with ::flow/input-filter backpressure."
       :ping-map-fn (fn [st]
                    {:provider-type (get-in st [:provider :type] :built-in)
                     :max-slots (:max-slots st)
                     :in-flight (count (:in-flight st))
                     :backpressuring? (contains? st ::flow/input-filter)
                     :completed (:completed st)
                     :errors (:errors st 0)})})
  ([{:keys [provider ctrl-ch max-slots latency-ms]}]
   (let [done-ch (a/chan 32)
         delta-ch (a/chan (a/sliding-buffer 128))]
     {:provider (or provider {:type :built-in})
     :done-ch done-ch
     :delta-ch delta-ch
     :max-slots (or max-slots 4)
     :latency-ms (or latency-ms 60)
     :in-flight #{}
     :completed 0
     :errors 0
     :ctx-windows {}
     ::flow/in-ports (cond-> {:done done-ch :delta delta-ch} ctrl-ch (assoc :ctrl ctrl-ch))}))
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
                                   " provider=" (get-in st' [:provider :type]))]}])

       :delta
       [st {:stream [msg]}]

       :request
       (let [{:keys [req-id agent-id system-prompt history user-prompt wire-messages]} msg
             done-ch (:done-ch st)
             delta-ch (:delta-ch st)
             prov (:provider st)
             delay-ms (:latency-ms st)
             ctx-toks (long (or (:context-tokens msg)
                                (when (seq wire-messages)
                                  (reduce + 0 (map estimate-message-tokens wire-messages)))
                                0))
             cw-key (when (= :openai-compat (:type prov)) [(:base-url prov) (:model prov)])
             known-cw (when cw-key (get (:ctx-windows st) cw-key))
             probe-cw? (and cw-key (not (contains? (:ctx-windows st) cw-key)))]
         (a/go
         (let [cw (if probe-cw?
                    (let [v (wire/fetch-model-context-window! prov)]
                      (when v (a/offer! delta-ch {:agent-id agent-id :req-id req-id :status :ctx-meta :context-tokens ctx-toks :max-context-tokens v}))
                      v)
                    known-cw)
               ans (try
                     (if (= :openai-compat (:type prov))
                       (openai-stream-chat!
                        prov
                        (or system-prompt default-system-prompt)
                        (or history [])
                        (or user-prompt (pr-str (select-keys msg [:agent-id :scope :role :goal :unread :turn :health])))
                        wire-messages
                        (fn [{:keys [kind text]}]
                          (a/offer! delta-ch {:agent-id agent-id :req-id req-id :status kind :text text})))
                       (do (when (pos? delay-ms) (a/<! (a/timeout delay-ms)))
                           (built-in-agent-synthesis msg)))
                     (catch Throwable ex
                       (error/model-error-response {:exception ex :provider prov :agent-id agent-id :branch (:branch msg) :req-id req-id})))]
           (a/>! done-ch (cond-> {:req-id req-id :agent-id agent-id :response ans :context-tokens ctx-toks}
                           (:branch msg) (assoc :branch (:branch msg))
                           probe-cw? (assoc :cw-key cw-key :max-context-tokens cw)))))
         [(sync-model-filter (update st :in-flight conj req-id))
          {:stream [(cond-> {:agent-id agent-id :status :thinking :req-id req-id :context-tokens ctx-toks}
                      (pos? (long (or known-cw 0))) (assoc :max-context-tokens known-cw))]}])

       :done
       (let [resp (:response msg)
           err? (or (:error-entity resp) (:error resp) (:seon.error/kind resp))
           usage (:usage resp)
           p-toks (long (or (:prompt-tokens usage) (:context-tokens msg) 0))
           c-toks (long (or (:completion-tokens usage) 0))
           tot-toks (if (pos? p-toks) (+ p-toks c-toks) 0)
           st' (-> st
                   (update :in-flight disj (:req-id msg))
                   (update :completed inc)
                   (cond-> err? (update :errors (fnil inc 0))
                           (:cw-key msg) (assoc-in [:ctx-windows (:cw-key msg)] (:max-context-tokens msg)))
                   sync-model-filter)]
       (if err?
         [st' (error/model-error-outputs msg)]
         [st' {:response [msg]
               :stream [(cond-> {:agent-id (:agent-id msg) :status :done :req-id (:req-id msg)}
                          (pos? tot-toks) (assoc :context-tokens tot-toks)
                          (pos? (long (or (:max-context-tokens msg) 0))) (assoc :max-context-tokens (:max-context-tokens msg)))]}]))))))