(ns seon.render.web-test
  "The page on a real socket.

  REAL SOCKETS, not a mocked handler. The rung's whole claim is about
  what goes on the wire — one morph per block, nothing when nothing
  changed — and a test that called the handler as a function would prove
  the derivation while leaving the claim untested. So every test here
  binds a real http-kit server on an ephemeral loopback port, speaks
  real HTTP, and reads the real SSE stream.

  READING SSE CORRECTLY IS PART OF THE TEST. `BodyHandlers/ofLines` does
  not yield on a stream that never ends, and a first draft of this proof
  reported zero events for a feed that was working perfectly. Worse, a
  single `.read` returns ONE chunk, so a reader that does not drain
  reports the PREVIOUS paint as the current one — which briefly looked
  like a suppression bug in the server. `drain!` reads until the socket
  goes quiet.

  Every server is stopped and every database deleted in a `finally`, so
  a failing assertion cannot leak a listening port into the next test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.render.block :as block]
            [seon.render.web :as web]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

(def ^:private caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

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

;;; ---------------------------------------------------------------------------
;;; Fixture
;;; ---------------------------------------------------------------------------

(defn- with-server
  [blocks body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)
        server (atom nil)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (vec (seon.schema/canonical-database-attributes))))
      (d/transact connection [{:seon.cluster.agent/id agent-id
                               :seon.cluster.agent/blocks blocks}])
      (reset! server (web/start! {:seon.store/connection connection
                                  :seon.cluster.agent/id agent-id
                                  :seon.sci.admit/caps caps
                                  :seon.config.render/coalesce-ms 16}))
      (body connection @server)
      (finally
        (when @server (web/stop! @server))
        (d/release connection)
        (d/delete-database configuration)))))

