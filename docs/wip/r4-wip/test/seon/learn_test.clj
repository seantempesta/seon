(ns seon.learn-test
  "The learning loop's pure functions (lane R4), called with data: template versions and
  picking, grades from attempts, rows, flags, the verdict between two arms, references
  found and not named. Run: bin/test seon.learn-test"
  (:require [clojure.test :refer [deftest is testing]]
            [seon.learn :as learn] [seon.template :as tpl]))

(def ^:private t-design (:design tpl/templates))

(defn- attempt [task n status & {:as m}]
  (merge {:seon.attempt/id (str task "#" n) :seon.attempt/task task :seon.attempt/status status
          :seon.attempt/start-ms 0 :seon.attempt/end-ms 1000 :seon.attempt/turns 2 :seon.attempt/tokens 3000
          :seon.attempt/outcome (if (#{:done :merged} status) :green :cap)}
         m))

(defn- task [id & {:as m}]
  (merge {:seon.task/id id :seon.task/kind :design :seon.task/template :design
          :seon.task/template-version (learn/version t-design)} m))

(deftest version-is-content
  (is (= (learn/version t-design) (learn/version (into {} t-design))) "equal content, equal version")
  (is (not= (learn/version t-design) (learn/version (assoc-in t-design [:seon.template/estimate :turns] 4)))
      "an estimate change is a new version"))

(deftest pick-splits-by-task-and-drops-losers
  (let [b (assoc t-design :seon.task/instructions "candidate" :seon.template/replaces (learn/version t-design))
        ids (map #(str "app/t" %) (range 200))
        picks (frequencies (map #(learn/version (learn/pick [t-design b] #{} %)) ids))]
    (is (= 2 (count picks)) "both arms are picked")
    (is (< 70 (get picks (learn/version b)) 130) "about half each")
    (is (= (learn/pick [t-design b] #{} "app/x") (learn/pick [t-design b] #{} "app/x")) "the same task, the same arm")
    (is (every? #(= t-design (learn/pick [t-design b] #{(learn/version b)} %)) ids) "a lost version is never picked")
    (is (= t-design (learn/pick [t-design] #{(learn/version t-design)} "app/x")) "a lone version is kept")))

(deftest grade-counts-every-run-until-done
  (testing "two failed attempts, then done: turns of all three"
    (let [g (learn/grade (task "a/x") [(attempt "a/x" 2 :bailed :seon.attempt/turns 8 :seon.attempt/refusals #{:proof-red})
                                       (attempt "a/x" 1 :killed :seon.attempt/turns 3)
                                       (attempt "a/x" 3 :done :seon.attempt/turns 1)
                                       (attempt "a/x" 4 :closed :seon.attempt/turns 9)])]
      (is (:done? g))
      (is (= 12 (:turns g)) "3 + 8 + 1; the attempt after done is not counted")
      (is (= 3 (:attempts g)))
      (is (= {:proof-red 1} (:refusals g)))
      (is (= :green (:outcome g)))))
  (testing "never done"
    (let [g (learn/grade (task "a/y") [(attempt "a/y" 1 :bailed :seon.attempt/turns 8)])]
      (is (false? (:done? g)))
      (is (= 8 (:turns g)))
      (is (= :cap (:outcome g))))))

(defn- grades [n done-every turns]
  (vec (for [i (range n) :let [id (str "a/t" i) done? (zero? (mod i done-every))]]
         (learn/grade (task id) [(attempt id 1 (if done? :done :bailed) :seon.attempt/turns turns)]))))

(deftest row-and-flag
  (let [e (:seon.template/estimate t-design)]
    (testing "every task green in the estimate: no flag"
      (let [r (learn/row (grades 20 1 3) e)]
        (is (= [20 20 1.0 3] [(:n r) (:done r) (:rate r) (:ttg-p50 r)]))
        (is (nil? (learn/flag r e learn/dials)))))
    (testing "one in three green: under the floor"
      (is (= :success-below-floor (:rule (learn/flag (learn/row (grades 30 3 3) e) e learn/dials)))))
    (testing "green, but at 3x the estimate"
      (is (= :turns-over-estimate (:rule (learn/flag (learn/row (grades 20 1 9) e) e learn/dials)))))
    (testing "too few ended tasks to judge"
      (is (nil? (learn/flag (learn/row (grades 5 3 9) e) e learn/dials))))
    (testing "rows keep the recent window per version"
      (is (= [12] (map :n (learn/rows (grades 40 1 3) {} 12)))))))

(defn- arm [version n green-every turns-fn]
  (vec (for [i (range n) :let [id (str version "/t" i) done? (zero? (mod i green-every))]]
         (learn/grade (task id :seon.task/template-version version)
                      [(attempt id 1 (if done? :done :bailed) :seon.attempt/turns (turns-fn i))]))))

(deftest verdict-decides-on-measured-grades
  (let [a-ver (apply str (repeat 16 "a")) b-ver (apply str (repeat 16 "b"))]
    (testing "too few tasks per arm"
      (is (false? (:decided? (learn/verdict (arm a-ver 10 1 (constantly 3)) (arm b-ver 40 1 (constantly 2)) learn/dials)))))
    (testing "the candidate turns more tasks green"
      (let [v (learn/verdict (arm a-ver 40 2 (constantly 3)) (arm b-ver 40 1 (constantly 3)) learn/dials)]
        (is (= [true :success b-ver a-ver] [(:decided? v) (:by v) (:winner v) (:loser v)]))))
    (testing "the same success, fewer turns"
      (let [v (learn/verdict (arm a-ver 40 1 #(+ 5 (mod % 3))) (arm b-ver 40 1 #(+ 2 (mod % 3))) learn/dials)]
        (is (= [:turns b-ver] [(:by v) (:winner v)]))))
    (testing "no measured gain: the candidate loses"
      (let [v (learn/verdict (arm a-ver 40 1 #(+ 3 (mod % 3))) (arm b-ver 40 1 #(+ 3 (mod % 3))) learn/dials)]
        (is (= [:no-gain a-ver b-ver] [(:by v) (:winner v) (:loser v)]))))))

(deftest found-not-named
  (is (= [{:symbol 'a/z :score 0.9 :found true} {:symbol 'a/y :score 0.7 :found true}]
         (learn/found [['a/x 0.95] ['a/y 0.7] ['a/z 0.9] ['a/w 0.1]] #{'a/x} 2))))

(deftest improve-task-filed-once-per-version
  (let [r (learn/row (grades 30 3 3) (:seon.template/estimate t-design))
        f (learn/flag r (:seon.template/estimate t-design) learn/dials)
        d1 (learn/improve-decl t-design r f []) d2 (learn/improve-decl t-design r f [{:task "a/t1"}])]
    (is (= (:seon.task/name d1) (:seon.task/name d2)) "the id derives from the flagged version")
    (is (= :design (:seon.task/kind d1)))
    (is (re-find #"`seon.template/templates`" (:seon.task/plan d1)) "the plan names the templates as a reference that resolves")))

(deftest waste-is-charged-to-the-producer-of-what-was-shown
  (let [shown {'seon.context/resolve-ref #{'app.cards/initials} 'seon.found/found #{'app.text/squeeze}}
        turns [{:n 0 :shown shown
                :items [{:form "(clojure.repl/source app.cards/initials)" :error "No such var: clojure.repl/source"}
                        {:form "(keys (ns-publics 'app.text))"}]}
               {:n 1 :shown shown
                :items [{:form "(clojure.repl/source app.text/squeeze)" :error "No such var: clojure.repl/source"}
                        {:form "(require '[app.text :as t])"}
                        {:form "(in-ns 'app.cards)"}
                        {:form "(defn initials {:malli/schema [:=> [:cat :string] :string]} [n] (t/fold-accents n))"}]}]
        final #{'app.text/fold-accents}
        ws (learn/waste turns final)]
    (is (= [[0 :re-read 'seon.context/resolve-ref] [0 :missing :template]
            [1 :repeats-error 'seon.context/current-state]]
           (mapv (juxt :turn :rule :producer) ws))
        "a shown var read again is its render's; a namespace the fix used but nothing showed is the template's; a repeated error is the render that delivered it")
    (is (= {'seon.context/resolve-ref {:forms 1 :turns 1} :template {:forms 1 :turns 1} 'seon.context/current-state {:forms 1 :turns 1}}
           (learn/waste-summary ws)))
    (is (= {'t 'app.text} (learn/aliases ["(require '[app.text :as t])"])))))
