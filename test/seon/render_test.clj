(ns seon.render-test
  "Renders (session W2): selection (override key, schema-declared, printer), root's context,
  and the render proc's step as a plain function: inputs before render, bytes before
  send, a new agent appended, a retired one removed, keyframes composed from what was
  retained. Run: bin/test seon.render-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str] [clojure.core.async :as a]
            [seon.render :as r] [seon.render.ui :as ui] [seon.render.proc :as p]
            [seon.web.html :as h] [clojure.walk] [seon.print.select] [seon.config] [seon.docs] [seon.print] [seon.db] [seon.reconcile] [seon.task] [seon.edn] [seon.context] [seon.contract] [seon.repl] [datahike.api :as d]
            [seon.fx] [seon.source] [seon.program] [seon.web] [seon.template] [seon.context-test] [seon.render-cost-test] [seon.turn] [seon.model] [seon.problems]))

(def ^:private task
  {:seon.task/id "calc/add" :seon.task/ns 'calc :seon.task/name "add" :seon.task/plan "Make `app.calc/add` pass"
   :seon.task/execution :running :seon.task/branch :task-calc-add :seon.task/proofs #{'app.proofs/add-test}
   :seon.task/needs #{} :seon.task/kind :work
   :seon.task/accumulated {:plan "Make `app.calc/add` pass" :proofs #{'app.proofs/add-test}}})

(deftest selection
  (testing "the value's own override key wins: data as is"
    (is (= [:b "mine"] (r/html {:schema :seon.task/task} {:a 1 :seon.render/html [:b "mine"]}))))
  (testing "the schema's declared render, named by the producer"
    (is (= 'seon.render.ui/task-box (r/declared :seon.task/task)))
    (is (str/includes? (h/html (r/html {:schema :seon.task/task} task)) "task · calc/add")))
  (testing "a pulled entity's schema is the one its identity attribute names"
    (is (= :seon.run/run (r/schema-of {:seon.run/id "x#1"} nil)))
    (is (str/includes? (h/html (r/html {} {:seon.run/id "calc/add#1" :seon.run/agent :a3 :seon.run/status :running}))
                       "<td><code>calc/add#1</code></td>")))
  (testing "no declaration: the one value printer's hiccup"
    (let [out (r/html {} {:k [1 2 3]})]
      (is (= "seon-data-panel" (get-in out [1 :class])))))
  (testing "one face: the page and the model read the same hiccup; no :ai face is left"
    (is (vector? (r/html {} {:k 1})))
    (is (nil? (resolve 'seon.render/ai)))
    (is (not-any? #(contains? (seon.contract/properties %) :seon.render/ai) (keys seon.contract/forms)) "no schema declares one")))

(deftest a-render-is-opts-then-children
  ;; Reagent's form (notes/archive/render-design-2026-10-26.md, piece 6): a render takes its opts first and its
  ;; children after, and a call in hiccup is written [f opts & children]
  (testing "every render a schema declares takes [opts v]: its contract's first argument is :seon.render/opts"
    (let [declared (distinct (keep #(r/declared %) (keys seon.contract/forms)))
          first-arg (fn [sym] (let [s (:malli/schema (meta (requiring-resolve sym)))
                                    arity (if (= :function (first s)) (second s) s)]
                                (second (second arity))))]
      (is (seq declared))
      ;; :seon.render/opts, or opts it narrows ([:and :seon.render/opts [:map ...]]: render-ctx's renders)
      (is (= [] (vec (remove #(let [a (first-arg %)] (or (= :seon.render/opts a) (and (vector? a) (= [:and :seon.render/opts] (subvec a 0 2))))) declared)))
          "none takes its value first")))
  (testing "a nested value's render is a call [f opts v], which the writer expands"
    (let [out (r/print-html {} {:k {:seon.run/id "calc/add#1" :seon.run/status :running}})
          call (some #(when (and (vector? %) (not (keyword? (first %))) (fn? (first %))) %) (tree-seq coll? seq out))]
      (is (= {:as :inline} (second call)) "opts second")
      (is (= "calc/add#1" (:seon.run/id (nth call 2))) "the value its child"))
    (is (str/includes? (h/html [:div [r/html {:schema :seon.task/task} task]]) "task · calc/add") "[f opts v] in hiccup")))

(deftest compose-fills-slots-recursively
  (is (= "<a><b>x</b></a>" (p/compose "<a><!--s:b--></a>" {"b" {:html "<b><!--s:c--></b>"} "c" {:html "x"}}))))

;; ---- root's process, and no clipping ---------------------------------------------------------

(deftest root-process-is-root-own-data
  ;; root's ruling, 2026-10-27: the writer's, fork server's and store's numbers, each declared proc's
  ;; defects and the events are root's own data, one value (:seon.render/process) the render proc hands
  ;; to root's ctx; health is what flows, no proc is pinged
  (let [st {:view {:writer {:recent [] :batch-ms [2 4 6]} :fork-server {:status :up :pid 7} :store {}
                   :stats {:commits 2 :batched-tx 6 :lag-ms [3] :fork-ms [5]}
                   :root-errors [{:pid :render :message "boom"}] :root-errors-total 4}
            :system-p (doto (promise) (deliver {:procs {:api {} :render {}}}))
            :events [{:type :ready :agent :a1 :at-ms 1}] :agent-run {}}
        v (p/process-value st)]
    (is (= 4 (:root-errors-total v)) "how many defects, whole")
    (is (= {:render {:defects 1 :newest "boom"} :api {:defects 0}} (:health v)) "each declared proc's defects by pid")
    (is (= 2 (get-in v [:writer :commits])))
    (is (= 3.0 (get-in v [:writer :tx-per-commit])) "a commit takes every queued transaction")
    (is (not-any? #{:recent :batch-ms} (keys (:writer v))) "the windows themselves dropped")
    (is (= {:status :up :pid 7} (select-keys (:fork-server v) [:status :pid])))
    (is (= [:ready] (mapv :type (:events v))))))

;; ---- the GUARDS audit's names for the same law (seon.render/no-clip-in-renders) -------------

(deftest no-render-calls-clip
  (is (nil? (resolve 'seon.render.ui/clip)))
  (is (nil? (resolve 'seon.render.ui/line))))

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
  (let [text (seon.print/text behind)]
    (testing "as a REPL answers it, by the one printer: its message, fix, variant and its own keys"
      (is (str/includes? text "main changed 3 key(s)"))
      (is (str/includes? text "A merge task merges main into the branch (my.branch/merge-main!)"))
      (is (str/includes? text ":seon.merge/behind"))
      (is (str/includes? text ":behind 3"))))
  (testing "the page shows the same facts"
    (let [html (h/html (r/html {} behind))]
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
  (testing "the page's task box shows the failure too"
    (let [html (h/html (r/html {:schema :seon.task/task} failed-task))]
      (is (str/includes? html "failed in calc/add#2: add cannot be total over longs")))))

(defn- row
  "A problem row as seon.problems/report makes it: every row's members, then its class's."
  [m] (merge {:producer nil :owner-ns 'calc :evidence {} :proofs [] :answered-by [] :at-ms 1761170000000} m))

(deftest the-problem-report-reads-by-class
  ;; the whole report on root's page: by class, each row by its render
  (let [rep (with-meta {:t 90 :classes {:failed {:n 1 :rows [(row {:task "calc/add" :reason "not total" :open? true})]}
                                        :merge {:n 1 :rows [(row {:task "calc/sub" :run "calc/sub#1" :error behind :open? false})]}
                                        :red {:n 0 :rows []}}}
              {:seon.render/schema :seon.problems/report})
        html (h/html (r/html {} rep))]
    (is (str/includes? html "not total"))
    (is (str/includes? html "calc/sub#1"))))

(deftest every-identity-attribute-is-named-by-one-entity-schema
  ;; the schema of an entity is the one its identity attribute names (seon.render/entity-schema)
  (let [attrs #{:seon.agent/key :seon.run/id :seon.task/id :seon.root/id :seon.turn/id :seon.message/id
                :seon.run/agent :seon.turn/run :seon.message/to :seon.agent/root :seon.eval/agent :seon.run/task}
        named (frequencies (for [[k form] seon.contract/forms :when (:seon.db/attributes (seon.contract/properties k))
                                 [a] (filter vector? (rest form)) :when (attrs a)]
                             a))]
    (is (= attrs (set (keys named))) "each is named by an entity schema")
    (is (every? #{1} (vals named)) (pr-str named))))

(deftest an-eval-and-a-turn-say-what-they-cost
  ;; an eval's cost as recorded, each number with its unit; a turn's header says which of how many
  (let [ev (h/html (ui/evaluation {} {:seon.eval/record {:code "(f 2)" :prose "f doubles?"
                                                         :response {:handle 'result/e1 :value 4 :ms 1.5 :bytes 2048 :ticks 120}}
                                      :seon.eval/by "a3"}))
        turn (h/html (ui/turn {} {:seon.turn/n 0 :seon.turn/tokens 1200 :seon.turn/basis {:max-turns 8}}))]
    (is (str/includes? ev "f doubles?") "the prose beside the call")
    (is (str/includes? ev "1.5 ms · <span>2.0 KB</span>") "its cost as recorded, each number with its unit")
    (is (str/includes? (h/html (#'ui/cost-line {:ticks 1200 :ms 1.5})) "1,200 ticks · 1.5 ms") "ticks too, when recorded; nothing not recorded")
    (is (str/includes? turn "turn 1 of 8"))
    (is (str/includes? turn "1,200 tokens"))))

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
  (let [idx (seon.program/reach-index (src-program) {:env "e" :tree "t" :config {}}
                                      {"agent" '[seon.render.ui/agent] "root" '[seon.render.ui/root] "root-ctx" '[seon.render.ui/root-ctx] "render-ctx" '[seon.render.ui/render-ctx]})]
    (is (= {"agent" false "root" false "root-ctx" false "render-ctx" false} (update-vals idx :process?))
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
  ;; schema declares a render shows as that render, one line, in its place: a call of its render
  ;; in the hiccup (expanded by the serializer); the printed value itself is the printer's, never
  ;; its own render again
  (let [a {:seon.run/id "calc/square#1" :seon.run/agent :a4 :seon.run/status :done :seon.run/outcome :green}
        v {:newest a :count 3}]
    (let [html (h/html (r/html {} v))]
      (is (str/includes? html "<tr class=\"at s-done o-green\">") "the hiccup: the run's own render")
      (is (str/includes? html "seon-print-keyword\">:count") "around it, the printer's map"))
    (is (str/includes? (h/html (r/html {} a)) "calc/square#1") "the value itself by its render, as before")))

;; ---- the pages: a page is its parts (task seon.render/every-page-is-its-parts) ----

(def ^:private walked-plan
  "A plan citing a var (one backtick: its doc), a namespace and a wiki section (a link)."
  "Make `seon.render/html` answer the tree; its printer is `seon.print`, and see [no clipping](docs/render.md#no-clipping).")

(defn- eid-of [db ref] (:db/id (d/entity db ref)))

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
                         :seon.task/branch :task-demo-f :seon.task/kind :work :seon.task/proofs #{'demo/f-test}
                         ;; the names its plan cites, as the task writer derives them (seon.task/references-of)
                         :seon.task/references (set (seon.ref/names walked-plan))}]
                       (seon.task/agent-tx "b1" :a3 {:status :idle :pid 77 :branch :task-demo-f})
                       (seon.task/run-start-tx "demo/f#1" "demo/f" "b1" :a3 {:branch :task-demo-f :model "m" :max-turns 8} 0 "p")
                       (seon.task/message-tx "m-1" :message "owner" "demo/f#1" "check g")
                       ;; root's own evaluation, from its REPL
                       (seon.repl/record-tx {:seon.root/id :root} "root-1" "outside" {:code "(my.root/problems sys)" :response {:handle 'result/e1 :value {}}})])
        branch (reduce d/db-with main0
                       [(seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e1" "a3"
                                             ;; its prose's citations as the record holds them (seon.repl/record-details):
                                             ;; what the page shows is read from here, never parsed from the prose again
                                             {:code "(f 2)" :prose "f doubles?" :run "demo/f#1"
                                              :cites (seon.ref/references main0 "`seon.docs/source-of`")
                                              :response {:handle 'result/e1 :value 4 :ms 1.5 :bytes 2048 :ticks 120}})
                        (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e2" "a3"
                                             {:code "(f 3)" :response {:handle 'result/e2 :value 6 :ms 0.4 :bytes 512 :ticks 40}})
                        [{:db/id "run" :seon.run/id "demo/f#1"}
                         {:seon.turn/id "demo/f#1/0" :seon.turn/run "run" :seon.turn/n 0 :seon.turn/origin :model :seon.turn/reply "it doubles"
                          :seon.turn/forms 2 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 5 :seon.turn/tokens 1200
                          :seon.turn/basis {:commit #uuid "00000000-0000-0000-0000-000000000000" :task "demo/f" :run "demo/f#1" :turn 0 :max-turns 8 :origin :model :observed {}}
                          :seon.turn/calls [{:id "c0" :code "(f 2)" :eval "e1"} {:id "c1" :code "(f 3)" :eval "e2"}]}]
                        (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e3" "user"
                                             {:code "(g)" :text "{:handle result/e3, :ex {:seon.error/message \"boom\"}}"
                                              :response {:handle 'result/e3 :ex {:seon.error/type :seon.agent/eval-error :seon.error/operation 'seon.repl/eval-code :seon.error/message "boom"}}})])
        main (d/db-with main0 (seon.task/agent-tx "b1" :a3 {:status :busy}))]
    [main branch (eid-of main [:seon.agent/key k])]))

(defn- proc-state
  "The render proc's state as it holds a read of main and of a3's branch: root's view (a3 busy;
  root's boot, b1, is its entity's on main), main's open problems."
  [main branch]
  {:rid 1 :pending {} :watch #{:root :a3} :waiting {} :kf-wanted #{} :new-events [] :events [] :views {} :branches {:a3 {:db branch :branch :task-demo-f}}
   ;; the one read of a3's branch every view of it shows (seon.render.proc/ask-branches)
   :branch-reads {:task-demo-f {:db branch}}
   :merges 0 :main-db main :view {:agents {:a3 {:status :busy :branch :task-demo-f}}}})

(defn- branch-state
  "proc-state with a3's branch read named (its branch, :task-demo-f), as ask-branches records it."
  [main branch]
  (assoc-in (proc-state main branch) [:branches :a3 :read] [:task-demo-f]))

(defn- root-state
  "St with root's walk current as of main (seon.render.proc/advance-main: root's ctx built at main's
  first read, its page the walk's units, root's agents list among them)."
  [st main]
  (#'p/advance-main (dissoc st :main-db) (d/db-with main [{:seon.root/id :root :seon.config.agent/max-running 4}])))

(deftest root-context-is-its-page-printed-at-the-budget
  ;; what root reads is its page, the render of its ctx (each component's hiccup by its id), printed once
  ;; by the one printer at :seon.config.render/root-context-budget; the cut is the printer's alone
  (let [[main branch _] (paged-dbs)
        st (-> (proc-state main branch) (assoc :watch #{}) (root-state main))
        st1 (#'p/pass st (volatile! {}))
        ctx (:context st1)
        budget (seon.config/value :seon.config.render/root-context-budget)]
    (testing "what is wrong comes first (its tiles: its process, main's problems and runs), at the printer's budget"
      (is (str/starts-with? (:text ctx) "{\"process-tile\""))
      (is (str/includes? (:text ctx) "root's process"))
      (is (<= (:tokens ctx) budget))
      (is (= (:tokens ctx) (seon.print.select/estimate (:text ctx)))))
    (testing "no clock: an unchanged system gives the same text"
      (is (not (re-find #"\d\d:\d\d:\d\d" (:text ctx))))
      (is (= ctx (:context (#'p/pass st1 (volatile! {}))))))
    (r/dispose-walk! (get-in st1 [:root-walk :w]))))

(defn- a3-walk
  "a3's page walked once from branch (seon.render/draw of its ctx by the layout its schema declares)."
  [branch]
  (r/draw (:tree (seon.ctx/build branch (seon.db/eid branch :seon.agent/key (seon.task/agent-key "b1" :a3)) :task-demo-f))
          seon.ctx/changes branch (requiring-resolve (r/declared :seon.render/render-ctx))))

(defn- drawn-parts
  "a3's page's units as its process draws them (seon.repl/render-ctx), drawn here from branch: what the
  render proc's {:op :render} ask is answered with."
  [branch]
  (let [w (a3-walk branch)] (try {:units (r/placed-units w)} (finally (r/dispose-walk! w)))))

(deftest the-problems-tile-follows-main-without-reading-it-whole
  ;; task seon.problems/health-at-the-cost-of-what-changed: root's walk holds main's health; a move of
  ;; main changes it by the datoms that moved (seon.render.proc/health!), and the tile draws what it holds
  (let [[main branch _] (paged-dbs)
        st (root-state (proc-state main branch) main)
        tile (fn [st] (:html (second (filter #(= "#p-tiles" (:container %)) (:seon.render/units (#'p/page-value st :root))))))
        main2 (datahike.api/db-with (:main-db st) [{:seon.task/id "zz/dead" :seon.task/ns 'zz :seon.task/name "dead" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :b :seon.task/kind :work}
                               {:seon.run/id "zz/dead#1" :seon.run/task [:seon.task/id "zz/dead"] :seon.run/boot "b" :seon.run/agent {:seon.agent/key "b/zz" :seon.agent/id :zz}
                                :seon.run/start-ms 1 :seon.run/end-ms 2 :seon.run/branch :b :seon.run/max-turns 2 :seon.run/status :dead :seon.run/reason "its agent died"
                                :seon.run/error (seon.contract/error :seon.agent/died 'seon.root/end-agent "agent zz died" {:agent :zz})}])
        before (tile st)
        st2 (#'p/advance-main st main2)]
    (is (not (str/includes? before "zz/dead")) "main holds no such run yet")
    (is (str/includes? (tile st2) "zz/dead") "the move shows its dead run in the tile")
    (is (= (:classes (seon.problems/health main2)) (:classes (:health @(get-in st2 [:root-walk :a])))) "the held health is health of the new main")
    (r/dispose-walk! (get-in st2 [:root-walk :w]))))

(deftest an-agents-page-draws-the-problems-of-its-own-run
  ;; task seon.problems/health-in-context: its run's open problems, by the report's render, beside the
  ;; run; none when none is open; another run's and main's classes are not drawn
  (let [[_ branch _] (paged-dbs)
        html (fn [db] (apply str (map :html (:units (drawn-parts db)))))
        red (datahike.api/db-with branch [{:seon.run/id "demo/f#1" :seon.run/failures {'demo/f-test [{:type :fail :message "m" :expected "1" :actual "2"}]}}])]
    (is (not (str/includes? (html branch) "red")) "no problem open: nothing drawn")
    (is (str/includes? (html red) "red") "a red proof of its task, drawn as the report draws it")))

(deftest root-and-an-agent-are-one-chrome
  (let [[main branch eid] (paged-dbs)
        st (root-state (proc-state main branch) main)
        root (#'p/page-value st :root)
        agent (#'p/page-value st :a3)
        in (fn [l c] (filter #(= c (:container %)) (:seon.render/units l)))
        input (fn [l] (first (filter #(= "p-input" (:id %)) (:seon.render/units l))))]
    (testing "root is an agent: its page and an agent's are each its lists in one chrome (seon.render.ui/page-chrome)"
      (is (= #{nil "#c-side" "#p-head" "#c-rows" "#p-tiles"} (set (map :container (:seon.render/units root)))))
      (is (= #{nil "#c-side" "#p-head" "#p-task" "#c-rows"} (set (map :container (:seon.render/units agent))))
          "an agent's: its task's list above its conversation; no eval of the fixture has a render of its own, so no tiles")
      (is (= 1 (count (in root "#c-rows"))) "root's list: its own evaluation"))
    (testing "root's agents list is the same on every page: root first, then its agents"
      (is (= 2 (count (in root "#c-side")) (count (in agent "#c-side"))))
      (is (= (map :html (in root "#c-side")) (map :html (in agent "#c-side"))))
      (is (str/includes? (:html (first (in root "#c-side"))) "href=\"/\"") "root's line links to its page")
      (is (str/includes? (:html (second (in root "#c-side"))) "href=\"/agent/a3\"") "a link to its page"))
    (testing "root's focus: its process, main's open problems and runs by their own renders, its input spawns"
      (is (= 3 (count (in root "#p-tiles"))))
      (is (str/includes? (:html (first (in root "#p-tiles"))) "process"))
      (is (str/includes? (:html (second (in root "#p-tiles"))) "problems"))
      (is (str/includes? (:html (nth (in root "#p-tiles") 2)) "runs"))
      (is (= :seon.render/root-input (:schema (input root)))))
    (testing "an agent's focus: its REPL and messages at the top, live while root's view holds it in this boot"
      (is (= [:seon.render/agent-input {:id :a3 :live? true :held nil}] [(:schema (input agent)) (:input (input agent))])))
    (testing "a location naming no entity is that error, never an empty page; a branch that could not be read is shown as its error"
      (is (= :seon.render/no-location (:seon.error/type (#'p/page-value st 99999999))))
      (let [err (seon.contract/error :seon.writer/refused 'seon.writer/writer-proc "no branch :task-demo-f" {})
            l (#'p/page-value (-> st (update-in [:branches :a3] dissoc :db) (assoc-in [:branches :a3 :error] err)) :a3)]
        (is (some #(str/includes? (str (:html %)) "no branch :task-demo-f") (:seon.render/units l)) "its error, by the error's render")
        (is (empty? (in l "#c-rows")) "no branch read: none of its rows")))
    (testing "an agent of an earlier boot is not live (root's boot is its entity's on main): its id names no process of this one"
      (let [st2 (assoc st :main-db (d/db-with (:main-db st) [{:seon.root/id :root :seon.root/boot "b2"}]))]
        (is (false? (:live? (:input (input (#'p/page-value st2 eid))))))
        (let [e (#'p/page-value st2 :a3)]
          (is (= :seon.render/no-location (:seon.error/type e)) "and its id in this boot names no entity: the page says so, never nothing")
          (is (str/includes? (:seon.error/message e) "no agent a3 in root's boot b2"))
          (testing "the pass answers it as that page"
            (is (str/includes? (p/view-html (first (#'p/pass-view st2 :a3)) :a3) "no agent a3 in root's boot b2"))))))
    (r/dispose-walk! (get-in st [:root-walk :w]))))

(deftest a-location-names-root-an-agent-or-an-entity
  (is (= [:root :root :a3 474] (mapv #'seon.web/view-of ["" "root" "a3" "474"]))))

(deftest a-page-keeps-what-it-read
  ;; task seon.render/a-view-renders-once-per-change-of-its-reads (B): a page's walk runs inside
  ;; d/with-reads and the proc indexes its reads by key, so a transaction finds the pages it
  ;; touched by its datoms' keys (seon.render.proc/touched), never by walking them
  (let [[main branch eid] (paged-dbs)
        [st _] (#'p/pass-view (proc-state main branch) :a3)
        e1 (:db/id (d/entity branch [:seon.eval/id "e1"]))
        touched (fn [db tx-data] (#'p/touched st (:tx-data (d/with db tx-data))))
        ;; an entity of main the page did not read, a form of the example program (ids from separate
        ;; speculative d/with calls can repeat in a test; on a store they are issued once); not a
        ;; namespace: related reads every namespace's file (seon.program/program-nss)
        unread (first (remove #(contains? (get-in st [:views :a3 :reads]) [:e %]) (d/q '[:find [?e ...] :where [?e :form/id]] main)))]
    (is (some? unread) "a form the page did not read")
    (is (seq (get-in st [:views :a3 :reads])) "the page holds what it read")
    (is (= #{:a3} (touched main [{:db/id eid :seon.agent/status :idle}])) "the agent's own datom")
    (is (empty? (touched branch [{:db/id e1 :seon.eval/by "user"}]))
        "a branch's entity is drawn outside the read set: a move of the branch marks the page when root reads it (take-read; a-new-evaluation-is-one-append)")
    (is (empty? (touched main [[:db/retractEntity unread]])) "an entity the page did not read")))

(deftest a-transaction-touching-no-page-walks-nothing
  ;; task seon.render/a-view-renders-once-per-change-of-its-reads (C): main's commits come from
  ;; the writer ({:t :ops :db}); the pass walks only the pages whose reads a commit's datoms touch, and
  ;; root's, which its walk marks (root-walk) when main moved
  (let [[main0 branch eid] (paged-dbs)
        o (volatile! {})
        st (#'p/pass (root-state (proc-state main0 branch) main0) o)
        main (:main-db st)
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
        (is (empty? (#'p/touched st' [{:e eid :a :seon.agent/status :v :idle}])) "root's page reads no datom: its walk marks it")
        (is (not (some (fn [[_ vs]] (contains? vs :a3)) (:read-index st'))))))
    (r/dispose-walk! (get-in st [:root-walk :w]))))

(deftest a-branch-commit-touches-its-page-by-its-datoms-diff
  ;; an agent's commit: its branch read again, and what moved is the datoms-diff of the held and
  ;; the new branch db; the page walks only when the diff touches its reads
  (let [[main branch _] (paged-dbs)
        main (d/db-with main [{:seon.root/id :root :seon.config.agent/max-running 4}])
        o (volatile! {})
        st (#'p/pass (proc-state main branch) o)
        k (seon.task/agent-key "b1" :a3)
        branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e4" "a3" {:code "(h)" :response {:handle 'result/e4 :value 1}}))
        answer (fn [st db] (#'p/answered (assoc-in st [:pending 9] [:branch :task-demo-f]) o [:branch :task-demo-f] {:db db}))]
    (is (not (some #{:a3} (:last-walked (answer st branch)))) "the same branch db: nothing moved, nothing walked")
    (is (some #{:a3} (:last-walked (answer st branch'))) "a new evaluation on it: the page walks")))

(deftest a-new-evaluation-is-one-append
  ;; an agent's commit on its branch: what moved (the datoms-diff) touches the list, and the new
  ;; evaluation renders once, sent as one row above the others
  (let [[main branch _] (paged-dbs)
        o (volatile! {})
        ;; one tab, on a3's page
        st (assoc (branch-state main branch) :watch #{:a3})
        [st1 _] (#'p/pass-view st :a3)
        k (seon.task/agent-key "b1" :a3)
        branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e4" "a3" {:code "(h)" :response {:handle 'result/e4 :value 1}}))
        st2 (#'p/answered st1 o [:branch :task-demo-f] {:db branch'})
        patch (first (filter #(and (= :patch (:op %)) (= :a3 (:view %))) (:to-web @o)))]
    (is (empty? (:frags patch)) "no held part changed")
    (is (= 1 (count (filter #(str/includes? (second %) "result/e4") (concat (:appends patch) (:afters patch))))) "one part, the new eval")
    (is (= 1 (count (concat (:appends patch) (:afters patch) (:prepends patch)))) "and nothing else sent")))

(deftest a-branch-read-anew-renders-its-components-again
  ;; a branch read after an error has nothing held to diff against: what was cached from that
  ;; branch is rendered again, so a change made while it could not be read is shown
  (let [[main branch _] (paged-dbs)
        o (volatile! {})
        st (assoc (branch-state main branch) :watch #{:a3})
        [st1 _] (#'p/pass-view st :a3)
        err (seon.contract/error :seon.writer/refused 'seon.writer/writer-proc "no branch :task-demo-f" {})
        st2 (#'p/answered st1 o [:branch :task-demo-f] err)
        branch' (d/db-with branch [{:seon.turn/id "demo/f#1/0" :seon.turn/tokens 4321}])
        st3 (#'p/answered st2 o [:branch :task-demo-f] {:db branch'})]
    (is (not (str/includes? (p/view-html st1 :a3) "4,321 tokens")))
    (is (str/includes? (p/view-html st3 :a3) "4,321 tokens") "the turn's tokens, written while its branch was unread")))

(deftest the-agent-page-is-its-conversation
  ;; task seon.render/a-page-is-its-entitys-view (4b; design section 5): an agent's page is its
  ;; conversation, oldest first: a turn's header, an eval's prose then its one collapsed line,
  ;; a message; the input under it
  (let [[main branch _] (paged-dbs)
        st (branch-state main branch)
        [st1 _] (#'p/pass-view st :a3)
        html (p/view-html st1 :a3)
        at #(str/index-of html %)]
    ;; the fixture writes the turn after its evals (seon.turn writes it as its request goes, before them)
    (is (< (at "check g") (at "f doubles?") (at "turn 1 of 8") (at "result/e3")) "in the order written: the message, an eval's prose, the turn, the owner's eval")
    (is (< (at "id=\"c-rows\"") (at "id=\"p-input\"")) "the input under the conversation")
    (is (re-find #"<details [^>]*class=\"seon-eval\"" html) "an eval is a block that opens")
    (is (re-find #"<details [^>]*id=\"eval-e1\"" html) "the row is its handle's anchor, open or not")
    (is (re-find #"<summary[^>]*>.*f doubles\?.*result/e1" html) "folded: its prose and its handle, never a copy of the eval")))

(deftest an-unchanged-unit-never-re-renders
  ;; each unit of a page is drawn only when its input changes (seon.render.proc/step-units): a commit on
  ;; the agent moves its head's input alone
  (let [[main branch _] (paged-dbs)
        [st1 _] (#'p/pass-view (branch-state main branch) :a3)
        made #(get-in % [:counts :rendered] 0)
        r (d/with main (seon.task/agent-tx "b1" :a3 {:status :idle}))
        [st2 patch] (#'p/pass-view (assoc st1 :main-db (:db-after r)) :a3)]
    (is (= 1 (- (made st2) (made st1))) "the head rendered again, nothing else")
    (is (= #{"p-agent-head"} (set (map first (:frags patch)))) "its fragment sent")
    (is (empty? (:prepends patch)))
    (testing "a pass with nothing touched renders nothing"
      (is (= (made st2) (made (first (#'p/pass-view st2 :a3))))))))

(deftest the-sidebar-is-roots-agents
  ;; the agents list is root's ctx, drawn by root's walk: root, then root's agents, each its own component,
  ;; the same on every page
  (let [[main branch _] (paged-dbs)
        st (root-state (branch-state main branch) main)
        [st1 _] (#'p/pass-view st :a3)
        side (fn [st] (filter #(= "#c-side" (:container %)) (vals (get-in st [:views :a3 :units]))))]
    (is (= 2 (count (side st1))) "root, and its one agent, a3")
    (is (str/includes? (p/view-html st1 :a3) "href=\"/\"") "root's line links to its page")
    (is (str/includes? (p/view-html st1 :a3) "href=\"/agent/a3\"") "a link to its page")
    (testing "a new agent of root's is a new line in the sidebar, its component; the others stay"
      (let [main' (d/db-with (:main-db st1) (seon.task/agent-tx "b1" :a4 {:status :busy}))
            st2 (#'p/advance-main st1 main')
            [st3 patch] (#'p/pass-view st2 :a3)]
        (is (= 3 (count (side st3))))
        (is (some #(str/includes? (second %) "href=\"/agent/a4\"") (concat (:appends patch) (:afters patch) (:prepends patch))) "its line is sent")
        (is (not-any? #(str/includes? (second %) "href=\"/agent/a3\"") (:frags patch)) "a3's line is not sent again")))
    (r/dispose-walk! (get-in st [:root-walk :w]))))

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

(deftest every-view-of-an-agent-shows-one-read-of-its-branch
  ;; the branch review (2026-10-25, F2): each view read the agent's branch on its own, at its own t,
  ;; and one still on an older read could refill the cache the newer one had invalidated. One read
  ;; per branch, shown by every view of the agent at once
  (let [[main branch _] (paged-dbs)
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

(deftest a-units-change-rerenders-that-unit
  (let [[main branch _] (paged-dbs)
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
            [_ p] (pass (assoc st1 :main-db (:db-after r)))]
        (is (= #{"p-agent-head"} (:frags (patch-ids p))) "its head")
        (is (empty? (:prepends p)))))
    (testing "a new evaluation on its branch: one new row, prepended above the rows the page holds; the others untouched"
      (let [k (seon.task/agent-key "b1" :a3)
            branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e4" "a3" {:code "(h)" :response {:handle 'result/e4 :value 1}}))
            [_ p] (pass (assoc-in st1 [:branches :a3 :db] branch'))]
        (is (empty? (:frags p)) "no held unit changed its input")
        (is (= 1 (count (filter #(str/includes? (second %) "result/e4") (concat (:appends p) (:afters p))))) "one part, placed after the one before it")))
    (testing "a page asked for before main is read waits for its first render, never answered empty"
      (let [o (volatile! {})
            st0 (-> st (dissoc :main-db) (assoc :waiting {7 :root}))
            st1 (#'p/pass st0 o)]
        (is (empty? (filter #(= :page (:op %)) (:to-web @o))))
        (is (= {7 :root} (:waiting st1)))
        (let [o (volatile! {})
              st2 (#'p/pass (root-state st1 main) o)
              page (first (filter #(= :page (:op %)) (:to-web @o)))]
          (is (= 7 (:k page)))
          (is (str/includes? (:html page) "id=\"p-head\""))
          (is (empty? (:waiting st2)))
          (r/dispose-walk! (get-in st2 [:root-walk :w]))))
      (testing "an agent's page waits for its branch read too: answered with the rows its branch records"
        (let [o (volatile! {})
              st1 (#'p/pass (-> st (update :branches dissoc :a3) (assoc :waiting {8 :a3})) o)]
          (is (empty? (filter #(= :page (:op %)) (:to-web @o))))
          (let [o (volatile! {})
                st2 (#'p/pass (assoc-in st1 [:branches :a3 :db] branch) o)
                page (first (filter #(= :page (:op %)) (:to-web @o)))]
            (is (str/includes? (:html page) "result/e1") "answered at once, drawn from its branch, never waiting on its process")
            (is (str/includes? (:html page) "its process has not answered yet") "and it says so")
            (is (some #(= {:op :render :id :a3} (get-in % [:req])) (:to-api @o)) "its process is asked for its parts")
            (let [o (volatile! {})
                  st3 (#'p/answered st2 o [:parts :a3] (drawn-parts branch))]
              (is (not (str/includes? (p/view-html st3 :a3) "has not answered yet")) "its own parts replace them")
              (is (some #(= "p-caption" (first %)) (mapcat :frags (filter #(= :patch (:op %)) (:to-web @o))))
                  "and the open tab is sent the line that said whose drawing it was"))))))
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
            ;; each row a unit whose input is [turn open?]; its data as its render reads it (seon.render.ui/debug-input)
            rows #(vec (for [u (:seon.render/units (#'p/page-value % [:debug :a1])) :when (= "#d-turns" (:container u))
                             :let [[e open?] (:input u)]]
                         {:id (:id u) :input (ui/debug-input (get-in % [:branches [:debug :a1] :db]) e open? (ui/view-param [:debug :a1]))}))
            units (rows st)
            ;; both rows opened, as a tab opening each row's <details> asks (/open)
            opened (rows (assoc-in st [:open [:debug :a1]] #{(str "d-" e0) (str "d-" e1)}))
            provider {:kind :openai-compat :base-url "https://api.example" :model "deepseek-flash" :api-key-env "NONE" :max-tokens 2048}
            body #(seon.model/request-body provider {:messages % :task "demo/f" :turn 1})
            messages (fn [parts] (vec (mapcat second parts)))
            [st' _] (#'p/pass-view st [:debug :a1])
            html (p/view-html st' [:debug :a1])]
        (is (= ["d-" "d-"] (mapv #(subs (:id %) 0 2) units)) "one row a model turn")
        (is (= [1 0] (mapv #(get-in % [:input :turn]) units)) "newest first")
        (is (= [:eval :page] (mapv ffirst (get-in units [0 :input :added]))) "turn 1 adds its call's eval and what changed of its page")
        (is (= {:turn 0 :parts 2} (get-in units [0 :input :same])) "and sends the help page and turn 0's page as turn 0 did")
        (is (nil? (get-in units [0 :input :whole])) "its whole request is not read back until it is opened")
        (is (= r1 (messages (get-in opened [0 :input :whole]))) "opened: turn 1's messages as it sent them")
        (is (= r0 (messages (get-in opened [1 :input :whole]))))
        (is (= (body r1) (body (messages (get-in opened [0 :input :whole])))) "the wire body the provider got, as data")
        (is (str/includes? html "eval e2") "each part beside the record that made it")
        (is (str/includes? html "the help page") "the system prompt's source, on the first turn's row")
        (is (str/includes? html "and 2 records as turn 1 sent them") "a later row names what it shares")
        (is (str/includes? html "I call f.") "the call's prose")
        (is (= [:debug :a1] (#'seon.web/view-of (java.net.URLDecoder/decode (ui/view-param [:debug :a1]) "UTF-8"))) "its feed names it, and reads it back")
        (is (= [:debug :a1] (#'seon.web/view-of "a1/debug")) "/agent/a1/debug")
        (testing "a turn whose call failed shows what it sent and its typed error"
          (let [db (d/db-with (get-in st [:branches [:debug :a1] :db])
                              [{:db/id e0 :seon.turn/error {:seon.error/type :seon.model/no-provider :seon.error/operation 'seon.model/complete
                                                            :seon.error/message "no model provider"}}])
                [st'' _] (#'p/pass-view (-> st (assoc :main-db db) (assoc-in [:branches [:debug :a1] :db] db)) [:debug :a1])
                html (p/view-html st'' [:debug :a1])]
            (is (str/includes? html "no model provider") "its error, by the error's render")
            (is (str/includes? html "the page whole") "beside what it sent")))))))

(deftest a-new-turn-is-one-new-row
  ;; a turn is written once: a pass after a new turn renders only its row
  (ts/test-branch!
    (fn [_ conn]
      (let [[st _] (debug-state conn)
            [st1 _] (#'p/pass-view st [:debug :a1])
            db (get-in st1 [:branches [:debug :a1] :db])
            b {:commit (d/commit-id db) :task "demo/f" :run "demo/f#1" :turn 2 :max-turns 4 :origin :model
               :observed {:results {} :test-run-errors [] :reds [] :reasons []} :agent "b/a1"}
            ;; as seon.turn goes: the turn recorded, then its request on the db that holds it
            db1 (d/db-with db [{:seon.turn/id "demo/f#1/2" :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/n 2 :seon.turn/origin :model
                                :seon.turn/basis b :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 1}])
            db' (d/db-with db1 [{:seon.turn/id "demo/f#1/2" :seon.turn/sent (:sent (seon.context/render db1 b))}])
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
  (let [flat (h/html (r/html {} {:demo/id "f" :demo/proofs ['demo-test/f-test]}))
        nested (h/html (r/html {} {:demo/id "f" :rows [{:a 1} {:a 2}]}))]
    (is (not (str/includes? flat "<details")) "a map of scalars and a vector of one symbol: no fold")
    (is (not (str/includes? flat "items, depth")))
    (is (str/includes? flat "demo-test/f-test") "the value is all there")
    (is (= 1 (count (re-seq #"<details" nested))) "only the vector of maps folds")
    (is (str/includes? nested "vector 2 items"))))

(deftest an-eval-collapses-to-its-reference
  ;; root, 2026-10-24: a collapsed eval is its code, its time and its handle (seon.render.ui/eval-item): never
  ;; a copy of its value or its cost line, which open under it
  (let [[main branch eid] (paged-dbs)
        k (:seon.agent/key (d/pull main [:seon.agent/key] eid))
        branch (d/db-with branch (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e9" "a3"
                                                      {:code "(+ 2 2)" :text "{:handle result/e9, :ms 0.2, :ticks 900, :value 4}" :line "4"
                                                       :response {:handle 'result/e9 :ms 0.2 :ticks 900}}))
        e9 (str "eval-item-" (seon.db/eid branch :seon.eval/id "e9"))
        closed (:html (some #(when (= e9 (:id %)) %) (:units (drawn-parts branch))))]
    (is (some? closed) "the eval, a component of the page")
    (is (str/includes? closed "(+ 2 2)") "its code's first line")
    (is (str/includes? closed "0.20 ms") closed)
    (is (str/includes? closed "result/e9") "its handle")
    (is (not (str/includes? closed ">4<")) "not its value: that opens under it")
    (is (not (str/includes? closed "ticks")) "and no cost line twice")))

(deftest roots-context-is-its-debug-page
  ;; root, 2026-10-26: root's page and an agent's both toggle to what their model reads; root has no
  ;; model turn, so its debug page is the context the render proc holds (its :context, the system's
  ;; value printed), shown whole, never computed again for the page
  (let [l (#'p/debug-value {:context {:text "{:seon-root \"the whole system\"}" :tokens 7}} [:debug :root])
        u (some #(when (= "d-context" (:id %)) %) (:seon.render/units l))]
    (is (= :seon.render/root-context (:schema u)))
    (is (str/includes? (h/html (r/html {:schema (:schema u) :unit "d-context"} (:input u))) "the whole system"))
    (is (str/includes? (h/html (:seon.render/html l)) "href=\"/\"") "the toggle back to its conversation")
    (is (nil? (#'p/debug-value {} [:debug :root])) "nothing held yet: no page")))

(deftest roots-page-reads-in-words
  ;; root, 2026-10-24: root is an agent; its head, problems and runs read as a person reads them,
  ;; each problem one line by its own :html view
  (let [row {:class :error :count 3 :task "demo/f" :run "demo/f#1" :message "boom" :answered-by ["demo/fix"]
             :producer 'demo/f :owner-ns 'demo :evidence {:kind :error} :proofs [] :at-ms nil}]
    (is (= "<p class=\"text-xs\">error ×3 demo/f demo/f#1: boom (answered by demo/fix)</p>" (h/html (ui/problem-row {} row))))
    (let [html (h/html (ui/problems {} {:t 1 :classes {:error {:n 1 :rows [(dissoc row :class)]} :red {:n 0 :rows []}}}))]
      (is (str/includes? html "<summary class=\"cursor-pointer\">error 1</summary>"))
      (is (str/includes? html "error ×3 demo/f") "its rows, each by the row's view")
      (is (not (str/includes? html ">red 0<")) "a class with no rows is left out"))
    (is (= ["none open"] (re-seq #"none open" (h/html (ui/problems {} {:t 1 :classes {}})))))
    (testing "on root's page a closed class renders no rows; the tab opens it (/open)"
      (let [r {:t 1 :classes {:error {:n 1 :rows [(dissoc row :class)]}}}]
        (is (not (str/includes? (h/html (ui/problems {} (assoc r :open #{}))) "boom")))
        (is (str/includes? (h/html (ui/problems {} (assoc r :open #{"pc-error"}))) "boom"))
        (is (str/includes? (h/html (ui/problems {} (assoc r :open #{}))) "unit=pc-error") "its toggle"))))
  (let [html (h/html (ui/runs {} {:by-status {:running 1 :closed 2} :running [{:seon.run/id "demo/f#1" :seon.run/status :running :seon.run/max-turns 8}]}))]
    (is (str/includes? html "2 closed · 1 running"))
    (is (str/includes? html "run demo/f#1 · running · up to 8 turns") "each running one by the run's view"))
  (let [html (h/html (ui/root {}
                             {:seon.root/id :root :seon.root/boot "b1" :seon.root/generation {:commit "abc" :ms 1}
                              :seon.root/quiescent {:t 9 :root-causes [{:task "demo/f" :cause "red proof" :blocks ["demo/g"]}]}}))]
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
        html (h/html (ui/evaluation {} {:seon.eval/record rec :seon.eval/by "outside"}))
        held (h/html (ui/evaluation {} {:seon.eval/record {:code "(Object.)" :printed "#object[java.lang.Object]" :response {:handle 'result/e2}}}))]
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
        html (h/html (r/html {} {:screen {:seon.render/html page} :n 1}))]
    (is (str/includes? html "<section><h2>agents</h2><ol><li>a1 busy</li></ol></section>") html)
    (is (str/includes? (:text (seon.print/print-value {:screen {:seon.render/html page} :n 1} {:render #'r/nested-html}))
                       "[:section [:h2 \"agents\"]")
        "a value's text names its hiccup as the data it is")))

(deftest a-cut-opens-its-path-in-the-row
  ;; the value view's step 3: a cut on the page is a button naming the form that reads its rest; the
  ;; row, given that form opened (/open), renders what it reads from the record, under the form as
  ;; the button that closes it, so it shows what the cut left
  (let [body (fn [n] (str/join "\n" (repeat 50 (str "  (is (= " n " (f " n ")))"))))
        proof (fn [n] {:test (symbol "demo-test" (str "t" n)) :source (seon.print/code (str "(deftest t" n "\n" (body n) ")"))})
        rec {:code "(my.task/status)" :response {:handle 'result/e5 :value {:proofs (mapv proof (range 1 5))}}}
        shown (fn [html] (count (filter #(str/includes? html (body %)) (range 1 5))))
        closed (h/html (ui/evaluation {} {:seon.eval/record rec}))
        path (some-> (re-find #"class=\"seon-print-cut\"[^>]*? data-seon-form=\"([^\"]*)\"" closed) second (str/replace "&amp;" "&"))
        opened (when path (h/html (ui/evaluation {:opened #{path}} {:seon.eval/record rec})))]
    (is path (str "four sources over the value's budget: a cut, a toggle naming the form that reads its rest: "
                  (shown closed) " shown; " (pr-str (re-seq #"seon-print-(?:cut|elision)[^<]{0,160}" closed))))
    (is (< (shown closed) 4) "closed, a source is left out")
    (is (> (shown opened) (shown closed)) "opened, what its form reads renders: what the cut left shows")
    (is (str/includes? closed "&amp;open=true") "the cut only opens: a re-render cannot post its close")
    (is (str/includes? opened "&amp;open=false") "the opened form is the button that closes it")))

(deftest every-sent-part-names-its-record
  ;; root, 2026-10-26: a [:line id] part (an eval sent collapsed, seon.context/request) had no clause
  ;; in the debug page's source line, so every pass of the render proc threw and no page answered;
  ;; each ref kind seon.context/request documents names its record
  (doseq [ref [[:section 397 536870918] [:page 12 nil #uuid "00000000-0000-0000-0000-000000000001"] [:page 12 #uuid "00000000-0000-0000-0000-000000000001" #uuid "00000000-0000-0000-0000-000000000002"] [:eval "e3"] [:eval "e4" {:since 'result/e3}] [:line "e5"] [:message "m1"] [:history 12]]]
    (is (string? (#'ui/source ref)) (pr-str ref)))
  (is (= "eval e5, sent as its one line" (#'ui/source [:line "e5"]))))

(deftest a-view-that-throws-answers-its-page-with-the-error
  ;; root, 2026-10-26: a render that threw stopped every pass of the render proc, and every page
  ;; waited forever. A view renders on its own: one whose render throws answers its own page with
  ;; the error and is recorded as a problem, and a second page renders through the same pass
  (let [k (seon.task/agent-key "b1" :a1)
        ;; a turn that sent a part no render names (a ref kind the debug page's source has no line for)
        db (reduce d/db-with (ts/test-db)
                   [[{:seon.root/id :root :seon.root/boot "b1" :seon.config.agent/max-running 4}]
                    (seon.task/agent-tx "b1" :a1 {:status :busy})
                    (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a1} "e1" "a1" {:code "(+ 1 2)" :response {:handle 'result/e1 :value 3} :run "t/x#1"})
                    [{:db/id "run" :seon.run/id "t/x#1"}]
                    [{:seon.turn/id "t/x#1/0" :seon.turn/run [:seon.run/id "t/x#1"] :seon.turn/n 0 :seon.turn/origin :model
                      :seon.turn/sent [[:no-such-part "x"]] :seon.turn/tokens 100 :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 1}]])
        debug [:debug :a1]
        o (volatile! {})
        st {:rid 1 :pending {} :watch #{debug :a1} :waiting {1 debug 2 :a1} :kf-wanted #{} :new-events [] :events [] :views {}
            ;; a1's process ended: root draws its page from its branch, in the same pass
            :main-db db :view {:agents {:a1 {:status :ended}}}
            :branches {debug {:db db :read [:task-x]} :a1 {:db db :read [:task-x]}}}
        st' (#'p/pass st o)
        pages (into {} (for [m (:to-web @o) :when (= :page (:op m))] [(:k m) (:html m)]))]
    (is (= #{1 2} (set (keys pages))) "both pages are answered: neither waits on the other's render")
    (is (str/includes? (str (pages 1)) "seon.render/view-defect") "the debug page is its error")
    (is (str/includes? (str (pages 1)) ":no-such-part") "it says what threw")
    (is (str/includes? (str (pages 2)) "drawn by root from its branch") "the agent's own page renders through the same pass")
    (is (some #(and (= :error (:op %)) (= :seon.render/view-defect (get-in % [:data :seon.error/type]))) (:to-api @o))
        "recorded as the problem it is: the api's proc error")
    (is (empty? (:waiting st')) "nothing left waiting")
    (testing "a pass after it, nothing changed: the same error is not sent again (its record walks no view into another)"
      (let [o2 (volatile! {})]
        (#'p/pass (assoc st' :waiting {3 debug}) o2)
        (is (not-any? #(= :error (:op %)) (:to-api @o2)))
        (is (some #(and (= 3 (:k %)) (str/includes? (:html %) "seon.render/view-defect")) (:to-web @o2)) "its page is still the error")))))

(deftest a-debug-page-of-an-agent-with-no-branch-answers
  ;; the browser pass (3b-root): /agent/user/debug never answered: the user has no branch on main, so
  ;; none was read, and the page waited for one. With no branch (or one that could not be read) its
  ;; debug page has no turn to show and says so at once
  (let [db (reduce d/db-with (ts/test-db) [[{:seon.root/id :root :seon.root/boot "b1"}] (seon.task/agent-tx "b1" :user {:status :idle})])
        st {:main-db db :branches {}}]
    (is (nil? (:seon.agent/branch (d/entity db (#'p/location db :user)))) "the fixture: no branch")
    (is (some? (:seon.render/html (#'p/debug-value st [:debug :user]))) "its page, with no turn")
    (is (some? (:seon.render/html (#'p/debug-value (assoc-in st [:branches [:debug :user] :error] {:seon.error/message "x"}) [:debug :user])))
        "a branch that could not be read: its page too")))

(deftest an-outside-read-is-no-model-call
  ;; the browser pass (3b-root): an outside read's turn (seon_context) said "the call is in flight"
  ;; on the debug page, though it calls no model
  (let [row (fn [origin] (h/html (ui/sent-request {:unit "d-1"} {:turn 0 :run "t/x#1" :view "a1" :refs 1 :added [] :seon.turn/origin origin})))]
    (is (str/includes? (row :outside) "an outside read: no model call"))
    (is (not (str/includes? (row :outside) "in flight")))
    (is (str/includes? (row :model) "the call is in flight") "a model turn with no usage yet is in flight")))

(deftest a-cut-with-its-whole-form-holds-no-copy
  ;; the second context audit (F7): a plan cut on the page kept its whole text in the cut's :title,
  ;; which the model reads too (1.2k chars twice on two pages). A cut that names the form reading the
  ;; whole holds no copy; with no form, the title is a person's way to the rest
  (let [long (str/join " " (repeat 400 "word"))
        with (ui/prose long 20 {:whole '(:plan (my.task/data))})
        without (ui/prose long 20)
        titles (fn [h] (keep #(when (map? %) (:title %)) (tree-seq coll? seq h)))]
    (is (empty? (titles with)) (pr-str with))
    (is (str/includes? (pr-str with) "(:plan (my.task/data))") "the cut names the form that reads the whole")
    (is (= [long] (titles without)) "no form: the title holds the whole")
    (is (not-any? nil? (tree-seq vector? seq with)) "and no empty child")))

(deftest a-whole-tasks-page-holds-its-instructions
  ;; the task part drew the plan and dropped the task's own data, its instructions with it, so no
  ;; agent read how its kind is worked (reproduce first, fail! when already right, stop reading once
  ;; wrong): they are drawn above the plan, whole
  (let [db (ts/test-db)
        db (d/db-with db (seon.task/create-tx db (seon.template/request {:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Make w true."})))
        h (h/html (ui/task-data {:seon.ctx/ctx {:runs {1 {:task {:seon.task/id "demo/w"}}}} :seon.db/db db} 1))]
    (is (str/includes? h "reproduce what the plan says is wrong") "the whole instructions are on the page")
    (is (str/includes? h "your run ends and root merges your branch") "whole: their last sentence too, nothing cut")
    (is (< (str/index-of h "reproduce what the plan says is wrong") (str/index-of h "Make w true.")) "above the plan")))

(defn- unit-of
  "The unit of units whose element id starts with prefix."
  [units prefix] (some #(when (str/starts-with? (:id %) prefix) %) units))

(deftest a-page-is-its-components
  ;; task seon.render/a-page-is-its-components: an agent's ctx drawn by the walk as components in
  ;; the page's lists, each keyed by what it shows, its root element's id its key
  (let [[_ branch _] (paged-dbs)
        k (seon.task/agent-key "b1" :a3)
        ;; the cited var's namespace, the program's as a store records it (seon.program/program-names)
        branch (d/db-with branch [{:ns/name 'seon.render :ns/file "src/seon/render.clj"}
                                  {:seon.turn/id "demo/f#1/1" :seon.turn/run [:seon.run/id "demo/f#1"] :seon.turn/n 1 :seon.turn/origin :model
                                   :seon.turn/basis {:commit #uuid "00000000-0000-0000-0000-000000000000" :task "demo/f" :run "demo/f#1" :turn 1 :max-turns 8 :origin :model
                                                     :observed {:results {'demo/f-test :fail} :reds [{:test 'demo/f-test :expected '(= 4 (f 2)) :actual 5}]
                                                                :reasons [{:reason :proof-red :symbol 'demo/f-test}]}}}])
        rid (seon.db/eid branch :seon.run/id "demo/f#1")
        draw #(let [w (r/draw (:tree (seon.ctx/build branch (seon.db/eid branch :seon.agent/key k) :task-demo-f)) seon.ctx/changes branch
                              (requiring-resolve (r/declared :seon.render/render-ctx)))]
                (try (r/placed-units w) (finally (r/dispose-walk! w))))
        units (draw)
        task (filterv #(= "#p-task" (:container %)) units)
        conv (filterv #(= "#c-rows" (:container %)) units)
        eid #(seon.db/eid branch %1 %2)]
    ;; a cited var is a component of its own when the db holds it (ctx joins the task's references by
    ;; :var/name: seon.context-test/a-cited-var-redefined-shows-in-the-next-view); this db holds none
    (is (= [(str "task-data-" rid) (str "related-" rid) (str "status-" rid) (str "run-head-" rid) (str "own-problems-" rid)] (mapv :id task))
        "the task, what is related, what its newest turn observed, the run, its open problems (the proof it fails; none open draws nothing) (and the keys the agent added, when it added one): each keyed by what it shows")
    (let [h (:html (unit-of task "status-"))]
      (is (str/includes? h "demo/f-test") "each proof as the newest turn observed it, (my.task/status)'s")
      (is (str/includes? h ":fail"))
      (is (str/includes? h "2 of 8") "the turn of how many"))
    (let [h (:html (unit-of task "task-data-"))]
      (is (str/includes? h "demo/f") "drawn by task-data")
      (is (str/includes? h "to-prove") "as the task's data, drawn from the db when it is drawn"))
    (is (str/includes? (:html (unit-of task "related-")) "nothing related yet") "related, drawn from the db: none yet here")
    (is (= [(str "message-item-" rid "-" (eid :seon.message/id "m-1")) (str "eval-item-" (eid :seon.eval/id "e1")) (str "eval-item-" (eid :seon.eval/id "e2"))
            (str "turn-item-" rid "-" (eid :seon.turn/id "demo/f#1/0")) (str "eval-item-" (eid :seon.eval/id "e3")) (str "turn-item-" rid "-" (eid :seon.turn/id "demo/f#1/1"))]
           (mapv :id conv))
        "the conversation in the order it was written: by eid, each a component keyed by its entity")
    (is (every? #(str/starts-with? (:html %) (str "<" )) units))
    (is (every? #(str/includes? (:html %) (str "id=\"" (:id %) "\"")) units) "each component's root element carries its key as its id")
    (is (= units (draw)) "pure: the same ctx, the same page")))

(deftest the-status-shows-what-an-outside-read-observed
  ;; task seon.render/the-status-before-any-model-turn: an outside read (seon.turn/context-block,
  ;; origin :outside) records what it observed as a model turn does, so the status is drawn from
  ;; the newest turn of either origin; an outside read has no turn count
  (let [[_ branch _] (paged-dbs)
        k (seon.task/agent-key "b1" :a3)
        branch (d/db-with branch [{:seon.turn/id "demo/f#1/read-1" :seon.turn/run [:seon.run/id "demo/f#1"] :seon.turn/n 1 :seon.turn/origin :outside
                                   :seon.turn/basis {:commit #uuid "00000000-0000-0000-0000-000000000000" :task "demo/f" :run "demo/f#1" :turn 1 :max-turns 1 :origin :outside
                                                     :observed {:results {'demo/f-test :pass} :reds [] :reasons []}}}])
        w (r/draw (:tree (seon.ctx/build branch (seon.db/eid branch :seon.agent/key k) :task-demo-f)) seon.ctx/changes branch (requiring-resolve (r/declared :seon.render/render-ctx)))
        status (try (unit-of (r/placed-units w) "status-") (finally (r/dispose-walk! w)))
        h (:html status)]
    (is (some? status) "the status, from the outside read")
    (is (str/includes? h ":pass") (str "what it observed: " h))
    (is (not (str/includes? h " of ")) "an outside read has no turn count")))

(deftest the-models-page-holds-the-plan-whole
  ;; the blind agent's plan was cut "+86 chars" at a part's budget: the task itself took an eval to read
  (let [[_ branch _] (paged-dbs)
        plan (str "Make `demo/f` return its argument doubled. " (str/join " " (repeat 60 "Every word of this plan is what the task is.")))
        db (d/db-with branch [{:seon.task/id "demo/f" :seon.task/plan plan}])
        page (seon.context/page db (seon.ctx/build db (seon.db/eid db :seon.agent/key (seon.task/agent-key "b1" :a3)) :task-demo-f))
        text (seon.print/text (vary-meta page assoc :seon.print/full true))]
    (is (< (:part seon.print/budgets) (seon.print.select/estimate plan)) "a plan over a part's budget")
    (is (str/includes? text (subs plan (- (count plan) 60))) "the plan's end is on the page")))

(deftest a-part-returns-its-data-as-a-call-of-the-printer
  ;; root, 2026-10-26 (the owner's compact renders, not stripped): the task's data is a call of the
  ;; printer, [#'seon.render/html opts value], which the page's serializer expands when it writes the
  ;; page; the model reads the call as returned, its value as data. Its opts are plain data, so an
  ;; equal value draws an equal call
  (let [[_ branch _] (paged-dbs)
        k (seon.task/agent-key "b1" :a3)
        task-hiccup #(let [w (r/draw (:tree (seon.ctx/build branch (seon.db/eid branch :seon.agent/key k) :task-demo-f)) seon.ctx/changes branch
                                     (requiring-resolve (r/declared :seon.render/render-ctx)))]
                       (try (second (first (r/placed-hiccup w ["#p-task"]))) (finally (r/dispose-walk! w))))
        call-of (fn [h] (some #(when (and (vector? %) (= #'r/html (first %))) %) (tree-seq coll? seq h)))
        h0 (task-hiccup)
        call (call-of h0)]
    (is (some? call) "the task's data is a call of the printer")
    (is (every? #(or (number? %) (symbol? %) (seq? %)) (vals (nth call 1))) "its opts are plain data: no db, no object")
    (is (= call (call-of (task-hiccup))) "drawn again, an equal call")
    (let [text (seon.print/text (vary-meta h0 assoc :seon.print/full true))]
      (is (str/includes? text "#'seon.render/html") "the model reads the call by its var")
      (is (not (str/includes? text "#object")) "and nothing printed as an object"))
    (is (str/includes? (h/html h0) "seon-data-panel") "the page writes the call expanded: the printer's panel")))

(defn- live-walk
  "a3's page walked over a reactive atom holding its ctx, as its process holds it (seon.repl/the-walk):
  [walk atom branch]."
  []
  (let [[_ branch _] (paged-dbs)
        a (seon.ratom/atom (:tree (seon.ctx/build branch (seon.db/eid branch :seon.agent/key (seon.task/agent-key "b1" :a3)) :task-demo-f)))
        w (r/walker {:ref a :changes seon.ctx/changes} (constantly branch) [(requiring-resolve (r/declared :seon.render/render-ctx)) {}])]
    (r/frame! w nil)
    [w a branch]))

(deftest a-component-re-runs-on-what-it-read
  ;; Reagent's rule with the cursor inferred: a change re-runs only the components whose read paths
  ;; it reaches; every other is not visited, and a re-run whose output is = sends nothing
  (let [[w a branch] (live-walk)
        e1 (seon.db/eid branch :seon.eval/id "e1")
        ran #(into {} (for [[f c] (r/ledger w) :when (or (:miss c) (:same c))] [(name f) (+ (:miss c 0) (:same c 0))]))]
    (vreset! (:ledger w) {})
    (swap! a assoc-in [:evals e1 :seon.eval/opened] true)
    (r/frame! w nil)
    (is (= {"eval-item" 1} (ran)) "an eval opened: that eval's component alone runs, its list's layout does not")
    (vreset! (:ledger w) {})
    (swap! a assoc :t 99)
    (r/frame! w nil)
    (is (= {} (ran)) "a change no component read: nothing runs")
    (vreset! (:ledger w) {})
    (swap! a assoc-in [:evals e1 :seon.eval/opened] true)
    (r/frame! w nil)
    (is (= {} (ran)) "the same value again: nothing runs")
    (r/dispose-walk! w)))

(deftest a-change-below-a-unit-moves-its-revision
  ;; a component placed in a list is a unit; one nested in it (a key the agent added, under ctx-keys)
  ;; changing moves the unit's revision with it, so the render proc sends the unit
  (let [[w a _] (live-walk)
        unit #(some (fn [u] (when (= "ctx-keys" (:id u)) u)) (r/placed-units w))]
    (swap! a assoc :lab.x/k "one")
    (r/frame! w nil)
    (let [u0 (unit)]
      (swap! a assoc :lab.x/k "two")
      (r/frame! w nil)
      (let [u1 (unit)]
        (is (str/includes? (:html u1) "two") "the new value drawn")
        (is (not= (:rev u0) (:rev u1)) "and the unit's revision moved, its own output unchanged")))
    (r/dispose-walk! w)))

(deftest a-key-is-its-id-one-to-one
  ;; an element id names one component: two keys never write one id
  (is (= "x-a.b_2Fc" (r/key-id ['x 'a.b/c])))
  (is (not= (r/key-id ['x 'a.b/c]) (r/key-id ['x 'a_b/c]) (r/key-id ['x "a.b/c"]))))

(deftest a-dead-agents-eval-opens
  ;; an agent with no process is drawn by root from its branch: an eval a reader opened (main's
  ;; :seon.agent/opened, which the agent would take) is drawn open
  (let [[main branch _] (paged-dbs)
        k (seon.task/agent-key "b1" :a3)
        main (d/db-with main [{:seon.agent/key k :seon.agent/opened #{"e1"}}])
        [st _] (#'p/pass-view (assoc (proc-state main branch) :view {:agents {}}) :a3)
        e1 (str "eval-item-" (seon.db/eid branch :seon.eval/id "e1"))
        html (get-in st [:views :a3 :units e1 :html])]
    (is (re-find #"<details[^>]* open" html) (str "drawn open: " html))))

(deftest a-new-member-is-an-append
  ;; a keyed component new in its list, its parent's output otherwise unchanged, is sent as an append
  ;; into that list (Datastar's mode append), never the list redrawn
  (let [[main branch _] (paged-dbs)
        k (seon.task/agent-key "b1" :a3)
        o (volatile! {})
        st (#'p/answered (first (#'p/pass-view (proc-state main branch) :a3)) o [:parts :a3] (drawn-parts branch))
        branch2 (d/db-with branch (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a3} "e4" "a3" {:code "(f 4)" :response {:handle 'result/e4 :value 8}}))
        o2 (volatile! {})
        _ (#'p/answered st o2 [:parts :a3] (drawn-parts branch2))
        patch (first (filter #(= :patch (:op %)) (:to-web @o2)))
        e4 (str "eval-item-" (seon.db/eid branch2 :seon.eval/id "e4"))]
    (is (= [["#c-rows" e4]] (mapv (fn [[sel html]] [sel (second (re-find #"id=\"([^\"]+)\"" html))]) (:appends patch)))
        "the new eval, appended to the conversation")
    (is (empty? (:frags patch)) "nothing else of the page sent")))

(deftest a-handle-links-to-its-eval
  ;; root, 2026-10-26: the conversation is keyed by eid, and a printed handle (result/e1) still
  ;; links to its eval: the eval's component draws its anchor (eval-e1) from the eval's id; opened
  ;; (:seon.eval/opened, the reader's fact taken onto the branch), it draws its value
  (let [[_ branch _] (paged-dbs)
        k (seon.task/agent-key "b1" :a3)
        units #(let [w (r/draw (:tree (seon.ctx/build % (seon.db/eid % :seon.agent/key k) :task-demo-f)) seon.ctx/changes %
                               (requiring-resolve (r/declared :seon.render/render-ctx)))]
                 (try (r/placed-units w) (finally (r/dispose-walk! w))))
        e1 (str "eval-item-" (seon.db/eid branch :seon.eval/id "e1"))
        closed (:html (some #(when (= e1 (:id %)) %) (units branch)))
        opened (:html (some #(when (= e1 (:id %)) %) (units (d/db-with branch [{:seon.eval/id "e1" :seon.eval/opened true}]))))]
    (is (str/includes? closed "id=\"eval-e1\"") "the handle's anchor, on the eval's own element")
    (is (str/includes? (h/html (r/print-html {} {:it 'result/e1})) "href=\"#eval-e1\"") "and a printed handle links to it")
    (is (not (re-find #"<details[^>]* open" closed)) "no one opened it: its one line")
    (is (str/includes? closed "seon.root%2Fopened") "opening it posts the fact (seon.root/opened!)")
    (is (re-find #"<details[^>]* open" opened) (str "opened: drawn open, its value with it: " opened))
    (is (> (count opened) (count closed)))))

(deftest a-dead-agents-page-renders-and-never-waits
  ;; root, 2026-10-26: no page waits on a process. A running agent's page is drawn from its branch until
  ;; its process answers with its parts; one whose process ended, or whose ask was answered with why,
  ;; is drawn from its branch and says so
  (let [[main branch _] (paged-dbs)
        o (volatile! {})
        st (assoc (proc-state main branch) :watch #{:a3} :waiting {5 :a3})
        st1 (#'p/pass st o)
        page (first (filter #(= :page (:op %)) (:to-web @o)))]
    (is (= 5 (:k page)) "running: answered in the pass that asked its process")
    (is (empty? (:waiting st1)))
    (testing "its process ended: root's view says so, its parts are dropped, and the page says it is not running"
      (let [st2 (#'p/answered (assoc-in st1 [:parts :a3 :units] []) (volatile! {}) :state (assoc-in (:view st1) [:agents :a3 :status] :dead))]
        (is (nil? (get-in st2 [:parts :a3])))
        (is (str/includes? (p/view-html st2 :a3) "its process is not running"))
        (is (str/includes? (p/view-html st2 :a3) "result/e1") "its parts, drawn by root from its branch")))
    (testing "its ask answered with why (gone, refused): the page says so"
      (let [gone (seon.contract/error :seon.root/no-agent 'seon.root/request "no live agent :a3" {})
            st2 (#'p/answered st1 (volatile! {}) [:parts :a3] gone)]
        (is (str/includes? (p/view-html st2 :a3) "its process did not answer (no live agent :a3)"))))))

(deftest an-agents-run-tile-is-its-ctxs
  ;; the owner, 2026-10-26: "any changes you want updated in the agents context and html should go
  ;; through database updates that reach the same data the context pull gets". A run's status main
  ;; writes reaches the agent's branch by take!; until then the tile shows what the branch holds
  (let [[main branch _] (paged-dbs)
        main (d/db-with main [{:seon.run/id "demo/f#1" :seon.run/status :closed :seon.run/outcome :done}])
        [st _] (#'p/pass-view (assoc (proc-state main branch) :view {:agents {}}) :a3)
        tile (some (fn [[id u]] (when (str/starts-with? id "run-head-") (:html u))) (get-in st [:views :a3 :units]))]
    (is (some? tile) "the run's tile, from the agent's record")
    (is (not (str/includes? (str tile) "closed")) (str "not main's status: " tile))
    (is (str/includes? (str tile) (name (:seon.run/status (d/pull branch [:seon.run/status] [:seon.run/id "demo/f#1"])))) "the branch's")))

(deftest a-collection-keyed-by-id-prints
  ;; a collection of ctx (a join's members, keyed by their ids, newest first) holds no override key and no
  ;; error type: its order is total over any key (seon.ctx/members-map's comparator), so a keyword lookup answers nil
  ;; and every value holding one prints (a sorted map ordered by compare throws on that lookup instead)
  (let [[_ branch _] (paged-dbs)
        evals (:evals (:tree (seon.ctx/build branch (seon.db/eid branch :seon.agent/key (seon.task/agent-key "b1" :a3)) :task-demo-f)))]
    (is (seq evals) "the agent's evals")
    (is (sorted? evals) "a collection, sorted by id")
    (is (every? int? (keys evals)))
    (is (nil? (get evals :seon.render/html)) "a keyword lookup answers nil")
    (is (vector? (r/print-html {} {:seon.task/id "t/x" :evals evals})) "nested in a value")
    (is (vector? (r/html {} evals)) "as the value itself")
    (is (nil? (r/schema-of evals nil)))))

(defn diff-view
  "A component whose answer is a diff: the vars its walk's db holds that base does not."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :seon.host/db] :any]}
  [{db :seon.db/db} base]
  [:p (str (count (second (d/datoms-diff base db :aevt [:var/name]))) " vars added")])

(deftest a-render-over-a-diff-runs-again-when-the-diff-moves
  ;; task datahike.db/a-diff-records-its-reads: a component that read a datoms-diff is matched
  ;; against a transaction by the range the diff read, so a var the branch adds runs it again
  ;; (it read no range at all before, and was skipped with its old answer)
  (let [base (ts/test-db)
        cell (volatile! base)
        w (r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} #(deref cell) [#'diff-view {} base])
        frame! (fn [tx-data] (let [res (d/with @cell tx-data)] (vreset! cell (:db-after res)) (r/frame! w (:tx-data res))))
        runs #(:miss (get (r/ledger w) `diff-view))
        html #(pr-str (map :out (vals @(:cache w))))]
    (r/frame! w nil)
    (is (str/includes? (html) "0 vars added"))
    (frame! [{:seon.task/id "unrelated/task"}])
    (is (= 1 (runs)) "a datom outside the diffed attribute leaves it as it was")
    (frame! [{:var/name 'app.m0/added-on-the-branch}])
    (is (str/includes? (html) "1 vars added") "the next frame shows the var the branch added")
    (is (= 2 (runs)) "the component ran again")
    (r/dispose-walk! w)))

;; ---- what a turn no longer computes: the task render's views (task seon.turn/observe-reads-are-renders) ----

(defn- defn-facts
  "Program facts for var v defined by source src, as an import records them: its namespace (src/),
  its form, its var."
  [v src]
  (let [n (namespace v)]
    [{:db/id (str "ns " n) :ns/name (symbol n) :ns/file (str "src/" (str/replace n "-" "_") ".clj")}
     {:db/id (str "form " v) :form/id (str v " " src) :form/src src :form/ns (str "ns " n) :form/order 0 :form/defines [(str v)]}
     {:db/id (str v) :var/name v :var/ns (str "ns " n) :var/form (str "form " v) :var/kind :fn}]))

(deftest the-merge-views-draw-what-the-merge-functions-answer
  ;; owner, 2026-10-27: what is derived for the reader is the hiccup's, drawn when what it reads
  ;; moves; a view of main and the merge base takes their commits as children (ctx's :main-commit
  ;; :base-commit) and reads them from the branch's own store, so it is a function of its args
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (defn-facts 'demo/f "(defn f [x] x)"))
      (let [base (d/commit-id @conn)
            _ (d/transact conn (defn-facts 'demo/k "(defn k [] 2)"))
            main (d/commit-id @conn)
            ours (d/db-with (d/commit-as-db conn base) (defn-facts 'demo/h "(defn h [] 1)"))
            merge (h/html (ui/merge-view {:seon.db/db ours} main base))
            carries (h/html (ui/divergence-view {:seon.db/db ours} base))
            gaps (h/html (ui/gaps-view {:seon.db/db ours} base))]
        (is (str/includes? merge "demo/k") "what main changed since the base")
        (is (str/includes? merge ":conflicts") "and which of those the branch changed too (none)")
        (is (str/includes? carries "demo/h") "what the branch's merge carries")
        (is (not (str/includes? carries "demo/k")) "never main's change")
        (is (str/includes? gaps "demo/h") "a function the branch defined with no contract")
        (is (not (str/includes? gaps "demo/f")) "never one it did not change")))))

(deftest an-unrelated-change-redraws-none-of-the-merge-views
  ;; task seon.turn/observe-reads-are-renders: a turn no longer computes these; each is a component
  ;; drawn again only when what it reads moves. An unrelated change of ctx redraws none; main
  ;; moving redraws the view of main (merge) and none of the base's
  (ts/test-branch!
    (fn [_ conn]
      (doseq [tx [[{:seon.root/id :root :seon.root/boot "b1"}]
                  [{:seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make demo/f." :seon.task/execution :running
                    :seon.task/branch :task-demo-f :seon.task/kind :whole :seon.task/proofs #{'demo/f-test}}]
                  (seon.task/agent-tx "b1" :a3 {:status :idle :branch :task-demo-f})
                  (seon.task/run-start-tx "demo/f#1" "demo/f" "b1" :a3 {:branch :task-demo-f :model "m" :max-turns 8} 0 "p")]]
        (d/transact conn tx))
      (let [c0 (d/commit-id @conn)
            k (seon.task/agent-key "b1" :a3)
            tree (assoc (:tree (seon.ctx/build @conn (seon.db/eid @conn :seon.agent/key k) :task-demo-f)) :main-commit c0 :base-commit c0)
            a (seon.ratom/atom tree)
            w (r/walker {:ref a :changes seon.ctx/changes} (fn [] @conn) [(r/declared-var :seon.render/render-ctx) {}])
            views '#{seon.render.ui/merge-view seon.render.ui/caused-view seon.render.ui/gaps-view seon.render.ui/divergence-view}
            ran (fn [] (into {} (for [[f v] (r/ledger w) :when (views f)] [f (+ (:miss v 0) (:same v 0))])))]
        (try
          (r/frame! w nil)
          (is (= views (set (keys (ran)))) "each view drawn once, as a component of the task's list")
          (let [r0 (ran)]
            (swap! a assoc :seon.agent/description "an unrelated change")
            (r/frame! w nil)
            (is (= r0 (ran)) "an unrelated change redraws none of them")
            (d/transact conn [{:seon.task/id "demo/g" :seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make demo/g." :seon.task/kind :work :seon.task/proofs #{}}])
            (swap! a assoc :main-commit (d/commit-id @conn))
            (r/frame! w nil)
            (let [r1 (ran)]
              (is (= #{'seon.render.ui/merge-view}
                     (set (for [[f n] r1 :when (not= n (r0 f))] f)))
                  "main moving redraws the view of main (a whole task merges itself), and none of the base's")))
          (finally (r/dispose-walk! w)))))))
