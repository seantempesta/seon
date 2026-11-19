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
            [jolt.http.core :as http]))

(defn- sync-model-filter [st]
  (if (>= (count (:in-flight st)) (:max-slots st))
    (assoc st ::flow/input-filter #{:done :ctrl})
    (dissoc st ::flow/input-filter)))

;; --- The Single `repl` Tool & Prefix-Cache-Friendly Wire Assembly ---

(def tool-name "repl")

(def eval-tool
  {:type "function"
   :function {:name tool-name
              :description "Evaluate Clojure forms in your branch's live Jolt REPL. Definitions persist into Datahike automatically; read `@seon.ctx/ctx` for your reactive entity & namespace subtree."
              :parameters {:type "object"
                           :properties (array-map
                                        :intent {:type "string" :description "One sentence explaining what this eval does."}
                                        :code   {:type "string" :description "Clojure form(s) to evaluate."})
                           :required ["intent" "code"]
                           :additionalProperties false}}})

(def default-system-prompt
  (str "You are a Seon Clojure agent running in your branch's live Jolt runtime.\n"
       "- Your live `@seon.ctx/ctx` state and available helpers are shown directly inside the `<walk-context>` tiles below.\n"
       "- Use `repl` when you need to inspect Datahike, compute, update the user's Canvas, or edit code; each eval binds to `result/e<n>`.\n"
       "- Reply in clear, concise prose with NO tool calls when answering the user. Never treat `<session kind=\"example\">` as your live session."))

(defn- format-tool-content [{:keys [handle value out error ms ticks result-schema]}]
  (pr-str (cond-> {:ok? (nil? error) :ms ms :ticks ticks}
            handle        (assoc :handle handle)
            result-schema (assoc :schema result-schema)
            (nil? error)  (assoc :value value)
            (not (str/blank? (str out))) (assoc :out out)
            error         (assoc :error error))))

(defn- turns->wire-messages [{:keys [thinking? replay-reasoning?] :or {replay-reasoning? true}} turns]
  (mapcat
   (fn [{:keys [messages thought reasoning calls]}]
     (let [in-msgs (mapv (fn [m]
                           {:role "user"
                            :content (str "<message from=\"" (or (:seon.agent.message/from m) (:from m) "user")
                                          "\" type=\"" (name (or (:seon.agent.message/type m) (:type m) :chat)) "\">\n"
                                          (or (:seon.agent.message/body m) (:body m) "")
                                          "\n</message>")})
                         messages)
           real-calls (filterv #(not (str/blank? (str (:code %)))) calls)
           tcs (mapv (fn [{:keys [id intent code]}]
                       {:id id
                        :type "function"
                        :function {:name tool-name
                                   :arguments (json/write-str
                                               (cond-> (array-map)
                                                 intent (assoc :intent intent)
                                                 true   (assoc :code (or code ""))))}})
                     real-calls)
           asst (when (or (seq tcs) (not (str/blank? (str thought))) (some? reasoning))
                  (let [base (cond-> {:role "assistant" :content (or thought "")}
                               (seq tcs) (assoc :tool_calls tcs)
                               (and replay-reasoning? (some? reasoning))
                               (assoc :reasoning_content reasoning))]
                    (if (and thinking? (not (contains? base :reasoning_content)))
                      (assoc base :reasoning_content "")
                      base)))
           tool-msgs (mapv (fn [{:keys [id] :as c}]
                             {:role "tool"
                              :tool_call_id id
                              :content (format-tool-content c)})
                           real-calls)]
       (concat in-msgs (when asst [asst]) tool-msgs)))
   turns))

(defn build-wire-messages
  "Transforms provider-neutral conversation turns + system prompt into prefix-cache-friendly
   OpenAI-compat / DeepSeek wire messages (`assistant` with `tool_calls` + `reasoning_content`,
   followed by matching `tool` messages, then the current turn's `user` context delta)."
  [{:keys [thinking? replay-reasoning?] :or {replay-reasoning? true} :as opts} system-prompt history user-prompt]
  (let [sys-msg (when (and system-prompt (not (str/blank? system-prompt)))
                  {:role "system" :content system-prompt})
        hist-msgs (turns->wire-messages opts history)]
    (vec (concat (when sys-msg [sys-msg])
                 hist-msgs
                 [{:role "user" :content (str user-prompt)}]))))

(defn estimate-message-tokens
  "Fast O(chars) token estimate for one OpenAI-compat message (used by Level 3 Debug view)."
  [msg]
  (let [c (:content msg)
        text-len (cond
                   (string? c) (count c)
                   (sequential? c) (reduce + 0 (map #(if (= "image_url" (:type %)) 1024 (count (str (:text %)))) c))
                   :else (count (str c)))
        tc-len (reduce + 0 (map #(count (get-in % [:function :arguments] "")) (:tool_calls msg)))
        r-len (count (str (:reasoning_content msg "")))]
    (max 4 (long (Math/ceil (/ (+ text-len tc-len r-len 16) 4.0))))))

(defn build-agent-wire-messages
  "Single source of truth for BOTH live LLM requests AND Level 3 Debug View (`/root/debug`, `/agent/<id>/debug`).
   Produces proper OpenAI-compat / DeepSeek wire messages with:
   1. Byte-identical `system` message (KV prefix cache anchor)
   2. Optional `<session id=\"example/...\" kind=\"example\" from=\"seon\">...</session>` in exact wire roles
   3. `<agent id=\"...\" branch=\"...\" from=\"seon\">` opening + initial rendered walk tiles
   4. Append-only live conversation turns (`user`, `assistant` + `tool_calls`, `tool` with `result/e<n>`)
   5. Current turn unread messages + walk delta + optional **ephemeral tail screenshot** (replaced every
      turn, never stored in history, attached only when a user is actively viewing the route)."
  [{:keys [thinking? replay-reasoning? vision?] :or {replay-reasoning? true} :as opts}
   {:keys [agent-id branch role goal system-prompt examples walk-text history unread delta-text viewport-screenshot]}]
  (let [sys-msg {:role "system" :content (or (not-empty system-prompt) default-system-prompt)}
        example-msgs
        (mapcat
         (fn [{:keys [id goal turns]}]
           (concat
            [{:role "user"
              :content (str "<session id=\"example/" (name id) "\" kind=\"example\" from=\"seon\">\n"
                            "A finished session of another task, shown for how its work was done; not yours.\n"
                            "Goal: " (or goal "(none)"))}]
            (turns->wire-messages opts turns)
            [{:role "user" :content "</session>"}]))
         examples)
        open-msg
        {:role "user"
         :content (str "<agent id=\"" (or agent-id :agent/root)
                       "\" branch=\"" (or branch :main)
                       "\" role=\"" (name (or role :worker))
                       "\" from=\"seon\">\n"
                       "Your own live session.\n"
                       "Goal: " (or (not-empty goal) "Assist the user and keep the system healthy.")
                       (when (not (str/blank? (str walk-text)))
                         (str "\n\n<walk-context>\n" walk-text "\n</walk-context>")))}
        hist-msgs (vec (turns->wire-messages opts history))
        unread-blocks (mapv (fn [m]
                              (str "<message from=\"" (or (:seon.agent.message/from m) (:from m) "user")
                                   "\" type=\"" (name (or (:seon.agent.message/type m) (:type m) :chat)) "\">\n"
                                   (or (:seon.agent.message/body m) (:body m) "")
                                   "\n</message>"))
                            unread)
        tail-text (str/join "\n\n"
                            (remove str/blank?
                                    (concat unread-blocks
                                            [(when (not (str/blank? (str delta-text)))
                                               (str "<walk-diff>\n" delta-text "\n</walk-diff>"))
                                             (when viewport-screenshot
                                               (str "<live-viewport route=\"" (:route viewport-screenshot "/")
                                                    "\" size=\"" (:width viewport-screenshot 1280) "x" (:height viewport-screenshot 820)
                                                    "\" ephemeral=\"true\">"
                                                    "Current screenshot of what the user sees right now (replaced each turn — fix any visual or layout issues you spot)."
                                                    "</live-viewport>"))])))
        tail-msg (when (or (not (str/blank? tail-text)) (and vision? viewport-screenshot))
                   (if (and vision? (:base64 viewport-screenshot))
                     {:role "user"
                      :content [{:type "text" :text (if (str/blank? tail-text) "Current live viewport screenshot:" tail-text)}
                                {:type "image_url"
                                 :image_url {:url (str "data:" (or (:mime viewport-screenshot) "image/jpeg")
                                                       ";base64," (:base64 viewport-screenshot))}}]}
                     {:role "user" :content tail-text}))
        messages (vec (concat [sys-msg] example-msgs [open-msg] hist-msgs (when tail-msg [tail-msg])))
        msg-tokens (mapv estimate-message-tokens messages)]
    {:messages messages
     :message-tokens msg-tokens
     :total-tokens (reduce + 0 msg-tokens)
     :prefix-messages-count (dec (count messages))
     :ephemeral-screenshot viewport-screenshot}))

(defn build-request-body
  "Constructs the OpenAI-compat / DeepSeek request body using present-only optional params
   (`temperature` omitted unless explicitly set, `stream_options: {:include_usage true}` when streaming)."
  [{:keys [model max-tokens temperature thinking reasoning-effort replay-reasoning stream? messages]
    :or {stream? true replay-reasoning true}}
   system-prompt history user-prompt]
  (let [thinking-enabled? (= :enabled thinking)
        raw-msgs (or messages
                     (build-wire-messages {:thinking? thinking-enabled?
                                           :replay-reasoning? (not (false? replay-reasoning))}
                                          system-prompt history user-prompt))
        ;; DeepSeek thinking mode requires every prior assistant message to carry `:reasoning_content`
        msgs (if thinking-enabled?
               (mapv (fn [m]
                       (if (and (= "assistant" (:role m)) (not (contains? m :reasoning_content)))
                         (assoc m :reasoning_content "")
                         m))
                     raw-msgs)
               raw-msgs)]
    (cond-> {:model (or model "deepseek-chat")
             :messages msgs
             :tools [eval-tool]
             :tool_choice "auto"
             :stream (boolean stream?)}
      stream? (assoc :stream_options {:include_usage true})
      max-tokens (assoc :max_tokens (long max-tokens))
      (some? temperature) (assoc :temperature temperature)
      thinking (assoc :thinking {:type (name thinking)})
      (and reasoning-effort (not= :disabled thinking))
      (assoc :reasoning_effort (name reasoning-effort)))))

;; --- `StringBuilder` SSE Chunk Accumulator (Borrowed from `clj-agent/stream/openai.clj`) ---

(defn make-stream-state []
  {:content (StringBuilder.)
   :reasoning (StringBuilder.)
   :tool-calls {}
   :finish-reason nil
   :usage nil})

(defn parse-sse-line [^String line]
  (when (and line (str/starts-with? line "data:"))
    (let [payload (str/trim (subs line 5))]
      (when (and (not (str/blank? payload)) (not= payload "[DONE]"))
        (try (json/read-str payload :key-fn keyword)
             (catch Throwable _ nil))))))

(defn process-sse-chunk
  "Purely accumulates one decoded SSE JSON chunk into `state`, returning `[state' delta-event-or-nil]`."
  [chunk state]
  (let [choice (first (:choices chunk))
        delta (:delta choice)
        state1 (cond-> state
                 (:usage chunk) (assoc :usage (:usage chunk))
                 (:finish_reason choice) (assoc :finish-reason (:finish_reason choice)))
        state2 (if-let [tcs (:tool_calls delta)]
                 (assoc state1 :tool-calls
                        (reduce
                         (fn [acc tc]
                           (let [idx (or (:index tc) (:id tc) (count acc))
                                 prev (get acc idx {:id nil :name "" :args (StringBuilder.)})
                                 ^StringBuilder sb (:args prev)]
                             (when-let [a (get-in tc [:function :arguments])]
                               (.append sb ^String a))
                             (assoc acc idx
                                    {:id (or (:id tc) (:id prev))
                                     :name (str (:name prev) (or (get-in tc [:function :name]) ""))
                                     :args sb})))
                         (:tool-calls state1)
                         tcs))
                 state1)
        rtext (not-empty (:reasoning_content delta))
        ctext (not-empty (:content delta))]
    (when rtext (.append ^StringBuilder (:reasoning state2) ^String rtext))
    (when ctext (.append ^StringBuilder (:content state2) ^String ctext))
    [state2
     (cond
       ctext {:kind :token :text ctext}
       rtext {:kind :reasoning-token :text rtext}
       :else nil)]))

(defn- parse-tool-args [^String s]
  (if (str/blank? s)
    {}
    (try (json/read-str s :key-fn keyword)
         (catch Throwable _ {:code s}))))

(defn finalize-stream-state
  "Materializes the accumulated `StringBuilder` SSE state into our clean internal completion map."
  [{:keys [^StringBuilder content ^StringBuilder reasoning tool-calls finish-reason usage]}]
  (let [tcs (->> (sort-by key tool-calls)
                 (mapv (fn [[idx {:keys [id name ^StringBuilder args]}]]
                         (let [parsed (parse-tool-args (.toString args))]
                           {:id (or id (str "call-" idx))
                            :tool (or (not-empty name) tool-name)
                            :intent (:intent parsed)
                            :code (or (:code parsed) "")}))))
        norm-usage (when usage
                     (cond-> {}
                       (:prompt_tokens usage) (assoc :prompt-tokens (:prompt_tokens usage))
                       (:completion_tokens usage) (assoc :completion-tokens (:completion_tokens usage))
                       (:prompt_cache_hit_tokens usage) (assoc :cache-hit-tokens (:prompt_cache_hit_tokens usage))
                       (:prompt_cache_miss_tokens usage) (assoc :cache-miss-tokens (:prompt_cache_miss_tokens usage))
                       (get-in usage [:prompt_tokens_details :cached_tokens])
                       (assoc :cache-hit-tokens (get-in usage [:prompt_tokens_details :cached_tokens]))))]
    (cond-> {:thought (.toString content)
             :finish-reason finish-reason
             :tool-calls tcs}
      (pos? (.length reasoning)) (assoc :reasoning (.toString reasoning))
      (seq norm-usage) (assoc :usage norm-usage))))

;; --- Pure Fiber-Parking HTTP POST & SSE Stream Client (`jolt.http.core`) ---

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
        {:status (:status r) :headers (:header-pairs r) :body (String. ^bytes bs "UTF-8")})
      (finally
        (http/s-close s)))))

(defn openai-stream-chat!
  "Streams an OpenAI-compat / DeepSeek `/chat/completions` call over `jolt.http.core`, invoking
   `(on-delta {:kind :token|:reasoning-token :text s})` per chunk and returning `finalize-stream-state`."
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
           req-body (json/write-str (build-request-body (cond-> prov wire-messages (assoc :messages wire-messages))
                                                        system-prompt history user-prompt))
          headers [["Authorization" (str "Bearer " api-key)]
                   ["Content-Type" "application/json"]
                   ["Accept" "text/event-stream"]]
          u (http/parse-url url)
          s (http/connect-stream (http/tget u :host) (http/effective-port u) (= "https" (http/tget u :protocol)) false nil nil)]
      (try
        (http/s-write s (http/build-request "POST" u headers req-body))
        (let [r (http/read-response s nil "POST" nil true true)]
          (if (not= 200 (:status r))
            (let [bs (if-let [pull (:body-pull r)]
                       (http/concat-bas (loop [acc []] (if-let [b (pull)] (recur (conj acc b)) acc)))
                       (or (:body r) (byte-array 0)))
                  body-str (String. ^bytes bs "UTF-8")
                  retry-after (some (fn [[k v]]
                                      (when (= "retry-after" (str/lower-case (str k)))
                                        (try (* 1000 (parse-long (str/trim (str v)))) (catch Throwable _ nil))))
                                    (:header-pairs r))]
              (throw (ex-info (str "Model HTTP " (:status r) ": " (subs body-str 0 (min 300 (count body-str))))
                              {:error (if (= 429 (:status r)) :seon.model/rate-limited :seon.model/provider-error)
                               :status (:status r)
                               :retry-after-ms retry-after
                               :body body-str})))
            (let [pull (or (:body-pull r)
                           (let [b (volatile! (:body r))]
                             (fn [] (let [x @b] (vreset! b nil) x))))]
              (loop [st (make-stream-state)
                     carry ""]
                (if-let [chunk-bytes (pull)]
                  (let [text (str carry (String. ^bytes chunk-bytes "UTF-8"))
                        lines (str/split text #"\r?\n" -1)
                        complete (pop lines)
                        rem-carry (peek lines)
                        st' (reduce (fn [acc line]
                                      (if-let [parsed (parse-sse-line line)]
                                        (let [[acc' delta] (process-sse-chunk parsed acc)]
                                          (when (and delta on-delta) (on-delta delta))
                                          acc')
                                        acc))
                                    st
                                    complete)]
                    (recur st' rem-carry))
                  (let [final-st (if-let [parsed (parse-sse-line carry)]
                                   (first (process-sse-chunk parsed st))
                                   st)]
                    (finalize-stream-state final-st)))))))
        (finally
          (http/s-close s)))))))

;; --- Built-In Deterministic Synthesizer (Offline Tests & Local Autonomous Demos) ---

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
       :tool-calls [{:id (str "tc-" turn "-0")
                     :intent "Evaluate directive form and mark branch ready"
                     :code (str explicit-code "\n(user/ready!)")}]}

      (some #(#{:test-failure :merge-conflict} (:seon.agent.message/type %)) unread)
      {:thought "Recorded test/merge failure notice; awaiting next directive."
       :tool-calls [{:id (str "tc-" turn "-0") :intent "Wait for updated directive" :code "(user/wait-for-mail!)"}]}

      (and (= role :coordinator) (= turn 1))
      (let [wa (keyword ns-part "worker-a")
            wb (keyword ns-part "worker-b")
            base-sc (or scope 'seon.generated)]
        {:thought (str "Decomposing coordinator goal '" goal "' into child agents " wa " and " wb)
         :tool-calls [{:id (str "tc-" turn "-0")
                       :intent "Spawn scoped workers and await reports"
                       :code (pr-str
                              `(do
                                 (user/spawn-agent! {:id ~wa :scope (quote ~(symbol (str base-sc ".a"))) :role :worker :goal ~(str "Implement helper for " goal)})
                                 (user/spawn-agent! {:id ~wb :scope (quote ~(symbol (str base-sc ".b"))) :role :worker :goal ~(str "Verify & message peer for " goal)})
                                 (user/wait-for-mail!)))}]})

      (= role :coordinator)
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
                                  "(defn " fn-sym " [x] (+ x " (* turn 10) "))\n"
                                  "(ns " test-ns " (:require [clojure.test :refer [deftest is]] [" fn-ns " :as sut]))\n"
                                  "(deftest " test-sym " (is (= " (+ 5 (* turn 10)) " (sut/" fn-sym " 5))))\n"
                                  "(in-ns 'user)\n"
                                  (when parent
                                    (str "(user/send-message! " parent " :report \"Defined " fn-ns "/" fn-sym " with passing deftest\")\n"))
                                  "(user/ready!)")}]}))))

(defn model-proc
  ([] {:params {:provider "Provider map {:type :built-in|:openai-compat ...}"
                :ctrl-ch "Optional throttle/slot control channel"
                :max-slots "Max concurrent in-flight model requests"}
       :ins {:request "Model completion requests from :agents"}
       :outs {:response "Model completions back to :agents"
              :stream "Live token & reasoning streaming deltas for :web (60+ fps!)"}
       :workload :io
       :doc "Streaming OpenAI-compat / DeepSeek boundary with ::flow/input-filter backpressure."
       :ping-map-fn (fn [st]
                      {:provider-type (get-in st [:provider :type] :built-in)
                       :max-slots (:max-slots st)
                       :in-flight (count (:in-flight st))
                       :backpressuring? (contains? st ::flow/input-filter)
                       :completed (:completed st)})})
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
             delay-ms (:latency-ms st)]
         (a/go
           (let [ans (try
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
                         (let [d (ex-data ex)]
                           {:thought (str "Model error: " (.getMessage ex))
                            :error (or (:error d) :seon.model/provider-error)
                            :retry-after-ms (:retry-after-ms d)
                            :tool-calls [{:id (str "err-" req-id) :intent "Pause on model error" :code "(user/wait-for-mail!)"}]})))]
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
