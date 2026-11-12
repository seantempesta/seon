(ns seon.ctx.wire-test
  "The agent's current-state block says each thing once: a passing proof is its status, a
  red one its source and its failures (expected and actual), a reference already shown as a
  proof is not shown again, a merge task's plan stays its own."
  (:require [my.branch] [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.db :as pdb] [seon.vector :as vector] [seon.task :as task] [seon.ctx.search :as search] [seon.ctx.session :as session] [my.schema] [seon.ctx.status :as status] [seon.ctx.wire :as wire] [seon.print :as print]
            [seon.doc-test] [seon.reader :as reader] [seon.proofs :as proofs] [seon.agent :as agent] [seon.doc :as doc] [seon.judge] [seon.ctx :as ctx] [seon.fixtures] [seon.render :as render] [seon.render.ui] [seon.web.html] [my.test :as t]
            [seon.print.select :as select] [seon.ratom] [clojure.walk] [clojure.string :as str] [seon.lib] [seon.edn] [seon.print-test :as print-test] [seon.repl :as repl] [seon.model] [seon.config] [clojure.data.json] [seon.render-cost-test]))

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
                                 ;; a var the tests' plans cite (a cite names what exists: docs/references.md#an-entity-cites)
                                 [demo/g "(defn g \"Halves.\" [x] (/ x 2))"]
                                 [demo-test/f-test "(deftest f-test (is (= 4 (f 2))))"]
                                 [demo-test/g-test "(deftest g-test (is (= 1 (g))))"]
                                 ;; a test the agent wrote in its scratch namespace, no proof of its task
                                 [user/scratch-test "(deftest scratch-test (is (= 1 2)))"]])))))

(defn- data-of
  "Task id's data on db (seon.ctx.status/task-data), its cites read as a reader that is no draw reads them (seon.task/task-cites)."
  [db id] (status/task-data db (task/task db id) (task/task-cites db id)))

