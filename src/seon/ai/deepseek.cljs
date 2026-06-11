(ns seon.ai.deepseek
  "DeepSeek HTTP client. ^:async — returns Promises.

   One agent-facing fn: [[agent-adapter]] returns `(fn [ctx-string])`
   compatible with `seon.agent/run-turn-once!`'s `llm-fn`. Reads the
   API key from `DEEPSEEK_API_KEY` in `process.env`.

   The system prompt sets the agent up as a REPL and is STORE-RESIDENT:
   priority-ordered `:my.soul` rows (seeded at boot from SOUL.md +
   the REPL mechanics, runtime-editable by transact — see
   `my.soul`), read per call by [[effective-system-prompt]] with a
   minimal [[fallback-system-prompt]] for the store-unavailable boot
   edge. The per-turn ctx
   (rendered via `seon.render/ai-render` against the agent's
   `:seon.render/ai` slot; default `'seon.agent/assemble-context`)
   follows.

   No tool-calling envelope, no streaming — the agent's responses are
   parsed as Clojure forms by `seon.repl/parse-forms`, evaluated as
   a REPL batch by `seon.eval/eval-batch!`."
  (:require [my.soul :as soul]
            [seon.db :as db]
            [seon.error :as error]
            [seon.log :as log]
            [seon.schema :as schema]))

;; ============================================================
;; Schemas — request + response shapes.
;; ============================================================

(schema/register! :seon.ai/text :string)
(schema/register! :seon.ai/model :string)
(schema/register! :seon.ai/temperature :double)
(schema/register! :seon.ai/max-tokens :int)
(schema/register! :seon.ai/system-prompt :string)
(schema/register! :seon.ai/ctx :string)
(schema/register! :seon.ai/usage :map)
(schema/register! :seon.ai/msg :string)
(schema/register! :seon.ai/status :int)
(schema/register! :seon.ai/timeout? :boolean)
(schema/register! :seon.ai/raw-body :string)

