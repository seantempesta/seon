(ns seon.context-test
  "The agent's current-state block says each thing once: a passing proof is its status, a
  red one its source and its failures (expected and actual), a reference already shown as a
  proof is not shown again, a merge task's plan stays its own."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.db :as pdb] [seon.vector :as vector] [seon.task :as task] [seon.context :as context] [seon.print :as print]
            [seon.doc-test] [seon.reader :as reader] [seon.proofs :as proofs] [seon.agent :as agent] [seon.doc :as doc] [seon.judge] [seon.ctx :as ctx] [seon.render :as render] [seon.render.ui] [seon.web.html]
            [seon.print.select :as select] [clojure.string :as str] [seon.lib] [seon.edn] [seon.print-test :as print-test] [seon.repl :as repl] [seon.model]))

(defn- ns-fact
  "Var v's namespace with its file, as a store records it: a program namespace's under src/, a
  test's under test/ (seon.program/program-name? reads which)."
  [v]
  (let [n (namespace v)]
    {:db/id (str "ns " n) :ns/name (symbol n) :ns/file (str (if (str/ends-with? n "-test") "test/" "src/") (str/replace n "-" "_") ".clj")}))

(defn- db-with-program []
  (let [db (ts/test-db)]
    ;; recorded definitions, each its form (seon.program's facts), written as data
    (d/db-with db (vec (mapcat (fn [[v src]]
                                 [{:db/id (str v) :var/name v :var/ns (str "ns " (namespace v)) :var/form (str "form " v)}
                                  {:db/id (str "form " v) :form/id (str v) :form/src src :form/ns (str "ns " (namespace v)) :form/order 0
                                   :form/defines [(str v)]}
                                  (ns-fact v)])
                               '[[demo/f "(defn f \"Doubles.\" [x] (* 2 x))"]
                                 [demo-test/f-test "(deftest f-test (is (= 4 (f 2))))"]
                                 [demo-test/g-test "(deftest g-test (is (= 1 (g))))"]
                                 ;; a test the agent wrote in its scratch namespace, no proof of its task
                                 [user/scratch-test "(deftest scratch-test (is (= 1 2)))"]])))))

(defn- data-of
  "Task id's data on db (seon.context/task-data), its cites read as a reader that is no draw reads them (seon.task/task-cites)."
  [db id] (context/task-data db (task/task db id) (task/task-cites db id)))

