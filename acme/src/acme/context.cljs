(ns acme.context
  "Acme's overlay of the agent page's canvas + supporting context tiles
   (Lane-U verification).

   The per-agent page (`/agent/{id}`, rendered by `seon.ui.agent_view` +
   `seon.web.datastar/agent-tile`) has a focal **canvas** — the ONE value
   the agent is currently conveying to its human, wired on
   `:seon.render.canvas/content` and rendered by
   `seon.render/render-agent-canvas`. Below it, a
   `:seon.agent.ctx/priority`-ordered scroll shows every `:seon.agent/ctx`
   block that carries a `:seon.render/html` render, each placed through the
   `seon.render/slot` primitive (decision #19 — the canvas is the
   `:seon.render.canvas/content` pin, NOT a ctx block; a block named
   `:canvas` would just be another supporting tile).

   So a third party shapes an agent's whole page with ZERO src/seon edits,
   using two override surfaces:
   - the focal canvas via the pin attr `:seon.render.canvas/content`
     ([[install-into!]] wires it to `acme.widget/broken-tile` to exercise
     the calm canvas-hero error path through `acme.overrides`'
     `error-response`);
   - the supporting tiles via `seon.agent.ctx/install!` — the SAME primitive
     the core seed uses. Each block's `:seon.render/html` is a qualified
     SYMBOL pointing at one of the acme tile fns below; a block-html fn
     returns BARE hiccup (the block-html contract).

   `install-all!` (scheduled from acme.pod after the pod's agents boot)
   installs the blocks into every live agent's OWN `:seon.agent/ctx` scope
   via `(db/with-agent id (fn [] (ctx/install! …)))`."
  (:require
    [seon.agent.ctx :as ctx]
    [seon.db :as db]
    [seon.log :as log]))

(defn overlay-tile
  "Acme's custom supporting TILE — bare hiccup, appears in the agent page's
   tile scroll below the canvas. Proves a downstream-installed context block
   with an `:seon.render/html` symbol becomes a first-class supporting tile."
  {:malli/schema [:=> [:cat :map] :seon.render.canvas/hiccup]}
  [{:seon.agent/keys [id]}]
  [:div {:class "flex flex-col gap-1 p-3"}
   [:div {:class "text-sm text-text-100"} "Acme context tile"]
   [:div {:class "text-xs text-text-300"}
    "installed via seon.agent.ctx/install! from acme — zero src/seon edits"]
   (when id [:div {:class "text-xs font-mono text-text-500"} id])])

(defn ^:async install-into!
  "Install acme's context blocks into agent `id`'s OWN ctx scope, using the
   seon override primitive `ctx/install!` (idempotent upsert-by-name).

   Exercises BOTH error seams with one throwing tile (`acme.widget/broken-tile`):
   - wired onto `:seon.render.canvas/content` → the focal canvas
     (`seon.render/render-agent-canvas`) throws → the CALM CANVAS-HERO seam
     `seon.render.canvas/error-response` (acme.overrides override);
   - installed as the `:acme-broken` block → the slot path (`render`/`slot`)
     throws → the `seon.render.canvas/error-tile` seam (acme.overrides
     override)."
  [id]
  (db/with-agent id
    (fn []
      (.then
        ;; Wire the canvas pin (`:seon.render.canvas/content` →
        ;; render-agent-canvas → error-response) so the focal canvas hero +
        ;; agent context exercise acme.overrides' error-response override.
        (db/transact!
          {:seon.db/tx-data
           [{:seon.agent/id id
             :seon.render.canvas/content 'acme.widget/broken-tile}]})
        (fn [_]
          (ctx/install!
            [{:seon.agent.ctx/name     :acme-tile
              :seon.agent.ctx/priority 50
              :seon.render/html        'acme.context/overlay-tile}
             ;; The EXISTING acme widget tile (returns an html-RESPONSE MAP,
             ;; the canvas tile-fn contract) installed onto the ctx-block
             ;; `:seon.render/html` slot — does the slot path consume the
             ;; html-response map contract?
             {:seon.agent.ctx/name     :acme-widget
              :seon.agent.ctx/priority 55
              :seon.render/html        'acme.widget/dash}
             {:seon.agent.ctx/name     :acme-broken
              :seon.agent.ctx/priority 60
              :seon.render/html        'acme.widget/broken-tile}]))))))

(defn ^:async install-all!
  "Install acme's context blocks into every live agent. Scheduled from
   acme.pod after boot (the conn + agent roster are up). Best-effort +
   logged — a failure must never take the pod down."
  []
  (try
    (let [db  @db/*conn*
          ids (map first
                   (db/query {:seon.db/db    db
                              :seon.db/query '[:find ?id :where
                                               [?a :seon.agent/id ?id]]}))]
      (log/info-console! "acme.context" "installing acme context blocks"
                         {:agents (vec ids)})
      (doseq [id ids]
        (-> (js/Promise.resolve (install-into! id))
            (.then  (fn [r] (log/info-console! "acme.context" "installed" {:agent id :result r})))
            (.catch (fn [e] (log/error-console! "acme.context" "install failed" e))))))
    (catch :default e
      (log/error-console! "acme.context" "install-all! threw" e))))
