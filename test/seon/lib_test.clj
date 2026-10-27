(ns seon.lib-test
  "The native probes (native/probe.c through seon.lib): what they answer."
  (:require [clojure.test :refer [deftest is]] [seon.lib :as lib] [seon.sock :as sock] [jolt.scheme :as scm]
            [seon.test-store :as ts] [clojure.edn :as edn] [clojure.string :as str]))

(deftest the-memory-probe-answers-what-the-process-holds
  ;; lib/mp-probe reads the kernel's ledger (one task_info call): this process's own memory in MB,
  ;; what it holds resident at most, a little more. (It once walked every page of each mapped
  ;; region, so a 64 GiB LMDB map made one probe take ~1.25 s; the work it does is the kernel's
  ;; ledger now, whatever the process maps.)
  (let [p (lib/mp-probe)]
    (is (< 1.0 (lib/real-mb p) (+ (:resident p) (:compressed p) 1.0)) (pr-str p))))

(deftest a-fork-beside-a-running-thread-is-refused-and-says-how-many-run
  ;; lib/fork! is the one spawning skeleton: a second active Chez thread breaks a forked
  ;; child (E11/E13), so it forks nothing and answers its refused branch with the count
  (let [stop (promise)
        spin (doto (Thread. #(loop [] (when-not (realized? stop) (recur)))) (.start))
        r (lib/fork! {:child #(lib/c-exit 3) :parent (fn [pid] [:forked pid]) :refused (fn [n] [:refused n])})]
    (deliver stop true)
    (.join spin)
    (is (= :refused (first r)) (str "answered " r))
    (is (<= 2 (second r)) (str "active threads " (second r)))))

(def ^:private park-in-a-read
  ;; a raw Chez thread (no Jolt lock taken to start it) blocked in a collect-safe read of fd
  (scm/eval-string
    "(let ([rd (foreign-procedure __collect_safe \"read\" (int u8* size_t) ssize_t)])
       (lambda (fd) (fork-thread (lambda () (rd fd (make-bytevector 1) 1))) #t))"))

(deftest a-fork-beside-a-thread-parked-in-a-read-forks
  ;; a thread in a collect-safe call (a read) runs no Scheme, so a fork beside it is a fork beside a
  ;; parked thread. Our Chez counts it in $active-threads until it is converted, and fork! read the
  ;; count bare: it refused every time (Jolt's forkheldlocks waited for good). The thread may not be
  ;; in its read yet at the first try, so the fork is tried again, at most 100000 times: refused that
  ;; often in a row is red (docs/forks.md, jolt/a-thread-in-c-is-not-active)
  (let [[r w] (sock/socketpair)
        _ (park-in-a-read r)
        answer (loop [n 0]
                 (let [a (lib/fork! {:child #(lib/c-exit 0) :parent (fn [pid] [:forked pid]) :refused (fn [k] [:refused k])})]
                   (if (and (= :refused (first a)) (< n 100000)) (recur (inc n)) a)))]
    (sock/write-line! w "go")
    (is (= :forked (first answer)) (str "answered " answer))
    (when (= :forked (first answer))
      (is (= {:exit 0} (lib/wait-status (second answer)))))
    (lib/c-close r) (lib/c-close w)))

(defn- fork-case
  "Fork case k's answer (seon.check/fork-cases), in a fresh process of the built binary
  (target/seon forks k): a process forks from its main carrier, where it is the one thread,
  never from this runner's, whose runtime threads would race the fork."
  [k]
  (let [{:keys [exit out err]} (ts/binary-sh "./seon" "forks" k :dir "target")]
    (if (zero? exit) (edn/read-string (last (str/split-lines out))) {:exit exit :err err})))

(deftest a-forked-child-that-throws-exits-and-never-returns-into-its-parent
  ;; the child holds a copy of every frame its parent forked from: a throw out of (child) that
  ;; unwound out of lib/fork! ran the parent's code in the child (in an agent's test child, the
  ;; agent's own serve! loop, which died as the agent, exit 70, or went on reading root's socket
  ;; as a second agent). The child ends at fork!, exit 1, saying what threw; one that returns
  ;; exits 0 (seon.check/fork-cases "child-throws")
  (is (= [{:exit 1} {:exit 0}] (fork-case "child-throws"))))

(deftest a-forked-child-runs-none-of-its-parents-shutdown-hooks
  ;; task seon.lib/a-fork-carries-no-shutdown-hook: Jolt's hooks are the process's list, and a
  ;; fork copied it; root's hook (its whole stop: seon.main/root-serve) ran in an agent or a test
  ;; child that left by System/exit, an uncaught throw or a signal, on root's copied state
  ;; against the shared store (seon.check/fork-cases "child-hooks")
  (is (= {:status {:exit 3} :parents-hook-ran-in nil} (fork-case "child-hooks"))))

(deftest a-wait-for-a-line-parks-the-fiber
  ;; an agent waits for its test child's lines for the whole test run (seon.agent/run-tests-forked):
  ;; on the main carrier (the agent's one thread) a poll(2) of the pipe held every other fiber
  ;; of the agent for that long. The wait parks through the io poller: a fiber queued before the
  ;; wait runs while the reader waits (the carrier is free only then), so it ran before the line
  ;; came. A poll(2) holding the carrier lets it run only after the read returns: false. The
  ;; line comes from a thread (no fiber could write it while the carrier is held), 200 ms on, the
  ;; end of a held carrier's wait; the verdict is which happened first, never how long it took.
  ;; In a child forked from a main carrier, the reader on the child's own fiber
  (is (= {:exit 0} (fork-case "line-parks")) "a fiber ran while the reader waited"))

(deftest the-memory-gauge-reads-what-macos-charges-across-a-fork
  ;; task seon.lib/real-mb-reads-what-macos-charges: the gauge is the task's phys_footprint, what
  ;; macOS charges (Activity Monitor, jetsam). It counted a process's private and shared-now-private
  ;; resident pages, and a fork turns the parent's pages copy-on-write: a parent holding 730 MB read
  ;; 76 MB after forking a child (measured 2026-10-23), so root, which forks the fork server, read a
  ;; quarter of what it holds. A forked child is charged what it writes, not what it shares.
  (let [{:keys [before held child after] :as r} (fork-case "memory-gauge")]
    (is (and before held (< 285 (- held before) 330)) (str "a 300 MB block written: " (pr-str r)))
    (is (= {:exit 0} child) "a forked child is charged the 30 MB it writes")
    (is (and after (< (* 0.95 held) after)) (str "the parent still holds its block after the fork: " (pr-str r)))))

(deftest a-forked-child-runs-go-blocks-and-makes-a-store
  ;; fork(2) copies only the calling thread, so the child inherits the runtime's bookkeeping and
  ;; none of its threads or waits: jolt.host/after-fork-child! drops them, or every go parked
  ;; forever (the step E dry run's design agent hung making a test store)
  (let [r (fork-case "go-blocks")]
    (is (= 41 (:parent r)) (str "the parent's go blocks run before it forks: " (pr-str r)))
    (is (= {:exit 0} (:go r)) "a go block that parks is resumed in the child")
    (is (= {:exit 0} (:timeout r)) "a timeout fires in the child")
    (is (= {:exit 0} (:store r)) "an in-memory Datahike store is made and written in the child")))
