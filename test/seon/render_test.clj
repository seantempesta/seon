(ns seon.render-test
  "Renders (session W2): selection (override key, schema-declared, printer), root's context,
  and the render proc's step as a plain function: inputs before render, bytes before
  send, a new agent appended, a retired one removed, keyframes composed from what was
  retained. Run: bin/test seon.render-test"
  (:require [clojure.edn] [seon.test-store :as ts] [test-runner :as runner] [clojure.test :refer [deftest is testing]] [clojure.data.json]
            [clojure.string :as str] [clojure.core.async :as a]
            [seon.render :as r] [seon.fixtures :refer [flush! draw]] [seon.render.ui :as ui] [seon.render.proc :as p]
            [seon.web.html :as h] [clojure.walk] [clojure.set] [seon.print.select] [seon.config] [seon.doc] [seon.print] [seon.db] [seon.reconcile] [seon.task] [seon.edn] [seon.ctx] [seon.contract] [seon.repl] [datahike.api :as d]
            [seon.fx] [seon.source] [seon.reader] [seon.program] [seon.web] [seon.template] [seon.ctx.wire-test] [seon.render-cost-test] [seon.turn] [seon.model] [seon.problems] [seon.system] [seon.error] [my.agent] [my.test] [seon.ctx] [malli.registry] [seon.ctx.status] [seon.cost]))

(def ^:private task
  {:seon.task/id "calc/add" :seon.task/ns 'calc :seon.task/name "add" :seon.task/plan "Make `app.calc/add` pass"
   :seon.task/execution :running :seon.task/branch :task-calc-add :seon.task/proofs #{'app.proofs/add-test}
   :seon.task/needs #{} :seon.task/kind :work
   :seon.task/accumulated {:plan "Make `app.calc/add` pass" :proofs #{'app.proofs/add-test}}})

(deftest a-problems-report-is-sent-grouped-with-its-handles
  ;; (the owner, 2026-10-30: errors grouped by signature with their counts, the
  ;; newest first line and a handle): the problems report a part answers is sent the model by its declared model render,
  ;; a few fields a row, never a row's example or evidence printed whole, each recorded error with the form that reads it
  (let [big (vec (range 5000))
        row (fn [sig ms n msg] {:class :eval :signature sig :type :seon.agent/eval-error :message msg :count n :at-ms ms
                                :example {:value big} :evidence {:kind :error :signature sig :example {:value big}}
                                :producer 'seon.ref/scan :owner-ns 'seon.ref :proofs [] :answered-by []})
        report (with-meta {:t 7 :classes {:eval {:n 2 :rows [(row "aaa" 10 31 "seon.ref/scan invalid-output at [:seon.ref/blocks 4]\n  a key its closed map does not admit")
                                                             (row "bbb" 20 1 "unbound var seon.ref/emit")]}}}
                 {:seon.render/schema :seon.problems/report})
        sent (ui/problems-model {} report)
        rows (get-in sent [:classes :eval :rows])]
    (is (= 'seon.render.ui/problems-model (r/model-declared (seon.db/index) :seon.problems/report)) "the report's schema declares it")
    (is (= 2 (get-in sent [:classes :eval :n])))
    (is (= ["unbound var seon.ref/emit" "seon.ref/scan invalid-output at [:seon.ref/blocks 4]"] (mapv :message rows))
        "newest first, each message's first line")
    (is (= [1 31] (mapv :count rows)) "with its count")
    (is (= '(my.agent/problem "bbb") (:read (first rows))) "and the form that reads it whole")
    (is (not-any? #(or (contains? % :example) (contains? % :evidence)) rows) "never its example or evidence")
    (is (< (count (pr-str sent)) 600) (str "a few lines for any number of errors: " (count (pr-str sent)) " chars"))))

(deftest selection
  (testing "the value's own override key wins: data as is"
    (is (= [:b "mine"] (r/hiccup {:seon.render/index (seon.db/index) :schema :seon.task/task} {:a 1 :seon.render/hiccup [:b "mine"]}))))
  (testing "the schema's declared render, named by the producer"
    (is (= 'seon.render.ui/task-box (r/declared (seon.db/index) :seon.task/task)))
    (is (str/includes? (h/html (r/hiccup {:seon.render/index (seon.db/index) :schema :seon.task/task} task)) "task · calc/add")))
  (testing "a pulled entity's schema is the one its identity attribute names"
    (is (= :seon.message/fact (r/schema-of (seon.db/index) {:seon.message/id "m1"} nil)))
    (is (str/includes? (h/html (r/hiccup {:seon.render/index (seon.db/index)} {:seon.message/id "m1" :seon.message/from {:seon.agent/key "owner"} :seon.message/body "Looks right."}))
                       "owner")
        "drawn by its schema's render, seon.render.ui/message: its sender above its body"))
  (testing "no declaration: the one value printer's hiccup"
    (let [out (r/hiccup {:seon.render/index (seon.db/index)} {:k [1 2 3]})]
      (is (= "seon-data-panel" (get-in out [1 :class])))))
  (testing "one face: the page and the model read the same hiccup; no :ai face is left"
    (is (vector? (r/hiccup {:seon.render/index (seon.db/index)} {:k 1})))
    (is (nil? (resolve 'seon.render/ai)))
    (is (not-any? #(contains? (seon.contract/properties %) :seon.render/ai) (keys seon.contract/forms)) "no schema declares one")))

;; docs/render.md#a-value-draws-itself: a value is drawn by the render its attribute's schema declares, by the one lookup
(deftest an-attributes-value-is-drawn-by-its-schemas-render
  (let [ix (seon.db/index) plan "Do `this`:\n\n- one\n- **two**"]
    (testing "an attribute aliased to a registered schema inherits its render: a task's plan is Markdown"
      (is (= 'seon.render.ui/text (r/render-for ix :seon.task/plan :seon.render/hiccup)))
      (is (= 'seon.render.ui/text (r/render-for ix :seon.task/instructions :seon.render/hiccup))))
    (testing "a code-composed string stays plain: no render"
      (is (nil? (r/render-for ix :seon.task/reason :seon.render/hiccup))))
    (testing "the task's plan drawn through task-box is its list, not preformatted text"
      (let [out (h/html (r/hiccup {:seon.render/index ix :schema :seon.task/task} (assoc-in task [:seon.task/accumulated :plan] plan)))]
        (is (= 2 (count (re-seq #"<li" out))) out)
        (is (not (str/includes? out "<pre")) out)
        (is (str/includes? out "<code") "a code span is its element, for the person and the model alike")
        (is (not (str/includes? out "`this`")) "no Markdown delimiter is left")))))

(deftest a-render-is-opts-then-children
  ;; Reagent's form (docs/render.md): a render takes its opts first and its
  ;; children after, and a call in hiccup is written [f opts & children]
  ;; declared renders are found from the schemas at run time, so a contract's change moves no digest of this test (status-hiccup, 2026-11-06)
  (testing "every render a schema declares takes [opts v]: its contract's first argument is :seon.render/opts"
    (let [declared (distinct (keep #(r/declared (seon.db/index) %) (keys seon.contract/forms)))
          first-arg (fn [sym] (let [s (:malli/schema (meta (requiring-resolve sym)))
                                    arity (if (= :function (first s)) (second s) s)]
                                (second (second arity))))]
      (is (seq declared))
      (is (some #{'seon.render.ui/script} declared) "a render only a schema names is found: named here, its contract's change moves this test's digest")
      ;; :seon.render/opts, or opts it narrows ([:and :seon.render/opts [:map ...]]: render-ctx's renders)
      (is (= [] (vec (remove #(let [a (first-arg %)] (or (= :seon.render/opts a) (and (vector? a) (= [:and :seon.render/opts] (subvec a 0 2))))) declared)))
          "none takes its value first")))
  (testing "a nested value's render is a call [f opts v], which the writer expands"
    (let [out (r/print-hiccup {:seon.render/index (seon.db/index)} {:k {:seon.model-call/id "b1/a1/1" :seon.model-call/n 1}})
          ;; its head the render's var (seon.render/render-of), so a walk gives it what its contract asks
          call (some #(when (and (vector? %) (var? (first %))) %) (tree-seq coll? seq out))]
      (is (= {:as :inline} (dissoc (second call) :seon.render/index)) "opts second")
      (is (= "b1/a1/1" (:seon.model-call/id (nth call 2))) "the value its child"))
    (is (str/includes? (h/html [:div [r/hiccup {:schema :seon.task/task :seon.render/index (seon.db/index)} task]]) "task · calc/add") "[f opts v] in hiccup")))

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
            :events [{:type :ready :agent :a1 :at-ms 1}]}
        v (p/process-value st)]
    (is (= 4 (:root-errors-total v)) "how many defects, whole")
    (is (= {:render {:defects 1 :newest "boom"} :api {:defects 0}} (:health v)) "each declared proc's defects by pid")
    (is (= 2 (get-in v [:writer :commits])))
    (is (= 3.0 (get-in v [:writer :tx-per-commit])) "a commit takes every queued transaction")
    (is (not-any? #{:recent :batch-ms} (keys (:writer v))) "the windows themselves dropped")
    (is (= {:status :up :pid 7} (select-keys (:fork-server v) [:status :pid])))
    (is (= [:ready] (mapv :type (:events v))))
    (is (= [{:type :fork-server-up}] (:events (p/process-value (assoc st :events [{:type :fork-server-up}]))))
        "an event that names no agent (the fork server's) reads as it is")))

;; ---- the GUARDS audit's names for the same law -------------

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
    (is (= :seon.merge/behind (r/schema-of (seon.db/index) behind nil)))
    (is (= :seon.error/base (r/schema-of (seon.db/index) {:seon.error/type :no.such/variant :seon.error/operation 'a/b :seon.error/message "m"} nil))))
  (let [text (seon.print/text behind)]
    (testing "as a REPL answers it, by the one printer: its message, fix, variant and its own keys"
      (is (str/includes? text "main changed 3 key(s)"))
      (is (str/includes? text "A merge task merges main into the branch (my.branch/merge-main!)"))
      (is (str/includes? text ":seon.merge/behind"))
      (is (str/includes? text ":behind 3"))))
  (testing "the page shows the same facts"
    (let [html (h/html (r/hiccup {:seon.render/index (seon.db/index)} behind))]
      (is (str/includes? html "main changed 3 key(s)"))
      (is (str/includes? html "fix: A merge task merges main"))
      (is (str/includes? html "seon.merge/behind in seon.writer/merge-refused"))))
  ;; an outside eval on root returned select-keys of its recorded errors, no operation among them,
  ;; and root's page threw on error-box's contract (2026-10-28)
  (let [picked {:seon.error/type :seon.agent/load-failed :seon.error/message "the branch program did not load"
                :seon.error/count 1 :seon.error/signature "f4c715c4eb3d54a7"}]
    (testing "a map holding an error's type but not the rest of an error is no error, and draws as data"
      (is (not= :seon.error/base (r/schema-of (seon.db/index) picked nil)))
      (is (not= :seon.agent/load-failed (r/schema-of (seon.db/index) picked nil)))
      (is (str/includes? (h/html (r/hiccup {:seon.render/index (seon.db/index)} [picked])) "the branch program did not load")))))

(def ^:private failed-task
  (assoc task :seon.task/execution :held :seon.task/needs #{"calc/sub"}
         :seon.task/accumulated {:plan (str/join "\n" (cons "Make `app.calc/add` pass." (for [i (range 400)] (str "Step " i ": read `app.calc/f" i "` and its proof."))))
                                 :proofs (set (for [i (range 12)] (symbol "app.proofs" (str "add-" i "-test"))))}
         :seon.task/failure {:reason "add cannot be total over longs" :counterexample "(add Long/MAX_VALUE 1)" :agent "boot-1/a2"}
         :seon.task/evidence {:from "calc/add" :conflicts [] :unmerged [] :red ['app.proofs/add-0-test] :problem "red"}))

(deftest a-task-reads-its-state-plan-proofs-and-failure
  (testing "the page's task box shows the failure too"
    (let [html (h/html (r/hiccup {:seon.render/index (seon.db/index) :schema :seon.task/task} failed-task))]
      (is (str/includes? html "failed in boot-1/a2: add cannot be total over longs")))))

(defn- row
  "A problem row as seon.problems/report makes it: every row's members, then its class's."
  [m] (merge {:producer nil :owner-ns 'calc :evidence {} :proofs [] :answered-by [] :at-ms 1761170000000} m))

(deftest the-problem-report-reads-by-class
  ;; the whole report on root's page: by class, each row by its render
  (let [rep (with-meta {:t 90 :classes {:failed {:n 1 :rows [(row {:task "calc/add" :reason "not total" :open? true})]}
                                        :merge {:n 1 :rows [(row {:task "calc/sub" :error behind :open? false})]}
                                        :red {:n 0 :rows []}}}
              {:seon.render/schema :seon.problems/report})
        html (h/html (r/hiccup {:seon.render/index (seon.db/index)} rep))]
    (is (str/includes? html "not total"))
    (is (str/includes? html "calc/sub"))))

(deftest every-identity-attribute-is-named-by-one-entity-schema
  ;; the schema of an entity is the one its identity attribute names (seon.render/entity-schema (seon.db/index))
  (let [attrs #{:seon.agent/key :seon.task/id :seon.system/id :seon.model-call/id :seon.message/id
                :seon.agent/root :seon.eval/agent}
        named (frequencies (for [[k form] seon.contract/forms :when (:seon.db/attributes (seon.contract/properties k))
                                 [a] (filter vector? (rest form)) :when (attrs a)]
                             a))]
    (is (= attrs (set (keys named))) "each is named by an entity schema")
    (is (every? #{1} (vals named)) (pr-str named))))

(deftest a-stored-prove-draws-as-its-live-response
  ;; the context lab, 2026-11-02: a stored prove's row drew its raw map (:at-judge, :error, :fail ...) where its live
  ;; response drew seon.render.ui/proved; the value's schema is metadata the store does not keep, the record keeps :schema
  (let [e {:seon.eval/code "(my.agent/prove)" :seon.eval/intent "Prove." :seon.eval/schema :seon.test/proved
           :seon.eval/response {:handle 'result/e9 :value {:reports [] :ran 3 :reused 2 :fail 0 :error 0 :pass 3 :heap [1 2 3]}}
           :seon.eval/by {:seon.agent/key "a3"}}
        row (h/html (ui/evaluation {:seon.render/index (seon.db/index)} e))
        line (h/html (ui/evaluation {:seon.render/index (seon.db/index) :as :inline} e))]
    (is (str/includes? row "green: 5 of 5 passed") (str "the row draws the prove's own render: " row))
    (is (not (str/includes? row ":heap")) "not its map")
    (is (not (str/includes? line ":heap")) (str "the one line is the model render's too: " line))))

(deftest an-eval-and-a-turn-say-what-they-cost
  ;; an eval's cost as recorded, each number with its unit; a turn's header says which of how many
  (let [ev (h/html (ui/evaluation {:seon.render/index (seon.db/index)} {:seon.eval/code "(f 2)" :seon.eval/intent "f doubles?" :seon.eval/response {:handle 'result/e1 :value 4 :ms 1.5 :bytes 2048 :ticks 120}
                                      :seon.eval/by {:seon.agent/key "a3"}}))
        call {:seon.model-call/n 1 :seon.model-call/reply "Done." :seon.model-call/tokens 1200 :seon.model-call/basis {:max-turns 8}}
        turn (h/html (ui/model-call-view {:as :inline} call))]
    (is (str/includes? ev "f doubles?") "the text beside the call")
    (is (str/includes? ev "1.5 ms · 120 ticks · 2.0 KB") "its cost as recorded, in the one wording")
    (is (str/includes? (h/html (#'ui/cost-line {:ticks 1200 :ms 1.5})) "1.5 ms · 1.2k ticks") "ticks too, when recorded; nothing not recorded")
    (is (str/includes? (h/html (#'ui/cost-line {})) "not run") "an eval never run says so")
    (is (str/includes? turn "turn 1 of 8"))
    (is (not (str/includes? (h/html (ui/model-call-view {} call)) "tokens")) "its tokens are the debug tab's (debug-turn), not the chat's")))

;;; --- the law, from the compiler's facts: one printer, and no cut before it -------------

(def ^:private printer-vars
  "The vars that turn a value into text beside the one printer."
  '#{clojure.core/pr-str clojure.core/prn-str clojure.core/print-str clojure.core/with-out-str
     clojure.core/pr clojure.core/prn clojure.core/printf clojure.pprint/pprint clojure.pprint/pp
     clojure.pprint/write clojure.pprint/cl-format})

(def ^:private budgeted-vars
  "The printer's calls that cut to a budget: a cut where a value is made, outside a render."
  '#{seon.print/text seon.print/line seon.print/plain})

(def ^:private cut-vars
  "The vars that shorten what is shown, where output is made for a person or a model."
  '#{clojure.core/subs clojure.core/take clojure.core/take-last clojure.core/drop-last})

(defn- src-program
  "The checkout's program as facts, src and test (analyzed, never run): the kept program db the plan
  imported once for this tree (test-runner/program-db, ts/kept-program-store), so a test reads it and
  imports nothing (the import of src was 1.4 G ticks a test, 2 tests). Its src namespaces are
  `src-nss`."
  []
  ;; the checkout this process runs from (seon.config/checkout), never the working directory: an agent's is empty,
  ;; and a test of it was red in every agent's run (md-scan #23)
  (runner/program))

(defn- src-nss
  "The namespaces of db whose file is under src/."
  [db]
  (into #{} (comp (filter (fn [[_ f]] (str/starts-with? f "src/"))) (map first))
        (d/q '[:find ?n ?f :where [?e :ns/name ?n] [?e :ns/file ?f]] db)))

(deftest a-render-reaches-no-process
  ;; root, 2026-10-25: after jolt/agent-conversation merged, bin/prove planned 29 keys of 686 tests:
  ;; every test reaches every schema's declared render (contract/checked, the registry, the schema
  ;; forms), and seon.render.ui/did named my.agent/prove as a quoted symbol, which the reach walk
  ;; follows (seon.program/reach-graph), so every test reached seon.lib/fork! through it, counted
  ;; as reaching a process and was left out of bin/prove. A render runs no process
  (let [db (src-program)
        in (seon.fx/inputs (seon.config/checkout))
        g (runner/reach-graph-of db (:data-refs in) (ts/kept-program-store))
        idx (seon.program/graph-index g in
                                      {"agent" '[seon.render.ui/agent] "root" '[seon.render.ui/root] "root-ctx" '[seon.render.ui/root-ctx] "render-ctx" '[seon.render.ui/render-ctx]})]
    (is (= {"agent" false "root" false} (update-vals idx :process?))
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
  (let [db (src-program)
        nss (src-nss db)
        marks (clojure.set/union printer-vars cut-vars budgeted-vars)
        ;; only the forms of src that reference a mark are read as rows (a row of every form was 200 M ticks)
        forms (->> (d/datoms db :aevt :form/refs) (keep #(when (marks (:v %)) (:e %))) distinct sort
                   (map #(seon.program/form-row db %)) (filter (comp nss :ns)) vec)
        doc (let [t (slurp (str (seon.config/checkout) "/docs/render.md"))] (subs t (str/index-of t "## One printer") (or (str/index-of t "\n## " (+ 3 (str/index-of t "## One printer"))) (count t))))
        ;; a site is named by a list item, before its reason (`- `ns/f`, `ns/g`: why`)
        named (set (for [[_ head] (re-seq #"(?m)^- (.*?): " doc) [_ n] (re-seq #"`([a-z][\w.\-]*/[^`\s]+)`" head)] (symbol n)))
        printer? #(not (str/starts-with? (str %) "seon.print"))
        display? #(re-find #"^(my\.|seon\.(render|web|ctx\.(search|status|wire|session)|mcp|server|cost|repl|agent|problems)($|\.))" (str %))]
    (testing "src is imported: the check reads facts, not nothing"
      (is (< 40 (count nss)))
      (is (< 100 (count forms))))
    (testing "every var printing beside the one printer is named with its reason"
      (is (= [] (remove named (sites forms printer-vars printer?))) "each var listed is a new site: print through seon.print/text, or name it in docs/render.md#one-printer as a list item, - `ns/f`: its reason (the wire, storage, code text, or a cut of data, not of output)"))
    (testing "every var cutting in a display namespace is named with its reason"
      (is (= [] (remove named (sites forms cut-vars display?))) "each var listed is a new site: print through seon.print/text, or name it in docs/render.md#one-printer as a list item, - `ns/f`: its reason (the wire, storage, code text, or a cut of data, not of output)"))
    (testing "every var cutting to a budget where a value is made, outside a display namespace, is named"
      (is (= [] (remove named (sites forms budgeted-vars #(and (printer? %) (not (display? %)))))) "each var listed is a new site: print through seon.print/text, or name it in docs/render.md#one-printer as a list item, - `ns/f`: its reason (the wire, storage, code text, or a cut of data, not of output)"))
    (testing "a site named that no longer prints or cuts is gone from the page too"
      (is (= [] (remove (set (concat (sites forms printer-vars printer?) (sites forms cut-vars display?)
                                     (sites forms budgeted-vars #(and (printer? %) (not (display? %))))))
                        (sort named)))))))

;; docs/render.md#one-path-to-the-model: what the compiler's facts and a form's read say of who makes what the model is sent
(def ^:private the-path
  "Each step of the one path to a provider, and who may reference it."
  '{seon.ctx.wire/request #{seon.ctx.wire/render}
    seon.model/wire-messages #{seon.model/request-body}
    seon.model/request-body #{seon.model/openai-compat seon.ctx.wire/wire-turn}})

(defn- rows-with
  "The rows of the forms of db in namespaces nss whose source holds one of texts, each with :read, what its source reads as."
  {:malli/schema [:=> [:cat :seon.host/db :any [:sequential :string]] [:vector :map]]}
  [db nss texts]
  (->> (d/datoms db :aevt :form/src)
       (keep #(when (some (fn [t] (str/includes? (:v %) t)) texts) (:e %)))
       (map #(seon.program/form-row db %))
       (filter (comp nss :ns))
       (mapv #(assoc % :read (seon.reader/read-text (:src %) (:ns %))))))

(defn- holds-map?
  "Does what form row r reads as hold a map literal with a key pred accepts?"
  {:malli/schema [:=> [:cat :map :any] :boolean]}
  [r pred] (boolean (some #(and (map? %) (some pred (keys %))) (tree-seq coll? seq (:read r)))))

(defn- named-by
  "The vars row r defines."
  {:malli/schema [:=> [:cat :map] [:sequential :any]]}
  [r] (for [[k sym] (:defines r) :when (#{:var :method} k)] sym))

(def ^:private model-renders
  "The model renders (:seon.render/openai-compat of an entity schema, docs/users.md#the-stages-agreed-2026-11-09) and the one helper that writes a turn's page message: each writes messages as the one walk places them, which is the path."
  '#{seon.ctx.wire/agent-msgs seon.ctx.wire/eval-msgs seon.ctx.wire/message-msgs seon.ctx.wire/model-call-msgs seon.ctx.wire/page-message seon.ctx.wire/example-msgs seon.ctx.wire/example-agent-msgs seon.ctx.wire/defect-msgs seon.ctx.wire/task-msgs})

(defn- outside-the-path
  "The vars of db in namespaces nss that make provider messages other than through seon.ctx.wire/request: one that references a step of `the-path` and is not named for it, or
  whose code holds a message ({:role :content}) or a request ({:messages} of a turn or a model) and is not a step."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:set :any]]}
  [db nss]
  (let [refs (->> (d/datoms db :aevt :form/refs) (keep #(when (contains? the-path (:v %)) [(:v %) (:e %)])) distinct)
        by-ref (for [[step e] refs :let [r (seon.program/form-row db e)] :when (nss (:ns r)) v (named-by r) :when (not (or (= step v) (contains? (get the-path step) v)))] v)
        role (for [r (rows-with db nss [":role"]) :when (some #(and (map? %) (contains? % :role) (contains? % :content)) (tree-seq coll? seq (:read r))) v (named-by r)
                   :when (not (or (#{'seon.model/wire-message} v) (model-renders v)))] v)
        asks (for [r (rows-with db nss [":messages"])
                   :when (some #(and (map? %) (contains? % :messages) (or (contains? % :turn) (contains? % :model))) (tree-seq coll? seq (:read r)))
                   v (named-by r)
                   :when (not (or (some #{'seon.ctx.wire/render 'seon.ctx.wire/request} (:refs r))
                                  (#{'seon.model/request-body 'seon.ctx.wire/request 'my.root/first-moves!} v)))] v)]
    (set (concat by-ref role asks))))

(defn- action-sites
  "The vars of db in namespaces nss whose hiccup holds an action: a callback as data (:seon/call under a map's key), or a Datastar handler (a data-on attribute)."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:set :any]]}
  [db nss]
  (set (for [r (rows-with db nss [":seon/call" "data-on"])
             :when (or (holds-map? r #{:seon/call})
                       (holds-map? r #(str/starts-with? (str (if (keyword? %) (name %) (if (seq? %) (second %) ""))) "data-on")))
             v (named-by r)] v)))

(deftest one-path-to-the-model
  (let [db (src-program) nss (src-nss db)]
    (is (= #{} (outside-the-path db nss)) "everything a model is sent is read from the tree")
    (testing "a second path is red: a function that writes messages and asks for them itself"
      (let [w (my.test/with (my.test/world)
                (my.test/code 'app.second (str "(ns app.second (:require [seon.model :as m]))\n"
                                          "(defn ask \"A request made beside the tree.\" [p] (m/request-body p {:messages [{:role \"user\" :content \"hi\"}]}))\n"
                                          "(defn told \"A message made beside the tree.\" [] {:role \"user\" :content \"hi\"})\n"
                                          "(defn sends \"Messages asked for beside the tree.\" [ms] {:messages ms :turn 1})")))]
        (is (= '#{app.second/ask app.second/told app.second/sends} (outside-the-path w #{'app.second})))))))

(deftest an-action-on-the-page-is-the-models-too
  ;; owner, 2026-11-06: "actions are in the data, never only in a look". Each render holding an action is named in docs/render.md#one-path-to-the-model, with what the model has for it
  (let [db (src-program) nss (src-nss db)
        doc (let [t (slurp (str (seon.config/checkout) "/docs/render.md"))] (subs t (str/index-of t "## One path to the model") (or (str/index-of t "\n## " (+ 3 (str/index-of t "## One path to the model"))) (count t))))
        named (set (for [[_ head] (re-seq #"(?m)^- (.*?): " doc) [_ n] (re-seq #"`([a-z][\w.\-]*/[^`\s]+)`" head)] (symbol n)))]
    (is (= named (action-sites db nss)) "a new one is red until the page says what the model has for it, and a name that holds none leaves it")
    (testing "a planted one is seen"
      (let [w (my.test/with (my.test/world) (my.test/code 'app.look "(ns app.look)\n(defn approve \"A button.\" [] [:button {:seon/call 'app.look/approve!} \"Approve\"])"))]
        (is (= '#{app.look/approve} (action-sites w #{'app.look})))))))

;; ---- the agent's debug view ----------------

(deftest a-printed-reference-is-its-identity-value
  ;; docs/render.md#a-render-is-a-value-printed-once: a map of identity attributes alone is a reference, as a pull joins a ref; the printer
  ;; prints it as its identity value, alone and in the line of the value holding it, never drawn by its schema's render, so a message's
  ;; line reads its sender's name as it did when senders were strings, and the data stays a ref
  (let [ix (seon.db/index) nested #'seon.render/nested
        msg {:seon.message/id "m1" :seon.message/from {:seon.agent/key "user"} :seon.message/body "hello"}]
    (is (= {:text "user" :html "user"} (nested ix {:seon.agent/key "user"})) "a reference alone: its identity value")
    (is (= {:text ":root" :html ":root"} (nested ix {:db/id 7 :seon.system/id :root})) "its :db/id beside it changes nothing")
    (is (str/includes? (:text (nested ix msg)) ":from \"user\"") "in the line of the value holding it: the sender's name")
    (is (vector? (:html (nested ix {:seon.agent/key "user" :seon.agent/status :running}))) "an entity with more than its identity is no reference: drawn by its schema's render")))

(deftest the-printer-renders-each-nested-value-by-its-own-render
  ;; owner, 2026-10-23: the default render auto-renders. A value inside the printed one whose
  ;; schema declares a render shows as that render, one line, in its place: a call of its render
  ;; in the hiccup (expanded by the serializer); the printed value itself is the printer's, never
  ;; its own render again
  (let [a {:seon.message/id "m1" :seon.message/from {:seon.agent/key "owner"} :seon.message/body "Looks right."}
        v {:newest a :count 3}]
    (let [html (h/html (r/hiccup {:seon.render/index (seon.db/index)} v))]
      (is (str/includes? html "<a href=\"/agent/owner\">owner</a>: ") "the hiccup: the message's own render, inline, its sender by seon.render.ui/sender")
      (is (str/includes? html "seon-print-keyword\">:count") "around it, the printer's map"))
    (is (re-find #"seon-note\"><a href=\"/agent/owner\">owner</a><" (h/html (r/hiccup {:seon.render/index (seon.db/index)} a))) "the value itself by its render, its sender above its body")))

;; ---- the pages: a page is its parts (task seon.render/every-page-is-its-parts) ----

(def ^:private walked-plan
  "A plan citing a var (one backtick: its doc), a namespace and a wiki section (a link)."
  "Make `seon.render/hiccup` answer the tree; its printer is `seon.print`, and see [no clipping](docs/render.md#no-clipping).")

(defn- eid-of [db ref] (:db/id (d/entity db ref)))

;; ---- the page: every page is the walk of one entity ----

(defn- root-works-for
  "Root's session as seon.system/root-agent-tx writes it: its pattern its role's name and the person it works for, the starter of its own
  task request/root (user u, a ref), whose sessions that person's pattern reads through the tasks they started."
  [u]
  [{:db/id "rt" :seon.task/id "request/root" :seon.task/ns 'request :seon.task/name "root" :seon.task/plan "p" :seon.task/kind :work
    :seon.task/execution :running :seon.task/branch :task-b :seon.task/started-by u :seon.task/agent "root"}
   {:db/id "root" :seon.agent/key "root" :seon.ctx/pattern [:seon.db/agent-fact {:seon.agent/task [{:seon.task/started-by :seon.db/user-fact}]}] :seon.agent/task "rt"}])

(defn- started-tx
  "User u (a ref: a lookup or a tempid) started agents as (refs), each by a task of its own naming it, as seon.task/start-tx writes a
  person's request: a user is in no process tree, and its agents are read through its tasks (:seon.task/_started-by)."
  [u as]
  (vec (for [[i a] (map-indexed vector as)]
         {:seon.task/id (str "req/s" (hash [u a]) "-" i) :seon.task/ns 'req :seon.task/name (str "s" (hash [u a]) "-" i) :seon.task/plan "p"
          :seon.task/kind :work :seon.task/execution :running :seon.task/branch :task-b :seon.task/agent a :seon.task/started-by u})))

(defn- signed-in
  "Main db with a signed-in user, \"user\", who started the agents eids: the agents list every page shows is that user's
  (seon.render.proc/user-sides)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :int]] :seon.host/db]}
  [db eids]
  ;; a user is in no process tree: the agents it started are read through the tasks it started
  (d/db-with db (into [{:db/id "u" :seon.agent/key "user" :seon.user/name "user"} {:seon.system/id :root :seon.system/signed-in "u"}]
                      (started-tx "u" eids))))

(defn- paged-dbs
  "Main and a3's branch as production writes them: main holds root, the task, the agent (forked
  idle, busy since), its run and a message to the run; the branch, forked from main before the
  agent turned busy, adds what only it records: a model turn whose two calls each ran one eval
  (named by each call's :eval), then an eval the owner asked for from the page, an error.
  Answers [main branch agent-eid]. With a conn (a test branch), main's transactions are committed on it,
  so main's dbs are commits a notice can name; otherwise they are values (d/db-with)."
  ([] (paged-dbs nil))
  ([conn]
  (let [k "a3"
        main0 (reduce (if conn (fn [_ tx] (:db-after (d/transact conn {:tx-data tx}))) d/db-with) (if conn @conn (ts/test-db))
                      [[{:seon.system/id :root :seon.system/boot "b1"}]
                       (into [{:db/id "demo/f" :seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan walked-plan :seon.task/execution :running
                               :seon.task/branch :task-demo-f :seon.task/kind :work :seon.task/proofs #{'demo/f-test}}]
                             ;; what its plan cites, as the task writer writes it (seon.task/cites-tx): its targets made before them in this write
                             (into [{:var/name 'seon.render/hiccup} {:seon.doc/address "docs/render.md#no-clipping" :seon.doc/heading "No clipping"}]
                                   (seon.task/cites-tx (ts/test-db) "demo/f" "demo/f" (seon.ref/kept (ts/test-db) (seon.ref/cited (:seon.ref/citations (seon.ref/parse nil walked-plan)))))))
                       (seon.task/agent-tx :a3 {:status :idle :pid 77 :branch :task-demo-f})
                       (seon.test-store/work-tx "demo/f" :running {:boot "b1" :agent :a3 :branch :task-demo-f :model "m" :max-turns 8 :start-ms 0})
                       (seon.task/message-tx "m-1" (seon.task/sender "owner") [k] "check g" 1000)
                       ;; the agent's task, which its record pulls (seon.agent/the-agent-is-the-unit-not-the-run)
                       [{:seon.agent/key k :seon.agent/task {:seon.task/id "demo/f"}}]
                       ;; root's own evaluation, from its REPL
                       (seon.repl/record-tx {:seon.system/id :root} "root-1" (seon.task/sender "outside") {:seon.eval/code "(my.root/problems sys)" :seon.eval/response {:handle 'result/e1 :value {}}})])
        branch (reduce d/db-with main0
                       [(seon.repl/record-tx {:seon.agent/key k} "e1" (seon.task/sender "a3")
                                             {:seon.eval/call-id "c-e1" :seon.eval/call (seon.task/call-ref k 1) :seon.eval/code "(f 2)" :seon.eval/intent "f doubles?" :seon.eval/response {:handle 'result/e1 :value 4 :ms 1.5 :bytes 2048 :ticks 120}})
                        (seon.repl/record-tx {:seon.agent/key k} "e2" (seon.task/sender "a3")
                                             {:seon.eval/call-id "c-e2" :seon.eval/call (seon.task/call-ref k 1) :seon.eval/code "(f 3)" :seon.eval/response {:handle 'result/e2 :value 6 :ms 0.4 :bytes 512 :ticks 40}})
                        [{:seon.agent/key k :seon.agent/turn 1}
                         {:seon.model-call/id (seon.lib/call-id k 1) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n 1 :seon.model-call/origin :model :seon.model-call/reply "it doubles"
                          :seon.model-call/forms 2 :seon.model-call/ms 5 :seon.model-call/tokens 1200
                          :seon.model-call/basis {:commit #uuid "00000000-0000-0000-0000-000000000000" :task "demo/f" :turn 1 :max-turns 8 :origin :model}}]
                        (seon.repl/record-tx {:seon.agent/key k} "e3" (seon.task/sender "user")
                                             {:seon.eval/code "(g)" :seon.eval/call (seon.task/call-ref k 1) :seon.eval/response {:handle 'result/e3 :ex {:seon.error/type :seon.agent/eval-error :seon.error/operation 'seon.repl/eval-code :seon.error/message "boom"}}})])
        main (if conn (:db-after (d/transact conn {:tx-data (seon.task/agent-tx :a3 {:status :busy})})) (d/db-with main0 (seon.task/agent-tx :a3 {:status :busy})))]
    [main branch (eid-of main [:seon.agent/key k])])))

(defn- proc-state
  "The render proc's state as it holds a read of main and of a3's branch: root's view (a3 busy;
  root's boot, b1, is its entity's on main), main's open problems."
  [main branch]
  {:rid 1 :pending {} :watch #{:root [:chat :a3]} :waiting {} :kf-wanted #{} :new-events [] :events [] :views {} :user-walks {} :branches {:a3 {:db branch :branch :task-demo-f} [:chat :a3] {:db branch :branch :task-demo-f}}
   ;; the one read of a3's branch every view of it shows (seon.render.proc/ask-branches)
   :branch-reads {:task-demo-f {:db branch}}
   :merges 0 :main-db main :view {:agents {:a3 {:status :busy :branch :task-demo-f}}}})

(defn- branch-state
  "proc-state with a3's branch read named (its branch, :task-demo-f), as ask-branches records it."
  [main branch]
  (assoc-in (proc-state main branch) [:branches :a3 :read] [:task-demo-f]))

(defn- root-state
  "St with root's walk current as of main (seon.render.proc/advance-main: root's ctx built at main's first read), main holding root's
  session as the boot makes it (seon.system/root-agent-boot-tx): root's page is that agent's; a signed-in user \"user\" is written as the
  boot writes it (seon.task/boot-tx: root's agent its parent)."
  [st main]
  (let [main (d/db-with main (seon.test-store/root-tx {:seon.config.agent/max-running 4}))]
    (#'p/advance-main (dissoc st :main-db) (d/db-with main (cond-> (seon.system/root-agent-boot-tx main)
                                                            (d/entity main [:seon.user/name "user"]) (into (seon.task/boot-tx "b1" "user")))))))

(deftest a-dropped-page-lets-go-of-its-branchs-db
  ;; docs/machine.md#roots-heap: a branch's db read for a page is kept while a page shows it. The
  ;; read was never dropped, so every branch ever opened kept a version of the index for root's life.
  (let [[main branch] (paged-dbs)
        st (proc-state main branch)]
    (is (some? (get-in st [:branch-reads :task-demo-f :db])) "the read is held while views show the branch")
    (let [one (#'p/drop-view st [:chat :a3])]
      (is (some? (get-in one [:branch-reads :task-demo-f :db])) "and while another view of it remains"))
    (let [none (#'p/drop-view (#'p/drop-view st [:chat :a3]) :a3)]
      (is (nil? (get-in none [:branch-reads :task-demo-f])) "gone with its last view")
      (is (= [:main-db] (map (comp first first) (ts/stale-dbs none Long/MAX_VALUE))) "so no db is held but main's"))))

(deftest root-context-is-its-page-printed-at-the-budget
  ;; what root reads is its page, the render of its ctx (each component's hiccup by its id), printed once
  ;; by the one printer at :seon.config.render/root-context-budget; the cut is the printer's alone
  (let [[main branch _] (paged-dbs)
        st (-> (proc-state main branch) (assoc :watch #{}) (root-state main))
        st1 (#'p/pass (update st :watch conj [:debug :root]) (volatile! {}))
        ctx (:context st1)
        budget (seon.config/value :seon.config.render/root-context-budget)]
    (testing "printed only for a reader (a tab on /root/debug, or a :context request): a pass nobody reads it in prints nothing (derived data: docs/data-modeling.md#derived-data-is-a-cache-never-relied-on)"
      (is (nil? (:context (#'p/pass st (volatile! {})))) "no tab: nothing printed, no health read")
      (is (nil? (:health-at (#'p/pass st (volatile! {})))))
      (is (= ctx (:context (#'p/with-context (#'p/health! st)))) "the :context request's print is the page's")
      (let [asked (#'p/with-context (#'p/health! st))]
        (is (identical? (:context asked) (:context (#'p/with-context asked))) "held by the walk's value and main's t: asked again prints nothing")))
    (testing "what is wrong comes first (its tiles: its process, main's problems and work), at the printer's budget"
      (is (str/starts-with? (:text ctx) "{\"process-tile\""))
      (is (str/includes? (:text ctx) "root's process"))
      (is (<= (:tokens ctx) budget))
      (is (= (:tokens ctx) (seon.print.select/estimate (:text ctx)))))
    (testing "no clock: an unchanged system gives the same text"
      (is (not (re-find #"\d\d:\d\d:\d\d" (:text ctx))))
      (is (= ctx (:context (#'p/pass st1 (volatile! {}))))))
    (r/dispose-walk! (get-in st1 [:root-walk :w]))))

(defn- pass-view
  "`seon.render.proc/pass-view`, and the assertion that no walk its state holds (root's, each branch's read, each user's) drew an error
  in a component's place (seon.fixtures/no-error-render): a view-pass drains them, so they are read here. See docs/render.md#a-component-answers-hiccup."
  {:malli/schema [:=> [:cat :map :any] [:tuple :map :any]]}
  [st view]
  (let [r (#'p/pass-view st view)]
    (doseq [w (concat (some-> (:root-walk (first r)) :w vector) (keep #(some-> (:walk (val %)) :w) (:branch-reads (first r))) (map :w (vals (:user-walks (first r)))))]
      (seon.fixtures/no-error-render @(:defects w)))
    r))

(defn- a3-walk
  "a3's page walked once from branch (seon.render/draw of its ctx by the layout its schema declares)."
  [branch]
  (draw (:tree (seon.ctx/build (seon.db/index) branch (seon.db/eid branch :seon.agent/key "a3") :task-demo-f))
          seon.ctx/changes branch #'seon.render.ui/agent))

(defn- drawn-parts
  "a3's page's units as its process draws them (seon.repl/render-ctx), drawn here from branch: what the
  render proc's {:op :render} ask is answered with."
  [branch]
  (let [w (a3-walk branch)] (try {:t (:max-tx branch) :units (r/placed-units w)} (finally (r/dispose-walk! w)))))

(defn- component-html
  "The html of the component whose element id is id in a3's page drawn from branch, however deep it sits (an eval is a
  component of its turn's, not a unit of the list): as the page writes it, or nil when the page draws none."
  [branch id]
  (let [w (a3-walk branch)]
    (try (some (fn [[k c]] (when (= id (:id c)) (h/html (r/resolved w k true)))) @(:cache w))
         (finally (r/dispose-walk! w)))))

(defn- turn-2-eval-tx
  "Agent k's turn 2 begun with an eval of its own, e4, as the agent's REPL records it: its number moves and the eval
  takes it (docs/turn.md#the-turn-is-a-number)."
  [k code]
  (into [{:seon.agent/key k :seon.agent/turn 2}]
        (seon.repl/record-tx {:seon.agent/key k} "e4" (seon.task/sender "a3")
                             {:seon.eval/call (seon.task/call-ref k 2) :seon.eval/code code :seon.eval/response {:handle 'result/e4 :value 1}})))

(deftest the-problems-tile-follows-main-without-reading-it-whole
  ;; task seon.problems/health-at-the-cost-of-what-changed: root's walk holds main's health; a move of
  ;; main changes it by the datoms that moved (seon.render.proc/health!, read when a reader is there, not per
  ;; commit: from the point it was read at, made again by seon.db/at-commit), and the tile draws what it holds
  (ts/test-branch! (fn [_ conn]
  (let [[_ branch _] (paged-dbs conn)
        main0 (do (d/transact conn {:tx-data (seon.test-store/root-tx {:seon.config.agent/max-running 4})}) (:db-after (d/transact conn {:tx-data (seon.system/root-agent-boot-tx @conn)})))
        st (assoc (#'p/advance-main (dissoc (proc-state main0 branch) :main-db) main0) :conn conn)
        tile (fn [st] (:html (second (filter #(= "#p-tiles" (:container %)) (:seon.render/units (#'p/page-value st :root))))))
        main2 (:db-after (d/transact conn {:tx-data [{:seon.task/id "zz/dead" :seon.task/ns 'zz :seon.task/name "dead" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :b :seon.task/kind :work
                                                      :seon.task/agent {:seon.agent/key "b/zz" :seon.agent/ended-ms 2}
                                                      :seon.task/outcome :dead :seon.task/reason "its agent died"
                                                      :seon.task/error (seon.contract/error :seon.agent/died 'seon.system/end-agent "agent zz died" {:agent :zz})}]}))
        moved (#'p/advance-main st main2)]
    (is (nil? (:health-at st)) "a move of main reads no health: nothing reads it")
    (is (nil? (:health-at moved)))
    (let [st (#'p/health! st) before (tile st)
          st2 (#'p/health! moved)]
      (is (some? (:health-at st)) "read when a reader is there")
      (is (not (str/includes? before "zz/dead")) "main holds no such dead task yet")
      (is (str/includes? (tile st2) "zz/dead") "the move shows its dead task in the tile")
      (is (= (:classes (seon.problems/health main2)) (:classes (:health @(get-in st2 [:root-walk :a])))) "the held health is health of the new main")
      (r/dispose-walk! (get-in st2 [:root-walk :w])))))))

(deftest a-move-of-main-is-diffed-once-for-every-walk
  ;; docs/render.md#a-page-is-a-kept-walk: the index, root's walk, each user's and the views it touched read the datoms main moved by,
  ;; one diff of the two mains (5.4 ms on 10.8k datoms in a :memory store); each walk diffed main again, one diff more a walk
  (let [[main branch] (paged-dbs)
        st (-> (proc-state main branch) (assoc :watch #{}) (root-state main))
        main2 (d/db-with (:main-db st) [{:seon.task/id "zz/one" :seon.task/ns 'zz :seon.task/name "one" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :b :seon.task/kind :work}])
        n (atom 0) diff d/datoms-diff
        st2 (with-redefs [d/datoms-diff (fn [& args] (swap! n inc) (apply diff args))] (#'p/advance-main st main2))]
    (is (= 1 @n) (str "one diff a move of main, " @n " made"))
    (is (identical? main2 @(get-in st2 [:root-walk :at])) "root's walk is at the new main")
    (r/dispose-walk! (get-in st2 [:root-walk :w]))))

(deftest the-problems-tile-counts-what-still-holds
  ;; Optimization, 2026-11-08: 7888's tile said "effect 4" for a :seon.vector/helper-died row whose cause had passed (:open? false,
  ;; seon.problems/closed?), as health does not: the tile draws and counts a class's open rows, and a class with none is not drawn
  (let [h {:t 9 :classes {:effect {:n 4 :rows [(row {:type :seon.vector/helper-died :count 4 :message "helper died" :open? false})]}
                          :eval {:n 1 :rows [(row {:type :seon.instrument/arity-error :count 1 :message "arity"})]}}}
        html (h/html (ui/problems-tile {:seon.render/index (seon.db/index) :seon.ctx/ctx {:health h}}))]
    (is (not (str/includes? html "helper died")) "a row whose cause passed is not drawn")
    (is (not (str/includes? html "effect")) "nor its class, which holds no open row")
    (is (str/includes? html "arity") "an open row is")))

(deftest a-stale-binary-is-on-the-problems-tile-with-its-acts
  ;; docs/source.md#files-and-the-db-two-directions: a mismatch is never silent to a person. The :stale-binary row health derives
  ;; (seon.problems/stale-binary) is drawn on root's problems tile, never folded under upkeep, its message naming the acts
  (let [db (seon.db/db-with (my.test/world {:history? true}) [{:seon.system/id :root :seon.system/source {:root "/c" :files 1 :forms 1 :ms 1 :boot "b" :binary "p0" :binary-tree "t0" :tree "t1" :program "p1" :stale-binary {:binary "t0" :tree "t1" :reaches ['seon.writer/commit-batch]}}}])
        h (seon.problems/health db)
        html (h/html (ui/problems-tile {:seon.render/index (seon.db/index) :seon.ctx/ctx {:health h}}))]
    (is (= 1 (get-in h [:classes :stale-binary :n])) "health holds the row")
    (is (str/includes? html "stale-binary") "the tile draws its class")
    (is (not (str/includes? (h/html (ui/problems-tile {:seon.render/index (seon.db/index) :seon.ctx/ctx {:health (update h :classes select-keys [:stale-section])}})) "stale-binary"))
        "and draws it from the row, not by itself")
    (is (str/includes? (get-in h [:classes :stale-binary :rows 0 :message]) "bin/system start --reindex") "its message names the acts")))

(deftest an-agents-page-draws-the-problems-of-its-own-task
  ;; task seon.problems/health-in-context: its task's open problems, by the report's render, beside the
  ;; task; none when none is open; another task's and main's classes are not drawn
  (let [[_ branch _] (paged-dbs)
        problems (fn [db] (filter #(= (:id %) "own-problems") (:units (drawn-parts db))))
        red (datahike.api/db-with branch [{:seon.task/id "demo/f" :seon.task/proofs #{'demo/f-test} :seon.task/failures {'demo/f-test [{:type :fail :message "m" :expected "1" :actual "2"}]}}])]
    (is (empty? (problems branch)) "no problem open: no unit, not an empty element")
    (is (str/includes? (apply str (map :html (problems red))) "red") "a red proof of its task, drawn as the report draws it")))

(deftest an-empty-part-sends-nothing
  ;; task seon.render/an-empty-part-draws-nothing: a component that draws nothing (own-problems with no
  ;; problem open) sends the model no part, not an element holding nil; one with a problem still does
  (let [[_ branch _] (paged-dbs)
        red (d/db-with branch [{:seon.task/id "demo/f" :seon.task/proofs #{'demo/f-test} :seon.task/failures {'demo/f-test [{:type :fail :message "m" :expected "1" :actual "2"}]}}])
        sent (fn [db] (keys (seon.fixtures/drawn-parts db (seon.ctx/build (seon.db/index) db (seon.db/eid db :seon.agent/key "a3") :task-demo-f))))
        own? #(= % "own-problems")]
    (is (seq (sent branch)) "the page still sends its other parts")
    (is (not-any? own? (sent branch)) "no problem open: no own-problems part")
    (is (some own? (sent red)) "a problem open: its part is sent")
    ;; the parent's hiccup still holds the nil child's marker: resolved replaces it by the child's output (nil),
    ;; which seon.web.html writes as nothing, so the whole layout holds no element or id for it
    (let [w (a3-walk branch)]
      (try (let [root @(:out w)]
             (is (not (str/includes? (h/html (r/resolved w root true)) "id=\"own-problems")) "the page's layout: no element, no id")
             (is (not (str/includes? (pr-str (r/resolved w root)) "\"own-problems\"")) "the model's resolved layout: a bare nil where it stood, no id and no element"))
           (finally (r/dispose-walk! w))))))

(deftest root-and-an-agent-are-one-chrome
  (let [[main branch eid] (paged-dbs)
        main (signed-in main [eid])
        st (root-state (proc-state main branch) main)
        root (#'p/page-value st :root)
        agent (#'p/page-value st [:chat :a3])
        ;; a list's units, each composed with the units nested in it (what the page shows of it)
        in (fn [l c] (filter #(= c (:container %)) (seon.fixtures/composed (:seon.render/units l))))
        top (fn [l] (remove :parent (:seon.render/units l)))
        input (fn [l] (first (filter #(= "p-input" (:id %)) (:seon.render/units l))))]
    (testing "root is an agent: its page and an agent's are each its lists in one chrome (seon.render.ui/page-chrome)"
      ;; the page (#p-page) is its layout's root unit, whose chat column (#p-chat) ends with the frame's caption and input
      (is (= #{"#p-page" "#p-chat" "#c-side" "#p-head" "#p-task" "#c-rows" "#p-rules" "#p-tiles"} (set (map :container (top root))))
          "root's: its agent's page (its tiles, its conversation) and, beside it, the system's own view (p-tiles)")
      (is (= #{"#p-page" "#p-chat" "#c-side" "#p-head" "#p-task" "#c-rows" "#p-rules"} (set (map :container (top agent))))
          "an agent's: its task's list above its conversation; no eval of the fixture has a render of its own, so no tiles")
      (is (= 1 (count (filter #(= "#p-page" (:container %)) (:seon.render/units root)))) "one page: the system walk's own root unit is not placed")
      (doseq [l [root agent] :let [by (into {} (map (juxt :id identity)) (:seon.render/units l))]
              u (:seon.render/units l) :when (:parent u) :let [p (by (:parent u))]]
        (is (some? p) (str (:id u) ": a nested unit's parent is a unit of the page"))
        (is (str/includes? (:html p) (str "id=\"" (subs (:container u) 1) "\"")) (str (:id u) ": its container is an element of its parent"))
        (is (str/includes? (:html p) (str "<!--s:" (:id u) "-->")) (str (:id u) ": its parent holds its slot"))))
    (testing "the agents list is the signed-in user's, the same on every page: root first, then what the user started"
      (is (= 2 (count (in root "#c-side")) (count (in agent "#c-side"))))
      (is (= (map :html (in root "#c-side")) (map :html (in agent "#c-side"))))
      (is (str/includes? (:html (first (in root "#c-side"))) "href=\"/root\"") "root's line links to its page")
      (is (str/includes? (:html (second (in root "#c-side"))) "href=\"/agent/a3\"") "a link to its page, by its key"))
    (testing "root's system view: its process, main's open problems and the evals sent to root, by their own renders; its input talks to root's session"
      (is (= 3 (count (in root "#p-tiles"))))
      (is (str/includes? (:html (first (in root "#p-tiles"))) "process"))
      (is (str/includes? (:html (second (in root "#p-tiles"))) "problems"))
      (is (str/includes? (:html (last (in root "#p-tiles"))) "sent to root"))
      (is (= [:seon.render/agent-input :root] [(:schema (input root)) (:id (:input (input root)))]) "a message to root's session, which starts the work"))
    (testing "an agent's focus: its REPL and messages at the top, live while root's view holds it in this boot"
      (is (= [:seon.render/agent-input {:id :a3 :live? true :held nil :focus? true :busy? true :talks? true :address :a3}] [(:schema (input agent)) (:input (input agent))]))
      (let [ctx-page (#'p/page-value st :a3)]
        (is (false? (:focus? (:input (input ctx-page)))) "@ctx's input takes no focus: a focused field at the page's end scrolls the page to its bottom")))
    (testing "a location naming no entity is that error, never an empty page; a branch that could not be read is shown as its error"
      (is (= :seon.render/no-location (:seon.error/type (#'p/page-value st 99999999))))
      (let [err (seon.contract/error :seon.writer/refused 'seon.writer/writer-proc "no branch :task-demo-f" {})
            l (#'p/page-value (-> st (update-in [:branches [:chat :a3]] dissoc :db) (assoc-in [:branches [:chat :a3] :error] err)) [:chat :a3])]
        (is (some #(str/includes? (str (:html %)) "no branch :task-demo-f") (:seon.render/units l)) "its error, by the error's render")
        (is (empty? (in l "#c-rows")) "no branch read: none of its rows")))
    (testing "an agent of an earlier boot is not live (root's view of this boot does not hold it)"
      (let [st2 (assoc st :main-db (d/db-with (:main-db st) [{:seon.system/id :root :seon.system/boot "b2"}]))]
        (is (str/includes? (str (#'p/page-value st2 eid)) "a3"))))
    (r/dispose-walk! (get-in st [:root-walk :w]))))

(defn my-member
  "A member of `my-list`, for a-render-places-its-own-regions: its number. See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:key :int]]]] :any]}
  [{n :key}] [:p (str "member " n)])

(defn my-list
  "A list in a container no frame region has (my-region), for a-render-places-its-own-regions. See docs/render.md#a-layout-places-its-lists."
  {:seon.render/list "my-region" :malli/schema [:=> [:cat :seon.render/opts] :any]}
  [_] [:section {:id "my-region"} [#'my-member {:key 1}] [#'my-member {:key 2}]])

(defn my-layout
  "A layout of its own, for a-render-places-its-own-regions: a heading and `my-list`. See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat :seon.render/opts] :any]}
  [_] [:div [:h1 "mine"] [#'my-list {}]])

(defn- page-html
  "The page html of a walk's units laid out by the frame (seon.render.ui/page-chrome), as a tab is sent it: its units run against
  nothing held and composed into the frame (seon.render.proc/view-html). See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat [:vector :map]] :string]}
  [units]
  (let [out (ui/page-chrome {:units units :view "v" :tab :chat} :v)
        us (:seon.render/units out)
        [view patch] (#'p/step-units (seon.db/index) nil us)
        [view _] (#'p/finish-patch view patch us)]
    (p/view-html {:views {:v (assoc view :skeleton (h/html (:seon.render/hiccup out)))}} :v)))

(deftest a-render-places-its-own-regions
  ;; docs/render.md#a-layout-places-its-lists: the root render's output is the page (its root unit), so a list it places in a container
  ;; no frame region has gets its members there; the frame holds only its bar, the agents list, the root unit and the input
  (let [a (seon.ratom/atom {:db/id 1})
        w (r/walker {:ref a :changes seon.ctx/changes} (constantly nil) [#'my-layout {}] (seon.db/index))]
    (try
      (r/flush! w nil)
      (let [html (page-html (r/placed-units w))]
        (is (re-find #"<section[^>]*id=\"my-region\"[^>]*>.*member 1.*member 2.*</section>" html) "its members, in its own region")
        (is (str/includes? html "<h1>mine</h1>") "the layout's own content is the page's")
        (is (= 1 (count (re-seq #"id=\"my-region\"" html))) "the region named once"))
      (finally (r/dispose-walk! w)))))

(deftest the-agent-page-holds-what-the-frame-held
  ;; docs/render.md#a-layout-places-its-lists: the agent's layout draws the regions page-chrome drew by hand (measured on main
  ;; b51de2877c, the chat page of a3 and root's page in this fixture), so the page holds the same elements and units
  (let [[main branch eid] (paged-dbs)
        st (root-state (proc-state (signed-in main [eid]) branch) (signed-in main [eid]))
        ids (fn [html] (into (sorted-set) (map second) (re-seq #" id=\"([^\"]+)\"" html)))
        [st1 _] (pass-view st [:chat :a3])
        [st2 _] (pass-view st1 :root)
        agent (ids (p/view-html st2 [:chat :a3]))
        root (ids (p/view-html st2 :root))
        ;; main's, b51de2877c
        agent0 #{"agent-state" "app" "c-rows" "c-side" "chat-ask" "chat-end" "cite-demo_2Ff_20docs_2Frender_2Emd_23no-clipping" "cite-demo_2Ff_20seon_2Erender_2Fhiccup"
                 "conversation-turn-1" "eval-e1" "eval-e2" "eval-e3" "eval-output-e1" "eval-output-e2" "eval-output-e3" "evaluation-e1" "evaluation-e2" "evaluation-e3"
                 "message-m-1" "model-call-view-b8ce12ca44ca5601" "p-agent-head" "p-caption" "p-head" "p-input" "p-rules" "p-task" "p-tiles" "page-rules" "status"
                 "step-back-a3-e1" "step-back-a3-e2" "step-back-a3-e3" "step-edit-a3-e1" "step-edit-a3-e2" "step-edit-a3-e3" "steps-1" "system-message" "task-view-demo_2Ff"
                 "turn" "u-agent-a3" "u-root-side"}
        ;; 2026-11-12: root's session names its task (request/root, as any request's agent does), so its page draws it as an agent's does:
        ;; the task, its cite, how it stands, its turn and the chat's ask
        root0 #{"chat-ask" "cite-request_2Froot_20docs_2Fagent_2Emd_23working-for-a-person" "status" "task-view-request_2Froot" "turn"
                "agent-a3" "agent-state" "agent-user" "app" "c-rows" "c-side" "eval-output-root-1" "eval-root-1" "evaluation-root-1" "p-agent-head" "p-caption" "p-head"
                "p-input" "p-rules" "p-task" "p-tiles" "page-rules" "problems-tile" "process-tile" "root-evals" "root-head" "stage-as" "stage-front" "system-message"
                "u-agent-a3" "u-root-side"}
        ;; what the frame's page-chrome drew by hand is now the layout's: its root (the page), its columns and the frame's slot for it
        ;; and the top bar's region for which system it is (seon.render.ui/system-source; this fixture's root holds no source, so it is empty)
        added #(remove (fn [id] (or (#{"p-page" "p-agents" "p-chat" "p-context" "p-system"} id) (str/starts-with? id "agent-") (str/starts-with? id "stage-"))) (clojure.set/difference % %2))]
    (is (= agent0 (clojure.set/intersection agent agent0)) "every element the agent's page held")
    (is (empty? (added agent agent0)) "and no other but its layout's own")
    (is (= root0 (clojure.set/intersection root root0)) "every element root's page held")
    (is (empty? (added root root0)) "and no other but its layout's own")
    (r/dispose-walk! (get-in st2 [:root-walk :w]))))

(deftest a-branchs-own-schema-property-is-read-from-its-index
  ;; docs/render.md#one-rendering-system-drawn-from-the-data: a schema's properties are read from the entity index alone (seon.render/props),
  ;; so a schema registered on a branch, as an agent registers one, is read whole there: its pattern as well as its render
  (let [reg (fn [db k form] (let [r (my.schema/registration db k form false)]
                              (when (seon.contract/error? r) (is false (str k " registers: " (pr-str r))))
                              (d/db-with db (:tx r))))
        db (-> (ts/test-db)
               (reg :lab.props/name :string)
               (reg :lab.props/thing-fact [:map {:seon.db/attributes true :seon.render/hiccup 'seon.render.ui/evaluation :seon.ctx/pattern [:db/id :lab.props/name]}
                                           [:lab.props/name :lab.props/name]]))
        ix (seon.program/index-of-db db)]
    (is (= 'seon.render.ui/evaluation (r/render-for ix :lab.props/thing-fact :seon.render/hiccup)) "its render, read from the index")
    (is (= [:db/id :lab.props/name] (r/schema-property ix :lab.props/thing-fact :seon.ctx/pattern)) "and its pattern, from the same index")))

(deftest every-page-says-which-system-it-is
  ;; docs/render.md#the-page: the system's source (root's :seon.system/source, as an index of the checkout writes it) is drawn by its
  ;; schema's render in the top bar of every page, an agent's and root's alike, from the system walk once
  (let [[main branch eid] (paged-dbs)
        src {:root "/Users/x/src/seon-next/.worktrees/frame" :commit "4a74777e2a0123456789abcdef0123456789abcd" :branch "jolt/frame"
             :tree "f3832a894ee0322bc9f1e079aeadb949b9c5c84b" :dirs ["src"] :files 3 :forms 9 :ms 1.5}
        main (d/db-with (signed-in main [eid]) [{:seon.system/id :root :seon.system/source src}])
        st (root-state (proc-state main branch) main)
        [st1 _] (pass-view st [:chat :a3])
        [st2 _] (pass-view st1 :root)
        ;; what follows the top bar's region, to the end of the bar
        line (fn [html] (when-let [i (str/index-of html "id=\"p-system\"")] (subs html i (or (str/index-of html "</nav>" i) (count html)))))]
    (doseq [[v html] [[[:chat :a3] (p/view-html st2 [:chat :a3])] [:root (p/view-html st2 :root)]]]
      (is (str/includes? (str (line html)) "frame · jolt/frame · <span class=\"commit\">4a74777e2a0123456789abcdef0123456789abcd</span>") (str v ": the checkout, its branch and its commit, in the top bar"))
      (is (= 1 (count (re-seq #"class=\"[^\"]*\bsource-line\b" html))) (str v ": once")))
    (r/dispose-walk! (get-in st2 [:root-walk :w]))))

(deftest a-turns-steps-say-what-each-was-for
  ;; docs/render.md#the-page: a turn's steps fold their code and output, and the fold's line says what each was for (its intent), so a
  ;; person reading the chat sees what the agent did, and a run that repeats a step reads as one
  (let [summary (fn [h] (second (re-find #"(?s)<summary>(.*?)</summary>" h)))
        two (h/html (ui/steps 3 [[:li "e1"] [:li "e2"]] ["Read the plan." "Write the test."]))
        none (h/html (ui/steps 4 [[:li "e3"]] []))]
    (is (str/includes? (summary two) "2 steps") "how many")
    (is (re-find #"Read the plan\..*Write the test\." (summary two)) "and each step's intent, in order, on the line")
    (is (not (str/includes? (summary two) "e1")) "never its code or output, which stay folded")
    (is (= "1 step" (summary none)) "a step with no intent names none")))

(deftest each-proof-state-says-what-helps-it
  ;; docs/render.md#how-the-work-stands: a proof with no verdict is told the one thing that helps its state (Data model, 2026-11-11: four
  ;; facts, never one word); a red proof shows every assertion it failed, its source and what it calls (the physics audit's high 1-3)
  (let [html (fn [s] (h/html (ui/status-hiccup {} s)))
        red (html {:proofs [{:test 'demo-test/r :status :fail :source "(deftest r (is (= 1 (f))) (is (= 2 (g))))"
                             :calls [{:var 'demo/f} {:var 'demo-test/helper :source "(defn helper [] 3)"}]
                             :failures [{:assert '(= 1 (f)) :got 0} {:assert '(= 2 (g)) :got 5}]}]})
        st (html {:proofs [{:test 'demo-test/d :status :not-run} {:test 'demo-test/p :status :deferred} {:test 'demo-test/u :status :unknown}]})]
    (is (str/includes? red "(= 1 (f))") "the first assertion that failed")
    (is (str/includes? red "(= 2 (g))") "and the second, never a count in its place")
    (is (not (str/includes? red "more assertion")) "no count of the rest")
    (is (not (str/includes? red "(deftest r")) "the test's own source is not repeated (owner, 2026-11-05)")
    (is (str/includes? red "`demo/f`") "what it calls")
    (is (str/includes? red "(defn helper [] 3)") "a helper of its own namespace, with its source")
    (let [explain (:seon.status/explain (seon.contract/properties :seon.ctx.status/proof-state))]
      (doseq [[k t] [[:not-run 'demo-test/d] [:deferred 'demo-test/p] [:unknown 'demo-test/u]]]
        (is (str/includes? st (str "<code>" t "</code>: ")) (str t ": its name, drawn as code"))
        (is (str/includes? st (first (str/split (get explain k) #"`"))) (str t ": then what its state means and what helps it, the schema's words"))))
    (is (str/includes? red (first (str/split (:fail (:seon.status/explain (seon.contract/properties :seon.ctx.status/proof-state))) #"`"))) "a red proof says what red means and what to do")
    (is (= 1 (count (re-seq #"<code>\(prove\)</code>" st))) "(prove) is offered, as code, for the proof not judged yet alone")))

(deftest every-proof-state-has-its-words
  ;; docs/render.md#how-the-work-stands: each state a proof is shown in carries its title and its explanation as data on its schema, so a
  ;; state added without words is red here, never a blank on the page
  (let [props (seon.contract/properties :seon.ctx.status/proof-state)
        members (seon.contract/enum-values :seon.ctx.status/proof-state)]
    (is (seq members) "the enum's members")
    (doseq [m members]
      (is (not (str/blank? (get (:seon.status/title props) m))) (str m ": a title"))
      (is (not (str/blank? (get (:seon.status/explain props) m))) (str m ": an explanation")))))

(deftest a-location-names-root-an-agent-or-an-entity
  (is (= [:root :root [:chat :a3] :a3 474] (mapv #'seon.web/view-of ["" "root" "a3" "a3/ctx" "474"])) "an agent's path is its chat; /debug every render of its ctx"))

(deftest a-branch-names-its-newest-agent
  ;; docs/render.md#the-page: a branch is an agent's durable name: its newest agent, of any boot, ended or
  ;; not, so a page opens from the store after the boot that made it is gone
  (let [db (d/db-with (ts/test-db) [{:seon.agent/key "old-boot/a4" :seon.agent/branch :main/task-x :seon.agent/status :ended}])
        old (seon.db/eid db :seon.agent/key "old-boot/a4")
        db2 (d/db-with db [{:seon.agent/key "new-boot/a1" :seon.agent/branch :main/task-x}
                           {:seon.agent/key "new-boot/a2" :seon.agent/branch :main/task-y}])
        newer (seon.db/eid db2 :seon.agent/key "new-boot/a1")]
    (is (= old (#'p/location db :main/task-x)) "an ended agent of an earlier boot, by its branch")
    (is (= old (#'p/location db [:chat :main/task-x])) "its chat")
    (is (= newer (#'p/location db2 :main/task-x)) "the newest agent of the branch")
    (is (nil? (#'p/location db2 :main/task-z)) "a branch no agent worked names none")))

(deftest a-page-keeps-what-it-read
  ;; a page's walk runs inside
  ;; d/with-reads and the proc indexes its reads by key, so a transaction finds the pages it
  ;; touched by its datoms' keys (seon.render.proc/touched), never by walking them
  (let [[main branch eid] (paged-dbs)
        [st _] (pass-view (proc-state main branch) [:chat :a3])
        e1 (:db/id (d/entity branch [:seon.eval/id "e1"]))
        touched (fn [db tx-data] (#'p/touched st (:tx-data (d/with db tx-data))))
        ;; an entity of main the page did not read, a form of the example program (ids from separate
        ;; speculative d/with calls can repeat in a test; on a store they are issued once); not a
        ;; namespace: a page part reads every namespace's file (seon.program/program-nss)
        unread (first (remove #(contains? (get-in st [:views [:chat :a3] :reads]) [:e %]) (d/q '[:find [?e ...] :where [?e :form/id]] main)))]
    (is (some? unread) "a form the page did not read")
    (is (seq (get-in st [:views [:chat :a3] :reads])) "the page holds what it read")
    (is (= #{[:chat :a3]} (touched main [{:db/id eid :seon.agent/status :idle}])) "the agent's own datom")
    (is (empty? (touched branch [{:db/id e1 :seon.eval/by {:seon.agent/key "user"}}]))
        "a branch's entity is drawn outside the read set: a move of the branch marks the page when root reads it (take-read; a-new-evaluation-is-one-append)")
    (is (empty? (touched main [[:db/retractEntity unread]])) "an entity the page did not read")))

(deftest a-transaction-touching-no-page-walks-nothing
  ;; main's commits come from
  ;; the writer ({:t :prev :ops :commit}, read with d/commit-as-db); the pass walks only the pages whose reads a commit's datoms touch, and
  ;; root's, which its walk marks (root-walk) when main moved
  (ts/test-branch! (fn [_ conn]
  (let [[main0 branch eid] (paged-dbs conn)
        main0 (do (d/transact conn {:tx-data (seon.test-store/root-tx {:seon.config.agent/max-running 4})}) (:db-after (d/transact conn {:tx-data (seon.system/root-agent-boot-tx @conn)})))
        o (volatile! {})
        st (#'p/pass (assoc (#'p/advance-main (dissoc (proc-state main0 branch) :main-db) main0) :conn conn) o)
        main (:main-db st)
        walked #(get-in % [:counts :walked] 0)
        commit (fn [st tx-data] (let [prev (:max-tx @conn) r (d/transact conn {:tx-data tx-data})]
                                  (first (#'p/render-proc st :main {:t (:max-tx (:db-after r)) :prev prev :commit (d/commit-id @conn)
                                                                    :ops (mapv (fn [d] [(if (:added d) :db/add :db/retract) (:e d) (:a d) (:v d)]) (:tx-data r))}))))]
    (is (= 2 (walked st)) "the first pass walks each watched page")
    (let [unread (first (remove #(some (fn [v] (contains? (get-in st [:views v :reads]) [:e %])) [:root [:chat :a3]]) (d/q '[:find [?e ...] :where [?e :ns/name]] main)))
          ;; a commit's datoms as the writer reports them, on an entity of main the page did not read
          st' (first (#'p/render-proc st :main {:t (:max-tx main) :prev (dec (:max-tx main)) :commit (d/commit-id main) :ops [[:db/add unread :ns/name 'unrelated.ns]]}))]
      (is (= (walked st) (walked st')) "a commit on an entity no page read walks nothing")
      (is (empty? (:last-walked st'))))
    (let [st' (commit st [{:db/id eid :seon.agent/status :idle}])]
      (is (= #{:root [:chat :a3]} (set (:last-walked st'))) "a commit on the agent walks its page, and root's, which lists it"))
    (testing "a view no tab watches leaves the index: no commit names it again"
      (let [[st' _] (#'p/render-proc st :from-web {:op :views :views #{:root} :tabs 1})]
        (is (empty? (#'p/touched st' [{:e eid :a :seon.agent/status :v :idle}])) "root's page reads no datom: its walk marks it")
        (is (not (some (fn [[_ vs]] (contains? vs [:chat :a3])) (:read-index st'))))))
    (r/dispose-walk! (get-in st [:root-walk :w]))))))

(deftest a-wanted-keyframe-is-answered-when-its-read-fails
  ;; docs/render.md#the-page: every wait ends, with its value or a typed error (docs/errors.md#nothing-crashes-and-nothing-hangs): a keyframe a tab asked for while the view could not
  ;; be drawn yet is answered when the read it waits on answers an error, by that error drawn, so the tab turns fresh and a later
  ;; read redraws it (task seon.web/a-tabs-first-keyframe-settles-on-its-own: bin/check idle waited ~30 min for a keyframe)
  (let [[main branch _] (paged-dbs)
        o (volatile! {})
        st (-> (proc-state main branch) (assoc :main-db nil :kf-wanted #{:root} :pending {9 :state}))
        err (seon.contract/error :seon.system/refused #'seon.render.proc/answered "root's view could not be read" {})
        st' (#'p/answered st o :state err)
        kfs (filter #(= :keyframe (:op %)) (:to-web @o))]
    (is (= [:root] (map :view kfs)) "the wanted keyframe is answered")
    (is (str/includes? (str (:html (first kfs))) "root's view could not be read") "by the error, drawn")
    (is (empty? (:kf-wanted st')) "and is no longer waited on")
    (is (#'p/walk? st' :root) "its view walks again at every pass until a read draws it")))

(deftest a-branch-commit-touches-its-page-by-its-datoms-diff
  ;; an agent's commit: its branch read again, and what moved is the datoms-diff of the held and
  ;; the new branch db; the page walks only when the diff touches its reads
  (let [[main branch _] (paged-dbs)
        main (d/db-with main (seon.test-store/root-tx {:seon.config.agent/max-running 4}))
        o (volatile! {})
        st (#'p/pass (proc-state main branch) o)
        k "a3"
        branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key k} "e4" (seon.task/sender "a3") {:seon.eval/call (seon.task/call-ref k 1) :seon.eval/code "(h)" :seon.eval/response {:handle 'result/e4 :value 1}}))
        answer (fn [st db] (#'p/answered (assoc-in st [:pending 9] [:branch :task-demo-f]) o [:branch :task-demo-f] {:db db}))]
    (is (not (some #{[:chat :a3]} (:last-walked (answer st branch)))) "the same branch db: nothing moved, nothing walked")
    (is (some #{[:chat :a3]} (:last-walked (answer st branch'))) "a new evaluation on it: the page walks")))

(deftest a-new-evaluation-is-one-append
  ;; an agent's commit on its branch: what moved (the datoms-diff) touches the list, and the agent's new turn
  ;; renders once, sent as one component placed after the others (docs/render.md#the-page)
  (let [[main branch _] (paged-dbs)
        o (volatile! {})
        ;; one tab, on a3's page
        st (assoc (branch-state main branch) :watch #{[:chat :a3]})
        [st1 _] (pass-view st [:chat :a3])
        k "a3"
        branch' (d/db-with branch (turn-2-eval-tx k "(h)"))
        st2 (#'p/answered st1 o [:branch :task-demo-f] {:db branch'})
        patch (first (filter #(and (= :patch (:op %)) (= [:chat :a3] (:view %))) (:to-web @o)))]
    (is (empty? (:frags patch)) "no held part changed")
    (is (= 1 (count (filter #(str/includes? (second %) "result/e4") (concat (:appends patch) (:afters patch))))) "one part, the new turn with its eval")
    (is (= 1 (count (concat (:appends patch) (:afters patch) (:prepends patch)))) "and nothing else sent")))

(deftest a-branchs-kept-walk-draws-what-a-cold-draw-does
  ;; docs/render.md#a-page-is-a-kept-walk: an agent's page drawn from its branch is one walk a branch, advanced by
  ;; each move; after a move its units are those a walk drawn cold at the new head draws, and a second view of
  ;; the agent draws from the same walk
  (let [[main branch _] (paged-dbs)
        o (volatile! {})
        st (assoc (branch-state main branch) :watch #{[:chat :a3] :a3})
        [st1 _] (pass-view st [:chat :a3])
        k "a3"
        branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key k} "e4" (seon.task/sender "a3") {:seon.eval/call (seon.task/call-ref k 1) :seon.eval/code "(h)" :seon.eval/response {:handle 'result/e4 :value 1}}))
        st2 (#'p/answered st1 o [:branch :task-demo-f] {:db branch'})
        cell (get-in st2 [:branch-reads :task-demo-f :walk])
        e (d/entity main [:seon.agent/key k])
        cold (let [w (draw (:tree (seon.ctx/build (seon.db/index) branch' (seon.db/eid branch' :seon.agent/key k) (:seon.agent/branch e))) seon.ctx/changes branch'
                             #'seon.render.ui/agent)]
               (try (mapv (juxt :id :html) (r/placed-units w)) (finally (r/dispose-walk! w))))]
    (is (some? (:w cell)) "the branch's read holds its walk")
    (is (= cold (mapv (juxt :id :html) (r/placed-units (:w cell)))) "after the move, what a cold draw at the head draws")
    (let [w (:w cell) [st3 _] (pass-view st2 :a3)
          html (p/view-html st3 :a3)]
      (is (identical? w (:w (get-in st3 [:branch-reads :task-demo-f :walk]))) "@ctx draws from the same walk")
      ;; owner, 2026-11-03: "the record is @ctx with each part that was rendered and the rendered part"
      (doseq [[id content] (r/placed-hiccup w (r/page-lists w))]
        (is (str/includes? html (str "data-part=\"" id "\"")) (str "@ctx pairs part " id))
        (is (str/includes? html (h/html (r/part-text content)))
            (str id ": what the model reads of it, as a request prints it"))))
    (testing "a move to a value that does not follow it (the eval gone again): what a cold draw there draws"
      (let [st5 (#'p/answered st2 o [:branch :task-demo-f] {:db branch})
            back (let [w (draw (:tree (seon.ctx/build (seon.db/index) branch (seon.db/eid branch :seon.agent/key k) (:seon.agent/branch e))) seon.ctx/changes branch
                                 #'seon.render.ui/agent)]
                   (try (mapv (juxt :id :html) (r/placed-units w)) (finally (r/dispose-walk! w))))]
        (is (= back (mapv (juxt :id :html) (r/placed-units (:w (get-in st5 [:branch-reads :task-demo-f :walk]))))))))
    (testing "the branch's read let go: its walk is disposed"
      (let [[st4 _] (p/render-proc st2 :from-web {:op :views :views #{} :tabs 0})]
        (is (nil? (get-in st4 [:branch-reads :task-demo-f])))))))

;; ---- a step costs what changed (task seon.render/a-step-costs-what-changed) ----

(defn- step-turn-tx
  "Turn i of a3's work after the fixture's, as production writes it (docs/turn.md#the-turn-is-a-number): its turn number moves with
  its model call (reply and all), an eval the call ran, and every third turn a message the agent took."
  [i]
  (let [k "a3" n (+ 2 i) id (str "x" i)]
    (concat
     [{:seon.agent/key k :seon.agent/turn n}
      {:seon.model-call/id (seon.lib/call-id k n) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n :seon.model-call/origin :model
       :seon.model-call/reply (str "Checking step " i ".") :seon.model-call/ms 1}]
     (seon.repl/record-tx {:seon.agent/key k} id (seon.task/sender "a3")
                          {:seon.eval/call-id (str "c-" id) :seon.eval/call (seon.task/call-ref k n) :seon.eval/code (str "(inc " i ")")
                           :seon.eval/response {:handle (symbol "result" id) :value {:step i}}})
     (when (zero? (mod i 3))
       (seon.task/message-tx (str "mm-" i) (seon.task/sender "owner") [k] (str "How is step " i "?") 1000)))))

(defn- step-dbs
  "a3's branch as of each of n further turns: [before turn 0, after turn 0, ... after turn n-1]."
  [n]
  (let [[_ branch _] (paged-dbs)]
    (vec (reductions (fn [db i] (d/db-with db (vec (step-turn-tx i)))) branch (range n)))))

(defn- step-walk
  "A kept walk of a3's page on db, as seon.render.proc/kept-walk holds one: {:w :a :c :at}."
  [db]
  (let [k "a3"
        c (seon.ctx/build (seon.db/index) db (seon.db/eid db :seon.agent/key k) :task-demo-f)
        at (volatile! db)
        a (seon.ratom/atom (:tree c))
        w (r/walker {:ref a :changes seon.ctx/changes} (fn [] @at) [#'seon.render.ui/agent {} (seon.ratom/ref-view a seon.ctx/changes)])]
    (flush! w nil)
    {:w w :a a :c c :at at}))

(defn- step-cold
  "What a walk drawn cold on db holds: [its units' ids and html, its ctx tree without the marks of its commit]."
  [db]
  (let [k "a3"
        c (seon.ctx/build (seon.db/index) db (seon.db/eid db :seon.agent/key k) :task-demo-f)
        w (draw (:tree c) seon.ctx/changes db #'seon.render.ui/agent)]
    [(try (mapv (juxt :id :html) (r/placed-units w)) (finally (r/dispose-walk! w))) (dissoc (:tree c) :t :commit)]))

(deftest a-kept-walk-steps-any-way
  ;; docs/render.md#a-page-is-a-kept-walk: a kept walk moved to any value of its branch, forward, back and across several turns (the
  ;; components of the turns between drawn, mounted or let go), draws what a walk drawn cold at that value draws, its units and its ctx
  (let [vs (step-dbs 5)
        kw0 (step-walk (nth vs 2))
        kw (reduce (fn [kw i]
                     (let [db (nth vs i)
                           kw' (#'p/advance-walk! kw db :task-demo-f identity)
                           [units tree] (step-cold db)]
                       (is (= units (mapv (juxt :id :html) (r/placed-units (:w kw')))) (str "its units at " i))
                       (is (= tree (dissoc (:tree (:c kw')) :t :commit)) (str "its ctx at " i))
                       kw'))
                   kw0 [3 4 3 1 5 0 4 4 2])]
    (r/dispose-walk! (:w kw))))

;; See docs/render.md#a-page-is-a-kept-walk
(deftest a-walk-kept-as-a-value-answers-what-a-cold-request-does
  ;; seon.render/walk moved to any value of the branch, forward, back and across several turns, by the datoms between, answers the
  ;; model's messages the request drawn cold there answers (seon.ctx.wire/render); the same value again runs nothing and answers the same
  (let [[db0 ix _ at] (#'seon.render-cost-test/session-of 2)
        vs (vec (reductions (fn [db i] (d/db-with db (#'seon.render-cost-test/session-eval (str "e" i) (inc i)))) db0 (range 2 7)))
        basis (fn [db] {:commit (d/commit-id db) :t (:max-tx db) :task "t/x" :turn 1 :max-turns 9 :origin :model :agent "a1"})
        msgs (fn [p] (mapv first (:seon.render/openai-compat (:answers p))))
        p0 (r/walk nil (at (nth vs 2)))
        [p db] (reduce (fn [[p db0] i]
                         (let [db (nth vs i)
                               p' (r/walk p (assoc (at db) :moved (seon.ctx/moved db0 db nil)))]
                           (is (= (:messages (seon.ctx.wire/render db (basis db) {:index ix})) (msgs p')) (str "its messages at " i))
                           [p' db]))
                       [p0 (nth vs 2)] [3 4 3 1 5 0 4 4 2])
        again (r/walk p (assoc (at db) :tree (:tree p) :moved []))]
    (is (not-any? #(str/includes? (str (:content %)) ":render-threw") (msgs p)) "no render threw")
    (testing "asked :resolve, the page as hiccup, what a cold draw at that db resolves, warm as cold"
      (let [cold (let [o (at db) w (draw (:tree o) seon.ctx/changes db (:root o) {:model-only true :index ix :outputs (:outputs o)})]
                   (try (r/resolved w @(:out w) false) (finally (r/dispose-walk! w))))
            p0' (r/walk nil (assoc (at (nth vs 3)) :resolve true))
            p1' (r/walk p0' (assoc (at db) :resolve true :moved (seon.ctx/moved (nth vs 3) db nil)))]
        (is (= cold (:hiccup p1')))))
    (is (= {} (:ran again)) "the same value again runs nothing")
    (is (identical? (:answers p) (:answers again)) "and answers what it answered")))

;; See docs/render.md#a-page-is-a-kept-walk
(deftest ^{:seon.test/estimate {:ticks 25000000}} a-page-drawn-by-walk-is-the-reactive-pages-units
  ;; estimate: 4 cold reactive page draws (~3M each) and 4 walks (one cold ~2.5M, three moves ~1M each) over a session of 2 to 6 turns
  ;; (the cost proof's world: an in-memory branch, no cited task to build); measured 18M on paged-dbs' world, whose 64M build it needs not
  ;; the render proc's page as a walk value (seon.render/walk, :units true): at any value of the branch, forward, back and across turns,
  ;; the units a cold reactive draw writes there, the page's rules among them
  (let [[db0 ix _ at] (#'seon.render-cost-test/session-of 2)
        vs (vec (reductions (fn [db i] (d/db-with db (#'seon.render-cost-test/session-eval (str "e" i) (inc i)))) db0 (range 2 7)))
        opts (fn [db] (assoc (at db) :model-only false :units true))
        cold (fn [db] (let [o (opts db) w (draw (:tree o) seon.ctx/changes db (:root o) {:index ix})]
                        (try (mapv (juxt :id :html) (r/placed-units w)) (finally (r/dispose-walk! w)))))
        p0 (r/walk nil (opts (nth vs 2)))]
    (is (= (cold (nth vs 2)) (mapv (juxt :id :html) (:units p0))) "drawn cold")
    (reduce (fn [[p db0] i]
              (let [db (nth vs i) p' (r/walk p (assoc (opts db) :moved (seon.ctx/moved db0 db nil)))]
                (is (= (cold db) (mapv (juxt :id :html) (:units p'))) (str "its units at " i))
                [p' db]))
            ;; forward one turn, back two, forward four
            [p0 (nth vs 2)] [3 1 5])))

;; See docs/render.md#a-page-is-a-kept-walk
(deftest ^{:seon.test/estimate {:ticks 15000000}} the-page-and-the-request-agree
  ;; estimate: 3 walks of a 2-to-4-turn session with both outputs and units (one cold ~2.5M, two moves ~1M each) and a scan of
  ;; their units' html per message (~100 messages x ~50 units, a string search each: ~2M)
  ;; one walk gives the page and the request (agreed with context, 11-13): each message the model is sent names the component that
  ;; answered it by the key :ran and the page use, so the request's reader joins a message to whether it ran, and every message's
  ;; component drawn for the page is on the page
  (let [[db0 _ _ at] (#'seon.render-cost-test/session-of 2)
        ;; each turn as production writes it: its eval and the agent's turn moved to it
        vs (vec (reductions (fn [db i] (d/db-with db (conj (#'seon.render-cost-test/session-eval (str "e" i) (inc i))
                                                           {:seon.agent/key "a1" :seon.agent/turn (inc i)})))
                            db0 (range 2 5)))
        opts (fn [db] (assoc (at db) :model-only false :units true))
        agree (fn [p what]
                (let [msgs (:seon.render/openai-compat (:answers p))
                      html (apply str (map :html (:units p)))
                      ks (map (comp :key second) msgs)]
                  (is (seq msgs) (str what ": the request has messages"))
                  (is (every? #(contains? (:cache p) %) ks) (str what ": every message names its component by its key"))
                  ;; a component drawn as the model's answer alone (:seon.render/as another output: wire's page message) has no element
                  (is (every? #(str/includes? html (str "id=\"" (r/key-id %) "\""))
                              (filter #(and (contains? (:ran p) %)
                                            (contains? #{nil :seon.render/hiccup} (get-in p [:cache % :opts :seon.render/as]))) ks))
                      (str what ": each component that ran for a message and is drawn for the page is on it"))))
        p0 (r/walk nil (opts (nth vs 2)))]
    (agree p0 "cold")
    (reduce (fn [[p db0] i]
              (let [db (nth vs i) p' (r/walk p (assoc (opts db) :moved (seon.ctx/moved db0 db nil)))]
                (agree p' (str "at " i))
                [p' db]))
            [p0 (nth vs 2)] [3 1])))

(deftest a-record-of-another-turn-reads-nothing-this-turn-read
  ;; docs/render.md#the-page: an agent's page is a component for each turn number, each reading its records through the index by
  ;; that number: a new eval at turn n draws turn n's component and its eval, and no other turn's, nor the list's
  (let [vs (step-dbs 5)
        db (nth vs 5)
        k "a3"
        kw (step-walk db)
        ran (fn [] (into {} (for [[f l] (r/ledger (:w kw))] [(str f) (+ (:miss l 0) (:same l 0))])))
        before (ran)
        ;; turn 3 (the agent's turn 5 is the newest): an eval of another hand, as the owner's from the page
        db' (d/db-with db (seon.repl/record-tx {:seon.agent/key k} "late" (seon.task/sender "user")
                                               {:seon.eval/call (seon.task/call-ref k 4) :seon.eval/code "(late)" :seon.eval/response {:handle 'result/late :value 1}}))
        _ (#'p/advance-walk! kw db' :task-demo-f identity)
        after (ran)
        ran-now (fn [f] (- (get after (str f) 0) (get before (str f) 0)))]
    (is (= 1 (ran-now `ui/conversation-turn)) "the turn the eval took draws again")
    (is (= 1 (ran-now `ui/evaluation)) "and the eval, new")
    (is (zero? (+ (ran-now `ui/conversation-list) (ran-now `ui/message))) "no other turn's records, nor the list, are read again")
    (r/dispose-walk! (:w kw))))

(deftest a-walk-made-before-its-agent-takes-the-agent
  ;; the browser check of 4bc612cf89: @ctx of a live agent was empty, its walk made at its first draw, before the
  ;; agent's first write to its branch, so its ctx held no entity and no patch could add one. A walk of another
  ;; entity than the branch holds is made again
  (let [[main branch _] (paged-dbs)
        o (volatile! {})
        k "a3"
        bare (d/db-with branch [[:db/retractEntity (seon.db/eid branch :seon.agent/key k)]])
        st (assoc (proc-state main bare) :watch #{:a3})
        [st1 _] (pass-view st :a3)
        st2 (#'p/answered st1 o [:branch :task-demo-f] {:db branch})
        html (p/view-html st2 :a3)]
    (is (not (str/includes? (p/view-html st1 :a3) "data-part=")) "no agent on its branch yet: no parts")
    (is (str/includes? html "data-part=") "the agent written: its parts")
    (is (= (seon.db/eid branch :seon.agent/key k) (get-in st2 [:branch-reads :task-demo-f :walk :c :tree :db/id])) "the walk holds the agent")))

(deftest a-branch-read-anew-renders-its-components-again
  ;; a branch read after an error has nothing held to diff against: what was cached from that
  ;; branch is rendered again, so a change made while it could not be read is shown
  (let [[main branch _] (paged-dbs)
        o (volatile! {})
        st (assoc (branch-state main branch) :watch #{[:chat :a3]})
        [st1 _] (pass-view st [:chat :a3])
        err (seon.contract/error :seon.writer/refused 'seon.writer/writer-proc "no branch :task-demo-f" {})
        st2 (#'p/answered st1 o [:branch :task-demo-f] err)
        branch' (d/db-with branch (seon.repl/record-tx {:seon.agent/key "a3"} "e4" (seon.task/sender "a3") {:seon.eval/call (seon.task/call-ref "a3" 1) :seon.eval/code "(h)" :seon.eval/response {:handle 'result/e4 :value 1}}))
        st3 (#'p/answered st2 o [:branch :task-demo-f] {:db branch'})]
    (is (not (str/includes? (p/view-html st1 [:chat :a3]) "result/e4")))
    (is (str/includes? (p/view-html st3 [:chat :a3]) "result/e4") "the eval, written while its branch was unread")))

(deftest the-agent-page-is-its-conversation
  ;; task seon.render/a-page-is-its-entitys-view (4b; design section 5): an agent's page is its
  ;; conversation, oldest first: a turn's header, an eval's text then its one collapsed line,
  ;; a message; the input under it
  (let [[main branch _] (paged-dbs)
        st (branch-state main branch)
        [st1 _] (pass-view st [:chat :a3])
        html (p/view-html st1 [:chat :a3])
        at #(str/index-of html %)]
    (is (< (at "check g") (at "it doubles") (at "f doubles?") (at "result/e3")) "in the order of the turn's number: the message, the reply, its eval's text, then the owner's eval")
    (is (< (at "id=\"c-rows\"") (at "id=\"p-input\"")) "the input under the conversation")
    (is (not (str/includes? html "Your own work:")) "no opening on the page: the model's request opens the agent's own work (seon.ctx.wire/agent-msgs)")
    (is (re-find #"<details [^>]*class=\"seon-eval\"" html) "an eval is a block that opens")
    (is (re-find #"<details [^>]*id=\"eval-e1\"" html) "the row is its handle's anchor, open or not")
    (is (re-find #"<summary[^>]*>.*f doubles\?.*result/e1" html) "folded: its message and its handle, never a copy of the eval")))

(deftest an-unchanged-unit-never-re-renders
  ;; each unit of a page is drawn only when its input changes (seon.render.proc/step-units): a commit on
  ;; the agent moves its head's input alone
  (let [[main branch _] (paged-dbs)
        [st1 _] (pass-view (branch-state main branch) [:chat :a3])
        made #(get-in % [:counts :rendered] 0)
        ;; its process ended: the head reads "finished" where a busy or idle one of a running task reads "working" (ui/standing),
        ;; and a commit whose head reads the same sends nothing
        r (d/with main (seon.task/agent-tx :a3 {:status :ended}))
        [st2 patch] (pass-view (assoc st1 :main-db (:db-after r)) [:chat :a3])]
    ;; its head, and its input (a process that ended is no longer busy: nothing to interrupt)
    (is (= 2 (- (made st2) (made st1))) "the head and the input rendered again, nothing else")
    (is (= #{"p-agent-head" "p-input"} (set (map first (:frags patch)))) "their fragments sent")
    (is (empty? (:prepends patch)))
    (testing "a pass with nothing touched renders nothing"
      (is (= (made st2) (made (first (pass-view st2 [:chat :a3]))))))))

(deftest the-sidebar-is-the-users-agents
  ;; docs/users.md#whats-on-screen: the agents list is the signed-in user's, drawn by the user's walk (its page's own list, read twice by
  ;; the frame): root, then what the user started, each its own component (the agent's entry), the same on every page
  (let [[main branch eid] (paged-dbs)
        main (signed-in main [eid])
        st (root-state (branch-state main branch) main)
        [st1 _] (pass-view st [:chat :a3])
        side (fn [st] (filter #(= "#c-side" (:container %)) (vals (get-in st [:views [:chat :a3] :units]))))]
    (is (= 2 (count (side st1))) "root's line, and an entry for what the user started")
    (is (str/includes? (p/view-html st1 [:chat :a3]) "href=\"/root\"") "root's line links to its page")
    (is (str/includes? (p/view-html st1 [:chat :a3]) "href=\"/agent/a3\"") "a link to its page, by its key: the name that survives a restart")
    (testing "a new agent the user started is a new entry in the list, its own component, appended; the others are not sent"
      (let [main' (d/db-with (:main-db st1) (into (seon.task/agent-tx :a4 {:status :busy}) (concat [{:seon.agent/key "a4" :seon.agent/boot "b1" :seon.agent/max-turns 40 :seon.agent/branch :task-a4}]
                                                                                                 (started-tx [:seon.agent/key "user"] [{:seon.agent/key "a4"}]))))
            st2 (#'p/advance-main st1 main')
            [st3 patch] (pass-view st2 [:chat :a3])
            added (concat (:appends patch) (:afters patch) (:prepends patch))]
        (is (= 3 (count (side st3))) "root's line, a3's entry, a4's entry")
        (is (= ["u-agent-a4"] (mapv (fn [[_ html]] (second (re-find #"id=\"([^\"]+)\"" html))) added)) "a4's entry, placed in the list, named apart as the user's walk names its elements")
        (is (str/includes? (second (first added)) "href=\"/agent/a4\"") "a link to its page")
        (is (empty? (:frags patch)) (str "nothing else of the list sent again: " (map first (:frags patch))))))
    (r/dispose-walk! (get-in st [:root-walk :w]))))

(deftest a-page-names-each-element-once
  ;; docs/render.md#a-layout-places-its-lists: a page's units are keyed by element id, and the frame puts the user's agents list beside the
  ;; page's own units: an agent the user started is on root's page twice (the list, root's sessions), so the user's walk names its elements
  ;; apart. Red before: agent-a3 twice, one unit overwriting the other in the view, a patch for one landing on the other
  (let [[main branch eid] (paged-dbs)
        main (d/db-with (signed-in main [eid]) (root-works-for [:seon.agent/key "user"]))
        st (root-state (branch-state main branch) main)
        [st1 _] (pass-view st :root)
        html (p/view-html st1 :root)
        ids (map second (re-seq #" id=\"([^\"]+)\"" html))]
    (is (= 2 (count (re-seq #"href=\"/agent/a3\"" html))) "a3's entry is drawn twice: the list and under the person on root's page")
    (is (empty? (for [[id n] (frequencies ids) :when (> n 1)] id)) (str "no element id twice: " (pr-str (for [[id n] (frequencies ids) :when (> n 1)] id))))
    (r/dispose-walk! (get-in st [:root-walk :w]))))

(deftest every-render-fits-the-hiccup
  ;; docs/render.md#the-hiccup-is-checked: what each render writes that the intermediate refuses (seon.render/misfits: :class, :style,
  ;; :wire marks, other qualified keys, attribute values that are no plain value), counted on the pages the test world draws (an agent's,
  ;; root's, the user's), pinned so a count only goes down: each later change moves a render's look to CSS and its count to none
  (let [[main branch eid] (paged-dbs)
        main (d/db-with (signed-in main [eid]) (root-works-for [:seon.agent/key "user"]))
        st (root-state (branch-state main branch) main)
        [st1 _] (pass-view st :root)
        [st2 _] (pass-view st1 [:chat :a3])
        ws (concat [(get-in st2 [:root-walk :w])] (keep #(some-> (:walk (val %)) :w) (:branch-reads st2)) (map :w (vals (:user-walks st2))))
        seen (apply merge-with #(merge-with + %1 %2) (map r/misfits-by-render ws))
        ;; root's session is in this world, so root's page draws the user's tree (the person, a3 under it): more agent renders, and root's nested sessions render, deleted, 3 classes fewer
        ;; measured 2026-11-11, the walk's own class on each component's root (its render's name) among them: it goes when data-render replaces it.
        ;; 2026-11-12: more drawn, no render's own count up: a3's page (every branch read keeps its walk now) and root's own task (root's
        ;; session names request/root, through which it reaches the person it works for)
        pinned '{seon.render.ui/agent {:class 23 :wire 4} seon.render.ui/agent-parts {:class 3} seon.render.ui/agent-state {:class 3 :wire 3} seon.render.ui/agents-list {:class 1} seon.render.ui/chat-ask {:class 6} seon.render.ui/chat-end {:class 1} seon.render.ui/cite {:class 2} seon.render.ui/conversation-list {:class 3} seon.render.ui/conversation-turn {:class 5} seon.render.ui/eval-output {:class 75 :qualified 36} seon.render.ui/evaluation {:class 21 :qualified 9} seon.render.ui/message {:class 2} seon.render.ui/model-call-view {:class 2} seon.render.ui/page-rules {:class 3} seon.render.ui/problems-tile {:class 1} seon.render.ui/process-tile {:class 4} seon.render.ui/root {:class 1} seon.render.ui/root-evals {:class 3} seon.render.ui/root-head {:class 2} seon.render.ui/root-side {:class 6} seon.render.ui/rules-list {:class 3} seon.render.ui/status {:class 2} seon.render.ui/system-message {:class 6 :wire 3} seon.render.ui/task-view {:class 10} seon.render.ui/turn {:class 2}}]

    (try (is (= pinned seen) (str "a count moved: write the new one here when it went down, never when it went up\n"
                                  (pr-str (into (sorted-map) seen))))
         (finally (doseq [w ws] (r/dispose-walk! w))))))

(deftest a-part-that-sorts-into-the-middle-goes-there
  ;; a new unit with held units on both sides of it in its container is placed after the one
  ;; before it, not at the container's end
  (let [u (fn [id] {:id id :input id :html (str "<li id=\"" id "\"></li>") :container "#c-rows"})
        held {:units (into {} (for [id ["a" "c"]] [id (u id)]))}
        [_ patch] (#'p/step-units (seon.db/index) held [(u "a") (u "b") (u "c")])]
    (is (= [["a" "b"]] (:afters patch)) "b after a")
    (is (empty? (:appends patch)))
    (is (empty? (:prepends patch)))))

(deftest a-reordered-unit-is-moved-not-its-container-sent
  ;; docs/render.md#a-layout-places-its-lists: a unit that changed place among the held ones of its container is taken out and put back by
  ;; its new neighbour; the others stay. Red before: a list's reorder moved nothing (the tab kept the old order), and a parent whose children
  ;; only changed order was sent whole with every child in it
  (let [li (fn [id] (str "<li id=\"" id "\">" id "</li>"))
        u (fn [id] {:id id :container "#c-rows" :input id :html (li id)})
        step (fn [held units] (let [[v patch] (#'p/step-units (seon.db/index) held units)] (#'p/finish-patch v patch units)))
        [v1 _] (step nil (mapv u ["a" "b" "c" "d"]))
        [v2 patch] (step v1 (mapv u ["a" "c" "d" "b"]))]
    (is (= [["b" "#d" "after" (li "b")]] (:moves patch)) "b alone moves, after d")
    (is (empty? (concat (:frags patch) (:appends patch) (:afters patch) (:prepends patch) (:removes patch))))
    (is (= {"#c-rows" ["a" "c" "d" "b"]} (:order v2)) "the order held is the new one")
    (let [[_ patch] (step v2 (mapv u ["b" "a" "c" "d"]))]
      (is (= [["b" "#a" "before" (li "b")]] (:moves patch)) "first: before the one now after it"))
    (testing "a parent whose children only changed order is not sent; the child that moved is"
      (let [parent (fn [ids] {:id "p" :container "#p-tiles" :input (vec ids) :html (str "<ul id=\"p\">" (apply str (for [i ids] (str "<!--s:" i "-->"))) "</ul>")})
            kid (fn [id] {:id id :container "#p" :parent "p" :input id :html (li id)})
            [v1 _] (step nil [(parent ["x" "y" "z"]) (kid "x") (kid "y") (kid "z")])
            [_ patch] (step v1 [(parent ["z" "x" "y"]) (kid "z") (kid "x") (kid "y")])]
        (is (empty? (:frags patch)) (str "the parent stays: " (:frags patch)))
        (is (= [["z" "#x" "before" (li "z")]] (:moves patch)))))
    (testing "a slot that moved into another element is no reorder: its parent is sent"
      (let [parent (fn [h] {:id "t" :container "#c-rows" :input h :html h})
            kid (fn [id] {:id id :container "#t" :parent "t" :input id :html (li id)})
            [v1 _] (step nil [(parent "<div id=\"t\"><div><!--s:a--></div><!--s:b--></div>") (kid "a") (kid "b")])
            [_ patch] (step v1 [(parent "<div id=\"t\"><div><!--s:b--><!--s:a--></div></div>") (kid "b") (kid "a")])]
        (is (= ["t"] (map first (:frags patch))) "the parent, whole")
        (is (empty? (:moves patch)) "its children come in it")))
    (testing "a move is sent as a remove, then a put back by its neighbour"
      (let [chunk (seon.web/chunk-of (assoc patch :view nil))]
        (is (re-find #"(?s)selector \[id=\"b\"\].*mode remove.*selector #d.*mode after.*elements <li id=\"b\">" chunk) chunk)))))

(defn- depth "Hiccup x's nesting." [x] (if (vector? x) (inc (reduce max 0 (map depth x))) 0))

(deftest every-view-of-an-agent-shows-one-read-of-its-branch
  ;; the branch review (2026-10-25, F2): each view read the agent's branch on its own, at its own t,
  ;; and one still on an older read could refill the cache the newer one had invalidated. One read
  ;; per branch, shown by every view of the agent at once
  (let [[main branch _] (paged-dbs)
        main (d/db-with main (seon.test-store/root-tx {:seon.config.agent/max-running 4}))
        st (-> (proc-state main branch) (assoc :watch #{:a3 [:debug :a3]} :branches {}) (assoc-in [:view :agents :a3 :t] 7))
        o (volatile! {})
        st (#'p/ask-branches st o)
        asks (filter #(= :branch-db (get-in % [:req :req :op])) (:to-api @o))
        newer (d/db-with branch [{:seon.eval/id "e9" :seon.eval/by {:seon.agent/key "a3"} :seon.eval/code "(f 9)" :seon.eval/response {:value 18}}])
        [st _] (#'p/render-proc st :answers {:tag (:tag (first asks)) :r {:db newer}})]
    (is (= 1 (count asks)) "one read for the two views")
    (is (identical? newer (get-in st [:branches :a3 :db])))
    (is (identical? newer (get-in st [:branches [:debug :a3] :db])) "both show it, from the one answer")))

(deftest a-units-change-rerenders-that-unit
  (let [[main branch _] (paged-dbs)
        st (proc-state main branch)
        pass (fn [st] (pass-view st [:chat :a3]))
        patch-ids (fn [patch] {:frags (set (map first (:frags patch))) :prepends (:prepends patch) :appends (mapv first (:appends patch)) :removes (:removes patch)})
        [st1 p1] (pass st)]
    (testing "the first pass renders every unit; a second with nothing changed renders and sends nothing"
      (is (= (count (:seon.render/units (#'p/page-value st [:chat :a3]))) (get-in st1 [:counts :rendered])))
      (let [[st2 p2] (pass st1)]
        (is (nil? p2))
        (is (= (get-in st1 [:counts :rendered]) (get-in st2 [:counts :rendered])))))
    (testing "the agent changed on main (a commit, as the writer reports it): its head re-renders, nothing else"
      (let [r (d/with main (seon.task/agent-tx :a3 {:status :ended}))
            [_ p] (pass (assoc st1 :main-db (:db-after r)))]
        (is (= #{"p-agent-head" "p-input"} (:frags (patch-ids p))) "its head, and its input (it is no longer busy)")
        (is (empty? (:prepends p)))))
    (testing "a new turn on its branch: one new component, placed after the rows the page holds; the others untouched"
      (let [k "a3"
            branch' (d/db-with branch (turn-2-eval-tx k "(h)"))
            [_ p] (pass (assoc-in st1 [:branches [:chat :a3] :db] branch'))]
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
              st1 (#'p/pass (-> st (update :branches dissoc [:chat :a3]) (assoc :waiting {8 [:chat :a3]})) o)]
          (is (empty? (filter #(= :page (:op %)) (:to-web @o))))
          (let [o (volatile! {})
                st2 (#'p/pass (assoc-in st1 [:branches [:chat :a3] :db] branch) o)
                page (first (filter #(= :page (:op %)) (:to-web @o)))]
            (is (str/includes? (:html page) "result/e1") "answered at once, drawn from its branch, never waiting on its process")
            (is (some #(= {:op :render :id :a3} (get-in % [:req])) (:to-api @o)) "its process is asked for its parts")))))
    (testing "a view no tab watches keeps nothing: its page, its branch read, its rows"
      (let [[st2 _] (#'p/render-proc st1 :from-web {:op :views :views #{:root} :tabs 1})]
        (is (nil? (get-in st2 [:views [:chat :a3]])))
        (is (nil? (get-in st2 [:branches [:chat :a3]])))))))

(defn- debug-state
  "The render proc's state holding agent a1's debug page: context-test's two model turns (each
  recorded the hash of what it sent), root's boot b whose agent a1 is, main and the branch the same db."
  [conn]
  (let [[db sent [e0 e1]] (#'seon.ctx.wire-test/sent-turns conn)
        db (d/db-with db [{:seon.system/id :root :seon.system/boot "b"} {:seon.agent/key "a1"}])]
    [{:rid 1 :pending {} :watch #{[:debug :a1]} :waiting {} :kf-wanted #{} :views {} :main-db db
      :branches {[:debug :a1] {:db db}} :view {:agents {}}}
     sent [e0 e1]]))

(defn- wire-text
  "The body's JSON a debug page's html shows whole (its data-wire pre), as text."
  [html]
  (some-> (re-find #"data-wire=\"body\"[^>]*>([^<]*)</pre>" html) second
          (str/replace "&quot;" "\"") (str/replace "&#39;" "'") (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&amp;" "&")))

(deftest the-debug-page-is-the-wire-request
  ;; task seon.render/the-debug-page-is-the-wire-request: /agent/{id}/context is a timeline of the
  ;; agent's requests; the one shown is the body its turn put on the wire, its JSON byte for byte; a
  ;; chosen turn does not change when the branch moves; latest is the turn to come while the run goes on
  (ts/test-branch!
    (fn [_ conn]
      (let [[st [r0 r1] [e0 e1]] (debug-state conn)
            db (get-in st [:branches [:debug :a1] :db])
            sent (fn [n msgs] (seon.model/request-body (seon.config/provider-at db db "demo/f") {:messages msgs :task "demo/f" :turn n}))
            page (fn [st at] (let [st (cond-> (update-in st [:tab [:debug :a1] :open] (fnil conj #{}) "d-turn-raw") at (assoc-in [:tab [:debug :a1] :at] at))
                                   [st' _] (pass-view st [:debug :a1])]
                               (p/view-html st' [:debug :a1])))
            h0 (page st e0) h1 (page st e1)]
        (is (= (clojure.data.json/write-str (sent 0 r0)) (wire-text h0)) "turn 0: the page's body is the bytes it sent")
        ;; docs/storage.md#collection: a turn's commit a sweep took is drawn as of its t, from the same records
        (let [b (:seon.model-call/basis (d/pull db [:seon.model-call/basis] e1))
              at (d/commit-as-db (:store db) (:commit b))]
          (is (= (:messages (seon.ctx.wire/wire-turn at b)) (:messages (seon.ctx.wire/wire-turn (d/as-of db (:max-tx at)) b)))
              "as of its t, the request is the one its commit makes")
          (is (= (:max-tx at) (:t (seon.problems/own (d/as-of db (:max-tx at)) (:e (first (d/datoms db :aevt :seon.agent/key))))))
              "and an agent's problems read there say that t"))
        (is (= (clojure.data.json/write-str (sent 1 r1)) (wire-text h1)) "turn 1: the same")
        (is (str/includes? (page (assoc-in st [:tab [:debug :a1] :open] #{"d-turn-o0"}) e1) "the system message") "each message, opened, beside the records that made it")
        (is (str/includes? h1 "I call f.") "a call's arguments as sent, its message in them")
        (let [in1 (ui/debug-input db e1 [e0 e1] false)
              roles (mapv :role (get-in in1 [:body :messages]))]
          (is (= false (first (:new in1))) "the system prompt was sent before: not new")
          (is (some true? (map (fn [r n] (and (= "tool" r) n)) roles (:new in1))) "the call's result is new since turn 0")
          (is (not-any? true? (:new (ui/debug-input db e0 [e0 e1] false))) "the first request marks nothing")
          (is (false? (:differs? in1)) "drawn again, the bytes the turn hashed")
          (let [other (d/db-with db [{:seon.model-call/id (seon.lib/call-id "a1" 2) :seon.model-call/sent-hash "0000000000000000"}])]
            (is (true? (:differs? (ui/debug-input other e1 [e0 e1] false))) "a hash that is not the redraw's: said")
            (is (str/includes? (page (assoc-in st [:branches [:debug :a1] :db] other) e1) "its bytes differ") "on the page")))
        (testing "a chosen turn stays as it was when the branch moves"
          (let [moved (d/db-with db [{:seon.eval/id "e9" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/call (seon.task/call-ref "a1" 2)
                                      :seon.eval/code "(f 9)" :seon.eval/response {:handle 'result/e9 :value 9}}])]
            (is (= h0 (page (assoc-in st [:branches [:debug :a1] :db] moved) e0)))))
        (testing "latest, while the agent's work goes on, is the turn to come, drawn at the head"
          (let [in (ui/debug-input db :latest [e0 e1] false)]
            (is (:to-come? in))
            (is (= 3 (:turn in)) "the next turn's request")
            (is (str/includes? (page st nil) "to come"))
            ;; the browser check of 2026-11-03: the agent records its own end on its branch (:seon.agent/outcome and
            ;; the turn it ended on), and an ended agent showed a turn to come
            (let [ended (d/db-with db [{:seon.agent/key "a1" :seon.agent/outcome :green :seon.agent/ended-turn 2}])
                  in (ui/debug-input ended :latest [e0 e1] false)]
              (is (not (:to-come? in)) "an agent that ended: nothing to come")
              (is (= 2 (:turn in)) "latest is its newest turn"))))
        (is (= [:debug :a1] (#'seon.web/view-of (java.net.URLDecoder/decode (ui/view-param [:debug :a1]) "UTF-8"))) "its feed names it, and reads it back")
        (is (= [:debug :a1] (#'seon.web/view-of "a1/debug")) "/agent/a1/debug")
        (testing "a turn whose call failed shows what it sent and its typed error"
          (let [db (d/db-with db [{:seon.model-call/id (seon.lib/call-id "a1" 1) :seon.model-call/error {:seon.error/type :seon.model/no-provider :seon.error/operation 'seon.model/complete
                                                                                :seon.error/message "no model provider"}}])
                html (page (-> st (assoc :main-db db) (assoc-in [:branches [:debug :a1] :db] db) (assoc-in [:tab [:debug :a1] :open] #{"d-turn-o2"})) e0)]
            (is (str/includes? html "no model provider") "its error, by the error's render")
            (is (str/includes? html "my.agent/describe") "beside what it sent: the record described, the turn's first generated eval")))))))

(deftest a-page-opened-before-its-first-turn-takes-it
  ;; the browser check of 4bc612cf89: a debug page opened at "no model turn yet" never showed the turns that
  ;; came (496 patches whose targets its tab did not hold): its strip and turn were its layout, not units. Now a
  ;; view's layout never moves on a turn (its units patch in place), and a view whose layout does move is sent whole
  (ts/test-branch!
    (fn [_ conn]
      (let [[st _ [e0 e1]] (debug-state conn)
            view [:debug :a1]
            db (get-in st [:branches view :db])
            none (d/db-with db [[:db/retractEntity e0] [:db/retractEntity e1]])
            at #(-> % (assoc :main-db %2) (assoc-in [:branches view :db] %2))
            [st0 _] (pass-view (at st none) view)
            [st1 msg] (pass-view (at st0 db) view)]
        (is (str/includes? (p/view-html st0 view) "no model turn yet"))
        (is (= :patch (:op msg)) "the first turn is a patch of the tab's own elements")
        (is (= (get-in st0 [:views view :skeleton]) (get-in st1 [:views view :skeleton])) "the layout did not move")
        (is (every? #(contains? (get-in st0 [:views view :units]) %) (map first (:frags msg))) "each element it replaces, the tab holds")
        (is (every? #(str/includes? (get-in st0 [:views view :skeleton]) (str "<!--s:" % "-->")) ["d-steps" "d-turn"]) "its slots are there before any turn")
        (is (= #{"d-steps" "d-turn"} (set (map first (:frags msg)))) "the patch replaces the strip and the turn")
        (is (str/includes? (p/view-html st1 view) "to come"))))))

(deftest a-patch-it-could-not-place-is-the-page-whole
  ;; a patch replaces only elements a tab holds: a page that was its error (no agent a3 on main yet) and becomes
  ;; the agent's page has units in no list (its caption) the tab never held, so the tab is sent the page whole
  (let [[main branch _] (paged-dbs)
        k "a3"
        none (d/db-with main [[:db/retractEntity (seon.db/eid main :seon.agent/key k)]])
        st (assoc (proc-state none branch) :watch #{[:chat :a3]})
        [st0 m0] (pass-view st [:chat :a3])
        [st1 m1] (pass-view (assoc st0 :main-db main) [:chat :a3])]
    (is (nil? m0))
    ;; the browser check of 686f441544: the page whole reached the tab, and Datastar found no #app to place it in
    (is (str/starts-with? (p/view-html st0 [:chat :a3]) "<div class=\"mx-auto flex max-w-screen-xl flex-col gap-3 p-3 sm:p-4\" id=\"app\"")
        "the error page is in the root every page has, #app")
    (is (re-find #"^<div [^>]*id=\"app\"" (:html m1)) "and so is the page that replaces it")
    (is (= :keyframe (:op m1)) "the page whole")
    (is (:whole m1) "to every tab of the view, none of which holds where the patch would go")
    (is (= (p/view-html st1 [:chat :a3]) (:html m1)) "as the tab would load it")))

(deftest a-chosen-turn-draws-once-and-latest-draws-on-a-move
  ;; task seon.render/the-debug-page-is-the-wire-request: a turn chosen is its eid, so a move of the branch
  ;; draws nothing of it; latest's input is the branch's commit, so a move draws it again, once
  (ts/test-branch!
    (fn [_ conn]
      (let [[st _ [e0 _]] (debug-state conn)
            moved #(assoc-in % [:branches [:debug :a1] :db]
                             (d/db-with (get-in % [:branches [:debug :a1] :db])
                                        [{:seon.eval/id "e9" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"}
                                          :seon.eval/code "(f 9)" :seon.eval/response {:handle 'result/e9 :value 9} :seon.eval/call (seon.task/call-ref "a1" 2) :seon.eval/call-id "c9"}]))
            drawn (fn [st] (let [[st1 _] (pass-view st [:debug :a1]) [st2 _] (pass-view (moved st1) [:debug :a1])]
                             (- (get-in st2 [:counts :rendered] 0) (get-in st1 [:counts :rendered] 0))))]
        (is (zero? (drawn (assoc-in st [:tab [:debug :a1] :at] e0))) "a chosen turn: nothing drawn again")
        (let [[st' _] (pass-view (assoc-in st [:tab [:debug :a1] :at] 999999) [:debug :a1])]
          (is (str/includes? (p/view-html st' [:debug :a1]) "is no request of this branch: latest is shown") "a turn chosen that is gone is said, never shown as latest in silence"))
        (is (= 1 (drawn st)) "latest: its request, once")))))

(deftest stepping-the-timeline-draws-the-turn-on-a-still-branch
  ;; the browser check of 2026-11-03: a step (/at) on a branch that no longer moves (its agent ended) drew
  ;; nothing, as a view walks only when its dbs or its tab's choices move, and :at was none of those.
  ;; A tab's choices are one map a view (:tab), read whole by what decides a walk
  (ts/test-branch!
    (fn [_ conn]
      (let [[st _ [e0 _]] (debug-state conn)
            view [:debug :a1]
            [st _] (pass-view (update st :watch (fnil conj #{}) view) view)
            step (fn [st turn] (let [[st' out] (p/render-proc st :from-web {:op :at :view view :turn turn})]
                                 [st' (some #(when (= view (:view %)) %) (:to-web out))]))
            [st1 patch] (step st e0)]
        (is patch "the step sends the page a patch")
        (is (str/includes? (p/view-html st view) "what it would send now") "on latest, the turn to come")
        (is (not (str/includes? (p/view-html st1 view) "what it would send now")) "a step shows the turn chosen")
        (is (str/includes? (p/view-html (first (step st1 :latest)) view) "what it would send now") "and latest again")))))

(deftest the-html-fallback-folds-only-what-nests
  ;; the owner, 2026-10-23, on the default html: a fold and its "map 12 items, depth 0" header on
  ;; every collection was noise. The value printed and a collection of scalars are inline; a
  ;; nested collection that holds collections folds, saying what it holds
  (let [flat (h/html (r/hiccup {:seon.render/index (seon.db/index)} {:demo/id "f" :demo/proofs ['demo-test/f-test]}))
        nested (h/html (r/hiccup {:seon.render/index (seon.db/index)} {:demo/id "f" :rows [{:a 1} {:a 2}]}))]
    (is (not (str/includes? flat "<details")) "a map of scalars and a vector of one symbol: no fold")
    (is (not (str/includes? flat "items, depth")))
    (is (str/includes? flat "demo-test/f-test") "the value is all there")
    (is (= 1 (count (re-seq #"<details" nested))) "only the vector of maps folds")
    (is (str/includes? nested "vector 2 items"))))

(deftest an-eval-collapses-to-its-reference
  ;; root, 2026-10-24: a collapsed eval is its code, its time and its handle (seon.render.ui/evaluation): never
  ;; a copy of its value or its cost line, which open under it
  (let [[main branch eid] (paged-dbs)
        k (:seon.agent/key (d/pull main [:seon.agent/key] eid))
        branch (d/db-with branch (seon.repl/record-tx {:seon.agent/key k} "e9" (seon.task/sender "a3")
                                                      {:seon.eval/call (seon.task/call-ref k 1) :seon.eval/code "(+ 2 2)" :seon.eval/response {:handle 'result/e9 :ms 0.2 :ticks 900}}))
        e9 "evaluation-e9"
        closed (component-html branch e9)]
    (is (some? closed) "the eval, a component of the page")
    (is (str/includes? closed "(+ 2 2)") "its code's first line")
    (is (str/includes? closed "0.20 ms") closed)
    (is (str/includes? closed "result/e9") "its handle")
    (is (not (str/includes? closed ">4<")) "not its value: that opens under it")
    (is (not (str/includes? closed "ticks")) "and no cost line twice")))

(defn- eval-html
  "The html of a3's eval e (id \"e<n>\"): its unit as the page draws it, from a branch recording an eval whose response is resp."
  [id code resp]
  (let [[_ branch0 eid] (paged-dbs)
        k (:seon.agent/key (d/pull (first (paged-dbs)) [:seon.agent/key] eid))
        branch (d/db-with branch0 (seon.repl/record-tx {:seon.agent/key k} id (seon.task/sender "a3")
                                                       {:seon.eval/call (seon.task/call-ref k 1) :seon.eval/code code :seon.eval/response resp}))
        unit (str "evaluation-" id)]
    (component-html branch unit)))

(deftest an-evals-warning-is-drawn-with-its-value
  ;; a task merged having defined its proof through eval: the model's text said so and the page did not
  (is (str/includes? (eval-html "e24" "(eval '(defn g []))" {:handle 'result/e24 :value 1 :warnings [{:seon.repl/finding :not-program :seon.repl/fix "it will not merge"}]})
                     "it will not merge")))

(deftest an-evals-printed-output-is-drawn-with-its-value
  ;; owner, 2026-11-05: (clojure.repl/source x) showed "→ nil" though the response held the source under :out
  ;; (seon.repl/response keeps *out* as seon.print/code and *err* under :err). The eval's row draws both, by the
  ;; one render and the value's budget; a nil value beside output is not drawn; a real nil with none still is
  (let [src "(defn twice [x]\n  (* 2 x))"
        printed (eval-html "e20" "(clojure.repl/source twice)" {:handle 'result/e20 :value nil :out (seon.print/code src)})
        silent (eval-html "e21" "(identity nil)" {:handle 'result/e21 :value nil})
        both (eval-html "e22" "(do (println \"hi\") 7)" {:handle 'result/e22 :value 7 :out (seon.print/code "printed-out\n") :err (seon.print/code "warned-err\n")})
        long-out (str/join "\n" (map #(str "line-" %) (range 3000)))
        cut (eval-html "e23" "(dotimes [i 3000] (println i))" {:handle 'result/e23 :value nil :out (seon.print/code long-out)})]
    (is (str/includes? printed "(defn twice [x]") "the printed source is drawn")
    (is (str/includes? printed "(* 2 x)"))
    (is (not (str/includes? printed "→")) "a nil value beside output draws no arrow")
    (is (not (str/includes? printed "nil")) printed)
    (is (str/includes? silent "nil") "a real nil with no output is still shown")
    (is (str/includes? both "printed-out") "output beside a value")
    (is (str/includes? both "warned-err") "and its stderr, under :err")
    (is (str/includes? both ">7<") "the value is in the step, shown once it is opened")
    (is (and (str/includes? cut "line-0") (not (str/includes? cut "line-2999"))) "output over the value budget is cut")
    (is (< (count cut) 6000) (str "cut by the value's budget: " (count cut) " chars"))
    (is (str/includes? cut "(subs (:seon.print/code (:out result/e23))") "and the cut says how to read the rest")))

(deftest the-repl-input-takes-focus-only-on-the-chat
  ;; owner, 2026-11-05: @ctx opened scrolled to its very bottom. The REPL's textarea is the page's last element and was
  ;; autofocus on every tab: a browser scrolls a focused field into view as the page loads. The chat, which follows its
  ;; tail, takes it; @ctx does not (and an agent with no process has no field at all)
  (let [html (fn [m] (h/html (ui/agent-input {:unit "p-input"} (merge {:id :a3 :live? true :held nil} m))))]
    (is (str/includes? (html {:focus? true}) "autofocus"))
    (is (not (str/includes? (html {:focus? false}) "autofocus")))
    (is (not (str/includes? (html {}) "autofocus")) "no word of it, no focus")
    (is (str/includes? (html {:focus? false}) "<textarea") "the field is still there")
    (is (not (str/includes? (html {:live? false :focus? true}) "<textarea")) "no process: no field")))

(deftest the-debug-tabs-turn-shows-its-model-and-time
  ;; docs/render.md#the-debug-page-is-the-wire-request: a turn on the debug tab says which model answered it and how long the call took,
  ;; the two facts a model call keeps (the Data model session's D1: they had no reader; red before: neither was drawn)
  (let [html (h/html (ui/debug-turn {:unit "d-turn"} {:view [:debug :a1] :turn 3 :agent "a1" :origin :model :body {:messages []} :new []
                                                       :usage {:seon.model-call/tokens 120 :seon.model-call/ms 2345 :seon.model-call/model "deepseek-chat"}}))]
    (is (str/includes? html "deepseek-chat · 2345 ms") html)))

(deftest roots-context-is-its-debug-page
  ;; root, 2026-10-26: root's page and an agent's both toggle to what their model reads; root's debug page shows the
  ;; context the render proc holds (its :context, the system's value printed), shown whole, never computed again for the page
  (let [l (#'p/debug-value {:context {:text "{:seon-root \"the whole system\"}" :tokens 7}} [:debug :root])
        u (some #(when (= "d-context" (:id %)) %) (:seon.render/units l))]
    (is (= :seon.render/root-context (:schema u)))
    (is (str/includes? (h/html (r/hiccup {:seon.render/index (seon.db/index) :schema (:schema u) :unit "d-context"} (:input u))) "the whole system"))
    (is (str/includes? (h/html (:seon.render/hiccup l)) "href=\"/\"") "the toggle back to its conversation")
    (is (nil? (#'p/debug-value {} [:debug :root])) "nothing held yet: no page")))

;; docs/render.md#the-debug-page-is-the-wire-request: root's session is an agent with model turns, so its debug page is the
;; timeline of its requests as any agent's is, beside the system's context; before, it drew the context alone
;; (a real turn, 2026-11-12: ten requests no page showed)
(deftest roots-debug-page-shows-its-sessions-requests
  (ts/test-branch!
    (fn [_ conn]
      (let [[st _ [e0 e1]] (debug-state conn)
            db (d/db-with (:main-db st) [{:seon.agent/key "root" :seon.agent/branch :main/task-request-root}])
            st (assoc st :main-db db :branches {[:debug :root] {:db db}} :context {:text "{:seon-root \"the whole system\"}" :tokens 7})
            l (#'p/debug-value st [:debug :root])
            ids (set (map :id (:seon.render/units l)))]
        (is (contains? ids "d-context") "the system's context, as before")
        (is (contains? ids "d-turn") "and its session's request, as any agent's")
        (is (= [e0 e1] (mapv :e (:turns (:input (some #(when (= "d-steps" (:id %)) %) (:seon.render/units l)))))) "each of its requests, in order")))))

(deftest roots-page-reads-in-words
  ;; root, 2026-10-24: root is an agent; its head, problems and work read as a person reads them,
  ;; each problem one line by its own :html view
  (let [row {:class :error :count 3 :task "demo/f" :message "boom" :answered-by ["demo/fix"]
             :producer 'demo/f :owner-ns 'demo :evidence {:kind :error} :proofs [] :at-ms nil}]
    (is (= "<p class=\"text-xs\">error ×3 demo/f: boom (answered by demo/fix)</p>" (h/html (ui/problem-row {} row))))
    (let [html (h/html (ui/problems {:seon.render/index (seon.db/index)} {:t 1 :classes {:error {:n 1 :rows [(dissoc row :class)]} :red {:n 0 :rows []}}}))]
      (is (str/includes? html "<summary class=\"cursor-pointer\" title=\"error\">errors: 1</summary>"))
      (is (str/includes? html "error ×3 demo/f") "its rows, each by the row's view")
      (is (not (str/includes? html ">red 0<")) "a class with no rows is left out"))
    (is (= ["none open"] (re-seq #"none open" (h/html (ui/problems {:seon.render/index (seon.db/index)} {:t 1 :classes {}})))))
    (testing "on root's page a closed class renders no rows; the tab opens it (/open)"
      (let [r {:t 1 :classes {:error {:n 1 :rows [(dissoc row :class)]}}}]
        (is (not (str/includes? (h/html (ui/problems {:seon.render/index (seon.db/index)} (assoc r :open #{}))) "boom")))
        (is (str/includes? (h/html (ui/problems {:seon.render/index (seon.db/index)} (assoc r :open #{"pc-error"}))) "boom"))
        (is (str/includes? (h/html (ui/problems {:seon.render/index (seon.db/index)} (assoc r :open #{}))) "unit=pc-error") "its toggle"))))
  (let [html (h/html (ui/root-quiet {}
                             {:seon.system/id :root :seon.system/boot "b1" :seon.system/generation {:commit "abc" :ms 1}
                              :seon.system/quiescent {:t 9 :root-causes [{:task "demo/f" :cause "red proof" :blocks ["demo/g"]}]}}))]
    (is (str/includes? html "1 root cause when it went quiet"))
    (is (str/includes? html "demo/f: red proof · blocks 1") "each cause one line")
    (is (not (str/includes? html "#:seon")) "nothing printed raw")))

(deftest an-eval-row-renders-its-value-as-structure
  ;; owner, 2026-10-25 (the value view): a value recorded as data is drawn from its data, a map as
  ;; rows and code as code, not as its printed text; the row's id is its handle's, which links reach
  (let [src "(deftest t\n  ;; why\n  (is (= 2 (f 1))))"
        rec {:seon.eval/code "(my.task/status)" :seon.eval/response {:handle 'result/e157 :ms 2.5 :value {:proofs [{:test 'demo-test/t :status :fail :source (seon.print/code src)}
                                                                                                                                  {:test 'demo-test/u :status :pass :source (seon.print/code "(deftest u)")}]}}}
        html (h/html (ui/evaluation {:seon.render/index (seon.db/index)} (assoc rec :seon.eval/by {:seon.agent/key "outside"})))
        held (h/html (ui/evaluation {:seon.render/index (seon.db/index)} {:seon.eval/code "(Object.)" :seon.eval/response {:handle 'result/e2 :value (seon.contract/plainly (Object.))}}))]
    (is (str/includes? html "seon-print-grid") "the value, a map, as rows")
    (is (str/includes? html "<table class=\"seon-print-table\"") "its proofs, maps sharing keys, as a table")
    (is (str/includes? html ";; why") "the source verbatim, its comment as written")
    (is (not (str/includes? html "\\\"")) "no escaped string literal")
    (is (not (str/includes? html ":ms")) "the cost is on its own line, not in the value")
    (is (str/includes? held "java.lang.Object") "a value that is no data shows what it was, as data")))

(deftest an-agents-page-draws-no-tiles
  ;; docs/render.md#the-page: an agent's page places the task's list and the conversation, so a value with a render of its own is drawn where it was answered, and nothing draws it twice
  (let [[_ branch _] (paged-dbs)
        w (a3-walk branch)]
    (try
      (is (not-any? #(= "#p-tiles" (:container %)) (seon.fixtures/page-units w)))
      (is (some #(= "#c-rows" (:container %)) (seon.fixtures/page-units w)) "its conversation is")
      (finally (r/dispose-walk! w)))))

(deftest a-page-an-agent-built-renders-nested
  ;; owner, 2026-10-25: an agent's hiccup renders as HTML. It is the value's own :seon.render/hiccup
  ;; key (a render already made), wherever the value sits
  (let [page [:section [:h2 "agents"] [:ol [:li "a1 busy"]]]
        html (h/html (r/hiccup {:seon.render/index (seon.db/index)} {:screen {:seon.render/hiccup page} :n 1}))]
    (is (str/includes? html "<section><h2>agents</h2><ol><li>a1 busy</li></ol></section>") html)
    (is (str/includes? (:text (seon.print/print-value {:screen {:seon.render/hiccup page} :n 1} {:render #(@#'r/nested-hiccup (seon.db/index) %)}))
                       "[:section [:h2 \"agents\"]")
        "a value's text names its hiccup as the data it is")))

(deftest a-schema-form-prints-as-data-and-a-call-that-throws-is-written-in-its-place
  ;; docs/failure-points.md (a component's render): an agent registered :lab.ctx/steps, and the page printed
  ;; its form, whose properties map {:seon.render/hiccup lab.ui/steps} was taken for a value asking for that
  ;; render: steps was called with it, threw while the HTML was written (after render!), and the agent died
  (let [form '[:vector {:seon.render/hiccup seon.render.ui/debug-turn} :string]
        html (h/html (r/hiccup {:seon.render/index (seon.db/index)} {:seon.schema/key :lab.ctx/steps :seon.schema/definition form}))]
    (is (str/includes? html "seon.render.ui/debug-turn") "the properties print as the data they are")
    (is (not (str/includes? html "threw")) "and no render is called with them"))
  (let [html (h/html [:div [:p "before"] [#'ui/debug-turn {} {:body 1}] [:p "after"]])]
    (is (str/includes? html "seon.render.ui/debug-turn threw:") html)
    (is (and (str/includes? html "before") (str/includes? html "after")) "the page around it is written")))

(deftest a-cut-opens-its-path-in-the-row
  ;; the value view's step 3: a cut on the page is a button naming the form that reads its rest; the
  ;; row, given that form opened (/open), renders what it reads from the record, under the form as
  ;; the button that closes it, so it shows what the cut left
  (let [body (fn [n] (str/join "\n" (repeat 50 (str "  (is (= " n " (f " n ")))"))))
        proof (fn [n] {:test (symbol "demo-test" (str "t" n)) :source (seon.print/code (str "(deftest t" n "\n" (body n) ")"))})
        rec {:seon.eval/code "(my.task/status)" :seon.eval/response {:handle 'result/e5 :value {:proofs (mapv proof (range 1 5))}}}
        shown (fn [html] (count (filter #(str/includes? html (body %)) (range 1 5))))
        closed (h/html (ui/evaluation {:seon.render/index (seon.db/index)} rec))
        path (some-> (re-find #"class=\"seon-print-cut\"[^>]*? data-seon-form=\"([^\"]*)\"" closed) second (str/replace "&amp;" "&"))
        opened (when path (h/html (ui/evaluation {:seon.render/index (seon.db/index) :opened #{path}} rec)))]
    (is path (str "four sources over the value's budget: a cut, a toggle naming the form that reads its rest: "
                  (shown closed) " shown; " (pr-str (re-seq #"seon-print-(?:cut|elision)[^<]{0,160}" closed))))
    (is (< (shown closed) 4) "closed, a source is left out")
    (is (> (shown opened) (shown closed)) "opened, what its form reads renders: what the cut left shows")
    (is (str/includes? closed "&amp;open=true") "the cut only opens: a re-render cannot post its close")
    (is (str/includes? opened "&amp;open=false") "the opened form is the button that closes it")))

(deftest every-sent-part-names-its-record
  ;; root, 2026-10-26: a part kind with no clause in the debug page's source line made every pass
  ;; of the render proc throw, so no page answered; each ref kind seon.ctx.wire/request documents names its record
  (doseq [ref [[:section 397 536870918] [:reply "b/a1/3"] [:eval "e3"] [:message "m1"] [:example "example/abc" 12] [:session "demo/g#1"] [:page 3]]]
    (is (string? (#'ui/source ref)) (pr-str ref))))

(deftest a-debug-page-before-a-model-turn-answers
  ;; a page of no units (an agent's debug page before its first model turn) is held with none and answered:
  ;; it had no :units, and composing it threw out of the pass, so no page answered
  (let [db (reduce d/db-with (ts/test-db)
                   [[{:seon.system/id :root :seon.system/boot "b1"}] (seon.task/agent-tx :a1 {:status :busy})])
        debug [:debug :a1]
        o (volatile! {})
        st {:rid 1 :pending {} :watch #{debug} :waiting {1 debug} :kf-wanted #{} :new-events [] :events [] :views {}
            :main-db db :view {:agents {}} :branches {debug {:db db :read [:b]}}}
        st' (#'p/pass st o)
        page (some #(when (= 1 (:k %)) (:html %)) (:to-web @o))]
    (is (str/includes? (str page) "no model turn yet") "its page says there is no turn")
    (is (= #{"d-steps" "d-turn"} (set (keys (get-in st' [:views debug :units])))) "held with its strip and its turn's place, no turn in it")))

(deftest a-view-that-throws-answers-its-page-with-the-error
  ;; root, 2026-10-26: a render that threw stopped every pass of the render proc, and every page
  ;; waited forever. A view renders on its own: one whose render throws answers its own page with
  ;; the error and is recorded as a problem, and a second page renders through the same pass
  (let [k "a1"
        ;; a db that holds no system prompt: the request a turn made again needs it, and making it throws
        db0 (reduce d/db-with (ts/test-db)
                   [(seon.test-store/root-tx {:seon.system/boot "b1" :seon.config.agent/max-running 4})
                    (seon.task/agent-tx :a1 {:status :busy})
                    (seon.repl/record-tx {:seon.agent/key k} "e1" (seon.task/sender "a1") {:seon.eval/call-id "c-e1" :seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/e1 :value 3}})
                    [{:seon.agent/key k :seon.agent/turn 1}
                     {:seon.model-call/id (seon.lib/call-id k 1) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n 1 :seon.model-call/origin :model
                      :seon.model-call/tokens 100 :seon.model-call/forms 1 :seon.model-call/ms 1}]])
        db (d/db-with db0 [{:seon.model-call/id (seon.lib/call-id k 1) :seon.model-call/basis {:commit (d/commit-id db0) :task "t/x" :turn 1 :max-turns 9 :origin :model :agent "no/such"}}])
        debug [:debug :a1]
        o (volatile! {})
        st {:rid 1 :pending {} :watch #{debug [:chat :a1]} :waiting {1 debug 2 [:chat :a1]} :kf-wanted #{} :new-events [] :events [] :views {}
            ;; a1's process ended: root draws its page from its branch, in the same pass
            :main-db db :view {:agents {:a1 {:status :ended}}}
            :branches {debug {:db db :read [:task-x]} [:chat :a1] {:db db :read [:task-x]}}}
        st' (#'p/pass st o)
        pages (into {} (for [m (:to-web @o) :when (= :page (:op m))] [(:k m) (:html m)]))]
    (is (= #{1 2} (set (keys pages))) "both pages are answered: neither waits on the other's render")
    (is (str/includes? (str (pages 1)) "seon.render/view-defect") "the debug page is its error")
    (is (str/includes? (str (pages 1)) "seon.ctx/agent-ctx invalid-input") "it says what threw: a turn whose agent the db lacks")
    (is (str/includes? (str (pages 2)) "c-rows") "the agent's own page renders through the same pass")
    (is (some #(and (= :error (:op %)) (= :seon.render/view-defect (get-in % [:data :seon.error/type]))) (:to-api @o))
        "recorded as the problem it is: the api's proc error")
    (is (empty? (:waiting st')) "nothing left waiting")
    (testing "a pass after it, nothing changed: the same error is not sent again (its record walks no view into another)"
      (let [o2 (volatile! {})]
        (#'p/pass (assoc st' :waiting {3 debug}) o2)
        (is (not-any? #(= :error (:op %)) (:to-api @o2)))
        (is (some #(and (= 3 (:k %)) (str/includes? (:html %) "seon.render/view-defect")) (:to-web @o2)) "its page is still the error")))))

(deftest a-branchless-agent-on-main-opens-at-its-id-under-main
  ;; an example session is an ended agent on main with no branch (my.root/load-example!): /agent/main/example-abc is
  ;; the view (keyword "main/example/abc"), found as :user is (an agent of main with no branch) and read from main
  (let [db (reduce d/db-with (ts/test-db)
                   [[{:seon.system/id :root :seon.system/boot "b1"}]
                    [{:seon.agent/key "example/abc" :seon.agent/status :ended :seon.agent/description "Make h." :seon.agent/turn 1}]
                    [{:seon.eval/id "example/abc/s1" :seon.eval/by {:seon.agent/key "src"} :seon.eval/agent [:seon.agent/key "example/abc"] :seon.eval/call (seon.task/call-ref "example/abc" 1)
                      :seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/example-abc-s1 :value 3} :seon.eval/call-id "example-abc-call_1"}]])
        eid (seon.db/eid db :seon.agent/key "example/abc")
        st {:rid 1 :pending {} :watch #{} :waiting {} :views {} :branches {} :main-db db :view {:agents {}}}
        units (fn [view] (:seon.render/units (#'p/page-value st view)))
        html (fn [view] (apply str (map :html (units view))))]
    (is (= eid (#'p/location db (keyword "main/example/abc"))) "found by its id under main's name")
    (is (= eid (#'p/location db [:chat (keyword "main/example/abc")])))
    (is (nil? (#'p/location db (keyword "main/example/none"))) "an id no agent has")
    (is (str/includes? (html [:chat (keyword "main/example/abc")]) "(+ 1 2)") "its chat page lists its evals")
    (is (str/includes? (h/html (:seon.render/hiccup (#'p/page-value st [:chat (keyword "main/example/abc")]))) "href=\"/agent/example/abc/debug") "its tabs name it by its key")
    (is (seq (units (keyword "main/example/abc"))) "its record page reads main")))

(deftest a-debug-page-of-an-agent-with-no-branch-answers
  ;; the browser pass (3b-root): /agent/user/debug never answered: the user has no branch on main, so
  ;; none was read, and the page waited for one. With no branch (or one that could not be read) its
  ;; debug page has no turn to show and says so at once
  (let [db (reduce d/db-with (ts/test-db) [[{:seon.system/id :root :seon.system/boot "b1"}] (seon.task/agent-tx :user {:status :idle})])
        st {:main-db db :branches {}}]
    (is (nil? (:seon.agent/branch (d/entity db (#'p/location db :user)))) "the fixture: no branch")
    (is (some? (:seon.render/hiccup (#'p/debug-value st [:debug :user]))) "its page, with no turn")
    (is (some? (:seon.render/hiccup (#'p/debug-value (assoc-in st [:branches [:debug :user] :error] {:seon.error/message "x"}) [:debug :user])))
        "a branch that could not be read: its page too")))

(deftest an-outside-read-is-no-model-call
  ;; the browser pass (3b-root): an outside read's turn (seon_context) said "the call is in flight"
  ;; on the debug page, though it calls no model
  (let [row (fn [origin] (h/html (ui/debug-turn {:seon.render/index (seon.db/index) :unit "d-turn"} {:turn 0 :agent "a1" :view "a1" :origin origin :body {:messages []} :sources [] :new [] :usage {}})))]
    (is (str/includes? (row :outside) "an outside read: no model call"))
    (is (not (str/includes? (row :outside) "in flight")))
    (is (str/includes? (row :model) "the call is in flight") "a model turn with no usage yet is in flight")))

(deftest the-debug-page-names-an-example-sessions-messages
  ;; the debug page's request: a message an example session made says so in its gutter, from its :example
  ;; ref (the messages' text is never read), its content as sent
  (let [h (h/html (ui/debug-turn {:seon.render/index (seon.db/index) :unit "d-turn"} {:turn 1 :agent "a1" :view "a1" :origin :model :usage {}
                                                   :body {:messages [{:role "system" :content "help"} {:role "user" :content "an opening, whatever it says"}]}
                                                   :sources [[[:section 1 1]] [[:example "example/abc" 5]]] :new [false true]}))]
    (is (str/includes? h "example session example/abc"))
    (is (str/includes? h "an opening, whatever it says"))
    (is (= 1 (count (re-seq #"data-example=" h))) "the system message is no session's")))

(deftest a-turn-whose-commit-is-gone-says-so
  ;; docs/render.md#the-debug-page-is-the-wire-request: the store's keep can sweep a turn's commit; the page draws and says what it drew
  (let [h (h/html (ui/debug-turn {:seon.render/index (seon.db/index) :unit "d-turn"} {:turn 1 :agent "a1" :view "a1" :origin :model :usage {} :swept? true :differs? true
                                                   :body {:messages [{:role "system" :content "help"}]} :sources [[]] :new [false]}))]
    (is (str/includes? h "gone from the store") "said plainly")
    (is (not (str/includes? h "a render or a record changed")) "and not as something else")))

(deftest a-request-is-its-wire-messages
  ;; docs/render.md#the-debug-page-is-the-wire-request: a row a message, typed by its wire role, and opening a row
  ;; replaces its line; its calls' arguments are the wire's JSON read as data
  (let [ms [{:role "system" :content "# Help\n\nread this"}
            {:role "assistant" :tool_calls [{:id "a" :function {:name "repl" :arguments "{\"intent\":\"Read it\",\"code\":\"(first-call)\"}"}}]}
            {:role "tool" :tool_call_id "a" :content (str/join " " (repeat 8000 "word"))}]
        draw (fn [opened] (h/html (ui/debug-turn {:seon.render/index (seon.db/index) :unit "d-turn"} {:turn 1 :agent "a1" :view "a1" :origin :model :usage {} :opened opened
                                                                   :body {:messages ms} :sources [[[:section 1 1]] [[:eval "e1"]] [[:eval "e1"]]] :new [false false true]})))
        h (draw #{})]
    (is (= ["system" "assistant" "tool"] (mapv second (re-seq #"data-role=\"(\w+)\"" h))) "a row a message, in the wire's order, by its role")
    (is (not-any? #(str/includes? h %) ["tool_calls[0]" "arguments.code"]) "a closed row is its line: its fields are drawn when opened")
    (is (str/includes? h "repl {") "an assistant's line is its calls as sent")
    (let [o (draw #{"d-turn-o1"})]
      (is (str/includes? o "tool_calls[0] · repl · a") "opened, the call's fields as sent")
      (is (str/includes? o "arguments.code") "its arguments by their wire names")
      (is (str/includes? o "(first-call)"))
      (is (str/includes? o "group-open:hidden") "the line gives way to the fields, so nothing is shown twice"))
    (is (< (count h) (count (draw #{"d-turn-o2"}))) "opened, a message is drawn whole, however large")
    (is (< (count (re-seq #"word" h)) 100) "closed, its line holds no copy of it")
    (is (<= 8000 (count (re-seq #"word" (draw #{"d-turn-o2"})))) "and nothing of it is cut")
    (is (str/includes? h "text-error") "a message past its limit reads red")
    (is (str/includes? (draw #{"d-turn-o0"}) "<h") "a system message's Markdown is drawn")))

(deftest markdown-is-drawn-for-a-person-and-kept-for-the-model
  ;; docs/references.md#the-delimiter-picks-the-depth: what the model reads keeps its delimiters; a person's page draws them
  (let [md "Read `a/b` and **this** then *that* at [the docs](https://x.y/z), not [bad](javascript:alert).\n\n- item `c`\n"
        kept (h/html (ui/markdown md))
        drawn (h/html (ui/markdown md {:inline? true}))]
    (is (str/includes? kept "Read `a/b` and **this**") "kept as written")
    (is (not (str/includes? kept "<strong>")))
    (is (str/includes? drawn "<code>a/b</code>"))
    (is (str/includes? drawn "<strong>this</strong>"))
    (is (str/includes? drawn "<em>that</em>"))
    (is (str/includes? drawn "<a href=\"https://x.y/z\">the docs</a>"))
    (is (not (str/includes? drawn "javascript:")) "a link goes only where a page may send a person")
    (is (str/includes? drawn "bad") "and keeps its text")
    (is (str/includes? drawn "<code>c</code>") "in a list item too")))

(defn styled-a "A render with CSS rules." {:seon.render/style ".styled-a { color: red; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}] [:p (str (:x c))])
(defn rules-shown "The rules as a component answers them." {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.render/drawn :any]]]] :any]}
  [{d :seon.render/drawn}] [:style (r/rules @d)])
(defn styled-layout "A layout: a styled render, then its rules." {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_] [:div {:id "x"} [#'styled-a {}] [#'rules-shown {}]])

(deftest the-page-draws-the-rules-of-its-renders
  ;; docs/render.md#style: a component of the walk, run again when a render is drawn or redefined and no other time
  (let [c (seon.ratom/atom {:db/id 1 :x 1})
        w (r/walker {:ref c :changes seon.ctx/changes} (constantly nil) [#'seon.render-test/styled-layout {}] (seon.db/index))]
    (flush! w nil)
    (is (str/includes? (h/html (r/resolved w @(:out w))) ".styled-a { color: red; }") "the rules of what it drew")
    (let [rules-key (some (fn [[k e]] (when (= #'seon.render-test/rules-shown (:f e)) k)) @(:cache w))
          rev (:rev (get @(:cache w) rules-key))]
      (is (some? rules-key))
      (swap! c assoc :x 2)
      (is (not (contains? (flush! w nil) rules-key)) "a change of data does not run them")
      (is (= rev (:rev (get @(:cache w) rules-key))))
      ;; as a defn at the REPL: its new metadata and its new root
      (alter-meta! #'styled-a assoc :seon.render/style ".styled-a { color: blue; }")
      (alter-var-root #'styled-a (fn [f] (fn [o] (f o))))
      (is (contains? (flush! w nil) rules-key) "a render redefined does")
      (is (str/includes? (h/html (r/resolved w @(:out w))) ".styled-a { color: blue; }") "and shows its new rules")
      (r/dispose-walk! w))))

(deftest a-cut-with-its-whole-form-holds-no-copy
  ;; the second context audit (F7): a plan cut on the page kept its whole text in the cut's :title,
  ;; which the model reads too (1.2k chars twice on two pages). A cut that names the form reading the
  ;; whole holds no copy; with no form, the title is a person's way to the rest
  (let [long (str/join " " (repeat 400 "word"))
        with (ui/plain long 20 {:whole '(:plan (my.task/data))})
        without (ui/plain long 20)
        titles (fn [h] (keep #(when (map? %) (:title %)) (tree-seq coll? seq h)))]
    (is (empty? (titles with)) (pr-str with))
    (is (str/includes? (pr-str with) "(:plan (my.task/data))") "the cut names the form that reads the whole")
    (is (= [long] (titles without)) "no form: the title holds the whole")
    (is (not-any? nil? (tree-seq vector? seq with)) "and no empty child")))

(defn- outside-code
  "The text html shows outside its <code> and <pre> elements and the printer's string values (where a backtick or a quote is the
  code's or the data's own)."
  [html] (-> html (str/replace #"(?s)<pre[^>]*>.*?</pre>" " ") (str/replace #"(?s)<code[^>]*>.*?</code>" " ")
             (str/replace #"(?s)<span[^>]*seon-print-string[^>]*>.*?</span>" " ") (str/replace #"<[^>]+>" " ")))

(deftest a-person-reads-no-markup
  ;; docs/render.md#the-page: what a person reads is drawn, never the marks that describe it: no Markdown delimiter, no escaped quote of
  ;; a printed form, no printer's tree heading, outside code (the owner, on the session pages: "we still aren't rendering html")
  (let [db (d/db-with (ts/test-db) (seon.task/create-tx (ts/test-db) (seon.template/request {:seon.task/ns 'demo :seon.task/name "w"
                                                                                              :seon.task/plan "Make w true:\n\n1. read the field `x`\n2. write **one** test"})))
        ;; drawn by the walk, as a page draws it: its joins (the task's search) drawn by their own renders
        task (let [w (draw (:tree (seon.ctx/build (seon.db/index) db (seon.db/eid db :seon.task/id "demo/w") nil)) seon.ctx/changes db #'ui/task-view {})]
               (try (apply str (map :html (seon.fixtures/page-units w))) (finally (r/dispose-walk! w))))
        red (h/html (ui/status-hiccup {} {:proofs [{:test 'demo-test/w :status :fail
                                                     :failures [{:assert {:seon.print/code "(str/includes? h \"[:code \\\"x\\\"]\")"} :got false}]}]}))
        value (h/html (r/print-hiccup {:seon.render/index (seon.db/index)} {:a {:b [1 {:c 2}]}}))]
    (doseq [[what html] [["a task's plan" task] ["a red proof" red] ["a value" value]]
            :let [t (outside-code html)]]
      (is (not (str/includes? t "`")) (str what ": no backtick outside code: " t))
      (is (not (str/includes? t "\\\"")) (str what ": no escaped quote outside code"))
      (is (not (str/includes? t "items, depth")) (str what ": no printer's tree heading")))))

(deftest a-plain-string-is-prose-by-default
  ;; docs/render.md#a-value-draws-itself: a string whose attribute's schema names no render is Markdown (owner: "if it is a text field it
  ;; is assumed to be markdown"), so a branch forked before its attribute became :seon.db/text draws it as main does; an id opts out
  (let [old (assoc-in (seon.db/index) [:forms :seon.task/plan] :string)
        draw-as (fn [ix k v] (h/html (r/hiccup {:seon.render/index ix :schema k} v)))]
    (is (str/includes? (draw-as old :seon.task/plan "read `x` first") "<code>x</code>") "an old branch's plan: Markdown, its code span drawn")
    (is (not (str/includes? (draw-as old :seon.task/plan "read `x` first") "`")) "no backtick left")
    (is (not (str/includes? (draw-as (seon.db/index) :seon.task/id "demo/w") "<code>")) "an id is no prose: printed as the data it is")))

(deftest a-whole-tasks-page-holds-its-instructions
  ;; the task part drew the plan and dropped the task's own data, its instructions with it, so no
  ;; agent read how its kind is worked (reproduce first, fail! when already right, stop reading once
  ;; wrong): they are drawn above the plan, whole
  (let [db (ts/test-db)
        db (d/db-with db (seon.task/create-tx db (seon.template/request {:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Make w true."})))
        ;; the task as its record pulls it, drawn by its html render
        h (h/html (ui/task-view {:seon.render/index (seon.db/index)} (:tree (seon.ctx/build (seon.db/index) db (seon.db/eid db :seon.task/id "demo/w") nil))))]
    (is (str/includes? h "Your task: do what the plan below asks") "the whole instructions are on the page")
    (is (str/includes? h "your run then ends and root merges your branch") "whole: their last sentence too, nothing cut")
    (is (< (str/index-of h "Your task: do what the plan below asks") (str/index-of h "Make w true.")) "above the plan")))

(defn- unit-of
  "The unit of units whose element id starts with prefix."
  [units prefix] (some #(when (str/starts-with? (:id %) prefix) %) units))

(deftest a-page-is-its-components
  ;; an agent's ctx drawn by the walk as components in
  ;; the page's lists, each keyed by what it shows, its root element's id its key
  (let [[_ branch _] (paged-dbs)
        k "a3"
        ;; the cited var's namespace, the program's as a store records it (seon.program/program-names)
        branch (d/db-with branch [{:ns/name 'seon.render :ns/file "src/seon/render.clj"}
                                  {:seon.agent/key k :seon.agent/turn 2}
                                  {:seon.model-call/id (seon.lib/call-id k 2) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n 2 :seon.model-call/origin :model
                                   ;; answered with nothing to say (a call with only its basis is one in flight)
                                   :seon.model-call/reply "" :seon.model-call/forms 0
                                   :seon.model-call/basis {:commit #uuid "00000000-0000-0000-0000-000000000000" :task "demo/f" :turn 2 :max-turns 8 :origin :model}}
                                  ;; the proof's verdict, as the test run recorded it on the proof's var entity
                                  {:var/name 'demo/f-test :seon.test/status :red
                                   :seon.test/failures [{:type :fail :expected "(= 4 (f 2))" :actual "(not (= 4 5))" :got "(= 4 5)"}]}])
        rid (seon.db/eid branch :seon.agent/key k)
        draw #(let [w (draw (:tree (seon.ctx/build (seon.db/index) branch (seon.db/eid branch :seon.agent/key k) :task-demo-f)) seon.ctx/changes branch
                              #'seon.render.ui/agent)]
                (try (seon.fixtures/page-units w) (finally (r/dispose-walk! w))))
        units (draw)
        task (filterv #(= "#p-task" (:container %)) units)
        conv (filterv #(= "#c-rows" (:container %)) units)
        eid #(seon.db/eid branch %1 %2)]
    ;; a cited var is a component of its own when the db holds it (ctx joins the task's references by
    ;; :var/name: seon.ctx.wire-test/a-cited-var-redefined-shows-in-the-next-view); this db holds none
    (is (= "task-view-demo_2Ff" (first (mapv :id task))) "first what its pull joins, by its own schema's render: its task, joined first (seon.render.ui/tiles)")
    (is (every? #(str/starts-with? % "cite-") (take-while #(not (#{"status" "turn" "agent-state"} %)) (rest (mapv :id task)))) "then a tile for each thing the task and the agent cite, by the cite's own render")
    (is (= ["status" "turn" "agent-state"] (vec (take-last 3 (mapv :id task))))
        "then the agent's own state, from its own attributes (open problems and added keys only when there are some)")
    (let [h (:html (unit-of task "status"))]
      (is (str/includes? h "demo/f-test") "each proof as its var entity holds its verdict, (my.task/status)'s")
      (is (str/includes? h "demo/f-test</code>: red") "the failing proof's verdict is the status's")
      (is (str/includes? h "(= 4 5)") "and what its assertion got"))
    (let [h (:html (unit-of task "task"))]
      (is (str/includes? h "task · demo") "the task's part, its namespace above its plan")
      ;; how a proof runs is the status's (its verdict, its source, the form that runs it), not repeated in the task's
      ;; part; this fixture's proof has no source, so the part says what to write (seon.ctx/not-written)
      (is (not (str/includes? h "my.agent/prove")) "no proof's run form, which the status shows"))
    (is (str/includes? (:html (unit-of task "status")) "What your task still needs") "how the work stands is the status's")
    (is (= ["system-message" "chat-ask" "conversation-turn-1" "chat-end"]
           (mapv :id conv))
        "the conversation by turn number, each turn a component keyed by the agent and its number (turn 0 took no record, and the agent's turn 2 said and ran nothing: no element), then how the agent stands")
    (let [turn1 (:html (unit-of conv "conversation-turn-"))
          at #(str/index-of turn1 %)]
      (is (< (at "id=\"message-m-1\"") (at "seon-message")
             (at "id=\"evaluation-e3\""))
          "a turn: the messages it took, its model call, then the evals of other hands")
      (is (every? #(str/includes? turn1 (str "id=\"evaluation-" % "\"")) ["e1" "e2" "e3"]) "its model call's evals among its steps"))
    (is (every? #(str/starts-with? (:html %) (str "<" )) units))
    (is (every? #(str/includes? (:html %) (str "id=\"" (:id %) "\"")) units) "each component's root element carries its key as its id")
    (is (= units (draw)) "pure: the same ctx, the same page")))

(deftest the-proofs-part-renders-the-recorded-status
  ;; task seon.proofs/test-results-are-a-few-facts (R2a): the task part of the one layout shows each
  ;; proof's status, the digest it was judged at and (while red) its first failure from @ctx, where the
  ;; agent's run recorded them on the proof's var entity; nothing is run or read from the cache to draw it
  (let [[_ branch _] (paged-dbs)
        k "a3"
        draw #(let [w (draw (:tree (seon.ctx/build (seon.db/index) % (seon.db/eid % :seon.agent/key k) :task-demo-f)) seon.ctx/changes %
                              #'seon.render.ui/agent)]
                (try (:html (unit-of (seon.fixtures/page-units w) "status")) (finally (r/dispose-walk! w))))
        written (d/db-with branch [{:db/id "f" :form/id "demo/f-test-1" :form/src "(deftest f-test (is (= 4 (f 2))))"}
                                   {:var/name 'demo/f-test :var/meta #{:test} :var/form "f"}])
        red (d/db-with written [{:var/name 'demo/f-test :seon.test/digest "digest-1" :seon.test/status :red
                                 :seon.test/failures [{:type :fail :message "it doubles" :expected "(= 4 (f 2))" :actual "(not (= 4 5))" :got "(= 4 5)"}]}])
        green (d/db-with red [[:db/retract [:var/name 'demo/f-test] :seon.test/failures [{:type :fail :message "it doubles" :expected "(= 4 (f 2))" :actual "(not (= 4 5))" :got "(= 4 5)"}]]
                              {:var/name 'demo/f-test :seon.test/digest "digest-2" :seon.test/status :green}])]
    (is (not (str/includes? (draw written) "digest-1")) "a proof not yet run shows no result")
    (let [h (draw red)]
      (is (str/includes? h "demo/f-test</code>: red") (str "the recorded status, red: " h))
      (is (str/includes? h "it doubles") "and its failing assertion")
      (is (str/includes? h "(= 4 5)") "as the assertion it got, printed as code"))
    (let [h (draw green)]
      (is (str/includes? h "Every box is checked: nothing blocks your merge") "green is the status's word")
      (is (not (str/includes? h "it doubles")) "a green keeps no failure"))))

(deftest the-status-is-drawn-from-facts-with-no-turn-of-the-reader
  ;; the status reads the branch's facts, never a
  ;; turn's record, so it is drawn for an agent whatever turns it has taken, a proof's verdict
  ;; included; the turn count is the turn's (seon.render.ui/turn), not the status's
  (let [[_ branch _] (paged-dbs)
        k "a3"
        branch (d/db-with branch [{:var/name 'demo/f-test :seon.test/status :green}])
        w (draw (:tree (seon.ctx/build (seon.db/index) branch (seon.db/eid branch :seon.agent/key k) :task-demo-f)) seon.ctx/changes branch #'seon.render.ui/agent)
        standing (try (unit-of (seon.fixtures/page-units w) "status") (finally (r/dispose-walk! w)))
        h (:html standing)]
    (is (some? standing) "the status is drawn")
    (is (str/includes? h "[x] <code>demo/f-test</code>") (str "the verdict the branch holds: " h))
    (is (not (str/includes? h "Turn")) "and has no turn count")))

(deftest ^{:seon.test/estimate {:ticks 3000000}} the-boxes-are-the-done-rules-reasons
  ;; the owner (2026-11-14): each turn the agent sees what its task still needs, merge needs only, as boxes. A backlog run's
  ;; proof went green at turn 48 and turns 48-80 went on what the page listed under "can't merge yet", stale sections among
  ;; them, which never refuse a merge (seon.problems/caused-split's :attention). The boxes are the done rule's reasons, the
  ;; judge's one function (seon.reconcile/done-explain), and nothing else; what does not block is said apart, as not blocking
  (let [s {:reasons [{:reason :proof-red :symbol 'demo/b-test} {:reason :stray-red :symbol 'demo/other-test} {:reason :contract-gap :symbol 'demo/g}]
           :proofs [{:test 'demo/a-test :status :pass}
                    {:test 'demo/b-test :status :fail :failures [{:assert "(= 4 (f 2))" :got "(= 4 5)"}]}]
           :refused [{:reason :stray-red :test 'demo/other-test} {:reason :contract-gap :vars ['demo/g]}]
           :without-a-contract ['demo/g]
           :attention ["docs/demo.md#f cites demo/f, which changed"]}
        h (h/html (ui/status-hiccup {} s))
        at #(str/index-of h %)
        not-blocking (at "don't block your merge")]
    (is (str/includes? h "What your task still needs") (str "headed by what remains: " h))
    (is (str/includes? h "done when every box is checked") "and says when it is done")
    (is (= 1 (count (re-seq #"\[x\]" h))) "the green proof is a checked box")
    (is (= 3 (count (re-seq #"\[ \]" h))) "one open box per reason: the red proof, the stray red, the contract gap")
    (is (every? #(str/includes? h %) ["demo/b-test" "demo/other-test" "demo/g"]) "each named")
    (is (and not-blocking (< (at "[ ]") not-blocking (at "docs/demo.md#f"))) "a stale section is said after the boxes, as not blocking")
    (is (not (str/includes? h "can't merge yet")) "no list beside the boxes says the same again"))
  (let [h (h/html (ui/status-hiccup {} {:reasons [] :proofs [{:test 'demo/a-test :status :pass}] :attention ["docs/demo.md#f cites demo/f, which changed"]}))]
    (is (not (re-find #"\[ \]" h)) "nothing open when the reasons are none, a stale section included")
    (is (str/includes? h "Every box is checked") (str "and it says so: " h))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} an-unmerged-entrys-form-is-one-resolve-takes
  ;; docs/render.md#how-the-work-stands: the form the page offers for an unmerged entry runs as written. A var's entry is settled by
  ;; its name (my.branch/resolve!'s contract); context's first-task merge, 11-14, got '[:var seon.repl/wrap-instrument] from the page
  ;; and a contract error from resolve!. Any other program key (a task's) is settled by the key itself
  (let [h (h/html (ui/status-hiccup {} {:unmerged [{:key '[:var app.m/f] :ours "(defn f [] 1)" :theirs "(defn f [] 2)"}
                                                   {:key [:task "app/t"] :ours {:seon.task/plan "a"} :theirs {:seon.task/plan "b"}}]}))]
    (is (str/includes? h "(my.branch/resolve! 'app.m/f)") (str "a var's, by its name: " h))
    (is (str/includes? h "(my.branch/resolve! 'app.m/f :theirs)") "both sides")
    (is (not (str/includes? h "'[:var")) "never its key")
    (is (some #{"(my.branch/resolve! '[:task \"app/t\"])"}
              (tree-seq coll? seq (ui/status-hiccup {} {:unmerged [{:key [:task "app/t"] :ours {:seon.task/plan "a"} :theirs {:seon.task/plan "b"}}]})))
        "a task's, by its key, as the code it runs")))

(deftest the-models-page-holds-the-plan-whole
  ;; the blind agent's plan was cut "+86 chars" at a part's budget: the task itself took an eval to read
  (let [[_ branch _] (paged-dbs)
        plan (str "Make `demo/f` return its argument doubled. " (str/join " " (repeat 60 "Every word of this plan is what the task is.")))
        db (d/db-with branch [{:seon.task/id "demo/f" :seon.task/plan plan}])
        page (seon.fixtures/drawn-parts db (seon.ctx/build (seon.db/index) db (seon.db/eid db :seon.agent/key "a3") :task-demo-f))
        text (seon.print/text (vary-meta page assoc :seon.print/full true))]
    (is (< (:part seon.print/budgets) (seon.print.select/estimate plan)) "a plan over a part's budget")
    (is (str/includes? text (subs plan (- (count plan) 60))) "the plan's end is on the page")))

(defn data-view
  "A component that answers data, a part of a schema that declares no render of its own."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_] (with-meta {:plan "Make it" :n 3} {:seon.render/schema :seon.render-test/no-render}))

(deftest a-value-in-a-components-output-draws-by-the-printer
  ;; root, 2026-10-26 (the owner's compact renders, not stripped): a value standing in a component's output: the page's serializer
  ;; draws it by the printer when it writes the page, the model reads the value as data
  (let [w (r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} (constantly (ts/test-db)) [#'data-view {}])]
    (try (flush! w nil)
         (let [k @(:out w)
               h0 (r/resolved w k true)
               text (r/part-text (r/part-form w k))]
           (is (str/includes? (h/html h0) "Make it") "the page draws the data by its schema's html render, here the printer's")
           (is (= h0 (r/resolved w k true)) "drawn again, equal")
           (is (str/includes? text "Make it") "the model is sent the value as data")
           (is (str/starts-with? text "[seon.render-test/data-view") "under the head that drew it")
           )
         (finally (r/dispose-walk! w)))))

(defn- live-walk
  "a3's page walked over a reactive atom holding its ctx, as its process holds it (seon.repl/the-walk):
  [walk atom branch]."
  []
  (let [[_ branch _] (paged-dbs)
        a (seon.ratom/atom (:tree (seon.ctx/build (seon.db/index) branch (seon.db/eid branch :seon.agent/key "a3") :task-demo-f)))
        w (r/walker {:ref a :changes seon.ctx/changes} (constantly branch) [#'seon.render.ui/agent {} (seon.ratom/ref-view a seon.ctx/changes)])]
    (flush! w nil)
    [w a branch]))

(deftest a-component-re-runs-on-what-it-read
  ;; Reagent's rule with the cursor inferred: a change re-runs only the components whose read paths
  ;; it reaches; every other is not visited, and a re-run whose output is = sends nothing
  (let [[w a branch] (live-walk)
        e3 (seon.db/eid branch :seon.eval/id "e3")]
    (swap! a assoc-in [:seon.eval/_agent e3 :seon.eval/opened] true)
    (is (= {[`ui/evaluation "e3"] :miss [`ui/eval-output "e3"] :same} (flush! w nil)) "an eval opened: that eval's line and its output run (opened, the output is whole); its turn's and its list's layout do not")
    (swap! a assoc :t 99)
    (is (= {} (flush! w nil)) "a change no component read: nothing runs")
    (swap! a assoc-in [:seon.eval/_agent e3 :seon.eval/opened] true)
    (is (= {} (flush! w nil)) "the same value again: nothing runs")
    (r/dispose-walk! w)))

(defn lab-row
  "An eval's code: a component over ctx (docs/render.md#reactive-renders)."
  {:malli/schema [:=> [:cat [:map [:seon.ctx/ctx :map]] :int] :any]}
  [{c :seon.ctx/ctx} id] [:li (get-in c [:evals id :seon.eval/code])])

(defn lab-length
  "An eval's code by its length."
  {:malli/schema [:=> [:cat [:map [:seon.ctx/ctx :map]] :int] :any]}
  [{c :seon.ctx/ctx} id] [:li (count (get-in c [:evals id :seon.eval/code]))])

(defn lab-page
  "The task's plan, then a row for each eval, each its own component."
  {:malli/schema [:=> [:cat [:map [:seon.ctx/ctx :map]]] :any]}
  [{c :seon.ctx/ctx}]
  [:div [:p (get-in c [:task :seon.task/plan])]
   (into [:ol] (for [id (keys (:evals c))] [#'lab-row {} id]))
   (into [:ol] (for [id (keys (:evals c))] [#'lab-length {} id]))])

(defn lab-inline
  "The task's plan and every eval's code, read in this one render."
  {:malli/schema [:=> [:cat [:map [:seon.ctx/ctx :map]]] :any]}
  [{c :seon.ctx/ctx}]
  [:div [:p (get-in c [:task :seon.task/plan])] (into [:ol] (for [[_ e] (:evals c)] [:li (:seon.eval/code e)]))])

(deftest a-flush-answers-what-it-redrew
  ;; docs/render.md#reactive-renders: each frame answers the components it ran, by key, new or the
  ;; same output, and what each kind of read costs: a member in its own component, or read inline
  (let [rec (seon.ratom/atom {:db/id 1 :t 5 :task {:db/id 2 :seon.task/plan "write the test"}
                              :evals (#'seon.ctx/members-map [[12 {:db/id 12 :seon.eval/code "(+ 1 2)"}]
                                                             [11 {:db/id 11 :seon.eval/code "(inc 1)"}]])})
        walk #(doto (r/walker {:ref rec :changes seon.ctx/changes} (constantly nil) [% {}] (seon.db/index)) (flush! nil))
        w (walk #'lab-page)
        inline (walk #'lab-inline)
        both #(vector (flush! w nil) (flush! inline nil))]
    (swap! rec assoc-in [:evals 12 :seon.eval/code] "(+ 1 5)")
    (is (= [{[`lab-row 12] :miss [`lab-length 12] :same} {[`lab-inline] :miss}] (both))
        "a member's change: its own rows, the length row the same; the inline render whole")
    (swap! rec assoc-in [:task :seon.task/plan] "ship it")
    (is (= [{[`lab-page] :miss} {[`lab-inline] :miss}] (both)) "the plan: the page, its rows skipped")
    (swap! rec assoc-in [:evals 13] {:db/id 13 :seon.eval/code "(dec 1)"})
    (is (= [{[`lab-page] :miss [`lab-row 13] :miss [`lab-length 13] :miss} {[`lab-inline] :miss}] (both))
        "a member added: the page, and the new member's rows")
    (swap! rec assoc :t 6)
    (is (= [{} {}] (both)) "a key no render read: nothing")
    (r/dispose-walk! w) (r/dispose-walk! inline)))

(defn lab-page-root
  "A walk's root over its ctx view, placing lab-page: the root the test below walks."
  {:malli/schema [:=> [:cat :map :any] :any]}
  [_ _] [:main [#'lab-page {}]])

(deftest a-walk-says-what-draws-what
  ;; docs/render.md#what-draws-what: seon.render/tree is a walk's cache read as data that chains, from its root: each entry
  ;; {:render :args :id :tokens :children}, :tokens its own output's (the one sizer over it, a child standing as its marker),
  ;; :reads? only for an entry that read the db. It reads a walk value (seon.render/walk), an agent's own (my.render/tree) among them.
  (let [long-code (apply str (repeat 2000 "x"))
        rec {:db/id 1 :t 5 :task {:db/id 2 :seon.task/plan "write the test"}
             :evals (#'seon.ctx/members-map [[12 {:db/id 12 :seon.eval/code long-code}]
                                            [11 {:db/id 11 :seon.eval/code "(inc 1)"}]])}
        p (r/walk nil {:db (ts/test-db) :tree rec :root #'lab-page-root :index (seon.db/index) :changes seon.ctx/changes :outputs #{:seon.render/hiccup}})
        ;; lab-page, the root's one child
        t (first (:children (r/tree p)))
        kid (fn [f id] (first (filter #(and (= f (:render %)) (= [id] (:args %))) (:children t))))]
    (do
      (is (= {:render `lab-page :args []} (select-keys t [:render :args])) "from the root")
      (is (= #{[`lab-row 12] [`lab-row 11] [`lab-length 12] [`lab-length 11]} (set (map (juxt :render (comp first :args)) (:children t)))) "each component it drew, with its args")
      (is (= (:id (get (:cache p) [`lab-row 12])) (:id (kid `lab-row 12))) "named by its element id")
      (is (every? #(and (empty? (:children %)) (nat-int? (:tokens %))) (:children t)) "leaves have none")
      (is (<= (seon.print.select/estimate long-code) (:tokens (kid `lab-row 12)) (* 2 (seon.print.select/estimate long-code)))
          "its tokens are the one sizer's over the characters of its own output: the 2,000 it shows")
      (is (< (:tokens t) (:tokens (kid `lab-row 12))) "the root's are its own: the 2,000 characters are its child's, not counted in it")
      (is (not-any? :reads? (cons t (:children t))) "no db read, no flag")
      (is (= t (clojure.edn/read-string (pr-str t))) "plain data: it reads back from its print, so it chains")
      (is (seon.contract/valid? (seon.contract/schema :seon.render/tree) t) "and its schema"))))

(defn lab-said
  "A line of text, a render redefined by the test below."
  {:malli/schema [:=> [:cat [:map [:seon.ctx/ctx :map]]] :any]}
  [{c :seon.ctx/ctx}] [:p (str "plan: " (get-in c [:task :seon.task/plan]))])

(deftest a-redefined-render-redraws
  ;; docs/render.md#reactive-renders: a walk watches each render it drew, so redefining one (a defn
  ;; at the REPL) runs its components at the next frame with the new definition; nothing else runs,
  ;; and a walk disposed watches nothing
  (let [rec (seon.ratom/atom {:db/id 1 :task {:db/id 2 :seon.task/plan "p"}})
        w (doto (r/walker {:ref rec :changes seon.ctx/changes} (constantly nil) [#'lab-said {}] (seon.db/index)) (flush! nil))
        was @#'lab-said
        out #(:out (get @(:cache w) [`lab-said]))]
    (try
      (is (= {} (flush! w nil)) "nothing changed: nothing runs")
      (alter-var-root #'lab-said (fn [f] (fn [o] (assoc (f o) 0 :h2))))
      (is (= {[`lab-said] :miss} (flush! w nil)) "redefined: its component runs")
      (is (= :h2 (first (out))) "with the new definition")
      (is (= {} (flush! w nil)) "and once")
      (r/dispose-walk! w)
      (is (empty? (.getWatches #'lab-said)) "a disposed walk watches nothing")
      (finally (alter-var-root #'lab-said (constantly was))))))

(defn lab-child
  "A line of text over ctx, the nested render the test below redefines."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}] [:p (str "plan " (:db/id c))])

(defn lab-root
  "A walk's root over its ctx view: a section holding lab-child."
  {:malli/schema [:=> [:cat :map :any] :any]}
  [_ _] [:section [#'lab-child {}]])

;; See docs/render.md#a-page-is-a-kept-walk
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-redefined-render-redraws-in-the-walk-value
  ;; estimate: a test db and two transactions (~1M), three walks of two components (~0.1M each)
  ;; the walk kept as a value takes a redefinition as the eval records it, its :var/form datom moved (no watch): the render's
  ;; components draw again with the new definition; one that wrote no datom is seen only where the walk visits it again
  (let [db0 (d/db-with (ts/test-db) [{:db/id "v" :var/name `lab-child :var/form "f"}
                                     {:db/id "f" :form/id "lab-child-1" :form/src "(defn lab-child ...)" :form/defines #{"v"}}])
        db1 (d/db-with db0 [{:db/id "f" :form/id "lab-child-2" :form/src "(defn lab-child ... :h2)" :form/defines #{[:var/name `lab-child]}}
                            {:var/name `lab-child :var/form "f"}])
        opts (fn [db] {:db db :tree {:db/id 1} :root #'lab-root :index (seon.db/index) :changes seon.ctx/changes :outputs #{:seon.render/hiccup}})
        k [`lab-child {}]
        out (fn [p] (pr-str (some (fn [[ck e]] (when (= #'lab-child (:f e)) (:out e))) (:cache p))))
        ran? (fn [p] (some (fn [[ck v]] (when (= `lab-child (first ck)) v)) (:ran p)))
        was @#'lab-child
        p0 (r/walk nil (opts db0))]
    (try
      (alter-var-root #'lab-child (fn [f] (fn [& a] (assoc (apply f a) 0 :h2))))
      (let [p1 (r/walk p0 (assoc (opts db0) :moved []))
            p2 (r/walk p1 (assoc (opts db1) :moved (seon.ctx/moved db0 db1 nil)))]
        (is (and (nil? (ran? p1)) (not (str/includes? (out p1) ":h2"))) (str "no :var/form moved: the child is not drawn again " (out p1)))
        (is (= :miss (ran? p2)) "its :var/form moved: its component runs")
        (is (str/includes? (out p2) ":h2") (str "with the new definition: " (out p2))))
      (finally (alter-var-root #'lab-child (constantly was))))))

(deftest a-change-below-a-unit-moves-its-revision
  ;; docs/render.md#a-layout-places-its-lists: every component is a unit, at any depth; one nested in another (a key the agent
  ;; added, under ctx-keys) changing moves its own revision alone, so the render proc sends that unit and not its parent
  (let [[w a _] (live-walk)
        unit (fn [id] (some (fn [u] (when (= id (:id u)) u)) (r/placed-units w)))
        k "ctx-key-lab_2Ex_2Fk"]
    (swap! a assoc :lab.x/k "one")
    (flush! w nil)
    (let [p0 (unit "ctx-keys") k0 (unit k)]
      (is (= "ctx-keys" (:parent k0)) "the key is a unit of its own, inside ctx-keys")
      (is (str/includes? (:html p0) (str "<!--s:" k "-->")) "ctx-keys holds the key's slot, not its html")
      (swap! a assoc :lab.x/k "two")
      (flush! w nil)
      (let [p1 (unit "ctx-keys") k1 (unit k)]
        (is (str/includes? (:html k1) "two") "the new value drawn, in the key's unit")
        (is (not= (:rev k0) (:rev k1)) "the key's revision moved")
        (is (= (:rev p0) (:rev p1)) "and ctx-keys' did not: its own output is unchanged")))
    (r/dispose-walk! w)))

(deftest a-key-is-its-id-one-to-one
  ;; an element id names one component: two keys never write one id; none holds a . (a #id selector reads it as a class)
  (is (= "x-a_2Eb_2Fc" (r/key-id ['x 'a.b/c])))
  (is (not (str/includes? (r/key-id ['cited 'seon.render.ui/status "a.b"]) ".")))
  (is (not= (r/key-id ['x 'a.b/c]) (r/key-id ['x 'a_b/c]) (r/key-id ['x "a.b/c"]))))

(deftest a-dead-agents-eval-opens
  ;; an agent with no process is drawn by root from its branch: an eval a reader opened (main's
  ;; :seon.agent/opened, which the agent would take) is drawn open
  (let [[main branch _] (paged-dbs)
        k "a3"
        main (d/db-with main [{:seon.agent/key k :seon.agent/opened #{"e3"}}])
        [st _] (pass-view (assoc (proc-state main branch) :view {:agents {}}) [:chat :a3])
        ;; e3 is an eval of another hand, the turn's own: a unit of its own inside its turn's, a details of its handle's anchor
        html (get-in st [:views [:chat :a3] :units "evaluation-e3" :html])
        tag (re-find #"<details[^>]*id=\"eval-e3\"[^>]*>" html)]
    (is (re-find #" open" tag) (str "drawn open: " tag))))

(deftest an-agents-chat-places-what-its-model-reads-beside-it
  ;; owner, 2026-11-08: "What happened to the sidebar on the right with all the rendered context parts? Docs that get pulled in,
  ;; the task, etc?": the task's list (seon.render.ui/agent-parts), which the model reads as parts, has its place on the chat page,
  ;; the same components, so a person sees what the agent was told
  (let [[main branch _] (paged-dbs)
        [st _] (pass-view (proc-state main branch) [:chat :a3])
        html (p/view-html st [:chat :a3])]
    ;; the place is the layout's (seon.render.ui/agent-page), the page's root unit, no longer the frame's skeleton
    (is (str/includes? html "id=\"p-task\"") "the page has the task's list's place")
    (is (re-find #"id=\"p-task\"[^>]*>(?:(?!</aside>).)*id=\"task-view-demo_2Ff\"" html) "and the task's component in it, its own render's")))

(deftest a-working-agents-page-follows-its-branch
  ;; owner, 2026-11-08: "the user message doesn't show up until the agent has finished their turn". A live agent draws its own
  ;; page (seon.render.proc/ask-parts), but its process is busy for the whole run, so its answer comes at the run's end: until
  ;; then root draws the page from the branch it already read, and the agent's parts count only when drawn at that t or later
  (let [[main branch _] (paged-dbs)
        k "a3"
        o (volatile! {})
        st (#'p/answered (first (pass-view (proc-state main branch) [:chat :a3])) o [:parts :a3] (drawn-parts branch))
        branch2 (d/db-with branch (turn-2-eval-tx k "(f 4)"))
        ;; root read the branch again (seon.render.proc/ask-branches); the agent has not answered since
        st2 (-> st (assoc-in [:branches [:chat :a3] :db] branch2) (assoc-in [:branch-reads :task-demo-f :db] branch2))
        [st3 _] (pass-view st2 [:chat :a3])]
    (is (contains? (get-in st3 [:views [:chat :a3] :units]) "conversation-turn-2") "the turn written since its process drew is on the page")
    (let [[st4 _] (pass-view (#'p/answered st3 (volatile! {}) [:parts :a3] (drawn-parts branch2)) [:chat :a3])]
      (is (get-in st4 [:views [:chat :a3] :units "conversation-turn-2" :input :own?]) "and its process's drawing takes over once it answers at that t"))))

(deftest a-new-member-is-an-append
  ;; a keyed component new in its list, its parent's output otherwise unchanged, is sent as an append
  ;; into that list (Datastar's mode append), never the list redrawn
  (let [[main branch _] (paged-dbs)
        k "a3"
        o (volatile! {})
        st (#'p/answered (first (pass-view (proc-state main branch) [:chat :a3])) o [:parts :a3] (drawn-parts branch))
        branch2 (d/db-with branch (turn-2-eval-tx k "(f 4)"))
        o2 (volatile! {})
        _ (#'p/answered st o2 [:parts :a3] (drawn-parts branch2))
        patch (first (filter #(= :patch (:op %)) (:to-web @o2)))
        turn2 "conversation-turn-2"]
    ;; the agent's end line (chat-end) closes the conversation, so a new turn is placed before it, after the one it follows
    (is (= [turn2] (mapv (fn [[_ html]] (second (re-find #"id=\"([^\"]+)\"" html))) (:afters patch)))
        "the new turn, placed in the conversation before its end line")
    (is (empty? (:appends patch)) "nothing else appended")
    (is (empty? (:frags patch)) "nothing else of the page sent")))

(deftest an-eval-is-its-own-anchor
  ;; root, 2026-10-26: the conversation is keyed by eid, and a printed handle (result/e1) still
  ;; links to its eval: the eval's component draws its anchor (eval-e1) from the eval's id; opened
  ;; (:seon.eval/opened, the reader's fact taken onto the branch), it draws its value
  (let [[_ branch _] (paged-dbs)
        ;; e3, an eval of another hand: the model's evals are the steps of their turn, drawn open
        e3 "evaluation-e3"
        closed (component-html branch e3)
        opened (component-html (d/db-with branch [{:seon.eval/id "e3" :seon.eval/opened true}]) e3)]
    (is (str/includes? closed "id=\"eval-e3\"") "its anchor, on the eval's own element, by its id")
    (is (not (re-find #"<details[^>]* open" closed)) "no one opened it: its one line")
    (is (str/includes? closed "seon.system%2Fopened") "opening it posts the fact (seon.system/opened!)")
    (is (re-find #"<details[^>]* open" opened) (str "opened: drawn open, its value with it: " opened))
    (is (> (count opened) (count closed)))))

(deftest a-dead-agents-page-renders-and-never-waits
  ;; root, 2026-10-26: no page waits on a process. A running agent's page is drawn from its branch until
  ;; its process answers with its parts; one whose process ended, or whose ask was answered with why,
  ;; is drawn from its branch and says so
  (let [[main branch _] (paged-dbs)
        o (volatile! {})
        st (assoc (proc-state main branch) :watch #{[:chat :a3]} :waiting {5 [:chat :a3]})
        st1 (#'p/pass st o)
        page (first (filter #(= :page (:op %)) (:to-web @o)))]
    (is (= 5 (:k page)) "running: answered in the pass that asked its process")
    (is (empty? (:waiting st1)))
    (testing "its process ended: root's view says so, its parts are dropped, and the page says it is not running"
      (let [st2 (#'p/answered (assoc-in st1 [:parts :a3 :units] []) (volatile! {}) :state (assoc-in (:view st1) [:agents :a3 :status] :dead))]
        (is (nil? (get-in st2 [:parts :a3])))
        (is (not (str/includes? (p/view-html st2 [:chat :a3]) "shown from its branch")) "how it was drawn is no news to a person")
        (is (str/includes? (p/view-html st2 [:chat :a3]) "result/e1") "its parts, drawn by root from its branch")))
    (testing "its ask answered with why (gone, refused): the page says so"
      (let [gone (seon.contract/error :seon.system/no-agent 'seon.system/request "no live agent :a3" {})
            st2 (#'p/answered st1 (volatile! {}) [:parts :a3] gone)]
        (is (str/includes? (p/view-html st2 [:chat :a3]) "Its process didn't answer, so this may be behind: no live agent :a3"))))))

(deftest an-agents-turn-tile-is-its-ctxs
  ;; the owner, 2026-10-26: "any changes you want updated in the agents context and html should go
  ;; through database updates that reach the same data the context pull gets". A task's outcome main
  ;; writes reaches the agent's branch by take!; until then the tile shows what the branch holds
  (let [[main branch _] (paged-dbs)
        main (d/db-with main [{:seon.task/id "demo/f" :seon.task/outcome :parked}])
        [st _] (pass-view (assoc (proc-state main branch) :view {:agents {}}) [:chat :a3])
        tile (some (fn [[id u]] (when (= id "turn") (:html u))) (get-in st [:views [:chat :a3] :units]))]
    ;; the tile is the agent's record's (its status, turn cap and turns taken)
    (is (some? tile) "the turn tile, from the agent's record")
    (is (not (str/includes? (str tile) "parked")) (str "not main's outcome: " tile))))

(deftest a-collection-keyed-by-id-prints
  ;; a collection of ctx (a join's members, keyed by their ids, newest first) holds no override key and no
  ;; error type: its order is total over any key (seon.ctx/members-map's comparator), so a keyword lookup answers nil
  ;; and every value holding one prints (a sorted map ordered by compare throws on that lookup instead)
  (let [[_ branch _] (paged-dbs)
        evals (:seon.eval/_agent (:tree (seon.ctx/build (seon.db/index) branch (seon.db/eid branch :seon.agent/key "a3") :task-demo-f)))]
    (is (seq evals) "the agent's evals")
    (is (sorted? evals) "a collection, sorted by id")
    (is (every? int? (keys evals)))
    (is (nil? (get evals :seon.render/hiccup)) "a keyword lookup answers nil")
    (is (vector? (r/print-hiccup {:seon.render/index (seon.db/index)} {:seon.task/id "t/x" :evals evals})) "nested in a value")
    (is (vector? (r/hiccup {:seon.render/index (seon.db/index)} evals)) "as the value itself")
    (is (nil? (r/schema-of (seon.db/index) evals nil)))))

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
        step! (fn [tx-data] (let [res (d/with @cell tx-data)] (vreset! cell (:db-after res)) (flush! w (:tx-data res))))
        runs #(:miss (get (r/ledger w) `diff-view))
        html #(pr-str (map :out (vals @(:cache w))))]
    (flush! w nil)
    (is (str/includes? (html) "0 vars added"))
    (step! [{:seon.task/id "unrelated/task"}])
    (is (= 1 (runs)) "a datom outside the diffed attribute leaves it as it was")
    (step! [{:var/name 'app.m0/added-on-the-branch}])
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
     {:db/id (str v) :var/name v :var/ns (str "ns " n) :var/form (str "form " v) :var/meta #{:arglists}}]))

(deftest the-status-draws-what-the-merge-functions-answer
  ;; owner, 2026-10-27: what is derived for the reader is the hiccup's, drawn when what it reads
  ;; moves; the status takes main's commit and the agent's base commit from its facts
  ;; (:seon.agent/main-commit, :seon.agent/base-commit), reads both from the branch's own store, and answers the merge's views
  ;; from the one diff of the branch against its base (docs/render.md#a-component-answers-hiccup)
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (defn-facts 'demo/f "(defn f [x] x)"))
      ;; the task is main's, as an agent's task is: its branch makes none
      (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "whole" :seon.task/kind :whole :seon.task/plan "Demo."}]))
      (let [base (d/commit-id @conn)
            _ (d/transact conn (defn-facts 'demo/k "(defn k [] 2)"))
            main (d/commit-id @conn)
            ;; the merge view is a task that merges itself's, read from main's commit as the agent heard it
            agent-on (fn [db] (d/db-with db [{:seon.agent/key "b1/a3" :seon.agent/base-commit base :seon.agent/main-program main :seon.agent/task {:seon.task/id "demo/whole"}}]))
            ours (agent-on (d/db-with (d/commit-as-db conn base) (defn-facts 'demo/h "(defn- h [] 1)")))
            a (seon.db/eid ours :seon.agent/key "b1/a3")
            st (seon.ctx.status/status ours (:tree (seon.ctx/build (seon.db/index) ours a :b)) {})
            b (d/commit-as-db (:store ours) base)
            ch (seon.merge/delta-changes b ours #{})]
        (is (not (contains? st :caused)) "nothing caused")
        (is (= (seon.merge/readiness b ours (d/commit-as-db (:store ours) main)) (:merge st))
            "the merge's readiness, as its own function answers it")
        (is (= (seon.merge/divergence ch) (:its-merge-carries st)) "and what the branch's merge carries, from the one diff")
        (let [html (h/html (ui/status-hiccup {} (select-keys st [:merge :its-merge-carries :reasons :refused])))
              merge-line (h/html (ui/status-hiccup {} {:merge (:merge st)}))]
          (is (str/includes? (pr-str (:main-moved (:merge st))) "demo/k") "what main changed since the base (the model reads it in the data)")
          (is (not (re-find #"Main has moved" html)) "main's move reaches the branch between turns: the agent is told only what conflicts")
          (is (not (str/includes? html "conflict")) "and which of those the branch changed too (none)")
          (is (str/includes? (h/html (ui/status-hiccup {} {:merge {:conflicts ['demo/k] :main-moved ['demo/k]}})) "1 things conflict with main") "a conflict is said")
          (is (str/includes? html "demo/h") "what the branch's merge carries, and a function it defined with no contract")
          (is (not (str/includes? html "demo/k")) "never main's change")
          (is (not (str/includes? html "demo/f")) "never one it did not change"))
        ;; a branch that changed nothing: the status holds none of them, not a header over an empty answer
        (let [same (d/db-with (d/commit-as-db conn base) [{:seon.agent/key "b1/a3" :seon.agent/base-commit base}])
              st0 (seon.ctx.status/status same (:tree (seon.ctx/build (seon.db/index) same (seon.db/eid same :seon.agent/key "b1/a3") :b)) {})]
          (is (not-any? #(contains? st0 %) [:caused :attention :without-a-contract :its-merge-carries :merge :unmerged])
              "a branch with no changes holds none of them"))))))

(deftest an-unrelated-change-redraws-none-of-the-merge-views
  ;; task seon.turn/observe-reads-are-renders: a turn no longer computes these; their one component
  ;; (standing, from the one diff of the branch against its base: docs/render.md#a-component-answers-hiccup)
  ;; is drawn again only when what it reads moves. An unrelated change of ctx redraws it not; main
  ;; moving redraws it (its merge section reads main)
  (ts/test-branch!
    (fn [_ conn]
      (doseq [tx [[{:seon.system/id :root :seon.system/boot "b1"}]
                  [{:seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "Make demo/f." :seon.task/execution :running
                    :seon.task/branch :task-demo-f :seon.task/kind :whole :seon.task/proofs #{'demo/f-test}}]
                  (seon.task/agent-tx :a3 {:status :idle :branch :task-demo-f})
                  (seon.test-store/work-tx "demo/f" :running {:boot "b1" :agent :a3 :branch :task-demo-f :model "m" :max-turns 8 :start-ms 0})
                  ;; the agent's task, its record's :task (task seon.agent/the-agent-is-the-unit-not-the-run)
                  [{:seon.agent/key "a3" :seon.agent/task {:seon.task/id "demo/f"}}]]]
        (d/transact conn tx))
      (let [c0 (d/commit-id @conn)
            k "a3"
            tree (assoc (:tree (seon.ctx/build (seon.db/index) @conn (seon.db/eid @conn :seon.agent/key k) :task-demo-f)) :seon.agent/main-commit c0 :seon.agent/main-program c0 :seon.agent/base-commit c0)
            a (seon.ratom/atom tree)
            w (r/walker {:ref a :changes seon.ctx/changes} (fn [] @conn) [#'seon.render.ui/agent {} (seon.ratom/ref-view a seon.ctx/changes)])
            views '#{seon.render.ui/status}
            ran (fn [] (into {} (for [[f v] (r/ledger w) :when (views f)] [f (+ (:miss v 0) (:same v 0))])))]
        (try
          (flush! w nil)
          (is (= views (set (keys (ran)))) "the views drawn once, as one component of the task's list")
          (let [r0 (ran)]
            (swap! a assoc :seon.agent/description "an unrelated change")
            (flush! w nil)
            (is (= r0 (ran)) "an unrelated change redraws none of them")
            (d/transact conn [{:seon.task/id "demo/g" :seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make demo/g." :seon.task/execution :held :seon.task/branch :task-demo-g :seon.task/kind :work :seon.task/proofs #{}}])
            (swap! a assoc :seon.agent/main-commit (d/commit-id @conn))
            (flush! w nil)
            (is (= r0 (ran)) "main's commit moving alone redraws none of them: root notes an agent's status on main at each eval")
            (swap! a assoc :seon.agent/main-program (d/commit-id @conn))
            (flush! w nil)
            (let [r1 (ran)]
              (is (= views (set (for [[f n] r1 :when (not= n (r0 f))] f)))
                  "main's content moving redraws the views (a whole task merges itself: its merge section reads main)")))
          (finally (r/dispose-walk! w)))))))

;; an eval's tokens are what the model reads of it, not its whole record: one read 99,303 for about 200
(deftest a-size-counts-what-is-read
  (let [record {:seon.eval/fns (vec (repeat 2000 {:var 'a/b :ticks 1})) :seon.eval/response {:handle 'result/e1 :value 1}}]
    (is (< (r/tokens-of (seon.db/index) [:li {:wire/result {:record record}} [:pre "(f)"]]) 200))
    (is (< 3000 (r/tokens-of (seon.db/index) [:li {:x record} [:pre "(f)"]])) "and a plain value's is counted")))

;; a live update resent 23.5 KB when about 100 lines joined what blocks a merge; each is its own name, and a reason said twice is said once
(deftest what-blocks-a-merge-is-named-and-said-once
  (let [names (fn [n] (mapv #(symbol "app.m" (str "problem-" %)) (range n)))
        st (fn [n] {:proofs [{:test 'a/t :status :pass}] :caused (names n) :without-a-contract '[app.m/f]
                    :reasons (into [{:reason :contract-gap :symbol 'app.m/f}] (for [k (names n)] {:reason :open-problem :key k}))
                    :refused [{:reason :contract-gap :vars '[app.m/f]} {:reason :open-problem :problems (names n)}]})
        form (fn [n] (into ['seon.render.ui/status {}] (filter some?) (next (seon.render.ui/status-hiccup {} (st n)))))
        es (r/page-edits (seon.db/index) (r/forms-moved {"status" (form 100)} {"status" (form 101)}))]
    (is (= 1 (count es)) (pr-str es))
    (is (= "app.m/problem-100" (peek (first (first es)))) "the one that came, by its name")
    (is (= 1 (count (re-seq #"functions without a contract:" (pr-str (form 1))))) "and a reason two facts hold is said once")))

(deftest a-prove-with-no-verdict-is-not-green
  ;; a live answer read "green: 0 of 0 passed" on a task naming one proof that was never written
  (let [run {:pass 0 :fail 0 :error 0 :ran 0 :reused 0 :deferred [] :reports [] :proofs {'app.okt/never-written :none} :unmatched ['app.okt/never-written]}
        line (:seon.print/code (my.agent/prove-summary {} run))]
    (is (str/starts-with? line "not green: 1 of 1 proofs have no verdict: app.okt/never-written") line)
    (is (str/includes? line "no test is named app.okt/never-written") "and says why"))
  (is (= "nothing judged: no proof and no test this change reaches"
         (:seon.print/code (my.agent/prove-summary {} {:pass 0 :fail 0 :error 0 :ran 0 :reused 0 :deferred [] :reports [] :proofs {}}))))
  (testing "a process test with no verdict: never green"
    (let [one {:pass 1 :fail 0 :error 0 :ran 1 :reused 0 :reports [{:type :pass :var "#'a/t"}] :proofs {}}]
      (is (str/starts-with? (:seon.print/code (my.agent/prove-summary {} (assoc one :deferred ['a/p] :unjudged "no checkout")))
                            "1 of 1 in-process tests passed; outstanding: 1 process tests with no verdict: no checkout. (my.agent/deferred) lists them."))
      (is (= "1 of 1 in-process tests passed, 1 of your 2 proofs among what passed; outstanding: 3 process tests with no verdict, your proofs a/p among them: later. (my.agent/deferred) lists them."
             (:seon.print/code (my.agent/prove-summary {} (assoc one :deferred ['a/p 'a/q 'a/r] :unjudged "later" :proofs {'a/t :pass 'a/p :deferred})))))))
  (is (str/starts-with? (:seon.print/code (my.agent/prove-summary {} {:pass 1 :fail 0 :error 0 :ran 1 :reused 0 :deferred [] :reports [{:type :pass :var "#'a/b"}] :proofs {'a/b :pass}})) "green: 1 of 1")))

(deftest a-prove-is-drawn-on-its-eval-red-and-died
  ;; docs/agent.md#done: a prove answers one line (my.agent/prove-summary) and what it found is drawn on
  ;; its eval from its value (seon.render.ui/proved): a red names its test and first failing assertion,
  ;; a test whose child died is listed as died and never as red
  (let [run {:pass 3 :fail 1 :error 0 :ran 3 :reused 1 :deferred []
             :reports [{:type :pass :var "#'app.okt/ok-test"}
                       {:type :fail :var "#'app.okt/bad-test" :message "the sum" :expected "(= 3 (+ 1 1))" :actual "(not (= 3 2))"}
                       {:type :error :var "#'app.okt/gone-test" :exit 137 :message "child died"}]
             :proofs {'app.okt/ok-test :pass 'app.okt/bad-test :fail}}
        html (h/html (ui/proved {:seon.render/index (seon.db/index)} run))]
    (is (= (str "red: 1 of 4 failed\n✗ app.okt/bad-test: the sum\n    asserted (= 3 (+ 1 1))\n    and got  (not (= 3 2))\nreused 1: their last result still counts, because nothing they reach has changed")
           (:seon.print/code (my.agent/prove-summary {} run))) "what failed, first failure, and only what is true of this run")
    (is (= "nothing judged: no proof and no test this change reaches\nno test is named app.okt/typo-test"
           (:seon.print/code (my.agent/prove-summary {} {:pass 0 :fail 0 :error 0 :ran 0 :reused 0 :deferred [] :reports [] :unmatched ['app.okt/typo-test]})))
        "a name that is no test is said, never a silent ran 0 (bench-scope-5)")
    (is (= #{'app.okt/gone-test} (seon.proofs/died run)))
    (is (str/includes? html "app.okt/bad-test") "the red test is named")
    (is (str/includes? html "the sum") "with its first failing assertion")
    (is (str/includes? html "died: ") "the dead child is listed")
    (is (not (str/includes? html "child died")) "and not drawn as a red's failure")
    ;; an agent's (select-keys (my.agent/prove) [:results ..]) keeps the schema in its metadata and drops
    ;; :reports: every tile of a1's page threw on proofs/failures' contract (2026-10-28)
    (let [picked (select-keys (with-meta run {:seon.render/schema :seon.test/proved}) [:pass :fail :proofs])]
      (is (= :seon.test/proved (r/schema-of (seon.db/index) picked nil)))
      (is (str/includes? (h/html (r/hiccup {:seon.render/index (seon.db/index)} picked)) "app.okt/bad-test") "a picked prove draws what it holds"))))

(deftest a-markdown-table-is-a-table-for-a-person
  ;; docs/render.md#text-and-the-one-string-cutter: a table is drawn as one for a person (:inline?), its cells' marks drawn, and kept as its
  ;; lines for the model (red before: a person read the pipes and the rule row)
  (let [md "Counts:\n\n| class | n |\n|---|---|\n| `:stale-section` | 369 |\n| `:eval` | 1 |\n"
        person (h/html (ui/markdown md {:inline? true}))
        model (h/html (ui/markdown md))]
    (is (str/includes? person "<table>") person)
    (is (str/includes? person "<th>class</th>") person)
    (is (= 2 (count (re-seq #"<tr><td>" person))) "two rows, the rule row left out")
    (is (str/includes? person "<code") "a cell's code span drawn")
    (is (not (str/includes? person "|---|")))
    (is (str/includes? model "|---|---|") "the model reads its lines")))

(deftest markdown-draws-each-block
  ;; docs/render.md#text-and-the-one-string-cutter: a plan or a reply drawn by its blocks, none dropped,
  ;; each block's text as written (its backticks pick what a citation pulls, and the model reads it)
  (let [h (h/html (ui/markdown "# Title\n\nA `code` and **bold** line\ncontinued.\n\n- one\n  more\n- two\n\n1. first\n2. second\n\n```clojure\n(+ 1 2)\n```\n\n---\n\ntail"))]
    (is (str/includes? h "<h3") "a heading at its level")
    (is (str/includes? h "A `code` and **bold** line\ncontinued.") "a paragraph as written")
    (is (str/includes? h "<ul") "a bulleted list")
    (is (str/includes? h "<ol") "a numbered list stays numbered")
    (is (= 4 (count (re-seq #"<li" h))) "two bullets and two numbered items")
    (is (str/includes? h "one\n  more") "a continuation line stays with its item")
    (is (str/includes? h "(+ 1 2)") "fenced code kept")
    (is (not (str/includes? h "```")) "fence marks are not text")
    (is (str/includes? h "<hr") "a break")
    (is (str/includes? h "tail") "nothing after the fence is lost")))

(deftest markdown-draws-fenced-code-and-a-numbered-list-from-the-scan
  ;; docs/render.md#text-and-the-one-string-cutter: a fenced block's content and a list's items are the scan's
  ;; offsets, so no regex reads the text again
  (let [fenced (ui/markdown "~~~clojure\n(+ 1 2)\n  (inc 3)\n~~~")
        open (ui/markdown "```\nnever closed")
        nums (ui/markdown "1. first\n   - nested\n2. second")]
    (is (= ["(+ 1 2)\n  (inc 3)"] (->> (tree-seq coll? seq fenced) (filter string?) (filter #(str/includes? % "(inc 3)"))))
        "a fenced block draws its content once, with no marks")
    (is (not (str/includes? (h/html fenced) "~~~")))
    (is (str/includes? (h/html open) "never closed") "an unclosed block draws what it holds")
    (is (= [:ol {:class "list-decimal pl-5"} [:li "first\n   - nested"] [:li "second"]] (last nums))
        "a numbered list is an ol of its items, a nested line inside its parent item")))

(deftest markdown-reads-no-text-again
  ;; docs/render.md#text-and-the-one-string-cutter: markdown draws what the scan found, and no regex reads the text
  ;; again (task seon.ref/markdown-reads-the-scan: md-scan #17 went green on the scan's proofs alone, its markdown
  ;; still splitting and replacing by regex). What the definition calls, as the compiler's facts say
  ;; (seon.source/accounts: its :refs), never a pattern over its text
  (let [src (my.agent/source-fn 'seon.render.ui/markdown)
        refs (into #{} (mapcat :refs) (:accounts (seon.source/accounts 'seon.render.ui src)))
        regex '[clojure.core/re-find clojure.core/re-seq clojure.core/re-matches clojure.core/re-pattern
                clojure.core/re-matcher clojure.string/split clojure.string/replace clojure.string/replace-first]]
    (is (some? src) "its source, as the process runs it")
    ;; its message names the regex functions markdown calls, never every symbol the compiler expanded it to (the
    ;; md-scan bench's agents read about 60 clojure.core names)
    (is (empty? (filter refs regex)) (str "a regex function in markdown: " (pr-str (filter refs regex))))))

(deftest a-task-title-is-its-name-in-words
  ;; docs/render.md#the-page: a task's id as its title
  (is (= "A long string" (ui/task-title {:seon.task/id "seon.print/a-long-string" :seon.task/plan "What exists: a long string."})))
  (is (= "REPL reads" (ui/task-title {:seon.task/id "seon.repl/REPL-reads"})) "capitals kept"))

;; docs/render.md#the-page: a person's request is titled by its first sentence as written (Data model, 2026-11-11): its task's name is that
;; sentence's words (seon.system/request-name, seon.task/request-words), so the title is computed from the plan, never stored
(deftest a-requests-title-is-its-first-sentence-as-written
  (let [ask "Prove the already-merged demo.math/half function and its test: verify demo.math/half is on main. Then report."
        id (str "request/" (seon.task/request-words ask))]
    (is (= "Prove the already-merged demo.math/half function and its test: verify demo.math/half is on main"
           (ui/task-title {:seon.task/id id :seon.task/plan ask})) "demo.math/half and the colon intact")
    (is (= "Prove the already-merged demo.math/half function and its test: verify demo.math/half is on main"
           (ui/task-title {:seon.task/id (str id "-2") :seon.task/plan ask})) "a name numbered because it was taken")
    (is (= "Make things" (ui/task-title {:seon.task/id "request/make-things" :seon.task/plan "Something else entirely."}))
        "a plan no longer opening with its name's words: the name, never a stale sentence")
    (is (= "A long string" (ui/task-title {:seon.task/id "seon.print/a-long-string" :seon.task/plan "What exists: a long string."}))
        "a task file's name, written by a person as its title, reads as itself")))

(declare sessions-db tree-db)

(defn- entry-of
  "Agent key's entry as a page draws it where its starter's pull joins it (seon.render.ui/agent nested, its pull by
  :seon.agent/entry's pattern and one level of the agents it started): its hiccup, the started agents in it as the pulled maps a walk
  places as their own entries."
  [db key]
  (let [;; the entry's own clauses: its join of the agents it started (asked to a depth, its key a vector with :seon.ctx/below) is asked here one level
        started? (fn [c] (and (map? c) (some #(= :seon.agent/_parent (if (vector? %) (first %) %)) (keys c))))
        e (vec (remove started? (r/schema-property (seon.db/index) :seon.agent/entry :seon.ctx/pattern)))]
    (#'ui/agent-entry db (seon.program/index-of-db db) (d/pull db (into [:db/id] (conj e {:seon.agent/_parent (into [:db/id] e)})) [:seon.agent/key key]))))

(deftest an-agents-entry-says-what-it-works-on
  ;; docs/users.md#whats-on-screen: an agent's entry (seon.render.ui/agent nested, wherever a page joins it): its task in words, how it
  ;; stands, a link to its page
  (let [db (sessions-db "b2" [["b2/a3" :a3 :running "seon.print/a-long-string" :running :task-a]])
        card (h/html (entry-of db "b2/a3"))]
    (is (str/includes? card "A long string") "its task, in words")
    (is (str/includes? card "working") "how its work stands")
    (is (str/includes? card "href=\"/agent/b2/a3\"") "its page")))

;; docs/users.md#whats-on-screen: a request and its work read as one tree: an agent's tile holds, under it, the tile of each agent it
;; started (its :seon.agent/_parent, read forward in the tile's pull). Cost: n = the agents under one tile (what that agent started); the
;; join is the tile's own entity's datoms of that attribute and a pull of each child, so a tile costs its own children, never the store's
;; agents, and each child tile is its own component, reused by its key
(deftest an-entry-holds-the-agents-its-agent-started
  (let [db (sessions-db "b2" [["b2/a2" :a2 :running "seon.print/a-long-string" :parked :task-a]
                              ["b2/a3" :a3 :running "seon.print/b-short-string" :running :task-b]])
        work-task (:db/id (:seon.agent/task (d/entity db [:seon.agent/key "b2/a3"])))
        ;; as production writes a started task: who started it, the agent working it, and the starter's :seon.agent/_parent (seon.task's
        ;; agent-tx writes it from the task's started-by)
        db (d/db-with db [[:db/add work-task :seon.task/started-by [:seon.agent/key "b2/a2"]] [:db/add work-task :seon.task/agent [:seon.agent/key "b2/a3"]]
                          [:db/add [:seon.agent/key "b2/a3"] :seon.agent/parent [:seon.agent/key "b2/a2"]]])
        ;; the started agents an entry places: the pulled maps under its "started" element, each drawn by its own entry in a walk
        kids (fn [hc] (keep #(when (and (map? %) (string? (:seon.agent/key %))) (:seon.agent/key %))
                             (mapcat #(when (and (vector? %) (= :div (first %)) (= "started" (:class (second %)))) (drop 2 %)) (tree-seq coll? seq hc))))]
    (is (= ["b2/a3"] (kids (entry-of db "b2/a2"))) "the request's entry holds its work agent's")
    (is (empty? (kids (entry-of db "b2/a3"))) "the work agent's holds none")))

(defn- user-units
  "The units of user name's page on main db, as its kept walk draws them (seon.render.proc/kept-walk: the agent's render over its pull),
  each composed with its nested units."
  [db name]
  (let [kw (#'p/kept-walk db (:db/id (d/entity db [:seon.user/name name])) :main {} identity)]
    (try (seon.fixtures/composed (r/placed-units (:w kw))) (finally (r/dispose-walk! (:w kw))))))

(deftest a-users-page-draws-the-agents-it-started
  ;; docs/users.md#whats-on-screen (step 3): a user is an agent found by its name; its page is the agent's render over its pull, which joins
  ;; the agents it started: each is drawn by its own render nested (an entry) among the page's tiles and in its agents list (the list
  ;; every page shows), each with the ones it started; where the pull's depth stops, the entry says how many are below
  (let [db (sessions-db "b2" [["b2/a1" :a1 :running "seon.print/a-long-string" :running :task-a] ["b2/a2" :a2 :idle "x/asks" :parked :task-b] ["b2/a3" :a3 :idle "x/other" :parked :task-c]])
        started (mapv #(:db/id (d/entity db [:seon.agent/key %])) ["b2/a1" "b2/a2"])
        db (d/db-with db (into [{:db/id "sean" :seon.agent/key "sean" :seon.user/name "sean"}] (started-tx "sean" started)))
        sean (d/entity db [:seon.user/name "sean"])
        in (fn [units sel] (apply str (map :html (filter #(= sel (:container %)) units))))
        units (user-units db "sean")]
    (is (= (:db/id sean) (#'p/agent-named db "sean")) "its name finds it, as an address")
    (is (and (str/includes? (in units "#p-task") "href=\"/agent/b2/a1\"") (str/includes? (in units "#p-task") "href=\"/agent/b2/a2\""))
        "an entry for each it started among its tiles")
    (is (not (str/includes? (in units "#p-task") "/agent/b2/a3")) "and not another's")
    (is (and (str/includes? (in units "#c-side") "href=\"/root\"") (str/includes? (in units "#c-side") "href=\"/agent/b2/a2\""))
        "its agents list: root, then what it started")))

(deftest a-users-agents-list-counts-the-agents-below-its-depth
  ;; docs/users.md#whats-on-screen: an entry holds the agents it started; at the depth the user's pull stops, how many are below
  (let [db (sessions-db "b2" [["b2/a1" :a1 :running "seon.print/a-long-string" :running :task-a] ["b2/a2" :a2 :idle "x/asks" :parked :task-b] ["b2/a3" :a3 :idle "x/other" :parked :task-c]])
        started (mapv #(:db/id (d/entity db [:seon.agent/key %])) ["b2/a1" "b2/a2"])
        db (d/db-with db (into [{:db/id "sean" :seon.agent/key "sean" :seon.user/name "sean"}] (started-tx "sean" started)))
        in (fn [units sel] (apply str (map :html (filter #(= sel (:container %)) units))))
        units (user-units db "sean")]
      (let [a1 (d/entity db [:seon.agent/key "b2/a1"])
            a3 (d/entity db [:seon.agent/key "b2/a3"])
            ;; as my.task/launch! writes it: the task names who started it, the agent made for it, and the starter's :seon.agent/_parent
            work (d/db-with db [{:db/id (:db/id (:seon.agent/task a3)) :seon.task/started-by (:db/id a1) :seon.task/agent (:db/id a3)}
                                [:db/add (:db/id a3) :seon.agent/parent (:db/id a1)]])
            ;; and on from a3, a chain one past the depth the user's pull reads (:seon.config.render/agent-tree-depth levels: a1 is the first)
            n (seon.config/value :seon.config.render/agent-tree-depth)
            chain (into ["b2/a3"] (map #(str "b2/c" %) (range 3 (+ n 2))))
            work (d/db-with work (into (mapv (fn [k] {:seon.agent/key k :seon.agent/status :idle}) (rest chain))
                                       (for [[x y] (map vector chain (rest chain))] {:seon.agent/key x :seon.agent/_parent [[:seon.agent/key y]]})))
            side (in (user-units work "sean") "#c-side")]
        ;; the user's pull (its schema's pattern: no :seon.ctx/pattern written on this user) holds entries to the depth, then ids
        (is (str/includes? side "href=\"/agent/b2/a3\"") "a1's started agent, within the depth: an entry")
        (is (str/includes? side (str "href=\"/agent/" (nth chain (- n 2)) "\"")) "the last level's agent: an entry")
        (is (not (str/includes? side (str "href=\"/agent/" (last chain) "\""))) "the one below the depth: no entry")
        (is (str/includes? side "1 more it started, below") (str "beyond the pull's depth, counted: " side))
        (is (not (str/includes? (in units "#c-side") "more it started")) "and nothing below an agent that started none"))))

;; See docs/render.md#the-page
(deftest a-member-pulled-without-its-identity-is-a-path
  ;; a join member pulled without its identity attribute is a path, never a unit: the members of its own joins are placed in its stead
  ;; (a user's agents read through the tasks it started, {:seon.task/_started-by [{:seon.task/agent ...}]}); pulled with its identity it is one
  (let [db (sessions-db "b2" [["b2/a1" :a1 :running "seon.print/a-long-string" :running :task-a]])
        db (d/db-with db [{:db/id "u" :seon.agent/key "sean" :seon.user/name "sean"}
                          {:seon.task/id "seon.print/a-long-string" :seon.task/started-by "u" :seon.task/agent [:seon.agent/key "b2/a1"]}])
        ix (seon.program/index-of-db db)
        u (:db/id (d/entity db [:seon.agent/key "sean"]))
        ;; as seon.ctx/build pulls: every level holds its :db/id
        placed (fn [p] (let [c (d/pull db (into [:db/id] p) u)]
                         (mapv #(let [m (seon.ratom/untracked %)] [(r/entity-schema ix m nil) (or (:seon.agent/key m) (:seon.task/id m))])
                               (#'ui/tiles ix (seon.ratom/ref-view (seon.ratom/atom c) seon.ctx/changes) p))))]
    (is (= [[:seon.db/agent-fact "b2/a1"]] (placed [{:seon.task/_started-by [:db/id {:seon.task/agent [:db/id :seon.agent/key :seon.agent/status]}]}]))
        "the task, pulled without its id, is the path: its agent is placed")
    (is (= [[:seon.task/task-fact "seon.print/a-long-string"]] (placed [{:seon.task/_started-by [:db/id :seon.task/id {:seon.task/agent [:db/id :seon.agent/key]}]}]))
        "pulled with its id, the task is the unit")))

(deftest an-entry-shows-the-last-exchange
  ;; docs/users.md#whats-on-screen: an agent's entry is titled by what the person typed and shows their newest message and its answer, never
  ;; an answer to an earlier one under a later question (owner, 2026-11-08: an atom tile showed a paragraph on agents)
  (let [db0 (sessions-db "b2" [["b2/a2" :a2 :idle "x/asks" :parked :task-b]])
        a (:db/id (d/entity db0 [:seon.agent/key "b2/a2"]))
        db1 (d/db-with db0 [{:db/id "u" :seon.agent/key "sean" :seon.user/name "sean"}
                            {:seon.task/id "x/asks" :seon.task/plan "What is (+ 2 3)?\nOne line." :seon.task/started-by "u" :seon.task/reason "It is five."}])
        db2 (d/db-with db1 [{:seon.message/from {:seon.agent/key "sean"} :seon.message/to [a] :seon.message/body "And times three?" :seon.message/sent-ms 1}])
        db3 (d/db-with db2 [{:seon.task/id "x/asks" :seon.task/reason "It is six."}])
        tile (fn [db] (h/html (entry-of db "b2/a2")))]
    (is (str/includes? (tile db1) "What is (+ 2 3)?") "titled by the first line the person typed")
    (is (not (str/includes? (tile db1) "x/asks</span>")) "not the task's id")
    (is (str/includes? (tile db1) "It is five.") "its answer, with nothing asked since")
    (is (str/includes? (tile db2) "And times three?") "the person's newest message")
    (is (not (str/includes? (tile db2) "It is five.")) "and not the answer to the earlier question")
    (is (str/includes? (tile db3) "It is six.") "its answer once root writes it")
    (is (str/includes? (tile db3) "And times three?"))))

(deftest the-most-specific-schema-names-an-entity
  ;; docs/ctx.md#the-pattern: an entity holding a user's and an agent's identity is a user whatever its keys' order (Context,
  ;; 2026-11-08: the first key named the schema, so a map's order decided its pattern, render and layout)
  (is (= :seon.db/user-fact (r/entity-schema (seon.db/index) (array-map :seon.agent/key "sean" :seon.user/name "sean") nil)))
  (is (= :seon.db/user-fact (r/entity-schema (seon.db/index) (array-map :seon.user/name "sean" :seon.agent/key "sean") nil)) "in either order")
  (is (= :seon.db/agent-fact (r/entity-schema (seon.db/index) {:seon.agent/key "b2/a1"} nil)))
  (is (= :seon.db/user-fact (r/schema-of (seon.db/index) (array-map :seon.agent/key "sean" :seon.user/name "sean") nil)) "and a value's schema the same way")
  (is (not= (r/schema-property (seon.db/index) :seon.db/agent-fact :seon.ctx/pattern) (r/schema-property (seon.db/index) :seon.db/user-fact :seon.ctx/pattern))
      "a user declares its own pattern, not the agent's it extends (docs/users.md#whats-on-screen)")
  (is (= :seon.agent/key (some #{:seon.agent/key} (keys (:entity-schemas (seon.db/index))))) "an attribute an extended schema names")
  (is (= :seon.db/agent-fact ((:entity-schemas (seon.db/index)) :seon.agent/key)) "is named by that schema alone"))

(deftest a-walk-builds-its-index-again-only-when-it-built-it-and-a-schema-entry-moves
  ;; docs/render.md#reactive-renders: the walk holds the entity index its renders read schemas in. One a caller gave it (an agent's slot,
  ;; the one copy) it never builds again, and takes the one each flush is handed; one it built from its db it builds again when a flush
  ;; moved a :schema/* datom, any other flush keeps it (O(moved), keyed by the write)
  (let [form [:map {:seon.db/attributes true} [:lab.walk/name {:optional true}]]
        db (seon.db/db-with (ts/test-db) [{:schema/key :lab.walk/thing :schema/form (binding [*print-namespace-maps* false] (seon.edn/encode form))}])
        schema-datoms (vec (d/datoms db :aevt :schema/form))
        other [{:e 1 :a :seon.eval/id :v "x" :tx 1 :added true}]
        walk (fn [& ix] (apply r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} (constantly db) [#'identity {}] ix))
        known? #(contains? (:forms @(:index %)) :lab.walk/thing)
        given (walk (seon.db/index))
        own (walk)]
    (flush! given nil) (flush! own nil)
    (is (known? own) "built from its db at its creation")
    (is (not (known? given)) "the caller's is held as given")
    (flush! given schema-datoms)
    (is (identical? (seon.db/index) @(:index given)) "a given index is never built again, whatever moved")
    (let [ix @(:index own)]
      (flush! own other)
      (is (identical? ix @(:index own)) "a flush that moves no schema datom keeps it")
      (flush! own schema-datoms)
      (is (not (identical? ix @(:index own))) "one that moved a schema datom builds the walk's own again"))
    (let [handed (seon.db/index-of {:forms {:lab.walk/thing form}})]
      (flush! given other handed)
      (is (identical? handed @(:index given)) "a flush handed an index holds it"))))

(deftest two-unrelated-identity-schemas-are-an-error
  ;; docs/ctx.md#the-pattern: a record whose identities name schemas neither extends is no one thing: an error naming them, never the
  ;; first key's; a value an agent made of such keys is drawn as the data it is
  (let [m (array-map :seon.task/id "x/y" :seon.agent/key "k")
        e (is (thrown? clojure.lang.ExceptionInfo (r/entity-schema (seon.db/index) m nil)))]
    (is (= :seon.render/no-one-schema (:seon.error/type (ex-data e))))
    (is (= #{:seon.task/task-fact :seon.db/agent-fact} (set (:schemas (ex-data e))))))
  (is (nil? (r/schema-of (seon.db/index) (array-map :seon.task/id "x/y" :seon.agent/key "k") nil)) "no schema is guessed for a value"))

(deftest an-entitys-schema-names-its-layout
  ;; docs/render.md#the-page: the render a walk of an entity is rooted on is the one its entity schema names, or the one it extends names
  (is (= #'ui/agent (r/render-var-of (seon.db/index) {:seon.agent/key "b2/a1"})))
  (is (= #'ui/agent (r/render-var-of (seon.db/index) (array-map :seon.user/name "sean" :seon.agent/key "sean"))) "a user is an agent: its page is the agent's render")
  (is (= #'ui/root (r/render-var-of (seon.db/index) {:seon.system/id :root})))
  (let [e (is (thrown? clojure.lang.ExceptionInfo (r/render-var-of (seon.db/index) {:var/name 'a/b})) "an entity whose schema names no render is an error, never a nil walk")]
    (is (= :seon.render/no-render (:seon.error/type (ex-data e))))
    (is (= [:var/name] (:identity (ex-data e))) "naming what it is"))
  (is (= :seon.render/no-render (:seon.error/type (ex-data (is (thrown? clojure.lang.ExceptionInfo (r/render-var-of (seon.db/index) {})))))) "and one with no identity at all"))

(deftest each-page-draws-its-entitys-layout
  ;; docs/render.md#a-page-is-a-kept-walk: root's, a user's and an agent's kept walks each draw the layout their entity names, no
  ;; caller choosing it
  (let [db0 (sessions-db "b2" [["b2/a1" :a1 :running "seon.print/a-long-string" :running :task-a]])
        db (d/db-with db0 [{:seon.agent/key "sean" :seon.user/name "sean"}])
        walk-layout (fn [eid branch ident] (let [kw (#'p/kept-walk db eid branch ident identity)]
                                              (try (first (:root (:w kw))) (finally (r/dispose-walk! (:w kw))))))]
    (is (= #'ui/agent (walk-layout (:db/id (d/entity db [:seon.user/name "sean"])) :main {})) "a user's: the agent's render")
    (is (= #'ui/root (walk-layout (:db/id (d/entity db [:seon.system/id :root])) :main {})))
    (is (= #'ui/agent (walk-layout (:db/id (d/entity db [:seon.agent/key "b2/a1"])) :main {:seon.agent/key "b2/a1"})))
    (is (= #'ui/agent (walk-layout nil :task-a {:seon.agent/key "b2/a1"})) "a branch that holds no entity yet: the agent's key names it")))

(defn- booted
  "The test world after a root's boot, which makes root's session (seon.system/root-agent-boot-tx, as root's boot writes it), and the
  person's first message to root."
  []
  (let [db (d/db-with (my.test/world) (into (seon.task/boot-tx "b1" "sean") (seon.system/root-agent-boot-tx (my.test/world))))]
    (d/db-with db (seon.task/message-tx "m-first" (seon.task/sender "sean") ["root"] "Hi root." 1))))

(deftest roots-session-is-an-agent-of-its-own
  ;; docs/users.md#roots-agent: root's session is an ordinary agent keyed "root", linked to root, working request/root, parked until a message
  ;; wakes it; root's own entity takes no key (an entity's id derives from its identity, so it cannot hold a second)
  (let [db (booted)
        s (d/entity db [:seon.agent/key "root"])
        t (d/entity db [:seon.task/id "request/root"])]
    (is (some? s) "an agent keyed root")
    (is (= (:db/id (d/entity db [:seon.system/id :root])) (:db/id (:seon.agent/root s))) "linked to root")
    (is (not= (:db/id s) (:db/id (d/entity db [:seon.system/id :root]))) "and not root's entity")
    (is (= :parked (:seon.task/outcome t)) "its task waits for a message")
    (is (= "root" (seon.task/agent-key db "request/root")) "and the ordinary spawn continues it by its key")
    (is (= :seon.db/agent-fact (r/entity-schema (seon.db/index) (d/pull db (quote [*]) (:db/id s)) nil)) "an agent like any other")
    ;; docs/users.md#who-started-whom (the owner, 2026-11-12: "Root is not special: it's the first agent; everyone descends from it")
    (is (nil? (:seon.agent/parent s)) "root's agent is the first: it has no parent")
    (is (nil? (:seon.agent/parent (d/entity db [:seon.user/name "sean"]))) "a user is never forked: it is in no agent's tree")
    (let [asked (d/db-with db [{:seon.task/id "request/t1" :seon.task/ns 'request :seon.task/name "t1" :seon.task/plan "Plan." :seon.task/kind :user
                                :seon.task/branch :task-request-t1 :seon.task/outcome :parked :seon.task/started-by [:seon.agent/key "sean"]}])
          asked (d/db-with asked (seon.task/start-tx asked "request/t1" "b1" :a9 {:branch :task-request-t1 :model nil :max-turns 4} 2))
          keys-below (fn kb [m] (cons (:seon.agent/key m) (mapcat kb (:seon.agent/_parent m))))]
      (is (= "root" (:seon.agent/key (:seon.agent/parent (d/entity asked [:seon.agent/key "a9"])))) "a person's request's agent is root's child: a person forks nothing")
      (is (= "sean" (:seon.agent/key (:seon.task/started-by (d/entity asked [:seon.task/id "request/t1"])))) "who asked stays the task's")
      (is (= #{"root" "a9"} (set (keys-below (d/pull asked [:seon.agent/key {:seon.agent/_parent '...}] [:seon.agent/key "root"]))))
          "and the tree from root reaches every agent"))
    (is (= #'ui/stage (r/render-var-of (seon.db/index) (d/pull db '[*] (:db/id s)))) "drawn by its own render, the stage (its :seon.render/hiccup), over the agent's pull")
    (is (= ["Hi root."] (mapv #(:seon.message/body (d/entity db (:e %))) (d/datoms db :avet :seon.message/to (:db/id s)))) "a message to root is its session's")
    (let [db2 (d/db-with db (seon.system/root-agent-boot-tx db))
          same (fn [d] [(dissoc (d/pull d '[*] [:seon.agent/key "root"]) :db/id) (dissoc (d/pull d '[*] [:seon.task/id "request/root"]) :db/id)])]
      (is (= (same db) (same db2)) "a boot after changes nothing of it"))
    (let [fresh (d/db-with (my.test/world) (into (seon.task/boot-tx "b1" "sean") (seon.system/root-agent-boot-tx (my.test/world))))]
      (is (some? (d/entity fresh [:seon.agent/key "root"])) "a root no one has messaged has its session from its boot")
      (is (= :parked (:seon.task/outcome (d/entity fresh [:seon.task/id "request/root"]))) "parked until a message"))))

(deftest a-boot-stores-the-role-names-and-writes-no-pattern-again
  ;; docs/users.md#roots-agent: the user stores no pattern (its pull is its schema's) and root's session stores its role's name and its one
  ;; join, written once when it is made; a second boot writes no pattern datom (a whole pattern written by code at boot was a stored copy
  ;; that went stale)
  (let [two [:seon.db/agent-fact {:seon.agent/task [{:seon.task/started-by :seon.db/user-fact}]}]
        db (booted)
        stored (fn [d] (set (map (juxt :e :v) (d/datoms d :aevt :seon.ctx/pattern))))]
    (is (nil? (:seon.ctx/pattern (d/pull db [:seon.ctx/pattern] [:seon.user/name "sean"]))) "the user holds no pattern")
    (is (= two (:seon.ctx/pattern (d/pull db [:seon.ctx/pattern] [:seon.agent/key "root"]))) "root's session: its role's name and one join")
    (let [r (d/with db (into (seon.task/boot-tx "b2" "sean") (seon.system/root-agent-boot-tx db)))]
      (is (empty? (filter #(= :seon.ctx/pattern (:a %)) (:tx-data r))) "a second boot writes no pattern datom")
      (is (= (stored db) (stored (:db-after r))) "and every stored pattern is as it was"))))

(deftest a-clause-the-role-gains-reaches-roots-session-with-no-write
  ;; docs/ctx.md#the-pattern: root's session names its role's pattern, so a clause the agent schema's pattern gains reaches its pull at once,
  ;; with no boot and no write (an entity index in which :seon.db/agent-fact's pattern asks one clause more, as a branch's own index is: seon.db/index-of)
  (let [ix (seon.db/index)
        db (d/db-with (booted) [{:seon.agent/key "root" :seon.agent/holder "a clause the role gained"}])
        a (seon.db/eid db :seon.agent/key "root")
        props (update (seon.contract/properties :seon.db/agent-fact) :seon.ctx/pattern #(conj (vec %) :seon.agent/holder))
        ;; the schema's own form with its pattern one clause longer: its entries are what make it an entity schema in the index
        forms {:seon.db/agent-fact (assoc (get seon.contract/forms :seon.db/agent-fact) 1 props)}
        before (seon.ctx/agent-ctx ix db a)
        ;; a branch's changed schema is read by the index derived from it (seon.db/index-of), as an agent's is
        after (seon.ctx/agent-ctx (seon.db/index-of {:forms forms}) db a)]
    (is (nil? (:seon.agent/holder before)) "the role's pattern does not ask it")
    (is (= "a clause the role gained" (:seon.agent/holder after)) "once the role's pattern asks it, root's session's pull holds it")
    (is (= [:seon.db/agent-fact {:seon.agent/task [{:seon.task/started-by :seon.db/user-fact}]}] (:seon.ctx/pattern (d/pull db [:seon.ctx/pattern] a))) "with nothing written")))

(deftest a-users-pull-holds-its-agents-to-the-depth-and-below-only-their-ids
  ;; docs/users.md#whats-on-screen: the user's pull is its schema's pattern (:seon.db/user-fact's, the agents it started read by :seon.agent/entry's):
  ;; on a chain user -> its task -> a1 -> a2 -> ... deeper than :seon.config.render/agent-tree-depth, the entries go that many levels and the last holds
  ;; only the ids of the ones below it, so it says how many there are and nothing is cut silently
  (let [n (seon.config/value :seon.config.render/agent-tree-depth)
        ks (mapv #(str "b1/a" %) (range 1 (+ n 3)))
        db (d/db-with (my.test/world) (into (seon.task/boot-tx "b1" "sean")
                                            (concat (map (fn [k] {:seon.agent/key k :seon.agent/status :running}) ks)
                                                    (started-tx {:seon.agent/key "sean"} [{:seon.agent/key (first ks)}])
                                                    (for [[x y] (map vector ks (rest ks))] {:seon.agent/key x :seon.agent/_parent [[:seon.agent/key y]]}))))
        tree (seon.ctx/agent-ctx (seon.db/index) db (seon.db/eid db :seon.user/name "sean"))
        ;; the first level through the task the person started (a user is in no process tree), then the agents each forked
        levels (take-while some? (iterate #(first (vals (:seon.agent/_parent %))) (:seon.task/agent (first (vals (:seon.task/_started-by tree))))))]
    (is (nil? (:seon.ctx/pattern (d/pull db [:seon.ctx/pattern] [:seon.user/name "sean"]))) "no pattern stored: its schema's")
    (is (= (subvec ks 0 n) (vec (keep :seon.agent/key levels))) "entries to the configured depth")
    (is (= (seon.db/eid db :seon.agent/key (nth ks n)) (:db/id (last levels))) "below the last, the next one's id")
    (is (nil? (:seon.agent/key (last levels))) "and nothing else of it")
    (is (= (inc n) (count levels)) "nothing deeper")))

(deftest roots-session-page-lists-no-sessions
  ;; docs/users.md#roots-agent: root's session's record holds the user's tree (its pattern's :seon.agent/parent join); its model reads none of
  ;; it in its page (it asks at its REPL); another agent's record holds none
  (let [db0 (booted)
        db (d/db-with db0 [{:seon.task/id "request/greet" :seon.task/ns 'request :seon.task/name "greet" :seon.task/plan "Say hi.\nOne line." :seon.task/kind :user
                            :seon.task/branch :task-request-greet :seon.task/outcome :parked}
                           {:seon.agent/key "b1/a2" :seon.agent/task [:seon.task/id "request/greet"]}
                           {:seon.agent/key "root" :seon.agent/_parent [[:seon.agent/key "b1/a2"]]}
                           ;; a session of an earlier boot, started by no agent of the user's tree: root's session does not see it
                           {:seon.task/id "request/older" :seon.task/ns 'request :seon.task/name "older" :seon.task/plan "An older ask." :seon.task/kind :user
                            :seon.task/branch :task-request-older :seon.task/outcome :parked}
                           {:seon.agent/key "b0/a9" :seon.agent/task [:seon.task/id "request/older"]}
                           ;; the person asks root's session something, as the home box will
                           {:seon.message/from {:seon.agent/key "sean"} :seon.message/to [[:seon.agent/key "root"]] :seon.message/body "What is running?" :seon.message/sent-ms 1}])
        db (d/db-with db [{:seon.agent/key "root" :seon.agent/task [:seon.task/id "request/root"]}])
        part (fn [k] (let [c (seon.ctx/agent-ctx (seon.db/index) db (:db/id (d/entity db [:seon.agent/key k])))
                           w (draw c seon.ctx/changes db (r/render-var-of (seon.db/index) c) {:model-only true})]
                       (try (pr-str (r/placed-hiccup w (r/page-lists w))) (finally (r/dispose-walk! w)))))
        root-text (part "root")]
    ;; Context's ruling (step 3): root's model reads no list of sessions in its page (a scan of every agent each request); it asks at
    ;; its REPL. The system entity its pull joins draws nothing nested (seon.render.ui/root), so no session's address is in its page
    (is (not (str/includes? root-text "/agent/b1/a2")) "root's session's page lists no other session")
    (is (not (str/includes? root-text "/agent/b0/a9")) "nor one of an earlier boot")
    (is (not (str/includes? root-text ":seon.config.")) "and none of root's config")
    (is (not (str/includes? (part "b1/a2") "the person Seon works for")) "another agent's page holds no tree of the user's")
    ;; its status answers: it threw "Duplicate key: root" when a set literal held "root" and the agent's own key, root's session's
    ;; being "root" too (seen live on a demo root, 2026-11-08)
    (is (map? (seon.ctx.status/status db (seon.ctx/agent-ctx (seon.db/index) db (:db/id (d/entity db [:seon.agent/key "root"]))) {})) "and how its work stands")))

(deftest roots-session-asks-for-its-sessions
  ;; docs/users.md#roots-agent: root's session's page lists no sessions (its model reads none in each request); it asks at its REPL,
  ;; (my.user/agents), which reads them from its record's join to the user who started it: every session in the user's tree, how each stands, by key; an
  ;; agent whose pattern holds no such join has none
  (let [db (d/db-with (booted) [{:seon.task/id "request/greet" :seon.task/ns 'request :seon.task/name "greet" :seon.task/plan "Say hi." :seon.task/kind :user
                                 :seon.task/branch :task-request-greet :seon.task/outcome :parked}
                                {:seon.agent/key "b1/a2" :seon.agent/task [:seon.task/id "request/greet"] :seon.agent/status :waiting}
                                {:seon.agent/key "root" :seon.agent/_parent [[:seon.agent/key "b1/a2"]]}])
        as (fn [k] (let [c (seon.ctx/agent-ctx (seon.db/index) db (:db/id (d/entity db [:seon.agent/key k])))]
                     (try (seon.ctx/install! {:tree c}) (my.user/agents)
                          (finally (seon.ctx/install! nil)))))
        listed (as "root")]
    (is (some #(= {:key "b1/a2" :status :waiting :task "request/greet" :outcome :parked} %) listed) (pr-str listed))
    (is (= (sort (map :key listed)) (map :key listed)) "by key")
    (is (= [] (as "b1/a2")) "an agent whose pattern holds no join through root: none")))

(deftest roots-session-holds-every-agent-with-its-status-and-task
  ;; docs/users.md#roots-agent: root's session's own pattern reaches every agent through root's entity, each with its status and task
  (let [db0 (booted)
        mk (fn [i] [{:seon.task/id (str "request/t" i) :seon.task/ns 'request :seon.task/name (str "t" i) :seon.task/plan "Plan." :seon.task/kind :user
                     :seon.task/branch (keyword (str "task-request-t" i)) :seon.task/outcome :parked}
                    {:seon.agent/key (str "b1/a" i) :seon.agent/status :running :seon.agent/task [:seon.task/id (str "request/t" i)]}])
        db (d/db-with (d/db-with db0 (mapcat mk [1 2 3])) [{:seon.agent/key "root" :seon.agent/_parent (for [i [1 2 3]] [:seon.agent/key (str "b1/a" i)])}])
        tree (seon.ctx/agent-ctx (seon.db/index) db (seon.db/eid db :seon.agent/key "root"))
        ;; a person's requests' agents are root's children (seon.task/forked-from)
        agents (vals (:seon.agent/_parent tree))
        by-key (into {} (map (juxt :seon.agent/key identity)) agents)]
    (is (every? by-key ["b1/a1" "b1/a2" "b1/a3"]) (pr-str (keys by-key)))
    (is (= [:running :running :running] (mapv #(:seon.agent/status (by-key %)) ["b1/a1" "b1/a2" "b1/a3"])))
    (is (= "request/t2" (get-in by-key ["b1/a2" :seon.agent/task :seon.task/id])) "and its task")
    (is (= :parked (get-in by-key ["b1/a2" :seon.agent/task :seon.task/outcome])))
    (is (not-any? #{:root} (keys tree)) "no :as name: keyed by attributes")))

(deftest a-users-ctx-holds-the-agents-it-started
  ;; docs/users.md#whats-on-screen: the user's pull (its schema's pattern, :seon.db/user-fact's; a user stores none): the agents it started, each with its status,
  ;; to the documented depth; its focus is main's, which the render proc reads to route home (seon.render.proc/location), not its page
  (let [db0 (d/db-with (my.test/world) (seon.task/boot-tx "b1" "sean"))
        db (d/db-with db0 [{:seon.agent/key "b1/a1" :seon.agent/status :running}
                           {:seon.agent/key "b1/a2" :seon.agent/status :idle}
                           {:seon.agent/key "sean" :seon.user/focus [:seon.agent/key "b1/a1"]}])
        db (d/db-with db (started-tx [:seon.agent/key "sean"] [[:seon.agent/key "b1/a1"] [:seon.agent/key "b1/a2"]]))
        tree (seon.ctx/agent-ctx (seon.db/index) db (seon.db/eid db :seon.user/name "sean"))
        ;; a user is in no process tree: the agents it started are its tasks' (:seon.task/_started-by, each task a path to its agent)
        agents (keep :seon.task/agent (vals (:seon.task/_started-by tree)))]
    (is (nil? (:seon.user/focus tree)) "its focus is not its page's: main's, read where home is routed")
    (is (= #{(seon.db/eid db :seon.agent/key "b1/a1") (seon.db/eid db :seon.agent/key "b1/a2")} (set (map :db/id agents))))
    (is (= #{:running :idle} (set (keep :seon.agent/status agents))))))

(deftest a-users-page-moves-with-main
  ;; docs/render.md#a-page-is-a-kept-walk: a user's page is a kept walk, as root's is: a move of main draws what it reached, so an agent it starts appears on a page already open
  (let [db0 (sessions-db "b2" [["b2/a1" :a1 :running "seon.print/a-long-string" :running :task-a]])
        db1 (d/db-with db0 [{:seon.agent/key "sean" :seon.user/name "sean"}])
        kw (#'p/user-walk {} db1 (d/entity db1 [:seon.user/name "sean"]))
        st {:user-walks {"sean" kw} :views {[:chat :sean] {} [:chat :a1] {}}}
        side (fn [w] (apply str (map :html (filter #(= "#c-side" (:container %)) (seon.fixtures/page-units w)))))
        before (side (:w kw))
        rows (fn [w] (apply str (map :html (filter #(= "#p-task" (:container %)) (seon.fixtures/page-units w)))))
        ;; read now: the walk is advanced in place
        rows-before (rows (:w kw))
        db2 (d/db-with db1 (started-tx [:seon.agent/key "sean"] [(:db/id (d/entity db1 [:seon.agent/key "b2/a1"]))]))
        st' (#'p/user-walks st db2)]
    (is (not (str/includes? before "/agent/b2/a1")) "nothing started yet")
    (is (str/includes? (side (:w (get (:user-walks st') "sean"))) "/agent/b2/a1") "the agent it started is on the page, with no new draw")
    (is (not (str/includes? rows-before "/agent/b2/a1")) "no tile while nothing is started")
    (is (str/includes? (rows (:w (get (:user-walks st') "sean"))) "/agent/b2/a1") "the first agent's entry too, among the page's tiles")
    (is (contains? (:dirty st') [:chat :sean]) "and its view is dirty")
    (is (not (contains? (:dirty st') [:chat :a1])) "another's isn't")))

(deftest what-a-person-types-is-words-and-forms-by-the-one-reader
  ;; docs/users.md#four-interfaces: text that isn't Clojure is a message, whatever the agent is doing, and a form evaluates; seon.reader/forgive tells them apart
  (let [reqs (fn [forms a] (mapv first (seon.web/requests {:var 'seon.system/submit! :agent :a1 :forms forms} {:a1 a})))
        say (fn [r] (select-keys r [:op :body])) ]
    (is (= [{:op :message :body "An otter."}] (map say (reqs "An otter." {}))) "words, to an agent the page knows no task for")
    (is (= [{:op :message :body "An otter."}] (map say (reqs "An otter." {:task "x/y"}))))
    (is (= [:eval] (map :op (reqs "(+ 1 2)" {:task "x/y"}))) "a form evaluates")
    (is (= "(+ 1 2)" (:forms (first (reqs "(+ 1 2)" {:task "x/y"})))))
    (let [mixed (reqs "Try this.\n(+ 1 2)\nand tell me." {:task "x/y"})]
      (is (= [:message :eval] (map :op mixed)) "both, the words first")
      (is (= "Try this.\nand tell me." (:body (first mixed))))
      (is (= "(+ 1 2)" (str/trim (:forms (second mixed))))))))

(deftest home-is-whoever-is-signed-in
  ;; docs/users.md#whats-on-screen: / is for whoever root's :seon.system/signed-in names (their focus, else root's page); with no one, a sign-in page
  (let [db0 (sessions-db "b2" [["b2/a1" :a1 :running "seon.print/a-long-string" :running :task-a]])
        db (d/db-with db0 (into [{:db/id "u" :seon.agent/key "sean" :seon.user/name "sean"}
                                 {:seon.system/id :root :seon.system/signed-in "u"}]
                                (started-tx "u" [(:db/id (d/entity db0 [:seon.agent/key "b2/a1"]))])))
        sean (:db/id (d/entity db [:seon.user/name "sean"]))]
    (is (some? sean) "a user signed in")
    (is (= (#'p/location db :root) (#'p/location db [:chat :home])) "home, with no focus, is root's page")
    (is (= [:chat :home] (#'seon.web/view-of (ui/view-param [:chat :home]))) "and its feed names it again")
    (is (nil? (#'p/location db0 [:chat :home])) "no one signed in: no home")
    (is (str/includes? (apply str (map :html (:seon.render/units (#'p/page-value {:main-db db0} [:chat :home])))) "sign in") "but a page saying so")
    (is (str/includes? (h/html (ui/root-side {:seon.ctx/ctx {}})) "href=\"/root\"") "root's own page is /root")))

(deftest a-record-that-no-longer-fits-its-schema-is-its-data
  ;; docs/render.md#the-page: a stored value outlives the schema it was written under; the page and the model read its data
  (let [old {:t 1 :classes {:a-class-since-removed {:n 1 :rows []}}}]
    (is (false? (seon.contract/fits? :seon.problems/report old)))
    (is (nil? (r/stored-model (seon.db/index) (with-meta old {:seon.render/schema :seon.problems/report}))) "the model reads it by the printer")
    (is (str/includes? (h/html (r/hiccup {:seon.render/index (seon.db/index) :schema :seon.problems/report} old)) "a-class-since-removed") "the page draws it as data")
    (is (not (str/includes? (h/html (r/hiccup {:seon.render/index (seon.db/index) :schema :seon.problems/report} old)) "threw")))))

(defn- root-page-html
  "Root's page on main db as the render proc draws it (seon.render.proc/page-value :root: root's agent's page, made at boot, with the
  system's own view beside it), its units composed, as one html string."
  [db]
  (let [st (root-state (proc-state db db) db)]
    (try (apply str (map :html (remove :parent (seon.fixtures/composed (:seon.render/units (#'p/page-value st :root))))))
         (finally (some-> (get-in st [:root-walk :w]) r/dispose-walk!)))))

(deftest root-reads-what-is-going-on
  ;; docs/render.md#the-page: root's head says what is going on, in words; its page lists every session root holds, each by its entry,
  ;; those that need the person first (the entry's group, a datum the page orders by)
  (let [db (tree-db "b2" [["b2/a1" :a1 :idle "x/asks" :parked :t1] ["b2/a2" :a2 :running "x/works" :running :t2] ["b2/a3" :a3 :ended "x/done" :merged :t3]])
        c (:tree (seon.ctx/build (seon.db/index) db (seon.db/eid db :seon.system/id :root) :db))
        html (root-page-html db)]
    (is (every? #(str/includes? html (str "href=\"/agent/" % "\"")) ["b2/a1" "b2/a2" "b2/a3"]) "every session with a task")
    (is (re-find #"data-standing=\"parked\"[^>]*id=\"agent-b2_2Fa1\"" html) "the parked one in the group that comes first")
    (is (re-find #"data-standing=\"finished\"[^>]*id=\"agent-b2_2Fa3\"" html) "the merged one in the last")
    (let [head (pr-str (ui/root-head {:seon.render/index (seon.db/index) :seon.ctx/ctx (or c {}) :seon.db/db db}))]
      (is (str/includes? head "1 task parked") "the head says so, in the state's plain word, with what it counts")
      (is (str/includes? head "1 task running") "a finished task is not running, whatever its execution still says"))))

(deftest how-an-agents-work-stands-is-said-from-its-data
  ;; owner, 2026-11-05: an agent whose task hit its turn cap read "waiting" and "idle", though a capped task is not woken
  ;; by a message. ui/standing is the one function the header and both agent lists ask (seon.reconcile/park-outcomes: a
  ;; parked task is woken by a message, a capped one waits for its budget to be raised)
  (let [st (fn [status o & {:as more}] (ui/standing (merge {:seon.agent/status status} (select-keys more [:seon.agent/max-turns :seon.agent/outcome :seon.agent/ended-turn :seon.agent/turn :seon.agent/reason]))
                                                     (when o {:seon.task/outcome o :seon.task/reason (:task-reason more)})))
        text (fn [x] (str (:text x) (when (:detail x) (str " · " (:detail x)))))]
    (testing "a task its agent works"
      (is (= "working" (text (st :busy :running))))
      (is (= "working · between turns" (text (st :idle :running))) "between two forms it still works, and says so")
      (is (= "starting" (text (st :spawning :running))))
      (is (= :live (:tone (st :busy :running)))))
    (testing "parked, or told, only where a message would be taken"
      (is (= "parked · waits for a message" (text (st :idle :parked))))
      (is (= "told · ended, said how" (text (st :idle :told))))
      (is (= "parked · waits for a message" (text (st :ended :parked))) "its process is gone, and a message starts it again")
      (is (= "parked · waits for a message" (text (st :dead :parked)))))
    (testing "a capped task is stopped, however idle its process looks"
      (is (= "stopped at its cap · 40 turns" (text (st :idle :cap :seon.agent/max-turns 40))))
      (is (= "stopped at its cap · 38 turns" (text (st :idle :cap :seon.agent/max-turns 40 :seon.agent/ended-turn 38))) "the turns it took when it recorded them")
      (is (= "stopped at its cap" (text (st :idle :cap))) "no turns known, none said")
      (is (not-any? #(str/includes? (text (st :idle :cap)) %) ["waiting" "idle"]))
      (is (= :stopped (:tone (st :idle :cap)))))
    (testing "a task's end"
      (is (= ["done" "ready to merge" "at the merge gate" "merged" "behind main"]
             (mapv #(text (st :ended %)) [:done :ready :gate :merged :behind])))
      (is (= ["died" "stopped by a restart" "killed" "refused" "closed"] (mapv #(text (st :dead %)) [:dead :lost :killed :refused :closed])))
      (is (= :quiet (:tone (st :dead :lost))) "a restart is no failure of its work")
      (is (= :done (:tone (st :ended :merged))))
      (is (= :failed (:tone (st :dead :dead)))))
    (testing "its reason, the task's else the agent's own"
      (is (= "proof-red seon.x/y" (:reason (st :idle :cap :task-reason "proof-red seon.x/y"))))
      (is (= "proof-red seon.x/y" (:reason (ui/standing {:seon.agent/status :idle :seon.agent/outcome :cap :seon.agent/ended-turn 4 :seon.agent/reason "proof-red seon.x/y"} nil))))
      (is (nil? (:reason (st :busy :running)))))
    (testing "an agent with no task outcome: its own end while it is at the turn it ended on, else its status"
      (is (= "stopped at its cap · 4 turns" (text (ui/standing {:seon.agent/status :idle :seon.agent/outcome :cap :seon.agent/ended-turn 4 :seon.agent/turn 4} nil))))
      (is (= "done" (text (ui/standing {:seon.agent/status :idle :seon.agent/outcome :green :seon.agent/ended-turn 4} nil))))
      (is (= "idle" (text (ui/standing {:seon.agent/status :idle :seon.agent/outcome :cap :seon.agent/ended-turn 4 :seon.agent/turn 5} nil))) "it was woken since")
      (is (= ["working" "idle" "finished" "killed" "stopped"] (mapv #(text (ui/standing {:seon.agent/status %} nil)) [:busy :idle :ended :killed :dead]))))))

(defn- sessions-db
  "A main db whose root is at boot, holding sessions, each [key id status task-id outcome branch] (a part left nil is not written):
  the agents of any boot, as a store holds them after a root restarts; none has started any other."
  [boot sessions]
  (reduce d/db-with (ts/test-db)
          (concat
           [[{:seon.system/id :root :seon.system/boot boot}]
            (vec (for [[tid o b] (distinct (for [[_ _ _ tid o b] sessions :when tid] [tid o b]))]
                   (cond-> {:seon.task/id tid :seon.task/ns (symbol (namespace (symbol tid))) :seon.task/name (name (symbol tid)) :seon.task/plan "p"
                            :seon.task/execution :running :seon.task/branch (or b :task-b) :seon.task/kind :work}
                     o (assoc :seon.task/outcome o)
                     (= :cap o) (assoc :seon.task/reason "proof-red seon.ref-test/an-alias"))))]
           (for [[k id status tid _ b] (reverse sessions)]
             [(cond-> (if (= :user id) {:seon.agent/key k} {:seon.agent/key k :seon.agent/boot (first (str/split k #"/")) :seon.agent/max-turns 40 :seon.agent/branch (or b :task-b)})
                status (assoc :seon.agent/status status) b (assoc :seon.agent/branch b)
                tid (assoc :seon.agent/task [:seon.task/id tid]))]))))

(defn- tree-db
  "`sessions-db` with the one tree (docs/users.md#who-started-whom): root's session (the agent keyed \"root\", its pattern its role's
  name, as seon.system/root-agent-tx writes it) is the first agent, and the agent of each request the person (:seon.user/name \"sean\",
  no pattern of its own: its schema's, and in no tree) started is root's child, its task started by the person (seon.task/forked-from,
  start-tx): those of started (default: every session listed), which root's page reads through its session's pull."
  ([boot sessions] (tree-db boot sessions (map first sessions)))
  ([boot sessions started]
   ;; root's session works for the starter of its own task (request/root, started by the person), as seon.system/root-agent-tx writes it
   (let [db (d/db-with (sessions-db boot sessions)
                       [{:db/id "sean" :seon.agent/key "sean" :seon.user/name "sean"}
                        {:db/id "rt" :seon.task/id "request/root" :seon.task/ns 'request :seon.task/name "root" :seon.task/plan "p" :seon.task/kind :work
                         :seon.task/execution :running :seon.task/branch :task-b :seon.task/started-by "sean" :seon.task/agent "root"}
                        {:db/id "root" :seon.agent/key "root" :seon.ctx/pattern [:seon.db/agent-fact {:seon.agent/task [{:seon.task/started-by :seon.db/user-fact}]}]
                         :seon.agent/task "rt"}])
         by (into {} (for [[k _ _ tid] sessions] [k tid]))]
     (d/db-with db (vec (mapcat (fn [k] (cond-> [{:seon.agent/key k :seon.agent/parent [:seon.agent/key "root"]}]
                                          (by k) (conj {:seon.task/id (by k) :seon.task/started-by [:seon.agent/key "sean"] :seon.task/agent [:seon.agent/key k]})))
                                started))))))


(defn- session-eid [db k] (seon.db/eid db :seon.agent/key k))

(deftest an-entity-shown-as-its-data-model
  ;; docs/render.md#an-entity-may-name-its-own-render: an entity naming seon.render.ui/pull-view as its own render shows, from its pull and
  ;; the entity index, where its pattern comes from, each clause with what the pull holds for it, and what its schema names that the
  ;; pattern does not ask: root's session here, whose pattern is its own
  (let [db (sessions-db "b2" [["b2/a1" :a1 :idle "x/asks" :parked :t1]])
        st (root-state (branch-state db db) db)
        main (:main-db st)
        main (d/db-with main [{:db/id (seon.db/eid main :seon.agent/key "root") :seon.render/hiccup 'seon.render.ui/pull-view}])
        html (root-page-html main)
        front (re-find #"(?s)<section[^>]*pull-front.*?Show as data" html)]
    (is (some? front) html)
    (is (not (str/includes? html "component-defect")) "nothing threw")
    (is (str/includes? front "its own (:seon.ctx/pattern on the entity)") "its pattern is its own")
    (is (str/includes? front ":seon.agent/key") "a clause it asks")
    (is (str/includes? front ":seon.agent/parent") "a join it asks, with its members: who started it")
    (is (str/includes? front "does not ask") "what its schema names beside")
    (is (str/includes? front "Your own render: the function your page is drawn by") "a clause says what its attribute is, in the attribute's own words")))

(deftest an-entity-as-its-data-model-has-one-producer
  ;; docs/render.md#an-entity-may-name-its-own-render: seon.render/pulled is the one reading of a pulled entity as its data model, which the
  ;; pull view lays out and an agent calls at its REPL (my.db/pulled): each clause with what the pull holds, a join asked and empty as 0
  ;; members (never a missing clause), a sub-pattern given by a schema's name read as that schema's pattern, what the schema names and
  ;; the entity holds that the pattern does not ask, and the renders that draw it
  (let [k "inspect/a1"
        db (:db-after (d/with (ts/test-db)
                              [{:seon.agent/key k :seon.agent/task {:seon.task/id "lab/inspect"} :seon.agent/turn 1}
                               {:seon.task/id "lab/inspect" :seon.task/ns 'app.x :seon.task/name "inspect" :seon.task/plan "A plan."
                                :seon.task/kind :work :seon.task/proofs #{} :seon.task/execution :running :seon.task/branch :main/lab-inspect}
                               {:seon.model-call/id (str k "/1") :seon.model-call/agent [:seon.agent/key k] :seon.model-call/n 1 :seon.model-call/reply "I will look."}
                               {:seon.eval/id "e1" :seon.eval/agent [:seon.agent/key k] :seon.eval/by {:seon.agent/key k} :seon.eval/call [:seon.model-call/id (str k "/1")]}]))
        a (seon.db/eid db :seon.agent/key k)
        ix (seon.db/index)
        t (:tree (seon.ctx/build ix db a :b))
        m (r/pulled db ix t nil)
        by (into {} (map (juxt :key identity)) (:clauses m))]
    (is (= :seon.db/agent-fact (:schema m)))
    (is (= :schema (:source m)) "no pattern of its own: its schema's")
    (is (= 1 (:value (by :seon.agent/turn))) "an attribute's clause holds what the pull holds")
    (is (nil? (:value (by :seon.agent/status))) "an attribute asked and not held is a clause with no value")
    (is (= 0 (:members (by :seon.message/_to))) "a join asked and empty is 0 members, never a missing clause")
    (is (= 1 (:members (by :seon.eval/_agent))))
    (is (= {:clause :seon.eval/id :held 1} (first (filter #(= :seon.eval/id (:clause %)) (:sub (by :seon.eval/_agent))))) "a sub-clause says how many members hold it")
    (is (= {:clause :seon.eval/code :held 0} (first (filter #(= :seon.eval/code (:clause %)) (:sub (by :seon.eval/_agent))))) "and 0 for one none holds")
    (is (some #(= :seon.task/plan (:clause %)) (:sub (by :seon.agent/task))) "a sub-pattern given by a schema's name is read as the pattern that schema declares")
    (is (contains? (set (:unasked m)) :seon.agent/holder) "what its schema names and the pattern does not ask")
    (is (= [] (:held-unasked m)) "everything this entity holds is asked")
    (is (= {:hiccup 'seon.render.ui/agent :model 'seon.ctx.wire/agent-msgs} (:renders m)))
    (let [mt (r/pulled db ix (:seon.agent/task t) (:pattern (by :seon.agent/task)))]
      (is (= :given (:source mt)) "a joined node is read by the sub-pattern it was pulled by")
      (is (= :seon.task/task-fact (:schema mt)))
      (is (some #(= :proved (:key %)) (:clauses mt)) "a join held under its :as is found there"))
    (let [mo (r/pulled db ix {:db/id a :seon.agent/key k :seon.ctx/pattern [:seon.agent/key :seon.ctx/pattern]} nil)]
      (is (= :entity (:source mo)) "an entity holding its own pattern is read by it")
      (is (= [:seon.agent/task :seon.agent/turn] (:held-unasked mo)) "what the entity holds on the db that its pattern does not ask"))))

(deftest a-pulled-entity-reads-a-schemas-name-in-its-pattern-as-that-schemas-pattern
  ;; docs/ctx.md#the-pattern: seon.render/pulled reads an entity's own pattern expanded (seon.render/expanded): a schema's name among its
  ;; clauses lists the clauses that schema's pattern asks, never the name, and the pattern is still the entity's own
  (let [k "inspect/named"
        db (:db-after (d/with (ts/test-db) [{:seon.agent/key k :seon.agent/turn 1}]))
        ix (seon.db/index)
        own [:seon.db/agent-fact :seon.agent/started-ms]
        node {:db/id (seon.db/eid db :seon.agent/key k) :seon.agent/key k :seon.ctx/pattern own}
        m (r/pulled db ix node nil)
        asked (set (map :clause (:clauses m)))]
    (is (= :entity (:source m)) "its own pattern")
    (is (= (:clauses (r/pulled db ix node (conj (vec (r/schema-property ix :seon.db/agent-fact :seon.ctx/pattern)) :seon.agent/started-ms)))
           (:clauses m))
        "clause by clause, the schema's pattern and the added clause")
    (is (every? asked [:seon.agent/key :seon.agent/started-ms]) "the schema's clauses and the added one")
    (is (some #(and (contains? % :members) (= :seon.agent/task (:attr %))) (:clauses m)) "the joins the schema's pattern asks")
    (is (not (asked :seon.db/agent-fact)) "never the schema's name as an attribute")))

(deftest a-schemas-name-whose-pattern-reaches-itself-is-refused
  ;; docs/ctx.md#the-pattern: a schema's name among a pattern's clauses is spliced by its pattern (seon.render/expanded); one whose pattern
  ;; names it again, through another, is refused with a typed error naming it, as a join's schema that reaches itself is
  (let [forms {:lab.loop/a-fact [:map {:seon.db/attributes true :seon.ctx/pattern [:lab.loop/b-fact :lab.loop/x]} [:lab.loop/x :lab.loop/x]]
               :lab.loop/b-fact [:map {:seon.db/attributes true :seon.ctx/pattern [:lab.loop/a-fact :lab.loop/y]} [:lab.loop/y :lab.loop/y]]
               :lab.loop/x :string :lab.loop/y :string}
        ix (seon.db/index-of {:forms forms})
        said (binding [malli.registry/*registry* (seon.contract/registry-of forms)]
               (try (r/pattern ix [:lab.loop/a-fact]) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (= :seon.ctx/invalid-pattern (:seon.error/type said)) (pr-str said))
    (is (str/includes? (str said) "reaches itself") (pr-str said))))

(deftest an-own-renders-part-is-the-models-unless-marked-for-the-page
  ;; docs/render.md#an-entity-may-name-its-own-render: the lists a walk reads as parts (seon.render/page-lists, what the model reads) hold
  ;; the pull view's front, a part like any, and not the stage's, which its render marks for the page alone (red before: the pull view's
  ;; list was marked page-only, so an agent putting it on itself never read its own pull)
  (let [db (booted)
        a (seon.db/eid db :seon.agent/key "root")
        ix (seon.db/index)
        lists (fn [r] (let [db (d/db-with db [{:db/id a :seon.render/hiccup r}])
                            t (:tree (seon.ctx/build ix db a :db))
                            w (r/draw t seon.ctx/changes db (r/render-var-of ix t) {:index ix})]
                        (try (set (r/page-lists w)) (finally (r/dispose-walk! w)))))]
    (is (contains? (lists 'seon.render.ui/pull-view) "#p-head") "the pull view's front is a part")
    (is (not (contains? (lists 'seon.render.ui/stage) "#p-head")) "the stage's front is the page's alone")))

(deftest a-render-name-that-resolves-to-nothing-is-said
  ;; docs/render.md#an-entity-may-name-its-own-render: an entity naming a render of its own that this process has no var for is drawn by
  ;; its schema's render with the typed error naming the symbol where its own would be (red before: the default, and nothing said why)
  (let [db (booted)
        a (seon.db/eid db :seon.agent/key "root")
        db (d/db-with db [{:db/id a :seon.render/hiccup 'no.such/stage}])
        ix (seon.db/index)
        t (:tree (seon.ctx/build ix db a :db))
        w (r/draw t seon.ctx/changes db (r/render-var-of ix t) {:index ix})
        units (try (seon.fixtures/page-units w) (finally (r/dispose-walk! w)))
        html (apply str (map :html units))]
    (is (= 'no.such/stage (:seon.render/hiccup t)) (str "its pull holds the name: " (pr-str (take 6 (:seon.ctx/pattern (d/pull db '[:seon.ctx/pattern] a))))))
    (is (= #'ui/agent (r/render-var-of ix t)) "drawn by its schema's render")
    (is (some #(= #'ui/unresolved-list %) (flatten (ui/agent {:seon.render/index ix :seon.ctx/ctx t} t))) (pr-str (ui/agent {:seon.render/index ix :seon.ctx/ctx t} t)))
    (is (str/includes? html "no.such/stage names no render this process has") (pr-str (map :id units)))))

(deftest an-agents-input-keeps-its-place-as-it-goes-busy-and-idle
  ;; docs/render.md#an-entity-may-name-its-own-render: the interrupt button is there while the process lives, disabled when nothing runs,
  ;; so the field keeps its width (red before: it came and went with the agent's status, and the field jumped three times in one turn)
  (let [in (fn [busy?] (h/html (ui/agent-input {:unit "p-input"} {:id :a1 :address :a1 :live? true :focus? false :busy? busy? :talks? true})))]
    (is (str/includes? (in true) "data-interrupt"))
    (is (str/includes? (in false) "data-interrupt") "idle: still there")
    (is (str/includes? (in false) "disabled") "idle: disabled")
    (is (not (str/includes? (in true) "disabled")) "busy: it interrupts")))

(deftest roots-sessions-list-an-agent-once-under-its-starter
  ;; docs/users.md#roots-agent: root holds every agent, and an entry draws the agents it started under it, so the top level is what
  ;; root's pull holds that no held agent started (Point's ruling, 2026-11-11); red before: a2 was listed twice, at the top and under a1
  (let [db (tree-db "b2" [["b2/a1" :a1 :idle "x/asks" :parked :t1] ["b2/a2" :a2 :running "x/works" :running :t2]] ["b2/a1"])
        db (d/db-with db [{:db/id (session-eid db "b2/a1") :seon.agent/_parent (session-eid db "b2/a2")}])
        html (root-page-html db)
        hrefs (re-seq #"href=\"/agent/b2/a2\"" html)]
    (is (= 1 (count hrefs)) html)
    (is (re-find #"(?s)href=\"/agent/b2/a1\".*class=\"started\".*href=\"/agent/b2/a2\"" html) "under the agent that started it")))

(deftest roots-sessions-show-the-user-as-the-person-seon-works-for
  ;; docs/users.md#roots-agent: root holds the user as it holds every agent, and nothing started it; its entry says what the data says
  ;; it is (it holds :seon.user/name), the person Seon works for, never a session with a standing (Point's ruling, 2026-11-11)
  (let [db (tree-db "b2" [["b2/a1" :a1 :idle "x/asks" :parked :t1]])
        html (root-page-html db)
        person (re-find #"(?s)<section[^>]*data-standing=\"person\".*?</section>" html)]
    (is (some? person) html)
    (is (str/includes? person "the person Seon works for") person)
    (is (str/includes? person "href=\"/agent/sean\"") "its page")
    (is (not (re-find #"data-standing=\"finished\"[^>]*id=\"agent-sean\"" html)) "not a finished session")
    (is (str/includes? html "href=\"/agent/b2/a1\"") "what the person asked for stays in root's list (red before: the user's join hid it)")))

(deftest roots-page-follows-the-person-starting-an-agent
  ;; docs/users.md#roots-agent: root's page is a kept walk; an agent the person starts afterwards is placed under them as it happens
  ;; (red before: the person's entry read its started agents untracked, so a live page never placed one)
  (let [db (tree-db "b2" [["b2/a1" :a1 :idle "x/asks" :parked :t1]] [])
        st (root-state (branch-state db db) db)
        [st1 _] (pass-view st :root)
        main' (d/db-with (:main-db st1) (started-tx [:seon.agent/key "sean"] [[:seon.agent/key "b2/a1"]]))
        st2 (#'p/advance-main st1 main')
        [st3 _] (pass-view st2 :root)]
    (try (is (not (str/includes? (p/view-html st1 :root) "href=\"/agent/b2/a1\"")) "before: not started by the person")
         (is (str/includes? (p/view-html st3 :root) "href=\"/agent/b2/a1\"") "after: under the person, live")
         (finally (r/dispose-walk! (get-in st [:root-walk :w]))))))

(deftest a-stopped-agent-does-not-read-as-waiting-in-its-head-or-its-lists
  (let [a {:seon.agent/id :a6 :seon.agent/status :idle :seon.agent/max-turns 40 :seon.agent/key "b2/a6"}
        t {:seon.task/id "seon.ref/an-alias" :seon.task/outcome :cap :seon.task/reason "proof-red seon.ref-test/an-alias"}
        head (h/html (ui/agent-head {} (assoc a :seon.agent/task t)))
        db (sessions-db "b2" [["b2/a6" :a6 :idle "seon.ref/an-alias" :cap :task-b] ["b2/a7" :a7 :idle "x/y" :parked :task-y]])
        entry (h/html (entry-of db "b2/a6"))]
    (is (str/includes? head "● stopped at its cap · 40 turns") head)
    (is (str/includes? head "proof-red seon.ref-test/an-alias") "its reason, in the header line")
    (is (not (str/includes? head "waiting")))
    (is (str/includes? entry "stopped at its cap") entry)
    (is (str/includes? entry ">An alias</span>") "the entry names the agent by its task")
    (is (not (str/includes? entry ">b2/a6<")) "its key only in the title")
    (is (not-any? #(str/includes? entry %) ["idle" "waiting"]))
    (is (str/includes? (h/html (entry-of db "b2/a7")) "parked") "a parked task can be answered")
    (is (str/includes? (h/html (ui/agent-head {} {:seon.agent/id :a7 :seon.agent/status :idle :seon.agent/task {:seon.task/outcome :parked}})) "● parked"))))

(deftest an-agents-pill-is-one-link-that-names-its-task
  ;; owner, 2026-11-05: the agents list read "a6 idle" and only the name was a link, though the pill looks like a button.
  ;; The whole entry is the link and says what the agent works on, cut to fit, the whole name in its title (the entry, wherever placed)
  (let [long-id "seon.print/a-long-string-that-does-not-starve-its-siblings-however-long-it-runs"
        db (sessions-db "b2" [["b2/a6" :a6 :idle "seon.ref/an-alias" :cap :task-seon-ref-an-alias]
                              ["b2/a7" :a7 :busy long-id :running :task-long]
                              ["b2/a8" :a8 :idle nil nil :task-demo-f]])
        c {:agents {1 {:seon.agent/id :a6 :seon.agent/key "b2/a6" :seon.agent/status :idle :task {:seon.task/id "seon.ref/an-alias" :seon.task/outcome :cap}}}}
        side (fn [k] (h/html (entry-of db k)))
        a6 (side "b2/a6") a7 (side "b2/a7") a8 (side "b2/a8")]
    (is (= 1 (count (re-seq #"<a " a6))) "one link")
    (is (str/includes? a6 "href=\"/agent/b2/a6\"") "by its key")
    (let [[_ inside] (re-find #"<a [^>]*>(.*)</a>" a6)]
      (is (and (str/includes? inside "○") (str/includes? inside "An alias") (str/includes? inside "stopped at its cap")) "its dot, name (its task in words) and status are inside the link"))
    (is (str/includes? a6 "title=\"") "the whole name in a title")
    (is (str/includes? a6 "seon.ref/an-alias") "the title names the task whole")
    (is (re-find #"truncate[^>]*>A long string that does not starve" a7) "a long name is cut to the entry's width by the page (truncate), never by dropping text")
    (is (str/includes? (second (re-find #"<a [^>]*>(.*)</a>" a8)) "task-demo-f") "no task: its branch")
    (testing "root's line is one link too"
      (let [root (h/html (ui/root-side {:seon.ctx/ctx c}))]
        (is (str/includes? (second (re-find #"<a [^>]*href=\"/root\"[^>]*>(.*)</a>" root)) "main"))))))

(deftest every-session-on-main-is-listed-across-boots
  ;; coordinator, 2026-11-05: after a root restarts its agents list drew "agent-side threw" and no agent; the person (user), with no status
  ;; and no task, made standing throw. Step 3: root's page lists every session root holds (its session's pull through the system entity,
  ;; [:seon.db/agent-fact]), each by its entry under the user who started it
  (let [db (tree-db "b2" [["b2/user" :user nil nil nil nil]
                              ["b2/a1" :a1 :busy "seon.x/now" :running :task-now]
                              ["b1/a1" :a1 :dead "seon.x/old" :cap :task-old]
                              ["b1/a2" :a2 :ended "seon.x/older" :merged :task-older]
                              ["b0/a1" :a1 :dead "seon.x/oldest" :cap :task-oldest]])
        ;; a person's request: its entry says what was asked, its plan's first line
        db (d/db-with db [{:seon.task/id "seon.x/older" :seon.task/plan "Older work, merged.\nThe rest."}])
        html (root-page-html db)]
    (testing "an agent with no status and no task reads nothing, and draws"
      (is (= :status/none (:state (ui/standing {:seon.agent/id :user} nil))))
      (is (str/includes? (h/html (entry-of db "b2/user")) "href=\"/agent/b2/user\"")))
    (testing "every boot's sessions are on root's page, each linked by its key"
      (is (not (str/includes? html "defect")) html)
      (is (str/includes? html "href=\"/agent/b2/a1\""))
      (is (and (str/includes? html "href=\"/agent/b1/a1\"") (str/includes? html "href=\"/agent/b1/a2\"") (str/includes? html "href=\"/agent/b0/a1\"")))
      (is (str/includes? html "stopped at its cap") "each with its standing, no process needed")
      (is (str/includes? html "merged"))
      (is (str/includes? html "Older work, merged.") "and what its person asked"))))

(deftest an-address-names-a-session-however-a-person-holds-it
  ;; coordinator, 2026-11-05: after a root restarts no past session opened (an id names one of this boot's). The session's
  ;; key is stable: one address for every page of it, its task's id or its branch resolve to its agent
  (let [db (sessions-db "b2" [["b2/user" :user nil nil nil nil]
                              ["b1/a4" :a4 :dead "seon.program/vars-history" :cap :task-seon-program-vars-history]
                              ["b2/a4" :a4 :busy "seon.other/now" :running :task-other-now]])
        old (session-eid db "b1/a4") now (session-eid db "b2/a4")
        loc #(#'p/location db %)]
    (is (= old (loc (keyword "b1/a4"))) "by its key")
    (is (= old (loc [:chat (keyword "b1/a4")])) "its chat")
    (is (= old (loc (keyword "seon.program/vars-history"))) "by its task's id")
    (is (= old (loc :task-seon-program-vars-history)) "by its branch")
    (is (= old (loc :main/task-seon-program-vars-history)) "or main's name for it")
    (is (= now (loc (keyword "b2/a4"))) "the newest by its key")
    (is (nil? (loc (keyword "b9/a1"))) "an address naming none")
    (is (nil? (loc :task-nobody)))
    (testing "the path: the suffix is cut first, so a key's slash is no matter"
      (is (= [[:chat (keyword "b1/a4")] (keyword "b1/a4") [:debug (keyword "b1/a4")]]
             (mapv #'seon.web/view-of ["b1/a4" "b1/a4/ctx" "b1/a4/debug"])))
      (is (= [(keyword "seon.program/vars-history") [:chat :task-seon-program-vars-history]]
             [(#'seon.web/view-of "seon.program/vars-history/ctx") (#'seon.web/view-of "task-seon-program-vars-history")])))
    (testing "every page of a session with no process reads from its branch, by its key, and its tabs link by it"
      (let [st {:rid 1 :pending {} :watch #{} :waiting {} :views {} :branches {} :main-db db :view {:agents {}}}
            chat (#'p/page-value st [:chat (keyword "b1/a4")])
            ctx (#'p/page-value st (keyword "b1/a4"))
            html (apply str (h/html (:seon.render/hiccup chat)) (map :html (:seon.render/units chat)))]
        (is (not (:seon.error/type chat)) (pr-str (:seon.error/message chat)))
        (is (not (:seon.error/type ctx)))
        (is (str/includes? html "href=\"/agent/b1/a4/debug\"") "its @ctx tab, by its key")
        (is (str/includes? html "href=\"/agent/b1/a4/debug\""))
        (is (str/includes? html "stopped at its cap") "and how it stands")
        (is (not (str/includes? html "<textarea")) "no process: no REPL box")
        (is (not (str/includes? html "interrupt")))
        (is (not (:seon.error/type (#'p/page-value (assoc st :branches {[:debug (keyword "b1/a4")] {:db db}}) [:debug (keyword "b1/a4")]))))))
    (testing "an address naming no session is that error, answered 404, with the way home"
      (let [st {:rid 1 :pending {} :watch #{} :waiting {9 [:chat (keyword "b9/a1")] 10 [:chat (keyword "b1/a4")]} :kf-wanted #{} :new-events [] :events [] :views {} :branches {}
                :merges 0 :main-db db :view {:agents {}}}
            o (volatile! {})
            _ (#'p/pass st o)
            pages (into {} (for [m (:to-web @o) :when (= :page (:op m))] [(:k m) m]))]
        (is (= 404 (:status (get pages 9))))
        (is (str/includes? (:html (get pages 9)) "href=\"/root\"") "a link to every session")
        (is (str/includes? (:html (get pages 9)) "b9/a1") "the message names the address")
        (is (nil? (:status (get pages 10))) "a session's page has no status: 200")))))

(deftest an-agent-with-a-task-can-be-spoken-to-with-no-process
  ;; docs/users.md#messages: a recipient needs no process: words wake it, whatever its state, addressed by its key; only an agent with no task and no process has nothing to take them
  (let [draw (fn [v] (h/html (ui/agent-input {:unit "p-input"} v)))
        has-form? #(str/includes? % "<textarea")]
    (is (has-form? (draw {:id :a1 :address :b1/a1 :live? false :focus? true :busy? false :talks? true})) "no process, a task: it can be spoken to")
    (is (str/includes? (draw {:id :a1 :address :b1/a1 :live? false :focus? true :busy? false :talks? true}) "agent=b1%2Fa1") "by its key, which any boot resolves")
    (is (has-form? (draw {:id :a1 :address :b1/a1 :live? true :focus? true :busy? false :talks? false})) "a live agent's REPL")
    (is (not (has-form? (draw {:id :a1 :address :b1/a1 :live? false :focus? true :busy? false :talks? false}))) "neither: nothing would take it")))

(deftest the-task-line-of-the-side-panel-reads-through-standing
  ;; the chat's side panel said "task · seon.program · running" of a capped task: running is the task's execution, not how it stands
  (let [t {:seon.task/id "seon.program/x" :seon.task/execution :running :seon.task/outcome :cap :seon.task/plan "p"}
        html (h/html (ui/task-view {:seon.render/index (seon.db/index)} t))]
    (is (str/includes? html "task · seon.program · stopped at its cap") html)
    (is (not (str/includes? html "running")))
    (is (str/includes? (h/html (ui/task-view {:seon.render/index (seon.db/index)} (dissoc t :seon.task/outcome))) "task · seon.program · running") "no outcome yet: its execution as before")
    ;; the agent's own end (an agent whose branch holds no outcome of its task) is the agent's own state, drawn by its render
    ;; (seon.render.ui/agent-state: a-merged-task-reads-merged-everywhere)
    ))

(deftest a-long-part-on-the-ctx-page-starts-closed-and-opens-on-demand
  ;; owner, 2026-11-05: @ctx was 1.3 MB and 614,773 px tall: the status part, as the model reads it, is a million
  ;; characters printed whole beside the part drawn. A part over the value budget is a closed <details> (its size in tokens); opened
  ;; (the tab's /open toggle, :open) it is the whole text, never cut. A part within budget is shown as it was
  (let [big (into [:ul] (for [i (range 4000)] [:li (str "row " i " of a long part")]))
        parts {"p-small" [:p "a short part"] "p-big" big}
        html {"p-small" "<p>drawn small</p>" "p-big" "<p>drawn big</p>"}
        closed (h/html (ui/ctx-parts parts html #{} ""))
        opened (h/html (ui/ctx-parts parts html #{"d-part-p-big"} ""))
        pair (fn [h id] (second (re-find (re-pattern (str "(?s)(<div[^>]*data-part=\"" id "\".*?drawn)")) h)))]
    (is (str/includes? closed "a short part") "a part within budget is shown whole, as before")
    (is (not (str/includes? (pair closed "p-small") "<details")) "and not folded")
    (is (str/includes? closed "drawn big") "the part drawn is shown")
    (is (not (str/includes? closed "row 3999 of a long part")) "the long text is not in the page until opened")
    (is (< (count closed) 10000) (str "a long part costs its summary: " (count closed) " chars"))
    (is (re-find #"<details[^>]*>\s*<summary[^>]*>[^<]*tok" (pair closed "p-big")) "folded, its size in tokens on the line")
    (is (not (re-find #"<details[^>]* open" (pair closed "p-big"))) "closed")
    (is (str/includes? closed "unit=d-part-p-big") "its toggle tells the render proc (/open)")
    (is (str/includes? opened "row 3999 of a long part") "opened it is whole")
    (is (re-find #"<details[^>]* open" (pair opened "p-big")) "and its details are open")
    (is (not (str/includes? (pair closed "p-big") "md:grid-cols-2")) "a long text does not share the row with the part drawn: it has the width")
    (is (str/includes? (pair closed "p-small") "md:grid-cols-2") "a short one does")
    (testing "a part the tab opened (the /open toggle) moves the page's unit, so the render proc sends it"
      (let [[main branch _] (paged-dbs)
            st (root-state (proc-state main branch) main)
            rev (fn [st] (:input (some #(when (= "d-parts" (:id %)) %) (:seon.render/units (#'p/page-value st :a3)))))]
        (is (some? (rev st)))
        (is (not= (rev st) (rev (assoc-in st [:tab :a3 :open] #{"d-part-p-big"}))))
        (is (= (rev st) (rev (assoc-in st [:tab :a3 :open] #{"pc-error"}))) "a toggle of another unit does not")))))

(deftest the-model-and-the-page-read-the-same-parts
  ;; task seon.ctx/the-context-is-the-agents-evals-and-seons (2026-10-29: "build with both the context
  ;; render and the web ui render in mind so we don't let one get out of sync with the other"): the
  ;; parts the model's request holds (seon.ctx/drawn-parts, the #p-task list of render-ctx) are, by
  ;; element id, the hiccup of the page's units of that id (the page /agent/{name} draws, render-ctx);
  ;; the conversation beside them holds the reply and the evals
  (let [[main branch _] (paged-dbs)
        k "a3"
        c (seon.ctx/build (seon.db/index) branch (seon.db/eid branch :seon.agent/key k) :task-demo-f)
        model (seon.fixtures/drawn-parts branch c)
        st (root-state (proc-state main branch) main)
        page (#'p/page-value st [:chat :a3])
        units (:seon.render/units page)
        in (fn [sel] (filterv #(= sel (:container %)) units))
        w (a3-walk branch)
        shown (try (r/placed-hiccup w ["#p-task"]) (finally (r/dispose-walk! w)))]
    (is (seq model) "the model reads parts")
    (is (= (keys model) (remove #(= % "agent-state") (map :id (in "#p-task")))) "the page's task list is the model's parts, by id and in order; its rules are the page's alone (docs/render.md#style)")
    (is (seq (in "#p-rules")) "the page draws its rules")
    (is (= model shown) "each part's hiccup the model reads is the hiccup the page's unit of that id is written from")
    (doseq [u (in "#p-task")] (is (str/includes? (:html u) (str "id=\"" (:id u) "\"")) "and the unit is that part"))
    (let [conv (apply str (map :html (filter #(= "#c-rows" (:container %)) (seon.fixtures/composed units))))]
      (is (str/includes? conv "it doubles") "the chat holds the model's reply")
      (is (str/includes? conv "(f 2)") "and an eval's line")
      (is (str/includes? conv "check g") "and a message"))
    (is (nil? (ns-resolve 'seon.render.ui 'chat-ctx)) "no second layout")
    (testing "the debug view is @ctx printed as data, not a second layout"
      (let [d (#'p/page-value st :a3)
            ds (filterv #(= "d-ctx" (:id %)) (:seon.render/units d))]
        (is (= 1 (count ds)) "one unit, the agent's record")
        (is (str/includes? (:html (first ds)) ":seon.eval/_agent") "its keys, by the one printer")
        (is (empty? (filterv #(= "#p-task" (:container %)) (:seon.render/units d))) "none of the page's parts")))
    (r/dispose-walk! (get-in st [:root-walk :w]))))

(deftest an-agents-chat-reads-as-a-conversation
  ;; docs/render.md#the-page (owner, 2026-10-28: a chat a person reads): a model turn is a
  ;; bubble of its reply with its thinking folded, a message a bubble with its sender, the agent's status its last line
  ;; (the agent's record holds the task, turns and messages: seon.agent/the-agent-is-the-unit-not-the-run)
  (let [c {:seon.agent/task {:seon.task/id "seon.print/a-long-string" :seon.task/plan "Make `seon.print/text` share its budget."}
           :seon.agent/status :idle :seon.agent/max-turns 8
           :seon.model-call/_agent {2 {:seon.model-call/n 1 :seon.model-call/origin :model :seon.model-call/reply "The string takes the whole budget; I'll **reproduce** it first." :seon.model-call/reasoning "Read select first."}
                   3 {:seon.model-call/n 2 :seon.model-call/origin :model :seon.model-call/reply "" :seon.model-call/reasoning ""}}
           :seon.message/_to {4 {:seon.message/from {:seon.agent/key "user"} :seon.message/body "Is the premise right?"}}}
        h #(h/html %)]
    (testing "a model turn is its reply, its thinking folded"
      (let [t (h (ui/model-call-view {} (get-in c [:seon.model-call/_agent 2])))]
        (is (str/includes? t "I'll <strong>reproduce</strong> it first."))
        (is (str/includes? t "<details") "its thinking folded")
        (is (str/includes? t "Read select first."))
        (is (not (str/includes? t "turn 1")) "its turn and tokens are for the debug tab"))
      (is (nil? (ui/model-call-view {} (get-in c [:seon.model-call/_agent 3]))) "a turn that said nothing draws no bubble")
      (let [t (h (ui/model-call-view {} {:seon.model-call/n 3 :seon.model-call/origin :model :seon.model-call/reply ""
                                         :seon.model-call/reasoning "First I read **it**.\n\nThen I check.\n\nThen I write."}))]
        (is (str/includes? t "First I read <strong>it</strong>.") "a turn with no reply but reasoning: its first paragraph is the agent's words")
        (is (str/includes? t "2 more paragraphs of thinking") "the rest folded under how much more there is")
        (is (re-find #"(?s)<details>.*Then I check" t) "folded")))
    (testing "a message is a bubble with its sender"
      (let [m (h (ui/message {} (get-in c [:seon.message/_to 4])))]
        (is (str/includes? m "user"))
        (is (str/includes? m "Is the premise right?"))))
    (testing "the agent's status is the last line"
      (is (= "idle" (last (ui/chat-end {:seon.render/index (seon.db/index) :seon.ctx/ctx c :seon.db/db (ts/test-db)}))) "an idle agent says so, in its state's plain word"))))

(deftest a-page-path-names-its-view-and-back
  ;; docs/render.md#the-page: an agent's chat, debug view and model context, by its id or its branch, and back
  (doseq [[path view] [["a4" [:chat :a4]] ["a4/ctx" :a4] ["a4/debug" [:debug :a4]]
                       ["main/task-x" [:chat :main/task-x]] ["main/task-x/ctx" :main/task-x] ["root" :root] ["root/debug" [:debug :root]]]]
    (is (= view (#'seon.web/view-of path)) path)
    (is (= path (java.net.URLDecoder/decode (ui/view-param view) "UTF-8")) path)))

(deftest an-agents-head-names-a-pid-only-while-its-process-runs
  ;; the browser check of 2026-10-29: a page showed an ended process's pid beside "idle"; the head names
  ;; the current process's pid (seon.task/newest-process) only while that process has not ended
  (let [head (fn [ps] (pr-str (ui/agent-head {} {:seon.agent/id :a1 :seon.agent/status :idle :processes ps})))]
    (is (str/includes? (head {1 {:seon.process/pid 7 :seon.process/forked-ms 1.0}}) "pid 7") "a running process's pid")
    (is (not (str/includes? (head {1 {:seon.process/pid 7 :seon.process/forked-ms 1.0 :seon.process/ended-ms 2.0}}) "pid 7")) "an ended one's is not shown")
    (is (str/includes? (head {1 {:seon.process/pid 7 :seon.process/forked-ms 1.0 :seon.process/ended-ms 2.0}
                              2 {:seon.process/pid 9 :seon.process/forked-ms 3.0}}) "pid 9") "the newest process's")
    (is (str/includes? (head [{:seon.process/pid 9 :seon.process/forked-ms 3.0}]) "pid 9") "root's own pull holds them as a vector")))

(defn throwing-view "A component (it reads the db) that throws an error with data, as a broken parser does."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [_opts]
  (throw (ex-info "seon.ref/scan invalid-output" {:seon.error/type :seon.instrument/contract-error :problems [{:in [:seon.ref/blocks 3]}]})))
(defn fine-view "A component (it reads the db) that draws."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [_opts] [:p "drawn"])
(defn two-part-layout "A list of two components, one that throws (a component itself: the walk's root)."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [_opts]
  ;; its list inside its root element, as render-ctx's #p-task is (the walk gives a component's root its own id)
  [:div [:div {:id "parts"} [#'throwing-view {}] [#'fine-view {}]]])

(defn boom "A helper a render calls, which throws." {:malli/schema [:=> [:cat] :any]} [] (throw (ex-info "no such key" {})))
(defn helper-throwing-view "A component whose helper throws."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [_opts] [:p (boom)])
(defn helper-layout "A list of one component whose helper throws."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [_opts] [:div [:div {:id "parts"} [#'helper-throwing-view {}]]])

(deftest a-components-defect-says-what-threw-and-where-in-a-line
  ;; docs/errors.md#nothing-crashes-and-nothing-hangs: a defect's first line, as text, names the render, what was thrown and the fn it
  ;; was thrown in (seon.error/thrown-in), so a reader takes it without walking the error's data (red before: the error was only a
  ;; map nested in the part; a model spent 18 turns finding what threw, Context's trial 2026-11-11)
  (let [w (r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} (constantly (ts/test-db)) [#'helper-layout {}])]
    (try (r/flush! w nil)
         (seon.fixtures/no-error-render @(:defects w) {:expects #{"seon.render-test/helper-throwing-view"}})
         (let [[[_ part]] (seq (r/placed-hiccup w ["#parts"]))
               line (some #(when (and (vector? %) (= :p (first %))) (second %)) (tree-seq coll? seq part))]
           (is (= "seon.render-test/helper-throwing-view threw: no such key (in seon.render-test/boom)" line) (pr-str part)))
         (finally (r/dispose-walk! w)))))

(defn interrupted-view
  "A component whose run is interrupted."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [_] (throw (ThreadDeath.)))

(defn interrupted-layout
  "A layout whose only component is interrupted while it draws."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [_] [:div [:div {:id "parts"} [#'interrupted-view {}]]])

(deftest a-private-helpers-rules-reach-the-page
  ;; docs/render.md#style: the rules of every var of a drawn render's namespace reach the page, a private helper's too (red before: read
  ;; from ns-publics, the stage's rules on its private helpers stage-now and stage-folds never reached a page)
  (let [rs (into {} (r/rules {#'ui/stage-front nil}))]
    (is (str/includes? (get rs 'seon.render.ui/stage-now "") ".stage-front h1") (pr-str (keys rs)))
    (is (str/includes? (get rs 'seon.render.ui/stage-folds "") "details[data-as]"))))

(deftest a-component-that-throws-is-a-part-holding-its-error
  ;; owner, 2026-10-29: "the UI components are all separately rendered so it's okay if some are
  ;; errors just make sure it's visible to the agent": a call of the error's render on the error as data, which the model
  ;; reads, the thrown error's own data in it; every other part draws
  (let [w (r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} (constantly (ts/test-db)) [#'two-part-layout {}])]
    (try (r/flush! w nil)
         (seon.fixtures/no-error-render @(:defects w) {:expects #{"seon.render-test/throwing-view"}})
         (is (= 1 (count @(:defects w))) "the one error is on purpose")
         (let [placed (r/placed-hiccup w ["#parts"])
               [[_ broken-part] [_ [_ _ fine]]] (seq placed)
               ;; the part: its line, then the error's render called on the error as data (its last argument)
               broken (last (last broken-part))]
           (is (= :seon.render/component-defect (:seon.error/type broken)) (str "the model reads the error as data: " (pr-str placed)))
           (is (str/includes? (:seon.error/message broken) "seon.ref/scan invalid-output") "what threw")
           (is (= [{:in [:seon.ref/blocks 3]}] (get-in broken [:seon.render/threw :problems])) "and the thrown error's own data")
           (is (= "drawn" (last fine)) "the other part draws"))
         (finally (r/dispose-walk! w)))))

(defn- plain-thrower
  "A plain call (its contract asks no input: the walk keeps it as written, the writer calls it) that throws."
  {:malli/schema [:=> [:cat :any] :any]}
  [_] (throw (ex-info "the plain call broke" {:at :plain})))

(defn- plain-call-layout
  "A layout placing a plain call that throws beside a part that draws. It reads no db, so the walk builds none."
  {:malli/schema [:=> [:cat :seon.render/opts] :any]}
  [_] [:div [:div {:id "parts"} [:p "drawn"] [#'plain-thrower {}]]])

;; See docs/failure-points.md
(deftest ^{:seon.test/estimate {:ticks 6000000}} a-plain-call-that-throws-is-drawn-and-recorded-as-a-component-defect
  ;; estimate: a walk of two components (~0.4M) and one error drawn by its render (~5M, the printer over the error as data)
  ;; one boundary for a render that throws: a plain call the writer makes is drawn by the error's render, never a bare span, and the
  ;; walk records it as it records a component's (take-defects!), so health and the model see it
  (let [w (r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} (constantly nil) [#'plain-call-layout {}] (seon.db/index))]
    (try (r/flush! w nil)
         (let [html (apply str (map :html (r/placed-units w)))]
           (is (str/includes? html "drawn") "the part beside it draws")
           (is (str/includes? html "the plain call broke") "what threw is on the page")
           (is (not (str/includes? html "<span class=\"err\">")) "by the error's render, not a bare span"))
         (is (= [:seon.render/component-defect] (map :seon.error/type (r/take-defects! w))) "recorded as a component's defect")
         (finally (r/dispose-walk! w)))))

(defn- nested-search-layout
  "A layout printing a value that holds a search (its schema's render, seon.render.ui/searched, asks the db) inside it, as an eval's
  value holds one."
  {:malli/schema [:=> [:cat :seon.render/opts] :any]}
  [{ix :seon.render/index}]
  [:div [:div {:id "parts"} [#'r/hiccup {:seon.render/index ix} {:found {:seon.search/id "s-nested" :seon.search/query "render walk" :seon.search/k 2}}]]])

;; See docs/render.md#one-rendering-system-drawn-from-the-data
(deftest ^{:seon.test/estimate {:ticks 15000000}} a-nested-value-whose-render-asks-the-db-draws-with-it
  ;; estimate: a two-component walk (~0.5M), one search of the test db's few texts (~5M), its html (~1M); the db built once (~5M)
  ;; a value printed inside another draws by its schema's render as a component of the walk, given what that render's contract asks
  ;; (:seon.db/db): seon.render.ui/searched ran as a plain call without it and threw (web's 23:30 land)
  (let [db (ts/test-db)
        w (r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} (constantly db) [#'nested-search-layout {}] (seon.db/index))]
    (try (r/flush! w nil)
         (let [html (apply str (map :html (r/placed-units w)))]
           (is (str/includes? html "searched") (str "the search drawn by its render: " html)))
         (is (= [] (r/take-defects! w)) "no render threw")
         (finally (r/dispose-walk! w)))))

(deftest a-component-that-throws-is-a-problem-health-reads
  ;; docs/health.md: a defect a person alone would see is a fact too: the walk answers it once to whoever records problems. An interrupt is nobody's defect: it passes
  (let [w (r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} (constantly (ts/test-db)) [#'two-part-layout {}])]
    (try (r/flush! w nil)
         (is (= [:seon.render/component-defect] (map :seon.error/type (r/take-defects! w))))
         (is (= [] (r/take-defects! w)) "taken once")
         (finally (r/dispose-walk! w))))
  (let [w (r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} (constantly (ts/test-db)) [#'interrupted-layout {}])]
    (try (is (instance? ThreadDeath (try (r/flush! w nil) nil (catch Throwable e e))) "an interrupt passes the render's edge, as root's interrupt is raised (seon.intr)")
         (is (= [] (r/take-defects! w)))
         (finally (r/dispose-walk! w)))))

(deftest a-wire-mark-never-reaches-the-browser
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: :wire/* is for the reading alone, so nothing in it can name a Datastar attribute (`ref` made a signal per element, posted back by @post); what Datastar reads stays :data-*
  (let [e {:seon.eval/id "e1" :seon.eval/code "(f)" :seon.eval/response {:value 1 :handle 'result/e1} :seon.eval/call-id "c"}
        html (h/html (seon.render.ui/evaluation {} e))]
    (is (not (str/includes? html "wire")) html)
    (is (not (str/includes? html "data-role")) "no marker under its old name")
    (is (str/includes? html "id=\"eval-e1\"") "an element's id stays: a patch lands by it")
    (is (= "<li data-x=\"1\"></li>" (h/html [:li {:wire/role "user" :data-x "1"}])))))

;; docs/render.md#a-change-is-edits-by-name: what the keyed read and its edits are, on the shape status draws
(def ^:private proofs-a
  '[seon.render.ui/status {:ctx [:seon.agent/task :proved]} [:h2 "Your proofs"]
    [:ul {:key :proofs}
     [:li {:key old-test} [:code "old-test"] ": fails"] [:li {:key b-test} [:code "b-test"] ": passes"] [:li {:key c-test} [:code "c-test"] ": passes"]
     [:li {:key d-test} [:code "d-test"] ": passes"] [:li {:key e-test} [:code "e-test"] ": passes"] [:li {:key f-test} [:code "f-test"] ": passes"]]
    [:p "Everything below this line is some text the agent has to read, long enough for a change above it to be worth saying by itself."]])

(defn- no-position?
"Does no edit's path hold a position?"
  {:malli/schema [:=> [:cat [:sequential :any]] :boolean]}
  [es] (not-any? (fn [[p]] (some number? p)) es))

(deftest a-change-speaks-in-names
  (let [edits (fn [was now] (r/page-edits (seon.db/index) (r/forms-moved {"status" was} {"status" now})))
        drop-old (assoc-in proofs-a [3] (into [:ul {:key :proofs}] (subvec (nth proofs-a 3) 3)))]
    (testing "a named child gone is its name and :-"
      (is (= '[[["status" :proofs old-test] :-]] (edits proofs-a drop-old))))
    (testing "a named child changed is replaced at its name, its value the hiccup"
      (is (= '[[["status" :proofs b-test] :r [:li {:key b-test} [:code "b-test"] ": fails"]]]
             (edits proofs-a (assoc-in proofs-a [3 3] '[:li {:key b-test} [:code "b-test"] ": fails"])))))
    (testing "a named child added is its name and :+"
      (is (= '[[["status" :proofs g-test] :+ [:li {:key g-test} "new"]]]
             (edits proofs-a (update proofs-a 3 conj '[:li {:key g-test} "new"])))))
    (testing "what has no :key goes by its tag when no sibling shares it"
      (is (= '[[["status" :h2] :r [:h2 "Your new proofs"]]] (edits proofs-a (assoc-in proofs-a [2] [:h2 "Your new proofs"])))))
    (testing "and by its first words when one does"
      (let [form (fn [x] ['f {} [:p "One thing the agent reads, long enough for it to count."] [:p "Another thing the agent reads, long enough for it to count."] [:pre [:code x]]])]
        (is (= '[["status" :pre]] (map first (edits (form "a") (form "b")))))
        (is (= '[[["status" "Another thing the agent reads, long"] :r [:p "Another thing the agent reads, long enough for it to count, now."]]]
               (edits (form "a") (assoc (form "a") 3 [:p "Another thing the agent reads, long enough for it to count, now."]))))))
    (testing "an element holding text is text, one value, and what is identical among siblings stays in :own"
      (is (no-position? (edits proofs-a (assoc-in proofs-a [3 3 2 1] "b-renamed"))))
      (is (= '[["status" :proofs b-test]] (map first (edits proofs-a (assoc-in proofs-a [3 3 2 1] "b-renamed")))))
      (is (= '[:ul {:x 1} [:li "1"] [:li "1"]] (r/keyed '[:ul {:x 1} [:li "1"] [:li "1"]])) "nothing to tell them apart")
      (is (= '[:ul [:li {:key :own} "1"]] (:own (r/keyed '[:ul [:li {:key :own} "1"] [:li {:key a} "2"]]))) "a name that takes :own's place stays in :own"))
    (testing "the whole component when that is shorter"
      (is (= [["status"]] (map first (edits '[f {} [:ul {:key :l} [:li {:key a} "1"]]] '[f {} [:h3 "other"]])))))
    (testing "named children that changed places are no edit a name can say: that element whole, at its name"
      (let [swapped-ul '[:ul {:key :proofs} [:li {:key b-test} [:code "b-test"] ": passes"] [:li {:key old-test} [:code "old-test"] ": fails"] [:li {:key c-test} [:code "c-test"] ": passes"]
                         [:li {:key d-test} [:code "d-test"] ": passes"] [:li {:key e-test} [:code "e-test"] ": passes"] [:li {:key f-test} [:code "f-test"] ": passes"]]]
        (is (= [[["status" :proofs] :r swapped-ul]] (edits proofs-a (assoc proofs-a 3 swapped-ul))))))
    (testing "a part's first draw and its going are the same words"
      (is (= '[[["n"] :+ [f {}]] [["g"] :-]] (r/page-edits (seon.db/index) (r/forms-moved {"g" '[f {}]} {"n" '[f {}]})))))
    (testing "an element's id names it where it has no :key: a component nested in a part"
      (let [long-text (apply str (repeat 40 "word "))
            mk (fn [t] ['f {} ['g {:id "cited-a"} [:p t] [:p {:key :x} long-text]] [:p {:key :y} long-text]])]
        (is (= '[[["p" "cited-a" :p] :r [:p "b"]]]
               (r/page-edits (seon.db/index) (r/forms-moved {"p" (mk "a")} {"p" (mk "b")}))))))
    (testing "no clock decides: with no timeout editscript holds no deadline"
      (is (not (contains? (editscript.util.common/with-vec-deadline {:algo :quick :vec-timeout nil}) :editscript.util.common/vec-deadline))))))

(defn cited-world
  "A task demo/c whose plan cites a wiki section, a defined var (with a test and a caller) and its own proof,
  and its agent made as root makes one (seon.task/agent-cites-tx: every agent's cites, a page whole and a
  section), each written by its one writer: [db agent-eid]."
  []
  (let [k "b1/a1"
        db (d/db-with (ts/test-db)
                      [{:ns/name 'demo :ns/file "src/demo.clj"}
                       {:seon.doc/address "docs/x.md#the-x" :seon.doc/heading "The x" :seon.doc/lead "What x does. And more." :seon.doc/text "What x does. And more."}
                       {:db/id "top" :seon.doc/address "docs/testing.md#testing" :seon.doc/heading "Testing" :seon.doc/lead "How a test is written."}
                       {:seon.doc/address "docs/testing.md#testing-in-the-world" :seon.doc/heading "Testing in the world" :seon.doc/parent "top"}
                       {:seon.doc/address "docs/references.md#an-entity-cites" :seon.doc/heading "An entity cites" :seon.doc/lead "A citation is an entity."}
                       {:seon.doc/address "docs/agent.md#working-a-task" :seon.doc/heading "Working a task"}
                       {:seon.doc/address "docs/agent.md#merging" :seon.doc/heading "Merging"}
                       {:seon.doc/address "docs/data-modeling.md#data-modeling" :seon.doc/heading "Data modeling"}
                       {:db/id "g" :form/id "demo/g-1" :form/src "(defn g \"Gets it.\" [x] x)"} {:db/id "gv" :var/name 'demo/g :var/doc "Gets it." :var/form "g"}
                       {:db/id "t" :form/id "demo-test/g-test-1" :form/src "(deftest g-test (is (g 1)))" :form/refs #{'demo/g} :form/defines ["tv"]}
                       {:db/id "tv" :var/name 'demo-test/g-test :var/meta #{:test} :var/form "t"}
                       {:db/id "h" :form/id "demo/h-1" :form/src "(defn h [] (g 2))" :form/refs #{'demo/g} :form/defines ["hv"]}
                       {:db/id "hv" :var/name 'demo/h :var/meta #{:arglists} :var/form "h"}])
        db (d/db-with db (seon.task/create-tx db [{:seon.task/ns 'demo :seon.task/name "c" :seon.task/proofs #{'demo-test/g-test}
                                                   :seon.task/plan "Make `demo/g` pass `demo-test/g-test`; see [the x](docs/x.md#the-x)."}]))
        db (d/db-with db (into [{:db/id k :seon.agent/key k :seon.agent/task [:seon.task/id "demo/c"]}] (seon.task/agent-cites-tx db k k)))]
    [db (seon.db/eid db :seon.agent/key k)]))

;; the instructions cite how a task is worked (docs/agent.md#working-a-task) at full depth: the agent is sent the
;; section's text, as the wiki holds it, not its address alone (a cite resolved at summary would show its heading)
(defn- cite-text
  "The cites on agent aid's page drawn by a walk over db, {target text}: each cite the walk places (seon.render.ui/tiles), by its own
  render (seon.render.ui/cite), as the model reads it; a cite that draws nothing (its owner's proof test) maps to nil."
  [db tree aid]
  (let [w (draw tree seon.ctx/changes db #'seon.render.ui/agent)]
    ;; a cite's component is keyed by the cite: its owner, a space, what it names (seon.render.ui/cite's identity)
    (try (into {} (for [[k _] @(:cache w) :when (= 'seon.render.ui/cite (first k))]
                    [(last (str/split (str (second k)) #" " 2)) (some-> (r/part-form w k) r/part-text)]))
         (finally (r/dispose-walk! w)))))

(deftest a-work-task-is-sent-how-a-task-is-worked
  (let [k "b1/a1"
        db (ts/test-db)
        ;; the section as the one parse reads it (seon.doc/page-sections), stored alone: its page's links reach pages this db does not hold
        sec (some #(when (= "docs/agent.md#working-a-task" (:seon.doc/address %)) %)
                  (seon.doc/page-sections "docs/agent.md" (slurp (str (seon.config/checkout) "/docs/agent.md"))))
        db (d/db-with db [(select-keys sec [:seon.doc/address :seon.doc/heading :seon.doc/level :seon.doc/position :seon.doc/lead :seon.doc/text])])
        db (d/db-with db (seon.task/create-tx db (seon.template/request {:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Make w true. How: ```docs/agent.md#working-a-task```."})))
        db (d/db-with db [{:db/id k :seon.agent/key k :seon.agent/task [:seon.task/id "demo/w"]}])
        aid (seon.db/eid db :seon.agent/key k)
        tree (:tree (seon.ctx/build (seon.db/index) db aid :b))
        text (get (cite-text db tree aid) "docs/agent.md#working-a-task")]
    (is (some? text) "the section is a part of the agent's context")
    (is (str/includes? text "their failures are the reproduction") (str "its text, whole: " (subs text 0 (min 300 (count text)))))
    (is (str/includes? text "before you prove again") "to its last step")))

;; docs/render.md#a-citation-says-why-it-is-there: each kind of cite read cold
(deftest a-cites-part-shows-a-callable-form
  ;; docs/references.md#an-entity-cites: what a cite shows of how a function is called is a form an agent can evaluate. A call inside a
  ;; macro's syntax-quote holds an unquote and is a template, never a call; a search hit whose line is only its own definition's opening
  ;; says nothing its name does not, and shows its arglist
  (let [macro "(defmacro doc [x] `(do (println (my.agent/doc-of ~x)) nil))"
        both (str macro "\n(defn g [] (my.agent/doc-of \"docs/x.md\"))")
        v {:var/doc "What it names, as data." :var/form {:form/src "(defn doc-of [x] x)"}
           :callers {1 {:form/src macro :form/defines {2 {:var/name 'my.agent/doc :var/meta #{:macro}}}}}}
        part (r/part-text ['seon.render.ui/cite {} (#'ui/var-hiccup 'my.agent/doc-of v false)])
        hit-line #'ui/hit-line]
    (is (nil? (ui/call-in macro 'my.agent/doc-of)) "a template is no call")
    (is (= "(my.agent/doc-of \"docs/x.md\")" (ui/call-in both 'my.agent/doc-of)) "a real call after it in the same source is the one shown")
    (is (not (str/includes? part "~")) (str "a cited function's part holds no template: " part))
    (is (not (str/includes? part "A real call")) "and says nothing of a call when it has none to show")
    (is (= "([v])" (hit-line "(defn- doc-view" 'my.agent/doc-view (constantly "(defn- doc-view [v] v)"))) "a hit whose line is its own definition's opening shows its arglist")
    (is (= "a line that says why it matters" (hit-line "a line that says why it matters" 'my.agent/doc-view #(throw (ex-info "read" {})))) "any other line is shown as it is, its source never read")))

(deftest a-cited-thing-is-drawn-by-what-it-is
  (let [[db aid] (cited-world)
        tree (:tree (seon.ctx/build (seon.db/index) db aid :b))
        all (cite-text db tree aid)
        drawn (into {} (filter val) all)]
    (is (every? (set (keys drawn)) ["demo/g" "docs/x.md#the-x"]) "a tile per cite of the task, each target once")
    (is (and (contains? all "demo-test/g-test") (nil? (all "demo-test/g-test"))) "its proof's cite draws nothing: the task's proofs show it (Context's rule)")
    (is (contains? (set (keys drawn)) "docs/agent.md#working-a-task") "and per cite of the agent's own (its config's), by the same render")
    (is (not (contains? (set (keys drawn)) "")) "every cite names what it cites")
    (is (every? #(re-find #"\[:h3 \"" %) (vals drawn)) "every cite is headed")
    (is (str/includes? (drawn "docs/x.md#the-x") "What x does. And more.") "a section at doc depth: its lead")
    (is (str/includes? (drawn "docs/x.md#the-x") "(doc \"docs/x.md#the-x\")") "and how to read the rest")
    (let [sec (r/part-text ['seon.render.ui/cite {} (ui/cite-hiccup (ts/test-db) {:seon.cite/depth :doc
                                                                          :seon.cite/target {:seon.doc/address "docs/testing.md#testing" :seon.doc/heading "Testing" :seon.doc/lead "How a test is written."
                                                                                    :seon.doc/_parent {1 {:seon.doc/address "docs/testing.md#testing-in-the-world" :seon.doc/heading "Testing in the world"}}}})])]
      (is (str/includes? sec "Testing in the world (docs/testing.md#testing-in-the-world)") "a page cited whole: its top section, the headings below it joined"))
    (is (str/includes? (drawn "demo/g") "(defn g \"Gets it.\" [x] x)") "a function at doc depth: its whole source, in a fence")
    (is (str/includes? (drawn "demo/g") "1 tests reach it: (callers 'demo/g) lists them.") "the tests that will judge the change")
    (is (not (str/includes? (drawn "demo/g") "Called by")) "and nothing of who calls it")
    (is (not (str/includes? (drawn "demo/g") "A real call")) "a function it will change is read whole: no call to copy")
    (doseq [[k t] drawn] (is (not (re-find #":depth|:symbol|:change|:read " t)) (str k " carries none of how it was fetched")))))

(deftest a-chat-is-the-agent-talking-and-its-steps
  ;; docs/render.md#the-page: a turn is what the agent said, then its evals as steps; a step opens once to its code and its value, drawn by the render its record names
  (let [[db aid] (cited-world)
        k (:seon.agent/key (d/pull db [:seon.agent/key] aid))
        db (d/db-with db [{:db/id aid :seon.agent/turn 1}
                          {:seon.model-call/id (seon.lib/call-id k 1) :seon.model-call/agent aid :seon.model-call/n 1 :seon.model-call/origin :model :seon.model-call/reply "Found **it**." :seon.model-call/forms 1}
                          {:seon.eval/id "e2" :seon.eval/agent aid :seon.eval/by {:seon.system/id :root} :seon.eval/call (seon.task/call-ref k 1) :seon.eval/code "(my.render/render-ctx)" :seon.eval/response {:handle 'result/e2 :value 1}}
                          {:seon.eval/id "e3" :seon.eval/agent aid :seon.eval/by {:seon.agent/key "a1"} :seon.eval/call (seon.task/call-ref k 1) :seon.eval/call-id "c1" :seon.eval/intent "Run the proofs." :seon.eval/code "(prove-it)"
                           :seon.eval/schema :seon.test/proved :seon.eval/response {:handle 'result/e3 :value {:proofs {'a/b :green}}}}
                          {:seon.eval/id "e4" :seon.eval/agent aid :seon.eval/by {:seon.agent/key "outside"} :seon.eval/call (seon.task/call-ref k 1) :seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/e4 :value 3}}])
        opts {:seon.ctx/ctx (:tree (seon.ctx/build (seon.db/index) db aid :b)) :seon.db/db db :seon.render/index (seon.db/index)}
        eid #(seon.db/eid db :seon.eval/id %)
        call (seon.db/eid db :seon.model-call/id (seon.lib/call-id k 1))
        turn (ui/conversation-turn (assoc opts :key 1))
        reply (some #(when (= :section (first %)) %) (drop 2 turn))
        fold (last reply)
        ids (map #(get % :seon.eval/id) (last (last fold)))]
    (is (not-any? #(and (map? %) (get % :seon.eval/id)) (drop 2 turn)) "the chat is messages: no step stands beside them")
    (is (and (= :details (first fold)) (not (:open (second fold))) (= "3 steps" (second (nth fold 2)))) "the turn's steps fold, closed, under one line saying how many (then what each was for)")
    (is (= "e3" (first ids)) "the agent's own step first")
    (is (= #{"e2" "e3" "e4"} (set ids)) "then those of other hands, Seon's among them")
    (let [t (h/html (ui/model-call-view {} (d/pull db '[*] call)))]
      (is (str/includes? t "Found <strong>it</strong>") "what it said, its Markdown drawn")
      (is (not (str/includes? t "of context")) "its tokens are for whoever opens a step"))
    (let [e (h/html (ui/evaluation opts (get-in (:seon.ctx/ctx opts) [:seon.eval/_agent (eid "e3")])))]
      (is (str/includes? e "Run the proofs.") "a step's line is what it set out to do")
      (is (= 1 (count (re-seq #"\(prove-it\)" e))) "its code once, under the line")
      (is (not (re-find #"<details[^>]* open" e)) "closed until opened"))
    (is (str/includes? (h/html (ui/eval-output opts (get-in (:seon.ctx/ctx opts) [:seon.eval/_agent (eid "e3")]))) "prove") "opened, its value by the render its record names")))

(deftest an-opened-step-shows-its-result-whole
  ;; docs/render.md#the-page: expanding expands: a step closed holds a start, opened all of it, with nothing further to open
  (let [v (mapv (fn [i] {:n i :name (str "row-" i)}) (range 800))
        e {:seon.eval/id "e1" :seon.eval/code "(rows)" :seon.eval/response {:handle 'result/e1 :value v}}
        draw (fn [e] (h/html (ui/eval-output {:seon.render/index (seon.db/index) :seon.db/db (ts/test-db)} e)))
        closed (draw e)
        opened (draw (assoc e :seon.eval/opened true))]
    (is (not (str/includes? closed "row-799")) "closed: its start")
    (is (str/includes? closed "drop ") "and it says what it left")
    (is (str/includes? opened "row-799") "opened: all of it")
    (is (not (str/includes? opened "drop ")) "no cut is left to open")))

(deftest the-three-agree-on-the-agents-own-end
  (let [[db aid] (cited-world)
        ;; the agent has taken a turn under a cap of 7, and holds an end from a wake before this one (ended at turn 0)
        db (d/db-with db [{:db/id aid :seon.agent/max-turns 7 :seon.agent/turn 1 :seon.agent/outcome :green :seon.agent/ended-turn 0}
                          {:seon.model-call/id (seon.lib/call-id "b1/a1" 1) :seon.model-call/agent aid :seon.model-call/n 1 :seon.model-call/origin :model}])
        opts (fn [db] {:seon.ctx/ctx (:tree (seon.ctx/build (seon.db/index) db aid :b)) :seon.db/db db :seon.render/index (seon.db/index)})]
    (is (not (str/includes? (pr-str (ui/chat-end (opts db))) "finished")) "the chat's end: an earlier wake's end is no end of this one")
    (let [ended (d/db-with db [{:db/id aid :seon.agent/ended-turn 1}])]
      (is (str/includes? (pr-str (ui/chat-end (opts ended))) "It finished its work in 1 turn") "the end it wrote at its turn now is"))))

;; docs/render.md#how-the-work-stands: the status's proofs are the task's :proved as the record pulls them (each
;; var's verdict, failures and form), never read again from the db; the done rule's verdict scan and the diff read it
(deftest the-status-proofs-are-what-the-record-pulled
  (let [[db aid] (cited-world)
        red (d/db-with db [{:var/name 'demo-test/g-test :seon.test/status :red
                            :seon.test/failures [{:type :fail :expected "(g 1)" :actual "nil" :got "nil"}]}])
        ;; the record pulled where the proof is red, the status read over a db that holds no verdict of it
        st (seon.ctx.status/status db (:tree (seon.ctx/build (seon.db/index) red aid :b)) {})
        [p :as ps] (:proofs st)]
    (is (= 1 (count ps)) (pr-str ps))
    (is (= ['demo-test/g-test :fail] [(:test p) (:status p)]) "the verdict the record holds")
    (is (= "nil" (get-in p [:failures 0 :got :seon.print/code])) "its failure, as the record holds it")
    (is (str/includes? (pr-str (:source p)) "deftest g-test") "its source, the pulled form's")))

;; docs/render.md#a-component-answers-hiccup: the task part is the pulled task: its heading, its plan accumulated over its joined needs and its
;; kind's instructions, from what @ctx holds; its render reads no db. What it places that reads one (a cite, by its contract) is a
;; component of its own, and the walk hands the db to those alone
(deftest the-task-part-reads-only-what-ctx-holds
  (let [[db _] (cited-world)
        db (d/db-with db (seon.task/create-tx db [{:seon.task/ns 'demo :seon.task/name "n" :seon.task/plan "First the need." :seon.task/proofs #{'demo-test/n-test}}]))
        db (d/db-with db (seon.task/create-tx db [{:seon.task/ns 'demo :seon.task/name "m" :seon.task/needs #{"demo/n"} :seon.task/proofs #{'demo-test/g-test}
                                                   :seon.task/plan "Then make `demo/g` pass."}]))
        db (d/db-with db [{:seon.agent/key "b1/a2" :seon.agent/task [:seon.task/id "demo/m"]}])
        aid (seon.db/eid db :seon.agent/key "b1/a2")
        tree (:tree (seon.ctx/build (seon.db/index) db aid :b))
        ;; the task drawn by its schema's render over the task's pull, as the agent's page walk places it (seon.render.ui/tiles)
        r0 (seon.ratom/atom (:seon.agent/task tree))
        reads (volatile! 0)
        w (r/walker {:ref r0 :changes seon.ctx/changes}
                    (fn [] (vswap! reads inc) db)
                    [#'ui/task-view {} (seon.ratom/ref-view r0 seon.ctx/changes)] (seon.db/index))
        [text html db-parts] (try (flush! w nil)
                                  [(r/part-text (r/part-form w @(:out w))) (h/html (r/resolved w @(:out w) true))
                                   (count (for [[_ c] @(:cache w) :when (and (var? (:f c)) (contains? (r/inputs (:f c)) :seon.db/db))] c))]
                                  (finally (r/dispose-walk! w)))]
    (is (not (contains? (r/inputs #'ui/task-view) :seon.db/db)) "the task's render takes no db")
    (is (pos? db-parts) "it places what reads one (its cites)")
    (is (= db-parts @reads) "and the walk read the db once for each of those, none for the task")
    (is (nil? (:data (get @(:cache w) @(:out w)))) "hiccup, not a map of keys")
    (is (re-find #"Task: demo/m" text) (str "the agent's task, named: " text))
    (is (< (str/index-of text "First the need.") (str/index-of text "Then make")) "its plan accumulated over its need, the need's first")
    (is (not (str/includes? text "to-prove")) "the proofs are the status's")
    (is (not (str/includes? text ":seon.task/")) "no map keys")
    (is (str/includes? html "First the need.") "the page's html")))

(deftest a-request-draws-its-search-and-a-new-query-redraws-it
  ;; the owner, 2026-11-01: context is data the agent's ctx holds, drawn by the walk's renders (hiccup for the page,
  ;; data for the model); a search is such data (its swap! on ctx: seon.ctx-test/a-search-is-a-member-a-swap-adds-and-changes).
  ;; measured 2026-11-01: a request's search ranked a changed item first in 41% of 49 tasks from Seon's history;
  ;; drawn once its query has a vector (owner, 2026-11-08)
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn ((my.test/code 'app.srch (str "(ns app.srch)\n"
                                                     "(defn untaken-entries\n  \"The messages addressed to this session that it has not taken.\"\n  [db] db)\n"
                                                     "(defn render-tile\n  \"A tile of the dashboard.\"\n  [x] x)\n")) @conn))
      (d/transact conn (seon.template/request-tx @conn [{:seon.task/ns 'demo :seon.task/name "find" :seon.task/kind :whole
                                                          :seon.task/plan "Messages addressed to the session go untaken."}]))
      (d/transact conn [{:seon.agent/key "b/find" :seon.agent/task [:seon.task/id "demo/find"]}])
      (let [a (:db/id (d/entity @conn [:seon.agent/key "b/find"]))
            draw #(seon.fixtures/drawn-parts @conn (seon.ctx/build (seon.db/index) @conn a (:seon.agent/branch (d/entity @conn a))))
            ;; the search is the request's task's: a part nested in the task's part, as the pull nests it (seon.render.ui/task-view)
            search-part (fn [parts] (some #(when (and (vector? %) (= 'seon.render.ui/searched (first %))) %) (tree-seq coll? seq (vals parts))))
            s (:db/id (d/entity @conn [:seon.search/id "demo/find/request"]))
            unembedded (search-part (draw))
            ;; its query's vector, as main's vectors proc writes it (seon.vector/tx-data)
            _ (d/transact conn (seon.vector/tx-data [s] ["query"] [(vec (repeat 768 0.036))] "m"))
            before (search-part (draw))]
        (is (nil? unembedded) "a query with no vector yet draws nothing: words alone are no search the page shows")
        (is (some? before) "the request's search is a part of the page once its query has a vector")
        (is (str/includes? (pr-str before) "app.srch/untaken-entries") (str "it finds what the request's words name: " (pr-str before)))
        (is (= :h3 (first (nth before 2))) "one compact hiccup, headed, the page's and the model's alike")
        (is (not (str/includes? (pr-str before) "has not taken")) "a hit is its name: no first sentence of its doc")
        (is (not (str/includes? (pr-str before) "more:")) "no line of the hits left out, whose count would move each turn")
        (do
          (d/transact conn [{:db/id s :seon.search/query "a tile of the dashboard"}])
          (is (str/includes? (pr-str (search-part (draw))) "app.srch/render-tile") "a new query: the part draws its hits"))))))

(deftest a-definition-matching-the-request-moves-no-search-hit
  ;; docs/vector.md#search: the demo.math/half request (2026-11-11) was sent
  ;; its 10-hit search whole three times, each time its agent's own new definition re-ranked it (seon.render/settled replaces
  ;; a reordered element whole). A page's search ranks main as the agent's branch last merged it (:seon.agent/base-commit), so
  ;; what the agent writes on its branch moves no hit
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn ((my.test/code 'app.srch (str "(ns app.srch)\n"
                                                     "(defn untaken-entries\n  \"The messages addressed to this session that it has not taken.\"\n  [db] db)\n"
                                                     "(defn render-tile\n  \"A tile of the dashboard.\"\n  [x] x)\n")) @conn))
      (d/transact conn (seon.template/request-tx @conn [{:seon.task/ns 'demo :seon.task/name "find" :seon.task/kind :whole
                                                          :seon.task/plan "Messages addressed to the session go untaken."}]))
      (let [s (:db/id (d/entity @conn [:seon.search/id "demo/find/request"]))
            _ (d/transact conn (seon.vector/tx-data [s] ["query"] [(vec (repeat 768 0.036))] "m"))
            base (d/commit-id @conn)
            _ (d/transact conn [{:seon.agent/key "b/find" :seon.agent/task [:seon.task/id "demo/find"] :seon.agent/base-commit base}])
            a (:db/id (d/entity @conn [:seon.agent/key "b/find"]))
            draw #(seon.fixtures/drawn-parts @conn (seon.ctx/build (seon.db/index) @conn a (:seon.agent/branch (d/entity @conn a))))
            search-part (fn [parts] (some #(when (and (vector? %) (= 'seon.render.ui/searched (first %))) %) (tree-seq coll? seq (vals parts))))
            before (search-part (draw))
            ;; the agent defines what its request's words name, on its branch, after its base
            _ (d/transact conn ((my.test/code 'app.mine (str "(ns app.mine)\n"
                                                             "(defn untaken-messages\n  \"The messages addressed to the session that go untaken.\"\n  [db] db)\n")) @conn))
            after (search-part (draw))]
        (is (str/includes? (pr-str before) "app.srch/untaken-entries") (str "the search finds what main held: " (pr-str before)))
        (is (= before after) (str "the agent's own definition moves no hit: " (pr-str after)))))))

(defn some-lists-part "A component (it reads ctx) with an element of its own that has an id, inside its root."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}] [:section [:p {:id "inner"} (:v c)]])
(defn some-lists-other "A component in a list the walk does not draw."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}] [:p (:v c)])
(defn some-lists-layout "A layout of two lists."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_] [:div [:div {:id "parts"} [#'some-lists-part {}]] [:div {:id "others"} [#'some-lists-other {:wire/for "page"}]]])

(deftest a-walk-only-the-model-reads-draws-the-page-s-pieces-when-a-person-comes
  ;; docs/render.md#a-layout-places-its-lists: a call or an element :wire/for "page" is the person's: a walk :model-only draws none of it, a
  ;; change draws what the model reads, and the rest is drawn when a person comes
  (let [a (seon.ratom/atom {:v 1})
        w (assoc (r/walker {:ref a :changes seon.ctx/changes} (constantly (ts/test-db)) [#'some-lists-layout {}]) :model-only true)
        ran (fn [w] (set (keys (r/ledger w))))]
    (try (flush! w nil)
         (is (= ["#parts"] (distinct (map first (#'r/placed w)))) "the person's list is placed nowhere")
         (reset! a {:v 2})
         (flush! w nil)
         (let [[content] (vals (r/placed-hiccup w ["#parts"]))]
           (is (some #(and (vector? %) (= :p (first %)) (= 2 (last %))) (tree-seq vector? seq content)) (str "the part moved, drawn whole: " (pr-str content))))
         (is (not ((ran w) 'seon.render-test/some-lists-other)) "the other never ran")
         (let [w2 (r/draw-all w)]
           (flush! w2 nil)
           (is ((ran w2) 'seon.render-test/some-lists-other) "and runs when it is asked for")
           (is (= ["#parts" "#others"] (distinct (map first (#'r/placed w2))))))
         (finally (r/dispose-walk! w)))))

(defn rr-part "A component whose render calls a helper, by the facts of its form."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_] [:p "drawn"])
(defn rr-other "A component no moved var here reaches."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_] [:p "other"])
(defn rr-layout "A layout of the two."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_] [:div [:div {:id "parts"} [#'rr-part {}] [#'rr-other {}]]])

(deftest a-render-that-changes-draws-again
  ;; docs/render.md#reactive-renders; the owner, 2026-11-04: "when data changes OR THE FUNCTION THAT RENDERS IT changes then you
  ;; re-render". A helper a render calls is an input of what it draws; a var nothing here reaches is not
  (let [facts (fn [form-id src refs defines]
                {:db/id form-id :form/id form-id :form/src src :form/refs refs :form/defines defines})
        db0 (d/db-with (ts/test-db)
                       [{:db/id "v1" :var/name 'seon.render-test/rr-part} {:db/id "v2" :var/name 'demo/rr-helper} {:db/id "v3" :var/name 'demo/rr-deep}
                        {:db/id "v4" :var/name 'demo/unrelated}
                        (facts "f1" "(defn rr-part [_] (demo/rr-helper))" '#{demo/rr-helper} ["v1"])
                        (facts "f2" "(defn rr-helper [] (demo/rr-deep))" '#{demo/rr-deep} ["v2"])
                        (facts "f3" "(defn rr-deep [] 1)" #{} ["v3"])])
        cell (volatile! db0)
        w (r/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} #(deref cell) [#'rr-layout {}])
        runs #(let [l (get (r/ledger w) %)] (+ (:miss l 0) (:same l 0)))
        move! (fn [n src] (let [rep (d/with @cell [{:db/id "new" :form/id src :form/src src} [:db/add [:var/name n] :var/form "new"]])]
                            (vreset! cell (:db-after rep))
                            (flush! w (:tx-data rep))))]
    (try
      (flush! w nil)
      (r/placed-hiccup w ["#parts"])
      (let [p (runs 'seon.render-test/rr-part) d (runs 'seon.render-test/rr-other)]
        (move! 'demo/unrelated "(defn unrelated [] 2)")
        (is (= [p d] [(runs 'seon.render-test/rr-part) (runs 'seon.render-test/rr-other)]) "a var nothing here reaches: nothing runs")
        (move! 'demo/rr-deep "(defn rr-deep [] 2)")
        (is (= (inc p) (runs 'seon.render-test/rr-part)) "a helper's helper moved: the render that reaches it runs")
        (is (= d (runs 'seon.render-test/rr-other)) "the other does not"))
      (finally (r/dispose-walk! w)))))


(defn mv-child "A child, its text read from ctx."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} id] [:li (get-in c [:items id :text])])
(defn mv-wrap "A child's parent, itself a child."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [_ id] [:ol [#'mv-child {} id]])
(defn mv-parent "The members of ctx's side: an id, a child; [id], its wrapped child."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :keyword] :any]}
  [{c :seon.ctx/ctx} side]
  (into [:ul] (for [x (seon.ratom/realize (get c side))] (if (vector? x) [#'mv-wrap {} (first x)] [#'mv-child {} x]))))
(defn mv-layout "Two parents."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_] [:div [:div {:id "parts"} [#'mv-parent {} :a] [#'mv-parent {} :b]]])

(deftest a-child-moved-between-parents-stays
  ;; docs/render.md#components: what allowed it was a parent's rerun unmounting at once what it no longer drew, whoever else draws it. A
  ;; component goes when, at the end of the flush, no parent holds it
  (let [start (fn [a b] (let [at (seon.ratom/atom {:db/id 1 :a a :b b :items {1 {:db/id 11 :text "one"} 2 {:db/id 12 :text "two"}}})
                              w (r/walker {:ref at :changes seon.ctx/changes} (constantly nil) [#'mv-layout {}] (seon.db/index))]
                          (flush! w nil)
                          [w at]))
        shown #(pr-str (r/resolved % @(:out %)))
        child (fn [id] [`mv-child id])]
    (testing "to a parent drawn later"
      (let [[w a] (start [1] [2])]
        (try (swap! a assoc :a [] :b [2 1])
             (flush! w nil)
             (is (str/includes? (shown w) "one"))
             (is (contains? @(:cache w) (child 1)))
             (finally (r/dispose-walk! w)))))
    (testing "to a parent drawn earlier: the old parent's rerun comes after the new parent's"
      (let [[w a] (start [2] [1])]
        (try (swap! a assoc :a [2 1] :b [])
             (flush! w nil)
             (is (str/includes? (shown w) "one") "it stays on the page")
             (is (contains? @(:cache w) (child 1)) "and in the walk")
             (swap! a assoc-in [:items 1 :text] "uno")
             (is (= :miss (get (flush! w nil) (child 1))) "still reactive: its reaction was not disposed")
             (is (str/includes? (shown w) "uno"))
             (finally (r/dispose-walk! w)))))
    (testing "a child's parent goes and the child moves"
      (let [[w a] (start [2] [[1]])]
        (try (swap! a assoc :a [2 1] :b [])
             (flush! w nil)
             (is (str/includes? (shown w) "one"))
             (is (not (contains? @(:cache w) [`mv-wrap 1])) "the parent went")
             (is (contains? @(:cache w) (child 1)) "its child did not")
             (finally (r/dispose-walk! w)))))
    (testing "held by two parents, dropped by one"
      (let [[w a] (start [1] [1 2])]
        (try (swap! a assoc :a [])
             (flush! w nil)
             (is (str/includes? (shown w) "one"))
             (is (contains? @(:cache w) (child 1)))
             (finally (r/dispose-walk! w)))))
    (testing "held by none: it goes, with what it held, and does not run on its way out"
      (let [[w a] (start [1 [2]] [])
            running (seon.ratom/running)]
        (try (swap! a (fn [c] (-> c (assoc :a []) (assoc-in [:items 1 :text] "uno") (assoc-in [:items 2 :text] "dos"))))
             (let [ran (flush! w nil)]
               (is (not (contains? ran (child 1))) "a change reached it, and its parent let go of it first")
               (is (not (contains? ran (child 2))) "nor below one that went")
               (is (not (str/includes? (shown w) "uno"))))
             (is (not (contains? @(:cache w) (child 1))))
             (is (not (contains? @(:cache w) [`mv-wrap 2])))
             (is (not (contains? @(:cache w) (child 2))) "what it alone held")
             (is (empty? @(:queue w)) "nothing left queued")
             (is (<= (seon.ratom/running) running) "its reaction disposed")
             (finally (r/dispose-walk! w)))))))

;; ---- a part prints as its render (docs/render.md#a-part-prints-as-its-render) ----

(deftest a-fence-is-longer-than-any-backtick-run-in-its-source
  (let [src "(defn f\n  \"doc with \\\"escaped\\\" quotes\n```\nlone fence\n``````\n  \"\n  [x] x)"
        f (r/fence src)]
    (is (str/starts-with? f "```````clojure\n") "one more than the longest run (6)")
    (is (str/includes? f src) "the source verbatim, not escaped")
    (is (= 1 (count (filter #(= "```````" %) (str/split-lines f)))) "only its own closing line closes it")
    (is (str/starts-with? (r/fence "(+ 1 2)") "```clojure\n") "three when the source has none")))

(deftest a-part-prints-as-its-head-its-path-and-its-hiccup
  (let [form [(quote seon.render.ui/status) {:ctx [:seon.agent/task :proved]}
              [:h2 "Where your work stands"]
              [:p "You finish when every proof is green."]
              [:pre [:code "(deftest t\n  (is (= \"a\" x)))"]]
              [:p "Next: " [:code "(prove)"]]]
        t (r/part-text form)]
    (is (some? (re-find #"^\[seon\.render\.ui/status\s+\{:ctx \[:seon.agent/task :proved\]\}" t)) "the real function and the path")
    (is (str/includes? t "(deftest t\n  (is (= \"a\" x)))") "source verbatim: no \\n and no \\\" in it")
    (is (not (str/includes? t "\\n")) "no escaped line break")
    (is (str/includes? t "```clojure") "in a fence")
    (is (str/includes? t "[:h2 \"Where your work stands\"]"))))

(deftest a-parts-head-is-the-function-that-drew-it-and-its-path-is-real
  ;; the owner: "the head names the real function and :ctx is a real path": (get-in @ctx path) answers the data it read
  (let [[_ branch _] (paged-dbs)
        k "a3"
        c (:tree (seon.ctx/build (seon.db/index) branch (seon.db/eid branch :seon.agent/key k) :task-demo-f))
        w (a3-walk branch)
        parts (try (r/placed-hiccup w ["#p-task"]) (finally (r/dispose-walk! w)))]
    (is (seq parts))
    (doseq [[id [head attrs]] parts]
      (is (some? (ns-resolve (find-ns (symbol (namespace head))) (symbol (name head)))) (str id ": the head names a function"))
      (is (some? (get-in c (vec (butlast (:ctx attrs))))) (str id ": " (pr-str (:ctx attrs)) " is a path into the record")))))

(deftest the-status-is-one-hiccup-with-its-proofs-failures-and-next-form
  ;; owner, 2026-11-05 (render-audit.md, how the work stands): the status read cold says what it is, what finishes the task,
  ;; each proof, each failure in a line, what blocks merging, and the next form to run; no :tokens-sent
  (let [st {:reasons [{:reason :proof-red :symbol 'seon.program-test/a-vars-history} {:reason :proof-unknown :symbol 'seon.program-test/b}]
            :proofs [{:test 'seon.program-test/a-vars-history :status :fail :source {:seon.print/code "(deftest a-vars-history\n  (is (= 1 (history 'a))))"}
                      :failures [{:assert {:seon.print/code "(= 1 (history 'a))"} :got {:seon.print/code "Unknown class program"} :message "Uncaught exception, not in assertion."}]}
                     {:test 'seon.program-test/b :status :undefined}]
            :attention ["seon.program/history is public and its docstring cites no docs/ section"]
            :refused [{:reason :proof-unknown :test 'seon.program-test/b :do '(my.agent/prove)}]}
        h (ui/status-hiccup {} st)
        t (h/html h)]
    (is (= :h2 (first (second h))) "headed")
    (is (str/includes? t "What your task still needs") "headed to the agent by what remains")
    (is (str/includes? t "<li>[ ] <code>seon.program-test/a-vars-history</code>") "each proof a box with its state")
    (is (str/includes? t (:undefined (:seon.status/title (seon.contract/properties :seon.ctx.status/proof-state)))) "each proof's state, its schema's title")
    (is (str/includes? t "Unknown class program") "a failure in a line")
    (is (not (str/includes? t "(deftest a-vars-history")) "the failing proof's source is the agent's own: not repeated")
    (is (< (str/index-of t "don't block your merge") (str/index-of t "seon.program/history is public")) "a stale section, said as not blocking (the owner, 2026-11-14)")
    (is (not (str/includes? t "(do ")) "separate top-level forms, never a do")
    (is (not (str/includes? t "tokens")) "no tokens sent")
    (is (not (str/includes? t "conflict")) "no merge line with nothing to merge")
    (is (str/includes? (h/html (ui/status-hiccup {} (assoc st :merge {:conflicts ['a/b] :main-moved ['a/b]}))) "1 things conflict with main") "a merge line only when something conflicts")))


(deftest a-proofs-story-is-a-row-for-each-change
  ;; docs/render.md#the-page: a stretch of the same reading is one row, and a change of status, count or first failure is a new one
  (is (= [{:from 1 :to 2 :status nil :failures 0 :first nil}
          {:from 3 :to 4 :status :red :failures 2 :first {:message "a"}}
          {:from 5 :to 5 :status :red :failures 2 :first {:message "b"}}
          {:from 6 :to 6 :status :green :failures 0 :first nil}]
         (ui/story-rows [[1 nil 0 nil] [2 nil 0 nil] [3 :red 2 {:message "a"}] [4 :red 2 {:message "a"}] [5 :red 2 {:message "b"}] [6 :green 0 nil]])))
  (is (= [] (ui/story-rows []))))

(deftest a-closed-tasks-standing-gives-the-gates-reason
  (let [why "its deferred tests, run on a checkout at its merge, are not green: a/b fail"
        s (#'ui/standing {:seon.agent/outcome :green :seon.agent/ended-turn 7 :seon.agent/turn 7}
                         {:seon.task/outcome :closed :seon.task/failure {:reason why}})]
    (is (= :task/closed (:state s)) "what root decided wins over the agent's own end")
    (is (= why (:reason s)))))

(deftest a-value-draws-itself-in-the-chat
  ;; docs/render.md#a-value-draws-itself: no click; the model reads the value as data
  (let [[_ branch _] (paged-dbs)
        k "a3"
        ev (fn [id value] (seon.repl/record-tx {:seon.agent/key k} id (seon.task/sender "a3")
                                               {:seon.eval/call (seon.task/call-ref k 5) :seon.eval/code "(page)" :seon.eval/response {:handle (symbol "result" id) :value value}}))
        db (d/db-with branch (vec (concat [{:seon.agent/key k :seon.agent/turn 6}]
                                          ;; as the REPL records a value that draws itself: its schema :seon.render/drawn
                                          (map #(cond-> % (:seon.eval/id %) (assoc :seon.eval/schema :seon.render/drawn))
                                               (ev "g1" {:points [1 2] :seon.render/hiccup [:svg {:width 10} [:circle {:r 3}]]}))
                                          (ev "g2" {:plain-value 41}))))
        w (a3-walk db)]
    (try
      (let [html (apply str (map :html (filter #(= "#c-rows" (:container %)) (seon.fixtures/page-units w))))
            tiles (apply str (map :html (filter #(= "#p-task" (:container %)) (seon.fixtures/page-units w))))
            ;; what the model reads of the eval: its model render's messages (seon.ctx.wire/eval-msgs), never the chat's elements
            read (pr-str (seon.fixtures/eval-messages {:db db} (d/pull db '[*] [:seon.eval/id "g2"])))]
        (is (str/includes? tiles "<svg") "a value drawing itself is a tile too (its record names :seon.render/drawn)")
        (is (not (str/includes? tiles "plain-value")) "a value with no render is no tile")
        (is (str/includes? html "<svg") "and inline in its step, drawn when the step is opened")
        (is (not (str/includes? html "<svg height=\"0")) "drawn as it is")
        (is (str/includes? html "plain-value") "a value with no render: the value printer")
        (is (str/includes? read "plain-value") "the model reads the value as data")
        (is (not (str/includes? read "eval-glance")) "and no glance"))
      (finally (r/dispose-walk! w))))
  (testing "a render no process here holds: the data, never a throw"
    (let [v {:n 1 :seon.render/hiccup 'agent.nobody/chart}]
      (is (nil? (r/render-of (seon.db/index) v nil)))
      (is (str/includes? (pr-str (r/hiccup {:seon.render/index (seon.db/index)} v)) ":n")))))

(deftest a-value-that-is-hiccup-is-drawn-on-the-page
  ;; docs/render.md#a-value-draws-itself: an agent's call of a render at its REPL (seon.render.ui/pull-front, whose contract answers
  ;; :seon.render/hiccup-value) answers hiccup; its eval names that schema from the contract alone, its value is drawn on the page as
  ;; html with the data folded under it, and the model reads the value as the data it is, as before
  (let [[_ branch eid] (paged-dbs)
        k "a3"
        v (ui/pull-front {:seon.ctx/ctx (d/pull branch '[*] eid) :seon.render/index (seon.db/index)})
        code "(seon.render.ui/pull-front opts)"
        rec (seon.repl/record-details code {:value v} {})
        db (d/db-with branch (seon.repl/record-tx {:seon.agent/key k} "h1" (seon.task/sender "a3")
                                                  (merge rec {:seon.eval/call (seon.task/call-ref k 1) :seon.eval/response {:handle 'result/h1 :value (get-in rec [:seon.eval/response :value])}})))
        html (component-html db "evaluation-h1")
        read (pr-str (seon.fixtures/eval-messages {:db db} (d/pull db '[*] [:seon.eval/id "h1"])))]
    (is (= :seon.render/hiccup-value (:seon.eval/schema rec)) "the eval names the schema the called render's contract answers")
    (is (some? html) "the eval, a component of the page")
    (is (str/includes? html "<h1>a3 as data</h1>") (str "the value drawn as html: " html))
    (is (re-find #"(?s)<details><summary>show as data</summary>.*:section" html) "and under it, folded, the value as data")
    (is (str/includes? read "[:section") "the model reads the value as data")
    (is (not (str/includes? read "<h1>")) "never as html")))

(def ^:private called (atom 0))

(defn a-function-a-value-names
  "A system function an agent's value names: counts each call, so a test sees whether drawing a value called it."
  {:malli/schema [:=> [:cat [:* :any]] :any]}
  [& _] (swap! called inc) [:p "CALLED"])

(deftest an-agents-hiccup-value-calls-nothing
  ;; docs/render.md#a-value-draws-itself: a value an agent's eval answered is drawn from the db, where a var is its symbol, and the walk
  ;; calls only a var or a fn at a vector's head: a value naming a system function is drawn as data and calls nothing
  (let [[_ branch _] (paged-dbs)
        k "a3"
        v [:section [:p "kept"] ['seon.render-test/a-function-a-value-names {} "x"]]
        db (d/db-with branch (seon.repl/record-tx {:seon.agent/key k} "h2" (seon.task/sender "a3")
                                                  {:seon.eval/code "(my-render)" :seon.eval/schema :seon.render/hiccup-value
                                                   :seon.eval/call (seon.task/call-ref k 1) :seon.eval/response {:handle 'result/h2 :value v}}))
        _ (reset! called 0)
        html (component-html db "evaluation-h2")]
    (is (some? html) "the eval is drawn")
    (is (str/includes? html "kept") "its plain elements are drawn")
    (is (= 0 @called) "the function the value names is never called")
    (is (not (str/includes? html "CALLED")) "and nothing it would answer is on the page")))

(deftest an-agents-own-draw-replaces-roots-at-the-same-revision
  ;; docs/render.md#a-value-draws-itself: root draws a live agent's page first, from its branch, and root holds no render the agent defined; the agent's answer must reach the page though each process counts revisions of its own
  (let [page (fn [u] (filter #(= "x" (:id %)) (:seon.render/units (ui/page-chrome {:units [u] :view "a1" :tab :chat} :a1))))
        roots (first (page {:id "x" :container "#c-rows" :rev 1 :html "<p>data</p>"}))
        agents (first (page {:id "x" :container "#c-rows" :rev 1 :html "<svg></svg>" :own? true}))]
    (is (some? roots))
    (is (not= (:input roots) (:input agents)) "the page's diff sees a new unit")))

(deftest words-to-an-agent-are-a-message-and-a-paren-is-code
  ;; docs/render.md#the-page: the chat's input
  (let [ops (fn [forms a] (mapv (comp :op first) (seon.web/requests {:var 'seon.system/submit! :agent :a1 :forms forms} {:a1 a})))
        on-task {:status :idle :task "x/y"}]
    (is (= [:message] (ops "hello there" on-task)) "words")
    (is (= [:eval] (ops "(+ 1 2)" on-task)) "code")
    (is (= [:eval] (ops "  (+ 1 2)" on-task)) "code, with room before it")
    (is (= [:eval] (ops "(+ 1 2)" {:status :idle})) "code, to an agent with no task")
    (is (= [:message] (ops "hello" {:status :idle})) "and words are a message to it")
    (is (= [] (ops "  " on-task)))))

(deftest a-quote-in-a-message-reads-as-a-quote
  ;; docs/render.md#text-and-the-one-string-cutter: what a person reads has its marks drawn; what the model reads stays as written
  (let [text "Here:\n\n> **Blue** is `rare`.\n  > and lazy\nstill\n\nDone."
        page (h/html (seon.render.ui/markdown text {:inline? true}))
        model (h/html (seon.render.ui/markdown text))]
    (is (str/includes? page "<blockquote"))
    (is (str/includes? page "<strong>Blue</strong>") "emphasis drawn")
    (is (str/includes? page "<code>rare</code>"))
    (is (not (str/includes? page "&gt;")) "no mark left")
    (is (str/includes? page "still"))
    (is (str/includes? model "&gt; **Blue**") "as written for the model")))

(deftest a-persons-agent-stays-open-and-is-asked-for-no-proof
  ;; docs/agent.md#working-for-a-person: no rule finishes a conversation, and being told to name a proof made an agent invent code to answer a question
  (let [base {:proofs #{} :results {} :main-green #{} :allowed-red #{} :uncontracted #{} :unmerged [] :caused #{} :plan "p" :plan-before "p" :deferred #{}}
        reasons #(set (map :reason (seon.reconcile/done-explain (assoc base :kind %))))]
    (is (= #{:open} (reasons :user)) "not done, and nothing asked of it")
    (is (contains? (reasons :whole) :no-proofs) "work that merges still is")))

(deftest a-steps-buttons-call-roots-table
  ;; docs/turn.md#an-eval-can-be-run-again: each button is a call of root's one table, and a request the button makes is no record of the conversation
  (let [tools (#'ui/step-tools :a1 {:seon.eval/id "e3" :seon.eval/code "(+ 1 2)"})
        nodes (tree-seq vector? seq tools)
        attrs (keep #(when (and (vector? %) (map? (second %))) (second %)) nodes)
        calls (filter :seon/call attrs)
        request #(seon.system/request 'again! :a1 % {:status :idle})]
    (is (= #{{:eval "e3"} {:eval "e3" :to-here true}} (set (keep :seon/args (filter #(and (= "button" (:type %)) (= 'seon.system/again! (:seon/call %))) calls)))) "run and run down to it")
    (is (= {:op :back :id :a1 :eval "e3"} (seon.system/request 'back! :a1 {:eval "e3"} nil)) "back to here is root's")
    (is (some #(and (= 'seon.system/back! (:seon/call %)) (= {:eval "e3"} (:seon/args %))) calls) "and a button")
    (is (every? #(= :a1 (:seon/agent %)) calls))
    (is (some #(= "(+ 1 2)" %) nodes) "the edit box holds its code")
    (is (some #(contains? % :data-ignore-morph) attrs) "what a person is typing survives a new eval landing")
    (is (= :context (:origin (request {:eval "e3"}))) "the button's own call is in no conversation")
    (is (= "(seon.repl/again \"e3\")" (:forms (request {:eval "e3"}))))
    (is (= "(seon.repl/again-to \"e3\")" (:forms (request {:eval "e3" :to-here true}))))
    (is (= "(seon.repl/again \"e3\" \"(+ 2 2)\")" (:forms (request {:eval "e3" :code "(+ 2 2)"}))))))
(deftest a-merged-task-reads-merged-everywhere
  ;; owner, 2026-11-05: the head said "merged" while the task's panel said "done": its agent's own end (:green) was read where
  ;; the task's outcome (:merged) was ignored; standing is one answer, from the task's outcome where it holds one
  (let [c {:seon.agent/outcome :green :seon.agent/status :idle :seon.agent/max-turns 4 :seon.model-call/_agent {"t1" {:seon.model-call/n 1}}
           :seon.agent/task {:seon.task/id "app.x/a-thing" :seon.task/outcome :merged}}
        opts {:seon.ctx/ctx c :seon.db/db (ts/test-db)}]
    (is (= "merged" (:text (ui/standing {:seon.agent/outcome :green} {:seon.task/outcome :merged}))))
    (let [h (h/html (ui/task-view {:seon.render/index (seon.db/index)} (:seon.agent/task c)))]
      (is (str/includes? h "task · app.x · merged") "the task's tile")
      (is (not (str/includes? h "· done")) "not the agent's own end"))
    (is (str/includes? (pr-str (ui/agent-state opts)) "● merged") "the agent state")
    (is (not (str/includes? (pr-str (ui/agent-state opts)) "● done")))))

(clojure.test/deftest the-banner-counts-what-root-counts
  ;; task seon.render/the-banner-counts-what-root-counts (docs/health.md#cost): a repeat of a signature main already
  ;; holds is counted in the api's fold, not on main. The api asks the render proc for main's health (:health), as
  ;; my.root/problems does, and the problems banner is what root's walk then draws: it names the count the api names.
  (ts/test-branch! (fn [_ conn]
    (let [[_ branch _] (paged-dbs conn)
          main0 (do (d/transact conn {:tx-data (seon.test-store/root-tx {:seon.config.agent/max-running 4})}) (:db-after (d/transact conn {:tx-data (seon.system/root-agent-boot-tx @conn)})))
          e (seon.contract/error :seon.instrument/contract-error 'app/f "app/f invalid-input: its arguments do not satisfy its contract"
                                 {:seon.error/member :malli.core/invalid-input :seon.error/problems [{:path [0] :in [0] :expected :int :value {:seon.print/code "nil"}}]})
          occ (fn [i] (seon.error/occurrence e :eval {:agent :a1 :task "t/a" :form "(f 1)"} (+ 1000 i)))
          [f1 tx] (seon.error/flush-tx (seon.error/fold {} (occ 0)) main0)
          main1 (:db-after (d/transact conn {:tx-data tx}))
          fold (reduce seon.error/fold f1 (map occ (range 1 4)))
          ;; main holds the signature once; the api's fold has seen it four times
          api (assoc (#'seon.system/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 64) :view {:agents {}} :graph (promise)
                                            :system (doto (promise) (deliver {:procs {} :conns [] :parts {[:proc :api] [{:proc 'seon.system/api-proc} nil]}}))})
                     :seon.system/errors fold)
          [_ asked] (#'seon.system/api-proc api :ext {:op :health :db main1 :p (promise)})
          st (assoc (#'p/advance-main (dissoc (proc-state main0 branch) :main-db) main0) :conn conn)
          [st _] (reduce (fn [[st _] m] (#'p/render-proc st :from-web m)) [st nil] (get asked [:render :from-web]))
          count-of (fn [h] (get-in h [:classes :eval :rows 0 :count]))
          banner (:html (second (filter #(= "#p-tiles" (:container %)) (:seon.render/units (#'p/page-value st :root)))))]
      (is (= 4 (count-of (seon.problems/health main1 fold))) "root counts the signature four times")
      (is (str/includes? banner "eval ×4") (str "the banner names root's count: " banner))
      ;; the audit of baaa7e10a8: the same main asked again after three more repeats, which only the fold holds, answers the new count
      (let [fold2 (reduce seon.error/fold fold (map occ (range 4 7)))
            [_ asked2] (#'seon.system/api-proc (assoc api :seon.system/errors fold2) :ext {:op :health :db main1 :p (promise)})
            [st2 out] (reduce (fn [[st _] m] (#'p/render-proc st :from-web m)) [st nil] (get asked2 [:render :from-web]))
            answered (some #(when (= :health-answer (:op %)) (:r %)) (get out :to-api))
            banner2 (:html (second (filter #(= "#p-tiles" (:container %)) (:seon.render/units (#'p/page-value st2 :root)))))]
        (is (= 7 (count-of answered)) (str "the api's answer counts all seven: " (pr-str (count-of answered))))
        (is (str/includes? banner2 "eval ×7") (str "and so does the banner: " banner2)))
      (r/dispose-walk! (get-in st [:root-walk :w]))))))

(clojure.test/deftest the-config-panel-names-each-source
  ;; task seon.render/config-shows-where-each-value-came-from (docs/config.md#layers): beside a value, the layer root recorded for it on main, a fact, so no test depends on the process it runs in
  (ts/test-branch! (fn [_ conn]
    (d/transact conn {:tx-data (seon.task/config-tx (assoc seon.config/config :seon.config.agent/max-running 6) {:seon.config.agent/max-running "--set"})})
    (let [db @conn
          sources (:seon.config/sources (d/pull db [:seon.config/sources] [:seon.system/id :root]))
          html (h/html (ui/config-panel {:seon.render/index (seon.db/index) :seon.config/sources sources} (seon.config/from-db db)))]
      (is (= "--set" (:seon.config.agent/max-running sources)))
      (is (str/includes? html "from --set") "the panel draws the layer main records")))))

(deftest a-call-in-flight-draws-its-stream-element-until-its-evals-ran
  ;; docs/render.md#in-flight-output-streams: from the call's commit until :seon.model-call/forms (its evals all ran) or its
  ;; error, the turn draws one element, its id the call's, busy, kept through redraws (data-ignore-morph), for the page alone;
  ;; the committed reply and evals draw beside it; an outside call never has one
  (let [[db aid] (cited-world)
        k (:seon.agent/key (d/pull db [:seon.agent/key] aid))
        cid (seon.lib/call-id k 1)
        ;; the agent's page as the walk draws it: the turn places the call's view, drawn by its own render (ui/model-call-view)
        at (fn [db] (let [w (draw (:tree (seon.ctx/build (seon.db/index) db aid :b)) seon.ctx/changes db #'ui/agent)]
                      (try (apply str (map :html (seon.fixtures/page-units w))) (finally (r/dispose-walk! w)))))
        open (d/db-with db [{:db/id aid :seon.agent/turn 1}
                            {:seon.model-call/id cid :seon.model-call/agent aid :seon.model-call/n 1 :seon.model-call/origin :model}])
        busy (at open)
        sel (seon.render/key-id [cid])
        ;; the in-flight element, by its id (the page holds other busy elements: its agent's state)
        streaming (str "id=\"" sel "\"")]
    (is (str/includes? busy (str "id=\"" sel "\"")) (str "the in-flight element, its id the call's: " busy))
    (is (str/includes? busy "aria-busy=\"true\"") "busy while the model answers")
    (is (str/includes? busy "data-ignore-morph") "kept as it is through the turn's redraws")
    (is (= [] (seon.ctx.wire/model-call-msgs {:seon.db/db open} (d/pull open '[*] [:seon.model-call/id cid]))) "the model reads nothing of it")
    (let [replied (d/db-with open [{:seon.model-call/id cid :seon.model-call/reply "Found it."}])]
      (is (str/includes? (at replied) "Found it.") "the committed reply draws while its evals run")
      (is (str/includes? (at replied) streaming) "beside the element, until its evals have run")
      (is (not (str/includes? (at (d/db-with replied [{:seon.model-call/id cid :seon.model-call/forms 1}])) streaming))
          ":forms ends it"))
    (is (not (str/includes? (str (at (d/db-with open [{:seon.model-call/id cid :seon.model-call/error {:seon.error/type :seon.model/transport :seon.error/message "gone"}}]))) streaming))
        "an error committed ends it too")
    (is (not (str/includes? (str (at (d/db-with open [{:seon.model-call/id cid :seon.model-call/origin :outside}]))) streaming))
        "an outside agent's call streams nothing")))

(deftest a-call-in-flight-keeps-its-committed-evals
  ;; docs/render.md#in-flight-output-streams: a call answered with tool calls alone commits evals and no reply text; while
  ;; it is in flight its turn draws those evals, and the in-flight element beside them, never in their place (found by
  ;; Optimization: they vanished from the page and the wire)
  (let [[db aid] (cited-world)
        k (:seon.agent/key (d/pull db [:seon.agent/key] aid))
        db (d/db-with db [{:db/id aid :seon.agent/turn 1}
                          {:seon.model-call/id (seon.lib/call-id k 1) :seon.model-call/agent aid :seon.model-call/n 1 :seon.model-call/origin :model}
                          {:seon.eval/id "e3" :seon.eval/agent aid :seon.eval/by {:seon.agent/key "a1"} :seon.eval/call (seon.task/call-ref k 1) :seon.eval/call-id "c1"
                           :seon.eval/intent "Run the proofs." :seon.eval/code "(prove-it)" :seon.eval/response {:handle 'result/e3 :value 1}}])
        turn (ui/conversation-turn {:seon.ctx/ctx (:tree (seon.ctx/build (seon.db/index) db aid :b)) :seon.db/db db :key 1})
        reply (some #(when (= :section (first %)) %) (drop 2 turn))]
    (is (= ["e3"] (map #(get % :seon.eval/id) (last (last (last reply)))))
        (str "its eval is the reply's step: " (pr-str reply)))
    (let [w (draw (:tree (seon.ctx/build (seon.db/index) db aid :b)) seon.ctx/changes db #'ui/agent)
          page (try (apply str (map :html (seon.fixtures/page-units w))) (finally (r/dispose-walk! w)))]
      (is (str/includes? page "aria-busy") "beside the in-flight element")
      (is (< (str/index-of page "seon-stream") (str/index-of page "id=\"evaluation-e3\"")) "the element is in the turn the page draws, its call's, above the step"))
    (is (not-any? #(str/includes? (pr-str %) "seon-stream")
                  (concat (seon.ctx.wire/model-call-msgs {:seon.db/db db} (d/pull db '[*] [:seon.model-call/id (seon.lib/call-id k 1)]))
                          (seon.fixtures/eval-messages {:db db} (d/pull db '[*] [:seon.eval/id "e3"]))))
        "and the model's renders of its call and eval never read it: the element is the page's alone")))

(deftest a-components-root-keeps-its-classes
  ;; owner, 2026-11-08: the home page's messages touched; a root's own classes (its layout, seon-stack's gap) were replaced
  ;; by the render's name, so a class written on a component's root did nothing. Kept after the name; the model reads none.
  (is (= [:section {:class "user-messages seon-stack" :id "k"} "x"] (#'r/with-id [:section {:class "seon-stack"} "x"] {:class "user-messages" :id "k"})))
  (is (= [:section {:class "chat-message" :id "k"} "x"] (#'r/with-id [:section {} "x"] {:class "chat-message" :id "k"})) "no class of its own: the name alone")
  (is (nil? (:class (#'r/strip-attrs {:class "user-messages seon-stack" :id "k"}))) "the model's reading carries no class"))

(deftest a-users-home-is-tiles-by-what-needs-them
  ;; owner, 2026-11-08: the home page is a tile per session, those that need the person first, each saying what its agent last told
  ;; them, not a feed of every message. Step 3: each is the agent's entry (seon.render.ui/agent nested), its group a datum the page orders by
  (let [db (sessions-db "b2" [["b2/a1" :a1 :running "seon.print/a-long-string" :running :task-a] ["b2/a2" :a2 :idle "x/asks" :parked :task-b]])
        started (mapv #(:db/id (d/entity db [:seon.agent/key %])) ["b2/a1" "b2/a2"])
        db (d/db-with db (into [{:db/id "sean" :seon.agent/key "sean" :seon.user/name "sean"}
                          {:seon.task/id "x/asks" :seon.task/reason "Which of the two names should the function take?"}]
                         (started-tx "sean" started)))
        units (user-units db "sean")
        entry (fn [k] (some #(when (= (r/key-id [(quote agent) k]) (:id %)) (:html %)) units))
        html (apply str (map :html (filter #(= "#p-task" (:container %)) units)))]
    (is (re-find #"data-standing=\"parked\"" (or (entry "b2/a2") "")) (str "the parked one is in the group that comes first: " (entry "b2/a2")))
    (is (str/includes? (or (entry "b2/a2") "") "Which of the two names should the function take?") "its entry says what its agent asked, while it waits")
    (is (str/includes? (or (entry "b2/a2") "") "class=\"said\"") "in the entry's own text")
    (is (not (str/includes? html "seon.render.ui/message")) "and the page has no feed of every message")))

;; seon.web/a-tabs-first-keyframe-settles-on-its-own (the 2026-11-10 occurrence): seon.check/idle's last wait, a commit to main reaching
;; root's quiet tab. A held task is one root's head counts (the tab is owed a patch); a namespace entity is nothing root's page reads,
;; so that commit owes the tab nothing, and a wait on it could only be met by bytes something else happened to send
(defn- root-tab-after
  "What the render proc sends root's tab when main moves by tx-data, from root's view walked and quiet on conn's main."
  [conn tx-data]
  (let [[_ branch _] (paged-dbs conn)
        main0 (do (d/transact conn {:tx-data (seon.test-store/root-tx {:seon.config.agent/max-running 4})}) (:db-after (d/transact conn {:tx-data (seon.system/root-agent-boot-tx @conn)})))
        st0 (#'p/pass (assoc (#'p/advance-main (assoc (proc-state main0 branch) :watch #{:root} :main-db nil) main0) :conn conn) (volatile! {}))
        st0 (#'p/pass st0 (volatile! {}))
        o (volatile! {})
        main1 (:db-after (d/transact conn {:tx-data tx-data}))]
    (#'p/pass (#'p/advance-main st0 main1) o)
    (filter #(and (#{:patch :keyframe} (:op %)) (= :root (:view %))) (:to-web @o))))

(deftest a-commit-root-shows-reaches-a-quiet-root-tab
  (ts/test-branch! (fn [_ conn]
    (is (seq (root-tab-after conn [{:seon.task/id "idle/check" :seon.task/ns 'idle :seon.task/name "check" :seon.task/plan "p" :seon.task/execution :held
                                    :seon.task/branch :task-idle-check :seon.task/kind :work}]))
        "a held task moves root's head: the tab is sent it"))))

(deftest a-commit-root-does-not-show-owes-its-tab-nothing
  (ts/test-branch! (fn [_ conn]
    (is (empty? (root-tab-after conn [{:db/id "n" :ns/name 'idle.check}]))
        "the idle check's old commit: nothing root's page reads, so nothing is sent"))))

;; seon.web/a-tabs-first-keyframe-settles-on-its-own (the 2026-11-10 occurrence): a tab on root's view, walked and quiet, then an
;; agent spawned: its event and root's view with the new agent reach the render proc, and the tab is sent what moved
(deftest a-spawn-reaches-a-quiet-root-tab
  (let [[main branch _] (paged-dbs)
        main (d/db-with main [{:seon.system/id :root :seon.config.agent/max-running 4}])
        ;; root's session, made at boot: root's page is its
        main (d/db-with main (seon.system/root-agent-boot-tx main))
        o (volatile! {})
        st0 (#'p/pass (#'p/advance-main (assoc (proc-state main branch) :watch #{:root} :main-db nil) main) (volatile! {}))
        _ (#'p/pass st0 (volatile! {}))
        st1 (#'p/on-event st0 {:type :spawn :agent :a9 :at-ms 1})
        view (assoc-in (:view st0) [:agents :a9] {:status :spawning :branch :task-demo-g})
        st2 (#'p/answered (assoc-in st1 [:pending 9] :state) o :state view)
        to-root (filter #(and (#{:patch :keyframe} (:op %)) (= :root (:view %))) (:to-web @o))]
    (is (seq to-root) (str "the spawn reaches root's tab: " (pr-str (map :op (:to-web @o)))))
    (is (some? st2))))
;; focus is a ref on the user entity (docs/users.md#whats-on-screen): a tab opening an agent's page writes it, home reads it
(defn- signed-in-main
  "Main with the user sean signed in, and focus on agent key focus when given."
  [main focus]
  (d/db-with main (cond-> [{:db/id "u" :seon.agent/key "sean" :seon.user/name "sean"} {:seon.system/id :root :seon.system/signed-in "u"}]
                    focus (conj [:db/add "u" :seon.user/focus [:seon.agent/key focus]]))))

(deftest the-user-entity-has-a-focus-ref-to-an-agent
  (let [a (get (d/schema (ts/test-db)) :seon.user/focus)]
    (is (= :db.type/ref (:db/valueType a)) "an optional ref on the user")))

(deftest opening-an-agents-page-writes-the-signed-in-users-focus
  (let [[main branch _] (paged-dbs)
        st (assoc (proc-state (signed-in-main main nil) branch) :watch #{:root})
        txs (fn [outs] (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= [:focus] (:tag %)) (:to-api outs)))))
        [st1 o1] (#'p/render-proc st :from-web {:op :views :views #{:root :a3} :tabs 1})]
    (is (= [[:db/add [:seon.user/name "sean"] :seon.user/focus [:seon.agent/key "a3"]]] (txs o1)) "the agent's page is the focus")
    (let [st2 (assoc st :main-db (signed-in-main main "a3") :watch #{:root :a3})
          [_ o2] (#'p/render-proc st2 :from-web {:op :views :views #{:root :a3} :tabs 2})]
      (is (empty? (txs o2)) "written only when it changes")
      (let [[_ o3] (#'p/render-proc st2 :from-web {:op :views :views #{:root :a3 :home} :tabs 2})]
        (is (= [[:db.fn/retractAttribute [:seon.user/name "sean"] :seon.user/focus]] (txs o3)) "opening home retracts it")))
    (is (some? st1))))

(deftest home-is-the-users-focus-else-roots-page
  ;; docs/users.md#whats-on-screen: home is the signed-in person's focus, else root's page, which presents the system to them
  ;; (owner, 2026-11-11: "the goal is mostly root running the presentation"; before, the person's own page)
  (let [[main _ _] (paged-dbs)
        a3 (fn [db] (:db/id (d/entity db [:seon.agent/key "a3"])))]
    (is (= (#'p/location (signed-in-main main nil) :root) (#'p/location (signed-in-main main nil) :home)) "no focus: root's page")
    (is (= (a3 (signed-in-main main "a3")) (#'p/location (signed-in-main main "a3") :home)) "a focus: its agent")
    ;; a focused task (an agent launched it; its agent is made a moment later): root's page until its agent exists, then that agent's
    (let [db (d/db-with (signed-in-main main nil) (seon.task/create-tx (signed-in-main main nil) [{:seon.task/ns 'request :seon.task/name "w" :seon.task/plan "Make w."}]))
          db (d/db-with db [[:db/add [:seon.user/name "sean"] :seon.user/focus [:seon.task/id "request/w"]]])]
      (is (= (#'p/location db :root) (#'p/location db :home)) "a focused task with no agent yet: root's page")
      (let [db (d/db-with db [[:db/add [:seon.task/id "request/w"] :seon.task/agent [:seon.agent/key "a3"]]])]
        (is (= (a3 db) (#'p/location db :home)) "its agent made: that agent's page")))))

;; Stage 2a's walk (docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09): one pass over a pull renders the page and
;; the model's messages. A lab schema in a branch's index names each entity's two renders; recognition is the best match of the
;; attributes an entity holds; a parent's render orders and places its children; a call placed in a model output is a
;; component of its own; the walk renders the model output only when asked (:outputs)
(defn lab-turns [c]
  (for [[_ call] (sort-by (comp :lab.ow/n val) (:lab.ow/_agent c))]
    [call (vec (for [[_ e] (:lab.ow/_by c) :when (= (:lab.ow/turn e) (:lab.ow/n call))] e))]))
(defn lab-agent [_ c] [:article [:h1 (:lab.ow/key c)] (into [:ol] (for [[call evals] (lab-turns c)] (into [:li call] evals)))])
(defn lab-turn-open [_ n] [{:role "user" :content (str "turn " n)}])
(defn lab-session-wire [_ c] (vec (concat [{:role "user" :content "open"}] (mapcat (fn [[call evals]] (concat [[#'lab-turn-open {} (:lab.ow/n call)] call] evals)) (lab-turns c)) [{:role "user" :content "close"}])))
(defn lab-agent-wire [_ c] (vec (mapcat (fn [[call evals]] (concat [[#'lab-turn-open {} (:lab.ow/n call)] call] evals)) (lab-turns c))))
(defn lab-call [_ x] [:p (str (:lab.ow/reply x))])
(defn lab-call-wire [_ x] [{:role "assistant" :content (str (:lab.ow/reply x))}])
(defn lab-eval [_ x] [:code (str (:lab.ow/code x))])
(defn lab-eval-wire [_ x] [{:role "tool" :content (str (:lab.ow/code x))}])

(deftest one-pass-renders-the-page-and-the-models-messages
  (let [forms {:lab.ow/key [:and {:seon.db/identity true} :string] :lab.ow/n :int :lab.ow/reply :string :lab.ow/code :string :lab.ow/turn :int
               :lab.ow/agent [:map {:seon.db/attributes true :seon.render/hiccup 'seon.render-test/lab-agent :seon.render/openai-compat 'seon.render-test/lab-agent-wire} [:lab.ow/key :lab.ow/key]]
               :lab.ow/call [:map {:seon.db/attributes true :seon.render/hiccup 'seon.render-test/lab-call :seon.render/openai-compat 'seon.render-test/lab-call-wire} [:lab.ow/n :lab.ow/n] [:lab.ow/reply {:optional true} :lab.ow/reply]]
               :lab.ow/eval [:map {:seon.db/attributes true :seon.render/hiccup 'seon.render-test/lab-eval :seon.render/openai-compat 'seon.render-test/lab-eval-wire} [:lab.ow/code :lab.ow/code] [:lab.ow/turn :lab.ow/turn]]}
        ix (seon.db/index-of {:forms forms})
        call (fn [id n r] {:db/id id :lab.ow/n n :lab.ow/reply r})
        ev (fn [id n code] {:db/id id :lab.ow/code code :lab.ow/turn n})
        ;; a collection as seon.ctx holds one: members by id, sorted as ctx sorts them
        coll (fn [& kvs] (into (sorted-map-by (fn [a b] (cond (and (int? a) (int? b)) (compare b a) (int? a) -1 (int? b) 1 :else (compare (str a) (str b))))) (apply hash-map kvs)))
        t2 {:db/id 1 :lab.ow/key "a" :lab.ow/_agent (coll 10 (call 10 1 "Reading.") 11 (call 11 2 "Fixing."))
            :lab.ow/_by (coll 20 (ev 20 1 "(+ 1 1)") 21 (ev 21 2 "(+ 2 2)"))}
        a (seon.ratom/atom t2)
        db (ts/test-db)
        walk (fn [outputs] (assoc (r/walker {:ref a :changes seon.ctx/changes} (constantly db) [#'lab-agent {} (seon.ratom/ref-view a seon.ctx/changes)] ix) :outputs outputs))
        w (walk #{:seon.render/hiccup :seon.render/openai-compat})
        ran (flush! w nil)]
    (is (= :lab.ow/agent (seon.db/most-specific ix t2)) "the pull's root is recognized by the attributes it holds")
    (is (= {:ambiguous [:lab.ow/call :lab.ow/eval]} (seon.db/most-specific ix {:lab.ow/n 1 :lab.ow/code "x"})) "a tie is an error value naming both")
    (is (= #{'seon.render-test/lab-agent 'seon.render-test/lab-call 'seon.render-test/lab-eval 'seon.render-test/lab-turn-open}
           (set (map (comp symbol first) (keys ran)))) "each entity rendered by its own schema's render, the placed call a component of its own")
    (is (str/includes? (h/html (r/resolved w @(:out w) true)) "Fixing."))
    (is (= [{:role "user" :content "turn 1"} {:role "assistant" :content "Reading."} {:role "tool" :content "(+ 1 1)"}
            {:role "user" :content "turn 2"} {:role "assistant" :content "Fixing."} {:role "tool" :content "(+ 2 2)"}]
           (r/openai-compat w)) "the messages, in the order the agent's model render places them")
    (swap! a assoc-in [:lab.ow/_by 21 :lab.ow/code] "(+ 2 3)")
    (is (= ['seon.render-test/lab-eval] (mapv (comp symbol first) (keys (flush! w nil)))) "one eval changed reruns its component alone, page and model")
    (is (= {:role "tool" :content "(+ 2 3)"} (peek (r/openai-compat w))))
    (let [before (pr-str (r/openai-compat w))]
      (swap! a (fn [t] (-> t (assoc-in [:lab.ow/_agent 12] (call 12 3 "Done.")) (assoc-in [:lab.ow/_by 22] (ev 22 3 "(+ 3 3)")))))
      (let [ran (flush! w nil) after (r/openai-compat w)]
        (is (= #{'seon.render-test/lab-agent 'seon.render-test/lab-call 'seon.render-test/lab-eval 'seon.render-test/lab-turn-open} (set (map (comp symbol first) (keys ran))))
            "a new turn runs the agent and the new turn's components alone")
        (is (= 4 (count ran)))
        (is (= before (pr-str (vec (take 6 after)))) "every earlier message is the same bytes")))
    (let [once (r/render-pull @a db #'lab-agent ix #{:seon.render/hiccup :seon.render/openai-compat} {:seon.render/changes seon.ctx/changes})]
      (is (= (r/openai-compat w) (:messages once)) "render-pull, one pass let go, answers what the kept walk does")
      (is (empty? (:messages (r/render-pull @a db #'lab-agent ix #{:seon.render/hiccup}))) "and asked for the page alone, no messages")
      (is (= (into [{:role "user" :content "open"}] (conj (r/openai-compat w) {:role "user" :content "close"}))
             (:messages (r/render-pull @a db #'lab-session-wire ix #{:seon.render/openai-compat} {:seon.render/as :seon.render/openai-compat})))
          "a root call answers the model output itself, placing the pull's entities each by its own model render"))
    ;; a walk rooted on its entity's render before the entity exists: flushed on an empty pull, then the entity, it answers the entity's
    ;; model render (the root's component reads its identity tracked)
    (let [e (seon.ratom/atom {})
          ew (assoc (r/walker {:ref e :changes seon.ctx/changes} (constantly db) [#'lab-agent {} (seon.ratom/ref-view e seon.ctx/changes)] ix) :outputs #{:seon.render/hiccup :seon.render/openai-compat})]
      (flush! ew nil)
      (is (= [] (r/openai-compat ew)) "no entity yet: no model output")
      (reset! e @a)
      (seon.ratom/flush!)
      (flush! ew nil)
      (is (= (r/openai-compat w) (r/openai-compat ew)) "the entity appears: the root runs again and answers its model render")
      (r/dispose-walk! ew))
    (let [h (walk #{:seon.render/hiccup}) _ (flush! h nil)]
      (is (= [] (r/openai-compat h)) "a walk asked for the page alone runs no model render")
      (is (not-any? :outs (vals @(:cache h)))))))

;; docs/references.md#an-entity-cites: a cite is rendered by its schema's render wherever a walk places it; a pulled var or section alone
;; is not (it prints as the data it is), since an agent's eval of a pull reads the data it asked for (Context, 2026-11-10)
(deftest a-placed-cite-is-its-schemas-render
  (let [ix (seon.db/index)
        a (seon.ratom/atom {:db/id 1 :c {:db/id 2 :seon.cite/id "c1" :seon.cite/depth :summary :seon.cite/order 0
                                         :seon.cite/target {:db/id 3 :file/path "README.md"}}
                            :v {:db/id 4 :var/name 'demo/f :var/ns 'demo}})
        view (seon.ratom/ref-view a seon.ctx/changes)]
    (is (= [#'ui/cite {:seon.render/nested true :key "c1"}] (r/entity-render ix (get view :c))) "a cite: its schema's render, keyed by its identity, marked nested (a parent placed it)")
    (is (nil? (r/entity-render ix (get view :v))) "a pulled var: no render, it prints")))

;; docs/render.md#the-page: a person's page asks for work; spawning agents and evaluating in every agent are root's debug page's
(deftest a-persons-input-holds-no-dev-tools
  (let [person (h/html (ui/root-input {:unit "p-input"} {}))
        debug (h/html (:seon.render/hiccup (ui/debug :root nil nil {:id "d-ctx"})))]
    (is (str/includes? person "What would you like done?"))
    (is (not (str/includes? person "spawn 10")) "no spawn on a person's page")
    (is (not (str/includes? person "all=&gt;")) "no eval-every-agent form")
    (is (str/includes? debug "spawn 10") "root's debug page holds them")))

;; docs/render.md#an-agents-address-and-the-agents-list: a status keeps its plain word (owner, 2026-11-08) and says beside it what the task
;; waits on, as a fact about the task (Point, 2026-11-10), so "working" beside an idle process or "parked" beside a dead one reads right
(deftest a-status-says-what-its-task-waits-on
  (let [s (fn [status o] (ui/standing {:seon.agent/status status} {:seon.task/outcome o}))]
    (is (= ["parked" "waits for a message"] ((juxt :text :detail) (s :dead :parked))))
    (is (= "ended, said how" (:detail (s :idle :told))))
    (is (= ["working" "between turns"] ((juxt :text :detail) (s :idle :running))))
    (is (nil? (:detail (s :busy :running))) "a running process is just working")
    (is (not-any? #(re-find #"(?i)\byou\b" (str (:detail %))) [(s :dead :parked) (s :idle :told) (s :idle :running)]) "no second person")))

;; docs/render.md#one-rendering-system-drawn-from-the-data: a view's units are rendered in main's entity index as root's walk holds it,
;; never the binary's once main is read, so a render main declares or changes is the one a unit runs
(deftest a-views-units-render-in-mains-index
  (let [ix {:forms {} :entity-schemas {} :marker ::mains}]
    (is (= ix (#'p/units-index {:root-walk {:w {:index (volatile! ix)}}})) "main's, as root's walk holds it")
    (is (= (seon.db/index) (#'p/units-index {})) "the binary's before main is read")))

;; docs/render.md#a-value-draws-itself: a pulled agent an eval answered prints as the data it is; the agent's render is its page, which
;; reads the walk's ctx and renders the entity a walk is rooted on
(deftest a-pulled-agent-value-prints-as-data
  (let [h (h/html (r/hiccup {:seon.render/index (seon.db/index)} {:db/id 7 :seon.agent/key "b2/a1" :seon.agent/status :idle}))]
    (is (str/includes? h "b2/a1") "its data")
    (is (not (str/includes? h "component-defect")) "never its page's error")))

;; docs/render.md#a-value-draws-itself: an eval whose record names its value's schema is a tile of the agent's parts, read by the seek
;; that answers it (the evals holding :seon.eval/schema, by its index), so an eval added with no schema reruns no tile list
(deftest an-evals-rendered-value-is-a-tile-read-by-a-seek
  (let [[db aid] (cited-world)
        k (:seon.agent/key (d/pull db [:seon.agent/key] aid))
        db (d/db-with db [{:seon.eval/id "e3" :seon.eval/agent aid :seon.eval/by {:seon.agent/key "a1"} :seon.eval/code "(prove)"
                           :seon.eval/schema :seon.test/proved :seon.eval/response {:handle 'result/e3 :value {:proofs {'a/b :green}}}}
                          {:seon.eval/id "e4" :seon.eval/agent aid :seon.eval/by {:seon.agent/key "a1"} :seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/e4 :value 3}}])
        opts {:seon.db/db db :seon.render/index (seon.db/index)}
        c {:db/id aid}
        kids (fn [hc] (keep #(when (and (vector? %) (= #'ui/eval-tile (first %))) (:key (second %))) (tree-seq coll? seq hc)))]
    (is (= ["e3"] (kids (ui/eval-tiles opts c))) "the eval with a rendered value, and only it")
    (is (str/includes? (h/html (ui/eval-tile (assoc opts :eid (seon.db/eid db :seon.eval/id "e3")))) "a/b") "the tile, by its schema's render")
    (let [[_ reads] (d/with-reads #(ui/eval-tiles opts c))
          db2 (d/db-with db [{:seon.eval/id "e5" :seon.eval/agent aid :seon.eval/by {:seon.agent/key "a1"} :seon.eval/code "(inc 1)" :seon.eval/response {:handle 'result/e5 :value 2}}])
          moved (apply concat (d/datoms-diff db db2 :eavt []))
          touched (seq (filter (fn [dt] (some #(contains? (d/reads-index reads) %) (datahike.reads/datom-keys dt))) moved))]
      (is (nil? touched) "an eval added with no schema touches nothing the tile list read"))))

(clojure.test/deftest the-root-tile-shows-the-defs-boot-loaded
  ;; docs/render.md#tiles-are-data-the-context-reads-the-pages-derivations: boot's program count is root's process data, drawn by the tile
  (let [tile (fn [view] (pr-str (seon.render.ui/process-tile {:seon.ctx/ctx {:process (seon.render.proc/process-value {:view view})}})))]
    (is (str/includes? (tile {:boot {:program {:loaded 1233 :ms 40}}}) "1233 forms loaded"))
    (is (str/includes? (tile {:boot {:program {:loaded 1233 :ms 40}}}) "40"))
    (is (str/includes? (tile {:boot {:program {:loaded 0 :ms 5}}}) "image held main's program"))
    (is (str/includes? (tile {:boot {}}) "image held main's program") "a boot that loaded nothing over its image")
    (is (not (str/includes? (tile {}) "program")) "no boot read yet: nothing said")))


(deftest a-sender-is-drawn-by-who-it-is
  ;; docs/users.md#messages: a message's :seon.message/from and an eval's :seon.eval/by are the entity that sent or ran it (an agent, a
  ;; user, the system), drawn by one render wherever a sender shows: a person or agent linked to its page by its name, Seon for the system
  (let [html #(h/html (ui/sender %))]
    (is (= "<a href=\"/agent/user\">sean</a>" (html {:seon.agent/key "user" :seon.user/name "sean"})) "a user by its name, linked to its page")
    (is (= "<a href=\"/agent/a3\">a3</a>" (html {:seon.agent/key "a3"})) "an agent by its key")
    (is (= "<span>Seon</span>" (html {:seon.system/id :root})) "the system as Seon")
    (is (nil? (ui/sender nil)) "none: nothing")
    (is (str/includes? (h/html (ui/message {} {:seon.message/from {:seon.agent/key "user" :seon.user/name "sean"} :seon.message/body "hi"}))
                       "href=\"/agent/user\"")
        "a message names its sender by that render")))

(deftest an-opened-step-says-who-ran-it
  ;; docs/users.md#messages: an opened step (seon.render.ui/eval-output) names who ran it from its :seon.eval/by: the agent's own turn, or
  ;; "run by" the sender (seon.render.ui/sender), as the pull joins it (with its :db/id, so "own" is the same entity, not a name)
  (let [[_ branch _] (paged-dbs)
        a (seon.db/eid branch :seon.agent/key "a3")
        out (fn [by] (h/html (ui/eval-output {:seon.db/db branch :seon.render/index (seon.db/index)}
                                             {:seon.eval/id "e1" :seon.eval/code "(+ 1 2)" :seon.eval/agent {:db/id a} :seon.eval/by by
                                              :seon.eval/response {:handle 'result/e1 :value 3}})))]
    (is (str/includes? (out {:db/id a :seon.agent/key "a3"}) "its own turn") "the agent's own")
    (is (str/includes? (out {:db/id (inc a) :seon.agent/key "user"}) "run by <a href=\"/agent/user\">user</a>") "another's: run by its sender, linked")
    (is (str/includes? (out {:db/id 1 :seon.system/id :root}) "run by <span>Seon</span>") "the system's")))

(deftest an-agent-an-agent-started-is-an-entry-on-its-page
  ;; docs/render.md#the-page (step 3 of pages from data): an agent another started is joined by the starter's pattern (:seon.agent/_parent)
  ;; and placed by the walk like any joined entity; its own render (seon.render.ui/agent) answers nested with its entry, a link to its
  ;; page: no ui code names it. The page's alone: the model's parts hold no entry
  (let [[_ branch _] (paged-dbs)
        branch (d/db-with branch [{:seon.agent/key "a5" :seon.agent/status :busy :seon.agent/branch :task-a5}
                                  {:seon.agent/key "a3" :seon.agent/_parent [[:seon.agent/key "a5"]]}])
        w (a3-walk branch)
        units (try (seon.fixtures/composed (r/placed-units w)) (finally (r/dispose-walk! w)))
        tiles (apply str (map :html (filter #(= "#p-task" (:container %)) units)))
        model (seon.fixtures/drawn-parts branch (seon.ctx/build (seon.db/index) branch (seon.db/eid branch :seon.agent/key "a3") :task-demo-f))]
    (is (str/includes? tiles "href=\"/agent/a5\"") "the started agent's entry, a link to its page, among the tiles")
    (is (some #(= "agent-a5" (:id %)) units) "a unit of its own, keyed by the agent's key")
    (is (not (str/includes? (pr-str model) "a5")) "the model's page holds no entry")))

(deftest describe-says-what-a-value-holds-by-path
  ;; docs/agent.md#looking-at-what-you-pulled (owner, 2026-11-11: "Instead of dumping the whole @ctx value we are now doing this more
  ;; metadata approach"): a value's paths and what each holds, never the value shown; keys, never positions; a plain value has no schema
  (let [v {:a 1 :n "demo/f" :s (apply str (repeat 20 "a word ")) :m {:b [1 2 3]} :ms (sorted-map 5 {:db/id 5 :x 1} 3 {:db/id 3 :x 2})}
        es (r/describe (seon.db/index) v [] 2)
        by (into {} (map (juxt :path :holds)) es)]
    (is (= 1 (by [:a])) "a scalar as itself")
    (is (= "demo/f" (by [:n])) "a name as itself")
    (is (= {:chars 140} (by [:s])) "text as its size, however short: what it says is read by its path")
    (is (= {:map 1 :keys [:b]} (by [:m])) "a map as its count and keys")
    (is (= {:vector 3} (by [:m :b])) "one level in, at depth 2")
    (is (= {:members 2 :ids [3 5]} (by [:ms])) "a map keyed by ids as its members")
    (is (every? #(not= ::none (get-in v (:path %) ::none)) es) "every entry's path reads back by get-in")
    (is (not-any? :schema es) "a plain value has no schema")
    (is (= [{:path [:a] :holds 1}] (r/describe (seon.db/index) v [:a] 2)) "a path to a scalar describes it alone")))

(deftest an-answer-says-what-it-is-and-what-reads-more
  ;; docs/render.md#a-render-is-a-value-printed-once (Point's ruling 2026-11-11, one way for every tool's answer): a value whose schema
  ;; says what brings more (:seon.render/more) prints as what it is (its description's first sentence), the value, and the forms that
  ;; act on it, each naming the answer by its handle; a plain value prints as itself: (+ 1 1) answers 2
  (let [ix (seon.db/index)
        render #(r/model ix %)
        es (with-meta [{:path [:a] :holds 1}] {:seon.render/schema :my.agent/described})
        text (seon.print/response-text {:handle 'result/e1 :value es} render)
        plain (seon.print/response-text {:handle 'result/e2 :value 2} render)]
    (is (str/includes? text "What a value holds, by path") (str "what it is, from its schema: " text))
    (is (not (str/includes? text "See docs/")) "the first sentence only")
    (is (str/includes? text "[:a]") "the value")
    (is (str/includes? text "(get-in result/e1 path)") "a form that reads more, naming the answer by its handle")
    (is (not (str/includes? text "(get-in v path)")) "never by a placeholder")
    (is (and (str/includes? plain ":value 2") (not (str/includes? plain ":is"))) (str "a plain value is itself: " plain))))

;; docs/render.md#how-the-work-stands: the done rule's reasons are data the task holds (:seon.task/reasons), read in their schema's words
;; (:seon.reconcile/reason's :seon.status/title), what each is about as code; never the flattened keyword string (Point, 2026-11-11: the
;; header read "proof-red seon.render-test/x; open-problem ..." raw)
(deftest an-agents-end-reasons-read-in-their-schemas-words
  (let [t {:seon.task/outcome :cap :seon.task/reason "proof-red seon.render-test/x; open-problem k"
           :seon.task/reasons [{:reason :proof-red :symbol 'seon.render-test/x} {:reason :open-problem :key "k"}]}
        r (:reason (ui/standing {:seon.agent/outcome :cap} t))]
    (is (= "proof red `seon.render-test/x`; problems to clear `k`" r) "each reason in its words, what it is about as code")
    (is (not (str/includes? r "proof-red")) "no keyword read raw")
    (is (= "root restarted" (:reason (ui/standing {} {:seon.task/outcome :cap :seon.task/reason "root restarted"})))
        "a reason with no data behind it is its string")))

(deftest describe-shows-what-acts-on-a-part-at-its-path
  ;; docs/render.md#a-render-is-a-value-printed-once (Point and Data model, 2026-11-11, after loop D2: nine reads to find where a cited
  ;; task's plan is): an entry whose schema says what acts on it carries those forms for its own path, and asked at a path, describe walks
  ;; into a join keyed by ids member by member, so a cited task shows the one form that changes its plan
  (let [ix (seon.db/index)
        v {:seon.agent/task {:db/id 1 :seon.task/id "x/a" :seon.task/plan "Do a."
                             :seon.cite/_of {5 {:db/id 5 :seon.cite/id "c" :seon.cite/target {:db/id 7 :seon.task/id "x/b" :seon.task/plan "Do b."}}}}}
        at (r/describe ix v [:seon.agent/task] 2)
        by (into {} (map (juxt :path identity)) at)
        target (by [:seon.agent/task :seon.cite/_of 5 :seon.cite/target])
        top (r/describe ix v [] 2)]
    (is (some? target) (str "the cited task has its own entry, member by its id: " (pr-str (map :path at))))
    (is (= :seon.task/task-fact (:schema target)))
    (is (= '[(swap! ctx assoc-in (conj [:seon.agent/task :seon.cite/_of 5 :seon.cite/target] :seon.task/plan) x)] (map second (:seon.print/to-use target)))
        "its form, for its own path")
    (is (not-any? #(= 5 (get (:path %) 2)) top) "a first look at the whole is the overview: no member walked")
    (is (not-any? (fn [e] (some #(some #{'v 'id 'path} (filter symbol? (tree-seq coll? seq (second %)))) (:seon.print/to-use e))) (concat at top))
        "no form shown holds a placeholder it could not fill")))

;; docs/ctx.md#the-pattern: root's evals are a window (the newest :seon.config.ctx/window); its line says how many it did not pull, in the printer's words
(deftest roots-evals-say-what-their-window-left-out
  (let [evals (fn [held joined] (with-meta (into {} (for [i (range held)] [(- 1000 i) {:seon.eval/id (str "e" i)}])) {:seon.ctx/count joined}))
        summary (fn [v] (let [[_ _ [_ _ s]] (seon.render.ui/root-evals {:seon.ctx/ctx {:evals v}})] s))]
    (is (= "20 evals sent to root, 92 not pulled" (summary (evals 20 112))))
    (is (= "20 evals sent to root" (summary (evals 20 20))) "a window holding every member says nothing left out")
    (is (= "20 evals sent to root" (summary (with-meta (evals 20 20) nil))))))

;; docs/health.md: a problem class reads in words, said after its count ("168 tasks that cite no section"), from :seon.problems/class's
;; :seon.status/title; the keyword stays the data (Point, 2026-11-11: the problems tile listed "uncited-task 168")
(deftest a-problem-class-reads-in-words
  (let [classes (drop 2 (get seon.contract/forms :seon.problems/class))
        words (:seon.status/title (seon.contract/properties :seon.problems/class))
        html (seon.web.html/html (ui/problems {} {:t 1 :open #{} :classes {:uncited-task {:n 168 :rows [{:producer nil :owner-ns nil :evidence {} :proofs [] :answered-by [] :at-ms nil}]}}}))]
    (is (= #{} (set (remove words classes))) "every class has its words: a new class is never a bare keyword")
    (is (str/includes? html "tasks that cite no section: 168") html)
    (is (not (re-find #">uncited-task" html)) "no bare keyword drawn")))

;; docs/render.md#the-page: a number the system's process tile shows says what it measures (Point and Web, 2026-11-11: "fork lag 23952 ms"
;; read as broken, "program load not measured" read as a gap): fork lag carries its meaning, and a boot that loaded no forms says so
(deftest the-process-tile-says-what-fork-lag-is
  (let [html (h/html (ui/process-tile {:seon.ctx/ctx {:process {:fork-server {:lag-ms 23952} :boot {}} :seon.system/boot "b1"}}))]
    (is (str/includes? html "how long a commit takes to reach the fork server") html)
    (is (not (str/includes? html "not measured")) "no gap where the boot loaded nothing")
    (is (str/includes? html "the image held main") html)))

;; docs/render.md#how-the-work-stands: a proof's verdict is what its last run saw at that run's program, not its state now (Tests' task
;; seon.render/a-verdict-shown-is-at-the-tests-digest; Data model: outside a status, a verdict reads as at its last run), so the agent's
;; state never says green now of a test edited since
(deftest the-agents-proofs-read-as-at-their-last-run
  (let [html (h/html (ui/agent-state {:seon.ctx/ctx {:db/id 1 :seon.agent/task {:seon.task/id "demo/g" :proved {'demo-test/g-test {:seon.test/status :green}}}}
                                      :seon.db/db (ts/test-db)}))]
    (is (str/includes? html "1 of 1 proofs green at their last run") html)))

;; docs/render.md#the-page: a fresh system tells a first-time person nothing it has not done (task seon.render/a-fresh-system-says-nothing-
;; it-has-not-done; seen 2026-11-11 on a fresh store: "1 parked.", "373 tasks not released yet.", "it starts again"): the head's counts say
;; what they count, the task files' held tasks are folded, and the input says what typing does now
(deftest a-fresh-system-says-nothing-it-has-not-done
  (let [db (d/db-with (ts/test-db) (for [i (range 3)] {:seon.task/id (str "demo/held-" i) :seon.task/ns 'demo :seon.task/name (str "held-" i)
                                                        :seon.task/plan "What exists: nothing." :seon.task/execution :held}))
        head (h/html (ui/root-head {:seon.render/index (seon.db/index) :seon.ctx/ctx {} :seon.db/db db}))
        input (h/html (ui/agent-input {:unit "u"} {:id :root :live? false :talks? true}))]
    (is (str/includes? head "<details") (str "the held tasks are folded: " head))
    (is (str/includes? head "3 tasks from the task files, not started") head)
    (is (str/includes? input "Say something and it starts") input)
    (is (not (str/includes? input "again")) "nothing ran yet: never again")))

;; docs/render.md#the-page: prose a model or a person wrote reads in the system's text face (--font-text, resources/public/css/input.css's
;; @theme); the theme sets --font-sans to the mono face for the page's chrome on purpose, so Markdown prose is drawn by .seon-md's own rule,
;; never by a font utility (jolt/readable put font-sans on it, which drew it mono)
(deftest prose-reads-in-the-text-face
  (let [css (slurp (clojure.java.io/file (seon.config/checkout) "resources/public/css/input.css"))
        md (h/html (ui/markdown "A sentence with `code`."))]
    (is (re-find #"\.seon-md \{ font-family: var\(--font-text\); \}" css) "the Markdown block's face is the text face")
    (is (re-find #"--font-sans: var\(--font-mono\)" css) "the chrome's face stays mono, which is why no font utility reaches prose")
    (is (str/includes? md "seon-md") md)
    (is (not (str/includes? md "font-sans")) "no utility that resolves to the mono face")))


(deftest a-cites-words-read-in-the-text-face
  ;; docs/render.md#the-page: the words a cite says of what it names ("A function your plan names.", "2 tests reach it") read in the text
  ;; face, as Markdown prose does; its code stays mono. A rule on the cite component's own style, page-only: nothing the model reads moves
  (let [rs (into {} (r/rules {#'ui/cite nil}))]
    (is (str/includes? (get rs 'seon.render.ui/cite "") ".cite > p { font-family: var(--font-text); }") (pr-str (get rs 'seon.render.ui/cite)))))

(defn note-html
  "The html render of the registered-schema world's note (:lab.note/note-fact, seon.render-test/a-joined-entity-of-a-registered-schema-reaches-the-page-and-the-request),
  an agent's own defn: its text behind a marker."
  {:malli/schema [:=> [:cat :map :map] :any]}
  [_ e] [:p (str "NOTE-HTML " (:lab.note/text e))])

(defn note-msgs
  "The model render of the registered-schema world's note: one user message, its text behind a marker of its own."
  {:malli/schema [:=> [:cat :map :map] [:vector :seon.model/message]]}
  [_ e] [{:role "user" :content (str "NOTE-MODEL " (:lab.note/text e))}])

(defn- registered-schema-world
  "The registered-schema world (docs/render.md#the-page): on a test db, as an agent would make it, a schema with both renders
  (:lab.note/note-fact, drawn by note-html and note-msgs; with history? false the html render alone, so no history),
  joined by the agent's own :seon.ctx/pattern, one note for agent lab/a1, and tx-data more written after it. Answers {:c the agent's
  pull :ix :page html :rows the conversation's html (c-rows) :msgs the request's messages, printed}."
  ([] (registered-schema-world {}))
  ([{:keys [history? more] :or {history? true}}]
  (let [reg (fn [db k form] (let [r (my.schema/registration db k form false)]
                              (when (seon.contract/error? r) (is false (str k " registers: " (pr-str r))))
                              (d/db-with db (:tx r))))
        db (-> (ts/test-db)
               (reg :lab.note/id [:and {:seon.db/identity true} :string])
               (reg :lab.note/text :string)
               (reg :lab.note/agent [:and {:seon.db/to [:seon.db/agent-fact]} :seon.db/ref])
               (reg :lab.note/note-fact [:map (cond-> {:seon.db/attributes true :seon.render/hiccup 'seon.render-test/note-html}
                                                   history? (assoc :seon.render/openai-compat 'seon.render-test/note-msgs))
                                            [:lab.note/id :lab.note/id] [:lab.note/text :lab.note/text] [:lab.note/agent :lab.note/agent]]))
        pattern (conj (r/schema-property (seon.db/index) :seon.db/agent-fact :seon.ctx/pattern) {[:lab.note/_agent :tx :at] [:db/id :lab.note/id :lab.note/text]})
        db (d/db-with db [{:seon.agent/key "lab/a1" :seon.ctx/pattern pattern}])
        db (d/db-with db [{:lab.note/id "n1" :lab.note/text "the note" :lab.note/agent [:seon.agent/key "lab/a1"]}])
        db (reduce d/db-with db more)
        db (d/db-with db (seon.doc/load-tx db "docs" {seon.doc/system-page "# A stub of the system prompt\n\nBe brief."}))
        ix (seon.program/index-of-db db)
        c (:tree (seon.ctx/build ix db (seon.db/eid db :seon.agent/key "lab/a1") :b))
        p (r/walk nil {:db db :tree c :root #'ui/agent :index ix :changes seon.ctx/changes :units true :outputs #{:seon.render/hiccup :seon.render/openai-compat}})
        units (seon.fixtures/composed (:units p))
        req (seon.ctx.wire/request p db)]
    (seon.fixtures/no-error-render (:defects p))
    {:c c :ix ix :page (apply str (map :html units)) :msgs (pr-str (:messages req)) :parts (:parts req)
     ;; the conversation as the page shows it: the units its list holds (container #c-rows, seon.render.ui/conversation-list)
     :rows (apply str (keep #(when (= "#c-rows" (:container %)) (:html %)) units))})))

(deftest a-joined-entity-of-a-registered-schema-reaches-the-page
  ;; docs/render.md#the-page: what an agent's pull joins reaches the page by its schema's html render, no code naming it.
  ;; Registered as data, as an agent would (registered-schema-world): its schema is history (seon.render/history?), so the conversation places it (seon.ctx/placed-messages)
  (let [{:keys [c ix page]} (registered-schema-world)]
    (is (= ["the note"] (map :lab.note/text (vals (:lab.note/_agent c)))) "the agent's pull holds the note it joins")
    (is (= :lab.note/note-fact (r/entity-schema ix (first (vals (:lab.note/_agent c))) nil)) "the note is its registered schema's entity")
    (is (str/includes? page "NOTE-HTML the note") "the note's html render draws it on the agent's page")))

(deftest a-joined-entity-of-a-registered-schema-reaches-the-request
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: what an agent's pull joins reaches the model's request by its schema's
  ;; renders, no code naming it (registered-schema-world): by its model render where it is history, by its html inside the page eval
  ;; (my.render/page) where it is a part
  (let [{:keys [msgs]} (registered-schema-world)]
    (when-not (is (or (str/includes? msgs "NOTE-MODEL the note") (str/includes? msgs "NOTE-HTML the note"))
                  "the note reaches the request's messages, as history or in the page")
      (println "the request's messages:" msgs))))


(deftest a-placed-history-member-is-labelled-by-its-schema
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: the debug page labels each message the model was sent by what answered
  ;; it (seon.ctx.wire/part-ref); a member the conversation placed that is no message, eval or reply (the note) is labelled by its
  ;; entity schema and id, never as the session's opening
  (let [{:keys [parts]} (registered-schema-world)
        refs (map first parts)]
    (is (some #(= :lab.note/note-fact (first %)) refs) (str "the note's part is labelled by its schema: " (pr-str refs)))
    (is (not-any? #(and (= :session (first %)) (nil? (second %))) refs) "no part labelled as a session with no key")))

(deftest a-join-whose-target-is-no-history-is-no-conversation
  ;; docs/turn.md#the-turn-is-a-number: the conversation is the joins whose target is history (seon.render/history?, Data model's
  ;; ruling): an agent's own pattern asking a join with :tx :at whose schema has no model render (a cite, a started agent, here a
  ;; registered-schema note drawn for html alone) puts none of its members in the conversation
  (let [{:keys [c rows page msgs]} (registered-schema-world {:history? false})]
    (is (= ["the note"] (map :lab.note/text (vals (:lab.note/_agent c)))) "the pull holds the note, with its link's :at")
    (is (some :at (vals (:lab.note/_agent c))))
    (is (str/includes? rows "system message") "the conversation is drawn")
    (is (not (str/includes? rows "NOTE-HTML")) "no member of it is placed in the conversation")
    (is (str/includes? page "NOTE-HTML the note") "the page draws it as a part")
    ;; the model reads it as the page shows it (its page message), never as a member of the conversation; before seon.ctx.wire/db-at
    ;; read a point that is db's own as db, this world's first page was drawn at the parent commit, where the agent was not yet, and
    ;; held nothing at all
    (let [holding (for [[ref ms] (:parts (registered-schema-world {:history? false})) :when (str/includes? (pr-str ms) "the note")] (first ref))]
      (is (= [:page] (distinct holding)) (str "nor read by the model as a conversation member: " (pr-str holding))))))

(deftest an-eval-by-another-hand-reaches-the-request-only-as-the-systems-with-an-intent
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: an eval no call holds is placed (seon.ctx/placed-messages) and the
  ;; page shows it; the model reads one by another than the agent only when it is the system's and holds an intent (seon.ctx.wire's
  ;; turn-entities, Context and Data model 2026-11-11): the system's own work (seon.turn/work!, no intent) and another agent's stay out
  (let [ev (fn [id by code intent] (cond-> {:seon.eval/id id :seon.eval/agent [:seon.agent/key "lab/a1"] :seon.eval/by by :seon.eval/code code
                                            :seon.eval/response {:handle (symbol "result" id) :value 1}}
                                     intent (assoc :seon.eval/intent intent)))
        {:keys [page msgs]} (registered-schema-world {:more [[(ev "e-work" {:seon.system/id :root} "(+ 100 1)" nil)]
                                                   [(ev "e-said" {:seon.system/id :root} "(+ 200 2)" "Read the plan.")]
                                                   [(ev "e-other" {:seon.agent/key "lab/b1"} "(+ 300 3)" nil)]]})]
    (doseq [code ["(+ 100 1)" "(+ 200 2)" "(+ 300 3)"]] (is (str/includes? page code) (str "the page shows " code)))
    (is (str/includes? msgs "(+ 200 2)") "the system's eval with an intent reaches the request")
    (is (not (str/includes? msgs "(+ 100 1)")) "the system's work with no intent does not")
    (is (not (str/includes? msgs "(+ 300 3)")) "nor another agent's")))

(deftest a-proof-to-write-is-described-with-its-namespace
  ;; task seon.render/a-proof-to-write-is-described-with-its-namespace (loop D3, 2026-11-12: twelve turns finding a test
  ;; namespace's aliases and how it sets up a world): an unwritten proof's shape holds its namespace's whole :require and its
  ;; use-fixtures, read from the namespace's forms, and the status says both (docs/render.md#how-the-work-stands)
  (let [db (my.test/with (my.test/world)
                         (my.test/code 'app.calc-test (str "(ns app.calc-test (:require [clojure.test :refer [deftest is use-fixtures]] [app.calc :as calc] [clojure.string :as str]))\n"
                                                           "(def ^:dynamic *k* 1)\n(use-fixtures :each (fn [f] (binding [*k* 2] (f))))\n(deftest a-test (is (= 2 *k*)))\n")))
        shape (seon.ctx.status/test-shape db 'app.calc-test/new-proof)
        h (h/html (ui/status-hiccup {} {:proofs [{:test 'app.calc-test/new-proof :status :undefined :shape shape}]}))
        ;; estimate: the old shape's 56k ticks (one index read, each var once) and one query over the namespace's four forms
        {:keys [ticks]} (seon.cost/measure (fn [] (seon.ctx.status/test-shape db 'app.calc-test/new-proof)) [])]
    (is (= "(:require [clojure.test :refer [deftest is use-fixtures]] [app.calc :as calc] [clojure.string :as str])" (:requires shape))
        "the whole :require, the alias no example calls too")
    (is (= ["(use-fixtures :each (fn [f] (binding [*k* 2] (f))))"] (:fixtures shape)) "its fixtures as written")
    (is (str/includes? h "[clojure.string :as str]") "the status shows the :require")
    (is (str/includes? h "use-fixtures :each") "and the fixtures")
    (is (< ticks 1e6) (str "test-shape on a four-form namespace: estimate 100k ticks, measured " ticks))))
