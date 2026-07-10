(ns seon.ai.openai-compat
  "OpenAI-compatible chat-completions client on the official `openai`
   Node SDK (SDK migration, 2026-06-16). ^:async — returns Promises.

   ONE request path, two providers (no fork — task #30):

     - `:deepseek` (the default) — the shipped endpoint
       (https://api.deepseek.com/v1 root) and the `DEEPSEEK_API_KEY`
       env default. The DeepSeek wire format IS the OpenAI format.
     - `:openai-compat` — any OpenAI-compatible gateway (vLLM/SGLang,
       enterprise bearer-keyed proxies, …). The endpoint is the config
       row's `:seon.ai/base-url` (SEON_AI_BASE_URL). No shipped default
       — `:openai-compat` selected with no base-url is a legible error
       envelope at call time, never a throw.

   baseURL reconciliation: `SEON_AI_BASE_URL` was historically the FULL
   chat-completions URL; the SDK wants the `/v1` ROOT and appends
   `/chat/completions` itself. [[sdk-base-url]] strips a trailing
   `/chat/completions` (or `/completions`) so BOTH the full-URL form
   and the `/v1` root keep working (the `/v1` root is now preferred).

   API-key resolution (read from `process.env` at CALL TIME — the key
   value itself is never stored in the DB):
     1. the env var NAMED by `:seon.ai/api-key-env`
        (SEON_AI_API_KEY_ENV) when set;
     2. for `:deepseek` only — `DEEPSEEK_API_KEY` (the shipped
        default, pre-#30 behavior preserved);
     3. `SEON_AI_API_KEY` directly (the conventional fallback).

   One agent-facing fn: [[agent-adapter]] returns `(fn [ctx-string])`
   compatible with `seon.agent/run-turn-once!`'s `llm-fn`.

   Call settings (model, temperature, max-tokens, thinking,
   timeout-ms, tools, tool-choice, extra-body) come from the
   `:seon.ai/config` row, read PER CALL via `seon.ai/current` (C-18 —
   env-seeded via SEON_AI_*, runtime-tunable by transact). Precedence:
   explicit request opt > config row > the shipped defaults below.

   The `system` role message is the HARDCODED system-specific seon
   mechanics (`seon.agent.ctx/system-text` via
   `seon.ai/effective-system-prompt`), byte-stable for every agent and
   turn — NO file read, NO fallback. The agent's IDENTITY (SOUL.md /
   AGENTS.md) rides the user-message CONTEXT as file-sections
   (`seon.agent.ctx/file-block`), decoupled from the system message.

   Streaming: we ASK for a stream (`.stream` + `.finalChatCompletion`)
   and the SDK buffers it into one assembled ChatCompletion object —
   the agent loop parses complete Clojure forms downstream, so this is
   a transport/robustness change, not consumer streaming. Native tool
   calling and a generic `:extra-body` (e.g. Qwen's
   `chat_template_kwargs`) ride through when the caller opts in;
   default off → byte-equivalent behavior to the pre-SDK adapter."
  (:require [clojure.string :as str]
            ["openai" :as OpenAI]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.error :as error]
            [seon.platform :as platform]
            [seon.repl.internal :as repl-internal]
            [seon.schema :as schema]))

;; ============================================================
;; Schemas — request + response shapes. The shared :seon.ai/* field
;; vocabulary (text, model, error envelope, tools, …) lives in seon.ai.
;; ============================================================

(schema/register!
  :seon.ai.openai-compat/complete-request
  [:map
   [:seon.ai/ctx           :seon.ai/ctx]
   [:seon.ai/system-prompt {:optional true} :seon.ai/system-prompt]
   [:seon.ai/model         {:optional true} :seon.ai/model]
   [:seon.ai/temperature   {:optional true} :seon.ai/temperature]
   [:seon.ai/max-tokens    {:optional true} :seon.ai/max-tokens]
   [:seon.ai/tools         {:optional true} :seon.ai/tools]
   [:seon.ai/tool-choice   {:optional true} :seon.ai/tool-choice]
   [:seon.ai/stream?       {:optional true} :seon.ai/stream?]
   [:seon.ai/extra-body    {:optional true} :seon.ai/extra-body]])

(schema/register!
  :seon.ai.openai-compat/complete-response
  [:map
   [:seon.ai/text                       :string]
   [:seon.ai/error                      {:optional true} :seon.ai/error]
   [:seon.ai.openai-compat/finish-reason {:optional true} :string]
   [:seon.ai/usage                      {:optional true} :seon.ai/usage]
   [:seon.ai/estimated?                 {:optional true} :seon.ai/estimated?]
   [:seon.ai/tool-calls                 {:optional true} :seon.ai/tool-calls]
   [:seon.ai/provider-fields            {:optional true} :seon.ai/provider-fields]])

;; agent-adapter request-option overrides (e.g. {:seon.ai/temperature 0.2}).
(schema/register! :seon.ai.openai-compat/opts :map)

;; ============================================================
;; Config — the shipped defaults. The :seon.ai/config row (read per
;; call) overrides these; explicit request opts override the row.
;; ============================================================

;; Model/temperature/max-tokens defaults live in the ONE per-provider map
;; `seon.ai/shipped-defaults` (:deepseek entry — :openai-compat shares
;; this wire path and the same fallbacks), so the queryable resolver
;; (`seon.ai/resolved-config`) reports exactly what this adapter falls
;; back to. Endpoint + timeout stay adapter-private.
(def ^:private defaults            (:deepseek ai/shipped-defaults))
(def ^:private default-model       (:seon.ai/model defaults))
;; The /v1 ROOT (not the full chat-completions URL) — the SDK appends
;; /chat/completions itself.
(def ^:private default-endpoint    "https://api.deepseek.com/v1")
(def ^:private default-temperature (:seon.ai/temperature defaults))
(def ^:private default-max-tokens  (:seon.ai/max-tokens defaults))
;; Wall-clock timeout for the HTTP call. A hung API stops wedging the
;; agent loop — turn fails with a timeout error and the next user
;; message kicks again. Override via the config row's
;; :seon.ai/timeout-ms (SEON_AI_TIMEOUT_MS).
(def ^:private default-timeout-ms  60000)

;; Thinking mode (deepseek-v4-pro). The API DEFAULTS TO ENABLED, which
;; is slow — a long-ctx thinking call can blow past the 60s wall-clock
;; timeout (observed 2026-06-10). For :deepseek we send {"thinking":
;; {"type": "disabled"}} unless the config row's :seon.ai/thinking
;; (SEON_AI_THINKING) turns it on: "true" → enabled, "high"/"max" →
;; enabled + that reasoning_effort. For :openai-compat the thinking
;; field is sent ONLY when set truthy — absent/"false" sends NOTHING
;; (graceful no-op on gateways that don't know the field).

(defn- openai-compat?
  "Is this pod's active provider :openai-compat? Read per call
   (reactive-context) — the SAME request path serves both providers."
  []
  (= :openai-compat (ai/provider)))

(defn- endpoint
  "The base endpoint for this call: the shipped deepseek /v1 root for
   :deepseek; the config row's :seon.ai/base-url for :openai-compat,
   with the SEON_AI_BASE_URL env as the pre-sync fallback. nil =
   :openai-compat selected but unconfigured ([[complete]] returns a
   legible error envelope). The value may be the /v1 root OR the legacy
   full chat-completions URL — [[sdk-base-url]] reconciles both."
  []
  (if (openai-compat?)
    (or (:seon.ai/base-url (ai/current))
        (:seon.ai/base-url (ai/env-row)))
    default-endpoint))

(defn- sdk-base-url
  "The `/v1` ROOT the SDK appends `/chat/completions` to, derived from
   [[endpoint]]. Strips a trailing `/chat/completions` or `/completions`
   off the legacy full-URL form; a value already at the root is used
   as-is. nil when [[endpoint]] is nil (:openai-compat unconfigured)."
  []
  (when-let [url (endpoint)]
    (cond
      (str/ends-with? url "/chat/completions")
      (subs url 0 (- (count url) (count "/chat/completions")))

      (str/ends-with? url "/completions")
      (subs url 0 (- (count url) (count "/completions")))

      :else url)))

(defn- resolved-api-key
  "The bearer key for this call, or nil — see the ns doc for the
   resolution order. Read from process.env at call time; the key value
   is never transacted."
  []
  (let [key-env (or (:seon.ai/api-key-env (ai/current))
                    (:seon.ai/api-key-env (ai/env-row)))]
    (or (some-> key-env platform/env-val)
        (when-not (openai-compat?) (platform/env-val "DEEPSEEK_API_KEY"))
        (platform/env-val "SEON_AI_API_KEY"))))

(defn api-key-configured?
  "Whether a bearer API key resolves for the ACTIVE provider.

   See the ns doc's resolution order. `seon.client/current-llm-fn` uses this
   to fall back to the stub llm-fn when no key is available."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (boolean (resolved-api-key)))

(defn request-params
  "Build the OpenAI chat-completions request PARAMS as a CLJ map.

   The bare keys (:model, :messages, :thinking, …) are the OpenAI/DeepSeek
   API's wire format — a third-party boundary, deliberately
   un-namespaced. NOTE: the `:seon.ai/extra-body` map is NOT inlined
   here — [[complete]] merges it into these params (the SDK's 2nd-arg
   `:body` would REPLACE the body, dropping model/messages).

   Reads the `:seon.ai/config` row PER CALL (`seon.ai/current`);
   explicit request opts win over the row, the row wins over the
   shipped defaults. We always request a stream (no `:stream false`)
   and ask for usage on the final chunk via
   `:stream_options {:include_usage true}`. `:tools` / `:tool_choice`
   are included ONLY when present (request opt > config row). Public so
   tests and live debugging can inspect exactly what goes over the
   wire."
  {:malli/schema [:=> [:cat :seon.ai.openai-compat/complete-request] :map]}
  [{:seon.ai/keys [ctx model temperature max-tokens tools tool-choice] :as request}]
  (let [cfg      (ai/current)
        thinking (ai/thinking-mode cfg)
        compat?  (openai-compat?)
        tools*   (or tools (:seon.ai/tools cfg))
        choice*  (or tool-choice (:seon.ai/tool-choice cfg))]
    (cond->
      {:model          (or model (:seon.ai/model cfg) default-model)
       :messages       [{:role "system" :content (ai/effective-system-prompt request)}
                        {:role "user"   :content ctx}]
       :temperature    (or temperature (:seon.ai/temperature cfg) default-temperature)
       :max_tokens     (or max-tokens (:seon.ai/max-tokens cfg) default-max-tokens)
       :stream_options {:include_usage true}}
      ;; :deepseek always sends the explicit toggle (the API defaults
      ;; to enabled); :openai-compat sends the field ONLY when truthy.
      (not compat?)          (assoc :thinking {:type (if thinking "enabled" "disabled")})
      (and compat? thinking) (assoc :thinking {:type "enabled"})
      (string? thinking)     (assoc :reasoning_effort thinking)
      (some? tools*)         (assoc :tools tools*)
      (some? choice*)        (assoc :tool_choice choice*))))

(defn- request-extra-body
  "The generic extra request fields for this call — `:seon.ai/extra-body`
   from the request opt (winning), else the config row's data-only door
   (`seon.ai/config-extra-body` — env SEON_AI_EXTRA_BODY / the row's
   ::extra-body-edn, the only path that reaches the agent turn loop). nil
   when neither set (nothing to merge)."
  [request]
  (or (:seon.ai/extra-body request)
      (not-empty (ai/config-extra-body))))

(def ^:private known-completion-keys
  "Top-level ChatCompletion keys the adapter consumes directly — the
   REMAINDER is preserved as :seon.ai/provider-fields (#25)."
  #{:choices :usage :id :object :created :model :system_fingerprint})

(defn parse-completion
  "Map an assembled OpenAI ChatCompletion OBJECT to a complete-response.

   Post-`.finalChatCompletion` → a
   `:seon.ai.openai-compat/complete-response`.
   `:seon.ai/text` from choices[0].message.content; finish_reason →
   `:seon.ai.openai-compat/finish-reason`; usage ALWAYS set;
   message.tool_calls → `:seon.ai/tool-calls` when present; the
   unrecognized top-level fields → `:seon.ai/provider-fields` (omitted
   when empty — optional-is-absent). Public for tests."
  {:malli/schema [:=> [:catn [:seon.ai.openai-compat/completion :any]]
                  :seon.ai.openai-compat/complete-response]}
  [completion]
  (let [body       (js->clj completion :keywordize-keys true)
        choice     (-> body :choices first)
        message    (:message choice)
        msg        (:content message)
        reasoning  (:reasoning_content message)
        tool-calls (:tool_calls message)
        extras     (apply dissoc body known-completion-keys)]
    ;; Diagnosis evidence, not behavior (downstream ask 20): a
    ;; thinking-mode completion can land EVERY token in
    ;; reasoning_content and return empty visible content — the
    ;; turn-outcome guard handles the re-prompt; here we just log that
    ;; the reasoning field was present and dropped.
    (when (and (zero? (count (or msg "")))
               (pos? (count (or reasoning ""))))
      (js/console.debug
        (str "seon.ai.openai-compat: completion content EMPTY but"
             " reasoning_content present (~" (tokens/estimate reasoning)
             " tokens) — thinking-mode tokens landed in the reasoning"
             " field; dropping it (parsed as before)")))
    (cond-> {:seon.ai/text                        (or msg "")
             :seon.ai.openai-compat/finish-reason (:finish_reason choice)
             :seon.ai/usage                       (:usage body)}
      (seq tool-calls) (assoc :seon.ai/tool-calls tool-calls)
      (seq extras)     (assoc :seon.ai/provider-fields extras))))

(defn- config-error
  "Errors-as-values envelope for a CALL-TIME config gap (no endpoint /
   no key). Never transport-flagged — a config error must not look
   retryable. Logged loudly, returned as a value."
  [label msg]
  (let [err {:seon.ai/msg msg}]
    (ai/log-error! label err)
    {:seon.ai/text "" :seon.ai/error err}))

(defn- error->envelope
  "Map an SDK error (or any throwable) onto the `:seon.ai/error`
   envelope. Branch order matters: APIConnectionTimeoutError /
   APIUserAbortError are SUBCLASSES of APIConnectionError which is a
   subclass of APIError — so check timeout/abort FIRST, connection
   (transport) SECOND, the generic APIError (carries .status) THIRD,
   and a plain message LAST (parse/unknown — no flags)."
  [label e]
  (cond
    (or (instance? (.-APIConnectionTimeoutError OpenAI) e)
        (instance? (.-APIUserAbortError OpenAI) e))
    {:seon.ai/msg      (str label " request timed out / aborted: "
                           (error/->message e))
     :seon.ai/timeout? true}

    (instance? (.-APIConnectionError OpenAI) e)
    {:seon.ai/msg        (str label " connection failed: " (error/->message e))
     :seon.ai/transport? true}

    (instance? (.-APIError OpenAI) e)
    (let [ra (ai/error-retry-after-ms e)]
      (cond-> {:seon.ai/msg    (str label " HTTP " (.-status e) ": " (error/->message e))
               :seon.ai/status (.-status e)}
        ra (assoc :seon.ai/retry-after-ms ra)))

    :else
    {:seon.ai/msg (str label " call failed: " (error/->message e))}))

(def ^:dynamic *fetch*
  "Test seam ONLY. When bound to a fetch fn, [[make-client]] hands it to
   the SDK as the `:fetch` option so wire/error tests can drive the SDK
   without a network. nil (the default) → the SDK uses Node's native
   fetch. A dynamic var (not a `with-redefs` on a private fn) because
   CLJS compiles intra-namespace `defn-` calls as direct references that
   `with-redefs` cannot intercept."
  nil)

(defn- make-client
  "Construct the `openai` SDK client. `maxRetries 0` — the agent loop is
   the single retry authority. Injects [[*fetch*]] when bound (tests)."
  [base-url key ms]
  (new (.-OpenAI OpenAI)
       (cond-> #js{:baseURL    base-url
                   :apiKey     key
                   :timeout    ms
                   :maxRetries 0}
         *fetch* (doto (aset "fetch" *fetch*)))))

(schema/register! ::text     :string)
(schema/register! ::aborted? :boolean)
(schema/register! ::stream-result
  [:map [::text ::text] [::aborted? ::aborted?]])

(defn ^:async stream-until-form!
  "Consume the SDK stream, aborting once one top-level form has streamed.

   The repl-mode `:stream` consumer. Per content delta: append to the accumulator, run the cheap
   [[seon.repl.internal/first-top-level-close]] delimiter gate, and — only
   when a top-level group has closed — CONFIRM with the real `parse-forms`
   that a genuine evaluable `:form` is present (a bare `{…}`/`[…]` closes at
   depth 0 but demotes to prose, so keep streaming). On confirm: `.abort()`
   the stream and resolve the `::stream-result` `{::text … ::aborted? true}`
   — the text through the delta that COMPLETED the first form (delta
   granularity: a same-delta tail rides along; Mode A's reply-boundary strip
   still cleans any fabricated remainder). On natural end (no form ever
   completes): `{::text <all> ::aborted? false}`. The usage-only final chunk
   (`:stream_options {:include_usage true}`) has no `choices` — guarded."
  {:malli/schema [:=> [:cat :any] :any]}
  [^js stream]
  (let [it (js-invoke stream js/Symbol.asyncIterator)]
    (loop [acc ""]
      (let [step (await (.next it))]
        (if (.-done step)
          {::text acc ::aborted? false}
          (let [^js chunk (.-value step)
                choices   (.-choices chunk)
                ^js choice (when (and choices (pos? (.-length choices)))
                             (aget choices 0))
                ^js delta  (some-> choice .-delta)
                piece      (some-> delta .-content)
                acc'    (if piece (str acc piece) acc)]
            (if (and piece
                     (repl-internal/first-top-level-close acc')
                     (some #(= :form (:seon.repl/kind %))
                           (repl-internal/parse-forms acc')))
              (do (.abort stream) {::text acc' ::aborted? true})
              (recur acc'))))))))

(defn- estimated-usage
  "A CLIENT-SIDE usage map for an ABORTED stream (the provider's final usage
   chunk is lost on abort). Prompt tokens = estimate over the system +
   user content actually sent; completion tokens = estimate over the text
   streamed through the first form. DeepSeek-shaped keys so the turn's
   `llm-usage` projection is uniform; the response also flags
   `:seon.ai/estimated? true` so the numbers are never mistaken for
   provider-reported."
  [request text]
  (let [p (+ (tokens/estimate (ai/effective-system-prompt request))
             (tokens/estimate (str (:seon.ai/ctx request))))
        c (tokens/estimate (str text))]
    {:prompt_tokens p :completion_tokens c :total_tokens (+ p c)}))

(defn ^:async complete
  "Send a chat-completions request to the active provider's endpoint.

   See the ns doc (:deepseek default or :openai-compat gateway); via
   the official `openai` SDK (streamed + buffered). Returns a Promise
   of a `:seon.ai.openai-compat/complete-response` map.

   Request opts (only :seon.ai/ctx required):
     :seon.ai/ctx           — the full ctx text (required)
     :seon.ai/system-prompt — overrides the store-resident soul
     :seon.ai/model         — override config row / default-model
     :seon.ai/temperature   — override config row / default-temperature
     :seon.ai/max-tokens    — override config row / default-max-tokens
     :seon.ai/tools         — OpenAI tool defs (passthrough, off by default)
     :seon.ai/tool-choice   — \"auto\"|\"none\"|\"required\"|{…}
     :seon.ai/extra-body    — generic extra request fields (e.g. Qwen
                              {:chat_template_kwargs {:enable_thinking false}})

   Config gaps (no base-url for :openai-compat, no resolvable API key)
   and network/HTTP failures resolve to `{:seon.ai/text \"\"
   :seon.ai/error {…}}` — never a throw to the agent loop. Callers
   destructure both `:seon.ai/text` and `:seon.ai/error`."
  {:malli/schema [:=> [:cat :seon.ai.openai-compat/complete-request]
                  :seon.ai.openai-compat/complete-response]}
  [request]
  (let [compat? (openai-compat?)
        label   (if compat? "OpenAI-compat" "DeepSeek")
        url     (sdk-base-url)
        key     (resolved-api-key)]
    (cond
      (nil? url)
      (config-error
        label
        (str ":openai-compat provider selected but no chat-completions URL "
             "configured — set SEON_AI_BASE_URL (or transact :seon.ai/base-url "
             "on the :seon.ai/config row) to the gateway's /v1 root, e.g. "
             "\"https://gw.example.com/v1\" (the legacy full "
             "/v1/chat/completions URL is also accepted)"))

      (nil? key)
      (config-error
        label
        (str label " API key not found in process.env — "
             (if compat?
               (str "set SEON_AI_API_KEY, or point :seon.ai/api-key-env "
                    "(SEON_AI_API_KEY_ENV) at the name of the env var "
                    "holding the gateway's bearer key")
               (str "set DEEPSEEK_API_KEY (or SEON_AI_API_KEY, or "
                    ":seon.ai/api-key-env / SEON_AI_API_KEY_ENV)"))))

      :else
      (let [ms      (or (:seon.ai/timeout-ms (ai/current)) default-timeout-ms)
            ^js client (make-client url key ms)
            extra   (request-extra-body request)
            ;; :extra-body is MERGED into the request PARAMS (1st arg).
            ;; openai-node passes unknown top-level params through
            ;; verbatim. The 2nd-arg RequestOptions :body REPLACES the
            ;; body (does NOT merge) — using it dropped model/messages
            ;; and 400'd every extra-body call (verified live).
            params  (clj->js (cond-> (request-params request)
                               (seq extra) (merge extra)))
            ^js completions (.. client -chat -completions)
            stream?  (boolean (:seon.ai/stream? request))]
        (try
          (let [^js stream (.stream completions params)]
            (if stream?
              ;; repl-mode :stream — consume deltas, abort at the first
              ;; complete top-level form (one form per turn).
              (let [{::keys [text aborted?]} (await (stream-until-form! stream))]
                (if aborted?
                  {:seon.ai/text                        text
                   :seon.ai.openai-compat/finish-reason "abort"
                   :seon.ai/usage                       (estimated-usage request text)
                   :seon.ai/estimated?                  true}
                  ;; Natural end before any form completed — fall back to
                  ;; the assembled completion for real usage + full text.
                  (parse-completion (await (.finalChatCompletion stream)))))
              ;; repl-mode :batch — buffer to the assembled completion.
              (let [completion (await (.finalChatCompletion stream))
                    result     (parse-completion completion)]
                (when-let [err (:seon.ai/error result)]
                  (ai/log-error! label err))
                result)))
          (catch :default e
            (let [err (error->envelope label e)]
              (ai/log-error! label err)
              {:seon.ai/text  ""
               :seon.ai/error err})))))))

;; ============================================================
;; Adapter for seon.agent.
;;
;; seon.agent/run-turn-once! expects (fn [ctx-string]) → Promise of
;; `{:text "..."}`. complete takes a request map and returns a Promise
;; of namespaced keys. This bridges the two.
;; ============================================================

(defn ^:async ^:private complete+wrap
  "Internal — call complete with merged opts, wrap response into the
   shape the turn loop expects. `arg` is EITHER a bare ctx string
   (back-compat) OR a request map carrying `:seon.ai/ctx` +
   `:seon.ai/stream?` (the widened shape the turn loop passes for
   repl-mode `:stream`). On failure `:seon.ai/error` is lifted to the TOP
   level (alongside `:text`) so the turn loop can surface it without
   digging into `:seon.ai/raw`."
  [opts arg]
  (let [ctx-text (ai/llm-arg->ctx arg)
        stream?  (ai/llm-arg->stream? arg)
        resp (await (complete (cond-> (assoc opts :seon.ai/ctx ctx-text)
                                stream? (assoc :seon.ai/stream? true))))]
    (cond-> {:text        (:seon.ai/text resp)
             :seon.ai/raw resp}
      (:seon.ai/error resp) (assoc :seon.ai/error (:seon.ai/error resp)))))

(defn agent-adapter
  "A fn-of-ctx-string suitable for `seon.agent/run-turn-once!`'s `llm-fn`.

   Optional `opts` override
   request defaults (e.g. `{:seon.ai/temperature 0.2}`). The returned
   fn calls `complete` ^:async-internally and returns a Promise of
   `{:text \"…\" :seon.ai/raw <full response>}` — plus a top-level
   `:seon.ai/error` (see the `:seon.ai/error` schema) when the call
   failed (timeout, connection error, HTTP error, parse error)."
  {:malli/schema
   [:function
    [:=> [:cat] :any]
    [:=> [:catn [::opts ::opts]] :any]]}
  ([] (agent-adapter {}))
  ([opts]
   (fn [arg] (complete+wrap opts arg))))
