(ns seon.handlers.retro-stamp
  "One-shot boot-time pass that stamps `:seon.render/ai` and
   `:seon.render/html` symbols on entities written before the render-
   symbol contract was added (commit 29372b9 + follow-ups).

   ## Why this exists

   The inspector's `seon.render/assemble-ai-context` walks entities by
   the presence of `:seon.render/ai`. Entities written before stamping
   was added at the write site don't carry the symbol, so they're
   silently excluded — opening a pre-existing agent shows an empty
   context even though the data is there.

   This pass runs at `start-agent!` boot, AFTER substrate handlers are
   registered and BEFORE the inspector tx-listener installs. It
   queries entities of each known renderable kind that lack
   `:seon.render/ai` and transacts the stamps in one tx tagged with
   `:seon.db/origin :retro-stamp` so any future audit can isolate
   these from agent-authored or substrate-authored writes.

   ## Idempotency

   The query filters on entities WITHOUT `:seon.render/ai`. Once
   stamped, the entity drops out of the query on the next boot —
   the second `start-agent!` run is a no-op.

   ## Adding a new kind

   Extend `kind->symbols` with the marker attr (some attr the kind
   always carries) and the two render symbols. The query auto-runs."
  (:require
    [seon.db :as db]))

(def ^:private kind->symbols
  "Map of `marker-attr → {:ai sym :html sym}`. Marker attr is something
   every entity of the kind carries; it identifies the kind for
   stamping purposes. Add more here as new renderable kinds land."
  {:seon.eval/source     {:ai 'seon.handlers.eval/render-ai
                          :html 'seon.handlers.eval/render-html}
   :seon.message/content {:ai 'seon.handlers.message/render-ai
                          :html 'seon.handlers.message/render-html}
   :seon.fn/sym          {:ai 'seon.handlers.fn/render-ai
                          :html 'seon.handlers.fn/render-html}
   :seon.schema/key      {:ai 'seon.handlers.schema/render-ai
                          :html 'seon.handlers.schema/render-html}
   :seon.ns/name         {:ai 'seon.handlers.ns/render-ai
                          :html 'seon.handlers.ns/render-html}})

(defn- unstamped-eids
  "Eids of entities carrying `marker-attr` but missing `:seon.render/ai`."
  [marker-attr]
  (->> (db/query
         {:seon.db/query
          [:find '?e
           :where ['?e marker-attr]
           [(list 'missing? '$ '?e :seon.render/ai)]]})
       (map first)))

(defn ^:async run!
  "Stamp render symbols on existing entities lacking them. Returns a
   summary map `{:stamped {<kind> <count>} :total <n>}`. Idempotent —
   re-running stamps zero new entities once the first pass completed."
  {:malli/schema [:=> [:cat] [:map [:stamped :map] [:total :int]]]}
  []
  (let [per-kind
        (into {}
              (for [[marker {:keys [ai html]}] kind->symbols
                    :let [eids (unstamped-eids marker)]
                    :when (seq eids)]
                [marker {:count (count eids)
                         :tx-entries
                         (for [eid eids]
                           {:db/id eid
                            :seon.render/ai ai
                            :seon.render/html html})}]))
        all-entries (mapcat :tx-entries (vals per-kind))
        summary (into {} (for [[k v] per-kind] [k (:count v)]))]
    (if (seq all-entries)
      (await
        (db/with-tx-context {:seon.db/origin :retro-stamp}
          (fn ^:async tx! []
            (await (db/transact! {:seon.db/tx-data (vec all-entries)}))
            {:stamped summary :total (count all-entries)})))
      {:stamped {} :total 0})))
