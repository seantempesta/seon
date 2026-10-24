(ns seon.repl-test
  "An agent's REPL answers: asked of an agent in a root of its own (seon.agent-test/in-agent), and a form bounded by the work it does, never by a clock (seon.intr/run under a tick budget)."
  (:require [clojure.test :refer [deftest is testing]] [clojure.string :as str] [seon.agent-test :as at]
            [seon.intr :as intr] [seon.agent :as agent] [seon.contract :as contract]
            [seon.ref :as ref] [seon.template :as template] [seon.repl :as repl] [seon.context :as context] [seon.docs :as docs]
            [seon.config :as cfg] [seon.render :as render] [seon.render.ui :as ui] [seon.web.html :as h] [seon.test-store :as ts] [datahike.api :as d]))

(deftest an-agent-repl-answers-what-a-clojure-repl-answers
  ;; owner, 2026-10-21: "Whatever intuitions agents have we need to make sure we are matching a
  ;; real clojure repl as much as possible AND improving on it". Each row is what a Clojure
  ;; user types at clojure.main's REPL (clojure.main/repl-requires refers source, apropos,
  ;; dir, pst, doc, find-doc, pp, pprint) and what it answers, in an agent's own namespace;
  ;; the improvements: source reads the agent's own redefinitions and Seon's program (task
  ;; seon.repl/source-reads-the-program: a compiled platform var's form from the program facts,
  ;; a library var's from its :file and :line; a live agent spent all 8 turns on
  ;; (source datahike.connector/connect), 2026-10-21). Each answer is read in the response as
  ;; printed under its handle (its :text), where the value and what the form wrote both are.
  (let [rows [["(source seon.reconcile/root-causes)" :text "(defn root-causes"]
              ["(clojure.repl/source-fn 'seon.reconcile/root-causes)" :text "(defn root-causes"]
              ["(do (require 'datahike.connector) (source datahike.connector/connect))" :text "(defn connect"]
              ["(defn twice [x] (* 2 x))" :text "twice"]
              ["(source twice)" :text "(defn twice [x] (* 2 x))"]
              ["(doc twice)" :text "twice"]
              ["(dir clojure.string)" :text "join"]
              ["(apropos \"index-of\")" :text "index-of"]
              ["(+ 1 2)" :text "3"]
              ["*1" :text "3"]
              ["(pprint {:a 1})" :text "{:a 1}"]
              ["(/ 1 0)" :text ""]
              ["(some? *e)" :text "true"]
              ["(pst)" :text "Divide by zero"]
              ["(in-ns 'seon.reconcile)" :text "seon.reconcile"]
              ;; the same source a row above printed: an earlier response's output prints as the form
              ;; that reads it again, never twice (notes/design-agent-view-2026-10-24.md section 1)
              ["(clojure.repl/source root-causes)" :text "(:out result/e"]
              ["(with-out-str (clojure.repl/source seon.reconcile/root-causes))" :text "(:out result/e"]
              ;; a namespace's ns form, its requires and aliases (step E's run 3: the real design
              ;; asked for seon.problems-test/ns 18 turns running, to write its deftest)
              ["(my.schema/forms :seon.run/status)" :text ":enum"]
              ["(clojure.repl/source seon.reconcile)" :text "(ns seon.reconcile"]
              ["(clojure.repl/source seon.reconcile/ns)" :text "(source seon.reconcile)"]
              ["(clojure.repl/source seon.reconcile/not-yet)" :text "no such var is defined"]
              ;; what an agent naturally types answers as it reads (step 6, 2026-10-25: a run read its
              ;; helpers by a doseq over quoted names, then by #'f, and got invalid-input each time)
              ["(clojure.repl/source 'seon.reconcile/merge-limits)" :text "(defn merge-limits"]
              ["(clojure.repl/source #'seon.reconcile/desired-env)" :text "(defn desired-env"]
              ["(doseq [s '[seon.reconcile/done-explain]] (clojure.repl/source s))" :text "(defn done-explain"]
              ["(some? (clojure.repl/source-fn #'seon.reconcile/hairy))" :text "true"]
              ;; clojure.test reports to *test-out*: the step E dry run's agent ran a red
              ;; proof and saw nothing (2026-10-22)
              ["(do (in-ns 'user) (clojure.test/deftest red (clojure.test/is (= 1 2))) (clojure.test/run-tests))" :text "FAIL in (red)"]
              ;; every value is data, printed by the one printer (notes/design-agent-view-2026-10-24.md:
              ;; nothing renders an eval's value), its producer's schema or not
              ;; a namespaced map as Clojure's REPL prints one (*print-namespace-maps*)
              ["(my.task/current)" :text "#:seon.task{"]
              ["(my.task/current)" :text ":id \"demo/t\""]
              ["(:seon.task/id (with-meta (my.task/current) nil))" :text "\"demo/t\""]
              ["(pr-str (keys (with-meta (my.task/current) nil)))" :text ":seon.task/plan"]
              ["{:seon.task/id \"x/y\" :n 1}" :text ":n 1"]
              ["(seon.contract/error :seon.root/refused 'a/b \"m\")" :text "#:seon.error{:type :seon.root/refused"]]
        items (at/in-agent (mapv first rows))]
    (doseq [[[form k expected] item] (map vector rows items)]
      (is (str/includes? (str (get item k)) expected) (str form " -> " (pr-str item))))))

