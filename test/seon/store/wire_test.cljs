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
   [cljs.core.async :refer [take!]]
   [cljs.test :refer [deftest is async]]
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

(defn- channel->promise
  "Resolve a Promise with the one value delivered on a promise-chan."
  [channel]
  (js/Promise.
   (fn [deliver _reject]
     (take! channel deliver))))

(defn- fake-conn
  "Minimal conn surface used by the wire writer and native listeners."
  ([basis-t]
   (fake-conn basis-t (atom {})))
  ([basis-t listeners]
   (reify
     IDeref
     (-deref [_] {:max-tx basis-t})
     IMeta
     (-meta [_] {:listeners listeners}))))

(defn- dispatch-transaction
  "Drive the real SeonWireWriter branch without Datahike's outer writer loop."
  [conn arg-map]
  (channel->promise
   (writer/-dispatch!
    (store.wire/->SeonWireWriter "stub.sock" conn)
    {:op 'transact! :args [arg-map]})))

(defn- success-response
  [basis-t]
  {:seon.store.wire/ok true
   :seon.store.wire/basis-t basis-t
   :seon.store.wire/tempids {}
   :seon.store.wire/tx-data []
   :seon.store.wire/datoms-added 0
   :seon.store.wire/datoms-retracted 0})

(defn- with-wire-state
  "Install hermetic adapter/transaction state for an async body, then restore."
  [adapter-state body]
  (let [adapter           @#'store.wire/!adapter
        saved-adapter     @adapter
        saved-transactions @store.wire/!transactions
        restore!          (fn []
                            (reset! adapter saved-adapter)
                            (reset! store.wire/!transactions
                                    saved-transactions))]
    (reset! adapter adapter-state)
    (reset! store.wire/!transactions {})
    (try
      (-> (js/Promise.resolve (body))
          (.finally restore!))
      (catch :default error
        (restore!)
        (js/Promise.reject error)))))

(defn- after-macrotask
  "Wait long enough for deferred native listener callbacks to run."
  []
  (js/Promise.
   (fn [deliver _reject]
     (js/setTimeout deliver 25))))

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
;; no-ops. The shared `!adapter`/`!transactions` are saved+restored.

(deftest handle-feed-event!-fires-foreign-once-and-dedups-overlap
  (async done
    (let [fired      (atom [])
          listeners  (atom {:k (fn [report] (swap! fired conj (count (:tx-data report))))})
          db-val     {:max-tx 100}
          conn       (reify
                       IDeref (-deref [_] db-val)
                       IMeta  (-meta  [_] {:listeners listeners}))
          adapter    @#'store.wire/!adapter
          saved      @adapter
          saved-transactions @store.wire/!transactions
          ev         (fn [bt] {:seon.store.wire/event   "tx"
                               :seon.store.wire/basis-t bt
                               :seon.store.wire/tx-data [[1 :a "v" bt true]]})]
      (reset! adapter {:started? true :last-db db-val :last-applied-t 99})
      (reset! store.wire/!transactions {})
      ;; foreign tx, bt=100 > watermark 99 → fires + advances watermark to 100
      (#'store.wire/handle-feed-event! conn (ev 100))
      ;; replay↔live overlap (same bt) → no-op; stale (lower bt) → no-op
      (#'store.wire/handle-feed-event! conn (ev 100))
      (#'store.wire/handle-feed-event! conn (ev 95))
      (is (= 100 (:last-applied-t @adapter))
          "watermark advanced to the applied basis-t")
      (js/setTimeout
        (fn []
          (is (= 1 (count @fired))
              "the foreign tx fired listeners exactly once; the bt-overlap dup and the stale event were no-ops")
          (reset! adapter saved)
          (reset! store.wire/!transactions saved-transactions)
          (done))
        25))))

;; ── Durable transaction ids + reply/feed ordering ─────────────────────────

(deftest transact-retry-resends-one-frozen-request
  (async done
    (let [!requests (atom [])
          !attempts (atom 0)
          conn      (fake-conn 17)]
      (-> (with-wire-state
           {:started? false}
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
                        (is (empty? @store.wire/!transactions)
                            "success without a running feed leaves no per-id state"))))))))
          (.catch (fn [error]
                    (is false (str "frozen-request retry test threw: " error))))
          (.finally done)))))

(deftest definite-allocator-protocol-rejection-cleans-state-and-is-structural
  (async done
    (let [!wire-id (atom nil)
          conn     (fake-conn 23)]
      (-> (with-wire-state
           {:started? false}
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (reset! !wire-id (:seon.store.wire/id request))
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
                          (is (nil? (get @store.wire/!transactions @!wire-id))
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
           {:started? false}
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
                       :seon.db.id/generated-candidates [candidate]
                       :seon.db.id/generated-identity-attrs
                       #{:seon.store.wire-test/id}})
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
                          (is (nil? (get @store.wire/!transactions @!wire-id))
                              "a definite candidate conflict removes per-id state")))))))))
          (.catch (fn [error]
                    (is false (str "candidate-conflict test threw: " error))))
          (.finally done)))))

(deftest exhausted-replies-return-unknown-without-claiming-non-commit
  (async done
    (let [!requests (atom [])
          conn      (fake-conn 31)]
      (-> (with-wire-state
           {:started? false}
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
                          (is (empty? @store.wire/!transactions)
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
           {:started? true
            :last-db {:max-tx 36}
            :last-applied-t 36}
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
                                :seon.store.wire/tx-data
                                [[1 :seon.store.wire-test/value
                                  "feed-first" 37 true]]}]
                   (#'store.wire/handle-feed-event! conn event)
                   (is (contains? @store.wire/!transactions wire-id)
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
                          (is (empty? @store.wire/!transactions)
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
           {:started? true
            :last-db {:max-tx 40}
            :last-applied-t 40}
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
                                       :seon.store.wire/tx-data
                                       [[1 :seon.store.wire-test/value
                                         "response-first" 41 true]]}]
                          (is (contains? @store.wire/!transactions wire-id)
                              "response-first remains tracked until its feed")
                          ;; Datahike's outer writer loop delivers the response
                          ;; report; the own feed must suppress its duplicate.
                          (#'store.wire/fire-native-listeners! conn report)
                          (#'store.wire/handle-feed-event! conn event)
                          (is (empty? @store.wire/!transactions)
                              "the matching feed consumes response-first state")
                          (-> (after-macrotask)
                              (.then
                               (fn []
                                 (is (= 1 (count @!deliveries))
                                     "response then feed delivers exactly once"))))))))))))
          (.catch (fn [error]
                    (is false (str "response-before-feed test threw: " error))))
          (.finally done)))))
