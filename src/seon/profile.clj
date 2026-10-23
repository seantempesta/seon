(ns seon.profile
  "What each contracted function costs, counted where it is called (Seon's seon.profile
  bracket and its :seon.fn.cost facts, ported for Jolt; E15 measured the port).

  Every armed function (seon.contract/checked, arm!) is wrapped once more by `counted`: a
  Chez procedure over the function's cell. A cell counts the function's calls exactly and
  times one call in 64 (its first call in an epoch always), so each function's time is an
  estimate from its samples and its count is exact. Counts are kept per epoch: `begin!`
  opens one (an eval form, a cost measurement) and `touched` answers the functions called
  since, so reading an epoch costs what it touched, never the program's size.

  Jolt-specific, and why (estimates before the probe, measured in the probe beside them):
  - The wrapper is Scheme, not Clojure: one fixnum compare and increment on a vector slot
    per call, no Clojure dispatch. Estimate 1-2 ns per call; probe 0.9 ns (9.5 vs 8.6 ns
    for a bare inc).
  - The clock is the machine's CLOCK_UPTIME_RAW (clock_gettime_nsec_np), a foreign
    procedure called from Scheme: 9.5 ns a read, nothing allocated, 41 ns resolution
    (2026-10-22), where Chez's `current-time` was 28 ns, 48 bytes and 1 us, counted from
    this process's start. It is Seon's one clock (`now-ns`, docs/machine.md#the-clock):
    every process reads the same timeline, so one process's stamp is another's t0.
  - The sample rate is a power of two, so the test is one fxand. Amortized clock cost: two
    reads per 64 calls, about 0.8 ns.
  - No ThreadLocal and no self-time frame (E15: the bracket's 3 ThreadLocal ops and frame
    were 0.7 of its 0.9 us). Times are inclusive: a caller's time includes its callees'.

  The meter is not metered: seon.contract/wrap counts every armed function except this
  namespace's own (armed at boot with seon.contract), so reading a count adds no call.

  The failure point: the cells are plain process state. An agent is one thread, so its counts
  are exact. Root's procs run on several threads, so two threads calling one function can
  lose an increment, and a function first called on two threads at once can be missed from
  `touched`. Root's counts are approximate; nothing in root reads them as proof."
  (:require [jolt.scheme :as scm]))

