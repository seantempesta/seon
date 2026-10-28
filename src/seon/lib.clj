(ns seon.lib
  "Host pieces shared by root, the fork server and agents: libc FFI, pipes, a
  poll-driven line reader, Chez introspection and GC controls, fork!, the Mach memory
  probe (native/probe.c, built by bin/build), small stats. From proto/flow f2-final
  proto.lib (itself e13.lib, trimmed)."
  (:refer-clojure :exclude [defn defn-])
  (:require [jolt.ffi :as ffi] [jolt.scheme :as scm]
            [seon.contract :refer [defn defn- defchecked] :as contract] [seon.config :as config] [jolt.io-poller :as poller]
            [seon.edn :as sedn]))

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
(defc c-close "close" [:int] :int)
(defc c-shutdown "shutdown" [:int :int] :int)
(defc c-write "write" [:int :pointer :size_t] :ssize_t {:blocking true :capture-native-error true})
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
(defc kq-new "e13_kq_new" [] :long)
(defc kq-watch "e13_kq_watch" [:int :int] :long)
(defc kq-watch-fd "e13_kq_watch_fd" [:int :int] :long)

(defc kq-wait "e13_wait" [:int :int] :long {:blocking true})
(defc kq-poll "e13_poll" [:int :int] :long)
(defc c-getppid "getppid" [] :int)
(defc end-with* "e13_end_with" [:int :int] :long)
(defc end-again* "e13_end_with_again" [] :long)
(defc end-watched "e13_end_watched" [] :long)

(declare end-with!)