(defn- status-at
  "How agent b/a1's work on task tid stands (seon.ctx.status/status) on db once the facts the source
  reads are on it: vs, {test [status failures]}, each test's :seon.test/status and
  :seon.test/failures on its var entity (seon.proofs/result-tx's), and the agent."
  ([db tid vs] (status-at db tid vs {}))
  ([db tid vs opts]
   (let [db (d/db-with db (into [{:seon.agent/key "a1" :seon.agent/task [:seon.task/id tid]}]
                                (for [[t [st fs]] vs]
                                  (cond-> {:var/name t :seon.test/status st} (seq fs) (assoc :seon.test/failures (vec fs))))))]
     ;; read from the agent's record as the page's status part reads it (its task's proofs)
     (status/status db (:tree (seon.ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "a1") :b)) opts))))

(defn- index-of
  "main's claims as the reconciler's index holds them (seon.reconcile/index), with only what the done rule
  reads: acc {task proofs}, claims {test #{tasks}}, done {task {:merged-t}}."
  [acc claims done]
  (assoc seon.reconcile/empty-index :acc acc :claims claims :done done))

(defn- wiki!
  "The checkout's system prompt into conn (seon.doc/system-page), as root's boot loads it with the rest of the wiki
  (seon.system/start!): the one page a request reads (the help page is read on demand, by `doc`). The whole wiki
  (1.4 MB, 40 pages) cost a request test 180 M ticks to load and check, and moved none of its bytes."
  [conn] (d/transact conn (doc/load-tx @conn "docs" {doc/system-page (slurp (str (seon.config/checkout) "/" doc/system-page))})))

(defn- un-example
  "Text s with each id example ek made of an id of db (seon.ctx.session's in-example: seon.lib/id of the digest of ek and it)
  written back as the id it was made of: how an example's records compare with the session's they copy."
  [db ek s]
  (reduce (fn [s x] (str/replace s (seon.lib/id (seon.lib/digest (str ek "/" x))) x)) s
          (into [] (comp cat (filter string?)) [(d/q '[:find [?v ...] :where [_ :seon.eval/id ?v]] db)
                                                (d/q '[:find [?v ...] :where [_ :seon.eval/call-id ?v]] db)
                                                (d/q '[:find [?v ...] :where [_ :seon.message/id ?v]] db)])))

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

(deftest the-turn-component-is-the-namespace-and-nothing-else
  (let [part (seon.render.ui/turn {:seon.ctx/ctx {:seon.agent/status :busy :seon.agent/max-turns 40 :seon.agent/ns 'seon.program :seon.agent/turn 39
                                                          :task {:seon.task/id "seon.program/a-var-is-read"}}})]
    (is (= "You're in seon.program." (second (second part))) "the heading is the namespace the REPL is in, whatever the turn and the cap")
    (is (nil? (:wire/for (second (second part)))) "the model's component holds the heading only")))

(deftest a-records-text-is-the-same-whatever-came-before
  ;; task seon.ctx/the-context-is-the-agents-evals-and-seons: a record's printed text is a
  ;; function of that record alone (docs/render.md). Records with equal values, and one whose code
  ;; repeats an earlier one's, print the same bytes after other records as each printed alone.
  (let [rows (vec (for [i (range 200)] {:row i :name (str "row-" i)}))
        recs {"e1" {:code "(rows)" :value rows :out "---- budget 16\n[:kw …]\n"}
              "e2" {:code "(rows)" :value (vec rows) :out "---- budget 16\n[:kw …]\n"}
              "e3" {:code "(other)" :value (vec rows) :out "other\n"}}
        db-of (fn [ids] (d/db-with (db-with-program) (for [id ids :let [r (recs id)]]
                                                      {:seon.eval/id id :seon.eval/by {:seon.agent/key "a1"}
                                                       :seon.eval/code (:code r) :seon.eval/call-id (str "c" id) :seon.eval/response {:handle (symbol "result" id) :value (:value r) :out (:out r) :ms 1}})))
        record (fn [db id] (seon.fixtures/eval-messages {:db db} (d/pull db '[*] [:seon.eval/id id])))
        after (db-of ["e1" "e2" "e3"])
        text #(:content (peek (record after %)))]
    (doseq [id ["e1" "e2" "e3"]]
      (is (= (record after id) (record (db-of [id]) id)) (str id " prints the same bytes after other records as alone")))
    (is (str/includes? (text "e2") "---- budget 16") "the repeated answer prints whole")
    (is (not (str/includes? (text "e3") "(:value result/")) "an equal value is printed, not read from an earlier handle")))

(defn- db-f
  "db with the task demo/f, whose proof is demo-test/f-test."
  [db] (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p"
                                          :seon.task/proofs #{'demo-test/f-test}}])))

(defn- task-f [db] (task/task (db-f db) "demo/f"))

(deftest a-reason-and-a-test-run-error-reach-the-context
  (let [db (db-f (db-with-program))
        failure {:type :fail :expected "(= 1 (g))" :actual "(not (= 1 0))" :got "(= 1 0)"}
        ;; main's claims (seon.reconcile/index): g-test is a merged task's proof, so red here it is a regression
        index (fn [] (index-of {"demo/f" #{'demo-test/f-test}} {'demo-test/g-test #{"demo/g"}} {"demo/g" {:merged-t 1}}))
        ;; the done rule refused beyond the task's own proof: a regression and a stray red; the test run named one
        ;; failing assertion of the red test
        st (status-at db "demo/f" {'demo-test/f-test [:red] 'demo-test/g-test [:red [failure]] 'user/scratch-test [:red]} {:index index})]
    (testing "every reason but the task's own red proof (shown with the proofs), each with what explains it"
      (is (= [{:reason :regression :test 'demo-test/g-test :failures [{:assert (print/code "(= 1 (g))") :got (print/code "(= 1 0)")}]
               :source (print/code "(deftest g-test (is (= 1 (g))))")}
              {:reason :stray-red :test 'user/scratch-test :source (print/code "(deftest scratch-test (is (= 1 2)))")
               :do '(my.task/update! :seon.task/proofs conj (quote user/scratch-test))}]
             (:refused st)))
      (is (= [:proof-red :regression :stray-red] (mapv :reason (:reasons st))) "the done rule's own answer, beside it")
      (is (not (re-find #":proof-red" (pr-str (seon.render.ui/status-hiccup {} st)))) "and the page and the model are told each as what to do, not the raw reasons"))
    (testing "a test run that failed as a whole is said, not read as tests not run"
      (let [err {:seon.error/type :seon.proofs/test-run-died :seon.error/operation 'seon.agent/test-run :seon.error/message "namespace app-test did not load: Unable to resolve symbol: g"}
            db (d/db-with db [{:seon.agent/key "a1" :seon.agent/task [:seon.task/id "demo/f"] :seon.agent/test-error err}])
            st (status/status db (:tree (seon.ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "a1") :b)) {})]
        (is (= {:failed ["namespace app-test did not load: Unable to resolve symbol: g"]} (:test-run st)))
        (is (= [{:test 'demo-test/f-test :status :not-run :source (print/code "(deftest f-test (is (= 4 (f 2))))")}] (:proofs st)) "a written proof did not run")
        (is (re-find #"did not load" (pr-str (seon.render.ui/status-hiccup {} st))) "drawn, it is there"))
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
  ;; owner, 2026-10-28: the forgiving reader may complete a form or make text a comment, and
  ;; says exactly what it did on that call's own result (:repaired, docs/prompt.md)
  (let [code "Here is f.\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)))"
        {:keys [text repairs]} (reader/forgive code)
        r (search/repaired text repairs)]
    (is (some #{{:near {:line 2 :column 60 :text "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)),"} :dropped ")" :inserted ","}} r)
        "the extra close: what was dropped, what replaced it, and where")
    (is (some #{{:near {:line 1 :column 1 :text ";; Here is f."} :inserted ";; "}} r)
        "and the text made a comment, told too")
    (is (= [] (search/repaired "(+ 1 2)" (:repairs (reader/forgive "(+ 1 2)")))) "code that reads is not repaired")))

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
    (is (agent/judges? 'ctx-prog-ns/p-test))
    (is (not (agent/judges? 'ctx-scratch-ns/s-test)))))

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
                                      {:var/meta #{:arglists} :var/contract "[:=> [:cat :int] :int]" :var/doc "Squares x."}]
                                     ;; a branch's function in a namespace this process never loaded
                                     [ctx-ghost/half "(defn half {:malli/schema [:=> [:cat :int] :int]} [x] (quot x 2))" [clojure.core/quot]
                                      {:var/meta #{:arglists} :var/contract "[:=> [:cat :int] :int]"}]
                                     [ctx-calc-test/square-test "(deftest square-test (is (= 9 (square 3))))" [clojure.test/is ctx-calc/square ctx-ghost/half]
                                      {:var/meta #{:test}}]
                                     [ctx-calc-test/empty-test "(deftest empty-test (square 3))" [ctx-calc/square] {:var/meta #{:test}}]
                                     [ctx-calc-test/stray-test "(deftest stray-test (is (= 2 (square 1))))" [clojure.test/is ctx-calc/square] {:var/meta #{:test}}]])))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'ctx :seon.task/name "sq" :seon.task/plan "p"
                                               :seon.task/proofs #{'ctx-calc-test/square-test 'ctx-calc-test/cube-test 'ctx-calc-test/empty-test}}]))
        ;; the test child's report of the failing assertion, as clojure.test makes it
        rep (agent/test-report {:type :fail :expected '(= 9 (square 3)) :actual '(not (= 9 6))} "#'ctx-calc-test/square-test")
        block (status-at db "ctx/sq" {'ctx-calc-test/square-test [:red (get (proofs/failures {:reports [rep]}) 'ctx-calc-test/square-test)]})
        by (into {} (map (juxt :test identity)) (:proofs block))]
    (is (= [{:assert (print/code "(= 9 (square 3))") :got (print/code "(= 9 6)")}] (:failures (by 'ctx-calc-test/square-test)))
        "the assertion once, and what it evaluated to")
    ;; a callee's docstring was about 2.4k
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
    (is (= {:test 'ctx-calc-test/cube-test :status :undefined} (dissoc (by 'ctx-calc-test/cube-test) :shape :says))
        "a proof not written yet says so")
    (is (= :unknown (:status (by 'ctx-calc-test/empty-test))) "a proof no report named never ran: the judge's :proof-unknown")
    (let [db (d/db-with db [{:seon.agent/key "a1" :seon.agent/task [:seon.task/id "ctx/sq"]
                             :seon.agent/test-error {:seon.error/type :seon.proofs/test-run-died :seon.error/operation 'seon.agent/test-run :seon.error/message "namespace app-test did not load"}}])
          b (status/status db (:tree (seon.ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "a1") :b)) {})]
      (is (= {'ctx-calc-test/square-test :not-run 'ctx-calc-test/empty-test :not-run 'ctx-calc-test/cube-test :undefined}
             (into {} (map (juxt :test :status)) (:proofs b)))
          "when the test run failed as a whole, a written proof did not run"))))

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

;; docs/references.md#an-entity-cites: a cite of a schema key joins its entry, of a namespace its entity, of a file its
;; entity, each drawn by the cite's render from the db by what the cite names
(deftest a-cited-schema-key-and-namespace-show-what-they-are
  (let [db (d/db-with (db-with-program) [{:ns/name 'demo.calc :ns/doc "Adds. More."} {:schema/key :x.s/block :schema/form "[:map [:x.s/from :int]]"}
                                         {:file/path "bin/env" :file/text "#!/bin/sh"}])
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "n"
                                               :seon.task/plan "Read `demo.calc`, `:x.s/block` and `bin/env`."}]))
        t (:tree (ctx/build (seon.db/index) db (pdb/eid db :seon.task/id "demo/n") :b))
        m (into {} (for [c (vals (:seon.cite/_of t))] [(task/cited-name c) (seon.render/part-text [(quote seon.render.ui/cite) {} (seon.render.ui/cite {:seon.db/db db :seon.render/index (seon.db/index)} c)])]))]
    (is (str/includes? (m 'demo.calc) "Adds.") "a namespace: the first sentence of its doc")
    (is (str/includes? (m 'demo.calc) "(doc 'demo.calc)") "and the form that reads it")
    (is (str/includes? (m :x.s/block) ":int") "a schema key: what it holds")
    (is (not (str/includes? (m :x.s/block) "my.schema/register!")) "its change is taught once, not on every cite")
    (is (str/includes? (m "bin/env") "(slurp \"bin/env\")") "a file: the form that reads it")))

(deftest an-agent-reads-its-conversation-so-far
  ;; a live DeepSeek agent re-read the same Datahike source every other turn because each
  ;; context showed only the previous turn's evaluations (2026-10-21); a REPL keeps its
  ;; scrollback. Each eval renders its own part (seon.seon.fixtures/eval-messages): the assistant message with
  ;; the call, then the response as a tool message; the provider's format folds a reply's calls
  ;; into one assistant message (docs/render.md, the request is a render)
  (let [ev (fn [id value] {:seon.eval/id id :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/call (seon.task/call-ref "a1" 1)
                           :seon.eval/code (str "(" id ")") :seon.eval/response {:handle (symbol "result" id) :value value} :seon.eval/call-id (str "c-" id)})
        db (d/db-with (db-with-program) (into (ts/turn-tx "a1" 1 {}) [(ev "e1" "E1") (ev "e2" "E2") (ev "e3" "E3")]))
        ;; as the walk hands them to the model render: the entities of the db, a reply naming its number by the call it belongs to
        ms (into [] (mapcat #(seon.fixtures/eval-messages {:db db} (d/pull db '[*] [:seon.eval/id %]))) ["e1" "e2" "e3"])]
    (is (= ["assistant" "tool" "assistant" "tool" "assistant" "tool"] (mapv :role ms)) "each eval its call and its response")
    (is (= {:role "assistant" :content "" :tool-calls [{:id "e1" :code "(e1)"}] :reply 1} (first ms)) "the call alone, naming its reply")
    (is (= {:role "tool" :tool-call-id "e1" :content "{:handle result/e1, :value \"E1\"}"} (second ms)))
    (let [wire (seon.model/wire-messages true ms)]
      (is (= ["assistant" "tool" "tool" "tool"] (mapv :role wire)) "a reply's three evals are one assistant message, and each result a tool message")
      (is (= ["e1" "e2" "e3"] (mapv :id (:tool_calls (nth wire 0)))))
      (is (= ["(e1)" "(e2)" "(e3)"] (mapv #(get (clojure.data.json/read-str (get-in % [:function :arguments])) "code") (:tool_calls (nth wire 0)))))
      (is (= ["e1" "e2" "e3"] (mapv :tool_call_id (rest wire))) "each answers its own call")
      (is (every? some? (map #(re-find (re-pattern (str "result/e" %)) (:content %2)) [1 2 3] (rest wire))) "each with its handle, in order")
      (is (not-any? #(contains? % :reply) wire) "the reply's number never reaches the wire")))
  (testing "a user's form is one user message carrying its response"
    (is (= [{:role "user" :content "{:by \"user\" :code \"(+ 1 2)\" :response {:handle result/e4, :value 3}}"}]
           (seon.fixtures/eval-messages {} {:seon.eval/id "e4" :seon.eval/by {:seon.agent/key "user"} :seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/e4 :value 3}})))))

(deftest an-inserted-eval-reaches-the-model-as-a-call-of-its-own
  ;; docs/turn.md#an-eval-can-be-inserted: an eval someone asked before (or between) the model's turns has intent and
  ;; no call id. Its request is made from its own id, intent and code, in the normal format: the assistant message that
  ;; made the call, then the tool message. Nothing is stored for it, and no reader asks who ran it.
  (let [ev (fn [id by turn] {:seon.eval/id id :seon.eval/by by :seon.eval/intent "Read the pattern." :seon.eval/code "(:seon.ctx/pattern @ctx)"
                             :seon.eval/response {:handle (symbol "result" id) :value [:a]}})
        ms (seon.fixtures/eval-messages {} (ev "e1" "root" 0))]
    (is (= [{:role "assistant" :content "" :tool-calls [{:id "e1" :code "(:seon.ctx/pattern @ctx)" :intent "Read the pattern."}]}
            {:role "tool" :tool-call-id "e1" :content "{:handle result/e1, :value [:a]}"}]
           ms)
        "a call made from the eval's own intent and code, answered by its response; turn 0 has no reply to name")
    (is (= ms (seon.fixtures/eval-messages {} (ev "e1" "anyone at all" 0))) "who asked is read by no one")
    (testing "after a model's reply it stands alone: the reply's own message is never edited"
      (let [model-eval {:seon.eval/id "e4" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/code "(e4)" :seon.eval/call-id "c-e4"
                        :seon.eval/response {:handle 'result/e4 :value 1}}
            conv (into [] (mapcat #(seon.fixtures/eval-messages {} %)) [model-eval (ev "e5" "root" 2)])
            wire (seon.model/wire-messages true conv)]
        (is (not-any? :reply (seon.fixtures/eval-messages {} (ev "e5" "root" 2))) "it names no reply")
        (is (= ["assistant" "tool" "assistant" "tool"] (mapv :role wire)) "two calls of two messages, not one folded into the other")
        (is (= ["e4"] (mapv :id (:tool_calls (nth wire 0)))) "the reply's message holds only its own call")
        (is (= ["e5"] (mapv :id (:tool_calls (nth wire 2)))))))
    (testing "an eval with no text is still one user message, as an outside agent's always was"
      (is (= 1 (count (seon.fixtures/eval-messages {} (dissoc (ev "e6" "outside" 3) :seon.eval/intent))))))))

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
      (d/transact conn [{:seon.agent/key "a1" :seon.agent/task [:seon.task/id "demo/f"] :seon.agent/base-commit (d/commit-id @conn)}
                        {:var/name 'demo-test/f-test :seon.test/status :green}])
      (d/transact conn (program-facts '[[demo/helper "(defn helper [x] x)" []]]))
      (d/transact conn [{:var/name 'demo/helper :var/meta #{:arglists}}])
      (let [db @conn st (status/status db (:tree (seon.ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "a1") :b)) {})]
        (is (= ['demo/helper] (:without-a-contract st)))
        (is (= [{:reason :contract-gap :vars ['demo/helper]}] (:refused st)))))))

(deftest a-red-main-was-red-on-too-is-the-agents-and-is-said
  ;; the owner, 2026-11-07: "I don't care who fixes the problems on main, if an agent needs to fix the problem to get all the tests to pass then that's fine".
  ;; A test red at the branch's merge base still has to clear, and the status says it's also red on main; only a test the branch
  ;; wrote or changed is offered the form that makes it a proof
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (program-facts '[[demo-test/env-test "(deftest env-test (is (pinned?)))" []]
                                        [demo-test/kept-test "(deftest kept-test (is (= 1 1)))" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p" :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:var/name 'demo-test/env-test :seon.test/status :red} {:var/name 'demo-test/kept-test :seon.test/status :green}])
      (d/transact conn [{:seon.agent/key "a1" :seon.agent/task [:seon.task/id "demo/f"] :seon.agent/base-commit (d/commit-id @conn)}])
      (d/transact conn (program-facts '[[demo-test/own-test "(deftest own-test (is (= 1 2)))" []]]))
      (d/transact conn [{:var/name 'demo-test/f-test :seon.test/status :green} {:var/name 'demo-test/own-test :seon.test/status :red}
                        {:var/name 'demo-test/kept-test :seon.test/status :red}])
      (let [db @conn
            st (status/status db (:tree (seon.ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "a1") :b))
                               {:index (fn [] (index-of {"demo/f" #{'demo-test/f-test}} {} {}))})
            by (into {} (map (juxt :test identity)) (:refused st))]
        (is (= #{'demo-test/own-test 'demo-test/kept-test 'demo-test/env-test} (set (keys by))) (str "main's own red is a reason too: " (pr-str (:refused st))))
        (is (= ['demo-test/env-test] (:also-red-on-main st)) "and said as a fact")
        (is (= '(my.task/update! :seon.task/proofs conj (quote demo-test/own-test)) (:do (by 'demo-test/own-test)))
            "a red test the branch wrote is offered the form that makes it a proof")
        (is (not (contains? (by 'demo-test/kept-test) :do)) "main's test the work turned red is not")))))

(defn- turn-of
  "A recorded model turn n (the agent b/a1's) with basis b: its number and its model call (the
  maps of one transaction)."
  [b n]
  (ts/turn-tx "a1" n {:seon.model-call/reply (str "reply " n) :seon.model-call/basis b :seon.model-call/forms 1
                                   :seon.model-call/ms 1}))

(defn- page-call?
  "Whether message m is a call Seon generated to show the record or the page: an assistant message whose one call's code is (my.agent/describe @ctx),
  (my.render/page ..) or (my.render/diff ..)."
  [m] (boolean (and (= "assistant" (:role m)) (some #(re-find #"^\((my\.render/(page|diff)|my\.agent/describe) " (str (:code %))) (:tool-calls m)))))

(defn- page-code
  "The code of the generated page call before the last message of ms (the tool message holding its result), else nil."
  [ms] (let [c (peek (pop ms))] (when (and (page-call? c) (= "tool" (:role (peek ms))) (= (:tool-call-id (peek ms)) (:id (first (:tool-calls c))))) (:code (first (:tool-calls c))))))

(defn- without-page
  "Messages ms but for the generated page calls and their results: the evals the agent made, as a test of them reads them."
  [ms] (let [ids (set (keep #(when (page-call? %) (:id (first (:tool-calls %)))) ms))]
         (vec (remove #(or (page-call? %) (contains? ids (:tool-call-id %))) ms))))

(deftest the-prefix-stays-byte-identical-across-turns
  ;; a provider caches a byte-identical message
  ;; prefix, so each request starts with the whole of the one before, even after the program the
  ;; task cites, or the task's plan, changed: the page is a generated eval, a call (my.render/page p) and its result, at the session's first turn
  ;; and the parts that moved are another turn's after it (docs/render.md#a-change-is-edits-by-name), each message a function of itself
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (program-facts '[[demo/f "(defn f \"Doubles.\" [x] (* 2 x))" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make ```demo/f``` triple."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:seon.task/id "demo/f" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/f"}}}])
      (let [call! (fn [n] (d/transact conn [{:seon.eval/id (str "e" n) :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"}
                                             :seon.eval/code (str "(+ " n " 1)") :seon.eval/response {:handle (symbol "result" (str "e" n)) :value (inc n)} :seon.eval/call (seon.task/call-ref "a1" n) :seon.eval/call-id (str "c" n) :seon.eval/intent (str "Step " n ".")}]))
            ;; as seon.turn goes: the turn recorded, its request made on the db that holds it
            request (fn [n] (let [b {:commit (d/commit-id @conn) :task "demo/f" :turn n :max-turns 4 :origin :model :agent "a1"}
                                  _ (d/transact conn (ts/turn-tx "a1" n {:seon.model-call/basis b}))
                                  {:keys [messages]} (wire/render @conn b)]
                              (d/transact conn (turn-of b n))
                              (call! n)
                              messages))
            r0 (request 1)
            _ (d/transact conn (program-facts '[[demo/f "(defn f \"Triples.\" [x] (* 3 x))" []]]))
            r1 (request 2)
            _ (d/transact conn [[:db/add [:seon.task/id "demo/f"] :seon.task/plan "Make ```demo/f``` quadruple."]])
            r2 (request 3)]
        (is (= r0 (subvec r1 0 (count r0))) "turn 1 starts with turn 0's request, whole")
        (is (= r1 (subvec r2 0 (count r1))) "turn 2 starts with turn 1's request, whole")
        (is (= (wire/system-text @conn) (:content (first r0))) "the system message is the system prompt as main holds it")
        (is (str/starts-with? (:content (first r0)) "# You are a Seon agent\n\nYou're at a Clojure REPL") "the page, from its sections")
        (is (= ["system" "user" "assistant" "tool" "assistant" "tool"] (mapv :role r0)) "the first request: the system prompt, what opens the session, then the record and the page whole, each a generated eval and its result")
        (is (= "(my.agent/describe @ctx)" (get-in r0 [2 :tool-calls 0 :code])) "the record described first, never printed whole (owner, 2026-11-11: \"Instead of dumping the whole @ctx value we are now doing this more metadata approach\")")
        (is (str/includes? (:content (nth r0 3)) "[:seon.agent/task]") "its entries name the task by its path")
        (is (not (str/includes? (:content (nth r0 3)) "Make ```demo/f``` triple")) "and never the plan's text: its size")
        (is (= 1 (count (filter #(= "(my.agent/describe @ctx)" (str (get-in % [:tool-calls 0 :code]))) r2))) "shown once, at the first point")
        (is (str/starts-with? (page-code r0) "(my.render/page {:commit #uuid \"") "the page is the call (my.render/page p)")
        (is (every? #(re-find % (:content (peek r0))) [#":render seon.render.ui/cite" #"Doubles\." #":render seon.render.ui/status"]) "the page whole, each part by the function that drew it: how the work stands too")
        (is (str/starts-with? (page-code r1) "(my.render/diff {:commit #uuid \"") "what moved is a generated eval of its own at the end, after turn 1's own eval")
        (is (re-find #"\[\"page\" \"p-task\" \"task-view-demo_2Ff\" \"cite-[^\"]*demo_2Ff\" :pre\]" (:content (peek r1)))
            "the cited var's edit at its own part, nested in its task's as the pull nests it: the ids name each render and what it shows, and only what moved inside the cite is sent")
        (is (not (str/includes? (:content (peek r1)) "Your proofs")) "one edit at the part that moved, never the page whole")
        (is (str/includes? (:content (peek r1)) "Triples."))
        (is (not (re-find #"seon\.render\.ui/task-view\b" (:content (peek r1)))) "a component that did not change is not told again")
        (is (not (re-find #"\"status\"" (:content (peek r1)))) "a turn taken moved nothing of how the work stands: not told again")
        (is (str/includes? (:content (peek r2)) "quadruple") "the plan changed mid-run: the task's part in the next diff")
        (let [db @conn
              at (fn [origin] (:messages (wire/render db {:commit (d/commit-id db) :task "demo/f" :turn 4 :max-turns 1 :origin origin :agent "a1"})))]
          ;; a read that held only the system prompt and the page hid the conversation a model reads (docs/turn.md#an-outside-agent)
          (is (= (at :model) (at :outside)) "an outside agent's read is the request a model's turn is sent, its conversation in it")
          (is (str/includes? (:content (peek r2)) "quadruple")))))))

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
      (d/transact conn [{:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/f"}}
                        {:var/name 'demo-test/f-test :seon.test/status :red}])
      (d/transact conn [{:seon.agent/key "a1" :seon.agent/turn 1}
                        {:seon.model-call/id (seon.lib/call-id "a1" 1) :seon.model-call/agent [:seon.agent/key "a1"] :seon.model-call/n 1 :seon.model-call/origin :model
                         :seon.model-call/basis {:commit (d/commit-id @conn) :task "demo/f" :turn 1 :max-turns 4 :origin :model}}])
      (let [db @conn
            a (pdb/eid db :seon.agent/key "a1")
            page (seon.fixtures/drawn-parts db (ctx/build (seon.db/index) db a :b))
            text (str/join "\n\n" (map render/part-text (vals page)))
            times (fn [s] (count (re-seq (re-pattern (java.util.regex.Pattern/quote s)) text)))]
        (is (some #(= % "status") (keys page)) "how the work stands is on the page")
        (is (= 1 (count (re-seq #"Make .{0,40}?demo/g.{0,40}? exact\." text))) "the plan, once, in the task")
        (is (zero? (times "```demo/g```")) "its code span an element, never its delimiters (docs/render.md#a-value-draws-itself)")
        (is (= 1 (times "(defn g \"Halves.\"")) "a cited source once, as code: its quotes as written")
        (is (= 1 (count (re-seq #"\[:code \"demo-test/f-test\"\]" text))) "the proofs' statuses once, in the status")))))

(deftest a-cited-var-shows-at-its-citations-depth
  ;; the delimiter picks the depth
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
    (is (= {:symbol 'ctx-depth/cube :arglists '([x]) :doc {:seon.print/plain "Cubes x."} :contract [:=> [:cat :int] :int]} (by 'ctx-depth/cube))
        "one backtick: arglists, doc and contract")
    (is (= {:symbol 'ctx-depth/tri :source (print/code "(defn tri \"Triples x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))")
            :change (print/code "(seon.repl/edit! 'ctx-depth/tri \"old text, exactly once\" \"new text\")")} (by 'ctx-depth/tri))
        "three backticks: the source, as code, and the form that changes a part of it")))

(deftest a-long-source-is-cut-by-the-printer-where-it-is-shown
  ;; the clipping audit (2026-10-25, P4) and the owner, 2026-10-26: "we don't need to and
  ;; shouldn't be printing thousands of items anywhere. The value printer is made to show as much
  ;; of the structure that is possible and to have ellipses for data that just continues so the
  ;; agent can know what's there and can dig into it". A cited source is whole in its data, never
  ;; swapped for its doc by size; its part is that data (docs/render.md#a-component-answers-hiccup), and the
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
        part {:seon.cite/depth :code :seon.cite/target {:var/name 'ctx-big/big :var/form {:form/src src}}}
        model (seon.render/part-text [(quote seon.render.ui/cite) {} (seon.render.ui/cite-hiccup db part {:changing? true})])]
    (is (= {:symbol 'ctx-big/big :source (print/code src)} (select-keys r [:symbol :source])) "the source whole as code, never swapped for its doc by size")
    (is (str/includes? model src) "the part holds it whole, in a fence: a function cited to be changed is read whole")
    (is (str/includes? model "```clojure") "fenced")))

;; a made unit vector, dim i weighted as a Matryoshka model's are (seon.vector-test/vectors)
(defn- made-vector [seed]
  (let [v (mapv (fn [i] (* (Math/exp (/ (- i) 96.0)) (Math/sin (* (inc seed) (inc i) 0.37)))) (range 768))
        n (Math/sqrt (reduce + (map #(* % %) v)))]
    (mapv #(/ % n) v)))

(deftest an-owners-message-reaches-the-next-turn
  ;; owner, 2026-10-23 (parity row 73): the page's message bar sends the owner's words through
  ;; root's delivery; the turn that takes them writes the message fact on its branch, so the
  ;; conversation holds it in tx order (seon.ctx/placed-messages), rendered by the message fact's
  ;; own messages (seon.seon.fixtures/eval-messages; docs/render.md)
  (let [[m] (seon.fixtures/message-messages {} {:seon.message/from {:seon.agent/key "owner"} :seon.message/body "use demo/g, not a new helper"})]
    (is (= "user" (:role m)))
    (is (str/includes? (:content m) "use demo/g, not a new helper"))
    (is (str/includes? (:content m) ":from \"owner\"") "who sent it"))
  (is (not (contains? (let [db (db-with-program) t (task-f db)] (status/task-data db t (task/task-cites db (:seon.task/id t)))) :messages)) "never in the task's data"))
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
  ;; (a 400 otherwise, measured: docs/model.md): the reply's own message (its model call's) carries it,
  ;; exactly as returned, and the provider's format sends it unless replay-reasoning is false
  (let [db (d/db-with (db-with-program) (into (ts/turn-tx "a1" 1 {:seon.model-call/reply "" :seon.model-call/reasoning "Square it first."})
                                              [{:seon.eval/id "e1" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/call (seon.task/call-ref "a1" 1)
                                                :seon.eval/code "(f 3)" :seon.eval/response {:value 9} :seon.eval/call-id "c1" :seon.eval/intent "f triples."}]))
        ;; the call as ctx holds it: its pull, with the evals that name it
        call (assoc (d/pull db '[*] [:seon.model-call/id (seon.lib/call-id "a1" 1)]) :seon.eval/_call {(pdb/eid db :seon.eval/id "e1") (d/pull db [:db/id :seon.eval/call-id :seon.eval/agent] [:seon.eval/id "e1"])})
        reply (seon.fixtures/turn-messages db call)
        evals (seon.fixtures/eval-messages {:db db} (d/pull db '[*] [:seon.eval/id "e1"]))
        ms (into reply evals)]
    (is (= "Square it first." (:reasoning (first reply))))
    (is (not-any? :reasoning evals))
    (is (= ["assistant" "tool"] (mapv :role (seon.model/wire-messages true ms))) "one assistant message, its call in it")
    (is (= "Square it first." (:reasoning_content (first (seon.model/wire-messages true ms)))))
    (is (= ["e1"] (mapv :id (:tool_calls (first (seon.model/wire-messages true ms))))))
    (is (not (contains? (first (seon.model/wire-messages false ms)) :reasoning_content)) "replay false leaves it out")
    (is (= [] (seon.fixtures/turn-messages db {:seon.model-call/n 2 :seon.model-call/reply ""})) "a reply with neither text nor reasoning makes no message")))

(defn- model-call-fact
  "Model call n of agent b/a1 as the turn loop writes it, with more."
  [n more]
  (merge {:seon.model-call/id (seon.lib/call-id "a1" n) :seon.model-call/agent [:seon.agent/key "a1"] :seon.model-call/n n :seon.model-call/origin :model} more))

(defn- reply-world
  "The conversation of agent b/a1 on the world, as ctx holds it: model call 1 (text \"I look.\", reasoning \"think\") with evals e1 e2
  e3, call 2 (\"Then.\", \"more\") with e4 e5, call 3 (no text, reasoning \"quiet\") with e6. Answers [db ctx-value]."
  []
  (let [ev (fn [n turn] {:seon.eval/id (str "e" n) :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent [:seon.agent/key "a1"] :seon.eval/call (seon.task/call-ref "a1" turn) :seon.eval/call-id (str "c" n)
                         :seon.eval/code (str "(f " n ")") :seon.eval/response {:handle (symbol "result" (str "e" n)) :value n} :seon.eval/intent (str "Call " n ".")})
        db (t/with (t/world)
                   [{:seon.agent/key "a1" :seon.agent/turn 3}]
                   [(model-call-fact 1 {:seon.model-call/forms 3 :seon.model-call/reply "I look." :seon.model-call/reasoning "think"})
                    (model-call-fact 2 {:seon.model-call/forms 2 :seon.model-call/reply "Then." :seon.model-call/reasoning "more"})
                    (model-call-fact 3 {:seon.model-call/forms 1 :seon.model-call/reply "" :seon.model-call/reasoning "quiet"})
                    (ev 1 1) (ev 2 1) (ev 3 1) (ev 4 2) (ev 5 2) (ev 6 3)])]
    [db (:tree (ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "a1") :b))]))

(defn- wire-without
  "The wire messages of the conversation of `reply-world` with the evals ids of its ctx unlinked (what swap! on @ctx does)."
  [ids]
  (let [[db c] (reply-world)
        ms (seon.fixtures/tree-messages (update c :seon.eval/_agent #(apply dissoc % (map (partial pdb/eid db :seon.eval/id) ids))) db)]
    (seon.model/wire-messages true ms)))

(defn- reply-shape
  "Each assistant wire message of wire as [its reply's message reasoning [its evals' code]]: its text, and the code of each of its calls."
  [wire] (vec (for [m wire :when (= "assistant" (:role m))]
                [(:content m) (:reasoning_content m) (mapv #(get (clojure.data.json/read-str (get-in % [:function :arguments])) "code") (:tool_calls m))])))

(deftest cutting-a-replys-calls-leaves-one-assistant-message-per-reply
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: a reply's text and reasoning are its model call's, never an
  ;; eval's, and evals fold by the reply they belong to: unlinking one (swap! on @ctx) cannot attach another to an earlier reply
  (testing "nothing cut: one assistant message per reply, its calls in order, each answered after it"
    (let [wire (wire-without [])]
      (is (= [["I look." "think" ["(f 1)" "(f 2)" "(f 3)"]] ["Then." "more" ["(f 4)" "(f 5)"]] ["" "quiet" ["(f 6)"]]] (reply-shape wire)))
      (is (= ["assistant" "tool" "tool" "tool" "assistant" "tool" "tool" "assistant" "tool"] (mapv :role wire)))
      (is (= ["e1" "e2" "e3" "e4" "e5" "e6"] (keep :tool_call_id wire)) "each call answered by a message of its own")))
  (testing "a reply's first call cut: its other calls stay on it, with its text and reasoning"
    (is (= [["I look." "think" ["(f 2)" "(f 3)"]] ["Then." "more" ["(f 4)" "(f 5)"]] ["" "quiet" ["(f 6)"]]] (reply-shape (wire-without ["e1"])))))
  (testing "a middle call cut"
    (is (= [["I look." "think" ["(f 1)" "(f 3)"]] ["Then." "more" ["(f 4)" "(f 5)"]] ["" "quiet" ["(f 6)"]]] (reply-shape (wire-without ["e2"])))))
  (testing "the calls of the second reply cut: the first is untouched, the second keeps its text"
    (let [wire (wire-without ["e4" "e5"])]
      (is (= [["I look." "think" ["(f 1)" "(f 2)" "(f 3)"]] ["Then." "more" []] ["" "quiet" ["(f 6)"]]] (reply-shape wire)))
      (is (not-any? #(contains? % :tool_calls) (filter #(= "Then." (:content %)) wire)))))
  (testing "all the calls of a reply cut: its text stays as a plain assistant message, with its reasoning"
    (is (= [["I look." "think" []] ["Then." "more" ["(f 4)" "(f 5)"]] ["" "quiet" ["(f 6)"]]] (reply-shape (wire-without ["e1" "e2" "e3"])))))
  (testing "a reply with no text, all its calls cut: nothing stays of it"
    (is (= [["I look." "think" ["(f 1)" "(f 2)" "(f 3)"]] ["Then." "more" ["(f 4)" "(f 5)"]]] (reply-shape (wire-without ["e6"])))))
  (testing "the reasoning survives a cut of call 0 and goes only when replay is off"
    (let [[db c] (reply-world)
          ms (seon.fixtures/tree-messages (update c :seon.eval/_agent dissoc (pdb/eid db :seon.eval/id "e1")) db)]
      (is (= "think" (:reasoning_content (first (seon.model/wire-messages true ms)))))
      (is (not (contains? (first (seon.model/wire-messages false ms)) :reasoning_content))))))

(deftest an-unknown-proof-says-what-to-write
  ;; a proof with no result: the reason names the deftest to write in its namespace and how
  ;; to run it (the dry run's agents read `{:reason :proof-unknown}` and searched for 25 turns)
  (let [db (d/db-with (db-with-program) (task/create-tx (db-with-program) [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p"
                                                                            :seon.task/proofs #{'demo-test/unwritten-test}}]))
        b (status-at db "demo/f" {})
        r (first (:refused b))]
    (is (= [{:test 'demo-test/unwritten-test :status :undefined}] (mapv #(dissoc % :shape :says) (:proofs b))) "a proof not written says so")
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
        ;; the page's part: the cite's target is the task, drawn by its plan
        cite (some #(when (= "demo.other/g" (get-in % [:seon.cite/target :seon.task/id])) %) (vals (:seon.cite/_of (:tree (ctx/build (seon.db/index) db (pdb/eid db :seon.task/id "demo/f") :b)))))
        part (seon.render/part-text [(quote seon.render.ui/cite) {} (seon.render.ui/cite {:seon.db/db db :seon.render/index (seon.db/index)} cite)])]
    (is (= "G is defined first. Then f calls it." (:seon.ref/lead (shown "demo.other/g"))) "doc depth: the task's plan")
    (is (= [] (:write-set block)) "and not a var of the write set, nor an unresolved name")
    (is (= "demo.other/g" (get-in cite [:seon.cite/target :seon.task/id])) "the cite's target is the task")
    (is (str/includes? part "G is defined first. Then f calls it.") "and its part shows the plan")
    (is (not (str/includes? part (:unresolved seon.ref/problem-messages))) "not an unresolved name")))


(deftest a-sessions-next-wake-reads-its-earlier-turns
  ;; design agent-loop section 1 (owner, 2026-10-25: "a resume where the agent wakes and responds
  ;; to a message it should have the same previous context"): the conversation is the session's,
  ;; not one wake's. An agent woken again reads the evals of its first wake and the messages its first
  ;; took, in turn order; another agent's records stay out
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (let [ev! (fn [id agent code turn] (d/transact conn [{:seon.eval/id id :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key agent}
                                                            :seon.eval/code code :seon.eval/response {:handle (symbol "result" id) :value 1} :seon.eval/call (seon.task/call-ref "a1" turn)}]))
            _ (d/transact conn [{:seon.task/id "demo/f" :seon.task/agent {:seon.agent/key "a1" :seon.agent/turn 2 :seon.agent/task {:seon.task/id "demo/f"}}}])
            _ (ev! "e1" "a1" "(f 1)" 1)
            _ (d/transact conn (task/message-tx "m1" (seon.task/sender "user") ["a1"] "use g" 1000))
            _ (ev! "e2" "a1" "(f 2)" 2)
            _ (ev! "x1" "b/a9" "(other)" 1)
            db @conn
            a (:db/id (d/entity db [:seon.agent/key "a1"]))
            ids (mapv (comp second first) (seon.fixtures/tree-parts (:tree (seon.ctx/build (seon.db/index) db a :b)) db))]
        (is (= ["e1" "m1" "e2"] ids) "the first wake's eval and message, then the second's, and no other agent's: by their turn numbers")))))

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
      (let [ev! (fn [id by turn]
                  (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "a1"}
                                     :seon.eval/code (str "(" id ")") :seon.eval/response {:handle (symbol "result" id) :value 1} :seon.eval/call (seon.task/call-ref "a1" turn) :seon.eval/call-id (str "c-" id) :seon.eval/intent "Step."}]))
            work! (fn [task] (d/transact conn [{:seon.task/id task :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id task}}}]))
            basis (fn [task n] {:commit (d/commit-id @conn) :task task :turn n :max-turns 9 :origin :model
                                :agent "a1"})
            ;; as seon.turn goes: the turn recorded, then its request on the db that holds it
            turn! (fn [b] (d/transact conn (ts/turn-tx "a1" (:turn b) {:seon.model-call/basis b :seon.model-call/forms 1 :seon.model-call/ms 1}))
                    (wire/render @conn (assoc b :commit (d/commit-id @conn))))
            _ (work! "demo/d")
            _ (ev! "e0" "a1" 0)
            b0 (basis "demo/d" 1)
            first-stage (turn! b0)
            boundary "demo/d is done. Your task now is demo/i, in this same session: what you did so far stays above.\n\nIts plan: Implement d."
            _ (work! "demo/i")
            _ (d/transact conn (task/message-tx "m1" seon.task/system-sender ["a1"] boundary 1000))
            second-stage (turn! (basis "demo/i" 2))
            ms0 (:messages first-stage) ms1 (:messages second-stage)
            refs (mapv first (:parts second-stage))
            at (fn [ref] (.indexOf refs ref))]
        (is (= ms0 (subvec ms1 0 (min (count ms1) (count ms0)))) "the first stage's request is the second's exact prefix")
        (is (= (mapv first (:parts first-stage)) (subvec refs 0 (count (:parts first-stage)))) "and records it held so")
        (is (< (at [:eval "e0"]) (at [:message "m1"])) "then the boundary, root's message, after the first stage's eval")
        (is (< (at [:message "m1"]) (at [:page 2])) "then the new stage's page, what changed since the first stage's turn, as turn 2's page message after the boundary")
        (is (some #(str/starts-with? (str (:code (first (:tool-calls %)))) "(my.render/diff ") (drop (count ms0) ms1)) "a generated eval of what changed")
        (is (some #(str/includes? (str (:content %)) "Your task now is demo/i") (drop (count ms0) ms1)) "the boundary says the new task")))))

(deftest a-turns-wire-request-is-made-again-at-its-basis
  ;; docs/render.md#the-debug-page-is-the-wire-request: the request body a turn put on the wire (its
  ;; messages under the provider at its basis, the run's model) is made again from its basis commit
  ;; byte for byte, after later turns, evals and a change of the task's own config
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (into (task/boot-tx "boot-1" "sean") (seon.task/config-tx (merge seon.config/config {:seon.config.ai/provider :openai-compat}) {})))
      (d/transact conn (doc/load-tx @conn "docs" {doc/system-page (slurp (str (seon.config/checkout) "/" doc/system-page))}))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g."
                                               :seon.task/proofs #{'demo-test/g-test}}]))
      (d/transact conn [{:seon.task/id "demo/g" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
      (let [sent (loop [n 0 out []]
                   (if (= n 3)
                     out
                     ;; as a turn goes (seon.turn/act!): the turn, its basis commit holding it, its provider
                     ;; there, its model call, its request drawn on the db after them; then its call's eval
                     (let [_ (d/transact conn (ts/turn-tx "a1" (inc n) {}))
                           b {:commit (d/commit-id @conn) :task "demo/g" :turn (inc n) :max-turns 9
                              :origin :model :agent "a1" :model "run-model"}
                           p (wire/provider-of @conn b)
                           _ (d/transact conn [{:seon.model-call/id (seon.lib/call-id "a1" (inc n)) :seon.model-call/basis b}])
                           r (wire/render @conn b)
                           body (seon.model/request-body p {:messages (:messages r) :task "demo/g" :turn (inc n)})]
                       (d/transact conn [{:seon.eval/id (str "e" n) :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"}
                                          :seon.eval/code (str "(e" n ")") :seon.eval/response {:handle (symbol "result" (str "e" n)) :value (vec (range (* 5 (inc n))))} :seon.eval/call (seon.task/call-ref "a1" (inc n)) :seon.eval/call-id (str "c" n) :seon.eval/intent (str "Step " n ".")}])
                       ;; the task's own config moves after turn 0: a later turn's provider, never turn 0's
                       (when (zero? n) (d/transact conn [{:seon.task/id "demo/g" :seon.config.ai/max-tokens 77}]))
                       (recur (inc n) (conj out [b body])))))]
        (is (= ["run-model" "run-model" "run-model"] (mapv (comp :model second) sent)) "the run's model names the provider's")
        (is (= [8192 77 77] (mapv (comp :max_tokens second) sent)) "each turn's provider is its basis's")
        (doseq [[b body] sent]
          (is (= (clojure.data.json/write-str body)
                 (clojure.data.json/write-str (:body (wire/wire-turn (d/commit-as-db (:store @conn) (:commit b)) b))))
              (str "turn " (:turn b) ": made again at its basis, the bytes it sent")))))))

(deftest the-request-is-a-conversation-of-evals-and-the-page
  ;; docs/render.md: each turn's message and eval calls with their responses
  ;; as tool messages (read from the eval records), the reasoning as returned, and the page among them as Seon's user message of each turn:
  ;; whole at the first, the parts that moved after (a verdict moves the status alone); the prefix stays byte-identical
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:seon.task/id "demo/f" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/f"}}}])
      (let [ev! (fn [id by code value & [more]] (d/transact conn [(merge {:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "a1"}
                                                                        :seon.eval/code code :seon.eval/response {:handle (symbol "result" id) :value value}}
                                                                       (update-keys (dissoc more :turn) #(keyword "seon.eval" (name %)))
                                                                       (when (:turn more) {:seon.eval/call (seon.task/call-ref "a1" (:turn more))}))]))
            ;; as seon.turn goes: the turn recorded, then its request on the db that holds it
            request (fn [n more] (let [b {:commit (d/commit-id @conn) :task "demo/f" :turn n :max-turns 4
                                          :origin :model :agent "a1"}]
                                   (d/transact conn (conj (ts/turn-tx "a1" n {})
                                                          {:seon.model-call/id (seon.lib/call-id "a1" n) :seon.model-call/basis (assoc b :commit (d/commit-id @conn)) :seon.model-call/forms 1
                                                           :seon.model-call/ms 1}))
                                   (let [ms (:messages (wire/render @conn (assoc b :commit (d/commit-id @conn))))]
                                     ;; its reply comes of its request
                                     (when (seq more) (d/transact conn [(assoc more :seon.model-call/id (seon.lib/call-id "a1" n))]))
                                     ms)))
            r0 (request 1 {:seon.model-call/reply "I call f." :seon.model-call/reasoning "why"})
            _ (ev! "e2" "a1" "(f 1)" 1 {:call-id "call-0" :turn 1})
            _ (d/transact conn [{:var/name 'demo-test/f-test :seon.test/status :red}])
            r1 (request 2 {})]
        (is (= ["system" "user" "assistant" "tool" "assistant" "tool"] (mapv :role r0)) "turn 1: the system prompt, what opens the session, then the record and the page whole, each a generated eval")
        (is (= ["system" "user" "assistant" "tool" "assistant" "tool" "assistant" "assistant" "tool" "assistant" "tool"] (mapv :role r1)) (pr-str (mapv :role r1)))
        (is (= {:role "assistant" :content "I call f." :reasoning "why" :reply 1} (nth r1 6)) "the reply: its text and the reasoning as returned")
        (is (= {:role "assistant" :content "" :tool-calls [{:id "e2" :code "(f 1)"}] :reply 1} (nth r1 7)) "then its call")
        (is (= [{:role "assistant" :content "I call f." :reasoning_content "why" :tool_calls [{:id "e2" :type "function" :function {:name "repl" :arguments "{\"code\":\"(f 1)\"}"}}]}
                {:role "tool" :content "{:handle result/e2, :value 1}" :tool_call_id "e2"}]
               (subvec (seon.model/wire-messages true r1) 6 8)) "one assistant message on the wire")
        (is (= "{:handle result/e2, :value 1}" (:content (nth r1 8))) "the call's response, its recorded value printed as it is sent")
        (is (= r0 (subvec r1 0 6)) "the whole earlier request is the prefix: its record and page stay, appended")
        (is (str/starts-with? (page-code r1) "(my.render/diff {:commit #uuid \"") "turn 2's page diff is the last")
        (is (re-find #"\"status\"" (:content (peek r1))) "a verdict recorded after turn 1: how the work stands, told again")
        ;; (the task's data draws each proof's recorded status too, so it moves with a verdict: reported)
        (is (not (re-find #"\"related" (:content (peek r1)))) "what a verdict does not move is not told again")
        (is (str/includes? (:content (peek r1)) "fails") "the proof's new verdict")
        (is (not (str/includes? (:content (peek r1)) "seon.render.ui/task-view")) "the rest unchanged, not told again")
        (is (not-any? #(str/includes? (str (:content %)) ";;") (rest r1)) "no ;; reaches the model")
        (is (nil? (resolve 'seon.ctx/messages)) "one path: no request without an agent")))))

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
    (is (= ["docs/a.md#one" "docs/b.md#two" "docs/decisions.md#ruled"] (:sections (ctx/cited db 'demo/f))) "the write set's entry for it carries them")
    (is (not (contains? (ctx/cited db 'demo/area) :sections)) "and an uncited var carries none")))

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
    (is (= "not written yet" (get (:to-prove data) 'demo-test/h-test)) "an unwritten proof: not written yet (the plan is above it)")
    (is (= "not written yet" (get (:to-prove data) 'demo-test/q-test))
        "one the plan's sentences do not name: that it is not written, not the plan again (the page shows it)")))

(deftest a-record-reads-as-its-live-response-through-its-model-render
  ;; the context lab, 2026-11-02: a prove's tool message, drawn from its record, was its whole value printed (~15.8k
  ;; tokens of map and :heap) where the live response was its model render's one line (my.agent/prove-summary): the
  ;; value's schema is metadata, which the store does not keep, and record-text never read the record's :schema
  (let [v (with-meta {:reports [] :ran 3 :reused 2 :fail 0 :error 0 :pass 3 :heap (vec (range 400))} {:seon.render/schema :seon.test/proved})
        r {:handle 'result/e7 :value v}
        live (seon.print/response-text r #(seon.render/model (seon.db/index) %))
        rec (repl/record-details "(my.agent/prove)" r {})
        db (d/db-with (db-with-program) [(into {:seon.eval/id "e7" :seon.eval/by {:seon.agent/key "a1"}} rec)])
        stored (d/pull db '[:seon.eval/code :seon.eval/response :seon.eval/result :seon.eval/schema] [:seon.eval/id "e7"])
        sent (:content (second (seon.fixtures/eval-messages {:db db} (assoc stored :seon.eval/id "e7" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/call-id "c1"))))]
    (is (= :seon.test/proved (:seon.eval/schema stored)) "the eval keeps the value's schema")
    (is (str/includes? live "green: 5 of 5 passed") "the live response is its one line")
    (is (= live sent) (str "the record's tool message reads as the live response did: " sent))
    (is (not (str/includes? sent ":heap")) "not the whole value")))

(deftest a-record-keeps-its-value
  ;; the owner's rule (clipping audit 2026-10-25, P1/P8): data is cut only by the one printer,
  ;; when it is printed into a reader's view; the full result is stored. A record keeps its
  ;; response's value as data, and is the same whatever was evaluated before it
  (let [db (db-with-program)
        r {:handle 'result/e5 :value (vec (for [i (range 300)] {:i i :name (str "row-" i)})) :ms 1.0}
        rec (repl/record-details "(rows)" r {})
        stored (d/db-with db [(into {:seon.eval/id "e5" :seon.eval/by {:seon.agent/key "a1"}} rec)])]
    (is (= (:value r) (:value (seon.db/response-of nil rec))) "the whole value, not a printout of it")
    (is (not (contains? (seon.db/response-of nil rec) :text)) "no text made for a reader")
    (is (= (select-keys rec [:seon.eval/code :seon.eval/response]) (select-keys (d/pull stored '[:seon.eval/code :seon.eval/response] [:seon.eval/id "e5"]) [:seon.eval/code :seon.eval/response])) "what the eval keeps round-trips through the store")
    (is (= (seon.db/response-of nil rec) (seon.db/response-of stored (d/pull stored '[:seon.eval/code :seon.eval/response :seon.eval/result] [:seon.eval/id "e5"]))) "and so does its response, whole")
    (is (str/includes? (print/record-text (assoc rec :seon.eval/response (seon.db/response-of nil rec))) "row-0") "printed when it is shown")
    (is (= rec (repl/record-details "(rows)" r {})) "the same whatever came before it")
    (testing "a value that is not plain data is kept as data: its members, its symbol, or what it was"
      (doseq [[v want] [[(atom 1) {:seon.value/type (.getName (class (atom 1)))}] [(map inc (range 3)) [1 2 3]] [#'clojure.core/inc 'clojure.core/inc]]]
        (let [rec (repl/record-details "(x)" {:handle 'result/e6 :value v} {})]
          (is (= want (:value (seon.db/response-of nil rec))) (str (type v)))
          (is (seon.contract/plain? (seon.db/response-of nil rec)) "and the store keeps it"))))))

(deftest a-value-past-the-largest-kept-is-gone-and-the-transcript-says-so
  ;; docs/storage.md#plain-data: :seon.config.store/max-value-chars bounds what an eval's record keeps; past it the
  ;; value's type and the bound are kept, the transcript says it is gone, and the handle still reads it live
  (let [bound (seon.config/value :seon.config.store/max-value-chars)
        big (apply str (repeat (inc bound) "x"))
        at (apply str (repeat bound "x"))
        details (fn [v] (repl/record-details "(read-it)" {:handle 'result/e1 :value v} {:call-id "c1"}))
        value-of #(:value (seon.db/response-of nil %))
        sent (:content (second (seon.fixtures/eval-messages nil (assoc (details big) :seon.eval/id "e1" :seon.eval/by {:seon.agent/key "a1"}))))]
    (is (= {:seon.value/gone {:type (.getName (class big)) :over bound}} (value-of (details big))) "a string past the bound")
    (is (= at (value-of (details at))) "and at the bound it is kept whole")
    (is (= bound (:over (:seon.value/gone (value-of (details (range (inc bound))))))) "a lazy seq past it too, never realized further")
    (is (str/includes? sent ":seon.value/gone") "the transcript says it is gone")
    (is (str/includes? sent (str ":over " bound)) "and past what")
    (is (< (count sent) 500) "and carries no part of the value")))

(deftest an-evals-facts-are-attributes-a-query-finds
  ;; docs/storage.md#plain-data: an eval's code, call id, text and response are attributes of its entity, so a
  ;; query finds a model's evals by the call they answered, with no record to unpack
  (let [db (d/db-with (db-with-program)
                      (for [[id call] [["e1" "c1"] ["e2" nil] ["e3" "c3"]]]
                        (into {:seon.eval/id id :seon.eval/by {:seon.agent/key "a1"}}
                              (repl/record-details (str "(" id ")") {:handle (symbol "result" id) :value 1} (cond-> {} call (assoc :call-id call :intent (str "ask " call)))))))]
    (is (= #{"e1" "e3"} (set (d/q '[:find [?id ...] :where [?e :seon.eval/call-id _] [?e :seon.eval/id ?id]] db))) "the model's evals, by the call id they answered")
    (is (= "ask c3" (d/q '[:find ?p . :in $ ?c :where [?e :seon.eval/call-id ?c] [?e :seon.eval/intent ?p]] db "c3")) "and the text beside one call")
    (is (= 1 (:value (seon.db/response-of db (d/pull db '[:seon.eval/response :seon.eval/result] [:seon.eval/id "e2"])))) "the response is the map, its value data")))

(deftest an-evals-record-keeps-its-text-as-written
  ;; docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map: an eval's record keeps its
  ;; text and parses none of it (the parser is program the agent may break: md-scan's broken seon.ref/scan
  ;; made every eval throw while the record parsed the text's citations); a reader parses the kept text
  (let [text "Reading `demo/f` before I change it."
        rec (repl/record-details "(f 1)" {:text "2"} {:intent text})]
    (is (= text (:seon.eval/intent rec)) "the text, kept as written")
    (is (not (contains? rec :cites)) "and nothing parsed from it")
    (let [row #(seon.web.html/html (seon.render.ui/evaluation {:seon.render/index (seon.db/index) :as :inline} (assoc (repl/record-details "(x)" {:text "x" :value %} {}) :seon.eval/by {:seon.agent/key "a1"})))]
      (is (str/includes? (row {:a 1}) "{:a 1}") "its value in one line, printed now, for the collapsed eval")
      (is (str/includes? (row nil) ">nil</code>") "a value of nil is nil, not nothing (such evals' collapsed rows showed nothing)"))))

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
        rec {:seon.eval/code "(my.task/status)" :seon.eval/response {:handle 'result/e4 :value {:source c}}}
        db (d/db-with (ts/test-db) [(into {:seon.eval/id "e4" :seon.eval/agent {:seon.agent/key "a1"}} rec)])
        back (d/pull db [:seon.eval/code :seon.eval/response] [:seon.eval/id "e4"])]
    (is (seon.contract/plain? c) "plain data")
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
        ;; a function beside the plan by meaning, and a schema key its plan names
        db (d/db-with db [{:db/id "f" :form/id "demo/g" :form/src "(defn g [])" :form/keywords #{:demo/thing :demo/not-a-schema}} {:var/name 'demo/g :var/form "f" :var/doc "Gets it. More."}
                          {:schema/key :demo/thing :schema/form "[:map]"}])
        ;; (written before the task: its plan cites them, and a cite names what exists)
        ;; every item scored is shown (its cut 0), so these tests read relevance's whole order
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "r" :seon.task/plan plan
                                              :seon.task/config {:seon.config.render/relevance-summary 0.0}}]))
        te (pdb/eid db :seon.task/id "demo/r")
        es (into [te] (for [i (range 5)] (pdb/eid db :seon.doc/address (anchors i))))
        db (d/db-with db (vector/tx-data es (repeat 6 "1") (into [(dir 1.0)] (for [i (range 5)] (dir 1.0 (* 0.1 (inc i))))) "m"))
        f (pdb/eid db :form/id "demo/g")]
    [(d/db-with db (vector/tx-data [f] ["1"] [(dir 1.0 0.05)] "m")) te dir]))

(deftest relevance-is-a-chance-that-follows-the-context
  ;; seon.ctx.search/relevance (docs/vector.md#relevance): each item near a context scored as a chance,
  ;; best first; the touched items left out, and what is near them lifted (zt)
  (let [[db te] (sections-near "Make the page say one thing.")
        qv (vector/vector-of db te)
        sec #(pdb/eid db :seon.doc/address (str "docs/vector.md#" %))
        score (fn [touched] (into {} (:section (search/relevance db {:qv qv :text "Make the page say one thing." :seeds touched :touched touched}
                                                                  #{:section} (constantly true)))))
        cold (:section (search/relevance db {:qv qv :text "Make the page say one thing." :seeds #{} :touched #{}} #{:section} (constantly true)))]
    (is (= 5 (count cold)) "every section near it scored")
    (is (every? (fn [[_ p]] (<= 0.0 p 1.0)) cold) "each a chance")
    (is (apply >= (map second cold)) "best first")
    (is (= (sec "the-model") (ffirst cold)) "the nearest first, all else equal")
    (is (= cold (:section (search/relevance db {:qv qv :text "Make the page say one thing." :seeds #{} :touched #{}
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
    (is (= [0.0 0.0 0.05 0.1 0.5 0.5] (mapv #(#'seon.ctx.search/percent knots %) [-9.0 -2.0 -1.0 0.0 2.0 9.0]))
        "a logit's chance: flat past either end, linear between knots"))
  (is (= [0.0 0.0] (#'seon.ctx.search/z [3.0 3.0])) "no spread, no evidence")
  (is (= [1.0 0.0 -1.0] (#'seon.ctx.search/z [2.0 nil 0.0])) "a missing value is no evidence; the rest over the values there are")
  (is (= [] (seon.doc/bm25 #{"a"} [] [])) "an empty pool scores nothing")
  (is (= [0.0 0.0] (seon.doc/bm25 #{} [{"a" 1} {"b" 2}] [1 2])) "an empty query scores every text 0")
  (let [[x y] (seon.doc/bm25 #{"near"} [{"near" 1 "of" 1} {"of" 2}] [2 2])]
    (is (and (pos? x) (zero? y)) "a text holding the query's word scores above one that does not")))

(deftest a-tokens-words-are-its-identifiers-parts
  (is (= ["seon" "vector" "near" "of" "foo" "bar"] (seon.doc/terms "seon.vector/near-of fooBar a"))
      "split at what is no letter or digit and at a case change, one-letter parts dropped"))

(defn- walked
  "Agent demo/a1's page on db (sections-near's, working demo/r), walked live over a reactive
  atom holding its ctx, as its process holds it: [walk atom db-cell rid]; with outputs (a set), a walk asked for those too (`wire/request` reads the model's). A change is (step! walk
  atom db-cell tx-data): the transaction applied, ctx patched by its datoms, the walk flushed."
  [db & [outputs]]
  (let [db (d/db-with db [{:seon.agent/key "demo/a1" :seon.agent/task {:seon.task/id "demo/r"}}]) 
        cell (volatile! db)
        c (volatile! (ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "demo/a1") :b))
        a (seon.ratom/atom (:tree @c))
        w (cond-> (render/walker {:ref a :changes ctx/changes} #(deref cell) [#'seon.render.ui/agent {} (seon.ratom/ref-view a ctx/changes)])
            outputs (assoc :outputs outputs))]
    (seon.fixtures/flush! w nil)
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
    (seon.fixtures/flush! w (:tx-data r))
    (let [u1 (into {} (map (juxt :id :html)) (render/placed-units w))]
      (vec (sort (for [[id h] u1 :when (not= h (get u0 id))] id))))))

(deftest a-new-turn-does-not-run-the-status-again
  ;; docs/render.md#how-the-work-stands: the status reads facts about the work, never the conversation. Its read of the newest
  ;; turn, for a design task, ran it again each turn for every task: about 11 MB a turn, for the same output
  (let [[db _] (sections-near "Make ```demo/g``` say one thing.")
        [w :as walk] (walked db)
        runs #(let [l (get (render/ledger w) 'seon.render.ui/status)] (+ (:miss l 0) (:same l 0)))
        before (runs)]
    (is (pos? before) "the status was drawn")
    (step! walk [{:seon.model-call/id (seon.lib/call-id "demo/a1" 1) :seon.model-call/agent [:seon.agent/key "demo/a1"] :seon.model-call/n 1 :seon.model-call/origin :model}])
    (is (= before (runs)) "a turn taken moved nothing of how the work stands"))
  ;; a design task's plan-before is its merge base's (the branch's base commit), no turn names it: its status reads no turn either
  (let [[db te] (sections-near "Make ```demo/g``` say one thing.")
        [w :as walk] (walked (d/db-with db [[:db/add te :seon.task/kind :design]]))
        runs #(let [l (get (render/ledger w) 'seon.render.ui/status)] (+ (:miss l 0) (:same l 0)))
        before (runs)]
    (step! walk [{:seon.model-call/id (seon.lib/call-id "demo/a1" 1) :seon.model-call/agent [:seon.agent/key "demo/a1"] :seon.model-call/n 1 :seon.model-call/origin :model}])
    (is (= before (runs)) "a design task's first turn moved nothing of how its work stands either")))

(deftest the-status-is-one-reaction-the-page-and-the-turn-read
  ;; docs/render.md#how-the-work-stands: the turn decides from what the component kept, so a status nothing moved costs nothing, and a move reaches both at once
  (let [[db te] (sections-near "Make ```demo/g``` say one thing.")
        [w :as walk] (walked db)
        kept #(render/kept-of w '[seon.render.ui/status])
        c (:tree (ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "demo/a1") :b))
        before (kept)]
    (is (map? before))
    (is (= (select-keys before [:phase :next]) (select-keys (status/status db (ctx/build (seon.db/index) (d/db-with db [{:seon.agent/key "demo/a1" :seon.agent/task {:seon.task/id "demo/r"}}]) (pdb/eid db :seon.agent/key "demo/a1") :b) {}) [:phase :next])))
    (step! walk [{:seon.model-call/id (seon.lib/call-id "demo/a1" 1) :seon.model-call/agent [:seon.agent/key "demo/a1"] :seon.model-call/n 1 :seon.model-call/origin :model}])
    (is (identical? before (kept)) "nothing of how the work stands moved: the same value, nothing ran")))

(deftest what-is-taken-for-the-next-turn-is-read-before-that-turn-begins
  ;; docs/turn.md#the-turn-is-a-number: a message is the next turn's, and an outside agent's turn never moves, so the conversation draws one past the turn in force: an agent never
  ;; loses what it was sent, on the page or in its request
  (let [[db _] (sections-near "Make ```demo/g``` say one thing.")
        ;; the request has a system message: a stub of the prompt
        db (d/db-with db (doc/load-tx db "docs" {doc/system-page "# A stub of the system prompt\n\nBe brief."}))
        [w :as walk] (walked db #{:seon.render/hiccup :seon.render/openai-compat})
        says #(pr-str (:messages (wire/request w)))
        take! (fn [id body] (seon.task/message-tx id (seon.task/sender "owner") ["demo/a1"] body 1000))]
    (is (not (str/includes? (says) "Before the turn.")))
    (step! walk (take! "m1" "Before the turn."))
    (is (str/includes? (says) "Before the turn.") "delivered for the turn after the one in force, 0")
    (step! walk (ts/turn-tx "demo/a1" 1 {}))
    (is (str/includes? (says) "Before the turn.") "and read by turn 1 when it opens")
    (step! walk (take! "m2" "One past it again."))
    (is (str/includes? (says) "One past it again.") "and past each turn after")))

(deftest opening-a-page-does-not-change-what-the-model-reads
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: the walk a person has opened draws more, and the model reads the same bytes as the cold walk that drew none of it
  (let [[db _] (sections-near "Make ```demo/g``` say one thing.")
        db (d/db-with db (conj (ts/turn-tx "demo/a1" 1 {:seon.model-call/reply "Reading."})
                               {:seon.agent/key "demo/a1" :seon.agent/task {:seon.task/id "demo/r"}}))
        c (:tree (ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "demo/a1") :b))
        root #'seon.render.ui/agent
        outs #{:seon.render/hiccup :seon.render/openai-compat}
        wire #(try (render/openai-compat %) (finally (render/dispose-walk! %)))]
    (is (= (wire (seon.fixtures/draw c ctx/changes db root {:model-only true :once true :outputs outs}))
           (wire (seon.fixtures/draw c ctx/changes db root {:outputs outs}))))))

(deftest a-cited-var-redefined-shows-in-the-next-view
  ;; the task's data is a render, never held: a var the plan cites redefined (the agent's defn, or main
  ;; caught up), the next view shows its new source. Each cited thing is a component of its own; the edit draws that
  ;; one again (its db reads touched) and the task's not
  (let [[db _] (sections-near "Make ```demo/g``` say one thing.")
        [w _ _ _ rid :as walk] (walked db)
        ;; the cite's own component, keyed by the cite (seon.render.ui/cite: its owner and what it names)
        cited (some #(when (and (str/starts-with? % "cite-") (str/ends-with? % "demo_2Fg")) %) (map :id (render/placed-units w)))
        html #(:html (some (fn [u] (when (= % (:id u)) u)) (render/placed-units w)))]
    (is (some? (html cited)) "the cited var, a component of its own")
    (is (not (str/includes? (html cited) ":redefined")))
    (let [changed (step! walk [{:db/id "f2" :form/id "demo/g-2" :form/src "(defn g [] :redefined)"} [:db/add [:var/name 'demo/g] :var/form "f2"]])]
      (is (str/includes? (html cited) "(defn g [] :redefined)") "the next view: the new source")
      (is (some #{cited} changed) "the edit's page diff holds the cited var's part")
      (is (not-any? #(str/starts-with? % "task-view") changed) "and not the task's, which cites it"))
    (render/dispose-walk! w)))

;; docs/references.md#an-entity-cites: a section a cite joins, changed (main's datoms patched into ctx), redraws that
;; cite's part alone, and the live page is the page a cold draw of the same db makes
(deftest a-cited-section-main-changed-is-its-part-alone
  (let [[db _] (sections-near "Make ```demo/g``` say one thing; see [the model](docs/vector.md#the-model).")
        [w _ cell _ rid :as walk] (walked db)
        part (some #(when (and (str/starts-with? % "cite-") (str/ends-with? % "docs_2Fvector_2Emd_23the-model")) %) (map :id (render/placed-units w)))
        html (fn [w] (into {} (map (juxt :id :html)) (render/placed-units w)))]
    (is (some? ((html w) part)) "the cited section, a part of its own")
    (let [changed (step! walk [[:db/add [:seon.doc/address "docs/vector.md#the-model"] :seon.doc/lead "The model, as main now says it."]])
          live (html w)
          cold (let [d @cell w2 (seon.fixtures/draw (:tree (ctx/build (seon.db/index) d (pdb/eid d :seon.agent/key "demo/a1") :b)) ctx/changes d
                                             #'seon.render.ui/agent)]
                 (try (html w2) (finally (render/dispose-walk! w2))))]
      (is (= [part] changed) "the cited section's part, and nothing else, drawn again")
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
        task (html "task-view")
        cites (apply str (map :html (filter #(str/starts-with? (:id %) "cite-") units)))]
    (is (re-find #"Make <code[^>]*>demo/g</code> say one thing" task) "D4: the whole plan, its code span an element (docs/render.md#a-value-draws-itself)")
    (is (not (str/includes? task "(deftest g-test")) "D1: the proof's source is the status's, the task's part does not repeat it")
    (is (str/includes? (html "status") "demo-test/g-test") "D1: the proof written is the status's, by name")

    (is (str/includes? cites "(defn g [])") "D2: the cited var's source, in its part")
    (is (str/includes? cites "Section 1 says one thing.") "D3: the cited section's text, not its address, in its part")
    (is (not (str/includes? task "Section 1 says one thing.")) "and not the task's again")
    (render/dispose-walk! w)))

(deftest a-db-component-runs-again-on-what-it-read
  ;; a component that reads the db runs under d/with-reads: a transaction touching what it read runs
  ;; it again, any other leaves it (the task's own keys, context/task-key and related-key, deleted)
  (let [[db te] (sections-near "Make ```demo/g``` say one thing (docs/vector.md#what-is-embedded).")
        [w :as walk] (walked db)
        ran #(do (vreset! (:ledger w) {}) (step! walk %) (into #{} (for [[f c] (render/ledger w) :when (or (:miss c) (:same c))] (name f))))]
    (is (not (contains? (ran [{:seon.agent/key "demo/a2"}]) "task")) "an agent written: nothing the task's data reads")
    (is (contains? (ran [[:db/add te :seon.task/plan "Make ```demo/g``` say two things."]]) "task-view") "the plan rewritten: the task's data runs again")
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
        names-in #'search/names-in]
    (is (= ['app/caller] (names-in db "who calls `app/caller`?")) "a name in backticks")
    (is (= ['app/caller] (names-in db "who calls app/caller now")) "a bare name")
    (is (= [:seon.task/plan] (names-in db "read :seon.task/plan please")) "a keyword, held or not")
    (is (= ['app/other :seon.task/plan 'app/caller] (names-in db "app/other and `:seon.task/plan` then `app/caller`, app/other again"))
        "once each, in first-mention order, symbols and keywords together")
    (is (= [] (names-in db "see `no.such/var` and not.a/var")) "a symbol naming no var on db is left out")))

(deftest a-search-answers-as-grep-does
  ;; curated runs, 2026-11-10: agents grepped 7 times to 0 searches, since grep shows the line; my.agent/search answers its hits in
  ;; grep's shape (my.agent/hit-rows), each the line holding a word of the query (docs/vector.md#search)
  (let [db (d/db-with (seon.doc-test/corpus-db)
                      [{:db/id "c" :var/name 'app/caller} {:form/id "app/caller" :form/src "(defn caller [] (g))" :form/defines ["c"] :form/refs ['app/g]}])
        hits (search/search db "fusing" {:min 0.0} nil)
        rows (my.agent/hit-rows hits)
        shown (remove #(= :more (:kind %)) rows)]
    (is (seq shown))
    (is (= (map :name (remove #(= :more (:kind %)) hits)) (map :name shown)) "the same hits, in the same order")
    (is (every? #(re-matches #".+:\d+" (:at %)) shown) (str "each at its name and line, as grep's :at: " (pr-str (map :at shown))))
    (is (some #(re-find #"(?i)fus" (:text %)) shown) (str "a hit's :text is the line holding the query's word: " (pr-str (map :text shown))))
    (is (every? #(= #{:at :kind :text :name} (disj (set (keys %)) :relevance)) shown) "grep's keys, its name, and its relevance where it has one")
    (is (= (filter #(= :more (:kind %)) hits) (filter #(= :more (:kind %)) rows)) "the count of what was left out, as it was")))

(deftest search-fuses-words-and-references-and-filters-by-plain-data
  ;; task one-index steps 4-5: one search over every kind; the answer is data an agent filters
  (let [db (d/db-with (seon.doc-test/corpus-db)
                      [{:db/id "c" :var/name 'app/caller} {:form/id "app/caller" :form/src "(defn caller [] (g))" :form/defines ["c"] :form/refs ['app/g]}])
        ;; :min 0 unless a test asks its own: on a corpus of a few texts every chance is low, and the
        ;; page's cut (seon.config.render/relevance-summary) would hide them
        s (fn ([q] (search/search db q {:min 0.0} nil)) ([q o] (search/search db q (merge {:min 0.0} o) nil)))
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
  (let [with-vec (fn [m i] [m (vector/fact (:db/id m) "0" (unit-vec i) "m")])
        db (d/db-with (ts/test-db)
                      (into [] cat
                            [(with-vec {:db/id "s0" :seon.doc/address "m.md#a" :seon.doc/heading "a" :seon.doc/text "about a"} 0)
                             (with-vec {:db/id "s1" :seon.doc/address "m.md#b" :seon.doc/heading "b" :seon.doc/text "about b"} 5)
                             [{:db/id "v" :var/name 'm/f :var/form "f"}]
                             (with-vec {:db/id "f" :form/id "m/f" :form/src "(defn f [] 1)" :form/defines ["v"]} 1)]))
        hits (search/search db "zzz" {:by #{:meaning} :k 10 :min 0.0} (unit-vec 0))]
    (is (= ["m.md#a" "m.md#b"] (map :name (filter #(= :section (:kind %)) hits))) "the nearer section first")
    (is (= #{:section :var} (set (map :kind hits))))
    (is (every? #(= #{:meaning} (:by %)) hits))
    (is (apply >= (map :relevance hits)) "ranked by one scale, best first")
    (is (= [:section] (distinct (map :kind (search/search db "zzz" {:by #{:meaning} :kinds #{:section} :min 0.0} (unit-vec 0))))))))


;; ---- the model reads the page as Seon's message of each turn (docs/render.md#a-change-is-edits-by-name) ----

(defn- parts-session
  "A branch with the task demo/f, its agent b/a1 and a run, and the functions that play its turns as
  seon.turn goes: [ask! ev! plan!]. (ask! n) records model turn n+1: the turn, with the basis its page message is made from, and its request made
  on the db that holds it (wire/render); (ev! id) records a model eval; (plan! text) changes the task's plan."
  [conn]
  (wiki! conn)
  (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
  (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                           :seon.task/proofs #{'demo-test/f-test}}]))
  (d/transact conn [{:seon.task/id "demo/f" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/f"}}}])
  (let [turn (atom 0)]
    [(fn ask! [n]
       ;; n counts from 0: model turn n+1 of the agent
       (let [n (reset! turn (inc n))
             b {:commit (d/commit-id @conn) :task "demo/f" :turn n :max-turns 9 :origin :model :agent "a1"}
             _ (d/transact conn (conj (ts/turn-tx "a1" n {})
                                      {:seon.model-call/id (seon.lib/call-id "a1" n) :seon.model-call/basis b :seon.model-call/forms 1
                                       :seon.model-call/ms 1}))]
         (wire/render @conn (assoc b :commit (d/commit-id @conn)))))
     ;; a record of the turn in force
     (fn ev! [id] (d/transact conn [{:seon.eval/id id :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/call (seon.task/call-ref "a1" @turn)
                                     :seon.eval/code (str "(f " id ")") :seon.eval/response {:handle (symbol "result" id) :value 1} :seon.eval/call-id (str "c-" id) :seon.eval/intent (str "Step " id ".")}]))
     (fn plan! [p] (d/transact conn [{:seon.task/id "demo/f" :seon.task/plan p}]))]))

(deftest a-past-points-page-parts-hold-the-tasks-part
  ;; docs/render.md#a-change-is-edits-by-name: a turn's diff compares the agent's page parts at two points, each pulled and rendered
  ;; alone (render-pull with seon.ctx/changes, so a component reading the ctx reads it, and its lists realized before the walk is let go):
  ;; an agent whose task has a plan has the task's part, and the plan's text is in it
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask!] (parts-session conn)
            _ (ask! 0)
            db @conn
            a (:db/id (d/entity db [:seon.agent/key "a1"]))
            parts (wire/page-parts-at db a {:commit (d/commit-id db)})
            text (pr-str parts)]
        (is (str/includes? text "Task: ") "the task's part, by its own render (seon.render.ui/task-view)")
        (is (str/includes? text "Make f.") "and its plan's text")
        (is (str/includes? text "Your proofs") "the proofs' part beside it")))))

(deftest a-first-request-is-the-system-prompt-and-the-page-as-a-message
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask!] (parts-session conn)
            {:keys [messages parts]} (ask! 0)]
        (is (= ["system" "user" "assistant" "tool" "assistant" "tool"] (mapv :role messages)) "the system prompt, what opens the session (a conversation never opens on a blank assistant message), then the page as a generated eval and its result")
        (is (= [:section :session :page] (mapv (comp first first) parts)) "the entities it was made of")
        (is (str/starts-with? (page-code messages) "(my.render/page {:commit #uuid \"") "the page whole, as the call (my.render/page p)")
        (is (str/includes? (:content (peek messages)) "Task: ") "the task's part is in it, by its own render")))))

(deftest a-turn-appends-the-parts-that-moved-as-a-message
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev! plan!] (parts-session conn)
            r0 (ask! 0)
            _ (ev! "e1")
            r1 (ask! 1)
            _ (ev! "e2")
            _ (plan! "Make f triple.")
            r2 (ask! 2)
            ms1 (:messages r1) ms2 (:messages r2)]
        (is (= (:messages r0) (subvec ms1 0 (count (:messages r0)))) "the request is the one before, byte for byte")
        (is (= ms1 (subvec ms2 0 (count ms1))) "and again")
        (is (= ["assistant" "tool" "assistant" "tool"] (mapv :role (subvec ms2 (count ms1)))) "and the new records: turn 2's eval, then what moved of the page as a generated eval")
        (is (str/starts-with? (page-code ms2) "(my.render/diff {:commit #uuid \"") "turn 3's page diff")
        (is (str/includes? (:content (peek ms2)) "Make f triple.") "the plan's part")
        (is (not (str/includes? (:content (peek ms2)) "(defn f")) "and not a part that did not move")
        (is (< (count (:content (peek ms2))) (count (:content (peek (:messages r0))))) "fewer than all of them")))))

(deftest a-record-the-agent-unlinked-is-gone-from-the-request
  ;; swap! on @ctx unlinks a record (docs/ctx.md#changing-it-swap): the next request is made from the records that remain
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev!] (parts-session conn)
            _ (ev! "e1")
            _ (ask! 0)
            _ (ev! "e2")
            r1 (ask! 1)
            _ (d/transact conn [[:db/retractEntity [:seon.eval/id "e1"]]])
            r2 (ask! 2)]
        (is (= [:eval "e1"] (some #(when (= [:eval "e1"] (first %)) (first %)) (:parts r1))) "before: e1 is in")
        (is (not-any? #(= [:eval "e1"] (first %)) (:parts r2)) "after: the trimmed record is gone")
        (is (= [:eval "e2"] (first (some #(when (= [:eval "e2"] (first %)) %) (:parts r2)))) "and the rest remains")
        ;; the record shown at the first point is @ctx as it was there, e1 in it: a function of that point, so the prefix holds; only a
        ;; compaction shows the record again (docs/ctx.md#what-the-model-reads-and-what-the-page-renders)
        (is (not-any? #(str/includes? (str (:content %)) "e1") (drop 4 (:messages r2))) "nothing after the first point's record reads it")))))

(deftest a-cut-reply-is-no-message-in-the-history
  ;; task seon.ctx/the-context-is-the-agents-evals-and-seons R1 (review): a turn's message reply with
  ;; no call is its assistant message, but one cut at the token limit is no message, as the turn loop
  ;; reads it (seon.turn/act!), so the history and the stop rule agree
  (let [db (d/db-with (ts/test-db) [{:seon.agent/key "a1" :seon.agent/turn 2}
                                    {:seon.model-call/id (seon.lib/call-id "a1" 1) :seon.model-call/agent [:seon.agent/key "a1"] :seon.model-call/n 1 :seon.model-call/origin :model :seon.model-call/forms 0 :seon.model-call/reply "Done: f0 is green."}
                                    {:seon.model-call/id (seon.lib/call-id "a1" 2) :seon.model-call/agent [:seon.agent/key "a1"] :seon.model-call/n 2 :seon.model-call/origin :model :seon.model-call/forms 0 :seon.model-call/reply "(defn f0 [x] (let [y"
                                     :seon.model-call/truncated true}])
        a (:db/id (d/entity db [:seon.agent/key "a1"]))]
    (is (= [{:role "assistant" :content "Done: f0 is green." :reply 1}] (seon.fixtures/tree-messages (:tree (seon.ctx/build (seon.db/index) db a :b)) db)) "the whole reply is a record, the cut one is not")))

(deftest a-reply-with-no-call-is-a-record-at-its-turn
  ;; docs/turn.md#the-turn-is-a-number (owner, 2026-11-04): the conversation is ordered by the numbers its records carry, not by
  ;; when they were written. One session of two turns on the world: a message, the model's reply that called something and its two evals, an outside eval (issue
  ;; number 10, which a text sort puts before 9), then a
  ;; message taken by the second turn, an outside eval made during it and its message reply (a record, after what else the turn holds)
  (let [ev (fn [id by turn & [more]] (merge {:seon.eval/id id :seon.eval/by by :seon.eval/agent [:seon.agent/key "a1"] :seon.eval/call (seon.task/call-ref "a1" turn)
                                              :seon.eval/code (str "(" id ")") :seon.eval/response {:handle (symbol "result" id) :value 1}} more))
        msg (fn [id] {:seon.message/id id :seon.message/to [[:seon.agent/key "a1"]] :seon.message/from {:seon.agent/key "owner"} :seon.message/body id})
        db (t/with (t/world)
                   [{:seon.agent/key "a1" :seon.agent/turn 2}]
                   ;; written in time order, though not the transcript's: a message before the turn that reads it, a reply before an eval of its turn
                   [(msg "m1")]
                   [(model-call-fact 1 {:seon.model-call/forms 2 :seon.model-call/reply "I look."})
                    (ev "e10" "outside" 1)
                    (ev "e9" "a1" 1 {:seon.eval/call-id "c9"})
                    (ev "e8" "a1" 1 {:seon.eval/call-id "c8"})]
                   [(msg "m2")]
                   [(model-call-fact 2 {:seon.model-call/forms 0 :seon.model-call/reply "Done." :seon.model-call/reasoning "easy"})
                    (ev "e11" "outside" 2)])
        a (pdb/eid db :seon.agent/key "a1")
        ps (seon.fixtures/tree-parts (:tree (ctx/build (seon.db/index) db a :b)) db)]
    (is (= ["m1" (seon.lib/call-id "a1" 1) "e9" "e8" "e10" "m2" (seon.lib/call-id "a1" 2) "e11"] (mapv (comp second first) ps))
        "per turn: its messages, its reply holding its evals as they ran, then the evals of other hands; a reply that called is a record while it has evals")
    (is (= [1 1 1 1 1 2 2 2] (mapv #(nth % 2) ps)) "each record's turn")
    (is (= {:role "assistant" :content "Done." :reasoning "easy" :reply 2} (first (second (nth ps 6)))) "the reply is an assistant message")))

(deftest a-mid-turn-message-reads-at-the-next-turn-and-the-prefix-holds
  ;; docs/turn.md#the-turn-is-a-number: a message delivered in the middle of turn 1 (after its basis) is read by turn 2, so request 2 holds
  ;; request 1 whole (its page first), then turn 1's evals as they were, then the message, the page's change after it
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev!] (parts-session conn)
            r0 (ask! 0)
            _ (ev! "e1")
            _ (d/transact conn [{:seon.message/id "mid" :seon.message/to [[:seon.agent/key "a1"]] :seon.message/from {:seon.agent/key "owner"} :seon.message/body "note taken in turn 1"}])
            r1 (ask! 1)]
        (is (= (:messages r0) (subvec (:messages r1) 0 (count (:messages r0)))) "request 2 starts with request 1 whole, its page the first request's")
        (is (= ["system" "user" "assistant" "tool" "assistant" "tool" "assistant" "tool" "user"] (mapv :role (:messages r1)))
            "the page, turn 1's eval and its result, then the message taken in turn 1, a user message: the page's parts did not move (the message is history, never a part), so no change follows")
        (is (str/includes? (:content (nth (:messages r1) 8)) "note taken in turn 1") "the message, read at the next turn, after the turn's evals")))))

(deftest a-message-delivered-mid-turn-reads-after-that-turns-evals-on-a-branch-that-merged-main
  ;; docs/turn.md#the-turn-is-a-number: a message sent on main before turn 1 was made (so its id, issued once across every branch,
  ;; is older than turn 1's call) and brought to the agent's branch by a merge of main in the middle of turn 1 was read by turn 2:
  ;; the model's history holds it after turn 1's evals, and request 2 holds request 1 whole. Its id is the send's, not the
  ;; delivery's: a message's entity id is a content id (:seon.message/id is one, the hash of the id, the same on every branch), so
  ;; it orders nothing; the order the chat is read in is the transaction that brought each entity to the branch
  (ts/test-branch!
    (fn [cfg conn]
      (let [[ask! ev!] (parts-session conn)
            mb (pdb/on-main (:branch cfg) :chat-main)
            _ (d/branch! conn (:branch cfg) mb)
            main (pdb/connect (assoc cfg :branch mb))]
        (try
          (d/transact main [{:seon.message/id "early" :seon.message/to [[:seon.agent/key "a1"]] :seon.message/from {:seon.agent/key "owner"} :seon.message/body "sent before turn 1"}])
          (let [r0 (ask! 0)
                _ (ev! "e1")
                m (d/entity @main [:seon.message/id "early"])
                ;; main merged into the branch mid-turn: the message comes whole, its id main's
                _ (d/merge-db conn #{(d/commit-id @main)} [{:db/id (:db/id m) :seon.message/id "early" :seon.message/to [[:seon.agent/key "a1"]]
                                                            :seon.message/from {:seon.agent/key "owner"} :seon.message/body "sent before turn 1"}])
                r1 (ask! 1)
                call1 (pdb/eid @conn :seon.model-call/id (seon.lib/call-id "a1" 1))]
            (is (= (:db/id m) (pdb/eid @conn :seon.message/id "early")) "the branch holds main's entity, its id the content id main minted")
            (is (> (:db/id m) call1) "which says nothing of when it came: by ids it would sit after every call")
            (is (= (:messages r0) (subvec (:messages r1) 0 (count (:messages r0)))) "request 2 starts with request 1 whole")
            (is (= ["system" "user" "assistant" "tool" "assistant" "tool" "assistant" "tool" "user"] (mapv :role (:messages r1)))
                "the page, turn 1's eval and its result, then the message, read at turn 2")
            (is (str/includes? (:content (nth (:messages r1) 8)) "sent before turn 1") "the message, after turn 1's evals")
            (ev! "e2")
            (let [r2 (ask! 2)]
              (is (= (:messages r1) (subvec (:messages r2) 0 (count (:messages r1))))
                  "request 3 holds request 2 whole: the message stays at turn 2's start, before turn 2's eval, never moved after it")))
          (finally (d/release main)))))))

(deftest a-stage-changes-nothing-the-model-reads
  ;; docs/render.md#an-entity-may-name-its-own-render: an agent's own render (the stage) is a layout: its request and its parts are byte
  ;; for byte the ones its schema's render makes (the Data model session's condition: a stage that changed them would move the cached prefix); the
  ;; model output a request is made of (seon.ctx.wire/request), compared before the system message this world holds none of
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev!] (parts-session conn)]
        (ask! 0)
        (ev! "e1")
        (ask! 1)
        (let [db0 @conn ix (seon.db/index) a (pdb/eid db0 :seon.agent/key "a1")
              ;; drawn as a page is: by the render its entity names (its own when it holds one, seon.render/render-var-of)
              drawn (fn [db] (let [t (:tree (ctx/build ix db a :b))
                                   w (render/draw t ctx/changes db (render/render-var-of ix t) {:index ix :outputs #{:seon.render/hiccup :seon.render/openai-compat}})]
                              (try [(pr-str (mapv first (render/answer-sources w :seon.render/openai-compat))) (pr-str (render/placed-hiccup w (render/page-lists w)))]
                                   (finally (render/dispose-walk! w)))))
              staged (d/db-with db0 [{:db/id a :seon.render/hiccup 'seon.render.ui/stage}])
              [m0 p0] (drawn db0)
              [m1 p1] (drawn staged)]
          (is (= #'seon.render.ui/stage (render/render-var-of ix (:tree (ctx/build ix staged a :b)))) "the agent names its stage")
          (is (pos? (count m0)) "a request was made")
          (is (= m0 m1) "the same messages, byte for byte")
          (is (= p0 p1) "the same parts, byte for byte"))))))

(deftest a-stage-shows-the-last-ask-and-its-answer
  ;; docs/render.md#an-entity-may-name-its-own-render: the stage (seon.render.ui/stage-front) shows what a person asked last and what the
  ;; agent said last before they asked again, the turns between it working out; with nothing asked, a welcome. Red before: an ask was
  ;; paired with the reply of the turn that read it, and the sender's user name was not in the pull, so no ask was a person's
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! _] (parts-session conn)
            staged #(let [db @conn ix (seon.db/index) a (pdb/eid db :seon.agent/key "a1")
                          db (d/db-with db [{:db/id a :seon.render/hiccup 'seon.render.ui/stage}])
                          t (:tree (ctx/build ix db a :b))
                          w (render/draw t ctx/changes db (render/render-var-of ix t) {:index ix})]
                      (try (apply str (map :html (filter #(= "#p-head" (:container %)) (seon.fixtures/page-units w)))) (finally (render/dispose-walk! w))))]
        (is (str/includes? (staged) "Hello.") "nothing asked: the welcome")
        (d/transact conn [{:seon.agent/key "sean" :seon.user/name "sean"}])
        (d/transact conn [{:seon.message/id "q1" :seon.message/to [[:seon.agent/key "a1"]] :seon.message/from {:seon.agent/key "sean"} :seon.message/body "What is f?"}])
        (ask! 0)
        (d/transact conn [{:seon.model-call/id (seon.lib/call-id "a1" 1) :seon.model-call/reply "Let me look."}])
        (ask! 1)
        (d/transact conn [{:seon.model-call/id (seon.lib/call-id "a1" 2) :seon.model-call/reply "f is the identity."}])
        (let [html (staged)]
          (is (str/includes? html "What is f?") (str "the ask, in front: " html))
          (is (str/includes? html "f is the identity.") "the answer: the last thing said before the next ask")
          (is (not (str/includes? html "Let me look.")) "not the turn that only began the work")
          (is (not (str/includes? html "Hello.")) "no welcome once something was asked"))))))

(deftest the-chat-and-the-request-walk-one-sequence
  ;; docs/turn.md#the-turn-is-a-number: the page's chat and the model's request are one walk of the agent's entities, so they hold
  ;; its messages and evals in one order: turn 1's eval of the model's, an eval of another hand in turn 1, a message delivered mid-turn
  ;; (read at turn 2), turn 2's eval
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev!] (parts-session conn)]
        (ask! 0)
        (ev! "e1")
        (d/transact conn [{:seon.eval/id "x1" :seon.eval/by {:seon.agent/key "owner"} :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/call (seon.task/call-ref "a1" 1)
                           :seon.eval/code "(f 9)" :seon.eval/response {:handle 'result/x1 :value 9}}])
        (d/transact conn [{:seon.message/id "mid" :seon.message/to [[:seon.agent/key "a1"]] :seon.message/from {:seon.agent/key "owner"} :seon.message/body "taken in turn 1"}])
        (ask! 1)
        (ev! "e2")
        (let [db @conn ix (seon.db/index)
              w (render/draw (:tree (ctx/build ix db (pdb/eid db :seon.agent/key "a1") :b)) ctx/changes db #'seon.render.ui/agent
                             {:index ix :outputs #{:seon.render/hiccup :seon.render/openai-compat}})
              ;; the chat's entities as the request holds them: an eval or a message by its id, in order, each once
              of (fn [x] (let [x (some-> x seon.ratom/realize)] (or (some->> (:seon.eval/id x) (vector :eval)) (some->> (:seon.message/id x) (vector :message)))))
              request (vec (dedupe (keep (fn [[_ {:keys [args]}]] (of (first args))) (render/answer-sources w :seon.render/openai-compat))))
              ;; the page's: the conversation's rows as the page holds them, each eval's and message's own component by its root element's id (seon.render/key-id)
              page (vec (dedupe (for [[_ k id] (re-seq #"id=\"(evaluation|message)-([^\"]+)\"" (apply str (map :html (filter #(= "#c-rows" (:container %)) (seon.fixtures/page-units w)))))]
                                  [(if (= k "message") :message :eval) id])))]
          (try
            (is (= [[:eval "e1"] [:eval "x1"] [:message "mid"] [:eval "e2"]] request) "the request: turn 1's evals, the model's first, then the message turn 2 read, then turn 2's eval")
            (is (= request page) "the page's chat holds them in the same order")
            (finally (render/dispose-walk! w))))))))

(deftest a-later-point-advances-the-kept-page-walk-and-answers-a-cold-draw
  ;; docs/render.md#a-page-is-a-kept-walk: page-parts-through keeps its walk at the last point it drew; a later point of the same
  ;; branch (a commit that reaches the kept one) advances that walk by what moved rather than drawing the page again (a test's context
  ;; read drew it cold at every read), and its parts are what a cold draw at that point answers
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev! plan!] (parts-session conn)
            _ (ask! 0)
            a (pdb/eid @conn :seon.agent/key "a1")
            point (fn [] (let [db @conn] {:commit (d/commit-id db) :t (:max-tx db)}))
            ;; the walk kept in the process's state (seon.agent/state), a value: the point it was drawn cold at, which an advance keeps
            kept #(:drawn (:seon.ctx.wire/parts-walk @seon.agent/state))
            p1 (point)
            h1 (wire/page-parts-at @conn a p1)
            w1 (kept)
            _ (ev! "e1")
            _ (plan! "Make f, then g.")
            p2 (point)
            h2 (wire/page-parts-at @conn a p2)]
        (is (some? w1) "the walk is kept after a draw")
        (is (nil? (resolve 'seon.ctx.wire/kept-walk)) "in the process's state, no cell of its own")
        (is (= w1 (kept)) "a later point of its branch advanced the same walk")
        (is (str/includes? (pr-str h2) "Make f, then g.") "and it shows what moved")
        (swap! seon.agent/state dissoc :seon.ctx.wire/parts-walk)
        (is (= h2 (wire/page-parts-at @conn a p2)) "what a cold draw at that point answers")
        (is (not= w1 (kept)) "a point with no kept walk to advance is drawn")
        (is (not= h1 h2))))))

(deftest a-flush-between-turns-leaves-the-next-turns-pair-to-advance
  ;; docs/render.md#a-page-is-a-kept-walk: a flush between turns (a :context read, a person's page, an eval's own) draws the pending
  ;; turn's page at the db's own point, past the last basis; the next turn's pair starts at that basis, behind the kept walk, and drew
  ;; the page cold at every such turn (2026-11-11: ~7.1M ticks, the :context read's 80 ms). The page at the chain's first point is kept
  ;; with the walk, so that pair advances the same walk, and answers what a cold draw does
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev! plan!] (parts-session conn)
            _ (ask! 0)
            a (pdb/eid @conn :seon.agent/key "a1")
            point (fn [] (let [db @conn] {:commit (d/commit-id db) :t (:max-tx db)}))
            ;; the walk kept in the process's state (seon.agent/state), a value: the point it was drawn cold at, which an advance keeps
            kept #(:drawn (:seon.ctx.wire/parts-walk @seon.agent/state))
            pb (point)
            _ (wire/page-parts-through @conn a [nil pb])
            _ (ev! "e1")
            here (point)
            ;; the flush between turns: the pending turn's pair, to the db's own point
            _ (wire/page-parts-through @conn a [pb here])
            w (kept)
            _ (ev! "e2")
            _ (plan! "Make f, then g.")
            pn (point)
            [h0 h1] (wire/page-parts-through @conn a [pb pn])]
        (is (some? w))
        (is (= w (kept)) "the next turn's pair advanced the walk the flush left, not a page drawn again")
        (is (str/includes? (pr-str h1) "Make f, then g.") "it shows what moved")
        (swap! seon.agent/state dissoc :seon.ctx.wire/parts-walk)
        (is (= [h0 h1] (wire/page-parts-through @conn a [pb pn])) "what a cold draw of the pair answers")))))

(deftest a-write-reruns-the-placing-render-and-no-earlier-page-pair
  ;; docs/render.md#a-page-is-a-kept-walk: a turn's page message reads only db values at fixed past points, so its reads are not
  ;; recorded and a later write runs no earlier pair again; the render that places them (the agent's model render, turn-entities)
  ;; reads the current db, tracked as every render read is, so the same write runs it again and the request holds what it wrote
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev!] (parts-session conn)
            _ (ask! 0) _ (ev! "e1") _ (ask! 1)
            db0 @conn ix (seon.db/index) a (pdb/eid db0 :seon.agent/key "a1")
            c0 (ctx/build ix db0 a :b)
            at (volatile! db0) r (seon.ratom/atom (:tree c0))
            w (assoc (render/walker {:ref r :changes ctx/changes} (fn [] @at)
                                    [(render/render-var-of ix (:tree c0)) {} (seon.ratom/ref-view r ctx/changes)] ix)
                     :outputs #{:seon.render/openai-compat})
            pairs (fn [] (into {} (for [[k e] @(:cache w) :when (= #'wire/page-diff-msgs (:f e))] [k e])))
            evals (fn [] (set (keep (fn [[_ {:keys [args]}]] (:seon.eval/id (some-> (first args) seon.ratom/realize)))
                                    (render/answer-sources w :seon.render/openai-compat))))]
        (try
          (render/flush! w nil)
          (let [p0 (pairs)
                _ (ev! "e2")
                db1 @conn moved (ctx/moved db0 db1 nil) c1 (ctx/patch c0 db1 moved :b)]
            (vreset! at db1) (reset! r (:tree c1))
            (render/flush! w moved (:index (:ix c1)))
            (let [p1 (pairs)]
              (let [turn-of (fn [e] (last (:args e)))
                    turns (fn [ps] (into {} (filter #(<= (turn-of (val %)) 2)) ps))]
                (is (= [1 2 3] (sort (map turn-of (vals p0)))) "the two turns' page messages, and the page of the turn now opening, at the head")
                (is (and (= 2 (count (turns p0))) (every? #(identical? ((turns p0) %) (p1 %)) (keys (turns p0))))
                    "the write ran neither turn's pair again: their reads are of fixed points, unrecorded")
                (is (= (:max-tx db1) (:t (nth (:args (val (first (remove #(<= (turn-of (val %)) 2) p1)))) 2)))
                    "the opening page is drawn again at the new head, the point it shows having moved"))
              (is (contains? (evals) "e2") "the placing render read the current db, tracked: the write ran it again, and the request holds the new eval")))
          (finally (render/dispose-walk! w)))))))

(deftest the-models-page-holds-no-attribute-of-the-pages-alone
  ;; docs/render.md#style: what the page's script and a screen reader read (:data-*, :aria-*) is the page's alone, as class and style
  ;; are; a link's target, a title and an image's text stay, and a component's root keeps its id and the render that drew it as data
  ;; (:render, its full symbol), never a class (Point's 1602 ruling, 2026-11-11: nothing presentational reaches the model)
  (is (= [:section {:render 'seon.render.ui/task-view :id "task-view-x"} [:a {:href "/x" :title "t"} "x"] [:button "close"] [:img {:alt "a"}]]
         (#'wire/presentation-free
          [:section {:class "task-view big" :id "task-view-x" :render 'seon.render.ui/task-view :data-render "seon.render.ui/task-view"}
           [:a {:href "/x" :title "t" :aria-current "page" :class "link"} "x"]
           [:button {:data-seon-form "(f)" (keyword "data-on:click") "@post('/open')"} "close"]
           [:img {:alt "a" :style "w"}]]))))

(deftest a-record-takes-the-agents-turn-in-force
  ;; docs/turn.md#the-turn-is-a-number: agent/turn-now is the agent's :seon.agent/turn on its branch (0 before its first turn),
  ;; and the messages the agent's code takes (seon.agent/messages) are written under it
  (let [old @agent/state
        db0 (t/with (t/world) [{:seon.agent/key "a1"}])
        db3 (t/with db0 [{:seon.agent/key "a1" :seon.agent/turn 3}])]
    (try (reset! agent/state {:key "a1" :db db0})
         (is (= 0 (agent/turn-now)) "before its first turn")
         (reset! agent/state {:key "a1" :db db3})
         (is (= 3 (agent/turn-now)))
         (is (= [["a1"] false] ((juxt (comp #(mapv second %) :seon.message/to) #(contains? % :seon.message/call)) (first (agent/entries-tx [{:id "m" :from (seon.task/sender "owner") :body "x" :sent-ms 1000}]))))
             "a message delivered to the agent carries its agent, and no turn: the turn that reads it is derived from when it was delivered")
         (finally (reset! agent/state old)))))

(deftest failures-that-got-the-same-value-are-one-entry
  ;; 2026-10-29, the md-scan bench: nine assertions that each threw one NullPointerException were
  ;; sent as nine entries, the same error nine times
  (let [npe "#error {:via [{:type java.lang.NullPointerException}]}"
        fs (status/failures [{:expected "(= 1 (f 1))" :actual npe} {:expected "(= 2 (f 2))" :actual npe}
                              {:expected "(= 3 (g))" :actual "(= 3 4)"}])]
    (is (= 2 (count fs)) "one entry per value got, in first-failure order")
    (is (= ["(= 1 (f 1))" "(= 2 (f 2))"] (mapv :seon.print/code (:asserts (first fs)))) "the assertions that got it")
    (is (= npe (:seon.print/code (:got (first fs)))))
    (is (= "(= 3 (g))" (:seon.print/code (:assert (second fs)))) "a failure of its own keeps its one assertion")))

(deftest the-repl-is-where-its-last-eval-left-it-or-where-its-task-starts
  ;; docs/agent.md#the-repl: before any eval the REPL is where it starts, the task's namespace; the turn line says where it is
  (let [db (d/db-with (ts/test-db) (task/create-tx (ts/test-db) [{:seon.task/ns 'x :seon.task/name "nsd" :seon.task/plan "Make it."}]))
        db (d/db-with db [{:seon.agent/key "a1" :seon.agent/task [:seon.task/id "x/nsd"]}])
        tree (:tree (ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "a1") :b))]
    (is (= 'x (ctx/current-ns tree)))
    (is (= 'y (ctx/current-ns (assoc tree :seon.agent/ns 'y))))))

(deftest a-draw-reads-the-cites-its-plan-write-derived
  ;; md-scan #10, 2026-10-29: the agent redefined seon.ref/scan, and every draw that parsed the plan
  ;; again ran its broken parser (the layout, the status, the namespaces part); a task's cites are
  ;; entities its plan's write makes (seon.task/cites-tx), which every draw reads through ctx
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "c" :seon.task/plan "Change `demo/f`."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        names #(set (keep (fn [c] (get-in c [:seon.cite/target :var/name])) (vals (:seon.cite/_of (:tree (ctx/build (seon.db/index) % (pdb/eid % :seon.task/id "demo/c") :b))))))]
    (is (= '#{demo/f} (names db)) "the cites its write made")
    (let [db (d/db-with db [{:var/name 'demo/g} {:seon.task/id "demo/c" :seon.task/plan "Change `demo/g`."}])]
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
      (d/transact conn [{:seon.task/id "demo/g" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
      (let [basis (fn [i] {:commit (d/commit-id @conn) :task "demo/g" :turn (inc i) :max-turns 9 :origin :model :agent "a1"})
            turn! (fn [i] (let [b (basis i)]
                            (d/transact conn (ts/turn-tx "a1" (inc i) {:seon.model-call/basis b}))
                            (d/transact conn [{:seon.eval/id (str "e" i) :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/call (seon.task/call-ref "a1" (inc i))
                                               :seon.eval/code (str "(f " i ")") :seon.eval/response {:handle (symbol "result" (str "e" i)) :value i} :seon.eval/call-id (str "c" i)}])
                            b))
            b0 (turn! 0) _ (turn! 1) b2 (turn! 2)
            evals (fn [r] (set (keep (fn [[[k x]]] (when (= :eval k) x)) (:parts r))))
            at (fn [b] (wire/render (d/commit-as-db conn (:commit b)) b))]
        (is (empty? (evals (at b0))) "turn 1 at its commit: no eval made after it")
        (is (= #{"e0" "e1"} (evals (at b2))) "turn 3 at its commit: the evals before it")))))

;; ---- example sessions in an agent's context (specs/example-sessions) ----

(defn- source-session!
  "Agent b/src's session on task demo/h, as a curated example would have it: an eval of code that called (its
  value shown as 3), a message and a reply with no call, each in a transaction of its own."
  ([conn] (source-session! conn "(+ 1 2)"))
  ([conn code]
   (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "h" :seon.task/plan "Make h." :seon.task/proofs #{'demo-test/h-test}}]))
   (d/transact conn [{:seon.agent/key "b/src" :seon.agent/turn 2 :seon.agent/task [:seon.task/id "demo/h"]}])
   (d/transact conn [{:seon.eval/id "s1" :seon.eval/by {:seon.agent/key "src"} :seon.eval/agent [:seon.agent/key "b/src"] :seon.eval/call (seon.task/call-ref "b/src" 1)
                      :seon.eval/code code :seon.eval/response {:handle 'result/s1 :value 3} :seon.eval/call-id "call_1" :seon.eval/intent "Add."}])
   (d/transact conn [{:seon.message/id "m1" :seon.message/to [[:seon.agent/key "b/src"]] :seon.message/from {:seon.agent/key "owner"} :seon.message/body "Looks right."}])
   (d/transact conn [{:seon.model-call/id (seon.lib/call-id "b/src" 2) :seon.model-call/agent [:seon.agent/key "b/src"] :seon.model-call/n 2 :seon.model-call/origin :model :seon.model-call/forms 0 :seon.model-call/reply "Done."}])))

(defn- example-world!
  "A branch with task demo/g, its agent b/a1 and its session, the system prompt, and example session abc made from b/src
  (`source-session!`, `session/session-tx`) named by the task; answers the session-tx."
  ([conn] (example-world! conn "(+ 1 2)"))
  ([conn code]
  (d/transact conn (doc/load-tx @conn "docs" {doc/system-page (slurp (str (seon.config/checkout) "/" doc/system-page))}))
  (source-session! conn code)
  (let [tx (session/session-tx @conn "b/src" "abc")]
    (doseq [t tx] (d/transact conn t))
    (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}
                                             :seon.task/examples #{[:seon.agent/key "example/abc"]}}]))
    (d/transact conn [{:seon.task/id "demo/g" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
    tx)))

(defn- turn!
  "Turn n (counting from 0, the agent's turn n+1) of session demo/g#1 (the id its own-session opening names) recorded and its request made: the turn,
  with the basis its page message is made from, then {:messages :parts}."
  [conn n]
  (let [n (inc n)
        b {:commit (d/commit-id @conn) :task "demo/g" :session "demo/g#1" :turn n :max-turns 9 :origin :model :agent "a1"}
        _ (d/transact conn (conj (ts/turn-tx "a1" n {})
                                 {:seon.model-call/id (seon.lib/call-id "a1" n) :seon.model-call/basis b :seon.model-call/forms 1 :seon.model-call/ms 1}))]
    (wire/render @conn (assoc b :commit (d/commit-id @conn)))))

(deftest a-task-with-an-example-reads-it-between-the-system-prompt-and-its-own-session
  (ts/test-branch!
    (fn [_ conn]
      (example-world! conn)
      (let [r0 (turn! conn 0)
            _ (d/transact conn [{:seon.eval/id "e0" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent [:seon.agent/key "a1"]
                                 :seon.eval/call (seon.task/call-ref "a1" 1) :seon.eval/code "(+ 5 1)" :seon.eval/response {:handle 'result/e0 :value 6} :seon.eval/call-id "c0"}])
            r1 (turn! conn 1)
            ms (:messages r0)
            [sys open & more] ms
            close-at (first (keep-indexed #(when (= "</session>" (:content %2)) %1) more))
            example (subvec (vec more) 0 close-at)
            db @conn
            original (mapcat second (seon.fixtures/tree-parts (:tree (seon.ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "b/src") :b)) db))
            prefix #(un-example db "example/abc" (pr-str %))]
        (is (= "system" (:role sys)))
        (is (= {:role "user" :content "<session id=\"example/abc\" kind=\"example\" from=\"seon\">\nA finished session of another task, shown for how its work was done; not yours. Its request:\nMake h."} open) "the example's opening and its request")
        (is (= (prefix (vec original)) (prefix (vec example))) "its records, printed as the agent's own are, each id the example's")
        (is (= ["assistant" "tool" "user" "assistant"] (mapv :role example)) "an eval's pair, a message, a reply")
        (is (= (seon.lib/id (seon.lib/digest "example/abc/s1")) (get-in example [0 :tool-calls 0 :id]) (:tool-call-id (second example))) "every call is followed by its tool message")
        (is (= {:role "user" :content "</session>"} (nth more close-at)))
        (is (= {:role "user" :content "<agent id=\"a1\" from=\"seon\">\nYour own work: the work you are asked to do."} (nth more (inc close-at))) "then the agent's own session")
        (is (str/includes? (:content (peek (:messages r0))) "Make g.") "then the page, as Seon's message")
        (is (= ms (subvec (:messages r1) 0 (count ms))) "the second request starts with the first, examples included")
        (is (= [:example "example/abc"] (vec (take 2 (first (second (:parts r0)))))) "the example is a ref of the request")))))

(deftest an-example-sends-a-cut-reply-as-cut
  ;; docs/tasks.md#a-task-names-examples: a reply the provider cut at its limit reads so in the session and in its example alike
  (ts/test-branch!
    (fn [_ conn]
      (source-session! conn)
      (d/transact conn [{:seon.model-call/id (seon.lib/call-id "b/src" 2) :seon.model-call/truncated true}])
      (let [replies (->> (session/session-tx @conn "b/src" "abc") (mapcat identity) (filter :seon.model-call/reply))]
        (is (= [true] (map :seon.model-call/truncated replies)))))))

(deftest a-task-with-no-examples-reads-what-it-read-before
  ;; the same task with no :seon.task/examples and with an empty one: the same messages
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (doc/load-tx @conn "docs" {doc/system-page (slurp (str (seon.config/checkout) "/" doc/system-page))}))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}}]))
      (d/transact conn [{:seon.task/id "demo/g" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
      (let [none (turn! conn 0)
            a (pdb/eid @conn :seon.agent/key "a1")
            _ (d/transact conn [[:db/add [:seon.task/id "demo/g"] :seon.task/examples a]])
            _ (d/transact conn [[:db/retract [:seon.task/id "demo/g"] :seon.task/examples a]])
            b (:seon.model-call/basis (d/pull @conn [:seon.model-call/basis] [:seon.model-call/id (seon.lib/call-id "a1" 1)]))
            empty-set (wire/render @conn b)]
        (is (= ["system" "user" "assistant" "tool" "assistant" "tool"] (mapv :role (:messages none))) "the system prompt, what opens the session, then the page as a generated eval")
        (is (= (:messages none) (:messages empty-set)) "an emptied :seon.task/examples reads as none")
        (is (not-any? #(str/includes? (:content %) "kind=\"example\"") (:messages none)))
        (is (not-any? #(= :example (ffirst %)) (:parts none)))))))

(deftest an-example-of-a-session-recorded-with-string-senders-writes-refs
  ;; docs/tasks.md#a-task-names-examples: a store kept from before senders were refs holds them as strings; an example
  ;; exported from it writes each as the ref it names (seon.ctx.session's who): an agent's key that agent, "root" and "seon" the system,
  ;; the session's own agent the example's
  (let [rekey #'seon.ctx.session/example-rekey
        msg (fn [from] (:seon.message/from (rekey "example/x" "a1" {:seon.message/id "m" :seon.message/from from :seon.message/body "b" :turn 1}
                                              [:seon.message/id :seon.message/to nil])))
        by (fn [b] (:seon.eval/by (rekey "example/x" "a1" {:seon.eval/id "e" :seon.eval/by b :seon.eval/code "(f)" :turn 1}
                                         [:seon.eval/id :seon.eval/agent :seon.eval/call])))]
    (is (= {:seon.agent/key "user"} (msg "user")) "a string sender: the agent it names")
    (is (= seon.task/system-sender (msg "root")) "root's name: the system")
    (is (= {:seon.agent/key "example/x"} (by "a1")) "the session's own agent: the example's")
    (is (= seon.task/system-sender (by "seon")) "Seon's: the system")
    (is (= {:seon.agent/key "user"} (msg {:seon.agent/key "user"})) "a joined sender, as a store kept since reads it")))

(deftest an-example-session-is-data-that-recreates-the-session
  (ts/test-branch!
    (fn [_ conn]
      (source-session! conn)
      (let [tx (session/session-tx @conn "b/src" "abc")
            names (fn [db k] (let [a (pdb/eid db :seon.agent/key k)]
                               (seon.fixtures/tree-messages (:tree (seon.ctx/build (seon.db/index) db a :b)) db)))]
        (is (= tx (clojure.edn/read-string (pr-str tx))) "plain data: written with pr-str, read back")
        (is (= {:seon.agent/key "example/abc" :seon.agent/status :ended :seon.agent/description "Make h." :seon.agent/turn 2} (ffirst tx)) "its turn the last its records carry: its page draws a component for each")
        (is (= 4 (count tx)) "the agent, then a transaction for each of three records")
        (ts/test-branch!
          (fn [_ fresh]
            (doseq [t (clojure.edn/read-string (pr-str tx))] (d/transact fresh t))
            (is (= (pr-str (names @conn "b/src")) (un-example @conn "example/abc" (pr-str (names @fresh "example/abc"))))
                "the example draws the same messages as the original's records")
            (is (= [(seon.lib/id (seon.lib/digest "example/abc/s1")) (seon.lib/id (seon.lib/digest "example/abc/m1")) (seon.lib/call-id "example/abc" 2)] (mapv (comp second first) (seon.fixtures/tree-parts (:tree (seon.ctx/build (seon.db/index) @fresh (pdb/eid @fresh :seon.agent/key "example/abc") :b)) @fresh)))
                "ids re-keyed under the example, in the order they ran, a model call's `<key>/<n>` as any agent's")))))))

(deftest the-page-draws-its-tasks-examples-in-the-conversation
  (ts/test-branch!
    (fn [_ conn]
      (example-world! conn)
      (let [db @conn
            a (pdb/eid db :seon.agent/key "a1")
            w (seon.fixtures/draw (:tree (ctx/build (seon.db/index) db a :b)) ctx/changes db #'seon.render.ui/agent)
            html (try (pr-str (render/placed-hiccup w ["#c-rows"])) (finally (render/dispose-walk! w)))]
        (is (str/includes? html "example/abc"))
        (is (str/includes? html "(+ 1 2)") "its eval, by the one hiccup an eval has")))))


(defn- no-example-requests!
  "Two requests of the fixed fixture of task demo/g, named by no example: its session of one eval, one message and one
  text reply, on conn (a branch with the system prompt loaded); answers {:r0 :r1}."
  [conn]
  (d/transact conn (doc/load-tx @conn "docs" {doc/system-page (slurp (str (seon.config/checkout) "/" doc/system-page))}))
  (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}}]))
  (d/transact conn [{:seon.task/id "demo/g" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
  (let [r0 (turn! conn 0)]
    (d/transact conn [{:seon.eval/id "e0" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent [:seon.agent/key "a1"] :seon.eval/call (seon.task/call-ref "a1" 1)
                       :seon.eval/code "(+ 5 1)" :seon.eval/response {:handle 'result/e0 :value 6} :seon.eval/call-id "c0" :seon.eval/intent "Add."}])
    (d/transact conn [{:seon.message/id "m0" :seon.message/to [[:seon.agent/key "a1"]] :seon.message/from {:seon.agent/key "owner"} :seon.message/body "Go on."}])
    (d/transact conn [{:seon.model-call/id (seon.lib/call-id "a1" 2) :seon.model-call/agent [:seon.agent/key "a1"] :seon.model-call/n 2 :seon.model-call/origin :model :seon.model-call/forms 0 :seon.model-call/reply "Thinking."}])
    {:r0 r0 :r1 (turn! conn 2)}))

(deftest a-task-with-no-examples-sends-the-first-request
  ;; (the bytes of main's request were pinned here by a literal; the page is now each part printed as its render, which
  ;; changes with each render step: the request's shape is what this holds)
  (ts/test-branch!
    (fn [_ conn]
      (let [{:keys [r0]} (no-example-requests! conn)
            ms (:messages r0)]
        (is (= {:role "system" :content (wire/system-text @conn)} (first ms)))
        (is (= ["system" "user" "assistant" "tool" "assistant" "tool"] (mapv :role ms)) "the system prompt, what opens the session, then the page whole as a generated eval")
        (is (str/includes? (:content (peek ms)) "Task: ") "the task's part, by its own render")
        (is (re-find #"demo-test/g-test" (:content (peek ms))) "and its proof")))))

(deftest a-task-with-no-examples-sends-the-conversation
  (ts/test-branch!
    (fn [_ conn]
      (let [{:keys [r0 r1]} (no-example-requests! conn)
            ms (:messages r1)]
        (is (= (:messages r0) (subvec ms 0 (count (:messages r0)))) "the first request is the prefix of the next")
        (is (= [{:role "assistant" :content "" :tool-calls [{:id "e0" :code "(+ 5 1)" :intent "Add."}] :reply 1}
                {:role "tool" :tool-call-id "e0" :content "{:handle result/e0, :value 6}"}
                {:role "user" :content "{:from \"owner\", :body \"Go on.\"}"}
                {:role "assistant" :content "Thinking." :reply 2}]
               (subvec ms (count (:messages r0)) (+ 4 (count (:messages r0)))))
            "then the records, each by its own render")))))

(deftest session-tx-answers-an-error-for-what-it-cannot-show
  (ts/test-branch!
    (fn [_ conn]
      (source-session! conn)
      (d/transact conn [{:seon.agent/key "b/bare"}])
      (let [none (session/session-tx @conn "b/none" "x")
            bare (session/session-tx @conn "b/bare" "x")]
        (is (= [:seon.ctx/session-refused "b/none"] ((juxt :seon.error/type :key) none)) "no agent has the key")
        (is (str/includes? (:seon.error/message none) "no agent is keyed"))
        (is (= [:seon.ctx/session-refused "b/bare"] ((juxt :seon.error/type :key) bare)) "an agent with no plan and no description has no request to show: not an empty one")
        (is (str/includes? (:seon.error/message bare) "no request to show"))
        (is (vector? (session/session-tx @conn "b/src" "x")) "an agent that has a request is shown")
        ;; a record is its own attributes less :db/id and :at (session-records): a ref it holds (a message's :seon.message/to) is re-keyed
        ;; by its identity (example-rekey), never an entity id of the store it came from
        (is (not-any? #(and (map? %) (contains? % :db/id)) (tree-seq coll? seq (session/session-tx @conn "b/src" "x"))) "no :db/id anywhere in what it answers")))))

(deftest an-example-refuses-a-history-record-of-a-kind-it-does-not-hold-naming-its-schema
  ;; docs/tasks.md#a-task-names-examples: a member the conversation places (seon.ctx/placed-messages) is recorded as the entity it is,
  ;; by its schema, never as a message; one of a kind an example does not hold (the note of seon.render-test's registered-schema world) is refused, naming its schema, never dropped
  (ts/test-branch!
    (fn [_ conn]
      (source-session! conn)
      (doseq [[k form] [[:lab.note/id [:and {:seon.db/identity true} :string]] [:lab.note/text :string]
                        [:lab.note/agent [:and {:seon.db/to [:seon.db/agent-fact]} :seon.db/ref]]
                        [:lab.note/note-fact [:map {:seon.db/attributes true :seon.render/hiccup 'seon.render-test/note-html :seon.render/openai-compat 'seon.render-test/note-msgs}
                                                 [:lab.note/id :lab.note/id] [:lab.note/text :lab.note/text] [:lab.note/agent :lab.note/agent]]]]]
        (d/transact conn (:tx (my.schema/registration @conn k form false))))
      (d/transact conn [{:seon.agent/key "b/src" :seon.ctx/pattern (conj (render/schema-property (seon.db/index) :seon.db/agent-fact :seon.ctx/pattern)
                                                                         {[:lab.note/_agent :tx :at] [:db/id :lab.note/id :lab.note/text]})}])
      (d/transact conn [{:lab.note/id "n1" :lab.note/text "the note" :lab.note/agent [:seon.agent/key "b/src"]}])
      ;; a turn after it, so the note is placed in a turn the example records
      (d/transact conn [{:seon.model-call/id (seon.lib/call-id "b/src" 3) :seon.model-call/agent [:seon.agent/key "b/src"] :seon.model-call/n 3 :seon.model-call/origin :model :seon.model-call/reply "Noted."}])
      (let [r (session/session-tx @conn "b/src" "x")]
        (is (= :seon.ctx/session-refused (:seon.error/type r)) "a record of a kind an example does not hold is refused")
        (is (str/includes? (str (:seon.error/message r)) ":lab.note/note-fact") "naming its schema")))))

(deftest an-example-keeps-an-eval-of-ctx-as-its-marker
  ;; the owner, 2026-11-04: "if an agent needs to swap or modify ctx then that needs to be present": no eval is left out of an
  ;; example; one whose basis a copy cannot rebuild keeps its code and shows its value as the marker of what it was
  (ts/test-branch!
    (fn [_ conn]
      (source-session! conn)
      (d/transact conn [{:seon.eval/id "s3" :seon.eval/by {:seon.agent/key "src"} :seon.eval/agent [:seon.agent/key "b/src"] :seon.eval/call (seon.task/call-ref "b/src" 1) :seon.eval/schema :seon.ctx/ctx
                         :seon.eval/basis {:t 5 :branch :b} :seon.eval/code "(swap! ctx assoc :x 1)" :seon.eval/call-id "call_3"
                         :seon.eval/response {:handle 'result/s3}}])
      (let [recs (->> (session/session-tx @conn "b/src" "abc") (mapcat identity) (filter :seon.eval/code))
            r3 (first (filter #(= "(swap! ctx assoc :x 1)" (:seon.eval/code %)) recs))]
        (is (= 2 (count recs)) "both evals are in the example")
        (is (nil? (:seon.eval/basis r3)) "no basis a copy cannot rebuild")
        (is (string? (get-in (seon.db/response-of nil r3) [:value :seon.value/gone :type])) "its value, the marker of what it was")
        (is (str/includes? (print/record-text (assoc r3 :seon.eval/response (seon.db/response-of nil r3))) "seon.value/gone") "which the transcript prints like any map")))))

(deftest an-example-keeps-no-id-of-the-session-it-was-made-from
  ;; the result handle its response and printed text name is the example's (nothing reads an example's evals by the
  ;; source session's)
  (ts/test-branch!
    (fn [_ conn]
      (source-session! conn)
      (d/transact conn [{:seon.eval/id "s2" :seon.eval/by {:seon.agent/key "src"} :seon.eval/agent [:seon.agent/key "b/src"]
                         :seon.eval/call (seon.task/call-ref "b/src" 1) :seon.eval/code "(atom 1)" :seon.eval/response {:handle 'result/s2 :value {:seon.value/type "clojure.lang.Atom"}} :seon.eval/call-id "call_2"}])
      (let [recs (->> (session/session-tx @conn "b/src" "abc") (mapcat identity) (filter :seon.eval/code))]
        (is (= 2 (count recs)))
        (is (not-any? #(re-find #"b/src|demo/h" (pr-str %)) recs) "no id of the source session in a record")
        (is (= '#{result/s1 result/s2} (set (map #(get-in % [:seon.eval/response :handle]) recs))) "a handle names its answer: it stays as it was")))))

(defn- two-examples-request!
  "Two sessions whose records carry the same ids (s1, call_1, result/s1: ids are an agent's own), loaded into one db
  in the order def, abc and named by task demo/g; answers {:r0 :r1}, requests 0 and 1 of its session."
  [conn]
  (let [from (fn [id code] (ts/test-branch! (fn [_ c] (source-session! c code) (session/session-tx @c "b/src" id))))
        tx-a (from "abc" "(+ 1 2)")
        tx-b (from "def" "(+ 3 4)")]
    (d/transact conn (doc/load-tx @conn "docs" {doc/system-page (slurp (str (seon.config/checkout) "/" doc/system-page))}))
    (doseq [t (concat tx-b tx-a)] (d/transact conn t))
    (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}
                                             :seon.task/examples #{[:seon.agent/key "example/def"] [:seon.agent/key "example/abc"]}}]))
    (d/transact conn [{:seon.task/id "demo/g" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
    (let [r0 (turn! conn 0)] {:r0 r0 :r1 (turn! conn 1)})))

(deftest two-examples-keep-their-order
  (ts/test-branch!
    (fn [_ conn]
      (let [{:keys [r0 r1]} (two-examples-request! conn)
            ms (:messages r0)
            opens (keep-indexed (fn [i m] (when (str/starts-with? (:content m "") "<session id=\"example/") i)) ms)]
        (is (= [[:example "example/abc"] [:example "example/def"]] (mapv #(vec (take 2 (first %))) (filter #(= :example (ffirst %)) (:parts r0)))) "in key order, whatever order they were loaded")
        (is (= [1 7] (vec opens)) "each example opens after the system message and the one before it closes")
        (is (= ["<session id=\"example/abc\" kind=\"example\" from=\"seon\">" "<session id=\"example/def\" kind=\"example\" from=\"seon\">"]
               (mapv #(first (str/split-lines (:content (nth ms %)))) opens)))
        (is (= ["</session>" "</session>"] (mapv #(:content (nth ms %)) [6 12])))
        (is (= [["assistant" "tool" "user" "assistant"] ["assistant" "tool" "user" "assistant"]] [(mapv :role (subvec ms 2 6)) (mapv :role (subvec ms 8 12))]) "each in the order its records ran")
        (is (= ms (subvec (:messages r1) 0 (count ms))) "both examples are the prefix of the next request")))))

(deftest two-examples-keep-their-call-ids-apart
  (ts/test-branch!
    (fn [_ conn]
      (let [{:keys [r0]} (two-examples-request! conn)
            ms (without-page (:messages r0))
            calls (keep #(get-in % [:tool-calls 0 :id]) ms)
            tool-text (fn [id] (:content (first (filter #(= id (:tool-call-id %)) ms))))]
        (is (= [(seon.lib/id (seon.lib/digest "example/abc/s1")) (seon.lib/id (seon.lib/digest "example/def/s1"))] (vec calls)) "no call id is another's: each example's own")
        (is (= (vec calls) (vec (keep :tool-call-id ms))) "every call is followed by its tool message")
        (is (string? (tool-text (seon.lib/id (seon.lib/digest "example/abc/s1")))))
        (is (string? (tool-text (seon.lib/id (seon.lib/digest "example/def/s1")))))))))

(defn- recorded-form
  "A request's messages as the recording holds them: the wire's messages (seon.model/wire-messages, what the provider is sent),
  the system prompt's text out (the wiki's own, moving with its edits) and each number of three digits or more renumbered by
  first appearance (a test branch's eids and ts differ run to run), and so is each commit's uuid and each 16-character id, everything else the bytes."
  [ms]
  (let [seen (volatile! {})
        renumber (fn [s re prefix] (clojure.string/replace s re (fn [n] (or (get @seen n) (let [k (str prefix (count @seen))] (vswap! seen assoc n k) k)))))]
    (-> (pr-str (mapv #(if (= "system" (:role %)) (assoc % :content "<system>") %) (seon.model/wire-messages true ms)))
        ;; a commit's uuid, and the id of a page call made from its point (seon.lib/id of a digest), differ run to run; and a transaction's
        ;; instant, which the record shown at the first point holds under each conversation join's :at
        (renumber #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" "U")
        (renumber #"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z" "T")
        (renumber #"(?<![0-9a-zA-Z])[a-z][0-9a-f]{15}(?![0-9a-zA-Z])" "I")
        (renumber #"(?<![0-9.])[0-9]{3,}(?![0-9.])" "N"))))

(defn- sent-turns
  "Two model turns as seon.turn records them, with the task's plan changed after turn 0 and again after turn 1: each turn's model call with the hash of the bytes it sent. Answers [db [r0 r1] [e0 e1]]: the requests' messages
  and the turns' model calls (turn 0 replies with reasoning, turn 1 follows its call's eval)."
  [conn]
  (wiki! conn)
  (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
  (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                           :seon.task/proofs #{'demo-test/f-test}}]))
  (d/transact conn [{:seon.task/id "demo/f" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/f"}}}])
  (let [turn! (fn [n more]
                (d/transact conn (ts/turn-tx "a1" n more))
                (let [b {:commit (d/commit-id @conn) :task "demo/f" :turn n :max-turns 4 :origin :model :agent "a1"}
                      _ (d/transact conn [{:seon.model-call/id (seon.lib/call-id "a1" n) :seon.model-call/basis b :seon.model-call/forms 1
                                           :seon.model-call/ms 1}])
                      ms (:messages (wire/render @conn b))]
                  (d/transact conn [{:seon.model-call/id (seon.lib/call-id "a1" n) :seon.model-call/sent-hash (seon.lib/digest (seon.edn/encode ms))}])
                  ms))
        r0 (turn! 1 {:seon.model-call/reasoning "why"})
        _ (d/transact conn [{:seon.eval/id "e2" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/code "(f 1)"
                             :seon.eval/response {:handle 'result/e2 :value 1} :seon.eval/call-id "call-0" :seon.eval/intent "I call f." :seon.eval/call (seon.task/call-ref "a1" 1)}])
        _ (d/transact conn [{:seon.task/id "demo/f" :seon.task/plan "Make f triple."}])
        r1 (turn! 2 {})
        _ (d/transact conn [{:seon.task/id "demo/f" :seon.task/plan "Later."}])
        ;; and the system prompt changes: a turn's system prompt is its section as of the turn's t
        _ (d/transact conn [{:db/id (seon.ref/page-top @conn doc/system-page) :seon.doc/text "Changed after the turns."}])
        db @conn
        e-of (fn [id] (d/q '[:find ?e . :in $ ?id :where [?e :seon.model-call/id ?id]] db id))]
    [db [r0 r1] [(e-of (seon.lib/call-id "a1" 1)) (e-of (seon.lib/call-id "a1" 2))]]))

(defn- recorded-sessions
  "Three sessions' requests, each turn's: the page moved by a plan's change and evals between turns; replies with calls and
  reasoning and a plan that changes; an example session."
  []
  (merge
    (ts/test-branch! (fn [_ conn]
                       (let [[ask! ev! plan!] (parts-session conn)
                             r0 (ask! 0) _ (ev! "e1") _ (ev! "e2") r1 (ask! 1) _ (plan! "Make f, and g.") _ (ev! "e3") r2 (ask! 2)]
                         {:parts (mapv (comp recorded-form :messages) [r0 r1 r2])})))
    (ts/test-branch! (fn [_ conn] (let [[_ rs] (sent-turns conn)] {:calls (mapv recorded-form rs)})))
    (ts/test-branch! (fn [_ conn] (example-world! conn) {:example (mapv #(recorded-form (:messages (turn! conn %))) [0 1])}))))

;; ---- the one tree (docs/render.md#the-models-context-a-cached-prefix-then-the-turn) ----

(defn- tree-world
  "A session on the world: the system prompt, its agent b/a1 and turn 1's reply with the evals evs, each {:id :call-id}: [db agent-eid]."
  [& evs]
  (let [db (d/db-with (ts/test-db) (doc/load-tx (ts/test-db) "docs" {doc/system-page "# You are a Seon agent\n\nThe rules.\n\n## The small one\n\nBe brief."}))
        db (d/db-with db (into (ts/turn-tx "a1" 1 {:seon.model-call/reply "I look."})
                               (for [{:keys [id call-id by]} evs]
                                 (cond-> {:seon.eval/id id :seon.eval/by (seon.task/sender (or by "a1")) :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/call (seon.task/call-ref "a1" 1)
                                          :seon.eval/code (str "(" id ")") :seon.eval/response {:handle (symbol "result" id) :value 1}}
                                   call-id (assoc :seon.eval/call-id call-id :seon.eval/intent (str "Run " id "."))))))]
    [db (pdb/eid db :seon.agent/key "a1")]))

(defn- wire-of-world
  "The request of agent a on db, read from the tree drawn cold."
  [db] (:messages (wire/render db {:commit #uuid "00000000-0000-0000-0000-000000000001" :task "t/x" :turn 2 :max-turns 9 :origin :model :agent "a1"})))

(deftest a-smaller-pattern-is-a-smaller-context
  ;; owner, 2026-11-06: the agent's pull says what its tree holds: a new agent entity with a smaller pattern gets a smaller context with no other mechanism, its system
  ;; message the doc section it links
  (let [[db a] (tree-world {:id "e1" :call-id "c"})
        section (pdb/eid db :seon.doc/address (first (filter #(str/includes? % "the-small-one") (map :v (d/datoms db :aevt :seon.doc/address)))))
        pattern '[:seon.agent/key :seon.agent/turn {:seon.agent/system [:db/id]}
                  {:seon.eval/_agent [:seon.eval/id :seon.eval/by :seon.eval/code :seon.eval/intent :seon.eval/call-id :seon.eval/response]}
                  {:seon.model-call/_agent [:seon.model-call/id :seon.model-call/n :seon.model-call/origin {:seon.eval/_call [:db/id]}]}]
        ;; turn 1's call names the point its page message is made from (its basis), so the whole agent has a page to be shown
        task (d/db-with db (into (seon.task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f." :seon.task/proofs #{'demo-test/f-test}}])
                                 [{:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/f"}}
                                  {:seon.model-call/id (seon.lib/call-id "a1" 1) :seon.model-call/basis {:commit #uuid "00000000-0000-0000-0000-000000000001" :t (:max-tx db) :task "demo/f" :turn 1 :max-turns 9 :origin :model :agent "a1"}}]))
        small (d/db-with task [{:db/id a :seon.ctx/pattern pattern :seon.agent/system [section]}])
        full (wire-of-world task)
        sent (wire-of-world small)]
    (is (= {:role "system" :content "## The small one\n\nBe brief."} (first sent)) "the section it links, not the whole prompt")
    (is (some #(str/starts-with? (str (:code (first (:tool-calls %)))) "(my.render/page ") full) "the whole agent is shown its page")
    (is (not-any? page-call? sent) "this one's pull holds no basis for a page: none is shown")
    (is (not (str/includes? (pr-str sent) "I look.")) "its pull holds no reply: none is sent")
    (is (= ["(e1)"] (keep #(get-in % [:tool-calls 0 :code]) (without-page sent))) "what it pulled, its eval, is")
    (is (< (count (pr-str sent)) (count (pr-str full))) "smaller")))

(deftest a-model-render-that-threw-is-a-message-saying-so
  ;; a component whose model render threw answers, in its messages' place, the render :seon.render/component-defect declares
  ;; (seon.ctx.wire/defect-msgs): the model is told a part is missing and what threw, never left reading around a gap
  (let [ix (seon.db/index)
        e (seon.contract/error :seon.render/component-defect 'seon.render/render! "seon.render.ui/task threw: boom" {:seon.error/in 'seon.render.ui/task})
        sym (render/render-for ix :seon.render/component-defect :seon.render/openai-compat)
        ms (@(requiring-resolve sym) {:seon.db/db (ts/test-db) :seon.render/index ix} e)]
    (is (= 'seon.ctx.wire/defect-msgs sym) "the schema names it")
    (is (= ["user"] (mapv :role ms)) "one user message")
    (is (str/includes? (:content (first ms)) "seon.render.ui/task") "naming the render that threw")
    (is (str/includes? (:content (first ms)) "boom") "and what it said")))

(deftest a-request-with-no-system-message-is-refused
  ;; an error is data, and a request does not silently lose its system message: a db without the prompt is refused, typed, as seon.ctx.wire/system-text's contract did
  (let [db0 (ts/test-db)
        ;; the store's stub prompt taken away
        db (d/db-with db0 (concat (for [d (d/datoms db0 :avet :seon.doc/address) :when (str/starts-with? (:v d) doc/system-page)] [:db/retractEntity (:e d)])
                                  (ts/turn-tx "a1" 1 {})))
        e (try (wire-of-world db) nil (catch Throwable e (ex-data e)))]
    (is (= :seon.ctx/no-system-message (:seon.error/type e)) (pr-str e))
    (is (str/includes? (:seon.error/message e) "the agent's render answered none") "and says that the agent's render answered no message")))

(deftest a-provider-reusing-its-call-id-duplicates-no-call
  ;; a new eval reusing a call id read as one call answered twice: the wire id is the eval's own
  (let [[db] (tree-world {:id "e1" :call-id "call_1"} {:id "e2" :call-id "call_1"})
        ms (without-page (wire-of-world db))]
    (is (= ["e1" "e2"] (keep #(get-in % [:tool-calls 0 :id]) ms)))
    (is (= ["e1" "e2"] (keep :tool-call-id ms)))))

(deftest an-eval-of-another-hand-comes-after-the-replys-calls
  ;; a reply node holds its evals, so one of another hand made between them cannot split the reply on the wire
  (let [[db] (tree-world {:id "e1" :call-id "c1"} {:id "e2" :by "outside"} {:id "e3" :call-id "c3"})
        wire (seon.model/wire-messages true (without-page (wire-of-world db)))
        reply (first (filter #(seq (:tool_calls %)) wire))]
    (is (= ["e1" "e3"] (mapv :id (:tool_calls reply))) "both its evals in the one assistant message, a call each")
    (is (= ["(e1)" "(e3)"] (mapv #(get (clojure.data.json/read-str (:arguments (:function %))) "code") (:tool_calls reply))))
    (is (str/includes? (:content reply) "I look."))
    (is (str/includes? (:content (last wire)) "{:by \"outside\" :code \"(e2)\"") "the other hand's after it")))

(deftest a-re-eval-updates-its-own-message-in-place
  ;; owner, 2026-11-06: "yes we can re-eval something and update it": the eval's id is its place, and everything before it stays byte for byte
  (let [[db] (tree-world {:id "e1" :call-id "c1"} {:id "e2" :call-id "c2"})
        before (without-page (wire-of-world db))
        after (without-page (wire-of-world (d/db-with db [{:seon.eval/id "e1" :seon.eval/response {:handle 'result/e1 :value 42}}])))
        at (first (keep-indexed (fn [i [a b]] (when (not= a b) i)) (map vector before after)))]
    (is (= (count before) (count after)) "no message more")
    (is (= [:same-prefix "tool" "e1"] [(when (= (subvec before 0 at) (subvec after 0 at)) :same-prefix) (:role (nth after at)) (:tool-call-id (nth after at))]) "its own result moved")
    (is (str/includes? (:content (nth after at)) "42"))
    (is (= (subvec before (inc at)) (subvec after (inc at))) "and nothing after it")))

(deftest a-page-message-is-a-generated-eval-with-no-wrapper
  ;; docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup: the page reaches the model as a
  ;; whole reply of the agent's, its text and a call with its intent (owner, 2026-11-11: "Seon's own calls should carry an intent too"),
  ;; the words read from the called function's var, and its result, the value printed whole by the one printer, nothing around it
  (let [edits [[["status"] :r [:p "moved"]]]
        q0 "{:commit #uuid \"00000000-0000-0000-0000-000000000001\", :t 1}" q1 "{:commit #uuid \"00000000-0000-0000-0000-000000000002\", :t 2}"
        id (seon.lib/id (seon.lib/digest "points"))
        [call result :as ms] (wire/page-message id 'my.render/diff [q0 q1] edits)
        {:seon.model-call/keys [reply] :seon.eval/keys [intent]} (meta #'my.render/diff)]
    (is (= ["assistant" "tool"] (mapv :role ms)))
    (is (= {:role "assistant" :content reply :tool-calls [{:id id :intent intent :code (str "(my.render/diff " q0 " " q1 ")")}]} call)
        "one reply: the function's words, then its call with its intent")
    (is (= id (:tool-call-id result)) "and its result names it")
    (is (= "[[[\"status\"] :r [:p \"moved\"]]]" (:content result)) "the edits printed whole, with no {:value ..} around them")
    (is (= ms (wire/page-message id 'my.render/diff [q0 q1] edits)) "the same bytes again")
    (is (= :seon.ctx/unworded (try (wire/page-message id 'clojure.core/inc ["1"] 2) nil (catch clojure.lang.ExceptionInfo e (:seon.error/type (ex-data e)))))
        "a function with no words is refused, never an empty message")))

(deftest a-diffs-reply-names-what-moved
  ;; loop 2 (2026-11-11): a model copied the diff's fixed reply word for word (call 16); the reply is a function of the edits, the parts
  ;; that moved by their names in the edits' paths, so it differs with what moved and says something true when copied
  (is (= "My page changed: status." (wire/diff-reply [[["page" "p-task" "status" :own] :r [:p "x"]] [["page" "p-task" "status"] :r [:p "y"]]])))
  (is (= "My page changed: status, cite-x." (wire/diff-reply [[["page" "p-task" "status"] :-] [["page" "p-task" "cite-x" :pre] :r "s"]])))
  (is (= "My page changed: page." (wire/diff-reply [[["page"] :r [:div]]])) "the page whole, by its own name"))

(deftest every-call-seon-writes-is-a-whole-reply
  ;; the law (owner, 2026-11-11: "Set a good example for the agent"): each function a Seon-made call names holds its reply and its
  ;; intent, and every assistant message of a request that holds no record of the model's own has text and each call an intent
  (doseq [f '[my.agent/describe my.render/page my.render/diff]
          k [:seon.model-call/reply :seon.eval/intent]]
    (is (seq (get (meta (requiring-resolve f)) k)) (str f " holds " k)))
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask!] (parts-session conn)
            r (ask! 0)
            seon-made (filter #(some (fn [c] (re-find #"^\((my.render|my.agent)/" (str (:code c)))) (:tool-calls %)) (:messages r))]
        (is (= 2 (count seon-made)) "the record and the page at the first point")
        (doseq [m seon-made]
          (is (seq (:content m)) (str "text before the call: " (pr-str (dissoc m :tool-calls))))
          (is (every? (comp seq :intent) (:tool-calls m)) (str "each call an intent: " (pr-str (:tool-calls m)))))))))

(deftest a-compacted-turns-page-is-regenerated-from-the-calls-that-remain
  ;; docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup: the page calls are never stored, so
  ;; a swap! that unlinks the middle turn's model call leaves the story the remaining calls tell: the page whole at turn 1's point, one
  ;; diff from turn 1's point to turn 3's, and no mention of the point removed
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! _ plan!] (parts-session conn)
            _ (ask! 0)
            _ (plan! "Make f triple.")
            _ (ask! 1)
            _ (plan! "Make f quadruple.")
            r3 (ask! 2)
            db @conn
            basis (fn [n] (select-keys (:seon.model-call/basis (d/entity db [:seon.model-call/id (seon.lib/call-id "a1" n)])) [:commit :t]))
            point (fn [n] (let [b (basis n)] (pr-str (array-map :commit (:commit b) :t (or (:t b) (:max-tx (d/commit-as-db (pdb/store-of db) (:commit b))))))))
            codes (fn [ms] (vec (keep #(when (page-call? %) (:code (first (:tool-calls %)))) ms)))
            before (codes (:messages r3))
            _ (d/transact conn [[:db/retract [:seon.model-call/id (seon.lib/call-id "a1" 2)] :seon.model-call/agent [:seon.agent/key "a1"]]])
            after (:messages (wire/render @conn {:commit (d/commit-id @conn) :task "demo/f" :turn 3 :max-turns 9 :origin :model :agent "a1"}))]
        (is (= ["(my.agent/describe @ctx)" (str "(my.render/page " (point 1) ")") (str "(my.render/diff " (point 1) " " (point 2) ")") (str "(my.render/diff " (point 2) " " (point 3) ")")] before) "three turns: the page, then a diff at each")
        (is (= ["(my.agent/describe @ctx)" (str "(my.render/page " (point 1) ")") (str "(my.render/diff " (point 1) " " (point 3) ")")] (codes after)) "the middle call unlinked: the page at turn 1's point, then one diff from it to turn 3's")
        (is (not (str/includes? (pr-str after) (point 2))) "and nothing names the point removed")
        (is (str/includes? (:content (peek after)) "quadruple") "the diff holds what moved over both turns, the plan's last text")))))

(deftest the-recorded-requests-come-out-the-same
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: what the model is sent does not change by a byte unless a change means it to, and then this
  ;; rewrites test/seon/recorded_requests.edn in the same commit
  (let [recorded (clojure.edn/read-string (my.branch/file "test/seon/recorded_requests.edn"))
        now (recorded-sessions)]
    (is (= (update-vals recorded count) (update-vals now count)) "the same sessions, the same turns of each")
    (doseq [[k turns] recorded [i t] (map-indexed vector turns)]
      (is (= t (get-in now [k i])) (str k " turn " i ": the bytes it was recorded with")))))

(deftest what-an-eval-recorded-is-in-what-the-model-reads
  ;; task seon.render/one-way-to-make-what-an-agent-is-sent, step 2: the eval tool promises :recorded, an outside agent read
  ;; it, and the stored record kept it beside its response, where no model's message printed it
  (let [rec (seon.repl/record-details "(defn f [] 1)" {:handle 'result/e1 :value #'clojure.core/inc :recorded '[app.x/f] :fns [{:f 'a/b}] :text "t"} {:call-id "c" :intent "p"})
        data (seon.repl/record-details "(defn f [] 1)" {:handle 'result/e1 :value 1 :recorded '[app.x/f]} {:call-id "c" :intent "p"})]
    (is (= '[app.x/f] (get-in data [:seon.eval/response :recorded])))
    (is (str/includes? (print/record-text data) "app.x/f") "the plain value's text, which the model reads")
    (is (str/includes? (print/record-text rec) "app.x/f") "and the text printed of a value that is no data")
    (is (not (contains? (:seon.eval/response rec) :fns)) "what the tool does not promise stays out")))

(deftest an-unwritten-proof-comes-with-the-shape-of-its-namespace
  ;; owner, 2026-11-05: "useful first": a proof not written is told where it goes, what that namespace defines, and one real
  ;; test of it to copy (vars-history spent turns 10-14 finding these)
  (let [db (d/db-with (db-with-program)
                      (into (program-facts '[[demo-test/shared-world "(defn- shared-world [] 1)" []]])
                            [{:var/name 'demo-test/f-test :var/meta #{:test}} {:var/name 'demo-test/g-test :var/meta #{:test}}
                             {:var/name 'demo-test/shared-world :var/meta #{:arglists}}]))
        shape (status/test-shape db 'demo-test/new-test)]
    (is (= 'demo-test (:ns shape)) "the namespace it goes in")
    (is (empty? (:helpers shape)) "only the helpers its example calls (it calls none)")
    (is (= "(deftest g-test (is (= 1 (g))))" (:example shape)) "one real test of it: the shortest of its first five by name")
    (is (nil? (status/test-shape db 'nowhere-test/x)) "a namespace the branch lacks has no shape: the agent makes it first")
    (let [h (pr-str (seon.render.ui/status-hiccup {} {:proofs [{:test 'demo-test/new-test :status :undefined :shape shape}]}))]
      (is (re-find #"Write " h) "it is told to write it")
      (is (re-find #"deftest g-test" h)))))

(deftest a-turn-passing-adds-no-page-message
  ;; owner, 2026-11-05: what repeatedly changes is the render's failure, not a reason for a message: the turn is data on each eval, whose
  ;; bytes never move, so the page says nothing new as a turn passes (docs/render.md#the-agents-turn)
  (ts/test-branch!
    (fn [_ conn]
      (let [[ask! ev! _] (parts-session conn)
            r0 (ask! 0)
            _ (ev! "e1")
            r1 (ask! 1)
            all (pr-str (:messages r1))]
        (is (not (re-find #"turns" (:content (peek (:messages r0))))) "the page announces no cap")
        (is (not (re-find #"Turn \d" all)) "no message holds a turn number as a line")
        (is (= 2 (count (filter page-call? (:messages r1)))) "and turn 2 adds no page message of its own")
        (is (= (:messages r0) (subvec (:messages r1) 0 (count (:messages r0)))) "the prefix before it is byte-identical")))))

(deftest a-cited-attribute-says-what-it-is-and-shows-a-real-value
  ;; owner, 2026-11-05: "useful first": :var/form followed to a real form entity shows at once that the text is :form/src
  ;; (vars-history spent turns 4-8 finding it out)
  (let [db (d/db-with (db-with-program) [{:schema/key :var/form :schema/form ":seon.db/ref"}
                                         {:schema/key :var/name :schema/form ":symbol"}])
        text (fn [k] (seon.render/part-text ['seon.render.ui/cite {} (seon.render.ui/cite-hiccup db {:seon.cite/depth :doc :seon.cite/target (d/pull db '[*] [:schema/key k])})]))
        t (text :var/form)]
    (is (str/includes? t "An attribute your plan names.") t)
    (is (str/includes? t ":form/src") "a ref followed: what the form entity holds")
    (is (str/includes? (text :var/name) "A real value:") "an attribute that is no ref: a real value")))

(deftest a-cited-schema-that-is-no-attribute-shows-its-form-and-its-renders
  ;; found reading one task's turn-1 request, 2026-11-11: a plan naming :seon.render/process ("a schema naming its renders") was told
  ;; "An attribute your plan names. It holds :any." (the last keyword a regex found in the form), and its agent spent five turns finding
  ;; how a schema declares its render. A key the db stores no attribute for is an entity's schema or a value's: its form, whole, and
  ;; the renders its properties name
  ;; the form as a schema file holds it (the stored :schema/form is that text)
  (let [form "[:map {:seon.render/hiccup seon.render.ui/problems :seon.render/model seon.render.ui/problems-model} [:t :int] [:classes :map]]"
        db (d/db-with (db-with-program) [{:schema/key :seon.problems/report :schema/form form}
                                         {:schema/key :seon.db/agent-fact :schema/form "[:map {:seon.db/attributes true} [:seon.agent/key :seon.agent/key]]"}])
        text (fn [k] (seon.render/part-text ['seon.render.ui/cite {} (seon.render.ui/cite-hiccup db {:seon.cite/depth :doc :seon.cite/target (d/pull db '[*] [:schema/key k])})]))
        t (text :seon.problems/report)]
    (is (not (str/includes? t "An attribute your plan names")) t)
    (is (str/includes? t "A value's schema your plan names") t)
    (is (str/includes? t "seon.render.ui/problems") "the render it names for a page")
    (is (str/includes? t "seon.render.ui/problems-model") "and the one that prints it for the model")
    (is (str/includes? t ":seon.render/hiccup") "its form whole, as the pull holds it")
    (is (str/includes? (text :seon.db/agent-fact) "An entity's schema your plan names") "an entity schema says it is one")))

(deftest proofs-of-one-namespace-show-its-example-once
  ;; docs/render.md#how-the-work-stands: seen 2026-11-06 on a task with four unwritten proofs: its namespace's example four times,
  ;; and every scaffold naming the first proof
  (let [shape {:ns 'demo-test :requires "(:require [x])" :example "(deftest an-example (is true))"}
        proof (fn [t] {:test t :status :undefined :shape shape})
        do-of (fn [t] {:reason :proof-unknown :test t :do (list 'scaffold t)})
        h (pr-str (seon.render.ui/status-hiccup {} {:proofs [(proof 'demo-test/a) (proof 'demo-test/b) {:test 'other-test/c :status :undefined :shape (assoc shape :ns 'other-test)}]
                                                    :refused [(do-of 'demo-test/a) (do-of 'demo-test/b) (do-of 'other-test/c)]}))
        n (fn [s] (count (re-seq (re-pattern (java.util.regex.Pattern/quote s)) h)))]
    (is (= 2 (n "an-example")) "once a namespace")
    (is (= 1 (n "(scaffold demo-test/a)")) "the first proof's scaffold names the first proof")
    (is (= 1 (n "(scaffold other-test/c)")) "the next namespace's its own")
    (is (zero? (n "(scaffold demo-test/b)")) "a later proof of a namespace repeats none of it")
    (is (str/includes? h "demo-test/b") "but says to write it")))

(deftest what-the-model-reads-is-the-same-with-result-entities
  ;; docs/users.md#what-each-thing-is: an eval's value, error and output are its result's, a ref the eval holds; what the model reads of
  ;; it (seon.ctx.wire/tool-text, the text of the request's tool message, and the message itself) is what it read when the response held them
  (let [answers {"e1" {:handle 'result/e1 :value 3 :ms 1.5 :ticks 120 :bytes 2048}
                 "e2" {:handle 'result/e2 :value nil :out (print/code "hi\n") :ns 'user}
                 "e3" {:handle 'result/e3 :ex {:seon.error/type :seon.agent/eval-error :seon.error/operation 'seon.repl/eval-code :seon.error/message "boom"} :ms 0.4}
                 "e4" {:handle 'result/e4 :forms 2 :value [1 {:a "b"}]}}
        expected {"e1" "{:handle result/e1, :value 3, :cost \"1.5 ms · 120 ticks · 2.0 KB · ~9 tok\"}"
                  "e2" "{:handle result/e2, :value nil, :out hi\n, :ns user}"
                  "e3" "{:handle result/e3,\n :ex\n   #:seon.error{:type :seon.agent/eval-error,\n                :operation seon.repl/eval-code,\n                :message \"boom\"}, :cost \"0.40 ms · ~47 tok\"}"
                  "e4" "{:handle result/e4, :forms 2, :value [1 {:a \"b\"}]}"}]
    (ts/test-branch!
      (fn [_ conn]
        (d/transact conn [{:seon.agent/key "a1"}])
        (doseq [[id r] answers]
          (d/transact conn [(into {:seon.eval/id id :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/call-id (str "c-" id)}
                                  (repl/record-details (str "(" id ")") r {}))]))
        (let [db @conn]
          (doseq [[id r] answers
                  :let [e (d/pull db '[*] [:seon.eval/id id])
                        sent (wire/tool-text (seon.db/index) db e)]]
            (is (some? (:seon.eval/result e)) (str id " holds a result entity"))
            (is (= (when (contains? r :value) (:value r)) (:seon.result/value (d/pull db '[*] (get-in e [:seon.eval/result :db/id])))) (str id " its value is the result's"))
            (is (not-any? #(contains? (:seon.eval/response e) %) [:value :ex :out :err :forms]) "and not the response's")
            (is (= (expected id) sent) (str id " reads as it always did"))
            (is (= sent (print/response-text r)) (str id " as its live response prints"))
            (is (= {:role "tool" :tool-call-id id :content sent}
                   (second (seon.fixtures/eval-messages {:db db} e))) (str id " and its tool message is that text"))))))))

;; ---- model renders (stage 2b, docs/users.md#the-stages-agreed-2026-11-09) ----

(defn- model-walk
  "What Web's walk makes of agent a's model render on db: a walker on the agent's pull asked for both outputs, rooted on the
  agent's entity (its schema's renders), flushed once, the root's model output with each placed entity or call resolved
  (seon.render/openai-compat)."
  [db a]
  (let [ix (seon.program/index-of-db db)
        r (seon.ratom/atom (ctx/agent-ctx ix db a))
        w (assoc (render/walker {:ref r :changes ctx/changes} (constantly db) [#'seon.render.ui/agent {} (seon.ratom/ref-view r ctx/changes)] ix)
                 :outputs #{:seon.render/hiccup :seon.render/openai-compat})]
    (try (seon.fixtures/flush! w nil) (render/openai-compat w) (finally (render/dispose-walk! w)))))

(defn- with-system
  "Db with agent key k's :seon.agent/system written as its run's start writes it (seon.agent/begin-run-tx): the system prompt's top section."
  [db k]
  (d/db-with db [{:seon.agent/key k :seon.agent/system [(seon.ref/page-top db doc/system-page)]}]))

(deftest the-model-renders-read-what-todays-request-reads
  ;; stage 2b: the request is the walk's model output, each entity's model render answering the messages it reads, in the order the agent's
  ;; render places them; the walk the layout draws (seon.ctx.wire/render) and the walk rooted on the agent's entity (`model-walk`) make the same messages, and the page
  ;; reaches the model as each turn's page message, never as an eval of seon's
  (doseq [[nm world] {"replies with calls, reasoning and a plan that changes" (fn [conn] (let [[db] (sent-turns conn)] [db "a1"]))
                      "an example session" (fn [conn] (example-world! conn) (turn! conn 0) (turn! conn 1) [@conn "a1"])}]
    (ts/test-branch!
      (fn [_ conn]
        (let [[db0 k] (world conn)
              db (with-system db0 k)
              a (pdb/eid db :seon.agent/key k)
              n (:seon.agent/turn (d/entity db a))
              today (wire/render db {:commit (d/commit-id @conn) :task (:seon.task/id (:seon.agent/task (d/entity db a))) :turn n :max-turns 9 :origin :model :agent k})
              ms (model-walk db a)
              want (:messages today)
              i (count (take-while true? (map = want ms)))
              cut (fn [m] (some-> m (update :content #(subs % 0 (min 300 (count %))))))]
          (is (= want ms) (str nm ": first differing message " i " of " [(count want) (count ms)]
                               "\n want " (pr-str (cut (get want i))) "\n got  " (pr-str (cut (get ms i)))))
          (is (every? #(seon.contract/fits? :seon.model/message %) ms) (str nm ": every one a message"))
          (is (seq (filter page-call? ms)) (str nm ": the page reaches the model as a generated eval")))))))

(deftest an-opened-eval-reads-whole-to-the-model
  ;; owner, 2026-11-09: opening an eval changes what the agent's model reads ("Model reads it too"): its result whole, not budgeted
  (let [[db] (tree-world)
        big (vec (range 40000))
        e {:seon.eval/id "e9" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/code "(range 40000)" :seon.eval/call-id "c9" :seon.eval/intent "Count."
           :seon.eval/response {:handle 'result/e9 :value big}}
        tool (fn [e] (:content (peek (wire/eval-msgs {:seon.db/db db :seon.render/index (seon.program/index-of-db db)} e))))
        closed (tool e) opened (tool (assoc e :seon.eval/opened true))]
    (is (str/includes? opened "39999") "opened: the whole value")
    (is (not (str/includes? closed "39999")) "closed: within its budget")
    (is (< (count closed) (count opened)))))


;; docs/storage.md#collection: a turn's basis past what collection kept names a commit the store no longer holds, so its point is read
;; as of its t (seon.ctx.wire/db-at); the page advanced from it to the next basis (seon.ctx/moved's d/datoms-diff of an as-of value
;; and a commit's) answers the same edits as from the commit itself, never a defect where the diff goes
(deftest a-page-advanced-from-a-collected-basis-answers-its-diff
  (ts/test-branch!
    (fn [_ conn]
      (binding [seon.render-cost-test/*conn* conn]
        (let [[_ ix a p0] (seon.render-cost-test/newest-pair-of 3)
              ;; the agent moved after the newest turn's basis: its REPL's namespace, which its page's turn part shows the model
              ;; (seon.render.ui/turn; the agent's state for the person, agent-state, is the page's alone)
              _ (d/transact conn [{:db/id a :seon.agent/ns 'lab.moved}])
              db @conn p1 {:branch :b :commit (d/commit-id db) :t (:max-tx db)}
              collected (assoc p0 :commit (random-uuid))
              held (wire/page-edits-between db ix a p0 p1)]
          (is (nil? (d/commit-as-db (pdb/store-of db) (:commit collected))) "the store holds no such commit: its point is read as of its t")
          (is (seq held) "the state moved the page")
          (is (= held (wire/page-edits-between db ix a collected p1)) "from the collected basis: the same edits"))))))

(deftest a-new-task-begins-where-the-agent-s-calls-change-task
  ;; docs/tasks.md#a-refine-curates-its-own-task: an agent that goes on with a task needing the one it finished (seon.task/agent-key) reads, at
  ;; the call whose basis names the new task, a message saying the task before ended and which is its task now, before that turn's page;
  ;; drawn from the calls' bases alone, so every later request holds it in the same place
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (doc/load-tx @conn "docs" {doc/system-page (slurp (str (seon.config/checkout) "/" doc/system-page))}))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}}
                                              {:seon.task/ns 'demo :seon.task/name "g-refine" :seon.task/plan "Refine demo/g." :seon.task/needs #{"demo/g"}}]))
      (d/transact conn [{:seon.task/id "demo/g" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/g"}}}])
      (let [turn (fn [n task] (let [b {:commit (d/commit-id @conn) :task task :session "demo/g#1" :turn n :max-turns 9 :origin :model :agent "a1"}]
                                (d/transact conn (conj (ts/turn-tx "a1" n {}) {:seon.model-call/id (seon.lib/call-id "a1" n) :seon.model-call/basis b :seon.model-call/forms 1 :seon.model-call/ms 1}))
                                (wire/render @conn (assoc b :commit (d/commit-id @conn)))))
            r1 (turn 1 "demo/g")
            r2 (turn 2 "demo/g")
            _ (d/transact conn [{:seon.agent/key "a1" :seon.agent/task [:seon.task/id "demo/g-refine"]}])
            r3 (turn 3 "demo/g-refine")
            r4 (turn 4 "demo/g-refine")
            begins (fn [r] (keep-indexed #(when (str/includes? (str (:content %2)) "<task id=\"demo/g-refine\"") %1) (:messages r)))]
        (is (empty? (begins r2)) "one task: no boundary")
        (is (= 1 (count (begins r3))) "the new task's first call: one boundary")
        (is (str/includes? (:content (nth (:messages r3) (first (begins r3)))) "demo/g") "it names the task that ended")
        (is (some #(= "(my.agent/describe @ctx [:seon.agent/task])" (get-in % [:tool-calls 0 :code])) (:messages r3))
            "and the new task is described where the record holds it, its cites' paths and the forms that act on them (docs/agent.md#looking-at-what-you-pulled)")
        (is (= (subvec (:messages r3) 0 (count (:messages r3))) (subvec (:messages r4) 0 (count (:messages r3)))) "the next request starts with this one, boundary included")))))

(defn lab-list
  "A list a test render places, read by the model as a part. See docs/render.md#an-entity-may-name-its-own-render."
  {:seon.render/list "p-lab" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}] [:div {:id "p-lab"} [:p (str "LAB-PART at turn " (get c :seon.agent/turn))]])

(defn lab-view
  "An agent's own render for a test: the agent's page and one list of its own after it. See docs/render.md#an-entity-may-name-its-own-render."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db {:optional true} :seon.host/db]]] :any] :any]}
  [opts c] (if (:seon.render/nested opts) (seon.render.ui/agent opts c) (conj (seon.render.ui/agent opts c) [#'lab-list {}])))

(deftest an-own-renders-list-is-in-the-models-page
  ;; docs/render.md#an-entity-may-name-its-own-render: the page parts the model reads are the lists of the entity's own render (seon.ctx.wire/model-parts),
  ;; so an agent that names a render of its own reads what that render's list draws, in its request, as it reads its task's part
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (doc/load-tx @conn "docs" {doc/system-page (slurp (str (seon.config/checkout) "/" doc/system-page))}))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}}]))
      (d/transact conn [{:seon.task/id "demo/g" :seon.task/agent {:seon.agent/key "a1" :seon.agent/task {:seon.task/id "demo/g"} :seon.render/hiccup 'seon.ctx.wire-test/lab-view}}])
      (let [r0 (turn! conn 0)
            page (apply str (map :content (:messages r0)))
            r1 (turn! conn 1)
            diff (:content (peek (:messages r1)))]
        (is (str/includes? page "LAB-PART at turn 1") "the own render's list is in what the model reads")
        (is (str/includes? page "task-view") "beside the task's part, as before")
        (is (str/includes? diff "LAB-PART at turn 2") "and what it draws next is in the next turn's page edits, as the task's part's are")))))

(deftest a-call-answering-the-request-that-throws-is-in-the-request
  ;; docs/failure-points.md (a component's render): a call placed to answer the model's output (turn-entities places task-msgs where an agent's
  ;; calls change task) that throws answers that output with its error, by the error schema's model render (defect-msgs), naming what threw:
  ;; never left out of what the model reads (Context, 2026-11-11: task-msgs failed its contract and the task boundary vanished with no trace)
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (doc/load-tx @conn "docs" {doc/system-page "# A stub of the system prompt\n\nBe brief."}))
      ;; two turns whose bases name different tasks: turn-entities places task-msgs between them
      (doseq [[n task] [[1 "demo/g"] [2 "demo/h"]]]
        (let [b {:commit (d/commit-id @conn) :task task :turn n :max-turns 9 :origin :model :agent "a1"}]
          (d/transact conn (ts/turn-tx "a1" n {:seon.model-call/basis b :seon.model-call/reply (str "Reply " n ".") :seon.model-call/forms 0 :seon.model-call/ms 1}))))
      (let [db @conn
            ix (seon.db/index)
            c (ctx/agent-ctx ix db (pdb/eid db :seon.agent/key "a1"))
            ;; task-msgs throwing as a contract refusal does (Context's case: its arguments refused), the call the walk places left as it is
            w (with-redefs [wire/task-msgs (fn [_ before id] (throw (ex-info "seon.ctx.wire/task-msgs invalid-input" {:seon.error/type :seon.instrument/contract-error :in [before id]})))]
                (render/draw c ctx/changes db (render/render-var-of ix c) {:model-only true :once true :index ix :outputs #{:seon.render/hiccup :seon.render/openai-compat}}))]
        (try
          (let [said (pr-str (:messages (wire/request w)))]
            (is (str/includes? said "seon.ctx.wire/task-msgs") (str "the model reads which call threw: " said))
            (is (and (str/includes? said "Reply 1.") (str/includes? said "Reply 2.")) "the other parts are still there")
            (is (= [:seon.render/component-defect] (map :seon.error/type (render/take-defects! w))) "one defect, recorded once"))
          (finally (render/dispose-walk! w)))))))

;; docs/render.md#style: presentation goes from the page the model reads, and a map the page shows as data keeps its entries:
;; a map entry [:k {...}] is a vector headed by a keyword with a map second, and was rebuilt as an element, which its map
;; could not take back (seon.ctx-test/an-agent-adds-a-datum-and-its-render, red in context's land, 2026-11-12)
(deftest presentation-leaves-a-maps-entries
  (is (= [:div [:pre {:task {:plan "p"} :opts {} :n 1}]]
         (#'wire/presentation-free [:div {:class "x"} [:pre {:class "y" :data-n 1} {:task {:plan "p"} :opts {} :n 1}]]))))

;; docs/render.md#a-page-is-a-kept-walk: a point is read as the db it names: its own db, its commit when the commit holds its t, else
;; the db as of its t. A world written without commits (d/db-with on a store's db, as a speculated or a test world is) names its
;; store's commit at every point; read as that commit, each past point was the world before any of it, and its page showed nothing
(deftest a-point-is-read-at-its-own-t
  (let [db0 (ts/test-db)
        db1 (d/db-with db0 [{:seon.agent/key "pt-a1"}])
        db2 (d/db-with db1 [{:seon.agent/key "pt-a2"}])
        at (fn [db p] (set (keep #(:seon.agent/key (d/entity db (:e %))) (d/datoms (#'wire/db-at db2 p) :aevt :seon.agent/key))))
        p1 {:commit (d/commit-id db2) :t (:max-tx db1)}
        p2 {:commit (d/commit-id db2) :t (:max-tx db2)}]
    (is (= (d/commit-id db0) (d/commit-id db2)) "the world's points all name its store's commit")
    (is (contains? (at db2 p1) "pt-a1") "a past point holds what was written by its t")
    (is (not (contains? (at db2 p1) "pt-a2")) "and nothing after it")
    (is (contains? (at db2 p2) "pt-a2") "the db's own point is the db")))

;; docs/render.md#how-the-work-stands: an unwritten proof's example is the shortest test that calls what its task cites, where its
;; namespace has one (the T3 rerun drew whole components where its task needed one tile; the 2026-11-12 loop spent twelve turns
;; finding how a REPL test evals in-process, which the tests calling the cited seon.repl/edit! show), else the shortest as before
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-proofs-example-calls-what-its-task-cites
  (let [db (t/with (t/world)
                   (t/code 'app.ex "(ns app.ex)\n(defn tile [] 1)\n(defn other [] 2)")
                   (t/code 'app.ex-test (str "(ns app.ex-test (:require [clojure.test :refer [deftest is]] [app.ex :as ex]))\n"
                                             "(deftest short-test (is (= 2 (ex/other))))\n"
                                             "(deftest the-tile-is-drawn-whole-and-right (is (= 1 (ex/tile))))")))]
    (is (str/includes? (:example (status/test-shape db 'app.ex-test/new-proof #{'app.ex/tile})) "ex/tile") "the test that calls the cited var")
    (is (str/includes? (:example (status/test-shape db 'app.ex-test/new-proof #{'app.ex/nothing})) "short-test") "none calls it: the shortest, as before")
    (is (= (status/test-shape db 'app.ex-test/new-proof) (status/test-shape db 'app.ex-test/new-proof #{})) "no cites: as before")))
