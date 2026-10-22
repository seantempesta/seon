(ns seon.fx-admit-test
  "Session R3's admission over FX1's owner: the provider's rate as a token bucket, the clock's
  tick, AIMD on a rate-limit outcome, priority by chain progress. Plain step-fn calls."
  (:require [clojure.test :refer [deftest is testing]] [seon.fx :as fx]))

(defn- owner [workers rate] (fx/owner-proc {:kind :model :workers workers :rate rate :work nil :back nil}))
(defn- req [k est & {:as more}] (merge {:req {:k k} :est est :dest {:agent :a1 :op :fx-reply :k k}} more))
(defn- ran [o] (map #(get-in % [:req :k]) (:to-workers o)))

(deftest a-request-waits-on-the-bucket-and-a-tick-admits-it
  (let [[st o] (fx/owner-proc (owner 8 1000) :in (req 1 900))
        _ (is (= [1] (ran o)) "900 of 1000 tokens: admitted")
        [st o] (fx/owner-proc st :in (req 2 900))
        _ (is (empty? (ran o)) "100 tokens left: it waits, a worker free")
        st (assoc-in st [:bucket :at] (- (get-in st [:bucket :at]) 1000))
        [_ o] (fx/owner-proc st :tick {})]
    (is (= [2] (ran o)) "a second later the bucket refilled: the tick admits it")))

(deftest the-actual-cost-settles-the-estimate
  (let [[st _] (fx/owner-proc (owner 8 1000) :in (req 1 500 :budget-key :t1))
        [st _] (fx/owner-proc st :done {:dest {:agent :a1} :outcome {:value 1} :budget-key :t1 :cost 200 :est 500})]
    (is (= 200 (get-in st [:spent :t1])) "the task is charged what the call cost")
    (is (< 799 (get-in st [:bucket :tokens]) 1001) "the bucket got back the 300 it over-reserved")))

(deftest a-rate-limit-halves-the-rate-and-successes-raise-it-back
  (let [[st _] (fx/owner-proc (owner 8 1000) :in (req 1 10))
        r0 (get-in st [:bucket :per-ms])
        [st _] (fx/owner-proc st :done {:dest {:agent :a1} :outcome {:seon.error/type :seon.model/rate-limited} :est 10 :cost 0})
        r1 (get-in st [:bucket :per-ms])
        [st _] (fx/owner-proc st :in (req 2 10))
        [st _] (fx/owner-proc st :done {:dest {:agent :a1} :outcome {:value 1} :est 10 :cost 10})]
    (is (= (/ r0 2) r1) "a 429 halves the admitted rate")
    (is (< r1 (get-in st [:bucket :per-ms]) (inc r0)) "a success raises it by a twentieth, never past the configured rate")))

(deftest a-later-step-of-a-chain-runs-first
  (let [[st _] (fx/owner-proc (owner 1 nil) :in (req 1 0))
        [st _] (fx/owner-proc st :in (req 2 0 :prio 0))
        [st _] (fx/owner-proc st :in (req 3 0 :prio 3))
        [_ o] (fx/owner-proc st :done {:dest {:agent :a1} :outcome {:value 1}})]
    (is (= [3] (ran o)) "prio is the task's depth in the needs DAG: finishing a chain beats starting one")))

(deftest a-rate-limited-model-call-waits-for-the-bucket-and-is-answered
  ;; task seon.guards/a-transient-model-failure-parks: a 429 is the provider saying "wait", so
  ;; the owner keeps the request (the worker hands it back as :request) until its bucket refills,
  ;; and the asker is answered only by the call that went through
  (let [r (req 1 10)
        dest (:dest r)
        answers (fn [o] (vec (for [x (:out o) :when (= dest (:dest x))] (:r x))))
        [st _] (fx/owner-proc (owner 8 1000) :in r)
        [st o] (fx/owner-proc st :done {:dest dest :request r :est 10 :cost 0
                                        :outcome {:seon.error/type :seon.model/rate-limited :seon.error/operation 'seon.model/complete
                                                  :seon.error/message "HTTP 429" :status 429}})]
    (is (= [] (answers o)) "the asker is not answered the 429")
    (is (= [:seon.model/rate-limited] (keep #(get-in % [:error :seon.error/type]) (:out o))) "each 429 is a recorded problem")
    (is (= [{:k 1}] (map :req (vals (:queue st)))) "the request waits in the owner's queue")
    (is (empty? (ran o)) "and is not sent again before the bucket refills")
    (let [st (assoc-in st [:bucket :at] (- (get-in st [:bucket :at]) 1000))
          [st o] (fx/owner-proc st :tick {})
          _ (is (= [1] (ran o)) "the refilled bucket's tick sends it again")
          [_ o] (fx/owner-proc st :done {:dest dest :request r :est 10 :cost 10 :outcome {:value 1}})]
      (is (= [{:value 1}] (answers o)) "the asker gets the call that went through"))))
