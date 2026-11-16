(ns seon.lib-test
  "The native probes (native/probe.c through seon.lib): what they answer."
  (:require [clojure.test :refer [deftest is]] [seon.lib :as lib] [seon.sock :as sock] [jolt.scheme :as scm]
            [seon.test-store :as ts] [seon.config :as config] [clojure.edn :as edn] [clojure.string :as str] [clojure.core.async] [jolt.io-poller]))

(deftest ^{:seon.test/estimate {:ticks 60000000}} a-fork-beside-a-running-thread-waits-for-it-to-park
  ;; lib/fork! is the one spawning skeleton: a second thread running Scheme at the fork breaks the child (E11/E13), so
  ;; jolt.host/fork-alone waits for every other thread to park, woken as each does (Chez fork_alone), then forks. The other
  ;; thread here spins a counted amount of work and ends; the fork started beside it waits it out and forks: never a
  ;; refusal (before, it refused, and 2-3 forks in 100 right after a timeout were refused in production's clone!). The
  ;; estimate: the spin, 2e7 iterations at ~2 ticks, ~40M; the fork and the child's exit ~1M.
  (let [running (promise)
        spin (doto (Thread. #(do (deliver running true) (loop [i 0] (when (< i 20000000) (recur (inc i)))))) (.start))
        _ @running
        r (lib/fork! {:child #(lib/c-exit 3) :parent (fn [pid] [:forked (lib/wait-status pid)]) :refused (fn [n] [:refused n])})]
    (.join spin)
    (is (= [:forked {:exit 3}] r) (str "answered " r))))

(deftest ^{:seon.test/estimate {:ticks 100000000}} a-full-collection-answers-from-a-process-running-several-threads
  ;; task seon.lib/a-full-collection-asked-from-any-thread-answers: Chez refuses (collect) while another thread is active, so
  ;; root's writer :collect and the fork server's freeze test never collected in full (context at root's REPL: "cannot collect
  ;; when multiple threads are active"); a full collection asked from any thread answers. A second thread spins Scheme while it
  ;; is asked. The estimate: one full collection of this test process's heap (~100 MB, ~0.5 ticks a byte: ~50M), the spin beside it
  (let [stop (volatile! false) running (promise)
        spin (doto (Thread. #(do (deliver running true) (loop [] (when-not @stop (recur))))) (.start))
        _ @running
        r (try (lib/non-static-live!) (catch Exception e e))]
    (vreset! stop true)
    (.join spin)
    (is (int? r) (str "answered " r))))

(deftest ^{:seon.test/estimate {:ticks 20000000}} a-forks-wait-ends-at-its-bound-naming-its-holders
  ;; task seon.lib/a-forks-wait-ends-at-its-declared-bound-naming-its-holders: a thread in a C call that is not
  ;; collect-safe never parks, and a fork beside it waited for good (a test child, 2026-11-14). fork! waits at most
  ;; :seon.config.agent/fork-wait-ms and answers refused naming the thread that held it. The estimate: the bound's
  ;; wait parked (no ticks) and one walk of the thread list, a few million ticks.
  ;; the holder runs Scheme and never parks (it holds every fork), reaching safe points (a holder blocked in a plain C call
  ;; would wedge a collection meanwhile, and this test with it)
  ;; the bound is 20 ms here (the key's default, 1000, is only the test's wait): the key read through the one resolver
  (let [stop (atom false) started (promise)
        bound 20
        value config/value
        holder (doto (Thread. #(do (deliver started true) (loop [] (when-not @stop (recur)))) "holder") (.setDaemon true) (.start))]
    (try
      @started
      (let [r (with-redefs [config/value (fn [k & more] (if (= k :seon.config.agent/fork-wait-ms) bound (apply value k more)))]
                (lib/fork! {:child #(lib/c-exit 3) :parent (fn [pid] [:forked (lib/wait-status pid)]) :refused (fn [m] [:refused m])}))
            [k {:keys [holders bound-ms active-threads]}] r]
        (is (= :refused k) (str "answered " r))
        (is (= bound bound-ms))
        (is (<= 2 active-threads))
        (is (some #(= "holder" (:name %)) holders) (str "holders " holders)))
      (finally (reset! stop true) (.join holder)))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-shell-call-answers-its-output-or-says-why-not
  ;; seon.lib/sh, the one shell, answered exit 0 with no output (`git status`, 2026-11-12): Jolt's pipe port read errno in a
  ;; later call, where a collection or another fiber could overwrite it, and took any errno but EINTR and EAGAIN for the end
  ;; of the output. Its read now keeps the errno of its own call and a failed read raises naming it (Jolt gate
  ;; pipereadfailure, red first on a closed descriptor). Here 20 calls at once on fibers, each child writing only after it
  ;; has read its stdin, so its first read finds nothing and parks: each answers exit 0 and its whole text. 4 calls at once
  ;; already overlap their parks on fibers. The estimate: 4 spawns and their pipe reads, ~2M ticks each.
  (let [calls (doall (for [i (range 4)]
                       (future (lib/sh "/bin/sh" "-c" (str "read go; printf 'out-" i "'") :in "go\n"))))
        answers (mapv deref calls)]
    (is (= (mapv (fn [i] {:exit 0 :out (str "out-" i)}) (range 4)) (mapv #(select-keys % [:exit :out]) answers)))))

(def ^:private park-in-a-read
  ;; a raw Chez thread (no Jolt lock taken to start it) blocked in a collect-safe read of fd
  (scm/eval-string
    "(let ([rd (foreign-procedure __collect_safe \"read\" (int u8* size_t) ssize_t)])
       (lambda (fd) (let ([b (make-immobile-bytevector 1)]) (fork-thread (lambda () (rd fd b 1)))) #t))"))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-fork-beside-a-thread-parked-in-a-read-forks
  ;; a thread in a collect-safe call (a read) runs no Scheme, so a fork beside it is a fork beside a
  ;; parked thread. Our Chez counts it in $active-threads until it is converted, and fork! read the
  ;; count bare: it refused every time (Jolt's forkheldlocks waited for good). The thread may not be
  ;; in its read yet at the first try, so the fork is tried again after a yield, at most 100000 times:
  ;; refused that often in a row is red (docs/forks.md, jolt/a-thread-in-c-is-not-active). Its buffer
  ;; is immobile: a collection may run while it reads
  (let [[r w] (sock/socketpair)]
    (try
      (park-in-a-read r)
      (let [answer (loop [n 0]
                     (let [a (lib/fork! {:child #(lib/c-exit 0) :parent (fn [pid] [:forked pid]) :refused (fn [k] [:refused k])})]
                       (if (and (= :refused (first a)) (< n 100000)) (do (Thread/yield) (recur (inc n))) a)))]
        (is (= :forked (first answer)) (str "answered " answer))
        (when (= :forked (first answer))
          (is (= {:exit 0} (lib/wait-status (second answer))))))
      (finally (sock/write-line! w "go") (lib/c-close r) (lib/c-close w)))))

(defn- fork-case
  "Fork case k's answer (seon.check/fork-cases), in a fresh process of the built binary
  (target/seon forks k): a process forks from its main carrier, where it is the one thread,
  never from this runner's, whose runtime threads would race the fork."
  [k]
  (let [{:keys [exit out err]} (ts/binary-sh "./seon" "forks" k "--profile" ts/root-profile :dir "target")]
    (if (zero? exit) (edn/read-string (last (str/split-lines out))) {:exit exit :err err})))

(defn- first-line
  "The first non-empty line a pipe end r gives, read on a parked fiber (the io poller), or nil at its end of file."
  [r]
  (let [rd (sock/line-reader r (fn [] (clojure.core.async/<!! (jolt.io-poller/ready-chan r :read))))]
    (loop [] (let [ls (rd)] (cond (= ls :eof) nil (seq ls) (first ls) :else (recur))))))

;; a wait that reaped nothing read its unwritten status buffer as {:exit 0}: a child another waiter reaped, or one never this
;; process's, answered a clean exit (seon.lib-test/a-forked-child-that-throws-exits-and-never-returns-into-its-parent and
;; seon.db-test/a-killed-writer-leaves-the-page-past-its-commits read 0 where 1 and a kill were, on the candidate, 2026-11-17). Red
;; first: {:exit 0} for pid 1, never this process's child. Estimate: one waitpid that fails at once: 100,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 100000}} a-wait-that-reaps-nothing-is-its-error-never-an-exit
  (let [r (try (lib/wait-status 1) (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :seon.lib/no-child (:seon.error/type r)) (str "no status read is no exit: " (pr-str r)))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-forked-child-that-throws-exits-and-never-returns-into-its-parent
  ;; the child holds a copy of every frame its parent forked from: a throw out of (child) that
  ;; unwound out of lib/fork! ran the parent's code in the child (in an agent's test child, the
  ;; agent's own serve! loop, which died as the agent, exit 70, or went on reading root's socket
  ;; as a second agent). The child ends at fork!, exit 1, saying what threw; one that returns
  ;; exits 0. In this process (a fork beside the runner's parked threads waits for them: no fresh binary): two forks and their
  ;; exits, ~1M ticks each. The throwing child's catch here would exit 7: it never runs
  (is (= [{:exit 1} {:exit 0}]
         [(try (lib/fork! {:child (fn [] (throw (ex-info "out of the child" {}))) :refused (fn [n] {:refused n}) :parent lib/wait-status})
               (catch clojure.lang.ExceptionInfo _ (lib/c-exit 7)))
          (lib/fork! {:child (fn [] :returned) :refused (fn [n] {:refused n}) :parent lib/wait-status})])))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-forked-child-runs-none-of-its-parents-shutdown-hooks
  ;; task seon.lib/a-fork-carries-no-shutdown-hook: Jolt's hooks are the process's list, and a
  ;; fork copied it; root's hook (its whole stop: seon.main/root-serve) ran in an agent or a test
  ;; child that left by System/exit, an uncaught throw or a signal, on root's copied state
  ;; against the shared store. In this process: a hook of the parent's writing a file, one fork leaving by System/exit, ~1M ticks
  (let [f (java.io.File/createTempFile "seon-child-hook" ".txt")
        _ (.delete f)
        hook (Thread. ^Runnable (fn [] (spit f (str (lib/c-getpid)) :append true)))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try (let [st (lib/fork! {:child (fn [] (System/exit 3)) :refused (fn [n] {:refused n}) :parent lib/wait-status})]
           (is (= {:exit 3} st))
           (is (not (.exists f)) (str "the parent's hook ran in the child: " (when (.exists f) (slurp f)))))
         (finally (.removeShutdownHook (Runtime/getRuntime) hook) (.delete f)))))

(deftest a-wait-for-a-line-parks-the-fiber
  ;; an agent waits for its test child's lines for the whole test run (seon.agent/run-tests-forked):
  ;; on the main carrier (the agent's one thread) a poll(2) of the pipe held every other fiber
  ;; of the agent for that long. The wait parks through the io poller: a fiber queued before the
  ;; wait runs while the reader waits (the carrier is free only then), so it ran before the line
  ;; came. A poll(2) holding the carrier lets none run. The fiber's own word to the parent is what
  ;; makes the parent write the line (seon.check/fork-cases "line-parks"), so the verdict is which
  ;; caused which, never how long anything took: a loaded machine cannot turn it red. In a child
  ;; forked from a main carrier, the reader on the child's own fiber
  (is (= {:exit 0} (fork-case "line-parks")) "a fiber ran while the reader waited"))

(deftest ^{:seon.test/estimate {:ticks 6000000}} a-process-ends-with-what-it-watches-and-so-does-what-it-forks
  ;; task seon.lib/a-child-ends-with-its-starter (docs/failure-points.md#failure-points-met-without-a-catch-the-edge-answers-data):
  ;; ./seon problems trees outlived the bin/check a timeout killed, 12 h at 0% CPU with ppid 1. A
  ;; process's C thread ends it when what it watches exits, and fork! arms it again in each child, so
  ;; a child parked in a read (no Scheme of its own would ever end it) ends with the process it watches.
  ;; Here: mid watches itself and forks a child parked in a read; mid is killed; the child ends. 2 forks, ~1M ticks each
  (let [[r w] (sock/socketpair)
        [hold _] (sock/socketpair)
        park (fn [_] (sock/recv! (sock/conn hold)))
        mid (lib/fork! {:keep #{w hold}
                        :child (fn []
                                 (lib/end-with! (lib/c-getpid) false)
                                 (lib/fork! {:keep #{w hold} :child (fn [] (sock/write-line! w (str (lib/c-getpid))) (park nil))
                                             :refused park :parent park}))
                        :refused (fn [n] {:refused n}) :parent identity})
        child (parse-long (first-line r))
        exit (jolt.io-poller/exit-chan child)]
    (lib/c-kill mid 9)
    (lib/wait-status mid)
    (is (= :exited (do (clojure.core.async/<!! exit) :exited)) "the child parked in a read ends with mid")
    (run! lib/c-close [r w])))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-process-watching-a-gone-pid-ends-at-once
  ;; task seon.lib/a-child-ends-with-its-starter: a pid already gone when it is watched ends the
  ;; process at once, though its code would park forever. 2 forks, ~1M ticks each
  (let [gone (lib/fork! {:child (fn [] :done) :refused (fn [n] {:refused n}) :parent identity})]
    (lib/wait-status gone)
    (is (= {:signal 9}
           (lib/fork! {:child (fn [] (lib/end-with! gone false) (sock/recv! (sock/conn (first (sock/socketpair)))))
                       :refused (fn [n] {:refused n}) :parent lib/wait-status})))))

(deftest ^{:seon.test/estimate {:ticks 8000000}} a-process-that-leads-its-group-ends-the-group
  ;; task seon.lib/a-child-ends-with-its-starter: the leader watches anchor; anchor is killed; the
  ;; leader ends its group, so its member, which watches only what still lives, ends with it. 3 forks, ~1M ticks each
  (let [[r w] (sock/socketpair)
        [hold _] (sock/socketpair)
        park (fn [_] (sock/recv! (sock/conn hold)))
        anchor (lib/fork! {:keep #{hold} :child (fn [] (park nil)) :refused (fn [n] {:refused n}) :parent identity})
        leader (lib/fork! {:keep #{w hold}
                           :child (fn []
                                    (lib/c-setpgid 0 0)
                                    (lib/fork! {:keep #{w hold} :child (fn [] (sock/write-line! w (str (lib/c-getpid))) (park nil))
                                                :refused park
                                                :parent (fn [_] (lib/end-with! anchor false) (park nil))}))
                           :refused (fn [n] {:refused n}) :parent identity})
        member (parse-long (first-line r))
        exit (jolt.io-poller/exit-chan member)]
    (lib/c-kill anchor 9)
    (is (= [{:signal 9} {:signal 9}] [(lib/wait-status anchor) (lib/wait-status leader)]))
    (is (= :exited (do (clojure.core.async/<!! exit) :exited)) "the member ends with its group")
    (run! lib/c-close [r w])))

(deftest ^{:seon.test/estimate {:ticks 20000000}} a-process-forks-beside-its-watch
  ;; task seon.lib/a-child-ends-with-its-starter (docs/failure-points.md#physics-who-may-run-threads):
  ;; the watcher is a C thread Chez never made, so forks beside it each run their child. A forked process arms its own watch
  ;; (never this runner's) and forks 20 times, ~1M ticks each, and says what the children's exits were
  (let [[r w] (sock/socketpair)
        pid (lib/fork! {:fd w :refused (fn [n] {:refused n}) :parent identity
                        :child (fn [] (lib/end-with! (lib/c-getpid) false)
                                 (sock/write-line! w (pr-str (frequencies (for [_ (range 20)]
                                                                           (lib/fork! {:child (fn [] :ran) :refused (fn [n] {:refused n}) :parent lib/wait-status}))))))})]
    (is (= {{:exit 0} 20} (edn/read-string (first-line r))))
    (is (= {:exit 0} (lib/wait-status pid)))
    (lib/c-close r)))

(deftest ^{:seon.test/estimate {:ticks 20000000}} the-memory-gauge-reads-what-macos-charges-across-a-fork
  ;; task seon.lib/real-mb-reads-what-macos-charges: the gauge is the task's phys_footprint, what
  ;; macOS charges (Activity Monitor, jetsam). It counted a process's private and shared-now-private
  ;; resident pages, and a fork turns the parent's pages copy-on-write: a parent holding 730 MB read
  ;; 76 MB after forking a child (measured 2026-10-23), so root, which forks the fork server, read a
  ;; quarter of what it holds. A forked child is charged what it writes, not what it shares.
  ;; In this process, a 16 MB block (one bytevector Chez keeps in place) and a 2 MB write in the child: the block's pages are
  ;; the same copy-on-write pages at any size. Every reading window holds only page writes (no allocation), after a full
  ;; collection. The estimate: 16 MB of page writes ~4M ticks, one fork and its 128 page copies ~1M, a collection of this
  ;; process's heap ~10M
  ;; One block of the same size is made and dropped first: a process's first block that size also grows the collector's own
  ;; bookkeeping (~1.4 MB, once a process: 17.66 MB charged the first time, 16.22 every time after, 2026-11-16), and the
  ;; claim is what a block written is charged, not that one-time growth
  (let [mb (* 1024 1024)
        src (byte-array mb)
        _ (.nextBytes (java.util.Random. 42) src)   ; random bytes: macOS compresses a repeated one under pressure
        _ (alength (byte-array (* 16 mb)))
        _ (jolt.host/gc-full!)
        before (lib/real-mb)
        block (byte-array (* 16 mb))]
    (dotimes [i 16] (System/arraycopy src 0 block (* i mb) mb))
    (let [held (lib/real-mb)
          [r w] (sock/socketpair)
          ;; the child writes one byte of each of 128 pages (2 MB): each is copied, and charged to it alone
          pid (lib/fork! {:keep #{w} :refused (fn [n] {:refused n}) :parent identity
                          :child (fn [] (let [b0 (lib/real-mb)]
                                          (dotimes [p 128] (aset block (* p 16384) (byte 1)))
                                          (sock/write-line! w (pr-str (- (lib/real-mb) b0)))))})
          charged (edn/read-string (first-line r))
          st (lib/wait-status pid)
          after (lib/real-mb)]
      (is (< 15 (- held before) 17.5) (str "a 16 MB block written is charged 16 MB: " (- held before)))
      (is (= {:exit 0} st))
      (is (< 1.9 charged 2.6) (str "a forked child is charged the 2 MB it writes: " charged))
      (is (< (* 0.95 held) after) (str "the parent still holds its block after the fork: " held " then " after))
      (is (= (aget src 0) (aget block 0))"the child's writes were its own")
      (run! lib/c-close [r w])))
  ;; the reading child (task seon.lib/scale-charges-what-the-machine-lost): a parent holds 8 MB it wrote; a child that only
  ;; reads every page is charged what it touches, as the one that writes them is, though only the writer takes memory from the
  ;; machine. That second half is the machine's free plus inactive pages (lib/sysmem), which every process on the machine
  ;; moves, so it is read, never judged: only the ledger's charge (a count of the child's own pages) and a reading that came
  ;; back are. The window allocates nothing and the parent collects before each fork
  (let [mb 8
        block (byte-array (* mb 1024 1024))
        _ (java.util.Arrays/fill block (byte 7))
        touch (fn [write?]
                (let [[r w] (sock/socketpair)
                      _ (jolt.host/gc-full!)
                      pid (lib/fork! {:keep #{w} :refused (fn [n] {:refused n}) :parent identity
                                      :child (fn []
                                               (let [f0 (lib/real-mb) m0 (:available-mb (lib/sysmem)) sum (volatile! 0)]
                                                 (dotimes [p (* mb 64)]
                                                   (let [i (* p 16384)] (if write? (aset block i (byte 1)) (vswap! sum + (aget block i)))))
                                                 (sock/write-line! w (pr-str {:charged (- (lib/real-mb) f0)
                                                                              :machine-drop (- m0 (:available-mb (lib/sysmem)))}))))})
                      line (first-line r)
                      st (lib/wait-status pid)]
                  (run! lib/c-close [r w])
                  (assoc (edn/read-string line) :exit st)))
        {:keys [read write] :as r} {:read (touch false) :write (touch true)}]
    (is (and (< 7.9 (:charged read) 9.5) (< 7.9 (:charged write) 9.5)) (str "both children are charged what they touch: " (pr-str r)))
    (is (and (= {:exit 0} (:exit read) (:exit write)) (number? (:machine-drop read)) (number? (:machine-drop write)))
        (str "both children ran and read the machine's memory: " (pr-str r)))))

(deftest a-forked-child-runs-go-blocks-and-makes-a-store
  ;; fork(2) copies only the calling thread, so the child inherits the runtime's bookkeeping and
  ;; none of its threads or waits: jolt.host/after-fork-child! drops them, or every go parked
  ;; forever (the step E dry run's design agent hung making a test store)
  (let [r (fork-case "go-blocks")]
    (is (= 41 (:parent r)) (str "the parent's go blocks run before it forks: " (pr-str r)))
    (is (= {:exit 0} (:go r)) "a go block that parks is resumed in the child")
    (is (= {:exit 0} (:timeout r)) "a timeout fires in the child")
    (is (= {:exit 0} (:store r)) "an in-memory Datahike store is made and written in the child")))

(deftest a-forked-child-is-reset-before-anything-can-preempt-it
  ;; docs/failure-points.md#physics-who-may-run-threads: fork(2) copies the parent's fibers and its carrier's run queue, and the
  ;; child drops them (jolt.host/after-fork-child!). Nothing may run between the fork and that reset: with interrupts enabled
  ;; first, a preemption there switched the child to the scheduler over the parent's queue, which ran a parent's ready fiber in
  ;; the child (exit 7 here), or, once the reset had marked them, died of "fiber in unexpected state forked" (bin/check scale,
  ;; 2026-11-10: an agent exited 1 with nothing on its stderr). Forced: the smallest quantum, ready fibers queued behind each fork
  ;; (seon.check/fork-cases "fork-reset"). The forks are made in a fresh process of the binary, on its main carrier (its fibers on
  ;; its one thread, as a root's boot and an agent do): forked from a copy of a shard (a 2.9 GB footprint, 4,000 regions) the
  ;; same 31 forks took 18-164 s against ~1 s alone (the batch's proves, 2026-11-10)
  (let [{:keys [forks doubles] :as r} (fork-case "fork-reset")]
    (is (= {{:exit 0} 10} forks)
        (str "every child of 10 reset itself and ran only its own code (exit 7: one ran a parent's fiber; before the fix all of them, 50 of 50 when it forked 50, did): " (pr-str r)))
    (is (= {"own" 10} doubles) (str "each of 10 grandchildren of a double fork ran its own code and nothing of the parent's: " (pr-str r)))))


(deftest ^{:seon.test/estimate {:ticks 3000000}} a-forked-child-holds-only-what-it-is-given
  ;; docs/processes.md#the-agent-process: a forked child closes every descriptor but 0-2 and what it is given (:fd, :keep), once, in
  ;; lib/fork! (ProcessBuilder's rule). An agent ran two test runs at once (the test service's items): one run's child kept the
  ;; other's pipe end, opened between that run's socketpair and its close, so the other's end of file never came and it answered
  ;; a :pipe-held error (bin/check agent-checks, 2026-11-12). Red before: the child held the other pair too
  (let [[r w] (sock/socketpair)
        [xr xw] (sock/socketpair)
        [kr kw] (sock/socketpair)
        open (fn [] (set (keep parse-long (.list (java.io.File. "/dev/fd")))))
        pid (lib/fork! {:fd w :keep #{kw} :refused (fn [n] {:refused n}) :parent identity
                        :child (fn [] (sock/write-line! w (pr-str (vec (sort (filter #(< 2 %) (open)))))))})]
    (try
      (let [rd (sock/line-reader r (fn [] (clojure.core.async/<!! (jolt.io-poller/ready-chan r :read))))
            line (loop [] (let [ls (rd)] (cond (= ls :eof) nil (seq ls) (first ls) :else (recur))))
            held (set (edn/read-string line))]
        (is (= {:exit 0} (lib/wait-status pid)))
        (is (contains? held w) "its own :fd")
        (is (contains? held kw) "and what it was given to keep")
        (is (not-any? held [r xr xw kr]) (str "and none of its parent's other descriptors: held " (pr-str held))))
      (finally (run! lib/c-close [r xr xw kr kw])))))

(deftest ^{:seon.test/estimate {:ticks 3000000} :seon.test/execs ["sh"]} a-shell-call-that-cannot-read-its-output-is-a-typed-error-naming-the-command
  ;; a shell call answers its whole output, or a typed error naming the command, never an empty answer; the read failure is
  ;; raised as Jolt's pipe port raises it since a5a3a89d (a Scheme error naming the errno). See docs/failure-points.md
  (is (= {:exit 0 :out "late\n" :err ""} (lib/sh "sh" "-c" "read go; echo late" :in "go\n")) "a child that writes after a wait answers its text")
  (let [e (with-redefs [clojure.java.shell/sh (fn [& _] (scm/eval-string "(error 'read \"failed: errno 9\")"))]
            (try (lib/sh "git" "status" "--porcelain=v2" :dir "/tmp") nil (catch Throwable e e)))
        d (ex-data e)]
    (is (= :seon.lib/shell-unanswered (:seon.error/type d)) (str "typed, not " (pr-str (type e)) " " (ex-message e)))
    (is (= ["git" "status" "--porcelain=v2"] (:seon.lib/command d)) "names the command, its options apart")
    (is (str/includes? (str (:seon.error/message d)) "errno 9") "says why: the read's own words")))

;; Estimate: the parent's 200,000 strings (~20 MB) made and collected twice (~0.5 ticks a byte: ~20M), one fork, the child's
;; 200,000 strings of its own and two collections of only what it allocated (~10M), and the parent's wait for its answer
(deftest ^{:seon.test/estimate {:ticks 60000000}} a-forked-child-never-collects-what-it-inherited
  ;; task jolt.host/a-forked-child-never-collects-what-it-inherited: the parent leaves garbage in its oldest generation and
  ;; forks; the child's collections leave that generation's bytes exactly as inherited (its pages stay shared), and it
  ;; collects one generation less than its parent. Red on Jolt before 054395e7: the child freed 27.3 MB of what it inherited.
  (let [gen-bytes (fn [g] (jolt.scheme/eval-string (str "(bytes-allocated " g ")")))
        junk (atom (vec (repeatedly 200000 #(str "inherited-" (rand)))))
        _ (System/gc) _ (System/gc)
        top (jolt.host/collect-ceiling)
        _ (reset! junk nil)
        out (str (System/getProperty "java.io.tmpdir") "/inherit-" (System/nanoTime))
        r (lib/fork! {:child (fn [] (let [before (gen-bytes top)
                                          _ (dotimes [_ 20] (count (vec (repeatedly 10000 #(str "child-" (rand))))))
                                          _ (System/gc) _ (System/gc)
                                          after (gen-bytes top)]
                                      (spit out (pr-str {:ceiling (jolt.host/collect-ceiling) :before before :after after}))))
                      :parent (fn [pid] (lib/wait-status pid) (try (read-string (slurp out)) (finally (.delete (java.io.File. out)))))
                      :refused (fn [m] m)})]
    (is (= (dec top) (:ceiling r)) (pr-str r))
    (is (pos? (:before r)) "the parent's oldest generation holds its garbage at the fork")
    (is (= (:before r) (:after r)) (str "the child's collections left what it inherited as it was: " (pr-str r)))))
