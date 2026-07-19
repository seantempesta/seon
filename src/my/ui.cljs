(ns my.ui
  "Compose static canvas views from dual-render building blocks.

   This namespace turns ordinary data into paired human-facing hiccup and
   compact agent-facing text. It provides layout, status, table, and prose
   components that remain synchronized by construction; persistence and
   interactive behavior belong to the canvas and database namespaces."
  (:require
    [clojure.string :as str]
    [seon.schema :as schema]))

;; SHARED FIELD SHAPES — registered once, referenced by every helper.
;; A canvas piece is label/value text + an optional Phosphor tone; the
;; output is always the `:seon.render/html-response` dual-render envelope
;; (registered in seon.render — referenced, never re-declared).

(schema/register! ::label :string)
(schema/register! ::value :string)
(schema/register! ::title :string)
;; The safelisted accent palette — maps to a `text-*` class. Absent =
;; plain cream text. (One shape; every helper that tints text uses it.)
(schema/register! ::tone
  [:enum :signal :success :error :warning :info])
;; A key/value row pair — `["Adobe CC" "$45"]`.
(schema/register! ::row   [:tuple :string :string])
(schema/register! ::rows  [:vector ::row])
;; Child envelopes `section` stacks — each already a dual-render result.
(schema/register! ::blocks [:vector :seon.render/html-response])
;; A bulleted list is just its strings.
(schema/register! ::items [:vector :string])
;; A progress reading is `current` of `total` — one shared number shape.
(schema/register! ::number  [:or :int :double])
(schema/register! ::current ::number)
(schema/register! ::total   ::number)
;; A table column is `[row-key header]`; cells are already strings (caller
;; formats, exactly like `kv-table` rows), so the dual render stays pure text.
(schema/register! ::column     [:tuple :keyword :string])
(schema/register! ::columns    [:vector ::column])
(schema/register! ::table-data [:vector [:map-of :keyword :string]])

(def ^:private tone->class
  "Tone keyword → safelisted accent class. Default cream when untoned."
  {:signal  "text-signal"
   :success "text-success"
   :error   "text-error"
   :warning "text-warning"
   :info    "text-info"})

(defn- tone->var
  "Tone keyword → the `var(--color-…)` the @theme defines (signal/success/
   error/warning/info all exist), for an inline-styled fill where no `bg-*`
   class is safelisted. Default :signal."
  [tone]
  (str "var(--color-" (name (or tone :signal)) ")"))

;; ── status-line ─────────────────────────────────────────────────────
(schema/register! ::status-line-request
  [:map
   [::label ::label]
   [::value ::value]
   [::tone {:optional true} ::tone]])

(defn ^:seon.fn/agent-facing? status-line
  "One labelled status line: `label: value`, tinted by `tone`.
   The dual render of a single fact.

     (status-line {:my.ui/label \"Status\" :my.ui/value \"All systems go\"
                   :my.ui/tone :success})
     ;; human: a flex row  ·  ai: \"Status: All systems go\""
  {:malli/schema [:=> [:cat ::status-line-request] :seon.render/html-response]}
  [{::keys [label value tone]}]
  {:seon.render/hiccup
   [:div {:class "flex flex-row gap-2 items-center"}
    [:span {:class "text-xs text-text-400"} label]
    [:span {:class (str "text-xs font-semibold "
                        (get tone->class tone "text-text-100"))}
     value]]
   :seon.render/ai (str label ": " value)})

;; ── kv-table ────────────────────────────────────────────────────────
(schema/register! ::kv-table-request
  [:map
   [::title {:optional true} ::title]
   [::rows  ::rows]])

