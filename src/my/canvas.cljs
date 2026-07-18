(ns my.canvas
  "The permanent agent-facing canvas API.

   `show!` and `clear!` update YOUR canvas. The eval boundary injects the
   current agent id and db, so normal calls never identify an agent manually.
   A renderer fn queries the supplied `:seon.db/db` and returns [[view]]; every
   database transaction then redraws through the normal live feed.

   Controls are ordinary hiccup carrying handler forms understood by Seon's
   render transform. A handler symbol is qualified to the renderer's agent
   namespace and routed through the existing `/agent/{id}/call` capability
   gate for the rendering agent. Buttons do not create routes. Reuse these
   primitives directly or
   build domain-specific helpers from them in your own namespace."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.render.canvas :as render-canvas]
    [seon.schema :as schema]))

(schema/register! ::label :string)
(schema/register! ::handler :symbol)
(schema/register! ::data [:map-of :qualified-keyword :any])
(schema/register! ::field :qualified-keyword)
(schema/register! ::placeholder :string)
(schema/register! ::option [:tuple :string :string])
(schema/register! ::options [:vector ::option])
(schema/register! ::content :seon.render.canvas/hiccup)
(schema/register! ::ai :string)
(schema/register! ::control :seon.render.canvas/hiccup)
(schema/register! ::controls [:vector ::control])
(schema/register! ::attributes [:vector :qualified-keyword])
(schema/register! ::values [:map-of :qualified-keyword :any])

(def ^:private button-class
  "px-2 py-1 rounded border border-base-700 bg-base-850 text-xs text-text-100 cursor-pointer select-none hover:bg-base-800 hover:text-text-50 focus:outline-none focus-visible:border-amber-400 disabled:opacity-50")
(def ^:private field-class
  "w-full px-2 py-1 rounded border border-base-700 bg-base-900 text-xs text-text-100 focus:outline-none focus-visible:border-amber-400 placeholder:text-text-500")
(def ^:private field-label-class "text-2xs text-text-400 uppercase tracking-wider")
(def ^:private field-wrap-class "flex flex-col gap-1")

(def ^:private signal-prefix "seon_")

(defn- field-signal
  "Encode a qualified field keyword as a Datastar-safe signal identifier.
   `/call` decodes this exact prefix back to the original keyword."
  [field]
  (str signal-prefix
       (.toString (.from js/Buffer (str field) "utf8") "base64url")))

(schema/register! ::view-request
  [:map
   [::content ::content]
   [::ai {:optional true} ::ai]])

(defn ^:seon.fn/agent-facing? view
  "Build the canonical render returned by a canvas renderer fn.

   The renderer contract is `[:=> [:cat :seon.render/system-input]
   :seon.render/html-response]`; destructure its injected `:seon.db/db` and
   derive from that frozen value. `::content` is required; `::ai` is an
   optional model-facing twin and is omitted when the visual needs no prose."
  {:malli/schema [:=> [:cat ::view-request] :seon.render/html-response]}
  [{::keys [content ai]}]
  (cond-> {:seon.render/hiccup content}
    (some? ai) (assoc :seon.render/ai ai)))

(schema/register! ::show-request
  [:map
   [::content :seon.render.canvas/content]
   [:seon.agent/id {:optional true} :string]])
(schema/register! ::show-response :seon.db/transact-response)

