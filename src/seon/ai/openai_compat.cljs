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
   `/chat/completions` itself. [[seon.ai/openai-sdk-base-url]] strips a trailing
   `/chat/completions` (or `/completions`) so BOTH the full-URL form
   and the `/v1` root keep working (the `/v1` root is now preferred).

   API-key resolution (read from `process.env` at CALL TIME — the key
   value itself is never stored in the DB):
     1. the env var NAMED by `:seon.ai/api-key-env`
        (SEON_AI_API_KEY_ENV) when set;
     2. for `:deepseek` only — `DEEPSEEK_API_KEY` (the shipped
        default, pre-#30 behavior preserved);
     3. `SEON_AI_API_KEY` directly (the conventional fallback).

   One agent-facing fn: [[agent-adapter]] consumes the closed
   `:seon.ai/request` map passed by the agent turn's `llm-fn`.

   Call settings (model, temperature, max-tokens, thinking, timeout-ms,
   tools, tool-choice, extra-body) come from one explicit
   `:seon.ai/config-resolution` value captured before the attempt. The
   adapter never rereads mutable config while assembling that request.

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
   [:seon.ai/abort-signal  {:optional true} :seon.ai/abort-signal]
   [:seon.ai/extra-body    {:optional true} :seon.ai/extra-body]
   [:seon.ai/config-resolution
    {:optional true}
    :seon.ai/config-resolution]])

(schema/register!
  :seon.ai.openai-compat/complete-response
  [:map
   [:seon.ai/text                       :string]
   [:seon.ai/error                      {:optional true} :seon.ai/error]
   [:seon.ai.openai-compat/finish-reason {:optional true} :string]
   [:seon.ai/usage                      {:optional true} :seon.ai/usage]
   [:seon.ai/estimated?                 {:optional true} :seon.ai/estimated?]
   [:seon.ai/tool-calls                 {:optional true} :seon.ai/tool-calls]
   [:seon.ai/provider-fields            {:optional true} :seon.ai/provider-fields]
   [:seon.ai/response-model             {:optional true} :seon.ai/response-model]
   [:seon.ai/system-fingerprint         {:optional true} :seon.ai/system-fingerprint]
   [:seon.ai/request-id                 {:optional true} :seon.ai/request-id]
   [:seon.ai/evidence-error             {:optional true} :seon.ai/evidence-error]
   [:seon.ai/config-evidence {:optional true} :seon.ai/config-evidence]])

;; agent-adapter request-option overrides (e.g. {:seon.ai/temperature 0.2}).
(schema/register! :seon.ai.openai-compat/opts :map)

;; Thinking mode (deepseek-v4-pro). The API DEFAULTS TO ENABLED, which
;; is slow — a long-ctx thinking call can blow past the 60s wall-clock
;; timeout (observed 2026-06-10). For :deepseek we send {"thinking":
;; {"type": "disabled"}} unless the config row's :seon.ai/thinking
;; (SEON_AI_THINKING) turns it on: "true" → enabled, "high"/"max" →
;; enabled + that reasoning_effort. For :openai-compat we send ONLY
;; the STANDARD OpenAI param — an effort string ("minimal"…"xhigh")
;; goes out as :reasoning_effort; the vendor :thinking field is NEVER
;; sent (strict gateways — Meta Model API, vLLM — HTTP-400 unknown
;; params; verified live against api.meta.ai 2026-07-10). "true" has
;; no standard wire form on a generic gateway (reasoning models reason
;; by default) → nothing is sent; use an effort string, or
;; :extra-body for a gateway's vendor field.

(defn- openai-compat?
  "Whether a resolved request selects the OpenAI-compatible provider."
  [resolution]
  (= :openai-compat
     (get-in resolution [:seon.ai/resolved-config :seon.ai/provider])))

