(ns seon.profile
  "Cumulative inclusive call timing held by the armed wrappers, and the work a
  world did, as its transactions describe it.

  Each armed callable owns one cell of JDK accumulators, created when it is
  installed: a log2 latency histogram (one LongAdder per bucket, so calls are
  its sum), total nanoseconds, self nanoseconds, lifetime maximum nanoseconds, entries (in flight
  is entries minus calls) and throws by class. `seon.instrument` brackets every
  call with two `System/nanoTime` reads and updates the cell directly (`timed`);
  the call path constructs no map, reads no database and calls no armed
  function. p50, p99 and the error ratio derive on demand from the counts.

  A call made inside an evaluation's world also counts into that world's call
  sink: its calls, nanoseconds and self nanoseconds (its elapsed less its
  counted callees', from the same two clock reads), and again in the sink's
  cold half when it is one of the cell's first `:seon.profile/cold-calls` calls
  in this process (the cell's ordinal decides: call order, no clock). A
  definition's cold and warm calls are never averaged together.
  `transaction-observation` turns a sink's delta into the cost entities the
  world's next transaction carries (`:seon.fn.cost/entity`, one per symbol and
  scope, a cold and a warm sample); `live-costs` reads a request's own sink for
  its reply.

  A host cell belongs to a loaded Var's JVM wrapper and is attributed to no
  cluster. A context cell belongs to one SCI installation's wrapper. Cells are
  cumulative and never reset (`LongAdder.sum` is not an atomic snapshot, and
  independent resets would split samples: JDK `LongAdder.java` sumThenReset).
  Reads are approximate under concurrent calls. A JVM starts every cell from
  zero (owner ruling): what survives a restart is what was stored.

  Times are inclusive wall time unless named self: a caller's total contains
  its callees' and its waits; its self time is its total less its counted
  armed callees' (what ran or waited in the callable's own body, and in any
  unarmed code it called), so a wrapper ranks below the work it wraps.

  A call is one outermost entry per thread: a call that re-enters the same
  cell on its thread (one arity of a multi-arity function calling another
  through the armed Var, or recursion through it) is inside that entry's time
  and is not counted again. Counting each re-entry inflated calls and totals
  (`seon.cluster/refresh-source!` showed x3 and three inclusive totals for one
  call).

  # Costs
  Measured in the landing note (docs/research/agent-platform/landing/
  health-recording-2026-09-28.md): the bracket against the previous one at one
  and eight threads, and a cell's bytes."
  (:require [seon.batch]
            [seon.schema.edn :as schema.edn])
  (:import [java.util Collections WeakHashMap]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLongArray LongAccumulator LongAdder]
           [java.util.function LongBinaryOperator Supplier]))

(schema.edn/load! {})

(def slow-ms
  "The owner's bound (2026-09-23): every operation over one second is explained."
  1000)

(def buckets
  "Histogram buckets: bucket b counts calls of [2^(b-1), 2^b) nanoseconds
  (bucket 0 the zero-nanosecond call); the last also holds everything longer."
  40)


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

(defn histogram?
  "True for a cell's histogram: an array of `buckets` LongAdders."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (instance? (Class/forName "[Ljava.util.concurrent.atomic.LongAdder;") value)
       (= buckets (alength ^objects value))))

(defn throw-counts?
  "True for a cell's throws by class: a ConcurrentHashMap of Class to LongAdder."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? ConcurrentHashMap value))

(defn counts?
  "True for a counts array: `buckets` histogram counts and the throws, as longs."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (boolean (and (some-> value class .isArray)
                (= Long/TYPE (.getComponentType (class value)))
                (= (inc buckets) (alength ^longs value)))))

(def ^:private longest
  (reify LongBinaryOperator
    (applyAsLong [_ a b] (Math/max a b))))

(def ^java.util.function.Function new-counter
  "The one function a cell's throws by class makes a class's first counter with."
  (reify java.util.function.Function
    (apply [_ _] (LongAdder.))))

;; The roster of live cells, keyed by executed definition `[sym digest]` (a
;; nil digest is unknown), each key's cells held weakly: a cell is reachable
;; from its wrapper's metadata, so a replaced wrapper or a released SCI
;; context releases its cell. One key has one cell per scope and context
;; (2,563 live cells over 2,560 keys, 2026-09-28). Written at installation
;; only; read by `cells` and `cells-of`.
(defonce ^:private roster (ConcurrentHashMap.))