(defn- status-at
  "How agent b/a1's work on task tid stands (seon.context/status) on db once the facts the source
  reads are on it: vs, {test [status failures]}, each test's :seon.test/status and
  :seon.test/failures on its var entity (seon.proofs/result-tx's), and the agent."
  ([db tid vs] (status-at db tid vs {}))
  ([db tid vs opts]
   (let [db (d/db-with db (into [{:seon.agent/key "b/a1" :seon.agent/task [:seon.task/id tid]}]
                                (for [[t [st fs]] vs]
                                  (cond-> {:var/name t :seon.test/status st} (seq fs) (assoc :seon.test/failures (vec fs))))))]
     ;; read from the agent's record as the page's status part reads it (its run, its task's proofs)
     (context/status db (:tree (seon.ctx/build db (pdb/eid db :seon.agent/key "b/a1") :b)) opts))))

(defn- index-of
  "main's claims as the reconciler's index holds them (seon.reconcile/index), with only what the done rule
  reads: acc {task proofs}, claims {test #{tasks}}, done {task {:merged-t}}."
  [acc claims done]
  {:root nil :tasks {} :keys {} :needed-by {} :acc acc :depth {} :claims claims :runs {} :by-task {} :done done
   :ready #{} :ready-key {} :live #{} :reap #{} :kill {} :judge #{} :merging #{}})

(defn- wiki!
  "The checkout's wiki into conn, as root's boot loads it (seon.root/start!): the help page
  every render reads is a page of it."
  [conn] (d/transact conn (doc/load-tx @conn "docs" (doc/pages (seon.config/checkout) "docs"))))

(deftest each-thing-is-said-once
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make `demo/f` double."
                                               :seon.task/instructions "Use `demo/f` and `demo-test/g-test`."
                                               :seon.task/proofs #{'demo-test/f-test 'demo-test/g-test}}]))
        t (task/task db "demo/f")
        block (data-of db "demo/f")
        st (status-at db "demo/f" {'demo-test/f-test [:green] 'demo-test/g-test [:red [{:type :fail :expected "(= 1 (g))" :actual "(not (= 1 0))" :got "(= 1 0)"}]]})
        by (into {} (map (juxt :test identity)) (:proofs st))]
    (is (= {:test 'demo-test/f-test :status :pass} (by 'demo-test/f-test)) "a passing proof is its status")
    (is (select/code? (:source (by 'demo-test/g-test))) "a red proof shows its source, as code")
    (is (= [{:assert (print/code "(= 1 (g))") :got (print/code "(= 1 0)")}] (:failures (by 'demo-test/g-test)))
        "a failure is the assertion and what it evaluated to")
    (is (= ['demo/f] (mapv :symbol (:write-set block))) "a proof named in the plan is not shown again in the write set")
    (is (select/code? (:source (first (:write-set block)))) "a var of the write set shows its source, as code")
    (is (= {'demo-test/f-test '(my.agent/prove #{demo-test/f-test}) 'demo-test/g-test '(my.agent/prove #{demo-test/g-test})} (:to-prove block))
        "each proof and the form that runs it")))

(deftest a-merge-plan-stays-its-own
  (let [db (d/db-with (db-with-program) [])
        decls [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "Write a."}
               {:seon.task/ns 'demo :seon.task/name "a-merge" :seon.task/kind :merge :seon.task/plan "Merge a." :seon.task/needs #{"demo/a"}}
               {:seon.task/ns 'demo :seon.task/name "b" :seon.task/plan "Write b." :seon.task/needs #{"demo/a-merge"}}]
        db (d/db-with db (task/create-tx db decls))]
    (is (= "Write a.\n\nMerge a." (:plan (:seon.task/accumulated (task/task db "demo/a-merge")))))
    (is (= "Write a.\n\nWrite b." (:plan (:seon.task/accumulated (task/task db "demo/b")))) "b is not told to merge")))

(deftest a-shown-value-without-a-handle-names-no-form
  (let [{:keys [text cuts]} (print/print-value (vec (range 5000)) {:budget 60})]
    (is (re-find #"…\d+" text))
    (is (not (re-find #"result/" text)) "no result handle the reader does not hold")
    (is (nil? (:seon.print/continuation (first cuts))))))

(deftest a-cut-reply-is-told-to-the-agent
  ;; the run's head (seon.render.ui/run-head), not the work's status: a cut reply is the conversation's
  (let [head (fn [turn] (pr-str (seon.render.ui/run-head {:seon.ctx/ctx {:seon.agent/status :running :seon.agent/max-turns 4 :turns {"t1" turn}} :seon.db/db (ts/test-db)} 1)))]
    (is (re-find #"at the model's token limit" (head {:seon.turn/n 0 :seon.turn/truncated true})) (head {:seon.turn/n 0 :seon.turn/truncated true}))
    (is (not (re-find #"token limit" (head {:seon.turn/n 0}))))))

;; task seon.context/an-agent-follows-its-instructions-to-green, item 2: three of five md-scan bench runs changed
;; nothing in 40 turns, their run head the turn alone; beside it, what the evals changed of the branch's program
(deftest the-run-head-shows-what-the-branch-changed
  (let [head (fn [evals] (seon.render.ui/run-head {:seon.ctx/ctx {:seon.agent/max-turns 4 :turns {"t1" {:seon.turn/n 0}} :evals evals} :seon.db/db (ts/test-db)} 1))
        ;; newest first, as ctx holds :evals: a read, the definition redefined, the definition
        evals (array-map "e3" {:seon.eval/record {:code "(f 2)" :response {:value 4}}}
                         "e2" {:seon.eval/record {:code "(defn f [x] (* 2 x))" :recorded '[demo/f] :response {}}}
                         "e1" {:seon.eval/record {:code "(defn f [x] x)" :recorded '[demo/f] :response {}}})
        m (head evals)]
    (is (nil? (:changed (head (array-map "e3" (get evals "e3"))))) "a read changes nothing")
    (is (= '[demo/f] (:changed m)) "one recorded definition, once however often redefined")
    (is (= [:turn :changed] (take 2 (keys m))) "beside the turn")
    (is (str/includes? (seon.web.html/html (seon.render.ui/run-html {} m)) "turn 1 of 4 · 1 change") "the page reads 1 change")))

(deftest a-records-text-is-the-same-whatever-came-before
  ;; task seon.context/the-context-is-the-agents-evals-and-seons: a record's printed text is a
  ;; function of that record alone (docs/render.md). Records with equal values, and one whose code
  ;; repeats an earlier one's, print the same bytes after other records as each printed alone.
  (let [rows (vec (for [i (range 200)] {:row i :name (str "row-" i)}))
        recs {"e1" {:code "(rows)" :value rows :out "---- budget 16\n[:kw …]\n"}
              "e2" {:code "(rows)" :value (vec rows) :out "---- budget 16\n[:kw …]\n"}
              "e3" {:code "(other)" :value (vec rows) :out "other\n"}}
        db (d/db-with (db-with-program) (for [[id r] recs]
                                          {:seon.eval/id id :seon.eval/by "a1"
                                           :seon.eval/record {:code (:code r) :call-id (str "c" id) :call 0
                                                              :response {:handle (symbol "result" id) :value (:value r) :out (:out r) :ms 1}}}))
        ev (fn [id] [[:eval id] (d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] [:seon.eval/id id]) :seon.db/eval-fact 1])
        after (#'context/printed-run (mapv ev ["e1" "e2" "e3"]) nil)]
    (doseq [id ["e1" "e2" "e3"]
            :let [alone (#'context/printed-run [(ev id)] nil)]]
      (is (= (get-in alone [:parts (str "eval/" id)]) (get-in after [:parts (str "eval/" id)])) (str id " prints the same after other records as alone"))
      (is (= [:eval id] (get-in after [:parts (str "eval/" id) :ref])) "and its ref names only itself"))
    (let [text #(:content (peek (get-in after [:parts (str "eval/" %) :whole])))]
      (is (str/includes? (text "e2") "---- budget 16") "the repeated answer prints whole")
      (is (not (str/includes? (text "e3") "(:value result/")) "an equal value is printed, not read from an earlier handle"))))

(defn- db-f
  "db with the task demo/f, whose proof is demo-test/f-test."
  [db] (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p"
                                          :seon.task/proofs #{'demo-test/f-test}}])))

(defn- task-f [db] (task/task (db-f db) "demo/f"))

(deftest a-reason-and-a-run-error-reach-the-context
  (let [db (db-f (db-with-program))
        failure {:type :fail :expected "(= 1 (g))" :actual "(not (= 1 0))" :got "(= 1 0)"}
        ;; main's claims (seon.reconcile/index): g-test is a merged task's proof, so red here it is a regression
        index (fn [] (index-of {"demo/f" #{'demo-test/f-test}} {'demo-test/g-test #{"demo/g"}} {"demo/g" {:merged-t 1}}))
        ;; the done rule refused beyond the task's own proof: a regression and a stray red; the run named one
        ;; failing assertion of the red test
        st (status-at db "demo/f" {'demo-test/f-test [:red] 'demo-test/g-test [:red [failure]] 'user/scratch-test [:red]} {:index index})]
    (testing "every reason but the task's own red proof (shown with the proofs), each with what explains it"
      (is (= [{:reason :regression :test 'demo-test/g-test :failures [{:assert (print/code "(= 1 (g))") :got (print/code "(= 1 0)")}]
               :source (print/code "(deftest g-test (is (= 1 (g))))")}
              {:reason :stray-red :test 'user/scratch-test :source (print/code "(deftest scratch-test (is (= 1 2)))")
               :do '(my.task/update! :seon.task/proofs conj (quote user/scratch-test))}]
             (:refused st)))
      (is (= [:proof-red :regression :stray-red] (mapv :reason (:reasons st))) "the done rule's own answer, beside it")
      (is (not (contains? (context/status-model {} st) :reasons)) "and the model is told each as what to do, not twice"))
    (testing "a run that failed as a whole is said, not read as tests not run"
      (let [err {:seon.error/type :seon.proofs/test-run-died :seon.error/operation 'seon.agent/test-run :seon.error/message "namespace app-test did not load: Unable to resolve symbol: g"}
            db (d/db-with db [{:seon.agent/key "b/a1" :seon.agent/task [:seon.task/id "demo/f"]}
                              {:seon.process/id "b/a1/1" :seon.process/agent [:seon.agent/key "b/a1"] :seon.process/test-error err}])
            st (context/status db (:tree (seon.ctx/build db (pdb/eid db :seon.agent/key "b/a1") :b)) {})]
        (is (= {:failed ["namespace app-test did not load: Unable to resolve symbol: g"]} (:test-run st)))
        (is (= [{:test 'demo-test/f-test :status :not-run :source (print/code "(deftest f-test (is (= 4 (f 2))))")}] (:proofs st)) "a written proof did not run")
        (is (re-find #"did not load" (print/text (vary-meta (context/status-model {} st) assoc :seon.print/full true))) "printed, as the page and (my.task/status) print it, it is there"))
      (is (= ["namespace app-test did not load: Unable to resolve symbol: g" "test process died: 9"]
             (proofs/test-run-errors {:reports [{:type :error :message "namespace app-test did not load: Unable to resolve symbol: g"}
                                           {:type :fail :var "#'demo-test/f-test" :message "x"}
                                           {:type :error :var nil :message "test process died: 9"}]}))))
    (testing "a design step that has not improved its plan is told what it lacks and the call that does it"
      (let [dd (d/db-with (db-with-program) (task/create-tx (db-with-program) [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/kind :design :seon.task/plan "p"
                                                                                :seon.task/proofs #{'demo-test/f-test}}]))
            b (status-at dd "demo/f" {'demo-test/f-test [:red]})]
        (is (= [[:no-design :seon.task/plan 'my.task/update!]]
               (mapv (juxt :reason :unchanged (comp first :do)) (filter #(= :no-design (:reason %)) (:refused b)))))))
    (testing "a task with no proofs is told the form that registers one (a design writes its own)"
      (let [nd (d/db-with (db-with-program) (task/create-tx (db-with-program) [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p"}]))
            b (status-at nd "demo/f" {})]
        (is (= ['my.task/update! :seon.task/proofs] (take 2 (:do (first (:refused b))))))))
    (testing "a stray red the branch does not define is one of main's tests it removed: marked so, and no form makes it a proof"
      (let [b (status-at db "demo/f" {'demo-test/f-test [:green] 'demo-test/gone [:red]} {:index (fn [] (index-of {"demo/f" #{'demo-test/f-test}} {} {}))})]
        (is (= [{:reason :stray-red :test 'demo-test/gone :removed true}] (:refused b)))))
    (testing "nothing refused, nothing failed: no section"
      (let [b (status-at db "demo/f" {'demo-test/f-test [:green]})]
        (is (not-any? #(contains? b %) [:refused :test-run :flagged :your-last-reply :unmerged]))
        (is (= [] (:reasons b)))))))

(deftest a-repair-is-told-on-its-call
  ;; owner, 2026-10-28: the forgiving reader may complete a form or make prose a comment, and
  ;; says exactly what it did on that call's own result (:repaired, docs/prompt.md)
  (let [code "Here is f.\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)))"
        {:keys [text repairs]} (reader/forgive code)
        r (context/repaired text repairs)]
    (is (some #{{:near {:line 2 :column 60 :text "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)),"} :dropped ")" :inserted ","}} r)
        "the extra close: what was dropped, what replaced it, and where")
    (is (some #{{:near {:line 1 :column 1 :text ";; Here is f."} :inserted ";; "}} r)
        "and the prose made a comment, told too")
    (is (= [] (context/repaired "(+ 1 2)" (:repairs (reader/forgive "(+ 1 2)")))) "code that reads is not repaired")))

(deftest only-program-tests-judge
  ;; a deftest in a scratch namespace never merges, so it never judges the branch
  (binding [*ns* *ns*]
    (eval '(ns ctx-prog-ns (:require [clojure.test :refer [deftest is]])))
    (eval '(deftest p-test (is true)))
    (eval '(ns ctx-scratch-ns (:require [clojure.test :refer [deftest is]])))
    (eval '(deftest s-test (is false)))
    ;; scratch as the platform declares the agent's own namespace (seon.agent/make-ns!);
    ;; Jolt's (ns ^:scratch x) does not carry the metadata yet (reported to root)
    (alter-meta! (find-ns 'ctx-scratch-ns) assoc :scratch true)
    (let [db (d/db-with (db-with-program) [{:ns/name 'ctx-prog-ns} {:ns/name 'ctx-scratch-ns} {:ns/name 'user}])]
      (is (= ['ctx-prog-ns/p-test] (:selected (agent/test-plan db db '#{ctx-prog-ns/p-test ctx-scratch-ns/s-test})))))))

(deftest a-failure-reads-as-assert-and-got
  (binding [*ns* *ns*]
    (eval '(ns ctx-calc))
    (eval '(defn square "Squares x." {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)))
    (eval '(ns ctx-calc-test (:require [clojure.test :refer [deftest is]] [ctx-calc :refer [square]])))
    (eval '(deftest square-test (is (= 9 (square 3))))))
  (let [db (d/db-with (db-with-program)
                      ;; as the compiler records them: each form's resolved references (:form/refs)
                      (vec (mapcat (fn [[v src refs facts]]
                                     [(merge {:db/id (str v) :var/name v :var/ns (str "ns " (namespace v)) :var/form (str "form " v)} facts)
                                      (cond-> {:db/id (str "form " v) :form/id (str v) :form/src src :form/ns (str "ns " (namespace v)) :form/order 0
                                               :form/defines [(str v)]}
                                        (seq refs) (assoc :form/refs refs))
                                      {:db/id (str "ns " (namespace v)) :ns/name (symbol (namespace v))}])
                                   '[[ctx-calc/square "(defn square \"Squares x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))" [clojure.core/*]
                                      {:var/kind :fn :var/contract "[:=> [:cat :int] :int]" :var/doc "Squares x."}]
                                     ;; a branch's function in a namespace this process never loaded
                                     [ctx-ghost/half "(defn half {:malli/schema [:=> [:cat :int] :int]} [x] (quot x 2))" [clojure.core/quot]
                                      {:var/kind :fn :var/contract "[:=> [:cat :int] :int]"}]
                                     [ctx-calc-test/square-test "(deftest square-test (is (= 9 (square 3))))" [clojure.test/is ctx-calc/square ctx-ghost/half]
                                      {:var/kind :test}]
                                     [ctx-calc-test/empty-test "(deftest empty-test (square 3))" [ctx-calc/square] {:var/kind :test}]
                                     [ctx-calc-test/stray-test "(deftest stray-test (is (= 2 (square 1))))" [clojure.test/is ctx-calc/square] {:var/kind :test}]])))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'ctx :seon.task/name "sq" :seon.task/plan "p"
                                               :seon.task/proofs #{'ctx-calc-test/square-test 'ctx-calc-test/cube-test 'ctx-calc-test/empty-test}}]))
        ;; the test child's report of the failing assertion, as clojure.test makes it
        rep (agent/test-report {:type :fail :expected '(= 9 (square 3)) :actual '(not (= 9 6))} "#'ctx-calc-test/square-test")
        block (status-at db "ctx/sq" {'ctx-calc-test/square-test [:red (get (proofs/failures {:reports [rep]}) 'ctx-calc-test/square-test)]})
        by (into {} (map (juxt :test identity)) (:proofs block))]
    (is (= [{:assert (print/code "(= 9 (square 3))") :got (print/code "(= 9 6)")}] (:failures (by 'ctx-calc-test/square-test)))
        "the assertion once, and what it evaluated to")
    ;; task seon.context/an-agent-follows-its-instructions-to-green, item 4: a callee's docstring was about 2.4k
    ;; of the md-scan bench's first request's 6.6k tokens; it is named, its doc a `doc` away
    (is (= [{:var 'ctx-calc/square} {:var 'ctx-ghost/half}]
           (:calls (by 'ctx-calc-test/square-test)))
        "the program functions the red proof calls, each by its name: one in a namespace never loaded too")
    ;; item 5: bench-b11 got its four proofs green and ended on 11 stray reds it could not trace; a red test
    ;; that is no proof shows the source and calls a proof does, by the same producer
    (let [stray (status-at db "ctx/sq" {'ctx-calc-test/square-test [:green] 'ctx-calc-test/cube-test [:green] 'ctx-calc-test/empty-test [:green]
                                        'ctx-calc-test/stray-test [:red]}
                           {:index (fn [] (index-of {"ctx/sq" #{'ctx-calc-test/square-test}} {} {}))})
          r (first (filter #(= 'ctx-calc-test/stray-test (:test %)) (:refused stray)))]
      (is (= :stray-red (:reason r)) (pr-str (:refused stray)))
      (is (= (print/code "(deftest stray-test (is (= 2 (square 1))))") (:source r)) "its source, as a proof's")
      (is (= [{:var 'ctx-calc/square}] (:calls r)) "and what it calls, as a proof's"))
    (is (= {:test 'ctx-calc-test/cube-test :status :undefined} (by 'ctx-calc-test/cube-test))
        "a proof not written yet says so")
    (is (= :unknown (:status (by 'ctx-calc-test/empty-test))) "a proof no report named never ran: the judge's :proof-unknown")
    (let [db (d/db-with db [{:seon.agent/key "b/a1" :seon.agent/task [:seon.task/id "ctx/sq"]}
                            {:seon.process/id "b/a1/1" :seon.process/agent [:seon.agent/key "b/a1"]
                             :seon.process/test-error {:seon.error/type :seon.proofs/test-run-died :seon.error/operation 'seon.agent/test-run :seon.error/message "namespace app-test did not load"}}])
          b (context/status db (:tree (seon.ctx/build db (pdb/eid db :seon.agent/key "b/a1") :b)) {})]
      (is (= {'ctx-calc-test/square-test :not-run 'ctx-calc-test/empty-test :not-run 'ctx-calc-test/cube-test :undefined}
             (into {} (map (juxt :test :status)) (:proofs b)))
          "when the run failed as a whole, a written proof did not run"))))

(deftest an-unresolved-citation-says-what-to-do
  ;; task seon.task/a-task-cites-what-it-needs: a name the plan cites that resolves to
  ;; nothing tells the agent what that means and what to do, not only that it is unresolved
  (let [r (seon.doc/resolve-ref (db-with-program) 'demo/nowhere :doc)]
    (is (:unresolved r))
    (is (string? (:problem r)) "a message beside the name")))

(def ^:private wiki-page
  "# Flow\n\nFlow runs procs.\n\n## Step\n\nA step returns its state and outputs. It never blocks.\n\n## Stop\n\nStop awaits each proc's exit. Then the graph is gone.\n")

(deftest a-task-context-pulls-what-its-plan-cites-at-that-depth
  ;; task seon.doc/a-task-cites-what-it-needs: a section the plan cites reaches the
  ;; context at the depth its delimiter asks for, read from the sections main holds
  (let [db (db-with-program)
        db (d/db-with db (doc/load-tx db "docs" {"docs/flow.md" wiki-page}))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f"
                                               :seon.task/plan "Read `docs/flow.md#step`, and ```docs/flow.md#stop``` whole."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        block (data-of db "demo/f")
        shown (into {} (map (juxt :seon.ref/address identity)) (:sections block))]
    (is (= "A step returns its state and outputs. It never blocks."
           (:seon.ref/lead (shown "docs/flow.md#step"))) "doc depth: the section's lead")
    (is (clojure.string/includes? (str (shown "docs/flow.md#stop")) "Then the graph is gone.") "code depth: the section whole")))

;; docs/references.md#an-entity-cites: a cite of a schema key joins its entry, of a namespace its entity, each drawn
;; from what the cite holds; a path no section of the db names joins nothing, and its part says so
(deftest a-cited-schema-key-and-namespace-show-what-they-are
  (let [db (d/db-with (db-with-program) [{:ns/name 'demo.calc :ns/doc "Adds. More."} {:schema/key :x.s/block :schema/form "[:map [:x.s/from :int]]"}])
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "n"
                                               :seon.task/plan "Read `demo.calc`, `:x.s/block` and `bin/env`."}]))
        t (:tree (ctx/build db (pdb/eid db :seon.task/id "demo/n") :b))
        m (into {} (for [c (vals (:cites t))] [(or (:seon.cite/address c) (:seon.cite/name c)) (seon.render.ui/cite-model {} c)]))]
    (is (= {:cites 'demo.calc :depth :doc :read '(my.agent/help (quote demo.calc)) :doc {:seon.print/prose "Adds. More."}} (m 'demo.calc))
        "a namespace: its doc, and the form that lists its definitions")
    (is (= "[:map [:x.s/from :int]]" (:seon.print/code (:form (m :x.s/block)))) "a schema key: its form as written")
    (is (str/includes? (:seon.print/code (:change (m :x.s/block))) "(my.schema/register! :x.s/block") "and how it changes at the REPL")
    (is (:unresolved (m "bin/env")) "a file: it joins nothing, and its part says so")))

(deftest an-agent-reads-its-conversation-so-far
  ;; a live DeepSeek agent re-read the same Datahike source every other turn because each
  ;; context showed only the previous turn's evaluations (2026-10-21); a REPL keeps its
  ;; scrollback. Each eval renders its own part (seon.context/eval-messages): the assistant message with
  ;; the call, then the response as a tool message; the provider's format folds a reply's calls
  ;; into one assistant message (docs/render.md, the request is a render)
  (let [ev (fn [id call prose value] {:seon.eval/id id :seon.eval/by "a1" :seon.eval/record (cond-> {:code (str "(" id ")") :response {:handle (symbol "result" id) :value value} :call-id (str "c-" id) :call call} prose (assoc :content prose))})
        ms (into [] (mapcat #(context/eval-messages % {})) [(ev "e1" 0 "R0" "E1") (ev "e2" 0 nil "E2") (ev "e3" 1 nil "E3")])]
    (is (= ["assistant" "tool" "assistant" "tool" "assistant" "tool"] (mapv :role ms)) "each eval its call and its response")
    (is (= {:role "assistant" :content "R0" :tool-calls [{:id "c-e1" :code "(e1)"}] :call 0} (first ms)))
    (is (= {:role "tool" :tool-call-id "c-e1" :content "{:handle result/e1, :value \"E1\"}"} (second ms)))
    (let [wire (seon.model/wire-messages true ms)]
      (is (= ["assistant" "tool" "assistant" "tool" "tool"] (mapv :role wire)) "a reply's two calls are one assistant message")
      (is (= ["c-e2" "c-e3"] (mapv :id (:tool_calls (nth wire 2)))))
      (is (= ["c-e2" "c-e3"] (mapv :tool_call_id (subvec wire 3 5))) "each call answered, in order")))
  (testing "a user's form is one user message carrying its response"
    (is (= [{:role "user" :content "{:by \"user\" :code \"(+ 1 2)\" :response {:handle result/e4, :value 3}}"}]
           (context/eval-messages {:seon.eval/by "user" :seon.eval/record {:code "(+ 1 2)" :response {:handle 'result/e4 :value 3}}} {})))))

;; ---- context v2: a stable prefix, each var at its citation's depth, the helpers -----------

(defn- program-facts
  "Program facts for [sym src refs] triples: each var, the form that defines it with its
  source and the vars it names (:form/refs), and its namespace."
  [defs]
  (vec (mapcat (fn [[v src refs]]
                 ;; its doc and contract, as the import records them from its source
                 [(let [[_ _ & more] (read-string src) d (when (string? (first more)) (first more))
                        c (some #(when (map? %) (:malli/schema %)) more)]
                    (cond-> {:db/id (str v) :var/name v :var/ns (str "ns " (namespace v)) :var/form (str "form " v)}
                      d (assoc :var/doc d) c (assoc :var/contract (pr-str c))))
                  (cond-> {:db/id (str "form " v) :form/id (str v " " src) :form/src src :form/ns (str "ns " (namespace v)) :form/order 0
                           :form/defines [(str v)]}
                    (seq refs) (assoc :form/refs (vec refs)))
                  (ns-fact v)])
               defs)))

(deftest a-function-without-a-contract-is-refused
  ;; the status reads the branch's diff against its base (:seon.agent/base-commit): a function the
  ;; branch defined without a contract is a reason, named once
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (program-facts '[[demo/f "(defn f \"Doubles.\" [x] (* 2 x))" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p" :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:seon.agent/key "b/a1" :seon.agent/task [:seon.task/id "demo/f"] :seon.agent/base-commit (d/commit-id @conn)}
                        {:var/name 'demo-test/f-test :seon.test/status :green}])
      (d/transact conn (program-facts '[[demo/helper "(defn helper [x] x)" []]]))
      (d/transact conn [{:var/name 'demo/helper :var/kind :fn}])
      (let [db @conn st (context/status db (:tree (seon.ctx/build db (pdb/eid db :seon.agent/key "b/a1") :b)) {})]
        (is (= ['demo/helper] (:without-a-contract st)))
        (is (= [{:reason :contract-gap :vars ['demo/helper]}] (:refused st)))))))

(deftest a-red-main-was-red-on-too-is-mains
  ;; docs/render.md#how-the-work-stands: a session ended refused over a test red on main as on its branch,
  ;; and was told to register that test, one it never wrote, as its own proof. A test red at the branch's merge base
  ;; is main's red; only a test the branch wrote or changed is offered the form that makes it a proof
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (program-facts '[[demo-test/env-test "(deftest env-test (is (pinned?)))" []]
                                        [demo-test/kept-test "(deftest kept-test (is (= 1 1)))" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p" :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:var/name 'demo-test/env-test :seon.test/status :red} {:var/name 'demo-test/kept-test :seon.test/status :green}])
      (d/transact conn [{:seon.agent/key "b/a1" :seon.agent/task [:seon.task/id "demo/f"] :seon.agent/base-commit (d/commit-id @conn)}])
      (d/transact conn (program-facts '[[demo-test/own-test "(deftest own-test (is (= 1 2)))" []]]))
      (d/transact conn [{:var/name 'demo-test/f-test :seon.test/status :green} {:var/name 'demo-test/own-test :seon.test/status :red}
                        {:var/name 'demo-test/kept-test :seon.test/status :red}])
      (let [db @conn
            st (context/status db (:tree (seon.ctx/build db (pdb/eid db :seon.agent/key "b/a1") :b))
                               {:index (fn [] (index-of {"demo/f" #{'demo-test/f-test}} {} {}))})
            by (into {} (map (juxt :test identity)) (:refused st))]
        (is (= #{'demo-test/own-test 'demo-test/kept-test} (set (keys by))) (str "main's own red is no reason: " (pr-str (:refused st))))
        (is (= '(my.task/update! :seon.task/proofs conj (quote demo-test/own-test)) (:do (by 'demo-test/own-test)))
            "a red test the branch wrote is offered the form that makes it a proof")
        (is (not (contains? (by 'demo-test/kept-test) :do)) "main's test the work turned red is not")))))

(defn- turn-of
  "A recorded model turn n of run demo/f#1 with basis b: one call and its answer, and its model call
  (the two maps of one transaction)."
  [b n]
  [{:db/id "turn" :seon.turn/id (str "demo/f#1/" n) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/agent {:seon.agent/key "b/a1"} :seon.turn/n n :seon.turn/origin :model
    :seon.turn/reply (str "reply " n) :seon.turn/calls [{:id (str "call-" n) :code (str "(+ " n " 1)") :evals (str (inc n))}]}
   {:seon.model-call/id (str "demo/f#1/" n) :seon.model-call/turn "turn" :seon.model-call/basis b :seon.model-call/forms 1 :seon.model-call/errors 0 :seon.model-call/red 1 :seon.model-call/ms 1}])

(deftest the-prefix-stays-byte-identical-across-turns
  ;; task seon.context/a-stable-prefix-is-sent-once: a provider caches a byte-identical message
  ;; prefix, so each request starts with the whole of the one before, even after the program the
  ;; task cites, or the task's plan, changed: the page is read whole at the session's first turn
  ;; and as what changed of it at each later one, by part key (docs/render.md
  ;; §7), each re-derived from the branch at that turn's commit
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (program-facts '[[demo/f "(defn f \"Doubles.\" [x] (* 2 x))" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make ```demo/f``` triple."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:seon.run/id "demo/f#1" :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/f"}}}])
      (let [call! (fn [n] (d/transact conn [{:seon.eval/id (str "e" n) :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key "b/a1"}
                                             :seon.eval/record {:code (str "(+ " n " 1)") :response {:handle (symbol "result" (str "e" n)) :value (inc n)}
                                                                :run "demo/f#1" :call-id (str "c" n) :call 0 :prose (str "Step " n ".")}}]))
            ;; as seon.turn goes: the turn recorded, its request made on the db that holds it, what it sent added
            request (fn [n] (let [b {:commit (d/commit-id @conn) :task "demo/f" :run "demo/f#1" :turn n :max-turns 4 :origin :model :agent "b/a1"}
                                  _ (d/transact conn (turn-of b n))
                                  {:keys [messages sent]} (context/render @conn b)]
                              (d/transact conn [{:seon.model-call/id (str "demo/f#1/" n) :seon.model-call/sent sent}])
                              (is (= messages (into [] (mapcat second) (context/parts-of @conn sent))) (str "turn " n " reads back as it was sent"))
                              (call! n)
                              messages))
            r0 (request 0)
            _ (d/transact conn (program-facts '[[demo/f "(defn f \"Triples.\" [x] (* 3 x))" []]]))
            r1 (request 1)
            _ (d/transact conn [[:db/add [:seon.task/id "demo/f"] :seon.task/plan "Make ```demo/f``` quadruple."]])
            r2 (request 2)]
        (is (= r0 (subvec r1 0 (count r0))) "turn 1 starts with turn 0's request, whole")
        (is (= r1 (subvec r2 0 (count r1))) "turn 2 starts with turn 1's request, whole")
        (is (= (context/system-text @conn) (:content (first r0))) "the system message is the system prompt as main holds it")
        (is (str/starts-with? (:content (first r0)) "# You are a Seon agent\n\nYou work at a Clojure REPL") "the page, from its sections")
        (is (= ["system" "user"] (mapv :role r0)) "the first request: the system prompt, then the page whole")
        (is (every? #(re-find % (:content (second r0))) [#"cited-\d+-demo_2Ff" #"Doubles\." #"status-\d+"]) "the page whole, by component key: how the work stands too")
        (is (re-find #"cited-\d+-demo_2Ff" (:content (peek r1))) "the cited var's edit: its component, by its key")
        (is (str/includes? (:content (peek r1)) "Triples."))
        (is (not (re-find #"task-data-\d+" (:content (peek r1)))) "a component that did not change is not told again")
        (is (not (re-find #"status-\d+" (:content (peek r1)))) "a turn taken moved nothing of how the work stands: not told again")
        (is (str/includes? (:content (peek r2)) "quadruple") "the plan changed mid-run: the task's part in the next diff")
        (let [db @conn
              read (:messages (context/render db {:commit (d/commit-id db) :task "demo/f" :run "demo/f#1" :turn 2 :max-turns 1
                                                  :origin :outside :agent "b/a1"}))]
          (is (= ["system" "user"] (mapv :role read)) "an outside agent's read: the system prompt and the page whole")
          (is (str/includes? (:content (peek read)) "quadruple")))))))

(deftest each-section-is-told-once-on-the-page
  ;; the old request sent the task, its references and its sections in the first turn's context
  ;; and again in the current block, every turn; on the page each is one part: the plan in
  ;; [:task], a cited var's source in its own part, the proofs' statuses in the status
  (ts/test-branch!
    (fn [_ conn]
      ;; its proof written: an unwritten one is told what to assert in the plan's words, the plan again
      (d/transact conn (program-facts '[[demo/g "(defn g \"Halves.\" [x] (/ x 2))" []] [demo-test/f-test "(deftest f-test (is (= 1 (g 2))))" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make ```demo/g``` exact."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:seon.run/id "demo/f#1" :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/f"}}}
                        {:db/id "turn" :seon.turn/id "demo/f#1/0" :seon.turn/run [:seon.run/id "demo/f#1"] :seon.turn/agent {:seon.agent/key "b/a1"} :seon.turn/n 0 :seon.turn/origin :model}
                        {:seon.model-call/id "demo/f#1/0" :seon.model-call/turn "turn"
                         :seon.model-call/basis {:commit (d/commit-id @conn) :task "demo/f" :run "demo/f#1" :turn 0 :max-turns 4 :origin :model}}
                        {:var/name 'demo-test/f-test :seon.test/status :red}])
      (let [db @conn
            a (pdb/eid db :seon.agent/key "b/a1")
            page (context/drawn-parts db (ctx/build db a :b))
            text (print/text (vary-meta page assoc :seon.print/full true))
            times (fn [s] (count (re-seq (re-pattern (java.util.regex.Pattern/quote s)) text)))]
        (is (some #(str/starts-with? % "status-") (keys page)) "how the work stands is on the page")
        (is (= 1 (times "Make ```demo/g``` exact.")) "the plan once")
        (is (= 1 (times "(defn g \"Halves.\"")) "a cited source once, as code: its quotes as written")
        (is (= 1 (count (re-seq #":test demo-test/f-test,\s+:status :fail" text))) "the proofs' statuses once, in the status")))))

(deftest a-cited-var-shows-at-its-citations-depth
  ;; task seon.context/a-cited-var-shows-at-its-citation-depth: the delimiter picks the depth
  ;; for a var as it does for a section (docs/references.md#the-delimiter-picks-the-depth)
  ;; the vars are the db's alone (no namespace of them in this process): what each shows is read
  ;; from its facts (seon.doc/resolve-ref)
  (let [db (d/db-with (db-with-program)
                      (program-facts '[[ctx-depth/sq "(defn sq \"Squares x. Used by area.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))" []]
                                       [ctx-depth/cube "(defn cube \"Cubes x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x x))" []]
                                       [ctx-depth/tri "(defn tri \"Triples x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))" []]]))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "d"
                                               :seon.task/plan "Build on ctx-depth/sq, `ctx-depth/cube` and ```ctx-depth/tri```."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        ;; the rule lives in doc/resolve-ref (the task's data shows its write set's vars whole: their source)
        by (into {} (for [r (seon.ref/references db (:seon.task/plan (task/task db "demo/d")))]
                      [(:seon.ref/name r) (seon.doc/resolve-ref db (:seon.ref/name r) (:seon.ref/depth r))]))]
    (is (= {:symbol 'ctx-depth/sq :arglists '([x]) :summary "Squares x."} (by 'ctx-depth/sq)) "bare: arglists and the first sentence")
    (is (= {:symbol 'ctx-depth/cube :arglists '([x]) :doc {:seon.print/prose "Cubes x."} :contract [:=> [:cat :int] :int]} (by 'ctx-depth/cube))
        "one backtick: arglists, doc and contract")
    (is (= {:symbol 'ctx-depth/tri :source (print/code "(defn tri \"Triples x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))")
            :change (print/code "(seon.repl/edit! 'ctx-depth/tri \"old text, exactly once\" \"new text\")")} (by 'ctx-depth/tri))
        "three backticks: the source, as code, and the form that changes a part of it")))

(deftest a-long-source-is-cut-by-the-printer-where-it-is-shown
  ;; the clipping audit (2026-10-25, P4) and the owner, 2026-10-26: "we don't need to and
  ;; shouldn't be printing thousands of items anywhere. The value printer is made to show as much
  ;; of the structure that is possible and to have ellipses for data that just continues so the
  ;; agent can know what's there and can dig into it". A cited source is whole in its data, never
  ;; swapped for its doc by size; its part is that data (docs/render.md#a-part-answers-data), and the
  ;; one printer cuts it where it is shown, for the page (cited-html) as for the model, the cut
  ;; naming the form that reads the rest
  (binding [*ns* *ns*]
    (eval '(ns ctx-big))
    (eval '(defn big "Does much." {:malli/schema [:=> [:cat] :int]} [] 1)))
  (let [src (str "(defn big \"Does much.\" [] " (str/join " " (repeat 8000 "(inc 1)")) ")")
        db (d/db-with (db-with-program) (program-facts [['ctx-big/big src []]]))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "r" :seon.task/plan "Make ```ctx-big/big``` fast."}]))
        r (seon.doc/resolve-ref db 'ctx-big/big :code)
        ;; the task's cite as ctx holds it: the var it names, with its form
        part (with-meta {:seon.cite/name 'ctx-big/big :seon.cite/depth :code :var {:var/name 'ctx-big/big :form {:form/src src}}} {:seon.render/schema :seon.cite/cite-fact})
        html (seon.web.html/html (seon.render/html {:schema :seon.cite/cite-fact :as :block} part))
        view (seon.render.ui/cite-model {} part)
        model (print/text view)]
    (is (= {:symbol 'ctx-big/big :source (print/code src)} (select-keys r [:symbol :source])) "the source whole as code, never swapped for its doc by size")
    (is (= (print/code src) (:source view)) "the part holds it whole, as data")
    (is (< (:context seon.print/budgets) (select/estimate src)) "a source over the context's budget")
    (is (< (count html) (count src)) "the page cuts it where it is written")
    (is (str/includes? html "my.agent/help") "its cut names the form that reads the rest")
    (is (< (select/estimate model) (select/estimate src)) (str "the model reads it cut too, at the printer's budget: " (select/estimate model) " tokens"))
    (is (str/includes? model "(clojure.repl/source ctx-big/big)") "and the cut names the form that reads the rest")))

;; a made unit vector, dim i weighted as a Matryoshka model's are (seon.vector-test/vectors)
(defn- made-vector [seed]
  (let [v (mapv (fn [i] (* (Math/exp (/ (- i) 96.0)) (Math/sin (* (inc seed) (inc i) 0.37)))) (range 768))
        n (Math/sqrt (reduce + (map #(* % %) v)))]
    (mapv #(/ % n) v)))

(deftest unresolved-names-share-one-problem
  ;; a measured context (2026-10-22) repeated the same 280-character problem under each name
  ;; that resolves to nothing; it is said once, beside every such name
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "u" :seon.task/plan "Use `demo/f`, `demo/x` and `demo/y`."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        refs (:write-set (data-of db "demo/u"))]
    (is (= ['demo/f] (keep :symbol refs)) "the resolved name as itself")
    (is (= {:unresolved ['demo/x 'demo/y] :problem (:unresolved seon.ref/problem-messages)} (last refs)) "the others, one problem")))

(deftest an-owners-message-reaches-the-next-turn
  ;; owner, 2026-10-23 (parity row 73): the page's message bar sends the owner's words through
  ;; root's delivery; the turn that takes them writes the message fact on its branch, so the
  ;; conversation holds it in tx order (seon.context/records), rendered by the message fact's
  ;; own messages (seon.context/eval-messages; docs/render.md)
  (let [[m] (context/message-messages {:seon.message/from "owner" :seon.message/body "use demo/g, not a new helper"} {})]
    (is (= "user" (:role m)))
    (is (str/includes? (:content m) "use demo/g, not a new helper"))
    (is (str/includes? (:content m) ":from \"owner\"") "who sent it"))
  (is (not (contains? (let [db (db-with-program) t (task-f db)] (context/task-data db t (task/task-cites db (:seon.task/id t)))) :messages)) "never in the task's data"))
(deftest an-error-shows-what-its-contract-says
  ;; a contract invalid is data (its problems, the fix): the response shows that, not only
  ;; the message (the step E dry run's agent read "invalid-input: its arguments do not
  ;; satisfy its contract" with no word of which, 2026-10-22)
  (let [em {:seon.error/type :seon.instrument/contract-error :seon.error/operation 'seon.e2e/agents-reused
            :seon.error/member :malli.core/invalid-output
            :seon.error/message "seon.e2e/agents-reused invalid-output: its value does not satisfy its contract"
            :seon.error/fix "Fix seon.e2e/agents-reused (the producer), or its contract."
            :seon.error/problems [{:path [0] :in [0] :expected :keyword :value {:seon.print/code "nil"}}]}
        text (print/response-text {:handle 'result/e1 :ex em})]
    (is (str/includes? text ":expected :keyword") text)
    (is (str/includes? text "Fix seon.e2e/agents-reused") text)
    (is (= 1 (count (re-seq #"its value does not satisfy" text))) "the message once")))

(deftest a-turns-reasoning-goes-back-with-its-reply
  ;; DeepSeek's thinking mode with tools needs each earlier reply's reasoning_content sent back
  ;; (a 400 otherwise, measured: docs/model.md): the reply's first eval carries it,
  ;; exactly as returned, and the provider's format sends it unless replay-reasoning is false
  (let [ms (context/eval-messages {:seon.eval/by "a1" :seon.eval/record {:code "(f 3)" :response {:value 9} :call-id "c1" :call 0 :prose "f triples." :reasoning "Square it first."}} {})
        assistant (first ms)]
    (is (= "Square it first." (:reasoning assistant)))
    (is (not-any? :reasoning (rest ms)))
    (is (= "Square it first." (:reasoning_content (first (seon.model/wire-messages true ms)))))
    (is (not (contains? (first (seon.model/wire-messages false ms)) :reasoning_content)) "replay false leaves it out")))
(deftest an-unknown-proof-says-what-to-write
  ;; a proof with no result: the reason names the deftest to write in its namespace and how
  ;; to run it (the dry run's agents read `{:reason :proof-unknown}` and searched for 25 turns)
  (let [db (d/db-with (db-with-program) (task/create-tx (db-with-program) [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p"
                                                                            :seon.task/proofs #{'demo-test/unwritten-test}}]))
        b (status-at db "demo/f" {})
        r (first (:refused b))]
    (is (= [{:test 'demo-test/unwritten-test :status :undefined}] (:proofs b)) "a proof not written says so")
    (is (= :proof-unknown (:reason r)))
    (is (= 'demo-test/unwritten-test (:test r)))
    (is (re-find #"deftest unwritten-test" (pr-str (:do r))) (pr-str r))
    ;; written, then judged by the runner whose results the done rule reads, never clojure.test's own run
    (is (re-find #"\(my.agent/prove\)" (pr-str (:do r))) (pr-str r)))
  ;; a proof written but with no result was offered the same template, which overwrites it
  ;; (the fixture's program defines demo-test/f-test)
  (let [r (first (:refused (status-at (db-f (db-with-program)) "demo/f" {})))]
    (is (= {:reason :proof-unknown :test 'demo-test/f-test :written true :do '(my.agent/prove)} r) "a written proof is run, never written again")))

(deftest a-cited-task-shows-its-plan-at-that-depth
  ;; wiki audit 2026-10-23 R4: a plan citing another task by its ns/name sees that task's
  ;; plan as a section at the citation's depth, not an unresolved name
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo.other :seon.task/name "g"
                                               :seon.task/plan "G is defined first. Then f calls it."
                                               :seon.task/proofs #{'demo-test/g-test}}
                                              {:seon.task/ns 'demo :seon.task/name "f"
                                               :seon.task/plan "Read `demo.other/g` before you start."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        block (data-of db "demo/f")
        shown (into {} (map (juxt :seon.ref/address identity)) (:sections block))
        ;; the page's part: the cite joins the task by its id (:seon.cite/task), drawn by its plan
        cite (some #(when (= 'demo.other/g (:seon.cite/name %)) %) (vals (:cites (:tree (ctx/build db (pdb/eid db :seon.task/id "demo/f") :b)))))
        part (seon.render.ui/cite-model {} cite)]
    (is (= "G is defined first. Then f calls it." (:seon.ref/lead (shown "demo.other/g"))) "doc depth: the task's plan")
    (is (= [] (:write-set block)) "and not a var of the write set, nor an unresolved name")
    (is (= "demo.other/g" (get-in cite [:task :seon.task/id])) "the cite joins the task by its id")
    (is (str/includes? (:seon.print/prose (:text part)) "G is defined first. Then f calls it.") "and its part shows the plan")
    (is (not (:unresolved part)) "not an unresolved name")))


(defn- read-back
  "What model call entity e on db sent, read back from its records (the refs it recorded, each by its own
  render: seon.context/parts-of), as one vector of messages."
  [db e] (into [] (mapcat second) (context/parts-of db (:seon.model-call/sent (d/pull db [:seon.model-call/sent] e)))))

(defn- sent-turns
  "A run of two model turns as seon.turn records them (owner's ruling 2026-10-24: each turn
  records what it sent as refs, :seon.model-call/sent), with the task's plan changed after turn 0 and
  again after turn 1. Answers [db [r0 r1] [e0 e1]]: the requests' messages as sent, and the turns."
  [conn]
  (wiki! conn)
  (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
  (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                           :seon.task/proofs #{'demo-test/f-test}}]))
  (d/transact conn [{:seon.run/id "demo/f#1" :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/f"}}}])
  (let [ev! (fn [id by code value & [more]] (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "b/a1"}
                                                               :seon.eval/record (merge {:code code :response {:handle (symbol "result" id) :value value} :run "demo/f#1"} more)}]))
        turn! (fn [n more]
                (let [b {:commit (d/commit-id @conn) :task "demo/f" :run "demo/f#1" :turn n :max-turns 4
                         :origin :model :agent "b/a1"}
                      _ (d/transact conn [(merge {:db/id "turn" :seon.turn/id (str "demo/f#1/" n) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/agent {:seon.agent/key "b/a1"} :seon.turn/n n
                                                  :seon.turn/origin :model}
                                                 more)
                                          {:seon.model-call/id (str "demo/f#1/" n) :seon.model-call/turn "turn" :seon.model-call/basis b :seon.model-call/forms 1
                                           :seon.model-call/errors 0 :seon.model-call/red 1 :seon.model-call/ms 1}])
                      {:keys [messages sent]} (context/render @conn b)]
                  (d/transact conn [{:seon.model-call/id (str "demo/f#1/" n) :seon.model-call/sent sent}])
                  messages))
        plan! (fn [p] (d/transact conn [{:seon.task/id "demo/f" :seon.task/plan p}]))
        r0 (turn! 0 {})
        _ (ev! "e2" "a1" "(f 1)" 1 {:call-id "call-0" :call 0 :prose "I call f." :reasoning "why"})
        _ (plan! "Make f triple.")
        r1 (turn! 1 {})
        _ (plan! "Later.")
        ;; and the system prompt changes: a turn's system prompt is its section as of the turn's t
        _ (d/transact conn [{:db/id (seon.ref/page-top @conn context/system-page) :seon.doc/text "Changed after the turns."}])
        db @conn
        e-of (fn [id] (d/q '[:find ?e . :in $ ?id :where [?e :seon.model-call/id ?id]] db id))]
    [db [r0 r1] [(e-of "demo/f#1/0") (e-of "demo/f#1/1")]]))

(deftest a-sessions-next-run-reads-its-earlier-runs
  ;; design agent-loop section 1 (owner, 2026-10-25: "a resume where the agent wakes and responds
  ;; to a message it should have the same previous context"): the conversation is the session's,
  ;; not the run's. An agent's second run reads the evals of its first and the messages its first
  ;; took, in tx order; another agent's records stay out
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (let [ev! (fn [id agent run code] (d/transact conn [{:seon.eval/id id :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key agent}
                                                           :seon.eval/record {:code code :response {:handle (symbol "result" id) :value 1} :run run}}]))
            run! (fn [run agent] (d/transact conn [{:seon.run/id run :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key agent :seon.agent/task {:seon.task/id "demo/f"}}}]))
            _ (run! "demo/f#1" "b/a1")
            _ (ev! "e1" "b/a1" "demo/f#1" "(f 1)")
            _ (d/transact conn (update (task/message-tx "m1" :message "user" :task-demo-f "use g") 0 assoc :seon.message/agent {:seon.agent/key "b/a1"}))
            _ (run! "demo/f#2" "b/a1")
            _ (ev! "e2" "b/a1" "demo/f#2" "(f 2)")
            _ (ev! "x1" "b/a9" "other#1" "(other)")
            db @conn
            a (:db/id (d/entity db [:seon.agent/key "b/a1"]))
            ids (mapv (fn [[_ v]] (or (:seon.eval/id v) (:seon.message/id v))) (context/records db a))]
        (is (= ["e1" "m1" "e2"] ids) "the first run's eval and message, then the second's, and no other agent's")))))

(deftest a-sessions-next-stage-keeps-the-last-request-as-its-prefix
  ;; live finding A1 (2026-10-25): an implement continuing its design's session was sent its own
  ;; task data at the top, before the design's conversation (1% of 33k tokens cached), with nothing
  ;; marking the switch. There is no task data at the top: each turn's page is a part of the
  ;; conversation, and the next stage's task comes as root's message at the boundary
  ;; (seon.reconcile/stage-boundary), taken before the new stage's first turn (seon.turn/take!),
  ;; and the conversation is append-only, so the second stage's request is the first's whole, then
  ;; the boundary, then what changed of the page
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "d" :seon.task/plan "Design d." :seon.task/proofs #{'demo-test/d-test}}
                                              {:seon.task/ns 'demo :seon.task/name "i" :seon.task/plan "Implement d." :seon.task/proofs #{'demo-test/i-test}}]))
      (let [ev! (fn [id by run]
                  (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "b/a1"}
                                     :seon.eval/record {:code (str "(" id ")") :response {:handle (symbol "result" id) :value 1} :line id :run run
                                                        :call-id (str "c-" id) :call 0 :prose "Step."}}]))
            run! (fn [run task] (d/transact conn [{:seon.run/id run :seon.run/task [:seon.task/id task] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id task}}}]))
            basis (fn [task run] {:commit (d/commit-id @conn) :task task :run run :turn 0 :max-turns 9 :origin :model
                                  :agent "b/a1"})
            ;; as seon.turn goes: the turn recorded, then its request on the db that holds it
            turn! (fn [run b] (d/transact conn [{:db/id "turn" :seon.turn/id (str run "/0") :seon.turn/run {:seon.run/id run} :seon.turn/agent {:seon.agent/key "b/a1"} :seon.turn/n 0 :seon.turn/origin :model}
                                                {:seon.model-call/id (str run "/0") :seon.model-call/turn "turn"
                                                 :seon.model-call/basis b :seon.model-call/forms 1 :seon.model-call/errors 0 :seon.model-call/red 1 :seon.model-call/ms 1}])
                    (let [r (context/render @conn b)] (d/transact conn [{:seon.model-call/id (str run "/0") :seon.model-call/sent (:sent r)}]) r))
            _ (run! "demo/d#1" "demo/d")
            _ (ev! "e0" "a1" "demo/d#1")
            b0 (basis "demo/d" "demo/d#1")
            first-stage (turn! "demo/d#1" b0)
            boundary "demo/d is done. Your task now is demo/i, in this same session: what you did so far stays above.\n\nIts plan: Implement d."
            _ (run! "demo/i#1" "demo/i")
            _ (d/transact conn (update (task/message-tx "m1" :stage "root" :task-demo-i boundary) 0 assoc :seon.message/agent {:seon.agent/key "b/a1"}))
            second-stage (turn! "demo/i#1" (basis "demo/i" "demo/i#1"))
            ms0 (:messages first-stage) ms1 (:messages second-stage)
            refs (mapv #(vec (take 2 %)) (:sent second-stage))
            at (fn [ref] (.indexOf refs ref))]
        (is (= ms0 (subvec ms1 0 (min (count ms1) (count ms0)))) "the first stage's request is the second's exact prefix")
        (is (= (pop (:sent first-stage)) (subvec (:sent second-stage) 0 (dec (count (:sent first-stage))))) "and records it sent so (its hashes aside, which the next request replaces)")
        (is (< (dec (dec (count (:sent first-stage)))) (at [:message "m1"]) (- (count refs) 2)) "then the boundary, root's message, before the new stage's first parts")
        (is (= :parts (first (peek (pop (:sent second-stage))))) "the new stage's parts: what changed since the first stage's turn")
        (is (some #(str/includes? (str (:content %)) "Your task now is demo/i") (drop (count ms0) ms1)) "the boundary says the new task")))))

(deftest a-run-printed-once-sends-what-a-cold-read-sends
  ;; the branch review (2026-10-25, F6, F7): a request threaded through what the run's earlier
  ;; requests printed (:printed) equals one made with none, turn by turn; what each turn recorded
  ;; it sent reads back as the same bytes (the fold assertions went with the fold, 2026-10-29)
  (ts/test-branch!
    (fn [_ conn]
      ;; only the system prompt every request opens with (seon.context/system-page), not the checkout's wiki
      (d/transact conn (doc/load-tx @conn "docs" {context/system-page (slurp (str (seon.config/checkout) "/" context/system-page))}))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g."
                                               :seon.task/proofs #{'demo-test/g-test}}]))
      (d/transact conn [{:seon.run/id "demo/g#1" :seon.run/task [:seon.task/id "demo/g"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
      (let [rows (vec (for [i (range 20)] {:row i :name (str "row-" i)}))
            ev! (fn [id by value & [more]]
                  (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "b/a1"}
                                     :seon.eval/record (merge {:code (str "(" id ")") :response {:handle (symbol "result" id) :value value}
                                                               :line (str id) :run "demo/g#1"} more)}]))
            basis (fn [n] {:commit (d/commit-id @conn) :task "demo/g" :run "demo/g#1" :turn n :max-turns 9
                           :origin :model :agent "b/a1"})
            turns (loop [n 0 printed nil out []]
                    (if (= n 4)
                      out
                      ;; as a turn goes: its request, then its call's eval
                      (do (let [b (basis n)
                                _ (d/transact conn [{:db/id "turn" :seon.turn/id (str "demo/g#1/" n) :seon.turn/run {:seon.run/id "demo/g#1"} :seon.turn/agent {:seon.agent/key "b/a1"} :seon.turn/n n
                                                     :seon.turn/origin :model}
                                    {:seon.model-call/id (str "demo/g#1/" n) :seon.model-call/turn "turn" :seon.model-call/basis b
                                     :seon.model-call/forms 1 :seon.model-call/errors 0 :seon.model-call/red 1 :seon.model-call/ms 1}])
                                warm (context/render @conn b {:printed printed})
                                cold (context/render @conn b)]
                            (d/transact conn [{:seon.model-call/id (str "demo/g#1/" n) :seon.model-call/sent (:sent warm)}])
                            (ev! (str "e" n) "a1" (if (odd? n) rows (vec (range (* 10 (inc n))))) {:call-id (str "c" n) :call 0 :prose (str "Step " n ".")})
                            (recur (inc n) (:printed warm) (conj out [n warm cold]))))))
            db @conn]
        (doseq [[n warm cold] turns]
          (is (= (:messages cold) (:messages warm)) (str "turn " n ": printed once equals printed cold"))
          (let [tok #(select/estimate (apply str (mapcat (fn [m] (cons (:content m) (map :code (:tool-calls m)))) %)))
                ms (:messages warm) split (context/sent-split (context/parts-of @conn (:sent warm)))]
            (is (= (tok [(first ms)]) (:system split)) (str "turn " n ": the system prompt's tokens, sized from what it recorded it sent"))
            (is (pos? (:page split)) "the page's")
            (is (= (pos? n) (pos? (:conversation split))) "and the conversation's, once there is one"))
          (is (= (:sent cold) (:sent warm)))
          (let [e (d/q '[:find ?e . :in $ ?id :where [?e :seon.model-call/id ?id]] db (str "demo/g#1/" n))]
            (is (= (:messages warm) (into [] (mapcat second) (context/parts-of db (:seon.model-call/sent (d/pull db [:seon.model-call/sent] e)))))
                (str "turn " n ": its read-back is the bytes it sent"))))
        (is (= 4 (count turns)))))))

(deftest a-turn-records-what-it-sent
  ;; task seon.context/a-turn-records-what-it-sent (A): a turn records the records its request
  ;; rendered, in order; read back, each by its own render as of the turn (the help page's
  ;; section and the task as of their t), they are the messages it sent, though the task changed
  ;; after it: nothing is built again
  (ts/test-branch!
    (fn [_ conn]
      (let [[db [r0 r1] [e0 e1]] (sent-turns conn)
            sent #(:seon.model-call/sent (d/pull db [:seon.model-call/sent] %))]
        (is (= [:section :parts :hashes] (mapv first (sent e0))) "turn 0: the system prompt, the page's parts whole, their hashes")
        (is (pos-int? (second (first (sent e0)))) "the system prompt's own section, by entity")
        (is (= [:section :parts :eval :parts :hashes] (mapv first (sent e1))) "turn 1: turn 0's parts, its call's eval, then what changed of the page")
        (is (str/includes? (str (peek r1)) "Make f triple.") "the plan changed after turn 0: in turn 1's page diff")
        (is (= r0 (read-back db e0)) "turn 0's messages, read from its records")
        (is (= r1 (read-back db e1)) "turn 1's, though the plan changed after both")
        (is (not (str/includes? (str (read-back db e1)) "Later.")) "the task as it was sent, not as it is")
        (is (str/includes? (context/system-text db) "Changed after the turns.") "the system prompt changed after them")
        (is (not (str/includes? (str (read-back db e0)) "Changed after the turns.")) "the system prompt as it was sent")))))

(deftest a-turn-records-one-turns-messages
  ;; what a turn writes is its refs, one a record it sent: the record grows with that turn's
  ;; messages, never with their text: a few dozen bytes a ref, whatever the records hold
  (ts/test-branch!
    (fn [_ conn]
      (let [[db [_ r1] [e0 e1]] (sent-turns conn)
            sent #(:seon.model-call/sent (d/pull db [:seon.model-call/sent] %))
            bytes #(count (seon.edn/encode (sent %)))]
        (is (= (dec (count (sent e1))) (- (count r1) (count (filter #(= "tool" (:role %)) r1)))) "one ref a record: a model eval's call and answer are one record (and the hashes after them)")
        (is (< (bytes e1) (* 250 (count (sent e1)))) (str "no text in the record: " (bytes e1) " bytes for " (count (sent e1)) " refs, the hashes the largest"))))))

(deftest the-request-is-a-conversation-of-evals-and-the-page
  ;; docs/render.md: each turn's prose and eval calls with their responses
  ;; as tool messages (read from the eval records), the reasoning as returned, and at each model
  ;; turn the page: whole at the first, what changed of it after (a verdict moves the status
  ;; alone); the prefix stays byte-identical
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:seon.run/id "demo/f#1" :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/f"}}}])
      (let [ev! (fn [id by code value & [more]] (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "b/a1"}
                                                                   :seon.eval/record (merge {:code code :response {:handle (symbol "result" id) :value value} :run "demo/f#1"} more)}]))
            ;; as seon.turn goes: the turn recorded, then its request on the db that holds it
            request (fn [n more] (let [b {:commit (d/commit-id @conn) :task "demo/f" :run "demo/f#1" :turn n :max-turns 4
                                          :origin :model :agent "b/a1"}]
                                   (d/transact conn [(merge {:db/id "turn" :seon.turn/id (str "demo/f#1/" n) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/agent {:seon.agent/key "b/a1"} :seon.turn/n n :seon.turn/origin :model} more)
                                                     {:seon.model-call/id (str "demo/f#1/" n) :seon.model-call/turn "turn" :seon.model-call/basis b :seon.model-call/forms 1
                                                      :seon.model-call/errors 0 :seon.model-call/red 1 :seon.model-call/ms 1}])
                                   (let [{:keys [messages sent]} (context/render @conn b)]
                                     (d/transact conn [{:seon.model-call/id (str "demo/f#1/" n) :seon.model-call/sent sent}])
                                     [b messages])))
            [b0 r0] (request 0 {:seon.turn/reply "I call f." :seon.turn/reasoning "why" :seon.turn/calls [{:id "call-0" :code "(f 1)" :eval "e2"}]})
            _ (ev! "e2" "a1" "(f 1)" 1 {:call-id "call-0" :call 0 :content "I call f." :reasoning "why"})
            _ (d/transact conn [{:var/name 'demo-test/f-test :seon.test/status :red}])
            [_ r1] (request 1 {})]
        (is (= ["system" "user"] (mapv :role r0)) "turn 0: the system prompt, then the page whole")
        (is (= ["system" "user" "assistant" "tool" "user"] (mapv :role r1)) (pr-str (mapv :role r1)))
        (is (= {:role "assistant" :content "I call f." :reasoning "why" :tool-calls [{:id "call-0" :code "(f 1)"}] :call 0} (nth r1 2))
            "prose as content, the reasoning as returned, the call")
        (is (= "{:handle result/e2, :value 1}" (:content (nth r1 3))) "the call's response, its recorded value printed as it is sent")
        (is (= r0 (subvec r1 0 2)) "the whole earlier request is the prefix: its page stays, appended")
        (is (re-find #"status-\d+" (:content (peek r1))) "a verdict recorded after turn 0: how the work stands, told again")
        ;; (the task's data draws each proof's recorded status too, so it moves with a verdict: reported)
        (is (not (re-find #"related-\d+" (:content (peek r1)))) "what a verdict does not move is not told again")
        (is (str/includes? (:content (peek r1)) ":fail") "the proof's new verdict")
        (is (not (str/includes? (:content (peek r1)) "[:task]")) "the rest unchanged, not told again")
        (is (not-any? #(str/includes? (str (:content %)) ";;") (rest r1)) "no ;; reaches the model")
        (is (nil? (resolve 'seon.context/messages)) "one path: no request without an agent")))))

(deftest an-assertion-message-of-any-value-is-kept-as-text
  ;; clojure.test's is takes any message (a doseq's keyword, say); a run whose test reported one
  ;; ended on seon.proofs/failures' contract (dbg/live#4, 2026-10-24) until the report kept it as text
  (let [rep (agent/test-report {:type :fail :message :scripted :expected '(= 1 2) :actual '(not (= 1 2))} "#'demo-test/t")]
    (is (= ":scripted" (:message rep)))
    (is (= [":scripted"] (map :message (get (proofs/failures {:reports [rep]}) 'demo-test/t))))))

(deftest a-cited-var-carries-the-sections-that-cite-it
  ;; root's coverage gap 7: the wiki sections reached the agent only through the task's own citations; a
  ;; cited var now carries the sections that cite it (seon.doc/citing-sections), bounded by
  ;; :seon.config.ctx/cited-by, the decisions page last
  (let [db (d/db-with (db-with-program) (program-facts '[[demo/area "(defn area [x] (demo/f x))" [demo/f]]]))
        pages {"docs/a.md" "# A\n\nThe a page.\n\n## One\n\nWhat f does: ```demo/f``` doubles.\n"
               "docs/b.md" "# B\n\nThe b page.\n\n## Two\n\nAlso `demo/f`.\n"
               "docs/decisions.md" "# D\n\nRulings.\n\n## Ruled\n\nOwner chose `demo/f`.\n"}
        db (d/db-with db (seon.doc/load-tx db "docs" pages))]
    (is (= ["docs/a.md#one" "docs/b.md#two" "docs/decisions.md#ruled"] (seon.doc/citing-sections db 'demo/f)) "the sections citing it, the decisions page last")
    (is (= [] (seon.doc/citing-sections db 'demo/area)) "none cite it")
    (is (= ["docs/a.md#one" "docs/b.md#two" "docs/decisions.md#ruled"] (:sections (context/cited db 'demo/f))) "the write set's entry for it carries them")
    (is (not (contains? (context/cited db 'demo/area) :sections)) "and an uncited var carries none")))

(deftest the-task-data-is-plan-write-set-and-proofs
  ;; task seon.render/a-page-is-its-entitys-view (4d): the task's data is the plan, the write set (each
  ;; cited var's source and callers), and each proof's run form or what it should assert; no
  ;; helpers by embedding, no namespace map, no evidence
  (let [db (d/db-with (db-with-program) (program-facts '[[demo/area "(defn area [x] (demo/f x))" [demo/f]]
                                                          [demo/vol "(defn vol [x] (demo/f (demo/f x)))" [demo/f]]]))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "s"
                                               :seon.task/plan "Make `demo/f` triple. demo-test/h-test asserts (f 2) is 6."
                                               :seon.task/proofs #{'demo-test/f-test 'demo-test/h-test 'demo-test/q-test}}]))
        data (data-of db "demo/s")]
    (is (= [:task :write-set :to-prove] (vec (keys data))) "plan, write set, proofs; nothing else uncited")
    (is (= [{:symbol 'demo/f :source (print/code "(defn f \"Doubles.\" [x] (* 2 x))") :callers '[demo/area demo/vol]
             :change (print/code "(seon.repl/edit! 'demo/f \"old text, exactly once\" \"new text\")")}] (:write-set data))
        "each cited var with its source and its callers")
    (is (= '(my.agent/prove #{demo-test/f-test}) (get (:to-prove data) 'demo-test/f-test)) "a written proof: the form that runs it")
    (is (= "not written yet; it should assert what the plan says: demo-test/h-test asserts (f 2) is 6."
           (get (:to-prove data) 'demo-test/h-test))
        "an unwritten proof: what the plan says it should assert")
    (is (= "not written yet" (get (:to-prove data) 'demo-test/q-test))
        "one the plan's sentences do not name: that it is not written, not the plan again (the page shows it)")))

(deftest a-record-reads-as-its-live-response-through-its-model-render
  ;; the context lab, 2026-11-02: a prove's tool message, drawn from its record, was its whole value printed (~15.8k
  ;; tokens of map and :heap) where the live response was its model render's one line (my.agent/prove-summary): the
  ;; value's schema is metadata, which the store does not keep, and record-text never read the record's :schema
  (let [v (with-meta {:reports [] :ran 3 :reused 2 :fail 0 :error 0 :pass 3 :heap (vec (range 400))} {:seon.render/schema :seon.test/proved})
        r {:handle 'result/e7 :value v}
        live (seon.print/response-text r seon.render/model-printed)
        rec (repl/record-details "(my.agent/prove)" r {} nil)
        stored (:seon.eval/record (d/pull (d/db-with (db-with-program) [{:seon.eval/id "e7" :seon.eval/by "a1" :seon.eval/record rec}])
                                          [:seon.eval/record] [:seon.eval/id "e7"]))
        sent (:content (second (context/eval-messages {:seon.eval/by "a1" :seon.eval/record (assoc stored :call-id "c1")} nil)))]
    (is (= :seon.test/proved (:schema stored)) "the record keeps the value's schema")
    (is (str/includes? live "green: ran 3") "the live response is its one line")
    (is (= live sent) (str "the record's tool message reads as the live response did: " sent))
    (is (not (str/includes? sent ":heap")) "not the whole value")))

(deftest a-record-keeps-its-value
  ;; the owner's rule (clipping audit 2026-10-25, P1/P8): data is cut only by the one printer,
  ;; when it is printed into a reader's view; the full result is stored. A record keeps its
  ;; response's value as data, and is the same whatever was evaluated before it
  (let [db (db-with-program)
        r {:handle 'result/e5 :value (vec (for [i (range 300)] {:i i :name (str "row-" i)})) :ms 1.0}
        rec (repl/record-details "(rows)" r {} nil)
        stored (d/db-with db [{:seon.eval/id "e5" :seon.eval/by "a1" :seon.eval/record rec}])]
    (is (= (:value r) (get-in rec [:response :value])) "the whole value, not a printout of it")
    (is (not (contains? rec :text)) "no text made for a reader")
    (is (= rec (:seon.eval/record (d/pull stored [:seon.eval/record] [:seon.eval/id "e5"]))) "it round-trips through the store")
    (is (str/includes? (print/record-text rec) "row-0") "printed when it is shown")
    (is (= rec (repl/record-details "(rows)" r {} nil)) "the same whatever came before it")
    (testing "a value that is not plain data keeps the response as it printed then, and its type"
      (doseq [v [(atom 1) (map inc (range 3)) #'clojure.core/inc]]
        (let [rec (repl/record-details "(x)" {:handle 'result/e6 :value v} {} nil)]
          (is (not (contains? (:response rec) :value)) (str (type v)))
          (is (= (str (type v)) (:type rec)))
          (is (string? (:printed rec)))
          (is (= (:printed rec) (print/record-text rec)) "and reads so"))))))

(deftest an-evals-record-keeps-its-prose-as-written
  ;; docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map: an eval's record keeps its
  ;; prose and parses none of it (the parser is program the agent may break: md-scan's broken seon.ref/scan
  ;; made every eval throw while the record parsed the prose's citations); a reader parses the kept prose
  (let [prose "Reading `demo/f` before I change it."
        rec (repl/record-details "(f 1)" {:text "2"} {:prose prose} "demo/s#1")]
    (is (= prose (:prose rec)) "the prose, kept as written")
    (is (not (contains? rec :cites)) "and nothing parsed from it")
    (is (= "demo/s#1" (:run rec)))
    (is (= "{:a 1}" (:line (repl/record-details "{:a 1}" {:text "x" :value {:a 1}} {} nil))) "its value in one line, for the collapsed eval")
    (is (= "nil" (:line (repl/record-details "(source f)" {:text "(defn f [x] x)\nnil" :value nil} {} nil)))
        "a value of nil after printing is nil, not nothing (step 6: such evals' collapsed rows showed nothing)")))

(deftest a-red-proofs-source-reads-as-its-file
  ;; owner, 2026-10-25: a test's source in my.task/status read as one escaped string literal
  ;; (every quote \", every line break \n); it is code (seon.print/code) and prints as its file
  ;; holds it, comments and quotes as written
  (let [src "(deftest f-test
  ;; f doubles: the plan's \"exact\" case
  (is (= 4 (f 2))
      \"two doubled\"))"
        db (d/db-with (db-with-program) (program-facts [['demo-test/f-test src []]]))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f exact."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        state (status-at db "demo/f" {'demo-test/f-test [:red]})
        text (:text (print/print-value {:handle 'result/e1 :value state} {:handle 'result/e1}))]
    (is (every? #(str/includes? text %) (str/split-lines src)) text)
    (is (not (str/includes? text "\\\"two doubled")) "no quote escaped")))

(deftest code-is-plain-data-that-round-trips
  ;; root, 2026-10-25: code is plain data ({:seon.print/code text}), one representation live and
  ;; stored: a record holding it stores as :db.type/data and reads back equal, printing verbatim
  (let [src "(deftest f-test\n  ;; a comment, a \"quoted\" word\n  (is (= 4 (f 2))))"
        c (print/code src)
        rec {:code "(my.task/status)" :response {:handle 'result/e4 :value {:source c}}}
        db (d/db-with (ts/test-db) [{:seon.eval/id "e4" :seon.eval/agent {:seon.agent/id :a1} :seon.eval/record rec}])
        back (:seon.eval/record (d/pull db [:seon.eval/record] [:seon.eval/id "e4"]))]
    (is (pdb/plain? c) "plain data")
    (is (= c (seon.edn/decode (seon.edn/encode c))) "its EDN reads back")
    (is (= rec back) "the record reads back equal")
    (is (every? #(str/includes? (print/record-text back) %) (str/split-lines src)) "and prints verbatim")))

(defn- sections-near
  "db with a task demo/r whose plan is plan, and five wiki sections each at a vector a step further
  from the plan's than the one before (docs/vector.md#the-model nearest), each with a lead."
  [plan]
  (let [anchors (mapv #(str "docs/vector.md#" %) ["the-model" "what-is-embedded" "the-pyramid" "fused-with-the-call-graph" "what-the-work-is-likely-to-need-as-an-eval"])
        dir (fn [& xs] (let [v (vec (concat xs (repeat (- 768 (count xs)) 0.0))) n (Math/sqrt (reduce + (map #(* % %) v)))] (mapv #(/ % n) v)))
        db (ts/test-db)
        db (d/db-with db (vec (for [i (range 5)] {:seon.doc/address (anchors i) :seon.doc/heading (str "S" i)
                                                  :seon.doc/lead (str "Section " i " says one thing. And more.")
                                                  :seon.doc/text (str "Section " i " says one thing. And more.")})))
        ;; demo is the program's (src/), demo-test and its helpers are test/'s (seon.program/program-nss),
        ;; held before the task cites them: a name in a namespace the program does not hold is not cited
        db (d/db-with db [{:ns/name 'demo :ns/file "src/demo.clj"} {:ns/name 'demo-test :ns/file "test/demo_test.clj"}])
        ;; every item scored is shown (its cut 0), so these tests read relevance's whole order
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "r" :seon.task/plan plan
                                              :seon.task/config {:seon.config.render/relevance-summary 0.0}}]))
        te (pdb/eid db :seon.task/id "demo/r")
        es (into [te] (for [i (range 5)] (pdb/eid db :seon.doc/address (anchors i))))
        db (d/db-with db (vector/tx-data es (repeat 6 1) (into [(dir 1.0)] (for [i (range 5)] (dir 1.0 (* 0.1 (inc i))))) (repeat 6 10) "m"))
        ;; a function beside the plan by meaning, and a schema key its plan names
        db (d/db-with db [{:db/id "f" :form/id "demo/g" :form/src "(defn g [])" :form/keywords #{:demo/thing :demo/not-a-schema}} {:var/name 'demo/g :var/form "f" :var/doc "Gets it. More."}
                          {:schema/key :demo/thing :schema/form "[:map]"}])
        f (pdb/eid db :form/id "demo/g")]
    [(d/db-with db (vector/tx-data [f] [1] [(dir 1.0 0.05)] [10] "m")) te dir]))

(deftest relevance-is-a-chance-that-follows-the-context
  ;; seon.context/relevance (docs/vector.md#relevance): each item near a context scored as a chance,
  ;; best first; the touched items left out, and what is near them lifted (zt)
  (let [[db te] (sections-near "Make the page say one thing.")
        qv (vector/vector-of db te)
        sec #(pdb/eid db :seon.doc/address (str "docs/vector.md#" %))
        score (fn [touched] (into {} (:section (context/relevance db {:qv qv :text "Make the page say one thing." :seeds touched :touched touched}
                                                                  #{:section} (constantly true)))))
        cold (:section (context/relevance db {:qv qv :text "Make the page say one thing." :seeds #{} :touched #{}} #{:section} (constantly true)))]
    (is (= 5 (count cold)) "every section near it scored")
    (is (every? (fn [[_ p]] (<= 0.0 p 1.0)) cold) "each a chance")
    (is (apply >= (map second cold)) "best first")
    (is (= (sec "the-model") (ffirst cold)) "the nearest first, all else equal")
    (is (= cold (:section (context/relevance db {:qv qv :text "Make the page say one thing." :seeds #{} :touched #{}
                                                 :near {:seon.doc/address (vector/near db qv 100 {:of :seon.doc/address})}}
                                             #{:section} (constantly true))))
        "a search's own scan of the nearest, handed over, scores as relevance's own scan does")
    (let [warm (score #{(sec "what-the-work-is-likely-to-need-as-an-eval")}) cold (into {} cold)
          gap (fn [m] (- (m (sec "fused-with-the-call-graph")) (m (sec "the-model"))))]
      (is (not (contains? warm (sec "what-the-work-is-likely-to-need-as-an-eval"))) "a touched item is not offered again")
      (is (> (gap warm) (gap cold))
          "the section nearest the touched one gains on the one farthest from it"))))

(deftest relevances-parts-at-their-bounds
  (let [knots [[-2.0 0.0] [0.0 0.1] [2.0 0.5]]]
    (is (= [0.0 0.0 0.05 0.1 0.5 0.5] (mapv #(#'seon.context/percent knots %) [-9.0 -2.0 -1.0 0.0 2.0 9.0]))
        "a logit's chance: flat past either end, linear between knots"))
  (is (= [0.0 0.0] (#'seon.context/z [3.0 3.0])) "no spread, no evidence")
  (is (= [1.0 0.0 -1.0] (#'seon.context/z [2.0 nil 0.0])) "a missing value is no evidence; the rest over the values there are")
  (is (= [] (seon.doc/bm25 #{"a"} [] [])) "an empty pool scores nothing")
  (is (= [0.0 0.0] (seon.doc/bm25 #{} [{"a" 1} {"b" 2}] [1 2])) "an empty query scores every text 0")
  (let [[x y] (seon.doc/bm25 #{"near"} [{"near" 1 "of" 1} {"of" 2}] [2 2])]
    (is (and (pos? x) (zero? y)) "a text holding the query's word scores above one that does not")))

(deftest a-tokens-words-are-its-identifiers-parts
  (is (= ["seon" "vector" "near" "of" "foo" "bar"] (seon.doc/terms "seon.vector/near-of fooBar a"))
      "split at what is no letter or digit and at a case change, one-letter parts dropped"))

(defn- walked
  "Agent demo/a1's page on db (sections-near's, with a run of demo/r), walked live over a reactive
  atom holding its ctx, as its process holds it: [walk atom db-cell rid]. A change is (step! walk
  atom db-cell tx-data): the transaction applied, ctx patched by its datoms, the walk flushed."
  [db]
  (let [db (d/db-with db [{:seon.agent/key "demo/a1" :seon.agent/task {:seon.task/id "demo/r"}} {:seon.run/id "demo/r#1" :seon.run/task [:seon.task/id "demo/r"] :seon.run/agent [:seon.agent/key "demo/a1"]}])
        cell (volatile! db)
        c (volatile! (ctx/build db (pdb/eid db :seon.agent/key "demo/a1") :b))
        a (seon.ratom/atom (:tree @c))
        w (render/walker {:ref a :changes ctx/changes} #(deref cell) [(requiring-resolve (render/declared :seon.render/render-ctx)) {}])]
    (render/flush! w nil)
    [w a cell c (pdb/eid db :seon.agent/key "demo/a1")]))

(defn- step!
  "Transaction tx-data on walked's db: ctx patched by its datoms, the walk flushed. Answers the units
  whose html changed, by id."
  [[w a cell c] tx-data]
  (let [u0 (into {} (map (juxt :id :html)) (render/placed-units w))
        r (d/with @cell tx-data)]
    (vreset! cell (:db-after r))
    (vswap! c ctx/patch (:db-after r) (:tx-data r) :b)
    (reset! a (:tree @c))
    (render/flush! w (:tx-data r))
    (let [u1 (into {} (map (juxt :id :html)) (render/placed-units w))]
      (vec (sort (for [[id h] u1 :when (not= h (get u0 id))] id))))))

(deftest a-cited-var-redefined-shows-in-the-next-view
  ;; the task's data is a render, never held: a var the plan cites redefined (the agent's defn, or main
  ;; caught up), the next view shows its new source. Each cited var is its own component, which reads
  ;; the db, so the edit runs that var's component again (its db reads touched) and the task's not
  (let [[db _] (sections-near "Make ```demo/g``` say one thing.")
        [w _ _ _ rid :as walk] (walked db)
        cited (str "cited-" rid "-demo_2Fg")
        html #(:html (some (fn [u] (when (= % (:id u)) u)) (render/placed-units w)))]
    (is (some? (html cited)) "the cited var, a component of its own")
    (is (not (str/includes? (html cited) ":redefined")))
    (let [changed (step! walk [{:db/id "f2" :form/id "demo/g-2" :form/src "(defn g [] :redefined)"} [:db/add [:var/name 'demo/g] :var/form "f2"]])]
      (is (str/includes? (html cited) "(defn g [] :redefined)") "the next view: the new source")
      (is (some #{cited} changed) "the edit's page diff holds that var's component")
      (is (not-any? #(str/starts-with? % "task-data-") changed) "and not the task's, which cites it"))
    (render/dispose-walk! w)))

;; docs/references.md#an-entity-cites: a section a cite joins, changed (main's datoms patched into ctx), redraws that
;; cite's part alone, and the live page is the page a cold draw of the same db makes
(deftest a-cited-section-main-changed-is-its-part-alone
  (let [[db _] (sections-near "Make ```demo/g``` say one thing; see [the model](docs/vector.md#the-model).")
        [w _ cell _ rid :as walk] (walked db)
        part (str "cited-" rid "-docs_2Fvector.md_23the-model")
        html (fn [w] (into {} (map (juxt :id :html)) (render/placed-units w)))]
    (is (some? ((html w) part)) "the cited section, a part of its own")
    (let [changed (step! walk [[:db/add [:seon.doc/address "docs/vector.md#the-model"] :seon.doc/lead "The model, as main now says it."]])
          live (html w)
          cold (let [d @cell w2 (render/draw (:tree (ctx/build d (pdb/eid d :seon.agent/key "demo/a1") :b)) ctx/changes d
                                             (requiring-resolve (render/declared :seon.render/render-ctx)))]
                 (try (html w2) (finally (render/dispose-walk! w2))))]
      (is (= [part] changed) "the section's part, and nothing else, drawn again")
      (is (str/includes? (live part) "The model, as main now says it.") "with the section as it is now")
      (is (= cold live) "the live page is the cold draw"))
    (render/dispose-walk! w)))

(deftest the-task-draws-what-it-needs
  ;; the scratch paper context-for-20-turns-2026-10-26.md (git history) (D1-D4): the task's list draws the whole plan (D4),
  ;; each proof written with its source (D1; its last result is the status's), each var the plan cites
  ;; with its source (D2, a component of its own) and each section the plan cites with its text (D3)
  (let [[db te] (sections-near "Make ```demo/g``` say one thing (docs/vector.md#what-is-embedded).")
        db (d/db-with db [{:db/id "t" :form/id "demo-test/g-test" :form/src "(deftest g-test (is (= :one (g))))"}
                          {:var/name 'demo-test/g-test :var/form "t"} [:db/add te :seon.task/proofs 'demo-test/g-test]])
        [w _ _ _ rid] (walked db)
        units (render/placed-units w)
        html #(:html (some (fn [u] (when (str/starts-with? (:id u) %) u)) units))
        task (html "task-data-")]
    (is (str/includes? task "Make ```demo/g``` say one thing") "D4: the whole plan")
    (is (not (str/includes? task "(deftest g-test")) "D1: the proof's source is the status's, the task's part does not repeat it")
    (is (str/includes? (html "status-") "demo-test/g-test") "D1: the proof written is the status's, by name")

    (is (str/includes? (html (str "cited-" rid "-demo_2Fg")) "(defn g [])") "D2: the cited var's source, its own component")
    (is (str/includes? (html (str "cited-" rid "-docs_2Fvector.md_23what-is-embedded")) "Section 1 says one thing.") "D3: the cited section's text, not its address, in its own component")
    (is (not (str/includes? task "Section 1 says one thing.")) "and not the task's again")
    (render/dispose-walk! w)))

(deftest a-db-component-runs-again-on-what-it-read
  ;; a component that reads the db runs under d/with-reads: a transaction touching what it read runs
  ;; it again, any other leaves it (the task's own keys, context/task-key and related-key, deleted)
  (let [[db te] (sections-near "Make ```demo/g``` say one thing (docs/vector.md#what-is-embedded).")
        [w :as walk] (walked db)
        ran #(do (vreset! (:ledger w) {}) (step! walk %) (into #{} (for [[f c] (render/ledger w) :when (or (:miss c) (:same c))] (name f))))]
    (is (not (contains? (ran [{:seon.agent/key "demo/a2"}]) "task-data")) "an agent written: nothing the task's data reads")
    (is (contains? (ran [[:db/add te :seon.task/plan "Make ```demo/g``` say two things."]]) "task-data") "the plan rewritten: the task's data runs again")
    (render/dispose-walk! w)))

(deftest a-vars-arglists-are-its-recorded-sources
  ;; what a var declares, read from its source as the db records it (seon.reader/arglists)
  (is (= '([x]) (seon.reader/arglists "(defn sq \"Squares.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))" 'demo)))
  (is (= '([x] [x y]) (seon.reader/arglists "(defn f ([x] x) ([x y] (+ x y)))" 'demo)) "each arity's")
  (is (= '([& body]) (seon.reader/arglists "(defmacro m [& body] `(do ~@body))" 'demo)) "a macro's")
  (is (nil? (seon.reader/arglists "(def x 1)" 'demo)) "no arglists: no function")
  (let [db (d/db-with (db-with-program) (program-facts '[[demo-only/h "(defn h \"Holds what the db says.\" [a b] a)" []]]))]
    (is (= {:symbol 'demo-only/h :arglists '([a b]) :summary "Holds what the db says."} (doc/resolve-ref db 'demo-only/h :summary))
        "a var only the db holds: its summary from its facts")))

(deftest a-pages-cited-vars-are-the-programs
  ;; audit item 2a: the page's cited vars are the program's; a reproduction's test helper
  ;; (demo-test is test/'s), library call and handle are not cited, while the program's var is
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "c"
                                              :seon.task/plan "Reproduce: (let [x (demo-test/f-test)] (demo/f (clojure.string/trim x))) answers result/e1."}]))]
    ;; the program's vars among its cites (what the judge reads, seon.judge/subject)
    (is (= #{'demo/f} (:cited (seon.judge/subject db (task/task db "demo/c")))))))

(deftest a-proof-with-no-result-reads-unknown
  ;; demo-test/f-test is written (its source is recorded) but no test report names it: it never
  ;; ran, which the judge calls :proof-unknown (seon.reconcile/done-explain); it is not "ran and asserted nothing"
  (let [db (db-with-program)
        block (status-at (db-f db) "demo/f" {})
        by (into {} (map (juxt :test :status)) (:proofs block))]
    (is (= :unknown (by 'demo-test/f-test)) (pr-str (:proofs block)))))

(deftest names-in-a-query-are-read-by-the-citation-parser
  ;; docs/references.md: a query's names come from seon.ref/parse: a var's symbol kept only when the db
  ;; holds it, a keyword always, once each in first-mention order, in backticks or bare
  (let [db (d/db-with (seon.doc-test/corpus-db) [{:db/id "c" :var/name 'app/caller} {:db/id "d" :var/name 'app/other}])
        names-in #'context/names-in]
    (is (= ['app/caller] (names-in db "who calls `app/caller`?")) "a name in backticks")
    (is (= ['app/caller] (names-in db "who calls app/caller now")) "a bare name")
    (is (= [:seon.task/plan] (names-in db "read :seon.task/plan please")) "a keyword, held or not")
    (is (= ['app/other :seon.task/plan 'app/caller] (names-in db "app/other and `:seon.task/plan` then `app/caller`, app/other again"))
        "once each, in first-mention order, symbols and keywords together")
    (is (= [] (names-in db "see `no.such/var` and not.a/var")) "a symbol naming no var on db is left out")))

(deftest search-fuses-words-and-references-and-filters-by-plain-data
  ;; task one-index steps 4-5: one search over every kind; the answer is data an agent filters
  (let [db (d/db-with (seon.doc-test/corpus-db)
                      [{:db/id "c" :var/name 'app/caller} {:form/id "app/caller" :form/src "(defn caller [] (g))" :form/defines ["c"] :form/refs ['app/g]}])
        ;; :min 0 unless a test asks its own: on a corpus of a few texts every chance is low, and the
        ;; page's cut (seon.config.render/relevance-summary) would hide them
        s (fn ([q] (context/search db q {:min 0.0} nil)) ([q o] (context/search db q (merge {:min 0.0} o) nil)))
        shown (fn [hits] (remove #(= :more (:kind %)) hits))
        kind (fn [hits k] (filter #(= k (:kind %)) hits))]
    (is (= #{:var :section} (set (map :kind (shown (s "fusing"))))) "functions and sections by default")
    (is (= #{:var :section :task :schema} (set (map :kind (shown (s "fusing" {:kinds #{:var :section :task :schema}}))))) "the rest on request")
    (is (every? #(and (number? (:score %)) (set? (:by %)) (:e %)) (shown (s "fusing"))) "plain data: kind, name, entity, score, by")
    (is (= [:section :section] (map :kind (s "fusing" {:kinds #{:section} :min 0.0}))) "kinds focus it")
    (is (every? #(= #{:text} (:by %)) (shown (s "fusing" {:by #{:text}}))))
    (is (seq (filter :relevance (s "fusing"))) "a function's or a section's relevance")
    (is (every? #(<= 0.0 (:relevance %) 1.0) (filter :relevance (s "fusing"))) "each a chance")
    (is (= (s "fusing") (s "fusing")) "deterministic")
    (let [hs (shown (s "fusing")) [known unknown] ((juxt filter remove) :relevance hs)]
      (is (= hs (concat known unknown)) "best first: the hits with a relevance first")
      (is (every? (fn [[a b]] (>= a b)) (partition 2 1 (map :relevance known))) "by it")
      (is (every? (fn [[a b]] (>= a b)) (partition 2 1 (map :score unknown))) "then the rest by the fused score"))
    (let [two (s "fusing" {:k 1 :min 0.0})]
      (is (= 2 (count two)) "k bounds it, and a last entry counts what it left")
      (is (= :more (:kind (last two))))
      (is (pos? (:left (last two))))
      (is (= (:left (last two)) (reduce + (vals (:counts (last two))))) "per kind")
      (is (str/includes? (:more (last two)) ":k ") "and the call that returns them"))
    (testing "the references of the names in the query: who calls a var"
      (is (= ['app/caller] (map :name (kind (s 'app/g {:by #{:refs}}) :var))))
      (is (= ['app/caller] (map :name (kind (s "who calls app/g" {:by #{:refs}}) :var))) "a name inside a text seeds them too")
      (is (= [] (kind (s "fusing" {:by #{:refs}}) :var)) "never the text hits' own"))
    (testing ":ns keeps the vars of a name and what is under it (seon.doc/named-under?)"
      (is (= ['app/g] (map :name (kind (s "fusing" {:ns "app"}) :var))))
      (is (= [] (kind (s "fusing" {:ns "ap"}) :var)) "never a name it only starts with")
      (is (= [] (kind (s "fusing" {:ns "other"}) :var)))
      (is (seq (kind (s "fusing" {:ns "other"}) :section)) "and leaves the other kinds"))
    (testing ":min leaves out a hit whose known relevance is below it"
      (is (= [] (shown (s "fusing" {:min 1.0}))))
      (is (= :more (:kind (first (s "fusing" {:min 1.0})))) "and says so"))))

(defn- unit-vec [i]
  (let [raw (mapv #(Math/sin (* (inc i) (inc %) 0.37)) (range 768))
        n (Math/sqrt (reduce + (map #(* % %) raw)))]
    (mapv #(/ % n) raw)))

(deftest search-ranks-meaning-across-kinds-by-score
  (let [with-vec (fn [m i] [m (vector/fact (:db/id m) 0 (unit-vec i) 4 "m")])
        db (d/db-with (ts/test-db)
                      (into [] cat
                            [(with-vec {:db/id "s0" :seon.doc/address "m.md#a" :seon.doc/heading "a" :seon.doc/text "about a"} 0)
                             (with-vec {:db/id "s1" :seon.doc/address "m.md#b" :seon.doc/heading "b" :seon.doc/text "about b"} 5)
                             [{:db/id "v" :var/name 'm/f :var/form "f"}]
                             (with-vec {:db/id "f" :form/id "m/f" :form/src "(defn f [] 1)" :form/defines ["v"]} 1)]))
        hits (context/search db "zzz" {:by #{:meaning} :k 10 :min 0.0} (unit-vec 0))]
    (is (= ["m.md#a" "m.md#b"] (map :name (filter #(= :section (:kind %)) hits))) "the nearer section first")
    (is (= #{:section :var} (set (map :kind hits))))
    (is (every? #(= #{:meaning} (:by %)) hits))
    (is (apply >= (map :relevance hits)) "ranked by one scale, best first")
    (is (= [:section] (distinct (map :kind (context/search db "zzz" {:by #{:meaning} :kinds #{:section} :min 0.0} (unit-vec 0))))))))


;; ---- the model reads the walk's parts (task seon.context/the-context-is-the-agents-evals-and-seons, R1) ----

(defn- parts-session
  "A branch with the task demo/f, its agent b/a1 and a run, and the functions that play its turns as
  seon.turn goes: [ask! ev! plan!]. (ask! n opts) records model turn n, makes its request on the db that holds it
  (context/render, opts as render takes them), records what it sent and answers the render; (ev! id)
  records a model eval; (plan! text) changes the task's plan."
  [conn]
  (wiki! conn)
  (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
  (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                           :seon.task/proofs #{'demo-test/f-test}}]))
  (d/transact conn [{:seon.run/id "demo/f#1" :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/f"}}}])
  (do
    [(fn ask! [n opts]
       (let [b {:commit (d/commit-id @conn) :task "demo/f" :run "demo/f#1" :turn n :max-turns 9 :origin :model :agent "b/a1"}
             _ (d/transact conn [{:db/id "turn" :seon.turn/id (str "demo/f#1/" n) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/agent {:seon.agent/key "b/a1"} :seon.turn/n n :seon.turn/origin :model}
                                 {:seon.model-call/id (str "demo/f#1/" n) :seon.model-call/turn "turn" :seon.model-call/basis b :seon.model-call/forms 1
                                  :seon.model-call/errors 0 :seon.model-call/red 1 :seon.model-call/ms 1}])
             r (context/render @conn b (cond-> opts (fn? (:parts opts)) (update :parts #(% @conn))))]
         (d/transact conn [{:seon.model-call/id (str "demo/f#1/" n) :seon.model-call/sent (:sent r)}])
         r))
     (fn ev! [id] (d/transact conn [{:seon.eval/id id :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key "b/a1"}
                                     :seon.eval/record {:code (str "(f " id ")") :response {:handle (symbol "result" id) :value 1} :run "demo/f#1"
                                                        :call-id (str "c-" id) :call 0 :prose (str "Step " id ".")}}]))
     (fn plan! [p] (d/transact conn [{:seon.task/id "demo/f" :seon.task/plan p}]))]))

(defn- part-ids
  "The ids of the parts a sent vector's [:parts ...] refs name, in order."
  [sent] (vec (mapcat #(nth % 4) (filter #(= :parts (first %)) sent))))

(deftest a-first-request-is-the-help-and-every-part
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev!] (parts-session conn)
            _ (ev! "e1")
            {:keys [messages sent]} (ask! 0 {})
            db @conn
            all (keys (context/drawn-parts db (ctx/build db (pdb/eid db :seon.agent/key "b/a1") (:seon.agent/branch (d/entity db (pdb/eid db :seon.agent/key "b/a1"))))))]
        (is (= ["system" "user" "assistant" "tool"] (mapv :role messages)) "the system prompt, the parts in one message, then the records")
        (is (= [:section :parts :eval :hashes] (mapv first sent)))
        (is (true? (nth (second sent) 3)) "whole")
        (is (= (vec all) (part-ids sent)) "every part of the page, in the layout's order")
        (is (every? #(str/includes? (:content (second messages)) %) all) "each by its id")
        (is (= (set all) (set (keys (second (peek sent))))) "and the hash of each is recorded")
        (is (seq all))))))

(deftest a-turn-appends-its-new-and-moved-parts
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev! plan!] (parts-session conn)
            r0 (ask! 0 {})
            _ (ev! "e1")
            r1 (ask! 1 {})
            _ (ev! "e2")
            _ (plan! "Make f triple.")
            r2 (ask! 2 {})
            ms1 (:messages r1) ms2 (:messages r2)
            moved (part-ids (:sent r2))
            all (part-ids (:sent r0))]
        (is (= ms1 (subvec ms2 0 (count ms1))) "the request is the one before, byte for byte")
        (is (= ["assistant" "tool" "user"] (mapv :role (subvec ms2 (count ms1)))) "and the new record, then one message of the parts that moved")
        (is (str/includes? (:content (peek ms2)) "Make f triple.") "the plan's part")
        (is (not (str/includes? (:content (peek ms2)) "(defn f")) "and not a part that did not move")
        (is (and (seq moved) (< (count (nth (last (filter #(= :parts (first %)) (:sent r2))) 4)) (count all))) "fewer than all of them")))))

(deftest a-part-is-sent-once-a-change
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! _ _] (parts-session conn)
            held (atom nil)
            ;; the parts as the walk draws them for turn 0, kept: the live walk of a turn that changed nothing
            drawn (fn [db] (let [a (pdb/eid db :seon.agent/key "b/a1")] (context/drawn-parts db (ctx/build db a (:seon.agent/branch (d/entity db a))))))
            r0 (ask! 0 {:parts (fn [db] (reset! held (drawn db)))})
            p @held
            ;; a request that finds every part as the last recorded: nothing is sent again, no empty message
            r1 (ask! 1 {:parts (fn [_] p)})
            ;; a process restarted on the branch (no print of its own): what the last call recorded decides
            r2 (ask! 2 {:parts (fn [_] p) :printed nil})
            k (first (keys p))
            changed (assoc p k [:p "moved"])
            r3 (ask! 3 {:parts (fn [_] changed) :printed (:printed r2)})
            r4 (ask! 4 {:parts (fn [_] changed)})
            sent-ids (fn [r] (nth (last (filter #(= :parts (first %)) (:sent r))) 4))]
        (is (= (:messages r0) (:messages r1)) "an unchanged part is not sent again")
        (is (= [] (sent-ids r1)) "and no message is made of none")
        (is (= (:messages r0) (:messages r2)) "nor by a restarted process")
        (is (= [k] (sent-ids r3)) "a changed part is sent")
        (is (= (:messages r0) (subvec (:messages r3) 0 (count (:messages r0)))) "after the request before")
        (is (str/includes? (:content (peek (:messages r3))) "moved"))
        (is (= [] (sent-ids r4)) "and once: the next request finds it as sent")))))

(deftest a-changed-part-is-sent-as-what-changed
  ;; owner, 2026-10-30 (approved in docs/decisions.md): a changed part that answers data sends what changed of it;
  ;; md-scan #28's status, 3-5k tokens, was sent whole eleven times. A map part's top-level keys: the new or changed
  ;; values under :changed, the keys that left under :gone, the keys that kept their value not sent again
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! _ _] (parts-session conn)
            p0 {"status-1" {:proofs [:red] :turn "1 of 40" :caused 2}}
            p1 {"status-1" {:proofs [:green] :turn "1 of 40" :ns 'demo}}
            r0 (ask! 0 {:parts (constantly p0)})
            r1 (ask! 1 {:parts (constantly p1) :printed (:printed r0)})
            sent (:content (peek (:messages r1)))]
        (is (str/includes? sent ":changed") (str "what changed of the part: " sent))
        (is (str/includes? sent ":green") "its new value")
        (is (str/includes? sent "demo") "a key it gained")
        (is (str/includes? sent ":gone [:caused]") "a key it lost")
        (is (not (str/includes? sent "1 of 40")) (str "a key that kept its value is not sent again: " sent))
        (let [hiccup {"status-1" [:p "moved"]}
              r2 (ask! 2 {:parts (constantly hiccup) :printed (:printed r1)})]
          (is (str/includes? (:content (peek (:messages r2))) "moved") "a part that is no map is sent whole"))))))

(deftest a-part-sent-as-what-changed-replays-as-sent
  ;; ts/test-branch!: a request reads its turns, model calls and parts from a branch's commits, as root's turns
  ;; write them (parts-session), and replay draws each commit's parts from them: a my.test world holds no branch
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! _ _] (parts-session conn)
            r0 (ask! 0 {})
            ;; a proof's verdict recorded since turn 0: the status, a map, moved
            _ (d/transact conn [{:var/name 'demo-test/f-test :seon.test/status :red}])
            r1 (ask! 1 {:printed (:printed r0)})
            ref1 (last (filter #(= :parts (first %)) (:sent r1)))
            _ (d/transact conn [{:var/name 'demo-test/f-test :seon.test/status :green}])
            ;; a process restarted on the branch holds no parts of the request before: it draws them at the commit
            ;; that request recorded, so it sends the same bytes a process that printed the run would
            r2 (ask! 2 {:printed nil})
            r2' (ask! 2 {:printed (:printed r1)})
            ref2 (last (filter #(= :parts (first %)) (:sent r2)))]
        (is (= 7 (count ref1)) (str "sent as what changed, its ref naming the commit it changed from: " (pr-str ref1)))
        (is (str/includes? (:content (peek (:messages r1))) ":changed") (:content (peek (:messages r1))))
        (is (= (peek (:messages r1)) (first (second (first (filter #(= ref1 (first %)) (context/parts-of @conn (:sent r1)))))))
            "read back from the record, the same bytes")
        (is (= 7 (count ref2)) (str "after a restart, still what changed: " (pr-str ref2)))
        (is (= (peek (:messages r2)) (peek (:messages r2'))) "the same bytes as a process that printed the run")))))

(deftest a-trimmed-conversation-draws-the-request-whole
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev!] (parts-session conn)
            _ (ev! "e1")
            _ (ask! 0 {})
            _ (ev! "e2")
            ;; a proof's verdict recorded since turn 0: the status is a part that moved
            _ (d/transact conn [{:var/name 'demo-test/f-test :seon.test/status :red}])
            r1 (ask! 1 {})
            ;; the agent trims its context (swap! on @ctx retracts the eval on its branch)
            _ (d/transact conn [[:db/retractEntity [:seon.eval/id "e1"]]])
            r2 (ask! 2 {:printed (:printed r1)})]
        (is (= ["system" "user" "assistant" "tool" "assistant" "tool" "user"] (mapv :role (:messages r1))) "before: the parts whole, e1, e2, the parts that moved")
        (is (= ["system" "user" "assistant" "tool"] (mapv :role (:messages r2))) "after: system, the parts whole, what remains")
        (is (= [:section :parts :eval :hashes] (mapv first (:sent r2))))
        (is (true? (nth (second (:sent r2)) 3)))
        (is (= [:eval "e2"] (nth (:sent r2) 2)))
        (is (not-any? #(str/includes? (str (:content %)) "e1") (rest (:messages r2))) "the trimmed record is gone")))))

(deftest a-cut-reply-is-no-message-in-the-history
  ;; task seon.context/the-context-is-the-agents-evals-and-seons R1 (review): a turn's prose reply with
  ;; no call is its assistant message, but one cut at the token limit is no message, as the turn loop
  ;; reads it (seon.turn/act!), so the history and the stop rule agree
  (let [db (d/db-with (ts/test-db) [{:seon.agent/key "b/a1" :seon.agent/id :a1}
                                    {:seon.turn/id "b/a1/1" :seon.turn/agent [:seon.agent/key "b/a1"] :seon.turn/n 1 :seon.turn/reply "Done: f0 is green."}
                                    {:seon.turn/id "b/a1/2" :seon.turn/agent [:seon.agent/key "b/a1"] :seon.turn/n 2 :seon.turn/reply "(defn f0 [x] (let [y"
                                     :seon.turn/truncated true}])
        a (:db/id (d/entity db [:seon.agent/key "b/a1"]))]
    (is (= ["Done: f0 is green."] (keep (comp :seon.turn/reply second) (context/records db a))) "the whole reply is a record, the cut one is not")))

(deftest failures-that-got-the-same-value-are-one-entry
  ;; 2026-10-29, the md-scan bench: nine assertions that each threw one NullPointerException were
  ;; sent as nine entries, the same error nine times
  (let [npe "#error {:via [{:type java.lang.NullPointerException}]}"
        fs (context/failures [{:expected "(= 1 (f 1))" :actual npe} {:expected "(= 2 (f 2))" :actual npe}
                              {:expected "(= 3 (g))" :actual "(= 3 4)"}])]
    (is (= 2 (count fs)) "one entry per value got, in first-failure order")
    (is (= ["(= 1 (f 1))" "(= 2 (f 2))"] (mapv :seon.print/code (:asserts (first fs)))) "the assertions that got it")
    (is (= npe (:seon.print/code (:got (first fs)))))
    (is (= "(= 3 (g))" (:seon.print/code (:assert (second fs)))) "a failure of its own keeps its one assertion")))

(deftest a-namespace-shows-the-names-the-task-names
  ;; docs/render.md#a-part-answers-data: a bench run's namespaces part was 1,256 tokens listing about 170 names
  ;; the task never needed. It shows the namespaces of what the task cites and of its proofs, each with the
  ;; names the task cites or its proofs call and the form that lists the rest, from the record alone; the one the
  ;; REPL is in and the task's own first (docs/agent.md#the-repl)
  (let [db (d/db-with (ts/test-db)
                      (into [{:db/id "n" :ns/name 'x.nsd} {:db/id "nt" :ns/name 'x.nsd-test}
                             {:db/id "tf" :form/id "x.nsd-test/t1" :form/src "(deftest t1 (is (= 1 (b 1))))" :form/refs #{'x.nsd/b 'clojure.test/is}}
                             {:var/name 'x.nsd-test/t1 :var/ns "nt" :var/kind :test :var/form "tf"}
                             {:var/name 'x.nsd/b :var/ns "n"} {:var/name 'x.nsd/c :var/ns "n"}]
                            (for [i (range 20)] {:var/name (symbol "x.nsd" (str "unrelated-" i)) :var/ns "n"})))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'x :seon.task/name "nsd" :seon.task/proofs #{'x.nsd-test/t1}
                                              :seon.task/plan "Make `x.nsd/c` call `x.nsd/b`."}]))
        db (d/db-with db [{:seon.agent/key "b/a1" :seon.agent/task [:seon.task/id "x/nsd"]}])
        tree (:tree (ctx/build db (pdb/eid db :seon.agent/key "b/a1") :b))]
    (is (= '{x {:ns-form (clojure.repl/source x)} x.nsd {:names [b c] :ns-form (clojure.repl/source x.nsd)} x.nsd-test {:names [t1] :ns-form (clojure.repl/source x.nsd-test)}}
           (context/namespaces-of tree))
        "the task's own namespace, then the cited names and the proof's callee, never the namespace's twenty others; a form for the rest")
    (is (= '[x x.nsd x.nsd-test] (keys (context/namespaces-of tree))) "the task's own first, then the cited, then the proofs'")
    (is (= '{:ns-form (clojure.repl/source x)} (get (context/namespaces-of tree) 'x)) "the task's own namespace, cited nowhere: its ns form, no names")
    (let [own (context/namespaces-of (assoc-in tree [:task :seon.task/ns] 'x.nsd))]
      (is (= '[x.nsd x.nsd-test] (keys own)) "a cited own namespace is once, first")
      (is (= '[b c] (:names (get own 'x.nsd))) "with the names cited in it"))
    (is (= '{x {:ns-form (clojure.repl/source x)}}
           (context/namespaces-of (update tree :task dissoc :cites :needs :proved)))
        "a task that cites and proves nothing answers its own namespace")
    (is (= 'x (context/current-ns tree)) "before any eval the REPL is where it starts, the task's namespace")
    (let [moved (assoc tree :evals [[1 {:seon.eval/record {:response {:ns 'y}}}]])]
      (is (= 'y (context/current-ns moved)))
      (is (= '[y x x.nsd x.nsd-test] (keys (context/namespaces-of moved))) "the part follows the REPL, the task's own still drawn"))))

(deftest a-draw-reads-the-cites-its-plan-write-derived
  ;; md-scan #10, 2026-10-29: the agent redefined seon.ref/scan, and every draw that parsed the plan
  ;; again ran its broken parser (the layout, the status, the namespaces part); a task's cites are
  ;; entities its plan's write makes (seon.task/cites-tx), which every draw reads through ctx
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "c" :seon.task/plan "Change `demo/f`."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        names #(set (keep :seon.cite/name (vals (:cites (:tree (ctx/build % (pdb/eid % :seon.task/id "demo/c") :b))))))]
    (is (= '#{demo/f} (names db)) "the cites its write made")
    (let [db (d/db-with db [{:seon.task/id "demo/c" :seon.task/plan "Change `demo/g`."}])]
      (is (= '#{demo/f} (names db)) "a draw reads the entities, never the text again")
      (is (= '#{demo/g} (names (d/db-with db (task/rederive-tx db (task/task db "demo/c")))))
          "and a write of the plan makes them again"))))

(deftest an-old-turns-request-holds-what-it-held-then
  ;; the optimization session, 2026-10-29: turn 0 re-rendered on a later db took the newest call as
  ;; the one before it and every later record as its own; a request is bounded by its turn's tx
  (ts/test-branch!
    (fn [_ conn]
      ;; the wiki, which holds the system prompt every request opens with
      (d/transact conn (doc/load-tx @conn "docs" (doc/pages (seon.config/checkout) "docs")))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}}]))
      (d/transact conn [{:seon.run/id "demo/g#1" :seon.run/task [:seon.task/id "demo/g"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
      (let [basis (fn [i] {:commit (d/commit-id @conn) :task "demo/g" :run "demo/g#1" :turn i :max-turns 9 :origin :model :agent "b/a1"})
            turn! (fn [i] (let [b (basis i)]
                            (d/transact conn [{:db/id "turn" :seon.turn/id (str "demo/g#1/" i) :seon.turn/run {:seon.run/id "demo/g#1"} :seon.turn/agent {:seon.agent/key "b/a1"} :seon.turn/n i :seon.turn/origin :model}
                                              {:seon.model-call/id (str "demo/g#1/" i) :seon.model-call/turn "turn" :seon.model-call/basis b}])
                            (d/transact conn [{:seon.model-call/id (str "demo/g#1/" i) :seon.model-call/sent (:sent (context/render @conn b))}])
                            (d/transact conn [{:seon.eval/id (str "e" i) :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key "b/a1"}
                                               :seon.eval/record {:code (str "(f " i ")") :response {:handle (symbol "result" (str "e" i)) :value i} :line (str i) :run "demo/g#1" :call-id (str "c" i) :call 0}}])
                            b))
            b0 (turn! 0) _ (turn! 1) _ (turn! 2)
            evals (fn [sent] (set (keep (fn [[k x]] (when (= :eval k) x)) sent)))]
        (is (empty? (evals (:sent (context/render @conn b0)))) "turn 0 on the db after turn 2: no eval made after it")
        (is (= #{"e0" "e1"} (evals (:sent (context/render @conn (assoc b0 :turn 2))))) "turn 2: the evals before it")))))

;; ---- example sessions in an agent's context (specs/example-sessions) ----

(defn- source-session!
  "Agent b/src's session on task demo/h, as a curated example would have it: an eval of code that called (its
  value shown as 3), a message and a reply with no call, each in a transaction of its own."
  ([conn] (source-session! conn "(+ 1 2)"))
  ([conn code]
   (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "h" :seon.task/plan "Make h." :seon.task/proofs #{'demo-test/h-test}}]))
   (d/transact conn [{:seon.agent/key "b/src" :seon.agent/task [:seon.task/id "demo/h"]}])
   (d/transact conn [{:seon.eval/id "s1" :seon.eval/by "src" :seon.eval/agent [:seon.agent/key "b/src"]
                      :seon.eval/record {:code code :response {:handle 'result/s1 :value 3} :run "demo/h#1" :call-id "call_1" :call 0 :prose "Add."}}])
   (d/transact conn [{:seon.message/id "m1" :seon.message/agent [:seon.agent/key "b/src"] :seon.message/from "owner" :seon.message/body "Looks right."}])
   (d/transact conn [{:seon.turn/id "demo/h#1/1" :seon.turn/agent [:seon.agent/key "b/src"] :seon.turn/reply "Done."}])))

(defn- example-world!
  "A branch with task demo/g, its agent b/a1 and its session, the system prompt, and example session abc made from b/src
  (`source-session!`, `context/session-tx`) named by the task; answers the session-tx."
  [conn]
  (d/transact conn (doc/load-tx @conn "docs" {context/system-page (slurp (str (seon.config/checkout) "/" context/system-page))}))
  (source-session! conn)
  (let [tx (context/session-tx @conn "b/src" "abc")]
    (doseq [t tx] (d/transact conn t))
    (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}
                                             :seon.task/examples #{[:seon.agent/key "example/abc"]}}]))
    (d/transact conn [{:seon.run/id "demo/g#1" :seon.run/task [:seon.task/id "demo/g"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
    tx))

(defn- turn!
  "Turn n of session demo/g#1 recorded and its request made: {:messages :sent}, the sent refs recorded as the turn does."
  [conn n]
  (let [b {:commit (d/commit-id @conn) :task "demo/g" :run "demo/g#1" :turn n :max-turns 9 :origin :model :agent "b/a1"}
        _ (d/transact conn [{:db/id "turn" :seon.turn/id (str "demo/g#1/" n) :seon.turn/run {:seon.run/id "demo/g#1"} :seon.turn/agent {:seon.agent/key "b/a1"} :seon.turn/n n :seon.turn/origin :model}
                            {:seon.model-call/id (str "demo/g#1/" n) :seon.model-call/turn "turn" :seon.model-call/basis b :seon.model-call/forms 1 :seon.model-call/errors 0 :seon.model-call/red 1 :seon.model-call/ms 1}])
        r (context/render @conn b)]
    (d/transact conn [{:seon.model-call/id (str "demo/g#1/" n) :seon.model-call/sent (:sent r)}])
    r))

(deftest a-task-with-an-example-reads-it-between-the-system-prompt-and-its-own-session
  (ts/test-branch!
    (fn [_ conn]
      (example-world! conn)
      (let [r0 (turn! conn 0)
            _ (d/transact conn [{:seon.eval/id "e0" :seon.eval/by "a1" :seon.eval/agent [:seon.agent/key "b/a1"]
                                 :seon.eval/record {:code "(+ 5 1)" :response {:handle 'result/e0 :value 6} :run "demo/g#1" :call-id "c0" :call 0}}])
            r1 (turn! conn 1)
            ms (:messages r0)
            [sys open & more] ms
            close-at (first (keep-indexed #(when (= "</session>" (:content %2)) %1) more))
            example (subvec (vec more) 0 close-at)
            db @conn
            original (mapcat (fn [[_ v k]] (case k :seon.db/eval-fact (context/eval-messages v {}) :seon.message/fact (context/message-messages v {}) :seon.turn/fact (context/turn-messages v {})))
                             (context/records db (pdb/eid db :seon.agent/key "b/src")))
            prefix #(str/replace (pr-str %) "example-abc-" "")]
        (is (= "system" (:role sys)))
        (is (= {:role "user" :content "<session id=\"example/abc\" kind=\"example\" from=\"seon\">\nA finished session of another task, shown for how its work was done; not yours. Its request:\nMake h."} open) "the example's opening and its request")
        (is (= (prefix (vec original)) (prefix (vec example))) "its records, printed as the agent's own are, call ids prefixed")
        (is (= ["assistant" "tool" "user" "assistant"] (mapv :role example)) "an eval's pair, a message, a reply")
        (is (= "example-abc-call_1" (get-in example [0 :tool-calls 0 :id]) (:tool-call-id (second example))) "every call is followed by its tool message")
        (is (= {:role "user" :content "</session>"} (nth more close-at)))
        (is (= {:role "user" :content "<session id=\"demo/g#1\" from=\"seon\">\nYour own session: the work you are asked to do."} (nth more (inc close-at))) "then the agent's own session")
        (is (str/includes? (:content (nth more (+ 2 close-at))) "Make g.") "then the page, as today")
        (is (= ms (subvec (:messages r1) 0 (count ms))) "the second request starts with the first, examples included")
        (is (= (:messages r1) (into [] (mapcat second) (context/parts-of db (:sent r1)))) "replay draws the same bytes")
        (is (= (:messages r0) (into [] (mapcat second) (context/parts-of db (:sent r0)))))
        (is (= [:example "example/abc"] (vec (take 2 (second (:sent r0))))) "the example is a ref of the request")
        (is (pos? (:examples (context/sent-split (context/parts-of db (:sent r1))))) "counted as its own share")))))

(deftest a-task-with-no-examples-reads-what-it-read-before
  ;; the same task with no :seon.task/examples and with an empty one: the same messages
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (doc/load-tx @conn "docs" {context/system-page (slurp (str (seon.config/checkout) "/" context/system-page))}))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}}]))
      (d/transact conn [{:seon.run/id "demo/g#1" :seon.run/task [:seon.task/id "demo/g"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
      (let [none (turn! conn 0)
            a (pdb/eid @conn :seon.agent/key "b/a1")
            _ (d/transact conn [[:db/add [:seon.task/id "demo/g"] :seon.task/examples a]])
            _ (d/transact conn [[:db/retract [:seon.task/id "demo/g"] :seon.task/examples a]])
            b (:seon.model-call/basis (d/pull @conn [:seon.model-call/basis] [:seon.model-call/id "demo/g#1/0"]))
            empty-set (context/render @conn b)]
        (is (= ["system" "user"] (mapv :role (:messages none))))
        (is (= (:messages none) (:messages empty-set)) "an emptied :seon.task/examples reads as none")
        (is (not-any? #(str/includes? (:content %) "<session") (:messages none)))
        (is (not (contains? (context/sent-split (context/parts-of @conn (:sent none))) :examples)))))))

(deftest an-example-session-is-data-that-recreates-the-session
  (ts/test-branch!
    (fn [_ conn]
      (source-session! conn)
      (let [tx (context/session-tx @conn "b/src" "abc")
            names (fn [db k] (let [a (pdb/eid db :seon.agent/key k)]
                               (mapv (fn [[_ v kind]] (case kind :seon.db/eval-fact (context/eval-messages v {}) :seon.message/fact (context/message-messages v {}) :seon.turn/fact (context/turn-messages v {})))
                                     (context/records db a))))]
        (is (= tx (clojure.edn/read-string (pr-str tx))) "plain data: written with pr-str, read back")
        (is (= {:seon.agent/key "example/abc" :seon.agent/id :example-abc :seon.agent/status :ended :seon.agent/description "Make h."} (ffirst tx)))
        (is (= 4 (count tx)) "the agent, then a transaction for each of three records")
        (ts/test-branch!
          (fn [_ fresh]
            (doseq [t (clojure.edn/read-string (pr-str tx))] (d/transact fresh t))
            (is (= (str/replace (pr-str (names @conn "b/src")) "example-abc-" "") (str/replace (pr-str (names @fresh "example/abc")) "example-abc-" ""))
                "the example draws the same messages as the original's records")
            (is (= ["example/abc/s1" "example/abc/m1" "example/abc/demo/h#1/1"] (mapv (fn [[_ v]] (or (:seon.eval/id v) (:seon.message/id v) (:seon.turn/id v))) (context/records @fresh (pdb/eid @fresh :seon.agent/key "example/abc"))))
                "ids re-keyed under the example, in the order they ran")))))))

(deftest the-page-draws-its-tasks-examples-with-evaluation
  (ts/test-branch!
    (fn [_ conn]
      (example-world! conn)
      (let [db @conn
            a (pdb/eid db :seon.agent/key "b/a1")
            w (render/draw (:tree (ctx/build db a :b)) ctx/changes db (render/declared-var :seon.render/render-ctx))
            html (try (pr-str (render/placed-hiccup w ["#p-tiles"])) (finally (render/dispose-walk! w)))]
        (is (str/includes? html "example session example/abc"))
        (is (str/includes? html "(+ 1 2)") "its eval, drawn by evaluation")))))


(defn- no-example-requests!
  "Two requests of the fixed fixture of task demo/g, named by no example: its session of one eval, one message and one
  prose reply, on conn (a branch with the system prompt loaded); answers {:r0 :r1}."
  [conn]
  (d/transact conn (doc/load-tx @conn "docs" {context/system-page (slurp (str (seon.config/checkout) "/" context/system-page))}))
  (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}}]))
  (d/transact conn [{:seon.run/id "demo/g#1" :seon.run/task [:seon.task/id "demo/g"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
  (let [r0 (turn! conn 0)]
    (d/transact conn [{:seon.eval/id "e0" :seon.eval/by "a1" :seon.eval/agent [:seon.agent/key "b/a1"]
                       :seon.eval/record {:code "(+ 5 1)" :response {:handle 'result/e0 :value 6} :run "demo/g#1" :call-id "c0" :call 0 :prose "Add."}}])
    (d/transact conn [{:seon.message/id "m0" :seon.message/agent [:seon.agent/key "b/a1"] :seon.message/from "owner" :seon.message/body "Go on."}])
    (d/transact conn [{:seon.turn/id "demo/g#1/1x" :seon.turn/agent [:seon.agent/key "b/a1"] :seon.turn/reply "Thinking."}])
    {:r0 r0 :r1 (turn! conn 1)}))

(defn- ids-as-n
  "Messages ms with the entity ids in the page's part names (-457, the test store's seed) read as -N."
  [ms] (mapv #(update % :content str/replace #"-\d+\"" "-N\"") ms))

(def ^:private no-example-page
  "The page's part message main sent the fixture's agent, entity ids as -N."
  "{\"task-data-N\"\n   {:to-prove {demo-test/g-test \"not written yet\"},\n    :task #:seon.task{:id \"demo/g\", :kind :work, :plan \"Make g.\"}},\n \"namespaces-N\" {demo {:ns-form (clojure.repl/source demo)}},\n \"status-N\"\n   {:proofs [{:test demo-test/g-test, :status :undefined}],\n    :refused\n      [{:reason :proof-unknown,\n        :test demo-test/g-test,\n        :do\n          (do\n           (in-ns 'demo-test)\n           (clojure.test/deftest\n            g-test\n            (clojure.test/is false\n             \"write the assertion: the behaviour, on data\"))\n           (my.agent/prove))}]},\n \"run-head-N\" {:turn 1, :ns demo}}")

;; The no-examples path only gains an empty ex-items (seon.context/request), so these literals are the branch's own
;; output for it; the tests assert them and a digest, so a later change to that path goes red. They are main's request
;; (9a35ff81ea, which holds no example) for the fixed fixture of `no-example-requests!`, its turn part naming the
;; namespace the REPL starts in, the task's (seon.context/current-ns, docs/agent.md#the-repl).
(deftest a-task-with-no-examples-sends-the-first-request-main-sent
  (ts/test-branch!
    (fn [_ conn]
      (let [{:keys [r0]} (no-example-requests! conn)]
        (is (= {:role "system" :content (context/system-text @conn)} (first (:messages r0))))
        (is (= [{:role "user" :content no-example-page}] (ids-as-n (rest (:messages r0)))))))))

(deftest a-task-with-no-examples-sends-the-conversation-main-sent
  (ts/test-branch!
    (fn [_ conn]
      (let [{:keys [r1]} (no-example-requests! conn)
            ms (ids-as-n (rest (:messages r1)))]
        (is (= [{:role "user" :content no-example-page}
                {:role "assistant" :content "" :tool-calls [{:id "c0" :code "(+ 5 1)" :prose "Add."}] :call 0}
                {:role "tool" :tool-call-id "c0" :content "{:handle result/e0, :value 6}"}
                {:role "user" :content "{:from \"owner\", :body \"Go on.\"}"}
                {:role "assistant" :content "Thinking."}
                {:role "user" :content "{\"run-head-N\" {:changed {:turn 3}}}"}]
               ms))
        (is (= "772edec9a84dece1" (seon.lib/digest (str/join "\n--\n" (map #(str (:role %) ":" (:content %)) ms))))
            "and the digest of its roles and contents (SHA-256, first 16 hex digits): a change to this path moves it")))))

(deftest session-tx-answers-an-error-for-what-it-cannot-show
  (ts/test-branch!
    (fn [_ conn]
      (source-session! conn)
      (d/transact conn [{:seon.agent/key "b/bare"}])
      (let [none (context/session-tx @conn "b/none" "x")
            bare (context/session-tx @conn "b/bare" "x")]
        (is (= [:seon.context/session-refused "b/none"] ((juxt :seon.error/type :key) none)) "no agent has the key")
        (is (str/includes? (:seon.error/message none) "no agent is keyed"))
        (is (= [:seon.context/session-refused "b/bare"] ((juxt :seon.error/type :key) bare)) "an agent with no plan and no description has no request to show: not an empty one")
        (is (str/includes? (:seon.error/message bare) "no request to show"))
        (is (vector? (context/session-tx @conn "b/src" "x")) "an agent that has a request is shown")))))

(deftest an-example-keeps-no-id-of-the-session-it-was-made-from
  ;; its eval's :run is the example's id (nothing reads an example's evals by the source session's), and the
  ;; result handle its response and printed text name is the example's
  (ts/test-branch!
    (fn [_ conn]
      (source-session! conn)
      (d/transact conn [{:seon.eval/id "s2" :seon.eval/by "src" :seon.eval/agent [:seon.agent/key "b/src"]
                         :seon.eval/record {:code "(atom 1)" :response {:handle 'result/s2} :printed "#atom result/s2" :run "demo/h#1" :call-id "call_2" :call 1}}])
      (let [recs (->> (context/session-tx @conn "b/src" "abc") (mapcat identity) (keep :seon.eval/record))]
        (is (= 2 (count recs)))
        (is (= #{"example/abc"} (set (map :run recs))))
        (is (= '#{result/example-abc-s1 result/example-abc-s2} (set (map #(get-in % [:response :handle]) recs))))
        (is (= "#atom result/example-abc-s2" (:printed (second recs))) "a result printed as recorded names the same handle")))))

(defn- two-examples-request!
  "Two sessions whose records carry the same ids (s1, call_1, result/s1: ids are an agent's own), loaded into one db
  in the order def, abc and named by task demo/g; answers {:r0 :r1}, requests 0 and 1 of its session."
  [conn]
  (let [from (fn [id code] (ts/test-branch! (fn [_ c] (source-session! c code) (context/session-tx @c "b/src" id))))
        tx-a (from "abc" "(+ 1 2)")
        tx-b (from "def" "(+ 3 4)")]
    (d/transact conn (doc/load-tx @conn "docs" {context/system-page (slurp (str (seon.config/checkout) "/" context/system-page))}))
    (doseq [t (concat tx-b tx-a)] (d/transact conn t))
    (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}
                                             :seon.task/examples #{[:seon.agent/key "example/def"] [:seon.agent/key "example/abc"]}}]))
    (d/transact conn [{:seon.run/id "demo/g#1" :seon.run/task [:seon.task/id "demo/g"] :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
    (let [r0 (turn! conn 0)] {:r0 r0 :r1 (turn! conn 1)})))

(deftest two-examples-keep-their-order
  (ts/test-branch!
    (fn [_ conn]
      (let [{:keys [r0 r1]} (two-examples-request! conn)
            ms (:messages r0)
            opens (keep-indexed (fn [i m] (when (str/starts-with? (:content m "") "<session id=\"example/") i)) ms)]
        (is (= [:example "example/abc"] (vec (take 2 (second (:sent r0))))) "in key order, whatever order they were loaded")
        (is (= [[:example "example/def"]] (mapv #(vec (take 2 %)) (filter #(= :example (first %)) (drop 2 (:sent r0))))))
        (is (= [1 7] (vec opens)) "each example opens after the system message and the one before it closes")
        (is (= ["<session id=\"example/abc\" kind=\"example\" from=\"seon\">" "<session id=\"example/def\" kind=\"example\" from=\"seon\">"]
               (mapv #(first (str/split-lines (:content (nth ms %)))) opens)))
        (is (= ["</session>" "</session>"] (mapv #(:content (nth ms %)) [6 12])))
        (is (= [["assistant" "tool" "user" "assistant"] ["assistant" "tool" "user" "assistant"]] [(mapv :role (subvec ms 2 6)) (mapv :role (subvec ms 8 12))]) "each in the order its records ran")
        (is (= ms (subvec (:messages r1) 0 (count ms))) "both examples are the prefix of the next request")
        (is (= (:messages r1) (into [] (mapcat second) (context/parts-of @conn (:sent r1)))) "replay draws the same bytes")))))

(deftest two-examples-keep-their-call-ids-and-handles-apart
  (ts/test-branch!
    (fn [_ conn]
      (let [{:keys [r0]} (two-examples-request! conn)
            ms (:messages r0)
            calls (keep #(get-in % [:tool-calls 0 :id]) ms)
            tool-text (fn [id] (:content (first (filter #(= id (:tool-call-id %)) ms))))]
        (is (= ["example-abc-call_1" "example-def-call_1"] (vec calls)) "no call id is another's")
        (is (= (vec calls) (vec (keep :tool-call-id ms))) "every call is followed by its tool message")
        (is (str/includes? (tool-text "example-abc-call_1") "result/example-abc-s1"))
        (is (str/includes? (tool-text "example-def-call_1") "result/example-def-s1"))
        (is (not-any? #(str/includes? (str (:content %)) "result/s1") ms) "no handle is the live agent's (result/s1)")
        (let [ids (:ids (:printed r0))]
          (is (apply distinct? ids))
          (is (every? #(str/includes? % "/") ids) "each id is its kind's"))))))