(defonce ^:private scheme-defs
  (scm/eval-string
    "(define seon-profile-epoch 0)
     (define seon-profile-touched '())
     ;; CLOCK_UPTIME_RAW is 8 on macOS
     (define seon-profile-now
       (let ([clock (foreign-procedure \"clock_gettime_nsec_np\" (int) unsigned-64)]) (lambda () (clock 8))))
     ;; a cell: #(calls epoch sampled-ns samples symbol)
     (define (seon-profile-cell sym) (vector 0 -1 0 0 sym))
     ;; count one call; #t when this call is to be timed
     (define (seon-profile-hit! c)
       (if (fx= (vector-ref c 1) seon-profile-epoch)
           (let ([n (fx+ 1 (vector-ref c 0))])
             (vector-set! c 0 n)
             (fx= 1 (fxand n 63)))
           (begin (vector-set! c 0 1) (vector-set! c 1 seon-profile-epoch)
                  (vector-set! c 2 0) (vector-set! c 3 0)
                  (set! seon-profile-touched (cons c seon-profile-touched))
                  #t)))
     ;; what one clock read costs (the least of 1000 back-to-back pairs): each sample
     ;; includes one read, which is not the function's time
     (define seon-profile-clock-ns
       (let loop ([i 0] [best 1000000])
         (if (fx= i 1000) best
             (let* ([a (seon-profile-now)] [b (seon-profile-now)]) (loop (fx+ i 1) (min best (- b a)))))))
     (define (seon-profile-took! c t0)
       (vector-set! c 2 (+ (vector-ref c 2) (max 0 (- (seon-profile-now) t0 seon-profile-clock-ns))))
       (vector-set! c 3 (fx+ 1 (vector-ref c 3))))
     (define-syntax seon-profile-call
       (syntax-rules ()
         [(_ c e) (if (seon-profile-hit! c)
                      (let* ([t0 (seon-profile-now)] [r e]) (seon-profile-took! c t0) r)
                      e)]))
     (define (seon-profile-counted c f)
       (case-lambda
         [() (seon-profile-call c (f))]
         [(a) (seon-profile-call c (f a))]
         [(a b) (seon-profile-call c (f a b))]
         [(a b d) (seon-profile-call c (f a b d))]
         [(a b d e) (seon-profile-call c (f a b d e))]
         [args (seon-profile-call c (apply f args))]))
     (define (seon-profile-begin!)
       (set! seon-profile-epoch (fx+ 1 seon-profile-epoch))
       (set! seon-profile-touched '())
       seon-profile-epoch)
     ;; fold f over the touched cells: (f acc symbol calls sampled-ns samples), one crossing
     (define (seon-profile-fold f init)
       (let loop ([cs seon-profile-touched] [acc init])
         (if (null? cs) acc
             (let ([c (car cs)])
               (loop (cdr cs) (f acc (vector-ref c 4) (vector-ref c 0) (vector-ref c 2) (vector-ref c 3)))))))
     ;; the calling thread's own allocation, never another thread's or the collector's copying
     ;; (Chez jolt/thread-bytes: S_thread_bytes_allocated)
     (define seon-profile-alloc (foreign-procedure \"(cs)thread_bytes_allocated\" () uptr))
     ;; CLOCK_THREAD_CPUTIME_ID is 16 on macOS: the calling thread's CPU time
     (define seon-profile-cpu
       (let ([clock (foreign-procedure \"clock_gettime_nsec_np\" (int) unsigned-64)]) (lambda () (clock 16))))
     (define (seon-profile-calls)
       (let loop ([cs seon-profile-touched] [n 0])
         (if (null? cs) n (loop (cdr cs) (+ n (vector-ref (car cs) 0))))))"))

(def ^:private procs
  "The Scheme procedures above, by name (host procedures, resolved once)."
  (into {} (for [k [:cell :counted :begin! :fold :calls :now :alloc :cpu]]
             [k (scm/proc (str "seon-profile-" (name k)))])))

(defn now-ns
  "Nanoseconds on the machine's monotonic clock (CLOCK_UPTIME_RAW): Seon's one clock, the
  same timeline in every process (docs/machine.md#the-clock)."
  {:malli/schema [:=> [:cat] :seon.lib/ns-time]}
  [] ((:now procs)))

(defn ms-since
  "Milliseconds on the one clock since t0, a reading of `now-ns`."
  {:malli/schema [:=> [:cat :seon.lib/ns-time] :seon.lib/ms]}
  [t0] (/ (- (now-ns) t0) 1e6))

(defn cpu-ns
  "The calling thread's CPU time in nanoseconds (CLOCK_THREAD_CPUTIME_ID): what a cost
  proof times, so another thread's work and the scheduler's pauses are not the measured
  call's (task seon.cost/a-proof-measures-only-its-own-thread). Not a clock that orders
  events: that is now-ns."
  {:malli/schema [:=> [:cat] :int]}
  [] ((:cpu procs)))

(defn alloc-bytes
  "Bytes the calling thread has allocated since it started (Chez jolt/thread-bytes: its
  allocation areas less the tails it dropped, and its large objects; never another
  thread's allocation or the collector's copying). Counted, never sampled; GC does not
  lower it. Not itself counted, so measuring adds no calls."
  {:malli/schema [:=> [:cat] :int]}
  [] ((:alloc procs)))

(defn counted
  "f, counted under fn-sym: every call counted, one in 64 timed."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.host/fn] :seon.host/fn]}
  [fn-sym f] ((:counted procs) ((:cell procs) fn-sym) f))

(defn begin!
  "Open a new epoch: counts start again from zero for every function. Answers its number."
  {:malli/schema [:=> [:cat] :int]}
  [] ((:begin! procs)))

(defn calls
  "Calls of contracted functions since the epoch began, all functions together."
  {:malli/schema [:=> [:cat] :int]}
  [] ((:calls procs)))

(defn touched
  "The functions called since the epoch began, each with its exact :calls and its :ms,
  estimated from its timed calls (sampled ns ÷ samples × calls). Costs what was touched."
  {:malli/schema [:=> [:cat] [:vector :seon.profile/fn-cost]]}
  []
  (persistent!
    ((:fold procs) (fn [acc sym calls ns samples]
                     (conj! acc {:var sym :calls calls
                                 :ms (if (pos? samples) (/ (* (/ (double ns) samples) calls) 1e6) 0.0)}))
                   (transient []))))

(defn measure!
  "f called and measured, its value answered: the engine ticks it ran (the work it did, the exact
  measure: jolt.host/ticks-of arms the tick timer, and the count is read in a finally inside it,
  so a call that throws is counted), the bytes this thread allocated, its wall ns (now-ns) and
  its thread's CPU ns (cpu-ns), and the contracted calls of the epoch it opens (begin!). What it
  measured goes to done, {:ticks :bytes :wall-ns :cpu-ns :calls}, from a finally: a call that
  throws is measured too, and its exception goes on to the caller. The one measurement of a call:
  the REPL's per-form cost (seon.repl/wrap-cost) and a cost proof's (seon.cost/measure) read it."
  {:malli/schema [:=> [:cat :seon.host/fn :seon.host/fn] :any]}
  [f done]
  (let [a0 (alloc-bytes) _ (begin!) w0 (now-ns) c0 (cpu-ns)
        v (volatile! nil) k (volatile! 0)]
    (try (jolt.host/ticks-of (fn [] (let [k0 (jolt.host/ticks)]
                                      (try (vreset! v (f))
                                           (finally (vreset! k (- (jolt.host/ticks) k0)))))))
         @v
         (finally (let [c1 (cpu-ns) w1 (now-ns) n (calls) a1 (alloc-bytes)]
                    (done {:ticks @k :bytes (- a1 a0) :wall-ns (- w1 w0) :cpu-ns (- c1 c0) :calls n}))))))

(defn top
  "The k costliest of fns (by estimated ms, then calls), costliest first."
  {:malli/schema [:=> [:cat [:vector :seon.profile/fn-cost] nat-int?] [:vector :seon.profile/fn-cost]]}
  [fns k]
  (vec (take k (sort-by (juxt (comp - :ms) (comp - :calls) (comp str :var)) fns))))
