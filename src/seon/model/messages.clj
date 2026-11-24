(ns seon.model.messages
  "Provider-neutral conversation turns -> OpenAI-compat / DeepSeek wire messages, request bodies, and token estimates (extracted from `seon.model` for the 8k ns budget)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [seon.model.wire :as wire]
            [seon.render :as render]
            [seon.render.tails :as rtails]))

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
  (str "You are a Seon Clojure agent in your branch's live Jolt runtime.\n"
       "Relevant code, schemas, docs, tasks, and primers are surfaced dynamically in context.\n"
       "When replying directly to the user (greetings, simple answers, or final summaries), respond in clear plain prose with NO tool calls.\n"
       "Never treat a closed `<agent ...>...</agent>` example block as your own live session."))

(defn format-tool-content
  {:malli/schema [:function [:=> [:cat :any] :string] [:=> [:cat :map :any] :string]], :cost/ticks 200000}
([c] (wire/format-eval-output c))
([opts c]
 (wire/format-eval-output
  (cond-> c
  (:eval-db opts)       (assoc :db (:eval-db opts))
  (:main-db opts)       (assoc :main-db (:main-db opts))
  (:fallback-db opts)   (assoc :fallback-db (:fallback-db opts))
  (:out-max-chars opts) (assoc :out-max-chars (:out-max-chars opts))))))

(defn turns->wire-messages
  {:malli/schema [:=> [:cat :map [:maybe [:sequential :map]]] [:sequential :map]], :cost/ticks 5000000}
  [{:keys [thinking? replay-reasoning?] :or {replay-reasoning? true} :as opts} turns]
  (let [seen-tc-ids (or (:seen-tc-ids opts) (volatile! #{}))
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
     (fn [{:keys [turn messages thought reasoning calls]}]
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
                                :content ((if (fn? (:format-tool-fn opts)) (:format-tool-fn opts) format-tool-content) opts (cond-> c (and (integer? turn) (nil? (:turn c))) (assoc :turn turn)))})
                             real-calls)]
         (concat in-msgs (when asst [asst]) tool-msgs)))
     turns)))
(defn- render-prefix-agents [opts prefix-agents main-db eval-db seen-tc-ids]
  (let [open? (volatile! false)
        msgs (vec
              (mapcat
               (fn [{:keys [id mode goal turns db messages]}]
                 (if (= :continue mode)
                   (when (seq messages)
                     (doseq [m messages tc (:tool_calls m) :when (:id tc)]
                       (vswap! seen-tc-ids conj (str (:id tc))))
                     (vreset! open? true)
                     messages)
                   (let [sid (if (qualified-keyword? id) (str id) (str "example/" (name (or id :unknown))))
                         close-pfx (when @open? (vreset! open? false) "</agent>\n")
                         hdr (str close-pfx "<agent id=\"" sid "\" kind=\"example\" from=\"seon\">\n"
                                  "A finished example agent session shown for reference; not yours.\n"
                                  "Goal: " (or goal "(none)"))
                         ex-db (or db main-db eval-db)
                         ex-opts (cond-> (assoc opts :seen-tc-ids seen-tc-ids)
                                   ex-db (assoc :eval-db ex-db :fallback-db ex-db)
                                   main-db (assoc :main-db main-db))]
                     (concat [{:role "user" :content hdr}]
                             (turns->wire-messages ex-opts turns)
                             [{:role "user" :content "</agent>"}]))))
               prefix-agents))]
    {:msgs msgs :parent-open? @open?}))

(defn build-wire-messages
  "Transforms provider-neutral conversation turns + system prompt into prefix-cache-friendly
   OpenAI-compat / DeepSeek wire messages (`assistant` with `tool_calls` + `reasoning_content`,
   followed by matching `tool` messages, then the current turn's `user` context delta)."
  {:malli/schema [:=> [:cat [:map [:thinking? {:optional true} :boolean] [:replay-reasoning? {:optional true} :boolean]] [:maybe :string] [:vector [:map [:thought {:optional true} :string] [:reasoning {:optional true} :string]]] [:maybe :string]] [:vector :map]], :cost/ticks 5000000}
  [{:keys [thinking? replay-reasoning?] :or {replay-reasoning? true} :as opts} system-prompt history user-prompt]
  (let [sys-msg (when (and system-prompt (not (str/blank? system-prompt)))
                  {:role "system" :content system-prompt})
        hist-msgs (turns->wire-messages opts history)]
    (vec (concat (when sys-msg [sys-msg])
                 hist-msgs
                 [{:role "user" :content (str user-prompt)}]))))

(defn estimate-message-tokens
  "Fast O(chars) token estimate for one OpenAI-compat message (used by Level 3 Debug view)."
  {:malli/schema [:=> [:cat [:map [:content {:optional true} [:or :string [:vector [:map [:type {:optional true} :string] [:text {:optional true} :string]]]]] [:tool_calls {:optional true} [:vector [:map [:function {:optional true} [:map [:arguments {:optional true} :string]]]]]] [:reasoning_content {:optional true} :string]]] [:int {:min 4}]], :cost/ticks 2000000}
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
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean], :cost/ticks 500000}
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