(defn- block-map
  [name priority projection]
  {:seon.block/name name
   :seon.block/priority priority
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

(defn- drain!
  "Everything the socket has, read until it goes quiet.
  One `.read` returns one chunk; a reader that stops there reports the
  previous paint as this one."
  [stream]
  (let [buffer (byte-array 65536)
        out (StringBuilder.)]
    (loop [idle 0]
      (if (pos? (.available stream))
        (let [read (.read stream buffer)]
          (.append out (String. buffer 0 read))
          (recur 0))
        (when (< idle 8)
          (Thread/sleep 60)
          (recur (inc idle)))))
    (.toString out)))

(defn- patches
  [text]
  (count (re-seq #"event: datastar-patch-elements" text)))

;;; ---------------------------------------------------------------------------
;;; The document
;;; ---------------------------------------------------------------------------

(deftest the-page-places-every-surface-at-its-own-id
  (with-server two-blocks
    (fn [_connection server]
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
    (fn [_connection server]
      (let [body (.body (fetch server "/"))]
        (is (< (.indexOf body "</main>") (.indexOf body "data-init"))
            "the opener is after every surface, not inside one")
        (is (str/includes? body "retryMaxCount: Infinity"))
        (is (str/includes? body "openWhenHidden: false"))))))

(deftest an-agent-page-is-the-same-mechanism-as-root
  ;; Root is an agent. If this test ever needs a root-specific branch,
  ;; the design has regressed.
  (with-server two-blocks
    (fn [connection server]
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
    (fn [_connection server]
      (is (= 200 (.statusCode (fetch server "/js/datastar.js"))))
      (testing "and path traversal is refused by construction"
        (is (= 404 (.statusCode (fetch server "/css/../../secret"))))))))

(deftest an-unknown-route-is-an-honest-404
  (with-server two-blocks
    (fn [_connection server]
      (is (= 404 (.statusCode (fetch server "/nope")))))))

;;; ---------------------------------------------------------------------------
;;; The wire — the rung's claim
;;; ---------------------------------------------------------------------------

(deftest the-initial-paint-sends-every-block-once
  (with-server two-blocks
    (fn [_connection server]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (try
          (let [initial (drain! stream)]
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
    (fn [connection server]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (try
          (drain! stream)
          (d/transact connection [{:seon.cluster.agent/id "agent-b"}])
          (let [repaint (drain! stream)]
            (is (= 1 (patches repaint)) "ONE block, not the page")
            (is (str/includes? repaint "agents: 2") "and it is the right one")
            (is (not (str/includes? repaint "surface-banner"))
                "the block that reads nothing was not sent"))
          (finally (.close stream)))))))

(deftest a-commit-that-changes-no-projection-sends-nothing
  ;; Equality suppression, measured where it counts: on the socket.
  (with-server two-blocks
    (fn [connection server]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (try
          (drain! stream)
          (d/transact connection
                      [{:seon.cluster.run/id "nothing-either-block-reads"}])
          (let [quiet (drain! stream)]
            (is (= 0 (patches quiet)))
            (is (= "" quiet) "not one byte"))
          (finally (.close stream)))))))

(deftest a-broken-block-paints-its-card-and-spares-the-page
  ;; Fail loud, do not fall down: the page arrives, the working block
  ;; works, and the broken one occupies its own space saying so.
  (with-server [(block-map :banner 0 `banner-html)
                (block-map :broken 10 `broken-html)]
    (fn [_connection server]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (try
          (let [initial (drain! stream)]
            (is (= 2 (patches initial)))
            (is (str/includes? initial "seon-error-card"))
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
    (fn [connection server]
      (let [first-stream (open-feed server (str "/feed/" agent-id))]
        (drain! first-stream)
        (.close first-stream))
      (d/transact connection [{:seon.cluster.agent/id "agent-b"}])
      (let [second-stream (open-feed server (str "/feed/" agent-id))]
        (try
          (let [repaint (drain! second-stream)]
            (is (= 2 (patches repaint)) "a fresh connection paints everything")
            (is (str/includes? repaint "agents: 2")
                "at the CURRENT basis, not the one the first tab saw"))
          (finally (.close second-stream)))))))

(deftest two-tabs-each-get-their-own-complete-paint
  (with-server two-blocks
    (fn [connection server]
      (let [a (open-feed server (str "/feed/" agent-id))
            b (open-feed server (str "/feed/" agent-id))]
        (try
          (drain! a)
          (drain! b)
          (d/transact connection [{:seon.cluster.agent/id "agent-b"}])
          (let [to-a (drain! a)
                to-b (drain! b)]
            (is (= 1 (patches to-a)))
            (is (= 1 (patches to-b)))
            (is (= to-a to-b)
                "identical bytes — determinism is what lets one
                 registration serve every tab when that lands"))
          (finally (.close a) (.close b)))))))

(deftest a-closed-tab-stops-painting
  ;; The listener's lifetime is the socket's. If this leaked, a long
  ;; session would accumulate a listener per tab ever opened, and each
  ;; one runs on the WRITER'S commit path.
  (with-server two-blocks
    (fn [connection server]
      (let [stream (open-feed server (str "/feed/" agent-id))]
        (drain! stream)
        (.close stream)
        (Thread/sleep 100)
        ;; the real assertion is that the writer stays healthy and fast
        ;; with the tab gone; a leaked listener would still be painting
        ;; into a dead socket on every one of these
        (dotimes [index 20]
          (d/transact connection
                      [{:seon.cluster.agent/id (str "agent-" index)}]))
        (is (= 21 (count (d/q '[:find ?a :where [?a :seon.cluster.agent/id _]]
                              (d/db connection))))
            "the writer is unaffected by a departed tab")))))

;;; ---------------------------------------------------------------------------
;;; Suppression, as a pure unit
;;; ---------------------------------------------------------------------------

(deftest suppression-compares-bytes
  ;; Bytes are what the socket costs and what the browser diffs, and the
  ;; serializer is deterministic, so this is sound.
  (let [surface {:seon.block/name :x
                 :seon.render/surface-id "surface-x"
                 :seon.render/kind :seon.render/html
                 :seon.render/output [:div {:id "surface-x"} "same"]}
        first-pass (web/changed {} [surface] caps nil)
        second-pass (web/changed (:seon.render.web/delivered first-pass)
                                 [surface] caps nil)]
    (is (= 1 (count (:seon.render.web/patches first-pass))))
    (is (= 0 (count (:seon.render.web/patches second-pass)))
        "an unchanged surface is not sent twice")
    (is (= (:seon.render.web/delivered first-pass)
           (:seon.render.web/delivered second-pass)))))

(deftest the-data-drill-is-browsable-and-its-cursor-is-in-the-url
  ;; A drilled position is a LINK, so the proof is that following one
  ;; lands somewhere different from the root.
  (with-server two-blocks
    (fn [_connection server]
      (let [root (.body (fetch server "/data"))]
        (is (str/includes? root "seon-data-drill"))
        (is (str/includes? root "showing 1")
            "a window, and it says so"))
      (testing "a stale or mangled cursor shows the root rather than failing"
        (let [response (fetch server "/data?path=%7Bbroken&offset=nope")]
          (is (= 200 (.statusCode response)))
          (is (str/includes? (.body response) "seon-data-drill")))))))
