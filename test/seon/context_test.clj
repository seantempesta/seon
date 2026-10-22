(ns seon.context-test
  "The agent's current-state block says each thing once: a passing proof is its status, a
  red one its source and its failures (expected and actual), a reference already shown as a
  proof is not shown again, a merge task's plan stays its own."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task] [seon.context :as context] [seon.print :as print]
            [seon.reader :as reader] [seon.proofs :as proofs] [seon.agent :as agent] [seon.docs :as docs]
            [seon.print.select :as select] [clojure.string :as str] [clojure.walk :as walk] [seon.edn]))

(defn- db-with-program []
  (let [db (ts/test-db)]
    ;; recorded definitions, each its form (seon.program's facts), written as data
    (d/db-with db (vec (mapcat (fn [[v src]]
                                 [{:db/id (str v) :var/name v :var/ns (str "ns " (namespace v)) :var/form (str "form " v)}
                                  {:db/id (str "form " v) :form/id (str v) :form/src src :form/ns (str "ns " (namespace v)) :form/order 0
                                   :form/defines [(str v)]}
                                  {:db/id (str "ns " (namespace v)) :ns/name (symbol (namespace v))}])
                               '[[demo/f "(defn f \"Doubles.\" [x] (* 2 x))"]
                                 [demo-test/f-test "(deftest f-test (is (= 4 (f 2))))"]
                                 [demo-test/g-test "(deftest g-test (is (= 1 (g))))"]])))))

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
    (is (string? (:source (by 'demo-test/g-test))) "a red proof shows its source")
    (is (= [{:assert "(= 1 (g))" :got "(= 1 0)"}] (:failures (by 'demo-test/g-test)))
        "a failure is the assertion and what it evaluated to")
    (is (= ['demo/f] (mapv :symbol (:references block))) "a proof named in the plan is not shown again as a reference")
    (is (nil? (:doc (first (:references block)))) "a program reference's source already holds its doc")))

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
                                           :run-errors ["namespace app-test did not load: Unable to resolve symbol: g"]})]
    (testing "every reason but the task's own red proof (shown with the proofs), each with what explains it"
      (is (= [{:reason :regression :test 'demo-test/g-test :failures [{:assert "(= 1 (g))" :got "(= 1 0)"}]}
              {:reason :stray-red :test 'user/scratch-test}
              {:reason :contract-gap :vars ['demo/helper]}]
             (:refused block))))
    (testing "a run that failed as a whole is said, not read as tests not run"
      (is (= {:failed ["namespace app-test did not load: Unable to resolve symbol: g"]} (:test-run block)))
      (is (= ["namespace app-test did not load: Unable to resolve symbol: g" "test process died: 9"]
             (proofs/run-errors {:reports [{:type :error :message "namespace app-test did not load: Unable to resolve symbol: g"}
                                           {:type :fail :var "#'demo-test/f-test" :message "x"}
                                           {:type :error :var nil :message "test process died: 9"}]}))))
    (testing "the printed context carries them"
      (let [text (:content (peek (context/messages "H" block)))]
        (is (re-find #":regression" text))
        (is (re-find #"did not load" text))))
    (testing "a design step that has not improved its plan is told what it lacks and the call that does it"
      (let [b (context/current-state db t {:results {} :reasons [{:reason :no-design}] :turn 1 :max-turns 4})]
        (is (= [[:no-design :seon.task/plan 'my.task/update!]]
               (mapv (juxt :reason :unchanged (comp first :do)) (:refused b))))))
    (testing "nothing refused, nothing failed: no section"
      (let [b (context/current-state db t {:results {'demo-test/f-test :pass} :reasons [] :turn 1 :max-turns 4 :run-errors []})]
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
      (is (= ['ctx-prog-ns/p-test] (mapv symbol (agent/test-vars db db '#{ctx-prog-ns/p-test ctx-scratch-ns/s-test})))))))

(deftest a-failure-reads-as-assert-and-got
  (binding [*ns* *ns*]
    (eval '(ns ctx-calc))
    (eval '(defn square "Squares x." {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)))
    (eval '(ns ctx-calc-test (:require [clojure.test :refer [deftest is]] [ctx-calc :refer [square]])))
    (eval '(deftest square-test (is (= 9 (square 3))))))
  (let [db (d/db-with (db-with-program)
                      (vec (mapcat (fn [[v src]]
                                     [{:db/id (str v) :var/name v :var/ns (str "ns " (namespace v)) :var/form (str "form " v)}
                                      {:db/id (str "form " v) :form/id (str v) :form/src src :form/ns (str "ns " (namespace v)) :form/order 0
                                       :form/defines [(str v)]}
                                      {:db/id (str "ns " (namespace v)) :ns/name (symbol (namespace v))}])
                                   '[[ctx-calc/square "(defn square \"Squares x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))"]
                                     [ctx-calc-test/square-test "(deftest square-test (is (= 9 (square 3))))"]
                                     [ctx-calc-test/empty-test "(deftest empty-test (square 3))"]])))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'ctx :seon.task/name "sq" :seon.task/plan "p"
                                               :seon.task/proofs #{'ctx-calc-test/square-test 'ctx-calc-test/cube-test 'ctx-calc-test/empty-test}}]))
        ;; the test child's report of the failing assertion, as clojure.test makes it
        rep (agent/test-report {:type :fail :expected '(= 9 (square 3)) :actual '(not (= 9 6))} "#'ctx-calc-test/square-test")
        block (context/current-state db (task/task db "ctx/sq")
                                     {:results {'ctx-calc-test/square-test :fail} :turn 1 :max-turns 4
                                      :reds (proofs/red-reports {:reports [rep]} #{'ctx-calc-test/square-test})})
        by (into {} (map (juxt :test identity)) (:proofs block))]
    (is (= [{:assert "(= 9 (square 3))" :got "(= 9 6)"}] (:failures (by 'ctx-calc-test/square-test)))
        "the assertion once, and what it evaluated to")
    (is (= [{:var 'ctx-calc/square :arglists '([x]) :contract [:=> [:cat :int] :int] :doc "Squares x."}]
           (:calls (by 'ctx-calc-test/square-test)))
        "the program function the red proof calls, at summary level")
    (is (= {:test 'ctx-calc-test/cube-test :status :undefined} (by 'ctx-calc-test/cube-test))
        "a proof not written yet says so")
    (is (= :no-assertions (:status (by 'ctx-calc-test/empty-test))) "a proof that ran and asserted nothing says so")
    (let [b (context/current-state db (task/task db "ctx/sq") {:results {} :turn 1 :max-turns 4 :run-errors ["namespace app-test did not load"]})]
      (is (= {'ctx-calc-test/square-test :not-run 'ctx-calc-test/empty-test :not-run 'ctx-calc-test/cube-test :undefined}
             (into {} (map (juxt :test :status)) (:proofs b)))
          "when the run failed as a whole, a written proof did not run"))))

(defn- evals
  "n evaluations cycling over k distinct forms, each answering a large value and printing
  a large output (inv3's edit/forms: 259 items, 11 distinct)."
  [n k]
  (let [kinds (vec (for [j (range k) :let [v (vec (range (* 2000 (inc j))))]]
                     {:tag :ret :form (str "(code-of " j ")") :handle (keyword (str "h" j))
                      :printed (print/text v) :value v
                      :out (apply str (repeat 400 (str "line " j " of pprint output\n")))}))]
    (vec (for [i (range n)] (assoc (nth kinds (mod i k)) :id i)))))

(deftest repeated-evals-fold-and-every-item-fits-its-share
  (let [db (db-with-program)
        t (task-f db)
        text-of (fn [items] (:content (peek (context/messages "H" (context/current-state db t {:turn 1 :max-turns 4 :last-items items})))))]
    (testing "259 items, 11 distinct: 11 folded items whose times sum to 259"
      (let [le (context/fold-evals (evals 259 11))]
        (is (= 11 (count le)))
        (is (= (range 11) (map :k le)) "each folded item is the first of its kind")
        (is (= [24 24 24 24 24 24 23 23 23 23 23] (mapv :times le)) "259 = 6x24 + 5x23")))
    (testing "the block and the evaluations each stay inside their own budget at 259 items and at 10x that, every distinct item shown"
      (doseq [n [259 2590]]
        (let [items (evals n 11) text (text-of items)]
          (is (<= (select/estimate text) (+ (:context print/budgets) context/last-evals-budget)) (str n " items"))
          (is (every? #(str/includes? text (str "\"(code-of " % ")\"")) (range 11)) "no item starves another")
          (is (str/includes? text (str ";; " (count (filter #(= "(code-of 0)" (:form %)) items)) "x, the same form and answer"))))))
    (testing "each item within its equal share; every cut's form reads exactly the rest from (my.turn/evals)"
      (let [items (evals 259 11)
            shown (context/shown-evals (context/fold-evals items))
            share (quot context/last-evals-budget 11)]
        (is (every? #(<= (:tokens %) share) shown))
        (is (= 22 (count (mapcat :cuts shown))) "each of 11 items cuts its value and its output")
        (doseq [c (mapcat :cuts shown)
                :let [f (:seon.print/continuation c)
                      rest ((eval (list 'fn '[items] (walk/postwalk-replace {'(my.turn/evals) 'items} f))) items)
                      whole (get-in items (into [(nth (:seon.print/requery-id c) 2)] (:seon.render.data/path c)))]]
          (is (= (:seon.render.data/next-offset c) (- (count whole) (count rest))) (pr-str f))
          (is (= (seq whole) (seq (concat (take (:seon.render.data/next-offset c) whole) rest))) (pr-str f)))))))

(deftest a-turn-records-its-basis-and-its-context-is-made-again-from-it
  ;; task seon.db/the-store-grows-with-what-it-keeps (owner, 2026-10-22: store each turn's
  ;; basis, not its context text): the text the model was sent, built as the turn loop built
  ;; it before (current-state and messages, the first turn's text carried in the scrollback),
  ;; equals the text seon.context/render makes again from the recorded basis on the commit it
  ;; names, after the branch moved on (the program changed, later turns were written)
  (ts/test-branch! (fn [_ conn] (let [_ (wiki! conn)
        program (fn [src] [{:db/id "f" :var/name 'demo/f :var/ns "ns" :var/form "form"}
                           {:db/id "form" :form/id (str "demo/f " src) :form/src src :form/ns "ns" :form/order 0 :form/defines ["f"]}
                           {:db/id "ns" :ns/name 'demo}])
        _ (d/transact conn (program "(defn f \"Doubles.\" [x] (* 2 x))"))
        _ (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make `demo/f` triple."
                                                   :seon.task/proofs #{'demo-test/f-test}}]))
        observed {:results {'demo-test/f-test :fail} :run-errors []
                  :reds [{:test 'demo-test/f-test :type :fail :expected "(= 6 (f 2))" :actual "(not (= 6 4))" :got "(= 6 4)"}]
                  :reasons [{:reason :proof-red :symbol 'demo-test/f-test} {:reason :contract-gap :symbol 'demo/helper}]}
        basis (fn [db n] {:commit (d/commit-id db) :task "demo/f" :attempt "demo/f#1" :turn n :max-turns 4 :origin :model :observed observed})
        record! (fn [b more] (d/transact conn [(merge {:seon.turn/id (str "demo/f#1/" (:turn b)) :seon.turn/attempt "demo/f#1"
                                                       :seon.turn/n (:turn b) :seon.turn/origin :model :seon.turn/basis b
                                                       :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 1 :seon.turn/tokens 1}
                                                      more)]))
        ;; turn 0: what the model was sent, the old way
        db0 @conn
        b0 (basis db0 0)
        msgs0 (context/messages "H" (context/current-state db0 (task/task db0 "demo/f") (assoc observed :turn 0 :max-turns 4 :repairs [])))
        sent0 (context/reader-text msgs0)
        code0 "(defn f [x] (* 3 x)"
        evals0 (context/evaluations-text [{:id 0 :tag :ret :form "(defn f [x] (* 3 x))" :printed "#'demo/f"}])
        calls0 [{:id "call-0" :code code0 :evals evals0}]
        _ (record! b0 {:seon.turn/reply "f triples." :seon.turn/calls calls0 :seon.turn/truncated true})
        ;; the reply changed the program: turn 0's text must come from its own commit
        _ (d/transact conn (program "(defn f \"Triples.\" [x] (* 3 x))"))
        db1 @conn
        b1 (basis db1 1)
        {:keys [text repairs]} (reader/forgive code0)
        sent1 (context/reader-text (context/messages "H" (context/current-state db1 (task/task db1 "demo/f")
                                                                            (assoc observed :turn 1 :max-turns 4 :truncated? true :source text :repairs repairs))
                                                     [{:reply "f triples." :calls calls0 :attempt (:content (second msgs0))}]))
        _ (record! b1 {:seon.turn/reply "" :seon.turn/calls [{:id "call-1" :code "(my.turn/wait)" :evals ""}]})
        _ (d/transact conn (program "(defn f \"Later.\" [x] x)"))
        db @conn
        e-of (fn [id] (d/q '[:find ?e . :in $ ?id :where [?e :seon.turn/id ?id]] db id))]
    (is (str/includes? sent0 "Doubles.") "turn 0 read the program as it stood")
    (is (not (str/includes? sent1 "Triples.")) "turn 1's attempt sections are turn 0's: the prefix stays")
    (is (= sent0 (context/turn-text db (e-of "demo/f#1/0"))) "turn 0's text, made again from its basis")
    (is (= sent1 (context/turn-text db (e-of "demo/f#1/1"))) "turn 1's text, its scrollback included")
    (is (= sent1 (context/reader-text (:messages (context/render db1 b1)))) "the turn loop's own render is the same text")
    (is (= {:text sent1 :origin :model :turn "demo/f#1/1"} (context/recorded db "demo/f#1")) "the page reads the last turn's")
    (is (nil? (context/recorded db "c#1")) "an attempt that read nothing has no context")
    (is (< (count (seon.edn/encode b1)) 600) "the basis is small: no text the db already holds")))))

(deftest a-context-read-as-a-value-does-not-grow-the-next-context
  ;; the model evaluates something that answers a whole context (its own block, say): the
  ;; item gets its one share like any other, so turn after turn the context cannot nest
  (let [db (db-with-program)
        t (task-f db)
        text-of (fn [items] (:content (peek (context/messages "H" (context/current-state db t {:turn 1 :max-turns 4 :last-items items})))))
        c0 (text-of (evals 259 11))
        item (fn [c] {:id 0 :tag :ret :form "(seon.turn/context-block \"demo/f\" \"demo/f#1\")" :printed (pr-str c) :value c})
        c1 (text-of [(item c0)])
        c2 (text-of [(item c1)])]
    (is (< (select/estimate c0) (+ (:context print/budgets) context/last-evals-budget)))
    (is (<= (select/estimate c1) (+ (:context print/budgets) context/last-evals-budget)))
    (is (<= (- (select/estimate c2) (select/estimate c1)) 16) "reading a context of a context adds nothing")
    (is (= 1 (count (context/shown-evals (context/fold-evals [(item c0) (item c0)])))) "a repeated read is one item")))

(deftest an-unresolved-citation-says-what-to-do
  ;; task seon.task/a-task-cites-what-it-needs: a name the plan cites that resolves to
  ;; nothing tells the agent what that means and what to do, not only that it is unresolved
  (let [r (context/resolve-ref (db-with-program) 'demo/nowhere :doc)]
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
    (is (:unresolved (by "docs/nowhere.md")) "a file that is not there says so")))

(deftest an-agent-reads-its-conversation-so-far
  ;; a live DeepSeek agent re-read the same Datahike source every other turn because each
  ;; context showed only the previous turn's evaluations (2026-10-21); a REPL keeps its
  ;; scrollback. Owner, 2026-10-22: a turn is native tool calls, so each eval call's answer is
  ;; its own tool message, read before the next call
  (let [turns [{:attempt "C0" :reply "R0" :calls [{:id "a" :code "(def x 41)" :evals "E0"}]}
               {:reply "" :calls [{:id "b" :code "(inc x)" :evals "E1"} {:id "c" :code "(dec x)" :evals "E2"}]}
               {:reply "R2" :calls []}]
        ms (context/messages "H" {:task "t"} turns)]
    (is (= ["system" "user" "assistant" "tool" "assistant" "tool" "tool" "assistant" "user"] (mapv :role ms))
        "the first context, then each reply with its calls' answers, then the current state")
    (is (= [[{:id "a" :code "(def x 41)"}] "a" "E0"] [(:tool-calls (nth ms 2)) (:tool-call-id (nth ms 3)) (:content (nth ms 3))])
        "a call and its answer, named by the call's id")
    (is (= ["b" "c"] (mapv :tool-call-id (subvec ms 5 7))) "each call of a reply answered, in order")
    (is (not (contains? (nth ms 7) :tool-calls)) "a reply that called nothing carries no calls")
    (is (str/starts-with? (:content (peek ms)) "The current state") "the current state comes last"))
  (testing "a reply that called nothing is followed by a user message, so two replies never meet"
    (let [ms (context/messages "H" {:task "t"} [{:attempt "C0" :reply "R0" :calls []} {:reply "R1" :calls []}])]
      (is (= ["system" "user" "assistant" "user" "assistant" "user"] (mapv :role ms)))))
  (testing "the history budget keeps whole turns, the newest always"
    (let [turns [{:reply (apply str (repeat 400 "a")) :calls []} {:reply "R1" :calls [{:id "b" :code "(f)" :evals "E1"}]} {:reply "R2" :calls []}]]
      (is (= ["R1" "R2"] (mapv :reply (#'context/kept-turns turns 10))) "an older turn past the budget is left out whole")
      (is (= ["R2"] (mapv :reply (#'context/kept-turns turns 0))) "the newest stays at budget 0"))))
;; ---- context v2: a stable prefix, each var at its citation's depth, the helpers -----------

(defn- program-facts
  "Program facts for [sym src refs] triples: each var, the form that defines it with its
  source and the vars it names (:form/refs), and its namespace."
  [defs]
  (vec (mapcat (fn [[v src refs]]
                 [{:db/id (str v) :var/name v :var/ns (str "ns " (namespace v)) :var/form (str "form " v)}
                  (cond-> {:db/id (str "form " v) :form/id (str v " " src) :form/src src :form/ns (str "ns " (namespace v)) :form/order 0
                           :form/defines [(str v)]}
                    (seq refs) (assoc :form/refs (vec refs)))
                  {:db/id (str "ns " (namespace v)) :ns/name (symbol (namespace v))}])
               defs)))

(defn- turn-of
  "A recorded model turn n of attempt demo/f#1 with basis b: one call and its answer."
  [b n]
  {:seon.turn/id (str "demo/f#1/" n) :seon.turn/attempt "demo/f#1" :seon.turn/n n :seon.turn/origin :model
   :seon.turn/basis b :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 1
   :seon.turn/reply (str "reply " n) :seon.turn/calls [{:id (str "call-" n) :code (str "(+ " n " 1)") :evals (str (inc n))}]})

(deftest the-prefix-stays-byte-identical-across-turns
  ;; task seon.context/a-stable-prefix-is-sent-once: a provider caches a byte-identical message
  ;; prefix, so each request starts with the whole of the one before but its last message (the
  ;; turn sections), even after the program the task cites changed
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (program-facts '[[demo/f "(defn f \"Doubles.\" [x] (* 2 x))" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make ```demo/f``` triple."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (let [observed {:results {'demo-test/f-test :fail} :run-errors [] :reds [] :reasons [{:reason :proof-red :symbol 'demo-test/f-test}]}
            request (fn [n] (let [db @conn
                                  b {:commit (d/commit-id db) :task "demo/f" :attempt "demo/f#1" :turn n :max-turns 4 :origin :model :observed observed}
                                  ms (:messages (context/render db b))]
                              (d/transact conn [(turn-of b n)])
                              ms))
            r0 (request 0)
            _ (d/transact conn (program-facts '[[demo/f "(defn f \"Triples.\" [x] (* 3 x))" []]]))
            r1 (request 1)
            r2 (request 2)]
        (is (= (pop r0) (subvec r1 0 (dec (count r0)))) "turn 1 starts with turn 0's request but its turn sections")
        (is (= (pop r1) (subvec r2 0 (dec (count r1)))) "turn 2 starts with turn 1's request but its turn sections")
        (is (= (context/help-text @conn) (:content (first r0))) "the system message is the help page as main holds it")
        (is (str/starts-with? (:content (first r0)) "# The agent\n\nYou are a Seon agent") "the page, from its sections")
        (is (str/includes? (:content (second r2)) "Doubles.") "the attempt sections are the first turn's")
        (is (str/starts-with? (:content (peek r2)) "The current state") "the turn sections come last")
        (is (str/includes? (:content (peek r2)) ":turn \"3 of 4\"") "a model turn says which of its most it is")
        (let [db @conn
              read (:messages (context/render db {:commit (d/commit-id db) :task "demo/f" :attempt "demo/f#1" :turn 2 :max-turns 1
                                                  :origin :outside :observed observed}))]
          (is (not (str/includes? (:content (peek read)) ":turn")) "an outside agent's read has no turn count"))))))

(deftest each-section-is-sent-once-per-request
  ;; the old request sent the task, its references and its sections in the first turn's
  ;; context and again in the current block, every turn
  (let [db (d/db-with (db-with-program) (program-facts '[[demo/g "(defn g \"Halves.\" [x] (/ x 2))" []]]))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make ```demo/g``` exact."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        block (context/current-state db (task/task db "demo/f") {:results {'demo-test/f-test :fail} :turn 1 :max-turns 4})
        ms (context/messages "H" block [{:attempt (:content (second (context/messages "H" block))) :reply "R0" :calls [{:id "a" :code "(g 2)" :evals "1"}]}])
        times (fn [s] (count (filter #(str/includes? (str (:content %)) s) ms)))]
    (is (= 1 (times "Make ```demo/g``` exact.")) "the plan once")
    (is (= 1 (times "(defn g \\\"Halves.\\\"")) "a cited source once")
    (is (= 1 (times ":test demo-test/f-test")) "the proofs once, in the turn sections")
    (is (not (str/includes? (:content (peek ms)) ":references")) "the turn sections hold no attempt section")))

(deftest a-cited-var-shows-at-its-citations-depth
  ;; task seon.context/a-cited-var-shows-at-its-citation-depth: the delimiter picks the depth
  ;; for a var as it does for a section (docs/references.md#the-delimiter-picks-the-depth)
  (binding [*ns* *ns*]
    (eval '(ns ctx-depth))
    (eval '(defn sq "Squares x. Used by area." {:malli/schema [:=> [:cat :int] :int]} [x] (* x x)))
    (eval '(defn cube "Cubes x." {:malli/schema [:=> [:cat :int] :int]} [x] (* x x x)))
    (eval '(defn tri "Triples x." {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))))
  (let [db (d/db-with (db-with-program)
                      (program-facts '[[ctx-depth/sq "(defn sq \"Squares x. Used by area.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))" []]
                                       [ctx-depth/cube "(defn cube \"Cubes x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x x))" []]
                                       [ctx-depth/tri "(defn tri \"Triples x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))" []]]))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "d"
                                               :seon.task/plan "Build on ctx-depth/sq, `ctx-depth/cube` and ```ctx-depth/tri```."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        by (into {} (map (juxt :symbol identity)) (:references (context/current-state db (task/task db "demo/d") {:turn 0 :max-turns 4})))]
    (is (= {:symbol 'ctx-depth/sq :arglists '([x]) :summary "Squares x."} (by 'ctx-depth/sq)) "bare: arglists and the first sentence")
    (is (= {:symbol 'ctx-depth/cube :arglists '([x]) :doc "Cubes x." :contract [:=> [:cat :int] :int]} (by 'ctx-depth/cube))
        "one backtick: arglists, doc and contract")
    (is (= {:symbol 'ctx-depth/tri :source "(defn tri \"Triples x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))"} (by 'ctx-depth/tri))
        "three backticks: the source")))

(deftest a-source-over-the-cap-says-what-it-left-and-how-to-read-it
  (binding [*ns* *ns*]
    (eval '(ns ctx-big))
    (eval '(defn big "Does much." {:malli/schema [:=> [:cat] :int]} [] 1)))
  (let [src (str "(defn big \"Does much.\" [] " (str/join " " (repeat 4000 "(inc 1)")) ")")
        db (d/db-with (db-with-program) (program-facts [['ctx-big/big src []]]))
        r (context/resolve-ref db 'ctx-big/big :code)]
    (is (< context/reference-tokens (select/estimate src)) "the source is over the cap")
    (is (= "Does much." (:doc r)) "the doc instead")
    (is (nil? (:source r)) "no source cut short")
    (is (= '(clojure.repl/source ctx-big/big) (:read r)) "the form that reads the rest")
    (is (str/includes? (:left-out r) (str (select/estimate src) " tokens")) "and how much it left out")))

(deftest found-names-the-helpers-the-plan-does-not
  ;; task seon.context/the-context-names-the-helpers-a-task-needs: R4's lever, from the call
  ;; graph: what the cited vars and the proofs call, most-called first, the named left out
  (let [helpers (for [i (range 3)] (symbol "geo" (str "h" i)))
        db (d/db-with (ts/test-db)
                      (program-facts (concat [['geo/area "(defn area [r] (* pi (sq r)))" (into '[geo/sq geo/pi clojure.core/* geo/defg geo/wrap geo/plumb] helpers)]
                                              ['geo/defg "(defmacro defg [n] `(wrap ~n))" '[geo/wrap]]
                                              ['geo/wrap "(defn wrap [x] x)" []]
                                              ['geo/plumb "(defn plumb [f] f)" []]
                                              ['geo/vol "(defn vol [r h] (* h (sq r)))" '[geo/sq geo/area geo/plumb]]
                                              ['geo/sq "(defn sq [x] (* x x))" []]
                                              ['geo/pi "(def pi 3.14)" []]
                                              ['geo-test/vol-test "(deftest vol-test (is (= 2 (vol 1 1))))" '[geo/vol geo/pi geo/plumb]]]
                                             (for [h helpers] [h (str "(defn " (name h) " [] 0)") []])
                                             ;; plumb is the program's plumbing: as many more forms name it as the
                                             ;; seed's program holds (seon.contract/checked: 864 of about 1,700)
                                             (for [i (range (max 8 (count (d/datoms (ts/test-db) :aevt :form/id))))]
                                               [(symbol "geo" (str "u" i)) (str "(defn u" i " [] (plumb " i "))") '[geo/plumb]]))))
        db (d/db-with db [{:var/name 'geo/defg :var/kind :macro}])
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'geo :seon.task/name "v" :seon.task/plan "Make `geo/vol` right; see `geo/area`."
                                               :seon.task/proofs #{'geo-test/vol-test}}]))
        fs (:found (context/current-state db (task/task db "geo/v") {:turn 0 :max-turns 4}))]
    (is (= ['geo/pi 'geo/sq 'geo/h0 'geo/h1 'geo/h2] (mapv :symbol (butlast fs)))
        "pi (called by area and the proof) and sq (by area and vol) first, then the most specific; area and vol are named")
    (is (= "+2 more: (my.agent/callees 'ns/f) of each cited var" (last fs))
        "what it left and how to read it: wrap and plumb (called by three of the sources, and across the program) rank after the specific helpers")
    (is (not-any? #{'geo/defg} (map :symbol (butlast fs))) "a macro is no helper")
    (is (not-any? #{'clojure.core/*} (map :symbol (butlast fs))) "only program functions")))

(deftest the-turn-names-what-the-merge-will-carry
  ;; owner D4 (2026-10-22): the branch's divergence from main, compact, every turn; it changes
  ;; as the agent works, so it is turn content, never the cached prefix
  (let [db (db-with-program)
        will {:added ['demo/h] :changed ['demo/f] :schemas [:demo/k]}
        ms (context/messages "H" (context/current-state db (task-f db) {:turn 1 :max-turns 4 :will-merge will}))]
    (is (str/includes? (:content (peek ms)) ":will-merge") "in the turn sections")
    (is (str/includes? (:content (peek ms)) "demo/h"))
    (is (not (str/includes? (:content (second ms)) ":will-merge")) "not in the attempt sections")
    (is (= {} (:will-merge (context/current-state db (task-f db) {:turn 1 :max-turns 4 :will-merge {}})))
        "measured and nothing to carry says so")
    (is (not (contains? (context/current-state db (task-f db) {:turn 1 :max-turns 4}) :will-merge))
        "not measured (a basis from before it was) is no section, never an empty one")))

(deftest unresolved-names-share-one-problem
  ;; a measured context (2026-10-22) repeated the same 280-character problem under each name
  ;; that resolves to nothing; it is said once, beside every such name
  (let [db (db-with-program)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "u" :seon.task/plan "Use `demo/f`, `demo/x` and `demo/y`."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        refs (:references (context/current-state db (task/task db "demo/u") {:turn 0 :max-turns 4}))]
    (is (= ['demo/f] (keep :symbol refs)) "the resolved name as itself")
    (is (= {:unresolved ['demo/x 'demo/y] :problem (:unresolved seon.ref/problem-messages)} (last refs)) "the others, one problem")))
