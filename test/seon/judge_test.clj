(ns seon.judge-test
  "The first-move judge's facts and score: a reply's first move read by the compiler, never by
  another model."
  (:require [clojure.test :refer [deftest is testing]] [clojure.edn :as edn] [seon.judge :as judge] [seon.agent-test :as at]))

(def ^:private task
  "A task as main holds it: the plan cites seon.judge/score, its proof is seon.judge-test/p."
  {:seon.task/id "seon.judge/t" :seon.task/ns 'seon.judge :seon.task/name "t"
   :seon.task/references #{'seon.judge/score 'seon.judge/fact} :seon.task/proofs #{'seon.judge-test/p}})

(deftest a-first-move-is-read-by-the-compiler
  (testing "a deftest naming the cited var is defined, references it and the proof's name"
    (let [fs (judge/form-facts 'seon.judge-test "(clojure.test/deftest p (clojure.test/is (map? (seon.judge/score {} []))))")]
      (is (= 1 (count fs)))
      (is (= #{'seon.judge-test/p} (:defs (first fs))))
      (is (contains? (:refs (first fs)) 'seon.judge/score))))
  (testing "a read names what it reads, through the quoted symbol the macro expands to"
    (is (contains? (:refs (first (judge/form-facts 'seon.judge-test "(clojure.repl/source seon.judge/score)"))) 'seon.judge/score)))
  (testing "a form that does not compile is its declared error, the others still read"
    (let [fs (judge/form-facts 'seon.judge-test "(no-such-fn 1)\n(def ok 1)")]
      (is (= :seon.judge/uncompiled (get-in (first fs) [:error :seon.error/type])))
      (is (= #{'seon.judge-test/ok} (:defs (second fs)))))))

(deftest the-score-says-what-the-first-move-did
  (let [act (judge/form-facts 'seon.judge-test "(clojure.test/deftest p (clojure.test/is (map? (seon.judge/score {} []))))")
        read (judge/form-facts 'seon.judge-test "(clojure.repl/source seon.judge/score)\n(clojure.repl/doc seon.judge/fact)")
        astray (judge/form-facts 'seon.judge-test "(defn helper [x] (inc x))")]
    (is (= {:acted true :right-var true :right-test true :waste 0 :uncompiled 0 :green true} (judge/score task act)))
    (is (= {:acted false :right-var true :right-test false :waste 2 :uncompiled 0 :green false} (judge/score task read))
        "reading the two cited vars again is two wasted reads and no act")
    (is (= {:acted true :right-var false :right-test false :waste 0 :uncompiled 0 :green false} (judge/score task astray))
        "acting on a var the plan does not name is not green")))

(deftest a-request-is-keyed-by-what-it-sends
  (let [r {:messages [{:role "user" :content "a"}] :model "m" :thinking :disabled}]
    (is (= (judge/digest r) (judge/digest r)))
    (is (not= (judge/digest r) (judge/digest (assoc r :thinking :enabled))) "the mode is part of the request")
    (is (not= (judge/digest r) (judge/digest (assoc-in r [:messages 0 :content] "b"))) "so is every byte of the context")))

(deftest a-pass-scores-each-request-once
  ;; root's ruling (2026-10-23): the :seon.judge fact is the record; a pass asks the model only
  ;; for a request it holds none for, and a cost it cannot price ends the pass (unknown, never 0).
  ;; The scripted model reports no cache split, so its first move is scored, written, and stops
  ;; the pass; the second pass reads it back and calls nothing
  (let [[r] (at/in-agent [{:op :eval :agent "root"
                           :code (str "(let [a (my.root/first-moves! sys [\"demo/t\"]) b (my.root/first-moves! sys [\"demo/t\"])]"
                                      " [(get-in a [:error :seon.error/type]) (mapv :reused? (:rows a)) (mapv :reused? (:rows b)) (:usd b)"
                                      " (count (datahike.api/datoms @(:conn sys) :aevt :seon.judge/digest))])")}])]
    (is (= [:seon.judge/cost-unknown [false] [true] 0.0 1] (edn/read-string (:printed r))) (pr-str r))))

