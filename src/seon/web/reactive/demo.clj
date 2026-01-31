(ns seon.web.reactive.demo
  "Demo page for reactive UI - now instance-based.

   Access at: /ns/seon.web.reactive.demo

   Each browser tab gets its own instance with isolated state.
   The routes.clj handles instance creation and redirects automatically.

   Demonstrates:
   - Instance-based reactive ctx (each tab = own state)
   - Hiccup transformation (clean syntax -> Datastar)
   - Action functions receiving ctx in signals
   - Form handling with signals

   To be a reactive namespace, provide:
   - render-content (required): fn [ctx-value] -> hiccup
   - initial-state (optional): fn [] -> initial ctx value"
  (:require [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Initial State
;;; ---------------------------------------------------------------------------

(defn initial-state
  "Initial state for new demo instances.
   Each browser tab gets its own instance with this starting state."
  []
  {:count 0
   :items []
   :message nil})

;;; ---------------------------------------------------------------------------
;;; Action Functions
;;;
;;; Action functions receive signals map from the form/button.
;;; For instance-based namespaces, :seon.reactive/ctx contains the ctx atom.
;;; ---------------------------------------------------------------------------

(defn increment!
  "Increment the counter."
  [{:seon.reactive/keys [ctx]}]
  (when ctx
    (swap! ctx update :count inc)))

(defn decrement!
  "Decrement the counter."
  [{:seon.reactive/keys [ctx]}]
  (when ctx
    (swap! ctx update :count dec)))

(defn reset-count!
  "Reset counter to zero."
  [{:seon.reactive/keys [ctx]}]
  (when ctx
    (swap! ctx assoc :count 0)))

(defn add-item!
  "Add an item from form signals."
  [{:seon.reactive/keys [ctx] :keys [item-name] :as signals}]
  (log/debug "add-item! called" {:signals (dissoc signals :seon.reactive/ctx)})
  (when (and ctx item-name (not (empty? item-name)))
    (swap! ctx update :items conj {:name item-name :id (random-uuid)})))

(defn remove-item!
  "Remove an item by id."
  [{:seon.reactive/keys [ctx] :keys [item-id]}]
  (when ctx
    (let [id (parse-uuid item-id)]
      (swap! ctx update :items (fn [items] (remove #(= (:id %) id) items))))))

(defn set-message!
  "Set the message from input."
  [{:seon.reactive/keys [ctx] :keys [message]}]
  (when ctx
    (swap! ctx assoc :message message)))

;;; ---------------------------------------------------------------------------
;;; Render Function
;;;
;;; Takes ctx VALUE (not atom) and returns hiccup.
;;; The framework handles transformation to Datastar and SSE push.
;;; ---------------------------------------------------------------------------

(defn render-content
  "Render the demo content. Called on ctx change.

   Receives the ctx value (a map), returns hiccup.
   The framework wraps this in a page template and handles SSE.

   All interactive elements have IDs for reliable browser automation:
   - btn-* for buttons
   - input-* for inputs
   - form-* for forms
   - span-* for display elements agents might verify"
  [{:keys [count items message]}]
  [:div#demo-content.space-y-6
   ;; Counter section
   [:section#section-counter.p-4.bg-surface-1.rounded
    [:h2.text-lg.font-semibold.text-text-primary.mb-3 "Counter Demo"]
    [:div.flex.items-center.gap-4
     [:span#span-count.text-2xl.font-mono.text-accent-primary (str count)]
     [:button#btn-decrement.px-3.py-1.bg-accent-primary.text-base-bg.rounded.hover:bg-accent-secondary
      {:on:click :decrement!} "-"]
     [:button#btn-increment.px-3.py-1.bg-accent-primary.text-base-bg.rounded.hover:bg-accent-secondary
      {:on:click :increment!} "+"]
     [:button#btn-reset.px-3.py-1.bg-surface-2.text-text-secondary.rounded.hover:bg-surface-3
      {:on:click :reset-count!} "Reset"]]]

   ;; Items section
   [:section#section-items.p-4.bg-surface-1.rounded
    [:h2.text-lg.font-semibold.text-text-primary.mb-3 "Items List"]
    [:form#form-add-item.flex.gap-2.mb-4 {:on:submit :add-item!}
     [:input#input-item-name.flex-1.px-3.py-1.bg-surface-2.text-text-primary.rounded.border.border-surface-3
      {:field :item-name :placeholder "Enter item name..."}]
     [:button#btn-add-item.px-4.py-1.bg-accent-primary.text-base-bg.rounded.hover:bg-accent-secondary
      {:type "submit"} "Add"]]
    (if (seq items)
      [:ul#list-items.space-y-2
       (for [{:keys [id name]} items]
         [:li.flex.items-center.justify-between.p-2.bg-surface-2.rounded
          {:id (str "item-" id) :key (str id)}
          [:span.text-text-primary name]
          [:button.px-2.py-1.text-sm.text-error.hover:bg-surface-3.rounded
           {:id (str "btn-remove-" id)
            :on:click :remove-item!
            :data-item-id (str id)} "×"]])]
      [:p#empty-items.text-text-muted.italic "No items yet"])]

   ;; Live input section
   [:section#section-live-input.p-4.bg-surface-1.rounded
    [:h2.text-lg.font-semibold.text-text-primary.mb-3 "Live Input"]
    ;; Note: data-on:input needs instance param in URL, but routes.clj adds it
    ;; via transform-hiccup. For now use :on:input which gets transformed.
    [:input#input-message.w-full.px-3.py-2.bg-surface-2.text-text-primary.rounded.border.border-surface-3
     {:field :message
      :placeholder "Type something..."
      :on:input :set-message!}]
    [:p.mt-2.text-text-secondary
     "You typed: "
     [:span#span-message.text-accent-primary.font-mono (or message "(nothing yet)")]]]])

(comment
  ;; Example: render with test data
  (render-content {:count 5
                   :items [{:id (random-uuid) :name "First item"}
                           {:id (random-uuid) :name "Second item"}]
                   :message "Hello world"})

  ;; Test action with mock ctx
  (let [ctx (atom {:count 0})]
    (increment! {:seon.reactive/ctx ctx})
    @ctx)
  ;; => {:count 1}
  )
