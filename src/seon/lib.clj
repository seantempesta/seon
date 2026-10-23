(ns seon.lib
  "Host pieces shared by root, the fork server and agents: libc FFI, pipes, a
  poll-driven line reader, Chez introspection and GC controls, fork!, the Mach memory
  probe (native/probe.c, built by bin/build), small stats. From proto/flow f2-final
  proto.lib (itself e13.lib, trimmed)."
  (:refer-clojure :exclude [defn defn-])
  (:require [jolt.ffi :as ffi] [jolt.scheme :as scm] [clojure.string :as str]
            [seon.contract :refer [defn defn- defchecked]] [seon.config :as config]))

(def ^:private c-schemas
  "Malli schemas of C types, for contracts derived from an FFI declaration (G1's proto.lib)."
  {:int :int :long :int :int64 :int :uint32 [:int {:min 0}] :uint64 :int :size_t [:int {:min 0}]
   :ssize_t :int :int16 :int :uint8 :int :string :string :pointer :seon.ffi/address :void :seon.host/void})

(defmacro defc
  "ffi/defcfn, its value checked from its definition on by the contract derived from its
  C signature (each C type's schema, c-schemas): the FFI edge is checked like every other
  function, direct-linked callers included (G1's proto.lib/defc). With
  {:capture-native-error true} it answers [result errno], errno read at the call."
  [sym cname args ret & opts]
  (let [[_ _ init] (macroexpand-1 `(ffi/defcfn ~sym ~cname ~args ~ret ~@opts)) args (remove #{:varargs} args)
        out (if (some #(and (map? %) (:capture-native-error %)) opts) [:tuple (c-schemas ret) :int] (c-schemas ret))]
    `(defchecked ~sym [:=> ~(into [:cat] (mapv c-schemas args)) ~out] ~init)))

(ffi/load-library)
;; native/probe.c's library (bin/build native): :seon.config.native/probe-lib
(ffi/load-library (config/value :seon.config.native/probe-lib))

(defc c-fork "fork" [] :int)
(defc c-exit "_exit" [:int] :void)
(defc c-getpid "getpid" [] :int)
(defc c-chdir "chdir" [:string] :int)
(defc c-waitpid "waitpid" [:int :pointer :int] :int {:blocking true})
(defc c-pipe "pipe" [:pointer] :int)
(defc c-close "close" [:int] :int)
(defc c-shutdown "shutdown" [:int :int] :int)
(defc c-read "read" [:int :pointer :size_t] :ssize_t {:blocking true})
(defc c-write "write" [:int :pointer :size_t] :ssize_t {:blocking true})
(defc c-signal "signal" [:int :pointer] :pointer)
(defc c-kill "kill" [:int :int] :int)
(defc c-setpgid "setpgid" [:int :int] :int)
(defc c-unsetenv "unsetenv" [:string] :int)
(defc c-creat "creat" [:string :int] :int)
(defc c-rename "rename" [:string :string] :int {:capture-native-error true})
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
(defc kq-new "e13_kq_new" [] :long)
(defc kq-watch "e13_kq_watch" [:int :int] :long)
(defc kq-wait "e13_wait" [:int :int] :long {:blocking true})
(defc mp-probe* "mp_probe" [:pointer :long] :long)
(defc nthreads "e13_nthreads" [] :long)
(defc sysmem* "e13_sysmem" [:pointer] :long)

(def PAGE 16384)
(defn ignore-sigpipe! {:malli/schema [:=> [:cat] :seon.ffi/address]}
  [] (c-signal 13 1))

(defn mp-probe
  "This process's Mach VM accounting: {:phys-fp :internal :resident :regions {:jit :malloc :anon0 :other}} in MB."
  {:malli/schema [:=> [:cat] :seon.lib/probe]}
  []
  (let [b (ffi/alloc 4096) n (mp-probe* b 4096)] (read-string (ffi/read-bytes b n))))

(defn real-mb
  "Pages this process owns (copied from its parent or newly dirtied), in MB: footprint
  overstates a forked child (docs/cost.md#how-we-measure), so count shared-now-private +
  private resident pages."
  {:malli/schema [:function [:=> [:cat] :seon.lib/mb] [:=> [:cat :seon.lib/probe] :seon.lib/mb]]}
  ([] (real-mb (mp-probe)))
  ([p] (reduce + 0.0 (for [k [:jit :malloc :anon0 :other]
                           :let [r (get-in p [:regions k])]]
                       (+ (:snp r 0.0) (:prv-res r 0.0))))))

(defn sysmem {:malli/schema [:=> [:cat] :seon.lib/sysmem]}
  [] (let [b (ffi/alloc 24)] (sysmem* b)
                  {:free-mb (/ (* PAGE (ffi/read b :long 0)) 1048576.0) :internal-mb (/ (* PAGE (ffi/read b :long 8)) 1048576.0)}))

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
         (define (proto-bytes-in-heap) (bytes-allocated))
         (define (proto-static!) (collect (collect-maximum-generation) 'static))
         (define (proto-set-trip! n) (collect-trip-bytes n))
         ;; fork only while this is the one active Chez thread, checked once under the thread
         ;; mutex; -2 when another is active (an agent's second thread is a defect, never waited out).
         ;; Parked threads are not active, so the fork can leave jolt's carriers, timer and shutdown
         ;; watcher behind: the child drops their bookkeeping first (jolt-after-fork-child!), and
         ;; they start afresh on first use there
         (define proto-fork-alone
           (let ([fork (foreign-procedure \"fork\" () int)])
             (lambda ()
               (disable-interrupts)
               (mutex-acquire #%$tc-mutex)
               (let ([pid (if (= (#%$top-level-value '$active-threads) 1) (fork) -2)])
                 (mutex-release #%$tc-mutex)
                 (enable-interrupts)
                 (when (= pid 0) (jolt-after-fork-child!))
                 pid))))")
      true))

(defn heap-cap!
  "Bound this process's heap at n bytes: Jolt's own ceiling (JOLT_MAX_HEAP), re-installed
  here; past it an allocation throws OutOfMemoryError. Built binaries only (source mode
  installs no collector policy)."
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (scm/eval-string (str "(begin (putenv \"JOLT_MAX_HEAP\" \"" (long n) "\") "
                        "(when jolt-gc-policy-installed? (jolt-install-gc-policy!)) jolt-heap-ceiling-bytes)")))

(defchecked ^:private collect-sweepers* [:=> [:cat :int] :int]
  (scm/eval-string "(foreign-procedure \"(cs)collect_sweepers\" (iptr) iptr)"))

(defn gc-sweepers!
  "Fix Chez's parallel-collector sweeper pool at n threads (our Chez, jolt/gc-sweepers):
  the n start now and no collection starts another. Unfixed, a collection starts one per
  thread it finds waiting and keeps it, so the pool's size depends on timing. n < 0 only
  asks. Answers the sweepers running. A process that forks must never start one
  (docs/failure-points.md, physics)."
  {:malli/schema [:=> [:cat :int] :int]}
  [n] (collect-sweepers* n))

(defchecked ^:private gc-stats* [:=> [:cat] [:vector :int]]
  (scm/eval-string "(lambda () (let ([s (statistics)] [ms (lambda (t) (+ (* 1000 (time-second t)) (quotient (time-nanosecond t) 1000000)))])
                                  (jolt-vector (sstats-gc-count s) (ms (sstats-gc-real s)) (ms (sstats-gc-cpu s)))))"))

(defn gc-stats
  "This process's collections so far: {:count n :real-ms :cpu-ms} (Chez's statistics;
  :cpu-ms sums every sweeper's CPU)."
  {:malli/schema [:=> [:cat] [:map [:count :int] [:real-ms :int] [:cpu-ms :int]]]}
  [] (let [[c r u] (gc-stats*)] {:count c :real-ms r :cpu-ms u}))

(defchecked ^:private install-signal-handler* [:=> [:cat :int :seon.host/fn] :any]
  (scm/eval-string "(lambda (sig f) (register-signal-handler sig (lambda (s) (f))))"))

(defn on-signal!
  "Run (f) in the main thread at its next safe point whenever signal sig arrives; f may
  throw, which unwinds the interrupted computation (as ^C does at Jolt's REPL)."
  {:malli/schema [:=> [:cat :int :seon.host/fn] :any]}
  [sig f] (install-signal-handler* sig f))

(def ^:private linux? (.startsWith (str (System/getProperty "os.name")) "Linux"))
(def SIGUSR1 "Root's interrupt: 30 on macOS, 10 on Linux." (if linux? 10 30))
(def SIGIO "Input on a socket that asked for it (O_ASYNC): 23 on macOS, 29 on Linux." (if linux? 29 23))

(defchecked active-threads [:=> [:cat] :int] (scm/proc "proto-active-threads"))
(defchecked bytes-in-heap [:=> [:cat] :int] (scm/proc "proto-bytes-in-heap"))
(defchecked static! [:=> [:cat] :any] (scm/proc "proto-static!"))
(defchecked set-trip! [:=> [:cat :int] :any] (scm/proc "proto-set-trip!"))
(defchecked ^:private fork-alone* [:=> [:cat] :seon.lib/pid] (scm/proc "proto-fork-alone"))

(defn wait-status
  "Reap child pid (blocking waitpid): its decoded exit status."
  {:malli/schema [:=> [:cat :seon.lib/pid] :seon.lib/exit-status]}
  [pid] (let [b (ffi/alloc 4)] (c-waitpid pid b 0) (decode-status (ffi/read b :int))))

(defn fork!
  "The one spawning skeleton (AGENTS section 2): fork only while this is the one active Chez
  thread (E11/E13: a fork beside a running thread breaks the child), checked once. Then
  (child) in the child, exiting 0 if it returns; (refused n) here, n the active threads (1:
  the OS refused); or (parent pid) here, whose answer is this fn's. fd, the child's own, is
  closed here. double? forks the child again and reaps the middle: a clone is nobody's child."
  {:malli/schema [:=> [:cat [:map [:child :seon.host/fn] [:refused :seon.host/fn] [:parent :seon.host/fn]
                             [:fd {:optional true} :seon.lib/fd] [:double? {:optional true} :boolean]]] :any]}
  [{:keys [child refused parent fd double?]}]
  (let [pid (fork-alone*)]
    (if (zero? pid)
      (do (when (or (not double?) (zero? (c-fork))) (child)) (c-exit 0))
      (do (some-> fd c-close)
          (if (neg? pid) (refused (active-threads)) (do (when double? (wait-status pid)) (parent pid)))))))

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
