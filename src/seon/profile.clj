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
  counted callees', from the same two clock reads). `transaction-observation`
  turns a sink's delta into the attributes the world's next transaction
  carries; `seon.profile.measurement` stores a definition's buckets when they
  move.

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
  (:import [java.util Collections IdentityHashMap WeakHashMap]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong AtomicLongArray AtomicReference LongAccumulator LongAdder]
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

(defn measured-mark?
  "True for a cell's judged mark (an AtomicReference).

  It holds the counts `significant-tx` last judged, nil before the first."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? AtomicReference value))

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
;; only; read by `cells` and by `significant-tx` for the definitions an
;; evaluation ran.
(defonce ^:private roster (ConcurrentHashMap.))

(def ^:private ^java.util.function.Function new-member-set
  (reify java.util.function.Function
    (apply [_ _] (Collections/synchronizedSet (Collections/newSetFromMap (WeakHashMap.))))))

(defn cell
  "A new cumulative cell for one installed callable.

  `callable-identity` names the callable's symbol, its definition digest when the
  installer holds one, and its scope. A missing digest is unknown, never a
  substitute."
  {:malli/schema [:=> [:cat :seon.profile/identity] :seon.profile/cell]}
  [callable-identity]
  (let [created (merge (select-keys callable-identity [:seon.profile/sym :seon.profile/digest
                                                       :seon.profile/scope :seon.profile/context])
                       {:seon.profile/histogram (into-array LongAdder (repeatedly buckets #(LongAdder.)))
                        :seon.profile/total-counter (LongAdder.)
                        :seon.profile/self-counter (LongAdder.)
                        :seon.profile/max-counter (LongAccumulator. longest 0)
                        :seon.profile/entry-counter (LongAdder.)
                        :seon.profile/throw-counts (ConcurrentHashMap.)
                        :seon.profile/entered (ThreadLocal.)
                        :seon.profile/measured (AtomicReference.)})]
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
         ;; the executed definition's identity, once: its symbol and held
         ;; definition digest (nil is unknown), the key of a world's call sink
         ~'__profile-key [(:seon.profile/sym cell#) (:seon.profile/digest cell#)]]
     ~@body))

(def ^java.util.function.Function new-adder
  "The one function a call sink makes an executed definition's first counter
  with: `[calls nanos self-nanos calls-stamped self-stamped]`, the last two
  where `transaction-observation` last read the first and third."
  (reify java.util.function.Function
    (apply [_ _] (AtomicLongArray. 5))))

(defn world-counter?
  "True for a call sink's counter as `new-adder` makes it (five slots)."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (instance? AtomicLongArray value) (= 5 (.length ^AtomicLongArray value))))

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
  nanoseconds; for an `outer`most entry, into the cell `with-cell` bound."
  [outer counted started frame saved]
  (let [elapsed (gensym "elapsed")
        c (hinted "counted" 'java.util.concurrent.atomic.AtomicLongArray)
        b (hinted "bucket" 'java.util.concurrent.atomic.LongAdder)]
    `(let [~elapsed (- (System/nanoTime) ~started)
           self# (- ~elapsed (aget ~frame 0))]
       (aset ~frame 0 (+ ~saved ~elapsed))
       (when-some [~c ~counted]
         (. ~c ~'incrementAndGet 0)
         (. ~c ~'addAndGet 1 ~elapsed)
         (. ~c ~'addAndGet 2 self#))
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
  clock reads per counted entry, never more."
  [& body]
  (let [prior (gensym "prior")
        sink (hinted "sink" 'java.util.concurrent.ConcurrentHashMap)
        here (gensym "here")
        outer (gensym "outer")
        started (gensym "started")
        counted (gensym "counted")
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
               ~frames seon.profile/callee-frame
               ~frame (. ~frames ~'get)
               ~saved (aget ~frame 0)]
           (aset ~frame 0 0)
           (when ~outer (. ~'__profile-entries ~'increment))
           (. ~'__profile-entered ~'set ~here)
           (try
             (let [~value (do ~@body)]
               (seon.profile/exited ~outer ~counted ~started ~frame ~saved)
               ~value)
             (catch Throwable ~failure
               (seon.profile/exited ~outer ~counted ~started ~frame ~saved)
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
  {:seon/tasks #{'seon.profile/reading-the-profile-validates-every-cell}
   :malli/schema [:=> [:cat] [:vector :seon.profile/held-cell]]}
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

(def explained-calls
  "Armed calls since a world's previous transaction from which its work is
  stamped even under `slow-ms`: call-heavy work (N+1, repeated derivation)."
  10000)

(def hot-limit
  "Definitions a stamped transaction names by self time."
  20)

(defn transaction-observation
  "The work `world` did since its previous transaction, as the attributes the
  transaction it writes carries on `:db/current-tx` (health plan §1.2), when
  that work is worth explaining: at least `slow-ms` milliseconds or
  `explained-calls` armed calls. Then its milliseconds, its armed calls, its
  top `hot-limit` definitions by self time and the definitions it called more
  than `seon.batch/repeated-calls` times; otherwise nothing.

  Why the floor: a store attribute costs its commit one rewritten index leaf
  (about 40-70 KB) once its datoms outgrow a shared leaf, whatever the values,
  and the plain daily collection keeps every commit of a live branch. Stamped
  on every commit (7,646 on default in 24 h) the four attributes cost ~0.8 to
  2.3 GB a day; above the floor (19 of 267 evaluations in the same day) they
  cost a leaf or two on the commits the health report reads, and more values
  in a touched attribute are nearly free (landing note).

  Read from the world's call sink (`:seon.batch/sink`): each counter's calls
  and self nanoseconds less those at the world's previous transaction, which
  this call then marks (the sink's counters own the mark, so nothing beside
  them holds state). Milliseconds come from the world's `:seon.batch/stamped-ns`
  (the fold's start instant, moved to now here) and are absent without it.
  Empty for no world, no sink, no stamp clock (a turn proc's pass counts its
  calls for its reply's cost, not for a transaction), or work under the floor; definitions whose
  digest is unknown count in calls but are named nowhere. Two threads stamping
  one world at once split its work between their transactions.

  # Costs
  Proportional to the definitions the world executed (one pass over its sink,
  a sort of those with calls): measured in the landing note."
  {:malli/schema [:=> [:cat [:maybe :map]] :seon.profile/transaction-observation]}
  [world]
  (let [sink (:seon.batch/sink world)]
    (if-not (and (instance? ConcurrentHashMap sink) (:seon.batch/stamped-ns world))
      {}
      (let [worked (into []
                         (keep (fn [[k counter]]
                                 (when (world-counter? counter)
                                   (let [^AtomicLongArray c counter
                                         calls (.get c 0)
                                         self (.get c 2)
                                         made (- calls (.getAndSet c 3 calls))
                                         used (- self (.getAndSet c 4 self))]
                                     (when (pos? made) [k made used])))))
                         ^ConcurrentHashMap sink)
            known (filterv (fn [[[_ digest]]] digest) worked)
            calls (reduce + 0 (map second worked))
            ms (when-let [^AtomicLong at (:seon.batch/stamped-ns world)]
                 (let [now (System/nanoTime)]
                   (quot (- now (.getAndSet at now)) 1000000)))
            hot (into #{} (comp (take hot-limit) (map (fn [[[sym digest] made used]] [sym digest made (quot used 1000000)])))
                      (sort-by #(- (long (nth % 2))) known))
            repeated (into #{} (keep (fn [[[sym digest] made]]
                                       (when (> made seon.batch/repeated-calls) [sym digest made])))
                           known)]
        ;; the marks above advanced either way: the next stamp is the work since this one
        (if (or (>= calls explained-calls) (and ms (>= ms slow-ms)))
          (cond-> {:seon.db.transaction/calls calls}
            ms (assoc :seon.db.transaction/ms ms)
            (seq hot) (assoc :seon.db.transaction/hot hot)
            (seq repeated) (assoc :seon.db.transaction/repeated repeated))
          {})))))

;;; ---------------------------------------------------------------------------
;;; Explaining one slow operation
;;; ---------------------------------------------------------------------------

(defn snapshot?
  "True for the counter snapshot `begin` takes.

  It holds the live cells it read and their entries and nanos,
  `[entries0 nanos0 self0 entries1 nanos1 self1 ...]`, in one long array."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (boolean (and (map? value)
                (vector? (::cells value))
                (some-> (::sums value) class .isArray))))

(defn begin
  "Mark the start of an operation: its clock and every cell's entries and total.

  One read of the live cells, then one pass over them reading their counters
  directly into one primitive array: no armed call per cell, three longs each."
  {:seon/tasks #{'seon.profile/begin-validates-every-cell-with-armed-predicates}
   :malli/schema [:=> [:cat] :seon.profile/mark]}
  []
  (let [held (cells)
        sums (long-array (* 3 (count held)))]
    (dotimes [i (count held)]
      (let [member (nth held i)]
        (aset sums (* 3 i) (.sum ^LongAdder (:seon.profile/entry-counter member)))
        (aset sums (+ 1 (* 3 i)) (.sum ^LongAdder (:seon.profile/total-counter member)))
        (aset sums (+ 2 (* 3 i)) (.sum ^LongAdder (:seon.profile/self-counter member)))))
    {:seon.profile/started-ns (System/nanoTime)
     :seon.profile/snapshot {::cells held ::sums sums}}))

(defn- growth-line
  {:malli/schema [:=> [:cat :seon.profile/growth] :string]}
  [{sym :seon.profile/sym calls :seon.profile/calls total :seon.profile/total-ms self :seon.profile/self-ms
    scope :seon.profile/scope context :seon.profile/context}]
  (str self " ms self, " total " ms total in " sym " x" calls
       (when (= :seon.profile/context scope)
         (str " [SCI" (when context (str " " context)) "]"))))

(defn explain
  "What the armed definitions did during the operation `mark` began.

  The growth is every cell's counter increase in this JVM during the window:
  the ranked growths (at least a millisecond inclusive, most self time first,
  then inclusive total), every
  entered definition by calls, and the calls in all. Nested calls appear in
  both caller and callee, and concurrent work on other threads is included.
  Calls are the outermost entries in the window, an entered call not yet
  returned included (this call's own entry and its contract's check of `mark`
  among them). No growth means the time was spent in unarmed code or waiting."
  {:malli/schema [:=> [:cat :seon.profile/mark [:int {:min 1}]] :seon.profile/explanation]}
  [{started :seon.profile/started-ns {held ::cells ^longs sums ::sums} :seon.profile/snapshot} limit]
  (let [elapsed-ms (quot (- (System/nanoTime) started) 1000000)
        ;; a local function, not an armed one: a read of the counters calls
        ;; no armed definition per cell
        growth (fn [member calls0 total0 self0]
                 (let [calls (- (.sum ^LongAdder (:seon.profile/entry-counter member)) (long calls0))]
                   (when (pos? calls)
                     (merge (select-keys member [:seon.profile/sym :seon.profile/digest
                                                 :seon.profile/scope :seon.profile/context])
                            {:seon.profile/calls calls
                             :seon.profile/self-ms (quot (- (.sum ^LongAdder (:seon.profile/self-counter member))
                                                            (long self0))
                                                         1000000)
                             :seon.profile/total-ms (quot (- (.sum ^LongAdder (:seon.profile/total-counter member))
                                                             (long total0))
                                                          1000000)}))))
        ;; the held cells are read before anything else is called, so the
        ;; read of the live cells below is outside the window
        old (into [] (keep (fn [i] (let [j (* 3 (long i))] (growth (nth held i) (aget sums j) (aget sums (inc j)) (aget sums (+ 2 j))))))
                  (range (count held)))
        ;; Cells are matched by identity (a LongAdder is a Number, which a
        ;; Clojure map would compare by value); a cell installed after
        ;; `begin` starts from zero.
        known (let [index (Collections/newSetFromMap (IdentityHashMap.))]
                (run! #(.add index (:seon.profile/entry-counter %)) held)
                index)
        grown (into old (keep #(when-not (.contains known (:seon.profile/entry-counter %)) (growth % 0 0 0)))
                    (cells))
        ranked (into [] (comp (filter (comp pos? :seon.profile/total-ms)) (take limit))
                     (sort-by (juxt :seon.profile/self-ms :seon.profile/total-ms) #(compare %2 %1) grown))]
    {:seon.profile/elapsed-ms elapsed-ms
     :seon.profile/growths ranked
     :seon.profile/by-calls (into [] (take limit) (sort-by :seon.profile/calls > grown))
     :seon.profile/calls (transduce (map :seon.profile/calls) + 0 grown)
     :seon.profile/lines
     (into [(str elapsed-ms " ms elapsed.")]
           (if (seq ranked)
             (into [(str "Armed definitions by self time, inclusive total beside it (a caller's"
                         " total contains its callees'; concurrent work included):")]
                   (map growth-line) ranked)
             [(str "No armed definition accumulated a millisecond: the time is in"
                   " unarmed code or waiting.")]))}))

(defn explain-slow
  "The explanation when the operation `mark` began took over one second.

  Nil is the declared answer for an operation that finished within the bound;
  the fast path pays one clock read."
  {:seon/tasks #{'seon.profile/slow-explanation-blames-the-agent-for-platform-time}
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

(defn summary
  "The definitions that did the work, once, as data: `limit` rows ranked by
  self time (then inclusive total), and how many definitions had a call over
  one second.

  A row is the definition's calls, self, inclusive and maximum milliseconds,
  with its throws when it threw and `:seon.profile/scope` when it is an SCI
  wrapper. Self time is the row's own body and unarmed callees; a wrapper whose
  callback does the work ranks by its own time, and its callee's row carries
  the work. The maximum is one call, inclusive."
  {:seon/tasks #{'seon.profile/scope-wrappers-report-callback-time-as-work}
   :malli/schema [:=> [:cat [:vector :seon.profile/held-cell] [:int {:min 1}]] :seon.profile/summary]}
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
  to other threads; on a virtual thread, which has no count, it is absent."
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
        entries-before (entries)
        [gc-before gc-ms-before] (collected)
        allocated-before (allocated)
        started (System/nanoTime)
        value (f)
        ms (/ (Math/round (/ (- (System/nanoTime) started) 1e4)) 100.0)
        allocated-after (allocated)
        [gc-after gc-ms-after] (collected)
        called (into []
                     (keep (fn [[sym [n self]]]
                             (let [[n0 self0] (get entries-before sym [0 0])
                                   d (- n n0)]
                               (when (pos? d)
                                 {:seon.profile/sym sym :seon.profile/calls d
                                  :seon.profile/self-ms (quot (- self self0) 1000000)}))))
                     (entries))
        ranked (fn [k] (into [] (take hot-limit) (sort-by k #(compare %2 %1) called)))]
    (cond->
    {:seon.profile/value value
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
