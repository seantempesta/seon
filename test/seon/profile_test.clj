(ns seon.profile-test
  "The armed wrapper's cumulative timing cells and their reads.

  One regression per behaviour class: a host call is counted on return and on
  throw without changing either; an SCI installation owns its own cell; a
  same-definition re-arm keeps its cell and a new definition gets a new one;
  a re-entry through the same wrapper is inside one outermost call;
  the reads rank and list over-second maxima; an operation's explanation names
  the definitions whose counters grew. No nanosecond assertion: timings are
  measured at the REPL (landing note), never asserted here."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.instrument :as mi]
            [seon.batch :as batch]
            [seon.config :as config]
            [seon.instrument :as instrument]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent.atomic LongAdder]))

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

(deftest an-explanation-names-the-definitions-whose-counters-grew
  (let [var-name (symbol (str "explained-" (random-uuid)))]
    (try
      (let [candidate (arm-probe! var-name
                                  (fn [n] (loop [i 0 t (System/nanoTime)]
                                            (if (< (- (System/nanoTime) t) 2000000)
                                              (recur (inc i) t)
                                              n)))
                                  digest-a)
            _ (candidate 0)
            mark (profile/begin)
            _ (dotimes [_ 3] (candidate 0))
            explanation (profile/explain mark 50)
            grown (some #(when (= (symbol "seon.profile-test" (name var-name))
                                  (:seon.profile/sym %)) %)
                        (:seon.profile/growths explanation))]
        (testing "only the growth inside the window is counted"
          (is (= 3 (:seon.profile/calls grown))))
        (is (<= 6 (:seon.profile/total-ms grown)))
        (is (some #(re-find #"ms in seon\.profile-test/explained-" %) (:seon.profile/lines explanation)))
        (is (re-find #"^OVER ONE SECOND"
                     (first (:seon.profile/lines
                             (profile/explain-slow (assoc mark :seon.profile/started-ns
                                                          (- (System/nanoTime) 2000000000))))))
            "over one second, the first line tells the agent the operation is its defect to fix")
        (is (nil? (profile/explain-slow (profile/begin)))
            "an operation within one second has nothing to explain"))
      (finally (ns-unmap 'seon.profile-test var-name)))))

(defn- digest-of
  [label]
  (apply str (take 64 (cycle (str label "0123456789abcdef")))))

(defn- probe-cell
  [label]
  (profile/cell {:seon.profile/sym (symbol "seon.profile-test" (str label "-" (random-uuid)))
                 :seon.profile/digest (digest-of label)
                 :seon.profile/scope :seon.profile/host}))

(deftest profile-summary-returns-call-data-once
  (let [inner-cell (probe-cell "inner")
        outer-cell (probe-cell "outer")
        idle (profile/cell {:seon.profile/sym 'probe/idle :seon.profile/scope :seon.profile/host})
        inner (profile/with-cell inner-cell (fn [] (profile/timed (Thread/sleep 30))))
        outer (profile/with-cell outer-cell (fn [] (profile/timed (inner))))
        _ (outer)
        summary (profile/summary [outer-cell inner-cell idle] 5)
        [first-row second-row :as rows] (:seon.profile/by-self summary)]
    (is (= 2 (:seon.profile/observed summary)) "a cell with no calls is not observed")
    (is (= [(:seon.profile/sym inner-cell) (:seon.profile/sym outer-cell)] (mapv :seon.profile/sym rows))
        "the callee that did the work ranks above the wrapper that only called it")
    (is (<= 25 (:seon.profile/self-ms first-row)))
    (is (<= (:seon.profile/self-ms second-row) 5) "a wrapper's self time excludes its callee's")
    (is (<= 25 (:seon.profile/total-ms second-row)) "its inclusive total still contains the callee's")
    (is (= #{:seon.profile/observed :seon.profile/over-second-count :seon.profile/by-self}
           (set (keys summary))) "one list of call data, no string rows or repeated rankings")
    (is (every? map? rows))
    (is (= 1 (count (:seon.profile/by-self (profile/summary [outer-cell inner-cell] 1)))))))

(deftest a-cell-counts-each-call-into-its-histogram-gauge-and-throws-by-class
  (let [held (probe-cell "histogram")
        inside (volatile! nil)
        call (profile/with-cell held (fn [f] (profile/timed (f))))]
    (call (fn [] (vreset! inside (:seon.profile/in-flight (profile/observation held)))))
    (dotimes [_ 2] (try (call #(throw (ex-info "probe" {}))) (catch clojure.lang.ExceptionInfo _)))
    (try (call #(throw (IllegalStateException. "probe"))) (catch IllegalStateException _))
    (let [observed (profile/observation held)]
      (is (= 1 @inside) "a call not yet returned is in flight")
      (is (= {:seon.profile/calls 4 :seon.profile/throws 3 :seon.profile/in-flight 0
              :seon.profile/throws-by-class {"clojure.lang.ExceptionInfo" 2 "java.lang.IllegalStateException" 1}}
             (select-keys observed [:seon.profile/calls :seon.profile/throws :seon.profile/in-flight
                                    :seon.profile/throws-by-class])))
      (is (= 4 (reduce + (map #(.sum ^LongAdder %) (:seon.profile/histogram held))))
          "calls are the histogram's sum")
      (is (<= (:seon.profile/p50-us observed) (:seon.profile/p99-us observed))))))

(deftest a-world-sink-holds-self-time-and-an-observation-is-its-delta
  (let [inner-cell (probe-cell "inner")
        outer-cell (probe-cell "outer")
        inner (profile/with-cell inner-cell (fn [] (profile/timed (Thread/onSpinWait))))
        outer (profile/with-cell outer-cell (fn [n] (profile/timed (dotimes [_ n] (inner)))))
        sink (batch/sink)
        world {:seon.batch/sink sink
               :seon.batch/stamped-ns (java.util.concurrent.atomic.AtomicLong. (System/nanoTime))}
        key-of (fn [held] [(:seon.profile/sym held) (:seon.profile/digest held)])
        slots (fn [held] (let [^java.util.concurrent.atomic.AtomicLongArray a (get sink (key-of held))]
                           (mapv #(.get a %) (range 3))))
        heavy (dec profile/explained-calls)]
    (binding [batch/*world* world] (outer 3))
    (is (= {} (profile/transaction-observation world)) "work under the floor stamps nothing")
    (binding [batch/*world* world] (outer heavy))
    (let [[outer-calls outer-nanos outer-self] (slots outer-cell)
          [inner-calls inner-nanos inner-self] (slots inner-cell)
          observed (profile/transaction-observation world)]
      (is (= [2 (+ 3 heavy)] [outer-calls inner-calls]))
      (is (= inner-nanos inner-self) "a leaf's self time is its whole time")
      (is (= outer-nanos (+ outer-self inner-nanos)) "a caller's self time excludes its counted callees")
      (is (= (inc heavy) (:seon.db.transaction/calls observed)) "the delta since the previous observation")
      (is (= #{(key-of inner-cell) (key-of outer-cell)}
             (into #{} (map (fn [[sym digest]] [sym digest])) (:seon.db.transaction/hot observed))))
      (is (= #{(conj (key-of inner-cell) heavy)} (:seon.db.transaction/repeated observed)))
      (is (nat-int? (:seon.db.transaction/ms observed)))
      (is (= {} (profile/transaction-observation world)) "the next observation holds only work done since"))))

(deftest ^{:seon/tasks #{'seon.profile/reading-the-profile-validates-every-cell
                         'seon.profile/begin-validates-every-cell-with-armed-predicates}}
  reading-the-profile-checks-no-cell-again
  ;; The counters are read directly: the per-cell predicates' own cells are
  ;; the observation, and none of them may move while the profile is read.
  (let [predicates '#{seon.profile/adder? seon.profile/accumulator? seon.profile/histogram?
                      seon.profile/thread-local? seon.profile/throw-counts? seon.profile/measured-mark?}
        entered (fn [] (reduce (fn [found cell]
                                 (if (contains? predicates (:seon.profile/sym cell))
                                   (update found (:seon.profile/sym cell) (fnil + 0)
                                           (.sum ^LongAdder (:seon.profile/entry-counter cell)))
                                   found))
                               {} (profile/cells)))
        before (entered)
        mark (profile/begin)
        explanation (profile/explain mark 10000)
        after (entered)
        own (filter #(= "seon.profile" (namespace (:seon.profile/sym %))) (:seon.profile/by-calls explanation))]
    (is (= predicates (set (keys before))) "every per-cell predicate is armed, so a zero is a reading")
    (is (= before after) "reading, marking and explaining call no per-cell predicate")
    (is (<= (reduce + 0 (map :seon.profile/calls own)) 3)
        "an empty mark grows only explain's entry and the two checks of the mark")))
