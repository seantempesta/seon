(ns seon.render.media
  "Pure uniform `ns-map -> ns-map` Hiccup renderers for `:seon.media/image` entities,
   mechanically discovered via `seon.render.fit/discover-renderers`."
  (:require [clojure.string :as str]
            [seon.render.links :as rlinks]))

(defn- safe-px [n]
  (if (number? n) (max 0 (long n)) 0))

(defn- nonblank [x]
  (when (and (string? x) (not (str/blank? x)))
    (str/trim x)))

(defn- dom-overflow-x? [dom]
  (boolean
   (when (map? dom)
     (or (:overflow-x? dom) (:overflowX dom)))))

(defn- image-meta-fields [img]
  (let [w (safe-px (:seon.media/width img))
        h (safe-px (:seon.media/height img))
        mime (or (nonblank (:seon.media/mime img)) "image/jpeg")
        lbl (or (nonblank (:seon.media/label img)) (str w "px"))
        dom (:seon.media/dom img)
        ov-x? (dom-overflow-x? dom)
        b64 (nonblank (:seon.media/base64 img))]
    {:id (:seon.media/id img)
     :w w
     :h h
     :dims (str w "×" h)
     :mime mime
     :label lbl
     :dom dom
     :ov-x? ov-x?
     :b64 b64}))

(defn- status-badge [ov-x?]
  (rlinks/badge (if ov-x? "red" "green") (if ov-x? "clipped" "ok")))

(defn render-image-chip
  "Compact clickable chip (`:data-min-width 180`) for `:seon.media/image` showing label,
   dimensions `WxH`, and `ok`/`clipped` overflow status badge."
  {:malli/schema [:=> [:cat [:map [:seon.media/image :seon.media/image]]]
                      [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{img :seon.media/image}]
  (let [{:keys [id dims label ov-x?]} (image-meta-fields img)]
    {:seon.ui/hiccup
     [:span {:class "ent-chip ent-media" :data-min-width 180}
      (rlinks/badge "blue" "img")
      (if id
        (rlinks/ent-link [:seon.media/id id] label "mono strong")
        [:span {:class "mono strong"} label])
      [:span {:class "mono tiny muted"} dims]
      (status-badge ov-x?)]}))

(defn render-image-card
  "Rendered preview card (`:data-min-width 360`) for `:seon.media/image` embedding the live
   `data:<mime>;base64,<b64>` image with width, height, and overflow telemetry."
  {:malli/schema [:=> [:cat [:map [:seon.media/image :seon.media/image]]]
                      [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{img :seon.media/image}]
  (let [{:keys [id dims mime label dom ov-x? b64]} (image-meta-fields img)
        scroll-w (when (map? dom) (or (:scroll-w dom) (:scrollW dom)))]
    {:seon.ui/hiccup
     [:div {:class "card ent-card ent-media" :data-min-width 360}
      [:div {:class "card-head ent-head"}
       [:div {:class "pill-row"}
        (rlinks/badge "blue" "image")
        (if id
          (rlinks/ent-link [:seon.media/id id] label "mono strong")
          [:span {:class "mono strong"} label])
        (status-badge ov-x?)]
       [:span {:class "muted tiny mono ent-meta"} (str dims " · " mime)]]
      (when b64
        [:img {:class "media-preview-img"
               :src (str "data:" mime ";base64," b64)
               :alt (str label " (" dims ")")
               :style {:display "block"
                       :max-width "100%"
                       :height "auto"
                       :border-radius "6px"
                       :border "1px solid var(--base-800)"}}])
      (rlinks/kv-dl [["size" (str dims " px")]
                     ["mime" mime]
                     ["overflow-x" (str ov-x?)]
                     ["scroll-w" (when (number? scroll-w) (str (safe-px scroll-w) "px"))]
                     ["id" (when id (rlinks/ent-link [:seon.media/id id] (str id) "mono tiny"))]])]}))
