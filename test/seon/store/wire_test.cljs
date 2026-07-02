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
   [cljs.test :refer [deftest is async]]
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
;; no-ops. The shared `!adapter`/`!own-write-ids` are saved+restored.

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
          saved-ids  @store.wire/!own-write-ids
          ev         (fn [bt] {:seon.store.wire/event   "tx"
                               :seon.store.wire/basis-t bt
                               :seon.store.wire/tx-data [[1 :a "v" bt true]]})]
      (reset! adapter {:started? true :last-db db-val :last-applied-t 99})
      (reset! store.wire/!own-write-ids #{})
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
          (reset! store.wire/!own-write-ids saved-ids)
          (done))
        25))))