(defn- armed
  "end-with!'s answer: nil once the watch thread runs; the OS refusing it is :seon.lib/starter-unwatched, thrown."
  {:malli/schema [:=> [:cat :int [:maybe :seon.lib/pid]] :nil]}
  [r pid]
  (when (neg? r)
    (let [e (contract/error :seon.lib/starter-unwatched #'seon.lib/end-with! (str "the OS refused the thread that ends this process with " (or pid "its starter")) {:pid pid})]
      (throw (ex-info (:seon.error/message e) e)))))

(defn end-with!
  "End this process when pid exits (parent? true: pid is its parent): a native thread waits on pid's
  NOTE_EXIT and SIGKILLs this process's group if it leads one, else this process, so nothing it
  started outlives what started it, even a process whose runtime holds a lock and runs no Scheme. What
  it forks watches the same pid (fork! arms it again in the child). nil; a thread the OS refused
  throws :seon.lib/starter-unwatched. docs/failure-points.md#failure-points-met-without-a-catch-the-edge-answers-data."
  {:malli/schema [:function [:=> [:cat :seon.lib/pid :boolean] :nil] [:=> [:cat] :nil]]}
  ([pid parent?] (armed (end-with* pid (if parent? 1 0)) pid))
  ([] (armed (end-again*) nil)))

(defn wait-fd-or-exit
  "A wait on fd's input and child pid's exit, whichever comes first, with no clock: one kqueue
  watching both (EVFILT_READ, and NOTE_EXIT, the fact the fork server reaps by), parked on as a
  fiber parks on any descriptor (jolt.io-poller: on a main carrier the process's other fibers run).
  Answers {:wait (a fn that parks until either comes and answers :input or :exited; once the child
  has exited, :exited at once) :close (frees the kqueue)}. A child gone before it was watched has
  exited already. A kernel that will not watch either, or a wait it fails, is :seon.lib/unwatched,
  answered as data (the watch's, or :wait's)."
  {:malli/schema [:=> [:cat :seon.lib/fd pos-int?] [:or [:map [:wait :seon.host/fn] [:close :seon.host/fn]] :seon.lib/unwatched]]}
  [fd pid]
  (let [kq (kq-new)
        fd-watch (kq-watch-fd kq fd)
        ;; -1: the child is gone already; -2: the kernel refused
        pid-watch (kq-watch kq pid)
        unwatched #(contract/error :seon.lib/unwatched #'seon.lib/wait-fd-or-exit
                                   (str "fd " fd " and process " pid " could not be watched (kqueue): " %) {:fd fd :pid pid})]
    (if (or (neg? fd-watch) (= -2 pid-watch))
      (do (c-close kq) (unwatched "the kernel refused the watch"))
      (let [exited (volatile! (= -1 pid-watch))]
        {:wait (fn [] (if @exited
                        :exited
                        (loop []
                          (poller/wait-ready kq :read)
                          ;; e13_poll never waits: 0 for fd's input, the pid at its exit, -3 when kq's
                          ;; readiness held no event yet (park again)
                          (let [r (kq-poll kq fd)]
                            (cond (pos? r) (do (vreset! exited true) :exited)
                                  (zero? r) :input
                                  (= -3 r) (recur)
                                  :else (unwatched "the wait failed"))))))
         :close (fn [] (poller/forget! kq) (c-close kq))}))))
(defc mp-probe* "mp_probe" [:pointer :long] :long)
(defc nthreads "e13_nthreads" [] :long)
(defc sysmem* "e13_sysmem" [:pointer] :long)

(def PAGE 16384)
(defn ignore-sigpipe! {:malli/schema [:=> [:cat] :seon.ffi/address]}
  [] (c-signal 13 1))

(defn mp-probe
  "This process's memory as macOS's ledger holds it (native/probe.c, task_info TASK_VM_INFO):
  {:phys-fp :internal :compressed :resident} in MB."
  {:malli/schema [:=> [:cat] :seon.lib/probe]}
  []
  (let [b (ffi/alloc 256) n (mp-probe* b 256)]
    (if (neg? n)
      (throw (ex-info "task_info refused this process's memory"
                      {:seon.error/type :seon.lib/probe-refused :seon.error/message "task_info refused this process's memory"
                       :kern-return (- n)}))
      (read-string (ffi/read-bytes b n)))))

(defn real-mb
  "This process's memory as macOS charges it, in MB: its phys_footprint (what Activity Monitor and
  jetsam charge: private dirty pages, compressed ones included). A forked child is charged what
  it writes, never what it only shares with its parent, and the parent keeps its own charge
  (docs/cost.md#how-we-measure)."
  {:malli/schema [:function [:=> [:cat] :seon.lib/mb] [:=> [:cat :seon.lib/probe] :seon.lib/mb]]}
  ([] (real-mb (mp-probe)))
  ([p] (double (:phys-fp p))))

(defn sysmem {:malli/schema [:=> [:cat] :seon.lib/sysmem]}
  [] (let [b (ffi/alloc 24)] (sysmem* b)
                  {:free-mb (/ (* PAGE (ffi/read b :long 0)) 1048576.0) :internal-mb (/ (* PAGE (ffi/read b :long 8)) 1048576.0)}))

(defn dir-texts
  "The text of each file in dir whose name ends with suffix, by name, sorted: the one read of a
  directory of files (the wiki's pages, the task files). A file listed and gone when it is read
  (a git merge or bin/update rewriting it, a link to nothing) is left out of this read, as a
  deleted file is: the change that removed it, or the one that writes it back, makes the next
  read (task seon.watch/a-vanished-file-is-a-fact; docs/failure-points.md)."
  {:malli/schema [:=> [:cat :string :string] [:map-of :string :string]]}
  [dir suffix]
  (into (sorted-map)
        (keep (fn [^java.io.File f]
                (let [n (.getName f)]
                  (when (.endsWith n suffix)
                    (try [n (slurp f)]
                         (catch java.io.FileNotFoundException _ nil))))))
        (or (seq (.listFiles (java.io.File. dir))) [])))

(defn decode-status {:malli/schema [:=> [:cat :int] :seon.lib/exit-status]}
  [st]
  (let [sig (bit-and st 0x7f)] (if (zero? sig) {:exit (bit-and (bit-shift-right st 8) 0xff)} {:signal sig})))

;; ---- Chez introspection and GC controls, defined once in Scheme ----
(defonce scheme-defs
  (do (scm/eval-string
        "(define (proto-active-threads)
           (disable-interrupts) (mutex-acquire #%$tc-mutex)
           (let ([n (jolt-active-threads)]) (mutex-release #%$tc-mutex) (enable-interrupts) n))
         (define (proto-bytes-in-heap) (bytes-allocated))
         (define (proto-static!) (collect (collect-maximum-generation) 'static))
         ;; fork only while this is the one active Chez thread, checked once under the thread
         ;; mutex; -2 when another is active (an agent's second thread is a defect, never waited out).
         ;; Read through jolt-active-threads: a thread in a collect-safe call (a read) is parked, which
         ;; our Chez counts only once converted (docs/forks.md, jolt/a-thread-in-c-is-not-active).
         ;; Parked threads are not active, so the fork can leave jolt's carriers, timer and shutdown
         ;; watcher behind: the child drops their bookkeeping first (jolt-after-fork-child!), and
         ;; they start afresh on first use there
         (define proto-fork-alone
           (let ([fork (foreign-procedure \"fork\" () int)])
             (lambda ()
               (disable-interrupts)
               (mutex-acquire #%$tc-mutex)
               (let ([pid (if (= (jolt-active-threads) 1) (fork) -2)])
                 (mutex-release #%$tc-mutex)
                 (enable-interrupts)
                 (when (= pid 0) (jolt-after-fork-child!))
                 pid))))")
      true))

(defn heap-cap!
  "Bound this process's heap at n bytes and start its collections every trip bytes: Jolt's own
  ceiling (JOLT_MAX_HEAP) and nursery floor (JOLT_NEW_SIZE), re-installed here in one go; past
  the ceiling an allocation throws OutOfMemoryError, and the adaptive nursery never sizes the
  trip below the floor. Built binaries only (source mode installs no collector policy). See
  docs/machine.md#what-a-process-owns-in-memory."
  {:malli/schema [:=> [:cat :int :int] :any]}
  [n trip]
  (scm/eval-string (str "(begin (putenv \"JOLT_MAX_HEAP\" \"" (long n) "\") (putenv \"JOLT_NEW_SIZE\" \"" (long trip) "\") "
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

(defchecked ^:private throw-frames [:=> [:cat :any] :string]
  ;; the frames a throw unwound from, innermost first, as Jolt's fiber dump names them (fibers.ss
  ;; jolt-continuation-frames over the throw's continuation, source-registry.ss
  ;; jolt-error-continuation): what a forked child that threw out of its code says of where
  (scm/eval-string "(lambda (e) (let ((k (jolt-error-continuation e))) (if k (format \"~s\" (jolt-continuation-frames k 30)) \"no continuation\")))"))

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
(defn macos? "Is this macOS (kqueue, Mach)?" {:malli/schema [:=> [:cat] :boolean]}
  [] (.startsWith (str (System/getProperty "os.name")) "Mac"))
(def SIGUSR1 "Root's interrupt: 30 on macOS, 10 on Linux." (if linux? 10 30))
(def SIGIO "Input on a socket that asked for it (O_ASYNC): 23 on macOS, 29 on Linux." (if linux? 29 23))

(defchecked active-threads [:=> [:cat] :int] (scm/proc "proto-active-threads"))
(defchecked bytes-in-heap [:=> [:cat] :int] (scm/proc "proto-bytes-in-heap"))
(defchecked static! [:=> [:cat] :any] (scm/proc "proto-static!"))
(defchecked ^:private fork-alone* [:=> [:cat] :seon.lib/pid] (scm/proc "proto-fork-alone"))

(defn wait-status
  "Reap child pid (blocking waitpid): its decoded exit status."
  {:malli/schema [:=> [:cat :seon.lib/pid] :seon.lib/exit-status]}
  [pid] (let [b (ffi/alloc 4)] (c-waitpid pid b 0) (decode-status (ffi/read b :int))))

(defn fork!
  "The one spawning skeleton (AGENTS section 2): fork only while this is the one active Chez
  thread (E11/E13: a fork beside a running thread breaks the child), checked once. Then
  (child) in the child, exiting 0 if it returns and 1 however else it ends (a throw, said on
  stderr with the frames it unwound from; a raise or an escape no catch takes): the child holds a copy of the parent's frames,
  and unwinding into them would run the parent's code in the child; (refused n) here, n the
  active threads (1: the OS refused); or (parent pid) here, whose answer is this fn's. fd, the
  child's own, is closed here. double? forks the child again and reaps the middle: a clone is
  nobody's child."
  {:malli/schema [:=> [:cat [:map [:child :seon.host/fn] [:refused :seon.host/fn] [:parent :seon.host/fn]
                             [:fd {:optional true} :seon.lib/fd] [:double? {:optional true} :boolean]]] :any]}
  [{:keys [child refused parent fd double?]}]
  (let [pid (fork-alone*)]
    (if (zero? pid)
      (let [status (volatile! 1) said? (volatile! false)]
        (try (when (or (not double?) (zero? (c-fork))) (end-with!) (child))
             (vreset! status 0)
             (catch Throwable e
               (vreset! said? true)
               (binding [*out* *err*] (println "a forked child threw out of its code:" (.getName (class e)) (ex-message e) (sedn/encode (ex-data e))
                                         "\n  from:" (throw-frames e)) (flush)))
             ;; a finally is a winder, run on any exit from the try (a fiber's park drops it). A child
             ;; that ends 1 with no throw said left its code by an escape no catch takes: said here, so
             ;; an exit 1 with nothing on stderr is not the child's code's
             (finally
               (when (and (= 1 @status) (not @said?))
                 (binding [*out* *err*] (println "a forked child left its code by an escape no catch took: exiting 1") (flush)))
               (c-exit @status))))
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
