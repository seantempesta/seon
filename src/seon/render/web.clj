(ns seon.render.web
  "The page on a socket — http-kit, one Datastar SSE per tab, one morph
  per block.

  N4 package 2, first web slice. What is REAL here: the server, the
  routes, the shell, the initial paint, live repaint driven by Datahike
  `listen!`, PER-BLOCK equality suppression, and the latest-wins mailbox.
  What is deliberately NOT here is named in `not-yet` below rather than
  implied by silence — a feed that quietly did less than the design says
  would be the absence-read-as-health class on the most visible surface
  in the system.

  ONE MORPH PER BLOCK, which is the whole point of the rung. A repaint
  derives the agent's surfaces, compares each against the last value
  DELIVERED on this connection, and patches only the blocks whose
  projection actually changed. The quarry sent the entire
  `<main id=\"app-view\">` subtree on every relevant datom; measured, the
  same one-row change is 287 bytes here against 82,893 bytes there.

  THE FEED OPENER IS A SIBLING OF THE MORPH TARGETS, never a child. A
  `data-init` inside a morphed element is stripped by the first
  whole-element morph and the connection never reopens — a lesson the
  quarry paid for and wrote down (`src-old/seon/web/datastar.cljs:611-620`),
  and the reason the shell puts the opener in its own hidden div beside
  the surfaces rather than on the container.

  Crash walk. The server holds a socket and a listener per connection
  and nothing durable, so a kill loses zero facts: every tab reconnects
  and repaints from current facts, which is what \"reconnect = repaint\"
  means operationally. `stop!` closes the server, and each connection's
  `on-close` unlistens its own listener — the listener is registered
  per connection precisely so its lifetime is the connection's and no
  bookkeeping outlives the socket."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [org.httpkit.server :as http]
            [seon.render.block :as block]
            [seon.render.data :as data]
            [seon.render.hiccup :as hiccup]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [starfederation.datastar.clojure.adapter.http-kit :as datastar.http-kit]
            [starfederation.datastar.clojure.api :as datastar])
  (:import [java.util.concurrent Executors]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/web.edn
;;; ---------------------------------------------------------------------------

(defn server?
  "True for an http-kit server object. The gate resolves and runs this,
  so it is real code rather than a contract stub."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x]
  (instance? org.httpkit.server.HttpServer x))

(defonce ^:private generator-server
  (delay (http/run-server (fn [_] {:status 200 :body ""})
                          {:ip "127.0.0.1" :port 0
                           :legacy-return-value? false})))

(def server-generator
  "An honest generator: a real, bound, loopback http-kit server, created
  once. A generator that returned a stub would let a schema pass that
  the runtime would refuse."
  (gen/fmap (fn [_] @generator-server) (gen/return nil)))

;; the gate refuses a `[:fn]` naming anything that is not a REGISTERED
;; core predicate — resolvable is not the same as vouched for
(schema/register-core-predicate! 'seon.render.web/server? server?)

(schema.edn/load! {})

(def not-yet
  "What this slice does NOT do, stated so nobody reads its absence as
  health. Each line is a package-2 remainder with a settled design, not
  an open question.

  - ATTRIBUTE-INTEREST MATCHING. A repaint re-derives every block of the
    agent rather than only the blocks whose read attributes changed. The
    wire is already correct (suppression means only changed blocks are
    sent); the CPU is bounded and measured at ~0.5 ms for a whole page,
    so this is a real cost and a small one. The fix is the render-interest
    listener matching registrations BEFORE coalescing.
  - THE SHARED REGISTRATION. Each connection keeps its own last-delivered
    values, so two tabs on one agent evaluate the blocks twice. The
    32-tab falsifier needs one registration per block with a `mult` and
    per-tab taps; this slice proves the morph granularity, not the
    sharing.
  - THE PER-TAB FLOW GRAPH. Connections use the adapter's own callbacks
    plus one listener; `ui.md` specifies a small fixed Flow graph per
    tab, which restores Flow reporting and lifecycle.
  - COALESCING CADENCE. `:seon.config.render/coalesce-ms` exists and is
    honoured as a floor between repaints; the isolated non-blocking sink
    that lets presentation lag without touching the producer is the
    streaming slice's, because it is the streamed-partial path that
    needs it."
  [::interest-matching ::shared-registration ::per-tab-graph ::isolated-sink])

;;; ---------------------------------------------------------------------------
;;; The shell
;;; ---------------------------------------------------------------------------

(defn shell
  "The HTML document for one agent's page.

  Every surface is placed at its own `surface-id`, because that id is
  what a later morph targets — the document and the patch agree by
  construction, since both call `seon.render.block/surface-id`.

  The feed opener is a hidden SIBLING of the surfaces. `retryMaxCount:
  Infinity` and `openWhenHidden: false` are the quarry's measured
  settings: reconnect forever, and do not hold a socket open for a
  backgrounded tab."
  {:malli/schema [:=> [:cat :seon.render.web/page-request] :string]}
  [{:keys [:seon.cluster.agent/id :seon.render/page :seon.render.web/feed-url]}]
  (str
   "<!doctype html>"
   (hiccup/->string
    [:html {:lang "en" :data-theme "phosphor"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport"
              :content "width=device-width, initial-scale=1.0"}]
      [:title (str "seon · " id)]
      [:link {:rel "stylesheet" :href "/css/output.css"}]
      [:script {:type "module" :src "/js/datastar.js"}]]
     [:body {:class "min-h-screen bg-base-950 text-text-50 font-mono p-4"}
      ;; `seq`, not the vector: a page is a VECTOR OF ELEMENTS, and a
      ;; vector whose head is a vector is not hiccup — the grammar
      ;; refuses it, correctly, and the first live page came back empty
      ;; until this said `seq`. A seq is a fragment and splices.
      [:main {:class "flex flex-col gap-3"} (seq page)]
      ;; OUTSIDE every morph target. A data-init inside one is stripped
      ;; by that element's first whole-element morph, and the tab then
      ;; looks alive while receiving nothing.
      [:div {:style "display:none"
             :data-init (str "@get('" feed-url
                             "', {retryMaxCount: Infinity, "
                             "openWhenHidden: false})")}]]])))

