(ns seon.ai.anthropic
  "Anthropic Messages API client (C-20). ^:async — returns Promises.
   Same agent-adapter contract as [[seon.ai.deepseek]]: one
   agent-facing fn, [[agent-adapter]], returns `(fn [ctx-string])`
   compatible with `seon.agent/run-turn-once!`'s `llm-fn`. Reads the
   API key from `ANTHROPIC_API_KEY` in `process.env`. Raw js/fetch
   wire shape mirroring deepseek.cljs — one pattern, no SDK dep.

   Call settings come from the `:seon.ai/config` row, read PER CALL
   via `seon.ai/current` (C-18). Precedence: explicit request opt >
   config row > the shipped defaults below.

   API specifics PINNED from the API reference 2026-06-11 (exact
   strings — do NOT append date suffixes):
     - default model \"claude-opus-4-8\"; also valid:
       \"claude-sonnet-4-6\", \"claude-haiku-4-5\", \"claude-fable-5\"
     - POST https://api.anthropic.com/v1/messages; headers `x-api-key`
       + `anthropic-version: 2023-06-01`
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
       surfaced as a legible :seon.ai/error envelope)."
  (:require [seon.ai :as ai]
            [seon.error :as error]
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
   [:seon.ai/max-tokens    {:optional true} :seon.ai/max-tokens]])

(schema/register! :seon.ai.anthropic/stop-reason :string)

(schema/register!
  :seon.ai.anthropic/complete-response
  [:map
   [:seon.ai/text                   :string]
   [:seon.ai/error                  {:optional true} :seon.ai/error]
   [:seon.ai.anthropic/stop-reason  {:optional true} :seon.ai.anthropic/stop-reason]
   [:seon.ai/usage                  {:optional true} :map]])

;; ============================================================
;; Config — the shipped defaults. The :seon.ai/config row (read per
;; call) overrides these; explicit request opts override the row.
;; ============================================================

(def ^:private default-model      "claude-opus-4-8")
(def ^:private default-endpoint   "https://api.anthropic.com/v1/messages")
;; max_tokens is REQUIRED by the Messages API. 16000 keeps long
;; non-streaming replies from truncating mid-thought (current API
;; guidance) while staying under HTTP timeouts.
(def ^:private default-max-tokens 16000)
(def ^:private default-timeout-ms 60000)
(def ^:private anthropic-version  "2023-06-01")

(defn- api-key []
  (or (some-> js/process .-env .-ANTHROPIC_API_KEY)
      (throw (ex-info
               "ANTHROPIC_API_KEY not set in process.env"
               {:seon.ai.anthropic/error :missing-api-key}))))

;; ============================================================
;; Request body.
;; ============================================================

(defn request-body
  "Build the Anthropic Messages API JSON request body as a CLJ map.
   The bare keys (:model, :messages, :system, …) are the API's wire
   format — a third-party boundary, deliberately un-namespaced.

   Reads the `:seon.ai/config` row PER CALL (`seon.ai/current`);
   explicit request opts win over the row, the row wins over the
   shipped defaults. Thinking: config row truthy → adaptive; falsy →
   the :thinking key is ABSENT (never {:type \"disabled\"} — 400s on
   Fable). NEVER carries :temperature/:top_p/:top_k (400 on Opus
   4.7+/Fable). Public so tests and live debugging can inspect exactly
   what goes over the wire."
  {:malli/schema [:=> [:cat :seon.ai.anthropic/complete-request] :map]}
  [{:seon.ai/keys [ctx model max-tokens] :as request}]
  (let [cfg (ai/current)]
    (cond->
      {:model      (or model (:seon.ai/model cfg) default-model)
       :max_tokens (or max-tokens (:seon.ai/max-tokens cfg) default-max-tokens)
       :system     (ai/effective-system-prompt request)
       :messages   [{:role "user" :content ctx}]}
      ;; Adaptive-only: any truthy thinking mode (true or an effort
      ;; string) maps to adaptive; reasoning-effort levels are a
      ;; deepseek wire concept with no Messages-API equivalent here.
      (ai/thinking-mode cfg) (assoc :thinking {:type "adaptive"}))))

(defn- body-json [request]
  (.stringify js/JSON (clj->js (request-body request))))

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

(defn parse-response
  "Parse a Messages API response body string to a
   `:seon.ai.anthropic/complete-response` map. `stop_reason`
   \"refusal\" (Fable safety classifiers; empty content array) becomes
   a legible `:seon.ai/error` envelope — callers must never read
   content as a reply when the model declined. Public for tests."
  {:malli/schema [:=> [:catn [:seon.ai/raw-body :seon.ai/raw-body]]
                  :seon.ai.anthropic/complete-response]}
  [body-text]
  (try
    (let [body        (js->clj (.parse js/JSON body-text) :keywordize-keys true)
          stop-reason (:stop_reason body)]
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
          stop-reason (assoc :seon.ai.anthropic/stop-reason stop-reason))))
    (catch :default e
      {:seon.ai/text  ""
       :seon.ai/error {:seon.ai/msg      (str "Failed to parse anthropic response: "
                                              (error/->message e))
                       :seon.ai/raw-body body-text}})))

