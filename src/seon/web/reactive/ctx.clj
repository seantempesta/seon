(ns seon.web.reactive.ctx
  "Reactive context (ctx) management for reactive UI.

   Each reactive namespace has a *ctx* atom. When agents swap! the atom,
   connected browsers automatically receive SSE updates.

   Simple architecture for PoC:
   1. ctx-registry: maps namespace -> {:atom ctx-atom :clients #{channel...}}
   2. When ctx changes, watch iterates clients and sends SSE
   3. No flow complexity - direct http-kit sends

   Usage:
     (def *ctx* (ctx/create! 'seon.trading {:signals []}))
     (swap! *ctx* update :signals conj {:name \"AAPL\"})
     ;; Connected browsers automatically update"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [org.httpkit.server :as hk]
            [dev.onionpancakes.chassis.core :as h]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(def CtxEntry
  "Schema for a registry entry."
  [:map
   [:seon.reactive/atom :any]
   [:seon.reactive/clients [:atom [:set :any]]]
   [:seon.reactive/render-fn {:optional true} :any]])

;;; ---------------------------------------------------------------------------
;;; Registry
;;; ---------------------------------------------------------------------------

;; Map of namespace symbol -> CtxEntry
(defonce ^:private registry (atom {}))

(defn get-ctx
  "Get the ctx atom for a namespace, or nil if not registered."
  {:malli/schema [:=> [:cat :symbol] [:maybe :any]]}
  [ns-sym]
  (get-in @registry [ns-sym :seon.reactive/atom]))

(defn get-entry
  "Get the full registry entry for a namespace."
  {:malli/schema [:=> [:cat :symbol] [:maybe CtxEntry]]}
  [ns-sym]
  (get @registry ns-sym))

(defn list-contexts
  "List all registered reactive contexts."
  {:malli/schema [:=> [:cat] [:sequential :symbol]]}
  []
  (keys @registry))

;;; ---------------------------------------------------------------------------
;;; SSE Push
;;; ---------------------------------------------------------------------------

(defn- format-sse-event
  "Format HTML as Datastar SSE event (patch-elements format)."
  [html-str]
  (str "event: datastar-patch-elements\n"
       "data: elements " (str/replace html-str "\n" "\ndata: elements ")
       "\n\n\n"))

(defn- push-to-client!
  "Push SSE update to a single client. Returns true if successful.

   IMPORTANT: Send raw SSE-formatted string, NOT a response map.
   Headers are sent once when connection opens (in sse-handler).
   Sending headers again corrupts the SSE stream."
  [channel html-str]
  (try
    (when (hk/open? channel)
      ;; Send raw string payload, not a map with headers
      (hk/send! channel (format-sse-event html-str) false)
      true)
    (catch Exception e
      (log/debug "Failed to push to client" {:error (.getMessage e)})
      false)))

(defn- push-update!
  "Push update to all clients for a namespace."
  [ns-sym html-str]
  (when-let [{:seon.reactive/keys [clients]} (get-entry ns-sym)]
    (let [channels @clients
          results (doall (map #(push-to-client! % html-str) channels))
          failed (count (filter false? results))]
      (when (pos? failed)
        ;; Clean up dead channels
        (swap! clients #(set (filter hk/open? %))))
      (log/debug "Pushed SSE update" {:ns ns-sym
                                       :clients (count channels)
                                       :failed failed}))))

;;; ---------------------------------------------------------------------------
;;; Render Integration
;;; ---------------------------------------------------------------------------

(defn set-render-fn!
  "Set the render function for a namespace.

   render-fn should take the ctx value and return hiccup.
   When ctx changes, render-fn is called and result pushed via SSE."
  {:malli/schema [:=> [:cat :symbol :any] :any]}
  [ns-sym render-fn]
  (swap! registry assoc-in [ns-sym :seon.reactive/render-fn] render-fn))

(defn- render-and-push!
  "Render current ctx state and push to all clients."
  [ns-sym ctx-value]
  (when-let [{:seon.reactive/keys [render-fn]} (get-entry ns-sym)]
    (when render-fn
      (try
        (let [hiccup (render-fn ctx-value)
              ;; Transform hiccup to Datastar format
              transformed ((requiring-resolve 'seon.web.reactive.transform/transform-hiccup)
                           ns-sym hiccup)
              html-str (str (h/html transformed))]
          (push-update! ns-sym html-str))
        (catch Exception e
          (log/error e "Render failed" {:ns ns-sym}))))))

;;; ---------------------------------------------------------------------------
;;; Ctx Lifecycle
;;; ---------------------------------------------------------------------------

(defn- make-watch
  "Create a watch function that pushes updates on ctx change."
  [ns-sym]
  (fn [_key _ref old-val new-val]
    (when (not= old-val new-val)
      (render-and-push! ns-sym new-val))))

(defn create!
  "Create a new reactive ctx for a namespace.

   ns-sym       - Namespace symbol (e.g., 'seon.trading)
   initial-val  - Initial ctx value

   Returns the ctx atom. Changes to this atom automatically push SSE updates."
  {:malli/schema [:=> [:cat :symbol :any] :any]}
  [ns-sym initial-val]
  (let [ctx-atom (atom initial-val)
        clients-atom (atom #{})]
    ;; Register in registry
    (swap! registry assoc ns-sym
           {:seon.reactive/atom ctx-atom
            :seon.reactive/clients clients-atom})
    ;; Add watch for SSE push
    (add-watch ctx-atom ::sse-push (make-watch ns-sym))
    (log/info "Created reactive ctx" {:ns ns-sym})
    ctx-atom))

(defn destroy!
  "Destroy a reactive ctx, closing all client connections."
  {:malli/schema [:=> [:cat :symbol] :boolean]}
  [ns-sym]
  (when-let [{:seon.reactive/keys [atom clients]} (get-entry ns-sym)]
    ;; Close all client connections
    (doseq [ch @clients]
      (try (hk/close ch) (catch Exception _)))
    ;; Remove watch
    (remove-watch atom ::sse-push)
    ;; Remove from registry
    (swap! registry dissoc ns-sym)
    (log/info "Destroyed reactive ctx" {:ns ns-sym})
    true))

;;; ---------------------------------------------------------------------------
;;; Client Management
;;; ---------------------------------------------------------------------------

(defn register-client!
  "Register an SSE client channel for a namespace.

   Note: Caller is responsible for cleanup on disconnect.
   Use unregister-client! in your on-close handler."
  {:malli/schema [:=> [:cat :symbol :any] :boolean]}
  [ns-sym channel]
  (if-let [{:seon.reactive/keys [clients]} (get-entry ns-sym)]
    (do
      (swap! clients conj channel)
      (log/debug "Client registered" {:ns ns-sym})
      true)
    (do
      (log/warn "Cannot register client - ctx not found" {:ns ns-sym})
      false)))

(defn unregister-client!
  "Unregister an SSE client channel from a namespace.

   Call this in your on-close handler to clean up."
  {:malli/schema [:=> [:cat :symbol :any] :boolean]}
  [ns-sym channel]
  (if-let [{:seon.reactive/keys [clients]} (get-entry ns-sym)]
    (do
      (swap! clients disj channel)
      (log/debug "Client unregistered" {:ns ns-sym})
      true)
    false))

(defn clients
  "Get the set of connected client channels for a namespace.

   Returns a set of http-kit channels, or nil if namespace not registered.
   Use this from the REPL to inspect connected browsers."
  {:malli/schema [:=> [:cat :symbol] [:maybe [:set :any]]]}
  [ns-sym]
  (when-let [{:seon.reactive/keys [clients]} (get-entry ns-sym)]
    @clients))

(defn client-count
  "Get the number of connected clients for a namespace."
  {:malli/schema [:=> [:cat :symbol] :int]}
  [ns-sym]
  (count (or (clients ns-sym) #{})))

;;; ---------------------------------------------------------------------------
;;; Manual Push (for testing)
;;; ---------------------------------------------------------------------------

(defn force-push!
  "Force push current state to all clients.

   Useful for initial render when client first connects."
  {:malli/schema [:=> [:cat :symbol] :boolean]}
  [ns-sym]
  (if-let [ctx-atom (get-ctx ns-sym)]
    (do
      (render-and-push! ns-sym @ctx-atom)
      true)
    false))

(comment
  ;; Example usage:

  ;; 1. Create ctx
  (def *ctx* (create! 'seon.example {:count 0}))

  ;; 2. Set render function
  (set-render-fn! 'seon.example
                  (fn [{:keys [count]}]
                    [:div#app
                     [:h1 "Count: " count]
                     [:button {:on:click :increment!} "+1"]]))

  ;; 3. Update triggers SSE push
  (swap! *ctx* update :count inc)

  ;; Check state
  (list-contexts)
  (client-count 'seon.example)
  (clients 'seon.example)  ; => #{#object[AsyncChannel ...]}

  ;; Cleanup
  (destroy! 'seon.example)
  )
