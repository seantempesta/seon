(ns seon.ai.dispatch
  "Effective-provider dispatch for the agent LLM boundary.

   Provider and DiffusionGemma backend selection are read for every call,
   after the agent turn has established its ambient database scope. This
   keeps per-agent provider overrides reactive and prevents a hosted agent
   from retaining an adapter chosen at boot. Missing credentials select the
   deterministic stub; no provider constructor is invoked in that case."
  (:require
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.ai :as ai]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.diffusiongemma :as diffusiongemma]
    [seon.ai.openai-compat :as openai]
    [seon.ai.tokens :as tokens]
    [seon.ai.typeahead :as typeahead]
    [seon.config :as config]
    [seon.db :as db]
    [seon.schema :as schema]))

;; The turn boundary has two intentional call shapes: buffered calls pass the
;; context string, while streaming calls add the stream flag to a request map.
(schema/register! ::request
  [:map
   [:seon.ai/ctx :seon.ai/ctx]
   [:seon.ai/stream? {:optional true} :seon.ai/stream?]])
(schema/register! ::arg [:or :string ::request])
(schema/register! ::llm-fn fn?)

;; `:text` is the established turn-loop adapter result key. Provider adapters
;; may add raw/error fields; the deterministic stub returns only this minimum.
(schema/register! ::stub-response [:map [:text :string]])

(defn stub
  "Return the deterministic no-credentials LLM reply."
  {:malli/schema [:=> [:catn [::arg ::arg]] ::stub-response]}
  [arg]
  (let [ctx  (ai/llm-arg->ctx arg)
        text (str
               ";; stub LLM here — the real one needs DEEPSEEK_API_KEY\n"
               ";; say hello to your human via the message/user function\n"
               "(message/user\n"
               "  "
               (pr-str (str "hello from the stub LLM — saw "
                            (tokens/estimate ctx) " tokens of ctx"))
               ")\n")]
    (.then (.resolve js/Promise nil) (fn [_] {:text text}))))

(defn adapter
  "The agent adapter for the currently effective provider."
  {:malli/schema [:=> [:cat] ::llm-fn]}
  []
  (case (ai/provider)
    :anthropic
    (if (config/anthropic-api-key)
      (anthropic/agent-adapter)
      stub)

    :diffusiongemma
    (case (ai/dg-backend)
      :control (if (diffusiongemma/api-configured?)
                 (diffusiongemma/agent-adapter)
                 stub)
      (if (openai/api-key-configured?)
        (openai/agent-adapter)
        stub))

    :typeahead
    (if (diffusiongemma/api-configured?)
      (typeahead/agent-adapter)
      stub)

    ;; DeepSeek and every OpenAI-compatible gateway share the same adapter.
    (if (openai/api-key-configured?)
      (openai/agent-adapter)
      stub)))

(defn llm-fn
  "Build a per-call dispatching agent LLM function."
  {:malli/schema [:=> [:cat] ::llm-fn]}
  []
  (fn [arg]
    ((adapter) arg)))

;; ============================================================
;; The PLANNER — a FRONTIER model, seeing ONLY the worker's rendered
;; context, writes a step-by-step plan. The worker (possibly a much
;; smaller model) translates it into a plan tree in ONE
;; (my.plan/reconcile! {:my.plan/tree …}) call and executes it.
;;
;; Placement note: the owner named this `seon.ai/generate-plan`, but
;; `seon.ai` is required BY the provider adapters, so it CANNOT require
;; them back (a hard ns cycle). This ns — `seon.ai.dispatch`, the ONE
;; provider-routing owner — already requires `seon.ai` + every provider,
;; and can cleanly add `seon.agent.ctx` (ctx does not require this ns), so
;; the frontier one-shot completion lives here. It reuses the existing
;; provider `complete` surface (no new LLM path) and `frontier-provider?`
;; (no new routing).
;;
;; Frontier-not-worker: the provider + model/temp/max-tokens are resolved
;; from the GLOBAL `:seon.ai/config` row (`resolved-config` with NO agent
;; id), then PINNED into the request — so even when the pod's per-agent
;; worker provider is a tiny model, the planner still calls the frontier
;; model. (Residual: `complete` re-reads api-key/base-url via the ambient
;; agent overlay; generate-plan is a driver-loop tool run OUTSIDE a worker
;; turn, so the ambient scope is nil = global. See the report.)
;; ============================================================