;; The errors-are-values envelope for LLM calls. Every failure mode
;; (timeout, fetch throw, HTTP non-2xx, unparseable body) resolves to
;; a response map carrying this under :seon.ai/error — never a
;; rejected Promise. Callers (seon.agent's turn loop) MUST surface it.
(schema/register!
  :seon.ai/error
  [:map
   [:seon.ai/msg      :seon.ai/msg]
   [:seon.ai/status   {:optional true} :seon.ai/status]
   [:seon.ai/timeout? {:optional true} :seon.ai/timeout?]
   [:seon.ai/raw-body {:optional true} :seon.ai/raw-body]])

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
;; Config — pinned model + endpoint.
;; ============================================================

(def ^:private default-model       "deepseek-v4-pro")
(def ^:private default-endpoint    "https://api.deepseek.com/chat/completions")
(def ^:private default-temperature 0.7)
(def ^:private default-max-tokens  4096)

;; Wall-clock timeout for the DeepSeek HTTP call. A hung API stops
;; wedging the agent loop — turn fails with a timeout error and the
;; next user message kicks again. Replace via [[set-timeout-ms!]].
(defonce !timeout-ms (atom 60000))

(defn set-timeout-ms!
  "Replace the per-call wall-clock timeout (default 60000ms). Returns
   the new value."
  {:malli/schema [:=> [:cat :int] :int]}
  [ms]
  (reset! !timeout-ms ms))

;; Thinking mode (deepseek-v4-pro). The API DEFAULTS TO ENABLED, which
;; is slow — a long-ctx thinking call can blow past the 60s wall-clock
;; timeout (observed 2026-06-10: turn 4 aborted at exactly 60.8s with
;; no reply). We send {"thinking": {"type": "disabled"}} by default for
;; fast iteration; re-enable per-run via [[set-thinking!]].
;;
;; Value: false (disabled — default), true (enabled), or a
;; reasoning-effort string "high"/"max" (enabled + "reasoning_effort").
(defonce !thinking (atom false))

(defn set-thinking!
  "Set DeepSeek thinking mode for subsequent calls. `mode` is `false`
   (disabled — the default), `true` (enabled), or \"high\"/\"max\"
   (enabled with that reasoning_effort). Returns the new value."
  {:malli/schema [:=> [:cat [:or :boolean [:enum "high" "max"]]]
                  [:or :boolean [:enum "high" "max"]]]}
  [mode]
  (reset! !thinking mode))

(def fallback-system-prompt
  "Minimal boot-edge fallback ONLY — used when the store has no
   :my.soul rows yet (or the conn is not up). The REAL system
   prompt lives in the store as :my.soul rows, seeded at boot from
   the repo's SOUL.md + my.soul/mechanics-text and editable at
   runtime by transact (see my.soul)."
  (str "You are Seon, a bonded Clojure agent. Your entire output is "
       "read and evaluated as ClojureScript source — act by emitting "
       "forms, narrate with ; line comments, no markdown fences."))

(defn effective-system-prompt
  "The system message content for a call: the request's explicit
   `:seon.ai/system-prompt` override when given, else the
   store-resident soul ([[my.soul/system-prompt-text]] — the
   priority-ordered :my.soul rows, seeded at boot from SOUL.md and
   runtime-editable by transact), else [[fallback-system-prompt]]
   (store empty or unavailable). Never throws."
  {:malli/schema [:=> [:cat :seon.ai.deepseek/complete-request]
                  :seon.ai/system-prompt]}
  [{:seon.ai/keys [system-prompt]}]
  (or system-prompt
      (try (not-empty (soul/system-prompt-text))
           (catch :default _ nil))
      fallback-system-prompt))

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

   Public so tests and live debugging can inspect exactly what goes
   over the wire — in particular the thinking toggle ([[!thinking]]):
   disabled by default, {:thinking {:type \"enabled\"}} (+ optional
   :reasoning_effort) when [[set-thinking!]] turned it on."
  {:malli/schema [:=> [:cat :seon.ai.deepseek/complete-request] :map]}
  [{:seon.ai/keys [ctx model temperature max-tokens] :as request}]
  (let [thinking @!thinking]
    (cond->
      {:model       (or model default-model)
       :messages    [{:role "system" :content (effective-system-prompt request)}
                     {:role "user"   :content ctx}]
       :temperature (or temperature default-temperature)
       :max_tokens  (or max-tokens default-max-tokens)
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

(defn- log-error!
  "ERROR-log an LLM failure with the live agent + turn identity (read
   from the ALS scopes seon.agent establishes around each turn) so a
   timed-out/failed call is NEVER silent in logs/pod.log. Best-effort —
   never throws."
  [error-map]
  (try
    (let [agent-id (db/current-agent-id)
          turn-id  (:seon.db/turn-id (db/current-tx-context))]
      (log/error!
        (cond-> {:seon.log/source  ::complete
                 :seon.log/message (str "DeepSeek call failed"
                                        (when turn-id (str " (turn " turn-id ")"))
                                        " — " (:seon.ai/msg error-map))
                 :seon.log/data    error-map}
          agent-id (assoc :seon.log/agent agent-id))))
    (catch :default _ nil)))

(defn ^:async complete
  "Send a completion request to DeepSeek. Returns a Promise of a
   `:seon.ai.deepseek/complete-response` map.

   Request opts (only :seon.ai/ctx required):
     :seon.ai/ctx           — the full ctx text (required)
     :seon.ai/system-prompt — overrides the store-resident soul
                              (see [[effective-system-prompt]])
     :seon.ai/model         — override default-model
     :seon.ai/temperature   — override default-temperature
     :seon.ai/max-tokens    — override default-max-tokens

   Network/HTTP failures resolve to `{:seon.ai/text \"\" :seon.ai/error
   {…}}` (per spec-02 §2.5: safe-by-default at the boundary). Callers
   destructure both `:seon.ai/text` and `:seon.ai/error`."
  {:malli/schema [:=> [:cat :seon.ai.deepseek/complete-request]
                  :seon.ai.deepseek/complete-response]}
  [{:seon.ai/keys [ctx system-prompt model temperature max-tokens]
    :or {model       default-model
         temperature default-temperature
         max-tokens  default-max-tokens}}]
  (let [controller (js/AbortController.)
        ms         @!timeout-ms
        timer      (js/setTimeout #(.abort controller) ms)]
    (try
      (let [resp (await (js/fetch default-endpoint
                          (clj->js
                            {:method  "POST"
                             :signal  (.-signal controller)
                             :headers {:Content-Type  "application/json"
                                       :Authorization (str "Bearer " (api-key))}
                             :body    (body-json
                                        (cond-> {:seon.ai/ctx         ctx
                                                 :seon.ai/model       model
                                                 :seon.ai/temperature temperature
                                                 :seon.ai/max-tokens  max-tokens}
                                          system-prompt
                                          (assoc :seon.ai/system-prompt system-prompt)))})))
            body-text (await (.text resp))
            _         (js/clearTimeout timer)
            result    (if (.-ok resp)
                        (parse-response body-text)
                        {:seon.ai/text  ""
                         :seon.ai/error {:seon.ai/msg    (str "DeepSeek HTTP " (.-status resp)
                                                              ": " body-text)
                                         :seon.ai/status (.-status resp)}})]
        (when-let [err (:seon.ai/error result)]
          (log-error! err))
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
                         aborted? (assoc :seon.ai/timeout? true))]
          (log-error! err)
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