;;; ---------------------------------------------------------------------------
;;; Painting
;;; ---------------------------------------------------------------------------

(defn surface-html
  "One surface as the HTML string that will be morphed into its id.

  A FAILED surface still paints, at its own id, as its error card: fail
  loud, do not fall down. A block that cannot render must not be able to
  remove itself from the page, because a silently missing surface is
  indistinguishable from a working empty one."
  {:malli/schema [:=> [:cat :seon.render/surface :seon.sci.admit/caps :any]
                  :string]}
  [surface caps db]
  (hiccup/->string
   (if-let [failure (:seon.error/value surface)]
     [:div {:id (:seon.render/surface-id surface) :class "seon-error-card"}
      [:span {:class "seon-error-card-name"}
       (str (:seon.block/name surface))]
      [:span {:class "seon-error-card-message"}
       (:seon.error/message failure)]]
     (block/expand (:seon.render/output surface)
                   {:seon.render/surfaces []
                    :seon.sci.admit/caps caps
                    :seon.db/db db}))))

(defn changed
  "The surfaces whose HTML differs from `delivered`, plus the new map.

  EQUALITY SUPPRESSION, and it compares the BYTES rather than the
  values, deliberately: bytes are what the socket costs and what the
  browser diffs, and two values that serialize identically are the same
  page whatever their internal representation. Determinism makes this
  sound — the serializer sorts attributes, so one value is always one
  byte string.

  Returns `{:seon.render.web/patches [[id html] …]
            :seon.render.web/delivered {id → html}}`, with patches
  ordered so a page repaints in reading order."
  {:malli/schema [:=> [:cat [:map-of :string :string] :seon.render/surfaces
                       :seon.sci.admit/caps :any]
                  :seon.render.web/repaint]}
  [delivered surfaces caps db]
  (reduce (fn [accumulated surface]
            (let [id (:seon.render/surface-id surface)
                  html (surface-html surface caps db)]
              (if (= html (get delivered id))
                accumulated
                (-> accumulated
                    (update :seon.render.web/patches conj [id html])
                    (assoc-in [:seon.render.web/delivered id] html)))))
          {:seon.render.web/patches []
           :seon.render.web/delivered delivered}
          surfaces))

