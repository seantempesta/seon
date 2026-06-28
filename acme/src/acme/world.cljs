(ns acme.world
  "Acme's overrides of the NEW world-layout surface (Lane-U verification).

   The per-agent page (`/agent/{id}` → `seon.ui.world/world-layout`) has a
   focal **canvas** that IS the agent's LIVE TILE (`render-agent-tile`
   resolving `:seon.render.live-tile/content`), above a
   `:seon.agent.ctx/priority`-ordered scroll of every `:seon.agent/ctx` block
   that carries a `:seon.render/html` render, each placed through the
   `seon.render/slot` primitive (decision #19 — the canvas is the live tile,
   NOT a ctx block; a block named `:canvas` is just another supporting tile).

   So a third party shapes an agent's whole world with ZERO src/seon edits,
   using two override surfaces:
   - the focal canvas via the live-tile attr `:seon.render.live-tile/content`
     ([[install-into!]] wires it to `acme.widget/broken-tile` to exercise the
     calm hero error path through `acme.overrides`' `error-response`);
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

(defn world-tile
  "Acme's custom TILE — bare hiccup, appears in the world-layout tile
   scroll below the canvas. Proves a downstream-installed context block
   with an `:seon.render/html` symbol becomes a first-class world tile."
  {:malli/schema [:=> [:cat :map] :seon.render.live-tile/hiccup]}
  [{:seon.agent/keys [id]}]
  [:div {:class "flex flex-col gap-1 p-3"}
   [:div {:class "text-sm text-text-100"} "Acme world tile"]
   [:div {:class "text-xs text-text-300"}
    "installed via seon.agent.ctx/install! from acme — zero src/seon edits"]
   (when id [:div {:class "text-xs font-mono text-text-500"} id])])

(defn ^:async install-into!
  "Install acme's world blocks into agent `id`'s OWN ctx scope, using the
   seon override primitive `ctx/install!` (idempotent upsert-by-name).

   Exercises BOTH error seams with one throwing tile (`acme.widget/broken-tile`):
   - wired onto `:seon.render.live-tile/content` → the focal canvas
     (`render-agent-tile`) throws → the CALM HERO seam
     `live-tile/error-response` (acme.overrides override);
   - installed as the `:acme-broken` block → the slot path (`render`/`slot`)
     throws → the `live-tile/error-tile` seam (acme.overrides override)."
  [id]
  (db/with-agent id
    (fn []
      (.then
        ;; Wire the live-tile contract surface (`:seon.render.live-tile/content`
        ;; → render-agent-tile → error-response) so the focal canvas hero +
        ;; agent context exercise acme.overrides' error-response override.
        (db/transact!
          {:seon.db/tx-data
           [{:seon.agent/id id
             :seon.render.live-tile/content 'acme.widget/broken-tile}]})
        (fn [_]
          ;; MIGRATION (#19): drop the pre-#19 `:canvas` block. The canvas is
          ;; the live tile now (NOT a ctx block), so a persisted `:canvas`
          ;; block renders as a redundant phantom tile. install! keeps blocks
          ;; it doesn't name, so the stale one must be retracted explicitly;
          ;; remove! is a no-op once a store has cycled past the old build.
          (.then
            (ctx/remove! :canvas)
            (fn [_]
              (ctx/install!
                [{:seon.agent.ctx/name     :acme-tile
                  :seon.agent.ctx/priority 50
                  :seon.render/html        'acme.world/world-tile}
                 ;; The EXISTING acme widget tile (returns an html-RESPONSE
                 ;; MAP, the live-tile contract) installed onto the NEW
                 ;; ctx-block/:seon.render/html slot — does the slot path
                 ;; consume the live-tile map contract?
                 {:seon.agent.ctx/name     :acme-widget
                  :seon.agent.ctx/priority 55
                  :seon.render/html        'acme.widget/dash}
                 {:seon.agent.ctx/name     :acme-broken
                  :seon.agent.ctx/priority 60
                  :seon.render/html        'acme.widget/broken-tile}]))))))))

(defn ^:async install-all!
  "Install acme's world blocks into every live agent. Scheduled from
   acme.pod after boot (the conn + agent roster are up). Best-effort +
   logged — a failure must never take the pod down."
  []
  (try
    (let [db  @db/*conn*
          ids (map first
                   (db/query {:seon.db/db    db
                              :seon.db/query '[:find ?id :where
                                               [?a :seon.agent/id ?id]]}))]
      (log/info-console! "acme.world" "installing acme world blocks"
                         {:agents (vec ids)})
      (doseq [id ids]
        (-> (js/Promise.resolve (install-into! id))
            (.then  (fn [r] (log/info-console! "acme.world" "installed" {:agent id :result r})))
            (.catch (fn [e] (log/error-console! "acme.world" "install failed" e))))))
    (catch :default e
      (log/error-console! "acme.world" "install-all! threw" e))))
