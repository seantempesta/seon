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
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http]
            [seon.blob :as blob]
            [seon.agent :as agent]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.context :as seon.context]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as sci.kernel]
            [seon.test-support :as support]
            [seon.program :as program]
            [sci.core :as sci])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Element]
           [java.util.concurrent CompletableFuture]
           [java.net BindException URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (support/environment "seon.render.web-test")))

(def ^:private caps
  (config/result-caps config/defaults))

(def ^:private process
  "This suite's run-holder identity. The web service REQUIRES one —
  which processes are alive is the one input no database value answers,
  and a page that guessed it would either invent wedges or hide them."
  "web-test-1")

(def ^:private agent-id "root")

(defn- web-private
  [function-name]
  (deref (ns-resolve 'seon.render.web function-name)))

(defn dom
  "The served HTML as its document: a page test asserts on the element and
  attribute its behavior is about, never on the bytes around them."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [^String html]
  (Jsoup/parse html))

(defn element
  "The first element `css` selects in `html`, or nil."
  {:malli/schema [:=> [:cat :string :string] :seon.schema/value]}
  ^Element [html css]
  (.selectFirst (dom html) ^String css))

(defn texts
  "The text of every element `css` selects in `html`."
  {:malli/schema [:=> [:cat :string :string] [:vector :string]]}
  [html css]
  (mapv #(.text ^Element %) (.select (dom html) ^String css)))

(defn painted
  "The elements a response delivered as one document: every
  `datastar-patch-elements` payload in order, or the page itself when the
  response is a document rather than a feed."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [body]
  (let [payloads (keep #(when (str/starts-with? % "data: elements ") (subs % 15))
                       (str/split-lines body))]
    (dom (if (seq payloads) (str/join "\n" payloads) body))))

(defn shows?
  "Whether the painted elements show `text`: what a reader sees, not markup."
  {:malli/schema [:=> [:cat :string :string] :boolean]}
  [sse text]
  (str/includes? (.text (painted sse)) text))

(defn stream-strip
  "The last painted stream strip as data: nil when none was painted, {} when
  empty, else its live reply text and token count."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [sse]
  (when-let [strip (last (.select (painted sse) "#surface-stream"))]
    (if-let [reply (.selectFirst strip ".seon-stream-reply")]
      {:text (.text (.selectFirst reply ".seon-stream-text"))
       :tokens (parse-long (.text (.selectFirst reply ".seon-stream-count")))}
      {})))

(defn href
  "A link as data: its path and its decoded query parameters."
  {:malli/schema [:=> [:cat :string] [:map [:path :string] [:query [:map-of :string :string]]]]}
  [link]
  (let [uri (URI. link)]
    {:path (.getPath uri)
     :query (into {} (map #(mapv (fn [part] (java.net.URLDecoder/decode ^String part "UTF-8"))
                                 (str/split % #"=" 2)))
                  (some-> (.getRawQuery uri) (str/split #"&")))}))

(defn data-links
  "Every `/data` link in the page's data panel, as data."
  {:malli/schema [:=> [:cat :string] [:vector :seon.schema/value]]}
  [html]
  (->> (.select (dom html) ".seon-data-panel a[href^=/data]")
       (mapv #(href (.attr ^Element % "href")))))

(defn labelled
  "The debug header's fields as data: each `<span>label</span>` followed by
  its shown value (`<code>` text or a value-printer panel), label to text."
  {:malli/schema [:=> [:cat :string] [:map-of :string :string]]}
  [html]
  (into {}
        (keep (fn [^Element span]
                (when-let [shown (.nextElementSibling span)]
                  (when (or (= "code" (.tagName shown)) (.hasClass shown "seon-data-panel"))
                    [(.text span) (.text shown)]))))
        (.select (dom html) "span")))

;;; ---------------------------------------------------------------------------
;;; Fixture
;;; ---------------------------------------------------------------------------

(deftest accepted-socket-writes-have-a-loud-drain-backstop
  (let [drained (CompletableFuture.)
        closed? (atom false)
        ;; A socket that accepts the frame and never drains it.
        result
        ((web-private 'write-package!)
         {:seon.render.web/send! (fn [_channel _frame _close?] true)
          :seon.render.web/write-state (fn [_channel]
                                         {:http-kit.write/pending-bytes 17
                                          :http-kit.write/drained drained})
          :seon.render.web/close-sse! (fn [_generator] (reset! closed? true))}
         ::channel ::generator (byte-array [1]) 20 ::socket-drain)]
    (is (= :seon.await/future (:seon.render.web/refused-member result)))
    (is (= 20 (get-in result [:seon.await/config-value])))
    (is (= ::socket-drain (:seon.await/requested-member result)))
    (is (true? @closed?))))

(defn- with-server
  "The whole render pipeline on real sockets: the render proc in its own
  graph, the mult the tabs tap, the routing listener that wakes it.

  `body` receives the connection, the server descriptor, and a CONTEXT
  map holding the live pipeline: `:graph` so a test can `flow.core/ping`
  it and assert HOW MANY derivations a commit cost (the claim the shared
  registration exists to make), and `:pages-mult` so a test can take a
  tap of its own and be the slow browser on purpose.

  `service` adds declared service members, such as the socket leaf
  `:seon.render.web/http`, to the service the server starts with."
  ([body] (with-server {} body))
  ([service body]
  (support/with-database
    (fn [connection]
      (let [_ (support/seed-cluster! connection "web-test")
            _ (support/transacted! connection
                                   (cluster.agent/creation-tx
                                    {:seon.agent/id agent-id
                                     :seon.cluster/name "web-test"
                                     :seon.ns/name 'my.agents.root}))
            ctx (support/fork-cluster-ctx connection)
            server (atom nil)
            render-channel (async/chan (async/sliding-buffer 1))
            runtime-eval-channel (async/chan (async/sliding-buffer 1))
            pages-channel (async/chan (async/sliding-buffer 1))
            registration (atom {})
            latest-packages (atom {})
            interest (atom :all)
            completion (async/promise-chan)
            fault-channel (async/chan (async/dropping-buffer 8))
            stream-channel (async/chan (async/sliding-buffer 1))
            graph-errors (atom [])
            view {:seon.render.web/render-channel render-channel
                  :seon.render.web/runtime-eval-channel runtime-eval-channel
                  :seon.render.web/pages-channel pages-channel
                  :seon.render.web/registration registration
                  :seon.render.web/latest-packages latest-packages
                  :seon.render.web/interest interest
                  :seon.render.web/completion completion
                  :seon.render.web/root-agent-id agent-id
                  ::web/profile
                  (render/agent-render-profile config/defaults)}
            handle (support/cluster-handle
                    {:seon.db/connection connection
                     :seon.cluster/name "web-test"
                     :seon.sci.admit/caps caps
                     :seon.sci.eval/ctx ctx
                     :seon.db.process/id process
                     :seon.turn.loop/stream-channel stream-channel
})
            graph (flow.core/create-flow
                   {:procs
                    {:seon.render.web/render
                     {:proc (flow/var-process
                             #'web/render-step :io
                             (assoc view
                                    :seon.env/environment @test-environment
                                    :seon.turn.loop/cluster
handle))}}
                    :conns []})
            pages-mult (async/mult pages-channel)
            {:keys [report-chan error-chan]} (flow.core/start graph)]
        ;; nothing asserts on reports here, but an unread report or
        ;; error channel would eventually park the graph's own plumbing.
        ;; THE ERRORS ARE KEPT, NOT DISCARDED: a proc that throws stops
        ;; taking, so the symptom every later assertion sees is a
        ;; timeout, and draining the error channel into the void hid
        ;; the one value that names the cause.
        (async/go-loop [] (when (async/<! report-chan) (recur)))
        (async/go-loop []
          (when-let [failure (async/<! error-chan)]
            (swap! graph-errors conj failure)
            (recur)))
        (try
          (flow.core/resume graph)
          (wake/route! {:seon.cluster.wake/connection connection
                        :seon.cluster.wake/channels (constantly {})
                        :seon.cluster.wake/fenced? (fn [_ _] false)
                        :seon.flow/plan-channel
                        (async/chan (async/sliding-buffer 1))
                        :seon.cluster.wake/render-channel render-channel
                        :seon.render.web/interest interest
                        :seon.cluster.wake/fault-channel fault-channel
                        :seon.cluster.wake/key ::route})
          (reset! server (web/start!
                          (cond-> {:seon.turn.loop/cluster handle
                           :seon.store/connection-object connection
                           :seon.agent/id agent-id
                           :seon.sci.admit/caps caps
                           :seon.sci.eval/ctx ctx
                           :seon.config.eval/time-limit-ms
                           (:seon.config.eval/time-limit-ms
                            config/defaults)
                           :seon.db.process/id process
                           :seon.render.web/pages-mult pages-mult
                           :seon.render.web/registration registration
                           :seon.render.web/latest-packages latest-packages
                           :seon.render.web/render-channel render-channel
                           :seon.render.web/fault-channel fault-channel}
                            ;; a declared member is present or absent, never nil
                            (:seon.render.web/http service)
                            (assoc :seon.render.web/http (:seon.render.web/http service)))))
          (body connection @server
                {:graph graph
                 :pages-mult pages-mult
                 :render-channel render-channel
                 :runtime-eval-channel runtime-eval-channel
                 :stream-channel stream-channel
                 :fault-channel fault-channel
                 :latest-packages latest-packages
                 :registration registration
                 :ctx ctx})
          (finally
            (when @server
              (web/stop! (assoc @server :seon.render.web/registration registration
                                :seon.config.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults))))
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key ::route})
            (flow.core/stop graph)
            ;; NAME THE CAUSE BEFORE THE SYMPTOM. A thrown transform
            ;; leaves the proc unable to take, so the completion below
            ;; never arrives and every reading times out; report what
            ;; the graph actually said first.
            (is (= [] (mapv #(ex-message (:clojure.core.async.flow/ex %))
                            @graph-errors))
                "the render graph reported no proc error")
            ;; the proc's OWN completion, under the shared loud
            ;; backstop: a wedged proc must fail this suite noisily
            ;; rather than hang the runner forever
            (support/await-event! completion
                                  [:render-proc-stopped])
            (async/close! render-channel)
            (async/close! runtime-eval-channel)
            (async/close! pages-channel)
            (async/close! stream-channel))))))))

(defn- ping-state
  "Await one proc reply under the declared test-event backstop."
  [context]
  (if-let [reply (flow.core/ping-proc
                 (:graph context) :seon.render.web/render
                 :timeout-ms (* 1000 support/event-backstop-seconds))]
    (:clojure.core.async.flow/state reply)
    (throw (ex-info "The render proc did not publish its requested reply."
                    {::support/event :render-proc-ping}))))

(defn- derivations
  "The render proc's pass count — the oracle for ONE derivation per
  commit however many tabs are open."
  [context]
  (:seon.render.web/passes (ping-state context)))

(defn- streaming-agents
  "The number of admitted partials in the render proc."
  [context]
  (:seon.render.web/streaming-agents (ping-state context)))

(defn- await-ping!
  "Wait until `pred` accepts the render proc's published ping state."
  [context pred label]
  (support/await-event!
   (future
     (loop []
       (let [state (ping-state context)]
         (if (pred state)
           state
           (recur)))))
   label))

(defn- settle-render!
  "Fence all render work preceding this message and return the pass count
  produced by the fence's own fact-only derivation."
  [context]
  (let [settlement (async/promise-chan)]
    (is (async/offer!
         (:render-channel context)
         {:seon.render.web/settlement settlement})
        "the settlement request enters the render proc's sliding in-port")
    (support/await-event!
     settlement
     [:render-settled])))

(defn- open-run!
  "Open a minimal run entity for one renderer presence-gate test."
  [connection run-id]
  (support/transacted! connection
                     [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}]))

(defn- client [] (.build (HttpClient/newBuilder)))

(defn- fetch
  ([server path] (fetch server path {}))
  ([server path headers]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create (str (:seon.render.web/url server) path)))
                    (as-> builder (reduce-kv (fn [result k v] (.header result k v)) builder headers))
                    (.GET)
                    (.build))]
    (.get (.sendAsync (client) request (HttpResponse$BodyHandlers/ofString))
          support/event-backstop-seconds java.util.concurrent.TimeUnit/SECONDS))))

(defn- post-form
  ([server path body]
   (post-form server path body nil))
  ([server path body origin]
   (let [builder (-> (HttpRequest/newBuilder
                      (URI/create (str (:seon.render.web/url server) path)))
                     (.header "content-type"
                              "application/x-www-form-urlencoded")
                     (.POST (HttpRequest$BodyPublishers/ofString body)))
         request (cond-> builder
                   origin (.header "origin" origin))]
     (.get (.sendAsync (client) (.build request)
                      (HttpResponse$BodyHandlers/ofString))
           support/event-backstop-seconds java.util.concurrent.TimeUnit/SECONDS))))

(defn- open-feed
  [server path]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create (str (:seon.render.web/url server) path)))
                    (.GET)
                    (.build))]
    (.body (.get (.sendAsync (client) request (HttpResponse$BodyHandlers/ofInputStream))
                 support/event-backstop-seconds java.util.concurrent.TimeUnit/SECONDS))))

(defn- debug-feed-path
  ([agent-id path] (debug-feed-path agent-id path ""))
  ([agent-id path extra]
   (str "/feed/" agent-id "?debug=true&path="
        (URLEncoder/encode (pr-str path) "UTF-8")
        "&offset=0" extra)))

(defn- patches
  [text]
  (count (re-seq #"event: datastar-patch-elements" text)))

(def ^:private patch-event-line "event: datastar-patch-elements")

(defn- read-events!
  "Read complete SSE events, deciding at each event boundary whether to stop.

  The decision is per EVENT, never per byte: rebuilding and rescanning the
  whole buffer after every byte is quadratic, and one page of a real entity
  is most of a megabyte — the read, not the page, was the clock that fired."
  [stream done? closed]
  (let [out (StringBuilder.)
        line (StringBuilder.)]
    (loop [events 0 blank? false]
      (let [next-byte (.read stream)]
        (when (neg? next-byte)
          (throw (ex-info "SSE feed closed before its expected patches."
                          (assoc closed ::actual (patches (.toString out))))))
        (let [character (char next-byte)]
          (.append out character)
          (if (= \newline character)
            (let [text (.toString line)
                  events (if (= patch-event-line text) (inc events) events)
                  boundary? (and blank? (zero? (.length line)))]
              (.setLength line 0)
              (if (and boundary? (done? events (.toString out)))
                (.toString out)
                (recur events true)))
            (do (.append line character)
                (recur events false))))))))

(defn- read-patches!
  "Read exactly through `expected` complete patch events."
  [stream expected]
  (support/await-event!
   (future (read-events! stream
                         (fn [events _text] (= expected events))
                         {::expected expected}))
   [:render-patches expected]))

(defn- read-until!
  "Read the feed until `needle` appears, returning everything read.
  Event-driven with the shared loud backstop, and the morph count of
  the returned text IS the coalescing measure: how many repaints the
  tab had to see before the settled value arrived."
  [stream needle]
  (support/await-event!
   (future (read-events! stream
                         (fn [_events text] (str/includes? text needle))
                         {::needle needle}))
   [:render-until needle]))

(defn- read-complete-paint!
  [stream _connection]
  (let [paint (read-patches! stream 1)]
    (is (= 1 (patches paint))
        "the feed sends one proc-framed keyframe event")
    (is (some? (stream-strip paint))
        "the proc-framed keyframe carries the stable stream surface")
    paint))

;;; ---------------------------------------------------------------------------
;;; The document
;;; ---------------------------------------------------------------------------

;; DELETED 2026-08-29 (owner gate ruling): the transcript block is
;; absent from the page at HEAD — the retired-assembler blocker
;; (docs/seon/issues/agent-html-still-uses-the-retired-transcript-assembler.md)
;; and wave G's chat-face rebuild own its return; the pin parks here.

(deftest the-feed-opener-is-a-sibling-of-the-morph-targets
  ;; The quarry's recorded lesson: a data-init INSIDE a morphed element
  ;; is stripped by that element's first whole-element morph, and the
  ;; tab then looks alive while receiving nothing.
  (with-server
    (fn [_connection server _context]
      (let [opener (element (.body (fetch server "/")) "[data-init]")]
        (is (= {:morph-target nil :parent "body"}
               {:morph-target (.closest opener "main, [id^=surface-], .seon-walk-unit")
                :parent (.tagName (.parent opener))})
            "the opener is a child of the body, inside no surface")))))

(deftest an-agent-page-is-the-same-mechanism-as-root
  ;; Root is an agent. If this test ever needs a root-specific branch,
  ;; the design has regressed.
  (with-server
    (fn [connection server _context]
      (support/transacted! connection
                         (cluster.agent/creation-tx
                          {:seon.agent/id "agent-b"
                           :seon.cluster/name "web-test"
                           :seon.ns/name 'my.agents.agent-b}))
      ;; One route, one page shape: each agent's page names that agent and
      ;; walks from its own root namespace.
      (let [page (fn [path]
                   (let [html (.body (fetch server path))]
                     {:title (first (texts html "title"))
                      :namespace (some #{"my.agents.root" "my.agents.agent-b"} (texts html "code"))
                      :walk-root? (some? (element html "[data-walk-path=\"[]\"]"))}))]
        (is (= [{:title "seon · root" :namespace "my.agents.root" :walk-root? true}
                {:title "seon · agent-b" :namespace "my.agents.agent-b" :walk-root? true}]
               [(page "/") (page "/agent/agent-b")]))))))

(deftest static-resources-come-off-the-classpath
  (with-server
    (fn [_connection server _context]
      (is (= 200 (.statusCode (fetch server "/js/datastar.js"))))
      (let [stylesheet (fetch server "/css/input.css")]
        (is (= 200 (.statusCode stylesheet)))
        (is (= [] (remove #(str/includes? (.body stylesheet) %)
                          [".seon-print-summary" ".seon-print-table" ".seon-render-unavailable"]))
            "every semantic class has a rule in the served stylesheet"))
      (testing "and path traversal is refused by construction"
        (is (= 404 (.statusCode (fetch server "/css/../../secret"))))))))

(deftest an-unknown-route-is-an-honest-404
  (with-server
    (fn [_connection server _context]
      (is (= 404 (.statusCode (fetch server "/nope")))))))

(defn- unowned-namespace
  "A corpus namespace with no assigned agent at `db`: `seon.flow` when free.

  The fixture branches the cluster head, whose namespaces may already have
  owners, so a test that needs an unowned page names one this way."
  [db]
  (let [names (sort (db/q '[:find [?name ...] :where [_ :seon.ns/name ?name]] db))
        free (remove #(seq (cluster.agent/assigned-to db %)) names)]
    (or (some #{'seon.flow} free) (first free))))

(deftest a-get-of-an-unowned-namespace-page-never-writes
  ;; 24w: the GET used to create the owner agent, which then ran paid
  ;; provider turns. A read path never writes a transaction.
  (with-server
    (fn [connection server _context]
      (let [namespace-name (unowned-namespace @connection)
            commit-before (db/commit-id @connection)
            basis-before (:max-tx @connection)
            known (fetch server (str "/ns/" namespace-name))]
        (is (= 200 (.statusCode known)))
        (is (= commit-before (db/commit-id @connection))
            "the head commit id is unchanged")
        (is (= basis-before (:max-tx @connection))
            "no transaction was committed")
        (is (= [] (cluster.agent/assigned-to @connection namespace-name))
            "no agent was created")
        (is (= (str "/agent/" namespace-name "/context")
               (some-> (element (.body known) "#create-namespace-owner")
                       (.closest "form")
                       (.attr "action")))
            "the page offers the explicit create control, posting to the namespace's agent")))))

(declare service-request)

(deftest a-request-that-throws-answers-its-error-value-never-a-stack-trace
  ;; Class regression (2026-09-27): POST action=system-turn answered HTTP 500
  ;; whose body was the Throwable printed with 163 stack frames. Every throw
  ;; in a request answers the error value through the error pair's HTML face.
  (support/with-database
    (fn [connection]
      (let [failure (ex-info "The record refused its request."
                             {:seon.error/at (java.util.Date.) :seon.error/layer :seon.turn/turn
                              :seon.error/operation 'seon.turn/record-evaluated-tx
                              :seon.error/message "The record refused its request."})
            response (#'web/failure-response (service-request connection {}) failure)]
        (is (= {:status 500 :content-type "text/html; charset=utf-8"}
               {:status (:status response)
                :content-type (get-in response [:headers "content-type"])}))
        ;; The body is the error value's face: its message is shown, and no
        ;; stack frame reaches the page.
        (is (str/includes? (.text (dom (:body response))) "The record refused its request."))
        (is (not (re-find #"\n\s+at |\.java:\d+\)" (:body response)))
            "no stack frame reaches the body")))))

(deftest the-inbox-received-button-acknowledges-its-message
  ;; Owner ruling 2026-09-27: the inbox's HTML face offers the AI face's
  ;; (my.message/received! id) as a button; its POST commits the acknowledgement.
  (with-server
    (fn [connection server _context]
      (support/transacted! connection (support/agents-tx @connection ["page-sender" "page-reader"]))
      (support/transacted! connection [{:seon.message/id "page-inbox-1" :seon.message/content "Read me."
                                        :seon.message/from [:seon.agent/id "page-sender"]
                                        :seon.message/to [:seon.agent/id "page-reader"]}])
      (let [route "/agent/page-reader/context"
            acknowledged (post-form server route "action=received&message-id=page-inbox-1")]
        (is (= 204 (.statusCode acknowledged)))
        (is (some? (:seon.message/received-tx
                    (db/pull @connection [:seon.message/received-tx] [:seon.message/id "page-inbox-1"])))
            "the acknowledgement is committed")
        (is (= 422 (.statusCode (post-form server route "action=received")))
            "a received action without its message refuses by name")))))

(deftest namespace-routes-admit-by-reader-and-existing-corpus-entity
  (with-server
    (fn [connection server _context]
      (testing "the explicit POST creates the owner once, with its provenance"
        (let [namespace-name (unowned-namespace @connection)
              route (str "/agent/" namespace-name "/context")
              created (post-form server route "action=create-owner")
              owner (cluster.agent/steward-of @connection namespace-name)
              basis-after-create (:max-tx @connection)]
          (is (= 303 (.statusCode created)))
          (is (= (str "/ns/" namespace-name)
                 (.orElse (.firstValue (.headers created) "location") nil)))
          (is (= (str namespace-name) owner))
          (is (= [process]
                 (db/q '[:find [?process-id ...]
                        :in $ ?agent-id
                        :where
                        [?agent :seon.agent/id ?agent-id ?tx]
                        [?tx :seon.db/process ?process]
                        [?process :seon.db.process/id ?process-id]]
                      @connection owner))
              "creation carries the existing creation provenance")
          (is (= 303 (.statusCode (post-form server route "action=create-owner"))))
          (is (= basis-after-create (:max-tx @connection))
              "a repeated create resumes the existing owner untouched")))
      (is (= 404 (.statusCode (post-form server "/agent/nonexistent.thing/context"
                                         "action=create-owner")))
          "creation refuses a namespace with no corpus entity")
      ;; The head commit id is the whole-store witness; decoding every
      ;; datom to count them cost seconds and proved nothing more.
      (doseq [path ["/ns/nonexistent.thing" "/ns/123bad"]]
        (let [commit-before (db/commit-id @connection)
              response (fetch server path)]
          (is (= 404 (.statusCode response)))
          (is (= commit-before (db/commit-id @connection))
              "committed no transaction"))))))

(deftest canonical-debug-inspects-without-creating-a-namespace-owner
  (with-server
    (fn [connection server _context]
      (let [namespace-name (unowned-namespace @connection)
            basis-before (:max-tx @connection)
            response (fetch server
                            (str "/ns/" namespace-name "/debug?subject="
                                 (java.net.URLEncoder/encode
                                  (pr-str [:seon.ns/name namespace-name])
                                  "UTF-8")
                                 "&output=%3Aseon.render%2Fhtml"))
            body (.body response)]
        (is (nil? (cluster.agent/steward-of @connection namespace-name)))
        (is (= 200 (.statusCode response)))
        (is (= basis-before (:max-tx @connection))
            "inspection commits no transaction")
        (is (nil? (cluster.agent/steward-of @connection namespace-name))
            "inspection does not create an agent")
        (is (some? (element body "#debug-inspection-header"))
            "the page is the inspection page")
        (is (str/includes? (.attr (element body "[data-init]") "data-init") "debug=true")
            "the existing feed receives the experiment request")))))

(deftest debug-header-identifies-the-handed-program-and-projection
  (support/with-database
    (fn [connection]
      (let [ctx (support/fork-cluster-ctx connection)
            program-identity
            ((web-private 'debug-program-identity) @connection ctx)
            projection (sci.kernel/context-projection ctx)
            header ((web-private 'debug-header-html)
                    @connection
                    {:seon.render.debug/viewer-namespace 'my.viewer
                     :seon.render.debug/subject [:my/id "subject"]
                     :seon.render/output :seon.render/html
                     :seon.render.data/limit 40
                     :seon.render.data/max-ref-attributes 40
                     :seon.render.web/pull-max-work 40
                     :seon.render.web/pull-max-results 40
                     :seon.render.data/max-result-weight 4000}
                    {:seon.render.data/snapshot
                     (db/database-value-identity @connection)}
                    {:my/id "subject"}
                    1.0
                    program-identity)]
        (is (= (db/q '[:find ?digest .
                       :where [_ :seon.source/digest ?digest]]
                     @connection)
               (:seon.source/digest program-identity))
            "the header identity is the digest recorded by this cluster database")
        (is (= (:seon.schema.projection/fingerprint projection)
               (:seon.schema.projection/fingerprint program-identity))
            "the fingerprint comes from the handed SCI context projection")
        ;; HTML shows a string's own characters (its quotes are CSS), so the labels are compared as shown.
        (is (= {"indexed source digest" (:seon.source/digest program-identity)
                "schema projection fingerprint" (str (:seon.schema.projection/fingerprint program-identity))}
               (select-keys (labelled header) ["indexed source digest" "schema projection fingerprint"]))
            "the header shows the indexed source digest and the context projection fingerprint")
        (is (= {"HTML" "page" "AI" ""}
               (into {} (map (fn [^Element link] [(.text link) (.attr link "aria-current")]))
                     (.select (dom header) "nav[aria-label=Rendered output] a")))
            "HTML and AI are ordinary links, the selected output marked current")))))

(deftest debug-reference-links-select-another-entity-and-preserve-the-view
  (let [request {:seon.render.debug/viewer-namespace 'my.viewer
                 :seon.render.debug/subject [:my/id "before"]
                 :seon.render/output :seon.render/ai
                 :seon.render.data/limit 17
                 :seon.render.data/max-ref-attributes 19
                 :seon.render.data/max-result-weight 23
                 :seon.render.web/pull-max-work 29
                 :seon.render.data/cursor
                 {:seon.render.data/path [:old]
                  :seon.render.data/offset 7}
                 :seon.render.data/outgoing-cursor {:old :outgoing}
                 :seon.render.data/incoming-cursor {:old :incoming}}
        link (web-private 'debug-subject-link)
        subject-href (fn [subject] (href (get-in (link request subject subject) [1 :href])))
        preserved {"output" ":seon.render/ai" "limit" "17" "maxRefAttributes" "19"
                   "maxResultWeight" "23" "maxWork" "29"}]
    (is (= {20 {:path "/ns/my.viewer/debug" :query (assoc preserved "subject" "20")}
            [:seon.fn/sym "my.render/card"]
            {:path "/ns/my.viewer/debug"
             :query (assoc preserved "subject" "[:seon.fn/sym \"my.render/card\"]")}}
           (into {} (map (juxt identity subject-href)) [20 [:seon.fn/sym "my.render/card"]]))
        "a link selects its subject in the viewing namespace, keeps output and bounds, and carries no page cursor")))

(deftest declared-units-are-components-in-schema-order
  (support/with-database
   (fn [connection]
     (support/transacted! connection
                          (support/agent-tx @connection "unit-owner"))
     (is (= 1234 (:seon.config.eval/time-limit-ms
                  (agent/settings! {:seon.config.eval/time-limit-ms 1234}
                                   connection "unit-owner"))))
     (let [database @connection
           projection (schema/projection-from-database database)
           declared (web-private 'declared-entity-units)
           entity (db/pull database '[*]
                           [:seon.agent/id "unit-owner"])]
       (is (= [:seon.agent/id :seon.agent/branch :seon.agent/cluster :seon.agent/archived-tx
               :seon.agent/admission-closed-tx
               :seon.agent/namespace :seon.agent/settings
               :seon.agent/runtime
               :seon.message/inbound-content]
              (declared projection database entity))
           "declared attributes retain schema order before block grouping")
       (is (= [] (declared projection database "ordinary value")))))))

(deftest an-attribute-description-comes-from-the-projection-form
  ;; The description is read from the projection's retained compiled schema,
  ;; so the projection is admitted by its owner, never a hand-built forms map
  ;; that carries no registry.
  (support/with-database
   (fn [connection]
     (let [projection
           (reduce (fn [projection [schema-key definition]]
                     (schema/projection-with-schema
                      projection schema-key definition
                      {:seon.schema.admission/source :agent}))
                   (db/carried-projection (db/db connection))
                   [[:my/documented [:string {:description "What this attribute means."}]]
                    [:my/plain :string]])
           description (web-private 'attribute-description)]
       (is (= "What this attribute means."
              (description projection :my/documented))
           "the authored :description is read from the compiled schema")
       (is (= "What this attribute means."
              (description projection :my/_documented))
           "a reverse unit is described by its forward attribute")
       (is (nil? (description projection :my/plain))
           "an undeclared description is absent, never invented")))))

(deftest applicable-render-functions-list-alternatives-and-hide-rejections
  ;; A preview renders against a database value (a6ec14a8c).
  (support/with-database
   (fn [connection]
  (let [candidate (fn [producer status]
                    {:seon.render.selection.candidate/producer producer
                     :seon.render.selection.candidate/status status})
        experiment
        (fn [selected selected-output alternative]
          {:seon.render/selection
           {:seon.render.selection/selected selected
            :seon.render.selection/stages
            [{:seon.render.selection.stage/name :declared
              :seon.render.selection.stage/status :selected
              :seon.render.selection.stage/candidates
              [(candidate selected :compatible)]}
             {:seon.render.selection.stage/name :floor
              :seon.render.selection.stage/status :not-consulted
              :seon.render.selection.stage/candidates
              [(candidate alternative :compatible)
               (candidate 'my.render/rejected :rejected)]}]}
           :seon.render/previews {selected selected-output}
           :seon.render/entries {}})
        experiments
        {:seon.render/ai
         (experiment 'my.render/ai "selected AI" 'my.render/other-ai)
         :seon.render/html
         (experiment 'my.render/html [:p "selected HTML"]
                     'my.render/other-html)}
        previews
        (mapv #((web-private 'experiment-preview-html) % (get experiments %) (db/db connection))
              [:seon.render/ai :seon.render/html])
        renderers
        ((web-private 'applicable-renderers-html)
         (db/db connection) {:seon.render.debug/viewer-namespace 'my.viewer} experiments)
        links (fn [css-class]
                (->> (support/hiccup-elements renderers)
                     (filter #(= css-class (:class (second %))))
                     first support/hiccup-elements
                     (filter #(= :a (first %)))
                     (mapv #(apply str (support/hiccup-leaves %)))))]
    (is (= ["my.render/other-ai" "my.render/other-html"]
           (links "seon-debug-alternative-renderers"))
        "compatible alternatives are listed; a rejected candidate is absent")
    (is (= [[:pre "selected AI"] [:p "selected HTML"]] (mapv last previews))
        "each preview is exactly its selected render function's value, no alternative beside it")))))

(deftest selected-ai-experiment-reuses-the-canonical-executed-call
  ;; THE WORLD IS DATA: the viewing agent's own render function competes with
  ;; the floor for its agent entity, and counts its calls in its own SCI
  ;; context. The page already executed the selected call; the experiment
  ;; must reuse that output and render no candidate again.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "experiment")
     (support/transacted! connection (support/agent-tx @connection "juniper"))
     (let [ctx (support/fork-cluster-ctx connection)
           in-viewer #(sci/binding [sci/ns (sci/create-ns 'my.agents.juniper)] (sci/eval-form ctx %))
           source (str "(defn agent-view {:malli/schema [:=> [:cat [:map [:seon.agent/id :seon.agent/id]]] :string]} [unit] "
                       "(swap! calls inc) (str \"rendered \" (:seon.agent/id unit)))")
           _ (in-viewer '(def calls (atom 0)))
           _ (support/transacted! connection [(support/program-fn-entity (db/db connection) 'my.agents.juniper/agent-view source)])
           _ (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                                 :seon.schema/projection (sci.kernel/context-projection ctx)})
           _ (in-viewer (edn/read-string source))
           selected 'my.agents.juniper/agent-view
           executed "Agent \"juniper\"\nNamespace my.agents.juniper"
           selected-entry {:seon.render.call/producer selected}
           request {:seon.render/value (db/pull @connection [:seon.agent/id] [:seon.agent/id "juniper"])
                    :seon.render/namespace 'my.agents.juniper
                    :seon.render/retained-calls {}
                    :seon.render/captured-calls (atom {})
                    :seon.sci.eval/ctx ctx
                    :seon.db/db @connection
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.sci.admit/caps caps
                    :seon.render/profile (render/agent-render-profile config/defaults)}
           experiment ((web-private 'debug-render-experiment)
                       request :seon.render/ai ::subject
                       {:seon.render.call/producer selected
                        :seon.render.call/output executed
                        :seon.render.call/entry selected-entry})]
       (is (= '[my.agents.juniper/agent-view seon.render.value/render-ai]
              (mapv :seon.render.selection.candidate/producer
                    (mapcat :seon.render.selection.stage/candidates
                            (:seon.render.selection/stages (:seon.render/selection experiment)))))
           "the viewer's function is selected and the floor is its compatible alternative")
       (is (= {:previews {selected executed} :entries {selected selected-entry} :calls 0}
              {:previews (:seon.render/previews experiment)
               :entries (:seon.render/entries experiment)
               :calls @(in-viewer 'calls)})
           "the executed call is reused, and no candidate, the alternative included, is rendered")
       (is (= [:pre executed]
              (last ((web-private 'experiment-preview-html)
                     :seon.render/ai experiment (db/db connection))))
           "the paired AI preview presents multiline terminal text as itself, not its EDN")))))

(deftest debug-selected-renderer-metadata-is-outside-preview
  ;; A preview renders against a database value (a6ec14a8c).
  (support/with-database
   (fn [connection]
  (let [selected 'my.render/ai
        experiment
        {:seon.render/selection
         {:seon.render.selection/selected selected
          :seon.render.selection/stages
          [{:seon.render.selection.stage/name :declared
            :seon.render.selection.stage/status :selected
            :seon.render.selection.stage/candidates
            [{:seon.render.selection.candidate/producer selected
              :seon.render.selection.candidate/status :compatible}]}]}
         :seon.render/previews {selected "exact agent-visible text"}
         :seon.render/entries
         {selected
          {:seon.render.call/basis-transaction 42
           :seon.render.call/read-evidence []
           :seon.render.call/static-evidence
           {:seon.render.call/declaration-row
            {:seon.sci.eval/function-source "(defn ai [x] x)"}}}}}
        request {:seon.render.debug/viewer-namespace 'my.viewer
                 :seon.render.debug/details? true}
        preview ((web-private 'experiment-preview-html)
                 :seon.render/ai experiment (db/db connection))
        details ((web-private 'debug-selected-renderer-details)
                 (db/db connection) request :seon.render/ai experiment)
        classes support/hiccup-classes]
    (is (= [:pre "exact agent-visible text"] (last preview))
        "the AI preview is the renderer output alone: no renderer metadata inside it")
    (is (= #{"seon-debug-read-dependencies" "seon-debug-function-definition"}
           (into #{} (filter #{"seon-debug-read-dependencies" "seon-debug-function-definition"})
                 (classes details)))
        "the separate renderer disclosure retains its evidence")))))

(deftest inspecting-the-page-writes-no-run-evaluation-or-fault-facts
  ;; Ruling 2026-09-06: preview results live in the invocation cache. A person
  ;; reading the page is not an agent taking a turn, so repeated inspection
  ;; must leave the durable counts exactly where it found them.
  ;;
  ;; THE BASIS IS THE TOTAL MEASURE (PRD §8, ten loads write nothing). Counting
  ;; runs, evaluations and faults says nothing about a datom this page had no
  ;; name for — a render-cost fact, a committed render fault, a receipt from a
  ;; preview that settled itself. `:t` moves for any of them, so the ten page
  ;; loads below assert the basis transaction, and the named counters stay to
  ;; say which family moved when it does.
  (with-server
    (fn [connection server _context]
      (let [durable-counts
            (fn []
              (let [database @connection
                    total (fn [attribute]
                            (or (db/q {:query
                                       {:find ['(count ?e) '.]
                                        :in ['$ '?a]
                                        :where [['?e '?a]]}
                                       :args [database attribute]})
                                0))]
                {:runs (total :seon.turn/id)
                 :evaluations (total :seon.cluster.eval/id)
                 :forms (total :seon.cluster.eval/ordinal)
                 :faults (total :seon.error/id)}))
            subject (URLEncoder/encode
                     (pr-str [:seon.agent/id agent-id]) "UTF-8")
            feed-path (str "/feed/" agent-id "?debug=true"
                           "&viewer=my.agents.root"
                           "&subject=" subject
                           "&output=%3Aseon.render%2Fhtml")
            inspect! (fn []
                       (let [stream (open-feed server feed-path)]
                         (try (read-patches! stream 1)
                              (finally (.close stream)))))]
        (open-run! connection "inspection-counts")
        (is (:db-after (db/transact! connection
                      [{:seon.cluster.eval/id "inspection-counts-e0"
                        :seon.cluster.eval/at (java.util.Date. 0)
                        :seon.cluster.eval/run
                        [:seon.turn/id "inspection-counts"]
                        :seon.cluster.eval/ordinal 0
                        :seon.cluster.eval/source "(+ 1 1)"}])))
        (inspect!)
        (is (= 200 (.statusCode (fetch server "/agent/root/debug"))))
        (let [before (durable-counts)
              before-basis (db/basis-t @connection)]
          (dotimes [_ 10]
            (is (= 200 (.statusCode (fetch server "/agent/root/debug"))))
            (is (= 200 (.statusCode (fetch server "/agent/root/debug?sent=true" {"datastar-request" "true"}))))
            (inspect!))
          (is (= before (durable-counts))
              "repeated inspection creates no run, evaluation, form, or fault")
          (is (= before-basis (db/basis-t @connection))
              "ten loads of the debug page, its context view and its feed write no datom at all")
          (is (and (pos? (:runs before)) (pos? (:evaluations before)))
              "the counters see the facts an ordinary turn writes"))))))

(deftest debug-renderer-definition-uses-retained-program-source
  (let [render-definition (web-private 'debug-renderer-definition)
        entry {:seon.render.call/static-evidence
               {:seon.render.call/declaration-row
                {:seon.sci.eval/function-source
                 "(defn render-card [x] (<unsafe> x))"}}}
        present (render-definition entry)
        missing (render-definition
                 {:seon.render.call/static-evidence
                  {:seon.render.call/declaration-row {}}})]
    (is (= [:pre [:code "(defn render-card [x] (<unsafe> x))"]] (last present))
        "the stored declaration source is shown as code text, which the serializer escapes")
    (is (= "seon-debug-renderer-source-missing" (:class (second missing)))
        "an absent stored definition is explicit rather than blank or fabricated")))

(deftest debug-renderer-definition-accepts-the-acquired-program-entity
  (support/with-database
    (fn [connection]
      (let [ctx (support/fork-cluster-ctx connection)
            entity (sci.kernel/program-function ctx 'seon.render.ns/task-html)
            definition ((web-private 'debug-renderer-definition)
                        {:seon.render.call/static-evidence
                         {:seon.render.call/declaration-row entity}})]
        (is (string? (:seon.sci.eval/function-source entity))
            "the acquired kernel entity carries the normalized source key")
        (is (= [:pre [:code (:seon.sci.eval/function-source entity)]] (last definition))
            "the disclosure shows the actual acquired program entity's source")))))

(deftest debug-graph-model-preserves-datom-identity-and-direction
  (let [request {:seon.render.debug/viewer-namespace 'my.viewer
                 :seon.render.debug/subject 42
                 :seon.render/output :seon.render/html
                 :seon.render.data/outgoing-cursor {:old true}
                 :seon.render.data/incoming-cursor {:old true}}
        observation
        {:seon.render.data/eid 42
         :seon.render.data/snapshot
         {:db-name :fixture :t 10
          :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000010"}
         :seon.render.data/identities
         [{:e 42 :a :fixture/id :v "</script><selected>" :tx 90}]
         :seon.render.data/outgoing
         {:seon.render.data/datoms
          [{:e 42 :a :fixture/ref :v 7 :tx 100 :added true}
           {:e 42 :a :fixture/scalar :v "plain" :tx 100 :added true}
           {:e 42 :a :fixture/ref :v 42 :tx 102 :added true}]
          :seon.render.data/complete? false}
         :seon.render.data/incoming
         {:seon.render.data/datoms
          [{:e 9 :a :fixture/ref :v 42 :tx 101 :added true}
           {:e 8 :a :fixture/ref :v 42 :tx 103 :added true}]
          :seon.render.data/complete? true}}
        model ((web-private 'graph-model) request #{:fixture/ref} observation)
        nodes (get-in model [:elements :nodes])
        edges (get-in model [:elements :edges])
        html ((web-private 'debug-graph-html)
              request #{:fixture/ref} observation)]
    (is (= (pr-str [:fixture 42]) (get model "seon.graph/selected")))
    (is (= #{(pr-str [:fixture 42]) (pr-str [:fixture 7])
             (pr-str [:fixture 9]) (pr-str [:fixture 8])}
           (into #{} (map #(get-in % [:data :id])) nodes)))
    (is (= #{(pr-str [:fixture 42 :fixture/ref 7 100])
             (pr-str [:fixture 42 :fixture/ref 42 102])
             (pr-str [:fixture 9 :fixture/ref 42 101])
             (pr-str [:fixture 8 :fixture/ref 42 103])}
           (into #{} (map #(get-in % [:data :id])) edges)))
    (is (every? #(= ":fixture/ref" (get-in % [:data :attribute])) edges))
    (is (= "Loaded 4 reference assertions; outgoing partial, incoming complete."
           (.text (element html "[data-graph-status]")))
        "the status reports each direction's completeness")
    (is (= {:tag "div" :canvases 0}
           (let [container (element html "[data-graph-canvas]")]
             {:tag (.tagName container) :canvases (count (.select container "canvas"))}))
        "Cytoscape receives an ordinary container for its child canvases")
    (is (= "Select a reference assertion for details."
           (.text (element html "[data-graph-detail]")))
        "bounded acquisition status remains separate from interaction detail")
    (is (= {:model? true :injected []}
           {:model? (some? (element html "script[data-graph-model]"))
            :injected (vec (.select (dom html) "selected"))})
        "a value holding </script> stays inside the model script and never becomes markup")))

(deftest canonical-debug-feed-repaints-when-the-subject-changes
  (with-server
    (fn [connection server _context]
      (let [namespace-name 'seon.flow
            subject (java.net.URLEncoder/encode
                     (pr-str [:seon.ns/name namespace-name]) "UTF-8")
            stream (open-feed
                    server
                    (str "/feed/seon.flow?debug=true&viewer=seon.flow"
                         "&subject=" subject
                         "&output=%3Aseon.render%2Fhtml"))]
        (try
          (read-patches! stream 1)
          (support/transacted! connection
                               [{:seon.ns/name namespace-name
                                 :seon.ns/doc "debug-live-subject-marker"}])
          (is (shows? (read-until! stream "debug-live-subject-marker")
                      "debug-live-subject-marker")
              "the existing feed repaints from the new database value")
          (finally (.close stream)))))))

(deftest debug-data-reuses-read-evidence-when-the-database-is-unchanged
  (support/with-database
    (fn [connection]
      (let [request {:seon.render.debug/subject [:seon.ns/name 'seon.flow]
                     :seon.render.data/limit 40
                     :seon.render.data/max-ref-attributes 40
                     :seon.render.data/max-result-weight 4000
                     :seon.render.web/pull-max-work 4000
                     :seon.render.web/pull-max-results 4000}
            projection (sci.kernel/context-projection
                        (support/fork-cluster-ctx connection))
            ;; The observation's armed counter; no Var is redefined.
            [_ calls]
            (support/armed-calls
             '#{seon.render.data/entity-observation}
             #(let [first-result ((web-private 'acquire-debug-data)
                                  projection @connection request {})
                    retained {(:seon.render.web/debug-data-call-id first-result)
                              (:seon.render.web/debug-data-entry first-result)}]
                ((web-private 'acquire-debug-data)
                 projection @connection request retained)))]
        (is (= 1 (get calls 'seon.render.data/entity-observation))
            "unchanged debug reads reuse the retained observation")))))

(deftest an-unchanged-head-re-derives-no-block-and-an-input-change-re-derives-its-block
  ;; The page key is the program and the committed value, never the database
  ;; object the context snapshot last advanced to (census entity A2). `acquire!`
  ;; advances that object on every same-program acquisition. The page's own
  ;; agent: every fork of one cluster context shares its render cache, so a
  ;; live `root` page in the hosting JVM would share this page's entry.
  (with-server
    (fn [connection server context]
      (support/transacted! connection
                           (cluster.agent/creation-tx
                            {:seon.agent/id "page-key"
                             :seon.cluster/name "web-test"
                             :seon.ns/name 'my.agents.page-key}))
      ;; Read evidence checks and page derivations are read from their armed
      ;; counters; which blocks re-derived is read from the retained calls
      ;; themselves: a re-derived call has a new render basis, a retained one
      ;; keeps its basis (a checked call only advances its database value).
      (let [read-attributes (web-private 'read-attributes)
            calls #(get-in @(render/shared-cache (:ctx context)) [::web/calls "page-key"] {})
            observe (fn []
                      (let [before (calls)
                            [response counted]
                            (support/armed-calls '#{seon.db/read-evidence-current? seon.render.web/page-result}
                                                 #(fetch server "/agent/page-key"))
                            after (calls)
                            ;; block calls only: the root acquisition is refreshed
                            ;; by its own check (`refresh-root`), not as a block
                            re-derived (filter (fn [[id entry]]
                                                 (and (contains? before id)
                                                      (not= ::web/root-acquisition (first id))
                                                      (not= (:seon.render.call/basis-transaction entry)
                                                            (:seon.render.call/basis-transaction (get before id)))))
                                               after)]
                        (is (= 200 (.statusCode response)))
                        {:evidence (get counted 'seon.db/read-evidence-current?)
                         :derivations (get counted 'seon.render.web/page-result)
                         :re-derived (count re-derived)
                         ;; re-derived blocks whose read evidence names its
                         ;; attributes (not a whole-index `:all` read)
                         :attribute-bound (count (remove #(= :all (read-attributes (val %))) re-derived))
                         ;; retained calls the acquisition kept
                         :retained (count before)}))
            acquire! #(sci.eval/acquire! {:seon.sci.eval/ctx (:ctx context)
                                          :seon.db/db @connection})]
          (acquire!)
          (is (= 1 (:derivations (observe))) "the first GET derives the page")
          (is (= {:evidence 0 :derivations 0 :re-derived 0 :attribute-bound 0}
                 (dissoc (observe) :retained))
              "an unchanged head asks no read evidence and derives no block")
          (support/transacted!
           connection
           ;; A data entity (not program: a namespace edit re-acquires the program)
           ;; that no root page block reads.
           [{:seon.cluster/name "render.web.page-key-unrelated"
             :seon.cluster/config
             (:db/id (:seon.cluster/config
                      (db/pull @connection [{:seon.cluster/config [:db/id]}]
                               [:seon.cluster/name "web-test"])))}])
          (acquire!)
          (let [unrelated (observe)]
            (is (pos? (:evidence unrelated))
                "a new commit checks the retained evidence instead of discarding it")
            ;; A whole-index read (`seon.db/index-page` captures `:all`) is
            ;; a candidate after any commit; every attribute-bound block stays.
            (is (pos? (:retained unrelated))
                "the acquisition keeps the program key, so the retained calls survive")
            (is (= 0 (:attribute-bound unrelated))
                "an acquisition after an unrelated write keeps every attribute-bound block")
            (is (< (:re-derived unrelated)
                   (count (get-in @(render/shared-cache (:ctx context))
                                  [::web/calls "page-key"])))
                "an unrelated write re-derives only whole-index reads"))
          (is (= {:evidence 0 :derivations 0 :re-derived 0 :attribute-bound 0}
                 (dissoc (observe) :retained))
              "the checked commit is itself an unchanged head")
          (support/transacted!
           connection
           [{:seon.ns/name 'my.agents.page-key :seon.ns/doc "page-key-marker"}])
          (let [changed (observe)]
            (is (= 1 (:derivations changed)) "a block input change derives the page")
            (is (pos? (:attribute-bound changed))
                "the block whose read attribute changed is a candidate")
            (is (< (:re-derived changed) (count (get-in @(render/shared-cache (:ctx context))
                                                        [::web/calls "page-key"])))
                "only the changed blocks re-derive; the rest stay retained"))))))

(defn- disclosure
  "A read-dependency disclosure as data: its summary line, its basis line and
  the value-printer panels it shows for its evidence."
  {:malli/schema [:=> [:cat :seon.schema/value] [:map [:summary :string] [:basis :seon.schema/value] [:evidence [:vector :seon.schema/value]] [:notes [:vector :string]]]]}
  [rendered]
  (let [shown (fn [tag] (->> (support/hiccup-elements rendered) (filter #(= tag (first %)))))]
    {:summary (apply str (support/hiccup-leaves (first (shown :summary))))
     :basis (some #(when (= "seon-debug-read-basis" (:class (second %))) (apply str (support/hiccup-leaves %)))
                  (support/hiccup-elements rendered))
     :evidence (filterv #(= "seon-data-panel" (:class (second %))) (shown :div))
     :notes (mapv #(apply str (support/hiccup-leaves %)) (remove #(map? (second %)) (shown :p)))}))

(deftest debug-compatible-candidate-discloses-retained-read-dependencies
  (support/with-database
   (fn [connection]
  (let [database @connection
        evidence {:seon.db/source-argument-position 0
              :datahike.read/dependency-plan
              {:datahike.query.dependency/sources
               [{:datahike.query.source/symbol '$
                 :datahike.query.source/argument-position 0
                 :datahike.query.source/attributes #{:seon.task/intent}}]}
              :datahike.read/revision
              {:datahike.read/attributes #{:seon.task/intent}
               :datahike.read/cache-eligible? false}}
        rendered ((web-private 'debug-read-dependencies-html)
                  database
                  {:seon.render.call/basis-transaction 42
                   :seon.render.call/read-evidence [evidence]})]
    (is (= {:summary "database read dependencies · 1 evidence entry"
            :basis "render basis transaction 42"
            :evidence [(value/render-html {:seon.render/value evidence :seon.db/db database
                                           :seon.render.value/root [::web/read-evidence 0]})]}
           (select-keys (disclosure rendered) [:summary :basis :evidence]))
        "the disclosure shows the render basis and each retained evidence entry through the value printer")))))

(deftest a-debug-value-shows-through-the-value-printer-within-its-budget
  ;; Class regression (print-site audit 2026-10-07): the debug page printed
  ;; a value whole with pr-str, so a 10 MB value became a 10 MB page.
  (support/with-database
   (fn [connection]
     (let [value (vec (repeat 100000 (apply str (repeat 100 "x"))))
           shown (value/html {:seon.render/value value :seon.db/db @connection :seon.render.value/root [::web/raw-data]})
           page (hiccup/->string shown)]
       (is (= "seon-data-panel" (:class (second shown))) "the value printer's panel is the shown value")
       (is (< (count page) 100000) "the page holds the printer's budget, never the 10 MB value")))))

(deftest debug-read-dependencies-distinguishes-empty-from-absent
  (let [none ((web-private 'debug-read-dependencies-html)
               nil
               {:seon.render.call/basis-transaction 7
                :seon.render.call/read-evidence []})
        absent ((web-private 'debug-read-dependencies-html) nil {})]
    (is (= [{:summary "database read dependencies · 0 evidence entries"
             :notes ["No database read evidence was retained."]}
            {:summary "database read dependencies unavailable"
             :notes ["Read evidence is absent from this retained render entry."]}]
           (mapv #(select-keys (disclosure %) [:summary :notes]) [none absent])))))

(deftest unrelated-transaction-reuses-debug-observation-and-render-call
  (with-server
    (fn [connection server context]
      ;; The armed counters of observation, discovery and invocation, read
      ;; from the feed's opening; no Var is redefined.
      (let [counted '#{seon.render.data/entity-observation seon.render/selection seon.sci.kernel/invoke}
            opening (support/armed-counts counted)
            counts #(let [now (merge-with - (support/armed-counts counted) opening)]
                      {:observation (get now 'seon.render.data/entity-observation)
                       :discovery (get now 'seon.render/selection)
                       :invocation (get now 'seon.sci.kernel/invoke)})
            subject (URLEncoder/encode
                     (pr-str [:seon.ns/name 'seon.flow]) "UTF-8")
            stream (open-feed
                    server
                    (str "/feed/seon.flow?debug=true&viewer=seon.flow"
                         "&subject=" subject
                         "&output=%3Aseon.render%2Fhtml"))]
        (try
          (read-patches! stream 1)
          (let [before (counts)
                pass-before (derivations context)]
            (is (= 1 (:observation before)))
            (is (pos? (:discovery before))
                "the initial page selects a render function for its units")
            ;; Invocations are content-keyed in the cache every fork of
            ;; this cluster context shares, so a JVM that rendered this
            ;; subject before answers the initial comparison from it.
            ;; A data entity NO acquisition derives interest in: no identity,
            ;; no listened attribute, no join. A bystander agent entity mints
            ;; a half-agent beside the real creation path (peer issue
            ;; an-unrelated-fixture-transaction-mints-a-half-agent).
            (support/transacted!
             connection
             [{:seon.cluster/name "render.web.unrelated"
               :seon.cluster/config
               (:db/id (:seon.cluster/config
                        (db/pull @connection [{:seon.cluster/config [:db/id]}]
                                 [:seon.cluster/name "web-test"])))}])
            (is (< pass-before (settle-render! context)))
            (is (= before (counts))
                "the database wake reuses observation, discovery, and invocation")
            (let [pass-after-unrelated (derivations context)]
              (let [marker (str "debug-cache-relevant-" (random-uuid))]
                (support/transacted!
                 connection
                 [{:seon.ns/name 'seon.flow
                   :seon.ns/doc marker}])
                (is (< pass-after-unrelated (settle-render! context)))
                (is (str/includes? (read-until! stream marker) marker)
                    "a selected-data change repaints the page with the changed data")
                (is (> (:observation (counts)) (:observation before))
                    "a selected-data change observes the entity again"))))
          (finally (.close stream)))))))

(deftest runtime-evaluation-cannot-be-displaced-by-a-database-wake
  ;; The page's derivations are read from page-result's armed counter; no Var
  ;; is redefined. A code signal and a database wake offered together derive
  ;; the page once for the code signal: the wake's newest-value buffer cannot
  ;; replace the pending code signal.
  (with-server
    (fn [connection server context]
      (let [stream (open-feed server (str "/feed/" agent-id))
            derived #(get (support/armed-counts '#{seon.render.web/page-result})
                          'seon.render.web/page-result)]
        (try
          (read-complete-paint! stream connection)
          (doseq [_ [1 2]]
            (let [before (derived)
                  passes (derivations context)]
              (is (async/offer! (:runtime-eval-channel context)
                                :seon.render.web/runtime-eval))
              (is (async/offer! (:render-channel context)
                                :seon.cluster.wake/render))
              (await-ping! context #(< passes (:seon.render.web/passes %)) [:runtime-eval-pass])
              (is (pos? (- (derived) before))
                  "the code signal re-derives the page though the database is unchanged")))
          (finally (.close stream)))))))

(declare throwing-render-state)

(deftest a-code-event-drops-an-unwatched-pages-package
  ;; THE STATE IS A VALUE: one watched page and one closed page, each with a
  ;; retained package and calls. A runtime evaluation keeps the watched page's
  ;; package as its repaint predecessor and drops the closed page's, so a
  ;; reopen derives current code; every code-derived call is dropped.
  (support/with-database
    (fn [connection]
      (let [state (throwing-render-state connection)
            cache (render/shared-cache (get-in state [:seon.turn.loop/cluster :seon.sci.eval/ctx]))
            closed [::web/debug-tab {:seon.render.debug/viewer-namespace 'my.task}]]
        (swap! cache assoc
               ::web/packages {"agent-a" {:seon.render.package/revision 1}
                               closed {:seon.render.package/revision 1}}
               ::web/calls {"agent-a" {:call :a} closed {:call :b}})
        (#'web/invalidate-runtime-derived-state state)
        (is (= {:packages #{"agent-a"} :calls nil}
               {:packages (set (keys (::web/packages @cache)))
                :calls (::web/calls @cache)})
            "the watched page keeps its package, the closed page's is dropped, and no call survives")))))

;;; ---------------------------------------------------------------------------
;;; The wire — the rung's claim
;;; ---------------------------------------------------------------------------

;; DELETED 2026-08-29 (owner gate ruling): the wire/initial-paint/
;; thinking-stream transcript machinery is wave-G territory (rip-out
;; register #16-17, #19; rulings 52-53 rebuild the seam) — tests
;; asserting the doomed mechanism are parked here, replaced by wave
;; G's own acceptance, not polished.

(deftest a-feed-writer-failure-enters-the-cluster-fault-path
  ;; A socket whose frame write throws: the external effect, handed as a value.
  (with-server
    {:seon.render.web/http
     {:seon.render.web/send! (fn [channel content close-after-send?]
                               (if (bytes? content)
                                 (throw (ex-info "injected writer failure" {}))
                                 (http/send! channel content close-after-send?)))}}
    (fn [connection server _context]
        (let [stream (open-feed server (str "/feed/" agent-id))
              ;; the one fault route: the failure is a recorded fault fact
              feed-faults (fn [database]
                            (db/q '[:find [(pull ?e [:seon.error/layer :seon.error/operation]) ...]
                                    :where [?e :seon.error/layer :seon.render.web/feed]]
                                  database))]
          (try
            (is (= [{:seon.error/layer :seon.render.web/feed
                     :seon.error/operation 'seon.render.web/feed-response}]
                   (feed-faults (support/await-event! connection [:feed-writer-fault]
                                                      #(seq (feed-faults %)))))
                "the feed writer's failure is recorded as its entrance's fault")
            (finally (.close stream)))))))

(deftest reconnect-is-repaint
  ;; Nothing rendered is stored, so a new connection derives the current
  ;; page from current facts — which is also what makes a killed process
  ;; cost nothing.
  (with-server
    (fn [connection server _context]
      (let [first-stream (open-feed server (str "/feed/" agent-id))]
        (read-complete-paint! first-stream connection)
        (.close first-stream))
      (support/transacted! connection
                         [{:seon.ns/name 'my.agents.root
                           :seon.ns/source "(ns my.agents.root)\n(def current true)"}])
      (let [second-stream (open-feed server (str "/feed/" agent-id))]
        (try
          (let [repaint (read-complete-paint! second-stream connection)]
            (is (shows? repaint "def current true")
                "at the CURRENT basis, not the one the first tab saw"))
          (finally (.close second-stream)))))))

(deftest two-tabs-each-get-their-own-complete-paint
  ;; The socket records each frame it sends: the external effect, as a value.
  (let [sent (atom [])]
  (with-server
    {:seon.render.web/http
     {:seon.render.web/send! (fn [channel content close-after-send?]
                               (when (bytes? content) (swap! sent conj content))
                               (http/send! channel content close-after-send?))}}
    (fn [_connection server context]
      ;; The document join settles one current fact-only package first.
      (is (= 200 (.statusCode (fetch server "/"))))
      (reset! sent [])
      (let [before (derivations context)
            a (open-feed server (str "/feed/" agent-id))
            b (open-feed server (str "/feed/" agent-id))]
            (try
              (let [to-a (read-patches! a 1)
                    to-b (read-patches! b 1)]
                (is (= to-a to-b)
                    "each tab receives the same complete keyframe event")
                (is (= 2 (count @sent))
                    ;; diagnosis (shrink-web 2026-09-26): which fragments each
                    ;; send carried
)
                (is (every? #(java.util.Arrays/equals ^bytes (first @sent) ^bytes %) @sent)
                    "each caller paints equivalent current bytes")
                (is (= before (derivations context))
                    "joining a current package performs no render pass"))
              (finally (.close a) (.close b))))))))

;;; ---------------------------------------------------------------------------
;;; The F2 sealed suite — seeds 2026072822, 2026072823, 2026072824, 2026072828
;;; ---------------------------------------------------------------------------

;;; 2. render-proc-one-derivation-many-tabs-test — seed 2026072822

(deftest render-proc-publishes-the-same-package-to-every-tab
  (with-server
    (fn [connection server _context]
      (let [tabs (mapv (fn [_] (open-feed server (str "/feed/" agent-id)))
                       (range 4))]
        (try
          (doseq [tab tabs] (read-complete-paint! tab connection))
          (support/transacted! connection
                               [{:seon.ns/name 'my.agents.root
                                 :seon.ns/source "(ns my.agents.root)\n(def shared true)"}])
          (let [morphs (mapv #(read-until! % "def shared true") tabs)]
              (is (every? seq morphs))
              (is (= 1 (count (distinct morphs)))
                  "byte-identical across every tab, because one
                   published package reaches every tab"))
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
  (with-server
    (fn [connection server context]
      (let [slow (async/chan (async/sliding-buffer 1))
            fast (open-feed server (str "/feed/" agent-id))]
        (async/tap (:pages-mult context) slow)
        (try
          (read-complete-paint! fast connection)
          ;; The socket's on-open wake is not one of the K commits.
          ;; Fence it on the render proc's own input and use the exact
          ;; pass count returned by that completed derivation.
          (let [before (settle-render! context)
                k 5]
            ;; nobody reads `slow` for the whole burst
            (doseq [n (range k)]
              (support/transacted! connection
                                 [{:seon.ns/name 'my.agents.root
                                   :seon.ns/source
                                   (str "(ns my.agents.root)\n(def slow " n ")")}])
              ;; the fast sibling proves the proc kept passing while the
              ;; slow tap stayed full — never parked, never blocked
              (read-until! fast (str "def slow " n)))
            (let [pending (support/await-event! slow [:slow-tap-newest])
                  package (get pending agent-id)
                  keyframe (:seon.render.package/keyframe package)]
              (is (some? package) "the slow tap yielded a package")
              (is (seq keyframe)
                  "a COMPLETE keyframe — every retained block present")
              (is (identical? package
                              (get @(:latest-packages context) agent-id))
                  "joins reuse the proc-owned immutable package")
              (is (nil? (async/poll! slow))
                  "exactly ONE value was pending, newest-wins")
              (let [passes (- (derivations context) before)]
                (is (<= passes k)
                    "coalescing means passes never exceed commits")
                (is (pos? passes) "and the proc did keep deriving"))))
          (finally
            (async/untap (:pages-mult context) slow)
            (.close fast)))))))

(deftest failed-ephemeral-bind-preserves-the-bind-failure
  (support/with-database
    (fn [connection]
      (let [pages-channel (async/chan (async/sliding-buffer 1))
            render-channel (async/chan (async/sliding-buffer 1))
            fault-channel (async/chan (async/dropping-buffer 1))
            ;; A server binding that fails, handed as the socket leaf's value.
            failure
              (try
                (web/start!
                 (service-request connection
                 {:seon.render.web/http
                  {:seon.render.web/run-server
                   (fn [_handler _options]
                     (throw (BindException. "injected ephemeral bind failure")))}
                  :seon.store/connection-object connection
                  :seon.agent/id agent-id
                  :seon.sci.admit/caps caps
                  :seon.db.process/id process
                  :seon.render.web/pages-mult (async/mult pages-channel)
                  :seon.render.web/registration (atom {})
                  :seon.render.web/latest-packages (atom {})
                  :seon.render.web/render-channel render-channel
                  :seon.render.web/fault-channel fault-channel
                  :seon.render.web/port 0}))
                nil
                (catch Throwable thrown thrown))]
        (try
          (is (instance? clojure.lang.ExceptionInfo failure))
          (is (= 0 (:seon.render.web/attempted-port (ex-data failure))))
          (is (instance? BindException (ex-cause failure)))
          (is (not (instance? NullPointerException failure)))
          (finally
            (async/close! pages-channel)
            (async/close! render-channel)
            (async/close! fault-channel)))))))

(deftest a-reconnect-refuses-a-pass-derived-before-it-connected
  ;; THE CLASS: a tab taps the mult and then paints the FIRST package it
  ;; sees. A pass already in flight when it tapped was derived at an
  ;; EARLIER database value, and its publication reaches the fresh tap
  ;; before the answer to this tab's own join request — so reconnect
  ;; painted a superseded page and stayed there until the next change.
  ;; It failed 3 of 6 scripted reconnects on 2026-08-07, and it is what
  ;; made `reconnect-is-repaint` and its wire twin red at random.
  ;;
  ;; The construction is deterministic rather than hopeful: the pass is
  ;; HELD inside its own derivation while a newer fact commits, so the
  ;; stale publication is guaranteed to be what arrives first.
  ;;
  ;; THE HOLD IS AGENT CODE IN THIS MEMBER'S OWN WORLD, not a redefinition:
  ;; the viewing agent's namespace (my.agents.root) gets a render function
  ;; for its namespace entity that blocks, on the first derivation of the
  ;; superseded source, on a promise defined beside it in the same SCI
  ;; context the render proc renders in. The test reaches that promise by
  ;; evaluating in the same context; the branch and its context die with
  ;; the member.
  (with-server
    (fn [connection server context]
      (let [ctx (:ctx context)
            source (str "(defn ns-view {:malli/schema [:=> [:cat [:map [:seon.ns/name :seon.ns/name] [:seon.ns/source :string]]] :seon.render/hiccup]} [unit] "
                        "(when (and (clojure.string/includes? (:seon.ns/source unit) \"superseded\") (compare-and-set! hold-first? true false)) "
                        "(deliver pass-entered true) (deref pass-released 20000 nil)) "
                        "[:pre (:seon.ns/source unit)])")
            in-root #(sci/binding [sci/ns (sci/create-ns 'my.agents.root)] (sci/eval-form ctx %))]
        (in-root '(do (def hold-first? (atom true))
                      (def pass-entered (promise))
                      (def pass-released (promise))))
        ;; the renderer is a program entity (the namespace stage selects the
        ;; viewer's public contracted functions from the acquired program)
        ;; and the same form in this context, closing over the promises
        (support/transacted! connection
                             [(support/program-fn-entity (db/db connection) 'my.agents.root/ns-view source)])
        (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                            :seon.schema/projection (sci.kernel/context-projection ctx)})
        (in-root (edn/read-string source))
        ;; one tab already watching, so a commit always costs a real pass
        (let [watching (open-feed server (str "/feed/" agent-id))]
          (try
            (read-complete-paint! watching connection)
            (support/transacted! connection
                                 [{:seon.ns/name 'my.agents.root
                                   :seon.ns/source
                                   "(ns my.agents.root)\n(def superseded true)"}])
            (is (true? (deref (in-root 'pass-entered) (* 1000 support/event-backstop-seconds) nil))
                "the pass deriving the superseded source is held in its renderer")
            ;; the held pass can no longer describe the facts
            (support/transacted! connection
                                 [{:seon.ns/name 'my.agents.root
                                   :seon.ns/source
                                   "(ns my.agents.root)\n(def connected true)"}])
            (let [fresh (open-feed server (str "/feed/" agent-id))]
              (try
                (in-root '(deliver pass-released true))
                (let [paint (read-complete-paint! fresh connection)]
                  (is (shows? paint "def connected true")
                      "the initial paint is derived at or after the basis
                       this tab connected at")
                  (is (not (shows? paint "def superseded true"))
                      "the in-flight pass that published first was refused"))
                (finally (.close fresh))))
            (finally
              (in-root '(deliver pass-released true))
              (.close watching))))))))

;;; 4. reconnect-is-repaint-wire-test — seed 2026072824

(deftest reconnect-is-repaint-wire-test
  ;; ORACLE: the in-process kill projection — drop the taps and the
  ;; channel contents mid-flight, then reopen the feed. The initial
  ;; paint derives EVERY block from current facts. The database holds no
  ;; partial text at ANY basis (an as-of walk over the window), and
  ;; nothing is retracted because nothing was ever written: after F2 no
  ;; partial entity CAN exist, so the stale-partial repair class is
  ;; unrepresentable rather than handled.
  (with-server
    (fn [connection server context]
      ;; a tab, a commit it never sees, and then the socket is gone —
      ;; the kill projection, minus killing the JVM (F4 owns that)
      (let [doomed (open-feed server (str "/feed/" agent-id))]
        (read-complete-paint! doomed connection)
        (.close doomed))
      ;; a snapshot in flight on the stream conn, dropped on the floor
      (async/offer! (:stream-channel context)
                    {:seon.agent/id agent-id
                     :seon.ai/partial {:seon.ai/text "half a re"
                                       :seon.ai/tokens 3}})
      (async/poll! (:stream-channel context))
      (support/transacted! connection
                         [{:seon.ns/name 'my.agents.root
                           :seon.ns/source
                           "(ns my.agents.root)\n(def after-drop true)"}])
      (let [fresh (open-feed server (str "/feed/" agent-id))]
        (try
          (let [repaint (read-complete-paint! fresh connection)]
            (is (shows? repaint "def after-drop true")
                "at the CURRENT basis — reconnect is repaint, and the
                 in-flight partial was superseded, never replayed"))
          (finally (.close fresh))))
      (testing "and no partial text exists at ANY basis in the window"
        (let [db @connection
              stream-attributes
              (db/q '[:find [?ident ...]
                     :where [_ :db/ident ?ident]
                     [(namespace ?ident) ?ns]
                     [(clojure.string/starts-with? ?ns "seon.ai.stream")]]
                   db)]
          (is (empty? stream-attributes)
              "the attribute family is GONE from the registry, so a
               partial row is unrepresentable — nothing to retract,
               nothing to mistake for a settled reply")
          ;; the family is not in the registry, so no datom of it can exist at any basis
          )))))

;;; Seal revision, 2026-07-29 — terminal facts supersede partials

(deftest a-terminal-fact-supersedes-a-partial-after-the-lost-clear-ordering
  ;; The audit's falsifier, driven through the real render proc and a
  ;; real socket. A has painted a partial. B's partial then occupies the
  ;; ONE sliding-1 conn at the point where the deleted design offered
  ;; A's clear. There is no clear now: A's frozen-plan fact commits,
  ;; its ordinary interest wake repaints, and the database presence gate
  ;; removes A's temporary text whatever B did on the stream conn.
  (with-server
    (fn [connection server context]
      (let [run-a "stream-run-a"
            run-b "stream-run-b"]
        (open-run! connection run-a)
        (support/transacted! connection
                           (into (support/agent-tx @connection "agent-b")
                             [{:seon.turn/id run-b :seon.turn/agent [:seon.agent/id "agent-b"] :seon.turn/opened-tx "datomic.tx"}]))
        (let [tab (open-feed server (str "/feed/" agent-id))]
        (try
          (is (= {} (stream-strip (read-complete-paint! tab connection)))
              "the fact-only initial paint has no partial")
          (await-ping! context
                       #(= 1 (:seon.render.web/watched-agents %))
                       [:initial-fact-paint-derived])

          (async/offer! (:stream-channel context)
                        {:seon.agent/id agent-id
                         :seon.turn/id run-a
                         :seon.ai/partial {:seon.ai/text "A half reply"
                                           :seon.ai/tokens 3}})
          (is (= {:text "A half reply" :tokens 3}
                 (stream-strip (read-until! tab "A half reply")))
              "the partial is painted with its token count")

          ;; This is the displacing value from the audit ordering. It
          ;; changes B's transient entry but cannot carry semantics for A.
          (async/offer! (:stream-channel context)
                        {:seon.agent/id "agent-b"
                         :seon.turn/id run-b
                         :seon.ai/partial {:seon.ai/text "B newest"
                                           :seon.ai/tokens 2}})
          (await-ping! context
                       #(= 2 (:seon.render.web/streaming-agents %))
                       [:both-partials-admitted])

          ;; The frozen plan is the settled provider reply fact. Its
          ;; normal database wake is the stream terminal.
          (is (:db-after (db/transact! connection
                      [[:db/add [:seon.turn/id run-a]
                        :seon.turn/reply-size
                        64]])))
          (await-ping! context
                       #(zero? (:seon.render.web/streaming-agents %))
                       [:terminal-fact-cleared-partials])
          (let [settled (read-until! tab "id=\"surface-stream\"></div>")]
            (is (= {} (stream-strip settled))
                "A's stale half-reply cannot survive the terminal fact"))
          (is (= 0 (streaming-agents context))
              "the fact-only interest pass retained no channel state")

          (testing "a delayed partial cannot repaint over its terminal fact"
            (let [before (derivations context)]
              (async/offer! (:stream-channel context)
                            {:seon.agent/id agent-id
                             :seon.turn/id run-a
                             :seon.ai/partial {:seon.ai/text "too late"
                                               :seon.ai/tokens 99}})
              (await-ping! context
                           #(> (:seon.render.web/passes %) before)
                           [:delayed-partial-considered])
              (is (= 0 (streaming-agents context))
                  "the run-id presence gate rejected the delayed partial")))
          (finally
            (.close tab))))))))

(deftest reconnect-mid-stream-is-a-fact-only-repaint
  ;; Partials are channel values, never facts. A new socket therefore
  ;; paints the current database value or nothing; it cannot restore the
  ;; old socket's last partial from the render proc's disposable memory.
  (with-server
    (fn [connection server context]
      (let [run-id "stream-reconnect"]
        (open-run! connection run-id)
        (let [first-tab (open-feed server (str "/feed/" agent-id))]
          (read-complete-paint! first-tab connection)
          (await-ping! context
                       #(= 1 (:seon.render.web/watched-agents %))
                       [:first-tab-derived])
          (async/offer! (:stream-channel context)
                        {:seon.agent/id agent-id
                         :seon.turn/id run-id
                         :seon.ai/partial {:seon.ai/text "not durable"
                                           :seon.ai/tokens 2}})
          (is (= {:text "not durable" :tokens 2}
                 (stream-strip (read-until! first-tab "not durable"))))
          (.close first-tab))
        (let [reconnected (open-feed server (str "/feed/" agent-id))]
          (try
            (let [repaint (read-complete-paint! reconnected connection)]
              (is (= {} (stream-strip repaint))
                  "reconnect repainted the fact-only stream strip: the in-flight partial was never restored"))
            (await-ping! context
                         #(zero? (:seon.render.web/streaming-agents %))
                         [:reconnect-dropped-partials])
            (finally
              (.close reconnected))))))))

;;; 8. coalesce-floor-one-derivation-test — seed 2026072828

(deftest coalesce-floor-one-derivation-test
  ;; ORACLE: M commits inside one floor window cost ONE derivation pass,
  ;; and each tab receives at most one morph per actually-changed block.
  ;; The floor is read from the CONFIG FACT planted per trial, honoured
  ;; at the proc, so a burst costs one derivation for the whole cluster
  ;; instead of one per tab. It remains a coalescing floor over an
  ;; observed event — the commit — never a poll.
  (with-server
    (fn [connection server context]
      ;; the dial as a fact, the way production ships it
      (support/apply-config! connection "web-test"
                             {:seon.config.render/coalesce-ms 250})
      (let [tab (open-feed server (str "/feed/" agent-id))]
        (try
          (read-complete-paint! tab connection)
          ;; the dial's own commit changes no block, so suppression
          ;; correctly puts NOTHING on the wire for it — the tab is
          ;; already settled
          (let [before (derivations context)
                m 6]
            (doseq [n (range m)]
              (support/transacted! connection
                                 [{:seon.ns/name 'my.agents.root
                                   :seon.ns/source
                                   (str "(ns my.agents.root)\n(def burst " n ")")}]))
            (let [settled (read-until! tab "def burst 5")]
              (is (< (patches settled) m)
                  "the burst of commits coalesces into fewer repaints"))
            (let [passes (- (derivations context) before)]
              (is (< passes m)
                  "the floor coalesces commits into fewer derivations for the whole cluster")))
          (finally (.close tab)))))))

(deftest the-pass-oracle-awaits-a-held-derivation
  ;; THE HOLD IS AGENT CODE: the page agent's render function for its
  ;; namespace entity blocks, on the first render of the observed source, on
  ;; a promise beside it in the same SCI context the render proc renders in.
  (with-server
    (fn [connection server context]
      (let [ctx (:ctx context)
            source (str "(defn ns-view {:malli/schema [:=> [:cat [:map [:seon.ns/name :seon.ns/name] [:seon.ns/source :string]]] :seon.render/hiccup]} [unit] "
                        "(when (and (clojure.string/includes? (:seon.ns/source unit) \"observed\") (compare-and-set! hold-first? true false)) "
                        "(deliver pass-entered true) (deref pass-released 20000 nil)) "
                        "[:pre (:seon.ns/source unit)])")
            in-root #(sci/binding [sci/ns (sci/create-ns 'my.agents.root)] (sci/eval-form ctx %))
            _ (in-root '(do (def hold-first? (atom true))
                            (def pass-entered (promise))
                            (def pass-released (promise))))
            _ (support/transacted! connection
                                   [(support/program-fn-entity (db/db connection) 'my.agents.root/ns-view source)])
            _ (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                                  :seon.schema/projection (sci.kernel/context-projection ctx)})
            _ (in-root (edn/read-string source))
            tab (open-feed server (str "/feed/" agent-id))]
        (try
          (read-complete-paint! tab connection)
          (let [before (derivations context)]
            (support/transacted! connection
                                 [{:seon.ns/name 'my.agents.root
                                   :seon.ns/source
                                   "(ns my.agents.root)\n(def observed true)"}])
            (is (true? (deref (in-root 'pass-entered) (* 1000 support/event-backstop-seconds) nil))
                "the pass is held inside its derivation")
            (let [observation (future (derivations context))]
              (in-root '(deliver pass-released true))
              (is (< before (support/await-event! observation ::derivation-observed))
                  "the oracle answers after the held pass completes")))
          (finally
            (in-root '(deliver pass-released true))
            (.close tab)))))))

;;; ---------------------------------------------------------------------------
;;; Suppression, as a pure unit
;;; ---------------------------------------------------------------------------

(deftest data-uses-the-one-floor-and-keeps-the-cursor-in-the-url
  ;; A drilled position is a LINK, so the proof is that following one
  ;; lands somewhere different from the root.
  (with-server
    (fn [_connection server _context]
      ;; The projection's consultations are read from its armed counter.
      (let [[response consulted]
            (support/armed-calls '#{seon.sci.kernel/context-projection}
                                 #(fetch server "/data"))
            root (.body response)]
        (is (= 200 (.statusCode response)))
        (is (= "[]" (some-> (element root ".seon-data-panel [data-seon-path]")
                            (.attr "data-seon-path")))
            "the panel shows the floor's root")
        (is (pos? (get consulted 'seon.sci.kernel/context-projection))
            "recursive producer selection consults the SCI context"))
      (testing "a stale or mangled cursor shows the root rather than failing"
        (let [response (fetch server "/data?path=%7Bbroken&offset=nope")]
          (is (= 200 (.statusCode response)))
          (is (= "[]" (some-> (element (.body response) ".seon-data-panel [data-seon-path]")
                              (.attr "data-seon-path")))))))))

(deftest data-resolves-an-entity-root-and-preserves-it-in-floor-links
  (with-server
    (fn [connection server _context]
      (support/transacted! connection
                         (support/agent-tx @connection "alice"))
      (let [response
            (fetch server
                   "/data?entity=%5B%3Aseon.agent%2Fid+%22alice%22%5D&path=%5B%5D&offset=0")
            body (.body response)
            default-body (.body (fetch server "/data"))]
        (is (= 200 (.statusCode response)))
        (is (str/includes? (.text (element body ".seon-data-panel")) "alice")
            "the pulled entity renders in the panel")
        (is (some-> (element default-body ".seon-data-panel [data-seon-path=\"[]\"]")
                    (.hasClass "seon-print-vector"))
            "without entity the schema vector remains the drill root")
        (is (= #{"[:seon.agent/id \"alice\"]"}
               (set (map #(get-in % [:query "entity"]) (data-links body))))
            "every floor handle preserves the selected entity root")))))

(deftest data-selects-a-stored-value-artifact-by-digest
  (with-server
    (fn [connection server _context]
      (let [stored (-> (admit/admit-value
                        {:seon.sci.admit/value {:alpha [1 2 3]}
                         :seon.sci.admit/interrupt-fn (fn [])
                         :seon.sci.admit/caps caps})
                       value/artifact
                       value/artifact-edn)
            digest (blob/put! connection stored)
            response (fetch server (str "/data?value=" digest))
            body (.body response)]
        (is (= 200 (.statusCode response)))
        (is (some #{":alpha"} (texts body ".seon-data-panel .seon-print-keyword"))
            "the stored value is the panel's root")
        (is (= #{digest} (set (map #(get-in % [:query "value"]) (data-links body))))
            "every floor handle keeps the stored value selected")))))

(deftest data-serves-a-five-megabyte-attribute-as-one-page-that-continues
  ;; One-render-path §3 (owner-adopted 2026-09-26; supersedes the 2026-09-07
  ;; unbounded-HTML ruling; Astra F1): HTML is the same selection as AI under
  ;; one B, so `/data` serves a page of the attribute, and its cut is an
  ;; expander at the same path and next offset: all of it stays reachable.
  (with-server
    (fn [connection server _context]
      (let [namespace-name 'my.agents.w3-data-cap
            huge (apply str (repeat (* 5 1024 1024) "x"))
            entity (URLEncoder/encode
                    (pr-str [:seon.ns/name namespace-name]) "UTF-8")
            path (URLEncoder/encode (pr-str [:seon.ns/source]) "UTF-8")]
        (support/transacted! connection
                           [(program/declaration-row
                             (db/carried-projection @connection)
                             {:seon.ns/name namespace-name :seon.ns/source huge}
                             :all :agent)])
        (let [response (fetch server (str "/data?entity=" entity
                                          "&path=" path "&offset=0"))
              body (.body response)]
          (is (= 200 (.statusCode response)))
          (is (< (count body) (* 1024 1024))
              "one page, never a second unbounded selection")
          (is (= [{:entity [:seon.ns/name namespace-name] :offset? true}]
                 (mapv (fn [{:keys [query]}]
                         {:entity (edn/read-string (get query "entity"))
                          :offset? (pos-int? (some-> (get query "offset") parse-long))})
                       (->> (.select (dom body) ".seon-data-panel a.seon-print-elision")
                            (mapv #(href (.attr ^Element % "href"))))))
              "the cut is one expander at its next offset, with a handle back to the same root"))))))

(deftest a-five-megabyte-value-is-one-bounded-selection-in-both-faces
  ;; One-render-path §3 (owner-adopted 2026-09-26, Astra F1): both faces are
  ;; one selection under the profile's B; the 2026-09-07 unbounded-HTML half
  ;; is retired. Before this `seon.print/fit` was the identity, so one stored
  ;; 5 MiB string rendered 5,242,987 bytes of `/ai` for a single evaluation
  ;; (measured, research/verify-storage-bound-2026-09-07.md B2) — a value
  ;; elided NOWHERE, in storage or in context.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "ai-bound")
     (let [namespace-name 'my.agents.ai-bound-source
           huge (apply str (repeat (* 5 1024 1024) "x"))]
       (support/transacted! connection
                            [(program/declaration-row
                              (db/carried-projection @connection)
                              {:seon.ns/name namespace-name :seon.ns/source huge}
                              :all :agent)])
       (let [database @connection
             ctx (support/fork-cluster-ctx connection)
             profile (render/agent-render-profile config/defaults)
             request {:seon.db/db database
                      :seon.sci.eval/ctx ctx
                      ;; THE VALUE IS THE STRING ITSELF, so the bound under
                      ;; test is the profile's string bound rather than its
                      ;; child count.
                      :seon.render/value huge
                      :seon.render/profile profile
                      :seon.render.call/id [::ai-bound namespace-name]
                      :seon.sci.admit/caps
                      (config/result-caps config/defaults)
                      :seon.sci.eval/time-limit-ms 20000}
             ai (render/render-ai request)
             html (render/render-html request)
             html-string (hiccup/->string html)]
         (is (string? ai))
         (is (< (count ai) (count huge))
             "the AI projection is bounded by the render profile")
         ;; A cut never omits its whole subject: the prefix that fit is
         ;; shown and only the remainder is counted (one-render-path §3e).
         (let [[_ prefix omitted] (re-find #"^\"([^\"]*)\"…\+(\d+)" ai)]
           (is (pos? (count prefix)))
           (is (= (count huge) (+ (count (edn/read-string (str "\"" prefix "\""))) (parse-long omitted)))))
         (is (not (str/includes? ai ";; …N")) "no legend: each cut carries its own continuation")
         (is (< (count html-string) (count huge))
             "the HTML projection is the same bounded selection")
         ;; THE STRUCTURAL HALF. A wide collection's cut is minted by
         ;; `fit-children`, which carries no prefix, and a collection cut
         ;; therefore rode an absent prefix as a STORED NIL into
         ;; `seon.print/elision` — a contract violation on every live
         ;; cluster, where the string half only failed when the profile
         ;; declared no bound. Both cuts are ordinary elision values.
         (let [wide (vec (range 5000))
               wide-request (assoc request
                                   :seon.render/value wide
                                   :seon.render.call/id
                                   [::ai-bound-wide namespace-name])
               wide-ai (render/render-ai wide-request)]
           (is (string? wide-ai))
           (is (< (count wide-ai) (count (pr-str wide)))
               "a wide collection is cut for AI under the same profile")
           (let [shown (edn/read-string wide-ai)
                 cut (first (filter #(and (symbol? %) (str/starts-with? (str %) "…")) shown))]
             ;; `[0 1 … …N (drop n source)]` reads back as numbers, the cut
             ;; symbol and, when the value has a source, its continuation.
             (is (= (count wide)
                    (+ (count (filter number? shown)) (parse-long (subs (str cut) 1))))))
           (is (not-any? #{4999 "4999"} (support/hiccup-leaves (render/render-html wide-request)))
               "and HTML shows the same cut collection")))))))

(deftest each-agent-has-an-isolated-debug-route
  (with-server
    (fn [connection server _context]
      (support/transacted! connection
                         (cluster.agent/creation-tx
                          {:seon.agent/id "alice"
                           :seon.cluster/name "web-test"
                           :seon.ns/name 'my.agents.alice}))
      (let [agent-page (.body (fetch server "/agent/root"))
            root (.body (fetch server "/agent/root/debug"))
            alice (.body (fetch server "/agent/alice/debug"))]
        (is (some #{"/agent/root/debug"} (map #(.attr ^Element % "href") (.select (dom agent-page) "a")))
            "the always-available debug view is linked from the curated page")
        (is (= [true true]
               [(some? (element root "#surface-graph-sent_2f_root"))
                (some? (element alice "#surface-graph-sent_2f_alice"))])
            "each agent's debug page is its own context surface"))
      (is (= 404 (.statusCode (fetch server "/agent/missing/debug")))))))

(deftest an-undeclared-incoming-reference-is-reachable-from-the-page
  ;; Transaction provenance remains navigable through the reference graph;
  ;; it does not become an extra context block.
  (with-server
    (fn [connection server _context]
      (support/transacted! connection
                         {:tx-data (support/agent-tx @connection "debug-trigger")
                          :tx-meta {:seon.db/user
                                    [:seon.agent/id agent-id]}})
      (let [stream (open-feed server (debug-feed-path
                                      agent-id [] "&maxRefAttributes=200"))]
        (try
          (let [paint (read-until! stream "id=\"debug-graph\"")]
            (is (some #{":seon.db/user"}
                      (->> (.select (painted paint) "script[data-graph-model]")
                           last .data json/read-str
                           (#(get-in % ["elements" "edges"]))
                           (map #(get-in % ["data" "attribute"]))))
                "the transaction's provenance reference is one of the graph's incoming edges"))
          (finally (.close stream)))))))

;;; ---------------------------------------------------------------------------
;;; Slice 1 — one POST, the existing route and render chain
;;; ---------------------------------------------------------------------------

(deftest the-message-route-commits-to-the-addressed-agent
  (with-server
    (fn [connection server _context]
      (let [response (post-form server
                                (str "/agent/" agent-id "/message")
                                "content=wire-echo-2026072903")]
        (is (= 204 (.statusCode response)))
        (is (empty? (.body response)))
        (is (= #{[agent-id "wire-echo-2026072903"]}
               (db/q '[:find ?id ?content
                       :in $ ?content
                       :where [?message :seon.message/content ?content]
                       [?message :seon.message/to ?agent]
                       [?agent :seon.agent/id ?id]]
                     @connection "wire-echo-2026072903")))))))

(deftest the-inbound-route-is-method-discriminated-test
  ;; seed 2026072905 — the former prefix-dispatch shadow class.
  (with-server
    (fn [connection server _context]
      (support/transacted! connection (support/agent-tx @connection "bob"))
      (is (= 404 (.statusCode (fetch server "/agent/bob/message"))))
      (is (= 404 (.statusCode (post-form server "/agent/bob" "content=x"))))
      (is (= 404 (.statusCode
                  (post-form server "/agent/bob/message/extra" "content=x"))))
      (is (= 404 (.statusCode
                  (post-form server "/agent/bob/messages" "content=x"))))
      (is (= 204 (.statusCode
                  (post-form server "/agent/bob/message" "content=exact")))
          "only the exact method and whole path reaches inbound"))))

(deftest a-refusal-emits-no-morph-test
  (with-server
    (fn [connection server _context]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (try
          (read-complete-paint! stream connection)
          (let [basis-before (:max-tx @connection)
                response (post-form server
                                    (str "/agent/" agent-id "/message")
                                    "content=%20%20")]
            (is (= 422 (.statusCode response)))
            (is (= basis-before (:max-tx @connection))
                "a refusal commits nothing, so route! has no report to paint")
            (is (zero? (patches (.body response)))
                "the HTTP refusal is text, never a competing morph"))
          (finally (.close stream)))))))

(defn- service-request
  "The DECLARED `:seon.render.web/service`, with only what a test varies over
  it. Its members are the ones a running cluster hands `start!` and
  `inbound`; a map of the four a given assertion reads is a shape the
  declared contract forbids."
  [connection overrides]
  (merge {:seon.store/connection-object connection
          :seon.agent/id agent-id
          :seon.sci.admit/caps caps
          :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
          :seon.config.eval/time-limit-ms
          (:seon.config.eval/time-limit-ms config/defaults)
          :seon.db.process/id process
          :seon.render.web/pages-mult
          (async/mult (async/chan (async/sliding-buffer 1)))
          :seon.render.web/registration (atom {})
          :seon.render.web/latest-packages (atom {})
          :seon.render.web/render-channel (async/chan (async/sliding-buffer 1))
          :seon.render.web/fault-channel (async/chan (async/dropping-buffer 1))}
         overrides))

(deftest transaction-refusals-map-to-http-without-success
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "web-write-refusal")
      (support/transacted! connection
                         (cluster.agent/creation-tx
                          {:seon.agent/id agent-id
                           :seon.cluster/name "web-write-refusal"
                           :seon.ns/name 'my.agents.root}))
      (let [service (service-request connection {})
            inbound {:seon.agent/id agent-id
                     :seon.message/inbound-content "accepted"}]
        (doseq [[result expected-status]
                [[{:seon.error/at (java.util.Date. 0) :seon.error/layer :seon.render.web-test/fixture :seon.error/operation 'seon.render.web-test/transaction-refusals-map-to-http-without-success :seon.render/refused-member :seon.render/output
                   :seon.error/message "dependency refusal"}
                  422]
                 [{:seon.error/at (java.util.Date. 0) :seon.error/layer :seon.render.web-test/fixture :seon.error/operation 'seon.render.web-test/transaction-refusals-map-to-http-without-success :seon.render/refused-member :seon.render/output
                   :seon.error/message "core failure"
                   :seon.db.write/attempt {:seon.db.write.attempt/completion-unavailable "No completion was observed."}}
                  500]]]
          (let [response (web/inbound (assoc service :seon.db/writer (fn [_connection _transaction] result))
                                      inbound)]
            (is (= expected-status (:status response)))
            (is (= (:seon.error/message result) (:body response)))))))))

(deftest a-route-throw-answers-a-bounded-500-inside-the-handler
  ;; Task 80f54dd48aef: a throw escaping the handler reached http-kit's
  ;; unbounded printer. A form body that is not a stream makes
  ;; `decode-form`'s slurp throw a real IllegalArgumentException.
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "web-handler-throw")
      (let [response ((web/handler (service-request connection {}))
                      {:request-method :post
                       :uri (str "/agent/" agent-id "/message")
                       :headers {}
                       :body 42})]
        (is (= 500 (:status response)))
        ;; the 500 body is the error entry of the value printer's HTML face: it names
        ;; the producing operation and carries the throwable's message
        (is (str/includes? (:body response) "seon.render.web/handler"))
        (is (str/includes? (:body response) "as an InputStream"))
        (is (<= (count (:body response))
                (inc (:throwable-chars @#'seon.render.value/text-limits))))))))

(deftest start-refuses-a-flat-process-write-before-binding
  (support/with-database
    (fn [connection]
      ;; The member's world holds no process row for this service, so start!
      ;; writes one through the service's writer: here, a refusing writer.
      (let [result
            (support/refusal-data
             #(web/start! (service-request
                           connection
                           {:seon.db/writer
                            (fn [_connection _transaction]
                              {:seon.error/at (java.util.Date. 0) :seon.error/layer :seon.render.web-test/fixture :seon.error/operation 'seon.render.web-test/start-refuses-a-flat-process-write-before-binding :seon.render/refused-member :seon.render/output
                               :seon.error/message "injected process refusal"})})))]
        (is (= :seon.render/output (:seon.render/refused-member result)))
        (is (= "injected process refusal" (:seon.error/message result)))))))

(deftest a-cross-origin-inbound-is-refused-test
  ;; seed 2026072906 — one state-changing branch, one same-origin check.
  (with-server
    (fn [connection server _context]
      (let [basis-before (:max-tx @connection)
            response (post-form server
                                (str "/agent/" agent-id "/message")
                                "content=forged"
                                "https://attacker.invalid")]
        (is (= 403 (.statusCode response)))
        (is (= basis-before (:max-tx @connection)))
        (is (empty?
             (db/q '[:find [?message ...]
                    :where
                    [?message :seon.message/content "forged"]]
                  @connection)))
        (is (= 403
               (.statusCode
                (post-form server
                           (str "/agent/" agent-id "/message")
                           "content=wrong-scheme"
                           (str "https://127.0.0.1:"
                                (:seon.render.web/port server)))))
            "same authority under a different scheme is cross-origin")))))

;;; ---------------------------------------------------------------------------
;;; Derived ports — bookmarkable, restart-stable, collision-tolerant
;;; ---------------------------------------------------------------------------

(deftest a-name-derives-one-port-forever
  ;; THE POINT IS A BOOKMARK: a named cluster must answer on the same
  ;; port after every restart, or the tab a person left open stops
  ;; working for no visible reason.
  (doseq [name ["default" "acme" "morning" "a" "клас" "with-dashes"]]
    (is (= (web/derived-port name) (web/derived-port name))))
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
    ;; GENERATED FROM THE DECLARED DOMAIN, never from bare ASCII: a cluster
    ;; name is one relative path segment, and `.` is not one — the property
    ;; was calling `derived-port` with values its own contract refuses.
    (prop/for-all [name cluster/cluster-name-generator]
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
  (with-server
    (fn [connection first-server _graph]
      (let [taken (:seon.render.web/port first-server)
            ;; its own disposable view half: this test is about the PORT,
            ;; and the second view never opens a feed
            second-service (service-request connection {:seon.render.web/port taken})
            second-server (web/start! second-service)]
        (try
          (is (not= taken (:seon.render.web/port second-server))
              "it bound somewhere else")
          (is (= taken (:seon.render.web/wanted-port second-server))
              "and it says which bookmark just stopped working")
          (is (= 200 (.statusCode (fetch second-server "/css/input.css")))
              "while serving normally — the collision costs a port, not a view")
          (finally (web/stop! (merge second-server (select-keys second-service [:seon.render.web/registration :seon.config.eval/time-limit-ms]))))))))
  (testing "a clean bind reports no wanted-port at all, so key presence
            answers 'did this fall back?'"
    (with-server
      (fn [_connection server _context]
        (is (nil? (:seon.render.web/wanted-port server)))))))

;;; ---------------------------------------------------------------------------
;;; A page that throws every pass is ONE fault, not one per pass
;;; ---------------------------------------------------------------------------

;;; The proc surviving a throwing page was the repair that made the rest of
;;; 2026-09-07 diagnosable; what it left behind was a fault committed on every
;;; pass — six identical entities in ninety seconds, live — plus an `offer!` whose
;;; refusal nobody could observe, and a `failed-page-result` sitting outside
;;; the pass's own protection, so a throw while REPORTING a failure ended the
;;; proc exactly as the failure would have.
(defn- throwing-render-state
  "One render-pass state whose pages all throw, recording faults under :record
  (R1: every producer records through seon.fault/fault! on its own thread)."
  [connection]
  (support/seed-cluster! connection "web-fault-test")
  (support/transacted! connection
                       (cluster.agent/creation-tx
                        {:seon.agent/id "agent-a"
                         :seon.cluster/name "web-fault-test"
                         :seon.ns/name 'my.agents.fault-test}))
  {:seon.turn.loop/cluster
   (support/cluster-handle
   {:seon.cluster/name "web-fault-test"
    :seon.db/connection connection
    :seon.sci.admit/caps caps
    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
    :seon.config.eval/time-limit-ms 1000
    :seon.db.process/id "web-fault-test"})
   :seon.agent/routing (atom {})
   ;; the render proc's args carry the environment its faults record through
   :seon.env/environment (support/environment "web-fault-test" connection)
   :seon.render.web/registration (atom {"agent-a" 1})
   :seon.render.web/latest-packages (atom {})
   :seon.render.web/root-agent-id "root"
   ::web/streams {}
   ::web/packages {}
   ::web/fragments {}
   ::web/calls {}
   ::web/passes 0})

(deftest a-page-failing-every-pass-records-one-fault-and-the-proc-survives
  ;; THE FAILING PAGE IS DATA: a registered debug page whose request carries
  ;; none of its bounds cannot be derived (the observation refuses a missing
  ;; limit). A valid request never throws here, since every refusal is a value;
  ;; no route registers such a request, so a stale or hand-made registration
  ;; is the one way in.
  (support/with-database
    (fn [connection]
      (let [unbounded (fn [subject]
                        [::web/debug-tab {:seon.render.debug/subject subject
                                          :seon.render.debug/viewer-namespace 'my.agents.fault-test}])
            failing (unbounded [:seon.agent/id "agent-a"])
            state (assoc (throwing-render-state connection)
                         :seon.render.web/registration (atom {failing 1}))
            ;; Every stored fault occurrence of this member: the page's fault
            ;; is recorded under its cause's declared identity.
            recorded #(or (db/q '[:find (sum ?n) . :with ?o
                                  :where [?o :seon.error.occurrence/count ?n]]
                                (db/db connection))
                          0)
            three-passes (fn [state]
                           (reduce (fn [state _] (first (#'web/render-pass state @connection true)))
                                   state (range 3)))
            before (recorded)
            after (three-passes state)]
        (testing "three failing passes record ONE fault"
          (is (= (inc before) (recorded))))
        (testing "and the proc kept going: every pass produced the page's failure"
          (is (= [3 #{failing}]
                 [(::web/passes after) (set (keys (::web/fault-signatures after)))])))
        (testing "another failing page records its own fault"
          (let [before (recorded)
                other (unbounded [:seon.agent/id "root"])]
            (reset! (:seon.render.web/registration after) {failing 1 other 1})
            (three-passes after)
            (is (= (inc before) (recorded)))))))))

(deftest debug-algorithm-carries-the-render-evaluation-inputs
  (with-server
    (fn [connection server _context]
      (let [before @connection
            responses (vec (repeatedly 10 #(fetch server "/agent/root/debug")))
            bodies (mapv #(.body %) responses)
            response (last responses)
            body (last bodies)]
        (is (every? #(= 200 (.statusCode %)) responses))
        (is (= before @connection) "ten preview loads write no facts")
        (is (= 200 (.statusCode response))
            "every algorithm value render receives the handle's deadline and caps")
        (is (= #{"system-turn" "virtual-turn" "compact"}
               (set (map #(.attr ^Element % "value")
                         (.select (dom body) ".seon-session-actions input[name=action]"))))
            "the rendered session offers its three system actions")))))
