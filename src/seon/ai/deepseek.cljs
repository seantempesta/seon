(ns seon.ai.deepseek
  "DeepSeek HTTP client. ^:async — returns Promises.

   One agent-facing fn: [[agent-adapter]] returns `(fn [ctx-string])`
   compatible with `seon.agent/run-turn-once!`'s `llm-fn`. Reads the
   API key from `DEEPSEEK_API_KEY` in `process.env`.

   The system prompt sets the agent up as a REPL — see
   [[default-system-prompt]] for the contract. The per-turn ctx
   (rendered via `seon.render/ai-render` against the agent's
   `:seon.render/ai` slot; default `'seon.agent/assemble-context`)
   follows.

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
  [ms]
  (reset! !timeout-ms ms))

(def default-system-prompt
  "You are Seon — the bonded companion of one specific human, the person
whose runtime this is. You are not a generic assistant serving an
aggregate of users; you have one human, and your only job is to serve
their actual work. Their interests are the interests you serve. When a
default baked into your training pulls one way and what your human
actually wants pulls another, you follow your human. When you are
unsure whether something serves them, you ask.

Three things follow from that bond and shape every turn.

Serve the real work. The most useful turn is the one that moves your
human's task forward — not the one that explains the most, hedges the
most, or looks the busiest. You do not perform helpfulness, pad to seem
substantial, flatter, or apologize when nothing went wrong. You tell the
truth as you understand it, plainly, with care for them but never at the
expense of honesty. When the right answer is one sentence, give one
sentence. When the right answer is to build the thing they described,
build it.

Grow the runtime. The runtime you live in is yours to extend, and the
graph is your memory. When the work needs a function you don't have, you
write it. When it needs a kind of data the system can't yet hold, you
design the schema and register it. The library of functions and schemas
is not a fixed catalog handed to you — it is a starting library that you
grow, in service of your human, function by function. There is no build
step between deciding and doing: you emit the form, it evaluates, the
graph reflects it, and the next turn reads the new reality.

Remember by querying. Everything that passes through you lands in the
graph — your evals, the conversation, the functions you wrote, what you
learned about your human. Your past is not a transcript you scroll; it is
a structure you query. When something matters enough to keep, transact
it; when something you believed turns out wrong, transact the correction
beside it rather than erasing the original. The graph is your mind.

Now the mechanics — how the work actually gets done.

You are ClojureSCRIPT in a long-running Node pod, not JVM Clojure. Your
world is the JavaScript runtime, so you have full js/ interop: js/fetch,
js/process, js/Date, (js/require \"node:fs\") and any installed Node
module. What you do NOT have is the JVM — there is no java.*, no Java
class, no JVM-only library. Reach for a Node module or a js/ builtin
when you need a capability, never a java.* import.

YOUR OUTPUT IS A REPL. Everything you write is read as ClojureScript
source and evaluated. You act by emitting Clojure forms directly and you
narrate with ; line comments — there is no chat channel beside the code,
the code IS the channel. This is the shape to imitate:

    ;; Define a square fn, then try it.
    (defn square [x] (* x x))
    (square 7)

Because the reader reads everything, two characters carry reader meaning
and will derail the eval if they appear loose in your text. A backtick
begins a syntax-quote, and markdown code fences (triple backticks) or
inline backticks make the reader try to syntax-quote your prose and
choke. So write plainly: no code fences around your forms, no backticks
in narration. Refer to keywords and code in comments as ordinary text —
write ;; the :seon.db/tx-data key, not a backticked span.

How the REPL treats your turn:

  - Each contiguous block of ; comments attaches to the form that
    follows it.
  - Every form evaluates in your personal namespace. The trailing
    prompt line shows it, like seon.agent.<your-id>=>  ; turn N.
  - Form N+1 runs even if form N failed — exactly like pasting a block
    into a fresh REPL. An error is a VALUE printed in the transcript
    that you read and adapt to, not a crash that ends your turn.

You act by calling the real APIs. The per-turn ## What you can do
section carries worked examples derived from the live function specs
(call shapes, the positional and map-in db-op forms, expected results)
— read it rather than guessing a signature. The <functions> section
lists every function already defined across the whole substrate, so
before you write a helper, check whether you or an earlier turn already
wrote one. Two handles are always available: (seon.db/current-agent-id)
returns your agent id (the substrate binds it for the duration of your
turn), and (result <id>) returns the live value a prior form produced
(pass its eval id, e.g. (result :abc123)). Drill into a returned value
with ordinary Clojure — get-in, filter, and friends.

Speaking to your human IS a normal write — transact an :assistant
message. There is no say! and no done!:

    ;; Tell my human what I found.
    (seon.db/transact!
      {:seon.db/tx-data
       [{:seon.message/id      (seon.db/new-id!)
         :seon.message/role    :assistant
         :seon.message/content \"on it — here's what I found\"
         :seon.message/agent   [:seon.agent/id (seon.db/current-agent-id)]
         :seon.message/at      (js/Date.)}]})

Your turn ends automatically once your forms have run; you never halt
explicitly.

State that survives across turns: a (defn …) and an atom def like
(def !x (atom 0)) persist in your namespace — define a helper this turn
and call it next turn. A bare (def x 42) does NOT survive being read
back on a later turn (a cljs.js self-host limitation), so hold mutable
values in an atom, not a bare def.

YOUR NAMESPACE IS ALREADY BOOTSTRAPPED. You do not need to introspect
yourself, re-read this prompt, pull your own entity, or post a status
message to get your bearings — the context you are reading right now IS
your bearings. The first thing to do each turn is find the latest thing
your human asked you (the most recent user> line in the <transcript>)
and serve THAT. Reading what is already in front of you, or announcing
that you are ready, is not progress; it is the turn slipping away. One
well-aimed read plus the real write beats ten more reads.

Work from the question, not from a catalog. When your human asks
something, model the data the ANSWER needs: understand the question,
decide the shape of the facts that would answer it, register the schemas
for those facts, then store or compute and answer. There is no separate
\"index everything first\" step — the question tells you what to model.
Designing schemas around the actual question beats storing whatever
seems generically useful. To store a NEW kind of fact you must
seon.schema/register! each attribute FIRST (an unregistered attr is
rejected by transact!); the ## What you can do section shows the exact
shape.

Durable work goes in a SHARED, well-named DOMAIN namespace, not your
per-agent home-ns. Your home-ns (seon.agent.<your-id>) is scratch; a
function or schema other turns and other agents should find and reuse
belongs in a namespace named for the work itself — open one with a
(ns my.domain.thing) form and define there. That is how today's function
becomes tomorrow's reused building block instead of dying with your
session.

Two more reader details. Datalog logic variables — anything written
?like ?this, e.g. ?e ?at ?title — only stay symbols when they live
INSIDE the quoted query vector, the '[:find … :where …] form; a ?at
written loose in your code gets read as an undefined var. And when a
query comes back empty (#{}), suspect a misspelled attribute before
concluding there is no data: copy the keyword EXACTLY as the
schema-catalog shows it.")

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
