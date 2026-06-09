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

   Async tests use the standard `(async done …)` + Promise-chain
   envelope (same pattern as `seon.db.envelope-test`). The previous
   revision spun a synchronous busy-wait loop on the conn Promise —
   impossible in Node (a sync loop blocks the microtask queue, so the
   Promise can never resolve) — which is why these tests failed from
   the day they were written."
  (:require [cljs.test :as t :refer [deftest is testing async]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.schema :as schema]))

(defn ^:async open-conn!
  "Open a fresh datahike conn with the given history setting. Used
   by both happy-path and failure-path tests."
  [keep-history?]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? keep-history?}]
    (await (d/create-database cfg))
    (await (d/connect cfg))))

(deftest precondition-happy-path
  (async done
    (-> (open-conn! true)
        (.then (fn [conn]
                 (testing "with :keep-history? true and all attrs registered, returns true"
                   (is (= true
                          (db/assert-preconditions! {:seon.db/conn conn}))
                       "happy-path returns true"))))
        (.catch (fn [e] (is false (str "threw — " e))))
        (.then (fn [_] (done))))))

(deftest precondition-keep-history-off
  (async done
    (-> (open-conn! false)
        (.then (fn [conn]
                 (testing "with :keep-history? false, throws precondition-failed/keep-history-off"
                   (let [err (try
                               (db/assert-preconditions! {:seon.db/conn conn})
                               nil
                               (catch :default e (ex-data e)))]
                     (is (some? err) "should throw")
                     (is (= :seon.boot/precondition-failed (:kind err)))
                     (is (= :keep-history-off (:failure err)))))))
        (.catch (fn [e] (is false (str "threw — " e))))
        (.then (fn [_] (done))))))

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
      (is (schema/registered? attr)
          (str attr " should be registered by seon.db's namespace load")))))