(defn- resolved-credential
  "Resolve a secret at call time and retain only its non-secret source."
  [resolution]
  (let [config (get resolution :seon.ai/resolved-config)
        compat? (openai-compat? resolution)
        configured-env (:seon.ai/api-key-env config)
        candidates (cond-> []
                     configured-env
                     (conj [configured-env :configured-env])

                     (not compat?)
                     (conj ["DEEPSEEK_API_KEY" :provider-default-env])

                     true
                     (conj ["SEON_AI_API_KEY" :conventional-env]))]
    (some (fn [[env-name class]]
            (when-let [secret (platform/env-val env-name)]
              {::api-key secret
               :seon.ai/credential-source
               {:seon.ai/credential-class class
                :seon.ai/api-key-env env-name}}))
          candidates)))

(defn api-key-configured?
  "Whether a bearer API key resolves for one authority resolution."
  {:malli/schema [:=> [:cat :seon.ai/config-resolution] :boolean]}
  [resolution]
  (boolean (resolved-credential resolution)))

(defn request-params
  "Build the OpenAI chat-completions request PARAMS as a CLJ map.

   The bare keys (:model, :messages, :thinking, …) are the OpenAI/DeepSeek
   API's wire format — a third-party boundary, deliberately
   un-namespaced. NOTE: the `:seon.ai/extra-body` map is NOT inlined
   here — [[complete]] merges it into these params (the SDK's 2nd-arg
   `:body` would REPLACE the body, dropping model/messages).

   Explicit request opts win over the resolved value. We always request a stream (no `:stream false`)
   and ask for usage on the final chunk via
   `:stream_options {:include_usage true}`. `:tools` / `:tool_choice`
   are included ONLY when present (request opt > config row). Public so
   tests and live debugging can inspect exactly what goes over the
   wire."
  {:malli/schema
   [:=> [:catn [:seon.ai.openai-compat/request
                :seon.ai.openai-compat/complete-request]
               [:seon.ai/config-resolution :seon.ai/config-resolution]]
    :map]}
  [{:seon.ai/keys [ctx model temperature max-tokens tools tool-choice] :as request}
   resolution]
  (let [cfg      (:seon.ai/resolved-config resolution)
        thinking (ai/thinking-mode cfg)
        compat?  (openai-compat? resolution)
        temperature* (or temperature (:seon.ai/temperature cfg))
        tools*   (or tools (:seon.ai/tools cfg))
        choice*  (or tool-choice (:seon.ai/tool-choice cfg))]
    (cond->
      {:model          (or model (:seon.ai/model cfg))
       :messages       [{:role "system" :content (ai/effective-system-prompt request)}
                        {:role "user"   :content ctx}]
       :max_tokens     (or max-tokens (:seon.ai/max-tokens cfg))
       :stream_options {:include_usage true}}
      (some? temperature*) (assoc :temperature temperature*)
      ;; :deepseek always sends the vendor :thinking toggle (that API
      ;; defaults to enabled); :openai-compat never sends it — only the
      ;; standard :reasoning_effort (see the thinking-mode note above).
      (not compat?)      (assoc :thinking {:type (if thinking "enabled" "disabled")})
      (string? thinking) (assoc :reasoning_effort thinking)
      (some? tools*)         (assoc :tools tools*)
      (some? choice*)        (assoc :tool_choice choice*))))

(defn- request-extra-body
  "The generic extra request fields for this call — `:seon.ai/extra-body`
   from the request opt (winning), else the already resolved config value.
   nil when neither is set (nothing to merge)."
  [request resolution]
  (or (:seon.ai/extra-body request)
      (:seon.ai/extra-body resolution)))

