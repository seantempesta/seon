;; The runs of section 8's prototype, each one :eval request to a fresh agent of this worktree's root
;; (bin/ask 8140), after (load-file "notes/agent-loop/main-carrier.clj") (lab.main-carrier/mode!) and requiring
;; clojure.core.async :as a, clojure.core.async.flow :as flow, jolt.async.remote :as remote. 2026-10-25.

;; ---- m2
(do (require '[jolt.async.remote :as remote])
    (def mc-echo
      (fn [fd]
        (lab.main-carrier/mode!)
        (let [e (remote/open fd {:pump :fiber}) done (volatile! false)]
          (a/go (loop [] (if-some [v (a/<! (:in e))] (do (a/>! (:out e) (inc v)) (recur)) (do (a/close! (:out e)) (vreset! done true)))))
          (lab.main-carrier/run-until #(deref done))
          (spit "/private/tmp/claude-501/mc-child.edn" (pr-str {:active (seon.lib/active-threads) :pid (seon.lib/c-getpid)})))))
    (def mc-round
      (fn [n]
        (let [[fa fb] (remote/socketpair)
              child (seon.lib/fork! {:child (fn [] (remote/close-fd! fa) (mc-echo fb)) :refused (fn [k] (throw (ex-info "refused" {:n k}))) :parent identity})
              _ (remote/close-fd! fb)
              e (remote/open fa {:pump :fiber})
              sum (volatile! 0) got (volatile! 0)
              t0 (System/nanoTime)]
          (a/go (dotimes [i n] (a/>! (:out e) i)) (a/close! (:out e)))
          (a/go (loop [] (when-some [v (a/<! (:in e))] (vswap! sum + v) (vswap! got inc) (recur))))
          (let [rounds (lab.main-carrier/run-until #(= n @got))
                ms (/ (- (System/nanoTime) t0) 1e6)]
            {:n n :sum @sum :want (reduce + (map inc (range n))) :ms ms :per-s (long (/ n (/ ms 1000))) :io-rounds rounds
             :child-exit (seon.lib/wait-status child) :active (seon.lib/active-threads)}))))
    (mc-round 20000))

;; ---- m3
(let [n 100000
      [fa fb] (remote/socketpair)
      child (seon.lib/fork! {:child (fn [] (remote/close-fd! fa) (mc-echo fb)) :refused (fn [k] (throw (ex-info "refused" {:n k}))) :parent identity})
      _ (remote/close-fd! fb)
      e (remote/open fa {:pump :fiber})
      sum (volatile! 0) got (volatile! 0)]
  (a/go (dotimes [i n] (a/>! (:out e) i)) (a/close! (:out e)))
  (a/go (loop [] (when-some [v (a/<! (:in e))] (vswap! sum + v) (vswap! got inc) (recur))))
  (let [forks (vec (for [k (range 50)]
                     (do (lab.main-carrier/run-until #(>= @got (* (inc k) 1000)))
                         (seon.lib/fork! {:child (fn [] (lab.main-carrier/mode!)
                                                   (let [c (a/chan)] (a/go (a/>! c 1)) (lab.main-carrier/take!! c))
                                                   (jolt.scheme/eval-string "(collect 4 4)"))
                                          :refused (fn [k] [:refused k])
                                          :parent (fn [pid] (seon.lib/wait-status pid))}))))
        _ (lab.main-carrier/run-until #(= n @got))]
    {:forks (frequencies forks) :got-at-last-fork 50000 :sum-ok (= @sum (reduce + (map inc (range n))))
     :echo-child (seon.lib/wait-status child) :active (seon.lib/active-threads)}))

;; ---- m7
(do (def seen (volatile! [0 0]))
    (defn incr ([] {:ins {:in "n"} :outs {:out "n+1"} :workload :io}) ([_] {}) ([s _] s) ([s _ m] [s {:out [(inc m)]}]))
    (defn sink2 ([] {:ins {:in "n"} :workload :io}) ([_] {}) ([s _] s) ([s _ m] (vswap! seen (fn [[n t]] [(inc n) (+ t m)])) [s nil]))
    (def fl2 (flow/create-flow {:procs {:a {:proc (flow/process #'incr)} :b {:proc (flow/process #'incr)} :s {:proc (flow/process #'sink2)}}
                                :conns [[[:a :out] [:b :in]] [[:b :out] [:s :in]]]}))
    (def stage (volatile! :none))
    (jolt.fibers/spawn (fn [] (vreset! stage :starting) (flow/start fl2) (vreset! stage :started) (flow/resume fl2) (vreset! stage :resumed)))
    (let [r (lab.main-carrier/run-until #(= :resumed @stage))] {:r r :stage @stage :active (seon.lib/active-threads)}))

;; ---- m9
(let [n 20000 _ (vreset! seen [0 0]) t0 (System/nanoTime)]
  (flow/inject fl2 [:a :in] (range n))
  (let [r (lab.main-carrier/run-until #(= n (first @seen))) ms (/ (- (System/nanoTime) t0) 1e6)]
    {:r r :seen @seen :want [n (reduce + (map #(+ 2 %) (range n)))] :ms ms :per-s (long (/ n (/ ms 1000))) :active (seon.lib/active-threads)}))

;; ---- m10
(let [n 100000
      [fa fb] (remote/socketpair)
      child (seon.lib/fork! {:child (fn [] (remote/close-fd! fa)
                                      (lab.main-carrier/mode!)
                                      (let [e (remote/open fb {:pump :fiber}) done (volatile! false)]
                                        (Thread/sleep 1000)
                                        (a/go (loop [] (if-some [v (a/<! (:in e))] (do (a/>! (:out e) (inc v)) (recur)) (do (a/close! (:out e)) (vreset! done true)))))
                                        (lab.main-carrier/run-until #(deref done))))
                             :refused (fn [k] (throw (ex-info "refused" {:n k}))) :parent identity})
      _ (remote/close-fd! fb)
      e (remote/open fa {:pump :fiber})
      sent (volatile! 0) got (volatile! 0) sum (volatile! 0)]
  (a/go (dotimes [i n] (a/>! (:out e) i) (vswap! sent inc)) (a/close! (:out e)))
  (a/go (loop [] (when-some [v (a/<! (:in e))] (vswap! sum + v) (vswap! got inc) (recur))))
  (lab.main-carrier/run-until #(pos? @got))
  (let [sent-while-blocked @sent]
    (lab.main-carrier/run-until #(= n @got))
    {:sent-while-child-blocked sent-while-blocked :got @got :sum-ok (= @sum (reduce + (map inc (range n))))
     :child (seon.lib/wait-status child) :active (seon.lib/active-threads)}))
