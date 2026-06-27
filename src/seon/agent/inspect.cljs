(ns seon.agent.inspect
  "Agent self-introspection: 'what am I seeing right now?'

   Two verbs:
     - `ctx-preview` — the FULL prompt the agent would receive on its
       next render: the HARDCODED system block FIRST (read via the SAME
       fn the adapters call, `seon.ai/effective-system-prompt` — the
       system-specific mechanics, NOT the soul/any file), then the
       assembled AI-context via `seon.agent.ctx/render-context` — the SINGLE
       producer the loop's prompt path (`seon.agent.turn/render-prompt`)
       also routes through, over the SAME unfiltered `@*conn*`. The
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
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.agent.ctx :as ctx]
    [seon.db :as db]
    [seon.schema :as schema]))

(schema/register! :seon.agent.inspect/request
  [:map [:seon.agent/id {:optional true} :string]])

;; One rendered section of the assembled context — name + the exact
;; text that section contributed to the joined prompt. Consumed by the
;; inspector's left pane so static sections can collapse per-section
;; instead of re-showing the full static bulk on every view.
(schema/register! :seon.agent.inspect/section-text
  [:map
   [:seon.agent.ctx/name :seon.agent.ctx/name]
   [:seon.render/text :string]])

(schema/register! :seon.agent.inspect/ctx-response
  [:map
   [:seon.render/text :string]
   [:seon.render/section-texts [:vector :seon.agent.inspect/section-text]]
   [:seon.render/section-html [:vector :seon.agent.ctx/block-html]]
   [:seon.render/token-estimate :int]])

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
   context comes from `seon.agent.ctx/context-root` → render (and now CARRIES
   the SOUL.md / AGENTS.md file-sections). Divergence is impossible — both
   surfaces derive from the same sources the real call uses.
   `:seon.render/text` = system + boundary + context.
   `:seon.render/section-texts` leads with a `:system` section (left pane
   shows the system message too); `:seon.render/section-html` mirrors the
   context section twins only (the system block is the system message,
   not a context section). `:seon.render/token-estimate` counts the WHOLE
   prompt (system included). Renders against the live `@*conn*` — the SAME
   unfiltered db value the loop renders the prompt over — so the two are
   byte-identical (no per-agent `d/filter` divergence)."
  {:malli/schema [:=> [:cat :seon.agent.inspect/request] :seon.agent.inspect/ctx-response]}
  [{:seon.agent/keys [id]}]
  (let [id  (resolve-id id)
        ;; THE SAME db the prompt path renders against — the live cluster
        ;; conn, UNFILTERED. The loop renders the prompt over `@*conn*`
        ;; ([[seon.agent.ctx/render-context]] / `render-prompt`); the inspector
        ;; must use the SAME db value or it would not be byte-identical (and
        ;; the old per-agent `d/filter` actively DROPPED inbound peer-message
        ;; content whose datom lived in the peer's tx — the inspector lied).
        db  @db/*conn*
        ctx {:seon.agent/id id :seon.db/db db}
        ;; THE SAME single producer the prompt path uses — both route
        ;; through `seon.agent.ctx/render-context`, so the LLM prompt and this
        ;; human inspector are byte-identical by construction.
        text          (ctx/render-context ctx)
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
     :seon.render/section-texts   (into [{:seon.agent.ctx/name     :system
                                          :seon.render/text  system}]
                                        section-texts)
     :seon.render/section-html    section-html
     ;; Estimate over the WHOLE prompt — same units as the composer
     ;; (~4 chars/token, via seon.ai.tokens), so the count grows by the
     ;; system-block length.
     :seon.render/token-estimate  (tokens/estimate full-text)}))
