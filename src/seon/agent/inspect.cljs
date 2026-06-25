(ns seon.agent.inspect
  "Agent self-introspection: 'what am I seeing right now?'

   Two verbs:
     - `ctx-preview` — the FULL prompt the agent would receive on its
       next render: the HARDCODED system block FIRST (read via the SAME
       fn the adapters call, `seon.ai/effective-system-prompt` — the
       system-specific mechanics, NOT the soul/any file), then the
       assembled AI-context (`seon.ctx/context-root` → render). The
       `:seon.render/text` is byte-identical to what the LLM receives
       (system message + context), with an explicit boundary between
       them. Per-section texts (left pane) lead with the system block;
       the per-section html twins (right pane) mirror the context
       sections only (which now include the SOUL.md / AGENTS.md
       file-sections). System block + context derive from the same
       sources the real call uses, so divergence is impossible.
     - `handlers` — the live handler registry visible to the agent
       (core + per-agent).

   All map-in, map-out. Defaults `:seon.agent/id` to
   `(seon.db/current-agent-id)` so REPL calls from inside an agent
   scope work with no argument."
  (:require
    [seon.agent-view :as agent-view]
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.handler :as handler]
    [seon.render :as render]
    [seon.schema :as schema]))

(schema/register! :seon.agent.inspect/request
  [:map [:seon.agent/id {:optional true} :string]])

;; One rendered section of the assembled context — name + the exact
;; text that section contributed to the joined prompt. Consumed by the
;; inspector's left pane so static sections can collapse per-section
;; instead of re-showing the full static bulk on every view.
(schema/register! :seon.agent.inspect/section-text
  [:map
   [:seon.ctx/name :seon.ctx/name]
   [:seon.render/text :string]])

(schema/register! :seon.agent.inspect/ctx-response
  [:map
   [:seon.render/text :string]
   [:seon.render/section-texts [:vector :seon.agent.inspect/section-text]]
   [:seon.render/section-html [:vector :seon.ctx/section-html]]
   [:seon.render/token-estimate :int]])

(schema/register! :seon.agent.inspect/handlers-response
  [:map [:seon.handler/list [:vector :map]]])

(defn- resolve-id
  [id]
  (or id
      (db/current-agent-id)
      (throw (ex-info
               "seon.agent.inspect: no agent-id — pass :seon.agent/id or call inside (seon.db/with-agent id ...)."
               {:seon.agent.inspect/error :no-agent-id}))))

(defn ctx-preview
  "Return the FULL prompt the agent would see on its next render — the
   EXACT bytes the LLM receives: the HARDCODED system block FIRST, then
   the assembled context. The system block is read via the SAME fn the
   adapters call (`seon.ai/effective-system-prompt` — the system-specific
   seon mechanics, NOT the soul/any file; explicit-override logic), so
   the debug text is byte-identical to the real system message; the
   context comes from `seon.ctx/context-root` → render (and now CARRIES
   the SOUL.md / AGENTS.md file-sections). Divergence is impossible — both
   surfaces derive from the same sources the real call uses.
   `:seon.render/text` = system + boundary + context.
   `:seon.render/section-texts` leads with a `:system` section (left pane
   shows the system message too); `:seon.render/section-html` mirrors the
   context section twins only (the system block is the system message,
   not a context section). `:seon.render/token-estimate` counts the WHOLE
   prompt (system included). Reads from the agent's filtered view so
   cross-agent tx are hidden."
  {:malli/schema [:=> [:cat :seon.agent.inspect/request] :seon.agent.inspect/ctx-response]}
  [{:seon.agent/keys [id]}]
  (let [id  (resolve-id id)
        {:seon.db/keys [db]} (agent-view/agent-view {:seon.agent/id id})
        ctx {:seon.agent/id id :seon.db/db db}
        ;; THE SAME render call the prompt path uses (`render-prompt`):
        ;; render the ROOT renderable → a bare String. One render, two
        ;; consumers — the LLM prompt and this human inspector — so they
        ;; can never diverge.
        text          (or (render/render :seon.render/ai ctx (ctx/context-root ctx)) "")
        ;; Per-section breakdown for the panes, derived from the SAME root +
        ;; render (left pane folds per section; right pane one html card per
        ;; renderable).
        {:seon.render/keys [section-texts section-html]} (ctx/ctx-sections ctx)
        ;; Block 1 — the hardcoded system message, via the EXACT fn the
        ;; adapters call (no re-implementation, no drift). No override is
        ;; passed, so this returns the system-specific seon mechanics —
        ;; the normal call's system message.
        system        (ai/effective-system-prompt {})
        ;; The FULL prompt = system + boundary + context, via the SAME fn
        ;; the adapters call so the two debug surfaces can't drift.
        full-text     (ai/debug-full-prompt {:seon.ai/ctx text})]
    {:seon.render/text            full-text
     :seon.render/section-texts   (into [{:seon.ctx/name     :system
                                          :seon.render/text  system}]
                                        section-texts)
     :seon.render/section-html    section-html
     ;; Estimate over the WHOLE prompt — same units as the composer
     ;; (~4 chars/token, via seon.ai.tokens), so the count grows by the
     ;; system-block length.
     :seon.render/token-estimate  (tokens/estimate full-text)}))

(defn handlers
  "Return the live handler registry visible to the agent (core
   handlers + the agent's own, if any), sorted by priority desc."
  {:malli/schema [:=> [:cat :seon.agent.inspect/request] :seon.agent.inspect/handlers-response]}
  [{:seon.agent/keys [id]}]
  (let [id (resolve-id id)
        {:seon.db/keys [db]} (agent-view/agent-view {:seon.agent/id id})
        hs (handler/query-handlers {:seon.agent/id id :seon.db/db db})]
    {:seon.handler/list hs}))
