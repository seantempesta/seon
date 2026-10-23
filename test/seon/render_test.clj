(ns seon.render-test
  "Renders (session W2): selection (override key, schema-declared, printer), root's AI face,
  and the render proc's step as a plain function: inputs before render, bytes before
  send, a new agent appended, a retired one removed, keyframes composed from what was
  retained. Run: bin/test seon.render-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str] [clojure.core.async :as a]
            [seon.render :as r] [seon.render.ui :as ui] [seon.render.ai :as ai] [seon.render.proc :as p]
            [seon.web.html :as h] [seon.config] [seon.docs] [seon.print] [seon.db] [seon.reconcile] [seon.task] [seon.edn] [seon.context] [seon.contract] [seon.repl] [datahike.api :as d]
            [seon.fx] [seon.source] [seon.program] [seon.web] [seon.turn] [seon.model] [seon.problems]))

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
  (let [text (r/ai sys {:schema :seon.render/system :budget 700})
        lines (str/split-lines text)]
    (testing "problems first: root's defect, then what main recorded (one producer); no proc is pinged"
      (is (= "# problems" (second lines)))
      (is (not (re-find #"\d\d:\d\d:\d\d" (first lines))) "no clock: an unchanged system gives the same text")
      (is (not-any? #(str/includes? % "UNKNOWN") lines) "no ping, so no unanswered ping")
      (is (some #(str/includes? % "defect in render: boom") lines))
      (is (some #(str/includes? % "recorded agent 3: tests red: agent.a1/c") lines))
      (testing "a class's other open rows are named, with where they all are"
        (is (some #(str/includes? % "tests red: agent.a1/c · +1 more: open, all in (get-in (my.root/problems sys) [:classes :agent :rows])") lines))))
    (testing "the live view is not a second producer: an agent's own entry makes no problem line"
      (is (not-any? #(re-find #"^- a\d" %) lines)))
    (testing "over the budget: later sections are named, not silently cut"
      (let [small (r/ai sys {:schema :seon.render/system :budget 40})]
        (is (str/includes? small "not shown, over the budget"))
        (is (<= (seon.print.select/estimate (first (str/split small #"\n\(not shown"))) 40))))))

(deftest compose-fills-slots-recursively
  (is (= "<a><b>x</b></a>" (p/compose "<a><!--s:b--></a>" {"b" {:html "<b><!--s:c--></b>"} "c" {:html "x"}}))))

(deftest observed-task-states
  (let [decl (fn [nm proofs & needs] (cond-> {:seon.task/ns 't :seon.task/name nm :seon.task/plan (str "Make " nm ".") :seon.task/proofs proofs}
                                       needs (assoc :seon.task/needs (set needs))))
        db (ts/test-db)
        db (-> db (d/db-with (seon.task/create-tx db [(decl "a" #{'p/x}) (decl "b" #{'p/z} "t/a") (decl "c" #{'p/w})]))
               (d/db-with (seon.task/execution-tx ["t/a" "t/b"] :running))
               (d/db-with (seon.task/execution-tx ["t/c"] :cancelled))
               (d/db-with (conj (seon.task/run-start-tx "t/a#1" "t/a" "boot-1" (seon.task/agent-key "boot-1" :a1) {:branch :task-t-a :model nil :max-turns 40} 1 "Make a.")
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

(deftest root-context-names-what-each-list-left
  (let [runs (into {} (for [i (range 12)] [(str "t/x" i "#1") {:seon.run/id (str "t/x" i "#1") :seon.run/status :done
                                                                     :seon.run/outcome :green :seon.run/end-ms i
                                                                     :seon.run/reason (if (= i 11) (long-text 200) "ok")}]))
        s (assoc sys :tasks {"t/x0" {}} :runs runs :task-rows {}
                 :root-errors [{:pid :render :message (long-text 300)}] :root-errors-total 4
                 :events (vec (for [i (range 30)] {:type :ready :agent :a1 :at-ms i})))
        text (r/ai s {:schema :seon.render/system :budget 2000})]
    (testing "five ended runs shown, seven more named with the form that reads them"
      (is (str/includes? text "+7 more: ended, all in (seon.task/runs (deref (:conn sys)))")))
    (testing "a long reason is elided with the form that reads its rest"
      (is (re-find #"…\+\d+ chars \(subs \(:seon.run/reason \(get \(seon.task/runs" text)))
    (testing "root's defects: the shown one, its cut's form, and how many more"
      (is (re-find #"defect in render: word0 .*…\+\d+ chars \(subs \(:message \(nth \(:root-errors \(seon.root/state sys\)\) 0\)\) \d+\)" text))
      (is (str/includes? text "+3 more: (:root-errors (seon.root/state sys))")))
    (testing "the events: eight newest, and the rest named"
      (is (str/includes? text "+22 more: on the dashboard's event stream")))))

(deftest root-context-groups-blocked-tasks-by-what-blocks-them
  (let [rows {"t/a" {:state :blocked :blocked-by #{"t/root"}} "t/b" {:state :blocked :blocked-by #{"t/root" "t/other"}}
              "t/c" {:state :blocked :blocked-by #{"t/root"}} "t/d" {:state :done :blocked-by #{}}}
        text (r/ai (assoc sys :tasks {"t/a" {}} :task-rows rows) {:schema :seon.render/system :budget 2000})]
    (is (str/includes? text "3 with needs not done, waiting on: t/root (3), t/other (1)") "each blocker once, the one holding most first")
    (is (not (str/includes? text "t/a blocked by")) "not one line per blocked task")))

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
    (is (= #{:answers :from-web} (set (keys (:ins d)))))
    (is (nil? (resolve 'seon.render.proc/SAMPLE-MS)))
    (is (nil? (resolve 'seon.render.proc/start-sampler!))))
  (testing "a proc's health from root's view: its declared procs, its defects by pid"
    (let [sysp (doto (promise) (deliver {:procs {:api {} :render {} :fx/model {}}}))
          h (#'p/proc-health-of {:system-p sysp} [{:pid :render :message "a"} {:pid :render :message "b"}])]
      (is (= {:defects 2 :newest "b"} (get h :render)))
      (is (= {:defects 0} (get h :api)))
      (is (= 3 (count h))))))

;; ---- one bounded list: its bound is config, its cut says what it left -----------------------

(deftest a-bounded-list-is-one-call-with-its-bound-in-config
  (let [n (seon.config/value :seon.config.render.list/top)
        [shown more] (seon.print/bounded (range 10) :seon.config.render.list/top "in (range 10)")]
    (is (= (range n) shown) "the first n, n from the config key")
    (is (= (str "+" (- 10 n) " more: in (range 10)") more)))
  (testing "a total beyond the items (a view that keeps fewer than were recorded) counts the whole"
    (is (= "+17 more: older" (second (seon.print/bounded [1 2 3] :seon.config.render.list/top "older" 20)))))
  (testing "nothing left, nothing said"
    (is (nil? (second (seon.print/bounded [1] :seon.config.render.list/top "x"))))))

(deftest the-load-tiles-are-data-the-context-reads
  (let [tiles (ui/load-tiles {:tx-count 4} {:status :down :pid 7} {:datoms 100})
        text (r/ai sys {:schema :seon.render/system :budget 2000})]
    (is (= ["t-writer" "t-fork-server" "t-main"] (mapv :id tiles)))
    (is (= "down" (second (:pill (second tiles)))))
    (testing "root's context prints them, one line each"
      (is (some #(str/starts-with? % "- writer 4 txs, batch") (str/split-lines text)))
      (is (some #(str/starts-with? % "- fork server up – ms fork p50, forked –") (str/split-lines text))))))

(def ^:private behind
  ;; as seon.writer/merge-reason makes it
  (seon.contract/error :seon.merge/behind 'seon.writer/merge-branch
                       "main changed 3 key(s) since the branch's merge base: merge main into the branch first"
                       {:tx 812 :behind 3
                        :seon.error/fix "A merge task merges main into the branch (my.branch/merge-main!), resolves any conflict, and its proofs run on the combined program."}))

(deftest an-error-reads-its-message-fix-and-variant
  (testing "an error map's schema is its variant: the base's render, whatever the variant"
    (is (= :seon.merge/behind (r/schema-of behind nil)))
    (is (= :seon.error/base (r/schema-of {:seon.error/type :no.such/variant :seon.error/operation 'a/b :seon.error/message "m"} nil))))
  (let [text (r/ai behind {:handle 'result/h4})]
    (testing "the message first, as a REPL answers; the fix; then its variant, where, and its own keys"
      (is (str/starts-with? text "main changed 3 key(s)"))
      (is (str/includes? text "fix: A merge task merges main into the branch (my.branch/merge-main!)"))
      (is (str/includes? text "(seon.merge/behind in seon.writer/merge-branch {"))
      (is (str/includes? text ":behind 3"))
      (is (str/includes? text "the whole value as data: (with-meta result/h4 nil)"))))
  (testing "the page shows the same facts"
    (let [html (h/html (r/html behind {}))]
      (is (str/includes? html "main changed 3 key(s)"))
      (is (str/includes? html "fix: A merge task merges main"))
      (is (str/includes? html "seon.merge/behind in seon.writer/merge-branch")))))

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
    (let [text (:text (r/printed (with-meta failed-task {:seon.render/schema :seon.task/task}) 'result/h2))]
      (is (str/starts-with? text "task calc/add · work · held · needs calc/sub"))
      (is (str/includes? text "failed: add cannot be total over longs · counterexample (add Long/MAX_VALUE 1)"))
      (testing "a long plan shows whole under the REPL's reply budget"
        (is (str/includes? text "Step 399")))
      (testing "(the part budget when the env names none)"
        (is (re-find #"…\+\d+ chars in \d+ more lines \(subs \(get-in result/h2 \[:seon.task/accumulated :plan\]\) \d+\)" (r/ai failed-task {:handle 'result/h2}))))
      (testing "the proofs are a bounded list: how many more and where"
        (is (str/includes? text "12 proofs: app.proofs/add-0-test"))
        (is (str/includes? text "+4 more: (get-in result/h2 [:seon.task/accumulated :proofs])")))
      (is (str/includes? text "evidence: {:from \"calc/add#2\""))))
  (testing "the page's task box shows the failure too"
    (let [html (h/html (r/html failed-task {:schema :seon.task/task}))]
      (is (str/includes? html "failed in calc/add#2: add cannot be total over longs")))))

(deftest a-run-reads-in-one-line
  (is (= "running calc/add#1 on a3, turn 2, 1840 tokens"
         (r/ai {:seon.run/id "calc/add#1" :seon.run/status :running :seon.run/agent :a3 :seon.run/turns 2 :seon.run/tokens 1840} {})))
  (let [line (r/ai {:seon.run/id "calc/add#2" :seon.run/status :failed :seon.run/outcome :merge-failed
                    :seon.run/reason (str "merge refused: " (:seon.error/message behind))
                    :seon.run/conflicts [{:key "var app.calc/add" :kind :var :base "a" :ours "b" :theirs "c"}]
                    :seon.run/unmerged ["var app.calc/add"]}
                   {:handle '(get atts "calc/add#2")})]
    (is (str/starts-with? line "ended calc/add#2 merge-failed: merge refused: main changed 3 key(s)"))
    (is (str/includes? line " · 1 conflicts in (:seon.run/conflicts (get atts \"calc/add#2\")) · 1 unmerged in (:seon.run/unmerged (get atts \"calc/add#2\"))"))))

(defn- row
  "A problem row as seon.problems/report makes it: every row's members, then its class's."
  [m] (merge {:producer nil :owner-ns 'calc :evidence {} :proofs [] :answered-by [] :at-ms 1761170000000} m))

(deftest every-problem-class-reads-what-it-is-about-and-why
  ;; rows as seon.problems/report makes them, one per class that had no line of its own
  (let [line #(r/ai (row (assoc %2 :class %1)) {:schema :seon.problems/row})]
    (is (= "task-conflict calc/add calc/add#3 plan: {:base \"Make add.\", :ours \"Make add total.\", :theirs \"Make add pass.\"}"
           (line :task-conflict {:task "calc/add" :run "calc/add#3" :attr :plan :open? true
                                 :evidence {:task "calc/add" :attr :plan :base "Make add." :ours "Make add total." :theirs "Make add pass." :from "calc/add#3"}})))
    (is (= "failed calc/add calc/add#2: run calc/add#2 ended cap: proof-red app.proofs/add-test"
           (line :failed {:task "calc/add" :run "calc/add#2" :reason "run calc/add#2 ended cap: proof-red app.proofs/add-test"}))
        "a refused run's hold: a reason and no counterexample")
    (is (= "failed calc/add calc/add#2: add cannot be total over longs"
           (line :failed {:task "calc/add" :run "calc/add#2" :reason "add cannot be total over longs" :counterexample "(add Long/MAX_VALUE 1)"})))
    (is (= "merge calc/add calc/add#2: main changed 3 key(s) since the branch's merge base: merge main into the branch first · 1 conflicts · unmerged var app.calc/add"
           (line :merge {:task "calc/add" :run "calc/add#2" :error behind :conflicts 1 :unmerged ["var app.calc/add"]})))
    (is (= "red calc/add calc/add#1 app.proofs/add-test: tests-red"
           (line :red {:task "calc/add" :run "calc/add#1" :test 'app.proofs/add-test :outcome :tests-red})))
    (is (= "platform ×4: Datahike transact refused (answered by calc/fix-store)"
           (line :platform {:signature "s" :message "Datahike transact refused" :count 4 :answered-by ["calc/fix-store"]}))))
  (testing "the whole report at root's REPL: by class, each row by its render, closed rows marked"
    (let [rep (with-meta {:t 90 :classes {:failed {:n 1 :rows [(row {:task "calc/add" :reason "not total" :open? true})]}
                                          :merge {:n 1 :rows [(row {:task "calc/sub" :run "calc/sub#1" :error behind :open? false})]}
                                          :red {:n 0 :rows []}}}
                {:seon.render/schema :seon.problems/report})
          text (:text (r/printed rep 'result/h1))]
      (is (= ["problems recorded on main at t 90" "# failed 1" "- failed calc/add: not total" "# merge 1" "- merge calc/sub calc/sub#1: main changed 3 key(s) since the branch's merge base: merge main into the branch first (closed)"
              "the whole value as data: (with-meta result/h1 nil)"]
             (str/split-lines text))))))


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

(deftest an-outside-model-reads-an-evaluation-as-the-repl-says-it
  ;; seon_eval's tool text (seon.server/mcp-reply): reply items as a REPL shows them, never
  ;; the items' map printed with its strings escaped
  (let [text (ai/items [{:id 0 :tag :ret :form "(def outside-x 41)" :printed "#'user/outside-x" :handle :h0}
                        {:id 1 :tag :ret :form "(println \"hi\") (inc outside-x)" :out "hi\n" :printed "42" :handle :h1}
                        {:id 2 :tag :err :form "(/ 1 0)" :printed "Divide by zero\n(seon.agent/eval-error in seon.repl/eval-code)"}])]
    (is (= (str "(def outside-x 41)\n=> #'user/outside-x\n;; result/h0\n\n"
                "(println \"hi\") (inc outside-x)\nhi\n=> 42\n;; result/h1\n\n"
                "(/ 1 0)\nx Divide by zero\n(seon.agent/eval-error in seon.repl/eval-code)")
           text))))

(deftest the-model-reads-a-rendered-value-as-its-repl-did
  ;; the context's last evaluations (seon.context/shown-evals): a value whose producer named
  ;; its render shows that render's text, as the agent's REPL answered it; other values print
  ;; structurally from the live value
  (let [t (with-meta failed-task {:seon.render/schema :seon.task/task})
        [shown plain] (seon.context/shown-evals [{:k 0 :times 1 :item {:tag :ret :form "(my.task/current)" :value t :printed (:text (r/printed t '(nth (my.turn/evals) 0)))}}
                                                 {:k 1 :times 1 :item {:tag :ret :form "{:n 1}" :value {:n 1} :printed "{:n 1}"}}])]
    (is (str/includes? (:text shown) "task calc/add · work · held"))
    (testing "the render's text as text, its lines real lines, never an escaped string literal"
      (is (str/includes? (:text shown) "\nfailed: add cannot be total"))
      (is (not (str/includes? (:text shown) "\\n"))))
    (is (not (str/includes? (:text shown) ":seon.task/accumulated")))
    (is (str/includes? (:text plain) ":value {:n 1}"))))

;; ---- the agent's debug view (task seon.render/a-debug-view-of-any-agent) ----------------

(deftest the-printer-renders-each-nested-value-by-its-own-render
  ;; owner, 2026-10-23: the default render auto-renders. A value inside the printed one whose
  ;; schema declares a render shows as that render, one line, in its place: the run's :ai
  ;; render in the text, a call of its :html render in the hiccup (expanded by the serializer);
  ;; the printed value itself is the printer's, never its own render again
  (let [a {:seon.run/id "calc/square#1" :seon.run/agent :a4 :seon.run/status :done :seon.run/outcome :green}
        v {:newest a :count 3}]
    (is (= "{:count 3, :newest ended calc/square#1 green}" (r/ai v {})) "the text: the run's own one line")
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
             (seon.task/run-start-tx "demo/f#1" "demo/f" "b1" k {:branch :task-demo-f :model "m" :max-turns 8} 0 "p")
             [{:seon.run/id "demo/f#1" :seon.run/results [{:seon.proof/test 'demo/f-test :seon.proof/status :pass}]}]
             (seon.task/message-tx "m-1" "owner" "demo/f#1" "check g, see `seon.print`")
             [{:db/id "run" :seon.run/id "demo/f#1"}
              {:seon.turn/id "demo/f#1/0" :seon.turn/run "run" :seon.turn/n 0 :seon.turn/origin :model :seon.turn/reply "(f 2), as `seon.render/walk` says"
               :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 5}]
             (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "a3-77-1"
                                  [{:id 1 :tag :ret :form "(f 2)" :printed "4"} {:id 2 :tag :ret :form "(f 3)" :printed "6"}] nil)])))

(defn- eid-of [db ref] (:db/id (d/entity db ref)))

(defn- attr-of [nd] (get-in nd [:seon.render/from :seon.render/attr]))

(defn- values-of
  "The k of each node of walk w reached by attribute a."
  [w a k] (into [] (comp (filter #(= a (attr-of %))) (map #(get-in % [:seon.render/value k]))) (:seon.render/nodes w)))

(deftest a-walk-is-its-entity-and-its-units
  (let [db (walked-db)]
    (testing "an agent: its own attributes, then its units in its schema's order, each a neighbour pulled whole"
      (let [eid (eid-of db [:seon.agent/key "b1/a3"])
            w (r/walk db eid)
            root (:seon.render/root w)]
        (is (= {:seon.render/kind :entity :seon.render/schema :seon.db/agent-fact :seon.render/eid eid}
               (select-keys root [:seon.render/kind :seon.render/schema :seon.render/eid :seon.render/from])) "the root came from nowhere")
        (is (= {:seon.agent/id :a3 :seon.agent/status :idle :seon.agent/pid 77 :seon.agent/running "(f 2)"}
               (select-keys (:seon.render/value root) [:seon.agent/id :seon.agent/status :seon.agent/pid :seon.agent/running])))
        (is (not (contains? (:seon.render/value root) :seon.agent/root)) "a unit is its own node, not again in the entity")
        (is (= [:seon.run/_agent :seon.cluster.eval/_agent :seon.cluster.eval/_agent] (mapv attr-of (:seon.render/nodes w)))
            "its run and its evaluations; root, of no use to a working agent, is not its unit")
        (is (every? #(= eid (get-in % [:seon.render/from :seon.render/eid])) (:seon.render/nodes w)) "each says where it came from")
        (is (= ["demo/f#1"] (values-of w :seon.run/_agent :seon.run/id)))
        (is (= ["a3-77-1/1" "a3-77-1/2"] (values-of w :seon.cluster.eval/_agent :seon.cluster.eval/id)))
        (testing "each node is an entity with its own schema; an evaluation, with no identity, the one naming the ref that reached it"
          (is (= [:seon.run/run-fact :seon.db/eval-fact :seon.db/eval-fact] (mapv :seon.render/schema (:seon.render/nodes w))))
          (is (every? #(= :entity (:seon.render/kind %)) (:seon.render/nodes w))))
        (testing "a neighbour holds only the attributes its schema declares relevant (:seon.render/keys)"
          (let [run (first (:seon.render/nodes w))]
            (is (= "demo/f#1" (get-in run [:seon.render/value :seon.run/id])))
            (is (not (contains? (:seon.render/value run) :seon.run/plan)) "its plan is its task's")
            (is (not (contains? (:seon.render/value run) :seon.run/boot)) "its boot is root's")))))
    (testing "a run: its task, its turns, the messages to it"
      (let [w (r/walk db (eid-of db [:seon.run/id "demo/f#1"]))]
        (is (= :seon.run/run-fact (get-in w [:seon.render/root :seon.render/schema])))
        (is (= [:seon.run/task :seon.turn/_run :seon.message/_to] (mapv attr-of (:seon.render/nodes w))))
        (is (= ["demo/f"] (values-of w :seon.run/task :seon.task/id)))
        (is (= ["demo/f#1/0"] (values-of w :seon.turn/_run :seon.turn/id)))
        (is (= ["m-1"] (values-of w :seon.message/_to :seon.message/id)))))
    (testing "root: its agents"
      (is (= [:a3] (values-of (r/walk db (eid-of db [:seon.root/id :root])) :seon.agent/_root :seon.agent/id))))
    (testing "a task: units that are its own values, plan then proofs, each its attribute's schema"
      (let [eid (eid-of db [:seon.task/id "demo/f"])
            w (r/walk db eid)]
        (is (= [[:value :seon.task/plan walked-plan eid] [:value :seon.task/proofs ['demo/f-test] eid]]
               (mapv (juxt :seon.render/kind :seon.render/schema :seon.render/value :seon.render/eid) (:seon.render/nodes w))))
        (is (not (contains? (get-in w [:seon.render/root :seon.render/value]) :seon.task/plan)))))
    (testing "an eid with nothing on db is an error to the asker, not an empty entity"
      (is (= :seon.render/no-entity (:seon.error/type (r/walk db 99999999)))))
    (testing "every identity and ref attribute is named by one entity schema (the walk's schema of an entity)"
      (let [attrs #{:seon.agent/key :seon.run/id :seon.task/id :seon.root/id :seon.turn/id :seon.message/id
                    :seon.run/agent :seon.turn/run :seon.message/to :seon.agent/root :seon.cluster.eval/agent :seon.run/task}
            named (frequencies (for [[k form] seon.contract/forms :when (:seon.db/attributes (seon.contract/properties k))
                                     [a] (filter vector? (rest form)) :when (attrs a)]
                                 a))]
        (is (= attrs (set (keys named))) "each is named by an entity schema")
        (is (every? #{1} (vals named)) (pr-str named))))))

(deftest a-walk-pulls-what-its-strings-cite
  (let [db (walked-db)
        task (eid-of db [:seon.task/id "demo/f"])
        w (r/walk db task)
        refs (:seon.render/refs w)]
    (testing "the plan's citations, each pulled once at its delimiter's depth (seon.docs/cited, the context's pull), numbered"
      ;; the test store holds no wiki sections, so the link reaches the checkout's file, and says so
      (is (= [[1 :var "seon.render/walk" :doc] [2 :namespace "seon.print" :doc] [3 :file "docs/render.md#no-clipping" :doc]]
             (mapv (juxt :seon.render/n :seon.render/kind :seon.render/address :seon.render/depth) refs)))
      (is (= 'seon.render/walk (get-in refs [0 :seon.render/value :symbol])))
      (is (string? (get-in refs [0 :seon.render/value :doc])))
      (is (= "docs/render.md#no-clipping" (get-in refs [2 :seon.render/value :seon.ref/address])))
      (is (= [{:seon.render/eid task :seon.render/attr :seon.task/plan :seon.render/line 1}] (get-in refs [0 :seon.render/cited-at])))
      (is (= [1 2 3] (get-in w [:seon.render/nodes 0 :seon.render/cites])) "the plan's node cites them by number"))
    (testing "only prose cites: an id names its entity, and a value that is no prose is data"
      (is (not-any? #(= "demo/f" (:seon.render/address %)) refs))
      (is (nil? (get-in w [:seon.render/root :seon.render/cites]))))
    (testing "a citation repeated across the walk is one ref, cited by its number at each place"
      (let [run (r/walk db (eid-of db [:seon.run/id "demo/f#1"]))
            by (into {} (map (juxt attr-of :seon.render/cites)) (:seon.render/nodes run))]
        (is (= 3 (count (:seon.render/refs run))))
        (is (= {:seon.run/task [1 2 3] :seon.turn/_run [1] :seon.message/_to [2]} by))
        (is (= [:seon.task/plan :seon.turn/reply] (mapv :seon.render/attr (get-in run [:seon.render/refs 0 :seon.render/cited-at]))))))
    (testing "refs a context has already seen keep their numbers: the run's turn and message cite 1 and 2 of the task's walk"
      (let [run (r/walk db (eid-of db [:seon.run/id "demo/f#1"]) refs)]
        (is (= (mapv :seon.render/value refs) (mapv :seon.render/value (:seon.render/refs run))))
        (is (= [1] (:seon.render/cites (second (:seon.render/nodes run)))))
        (is (= 3 (count (get-in run [:seon.render/refs 0 :seon.render/cited-at]))) "the task's place, then the run's task's and its turn's")))))

(deftest a-walk-names-the-render-of-each-part
  (let [db (walked-db)
        run (eid-of db [:seon.run/id "demo/f#1"])
        w (r/rendered (r/walk db run) {})
        [task-node turn msg] (:seon.render/nodes w)
        by (juxt :seon.render/as :seon.render/ai-by :seon.render/html-by)]
    (is (= [:block 'seon.render/print-ai 'seon.render/print-html] (by (:seon.render/root w))) "no render declared: the one printer, named")
    (is (= [:tile 'seon.render/print-ai 'seon.render/print-html] (by task-node)) "no render declared: the one printer, named")
    (is (= [:tile 'seon.render/print-ai 'seon.render.ui/turn] (by turn)) "a turn by its schema's declared render")
    (is (= (r/render-of (:seon.render/value turn) :html :seon.turn/turn-fact) @(requiring-resolve (:seon.render/html-by turn))) "the named render is the one that ran")
    (is (every? #(= :tile (:seon.render/as %)) (concat [task-node msg] (:seon.render/refs w))))
    (testing "both faces from the one walk, each saying whether it was cut"
      (is (str/includes? (:seon.render/ai-text (:seon.render/root w)) "demo/f#1"))
      (is (false? (:seon.render/truncated? (:seon.render/root w))))
      (is (str/includes? (h/html (:seon.render/hiccup turn)) "as `seon.render/walk` says") "the turn row shows the turn's reply")
      (is (str/includes? (:seon.render/ai-text msg) "check g"))
      (is (str/includes? (:seon.render/ai-text (first (:seon.render/refs w))) "seon.render/walk")))))

(deftest render-name-names-what-render-of-picks
  ;; render-name repeats render-of's selection (a shared step would cost the printer three calls a
  ;; nested value): every outcome agrees
  (doseq [[v face hint] [[{:k 1} :ai nil] [{:k 1} :html nil]
                         [{:seon.run/id "x#1"} :ai nil] [{:seon.run/id "x#1"} :html nil]
                         [{:k 1 :seon.render/html 'seon.render.ui/task-box} :html nil]
                         [{:k 1 :seon.render/html [:b "mine"]} :html nil]
                         [{:k 1} :html :seon.turn/turn-fact] [{:k 1} :ai :seon.task/task]]
          :let [f (r/render-of v face hint) n (r/render-name v face hint)]]
    (if f
      (is (= f @(requiring-resolve n)) (pr-str v face hint))
      (is (contains? #{'seon.render/print-ai 'seon.render/print-html (r/faces face)} n) (pr-str v face hint)))))

;; ---- the page (task seon.render/page): every page is the walk of one entity ----

(defn- paged-dbs
  "Main and a3's branch as production writes them: main holds root, the task, the agent (forked
  idle, busy since), its run and a message to the run; the branch, forked from main before the
  agent turned busy, adds what only it records: a model turn whose one call ran two evaluations
  (named by the call's :items), then an evaluation the owner asked for from the page, an error.
  Answers [main branch agent-eid]."
  []
  (let [k (seon.task/agent-key "b1" :a3)
        main0 (reduce d/db-with (ts/test-db)
                      [[{:seon.root/id :root :seon.root/boot "b1"}]
                       [{:seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan walked-plan :seon.task/execution :running
                         :seon.task/branch :task-demo-f :seon.task/kind :work :seon.task/proofs #{'demo/f-test}}]
                       (seon.task/agent-tx "b1" :a3 {:status :idle :pid 77 :branch :task-demo-f})
                       (seon.task/run-start-tx "demo/f#1" "demo/f" "b1" k {:branch :task-demo-f :model "m" :max-turns 8} 0 "p")
                       (seon.task/message-tx "m-1" "owner" "demo/f#1" "check g")
                       ;; root's own evaluation, from its REPL
                       (seon.repl/record-tx {:seon.root/id :root} "root-1" [{:id 1 :tag :ret :form "(my.root/problems sys)" :printed "{}"}] nil)])
        branch (reduce d/db-with main0
                       [(seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "a3-77-1"
                                             [{:id 1 :tag :ret :form "(f 2)" :lead ";; f doubles?" :printed "4" :ms 1.5 :alloc-bytes 2048 :ticks 120}
                                              {:id 2 :tag :ret :form "(f 3)" :printed "6" :ms 0.4 :alloc-bytes 512 :ticks 40}] nil)
                        [{:db/id "run" :seon.run/id "demo/f#1"}
                         {:seon.turn/id "demo/f#1/0" :seon.turn/run "run" :seon.turn/n 0 :seon.turn/origin :model :seon.turn/reply "it doubles"
                          :seon.turn/forms 2 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 5 :seon.turn/tokens 1200
                          :seon.turn/basis {:commit #uuid "00000000-0000-0000-0000-000000000000" :task "demo/f" :run "demo/f#1" :turn 0 :max-turns 8 :origin :model :observed {}}
                          :seon.turn/calls [{:id "c0" :code "(f 2) (f 3)" :evals "" :items ["a3-77-1/1" "a3-77-1/2"]}]}]
                        (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "a3-77-2"
                                             [{:id 1 :tag :err :form "(g)" :printed "boom" :origin :ui}] nil)])
        main (d/db-with main0 (seon.task/agent-tx "b1" :a3 {:status :busy}))]
    [main branch (eid-of main [:seon.agent/key k])]))

(defn- rows-of "Each row unit of layout l: its parts' schemas." [l]
  (for [u (:seon.render/units l) :when (= :seon.render/page-row (:schema u))] (mapv :seon.render/schema (:input u))))

(defn- unit-html "Unit u of a layout, rendered as the render proc renders it." [u]
  (h/html (r/html (:input u) {:schema (:schema u) :unit (:id u)})))

(deftest a-page-is-the-walk-of-its-entity
  (let [[main branch eid] (paged-dbs)
        w (r/page [main branch] eid)
        l (ui/page w {:shown 20 :view "a3"})
        units (:seon.render/units l)]
    (testing "the entity as main holds it: what root observed wins over the branch's older copy"
      (is (= :busy (get-in w [:seon.render/root :seon.render/value :seon.agent/status])))
      (is (= "p-head" (:id (first units))))
      (is (every? #(str/includes? (unit-html (first units)) %) [":a3" ":busy" ":task-demo-f"]) "its relevant facts, by the one printer at the page's budget"))
    (testing "its units from every db, each once: main's run, the branch's evaluations"
      (is (= 3 (count (filter #(= :seon.db/eval-fact (:seon.render/schema %)) (:seon.render/nodes w)))))
      (is (= (count (distinct (map :seon.render/eid (:seon.render/nodes w)))) (count (:seon.render/nodes w)))))
    (testing "one list, newest first: the owner's evaluation, then the turn with its two evaluations in the order its call ran them, then the message"
      (is (= [[:seon.db/eval-fact] [:seon.turn/turn-fact :seon.db/eval-fact :seon.db/eval-fact] [:seon.message/fact]] (rows-of l)))
      (let [turn (unit-html (second (filter #(= :seon.render/page-row (:schema %)) units)))]
        (is (< (str/index-of turn "turn 1 of 8") (str/index-of turn "(f 2)") (str/index-of turn "(f 3)")))
        (is (str/includes? turn "f doubles?") "a form's ;; thought, as prose")
        (is (str/includes? turn "1.5 ms · <span>2.0 KB</span>") "its cost as recorded, each number with its unit")
        (is (str/includes? (h/html (#'ui/cost-line {:ticks 1200 :ms 1.5})) "1,200 ticks · 1.5 ms") "ticks too, when recorded; nothing not recorded")
        (is (str/includes? turn "1,200 tokens"))))
    (testing "each evaluation is rendered once: in its turn's row, or alone"
      (let [ids (for [u units :when (= :seon.render/page-row (:schema u)) nd (:input u) :when (= :seon.db/eval-fact (:seon.render/schema nd))] (:seon.render/eid nd))]
        (is (= 3 (count ids) (count (distinct ids))))))
    (testing "its other units are tiles, each a link that moves the page to its entity"
      (is (= [:seon.run/run-fact :seon.task/task-fact]
             (for [u units :when (= :seon.render/page-tile (:schema u))] (get-in u [:input :seon.render/schema]))))
      (let [run (first (filter #(= :seon.render/page-tile (:schema %)) units))]
        (is (str/includes? (unit-html run) (str "href=\"/walk/" (get-in run [:input :seon.render/eid]) "\"")))))
    (testing "an entity no db holds is an error to the asker"
      (is (= :seon.render/no-entity (:seon.error/type (r/page [main branch] 99999999)))))))

(defn- proc-state
  "The render proc's state as it holds a read of main and of a3's branch: root's view (a3 busy;
  root's boot, b1, is its entity's on main), main's open problems."
  [main branch]
  {:rid 1 :pending {} :watch #{:root :a3} :waiting {} :kf-wanted #{} :new-events [] :events [] :views {} :branches {:a3 {:db branch}}
   :merges 0 :main-db main :recorded (seon.problems/health main) :view {:agents {:a3 {:status :busy :branch :task-demo-f}}}})

(deftest root-and-an-agent-are-one-page-function
  (let [[main branch eid] (paged-dbs)
        st (proc-state main branch)
        root (#'p/page-value st :root)
        agent (#'p/page-value st :a3)
        tiles (fn [l] (for [u (:seon.render/units l) :when (= :seon.render/page-tile (:schema u))] (get-in u [:input :seon.render/schema])))]
    (testing "both are seon.render.ui/page's layout of an entity's page: the same units by the same schemas, but for their input"
      (is (= (disj (set (map :schema (:seon.render/units root))) :seon.render/root-input)
             (disj (set (map :schema (:seon.render/units agent))) :seon.render/agent-input)))
      (is (= [[:seon.db/eval-fact]] (rows-of root)) "root's list: its own evaluations")
      (is (= "p-head" (:id (first (:seon.render/units root))) (:id (first (:seon.render/units agent))))))
    (testing "root's focus: its agents as tiles, main's open problems a tile by their own schema, its input spawns"
      (is (= [:seon.db/agent-fact :seon.problems/report] (tiles root)))
      (is (= :seon.render/root-input (:schema (second (:seon.render/units root))))))
    (testing "an agent's focus: its REPL and messages at the top, live while root's view holds it in this boot"
      (let [in (second (:seon.render/units agent))]
        (is (= [:seon.render/agent-input {:id :a3 :live? true :held nil}] [(:schema in) (:input in)]))))
    (testing "a location is an entity: /agent/a3 and /walk/<its eid> are one page (each view reads the branch for itself)"
      (let [by-eid (#'p/page-value (assoc-in st [:branches eid :db] branch) eid)
            same #(for [u (:seon.render/units %) :when (not= :seon.render/page-more (:schema u))] (:input u))]
        (is (= (same agent) (same by-eid)))))
    (testing "a location naming no entity is that error, never an empty page; a branch that could not be read is shown as its error"
      (is (= :seon.render/no-entity (:seon.error/type (#'p/page-value st 99999999))))
      (let [err (seon.contract/error :seon.writer/refused 'seon.writer/writer-proc "no branch :task-demo-f" {})
            l (#'p/page-value (-> st (update-in [:branches :a3] dissoc :db) (assoc-in [:branches :a3 :error] err)) :a3)]
        (is (some #(= err (get-in % [:input :seon.render/value])) (:seon.render/units l)))
        (is (empty? (rows-of (assoc l :seon.render/units (remove #(= :seon.render/page-row (:schema %)) (:seon.render/units l))))))))
    (testing "an agent of an earlier boot is not live (root's boot is its entity's on main): its id names no process of this one"
      (let [st2 (assoc st :main-db (d/db-with main [{:seon.root/id :root :seon.root/boot "b2"}]))]
        (is (false? (:live? (:input (second (:seon.render/units (#'p/page-value st2 eid)))))))
        (let [e (#'p/page-value st2 :a3)]
          (is (= :seon.render/no-location (:seon.error/type e)) "and its id in this boot names no entity: the page says so, never nothing")
          (is (str/includes? (:seon.error/message e) "no agent a3 in root's boot b2"))
          (testing "the pass answers it as that page"
            (is (str/includes? (p/page-html (first (#'p/pass-view st2 :a3)) :a3) "no agent a3 in root's boot b2"))))))))

(deftest a-run-is-part-of-its-agents-page
  (let [[main branch eid] (paged-dbs)
        w (r/page [main branch] eid)
        attr (fn [nd] (get-in nd [:seon.render/from :seon.render/attr]))]
    (testing "the agent's schema names its runs as part of its page: each run's units join it"
      (is (= [:seon.run/_agent] (:seon.render/parts (seon.contract/properties :seon.db/agent-fact))))
      (is (= #{:seon.run/task :seon.turn/_run :seon.message/_to}
             (set (filter #{:seon.run/task :seon.turn/_run :seon.message/_to} (map attr (:seon.render/nodes w)))))))
    (testing "a part's way back to the agent is not shown again"
      (is (= 1 (count (filter #(= eid (:seon.render/eid %)) (cons (:seon.render/root w) (:seon.render/nodes w)))))))
    (testing "the run's prose cites into the agent page's one numbered list"
      (is (= ["seon.render/walk" "seon.print" "docs/render.md#no-clipping"] (mapv :seon.render/address (:seon.render/refs w)))))
    (testing "no separate run window: a location names root, an agent of this boot, or an entity"
      (is (= [:root :root :a3 474] (mapv #'seon.web/view-of ["" "root" "a3" "474"]))))))

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
    (testing "the agent changed on main (an event's commit): its head re-renders, nothing else"
      (let [[_ p] (pass (assoc st1 :main-db (d/db-with main (seon.task/agent-tx "b1" :a3 {:status :idle}))))]
        (is (= #{"p-head"} (:frags (patch-ids p))))
        (is (empty? (:prepends p)))))
    (testing "a new evaluation on its branch: one new row, prepended above the rows the page holds; the others untouched"
      (let [k (seon.task/agent-key "b1" :a3)
            branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "a3-77-3" [{:id 1 :tag :ret :form "(h)" :printed "1"}] nil))
            [_ p] (pass (assoc-in st1 [:branches :a3 :db] branch'))]
        (is (empty? (:frags p)) "no held unit changed its input")
        (is (= 1 (count (:prepends p))))
        (is (= "#p-rows" (ffirst (:prepends p))))
        (is (str/includes? (second (first (:prepends p))) "(h)"))))
    (testing "only the rows a tab scrolled to are rendered: the rest are counted, and the next come when its end scrolls into view"
      (let [st (assoc-in st [:shown :a3] 1)
            [st1 p] (pass st)
            rows (fn [st] (count (filter #(str/starts-with? % "r-") (keys (get-in st [:views :a3 :units])))))]
        (is (= 1 (rows st1)))
        (is (str/includes? (get-in st1 [:views :a3 :units "p-more-1" :html]) "2 older rows, rendered as this line scrolls into view"))
        (is (str/includes? (get-in st1 [:views :a3 :units "p-more-1" :html]) "data-on-intersect=\"@post('/more?view=a3')\""))
        (let [[st2 out] (#'p/render-proc (assoc st1 :shown {:a3 1}) :from-web {:op :more :view :a3})
              p (first (filter #(= :patch (:op %)) (:to-web out)))]
          (is (= 21 (get-in st2 [:shown :a3])) "one more page of rows (:seon.config.render.list/history)")
          (is (= 3 (rows st2)))
          (is (= ["#p-rows" "#p-rows" "#p-foot"] (mapv first (:appends p))) "the older rows after what the list holds, then the list's new end")
          (is (= ["p-more-1"] (:removes p)) "the old end goes: a new element, watched anew"))))
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

(deftest a-walk-is-data-a-model-reads
  ;; the owner, 2026-10-23: what a model reads is data, improved at its producer. The walk as one
  ;; value: the entity's relevant attributes, each unit under the attribute that reached it, what
  ;; its prose cites as data under :seon.render/refs; no prose, no printer artifact
  (let [w {:seon.render/root {:seon.render/kind :entity :seon.render/eid 1 :seon.render/value {:seon.run/id "demo/f#1" :seon.run/status :running}}
           :seon.render/nodes [{:seon.render/kind :entity :seon.render/eid 2 :seon.render/value {:seon.task/id "demo/f" :seon.task/plan "Make `demo/f` triple."}
                                :seon.render/from {:seon.render/eid 1 :seon.render/attr :seon.run/task}}
                               {:seon.render/kind :entity :seon.render/eid 3 :seon.render/value {:seon.turn/id "demo/f#1/0"}
                                :seon.render/from {:seon.render/eid 1 :seon.render/attr :seon.turn/_run}}]
           :seon.render/refs [{:seon.render/n 1 :seon.render/kind :var :seon.render/address "demo/f" :seon.render/depth :summary
                               :seon.render/cited-at [{:seon.render/eid 2 :seon.render/attr :seon.task/plan}]
                               :seon.render/value {:symbol 'demo/f :arglists '([x]) :summary "Doubles."}}]}
        d (r/data w)]
    (is (= {:seon.run/id "demo/f#1" :seon.run/status :running
            :seon.run/task {:seon.task/id "demo/f" :seon.task/plan "Make `demo/f` triple."}
            :seon.turn/_run [{:seon.turn/id "demo/f#1/0"}]
            :seon.render/refs [{:symbol 'demo/f :arglists '([x]) :summary "Doubles." :seon.render/n 1 :seon.render/address "demo/f"}]}
           d))
    (is (not (str/includes? (r/ai d {}) ";;")) "no comment lines: data only")))

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
