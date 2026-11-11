(ns seon.fixtures
  "The end-to-end task graphs and the scripted model's script: test fixtures, outside src. A scripted root loads this namespace from the
  checkout's test/ (seon.main/checkout-roots!, chosen by its provider's config).

  The program: `app.calc` (and each scenario's namespace) holds stub functions that answer
  wrong values, and `app.proofs` holds the deftests that prove each task (red until the
  task's agent redefines its function). The proofs namespace sorts after the subjects, so a boot that
  loads main's program in name order loads a subject before its proof."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn] [seon.template :as tpl] [seon.source :as source] [seon.reader :as reader] [seon.config :as config] [seon.render] [clojure.test :refer [is]]))

(def calc
  "The six-task graph: add; twice and square need add; sum-sq needs both; neg (the one the demo
  cancels, seon.fixtures/calc-cancel) and abs, which needs neg."
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

(defn- calls
  "A scripted reply of eval calls: the text is text, and each code string (its forms)
  is a call that carries the same text (docs/prompt.md)."
  {:malli/schema [:=> [:cat :string [:* :string]] :seon.model/script-reply]}
  [text & codes]
  {:text text :calls (mapv (fn [c] {:intent text :code c}) codes)})

(defn- says
  "A scripted reply of text and no call: a message, which ends the turn (docs/prompt.md)."
  {:malli/schema [:=> [:cat :string] :seon.model/script-reply]}
  [text]
  {:text text})

(def calc-replies
  "The scripted model's replies by task name and turn. square answers wrong first (its proof
  stays red, the next turn sees the failure and fixes it); each reply is one turn's calls."
  {"add" [(calls "add adds." "(in-ns 'app.calc)" "(defn add {:malli/schema [:=> [:cat :int :int] :int]} [a b] (+ a b))")]
   "twice" [(calls "twice is add with itself." "(in-ns 'app.calc)" "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x] (add x x))")]
   "square" [(calls "Squaring doubles?" "(in-ns 'app.calc)" "(defn square {:malli/schema [:=> [:cat :int] :int]} [x] (twice x))")
             (calls "The proof shows 6, not 9: square multiplies." "(in-ns 'app.calc)" "(defn square {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))")]
   "sum-sq" [(calls "Sum of squares." "(in-ns 'app.calc)" "(defn sum-sq {:malli/schema [:=> [:cat :int :int] :int]} [a b] (add (square a) (square b)))")]
   "neg" [(calls "Thinking slowly first." "(in-ns 'app.calc)" "(defn neg {:malli/schema [:=> [:cat :int] :int]} [x] (- x))")]
   "abs" [(calls "abs via neg." "(in-ns 'app.calc)" "(defn abs {:malli/schema [:=> [:cat :int] :int]} [x] (if (neg? x) (neg x) x))")]})

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
  ;; a plan cites the program the specs write; any other var its proof calls (seon.cost/proof) a literal, as a world
  ;; without it holds nothing it names (docs/references.md#an-entity-cites)
  (let [nss (into #{} (map #(str (:ns % 'app.calc))) specs)]
  (tpl/with-merge
    (vec (for [{:keys [name needs] :as s} specs :when name]
           (cond-> {:seon.task/ns tns :seon.task/name name
                    :seon.task/plan (or (:plan s)
                                        (str "Make " (str/join ", " (map #(if (nss (namespace %)) (str "`" % "`") (str "``" % "``")) (proof-vars s))) " pass its proof ``app.proofs/" name "-test``."
                                             (when (seq needs) (str " It may use " (str/join ", " (map #(str "`app.calc/" % "`") needs)) "."))))
                    :seon.task/proofs (or (:proofs s) #{(symbol "app.proofs" (str name "-test"))})}
             (seq needs) (assoc :seon.task/needs (set (map #(str tns "/" %) needs)))
             model (assoc :seon.task/model model)))))))

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
              :replies {"a" [(calls "f adds one." "(in-ns 'app.m)" (defn1 "f" "(+ x 1)"))]
                        "b" [(calls "f increments." "(in-ns 'app.m)" (defn1 "f" "(inc x)"))]}}
   :semantic {:specs [{:name "a" :ns 'app.m :var "scale" :stub (defn1 "scale" "(* x 2)") :plan "Make `app.m/scale` pass `app.proofs/a-test`." :proof "(deftest a-test (is (= 9 (app.m/scale 3))))"}
                      {:name "b" :ns 'app.m :var "six" :plan "Make `app.m/six` pass `app.proofs/b-test`, using `app.m/scale`." :stub "(defn six {:malli/schema [:=> [:cat] :int]} [] 0)"
                       :proof "(deftest b-test (is (= 6 (app.m/six))))"}]
              :after {"b-merge" "a-merge"}
              :check "[(app.m/scale 3) (app.m/six)]" :expect ["[9 6]"]
              :replies {"a" [(calls "scale triples." "(in-ns 'app.m)" (defn1 "scale" "(* x 3)"))]
                        "b" [(calls "six is scale of 3." "(in-ns 'app.m)" "(defn six {:malli/schema [:=> [:cat] :int]} [] (scale 3))")]
                        "b-merge" [(calls "Main's scale triples now, so six must not use it." "(in-ns 'app.m)" "(defn six {:malli/schema [:=> [:cat] :int]} [] 6)")]}}
   :sibling {:specs [{:name "a" :ns 'app.s :var "f" :stub (defn1 "f" "0") :plan "Make `app.s/f` pass `app.proofs/a-test`." :proof "(deftest a-test (is (= 2 (app.s/f 1))))"}
                     {:name "b" :ns 'app.s :var "g" :stub (defn1 "g" "0") :plan "Make `app.s/g` pass `app.proofs/b-test`." :proof "(deftest b-test (is (= 30 (app.s/g 10))))"}]
             :after {"b-merge" "a-merge"}
             :check "[(app.s/f 1) (app.s/g 10)]" :expect ["[2 30]"]
             :replies {"a" [(calls "f adds one." "(in-ns 'app.s)" (defn1 "f" "(+ x 1)"))]
                       "b" [(calls "g triples, through a helper." "(in-ns 'app.s)" (defn1 "h3" "(* x 3)") (defn1 "g" "(h3 x)"))]}}
   :newns {:specs [{:name "n" :ns 'app.m :proofs #{'app.newns-test/triple-test}
                    :plan "Create the namespace `app.newns` with `triple` (three times x), and its tests in `app.newns-test`, which refers `triple`."}]
           :after {}
           ;; main runs the new namespaces as written: the function, data still data, the var still
           ;; dynamic, the test (its :refer) passing, user's def absent, and the test a program test (it has a reach digest)
           :check (str "[(app.newns/triple 3) app.newns/ops app.newns/syms"
                       " (:dynamic (meta #'app.newns/*depth*)) (binding [app.newns/*depth* 1] app.newns/*depth*)"
                       " ((juxt :test :pass :fail :error) (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/test-ns 'app.newns-test)))"
                       " (some? (resolve 'user/scratch-x))"
                       " (some? ((update-vals (seon.program/graph-index (seon.program/reach-graph (seon.agent/db) nil) {:env \"\" :tree \"\" :config {}} nil) :digest) 'app.newns-test/triple-test))]")
           :expect ["[9 (inc 1) [a b] true 1 [1 1 0 0] false true]"]
           ;; the plan asks for triple and a passing test that refers it
           :model {:check (str "(let [r (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/test-ns 'app.newns-test))]"
                               " [(app.newns/triple 3) (pos? (:pass r)) (zero? (+ (:fail r) (:error r)))])")
                   :expect ["[9 true true]"]}
           :replies {"n" [(calls "A new namespace and its tests, as at any REPL."
                                 "(ns app.newns)"
                                 "(defn triple {:malli/schema [:=> [:cat :int] :int]} [x] (* 3 x))"
                                 "(def ops '(inc 1))"
                                 "(def syms '[a b])"
                                 "(def ^:dynamic *depth* 0)"
                                 "(ns app.newns-test\n  (:require [clojure.test :refer [deftest is]]\n            [app.newns :refer [triple]]))"
                                 "(deftest triple-test (is (= 9 (triple 3))))"
                                 "(in-ns 'user)"
                                 "(def scratch-x 41)")]}}
   :loop {:specs [{:name "gen" :ns 'seon.generation :proofs #{'app.proofs/gen-test} :proof "(deftest gen-test (is (= 2 (seon.generation/generation))))"
                   :plan "Seon's own source is the program: make `seon.generation/generation` answer 2, so its proof `app.proofs/gen-test` passes."}]
          :after {}
          :replies {"gen" [(calls "The next generation of Seon's own source."
                                  "(in-ns 'seon.generation)"
                                  (str "(defn generation\n  \"Which generation of Seon's own source this binary was built from: the loop's subject\n"
                                        "  (seon.e2e/loop-run). A task redefines it on its branch, the merge is written back and\n"
                                        "  committed on the merges branch, and the binary built from that commit answers the new\n"
                                        "  value.\"\n  {:malli/schema [:=> [:cat] :int]}\n  [] 2)"))]}}
   ;; the loop's second turn (seon.source-test: a restart catches up on two merges); its proof
   ;; resolves the new var when it runs (the proof is compiled before the var exists)
   :loop-2 {:specs [{:name "gens" :ns 'seon.generation :proofs #{'app.proofs/gens-test} :proof "(deftest gens-test (is (= [1 2] ((resolve 'seon.generation/generations)))))"
                     :plan "Seon's own source is the program: define `seon.generation/generations`, the generations so far, so its proof `app.proofs/gens-test` passes."}]
            :after {}
            :replies {"gens" [(calls "The generations so far."
                                     "(in-ns 'seon.generation)"
                                     (str "(defn generations\n  \"The generations of Seon's own source so far: the loop's second subject.\"\n"
                                           "  {:malli/schema [:=> [:cat] [:vector :int]]}\n  [] [1 2])"))]}}
   :hairy {:specs (into [{:name "a" :ns 'app.m :var "f1" :stub (defn1 "f1" "0") :proofs #{'app.proofs/a-test}
                          :proof "(deftest a-test (is (= [2 3 4 5] [(app.m/f1 1) (app.m/f2 1) (app.m/f3 1) (app.m/f4 1)])))"}
                         {:name "b" :ns 'app.m :proofs #{'app.proofs/b-test}
                          :proof "(deftest b-test (is (= [11 12 13 14] [(app.m/f1 10) (app.m/f2 10) (app.m/f3 10) (app.m/f4 10)])))"}]
                        (for [k [2 3 4]] {:var (str "f" k) :ns 'app.m :stub (defn1 (str "f" k) "0")}))
           :after {"b-merge" "a-merge"} :held {"b-merge" ":seon.config.merge/max-conflicts"}
           :check "(mapv #(% 10) [app.m/f1 app.m/f2 app.m/f3 app.m/f4])" :expect ["[11 12 13 14]"]
           :replies {"a" [(apply calls "Each fK adds K." "(in-ns 'app.m)" (for [k [1 2 3 4]] (defn1 (str "f" k) (str "(+ x " k ")"))))]
                     "b" [(apply calls "Each fK adds K, other way round." "(in-ns 'app.m)" (for [k [1 2 3 4]] (defn1 (str "f" k) (str "(+ " k " x)"))))]}}})

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
  "Work task sK's scripted reply: a conflict main brought onto its branch (a work agent merges main
  every turn it moved, seon.turn/work!) is resolved keeping its own side, as a work agent does
  before it can be done, then its definition is written. Whether a conflict meets a task's work
  agent or its merge agent is the order main's merges came in (swarm red once, 2026-10-31: sw/s5's
  second run met s0's v0 and, never resolving it, ran to its cap); both resolve it."
  {:malli/schema [:=> [:cat :string] [:maybe :seon.model/script-reply]]}
  [nm]
  (when-let [k (and (str/starts-with? nm "s") (parse-long (subs nm 1)))]
    (let [j (mod k 5)]
      (calls "Keep my side of a conflict main brought, then rewrite in my own words."
             "(doseq [e (my.branch/unmerged)] (my.branch/resolve! (second (:key e)) :ours))"
             "(in-ns 'app.sw)" (defn1 (str "v" j) (str "(+ x " j (str/join (repeat (quot k 5) " 0")) ")"))))))

(def ^:private generic
  "The scripted model's reply by task kind (from the name): a merge agent takes main's side of
  every unmerged entry; a design agent improves the plan and waits."
  {"-merge" (calls "Take main's version of each conflict." "(doseq [e (my.branch/unmerged)] (my.branch/resolve! (second (:key e)) :theirs))")
   "-design" (calls "Main's code is the base now." "(my.task/update! :seon.task/plan str \" Design: build on main's definitions as they are.\")" "(my.turn/wait)")})

(def measure-replies
  "A scripted run whose evals answer values as a real run's do (a source, a vector of rows, a
  long vector, the task's status, one value again), for measuring what a turn sends (tokens per
  turn) before and after a change to how records print, on task seon.ref/a-summary-says-there-is-more."
  {"seon.ref/a-summary-says-there-is-more"
   [(calls "Read the function the plan names." "(clojure.repl/source seon.ref/shown)")
    (calls "A table of rows." "(vec (for [i (range 120)] {:i i :name (str \"item-\" i) :tags #{:a :b}}))")
    (calls "A long value." "(vec (range 400))")
    (calls "The task's state." "(my.task/status)")
    (calls "The rows again." "(vec (for [i (range 120)] {:i i :name (str \"item-\" i) :tags #{:a :b}}))")
    (calls "A small value." "(+ 1 2)")
    (calls "Done reading." "(my.turn/wait)")]})

(def requests
  "The nine requests a live run measures, as a user would ask for them: a short problem
  description and the one form that reproduces it, with what it answers and what it should, no
  proof and no write set (its agent writes its own proof, red, and names what to change). Each is
  a request for seon.template/request (design-build, then merge: my.root/request! writes it).
  Each reproduction was run on main before the set was used: a request whose reproduction
  answers right is done, and is replaced, never measured."
  [{:seon.task/ns 'seon.ctx :seon.task/name "a-message-and-an-eval-of-one-id-both-print"
    :seon.task/plan "When a run holds a message and an eval with the same id, the conversation keeps only one of them, because the printed parts are looked up by the bare id. Each record should be kept under its own kind, so both are printed. Reproduce: (let [ev [[:eval \"x\"] {:seon.eval/id \"x\" :seon.eval/by {:seon.agent/key \"a1\"} :seon.eval/code \"(+ 1 1)\" :seon.eval/call-id \"c1\" :seon.eval/response {:handle 'result/x :value 2 :ms 1}} :seon.db/eval-fact 1] msg [[:message \"x\"] {:seon.message/id \"x\" :seon.message/from {:seon.root/id :root} :seon.message/body \"hello\"} :seon.message/fact 2] c (#'seon.ctx/printed-run [ev msg] nil nil)] [(count (:ids c)) (count (:parts c))]) answers [1 1]; it should answer [2 2]."}
   {:seon.task/ns 'my.agent :seon.task/name "the-help-docstring-says-what-it-does-now"
    :seon.task/plan "The docstring of my.agent/help tells, in its middle, what an anchor answered before this change, which is history, not what the function does. Make it say only what the function answers now. Reproduce: (re-find #\"Before this\" (:doc (meta #'my.agent/help))) answers \"Before this\"; it should answer nil."}
   {:seon.task/ns 'seon.print :seon.task/name "a-namespaced-map-indents-its-keys-past-its-opener"
    :seon.task/plan "A map whose keys share a namespace prints as #:seon.task{:kind :work, and its later keys sit one column in, under the #, so they read as the parent's. Keys should start one column past the opener, and the budget should count that. Reproduce: (let [{:keys [text]} (seon.print/print-value {:seon.task/kind :work :seon.task/id \"demo/f\" :seon.task/plan (apply str (repeat 70 \"p\"))} {:handle 'result/h1 :width 40})] (mapv #(count (re-find #\"^ *\" %)) (clojure.string/split-lines text))) answers [0 1 1 3]; it should answer [0 12 12 14], the keys starting past #:seon.task{. Its cause: seon.print.emit/emit-collection nests a map's members one column past the group's indentation, not past its opener, and seon.print.select/member-indent charges that same one column, not knowing the lift."}
   {:seon.task/ns 'seon.print :seon.task/name "a-collection-beside-a-long-string-shows-its-first-part"
    :seon.task/plan "When one member of a value is a long string and another a big collection, the string takes the whole budget and the collection shows nothing, not even its first element. Every member should show its first part before any one grows. Reproduce: (let [v {:form \"(code-of 0)\" :out (apply str (repeat 480 \"output line of the code\\n\")) :value (vec (range 2000))}] (boolean (re-find #\":value \\[0\" (:text (seon.print/print-value v {:budget 545 :handle 'result/e1}))))) answers false (the value prints as […2000 (get-in result/e1 [:value])]); it should answer true."}
   {:seon.task/ns 'seon.render.ui :seon.task/name "a-size-just-under-a-megabyte-rolls-over"
    :seon.task/plan "A size just under a megabyte reads 1024.0 KB: the rounding should carry into the next unit. Reproduce: (seon.render.ui/bytes-str 1048575) answers \"1024.0 KB\"; it should answer \"1.0 MB\"."}
   {:seon.task/ns 'seon.ref :seon.task/name "a-first-sentence-ends-at-its-question-mark"
    :seon.task/plan "A section's summary ends at the first full stop followed by a space, so a lead that opens with a question, or with a full stop inside brackets, shows too much. Reproduce: (mapv seon.ref/lead-sentence [\"Is it fast? Yes. It is.\" \"A thing (see the page.) Then more.\"]) answers [\"Is it fast? Yes.\" \"A thing (see the page.) Then more.\"]; it should answer [\"Is it fast?\" \"A thing (see the page.)\"]."}
   {:seon.task/ns 'seon.print.emit :seon.task/name "a-cut-of-one-character-says-char"
    :seon.task/plan "A cut that left one character says …+1 chars; it should say …+1 char, as the line count already says 1 more line. Reproduce: [(seon.print.emit/cut-words 1 0 nil) (seon.print.emit/cut-words 1 1 nil)] answers [\"…+1 chars\" \"…+1 chars in 1 more line\"]; it should answer [\"…+1 char\" \"…+1 char in 1 more line\"]."}
   {:seon.task/ns 'seon.ref :seon.task/name "a-name-ending-in-a-star-is-not-cited-as-another"
    :seon.task/plan "A name ending in * is never a reference, but in text its trailing star is trimmed as if it were emphasis, so ns.x/f* is cited as ns.x/f, another var. Reproduce: (seon.ref/names \"Callers of seon.print.emit/foo* are listed.\") answers [seon.print.emit/foo]; it should answer []."}])

(def ^:private all-replies
  "Scripted replies by task name (calc) and by task id (a scenario's tasks live in a task
  namespace named after it, e.g. conflict/b-merge; a measurement's, measure-replies)."
  (-> calc-replies (into measure-replies) (into (for [[k {:keys [replies]}] scenarios [nm r] replies] [(str (name k) "/" nm) r]))))

(defn same-call
  "The scripted model in a loop (seon.agent-test): every reply makes the same call,
  whatever it saw (the step E dry run's first real design made one pair of calls 18 times)."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_] (calls "Add one and one." "(+ 1 1)"))

(defn three-calls
  "The scripted model of a reply of three calls that all run (seon.agent-test): what one model turn's evals commit as,
  each as it lands (docs/turn.md#the-turn-is-a-number)."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_] {:calls [{:intent "Define a." :code "(def a 1)"}
               {:intent "Define b." :code "(def b 2)"}
               {:intent "Add them." :code "(+ a b)"}]})

(defn two-calls
  "The scripted model that proves a turn's rhythm (seon.agent-test): its first call evaluates
  (+ 20 21); each later call adds one to the value the last call's response showed, so its
  code holds what it saw before it was made. The answers it reads are its own calls', not the page Seon generated as an
  eval of the agent's (a call of my.render/, docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup)."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [messages]}]
  (if-let [seen (let [page-ids (set (for [m messages c (:tool-calls m) :when (str/starts-with? (str (:code c)) "(my.render/")] (:id c)))]
                  (some #(when (and (= "tool" (:role %)) (not (page-ids (:tool-call-id %)))) (edn/read-string (:content %))) (rseq messages)))]
    (calls "Add one to the value the last call showed." (str "(+ 1 " (:value seen) ")"))
    (calls "Add twenty and twenty-one." "(+ 20 21)")))

(defn calls-one-throws
  "The scripted model of a reply whose second call throws (seon.agent-test): three calls, the
  third never run (docs/prompt.md)."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_] {:calls [{:intent "Define a." :code "(def a 1)"}
               {:intent "Throw." :code "(throw (ex-info \"boom\" {}))"}
               {:intent "Increment a." :code "(inc a)"}]})

(defn calls-one-refused
  "The scripted model of a reply whose second call answers an error value, an edit refused (seon.agent-test): three
  calls, the third, built on the edit, never run (task seon.turn/an-agent-that-breaks-its-own-render-is-told-and-goes-on)."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_] {:calls [{:intent "Define a." :code "(def a 1)"}
               {:intent "Change a text that is not there." :code "(seon.repl/edit! 'clojure.core/inc \"no such text\" \"x\")"}
               {:intent "Increment a." :code "(inc a)"}]})

(defn two-forms-in-a-call
  "The scripted model of a reply whose first call holds two forms (seon.agent-test): they run in
  order, and the call after it runs too (docs/prompt.md)."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_] {:calls [{:intent "Define g and increment it." :code "(def g 1) (inc g)"}
               {:intent "Add five and six." :code "(+ 5 6)"}]})

(defn thinking-call
  "The scripted model that proves a reply's message is recorded with its call (seon.agent-test):
  one form, and the text beside it."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_] (calls "check the sum before the proof" "(+ 1 2)"))

(defn wait-call
  "The scripted model of a session that waits (seon.agent-test): every turn waits for a message
  (my.turn/wait), so each run parks and the next message wakes it (design agent-loop section 2)."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_] (calls "Wait for a message." "(my.turn/wait)"))

(defn text-reply
  "The scripted model of a session that answers in text (seon.agent-test): its first reply is text
  with no call (the agent winding down); a request that holds that reply as its assistant message
  is answered by a call that says so, one that lost it by a call that says that."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [messages]}]
  (if (some #(and (= "assistant" (:role %)) (= "Nothing to run; I wait for you." (:content %))) messages)
    (calls "Saw my own reply." "(my.turn/wait)")
    ;; (an assistant message of the model's: the page Seon ran as an eval has text too, on a call of its own, and so does each call
    ;; Seon writes for the model, (my.render/ctx p), (my.render/page p) and (my.render/diff p0 p1), a whole reply of its own)
    (if (some #(and (= "assistant" (:role %)) (seq (:content %))
                    (not (some (fn [c] (or (clojure.string/starts-with? (str (:id c)) "eval-") (re-find #"^\((my\.render/|my\.agent/describe )" (str (:code c))))) (:tool-calls %)))) messages)
      (calls "Lost my own reply." "(my.turn/wait)")
      {:text "Nothing to run; I wait for you."})))

(defn fail-call
  "The scripted model of a run that refutes its premise (seon.agent-test): every turn fails the
  task (my.task/fail!), the agent's own verdict."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_] (calls "The premise is refuted, so fail the task." "(my.task/fail! \"the premise is refuted\" '(= 1 2))"))

(defn stages
  "The scripted model of a request's stages on one session (seon.agent-test): the design writes
  a proof red, registers it and adds to its plan, a call per form, then ends its turn with a
  message and no call (done: its proof red and run, its plan changed); the implement and the
  merge wait (each parks)."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [task turn]}]
  (cond (or (str/ends-with? task "-implement") (str/ends-with? task "-merge")) (calls "Wait for a message." "(my.turn/wait)")
        (zero? turn) (calls "Write the proof, red, and say what implement changes."
                            "(in-ns 'app.stages-test)"
                            "(clojure.test/deftest t (clojure.test/is (= 1 2)))"
                            "(my.task/update! :seon.task/proofs conj 'app.stages-test/t)"
                            "(my.task/update! :seon.task/plan str \" Implement makes app.stages-test/t pass.\")")
        :else (says "The proof is written and red, and the plan says what implement changes.")))

(defn reads-its-own-reply
  "The scripted model of a reply whose eval reads this very model call's record (seon.agent-test): its reply, written before its evals run, ends the work as its reason."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_]
  (calls "The reply itself."
         "(my.task/fail! (str \"read: \" (datahike.api/q '[:find ?r . :where [?m :seon.model-call/reply ?r] [?m :seon.model-call/reply \"The reply itself.\"]] (my.db/db))) '(= 1 2))"))

(defn whole-green
  "The scripted model of a whole task that goes green (seon.agent-test): it writes a proof that
  passes and registers it; the done rule then passes and the run ends, for root to merge."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [turn]}]
  (if (zero? turn)
    (calls "Write the proof, and register it."
           "(in-ns 'app.whole-test)"
           "(clojure.test/deftest t (clojure.test/is (= 1 1)))"
           "(my.task/update! :seon.task/proofs conj 'app.whole-test/t)")
    (says "The proof passes.")))

(defn whole-stray
  "The scripted model of a whole task whose proof passes while its change breaks a test of main's
  (seon.agent-test): it writes a green proof and redefines seon.repl/offsets (where edit! finds its old
  text), its contract kept, to find nothing, which turns seon.repl-test's edit test red;
  then it only replies."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [turn]}]
  (if (zero? turn)
    (calls "Write the proof, register it, and change offsets."
           "(in-ns 'app.stray-test)"
           "(clojure.test/deftest t (clojure.test/is (= 1 1)))"
           "(my.task/update! :seon.task/proofs conj 'app.stray-test/t)"
           "(in-ns 'seon.repl) (seon.contract/defn- offsets \"Finds nothing.\" {:malli/schema [:=> [:cat :string :string] [:vector nat-int?]]} [text s] [])")
    (says "The proof passes.")))

(defn whole-red
  "The scripted model of a whole task whose proof stays red (seon.agent-test): it writes a red
  proof and registers it, then waits (my.turn/wait), so its run parks with the proof red."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [turn]}]
  (if (zero? turn)
    (calls "Write the proof, red, and register it."
           "(in-ns 'app.whole-test)"
           "(clojure.test/deftest t (clojure.test/is (= 1 2)))"
           "(my.task/update! :seon.task/proofs conj 'app.whole-test/t)")
    (calls "Wait for a message." "(my.turn/wait)")))

(defn reply
  "The scripted model: the scripted reply for the request's task and turn (the last one
  repeats); a swarm task's reply is generated from its index; a merge or design task
  without a script gets the generic reply of its kind; any other task, a message without a call."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [task turn]}]
  (let [nm (subs task (inc (str/index-of task "/")))]
    (if-let [rs (or (get all-replies task) (get all-replies nm))]
      (nth rs (min turn (dec (count rs))))
      (or (some (fn [[suffix r]] (when (str/ends-with? nm suffix) r)) generic)
          (swarm-reply nm)
          (says "No script for this task.")))))

;; the curated session is read from its own file once per call (small), never held
(defn curated-turn
  "The scripted reply of turn n of the curated session in dir for task id: the file
  <dir>/<ns>_<name>.edn is a map whose :turns vector holds {:text :calls}, answered as is; other keys
  are provenance. A turn past the last, no dir, no file or one that does not read as a map (seon.reader/read-text,
  the edge for text read as data): a message that says so, which ends the turn. See docs/model.md#running-a-curated-session."
  {:malli/schema [:=> [:cat [:maybe :string] :string :int] :seon.model/script-reply]}
  [dir id n]
  (let [f (when dir (java.io.File. (str dir "/" (str/replace id "/" "_") ".edn")))]
    (if-not (and f (.exists f))
      (says (str "No curated session for " id "."))
      (let [m (first (reader/read-text (slurp f) 'user))]
        (cond (not (map? m)) (says (str "The curated session of " id " does not read as a map."))
              (get (:turns m) n) (select-keys (get (:turns m) n) [:text :calls])
              :else (says (str "The curated session has no turn " n ".")))))))

(defn curated
  "A curated session (an agent's recorded session with its dead ends cut) replayed
  through the scripted provider on a fresh branch: the scripted model for the request's task and turn,
  from <runs-dir>/curated (:seon.config.root/runs-dir; curated-turn). Every result it shows is evaluated
  live, never copied. See docs/model.md#running-a-curated-session."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [task turn]}]
  (curated-turn (str (config/value :seon.config.root/runs-dir) "/curated") task turn))

(defn calc-cancel
  "The scripted model of the calc run that cancels neg: `reply`, except neg's first turn derefs a
  promise nothing delivers, so the turn stays in flight until the run cancels it (seon.e2e/calc)."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [task turn] :as request}]
  (if (and (= "calc/neg" task) (zero? turn))
    (calls "Waiting on a promise nothing delivers." "@(promise)")
    (reply request)))

;; ---- what the model reads, an entity at a time (docs/render.md#the-models-context-a-cached-prefix-then-the-turn) ----

(defn eval-messages
  "Eval record e as the model reads it, through its schema's model render (seon.ctx.wire/eval-msgs) on opts :db (else the test store's seed): its call, naming its reply when its call ref names a model call, and its result."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:vector :map]]}
  [opts e] (@(requiring-resolve (quote seon.ctx.wire/eval-msgs)) {:seon.db/db (or (:db opts) (@(requiring-resolve (quote seon.test-store/test-db)))) :seon.render/index (seon.db/index)} e))

(defn message-messages
  "Message m as the model reads it (seon.ctx.wire/message-msgs)."
  {:malli/schema [:=> [:cat :any :map] [:vector :map]]}
  [_ m] (@(requiring-resolve (quote seon.ctx.wire/message-msgs)) {} m))

(defn turn-messages
  "Model call c's reply as the model reads it (seon.ctx.wire/model-call-msgs)."
  {:malli/schema [:=> [:cat :any :map] [:vector :map]]}
  [db c] (@(requiring-resolve (quote seon.ctx.wire/model-call-msgs)) {:seon.db/db db} (assoc c :seon.model-call/origin :model)))

(defn no-error-render
  "Asserts that defects ds (a walk's `:seon.render/component-defect`s, read with @(:defects w): `seon.render/take-defects!` is the
  reader that drains them) hold no component's error drawn in a page: each that `:expects` does not name is a failed assertion naming
  the render and its message. `:expects` is a set of strings, each matched exactly against a defect's `:seon.error/message` or the
  name of the render that threw (`:seon.error/in`, as a string): a test that renders an error on purpose names it, and nothing else
  passes. Answers the defects not expected (empty when the assertion held). A page built in an agent's own process (a forked one, as
  seon.agent-test's in-agent tests build) has no walk here: those tests check the agent's page themselves. See
  docs/render.md#a-component-answers-hiccup and docs/testing.md#a-proof-is-focused."
  {:malli/schema [:function [:=> [:cat [:sequential :map]] [:vector :map]]
                  [:=> [:cat [:sequential :map] [:map [:expects {:optional true} [:set :string]]]] [:vector :map]]]}
  ([ds] (no-error-render ds {}))
  ([ds {:keys [expects]}]
   (let [ok (set expects)
         unexpected (into [] (remove (fn [d] (or (ok (:seon.error/message d)) (ok (str (:seon.error/in d)))))) ds)]
     (is (empty? unexpected) (str "a page drew an error in a component's place: " (pr-str (mapv #(select-keys % [:seon.error/in :seon.error/message]) unexpected))))
     unexpected)))

(defn flush!
  "`seon.render/flush!` of walk w (what it ran), and `no-error-render` of w: a test that flushes a walk through this checks, each time, that no
  component drew an error. A test that renders an error on purpose calls `seon.render/flush!` and checks its defects itself."
  {:malli/schema [:function [:=> [:cat :map [:or [:= :all] [:maybe [:sequential :any]]]] :map]
                  [:=> [:cat :map [:or [:= :all] [:maybe [:sequential :any]]] [:maybe :map]] :map]]}
  ([w datoms] (flush! w datoms nil))
  ([w datoms ix] (let [ran (seon.render/flush! w datoms ix)] (no-error-render @(:defects w)) ran)))

(defn draw
  "`seon.render/draw` of ctx tree c (the walk), and `no-error-render` of that walk: a test that draws a page through this checks that no
  component drew an error."
  {:malli/schema [:function [:=> [:cat :map :any :any :any] :map]
                  [:=> [:cat :map :any :any :any :map] :map]]}
  ([c changes db f] (draw c changes db f nil))
  ([c changes db f opts] (let [w (if opts (seon.render/draw c changes db f opts) (seon.render/draw c changes db f))] (no-error-render @(:defects w)) w)))

(defn tree-parts
  "The conversation of agent record c on db as the agent's walk answers it for the model, cold: parts [ref messages turn] of the request, its system message, its opening and the page calls Seon generates (seon.ctx.wire/page-diff-msgs) left out. A db that holds no system prompt gets a stub of one, which the walk needs and the parts leave out."
  {:malli/schema [:=> [:cat :map :seon.host/db] [:vector :any]]}
  [c db]
  (let [doc-load (requiring-resolve 'seon.doc/load-tx)
        page (requiring-resolve 'seon.doc/system-page)
        db (if (@(requiring-resolve 'seon.doc/page-text) db @page) db (datahike.api/db-with db (doc-load db "docs" {@page "# A stub of the system prompt\n\nBe brief."})))
        w (draw c @(requiring-resolve 'seon.ctx/changes) db #'seon.render.ui/agent
                            {:model-only true :once true :outputs #{:seon.render/hiccup :seon.render/openai-compat}})]
    (try (vec (remove (fn [[r]] (or (nil? r) (#{:section :session :page} (first r)))) (:parts (@(requiring-resolve (quote seon.ctx.wire/request)) w))))
         (finally (seon.render/dispose-walk! w)))))

(defn tree-messages
  "The messages of `tree-parts`."
  {:malli/schema [:=> [:cat :map :seon.host/db] [:vector :map]]}
  [c db] (into [] (mapcat second) (tree-parts c db)))

(defn drawn-parts
  "The parts of agent record c ({:tree}) on db as the model reads them, drawn cold: {id form}."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:tree :map]]] :map]}
  [db c]
  (let [w (draw (:tree c) @(requiring-resolve 'seon.ctx/changes) db #'seon.render.ui/agent {:model-only true})]
    (try (seon.render/placed-hiccup w (seon.render/page-lists w)) (finally (seon.render/dispose-walk! w)))))

(defn composed
  "Units ({:id :html}, a page's at any depth) each with its html composed with its children's (seon.render.proc/compose fills each
  slot): what a reader of the page's html reads of each. See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [us]
  (let [by (into {} (map (juxt :id identity)) us)
        compose @(requiring-resolve 'seon.render.proc/compose)]
    (mapv #(cond-> % (:html %) (update :html compose by)) us)))

(defn page-units
  "Walk w's units as the page shows them (seon.render/placed-units, at any depth), each composed (composed). See
  docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [w]
  (composed (seon.render/placed-units w)))
