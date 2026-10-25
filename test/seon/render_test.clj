(ns seon.render-test
  "Renders (session W2): selection (override key, schema-declared, printer), root's AI face,
  and the render proc's step as a plain function: inputs before render, bytes before
  send, a new agent appended, a retired one removed, keyframes composed from what was
  retained. Run: bin/test seon.render-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str] [clojure.core.async :as a]
            [seon.render :as r] [seon.render.ui :as ui] [seon.render.ai :as ai] [seon.render.proc :as p]
            [seon.web.html :as h] [seon.config] [seon.docs] [seon.print] [seon.db] [seon.reconcile] [seon.task] [seon.edn] [seon.context] [seon.contract] [seon.repl] [datahike.api :as d]
            [seon.fx] [seon.source] [seon.program] [seon.web] [seon.context-test] [seon.render-cost-test] [seon.turn] [seon.model] [seon.problems]))

(def ^:private task
  {:seon.task/id "calc/add" :seon.task/ns 'calc :seon.task/name "add" :seon.task/plan "Make `app.calc/add` pass"
   :seon.task/execution :running :seon.task/branch :task-calc-add :seon.task/proofs #{'app.proofs/add-test}
   :seon.task/needs #{} :seon.task/kind :work
   :seon.task/accumulated {:plan "Make `app.calc/add` pass" :proofs #{'app.proofs/add-test}}})

(deftest selection
  (testing "the value's own override key wins: data as is"
    (is (= [:b "mine"] (r/html {:a 1 :seon.render/html [:b "mine"]} {:schema :seon.task/task}))))
  (testing "the schema's declared render, named by the producer"
    (is (= 'seon.render.ui/task-box (r/declared :seon.task/task :html)))
    (is (str/includes? (h/html (r/html task {:schema :seon.task/task})) "task · calc/add")))
  (testing "a pulled entity's schema is the one its identity attribute names"
    (is (= :seon.run/run (r/schema-of {:seon.run/id "x#1"} nil)))
    (is (str/includes? (h/html (r/html {:seon.run/id "calc/add#1" :seon.run/agent :a3 :seon.run/status :running} {}))
                       "<td><code>calc/add#1</code></td>")))
  (testing "no declaration: the one value printer's hiccup"
    (let [out (r/html {:k [1 2 3]} {})]
      (is (= "seon-data-panel" (get-in out [1 :class])))))
  (testing "the AI face of an undeclared value is the printer's text"
    (is (= "{:k 1}" (r/ai {:k 1} {})))
    (is (vector? (r/html {:k 1} {})))))

(def ^:private sys
  {:agents {:a1 {:status :idle :tests {:pass 1 :fail 1 :error 0 :reports [{:type :fail :var 'agent.a1/c}]}}
            :a2 {:status :dead}
            :a3 {:status :killed :tests {:pass 0 :fail 3 :error 0}}}
   :health {:api {:defects 0} :render {:defects 1 :newest "boom"}}
   :writer {:tx-count 4 :tx-per-s 2.0} :fork-server {:status :up :pid 7} :main {:t 9 :datoms 100}
   :tasks {} :runs {} :events [] :root-errors [{:pid :render :message "boom"}] :now-ms 0
   ;; what main recorded (seon.problems/report): root records an agent's death and a red run
   :recorded {:classes {:agent {:n 3 :rows [{:signature "s1" :count 2 :message "tests red: agent.a1/c"}
                                            {:signature "s2" :count 1 :message "agent a2 died: signal 9"}]}
                        :red {:n 0 :rows []}}}})

(deftest root-context
  (let [data (ai/system-data sys)
        text (r/ai sys {:schema :seon.render/system :budget 700})
        entries (for [[_ es] (:problems data) e es] e)]
    (testing "problems first: root's defect, then what main recorded (one producer); no proc is pinged"
      (is (= [:seon-root :problems] (take 2 (keys data))))
      (is (not (re-find #"\d\d:\d\d:\d\d" text)) "no clock: an unchanged system gives the same text")
      (is (not (str/includes? text "UNKNOWN")) "no ping, so no unanswered ping")
      (is (= {:defect-in :render :message "boom"} (first (get-in data [:problems :root]))))
      (is (str/includes? text "boom"))
      (let [agent (some #(when (= :agent (get-in % [:problem :class])) %) entries)]
        (is (= "tests red: agent.a1/c" (get-in agent [:problem :why])) "the class's newest or commonest open row, by its render")
        (is (= 3 (get-in agent [:problem :n])))
        (testing "a class's other open rows are counted, with where they all are"
          (is (= 2 (:open agent)))
          (is (= '(get-in (my.root/problems sys) [:classes :agent :rows]) (:all-in agent)))))
      (is (str/includes? text "tests red: agent.a1/c")))
    (testing "the live view is not a second producer: an agent's own entry makes no problem entry"
      (is (not-any? #(= :a1 (:defect-in %)) entries)))
    (testing "over the budget: the printer's cut, which says what it left; nothing cut before it"
      (let [small (ai/system sys {:budget 40})]
        (is (:truncated? small))
        (is (<= (seon.print.select/estimate (:text small)) 40))))))

(deftest compose-fills-slots-recursively
  (is (= "<a><b>x</b></a>" (p/compose "<a><!--s:b--></a>" {"b" {:html "<b><!--s:c--></b>"} "c" {:html "x"}}))))

(deftest observed-task-states
  (let [decl (fn [nm proofs & needs] (cond-> {:seon.task/ns 't :seon.task/name nm :seon.task/plan (str "Make " nm ".") :seon.task/proofs proofs}
                                       needs (assoc :seon.task/needs (set needs))))
        db (ts/test-db)
        db (-> db (d/db-with (seon.task/create-tx db [(decl "a" #{'p/x}) (decl "b" #{'p/z} "t/a") (decl "c" #{'p/w})]))
               (d/db-with (seon.task/execution-tx ["t/a" "t/b"] :running))
               (d/db-with (seon.task/execution-tx ["t/c"] :cancelled))
               (d/db-with (conj (seon.task/run-start-tx "t/a#1" "t/a" "boot-1" :a1 {:branch :task-t-a :model nil :max-turns 40} 1 "Make a.")
                                {:seon.run/id "t/a#1" :seon.run/tokens 7
                                 :seon.run/results [{:seon.proof/test 'p/x :seon.proof/status :pass} {:seon.proof/test 'p/y :seon.proof/status :fail}]})))
        rows (p/task-rows (seon.reconcile/index db))]
    (testing "what happened, not the desired execution: running, blocked by its need, cancelled"
      (is (= :running (get-in rows ["t/a" :state])))
      (is (= :blocked (get-in rows ["t/b" :state])))
      (is (= #{"t/a"} (get-in rows ["t/b" :blocked-by])))
      (is (= :cancelled (get-in rows ["t/c" :state]))))
    (testing "its own proofs on its newest run (another task's red is allowed), runs so far, tokens"
      (is (= [1 0 1 1 7] ((juxt :green :red :allowed-red :runs :tokens) (get rows "t/a")))))))

;; ---- no clipping: every cut on the page and in root's context says what it left -------------

(defn- long-text [n] (str/join " " (for [i (range n)] (str "word" i))))

(deftest root-context-cuts-only-in-the-printer
  ;; the owner's rule (clipping audit 2026-10-25, P6/P7): data is cut only by the one printer,
  ;; at the end; the context's value holds every run, event and defect whole, and its read forms
  (let [runs (into {} (for [i (range 12)] [(str "t/x" i "#1") {:seon.run/id (str "t/x" i "#1") :seon.run/status :done
                                                                     :seon.run/outcome :green :seon.run/end-ms i
                                                                     :seon.run/reason (if (= i 11) (long-text 200) "ok")}]))
        s (assoc sys :tasks {"t/x0" {}} :runs runs :task-rows {}
                 :root-errors [{:pid :render :message (long-text 300)}] :root-errors-total 4
                 :events (vec (for [i (range 30)] {:type :ready :agent :a1 :at-ms i})))
        data (ai/system-data s)
        out (ai/system s {:budget 2000})]
    (testing "every ended run, its long reason whole, and the form that reads them all"
      (is (= 12 (count (get-in data [:work :ended]))))
      (is (= (long-text 200) (:reason (first (get-in data [:work :ended])))))
      (is (= '(seon.task/runs (deref (:conn sys))) (get-in data [:work :all-in]))))
    (testing "root's defects: the one root's view holds, whole, and how many older, with where"
      (is (= (long-text 300) (:message (first (get-in data [:problems :root])))))
      (is (= {:older 3 :root-holds-the-newest (seon.config/value :seon.config.root/view-window) :all-in '(:root-errors (seon.root/state sys))}
             (second (get-in data [:problems :root])))))
    (testing "every event root's view keeps"
      (is (= 30 (count (:newest-events data)))))
    (testing "the printer cuts it at the budget, and says so"
      (is (:truncated? out))
      (is (<= (seon.print.select/estimate (:text out)) 2000)))))

(deftest root-context-groups-blocked-tasks-by-what-blocks-them
  (let [rows {"t/a" {:state :blocked :blocked-by #{"t/root"}} "t/b" {:state :blocked :blocked-by #{"t/root" "t/other"}}
              "t/c" {:state :blocked :blocked-by #{"t/root"}} "t/d" {:state :done :blocked-by #{}}}
        data (ai/system-data (assoc sys :tasks {"t/a" {}} :task-rows rows))]
    (is (= [["t/root" 3] ["t/other" 1]] (mapv vec (get-in data [:work :waiting-on]))) "each blocker once, the one holding most first")
    (is (not (str/includes? (pr-str data) "t/a")) "not one entry per blocked task")))

;; ---- the GUARDS audit's names for the same law (seon.render/no-clip-in-renders) -------------

(deftest no-render-calls-clip
  (is (nil? (resolve 'seon.render.ui/clip)))
  (is (nil? (resolve 'seon.render.ui/line)))
  (testing "root's context sizes tokens by the printer's own measure (seon.print.select/estimate), no alias of it"
    (is (nil? (resolve 'seon.render.ai/tokens)))))

;; ---- no beat: health is what flows (owner, 2026-10-21) --------------------------------------

(deftest render-proc-has-no-beat
  (let [d (p/render-proc)]
    (is (not (contains? (:ins d) :tick)) "no beat in-port")
    ;; :main, main's commits from the writer: a pass follows a commit, never a clock
    (is (= #{:answers :from-web :main} (set (keys (:ins d)))))
    (is (nil? (resolve 'seon.render.proc/SAMPLE-MS)))
    (is (nil? (resolve 'seon.render.proc/start-sampler!))))
  (testing "a proc's health from root's view: its declared procs, its defects by pid"
    (let [sysp (doto (promise) (deliver {:procs {:api {} :render {} :fx/model {}}}))
          h (#'p/proc-health-of {:system-p sysp} [{:pid :render :message "a"} {:pid :render :message "b"}])]
      (is (= {:defects 2 :newest "b"} (get h :render)))
      (is (= {:defects 0} (get h :api)))
      (is (= 3 (count h))))))

(deftest the-load-tiles-are-data-the-context-reads
  (let [tiles (ui/load-tiles {:tx-count 4} {:status :down :pid 7} {:datoms 100})
        text (r/ai sys {:schema :seon.render/system :budget 2000})]
    (is (= ["t-writer" "t-fork-server" "t-main"] (mapv :id tiles)))
    (is (= "down" (second (:pill (second tiles)))))
    (testing "root's context holds them, one line each"
      (let [tiles (get-in (ai/system-data sys) [:load :tiles])]
        (is (some #(str/starts-with? % "writer 4 txs, batch") tiles))
        (is (some #(str/starts-with? % "fork server up – ms fork p50, forked –") tiles))))))

(def ^:private behind
  ;; as seon.writer/merge-reason makes it
  (seon.contract/error :seon.merge/behind 'seon.writer/merge-refused
                       "main changed 3 key(s) since the branch's merge base: merge main into the branch first"
                       {:tx 812 :behind 3
                        :seon.error/fix "A merge task merges main into the branch (my.branch/merge-main!), resolves any conflict, and its proofs run on the combined program."}))

(deftest an-error-reads-its-message-fix-and-variant
  (testing "an error map's schema is its variant: the base's render, whatever the variant"
    (is (= :seon.merge/behind (r/schema-of behind nil)))
    (is (= :seon.error/base (r/schema-of {:seon.error/type :no.such/variant :seon.error/operation 'a/b :seon.error/message "m"} nil))))
  (let [text (r/ai behind {:handle 'result/h4})
        data (ai/error-data behind)]
    (testing "the message first, as a REPL answers; the fix; then its variant, where, and its own keys"
      (is (= [:message :fix :type :operation :also] (keys data)))
      (is (= {:tx 812 :behind 3} (:also data)))
      (is (str/includes? text "main changed 3 key(s)"))
      (is (str/includes? text "A merge task merges main into the branch (my.branch/merge-main!)"))
      (is (str/includes? text ":seon.merge/behind"))
      (is (str/includes? text ":behind 3"))
      (is (str/includes? text ":as-data (with-meta result/h4 nil)"))))
  (testing "the page shows the same facts"
    (let [html (h/html (r/html behind {}))]
      (is (str/includes? html "main changed 3 key(s)"))
      (is (str/includes? html "fix: A merge task merges main"))
      (is (str/includes? html "seon.merge/behind in seon.writer/merge-refused")))))

(def ^:private failed-task
  (assoc task :seon.task/execution :held :seon.task/needs #{"calc/sub"}
         :seon.task/accumulated {:plan (str/join "\n" (cons "Make `app.calc/add` pass." (for [i (range 400)] (str "Step " i ": read `app.calc/f" i "` and its proof."))))
                                 :proofs (set (for [i (range 12)] (symbol "app.proofs" (str "add-" i "-test"))))}
         :seon.task/failure {:reason "add cannot be total over longs" :counterexample "(add Long/MAX_VALUE 1)" :run "calc/add#2"}
         :seon.task/evidence {:from "calc/add#2" :conflicts [] :unmerged [] :red ['app.proofs/add-0-test] :problem "red"}))

(deftest a-task-reads-its-state-plan-proofs-and-failure
  (testing "a key present in a map names no render at the REPL: it prints as the data it is"
    (is (str/starts-with? (:text (r/printed failed-task 'result/h2)) "#:seon.task{")))
  (testing "a task its producer names (seon.task/task's metadata) reads at the REPL by its declared render"
    (let [text (:text (r/printed (with-meta failed-task {:seon.render/schema :seon.task/task}) 'result/h2))
          data (ai/task-data failed-task)]
      (is (= [:task :kind :execution :needs :branch :failure :plan :proofs :evidence] (take 9 (keys data))) "its id, state and needs, what is wrong, the plan, then the rest")
      (is (= ["calc/sub"] (:needs data)))
      (is (= "add cannot be total over longs" (get-in data [:failure :reason])))
      (is (= 12 (count (:proofs data))) "every proof")
      (is (str/includes? text "add cannot be total over longs"))
      (is (str/includes? text ":from \"calc/add#2\""))
      (testing "a long plan is the printer's cut, reading its rest through the form that makes the value again"
        (let [cut (r/ai failed-task {:schema :seon.task/task :handle 'result/h2 :budget 300})]
          (is (re-find #"\(seon.render.ai/task-data result/h2\)" cut) cut)))))
  (testing "the page's task box shows the failure too"
    (let [html (h/html (r/html failed-task {:schema :seon.task/task}))]
      (is (str/includes? html "failed in calc/add#2: add cannot be total over longs")))))

(deftest a-run-reads-as-its-data
  (is (= "{:run \"calc/add#1\", :status :running, :agent :a3, :turn 2, :tokens 1840}"
         (r/ai {:seon.run/id "calc/add#1" :seon.run/status :running :seon.run/agent :a3 :seon.run/turns 2 :seon.run/tokens 1840} {})))
  (let [a {:seon.run/id "calc/add#2" :seon.run/status :failed :seon.run/outcome :merge-failed
           :seon.run/reason (str "merge refused: " (:seon.error/message behind))
           :seon.run/conflicts [{:key "var app.calc/add" :kind :var :base "a" :ours "b" :theirs "c"}]
           :seon.run/unmerged ["var app.calc/add"]}
        data (ai/run-data a)]
    (is (= [:run :status :outcome :reason :conflicts :unmerged] (keys data)))
    (is (= ["var app.calc/add"] (:unmerged data)) "what its end carried, whole")
    (is (str/includes? (r/ai a {:handle '(get atts "calc/add#2")}) "merge refused: main changed 3 key(s)"))))

(defn- row
  "A problem row as seon.problems/report makes it: every row's members, then its class's."
  [m] (merge {:producer nil :owner-ns 'calc :evidence {} :proofs [] :answered-by [] :at-ms 1761170000000} m))

(deftest every-problem-class-reads-what-it-is-about-and-why
  ;; rows as seon.problems/report makes them, one per class that had no line of its own
  (let [data #(ai/problem-data (row (assoc %2 :class %1)))]
    (is (= {:class :task-conflict :about ["calc/add" "calc/add#3" "plan"] :why {:base "Make add." :ours "Make add total." :theirs "Make add pass."}}
           (data :task-conflict {:task "calc/add" :run "calc/add#3" :attr :plan :open? true
                                 :evidence {:task "calc/add" :attr :plan :base "Make add." :ours "Make add total." :theirs "Make add pass." :from "calc/add#3"}})))
    (is (= {:class :failed :about ["calc/add" "calc/add#2"] :why "run calc/add#2 ended cap: proof-red app.proofs/add-test"}
           (data :failed {:task "calc/add" :run "calc/add#2" :reason "run calc/add#2 ended cap: proof-red app.proofs/add-test"}))
        "a refused run's hold: a reason and no counterexample")
    (is (= {:class :merge :about ["calc/add" "calc/add#2"] :why (:seon.error/message behind) :conflicts 1 :unmerged ["var app.calc/add"]}
           (data :merge {:task "calc/add" :run "calc/add#2" :error behind :conflicts 1 :unmerged ["var app.calc/add"]})))
    (is (= {:class :red :about ["calc/add" "calc/add#1" "app.proofs/add-test"] :why :tests-red}
           (data :red {:task "calc/add" :run "calc/add#1" :test 'app.proofs/add-test :outcome :tests-red})))
    (is (= {:class :platform :count 4 :why "Datahike transact refused" :answered-by ["calc/fix-store"]}
           (data :platform {:signature "s" :message "Datahike transact refused" :count 4 :answered-by ["calc/fix-store"]}))))
  (testing "the whole report at root's REPL: by class, each row by its render, closed rows marked"
    (let [rep (with-meta {:t 90 :classes {:failed {:n 1 :rows [(row {:task "calc/add" :reason "not total" :open? true})]}
                                          :merge {:n 1 :rows [(row {:task "calc/sub" :run "calc/sub#1" :error behind :open? false})]}
                                          :red {:n 0 :rows []}}}
                {:seon.render/schema :seon.problems/report})
          data (ai/report-data rep)
          text (:text (r/printed rep 'result/h1))]
      (is (= 90 (:problems-on-main-at-t data)))
      (is (= [:failed :merge] (keys (:classes data))) "a class with rows; an empty one left out")
      (is (= true (get-in data [:classes :merge :rows 0 :closed])))
      (is (not (contains? (get-in data [:classes :merge :rows 0]) :class)) "a row under its class does not say it again")
      (is (str/includes? text "not total"))
      (is (str/includes? text ":as-data (with-meta result/h1 nil)")))))

;;; --- the law, from the compiler's facts: one printer, and no cut before it -------------

(def ^:private printer-vars
  "The vars that turn a value into text beside the one printer."
  '#{clojure.core/pr-str clojure.core/prn-str clojure.core/print-str clojure.core/with-out-str
     clojure.core/pr clojure.core/prn clojure.core/printf clojure.pprint/pprint clojure.pprint/pp
     clojure.pprint/write clojure.pprint/cl-format})

(def ^:private budgeted-vars
  "The printer's calls that cut to a budget: a cut where a value is made, outside a render."
  '#{seon.print/text seon.print/line seon.print/prose})

(def ^:private cut-vars
  "The vars that shorten what is shown, where output is made for a person or a model."
  '#{clojure.core/subs clojure.core/take clojure.core/take-last clojure.core/drop-last})

(defn- src-program
  "Main's db with every file of src imported (each form analyzed, never run: the running
  generation's namespaces), as seon.source-test imports it."
  []
  (let [files (:value (seon.fx/read-source {:req {:root (System/getProperty "user.dir") :dirs ["src"]}}))]
    (doseq [n (keep #(seon.source/file-ns (val %)) files)] (require n))
    (let [db (ts/test-db)] (d/db-with db (seon.source/import-tx db files)))))

(deftest a-render-reaches-no-process
  ;; root, 2026-10-25: after jolt/agent-conversation merged, bin/prove planned 29 keys of 686 tests:
  ;; every test reaches every schema's declared render (contract/checked, the registry, the schema
  ;; forms), and seon.render.ui/did named my.agent/prove as a quoted symbol, which the reach walk
  ;; follows (seon.program/reach-graph), so every test reached seon.lib/fork! through it, counted
  ;; as reaching a process and was left out of bin/prove. A render runs no process
  (let [idx (seon.program/reach-index (src-program) {:env "e" :tree "t"}
                                      {"agent" '[seon.render.ui/agent] "root" '[seon.render.ui/root] "page" '[seon.render.ui/page]})]
    (is (= {"agent" false "root" false "page" false} (update-vals idx :process?))
        "no render reaches a process var (:seon.config.source/process-vars)")))

(defn- sites
  "The vars of src whose forms reference one of marks (the compiler's :form/refs), in
  namespaces ns? holds."
  [forms marks ns?]
  (sort (distinct (for [f forms :when (and (ns? (:ns f)) (some marks (:refs f)))
                        ;; a var's form, or a method's (named by its multi)
                        [k sym] (:defines f) :when (#{:var :method} k)]
                    sym))))

(deftest one-printer-and-no-cut-before-it
  ;; owner, 2026-10-22: "Do not create more than one value printer and no clipping happens
  ;; before then". Every var of src that prints beside seon.print, and every var of a
  ;; display namespace that cuts, is named in docs/render.md#one-printer with its reason
  ;; (the wire, storage, code text, or a cut of data, not of output); a new one is red.
  (let [forms (seon.program/live-forms (src-program))
        doc (let [t (slurp "docs/render.md")] (subs t (str/index-of t "## One printer") (or (str/index-of t "\n## " (+ 3 (str/index-of t "## One printer"))) (count t))))
        ;; a site is named by a list item, before its reason (`- `ns/f`, `ns/g`: why`)
        named (set (for [[_ head] (re-seq #"(?m)^- (.*?): " doc) [_ n] (re-seq #"`([a-z][\w.\-]*/[^`\s]+)`" head)] (symbol n)))
        printer? #(not (str/starts-with? (str %) "seon.print"))
        display? #(re-find #"^(my\.|seon\.(render|web|context|mcp|server|cost|repl|agent|problems)($|\.))" (str %))]
    (testing "src is imported: the check reads facts, not nothing"
      (is (< 1000 (count forms))))
    (testing "every var printing beside the one printer is named with its reason"
      (is (= [] (remove named (sites forms printer-vars printer?)))))
    (testing "every var cutting in a display namespace is named with its reason"
      (is (= [] (remove named (sites forms cut-vars display?)))))
    (testing "every var cutting to a budget where a value is made, outside a display namespace, is named"
      (is (= [] (remove named (sites forms budgeted-vars #(and (printer? %) (not (display? %))))))))
    (testing "a site named that no longer prints or cuts is gone from the page too"
      (is (= [] (remove (set (concat (sites forms printer-vars printer?) (sites forms cut-vars display?)
                                     (sites forms budgeted-vars #(and (printer? %) (not (display? %))))))
                        (sort named)))))))

;; ---- the agent's debug view (task seon.render/a-debug-view-of-any-agent) ----------------

(deftest the-printer-renders-each-nested-value-by-its-own-render
  ;; owner, 2026-10-23: the default render auto-renders. A value inside the printed one whose
  ;; schema declares a render shows as that render, one line, in its place: the run's :ai
  ;; render in the text, a call of its :html render in the hiccup (expanded by the serializer);
  ;; the printed value itself is the printer's, never its own render again
  (let [a {:seon.run/id "calc/square#1" :seon.run/agent :a4 :seon.run/status :done :seon.run/outcome :green}
        v {:newest a :count 3}]
    (is (re-find #":newest\s+\{:run \"calc/square#1\"" (r/ai v {})) (str "the text: the run's own render, in its place: " (r/ai v {})))
    (let [html (h/html (r/html v {}))]
      (is (str/includes? html "<tr class=\"at s-done o-green\">") "the hiccup: the run's own render")
      (is (str/includes? html "seon-print-keyword\">:count") "around it, the printer's map"))
    (is (= "{:k [1 2 3]}" (r/ai {:k [1 2 3]} {})) "a value with no render of its own prints as data")
    (is (str/includes? (r/ai a {}) "calc/square#1") "the value itself by its render, as before")))

;; ---- the walk (task seon.render/walk): an entity and the units its schema declares ----

(def ^:private walked-plan
  "A plan citing a var (one backtick: its doc), a namespace and a wiki section (a link)."
  "Make `seon.render/walk` answer the tree; its printer is `seon.print`, and see [no clipping](docs/render.md#no-clipping).")

(defn- walked-db
  "Main as root writes it: root, a task, root's agent a3, the agent's run of the task with a proof
  result (a component), a message to the run, a turn of the run and two evaluations of the agent,
  each written by its producer's own function, so each link is the ref production makes. The
  turn's reply and the message cite what the plan cites."
  []
  (let [k (seon.task/agent-key "b1" :a3)]
    (reduce d/db-with (ts/test-db)
            [[{:seon.root/id :root :seon.root/boot "b1"}]
             [{:seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan walked-plan :seon.task/execution :running
               :seon.task/branch :task-demo-f :seon.task/kind :work :seon.task/proofs #{'demo/f-test}}]
             (seon.task/agent-tx "b1" :a3 {:status :idle :pid 77 :running {:form "(f 2)"}})
             (seon.task/run-start-tx "demo/f#1" "demo/f" "b1" :a3 {:branch :task-demo-f :model "m" :max-turns 8} 0 "p")
             [{:seon.run/id "demo/f#1" :seon.run/results [{:seon.proof/test 'demo/f-test :seon.proof/status :pass}]}]
             (seon.task/message-tx "m-1" "owner" "demo/f#1" "check g, see `seon.print`")
             [{:db/id "run" :seon.run/id "demo/f#1"}
              {:seon.turn/id "demo/f#1/0" :seon.turn/run "run" :seon.turn/n 0 :seon.turn/origin :model :seon.turn/reply "(f 2), as `seon.render/walk` says"
               :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 5}]
             (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e1" "a3" {:code "(f 2)" :response {:handle 'result/e1 :value 4}} nil)
             (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e2" "a3" {:code "(f 3)" :response {:handle 'result/e2 :value 6}} nil)])))

(defn- eid-of [db ref] (:db/id (d/entity db ref)))

(defn- attr-of [nd] (get-in nd [:seon.render/from :seon.render/attr]))

(defn- values-of
  "The k of each node of walk w reached by attribute a."
  [w a k] (into [] (comp (filter #(= a (attr-of %))) (map #(get-in % [:seon.render/value k]))) (:seon.render/nodes w)))

(deftest a-walk-is-its-entity-and-its-units
  (let [db (walked-db)]
    (testing "an agent: its own attributes, then its units in its schema's order, each a neighbour pulled whole"
      (let [eid (eid-of db [:seon.agent/key "b1/a3"])
            w (r/walk [db] eid)
            root (:seon.render/root w)]
        (is (= {:seon.render/kind :entity :seon.render/schema :seon.db/agent-fact :seon.render/eid eid}
               (select-keys root [:seon.render/kind :seon.render/schema :seon.render/eid :seon.render/from])) "the root came from nowhere")
        (is (= {:seon.agent/id :a3 :seon.agent/status :idle :seon.agent/pid 77 :seon.agent/running "(f 2)"}
               (select-keys (:seon.render/value root) [:seon.agent/id :seon.agent/status :seon.agent/pid :seon.agent/running])))
        (is (not (contains? (:seon.render/value root) :seon.agent/root)) "a unit is its own node, not again in the entity")
        (is (= [:seon.eval/_agent :seon.eval/_agent :seon.run/_agent] (mapv attr-of (filter #(= eid (get-in % [:seon.render/from :seon.render/eid])) (:seon.render/nodes w))))
            "its evaluations and its run, newest first; root, of no use to a working agent, is not its unit")
        (is (= #{:seon.turn/_run :seon.message/_to :seon.run/task} (set (map attr-of (remove #(= eid (get-in % [:seon.render/from :seon.render/eid])) (:seon.render/nodes w)))))
            "its run is part of its page (:seon.render/parts): the run's units one hop more, each saying it came from the run")
        (is (= ["demo/f#1"] (values-of w :seon.run/_agent :seon.run/id)))
        (is (= ["e2" "e1"] (values-of w :seon.eval/_agent :seon.eval/id)))
        (testing "each node is an entity with its own schema; an evaluation, with no identity, the one naming the ref that reached it"
          (is (= [:seon.db/eval-fact :seon.db/eval-fact :seon.run/run-fact] (mapv :seon.render/schema (filter #(= eid (get-in % [:seon.render/from :seon.render/eid])) (:seon.render/nodes w)))))
          (is (every? #(= :entity (:seon.render/kind %)) (:seon.render/nodes w))))
        (testing "a neighbour holds only the attributes its schema declares relevant (:seon.render/keys)"
          (let [run (first (filter #(= :seon.run/run-fact (:seon.render/schema %)) (:seon.render/nodes w)))]
            (is (= "demo/f#1" (get-in run [:seon.render/value :seon.run/id])))
            (is (not (contains? (:seon.render/value run) :seon.run/plan)) "its plan is its task's")
            (is (not (contains? (:seon.render/value run) :seon.run/boot)) "its boot is root's")))))
    (testing "a run: its task, its turns, the messages to it"
      (let [w (r/walk [db] (eid-of db [:seon.run/id "demo/f#1"]))]
        (is (= :seon.run/run-fact (get-in w [:seon.render/root :seon.render/schema])))
        (is (= #{:seon.run/task :seon.turn/_run :seon.message/_to} (set (map attr-of (:seon.render/nodes w)))))
        (is (apply >= (map :seon.render/tx (:seon.render/nodes w))) "newest first")
        (is (= ["demo/f"] (values-of w :seon.run/task :seon.task/id)))
        (is (= ["demo/f#1/0"] (values-of w :seon.turn/_run :seon.turn/id)))
        (is (= ["m-1"] (values-of w :seon.message/_to :seon.message/id)))))
    (testing "root: its agents"
      (is (= [:a3] (values-of (r/walk [db] (eid-of db [:seon.root/id :root])) :seon.agent/_root :seon.agent/id))))
    (testing "a task: its proofs a unit of their own, its attribute's schema; its plan its own value, which its view shows"
      (let [eid (eid-of db [:seon.task/id "demo/f"])
            w (r/walk [db] eid)]
        (is (= [[:value :seon.task/proofs ['demo/f-test] eid]]
               (mapv (juxt :seon.render/kind :seon.render/schema :seon.render/value :seon.render/eid) (:seon.render/nodes w))))
        (is (= walked-plan (get-in w [:seon.render/root :seon.render/value :seon.task/plan])))))
    (testing "an eid with nothing on db is an error to the asker, not an empty entity"
      (is (= :seon.render/no-entity (:seon.error/type (r/walk [db] 99999999)))))
    (testing "every identity and ref attribute is named by one entity schema (the walk's schema of an entity)"
      (let [attrs #{:seon.agent/key :seon.run/id :seon.task/id :seon.root/id :seon.turn/id :seon.message/id
                    :seon.run/agent :seon.turn/run :seon.message/to :seon.agent/root :seon.eval/agent :seon.run/task}
            named (frequencies (for [[k form] seon.contract/forms :when (:seon.db/attributes (seon.contract/properties k))
                                     [a] (filter vector? (rest form)) :when (attrs a)]
                                 a))]
        (is (= attrs (set (keys named))) "each is named by an entity schema")
        (is (every? #{1} (vals named)) (pr-str named))))))

(defn boom
  "An :ai render that throws: a page part that draws its :ai face fails on it."
  [_ _] (throw (ex-info "a page part drew its :ai face" {})))

(deftest a-page-part-renders-only-its-view
  ;; a page part is drawn by its :html render alone: its :ai face is the model's, never made for
  ;; the page (notes/audit-2026-10-24.md correction 1; seon.render/shown drew both every pass)
  (let [nd {:seon.render/kind :value :seon.render/eid 7 :seon.render/schema :seon.task/task
            :seon.render/value (assoc task :seon.render/ai `boom)}
        db (walked-db)
        t (eid-of db [:seon.task/id "demo/f"])]
    (is (str/includes? (h/html (ui/tile nd {:unit "t-7"})) "task · calc/add") "a value's tile, by its view")
    (testing "an entity's component, read from its db, by its schema's view at each size"
      (is (str/includes? (h/html (ui/component db t :block)) "demo/f") "the main pane")
      (is (str/includes? (h/html (ui/component db t :tile)) "demo/f") "a tile")
      (is (str/includes? (h/html (ui/component db t :row)) "demo/f") "a row"))))

;; ---- the page (task seon.render/page): every page is the walk of one entity ----

(defn- paged-dbs
  "Main and a3's branch as production writes them: main holds root, the task, the agent (forked
  idle, busy since), its run and a message to the run; the branch, forked from main before the
  agent turned busy, adds what only it records: a model turn whose two calls each ran one eval
  (named by each call's :eval), then an eval the owner asked for from the page, an error.
  Answers [main branch agent-eid]."
  []
  (let [k (seon.task/agent-key "b1" :a3)
        main0 (reduce d/db-with (ts/test-db)
                      [[{:seon.root/id :root :seon.root/boot "b1"}]
                       [{:seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan walked-plan :seon.task/execution :running
                         :seon.task/branch :task-demo-f :seon.task/kind :work :seon.task/proofs #{'demo/f-test}}]
                       (seon.task/agent-tx "b1" :a3 {:status :idle :pid 77 :branch :task-demo-f})
                       (seon.task/run-start-tx "demo/f#1" "demo/f" "b1" :a3 {:branch :task-demo-f :model "m" :max-turns 8} 0 "p")
                       (seon.task/message-tx "m-1" "owner" "demo/f#1" "check g")
                       ;; root's own evaluation, from its REPL
                       (seon.repl/record-tx {:seon.root/id :root} "root-1" "outside" {:code "(my.root/problems sys)" :response {:handle 'result/e1 :value {}}} nil)])
        branch (reduce d/db-with main0
                       [(seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e1" "a3"
                                             ;; its prose's citations as the record holds them (seon.repl/record-details):
                                             ;; what the screen shows is read from here, never parsed from the prose again
                                             {:code "(f 2)" :prose "f doubles?" :run "demo/f#1"
                                              :cites (seon.ref/references main0 "`seon.docs/source-of`")
                                              :response {:handle 'result/e1 :value 4 :ms 1.5 :bytes 2048 :ticks 120}} nil)
                        (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e2" "a3"
                                             {:code "(f 3)" :response {:handle 'result/e2 :value 6 :ms 0.4 :bytes 512 :ticks 40}} nil)
                        [{:db/id "run" :seon.run/id "demo/f#1"}
                         {:seon.turn/id "demo/f#1/0" :seon.turn/run "run" :seon.turn/n 0 :seon.turn/origin :model :seon.turn/reply "it doubles"
                          :seon.turn/forms 2 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 5 :seon.turn/tokens 1200
                          :seon.turn/basis {:commit #uuid "00000000-0000-0000-0000-000000000000" :task "demo/f" :run "demo/f#1" :turn 0 :max-turns 8 :origin :model :observed {}}
                          :seon.turn/calls [{:id "c0" :code "(f 2)" :eval "e1"} {:id "c1" :code "(f 3)" :eval "e2"}]}]
                        (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e3" "user"
                                             {:code "(g)" :text "{:handle result/e3, :ex {:seon.error/message \"boom\"}}"
                                              :response {:handle 'result/e3 :ex {:seon.error/type :seon.agent/eval-error :seon.error/operation 'seon.repl/eval-code :seon.error/message "boom"}}} nil)])
        main (d/db-with main0 (seon.task/agent-tx "b1" :a3 {:status :busy}))]
    [main branch (eid-of main [:seon.agent/key k])]))

(defn- schema-of "Entity e's schema on db." [db e] (r/entity-schema (d/pull db '[*] e) :seon.eval/agent))

(defn- rows-of
  "Each row of layout l (a component on dbs): its entity's schema, and a turn's evals' after it."
  [dbs l]
  (for [u (:seon.render/units l) :when (= "#p-rows" (:container u)) :let [[i e] (:comp u) db (nth dbs i)]]
    (into [(schema-of db e)] (for [c (:seon.turn/calls (d/pull db [:seon.turn/calls] e)) :when (:eval c)] :seon.db/eval-fact))))

(defn- tiles-of "Each tile of layout l: an entity component's schema, or a value's." [dbs l]
  (for [u (:seon.render/units l) :when (= "#p-tiles" (:container u))]
    (if-let [[i e] (:comp u)] (schema-of (nth dbs i) e) (get-in u [:input :seon.render/schema]))))

(defn- unit-html "Unit u of a layout on dbs, rendered as the render proc renders it." [dbs u]
  (if-let [[i e as] (:comp u)]
    (h/html (ui/component (nth dbs i) e as))
    (h/html (r/html (:input u) {:schema (:schema u) :unit (:id u)}))))

(deftest a-page-is-the-walk-of-its-entity
  (let [[main branch eid] (paged-dbs)
        dbs [main branch]
        w (r/walk dbs eid)
        l (ui/page w {:shown 20 :view "a3"})
        units (:seon.render/units l)]
    (testing "the entity as main holds it: what root observed wins over the branch's older copy"
      (is (= :busy (get-in w [:seon.render/root :seon.render/value :seon.agent/status])))
      (is (= ["p-head" [0 eid :block]] ((juxt :id :comp) (first units))) "a component on main")
      (is (every? #(str/includes? (unit-html dbs (first units)) %) ["a3" "busy" "on branch task-demo-f"]) (str "its head, in words: its id, status and branch: " (unit-html dbs (first units)))))
    (testing "its units from every db, each once: main's run, the branch's evaluations"
      (is (= 3 (count (filter #(= :seon.db/eval-fact (:seon.render/schema %)) (:seon.render/nodes w)))))
      (is (= (count (distinct (map :seon.render/eid (:seon.render/nodes w)))) (count (:seon.render/nodes w))))
      (is (every? #(= 1 (:seon.render/db %)) (filter #(= :seon.db/eval-fact (:seon.render/schema %)) (:seon.render/nodes w))) "evaluations from the branch"))
    (testing "one list, newest first: the owner's evaluation, then the turn with its two evaluations in the order its call ran them, then the message"
      (is (= [[:seon.db/eval-fact] [:seon.turn/turn-fact :seon.db/eval-fact :seon.db/eval-fact] [:seon.message/fact]] (rows-of dbs l)))
      (let [turn (unit-html dbs (second (filter #(= "#p-rows" (:container %)) units)))]
        (is (< (str/index-of turn "turn 1 of 8") (str/index-of turn "(f 2)") (str/index-of turn "(f 3)")))
        (is (str/includes? turn "f doubles?") "the prose beside the call")
        (is (str/includes? turn "1.5 ms · <span>2.0 KB</span>") "its cost as recorded, each number with its unit")
        (is (str/includes? (h/html (#'ui/cost-line {:ticks 1200 :ms 1.5})) "1,200 ticks · 1.5 ms") "ticks too, when recorded; nothing not recorded")
        (is (str/includes? turn "1,200 tokens"))))
    (testing "each evaluation is in one row only: in its turn's, or alone"
      (is (= 3 (count (filter #{:seon.db/eval-fact} (apply concat (rows-of dbs l)))))))
    (testing "its other units are tiles, each a link that moves the page to its entity"
      (is (= #{:seon.run/run-fact :seon.task/task-fact} (set (tiles-of dbs l))))
      (let [run (first (filter #(= "#p-tiles" (:container %)) units))]
        (is (str/includes? (unit-html dbs run) (str "href=\"/walk/" (second (:comp run)) "\"")))))
    (testing "an entity no db holds is an error to the asker"
      (is (= :seon.render/no-entity (:seon.error/type (r/walk dbs 99999999)))))))

(defn- proc-state
  "The render proc's state as it holds a read of main and of a3's branch: root's view (a3 busy;
  root's boot, b1, is its entity's on main), main's open problems."
  [main branch]
  {:rid 1 :pending {} :watch #{:root :a3} :waiting {} :kf-wanted #{} :new-events [] :events [] :views {} :branches {:a3 {:db branch :branch :task-demo-f}}
   ;; the one read of a3's branch every view of it shows (seon.render.proc/ask-branches)
   :branch-reads {:task-demo-f {:db branch}}
   :merges 0 :main-db main :recorded (seon.problems/health main) :view {:agents {:a3 {:status :busy :branch :task-demo-f}}}})

(deftest root-and-an-agent-are-one-page-function
  (let [[main branch eid] (paged-dbs)
        st (proc-state main branch)
        root (#'p/page-value st :root)
        agent (#'p/page-value st :a3)
        tiles (fn [l] (tiles-of [main branch] l))]
    (testing "root is an agent: its page and an agent's are each its conversation (seon.render.ui/chat), with its head, its list, its tiles"
      (is (= #{nil "#c-side" "#c-older" "#c-rows" "#p-tiles"} (set (map :container (:seon.render/units root))) (set (map :container (:seon.render/units agent)))))
      (is (= [:seon.db/eval-fact] (for [u (:seon.render/units root) :when (= "#c-rows" (:container u))] (schema-of main (second (:comp u)))))
          "root's list: its own evaluations")
      (is (= "p-head" (:id (first (:seon.render/units root))) (:id (first (:seon.render/units agent))))))
    (testing "root's focus: its agents the sidebar, main's open problems and runs tiles by their own schemas, its input spawns"
      (is (= [:seon.db/agent-fact] (for [u (:seon.render/units root) :when (= "#c-side" (:container u))] (schema-of main (second (:comp u))))))
      (is (= [:seon.render/problems :seon.render/runs] (tiles root)))
      (is (= :seon.render/root-input (:schema (first (filter #(= "p-input" (:id %)) (:seon.render/units root)))))))
    (testing "an agent's focus: its REPL and messages at the top, live while root's view holds it in this boot"
      (let [in (first (filter #(= "p-input" (:id %)) (:seon.render/units agent)))]
        (is (= [:seon.render/agent-input {:id :a3 :live? true :held nil}] [(:schema in) (:input in)]))))
    (testing "a location is an entity: /agent/a3 and /walk/<its eid> are one page (each view reads the branch for itself)"
      (let [by-eid (#'p/page-value (assoc-in st [:branches eid :db] branch) eid)
            same #(for [u (:seon.render/units %) :when (not= :seon.render/page-more (:schema u))] (or (:comp u) (:input u)))]
        (is (= (same agent) (same by-eid)))))
    (testing "a location naming no entity is that error, never an empty page; a branch that could not be read is shown as its error"
      (is (= :seon.render/no-entity (:seon.error/type (#'p/page-value st 99999999))))
      (let [err (seon.contract/error :seon.writer/refused 'seon.writer/writer-proc "no branch :task-demo-f" {})
            l (#'p/page-value (-> st (update-in [:branches :a3] dissoc :db) (assoc-in [:branches :a3 :error] err)) :a3)]
        (is (some #(= err (get-in % [:input :seon.render/value])) (:seon.render/units l)))
        (is (every? #(zero? (first (:comp %))) (filter #(= "#p-rows" (:container %)) (:seon.render/units l))) "no branch read: none of its rows")))
    (testing "an agent of an earlier boot is not live (root's boot is its entity's on main): its id names no process of this one"
      (let [st2 (assoc st :main-db (d/db-with main [{:seon.root/id :root :seon.root/boot "b2"}]))]
        (is (false? (:live? (:input (first (filter #(= "p-input" (:id %)) (:seon.render/units (#'p/page-value st2 eid))))))))
        (let [e (#'p/page-value st2 :a3)]
          (is (= :seon.render/no-location (:seon.error/type e)) "and its id in this boot names no entity: the page says so, never nothing")
          (is (str/includes? (:seon.error/message e) "no agent a3 in root's boot b2"))
          (testing "the pass answers it as that page"
            (is (str/includes? (p/page-html (first (#'p/pass-view st2 :a3)) :a3) "no agent a3 in root's boot b2"))))))))

(deftest a-run-is-part-of-its-agents-page
  (let [[main branch eid] (paged-dbs)
        w (r/walk [main branch] eid)
        attr (fn [nd] (get-in nd [:seon.render/from :seon.render/attr]))]
    (testing "the agent's schema names its runs as part of its page: each run's units join it"
      (is (= [:seon.run/_agent] (:seon.render/parts (seon.contract/properties :seon.db/agent-fact))))
      (is (= #{:seon.run/task :seon.turn/_run :seon.message/_to}
             (set (filter #{:seon.run/task :seon.turn/_run :seon.message/_to} (map attr (:seon.render/nodes w)))))))
    (testing "a part's way back to the agent is not shown again"
      (is (= 1 (count (filter #(= eid (:seon.render/eid %)) (cons (:seon.render/root w) (:seon.render/nodes w)))))))
    (testing "no separate run window: a location names root, an agent of this boot, or an entity"
      (is (= [:root :root :a3 474] (mapv #'seon.web/view-of ["" "root" "a3" "474"]))))))

(deftest a-page-keeps-what-it-read
  ;; task seon.render/a-view-renders-once-per-change-of-its-reads (B): a page's walk runs inside
  ;; d/with-reads and the proc indexes its reads by key, so a transaction finds the pages it
  ;; touched by its datoms' keys (seon.render.proc/touched), never by walking them
  (let [[main branch eid] (paged-dbs)
        [st _] (#'p/pass-view (proc-state main branch) :a3)
        e1 (:db/id (d/entity branch [:seon.eval/id "e1"]))
        touched (fn [db tx-data] (#'p/touched st (:tx-data (d/with db tx-data))))
        ;; an entity of main the page did not read (ids from separate speculative d/with calls can
        ;; repeat in a test; on a store they are issued once)
        unread (first (remove #(contains? (get-in st [:views :a3 :reads]) [:e %]) (d/q '[:find [?e ...] :where [?e :ns/name]] main)))]
    (is (seq (get-in st [:views :a3 :reads])) "the page holds what it read")
    (is (= #{:a3} (touched main [{:db/id eid :seon.agent/status :idle}])) "the agent's own datom")
    (is (= #{:a3} (touched branch [{:db/id e1 :seon.eval/by "user"}])) "a row's entity, on the branch")
    (is (empty? (touched main [[:db/retractEntity unread]])) "an entity the page did not read")))

(deftest a-transaction-touching-no-page-walks-nothing
  ;; task seon.render/a-view-renders-once-per-change-of-its-reads (C): main's commits come from
  ;; the writer ({:t :ops :db}); the pass walks only the pages whose reads a commit's datoms touch
  (let [[main branch eid] (paged-dbs)
        ;; a whole pass reads root's config on main (seon.task/root), as root writes it at boot
        main (d/db-with main [{:seon.root/id :root :seon.config.agent/max-running 4}])
        o (volatile! {})
        st (#'p/pass (proc-state main branch) o)
        walked #(get-in % [:counts :walked] 0)
        commit (fn [st tx-data] (let [r (d/with (:main-db st) tx-data)]
                                  (first (#'p/render-proc st :main {:t (:max-tx (:db-after r)) :db (:db-after r)
                                                                    :ops (mapv (fn [d] [(if (:added d) :db/add :db/retract) (:e d) (:a d) (:v d)]) (:tx-data r))}))))]
    (is (= 2 (walked st)) "the first pass walks each watched page")
    (let [unread (first (remove #(some (fn [v] (contains? (get-in st [:views v :reads]) [:e %])) [:root :a3]) (d/q '[:find [?e ...] :where [?e :ns/name]] main)))
          ;; a commit's datoms as the writer reports them, on an entity of main the page did not read
          st' (first (#'p/render-proc st :main {:t (:max-tx main) :db main :ops [[:db/add unread :ns/name 'unrelated.ns]]}))]
      (is (= (walked st) (walked st')) "a commit on an entity no page read walks nothing")
      (is (empty? (:last-walked st'))))
    (let [st' (commit st [{:db/id eid :seon.agent/status :idle}])]
      (is (= #{:root :a3} (set (:last-walked st'))) "a commit on the agent walks its page, and root's, which lists it"))
    (testing "a view no tab watches leaves the index: no commit names it again"
      (let [[st' _] (#'p/render-proc st :from-web {:op :views :views #{:root} :tabs 1})]
        (is (= #{:root} (#'p/touched st' [{:e eid :a :seon.agent/status :v :idle}])))
        (is (not (some (fn [[_ vs]] (contains? vs :a3)) (:read-index st'))))))))

(deftest a-branch-commit-touches-its-page-by-its-datoms-diff
  ;; an agent's commit: its branch read again, and what moved is the datoms-diff of the held and
  ;; the new branch db; the page walks only when the diff touches its reads
  (let [[main branch _] (paged-dbs)
        main (d/db-with main [{:seon.root/id :root :seon.config.agent/max-running 4}])
        o (volatile! {})
        st (#'p/pass (proc-state main branch) o)
        k (seon.task/agent-key "b1" :a3)
        branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e4" "a3" {:code "(h)" :response {:handle 'result/e4 :value 1}} nil))
        answer (fn [st db] (#'p/answered (assoc-in st [:pending 9] [:branch :task-demo-f]) o [:branch :task-demo-f] {:db db}))]
    (is (not (some #{:a3} (:last-walked (answer st branch)))) "the same branch db: nothing moved, nothing walked")
    (is (some #{:a3} (:last-walked (answer st branch'))) "a new evaluation on it: the page walks")))

(defn- comp-state
  "proc-state with each agent view's branch read named (its branch, :task-demo-f), as ask-branches
  records it, so every view of a3 shares its branch's components."
  [main branch eid]
  (-> (proc-state main branch)
      (assoc-in [:branches :a3 :read] [:task-demo-f])
      (assoc-in [:branches eid] {:db branch :read [:task-demo-f] :branch :task-demo-f})))

(deftest an-unchanged-component-never-re-renders
  ;; task seon.render/a-page-is-its-entitys-view (4a): each entity a page shows is a component,
  ;; rendered once and again only when a transaction touches what it read
  (let [[main branch eid] (paged-dbs)
        st (comp-state main branch eid)
        [st1 _] (#'p/pass-view st :a3)
        made #(get-in % [:counts :components] 0)
        r (d/with main (seon.task/agent-tx "b1" :a3 {:status :idle}))
        ds (map (fn [d] {:e (:e d) :a (:a d) :v (:v d)}) (:tx-data r))
        touched (p/comps-touched st1 :main ds)
        [st2 patch] (#'p/pass-view (#'p/invalidate (assoc st1 :main-db (:db-after r)) touched) :a3)]
    (is (= #{[:main eid :block] [:main eid :side]} touched) "the commit on the agent touched its own components alone: its head and its sidebar line")
    (is (= 2 (- (made st2) (made st1))) "those rendered again, nothing else")
    (is (= #{"p-head" (str "s-" eid)} (set (map first (:frags patch)))) "their fragments sent")
    (is (empty? (:prepends patch)))
    (testing "a pass with nothing touched renders nothing"
      (is (= (made st2) (made (first (#'p/pass-view st2 :a3))))))))

(deftest a-new-evaluation-is-one-append
  ;; an agent's commit on its branch: what moved (the datoms-diff) touches the list, and the new
  ;; evaluation renders once, sent as one row above the others
  (let [[main branch eid] (paged-dbs)
        o (volatile! {})
        ;; one tab, on a3's page
        st (assoc (comp-state main branch eid) :watch #{:a3})
        [st1 _] (#'p/pass-view st :a3)
        k (seon.task/agent-key "b1" :a3)
        branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e4" "a3" {:code "(h)" :response {:handle 'result/e4 :value 1}} nil))
        st2 (#'p/answered st1 o [:branch :task-demo-f] {:db branch'})
        patch (first (filter #(and (= :patch (:op %)) (= :a3 (:view %))) (:to-web @o)))]
    (is (= 1 (- (get-in st2 [:counts :components]) (get-in st1 [:counts :components]))) "one component rendered: the new row")
    (is (empty? (:frags patch)) "no held row changed")
    (is (= 1 (count (filter #(= "#c-rows" (first %)) (:appends patch)))) "one part, at the conversation's end")
    (is (str/includes? (second (first (filter #(= "#c-rows" (first %)) (:appends patch)))) "(h)"))))

(deftest a-branch-read-anew-renders-its-components-again
  ;; a branch read after an error has nothing held to diff against: what was cached from that
  ;; branch is rendered again, so a change made while it could not be read is shown
  (let [[main branch eid] (paged-dbs)
        o (volatile! {})
        st (assoc (comp-state main branch eid) :watch #{:a3})
        [st1 _] (#'p/pass-view st :a3)
        err (seon.contract/error :seon.writer/refused 'seon.writer/writer-proc "no branch :task-demo-f" {})
        st2 (#'p/answered st1 o [:branch :task-demo-f] err)
        branch' (d/db-with branch [{:seon.turn/id "demo/f#1/0" :seon.turn/tokens 4321}])
        st3 (#'p/answered st2 o [:branch :task-demo-f] {:db branch'})]
    (is (not (str/includes? (p/page-html st1 :a3) "4,321 tokens")))
    (is (str/includes? (p/page-html st3 :a3) "4,321 tokens") "the turn's tokens, written while its branch was unread")))

(deftest a-component-on-two-pages-renders-once
  ;; /agent/a3 and /walk/<its eid> are two pages showing the same rows: each row renders once, for both
  (let [[main branch eid] (paged-dbs)
        st (comp-state main branch eid)
        [st1 _] (#'p/pass-view st :a3)
        [st2 _] (#'p/pass-view st1 eid)
        rows #(set (filter (fn [id] (and (str/starts-with? id "c-") (not (str/starts-with? id "c-more")))) (keys (get-in %1 [:views %2 :units]))))]
    (is (seq (rows st2 :a3)))
    (is (= (rows st2 :a3) (rows st2 eid)) "the same rows on both pages")
    (is (= (get-in st1 [:counts :components]) (get-in st2 [:counts :components])) "the second page rendered none: every one was cached")
    (is (= (get-in st2 [:views :a3 :units "p-head" :html]) (get-in st2 [:views eid :units "p-head" :html])) "the same html")))

(deftest the-agent-page-is-its-conversation
  ;; task seon.render/a-page-is-its-entitys-view (4b; design section 5): an agent's page is its
  ;; conversation, oldest first: a turn's header, an eval's prose then its one collapsed line,
  ;; a message; the input under it
  (let [[main branch eid] (paged-dbs)
        st (comp-state main branch eid)
        [st1 _] (#'p/pass-view st :a3)
        html (p/page-html st1 :a3)
        at #(str/index-of html %)]
    ;; the fixture writes the turn after its evals (seon.turn writes it as its request goes, before them)
    (is (< (at "check g") (at "f doubles?") (at "turn 1 of 8") (at "(g)")) "in the order written: the message, an eval's prose, the turn, the owner's eval")
    (is (< (at "id=\"c-rows\"") (at "id=\"p-input\"")) "the input under the conversation")
    (is (str/includes? html "<details data-on:toggle=") "an eval is a line that opens")
    (is (not (str/includes? html "<pre class=\"text-text-100\"><code>(f 2)")) "closed: its form is not rendered, only its line")
    (testing "opened, it is rendered whole"
      (let [e1 (:db/id (d/entity branch [:seon.eval/id "e1"]))
            [st2 _] (#'p/pass-view (assoc-in st1 [:open :a3] #{(str "c-" e1)}) :a3)]
        (is (str/includes? (p/page-html st2 :a3) "<pre class=\"text-text-100\"><code>(f 2)"))))))

(deftest the-sidebar-is-roots-agents
  ;; the sidebar is the agent's ref to root, rendered: root's agents, each its own component,
  ;; the same on every agent's page
  (let [[main branch eid] (paged-dbs)
        st (comp-state main branch eid)
        [st1 _] (#'p/pass-view st :a3)
        side (filter #(= "#c-side" (:container %)) (vals (get-in st1 [:views :a3 :units])))]
    (is (= [[:main eid :side]] (map #(get-in % [:input :comp]) side)) "root's one agent, a3")
    (is (str/includes? (p/page-html st1 :a3) "href=\"/agent/a3\"") "a link to its page")
    (testing "a new agent of root's is a new line in the sidebar, its component; the others stay"
      (let [r (d/with main (seon.task/agent-tx "b1" :a4 {:status :busy}))
            ds (map (fn [d] {:e (:e d) :a (:a d) :v (:v d)}) (:tx-data r))
            st2 (-> (assoc st1 :main-db (:db-after r)) (update :dirty (fnil into #{}) (p/touched st1 ds)) (#'p/invalidate (p/comps-touched st1 :main ds)))
            [st3 patch] (#'p/pass-view st2 :a3)
            a4 (:db/id (d/entity (:db-after r) [:seon.agent/key "b1/a4"]))]
        (is (= #{eid a4} (set (map #(second (get-in % [:input :comp])) (filter #(= "#c-side" (:container %)) (vals (get-in st3 [:views :a3 :units])))))))
        (is (some #(str/includes? (second %) "href=\"/agent/a4\"") (concat (:appends patch) (:afters patch) (:prepends patch))) "its line is sent")
        (is (not-any? #(= (str "s-" eid) (first %)) (:frags patch)) "a3's line is not sent again")))))

(deftest a-part-that-sorts-into-the-middle-goes-there
  ;; a new unit with held units on both sides of it in its container is placed after the one
  ;; before it, not at the container's end
  (let [u (fn [id] {:id id :input {:older 0 :view id} :schema :seon.render/page-more :container "#c-rows"})
        held {:units (into {} (for [id ["a" "c"]] [id {:input {:older 0 :view id} :html (str "<li id=\"" id "\"></li>") :container "#c-rows"}]))}
        [_ patch] (#'p/step-units held [(u "a") (u "b") (u "c")])]
    (is (= [["a" "b"]] (:afters patch)) "b after a")
    (is (empty? (:appends patch)))
    (is (empty? (:prepends patch)))))

(defn- depth "Hiccup x's nesting." [x] (if (vector? x) (inc (reduce max 0 (map depth x))) 0))

(deftest the-screen-is-the-page-without-its-chat
  ;; task seon.render/a-page-is-its-entitys-view (4c): the agent's screen is root's page of it, as
  ;; the render proc holds it, without its conversation (the model has it as its messages), its
  ;; input, or presentation; at main's t
  (let [[main branch eid] (paged-dbs)
        [st1 _] (#'p/pass-view (comp-state main branch eid) :a3)
        {:keys [t page]} (p/screen-of st1 :a3)
        text (pr-str page)]
    (is (= (:max-tx main) t))
    (is (vector? page) "hiccup, as the page is")
    (is (some #{[:h1 "a3"]} (tree-seq coll? seq page)) "the agent's head")
    (is (some #(and (vector? %) (= :li (first %)) (some #{"a3"} %)) (tree-seq coll? seq page)) "root's agents, the sidebar")
    (is (some #{[:section [:h2 "run"] [:p "demo/f#1 · running · up to 8 turns · m"]]} (tree-seq coll? seq page))
        (str "the run's tile, by the thing it shows: " (pr-str page)))
    (is (not (str/includes? text "#:seon")) "no value printed raw")
    (is (not-any? #(str/includes? text %) ["(f 2)" "◆ seon" "its page" "fact" walked-plan]) "no conversation, no chrome, no schema names, no plan (the story's)")
    (is (not (str/includes? text "release")) "no input")
    (is (not-any? map? (tree-seq coll? seq page)) "no attributes")
    (is (<= (depth page) 5) (str "shallow: " (depth page)))
    (is (some #(and (vector? %) (= :h2 (first %)) (some #{"changes"} %)) (tree-seq coll? seq (map :hiccup (vals (get-in st1 [:views :a3 :units])))))
        "the page itself shows the branch's changes")
    (is (not-any? #(and (vector? %) (= :h2 (first %)) (some #{"proofs" "changes"} %)) (tree-seq coll? seq page))
        "the proofs and the changes are the turn's own forms (my.task/status, my.branch/changes), not the screen's")))

(deftest nothing-is-shown-twice
  ;; what the conversation holds (each eval, as its own message) is never on the screen too
  (let [[main branch eid] (paged-dbs)
        [st1 _] (#'p/pass-view (comp-state main branch eid) :a3)
        text (pr-str (:page (p/screen-of st1 :a3)))
        codes (keep #(get-in % [:seon.eval/record :code]) (d/q '[:find [(pull ?e [:seon.eval/record]) ...] :where [?e :seon.eval/id]] branch))]
    (is (seq codes))
    (is (not-any? #(str/includes? text (pr-str %)) codes) "no eval's code")))

(defn- answer-main
  "The render proc after the api answers st's pending read of main for a screen (its
  [:screen-main k] request) with db: [st outputs]."
  [st db]
  (let [tag (some (fn [[rid kind]] (when (and (vector? kind) (= :screen-main (first kind))) rid)) (:pending st))]
    (#'p/render-proc st :answers {:tag tag :r {:db db}})))

(deftest an-agent-admitted-after-the-others-gets-its-screen
  ;; task seon.render/a-late-agent-gets-its-screen: the proc's own read of main can lag main's
  ;; commits (it takes them one message at a time), so an agent admitted after the others asked for
  ;; its screen while that read held no entity of it, and was answered "no agent a5 in root's boot"
  ;; every turn of its run (step 6, 2026-10-25). Its screen is answered on main as of its request.
  (let [[main branch eid] (paged-dbs)
        ;; main as root writes it at boot: its config on root's entity (a pass reads it, seon.task/root)
        main (d/db-with main [{:seon.root/id :root :seon.config.agent/max-running 4}])
        a3 (:db/id (d/entity main [:seon.agent/key (seon.task/agent-key "b1" :a3)]))
        before (d/db-with main [[:db/retractEntity a3]])
        st (assoc (comp-state before branch eid) :watch #{})
        [st1 out1] (#'p/render-proc st :from-web {:op :page :view :a3 :screen {:route [:a3 :in] :tag 9}})]
    (is (empty? (get out1 [:a3 :in])) "not answered from the proc's older read of main")
    ;; main as read for the screen: the agent admitted since (a newer main than the proc held)
    (let [[st2 out2] (answer-main st1 (d/db-with before (seon.task/agent-tx "b1" :a3 {:status :busy :pid 77 :branch :task-demo-f})))
          o (volatile! {})
          _ (#'p/pass st2 o)
          reply (first (concat (get out2 [:a3 :in]) (get @o [:a3 :in])))]
      (is (= 9 (:tag reply)))
      (is (some #{[:h1 "a3"]} (tree-seq coll? seq (:page (:r reply)))) (str "its page, not no-location: " (pr-str (:r reply)))))))

(deftest a-screen-from-a-newer-main-renders-what-changed-since
  ;; the branch review (2026-10-25, F1): main read for a screen moved the proc's main past what its
  ;; cached components were rendered from, and the screen showed their older content. Main moves one
  ;; way (advance-main): what its datoms-diff touched renders again
  (let [[main branch eid] (paged-dbs)
        main (d/db-with main [{:seon.root/id :root :seon.config.agent/max-running 4}])
        st (assoc (comp-state main branch eid) :watch #{:a3})
        [st _] (#'p/pass-view st :a3)
        _ (is (str/includes? (str (:page (p/screen-of st :a3))) "busy") "cached as main had it")
        newer (d/db-with main (seon.task/agent-tx "b1" :a3 {:status :idle :pid 77 :branch :task-demo-f}))
        [st1 _] (#'p/render-proc st :from-web {:op :page :view :a3 :screen {:route [:a3 :in] :tag 5}})
        [_ out] (answer-main st1 newer)
        page (str (:page (:r (first (get out [:a3 :in])))))]
    (is (str/includes? page "idle") (str "the screen shows main as read for it: " page))
    (is (not (str/includes? page "busy")) "not the component cached before")))

(deftest every-view-of-an-agent-shows-one-read-of-its-branch
  ;; the branch review (2026-10-25, F2): each view read the agent's branch on its own, at its own t,
  ;; and one still on an older read could refill the cache the newer one had invalidated. One read
  ;; per branch, shown by every view of the agent at once
  (let [[main branch eid] (paged-dbs)
        main (d/db-with main [{:seon.root/id :root :seon.config.agent/max-running 4}])
        st (-> (proc-state main branch) (assoc :watch #{:a3 [:debug :a3]} :branches {}) (assoc-in [:view :agents :a3 :t] 7))
        o (volatile! {})
        st (#'p/ask-branches st o)
        asks (filter #(= :branch-db (get-in % [:req :req :op])) (:to-api @o))
        newer (d/db-with branch [{:seon.eval/id "e9" :seon.eval/by "a3" :seon.eval/record {:code "(f 9)" :response {:value 18}}}])
        [st _] (#'p/render-proc st :answers {:tag (:tag (first asks)) :r {:db newer}})]
    (is (= 1 (count asks)) "one read for the two views")
    (is (identical? newer (get-in st [:branches :a3 :db])))
    (is (identical? newer (get-in st [:branches [:debug :a3] :db])) "both show it, from the one answer")))

(deftest a-screen-waits-for-the-branch-read-that-has-its-agents-commit
  ;; the branch review (2026-10-25, F3): the agent's commit and its screen request travel different
  ;; paths, and the screen could be answered from a read of the branch before that commit. The
  ;; request names the commit's t; it is answered from a branch read that covers it
  (let [[main branch eid] (paged-dbs)
        main (d/db-with main [{:seon.root/id :root :seon.config.agent/max-running 4}])
        newer (d/db-with branch [{:seon.eval/id "e9" :seon.eval/by "a3" :seon.eval/record {:code "(f 9)" :response {:value 18}}}])
        st (assoc (comp-state main branch eid) :watch #{})
        [st1 _] (#'p/render-proc st :from-web {:op :page :view :a3 :t (:max-tx newer) :screen {:route [:a3 :in] :tag 3}})
        [st2 out2] (answer-main st1 main)]
    (is (empty? (get out2 [:a3 :in])) "main read, but the branch read predates the agent's commit: not yet")
    (let [[_ out3] (#'p/render-proc (assoc-in st2 [:pending 77] [:branch :task-demo-f]) :answers {:tag 77 :r {:db newer}})]
      (is (= 3 (:tag (first (get out3 [:a3 :in])))) "answered once a read has it"))))

(deftest a-screen-is-asked-as-a-page-and-answered-on-its-route
  ;; the agent's {:op :screen} reaches the render proc as its page's request (seon.root's :screen),
  ;; is held until the page is whole on main as of the request (its own read of main), and is
  ;; answered on the asker's route
  (let [[main branch eid] (paged-dbs)
        ;; main as root writes it at boot: its config on root's entity (a pass reads it, seon.task/root)
        main (d/db-with main [{:seon.root/id :root :seon.config.agent/max-running 4}])
        st (assoc (comp-state main branch eid) :watch #{})
        [st1 out1] (#'p/render-proc st :from-web {:op :page :view :a3 :screen {:route [:a3 :in] :tag 9}})
        [st2 out2] (answer-main st1 main)
        reply (first (get out2 [:a3 :in]))]
    (is (contains? (:screens st1) [[:a3 :in] 9]) "held while main as of the request is not read")
    (is (empty? (get out1 [:a3 :in])))
    (is (= 9 (:tag reply)))
    (is (some #{[:h1 "a3"]} (tree-seq coll? seq (:page (:r reply)))))
    (is (empty? (:screens st2)) "answered once")
    (is (nil? (get-in st2 [:views :a3])) "a page only a screen needed keeps nothing")
    (is (empty? (:components st2)) "nor its components in the cache (the review's F5: no tab, no forget)")
    (testing "two agents asking with the same counter are two requests, each answered on its route"
      (let [[st1 _] (#'p/render-proc st :from-web {:op :page :view :a3 :screen {:route [:a3 :in] :tag 9}})
            [st1 _] (#'p/render-proc st1 :from-web {:op :page :view :root :screen {:route [:a9 :in] :tag 9}})
            _ (is (= 2 (count (:screens st1))))
            [st1 o1] (answer-main st1 main)
            [_ o2] (answer-main st1 main)
            replies (merge-with into o1 o2)]
        (is (= [9 9] [(:tag (first (get replies [:a3 :in]))) (:tag (first (get replies [:a9 :in])))]))))))

(deftest a-units-change-rerenders-that-unit
  (let [[main branch eid] (paged-dbs)
        st (proc-state main branch)
        pass (fn [st] (#'p/pass-view st :a3))
        patch-ids (fn [patch] {:frags (set (map first (:frags patch))) :prepends (:prepends patch) :appends (mapv first (:appends patch)) :removes (:removes patch)})
        [st1 p1] (pass st)]
    (testing "the first pass renders every unit; a second with nothing changed renders and sends nothing"
      (is (= (count (:seon.render/units (#'p/page-value st :a3))) (get-in st1 [:counts :rendered])))
      (let [[st2 p2] (pass st1)]
        (is (nil? p2))
        (is (= (get-in st1 [:counts :rendered]) (get-in st2 [:counts :rendered])))))
    (testing "the agent changed on main (a commit, as the writer reports it): its head re-renders, nothing else"
      (let [r (d/with main (seon.task/agent-tx "b1" :a3 {:status :idle}))
            ;; the commit's datoms drop the components they touch (the render proc's :main)
            st' (#'p/invalidate (assoc st1 :main-db (:db-after r))
                                (p/comps-touched st1 :main (map (fn [d] {:e (:e d) :a (:a d) :v (:v d)}) (:tx-data r))))
            [_ p] (pass st')]
        (is (= #{"p-head" (str "s-" eid)} (:frags (patch-ids p))) "its head, and its line in the sidebar of root's agents")
        (is (empty? (:prepends p)))))
    (testing "a new evaluation on its branch: one new row, prepended above the rows the page holds; the others untouched"
      (let [k (seon.task/agent-key "b1" :a3)
            branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e4" "a3" {:code "(h)" :response {:handle 'result/e4 :value 1}} nil))
            [_ p] (pass (assoc-in st1 [:branches :a3 :db] branch'))]
        (is (empty? (:frags p)) "no held unit changed its input")
        (is (= 1 (count (filter #(= "#c-rows" (first %)) (:appends p)))) "one part, at the conversation's end")
        (is (str/includes? (second (first (filter #(= "#c-rows" (first %)) (:appends p)))) "(h)"))))
    (testing "only the rows a tab scrolled to are rendered: the rest are counted, and the next come when its end scrolls into view"
      (let [st (assoc-in st [:shown :a3] 1)
            [st1 p] (pass st)
            rows (fn [st] (count (filter #(str/starts-with? % "c-") (remove #(str/starts-with? % "c-more") (keys (get-in st [:views :a3 :units]))))))]
        (is (= 1 (rows st1)))
        ;; at the conversation's top, in view as the page opens: the older load when it is clicked, never by being in view
        (is (str/includes? (get-in st1 [:views :a3 :units "c-more-1" :html]) "4 older parts, shown when this is clicked"))
        (is (str/includes? (get-in st1 [:views :a3 :units "c-more-1" :html]) "data-on:click=\"@post('/more?view=a3')\""))
        (is (not (str/includes? (get-in st1 [:views :a3 :units "c-more-1" :html]) "data-on-intersect")))
        (let [[st2 out] (#'p/render-proc (assoc st1 :shown {:a3 1}) :from-web {:op :more :view :a3})
              p (first (filter #(= :patch (:op %)) (:to-web out)))]
          (is (= 21 (get-in st2 [:shown :a3])) "one more page of rows (:seon.config.render.list/history)")
          (is (= 5 (rows st2)))
          (is (= ["#c-rows"] (mapv first (:prepends p))) "the older parts above what the conversation holds")
          (is (= ["#c-older"] (mapv first (:appends p))) "then its new end")
          (is (= ["c-more-1"] (:removes p)) "the old end goes: a new element, watched anew"))))
    (testing "a page asked for before main is read waits for its first render, never answered empty"
      (let [o (volatile! {})
            st0 (-> st (dissoc :main-db) (assoc :waiting {7 :root}))
            st1 (#'p/pass st0 o)]
        (is (empty? (filter #(= :page (:op %)) (:to-web @o))))
        (is (= {7 :root} (:waiting st1)))
        (let [o (volatile! {})
              st2 (#'p/pass (assoc st1 :main-db main) o)
              page (first (filter #(= :page (:op %)) (:to-web @o)))]
          (is (= 7 (:k page)))
          (is (str/includes? (:html page) "id=\"p-head\""))
          (is (empty? (:waiting st2)))))
      (testing "an agent's page waits for its branch read too: answered with the rows its branch records"
        (let [o (volatile! {})
              st1 (#'p/pass (-> st (update :branches dissoc :a3) (assoc :waiting {8 :a3})) o)]
          (is (empty? (filter #(= :page (:op %)) (:to-web @o))))
          (let [o (volatile! {})
                _ (#'p/pass (assoc-in st1 [:branches :a3 :db] branch) o)
                page (first (filter #(= :page (:op %)) (:to-web @o)))]
            (is (str/includes? (:html page) "(f 2)"))))))
    (testing "a view no tab watches keeps nothing: its page, its branch read, its rows"
      (let [[st2 _] (#'p/render-proc (assoc st1 :shown {:a3 21}) :from-web {:op :views :views #{:root} :tabs 1})]
        (is (nil? (get-in st2 [:views :a3])))
        (is (nil? (get-in st2 [:branches :a3])))
        (is (nil? (get-in st2 [:shown :a3])))
        (is (= st2 (first (#'p/render-proc st2 :from-web {:op :more :view :a3}))) "and its end scrolling into view asks for nothing")))))

(defn- debug-state
  "The render proc's state holding agent a1's debug page: context-test's two model turns (each
  recorded what it sent), root's boot b whose agent a1 is, main and the branch the same db."
  [conn]
  (let [[db sent [e0 e1]] (#'seon.context-test/sent-turns conn)
        db (d/db-with db [{:seon.root/id :root :seon.root/boot "b"} {:seon.agent/key "b/a1" :seon.agent/id :a1}])]
    [{:rid 1 :pending {} :watch #{[:debug :a1]} :waiting {} :kf-wanted #{} :views {} :main-db db
      :branches {[:debug :a1] {:db db}} :view {:agents {}}}
     sent [e0 e1]]))

(deftest the-debug-page-shows-what-each-turn-sent
  ;; task seon.context/a-turn-records-what-it-sent (B): /agent/{id}/debug renders each model turn's
  ;; recorded parts, read from its records (no request built again); a turn's messages there are
  ;; the ones it sent, and their wire body the one the provider got
  (ts/test-branch!
    (fn [_ conn]
      (let [[st [r0 r1] [e0 e1]] (debug-state conn)
            ;; each row a component; its data as its render reads it (seon.render.ui/debug-input)
            rows #(vec (for [u (:seon.render/units (#'p/page-value % [:debug :a1])) :when (= "#d-turns" (:container u))
                             :let [[_ e [_ open? v]] (:comp u)]]
                         {:id (:id u) :input (ui/debug-input (get-in % [:branches [:debug :a1] :db]) e open? v)}))
            units (rows st)
            ;; both rows opened, as a tab opening each row's <details> asks (/open)
            opened (rows (assoc-in st [:open [:debug :a1]] #{(str "d-" e0) (str "d-" e1)}))
            provider {:kind :openai-compat :base-url "https://api.example" :model "deepseek-flash" :api-key-env "NONE" :max-tokens 2048}
            body #(seon.model/request-body provider {:messages % :task "demo/f" :turn 1})
            messages (fn [parts] (vec (mapcat second parts)))
            [st' _] (#'p/pass-view st [:debug :a1])
            html (p/page-html st' [:debug :a1])]
        (is (= ["d-" "d-"] (mapv #(subs (:id %) 0 2) units)) "one row a model turn")
        (is (= [1 0] (mapv #(get-in % [:input :turn]) units)) "newest first")
        (is (= [[:eval "e2"] [:eval "e3"]] (mapv first (get-in units [0 :input :added]))) "turn 1 adds its call's eval and its screen eval")
        (is (= {:turn 0 :parts 2} (get-in units [0 :input :same])) "and sends the help page and the story as turn 0 did")
        (is (nil? (get-in units [0 :input :whole])) "its whole request is not read back until it is opened")
        (is (= r1 (messages (get-in opened [0 :input :whole]))) "opened: turn 1's messages as it sent them")
        (is (= r0 (messages (get-in opened [1 :input :whole]))))
        (is (= (body r1) (body (messages (get-in opened [0 :input :whole])))) "the wire body the provider got, as data")
        (is (str/includes? html "eval e2") "each part beside the record that made it")
        (is (str/includes? html "the task story") "the task story's source, on the first turn's row")
        (is (str/includes? html "and 2 records as turn 1 sent them") "a later row names what it shares")
        (is (str/includes? html "I call f.") "the call's prose")
        (is (= [:debug :a1] (#'seon.web/view-of (java.net.URLDecoder/decode (ui/view-param [:debug :a1]) "UTF-8"))) "its feed names it, and reads it back")
        (is (= [:debug :a1] (#'seon.web/view-of "a1/debug")) "/agent/a1/debug")
        (testing "a turn whose call failed shows what it sent and its typed error"
          (let [db (d/db-with (get-in st [:branches [:debug :a1] :db])
                              [{:db/id e0 :seon.turn/error {:seon.error/type :seon.model/no-provider :seon.error/operation 'seon.model/complete
                                                            :seon.error/message "no model provider"}}])
                [st'' _] (#'p/pass-view (-> st (assoc :main-db db) (assoc-in [:branches [:debug :a1] :db] db)) [:debug :a1])
                html (p/page-html st'' [:debug :a1])]
            (is (str/includes? html "no model provider") "its error, by the error's render")
            (is (str/includes? html "eval e1") "beside what it sent")))))))

(deftest a-new-turn-is-one-new-row
  ;; a turn is written once: a pass after a new turn renders only its row
  (ts/test-branch!
    (fn [_ conn]
      (let [[st _] (debug-state conn)
            [st1 _] (#'p/pass-view st [:debug :a1])
            db (get-in st1 [:branches [:debug :a1] :db])
            b {:commit (d/commit-id db) :task "demo/f" :run "demo/f#1" :turn 2 :max-turns 4 :origin :model
               :observed {:results {} :test-run-errors [] :reds [] :reasons []} :screen ["e3"] :agent "b/a1"}
            db' (d/db-with db [{:seon.turn/id "demo/f#1/2" :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/n 2 :seon.turn/origin :model
                                :seon.turn/basis b :seon.turn/sent (:sent (seon.context/render db b))
                                :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 1}])
            [st2 patch] (#'p/pass-view (assoc-in st1 [:branches [:debug :a1] :db] db') [:debug :a1])]
        (is (= 3 (get-in st1 [:counts :rendered])) "the first pass renders both turns and the list's end")
        (is (= 5 (get-in st2 [:counts :rendered])) "the next renders the new turn alone, and the list's end it moved")
        (is (empty? (:frags patch)) "no held row changed")
        (is (= ["#d-turns"] (mapv first (:prepends patch))) "one row, above the others")))))

(deftest the-debug-page-grows-with-its-rows-not-its-turns
  ;; root, 2026-10-24: at ~560 turns the debug page was 135 MB of HTML and 1.9M elements, each row
  ;; reprinting its whole request (O(turns^2)). A row now shows what its turn added and the rows a
  ;; tab scrolled to render; the page's size follows the rows shown, flat in the run's turns.
  ;; Measured 2026-10-24 before: 0.95 / 3.76 / 15.0 MB and 15,306 / 60,606 / 241,206 elements at 50 /
  ;; 100 / 200 turns (seon.render-cost-test/page-size)
  (let [[a b c] (map seon.render-cost-test/page-size [50 100 200])]
    (is (< (:bytes c) (* 1.1 (:bytes a))) (pr-str [a b c]))
    (is (< (:nodes c) (* 1.1 (:nodes a))) (pr-str [a b c]))
    (is (< (:bytes c) 100000) "a page of the rows shown, not of the run")))

(deftest the-html-fallback-folds-only-what-nests
  ;; the owner, 2026-10-23, on the default html: a fold and its "map 12 items, depth 0" header on
  ;; every collection was noise. The value printed and a collection of scalars are inline; a
  ;; nested collection that holds collections folds, saying what it holds
  (let [flat (h/html (r/html {:demo/id "f" :demo/proofs ['demo-test/f-test]} {}))
        nested (h/html (r/html {:demo/id "f" :rows [{:a 1} {:a 2}]} {}))]
    (is (not (str/includes? flat "<details")) "a map of scalars and a vector of one symbol: no fold")
    (is (not (str/includes? flat "items, depth")))
    (is (str/includes? flat "demo-test/f-test") "the value is all there")
    (is (= 1 (count (re-seq #"<details" nested))) "only the vector of maps folds")
    (is (str/includes? nested "vector 2 items"))))

(deftest the-screen-says-what-the-run-did
  ;; task seon.render/a-page-is-its-entitys-view (4d): from the run's eval records, the vars it
  ;; read (a repeated read shows its count), defined and evaluated, and the forms that ran its
  ;; tests; the screen's own evals are not the run's doing
  (let [ev (fn [id by rec] {:seon.eval/id id :seon.eval/by by :seon.eval/record (merge {:response {} :run "demo/f#1"} rec)})
        evals [(ev "e1" "a3" {:code "(clojure.repl/source demo/f)"})
               (ev "e2" "screen" {:code "(seon.screen/show)" :fns [{:var 'seon.screen/show :calls 1 :ms 1.0}]})
               (ev "e3" "a3" {:code "(source demo/f)"})
               (ev "e4" "a3" {:code "(defn g [x] (demo/f x))" :recorded ['demo/g] :fns [{:var 'demo/f :calls 2 :ms 0.1} {:var 'clojure.core/inc :calls 1 :ms 0.1}]})
               (ev "e5" "a3" {:code "(my.agent/prove)"})]
        did (ui/did evals)]
    (is (= {'demo/f 2} (:read did)) "read twice, shown once with its count")
    (is (= ['demo/g] (:defined did)))
    (is (= ['demo/f] (:evaluated did)) "program functions, the library's left out, the screen's not the run's")
    (is (= [{:form "(my.agent/prove)" :handle "e5"}] (:tests did)))
    (is (str/includes? (h/html (ui/this-run did {})) "demo/f ×2"))))

(deftest the-screen-shows-what-the-prose-cites
  ;; task seon.render/a-page-is-its-entitys-view (4e): what the run's prose cited, as its eval
  ;; records hold it, is on the screen, each cited thing at its citation's depth
  (let [[main branch eid] (paged-dbs)
        [st1 _] (#'p/pass-view (comp-state main branch eid) :a3)
        text (pr-str (:page (p/screen-of st1 :a3)))]
    (is (some #(and (vector? %) (= [:h2 "cited"] (second %)) (str/includes? (pr-str %) "seon.docs/source-of"))
              (tree-seq coll? seq (:page (p/screen-of st1 :a3))))
        (str "the var the prose cited, in the cited tile, read from the record (its prose names none): " text))
    (testing "a run whose records cite nothing has no cited tile"
      (let [branch' (d/db-with branch [{:seon.eval/id "e1" :seon.eval/record {:code "(f 2)" :response {:value "x"} :run "demo/f#1"}}])
            [st2 _] (#'p/pass-view (comp-state main branch' eid) :a3)]
        (is (not (str/includes? (pr-str (:page (p/screen-of st2 :a3))) "[:h2 \"cited\"]")))))))

(deftest a-turns-screen-folds-into-it-and-an-eval-collapses-to-its-value
  ;; root, 2026-10-24: the screen's evals are the system's, folded into their turn's header (they
  ;; stay recorded, and on the debug page as sent); a collapsed eval is code ⇒ value · ms
  (let [[main branch eid] (paged-dbs)
        k (:seon.agent/key (d/pull main [:seon.agent/key] eid))
        branch (reduce d/db-with branch
                       [(seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "s1" "screen"
                                             {:code "(seon.screen/show)" :text "{:handle result/s1, :value {:page []}}" :line "{:page []}"
                                              :response {:handle 'result/s1 :ms 3.0}} nil)
                        [{:seon.turn/id "demo/f#1/0" :seon.turn/basis {:commit #uuid "00000000-0000-0000-0000-000000000000" :task "demo/f" :run "demo/f#1"
                                                                      :turn 0 :max-turns 8 :origin :model :observed {} :screen ["s1"]}}]
                        (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e9" "a3"
                                             {:code "(+ 2 2)" :text "{:handle result/e9, :ms 0.2, :ticks 900, :value 4}" :line "4"
                                              :response {:handle 'result/e9 :ms 0.2 :ticks 900}} nil)])
        st (proc-state main branch)
        l (#'p/page-value st :a3)
        rows (for [u (:seon.render/units l) :when (= "#c-rows" (:container u))] (:comp u))
        html-of (fn [e as] (h/html (ui/component branch e as)))
        e-of #(:db/id (d/entity branch [:seon.eval/id %]))
        turn (:db/id (d/entity branch [:seon.turn/id "demo/f#1/0"]))]
    (is (not-any? #(= (e-of "s1") (second %)) rows) "the screen's eval is no part of the conversation")
    (is (str/includes? (html-of turn [:chat false]) ">screen<") "its turn says it had a screen")
    (is (not (str/includes? (html-of turn [:chat false]) "seon.screen/show")) "rendered only while open")
    (is (str/includes? (html-of turn [:chat true]) "seon.screen/show") "open: the screen eval as it ran")
    (let [closed (html-of (e-of "e9") [:chat false])]
      (is (str/includes? closed ">4<") "collapsed: its value in one line")
      (is (str/includes? closed "0.20 ms") closed)
      (is (not (str/includes? closed "ticks")) "and no response map or cost line twice"))))

(deftest roots-page-reads-in-words
  ;; root, 2026-10-24: root is an agent; its head, problems and runs read as a person reads them,
  ;; each problem one line by its own :html view
  (let [row {:class :error :count 3 :task "demo/f" :run "demo/f#1" :message "boom" :answered-by ["demo/fix"]
             :producer 'demo/f :owner-ns 'demo :evidence {:kind :error} :proofs [] :at-ms nil}]
    (is (= "<p class=\"text-xs\">error ×3 demo/f demo/f#1: boom (answered by demo/fix)</p>" (h/html (ui/problem-row row {}))))
    (let [html (h/html (ui/problems {:t 1 :classes {:error {:n 1 :rows [(dissoc row :class)]} :red {:n 0 :rows []}}} {}))]
      (is (str/includes? html "<summary class=\"cursor-pointer\">error 1</summary>"))
      (is (str/includes? html "error ×3 demo/f") "its rows, each by the row's view")
      (is (not (str/includes? html ">red 0<")) "a class with no rows is left out"))
    (is (= ["none open"] (re-seq #"none open" (h/html (ui/problems {:t 1 :classes {}} {})))))
    (testing "on root's page a closed class renders no rows; the tab opens it (/open)"
      (let [r {:t 1 :classes {:error {:n 1 :rows [(dissoc row :class)]}}}]
        (is (not (str/includes? (h/html (ui/problems (assoc r :open #{}) {})) "boom")))
        (is (str/includes? (h/html (ui/problems (assoc r :open #{"pc-error"}) {})) "boom"))
        (is (str/includes? (h/html (ui/problems (assoc r :open #{}) {})) "unit=pc-error") "its toggle"))))
  (let [html (h/html (ui/runs {:by-status {:running 1 :closed 2} :running [{:seon.run/id "demo/f#1" :seon.run/status :running :seon.run/max-turns 8}]} {}))]
    (is (str/includes? html "2 closed · 1 running"))
    (is (str/includes? html "run demo/f#1 · running · up to 8 turns") "each running one by the run's view"))
  (let [html (h/html (ui/root {:seon.root/id :root :seon.root/boot "b1" :seon.root/generation {:commit "abc" :ms 1}
                              :seon.root/quiescent {:t 9 :root-causes [{:task "demo/f" :cause "red proof" :blocks ["demo/g"]}]}}
                             {:as :block}))]
    (is (str/includes? html "boot b1 · generation abc"))
    (is (str/includes? html "1 root causes when it went quiet at t 9"))
    (is (str/includes? html "demo/f: red proof · blocks 1") "each cause one line")
    (is (not (str/includes? html "#:seon")) "nothing printed raw")))

(deftest an-eval-row-renders-its-value-as-structure
  ;; owner, 2026-10-25 (the value view): a value recorded as data is drawn from its data, a map as
  ;; rows and code as code, not as its printed text; the row's id is its handle's, which links reach
  (let [src "(deftest t\n  ;; why\n  (is (= 2 (f 1))))"
        rec {:code "(my.task/status)" :response {:handle 'result/e157 :ms 2.5 :value {:turn "29 of 30" :proofs [{:test 'demo-test/t :status :fail :source (seon.print/code src)}
                                                                                                           {:test 'demo-test/u :status :pass :source (seon.print/code "(deftest u)")}]}}}
        html (h/html (ui/evaluation {:seon.eval/record rec :seon.eval/by "outside"} {}))
        held (h/html (ui/evaluation {:seon.eval/record {:code "(Object.)" :printed "#object[java.lang.Object]" :response {:handle 'result/e2}}} {}))]
    (is (str/includes? html "id=\"eval-e157\"") "the row is its handle's anchor")
    (is (str/includes? html "seon-print-grid") "the value, a map, as rows")
    (is (str/includes? html "<table class=\"seon-print-table\"") "its proofs, maps sharing keys, as a table")
    (is (str/includes? html ";; why") "the source verbatim, its comment as written")
    (is (not (str/includes? html "\\\"")) "no escaped string literal")
    (is (not (str/includes? html ":ms")) "the cost is on its own line, not in the value")
    (is (str/includes? held "#object[java.lang.Object]") "a value not held as data shows as it was printed")))

(deftest a-page-an-agent-built-renders-nested
  ;; owner, 2026-10-25: an agent's hiccup renders as HTML. It is the value's own :seon.render/html
  ;; key (a render already made), wherever the value sits
  (let [page [:section [:h2 "agents"] [:ol [:li "a1 busy"]]]
        html (h/html (r/html {:screen {:seon.render/html page} :n 1} {}))]
    (is (str/includes? html "<section><h2>agents</h2><ol><li>a1 busy</li></ol></section>") html)))

(deftest a-cut-opens-its-path-in-the-row
  ;; the value view's step 3: a cut on the page is a toggle naming its path; the row, given that
  ;; path opened (/open), renders it at the whole budget from the record, under the form the cut names
  (let [body (str/join "\n" (repeat 50 "  (is (= 2 (f 1)))"))
        proof (fn [n] {:test (symbol "demo-test" n) :source (seon.print/code (str "(deftest " n "\n" body ")"))})
        rec {:code "(my.task/status)" :response {:handle 'result/e5 :value {:proofs (mapv proof ["t" "u" "v"])}}}
        closed (h/html (ui/evaluation {:seon.eval/record rec} {}))
        opened (h/html (ui/evaluation {:seon.eval/record rec} {:opened #{"[:value :proofs 0 :source]"}}))]
    (is (str/includes? closed "class=\"seon-print-cut\"") "three sources over the value's budget: cut, each cut a toggle")
    (is (str/includes? closed "data-seon-path=\"[:value :proofs 0 :source]\"") "naming its path in the value printed")
    (is (not (str/includes? closed body)) "closed, t's source is cut")
    (is (str/includes? opened body) "opened, its path renders whole at the value's budget")))
