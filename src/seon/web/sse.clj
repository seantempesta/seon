(ns seon.web.sse
  "SSE (Server-Sent Events) implementation following Datastar SDK patterns.

  Key insights:
  1. view = f(state) - Render full view, not deltas
  2. Hash-based change detection - Only send if view actually changed
  3. Headers sent ONCE in on-open, then only event bodies
  4. Write profiles for optional compression (Brotli)
  5. Content negotiation via Accept-Encoding header
  6. hk/as-channel - http-kit's async API for SSE"
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [org.httpkit.server :as hk])
  (:import [java.io ByteArrayOutputStream Closeable]))

;;; ---------------------------------------------------------------------------
;;; Write Profile Keys (following SDK pattern)
;;; ---------------------------------------------------------------------------

(def wrap-output-stream
  "Write profile key: Function (fn [ByteArrayOutputStream] -> Writer)
   that wraps the output stream with compression and/or buffering."
  ::wrap-output-stream)

(def write!
  "Write profile key: Function (fn [writer event-string] -> nil)
   that writes an event string to the wrapped writer."
  ::write!)

(def content-encoding
  "Write profile key: String value for Content-Encoding header.
   \"br\" for Brotli, \"gzip\" for gzip, nil for plain text."
  ::content-encoding)

;;; ---------------------------------------------------------------------------
;;; Broadcast infrastructure
;;; ---------------------------------------------------------------------------

(defonce ^:private refresh-ch_ (atom nil))

;;; ---------------------------------------------------------------------------
;;; Event Building
;;; ---------------------------------------------------------------------------

