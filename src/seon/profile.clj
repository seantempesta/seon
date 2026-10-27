(ns seon.profile
  "What each contracted function costs, counted where it is called (Seon's seon.profile
  bracket and its :seon.fn.cost facts, ported for Jolt; E15 measured the port).

  Every armed function (seon.contract/checked, arm!) is wrapped once more by `counted`: a
  Chez procedure over the function's cell. A cell counts, exactly, the function's calls and
  the engine ticks (jolt-ticks: the work done, a fiber's own on a fiber) and thread bytes
  (seon-profile-alloc) they did, in all and of its own: a call saves and zeroes what its
  callees have done so far (two process-wide counters), so its self is its own work less
  theirs, and it adds its whole to its caller's on the way out. Counts are kept per epoch:
  `begin!` opens one (an eval form, a cost measurement, a test) and `touched` answers the
  functions called since, so reading an epoch costs what it touched, never the program's size.
  Cost is work done (docs/cost.md), so no clock is read per call: this replaced timing one call
  in 64 by the wall clock, an estimate, inclusive only.

  Jolt-specific, and why (estimates before the probe, measured in the probe beside them):
  - The wrapper is Scheme, not Clojure: no Clojure dispatch. Measured 2026-10-27 (load 30-50)
    over 1M calls of a two-key predicate: the call alone 10 ticks; counting calls alone (the
    wrapper before) 11; calls, ticks and bytes 15 ticks, about 60 ns more a call in wall time
    (a jolt-ticks read about 26 ns, a bytes read 3 ns); the same predicate contracted, its
    schema checked, 48 ticks and 120 ns.
  - Nothing is allocated per call: every count is a fixnum in the cell's slots.
  - Self is approximate where a call does not return: a throw skips its frame's update, so
    the frame that catches it counts the thrower's callees as its own; and a call that parks
    on a fiber counts the bytes others allocated on its thread meanwhile (its ticks are its
    fiber's own).

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
     ;; the calling thread's own allocation, never another thread's or the collector's copying
     ;; (Chez jolt/thread-bytes: S_thread_bytes_allocated)
     (define seon-profile-alloc (foreign-procedure \"(cs)thread_bytes_allocated\" () uptr))
     ;; a cell: #(calls epoch ticks self-ticks bytes self-bytes symbol)
     (define (seon-profile-cell sym) (vector 0 -1 0 0 0 0 sym))
     ;; the ticks and bytes the calls running inside the current one have done so far: saved
     ;; and zeroed as a call starts, so its self is what it did less what they did
     (define seon-profile-kid-ticks 0)
     (define seon-profile-kid-bytes 0)
     ;; count one call (a cell first touched in this epoch starts from zero)
     (define (seon-profile-hit! c)
       (if (fx= (vector-ref c 1) seon-profile-epoch)
           (vector-set! c 0 (fx+ 1 (vector-ref c 0)))
           (begin (vector-set! c 0 1) (vector-set! c 1 seon-profile-epoch)
                  (vector-set! c 2 0) (vector-set! c 3 0) (vector-set! c 4 0) (vector-set! c 5 0)
                  (set! seon-profile-touched (cons c seon-profile-touched)))))
     (define (seon-profile-took! c dt db kt kb)
       (vector-set! c 2 (+ dt (vector-ref c 2)))
       (vector-set! c 3 (+ (- dt seon-profile-kid-ticks) (vector-ref c 3)))
       (vector-set! c 4 (+ db (vector-ref c 4)))
       (vector-set! c 5 (+ (- db seon-profile-kid-bytes) (vector-ref c 5)))
       (set! seon-profile-kid-ticks (+ kt dt))
       (set! seon-profile-kid-bytes (+ kb db)))
     (define-syntax seon-profile-call
       (syntax-rules ()
         [(_ c e) (let ([kt seon-profile-kid-ticks] [kb seon-profile-kid-bytes])
                    (seon-profile-hit! c)
                    (set! seon-profile-kid-ticks 0) (set! seon-profile-kid-bytes 0)
                    (let* ([t0 (jolt-ticks)] [b0 (seon-profile-alloc)] [r e])
                      (seon-profile-took! c (- (jolt-ticks) t0) (- (seon-profile-alloc) b0) kt kb)
                      r))]))
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
     ;; fold f over the touched cells: (f acc symbol calls ticks self-ticks bytes self-bytes), one crossing
     (define (seon-profile-fold f init)
       (let loop ([cs seon-profile-touched] [acc init])
         (if (null? cs) acc
             (let ([c (car cs)])
               (loop (cdr cs) (f acc (vector-ref c 6) (vector-ref c 0) (vector-ref c 2) (vector-ref c 3) (vector-ref c 4) (vector-ref c 5)))))))
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
  "f, counted under fn-sym: every call counted, with the ticks and bytes it did, in all and of its own."
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
  "The functions called since the epoch began, each with its exact :calls, the engine :ticks and
  thread :bytes its calls did in all (callees included), and :self-ticks and :self-bytes, what
  they did less what the contracted calls inside them did. Costs what was touched."
  {:malli/schema [:=> [:cat] [:vector :seon.profile/fn-cost]]}
  []
  (persistent!
    ((:fold procs) (fn [acc sym calls ticks self-ticks bytes self-bytes]
                     (conj! acc {:var sym :calls calls :ticks ticks :self-ticks self-ticks :bytes bytes :self-bytes self-bytes}))
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
  "The k costliest of fns (by ticks, then calls), costliest first."
  {:malli/schema [:=> [:cat [:vector :seon.profile/fn-cost] nat-int?] [:vector :seon.profile/fn-cost]]}
  [fns k]
  (vec (take k (sort-by (juxt (comp - :ticks) (comp - :calls) (comp str :var)) fns))))
