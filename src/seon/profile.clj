(ns seon.profile
  "Cumulative inclusive call timing held by the armed wrappers.

  Each armed callable owns one cell of JDK accumulators, created when it is
  installed: calls, total nanoseconds, lifetime maximum nanoseconds and
  throws. `seon.instrument` brackets every call with two `System/nanoTime`
  reads and updates the cell's accumulators directly (`timed`); the call path
  constructs no map, reads no database and calls no armed function.

  A host cell belongs to a loaded Var's JVM wrapper and is attributed to no
  cluster. A context cell belongs to one SCI installation's wrapper. Cells are
  cumulative and never reset (`LongAdder.sum` is not an atomic snapshot, and
  independent resets would split samples: JDK `LongAdder.java` sumThenReset).
  Reads are approximate under concurrent calls.

  Times are inclusive wall time: a caller's total contains its callees' and its
  waits. Totals cannot be subtracted into self time.

  A call is one outermost entry per thread: a call that re-enters the same
  cell on its thread (one arity of a multi-arity function calling another
  through the armed Var, or recursion through it) is inside that entry's time
  and is not counted again. Counting each re-entry inflated calls and totals
  (`seon.cluster/refresh-source!` showed x3 and three inclusive totals for one
  call)."
  (:require [seon.batch]
            [seon.schema.edn :as schema.edn])
  (:import [java.util Collections IdentityHashMap WeakHashMap]
           [java.util.concurrent.atomic LongAccumulator LongAdder]
           [java.util.function LongBinaryOperator]))

(schema.edn/load! {})

(def slow-ms
  "The owner's bound (2026-09-23): every operation over one second is explained."
  1000)

;;; ---------------------------------------------------------------------------
;;; Cells
;;; ---------------------------------------------------------------------------

(defn adder?
  "True for a JDK LongAdder."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? LongAdder value))

(defn thread-local?
  "True for a JDK ThreadLocal."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? ThreadLocal value))

(defn accumulator?
  "True for a JDK LongAccumulator."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? LongAccumulator value))

(def ^:private longest
  (reify LongBinaryOperator
    (applyAsLong [_ a b] (Math/max a b))))

;; The live cells, held weakly: a cell is reachable from its wrapper's
;; metadata, so a replaced wrapper or a released SCI context releases its cell.
;; Written at installation only; read by `cells`.
(defonce ^:private live
  (Collections/synchronizedSet (Collections/newSetFromMap (WeakHashMap.))))

(defn cell
  "A new cumulative cell for one installed callable.

  `callable-identity` names the callable's symbol, its definition digest when the
  installer holds one, and its scope. A missing digest is unknown, never a
  substitute."
  {:malli/schema [:=> [:cat :seon.profile/identity] :seon.profile/cell]}
  [callable-identity]
  (let [created (merge (select-keys callable-identity [:seon.profile/sym :seon.profile/digest
                                              :seon.profile/scope :seon.profile/context])
                       {:seon.profile/call-counter (LongAdder.)
                        :seon.profile/total-counter (LongAdder.)
                        :seon.profile/max-counter (LongAccumulator. longest 0)
                        :seon.profile/throw-counter (LongAdder.)
                        :seon.profile/entered (ThreadLocal.)})]
    (.add ^java.util.Set live created)
    created))

(defn reusable?
  "True when `prior` may keep accumulating for the callable `callable-identity` names.

  The same symbol and definition digest is the same definition. Without a
  digest only the identical underlying callable is known to be the same."
  {:malli/schema [:=> [:cat [:or :nil :seon.profile/cell] :seon.profile/identity
                       :seon.schema/value :seon.schema/value] :boolean]}
  [prior callable-identity prior-original original]
  (boolean
   (and prior
        (= (:seon.profile/sym prior) (:seon.profile/sym callable-identity))
        (= (:seon.profile/scope prior) (:seon.profile/scope callable-identity))
        (= (:seon.profile/context prior) (:seon.profile/context callable-identity))
        (if-let [digest (:seon.profile/digest callable-identity)]
          (= digest (:seon.profile/digest prior))
          (and (not (:seon.profile/digest prior))
               (identical? prior-original original))))))

