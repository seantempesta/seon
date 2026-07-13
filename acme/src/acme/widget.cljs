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
                           :seon.render.canvas/content 'acme.widget/dash}]})"
  (:require [acme.helpers :as h]
            [seon.db :as db]))

(def grounded-dims
  "A top-level NON-fn data constant (a set). The `dims` tile below references
   it by simple name from its OWN ns body — the exact case SCI's member
   enumeration missed: `expose-ns` exposed own-ns FNS but not own-ns NON-fn
   `(def …)` data vars, so a tile reading `grounded-dims` threw 'Unable to
   resolve symbol' under SCI and fell to the UNBOUNDED compiled path. With
   `ns-data-members` merged into the SCI ns map, the constant resolves and the
   tile stays interrupt-bounded."
  #{:a :b :c})

(defn dims
  "Live tile that reads the own-ns `grounded-dims` data constant — the BUG
   reproduction + fix proof. Renders `(count grounded-dims)` (3). It must
   render via the SCI-BOUNDED path (no 'could not run under SCI bounding'
   warn), proving own-ns non-fn vars resolve under SCI.

   SPECCED (welcome-tile contract) so it boot-indexes as a `:seon.fn` row,
   making its source available to the SCI-bounding reconstruction path."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_in]
  (let [n (count grounded-dims)]
    {:seon.render/hiccup
     [:div {:class "seon-tile"}
      [:div {:class "seon-tile-compact flex flex-col gap-1 p-3"}
       [:div {:class "text-sm text-text-100"} "Acme grounded dims"]
       [:div {:class "text-xs text-text-300"} (str n " dims")]]
      [:div {:class "seon-tile-expanded flex flex-col gap-3 p-4"}
       [:div {:class "text-lg text-text-50"} "Acme grounded dims"]
       [:div {:class "text-sm text-text-200"} (str "grounded-dims has " n " members")]]]
     :seon.render/ai
     (str "Acme grounded-dims tile — " n " dims (" (pr-str grounded-dims) ").")}))

(defn set-location!
  "Specced product fn — the index/context proof."
  {:malli/schema [:=> [:cat :string] :string]}
  [loc]
  (str "acme location set: " loc))

(defn dash
  "Acme dashboard live tile — derives everything from the db value at
   render time (no stored hiccup). Calls the UNSPECCED `h/format-count`.

   SPECCED (welcome-tile contract) so it boot-indexes as a `:seon.fn`
   row → its source is available to the SCI-bounding path, which is what
   lets the tile exercise the unspecced-helper SCI fix (BUG A): the
   bounded interpreter must resolve `h/format-count` from a required ns
   even though that helper has no `:seon.fn` row of its own."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
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

(defn broken-tile
  "A DELIBERATELY-broken live tile — it throws when rendered. Wire it onto
   an agent to prove `acme.overrides`' `set!` of
   `seon.render.canvas/error-response` is live: instead of seon's stock
   'Updating this panel…' card, the human sees the calm Acme-branded
   'Acme is preparing this view…' card. This is the override seam exercised
   end to end — extend seon without forking it."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_in]
  (throw (js/Error. "acme.widget/broken-tile: deliberately broken for the override demo")))
