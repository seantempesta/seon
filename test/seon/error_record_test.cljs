(ns seon.error-record-test
  "Tests + worked examples for `seon.error/record!` (error-blame-strict-gate
   phase 1): fault classification, EDN stack frames, fire-and-forget
   persistence (+ the no-conn buffer), the wrapper rejection/output arms,
   and one-error-one-datom dedup.

   DELIBERATELY exercises `:agent` faults only — a `:core` fault prints the
   `SEON-CORE-FAULT` marker that bin/test-cljs's strict gate greps for, so a
   passing suite must not emit one. The `:core` escalation path (marker +
   dial) is live-proven against the pod (see the phase-1 report), and the
   classification fns are pure — tested directly here."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.db :as db]
    [seon.error :as error]
    [seon.error.instrument :as ei]
    [seon.instrument :as si]))

;; ---------------------------------------------------------------------------
;; Pure pieces — no conn needed.
;; ---------------------------------------------------------------------------

(deftest fault-discriminator-is-what-were-we-calling
  (testing "agent-authored namespaces → :agent"
    (is (= :agent (error/fault-for 'my.plan/add!)))
    (is (= :agent (error/fault-for 'my.agent.root/tile))))
  (testing "core/lib namespaces → :core (unclassified = loud)"
    (is (= :core (error/fault-for 'seon.eval/raw-eval)))
    (is (= :core (error/fault-for 'seon.db/transact!)))
    (is (= :core (error/fault-for 'cljs.core/map)))
    (is (= :core (error/fault-for 'unqualified)))))

(deftest parse-frames-nodejs-stack
  (let [stack (str "Error: boom\n"
                   "    at myFn (/Users/x/seon/out/client/main.js:106:10)\n"
                   "    at /Users/x/seon/out/client/cljs-runtime/seon.eval.js:22:5\n")
        frames (error/parse-frames stack)]
    (is (vector? frames))
    (is (= 0 (:seon.error.frame/index (first frames))))
    (is (= "myFn" (:seon.error.frame/fn (first frames))))
    (is (= 106 (:seon.error.frame/line (first frames))))
    (is (= 10 (:seon.error.frame/column (first frames))))
    (testing "nil-valued slots are ABSENT (optional = absent)"
      (is (not (contains? (second frames) :seon.error.frame/fn))))
    (testing "garbage/nil → nil, never a throw"
      (is (nil? (error/parse-frames nil)))
      (is (nil? (error/parse-frames "no frames here"))))))

(deftest record-returns-envelope-and-never-throws
  ;; No conn bound in this test process yet — record! must still return the
  ;; envelope (the projection lands in the in-memory buffer, flushed by the
  ;; next conn-backed record!).
  (let [env (error/record! {:seon.error/raw (js/Error. "buffered one")
                            :seon.error/fault :agent})]
    (is (= :agent (:seon.error/fault env)))
    (is (= "buffered one" (:seon.error/message env)))
    (is (map? (error/record! {:seon.error/raw nil :seon.error/fault :agent}))
        "nil raw still yields an envelope")))

(deftest recorded-tag-dedup
  (let [e (js/Error. "tag me")]
    (is (false? (error/recorded? e)))
    (error/record! {:seon.error/raw e :seon.error/fault :agent})
    (is (true? (error/recorded? e))
        "record! tags the raw error so outer funnels skip it")
    (is (false? (error/recorded? nil)))
    (is (false? (error/recorded? "a string reason")))))

;; ---------------------------------------------------------------------------
;; Persistence — fresh :memory conn, root set! of db/*conn* (CLJS has no
;; binding across awaits; the persist hook closes over the var root).
;; ---------------------------------------------------------------------------

(defn- fresh-conn
  "Fresh :memory datahike conn (history ON — as-of needs the temporal
   index). Returns a Promise of the conn."
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

(defn- assert-persisted-row!
  "Assertions over the row `record-persists…` wrote — split out to keep
   the async chain shallow."
  [env]
  (let [row (first (db/query {:seon.db/query
                              '[:find ?e ?at
                                :where
                                [?e :seon.error/message "persisted one"]
                                [?e :seon.error/fault :agent]
                                [?e :seon.error/at ?at]]}))
        eid (first row)
        at  (second row)]
    (is (some? row) "the datom projection landed")
    (is (= at (:seon.error/at env)))
    (testing "frames are reified component entities"
      (is (pos? (count (db/query {:seon.db/query
                                  '[:find ?f
                                    :in $ ?e
                                    :where [?e :seon.error/frames ?f]]
                                  :seon.db/args [eid]})))))
    (testing "as-of the stored :seon.error/at = the db the failing code saw"
      (is (nil? (db/pull {:seon.db/db (db/as-of at)
                          :seon.db/pull-pattern '[:seon.error/message]
                          :seon.db/ref eid}))))
    (testing "the earlier no-conn record! was buffered and FLUSHED here"
      (is (some? (db/query {:seon.db/query
                            '[:find ?e .
                              :where [?e :seon.error/message "buffered one"]]}))))))

(deftest record-persists-fault-at-frames-and-buffer-flush
  (async done
    (with-fresh-conn
      (fn [_conn]
        (let [env (error/record! {:seon.error/raw (js/Error. "persisted one")
                                  :seon.error/fault :agent})]
          (is (= :agent (:seon.error/fault env)))
          (is (int? (:seon.error/at env)) "basis-t stamped when a conn is live")
          (-> (tick 100)
              (.then (fn [] (assert-persisted-row! env))))))
      done)))

;; ---------------------------------------------------------------------------
;; The wrapper arms — async rejection + output violation become datoms.
;; ---------------------------------------------------------------------------

(defn- wrap
  "Instrument `f` through the injecting wrapper under symbol `sym`."
  [sym f]
  (m/-instrument-f (si/injecting-fschema [:=> [:cat :map] :map] sym)
                   {:report ei/report-fn} f nil))

(deftest async-rejection-arm-records-and-re-rejects
  (async done
    (with-fresh-conn
      (fn [_conn]
        (let [wrapped (wrap 'my.probe/reject-fn
                            (fn [_] (js/Promise.reject (js/Error. "reject-arm"))))]
          (-> (wrapped {})
              (.then (fn [_] (is false "must reject"))
                     (fn [e] (is (= "reject-arm" (.-message e))
                                 "caller sees the ORIGINAL rejection unchanged")))
              (.then (fn [] (tick 100)))
              (.then (fn []
                       (is (= #{[:agent]}
                              (db/query {:seon.db/query
                                         '[:find ?fault
                                           :where
                                           [?e :seon.error/message "reject-arm"]
                                           [?e :seon.error/fault ?fault]]}))
                           "ONE datom, my.* sym → :agent"))))))
      done)))

(defn- assert-args-edn-row! []
  (let [row (first (db/query {:seon.db/query
                              '[:find ?args ?data
                                :where
                                [?e :seon.error/args-edn ?args]
                                [?e :seon.error/data-edn ?data]
                                [?e :seon.error/fault :agent]]}))
        args-edn (first row)
        data-edn (second row)]
    (is (= "[{:my.probe/arg 42}]" args-edn)
        "the FULL args vector persisted, read-string-able")
    (is (re-find #"malli-instrument-output" (str data-edn)))
    (is (not (re-find #"seon.error.malli/errors" (str data-edn)))
        "live-Schema explain leafs dropped from the projection")))

(deftest async-output-violation-records-with-full-args
  (async done
    (with-fresh-conn
      (fn [_conn]
        (let [wrapped (wrap 'my.probe/bad-output
                            (fn [_] (js/Promise.resolve :not-a-map)))]
          (-> (wrapped {:my.probe/arg 42})
              (.then (fn [_] (is false "must reject on output violation"))
                     (fn [e] (is (= ":malli.core/invalid-output" (.-message e)))))
              (.then (fn [] (tick 100)))
              (.then (fn [] (assert-args-edn-row!))))))
      done)))

(deftest propagated-rejection-is-recorded-once-with-refined-fault
  (async done
    (with-fresh-conn
      (fn [_conn]
        ;; An agent-diagnostic error rejecting through TWO nested
        ;; core-population conduits (the seon.eval shape): the dedup tag
        ;; yields ONE datom, and wrapper-fault refines :core → :agent.
        (let [diag  (ex-info "propagated diag"
                             {:seon.eval/warning-type :undeclared-var})
              inner (wrap 'seon.probe/conduit-inner
                          (fn [_] (js/Promise.reject diag)))
              outer (wrap 'seon.probe/conduit-outer
                          (fn [m] (inner m)))]
          (-> (outer {})
              (.then (fn [_] (is false "must reject"))
                     (fn [e] (is (= "propagated diag" (.-message e)))))
              (.then (fn [] (tick 100)))
              (.then (fn []
                       (is (= #{[:agent]}
                              (db/query {:seon.db/query
                                         '[:find ?fault
                                           :where
                                           [?e :seon.error/message "propagated diag"]
                                           [?e :seon.error/fault ?fault]]}))
                           "exactly ONE datom, refined to :agent"))))))
      done)))
