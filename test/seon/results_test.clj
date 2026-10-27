(ns seon.results-test
  "Results as facts on main (task seon.proofs/tests-are-facts, docs/merging.md#results-are-facts-on-main):
  what ran is a fact at its digest, flaky a fact, a run's function costs one value, a function's
  cost per call a fact written only when it moves, and the queries over them; on a branch of the
  one test store."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d] [seon.test-store :as ts] [seon.proofs :as proofs]))

(defn- fc [v calls ticks self bytes] {:var v :calls calls :ticks ticks :self-ticks self :bytes bytes :self-bytes bytes})

(defn- write-on [conn] (fn [tx] (d/transact conn {:tx-data tx})))

(defn- rec!
  ([conn ran] (rec! conn ran {} false))
  ([conn ran failures main?]
   (proofs/record! (write-on conn) @conn {:run {:id (str "run " (random-uuid)) :tree "t"} :ran ran :failures failures :main? main?})))

(deftest a-digest-judged-green-and-red-is-flaky-whichever-came-last
  (ts/test-branch!
   (fn [_ conn]
     (rec! conn {'a/t {:test 'a/t :digest "d-flip" :pass 1 :fail 0 :error 0}
                 'a/u {:test 'a/u :digest "d-green" :pass 1 :fail 0 :error 0}})
     (is (= {"d-flip" {:digest "d-flip" :pass 1 :fail 0 :error 0} "d-green" {:digest "d-green" :pass 1 :fail 0 :error 0}}
            (proofs/stored-results @conn ["d-flip" "d-green" "d-none"]))
         "a green is reused; a digest with no result is none")
     (rec! conn {'a/t {:test 'a/t :digest "d-flip" :pass 0 :fail 1 :error 0}} {'a/t [{:type :fail :message "boom" :expected "1" :actual "2"}]} false)
     (let [r (get (proofs/stored-results @conn ["d-flip"]) "d-flip")]
       (is (:flaky r) (pr-str r))
       (is (not (proofs/green? r)) "flaky is red, never reused as green")
       (is (some #(= "boom" (:message %)) (:failures r)) "with the failures its red run recorded"))
     (rec! conn {'a/t {:test 'a/t :digest "d-flip" :pass 1 :fail 0 :error 0}})
     (is (:flaky (get (proofs/stored-results @conn ["d-flip"]) "d-flip")) "a green after the red leaves it flaky"))))

(deftest a-red-is-reused-with-its-failures
  (ts/test-branch!
   (fn [_ conn]
     (rec! conn {'a/r {:test 'a/r :digest "d-red" :pass 0 :fail 0 :error 1}} {'a/r [{:type :error :message "thrown"}]} false)
     (let [r (get (proofs/stored-results @conn ["d-red"]) "d-red")]
       (is (= [1 false] [(:error r) (boolean (:flaky r))]) (pr-str r))
       (is (= ["thrown"] (map :message (:failures r))))))))

(deftest flaky-and-unreported-results-record-nothing
  (is (= []
         (proofs/results-tx {:id "r"} {'a/f {:test 'a/f :digest "d1" :pass 0 :fail 1 :error 0 :flaky true}
                                       'a/u {:test 'a/u :digest "d2" :pass 0 :fail 0 :error 1 :unreported true}
                                       'a/n {:test 'a/n :pass 1 :fail 0 :error 0}} {}))))

(deftest a-refused-write-is-unrecorded
  (let [refused {:seon.error/type :seon.writer/refused :seon.error/operation 'seon.writer/writer-one :seon.error/message "no"}
        r (proofs/record! (fn [_] refused) nil {:run {:id "r"} :ran {'a/t {:test 'a/t :digest "d" :pass 1 :fail 0 :error 0}}})]
    (is (= :seon.proofs/unrecorded (:seon.error/type r)) (pr-str r))))

(deftest a-run-writes-what-ran-and-one-value-of-its-function-costs
  (ts/test-branch!
   (fn [_ conn]
     (let [ran {'a/t1 {:test 'a/t1 :digest "d1" :pass 1 :fail 0 :error 0 :fns (into [(fc 'a/f 10 1000 900 64)] (for [i (range 40)] (fc (symbol "a" (str "g" i)) 1 50 50 0)))}
                'a/t2 {:test 'a/t2 :digest "d2" :pass 1 :fail 0 :error 0 :fns [(fc 'a/f 2 300 100 8)]}}
           tx (proofs/record-tx @conn {:run {:id "r1" :tree "t"} :ran ran})
           before (count (d/datoms @conn :eavt))
           _ (d/transact conn {:tx-data tx})
           written (- (count (d/datoms @conn :eavt)) before)]
       (testing "no datom a function or a call: two results, a run with one detail value"
         (is (= 3 (count tx)) (pr-str (map keys tx)))
         (is (< written 30) (str written " datoms for 2 tests calling 41 functions")))
       (testing "which tests exercise a function, and how much, read from the runs that recorded them"
         (is (= [['a/t1 10 1000 900] ['a/t2 2 300 100]] (mapv (juxt :test :calls :ticks :self-ticks) (proofs/exercising @conn 'a/f ["d1" "d2"]))))
         (is (= ['a/t2] (mapv :test (proofs/exercising @conn 'a/f ["d2"]))) "only the tests at the digests asked"))
       (testing "a run off main's tree writes no function facts"
         (is (= [] (proofs/fns @conn))))))))

(deftest a-functions-cost-per-call-is-written-only-when-it-moves
  (ts/test-branch!
   (fn [_ conn]
     (rec! conn {'a/t1 {:test 'a/t1 :digest "m1" :pass 1 :fail 0 :error 0 :fns [(fc 'a/f 10 1000 1000 100) (fc 'a/g 4 400 400 0)]}} {} true)
     (is (= {'a/f [100 10] 'a/g [100 0]} (into {} (map (juxt :var (juxt :self-ticks :self-bytes))) (proofs/fns @conn)))
         "main's run: each function's cost per call, its sums over the calls")
     (let [n (count (d/datoms @conn :eavt))]
       (rec! conn {'a/t1 {:test 'a/t1 :digest "m2" :pass 1 :fail 0 :error 0 :fns [(fc 'a/f 10 1050 1050 100) (fc 'a/g 4 400 400 0)]}} {} true)
       (is (= {'a/f 100} (into {} (map (juxt :var :self-ticks)) (filter #(= 'a/f (:var %)) (proofs/fns @conn)))) "5% is noise: not rewritten")
       (is (< (- (count (d/datoms @conn :eavt)) n) 15) "only the result and the run were written"))
     (rec! conn {'a/t1 {:test 'a/t1 :digest "m3" :pass 1 :fail 0 :error 0 :fns [(fc 'a/f 10 3000 3000 100) (fc 'a/g 4 400 400 0)]}} {} true)
     (is (= 300 (:self-ticks (first (filter #(= 'a/f (:var %)) (proofs/fns @conn))))) "tripled: rewritten")
     (testing "the slowest per call, by its own ticks"
       (is (= ['a/f 'a/g] (mapv :var (proofs/slowest @conn 2)))))
     (testing "what got slower: a tree's detail against main's facts"
       (let [detail {'a/t1 {'a/f [10 9000 9000 100 100] 'a/g [4 400 400 0 0] 'a/new [1 9 9 0 0]}}]
         (is (= [['a/f :self-ticks 300 900 3.0]] (mapv (juxt :var :by :before :after :ratio) (proofs/slower @conn detail)))
             "f tripled again; g held; a function main never measured is new, not slower"))))))
