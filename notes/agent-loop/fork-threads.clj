;; Can a process that runs threads (Jolt fibers, a core.async thread, a Flow graph) still fork
;; children that collect and run go blocks? Each form is one :eval request to a fresh agent of
;; this worktree's root (bin/ask), 2026-10-25, notes/design-agent-loop-2026-10-25.md section 8. Forms 3-5 of the measured runs are in the note: a busy flow (8 go blocks on one channel), forks forced through, and the quiet control.
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
