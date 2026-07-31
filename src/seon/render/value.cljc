(ns seon.render.value
  "Presentation for the one admission-backed structural floor.

  `seon.sci.admit/admit` is the only bounded walk. This namespace presents
  that finite ordinary value as AI text or drillable HTML; it never samples
  the raw value through a second safety codec. The HTML projection keeps the
  quarry's useful affordances: stable path identities, collapsible structure,
  typed summaries, loud elision, opaque markers, breadcrumbs, and path/offset
  handles whose root selector is preserved by the route base.

  A routed drill windows only the value the reader opened before admission.
  Closed debug tabs therefore cost nothing and a large unopened child is only
  summarized by the parent. Raw values are consulted after admission only for
  O(1) count/length summaries and for the already-bounded set of retained path
  identities; presentation never recursively walks an unadmitted tail."
  (:require [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Stable addresses
;;; ---------------------------------------------------------------------------

(defn node-id
  "Stable element id for one root selector and `get-in` path."
  {:malli/schema [:=> [:cat :seon.render/unit :seon.render.data/path]
                  :string]}
  [unit path]
  (let [root-address
        (or (:seon.render.value/root unit)
            (when-some [eid (:db/id unit)] [:db/id eid])
            (when-some [block-name (:seon.render.block/name unit)]
              [:seon.render.block/name block-name])
            :seon.render.value/anonymous)
        address [(:seon.cluster.agent/id unit)
                 root-address
                 path]
        digest (schema/sha-256
                [(.getBytes ^String (pr-str address) "UTF-8")])]
    (str "seon-value-" (subs digest 0 24))))

(defn- encoded
  [value]
  #?(:clj (java.net.URLEncoder/encode (str value) "UTF-8")
     :cljs (js/encodeURIComponent (str value))))

(defn- path-url
  [unit path offset]
  (when-let [base (:seon.render.value/route-base unit)]
    (str base (if (str/includes? base "?") "&" "?")
         "path=" (encoded (pr-str path))
         "&offset=" offset)))

(defn- path-link
  [unit path offset label css-class]
  (if-some [url (path-url unit path offset)]
    [:a {:class css-class :href url} label]
    label))

(defn- path-segment?
  [value]
  (or (nil? value)
      (boolean? value)
      (number? value)
      (string? value)
      (keyword? value)
      (symbol? value)))

;;; ---------------------------------------------------------------------------
;;; Opened-value window
;;; ---------------------------------------------------------------------------

(defn- stable-entries
  [value]
  (cond
    (map? value) (sort-by (comp pr-str first) (seq value))
    (set? value) (map (fn [entry] [entry entry]) (sort-by pr-str value))
    (vector? value) (map-indexed vector value)
    (sequential? value) (map-indexed vector value)
    :else nil))

(defn- counted-size
  [value]
  (when (counted? value)
    (try (count value) (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- opened-window
  [value offset size]
  (try
    (if-let [entries (stable-entries value)]
      (let [head (into [] (comp (drop offset) (take (inc size))) entries)
            more? (> (count head) size)
            page (if more? (pop head) head)
            total (counted-size value)
            page-value
            (cond
              (map? value) (into {} page)
              (set? value) (into #{} (map second) page)
              :else (mapv second page))]
        {:seon.render.value/window page-value
         :seon.render.value/steps (mapv first page)
         :seon.render.value/offset offset
         :seon.render.value/shown (count page)
         :seon.render.value/total total
         :seon.render.value/more? more?})
      {:seon.render.value/window value
       :seon.render.value/steps []
       :seon.render.value/offset 0
       :seon.render.value/shown 0
       :seon.render.value/total nil
       :seon.render.value/more? false})
    (catch #?(:clj Throwable :cljs :default) failure
      {:seon.render.value/window
       {::admit/projection-error (.getName #?(:clj (class failure)
                                              :cljs (type failure)))
        ::admit/name (or (ex-message failure) "realization failed")}
       :seon.render.value/steps []
       :seon.render.value/offset offset
       :seon.render.value/shown 0
       :seon.render.value/total nil
       :seon.render.value/more? false})))

(defn- display-value
  [unit raw caps]
  (if (and (:seon.render.value/route-base unit)
           (:seon.render.data/cursor unit))
    (opened-window raw
                   (long (get-in unit [:seon.render.data/cursor
                                       :seon.render.data/offset] 0))
                   (long (:seon.config.eval.result/max-collection caps)))
    {:seon.render.value/window raw
     :seon.render.value/steps []
     :seon.render.value/offset 0
     :seon.render.value/shown 0
     :seon.render.value/total (counted-size raw)
     :seon.render.value/more? false}))

;;; ---------------------------------------------------------------------------
;;; One admitted projection
;;; ---------------------------------------------------------------------------

(defn- admitted-view
  [unit]
  (when-let [caps (:seon.sci.admit/caps unit)]
    (let [raw (:seon.render/value unit)
          window (display-value unit raw caps)
          admitted
          (admit/admit
           {:seon.sci.admit/value (:seon.render.value/window window)
            :seon.sci.admit/caps caps
            :seon.sci.admit/interrupt-fn (fn [])
            :seon.config/on-core-error :record})]
      (assoc window
             :seon.render.value/raw raw
             :seon.render.value/tree (:seon.sci.admit/value admitted)
             :seon.render.value/truncated?
             (boolean (or (:seon.sci.admit/capped? admitted)
                          (:seon.render.value/more? window)))))))

(defn- summary
  [value]
  (cond
    (map? value) (str "{} " (count value) " keys")
    (set? value) (str "#{} " (count value) " members")
    (vector? value) (str "[] " (count value) " items")
    (and (sequential? value) (counted? value))
    (str "() " (count value) " items")
    (sequential? value) "() sequence"
    (string? value) (str "string · " (tokens/estimate value)
                         " tokens")
    (= ::admit/elided value) "elided"
    (nil? value) "nil"
    :else (str (type value))))

(defn prepare
  "Admit one floor unit once into the finite projection both twins consume."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :nil :seon.render.value/projection]]}
  [unit]
  (when-let [view (admitted-view unit)]
    {:seon.render.value/tree (:seon.render.value/tree view)
     :seon.render.value/summary (summary (:seon.render.value/raw view))
     :seon.render.value/truncated?
     (:seon.render.value/truncated? view)}))

(defn render-html-data
  "Return the admitted plain-data projection unchanged."
  {:malli/schema [:=> [:cat :seon.render.value/projection]
                  :seon.render.value/projection]}
  [projection]
  projection)

(defn render-ai-data
  "Render AI text from one already admitted projection."
  {:malli/schema [:=> [:cat :seon.render.value/projection] :string]}
  [projection]
  (str (pr-str (:seon.render.value/tree projection))
       (when (:seon.render.value/truncated? projection)
         " (elided — this value is larger than the configured caps)")))

;;; ---------------------------------------------------------------------------
;;; HTML presentation over finite admitted data
;;; ---------------------------------------------------------------------------

(defn- marker-map?
  [value]
  (and (map? value)
       (or (contains? value ::admit/opaque)
           (contains? value ::admit/reference)
           (contains? value ::admit/type)
           (contains? value ::admit/projection-error))))

(defn- marker-text
  [value]
  (cond
    (= ::admit/elided value) "‹elided›"
    (::admit/reference value)
    (str "#‹" (::admit/reference value)
         (when-some [marker-name (::admit/name value)]
           (str " " marker-name)) "›")
    (::admit/opaque value)
    (str "#‹" (::admit/opaque value)
         (when-some [marker-name (::admit/name value)]
           (str " " marker-name)) "›")
    (::admit/type value) (str "#‹" (::admit/type value) "›")
    :else (pr-str value)))

(defn- raw-child
  [raw step]
  (cond
    (and (map? raw) (contains? raw step)) (get raw step)
    (and (vector? raw) (integer? step) (< -1 step (count raw)))
    (nth raw step)
    (and (set? raw) (contains? raw step)) step
    :else nil))

(declare html-node)

(defn- leaf
  [unit admitted raw path]
  (cond
    (= ::admit/elided admitted)
    [:span {:class "seon-value-marker"} "‹elided›"]

    (marker-map? admitted)
    [:span {:class "seon-value-marker"} (marker-text admitted)]

    (string? admitted)
    (let [whole-length (when (string? raw) (count raw))
          clipped? (and whole-length (> whole-length (count admitted)))]
      [:span {:class "seon-data-string"}
       admitted
       (when clipped?
         [:span {:class "seon-value-marker"}
          (str "… ⟨" (tokens/estimate raw) " tokens⟩")])
       (when clipped?
         [:span " " (path-link unit path 0 "inspect" "seon-data-step")])])

    (nil? admitted)
    [:span {:class "seon-data-nil"} "nil"]

    :else
    [:span {:class "seon-data-scalar"} (pr-str admitted)]))

(defn- map-node
  [unit admitted raw path depth]
  (let [entries (sort-by (comp pr-str first) (seq admitted))]
    [:details (cond-> {:class "seon-value-node"}
                (< depth 2) (assoc :open "open"))
     [:summary {:class "seon-value-summary"} (summary raw)]
     [:dl {:class "seon-data-map"}
      (map-indexed
       (fn [index [entry-key child]]
         (let [drillable? (path-segment? entry-key)
               child-path (if drillable?
                            (conj path entry-key)
                            (conj path [:seon.render.value/entry index]))
               child-raw (if drillable?
                           (raw-child raw entry-key)
                           child)]
           [:div {:class "seon-data-entry"}
            [:dt {:class "seon-data-key"}
             (if drillable?
               (path-link unit child-path 0 (pr-str entry-key)
                          "seon-data-step")
               (str (pr-str entry-key) " · non-drillable"))]
            [:dd {:class "seon-data-value"}
             (html-node unit child child-raw child-path (inc depth))]]))
       entries)]]))

(defn- sequential-node
  [unit admitted raw path depth steps]
  [:details (cond-> {:class "seon-value-node"}
              (< depth 2) (assoc :open "open"))
   [:summary {:class "seon-value-summary"} (summary raw)]
   [:ol {:class "seon-data-list"}
    (map
     (fn [index child]
       (let [step (or (get steps index) index)
             child-path (conj path step)
             child-raw (raw-child raw step)]
         [:li {:class "seon-data-entry"}
          (path-link unit child-path 0 (pr-str step) "seon-data-step")
          [:span " "]
          (html-node unit child child-raw child-path (inc depth))]))
     (range)
     admitted)]])

(defn- set-node
  [unit admitted raw path depth]
  [:details (cond-> {:class "seon-value-node"}
              (< depth 2) (assoc :open "open"))
   [:summary {:class "seon-value-summary"} (summary raw)]
   [:ul {:class "seon-data-set"}
    (map-indexed
     (fn [index child]
       (let [drillable? (path-segment? child)
             child-path (if drillable?
                          (conj path child)
                          (conj path [:seon.render.value/entry index]))]
         [:li {:class "seon-data-entry"}
          (when drillable?
            (path-link unit child-path 0 (pr-str child) "seon-data-step"))
          [:span " "]
          (html-node unit child child child-path (inc depth))]))
     (sort-by pr-str admitted))]])

(defn- node-content
  [unit admitted raw path depth steps]
  (cond
    (marker-map? admitted) (leaf unit admitted raw path)
    (map? admitted) (map-node unit admitted raw path depth)
    (set? admitted) (set-node unit admitted raw path depth)
    (sequential? admitted)
    (sequential-node unit admitted raw path depth steps)
    :else (leaf unit admitted raw path)))

(defn- html-node
  ([unit admitted raw path depth]
   (html-node unit admitted raw path depth []))
  ([unit admitted raw path depth steps]
   [:div {:id (node-id unit path) :class "seon-value-node-body"}
    (node-content unit admitted raw path depth steps)]))

(defn- breadcrumbs
  [unit path]
  (when (:seon.render.value/route-base unit)
    [:nav {:class "seon-data-crumbs"}
     (path-link unit [] 0 "root" "seon-data-crumb")
     (map
      (fn [index]
        (path-link unit (subvec path 0 (inc index)) 0
                   (pr-str (nth path index)) "seon-data-crumb"))
      (range (count path)))]))

(defn- pager
  [unit path {:seon.render.value/keys [offset shown total more?]} caps]
  (when (:seon.render.value/route-base unit)
    (let [size (long (:seon.config.eval.result/max-collection caps))]
      [:div {:class "seon-data-pager"}
       (when (pos? offset)
         (path-link unit path (max 0 (- offset size))
                    "← previous" "seon-data-page"))
       [:span {:class "seon-data-range"}
        (str "showing " (if (zero? shown) 0 (inc offset))
             "–" (+ offset shown)
             (when total (str " of " total)))]
       (when more?
         (path-link unit path (+ offset shown)
                    "next →" "seon-data-page"))])))

;;; ---------------------------------------------------------------------------
;;; The floor twins
;;; ---------------------------------------------------------------------------

(defn render-ai
  "Render any floor unit as admitted structural text."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (if-let [projection (prepare unit)]
    (render-ai-data projection)
    (str "This projection needs :seon.sci.admit/caps on the unit; without "
         "them nothing bounds what it would say.")))

(defn render-html
  "Render any floor unit as admitted, drillable structural HTML."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (if-let [view (admitted-view unit)]
    (let [path (vec (get-in unit [:seon.render.data/cursor
                                  :seon.render.data/path] []))
          caps (:seon.sci.admit/caps unit)]
      [:div {:id (node-id unit path) :class "seon-data-panel"}
       (breadcrumbs unit path)
       [:div {:class "seon-value-summary"}
        (summary (:seon.render.value/raw view))]
       (pager unit path view caps)
       (node-content unit
                     (:seon.render.value/tree view)
                     (:seon.render.value/raw view)
                     path
                     0
                     (:seon.render.value/steps view))
       (when (:seon.render.value/truncated? view)
         [:p {:class "seon-data-capped"}
          "elided — this value is larger than the configured caps"
          (when (:seon.render.value/route-base unit)
            [:span " · " (path-link unit path 0 "inspect this path"
                                     "seon-data-step")])])])
    [:div {:class "seon-error-card"}
     [:span {:class "seon-error-card-message"}
      (str "This panel needs :seon.sci.admit/caps on the unit; without "
           "them nothing bounds what it would print.")]]))
