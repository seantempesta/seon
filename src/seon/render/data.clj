(ns seon.render.data
  "The `/data` drill — bounded expansion whose cursor says where to
  resume instead of eliding.

  THE DIFFERENCE FROM A PANEL, and it is the only one: `data-panel`
  bounds a value by ELIDING past the caps, which is right for a value
  nobody asked to explore. A drill bounds the same value by WINDOWING —
  it shows a page and tells you how to get the next one — which is right
  for a value somebody is navigating. Same caps, same walk, same
  refusals; one throws the tail away and one keeps a cursor to it.

  NAVIGATION IS A `get-in` PATH, which is the quarry's own vocabulary
  for this and is deliberately not a new noun. A cursor is a path plus
  an offset: the path says which nested value is on screen, the offset
  says how far into it we are. Both are ordinary data in the URL, so a
  drilled position is a link somebody can send to somebody else, and
  reconnecting to one costs one derivation.

  IT PAYS ONLY FOR WHAT IS OPENED. The window is read at the cursor and
  nothing walks the parts nobody asked for — the architecture's
  `/data` rule (`ui.md`, \"The database browser pays for opened data\"),
  applied to an ordinary value rather than to an index scan. A million-
  element vector costs the same as a ten-element one to display.

  Crash walk: pure. A kill loses a cursor that lives in a URL."
  (:require [clojure.edn :as edn]
            [seon.render.block :as block]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/data.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The cursor
;;; ---------------------------------------------------------------------------

(defn parse-cursor
  "Read a cursor from ordinary query parameters. Total.

  `path` is EDN so a key can be a keyword, a string, or an integer index
  without a second encoding to get wrong; anything unreadable is the
  root, because a broken link should show the top of the value rather
  than an error page. A negative or unreadable offset is zero for the
  same reason."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]]
                  :seon.render.data/cursor]}
  [path offset]
  {:seon.render.data/path
   (let [read (try (edn/read-string (or path "")) (catch Throwable _ nil))]
     (if (vector? read) read []))
   :seon.render.data/offset
   (max 0 (or (when offset (parse-long offset)) 0))})

(defn at
  "The value `cursor`'s path names, or a refusal naming where it broke.

  A path that leaves the value is a legible refusal rather than nil,
  because nil is also a legitimate value to have navigated to and the
  two must not look the same."
  {:malli/schema [:=> [:cat :any :seon.render.data/cursor]
                  [:or [:map {:closed true} [:seon.render.data/value :any]]
                   :seon.error/value]]}
  [value {:keys [:seon.render.data/path]}]
  (reduce (fn [found step]
            (let [inner (:seon.render.data/value found)]
              (cond
                (and (map? inner) (contains? inner step))
                {:seon.render.data/value (get inner step)}

                (and (sequential? inner) (int? step)
                     (< -1 step (count inner)))
                {:seon.render.data/value (nth inner step)}

                (and (set? inner) (contains? inner step))
                {:seon.render.data/value step}

                :else
                (reduced
                 {:seon.error/kind ::no-such-path
                  :seon.error/message (str "There is nothing at " (pr-str step)
                                           " in this value.")
                  :seon.error/data {:seon.render.data/step (pr-str step)}}))))
          {:seon.render.data/value value}
          path))

;;; ---------------------------------------------------------------------------
;;; The window
;;; ---------------------------------------------------------------------------

(defn entries
  "The navigable children of `value` as `[step child]` pairs, ordered.

  ORDER IS DERIVED AND STABLE, because a pager over an unstable order is
  a pager that shows the same row twice and never shows another: map
  keys and set members sort by their printed form, and a sequential's
  steps are its indices. Nothing here stores an order.

  A scalar has no entries, which is how the renderer knows it has
  reached a leaf without asking what a leaf is."
  {:malli/schema [:=> [:cat :any] [:vector [:tuple :any :any]]]}
  [value]
  (cond
    (map? value) (vec (sort-by (comp pr-str first) (seq value)))
    (set? value) (mapv (fn [entry] [entry entry]) (sort-by pr-str value))
    (sequential? value) (vec (map-indexed vector value))
    :else []))

(defn window
  "One page of `value`'s entries at the cursor, plus where to resume.

  THE WHOLE POINT: a bounded read that keeps a cursor to the tail rather
  than discarding it. `:seon.render.data/total` is the honest count —
  a reader must know a window is a window — and `next-offset` is present
  exactly when there IS more, so \"is there a next page?\" is key
  presence rather than arithmetic at every call site.

  The page size is `:seon.config.eval.result/max-collection`, the same
  dial that bounds a panel's width, because it is the same question
  asked by a different consumer."
  {:malli/schema [:=> [:cat :any :seon.render.data/cursor :seon.sci.admit/caps]
                  :seon.render.data/window]}
  [value {:keys [:seon.render.data/offset]} caps]
  (let [all (entries value)
        size (long (:seon.config.eval.result/max-collection caps))
        total (count all)
        start (min offset total)
        end (min (+ start size) total)]
    (cond-> {:seon.render.data/entries (subvec all start end)
             :seon.render.data/offset start
             :seon.render.data/total total}
      (< end total) (assoc :seon.render.data/next-offset end)
      (pos? start) (assoc :seon.render.data/previous-offset
                          (max 0 (- start size))))))

;;; ---------------------------------------------------------------------------
;;; The surface
;;; ---------------------------------------------------------------------------

(defn- link
  [path offset label class]
  [:a {:class class
       :href (str "/data?path=" (java.net.URLEncoder/encode (pr-str path) "UTF-8")
                  "&offset=" offset)}
   label])

(defn- summary
  "What a child IS, in one line, without walking it.
  Walking a child to describe it would defeat the paging — the cost of
  displaying a page must not depend on the size of what it links to."
  [value]
  (cond
    (map? value) (str "{} " (count value) " keys")
    (set? value) (str "#{} " (count value) " members")
    (vector? value) (str "[] " (count value) " items")
    (sequential? value) "() sequence"
    (string? value) (let [text (str value)]
                      (if (> (count text) 60)
                        (str (subs text 0 60) "…")
                        text))
    (nil? value) "nil"
    :else (pr-str value)))

(defn- navigable?
  [value]
  (or (map? value) (set? value) (sequential? value)))

(defn drill-html
  "`:seon.render/html` — one page of one nested value, with its cursor.

  Reads `:seon.render.data/cursor` from the unit and the value from
  `:seon.render/value`. Breadcrumbs are the path's own prefixes, so
  navigating back up needs no history and no stored trail — the path IS
  the trail."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [caps (:seon.sci.admit/caps unit)
        cursor (:seon.render.data/cursor unit)]
    (if-not (and caps cursor)
      [:div {:class "seon-error-card"}
       [:span {:class "seon-error-card-message"}
        "This drill needs :seon.sci.admit/caps and a cursor on the unit."]]
      (let [path (:seon.render.data/path cursor)
            found (at (:seon.render/value unit) cursor)]
        (if-let [failure (:seon.error/kind found)]
          [:div {:id (block/surface-id :data) :class "seon-error-card"}
           [:span {:class "seon-error-card-name"} (str failure)]
           [:span {:class "seon-error-card-message"}
            (:seon.error/message found)]]
          (let [value (:seon.render.data/value found)
                page (window value cursor caps)]
            [:div {:id (block/surface-id :data) :class "seon-data-drill"}
             [:nav {:class "seon-data-crumbs"}
              (link [] 0 "root" "seon-data-crumb")
              (for [index (range (count path))]
                (link (subvec path 0 (inc index)) 0
                      (pr-str (nth path index)) "seon-data-crumb"))]
             (if (navigable? value)
               [:div {:class "seon-data-window"}
                [:p {:class "seon-data-range"}
                 (let [start (:seon.render.data/offset page)
                       shown (count (:seon.render.data/entries page))]
                   (str "showing " (if (zero? shown) 0 (inc start))
                        "–" (+ start shown)
                        " of " (:seon.render.data/total page)))]
                [:dl {:class "seon-data-map"}
                 (for [[step child] (:seon.render.data/entries page)]
                   [:div {:class "seon-data-entry"}
                    [:dt {:class "seon-data-key"}
                     (if (navigable? child)
                       (link (conj path step) 0 (pr-str step) "seon-data-step")
                       (pr-str step))]
                    [:dd {:class "seon-data-value"} (summary child)]])]
                [:div {:class "seon-data-pager"}
                 (when-let [previous (:seon.render.data/previous-offset page)]
                   (link path previous "← previous" "seon-data-page"))
                 (when-let [next-offset (:seon.render.data/next-offset page)]
                   (link path next-offset "next →" "seon-data-page"))]]
               [:pre {:class "seon-data-leaf"} (pr-str value)])]))))))
