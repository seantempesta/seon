(ns seon.web.reactive.instance
  "Reactive UI instance management.

   Each instance is an isolated reactive UI context with:
   - Unique instance ID (4-char hex like agent sessions)
   - *ctx* atom for state
   - SSE clients set for push updates
   - Render function for transforming state to HTML

   This is a simplified version of agent sessions without XTDB persistence
   or nREPL servers. Instances are lightweight and ephemeral.

   Usage:
     ;; Create instance
     (def result (create-instance! {::namespace 'seon.trading
                                    ::initial-value {:signals []}}))
     (def id (::id result))

     ;; Get the ctx atom
     (def *ctx* (::atom (get-instance {::id id})))

     ;; Set render function
     (set-render-fn! {::id id
                      ::render-fn (fn [{:keys [signals]}]
                                    [:div#app
                                     [:h1 \"Signals: \" (count signals)]])})

     ;; Update triggers SSE push to all connected clients
     (swap! *ctx* update :signals conj {:symbol \"AAPL\"})

     ;; Cleanup
     (destroy-instance! {::id id})"
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as h]
            [org.httpkit.server :as hk]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.security SecureRandom]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::id
                  [:string {:min 4 :max 4
                            :pattern "^[a-f0-9]{4}$"
                            :description "4-character hex instance ID"}])

(schema/register! ::namespace
                  [:symbol {:description "Namespace symbol for action URL generation"}])

(schema/register! ::initial-value
                  [:any {:description "Initial ctx value"}])

(schema/register! ::atom
                  [:any {:description "The ctx atom for this instance"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate atom"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::clients
                  [:any {:description "Atom containing set of SSE client channels"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate clients atom"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::render-fn
                  [:any {:description "Function that takes ctx value and returns hiccup"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate render-fn"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::created-at
                  [inst? {:description "When the instance was created"}])

(schema/register! ::channel
                  [:any {:description "http-kit async channel"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate channel"
                                                           {:type :malli.generator/no-generator})))}])

;;; Instance info map
(schema/register! ::instance
                  [:map
                   [::id ::id]
                   [::namespace ::namespace]
                   [::atom ::atom]
                   [::clients ::clients]
                   [::render-fn {:optional true} [:maybe ::render-fn]]
                   [::created-at ::created-at]])

;;; Request/Response Schemas

(schema/register! ::create-instance-request
                  [:map
                   [::namespace ::namespace]
                   [::initial-value {:optional true} ::initial-value]])

(schema/register! ::create-instance-response
                  [:map
                   [::id ::id]
                   [::namespace ::namespace]
                   [::created-at ::created-at]])

(schema/register! ::get-instance-request
                  [:map
                   [::id ::id]])

(schema/register! ::get-instance-response
                  [:map
                   [::id {:optional true} ::id]
                   [::namespace {:optional true} ::namespace]
                   [::atom {:optional true} ::atom]
                   [::clients {:optional true} ::clients]
                   [::render-fn {:optional true} [:maybe ::render-fn]]
                   [::created-at {:optional true} ::created-at]])

(schema/register! ::destroy-instance-request
                  [:map
                   [::id ::id]])

(schema/register! ::destroy-instance-response
                  [:map
                   [::destroyed :boolean]])

(schema/register! ::set-render-fn-request
                  [:map
                   [::id ::id]
                   [::render-fn ::render-fn]])

(schema/register! ::set-render-fn-response
                  [:map
                   [::set :boolean]])

(schema/register! ::register-client-request
                  [:map
                   [::id ::id]
                   [::channel ::channel]])

(schema/register! ::register-client-response
                  [:map
                   [::registered :boolean]])

(schema/register! ::unregister-client-request
                  [:map
                   [::id ::id]
                   [::channel ::channel]])

(schema/register! ::unregister-client-response
                  [:map
                   [::unregistered :boolean]])

(schema/register! ::clients-request
                  [:map
                   [::id ::id]])

(schema/register! ::clients-response
                  [:map
                   [::clients [:maybe [:set ::channel]]]])

(schema/register! ::client-count-request
                  [:map
                   [::id ::id]])

(schema/register! ::client-count-response
                  [:map
                   [::count :int]])

(schema/register! ::force-push-request
                  [:map
                   [::id ::id]])

(schema/register! ::force-push-response
                  [:map
                   [::pushed :boolean]])

(schema/register! ::list-instances-request
                  [:map])

(schema/register! ::list-instances-response
                  [:map
                   [::instances [:vector ::id]]])

(schema/register! ::instance-ctx-request
                  [:map
                   [::id ::id]])

(schema/register! ::instance-ctx-response
                  [:map
                   [::atom {:optional true} [:maybe ::atom]]])

(schema/register! ::instance-namespace-request
                  [:map
                   [::id ::id]])

(schema/register! ::instance-namespace-response
                  [:map
                   [::namespace {:optional true} [:maybe ::namespace]]])

;;; ---------------------------------------------------------------------------
;;; Instance ID Generation
;;; ---------------------------------------------------------------------------

(def ^:private secure-random (SecureRandom.))

(defn- generate-instance-id
  "Generate a 4-character hex instance ID."
  []
  (let [bytes (byte-array 2)]
    (.nextBytes secure-random bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

;;; ---------------------------------------------------------------------------
;;; Registry
;;; ---------------------------------------------------------------------------

;; Map of instance-id -> instance-info
(defonce ^:private registry (atom {}))

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
      (hk/send! channel (format-sse-event html-str) false)
      true)
    (catch Exception e
      (log/debug "Failed to push to client" {:error (.getMessage e)})
      false)))

(defn- push-update!
  "Push update to all clients for an instance."
  [instance-id html-str]
  (when-let [instance (get @registry instance-id)]
    (let [clients-atom (::clients instance)
          channels @clients-atom
          results (doall (map #(push-to-client! % html-str) channels))
          failed (count (filter false? results))]
      (when (pos? failed)
        ;; Clean up dead channels
        (swap! clients-atom #(set (filter hk/open? %))))
      (log/debug "Pushed SSE update" {:instance-id instance-id
                                       :clients (count channels)
                                       :failed failed}))))

;;; ---------------------------------------------------------------------------
;;; Render Integration
;;; ---------------------------------------------------------------------------

(defn- render-and-push!
  "Render current ctx state and push to all clients."
  [instance-id ctx-value]
  (when-let [instance (get @registry instance-id)]
    (when-let [render-fn (::render-fn instance)]
      (try
        (let [ns-sym (::namespace instance)
              hiccup (render-fn ctx-value)
              ;; Transform hiccup to Datastar format, including instance-id in action URLs
              transformed ((requiring-resolve 'seon.web.reactive.transform/transform-hiccup)
                           ns-sym hiccup instance-id)
              html-str (str (h/html transformed))]
          (push-update! instance-id html-str))
        (catch Exception e
          (log/error e "Render failed" {:instance-id instance-id}))))))

;;; ---------------------------------------------------------------------------
;;; Watch
;;; ---------------------------------------------------------------------------

(defn- make-watch
  "Create a watch function that pushes updates on ctx change."
  [instance-id]
  (fn [_key _ref old-val new-val]
    (when (not= old-val new-val)
      (render-and-push! instance-id new-val))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn create-instance!
  "Create a new reactive instance.

   Request keys:
     ::namespace     - Required. Namespace symbol for action URL generation
     ::initial-value - Optional. Initial ctx value (default: {})

   Response keys:
     ::id         - 4-char hex instance ID
     ::namespace  - The namespace symbol
     ::created-at - When instance was created

   Example:
     (create-instance! {::namespace 'seon.trading ::initial-value {:signals []}})"
  {:malli/schema [:=> [:cat ::create-instance-request] ::create-instance-response]}
  [{::keys [namespace initial-value]}]
  (let [instance-id (generate-instance-id)
        ctx-atom (atom (or initial-value {}))
        clients-atom (atom #{})
        created-at (java.util.Date.)
        instance {::id instance-id
                  ::namespace namespace
                  ::atom ctx-atom
                  ::clients clients-atom
                  ::render-fn nil
                  ::created-at created-at}]
    ;; Register in registry
    (swap! registry assoc instance-id instance)
    ;; Add watch for SSE push
    (add-watch ctx-atom ::sse-push (make-watch instance-id))
    (log/info "Created reactive instance" {:instance-id instance-id :ns namespace})
    {::id instance-id
     ::namespace namespace
     ::created-at created-at}))

(defn get-instance
  "Get the full instance info map.

   Request keys:
     ::id - Required. The instance ID

   Response keys:
     ::id         - Instance ID (present if found)
     ::namespace  - Namespace symbol
     ::atom       - The ctx atom
     ::clients    - Atom containing set of SSE channels
     ::render-fn  - The render function (may be nil)
     ::created-at - When instance was created

   Returns empty map if instance not found.

   Example:
     (get-instance {::id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::get-instance-request] ::get-instance-response]}
  [{::keys [id]}]
  (or (get @registry id) {}))

(defn destroy-instance!
  "Destroy a reactive instance, closing all client connections.

   Request keys:
     ::id - Required. The instance ID

   Response keys:
     ::destroyed - Whether instance was found and destroyed

   Example:
     (destroy-instance! {::id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::destroy-instance-request] ::destroy-instance-response]}
  [{::keys [id]}]
  (if-let [instance (get @registry id)]
    (do
      ;; Close all client connections
      (doseq [ch @(::clients instance)]
        (try (hk/close ch) (catch Exception _)))
      ;; Remove watch
      (remove-watch (::atom instance) ::sse-push)
      ;; Remove from registry
      (swap! registry dissoc id)
      (log/info "Destroyed reactive instance" {:instance-id id})
      {::destroyed true})
    (do
      (log/warn "Instance not found for destroy" {:instance-id id})
      {::destroyed false})))

(defn instance-ctx
  "Get the *ctx* atom for an instance.

   Request keys:
     ::id - Required. The instance ID

   Response keys:
     ::atom - The ctx atom (nil if instance not found)

   Example:
     (instance-ctx {::id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::instance-ctx-request] ::instance-ctx-response]}
  [{::keys [id]}]
  (if-let [instance (get @registry id)]
    {::atom (::atom instance)}
    {::atom nil}))

(defn set-render-fn!
  "Set the render function for an instance.

   render-fn should take the ctx value and return hiccup.
   When ctx changes, render-fn is called and result pushed via SSE.

   Request keys:
     ::id        - Required. The instance ID
     ::render-fn - Required. Function (ctx-value) -> hiccup

   Response keys:
     ::set - Whether render-fn was set (false if instance not found)

   Example:
     (set-render-fn! {::id \"a1b2\"
                      ::render-fn (fn [{:keys [count]}]
                                    [:div#app [:h1 \"Count: \" count]])})"
  {:malli/schema [:=> [:cat ::set-render-fn-request] ::set-render-fn-response]}
  [{::keys [id render-fn]}]
  (if (contains? @registry id)
    (do
      (swap! registry assoc-in [id ::render-fn] render-fn)
      {::set true})
    {::set false}))

(defn register-client!
  "Register an SSE client channel for an instance.

   Note: Caller is responsible for cleanup on disconnect.
   Use unregister-client! in your on-close handler.

   Request keys:
     ::id      - Required. The instance ID
     ::channel - Required. The http-kit async channel

   Response keys:
     ::registered - Whether client was registered (false if instance not found)

   Example:
     (register-client! {::id \"a1b2\" ::channel ch})"
  {:malli/schema [:=> [:cat ::register-client-request] ::register-client-response]}
  [{::keys [id channel]}]
  (if-let [instance (get @registry id)]
    (do
      (swap! (::clients instance) conj channel)
      (log/debug "Client registered" {:instance-id id})
      {::registered true})
    (do
      (log/warn "Cannot register client - instance not found" {:instance-id id})
      {::registered false})))

(defn unregister-client!
  "Unregister an SSE client channel from an instance.

   Call this in your on-close handler to clean up.

   Request keys:
     ::id      - Required. The instance ID
     ::channel - Required. The http-kit async channel

   Response keys:
     ::unregistered - Whether client was unregistered (false if instance not found)

   Example:
     (unregister-client! {::id \"a1b2\" ::channel ch})"
  {:malli/schema [:=> [:cat ::unregister-client-request] ::unregister-client-response]}
  [{::keys [id channel]}]
  (if-let [instance (get @registry id)]
    (do
      (swap! (::clients instance) disj channel)
      (log/debug "Client unregistered" {:instance-id id})
      {::unregistered true})
    {::unregistered false}))

(defn clients
  "Get the set of connected client channels for an instance.

   Request keys:
     ::id - Required. The instance ID

   Response keys:
     ::clients - Set of http-kit channels (nil if instance not found)

   Example:
     (clients {::id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::clients-request] ::clients-response]}
  [{::keys [id]}]
  (if-let [instance (get @registry id)]
    {::clients @(::clients instance)}
    {::clients nil}))

(defn client-count
  "Get the number of connected clients for an instance.

   Request keys:
     ::id - Required. The instance ID

   Response keys:
     ::count - Number of connected clients (0 if instance not found)

   Example:
     (client-count {::id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::client-count-request] ::client-count-response]}
  [{::keys [id]}]
  (let [result (clients {::id id})]
    {::count (count (or (::clients result) #{}))}))

(defn force-push!
  "Force push current state to all clients.

   Useful for initial render when client first connects.

   Request keys:
     ::id - Required. The instance ID

   Response keys:
     ::pushed - Whether push was attempted (false if instance not found)

   Example:
     (force-push! {::id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::force-push-request] ::force-push-response]}
  [{::keys [id]}]
  (if-let [instance (get @registry id)]
    (do
      (render-and-push! id @(::atom instance))
      {::pushed true})
    {::pushed false}))

(defn list-instances
  "List all active instance IDs.

   Request keys: (none required)

   Response keys:
     ::instances - Vector of instance IDs

   Example:
     (list-instances {})"
  {:malli/schema [:=> [:cat ::list-instances-request] ::list-instances-response]}
  [{}]
  {::instances (vec (keys @registry))})

(defn instance-namespace
  "Get the namespace symbol for an instance.

   Request keys:
     ::id - Required. The instance ID

   Response keys:
     ::namespace - The namespace symbol (nil if instance not found)

   Example:
     (instance-namespace {::id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::instance-namespace-request] ::instance-namespace-response]}
  [{::keys [id]}]
  (if-let [instance (get @registry id)]
    {::namespace (::namespace instance)}
    {::namespace nil}))

(comment
  ;; Example usage:

  ;; 1. Create instance
  (def result (create-instance! {::namespace 'test.ns ::initial-value {:count 0}}))
  (def id (::id result))

  ;; 2. Get instance info
  (get-instance {::id id})

  ;; 3. Get ctx atom
  @(::atom (instance-ctx {::id id}))

  ;; 4. Set render function
  (set-render-fn! {::id id
                   ::render-fn (fn [{:keys [count]}]
                                 [:div#app
                                  [:h1 "Count: " count]
                                  [:button {:on:click :increment!} "+1"]])})

  ;; 5. Update triggers SSE push (if clients connected)
  (swap! (::atom (instance-ctx {::id id})) update :count inc)

  ;; 6. Check state
  (list-instances {})
  (client-count {::id id})
  (clients {::id id})

  ;; 7. Cleanup
  (destroy-instance! {::id id})

  nil)
