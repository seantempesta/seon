(ns seon.board
  "General interactive multi-section live data board (`:seon.board/entity`):
   - Thin container shell: routes each section's `:refs` bag straight through `seon.rule.context/derive-bag`
     so the logic rules engine chooses every entity's render function, layout, priority, tree nesting
     (`:view/parent`), and live breadcrumbs (`seon.render/render-tile-node`).
   - Supports Human UI, Split Parity, and Model Wire modes plus a live Entity Inspector (`:seon.board/inspect`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render :as render]
            [seon.render.breadcrumbs :as crumbs]
            [seon.render.markdown :as rmd]
            [seon.rule.context :as rctx]
            [seon.schema :as schema]
            [seon.schema.index :as six]))

(defn active-section
  "Returns the active section map of `board-ent` (matching `:seon.board/active` or the first section)."
  {:malli/schema [:=> [:cat :seon.board/entity] [:maybe :map]]
   :cost/ticks 50000
   :cost/bytes 16384}
  [board-ent]
  (let [secs (vec (:seon.board/sections board-ent))
        act (:seon.board/active board-ent)]
    (or (when act (some #(when (= act (:id %)) %) secs))
        (first secs))))

(defn board-summary
  "Pure metadata summary of a `:seon.board/entity`."
  {:malli/schema [:=> [:cat :seon.board/entity] :map]
   :cost/ticks 50000
   :cost/bytes 16384}
  [board-ent]
  (let [secs (vec (:seon.board/sections board-ent))
        act (active-section board-ent)]
    {:id (:seon.board/id board-ent)
     :title (:seon.board/title board-ent)
     :section-count (count secs)
     :active (:id act)
     :view-as (or (:seon.board/view-as board-ent) :human)
     :inspecting (:seon.board/inspect board-ent)}))

(defn resolve-ref-entity
  "Resolves any Datahike lookup ref `[attr val]` against `db` or `main-db`, attaching `:seon/schema` metadata."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :any] [:maybe :map]]
   :cost/ticks 500000
   :cost/bytes 262144}
  [db main-db ref]
  (when (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)))
    (let [[attr val] ref
          eff-db (or main-db db)
          ix (schema/entity-index eff-db)
          sk (get-in ix [:identity-schemas attr])
          pull-from (fn [adb]
                      (when (and adb (contains? (:schema adb) attr))
                        (when-let [e (:e (first (d/datoms adb :avet attr val)))]
                          (let [ent (dissoc (d/pull adb '[*] e) :db/id)]
                            (when (seq ent)
                              {:ref ref :eid e :schema sk
                               :entity (with-meta ent (when sk {:seon/schema sk}))})))))]
      (or (pull-from db) (when (and main-db (not= main-db db)) (pull-from main-db))))))

(defn- render-inspector [db main-db bid ref opts]
  (when ref
    (let [resolved (resolve-ref-entity db main-db ref)
          eff-db (or main-db db)
          ix (schema/entity-index eff-db)]
      [:section {:id "board-inspector" :class "card board-inspector"}
       [:div {:class "card-head"}
        [:div {:class "pill-row"}
         [:span {:class "badge amber"} "Live Entity Inspector"]
         [:code {:class "mono"} (pr-str ref)]
         (when-let [sk (:schema resolved)] [:span {:class "badge purple mono"} (str sk)])]
        [:button {:type "button" :class "btn sm"
                  :on-click (list 'seon.web/set-board-state! bid {:inspect nil})}
         "× Close"]]
       (if-not resolved
         [:p {:class "muted sm-copy"} (str "Entity " (pr-str ref) " was not found in the live store.")]
         (let [{:keys [schema entity]} resolved
               fs (six/facets-of ix entity)
               wire-h (render/render-value eff-db entity (assoc opts :mode :agent :main-db eff-db :compact? true))
               bc-wire (crumbs/render-breadcrumbs-hiccup eff-db schema entity (assoc opts :mode :agent :main-db eff-db))
               wire-txt (str "### [" (or schema :value) "]\n" (render/hiccup->text wire-h)
                             (when bc-wire (str "\n\n" (render/hiccup->text bc-wire))))]
           [:div {:class "stack-sm"}
            (when (seq fs)
              [:div {:class "pill-row"}
               [:span {:class "muted tiny mono"} "Matched facets:"]
               (for [f fs]
                 [:span {:class "pill mono"}
                  (str (:schema f) " (" (:hits f) " attrs" (when (:drawn? f) ", drawn") ")")])])
            (render/render-value eff-db entity (assoc opts :mode :human :main-db eff-db :board-id bid))
            [:details {:class "fold"}
             [:summary {:class "muted tiny mono"} "Exact Model Wire Projection (same Hiccup → Text)"]
             [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} [:code wire-txt]]]
            (crumbs/render-breadcrumbs-hiccup eff-db schema entity
                                              (assoc opts :mode :human :main-db eff-db
                                                     :board-id bid :lookup-ref ref :suppress-pin? true))]))])))

(defn- render-bag-tile [eff-db tile view-as tile-opts]
  (let [human-h (when (not= :model view-as)
                  (render/render-tile-node eff-db tile (assoc tile-opts :mode :human)))
        wire-txt (when (not= :human view-as)
                   (str "### [" (:schema tile) " · " (:view-fn tile) "]\n"
                        (render/hiccup->text (render/render-tile-node eff-db tile (assoc tile-opts :mode :agent :compact? true)))))]
    (case view-as
      :model
      [:div {:class "card tile-card"}
       [:div {:class "card-head"}
        [:div {:class "pill-row"}
         [:span {:class "badge purple mono"} "Model Wire"]
         [:code {:class "mono tiny"} (pr-str (or (:ref tile) (:id tile)))]]]
       [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} [:code wire-txt]]]

      :split
      [:div {:class "board-split"}
       [:div {:class "board-split-col"}
        [:div {:class "kicker"} "Human HTML (same schema renderer)"]
        human-h]
       [:div {:class "board-split-col"}
        [:div {:class "kicker"} "Model Wire Text (same Hiccup → Text)"]
        [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} [:code wire-txt]]]]

      human-h)))

