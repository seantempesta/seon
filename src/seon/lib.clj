(ns seon.lib
  "Host pieces shared by root, the fork server and agents: libc FFI, pipes, a
  poll-driven line reader, Chez introspection and GC controls, qfork, the Mach memory
  probe (native/probe.c, built by bin/build), small stats. From proto/flow f2-final
  proto.lib (itself e13.lib, trimmed)."
  (:require [jolt.ffi :as ffi] [jolt.scheme :as scm] [clojure.string :as str]))

(ffi/load-library)
(defmacro ^:private probe-lib-default
  "Where bin/build puts native/probe.c's library, read when this namespace compiles (a
  built binary carries the path)."
  [] (str (or (System/getenv "JOLT_PWD") ".") "/target/lib/libprobe.dylib"))
(ffi/load-library (or (System/getenv "SEON_PROBE_LIB") (probe-lib-default)))

(ffi/defcfn c-fork "fork" [] :int)
(ffi/defcfn c-exit "_exit" [:int] :void)
(ffi/defcfn c-getpid "getpid" [] :int)
(ffi/defcfn c-getppid "getppid" [] :int)
(ffi/defcfn c-waitpid "waitpid" [:int :pointer :int] :int {:blocking true})
(ffi/defcfn c-waitpid-nb "waitpid" [:int :pointer :int] :int)
(ffi/defcfn c-pipe "pipe" [:pointer] :int)
(ffi/defcfn c-close "close" [:int] :int)
(ffi/defcfn c-read "read" [:int :pointer :size_t] :ssize_t {:blocking true})
(ffi/defcfn c-write "write" [:int :pointer :size_t] :ssize_t {:blocking true})
(ffi/defcfn c-signal "signal" [:int :pointer] :pointer)
(ffi/defcfn c-kill "kill" [:int :int] :int)
(ffi/defcfn c-setpgid "setpgid" [:int :int] :int)
(ffi/defcfn c-setrlimit "setrlimit" [:int :pointer] :int)
(ffi/defcfn c-getrlimit "getrlimit" [:int :pointer] :int)

(defn raise-open-files!
  "Raise this process's open-file limit to n (or its hard limit): root holds one socket
  per agent. Answers the new soft limit."
  [n]
  (let [b (ffi/alloc 16)]
    (c-getrlimit 8 b)
    (ffi/write b :uint64 (min n (ffi/read b :uint64 8)) 0)
    (c-setrlimit 8 b)
    (c-getrlimit 8 b)
    (ffi/read b :uint64 0)))
(ffi/defcfn poll1 "e13_poll1" [:int :int] :long {:blocking true})
(ffi/defcfn mp-probe* "mp_probe" [:pointer :long] :long)
(ffi/defcfn events* "e13_events" [:pointer] :long)
(ffi/defcfn nthreads "e13_nthreads" [] :long)
(ffi/defcfn sysmem* "e13_sysmem" [:pointer] :long)

(def PAGE 16384)
(defn ignore-sigpipe! [] (c-signal 13 1))
(defn now-ns [] (System/nanoTime))
(defn ms-since [t0] (/ (- (now-ns) t0) 1e6))

(defn mp-probe
  "This process's Mach VM accounting: {:phys-fp :internal :resident :regions {:jit :malloc :anon0 :other}} in MB."
  []
  (let [b (ffi/alloc 4096) n (mp-probe* b 4096)] (read-string (ffi/read-bytes b n))))

(defn real-mb
  "Pages this process owns (copied from its parent or newly dirtied), in MB: per method.md,
  footprint overstates a forked child, so count shared-now-private + private resident pages."
  ([] (real-mb (mp-probe)))
  ([p] (reduce + 0.0 (for [k [:jit :malloc :anon0 :other]
                           :let [r (get-in p [:regions k])]]
                       (+ (:snp r 0.0) (:prv-res r 0.0))))))

(defn events [] (let [b (ffi/alloc 24)] (events* b) {:cow-faults (ffi/read b :long 0) :faults (ffi/read b :long 8)}))
(defn sysmem [] (let [b (ffi/alloc 24)] (sysmem* b)
                  {:free-mb (/ (* PAGE (ffi/read b :long 0)) 1048576.0) :internal-mb (/ (* PAGE (ffi/read b :long 8)) 1048576.0)}))

