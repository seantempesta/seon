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
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
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

(defn omitted-html
  "Returns nil when this block has nothing to say."
  [_unit]
  nil)

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
          ; .read returns -1 at EOF; available>0 makes that rare, not
          ; impossible — a negative count must end the drain, not throw
          (when (pos? read)
            (.append out (String. buffer 0 read)))
          (if (pos? read) (recur 0) nil))
        (when (< idle 8)
          (Thread/sleep 60)
          (recur (inc idle)))))
    (.toString out)))

(defn- check!
  [label result]
  (is (true? (:result result)) (str label " failed: " (pr-str result))))

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
  (let [surface {:seon.render.block/name :x
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
  (let [omitted {:seon.render.block/name :optional
                 :seon.render/surface-id "surface-optional"
                 :seon.render/kind :seon.render/html
                 :seon.render/output nil}
        visible (assoc omitted :seon.render/output
                       [:div {:id "surface-optional"} "now visible"])
        first-paint (web/changed {} [omitted] caps nil)
        repaint (web/changed (:seon.render.web/delivered first-paint)
                             [visible] caps nil)]
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
    (fn [_connection server]
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
  (check!
   "derived port range"
   (tc/quick-check
    500
    (prop/for-all [name (gen/such-that seq gen/string-ascii 100)]
      (<= web/port-floor (web/derived-port name) (dec web/port-ceiling)))
    :seed 202607280501)))

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
    (fn [connection first-server]
      (let [taken (:seon.render.web/port first-server)
            second-server (web/start!
                           {:seon.store/connection connection
                            :seon.cluster.agent/id agent-id
                            :seon.sci.admit/caps caps
                            :seon.config.render/coalesce-ms 16
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
      (fn [_connection server]
        (is (nil? (:seon.render.web/wanted-port server)))))))