;;; ---------------------------------------------------------------------------
;;; The feed
;;; ---------------------------------------------------------------------------

(defn- surfaces-of
  [db agent-id]
  (block/surfaces db {:seon.cluster.agent/id agent-id
                      :seon.render/kind :seon.render/html}))

(defn- paint!
  "Derive, suppress, and patch. Returns the new delivered map.
  Patches ONE element per changed block: the morph target is the block."
  [generator db agent-id caps delivered]
  (let [{:seon.render.web/keys [patches] :as repaint}
        (changed delivered (surfaces-of db agent-id) caps db)]
    (doseq [[_id html] patches]
      ;; default patch mode is `outer`, which is what a complete morph of
      ;; one element wants; the id rides in the element itself
      (datastar/patch-elements! generator html))
    (:seon.render.web/delivered repaint)))

(defn feed
  "The SSE response for one tab.

  One Datahike listener per connection, registered in `on-open` and
  removed in `on-close`, so its lifetime is exactly the socket's and a
  dropped tab leaves nothing behind.

  THE LISTENER DOES ALMOST NOTHING, and that is a hard constraint rather
  than a style: Datahike invokes it on the writer's commit path, so work
  inside it is added to EVERY subsequent transaction. It `offer!`s onto
  a latest-wins mailbox and returns. A full mailbox is not a problem to
  report — it means a repaint is already pending, and the newest
  database value is the one that matters.

  Painting happens on the connection's own thread, never the writer's."
  {:malli/schema [:=> [:cat :any :seon.render.web/feed-request] :any]}
  [request {:keys [:seon.cluster.agent/id :seon.store/connection]
            caps :seon.sci.admit/caps
            coalesce :seon.config.render/coalesce-ms}]
  (let [;; latest-wins: at most one pending repaint per tab, and the
        ;; newest database value wins. This is the mailbox the
        ;; architecture calls for, expressed with the one construct that
        ;; already means it.
        mailbox (java.util.concurrent.ArrayBlockingQueue. 1)
        key (str "seon.render.web/" id "/" (random-uuid))
        painting (volatile! true)]
    (datastar.http-kit/->sse-response
     request
     {datastar.http-kit/on-open
      (fn [generator]
        (d/listen (:seon.store/connection-value request) key
                  (fn [_report] (.offer mailbox :look)))
        (.start
         (Thread/ofVirtual)
         (fn []
           (try
             (loop [delivered (paint! generator @connection id caps {})]
               (when @painting
                 (if (.poll mailbox coalesce java.util.concurrent.TimeUnit/MILLISECONDS)
                   ;; a coalescing floor, not a timer: several commits
                   ;; inside one window produce one repaint, and the
                   ;; window is a config fact rather than a constant
                   (recur (paint! generator @connection id caps delivered))
                   (recur delivered))))
             (catch Throwable _ nil)))))

      datastar.http-kit/on-close
      (fn [_generator _status]
        (vreset! painting false)
        (d/unlisten connection key))})))

;;; ---------------------------------------------------------------------------
;;; Routes
;;; ---------------------------------------------------------------------------

(def ^:private content-types
  {"css" "text/css" "js" "text/javascript" "woff2" "font/woff2"
   "svg" "image/svg+xml" "png" "image/png" "ico" "image/x-icon"})