;; ============================================================
;; HTTP — js/fetch + ^:async/await, errors-as-values (same envelope +
;; failure classification as deepseek: timeout / transport / HTTP).
;; ============================================================

(defn ^:async complete
  "Send a completion request to Anthropic. Returns a Promise of a
   `:seon.ai.anthropic/complete-response` map.

   Request opts (only :seon.ai/ctx required):
     :seon.ai/ctx           — the full ctx text (required)
     :seon.ai/system-prompt — overrides the store-resident soul
                              (see `seon.ai/effective-system-prompt`)
     :seon.ai/model         — override config row / default-model
     :seon.ai/max-tokens    — override config row / default-max-tokens

   Network/HTTP failures resolve to `{:seon.ai/text \"\" :seon.ai/error
   {…}}` — never a rejected Promise. Callers destructure both
   `:seon.ai/text` and `:seon.ai/error`."
  {:malli/schema [:=> [:cat :seon.ai.anthropic/complete-request]
                  :seon.ai.anthropic/complete-response]}
  [request]
  (let [controller (js/AbortController.)
        ms         (or (:seon.ai/timeout-ms (ai/current)) default-timeout-ms)
        timer      (js/setTimeout #(.abort controller) ms)]
    (try
      (let [resp (await (js/fetch default-endpoint
                          (clj->js
                            {:method  "POST"
                             :signal  (.-signal controller)
                             :headers {:content-type      "application/json"
                                       :x-api-key         (api-key)
                                       :anthropic-version anthropic-version}
                             :body    (body-json request)})))
            body-text (await (.text resp))
            _         (js/clearTimeout timer)
            result    (if (.-ok resp)
                        (parse-response body-text)
                        {:seon.ai/text  ""
                         :seon.ai/error {:seon.ai/msg    (str "Anthropic HTTP " (.-status resp)
                                                              ": " body-text)
                                         :seon.ai/status (.-status resp)}})]
        (when-let [err (:seon.ai/error result)]
          (ai/log-error! "Anthropic" err))
        result)
      (catch :default e
        (js/clearTimeout timer)
        (let [aborted? (= "AbortError" (some-> e .-name))
              err      (cond-> {:seon.ai/msg
                                (if aborted?
                                  (str "Anthropic request timed out after " ms
                                       "ms (wall-clock abort — no reply received)")
                                  (str "Anthropic fetch failed: " (error/->message e)))}
                         ;; optional = absent — only present (true) on a
                         ;; genuine wall-clock abort, never stored false.
                         aborted?       (assoc :seon.ai/timeout? true)
                         ;; fetch threw with NO abort = network-shaped
                         ;; transport failure — the one retryable class.
                         (not aborted?) (assoc :seon.ai/transport? true))]
          (ai/log-error! "Anthropic" err)
          {:seon.ai/text  ""
           :seon.ai/error err})))))

;; ============================================================
;; Adapter for seon.agent — same bridge shape as deepseek's.
;; ============================================================

(defn ^:async ^:private complete+wrap
  "Internal — call complete with merged opts, wrap response into the
   shape the turn loop expects. On failure `:seon.ai/error` is lifted
   to the TOP level (alongside `:text`) so the turn loop can surface
   it without digging into `:seon.ai/raw`."
  [opts ctx-text]
  (let [resp (await (complete (assoc opts :seon.ai/ctx ctx-text)))]
    (cond-> {:text        (:seon.ai/text resp)
             :seon.ai/raw resp}
      (:seon.ai/error resp) (assoc :seon.ai/error (:seon.ai/error resp)))))

(defn agent-adapter
  "Returns a fn-of-ctx-string suitable for
   `seon.agent/run-turn-once!`'s `llm-fn`. Optional `opts` override
   request defaults (e.g. `{:seon.ai/max-tokens 2048}`). The returned
   fn calls `complete` ^:async-internally and returns a Promise of
   `{:text \"…\" :seon.ai/raw <full response>}` — plus a top-level
   `:seon.ai/error` (see the `:seon.ai/error` schema) when the call
   failed (timeout, fetch error, HTTP error, unparseable body,
   refusal)."
  ([] (agent-adapter {}))
  ([opts]
   (fn [ctx-text] (complete+wrap opts ctx-text))))
