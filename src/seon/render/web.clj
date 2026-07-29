(ns seon.render.web
  "The page on a socket — http-kit, one Datastar SSE per tab, one morph
  per block, ONE derivation per cluster.

  F2 §1: the render pipeline is one derivation → equality suppression →
  `mult` → per-tab sliding-1 taps. The RENDER PROC (a proc of the
  cluster's own graph, pinned `:io`, built through
  `seon.flow/var-process`) runs one pass per wake over ONE database
  value, derives every WATCHED agent's page, suppresses at the proc
  against the last value PRODUCED, and puts one COMPLETE
  `{agent-id → {surface-id → html}}` snapshot on the mult. Complete
  snapshots, never incremental patches — a sliding-1 tap holds exactly
  one pending value, so a patch displaced by a patch would be a
  PERMANENTLY lost morph; the newest-only buffer row demands a complete
  value (F2 R7).

  ONE MORPH PER BLOCK is preserved AT THE SOCKET: each tab's writer
  diffs the snapshot against what IT last delivered and sends only the
  changed blocks — the same 287-bytes-not-82,893 wire economy this
  namespace proved, now computed per tab from one shared derivation
  instead of derived per tab. DELETED with F2: the per-connection
  `d/listen` registration, the hand-rolled latest-wins mailbox, and the
  per-tab full re-derivation.

  THE WAKE SOURCE is `wake/route!` — the cluster's ONE listener — which
  offers one payload-free render wake per transaction report; a freshly
  opened tab offers its own. The COALESCE FLOOR
  (`:seon.config.render/coalesce-ms`, a config fact) is honored at the
  proc: a burst of commits costs one derivation for the whole cluster,
  and the per-tab writer just writes. It remains a coalescing floor
  over an observed event (the commit), never a poll.

  THE FEED OPENER IS A SIBLING OF THE MORPH TARGETS, never a child. A
  `data-init` inside a morphed element is stripped by the first
  whole-element morph and the connection never reopens — a lesson the
  quarry paid for and wrote down (`src-old/seon/web/datastar.cljs:611-620`),
  and the reason the shell puts the opener in its own hidden div beside
  the surfaces rather than on the container.

  Crash walk. Everything here is channel contents or process-local
  disposable state — the produced-memory, the delivered-memory, the
  watched registration, pending snapshots on taps — all free to lose by
  the transport law: every tab reconnects and repaints from current
  facts (reconnect = repaint), the registration re-fills from
  `on-open`, and the next commit re-offers the wake."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [org.httpkit.server :as http]
            [seon.cluster.message :as message]
            [seon.cluster.run :as run]
            [seon.render.block :as block]
            [seon.render.data :as data]
            [seon.render.hiccup :as hiccup]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [starfederation.datastar.clojure.adapter.http-kit :as datastar.http-kit]
            [starfederation.datastar.clojure.api :as datastar])
  (:import [java.net URI URLDecoder URLEncoder]
           [java.util Date]
           [java.util.concurrent Executors]))

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

(defn mult?
  "True for a core.async mult — the fan-out the page snapshots ride."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (satisfies? clojure.core.async/Mult value))

(defonce ^:private generator-mult
  (delay (async/mult (async/chan (async/sliding-buffer 1)))))

(def mult-generator
  "An honest generator: a real mult over a real channel, created once."
  (gen/fmap (fn [_] @generator-mult) (gen/return nil)))

