(ns seon.web.serve
  "Pod-side HTTP+SSE server on a loopback ephemeral port.

   Per spec-05 §10.2 A-5 + §21.1 the pod hosts its own minimal HTTP
   surface so a browser (Chrome in Lane A dev, Tauri WebView in Lane B
   prod) can reach the agent UI without intermediate infrastructure.

   Routes (V0.5):
     GET  /                  → seon.web.page/root-html (the shell)
     GET  /css/output.css    → resources/public/css/output.css
     GET  /js/datastar.js    → resources/public/js/datastar.js
     GET  /sse               → SSE stream (A-6 wires broadcast)
     POST /chat              → A-8 (user message → :seon.message/role :user tx)

   ## Port discovery

   `start!` listens on port 0 (ephemeral), reads the bound port from
   `server.address()`, and writes it to `$SEON_PORT_FILE` (default
   `/tmp/seon-port`). External tooling (`bin/dev-harness`, the
   convergence test in spec-05 §21.3) reads this file rather than
   parsing logs.

   ## V0.5 throwaway

   When V1+ lands the JVM seon server takes over HTTP+SSE rendering
   (it already has a similar pipeline in `seon.web.sse` per the
   2026-05-19 audit). This namespace becomes dev-mode only — a
   standalone-pod render path so we can iterate on agent code in
   Chrome without booting the full server stack. The CLJS pod's role
   in the V0.5 demo Tauri shell becomes 'eval substrate', not
   'HTTP server'.

   ## SSE connection registry

   A-6 will register each open SSE stream's `response` object in
   `!sse-connections` so the broadcast tx-listener can write
   `datastar-patch-elements` events. Today A-5 ships the registry +
   the connection-add-on-open + connection-remove-on-close lifecycle;
   broadcast.cljs gets to assume the registry exists."
  (:require
    ["node:http" :as http]
    ["node:fs" :as fs]
    ["node:path" :as path]
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.web.page :as page]))

;; ============================================================
;; Process-lifetime state
;; ============================================================

(defonce ^{:doc "The bound HTTP server, or nil before start!."}
  !server (atom nil))

(defonce ^{:doc "Connection registry — atom of vector of
                  `{:id <uuid> :res <http.ServerResponse>}` for every
                  open SSE stream. A-6 reads this to fan out
                  datastar-patch-elements events per tx."}
  !sse-connections (atom []))

(defn open-sse-connections
  "Public accessor — returns the current vector of open SSE
   connections. A-6 will close over this via `seon.db/listen!`."
  []
  @!sse-connections)

;; ============================================================
;; Static serving
;;
;; Map URL prefix → disk root (relative to cwd). The pod is started
;; from the seon submodule root (`./bin/start` → seon/bin/run), so
;; cwd should be the seon dir; relative paths resolve under it.
;; ============================================================

(def ^:private static-roots
  {"/css/" "resources/public/css/"
   "/js/"  "resources/public/js/"})

(defn- mime-type [filename]
  (cond
    (str/ends-with? filename ".css")  "text/css; charset=utf-8"
    (str/ends-with? filename ".js")   "application/javascript; charset=utf-8"
    (str/ends-with? filename ".html") "text/html; charset=utf-8"
    (str/ends-with? filename ".json") "application/json; charset=utf-8"
    (str/ends-with? filename ".png")  "image/png"
    (str/ends-with? filename ".svg")  "image/svg+xml"
    :else                             "application/octet-stream"))

(defn- write-status! [res code mime body]
  (.writeHead res code #js {"Content-Type" mime
                            "Cache-Control" "no-cache"})
  (.end res body))

(defn- serve-static! [res url]
  (if-let [[prefix root] (some (fn [[p r]]
                                 (when (str/starts-with? url p) [p r]))
                               static-roots)]
    (let [rel  (subs url (count prefix))
          ;; Path-traversal guard — reject relative segments that
          ;; escape the static root. `node:path/normalize` collapses
          ;; `..` segments; if the result begins with `..` or contains
          ;; one, refuse.
          safe (.normalize path rel)]
      (if (or (str/blank? safe)
              (str/starts-with? safe "..")
              (str/includes? safe "/..")
              (.isAbsolute path safe))
        (write-status! res 404 "text/plain; charset=utf-8" (str "Not found: " url))
        (let [full (.join path root safe)]
          (try
            (let [body (.readFileSync fs full)]
              (write-status! res 200 (mime-type full) body))
            (catch :default _
              (write-status! res 404 "text/plain; charset=utf-8" (str "Not found: " url)))))))
    (write-status! res 404 "text/plain; charset=utf-8" (str "Not found: " url))))

;; ============================================================
;; Route handlers
;; ============================================================

(defn- serve-root! [res]
  (write-status! res 200 "text/html; charset=utf-8" (page/root-html)))

