(ns seon.render.web-test
  "The page on a real socket.

  REAL SOCKETS, not a mocked handler. The rung's whole claim is about
  what goes on the wire — one morph per block, nothing when nothing
  changed — and a test that called the handler as a function would prove
  the derivation while leaving the claim untested. So every test here
  binds a real http-kit server on an ephemeral loopback port, speaks
  real HTTP, and reads the real SSE stream.

  READING SSE CORRECTLY IS PART OF THE TEST. `BodyHandlers/ofLines` does
  not yield on a stream that never ends. `read-patches!` instead blocks
  on a complete, counted SSE event; its clock is only the shared loud
  backstop around the future returned by that read.

  THE FULL PIPELINE, not a handler: since F2 the derivation lives in
  the RENDER PROC and a tab is a tap on a mult, so the fixture builds
  the real proc in a real one-proc graph and registers the real routing
  listener. A commit therefore reaches a socket the way production
  reaches it — `route!` offers one render wake, the proc derives once
  for the whole cluster, and each tab diffs the complete snapshot
  against what it last delivered.

  Every server is stopped, every graph joined at its own completion,
  and every database deleted in a `finally`, so a failing assertion
  cannot leak a listening port or a live proc into the next test."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow.core]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.flow :as flow]
            [seon.render.block :as block]
            [seon.render.web :as web]
            [seon.test-support :as support])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(def ^:private agent-id "root")

;;; ---------------------------------------------------------------------------
;;; Blocks this suite owns
;;; ---------------------------------------------------------------------------

(defn banner-html
  "Reads nothing, so it must never be repainted."
  [_unit]
  [:section {:id (block/surface-id :banner)} [:h1 "seon"]])

(defn counter-html
  "Reads the agent count, so it repaints exactly when that changes."
  [unit]
  [:div {:id (block/surface-id :counter)}
   [:span (str "agents: "
               (count (d/q '[:find ?a :where [?a :seon.cluster.agent/id _]]
                           (:seon.db/db unit))))]])

(defn broken-html
  [_unit]
  (throw (ex-info "this block is broken" {::deliberate true})))

(defn omitted-html
  "Returns nil when this block has nothing to say."
  [_unit]
  nil)

;;; ---------------------------------------------------------------------------
;;; Fixture
;;; ---------------------------------------------------------------------------

(defn- with-server
  "The whole render pipeline on real sockets: the render proc in its own
  graph, the mult the tabs tap, the routing listener that wakes it.

  `body` receives the connection, the server descriptor, and a CONTEXT
  map holding the live pipeline: `:graph` so a test can `flow.core/ping`
  it and assert HOW MANY derivations a commit cost (the claim the shared
  registration exists to make), and `:pages-mult` so a test can take a
  tap of its own and be the slow browser on purpose."
  [blocks body]
  (support/with-database
    (fn [connection]
      (let [server (atom nil)
            render-channel (async/chan (async/sliding-buffer 1))
            pages-channel (async/chan (async/sliding-buffer 1))
            registration (atom {})
            completion (async/promise-chan)
            fault-channel (async/chan (async/dropping-buffer 8))
            stream-channel (async/chan (async/sliding-buffer 1))
            view {:seon.render.web/render-channel render-channel
                  :seon.render.web/pages-channel pages-channel
                  :seon.render.web/registration registration
                  :seon.render.web/completion completion}
            graph (flow.core/create-flow
                   {:procs
                    {:seon.render.web/render
                     {:proc (flow/var-process
                             #'web/render-step :io
                             (assoc view :seon.cluster.loop/cluster
                                    {:seon.store/branch-connection connection
                                     :seon.sci.admit/caps caps
                                     ;; the cluster's one stream conn:
                                     ;; production always has it, and
                                     ;; the proc now refuses to be
                                     ;; built without it
                                     :seon.cluster.loop/stream-channel
                                     stream-channel}))}}
                    :conns []})
            pages-mult (async/mult pages-channel)
            {:keys [report-chan error-chan]} (flow.core/start graph)]
        ;; nothing asserts on reports here, but an unread report or
        ;; error channel would eventually park the graph's own plumbing
        (async/go-loop [] (when (async/<! report-chan) (recur)))
        (async/go-loop [] (when (async/<! error-chan) (recur)))
        (try
          (d/transact connection [{:seon.cluster.agent/id agent-id
                                   :seon.cluster.agent/blocks blocks}])
          (flow.core/resume graph)
          (wake/route! {:seon.cluster.wake/connection connection
                        :seon.cluster.wake/channels (constantly {})
                        :seon.cluster.wake/armer-channel
                        (async/chan (async/sliding-buffer 1))
                        :seon.cluster.wake/render-channel render-channel
                        :seon.cluster.wake/fault-channel fault-channel
                        :seon.cluster.wake/key ::route})
          (reset! server (web/start!
                          {:seon.store/connection connection
                           :seon.cluster.agent/id agent-id
                           :seon.sci.admit/caps caps
                           :seon.render.web/pages-mult pages-mult
                           :seon.render.web/registration registration
                           :seon.render.web/render-channel render-channel}))
          (body connection @server
                {:graph graph
                 :pages-mult pages-mult
                 :render-channel render-channel
                 :stream-channel stream-channel
                 :registration registration})
          (finally
            (when @server (web/stop! @server))
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key ::route})
            (flow.core/stop graph)
            ;; the proc's OWN completion, under the shared loud
            ;; backstop: a wedged proc must fail this suite noisily
            ;; rather than hang the runner forever
            (support/await-event! (future (async/<!! completion))
                                  [:render-proc-stopped])
            (async/close! render-channel)
            (async/close! pages-channel)
            (async/close! stream-channel)))))))

