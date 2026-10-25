;; Can a process that runs threads (Jolt fibers, a core.async thread, a Flow graph) still fork
;; children that collect and run go blocks? Each form is one :eval request to a fresh agent of
;; this worktree's root (bin/ask), 2026-10-25, notes/design-agent-loop-2026-10-25.md section 8. Form 2 ran as its first four defs (the Flow graph was not started); forms 5-7 below are the runs the note measured.
(seon.root/spawn! sys {:branch :fork-lab1})                        ; in root

;; 1. the baseline: the sweeper pool, the active Chez threads, collections so far
{:sweepers (seon.lib/gc-sweepers! -1) :active (seon.lib/active-threads) :gc (seon.lib/gc-stats)}

;; 2. threads: a parked go block (a fiber carrier), an OS thread blocked in <!!, and a Flow graph
(do (require '[clojure.core.async :as a] '[clojure.core.async.flow :as flow])
    (def c1 (a/chan)) (def c2 (a/chan))
    (def g1 (a/go (a/<! c1)))
    (def t1 (a/thread (a/<!! c2)))
    (defn counter ([] {:ins {:in "n"} :outs {:out "n+"}}) ([_] {:n 0}) ([s _] s)
      ([s _ m] [(update s :n inc) {:out [(inc m)]}]))
    (def fl (flow/create-flow {:procs {:counter {:proc (flow/process #'counter)}}}))
    (def started (flow/start fl)) (flow/resume fl)
    (dotimes [i 100] (a/>!! (:in (:chans started) [:counter :in] (a/chan)) i))
    {:sweepers (seon.lib/gc-sweepers! -1) :active (seon.lib/active-threads)})

;; 3. collections beside the waiting threads: does any sweeper start?
(do (dotimes [i 200] (vec (range 10000)))
    (jolt.scheme/eval-string "(collect 4 4)")
    {:sweepers (seon.lib/gc-sweepers! -1) :active (seon.lib/active-threads) :gc (seon.lib/gc-stats)})

;; 4. fork a child that allocates, collects fully, runs a go block and writes a file
(seon.lib/fork! {:child (fn [] (dotimes [i 200] (vec (range 10000)))
                          (jolt.scheme/eval-string "(collect 4 4)")
                          (let [v (a/<!! (a/go (+ 1 2)))]
                            (spit "/tmp/claude-501/fork-lab-child.edn" (pr-str {:go v :gc (seon.lib/gc-stats) :sweepers (seon.lib/gc-sweepers! -1)}))))
                 :refused (fn [n] {:refused n}) :parent (fn [pid] {:pid pid :status (seon.lib/wait-status pid)})})

;; 5. a busy flow: 8 go blocks on one channel, then 200 forks (measured: 199 refused, 1 child deadlocked)
(do (def busy (a/chan 16)) (def run? (atom true))
    (dotimes [k 4] (a/go (loop [] (when @run? (a/>! busy k) (recur)))))
    (dotimes [k 4] (a/go (loop [] (when @run? (a/<! busy) (recur)))))
    (Thread/sleep 50)
    (let [rs (vec (for [i (range 200)]
                    (seon.lib/fork! {:child (fn [] (a/<!! (a/go 1)) (a/>!! busy 99) (jolt.scheme/eval-string "(collect 4 4)"))
                                     :refused (fn [n] [:refused n]) :parent (fn [pid] [:pid pid])})))]
      (reset! run? false)
      (spit "/tmp/claude-501/fork-lab-pids.txt" (clojure.string/join " " (keep #(when (= :pid (first %)) (second %)) rs)))
      (frequencies (map first rs))))

;; 6. the same flow busy, forks retried until 50 go through (measured: 813 refused; 50 of 50 children deadlocked in S_mutex_acquire)
(do (reset! run? true)
    (dotimes [k 4] (a/go (loop [] (when @run? (a/>! busy k) (recur)))))
    (dotimes [k 4] (a/go (loop [] (when @run? (a/<! busy) (recur)))))
    (Thread/sleep 50)
    (let [rs (loop [acc [] tries 0]
               (if (or (= 50 (count (filter #(= :pid (first %)) acc))) (> tries 200000)) acc
                 (recur (conj acc (seon.lib/fork! {:child (fn [] (a/poll! busy) (a/offer! busy 99) (a/<!! (a/go 1)) (jolt.scheme/eval-string "(collect 4 4)"))
                                                    :refused (fn [n] [:refused n]) :parent (fn [pid] [:pid pid])})) (inc tries))))]
      (reset! run? false)
      (spit "/tmp/claude-501/fork-lab-pids.txt" (clojure.string/join " " (keep #(when (= :pid (first %)) (second %)) rs)))
      (frequencies (map first rs))))

;; 7. the control, the flow quiet: 50 forks (measured: 50 of 50 exit 0)
(do (Thread/sleep 100)
    (let [rs (vec (for [i (range 50)]
                    (seon.lib/fork! {:child (fn [] (a/poll! busy) (a/offer! busy 99) (a/<!! (a/go 1)) (jolt.scheme/eval-string "(collect 4 4)"))
                                     :refused (fn [n] [:refused n]) :parent (fn [pid] [:pid pid (seon.lib/wait-status pid)])})))]
      {:outcomes (frequencies (map (juxt first last) rs)) :active (seon.lib/active-threads)}))
