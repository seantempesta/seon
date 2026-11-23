(ns seon.render.surface.kv-cache
  "Hyperlinked KV-Cache Anatomy & UI Incremental Walk Cache section for `seon.render.surface.context-lab`."
  (:require [seon.render.links :as rlinks :refer [badge kicker mut]]))

(def ^:private walk-unit-vars
  {"seon-css"           'seon.render.ui/css-unit
   "seon-header"        'seon.render.ui/header-unit
   "seon-sidebar"       'seon.render.ui/sidebar-unit
   "seon-view-unit"     'seon.render.ui/view-unit
   "seon-stream-unit"   'seon.render.ui/stream-unit
   "seon-composer-unit" 'seon.render.ui/composer-unit})

(defn- card [id title pills & body]
  (into [:section (cond-> {:class "card stack-sm"} id (assoc :id id))
         [:div {:class "card-head"}
          [:h2 title]
          (when (seq pills) (into [:div {:class "pill-row"}] (remove nil? pills)))]]
        (remove nil? body)))

(defn render-kv-cache-section
  "Renders KV-Cache prefix/delta bar, hyperlinked Prefix-Bust Explainer, clickable turn table,
   and UI incremental `datahike.reads` walk cache table (with clickable component var links)."
  {:malli/schema [:=> [:cat :map :map [:maybe :map]] [:vector :any]]
   :cost/ticks 5000000}
  [kv walk-cache eval-by-turn]
  (let [eval-by-turn (or eval-by-turn {})
        pref (:cached-prefix kv)
        delta (:uncached-delta kv)
        zones (:zones delta)
        tot (max 1 (long (:total-tokens kv 0)))
        seg-w (fn [t] (max 0.0 (min 100.0 (* 100.0 (/ (double (long (or t 0))) (double tot))))))
        turns (:turns kv)
        busts (:busts kv)]
    (card "ctx-kv-cache-unit" "KV-Cache Anatomy — Cached Prefix vs Uncached Turn Delta"
          [(badge (if (>= (double (:overall-hit-pct kv 0.0)) 80.0) "green mono" "amber mono")
                  (str "KV hit " (:overall-hit-pct kv 0.0) "%"))
           (when (:prefix-hash pref) (badge "mono" (str "prefix " (:prefix-hash pref))))
           (badge (if (pos? (count busts)) "red mono" "green mono") (str (count busts) " bust(s)"))
           (badge "purple mono" (str (get-in kv [:already-shown :zero-token-count] 0) " already-shown (0 tok)"))]
          [:div {:class "kv-bar" :title (:summary kv)}
           [:div {:class "kv-seg is-prefix" :style {:width (str (seg-w (:tokens pref)) "%")}
                  :title (str "Cached Prefix: " (:tokens pref 0) " tok (" (:pct-of-total pref 0.0) "%)")}]
           [:div {:class "kv-seg is-history" :style {:width (str (seg-w (get-in zones [:history :tokens])) "%")}
                  :title (str "History: " (get-in zones [:history :tokens] 0) " tok")}]
           [:div {:class "kv-seg is-ephem" :style {:width (str (seg-w (get-in zones [:ephemeral-tail :tokens])) "%")}
                  :title (str "Ephemeral Tail: " (get-in zones [:ephemeral-tail :tokens] 0) " tok")}]
           [:div {:class "kv-seg is-unread" :style {:width (str (seg-w (get-in zones [:unread :tokens])) "%")}
                  :title (str "Unread: " (get-in zones [:unread :tokens] 0) " tok")}]
           [:div {:class "kv-seg is-vision" :style {:width (str (seg-w (get-in zones [:vision :tokens])) "%")}
                  :title (str "Vision: " (get-in zones [:vision :tokens] 0) " tok")}]]
          [:div {:class "pill-row"}
           (badge "green mono" (str "Cached Prefix: " (:tokens pref 0) " tok (" (:pct-of-total pref 0.0) "%)"))
           (badge "blue mono" (str "History: " (get-in zones [:history :tokens] 0) " tok (" (get-in zones [:history :message-count] 0) " msgs)"))
           (badge "amber mono" (str "<ephemeral-tail>: " (get-in zones [:ephemeral-tail :tokens] 0) " tok"))
           (badge "purple mono" (str "Unread: " (get-in zones [:unread :tokens] 0) " tok (" (get-in zones [:unread :count] 0) ")"))
           (when (get-in zones [:vision :present?])
             (badge "muted mono" (str "Vision: " (get-in zones [:vision :tokens] 0) " tok " (get-in zones [:vision :dimensions]))))]
          (when (seq busts)
            [:div {:class "stack-xs"}
             (kicker (str "Prefix-Bust Explainer (" (count busts) ")"))
             (for [b busts
                   :let [f-id (get eval-by-turn (long (:from-turn b 0)))
                         t-id (get eval-by-turn (long (:to-turn b 0)))]]
               [:div {:class "notice red tiny mono pill-row"}
                (rlinks/ent-link (when f-id [:seon.agent.eval/id f-id]) (str "Turn " (:from-turn b)) "mono tiny strong")
                "→"
                (rlinks/ent-link (when t-id [:seon.agent.eval/id t-id]) (str "Turn " (:to-turn b)) "mono tiny strong")
                [:span (str "· " (:from-hash b) " → " (:to-hash b)
                            " (-" (:lost-cache-tokens b 0) " cached tok): " (:reason b))]])])
          (when (seq turns)
            [:div {:class "table-scroll"}
             [:table {:class "data-table"}
              [:thead [:tr [:th "Turn"] [:th "Prefix Hash"] [:th "Prompt"] [:th "Hit"] [:th "Miss"] [:th "Hit %"] [:th "State"]]]
              [:tbody
               (for [t (take-last 10 turns)
                     :let [ev-id (get eval-by-turn (long (:turn t 0)))]]
                 [:tr
                  [:td (rlinks/ent-link (when ev-id [:seon.agent.eval/id ev-id]) (str "T" (:turn t)) "mono tiny strong")]
                  [:td {:class "mono tiny"} (str (or (:prefix-hash t) "—"))]
                  [:td {:class "mono tiny"} (str (:prompt-tokens t 0))]
                  [:td {:class "mono tiny green-text"} (str (:cache-hit-tokens t 0))]
                  [:td {:class "mono tiny muted"} (str (:cache-miss-tokens t 0))]
                  [:td {:class "mono tiny"} (str (:hit-pct t 0.0) "%")]
                  [:td (if (:busted? t) (badge "red" "BUST") (badge "green" "stable"))]])]]])
          [:div {:class "stack-xs"}
           [:div {:class "row-between"}
            (kicker "UI Incremental Walk Cache (datahike.reads)")
            (mut (:summary walk-cache))]
           (when (seq (:units walk-cache))
             [:div {:class "table-scroll"}
              [:table {:class "data-table"}
               [:thead [:tr [:th "Unit"] [:th "Status"] [:th "Reads"] [:th "ms"]]]
               [:tbody
                (for [u (:units walk-cache)
                      :let [uv (get walk-unit-vars (:id u))]]
                  [:tr
                   [:td (rlinks/ent-link (when uv [:seon.code.var/name uv]) (str "#" (:id u)) "mono tiny")]
                   [:td (badge (if (:hit? u) "green" "amber") (if (:hit? u) "HIT" "MISS"))]
                   [:td {:class "mono tiny"} (str (:reads-count u 0))]
                   [:td {:class "mono tiny muted"} (str (:ms u 0.0) " ms")]])]]])])))
