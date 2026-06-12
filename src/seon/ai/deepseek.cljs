(ns seon.ai.deepseek
  "DeepSeek HTTP client. ^:async — returns Promises.

   One agent-facing fn: [[agent-adapter]] returns `(fn [ctx-string])`
   compatible with `seon.agent/run-turn-once!`'s `llm-fn`. Reads the
   API key from `DEEPSEEK_API_KEY` in `process.env`.

   Call settings (model, temperature, max-tokens, thinking,
   timeout-ms) come from the `:seon.ai/config` row, read PER CALL via
   `seon.ai/current` (C-18 — env-seeded via SEON_AI_*, runtime-tunable
   by transact). Precedence: explicit request opt > config row > the
   shipped defaults below. Absent env + absent row sends EXACTLY the
   pre-C-18 wire body.

   The system prompt sets the agent up as a REPL and is STORE-RESIDENT:
   priority-ordered `:my.soul` rows (seeded at boot from SOUL.md +
   the REPL mechanics, runtime-editable by transact — see
   `my.soul`), read per call by `seon.ai/effective-system-prompt` with
   a minimal fallback for the store-unavailable boot edge. The
   per-turn ctx (rendered via `seon.render/ai-render` against the
   agent's `:seon.render/ai` slot; default
   `'seon.agent/assemble-context`) follows.

   No tool-calling envelope, no streaming — the agent's responses are
   parsed as Clojure forms by `seon.repl/parse-forms`, evaluated as
   a REPL batch by `seon.eval/eval-batch!`."
  (:require [seon.ai :as ai]
            [seon.error :as error]
            [seon.schema :as schema]))

