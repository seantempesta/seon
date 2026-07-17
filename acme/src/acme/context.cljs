(ns acme.context
  "Acme's overlay of the agent page's canvas and context surfaces.

   The execution child builds the per-agent page's ordinary surface projection
   from `:seon.render.canvas/content` plus the priority-ordered
   `:seon.agent/ctx` blocks carrying `:seon.render/html`.
   `seon.render.surface/materialized` normalizes each result and
   `seon.ui.agent-view/render-agent-view` lays out the selected expanded
   surface with the remaining compact surfaces in the rail. The canvas is the
   `:seon.render.canvas/content` pin, not a context block; a block named
   `:canvas` would remain an ordinary context surface.

   So a third party shapes an agent's whole page with ZERO src/seon edits,
   using two extension surfaces:
   - the focal canvas via the pin attr `:seon.render.canvas/content`
     ([[install-into!]] wires it to `acme.widget/dash`);
   - the supporting surfaces via `seon.agent.ctx/install!` — the SAME primitive
     the core seed uses. Each block's `:seon.render/html` is a qualified
     SYMBOL pointing at one of the Acme surface fns below; a block-html fn
     returns BARE hiccup (the block-html contract).

   `install-all!` (scheduled from acme.pod after the pod's agents boot)
   installs the blocks into every live agent's OWN `:seon.agent/ctx` scope
   via `(db/with-agent id (fn [] (ctx/install! …)))`."
  (:require
    [seon.agent.ctx :as ctx]
    [seon.db :as db]
    [seon.log :as log]))

(defn overlay-surface
  "Render Acme's supporting surface below the canvas.

   Proves a downstream-installed context block with an `:seon.render/html` symbol
   becomes a first-class supporting surface."
  {:malli/schema [:=> [:cat :map] :seon.render.canvas/hiccup]}
  [{:seon.agent/keys [id]}]
  [:div {:class "flex flex-col gap-1 p-3"}
   [:div {:class "text-sm text-text-100"} "Acme context surface"]
   [:div {:class "text-xs text-text-300"}
    "installed via seon.agent.ctx/install! from acme — zero src/seon edits"]
   (when id [:div {:class "text-xs font-mono text-text-500"} id])])

(defn ^:async install-into!
  "Install Acme's context blocks into agent `id`'s own scope.

   Uses the Seon override primitive `ctx/install!` (idempotent upsert-by-name).

   Normal startup installs only healthy downstream renders. Consumers may wire
   `acme.widget/broken-surface` explicitly when exercising the error-response
   override; a deliberate failure is a test fixture, not default page state."
  [id]
  (db/with-agent id
    (fn ^:async install-context []
      (let [removed (await (ctx/remove! :acme-broken))]
        (if (false? (::ctx/ok? removed))
          removed
          (let [canvas
                (await
                  (db/transact!
                    {:seon.db/tx-data
                     [{:seon.agent/id id
                       :seon.render.canvas/content 'acme.widget/dash}]}))]
            (if (:seon.error/message canvas)
              canvas
              (await
                (ctx/install!
                  [{:seon.agent.ctx/name     :acme-surface
                    :seon.agent.ctx/priority 50
                    :seon.render/html        'acme.context/overlay-surface}
                   {:seon.agent.ctx/name     :acme-widget
                    :seon.agent.ctx/priority 55
                    :seon.render/html        'acme.widget/dash}])))))))))

(defn ^:async install-all!
  "Install Acme's context blocks into every live agent.

   Scheduled from acme.pod after boot, when the database session and agent
   roster are available. The roster is read from one immutable database value;
   each agent installation then uses the current database so its sequential
   writes observe the preceding write. Database failures return as data."
  []
  (let [database (await (db/db))]
    (if (:seon.error/message database)
      database
      (let [rows (await
                   (db/query {:seon.db/db database
                              :seon.db/query '[:find ?id :where
                                               [?agent :seon.agent/id ?id]]}))]
        (if (:seon.error/message rows)
          rows
          (let [ids (mapv first rows)]
            (log/info-console! "acme.context" "installing acme context blocks"
                               {:agents ids})
            (letfn [(install-next [remaining]
                      (if-let [id (first remaining)]
                        (-> (install-into! id)
                            (.then
                              (fn [result]
                                (if (or (:seon.error/message result)
                                        (false? (::ctx/ok? result)))
                                  result
                                  (do
                                    (log/info-console!
                                      "acme.context" "installed"
                                      {:agent id :result result})
                                    (install-next (next remaining)))))))
                        (js/Promise.resolve ids)))]
              (await (install-next ids)))))))))
