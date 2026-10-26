(ns seon.lib-test
  "The native probes (native/probe.c through seon.lib): what they answer and what they cost."
  (:require [seon.profile :as profile] [clojure.test :refer [deftest is testing]] [clojure.java.shell] [seon.lib :as lib]
            [konserve.jolt.lmdb-ffi :as lmdb] [clojure.core.async :as a] [datahike.api :as d] [seon.sock :as sock]))

(defn- probe-ms
  "The median time of 9 calls of lib/mp-probe, ms."
  []
  (let [ts (sort (for [_ (range 9)] (let [t0 (profile/now-ns)] (lib/mp-probe) (profile/ms-since t0))))]
    (nth ts 4)))

(deftest the-memory-probe-costs-what-the-process-holds-not-what-it-maps
  ;; lib/mp-probe once asked the kernel for each region's extended info, which walks every page
  ;; of the region: an agent maps the store's LMDB file twice at :seon.config.store/lmdb-map-bytes
  ;; (64 GiB), so one probe took ~1.25 s and 100 agents probing at their start serialized on the
  ;; file's object (session spawn-regression, 2026-10-22). It reads the kernel's ledger now (one
  ;; task_info call), and its cost must not grow with what the process maps: a probe with a
  ;; 64 GiB map open costs about what it costs with a 1 GiB map. Two LMDB envs on disk of this test's own,
  ;; the last resort: what is proved is a mapping's size; removed at the end
  (let [dir (str (System/getProperty "user.dir") "/target/lib-test-" (random-uuid))
        _ (doseq [d ["-small" "-large"]] (.mkdirs (java.io.File. (str dir d))))
        small (lmdb/open-env (str dir "-small") {:map-size (* 1 1024 1024 1024)})
        t-small (probe-ms)
        large (lmdb/open-env (str dir "-large") {:map-size (* 64 1024 1024 1024)})
        t-large (probe-ms)]
    (try
      (is (< t-large (+ 1.0 (* 2 t-small))) (str "probe " t-small " ms with 1 GiB mapped, " t-large " ms with 64 GiB more"))
      (testing "and it answers this process's own memory, in MB: what it holds resident at most, a little more"
        (let [p (lib/mp-probe)]
          (is (< 1.0 (lib/real-mb p) (+ (:resident p) (:compressed p) 1.0)) (pr-str p))))
      (finally (lmdb/close-env small) (lmdb/close-env large)
               (clojure.java.shell/sh "rm" "-rf" (str dir "-small") (str dir "-large"))))))

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

(defn- in-child
  "f's answer in a child lib/fork! makes, as its exit status: 0 when (f) answers true
  within 5 s, 1 when it answers anything else, 2 when it has not answered (a future
  with a deadline, so a child whose runtime threads are gone ends instead of hanging)."
  [f]
  (lib/fork! {:child #(lib/c-exit (let [r (deref (future (f)) 5000 ::hung)] (cond (= ::hung r) 2 (true? r) 0 :else 1)))
              :refused (fn [n] {:refused n}) :parent lib/wait-status}))

(deftest a-wait-for-a-line-parks-the-fiber
  ;; an agent waits for its test child's lines for the whole test run (seon.agent/run-tests-forked):
  ;; on the main carrier (the agent's one thread) a poll(2) of the pipe held every other fiber
  ;; of the agent for that long. The wait parks through the io poller: a fiber queued before the
  ;; wait runs while the reader waits (the carrier is free only then), so it ran before the line
  ;; came. A poll(2) holding the carrier lets it run only after the read returns: false. The
  ;; line comes from a thread (no fiber could write it while the carrier is held), 200 ms on, the
  ;; end of a held carrier's wait; the verdict is which happened first, never how long it took
  (is (= {:exit 0}
         (in-child (fn []
                     (jolt.host/main-carrier-run!
                       (fn []
                         (let [[r w] (sock/socketpair)
                               ran (volatile! false)
                               _ (a/go (vreset! ran true))
                               _ (future (Thread/sleep 200) (sock/write-line! w "done"))
                               rd (sock/line-reader r)
                               lines (loop [] (let [ls (rd)] (if (seq ls) ls (recur))))]
                           (and (= ["done"] lines) @ran)))))))
      "a fiber ran while the reader waited"))

(deftest the-memory-gauge-reads-what-macos-charges-across-a-fork
  ;; task seon.lib/real-mb-reads-what-macos-charges: the gauge is the task's phys_footprint, what
  ;; macOS charges (Activity Monitor, jetsam). It counted a process's private and shared-now-private
  ;; resident pages, and a fork turns the parent's pages copy-on-write: a parent holding 730 MB read
  ;; 76 MB after forking a child (measured 2026-10-23), so root, which forks the fork server, read a
  ;; quarter of what it holds. A forked child is charged what it writes, not what it shares.
  ;; the largest block a test allocates (one process, no fan-out: AGENTS section 8); Chez keeps a
  ;; bytevector this large in place (a large object is never copied by a collection)
  (System/gc)
  (let [before (lib/real-mb)
        block (byte-array (* 300 1024 1024))]
    (java.util.Arrays/fill block (byte 7))
    (let [held (lib/real-mb)]
      (is (< 285 (- held before) 330) (str "a 300 MB block written: " before " -> " held " MB"))
      (is (= {:exit 0} (in-child (fn [] (let [b0 (lib/real-mb)]
                                          ;; one byte a 16 KB page: 30 MB of pages written, ~1,900 writes
                                          (doseq [i (range 0 (* 30 1024 1024) 16384)] (aset block i (byte 1)))
                                          (< 28 (- (lib/real-mb) b0) 60)))))
          "a forked child is charged the 30 MB it writes")
      (is (< (* 0.95 held) (lib/real-mb)) (str "the parent still holds its block after the fork: " held " MB before"))
      (aget block 0))))

(deftest a-forked-child-runs-go-blocks-and-makes-a-store
  ;; fork(2) copies only the calling thread: jolt's carriers, timer and shutdown watcher
  ;; are parked (not active) when the one active thread forks, so the child inherited a
  ;; started pool with no threads and every go parked forever (the step E dry run's
  ;; design agent hung making a test store, 2026-10-22). The child starts them afresh.
  (let [c (a/chan) r (a/go (a/<! c))] (a/>!! c 41) (is (= 41 (a/<!! r)) "the parent's carriers are up before it forks"))
  (a/<!! (a/timeout 1))
  (is (= {:exit 0} (in-child (fn [] (let [c (a/chan) r (a/go (a/<! c))] (a/>!! c 41) (= 41 (a/<!! r))))))
      "a go block that parks is resumed in the child")
  (is (= {:exit 0} (in-child (fn [] (= :t (a/alt!! (a/timeout 10) :t)))))
      "a timeout fires in the child")
  (is (= {:exit 0} (in-child (fn [] (let [cfg {:store {:backend :memory :id (random-uuid)}}]
                                      (d/create-database cfg)
                                      (let [c (d/connect cfg)] (d/transact c [{:db/ident :x}]) (d/release c) true)))))
      "an in-memory Datahike store is made and written in the child"))
