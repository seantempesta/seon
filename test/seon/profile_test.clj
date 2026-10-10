(ns seon.profile-test
  "The armed wrapper's cumulative timing cells and their reads.

  One regression per behaviour class: a host call is counted on return and on
  throw without changing either; an SCI installation owns its own cell; a
  same-definition re-arm keeps its cell and a new definition gets a new one;
  a re-entry through the same wrapper is inside one outermost call;
  the reads rank and list over-second maxima. No nanosecond assertion: timings are
  measured at the REPL (landing note), never asserted here."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [malli.instrument :as mi]
            [seon.batch :as batch]
            [seon.config :as config]
            [seon.db :as db]
            [seon.instrument :as instrument]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(defn- preserving-roots
  [body]
  (test-support/with-database
   (fn [_connection]
     (let [roots (into {} (map (juxt identity deref)) (instrument/instrumented))]
       (try
         (body)
         (finally
           (doseq [candidate (instrument/instrumented)]
             (alter-var-root candidate mi/-f->original))
           (doseq [[candidate root] roots]
             (alter-var-root candidate (constantly root)))))))))

(use-fixtures :each preserving-roots)

(def ^:private digest-a (apply str (repeat 64 "a")))
(def ^:private digest-b (apply str (repeat 64 "b")))

(defn- arm-probe!
  "Arm the probe Var `var-name` (interned with `f` when absent) with `digest`
  through the owner's Var arming, and return the Var."
  [var-name f digest]
  (let [candidate (or (find-var (symbol "seon.profile-test" (name var-name)))
                      (intern 'seon.profile-test var-name f))
        projection (schema/handed-projection)
        effective (test-support/effective-config)
        contract [:=> [:cat :int] :int]]
    (alter-meta! candidate assoc :malli/schema contract)
    (let [policy {:seon.config.error/max-evidence-bytes
                  (:seon.config.error/max-evidence-bytes effective)}]
      ;; Arm as adoption does: the wrapper compiled once, then installed.
      (#'instrument/arm-var! candidate contract projection
                             (#'instrument/compiled-wrapper projection (symbol candidate) contract
                                                            (mi/-f->original @candidate)
                                                            (config/result-caps effective) policy)
                             policy digest))
    candidate))

(defn- cell-of
  [callable]
  (:seon.instrument/cell (meta callable)))

(deftest a-host-call-is-counted-on-return-and-on-throw-without-changing-either
  (let [var-name (symbol (str "profiled-" (random-uuid)))
        failure (ex-info "original failure" {::probe true})]
    (try
      (let [candidate (arm-probe! var-name
                                  (fn [n] (if (neg? n) (throw failure) (inc n)))
                                  digest-a)
            cell (cell-of @candidate)]
        (is (= 2 (candidate 1)))
        (is (= 3 (candidate 2)))
        (is (identical? failure (try (candidate -1) (catch Throwable thrown thrown)))
            "the original exception object is rethrown unchanged")
        (let [observed (profile/observation cell)]
          (is (= {:seon.profile/sym (symbol "seon.profile-test" (name var-name))
                  :seon.profile/digest digest-a
                  :seon.profile/scope :seon.profile/host
                  :seon.profile/calls 3
                  :seon.profile/throws 1}
                 (select-keys observed [:seon.profile/sym :seon.profile/digest
                                        :seon.profile/scope :seon.profile/calls
                                        :seon.profile/throws])))))
      (finally (ns-unmap 'seon.profile-test var-name)))))

(deftest a-same-definition-rearm-keeps-its-cell-and-a-new-definition-gets-a-new-one
  (let [var-name (symbol (str "rearmed-" (random-uuid)))]
    (try
      (let [candidate (arm-probe! var-name inc digest-a)
            first-cell (cell-of @candidate)
            _ (candidate 1)
            _ (arm-probe! var-name inc digest-a)
            same-cell (cell-of @candidate)
            ;; A new definition is a new loaded function, as a reload makes it.
            _ (alter-var-root candidate (constantly (fn [n] (inc n))))
            _ (arm-probe! var-name nil digest-b)
            new-cell (cell-of @candidate)]
        (is (identical? first-cell same-cell))
        (let [identity-a {:seon.profile/sym (:seon.profile/sym first-cell)
                          :seon.profile/scope :seon.profile/host
                          :seon.profile/digest digest-a}]
          (is (profile/reusable? first-cell identity-a inc inc))
          (is (not (profile/reusable? first-cell (assoc identity-a :seon.profile/digest digest-b)
                                      inc inc))
              "a different definition digest never inherits the cell"))
        (is (= 1 (:seon.profile/calls (profile/observation same-cell))))
        (is (not (identical? first-cell new-cell)))
        (is (= digest-b (:seon.profile/digest new-cell)))
        (is (zero? (:seon.profile/calls (profile/observation new-cell)))))
      (finally (ns-unmap 'seon.profile-test var-name)))))

(deftest an-sci-installation-owns-its-own-cell
  (let [projection (schema/handed-projection)
        caps (config/result-caps (test-support/effective-config))
        install #(instrument/wrap-interpreted
                  'my.agents.profiled/value "[:=> [:cat :int] :int]"
                  projection caps inc
                  {:seon.program/definition-digest digest-a})
        one (install)
        other (install)]
    (is (= 2 (one 1)))
    (is (= 3 (one 2)))
    (is (= 4 (other 3)))
    (is (not (identical? (cell-of one) (cell-of other))))
    (is (= [2 1] (mapv (comp :seon.profile/calls profile/observation cell-of) [one other])))
    (is (= {:seon.profile/scope :seon.profile/context :seon.profile/digest digest-a}
           (select-keys (cell-of one) [:seon.profile/scope :seon.profile/digest])))))

(deftest a-re-entry-through-the-same-wrapper-is-inside-one-outermost-call
  ;; One arity calling another, and recursion, through the armed callable: the
  ;; profiler counted each re-entry (refresh-source! showed x3 for one call).
  (let [projection (schema/handed-projection)
        caps (config/result-caps (test-support/effective-config))
        ;; The body re-enters through the armed callable, which exists only
        ;; after wrapping: one write, before the first call.
        self (volatile! nil)
        failure (ex-info "bottom" {::probe true})
        armed (instrument/wrap-interpreted
               'my.agents.profiled/arities
               "[:function [:=> [:cat :int] :int] [:=> [:cat :int :int] :int]]"
               projection caps
               (fn ([n] (@self n 0))
                 ([n acc] (cond (neg? n) (throw failure)
                                (zero? n) acc
                                :else (@self (dec n) (inc acc)))))
               {:seon.program/definition-digest digest-a})
        _ (vreset! self armed)
        calls #(select-keys (profile/observation (cell-of armed))
                            [:seon.profile/calls :seon.profile/throws])]
    (is (= 3 (armed 3)))
    (is (= {:seon.profile/calls 1 :seon.profile/throws 0} (calls))
        "one outermost entry through two arities and three recursions")
    (is (identical? failure (try (armed -1) (catch Throwable thrown thrown))))
    (is (= {:seon.profile/calls 2 :seon.profile/throws 1} (calls)))
    (is (= 2 (armed 2 0)))
    (is (= {:seon.profile/calls 3 :seon.profile/throws 1} (calls))
        "a throw leaves the thread outside the cell, so the next entry counts")))

(deftest calls-of-counts-a-function-past-the-hot-limit
  (let [projection (schema/handed-projection)
        caps (config/result-caps (test-support/effective-config))
        prefix (str "my.agents.counted-" (random-uuid))
        installed (mapv (fn [i]
                          (instrument/wrap-interpreted
                           (symbol prefix (str "f" i)) "[:=> [:cat :int] :int]"
                           projection caps inc {:seon.program/definition-digest digest-a}))
                        (range (+ profile/hot-limit 5)))
        measured (profile/measure #(do (run! (fn [f] (f 1)) installed) ((first installed) 2)))]
    (is (= 2 (profile/calls-of measured (symbol prefix "f0"))))
    (is (every? #(= 1 (profile/calls-of measured (symbol prefix (str "f" %))))
                (range 1 (count installed)))
        "every definition is counted, the ones the ranked lists cut included")
    (is (zero? (profile/calls-of measured (symbol prefix "never"))))))

(deftest a-stamped-transaction-replaces-the-values-that-moved
  (test-support/with-database
   (fn [connection]
     (let [sink (batch/sink)
           world {:seon.batch/sink sink
                  :seon.batch/stamped-ns (java.util.concurrent.atomic.AtomicLong. (System/nanoTime))}
           sym 'seon.profile-test/cost-probe
           stored (fn [] (db/pull (db/db connection) '[:seon.fn.cost/self-us :seon.fn.cost/digest] [:seon.fn.cost/sym sym]))
           ;; the world does `calls` calls of `self-us` microseconds each under `digest`, then writes one transaction
           step! (fn [calls self-us digest]
                   (let [^java.util.concurrent.atomic.AtomicLongArray counter
                         (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap sink [sym digest] profile/new-adder)
                         nanos (long (* calls self-us 1000))]
                     (.addAndGet counter 0 calls)
                     (.addAndGet counter 1 nanos)
                     (.addAndGet counter 2 nanos))
                   (let [report (binding [batch/*world* world] (db/transact! connection []))
                         probe (:db/id (db/pull (db/db connection) [:db/id] [:seon.fn.cost/sym sym]))
                         ;; the probe's own entity: the writer's armed calls count into the world too
                         written (count (filter #(and (= probe (:e %)) (= "seon.fn.cost" (namespace (:a %)))) (:tx-data report)))
                         {:seon.fn.cost/keys [self-us digest]} (stored)]
                     {:self-us self-us :digest digest :written? (pos? written)}))]
       (clojure.test/are [calls self-us digest expected] (= expected (step! calls self-us digest))
         100 50.0  digest-a {:self-us 50.0  :digest digest-a :written? true}
         100 50.0  digest-a {:self-us 50.0  :digest digest-a :written? false}
         100 100.0 digest-a {:self-us 100.0 :digest digest-a :written? true}
         100 100.0 digest-b {:self-us 100.0 :digest digest-b :written? true}
         5   900.0 digest-b {:self-us 100.0 :digest digest-b :written? false})
       (is (= #{[100.0 true]}
              (db/q '[:find ?us ?added :in $ ?sym :where [?e :seon.fn.cost/sym ?sym] [?e :seon.fn.cost/self-us ?us _ ?added]]
                    (db/history (db/db connection)) sym))
           "the replaced values leave one datom in the history: the current value")))))
