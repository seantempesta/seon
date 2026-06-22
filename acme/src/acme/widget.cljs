(ns acme.widget
  "Acme's product surface.

   - `set-location!` — a specced product fn. Proves a third party's own
     source is boot-indexed (`:seon.fn` row + spec) and shown in agent
     context once the preload registers it (BUG B).
   - `dash` — a live tile. It is correctly `:require`-d (so it is NOT the
     'ns declares no alias but body uses one' user-error class), yet it
     calls `h/format-count` — an UNSPECCED helper in a required ns — so it
     reproduces the SCI-bounding miss (BUG A) until `expose-ns` enumerates
     unspecced compiled members.

   Wire the tile onto an agent with:
     (seon.db/transact!
       {:seon.db/tx-data [{:seon.agent/id \"<id>\"
                           :seon.render.live-tile/content 'acme.widget/dash}]})"
  (:require [acme.helpers :as h]
            [seon.db :as db]))

(defn set-location!
  "Specced product fn — the index/context proof."
  {:malli/schema [:=> [:cat :string] :string]}
  [loc]
  (str "acme location set: " loc))

(defn dash
  "Acme dashboard live tile — derives everything from the db value at
   render time (no stored hiccup). Calls the UNSPECCED `h/format-count`."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [n (if db (count (db/installed-schema db)) 0)]
    {:seon.render/hiccup
     [:div {:class "seon-tile"}
      [:div {:class "seon-tile-compact flex flex-col gap-1 p-3"}
       [:div {:class "text-sm text-text-100"} "Acme dashboard"]
       [:div {:class "text-xs text-text-300"} (h/format-count n "installed schema")]]
      [:div {:class "seon-tile-expanded flex flex-col gap-3 p-4"}
       [:div {:class "text-lg text-text-50"} "Acme dashboard"]
       [:div {:class "text-sm text-text-200"} (h/format-count n "installed schema")]
       (when id [:div {:class "text-[10px] font-mono text-text-500"} id])]]
     :seon.render/ai
     (str "Acme dashboard tile — the human sees " (h/format-count n "installed schema") ".")}))
