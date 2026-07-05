(ns seon.agent-inspect-errors-test
  "Tests + worked examples for the `seon.agent.inspect` error-triage
   verbs (`errors` / `error` / `repro`) over persisted
   `seon.error/record!` datoms, and the deepest-cause projection
   message (the `SEON-CORE-FAULT` marker / list rows show the REAL
   cause, never cljs.js's \"ERROR\" wrapper).

   `:agent` faults seed the store directly; the one deliberately-
   provoked `:core` fault is bracketed by
   `seon.error/expecting-core-fault!` (prints the DISTINCT
   `SEON-EXPECTED-CORE-FAULT` marker bin/test-cljs's gate does not
   count). Hermetic per-test :memory conns (history ON — repro's as-of
   needs the temporal index)."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent.inspect :as inspect]
    [seon.db :as db]
    [seon.error :as error]))

;; ---------------------------------------------------------------------------
;; Pure piece — the deepest-message helper (moved from seon.eval).
;; ---------------------------------------------------------------------------

(deftest deepest-message-skips-generic-wrappers
  (let [wrapped (error/->map
                  (ex-info "ERROR" {}
                           (ex-info "the real cause" {:seon.error/kind :user-input})))]
    (is (= "the real cause" (error/deepest-message wrapped))
        "cljs.js's generic top wrapper is skipped for the real cause")
    (is (= "plain boom" (error/deepest-message (error/->map (js/Error. "plain boom"))))
        "an unwrapped error keeps its own message")
    (is (= "" (error/deepest-message nil)))))