(schema/register-core-predicate! 'seon.render.web/mult? mult?)

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The shell
;;; ---------------------------------------------------------------------------

(defn- path-segment
  [value]
  (-> (URLEncoder/encode (str value) "UTF-8")
      (str/replace "+" "%20")))

(defn message-bar-html
  "`:seon.render/html` — the constant human-to-agent message bar.

  Every transient value lives in a Datastar signal: typed text,
  request progress, and refusal prose. The render depends only on the
  agent id, so unrelated facts serialize to identical bytes and the
  per-tab equality check never morphs this surface or disturbs its
  caret."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [agent-id (:seon.cluster.agent/id unit)]
    [:form {:id (block/surface-id :message-bar)
            :class "seon-bar"
            :data-signals "{text:'',refusal:''}"
            (keyword "data-on:submit")
            (str "$refusal=''; @post('/agent/" (path-segment agent-id)
                 "/message', {contentType:'form'}); $text=''")
            (keyword "data-on:datastar-fetch")
            (str "evt.detail.el===el && ("
                 "evt.detail.type==='started' ? $refusal='' : "
                 "evt.detail.type==='error' ? $refusal="
                 "'Message not sent (HTTP '+evt.detail.argsRaw.status+'). "
                 "Correct the message and retry.' : null)")}
     [:input {:class "seon-bar-field"
              :type "text"
              :name "content"
              :data-bind "text"
              :required true
              :autocomplete "off"
              :autofocus true
              :placeholder (str "message agent " agent-id " …")}]
     [:button {:class "seon-bar-send" :type "submit"} "send"]
     [:span {:class "seon-bar-refusal"
             :role "status"
             :aria-live "polite"
             :data-show "$refusal"
             :data-text "$refusal"}]]))

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
     ;; SEMANTIC CLASSES, not utility strings, for the same reason the
     ;; render surfaces already use them (the note above `.seon-problems`
     ;; in `input.css`): the document frame is one thing, it is styled in
     ;; one place, and restyling the page does not mean editing Clojure.
     [:body {:class "seon-body"}
      ;; `seq`, not the vector: a page is a VECTOR OF ELEMENTS, and a
      ;; vector whose head is a vector is not hiccup — the grammar
      ;; refuses it, correctly, and the first live page came back empty
      ;; until this said `seq`. A seq is a fragment and splices.
      [:main {:class "seon-main"} (seq page)]
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
  loud, do not fall down. An omitted surface paints an empty wrapper at
  its own id, so a later non-nil render still has a morph target."
  {:malli/schema [:=> [:cat :seon.render/surface :seon.sci.admit/caps :any]
                  :string]}
  [surface caps db]
  (hiccup/->string
   (if-let [failure (:seon.error/value surface)]
     [:div {:id (:seon.render/surface-id surface)
            :class "seon-error-card"
            :data-block (subs (str (:seon.render.block/name surface)) 1)
            :data-error-kind (str (:seon.error/kind failure))}
      [:span {:class "seon-error-card-name"}
       (str (:seon.render.block/name surface))]
      [:span {:class "seon-error-card-message"}
       (:seon.error/message failure)]]
     (if-some [output (get surface :seon.render/output)]
       (block/expand output
                     {:seon.render/surfaces []
                      :seon.sci.admit/caps caps
                      :seon.db/db db})
       [:div {:id (:seon.render/surface-id surface)} ""]))))

(defn page-of
  "One agent's page as `{surface-id → html}`. THE one serialization.

  Derives the agent's html surfaces at `db` and serializes each through
  `surface-html`. The render proc and the initial paint both call THIS,
  so the bytes the proc suppresses against and the bytes a tab diffs
  against are the same bytes by construction — colocation is what makes
  the byte contract structural (F2 R2).

  THE LIVE SET RIDES IN, it is not defaulted here. `:seon.cluster.run/
  live-processes` is the one input no database value can answer, and
  the problems block refuses to guess it rather than inventing wedges
  (`#{}`) or hiding them (\"assume alive\"). The web layer can answer
  it because the web layer IS the running process: the service carries
  this process's run-holder identity, and on one branch that is the
  whole live set."
  {:malli/schema [:=> [:cat :seon.render.web/paint-request]
                  [:map-of :seon.render/surface-id :string]]}
  [{:keys [:seon.db/db :seon.cluster.agent/id] caps :seon.sci.admit/caps
    :as request}]
  (into {}
        (map (fn [surface]
               [(:seon.render/surface-id surface)
                (surface-html surface caps db)]))
        (block/surfaces db (merge {:seon.cluster.agent/id id
                                   :seon.render/kind :seon.render/html
                                   :seon.sci.admit/caps caps}
                                  (select-keys
                                   request
                                   [:seon.cluster.run/live-processes
                                    :seon.ai/partial])))))

(defn changed
  "The patches whose bytes differ between `delivered` and `page`.

  EQUALITY SUPPRESSION, and it compares the BYTES rather than the
  values, deliberately: bytes are what the socket costs and what the
  browser diffs, and two values that serialize identically are the same
  page whatever their internal representation. Determinism makes this
  sound — the serializer sorts attributes, so one value is always one
  byte string. The comparison is UNCHANGED in kind since the first web
  slice, relocated in owner (F2 §1.5): the proc suppresses against the
  last value PRODUCED, each tab diffs against the last value IT
  delivered.

  Returns `{:seon.render.web/patches [[id html] …]
            :seon.render.web/delivered {id → html}}`, patches sorted by
  id so one input always yields one output."
  {:malli/schema [:=> [:cat [:map-of :string :string]
                       [:map-of :string :string]]
                  :seon.render.web/repaint]}
  [delivered page]
  {:seon.render.web/patches
   (into []
         (filter (fn [[id html]] (not= html (get delivered id))))
         (sort-by key page))
   :seon.render.web/delivered page})

;;; ---------------------------------------------------------------------------
;;; The render proc — the cluster graph's second proc (F2 §1)
;;; ---------------------------------------------------------------------------

(defn- coalesce-floor
  "The coalescing floor, read from the config facts at `db` — a live
  dial change applies at the very next pass. 0 when absent."
  [db]
  (or (d/q '[:find ?value .
             :where [_ :seon.config.render/coalesce-ms ?value]]
           db)
      0))