(deftest a-response-carries-values-not-their-printed-text
  ;; convergence item 9: the response holds values under its own keys, so nothing is printed
  ;; and read back in the agent; only :text is the printed response. The REPL in this process,
  ;; as a scratch session (not agent mode: nothing is transacted)
  (let [old @agent/state
        r (try (reset! agent/state {:n 0 :ns (create-ns 'seon.repl-test.scratch)})
               (repl/eval-string "(defn uncontracted-g [x] x) (/ 1 0)" {})
               (finally (reset! agent/state old)))]
    (is (qualified-symbol? (:handle r)) (pr-str r))
    (is (number? (:ms r)))
    (is (= :seon.agent/eval-error (:seon.error/type (:ex r))) "the error map itself")
    (is (str/includes? (:text r) "Divide by zero") "and printed under its handle")))

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
    (is (= [] (:value problems)) (str "every name the instructions cite resolves in the program: " (pr-str problems)))
    (is (some #{"(my.branch/unmerged)"} forms) "merge's own: the entries it resolves")
    (doseq [[form item] (map vector forms items)]
      (is (not (:ex item)) (str form " -> " (pr-str item))))
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
    (is (= [] (:value problems)) (str "every name the help page cites resolves in the program: " (pr-str problems)))
    (is (< 20 (count forms)) "the page shows the agent how to work")
    (doseq [[form item] (map vector forms items)]
      (is (not (:ex item)) (str form " -> " (pr-str item))))
    (is (= (count forms) (count items)) "every form answered")
    (let [by (zipmap forms items)
          printed #(str (:text (by %)))]
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
    (is (= 3 (:value after-idle)) (str "the request after an idle interrupt runs: " (pr-str after-idle)))
    (is (str/starts-with? (str (:seon.error/message (:ex stopped))) "interrupted by root") (pr-str stopped))
    (is (= 4 (:value after-stop)) (pr-str after-stop))))

(deftest an-interrupt-after-the-request-began-stops-it
  ;; land6 review, must-fix 1: root waits on the agent's :started, then sends SIGUSR1. One that
  ;; merges after :started and before the first form runs finds no eval on intr's stack, so it
  ;; is pending; the request it was meant for must start stopped, never have it cleared
  (intr/clear-pending!)
  (#'intr/on-usr1)
  (try (let [r (repl/eval-string "(+ 1 2)" {"ns" "user" :origin :outside})]
         (is (not (contains? r :value)) (pr-str r))
         (is (str/starts-with? (str (:seon.error/message (:ex r))) "interrupted by root") (pr-str r)))
       (finally (intr/clear-pending!))))

(deftest a-handler-defect-answers-its-error
  ;; land6 review, must-fix 2: a defect past a form's own boundary (here the printer meets a lazy
  ;; value that throws as it is realized) ends the request with its error map as the response's
  ;; :ex, so the record holds why the request stopped
  (let [r (repl/eval-string "(+ 1 2) (map (fn [_] (throw (ex-info \"boom\" {}))) [1])" {"ns" "user" :origin :outside})]
    (is (= "boom" (:seon.error/message (:ex r))) (pr-str (dissoc r :value)))
    (is (string? (:text r)) "and still a text every reader reads")))

(defn- eval-in
  "The response of `code` asked of the REPL in this process, as a scratch session in namespace
  nsym (seon.repl-test.scratch by default; not agent mode: nothing is transacted)."
  {:malli/schema [:function [:=> [:cat :string] :map] [:=> [:cat :string :symbol] :map]]}
  ([code] (eval-in code 'seon.repl-test.scratch))
  ([code nsym]
   (let [old @agent/state]
     (try (reset! agent/state {:n 0 :ns (create-ns nsym)})
          (repl/eval-string code {})
          (finally (reset! agent/state old))))))

(deftest an-eval-errors-data-is-printed-once
  ;; a compile error's Jolt diagnostics (:jolt.error/*, ex-data) are keys of the error map, the
  ;; suggestions its fix, and the response prints each once
  (let [r (eval-in "(prinltn 1)")
        em (:ex r)]
    (is (= :seon.agent/eval-error (:seon.error/type em)))
    (is (= 1 (:jolt.error/line em)))
    (is (= "prinltn" (:jolt.error/symbol em)) "Jolt answers the symbol as a string, as the schema says")
    (is (= ["print" "printf" "println"] (:jolt.error/suggestions em)) (pr-str em))
    (is (str/includes? (str (:seon.error/fix em)) "println"))
    (is (nil? (:data em)) "no printed copy of the ex-data")
    (is (contract/valid? :seon.agent/eval-error em) "the error is the declared variant")
    (is (= 1 (count (re-seq #"did you mean" (:text r)))) (:text r))
    (is (= 1 (count (re-seq #":jolt.error/suggestions" (:text r)))) (:text r))))

(deftest an-ordinary-exception-is-its-error-map
  (let [r (eval-in "(+ 1 :a)")
        em (:ex r)]
    (is (str/includes? (:seon.error/message em) "cannot be cast") (pr-str em))
    (is (= "class java.lang.ClassCastException" (:class em)))
    (is (str/includes? (:text r) (:seon.error/message em)))
    (is (nil? (:seon.error/fix em)))
    ;; on main :data was the printed string "{:k 1}": the map assertion below is the one that fails there
    (let [r (eval-in "(throw (ex-info \"boom\" {:k 1}))")]
      (is (= 1 (count (re-seq #"boom" (:text r)))) (:text r))
      (is (= {:k 1} (:data (:ex r))) "an ordinary ex-data stays data"))
    (let [em (agent/error-map (ex-info "x" {:my/id 1 :seon.error/message "shadow" :jolt.error/line 7}))]
      (is (= "x" (:seon.error/message em)) "a qualified key of someone else's cannot shadow the error's own")
      (is (= {:my/id 1 :seon.error/message "shadow"} (:data em)))
      (is (= 7 (:jolt.error/line em)) "Jolt's own keys are keys"))))

(deftest an-eval-error-says-where-in-its-forms-own-text
  ;; positions are the form's own (what (source f) prints), not the request's: a form on lines
  ;; 2-4 of a request whose line 4 fails is line 3 of it, and a fn defined on lines 1-3 of a
  ;; request that fails at its line 3 when called is line 3 of it. The first error stops a
  ;; request, so each error is its own request
  (let [compile-err (:ex (eval-in "(def ok 1)\n(defn f [x]\n  (+ x\n     (prinltn 2)))" 'seon.repl-test.where))
        run-err (:ex (eval-in "(defn g [x]\n  (inc x)\n  (+ 1 (first :k)))\n(g 1)" 'seon.repl-test.where))]
    (is (= {:line 3 :column 6} (:seon.error/at compile-err)) (pr-str compile-err))
    (is (nil? (:seon.error/in compile-err)))
    (is (= 'seon.repl-test.where/g (:seon.error/in run-err)) (pr-str run-err))
    (is (= {:line 3} (:seon.error/at run-err)))))

(deftest an-error-in-an-anonymous-fn-is-in-the-fn-around-it
  ;; what Jolt names for a frame inside an anonymous fn: the var that holds it when the call is
  ;; inside that var, nothing when the fn runs from the top of a request (never a core var)
  (let [in-fn (:ex (eval-in "(defn h [x]\n  ((fn [y]\n     (inc y))\n   x))\n(h \"a\")" 'seon.repl-test.anon))
        lazy (eval-in "(doall (map (fn [y] (inc y)) [\"a\"]))" 'seon.repl-test.anon)
        top (eval-in "(first :k)" 'seon.repl-test.anon)]
    (is (= 'seon.repl-test.anon/h (:seon.error/in in-fn)) (pr-str in-fn))
    (is (= 2 (:line (:seon.error/at in-fn))) "the line in h that runs the anonymous fn, as Jolt frames it")
    (doseq [r [lazy top]]
      (is (nil? (:seon.error/in (:ex r))) (pr-str (:ex r)))
      (is (not (re-find #"clojure\.core" (str (:seon.error/in (:ex r))))))
      (is (nil? (:seon.error/at (:ex r))) "no position is claimed that Jolt did not give"))))

(deftest a-request-relative-position-shows-unless-the-forms-own-does
  ;; when located cannot move a position into the form's terms (no :seon.error/at), the
  ;; request's own is printed rather than none; with :seon.error/at it is hidden
  (let [em (contract/error :seon.agent/eval-error 'seon.repl/eval-code "m" {:class "c" :jolt.error/line 4 :jolt.error/column 6})]
    (is (str/includes? (render/ai em {}) ":jolt.error/line 4") (render/ai em {}))
    (let [r (render/ai (assoc em :seon.error/at {:line 3 :column 6}) {})]
      (is (not (str/includes? r ":jolt.error/line")) r)
      (is (some #{"at line 3:6"} (str/split-lines r))))))

(def evals-run
  "How many times the ticks proof's form ran."
  (atom 0))

(deftest an-evals-response-carries-its-ticks
  ;; task seon.turn/a-turn-shows-its-cost-against-its-budget (1), owner 2026-10-23 (the model sees
  ;; cost, stable across turns): the work a call's forms did is taken once, when they ran
  ;; (jolt.host/ticks-of, bytes), summed into its response, which its record stores
  ;; (seon.repl/record-tx: the response without its live value), so nothing is measured again
  (let [small (eval-in "(reduce + (range 100))")
        big (eval-in "(reduce + (range 1000000))")
        bad (eval-in "(/ 1 0)")]
    (is (pos? (:ticks small)) (pr-str small))
    (is (> (:ticks big) (* 10 (:ticks small))) "ticks are the work done: 10,000x the loop is >10x the ticks (the form's compile is a constant)")
    (is (pos? (:ticks bad)) "a form that threw still did work")
    (is (every? #(and (number? (:ms %)) (pos? (:bytes %))) [small big bad]) "its ms and bytes too")))

(deftest a-call-is-one-response
  ;; notes/design-agent-view-2026-10-24.md §1: one response per eval call, {:handle :value :out
  ;; :err :ex}, each key only when present; its forms run in order as load-string runs them,
  ;; and the first exception stops them; its handle result/e<n> holds it
  (let [r (repl/eval-string "(println \"a\") (+ 1 2) (str \"x\")" {"ns" "user" :origin :outside})]
    (is (= "x" (:value r)) (pr-str r))
    (is (= "a\n" (:out r)))
    (is (not-any? #(contains? r %) [:ex :err]) "keys only when present")
    (is (= "x" (:value @(find-var (:handle r)))) "the handle holds the response")
    (is (str/starts-with? (:text r) (str "{:handle " (:handle r))) "printed under its handle"))
  (let [r (repl/eval-string "(+ 1 2) (/ 1 0) (def a-call-is-one-response-never 1)" {"ns" "user" :origin :outside})]
    (is (= "Divide by zero" (:seon.error/message (:ex r))) (pr-str r))
    (is (not (contains? r :value)) "an exception is the response: no value")
    (is (nil? (resolve 'user/a-call-is-one-response-never)) "a form after the exception never ran")))
