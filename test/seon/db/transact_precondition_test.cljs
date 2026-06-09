(ns seon.db.transact-precondition-test
  "Tests for KI-1 — `seon.db/transact!`'s invocation-shape precondition.

   The contract: `transact!` takes ONE map argument with at minimum
   `:seon.db/tx-data`. Positional invocations (`(transact! conn tx-data)`)
   or unqualified-key maps (`{:tx-data […]}`) used to silently
   destructure to nil and crash deep inside datahike with cryptic
   errors.

   A4 (2026-06-09) changed the failure SURFACE: `transact!` never
   throws and never rejects — every failure RESOLVES to the envelope
   `{:seon.db/ok? false :seon.db/error …}` so an agent's eval captures
   the error as a VALUE. These tests assert the envelope, async-style
   (mirrors test/seon/db/envelope_test.cljs).

   Run via `seon.test.runner/run-vars` over MCP."
  (:require [cljs.test :as t :refer [deftest is testing async]]
            [seon.db :as db]))

(defn- envelope-error
  "Call `transact!` with `arg`; deliver the resolved envelope's error
   CODE (lives at [:seon.db/error :seon.error/data :seon.db/error] —
   the error-map shape `seon.error/->map` produces) to `done-fn`.
   Never expects a throw/rejection — A4 contract."
  [arg done-fn]
  (-> (db/transact! arg)
      (.then (fn [r]
               (done-fn (get-in r [:seon.db/error :seon.error/data
                                   :seon.db/error]))))
      (.catch (fn [e] (done-fn [:UNEXPECTED-REJECTION (str e)])))))

(deftest non-map-arg-resolves-invalid-shape
  (testing "positional / non-map first arg resolves to invalid-invocation-shape envelope"
    (async done
      (let [pending (atom 4)
            check   (fn [v]
                      (is (= :seon.db/invalid-invocation-shape v))
                      (when (zero? (swap! pending dec)) (done)))]
        (envelope-error "not a map" check)
        (envelope-error [{:foo "bar"}] check)
        (envelope-error nil check)
        (envelope-error 42 check)))))

(deftest unqualified-tx-data-key-resolves-invalid-shape
  (testing "{:tx-data …} (bare keyword) resolves to invalid-invocation-shape"
    (async done
      (envelope-error {:tx-data []}
                      (fn [v]
                        (is (= :seon.db/invalid-invocation-shape v)
                            "bare :tx-data must fail — keys must be namespaced")
                        (done))))))

(deftest missing-tx-data-resolves-invalid-shape
  (testing "missing tx-data key resolves to invalid-invocation-shape"
    (async done
      (let [pending (atom 2)
            check   (fn [v]
                      (is (= :seon.db/invalid-invocation-shape v))
                      (when (zero? (swap! pending dec)) (done)))]
        (envelope-error {} check)
        (envelope-error {:seon.db/opts {}} check)))))

(deftest error-message-mentions-namespacing-hint
  (testing "the envelope message for bare :tx-data names the qualified key"
    (async done
      (-> (db/transact! {:tx-data []})
          (.then (fn [r]
                   (let [msg (get-in r [:seon.db/error :seon.error/message])]
                     (is (false? (:seon.db/ok? r)))
                     (is (re-find #"seon\.db/tx-data" (str msg))
                         (str "envelope should name the qualified key; got: "
                              (pr-str msg)))
                     (done))))
          (.catch (fn [e]
                    (is false (str "unexpected rejection: " e))
                    (done)))))))