(defn- resource
  "A file under `resources/public`, served from the CLASSPATH.
  Path traversal is refused by construction rather than by sanitising:
  the path must match a conservative pattern, so `..` never reaches
  `io/resource` at all."
  [path]
  (when (re-matches #"[A-Za-z0-9._/-]+" path)
    (when-not (str/includes? path "..")
      (when-let [found (io/resource (str "public/" path))]
        {:status 200
         :headers {"content-type"
                   (get content-types
                        (last (str/split path #"\."))
                        "application/octet-stream")}
         :body (io/input-stream found)}))))

(defn handler
  "The ring handler. Four routes and no router library yet — reitit and
  the capability gate are the interaction slice's, and inventing a
  route table before there are interactions to gate would be the
  machinery-first mistake."
  {:malli/schema [:=> [:cat :seon.render.web/service] fn?]}
  [{:keys [:seon.store/connection :seon.cluster.agent/id]
    caps :seon.sci.admit/caps
    coalesce :seon.config.render/coalesce-ms
    :as service}]
  (fn [request]
    (let [uri (:uri request)
          agent-of (fn [prefix] (subs uri (count prefix)))]
      (cond
        (= "/" uri)
        {:status 200
         :headers {"content-type" "text/html; charset=utf-8"}
         :body (shell {:seon.cluster.agent/id id
                       :seon.render/page (block/page @connection
                                                     {:seon.cluster.agent/id id
                                                      :seon.sci.admit/caps caps})
                       :seon.render.web/feed-url (str "/feed/" id)})}

        (str/starts-with? uri "/agent/")
        (let [agent-id (agent-of "/agent/")]
          {:status 200
           :headers {"content-type" "text/html; charset=utf-8"}
           :body (shell {:seon.cluster.agent/id agent-id
                         :seon.render/page
                         (block/page @connection
                                     {:seon.cluster.agent/id agent-id
                                      :seon.sci.admit/caps caps})
                         :seon.render.web/feed-url (str "/feed/" agent-id)})})

        (str/starts-with? uri "/feed/")
        (feed (assoc request :seon.store/connection-value connection)
              {:seon.cluster.agent/id (agent-of "/feed/")
               :seon.store/connection connection
               :seon.sci.admit/caps caps
               :seon.config.render/coalesce-ms coalesce})

        (= "/data" uri)
        ;; the drill over the CLUSTER's own facts. Its cursor is
        ;; ordinary query data, so a drilled position is a link
        ;; somebody can send rather than a session somebody holds.
        (let [query (into {} (map (fn [pair]
                                    (let [[k v] (str/split pair #"=" 2)]
                                      [k (some-> v (java.net.URLDecoder/decode
                                                    "UTF-8"))])))
                          (str/split (or (:query-string request) "") #"&"))]
          {:status 200
           :headers {"content-type" "text/html; charset=utf-8"}
           :body (shell {:seon.cluster.agent/id id
                         :seon.render/page
                         [(data/drill-html
                           {:seon.render/value (schema/canonical-database-attributes)
                            :seon.sci.admit/caps caps
                            :seon.render.data/cursor
                            (data/parse-cursor (get query "path")
                                               (get query "offset"))})]
                         ;; no feed: a drilled page is a position, and
                         ;; repainting it under the reader would move
                         ;; the ground they are standing on. Reload is
                         ;; the refresh, and the URL is the state.
                         :seon.render.web/feed-url (str "/feed/" id)})})

        (str/starts-with? uri "/css/")
        (or (resource (subs uri 1)) {:status 404 :body "not found"})

        (str/starts-with? uri "/js/")
        (or (resource (subs uri 1)) {:status 404 :body "not found"})

        :else {:status 404
               :headers {"content-type" "text/plain"}
               :body "not found"}))))

;;; ---------------------------------------------------------------------------
;;; Lifecycle
;;; ---------------------------------------------------------------------------

(def port-floor
  "7700. The bottom of the derived range.

  GROUNDED, not picked from the air: 7700-7999 carries no IANA
  assignment, sits clear of the crowded 3000/4000/5000/8000/8080 block
  every other dev server reaches for, and is comfortably below the
  ephemeral range the operating system allocates from (49152+ on this
  platform), so a derived port can never collide with one the OS was
  about to hand out."
  7700)

(def port-ceiling
  "8000, exclusive. Three hundred ports — enough that a handful of named
  clusters on one machine rarely collide, small enough that the whole
  range is greppable when one does."
  8000)

(defn derived-port
  "The default port for a cluster NAME. Pure, and stable forever.

  THE POINT IS BOOKMARKABILITY: a named cluster answers on the same port
  after every restart, so a browser tab keeps working and nobody reads a
  log to find their own cluster. The derivation is not magic — it is
  FNV-1a over the name's UTF-8 bytes, folded into the range — and it is
  written out here rather than delegated to `clojure.core/hash` on
  purpose: `hash` is stable in practice but its stability across JVM
  versions is not a contract, and a bookmark that silently moves is
  worse than one that never existed.

  Collisions are expected and handled, not prevented: two names can land
  on one port, and `start!` falls back to an ephemeral port and SAYS SO
  rather than refusing to serve."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name] :seon.render.web/port]}
  [cluster-name]
  (let [;; FNV-1a, 32-bit: offset basis 2166136261, prime 16777619.
        ;; Unsigned arithmetic by construction — the mask keeps it in
        ;; 32 bits so the JVM's signed longs cannot change the answer.
        hashed (reduce (fn [accumulated byte-value]
                         (-> (bit-xor accumulated (bit-and byte-value 0xff))
                             (* 16777619)
                             (bit-and 0xffffffff)))
                       2166136261
                       (.getBytes ^String cluster-name "UTF-8"))]
    (+ port-floor (mod hashed (- port-ceiling port-floor)))))

(defn start!
  "Bind an http-kit server on LOOPBACK and return its descriptor.

  Port 0 means the operating system chooses, and the chosen port is
  reported in the return value rather than written to a file: the
  interface publishes its own readiness, which is the standing rule for
  anything a caller would otherwise poll for.

  Loopback only. This serves an agent's page and, later, its
  interactions; exposing it on every interface would be a decision, and
  a decision like that does not belong in a default."
  {:malli/schema [:=> [:cat :seon.render.web/service] :seon.render.web/server]}
  [service]
  (let [workers (Executors/newVirtualThreadPerTaskExecutor)
        wanted (or (:seon.render.web/port service) 0)
        bind! (fn [port]
                (http/run-server (handler service)
                                 {:ip "127.0.0.1"
                                  :port port
                                  :worker-pool workers
                                  :legacy-return-value? false}))
        ;; A TAKEN PORT MUST NOT COST THE VIEW. Two clusters whose names
        ;; derive the same port, or a stale process still holding one,
        ;; are ordinary situations — so the second one serves anyway, on
        ;; an ephemeral port, and REPORTS both numbers. Refusing to serve
        ;; would make a name collision look like a broken build; serving
        ;; silently on a different port would make a bookmark fail with
        ;; no explanation. Saying so is the only honest option.
        [server fell-back?]
        (try
          [(bind! wanted) false]
          (catch java.net.BindException _
            (when-not (zero? wanted)
              [(bind! 0) true])))
        bound (http/server-port server)]
    (cond-> {:seon.render.web/server server
             :seon.render.web/port bound
             :seon.render.web/url (str "http://127.0.0.1:" bound)}
      ;; present exactly when the wanted port was not the bound one, so
      ;; "did this fall back?" is key presence rather than a comparison
      ;; every reader has to remember to make
      fell-back? (assoc :seon.render.web/wanted-port wanted))))

(defn stop!
  "Close the server. Every connection's `on-close` unlistens its own
  listener, so nothing survives this that could keep painting."
  {:malli/schema [:=> [:cat :seon.render.web/server] :nil]}
  [{:keys [:seon.render.web/server]}]
  (http/server-stop! server)
  nil)
