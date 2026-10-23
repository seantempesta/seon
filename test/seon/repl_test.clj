(ns seon.repl-test
  "An agent's REPL answers: asked of an agent in a root of its own (seon.agent-test/in-agent), and a form bounded by the work it does, never by a clock (seon.intr/run under a tick budget)."
  (:require [clojure.test :refer [deftest is testing]] [clojure.string :as str] [seon.agent-test :as at]
            [seon.intr :as intr] [seon.agent :as agent] [seon.contract :as contract]
            [seon.ref :as ref] [seon.template :as template] [seon.repl :as repl] [seon.context :as context] [seon.docs :as docs]
            [seon.config :as cfg] [seon.test-store :as ts] [datahike.api :as d]))

(deftest an-agent-repl-answers-what-a-clojure-repl-answers
  ;; owner, 2026-10-21: "Whatever intuitions agents have we need to make sure we are matching a
  ;; real clojure repl as much as possible AND improving on it". Each row is what a Clojure
  ;; user types at clojure.main's REPL (clojure.main/repl-requires refers source, apropos,
  ;; dir, pst, doc, find-doc, pp, pprint) and what it answers, in an agent's own namespace;
  ;; the improvements: source reads the agent's own redefinitions and Seon's program (task
  ;; seon.repl/source-reads-the-program: a compiled platform var's form from the program facts,
  ;; a library var's from its :file and :line; a live agent spent all 8 turns on
  ;; (source datahike.connector/connect), 2026-10-21).
  (let [rows [["(source seon.reconcile/root-causes)" :out "(defn root-causes"]
              ["(clojure.repl/source-fn 'seon.reconcile/root-causes)" :printed "(defn root-causes"]
              ["(do (require 'datahike.connector) (source datahike.connector/connect))" :out "(defn connect"]
              ["(defn twice [x] (* 2 x))" :printed "twice"]
              ["(source twice)" :out "(defn twice [x] (* 2 x))"]
              ["(doc twice)" :out "twice"]
              ["(dir clojure.string)" :out "join"]
              ["(apropos \"index-of\")" :printed "index-of"]
              ["(+ 1 2)" :printed "3"]
              ["*1" :printed "3"]
              ["(pprint {:a 1})" :out "{:a 1}"]
              ["(/ 1 0)" :printed ""]
              ["(some? *e)" :printed "true"]
              ["(pst)" :out "Divide by zero"]
              ["(in-ns 'seon.reconcile)" :printed "seon.reconcile"]
              ["(clojure.repl/source root-causes)" :out "(defn root-causes"]
              ;; a namespace's ns form, its requires and aliases (step E's run 3: the real design
              ;; asked for seon.problems-test/ns 18 turns running, to write its deftest)
              ["(my.schema/forms :seon.attempt/status)" :printed ":enum"]
              ["(clojure.repl/source seon.reconcile)" :out "(ns seon.reconcile"]
              ["(clojure.repl/source seon.reconcile/ns)" :out "(source seon.reconcile)"]
              ["(clojure.repl/source seon.reconcile/not-yet)" :out "no such var is defined"]
              ;; clojure.test reports to *test-out*: the step E dry run's agent ran a red
              ;; proof and saw nothing (2026-10-22)
              ["(do (in-ns 'user) (clojure.test/deftest red (clojure.test/is (= 1 2))) (clojure.test/run-tests))" :out "FAIL in (red)"]
              ;; a value its producer names the schema of reads by its declared render, and
              ;; shows whole as data without that metadata; a map that only holds a key is data
              ["(my.task/current)" :printed "task demo/t · work"]
              ["(my.task/current)" :printed "the whole value as data: (with-meta result/h"]
              ["(:seon.task/id (with-meta (my.task/current) nil))" :printed "\"demo/t\""]
              ["(pr-str (keys (with-meta (my.task/current) nil)))" :printed ":seon.task/plan"]
              ["{:seon.task/id \"x/y\" :n 1}" :printed ":n 1"]
              ["(seon.contract/error :seon.root/refused 'a/b \"m\")" :printed "#:seon.error{:type :seon.root/refused"]]
        items (at/in-agent (mapv first rows))]
    (doseq [[[form k expected] item] (map vector rows items)]
      (is (str/includes? (str (get item k)) expected) (str form " -> " (pr-str item))))))