(defmacro with-cell
  "Bind `cell`'s accumulators as hinted locals around `body`, once, at install.

  The closure `body` builds captures the JDK objects themselves, so `timed`
  inside it performs no lookup."
  [cell-form & body]
  `(let [cell# ~cell-form
         ~(with-meta '__profile-calls {:tag 'java.util.concurrent.atomic.LongAdder})
         (:seon.profile/call-counter cell#)
         ~(with-meta '__profile-total {:tag 'java.util.concurrent.atomic.LongAdder})
         (:seon.profile/total-counter cell#)
         ~(with-meta '__profile-max {:tag 'java.util.concurrent.atomic.LongAccumulator})
         (:seon.profile/max-counter cell#)
         ~(with-meta '__profile-throws {:tag 'java.util.concurrent.atomic.LongAdder})
         (:seon.profile/throw-counter cell#)
         ~(with-meta '__profile-entered {:tag 'java.lang.ThreadLocal})
         (:seon.profile/entered cell#)
         ~'__profile-sym (:seon.profile/sym cell#)]
     ~@body))

(def ^java.util.function.Function new-adder
  "The one function a call sink makes a symbol's first counter with."
  (reify java.util.function.Function
    (apply [_ _] (LongAdder.))))

(defmacro count-into-world!
  "Count one call of `sym` in the calling evaluation's world's sink
  (`seon.batch/*world*`, its `:seon.batch/sink`): a dynamic read and a
  LongAdder increment, calling no armed function."
  [sym]
  `(when-some [sink# (:seon.batch/sink seon.batch/*world*)]
     (.increment ^java.util.concurrent.atomic.LongAdder
                 (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap sink# ~sym seon.profile/new-adder))))

(def host-entry
  "The re-entry marker of an armed call made outside any evaluation's world."
  (Object.))

(defmacro timed
  "Evaluate `body` inside `with-cell`, counting its completion and elapsed time
  once per outermost entry on this thread, and once per world entered.

  The cell's thread entry holds the call sink of the world it was entered in
  (`seon.batch/*world*`'s `:seon.batch/sink`, else `host-entry`). A re-entry
  of the same cell in the same world runs `body` uncounted: its time is inside
  the outer entry's. A re-entry under another world (a nested test agent)
  counts one call into that world's sink only; the JVM-wide counters keep
  their outermost entry. Each entry restores the prior one on exit. A throw
  is counted as a completed call and a throw, then rethrown unchanged."
  [& body]
  `(let [prior# (.get ~'__profile-entered)
         here# (or (:seon.batch/sink seon.batch/*world*) seon.profile/host-entry)]
     (cond
       (identical? prior# here#) (do ~@body)

       (some? prior#)
       (do (.set ~'__profile-entered here#)
           (try
             (let [value# (do ~@body)] (count-into-world! ~'__profile-sym) value#)
             (catch Throwable failure# (count-into-world! ~'__profile-sym) (throw failure#))
             (finally (.set ~'__profile-entered prior#))))

       :else
       (let [started# (System/nanoTime)]
         (.set ~'__profile-entered here#)
         (try
           (let [value# (do ~@body)
                 elapsed# (- (System/nanoTime) started#)]
             ;; the calling evaluation's own count (its world's sink): a test
             ;; agent's counters see only its own calls
             (count-into-world! ~'__profile-sym)
             (.increment ~'__profile-calls)
             (.add ~'__profile-total elapsed#)
             (.accumulate ~'__profile-max elapsed#)
             value#)
           (catch Throwable failure#
             (let [elapsed# (- (System/nanoTime) started#)]
               (count-into-world! ~'__profile-sym)
               (.increment ~'__profile-calls)
               (.add ~'__profile-total elapsed#)
               (.accumulate ~'__profile-max elapsed#)
               (.increment ~'__profile-throws))
             (throw failure#))
           (finally (.set ~'__profile-entered prior#)))))))

;;; ---------------------------------------------------------------------------
;;; Reads — pure over the cells; nothing here resets a counter
;;; ---------------------------------------------------------------------------

(defn cells
  "Every live cell, host and context."
  {:malli/schema [:=> [:cat] [:vector :seon.profile/cell]]}
  []
  (locking live (vec live)))

(defn observation
  "One cell's cumulative counters as a value."
  {:malli/schema [:=> [:cat :seon.profile/cell] :seon.profile/observation]}
  [held]
  (let [calls (.sum ^LongAdder (:seon.profile/call-counter held))
        total (.sum ^LongAdder (:seon.profile/total-counter held))]
    (merge (select-keys held [:seon.profile/sym :seon.profile/digest
                              :seon.profile/scope :seon.profile/context])
           {:seon.profile/calls calls
            :seon.profile/total-ms (quot total 1000000)
            :seon.profile/mean-us (if (pos? calls) (quot total (* 1000 calls)) 0)
            :seon.profile/max-ms (quot (.get ^LongAccumulator (:seon.profile/max-counter held)) 1000000)
            :seon.profile/throws (.sum ^LongAdder (:seon.profile/throw-counter held))})))

(defn top
  "Rank `population` by inclusive total and maximum; list maxima over one second.

  The top `limit` definitions by each measure, and every definition whose
  maximum exceeds one second."
  {:seon/tasks #{"5f54ae50d841"}
   :malli/schema [:=> [:cat [:vector :seon.profile/cell] [:int {:min 1}]] :seon.profile/report]}
  [population limit]
  (let [observed (into [] (comp (map observation) (filter (comp pos? :seon.profile/calls))) population)
        ranked (fn [k] (into [] (take limit) (sort-by k > observed)))]
    {:seon.profile/cells (count population)
     :seon.profile/observed (count observed)
     :seon.profile/by-total (ranked :seon.profile/total-ms)
     :seon.profile/by-max (ranked :seon.profile/max-ms)
     :seon.profile/over-second
     (into [] (filter #(< slow-ms (:seon.profile/max-ms %))) (sort-by :seon.profile/max-ms > observed))}))

;;; ---------------------------------------------------------------------------
;;; Explaining one slow operation
;;; ---------------------------------------------------------------------------

(defn snapshot?
  "True for the counter snapshot `begin` takes: the live cells it read and
  their calls and nanos, `[calls0 nanos0 calls1 nanos1 ...]` in one long array."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (boolean (and (map? value)
                (vector? (::cells value))
                (some-> (::sums value) class .isArray))))

(defn begin
  "Mark the start of an operation: its clock and every cell's calls and total.

  One pass over the live cells, calling no other armed function, fills one
  primitive array beside the vector of cells it read: its cost is proportional
  to the number of cells, with two longs each and no per-cell allocation."
  {:malli/schema [:=> [:cat] :seon.profile/mark]}
  []
  (let [held (locking live (vec live))
        sums (long-array (* 2 (count held)))]
    (dotimes [i (count held)]
      (let [member (nth held i)]
        (aset sums (* 2 i) (.sum ^LongAdder (:seon.profile/call-counter member)))
        (aset sums (inc (* 2 i)) (.sum ^LongAdder (:seon.profile/total-counter member)))))
    {:seon.profile/started-ns (System/nanoTime)
     :seon.profile/snapshot {::cells held ::sums sums}}))

(defn- growth-line
  {:malli/schema [:=> [:cat :seon.profile/growth] :string]}
  [{sym :seon.profile/sym calls :seon.profile/calls total :seon.profile/total-ms
    scope :seon.profile/scope context :seon.profile/context}]
  (str total " ms in " sym " x" calls
       (when (= :seon.profile/context scope)
         (str " [SCI" (when context (str " " context)) "]"))))

(defn explain
  "What the armed definitions did during the operation `mark` began.

  The growth is every cell's inclusive counter increase in this JVM during the
  window, largest first: nested calls appear in both caller and callee, and
  concurrent work on other threads is included. No growth means the time was
  spent in unarmed code or waiting."
  {:malli/schema [:=> [:cat :seon.profile/mark [:int {:min 1}]] :seon.profile/explanation]}
  [{started :seon.profile/started-ns {held ::cells ^longs sums ::sums} :seon.profile/snapshot} limit]
  (let [elapsed-ms (quot (- (System/nanoTime) started) 1000000)
        ;; Cells are matched by identity (a LongAdder is a Number, which a
        ;; Clojure map would compare by value); a cell installed after
        ;; `begin` starts from zero.
        position (let [index (IdentityHashMap.)]
                   (dotimes [i (count held)]
                     (.put index (:seon.profile/call-counter (nth held i)) i))
                   index)
        prior (fn [member offset]
                (if-let [i (.get position (:seon.profile/call-counter member))]
                  (aget sums (+ (* 2 (long i)) (long offset)))
                  0))
        growth (into []
                     (comp
                      (keep (fn [member]
                              (let [calls (- (.sum ^LongAdder (:seon.profile/call-counter member))
                                             (long (prior member 0)))
                                    total (- (.sum ^LongAdder (:seon.profile/total-counter member))
                                             (long (prior member 1)))]
                                (when (pos? calls)
                                  (merge (select-keys member [:seon.profile/sym :seon.profile/digest
                                                            :seon.profile/scope :seon.profile/context])
                                         {:seon.profile/calls calls
                                          :seon.profile/total-ms (quot total 1000000)})))))
                      (filter (comp pos? :seon.profile/total-ms)))
                     (cells))
        ranked (into [] (take limit) (sort-by :seon.profile/total-ms > growth))]
    {:seon.profile/elapsed-ms elapsed-ms
     :seon.profile/growths ranked
     :seon.profile/lines
     (into [(str elapsed-ms " ms elapsed.")]
           (if (seq ranked)
             (into [(str "Armed definitions by inclusive time (callers include callees;"
                         " concurrent work included):")]
                   (map growth-line) ranked)
             [(str "No armed definition accumulated a millisecond: the time is in"
                   " unarmed code or waiting.")]))}))

(defn explain-slow
  "The explanation when the operation `mark` began took over one second.

  Nil is the declared answer for an operation that finished within the bound;
  the fast path pays one clock read."
  {:seon/tasks #{"c7bc0c506aa4"}
   :malli/schema [:=> [:cat :seon.profile/mark] [:or :nil :seon.profile/explanation]]}
  [mark]
  (when (< slow-ms (quot (- (System/nanoTime) (:seon.profile/started-ns mark)) 1000000))
    (let [explanation (explain mark 10)]
      (update explanation :seon.profile/lines
              #(into [(str "OVER ONE SECOND (" (:seon.profile/elapsed-ms explanation)
                           " ms). The armed definitions below grew in this JVM during the"
                           " operation, concurrent work included, so they name where time"
                           " went, not whose defect it is. Find what the top call's work is"
                           " proportional to (the whole program? the store? the call count?).")]
                     (rest %))))))

(defn- observation-line
  {:malli/schema [:=> [:cat :seon.profile/observation] :string]}
  [{sym :seon.profile/sym calls :seon.profile/calls total :seon.profile/total-ms
    longest :seon.profile/max-ms throws :seon.profile/throws scope :seon.profile/scope}]
  (str sym " inclusive total " total " ms, max " longest " ms, x" calls
       (when (pos? throws) (str ", " throws " threw"))
       (when (= :seon.profile/context scope) " [SCI]")))

(defn summary
  "`top` as one line per definition, for status surfaces an agent reads.

  Every time is inclusive: a caller's line contains its callees', so nested
  definitions repeat down a call chain; no self time is derived.

  Over-second lines are bounded by `limit` too; the count says how many
  definitions exceeded one second in all."
  {:malli/schema [:=> [:cat [:vector :seon.profile/cell] [:int {:min 1}]] :seon.profile/summary]}
  [population limit]
  (let [{by-total :seon.profile/by-total by-max :seon.profile/by-max
         over :seon.profile/over-second observed :seon.profile/observed}
        (top population limit)]
    {:seon.profile/observed observed
     :seon.profile/over-second-count (count over)
     :seon.profile/by-total-lines (mapv observation-line by-total)
     :seon.profile/by-max-lines (mapv observation-line by-max)
     :seon.profile/over-second-lines (into [] (comp (take limit) (map observation-line)) over)}))