;; ============================================================
;; Schemas — request + response shapes. The shared :seon.ai/* field
;; vocabulary (text, model, error envelope, …) lives in seon.ai.
;; ============================================================

(schema/register!
  :seon.ai.deepseek/complete-request
  [:map
   [:seon.ai/ctx           :seon.ai/ctx]
   [:seon.ai/system-prompt {:optional true} :seon.ai/system-prompt]
   [:seon.ai/model         {:optional true} :seon.ai/model]
   [:seon.ai/temperature   {:optional true} :seon.ai/temperature]
   [:seon.ai/max-tokens    {:optional true} :seon.ai/max-tokens]])

(schema/register!
  :seon.ai.deepseek/complete-response
  [:map
   [:seon.ai/text                    :string]
   [:seon.ai/error                   {:optional true} :seon.ai/error]
   [:seon.ai.deepseek/finish-reason  {:optional true} :string]
   [:seon.ai/usage                   {:optional true} :map]])

;; ============================================================
;; Config — the shipped defaults. The :seon.ai/config row (read per
;; call) overrides these; explicit request opts override the row.
;; ============================================================

(def ^:private default-model       "deepseek-v4-pro")
(def ^:private default-endpoint    "https://api.deepseek.com/chat/completions")
(def ^:private default-temperature 0.7)
(def ^:private default-max-tokens  4096)
;; Wall-clock timeout for the DeepSeek HTTP call. A hung API stops
;; wedging the agent loop — turn fails with a timeout error and the
;; next user message kicks again. Override via the config row's
;; :seon.ai/timeout-ms (SEON_AI_TIMEOUT_MS).
(def ^:private default-timeout-ms  60000)

;; Thinking mode (deepseek-v4-pro). The API DEFAULTS TO ENABLED, which
;; is slow — a long-ctx thinking call can blow past the 60s wall-clock
;; timeout (observed 2026-06-10: turn 4 aborted at exactly 60.8s with
;; no reply). We send {"thinking": {"type": "disabled"}} unless the
;; config row's :seon.ai/thinking (SEON_AI_THINKING) turns it on:
;; "true" → enabled, "high"/"max" → enabled + that reasoning_effort.

;; ============================================================
;; HTTP — js/fetch + ^:async/await. Errors return as values on the
;; response map (caller destructures :seon.ai/text + :seon.ai/error).
;; Uses Node 18+'s native fetch; no polyfill.
;; ============================================================

(defn- api-key []
  (or (some-> js/process .-env .-DEEPSEEK_API_KEY)
      (throw (ex-info
               "DEEPSEEK_API_KEY not set in process.env"
               {:seon.ai.deepseek/error :missing-api-key}))))

(defn request-body
  "Build the DeepSeek JSON request body as a CLJ map. The bare keys
   (:model, :messages, :thinking, …) are the DeepSeek API's wire
   format — a third-party boundary, deliberately un-namespaced.

   Reads the `:seon.ai/config` row PER CALL (`seon.ai/current`);
   explicit request opts win over the row, the row wins over the
   shipped defaults. Public so tests and live debugging can inspect
   exactly what goes over the wire — in particular the thinking
   toggle: disabled unless the row turns it on
   ({:thinking {:type \"enabled\"}} + optional :reasoning_effort)."
  {:malli/schema [:=> [:cat :seon.ai.deepseek/complete-request] :map]}
  [{:seon.ai/keys [ctx model temperature max-tokens] :as request}]
  (let [cfg      (ai/current)
        thinking (ai/thinking-mode cfg)]
    (cond->
      {:model       (or model (:seon.ai/model cfg) default-model)
       :messages    [{:role "system" :content (ai/effective-system-prompt request)}
                     {:role "user"   :content ctx}]
       :temperature (or temperature (:seon.ai/temperature cfg) default-temperature)
       :max_tokens  (or max-tokens (:seon.ai/max-tokens cfg) default-max-tokens)
       :thinking    {:type (if thinking "enabled" "disabled")}
       :stream      false}
      (string? thinking) (assoc :reasoning_effort thinking))))

(defn- body-json [request]
  (.stringify js/JSON (clj->js (request-body request))))

(defn- parse-response [body-text]
  (try
    (let [body (js->clj (.parse js/JSON body-text) :keywordize-keys true)
          msg  (-> body :choices first :message :content)]
      {:seon.ai/text                    (or msg "")
       :seon.ai.deepseek/finish-reason  (-> body :choices first :finish_reason)
       :seon.ai/usage                   (-> body :usage)})
    (catch :default e
      {:seon.ai/text  ""
       :seon.ai/error {:seon.ai/msg      (str "Failed to parse deepseek response: "
                                              (error/->message e))
                       :seon.ai/raw-body body-text}})))

(defn ^:async complete
  "Send a completion request to DeepSeek. Returns a Promise of a
   `:seon.ai.deepseek/complete-response` map.

   Request opts (only :seon.ai/ctx required):
     :seon.ai/ctx           — the full ctx text (required)
     :seon.ai/system-prompt — overrides the store-resident soul
                              (see `seon.ai/effective-system-prompt`)
     :seon.ai/model         — override config row / default-model
     :seon.ai/temperature   — override config row / default-temperature
     :seon.ai/max-tokens    — override config row / default-max-tokens

   Network/HTTP failures resolve to `{:seon.ai/text \"\" :seon.ai/error
   {…}}` (per spec-02 §2.5: safe-by-default at the boundary). Callers
   destructure both `:seon.ai/text` and `:seon.ai/error`."
  {:malli/schema [:=> [:cat :seon.ai.deepseek/complete-request]
                  :seon.ai.deepseek/complete-response]}
  [request]
  (let [controller (js/AbortController.)
        ms         (or (:seon.ai/timeout-ms (ai/current)) default-timeout-ms)
        timer      (js/setTimeout #(.abort controller) ms)]
    (try
      (let [resp (await (js/fetch default-endpoint
                          (clj->js
                            {:method  "POST"
                             :signal  (.-signal controller)
                             :headers {:Content-Type  "application/json"
                                       :Authorization (str "Bearer " (api-key))}
                             :body    (body-json request)})))
            body-text (await (.text resp))
            _         (js/clearTimeout timer)
            result    (if (.-ok resp)
                        (parse-response body-text)
                        {:seon.ai/text  ""
                         :seon.ai/error {:seon.ai/msg    (str "DeepSeek HTTP " (.-status resp)
                                                              ": " body-text)
                                         :seon.ai/status (.-status resp)}})]
        (when-let [err (:seon.ai/error result)]
          (ai/log-error! "DeepSeek" err))
        result)
      (catch :default e
        (js/clearTimeout timer)
        (let [aborted? (= "AbortError" (some-> e .-name))
              err      (cond-> {:seon.ai/msg
                                (if aborted?
                                  (str "DeepSeek request timed out after " ms
                                       "ms (wall-clock abort — no reply received)")
                                  (str "DeepSeek fetch failed: " (error/->message e)))}
                         ;; optional = absent — only present (true) on a
                         ;; genuine wall-clock abort, never stored false.
                         aborted?       (assoc :seon.ai/timeout? true)
                         ;; fetch threw with NO abort = network-shaped
                         ;; transport failure — the one retryable class
                         ;; (see the :seon.ai/transport? registration).
                         (not aborted?) (assoc :seon.ai/transport? true))]
          (ai/log-error! "DeepSeek" err)
          {:seon.ai/text  ""
           :seon.ai/error err})))))

;; ============================================================
;; Adapter for seon.agent.
;;
;; seon.agent/run-turn-once! expects (fn [ctx-string]) → Promise of
;; `{:text "..."}`. complete takes a request map and returns a Promise
;; of namespaced keys. This bridges the two.
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
   request defaults (e.g. `{:seon.ai/temperature 0.2}`). The returned
   fn calls `complete` ^:async-internally and returns a Promise of
   `{:text \"…\" :seon.ai/raw <full response>}` — plus a top-level
   `:seon.ai/error` (see the `:seon.ai/error` schema) when the call
   failed (timeout, fetch error, HTTP error, unparseable body)."
  ([] (agent-adapter {}))
  ([opts]
   (fn [ctx-text] (complete+wrap opts ctx-text))))