(defn- unsettled-stream?
  "True when a stream entry's run has no settled terminal fact at `db`.

  The provider reply settles as a frozen plan (`::run/plan-digest`) or
  a durable `::run/error`; `::run/closed-at` covers a run terminated by
  another path. A missing run is not live. This presence gate makes a
  delayed partial incapable of repainting over its settled facts."
  [db stream]
  (when-let [run-id (:seon.cluster.run/id stream)]
    (let [row (d/pull db [:db/id ::run/plan-digest ::run/error ::run/closed-at]
                      [::run/id run-id])]
      (and (some? row)
           (not-any? #(contains? row %)
                     [::run/plan-digest ::run/error ::run/closed-at])))))

(defn- render-pass
  "ONE pass over ONE database value: derive every WATCHED agent's page,
  suppress against the last value PRODUCED, and return
  `[state' pages-or-nil]` — `pages` is the COMPLETE
  `{agent-id → {surface-id → html}}` snapshot exactly when anything
  changed."
  [{registration :seon.render.web/registration :as state}]
  (let [handle (:seon.cluster.loop/cluster state)
        connection (:seon.store/branch-connection handle)
        caps (:seon.sci.admit/caps handle)
        db @connection
        watched (into (sorted-set)
                      (keep (fn [[agent-id tabs]]
                              (when (pos? (long tabs)) agent-id)))
                      @registration)
        ;; The run id makes each partial self-describing. Keep only
        ;; entries whose run is still unsettled at THIS immutable
        ;; database value; terminal facts supersede and remove them.
        streams (into {}
                      (filter (fn [[_agent-id stream]]
                                (unsettled-stream? db stream)))
                      (::streams state))
        pages (into {}
                    (map (fn [agent-id]
                           [agent-id
                            (page-of
                             (cond-> {:seon.db/db db
                                      :seon.cluster.agent/id agent-id
                                      :seon.sci.admit/caps caps
                                      :seon.cluster.run/live-processes
                                      #{(:seon.cluster.run/process handle)}}
                               (get-in streams [agent-id :seon.ai/partial])
                               (assoc :seon.ai/partial
                                      (get-in streams
                                              [agent-id
                                               :seon.ai/partial]))))]))
                    watched)
        state (-> state
                  (update ::passes inc)
                  (assoc ::watched (count watched)
                         ::streams streams))]
    (if (= pages (::produced state))
      [state nil]
      [(assoc state ::produced pages) pages])))

(defn render-step
  "The render proc's transform, in Flow's four arities (F2 §1.1).

  Two in-ports, both `(sliding-buffer 1)`: `::interest` — the render
  wake channel, a payload-free \"look\"; `::stream` — the cluster's one
  stream conn carrying `{agent-id + run-id + :seon.ai/partial snapshot}`
  entries. There is NO clear entry: the frozen-plan/error/close fact is
  the stream terminal. One out-port, `::pages`, feeding the mult;
  sliding-1 everywhere means the proc never parks.

  THE COALESCE FLOOR is honored HERE: a wake arriving inside the floor
  waits out the remainder before the next derivation (this proc is
  `:io`; the wait coalesces further wakes on the sliding-1 in-ports),
  so a burst of commits costs one derivation for the whole cluster and
  the per-tab writer just writes. The one surviving render clock, and
  it remains a coalescing floor over an observed event — the commit,
  delivered by the routing listener — never a poll.

  All state is disposable by the transport law: the produced-memory is
  rebuilt by one re-render after any restart, and a partial is admitted
  only while its run lacks a terminal fact. An interest pass is a
  repaint from facts, so it drops every cached partial before deriving;
  reconnect can never restore one."
  {:malli/schema [:function
                  [:=> [:cat] [:map]]
                  [:=> [:cat :map] :map]
                  [:=> [:cat :map :keyword] :map]
                  [:=> [:cat :map :keyword :any]
                   ;; `[state out]`, where `out` is nil when suppression
                   ;; found nothing to say and otherwise ONE complete
                   ;; page snapshot on `::pages`
                   [:tuple :map
                    [:or :nil
                     [:map-of :keyword
                      [:vector [:map-of :seon.cluster.agent/id
                                [:map-of :seon.render/surface-id
                                 :string]]]]]]]]}
  ([]
   {:ins {}
    :outs {}
    :workload :io
    :ping-map-fn (fn [state]
                   {::passes (::passes state 0)
                    ::watched-agents (::watched state 0)
                    ::tap-count (transduce (map long) + 0
                                           (vals @(:seon.render.web/registration
                                                   state)))
                    ::streaming-agents (count (::streams state))})})
  ([args]
   ;; THE PORTS ARE A DEPENDENCY, SO SAY SO. A nil in-port does not
   ;; fail: Flow leaves the proc :running with an unreadable port, it
   ;; takes nothing forever, and — measured — its stop transition never
   ;; runs either, so `disarm-agents!` waits on a completion that can
   ;; never arrive. A silent wedge that turns into a hang at shutdown
   ;; is exactly the class the readiness rule exists to kill, so the
   ;; proc refuses to be built without the channels it reads.
   (let [ports {::interest (:seon.render.web/render-channel args)
                ::stream (:seon.cluster.loop/stream-channel
                          (:seon.cluster.loop/cluster args))
                ::pages (:seon.render.web/pages-channel args)}]
     (when-let [missing (seq (sort (keep (fn [[port channel]]
                                           (when-not channel port))
                                         ports)))]
       (throw (ex-info "the render proc is missing an in/out port"
                       {:seon.error/kind ::missing-port
                        ::missing (vec missing)}))))
   (assoc args
          ::flow/in-ports
          {::interest (:seon.render.web/render-channel args)
           ::stream (:seon.cluster.loop/stream-channel
                     (:seon.cluster.loop/cluster args))}
          ::flow/out-ports
          {::pages (:seon.render.web/pages-channel args)}
          ::produced {}
          ::streams {}
          ::passes 0
          ::watched 0
          ::last-pass-nanos 0))
  ([state transition]
   (when (= ::flow/stop transition)
     ;; its OWN completion, not the cluster handle's: disarm must join
     ;; BOTH cluster-graph procs' active transforms before the branch
     ;; connection is released
     (async/put! (:seon.render.web/completion state) ::stopped))
   state)
  ([state input message]
   (let [state (case input
                 ;; A repaint is facts only. Partials were never facts,
                 ;; so a commit wake or reconnect wake cannot restore
                 ;; process-local stream memory.
                 ::interest (assoc state ::streams {})
                 ::stream
                 (assoc-in state
                           [::streams (:seon.cluster.agent/id message)]
                           message)
                 state)
         connection (:seon.store/branch-connection
                     (:seon.cluster.loop/cluster state))
         floor (coalesce-floor @connection)
         elapsed-ms (quot (- (System/nanoTime)
                             (long (::last-pass-nanos state)))
                          1000000)
         remainder (- floor elapsed-ms)]
     (when (pos? remainder)
       ;; the floor: wait out the remainder BEFORE deriving, so the
       ;; sliding-1 in-ports coalesce the burst and one derivation
       ;; serves it whole
       (Thread/sleep (long remainder)))
     (let [[state pages] (render-pass state)]
       [(assoc state ::last-pass-nanos (System/nanoTime))
        (when pages {::pages [pages]})]))))

;;; ---------------------------------------------------------------------------
;;; The feed — per tab: a tap and a virtual thread (F2 §1.3)
;;; ---------------------------------------------------------------------------

(defn- register-tab!
  "Count one open tab on `agent-id` in the watched registration."
  [registration agent-id]
  (swap! registration update agent-id (fnil inc 0)))

(defn- deregister-tab!
  "Drop one open tab; the last tab out removes the agent entirely, so
  the render pass stops deriving a page nobody is watching."
  [registration agent-id]
  (swap! registration
         (fn [watched]
           (let [remaining (dec (long (get watched agent-id 1)))]
             (if (pos? remaining)
               (assoc watched agent-id remaining)
               (dissoc watched agent-id))))))

(defn feed
  "The SSE response for one tab: a tap and a virtual thread — never a
  graph, never a listener.

  `on-open` registers interest for the agent, taps the mult with a
  `(sliding-buffer 1)`, paints once from the CURRENT database value
  (the initial full paint — every block, at its own id), offers one
  render wake so a freshly watched agent is derived without waiting for
  the next commit, then loops on the tap: take the newest complete
  snapshot, select this agent's entry, diff against this connection's
  own last-delivered map, and patch only the changed blocks.

  Backpressure walk: this tab's socket backpressure is absorbed by this
  loop alone — the tap's sliding-1 keeps the newest page pending and
  the proc never parks (inventory §3.2, measured mechanics §5).

  `on-close` untaps and deregisters. The connection owns exactly one
  virtual thread and one map; nothing outlives the socket."
  {:malli/schema [:=> [:cat :any :seon.render.web/feed-request] :any]}
  [request {:keys [:seon.cluster.agent/id :seon.store/connection]
            caps :seon.sci.admit/caps
            process :seon.cluster.run/process
            pages-mult :seon.render.web/pages-mult
            registration :seon.render.web/registration
            render-channel :seon.render.web/render-channel}]
  (let [tap (async/chan (async/sliding-buffer 1))
        painting (volatile! true)]
    (datastar.http-kit/->sse-response
     request
     {datastar.http-kit/on-open
      (fn [generator]
        ;; interest FIRST, so the pass a wake triggers derives this
        ;; agent; the tap BEFORE the paint, so a snapshot racing the
        ;; paint is diffed rather than missed
        (register-tab! registration id)
        (async/tap pages-mult tap)
        (.start
         (Thread/ofVirtual)
         (fn []
           (try
             (let [initial (page-of {:seon.db/db @connection
                                     :seon.cluster.agent/id id
                                     :seon.sci.admit/caps caps
                                     :seon.cluster.run/live-processes
                                     #{process}})]
               ;; the initial full paint: every block, at its own id
               (doseq [[_id html] (sort-by key initial)]
                 (datastar/patch-elements! generator html))
               (async/offer! render-channel ::wake)
               (loop [delivered initial]
                 (when @painting
                   (when-let [pages (async/<!! tap)]
                     (if-let [page (get pages id)]
                       (let [{:seon.render.web/keys [patches]}
                             (changed delivered page)]
                         (doseq [[_id html] patches]
                           ;; default patch mode is `outer` — a complete
                           ;; morph of one element; the id rides in the
                           ;; element itself
                           (datastar/patch-elements! generator html))
                         (recur page))
                       ;; a snapshot that predates this tab's interest
                       ;; carries no entry for its agent; the wake this
                       ;; tab offered brings the next one
                       (recur delivered))))))
             (catch Throwable _ nil)))))

      datastar.http-kit/on-close
      (fn [_generator _status]
        (vreset! painting false)
        (async/untap pages-mult tap)
        (async/close! tap)
        (deregister-tab! registration id))})))

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

(def ^:private loopback-hosts
  #{"127.0.0.1" "localhost" "::1"})

(defn- same-origin?
  "True when a browser POST is same-origin, or supplies no Origin."
  [request]
  (let [headers (:headers request)
        origin (get headers "origin")]
    (boolean
     (or (str/blank? origin)
         (try
           (let [uri (URI. origin)
                 origin-host (.getHost uri)
                 origin-authority (.getAuthority uri)
                 request-host (get headers "host")
                 request-scheme (some-> (:scheme request) name)]
             (and (= (.getScheme uri) request-scheme)
                  (or (and request-host (= origin-authority request-host))
                 (and (nil? request-host)
                      (contains? loopback-hosts origin-host)))))
           (catch Throwable _
             false))))))

(defn- decode-form
  "One URL-encoded form body as string keys and values."
  [request]
  (let [body (:body request)
        encoded (cond
                  (nil? body) ""
                  (string? body) body
                  :else (slurp body))]
    (into {}
          (keep (fn [pair]
                  (when-not (str/blank? pair)
                    (let [[key value] (str/split pair #"=" 2)]
                      [(URLDecoder/decode key "UTF-8")
                       (URLDecoder/decode (or value "") "UTF-8")]))))
          (str/split encoded #"&"))))

(defn- agent-exists?
  [db agent-id]
  (some?
   (d/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.cluster.agent/id ?agent-id]]
        db agent-id)))

(defn- inbound-tx-meta
  [db process user-id]
  (cond-> {:seon.db/process [:seon.db.process/id process]}
    (agent-exists? db user-id)
    (assoc :seon.db/user [:seon.cluster.agent/id user-id])))

(defn inbound
  "Commit one admitted inbound message and return its Ring response.

  The response never paints. A successful POST is 204 with no body;
  the existing commit → route → render path paints the message and
  wakes the recipient. Refusals are 422 text values and commit nothing."
  {:malli/schema [:=> [:cat :seon.render.web/service
                       :seon.render.web/inbound]
                  :any]}
  [{:keys [:seon.store/connection :seon.cluster.agent/id]
    caps :seon.sci.admit/caps
    process :seon.cluster.run/process}
   inbound]
  (let [request
        {:seon.cluster.agent/id (:seon.cluster.agent/id inbound)
         :seon.cluster.message/inbound-content
         (or (:seon.cluster.message/inbound-content inbound) "")
         :seon.cluster.message/at (Date.)
         :seon.config.eval.result/max-string
         (:seon.config.eval.result/max-string caps)}
        decision (message/inbound-tx @connection request)]
    (if (vector? decision)
      (do
        (d/transact
         connection
         {:tx-data [[:db.fn/call #'message/inbound-tx request]]
          :tx-meta (inbound-tx-meta @connection process id)})
        {:status 204 :headers {} :body nil})
      {:status 422
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body (:seon.error/message decision)})))

(defn- exact-agent-id
  [pattern uri]
  (some-> (re-matches pattern uri) second
          (URLDecoder/decode "UTF-8")))

(defn- query-entity
  [encoded]
  (try
    (let [value (some-> encoded edn/read-string)]
      (when (and (vector? value)
                 (= 2 (count value))
                 (qualified-keyword? (first value)))
        value))
    (catch Throwable _ nil)))

(defn handler
  "The one Ring dispatcher, including the exact inbound POST route.

  Reitit remains deferred until nested route data and capability
  middleware make a tree pay for itself. Method and whole-path
  discrimination here make the one state-changing route exact without
  introducing a second dispatcher."
  {:malli/schema [:=> [:cat :seon.render.web/service] fn?]}
  [{:keys [:seon.store/connection :seon.cluster.agent/id]
    caps :seon.sci.admit/caps
    process :seon.cluster.run/process
    :as service}]
  (fn [request]
    (let [uri (:uri request)
          method (:request-method request)
          inbound-agent (when (= :post method)
                          (exact-agent-id #"/agent/([^/]+)/message" uri))
          page-agent (when (= :get method)
                       (exact-agent-id #"/agent/([^/]+)" uri))
          feed-agent (when (= :get method)
                       (exact-agent-id #"/feed/([^/]+)" uri))
          ;; ONE page request builder for both html routes, so `/` and
          ;; `/agent/{id}` cannot drift into two answers about who is
          ;; alive. Root IS an agent: the only difference between these
          ;; routes is which id they name.
          page-request (fn [agent-id]
                         {:seon.cluster.agent/id agent-id
                          :seon.sci.admit/caps caps
                          :seon.cluster.run/live-processes #{process}})]
      (cond
        inbound-agent
        (if (same-origin? request)
          (let [params (decode-form request)]
            (inbound service
                     (cond-> {:seon.cluster.agent/id inbound-agent}
                       (contains? params "content")
                       (assoc :seon.cluster.message/inbound-content
                              (get params "content")))))
          {:status 403
           :headers {"content-type" "text/plain; charset=utf-8"}
           :body "cross-origin POST refused"})

        (and (= :get method) (= "/" uri))
        {:status 200
         :headers {"content-type" "text/html; charset=utf-8"}
         :body (shell {:seon.cluster.agent/id id
                       :seon.render/page (block/page @connection
                                                     (page-request id))
                       :seon.render.web/feed-url (str "/feed/" id)})}

        page-agent
        (let [agent-id page-agent]
          {:status 200
           :headers {"content-type" "text/html; charset=utf-8"}
           :body (shell {:seon.cluster.agent/id agent-id
                         :seon.render/page
                         (block/page @connection (page-request agent-id))
                         :seon.render.web/feed-url (str "/feed/" agent-id)})})

        feed-agent
        (feed request
              (merge {:seon.cluster.agent/id feed-agent}
                     (select-keys service
                                  [:seon.store/connection
                                   :seon.sci.admit/caps
                                   :seon.cluster.run/process
                                   :seon.render.web/pages-mult
                                   :seon.render.web/registration
                                   :seon.render.web/render-channel])))

        (and (= :get method) (= "/data" uri))
        ;; The entity lookup ref selects the drill root; the cursor
        ;; navigates within that ordinary value. With no entity the
        ;; canonical database attributes remain the front page.
        (let [query (into {} (map (fn [pair]
                                    (let [[k v] (str/split pair #"=" 2)]
                                      [k (some-> v (java.net.URLDecoder/decode
                                                    "UTF-8"))])))
                          (str/split (or (:query-string request) "") #"&"))
              entity? (contains? query "entity")
              entity (query-entity (get query "entity"))
              value (if entity?
                      (when entity
                        (d/pull @connection '[*] entity))
                      (schema/canonical-database-attributes))]
          {:status 200
           :headers {"content-type" "text/html; charset=utf-8"}
           :body (shell {:seon.cluster.agent/id id
                         :seon.render/page
                         [(data/drill-html
                           {:seon.render/value value
                            :seon.sci.admit/caps caps
                            :seon.render.data/cursor
                            (data/parse-cursor (get query "path")
                                               (get query "offset"))})]
                         ;; no feed: a drilled page is a position, and
                         ;; repainting it under the reader would move
                         ;; the ground they are standing on. Reload is
                         ;; the refresh, and the URL is the state.
                         :seon.render.web/feed-url (str "/feed/" id)})})

        (and (= :get method) (str/starts-with? uri "/css/"))
        (or (resource (subs uri 1)) {:status 404 :body "not found"})

        (and (= :get method) (str/starts-with? uri "/js/"))
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
  (let [connection (:seon.store/connection service)
        process (:seon.cluster.run/process service)
        ;; Transaction provenance resolves to a durable process entity.
        ;; This is convergent: a running service creates its row once,
        ;; and every later start observes it.
        _ (when-not
           (d/q '[:find ?process .
                  :in $ ?id
                  :where [?process :seon.db.process/id ?id]]
                @connection process)
            (d/transact connection [{:seon.db.process/id process}]))
        workers (Executors/newVirtualThreadPerTaskExecutor)
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
  "Close the server. Every connection's `on-close` untaps its own tap
  and drops its registration, so nothing survives this that could keep
  painting."
  {:malli/schema [:=> [:cat :seon.render.web/server] :nil]}
  [{:keys [:seon.render.web/server]}]
  (http/server-stop! server)
  nil)
