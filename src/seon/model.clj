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
            [jolt.http.core :as http]
            [seon.model.wire :as wire]))

(defn- sync-model-filter [st]
  (if (>= (count (:in-flight st)) (:max-slots st))
    (assoc st ::flow/input-filter #{:done :ctrl})
    (dissoc st ::flow/input-filter)))

;; --- The Single `repl` Tool & Prefix-Cache-Friendly Wire Assembly ---

(def tool-name wire/tool-name)

(def eval-tool
  {:type "function"
   :function {:name tool-name
              :description "Evaluate Clojure forms in your branch's live Jolt REPL. Definitions persist into Datahike automatically; read `@seon.ctx/ctx` for your reactive entity & namespace subtree."
              :parameters {:type "object"
                           :properties (array-map
                                        :intent {:type "string" :description "Short present-tense action label shown to the user in the activity list (e.g. 'Inspect seon.health/report', 'Add LRU cache')."}
                                        :code   {:type "string" :description "Clojure form(s) to evaluate."})
                           :required ["intent" "code"]
                           :additionalProperties false}}})

(def default-system-prompt
(str "You are a Seon Clojure agent running in your branch's live Jolt runtime.\n"
     "- Your live `@seon.ctx/ctx` state, scope, and helpers live in `<walk-context>`; ephemeral tail blocks (branch status, health, activity, viewport) refresh every turn at the end of context.\n"
     "- Batch independent reads/edits into multiple `repl` tool calls in one turn. Put a crisp 4–8 word present-tense title in `:intent`, keep reasoning in thinking tokens, and leave assistant `content` empty on tool-calling turns (use prose only for final replies or major milestones). Each eval binds to `result/e<n>`.\n"
     "- Every newly added or edited public `defn` must carry a `:malli/schema` contract (use `(user/set-meta! 'ns/fn {:malli/schema [...]})`), and pure functions should declare `:cost/ticks` and `:cost/bytes` budgets.\n"
     "- When coordinating or delegating via `(user/ask! :ns/<subsys> ...)` + `(user/await! [...])`, inspect returned results or worker branches (`(user/inspect-branch ...)`), critique weak answers, and launch follow-up research or fixes if needed before answering.\n"
     "- Reply in clear, concise prose with NO tool calls when done. Never treat `<session kind=\"example\">` as your live session."))

(defn- format-tool-content [{:keys [handle value out error ms ticks result-schema]}]
  (pr-str (cond-> {:ok? (nil? error) :ms ms :ticks ticks}
            handle        (assoc :handle handle)
            result-schema (assoc :schema result-schema)
            (nil? error)  (assoc :value value)
            (not (str/blank? (str out))) (assoc :out out)
            error         (assoc :error error))))

(defn- turns->wire-messages [{:keys [thinking? replay-reasoning?] :or {replay-reasoning? true}} turns]
  (let [seen-tc-ids (volatile! #{})
        uniq-tc-id (fn [raw-id]
                     (let [base (str (or raw-id "tc"))]
                       (if-not (contains? @seen-tc-ids base)
                         (do (vswap! seen-tc-ids conj base) base)
                         (loop [n 2]
                           (let [cand (str base "-" n)]
                             (if-not (contains? @seen-tc-ids cand)
                               (do (vswap! seen-tc-ids conj cand) cand)
                               (recur (inc n))))))))]
    (mapcat
     (fn [{:keys [messages thought reasoning calls]}]
       (let [in-msgs (mapv (fn [m]
                             {:role "user"
                              :content (str "<message from=\"" (or (:seon.agent.message/from m) (:from m) "user")
                                            "\" type=\"" (name (or (:seon.agent.message/type m) (:type m) :chat)) "\">\n"
                                            (or (:seon.agent.message/body m) (:body m) "")
                                            "\n</message>")})
                           messages)
             real-calls (mapv (fn [c] (assoc c :id (uniq-tc-id (:id c))))
                              (filter #(not (str/blank? (str (:code %)))) calls))
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
     turns)))

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

(defn vision-capable-model?
  "Returns true when `model-name` natively accepts OpenAI-compat `image_url` content blocks."
  [model-name]
  (let [m (str/lower-case (str (or model-name "")))]
    (boolean
     (or (str/includes? m "flash")
         (str/includes? m "vision")
         (str/includes? m "vl")
         (str/starts-with? m "gpt-4o")
         (str/starts-with? m "gpt-5")
         (str/starts-with? m "claude-")
         (str/starts-with? m "gemini-")))))

(defn- format-viewport-dom [dom]
  (when (map? dom)
    (let [parts (remove nil?
                        [(when (not (str/blank? (str (:viewing dom)))) (str "viewing=" (:viewing dom)))
                         (when (not (str/blank? (str (:mode dom)))) (str "mode=" (:mode dom)))
                         (when (:theme dom) (str "theme=" (:theme dom)))
                         (when (some? (:overflowX dom)) (str "overflowX=" (:overflowX dom)))
                         (when (some? (:svgCount dom)) (str "svgs=" (:svgCount dom)))
                         (when (seq (:svgs dom)) (str "svgBoxes=" (pr-str (:svgs dom))))])]
      (when (seq parts) (str/join " · " parts)))))

(defn build-agent-wire-messages
  "Single source of truth for BOTH live LLM requests AND Level 3 Debug View.
   Produces proper OpenAI-compat / DeepSeek wire messages with:
   1. Byte-identical `system` message (KV prefix cache anchor)
   2. Optional `<session id=\"example/...\" kind=\"example\" from=\"seon\">...</session>` in exact wire roles
   3. `<agent id=\"...\" branch=\"...\" from=\"seon\">` opening + initial rendered walk tiles
   4. Append-only live conversation turns (`user`, `assistant` + `tool_calls`, `tool` with `result/e<n>`)
   5. Current turn unread messages + walk delta + optional ephemeral tail screenshot."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [thinking? replay-reasoning? vision?] :or {replay-reasoning? true} :as opts}
   {:keys [agent-id branch role goal system-prompt examples walk-text history unread delta-text activity-text viewport-screenshot tail-blocks ephemeral-image]}]
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
       :content (str "<agent id=\"" (or agent-id :agent/unknown)
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
      dom-summary (format-viewport-dom (:dom viewport-screenshot))
      fmt-attrs (fn [m]
                  (str/join "" (map (fn [[k v]] (str " " (name k) "=\"" v "\""))
                                    (sort-by (comp name key) m))))
      rendered-tails (if (some? tail-blocks)
                       (mapv (fn [{:keys [tag attrs text]}]
                               (let [t (or tag "tail")]
                                 (str "<" t (fmt-attrs (assoc (or attrs {}) :ephemeral "true")) ">\n"
                                      (or text "")
                                      "\n</" t ">")))
                             tail-blocks)
                       [(when (not (str/blank? (str activity-text)))
                          (str "<neighborhood-activity ephemeral=\"true\">\n" activity-text "\n</neighborhood-activity>"))
                        (when viewport-screenshot
                          (str "<live-viewport route=\"" (:route viewport-screenshot "/")
                               "\" size=\"" (:width viewport-screenshot 1280) "x" (:height viewport-screenshot 820)
                               "\" ephemeral=\"true\">"
                               "Current live WKWebView screenshot of what the user sees right now (always replaced at end of context — inspect closely and fix any clipping, overlap, contrast, or stray SVG artifacts before finishing)."
                               (when dom-summary (str "\nDOM: " dom-summary))
                               "</live-viewport>"))])
      tail-text (str/join "\n\n"
                          (remove str/blank?
                                  (concat unread-blocks
                                          [(when (not (str/blank? (str delta-text)))
                                             (str "<walk-diff>\n" delta-text "\n</walk-diff>"))]
                                          rendered-tails)))
      eff-img (or ephemeral-image
                  (when (and (nil? tail-blocks) vision? (:base64 viewport-screenshot))
                    {:mime (or (:mime viewport-screenshot) "image/jpeg")
                     :base64 (:base64 viewport-screenshot)
                     :raw viewport-screenshot}))
      tail-msg (when (or (not (str/blank? tail-text)) (:base64 eff-img))
                 (if (:base64 eff-img)
                   {:role "user"
                    :content [{:type "text" :text (if (str/blank? tail-text) "Current live viewport screenshot:" tail-text)}
                              {:type "image_url"
                               :image_url {:url (str "data:" (or (:mime eff-img) "image/jpeg")
                                                     ";base64," (:base64 eff-img))
                                           :detail "auto"}}]}
                   {:role "user" :content tail-text}))
      messages (vec (concat [sys-msg] example-msgs [open-msg] hist-msgs (when tail-msg [tail-msg])))
      msg-tokens (mapv estimate-message-tokens messages)]
  {:messages messages
   :message-tokens msg-tokens
   :total-tokens (reduce + 0 msg-tokens)
   :prefix-messages-count (dec (count messages))
   :ephemeral-screenshot (or (:raw eff-img) (when (nil? tail-blocks) viewport-screenshot))}))

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
    (cond-> {:model (or model "deepseek-flash")
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

(defn make-stream-state "Stable `seon.model` facade over `seon.model.wire/make-stream-state`." [& args] (apply wire/make-stream-state args))

(defn parse-sse-line "Stable `seon.model` facade over `seon.model.wire/parse-sse-line`." [& args] (apply wire/parse-sse-line args))

(defn process-sse-chunk "Stable `seon.model` facade over `seon.model.wire/process-sse-chunk`." [& args] (apply wire/process-sse-chunk args))

(defn finalize-stream-state "Stable `seon.model` facade over `seon.model.wire/finalize-stream-state`." [& args] (apply wire/finalize-stream-state args))

;; --- Pure Fiber-Parking HTTP POST & SSE Stream Client (`jolt.http.core`) ---

(defn http-post! "Stable `seon.model` facade over `seon.model.wire/http-post!`." [& args] (apply wire/http-post! args))

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
                                  "(defn " fn-sym " {:malli/schema [:=> [:cat :int] :int]} [x] (+ x " (* turn 10) "))\n"
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
