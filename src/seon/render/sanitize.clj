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

(def ^:private svg-ratio-attr-re
  #"(?i)(\b(?:cx|cy|x|y|x1|y1|x2|y2|r|rx|ry|dx|dy|width|height|stroke-width|font-size)\s*=\s*[\"'])(-?\d+)/(\d+)([\"'])")

(defn- normalize-svg-ratios [s]
  (if-not (str/includes? s "/")
    s
    (let [fmt-ratio (fn [[full num-s den-s]]
                      (let [n (try (Double/parseDouble num-s) (catch NumberFormatException _ nil))
                            d (try (Double/parseDouble den-s) (catch NumberFormatException _ nil))]
                        (if (and n d (not (zero? d)))
                          (let [v (/ (Math/round (* (/ n d) 100.0)) 100.0)]
                            (if (== v (Math/floor v)) (str (long v)) (str v)))
                          full)))]
      (-> s
          (str/replace svg-ratio-attr-re (fn [[full pre n d suf]] (str pre (fmt-ratio [full n d]) suf)))
          (str/replace #"(?i)(\b(?:points|d|viewBox)\s*=\s*[\"'])([^\"']+)([\"'])"
                       (fn [[_ pre body suf]]
                         (str pre (str/replace body #"(-?\d+)/(\d+)" fmt-ratio) suf)))))))

(def ^:private svg-data-html-re #"(?i)data\s*:\s*text/html")

(def ^:private svg-proto-re #"(?i)(?:javascript|vbscript)\s*:")

(def ^:private svg-on-attr-re #"(?i)\s+on[a-z]+\s*=\s*(?:\"[^\"]*\"|'[^']*'|[^\s>/]+)")

(def ^:private svg-active-self-re #"(?is)<(?:foreignObject|iframe|object|embed)\b[^>]*/\s*>")

(def ^:private svg-active-re #"(?is)<(?:foreignObject|iframe|object|embed)\b[^>]*>.*?</(?:foreignObject|iframe|object|embed)>")

(def ^:private svg-script-self-re #"(?is)<script\b[^>]*/\s*>")

(def ^:private svg-script-re #"(?is)<script\b[^>]*>.*?</script>")

(def ^:private svg-open-re #"(?i)^<svg(?:\s|>|/)")

(defn svg-static-defects
  "Pure static defect checks on `raw-svg` vs `sanitized-svg`, returning `{:defects [...] :svg-normalized? bool}`."
  {:malli/schema [:=> {:examples [[["<svg><script>x()</script></svg>" "<svg></svg>"]
                                   {:defects ["SVG contained <script> (stripped by sanitizer)"] :svg-normalized? true}]
                                  [["<div>nope</div>" nil]
                                   {:defects ["SVG rejected by sanitizer (must start with <svg ...>)"] :svg-normalized? true}]
                                  [["<svg viewBox=\"0 0 10 10\"><rect width=\"5\" height=\"5\"/></svg>"
                                    "<svg viewBox=\"0 0 10 10\"><rect width=\"5\" height=\"5\"/></svg>"]
                                   {:defects [] :svg-normalized? false}]]}
                  [:cat [:maybe :string] [:maybe :string]]
                  [:map [:defects [:vector :string]] [:svg-normalized? :boolean]]]
   :cost/ticks 500000 :cost/bytes 131072}
  [raw-svg sanitized-svg]
  (let [raw-s (when (string? raw-svg) (str/trim raw-svg))
        san-s (when (string? sanitized-svg) (str/trim sanitized-svg))
        defs (cond-> []
               (and (seq raw-s) (empty? san-s)) (conj "SVG rejected by sanitizer (must start with <svg ...>)")
               (and (seq raw-s) (re-find #"(?i)<script\b" raw-s)) (conj "SVG contained <script> (stripped by sanitizer)")
               (and (seq raw-s) (re-find #"(?i)\son[a-z]+\s*=" raw-s)) (conj "SVG contained inline event handler (stripped by sanitizer)")
               (and (seq raw-s) (re-find #"(?i)(?:cx|cy|x|y|x1|y1|x2|y2|width|height|r|rx|ry|d|points)\s*=\s*[\"'][^\"']*-?\d+/\d+" raw-s))
               (conj "SVG contained Clojure ratio literal(s) (auto-normalized to decimals)"))]
    {:defects defs :svg-normalized? (boolean (and (seq raw-s) (not= raw-s san-s)))}))

(defn contains-unevaluated-form?
  "True when parsed EDN Hiccup `node` contains unevaluated Clojure code lists (e.g. `(for ...)`, `(let ...)`)."
  {:malli/schema [:=> {:examples [[[[ :div "ok"]] false]
                                  [[[:svg (list (quote for) '[[x [1]] [:rect]])]] true]]}
                  [:cat :any] :boolean]
   :cost/ticks 500000 :cost/bytes 131072}
  [node]
  (letfn [(scan [x d]
            (cond
              (or (nil? x) (> d 24)) false
              (seq? x) (boolean (or (and (seq x) (symbol? (first x))) (some #(scan % (inc d)) x)))
              (vector? x) (boolean (some #(scan % (inc d)) x))
              (map? x) (boolean (some #(scan % (inc d)) (vals x)))
              :else false))]
    (scan node 0)))

(defn- parse-rgb [s]
  (when (string? s)
    (let [lo (str/lower-case (str/trim s))]
      (cond
        (= lo "white") [255 255 255 1.0]
        (= lo "black") [0 0 0 1.0]
        (str/starts-with? lo "#")
        (let [h (subs lo 1)]
          (try
            (case (count h)
              3 [(Integer/parseInt (str (nth h 0) (nth h 0)) 16)
                 (Integer/parseInt (str (nth h 1) (nth h 1)) 16)
                 (Integer/parseInt (str (nth h 2) (nth h 2)) 16) 1.0]
              6 [(Integer/parseInt (subs h 0 2) 16)
                 (Integer/parseInt (subs h 2 4) 16)
                 (Integer/parseInt (subs h 4 6) 16) 1.0]
              8 [(Integer/parseInt (subs h 0 2) 16)
                 (Integer/parseInt (subs h 2 4) 16)
                 (Integer/parseInt (subs h 4 6) 16)
                 (/ (double (Integer/parseInt (subs h 6 8) 16)) 255.0)]
              nil)
            (catch NumberFormatException _ nil)))
        (str/starts-with? lo "rgb")
        (let [ns (re-seq #"\d+(?:\.\d+)?" lo)]
          (when (>= (count ns) 3)
            (try
              [(long (Math/round (Double/parseDouble (nth ns 0))))
               (long (Math/round (Double/parseDouble (nth ns 1))))
               (long (Math/round (Double/parseDouble (nth ns 2))))
               (if (>= (count ns) 4) (Double/parseDouble (nth ns 3)) 1.0)]
              (catch NumberFormatException _ nil))))
        :else nil))))

(defn- rel-lum [[r g b]]
  (let [lin (fn [c] (let [v (/ (double (max 0 (min 255 c))) 255.0)]
                      (if (<= v 0.03928) (/ v 12.92) (Math/pow (/ (+ v 0.055) 1.055) 2.4))))]
    (+ (* 0.2126 (lin r)) (* 0.7152 (lin g)) (* 0.0722 (lin b)))))

(defn- adapt-svg-fill [tag fill-val attrs]
  (if-not (string? fill-val)
    fill-val
    (if-let [[r g b a :as rgba] (parse-rgb fill-val)]
      (cond
        (and (#{:svg :rect} tag) (>= a 0.85) (>= r 240) (>= g 240) (>= b 240)
             (let [pn (fn [v] (cond (number? v) (double v)
                                    (string? v) (when-let [m (re-find #"^\d+(?:\.\d+)?" (str/trim v))]
                                                  (try (Double/parseDouble m) (catch NumberFormatException _ nil)))
                                    :else nil))
                   w (pn (:width attrs)) h (pn (:height attrs))]
               (not (and w h (< w 48.0) (< h 48.0)))))
        "var(--base-900)"

        (and (#{:text :tspan} tag) (>= a 0.6) (<= (- (max r g b) (min r g b)) 48))
        (let [lum (rel-lum rgba)]
          (cond (< lum 0.08) "var(--text-100)"
                (< lum 0.28) "var(--text-300)"
                :else fill-val))

        :else fill-val)
      fill-val)))

(defn- adapt-svg-style-str [tag s attrs]
  (if-not (string? s)
    s
    (str/replace s #"(?i)(\b(?:background(?:-color)?|fill)\s*:\s*)([^;\"']+)"
                 (fn [[full prop val]]
                   (let [nv (adapt-svg-fill tag val attrs)]
                     (if (= nv val) full (str prop nv)))))))

(defn sanitize-hiccup-with-report
  "Sanitizes an untrusted Hiccup tree against the strict HTML/SVG tag and attribute allowlist,
   returning `{:hiccup clean :dropped [...]}` with up to 12 concrete drop messages."
  {:malli/schema [:function
                  [:=> {:examples [[[[ :div {:class "box" :onclick "evil()"} [:script "x"] "ok"]]
                                    {:hiccup [:div {:class "box"} "ok"]
                                     :dropped ["Stripped disallowed attr :onclick on :div"
                                               "Stripped forbidden tag :script"]}]
                                   [[[:div {:class "ok"} "clean"]]
                                    {:hiccup [:div {:class "ok"} "clean"] :dropped []}]]}
                   [:cat :any] [:map [:hiccup :any] [:dropped [:vector :string]]]]
                  [:=> [:cat :any [:maybe :map]] [:map [:hiccup :any] [:dropped [:vector :string]]]]]
   :cost/ticks 2500000 :cost/bytes 4194304}
  ([node] (sanitize-hiccup-with-report node nil))
  ([node opts]
   (let [actions? (boolean (:allow-seon-actions? opts))
         tags (if actions? safe-action-tags safe-hiccup-tags)
         dropped (volatile! [])
         note! (fn [msg]
                 (let [v @dropped]
                   (when (and (< (count v) 12) (not (some #(= % msg) v)))
                     (vswap! dropped conj msg))))
         clean-attrs
         (fn [btag m]
           (reduce-kv
            (fn [acc k v]
              (let [allowed? (and (simple-keyword? k)
                                  (some? v)
                                  (or (contains? safe-hiccup-attrs k)
                                      (and actions? (contains? safe-action-extra-attrs k))))]
                (if-not allowed?
                  (do (when (some? v) (note! (str "Stripped disallowed attr " k " on " btag))) acc)
                  (case k
                  :href (let [{:keys [kind href]} (classify-url (str v))]
                          (case kind
                            :external (assoc acc :href href :target "_blank" :rel "noopener noreferrer")
                            (:internal :fragment :mailto) (assoc acc :href href)
                            (do (note! (str "Stripped unsafe :href URL on " btag)) acc)))
                  :src (let [{:keys [kind href]} (classify-url (str v))]
                         (if (#{:external :internal} kind)
                           (assoc acc :src href)
                           (do (note! (str "Stripped unsafe :src URL on " btag)) acc)))
                  :on-click (if (and actions? (safe-seon-action? v))
                              (assoc acc :on-click v)
                              (do (note! (str "Stripped disallowed :on-click action on " btag)) acc))
                  :type (if (and actions? (#{"button" :button} v))
                          (assoc acc :type "button")
                          (do (note! (str "Stripped disallowed :type " (pr-str v) " on " btag)) acc))
                  :fill (if (string? v)
                          (assoc acc :fill (adapt-svg-fill btag v m))
                          (if (or (number? v) (boolean? v) (keyword? v))
                            (assoc acc :fill v)
                            (do (note! (str "Stripped invalid value for :fill on " btag)) acc)))
                  :style (cond
                           (string? v) (if (safe-style-str? v)
                                         (assoc acc :style (adapt-svg-style-str btag v m))
                                         (do (note! (str "Stripped unsafe :style on " btag)) acc))
                           (map? v) (let [sm (into {} (keep (fn [[sk sv]]
                                                              (if (and (or (simple-keyword? sk) (string? sk))
                                                                       (or (string? sv) (number? sv) (keyword? sv))
                                                                       (safe-style-str? (str sv)))
                                                                (let [sv' (if (and (string? sv) (#{:fill :background :background-color "fill" "background" "background-color"} sk))
                                                                            (adapt-svg-fill btag sv m) sv)]
                                                                  [sk sv'])
                                                                (do (note! (str "Stripped unsafe :style entry " sk " on " btag)) nil)))) v)]
                                      (if (seq sm) (assoc acc :style sm) acc))
                           :else (do (note! (str "Stripped unsafe :style on " btag)) acc))
                  (if (or (string? v) (number? v) (boolean? v) (keyword? v))
                    (assoc acc k v)
                    (do (note! (str "Stripped invalid value for " k " on " btag)) acc))))))
            {} m))]
     (letfn [(walk [n depth]
               (cond
                 (or (nil? n) (false? n) (> depth 24)) nil
                 (or (string? n) (number? n) (boolean? n)) n
                 (or (keyword? n) (symbol? n)) (str n)
                 (map? n) (do (note! "Stripped raw HTML/map node") nil)
                 (seq? n) (if (and (seq n) (symbol? (first n)))
                          (do (note! (str "Stripped unevaluated Clojure form (" (first n) " ...)")) nil)
                          (let [xs (doall (keep #(walk % (inc depth)) n))] (when (seq xs) xs)))
                 (vector? n)
                 (let [hd (first n)
                       btag (base-tag-kw hd)]
                   (cond
                     (and btag (contains? tags btag))
                     (let [has-attrs? (map? (second n))
                           attrs (when has-attrs? (clean-attrs btag (second n)))
                           kids (keep #(walk % (inc depth)) (if has-attrs? (drop 2 n) (rest n)))]
                       (into (if (seq attrs) [hd attrs] [hd]) kids))
                     (not (keyword? hd))
                     (let [xs (vec (keep #(walk % (inc depth)) n))] (when (seq xs) xs))
                     :else (do (note! (str "Stripped forbidden tag " (or btag hd))) nil)))
                 :else nil))]
       {:hiccup (walk node 0) :dropped @dropped}))))

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
  ([node] (:hiccup (sanitize-hiccup-with-report node nil)))
  ([node opts] (:hiccup (sanitize-hiccup-with-report node opts))))

(defn ^:seon.flow/edge eval-and-sanitize-canvas
  "Safely evaluates a Live Agent Canvas vector or renderer symbol (`(fn [db agent])` or 1-arg `:seon.ui/hiccup`)
   and runs `sanitize-hiccup-with-report` (`:allow-seon-actions? true`)."
  {:malli/schema [:=> [:cat :any :map :any] :map]
   :cost/ticks 10000000}
  [db agent hiccup-or-fn]
  (let [ev (cond
             (vector? hiccup-or-fn) {:ok? true :node hiccup-or-fn}
             (qualified-symbol? hiccup-or-fn)
             (let [v (try (or (when-let [n (find-ns (symbol (namespace hiccup-or-fn)))]
                                (ns-resolve n (symbol (name hiccup-or-fn))))
                              (requiring-resolve hiccup-or-fn))
                          (catch Throwable _ nil))]
               (if-not (and (var? v) (bound? v))
                 {:ok? false :eval-error (str "Unresolved canvas fn: " hiccup-or-fn)}
                 (try
                   (let [als (:arglists (meta v))
                         raw (if (and (seq als) (every? #(= 1 (count %)) als))
                               (@v {:seon.agent/entity agent :db db})
                               (@v db agent))]
                     {:ok? true :node (if (and (map? raw) (contains? raw :seon.ui/hiccup)) (:seon.ui/hiccup raw) raw)})
                   (catch Throwable ex {:ok? false :eval-error (or (.getMessage ex) (str ex))}))))
             :else {:ok? false :eval-error (str "Unsupported canvas target: " (type hiccup-or-fn))})]
    (if-not (:ok? ev)
      {:ok? false :eval-error (:eval-error ev) :hiccup nil :dropped []}
      (let [{:keys [hiccup dropped]} (sanitize-hiccup-with-report (:node ev) {:allow-seon-actions? true})]
        {:ok? true :hiccup hiccup :dropped (vec dropped)}))))

(defn adapt-svg-theme-colors
  "Rewrites hardcoded bright-white SVG card fills (`#fff`/`#f8fafc`/`white`) to `var(--base-900)`
   and dark neutral SVG text fills (`#0f172a`/`#475569`/`#64748b`/`black`) to `var(--text-100)`/`var(--text-300)`
   so inline SVGs maintain >= 7:1 contrast across dark and light themes without altering theme-aware accents."
  {:malli/schema [:=> {:examples [[["<svg><rect width=\"340\" height=\"210\" fill=\"#f8fafc\"/><text fill=\"#0f172a\">Hi</text></svg>"]
                                   "<svg><rect width=\"340\" height=\"210\" fill=\"var(--base-900)\"/><text fill=\"var(--text-100)\">Hi</text></svg>"]]}
                  [:cat [:maybe :string]] [:maybe :string]]
   :cost/ticks 500000 :cost/bytes 2097152}
  [s]
  (when (string? s)
    (str/replace
     s
     #"(?is)<(svg|rect|text|tspan)\b([^>]*)>"
     (fn [[full tag-s attr-s]]
       (let [tag (keyword (str/lower-case tag-s))
             w (second (re-find #"(?i)\bwidth\s*=\s*[\"']([^\"']+)[\"']" attr-s))
             h (second (re-find #"(?i)\bheight\s*=\s*[\"']([^\"']+)[\"']" attr-s))
             am (cond-> {} w (assoc :width w) h (assoc :height h))
             a1 (str/replace attr-s
                             #"(?i)(\bfill\s*=\s*[\"'])([^\"']+)([\"'])"
                             (fn [[f pre v suf]] (let [nv (adapt-svg-fill tag v am)] (if (= nv v) f (str pre nv suf)))))
             a2 (str/replace a1
                             #"(?i)(\bstyle\s*=\s*[\"'])([^\"']+)([\"'])"
                             (fn [[f pre v suf]] (let [nv (adapt-svg-style-str tag v am)] (if (= nv v) f (str pre nv suf)))))]
         (if (= a2 attr-s) full (str "<" tag-s a2 ">")))))))

(defn sanitize-svg
  "Sanitizes an inline `<svg ...>` string by stripping `<script>`, active embedded elements,
   `on*` event attributes, and `javascript:`/`vbscript:`/`data:text/html` URIs, and normalizing
   any Clojure ratio literals (`1153/12` -> `96.08`). Returns `nil` if `s` does not start with `<svg`."
  {:malli/schema [:=> {:examples [[["<svg width=\"40\"><script>alert(1)</script><circle onclick=\"evil()\" cx=\"10\" cy=\"10\" r=\"5\"/></svg>"]
                                   "<svg width=\"40\"><circle cx=\"10\" cy=\"10\" r=\"5\"/></svg>"]
                                  [["not-svg"] nil]]}
                  [:cat [:maybe :string]] [:maybe :string]]
   :cost/ticks 500000 :cost/bytes 2097152}
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (let [trimmed (str/trim s)]
      (when (and (re-find svg-open-re trimmed) (str/includes? trimmed ">"))
        (let [lo (str/lower-case trimmed)
              dirty? (or (str/includes? lo "<script") (str/includes? lo "<foreignobject")
                         (str/includes? lo "<iframe") (str/includes? lo "<object")
                         (str/includes? lo "<embed") (str/includes? lo " on")
                         (str/includes? lo "javascript:") (str/includes? lo "vbscript:")
                         (str/includes? lo "text/html"))
              cleaned (if-not dirty?
                        trimmed
                        (-> trimmed
                            (str/replace svg-script-re "")
                            (str/replace svg-script-self-re "")
                            (str/replace svg-active-re "")
                            (str/replace svg-active-self-re "")
                            (str/replace svg-on-attr-re "")
                            (str/replace svg-proto-re "")
                            (str/replace svg-data-html-re "")
                            str/trim))]
          (when (and (re-find svg-open-re cleaned) (str/includes? cleaned ">"))
            (adapt-svg-theme-colors (normalize-svg-ratios cleaned))))))))