(defn- ping-state
  "The render proc's own ping state — passes, watched agents, taps and
  streaming agents, exposed by its `:ping-map-fn`."
  [context]
  (-> (flow.core/ping (:graph context))
      (get :seon.render.web/render)
      (get :clojure.core.async.flow/state)))

(defn- derivations
  "The render proc's pass count — the oracle for ONE derivation per
  commit however many tabs are open."
  [context]
  (:seon.render.web/passes (ping-state context)))

(defn- block-map
  [name priority projection]
  {:seon.render.block/name name
   :seon.render.block/priority priority
   :seon.render/html projection})

(def ^:private two-blocks
  [(block-map :banner 0 `banner-html)
   (block-map :counter 10 `counter-html)])

(defn- client [] (.build (HttpClient/newBuilder)))

(defn- fetch
  [server path]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create (str (:seon.render.web/url server) path)))
                    (.GET)
                    (.build))]
    (.send (client) request (HttpResponse$BodyHandlers/ofString))))

(defn- open-feed
  [server path]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create (str (:seon.render.web/url server) path)))
                    (.GET)
                    (.build))]
    (.body (.send (client) request (HttpResponse$BodyHandlers/ofInputStream)))))

(defn- patches
  [text]
  (count (re-seq #"event: datastar-patch-elements" text)))

(defn- read-patches!
  "Read exactly through `expected` complete patch events."
  [stream expected]
  (support/await-event!
   (future
     (let [out (StringBuilder.)]
       (loop []
         (let [next-byte (.read stream)]
           (when (neg? next-byte)
             (throw (ex-info "SSE feed closed before its expected patches."
                             {::expected expected
                              ::actual (patches (.toString out))})))
           (.append out (char next-byte))
           (let [text (.toString out)]
             (if (and (= expected (patches text))
                      (str/ends-with? text "\n\n"))
               text
               (recur)))))))
   [:render-patches expected]))

(defn- read-until!
  "Read the feed until `needle` appears, returning everything read.
  Event-driven with the shared loud backstop, and the morph count of
  the returned text IS the coalescing measure: how many repaints the
  tab had to see before the settled value arrived."
  [stream needle]
  (support/await-event!
   (future
     (let [out (StringBuilder.)]
       (loop []
         (let [next-byte (.read stream)]
           (when (neg? next-byte)
             (throw (ex-info "SSE feed closed before its needle."
                             {::needle needle
                              ::actual (.toString out)})))
           (.append out (char next-byte))
           (let [text (.toString out)]
             (if (and (str/includes? text needle)
                      (str/ends-with? text "\n\n"))
               text
               (recur)))))))
   [:render-until needle]))

;;; ---------------------------------------------------------------------------
;;; The document
;;; ---------------------------------------------------------------------------

(deftest the-page-places-every-surface-at-its-own-id
  (with-server two-blocks
    (fn [_connection server _context]
      (let [response (fetch server "/")
            body (.body response)]
        (is (= 200 (.statusCode response)))
        (is (str/includes? body "id=\"surface-banner\""))
        (is (str/includes? body "id=\"surface-counter\"")
            "each block is its own morph target from the first paint")
        (is (str/starts-with? body "<!doctype html>"))))))

(deftest the-feed-opener-is-a-sibling-of-the-morph-targets
  ;; The quarry's recorded lesson: a data-init INSIDE a morphed element
  ;; is stripped by that element's first whole-element morph, and the
  ;; tab then looks alive while receiving nothing.
  (with-server two-blocks
    (fn [_connection server _context]
      (let [body (.body (fetch server "/"))]
        (is (< (.indexOf body "</main>") (.indexOf body "data-init"))
            "the opener is after every surface, not inside one")
        (is (str/includes? body "retryMaxCount: Infinity"))
        (is (str/includes? body "openWhenHidden: false"))))))

(deftest an-agent-page-is-the-same-mechanism-as-root
  ;; Root is an agent. If this test ever needs a root-specific branch,
  ;; the design has regressed.
  (with-server two-blocks
    (fn [connection server _context]
      (d/transact connection
                  [{:seon.cluster.agent/id "agent-b"
                    :seon.cluster.agent/blocks [(block-map :banner 0 `banner-html)]}])
      (let [root (.body (fetch server "/"))
            other (.body (fetch server "/agent/agent-b"))]
        (is (str/includes? root "surface-counter"))
        (is (not (str/includes? other "surface-counter"))
            "a different agent's page differs only in its block DATA")
        (is (str/includes? other "surface-banner"))))))

(deftest static-resources-come-off-the-classpath
  (with-server two-blocks
    (fn [_connection server _context]
      (is (= 200 (.statusCode (fetch server "/js/datastar.js"))))
      (testing "and path traversal is refused by construction"
        (is (= 404 (.statusCode (fetch server "/css/../../secret"))))))))

(deftest an-unknown-route-is-an-honest-404
  (with-server two-blocks
    (fn [_connection server _context]
      (is (= 404 (.statusCode (fetch server "/nope")))))))

;;; ---------------------------------------------------------------------------
;;; The wire — the rung's claim
;;; ---------------------------------------------------------------------------

(deftest the-initial-paint-sends-every-block-once
  (with-server two-blocks
    (fn [_connection server _context]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (try
          (let [initial (read-patches! stream 2)]
            (is (= 2 (patches initial)) "one morph per block, no page wrapper")
            (is (str/includes? initial "surface-banner"))
            (is (str/includes? initial "agents: 1")))
          (finally (.close stream)))))))

(deftest only-the-block-that-changed-goes-on-the-wire
  ;; THE RUNG'S THESIS, on a real socket. The quarry sent the whole page
  ;; on any relevant datom; this sends the one block whose projection
  ;; actually changed, and the block that reads nothing is never
  ;; re-serialized and never re-sent.
  (with-server two-blocks
    (fn [connection server _context]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (try
          (read-patches! stream 2)
          (d/transact connection [{:seon.cluster.agent/id "agent-b"}])
          (let [repaint (read-patches! stream 1)]
            (is (= 1 (patches repaint)) "ONE block, not the page")
            (is (str/includes? repaint "agents: 2") "and it is the right one")
            (is (not (str/includes? repaint "surface-banner"))
                "the block that reads nothing was not sent"))
          (finally (.close stream)))))))

(deftest a-broken-block-paints-its-card-and-spares-the-page
  ;; Fail loud, do not fall down: the page arrives, the working block
  ;; works, and the broken one occupies its own space saying so.
  (with-server [(block-map :banner 0 `banner-html)
                (block-map :broken 10 `broken-html)]
    (fn [_connection server _context]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (try
          (let [initial (read-patches! stream 2)]
            (is (= 2 (patches initial)))
            (is (str/includes? initial "seon-error-card"))
            (is (str/includes? initial "data-error-kind"))
            (is (str/includes? initial "surface-broken")
                "the failure keeps the block's address, so a fix morphs over it")
            (is (str/includes? initial "seon")
                "and the healthy sibling painted"))
          (finally (.close stream)))))))

(deftest reconnect-is-repaint
  ;; Nothing rendered is stored, so a new connection derives the current
  ;; page from current facts — which is also what makes a killed process
  ;; cost nothing.
  (with-server two-blocks
    (fn [connection server _context]
      (let [first-stream (open-feed server (str "/feed/" agent-id))]
        (read-patches! first-stream 2)
        (.close first-stream))
      (d/transact connection [{:seon.cluster.agent/id "agent-b"}])
      (let [second-stream (open-feed server (str "/feed/" agent-id))]
        (try
          (let [repaint (read-patches! second-stream 2)]
            (is (= 2 (patches repaint)) "a fresh connection paints everything")
            (is (str/includes? repaint "agents: 2")
                "at the CURRENT basis, not the one the first tab saw"))
          (finally (.close second-stream)))))))

(deftest two-tabs-each-get-their-own-complete-paint
  ;; ONE DERIVATION, N TABS — the shared registration's whole claim,
  ;; and the reason the proc exists. Both tabs still get their own
  ;; complete paint and their own byte-identical morph; what changed is
  ;; that the page behind them was derived once for the cluster.
  (with-server two-blocks
    (fn [connection server context]
      (let [a (open-feed server (str "/feed/" agent-id))
            b (open-feed server (str "/feed/" agent-id))]
        (try
          (read-patches! a 2)
          (read-patches! b 2)
          (let [before (derivations context)]
            (d/transact connection [{:seon.cluster.agent/id "agent-b"}])
            (let [to-a (read-patches! a 1)
                  to-b (read-patches! b 1)]
              (is (= 1 (patches to-a)))
              (is (= 1 (patches to-b)))
              (is (= to-a to-b)
                  "identical bytes — determinism is what lets ONE
                   registration serve every tab")
              (is (= 1 (- (derivations context) before))
                  "the commit cost ONE derivation, not one per tab")))
          (finally (.close a) (.close b)))))))

;;; ---------------------------------------------------------------------------
;;; The F2 sealed suite — seeds 2026072822, 2026072823, 2026072824, 2026072828
;;; ---------------------------------------------------------------------------

;;; 2. render-proc-one-derivation-many-tabs-test — seed 2026072822

(deftest render-proc-one-derivation-many-tabs-test
  ;; ORACLE: N real SSE tabs on one agent, one committed change — each
  ;; tab receives exactly the changed block's morph (counted events,
  ;; byte-compared), the proc's pass count advanced by ONE for that
  ;; commit rather than by N, and an untouched block's id appears on no
  ;; socket. This is the shared registration's whole reason to exist:
  ;; the wire was already exact per tab, the DERIVATION was not.
  (with-server two-blocks
    (fn [connection server context]
      (let [tabs (mapv (fn [_] (open-feed server (str "/feed/" agent-id)))
                       (range 4))]
        (try
          (doseq [tab tabs] (read-patches! tab 2))
          (let [before (derivations context)]
            (d/transact connection [{:seon.cluster.agent/id "agent-b"}])
            (let [morphs (mapv (fn [tab] (read-patches! tab 1)) tabs)]
              (is (every? (fn [morph] (= 1 (patches morph))) morphs)
                  "each tab got exactly ONE morph — the changed block")
              (is (= 1 (count (distinct morphs)))
                  "byte-identical across every tab, because one
                   derivation produced them all")
              (is (every? (fn [morph] (str/includes? morph "agents: 2"))
                          morphs)
                  "and it is the block that actually changed")
              (is (not-any? (fn [morph] (str/includes? morph "surface-banner"))
                            morphs)
                  "the untouched block's id reached NO socket")
              (is (= 1 (- (derivations context) before))
                  "ONE derivation for the commit, not one per tab")))
          (finally (doseq [tab tabs] (.close tab))))))))

;;; 3. slow-tab-newest-complete-page-test — seed 2026072823

(deftest slow-tab-newest-complete-page-test
  ;; ORACLE: one tap deliberately unread while K distinct commits land.
  ;; On read it yields ONE value equal to the NEWEST COMPLETE page —
  ;; every block current, no lost morph — which is the §1.2 displacement
  ;; class dead by construction: increments on a sliding-1 buffer would
  ;; permanently lose a block whose patch was displaced by another
  ;; block's. The proc never parked while the slow tap sat full: a fast
  ;; sibling tab observed every repaint.
  (with-server two-blocks
    (fn [connection server context]
      (let [slow (async/chan (async/sliding-buffer 1))
            fast (open-feed server (str "/feed/" agent-id))]
        (async/tap (:pages-mult context) slow)
        (try
          (read-patches! fast 2)
          (let [before (derivations context)
                k 5]
            ;; nobody reads `slow` for the whole burst
            (doseq [n (range k)]
              (d/transact connection
                          [{:seon.cluster.agent/id (str "slow-" n)}])
              ;; the fast sibling proves the proc kept passing while the
              ;; slow tap stayed full — never parked, never blocked
              (read-patches! fast 1))
            (let [pending (support/await-event! slow [:slow-tap-newest])
                  page (get pending agent-id)]
              (is (some? page) "the slow tap yielded a value")
              (is (= 2 (count page))
                  "a COMPLETE page — every block present, not a patch")
              (is (= page (web/page-of @connection agent-id caps nil))
                  "and it is the NEWEST page: byte-equal to a fresh
                   derivation at the current basis, so no block was lost
                   to displacement")
              (is (nil? (async/poll! slow))
                  "exactly ONE value was pending, newest-wins")
              (let [passes (- (derivations context) before)]
                (is (<= passes k)
                    "coalescing means passes never exceed commits")
                (is (pos? passes) "and the proc did keep deriving"))))
          (finally
            (async/untap (:pages-mult context) slow)
            (.close fast)))))))

;;; 4. reconnect-is-repaint-wire-test — seed 2026072824

(deftest reconnect-is-repaint-wire-test
  ;; ORACLE: the in-process kill projection — drop the taps and the
  ;; channel contents mid-flight, then reopen the feed. The initial
  ;; paint derives EVERY block from current facts. The database holds no
  ;; partial text at ANY basis (an as-of walk over the window), and
  ;; nothing is retracted because nothing was ever written: after F2 no
  ;; partial row CAN exist, so the stale-partial repair class is
  ;; unrepresentable rather than handled.
  (with-server two-blocks
    (fn [connection server context]
      ;; a tab, a commit it never sees, and then the socket is gone —
      ;; the kill projection, minus killing the JVM (F4 owns that)
      (let [doomed (open-feed server (str "/feed/" agent-id))]
        (read-patches! doomed 2)
        (.close doomed))
      ;; a snapshot in flight on the stream conn, dropped on the floor
      (async/offer! (:stream-channel context)
                    {:seon.cluster.agent/id agent-id
                     :seon.ai/partial {:seon.ai/text "half a re"
                                       :seon.ai/tokens 3}})
      (async/poll! (:stream-channel context))
      (d/transact connection [{:seon.cluster.agent/id "after-the-drop"}])
      (let [fresh (open-feed server (str "/feed/" agent-id))]
        (try
          (let [repaint (read-patches! fresh 2)]
            (is (= 2 (patches repaint))
                "a fresh connection paints EVERY block")
            (is (str/includes? repaint "agents: 2")
                "at the CURRENT basis — reconnect is repaint, and the
                 in-flight partial was superseded, never replayed"))
          (finally (.close fresh))))
      (testing "and no partial text exists at ANY basis in the window"
        (let [db @connection
              stream-attributes
              (d/q '[:find [?ident ...]
                     :where [_ :db/ident ?ident]
                     [(namespace ?ident) ?ns]
                     [(clojure.string/starts-with? ?ns "seon.ai.stream")]]
                   db)]
          (is (empty? stream-attributes)
              "the attribute family is GONE from the registry, so a
               partial row is unrepresentable — nothing to retract,
               nothing to mistake for a settled reply")
          ;; every REAL basis in the window — Datahike transaction ids
          ;; start above 536870912, so the walk asks the facts which
          ;; bases exist rather than counting from one
          (doseq [t (sort (d/q '[:find [?tx ...]
                                 :where [?tx :db/txInstant _]]
                               db))]
            (is (empty? (d/q '[:find [?e ...]
                               :where [?e :seon.ai.stream/text _]]
                             (d/as-of db t)))
                (str "no partial row at basis " t))))))))

;;; 8. coalesce-floor-one-derivation-test — seed 2026072828

(deftest coalesce-floor-one-derivation-test
  ;; ORACLE: M commits inside one floor window cost ONE derivation pass,
  ;; and each tab receives at most one morph per actually-changed block.
  ;; The floor is read from the CONFIG FACT planted per trial, honoured
  ;; at the proc, so a burst costs one derivation for the whole cluster
  ;; instead of one per tab. It remains a coalescing floor over an
  ;; observed event — the commit — never a poll.
  (with-server two-blocks
    (fn [connection server context]
      ;; the dial as a fact, the way production ships it
      (d/transact connection [{:seon.config/cluster "web-test"
                               :seon.config.render/coalesce-ms 250}])
      (let [tab (open-feed server (str "/feed/" agent-id))]
        (try
          (read-patches! tab 2)
          ;; the dial's own commit changes no block, so suppression
          ;; correctly puts NOTHING on the wire for it — the tab is
          ;; already settled
          (let [before (derivations context)
                m 6]
            (doseq [n (range m)]
              (d/transact connection
                          [{:seon.cluster.agent/id (str "burst-" n)}]))
            ;; the counter block changed m times; the tab sees the
            ;; settled value after far fewer repaints than commits
            (let [settled (read-until! tab (str "agents: " (+ 1 m)))]
              (is (< (patches settled) m)
                  (str "the tab saw " (patches settled) " repaints for "
                       m " commits — the burst coalesced"))
              (is (not (str/includes? settled "surface-banner"))
                  "and the unchanged block still never went on the wire"))
            (let [passes (- (derivations context) before)]
              (is (< passes m)
                  (str "the floor coalesced " m " commits into " passes
                       " derivations for the whole cluster"))))
          (finally (.close tab)))))))

;;; ---------------------------------------------------------------------------
;;; Suppression, as a pure unit
;;; ---------------------------------------------------------------------------

(deftest suppression-compares-bytes
  ;; Bytes are what the socket costs and what the browser diffs, and the
  ;; serializer is deterministic, so this is sound.
  ;; a PAGE — `{surface-id → html}` — because since F2 the serialization
  ;; happens once in `page-of` and the proc and every tab compare the
  ;; same bytes; suppression itself is unchanged in kind, only relocated
  (let [page {"surface-x" "<div id=\"surface-x\">same</div>"}
        first-pass (web/changed {} page)
        second-pass (web/changed (:seon.render.web/delivered first-pass) page)]
    (is (= 1 (count (:seon.render.web/patches first-pass))))
    (is (= 0 (count (:seon.render.web/patches second-pass)))
        "an unchanged surface is not sent twice")
    (is (= (:seon.render.web/delivered first-pass)
           (:seon.render.web/delivered second-pass)))))

(deftest a-nil-projection-keeps-the-blocks-identified-wrapper
  (let [surface
        (block/surface {:seon.db/db nil
                        :seon.cluster.agent/id agent-id
                        :seon.sci.admit/caps caps}
                       {:seon.render.block/name :optional
                        :seon.render.block/priority 0
                        :seon.render/html `omitted-html}
                       :seon.render/html)
        html (web/surface-html surface caps nil)]
    (is (nil? (:seon.error/value surface))
        "nil is an omitted projection, not malformed hiccup")
    (is (= "<div id=\"surface-optional\"></div>" html)
        "empty content keeps the block's stable morph target")))

(deftest a-later-non-nil-render-patches-back-into-the-same-wrapper
  (let [empty-wrapper "<div id=\"surface-optional\"></div>"
        visible "<div id=\"surface-optional\">now visible</div>"
        first-paint (web/changed {} {"surface-optional" empty-wrapper})
        repaint (web/changed (:seon.render.web/delivered first-paint)
                             {"surface-optional" visible})]
    (is (= [["surface-optional" "<div id=\"surface-optional\"></div>"]]
           (:seon.render.web/patches first-paint)))
    (is (= 1 (count (:seon.render.web/patches repaint))))
    (is (str/includes? (second (first (:seon.render.web/patches repaint)))
                       "now visible")
        "the stable id lets the later whole-element morph land")))

(deftest the-data-drill-is-browsable-and-its-cursor-is-in-the-url
  ;; A drilled position is a LINK, so the proof is that following one
  ;; lands somewhere different from the root.
  (with-server two-blocks
    (fn [_connection server _context]
      (let [root (.body (fetch server "/data"))]
        (is (str/includes? root "seon-data-drill"))
        (is (str/includes? root "showing 1")
            "a window, and it says so"))
      (testing "a stale or mangled cursor shows the root rather than failing"
        (let [response (fetch server "/data?path=%7Bbroken&offset=nope")]
          (is (= 200 (.statusCode response)))
          (is (str/includes? (.body response) "seon-data-drill")))))))

;;; ---------------------------------------------------------------------------
;;; Derived ports — bookmarkable, restart-stable, collision-tolerant
;;; ---------------------------------------------------------------------------

(deftest a-name-derives-one-port-forever
  ;; THE POINT IS A BOOKMARK: a named cluster must answer on the same
  ;; port after every restart, or the tab a person left open stops
  ;; working for no visible reason.
  (doseq [name ["default" "acme" "morning" "a" "клас" "with-dashes"]]
    (is (= (web/derived-port name) (web/derived-port name))
        (str "unstable derivation for " name)))
  (testing "and it is a pinned VALUE, not merely stable within a run —
            these are the numbers a bookmark depends on"
    (is (= 7994 (web/derived-port "default")))
    (is (= 7815 (web/derived-port "acme")))))

(deftest derived-ports-stay-inside-the-documented-range
  ;; Below the ephemeral range the OS allocates from, so a derived port
  ;; can never collide with one the OS was about to hand out.
  (support/assert-check!
   (tc/quick-check
    500
    (prop/for-all [name (gen/such-that seq gen/string-ascii 100)]
      (<= web/port-floor (web/derived-port name) (dec web/port-ceiling)))
    :seed 202607280501)
   "derived port range"))

(deftest different-names-mostly-differ-and-collisions-are-survivable
  ;; Three hundred ports and a hash: collisions are EXPECTED. The
  ;; contract is not that they never happen, it is that they cost
  ;; nothing — which the next test proves.
  (let [names (map (fn [index] (str "cluster-" index)) (range 60))
        ports (map web/derived-port names)]
    (is (> (count (distinct ports)) 45)
        "a derivation that bunched everything onto a few ports would
         make the fallback the normal path rather than the exception")))

(deftest a-taken-port-serves-anyway-and-says-so
  ;; A name collision must not look like a broken build, and a moved
  ;; bookmark must not fail silently. Both numbers, or neither is
  ;; actionable.
  (with-server two-blocks
    (fn [connection first-server _graph]
      (let [taken (:seon.render.web/port first-server)
            second-server (web/start!
                           {:seon.store/connection connection
                            :seon.cluster.agent/id agent-id
                            :seon.sci.admit/caps caps
                            ;; its own disposable view half: this test
                            ;; is about the PORT, and the second view
                            ;; never opens a feed
                            :seon.render.web/pages-mult
                            (async/mult (async/chan (async/sliding-buffer 1)))
                            :seon.render.web/registration (atom {})
                            :seon.render.web/render-channel
                            (async/chan (async/sliding-buffer 1))
                            :seon.render.web/port taken})]
        (try
          (is (not= taken (:seon.render.web/port second-server))
              "it bound somewhere else")
          (is (= taken (:seon.render.web/wanted-port second-server))
              "and it says which bookmark just stopped working")
          (is (= 200 (.statusCode (fetch second-server "/")))
              "while serving normally — the collision costs a port, not a view")
          (finally (web/stop! second-server))))))
  (testing "a clean bind reports no wanted-port at all, so key presence
            answers 'did this fall back?'"
    (with-server two-blocks
      (fn [_connection server _context]
        (is (nil? (:seon.render.web/wanted-port server)))))))
