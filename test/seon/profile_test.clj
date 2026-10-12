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

(deftest ^{:seon/tasks #{'seon.profile/cost-entities-are-built-only-for-selected-counters}}
  ranking-costs-computes-each-definitions-spending-once
  (doseq [n [1 10 100]]
    (let [sink (batch/sink)
          prefix (str "cost.rank." (random-uuid))
          symbols (mapv #(symbol prefix (str "f" %)) (range n))]
      (doseq [[index sym] (map-indexed vector symbols)]
        (let [^java.util.concurrent.atomic.AtomicLongArray counter
              (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap sink [sym digest-a] profile/new-adder)]
          (.set counter 0 10)
          (.set counter 1 (* 10000 (inc index)))
          (.set counter 2 (* 10000 (inc index)))))
      (let [measured (profile/measure #(profile/live-costs sink {} 100000 n))]
        (is (empty? (:seon.profile/value measured)))
        (is (zero? (profile/calls-of measured 'seon.profile/cost-entity))
            "definitions below the floor construct no cost entities"))
      (let [measured (profile/measure #(profile/live-costs sink {} 0 1))]
        (is (= [(last symbols)] (mapv (comp first :seon.fn.cost/key) (:seon.profile/value measured))))
        (is (= 1 (profile/calls-of measured 'seon.profile/cost-entity))
            "the limit is applied before construction"))
      (let [[costs calls] (test-support/armed-calls
                          '#{seon.batch/spent}
                          #(profile/transaction-observation {:seon.batch/sink sink} {} 1 0 n))]
        (is (= (vec (reverse symbols)) (mapv (comp first :seon.fn.cost/key) costs)))
        (is (<= (get calls 'seon.batch/spent 0) n)))
      (doseq [^java.util.concurrent.atomic.AtomicLongArray counter (.values sink)]
        (.addAndGet counter 0 1)
        (.addAndGet counter 1 1)
        (.addAndGet counter 2 1))
      (let [measured (profile/measure #(profile/transaction-observation {:seon.batch/sink sink} {} 1 100000 n))]
        (is (empty? (:seon.profile/value measured)))
        (is (zero? (profile/calls-of measured 'seon.profile/cost-entity))))
      (let [cell (profile/cell {:seon.profile/sym (first symbols) :seon.profile/digest digest-a
                                :seon.profile/scope :seon.profile/context})
            costs (profile/live-costs sink {} 100000 n)]
        (is (= [[(first symbols) (:seon.profile/scope cell)]] (mapv :seon.fn.cost/key costs))
            "a context definition remains visible below the host floor")))))

(defn- stamping-world
  "A world over `connection` whose work is set by hand: `(step! warm-calls
  warm-us cold-calls cold-us digest)` adds that many warm and cold calls of
  that many self microseconds each to its sink under `digest`, writes one
  transaction and answers the stored entity's samples and whether that
  transaction wrote any of them."
  [connection sym]
  (let [sink (batch/sink)
        world {:seon.batch/sink sink
               :seon.batch/stamped-ns (java.util.concurrent.atomic.AtomicLong. (System/nanoTime))}
        k [sym :seon.profile/host]]
    (fn [warm-calls warm-us cold-calls cold-us digest]
      (let [^java.util.concurrent.atomic.AtomicLongArray counter
            (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap sink [sym digest] profile/new-adder)
            warm (long (* warm-calls warm-us 1000))
            cold (long (* cold-calls cold-us 1000))]
        (doseq [[slot n] [[0 (+ warm-calls cold-calls)] [1 (+ warm cold)] [2 (+ warm cold)]
                          [profile/cold-slot cold-calls] [(+ 1 profile/cold-slot) cold] [(+ 2 profile/cold-slot) cold]]]
          (.addAndGet counter slot n))
        (let [report (binding [batch/*world* world] (db/transact! connection []))
              probe (:db/id (db/pull (db/db connection) [:db/id] [:seon.fn.cost/key k]))
              ;; the probe's own entity: the writer's armed calls count into the world too
              written (count (filter #(and (= probe (:e %)) (= "seon.fn.cost" (namespace (:a %)))) (:tx-data report)))
              stored (get (db/stored-costs (db/db connection) [k]) k)]
          {:warm (some-> (batch/sample stored :warm) (nth 2))
           :cold (some-> (batch/sample stored :cold) (nth 2))
           :digest (first (batch/sample stored :warm))
           :written? (pos? written)})))))

(deftest a-stamped-transaction-replaces-the-values-that-moved
  (test-support/with-database
   (fn [connection]
     (let [sym 'seon.profile-test/cost-probe
           step! (stamping-world connection sym)]
       (clojure.test/are [calls self-us digest expected] (= expected (step! calls self-us 0 0.0 digest))
         100 50.0  digest-a {:warm 50.0  :cold nil :digest digest-a :written? true}
         100 50.0  digest-a {:warm 50.0  :cold nil :digest digest-a :written? false}
         100 100.0 digest-a {:warm 100.0 :cold nil :digest digest-a :written? true}
         100 100.0 digest-b {:warm 100.0 :cold nil :digest digest-b :written? true}
         5   900.0 digest-b {:warm 100.0 :cold nil :digest digest-b :written? false})
       (is (= #{[100.0 true]}
              (db/q '[:find ?us ?added :in $ ?k :where [?e :seon.fn.cost/key ?k] [?e :seon.fn.cost/warm-self-us ?us _ ?added]]
                    (db/history (db/db connection)) [sym :seon.profile/host]))
           "the replaced values leave one datom in the history: the current value")))))

(deftest ^{:seon/tasks #{'seon.profile/cost-values-are-reliable-and-like-for-like}}
  a-cold-single-call-never-becomes-the-warm-baseline
  (test-support/with-database
   (fn [connection]
     (let [step! (stamping-world connection 'seon.profile-test/cold-probe)]
       ;; warm calls, warm us, cold calls, cold us: each sample changes only from its own kind
       (clojure.test/are [warm-calls warm-us cold-calls cold-us expected]
                         (= expected (dissoc (step! warm-calls warm-us cold-calls cold-us digest-a) :digest))
         ;; a process's first call alone: startup cost, never the warm baseline
         0   0.0  1 5000.0  {:warm nil  :cold 5000.0 :written? true}
         100 50.0 0 0.0     {:warm 50.0 :cold 5000.0 :written? true}
         ;; a warm window never touches the cold sample, however different
         100 52.0 0 0.0     {:warm 50.0 :cold 5000.0 :written? false}
         ;; after a restart the first call replaces only the cold sample, and only when it moved
         0   0.0  1 5200.0  {:warm 50.0 :cold 5000.0 :written? false}
         0   0.0  1 10000.0 {:warm 50.0 :cold 10000.0 :written? true})))))

(deftest ^{:seon/tasks #{'seon.profile/cost-values-are-reliable-and-like-for-like}}
  a-fresh-cells-first-call-is-cold
  ;; a restart starts every cell from zero: the first call after it is cold
  (let [var-name (symbol (str "cold-" (random-uuid)))]
    (try
      (let [candidate (arm-probe! var-name inc digest-a)
            sink (batch/sink)
            _ (binding [batch/*world* {:seon.batch/sink sink}] (candidate 1) (candidate 2) (candidate 3))
            ^java.util.concurrent.atomic.AtomicLongArray counter
            (get sink [(symbol "seon.profile-test" (name var-name)) digest-a])]
        (is (= [3 1] [(.get counter 0) (.get counter profile/cold-slot)])
            "three calls, the cell's first of them cold"))
      (finally (ns-unmap 'seon.profile-test var-name)))))
