(ns my.tile
  "INTERACTIVE canvas pieces — the sibling of `my.ui` (static). Where
   `my.ui` gives you read-only status lines and tables, `my.tile` gives
   you CONTROLS the human can click/type into that call one of YOUR OWN
   fns back. Same dual-render contract: DATA in, the
   `:seon.render/html-response` envelope out —

     {:seon.render/hiccup […]   ; the HUMAN's styled, safelisted control
      :seon.render/ai    \"…\"}    ; YOUR view — \"[button: …] → my.fn\"

   so the two can't drift: the human gets a real button, you read the
   compact line describing WHICH of your fns it's wired to.

   ## How a control calls you back (the whole mechanism)

   A control's action is a fn YOU defined in your home ns — nothing else
   is invocable (the `/agent/<id>/call` gate authorizes only your own
   `:seon.fn` set). You never write a URL or a Datastar string; you pass
   a fn-CALL or a fn-REF and the render rewrites it:

   - fn-CALL — `(list 'approve! order-id)` — the args are captured NOW,
     when the tile renders (use for a row's id). They arrive as positional
     args, DATA-ONLY.
   - fn-REF — `'save-note!` (a bare symbol) — no render-time args; the
     human's typed/picked field signals POST as the body and land as ONE
     map argument `{:field val …}` (use for forms/inputs).

   ## Worked example — a counter button + a note form

     ;; 1. eval your handler fns ONCE (they become granted :seon.fn rows).
     ;;    register any new attr first, else db/transact! refuses it.
     (seon.schema/register! :my.agent.me/counter :int)
     (defn ^:async bump! [_]
       (let [id (seon.db/current-agent-id)
             n  (or (some-> (seon.db/pull
                              {:seon.db/db @seon.db/*conn*
                               :seon.db/pull-pattern '[:my.agent.me/counter]
                               :seon.db/ref [:seon.agent/id id]})
                            :my.agent.me/counter) 0)]
         (seon.db/transact! {:seon.db/tx-data
                             [{:seon.agent/id id :my.agent.me/counter (inc n)}]})))

     ;; 2. wire a LIVE tile fn that re-derives every render — the click's
     ;;    effect shows up with no extra wiring (the feed re-renders on tx).
     (defn my-tile [{:keys [seon.db/db]}]
       (let [n (or (:my.agent.me/counter
                    (seon.db/pull {:seon.db/db db
                                   :seon.db/pull-pattern '[:my.agent.me/counter]
                                   :seon.db/ref [:seon.agent/id (seon.db/current-agent-id)]}))
                   0)]
         (my.ui/section
           {:my.ui/title \"Counter\"
            :my.ui/blocks
            [(my.ui/status-line {:my.ui/label \"count\" :my.ui/value (str n)})
             (my.tile/button {:my.tile/label \"+1\" :my.tile/action 'bump!})]})))
     (seon.db/transact! {:seon.db/tx-data
                         [{:seon.agent/id (seon.db/current-agent-id)
                           :seon.render.live-tile/content 'my.agent.me/my-tile}]})

   A note FORM that messages you instead:

     (defn ^:async save-note! [{:keys [note]}]        ; signals → one map arg
       (seon.agent/reply! {…}))   ; or transact a :my.note/* entity
     (my.tile/form
       {:my.tile/submit 'save-note!
        :my.tile/label  \"Save\"
        :my.tile/fields [(my.tile/input {:my.tile/field \"note\"
                                         :my.tile/label \"Note\"})]})

   All helpers emit ONLY safelisted classes (`resources/public/css/input.css`)
   and are SYNC pure data. The action MUST be a fn-symbol or a fn-call —
   never a raw string (a string can't name your fn, and the gate would
   refuse it anyway)."
  (:require
    [clojure.string :as str]
    [seon.schema :as schema]))

;; ── SHARED FIELD SHAPES — registered once, referenced by every helper ──
;; A control is a LABEL/FIELD + a fn ACTION; the output is always the
;; `:seon.render/html-response` dual-render envelope (registered in
;; seon.render — referenced, never re-declared).

(schema/register! ::label :string)
;; The signal name a field binds to (Datastar `data-bind`); the form's
;; submit posts every bound signal as one map arg keyed by these.
(schema/register! ::field :string)
(schema/register! ::placeholder :string)
;; A control ACTION is a fn the agent defined in its home ns — either a
;; fn-REF (bare/qualified symbol, args from click-time signals) or a
;; fn-CALL (a seq with a symbol head, args captured at render time). A
;; raw string is intentionally NOT accepted — only a fn the gate grants.
(schema/register! ::action [:or :symbol [:sequential :any]])
;; A select option — `["pro" "Pro"]` = [value label].
(schema/register! ::option  [:tuple :string :string])
(schema/register! ::options [:vector ::option])
;; Child field envelopes `form` stacks — each already a dual render.
(schema/register! ::fields  [:vector :seon.render/html-response])

;; ── safelisted control classes (extend input.css @source inline) ──
(def ^:private btn-class
  "px-2 py-1 rounded border border-base-700 bg-base-850 text-xs text-text-100 cursor-pointer select-none hover:bg-base-800 hover:text-text-50 focus:outline-none focus-visible:border-amber-400 disabled:opacity-50")
(def ^:private field-class
  "w-full px-2 py-1 rounded border border-base-700 bg-base-900 text-xs text-text-100 focus:outline-none focus-visible:border-amber-400 placeholder:text-text-500")
(def ^:private field-label-class "text-2xs text-text-400 uppercase tracking-wider")
(def ^:private field-wrap-class "flex flex-col gap-1")

(defn- action-fn-sym
  "The fn symbol an action names — the head of a fn-CALL seq, or the
   symbol itself for a fn-REF."
  [action]
  (if (seq? action) (first action) action))

(defn- action-arg-desc
  "A `\" arg1 arg2\"` suffix describing a fn-CALL's render-time args, or
   nil for a fn-REF / arg-less call."
  [action]
  (when (seq? action)
    (let [args (rest action)]
      (when (seq args)
        (str " " (str/join " " (map pr-str args)))))))

;; ── button ───────────────────────────────────────────────────────────
(schema/register! ::button-request
  [:map
   [::label  ::label]
   [::action ::action]])

(defn button
  "A clickable button wired to one of YOUR fns. `action` is a fn-REF
   (`'submit!` — click-time signals become its one map arg) or a fn-CALL
   (`(list 'approve! id)` — render-time args become positional args).

     (button {:my.tile/label \"Approve\" :my.tile/action (list 'approve! \"o-7\")})
     ;; human: a styled clickable button (data-on:click @post → /call)
     ;; ai:    \"[button: \\\"Approve\\\" → approve! \\\"o-7\\\"]\""
  {:malli/schema [:=> [:cat ::button-request] :seon.render/html-response]}
  [{::keys [label action]}]
  {:seon.render/hiccup
   [:button {:on-click action :class btn-class} label]
   :seon.render/ai
   (str "[button: \"" label "\" → " (action-fn-sym action)
        (or (action-arg-desc action) "") "]")})

;; ── input ────────────────────────────────────────────────────────────
(schema/register! ::input-request
  [:map
   [::field       ::field]
   [::label       {:optional true} ::label]
   [::placeholder {:optional true} ::placeholder]])

(defn input
  "A labelled text field BOUND to a signal (`data-bind`). Its value posts
   with the form submit (or any fn-REF button) as `{:<field> value}`.

     (input {:my.tile/field \"note\" :my.tile/label \"Note\" :my.tile/placeholder \"…\"})"
  {:malli/schema [:=> [:cat ::input-request] :seon.render/html-response]}
  [{::keys [field label placeholder]}]
  {:seon.render/hiccup
   [:label {:class field-wrap-class}
    (when label [:span {:class field-label-class} label])
    [:input (cond-> {:type "text" :data-bind field :class field-class}
              placeholder (assoc :placeholder placeholder))]]
   :seon.render/ai
   (str "[input: " (or label field) " → signal \"" field "\"]")})

;; ── select ───────────────────────────────────────────────────────────
(schema/register! ::select-request
  [:map
   [::field   ::field]
   [::options ::options]
   [::label   {:optional true} ::label]])

(defn select
  "A labelled dropdown BOUND to a signal. `options` is `[[value label] …]`.

     (select {:my.tile/field \"tier\"
              :my.tile/options [[\"free\" \"Free\"] [\"pro\" \"Pro\"]]})"
  {:malli/schema [:=> [:cat ::select-request] :seon.render/html-response]}
  [{::keys [field options label]}]
  {:seon.render/hiccup
   [:label {:class field-wrap-class}
    (when label [:span {:class field-label-class} label])
    (into [:select {:data-bind field :class field-class}]
          (for [[value opt-label] options]
            [:option {:value value} opt-label]))]
   :seon.render/ai
   (str "[select: " (or label field) " → signal \"" field "\" | options: "
        (str/join ", " (map second options)) "]")})

;; ── toggle ───────────────────────────────────────────────────────────
(schema/register! ::toggle-request
  [:map
   [::field ::field]
   [::label {:optional true} ::label]])

(defn toggle
  "A labelled checkbox BOUND to a boolean signal.

     (toggle {:my.tile/field \"live\" :my.tile/label \"Live updates\"})"
  {:malli/schema [:=> [:cat ::toggle-request] :seon.render/html-response]}
  [{::keys [field label]}]
  {:seon.render/hiccup
   [:label {:class "flex flex-row gap-2 items-center cursor-pointer select-none"}
    [:input {:type "checkbox" :data-bind field :class "cursor-pointer accent-amber-400"}]
    (when label [:span {:class "text-xs text-text-200"} label])]
   :seon.render/ai
   (str "[toggle: " (or label field) " → signal \"" field "\"]")})

;; ── form ─────────────────────────────────────────────────────────────
(schema/register! ::form-request
  [:map
   [::submit ::action]
   [::label  ::label]
   [::fields ::fields]])

(defn form
  "COMPOSE fields into a submitting form. On submit, every field's bound
   signal POSTs to your `submit` fn as ONE map argument `{:<field> val …}`
   (a fn-REF). Stacks the child field envelopes (dual render holds through
   nesting), then appends the submit button.

     (form {:my.tile/submit 'save-note!
            :my.tile/label  \"Save\"
            :my.tile/fields [(input  {:my.tile/field \"note\"  :my.tile/label \"Note\"})
                             (select {:my.tile/field \"tier\" :my.tile/options […]})]})"
  {:malli/schema [:=> [:cat ::form-request] :seon.render/html-response]}
  [{::keys [submit label fields]}]
  {:seon.render/hiccup
   (into [:form {:on-submit submit :class "flex flex-col gap-2"}]
         (conj (mapv :seon.render/hiccup fields)
               [:button {:type "submit" :class btn-class} label]))
   :seon.render/ai
   (str "[form → " (action-fn-sym submit) "]"
        (when (seq fields)
          (str "\n" (str/join "\n" (keep :seon.render/ai fields))))
        "\n[submit: \"" label "\"]")})