(defn- open-sse! [req res]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  ;; Flush headers immediately so the browser registers the stream.
  ;; SSE-spec comment lines (begin with `:`) are ignored by clients.
  (.write res ": connected\n\n")
  ;; Register the connection so A-6's broadcast can write into it.
  (let [conn {:id (random-uuid) :res res :opened-at (js/Date.)}]
    (swap! !sse-connections conj conn)
    (.on req "close"
         (fn []
           (swap! !sse-connections
                  (fn [conns] (vec (remove #(= (:id %) (:id conn)) conns))))))))

;; ============================================================
;; POST /chat — inject a :user message into the named agent's log.
;; The agent's kick listener (seon.agent/install-kick!) fires on the
;; resulting tx, run-turn-once! starts, the LLM responds, broadcast
;; morphs the tile via SSE.
;;
;; Body is application/x-www-form-urlencoded (Datastar's
;; `@post('/chat', {contentType:'form'})` posts FormData). `agent` is
;; in the query string (defaults to "seon").
;; ============================================================

(defn- read-body
  "Collect a Node request body into a String. Returns a Promise."
  [req]
  (js/Promise.
    (fn [resolve _reject]
      (let [chunks (atom [])]
        (.on req "data"
             (fn [chunk]
               (swap! chunks conj chunk)))
        (.on req "end"
             (fn []
               (resolve (.toString
                          (.concat js/Buffer (clj->js @chunks))))))))))

(defn- parse-urlencoded
  "Parse an `application/x-www-form-urlencoded` body into a map of
   String → String. URLSearchParams handles RFC 3986 percent decoding."
  [body]
  (let [params (js/URLSearchParams. body)]
    (into {} (map (fn [[k v]] [k v]) (es6-iterator-seq (.entries params))))))

(defn- query-param
  "Pull a single query-string value out of `req.url`. Returns nil if
   absent. Defensive against malformed URLs."
  [req k]
  (try
    (let [full-url (str "http://x" (.-url req))   ; URL needs an origin
          u (js/URL. full-url)]
      (.get (.-searchParams u) k))
    (catch :default _ nil)))

(defn- handle-chat! [req res]
  (let [agent-id (or (query-param req "agent") "seon")]
    (-> (read-body req)
        (.then (fn [body]
                 (let [params (parse-urlencoded body)
                       text   (get params "text")]
                   (if (or (nil? text) (str/blank? text))
                     (write-status! res 400 "text/plain; charset=utf-8"
                                    "missing 'text' param")
                     (-> (agent/chat agent-id text)
                         (.then (fn [_mid]
                                  (write-status! res 204 "text/plain; charset=utf-8" "")))
                         (.catch (fn [err]
                                   (js/console.error "[seon.web.serve] /chat agent/chat threw:" err)
                                   (write-status! res 500 "text/plain; charset=utf-8"
                                                  (str "chat failed: " err)))))))))
        (.catch (fn [err]
                  (js/console.error "[seon.web.serve] /chat body read failed:" err)
                  (try
                    (write-status! res 500 "text/plain; charset=utf-8" (str err))
                    (catch :default _ nil)))))))

(defn- handler [req res]
  (let [url    (or (.-url req) "/")
        ;; Strip query string for routing match
        path   (first (str/split url #"\?"))
        method (or (.-method req) "GET")]
    (try
      (case method
        "GET"  (cond
                 (= path "/")                       (serve-root! res)
                 (str/starts-with? path "/css/")    (serve-static! res path)
                 (str/starts-with? path "/js/")     (serve-static! res path)
                 (= path "/sse")                    (open-sse! req res)
                 :else                              (write-status! res 404 "text/plain; charset=utf-8"
                                                                   (str "Not found: " url)))
        "POST" (cond
                 (= path "/chat")                   (handle-chat! req res)
                 :else                              (write-status! res 404 "text/plain; charset=utf-8"
                                                                   (str "Not found: " url)))
        (write-status! res 405 "text/plain; charset=utf-8" "Method not allowed"))
      (catch :default e
        (js/console.error "[seon.web.serve] handler error:" e)
        (try
          (write-status! res 500 "text/plain; charset=utf-8" (str "Internal error: " e))
          (catch :default _ nil))))))

;; ============================================================
;; Lifecycle
;; ============================================================

(defn- write-port-file! [port]
  (let [target (or (.. js/process -env -SEON_PORT_FILE)
                   "/tmp/seon-port")]
    (.writeFileSync fs target (str port))
    target))

(defn start!
  "Start the HTTP+SSE server on an ephemeral loopback port. Returns a
   Promise resolving to:
     {:seon.web/port <int> :seon.web/port-file <abs-path>}

   Writes the bound port to $SEON_PORT_FILE (default /tmp/seon-port).
   Idempotent — repeat calls close the old server first.

   The server binds to 127.0.0.1 (loopback only). Browsers on the
   same machine can connect; nothing on the LAN sees the pod."
  {:malli/schema [:=> [:cat] :any]}
  []
  (js/Promise.
    (fn [resolve reject]
      (when-let [old @!server]
        (try (.close old) (catch :default _ nil))
        (reset! !server nil)
        (reset! !sse-connections []))
      (let [server (.createServer http handler)]
        (.once server "error" reject)
        (.listen server 0 "127.0.0.1"
                 (fn []
                   (let [addr (.address server)
                         port (.-port addr)
                         port-file (write-port-file! port)]
                     (reset! !server server)
                     (js/console.log "[seon.web.serve] listening on http://127.0.0.1:"
                                     port "— port written to" port-file)
                     (resolve {:seon.web/port port
                               :seon.web/port-file port-file}))))))))

(defn stop!
  "Close the HTTP server, clear the connection registry. Returns nil."
  []
  (when-let [server @!server]
    (.close server)
    (reset! !server nil))
  (reset! !sse-connections [])
  nil)
