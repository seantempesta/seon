(ns seon.profile
  "Cumulative inclusive call timing held by the armed wrappers, and the work a
  world did, as its transactions describe it.

  Each armed callable owns one cell of JDK accumulators, created when it is
  installed: a log2 latency histogram (one LongAdder per bucket, so calls are
  its sum), total nanoseconds, self nanoseconds, lifetime maximum nanoseconds, entries (in flight
  is entries minus calls) and throws. `seon.instrument` brackets every
  call with two `System/nanoTime` reads and updates the cell directly (`bracket`);
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
  (:require [datahike.db.interface]
            [seon.batch]
            [seon.schema.edn :as schema.edn])
  (:import [java.util Collections WeakHashMap]
           [java.util.concurrent ConcurrentHashMap ThreadLocalRandom]
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

(def read-entrances
  "The `seon.db` functions that read a database value: the armed calls a cell
  counts as reads (`bracket`) and `measure` counts as reads."
  #{'seon.db/q 'seon.db/pull 'seon.db/pull-many 'seon.db/entity 'seon.db/datoms})

(def size-slots
  "A cell's sampled-size counters (`:seon.profile/sizes`): sampled calls, their
  summed touched size N, steps, reads and allocated bytes, the largest reads
  and steps per unit of N of one sampled call, in thousandths, then their self
  steps, self reads and self allocated bytes (their own body's: inclusive less
  their counted callees')."
  10)


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

;; The registry of live cells, keyed by executed definition `[sym digest]` (a
;; nil digest is unknown), each key's cells held weakly: a cell is reachable
;; from its wrapper's metadata, so a replaced wrapper or a released SCI
;; context releases its cell. One key has one cell per scope and context
;; (2,563 live cells over 2,560 keys, 2026-09-28). Written at installation
;; only; read by `cells` and `cells-of`.
(defonce ^:private cells-by-key (ConcurrentHashMap.))

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
                                                       :seon.profile/cold-calls :seon.profile/size-period])
                       {:seon.profile/ordinal (java.util.concurrent.atomic.AtomicLong.)
                        :seon.profile/histogram (into-array LongAdder (repeatedly buckets #(LongAdder.)))
                        :seon.profile/total-counter (LongAdder.)
                        :seon.profile/self-counter (LongAdder.)
                        :seon.profile/max-counter (LongAccumulator. longest 0)
                        :seon.profile/entry-counter (LongAdder.)
                        :seon.profile/throw-counter (LongAdder.)
                        :seon.profile/entered (ThreadLocal.)
                        :seon.profile/alloc-counter (LongAdder.)
                        :seon.profile/self-alloc-counter (LongAdder.)
                        :seon.profile/alloc-max-counter (LongAccumulator. longest 0)
                        :seon.profile/alloc-histogram (AtomicLongArray. buckets)
                        :seon.profile/alloc-unmeasured-counter (LongAdder.)
                        :seon.profile/reads-counter (LongAdder.)
                        :seon.profile/self-reads-counter (LongAdder.)
                        :seon.profile/reads-max-counter (LongAccumulator. longest 0)
                        :seon.profile/reads-histogram (AtomicLongArray. buckets)
                        :seon.profile/steps-counter (LongAdder.)
                        :seon.profile/sizes (AtomicLongArray. size-slots)
                        :seon.profile/read? (contains? read-entrances (:seon.profile/sym callable-identity))})]
    (.add ^java.util.Set (.computeIfAbsent ^ConcurrentHashMap cells-by-key
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

(def ^java.util.function.Function new-adder
  "The one function a call sink makes an executed definition's first counter
  with: `[calls nanos self-nanos calls-stamped self-stamped nanos-stamped]` for
  every call, then the same six for its cold calls alone (`cold-slot` on):
  the stamped slots are where `transaction-observation` last read the others.
  Then, from `volume-slot`, the measured calls' allocated bytes and self
  allocated bytes, all calls' reads, self reads and steps, and how many calls
  measured their allocation."
  (reify java.util.function.Function
    (apply [_ _] (AtomicLongArray. 18))))

(def cold-slot
  "The first slot of a sink counter's cold half (`new-adder`)."
  6)

(def volume-slot
  "The first of a sink counter's six volume slots (`new-adder`): allocated
  bytes and self allocated bytes of the calls that measured them, reads, self
  reads, steps, and the calls that measured their allocation."
  12)

(defn world-counter?
  "True for a world's counter array: a call sink's counter as `new-adder`
  makes it, or a writer meter (`seon.db/writer-meter`)."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? AtomicLongArray value))

(def ^ThreadLocal callee-frame
  "Per thread: [0] the nanoseconds and [1] the allocated bytes the counted armed
  calls inside the current counted entry have used, [2] the thread's reads so
  far (counted read entrances, `read-entrances`), [3] the reads inside the
  current entry's counted callees, [4] the thread's steps so far (counted
  entries), [5] how many entered calls on the thread are measuring their
  allocation (inside one, every call measures), [6] the steps inside the
  current entry's counted callees, [7] the touched size N of a sampled call
  that has just returned. Self figures are an entry's own less its callees',
  from the bracket's own readings (profiling research Part A §2.2)."
  (ThreadLocal/withInitial (reify Supplier (get [_] (long-array 8)))))

(def ^com.sun.management.ThreadMXBean thread-mx
  "HotSpot's thread bean: `getCurrentThreadAllocatedBytes` is the bracket's
  allocation reading (about 35 ns; -1 on a virtual thread, which keeps no count)."
  (java.lang.management.ManagementFactory/getThreadMXBean))

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

(def returned-entry
  "The thread entry a call leaves on its cell's `entered` once its function has
  returned: the bracket's exit, in `finally`, tells a return from a throw by
  it, without catching."
  (Object.))

(definterface Bracket
  (counted [f arguments])
  (exit [outer counter cold ^long started frame ^long saved ^long saved-alloc ^long saved-reads
         ^long saved-steps ^long reads0 ^long steps0 ^long alloc0 sampled returned]))

(defn bracket
  "The one counting entry of `held`'s callable: a `Bracket` whose `counted`
  applies `f` to `arguments` and counts the call once per outermost entry on
  its thread, and once per world entered. Made once, at installation, holding
  the cell's JDK accumulators, so a call performs no lookup.

  The world (`seon.batch/*world*`) is read once. The cell's thread entry holds
  the call sink of the world it was entered in (its `:seon.batch/sink`, else
  `host-entry`). A re-entry of the same cell in the same world runs uncounted:
  its time is inside the outer entry's, self time included. A re-entry under
  another world (a nested test agent) counts one call into that world's sink
  only; the JVM-wide counters keep their outermost entry. A world's count is
  keyed by the executed definition (`[sym digest]`) and taken at entry, so an
  entered, unfinished call is evidence. The exit is counted in `finally`, so a
  throw is counted as a completed call, and, when the entry never reached
  `returned-entry`, as a throw; the throw passes through untouched. Two clock
  reads and two allocation reads per measuring entry, never more. A counted
  entry among the cell's first `:seon.profile/cold-calls` in this process is
  cold.

  Self time, self allocation, self reads and self steps are the entry's own
  less what its counted callees added to the thread's `callee-frame`; reads
  and steps are the thread's counts since entry, and a read entrance adds its
  own read after taking them, so it counts in its callers; both are exact on
  every call. Allocation is sampled, because its reading costs about 35 ns and
  doubled a hot call measured exactly: one call in the cell's size period, at
  random, and every call inside one, read it, so a measured call's callees are
  measured and its self allocation is exact; per-call and total figures are
  estimated from the measured calls. It is the calling thread's own: work
  handed to another thread is not in it, and a virtual thread's call counts as
  unmeasured. A sampled call that returns also records its touched size N and
  its inclusive and self volumes (`size-slots`): N is its arguments' and
  value's extents, a counted Clojure collection its count, nil none, anything
  else (a database value included) one."
  {:malli/schema [:=> [:cat :seon.profile/cell] :seon.profile/bracket]}
  [held]
  (let [^objects histogram (:seon.profile/histogram held)
        ^LongAdder total-counter (:seon.profile/total-counter held)
        ^LongAdder self-counter (:seon.profile/self-counter held)
        ^LongAccumulator max-counter (:seon.profile/max-counter held)
        ^LongAdder entries (:seon.profile/entry-counter held)
        ^LongAdder throw-counter (:seon.profile/throw-counter held)
        ^ThreadLocal entered (:seon.profile/entered held)
        ^java.util.concurrent.atomic.AtomicLong ordinal (:seon.profile/ordinal held)
        cold-calls (long (:seon.profile/cold-calls held 0))
        ^LongAdder alloc-counter (:seon.profile/alloc-counter held)
        ^LongAdder self-alloc-counter (:seon.profile/self-alloc-counter held)
        ^LongAccumulator alloc-max (:seon.profile/alloc-max-counter held)
        ^AtomicLongArray alloc-histogram (:seon.profile/alloc-histogram held)
        ^LongAdder unmeasured (:seon.profile/alloc-unmeasured-counter held)
        ^LongAdder reads-counter (:seon.profile/reads-counter held)
        ^LongAdder self-reads-counter (:seon.profile/self-reads-counter held)
        ^LongAccumulator reads-max (:seon.profile/reads-max-counter held)
        ^AtomicLongArray reads-histogram (:seon.profile/reads-histogram held)
        ^LongAdder steps-counter (:seon.profile/steps-counter held)
        ^AtomicLongArray sizes (:seon.profile/sizes held)
        read? (boolean (:seon.profile/read? held))
        period (int (:seon.profile/size-period held 0))
        ;; the executed definition's identity, the key of a world's call sink
        executed-key [(:seon.profile/sym held) (:seon.profile/digest held)]
        ^com.sun.management.ThreadMXBean mx thread-mx
        ;; a Clojure collection's count; a database value is one (it is `counted?`, and
        ;; counting it walks its index: what a call reads of it is its reads, never N)
        extent (fn ^long [x] (cond (nil? x) 0
                                   (and (counted? x) (or (vector? x) (map? x) (set? x) (seq? x))
                                        (not (instance? datahike.db.interface.IDB x)))
                                   (count x)
                                   :else 1))
        touched (fn [arguments value] (reduce (fn [^long n a] (+ n (extent a))) (extent value) arguments))
        most! (fn [slot ^long m]
                (loop [] (let [c (.get sizes (int slot))]
                           (when (and (> m c) (not (.compareAndSet sizes (int slot) c m))) (recur)))))
        sized! (fn [n steps reads allocated self-steps self-reads self-allocated]
                 (let [n (long n)]
                   (.incrementAndGet sizes 0)
                   (.addAndGet sizes 1 n)
                   (.addAndGet sizes 2 steps)
                   (.addAndGet sizes 3 reads)
                   (.addAndGet sizes 4 allocated)
                   (most! 5 (quot (* 1000 (long reads)) (max 1 n)))
                   (most! 6 (quot (* 1000 (long steps)) (max 1 n)))
                   (.addAndGet sizes 7 self-steps)
                   (.addAndGet sizes 8 self-reads)
                   (.addAndGet sizes 9 self-allocated)))]
    (reify Bracket
      (counted [this f arguments]
        (let [prior (.get entered)
              ^ConcurrentHashMap sink (:seon.batch/sink seon.batch/*world*)
              here (or sink host-entry)]
          (if (identical? prior here)
            (apply f arguments)
            (let [outer (nil? prior)
                  ^AtomicLongArray counter (when (some? sink) (.computeIfAbsent sink executed-key new-adder))
                  cold (and (< (.get ordinal) cold-calls) (<= (.incrementAndGet ordinal) cold-calls))
                  ^longs frame (.get callee-frame)
                  saved (aget frame 0)
                  saved-alloc (aget frame 1)
                  saved-reads (aget frame 3)
                  saved-steps (aget frame 6)
                  reads0 (aget frame 2)
                  steps0 (inc (aget frame 4))
                  ;; one call in the period, at random, and every call inside one, reads allocation
                  sampled (and (pos? period) (zero? (.nextInt (ThreadLocalRandom/current) period)))
                  measuring (or sampled (pos? (aget frame 5)))
                  alloc0 (if measuring (.getCurrentThreadAllocatedBytes mx) -2)
                  started (System/nanoTime)]
              (aset frame 0 0)
              (aset frame 1 0)
              (aset frame 3 0)
              (aset frame 6 0)
              (aset frame 4 steps0)
              (when measuring (aset frame 5 (inc (aget frame 5))))
              (when outer (.increment entries))
              (.set entered here)
              (try
                (let [value (apply f arguments)]
                  ;; a sampled call's touched size, taken while its value is in hand
                  (when sampled (aset frame 7 (long (touched arguments value))))
                  (.set entered returned-entry)
                  value)
                (finally
                  (.exit this outer counter cold started frame saved saved-alloc saved-reads saved-steps
                         reads0 steps0 alloc0 sampled (identical? returned-entry (.get entered)))
                  (.set entered prior)))))))
      (exit [_ outer counter cold started frame saved saved-alloc saved-reads saved-steps reads0 steps0 alloc0 sampled returned]
        (let [^AtomicLongArray counter counter
              ^longs frame frame
              elapsed (- (System/nanoTime) started)
              alloc1 (if (= -2 alloc0) -2 (.getCurrentThreadAllocatedBytes mx))
              measured (and (<= 0 alloc0) (<= alloc0 alloc1))
              allocated (if measured (- alloc1 alloc0) 0)
              self (- elapsed (aget frame 0))
              self-alloc (max 0 (- allocated (aget frame 1)))
              reads (- (aget frame 2) reads0)
              self-reads (- reads (aget frame 3))
              steps (- (aget frame 4) steps0)
              self-steps (- steps (aget frame 6))]
          (aset frame 0 (+ saved elapsed))
          (aset frame 1 (+ saved-alloc allocated))
          (aset frame 3 (+ saved-reads reads))
          (aset frame 6 (+ saved-steps steps))
          (when read? (aset frame 2 (inc (aget frame 2))))
          (when-not (= -2 alloc0) (aset frame 5 (dec (aget frame 5))))
          (when counter
            (.incrementAndGet counter 0)
            (.addAndGet counter 1 elapsed)
            (.addAndGet counter 2 self)
            (when measured
              (.addAndGet counter 12 allocated)
              (.addAndGet counter 13 self-alloc)
              (.incrementAndGet counter 17))
            (.addAndGet counter 14 reads)
            (.addAndGet counter 15 self-reads)
            (.addAndGet counter 16 steps)
            (when cold
              (.incrementAndGet counter 6)
              (.addAndGet counter 7 elapsed)
              (.addAndGet counter 8 self)))
          (when outer
            (.increment ^LongAdder (aget histogram (bucket-of elapsed)))
            (.add total-counter elapsed)
            (.add self-counter self)
            (.accumulate max-counter elapsed)
            (if measured
              (do (.add alloc-counter allocated)
                  (.add self-alloc-counter self-alloc)
                  (.accumulate alloc-max allocated)
                  (.incrementAndGet alloc-histogram (bucket-of allocated)))
              (when (= -1 alloc0) (.increment unmeasured)))
            (.add reads-counter reads)
            (.add self-reads-counter self-reads)
            (.accumulate reads-max reads)
            (.incrementAndGet reads-histogram (bucket-of reads))
            (.add steps-counter steps)
            (if returned
              (when sampled (sized! (aget frame 7) steps reads allocated self-steps self-reads self-alloc))
              (.increment throw-counter))))))))

;;; ---------------------------------------------------------------------------
;;; Reads — pure over the cells; nothing here resets a counter
;;; ---------------------------------------------------------------------------

(defn cells
  "Every live cell, host and context."
  {:malli/schema [:=> [:cat] [:vector :seon.profile/held-cell]]}
  []
  ;; a synchronized set's toArray holds its own lock
  (into [] (mapcat (fn [^java.util.Set members] (.toArray members)))
        (.values ^ConcurrentHashMap cells-by-key)))

(defn cells-of
  "The live cells of the executed definition `[sym digest]`."
  {:malli/schema [:=> [:cat :seon.batch/executed-key] [:vector :seon.profile/cell]]}
  [executed-key]
  (if-let [^java.util.Set members (.get ^ConcurrentHashMap cells-by-key executed-key)]
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
    (aset out buckets (.sum ^LongAdder (:seon.profile/throw-counter held)))
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

(defn volume-quantile
  "The `q` quantile of a volume histogram (bucket b counts calls of
  [2^(b-1), 2^b) bytes or reads, bucket 0 the zero call) as the largest value
  its bucket holds: 0, 1, 3, 7 ... within a factor of two above. The rank
  never rests on the single largest call (`quantile-position`); 0 for no
  calls."
  {:malli/schema [:=> [:cat :seon.profile/volume-histogram [:double {:min 0.0 :max 1.0}]] nat-int?]}
  [^AtomicLongArray histogram q]
  (let [total (loop [b 0 n 0] (if (< b buckets) (recur (inc b) (+ n (.get histogram b))) n))
        target (max 1.0 (min (* (double q) (double total)) (double (max 1 (dec total)))))]
    (if (zero? total)
      0
      (loop [b 0 below 0]
        (let [upto (+ below (.get histogram b))]
          (if (or (>= upto target) (= b (dec buckets)))
            (if (zero? b) 0 (dec (bit-shift-left 1 b)))
            (recur (inc b) upto)))))))

(defn volumes
  "A cell's allocation, reads and steps as a value: inclusive and self totals
  (allocation estimated from its measured calls, scaled to all calls), the
  per-call p50, p95 and maximum of allocated bytes (over measured calls) and
  of reads (exact),
  the calls whose allocation went unmeasured (a virtual thread's), and its
  sampled sizes (`sized!`) with their per-unit reads, steps and bytes."
  {:malli/schema [:=> [:cat :seon.profile/cell] :seon.profile/volumes]}
  [held]
  (let [sum #(.sum ^LongAdder (get held %))
        ^AtomicLongArray sizes (:seon.profile/sizes held)
        samples (.get sizes 0)
        n (.get sizes 1)
        per-n #(/ (Math/round (* 1000.0 (/ (double %) (max 1 n)))) 1000.0)
        unmeasured (sum :seon.profile/alloc-unmeasured-counter)
        ^AtomicLongArray alloc-histogram (:seon.profile/alloc-histogram held)
        measured (loop [b 0 m 0] (if (< b buckets) (recur (inc b) (+ m (.get alloc-histogram b))) m))
        calls (sum :seon.profile/entry-counter)
        ;; allocation is read on sampled calls: totals are the measured calls' scaled to all calls
        scale (if (pos? measured) (/ (double calls) measured) 0.0)
        ;; a bucket's largest value can pass the exact maximum: a quantile never exceeds it
        alloc-max (.get ^LongAccumulator (:seon.profile/alloc-max-counter held))
        reads-max (.get ^LongAccumulator (:seon.profile/reads-max-counter held))]
    (cond-> {:seon.profile/alloc-mb (/ (Math/round (/ (* (sum :seon.profile/alloc-counter) scale) 1e3)) 1e3)
             :seon.profile/self-alloc-mb (/ (Math/round (/ (* (sum :seon.profile/self-alloc-counter) scale) 1e3)) 1e3)
             :seon.profile/alloc-measured measured
             :seon.profile/alloc-p50-bytes (min alloc-max (volume-quantile alloc-histogram 0.5))
             :seon.profile/alloc-p95-bytes (min alloc-max (volume-quantile alloc-histogram 0.95))
             :seon.profile/alloc-max-bytes alloc-max
             :seon.profile/reads (sum :seon.profile/reads-counter)
             :seon.profile/self-reads (sum :seon.profile/self-reads-counter)
             :seon.profile/reads-p50 (min reads-max (volume-quantile (:seon.profile/reads-histogram held) 0.5))
             :seon.profile/reads-p95 (min reads-max (volume-quantile (:seon.profile/reads-histogram held) 0.95))
             :seon.profile/reads-max reads-max
             :seon.profile/steps (sum :seon.profile/steps-counter)}
      (pos? unmeasured) (assoc :seon.profile/alloc-unmeasured unmeasured)
      (pos? samples) (assoc :seon.profile/sampled
                            {:seon.profile/samples samples
                             :seon.profile/size-mean (/ (Math/round (* 10.0 (/ (double n) samples))) 10.0)
                             :seon.profile/reads-per-n (per-n (.get sizes 3))
                             :seon.profile/steps-per-n (per-n (.get sizes 2))
                             :seon.profile/bytes-per-n (per-n (.get sizes 4))
                             :seon.profile/max-reads-per-n (/ (.get sizes 5) 1000.0)
                             :seon.profile/max-steps-per-n (/ (.get sizes 6) 1000.0)}))))

(defn observation
  "One cell's cumulative counters as a value.

  Its p50 and p99 are the upper edges of their histogram buckets (within a
  factor of two); beside them the calls in flight and the throws."
  {:malli/schema [:=> [:cat :seon.profile/cell] :seon.profile/observation]}
  [held]
  (let [counted (counts held)
        calls (histogram-calls counted)
        total (.sum ^LongAdder (:seon.profile/total-counter held))]
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
                          :seon.profile/p95-us (upper-us (bucket-at (quantile-position counted calls 0.95)))
                          :seon.profile/p99-us (upper-us (bucket-at (quantile-position counted calls 0.99))))
      (:seon.profile/alloc-counter held) (merge (volumes held)))))

(def hot-limit
  "Definitions `measure` ranks by self time and by calls."
  20)

(defn- cost-scope
  "The scope of an executed definition's live cells."
  {:malli/schema [:=> [:cat :seon.batch/executed-key] :seon.profile/scope]}
  [executed-key]
  (let [^java.util.Set members (.get ^ConcurrentHashMap cells-by-key executed-key)]
    (if (and members
             (some #(= :seon.profile/context (:seon.profile/scope %)) (.toArray members)))
      :seon.profile/context
      :seon.profile/host)))

(defn- cost-entity
  "The selected warm and cold samples of one executed definition."
  {:malli/schema [:=> [:cat [:tuple :seon.batch/executed-key :int :int :int :int :int :int]
                       :seon.profile/scope :boolean :boolean]
                  :seon.fn.cost/entity]}
  [[[sym digest] calls nanos self cold-calls cold-nanos cold-self] scope warm? cold?]
  (let [mean (fn [n total self] [digest n (/ (double self) n 1000.0) (/ (double total) n 1000.0)])
        warm-calls (- calls cold-calls)]
    (cond-> {:seon.fn.cost/key [sym scope]}
      warm? (seon.batch/with-sample :warm (mean warm-calls (- nanos cold-nanos) (- self cold-self)))
      cold? (seon.batch/with-sample :cold (mean cold-calls cold-nanos cold-self)))))

(defn undigested
  "The symbols `sink` counted under no definition digest: interpreted
  definitions armed at their write, before the request derived their digest
  (`seon.sci.arm/arm!`). Their callers resolve them on the branch."
  {:malli/schema [:=> [:cat :seon.batch/sink] [:vector :qualified-symbol]]}
  [sink]
  (into [] (keep (fn [[sym digest]] (when (nil? digest) sym))) (.keySet ^ConcurrentHashMap sink)))

(defn- selected
  "Cost entities for selected counter samples, ranked before construction.
  Sum each executed definition, select its most called digest per scope,
  then apply the sample thresholds. Live replies keep every context sample
  and select host definitions by combined self time; transactions select
  warm and cold samples separately. Only the first `limit` become entities."
  {:malli/schema [:=> [:cat [:vector [:tuple :seon.batch/executed-key :int :int :int :int :int :int]]
                       [:map-of :qualified-symbol :seon.program/definition-digest]
                       :int :seon.config.fn.cost/min-total-us :seon.config.test.cost/top :boolean]
                  [:vector :seon.fn.cost/entity]]}
  [worked digests min-calls min-total-us limit live?]
  (let [floor (* 1000 min-total-us)]
    (->> worked
         (keep (fn [[[sym digest :as k] & counters]]
                 (when-let [digest (or digest (get digests sym))]
                   [[sym digest] (vec counters) (cost-scope k)])))
         (reduce (fn [m [k counters scope]]
                   (update m k (fn [[_ summed sc]]
                                 [k (if summed (mapv + summed counters) counters)
                                  (if (= :seon.profile/context sc) sc scope)])))
                 {})
         vals
         (group-by (fn [[[sym] _ scope]] [sym scope]))
         (keep (fn [[_ samples]]
                 (let [[k [calls _ self cold-calls _ cold-self :as counters] scope]
                       (apply max-key #(first (second %)) samples)
                       context? (= :seon.profile/context scope)
                       warm-calls (- calls cold-calls)
                       warm-self (- self cold-self)
                       warm? (and (pos? warm-calls)
                                  (or live? (and (<= min-calls warm-calls) (<= floor warm-self))))
                       cold? (and (pos? cold-calls) (or live? (<= floor cold-self)))]
                   (when (and (or warm? cold?)
                              (or (not live?) context? (<= floor self)))
                     [[(if (and live? context?) 0 1)
                       (- (+ (if warm? warm-self 0) (if cold? cold-self 0))) (first k)]
                      (into [k] counters) scope warm? cold?]))))
         (sort-by first)
         (take limit)
         (mapv (fn [[_ counters scope warm? cold?]]
                 (cost-entity counters scope warm? cold?))))))

(defn live-costs
  "Selected request costs: every context definition first, then host
  definitions meeting the combined self-time floor, at most `limit`.
  A definition without a digest takes its symbol's from `digests`."
  {:malli/schema [:=> [:cat :seon.batch/sink [:map-of :qualified-symbol :seon.program/definition-digest]
                       :seon.config.fn.cost/min-total-us :seon.config.test.cost/top]
                  :seon.cost/live]}
  [sink digests min-total-us limit]
  (selected (into [] (map (fn [[k ^AtomicLongArray c]]
                           [k (.get c 0) (.get c 1) (.get c 2) (.get c 6) (.get c 7) (.get c 8)]))
                  ^ConcurrentHashMap sink)
            digests 1 min-total-us limit true))

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
       digests min-calls min-total-us limit false))))

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
;;; Volumes — allocation, reads and steps, per request and per definition
;;; ---------------------------------------------------------------------------

(defn top-functions
  "A request's functions from its call sink, `limit` ranked by self allocated
  bytes (scaled from the calls that measured it, `bracket`) and `limit`
  ranked by self reads, each only when positive, every executed definition of
  a symbol summed: each with its calls, inclusive ms,
  inclusive and self MB, inclusive and self reads, steps, and per call its
  KB and reads. Self is the function's own body: a wrapper ranks below the
  work it wraps, and the function issuing the reads ranks by them."
  {:malli/schema [:=> [:cat :seon.batch/sink :seon.config.test.cost/top] :seon.cost/functions]}
  [sink limit]
  (let [summed (reduce (fn [m [[sym _] ^AtomicLongArray c]]
                         (update m sym (fn [v] (let [v (or v (long-array 8))
                                                     ^longs v v]
                                                 (aset v 0 (+ (aget v 0) (.get c 0)))
                                                 (aset v 1 (+ (aget v 1) (.get c 1)))
                                                 (dotimes [i 6] (aset v (+ 2 i) (+ (aget v (+ 2 i)) (.get c (+ volume-slot i)))))
                                                 v))))
                       {} ^ConcurrentHashMap sink)
        ;; allocation is read on sampled calls: scaled from the measured ones to all
        scaled (fn [^longs v slot] (if (pos? (aget v 7)) (* (aget v slot) (/ (double (aget v 0)) (aget v 7))) 0.0))
        row (fn [[sym ^longs v]]
              (let [calls (max 1 (aget v 0))]
                {:seon.cost.function/sym sym
                 :seon.cost.function/calls (aget v 0)
                 :seon.cost.function/ms (/ (Math/round (/ (aget v 1) 1e5)) 10.0)
                 :seon.cost.function/alloc-mb (/ (Math/round (/ (scaled v 2) 1e3)) 1e3)
                 :seon.cost.function/self-alloc-mb (/ (Math/round (/ (scaled v 3) 1e3)) 1e3)
                 :seon.cost.function/alloc-measured (aget v 7)
                 :seon.cost.function/reads (aget v 4)
                 :seon.cost.function/self-reads (aget v 5)
                 :seon.cost.function/steps (aget v 6)
                 :seon.cost.function/kb-per-call (/ (Math/round (/ (scaled v 2) calls)) 1e3)
                 :seon.cost.function/reads-per-call (/ (Math/round (* 100.0 (/ (double (aget v 4)) calls))) 100.0)}))
        ranked (fn [weight]
                 (into [] (comp (filter (fn [[_ v]] (pos? (weight v)))) (take limit) (map row))
                       (sort-by (fn [[_ v]] (- (weight v))) summed)))]
    {:seon.cost.functions/by-alloc (ranked #(scaled % 3))
     :seon.cost.functions/by-reads (ranked (fn [^longs v] (aget v 5)))}))

(defn figures
  "The live per-call figures of the executed definition `[sym digest]`, as
  `doc` shows them: its most-called live cell's calls, per-call ms p50/p95/max,
  allocated bytes and reads p50/p95/max, and its sampled sizes. Absent when no
  live cell of that definition has been called: a changed body starts fresh."
  {:malli/schema [:=> [:cat :seon.batch/executed-key] [:maybe :seon.profile/figures]]}
  [executed-key]
  (let [held (reduce (fn [best member]
                       (let [o (observation member)]
                         (if (< (:seon.profile/calls best 0) (:seon.profile/calls o)) o best)))
                     nil (cells-of executed-key))]
    (when (pos? (:seon.profile/calls held 0))
      (cond-> (select-keys held [:seon.profile/calls :seon.profile/scope :seon.profile/p50-us :seon.profile/p95-us
                                 :seon.profile/alloc-p50-bytes :seon.profile/alloc-p95-bytes :seon.profile/alloc-max-bytes
                                 :seon.profile/reads-p50 :seon.profile/reads-p95 :seon.profile/reads-max
                                 :seon.profile/alloc-unmeasured :seon.profile/sampled])
        true (assoc :seon.profile/max-us (* 1000 (:seon.profile/max-ms held)))))))

(def dimensions
  "The default cost contract's dimensions and the sampled-size slots each
  reads (`size-slots`), inclusive then self: steps, reads and allocated bytes."
  {:steps [2 7] :reads [3 8] :bytes [4 9]})

(defn pain
  "The whole system's pain, ranked: for every live cell with sampled sizes,
  per dimension (`dimensions`) the cost its sampled calls spent in their own
  bodies (self: inclusive less their counted callees') beyond the default
  cost contract `k × N + c` (`contract`: dimension -> [k c], default the
  shipped `:seon.config.fn.cost/steps-k` ... `bytes-c` dials), scaled from its
  samples to all its calls, its overrun ratio (spent over allowed) and its
  share of the system's self overrun in that dimension. Self points at the
  producer: an entrance carrying its callees' work has no pain of its own. The
  inclusive overrun ratio and per-N figures stay beside, as data. A
  definition inside its contract has no pain however hot it is. Ranked by
  summed shares, then self reads per N, then self steps per N. Each entry
  offers its `source` and `callers` forms as data. The overrun is of the
  samples' sums, not of each call: one call far over beside many under is
  diluted.

  # Costs
  One pass over the live cells: proportional to the armed definitions."
  {:malli/schema [:function
                  [:=> [:cat] :seon.profile/pain]
                  [:=> [:cat :seon.profile.pain/contract] :seon.profile/pain]]}
  ;; seon.config requires seon.db, which requires this namespace: the shipped
  ;; dial is resolved where it is read
  ([] (pain (let [dials @(requiring-resolve 'seon.config/defaults)]
              {:steps [(:seon.config.fn.cost/steps-k dials) (:seon.config.fn.cost/steps-c dials)]
               :reads [(:seon.config.fn.cost/reads-k dials) (:seon.config.fn.cost/reads-c dials)]
               :bytes [(:seon.config.fn.cost/bytes-k dials) (:seon.config.fn.cost/bytes-c dials)]})))
  ([contract]
   (let [rounded (fn [scale x] (/ (Math/round (* scale (double x))) scale))
         judged (fn [[k c] n samples spent]
                  (let [allowed (+ (* (double k) n) (* (double c) samples))]
                    {:over (max 0.0 (- spent allowed))
                     :ratio (rounded 100.0 (/ spent (max 1.0 allowed)))
                     :per-n (/ (double spent) (max 1 n))}))
         rows (into []
                    (keep (fn [member]
                            (let [^AtomicLongArray sizes (:seon.profile/sizes member)
                                  samples (.get sizes 0)]
                              (when (pos? samples)
                                (let [n (.get sizes 1)
                                      calls (.sum ^LongAdder (:seon.profile/entry-counter member))
                                      scale (/ (double calls) samples)
                                      by-dim (into {}
                                                   (map (fn [[dim [inclusive-slot self-slot]]]
                                                          (let [term (get contract dim)]
                                                            [dim {:self (update (judged term n samples (.get sizes self-slot)) :over * scale)
                                                                  :inclusive (judged term n samples (.get sizes inclusive-slot))}])))
                                                   dimensions)]
                                  {:cell member :calls calls :samples samples :by-dim by-dim})))))
                    (cells))
         totals (into {} (map (fn [dim] [dim (transduce (map #(get-in % [:by-dim dim :self :over])) + 0.0 rows)]))
                      (keys dimensions))
         share (fn [row dim] (let [t (get totals dim)]
                               (if (pos? t) (/ (get-in row [:by-dim dim :self :over]) t) 0.0)))
         figure (fn [row side k scale] (update-vals (:by-dim row) #(rounded scale (get-in % [side k]))))
         entry (fn [row]
                 (let [{sym :seon.profile/sym digest :seon.profile/digest} (:cell row)]
                   (cond-> {:seon.profile/sym sym
                            :seon.profile/calls (:calls row)
                            :seon.profile/samples (:samples row)
                            :seon.profile.pain/overrun (figure row :self :ratio 100.0)
                            :seon.profile.pain/per-n (figure row :self :per-n 1000.0)
                            :seon.profile.pain/share (into {} (map (fn [dim] [dim (rounded 1000.0 (share row dim))])) (keys dimensions))
                            :seon.profile.pain/inclusive-overrun (figure row :inclusive :ratio 100.0)
                            :seon.profile.pain/inclusive-per-n (figure row :inclusive :per-n 1000.0)
                            :seon.render/operations [(list 'source sym) (list 'my.program/callers (list 'quote sym))]}
                     digest (assoc :seon.profile/digest digest))))]
     (into []
           (map entry)
           (sort-by (fn [row] [(- (reduce + (map #(share row %) (keys dimensions))))
                               (- (get-in row [:by-dim :reads :self :per-n]))
                               (- (get-in row [:by-dim :steps :self :per-n]))])
                    rows)))))

;;; ---------------------------------------------------------------------------
;;; A measured call — JVM counters around one function, as data
;;; ---------------------------------------------------------------------------

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
  and the count of reads among them (`read-entrances`). Every armed call
  inside it on the calling thread measures its allocation exactly (`bracket`
  samples it otherwise). A thrown exception propagates.

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
                                (transient {}) (.values ^ConcurrentHashMap cells-by-key))))
        window-before (seon.batch/jvm-counters)
        entries-before (entries)
        [gc-before gc-ms-before] (collected)
        allocated-before (allocated)
        ^longs frame (.get callee-frame)
        started (System/nanoTime)
        ;; every armed call inside a measured call measures its allocation exactly
        value (do (aset frame 5 (inc (aget frame 5)))
                  (try (f) (finally (aset frame 5 (dec (aget frame 5))))))
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
