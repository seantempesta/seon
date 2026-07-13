(ns seon.store.wire-test
  "Unit tests for `seon.store.wire/ping!`'s bounded retry (unit 5 —
   the `bin/seon start all` race): the pod's boot ping retries the
   wire rpc up to 5 times (~10s) before the existing fail-loud throw.
   Boot stays fail-loud, just not fail-instant.

   `seon.store.internal.wire-node/rpc` is stubbed via root `set!` (same rationale
   as `seon.agent.message-test/with-conn`: dynamic `binding` is popped at the
   first microtask boundary inside `^:async` bodies; the root swap is
   visible across microtasks, tests run serially, restore in
   `.finally`). No real socket is touched.

   Run interactively via MCP eval:
     (require 'seon.store.wire-test :reload)
     (cljs.test/run-tests 'seon.store.wire-test)"
  (:require
   [cljs.core.async :refer [take! poll!]]
   [cljs.test :refer [deftest is async]]
   [datahike.api :as d]
   [datahike.writer :as writer]
   [seon.store.internal.wire-node :as wire]
   [seon.store.wire :as store.wire]))

(defn- with-rpc-stub
  "Run `body` (no-arg fn → Promise) with `wire/rpc` replaced by `stub`
   (called as sock-path req opts → Promise). Restores the original rpc
   in `.finally`.

   The replacement must be MULTI-ARITY: `rpc` is a multi-arity defn, so
   call sites compile to direct `.cljs$core$IFn$_invoke$arity$3` calls
   — a single-arity stub set! onto the var lacks that property and the
   compiled call throws 'arity$3 is not a function'."
  [stub body]
  (let [orig    wire/rpc
        wrapped (fn wrapped-rpc
                  ([req] (stub nil req nil))
                  ([sock-path req] (stub sock-path req nil))
                  ([sock-path req opts] (stub sock-path req opts)))]
    (set! wire/rpc wrapped)
    (-> (js/Promise.resolve (body))
        (.finally (fn [] (set! wire/rpc orig))))))

(declare with-as-of-stub)

(defn- with-feed-stubs
  "Run an async body with the pub connector and paginated replay stubbed."
  [connect-stub replay-stub body]
  (let [original-connect wire/connect-pub
        original-replay  wire/replay-tx
        wrapped-replay   (fn wrapped-replay-tx
                           ([opts] (replay-stub nil opts))
                           ([sock-path opts] (replay-stub sock-path opts)))
        restore!         (fn []
                           (set! wire/connect-pub original-connect)
                           (set! wire/replay-tx original-replay))]
    (set! wire/connect-pub connect-stub)
    (set! wire/replay-tx wrapped-replay)
    (try
      (-> (with-as-of-stub body)
          (.finally restore!))
      (catch :default error
        (restore!)
        (js/Promise.reject error)))))

(defn- channel->promise
  "Resolve a Promise with the one value delivered on a promise-chan."
  [channel]
  (js/Promise.
   (fn [deliver _reject]
     (take! channel deliver))))

(def ^:private fake-store-id
  #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa")

(defn- fake-db
  ([basis-t] (fake-db basis-t :db))
  ([basis-t branch]
   {:max-tx basis-t
    :config {:store {:id fake-store-id}
             :branch branch
             :writer {:backend :seon-wire}}}))

(defn- fake-conn
  "Minimal conn surface used by the wire writer and native listeners."
  ([basis-t]
   (fake-conn basis-t (atom {})))
  ([basis-t listeners]
   (reify
     IDeref
     (-deref [_] (fake-db basis-t))
     IMeta
     (-meta [_] {:listeners listeners}))))

(defn- fake-changing-conn
  "Fake connection whose branch-local head can advance during a test."
  [database listeners]
  (reify
    IDeref
    (-deref [_] @database)
    IMeta
    (-meta [_] {:listeners listeners})))

(defn- dispatch-transaction
  "Drive the real SeonWireWriter branch without Datahike's outer writer loop."
  [conn arg-map]
  (channel->promise
   (writer/-dispatch!
    (store.wire/->SeonWireWriter
     "stub.sock" conn
     (atom {:seon.store.wire/writer-open? true
            :seon.store.wire/writer-pending #{}}))
    {:op 'transact! :args [arg-map]})))

(defn- test-writer
  [conn]
  (store.wire/->SeonWireWriter
   "stub.sock" conn
   (atom {:seon.store.wire/writer-open? true
          :seon.store.wire/writer-pending #{}})))

(defn- success-response
  [basis-t]
  {:seon.store.wire/ok true
   :seon.store.wire/basis-t basis-t
   :seon.store.wire/tempids {}
   :seon.store.wire/tx-data []
   :seon.store.wire/datoms-added 0
   :seon.store.wire/datoms-retracted 0})

(defn- with-wire-state
  "Install hermetic attachment state for an async body, then restore."
  [adapter-state body]
  (let [adapter           @#'store.wire/!adapter
        saved-adapter     @adapter
        restore!          (fn []
                            (store.wire/stop-listen-adapter!)
                            (reset! adapter saved-adapter))]
    (reset! adapter adapter-state)
    (try
      (-> (js/Promise.resolve (body))
          (.finally restore!))
      (catch :default error
        (restore!)
        (js/Promise.reject error)))))

(defn- stopped-state
  []
  {::store.wire/phase ::store.wire/stopped
   ::store.wire/generation 0
   ::store.wire/correlations {}})

(defn- attached-state
  ([conn basis-t] (attached-state conn basis-t ::store.wire/live))
  ([conn basis-t phase]
   (let [coordinate (#'store.wire/connection-coordinate @conn)]
     {::store.wire/phase phase
      ::store.wire/generation 1
      ::store.wire/conn conn
      ::store.wire/database-coordinate coordinate
      ::store.wire/last-applied-coordinate
      (#'store.wire/progress-coordinate coordinate basis-t)
      ::store.wire/sock-path "req.sock"
      ::store.wire/pub-sock-path "pub.sock"
      ::store.wire/own-skips 0
      ::store.wire/correlations {}})))

(defn- adapter-state
  []
  @(deref #'store.wire/!adapter))

(defn- adapter-generation
  []
  (::store.wire/generation (adapter-state)))

(defn- adapter-basis-t
  []
  (get-in (adapter-state)
          [::store.wire/last-applied-coordinate ::store.wire/basis-t]))

(defn- correlations
  []
  (::store.wire/correlations (adapter-state)))

(defn- connect-feed!
  [conn sock-path pub-sock-path on-drop]
  (let [state (adapter-state)]
    (#'store.wire/connect-feed!
     (::store.wire/generation state)
     conn
     (::store.wire/database-coordinate state)
     sock-path
     pub-sock-path
     on-drop)))

(defn- with-as-of-stub
  [body]
  (let [original d/as-of]
    (set! d/as-of (fn [db basis-t] (assoc db :max-tx basis-t)))
    (try
      (-> (js/Promise.resolve (body))
          (.finally #(set! d/as-of original)))
      (catch :default error
        (set! d/as-of original)
        (js/Promise.reject error)))))

(defn- after-macrotask
  "Wait long enough for deferred native listener callbacks to run."
  []
  (js/Promise.
   (fn [deliver _reject]
     (js/setTimeout deliver 25))))

(defn- replay-event
  [db-name basis-t basis-t-before]
  {:seon.store.wire/event "tx"
   :seon.store.wire/db-name db-name
   :seon.store.wire/basis-t basis-t
   :seon.store.wire/basis-t-before basis-t-before
   :seon.store.wire/tx-data
   [[basis-t :seon.store.wire-test/value basis-t basis-t true]]})

(defn- replay-page
  [db-name since-t through-t continuation-t done? events]
  {:seon.store.wire/ok true
   :seon.store.wire/db-name db-name
   :seon.store.wire/since-t since-t
   :seon.store.wire/through-t through-t
   :seon.store.wire/continuation-t continuation-t
   :seon.store.wire/done? done?
   :seon.store.wire/events events
   :seon.store.wire/replayed (count events)})

(deftest wire-writer-shutdown-closes-admission-and-drains-accepted-rpcs
  (async done
    (let [conn (fake-conn 17)
          respond (atom nil)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
              (fn [_sock-path _request _opts]
                (js/Promise.
                 (fn [deliver _reject]
                   (reset! respond deliver))))
              (fn []
                (let [wire-writer (test-writer conn)
                      result (writer/-dispatch!
                              wire-writer
                              {:op 'transact!
                               :args [{:tx-data
                                       [{:seon.store.wire-test/value
                                         "accepted"}]}]})
                      shutdown (writer/-shutdown wire-writer)]
                  (is (nil? (poll! shutdown))
                      "shutdown waits for the already-admitted RPC")
                  (@respond (success-response 17))
                  (-> (channel->promise result)
                      (.then
                       (fn [report]
                         (is (= 17 (get-in report [:db-after :max-tx])))
                         (channel->promise shutdown)))
                      (.then
                       (fn [drained]
                         (is (true? drained))
                         (channel->promise
                          (writer/-dispatch!
                           wire-writer
                           {:op 'transact!
                            :args [{:tx-data []}]}))))
                      (.then
                       (fn [error]
                         (is (instance? js/Error error))
                         (is (re-find #"shut down" (.-message error)))))))))))
          (.catch (fn [error]
                    (is false (str "wire writer drain test threw: " error))))
          (.finally done)))))

(deftest ping-retries-through-transient-failure
  ;; First two rpcs fail (socket not accepting yet — the start-all
  ;; race); the third succeeds. ping! must resolve, not throw.
  (async done
    (let [!calls (atom 0)]
      (-> (with-rpc-stub
            (fn [_sock-path _req _opts]
              (if (< (swap! !calls inc) 3)
                (js/Promise.reject (js/Error. "connect ECONNREFUSED (stub)"))
                (js/Promise.resolve {:seon.store.wire/ok true})))
            (fn [] (store.wire/ping!)))
          (.then (fn [resp]
                   (is (true? (:seon.store.wire/ok resp))
                       "resolves to the reply map once an attempt succeeds")
                   (is (= 3 @!calls)
                       "two failed attempts consumed, third succeeded")))
          (.catch (fn [e]
                    (is false (str "ping! must survive transient failures, threw: "
                                   (.-message e)))))
          (.finally done)))))

(deftest ping-exhausts-budget-then-fails-loud
  ;; Every rpc fails — after the 5-attempt budget the SAME fail-loud
  ;; error throws (boots-only-against-cluster-store is not weakened).
  (async done
    (let [!calls (atom 0)]
      (-> (with-rpc-stub
            (fn [_sock-path _req _opts]
              (swap! !calls inc)
              (js/Promise.reject (js/Error. "connect ECONNREFUSED (stub)")))
            (fn [] (store.wire/ping!)))
          (.then (fn [_]
                   (is false "ping! must throw once the retry budget is exhausted")))
          (.catch (fn [e]
                    (is (= 5 @!calls) "all 5 attempts consumed")
                    (is (re-find #"UNREACHABLE" (.-message e))
                        "fail-loud message preserved")
                    (is (re-find #"after 5 attempts" (.-message e))
                        "message names the exhausted retry budget")
                    (is (= :core-bug (:seon.error/kind (ex-data e)))
                        "error kind unchanged")))
          (.finally done)))))

;; ── FIX 3: the tx-feed pump dispatches each listener ASYNCHRONOUSLY ────────
;; so one slow/throwing listener can't block the pump for all the others.
;; A fake conn carries its listeners exactly where `d/listen` puts them —
;; an atom in the conn's `:listeners` metadata — which fire-native-listeners!
;; reads. We prove: (1) callbacks do NOT run inline (deferred to a later
;; macrotask), and (2) a throwing listener doesn't stop another from firing
;; (the per-listener throw guard is preserved).

(deftest fire-native-listeners!-dispatches-async-and-survives-a-throwing-listener
  (async done
    (let [fired    (atom #{})
          throw-cb (fn [_report] (throw (js/Error. "boom — a slow/bad listener")))
          ok-cb    (fn [_report] (swap! fired conj :ok))
          conn     (with-meta {} {:listeners (atom {:k1 throw-cb :k2 ok-cb})})]
      (#'store.wire/fire-native-listeners! conn {:tx-data []})
      (is (empty? @fired)
          "listeners are dispatched on a later macrotask, NOT inline (pump never blocks)")
      (js/setTimeout
        (fn []
          (is (contains? @fired :ok)
              "the non-throwing listener still ran — a throwing one doesn't block it")
          (done))
        25))))

;; ── DE-2: feed application is IDEMPOTENT on the basis-t watermark ──────────
;; The reconnect since-t replay can deliver a tx by BOTH the replay and the
;; live path (same basis-t) — handle-feed-event! must apply each tx at most
;; once. We drive a fake conn (IDeref → a db value with :max-tx; IMeta →
;; listeners) and assert: a foreign tx above the watermark fires listeners once
;; and advances the watermark; a same-bt overlap and a stale (lower) bt are
;; no-ops. The attachment state is installed and restored hermetically.

(deftest handle-feed-event!-fires-foreign-once-and-dedups-overlap
  (async done
    (let [fired      (atom [])
          listeners  (atom {:k (fn [report] (swap! fired conj (count (:tx-data report))))})
          conn       (fake-conn 100 listeners)
          ev         (fn [bt] {:seon.store.wire/event   "tx"
                               :seon.store.wire/basis-t bt
                               :seon.store.wire/basis-t-before (dec bt)
                               :seon.store.wire/tx-data [[1 :a "v" bt true]]})]
      (-> (with-wire-state
           (attached-state conn 99)
           (fn []
             (with-as-of-stub
              (fn []
                ;; foreign tx, bt=100 > watermark 99 → fire + advance.
                (#'store.wire/handle-feed-event!
                 (adapter-generation) conn (ev 100))
                ;; replay/live overlap and a stale event are both no-ops.
                (#'store.wire/handle-feed-event!
                 (adapter-generation) conn (ev 100))
                (#'store.wire/handle-feed-event!
                 (adapter-generation) conn (ev 95))
                (is (= 100 (adapter-basis-t))
                    "the branch-qualified watermark advanced")
                (-> (after-macrotask)
                    (.then
                     (fn []
                       (is (= 1 (count @fired))
                           "overlap and stale frames did not redeliver"))))))))
          (.catch (fn [error]
                    (is false (str "foreign-event dedup test threw: " error))))
          (.finally done)))))

(deftest connect-feed!-walks-pages-then-dedups-the-buffered-live-overlap
  (async done
    (let [db-name       store.wire/cluster-name
          !requests     (atom [])
          !callbacks    (atom nil)
          !destroyed?   (atom false)
          !deliveries   (atom 0)
          listeners     (atom {:listener (fn [_] (swap! !deliveries inc))})
          conn          (fake-conn 104 listeners)
          socket        #js {:destroy (fn [] (reset! !destroyed? true))}
          connect-stub  (fn [_ {:keys [on-event] :as callbacks}]
                          (reset! !callbacks callbacks)
                          (is (fn? on-event))
                          (js/Promise.resolve socket))
          replay-stub   (fn [_ opts]
                          (swap! !requests conj opts)
                          (case (count @!requests)
                            1 (do
                                ;; These frames arrive while both replay pages
                                ;; are in flight. They overlap page two and must
                                ;; be discarded by the monotonic watermark.
                                ((:on-event @!callbacks)
                                 (replay-event db-name 103 102))
                                ((:on-event @!callbacks)
                                 (replay-event db-name 104 103))
                                (js/Promise.resolve
                                 (replay-page
                                  db-name 100 104 102 false
                                  [(replay-event db-name 101 100)
                                   (replay-event db-name 102 101)])))
                            2 (js/Promise.resolve
                               (replay-page
                                db-name 102 104 104 true
                                [(replay-event db-name 103 102)
                                 (replay-event db-name 104 103)]))))]
      (-> (with-wire-state
           (attached-state conn 100 ::store.wire/connecting)
           (fn []
             (with-feed-stubs
              connect-stub replay-stub
              (fn []
                (-> (connect-feed! conn "req.sock" "pub.sock" (fn [_] nil))
                    (.then
                     (fn [result]
                       (is (= 4 (::store.wire/replayed result)))
                       (is (= db-name (::store.wire/db-name result)))
                       (is (= [{:since-t 100 :db-name db-name}
                               {:since-t 102 :through-t 104 :db-name db-name}]
                              (mapv #(select-keys % [:since-t :through-t :db-name])
                                    @!requests))
                           "only continuations carry the fixed upper watermark")
                       (is (= 104 (adapter-basis-t))
                           "every replay page advanced the durable reconnect cursor")
                       (is (false? @!destroyed?))
                       (-> (after-macrotask)
                           (.then
                            (fn []
                              (is (= 4 @!deliveries)
                                  "four replay txs fired once; buffered duplicates did not")))))))))))
          (.catch (fn [error]
                    (is false (str "paginated feed test threw: " error))))
          (.finally done)))))

(deftest connect-feed!-rejects-a-non-final-empty-page-without-advancing
  (async done
    (let [db-name      store.wire/cluster-name
          !destroyed?  (atom false)
          !calls       (atom 0)
          conn         (fake-conn 102)
          socket       #js {:destroy (fn [] (reset! !destroyed? true))}
          connect-stub (fn [_ _] (js/Promise.resolve socket))
          replay-stub  (fn [_ _]
                         (swap! !calls inc)
                         (js/Promise.resolve
                          (replay-page db-name 100 102 100 false [])))]
      (-> (with-wire-state
           (attached-state conn 100 ::store.wire/connecting)
           (fn []
             (with-feed-stubs
              connect-stub replay-stub
              (fn []
                (-> (connect-feed! conn "req.sock" "pub.sock" (fn [_] nil))
                    (.then (fn [_]
                             (is false "a no-progress page must not go live")))
                    (.catch
                     (fn [error]
                       (is (= :core-bug (:seon.error/kind (ex-data error))))
                       (is (= 1 @!calls) "the client cannot spin on an empty page")
                       (is (= 100 (adapter-basis-t))
                           "an invalid page never advances past unseen txs")
                       (is (true? @!destroyed?)))))))))
          (.catch (fn [error]
                    (is false (str "empty-page safety test threw: " error))))
          (.finally done)))))

(deftest reconnect-during-replay-resumes-from-the-last-complete-page
  (async done
    (let [db-name       store.wire/cluster-name
          !connects     (atom 0)
          !replays      (atom 0)
          !callbacks    (atom nil)
          !drops        (atom [])
          !deliveries   (atom 0)
          listeners     (atom {:listener (fn [_] (swap! !deliveries inc))})
          conn          (fake-conn 104 listeners)
          connect-stub  (fn [_ callbacks]
                          (swap! !connects inc)
                          (reset! !callbacks callbacks)
                          (js/Promise.resolve #js {:destroy (fn [] nil)}))
          replay-stub   (fn [_ opts]
                          (case (swap! !replays inc)
                            1 (do
                                (is (= 100 (:since-t opts)))
                                (js/Promise.resolve
                                 (replay-page
                                  db-name 100 104 102 false
                                  [(replay-event db-name 101 100)
                                   (replay-event db-name 102 101)])))
                            2 (do
                                (is (= 102 (:since-t opts)))
                                (is (= 104 (:through-t opts)))
                                ((:on-close @!callbacks) "drop during replay")
                                (js/Promise.resolve
                                 (replay-page
                                  db-name 102 104 104 true
                                  [(replay-event db-name 103 102)
                                   (replay-event db-name 104 103)])))
                            3 (do
                                (is (= 102 (:since-t opts))
                                    "the new connection resumes after the applied page")
                                (is (not (contains? opts :through-t))
                                    "a reconnect captures a fresh upper watermark")
                                (js/Promise.resolve
                                 (replay-page
                                  db-name 102 104 104 true
                                  [(replay-event db-name 103 102)
                                   (replay-event db-name 104 103)])))))]
      (-> (with-wire-state
           (attached-state conn 100 ::store.wire/connecting)
           (fn []
             (with-feed-stubs
              connect-stub replay-stub
              (fn []
                (-> (connect-feed!
                     conn "req.sock" "pub.sock" #(swap! !drops conj %))
                    (.then (fn [_]
                             (is false "the dropped replay must not go live")))
                    (.catch
                     (fn [error]
                       (is (= "drop during replay"
                              (::store.wire/drop-reason (ex-data error))))
                       (is (= 102 (adapter-basis-t))
                           "only the fully applied page advances the watermark")
                       (connect-feed!
                        conn "req.sock" "pub.sock" #(swap! !drops conj %))))
                    (.then
                     (fn [result]
                       (is (= 2 (::store.wire/replayed result)))
                       (is (= 2 @!connects))
                       (is (empty? @!drops)
                           "a pre-live drop rejects the attempt; only a live feed calls on-drop")
                       (is (= 104 (adapter-basis-t)))
                       (-> (after-macrotask)
                           (.then
                            (fn []
                              (is (= 4 @!deliveries)
                                  "each transaction was delivered once across attempts")))))))))))
          (.catch (fn [error]
                    (is false (str "mid-replay reconnect test threw: " error))))
          (.finally done)))))

(deftest listen-adapter-attaches-stops-and-reattaches-with-branch-qualified-progress
  (async done
    (let [db-name         store.wire/cluster-name
          !connections    (atom [])
          !replays        (atom [])
          !a-destroyed    (atom 0)
          !b-destroyed    (atom 0)
          !a-deliveries   (atom [])
          !b-deliveries   (atom [])
          a-listeners     (atom {:a #(swap! !a-deliveries conj %)})
          b-listeners     (atom {:b #(swap! !b-deliveries conj %)})
          a-database      (atom (fake-db 50 :branch/a))
          b-database      (atom (fake-db 50 :branch/b))
          conn-a          (fake-changing-conn a-database a-listeners)
          conn-b          (fake-changing-conn b-database b-listeners)
          socket-for      (fn [connection-number]
                            #js {:destroy
                                 #(swap! (if (= 1 connection-number)
                                           !a-destroyed
                                           !b-destroyed)
                                         inc)})
          connect-stub    (fn [_ callbacks]
                            (let [connection-number
                                  (inc (count @!connections))]
                              (swap! !connections conj callbacks)
                              (js/Promise.resolve
                               (socket-for connection-number))))
          replay-stub     (fn [_ {:keys [since-t db-name] :as request}]
                            (swap! !replays conj request)
                            (js/Promise.resolve
                             (replay-page db-name since-t since-t since-t
                                          true [])))
          start!          (fn [conn]
                            (store.wire/start-listen-adapter!
                             {::store.wire/conn conn
                              ::store.wire/sock-path "req.sock"
                              ::store.wire/pub-sock-path "pub.sock"}))
          lifecycle!      (fn []
                            (-> (start! conn-a)
                                (.then
                                 (fn [resolved-db-name]
                                   (is (= db-name resolved-db-name))
                                   (is (= 1 (count @!connections)))
                                   (is (= :branch/a
                                          (get-in
                                           (store.wire/adapter-status)
                                           [::store.wire/database-coordinate
                                            ::store.wire/branch])))
                                   (start! conn-a)))
                                (.then
                                 (fn [_]
                                   (is (= 1 (count @!connections))
                                       "starting the same attachment is idempotent")
                                   (is (= ::store.wire/tracked
                                          (#'store.wire/begin-transaction!
                                           conn-a "pending-a")))
                                   (is (= 1 (::store.wire/correlation-count
                                             (store.wire/adapter-status))))
                                   (is (true?
                                        (store.wire/stop-listen-adapter!)))
                                   (is (false?
                                        (store.wire/stop-listen-adapter!))
                                       "stopping an already stopped adapter is idempotent")
                                   (is (= 1 @!a-destroyed)
                                       "stopping closes the old pub socket once")
                                   (is (zero?
                                        (::store.wire/correlation-count
                                         (store.wire/adapter-status)))
                                       "attachment-owned correlations are disposed")
                                   (start! conn-b)))
                                (.then
                                 (fn [_]
                                   (let [status      (store.wire/adapter-status)
                                         a-callbacks (first @!connections)
                                         b-callbacks (second @!connections)]
                                     (is (= 2 (count @!connections)))
                                     (is (= 2 (count @!replays)))
                                     (is (= :branch/b
                                            (get-in
                                             status
                                             [::store.wire/database-coordinate
                                              ::store.wire/branch])))
                                     (is (= :branch/b
                                            (get-in
                                             status
                                             [::store.wire/last-applied-coordinate
                                              ::store.wire/database-coordinate
                                              ::store.wire/branch])))
                                     (is (= 50
                                            (get-in
                                             status
                                             [::store.wire/last-applied-coordinate
                                              ::store.wire/basis-t]))
                                         "branch B starts from its own t=50, not A's cursor")
                                     (reset! a-database
                                             (fake-db 51 :branch/a))
                                     (reset! b-database
                                             (fake-db 51 :branch/b))
                                     ((:on-event a-callbacks)
                                      (replay-event db-name 51 50))
                                     ((:on-event b-callbacks)
                                      (replay-event db-name 51 50))
                                     ((:on-event b-callbacks)
                                      (replay-event db-name 51 50))
                                     (-> (after-macrotask)
                                         (.then
                                          (fn []
                                            (is (empty? @!a-deliveries)
                                                "a stale A callback cannot reach either attachment")
                                            (is (= 1 (count @!b-deliveries))
                                                "B applies its commit exactly once")
                                            (is (= 51 (adapter-basis-t)))
                                            (is (true?
                                                 (store.wire/stop-listen-adapter!)))
                                            (is (= 1 @!b-destroyed))))))))))]
      (-> (with-wire-state
           (stopped-state)
           #(with-feed-stubs connect-stub replay-stub lifecycle!))
          (.catch
           (fn [error]
             (is false (str "attachment lifecycle test threw: " error))))
          (.finally done)))))

;; ── Durable transaction ids + reply/feed ordering ─────────────────────────

(deftest transact-retry-resends-one-frozen-request
  (async done
    (let [!requests (atom [])
          !attempts (atom 0)
          conn      (fake-conn 17)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (swap! !requests conj request)
                 (if (< (swap! !attempts inc) wire/transact-attempts)
                   (js/Promise.reject
                    (ex-info "ambiguous reply loss"
                             {:seon.store.wire/rpc-failure :timeout}))
                   (js/Promise.resolve (success-response 17))))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data [{:seon.store.wire-test/value "probe"}]})
                     (.then
                      (fn [report]
                        (is (= wire/transact-attempts (count @!requests))
                            "the bounded retry budget reached the successful attempt")
                        (is (= 1
                               (count
                                (set
                                 (map :seon.store.wire/id @!requests))))
                            "every ambiguous retry retained one durable wire id")
                        (is (apply = @!requests)
                            "the complete request stayed frozen across retries")
                        (is (= 17 (:max-tx (:db-after report)))
                            "the eventual response materialized normally")
                        (is (empty? (correlations))
                            "success without a running feed leaves no per-id state"))))))))
          (.catch (fn [error]
                    (is false (str "frozen-request retry test threw: " error))))
          (.finally done)))))

(deftest definite-allocator-protocol-rejection-cleans-state-and-is-structural
  (async done
    (let [!request (atom nil)
          conn     (fake-conn 23)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (reset! !request request)
                 (js/Promise.resolve
                  {:seon.store.wire/ok false
                   :seon.store.wire/error-kind "protocol"
                   :seon.store.wire/error :invalid-allocation-shape}))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data []
                       :seon.db.id/generated-candidates ["mint-ember-otter"]
                       :seon.db.id/generated-identity-attrs
                       #{:seon.store.wire-test/id}})
                     (.then
                      (fn [error]
                        (let [data (ex-data error)]
                          (is (= :seon.db.id.error/invalid-allocation-transaction
                                 (:seon.db.id/error data))
                              "allocator protocol failure has a stable machine tag")
                          (is (= :core-bug (:seon.error/kind data))
                              "malformed allocator protocol is blamed on core")
                          (is (= ["mint-ember-otter"]
                                 (:seon.store.wire/generated-candidates
                                  @!request))
                              "the candidate manifest crosses the wire unchanged")
                          (is (not (contains?
                                   @!request
                                   :seon.store.wire/generated-identity-attrs))
                              "the client-side identity catalog never crosses the wire")
                          (is (nil?
                               (get (correlations)
                                    (:seon.store.wire/id @!request)))
                              "a definite rejection removes its wire-id state")))))))))
          (.catch (fn [error]
                    (is false (str "allocator protocol rejection test threw: "
                                   error))))
          (.finally done)))))

(deftest definite-candidate-conflict-cleans-state-and-identifies-candidate
  (async done
    (let [candidate "mint-ember-otter"
          !wire-id  (atom nil)
          conn      (fake-conn 29)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (reset! !wire-id (:seon.store.wire/id request))
                 (js/Promise.resolve
                  {:seon.store.wire/ok false
                   :seon.store.wire/error-kind
                   "generated-candidate-conflict"
                   :seon.store.wire/generated-candidate candidate
                   :seon.store.wire/error :candidate-already-present}))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data []
                       :seon.db.id/generated-candidates [candidate]})
                     (.then
                      (fn [error]
                        (let [data (ex-data error)]
                          (is (= :seon.db.id.error/candidate-conflict
                                 (:seon.db.id/error data))
                              "candidate conflicts have a stable machine tag")
                          (is (= candidate
                                 (:seon.db.id/generated-candidate data))
                              "the rejected candidate remains inspectable")
                          (is (= :user-input (:seon.error/kind data))
                              "a caller-provided collision is structurally distinct")
                          (is (nil? (get (correlations) @!wire-id))
                              "a definite candidate conflict removes per-id state")))))))))
          (.catch (fn [error]
                    (is false (str "candidate-conflict test threw: " error))))
          (.finally done)))))

(deftest exhausted-replies-return-unknown-without-claiming-non-commit
  (async done
    (let [!requests (atom [])
          conn      (fake-conn 31)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (swap! !requests conj request)
                 (js/Promise.reject
                  (ex-info "ambiguous reply loss"
                           {:seon.store.wire/rpc-failure :timeout})))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data [{:seon.store.wire-test/value "unknown"}]})
                     (.then
                      (fn [error]
                        (let [data (ex-data error)]
                          (is (= wire/transact-attempts
                                 (:seon.store.wire/attempts data))
                              "unknown is returned only after the retry budget")
                          (is (= :seon.store.wire.status/unknown
                                 (:seon.store.wire/status data))
                              "reply exhaustion reports commit ambiguity")
                          (is (= :timeout
                                 (:seon.store.wire/rpc-failure data))
                              "the transport failure remains structured")
                          (is (= :core-bug (:seon.error/kind data))
                              "exhausted infrastructure ambiguity is a core fault")
                          (is (not (contains? data
                                              :seon.store.wire/committed?))
                              "unknown never falsely claims the transaction did not commit")
                          (is (= 1
                                 (count
                                  (set
                                   (map :seon.store.wire/id @!requests))))
                              "reply exhaustion still used one durable wire id")
                          (is (apply = @!requests)
                              "every exhausted attempt resent the frozen request")
                          (is (empty? (correlations))
                              "terminal unknown removes the local per-id state")))))))))
          (.catch (fn [error]
                    (is false (str "reply-exhaustion test threw: " error))))
          (.finally done)))))

(deftest feed-before-response-delivers-once-and-cleans-per-id-state
  (async done
    (let [!deliveries (atom [])
          listeners   (atom {:listener #(swap! !deliveries conj %)})
          conn        (fake-conn 37 listeners)
          !request    (atom nil)
          !respond    (atom nil)]
      (-> (with-wire-state
           (attached-state conn 36)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (reset! !request request)
                 (js/Promise.
                  (fn [deliver _reject]
                    (reset! !respond deliver))))
               (fn []
                 (let [result-promise
                       (dispatch-transaction
                        conn
                        {:tx-data [{:seon.store.wire-test/value "feed-first"}]})
                       wire-id (:seon.store.wire/id @!request)
                       event   {:seon.store.wire/event "tx"
                                :seon.store.wire/id wire-id
                                :seon.store.wire/basis-t 37
                                :seon.store.wire/basis-t-before 36
                                :seon.store.wire/tx-data
                                [[1 :seon.store.wire-test/value
                                  "feed-first" 37 true]]}]
                   (#'store.wire/handle-feed-event!
                    (adapter-generation) conn event)
                   (is (contains? (correlations) wire-id)
                       "feed-first remains recoverable until the response arrives")
                   (is (empty? @!deliveries)
                       "the own feed never delivers inline")
                   (@!respond (success-response 37))
                   (-> result-promise
                       (.then
                        (fn [report]
                          ;; Datahike's outer writer loop performs this step in
                          ;; production after it receives a successful report.
                          (#'store.wire/fire-native-listeners! conn report)
                          (is (empty? (correlations))
                              "the matching response consumes feed-first state")
                          (-> (after-macrotask)
                              (.then
                               (fn []
                                 (is (= 1 (count @!deliveries))
                                     "feed then response delivers exactly once"))))))))))))
          (.catch (fn [error]
                    (is false (str "feed-before-response test threw: " error))))
          (.finally done)))))

(deftest response-before-feed-delivers-once-and-cleans-per-id-state
  (async done
    (let [!deliveries (atom [])
          listeners   (atom {:listener #(swap! !deliveries conj %)})
          conn        (fake-conn 41 listeners)
          !request    (atom nil)]
      (-> (with-wire-state
           (attached-state conn 40)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (reset! !request request)
                 (js/Promise.resolve (success-response 41)))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data [{:seon.store.wire-test/value
                                  "response-first"}]})
                     (.then
                      (fn [report]
                        (let [wire-id (:seon.store.wire/id @!request)
                              event   {:seon.store.wire/event "tx"
                                       :seon.store.wire/id wire-id
                                       :seon.store.wire/basis-t 41
                                       :seon.store.wire/basis-t-before 40
                                       :seon.store.wire/tx-data
                                       [[1 :seon.store.wire-test/value
                                         "response-first" 41 true]]}]
                          (is (contains? (correlations) wire-id)
                              "response-first remains tracked until its feed")
                          ;; Datahike's outer writer loop delivers the response
                          ;; report; the own feed must suppress its duplicate.
                          (#'store.wire/fire-native-listeners! conn report)
                          (#'store.wire/handle-feed-event!
                           (adapter-generation) conn event)
                          (is (empty? (correlations))
                              "the matching feed consumes response-first state")
                          (-> (after-macrotask)
                              (.then
                               (fn []
                                 (is (= 1 (count @!deliveries))
                                     "response then feed delivers exactly once"))))))))))))
          (.catch (fn [error]
                    (is false (str "response-before-feed test threw: " error))))
          (.finally done)))))
