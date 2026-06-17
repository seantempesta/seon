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
                (js/Promise.resolve {"ok" true})))
            (fn [] (store.wire/ping!)))
          (.then (fn [resp]
                   (is (true? (get resp "ok"))
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
