(ns seon.context-test
  "The agent's current-state block says each thing once: a passing proof is its status, a
  red one its source and its failures (expected and actual), a reference already shown as a
  proof is not shown again, a merge task's plan stays its own."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.db :as pdb] [seon.vector :as vector] [seon.task :as task] [seon.context :as context] [seon.print :as print]
            [seon.docs-test] [seon.reader :as reader] [seon.proofs :as proofs] [seon.agent :as agent] [seon.docs :as docs] [seon.ctx :as ctx] [seon.render :as render] [seon.render.ui] [seon.web.html]
            [seon.print.select :as select] [clojure.string :as str] [seon.edn] [seon.print-test :as print-test] [seon.repl :as repl] [seon.model]))

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

(defn- wiki!
  "The checkout's wiki into conn, as root's boot loads it (seon.root/start!): the help page
  every render reads is a page of it."
  [conn] (d/transact conn (docs/load-tx @conn "docs" (docs/pages (seon.config/checkout) "docs"))))

(deftest each-thing-is-said-once
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make `demo/f` double."
                                               :seon.task/instructions "Use `demo/f` and `demo-test/g-test`."
                                               :seon.task/proofs #{'demo-test/f-test 'demo-test/g-test}}]))
        t (task/task db "demo/f")
        block (context/current-state db t {:results {'demo-test/f-test :pass 'demo-test/g-test :fail}
                                           :reds [{:test 'demo-test/g-test :type :fail :message nil :expected "(= 1 (g))" :actual "(not (= 1 0))" :got "(= 1 0)"}]
                                           :turn 0 :max-turns 4})
        by (into {} (map (juxt :test identity)) (:proofs block))]
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
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p"}]))
        t (task/task db "demo/f")]
    (is (re-find #"at the model's token limit" (:cut (:your-last-reply (context/current-state db t {:turn 1 :max-turns 4 :truncated? true})))))
    (is (nil? (:your-last-reply (context/current-state db t {:turn 1 :max-turns 4}))))))

(deftest code-run-again-with-the-same-answer-says-so-on-it
  ;; the live runs of 2026-10-25/26: an answer to code sent again printed as the form that reads
  ;; the earlier one, `:out (:out result/e89)`, and the agent sent it again four times; the earlier
  ;; one was sent whole each time. Sent again with the same answer, it prints whole beside that
  ;; one's handle; a different answer is new, so it is not marked
  (let [outs {"e1" "---- budget 16\n[:kw …]\n" "e2" "---- budget 16\n[:kw …]\n" "e3" "other\n"}
        db (d/db-with (db-with-program) (for [[id out] outs]
                                          {:seon.eval/id id :seon.eval/by "a1"
                                           :seon.eval/record {:code "(probe)" :call-id (str "c" id) :call 0
                                                              :response {:handle (symbol "result" id) :value nil :out out :ms 1}}}))
        ev (fn [id] [[:eval id] (d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] [:seon.eval/id id]) :seon.db/eval-fact 1])
        c (#'context/printed-run (mapv ev ["e1" "e2" "e3"]) nil)
        text (fn [id] (:content (peek (get-in c [:parts id :whole]))))]
    (is (= [:eval "e2" {:same-as 'result/e1}] (get-in c [:parts "e2" :ref])) "its ref names the earlier one")
    (is (str/includes? (text "e2") ":same-as result/e1") (text "e2"))
    (is (str/includes? (text "e2") "---- budget 16") "and its answer prints whole, never as (:out result/e1)")
    (is (= [:eval "e3"] (get-in c [:parts "e3" :ref])) "a different answer to the same code is not marked")
    (is (= (get-in c [:parts "e2" :whole]) (second (first (context/parts-of db [[:eval "e2" {:same-as 'result/e1}]]))))
        "read back from its ref")))

(defn- task-f [db] (task/task (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p"
                                                                  :seon.task/proofs #{'demo-test/f-test}}]))
                              "demo/f"))

(deftest a-reason-and-a-run-error-reach-the-context
  (let [db (db-with-program)
        t (task-f db)
        ;; the done rule refused beyond the task's own proof: a regression, a stray red, a
        ;; contract gap; the run named one failing assertion of each red test
        reasons [{:reason :proof-red :symbol 'demo-test/f-test} {:reason :regression :symbol 'demo-test/g-test}
                  {:reason :stray-red :symbol 'user/scratch-test} {:reason :contract-gap :symbol 'demo/helper}]
        reds [{:test 'demo-test/g-test :type :fail :expected "(= 1 (g))" :actual "(not (= 1 0))" :got "(= 1 0)"}]
        block (context/current-state db t {:results {'demo-test/f-test :fail 'demo-test/g-test :fail} :reds reds
                                           :reasons reasons :turn 1 :max-turns 4
                                           :test-run-errors ["namespace app-test did not load: Unable to resolve symbol: g"]})]
    (testing "every reason but the task's own red proof (shown with the proofs), each with what explains it"
      (is (= [{:reason :regression :test 'demo-test/g-test :failures [{:assert (print/code "(= 1 (g))") :got (print/code "(= 1 0)")}]}
              {:reason :stray-red :test 'user/scratch-test :do '(my.task/update! :seon.task/proofs conj (quote user/scratch-test))}
              {:reason :contract-gap :vars ['demo/helper]}]
             (:refused block))))
    (testing "a run that failed as a whole is said, not read as tests not run"
      (is (= {:failed ["namespace app-test did not load: Unable to resolve symbol: g"]} (:test-run block)))
      (is (= ["namespace app-test did not load: Unable to resolve symbol: g" "test process died: 9"]
             (proofs/test-run-errors {:reports [{:type :error :message "namespace app-test did not load: Unable to resolve symbol: g"}
                                           {:type :fail :var "#'demo-test/f-test" :message "x"}
                                           {:type :error :var nil :message "test process died: 9"}]}))))
    (testing "printed, as the page's status and (my.task/status) print it, they are there"
      (let [text (print/text (vary-meta (dissoc block :write-set :to-prove :task) assoc :seon.print/full true))]
        (is (re-find #":regression" text))
        (is (re-find #"did not load" text))))
    (testing "a design step that has not improved its plan is told what it lacks and the call that does it"
      (let [b (context/current-state db t {:results {} :reasons [{:reason :no-design}] :turn 1 :max-turns 4})]
        (is (= [[:no-design :seon.task/plan 'my.task/update!]]
               (mapv (juxt :reason :unchanged (comp first :do)) (:refused b))))))
    (testing "a task with no proofs is told the form that registers one (a design writes its own)"
      (let [b (context/current-state db t {:results {} :reasons [{:reason :no-proofs}] :turn 1 :max-turns 4})]
        (is (= ['my.task/update! :seon.task/proofs] (take 2 (:do (first (:refused b))))))))
    (testing "a stray red is told the form that makes it a proof of the task"
      (let [b (context/current-state db t {:results {'user/scratch-test :fail} :reasons [{:reason :stray-red :symbol 'user/scratch-test}] :turn 1 :max-turns 4})]
        (is (= ['(my.task/update! :seon.task/proofs conj (quote user/scratch-test))]
               (keep :do (filter #(= :stray-red (:reason %)) (:refused b)))))))
    (testing "a stray red the branch does not define is one of main's tests it removed: marked so, and no form makes it a proof"
      (let [b (context/current-state db t {:results {'demo-test/gone :fail} :reasons [{:reason :stray-red :symbol 'demo-test/gone}] :turn 1 :max-turns 4})]
        (is (= [{:reason :stray-red :test 'demo-test/gone :removed true}] (:refused b)))))
    (testing "nothing refused, nothing failed: no section"
      (let [b (context/current-state db t {:results {'demo-test/f-test :pass} :reasons [] :turn 1 :max-turns 4 :test-run-errors []})]
        (is (not-any? #(contains? b %) [:refused :test-run :flagged :your-last-reply]))))))

(deftest a-repair-is-shown-next-turn
  (let [db (db-with-program)
        t (task-f db)
        reply "Here is f.\n```clojure\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)))\n```"
        {:keys [text repairs]} (reader/forgive reply)
        block (context/current-state db t {:turn 1 :max-turns 4 :source text :repairs repairs})]
    (is (seq repairs) "the reader repaired the extra close")
    (is (= [{:near {:line 3 :column 60 :text "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)),"} :dropped ")" :inserted ","}]
           (:repaired (:your-last-reply block)))
        "what was dropped, what replaced it, and where: its line and column, and the whole line; the prose made comments is no repair")))

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
                                     [ctx-calc-test/empty-test "(deftest empty-test (square 3))" [ctx-calc/square] {:var/kind :test}]])))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'ctx :seon.task/name "sq" :seon.task/plan "p"
                                               :seon.task/proofs #{'ctx-calc-test/square-test 'ctx-calc-test/cube-test 'ctx-calc-test/empty-test}}]))
        ;; the test child's report of the failing assertion, as clojure.test makes it
        rep (agent/test-report {:type :fail :expected '(= 9 (square 3)) :actual '(not (= 9 6))} "#'ctx-calc-test/square-test")
        block (context/current-state db (task/task db "ctx/sq")
                                     {:results {'ctx-calc-test/square-test :fail} :turn 1 :max-turns 4
                                      :reds (proofs/red-reports {:reports [rep]} #{'ctx-calc-test/square-test})})
        by (into {} (map (juxt :test identity)) (:proofs block))]
    (is (= [{:assert (print/code "(= 9 (square 3))") :got (print/code "(= 9 6)")}] (:failures (by 'ctx-calc-test/square-test)))
        "the assertion once, and what it evaluated to")
    (is (= [{:var 'ctx-calc/square :contract [:=> [:cat :int] :int] :doc {:seon.print/prose "Squares x."}}
            {:var 'ctx-ghost/half :contract [:=> [:cat :int] :int]}]
           (:calls (by 'ctx-calc-test/square-test)))
        "the program functions the red proof calls, at summary level from their facts: one in a namespace never loaded too")
    (is (= {:test 'ctx-calc-test/cube-test :status :undefined} (by 'ctx-calc-test/cube-test))
        "a proof not written yet says so")
    (is (= :unknown (:status (by 'ctx-calc-test/empty-test))) "a proof no report named never ran: the judge's :proof-unknown")
    (let [b (context/current-state db (task/task db "ctx/sq") {:results {} :turn 1 :max-turns 4 :test-run-errors ["namespace app-test did not load"]})]
      (is (= {'ctx-calc-test/square-test :not-run 'ctx-calc-test/empty-test :not-run 'ctx-calc-test/cube-test :undefined}
             (into {} (map (juxt :test :status)) (:proofs b)))
          "when the run failed as a whole, a written proof did not run"))))

(deftest an-unresolved-citation-says-what-to-do
  ;; task seon.task/a-task-cites-what-it-needs: a name the plan cites that resolves to
  ;; nothing tells the agent what that means and what to do, not only that it is unresolved
  (let [r (seon.docs/resolve-ref (db-with-program) 'demo/nowhere :doc)]
    (is (:unresolved r))
    (is (string? (:problem r)) "a message beside the name")))

(def ^:private wiki-page
  "# Flow\n\nFlow runs procs.\n\n## Step\n\nA step returns its state and outputs. It never blocks.\n\n## Stop\n\nStop awaits each proc's exit. Then the graph is gone.\n")

(deftest a-task-context-pulls-what-its-plan-cites-at-that-depth
  ;; task seon.docs/a-task-cites-what-it-needs: a section the plan cites reaches the
  ;; context at the depth its delimiter asks for, read from the sections main holds
  (let [db (db-with-program)
        db (d/db-with db (docs/load-tx db "docs" {"docs/flow.md" wiki-page}))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f"
                                               :seon.task/plan "Read `docs/flow.md#step`, and ```docs/flow.md#stop``` whole."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        block (context/current-state db (task/task db "demo/f") {:results {} :reds [] :turn 0 :max-turns 4})
        shown (into {} (map (juxt :seon.ref/address identity)) (:sections block))]
    (is (= "A step returns its state and outputs. It never blocks."
           (:seon.ref/lead (shown "docs/flow.md#step"))) "doc depth: the section's lead")
    (is (clojure.string/includes? (str (shown "docs/flow.md#stop")) "Then the graph is gone.") "code depth: the section whole")))

(deftest a-cited-file-is-read-on-demand
  ;; task seon.ref/files-namespaces-and-symbols-are-plain-markdown (owner, 2026-10-21): a
  ;; cited file outside the stored wiki is read and shown when the context is built, never
  ;; stored; three backticks show it whole
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f"
                                               :seon.task/plan "Read ```bin/env``` first."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        block (context/current-state db (task/task db "demo/f") {:results {} :reds [] :turn 0 :max-turns 4})]
    (is (clojure.string/includes? (str block) "export SEON_CHECKOUT") "the file's text, read on demand")
    (is (empty? (d/q '[:find ?e :where [?e :seon.section/address ?a] [(clojure.string/starts-with? ?a "bin/")]] db))
        "and nothing stored for it")))

(deftest a-cited-namespace-and-a-reference-code-file-show-what-they-are
  ;; task seon.ref/files-namespaces-and-symbols-are-plain-markdown: a namespace shows its
  ;; docstring and publics, a reference-code file the revision main's source pins it at (the
  ;; gitlink root's import recorded: a fact, never git run per render)
  (let [pinned (apply str (repeat 40 "a"))
        db (d/db-with (db-with-program) [{:db/id "ns" :ns/name 'demo.calc :ns/doc "Adds. More."} {:var/name 'demo.calc/add :var/ns "ns"}
                                         {:seon.root/id :root :seon.root/source {:root "tree" :files 0 :forms 0 :ms 0.0 :pins {"reference-code/malli" pinned}}}])
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "n"
                                               :seon.task/plan "Read `demo.calc` then `reference-code/malli/README.md` and `docs/nowhere.md`."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        block (context/current-state db (task/task db "demo/n") {:results {} :reds [] :turn 0 :max-turns 4})
        by (into {} (map (juxt :seon.ref/address identity)) (:sections block))]
    (is (= pinned (:seon.ref/pin (first (filter #(clojure.string/starts-with? (:seon.ref/address %) "reference-code/malli/README.md") (:sections block)))))
        "the pin main's source fact records")
    (is (= {:seon.ref/address "demo.calc" :seon.ref/heading "demo.calc" :seon.ref/lead "Adds. More."
            :seon.ref/below [{:seon.ref/address "demo.calc/add" :seon.ref/heading "add"}] :seon.ref/depth :doc}
           (by "demo.calc")) "a namespace: its docstring and publics")
    (is (:unresolved (by "docs/nowhere.md")) "a file that is not there says so")
    ;; root, 2026-10-25 (the a-map design read a namespace one (source f) a turn; its bug was in
    ;; privates ns-publics never named): every definition, privates too, in file order, a line each
    (let [db (d/db-with db [{:db/id "f1" :form/id "demo.calc/zz" :form/order 0} {:db/id "f2" :form/id "demo.calc/add" :form/order 1}
                            {:var/name 'demo.calc/zz :var/ns [:ns/name 'demo.calc] :var/form "f1" :var/doc "A private helper. Its rest."}
                            {:var/name 'demo.calc/add :var/form "f2" :var/doc "Adds two."}])
          text (:seon.print/code (seon.docs/cited-code db {:seon.ref/name 'demo.calc :seon.ref/depth :doc :seon.ref/line 1}))]
      (is (= ["(ns demo.calc" "  \"Adds. More.\")" ";; zz: A private helper." ";; add: Adds two."] (str/split-lines text))
          "the namespace head, then each definition by its place in the file: its name and its doc's first sentence"))))

(deftest an-agent-reads-its-conversation-so-far
  ;; a live DeepSeek agent re-read the same Datahike source every other turn because each
  ;; context showed only the previous turn's evaluations (2026-10-21); a REPL keeps its
  ;; scrollback. Each eval renders its own part (seon.context/eval-messages): the assistant message with
  ;; the call, then the response as a tool message; the provider's format folds a reply's calls
  ;; into one assistant message (notes/archive/design-agent-view-2026-10-24.md §3, the request is a render)
  (let [ev (fn [id call prose value] {:seon.eval/id id :seon.eval/by "a1" :seon.eval/record (cond-> {:code (str "(" id ")") :response {:handle (symbol "result" id) :value value} :call-id (str "c-" id) :call call} prose (assoc :prose prose))})
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
           (context/eval-messages {:seon.eval/by "user" :seon.eval/record {:code "(+ 1 2)" :response {:handle 'result/e4 :value 3}}} {}))))
  (testing "the history budget sends the newest whole, then older ones whole, then collapsed, never starting inside a reply"
    (let [part (fn [whole small call] {:cost [whole small] :call call})
          ;; tokens whole and collapsed, oldest first; the third continues the second's reply
          parts [(part 400 5 0) (part 10 2 0) (part 10 2 1) (part 10 2 0)]]
      (is (= [[:whole :whole :whole] 0] (#'context/kept (subvec parts 1) 30)) "all whole when they fit")
      (is (= [[:line :whole :whole :whole] 0] (#'context/kept parts 35)) "an older one too large whole is collapsed, not dropped")
      (is (= [[:whole] 3] (#'context/kept parts 0)) "the newest stays at budget 0")
      (is (= [[:whole] 1] (#'context/kept (subvec parts 2) 10)) "a cut never starts inside a reply")
      (testing "the cuts only advance, in jumps to half the budget, so the prefix holds between them"
        (let [many (vec (repeat 40 (part 10 2 0)))
              ;; what each length of the history sends, as [first-sent picks]
              at (fn [k] (let [[picks dropped] (#'context/kept (subvec many 0 k) 130)] [dropped picks]))
              moved (vec (for [k (range 2 41) :let [[d0 p0] (at (dec k)) [d1 p1] (at k)]
                               ;; the earlier request's parts, as this one sends them
                               :when (not (and (= d0 d1) (= p0 (subvec p1 0 (count p0)))))] k))]
          (is (= [(into (vec (repeat 2 :line)) (repeat 6 :whole)) 6] (#'context/kept (subvec many 0 14) 130))
              "past the budget at 14 whole: the oldest collapse until the whole fit in half (6 of 10), then the oldest collapsed are left out until all fits in half")
          (is (= [14 21 28 35] moved) "the cuts move at four lengths of 39, every 7th; every other request is the one before and what was added")
          (is (every? (fn [k] (let [[_ p] (at k)] (<= (+ (* 2 (count (filter #{:line} p))) (* 10 (count (filter #{:whole} p)))) 130))) (range 1 41))
              "and what is sent stays within the budget"))))))

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

(defn- turn-of
  "A recorded model turn n of run demo/f#1 with basis b: one call and its answer."
  [b n]
  {:seon.turn/id (str "demo/f#1/" n) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/n n :seon.turn/origin :model
   :seon.turn/basis b :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 1
   :seon.turn/reply (str "reply " n) :seon.turn/calls [{:id (str "call-" n) :code (str "(+ " n " 1)") :evals (str (inc n))}]})

(deftest the-prefix-stays-byte-identical-across-turns
  ;; task seon.context/a-stable-prefix-is-sent-once: a provider caches a byte-identical message
  ;; prefix, so each request starts with the whole of the one before, even after the program the
  ;; task cites, or the task's plan, changed: the page is read whole at the session's first turn
  ;; and as what changed of it at each later one, by part key (notes/archive/one-derivation-design-rev42.md
  ;; §7), each re-derived from the branch at that turn's commit
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (program-facts '[[demo/f "(defn f \"Doubles.\" [x] (* 2 x))" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make ```demo/f``` triple."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:seon.run/id "demo/f#1" :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key "b/a1"}}])
      (let [observed {:results {'demo-test/f-test :fail} :test-run-errors [] :reds [] :reasons [{:reason :proof-red :symbol 'demo-test/f-test}]}
            call! (fn [n] (d/transact conn [{:seon.eval/id (str "e" n) :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key "b/a1"}
                                             :seon.eval/record {:code (str "(+ " n " 1)") :response {:handle (symbol "result" (str "e" n)) :value (inc n)}
                                                                :run "demo/f#1" :call-id (str "c" n) :call 0 :prose (str "Step " n ".")}}]))
            ;; as seon.turn goes: the turn recorded, its request made on the db that holds it, what it sent added
            request (fn [n] (let [b {:commit (d/commit-id @conn) :task "demo/f" :run "demo/f#1" :turn n :max-turns 4 :origin :model :observed observed :agent "b/a1"}
                                  _ (d/transact conn [(turn-of b n)])
                                  {:keys [messages sent]} (context/render @conn b)]
                              (d/transact conn [{:seon.turn/id (str "demo/f#1/" n) :seon.turn/sent sent}])
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
        (is (every? #(re-find % (:content (second r0))) [#"\{:page" #"cited-\d+-demo_2Ff" #"Doubles\." #"status-\d+" #"1 of 4"]) "the page whole, by component key: the turn's status too")
        (is (re-find #"cited-\d+-demo_2Ff" (:content (peek r1))) "the cited var's edit: its component, by its key")
        (is (str/includes? (:content (peek r1)) "Triples."))
        (is (not (re-find #"task-data-\d+" (:content (peek r1)))) "a component that did not change is not told again")
        (is (str/includes? (:content (peek r1)) "2 of 4") "the turn's status, what changed of it")
        (is (str/includes? (:content (peek r2)) "quadruple") "the plan changed mid-run: the task's part in the next diff")
        (let [db @conn
              read (:messages (context/render db {:commit (d/commit-id db) :task "demo/f" :run "demo/f#1" :turn 2 :max-turns 1
                                                  :origin :outside :observed observed :agent "b/a1"}))]
          (is (= ["system" "user"] (mapv :role read)) "an outside agent's read: the system prompt and the page whole")
          (is (str/includes? (:content (peek read)) "quadruple")))))))

(deftest each-section-is-told-once-on-the-page
  ;; the old request sent the task, its references and its sections in the first turn's context
  ;; and again in the current block, every turn; on the page each is one part: the plan in
  ;; [:task], a cited var's source in its own part, the proofs' statuses in [:status]
  (ts/test-branch!
    (fn [_ conn]
      ;; its proof written: an unwritten one is told what to assert in the plan's words, the plan again
      (d/transact conn (program-facts '[[demo/g "(defn g \"Halves.\" [x] (/ x 2))" []] [demo-test/f-test "(deftest f-test (is (= 1 (g 2))))" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make ```demo/g``` exact."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:seon.run/id "demo/f#1" :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key "b/a1"}}
                        {:seon.turn/id "demo/f#1/0" :seon.turn/run [:seon.run/id "demo/f#1"] :seon.turn/n 0 :seon.turn/origin :model
                         :seon.turn/basis {:commit (d/commit-id @conn) :task "demo/f" :run "demo/f#1" :turn 0 :max-turns 4 :origin :model
                                           :observed {:results {'demo-test/f-test :fail} :reds [] :reasons []}}}])
      (let [db @conn
            a (pdb/eid db :seon.agent/key "b/a1")
            page (context/page db (ctx/build db a :b))
            text (print/text (vary-meta page assoc :seon.print/full true))
            times (fn [s] (count (re-seq (re-pattern (java.util.regex.Pattern/quote s)) text)))]
        (is (some #(str/starts-with? % "status-") (keys page)) "the status is on the page")
        (is (= 1 (times "Make ```demo/g``` exact.")) "the plan once")
        (is (= 1 (times "(defn g \"Halves.\"")) "a cited source once, as code: its quotes as written")
        (is (= 1 (count (re-seq #":test demo-test/f-test,\s+:status :fail" text))) "the proofs' statuses once, in the status")))))

(deftest a-cited-var-shows-at-its-citations-depth
  ;; task seon.context/a-cited-var-shows-at-its-citation-depth: the delimiter picks the depth
  ;; for a var as it does for a section (docs/references.md#the-delimiter-picks-the-depth)
  ;; the vars are the db's alone (no namespace of them in this process): what each shows is read
  ;; from its facts (seon.docs/resolve-ref)
  (let [db (d/db-with (db-with-program)
                      (program-facts '[[ctx-depth/sq "(defn sq \"Squares x. Used by area.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))" []]
                                       [ctx-depth/cube "(defn cube \"Cubes x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x x))" []]
                                       [ctx-depth/tri "(defn tri \"Triples x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))" []]]))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "d"
                                               :seon.task/plan "Build on ctx-depth/sq, `ctx-depth/cube` and ```ctx-depth/tri```."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        ;; the rule lives in docs/resolve-ref (the task's data shows its write set's vars whole: their source)
        by (into {} (for [r (seon.ref/references db (:seon.task/plan (task/task db "demo/d")))]
                      [(:seon.ref/name r) (seon.docs/resolve-ref db (:seon.ref/name r) (:seon.ref/depth r))]))]
    (is (= {:symbol 'ctx-depth/sq :arglists '([x]) :summary "Squares x."} (by 'ctx-depth/sq)) "bare: arglists and the first sentence")
    (is (= {:symbol 'ctx-depth/cube :arglists '([x]) :doc {:seon.print/prose "Cubes x."} :contract [:=> [:cat :int] :int]} (by 'ctx-depth/cube))
        "one backtick: arglists, doc and contract")
    (is (= {:symbol 'ctx-depth/tri :source (print/code "(defn tri \"Triples x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))")} (by 'ctx-depth/tri))
        "three backticks: the source, as code")))

(deftest a-long-source-is-cut-by-the-printer-where-it-is-shown
  ;; the clipping audit (2026-10-25, P4) and the owner, 2026-10-26: "we don't need to and
  ;; shouldn't be printing thousands of items anywhere. The value printer is made to show as much
  ;; of the structure that is possible and to have ellipses for data that just continues so the
  ;; agent can know what's there and can dig into it". A cited source is whole in its data, never
  ;; swapped for its doc by size; its part returns it as a call of the printer with its budget and
  ;; the form that reads it ([#'seon.render/html opts value]), and the printer prints that call's
  ;; value at that budget for the model as the page's writer does for the page: the same cut,
  ;; naming the form that reads the rest
  (binding [*ns* *ns*]
    (eval '(ns ctx-big))
    (eval '(defn big "Does much." {:malli/schema [:=> [:cat] :int]} [] 1)))
  (let [src (str "(defn big \"Does much.\" [] " (str/join " " (repeat 8000 "(inc 1)")) ")")
        db (d/db-with (db-with-program) (program-facts [['ctx-big/big src []]]))
        r (seon.docs/resolve-ref db 'ctx-big/big :code)
        ;; the task's record as ctx holds it: its newest run's task citing the var, with its form
        c {:runs {1 {:task {:cited {2 {:var/name 'ctx-big/big :form {:form/src src}}}}}}}
        hiccup (seon.render.ui/cited {:seon.ctx/ctx c :seon.db/db db} 0 'ctx-big/big)
        html (seon.web.html/html hiccup)
        model (print/text (vary-meta hiccup assoc :seon.print/full true))]
    (is (= {:symbol 'ctx-big/big :source (print/code src)} r) "the source whole as code, never swapped for its doc by size")
    (is (< (:context seon.print/budgets) (select/estimate src)) "a source over the context's budget")
    (is (< (count html) (count src)) "the page cuts it where it is written")
    (is (str/includes? html "seon.render.ui/cited-of") "its cut names the form that reads the rest")
    (is (< (select/estimate model) (select/estimate src)) (str "the model reads it cut too, at the call's budget: " (select/estimate model) " tokens"))
    (is (str/includes? model "#'seon.render/html") "the call as returned")
    (is (str/includes? model "seon.render.ui/cited-of") "and the cut names the form that reads the rest")
    (is (= "[#'seon.context/cited 1 {:a 1}]" (print/text [#'seon.context/cited 1 {:a 1}])) "a vector headed by a var with no budget is data")))

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
        refs (:write-set (context/current-state db (task/task db "demo/u") {:turn 0 :max-turns 4}))]
    (is (= ['demo/f] (keep :symbol refs)) "the resolved name as itself")
    (is (= {:unresolved ['demo/x 'demo/y] :problem (:unresolved seon.ref/problem-messages)} (last refs)) "the others, one problem")))

(deftest an-owners-message-reaches-the-next-turn
  ;; owner, 2026-10-23 (parity row 73): the page's message bar sends the owner's words through
  ;; root's delivery; the turn that takes them writes the message fact on its branch, so the
  ;; conversation holds it in tx order (seon.context/records), rendered by the message fact's
  ;; own messages (seon.context/eval-messages; notes/archive/design-agent-view-2026-10-24.md §3)
  (let [[m] (context/message-messages {:seon.message/from "owner" :seon.message/body "use demo/g, not a new helper"} {})]
    (is (= "user" (:role m)))
    (is (str/includes? (:content m) "use demo/g, not a new helper"))
    (is (str/includes? (:content m) ":from \"owner\"") "who sent it"))
  (is (not (contains? (context/current-state (db-with-program) (task-f (db-with-program)) {:turn 1 :max-turns 4}) :messages)) "never in the block"))
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
  ;; (a 400 otherwise, measured: notes/step0/no-replay.edn): the reply's first eval carries it,
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
  (let [db (db-with-program)
        b (context/current-state db (task-f db) {:results {} :reasons [{:reason :proof-unknown :symbol 'demo-test/f-test}] :turn 1 :max-turns 4})
        r (first (:refused b))]
    (is (= :proof-unknown (:reason r)))
    (is (= 'demo-test/f-test (:test r)))
    (is (re-find #"deftest f-test" (pr-str (:do r))) (pr-str r))
    (is (re-find #"run-test" (pr-str (:do r))) (pr-str r))))

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
        block (context/current-state db (task/task db "demo/f") {:results {} :reds [] :turn 0 :max-turns 4})
        shown (into {} (map (juxt :seon.ref/address identity)) (:sections block))]
    (is (= "G is defined first. Then f calls it." (:seon.ref/lead (shown "demo.other/g"))) "doc depth: the task's plan")
    (is (= [] (:write-set block)) "and not a var of the write set, nor an unresolved name")))


(deftest a-taken-message-is-read-from-its-fact
  ;; root's ruling (2026-10-23): a message is a fact. Root writes the owner's on main; the turn
  ;; that takes it writes the same fact on its branch and names it in its basis by id
  (let [db (d/db-with (db-with-program) [{:seon.message/id "m-1" :seon.message/from "owner" :seon.message/to {:seon.run/id "demo/f#1"}
                                          :seon.message/body "use demo/g, not a new helper"}])]
    (is (= [{:id "m-1" :from "owner" :body "use demo/g, not a new helper"}] (context/taken-messages db {:message-ids ["m-1"]})))))

(defn- read-back
  "What turn entity e on db sent, read back from its records (the refs it recorded, each by its own
  render: seon.context/parts-of), as one vector of messages."
  [db e] (into [] (mapcat second) (context/parts-of db (:seon.turn/sent (d/pull db [:seon.turn/sent] e)))))

(defn- sent-turns
  "A run of two model turns as seon.turn records them (owner's ruling 2026-10-24: each turn
  records what it sent as refs, :seon.turn/sent), with the task's plan changed after turn 0 and
  again after turn 1. Answers [db [r0 r1] [e0 e1]]: the requests' messages as sent, and the turns."
  [conn]
  (wiki! conn)
  (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
  (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                           :seon.task/proofs #{'demo-test/f-test}}]))
  (d/transact conn [{:seon.run/id "demo/f#1" :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key "b/a1"}}])
  (let [observed {:results {'demo-test/f-test :fail} :test-run-errors [] :reds [] :reasons []}
        ev! (fn [id by code value & [more]] (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "b/a1"}
                                                               :seon.eval/record (merge {:code code :response {:handle (symbol "result" id) :value value} :run "demo/f#1"} more)}]))
        turn! (fn [n more]
                (let [b {:commit (d/commit-id @conn) :task "demo/f" :run "demo/f#1" :turn n :max-turns 4
                         :origin :model :observed observed :agent "b/a1"}
                      _ (d/transact conn [(merge {:seon.turn/id (str "demo/f#1/" n) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/n n
                                                  :seon.turn/origin :model :seon.turn/basis b :seon.turn/forms 1
                                                  :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 1}
                                                 more)])
                      {:keys [messages sent]} (context/render @conn b)]
                  (d/transact conn [{:seon.turn/id (str "demo/f#1/" n) :seon.turn/sent sent}])
                  messages))
        plan! (fn [p] (d/transact conn [{:seon.task/id "demo/f" :seon.task/plan p}]))
        r0 (turn! 0 {})
        _ (ev! "e2" "a1" "(f 1)" 1 {:call-id "call-0" :call 0 :prose "I call f." :reasoning "why"})
        _ (plan! "Make f triple.")
        r1 (turn! 1 {})
        _ (plan! "Later.")
        ;; and the system prompt changes: a turn's system prompt is its section as of the turn's t
        _ (d/transact conn [{:db/id (seon.docs/page-top @conn context/system-page) :seon.section/text "Changed after the turns."}])
        db @conn
        e-of (fn [id] (d/q '[:find ?e . :in $ ?id :where [?e :seon.turn/id ?id]] db id))]
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
            run! (fn [run agent] (d/transact conn [{:seon.run/id run :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key agent}}]))
            _ (run! "demo/f#1" "b/a1")
            _ (ev! "e1" "b/a1" "demo/f#1" "(f 1)")
            _ (d/transact conn (task/message-tx "m1" :message "user" "demo/f#1" "use g"))
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
            run! (fn [run task] (d/transact conn [{:seon.run/id run :seon.run/task [:seon.task/id task] :seon.run/agent {:seon.agent/key "b/a1"}}]))
            basis (fn [task run] {:commit (d/commit-id @conn) :task task :run run :turn 0 :max-turns 9 :origin :model
                                  :observed {:results {} :test-run-errors [] :reds [] :reasons []} :agent "b/a1"})
            ;; as seon.turn goes: the turn recorded, then its request on the db that holds it
            turn! (fn [run b] (d/transact conn [{:seon.turn/id (str run "/0") :seon.turn/run {:seon.run/id run} :seon.turn/n 0 :seon.turn/origin :model
                                                 :seon.turn/basis b :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 1}])
                    (let [r (context/render @conn b)] (d/transact conn [{:seon.turn/id (str run "/0") :seon.turn/sent (:sent r)}]) r))
            _ (run! "demo/d#1" "demo/d")
            _ (ev! "e0" "a1" "demo/d#1")
            b0 (basis "demo/d" "demo/d#1")
            first-stage (turn! "demo/d#1" b0)
            boundary "demo/d is done. Your task now is demo/i, in this same session: what you did so far stays above.\n\nIts plan: Implement d."
            _ (run! "demo/i#1" "demo/i")
            _ (d/transact conn (task/message-tx "m1" :stage "root" "demo/i#1" boundary))
            second-stage (turn! "demo/i#1" (basis "demo/i" "demo/i#1"))
            ms0 (:messages first-stage) ms1 (:messages second-stage)
            refs (mapv #(vec (take 2 %)) (:sent second-stage))
            at (fn [ref] (.indexOf refs ref))]
        (is (= ms0 (subvec ms1 0 (min (count ms1) (count ms0)))) "the first stage's request is the second's exact prefix")
        (is (= (:sent first-stage) (subvec (:sent second-stage) 0 (count (:sent first-stage)))) "and records it sent so")
        (is (< (dec (count (:sent first-stage))) (at [:message "m1"]) (dec (count refs))) "then the boundary, root's message, before the new stage's first page")
        (is (= :page (first (peek (:sent second-stage)))) "the new stage's page: what changed since the first stage's turn")
        (is (some #(str/includes? (str (:content %)) "Your task now is demo/i") (drop (count ms0) ms1)) "the boundary says the new task")))))

(deftest a-run-printed-once-sends-what-a-cold-read-sends
  ;; the branch review (2026-10-25, F6, F7): a request threaded through what the run's earlier
  ;; requests printed (:printed) equals one made with none, turn by turn; what each turn recorded
  ;; it sent reads back as the same bytes; and a value folded to an earlier record's form is folded
  ;; only while that record is sent whole
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g."
                                               :seon.task/proofs #{'demo-test/g-test}}]))
      (d/transact conn [{:seon.run/id "demo/g#1" :seon.run/task [:seon.task/id "demo/g"] :seon.run/agent {:seon.agent/key "b/a1"}}])
      (let [rows (vec (for [i (range 40)] {:row i :name (str "row-" i)}))
            ev! (fn [id by value & [more]]
                  (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "b/a1"}
                                     :seon.eval/record (merge {:code (str "(" id ")") :response {:handle (symbol "result" id) :value value}
                                                               :line (str id) :run "demo/g#1"} more)}]))
            basis (fn [n] {:commit (d/commit-id @conn) :task "demo/g" :run "demo/g#1" :turn n :max-turns 9
                           :origin :model :observed {:results {} :test-run-errors [] :reds [] :reasons []} :agent "b/a1"})
            turns (loop [n 0 printed nil out []]
                    (if (= n 5)
                      out
                      ;; as a turn goes: its request, then its call's eval
                      (do (let [b (basis n)
                                _ (d/transact conn [{:seon.turn/id (str "demo/g#1/" n) :seon.turn/run {:seon.run/id "demo/g#1"} :seon.turn/n n
                                                     :seon.turn/origin :model :seon.turn/basis b
                                                     :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 1}])
                                warm (context/render @conn b {:printed printed})
                                cold (context/render @conn b)]
                            (d/transact conn [{:seon.turn/id (str "demo/g#1/" n) :seon.turn/sent (:sent warm)}])
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
          (let [e (d/q '[:find ?e . :in $ ?id :where [?e :seon.turn/id ?id]] db (str "demo/g#1/" n))]
            (is (= (:messages warm) (into [] (mapcat second) (context/parts-of db (:seon.turn/sent (d/pull db [:seon.turn/sent] e)))))
                (str "turn " n ": its read-back is the bytes it sent"))))
        (let [[_ last-turn] (peek turns)]
          (is (some #(and (vector? %) (= [:eval "e3" {:value 'result/e1}] %)) (:sent last-turn)) "e3's rows are e1's, sent whole: folded")
          (is (str/includes? (str (:messages last-turn)) "(:value result/e1)")))
        (testing "a fold whose record the history no longer sends whole is sent unfolded"
          (let [p {:ref [:eval "e3" {:value 'result/e1}] :whole [{:role "tool" :content "(:value result/e1)"}] :fold {:value 'result/e1}
                   :plain [{:role "tool" :content "[{:row 0 …}]"}]}]
            (is (= [[:eval "e3" {:value 'result/e1}] (:whole p)] (#'context/as-sent p #{"e1" "e3"})) "e1 sent whole: folded")
            (is (= [[:eval "e3"] (:plain p)] (#'context/as-sent p #{"e3"})) "e1 collapsed or left out: the value itself")))))))

(deftest a-turn-records-what-it-sent
  ;; task seon.context/a-turn-records-what-it-sent (A): a turn records the records its request
  ;; rendered, in order; read back, each by its own render as of the turn (the help page's
  ;; section and the task as of their t), they are the messages it sent, though the task changed
  ;; after it: nothing is built again
  (ts/test-branch!
    (fn [_ conn]
      (let [[db [r0 r1] [e0 e1]] (sent-turns conn)
            sent #(:seon.turn/sent (d/pull db [:seon.turn/sent] %))]
        (is (= [:section :page] (mapv first (sent e0))) "turn 0: the system prompt, the page whole")
        (is (pos-int? (second (first (sent e0)))) "the system prompt's own section, by entity")
        (is (= [:section :page :eval :page] (mapv first (sent e1))) "turn 1: turn 0's page, its call's eval, then what changed of the page")
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
            sent #(:seon.turn/sent (d/pull db [:seon.turn/sent] %))
            bytes #(count (seon.edn/encode (sent %)))]
        (is (= (count (sent e1)) (- (count r1) (count (filter #(= "tool" (:role %)) r1)))) "one ref a record: a model eval's call and answer are one record")
        (is (< (bytes e1) (* 60 (count (sent e1)))) (str "no text in the record: " (bytes e1) " bytes for " (count (sent e1)) " refs"))))))

(deftest the-request-is-a-conversation-of-evals-and-the-page
  ;; notes/archive/one-derivation-design-rev42.md §4: each turn's prose and eval calls with their responses
  ;; as tool messages (read from the eval records), the reasoning as returned, and at each model
  ;; turn the page: whole at the first, what changed of it after (a turn taken moves the status
  ;; alone); the prefix stays byte-identical
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (d/transact conn [{:seon.run/id "demo/f#1" :seon.run/task [:seon.task/id "demo/f"] :seon.run/agent {:seon.agent/key "b/a1"}}])
      (let [observed {:results {'demo-test/f-test :fail} :test-run-errors [] :reds [] :reasons []}
            ev! (fn [id by code value & [more]] (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "b/a1"}
                                                                   :seon.eval/record (merge {:code code :response {:handle (symbol "result" id) :value value} :run "demo/f#1"} more)}]))
            ;; as seon.turn goes: the turn recorded, then its request on the db that holds it
            request (fn [n more] (let [b {:commit (d/commit-id @conn) :task "demo/f" :run "demo/f#1" :turn n :max-turns 4
                                          :origin :model :observed observed :agent "b/a1"}]
                                   (d/transact conn [(merge {:seon.turn/id (str "demo/f#1/" n) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/n n :seon.turn/origin :model
                                                             :seon.turn/basis b :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 1} more)])
                                   (let [{:keys [messages sent]} (context/render @conn b)]
                                     (d/transact conn [{:seon.turn/id (str "demo/f#1/" n) :seon.turn/sent sent}])
                                     [b messages])))
            [b0 r0] (request 0 {:seon.turn/reply "I call f." :seon.turn/reasoning "why" :seon.turn/calls [{:id "call-0" :code "(f 1)" :eval "e2"}]})
            _ (ev! "e2" "a1" "(f 1)" 1 {:call-id "call-0" :call 0 :prose "I call f." :reasoning "why"})
            [_ r1] (request 1 {})]
        (is (= ["system" "user"] (mapv :role r0)) "turn 0: the system prompt, then the page whole")
        (is (= ["system" "user" "assistant" "tool" "user"] (mapv :role r1)) (pr-str (mapv :role r1)))
        (is (= {:role "assistant" :content "I call f." :reasoning "why" :tool-calls [{:id "call-0" :code "(f 1)"}] :call 0} (nth r1 2))
            "prose as content, the reasoning as returned, the call")
        (is (= "{:handle result/e2, :value 1}" (:content (nth r1 3))) "the call's response, its recorded value printed as it is sent")
        (is (= r0 (subvec r1 0 2)) "the whole earlier request is the prefix: its page stays, appended")
        (is (re-find #"status-\d+" (:content (peek r1))) "a turn taken: the status, the turn of how many")
        (is (not-any? #(re-find % (:content (peek r1))) [#"task-data-\d+" #"related-\d+" #"run-head-\d+"]) "the status alone")
        (is (str/includes? (:content (peek r1)) "2 of 4"))
        (is (not (str/includes? (:content (peek r1)) "[:task]")) "the rest unchanged, not told again")
        (is (not-any? #(str/includes? (str (:content %)) ";;") (rest r1)) "no ;; reaches the model")
        (is (nil? (resolve 'seon.context/messages)) "one path: no request without an agent")))))

(deftest an-assertion-message-of-any-value-is-kept-as-text
  ;; clojure.test's is takes any message (a doseq's keyword, say); a run whose test reported one
  ;; ended on seon.proofs/failures' contract (dbg/live#4, 2026-10-24) until the report kept it as text
  (let [rep (agent/test-report {:type :fail :message :scripted :expected '(= 1 2) :actual '(not (= 1 2))} "#'demo-test/t")]
    (is (= ":scripted" (:message rep)))
    (is (= [":scripted"] (map :message (get (proofs/failures {:reports [rep]}) 'demo-test/t))))))

(deftest the-task-data-is-plan-write-set-and-proofs
  ;; task seon.render/a-page-is-its-entitys-view (4d): the task's data is the plan, the write set (each
  ;; cited var's source and callers), and each proof's run form or what it should assert; no
  ;; helpers by embedding, no namespace map, no evidence
  (let [db (d/db-with (db-with-program) (program-facts '[[demo/area "(defn area [x] (demo/f x))" [demo/f]]
                                                          [demo/vol "(defn vol [x] (demo/f (demo/f x)))" [demo/f]]]))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "s"
                                               :seon.task/plan "Make `demo/f` triple. demo-test/h-test asserts (f 2) is 6."
                                               :seon.task/proofs #{'demo-test/f-test 'demo-test/h-test 'demo-test/q-test}}]))
        data (context/task-data db (task/task db "demo/s"))]
    (is (= [:task :write-set :to-prove] (vec (keys data))) "plan, write set, proofs; nothing else uncited (what is near is off by default)")
    (let [db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "n" :seon.task/plan "Make `demo/f` triple."
                                                 :seon.task/proofs #{'demo-test/f-test} :seon.task/config {:seon.config.render/similar 5}}]))]
      (is (contains? (:similar (context/task-data db (task/task db "demo/n"))) :not-embedded)
          "a task that turns it on, its plan with no vector yet: says so"))
    (is (= [{:symbol 'demo/f :source (print/code "(defn f \"Doubles.\" [x] (* 2 x))") :callers '[demo/area demo/vol]}] (:write-set data))
        "each cited var with its source and its callers")
    (is (= '(my.agent/prove #{demo-test/f-test}) (get (:to-prove data) 'demo-test/f-test)) "a written proof: the form that runs it")
    (is (= "not written yet; it should assert what the plan says: demo-test/h-test asserts (f 2) is 6."
           (get (:to-prove data) 'demo-test/h-test))
        "an unwritten proof: what the plan says it should assert")
    (is (= "not written yet" (get (:to-prove data) 'demo-test/q-test))
        "one the plan's sentences do not name: that it is not written, not the plan again (the page shows it)")))

(deftest a-record-keeps-its-value
  ;; the owner's rule (clipping audit 2026-10-25, P1/P8): data is cut only by the one printer,
  ;; when it is printed into a reader's view; the full result is stored. A record keeps its
  ;; response's value as data, and is the same whatever was evaluated before it
  (let [db (db-with-program)
        r {:handle 'result/e5 :value (vec (for [i (range 300)] {:i i :name (str "row-" i)})) :ms 1.0}
        rec (repl/record-details db "(rows)" r {} nil)
        stored (d/db-with db [{:seon.eval/id "e5" :seon.eval/by "a1" :seon.eval/record rec}])]
    (is (= (:value r) (get-in rec [:response :value])) "the whole value, not a printout of it")
    (is (not (contains? rec :text)) "no text made for a reader")
    (is (= rec (:seon.eval/record (d/pull stored [:seon.eval/record] [:seon.eval/id "e5"]))) "it round-trips through the store")
    (is (str/includes? (print/record-text rec) "row-0") "printed when it is shown")
    (testing "the same whatever came before it: a value an earlier eval showed is the reader's to fold"
      (let [seen (print/seen nil (assoc r :handle 'result/e1))]
        (is (= rec (repl/record-details db "(rows)" r {} nil)))
        (is (str/includes? (print/record-text rec seen) "(:value result/e1)") "folded as it is sent, not as it is stored")))
    (testing "a value that is not plain data keeps the response as it printed then, and its type"
      (doseq [v [(atom 1) (map inc (range 3)) #'clojure.core/inc]]
        (let [rec (repl/record-details db "(x)" {:handle 'result/e6 :value v} {} nil)]
          (is (not (contains? (:response rec) :value)) (str (type v)))
          (is (= (str (type v)) (:type rec)))
          (is (string? (:printed rec)))
          (is (= (:printed rec) (print/record-text rec)) "and reads so"))))))

(deftest prose-references-are-parsed-once
  ;; task seon.render/a-page-is-its-entitys-view (4e): an eval's prose is parsed once, as it is
  ;; recorded, by the one reference parser; its record holds what it cites
  (let [db (db-with-program)
        rec (repl/record-details db "(f 1)" {:text "2"} {:prose "Reading `demo/f` before I change it."} "demo/s#1")]
    (is (= ['demo/f] (mapv :seon.ref/name (:cites rec))) "the citation, kept in the record")
    (is (= (seon.ref/references db "Reading `demo/f` before I change it.") (:cites rec)) "as the one parser reads it")
    (is (= "demo/s#1" (:run rec)))
    (is (= "{:a 1}" (:line (repl/record-details db "{:a 1}" {:text "x" :value {:a 1}} {} nil))) "its value in one line, for the collapsed eval")
    (is (= "nil" (:line (repl/record-details db "(source f)" {:text "(defn f [x] x)\nnil" :value nil} {} nil)))
        "a value of nil after printing is nil, not nothing (step 6: such evals' collapsed rows showed nothing)")
    (is (not (contains? (repl/record-details db "(f 1)" {:text "2"} {:prose "No names here."} nil) :cites)) "prose citing nothing records none")))

(deftest the-task-data-names-the-functions-nearest-its-plan
  ;; the owner's ruling (2026-10-24, DECISIONS "Embeddings join the context"): the task's data's
  ;; :similar, the program functions nearest the plan by meaning (exact), those it names and
  ;; what is no function left out, the first :seon.config.render/similar (5, the task's own setting), each a line: its
  ;; name and its doc's first sentence; a plan with no vector says so
  (let [fns '[demo/area demo/pi demo/noise demo/e1 demo/e2 demo/e3]
        db (d/db-with (db-with-program) (program-facts (vec (for [s (conj fns 'demo-test/near-test 'demo.fixtures/near-helper)] [s (str "(defn " (name s) " [])") []]))))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "c" :seon.task/plan "Make `demo/f` use the circle's area."
                                               :seon.task/proofs #{'demo-test/f-test} :seon.task/config {:seon.config.render/similar 5}}]))
        db (d/db-with db (vec (concat (for [s (conj fns 'demo/f)] {:var/name s :var/kind :fn :var/doc (str (name s) " does it. More.")})
                                      [{:var/name 'demo-test/near-test :var/kind :test}
                                       ;; a test/ helper of any name (seon.fixtures, seon.check): a function, not the program's
                                       {:var/name 'demo.fixtures/near-helper :var/kind :fn :var/doc "near-helper does it."}
                                       {:ns/name 'demo :ns/file "src/demo.clj"} {:ns/name 'demo-test :ns/file "test/demo_test.clj"}
                                       {:ns/name 'demo.fixtures :ns/file "test/demo/fixtures.clj"}])))
        dir (fn [& xs] (let [v (vec (concat xs (repeat (- 768 (count xs)) 0.0))) n (Math/sqrt (reduce + (map #(* % %) v)))] (mapv #(/ % n) v)))
        form (fn [s] (:db/id (:var/form (d/entity db [:var/name s]))))
        te (pdb/eid db :seon.task/id "demo/c")
        ;; nearest first: the named f, a test, a test/ helper, then the six functions in order
        order (into '[demo/f demo-test/near-test demo.fixtures/near-helper] fns)
        es (into [te] (map form) order)
        vs (into [(dir 1.0)] (map-indexed (fn [i _] (dir 1.0 (* 0.05 (inc i)))) order))
        db (d/db-with db (vector/tx-data es (repeat (count es) 1) vs (repeat (count es) 10) "m"))
        sim (context/similar db te #{'demo/f 'demo-test/f-test})]
    (is (= ["demo/area: area does it." "demo/pi: pi does it." "demo/noise: noise does it." "demo/e1: e1 does it." "demo/e2: e2 does it."]
           (:nearest sim))
        "nearest first, the named one, the test and the test/ helper left out, the first five, each its name and first sentence")
    (is (string? (:more sim)) "and the form that searches further")
    (is (= sim (:similar (context/task-data db (task/task db "demo/c")))) "in the task's data")
    (is (nil? (context/similar db nil #{})) "off by default (:seon.config.render/similar 0): nothing listed")))

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
        state (context/turn-state db (task/task db "demo/f") {:results {'demo-test/f-test :fail} :reds [] :turn 0 :max-turns 4})
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
        db (d/db-with db (vec (for [i (range 5)] {:seon.section/address (anchors i) :seon.section/heading (str "S" i)
                                                  :seon.section/lead (str "Section " i " says one thing. And more.")
                                                  :seon.section/text (str "Section " i " says one thing. And more.")})))
        ;; demo is the program's (src/), demo-test and its helpers are test/'s (seon.program/program-nss),
        ;; held before the task cites them: a name in a namespace the program does not hold is not cited
        db (d/db-with db [{:ns/name 'demo :ns/file "src/demo.clj"} {:ns/name 'demo-test :ns/file "test/demo_test.clj"}])
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "r" :seon.task/plan plan}]))
        te (pdb/eid db :seon.task/id "demo/r")
        es (into [te] (for [i (range 5)] (pdb/eid db :seon.section/address (anchors i))))
        db (d/db-with db (vector/tx-data es (repeat 6 1) (into [(dir 1.0)] (for [i (range 5)] (dir 1.0 (* 0.1 (inc i))))) (repeat 6 10) "m"))
        ;; a function beside the plan by meaning, and a schema key its plan names
        db (d/db-with db [{:db/id "f" :form/id "demo/g" :form/src "(defn g [])" :form/keywords #{:demo/thing :demo/not-a-schema}} {:var/name 'demo/g :var/form "f" :var/doc "Gets it. More."}
                          {:schema/key :demo/thing :schema/form "[:map]"}])
        f (pdb/eid db :form/id "demo/g")]
    [(d/db-with db (vector/tx-data [f] [1] [(dir 1.0 0.05)] [10] "m")) te dir]))

(deftest the-related-part-shows-the-sections-nearest-the-plan
  ;; the owner (2026-10-25): embed the docs and pull in related concepts, as explicit evals refined
  ;; over the session: the page's [:related] part (and (my.task/related)), the sections nearest the plan's vector
  ;; that it does not cite, the first :seon.config.render/related (5), each a line with the forms
  ;; that dig further (its wrappers' own test: seon.agent-test/related-is-an-eval-of-the-plan-and-of-any-text)
  (let [[db te] (sections-near "Make the page say one thing (docs/vector.md#what-is-embedded) of `:demo/thing`.")
        rel (context/related db te)
        v (context/related-view db rel)]
    (is (= ["docs/vector.md#the-model" "docs/vector.md#the-pyramid" "docs/vector.md#fused-with-the-call-graph" "docs/vector.md#what-the-work-is-likely-to-need-as-an-eval"] (:sections rel))
        "the sections nearest first, the section the plan cites left out")
    (is (= ['demo/g] (:functions rel)) "the functions nearest it")
    (is (= [:demo/thing] (:schemas rel)) "and the schema keys its plan cites")
    (is (= [["demo/g: Gets it." [:demo/thing]]] (:functions v))
        "a function a line: its name, its doc's first sentence and the registry keys it operates on (its :form/keywords)")
    (is (= ["docs/vector.md#the-model: Section 0 says one thing." "docs/vector.md#the-pyramid: Section 2 says one thing."
            "docs/vector.md#fused-with-the-call-graph: Section 3 says one thing." "docs/vector.md#what-the-work-is-likely-to-need-as-an-eval: Section 4 says one thing."]
           (:sections v))
        "each at summary depth: its address and its lead's first sentence")
    (is (str/includes? (:read v) "(:seon.ref/text (my.agent/help ") "with the form that reads one whole, its text's key named")
    (is (str/includes? (:more v) "my.agent/search") "and the form that searches further")
    (is (nil? (context/related (ts/test-db) (pdb/eid db :seon.task/id "demo/r"))) "a plan with no vector shows none")))

(deftest related-sections-follow-a-rewritten-plan
  ;; related is a render over the plan's vectors (seon.context/related): the plan rewritten and
  ;; embedded on the branch, its answer follows; a section embedded with the plan unchanged is in
  ;; the next answer
  (let [[db te dir] (sections-near "Make the page say one thing.")
        _ (is (= "docs/vector.md#the-model" (first (:sections (context/related db te)))))
        db (d/db-with db [[:db/add te :seon.task/plan "Make the last section say it."]])
        t (:tx (first (d/datoms db :eavt te :seon.task/plan)))
        db (d/db-with db (vector/tx-data [te] [t] [(dir 1.0 0.5)] [10] "m"))]
    (is (= "docs/vector.md#what-the-work-is-likely-to-need-as-an-eval" (first (:sections (context/related db te)))) "the sections follow it: the one nearest the new plan first")
    (let [s (d/db-with db [{:seon.section/address "docs/vector.md#the-pyramid-b" :seon.section/heading "B" :seon.section/lead "New. More."}])
          se (pdb/eid s :seon.section/address "docs/vector.md#the-pyramid-b")
          s (d/db-with s (vector/tx-data [se] [1] [(dir 1.0 0.5)] [10] "m"))]
      (is (some #{"docs/vector.md#the-pyramid-b"} (:sections (context/related s te)))
          "a section embedded on the branch with the plan unchanged is in the next answer"))))

(defn- walked
  "Agent demo/a1's page on db (sections-near's, with a run of demo/r), walked live over a reactive
  atom holding its ctx, as its process holds it: [walk atom db-cell rid]. A change is (step! walk
  atom db-cell tx-data): the transaction applied, ctx patched by its datoms, the walk framed."
  [db]
  (let [db (d/db-with db [{:seon.agent/key "demo/a1"} {:seon.run/id "demo/r#1" :seon.run/task [:seon.task/id "demo/r"] :seon.run/agent [:seon.agent/key "demo/a1"]}])
        cell (volatile! db)
        c (volatile! (ctx/build db (pdb/eid db :seon.agent/key "demo/a1") :b))
        a (seon.ratom/atom (:tree @c))
        w (render/walker {:ref a :changes ctx/changes} #(deref cell) [(requiring-resolve (render/declared :seon.render/render-ctx)) {}])]
    (render/frame! w nil)
    [w a cell c (pdb/eid db :seon.run/id "demo/r#1")]))

(defn- step!
  "Transaction tx-data on walked's db: ctx patched by its datoms, the walk framed. Answers the units
  whose html changed, by id."
  [[w a cell c] tx-data]
  (let [u0 (into {} (map (juxt :id :html)) (render/placed-units w))
        r (d/with @cell tx-data)]
    (vreset! cell (:db-after r))
    (vswap! c ctx/patch (:db-after r) (:tx-data r) :b)
    (reset! a (:tree @c))
    (render/frame! w (:tx-data r))
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

(deftest the-task-draws-what-it-needs
  ;; notes/context-for-20-turns-2026-10-26.md (D1-D4): the task's list draws the whole plan (D4),
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
    (is (str/includes? task "(deftest g-test (is (= :one (g))))") "D1: the proof written, its source")
    (is (str/includes? (html (str "cited-" rid "-demo_2Fg")) "(defn g [])") "D2: the cited var's source, its own component")
    (is (str/includes? task "Section 1 says one thing.") "D3: the cited section's text, not its address")
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
    (is (= {:symbol 'demo-only/h :arglists '([a b]) :summary "Holds what the db says."} (docs/resolve-ref db 'demo-only/h :summary))
        "a var only the db holds: its summary from its facts")))

(deftest related-fuses-the-plans-references-with-meaning
  ;; seon.context/related's graph half: the plan cites `demo/h`, so its caller demo/k (no vector:
  ;; meaning alone never finds it) and the section naming it (no vector either) are fused with the
  ;; nearest by meaning (seon.vector/rrf: a tie broken by name); what the plan cites is left out
  (let [[db te] (sections-near "Make the page say one thing (docs/vector.md#what-is-embedded), in `demo/h`.")
        db (d/db-with db [{:db/id "h" :form/id "demo/h" :form/src "(defn h [])"} {:var/name 'demo/h :var/form "h" :var/kind :fn}
                          {:db/id "k" :form/id "demo/k" :form/src "(defn k [] (h))" :form/refs #{'demo/h} :form/defines #{"kv"}} {:db/id "kv" :var/name 'demo/k :var/form "k" :var/kind :fn}
                          {:db/id "t" :form/id "demo/k-test" :form/src "(deftest k-test (h))" :form/refs #{'demo/h} :form/defines #{"tv"}} {:db/id "tv" :var/name 'demo/k-test :var/form "t" :var/kind :test}
                          ;; a test namespace's helper calling it: a fixture, no program function
                          {:db/id "x" :form/id "demo-test/fixture" :form/src "(defn fixture [] (h))" :form/refs #{'demo/h} :form/defines #{"xv"}}
                          {:db/id "xv" :var/name 'demo-test/fixture :var/form "x" :var/kind :fn}
                          ;; a helper of test/ whose name does not end in -test (seon.fixtures, seon.check): not the program's
                          {:ns/name 'demo.fixtures :ns/file "test/demo/fixtures.clj"}
                          {:db/id "y" :form/id "demo.fixtures/helper" :form/src "(defn helper [] (h))" :form/refs #{'demo/h} :form/defines #{"yv"}}
                          {:db/id "yv" :var/name 'demo.fixtures/helper :var/form "y" :var/kind :fn}
                          {:seon.section/address "docs/vector.md#the-pyramid-c" :seon.section/heading "C" :seon.section/lead "Names h. More."
                           :seon.section/names #{'demo/h}}])
        rel (context/related db te)]
    (is (= ['demo/g 'demo/k] (:functions rel)) "the caller of the cited var beside the nearest by meaning; the cited var, the test, a test namespace's helper and a test/ helper of any name left out")
    (is (= ["docs/vector.md#the-model" "docs/vector.md#the-pyramid-c" "docs/vector.md#the-pyramid" "docs/vector.md#fused-with-the-call-graph"
            "docs/vector.md#what-the-work-is-likely-to-need-as-an-eval"]
           (:sections rel))
        "the section naming the cited var fused at its rank with the nearest by meaning; the cited section left out")
    (is (nil? (context/related-view db {:sections [] :functions [] :schemas []})) "nothing related, nothing shown")
    ;; task seon.context/related-weighs-a-neighbour-by-its-rarity: h calls demo/plumb, which twenty
    ;; forms name, and demo/rare, which only h names; by plain counts the two tie and the name put
    ;; plumbing first
    (let [db (d/db-with db (into [{:db/id "h" :form/id "demo/h" :form/refs #{'demo/plumb 'demo/rare}}
                                  {:db/id "pv" :var/name 'demo/plumb :var/kind :fn} {:db/id "rv" :var/name 'demo/rare :var/kind :fn}]
                                 (for [i (range 20)] {:form/id (str "demo/p" i) :form/src "(plumb)" :form/refs #{'demo/plumb}})))]
      (is (= ['demo/g 'demo/k 'demo/rare 'demo/plumb] (:functions (context/related db te)))
          "a neighbour few forms name before one every form names")
      ;; a namespace with no file yet: what the agent wrote at its REPL, its new work, shown
      (let [db (d/db-with db [{:ns/name 'agent.app} {:db/id "z" :form/id "agent.app/z" :form/src "(defn z [] (h))" :form/refs #{'demo/h} :form/defines #{"zv"}}
                              {:db/id "zv" :var/name 'agent.app/z :var/form "z" :var/kind :fn}])]
        (is (= 'agent.app/z (first (:functions (context/related db te)))) "a caller in a namespace with no file is shown")))))

(deftest a-prose-requests-functions-come-from-its-nearest-and-their-neighbours
  ;; task seon.context/a-prose-request-finds-its-functions: a plan citing no defined var (a user's
  ;; request in prose; `demo/nope` names none) seeds related's call graph with the program functions
  ;; nearest it by meaning, read past the tests nearer still (:seon.config.render/related-seeds-near):
  ;; twenty-five tests sit nearer the plan than demo/g, so meaning's candidates (related-near per
  ;; line) hold no program function, and demo/h (no vector) is found only as g's callee
  (doseq [plan ["Make the page say one thing." "Make the page say one thing, as `demo/nope` does."]]
    (let [[db te dir] (sections-near plan)
          db (d/db-with db [{:db/id "h" :form/id "demo/h" :form/src "(defn h [])" :form/defines #{"hv"}} {:db/id "hv" :var/name 'demo/h :var/form "h" :var/kind :fn}
                            {:form/id "demo/g" :form/refs #{'demo/h} :form/defines #{[:var/name 'demo/g]}}])
          db (d/db-with db (vec (for [i (range 25)] (let [f (str "t" i)]
                                                      {:db/id f :form/id (str "demo-test/t" i) :form/src "(deftest t (g))" :form/refs #{'demo/g}
                                                       :form/defines #{(str f "v")}}))))
          db (d/db-with db (vec (for [i (range 25)] {:db/id (str "t" i "v") :var/name (symbol "demo-test" (str "t" i)) :var/form [:form/id (str "demo-test/t" i)] :var/kind :test})))
          ts (for [i (range 25)] (pdb/eid db :form/id (str "demo-test/t" i)))
          db (d/db-with db (vector/tx-data (vec ts) (repeat 25 1) (for [i (range 25)] (dir 1.0 (* 0.001 (inc i)))) (repeat 25 10) "m"))
          rel (context/related db te)]
      (is (some #{'demo/h} (:functions rel)) (str "the nearest function's callee, for: " plan " " (pr-str (:functions rel))))
      ;; a plan citing a defined var keeps its own graph: g's callee is not reached from meaning
      (let [db (d/db-with db [{:db/id "q" :form/id "demo/q" :form/src "(defn q [])" :form/defines #{"qv"}} {:db/id "qv" :var/name 'demo/q :var/form "q" :var/kind :fn}
                              {:db/id "c" :form/id "demo/c" :form/src "(defn c [] (q))" :form/refs #{'demo/q} :form/defines #{"cv"}} {:db/id "cv" :var/name 'demo/c :var/form "c" :var/kind :fn}
                              {:seon.task/id "demo/r" :seon.task/plan (str plan " Change `demo/q`.")}])
            fs (:functions (context/related db te))]
        (is (some #{'demo/c} fs) (str "a cited var's graph: its caller " (pr-str fs)))
        (is (not-any? #{'demo/h} fs) "and not meaning's seed")))))

(deftest a-pages-cited-vars-are-the-programs
  ;; audit item 2a: the page's cited vars are the program's; a reproduction's test helper
  ;; (demo-test is test/'s), library call and handle are not cited, while the program's var is
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "c"
                                              :seon.task/plan "Reproduce: (let [x (demo-test/f-test)] (demo/f (clojure.string/trim x))) answers result/e1."}]))]
    ;; the cited set is the task's references (ctx's :cited joins them by :var/name)
    (is (= #{'demo/f} (set (:seon.task/references (d/pull db [:seon.task/references] [:seon.task/id "demo/c"])))))))

(deftest a-proof-with-no-result-reads-unknown
  ;; demo-test/f-test is written (its source is recorded) but no test report names it: it never
  ;; ran, which the judge calls :proof-unknown (seon.reconcile/done-explain); it is not "ran and asserted nothing"
  (let [db (db-with-program)
        block (context/current-state db (task-f db) {:results {} :turn 1 :max-turns 4})
        by (into {} (map (juxt :test :status)) (:proofs block))]
    (is (= :unknown (by 'demo-test/f-test)) (pr-str (:proofs block)))))

(deftest search-fuses-words-and-references-and-filters-by-plain-data
  ;; task one-index steps 4-5: one search over every kind; the answer is data an agent filters
  (let [db (d/db-with (seon.docs-test/corpus-db)
                      [{:db/id "c" :var/name 'app/caller} {:form/id "app/caller" :form/src "(defn caller [] (g))" :form/defines ["c"] :form/refs ['app/g]}])
        s (fn ([q] (context/search db q {} nil)) ([q o] (context/search db q o nil)))
        shown (fn [hits] (remove #(= :more (:kind %)) hits))
        kind (fn [hits k] (filter #(= k (:kind %)) hits))]
    (is (= #{:var :section} (set (map :kind (shown (s "fusing"))))) "functions and sections by default")
    (is (= #{:var :section :task :schema} (set (map :kind (shown (s "fusing" {:kinds #{:var :section :task :schema}}))))) "the rest on request")
    (is (every? #(and (number? (:score %)) (set? (:by %)) (:e %)) (shown (s "fusing"))) "plain data: kind, name, entity, score, by")
    (is (= [:section :section] (map :kind (s "fusing" {:kinds #{:section} :min 0.0}))) "kinds focus it")
    (is (every? #(= #{:text} (:by %)) (shown (s "fusing" {:by #{:text}}))))
    (is (every? #(<= 0.0 (:relevance %) 1.0) (filter :relevance (s "fusing" {:min 0.0}))) "a text hit's relevance")
    (is (= (s "fusing") (s "fusing")) "deterministic")
    (is (apply >= (map :score (shown (s "fusing")))) "best first")
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
    (testing ":ns keeps the vars of a namespace prefix"
      (is (= ['app/g] (map :name (kind (s "fusing" {:ns "app"}) :var))))
      (is (= [] (kind (s "fusing" {:ns "other"}) :var)))
      (is (seq (kind (s "fusing" {:ns "other"}) :section)) "and leaves the other kinds"))
    (testing ":min leaves out a hit whose known relevance is below it"
      (is (= [] (shown (s "fusing" {:min 1.0}))))
      (is (= :more (:kind (first (s "fusing" {:min 1.0})))) "and says so"))))

(defn- unit-vec [i]
  (let [raw (mapv #(Math/sin (* (inc i) (inc %) 0.37)) (range 768))
        n (Math/sqrt (reduce + (map #(* % %) raw)))]
    (mapv #(/ % n) raw)))

(deftest search-ranks-meaning-across-kinds-by-score-and-weights-the-rankings
  (let [with-vec (fn [m i] (merge m (vector/pack (unit-vec i)) {:seon.vector/model "m" :seon.vector/text-t 0 :seon.vector/tokens 4}))
        db (d/db-with (ts/test-db)
                      [(with-vec {:db/id "s0" :seon.section/address "m.md#a" :seon.section/heading "a" :seon.section/text "about a"} 0)
                       (with-vec {:db/id "s1" :seon.section/address "m.md#b" :seon.section/heading "b" :seon.section/text "about b"} 5)
                       {:db/id "v" :var/name 'm/f :var/form "f"}
                       (with-vec {:db/id "f" :form/id "m/f" :form/src "(defn f [] 1)" :form/defines ["v"]} 1)])
        hits (context/search db "zzz" {:by #{:meaning} :k 10} (unit-vec 0))]
    (is (= "m.md#a" (:name (first hits))) "the nearest of all kinds first, whichever kind it is")
    (is (= #{:section :var} (set (map :kind hits))))
    (is (every? #(= #{:meaning} (:by %)) hits))
    (is (apply >= (map :score hits)) "ranked by one list, best first")
    (is (= [:section] (distinct (map :kind (context/search db "zzz" {:by #{:meaning} :kinds #{:section}} (unit-vec 0))))))))

(deftest rrf-scores-weigh-each-ranking
  (let [plain (vector/rrf-scores [[:a] [:b]])
        weighed (vector/rrf-scores [[:a] [:b]] [1.0 3.0])]
    (is (= (:a plain) (:b plain)) "two firsts weigh alike")
    (is (< (:a weighed) (:b weighed)) "a ranking's weight scales its share")
    (is (< (Math/abs (- (:b weighed) (* 3.0 (:a weighed)))) 1e-12))))
