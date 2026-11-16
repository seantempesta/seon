(ns my.test-test
  "my.test: the world and its builders (task my.test/a-test-runs-in-the-world)."
  (:require [clojure.test :refer [deftest is]] [my.test :as t] [datahike.api :as d] [seon.task] [clojure.string :as str] [seon.test-store :as ts] [seon.db :as pdb] [seon.agent]))

(deftest the-world-is-built-once-per-process
  ;; docs/testing.md#testing-in-the-world: one world a process, every test's base
  (is (identical? (t/world) (t/world)))
  (is (= '#{app.calc/add app.calc/inc2 app.calc/total app.calc/twice app.report/line app.calc-test/add-adds app.calc-test/total-sums}
         (set (d/q '[:find [?n ...] :where [_ :var/name ?n]] (t/world))))
      "it holds what its docstring names"))

(deftest the-history-world-keeps-what-a-fixture-replaced
  ;; docs/testing.md#testing-in-the-world: {:history? true} is the same world, keeping history
  (let [hw (t/world {:history? true})
        db (t/with hw (t/code 'app.calc "(ns app.calc)\n(defn add [x y] (- x y))\n"))
        srcs (fn [d] (set (d/q '[:find [?s ...] :where [?v :var/name app.calc/add] [?v :var/form ?f] [?f :form/src ?s]] d)))]
    (is (identical? hw (t/world {:history? true})) "built once a process")
    (is (= (srcs (t/world)) (srcs hw)) "the same world")
    (is (< 1 (count (srcs (d/history db)))) "the replaced form is in its history")))

(deftest a-fixture-adds-to-the-world-and-leaves-it-as-it-was
  (let [db (t/with (t/world) (t/code 'app.x "(ns app.x)\n(defn f [x] (app.calc/add x 1))\n") (t/page "docs/x.md" "# X\n\nSee app.x/f.\n"))]
    (is (some? (d/entity db [:var/name 'app.x/f])) "the fixture's var")
    (is (nil? (d/entity (t/world) [:var/name 'app.x/f])) "the world unchanged: a value")))

(deftest a-world-test-runs-far-under-the-ceiling
  ;; the per-test ceiling is :seon.config.agent/test-ticks; a world and a fixture cost a sliver of it
  (let [{:keys [ticks]} (t/cost #(t/with (t/world) (t/code 'app.y "(ns app.y)\n(defn g [] 1)\n")))]
    (is (< ticks 20000000) (str "ticks: " ticks))))

(deftest the-pages-example-runs
  ;; docs/testing.md#testing-in-the-world's example, as written there
  (let [db (t/with (t/world)
                   (t/code 'app.x "(ns app.x)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"))]
    (is (= "[:=> [:cat :int] :int]" (:var/contract (d/entity db [:var/name 'app.x/f]))))))

(deftest without-takes-a-var-and-its-form-out
  (let [db (t/with (t/world) (t/without 'app.calc/inc2))]
    (is (nil? (d/entity db [:var/name 'app.calc/inc2])))
    (is (empty? (d/q '[:find ?f :where [?f :form/id "app.calc/inc2"]] db)) "its form too")
    (is (some? (d/entity db [:var/name 'app.calc/add])) "and nothing else")))

(deftest a-task-fixture-takes-the-tasks-it-needs
  (let [db (t/with (t/world) (t/task 'app.calc "next" "Then this." #{'app.calc-test/add-adds} #{"app.calc/total-is-exact"}))]
    (is (= #{'app.calc-test/add-adds 'app.calc-test/total-sums}
           (:proofs (:seon.task/accumulated (seon.task/task db "app.calc/next"))))
        "it inherits the proofs of the task it needs")))

;; Estimate: two evals recorded as an agent's (~1M each: the defn's analysis and record, the call's record), the task and agent made
;; on the world (~0.5M), one pull and one query (~0.2M) = ~3M ticks.
(deftest ^{:seon.test/estimate {:ticks 3000000}} eval-in-records-each-form-as-its-own-tasks-agent
  ;; docs/testing.md#testing-in-the-world: forms evaluated as an agent in this process, as c/submit! has an agent evaluate them,
  ;; with no root: the definition is on its db, each eval is recorded on its own task's agent, and nothing is stored
  (let [[defd called rec] (t/eval-in (fn [{:keys [ns]}]
                                        [(str "(ns " ns ".a) (defn f \"One.\" [] 1)")
                                         (str "(" ns ".a/f)")
                                         (fn [{:keys [db names]}]
                                           {:src (:form/src (:var/form (d/entity db [:var/name (symbol (str (:ns names) ".a") "f")])))
                                            :evals (d/q '[:find (count ?e) . :in $ ?id :where [?t :seon.task/id ?id] [?a :seon.agent/task ?t] [?e :seon.eval/agent ?a]]
                                                        db (:task names))
                                            :ns (:seon.agent/ns (d/entity db [:seon.agent/key (:agent names)]))})])
                                      'user {:db (t/world)})]
    (is (nil? (:ex defd)) (pr-str defd))
    (is (= 1 (:value called)) (pr-str called))
    (is (str/includes? (str (get-in rec [:value :src])) "[] 1") (pr-str rec))
    (is (= 2 (get-in rec [:value :evals])) "both evals recorded on the task's own agent")
    (is (str/ends-with? (str (get-in rec [:value :ns])) ".a") "where its eval left its REPL")
    (is (empty? (d/q '[:find ?e :where [?e :seon.eval/id]] (t/world))) "the world is as it was: nothing stored")))

;; docs/testing.md#testing-in-the-world: an agent's test fork on a stored branch of its own (my.test/eval-in with :conn, a branch of
;; seon.test-store/test-branch!) commits there, as an agent commits its branch, and tells no root: a test's agent writes its own value
;; only (:role :test). Red before: eval-in took no :conn, so its agent had no store handle and commit! refused (no branch).
;; Estimate: a test branch (~1M), the task and agent committed (~1M), one write and its commit (~1M): 3,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 3000000}} an-agents-test-fork-on-a-stored-branch-commits-there-and-tells-no-root
  (let [sent (atom [])]
    (with-redefs [seon.agent/send! (fn [m] (swap! sent conj m) true)]
      (ts/test-branch!
        (fn [_ conn]
          (let [before (d/commit-id @conn)
                [r] (my.test/eval-in ["(do (seon.agent/transact! agent [{:seon.agent/key (:key agent) :seon.agent/ns 'stored.one}]) (seon.agent/commit! agent) (datahike.api/commit-id (seon.agent/db)))"]
                                     'seon.repl-test.scratch {:conn conn})]
            (is (some? (:value r)) (pr-str r))
            (is (= (:value r) (d/commit-id @conn)) "the agent's commit is the branch's head")
            (is (not= before (d/commit-id @conn)) "the branch moved")
            (is (= ['stored.one] (d/q '[:find [?n ...] :where [?a :seon.agent/ns ?n]] @conn)) "with the agent's write")))))
    (is (= [] @sent) "and no root heard it")))