(def ^:private ^java.util.function.Function new-member-set
  (reify java.util.function.Function
    (apply [_ _] (Collections/synchronizedSet (Collections/newSetFromMap (WeakHashMap.))))))

(defn cell
  "A new cumulative cell for one installed callable.

  `callable-identity` names the callable's symbol, its definition digest when the
  installer holds one, its scope and how many of its first calls in this
  process are cold. A missing digest is unknown, never a substitute."
  {:malli/schema [:=> [:cat :seon.profile/identity] :seon.profile/cell]}
  [callable-identity]
  (let [created (merge (select-keys callable-identity [:seon.profile/sym :seon.profile/digest
                                                       :seon.profile/scope :seon.profile/context
                                                       :seon.profile/cold-calls])
                       {:seon.profile/ordinal (java.util.concurrent.atomic.AtomicLong.)
                        :seon.profile/histogram (into-array LongAdder (repeatedly buckets #(LongAdder.)))
                        :seon.profile/total-counter (LongAdder.)
                        :seon.profile/self-counter (LongAdder.)
                        :seon.profile/max-counter (LongAccumulator. longest 0)
                        :seon.profile/entry-counter (LongAdder.)
                        :seon.profile/throw-counts (ConcurrentHashMap.)
                        :seon.profile/entered (ThreadLocal.)})]
    (.add ^java.util.Set (.computeIfAbsent ^ConcurrentHashMap roster
                                           [(:seon.profile/sym created) (:seon.profile/digest created)]
                                           new-member-set)
          created)
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
         ~(with-meta '__profile-histogram {:tag 'objects})
         (:seon.profile/histogram cell#)
         ~(with-meta '__profile-total {:tag 'java.util.concurrent.atomic.LongAdder})
         (:seon.profile/total-counter cell#)
         ~(with-meta '__profile-self {:tag 'java.util.concurrent.atomic.LongAdder})
         (:seon.profile/self-counter cell#)
         ~(with-meta '__profile-max {:tag 'java.util.concurrent.atomic.LongAccumulator})
         (:seon.profile/max-counter cell#)
         ~(with-meta '__profile-entries {:tag 'java.util.concurrent.atomic.LongAdder})
         (:seon.profile/entry-counter cell#)
         ~(with-meta '__profile-throws {:tag 'java.util.concurrent.ConcurrentHashMap})
         (:seon.profile/throw-counts cell#)
         ~(with-meta '__profile-entered {:tag 'java.lang.ThreadLocal})
         (:seon.profile/entered cell#)
         ~(with-meta '__profile-ordinal {:tag 'java.util.concurrent.atomic.AtomicLong})
         (:seon.profile/ordinal cell#)
         ~'__profile-cold (long (:seon.profile/cold-calls cell# 0))
         ;; the executed definition's identity, once: its symbol and held
         ;; definition digest (nil is unknown), the key of a world's call sink
         ~'__profile-key [(:seon.profile/sym cell#) (:seon.profile/digest cell#)]]
     ~@body))

(def ^java.util.function.Function new-adder
  "The one function a call sink makes an executed definition's first counter
  with: `[calls nanos self-nanos calls-stamped self-stamped nanos-stamped]` for
  every call, then the same six for its cold calls alone (`cold-slot` on):
  the stamped slots are where `transaction-observation` last read the others."
  (reify java.util.function.Function
    (apply [_ _] (AtomicLongArray. 12))))

(def cold-slot
  "The first slot of a sink counter's cold half (`new-adder`)."
  6)

(defn world-counter?
  "True for a world's counter array: a call sink's counter as `new-adder`
  makes it, or a writer meter (`seon.db/writer-meter`)."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? AtomicLongArray value))

(def ^ThreadLocal callee-frame
  "Per thread, the nanoseconds the counted armed calls inside the current
  counted entry have used: self time is an entry's elapsed less this, from the
  bracket's own two clock reads (profiling research Part A §2.2)."
  (ThreadLocal/withInitial (reify Supplier (get [_] (long-array 1)))))

(defmacro bucket-of
  "The histogram bucket of `elapsed` nanoseconds: its bit length, the last
  bucket holding everything longer. One leading-zeros instruction."
  [elapsed]
  `(let [b# (- 64 (Long/numberOfLeadingZeros ~elapsed))]
     (if (< b# ~(dec buckets)) b# ~(dec buckets))))

(defn bucket
  "The histogram bucket of `elapsed` nanoseconds (`bucket-of` as a function)."
  {:malli/schema [:=> [:cat nat-int?] [:int {:min 0 :max 39}]]}
  [elapsed]
  (bucket-of (long elapsed)))

(def host-entry
  "The re-entry marker of an armed call made outside any evaluation's world."
  (Object.))

(defn- hinted
  "A fresh local symbol tagged `tag`, for a macro expansion. Built outside the
  syntax-quote so every reader (the MCP evaluator's included) keeps the tag a
  class name."
  {:malli/schema [:=> [:cat :string :symbol] :symbol]}
  [prefix tag]
  (with-meta (gensym prefix) {:tag tag}))

(defmacro exited
  "Count one exit of an entry made at `started`, from the one clock read here.
  Its self time is its elapsed less what its counted callees added to the
  thread's `callee-frame` (reset at entry, the caller's total `saved`); the
  caller's frame then holds `saved` plus this elapsed. Into the world counter
  `counted` (nil outside a world): a call, its nanoseconds and its self
  nanoseconds, and into its cold half too when `cold` (one of the cell's
  first calls in this process); for an `outer`most entry, into the cell
  `with-cell` bound."
  [outer counted cold started frame saved]
  (let [elapsed (gensym "elapsed")
        c (hinted "counted" 'java.util.concurrent.atomic.AtomicLongArray)
        b (hinted "bucket" 'java.util.concurrent.atomic.LongAdder)]
    `(let [~elapsed (- (System/nanoTime) ~started)
           self# (- ~elapsed (aget ~frame 0))]
       (aset ~frame 0 (+ ~saved ~elapsed))
       (when-some [~c ~counted]
         (. ~c ~'incrementAndGet 0)
         (. ~c ~'addAndGet 1 ~elapsed)
         (. ~c ~'addAndGet 2 self#)
         (when ~cold
           (. ~c ~'incrementAndGet 6)
           (. ~c ~'addAndGet 7 ~elapsed)
           (. ~c ~'addAndGet 8 self#)))
       (when ~outer
         (let [~b (aget ~'__profile-histogram (seon.profile/bucket-of ~elapsed))]
           (. ~b ~'increment))
         (. ~'__profile-total ~'add ~elapsed)
         (. ~'__profile-self ~'add self#)
         (. ~'__profile-max ~'accumulate ~elapsed)))))

(defmacro timed
  "Evaluate `body` inside `with-cell`, counting its completion and elapsed time
  once per outermost entry on this thread, and once per world entered.

  The world (`seon.batch/*world*`) is read once. The cell's thread entry holds
  the call sink of the world it was entered in (its `:seon.batch/sink`, else
  `host-entry`). A re-entry of the same cell in the same world runs `body`
  uncounted: its time is inside the outer entry's, self time included. A
  re-entry under another world (a nested test agent) counts one call into that
  world's sink only; the JVM-wide counters keep their outermost entry. A
  world's count is keyed by the executed definition (`[sym digest]`) and taken
  at entry, so an entered, unfinished call is evidence; its calls, nanoseconds
  and self nanoseconds are added at exit from the same clock read as the
  cell's. Each entry restores the prior one on exit. A throw is counted as a
  completed call and as a throw of its class, then rethrown unchanged. Two
  clock reads per counted entry, never more. A counted entry among the cell's
  first `:seon.profile/cold-calls` in this process is cold: once past them
  the ordinal is only read, never written."
  [& body]
  (let [prior (gensym "prior")
        sink (hinted "sink" 'java.util.concurrent.ConcurrentHashMap)
        here (gensym "here")
        outer (gensym "outer")
        started (gensym "started")
        counted (gensym "counted")
        cold (gensym "cold")
        frames (hinted "frames" 'java.lang.ThreadLocal)
        frame (hinted "frame" 'longs)
        saved (gensym "saved")
        value (gensym "value")
        failure (gensym "failure")
        thrown (hinted "thrown" 'java.util.concurrent.atomic.LongAdder)]
    `(let [~prior (. ~'__profile-entered ~'get)
           ~sink (:seon.batch/sink seon.batch/*world*)
           ~here (or ~sink seon.profile/host-entry)]
       (if (identical? ~prior ~here)
         (do ~@body)
         (let [~outer (nil? ~prior)
               ~started (System/nanoTime)
               ~counted (when (some? ~sink)
                          (. ~sink ~'computeIfAbsent ~'__profile-key seon.profile/new-adder))
               ~cold (and (< (. ~'__profile-ordinal ~'get) ~'__profile-cold)
                          (<= (. ~'__profile-ordinal ~'incrementAndGet) ~'__profile-cold))
               ~frames seon.profile/callee-frame
               ~frame (. ~frames ~'get)
               ~saved (aget ~frame 0)]
           (aset ~frame 0 0)
           (when ~outer (. ~'__profile-entries ~'increment))
           (. ~'__profile-entered ~'set ~here)
           (try
             (let [~value (do ~@body)]
               (seon.profile/exited ~outer ~counted ~cold ~started ~frame ~saved)
               ~value)
             (catch Throwable ~failure
               (seon.profile/exited ~outer ~counted ~cold ~started ~frame ~saved)
               (when ~outer
                 (let [~thrown (. ~'__profile-throws ~'computeIfAbsent (class ~failure) seon.profile/new-counter)]
                   (. ~thrown ~'increment)))
               (throw ~failure))
             (finally
               (. ~'__profile-entered ~'set ~prior))))))))

;;; ---------------------------------------------------------------------------
;;; Reads — pure over the cells; nothing here resets a counter
;;; ---------------------------------------------------------------------------

(defn cells
  "Every live cell, host and context."
  {:malli/schema [:=> [:cat] [:vector :seon.profile/held-cell]]}
  []
  ;; a synchronized set's toArray holds its own lock
  (into [] (mapcat (fn [^java.util.Set members] (.toArray members)))
        (.values ^ConcurrentHashMap roster)))

(defn cells-of
  "The live cells of the executed definition `[sym digest]`."
  {:malli/schema [:=> [:cat :seon.batch/executed-key] [:vector :seon.profile/cell]]}
  [executed-key]
  (if-let [^java.util.Set members (.get ^ConcurrentHashMap roster executed-key)]
    (vec (.toArray members))
    []))

(defn counts
  "A cell's histogram counts and throws, `[b0 ... b39 throws]`, as one array."
  {:malli/schema [:=> [:cat :seon.profile/cell] :seon.profile/counts]}
  [held]
  (let [^objects histogram (:seon.profile/histogram held)
        out (long-array (inc buckets))]
    (dotimes [b buckets]
      (aset out b (.sum ^LongAdder (aget histogram b))))
    (aset out buckets (long (reduce (fn [n ^LongAdder a] (+ n (.sum a))) 0
                                    (.values ^ConcurrentHashMap (:seon.profile/throw-counts held)))))
    out))

(defn histogram-calls
  "The calls a counts array holds: its buckets' sum."
  {:malli/schema [:=> [:cat :seon.profile/counts] nat-int?]}
  [^longs counted]
  (loop [b 0 n 0] (if (< b buckets) (recur (inc b) (+ n (aget counted b))) n)))

(defn quantile-position
  "The log2-nanosecond position of quantile `q` of `total` calls in `counted`:
  a call in bucket b lies in [b-1, b), interpolated by its rank inside the
  bucket; the zero bucket is 0.

  The rank never exceeds `total` - 1: a quantile never rests on the single
  largest call, so one outlier (a collection pause) in a window under 100
  calls cannot move a p99. From 100 calls on the clamp changes nothing. The
  largest call is the lifetime maximum, reported beside it."
  {:malli/schema [:=> [:cat :seon.profile/counts pos-int? [:double {:min 0.0 :max 1.0}]] [:double {:min 0.0}]]}
  [^longs counted total q]
  (let [target (max 1.0 (min (* (double q) (double total)) (double (dec total))))]
    (loop [b 0 below 0]
      (let [n (aget counted b)
            upto (+ below n)]
        (if (or (and (pos? n) (>= upto target)) (= b (dec buckets)))
          (if (zero? b)
            0.0
            (+ (double (dec b)) (if (pos? n) (min 1.0 (max 0.0 (/ (- target below) (double n)))) 0.0)))
          (recur (inc b) upto))))))

(defn bucket-at
  "The bucket a log2 position lies in: [b-1, b) is bucket b."
  {:malli/schema [:=> [:cat [:double {:min 0.0}]] [:int {:min 0 :max 39}]]}
  [p]
  (min (dec buckets) (inc (long (Math/floor p)))))

(defn- upper-us
  "The upper edge of bucket `b` in whole microseconds."
  {:malli/schema [:=> [:cat [:int {:min 0 :max 39}]] nat-int?]}
  [b]
  (quot (bit-shift-left 1 b) 1000))

(defn observation
  "One cell's cumulative counters as a value.

  Its p50 and p99 are the upper edges of their histogram buckets (within a
  factor of two); beside them the calls in flight and the throws by class."
  {:malli/schema [:=> [:cat :seon.profile/cell] :seon.profile/observation]}
  [held]
  (let [counted (counts held)
        calls (histogram-calls counted)
        total (.sum ^LongAdder (:seon.profile/total-counter held))
        by-class (into {} (map (fn [[^Class k ^LongAdder v]] [(.getName k) (.sum v)]))
                       (:seon.profile/throw-counts held))]
    (cond-> (merge (select-keys held [:seon.profile/sym :seon.profile/digest
                                      :seon.profile/scope :seon.profile/context])
                   {:seon.profile/calls calls
                    :seon.profile/total-ms (quot total 1000000)
                    :seon.profile/self-ms (quot (.sum ^LongAdder (:seon.profile/self-counter held)) 1000000)
                    :seon.profile/mean-us (if (pos? calls) (quot total (* 1000 calls)) 0)
                    :seon.profile/self-mean-us (if (pos? calls)
                                                 (quot (.sum ^LongAdder (:seon.profile/self-counter held)) (* 1000 calls))
                                                 0)
                    :seon.profile/max-ms (quot (.get ^LongAccumulator (:seon.profile/max-counter held)) 1000000)
                    :seon.profile/throws (aget ^longs counted buckets)
                    :seon.profile/in-flight (max 0 (- (.sum ^LongAdder (:seon.profile/entry-counter held)) calls))})
      (pos? calls) (assoc :seon.profile/p50-us (upper-us (bucket-at (quantile-position counted calls 0.5)))
                          :seon.profile/p99-us (upper-us (bucket-at (quantile-position counted calls 0.99))))
      (seq by-class) (assoc :seon.profile/throws-by-class by-class))))

(def hot-limit
  "Definitions `measure` ranks by self time and by calls."
  20)

(defn- cost-scope
  "The scope of the executed definition `executed-key`: an interpreted SCI
  definition's when any of its live cells is a context cell, else the host's."
  {:malli/schema [:=> [:cat :seon.batch/executed-key] :seon.profile/scope]}
  [executed-key]
  (if (some #(= :seon.profile/context (:seon.profile/scope %)) (cells-of executed-key))
    :seon.profile/context
    :seon.profile/host))

(defn- cost-entity
  "One executed definition's cost entity from its row (calls, nanoseconds and
  self nanoseconds of all its calls, then of its cold calls alone) and scope:
  its warm sample (the calls that were not cold) and its cold sample, each
  per-call means in microseconds, each present when `qualifies?` it (its
  half, calls and self nanoseconds); nil when neither does."
  {:malli/schema [:=> [:cat [:tuple :seon.batch/executed-key :int :int :int :int :int :int]
                       :seon.profile/scope [:=> [:cat [:enum :warm :cold] :int :int] :boolean]]
                  [:maybe :seon.fn.cost/entity]]}
  [[[sym digest] calls nanos self cold-calls cold-nanos cold-self] scope qualifies?]
  (let [mean (fn [n total self] [digest n (/ (double self) n 1000.0) (/ (double total) n 1000.0)])
        warm-calls (- calls cold-calls)
        warm (when (qualifies? :warm warm-calls (- self cold-self))
               (mean warm-calls (- nanos cold-nanos) (- self cold-self)))
        cold (when (qualifies? :cold cold-calls cold-self) (mean cold-calls cold-nanos cold-self))]
    (when (or warm cold)
      (cond-> {:seon.fn.cost/key [sym scope]}
        warm (seon.batch/with-sample :warm warm)
        cold (seon.batch/with-sample :cold cold)))))

(defn undigested
  "The symbols `sink` counted under no definition digest: interpreted
  definitions armed at their write, before the request derived their digest
  (`seon.sci.arm/arm!`). Their callers resolve them on the branch."
  {:malli/schema [:=> [:cat :seon.batch/sink] [:vector :qualified-symbol]]}
  [sink]
  (into [] (keep (fn [[sym digest]] (when (nil? digest) sym))) (.keySet ^ConcurrentHashMap sink)))

(defn- selected
  "`worked` (`[[sym digest] calls nanos self-nanos cold-calls cold-nanos
  cold-self-nanos]` rows) as cost entities, one per symbol and scope: a row
  without a digest takes its symbol's from `digests` (else is dropped), rows
  of one executed definition are summed, one digest per symbol and scope (its
  most called). A warm sample needs at least `min-calls` warm calls, a cold
  sample one cold call, and each at least `min-total-us` microseconds of self
  time; the `limit` most self time spent first (ties by symbol): the same work
  selects the same definitions, and cheap predicates called often never
  qualify."
  {:malli/schema [:=> [:cat [:vector [:tuple :seon.batch/executed-key :int :int :int :int :int :int]]
                       [:map-of :qualified-symbol :seon.program/definition-digest]
                       :int :seon.config.fn.cost/min-total-us :seon.config.test.cost/top]
                  [:vector :seon.fn.cost/entity]]}
  [worked digests min-calls min-total-us limit]
  (let [qualifies? (fn [half calls self]
                     (and (pos? calls) (<= (if (= :warm half) min-calls 1) calls) (<= (* 1000 min-total-us) self)))]
    (->> worked
         (keep (fn [[[sym digest :as k] & row]]
                 (when-let [digest (or digest (get digests sym))]
                   [[sym digest] (vec row) (cost-scope k)])))
         (reduce (fn [m [k row scope]]
                   (update m k (fn [[_ summed sc]]
                                 [k (if summed (mapv + summed row) row)
                                  (if (= :seon.profile/context sc) sc scope)])))
                 {})
         vals
         (group-by (fn [[[sym] _ scope]] [sym scope]))
         (keep (fn [[_ rows]]
                 (let [[k row scope] (apply max-key #(first (second %)) rows)]
                   (cost-entity (into [k] row) scope qualifies?))))
         (sort-by (fn [e] [(- (seon.batch/spent e)) (first (:seon.fn.cost/key e))]))
         (into [] (take limit)))))

(defn live-costs
  "The request's own per-definition values from its sink, as cost entities:
  what a reply compares with main. The agent's interpreted definitions first,
  every one, then the host's spending at least `min-total-us` of self time,
  each by total self time (`selected`, any call count), at most `limit`. A
  definition counted under no digest takes its symbol's from `digests`.
  Reads only the sink's counters."
  {:malli/schema [:=> [:cat :seon.batch/sink [:map-of :qualified-symbol :seon.program/definition-digest]
                       :seon.config.fn.cost/min-total-us :seon.config.test.cost/top]
                  :seon.cost/live]}
  [sink digests min-total-us limit]
  (let [entities (selected (into [] (map (fn [[k ^AtomicLongArray c]]
                                           [k (.get c 0) (.get c 1) (.get c 2) (.get c 6) (.get c 7) (.get c 8)]))
                                 ^ConcurrentHashMap sink)
                           digests 1 0 Integer/MAX_VALUE)
        [own host] ((juxt filter remove) #(= :seon.profile/context (second (:seon.fn.cost/key %))) entities)]
    (into [] (take limit)
          (concat own (filter #(<= min-total-us (seon.batch/spent %)) host)))))

(defn transaction-observation
  "The cost entities of the work `world` did since its previous transaction:
  `selected` from its call sink, per-call means of that work alone, its cold
  calls apart from its warm ones. The writer's own armed calls count too:
  they are that world's work. Empty for no world or no sink. A definition
  counted under no digest takes its symbol's from `digests`.

  Read from the world's call sink (`:seon.batch/sink`): each counter's calls,
  nanoseconds and self nanoseconds, all and cold, less those at the world's
  previous transaction, which this call then marks (the sink's counters own
  the marks, so nothing beside them holds state). Two threads observing one
  world at once split its work between their transactions. The caller decides
  which samples moved (`moved?`) against the stored values.

  # Costs
  Proportional to the definitions the world executed: one pass over its
  sink, a sort of those that qualify."
  {:malli/schema [:=> [:cat [:maybe :map] [:map-of :qualified-symbol :seon.program/definition-digest]
                       :seon.config.fn.cost/min-calls :seon.config.fn.cost/min-total-us :seon.config.test.cost/top]
                  [:vector :seon.fn.cost/entity]]}
  [world digests min-calls min-total-us limit]
  (let [sink (:seon.batch/sink world)
        ;; the slots of one half since its mark, marking them: [calls nanos self]
        since! (fn [^AtomicLongArray c base]
                 (let [calls (.get c base) nanos (.get c (+ base 1)) self (.get c (+ base 2))]
                   [(- calls (.getAndSet c (+ base 3) calls))
                    (- nanos (.getAndSet c (+ base 5) nanos))
                    (- self (.getAndSet c (+ base 4) self))]))]
    (if-not (instance? ConcurrentHashMap sink)
      []
      (selected
       (into []
             (keep (fn [[k counter]]
                     (when (instance? AtomicLongArray counter)
                       (let [[made :as all] (since! counter 0)
                             cold (since! counter cold-slot)]
                         (when (pos? made)
                           (into (into [k] all) cold))))))
             ^ConcurrentHashMap sink)
       digests min-calls min-total-us limit))))

(defn moved?
  "True when the `live` sample should replace the `stored` one of the same
  half: none is stored, the definition digest changed, or the per-call self
  time differs by at least the factor `hysteresis` either way."
  {:malli/schema [:=> [:cat :seon.config.fn.cost/hysteresis [:maybe :seon.fn.cost/sample] :seon.fn.cost/sample] :boolean]}
  [hysteresis stored live]
  (let [[stored-digest _ a] stored
        [live-digest _ b] live]
    (boolean
     (or (nil? a)
         (not= stored-digest live-digest)
         (and (not= a b) (>= (max a b) (* hysteresis (min a b))))))))

(defn summary
  "The definitions that did the work, once, as data: `limit` rows ranked by
  self time (then inclusive total), and how many definitions had a call over
  one second.

  A row is the definition's calls, self, inclusive and maximum milliseconds,
  with its throws when it threw and `:seon.profile/scope` when it is an SCI
  wrapper. Self time is the row's own body and unarmed callees; a wrapper whose
  callback does the work ranks by its own time, and its callee's row carries
  the work. The maximum is one call, inclusive."
  {:malli/schema [:=> [:cat [:vector :seon.profile/held-cell] [:int {:min 1}]] :seon.profile/summary]}
  [population limit]
  (let [observed (into [] (comp (map observation) (filter (comp pos? :seon.profile/calls))) population)
        row (fn [{:seon.profile/keys [sym calls self-ms total-ms max-ms throws scope]}]
              (cond-> {:seon.profile/sym sym :seon.profile/calls calls :seon.profile/self-ms self-ms
                       :seon.profile/total-ms total-ms :seon.profile/max-ms max-ms}
                (pos? throws) (assoc :seon.profile/throws throws)
                (= :seon.profile/context scope) (assoc :seon.profile/scope scope)))]
    {:seon.profile/observed (count observed)
     :seon.profile/over-second-count (count (filter #(< slow-ms (:seon.profile/max-ms %)) observed))
     :seon.profile/by-self (into [] (comp (take limit) (map row))
                                 (sort-by (juxt :seon.profile/self-ms :seon.profile/total-ms) #(compare %2 %1) observed))}))

;;; ---------------------------------------------------------------------------
;;; A measured call — JVM counters around one function, as data
;;; ---------------------------------------------------------------------------

(def read-entrances
  "The `seon.db` functions that read a database value: the armed calls
  `measure` counts as reads."
  #{'seon.db/q 'seon.db/pull 'seon.db/pull-many 'seon.db/entity 'seon.db/datoms})

(def transaction-entrances
  "The `seon.db` function each commit attempt passes through once: the armed
  calls a request's cost counts as transactions."
  #{'seon.db/transact-call})

(defn sink-counts
  "A call sink's armed calls, the reads among them (`read-entrances`) and the
  transactions (`transaction-entrances`)."
  {:malli/schema [:=> [:cat :seon.batch/sink] :seon.cost/counts]}
  [sink]
  (reduce-kv (fn [counts sym n]
               (cond-> (update counts :seon.cost/calls + n)
                 (read-entrances sym) (update :seon.cost/reads + n)
                 (transaction-entrances sym) (update :seon.cost/transactions + n)))
             {:seon.cost/calls 0 :seon.cost/reads 0 :seon.cost/transactions 0}
             (seon.batch/calls sink)))

(defn measure
  "Call `f` once and return its value beside what the call cost, read from
  JVM counters, so compiled work inside it counts: wall ms, allocated bytes,
  GC collections and their accumulated time, the armed definitions ranked by
  self time and by calls (host and SCI wrappers' outermost entries, at most
  `hot-limit` each), every entered definition's entries (`calls-of` reads one)
  and the count of reads among them (`read-entrances`). A thrown exception
  propagates.

  Every counter but the allocation is JVM-wide over the call's window, so
  concurrent unrelated work counts too. The allocation is the calling platform
  thread's own (`:seon.profile/alloc-scope :thread`) and misses work it hands
  to other threads; on a virtual thread, which has no count, it is absent.
  `:seon.cost/jvm-window` also reports all-thread allocation and process CPU,
  explicitly including unrelated concurrent work, GC and compilation."
  {:malli/schema [:=> [:cat :seon.profile/thunk] :seon.profile/measured-call]}
  [f]
  (let [;; local functions, not armed ones: the second reading's own calls
        ;; would otherwise count as the measured call's
        mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        allocated (fn [] (.getCurrentThreadAllocatedBytes mx))
        collected (fn [] (reduce (fn [[n ms] ^java.lang.management.GarbageCollectorMXBean gc-bean]
                                   [(+ n (max 0 (.getCollectionCount gc-bean))) (+ ms (max 0 (.getCollectionTime gc-bean)))])
                                 [0 0] (java.lang.management.ManagementFactory/getGarbageCollectorMXBeans)))
        ;; symbol -> [entries self-nanos], summed over the symbol's cells
        entries (fn [] (persistent!
                        (reduce (fn [m ^java.util.Set members]
                                  (reduce (fn [m held]
                                            (let [n (.sum ^LongAdder (:seon.profile/entry-counter held))
                                                  sym (:seon.profile/sym held)
                                                  [n0 self0] (get m sym [0 0])]
                                              (if (pos? n)
                                                (assoc! m sym [(+ n n0) (+ self0 (.sum ^LongAdder (:seon.profile/self-counter held)))])
                                                m)))
                                          m (.toArray members)))
                                (transient {}) (.values ^ConcurrentHashMap roster))))
        window-before (seon.batch/jvm-counters)
        entries-before (entries)
        [gc-before gc-ms-before] (collected)
        allocated-before (allocated)
        started (System/nanoTime)
        value (f)
        ms (/ (Math/round (/ (- (System/nanoTime) started) 1e4)) 100.0)
        allocated-after (allocated)
        entries-after (entries)
        window-after (seon.batch/jvm-counters)
        [gc-after gc-ms-after] (collected)
        called (into []
                     (keep (fn [[sym [n self]]]
                             (let [[n0 self0] (get entries-before sym [0 0])
                                   d (- n n0)]
                               (when (pos? d)
                                 {:seon.profile/sym sym :seon.profile/calls d
                                  :seon.profile/self-ms (quot (- self self0) 1000000)}))))
                     entries-after)
        ranked (fn [k] (into [] (take hot-limit) (sort-by k #(compare %2 %1) called)))]
    (cond->
    {:seon.profile/value value
     :seon.cost/jvm-window (seon.batch/jvm-window window-before window-after)
     :seon.profile/ms ms
     :seon.profile/gc-count (- gc-after gc-before)
     :seon.profile/gc-ms (- gc-ms-after gc-ms-before)
     :seon.profile/calls (transduce (map :seon.profile/calls) + 0 called)
     :seon.profile/reads (transduce (comp (filter (comp read-entrances :seon.profile/sym)) (map :seon.profile/calls)) + 0 called)
     :seon.profile/ranked-by-self (ranked (juxt :seon.profile/self-ms :seon.profile/calls))
     :seon.profile/ranked-by-calls (ranked :seon.profile/calls)
     :seon.profile/called (into {} (map (juxt :seon.profile/sym :seon.profile/calls)) called)}
      (not (or (neg? allocated-before) (neg? allocated-after)))
      (assoc :seon.profile/alloc-bytes (- allocated-after allocated-before)
             :seon.profile/alloc-scope :thread))))

(defn calls-of
  "How many outermost entries the armed definition `sym` made over the
  `measured` call: 0 when it made none."
  {:malli/schema [:=> [:cat :seon.profile/measured-call :qualified-symbol] [:int {:min 0}]]}
  [measured sym]
  (get (:seon.profile/called measured) sym 0))
