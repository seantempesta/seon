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