(defn- render-section-nav [bid secs cur-sec]
  (let [idx (or (first (keep-indexed (fn [i s] (when (= (:id s) (:id cur-sec)) i)) secs)) 0)
        prev-s (when (pos? idx) (nth secs (dec idx)))
        next-s (when (< (inc idx) (count secs)) (nth secs (inc idx)))]
    (when (> (count secs) 1)
      [:div {:class "row-between" :style "padding-top:6px;border-top:1px solid var(--base-800)"}
       (if prev-s
         [:button {:type "button" :class "btn sm"
                   :on-click (list 'seon.web/set-board-state! bid {:active (:id prev-s)})}
          (str "← " (:title prev-s))]
         [:span])
       [:span {:class "muted tiny mono"} (str (inc idx) " / " (count secs))]
       (if next-s
         [:button {:type "button" :class "btn sm primary"
                   :on-click (list 'seon.web/set-board-state! bid {:active (:id next-s)})}
          (str (:title next-s) " →")]
         [:span])])))

(defn render
  "Thin schema renderer (`:seon.render/hiccup`) for `:seon.board/entity`.
   Section entities are projected by `seon.rule.context/derive-bag` — rules choose every item's
   render function, layout, tree nesting, and live breadcrumbs."
  {:malli/schema [:=> [:cat [:map [:db {:optional true} [:maybe :seon.host/db]]
                                  [:main-db {:optional true} [:maybe :seon.host/db]]]
                            :seon.board/entity] :any]
   :cost/ticks 50000000
   :cost/bytes 33554432}
  [{:keys [db main-db mode] :as opts} board-ent]
  (let [eff-db (or main-db db)
        bid (:seon.board/id board-ent)
        live-b (if (and eff-db bid (contains? (:schema eff-db) :seon.board/id))
                 (let [p (d/pull eff-db '[*] [:seon.board/id bid])]
                   (if (:seon.board/id p) p board-ent))
                 board-ent)
        secs (vec (:seon.board/sections live-b))
        cur-sec (active-section live-b)
        view-as (or (:seon.board/view-as live-b) :human)
        inspect-ref (:seon.board/inspect live-b)
        bag (when (and eff-db (seq (:refs cur-sec)))
              (rctx/derive-bag eff-db (:refs cur-sec) {:target (if (= :human (or mode :human)) :html :wire)}))
        tile-opts (cond-> (assoc opts :main-db eff-db :board-id bid)
                    (:try-agent cur-sec) (assoc :try-agent (:try-agent cur-sec)))]
    (if (not= :human (or mode :human))
      [:div {:class "card tile-card"}
       [:h2 (str (:seon.board/title live-b) " (" bid ")")]
       (when-let [sm (:seon.board/summary live-b)] [:p sm])
       [:p (str "Sections: " (str/join ", " (map #(str (:id %) " (" (:title %) ")") secs)))]
       (when cur-sec
         [:div
          [:h3 (str "Active section: " (:title cur-sec))]
          (when-let [sd (:summary cur-sec)] [:p sd])
          (for [tile (take 6 (:tree bag))]
            (render/render-tile-node eff-db tile (assoc tile-opts :mode :agent :compact? true)))])]
      [:div {:class "board-shell stack"}
       [:header {:class "board-head"}
        [:div {:class "row-between"}
         [:div {:class "pill-row"}
          [:span {:class "badge amber mono"} ":seon.board/entity"]
          [:span {:class "muted tiny mono"} (str bid)]]
         [:div {:class "mode-group" :role "group" :aria-label "View parity mode"}
          (for [[m lbl] [[:human "Human UI"] [:split "Split Parity"] [:model "Model Wire"]]]
            [:button {:type "button"
                      :class (str "nav-pill" (when (= view-as m) " active"))
                      :on-click (list 'seon.web/set-board-state! bid {:view-as m})}
             lbl])]]
        [:h1 {:class "canvas-title"} (:seon.board/title live-b)]
        (when-let [sm (:seon.board/summary live-b)]
          (when-not (str/blank? sm)
            [:div {:class "canvas-summary"} (rmd/render-markdown-blocks sm)]))]
       [:nav {:class "board-tabs" :role "tablist"}
        (for [[i s] (map-indexed vector secs)
              :let [active? (= (:id s) (:id cur-sec))]]
          [:button {:type "button"
                    :role "tab"
                    :aria-selected (str (boolean active?))
                    :class (str "board-tab" (when active? " active"))
                    :on-click (list 'seon.web/set-board-state! bid {:active (:id s)})}
           [:span {:class "board-tab-num mono"} (str (inc i))]
           [:span (:title s)]])]
       (when inspect-ref
         (render-inspector db main-db bid inspect-ref opts))
       (when cur-sec
         [:section {:class "board-section stack"}
          [:div {:class "card board-narrative"}
           [:div {:class "row-between"}
            [:h2 (:title cur-sec)]
            [:span {:class "muted tiny mono"} (str (:id cur-sec))]]
           (when-let [sd (:summary cur-sec)]
             [:div {:class "sm-copy" :style "margin-top:6px"} (rmd/render-markdown-blocks sd)])
           (when (seq (:actions cur-sec))
             [:div {:class "pill-row" :style "margin-top:10px"}
              (for [{:keys [label style call]} (:actions cur-sec)]
                [:button {:type "button"
                          :class (str "btn sm" (when (= style :primary) " primary") (when (= style :success) " success"))
                          :on-click call}
                 label])])]
          (if (and (= :grid (:layout cur-sec)) (= :human view-as))
            [:div {:class "board-grid"}
             (for [tile (:tree bag)]
               (render-bag-tile eff-db tile view-as tile-opts))]
            (for [tile (:tree bag)]
              (render-bag-tile eff-db tile view-as tile-opts)))
          (render-section-nav bid secs cur-sec)])])))
