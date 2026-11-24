(ns seon.render.sanitize
  "Single-source allowlist URL classifier and HTML/SVG Hiccup XSS sanitizer shared by
   Interactive Markdown (`seon.render.markdown.parse`) and Live Agent Canvas (`seon.render.surface.presence`)."
  (:require [clojure.string :as str]))

(defn classify-url
  "Classifies a link/image URL into `:internal`, `:external`, `:mailto`, `:fragment`, or `:unsafe`
   (stripping control characters and neutralizing `javascript:`, `data:`, `vbscript:`, and `//...`)."
  {:malli/schema [:=> {:examples [[["/var/seon.web/web-proc"] {:kind :internal :href "/var/seon.web/web-proc"}]
                                  [["https://example.com"] {:kind :external :href "https://example.com"}]
                                  [["javascript:alert(1)"] {:kind :unsafe :href ""}]
                                  [["data:text/html,<h1>x</h1>"] {:kind :unsafe :href ""}]]}
                  [:cat [:maybe :string]] [:map [:kind :keyword] [:href :string]]]
   :cost/ticks 200000 :cost/bytes 131072}
  [raw-url]
  (let [cleaned (-> (str (or raw-url ""))
                    (str/replace #"[\u0000-\u001f\u007f]+" "")
                    str/trim)
        lo (str/lower-case cleaned)]
    (cond
      (str/blank? cleaned) {:kind :unsafe :href ""}
      (and (str/starts-with? cleaned "/") (not (str/starts-with? cleaned "//"))) {:kind :internal :href cleaned}
      (str/starts-with? cleaned "#") {:kind :fragment :href cleaned}
      (or (str/starts-with? lo "https://") (str/starts-with? lo "http://")) {:kind :external :href cleaned}
      (str/starts-with? lo "mailto:") {:kind :mailto :href cleaned}
      :else {:kind :unsafe :href ""})))

(def ^:private safe-hiccup-tags
  #{:div :span :p :a :h1 :h2 :h3 :h4 :h5 :h6 :ul :ol :li :dl :dt :dd
    :table :thead :tbody :tfoot :tr :th :td :caption :blockquote :pre :code
    :strong :b :em :i :del :s :u :small :sub :sup :mark :br :hr
    :details :summary :figure :figcaption :section :article :header :footer
    :nav :aside :meter :progress :time :kbd :abbr :img
    :svg :g :path :circle :ellipse :rect :line :polyline :polygon :text :tspan
    :defs :linearGradient :radialGradient :stop :clipPath :title :desc :marker})

(def ^:private safe-action-tags
  (conj safe-hiccup-tags :button))

(def ^:private safe-hiccup-attrs
  #{:class :id :title :role :colspan :rowspan :open :width :height :alt
    :value :min :max :low :high :optimum :datetime :href :src :target :rel :style
    :aria-label :aria-valuenow :aria-valuemin :aria-valuemax :aria-hidden
    :viewBox :viewbox :xmlns :fill :fill-opacity :fill-rule :stroke :stroke-width
    :stroke-linecap :stroke-linejoin :stroke-dasharray :stroke-opacity :opacity
    :x :y :x1 :y1 :x2 :y2 :cx :cy :r :rx :ry :dx :dy :d :points :transform
    :text-anchor :dominant-baseline :font-size :font-family :font-weight
    :letter-spacing :offset :stop-color :stop-opacity :gradientUnits :preserveAspectRatio})

(def ^:private safe-action-extra-attrs
  #{:type :data-preview-ref :interestfor :on-click})

(def ^:private allowed-seon-web-actions
  #{(quote seon.web/navigate-url!)
    (quote seon.web/inspect-entity!)
    (quote seon.web/inspect-back!)
    (quote seon.web/inspect-forward!)
    (quote seon.web/set-view-mode!)
    (quote seon.web/view-agent!)
    (quote seon.web/send-message!)
    (quote seon.web/toggle-expert-mode!)
    (quote seon.web/eval-snippet!)})

(defn- safe-style-str? [s]
  (let [lo (str/lower-case (str (or s "")))]
    (not (or (str/includes? lo "javascript:") (str/includes? lo "vbscript:")
             (str/includes? lo "expression(") (str/includes? lo "url(")
             (str/includes? lo "@import")))))