(schema/register! ::ok? :boolean)
(schema/register! ::plan-heredoc :string)
(schema/register! ::error :string)

;; `::goal` is the task goal — the same primitive `:string` shape as
;; `:my.plan/goal` (an unconstrained root-level WHY). Referenced as `:string`
;; here, NOT `:my.plan/goal`: this ns loads (via `seon.agent.runtime`) BEFORE
;; `my.plan`, so a `:my.plan/goal` reference is unregistered at load time.
(schema/register! ::goal :string)
(schema/register! ::generate-plan-request
  [:map
   [::goal         ::goal]
   [:seon.agent/id {:optional true} :seon.agent/id]])

(schema/register! ::generate-plan-response
  [:map
   [::ok?          ::ok?]
   [::plan-heredoc {:optional true} ::plan-heredoc]
   [::error        {:optional true} ::error]])

(def planner-system-prompt
  "The planner meta-prompt (the lever — edit this string to tune plans).

   Sent as the SYSTEM message; the worker's rendered context + the goal
   ride the user message ([[planner-user-text]])."
  (str
    "You are an expert planner for a Seon agent cluster. A WORKER agent "
    "will EXECUTE your plan: it translates the plan into a plan tree in ONE "
    "(my.plan/reconcile! {:my.plan/tree <markdown or nested map>}) call, "
    "then works the steps one at a time, closing each with "
    "(my.plan/done! {:my.plan/id \"<id>\"}).\n\n"
    "You are given the EXACT context that worker sees — its available "
    "functions (the toolkit cards + namespace listings), its home requires, "
    "and its live database state. Plan ONLY with functions that appear in "
    "that context; do NOT invent function names or arguments. If the "
    "context does not expose a capability the goal needs, say so as a step "
    "rather than inventing one.\n\n"
    "Write the plan as a SINGLE markdown document:\n"
    "  - ONE '# <title>' heading naming the overall goal.\n"
    "  - Then a NUMBERED list of steps, in dependency order.\n"
    "  - EACH step names the specific function(s) to call and the argument "
    "shape (namespaced keys), concrete enough that the worker executes it "
    "without guessing — e.g. \"1. Add each book with "
    "(my.kb/remember {:my.kb/... ...}).\"\n\n"
    "Output MARKDOWN ONLY — no preamble, no closing remarks, no code "
    "fences. Start with the '#' heading."))

(defn planner-user-text
  "The user-message body: the worker's rendered context, then the goal.

   `ctx-text` is the byte-exact worker prompt (comment-formatted Clojure);
   the goal + write-instruction ride as `;` prose so the whole message
   reads as eval'able Clojure — the same grammar the worker lives in."
  {:malli/schema [:=> [:catn [::ctx-text :string] [::goal ::goal]]
                  :string]}
  [ctx-text goal]
  (str "; ── The worker's rendered context (its available functions, home\n"
       "; requires, and current db state) — plan ONLY with what appears here:\n\n"
       ctx-text
       "\n\n; ── TASK GOAL for the worker:\n; " goal
       "\n\n; Write the worker's step-by-step plan now (markdown only)."))

(defn heredoc-wrap
  "Wrap `text` as a `#code/<lang> <<SENTINEL … SENTINEL` heredoc literal.

   The parser (`seon.repl.internal`) reads this to a `#code` block value;
   the closer must be a whole line equal to the sentinel, so the sentinel
   is grown until no payload line collides with it."
  {:malli/schema [:=> [:catn [::lang :string] [::text :string]] :string]}
  [lang text]
  (let [lines    (set (str/split-lines text))
        sentinel (loop [s "SEON_PLAN"]
                   (if (contains? lines s) (recur (str s "_X")) s))]
    (str "#code/" lang " <<" sentinel "\n"
         text (when-not (str/ends-with? text "\n") "\n")
         sentinel "\n")))

