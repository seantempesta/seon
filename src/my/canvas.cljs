(ns my.canvas
  "The permanent agent-facing canvas API.

   `show!` and `clear!` update YOUR canvas. The eval boundary injects the
   current agent id and db, so normal calls never identify an agent manually.
   A renderer fn queries the supplied `:seon.db/db` and returns [[view]]; every
   database transaction then redraws through the normal live feed.

   Controls are ordinary hiccup carrying handler forms understood by Seon's
   render transform. A handler symbol is qualified to the renderer's agent
   namespace and routed through the existing `/agent/{id}/call` capability
   gate. Buttons do not create routes. Reuse these primitives directly or
   build domain-specific helpers from them in your own namespace."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.render.canvas :as render-canvas]
    [seon.schema :as schema]))

(schema/register! ::label :string)
(schema/register! ::handler :symbol)
(schema/register! ::args [:vector :any]) ; captured DATA payload, checked again by /call
(schema/register! ::field :string)
(schema/register! ::placeholder :string)
(schema/register! ::option [:tuple :string :string])
(schema/register! ::options [:vector ::option])
(schema/register! ::content :seon.render.canvas/hiccup)
(schema/register! ::ai :string)
(schema/register! ::control :seon.render.canvas/hiccup)
(schema/register! ::controls [:vector ::control])

(def ^:private button-class
  "px-2 py-1 rounded border border-base-700 bg-base-850 text-xs text-text-100 cursor-pointer select-none hover:bg-base-800 hover:text-text-50 focus:outline-none focus-visible:border-amber-400 disabled:opacity-50")
(def ^:private field-class
  "w-full px-2 py-1 rounded border border-base-700 bg-base-900 text-xs text-text-100 focus:outline-none focus-visible:border-amber-400 placeholder:text-text-500")
(def ^:private field-label-class "text-2xs text-text-400 uppercase tracking-wider")
(def ^:private field-wrap-class "flex flex-col gap-1")

(defn- action [handler args]
  (if (seq args) (apply list handler args) handler))

(schema/register! ::view-request
  [:map
   [::content ::content]
   [::ai ::ai]])

(defn view
  "Build the canonical dual render returned by a canvas renderer fn."
  {:malli/schema [:=> [:cat ::view-request] :seon.render/html-response]}
  [{::keys [content ai]}]
  {:seon.render/hiccup content
   :seon.render/ai ai})

(schema/register! ::show-request
  [:map
   [::content :seon.render.canvas/content]
   [:seon.agent/id {:optional true} :string]])
(schema/register! ::show-response :seon.db/transact-response)

(defn ^:async show!
  "Pin literal hiccup or a qualified renderer fn to YOUR canvas.

   `:seon.agent/id` is injected. Omit it in agent code."
  {:malli/schema [:=> [:cat ::show-request] ::show-response]}
  [{::keys [content] agent-id :seon.agent/id}]
  (await
    (db/transact!
      {:seon.db/tx-data
       [{:seon.agent/id agent-id
         :seon.render.canvas/content content}]})))

(schema/register! ::canvas-request
  [:map
   [:seon.db/db {:optional true} :seon.db/db]
   [:seon.agent/id {:optional true} :string]])
(schema/register! ::clear-response :seon.db/transact-response)

(defn ^:async clear!
  "Clear YOUR explicit canvas pin and resume the derived default.

   Call `(clear! {})`; agent id and db are injected."
  {:malli/schema [:=> [:cat ::canvas-request] ::clear-response]}
  [{agent-id :seon.agent/id}]
  (await
    (db/transact!
      {:seon.db/tx-data
       [[:db/retract [:seon.agent/id agent-id]
         :seon.render.canvas/content]]})))

(schema/register! ::pinned-response
  [:map [::content {:optional true} :seon.render.canvas/content]])

(defn pinned
  "Return YOUR explicit canvas pin, or an empty map when none is pinned.

   Call `(pinned {})`; agent id and db are injected."
  {:malli/schema [:=> [:cat ::canvas-request] ::pinned-response]}
  [{dbv :seon.db/db agent-id :seon.agent/id}]
  (if (and dbv agent-id
           (contains? (db/installed-schema dbv) :seon.render.canvas/content))
    (if-some [content (some-> (db/pull dbv [:seon.render.canvas/content]
                                      [:seon.agent/id agent-id])
                              :seon.render.canvas/content
                              (db/decode-edn-value :seon.render.canvas/content))]
      {::content content}
      {})
    {}))

(schema/register! ::button-request
  [:map
   [::label ::label]
   [::handler ::handler]
   [::args {:optional true} ::args]])

(defn button
  "A button routed to one of YOUR handler fns through the standard call gate."
  {:malli/schema [:=> [:cat ::button-request] ::control]}
  [{::keys [label handler args]}]
  [:button {:type "button"
            :on-click (action handler args)
            :class button-class}
   label])

(schema/register! ::input-request
  [:map
   [::field ::field]
   [::label {:optional true} ::label]
   [::placeholder {:optional true} ::placeholder]])

(defn input
  "A text field bound to a Datastar signal for a surrounding [[form]]."
  {:malli/schema [:=> [:cat ::input-request] ::control]}
  [{::keys [field label placeholder]}]
  [:label {:class field-wrap-class}
   (when label [:span {:class field-label-class} label])
   [:input (cond-> {:type "text" :data-bind field :class field-class}
             placeholder (assoc :placeholder placeholder))]])

(schema/register! ::select-request
  [:map
   [::field ::field]
   [::options ::options]
   [::label {:optional true} ::label]])

(defn select
  "A dropdown bound to a Datastar signal for a surrounding [[form]]."
  {:malli/schema [:=> [:cat ::select-request] ::control]}
  [{::keys [field options label]}]
  [:label {:class field-wrap-class}
   (when label [:span {:class field-label-class} label])
   (into [:select {:data-bind field :class field-class}]
         (map (fn [[value option-label]]
                [:option {:value value} option-label]))
         options)])

(schema/register! ::toggle-request
  [:map
   [::field ::field]
   [::label {:optional true} ::label]])

(defn toggle
  "A boolean checkbox bound to a Datastar signal for a surrounding [[form]]."
  {:malli/schema [:=> [:cat ::toggle-request] ::control]}
  [{::keys [field label]}]
  [:label {:class "flex flex-row gap-2 items-center cursor-pointer select-none"}
   [:input {:type "checkbox" :data-bind field
            :class "cursor-pointer accent-amber-400"}]
   (when label [:span {:class "text-xs text-text-200"} label])])

(schema/register! ::form-request
  [:map
   [::handler ::handler]
   [::label ::label]
   [::controls ::controls]])

(defn form
  "Stack controls into a form that sends current signals to YOUR handler fn."
  {:malli/schema [:=> [:cat ::form-request] ::control]}
  [{::keys [handler label controls]}]
  (into [:form {:on-submit handler :class "flex flex-col gap-2"}]
        (conj controls
              [:button {:type "submit" :class button-class} label])))
