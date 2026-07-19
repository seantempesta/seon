(ns seon.ai.anthropic
  "Adapt Anthropic Messages to Seon's provider request contract.

   This async adapter uses the official `@anthropic-ai/sdk` Node SDK. It
   returns Promises. Same agent-adapter contract as
   [[seon.ai.openai-compat]]: one agent-facing fn, [[agent-adapter]],
   consumes the closed `:seon.ai/request` map passed by the agent turn's
   `llm-fn`. Reads the API key from
   `ANTHROPIC_API_KEY` in `process.env`. The SDK owns streaming
   (`.stream` + `.finalMessage`, buffered to one assembled Message) and
   sets the `anthropic-version` header itself.

   Call settings come from one explicit `:seon.ai/config-resolution`
   captured before the attempt. Explicit request opts win over that
   immutable resolved value.

   API specifics PINNED from the API reference 2026-06-11 (exact
   strings — do NOT append date suffixes):
     - default model \"claude-opus-4-8\"; also valid:
       \"claude-sonnet-4-6\", \"claude-haiku-4-5\", \"claude-fable-5\"
     - POST https://api.anthropic.com/v1/messages (the SDK owns the URL
       + the `x-api-key` / `anthropic-version` headers)
     - thinking is ADAPTIVE-ONLY on Opus 4.7+/Fable: config thinking
       truthy → {:thinking {:type \"adaptive\"}}; falsy → OMIT the key
       entirely (an explicit {:type \"disabled\"} 400s on Fable)
     - sampling params (temperature/top_p/top_k) are REMOVED on Opus
       4.7+/Fable and 400 — this adapter NEVER sends them (the config
       row's :seon.ai/temperature is deepseek-only)
     - no assistant prefill; system prompt is top-level :system, not a
       messages entry; max_tokens is REQUIRED
     - response :content is an ARRAY of typed blocks — extract \"text\"
       blocks, skip \"thinking\" blocks; check :stop_reason BEFORE
       reading content (Fable adds \"refusal\": empty content array,
       surfaced as a legible :seon.ai/error envelope)
     - PROMPT CACHING (task #34, 2026-06-12 — the system-block-only
       breakpoint covered just ~5.4k of ~38k input tokens because the
       ENTIRE assembled ctx rode as one user message AFTER the only
       breakpoint): the ctx string carries seon.agent.ctx's in-band
       [[seon.agent.ctx/stable-boundary]]; [[seon.agent.ctx/split-context]]
       recovers the STABLE prefix (sections through :namespaces —
       byte-stable within a session) and the VOLATILE tail. :system
       becomes TWO content blocks, each with cache_control {:type
       \"ephemeral\"} — [core soul block] [stable-ctx block] — and
       :messages carries ONLY the volatile tail (2 of the 4 allowed
       breakpoints; tools→system→messages render order means the
       second breakpoint caches everything before it). A ctx WITHOUT
       the boundary (tests, stub prompts) degrades to the pre-split
       shape: one system block, full ctx as the user message.
       Caveats: prefixes under the model's minimum (4096 tokens on
       Opus 4.x) silently don't cache; verify with usage
       :cache_read_input_tokens on call 2."
  (:require [clojure.string :as str]
            ["@anthropic-ai/sdk" :as Anthropic]
            [seon.ai :as ai]
            [seon.agent.ctx :as ctx]
            [seon.error :as error]
            [seon.platform :as platform]
            [seon.schema :as schema]))

;; ============================================================
;; Schemas — request + response shapes. The shared :seon.ai/* field
;; vocabulary lives in seon.ai. NOTE: no :seon.ai/temperature slot —
;; sampling params 400 on Opus 4.7+/Fable.
;; ============================================================

(schema/register!
  :seon.ai.anthropic/complete-request
  [:map
   [:seon.ai/ctx           :seon.ai/ctx]
   [:seon.ai/system-prompt {:optional true} :seon.ai/system-prompt]
   [:seon.ai/model         {:optional true} :seon.ai/model]
   [:seon.ai/max-tokens    {:optional true} :seon.ai/max-tokens]
   [:seon.ai/tools         {:optional true} :seon.ai/tools]
   [:seon.ai/tool-choice   {:optional true} :seon.ai/tool-choice]
   [:seon.ai/abort-signal  {:optional true} :seon.ai/abort-signal]
   [:seon.ai/extra-body    {:optional true} :seon.ai/extra-body]
   [:seon.ai/config-resolution
    {:optional true}
    :seon.ai/config-resolution]])

(schema/register! :seon.ai.anthropic/stop-reason :string)

;; agent-adapter request-option overrides (e.g. {:seon.ai/max-tokens 2048}).
(schema/register! :seon.ai.anthropic/opts :map)

(schema/register!
  :seon.ai.anthropic/complete-response
  [:map
   [:seon.ai/text                   :string]
   [:seon.ai/error                  {:optional true} :seon.ai/error]
   [:seon.ai.anthropic/stop-reason  {:optional true} :seon.ai.anthropic/stop-reason]
   [:seon.ai/usage                  {:optional true} :seon.ai/usage]
   [:seon.ai/tool-calls             {:optional true} :seon.ai/tool-calls]
   [:seon.ai/provider-fields        {:optional true} :seon.ai/provider-fields]
   [:seon.ai/config-evidence        {:optional true} :seon.ai/config-evidence]])

(defn- resolved-credential
  "Resolve a secret at call time and retain only its non-secret source."
  [resolution]
  (let [configured-env (get-in resolution
                               [:seon.ai/resolved-config
                                :seon.ai/api-key-env])
        candidates (cond-> []
                     configured-env
                     (conj [configured-env :configured-env])
                     true
                     (conj ["ANTHROPIC_API_KEY" :provider-default-env]
                           ["SEON_AI_API_KEY" :conventional-env]))]
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

(defn- config-error
  "Errors-as-values envelope for a CALL-TIME config gap (no API key).
   Never transport-flagged — a config error must not look retryable.
   Logged loudly, returned as a value."
  [msg]
  (let [err {:seon.ai/msg msg}]
    (ai/log-error! "Anthropic" err)
    {:seon.ai/text "" :seon.ai/error err}))

;; ============================================================
;; Request body.
;; ============================================================

(defn request-params
  "Build the Anthropic Messages API request PARAMS as a CLJ map.

   The bare keys (:model, :messages, :system, …) are the API's wire format
   — a third-party boundary, deliberately un-namespaced. NOTE: the
   `:seon.ai/extra-body` map is NOT inlined here — [[complete]] merges
   it into these params (the SDK's 2nd-arg `:body` would REPLACE the
   body, dropping model/messages).

   Explicit request opts win over the supplied authority resolution.
   Thinking: resolved config truthy → adaptive; falsy →
   the :thinking key is ABSENT (never {:type \"disabled\"} — 400s on
   Fable). NEVER carries :temperature/:top_p/:top_k (400 on Opus
   4.7+/Fable). `:tools` / `:tool_choice` included ONLY when present
   (request opt > config row). Public so tests and live debugging can
   inspect exactly what goes over the wire."
  {:malli/schema
   [:=> [:catn [:seon.ai.anthropic/request
                :seon.ai.anthropic/complete-request]
               [:seon.ai/config-resolution :seon.ai/config-resolution]]
    :map]}
  [{:seon.ai/keys [ctx model max-tokens tools tool-choice] :as request}
   resolution]
  (let [cfg (:seon.ai/resolved-config resolution)
        {:seon.render/keys [stable-text volatile-text]} (ctx/split-context ctx)
        ;; Both halves must be non-blank to split — a boundary-less ctx
        ;; (tests, stub prompts) degrades to the pre-split wire shape.
        split?  (not (or (str/blank? stable-text) (str/blank? volatile-text)))
        tools*  (or tools (:seon.ai/tools cfg))
        choice* (or tool-choice (:seon.ai/tool-choice cfg))]
    (cond->
      {:model      (or model (:seon.ai/model cfg))
       :max_tokens (or max-tokens (:seon.ai/max-tokens cfg))
       ;; Block array (not a bare string) so the stable prefix carries
       ;; cache breakpoints — see the ns docstring's PROMPT CACHING
       ;; pin. Block 1 = the soul/system prompt; block 2 = the ctx's
       ;; stable prefix (through :namespaces). cache_control on the
       ;; LAST system block caches tools + system + stable ctx; only
       ;; the volatile tail rides after the breakpoint as the user
       ;; message. 2 of the 4 allowed breakpoints — the first keeps a
       ;; partial hit alive when the stable ctx changes (reload, new
       ;; ns) while the soul doesn't.
       :system     (cond-> [{:type "text"
                             :text (ai/effective-system-prompt request)
                             :cache_control {:type "ephemeral"}}]
                     split? (conj {:type "text"
                                   :text stable-text
                                   :cache_control {:type "ephemeral"}}))
       :messages   [{:role "user" :content (if split? volatile-text ctx)}]}
      ;; Adaptive-only: any truthy thinking mode (true or an effort
      ;; string) maps to adaptive; reasoning-effort levels are a
      ;; deepseek wire concept with no Messages-API equivalent here.
      (ai/thinking-mode cfg) (assoc :thinking {:type "adaptive"})
      (some? tools*)         (assoc :tools tools*)
      (some? choice*)        (assoc :tool_choice choice*))))

(defn- request-extra-body
  "The request override, else the already resolved config value."
  [request resolution]
  (or (:seon.ai/extra-body request)
      (:seon.ai/extra-body resolution)))

;; ============================================================
;; Response parsing — :content is an ARRAY of typed blocks; check
;; :stop_reason before reading it.
;; ============================================================

(defn- text-of-blocks
  "Concatenated text of the \"text\" blocks in a Messages API content
   array — \"thinking\" (and any other typed) blocks are skipped."
  [content]
  (->> content
       (filter #(= "text" (:type %)))
       (map :text)
       (apply str)))

(defn- tool-use-blocks
  "The \"tool_use\" blocks in a Messages API content array (the
   convenience `:seon.ai/tool-calls` surface). Empty when none."
  [content]
  (filterv #(= "tool_use" (:type %)) content))

(def ^:private known-message-keys
  "Top-level Message keys the adapter consumes directly — the REMAINDER
   is preserved as :seon.ai/provider-fields (#25). `:parsed_output` is
   an SDK-added convenience field (not provider data), dropped too."
  #{:content :usage :id :type :role :model :stop_reason :stop_sequence
    :parsed_output})

(defn parse-completion
  "Map an assembled Anthropic Message OBJECT to a complete-response.

   Post-`.finalMessage` → a `:seon.ai.anthropic/complete-response`.
   `stop_reason` \"refusal\"
   (Fable safety classifiers; empty content array) becomes a legible
   `:seon.ai/error` envelope — callers must never read content as a
   reply when the model declined. `:content` is an ARRAY of typed
   blocks — \"text\" blocks joined, \"thinking\" skipped, \"tool_use\"
   surfaced as `:seon.ai/tool-calls`; unrecognized top-level fields →
   `:seon.ai/provider-fields` (omitted when empty). Public for tests."
  {:malli/schema [:=> [:catn [:seon.ai.anthropic/message :any]]
                  :seon.ai.anthropic/complete-response]}
  [message]
  (try
    (let [body        (js->clj message :keywordize-keys true)
          stop-reason (:stop_reason body)
          tool-calls  (tool-use-blocks (:content body))
          extras      (apply dissoc body known-message-keys)]
      (if (= "refusal" stop-reason)
        {:seon.ai/text                  ""
         :seon.ai.anthropic/stop-reason stop-reason
         :seon.ai/usage                 (:usage body)
         :seon.ai/error
         {:seon.ai/msg (str "Anthropic refusal — the model declined this "
                            "request (stop_reason \"refusal\", empty "
                            "content). Rephrase or reduce the request; "
                            "the call is not billed pre-output.")}}
        (cond-> {:seon.ai/text  (text-of-blocks (:content body))
                 :seon.ai/usage (:usage body)}
          stop-reason      (assoc :seon.ai.anthropic/stop-reason stop-reason)
          (seq tool-calls) (assoc :seon.ai/tool-calls tool-calls)
          (seq extras)     (assoc :seon.ai/provider-fields extras))))
    (catch :default e
      {:seon.ai/text  ""
       :seon.ai/error {:seon.ai/msg (str "Failed to parse anthropic response: "
                                         (error/->message e))}})))

;; ============================================================
;; SDK call — `.stream` + `.finalMessage` (buffered), errors-as-values.
;; Same envelope + failure classification as openai-compat: timeout /
;; transport / HTTP-status. The SDK's error classes are the source of
;; truth (it wraps a thrown fetch into APIConnectionError, an HTTP
;; non-2xx into an APIError subclass carrying .status).
;; ============================================================

(defn- error->envelope
  "Map an SDK error (or any throwable) onto the `:seon.ai/error`
   envelope. Branch order matters: APIConnectionTimeoutError /
   APIUserAbortError are SUBCLASSES of APIConnectionError which is a
   subclass of APIError — so check timeout/abort FIRST, connection
   (transport) SECOND, the generic APIError (carries .status) THIRD,
   and a plain message LAST (parse/unknown — no flags)."
  [e]
  (cond
    (or (instance? (.-APIConnectionTimeoutError Anthropic) e)
        (instance? (.-APIUserAbortError Anthropic) e))
    {:seon.ai/msg      (str "Anthropic request timed out / aborted: "
                           (error/->message e))
     :seon.ai/timeout? true}

    (instance? (.-APIConnectionError Anthropic) e)
    {:seon.ai/msg        (str "Anthropic connection failed: " (error/->message e))
     :seon.ai/transport? true}

    (instance? (.-APIError Anthropic) e)
    (let [ra (ai/error-retry-after-ms e)]
      (cond-> {:seon.ai/msg    (str "Anthropic HTTP " (.-status e) ": " (error/->message e))
               :seon.ai/status (.-status e)}
        ra (assoc :seon.ai/retry-after-ms ra)))

    :else
    {:seon.ai/msg (str "Anthropic call failed: " (error/->message e))}))

(def ^:dynamic *fetch*
  "Test seam ONLY — see seon.ai.openai-compat/*fetch*. When bound,
   [[make-client]] hands it to the SDK as the `:fetch` option. nil
   (default) → the SDK uses Node's native fetch."
  nil)

(defn- make-client
  "Construct the `@anthropic-ai/sdk` client. `maxRetries 0` — the agent
   loop is the single retry authority. The SDK sets the
   `anthropic-version` + `x-api-key` headers itself. Injects [[*fetch*]]
   when bound (tests)."
  [key ms]
  (new (.-Anthropic Anthropic)
       (cond-> #js{:apiKey     key
                   :timeout    ms
                   :maxRetries 0}
         *fetch* (doto (aset "fetch" *fetch*)))))

(defn ^:async complete
  "Send a completion request to Anthropic via the official SDK.

   The `@anthropic-ai/sdk` (streamed + buffered). Returns a Promise of a
   `:seon.ai.anthropic/complete-response` map.

   Request opts (only :seon.ai/ctx required):
     :seon.ai/ctx           — the full ctx text (required)
     :seon.ai/system-prompt — overrides the database-resident soul
                              (see `seon.ai/effective-system-prompt`)
     :seon.ai/model         — override config row / default-model
     :seon.ai/max-tokens    — override config row / default-max-tokens
     :seon.ai/tools         — Anthropic tool defs (passthrough, off by default)
     :seon.ai/tool-choice   — {:type \"auto\"|\"any\"|\"tool\" …}
     :seon.ai/extra-body    — generic extra request fields

   A missing API key and network/HTTP failures resolve to
   `{:seon.ai/text \"\" :seon.ai/error {…}}` — never a rejected
   Promise. Callers destructure both `:seon.ai/text` and
   `:seon.ai/error`."
  {:malli/schema [:=> [:cat :seon.ai.anthropic/complete-request]
                  :seon.ai.anthropic/complete-response]}
  [request]
  (let [resolution (:seon.ai/config-resolution request)
        config (:seon.ai/resolved-config resolution)
        credential (resolved-credential resolution)
        key (::api-key credential)
        credential-source (:seon.ai/credential-source credential)
        evidence (when resolution
                   (if credential-source
                     (ai/config-evidence resolution credential-source)
                     (ai/config-evidence resolution)))]
    (cond
      (nil? resolution)
      (config-error
        "missing :seon.ai/config-resolution — resolve ordinary authority data before calling Anthropic")

      (nil? key)
      (assoc (config-error
               "Anthropic API key not found in process.env — set ANTHROPIC_API_KEY, SEON_AI_API_KEY, or select an env var with :seon.ai/api-key-env")
             :seon.ai/config-evidence evidence)

      :else
      ;; The WHOLE build+call rides inside the try — the params build reads
      ;; config-provided data (the config row, SEON_AI_EXTRA_BODY extra-body
      ;; merged into the params, the ctx split), so a throw there is an
      ;; EXPECTED error and must resolve to an envelope, never reject: the
      ;; instrument wrapper records a rejection as a :core fault (crashes
      ;; the dev pod). Same class as the stream-until-form! fix (e6295ecd).
      (try
        (let [ms      (:seon.ai/timeout-ms config)
              ^js client (make-client key ms)
              extra   (request-extra-body request resolution)
              ;; :extra-body is MERGED into the request PARAMS (1st arg) —
              ;; the SDK's 2nd-arg RequestOptions :body REPLACES the body
              ;; (drops model/messages), so it must NOT be used. Same fix
              ;; as seon.ai.openai-compat (verified live there).
              params  (clj->js (cond-> (request-params request resolution)
                                 (seq extra) (merge extra)))
              ^js messages (.. client -messages)
              signal (:seon.ai/abort-signal request)
              ^js stream (if signal
                           (.stream messages params #js{:signal signal})
                           (.stream messages params))
              message (await (.finalMessage stream))
              result  (parse-completion message)]
          (when-let [err (:seon.ai/error result)]
            (ai/log-error! "Anthropic" err))
          result)
        (catch :default e
          (let [err (error->envelope e)]
            (ai/log-error! "Anthropic" err)
            {:seon.ai/text  ""
             :seon.ai/error err}))))))

;; ============================================================
;; Adapter for seon.agent — same bridge shape as deepseek's.
;; ============================================================

(defn ^:async ^:private complete+wrap
  "Internal — call complete with merged opts, wrap response into the
   shape the turn loop expects. On failure `:seon.ai/error` is lifted
   to the TOP level (alongside `:text`) so the turn loop can surface
   it without digging into `:seon.ai/raw`."
  [opts request]
  (let [ctx-text (:seon.ai/ctx request)
        signal   (:seon.ai/abort-signal request)
        system-prompt (:seon.ai/system-prompt request)
        resolution (:seon.ai/config-resolution request)
        resp (await (complete (cond-> (assoc opts :seon.ai/ctx ctx-text)
                                signal (assoc :seon.ai/abort-signal signal)
                                resolution
                                (assoc :seon.ai/config-resolution resolution)
                                system-prompt
                                (assoc :seon.ai/system-prompt system-prompt))))]
    (cond-> {:text        (:seon.ai/text resp)
             :seon.ai/raw resp}
      (:seon.ai/error resp) (assoc :seon.ai/error (:seon.ai/error resp)))))

(defn agent-adapter
  "A request function suitable for the agent turn's `llm-fn`.

   Optional `opts` override
   request defaults (e.g. `{:seon.ai/max-tokens 2048}`). The returned
   fn calls `complete` ^:async-internally and returns a Promise of
   `{:text \"…\" :seon.ai/raw <full response>}` — plus a top-level
   `:seon.ai/error` (see the `:seon.ai/error` schema) when the call
   failed (timeout, fetch error, HTTP error, unparseable body,
   refusal)."
  {:malli/schema
   [:function
    [:=> [:cat] :any]
    [:=> [:catn [::opts ::opts]] :any]]}
  ([] (agent-adapter {}))
  ([opts]
   ;; This adapter buffers, so it ignores `:seon.ai/stream?`, but preserves the
   ;; request's attempt-cancellation signal.
   (fn [request] (complete+wrap opts request))))
