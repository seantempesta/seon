(ns seon.fixtures
  "The end-to-end task graphs and the scripted model's script: test fixtures, outside src
  (task seon.main/drivers-leave-src). A scripted root loads this namespace from the
  checkout's test/ (seon.main/checkout-roots!, chosen by its provider's config).

  The program: `app.calc` (and each scenario's namespace) holds stub functions that answer
  wrong values, and `app.proofs` holds the deftests that prove each task (red until the
  task's agent redefines its function). The proofs namespace sorts after the subjects, so a boot that
  loads main's program in name order loads a subject before its proof."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn] [seon.template :as tpl] [seon.source :as source] [seon.reader :as reader]))

(def calc
  "The six-task graph: add; twice and square need add; sum-sq needs both; neg (slow on
  its first turn, the one the demo cancels) and abs, which needs neg."
  [{:name "add" :stub "(defn add {:malli/schema [:=> [:cat :int :int] :int]} [a b] 0)"
    :proof "(deftest add-test (is (= 3 (app.calc/add 1 2))) (is (= 0 (app.calc/add -2 2))))"}
   {:name "twice" :needs ["add"] :stub "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] 0)"
    :proof "(deftest twice-test (is (= 8 (app.calc/twice 4))))"}
   {:name "square" :needs ["add"] :stub "(defn square {:malli/schema [:=> [:cat :int] :int]} [x] 0)"
    :proof "(deftest square-test (is (= 9 (app.calc/square 3))) (is (= 16 (app.calc/square -4))))"}
   {:name "sum-sq" :needs ["twice" "square"] :stub "(defn sum-sq {:malli/schema [:=> [:cat :int :int] :int]} [a b] 0)"
    :proof "(deftest sum-sq-test (is (= 25 (app.calc/sum-sq 3 4))))"}
   {:name "neg" :stub "(defn neg {:malli/schema [:=> [:cat :int] :int]} [x] 0)"
    :proof "(deftest neg-test (is (= -5 (app.calc/neg 5))))"}
   {:name "abs" :needs ["neg"] :stub "(defn abs {:malli/schema [:=> [:cat :int] :int]} [x] 0)"
    :proof "(deftest abs-test (is (= 5 (app.calc/abs -5))) (is (= 5 (app.calc/abs 5))))"}])

(defn- fenced {:malli/schema [:=> [:cat :string :string] :string]} [prose code] (str prose "\n```clojure\n" code "\n```\n"))

(def calc-replies
  "The scripted model's replies by task name and turn. square answers wrong first (its proof
  stays red, the next turn sees the failure and fixes it); neg is slow."
  {"add" [(fenced "add adds." "(in-ns 'app.calc)\n(defn add {:malli/schema [:=> [:cat :int :int] :int]} [a b] (+ a b))")]
   "twice" [(fenced "twice is add with itself." "(in-ns 'app.calc)\n(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] (add x x))")]
   "square" [(fenced "Squaring doubles?" "(in-ns 'app.calc)\n(defn square {:malli/schema [:=> [:cat :int] :int]} [x] (twice x))")
             (fenced "The proof shows 6, not 9: square multiplies." "(in-ns 'app.calc)\n(defn square {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))")]
   "sum-sq" [(fenced "Sum of squares." "(in-ns 'app.calc)\n(defn sum-sq {:malli/schema [:=> [:cat :int :int] :int]} [a b] (add (square a) (square b)))")]
   "neg" [(fenced "Thinking slowly first." "(Thread/sleep 8000)\n(in-ns 'app.calc)\n(defn neg {:malli/schema [:=> [:cat :int] :int]} [x] (- x))")]
   "abs" [(fenced "abs via neg." "(in-ns 'app.calc)\n(defn abs {:malli/schema [:=> [:cat :int] :int]} [x] (if (neg? x) (neg x) x))")]})