(defn- base-tag-kw [tag]
  (when (simple-keyword? tag)
    (let [s (name tag)
          i1 (or (str/index-of s "#") (count s))
          i2 (or (str/index-of s ".") (count s))
          b (subs s 0 (min i1 i2))]
      (when (seq b) (keyword (str/lower-case b))))))

(defn- safe-seon-action? [v]
  (cond
    (symbol? v) (contains? allowed-seon-web-actions v)
    (and (seq? v) (seq v)) (contains? allowed-seon-web-actions (first v))
    :else false))

(defn sanitize-hiccup
  "Sanitizes an untrusted Hiccup tree against a strict HTML/SVG tag and attribute allowlist:
   drops `<script>`, `<iframe>`, `<style>`, `on*`/`data-on*` event attrs, `javascript:`/`data:` URLs,
   unsafe CSS, and raw HTML maps. With `{:allow-seon-actions? true}` (used by Live Agent Canvas),
   also permits `:button` and strictly allowlisted quoted `(seon.web/...)` `:on-click` actions."
  {:malli/schema [:function
                  [:=> {:examples [[[[ :div {:class "box" :onclick "evil()"} [:script "x"] "ok"]]
                                    [:div {:class "box"} "ok"]]
                                   [[[:a {:href "javascript:alert(1)"} "click"]]
                                    [:a "click"]]]}
                   [:cat :any] :any]
                  [:=> [:cat :any [:maybe :map]] :any]]
   :cost/ticks 2500000 :cost/bytes 4194304}
  ([node] (sanitize-hiccup node nil))
  ([node opts]
   (let [actions? (boolean (:allow-seon-actions? opts))
         tags (if actions? safe-action-tags safe-hiccup-tags)
         clean-attrs
         (fn [m]
           (reduce-kv
            (fn [acc k v]
              (let [allowed? (and (simple-keyword? k)
                                  (some? v)
                                  (or (contains? safe-hiccup-attrs k)
                                      (and actions? (contains? safe-action-extra-attrs k))))]
                (if-not allowed?
                  acc
                  (case k
                    :href (let [{:keys [kind href]} (classify-url (str v))]
                            (case kind
                              :external (assoc acc :href href :target "_blank" :rel "noopener noreferrer")
                              (:internal :fragment :mailto) (assoc acc :href href)
                              acc))
                    :src (let [{:keys [kind href]} (classify-url (str v))]
                           (if (#{:external :internal} kind) (assoc acc :src href) acc))
                    :on-click (if (and actions? (safe-seon-action? v)) (assoc acc :on-click v) acc)
                    :type (if (and actions? (#{"button" :button} v)) (assoc acc :type "button") acc)
                    :style (cond
                             (string? v) (if (safe-style-str? v) (assoc acc :style v) acc)
                             (map? v) (let [sm (into {} (keep (fn [[sk sv]]
                                                                (when (and (or (simple-keyword? sk) (string? sk))
                                                                           (or (string? sv) (number? sv) (keyword? sv))
                                                                           (safe-style-str? (str sv)))
                                                                  [sk sv]))) v)]
                                        (if (seq sm) (assoc acc :style sm) acc))
                             :else acc)
                    (if (or (string? v) (number? v) (boolean? v) (keyword? v)) (assoc acc k v) acc)))))
            {} m))]
     (letfn [(walk [n depth]
               (cond
                 (or (nil? n) (false? n) (> depth 24)) nil
                 (or (string? n) (number? n) (boolean? n)) n
                 (or (keyword? n) (symbol? n)) (str n)
                 (map? n) nil
                 (seq? n) (let [xs (vec (keep #(walk % (inc depth)) n))] (when (seq xs) xs))
                 (vector? n)
                 (let [hd (first n)
                       btag (base-tag-kw hd)]
                   (cond
                     (and btag (contains? tags btag))
                     (let [has-attrs? (map? (second n))
                           attrs (when has-attrs? (clean-attrs (second n)))
                           kids (keep #(walk % (inc depth)) (if has-attrs? (drop 2 n) (rest n)))]
                       (into (if (seq attrs) [hd attrs] [hd]) kids))
                     (not (keyword? hd))
                     (let [xs (vec (keep #(walk % (inc depth)) n))] (when (seq xs) xs))
                     :else nil))
                 :else nil))]
       (walk node 0)))))