(defn pipe []
  (let [b (ffi/alloc 8)]
    (when-not (zero? (c-pipe b)) (throw (ex-info "pipe failed" {})))
    [(ffi/read b :int) (ffi/read (+ b 4) :int)]))

(defn write-all! [fd ^bytes arr]
  (let [n (alength arr) buf (ffi/alloc (max 1 n))]
    (ffi/write-array buf arr)
    (loop [off 0]
      (if (< off n)
        (let [r (c-write fd (+ buf off) (- n off))]
          (if (neg? r) -1 (recur (+ off r))))
        n))))

(defn write-line! [fd s] (write-all! fd (.getBytes (str s "\n") "UTF-8")))

(defn line-reader
  "A fn of a timeout in ms (negative: block) that answers the complete lines that
  arrived (a vector, maybe empty) or :eof."
  [fd]
  (let [cap 1048576 buf (ffi/alloc cap) pending (volatile! (byte-array 0))]
    (fn [ms]
      (if (and (>= ms 0) (<= (poll1 fd ms) 0))
        []
        (let [r (c-read fd buf cap)]
          (if (<= r 0)
            :eof
            (let [more (ffi/read-array buf r) ^bytes p @pending q (byte-array (+ (alength p) r))]
              (System/arraycopy p 0 q 0 (alength p))
              (System/arraycopy more 0 q (alength p) r)
              (let [nl (loop [k (dec (alength q))] (cond (< k 0) -1 (= 10 (aget q k)) k :else (recur (dec k))))]
                (if (neg? nl)
                  (do (vreset! pending q) [])
                  (do (vreset! pending (java.util.Arrays/copyOfRange q (inc nl) (alength q)))
                      (vec (remove str/blank? (str/split-lines (String. (java.util.Arrays/copyOfRange q 0 nl) "UTF-8"))))))))))))))

(defn decode-status [st]
  (let [sig (bit-and st 0x7f)] (if (zero? sig) {:exit (bit-and (bit-shift-right st 8) 0xff)} {:signal sig})))

