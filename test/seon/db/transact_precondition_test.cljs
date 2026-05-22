(ns seon.db.transact-precondition-test
  "Tests for KI-1 — `seon.db/transact!`'s invocation-shape precondition.

   The contract: `transact!` takes ONE map argument with at minimum
   `:seon.db/tx-data`. Positional invocations (`(transact! conn tx-data)`)
   or unqualified-key maps (`{:tx-data […]}`) used to silently
   destructure to nil and crash deep inside datahike with cryptic
   errors. The guard catches both at the boundary with a clean
   `ex-info` carrying `::seon.db/error :seon.db/invalid-invocation-shape`.

   Run via `seon.test.runner/run-vars` over MCP."
  (:require [cljs.test :as t :refer [deftest is testing]]
            [seon.db :as db]))

(defn- transact-throws-with-error?
  "Call `transact!` with `arg`, return the matched ::seon.db/error
   keyword from ex-data, or nil if it didn't throw / wrong error."
  [arg]
  (try
    (db/transact! arg)
    nil
    (catch :default e
      (:seon.db/error (ex-data e)))))

(deftest non-map-arg-throws
  (testing "positional / non-map first arg throws invalid-invocation-shape"
    (is (= :seon.db/invalid-invocation-shape
           (transact-throws-with-error? "not a map")))
    (is (= :seon.db/invalid-invocation-shape
           (transact-throws-with-error? [{:foo "bar"}])))
    (is (= :seon.db/invalid-invocation-shape
           (transact-throws-with-error? nil)))
    (is (= :seon.db/invalid-invocation-shape
           (transact-throws-with-error? 42)))))

(deftest unqualified-tx-data-key-throws
  (testing "{:tx-data …} (bare keyword) throws invalid-invocation-shape"
    (is (= :seon.db/invalid-invocation-shape
           (transact-throws-with-error? {:tx-data []}))
        "bare :tx-data must throw — keys must be namespaced")))

(deftest missing-tx-data-throws
  (testing "missing tx-data key throws invalid-invocation-shape"
    (is (= :seon.db/invalid-invocation-shape
           (transact-throws-with-error? {})))
    (is (= :seon.db/invalid-invocation-shape
           (transact-throws-with-error? {:seon.db/opts {}}))
        "only opts, no tx-data — must throw")))

(deftest error-message-mentions-namespacing-hint
  (testing "the error message when :tx-data is bare mentions the namespacing rule"
    (let [msg (try
                (db/transact! {:tx-data []})
                ""
                (catch :default e (ex-message e)))]
      (is (re-find #"seon\.db/tx-data" msg)
          (str "error message should name the qualified key; got: "
               (pr-str msg))))))
