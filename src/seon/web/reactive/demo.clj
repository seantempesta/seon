(ns seon.web.reactive.demo
  "Demo page for reactive UI proof of concept.

   Access at: /reactive-demo

   Demonstrates:
   - Reactive ctx with automatic SSE push
   - Hiccup transformation (clean syntax -> Datastar)
   - Action endpoint (button clicks -> server functions)
   - Form handling with signals"
  (:require [seon.web.reactive.ctx :as ctx]
            [seon.web.reactive.transform :as transform]
            [seon.web.html :as html]
            [dev.onionpancakes.chassis.core :as h]
            [org.httpkit.server :as hk]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Demo State
;;; ---------------------------------------------------------------------------

(declare render-content)

(defonce ^:private initialized? (atom false))

(defn- ensure-ctx!
  "Ensure the demo ctx exists."
  []
  (when-not @initialized?
    (ctx/create! 'seon.web.reactive.demo
                 {:count 0
                  :items []
                  :message nil})
    (ctx/set-render-fn! 'seon.web.reactive.demo #'render-content)
    (reset! initialized? true)
    (log/info "Demo ctx initialized")))

;;; ---------------------------------------------------------------------------
;;; Action Functions (called via /action endpoint)
;;; ---------------------------------------------------------------------------

(defn increment!
  "Increment the counter."
  [_signals]
  (when-let [ctx (ctx/get-ctx 'seon.web.reactive.demo)]
    (swap! ctx update :count inc)))

(defn decrement!
  "Decrement the counter."
  [_signals]
  (when-let [ctx (ctx/get-ctx 'seon.web.reactive.demo)]
    (swap! ctx update :count dec)))

(defn reset-count!
  "Reset counter to zero."
  [_signals]
  (when-let [ctx (ctx/get-ctx 'seon.web.reactive.demo)]
    (swap! ctx assoc :count 0)))

(defn add-item!
  "Add an item from form signals."
  [{:keys [item-name] :as signals}]
  (log/debug "add-item! called" {:signals signals})
  (when-let [ctx (ctx/get-ctx 'seon.web.reactive.demo)]
    (when (and item-name (not (empty? item-name)))
      (swap! ctx update :items conj {:name item-name :id (random-uuid)}))))

(defn remove-item!
  "Remove an item by id."
  [{:keys [item-id]}]
  (when-let [ctx (ctx/get-ctx 'seon.web.reactive.demo)]
    (let [id (parse-uuid item-id)]
      (swap! ctx update :items (fn [items] (remove #(= (:id %) id) items))))))

(defn set-message!
  "Set the message from input."
  [{:keys [message]}]
  (when-let [ctx (ctx/get-ctx 'seon.web.reactive.demo)]
    (swap! ctx assoc :message message)))

;;; ---------------------------------------------------------------------------
;;; Render Function
;;; ---------------------------------------------------------------------------

(defn render-content
  "Render the demo content. Called on ctx change.

   Note: Returns content for INSIDE #reactive-content, not the container itself.
   The container (<main>) has data-init for SSE connection setup.

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
    [:input#input-message.w-full.px-3.py-2.bg-surface-2.text-text-primary.rounded.border.border-surface-3
     {:field :message
      :placeholder "Type something..."
      :data-on:input "@post('/action/seon.web.reactive.demo/set-message!')"}]
    [:p.mt-2.text-text-secondary
     "You typed: "
     [:span#span-message.text-accent-primary.font-mono (or message "(nothing yet)")]]]])

;;; ---------------------------------------------------------------------------
;;; Page Handler
;;; ---------------------------------------------------------------------------

(defn- full-page
  "Render the full HTML page with Datastar setup."
  [content-hiccup]
  (let [transformed (transform/transform-hiccup 'seon.web.reactive.demo content-hiccup)]
    (str
     "<!DOCTYPE html>"
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title "Reactive UI Demo"]
        [:link {:rel "stylesheet" :href "/css/output.css"}]
        ;; Datastar from shared constant
        [:script {:type "module" :src html/datastar-js}]
        ;; Debug panel - shows SSE connection status, events, errors
        ;; Access window.SEON_DEBUG in console for full state
        [:script {:src "/js/seon-debug.js"}]]
       [:body.bg-base-bg.text-text-primary.font-mono.min-h-screen
        [:div.container.mx-auto.p-6.max-w-2xl
         [:header.mb-8
          [:h1.text-2xl.font-bold.text-accent-primary "Reactive UI Demo - RELOAD TEST"]
          [:p.text-text-secondary.mt-2
           "Proof of concept: agents write clean Clojure, framework handles Datastar/SSE"]]

         ;; Main content area - this gets replaced via SSE
         ;; data-init triggers POST on load to establish SSE connection
         [:main#reactive-content
          {:data-init "@post('/reactive-demo')"}
          transformed]

         [:footer.mt-8.pt-4.border-t.border-surface-2.text-text-muted.text-sm
          [:p "Connected clients: "
           [:span#client-count "..."]]
          [:p.mt-1
           [:a.text-accent-primary.hover:underline {:href "/"} "← Back to dashboard"]]]]]]))))

(defn- sse-handler
  "Handle SSE connection for reactive updates."
  [request]
  (ensure-ctx!)
  (hk/as-channel request
    {:on-open (fn [channel]
                (log/debug "SSE client connected for demo")
                ;; IMPORTANT: Send headers ONCE when connection opens.
                ;; All subsequent sends must be raw strings (no headers).
                (hk/send! channel
                          {:status 200
                           :headers {"Content-Type" "text/event-stream"
                                     "Cache-Control" "no-cache"
                                     "Connection" "keep-alive"}}
                          false)
                ;; Register client and push initial content as raw string
                (ctx/register-client! 'seon.web.reactive.demo channel)
                (ctx/force-push! 'seon.web.reactive.demo))
     :on-close (fn [channel _status]
                 (log/debug "SSE client disconnected from demo")
                 (ctx/unregister-client! 'seon.web.reactive.demo channel))}))

(defn handler
  "Ring handler for the reactive demo page.
   GET  /reactive-demo → HTML page
   POST /reactive-demo → SSE connection (Datastar pattern)"
  [request]
  (ensure-ctx!)
  (let [uri (:uri request)
        method (:request-method request)]
    (when (= uri "/reactive-demo")
      (case method
        ;; GET → HTML page
        :get
        (let [ctx-val @(ctx/get-ctx 'seon.web.reactive.demo)
              content (render-content ctx-val)]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (full-page content)})

        ;; POST → SSE connection
        :post
        (sse-handler request)

        ;; Other methods
        nil))))

(comment
  ;; Test rendering
  (ensure-ctx!)
  (render-content @(ctx/get-ctx 'seon.web.reactive.demo))

  ;; Test transformation
  (transform/transform-hiccup 'seon.web.reactive.demo
    [:button {:on:click :increment!} "+"])

  ;; Test actions
  (increment! {})
  @(ctx/get-ctx 'seon.web.reactive.demo)
  )