(defn ^{:async true :seon.fn/agent-facing? true} show!
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

(defn ^{:async true :seon.fn/agent-facing? true} clear!
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

(defn ^:seon.fn/agent-facing? pinned
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

(schema/register! ::state-request
  [:map
   [::attributes ::attributes]
   [:seon.db/db {:optional true} :seon.db/db]
   [:seon.agent/id {:optional true} :string]])
(schema/register! ::state-response ::values)

(defn ^:seon.fn/agent-facing? state
  "Read qualified canvas/domain attributes from YOUR agent entity.

   `:seon.db/db` and `:seon.agent/id` are injected. Example:
   `(state {:my.canvas/attributes [:my.agent.example/count]})`.
   Missing attributes are omitted. Advanced graph queries still belong in
   `seon.db`; this helper owns the common agent-local state case."
  {:malli/schema [:=> [:cat ::state-request] ::state-response]}
  [{::keys [attributes] dbv :seon.db/db agent-id :seon.agent/id}]
  (or (db/pull {:seon.db/db dbv
                :seon.db/pull-pattern attributes
                :seon.db/ref [:seon.agent/id agent-id]})
      {}))

(schema/register! ::save-request
  [:map
   [::values ::values]
   [:seon.agent/id {:optional true} :string]])
(schema/register! ::save-response :seon.db/transact-response)

(defn ^{:async true :seon.fn/agent-facing? true} save!
  "Merge qualified values onto YOUR agent entity; returns the envelope.

   `:seon.agent/id` is injected. Every attribute must already have a registered
   schema. Example:
   `(save! {:my.canvas/values {:my.agent.example/count 1}})`.
   Inspect `:seon.db/ok?` before claiming the visible update worked."
  {:malli/schema [:=> [:cat ::save-request] ::save-response]}
  [{::keys [values] agent-id :seon.agent/id}]
  (await
    (db/transact!
      {:seon.db/tx-data
       [(assoc values :seon.db/ref [:seon.agent/id agent-id])]})))

(schema/register! ::button-request
  [:map
   [::label ::label]
   [::handler ::handler]
   [::data {:optional true} ::data]])

(defn ^:seon.fn/agent-facing? button
  "A button routed to one of YOUR map-in handler fns through the call gate.

   The handler receives the VALUE of `::data` directly, never a
   `{:my.canvas/data ...}` wrapper. Omitted `::data` therefore invokes the
   handler with `{}`. A generic handler may use
   `[:=> [:cat :my.canvas/data] :any]`; domain handlers should use a concrete
   fully-namespaced map schema for their captured identity or parameters."
  {:malli/schema [:=> [:cat ::button-request] ::control]}
  [{::keys [label handler data]}]
  [:button {:type "button"
            :on-click (list handler (or data {}))
            :class button-class}
   label])

(schema/register! ::input-request
  [:map
   [::field ::field]
   [::label {:optional true} ::label]
   [::placeholder {:optional true} ::placeholder]])

(defn ^:seon.fn/agent-facing? input
  "A text field for a surrounding [[form]].

   `::field` is a qualified keyword; the routing adapter preserves that
   exact key in the handler request map."
  {:malli/schema [:=> [:cat ::input-request] ::control]}
  [{::keys [field label placeholder]}]
  [:label {:class field-wrap-class}
   (when label [:span {:class field-label-class} label])
   [:input (cond-> {:type "text" :data-bind (field-signal field) :class field-class}
             placeholder (assoc :placeholder placeholder))]])

(schema/register! ::select-request
  [:map
   [::field ::field]
   [::options ::options]
   [::label {:optional true} ::label]])

(defn ^:seon.fn/agent-facing? select
  "A dropdown whose qualified `::field` is preserved in the handler map."
  {:malli/schema [:=> [:cat ::select-request] ::control]}
  [{::keys [field options label]}]
  [:label {:class field-wrap-class}
   (when label [:span {:class field-label-class} label])
   (into [:select {:data-bind (field-signal field) :class field-class}]
         (map (fn [[value option-label]]
                [:option {:value value} option-label]))
         options)])

(schema/register! ::toggle-request
  [:map
   [::field ::field]
   [::label {:optional true} ::label]])

(defn ^:seon.fn/agent-facing? toggle
  "A boolean checkbox; its qualified `::field` reaches the handler map."
  {:malli/schema [:=> [:cat ::toggle-request] ::control]}
  [{::keys [field label]}]
  [:label {:class "flex flex-row gap-2 items-center cursor-pointer select-none"}
   [:input {:type "checkbox" :data-bind (field-signal field)
            :class "cursor-pointer accent-amber-400"}]
   (when label [:span {:class "text-xs text-text-200"} label])])

(schema/register! ::form-request
  [:map
   [::handler ::handler]
   [::label ::label]
   [::controls ::controls]])

(defn ^:seon.fn/agent-facing? form
  "Stack controls into a form that submits a field map to your handler.

   The fully-namespaced field map is the handler's direct argument, not a
   `:my.canvas/data` wrapper. Ambient page signals are excluded by the call
   adapter."
  {:malli/schema [:=> [:cat ::form-request] ::control]}
  [{::keys [handler label controls]}]
  (into [:form {:on-submit handler :class "flex flex-col gap-2"}]
        (conj controls
              [:button {:type "submit" :class button-class} label])))