(defn program-txs
  "Two transactions writing the program of specs on main (db): the subjects (each spec's
  :stub in its :ns), then the proofs (app.proofs), each namespace's text analyzed as a file
  is (seon.source/text-tx)."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :map]] [:vector :seon.db/tx-data]]}
  [db specs]
  (let [by-ns (group-by #(:ns % 'app.calc) (filter :stub specs))
        subjects (vec (mapcat (fn [[n ss]] (source/text-tx db n (str/join "\n" (map :stub ss)) nil)) (sort-by (comp str key) by-ns)))
        proofs (keep :proof specs)]
    [subjects
     (if (seq proofs) (source/text-tx db 'app.proofs (str/join "\n" proofs) nil) [])]))

(defn- proof-vars
  "The vars a spec's proof calls: the qualified symbols its form holds outside app.proofs."
  {:malli/schema [:=> [:cat :map] [:vector :qualified-symbol]]}
  [spec]
  (->> (tree-seq coll? seq (reader/read-text (:proof spec) 'app.proofs))
       (filter qualified-symbol?) (remove #(= "app.proofs" (namespace %))) distinct vec))

(defn decls
  "Task declarations for specs, in task namespace tns, with model env: each work task
  followed by its merge task (seon.template/with-merge)."
  {:malli/schema [:=> [:cat [:vector :map] :symbol [:maybe :string]] [:vector :seon.task/declaration]]}
  [specs tns model]
  (tpl/with-merge
    (vec (for [{:keys [name needs] :as s} specs :when name]
           (cond-> {:seon.task/ns tns :seon.task/name name
                    :seon.task/plan (or (:plan s)
                                        (str "Make " (str/join ", " (map #(str "`" % "`") (proof-vars s))) " pass its proof `app.proofs/" name "-test`."
                                             (when (seq needs) (str " It may use " (str/join ", " (map #(str "`app.calc/" % "`") needs)) "."))))
                    :seon.task/proofs (or (:proofs s) #{(symbol "app.proofs" (str name "-test"))})}
             (seq needs) (assoc :seon.task/needs (set (map #(str tns "/" %) needs)))
             model (assoc :seon.task/model model))))))

;; ---- merge scenarios (M1): two tasks on overlapping definitions -----------------------

(defn- defn1 {:malli/schema [:=> [:cat :string :string] :string]} [nm body]
  (str "(defn " nm " {:malli/schema [:=> [:cat :int] :int]} [x] " body ")"))

(def scenarios
  "Merge scenarios, by name (seon.e2e runs each as a row): :specs (the program and its
  tasks, in app.m), :after (merge task -> the merge task it must follow, so the order is
  fixed), the scripted model's replies by task name and turn, and :check, the one form a
  fresh agent runs on what main runs, with :expect, what its value prints as ([printed],
  seon.e2e/fresh); :model holds the :check and :expect of a real model's run where they differ: what
  the task's plan and proofs ask of any correct solution, where :check also asserts the
  scripted model's own extras.
  - :conflict: a and b both rewrite f (same behaviour, different text); b merges after a,
    gets one conflict, and its merge agent takes main's version.
  - :semantic: a triples scale; b defines six from scale on a branch where scale doubles.
    No key overlaps, but six-test is red after b's merge agent merges main; it fixes six.
  - :sibling: a and b change two definitions of one namespace; b merges after a, and b's g
    calls a helper b defined, h3, which sorts after it. b's merge agent (forked holding
    main's program, resuming b's branch) must run b's g (session T5b's finding: loading in name
    order failed on the helper and left main's stub running).
  - :newns: n creates two namespaces at its REPL (next-review #6, #5): app.newns with a
    function, quoted data and a dynamic var, and app.newns-test whose deftest uses a
    :refer'd fn; a def in `user` stays scratch. Main must run all of it as written.
  - :hairy: a and b both rewrite f1..f4: four conflicts (:seon.config.merge/max-conflicts 3), so b's merge fails and
    is held for root (:held), with its reason; a's code is on main, where b's proofs pass too."
  {:conflict {:specs [{:name "a" :ns 'app.m :var "f" :stub (defn1 "f" "0")
                       :plan "Make `app.m/f` pass its proof `app.proofs/a-test`. Write its body as (+ x 1)." :proof "(deftest a-test (is (= 2 (app.m/f 1))))"}
                      {:name "b" :ns 'app.m :proofs #{'app.proofs/b-test} :proof "(deftest b-test (is (= 11 (app.m/f 10))))"
                       :plan "Make `app.m/f` pass its proof `app.proofs/b-test`. Write its body as (inc x)."}]
              :after {"b-merge" "a-merge"}
              :check "(app.m/f 41)" :expect ["42"]
              :replies {"a" [(fenced "f adds one." (str "(in-ns 'app.m)\n" (defn1 "f" "(+ x 1)")))]
                        "b" [(fenced "f increments." (str "(in-ns 'app.m)\n" (defn1 "f" "(inc x)")))]}}
   :semantic {:specs [{:name "a" :ns 'app.m :var "scale" :stub (defn1 "scale" "(* x 2)") :plan "Make `app.m/scale` pass `app.proofs/a-test`." :proof "(deftest a-test (is (= 9 (app.m/scale 3))))"}
                      {:name "b" :ns 'app.m :var "six" :plan "Make `app.m/six` pass `app.proofs/b-test`, using `app.m/scale`." :stub "(defn six {:malli/schema [:=> [:cat] :int]} [] 0)"
                       :proof "(deftest b-test (is (= 6 (app.m/six))))"}]
              :after {"b-merge" "a-merge"}
              :check "[(app.m/scale 3) (app.m/six)]" :expect ["[9 6]"]
              :replies {"a" [(fenced "scale triples." (str "(in-ns 'app.m)\n" (defn1 "scale" "(* x 3)")))]
                        "b" [(fenced "six is scale of 3." "(in-ns 'app.m)\n(defn six {:malli/schema [:=> [:cat] :int]} [] (scale 3))")]
                        "b-merge" [(fenced "Main's scale triples now, so six must not use it." "(in-ns 'app.m)\n(defn six {:malli/schema [:=> [:cat] :int]} [] 6)")]}}
   :sibling {:specs [{:name "a" :ns 'app.s :var "f" :stub (defn1 "f" "0") :plan "Make `app.s/f` pass `app.proofs/a-test`." :proof "(deftest a-test (is (= 2 (app.s/f 1))))"}
                     {:name "b" :ns 'app.s :var "g" :stub (defn1 "g" "0") :plan "Make `app.s/g` pass `app.proofs/b-test`." :proof "(deftest b-test (is (= 30 (app.s/g 10))))"}]
             :after {"b-merge" "a-merge"}
             :check "[(app.s/f 1) (app.s/g 10)]" :expect ["[2 30]"]
             :replies {"a" [(fenced "f adds one." (str "(in-ns 'app.s)\n" (defn1 "f" "(+ x 1)")))]
                       "b" [(fenced "g triples, through a helper." (str "(in-ns 'app.s)\n" (defn1 "h3" "(* x 3)") "\n" (defn1 "g" "(h3 x)")))]}}
   :newns {:specs [{:name "n" :ns 'app.m :proofs #{'app.newns-test/triple-test}
                    :plan "Create the namespace `app.newns` with `triple` (three times x), and its tests in `app.newns-test`, which refers `triple`."}]
           :after {}
           ;; main runs the new namespaces as written: the function, data still data, the var still
           ;; dynamic, the test (its :refer) passing, user's def absent, and the test a program test (it has a reach digest)
           :check (str "[(app.newns/triple 3) app.newns/ops app.newns/syms"
                       " (:dynamic (meta #'app.newns/*depth*)) (binding [app.newns/*depth* 1] app.newns/*depth*)"
                       " ((juxt :test :pass :fail :error) (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/test-ns 'app.newns-test)))"
                       " (some? (resolve 'user/scratch-x))"
                       " (some? ((seon.program/reach-digests (seon.agent/db) {:env \"\" :tree \"\"}) 'app.newns-test/triple-test))]")
           :expect ["[9 (inc 1) [a b] true 1 [1 1 0 0] false true]"]
           ;; the plan asks for triple and a passing test that refers it
           :model {:check (str "(let [r (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/test-ns 'app.newns-test))]"
                               " [(app.newns/triple 3) (pos? (:pass r)) (zero? (+ (:fail r) (:error r)))])")
                   :expect ["[9 true true]"]}
           :replies {"n" [(fenced "A new namespace and its tests, as at any REPL."
                                  (str/join "\n" ["(ns app.newns)"
                                                  "(defn triple {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))"
                                                  "(def ops '(inc 1))"
                                                  "(def syms '[a b])"
                                                  "(def ^:dynamic *depth* 0)"
                                                  "(ns app.newns-test"
                                                  "  (:require [clojure.test :refer [deftest is]]"
                                                  "            [app.newns :refer [triple]]))"
                                                  "(deftest triple-test (is (= 9 (triple 3))))"
                                                  "(in-ns 'user)"
                                                  "(def scratch-x 41)"]))]}}
   :loop {:specs [{:name "gen" :ns 'seon.generation :proofs #{'app.proofs/gen-test} :proof "(deftest gen-test (is (= 2 (seon.generation/generation))))"
                   :plan "Seon's own source is the program: make `seon.generation/generation` answer 2, so its proof `app.proofs/gen-test` passes."}]
          :after {}
          :replies {"gen" [(fenced "The next generation of Seon's own source."
                                   (str "(in-ns 'seon.generation)\n"
                                        "(defn generation\n  \"Which generation of Seon's own source this binary was built from: the loop's subject\n"
                                        "  (seon.e2e/loop-run). A task redefines it on its branch, the merge is written back and\n"
                                        "  committed on the merges branch, and the binary built from that commit answers the new\n"
                                        "  value.\"\n  {:malli/schema [:=> [:cat] :int]}\n  [] 2)"))]}}
   ;; the loop's second turn (seon.source-test: a restart catches up on two merges); its proof
   ;; resolves the new var when it runs (the proof is compiled before the var exists)
   :loop-2 {:specs [{:name "gens" :ns 'seon.generation :proofs #{'app.proofs/gens-test} :proof "(deftest gens-test (is (= [1 2] ((resolve 'seon.generation/generations)))))"
                     :plan "Seon's own source is the program: define `seon.generation/generations`, the generations so far, so its proof `app.proofs/gens-test` passes."}]
            :after {}
            :replies {"gens" [(fenced "The generations so far."
                                      (str "(in-ns 'seon.generation)\n"
                                           "(defn generations\n  \"The generations of Seon's own source so far: the loop's second subject.\"\n"
                                           "  {:malli/schema [:=> [:cat] [:vector :int]]}\n  [] [1 2])"))]}}
   :hairy {:specs (into [{:name "a" :ns 'app.m :var "f1" :stub (defn1 "f1" "0") :proofs #{'app.proofs/a-test}
                          :proof "(deftest a-test (is (= [2 3 4 5] [(app.m/f1 1) (app.m/f2 1) (app.m/f3 1) (app.m/f4 1)])))"}
                         {:name "b" :ns 'app.m :proofs #{'app.proofs/b-test}
                          :proof "(deftest b-test (is (= [11 12 13 14] [(app.m/f1 10) (app.m/f2 10) (app.m/f3 10) (app.m/f4 10)])))"}]
                        (for [k [2 3 4]] {:var (str "f" k) :ns 'app.m :stub (defn1 (str "f" k) "0")}))
           :after {"b-merge" "a-merge"} :held {"b-merge" ":seon.config.merge/max-conflicts"}
           :check "(mapv #(% 10) [app.m/f1 app.m/f2 app.m/f3 app.m/f4])" :expect ["[11 12 13 14]"]
           :replies {"a" [(fenced "Each fK adds K." (str "(in-ns 'app.m)\n" (str/join "\n" (for [k [1 2 3 4]] (defn1 (str "f" k) (str "(+ x " k ")"))))))]
                     "b" [(fenced "Each fK adds K, other way round." (str "(in-ns 'app.m)\n" (str/join "\n" (for [k [1 2 3 4]] (defn1 (str "f" k) (str "(+ " k " x)"))))))]}}})

(defn swarm-specs
  "n tasks over 5 shared definitions: task sK rewrites app.sw/v(K mod 5) to add (K mod
  5) in its own text (so two tasks on one var conflict) and proves it."
  {:malli/schema [:=> [:cat pos-int?] [:vector :map]]}
  [n]
  (into (vec (for [j (range 5)] {:var (str "v" j) :ns 'app.sw :stub (defn1 (str "v" j) "0")}))
        (for [k (range n) :let [j (mod k 5)]]
          {:name (str "s" k) :ns 'app.sw :proofs #{(symbol "app.proofs" (str "s" k "-test"))}
           :proof (str "(deftest s" k "-test (is (= " (+ 100 j) " (app.sw/v" j " 100))))")
           :plan (str "Make `app.sw/v" j "` add " j "; its proof is `app.proofs/s" k "-test`.")})))

(defn- swarm-reply
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [nm]
  (when-let [k (and (str/starts-with? nm "s") (parse-long (subs nm 1)))]
    (let [j (mod k 5)]
      (fenced "Rewrite in my own words." (str "(in-ns 'app.sw)\n" (defn1 (str "v" j) (str "(+ x " j (str/join (repeat (quot k 5) " 0")) ")")))))))

(def ^:private generic
  "The scripted model's reply by task kind (from the name): a merge agent takes main's side of
  every unmerged entry; a design agent improves the plan and waits."
  {"-merge" (fenced "Take main's version of each conflict." "(doseq [e (my.branch/unmerged)] (my.branch/resolve! (second (:key e)) :theirs))")
   "-design" (fenced "Main's code is the base now." "(my.task/update! :seon.task/plan str \" Design: build on main's definitions as they are.\")\n(my.turn/wait)")})

(def measure-replies
  "A scripted run whose evals answer values as a real run's do (a source, a vector of rows, a
  long vector, the task's status, one value again), for measuring what a turn sends (tokens per
  turn) before and after a change to how records print, on task seon.ref/a-summary-says-there-is-more."
  {"seon.ref/a-summary-says-there-is-more"
   [(fenced "Read the function the plan names." "(clojure.repl/source seon.ref/shown)")
    (fenced "A table of rows." "(vec (for [i (range 120)] {:i i :name (str \"item-\" i) :tags #{:a :b}}))")
    (fenced "A long value." "(vec (range 400))")
    (fenced "The task's state." "(my.task/status)")
    (fenced "The rows again." "(vec (for [i (range 120)] {:i i :name (str \"item-\" i) :tags #{:a :b}}))")
    (fenced "A small value." "(+ 1 2)")
    (fenced "Done reading." "(my.turn/wait)")]})

(def fit-requests
  "The five tasks fit to measure (jolt/fit-tasks, 2026-10-24), as a user would ask for them: a
  short problem description each, no proof, no write set (design writes its own proofs, red, and
  names what to change), each a request for seon.template/request (design -> implement -> merge on
  one branch: my.root/request! writes it). Kept here, not run: the clean path's measurement waits
  for the owner's go (root, 2026-10-25)."
  [{:seon.task/ns 'seon.ref :seon.task/name "a-summary-says-what-it-left"
    :seon.task/plan "When a wiki section is cited at summary depth, the agent sees only the first sentence of its lead and nothing says there is more. It should say how much of the lead it left out and how to read the rest."}
   {:seon.task/ns 'seon.model :seon.task/name "the-transport-failure-docstring-is-true"
    :seon.task/plan "The docstring of seon.model/transport-error says a failed request is kept and retried after a pause, but a transport failure goes straight back to the agent. Make the docstring say what really happens."}
   {:seon.task/ns 'seon.context :seon.task/name "a-proof-that-never-ran-says-so"
    :seon.task/plan "When one of my proofs has no test result yet, my status says it ran and asserted nothing. It should say it has not run."}
   {:seon.task/ns 'seon.print :seon.task/name "a-long-name-cut-says-its-chars"
    :seon.task/plan "When the printer cuts a long keyword it just shows …+N; it should say how many chars it left and how to read the rest."}
   {:seon.task/ns 'seon.print :seon.task/name "a-map-shows-its-first-entry"
    :seon.task/plan "Printing a big map with a small budget fails, saying it needs 33 characters, while a vector of the same size shows its first item and a cut. A map should show its first entry and a cut too."}])

(def ^:private all-replies
  "Scripted replies by task name (calc) and by task id (a scenario's tasks live in a task
  namespace named after it, e.g. conflict/b-merge; a measurement's, measure-replies)."
  (-> calc-replies (into measure-replies) (into (for [[k {:keys [replies]}] scenarios [nm r] replies] [(str (name k) "/" nm) r]))))

(defn same-call
  "The scripted model in a loop (seon.agent-test): every reply makes the same call,
  whatever it saw (the step E dry run's first real design made one pair of calls 18 times)."
  {:malli/schema [:=> [:cat :seon.model/request] :string]}
  [_] "(+ 1 1)")

(defn two-calls
  "The scripted model that proves a turn's rhythm (seon.agent-test): its first call evaluates
  (+ 20 21); each later call adds one to the value the last call's response showed, so its
  code holds what it saw before it was made."
  {:malli/schema [:=> [:cat :seon.model/request] :string]}
  [{:keys [messages]}]
  (if-let [seen (some #(when (= "tool" (:role %)) (edn/read-string (:content %))) (rseq messages))]
    (str "(+ 1 " (:value seen) ")")
    "(+ 20 21)"))

(defn thinking-call
  "The scripted model that proves a reply's prose is recorded with its call (seon.agent-test):
  one form, and the prose beside it (the scripted provider's reply text)."
  {:malli/schema [:=> [:cat :seon.model/request] [:map [:code :string] [:thinking :string]]]}
  [_] {:code "(+ 1 2)" :thinking "check the sum before the proof"})

(defn wait-call
  "The scripted model of a session that waits (seon.agent-test): every turn waits for a message
  (my.turn/wait), so each run parks and the next message wakes it (design agent-loop section 2)."
  {:malli/schema [:=> [:cat :seon.model/request] :string]}
  [_] "(my.turn/wait)")

(defn stages
  "The scripted model of a request's stages on one session (seon.agent-test): the design writes
  a proof red, registers it and adds to its plan, then ends its turn with prose (done: its proof
  red and run, its plan changed); the implement and the merge wait (each parks)."
  {:malli/schema [:=> [:cat :seon.model/request] :string]}
  [{:keys [task turn]}]
  (cond (or (str/ends-with? task "-implement") (str/ends-with? task "-merge")) "(my.turn/wait)"
        (zero? turn) (fenced "Write the proof, red, and say what implement changes."
                             (str "(in-ns 'app.stages-test)\n(clojure.test/deftest t (clojure.test/is (= 1 2)))\n"
                                  "(my.task/update! :seon.task/proofs conj 'app.stages-test/t)\n"
                                  "(my.task/update! :seon.task/plan str \" Implement makes app.stages-test/t pass.\")"))
        :else "The proof is written and red, and the plan says what implement changes."))

(defn reply
  "The scripted model: the scripted reply for the request's task and turn (the last one
  repeats); a swarm task's reply is generated from its index; a merge or design task
  without a script gets the generic reply of its kind; any other task, prose without code."
  {:malli/schema [:=> [:cat :seon.model/request] :string]}
  [{:keys [task turn]}]
  (let [nm (subs task (inc (str/index-of task "/")))]
    (if-let [rs (or (get all-replies task) (get all-replies nm))]
      (nth rs (min turn (dec (count rs))))
      (or (some (fn [[suffix r]] (when (str/ends-with? nm suffix) r)) generic)
          (swarm-reply nm)
          "No script for this task."))))