(def ^:private known-completion-keys
  "Top-level ChatCompletion keys the adapter consumes directly — the
   REMAINDER is preserved as :seon.ai/provider-fields (#25)."
  #{:choices :usage :id :object :created :model :system_fingerprint})

(defn- response-identity-result
  "Retain one bounded identity or return a generic bounded marker."
  [resolution schema-key label value]
  (when (some? value)
    (let [cap (get-in resolution
                      [:seon.ai/resolved-config
                       :seon.config.model-transport/response-identity-cap])]
      (cond
        (nil? cap) nil
        (and (schema/valid-candidate-value? schema-key value)
             (<= (count value) cap)) {::identity-value value}
        :else
        {::identity-error
         (ai/bounded-evidence-error
           (str "Provider response " label " exceeds its evidence bound.")
           cap)}))))

(defn parse-completion
  "Map an assembled OpenAI ChatCompletion OBJECT to a complete-response.

   Post-`.finalChatCompletion` → a
   `:seon.ai.openai-compat/complete-response`.
   `:seon.ai/text` from choices[0].message.content; finish_reason →
   `:seon.ai.openai-compat/finish-reason`; usage set WHEN the provider
   sent it (we request it via `:stream_options {:include_usage true}`,
   but a gateway may omit the usage chunk — optional-is-absent, never
   a present nil); message.tool_calls → `:seon.ai/tool-calls` when
   present; response `model`, `system_fingerprint`, and request `id`
   retain their bounded identities when present; the
   unrecognized top-level fields → `:seon.ai/provider-fields` (omitted
   when empty — optional-is-absent). Public for tests."
  {:malli/schema [:=> [:catn [:seon.ai.openai-compat/completion :any]
                             [:seon.ai/config-resolution
                              :seon.ai/config-resolution]]
                  :seon.ai.openai-compat/complete-response]}
  [completion resolution]
  (let [body       (js->clj completion :keywordize-keys true)
        choice     (-> body :choices first)
        message    (:message choice)
        msg        (:content message)
        reasoning  (:reasoning_content message)
        tool-calls (:tool_calls message)
        model-result
        (response-identity-result resolution :seon.ai/response-model
                                  "model identity" (:model body))
        fingerprint-result
        (response-identity-result resolution :seon.ai/system-fingerprint
                                  "system fingerprint"
                                  (:system_fingerprint body))
        request-id-result
        (response-identity-result resolution :seon.ai/request-id
                                  "request identity" (:id body))
        response-model (::identity-value model-result)
        fingerprint (::identity-value fingerprint-result)
        request-id (::identity-value request-id-result)
        evidence-error (some ::identity-error
                             [model-result fingerprint-result request-id-result])
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
             :seon.ai.openai-compat/finish-reason (:finish_reason choice)}
      (some? (:usage body)) (assoc :seon.ai/usage (:usage body))
      (seq tool-calls)      (assoc :seon.ai/tool-calls tool-calls)
      response-model (assoc :seon.ai/response-model response-model)
      fingerprint (assoc :seon.ai/system-fingerprint fingerprint)
      request-id (assoc :seon.ai/request-id request-id)
      evidence-error (assoc :seon.ai/evidence-error evidence-error)
      (seq extras)          (assoc :seon.ai/provider-fields extras))))

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
;; The captured SDK throwable when the stream failed mid-consume — a
;; third-party boundary value (:any is allowed exactly here).
(schema/register! ::error    :any)
(schema/register! ::stream-result
  [:map [::text ::text] [::aborted? ::aborted?]
   [::error {:optional true} ::error]])

(defn ^:async stream-until-form!
  "Consume the SDK stream, aborting once one top-level form has streamed.

   The repl-mode `:stream` consumer. Per content delta: append to the accumulator, run the cheap
   [[seon.repl.internal/first-top-level-close]] delimiter gate, and — only
   when a top-level group has closed — CONFIRM with the real `parse-forms`
   that a genuine evaluable `:form` is present (a bare `{…}`/`[…]` closes at
   depth 0 but demotes to prose, so keep streaming). On confirm: `.abort()`
   the stream and resolve the `::stream-result` `{::text … ::aborted? true}`
   — the text through the delta that COMPLETED the first form (delta
   granularity: a same-delta tail rides along; parser classification keeps
   non-form remainder as evidence without executing it). On natural end (no form ever
   completes): `{::text <all> ::aborted? false}`. The usage-only final chunk
   (`:stream_options {:include_usage true}`) has no `choices` — guarded.

   NEVER rejects. A transport/SDK failure mid-consume (timeout, connection
   reset — the iterator's `.next` Promise rejects) is returned as the
   `::error` VALUE, converted to the `:seon.ai/error` envelope by
   [[complete]]. This fn is instrumented, and a rejection crossing the
   wrapper records a `:seon.error/fault :core` datom — pod-fatal under the
   dev `:crash` dial — even though [[complete]] catches it one frame up
   (the P4-bench acme pod crashes, 2026-07-10). Errors-as-values at this
   boundary is the root fix, not an exception to the fault net: an
   external provider failure is the caller's expected error, not our bug."
  {:malli/schema [:=> [:cat :any] :any]}
  [^js stream]
  (try
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
                (recur acc')))))))
    (catch :default e
      {::text "" ::aborted? false ::error e})))

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
     :seon.ai/system-prompt — overrides the database-resident soul
     :seon.ai/model         — override config row / default-model
     :seon.ai/temperature   — override config row / default-temperature
     :seon.ai/max-tokens    — override config row / default-max-tokens
     :seon.ai/tools         — OpenAI tool defs (passthrough, off by default)
     :seon.ai/tool-choice   — \"auto\"|\"none\"|\"required\"|{…}
     :seon.ai/extra-body    — generic extra request fields (e.g. Qwen
                              {:chat_template_kwargs {:enable_thinking false}})
     :seon.ai/config-resolution — one caller-captured immutable config value

   Config gaps (no base-url for :openai-compat, no resolvable API key)
   and network/HTTP failures resolve to `{:seon.ai/text \"\"
   :seon.ai/error {…}}` — never a throw to the agent loop. Callers
   destructure both `:seon.ai/text` and `:seon.ai/error`."
  {:malli/schema [:=> [:cat :seon.ai.openai-compat/complete-request]
                  :seon.ai.openai-compat/complete-response]}
  [request]
  (let [resolution (:seon.ai/config-resolution request)
        config     (:seon.ai/resolved-config resolution)
        compat? (openai-compat? resolution)
        label   (if compat? "OpenAI-compat" "DeepSeek")
        url     (some-> (:seon.ai/base-url config) ai/openai-sdk-base-url)
        credential (resolved-credential resolution)
        key     (::api-key credential)
        credential-source (:seon.ai/credential-source credential)
        evidence (when resolution
                   (if credential-source
                     (ai/config-evidence resolution credential-source)
                     (ai/config-evidence resolution)))]
    (cond
      (nil? resolution)
      (config-error
        "OpenAI-compatible"
        "missing :seon.ai/config-resolution — capture one immutable database value and resolve it before calling the provider")

      (nil? url)
      (assoc (config-error
               label
               (str ":openai-compat provider selected but no chat-completions URL "
                    "configured — set SEON_AI_BASE_URL (or transact :seon.ai/base-url "
                    "on the :seon.ai/config row) to the gateway's /v1 root, e.g. "
                    "\"https://gw.example.com/v1\" (the legacy full "
                    "/v1/chat/completions URL is also accepted)"))
             :seon.ai/config-evidence evidence)

      (nil? key)
      (assoc (config-error
               label
               (str label " API key not found in process.env — "
                    (if compat?
                      (str "set SEON_AI_API_KEY, or point :seon.ai/api-key-env "
                           "(SEON_AI_API_KEY_ENV) at the name of the env var "
                           "holding the gateway's bearer key")
                      (str "set DEEPSEEK_API_KEY (or SEON_AI_API_KEY, or "
                           ":seon.ai/api-key-env / SEON_AI_API_KEY_ENV)"))))
             :seon.ai/config-evidence evidence)

      :else
      ;; The WHOLE build+call rides inside the try — the params build reads
      ;; config-provided data (the config row, extra-body EDN merged into the
      ;; params, make-client), so a throw there is an EXPECTED error and must
      ;; resolve to an envelope, never reject: the instrument wrapper records
      ;; a rejection as a :core fault (crashes the dev pod). Same class as the
      ;; stream-until-form! fix (e6295ecd) and the anthropic fix (06615941).
      (try
        (let [ms      (:seon.ai/timeout-ms config)
              ^js client (make-client url key ms)
              extra   (request-extra-body request resolution)
              ;; :extra-body is MERGED into the request PARAMS (1st arg).
              ;; openai-node passes unknown top-level params through
              ;; verbatim. The 2nd-arg RequestOptions :body REPLACES the
              ;; body (does NOT merge) — using it dropped model/messages
              ;; and 400'd every extra-body call (verified live).
              params  (clj->js (cond-> (request-params request resolution)
                                 (seq extra) (merge extra)))
              ^js completions (.. client -chat -completions)
              stream?  (boolean (:seon.ai/stream? request))
              signal   (:seon.ai/abort-signal request)
              ^js stream (if signal
                           (.stream completions params #js{:signal signal})
                           (.stream completions params))]
          (if stream?
            ;; repl-mode :stream — consume deltas, abort at the first
            ;; complete top-level form (one form per turn).
            (let [{::keys [text aborted? error]} (await (stream-until-form! stream))]
              (cond
                ;; The consumer captured an SDK failure as a VALUE (it
                ;; never rejects — see its docstring); re-raise into
                ;; THIS fn's catch, the one error->envelope site.
                (some? error) (throw error)

                aborted?
                {:seon.ai/text                        text
                 :seon.ai.openai-compat/finish-reason "abort"
                 :seon.ai/usage                       (estimated-usage request text)
                 :seon.ai/estimated?                  true
                 :seon.ai/config-evidence             evidence}

                ;; Natural end before any form completed — fall back to
                ;; the assembled completion for real usage + full text.
                :else
                (assoc (parse-completion (await (.finalChatCompletion stream))
                                         resolution)
                       :seon.ai/config-evidence evidence)))
            ;; repl-mode :batch — buffer to the assembled completion.
            (let [completion (await (.finalChatCompletion stream))
                  result     (assoc (parse-completion completion resolution)
                                    :seon.ai/config-evidence evidence)]
              (when-let [err (:seon.ai/error result)]
                (ai/log-error! label err))
              result)))
        (catch :default e
          (let [err (error->envelope label e)]
            (ai/log-error! label err)
            {:seon.ai/text  ""
             :seon.ai/error err
             :seon.ai/config-evidence evidence}))))))

;; ============================================================
;; Adapter for seon.agent.
;;
;; The agent turn expects a Promise of `{:text "..."}` from one closed
;; `:seon.ai/request` map. complete returns namespaced keys; this bridges the
;; response shapes.
;; ============================================================

(defn ^:async ^:private complete+wrap
  "Internal — call complete with merged opts, wrap response into the
   shape the turn loop expects. On failure `:seon.ai/error` is lifted to the TOP
   level (alongside `:text`) so the turn loop can surface it without
   digging into `:seon.ai/raw`."
  [opts request]
  (let [ctx-text (:seon.ai/ctx request)
        stream?  (:seon.ai/stream? request)
        signal   (:seon.ai/abort-signal request)
        system-prompt (:seon.ai/system-prompt request)
        resolution (:seon.ai/config-resolution request)
        resp (await (complete (cond-> (assoc opts :seon.ai/ctx ctx-text)
                                stream? (assoc :seon.ai/stream? true)
                                signal (assoc :seon.ai/abort-signal signal)
                                system-prompt
                                (assoc :seon.ai/system-prompt system-prompt)
                                resolution (assoc :seon.ai/config-resolution resolution))))]
    (cond-> {:text        (:seon.ai/text resp)
             :seon.ai/raw resp}
      (:seon.ai/error resp) (assoc :seon.ai/error (:seon.ai/error resp)))))

(defn agent-adapter
  "A request function suitable for the agent turn's `llm-fn`.

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
   (fn [request] (complete+wrap opts request))))
