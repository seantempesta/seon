(ns seon.repl-test
  "An agent's REPL answers: asked of an agent in a root of its own (seon.agent-test/in-agent), and a form bounded by the work it does, never by a clock (seon.intr/run under a tick budget)."
  (:require [clojure.test :refer [deftest is testing]] [clojure.string :as str] [seon.agent-test :as at]
            [seon.intr :as intr] [seon.agent :as agent] [seon.contract :as contract]
            [seon.ref :as ref] [seon.template :as template] [seon.repl :as repl] [seon.context :as context] [seon.docs :as docs]
            [seon.config :as cfg] [my.agent] [seon.render :as render] [seon.render.ui :as ui] [seon.web.html :as h] [seon.test-store :as ts] [my.test] [datahike.api :as d]))

(deftest an-eval-answers-its-value-as-clojure-does
  ;; owner, 2026-10-28 and 2026-10-29: match Clojure for the eval's output: top-level forms run in
  ;; order and each one's value is answered, as a REPL prints them (:forms, :value their vector),
  ;; with the evaluation's facts as data; (do (+ 1 1) (+ 2 2)) is one form and answers 4. A form
  ;; that throws ends it, after the values of those before it.
  (let [old @agent/state
        ev (fn [code] (try (reset! agent/state {:n 0 :ns (the-ns 'user)})
                           (repl/eval-string code {})
                           (finally (reset! agent/state old))))
        r (ev "(+ 1 2) (str \"a\" \"b\") [:x]")]
    (is (= [3 "ab" [:x]] (:value r)) "each top-level form's value, in order")
    (is (= 3 (:forms r)) "and how many ran")
    (is (= [3 "ab" [:x]] (:value @(resolve (:handle r)))) "and its handle reads it")
    (is (= {:value 3} (select-keys (ev "(+ 1 2)") [:value :forms])) "one form: its value, no :forms")
    (is (contains? r :ms) "the evaluation's facts: its cost")
    (is (= {:value 4} (select-keys (ev "(do (+ 1 1) (+ 2 2))") [:value :forms])) "a do is one form: its last form's value")
    (let [rec (repl/record-details "(atom 1)" {:handle 'result/e9 :value (atom 1)} {} nil)]
      (is (:printed rec) "a value that is no plain data: the response as it printed"))
    (let [e (ev "(+ 1 2) (throw (ex-info \"no\" {})) 4")]
      (is (contains? e :ex) "the error stops it")
      (is (= {:forms 2 :value [3]} (select-keys e [:forms :value])) "the values of the forms before it, and the forms after it never ran"))
    ;; md-scan #11: an error in macroexpanding says so, where, as Clojure's REPL does (clojure.main/ex-str)
    (let [e (ev "(cond true 1 false)")]
      ;; the macro as written, as Clojure's REPL names it, and the reference's own reason (Jolt f6bac31c)
      (is (str/starts-with? (str (get-in e [:ex :seon.error/message])) "Syntax error macroexpanding cond at (1:1). ")
          (str "the phase, the macro and the position first: " (get-in e [:ex :seon.error/message])))
      (is (str/includes? (str (get-in e [:ex :seon.error/message])) "cond requires an even number of forms")
          (str "the phase, the macro and the position first: " (get-in e [:ex :seon.error/message])))
      (is (= ["true" "1" "false"] (get-in e [:ex :seon.error/given])) (str "the forms it was given, as read: " (pr-str (:ex e)))))
    ;; md-scan #22: an and one paren short swallowed its clause's value; the forms given show it, nested
    (let [e (ev "(let [c 1]\n  (cond\n    (and (= c 1) (or (= c 2)) [1 2]\n    :else nil)))")]
      (is (= 1 (count (get-in e [:ex :seon.error/given]))) (str "one form: the and took the rest " (pr-str (:ex e))))
      (is (str/includes? (first (get-in e [:ex :seon.error/given])) ":else") "the clauses inside the and, where the reader put them"))
    ;; whole, however long: a cut would drop the swallowed tail
    (let [long-test (apply str (interpose " " (repeat 60 "(= c 2)")))
          e (ev (str "(let [c 1] (cond (and (or " long-test ") [1 2] :else nil)))"))]
      (is (not (str/includes? (first (get-in e [:ex :seon.error/given])) "…")) (str "nothing elided: an agent's own error is whole: " (first (get-in e [:ex :seon.error/given])))))
    ;; a form past its byte budget stops with the typed error, its allocation and budget as data
    (let [old2 @agent/state
          e (try (reset! agent/state {:n 0 :ns (the-ns 'user)})
                 (repl/eval-string "(count (vec (range 5000000)))" {:origin :model :bytes 1000000})
                 (finally (reset! agent/state old2)))]
      (is (= :seon.repl/bytes-spent (get-in e [:ex :seon.error/type])) (str "stopped by its byte budget: " (pr-str (:ex e))))
      (is (= 1000000 (get-in e [:ex :budget])) "its budget, as data"))
    (let [e (ev "(+ 1 1) (+ 2 2) (oops")]
      (is (contains? e :ex) "a read error stops it")
      (is (= {:forms 2 :value [2 4]} (select-keys e [:forms :value])) "and is no form that ran: the two before it are"))))

(deftest an-edit-replaces-exact-text-once-and-evaluates-the-definition
  ;; docs/agent.md#changing-a-definition: the md-scan bench's agent string-edited a copy of a
  ;; 250-line scan for 30 turns; edit! replaces one exact piece of the recorded source and evaluates it whole
  (let [old @agent/state]
    (try (reset! agent/state {:n 0 :ns (the-ns 'user)})
         (repl/eval-string "(ns x.repl-edit) (defn f \"Adds.\" [a]\n  (let [b 1\n        c 2]\n    (+ a b c)))\n(defn g [] (+ 1 1)) (defn h [] (+ 1 1)) (in-ns 'user)" {})
         (let [r (repl/edit! 'x.repl-edit/f "c 2" "c 20")]
           (is (= 22 ((resolve 'x.repl-edit/f) 1)) "the new definition is the one called")
           (is (= 'x.repl-edit/f (get-in r [:edited :var])))
           (is (some #(str/includes? (second %) "c 20") (get-in r [:edited :lines])) "the lines around the change")
           (is (str/includes? (my.agent/source-fn 'x.repl-edit/f) "c 20") "its source is the new text: the next edit reads it"))
         (let [r (repl/edit! 'x.repl-edit/f "c 3" "c 4")]
           (is (= :not-found (:seon.repl/refused-because r)) "old text not there: refused, nothing changed")
           (is (= 22 ((resolve 'x.repl-edit/f) 1))))
         ;; md-scan #23: ten evals printing lines with visible whitespace; the refused edit names the line and how it differs
         (let [r (repl/edit! 'x.repl-edit/f "(let [b 1\n      c 20]" "(let [b 1\n      c 3]")]
           (is (= :not-found (:seon.repl/refused-because r)))
           (is (= {:line 3 :old "      c 20]" :source "        c 20]" :indent [6 8]} (first (:seon.repl/differs r))) (pr-str r))
           (is (str/includes? (:seon.error/message r) "at line 3") (:seon.error/message r))
           (is (str/includes? (:seon.error/message r) "6 leading spaces, the source 8") (:seon.error/message r)))
         (let [r (repl/edit! 'x.repl-edit/f "  (let [b 1" "(let [b 1")]
           (is (nil? (:seon.repl/refused-because r)) "indentation is text: the exact match holds"))
         (let [r2 (repl/edit! 'x.repl-edit/f "c" "d")]
           (is (= :ambiguous (:seon.repl/refused-because r2)) "there more than once: refused")
           (is (<= 2 (count (:seon.repl/lines r2))) "naming each candidate's line"))
         ;; md-scan #17: an edit one paren short was repaired far from it and failed as cond there
         (let [r (repl/edit! 'x.repl-edit/g "(+ 1 1)" "(+ 1 (inc 1)")]
           (is (= :unbalanced (:seon.repl/refused-because r)) "a delimiter the new text leaves open: refused, never repaired")
           (is (str/includes? (:seon.error/message r) "opens 1 more (") (:seon.error/message r))
           (is (str/includes? (:seon.error/message r) "a ( left open at its line 1, \"(+ 1 (inc 1)\"") (str "and where (md-scan bench-b15): " (:seon.error/message r)))
           (is (= 2 ((resolve 'x.repl-edit/g))) "nothing changed"))
         (let [r (repl/edit! 'x.repl-edit/g "(+ 1 1)" "(+ 1 [1)]")]
           (is (= :unbalanced (:seon.repl/refused-because r)) (str "a ( met by ], as many of each: refused, never read as \"Unmatched delimiter\": " (pr-str r)))
           (is (str/includes? (:seon.error/message r) "the ) at its line 1") (:seon.error/message r)))
         (let [r (repl/edit! 'x.repl-edit/g "(+ 1 1)" "(+ 1 1))")]
           (is (= :unbalanced (:seon.repl/refused-because r)) "a closer more")
           (is (str/includes? (:seon.error/message r) "closes 1 more )") (:seon.error/message r)))
         (let [r (repl/edit! 'x.repl-edit/g "(+ 1 1)" "(str \\) \")\" ;)\n 1)")]
           (is (nil? (:seon.repl/refused-because r)) (str "a closer in a character literal, a string or a comment is none: " (pr-str r)))
           (is (= "))1" ((resolve 'x.repl-edit/g)))))
         (is (= :no-source (:seon.repl/refused-because (repl/edit! 'clojure.core/nope "a" "b"))) "no recorded source (outside an agent: no branch to name the nearest from)")
         ;; md-scan bench-b13: a loop's new binding and its recur, edited one by one, each failed to compile; the
         ;; error sat nested in the edit's value, cut from sight, and the agent read 33 turns of edits as held
         (repl/eval-string "(in-ns 'x.repl-edit) (defn l [n] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc i)) acc))) (in-ns 'user)" {})
         (let [r (repl/eval-string "(seon.repl/edit! 'x.repl-edit/l \"[i 0 acc 0]\" \"[i 0 acc 0 k 1]\")" {})]
           (is (str/includes? (str (get-in r [:ex :seon.error/message])) "nothing changed") (str "a partial edit that does not compile is the eval's own error: " (pr-str r)))
           (is (str/includes? (str (get-in r [:ex :seon.error/message])) "recur") "with the compiler's message")
           (is (str/includes? (str (get-in r [:ex :seon.error/message])) "at line 1") (str "and the line it names: " (get-in r [:ex :seon.error/message])))
           (is (= 6 ((resolve 'x.repl-edit/l) 4)) "and nothing changed"))
         (let [r (repl/edit! 'x.repl-edit/l [["[i 0 acc 0]" "[i 0 acc 0 k 10]"] ["(recur (inc i) (+ acc i))" "(recur (inc i) (+ acc i) k)"] ["acc)))" "(+ acc k))))"]])]
           (is (get-in r [:edited :var]) (str "several changes, applied together and compiled once: " (pr-str r)))
           (is (= 16 ((resolve 'x.repl-edit/l) 4)) "the bindings, the recur and the result changed at once")
           (is (str/includes? (my.agent/source-fn 'x.repl-edit/l) "k 10]") "its source is the new text")
           (is (= 1 (ffirst (get-in r [:edited :lines]))) "the lines around the changes"))
         ;; each change's lines numbered in the new text: the second change's line moved down by the line the first added
         (repl/eval-string "(in-ns 'x.repl-edit) (defn m [x]\n  (let [a 1]\n    (+ x\n       a)))\n(in-ns 'user)" {})
         (let [r (repl/edit! 'x.repl-edit/m [["(let [a 1]" "(let [a 1\n        b 2]"] ["       a)))" "       a b)))"]])]
           (is (= 4 ((resolve 'x.repl-edit/m) 1)) (pr-str r))
           (is (some #{[5 "       a b)))"]} (get-in r [:edited :lines])) (str "the second change at its line in the new text: " (pr-str (get-in r [:edited :lines]))))
           (is (apply < (map first (get-in r [:edited :lines]))) "each line once, in order"))
         (let [r (repl/edit! 'x.repl-edit/l [["(recur (inc i)" "(recur (+ i 1)"] ["(inc i) (+ acc" "(+ 1 i) (+ acc"]])]
           (is (= :overlap (:seon.repl/refused-because r)) (str "two changes whose texts overlap, neither the other: " (pr-str r))))
         (let [r (repl/edit! 'x.repl-edit/l "" "x")]
           (is (= :empty (:seon.repl/refused-because r)) (str "an empty old text is refused, never matched everywhere: " (pr-str r))))
         (let [r (repl/edit! 'x.repl-edit/l [["(loop [i 0" "(loop [i 1"] ["(+ acc i)" "(+ acc i"]])]
           (is (= :unbalanced (:seon.repl/refused-because r)) "each change is checked as one alone is")
           (is (str/includes? (:seon.error/message r) "change 2 of 2") (str "naming which: " (:seon.error/message r)))
           (is (= 16 ((resolve 'x.repl-edit/l) 4)) "and nothing changed"))
         (let [r (repl/edit! 'x.repl-edit/l [["(loop [i 0" "(loop [i 0"] ["0 acc" "0 acc"]])]
           (is (= :overlap (:seon.repl/refused-because r)) (str "two changes over one text: " (pr-str r))))
         (finally (reset! agent/state old) (remove-ns 'x.repl-edit)))))

(deftest an-edit-in-an-agent-is-program-on-its-branch
  ;; the edit's definition is recorded as a typed defn is: the branch's program holds the new source,
  ;; and its inner eval records no eval of its own
  (let [[_ edit branch] (at/in-agent (fn [{:keys [ns task]}]
                                       [(str "(ns " ns ".ed) (defn f \"One.\" {:malli/schema [:=> [:cat] :int]} [] 1)")
                                        (str "(seon.repl/edit! '" ns ".ed/f \"[] 1\" \"[] 2\")")
                                        {:op :eval :agent "root"
                                         :code (str "(let [b (:db @(seon.root/branch-db sys (:seon.task/branch (seon.task/task @(:conn sys) " (pr-str task) "))))]"
                                                    " [(:form/src (:var/form (datahike.api/entity b [:var/name '" ns ".ed/f])))"
                                                    "  (count (filter #(clojure.string/includes? (:code %) \"[] 2)\") (datahike.api/q '[:find [?r ...] :where [_ :seon.eval/record ?r]] b)))])")}]))]
    (is (get-in edit [:value :edited]) (pr-str edit))
    (is (clojure.string/includes? (str (first (:value branch))) "[] 2") "the branch's program holds the new source")
    (is (= 0 (second (:value branch))) "its inner eval records no eval of its own: the edit's call is the one kept")))

(deftest an-unmapped-definition-is-no-longer-the-branchs-program
  ;; docs/source.md#an-agent-changes-code-at-its-repl: md-scan #19 unmapped a scratch helper and its branch kept it,
  ;; uncontracted, so the done rule refused the work at the cap; Clojure's ns-unmap, observed, retracts it
  (let [[_ gone branch] (at/in-agent (fn [{:keys [ns task]}]
                                       [(str "(ns " ns ".um) (defn scratch [] 1)")
                                        (str "(ns-unmap '" ns ".um 'scratch)")
                                        {:op :eval :agent "root"
                                         :code (str "(let [b (:db @(seon.root/branch-db sys (:seon.task/branch (seon.task/task @(:conn sys) " (pr-str task) "))))]"
                                                    " (:var/form (datahike.api/entity b [:var/name '" ns ".um/scratch])))")}]))]
    (is (str/includes? (:text gone) ":unmapped") (str "the answer says what it removed: " (pr-str gone)))
    (is (nil? (:value branch)) (str "the branch records no definition of it: " (pr-str branch)))))

(deftest an-eval-that-printed-answers-its-value
  ;; since an eval's :out is shown verbatim (md-scan #20), the printed answer is no EDN once it printed, and a reader
  ;; that read the value back from the text got nil (seon.source-test's restart read nil of a turn that printed); the
  ;; agent's reply carries a plain value as data beside the text (seon.repl, seon.server)
  (let [[r] (at/in-agent ["(do (println \"a ] b\") {:n 42})"])]
    (is (= {:n 42} (:value r)) (pr-str r))
    (is (str/includes? (str (:text r)) "a ] b") "and its text still shows what it printed, verbatim")))

(deftest an-agents-namespace-is-recorded-where-its-eval-left-it
  ;; owner, 2026-10-29: "we have to be able to expose this we own all the machinery": the namespace the
  ;; REPL is in lived only in the agent's memory; each eval's record keeps it (seon.context/current-ns). The agent
  ;; is the one with a key: main's evals, root's, are on the branch too (any one agent was root's by chance)
  (let [[_ _ where] (at/in-agent (fn [{:keys [ns task]}]
                                   [(str "(ns " ns ".here)")
                                    "(+ 1 1)"
                                    {:op :eval :agent "root"
                                     :code (str "(let [b (:db @(seon.root/branch-db sys (:seon.task/branch (seon.task/task @(:conn sys) " (pr-str task) "))))"
                                                " a (datahike.api/q '[:find ?a . :where [_ :seon.eval/agent ?a] [?a :seon.agent/key _]] b)]"
                                                " (seon.context/current-ns (:tree (seon.ctx/build b a :b))))")}]))]
    (is (str/ends-with? (str (:value where)) ".here") (pr-str where))))

(deftest a-move-into-a-namespace-that-hides-a-helper-says-so
  ;; owner, 2026-10-29: "don't some repls output a warning ... you are overwriting symbols in this
  ;; namespace?": an agent in-ns'd to seon.render.ui, whose own source shadows the REPL's, and read
  ;; an nth error four times; Clojure warns when a definition replaces a referred var
  (let [old @agent/state]
    (try (reset! agent/state {:n 0 :ns (the-ns 'user)})
         (repl/eval-string "(ns x.repl-shadow) (defn source [x] x) (in-ns 'user)" {})
         (let [r (repl/eval-string "(in-ns 'x.repl-shadow)" {})
               w (some #(when (= :helper-shadowed (:seon.repl/finding %)) %) (:warnings r))]
           (is (= '{source x.repl-shadow/source} (:shadowed w)) "the move names what hides the helper")
           (is (str/includes? (:seon.repl/fix w) "clojure.repl/source") "and the qualified helper to call")
           (is (not-any? #(= :helper-shadowed (:seon.repl/finding %)) (:warnings (repl/eval-string "(+ 1 1)" {})))
               "once, as it moves: an eval that stays says nothing"))
         ;; an eval with prose outside an agent (root's) is answered as any is
         (reset! agent/state {:n 0 :ns (the-ns 'user)})
         (is (= 2 (:value (repl/eval-string "(+ 1 1)" {:prose "Adds, see seon.ref/scan."}))) "an eval with prose outside an agent")
         (finally (reset! agent/state old) (remove-ns 'x.repl-shadow)))))

(deftest a-broken-parser-leaves-the-repl-answering
  ;; docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map: a DeepSeek run of md-scan gave
  ;; seon.ref/scan's emit step a new arity, and every eval it made after threw "Wrong number of args (6)", a pure
  ;; (count s) included, since seon.repl/eval-string parsed each call's prose for its citations (seon.ref/references
  ;; -> parse -> scan) with the parser the agent had broken: it could not inspect or undo its change. The eval path
  ;; runs no code the agent may redefine for its own bookkeeping: the record keeps the prose, and a reader parses it
  (let [prose "Counting, as `seon.ref/scan` reads docs/references.md#resolving-and-showing."
        call (fn [code] (str "(let [r (seon.repl/eval-string " (pr-str code) " {:prose " (pr-str prose) "})]"
                            " {:value (when (number? (:value r)) (:value r)) :ex (some-> (:ex r) :seon.error/message str) :record (:record r)})"))
        [_ counted undone works]
        (at/in-agent [(str "(in-ns 'seon.ref) (def scan-was scan)"
                           " (clojure.core/defn scan [& _] (throw (ex-info \"Wrong number of args (6) passed to: fn\" {})))")
                      (call "(count \"abc\")")
                      (call "(in-ns 'seon.ref) (def scan scan-was)")
                      "(map? (seon.ref/scan \"# h\\n\"))"])]
    (is (= 3 (get-in counted [:value :value]))
        (str "a broken seon.ref/scan never reaches the answer of an eval that does not call it (seon.repl/eval-string ran it on the prose): " (pr-str counted)))
    (is (string? (get-in counted [:value :record])) (str "and the eval is recorded: " (pr-str counted)))
    (is (and (map? (:value undone)) (nil? (get-in undone [:value :ex]))) (str "an eval that redefines scan back works: " (pr-str undone)))
    (is (true? (:value works)) (str "and scan is whole again: " (pr-str works)))))

(deftest a-def-beside-calls-stays-a-recorded-eval
  ;; root's ruling, 2026-10-27: a branch's program held the eval (let [r (my.agent/prove)] (def
  ;; last-prove r) r), so every agent loading the branch ran prove at load and died ("nil where a
  ;; java.util.concurrent.Future is required"). A form is program when its top-level form is a
  ;; definition; a def nested beside calls stays the recorded eval, and its record says so
  (let [[top nested branch] (at/in-agent (fn [{:keys [ns task agent]}]
                                           [(str "(ns " ns ".core) (def a 1)")
                                            (str "(in-ns '" ns ".core) (let [r (+ 1 1)] (def b r) r)")
                                            {:op :eval :agent "root"
                                             ;; the records are applied ahead of the agent's commit (seon.agent/transact!): read at its
                                             ;; :commit events, the tap first, until a is there (b never is)
                                             :code (str "(let [ev (c/events sys) a :" agent
                                                        " read (fn [] (let [b (:db @(seon.root/branch-db sys (:seon.task/branch (seon.task/task @(:conn sys) " (pr-str task) "))))]"
                                                        " (mapv #(some? (datahike.api/entity b [:var/name %])) '[" ns ".core/a " ns ".core/b])))"
                                                        " r (loop [] (let [v (read)] (if (first v) v"
                                                        " (do (loop [] (let [e (clojure.core.async/<!! ev)] (when-not (and (= :commit (:type e)) (= a (:agent e))) (recur)))) (recur)))))]"
                                                        " (clojure.core.async/untap (:events-mult sys) ev) r)")}]))]
    (is (some #(= "a" (name %)) (:recorded top)) (str "a top-level def is program: " (pr-str top)))
    (is (empty? (:recorded nested)) (pr-str nested))
    (is (clojure.string/includes? (str (:text nested)) "not program") "its record says why it stays an eval")
    (is (= [true false] (:value branch)) "the branch's program holds a, not b")))

(deftest a-definition-inside-a-top-level-do-is-program
  ;; owner, 2026-10-28: match Clojure, whose compiler evaluates each form of a top-level do as a
  ;; top-level form; an agent's (do (in-ns ..) (defn f ..)) never reached its branch, and its run
  ;; spent 39 turns finding out why (docs/prompt.md)
  (let [[r nested stopped after branch]
        (at/in-agent (fn [{:keys [ns task agent]}]
                       [(str "(do (ns " ns ".core)\n  (def c 1)\n  (inc c))")
                        (str "(in-ns '" ns ".core) (do (do (def d 3)) (* d 2))")
                        (str "(in-ns '" ns ".core) (do (def e 1) (throw (ex-info \"boom\" {})) (def f 2))")
                        (str "[(some? (resolve '" ns ".core/e)) (some? (resolve '" ns ".core/f))]")
                        {:op :eval :agent "root"
                         ;; the agent's records are applied ahead of its commit (seon.agent/transact!): read at its
                         ;; :commit events, the tap first (seon.check/await-records's order)
                         :code (str "(let [ev (c/events sys) a :" agent
                                    " read (fn [] (let [b (:db @(seon.root/branch-db sys (:seon.task/branch (seon.task/task @(:conn sys) " (pr-str task) "))))]"
                                    " (mapv #(some? (datahike.api/entity b [:var/name %])) '[" ns ".core/c " ns ".core/d])))"
                                    " r (loop [] (let [v (read)] (if (every? true? v) v"
                                    " (do (loop [] (let [e (clojure.core.async/<!! ev)] (when-not (and (= :commit (:type e)) (= a (:agent e))) (recur)))) (recur)))))]"
                                    " (clojure.core.async/untap (:events-mult sys) ev) r)")}]))]
    (is (some #(= "c" (name %)) (:recorded r)) (str "the def inside the do is program: " (pr-str r)))
    (is (= 2 (:value r)) "the do's value is its last form's")
    (is (= 6 (last (:value nested))) "a do inside a top-level do is its forms too (after the in-ns: each top-level form's value)")
    (is (str/includes? (str (:text stopped)) "boom") "an error inside the do is the request's")
    (is (= [true false] (:value after)) "the forms after the error never ran")
    (is (= [true true] (:value branch)) "the branch's program holds c and d")))

(deftest a-top-level-dos-inner-forms-are-recorded-with-their-own-text
  ;; coordinator, 2026-10-29: the inner forms of a written (do ...) come from the form the reader
  ;; returned, each form's exact text from the reader's positions (seon.repl/do-parts), no regex over
  ;; the code. Each definition is program with its own text, comments and spacing as written
  (let [[_ r branch]
        (at/in-agent (fn [{:keys [ns task agent]}]
                       [(str "(ns " ns ".core)")
                        (str "(do ; leads f\n  (seon.contract/defn f {:malli/schema [:=> [:cat :int] :int]} [x]   (inc x))\n"
                             "  ;; leads g-test\n  (clojure.test/deftest g-test (clojure.test/is (= 2 (f 1)))))")
                        {:op :eval :agent "root"
                         ;; an agent's records are applied ahead of its commit (seon.agent/transact!), so its branch
                         ;; shows them at its :commit event: the tap first, then the read, and again at each of
                         ;; the agent's commits until both forms are there (seon.check/await-records's order)
                         :code (str "(let [ev (c/events sys) a :" agent
                                    " read (fn [] (let [b (:db @(seon.root/branch-db sys (:seon.task/branch (seon.task/task @(:conn sys) " (pr-str task) "))))]"
                                    " (mapv #(datahike.api/q '[:find ?src . :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/src ?src]] b %) '[" ns ".core/f " ns ".core/g-test])))"
                                    " r (loop [] (let [v (read)] (if (every? some? v) v"
                                    " (do (loop [] (let [e (clojure.core.async/<!! ev)] (when-not (and (= :commit (:type e)) (= a (:agent e))) (recur)))) (recur)))))]"
                                    " (clojure.core.async/untap (:events-mult sys) ev) r)")}]))]
    (is (= 2 (count (:recorded r))) (str "both inner definitions are program: " (pr-str r)))
    (is (= ["(seon.contract/defn f {:malli/schema [:=> [:cat :int] :int]} [x]   (inc x))"
            "(clojure.test/deftest g-test (clojure.test/is (= 2 (f 1))))"]
           (:value branch)) "each recorded with its own exact text, not the do's")))

(deftest a-loaded-file-defines-what-source-shows-and-the-branch-records
  ;; seon.repl/a-loaded-file-is-recorded-as-evals-are: a top-level (load-file "path") is its file's forms,
  ;; each evaluated as a top-level form of the request (bench run A3: source-fn answered the old text)
  (let [file (java.io.File/createTempFile "loaded" ".clj")
        path (.getPath file)
        body (fn [ns n] (str "(ns " ns ".lf)\n(seon.contract/defn f {:malli/schema [:=> [:cat] :int]} [] " n ")"))]
    (try
      (let [[_ _ _ _ loaded called src then]
            (at/in-agent (fn [{:keys [ns]}]
                           [(str "(ns " ns ".core)")
                            (str "(spit " (pr-str path) " " (pr-str (body ns 1)) ")")
                            (str "(load-file " (pr-str path) ")")
                            (str "(spit " (pr-str path) " " (pr-str (body ns 2)) ")")
                            (str "(load-file " (pr-str path) ")")
                            (str "(" ns ".lf/f)")
                            (str "(my.agent/source-fn '" ns ".lf/f)")
                            ;; a form after the load-file in the same request runs after the file's forms
                            (str "(load-file " (pr-str path) ")\n(" ns ".lf/f)")]))]
        (is (= 2 (:value called)) "the new definition runs")
        (is (str/includes? (:value src) "[] 2") "source shows the new text")
        (is (= 2 (last (:value then))) (str "the form after a load-file runs: " (pr-str (select-keys then [:value :forms :ex]))))
        (is (some #(str/ends-with? (str %) ".lf/f") (:recorded loaded)) (str "the loaded definition is recorded as program on the branch: " (pr-str (:recorded loaded)))))
      (finally (.delete file)))))

(deftest a-value-that-is-no-data-reaches-root-printed
  ;; (in-ns 'x) answers a Namespace: it is printed for the model, and the agent's reply to root
  ;; carries the text, never the live value (which no wire encodes)
  (let [[ns-call next-call] (at/in-agent ["(in-ns 'user)" "(+ 1 1)"])]
    (is (str/includes? (str (:text ns-call)) "#namespace") "the value, printed")
    (is (= 2 (:value next-call)) "and the agent lives to answer the next")))

(deftest a-redefinition-that-changes-a-docstring-says-so
  ;; owner, 2026-10-25 (DECISIONS: a change to a schema is an explicit fact the eval shows): the
  ;; eval that changes an existing var's docstring or contract answers what it was and is now
  (let [[_ again same] (at/in-agent ["(ns app.redef) (seon.contract/defn f \"One.\" {:malli/schema [:=> [:cat] :int]} [] 1)"
                                      "(seon.contract/defn f \"Two.\" {:malli/schema [:=> [:cat] :int]} [] 1)"
                                      "(seon.contract/defn f \"Two.\" {:malli/schema [:=> [:cat] :int]} [] 2)"])]
    (is (str/includes? (str (:text again)) ":redefined") "a docstring changed: said")
    (is (str/includes? (str (:text again)) "\"One.\"") "with what it was")
    (is (not (str/includes? (str (:text same)) ":redefined")) "a body alone: nothing said")))

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
              ["(/ 1 0)" :text "Divide by zero"]
              ["(some? *e)" :text "true"]
              ["(pst)" :text "Divide by zero"]
              ;; *e keeps the error as data (seon.repl/error-kept), not the thrown exception's frames
              ["(some? (:seon.error/message (ex-data *e)))" :text "true"]
              ["(in-ns 'seon.reconcile)" :text "seon.reconcile"]
              ;; the same source twice: each answer prints it whole, whatever came before (docs/render.md)
              ["(clojure.repl/source root-causes)" :text "(defn root-causes"]
              ["(with-out-str (clojure.repl/source seon.reconcile/root-causes))" :text "(defn root-causes"]
              ;; a namespace's ns form, its requires and aliases (step E's run 3: the real design
              ;; asked for seon.problems-test/ns 18 turns running, to write its deftest)
              ["(my.schema/forms :seon.run/status)" :text ":enum"]
              ;; a key no schema registers is said so, with its namespace's keys (md-scan #6 read nil twenty times)
              ["(my.schema/forms (keyword \"seon.run\" \"no-such-key\"))" :text ":seon.schema/unknown-key"]
              ;; (the typo made at run time: a key this file names is one the program knows)
              ["(my.schema/forms (keyword \"seon.run\" \"statu\"))" :text ":seon.run/status"]
              ["(my.agent/callers (keyword \"seon.run\" \"statu\"))" :text ":seon.schema/unknown-key"]
              ;; a help address that is no section names the sections whose text holds it
              ["(:nearest (my.agent/help \"schema\"))" :text "docs/"]
              ["(clojure.repl/source seon.reconcile)" :text "(ns seon.reconcile"]
              ;; then every definition, privates included, a line each (pipeline 1's F4: a design took
              ;; the ns form alone for all that was recorded and spent five turns finding the privates)
              ["(let [s (clojure.repl/source-fn 'seon.reconcile)] (mapv #(clojure.string/includes? s %) [\";; branch-messages\" \";; hairy\"]))" :text "[true true]"]
              ["(clojure.repl/source seon.reconcile/ns)" :text "(source seon.reconcile)"]
              ["(clojure.repl/source seon.reconcile/not-yet)" :text "names no var on this branch"]
              ["(clojure.repl/source seon.reconcile/not-yet-either)" :text "every definition of seon.reconcile, a line each: (source seon.reconcile)"]
              ;; what an agent naturally types answers as it reads (step 6, 2026-10-25: a run read its
              ;; helpers by a doseq over quoted names, then by #'f, and got invalid-input each time)
              ["(clojure.repl/source 'seon.reconcile/merge-limits)" :text "(defn merge-limits"]
              ["(clojure.repl/source #'seon.reconcile/desired-env)" :text "(defn desired-env"]
              ["(doseq [s '[seon.reconcile/done-explain]] (clojure.repl/source s))" :text "(defn done-explain"]
              ["(some? (clojure.repl/source-fn #'seon.reconcile/hairy))" :text "true"]
              ;; clojure.test reports to *test-out*: the step E dry run's agent ran a red
              ;; proof and saw nothing (2026-10-22)
              ["(do (in-ns 'user) (clojure.test/deftest red (clojure.test/is (= 1 2))) (clojure.test/run-tests))" :text "FAIL in (red)"]
              ;; every value is data, printed by the one printer (docs/render.md:
              ;; nothing renders an eval's value), its producer's schema or not
              ;; a namespaced map as Clojure's REPL prints one (*print-namespace-maps*)
              ["(my.task/current)" :text "#:seon.task{"]
              ["(my.task/current)" :text "/t\""]
              ["(:seon.task/id (with-meta (my.task/current) nil))" :text "/t\""]
              ["(pr-str (keys (with-meta (my.task/current) nil)))" :text ":seon.task/plan"]
              ;; update! answers the member it changed, as swap! answers the new value, never the
              ;; whole task (live agents, 2026-10-25: its instructions and task data were most of each answer)
              ["(my.task/update! :seon.task/plan str \" Then more.\")" :text "Then more.\""]
              ["(string? (my.task/update! :seon.task/plan str \"\"))" :text "true"]
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

(deftest a-new-process-takes-the-handle-after-the-branchs-highest
  ;; the owner's watch, 2026-10-28 (a4's branch): a root restart killed run #4's seon.turn/work!
  ;; eval (e218) before it was recorded, so the branch held one id fewer than its highest; run #5's
  ;; process counted the ids and took e222 again, and its record replaced the model's eval e222 (an
  ;; identity). A process's first handle follows the highest e<n> its branch holds: a gap, or an id
  ;; that is no e<n> (root's own, inherited from main), moves nothing. See docs/agent.md#your-turn
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}
        _ (d/create-database cfg)
        conn (d/connect cfg)
        _ (d/transact conn [{:db/ident :seon.eval/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}])
        ;; e2 and e3 cut off unrecorded, and one of root's ids
        _ (d/transact conn [{:seon.eval/id "e1"} {:seon.eval/id "e4"} {:seon.eval/id "root-outside-17"}])
        old @agent/state
        [r1 r2] (try (reset! agent/state {:n 0 :ns (create-ns 'seon.repl-test.scratch) :db @conn})
                     [(repl/eval-string "1" {}) (repl/eval-string "2" {})]
                     (finally (reset! agent/state old) (d/release conn)))]
    (is (= 'result/e5 (:handle r1)) "after e4, the highest the branch holds: never e4 again")
    (is (= 'result/e6 (:handle r2)) "then this process's own count")))

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
        [problems & items] (rest (at/in-agent (fn [{:keys [agent]}] (into [{:op :context :agent agent} cited] forms))))]
    (is (= [] (:value problems)) (str "every name the instructions cite resolves in the program: " (pr-str problems)))
    (is (some #{"(my.branch/unmerged)"} forms) "merge's own: the entries it resolves")
    (doseq [[form item] (map vector forms items)]
      (is (not (:ex item)) (str form " -> " (pr-str item))))
    (is (= (count forms) (count items)) "every form answered")))

(deftest the-help-page-runs
  ;; task seon.context/help-is-a-checked-docs-page (v1's help was tested data, its most-proven
  ;; turn saver): every form the help page tells an agent to run works in a real agent, in
  ;; order, the ending functions last, and every name it cites resolves in the program
  (let [forms (runnable (docs/page-text (my.test/with (my.test/world)
                                                    ;; the help page alone: the forms it tells an agent to run (the agent under test reads the rest)
                                                    (my.test/page context/help-page (slurp (str (cfg/checkout) "/" context/help-page))))
                                       context/help-page))
        end? #(or (= "(my.turn/wait)" %) (str/starts-with? % "(my.task/fail!"))
        forms (concat (remove end? forms) (sort-by #(= "(my.turn/wait)" %) (filter end? forms)))
        cited (str "(seon.task/citation-problems (my.db/db) [{:seon.task/ns 'demo :seon.task/name \"help\" :seon.task/plan (seon.context/help-text (my.db/db))}])")
        [problems & items] (rest (at/in-agent (fn [{:keys [agent]}] (into [{:op :context :agent agent} cited] forms))))]
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

(deftest a-name-that-is-no-var-says-so
  ;; the live rounds (the archived paper live-2026-10-25.md (git history)): (my.agent/callers 'seon.proof/status) answered [],
  ;; read as "no callers", and the run asked again until it ended :no-progress. A symbol that names
  ;; no var says so, with the form for the registry key of that name when there is one
  (let [[_ nothing key-name real callees library _ fresh aliased] (at/in-agent #(vector {:op :context :agent (:agent %)}
                                                         "(my.agent/callers 'seon.task/no-such-thing)"
                                                         "(my.agent/callers 'seon.task/plan)"
                                                         "(my.agent/callers 'seon.task/accumulate)"
                                                         "(my.agent/callees 'seon.task/no-such-thing)"
                                                         "(my.agent/callers 'clojure.core/map)"
                                                         "(defn zz-fresh [] 1)"
                                                         "(my.agent/callers (symbol (str *ns*) \"zz-fresh\"))"
                                                         "(do (require '[clojure.string :as s-alias]) (my.agent/callers 's-alias/join))"))]
    (is (= :my.agent/no-var (:seon.error/type (:value nothing))) (pr-str nothing))
    (is (= 'seon.task/no-such-thing (:name (:value nothing))) "it names what was asked")
    (is (nil? (:ask (:value nothing))) "and no key of that name: nothing to ask instead")
    (is (= "(my.agent/callers :seon.task/plan)" (:ask (:value key-name))) "a registry key of that name: the form that asks for it")
    (is (vector? (:value real)) "a var still answers its callers")
    (is (= :my.agent/no-var (:seon.error/type (:value callees))) "callees says the same")
    (is (vector? (:value library)) "a library's var is a var")
    (is (vector? (:value fresh)) (str "a var just defined at the REPL is a var: " (pr-str fresh)))
    (is (seq (:value aliased)) (str "an alias reads as the var it resolves to, whose callers are recorded by its full name: " (pr-str aliased)))))

(deftest a-missing-name-says-the-nearest
  ;; the live rounds' repeats (the archived paper live-2026-10-25.md (git history); jolt/repeat-causes): agents re-sent a guessed
  ;; name after "Source not found ... If your plan names it as new, write it" and after a missing
  ;; namespace with no alternative. The one answer (my.agent/no-var) names the nearest existing names
  ;; by Jolt's did-you-mean ranking, and says write it only when the task cites the name
  (let [[typo no-ns bare loaded guessed _ cited new-ns] (at/in-agent ["(my.agent/no-var (seon.agent/db) 'seon.reconcile/root-cause)"
                                                         "(my.agent/no-var (seon.agent/db) 'seon.reconcil/root-causes)"
                                                         "(my.agent/no-var (seon.agent/db) 'seon.reconcil)"
                                                         "(my.agent/no-var (seon.agent/db) 'clojure.string)"
                                                         "(my.agent/no-var (seon.agent/db) 'seon.reconcile/to-write)"
                                                         "(my.task/update! :seon.task/plan str \" Add ```seon.reconcile/to-write``` and ```seon.fresh-ns/made```.\")"
                                                         "(my.agent/no-var (seon.agent/db) 'seon.reconcile/to-write)"
                                                         "(my.agent/no-var (seon.agent/db) 'seon.fresh-ns/made)"])
        msg #(str (:seon.error/message (:value %)))]
    (is (= 'seon.reconcile/root-causes (first (:nearest (:value typo)))) (str "a typo's nearest is the name it missed: " (pr-str typo)))
    (is (str/includes? (msg typo) "nearest: seon.reconcile/root-causes"))
    (is (str/includes? (msg typo) "every definition of seon.reconcile, a line each: (source seon.reconcile)"))
    (is (str/includes? (msg no-ns) "names no namespace on this branch (seon.reconcil)") (pr-str no-ns))
    (is (some #{'seon.reconcile} (:nearest (:value no-ns))) "a missing namespace's nearest are namespaces")
    (is (some #{'seon.reconcile} (:nearest (:value bare))) (str "a bare name's nearest include namespaces: " (pr-str bare)))
    (is (nil? (:value loaded)) (str "a namespace's name that names one is not missing: " (pr-str loaded)))
    (is (str/includes? (msg new-ns) "write it: (in-ns 'seon.fresh-ns) (defn made ...)") (str "a cited function of a new namespace is one to write: " (msg new-ns)))
    (is (not (str/includes? (msg guessed) "write it")) (str "a guessed name the task does not cite is not one to write: " (msg guessed)))
    (is (str/includes? (msg cited) "your task cites it, so write it: (in-ns 'seon.reconcile) (defn to-write ...)") (msg cited))))

(deftest a-broken-parser-is-named-in-a-missing-names-answer
  ;; the eval-path review (AGENTS.md §9: no swallowed error): my.agent/no-var reads the plan with the one parser;
  ;; an agent that broke seon.ref/scan is answered from the task's stored cites, and the answer names the throw,
  ;; so it learns its parser is broken (docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map)
  ;; the names as quoted symbols, so the test's reach holds them (a name inside code text is no reference)
  (let [scan 'seon.ref/scan no-var 'my.agent/no-var
        [_ broken] (at/in-agent ["(my.task/update! :seon.task/plan str \" Add ```seon.reconcile/to-write```.\")"
                                 (str "(let [was " scan "]"
                                      " (alter-var-root #'" scan " (constantly (fn [& _] (throw (ex-info \"scan is broken\" {})))))"
                                      " (try (" no-var " (seon.agent/db) 'seon.reconcile/to-write)"
                                      " (finally (alter-var-root #'" scan " (constantly was)))))")])
        msg (str (:seon.error/message (:value broken)))]
    (is (= :my.agent/no-var (:seon.error/type (:value broken))) (pr-str broken))
    (is (str/includes? msg "your plan's parse threw (scan is broken)") (str "the answer names the parser's throw: " msg))
    (is (str/includes? msg "your task cites it, so write it") (str "and still says what the stored cites say: " msg))))

(deftest help-answers-a-section
  ;; the help page is read from the wiki's sections main holds (root's boot loads them), at
  ;; the commit a turn read, through seon.docs's one reader
  (let [db0 (ts/test-db)
        db (d/db-with db0 (docs/load-tx db0 "docs" (docs/pages (cfg/checkout) "docs")))
        toc (docs/page db context/help-page)]
    (is (= ["docs/agent.md#the-agent" 1] ((juxt :seon.ref/address :seon.ref/level) (first toc))) "the page's sections, the first its title")
    (is (every? :seon.ref/lead toc) "each section leads with a sentence that stands alone")
    (is (some #(= "docs/agent.md#done" (:seon.ref/address %)) toc))
    (is (str/includes? (:seon.ref/text (docs/view db "docs/agent.md#done" :code)) "The judge counts exactly") "one section, whole")
    (is (nil? (docs/view db "docs/agent.md#nowhere" :code)) "no such section")
    (is (str/starts-with? (context/help-text db) "# The agent\n\nYou are a Seon agent") "the page, made from its sections")
    (is (nil? (docs/page db0 context/help-page)) "a db without the wiki holds no help page")))

(deftest help-reads-any-address-and-lists-on-a-miss
  ;; dogfood 2026-10-25: (my.agent/help "references") answered nil, three evals lost. Help is the
  ;; wiki's one view: the help page at :summary, any section at :code, and on a miss what it
  ;; did not find, the nearest addresses and the form that lists the rest, never nil or a page dump
  (let [[toc anchor other miss] (rest (at/in-agent #(vector {:op :context :agent (:agent %)}
                                                       "(my.agent/help)" "(my.agent/help \"done\")" "(my.agent/help \"docs/vector.md#the-model\")"
                                                       "(my.agent/help \"references\")")))
        printed #(str (:text %))]
    (is (str/includes? (printed toc) "docs/agent.md#done") "no argument: the help page's sections at summary")
    (is (str/includes? (printed anchor) "The judge counts exactly") "an anchor of the help page")
    (is (str/includes? (printed other) "docs/vector.md#the-model") "any address of the wiki")
    (is (= #{:no-section :nearest :read-one} (set (keys (:value miss)))) "a miss is the declared map, not nil")
    (is (some #(str/starts-with? % "docs/references.md") (:nearest (:value miss))) "a miss names the nearest addresses: the page that holds the word")
    (is (< (count (printed miss)) 1500) "and not the help page: a miss costs what it names")
    (is (str/includes? (printed miss) "my.agent/help") "and the form that reads one")))

(defn- outcome [ticks thunk]
  (try {:value (intr/run ticks thunk)} (catch Throwable e {:error e})))

(deftest an-endless-form-trips-its-ticks-and-is-named
  (let [{e :error} (outcome 20000000 #(loop [] (recur)))
        m (agent/error-map e)]
    (is (= :seon.repl/ticks-spent (:seon.error/type m)) "a loop that allocates nothing stops, as the typed error")
    (is (<= 20000000 (:ticks m) 20100000) "it ran its budget, and at most one poll past it")
    (is (= 20000000 (:budget m)))
    (is (contract/valid? :seon.repl/ticks-spent m) "the error is the declared variant, data for the agent"))
  (let [{e :error} (outcome 20000000 #(try (loop [] (recur)) (catch Throwable _ :swallowed)))]
    (is (= :seon.repl/ticks-spent (:seon.error/type (agent/error-map e))) "the form's own catch cannot swallow it"))
  (is (= {:value 5050} (outcome 20000000 #(reduce + (range 101)))) "a form under its budget answers its value")
  (is (= {:value 3000000} (outcome nil #(loop [i 0] (if (= i 3000000) i (recur (inc i)))))) "no budget: unbounded"))

(deftest an-interrupt-stops-only-the-request-it-arrives-in
  ;; research row 7: no client sends nREPL's `interrupt` op, so it cannot be what clears an
  ;; interrupt; root's SIGUSR1 stops the request running, and one that merges while the agent
  ;; is idle stops nothing that comes after it
  (let [[_ after-idle stopped after-stop]
        (at/in-agent #(vector {:op :eval :agent "root" :code (str "(seon.root/interrupt! sys :" (:agent %) ")")}
                             "(+ 1 2)"
                             "(do (seon.lib/c-kill (seon.lib/c-getpid) seon.lib/SIGUSR1) (loop [] (recur)))"
                             "(+ 2 2)"))]
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
    (is (str/includes? (h/html (render/html {} em)) ":jolt.error/line 4") (h/html (render/html {} em)))
    (let [r (h/html (render/html {} (assoc em :seon.error/at {:line 3 :column 6})))]
      (is (not (str/includes? r ":jolt.error/line")) r)
      (is (str/includes? r "at line 3:6") r))))

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
  ;; docs/render.md: one response per eval call, {:handle :value :out
  ;; :err :ex}, each key only when present; its forms run in order as a REPL runs them, each
  ;; one's value answered (:forms, :value their vector), and the first exception stops them; its
  ;; handle result/e<n> holds it
  (let [r (repl/eval-string "(println \"a\") (+ 1 2) (str \"x\")" {"ns" "user" :origin :outside})]
    (is (= [nil 3 "x"] (:value r)) (pr-str r))
    (is (= 3 (:forms r)))
    (is (= {:seon.print/code "a\n"} (:out r)) "what it printed, verbatim: never a string literal")
    ;; md-scan #20: (println (source-fn 'seon.ref/scan)) answered :out as a string literal, \( shown \\(, and the
    ;; agent copied the escapes back as code
    (let [r (repl/eval-string "(println \"(case c \\\\( 1)\")" {"ns" "user" :origin :outside})]
      (is (str/includes? (seon.print/response-text (dissoc r :text)) "(case c \\( 1)") (seon.print/response-text (dissoc r :text)))
      (is (not (str/includes? (seon.print/response-text (dissoc r :text)) "\\\\(")) (str "one backslash, as printed: " (seon.print/response-text (dissoc r :text)))))
    (is (not-any? #(contains? r %) [:ex :err]) "keys only when present")
    (is (= [nil 3 "x"] (:value @(find-var (:handle r)))) "the handle holds the response")
    (is (str/starts-with? (:text r) (str "{:handle " (:handle r))) "printed under its handle"))
  (let [r (repl/eval-string "(+ 1 2) (/ 1 0) (def a-call-is-one-response-never 1)" {"ns" "user" :origin :outside})]
    (is (= "Divide by zero" (:seon.error/message (:ex r))) (pr-str r))
    (is (= {:forms 2 :value [3]} (select-keys r [:forms :value])) "the value of the form before the exception, and the exception")
    (is (nil? (resolve 'user/a-call-is-one-response-never)) "a form after the exception never ran")))

(deftest an-uncontracted-definition-is-told-on-its-answer
  ;; the one derivation's review (2026-10-26): instrument computed the uncontracted-definition
  ;; warning per form and seon.repl/response dropped it, so the agent never read it. The answer
  ;; carries it, naming every uncontracted definition so far and how to fix it; a contracted one is
  ;; armed and warns nothing
  (let [old @agent/state
        ev (fn [st code] (try (reset! agent/state st) [(repl/eval-string code {}) @agent/state] (finally (reset! agent/state old))))
        st0 {:n 0 :ns (create-ns 'seon.repl-test.warned)}
        [r st1] (ev st0 "(defn warned-f [x] x)")
        [r2 _] (ev st1 "(defn warned-g [x] x) (defn armed-h {:malli/schema [:=> [:cat :int] :int]} [x] x)")
        [r3 _] (ev st0 "(defn armed-k {:malli/schema [:=> [:cat :int] :int]} [x] x)")
        w (first (:warnings r))]
    (is (= :uncontracted-definition (:seon.repl/finding w)) (pr-str r))
    (is (= ['seon.repl-test.warned/warned-f] (:uncontracted w)))
    (is (str/includes? (:seon.repl/fix w) ":malli/schema") "and how to fix it")
    (is (str/includes? (:text r) ":uncontracted-definition") "printed in the text the agent reads")
    (is (= ['seon.repl-test.warned/warned-f 'seon.repl-test.warned/warned-g] (:uncontracted (first (:warnings r2))))
        "the next names every uncontracted definition so far, once")
    (is (not (contains? r3 :warnings)) "a contracted definition warns nothing")
    (let [[r4 _] (ev st0 "(defn warned-m [x] x) (defn warned-m {:malli/schema [:=> [:cat :int] :int]} [x] x)")]
      (is (not (contains? r4 :warnings)) "one defined uncontracted, then armed in the same eval: the warning is the eval's end, none left"))
    (let [[r5 _] (ev st0 "(defn bad-n {:malli/schema [:=> [:cat :no.such/schema] :int]} [x] x)")
          f (first (:warnings r5))]
      (is (= :invalid-contract (:seon.repl/finding f)) (pr-str r5))
      (is (= 'seon.repl-test.warned/bad-n (:var f)) "naming the definition whose contract could not be armed"))))
