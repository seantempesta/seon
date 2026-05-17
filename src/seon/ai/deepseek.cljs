(ns seon.ai.deepseek
  "DeepSeek HTTP client — V0 MVP.

   One fn: `complete`. Takes a ctx string + opts; returns a channel of
   `{:seon.ai/text \"...\"}`. No tool-calling envelope, no streaming.
   The MVP agent loop parses tool-like forms out of the response text
   itself (bash-style) — we don't use deepseek's tool_calls feature
   at all in V0. Future: add streaming when we want the user to see
   `say!` results land progressively from a single LLM completion.

   ## Auth

   Reads `DEEPSEEK_API_KEY` from `process.env`. Throws on first call
   if unset.

   ## Models

   Pinned to `deepseek-chat` (V3) for V0. We'll add the reasoning
   model (`deepseek-reasoner`) once the bash-style parse loop is
   battle-tested — the reasoning model interleaves `<think>` blocks
   in its responses which would need a different parser pass."
  (:require
    [cljs.core.async :as a :refer [chan close! put!]]
    [clojure.string :as str]
    [seon.schema :as schema])
  (:require-macros
    [cljs.core.async :refer [go]]))

;; ============================================================
;; Schemas — the request and response shapes seon.ai.deepseek owns.
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
;; Config — pinned model + endpoint. Override via opts if needed.
;; ============================================================

(def ^:private default-model       "deepseek-chat")
(def ^:private default-endpoint    "https://api.deepseek.com/chat/completions")
(def ^:private default-temperature 0.7)
(def ^:private default-max-tokens  4096)

(def ^:private default-system-prompt
  "You are a Clojure-fluent agent running inside a sandboxed CLJS pod
on Node. Your responses are parsed as a Clojure REPL session:

  - `;; foo` lines are NARRATION shown to both you (next turn) and the
    user (rendered as markdown). Write comments worth reading.
  - The form on the line after a comment block gets EVALUATED in your
    personal namespace.
  - You can do many forms per response — each evaluated serially.

To message the user, call `(seon.agent/say! \"text\")`. To halt your
turn, call `(seon.agent/done!)`. To run another tick without halting,
call `(seon.agent/keep-going)`.

You will see two namespaces rendered in your context every turn:
seon.agent (the runtime) and your personal playground. Everything you
need to know is there. Define new helpers in your playground;
they'll be visible to you next turn.")

;; ============================================================
;; HTTP plumbing.
;;
;; Uses js/fetch (Node 18+ has it native; shadow's :node-script target
;; doesn't bundle a fetch polyfill but Node 18+ doesn't need one).
;; Errors land on the channel as {:seon.ai/error {...}} — caller can
;; pattern-match.
;; ============================================================

(defn- api-key []
  (or (some-> js/process .-env .-DEEPSEEK_API_KEY)
      (throw (ex-info
               "DEEPSEEK_API_KEY not set in process.env"
               {:seon.ai.deepseek/error :missing-api-key}))))

(defn- body-json [{:keys [system-prompt ctx model temperature max-tokens]}]
  (.stringify js/JSON
    (clj->js
      {:model (or model default-model)
       :messages [{:role "system" :content (or system-prompt default-system-prompt)}
                  {:role "user"   :content ctx}]
       :temperature (or temperature default-temperature)
       :max_tokens  (or max-tokens default-max-tokens)
       :stream false})))

(defn- parse-response [body-text]
  (try
    (let [body (js->clj (.parse js/JSON body-text) :keywordize-keys true)
          msg  (-> body :choices first :message :content)]
      {:seon.ai/text                    (or msg "")
       :seon.ai.deepseek/finish-reason  (-> body :choices first :finish_reason)
       :seon.ai/usage                   (-> body :usage)})
    (catch :default e
      {:seon.ai/text  ""
       :seon.ai/error {:seon.ai/msg (str "Failed to parse deepseek response: " e)
                       :seon.ai/raw body-text}})))

(defn complete
  "Send a completion request to DeepSeek. Returns a channel of
   :seon.ai.deepseek/complete-response.

   Request opts (all optional except :seon.ai/ctx):
     :seon.ai/ctx           — the full ctx text (required)
     :seon.ai/system-prompt — overrides the default agent system prompt
     :seon.ai/model         — override default-model (\"deepseek-chat\")
     :seon.ai/temperature   — override default-temperature (0.7)
     :seon.ai/max-tokens    — override default-max-tokens (4096)

   Errors during the HTTP call put `{:seon.ai/text \"\" :seon.ai/error {...}}`
   on the channel. Callers should always destructure both keys."
  {:malli/schema [:=> [:cat :seon.ai.deepseek/complete-request]
                  :seon.ai.deepseek/complete-response]}
  [{:seon.ai/keys [ctx system-prompt model temperature max-tokens]
    :or {model default-model
         temperature default-temperature
         max-tokens default-max-tokens}}]
  (let [out (chan 1)]
    (-> (js/fetch default-endpoint
          (clj->js
            {:method "POST"
             :headers {:Content-Type "application/json"
                       :Authorization (str "Bearer " (api-key))}
             :body (body-json {:ctx ctx
                               :system-prompt system-prompt
                               :model model
                               :temperature temperature
                               :max-tokens max-tokens})}))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (-> (.text resp)
                       (.then (fn [text]
                                (put! out (parse-response text))
                                (close! out))))
                   (-> (.text resp)
                       (.then (fn [text]
                                (put! out {:seon.ai/text ""
                                           :seon.ai/error
                                           {:seon.ai/msg
                                            (str "DeepSeek HTTP "
                                                 (.-status resp) ": " text)
                                            :seon.ai/status (.-status resp)}})
                                (close! out)))))))
        (.catch (fn [e]
                  (put! out {:seon.ai/text ""
                             :seon.ai/error
                             {:seon.ai/msg (str "DeepSeek fetch failed: " e)}})
                  (close! out))))
    out))

;; ============================================================
;; Adapter for seon.agent.
;;
;; seon.agent expects (fn [ctx-string]) → chan of {:text "..."}.
;; deepseek's complete takes a request map and returns a chan of
;; namespaced keys. This adapter bridges the two so we can wire it
;; in seon.client at boot via (agent/set-llm-fn! deepseek-adapter).
;; ============================================================

(defn agent-adapter
  "Returns a fn-of-ctx-string suitable for seon.agent/set-llm-fn!.
   Optional `opts` override request defaults."
  ([] (agent-adapter {}))
  ([opts]
   (fn [ctx-text]
     (let [out (chan 1)]
       (go
         (let [resp (a/<! (complete (assoc opts :seon.ai/ctx ctx-text)))]
           (put! out {:text (:seon.ai/text resp)
                      :seon.ai/raw resp})
           (close! out)))
       out))))