(defn- exemplar-worker-id
  "A live worker agent's id to render the base worker context from — the
   sorted-first non-terminated agent carrying home requires, or nil when
   none exists. Install-gated so an empty store returns nil, never throws."
  [db]
  (when (contains? (db/installed-schema db) :seon.eval/home-requires)
    (->> (db/query {:seon.db/db    db
                    :seon.db/query '[:find [?id ...]
                                     :where
                                     [?a :seon.agent/id ?id]
                                     [?a :seon.eval/home-requires _]]})
         sort
         (remove (fn [id]
                   (:seon.agent/terminated-at
                     (db/entity {:seon.db/db db
                                 :seon.db/ref [:seon.agent/id id]}))))
         first)))

(defn ^:async generate-plan
  "Have the FRONTIER model write a worker's execution plan for `::goal`.

   The planner sees ONLY the context a worker agent gets ([[ctx/render-context]]
   over `:seon.agent/id` — the passed worker, or the cluster's exemplar
   worker when omitted). The provider + model/temp/max-tokens are PINNED
   from the GLOBAL config row (`resolved-config`, no agent id) so the
   planner calls the frontier model even when the pod's worker provider is
   a tiny one; `frontier-provider?` gates it. Returns the plan wrapped in a
   `#code/markdown` heredoc under `::plan-heredoc` (ready to drop into a
   prompt or stash as a `result/<id>`). Errors-as-values: a non-frontier
   provider, no renderable agent, an LLM failure, or empty text →
   `{::ok? false ::error <directive message>}`; never throws."
  {:malli/schema [:=> [:cat ::generate-plan-request] ::generate-plan-response]}
  [{::keys [goal] agent-id :seon.agent/id}]
  (let [db   @db/*conn*
        cfg  (:seon.ai/resolved-config (ai/resolved-config {:seon.db/db db}))
        prov (:seon.ai/provider cfg)]
    (cond
      (not (ai/frontier-provider? prov))
      {::ok?   false
       ::error (str "generate-plan needs a FRONTIER planner provider, but the "
                    "configured provider is " prov " (a local worker). Select a "
                    "frontier provider via SEON_AI_PROVIDER (deepseek | "
                    "anthropic | openai-compat) — or transact :seon.ai/provider "
                    "on the :seon.ai/config row.")}

      :else
      (let [id (or agent-id (exemplar-worker-id db))]
        (if (nil? id)
          {::ok?   false
           ::error (str "generate-plan: no agent exists to render the worker "
                        "context from. Create an agent (bin/seon …), or pass "
                        ":seon.agent/id of the worker this plan is for.")}
          (let [ctx-text (ctx/render-context {:seon.agent/id id :seon.db/db db})
                req  (cond-> {:seon.ai/ctx           (planner-user-text ctx-text goal)
                              :seon.ai/system-prompt planner-system-prompt}
                       (:seon.ai/model cfg)
                       (assoc :seon.ai/model (:seon.ai/model cfg))
                       ;; anthropic's complete-request carries no temperature.
                       (and (:seon.ai/temperature cfg) (not= :anthropic prov))
                       (assoc :seon.ai/temperature (:seon.ai/temperature cfg))
                       (:seon.ai/max-tokens cfg)
                       (assoc :seon.ai/max-tokens (:seon.ai/max-tokens cfg)))
                complete-fn (if (= :anthropic prov) anthropic/complete openai/complete)
                {:seon.ai/keys [text error]} (await (complete-fn req))]
            (cond
              (some? error)
              {::ok?   false
               ::error (str "generate-plan: frontier LLM call failed — "
                            (or (:seon.error/message error) (pr-str error)))}

              (str/blank? text)
              {::ok?   false
               ::error "generate-plan: frontier LLM returned empty text."}

              :else
              {::ok?          true
               ::plan-heredoc (heredoc-wrap "markdown" (str/trim text))})))))))