;; ---------------------------------------------------------------------------
;; Fixture — fresh :memory conn, root set! of db/*conn* (CLJS has no
;; binding across awaits; the persist hook closes over the var root).
;; ---------------------------------------------------------------------------

(defn- fresh-conn
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false}))))))

(defn- tick
  "Promise resolving after `ms` — lets a fire-and-forget persist settle."
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- with-fresh-conn
  "Run `f` (conn → Promise) with db/*conn* set! to a fresh conn; restore
   the prior root either way, then `done`."
  [f done]
  (let [prior db/*conn*
        finish (fn [] (set! db/*conn* prior) (done))]
    (-> (fresh-conn)
        (.then (fn [conn] (set! db/*conn* conn) (f conn)))
        (.catch (fn [e] (is false (str "test chain threw/rejected — " e))))
        (.then finish))))

(defn- seed-errors!
  "Seed the three fixture errors; returns the persist-settle Promise.

   1. an :agent fault whose top message is the generic \"ERROR\" wrapper
      (the deepest cause must surface instead);
   2. a malli-shaped :agent fault carrying fn-sym + a readable args-edn
      (the repro re-invocation path);
   3. a deliberately-provoked :core fault, EXPECTED-bracketed."
  []
  (error/record! {:seon.error/raw
                  (ex-info "ERROR" {}
                           (ex-info "wrapped real cause"
                                    {:seon.error/kind :user-input}))
                  :seon.error/fault :agent})
  (error/record! {:seon.error/raw
                  (ex-info "malli boom"
                           {:seon.error/kind :malli-instrument-input
                            :seon.error.malli/fn-sym 'my.probe/f
                            :seon.error/args-edn "[{:my.probe/arg 42}]"})
                  :seon.error/fault :agent})
  (error/expecting-core-fault!
    (fn []
      (error/record! {:seon.error/raw (js/Error. "core fixture boom")
                      :seon.error/fault :core})))
  (tick 100))

(defn- eid-of [msg]
  (db/query '[:find ?e . :in $ ?m :where [?e :seon.error/message ?m]] msg))

;; ---------------------------------------------------------------------------
;; errors — compact list, newest first, deepest message, fault filter.
;; ---------------------------------------------------------------------------

(defn- assert-errors-list! []
  ;; NOT exact-count: seon.error's pending buffer is process-global, so a
  ;; stray no-conn record! from an earlier test ns can flush into this
  ;; test's fresh conn alongside the three fixtures. Assert membership.
  (let [{rows :seon.agent.inspect/errors} (inspect/errors)
        fixture? (fn [msg] (some #(= msg (:seon.error/message %)) rows))]
    (is (<= 3 (count rows)))
    (is (apply > (map :seon.agent.inspect/eid rows)) "newest (highest eid) first")
    (testing "the deepest real cause renders, never the \"ERROR\" wrapper"
      (is (fixture? "wrapped real cause"))
      (is (fixture? "malli boom"))
      (is (fixture? "core fixture boom"))
      (is (not-any? #(= "ERROR" (:seon.error/message %)) rows)))
    (testing "the fixture rows carry fault + at"
      (let [mine (filter #(#{"wrapped real cause" "malli boom" "core fixture boom"}
                            (:seon.error/message %)) rows)]
        (is (= #{:agent :core} (set (map :seon.error/fault mine))))
        (is (every? (comp int? :seon.error/at) mine))))
    (testing "fault filter + limit"
      (let [core-rows (:seon.agent.inspect/errors
                        (inspect/errors {:seon.error/fault :core}))]
        (is (every? #(= :core (:seon.error/fault %)) core-rows))
        (is (some #(= "core fixture boom" (:seon.error/message %)) core-rows)))
      (is (= 1 (count (:seon.agent.inspect/errors
                        (inspect/errors {:seon.agent.inspect/limit 1}))))))))

;; ---------------------------------------------------------------------------
;; error — full envelope; repro — the work-backwards bundle.
;; ---------------------------------------------------------------------------

(defn- assert-error-detail! []
  (let [eid (eid-of "wrapped real cause")
        e   (inspect/error {:seon.agent.inspect/eid eid})]
    (is (true? (:seon.agent.inspect/ok? e)))
    (is (= :agent (:seon.error/fault e)))
    (is (= "wrapped real cause" (:seon.error/message e)))
    (is (int? (:seon.error/at e)))
    (is (string? (:seon.error/stack e)))
    (is (vector? (:seon.agent.inspect/frames e)) "frames table pulled")
    (is (= 0 (:seon.error.frame/index (first (:seon.agent.inspect/frames e))))
        "frames sorted by index"))
  (testing "unknown eid is a guiding value, not a throw"
    (let [miss (inspect/error {:seon.agent.inspect/eid 999999999})]
      (is (false? (:seon.agent.inspect/ok? miss)))
      (is (str/includes? (:seon.agent.inspect/error miss) "no persisted error")))))

(defn- assert-repro-bundle! []
  (testing "malli-shaped error → fn-sym + args + apply expression"
    (let [eid (eid-of "malli boom")
          r   (inspect/repro {:seon.agent.inspect/eid eid})]
      (is (true? (:seon.agent.inspect/ok? r)))
      (is (some? (:seon.db/db r)) "the frozen as-of db VALUE is in the bundle")
      ;; NB: (db/basis-t as-of-db) reports the ORIGIN db's max-tx, not the
      ;; as-of point (datahike-primer §1) — frozenness is proven by the
      ;; differs-from-head query below, not by basis-t.
      (is (= 'my.probe/f (:seon.agent.inspect/fn-sym r)))
      (is (= "[{:my.probe/arg 42}]" (:seon.error/args-edn r)))
      (is (str/includes? (:seon.agent.inspect/repro-expr r)
                         "(apply (resolve 'my.probe/f)"))
      (is (not (contains? r :seon.agent.inspect/note)))
      (testing "the as-of db PRE-DATES the error datom (differs from head)"
        (is (nil? (db/query '[:find ?e . :in $ ?m
                              :where [?e :seon.error/message ?m]]
                            (:seon.db/db r) "malli boom"))))))
  (testing "non-malli error → honest note, freeze-only expression"
    (let [eid (eid-of "wrapped real cause")
          r   (inspect/repro {:seon.agent.inspect/eid eid})]
      (is (true? (:seon.agent.inspect/ok? r)))
      (is (nil? (:seon.agent.inspect/fn-sym r)))
      (is (str/includes? (:seon.agent.inspect/note r) "no captured fn/args"))
      (is (str/includes? (:seon.agent.inspect/repro-expr r) "seon.db/as-of"))))
  (testing "unknown eid"
    (is (false? (:seon.agent.inspect/ok?
                  (inspect/repro {:seon.agent.inspect/eid 999999999}))))))

(deftest error-triage-verbs-over-seeded-datoms
  (async done
    (with-fresh-conn
      (fn [_conn]
        (-> (seed-errors!)
            (.then (fn []
                     (assert-errors-list!)
                     (assert-error-detail!)
                     (assert-repro-bundle!)))))
      done)))