;; ---- Chez introspection and GC controls, defined once in Scheme ----
(defonce scheme-defs
  (do (scm/eval-string
        "(define (proto-active-threads) (#%$top-level-value '$active-threads))
         (define (proto-bytes-allocated) (sstats-bytes (statistics)))
         (define (proto-current-memory) (current-memory-bytes))
         (define (proto-bytes-in-heap) (bytes-allocated))
         (define (proto-collections) (collections))
         (define (proto-static!) (collect (collect-maximum-generation) 'static))
         (define (proto-set-trip! n) (collect-trip-bytes n))
         (define proto-qfork
           (let ([fork (foreign-procedure \"fork\" () int)])
             (lambda ()
               (let loop ([n 0])
                 (disable-interrupts)
                 (mutex-acquire #%$tc-mutex)
                 (if (= (#%$top-level-value '$active-threads) 1)
                     (let ([pid (fork)])
                       (mutex-release #%$tc-mutex)
                       (enable-interrupts)
                       pid)
                     (begin (mutex-release #%$tc-mutex) (enable-interrupts)
                            (sleep (make-time 'time-duration 50000 0))
                            (loop (+ n 1))))))))
         ;; qfork with a bound: -2 when another Chez thread stayed active for max-tries x 50 us
         (define proto-qfork-bounded
           (let ([fork (foreign-procedure \"fork\" () int)])
             (lambda (max-tries)
               (let loop ([n 0])
                 (disable-interrupts)
                 (mutex-acquire #%$tc-mutex)
                 (if (= (#%$top-level-value '$active-threads) 1)
                     (let ([pid (fork)])
                       (mutex-release #%$tc-mutex)
                       (enable-interrupts)
                       pid)
                     (begin (mutex-release #%$tc-mutex) (enable-interrupts)
                            (if (>= n max-tries)
                                -2
                                (begin (sleep (make-time 'time-duration 50000 0))
                                       (loop (+ n 1))))))))))")
      true))

(defn heap-cap!
  "Bound this process's heap at n bytes: Jolt's own ceiling (JOLT_MAX_HEAP), re-installed
  here; past it an allocation throws OutOfMemoryError. Built binaries only (source mode
  installs no collector policy)."
  [n]
  (scm/eval-string (str "(begin (putenv \"JOLT_MAX_HEAP\" \"" (long n) "\") "
                        "(when jolt-gc-policy-installed? (jolt-install-gc-policy!)) jolt-heap-ceiling-bytes)")))

(def ^:private run-bounded*
  (scm/eval-string
    "(lambda (stop? on-stop thunk)
       (let ([prev (timer-interrupt-handler)])
         (dynamic-wind
           (lambda ()
             (timer-interrupt-handler (lambda () (if (stop?) (on-stop) (set-timer 20000))))
             (set-timer 20000))
           thunk
           (lambda () (set-timer 0) (timer-interrupt-handler prev)))))"))

(defn run-bounded
  "Run (thunk) on this thread; Chez's engine timer polls (stop?) every 20,000 ticks
  (procedure calls and loop back-edges, so a tight native loop too) and calls (on-stop),
  which throws, when it answers true. No signals: it works in any process, whichever
  thread forked it."
  [stop? on-stop thunk] (run-bounded* stop? on-stop thunk))

(def ^:private install-signal-handler*
  (scm/eval-string "(lambda (sig f) (register-signal-handler sig (lambda (s) (f))))"))

(defn on-signal!
  "Run (f) in the main thread at its next safe point whenever signal sig arrives; f may
  throw, which unwinds the interrupted computation (as ^C does at Jolt's REPL)."
  [sig f] (install-signal-handler* sig f))

(def SIGUSR1 30)
(def SIGALRM 14)

(defn sigmask
  "This thread's blocked-signal mask (macOS: a 32-bit set), for diagnosis."
  []
  (scm/eval-string "(let ([old (sa-foreign-alloc 128)]) (c-pthread-sigmask jolt-sig-block-how 0 old) (let ([v (foreign-ref 'unsigned-32 old 0)]) (sa-foreign-free old) v))"))

(defn unblock-signals!
  "Unblock sigs on this thread. A process forked from a thread Jolt created inherits that
  thread's mask, so an agent unblocks the signals it is driven by."
  [sigs]
  (scm/eval-string (str "(c-pthread-sigmask jolt-sig-unblock-how (jolt-make-sigset '(" (clojure.string/join " " sigs) ")) 0)")))

(ffi/defcfn c-setitimer "setitimer" [:int :pointer :pointer] :int)

(defn alarm-in!
  "Deliver SIGALRM to this process after ms (ITIMER_REAL); 0 disarms."
  [ms]
  (let [b (ffi/alloc 32)]            ; struct itimerval: {interval, value}, timeval = {long sec; int usec; pad}
    (ffi/write b :long 0) (ffi/write (+ b 8) :int 0)
    (ffi/write (+ b 16) :long (quot ms 1000)) (ffi/write (+ b 24) :int (* 1000 (mod ms 1000)))
    (c-setitimer 0 b ffi/null)))

(def active-threads (scm/proc "proto-active-threads"))
(def bytes-allocated (scm/proc "proto-bytes-allocated"))
(def current-memory (scm/proc "proto-current-memory"))
(def bytes-in-heap (scm/proc "proto-bytes-in-heap"))
(def collections (scm/proc "proto-collections"))
(def static! (scm/proc "proto-static!"))
(def set-trip! (scm/proc "proto-set-trip!"))
(def qfork (scm/proc "proto-qfork"))
(def qfork-bounded* (scm/proc "proto-qfork-bounded"))
(defn safe-fork
  "fork() only while this is the one active Chez thread (a fork beside a running thread
  breaks the child, E11/E13); waits up to ms, then answers -2."
  [ms] (qfork-bounded* (* 20 ms)))

;; ---- stats ----
(defn percentile [xs p]
  (let [v (vec (sort xs)) n (count v)] (when (pos? n) (nth v (min (dec n) (int (Math/floor (* p n))))))))
(defn r2 [x] (when x (/ (Math/round (* 100.0 (double x))) 100.0)))
(defn summ [xs]
  (if (seq xs)
    {:n (count xs) :min (r2 (apply min xs)) :p50 (r2 (percentile xs 0.5)) :p95 (r2 (percentile xs 0.95))
     :p99 (r2 (percentile xs 0.99)) :max (r2 (apply max xs))}
    {:n 0}))
(defn push-window "x appended to window v, keeping the last n." [v x n]
  (let [v (conj (or v []) x)] (if (> (count v) n) (subvec v (- (count v) n)) v)))
(defn p50 "The median of xs, rounded." [xs] (some-> (percentile xs 0.5) r2))
