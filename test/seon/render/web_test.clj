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
            [org.httpkit.server :as http]
            [seon.blob :as blob]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.context :as seon.context]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.oversight :as oversight]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.render.data :as data]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.render.walk :as render.walk]
            [seon.render.web :as web]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as sci.kernel]
            [seon.test-support :as support]
            [starfederation.datastar.clojure.api :as datastar])
  (:import [java.util.concurrent CompletableFuture CountDownLatch]
           [java.net BindException URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (support/environment "seon.render.web-test")))


(def ^:private caps
  (config/result-caps (config/defaults)))

(def ^:private process
  "This suite's run-holder identity. The web service REQUIRES one —
  which processes are alive is the one input no database value answers,
  and a page that guessed it would either invent wedges or hide them."
  "web-test-1")

(def ^:private agent-id "root")

(defn- web-private
  [function-name]
  (deref (ns-resolve 'seon.render.web function-name)))

;;; ---------------------------------------------------------------------------
;;; Fixture
;;; ---------------------------------------------------------------------------

(deftest accepted-socket-writes-have-a-loud-drain-backstop
  (let [drained (CompletableFuture.)
        closed? (atom false)
        result
        (with-redefs [http/send! (fn [& _] true)
                      http/write-state
                      (fn [_]
                        {:http-kit.write/pending-bytes 17
                         :http-kit.write/drained drained})
                      datastar/close-sse! (fn [_] (reset! closed? true))]
          ((web-private 'write-package!)
           ::channel ::generator (byte-array [1]) 20 ::socket-drain))]
    (is (= :seon.await/backstop-fired (:seon.error/kind result)))
    (is (= ::socket-drain
           (get-in result [:seon.error/data
                           :seon.error/diagnostic-member])))
    (is (true? @closed?))))

(deftest initial-package-publication-has-a-loud-render-backstop
  (support/with-database
    (fn [connection]
      (let [pages (async/chan)
            pages-mult (async/mult pages)
            result
            ((web-private 'settle-package!)
             {:seon.store/connection-object connection
              :seon.render.web/registration (atom {})
              :seon.render.web/render-channel (async/chan 1)
              :seon.render.web/pages-mult pages-mult
              :seon.render.web/latest-packages (atom {})
              :seon.config.eval/time-limit-ms 20}
             ::missing-page)]
        (is (= :seon.await/backstop-fired (:seon.error/kind result)))
        (is (= ::missing-page
               (get-in result [:seon.error/data
                               :seon.error/diagnostic-member])))
        (is (integer?
             (get-in result
                     [:seon.error/data
                      :seon.error/diagnostic-evidence
                      :seon.await/observation
                      :seon.render.package/basis-transaction])))
        (async/close! pages)))))

(defn- with-server
  "The whole render pipeline on real sockets: the render proc in its own
  graph, the mult the tabs tap, the routing listener that wakes it.

  `body` receives the connection, the server descriptor, and a CONTEXT
  map holding the live pipeline: `:graph` so a test can `flow.core/ping`
  it and assert HOW MANY derivations a commit cost (the claim the shared
  registration exists to make), and `:pages-mult` so a test can take a
  tap of its own and be the slow browser on purpose."
  [body]
  (support/with-database
    (fn [connection]
      (let [_ (support/seed-cluster! connection "web-test")
            _ (db/transact! connection
                            (cluster.agent/creation-tx
                             {:seon.cluster.agent/id agent-id
                              :seon.cluster/name "web-test"
                              :seon.ns/name 'my.agents.root}))
            ctx (sci.eval/cluster-ctx @connection connection)
            server (atom nil)
            render-channel (async/chan (async/sliding-buffer 1))
            runtime-eval-channel (async/chan (async/sliding-buffer 1))
            context-channel (async/chan)
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
                  :seon.render/context-channel context-channel
                  :seon.render.web/pages-channel pages-channel
                  :seon.render.web/registration registration
                  :seon.render.web/latest-packages latest-packages
                  :seon.render.web/interest interest
                  :seon.render.web/completion completion
                  :seon.render.web/root-agent-id agent-id
                  ::web/profile
                  (render/agent-render-profile (config/defaults))}
            graph (flow.core/create-flow
                   {:procs
                    {:seon.render.web/render
                     {:proc (flow/var-process
                             #'web/render-step :io
                             (assoc view
                                    :seon.env/environment @test-environment
                                    :seon.cluster.loop/cluster
                                    {:seon.db/connection connection
                                     :seon.cluster/name "web-test"
                                     :seon.sci.admit/caps caps
                                     :seon.sci.eval/ctx ctx
                                     :seon.config.eval/time-limit-ms
                                     (:seon.config.eval/time-limit-ms
                                      (config/defaults))
                                     :seon.config/on-core-error :record
                                     :seon.cluster.run/process process
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
                        :seon.cluster.wake/armer-channel
                        (async/chan (async/sliding-buffer 1))
                        :seon.cluster.wake/render-channel render-channel
                        :seon.render.web/interest interest
                        :seon.cluster.wake/fault-channel fault-channel
                        :seon.cluster.wake/key ::route})
          (reset! server (web/start!
                          {:seon.store/connection-object connection
                           :seon.cluster.agent/id agent-id
                           :seon.sci.admit/caps caps
                           :seon.sci.eval/ctx ctx
                           :seon.config.eval/time-limit-ms
                           (:seon.config.eval/time-limit-ms
                            (config/defaults))
                           :seon.config/on-core-error :record
                           :seon.cluster.run/process process
                           :seon.render.web/pages-mult pages-mult
                           :seon.render.web/registration registration
                           :seon.render.web/latest-packages latest-packages
                           :seon.render.web/render-channel render-channel
                           :seon.render/context-channel context-channel
                           :seon.render.web/fault-channel fault-channel}))
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
            (when @server (web/stop! @server))
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
            (support/await-event! (future (async/<!! completion))
                                  [:render-proc-stopped])
            (async/close! render-channel)
            (async/close! runtime-eval-channel)
            (async/close! pages-channel)
            (async/close! stream-channel)))))))

(defn- ping-state
  "The render proc's own ping state — passes, watched agents, taps and
  streaming agents, exposed by its `:ping-map-fn`.

  A MISSED PING IS \"BUSY\", NEVER \"NO STATE\". `flow/ping` returns a
  map only \"for those procs that reply within timeout-ms (default
  1000)\"
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-142`),
  and it answers on the proc's own transform loop
  (`.../flow/impl.clj:76-86,205`). This proc is `:io`, one derivation
  serializes the whole walk, and it honours the coalescing floor INSIDE
  the transform — so a pass routinely outlasts that window and flow
  reports nothing for this pid. Every oracle below then read `nil` as a
  state map: `(zero? nil)` and `(- nil before)` both threw
  NullPointerException in one run of 2026-08-07. So observe the proc's
  answer instead of sampling for it: retry until it replies, paced by
  ping's own window, under the shared loud backstop that turns a
  genuinely wedged proc into a failure rather than a hang."
  [context]
  (support/await-event!
   (future
     (loop []
       (or (-> (flow.core/ping (:graph context))
               (get :seon.render.web/render)
               (get :clojure.core.async.flow/state))
           (recur))))
   [:render-proc-ping]))

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
     (future (async/<!! settlement))
     [:render-settled])))

(defn- open-run!
  "Open a minimal run row for one renderer presence-gate test."
  [connection run-id]
  (db/transact! connection
              [{:seon.cluster.run/id run-id
                :seon.cluster.run/agent
                [:seon.cluster.agent/id agent-id]
                :seon.cluster.run/opened-at (java.util.Date.)}]))

(defn- client [] (.build (HttpClient/newBuilder)))

(defn- fetch
  [server path]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create (str (:seon.render.web/url server) path)))
                    (.GET)
                    (.build))]
    (.send (client) request (HttpResponse$BodyHandlers/ofString))))

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
     (.send (client) (.build request)
            (HttpResponse$BodyHandlers/ofString)))))

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

(defn- read-complete-paint!
  [stream _connection]
  (let [paint (read-patches! stream 1)]
    (is (= 1 (patches paint))
        "the feed sends one proc-framed keyframe event")
    (is (str/includes? paint "surface-stream")
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
      (let [body (.body (fetch server "/"))]
        (is (< (.indexOf body "</main>") (.indexOf body "data-init"))
            "the opener is after every surface, not inside one")
        (is (str/includes? body "retryMaxCount: Infinity"))
        (is (str/includes? body "openWhenHidden: false"))))))

(deftest an-agent-page-is-the-same-mechanism-as-root
  ;; Root is an agent. If this test ever needs a root-specific branch,
  ;; the design has regressed.
  (with-server
    (fn [connection server _context]
      (db/transact! connection
                  (cluster.agent/creation-tx
                   {:seon.cluster.agent/id "agent-b"
                    :seon.cluster/name "web-test"
                    :seon.ns/name 'my.agents.agent-b}))
      (let [root (.body (fetch server "/"))
            other (.body (fetch server "/agent/agent-b"))]
        (is (str/includes? root "<title>seon · root</title>"))
        (is (str/includes? root "<code>my.agents.root</code>"))
        (is (str/includes? other "<title>seon · agent-b</title>"))
        (is (str/includes? other "<code>my.agents.agent-b</code>"))
        ;; narration face retired (results-as-data); the ns-code checks
        ;; above prove the alias selected agent-b's own walk root
        (is (str/includes? other "data-walk-path=\"[]\""))))))

(deftest debug-responds-from-the-exact-capture-before-deriving-the-live-walk
  (with-server
    (fn [connection server context]
      (let [calls (atom 0)
            exact-ai (str "left<&\n"
                          (apply str (repeat 150000 "x"))
                          "\nright")
            run-id "debug-exact-capture"]
        (db/transact!
         connection
         [{:seon.cluster.run/id run-id
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (java.util.Date.)}])
        (db/transact!
         connection
         [{:seon.context.capture/id "debug-exact-capture-context"
           :seon.context.capture/run [:seon.cluster.run/id run-id]
           :seon.context.capture/basis-t 42
           :seon.context.capture/prompt exact-ai}])
        (let [before (settle-render! context)]
          (with-redefs [render/walk
                        (fn [_options]
                          (swap! calls inc)
                          (throw
                           (ex-info "the request thread derived a walk" {})))]
            (let [response (fetch server "/agent/root/debug?prompt=true")
                  body (.body response)]
              (is (= 200 (.statusCode response)))
              (is (zero? @calls)
                  "the response does not derive either live projection")
              (is (= before (derivations context))
                  "the response does not wait on a debug render-proc pass")
              (is (str/includes? body "id=\"debug-ai-root\""))
              (is (str/includes? body "left&lt;&amp;\n"))
              (is (str/includes? body "\nright"))
              (is (str/includes? body "id=\"debug-html-root\""))
              (is (str/includes? body "class=\"seon-debug-grid\""))
              (is (str/includes? body
                                 "Loading the actual selected output")
                  "the pending pane states what has not derived yet"))))))))

(deftest a-never-run-agents-debug-context-is-labeled-prospective
  (with-server
    (fn [connection server _context]
      (let [observed (atom nil)
            history render.walk/history]
        (with-redefs [render.walk/history
                      (fn [request]
                        (reset! observed request)
                        (history request))]
          (let [response (fetch server "/agent/root/debug?prompt=true")
                body (.body response)]
            (is (= 200 (.statusCode response)))
            (is (= agent-id
                   (:seon.cluster.agent/id @observed))
                "the production request carries history's agent input")
            (is (= [:seon.cluster.agent/id agent-id]
                   (:seon.render.walk/lookup @observed)))
            (is (contains? @observed :seon.render.walk/root-acquisition)
                "debug uses the next transition's compiled root acquisition")
            (is (identical? @connection (:seon.db/db @observed))
                "the prospective query reads the current immutable database")
            (is (str/includes? body
                               "seon-debug-context-status\">prospective"))
            (is (not (str/includes? body
                                    "No recorded context capture exists")))))))))

(deftest a-fresh-cluster-debug-page-renders-a-prospective-prompt
  (with-server
    (fn [connection server _context]
      (db/transact!
       connection
       [(assoc (config/defaults)
               :seon.config/cluster "web-test")
        {:seon.cluster.message/id "fresh-cluster-task"
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/content "inspect the fresh cluster"
         :seon.cluster.message/at (java.util.Date. 1788460800000)}
        {:seon.cluster.run/id "fresh-cluster-run"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/trigger
         [:seon.cluster.message/id "fresh-cluster-task"]
         :seon.cluster.run/opened-at (java.util.Date. 1788460801000)
         :seon.cluster.run/process process}
        {:seon.cluster.agent/id agent-id
         :seon.cluster.agent/run [:seon.cluster.run/id "fresh-cluster-run"]}])
      (let [message-custody seon.context/message-custody
            observed-custody-inputs (atom [])]
        (with-redefs [seon.context/message-custody
                      (fn [database run-id rendered-agent-id message-eid]
                        (swap! observed-custody-inputs
                               conj
                               {:seon.cluster.run/id run-id
                                :seon.cluster.agent/id rendered-agent-id})
                        (message-custody database run-id rendered-agent-id
                                         message-eid))]
          (let [response (fetch server "/agent/root/debug?prompt=true")
                body (.body response)]
            (is (= 200 (.statusCode response)))
            (is (nil?
                 (db/q '[:find ?capture .
                         :where
                         [?run :seon.cluster.run/id "fresh-cluster-run"]
                         [?capture :seon.context.capture/run ?run]]
                       @connection))
                "the fresh agent has never reached context capture")
            (is (str/includes? body
                               "seon-debug-context-status\">prospective"))
            (is (not (str/includes? body "<pre></pre>"))
                "the real walk contributes at least one byte to the prompt")
            (is (= #{{:seon.cluster.run/id "fresh-cluster-run"
                      :seon.cluster.agent/id agent-id}}
                   (set @observed-custody-inputs))
                "history receives the run and agent it uses for custody")
            (is (empty?
                 (db/q '[:find ?cost
                         :where
                         [?cost :seon.render.cost/estimated-tokens]]
                       @connection))
                "prospective debug remains a read-only observation")
            (is (not (str/includes?
                      body
                      "The prospective agent context is unavailable.")))))))))

(deftest an-unavailable-prospective-context-renders-its-diagnostic-data
  (with-server
    (fn [_connection server _context]
      (with-redefs [render.walk/history
                    (fn [_request]
                      (throw (RuntimeException.
                              "injected prospective failure")))]
        (let [response (fetch server "/agent/root/debug?prompt=true")
              body (.body response)]
          (is (= 200 (.statusCode response)))
          (is (str/includes? body
                             "seon-debug-context-status\">unavailable"))
          (is (not (str/includes? body
                                  "seon-debug-context-status\">prospective"))
              "HTTP 200 does not label an unavailable pane as healthy")
          (is (str/includes? body "class=\"seon-debug-diagnostic\"")
              "the composite page keeps HTTP 200 only with a visible diagnostic")
          (is (not (str/includes? body "<pre"))
              "an unavailable prompt is not rendered as a healthy prompt")
          (doseq [field [":seon.error/kind"
                         ":seon.error/diagnostic-member"
                         ":seon.error/diagnostic-cause"
                         ":seon.error/diagnostic-expected"]]
            (is (str/includes? body field)
                (str "the unavailable result renders " field)))
          (is (str/includes? body
                             ":seon.render.web/prospective-context-unavailable"))
          (is (str/includes? body "injected prospective failure"))
          (is (str/includes? body ":seon.cluster.prompt/text")))))))

(deftest debug-pages-distinguish-held-live-and-dead-runs
  (with-server
    (fn [connection server _context]
      (db/transact!
       connection
       [{:seon.cluster.run/id "debug-held-live"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (java.util.Date.)
         :seon.cluster.run/process process}
        {:seon.cluster.run/id "debug-held-dead"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (java.util.Date.)
         :seon.cluster.run/process "web-test-dead-process"}])
      (let [observed (atom nil)
            unit oversight/unit]
        (with-redefs [oversight/unit
                      (fn [request]
                        (reset! observed request)
                        (unit request))]
          (let [stream
                (open-feed
                 server
                 (str "/feed/" agent-id
                      "?debug=true&path="
                      (java.net.URLEncoder/encode (pr-str []) "UTF-8")
                      "&offset=0"))]
            (try
              (read-patches! stream 1)
              (is (= #{process}
                     (:seon.cluster.run/live-processes @observed))
                  "debug passes the service's observed process set")
              (is (= #{"debug-held-dead"}
                     (into #{}
                           (map :seon.cluster.run/id)
                           (:seon.problems/wedged-runs
                            (problems/problems
                             @connection
                             (select-keys
                              @observed
                              [:seon.cluster.run/live-processes])))))
                  "the dead holder is wedged and the live holder is not")
              (finally (.close stream)))))))))

(deftest static-resources-come-off-the-classpath
  (with-server
    (fn [_connection server _context]
      (is (= 200 (.statusCode (fetch server "/js/datastar.js"))))
      (let [stylesheet (fetch server "/css/input.css")]
        (is (= 200 (.statusCode stylesheet)))
        (doseq [selector [".seon-print-summary"
                          ".seon-print-table"
                          ".seon-render-unavailable"]]
          (is (str/includes? (.body stylesheet) selector)
              (str selector " has a rule in the served stylesheet"))))
      (testing "and path traversal is refused by construction"
        (is (= 404 (.statusCode (fetch server "/css/../../secret"))))))))

(deftest an-unknown-route-is-an-honest-404
  (with-server
    (fn [_connection server _context]
      (is (= 404 (.statusCode (fetch server "/nope")))))))

(deftest namespace-routes-admit-by-reader-and-existing-corpus-row
  (with-server
    (fn [connection server context]
      (is (nil? (cluster.agent/owner-of @connection 'seon.flow)))
      (let [known (fetch server "/ns/seon.flow")
            owner (cluster.agent/owner-of @connection 'seon.flow)
            basis-after-known (:max-tx @connection)]
        (is (= 200 (.statusCode known)))
        (is (str/includes? (.body known) "data-walk-path=\"[]\"")
            "the canonical namespace page renders its owner's HTML walk
            (narration face retired; structural root marker instead)")
        (is (= "seon.flow" owner))
        (is (= [process]
               (db/q '[:find [?process-id ...]
                      :in $ ?agent-id
                      :where
                      [?agent :seon.cluster.agent/id ?agent-id ?tx]
                      [?tx :seon.db/process ?process]
                      [?process :seon.db.process/id ?process-id]]
                    @connection owner))
            "first-touch ensure carries the existing creation provenance")
        (is (= 200 (.statusCode (fetch server "/ns/seon.flow/debug"))))
        (is (= basis-after-known (:max-tx @connection))
            "debug and repeat visits resume the existing owner untouched"))
      (doseq [path ["/ns/nonexistent.thing" "/ns/123bad"]]
        (let [datoms-before (count (db/datoms @connection :eavt))
              basis-before (:max-tx @connection)
              response (fetch server path)]
          (is (= 404 (.statusCode response)) path)
          (is (= datoms-before (count (db/datoms @connection :eavt)))
              (str path " wrote no datoms"))
          (is (= basis-before (:max-tx @connection))
              (str path " committed no transaction")))))))

(deftest canonical-debug-inspects-without-creating-a-namespace-owner
  (with-server
    (fn [connection server _context]
      (let [namespace-name 'seon.flow
            basis-before (:max-tx @connection)
            response (fetch server
                            (str "/ns/seon.flow/debug?subject="
                                 (java.net.URLEncoder/encode
                                  (pr-str [:seon.ns/name namespace-name])
                                  "UTF-8")
                                 "&output=%3Aseon.render%2Fhtml"))
            body (.body response)]
        (is (nil? (cluster.agent/owner-of @connection namespace-name)))
        (is (= 200 (.statusCode response)))
        (is (= basis-before (:max-tx @connection))
            "inspection commits no transaction")
        (is (nil? (cluster.agent/owner-of @connection namespace-name))
            "inspection does not create an agent")
        (is (str/includes? body "id=\"debug-inspection-header\""))
        (is (str/includes? body "viewer</span><code>seon.flow"))
        (is (str/includes? body "subject</span><code>[:seon.ns/name seon.flow]"))
        (is (str/includes? body "debug=true")
            "the existing feed receives the experiment request")))))

(deftest debug-header-identifies-the-handed-program-and-projection
  (support/with-database
    (fn [connection]
      (let [ctx (support/fork-cluster-ctx connection)
            program-identity
            ((web-private 'debug-program-identity) @connection ctx)
            projection (sci.kernel/context-projection ctx)
            header ((web-private 'debug-header-html)
                    {:seon.render.debug/viewer-namespace 'my.viewer
                     :seon.render.debug/subject [:my/id "subject"]
                     :seon.render/output :seon.render/html
                     :seon.render.web/pull-max-work 40
                     :seon.render.web/pull-max-results 40
                     :seon.render.data/max-result-weight 4000}
                    {:seon.render.data/snapshot
                     (db/database-value-identity @connection)}
                    1.0
                    program-identity)]
        (is (= (apply str (repeat 64 "0"))
               (:seon.source/digest program-identity))
            "the header identity is the digest recorded by this cluster database")
        (is (= (:seon.schema.projection/fingerprint projection)
               (:seon.schema.projection/fingerprint program-identity))
            "the fingerprint comes from the handed SCI context projection")
        (is (str/includes? header
                           (str "indexed source digest</span><code>&quot;"
                                (:seon.source/digest program-identity)
                                "&quot;"))
            "the digest is labelled as indexed source, not current loaded code")
        (is (str/includes?
             header
             (str "schema projection fingerprint</span><code>"
                  (:seon.schema.projection/fingerprint program-identity)))
            "the exact context projection fingerprint is visible")))))

(deftest debug-datom-links-follow-reference-direction-and-preserve-the-view
  (let [request {:seon.render.debug/viewer-namespace 'my.viewer
                 :seon.render.debug/subject [:my/id "before"]
                 :seon.render/output :seon.render/ai
                 :seon.render.debug/prompt? true
                 :seon.render.data/limit 17
                 :seon.render.data/max-ref-attributes 19
                 :seon.render.data/max-result-weight 23
                 :seon.render.web/pull-max-work 29
                 :seon.render.web/pull-max-results 31
                 :seon.render.data/cursor
                 {:seon.render.data/path [:old]
                  :seon.render.data/offset 7}
                 :seon.render.data/outgoing-cursor {:old :outgoing}
                 :seon.render.data/incoming-cursor {:old :incoming}}
        page (fn [datoms]
               {:seon.render.data/datoms datoms
                :seon.render.data/complete? true})
        render (web-private 'datom-page-html)
        outgoing (render request #{:my/ref} :outgoing
                         (page [{:e 10 :a :my/ref :v 20 :tx 30 :added true}
                                {:e 10 :a :my/name :v "plain"
                                 :tx 31 :added true}]))
        incoming (render request #{:my/ref} :incoming
                         (page [{:e 90 :a :my/ref :v 10
                                 :tx 32 :added true}]))
        anchors (fn [tree]
                  (filter #(and (vector? %) (= :a (first %)))
                          (tree-seq coll? seq tree)))
        hrefs (into {}
                    (map (fn [anchor]
                           [(get-in anchor [2 1])
                            (java.net.URLDecoder/decode
                             (get-in anchor [1 :href]) "UTF-8")]))
                    (concat (anchors outgoing) (anchors incoming)))
        target-href (get hrefs "20")
        source-href (get hrefs "90")
        attribute-href (get hrefs ":my/ref")]
    (is (str/includes? target-href "subject=20")
        "an outgoing ref value follows its target")
    (is (str/includes? source-href "subject=90")
        "an incoming ref entity follows its source")
    (is (str/includes? attribute-href
                       "subject=[:db/ident :my/ref]")
        "an attribute follows its installed :db/ident entity")
    (is (not (find hrefs "\"plain\""))
        "an ordinary scalar value remains text")
    (doseq [href [target-href source-href attribute-href]]
      (is (str/starts-with? href "/ns/my.viewer/debug?")
          "navigation keeps the viewing namespace")
      (is (every? #(str/includes? href %)
                  ["output=:seon.render/ai" "prompt=true" "limit=17"
                   "maxRefAttributes=19" "maxResultWeight=23"
                   "maxWork=29" "maxResults=31" "path=[]" "offset=0"])
          "navigation preserves output and bounds and resets the value cursor")
      (is (not (or (str/includes? href "outgoingCursor=")
                   (str/includes? href "incomingCursor=")))
          "a new subject never carries snapshot-bound page cursors"))))

(deftest debug-layout-leads-with-the-rendered-result-and-real-description
  (let [placeholder
        (hiccup/->string
         ((web-private 'debug-experiment-placeholder)
          "debug-html-inspection"))
        output-position (.indexOf placeholder "id=\"debug-html-inspection\"")
        selection-position (.indexOf placeholder "id=\"debug-selection\"")
        observation-position (.indexOf placeholder "id=\"debug-observation\"")
        selection
        {:seon.render.selection/selected 'my.render/card
         :seon.render.selection/stages
         [{:seon.render.selection.stage/name :namespace
           :seon.render.selection.stage/status :selected
           :seon.render.selection.stage/candidates
           [{:seon.render.selection.candidate/producer 'my.render/card
             :seon.render.selection.candidate/status :compatible}]}]}
        selected-html
        ((web-private 'debug-selection-html)
         selection
         {:seon.fn/doc "Render the actual <card>."})
        undocumented-html
        ((web-private 'debug-selection-html) selection {})
        observation-html
        ((web-private 'debug-observation-html)
         #{}
         {:seon.render.debug/viewer-namespace 'my.viewer}
         {:seon.render.data/eid 42
          :seon.render.data/ref-attributes-probed 0
          :seon.render.data/outgoing
          {:seon.render.data/datoms []
           :seon.render.data/complete? true}
          :seon.render.data/incoming
          {:seon.render.data/datoms []
           :seon.render.data/complete? true}
          :seon.render.data/identities []
          :seon.render.data/identities-complete? true}
         [:div "structural marker"]
         false)]
    (is (< -1 output-position selection-position observation-position)
        "the stable feed targets appear in rendered-result-first order")
    (is (str/includes?
         selected-html
         "<p class=\"seon-debug-description\"><span>description</span> Render the actual &lt;card&gt;.</p>")
        "the selected declaration's actual :seon.fn/doc is the description")
    (is (and (str/includes? selected-html "selection evidence")
             (str/includes? selected-html "my.render/card")
             (str/includes? selected-html "compatible"))
        "ranked candidate evidence remains available in its disclosure")
    (is (not (str/includes? undocumented-html "seon-debug-description"))
        "an absent declaration doc does not invent a description")
    (is (and (str/includes?
              observation-html
              "<details class=\"seon-debug-structural-detail\">")
             (str/includes? observation-html "structural marker")
             (str/includes? observation-html "raw datom evidence")
             (not (str/includes? observation-html "<details open")))
        "structural value and raw datoms are present but collapsed")))

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
    (is (str/includes? html "outgoing partial, incoming complete"))
    (is (and (str/includes? html "<div")
             (str/includes? html "class=\"seon-debug-graph-canvas\"")
             (str/includes? html "role=\"img\"")
             (not (str/includes? html "<canvas")))
        "Cytoscape receives an ordinary container for its child canvases")
    (is (and (str/includes? html "data-graph-status")
             (str/includes? html "data-graph-detail")
             (str/includes? html
                            "Select a reference assertion for details."))
        "bounded acquisition status remains separate from interaction detail")
    (is (str/includes? html "\\u003c\\/script>\\u003cselected>"))
    (is (not (str/includes? html "</script><selected>")))))

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
          (db/transact! connection
                        [{:seon.ns/name namespace-name
                          :seon.ns/doc "debug-live-subject-marker"}])
          (is (str/includes?
               (read-until! stream "debug-live-subject-marker")
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
            calls (atom 0)
            observe data/entity-observation]
        (with-redefs [data/entity-observation
                      (fn [observation-request]
                        (swap! calls inc)
                        (observe observation-request))]
          (let [first-result ((web-private 'acquire-debug-data)
                              @connection request {})
                retained {(:seon.render.web/debug-data-call-id first-result)
                          (:seon.render.web/debug-data-entry first-result)}]
            ((web-private 'acquire-debug-data) @connection request retained)
            (is (= 1 @calls)
                "unchanged debug reads reuse the retained observation")))))))

(deftest unrelated-transaction-reuses-debug-observation-and-render-call
  (with-server
    (fn [connection server context]
      (let [counts (atom {:observation 0 :discovery 0 :invocation 0})
            observe data/entity-observation
            discover render/selection
            invoke render/render-html]
        (with-redefs [data/entity-observation
                      (fn [request]
                        (swap! counts update :observation inc)
                        (observe request))
                      render/selection
                      (fn [request]
                        (swap! counts update :discovery inc)
                        (discover request))
                      render/render-html
                      (fn [request]
                        (swap! counts update :invocation inc)
                        (invoke request))]
          (let [subject (URLEncoder/encode
                         (pr-str [:seon.ns/name 'seon.flow]) "UTF-8")
                stream (open-feed
                        server
                        (str "/feed/seon.flow?debug=true&viewer=seon.flow"
                             "&subject=" subject
                             "&output=%3Aseon.render%2Fhtml"))]
            (try
              (read-patches! stream 1)
              (let [before @counts
                    pass-before (derivations context)]
                (is (= {:observation 1 :discovery 1 :invocation 1} before)
                    "the initial page acquires and renders exactly once")
                (db/transact!
                 connection
                 [{:seon.cluster.message/id "debug-cache-unrelated"
                   :seon.cluster.message/content
                   "does not affect the inspected namespace"}])
                (await-ping!
                 context
                 #(< pass-before (:seon.render.web/passes %))
                 [:unrelated-debug-render-wake])
                (is (= before @counts)
                    "the database wake reuses observation, discovery, and invocation")
                (let [pass-after-unrelated (derivations context)]
                  (db/transact!
                   connection
                   [{:seon.ns/name 'seon.flow
                     :seon.ns/doc "debug-cache-relevant"}])
                  (await-ping!
                   context
                   #(< pass-after-unrelated (:seon.render.web/passes %))
                   [:relevant-debug-render-wake])
                  (is (= {:observation 2 :discovery 2 :invocation 2}
                         @counts)
                      "a selected-data change reacquires and renders exactly once")))
              (finally (.close stream)))))))))

(deftest runtime-evaluation-cannot-be-displaced-by-a-database-wake
  (with-server
    (fn [_connection server context]
      (let [phase (atom 0)
            calls (atom 0)
            original (web-private 'debug-page-result)
            decorated
            (fn [& args]
              (swap! calls inc)
              (let [result (apply original args)
                    marker (str "runtime-eval-phase-" @phase)]
                (update-in result
                           [:seon.render.web/page
                            "debug-inspection-header"]
                           str/replace
                           "</header>"
                           (str "<span>" marker "</span></header>"))))]
        (with-redefs-fn
          {(ns-resolve 'seon.render.web 'debug-page-result) decorated}
          (fn []
            (let [subject (URLEncoder/encode
                           (pr-str [:seon.ns/name 'seon.flow]) "UTF-8")
                  stream (open-feed
                          server
                          (str "/feed/seon.flow?debug=true&viewer=seon.flow"
                               "&subject=" subject
                               "&output=%3Aseon.render%2Fhtml"))]
              (try
                (read-until! stream "runtime-eval-phase-0")
                (doseq [next-phase [1 2]]
                  (reset! phase next-phase)
                  (is (async/offer! (:runtime-eval-channel context)
                                    :seon.render.web/runtime-eval))
                  ;; The ordinary database wake has a different newest-value
                  ;; buffer, so it cannot replace the pending code signal.
                  (is (async/offer! (:render-channel context)
                                    :seon.cluster.wake/render))
                  (is (str/includes?
                       (read-until! stream
                                    (str "runtime-eval-phase-" next-phase))
                       (str "runtime-eval-phase-" next-phase))))
                (is (= 3 @calls)
                    "the initial paint and both code changes rederive once")
                (finally (.close stream))))))))))

(deftest runtime-evaluation-drops-a-closed-debug-pages-package
  (with-server
    (fn [_connection server context]
      (let [phase (atom 0)
            calls (atom {})
            original (web-private 'debug-page-result)
            decorated
            (fn [& args]
              (let [viewer (:seon.render.debug/viewer-namespace (nth args 2))
                    marker (str viewer "-runtime-phase-" @phase)]
                (swap! calls update viewer (fnil inc 0))
                (update-in (apply original args)
                           [:seon.render.web/page
                            "debug-inspection-header"]
                           str/replace
                           "</header>"
                           (str "<span>" marker "</span></header>"))))
            subject (URLEncoder/encode
                     (pr-str [:seon.ns/name 'seon.flow]) "UTF-8")
            feed-url (fn [viewer]
                       (str "/feed/" agent-id
                            "?debug=true&viewer=" viewer
                            "&subject=" subject
                            "&output=%3Aseon.render%2Fhtml"))]
        (with-redefs-fn
          {(ns-resolve 'seon.render.web 'debug-page-result) decorated}
          (fn []
            (let [closed-view (open-feed server (feed-url "my.plan"))
                  active-view (open-feed server (feed-url "seon.flow"))]
              (try
                (read-until! closed-view "my.plan-runtime-phase-0")
                (read-until! active-view "seon.flow-runtime-phase-0")
                (.close closed-view)
                (support/await-event!
                 (future
                   (loop []
                     (if (some (fn [[registration-key _tabs]]
                                 (= 'my.plan
                                    (get-in registration-key
                                            [1 :seon.render.debug/viewer-namespace])))
                               @(:registration context))
                       (recur)
                       true)))
                 [:closed-debug-registration-removed])
                (reset! phase 1)
                (is (async/offer! (:runtime-eval-channel context)
                                  :seon.render.web/runtime-eval))
                (read-until! active-view "seon.flow-runtime-phase-1")
                (is (= 1 (get @calls 'my.plan))
                    "the closed page was not derived for the code event")
                (let [reopened (open-feed server (feed-url "my.plan"))]
                  (try
                    (is (str/includes?
                         (read-until! reopened "my.plan-runtime-phase-1")
                         "my.plan-runtime-phase-1")
                        "reopen derives current code at an unchanged database basis")
                    (is (= 2 (get @calls 'my.plan))
                        "the closed page's old package was not reused")
                    (finally (.close reopened))))
                (.close active-view)
                (support/await-event!
                 (future
                   (loop []
                     (if (seq @(:registration context))
                       (recur)
                       true)))
                 [:all-debug-registrations-removed])
                (reset! phase 2)
                (let [passes-before (derivations context)]
                  (is (async/offer! (:runtime-eval-channel context)
                                    :seon.render.web/runtime-eval))
                  (await-ping! context
                               #(< passes-before
                                   (:seon.render.web/passes %))
                               [:unwatched-runtime-eval])
                  (is (empty? @(:latest-packages context))
                      "a code event with no watchers publishes an empty cache"))
                (let [reopened (open-feed server (feed-url "my.plan"))]
                  (try
                    (is (str/includes?
                         (read-until! reopened "my.plan-runtime-phase-2")
                         "my.plan-runtime-phase-2")
                        "a new subscriber after an unwatched event derives current code")
                    (is (= 3 (get @calls 'my.plan))
                        "no package survived the zero-watcher code event")
                    (finally (.close reopened))))
                (finally (.close active-view))))))))))

;;; ---------------------------------------------------------------------------
;;; The wire — the rung's claim
;;; ---------------------------------------------------------------------------

;; DELETED 2026-08-29 (owner gate ruling): the wire/initial-paint/
;; thinking-stream transcript machinery is wave-G territory (rip-out
;; register #16-17, #19; rulings 52-53 rebuild the seam) — tests
;; asserting the doomed mechanism are parked here, replaced by wave
;; G's own acceptance, not polished.

(deftest a-feed-writer-failure-enters-the-cluster-fault-path
  (with-server
    (fn [_connection server context]
      (let [send! http/send!]
        (with-redefs [http/send!
                      (fn [channel content close-after-send?]
                        (if (bytes? content)
                          (throw (ex-info "injected writer failure" {}))
                          (send! channel content close-after-send?)))]
        (let [stream (open-feed server (str "/feed/" agent-id))]
          (try
            (let [fault (support/await-event!
                         (:fault-channel context)
                         [:feed-writer-fault])
                  data (ex-data (:clojure.core.async.flow/ex fault))]
              (is (= :seon.render.web/feed
                     (:clojure.core.async.flow/pid fault)))
              (is (= agent-id (:seon.cluster.agent/id fault)))
              (is (string? (:seon.render.web/tab-id data)))
              (is (= agent-id (:seon.render.web/page data)))
              (is (= :seon.render/html (:seon.render/output data))))
            (finally (.close stream)))))))))

(deftest reconnect-is-repaint
  ;; Nothing rendered is stored, so a new connection derives the current
  ;; page from current facts — which is also what makes a killed process
  ;; cost nothing.
  (with-server
    (fn [connection server _context]
      (let [first-stream (open-feed server (str "/feed/" agent-id))]
        (read-complete-paint! first-stream connection)
        (.close first-stream))
      (db/transact! connection
                  [{:seon.ns/name 'my.agents.root
                    :seon.ns/source "(ns my.agents.root)\n(def current true)"}])
      (let [second-stream (open-feed server (str "/feed/" agent-id))]
        (try
          (let [repaint (read-complete-paint! second-stream connection)]
            (is (str/includes? repaint "def current true")
                "at the CURRENT basis, not the one the first tab saw"))
          (finally (.close second-stream)))))))

(deftest two-tabs-each-get-their-own-complete-paint
  (with-server
    (fn [_connection server context]
      ;; The document join settles one current fact-only package first.
      (is (= 200 (.statusCode (fetch server "/"))))
      (let [package (get @(:latest-packages context) agent-id)
            keyframe (:seon.render.package/keyframe-bytes package)
            before (derivations context)
            send! http/send!
            sent (atom [])]
        (with-redefs [hiccup/->string
                      (fn [& _] (throw (ex-info "serialized on join" {})))
                      http/send!
                      (fn [channel content close-after-send?]
                        (when (bytes? content) (swap! sent conj content))
                        (send! channel content close-after-send?))]
          (let [a (open-feed server (str "/feed/" agent-id))
                b (open-feed server (str "/feed/" agent-id))]
            (try
              (let [to-a (read-patches! a 1)
                    to-b (read-patches! b 1)]
                (is (= to-a to-b)
                    "each tab receives the same complete keyframe event")
                (is (= 2 (count @sent)))
                (is (every? #(identical? keyframe %) @sent)
                    "both writers receive the exact cached byte array")
                (is (= before (derivations context))
                    "joining a current package performs no render pass"))
              (finally (.close a) (.close b)))))))))

;;; ---------------------------------------------------------------------------
;;; The F2 sealed suite — seeds 2026072822, 2026072823, 2026072824, 2026072828
;;; ---------------------------------------------------------------------------

;;; 2. render-proc-one-derivation-many-tabs-test — seed 2026072822

(deftest render-proc-one-derivation-many-tabs-test
  (with-server
    (fn [connection server context]
      (let [tabs (mapv (fn [_] (open-feed server (str "/feed/" agent-id)))
                       (range 4))]
        (try
          (doseq [tab tabs] (read-complete-paint! tab connection))
          (let [before (derivations context)]
            (db/transact! connection
                        [{:seon.ns/name 'my.agents.root
                          :seon.ns/source "(ns my.agents.root)\n(def shared true)"}])
            (let [morphs (mapv #(read-until! % "def shared true") tabs)]
              (is (= 1 (count (distinct morphs)))
                  "byte-identical across every tab, because one
                   derivation produced them all")
              (is (<= 1 (- (derivations context) before) 2)
                  "tabs share the proc derivation rather than deriving per tab")))
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
              (db/transact! connection
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
            failure
            (with-redefs
             [http/run-server
              (fn [_handler _options]
                (throw (BindException. "injected ephemeral bind failure")))]
              (try
                (web/start!
                 {:seon.store/connection-object connection
                  :seon.cluster.agent/id agent-id
                  :seon.sci.admit/caps caps
                  :seon.cluster.run/process process
                  :seon.render.web/pages-mult (async/mult pages-channel)
                  :seon.render.web/registration (atom {})
                  :seon.render.web/latest-packages (atom {})
                  :seon.render.web/render-channel render-channel
                  :seon.render.web/fault-channel fault-channel
                  :seon.render.web/port 0})
                nil
                (catch Throwable thrown thrown)))]
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
  ;; HELD inside its own serialization while a newer fact commits, so
  ;; the stale publication is guaranteed to be what arrives first.
  (with-server
    (fn [connection server _context]
      ;; one tab already watching, so a commit always costs a real pass
      (let [watching (open-feed server (str "/feed/" agent-id))]
        (try
          (read-complete-paint! watching connection)
          (let [entered (CountDownLatch. 1)
                holding (CountDownLatch. 1)
                first-pass (atom true)
                walk render.walk/neighborhood]
            (with-redefs [render.walk/neighborhood
                          (fn [request]
                            (when (compare-and-set! first-pass true false)
                              (.countDown entered)
                              (.await holding))
                            (walk request))]
              (db/transact! connection
                            [{:seon.ns/name 'my.agents.root
                              :seon.ns/source
                              "(ns my.agents.root)\n(def superseded true)"}])
              (support/await-event! entered [:pass-held-mid-derivation])
              ;; the held pass can no longer describe the facts
              (db/transact! connection
                            [{:seon.ns/name 'my.agents.root
                              :seon.ns/source
                              "(ns my.agents.root)\n(def connected true)"}])
              (let [fresh (open-feed server (str "/feed/" agent-id))]
                (try
                  (.countDown holding)
                  (let [paint (read-complete-paint! fresh connection)]
                    (is (str/includes? paint "def connected true")
                        "the initial paint is derived at or after the basis
                         this tab connected at")
                    (is (not (str/includes? paint "def superseded true"))
                        "the in-flight pass that published first was refused"))
                  (finally (.close fresh))))))
          (finally (.close watching)))))))

;;; 4. reconnect-is-repaint-wire-test — seed 2026072824

(deftest reconnect-is-repaint-wire-test
  ;; ORACLE: the in-process kill projection — drop the taps and the
  ;; channel contents mid-flight, then reopen the feed. The initial
  ;; paint derives EVERY block from current facts. The database holds no
  ;; partial text at ANY basis (an as-of walk over the window), and
  ;; nothing is retracted because nothing was ever written: after F2 no
  ;; partial row CAN exist, so the stale-partial repair class is
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
                    {:seon.cluster.agent/id agent-id
                     :seon.ai/partial {:seon.ai/text "half a re"
                                       :seon.ai/tokens 3}})
      (async/poll! (:stream-channel context))
      (db/transact! connection
                  [{:seon.ns/name 'my.agents.root
                    :seon.ns/source
                    "(ns my.agents.root)\n(def after-drop true)"}])
      (let [fresh (open-feed server (str "/feed/" agent-id))]
        (try
          (let [repaint (read-complete-paint! fresh connection)]
            (is (str/includes? repaint "def after-drop true")
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
          ;; every REAL basis in the window — Datahike transaction ids
          ;; start above 536870912, so the walk asks the facts which
          ;; bases exist rather than counting from one
          (doseq [t (sort (db/q '[:find [?tx ...]
                                 :where [?tx :db/txInstant _]]
                               db))]
            (is (empty?
                 (filter #(= :seon.ai.stream/text (:a %))
                         (db/datoms (db/as-of db t) :eavt)))
                (str "no partial row at basis " t))))))))

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
        (db/transact! connection
                    [{:seon.cluster.agent/id "agent-b"}
                     {:seon.cluster.run/id run-b
                      :seon.cluster.run/agent
                      [:seon.cluster.agent/id "agent-b"]
                      :seon.cluster.run/opened-at (java.util.Date.)}])
        (let [tab (open-feed server (str "/feed/" agent-id))]
        (try
          (is (not (str/includes? (read-complete-paint! tab connection)
                                  "A half reply"))
              "the fact-only initial paint has no partial")
          (await-ping! context
                       #(= 1 (:seon.render.web/watched-agents %))
                       [:initial-fact-paint-derived])

          (async/offer! (:stream-channel context)
                        {:seon.cluster.agent/id agent-id
                         :seon.cluster.run/id run-a
                         :seon.ai/partial {:seon.ai/text "A half reply"
                                           :seon.ai/tokens 3}})
          (let [partial (read-until! tab "A half reply")]
            (is (str/includes? partial "A half reply"))
            (is (str/includes? partial ">3<")))

          ;; This is the displacing value from the audit ordering. It
          ;; changes B's transient entry but cannot carry semantics for A.
          (async/offer! (:stream-channel context)
                        {:seon.cluster.agent/id "agent-b"
                         :seon.cluster.run/id run-b
                         :seon.ai/partial {:seon.ai/text "B newest"
                                           :seon.ai/tokens 2}})
          (await-ping! context
                       #(= 2 (:seon.render.web/streaming-agents %))
                       [:both-partials-admitted])

          ;; The frozen plan is the settled provider reply fact. Its
          ;; normal database wake is the stream terminal.
          (db/transact! connection
                      [[:db/add [:seon.cluster.run/id run-a]
                        :seon.cluster.run/plan-digest
                        (apply str (repeat 64 "a"))]])
          (await-ping! context
                       #(zero? (:seon.render.web/streaming-agents %))
                       [:terminal-fact-cleared-partials])
          (let [settled (read-until! tab "id=\"surface-stream\"></div>")]
            (is (not (str/includes? settled "A half reply"))
                "A's stale half-reply cannot survive the terminal fact"))
          (is (= 0 (streaming-agents context))
              "the fact-only interest pass retained no channel state")

          (testing "a delayed partial cannot repaint over its terminal fact"
            (let [before (derivations context)]
              (async/offer! (:stream-channel context)
                            {:seon.cluster.agent/id agent-id
                             :seon.cluster.run/id run-a
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
                        {:seon.cluster.agent/id agent-id
                         :seon.cluster.run/id run-id
                         :seon.ai/partial {:seon.ai/text "not durable"
                                           :seon.ai/tokens 2}})
          (is (str/includes? (read-until! first-tab "not durable")
                             "not durable"))
          (.close first-tab))
        (let [reconnected (open-feed server (str "/feed/" agent-id))]
          (try
            (let [repaint (read-complete-paint! reconnected connection)]
              (is (str/includes? repaint "id=\"surface-stream\"></div>")
                  "reconnect repainted the fact-only stream strip")
              (is (not (str/includes? repaint "not durable"))
                  "the in-flight partial was never restored"))
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
      (db/transact! connection [{:seon.config/cluster "web-test"
                               :seon.config.render/coalesce-ms 250}])
      (let [tab (open-feed server (str "/feed/" agent-id))]
        (try
          (read-complete-paint! tab connection)
          ;; the dial's own commit changes no block, so suppression
          ;; correctly puts NOTHING on the wire for it — the tab is
          ;; already settled
          (let [before (derivations context)
                m 6]
            (doseq [n (range m)]
              (db/transact! connection
                          [{:seon.ns/name 'my.agents.root
                            :seon.ns/source
                            (str "(ns my.agents.root)\n(def burst " n ")")}]))
            (let [settled (read-until! tab "def burst 5")]
              (is (< (patches settled) m)
                  (str "the tab saw " (patches settled) " repaints for "
                       m " commits — the burst coalesced")))
            (let [passes (- (derivations context) before)]
              (is (< passes m)
                  (str "the floor coalesced " m " commits into " passes
                       " derivations for the whole cluster"))))
          (finally (.close tab)))))))

(deftest the-pass-oracle-observes-a-derivation-longer-than-flows-ping-window
  ;; THE CLASS: `flow/ping` replies only for procs that answer inside
  ;; its 1000 ms window, and it answers on the proc's own transform
  ;; loop. A proc that is mid-derivation is simply absent from the
  ;; result — which every oracle in this namespace used to read as a
  ;; state map, throwing NullPointerException on `(zero? nil)` and
  ;; `(- nil before)` (two errors in one run, 2026-08-07). The wanted
  ;; behavior is that the oracle OBSERVES the proc's answer.
  ;;
  ;; The busy window is produced by the production dial, not by a
  ;; redefinition: the proc waits out the coalescing floor INSIDE its
  ;; transform, so a floor above flow's window makes the missed ping
  ;; certain rather than load-dependent.
  (with-server
    (fn [connection server context]
      (let [tab (open-feed server (str "/feed/" agent-id))]
        (try
          (read-complete-paint! tab connection)
          (db/transact! connection [{:seon.config/cluster "web-test"
                                     :seon.config.render/coalesce-ms 1500}])
          (let [before (derivations context)]
            (is (number? before)
                "the pass count is the proc's own answer, never a missed ping")
            (db/transact! connection
                          [{:seon.ns/name 'my.agents.root
                            :seon.ns/source
                            "(ns my.agents.root)\n(def floored true)"}])
            (read-until! tab "def floored true")
            (is (< (long before) (long (derivations context)))
                "and it counts the pass the floor held past that window"))
          (finally (.close tab)))))))

;;; ---------------------------------------------------------------------------
;;; Suppression, as a pure unit
;;; ---------------------------------------------------------------------------

(deftest data-uses-the-one-floor-and-keeps-the-cursor-in-the-url
  ;; A drilled position is a LINK, so the proof is that following one
  ;; lands somewhere different from the root.
  (with-server
    (fn [_connection server context]
      (let [seen-contexts (atom [])
            context-projection sci.kernel/context-projection]
        (with-redefs [sci.kernel/context-projection
                      (fn [ctx]
                        (swap! seen-contexts conj ctx)
                        (context-projection ctx))]
          (let [response (fetch server "/data")
                root (.body response)]
            (is (= 200 (.statusCode response)))
            (is (str/includes? root "seon-data-panel"))
            (is (str/includes? root "showing 1")
                "a window, and it says so")))
        (is (seq @seen-contexts)
            "recursive producer selection consults the SCI context")
        (is (every? #(identical? (:ctx context) %) @seen-contexts)
            "the route supplies the cluster's one live SCI context"))
      (testing "a stale or mangled cursor shows the root rather than failing"
        (let [response (fetch server "/data?path=%7Bbroken&offset=nope")]
          (is (= 200 (.statusCode response)))
          (is (str/includes? (.body response) "seon-data-panel")))))))

(deftest data-resolves-an-entity-root-and-preserves-it-in-floor-links
  (with-server
    (fn [connection server _context]
      (db/transact! connection
                  [{:seon.cluster.agent/id "alice"}])
      (let [response
            (fetch server
                   "/data?entity=%5B%3Aseon.cluster.agent%2Fid+%22alice%22%5D&path=%5B%5D&offset=0")
            body (.body response)
            default-body (.body (fetch server "/data"))]
        (is (= 200 (.statusCode response)))
        (is (str/includes? body "seon-data-panel"))
        (is (str/includes? body "alice")
            "the pulled entity renders (the declared-producer face is the
            filed registered-render-producers S2 issue, not re-pinned here)")
        (is (str/includes? default-body ":seon.ai.attempt/at")
            "without entity the schema vector remains the drill root")
        (is (str/includes? body
                           "entity=%5B%3Aseon.cluster.agent%2Fid+%22alice%22%5D")
            "every floor handle preserves the selected entity root")))))

(deftest data-selects-a-stored-value-artifact-by-digest
  (with-server
    (fn [connection server _context]
      (let [stored (-> (admit/admit-value
                        {:seon.sci.admit/value {:alpha [1 2 3]}
                         :seon.sci.admit/interrupt-fn (fn [])
                         :seon.sci.admit/caps caps
                         :seon.config/on-core-error :record})
                       value/artifact
                       value/artifact-edn)
            digest (blob/put! connection stored)
            response (fetch server (str "/data?value=" digest))
            body (.body response)]
        (is (= 200 (.statusCode response)))
        (is (str/includes? body ":alpha"))
        (is (str/includes? body (str "value=" digest)))))))

(deftest data-caps-a-five-megabyte-attribute-through-the-shared-floor
  (with-server
    (fn [connection server _context]
      (let [namespace-name 'my.agents.w3-data-cap
            huge (apply str (repeat (* 5 1024 1024) "x"))
            entity (URLEncoder/encode
                    (pr-str [:seon.ns/name namespace-name]) "UTF-8")
            path (URLEncoder/encode (pr-str [:seon.ns/source]) "UTF-8")]
        (db/transact! connection
                    [{:seon.ns/name namespace-name :seon.ns/source huge}])
        (let [response (fetch server (str "/data?entity=" entity
                                          "&path=" path "&offset=0"))
              body (.body response)]
          (is (= 200 (.statusCode response)))
          (is (< (count body) 300000))
          (is (str/includes? body "elided"))
          (is (str/includes? body "seon-data-panel"))
          (is (str/includes? body (str "entity=" entity))
              "the capped value retains a handle back to the same root"))))))

(defn- debug-feed-path
  [agent-id path]
  (str "/feed/" agent-id "?debug=true&path="
       (URLEncoder/encode (pr-str path) "UTF-8")
       "&offset=0"))

(deftest each-agent-has-an-isolated-debug-route
  (with-server
    (fn [connection server _context]
      (db/transact! connection
                  (cluster.agent/creation-tx
                   {:seon.cluster.agent/id "alice"
                    :seon.cluster/name "web-test"
                    :seon.ns/name 'my.agents.alice}))
      (let [agent-page (.body (fetch server "/agent/root"))
            root (.body (fetch server "/agent/root/debug"))
            alice (.body (fetch server "/agent/alice/debug"))]
        (is (str/includes? agent-page "/agent/root/debug")
            "the always-available debug view is linked from the curated page")
        (is (str/includes? root "debug=true"))
        (is (str/includes? root "include agent prompt comparison")
            "the expensive prompt comparison is an explicit secondary view")
        (is (not (str/includes? root "id=\"debug-ai-root\"")))
        (is (str/includes? alice "/feed/alice"))
        (is (not= root alice) "the stable root address includes the agent"))
      (is (= 404 (.statusCode (fetch server "/agent/missing/debug")))))))

(deftest debug-drills-three-levels-and-includes-apparatus
  (with-server
    (fn [connection server _context]
      (db/transact! connection
                  {:tx-data [{:seon.cluster.agent/id "debug-trigger"}]
                   :tx-meta {:seon.db/user
                             [:seon.cluster.agent/id agent-id]}})
      (doseq [[path needle]
              [[[:seon.render.debug/reverse-refs :seon.db/user 0]
                ":db/txInstant"]]]
        (let [stream (open-feed server (debug-feed-path agent-id path))]
          (try
            (let [paint (read-patches! stream 1)]
              (is (str/includes? paint needle)
                  (str "the debug floor exposes " (pr-str path))))
            (finally (.close stream))))))))

;;; ---------------------------------------------------------------------------
;;; Slice 1 — one POST, the existing route and render chain
;;; ---------------------------------------------------------------------------

(deftest the-message-appears-on-the-page-wire-test
  ;; seed 2026072903 — reverse refs do the echo; no message-specific page code.
  (with-server
    (fn [connection server _context]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (try
          (read-complete-paint! stream connection)
          (let [response (post-form server
                                    (str "/agent/" agent-id "/message")
                                    "content=wire-echo-2026072903")]
            (is (= 204 (.statusCode response)))
            (is (empty? (.body response))))
          (let [paint (read-until! stream "wire-echo-2026072903")]
            (is (str/includes? paint "surface-transcript"))
            (is (str/includes? paint "wire-echo-2026072903"))
            (is (not (str/includes? paint "surface-message-bar"))
                "the existing reverse-ref render changes; the bar does not"))
          (finally (.close stream)))))))

(deftest the-inbound-route-is-method-discriminated-test
  ;; seed 2026072905 — the former prefix-dispatch shadow class.
  (with-server
    (fn [connection server _context]
      (db/transact! connection [{:seon.cluster.agent/id "bob"}])
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
            (is (not (str/includes? (.body response)
                                    "datastar-patch-elements"))
                "the HTTP refusal is text, never a competing morph"))
          (finally (.close stream)))))))

(deftest transaction-refusals-map-to-http-without-success
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "web-write-refusal")
      (db/transact! connection
                  (cluster.agent/creation-tx
                   {:seon.cluster.agent/id agent-id
                    :seon.cluster/name "web-write-refusal"
                    :seon.ns/name 'my.agents.root}))
      (let [service {:seon.store/connection-object connection
                     :seon.cluster.agent/id agent-id
                     :seon.sci.admit/caps caps
                     :seon.cluster.run/process process}
            inbound {:seon.cluster.agent/id agent-id
                     :seon.cluster.message/inbound-content "accepted"}]
        (doseq [[result expected-status]
                [[{:seon.error/kind :seon.db/rejected
                   :seon.error/message "dependency refusal"}
                  422]
                 [{:seon.error/kind :seon.db/unknown-failure
                   :seon.error/message "core failure"}
                  500]]]
          (let [response (with-redefs [db/transact! (fn [& _] result)]
                           (web/inbound service inbound))]
            (is (= expected-status (:status response)))
            (is (= (:seon.error/message result) (:body response)))))))))

(deftest start-refuses-a-flat-process-write-before-binding
  (support/with-database
    (fn [connection]
      (let [result
            (with-redefs [db/q (fn [& _] nil)
                          db/transact!
                          (fn [& _]
                            {:seon.error/kind :seon.db/rejected
                             :seon.error/message "injected process refusal"})]
              (support/refusal-data
               #(web/start! {:seon.store/connection-object connection
                             :seon.cluster.run/process process})))]
        (is (= :seon.db/rejected (:seon.error/kind result)))
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
                    [?message :seon.cluster.message/content "forged"]]
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
  (with-server
    (fn [connection first-server _graph]
      (let [taken (:seon.render.web/port first-server)
            second-server (web/start!
                           {:seon.store/connection-object connection
                            :seon.cluster.agent/id agent-id
                            :seon.sci.admit/caps caps
                            :seon.cluster.run/process process
                            ;; its own disposable view half: this test
                            ;; is about the PORT, and the second view
                            ;; never opens a feed
                            :seon.render.web/pages-mult
                            (async/mult (async/chan (async/sliding-buffer 1)))
                            :seon.render.web/registration (atom {})
                            :seon.render.web/latest-packages (atom {})
                            :seon.render.web/render-channel
                            (async/chan (async/sliding-buffer 1))
                            :seon.render.web/fault-channel
                            (async/chan (async/dropping-buffer 1))
                            :seon.render.web/port taken})]
        (try
          (is (not= taken (:seon.render.web/port second-server))
              "it bound somewhere else")
          (is (= taken (:seon.render.web/wanted-port second-server))
              "and it says which bookmark just stopped working")
          (is (= 200 (.statusCode (fetch second-server "/css/input.css")))
              "while serving normally — the collision costs a port, not a view")
          (finally (web/stop! second-server))))))
  (testing "a clean bind reports no wanted-port at all, so key presence
            answers 'did this fall back?'"
    (with-server
      (fn [_connection server _context]
        (is (nil? (:seon.render.web/wanted-port server)))))))
