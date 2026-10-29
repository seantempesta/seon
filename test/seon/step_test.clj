(ns seon.step-test
  "seon.step's helpers every proc and edge shares: an edge asks a proc one way (ask-edge)."
  (:require [clojure.test :refer [deftest is testing]] [clojure.core.async :as a] [seon.step :as step]))

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