(defn ^:seon.fn/agent-facing? kv-table
  "A two-column key/value table from `rows`, with an optional `title`.

   `rows` is `[[k v] …]`. The dual render of a small breakdown — a styled
   table for the human, aligned `k: v` lines for you.

     (kv-table {:my.ui/title \"Costs\"
                :my.ui/rows [[\"Adobe\" \"$45\"] [\"Netflix\" \"$18\"]]})"
  {:malli/schema [:=> [:cat ::kv-table-request] :seon.render/html-response]}
  [{::keys [title rows]}]
  {:seon.render/hiccup
   (into (if title
           [:div {:class "flex flex-col gap-1"}
            [:h3 {:class "text-xs font-bold text-text-200"} title]]
           [:div {:class "flex flex-col gap-1"}])
         [(into [:table]
                (for [[k v] rows]
                  [:tr
                   [:td {:class "text-xs text-text-400"} k]
                   [:td {:class "text-xs text-text-100 tabular-nums"} v]]))])
   :seon.render/ai
   (str (when title (str title "\n"))
        (str/join "\n" (for [[k v] rows] (str k ": " v))))})

;; ── badge ───────────────────────────────────────────────────────────
(schema/register! ::badge-request
  [:map
   [::label ::label]
   [::tone {:optional true} ::tone]])

(defn ^:seon.fn/agent-facing? badge
  "A small status pill: `label` tinted by `tone` (default :info).

   The dual render of one labelled state: a bordered pill for the human,
   `[tone] label` for you.

     (badge {:my.ui/label \"passing\" :my.ui/tone :success})
     ;; human: a green-text pill  ·  ai: \"[success] passing\""
  {:malli/schema [:=> [:cat ::badge-request] :seon.render/html-response]}
  [{::keys [label tone]}]
  (let [tone (or tone :info)]
    {:seon.render/hiccup
     [:span {:class (str "px-2 py-1 rounded text-2xs font-semibold "
                         "bg-base-850 border border-base-700 "
                         (get tone->class tone "text-text-100"))}
      label]
     :seon.render/ai (str "[" (name tone) "] " label)}))

;; ── bullets ─────────────────────────────────────────────────────────
(schema/register! ::bullets-request
  [:map
   [::title {:optional true} ::title]
   [::items ::items]])

(defn ^:seon.fn/agent-facing? bullets
  "A bulleted list from `items`, with an optional `title`.

   The dual render of a simple list — a semantic `[:ul]` for the human,
   `- item` lines for you.

     (bullets {:my.ui/title \"Next\" :my.ui/items [\"deploy\" \"verify\"]})"
  {:malli/schema [:=> [:cat ::bullets-request] :seon.render/html-response]}
  [{::keys [title items]}]
  {:seon.render/hiccup
   (into (if title
           [:div {:class "flex flex-col gap-1"}
            [:h3 {:class "text-xs font-bold text-text-200"} title]]
           [:div {:class "flex flex-col gap-1"}])
         [(into [:ul] (for [item items] [:li item]))])
   :seon.render/ai
   (str (when title (str title "\n"))
        (str/join "\n" (for [item items] (str "- " item))))})

;; ── progress ────────────────────────────────────────────────────────
(schema/register! ::progress-request
  [:map
   [::label   ::label]
   [::current ::current]
   [::total   ::total]
   [::tone {:optional true} ::tone]])

(defn ^:seon.fn/agent-facing? progress
  "A labelled progress bar showing `current` of `total`.

   The fill is tinted by `tone` (default :signal). The dual render of a
   ratio: a filled bar for the human, `label: current/total (pct%)` for you.

     (progress {:my.ui/label \"Steps\" :my.ui/current 7 :my.ui/total 10})
     ;; human: a 70%-filled bar  ·  ai: \"Steps: 7/10 (70%)\""
  {:malli/schema [:=> [:cat ::progress-request] :seon.render/html-response]}
  [{::keys [label current total tone]}]
  (let [pct (if (pos? total) (js/Math.round (* 100.0 (/ current total))) 0)]
    {:seon.render/hiccup
     [:div {:class "flex flex-col gap-1"}
      [:div {:class "flex flex-row justify-between"}
       [:span {:class "text-xs text-text-400"} label]
       [:span {:class "text-xs text-text-200 tabular-nums"}
        (str current "/" total " (" pct "%)")]]
      [:div {:class "w-full bg-base-800 rounded overflow-hidden"
             :style {:height "6px"}}
       [:div {:class "h-full"
              :style {:width (str pct "%")
                      :background-color (tone->var tone)}}]]]
     :seon.render/ai
     (str label ": " current "/" total " (" pct "%)")}))

;; ── table ───────────────────────────────────────────────────────────
(schema/register! ::table-request
  [:map
   [::title {:optional true} ::title]
   [::columns    ::columns]
   [::table-data ::table-data]])

(defn ^:seon.fn/agent-facing? table
  "A table of N labelled columns, built from rows of cell maps.

   Generalises `kv-table` to N columns.
   `columns` is `[[row-key header] …]`, `table-data` a seq of maps keyed by
   those row-keys (cells already strings, like `kv-table`). The dual render
   of a grid: a styled table for the human, monospace-aligned text rows for
   you.

     (table {:my.ui/columns [[:name \"Name\"] [:cost \"Cost\"]]
             :my.ui/table-data [{:name \"Adobe\" :cost \"$45\"}
                                {:name \"Netflix\" :cost \"$18\"}]})
     ;; ai:
     ;;   Name     Cost
     ;;   Adobe    $45
     ;;   Netflix  $18"
  {:malli/schema [:=> [:cat ::table-request] :seon.render/html-response]}
  [{::keys [title columns table-data]}]
  (let [ks      (map first columns)
        headers (map second columns)
        cell    (fn [row k] (str (get row k)))
        text-rows (cons (vec headers)
                        (for [row table-data] (mapv #(cell row %) ks)))
        widths    (vec (for [i (range (count columns))]
                         (apply max (map #(count (nth % i)) text-rows))))
        pad-row   (fn [cells]
                    (str/trimr
                      (str/join "  "
                        (map (fn [c w] (str c (apply str (repeat (- w (count c)) " "))))
                             cells widths))))]
    {:seon.render/hiccup
     (into (if title
             [:div {:class "flex flex-col gap-1"}
              [:h3 {:class "text-xs font-bold text-text-200"} title]]
             [:div {:class "flex flex-col gap-1"}])
           [(into [:table
                   (into [:tr] (for [h headers]
                                 [:th {:class "text-xs text-text-400 text-left"} h]))]
                  (for [row table-data]
                    (into [:tr] (for [k ks]
                                  [:td {:class "text-xs text-text-100 tabular-nums"}
                                   (cell row k)]))))])
     :seon.render/ai
     (str (when title (str title "\n"))
          (str/join "\n" (map pad-row text-rows)))}))

;; ── section ─────────────────────────────────────────────────────────
(schema/register! ::section-request
  [:map
   [::title  ::title]
   [::blocks ::blocks]])

(defn ^:seon.fn/agent-facing? section
  "COMPOSE child envelopes under one titled container.

   The combinator that keeps the dual render mirrored through nesting.
   Stacks every block's
   `:seon.render/hiccup` and joins every block's `:seon.render/ai`, so the
   composed result is itself a faithful `:seon.render/html-response` you can
   transact onto your canvas (or return from a canvas fn).

     (section {:my.ui/title \"Subscriptions\"
               :my.ui/blocks [(status-line {…}) (kv-table {…})]})"
  {:malli/schema [:=> [:cat ::section-request] :seon.render/html-response]}
  [{::keys [title blocks]}]
  {:seon.render/hiccup
   (into [:div {:class "p-3 flex flex-col gap-2"}
          [:h2 {:class "text-sm font-bold text-signal"} title]]
         (map :seon.render/hiccup blocks))
   :seon.render/ai
   (str title "\n"
        (str/join "\n" (keep :seon.render/ai blocks)))})
