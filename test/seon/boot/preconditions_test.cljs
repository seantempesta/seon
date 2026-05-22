(ns seon.boot.preconditions-test
  "Tests for `seon.db/assert-preconditions!`.

   The fn checks v1.md §7.1 boot invariants:

     1. The resolved conn was opened with `:keep-history? true`.
        Required for tx-meta-as-history (datahike drops tx-meta
        datoms on compaction when history is off).
     2. All 7 `:seon.db/*` tx-meta attrs are registered in
        seon.schema. Datahike's `flush-tx-meta` rejects unregistered
        keys at write time; the first tx after boot would crash.

   Both checks throw `ex-info` with
   `{:kind :seon.boot/precondition-failed}` plus a `:failure` key
   naming the specific check that fired.

   Run via `seon.test.runner/run-vars` over MCP. The tests are
   async (open + connect datahike) but the assertions hold their
   results in an atom that the test reads after spinning briefly —
   keeping them runnable under seon.test.runner's sync driver."
  (:require [cljs.test :as t :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]))

(defn ^:async open-conn!
  "Open a fresh datahike conn with the given history setting. Used
   by both happy-path and failure-path tests."
  [keep-history?]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? keep-history?}]
    (await (d/create-database cfg))
    (await (d/connect cfg))))

(defn- await-into-atom!
  "Drive an ^:async fn to a resolved value. Returns the atom; spins
   on the deadline. Synchronous spin is OK here because the runner
   doesn't have other test fns competing for the microtask queue
   between assertions, and the datahike conn ops are fast (sub-ms
   for :memory)."
  [async-fn]
  (let [!r (atom :unset)]
    (-> (async-fn)
        (.then #(reset! !r %))
        (.catch #(reset! !r {:error (str %)})))
    (let [deadline (+ (.now js/Date) 1000)]
      (loop []
        (when (and (= :unset @!r) (< (.now js/Date) deadline))
          (recur))))
    !r))

(deftest precondition-happy-path
  (testing "with :keep-history? true and all attrs registered, returns true"
    (let [!conn (await-into-atom! #(open-conn! true))]
      (is (not (= :unset @!conn)) "conn must resolve within deadline")
      (is (= true
             (db/assert-preconditions! {:seon.db/conn @!conn}))
          "happy-path returns true"))))

(deftest precondition-keep-history-off
  (testing "with :keep-history? false, throws precondition-failed/keep-history-off"
    (let [!conn (await-into-atom! #(open-conn! false))
          err   (try
                  (db/assert-preconditions! {:seon.db/conn @!conn})
                  nil
                  (catch :default e (ex-data e)))]
      (is (some? err) "should throw")
      (is (= :seon.boot/precondition-failed (:kind err)))
      (is (= :keep-history-off (:failure err))))))

(deftest precondition-tx-meta-attrs-registered
  (testing "all 7 tx-meta attrs are registered at seon.db load time"
    ;; This is a smoke check on the namespace-load registration.
    ;; If seon.db is reachable, the registrations have already run.
    (doseq [attr [:seon.db/agent-id
                  :seon.db/session-id
                  :seon.db/turn-id
                  :seon.db/eval-id
                  :seon.db/origin
                  :seon.db/replay?
                  :seon.db/resume-marker?]]
      (is (seon.schema/registered? attr)
          (str attr " should be registered by seon.db's namespace load")))))
