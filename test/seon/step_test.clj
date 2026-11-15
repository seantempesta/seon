(ns seon.step-test
  "seon.step's helpers every proc and edge shares: an edge asks a proc one way (ask-edge)."
  (:require [clojure.test :refer [deftest is testing]] [clojure.core.async :as a] [clojure.core.async.flow :as flow] [seon.step :as step]))

(deftest an-edge-asks-one-way
  ;; task seon.step/an-edge-asks-one-way: the request carries its promise, the proc delivers its
  ;; one answer to it, and a channel already closed answers the caller's closed value at once
  (testing "the proc's answer reaches the edge through the request's promise"
    (let [ch (a/chan 1)
          p (step/ask-edge ch {:op :page} :closed)
          m (a/<!! ch)]
      (is (= :page (:op m)) "the request as asked")
      (is (not (realized? p)) "unanswered until the proc answers")
      (deliver (:p m) {:html "ok"})
      (is (and (realized? p) (= {:html "ok"} @p)))))
  (testing "a closed channel answers at once: no request waits on a proc that will never take it"
    (let [ch (doto (a/chan 1) a/close!)]
      (let [p (step/ask-edge ch {:op :page} :closed)]
        (is (realized? p) "answered before ask-edge returns")
        (is (= :closed @p))))))

(defn- timer-threads
  "Threads alive after 25 and after 1000 pending timers, [[go25 go1000] [take25 take1000]]: the go form
  (step/put-after!) and the callback take! it replaced."
  []
  (let [count-threads #(count (Thread/getAllStackTraces))
        grow (fn [arm!] (let [c (a/chan 1) _ (arm! c 25) t25 (count-threads) _ (arm! c 975) t1000 (count-threads)] [t25 t1000]))]
    [(grow (fn [c n] (dotimes [_ n] (step/put-after! c 600000 :due))))
     (grow (fn [c n] (dotimes [_ n] (a/take! (a/timeout 600000) (fn [_] (a/put! c :due))))))]))

(deftest pending-timers-hold-no-threads
  ;; docs/clocks.md#time-decides-the-owners-exceptions (measured 2026-10-29: 21 threads at 25 and at
  ;; 1000 pending, both forms)
  (let [[[g25 g1000] [t25 t1000]] (timer-threads)]
    ;; a thread per pending take would add ~975; a lazily started runtime thread (the asleep detector)
    ;; may add one whenever it starts, so the claim is that threads do not grow with the timers
    (is (< (- g1000 g25) 3) (str "975 more pending timers added " (- g1000 g25) " threads"))
    (is (< (- t1000 t25) 3) "the callback form, measured with it: Jolt's pin forks no thread per pending take")))

;; docs/clocks.md#time-decides-the-owners-exceptions: a bound whose wait was answered disarms (seon.agent/await-reply), so the
;; timer never puts. Two timers on one channel, the earlier cancelled: the later's message is the first to come. Red under a
;; cancel! that does nothing (:early arrives). Estimate: two heap entries and one take, ~20k ticks; 60 ms of the later timer.
(deftest ^{:seon.test/estimate {:ticks 100000}} a-cancelled-timer-never-puts
  (let [c (a/chan 2)
        h (step/put-after! c 20 :early)]
    (step/put-after! c 60 :late)
    (step/cancel! h)
    (is (= :late (a/<!! c)) "the cancelled timer's message never came")
    (is (nil? (step/cancel! nil)) "a bound never armed has nothing to cancel")))

(defn- running-src
  "A step-fn whose output tags each number with its code's version and its count (the running-graph proof's source)."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:in "numbers"} :outs {:out "numbers, tagged"} :workload :io})
  ([_] {:n 0})
  ([st _] st)
  ([st _ m] [(update st :n inc) {:out [[:v1 m (:n st)]]}]))

(defn- running-sink
  "A step-fn putting each message, with its own count, on the channel its args name (the running-graph proof's sink)."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:in "tagged numbers"} :outs {} :workload :io})
  ([args] {:seen 0 :out (:out args)})
  ([st _] st)
  ([st _ m] (a/>!! (:out st) [m (inc (:seen st))]) [(update st :seen inc) nil]))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-running-graph-takes-new-code-and-new-procs-with-no-restart
  ;; docs/flow.md#changing-a-running-graph: a step-fn is held by its Var, so a redefinition is its next step's code, its
  ;; state kept; update! adds and rewires procs keeping every unchanged proc's loop, state and queue, and restarts only a
  ;; proc whose own definition changed. Seon relies on it: root adds each agent's proc live, an agent its test runs
  (let [out (a/chan 100) out2 (a/chan 100)
        cfg (fn [sink-out tap?] {:procs (cond-> {:src {:proc (flow/process #'running-src)}
                                                 :sink {:proc (flow/process #'running-sink) :args {:out sink-out}}}
                                          tap? (assoc :tap {:proc (flow/process #'running-sink) :args {:out out2}}))
                                 :conns (cond-> [[[:src :out] [:sink :in]]] tap? (conj [[:src :out] [:tap :in]]))})
        g (flow/create-flow (cfg out false))
        send! (fn [i] @(flow/inject g [:src :in] [i]))
        old (var-get #'running-src)]
    (try
      (flow/start g) (flow/resume g)
      (send! 1)
      (is (= [[:v1 1 0] 1] (a/<!! out)))
      (testing "a redefined step-fn is the next step's code, and the proc's state is kept"
        (alter-var-root #'running-src (constantly (fn ([] (old)) ([a] (old a)) ([st t] (old st t))
                                                    ([st _ m] [(update st :n inc) {:out [[:v2 m (:n st)]]}]))))
        (send! 2)
        (is (= [[:v2 2 1] 2] (a/<!! out)) "the new code, the source's count and the sink's count both carried on"))
      (testing "update! adds and rewires a proc: nothing stops, nothing is lost, kept procs keep their state"
        (let [r (flow/update! g (cfg out true))]
          (flow/resume g)
          (send! 3)
          (is (= #{} (:stopped r)) "no proc stopped")
          (is (= {} (:lost r)) "no message lost")
          (is (= [[:v2 3 2] 3] (a/<!! out)) "the kept sink's state carried on")
          (is (= [[:v2 3 2] 1] (a/<!! out2)) "the new proc took its first message")))
      (testing "a proc whose own definition changed restarts alone"
        (let [out3 (a/chan 100) r (flow/update! g (assoc-in (cfg out true) [:procs :sink :args :out] out3))]
          (flow/resume g)
          (send! 4)
          (is (= #{:sink} (:stopped r) (:started r)) "only the changed proc")
          (is (= [[:v2 4 3] 1] (a/<!! out3)) "it starts fresh; the source kept its count")
          (is (= [[:v2 4 3] 2] (a/<!! out2)) "the other kept proc kept its count")))
      (finally (alter-var-root #'running-src (constantly old)) (flow/stop g)))))
