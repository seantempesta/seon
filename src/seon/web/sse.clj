(ns seon.web.sse
  "SSE (Server-Sent Events) implementation using Hyperlith patterns.

  Key insights:
  1. view = f(state) - Render full view, not deltas
  2. Hash-based change detection - Only send if view actually changed
  3. Streaming brotli - Compression over connection lifetime
  4. Throttling - Max refresh rate to prevent overload
  5. hk/as-channel - http-kit's async API for SSE"
  (:require [clojure.core.async :as a]
            [taoensso.timbre :as log]
            [org.httpkit.server :as hk]
            [seon.web.brotli :as br]))

;; Broadcast infrastructure
;; Single channel that gets mult'd to all connections
(defonce ^:private refresh-ch_ (atom nil))

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
  (let [{:keys [selector mode event-id]}
        (if (map? opts-or-event-id)
          opts-or-event-id
          {:event-id opts-or-event-id})
        mode-str (when (and mode (not= mode :outer))
                   (name mode))]
    (str "event: datastar-patch-elements"
         (when event-id (str "\nid: " event-id))
         (when selector (str "\ndata: selector " selector))
         (when mode-str (str "\ndata: mode " mode-str))
         "\ndata: elements " (clojure.string/replace elements "\n" "\ndata: elements ")
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
       "\ndata: script " (clojure.string/replace script "\n" "\ndata: script ")
       "\n\n\n"))

(defn send!
  "Send an SSE event down the http-kit channel.

  ch    - http-kit async channel
  event - SSE formatted string (already compressed)"
  [ch event]
  (hk/send! ch {:status  200
                :headers {"Content-Type"     "text/event-stream"
                          "Cache-Control"    "no-store"
                          "Content-Encoding" "br"}
                :body    event}
            false))  ; false = don't close channel

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

(defn render-handler
  "Create an SSE handler that re-renders on refresh events.

  render-fn - (fn [request] hiccup-or-html-string)
              Called on initial connection and every refresh.
              Should render the full view, not deltas.

  Options:
  - :on-open           - (fn [req]) called when connection opens
  - :on-close          - (fn [req]) called when connection closes
  - :br-window-size    - Brotli LZ77 window size (default: 18 = 262KB)
  - :render-on-connect - Render immediately on connect? (default: true)

  Returns: Ring handler function for http-kit"
  [render-fn & {:keys [on-open on-close br-window-size render-on-connect]
                :or   {br-window-size    18
                       render-on-connect true}}]
  (fn handler [req]
    (let [;; Dropping buffer - slow handlers won't block other handlers
          <ch     (a/tap (:seon.web.sse/refresh-mult req)
                         (a/chan (a/dropping-buffer 1)))
          ;; Ensure at least one render on connect
          _       (when render-on-connect (a/>!! <ch :first-render))
          ;; Poison pill for work cancelling
          <cancel (a/chan)]

      (hk/as-channel req
                     {:on-open
                      (fn hk-on-open [ch]
           ;; Virtual thread for handling SSE stream
                        (.start (Thread/ofVirtual)
                                (fn []
                                  (with-open [out (br/byte-array-out-stream)
                                              br  (br/compress-out-stream out
                                                                          :window-size br-window-size)]
                                    (loop [last-view-hash (get-in req [:headers "last-event-id"])]
                                      (a/alt!!
                           ;; Cancel signal from on-close
                                        [<cancel]
                                        (do (a/close! <ch)
                                            (a/close! <cancel))

                           ;; Refresh event
                                        [<ch]
                                        ([_]
                                         (when-some [recur-hash
                                                     (try
                                          ;; Render view (chassis h/html returns strings)
                                                       (when-some [new-view-str (render-fn req)]
                                                         (let [;; Fast hash for change detection
                                                               new-view-hash (Integer/toHexString
                                                                              (hash new-view-str))]
                                              ;; Only send if view actually changed
                                                           (when (not= last-view-hash new-view-hash)
                                                             (log/debug "Sending SSE update"
                                                                        {:hash new-view-hash
                                                                         :size (count new-view-str)})
                                                             (->> (patch-elements new-view-hash new-view-str)
                                                                  (br/compress-stream out br)
                                                                  (send! ch)))
                                                           new-view-hash))
                                                       (catch Exception e
                                                         (log/error e "Error rendering SSE view")
                                            ;; Continue with last hash on error
                                                         last-view-hash))]
                                           (recur recur-hash)))

                           ;; Priority for cancellation
                                        :priority true)))
                     ;; Close on error or when thread stops
                                  (hk/close ch)))
                        (when on-open (on-open req)))

                      :on-close
                      (fn hk-on-close [_ch _status]
                        (a/>!! <cancel :cancel)
                        (a/untap (:seon.web.sse/refresh-mult req) <ch)
                        (when on-close (on-close req)))}))))

(defn refresh-all!
  "Trigger a refresh for all connected SSE clients.

  This is the key function that causes all views to re-render.
  Call this when your state changes and you want the dashboard to update."
  [& _opts]
  (when-let [<refresh-ch @refresh-ch_]
    (a/>!! <refresh-ch :refresh-event)))

(defn init-sse!
  "Initialize the SSE broadcast infrastructure.

  Options:
  - :max-refresh-ms - Throttle to max refresh rate (milliseconds)
                      If nil, no throttling (updates happen immediately)

  Returns: refresh-mult that should be added to requests"
  [& {:keys [max-refresh-ms]}]
  (let [<refresh-ch (a/chan (a/dropping-buffer 1))
        _           (reset! refresh-ch_ <refresh-ch)
        refresh-mult (-> (if max-refresh-ms
                           (throttle <refresh-ch max-refresh-ms)
                           <refresh-ch)
                         a/mult)]
    (log/info "SSE broadcast initialized"
              {:throttle-ms max-refresh-ms})
    refresh-mult))

(defn wrap-refresh-mult
  "Ring middleware that adds refresh-mult to requests.

  This enables handlers to tap into the refresh stream."
  [handler refresh-mult]
  (fn [request]
    (handler (assoc request :seon.web.sse/refresh-mult refresh-mult))))