(defn format-viewport-dom
  {:malli/schema [:=> [:cat :any] [:maybe :string]], :cost/ticks 300000}
  [dom]
  (when (map? dom)
    (let [parts (remove nil?
                        [(when (not (str/blank? (str (:viewing dom)))) (str "viewing=" (:viewing dom)))
                         (when (not (str/blank? (str (:mode dom)))) (str "mode=" (:mode dom)))
                         (when (:theme dom) (str "theme=" (:theme dom)))
                         (when (some? (:overflowX dom)) (str "overflowX=" (:overflowX dom)))
                         (when (some? (:svgCount dom)) (str "svgs=" (:svgCount dom)))
                         (when (seq (:svgs dom)) (str "svgBoxes=" (pr-str (:svgs dom))))])]
      (when (seq parts) (str/join " · " parts)))))

(defn build-request-body
  "Constructs the OpenAI-compat / DeepSeek request body using present-only optional params
   (`temperature` omitted unless explicitly set, `stream_options: {:include_usage true}` when streaming)."
  {:malli/schema [:=> [:cat [:map [:model {:optional true} :string] [:max-tokens {:optional true} :int] [:temperature {:optional true} :double] [:thinking {:optional true} [:enum :enabled :disabled]] [:reasoning-effort {:optional true} [:enum :low :medium :high]] [:replay-reasoning {:optional true} :boolean] [:stream? {:optional true} :boolean] [:messages {:optional true} [:vector [:map [:role :string] [:content :string]]]]] [:maybe :string] [:vector [:map [:thought {:optional true} :string]]] [:maybe :string]] :map], :cost/ticks 5000000}
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

(defn build-agent-wire-messages
  "Single source of truth for BOTH live LLM requests AND Level 3 Debug View.
   Produces proper OpenAI-compat / DeepSeek wire messages with:
   1. Byte-identical `system` message (KV prefix cache anchor)
   2. Unified `<agent ...>` prefix blocks (`:mode :example` closed reference sessions, or `:mode :continue` verbatim forked parent prefix)
   3. `<agent id=\"...\" branch=\"...\" from=\"seon\">` (prefixed with `</agent>` when `:forked-from`) + initial rendered walk tiles
   4. Append-only live conversation turns (`user`, `assistant` + `tool_calls`, `tool` with `result/e<n>`)
   5. Optional current turn unread messages + volatility-ordered ephemeral tail blocks + optional viewport screenshot (omitted when `:include-tail? false`).
   Returns `:prefix-hash` (16-char SHA-256 over messages 1..3) for live KV-cache prefix-bust tracking."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [thinking? replay-reasoning? vision?] :or {replay-reasoning? true} :as opts}
   {:keys [agent-id branch role goal system-prompt examples prefix-agents forked-from include-tail? walk-text history unread delta-text activity-text viewport-screenshot tail-blocks ephemeral-image eval-db main-db fallback-db]}]
  (let [sys-msg {:role "system" :content (or (not-empty system-prompt) default-system-prompt)}
        seen-tc-ids (volatile! #{})
        eff-prefixes (or (seq prefix-agents) examples)
        {:keys [msgs parent-open?]} (render-prefix-agents opts eff-prefixes main-db eval-db seen-tc-ids)
        prefix-msgs msgs
        open-msg
        {:role "user"
         :content (str (when parent-open? "</agent>\n")
                       "<agent id=\"" (or agent-id :agent/unknown)
                       "\" branch=\"" (or branch :main)
                       "\" role=\"" (name (or role :worker))
                       (when forked-from (str "\" forked-from=\"" forked-from))
                       "\" from=\"seon\">\n"
                       (if forked-from
                         (str "Your own live session (forked from " forked-from " above).\n")
                         "Your own live session.\n")
                       "Goal: " (or (not-empty goal) "Assist the user and keep the system healthy.")
                       (when (not (str/blank? (str walk-text)))
                         (str "\n\n<walk-context>\n" walk-text "\n</walk-context>")))}
        prefix-hash (wire/prefix-sha256
                     (str/join "\u0000"
                               (map (fn [m]
                                      (str (:role m) ":" (:tool_call_id m) ":" (:reasoning_content m)
                                           ":" (when (:tool_calls m) (pr-str (:tool_calls m))) ":" (:content m)))
                                    (concat [sys-msg] prefix-msgs [open-msg]))))
        hist-msgs (vec (turns->wire-messages (cond-> (assoc opts :seen-tc-ids seen-tc-ids)
                                               eval-db (assoc :eval-db eval-db)
                                               main-db (assoc :main-db main-db)
                                               fallback-db (assoc :fallback-db fallback-db))
                                             history))
        unread-blocks (when (and (seq unread) (not (some #(= "unread-inbox" (:tag %)) tail-blocks)))
                        (when-let [res (rtails/render-inbox-tail {:seon.tail/inbox {:unread unread}})]
                          [(str "<unread-inbox ephemeral=\"true\">\n"
                                (render/hiccup->text (:seon.ui/hiccup res))
                                "\n</unread-inbox>")]))
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
        eval-images (if vision?
                      (wire/extract-recent-eval-images
                       (or fallback-db eval-db main-db) history 4 (remove nil? [eval-db main-db]))
                      [])
        tail-msg (when (not= false include-tail?)
                   (wire/build-ephemeral-tail-msg tail-text eff-img eval-images (boolean vision?)))
        messages (vec (concat [sys-msg] prefix-msgs [open-msg] hist-msgs (when tail-msg [tail-msg])))
        msg-tokens (mapv estimate-message-tokens messages)]
    {:messages messages
     :message-tokens msg-tokens
     :total-tokens (reduce + 0 msg-tokens)
     :prefix-hash prefix-hash
     :prefix-messages-count (dec (count messages))
     :ephemeral-screenshot (or (:raw eff-img) (when (nil? tail-blocks) viewport-screenshot))
     :ephemeral-eval-images eval-images}))