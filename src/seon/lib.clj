(ns seon.lib
  "Host pieces shared by root, the fork server and agents: libc FFI, pipes, a
  poll-driven line reader, Chez introspection and GC controls, qfork, the Mach memory
  probe (native/probe.c, built by bin/build), small stats. From proto/flow f2-final
  proto.lib (itself e13.lib, trimmed)."
  (:refer-clojure :exclude [defn defn-])
  (:require [jolt.ffi :as ffi] [jolt.scheme :as scm] [clojure.string :as str]
            [seon.contract :refer [defn defn- defchecked]]))

(def ^:private c-schemas
  "Malli schemas of C types, for contracts derived from an FFI declaration (G1's proto.lib)."
  {:int :int :long :int :int64 :int :uint32 [:int {:min 0}] :uint64 :int :size_t [:int {:min 0}]
   :ssize_t :int :int16 :int :uint8 :int :string :string :pointer :seon.ffi/address :void :seon.host/void})

(defmacro defc
  "ffi/defcfn, its value checked from its definition on by the contract derived from its
  C signature (each C type's schema, c-schemas): the FFI edge is checked like every other
  function, direct-linked callers included (G1's proto.lib/defc)."
  [sym cname args ret & opts]
  (let [[_ _ init] (macroexpand-1 `(ffi/defcfn ~sym ~cname ~args ~ret ~@opts)) args (remove #{:varargs} args)]
    `(defchecked ~sym [:=> ~(into [:cat] (mapv c-schemas args)) ~(c-schemas ret)] ~init)))

(ffi/load-library)
(defmacro ^:private probe-lib-default
  "Where bin/build puts native/probe.c's library, read when this namespace compiles (a
  built binary carries the path)."
  [] (str (or (System/getenv "JOLT_PWD") ".") "/target/lib/libprobe.dylib"))
(ffi/load-library (or (System/getenv "SEON_PROBE_LIB") (probe-lib-default)))

(defc c-fork "fork" [] :int)
(defc c-exit "_exit" [:int] :void)
(defc c-getpid "getpid" [] :int)
(defc c-getppid "getppid" [] :int)
(defc c-waitpid "waitpid" [:int :pointer :int] :int {:blocking true})
(defc c-waitpid-nb "waitpid" [:int :pointer :int] :int)
(defc c-pipe "pipe" [:pointer] :int)
(defc c-close "close" [:int] :int)
(defc c-read "read" [:int :pointer :size_t] :ssize_t {:blocking true})
(defc c-write "write" [:int :pointer :size_t] :ssize_t {:blocking true})
(defc c-signal "signal" [:int :pointer] :pointer)
(defc c-kill "kill" [:int :int] :int)
(defc c-setpgid "setpgid" [:int :int] :int)
(defc c-unsetenv "unsetenv" [:string] :int)
(defc c-creat "creat" [:string :int] :int)
(defc c-dup2 "dup2" [:int :int] :int)
(defc c-setrlimit "setrlimit" [:int :pointer] :int)
(defc c-getrlimit "getrlimit" [:int :pointer] :int)

(defn raise-open-files!
  "Raise this process's open-file limit to n (or its hard limit): root holds one socket
  per agent. Answers the new soft limit."
  {:malli/schema [:=> [:cat :int] :int]}
  [n]
  (let [b (ffi/alloc 16)]
    (c-getrlimit 8 b)
    (ffi/write b :uint64 (min n (ffi/read b :uint64 8)) 0)
    (c-setrlimit 8 b)
    (c-getrlimit 8 b)
    (ffi/read b :uint64 0)))
(defc poll1 "e13_poll1" [:int :int] :long {:blocking true})
(defc mp-probe* "mp_probe" [:pointer :long] :long)
(defc events* "e13_events" [:pointer] :long)
(defc nthreads "e13_nthreads" [] :long)
(defc sysmem* "e13_sysmem" [:pointer] :long)

(def PAGE 16384)
(defn ignore-sigpipe! {:malli/schema [:=> [:cat] :seon.ffi/address]}
  [] (c-signal 13 1))
(defn now-ns {:malli/schema [:=> [:cat] :seon.lib/ns-time]}
  [] (System/nanoTime))
(defn ms-since {:malli/schema [:=> [:cat :seon.lib/ns-time] :seon.lib/ms]}
  [t0] (/ (- (now-ns) t0) 1e6))

(defn mp-probe
  "This process's Mach VM accounting: {:phys-fp :internal :resident :regions {:jit :malloc :anon0 :other}} in MB."
  {:malli/schema [:=> [:cat] :seon.lib/probe]}
  []
  (let [b (ffi/alloc 4096) n (mp-probe* b 4096)] (read-string (ffi/read-bytes b n))))

(defn real-mb
  "Pages this process owns (copied from its parent or newly dirtied), in MB: per method.md,
  footprint overstates a forked child, so count shared-now-private + private resident pages."
  {:malli/schema [:function [:=> [:cat] :seon.lib/mb] [:=> [:cat :seon.lib/probe] :seon.lib/mb]]}
  ([] (real-mb (mp-probe)))
  ([p] (reduce + 0.0 (for [k [:jit :malloc :anon0 :other]
                           :let [r (get-in p [:regions k])]]
                       (+ (:snp r 0.0) (:prv-res r 0.0))))))

(defn events {:malli/schema [:=> [:cat] :seon.lib/events]}
  [] (let [b (ffi/alloc 24)] (events* b) {:cow-faults (ffi/read b :long 0) :faults (ffi/read b :long 8)}))
(defn sysmem {:malli/schema [:=> [:cat] :seon.lib/sysmem]}
  [] (let [b (ffi/alloc 24)] (sysmem* b)
                  {:free-mb (/ (* PAGE (ffi/read b :long 0)) 1048576.0) :internal-mb (/ (* PAGE (ffi/read b :long 8)) 1048576.0)}))

(defn pipe {:malli/schema [:=> [:cat] :seon.lib/pipe]}
  []
  (let [b (ffi/alloc 8)]
    (when-not (zero? (c-pipe b)) (throw (ex-info "pipe failed" {})))
    [(ffi/read b :int) (ffi/read (+ b 4) :int)]))

(defn write-all! {:malli/schema [:=> [:cat :seon.lib/fd :seon.host/bytes] :int]}
  [fd ^bytes arr]
  (let [n (alength arr) buf (ffi/alloc (max 1 n))]
    (ffi/write-array buf arr)
    (loop [off 0]
      (if (< off n)
        (let [r (c-write fd (+ buf off) (- n off))]
          (if (neg? r) -1 (recur (+ off r))))
        n))))

(defn write-line! {:malli/schema [:=> [:cat :seon.lib/fd :any] :int]}
  [fd s] (write-all! fd (.getBytes (str s "\n") "UTF-8")))

(defn line-reader
  "A fn of a wait in ms (negative: until something arrives) that answers the complete
  lines that arrived (a vector, maybe empty) or :eof. A signal ending the wait (EINTR)
  answers [] and the caller waits again."
  {:malli/schema [:=> [:cat :seon.lib/fd] :seon.host/fn]}
  [fd]
  (let [cap 1048576 buf (ffi/alloc cap) pending (volatile! (byte-array 0))]
    (fn [ms]
      (if (<= (poll1 fd ms) 0)
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

(defn decode-status {:malli/schema [:=> [:cat :int] :seon.lib/exit-status]}
  [st]
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
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (scm/eval-string (str "(begin (putenv \"JOLT_MAX_HEAP\" \"" (long n) "\") "
                        "(when jolt-gc-policy-installed? (jolt-install-gc-policy!)) jolt-heap-ceiling-bytes)")))

(defchecked ^:private install-signal-handler* [:=> [:cat :int :seon.host/fn] :any]
  (scm/eval-string "(lambda (sig f) (register-signal-handler sig (lambda (s) (f))))"))

(defn on-signal!
  "Run (f) in the main thread at its next safe point whenever signal sig arrives; f may
  throw, which unwinds the interrupted computation (as ^C does at Jolt's REPL)."
  {:malli/schema [:=> [:cat :int :seon.host/fn] :any]}
  [sig f] (install-signal-handler* sig f))

(def ^:private linux? (.startsWith (str (System/getProperty "os.name")) "Linux"))
(def SIGUSR1 "Root's interrupt: 30 on macOS, 10 on Linux." (if linux? 10 30))
(def SIGSTOP "Stop the process: 17 on macOS, 19 on Linux." (if linux? 19 17))
(def SIGCONT "Continue the process: 19 on macOS, 18 on Linux." (if linux? 18 19))

(defn sigmask
  "This thread's blocked-signal mask (macOS: a 32-bit set), for diagnosis."
  {:malli/schema [:=> [:cat] :int]}
  []
  (scm/eval-string "(let ([old (sa-foreign-alloc 128)]) (c-pthread-sigmask jolt-sig-block-how 0 old) (let ([v (foreign-ref 'unsigned-32 old 0)]) (sa-foreign-free old) v))"))

(defn unblock-signals!
  "Unblock sigs on this thread. A process forked from a thread Jolt created inherits that
  thread's mask, so an agent unblocks the signals it is driven by."
  {:malli/schema [:=> [:cat [:sequential :int]] :any]}
  [sigs]
  (scm/eval-string (str "(c-pthread-sigmask jolt-sig-unblock-how (jolt-make-sigset '(" (clojure.string/join " " sigs) ")) 0)")))

(defchecked active-threads [:=> [:cat] :int] (scm/proc "proto-active-threads"))
(defchecked bytes-allocated [:=> [:cat] :int] (scm/proc "proto-bytes-allocated"))
(defchecked current-memory [:=> [:cat] :int] (scm/proc "proto-current-memory"))
(defchecked bytes-in-heap [:=> [:cat] :int] (scm/proc "proto-bytes-in-heap"))
(defchecked collections [:=> [:cat] :int] (scm/proc "proto-collections"))
(defchecked static! [:=> [:cat] :any] (scm/proc "proto-static!"))
(defchecked set-trip! [:=> [:cat :int] :any] (scm/proc "proto-set-trip!"))
(defchecked qfork [:=> [:cat] :seon.lib/pid] (scm/proc "proto-qfork"))
(defchecked qfork-bounded* [:=> [:cat :int] :seon.lib/pid] (scm/proc "proto-qfork-bounded"))
(defn safe-fork
  "fork() only while this is the one active Chez thread (a fork beside a running thread
  breaks the child, E11/E13); waits up to ms, then answers -2."
  {:malli/schema [:=> [:cat :int] :seon.lib/pid]}
  [ms] (qfork-bounded* (* 20 ms)))

;; ---- stats ----
(defn percentile {:malli/schema [:=> [:cat [:maybe [:sequential number?]] [:double {:min 0.0 :max 1.0}]] [:maybe number?]]}
  [xs p]
  (let [v (vec (sort xs)) n (count v)] (when (pos? n) (nth v (min (dec n) (int (Math/floor (* p n))))))))
(defn r2 {:malli/schema [:=> [:cat [:maybe number?]] [:maybe :double]]}
  [x] (when x (/ (Math/round (* 100.0 (double x))) 100.0)))
(defn summ {:malli/schema [:=> [:cat [:sequential number?]] :seon.lib/summary]}
  [xs]
  (if (seq xs)
    {:n (count xs) :min (r2 (apply min xs)) :p50 (r2 (percentile xs 0.5)) :p95 (r2 (percentile xs 0.95))
     :p99 (r2 (percentile xs 0.99)) :max (r2 (apply max xs))}
    {:n 0}))
(defn push-window "x appended to window v, keeping the last n." {:malli/schema [:=> [:cat [:maybe vector?] :any pos-int?] vector?]}
  [v x n]
  (let [v (conj (or v []) x)] (if (> (count v) n) (subvec v (- (count v) n)) v)))
(defn p50 "The median of xs, rounded." {:malli/schema [:=> [:cat [:maybe [:sequential number?]]] [:maybe :double]]}
  [xs] (some-> (percentile xs 0.5) r2))
