(ns my.ui
  "COMPOSE your canvas from small dual-render pieces — don't hand-roll a
   `[:div …]` from scratch. Every helper here takes DATA and returns the
   `:seon.render/html-response` envelope your live tile wants:

     {:seon.render/hiccup […]   ; the HUMAN's view — styled, safelisted
      :seon.render/ai    \"…\"}    ; YOUR view — the SAME info, compact text

   The two are mirrored from ONE input, so they CAN'T drift: the agent
   reasons over the lean `:seon.render/ai` line it sees every turn (the
   `; Your live tile` section renders it), while the human gets the
   beautiful HTML. Neither lies about the other.

   THE MOVE — set your canvas by COMPOSING, then wire the result. Build
   pieces, stack them in a `section`, transact the section's hiccup:

     (let [s (section
               {:my.ui/title \"Subscriptions\"
                :my.ui/blocks
                [(status-line {:my.ui/label \"Total\" :my.ui/value \"$101/mo\"
                               :my.ui/tone :signal})
                 (kv-table {:my.ui/rows [[\"Adobe CC\" \"$45\"]
                                         [\"Netflix\" \"$18\"]]})]})]
       (seon.db/transact!
         {:seon.db/tx-data
          [{:seon.agent/id (seon.db/current-agent-id)
            :seon.render.live-tile/content (:seon.render/hiccup s)}]}))

   For a LIVE tile that re-derives every render, wrap a `section` call in a
   home-ns fn and wire its SYMBOL instead (see the `ui-live-tiles` skill).

   All helpers emit ONLY safelisted classes (`resources/public/css/input.css`)
   — anything else is invisible — and are SYNC pure data (no `^:async`).
   `section` COMPOSES child envelopes: it stacks their hiccup and joins
   their `:seon.render/ai`, so the mirror holds through nesting."
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

(def ^:private tone->class
  "Tone keyword → safelisted accent class. Default cream when untoned."
  {:signal  "text-signal"
   :success "text-success"
   :error   "text-error"
   :warning "text-warning"
   :info    "text-info"})

;; ── status-line ─────────────────────────────────────────────────────
(schema/register! ::status-line-request
  [:map
   [::label ::label]
   [::value ::value]
   [::tone {:optional true} ::tone]])

(defn status-line
  "One labelled status line — `label: value`, value tinted by `tone`.
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

(defn kv-table
  "A two-column key/value table from `rows` (`[[k v] …]`), with an optional
   `title`. The dual render of a small breakdown — a styled table for the
   human, aligned `k: v` lines for you.

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

;; ── section ─────────────────────────────────────────────────────────
(schema/register! ::section-request
  [:map
   [::title  ::title]
   [::blocks ::blocks]])

(defn section
  "COMPOSE child envelopes under one titled container — the combinator that
   keeps the dual render mirrored through nesting. Stacks every block's
   `:seon.render/hiccup` and joins every block's `:seon.render/ai`, so the
   composed result is itself a faithful `:seon.render/html-response` you can
   transact onto your canvas (or return from a tile fn).

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
