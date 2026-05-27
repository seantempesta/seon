(ns seon.ai.deepseek
  "DeepSeek HTTP client. ^:async — returns Promises.

   One agent-facing fn: [[agent-adapter]] returns `(fn [ctx-string])`
   compatible with `seon.agent/run-turn-once!`'s `llm-fn`. Reads the
   API key from `DEEPSEEK_API_KEY` in `process.env`.

   The system prompt sets the agent up as a REPL — see
   [[default-system-prompt]] for the contract. The per-turn ctx
   (rendered via `seon.render/ai-render` against the agent's
   `:seon.render/ai` slot; default `'seon.render.default/ctx`) follows.

   No tool-calling envelope, no streaming — the agent's responses are
   parsed as Clojure forms by `seon.repl/parse-forms`, evaluated as
   a REPL batch by `seon.eval/eval-batch!`."
  (:require [seon.error :as error]
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
   [:seon.ai.deepseek/finish-reason  {:optional true} :string]
   [:seon.ai/usage                   {:optional true} :map]])

;; ============================================================
;; Config — pinned model + endpoint.
;; ============================================================

(def ^:private default-model       "deepseek-chat")
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
  [ms]
  (reset! !timeout-ms ms))

(def default-system-prompt
  "You are a Clojure-fluent agent running inside a CLJS pod on Node.

# Output format — read carefully

Emit Clojure forms DIRECTLY as text. Do NOT wrap them in markdown
code fences. NO ``` clojure ... ```. NO ``` ... ```. NO ~~~. The
parser reads your output as Clojure: a backtick (`) is the
syntax-quote reader macro, so a triple-backtick ` ``` ` reads as
a triple-syntax-quote of whatever follows, producing nonsense
macroexpansions in the eval log.

Correct:

    ;; Define a square function.
    (defn square [x] (* x x))
    ;; Test it.
    (square 7)

INCORRECT (do not do this):

    ```clojure
    (defn square [x] (* x x))
    ```

Use `;` line comments for narration. Each contiguous block of
`;` comments is associated with the form that follows; every form
is evaluated in your personal namespace (shown at the top of every
turn's ctx as `current-ns`). Form N+1 always runs even if N failed
— like pasting a block into a fresh REPL.

You talk to the system by calling the real APIs you'll see worked
examples for in every turn's `## What you can do` section:
`seon.db/transact!`, `seon.db/query`, `seon.db/pull`, `seon.db/entity`.
`(seon.db/current-agent-id)` returns your agent id (the substrate
binds it for the duration of your turn) and `(result :<eval-id>)`
retrieves any prior form's value.

You do not have `say!` or `done!` verbs — those are gone. To message
the user, transact a `:seon.message` entity with `:role :assistant`
(see the worked example). Your turn ends automatically after your
forms run; you don't have to halt explicitly.

You can `(defn fib [n] …)` and call it later — function definitions
persist in your personal ns across turns. Use atoms for stateful
values: `(def !x (atom 0))` then `@!x` works; bare `(def x 42)`
doesn't survive cross-eval reads (a cljs.js limitation, explained in
the `## Conventions + gotchas` section every turn).

Be concise. Narrate what you're about to do, then do it. Look at
`## Recent evals` to see what worked or failed last time — errors are
values you can read and adapt to, not exceptions that crash the
session.")

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

(defn- body-json [{:keys [system-prompt ctx model temperature max-tokens]}]
  (.stringify js/JSON
    (clj->js
      {:model       (or model default-model)
       :messages    [{:role "system" :content (or system-prompt default-system-prompt)}
                     {:role "user"   :content ctx}]
       :temperature (or temperature default-temperature)
       :max_tokens  (or max-tokens default-max-tokens)
       :stream      false})))

(defn- parse-response [body-text]
  (try
    (let [body (js->clj (.parse js/JSON body-text) :keywordize-keys true)
          msg  (-> body :choices first :message :content)]
      {:seon.ai/text                    (or msg "")
       :seon.ai.deepseek/finish-reason  (-> body :choices first :finish_reason)
       :seon.ai/usage                   (-> body :usage)})
    (catch :default e
      {:seon.ai/text  ""
       :seon.ai/error {:seon.ai/msg (str "Failed to parse deepseek response: "
                                         (error/->message e))
                       :seon.ai/raw body-text}})))

(defn ^:async complete
  "Send a completion request to DeepSeek. Returns a Promise of a
   `:seon.ai.deepseek/complete-response` map.

   Request opts (only :seon.ai/ctx required):
     :seon.ai/ctx           — the full ctx text (required)
     :seon.ai/system-prompt — overrides the default agent system prompt
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
                             :body    (body-json {:ctx           ctx
                                                  :system-prompt system-prompt
                                                  :model         model
                                                  :temperature   temperature
                                                  :max-tokens    max-tokens})})))
            body-text (await (.text resp))]
        (js/clearTimeout timer)
        (if (.-ok resp)
          (parse-response body-text)
          {:seon.ai/text  ""
           :seon.ai/error {:seon.ai/msg    (str "DeepSeek HTTP " (.-status resp)
                                                ": " body-text)
                           :seon.ai/status (.-status resp)}}))
      (catch :default e
        (js/clearTimeout timer)
        (let [aborted? (= "AbortError" (some-> e .-name))]
          {:seon.ai/text  ""
           :seon.ai/error {:seon.ai/msg
                           (if aborted?
                             (str "DeepSeek request timed out after " ms "ms")
                             (str "DeepSeek fetch failed: " (error/->message e)))
                           :seon.ai/timeout? aborted?}})))))

;; ============================================================
;; Adapter for seon.agent.
;;
;; seon.agent/run-turn-once! expects (fn [ctx-string]) → Promise of
;; `{:text "..."}`. complete takes a request map and returns a Promise
;; of namespaced keys. This bridges the two.
;; ============================================================

(defn ^:async ^:private complete+wrap
  "Internal — call complete with merged opts, wrap response into the
   shape the turn loop expects."
  [opts ctx-text]
  (let [resp (await (complete (assoc opts :seon.ai/ctx ctx-text)))]
    {:text        (:seon.ai/text resp)
     :seon.ai/raw resp}))

(defn agent-adapter
  "Returns a fn-of-ctx-string suitable for
   `seon.agent/run-turn-once!`'s `llm-fn`. Optional `opts` override
   request defaults (e.g. `{:seon.ai/temperature 0.2}`). The returned
   fn calls `complete` ^:async-internally and returns a Promise of
   `{:text \"…\" :seon.ai/raw <full response>}`."
  ([] (agent-adapter {}))
  ([opts]
   (fn [ctx-text] (complete+wrap opts ctx-text))))
