(ns seon.ai.deepseek
  "OpenAI-compatible chat-completions HTTP client (the DeepSeek wire
   format IS the OpenAI format). ^:async — returns Promises.

   ONE request path, two providers (no fork — task #30):

     - `:deepseek` (the default) — the shipped endpoint
       (https://api.deepseek.com/chat/completions) and the
       `DEEPSEEK_API_KEY` env default.
     - `:openai-compat` — any OpenAI-compatible gateway. The endpoint
       is the config row's `:seon.ai/base-url` (SEON_AI_BASE_URL): the
       FULL chat-completions URL, posted as-is, no path appended. No
       shipped default — `:openai-compat` selected with no base-url is
       a legible error envelope at call time, never a throw.

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

   No tool-calling envelope, no streaming REQUESTED — we always send
   `stream: false` and the agent's responses are parsed as Clojure
   forms by `seon.repl/parse-forms`, evaluated as a REPL batch by
   `seon.eval/eval-batch!`. Some gateways IGNORE `stream: false` and
   send an SSE body anyway (observed live, 2026-06-11 — gateway bug,
   flagged upstream); the response path branches on the Content-Type
   header and aggregates a `text/event-stream` body into the SAME
   completion shape the JSON path produces — one parse target
   downstream, no streaming to consumers."
  (:require [clojure.string :as str]
            [seon.ai :as ai]
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
;; no reply). For :deepseek we send {"thinking": {"type": "disabled"}}
;; unless the config row's :seon.ai/thinking (SEON_AI_THINKING) turns
;; it on: "true" → enabled, "high"/"max" → enabled + that
;; reasoning_effort. For :openai-compat the thinking field is sent
;; ONLY when set truthy — absent/"false" sends NOTHING (graceful no-op
;; on gateways that don't know the field; deepseek's explicit-disable
;; rationale doesn't apply to a generic gateway).

;; ============================================================
;; HTTP — js/fetch + ^:async/await. Errors return as values on the
;; response map (caller destructures :seon.ai/text + :seon.ai/error).
;; Uses Node 18+'s native fetch; no polyfill.
;; ============================================================

(defn- env*
  "process.env value for `var-name`, or nil when unset/blank."
  [var-name]
  (let [v (some-> js/process .-env (aget var-name))]
    (when (and (string? v) (seq v)) v)))

(defn- openai-compat?
  "Is this pod's active provider :openai-compat? Read per call
   (reactive-context) — the SAME request path serves both providers."
  []
  (= :openai-compat (ai/provider)))

(defn- endpoint
  "The chat-completions URL for this call: the shipped deepseek
   endpoint for :deepseek; the config row's :seon.ai/base-url (FULL
   URL, posted as-is — see the ns doc) for :openai-compat, with the
   SEON_AI_BASE_URL env as the pre-sync fallback. nil = :openai-compat
   selected but unconfigured ([[complete]] returns a legible error
   envelope)."
  []
  (if (openai-compat?)
    (or (:seon.ai/base-url (ai/current))
        (:seon.ai/base-url (ai/env-row)))
    default-endpoint))

(defn- resolved-api-key
  "The bearer key for this call, or nil — see the ns doc for the
   resolution order. Read from process.env at call time; the key value
   is never transacted."
  []
  (let [key-env (or (:seon.ai/api-key-env (ai/current))
                    (:seon.ai/api-key-env (ai/env-row)))]
    (or (some-> key-env env*)
        (when-not (openai-compat?) (env* "DEEPSEEK_API_KEY"))
        (env* "SEON_AI_API_KEY"))))

(defn api-key-configured?
  "Whether a bearer API key resolves for the ACTIVE provider (see the
   ns doc's resolution order). `seon.client/current-llm-fn` uses this
   to fall back to the stub llm-fn when no key is available."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (boolean (resolved-api-key)))

(defn request-body
  "Build the DeepSeek JSON request body as a CLJ map. The bare keys
   (:model, :messages, :thinking, …) are the DeepSeek API's wire
   format — a third-party boundary, deliberately un-namespaced.

   Reads the `:seon.ai/config` row PER CALL (`seon.ai/current`);
   explicit request opts win over the row, the row wins over the
   shipped defaults. Public so tests and live debugging can inspect
   exactly what goes over the wire — in particular the thinking
   toggle: for :deepseek, disabled unless the row turns it on
   ({:thinking {:type \"enabled\"}} + optional :reasoning_effort);
   for :openai-compat, sent ONLY when the row turns it on (absent
   otherwise — graceful no-op on plain gateways)."
  {:malli/schema [:=> [:cat :seon.ai.deepseek/complete-request] :map]}
  [{:seon.ai/keys [ctx model temperature max-tokens] :as request}]
  (let [cfg      (ai/current)
        thinking (ai/thinking-mode cfg)
        compat?  (openai-compat?)]
    (cond->
      {:model       (or model (:seon.ai/model cfg) default-model)
       :messages    [{:role "system" :content (ai/effective-system-prompt request)}
                     {:role "user"   :content ctx}]
       :temperature (or temperature (:seon.ai/temperature cfg) default-temperature)
       :max_tokens  (or max-tokens (:seon.ai/max-tokens cfg) default-max-tokens)
       :stream      false}
      ;; :deepseek always sends the explicit toggle (the API defaults
      ;; to enabled — see the thinking-mode comment above);
      ;; :openai-compat sends the field ONLY when thinking is truthy.
      (not compat?)          (assoc :thinking {:type (if thinking "enabled" "disabled")})
      (and compat? thinking) (assoc :thinking {:type "enabled"})
      (string? thinking)     (assoc :reasoning_effort thinking))))

(defn- body-json [request]
  (.stringify js/JSON (clj->js (request-body request))))

(defn- parse-response [body-text]
  (try
    (let [body      (js->clj (.parse js/JSON body-text) :keywordize-keys true)
          message   (-> body :choices first :message)
          msg       (:content message)
          reasoning (:reasoning_content message)]
      ;; Diagnosis evidence, not behavior (downstream ask 20): a
      ;; thinking-mode completion can land EVERY token in
      ;; reasoning_content and return empty visible content — the
      ;; turn-outcome guard in seon.agent/run-agentic-loop! handles the
      ;; re-prompt; here we just log that the reasoning field was
      ;; present and dropped, so an empty turn is attributable.
      (when (and (zero? (count (or msg "")))
                 (pos? (count (or reasoning ""))))
        (js/console.debug
          (str "seon.ai.deepseek: completion content EMPTY but"
               " reasoning_content present (" (count reasoning)
               " chars) — thinking-mode tokens landed in the reasoning"
               " field; dropping it (parsed as before)")))
      {:seon.ai/text                    (or msg "")
       :seon.ai.deepseek/finish-reason  (-> body :choices first :finish_reason)
       :seon.ai/usage                   (-> body :usage)})
    (catch :default e
      {:seon.ai/text  ""
       :seon.ai/error {:seon.ai/msg      (str "Failed to parse deepseek response: "
                                              (error/->message e))
                       :seon.ai/raw-body body-text}})))

;; ============================================================
;; SSE body tolerance (task #31). We always send `stream: false`, but
;; some gateways stream SSE unconditionally (gateway bug — observed
;; live downstream 2026-06-11, flagged to its owners). The body is
;; already fully buffered by `(.text resp)` above, so this is a
;; split-and-aggregate over `data:` lines, NOT consumer streaming.
;; ============================================================

(defn- sse-content-type?
  "Does this Content-Type header value denote an SSE body? Matches
   `text/event-stream` with or without parameters
   (\"text/event-stream; charset=utf-8\"). nil/absent header → false."
  [content-type]
  (boolean
    (some-> content-type str/lower-case str/trim
            (str/starts-with? "text/event-stream"))))

(defn- sse-data-lines
  "The `data:` field values of a buffered SSE body, in order.
   Per the SSE spec, the field value starts after `data:` with one
   optional leading space; other fields (event:, id:, retry:,
   comments) and blank separator lines are ignored."
  [body-text]
  (keep (fn [line]
          (let [line (str/replace line #"\r$" "")]
            (when (str/starts-with? line "data:")
              (let [v (subs line 5)]
                (if (str/starts-with? v " ") (subs v 1) v)))))
        (str/split-lines body-text)))

(defn- parse-sse-response
  "Aggregate a fully-buffered SSE chat-completions body into the SAME
   shape [[parse-response]] produces — one parse target downstream.

   Each `data: {json}` line is a chat-completion chunk:
   `choices[0].delta.content` fragments concatenate into the text;
   `delta.reasoning_content` is NOT visible content and is dropped
   with a debug log (same semantics as the JSON path's
   reasoning_content); the final data chunk carries `usage`;
   `data: [DONE]` terminates the stream.

   Malformed input (no [DONE] terminator, unparseable JSON line) →
   legible error envelope with the raw body attached, never a throw."
  [body-text]
  (try
    (let [lines  (sse-data-lines body-text)
          [chunks-raw terminated]
          (loop [ls lines acc []]
            (cond
              (empty? ls)             [acc false]
              (= "[DONE]" (first ls)) [acc true]
              :else                   (recur (rest ls) (conj acc (first ls)))))]
      (if-not terminated
        {:seon.ai/text  ""
         :seon.ai/error {:seon.ai/msg      (str "Malformed SSE response: stream has no "
                                                "data: [DONE] terminator ("
                                                (count chunks-raw) " data chunk(s) seen) — "
                                                "truncated or not a chat-completions stream")
                         :seon.ai/raw-body body-text}}
        (let [chunks    (mapv #(js->clj (.parse js/JSON %) :keywordize-keys true)
                              chunks-raw)
              deltas    (keep #(-> % :choices first :delta) chunks)
              text      (apply str (keep :content deltas))
              reasoning (transduce (keep #(some-> % :reasoning_content count))
                                   + 0 deltas)
              finish    (last (keep #(-> % :choices first :finish_reason) chunks))
              usage     (last (keep :usage chunks))]
          (when (and (zero? (count text)) (pos? reasoning))
            (js/console.debug
              (str "seon.ai.deepseek: SSE completion content EMPTY but"
                   " reasoning_content deltas present (" reasoning
                   " chars) — thinking-mode tokens landed in the reasoning"
                   " field; dropping it (same semantics as the JSON path)")))
          {:seon.ai/text                    text
           :seon.ai.deepseek/finish-reason  finish
           :seon.ai/usage                   usage})))
    (catch :default e
      {:seon.ai/text  ""
       :seon.ai/error {:seon.ai/msg      (str "Failed to parse SSE response: "
                                              (error/->message e))
                       :seon.ai/raw-body body-text}})))

(defn- config-error
  "Errors-as-values envelope for a CALL-TIME config gap (no endpoint /
   no key). Never transport-flagged — a config error must not look
   retryable. Logged loudly, returned as a value."
  [label msg]
  (let [err {:seon.ai/msg msg}]
    (ai/log-error! label err)
    {:seon.ai/text "" :seon.ai/error err}))

(defn ^:async complete
  "Send a chat-completions request to the active provider's endpoint
   (see the ns doc — :deepseek default or :openai-compat gateway).
   Returns a Promise of a `:seon.ai.deepseek/complete-response` map.

   Request opts (only :seon.ai/ctx required):
     :seon.ai/ctx           — the full ctx text (required)
     :seon.ai/system-prompt — overrides the store-resident soul
                              (see `seon.ai/effective-system-prompt`)
     :seon.ai/model         — override config row / default-model
     :seon.ai/temperature   — override config row / default-temperature
     :seon.ai/max-tokens    — override config row / default-max-tokens

   Config gaps (no base-url for :openai-compat, no resolvable API key)
   and network/HTTP failures resolve to `{:seon.ai/text \"\"
   :seon.ai/error {…}}` (per spec-02 §2.5: safe-by-default at the
   boundary) — never a throw to the agent loop. Callers destructure
   both `:seon.ai/text` and `:seon.ai/error`."
  {:malli/schema [:=> [:cat :seon.ai.deepseek/complete-request]
                  :seon.ai.deepseek/complete-response]}
  [request]
  (let [compat? (openai-compat?)
        label   (if compat? "OpenAI-compat" "DeepSeek")
        url     (endpoint)
        key     (resolved-api-key)]
    (cond
      (nil? url)
      (config-error
        label
        (str ":openai-compat provider selected but no chat-completions URL "
             "configured — set SEON_AI_BASE_URL (or transact :seon.ai/base-url "
             "on the :seon.ai/config row) to the gateway's FULL "
             "chat-completions URL, e.g. "
             "\"https://gw.example.com/v1/chat/completions\""))

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
      (let [controller (js/AbortController.)
            ms         (or (:seon.ai/timeout-ms (ai/current)) default-timeout-ms)
            timer      (js/setTimeout #(.abort controller) ms)]
        (try
          (let [resp (await (js/fetch url
                              (clj->js
                                {:method  "POST"
                                 :signal  (.-signal controller)
                                 :headers {:Content-Type  "application/json"
                                           :Authorization (str "Bearer " key)}
                                 :body    (body-json request)})))
                body-text (await (.text resp))
                _         (js/clearTimeout timer)
                ;; Some gateways ignore `stream: false` and send SSE
                ;; anyway — branch on the Content-Type header (see the
                ;; SSE section above). Absent header → JSON path.
                sse?      (sse-content-type?
                            (some-> resp .-headers (.get "content-type")))
                result    (cond
                            (not (.-ok resp))
                            {:seon.ai/text  ""
                             :seon.ai/error {:seon.ai/msg    (str label " HTTP " (.-status resp)
                                                                  ": " body-text)
                                             :seon.ai/status (.-status resp)}}

                            sse?  (parse-sse-response body-text)
                            :else (parse-response body-text))]
            (when-let [err (:seon.ai/error result)]
              (ai/log-error! label err))
            result)
          (catch :default e
            (js/clearTimeout timer)
            (let [aborted? (= "AbortError" (some-> e .-name))
                  err      (cond-> {:seon.ai/msg
                                    (if aborted?
                                      (str label " request timed out after " ms
                                           "ms (wall-clock abort — no reply received)")
                                      (str label " fetch failed: " (error/->message e)))}
                             ;; optional = absent — only present (true) on a
                             ;; genuine wall-clock abort, never stored false.
                             aborted?       (assoc :seon.ai/timeout? true)
                             ;; fetch threw with NO abort = network-shaped
                             ;; transport failure — the one retryable class
                             ;; (see the :seon.ai/transport? registration).
                             (not aborted?) (assoc :seon.ai/transport? true))]
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