(deftest a-reply-item-carries-values-not-their-printed-text
  ;; convergence item 9: the middleware answer under the item's own keys, so nothing is
  ;; printed and read back in the agent. The REPL's own handler in this process, as a
  ;; scratch session (not agent mode: nothing is transacted)
  (let [answers (volatile! [])
        old @agent/state]
    (reset! agent/state {:n 0 :ns (create-ns 'seon.repl-test.scratch)})
    (try ((repl/handler) {"op" "eval" "code" "(defn uncontracted-g [x] x) (/ 1 0)" :reply #(vswap! answers conj %)})
         (finally (reset! agent/state old)))
    (let [[d e] (repl/fold @answers)]
      (is (= [:ret :err] (mapv :tag [d e])) (pr-str @answers))
      (is (= [0 1] (mapv :id [d e])))
      (is (= :h0 (:handle d)))
      (is (= :uncontracted-definition (:seon.repl/finding (first (:warnings d)))) "the warning, a map")
      (is (= ['seon.repl-test.scratch/uncontracted-g] (:uncontracted (first (:warnings d)))))
      (is (every? number? (map :ms [d e])))
      (is (= :seon.agent/eval-error (:seon.error/type (:err e))) "the error map itself")
      (testing "its text is the error's render: the message first, then its type and where it was raised"
        (is (str/starts-with? (:printed e) (:seon.error/message (:err e))))
        (is (str/includes? (:printed e) "(seon.agent/eval-error in seon.repl/eval-code")))
      (is (str/includes? (str (:err-text e)) "Divide by zero") (pr-str e)))))

(defn- runnable
  "The forms text tells an agent to run, in order: each single-backticked code span that is a
  form (a double-backticked one is a pattern to fill in, never run)."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [text]
  (vec (for [{:seon.ref/keys [code width]} (:seon.ref/spans (ref/scan text))
             :when (and (= 1 width) (str/starts-with? code "("))]
         code)))

(deftest the-template-instructions-run
  ;; each form an instruction tells an agent to run must work in a real agent (how every
  ;; agent moves around the system is the help page's: the-help-page-runs). One agent,
  ;; reading its context first (as a model's turn does, which makes the my.* functions act on
  ;; its task), runs every kind's forms in order; the ending functions come last
  (let [forms (into [] (comp (mapcat (comp runnable val)) (distinct)) (sort-by key template/instructions))
        ends #{"(my.turn/wait)"}
        forms (concat (remove #(or (ends %) (str/starts-with? % "(my.task/fail!")) forms)
                      (filter #(str/starts-with? % "(my.task/fail!") forms)
                      (filter ends forms))
        cited "(seon.task/citation-problems (my.db/db) (vec (for [[k v] seon.template/instructions] {:seon.task/ns 'demo :seon.task/name (name k) :seon.task/plan \"\" :seon.task/instructions v})))"
        [problems & items] (rest (at/in-agent (into [{:op :context :agent "a1"} cited] forms)))]
    (is (= "[]" (:printed problems)) (str "every name the instructions cite resolves in the program: " (pr-str problems)))
    (is (some #{"(my.branch/unmerged)"} forms) "merge's own: the entries it resolves")
    (doseq [[form item] (map vector forms items)]
      (is (not= :err (:tag item)) (str form " -> " (pr-str item))))
    (is (= (count forms) (count items)) "every form answered")))

(deftest the-help-page-runs
  ;; task seon.context/help-is-a-checked-docs-page (v1's help was tested data, its most-proven
  ;; turn saver): every form the help page tells an agent to run works in a real agent, in
  ;; order, the ending functions last, and every name it cites resolves in the program
  (let [forms (runnable (docs/page-text (let [db0 (ts/test-db)] (d/db-with db0 (docs/load-tx db0 "docs" (docs/pages (cfg/checkout) "docs"))))
                                       context/help-page))
        end? #(or (= "(my.turn/wait)" %) (str/starts-with? % "(my.task/fail!"))
        forms (concat (remove end? forms) (sort-by #(= "(my.turn/wait)" %) (filter end? forms)))
        cited (str "(seon.task/citation-problems (my.db/db) [{:seon.task/ns 'demo :seon.task/name \"help\" :seon.task/plan (seon.context/help-text (my.db/db))}])")
        [problems & items] (rest (at/in-agent (into [{:op :context :agent "a1"} cited] forms)))]
    (is (= "[]" (:printed problems)) (str "every name the help page cites resolves in the program: " (pr-str problems)))
    (is (< 20 (count forms)) "the page shows the agent how to work")
    (doseq [[form item] (map vector forms items)]
      (is (not= :err (:tag item)) (str form " -> " (pr-str item))))
    (is (= (count forms) (count items)) "every form answered")
    (let [by (zipmap forms items)
          printed #(str (:printed (by %)))]
      ;; the navigation functions answer from the program facts the branch records
      (is (str/includes? (printed "(my.agent/callers 'seon.task/accumulate)") "seon.task/tasks") "callers, from :form/refs")
      (is (str/includes? (printed "(my.agent/callees 'seon.task/accumulate)") "clojure.core/reduce") "callees, from its form's :form/refs")
      (is (str/includes? (printed "(my.agent/grep \"accumulated\")") "seon.task/accumulate") "grep, over :form/src")
      (is (str/includes? (printed "(get (my.schema/forms) :seon.task/task)") ":seon.task/own") "a schema's form")
      (is (str/includes? (printed "(my.schema/register! :app.workout/set [:map [:reps :int] [:kg :double]])") ":app.workout/set") "a new schema registers")
      (is (str/includes? (printed "(my.agent/help)") "docs/agent.md#done") "help lists the page's sections, from the branch's db")
      (is (str/includes? (printed "(my.agent/help \"done\")") "The judge counts exactly") "and one section's text"))))

(deftest help-answers-a-section
  ;; the help page is read from the wiki's sections main holds (root's boot loads them), at
  ;; the commit a turn read, through seon.docs's one reader
  (let [db0 (ts/test-db)
        db (d/db-with db0 (docs/load-tx db0 "docs" (docs/pages (cfg/checkout) "docs")))
        toc (docs/page db context/help-page)]
    (is (= ["docs/agent.md#the-agent" 1] ((juxt :address :level) (first toc))) "the page's sections, the first its title")
    (is (every? :lead toc) "each section leads with a sentence that stands alone")
    (is (some #(= "docs/agent.md#done" (:address %)) toc))
    (is (str/includes? (:seon.ref/text (docs/view db "docs/agent.md#done" :code)) "The judge counts exactly") "one section, whole")
    (is (nil? (docs/view db "docs/agent.md#nowhere" :code)) "no such section")
    (is (str/starts-with? (context/help-text db) "# The agent\n\nYou are a Seon agent") "the page, made from its sections")
    (is (nil? (docs/page db0 context/help-page)) "a db without the wiki holds no help page")))

(defn- outcome [ticks thunk]
  (try {:value (intr/run ticks thunk)} (catch Throwable e {:error e})))

(deftest an-endless-form-trips-its-ticks-and-is-named
  (let [{e :error} (outcome 100000000 #(loop [] (recur)))
        m (agent/error-map e)]
    (is (= :seon.repl/ticks-spent (:seon.error/type m)) "a loop that allocates nothing stops, as the typed error")
    (is (<= 100000000 (:ticks m) 100100000) "it ran its budget, and at most one poll past it")
    (is (= 100000000 (:budget m)))
    (is (contract/valid? :seon.repl/ticks-spent m) "the error is the declared variant, data for the agent"))
  (let [{e :error} (outcome 100000000 #(try (loop [] (recur)) (catch Throwable _ :swallowed)))]
    (is (= :seon.repl/ticks-spent (:seon.error/type (agent/error-map e))) "the form's own catch cannot swallow it"))
  (is (= {:value 5050} (outcome 100000000 #(reduce + (range 101)))) "a form under its budget answers its value")
  (is (= {:value 3000000} (outcome nil #(loop [i 0] (if (= i 3000000) i (recur (inc i)))))) "no budget: unbounded"))

(deftest an-interrupt-stops-only-the-request-it-arrives-in
  ;; research row 7: no client sends nREPL's `interrupt` op, so it cannot be what clears an
  ;; interrupt; root's SIGUSR1 stops the request running, and one that merges while the agent
  ;; is idle stops nothing that comes after it
  (let [[_ after-idle stopped after-stop]
        (at/in-agent [{:op :eval :agent "root" :code "(seon.root/interrupt! sys :a1)"}
                      "(+ 1 2)"
                      "(do (seon.lib/c-kill (seon.lib/c-getpid) seon.lib/SIGUSR1) (loop [] (recur)))"
                      "(+ 2 2)"])]
    (is (= "3" (:printed after-idle)) (str "the request after an idle interrupt runs: " (pr-str after-idle)))
    (is (str/starts-with? (:printed stopped) "interrupted by root\n") (pr-str stopped))
    (is (= "4" (:printed after-stop)) (pr-str after-stop))))

(deftest an-interrupt-after-the-request-began-stops-it
  ;; land6 review, must-fix 1: root waits on the agent's :started, then sends SIGUSR1. One that
  ;; merges after :started and before the first form runs finds no eval on intr's stack, so it
  ;; is pending; the request it was meant for must start stopped, never have it cleared
  (intr/clear-pending!)
  (#'intr/on-usr1)
  (try (let [[it] (repl/eval-string "(+ 1 2)" {"ns" "user" :origin :outside})]
         (is (= :err (:tag it)) (pr-str it))
         (is (str/starts-with? (:printed it) "interrupted by root\n")))
       (finally (intr/clear-pending!))))

(deftest a-handler-defect-answers-what-ran-and-its-error
  ;; land6 review, must-fix 2: a defect past a form's own boundary (here the printer meets a lazy
  ;; value that throws as it is realized) keeps every answer before it and adds one :err item
  ;; with its error map and id, so the record holds what ran and why the request stopped
  (let [items (repl/eval-string "(+ 1 2) (map (fn [_] (throw (ex-info \"boom\" {}))) [1]) (+ 3 4)" {"ns" "user" :origin :outside})
        [ok bad] items]
    (is (= "3" (:printed ok)) (pr-str items))
    (is (= :err (:tag bad)) (pr-str items))
    (is (= "boom" (:seon.error/message (:err bad))) "the defect as its error map")
    (is (int? (:id bad)) "an id, so record-tx records it")
    (is (= 2 (count (filter :seon.cluster.eval/record (repl/record-tx :a1 "r" items nil))))
        "both items are recorded: the form that ran and the defect")))

(deftest a-forms-lead-is-recorded-with-its-eval
  ;; owner, 2026-10-23: the agent thinks in ;; comments beside the forms it evaluates; each
  ;; form's comments are recorded with its evaluation (as :form/lead keeps a definition's), so
  ;; its page reads thought -> form -> result. A form with none has no :lead; a comment after the
  ;; last form leads nothing
  (let [items (repl/eval-string ";; first, the sum\n;; of two\n(+ 1 2)\n\n(+ 3 4) ; trailing\n;; why: the product\n(* 2 3)\n;; left over" {"ns" "user" :origin :outside})
        recs (map :seon.cluster.eval/record (filter :seon.cluster.eval/record (repl/record-tx :a1 "r" items nil)))]
    (is (= [";; first, the sum\n;; of two" nil "; trailing\n;; why: the product"] (mapv :lead items)) (pr-str items))
    (is (= ["3" "7" "6"] (mapv :printed items)))
    (is (= (mapv :lead items) (mapv :lead recs)) "its record keeps it")))
