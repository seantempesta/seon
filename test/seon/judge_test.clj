(ns seon.judge-test
  "The first-move judge's facts and score: a reply's first move read by the compiler, never by
  another model."
  (:require [clojure.test :refer [deftest is testing]] [clojure.edn :as edn] [seon.judge :as judge] [seon.agent-test :as at]))

(def ^:private subject
  "A task's subject as seon.judge/subject makes it: the plan cites seon.judge/score (its source
  shown) and seon.judge/fact (by name), and its proof is seon.judge-test/p."
  {:cited #{'seon.judge/score 'seon.judge/fact} :shown #{'seon.judge/score} :proofs #{'seon.judge-test/p} :ns 'seon.judge-test})

(defn- move [& calls] (judge/first-move subject (vec calls)))

(deftest a-first-move-is-read-by-the-compiler
  (testing "a deftest naming the cited var is defined, references it and the proof's name"
    (let [fs (judge/form-facts 'seon.judge-test "(clojure.test/deftest p (clojure.test/is (map? (seon.judge/score {} []))))")]
      (is (= 1 (count fs)))
      (is (= #{'seon.judge-test/p} (:defs (first fs))))
      (is (contains? (:refs (first fs)) 'seon.judge/score))))
  (testing "a read names what it reads"
    (is (contains? (:refs (first (judge/form-facts 'seon.judge-test "(clojure.repl/source seon.judge/score)"))) 'seon.judge/score)))
  (testing "a reply's ns form sets up the aliases its later forms use, as loading does"
    (let [fs (judge/form-facts 'seon.judge-test "(ns judge.scratch-a (:require [seon.judge :as j]))\n(defn f [] (j/score {} []))")]
      (is (= [nil nil] (map :error fs)))
      (is (= #{'judge.scratch-a/f} (:defs (second fs))))
      (is (contains? (:refs (second fs)) 'seon.judge/score))))
  (testing "code is read with *read-eval* off"
    (is (some? (:error (first (judge/form-facts 'seon.judge-test "#=(java.lang.System/exit 3)"))))))
  (testing "a form that does not compile is its declared error, the others still read"
    (let [fs (judge/form-facts 'seon.judge-test "(no-such-fn 1)\n(def ok 1)")]
      (is (= :seon.judge/uncompiled (get-in (first fs) [:error :seon.error/type])))
      (is (= #{'seon.judge-test/ok} (:defs (second fs)))))))

(deftest the-score-says-what-the-first-move-did
  (is (= {:acted true :right-var true :right-test true :waste 0 :uncompiled 0 :green true}
         (move "(clojure.test/deftest p (clojure.test/is (map? (seon.judge/score {} []))))"))
      "the proof written, on the cited var: green")
  (is (= {:acted false :right-var false :right-test false :waste 1 :uncompiled 0 :green false}
         (move "(clojure.repl/source seon.judge/score)" "(clojure.repl/doc seon.judge/fact)"))
      "reading the shown var again is waste; the one cited by name only was not shown")
  (is (= {:acted true :right-var false :right-test false :waste 1 :uncompiled 0 :green false}
         (move "(defn helper [x] (inc x))" "(clojure.repl/source seon.judge/score)"))
      "an act off the task beside a read of the cited var is not green")
  (is (= {:acted false :right-var false :right-test false :waste 0 :uncompiled 0 :green false}
         (move "(clojure.repl/doc my.task/update!)"))
      "reading the plan's writer is no rewrite of the plan")
  (is (= {:acted true :right-var true :right-test true :waste 1 :uncompiled 0 :green true}
         (move "(do (clojure.repl/source seon.judge/score) (clojure.test/deftest p (clojure.test/is true)))"))
      "a form that reads and defines the proof acts")
  (is (= {:acted false :right-var false :right-test false :waste 0 :uncompiled 0 :green false} (move))
      "a reply with no eval call did nothing")
  (is (= {:acted true :right-var false :right-test false :waste 0 :uncompiled 0 :green false}
         (move "(ns judge.scratch-b)\n(def p 1)"))
      "a def named like the proof in another namespace is not the proof"))

(deftest a-request-is-keyed-by-what-it-sends
  (let [r {:messages [{:role "user" :content "a"}] :model "m" :thinking :disabled}]
    (is (= (judge/digest r) (judge/digest r)))
    (is (not= (judge/digest r) (judge/digest (assoc r :thinking :enabled))) "the mode is part of the request")
    (is (not= (judge/digest r) (judge/digest (assoc-in r [:messages 0 :content] "b"))) "so is every byte of the context")))

(deftest a-pass-scores-each-request-once
  ;; root's ruling (2026-10-23): the :seon.judge fact is the record; a pass asks the model only
  ;; for a request it holds none for, and a cost it cannot price ends the pass (unknown, never 0).
  ;; The scripted model reports no cache split, so its first move is scored (in the agent the
  ;; pass spawns), written, and stops the pass; the second pass reads it back and calls nothing
  (let [[r] (at/in-agent [{:op :eval :agent "root"
                           :code (str "(let [a (my.root/first-moves! sys [\"demo/t\"]) b (my.root/first-moves! sys [\"demo/t\"])]"
                                      " [(get-in a [:error :seon.error/type]) (mapv :reused? (:rows a)) (boolean? (:acted (first (:rows a))))"
                                      " (mapv :reused? (:rows b)) (:usd b) (count (datahike.api/datoms @(:conn sys) :aevt :seon.judge/digest))])")}])]
    (is (= [:seon.judge/cost-unknown [false] true [true] 0.0 1] (:value r)) (pr-str r))))

(deftest a-pass-stops-at-its-bound
  ;; at :seon.config.ai/judge-pass-usd the pass makes no further call and writes what it left
  (let [[r] (at/in-agent [{:op :eval :agent "root"
                           :code (str "(let [a (my.root/first-moves! sys [\"demo/t\"])]"
                                      " [(:rows a) (:stopped a) (count (datahike.api/datoms @(:conn sys) :aevt :seon.judge/stopped))])")}]
                         {:sets [":seon.config.ai/judge-pass-usd=0.0"]})]
    (is (= [[] {:usd 0.0 :bound 0.0 :left ["demo/t"]} 1] (:value r)) (pr-str r))))