(defn patch-elements
  "Build a datastar SSE event for patching elements.

  Two call signatures:
  1. (patch-elements opts elements) - New API with options map
  2. (patch-elements event-id elements) - Backward compatible

  Options map:
  - :selector - CSS selector for target element (default: uses element's own ID)
  - :mode     - Patch mode keyword (default: :outer)
               :outer   - Morph element into existing element
               :inner   - Replace inner HTML of existing element
               :append  - Append inside existing element
               :prepend - Prepend inside existing element
               :before  - Insert before existing element
               :after   - Insert after existing element
               :replace - Replace existing element entirely
               :remove  - Remove existing element
  - :event-id - For idempotency/resumption (hash of content)

  elements - HTML string to patch"
  [opts-or-event-id elements]
  (let [{:keys [selector mode event-id use-view-transition?]}
        (if (map? opts-or-event-id)
          opts-or-event-id
          {:event-id opts-or-event-id})
        mode-str (when (and mode (not= mode :outer))
                   (name mode))]
    (str "event: datastar-patch-elements"
         (when event-id (str "\nid: " event-id))
         (when use-view-transition? "\ndata: useViewTransition true")
         (when selector (str "\ndata: selector " selector))
         (when mode-str (str "\ndata: mode " mode-str))
         "\ndata: elements " (str/replace elements "\n" "\ndata: elements ")
         "\n\n\n")))

(defn execute-script
  "Build a datastar SSE event for executing JavaScript.

  Options:
  - :script   - JavaScript code to execute (required)
  - :event-id - For idempotency/resumption (optional)

  Example:
  (execute-script {:script \"document.getElementById('chat').scrollTop = 9999999\"})"
  [{:keys [script event-id]}]
  (str "event: datastar-execute-script"
       (when event-id (str "\nid: " event-id))
       "\ndata: script " (str/replace script "\n" "\ndata: script ")
       "\n\n\n"))

;;; ---------------------------------------------------------------------------
;;; Headers (sent once on connection open)
;;; ---------------------------------------------------------------------------

(def ^:private base-sse-headers
  "Base headers for SSE connections."
  {"Content-Type"  "text/event-stream"
   "Cache-Control" "no-cache"})

(defn- add-keep-alive?
  "Check if Connection: keep-alive should be added based on protocol."
  [req]
  (let [protocol (:protocol req)]
    (or (nil? protocol)
        (neg? (compare protocol "HTTP/1.1")))))

(defn sse-headers
  "Build SSE response headers.

  Options:
  - ::content-encoding - Compression encoding (\"br\", \"gzip\", or nil)
  - :headers           - Additional headers to merge"
  [req & {:as opts}]
  (let [encoding (::content-encoding opts)]
    (cond-> (merge base-sse-headers (:headers opts))
      (add-keep-alive? req) (assoc "Connection" "keep-alive")
      encoding              (assoc "Content-Encoding" encoding))))

(defn send-headers!
  "Send SSE response headers. Call ONCE in on-open callback.

  ch   - http-kit async channel
  req  - Ring request (for protocol detection)
  opts - Options including ::content-encoding"
  [ch req opts]
  (hk/send! ch
            {:status 200
             :headers (sse-headers req opts)}
            false))

;;; ---------------------------------------------------------------------------
;;; Content Negotiation
;;; ---------------------------------------------------------------------------

(defn client-accepts-brotli?
  "Check if client accepts Brotli compression via Accept-Encoding header."
  [req]
  (when-let [accept (get-in req [:headers "accept-encoding"])]
    (str/includes? accept "br")))

;;; ---------------------------------------------------------------------------
;;; Send Functions (following SDK pattern)
;;; ---------------------------------------------------------------------------

(defn- ->send-simple
  "Create a simple send function for plain text (no compression).
   Returns (fn [event-string] -> boolean) that sends event and returns success."
  [ch]
  (fn send-simple [event]
    (hk/send! ch event false)))

(defn- flush-baos!
  "Flush ByteArrayOutputStream contents to channel and reset buffer."
  [^ByteArrayOutputStream baos ch]
  (let [data (.toByteArray baos)]
    (.reset baos)
    (hk/send! ch data false)))

(defn- ->send-with-output-stream
  "Create a send function that compresses through a write profile.
   The ByteArrayOutputStream and writer persist for connection lifetime,
   allowing the compressor to learn patterns (90-100x compression).

   Returns a map with:
   - :send-fn - (fn [event-string] -> boolean)
   - :close-fn - (fn [] -> nil) to close writer on connection end"
  [ch write-profile]
  (let [^ByteArrayOutputStream baos (ByteArrayOutputStream.)
        wrap-os (::wrap-output-stream write-profile)
        writer (wrap-os baos)]
    {:send-fn
     (fn send-compressed [event]
       (try
         ;; Write event through compression pipeline
         (.append ^java.io.Writer writer ^String event)
         (.flush ^java.io.Flushable writer)
         ;; Send compressed bytes
         (flush-baos! baos ch)
         (catch java.io.IOException _
           false)))
     :close-fn
     (fn close-writer []
       (try
         (.close ^Closeable writer)
         ;; Flush any remaining data
         (when (pos? (.size baos))
           (flush-baos! baos ch))
         (catch Exception _)))}))

(defn ->send-fn
  "Create appropriate send function based on write profile.

   Returns a map with:
   - :send-fn  - (fn [event-string] -> boolean)
   - :close-fn - (fn [] -> nil) for cleanup (may be nil)"
  [ch write-profile]
  (if (::wrap-output-stream write-profile)
    (->send-with-output-stream ch write-profile)
    {:send-fn (->send-simple ch)
     :close-fn nil}))

;;; ---------------------------------------------------------------------------
;;; Throttling
;;; ---------------------------------------------------------------------------

(defn throttle
  "Throttle a channel to maximum frequency.

  <in-ch - Input channel to throttle
  msec   - Minimum milliseconds between emits

  Returns: Output channel that emits at most every msec milliseconds"
  [<in-ch msec]
  (let [<out-ch (a/chan)]
    (.start (Thread/ofVirtual)
            (fn []
              (loop []
                (when-some [event (a/<!! <in-ch)]
                  (a/>!! <out-ch event)
                  (Thread/sleep ^long msec)
                  (recur)))
              (a/close! <out-ch)))
    <out-ch))

;;; ---------------------------------------------------------------------------
;;; Rendering Loop
;;; ---------------------------------------------------------------------------

(def ^:private keepalive-comment
  "SSE comment used as keepalive to detect dead connections."
  ": keepalive\n\n")

(defn- do-render
  "Render view and send SSE update if changed.
   Returns new hash, or nil if connection dead."
  [render-fn req send-fn last-view-hash use-view-transition?]
  (try
    (when-some [new-view-str (render-fn req)]
      (let [new-view-hash (Integer/toHexString (hash new-view-str))]
        (if (not= last-view-hash new-view-hash)
          ;; View changed - send update
          (let [sent? (send-fn (patch-elements {:event-id new-view-hash
                                                :use-view-transition? use-view-transition?}
                                               new-view-str))]
            (if sent?
              (do (log/debug "Sending SSE update" {:hash new-view-hash :size (count new-view-str)})
                  new-view-hash)
              (do (log/debug "SSE send failed, closing dead connection")
                  nil)))
          ;; View unchanged - send keepalive to detect dead connections
          (let [sent? (send-fn keepalive-comment)]
            (if sent?
              new-view-hash
              (do (log/debug "SSE keepalive failed, closing dead connection")
                  nil))))))
    (catch Exception e
      (log/error e "Error rendering SSE view")
      last-view-hash)))

;;; ---------------------------------------------------------------------------
;;; Handler Factory
;;; ---------------------------------------------------------------------------

(defn render-handler
  "Create an SSE handler that re-renders on refresh events.

  render-fn - (fn [request] hiccup-or-html-string)
              Called on initial connection and every refresh.
              Should render the full view, not deltas.

  Options:
  - :on-open           - (fn [req]) called when connection opens
  - :on-close          - (fn [req]) called when connection closes
  - :render-on-connect - Render immediately on connect? (default: true)
  - :poll-ms           - If set, poll for changes at this interval (milliseconds)
                         Useful for views that don't have explicit refresh triggers.
  - :write-profile     - Optional write profile for compression.
                         If nil and client accepts Brotli, uses Brotli profile.
                         If nil and client doesn't accept Brotli, uses plain text.
  - :auto-brotli?      - Enable automatic Brotli negotiation (default: true)
  - :use-view-transition? - Enable Chrome View Transitions API on patches (default: false).
                            Animates DOM changes but adds ~2-3s delay. Only useful for
                            page-level transitions, not incremental state updates.

  Returns: Ring handler function for http-kit"
  [render-fn & {:keys [on-open on-close render-on-connect poll-ms
                       write-profile auto-brotli? use-view-transition?]
                :or   {render-on-connect true
                       auto-brotli? false
                       use-view-transition? false}}]
  (fn handler [req]
    (let [;; Sliding buffer - always keeps the latest event, never silently drops
          <ch     (a/tap (::refresh-mult req)
                         (a/chan (a/sliding-buffer 1)))
          ;; Ensure at least one render on connect
          _       (when render-on-connect (a/>!! <ch :first-render))
          ;; Poison pill for work cancelling
          <cancel (a/chan)
          ;; Determine write profile (auto-negotiate if not specified)
          effective-profile (cond
                              write-profile write-profile
                              (and auto-brotli? (client-accepts-brotli? req))
                              (do
                                (log/debug "Client accepts Brotli, using compression")
                                ;; Lazy require to avoid loading Brotli when not needed
                                (require 'seon.web.brotli)
                                ((resolve 'seon.web.brotli/->write-profile)))
                              :else nil)]

      (hk/as-channel req
                     {:on-open
                      (fn hk-on-open [ch]
                        ;; Send headers ONCE before starting render loop
                        (send-headers! ch req {::content-encoding (::content-encoding effective-profile)})

                        ;; Create send function (plain or compressed)
                        (let [{:keys [send-fn close-fn]} (->send-fn ch effective-profile)]
                          ;; Virtual thread for handling SSE stream
                          (.start (Thread/ofVirtual)
                                  (fn []
                                    (loop [last-view-hash (get-in req [:headers "last-event-id"])]
                                      (let [[_val port]
                                            (if poll-ms
                                              ;; With polling: wait for refresh, cancel, or timeout
                                              (a/alts!! [<cancel <ch (a/timeout poll-ms)]
                                                        :priority true)
                                              ;; Without polling: wait for refresh or cancel
                                              (a/alts!! [<cancel <ch]
                                                        :priority true))]
                                        (cond
                                          ;; Cancel signal - stop the loop
                                          (= port <cancel)
                                          (do (a/close! <ch)
                                              (a/close! <cancel))

                                          ;; Refresh or timeout - render and continue
                                          :else
                                          (when-some [new-hash (do-render render-fn req send-fn last-view-hash use-view-transition?)]
                                            (recur new-hash)))))
                                    ;; Cleanup compression resources if any
                                    (when close-fn (close-fn))
                                    ;; Close on error or when thread stops
                                    (hk/close ch))))
                        (when on-open (on-open req)))

                      :on-close
                      (fn hk-on-close [_ch _status]
                        (a/>!! <cancel :cancel)
                        (a/untap (::refresh-mult req) <ch)
                        (when on-close (on-close req)))}))))

;;; ---------------------------------------------------------------------------
;;; Deprecated send! (for backwards compatibility during migration)
;;; ---------------------------------------------------------------------------

(defn send!
  "DEPRECATED: Sends headers on every call. Use send-headers! + send-fn pattern.

  Kept for backwards compatibility during migration."
  {:deprecated "Use send-headers! + ->send-fn pattern"}
  [ch event]
  (hk/send! ch {:status  200
                :headers {"Content-Type"  "text/event-stream"
                          "Cache-Control" "no-store"}
                :body    event}
            false))

;;; ---------------------------------------------------------------------------
;;; Broadcast Infrastructure
;;; ---------------------------------------------------------------------------

(defn refresh-all!
  "Trigger a refresh for all connected SSE clients.

  This is the key function that causes all views to re-render.
  Call this when your state changes and you want the dashboard to update."
  [& _opts]
  (when-let [<refresh-ch @refresh-ch_]
    (a/>!! <refresh-ch :refresh-event)))

(defn init-sse!
  "Initialize the SSE broadcast infrastructure.

  Safe to call multiple times - closes old channels before creating new ones.
  This prevents core.async protocol corruption during reloads.

  Options:
  - :max-refresh-ms - Throttle to max refresh rate (milliseconds)
                      If nil, no throttling (updates happen immediately)

  Returns: refresh-mult that should be added to requests"
  [& {:keys [max-refresh-ms]}]
  ;; Close old channel if it exists (prevents protocol corruption on reload)
  (when-let [old-ch @refresh-ch_]
    (try
      (a/close! old-ch)
      (catch Exception _)))
  (let [<refresh-ch (a/chan (a/dropping-buffer 1))
        _           (reset! refresh-ch_ <refresh-ch)
        refresh-mult (-> (if max-refresh-ms
                           (throttle <refresh-ch max-refresh-ms)
                           <refresh-ch)
                         a/mult)]
    (log/info "SSE broadcast initialized"
              {:throttle-ms max-refresh-ms})
    refresh-mult))

(defn shutdown-sse!
  "Shut down SSE infrastructure. Call on server halt to prevent protocol corruption."
  []
  (when-let [ch @refresh-ch_]
    (try
      (a/close! ch)
      (catch Exception _))
    (reset! refresh-ch_ nil)
    (log/info "SSE broadcast shut down")))

(defn wrap-refresh-mult
  "Ring middleware that adds refresh-mult to requests.

  This enables handlers to tap into the refresh stream."
  [handler refresh-mult]
  (fn [request]
    (handler (assoc request ::refresh-mult refresh-mult))))
