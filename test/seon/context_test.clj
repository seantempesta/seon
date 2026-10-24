(ns seon.context-test
  "The agent's current-state block says each thing once: a passing proof is its status, a
  red one its source and its failures (expected and actual), a reference already shown as a
  proof is not shown again, a merge task's plan stays its own."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task] [seon.context :as context] [seon.print :as print]
            [seon.reader :as reader] [seon.proofs :as proofs] [seon.agent :as agent] [seon.docs :as docs]
            [seon.print.select :as select] [clojure.string :as str] [seon.edn] [seon.print-test :as print-test] [seon.repl :as repl] [seon.model]))

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
                                           :test-run-errors ["namespace app-test did not load: Unable to resolve symbol: g"]})]
    (testing "every reason but the task's own red proof (shown with the proofs), each with what explains it"
      (is (= [{:reason :regression :test 'demo-test/g-test :failures [{:assert "(= 1 (g))" :got "(= 1 0)"}]}
              {:reason :stray-red :test 'user/scratch-test}
              {:reason :contract-gap :vars ['demo/helper]}]
             (:refused block))))
    (testing "a run that failed as a whole is said, not read as tests not run"
      (is (= {:failed ["namespace app-test did not load: Unable to resolve symbol: g"]} (:test-run block)))
      (is (= ["namespace app-test did not load: Unable to resolve symbol: g" "test process died: 9"]
             (proofs/test-run-errors {:reports [{:type :error :message "namespace app-test did not load: Unable to resolve symbol: g"}
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
    (is (= [{:assert "(= 9 (square 3))" :got "(= 9 6)"}] (:failures (by 'ctx-calc-test/square-test)))
        "the assertion once, and what it evaluated to")
    (is (= [{:var 'ctx-calc/square :contract [:=> [:cat :int] :int] :doc "Squares x."}
            {:var 'ctx-ghost/half :contract [:=> [:cat :int] :int]}]
           (:calls (by 'ctx-calc-test/square-test)))
        "the program functions the red proof calls, at summary level from their facts: one in a namespace never loaded too")
    (is (= {:test 'ctx-calc-test/cube-test :status :undefined} (by 'ctx-calc-test/cube-test))
        "a proof not written yet says so")
    (is (= :no-assertions (:status (by 'ctx-calc-test/empty-test))) "a proof that ran and asserted nothing says so")
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
    (is (:unresolved (by "docs/nowhere.md")) "a file that is not there says so")))

(deftest an-agent-reads-its-conversation-so-far
  ;; a live DeepSeek agent re-read the same Datahike source every other turn because each
  ;; context showed only the previous turn's evaluations (2026-10-21); a REPL keeps its
  ;; scrollback. Each eval renders its own part (its :seon.render/ai): the assistant message with
  ;; the call, then the response as a tool message; the provider's format folds a reply's calls
  ;; into one assistant message (notes/design-agent-view-2026-10-24.md §3, the request is a render)
  (let [ev (fn [id call prose text] {:seon.eval/id id :seon.eval/by "a1" :seon.eval/record (cond-> {:code (str "(" id ")") :text text :call-id (str "c-" id) :call call} prose (assoc :prose prose))})
        ms (into [] (mapcat #(context/eval-messages % {})) [(ev "e1" 0 "R0" "E1") (ev "e2" 0 nil "E2") (ev "e3" 1 nil "E3")])]
    (is (= ["assistant" "tool" "assistant" "tool" "assistant" "tool"] (mapv :role ms)) "each eval its call and its response")
    (is (= {:role "assistant" :content "R0" :tool-calls [{:id "c-e1" :code "(e1)"}] :call 0} (first ms)))
    (is (= {:role "tool" :tool-call-id "c-e1" :content "E1"} (second ms)))
    (let [wire (seon.model/wire-messages true ms)]
      (is (= ["assistant" "tool" "assistant" "tool" "tool"] (mapv :role wire)) "a reply's two calls are one assistant message")
      (is (= ["c-e2" "c-e3"] (mapv :id (:tool_calls (nth wire 2)))))
      (is (= ["c-e2" "c-e3"] (mapv :tool_call_id (subvec wire 3 5))) "each call answered, in order")))
  (testing "a user's form is one user message carrying its response"
    (is (= [{:role "user" :content "{:by \"user\" :code \"(+ 1 2)\" :response {:handle result/e4, :value 3}}"}]
           (context/eval-messages {:seon.eval/by "user" :seon.eval/record {:code "(+ 1 2)" :text "{:handle result/e4, :value 3}"}} {}))))
  (testing "the history budget keeps whole records, the newest always, never starting inside a reply"
    (let [part (fn [c call] [{:role "assistant" :content (str c " " (apply str (repeat 30 "x"))) :call call}])
          parts [(part (apply str (repeat 400 "a")) 0) (part "R1" 0) (part "R2" 1) (part "R3" 0)]
          names (fn [ps] (mapv #(subs (:content (first %)) 0 2) ps))
          one (select/estimate (:content (first (part "R1" 0))))]
      (is (= [["R1" "R2" "R3"] 1] [(names (first (#'context/kept parts (* 3 one)))) (second (#'context/kept parts (* 3 one)))]))
      (is (= ["R3"] (names (first (#'context/kept parts 0)))) "the newest stays at budget 0")
      (is (= ["R3"] (names (first (#'context/kept (subvec parts 2) (* 2 one))))) "a cut never starts inside a reply"))))
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
  "A recorded model turn n of run demo/f#1 with basis b: one call and its answer."
  [b n]
  {:seon.turn/id (str "demo/f#1/" n) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/n n :seon.turn/origin :model
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
      (let [observed {:results {'demo-test/f-test :fail} :test-run-errors [] :reds [] :reasons [{:reason :proof-red :symbol 'demo-test/f-test}]}
            request (fn [n] (let [db @conn
                                  b {:commit (d/commit-id db) :task "demo/f" :run "demo/f#1" :turn n :max-turns 4 :origin :model :observed observed}
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
        (is (str/includes? (:content (second r2)) "Doubles.") "the run sections are the first turn's")
        (is (str/starts-with? (:content (peek r2)) "The current state") "the turn sections come last")
        (is (str/includes? (:content (peek r2)) ":turn \"3 of 4\"") "a model turn says which of its most it is")
        (let [db @conn
              read (:messages (context/render db {:commit (d/commit-id db) :task "demo/f" :run "demo/f#1" :turn 2 :max-turns 1
                                                  :origin :outside :observed observed}))]
          (is (not (str/includes? (:content (peek read)) ":turn")) "an outside agent's read has no turn count"))))))

(deftest each-section-is-sent-once-per-request
  ;; the old request sent the task, its references and its sections in the first turn's
  ;; context and again in the current block, every turn
  (let [db (d/db-with (db-with-program) (program-facts '[[demo/g "(defn g \"Halves.\" [x] (/ x 2))" []]]))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make ```demo/g``` exact."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
        block (context/current-state db (task/task db "demo/f") {:results {'demo-test/f-test :fail} :turn 1 :max-turns 4})
        ms (context/messages "H" block)
        times (fn [s] (count (filter #(str/includes? (str (:content %)) s) ms)))]
    (is (= 1 (times "Make ```demo/g``` exact.")) "the plan once")
    (is (= 1 (times "(defn g \\\"Halves.\\\"")) "a cited source once")
    (is (= 1 (times ":test demo-test/f-test")) "the proofs once, in the turn sections")
    (is (not (str/includes? (:content (peek ms)) ":references")) "the turn sections hold no run section")))

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
        r (seon.docs/resolve-ref db 'ctx-big/big :code)]
    (is (< seon.docs/reference-tokens (select/estimate src)) "the source is over the cap")
    (is (= "Does much." (:doc r)) "the doc instead")
    (is (nil? (:source r)) "no source cut short")
    (is (= '(clojure.repl/source ctx-big/big) (:read r)) "the form that reads the rest")
    (is (str/includes? (:left-out r) (str (select/estimate src) " tokens")) "and how much it left out")))

;; a made unit vector, dim i weighted as a Matryoshka model's are (seon.vector-test/vectors)
(defn- made-vector [seed]
  (let [v (mapv (fn [i] (* (Math/exp (/ (- i) 96.0)) (Math/sin (* (inc seed) (inc i) 0.37)))) (range 768))
        n (Math/sqrt (reduce + (map #(* % %) v)))]
    (mapv #(/ % n) v)))

(deftest similar-finds-a-helper-the-call-graph-misses
  ;; task seon.context/similar-finds-helpers-by-embedding: a helper nothing cited calls is found by
  ;; its meaning, the program form nearest the plan, fused with the call graph's own
  (let [plan (made-vector 1)
        near-plan (let [v (mapv + plan (map #(* 0.1 %) (made-vector 2)))
                        n (Math/sqrt (reduce + (map #(* % %) v)))] (mapv #(/ % n) v))
        db (d/db-with (ts/test-db)
                      (program-facts [['geo/vol "(defn vol [r h] (* h (sq r)))" '[geo/sq]]
                                      ['geo/sq "(defn sq [x] (* x x))" []]
                                      ['geo/clamp-radius "(defn clamp-radius [r] (max 0 r))" []]
                                      ['geo/unrelated "(defn unrelated [] :x)" []]
                                      ['geo-test/vol-test "(deftest vol-test (is (= 2 (vol 1 1))))" '[geo/vol]]]))
        form (fn [db sym] (:db/id (:var/form (d/entity db [:var/name sym]))))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'geo :seon.task/name "v" :seon.task/plan "Make `geo/vol` refuse a negative radius."
                                               :seon.task/proofs #{'geo-test/vol-test}}]))
        task-e (:db/id (d/entity db [:seon.task/id "geo/v"]))
        found-of (fn [db] (mapv :symbol (filter map? (:found (context/current-state db (task/task db "geo/v") {:turn 0 :max-turns 4})))))
        _ (is (= ['geo/sq] (found-of db)) "without vectors the call graph names only what vol calls")
        db (d/db-with db (seon.vector/tx-data [task-e (form db 'geo/clamp-radius) (form db 'geo/unrelated) (form db 'geo/sq)] [1 1 1 1]
                                              [plan near-plan (made-vector 7) (made-vector 9)] [10 10 10 10] "m"))
        fs (found-of db)]
    (is (some #{'geo/clamp-radius} fs) "the helper nearest the plan is found, though nothing cited calls it")
    (is (some #{'geo/sq} fs) "the call graph's helper stays")
    (is (= ['geo/clamp-radius 'geo/unrelated] (filterv #{'geo/clamp-radius 'geo/unrelated} fs)) "nearer the plan ranks first")
    (is (not-any? #{'geo/vol 'geo-test/vol-test} fs) "what the plan names is no helper")))

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
    (is (= "+2 more: (my.agent/callees 'ns/f) of each cited var, (my.agent/similar \"text\")" (last fs))
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
    (is (not (str/includes? (:content (second ms)) ":will-merge")) "not in the run sections")
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

(deftest an-owners-message-reaches-the-next-turn
  ;; owner, 2026-10-23 (parity row 73): the page's message bar sends the owner's words through
  ;; root's delivery; the turn that takes them writes the message fact on its branch, so the
  ;; conversation holds it in tx order (seon.context/records), rendered by the message fact's
  ;; own :seon.render/ai (notes/design-agent-view-2026-10-24.md §3)
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
            :seon.error/problems [{:path [0] :in [0] :expected :keyword :value nil}]}
        text (repl/printed {:handle 'result/e1 :ex em})]
    (is (str/includes? text ":expected :keyword") text)
    (is (str/includes? text "Fix seon.e2e/agents-reused") text)
    (is (= 1 (count (re-seq #"its value does not satisfy" text))) "the message once")))

(deftest a-turns-reasoning-goes-back-with-its-reply
  ;; DeepSeek's thinking mode with tools needs each earlier reply's reasoning_content sent back
  ;; (a 400 otherwise, measured: notes/step0/no-replay.edn): the reply's first eval carries it,
  ;; exactly as returned, and the provider's format sends it unless replay-reasoning is false
  (let [ms (context/eval-messages {:seon.eval/by "a1" :seon.eval/record {:code "(f 3)" :text "9" :call-id "c1" :call 0 :prose "f triples." :reasoning "Square it first."}} {})
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

(deftest a-turn-says-what-it-ran-what-the-judge-and-the-merge-run
  ;; root's ruling (a), 2026-10-22: a turn runs the proofs and the tests the branch changed;
  ;; the judge runs the reached in-process set at the run's end; the merge runs the
  ;; process tests. The turn says each, and what runs the rest
  (let [db (db-with-program)
        b (context/current-state db (task-f db) {:results {} :turn 1 :max-turns 4
                                                  :tests {:this-turn 2 :at-judge 40 :when-asked 3 :unfinished 1}})]
    (is (= {:this-turn 2 :at-judge 40 :when-asked 3 :unfinished 1
            :unfinished-run '(my.agent/prove) :judge-runs '(my.agent/prove) :root-runs '(my.agent/deferred)}
           (:tests b)))
    (is (nil? (:tests (context/current-state db (task-f db) {:results {} :turn 1 :max-turns 4}))) "not measured: nothing said")))

(deftest the-map-names-each-cited-namespaces-functions
  ;; root's research, lever 1 (aider's ranked repo map; v1 R4: naming the helpers took 7/32
  ;; green to 22/24): the run shows the functions of the namespaces its proofs and cited
  ;; vars live in, each a line (arglists, first sentence), most referenced first, within its
  ;; token budget, and the form that lists the rest. Step E's runs 1-5: designs spent 15-25
  ;; turns reading sources one pair at a time before writing anything
  (let [db (db-with-program)
        m (context/ns-map db '[demo/f demo-test/f-test])]
    (is (= '[demo demo-test] (mapv :ns m)))
    (is (= '[demo/f] (mapv :symbol (:vars (first m)))))
    (is (= "Doubles." (:summary (first (:vars (first m))))))
    (is (= '(clojure.repl/dir demo) (:all (first m))))
    (is (= '#{demo-test/f-test demo-test/g-test} (set (map :symbol (:vars (second m))))))))

(deftest the-map-shows-the-most-used-first-then-by-name
  ;; each var's uses are counted once and sorted with its name (a ranking takes each key once):
  ;; the order is uses descending, the name breaking a tie
  (let [db (db-with-program)
        ne (fn [db n] (pdb/eid db :ns/name n))
        db (d/db-with db (vec (for [v '[rank/d rank/c rank/b rank/a]
                                    x [{:db/id (str v) :var/name v :var/ns (str "ns rank") :var/form (str "form " v)}
                                       {:db/id (str "form " v) :form/id (str v) :form/src (str "(defn " (name v) " [])") :form/ns "ns rank" :form/order 0 :form/defines [(str v)]}
                                       {:db/id "ns rank" :ns/name 'rank}]]
                                x)))
        db (d/db-with db (vec (for [[i refs] (map-indexed vector '[[rank/c rank/b] [rank/c] [rank/c rank/a] [rank/d]])]
                                {:form/id (str "user " i) :form/src "(use)" :form/ns (ne db 'demo) :form/order (inc i) :form/refs refs})))]
    (is (= '[rank/c rank/a rank/b rank/d] (mapv :symbol (:vars (first (context/ns-map db '[rank/a])))))
        "c named by three forms, then a, b and d by one each, by name")))

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
    (is (not (clojure.string/includes? (str (:references block)) "demo.other/g")) "and not an unresolved name")))


(deftest a-taken-message-is-read-from-its-fact
  ;; root's ruling (2026-10-23): a message is a fact. Root writes the owner's on main; the turn
  ;; that takes it writes the same fact on its branch and names it in its basis by id
  (let [db (d/db-with (db-with-program) [{:seon.message/id "m-1" :seon.message/from "owner" :seon.message/to {:seon.run/id "demo/f#1"}
                                          :seon.message/body "use demo/g, not a new helper"}])]
    (is (= [{:id "m-1" :from "owner" :body "use demo/g, not a new helper"}] (context/taken-messages db {:message-ids ["m-1"]})))))
(defn- sent-turns
  "A run of two model turns as seon.turn records them (owner's ruling 2026-10-24: each turn
  records what it sent as refs, :seon.turn/sent), with the task's plan changed after turn 0 and
  again after turn 1. Answers [db [r0 r1] [e0 e1]]: the requests' messages as sent, and the turns."
  [conn]
  (wiki! conn)
  (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
  (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                           :seon.task/proofs #{'demo-test/f-test}}]))
  (let [observed {:results {'demo-test/f-test :fail} :test-run-errors [] :reds [] :reasons []}
        ev! (fn [id by code text & [more]] (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "b/a1"}
                                                              :seon.eval/record (merge {:code code :text text :run "demo/f#1"} more)}]))
        turn! (fn [n screen more]
                (let [db @conn
                      b {:commit (d/commit-id db) :task "demo/f" :run "demo/f#1" :turn n :max-turns 4
                         :origin :model :observed observed :screen screen :agent "b/a1"}
                      {:keys [messages sent]} (context/render db b)]
                  (d/transact conn [(merge {:seon.turn/id (str "demo/f#1/" n) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/n n
                                            :seon.turn/origin :model :seon.turn/basis b :seon.turn/sent sent :seon.turn/forms 1
                                            :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 1}
                                           more)])
                  messages))
        plan! (fn [p] (d/transact conn [{:seon.task/id "demo/f" :seon.task/plan p}]))
        _ (ev! "e1" "screen" "(my.task/status)" "{:handle result/e1, :value :red}")
        r0 (turn! 0 ["e1"] {})
        _ (ev! "e2" "a1" "(f 1)" "{:handle result/e2, :value 1}" {:call-id "call-0" :call 0 :prose "I call f." :reasoning "why"})
        _ (plan! "Make f triple.")
        _ (ev! "e3" "screen" "(my.task/status)" "{:handle result/e3, :value :green}")
        r1 (turn! 1 ["e3"] {})
        _ (plan! "Later.")
        ;; and the help page changes: a turn's system prompt is its section as of the turn's t
        _ (d/transact conn [{:db/id (seon.docs/page-top @conn context/help-page) :seon.section/text "Changed after the turns."}])
        db @conn
        e-of (fn [id] (d/q '[:find ?e . :in $ ?id :where [?e :seon.turn/id ?id]] db id))]
    [db [r0 r1] [(e-of "demo/f#1/0") (e-of "demo/f#1/1")]]))

(deftest a-turn-records-what-it-sent
  ;; task seon.context/a-turn-records-what-it-sent (A): a turn records the records its request
  ;; rendered, in order; read back, each by its own render as of the turn (the help page's
  ;; section and the task as of their t), they are the messages it sent, though the task changed
  ;; after it: nothing is built again
  (ts/test-branch!
    (fn [_ conn]
      (let [[db [r0 r1] [e0 e1]] (sent-turns conn)
            sent #(:seon.turn/sent (d/pull db [:seon.turn/sent] %))]
        (is (= [:section :task :eval] (mapv first (sent e0))) "turn 0: the help page, the task story, the screen eval")
        (is (every? pos-int? (map second (take 2 (sent e0)))) "the help page's own section and the task, by entity")
        (is (= [:section :task :eval :eval] (mapv first (sent e1))) "turn 1: then its call's eval, then the screen eval")
        (is (= (second (sent e0)) (second (sent e1))) "the task story is the run's first turn's: the same task as of the same t")
        (is (= r0 (context/sent-messages db e0)) "turn 0's messages, read from its records")
        (is (= r1 (context/sent-messages db e1)) "turn 1's, though the plan changed after both")
        (is (not (str/includes? (str (context/sent-messages db e1)) "Later.")) "the task as it was sent, not as it is")
        (is (str/includes? (context/help-text db) "Changed after the turns.") "the help page changed after them")
        (is (not (str/includes? (str (context/sent-messages db e0)) "Changed after the turns.")) "the help page as it was sent")))))

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

(deftest the-request-is-a-conversation-of-evals-then-the-screen
  ;; notes/design-agent-view-2026-10-24.md §1: each turn's prose and eval calls with their
  ;; responses as tool messages (read from the eval records), the reasoning as returned, then
  ;; the screen evals, last, replaced by the next request's; the prefix stays byte-identical
  (ts/test-branch!
    (fn [_ conn]
      (wiki! conn)
      (d/transact conn (program-facts '[[demo/f "(defn f [x] x)" []]]))
      (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make f."
                                               :seon.task/proofs #{'demo-test/f-test}}]))
      (let [observed {:results {'demo-test/f-test :fail} :test-run-errors [] :reds [] :reasons []}
            ev! (fn [id by code text & [more]] (d/transact conn [{:seon.eval/id id :seon.eval/by by :seon.eval/agent {:seon.agent/key "b/a1"}
                                                                  :seon.eval/record (merge {:code code :text text :run "demo/f#1"} more)}]))
            request (fn [n screen] (let [db @conn
                                         b {:commit (d/commit-id db) :task "demo/f" :run "demo/f#1" :turn n :max-turns 4
                                            :origin :model :observed observed :screen screen :agent "b/a1"}]
                                     [b (:messages (context/render db b))]))
            _ (ev! "e1" "screen" "(my.task/status)" "{:handle result/e1, :value :red}")
            [b0 r0] (request 0 ["e1"])
            _ (ev! "e2" "a1" "(f 1)" "{:handle result/e2, :value 1}" {:call-id "call-0" :call 0 :prose "I call f." :reasoning "why"})
            _ (d/transact conn [{:seon.turn/id "demo/f#1/0" :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/n 0 :seon.turn/origin :model
                                 :seon.turn/basis b0 :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 1
                                 :seon.turn/reply "I call f." :seon.turn/reasoning "why" :seon.turn/calls [{:id "call-0" :code "(f 1)" :eval "e2"}]}])
            _ (ev! "e3" "screen" "(my.task/status)" "{:handle result/e3, :value :green}")
            [_ r1] (request 1 ["e3"])]
        (is (= ["system" "user" "user"] (mapv :role r0)) "turn 0: the task, then the screen")
        (is (= ["system" "user" "assistant" "tool" "user"] (mapv :role r1)) (pr-str (mapv :role r1)))
        (is (= {:role "assistant" :content "I call f." :reasoning "why" :tool-calls [{:id "call-0" :code "(f 1)"}] :call 0} (nth r1 2))
            "prose as content, the reasoning as returned, the call")
        (is (= "{:handle result/e2, :value 1}" (:content (nth r1 3))) "the call's response, as recorded")
        (is (= (pop r0) (subvec r1 0 2)) "the prefix carries over")
        (is (= "{:by \"screen\" :code \"(my.task/status)\" :response {:handle result/e3, :value :green}}" (:content (peek r1)))
            "the screen eval, last")
        (is (not-any? #(str/includes? (str (:content %)) ":value :red") r1) "the earlier screen is gone")
        (is (not-any? #(str/includes? (str (:content %)) ";;") (rest r1)) "no ;; reaches the model")))))

(deftest an-assertion-message-of-any-value-is-kept-as-text
  ;; clojure.test's is takes any message (a doseq's keyword, say); a run whose test reported one
  ;; ended on seon.proofs/failures' contract (dbg/live#4, 2026-10-24) until the report kept it as text
  (let [rep (agent/test-report {:type :fail :message :scripted :expected '(= 1 2) :actual '(not (= 1 2))} "#'demo-test/t")]
    (is (= ":scripted" (:message rep)))
    (is (= [":scripted"] (map :message (get (proofs/failures {:reports [rep]}) 'demo-test/t))))))
