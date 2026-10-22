(ns seon.render.ai
  "The AI renders that earn their place (DECISIONS: an AI render exists only when it
  focuses attention, adds context or filters excess; W1 §1.4).

  `system` is root's context: the whole system (:seon.render/system) as a compact text
  root reads before it acts. The printer of the same value would spend its budget on the
  first agents' maps; this one focuses: what is wrong first (root's defects, then what
  main recorded: dead agents, red tests, refused merges among them), then the work (tasks and attempts),
  then the load (writer, fork server, main), then what is costly (the functions the
  newest evaluations spent most in), then the newest events. Sections are added in that
  order until the token budget is spent; what did not fit is named, never silently cut.

  The values agents and root read at the REPL (seon.render/printed) render here too, each a
  few lines that cite what they show and the form that reads the rest (env
  :seon.render/handle, the value's result handle or a form that reads it): an error map
  (`error`), a task (`task`), an attempt (`attempt`) and a problem row (`problem-row`, the
  line root's context and the page's banner lead with)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [seon.config :as config] [seon.print :as print] [seon.print.select :as select] [seon.render.ui :as ui]))

(defn- line-budget {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/line-budget))
(defn- part {:malli/schema [:=> [:cat :any] :string]} [v] (print/text v (config/value :seon.config.render/part-budget)))

(defn- read-of
  "The form that reads part k of the value env shows (k applied to its handle), nil without one."
  {:malli/schema [:=> [:cat :seon.render/env :any] :any]}
  [env k] (some->> (:seon.render/handle env) (list k)))

(defn- form
  "A form the reader evaluates to read more, printed by the one printer."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [f] (some-> f (print/text (config/value :seon.config.render/form-budget))))

(defn- whole
  "The closing line, when env names a handle: the form that shows the whole value as data
  (without the metadata that names its render, the printer prints it)."
  {:malli/schema [:=> [:cat :seon.render/env] [:maybe :string]]}
  [env] (when-let [h (:seon.render/handle env)] (str "the whole value as data: " (form (list 'with-meta h nil)))))

(defn error
  "An error map (:seon.error/base and every variant): its message first, as a REPL answers,
  the fix when it carries one, each refused part (malli's path, what it expected, the
  value), then its type, where it was raised and its variant's own keys printed, and the
  form that reads the whole."
  {:malli/schema [:=> [:cat :seon.error/base :seon.render/env] :seon.render/out]}
  [e env]
  (let [[ps more] (print/bounded (:seon.error/problems e) :seon.config.render.list/top
                                 (form (read-of env :seon.error/problems)))
        own (dissoc e :seon.error/type :seon.error/operation :seon.error/message :seon.error/fix :seon.error/problems)]
    {:seon.render/ai
     (str/join "\n" (concat [(:text (print/prose (:seon.error/message e) (config/value :seon.config.render/part-budget)
                                                  {:source (read-of env :seon.error/message)}))]
                             (when (:seon.error/fix e) [(str "fix: " (:seon.error/fix e))])
                             (for [{:keys [in expected value]} ps] (str "at " (part in) " expected " (part expected) ", got " value))
                             (some-> more vector)
                             [(str "(" (ui/kname (:seon.error/type e)) " in " (:seon.error/operation e) (when (seq own) (str " " (part own))) ")")]
                             (some-> (whole env) vector)))}))

(defn task
  "A task as an agent or root reads it: its id, kind, desired execution and needs, what is
  wrong with it (its problem, failure and the stuck fact), its plan (accumulated along its
  needs) as prose whose cut names the form that reads the rest, its proofs, its evidence,
  and the form that reads the whole."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [t env]
  (let [acc (:seon.task/accumulated t)
        plan (or (:plan acc) (:seon.task/plan t))
        [proofs more] (print/bounded (sort-by str (or (:proofs acc) (:seon.task/proofs t))) :seon.config.render.list/rows
                                     (form (read-of env :seon.task/proofs)))
        {:keys [reason counterexample]} (:seon.task/failure t)
        {:keys [cause blocks]} (:seon.task/stuck t)]
    {:seon.render/ai
     (str/join "\n"
       (remove nil?
         [(str "task " (:seon.task/id t) " · " (ui/kname (:seon.task/kind t)) " · " (ui/kname (:seon.task/execution t))
               (when (seq (:seon.task/needs t)) (str " · needs " (str/join ", " (sort (:seon.task/needs t)))))
               (some->> (:seon.task/branch t) ui/kname (str " · branch ")) (some->> (:seon.task/model t) (str " · model ")))
          (when (:seon.task/problem t) (str "problem: " (print/line (:seon.task/problem t) (line-budget) (read-of env :seon.task/problem))))
          (when reason (str "failed: " (print/line reason (line-budget)) (when counterexample (str " · counterexample " (print/line counterexample (line-budget))))))
          (when cause (str "blocked: " (print/line cause (line-budget)) (when (seq blocks) (str " · blocks " (str/join ", " (sort blocks))))))
          (when plan (str "plan: " (:text (print/prose plan (or (:seon.render/budget env) (config/value :seon.config.render/part-budget))
                                            {:source (if acc (some-> (:seon.render/handle env) (#(list 'get-in % [:seon.task/accumulated :plan]))) (read-of env :seon.task/plan))}))))
          (when (:seon.task/instructions t) (str "instructions: " (print/line (:seon.task/instructions t) (line-budget) (read-of env :seon.task/instructions))))
          (let [n (count (or (:proofs acc) (:seon.task/proofs t)))] (when (pos? n) (str n (if (= 1 n) " proof: " " proofs: ") (str/join " " proofs) (when more (str " · " more)))))
          (when (:seon.task/evidence t) (str "evidence: " (part (:seon.task/evidence t))))
          (whole env)]))}))

(defn attempt
  "An attempt in one line: running (its agent, turn and tokens) or ended (its outcome and
  reason, whose cut names the form that reads it), and what its end carried: conflicts,
  unmerged entries, what it changed, contract gaps. Root's context's work lines and the REPL."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [a env]
  (let [{:seon.attempt/keys [id agent status outcome reason turns tokens conflicts unmerged changed uncontracted]} a
        n (fn [xs word] (when (seq xs) (str " · " (count xs) " " word)))]
    {:seon.render/ai
     (str (if outcome
            (str "ended " id " " (ui/kname outcome)
                 (when reason (str ": " (print/line reason (line-budget) (read-of env :seon.attempt/reason)))))
            (str (ui/kname status) " " id (some->> agent ui/kname (str " on ")) (some->> turns (str ", turn ")) (some-> tokens (str " tokens") (->> (str ", ")))))
          (n conflicts "conflicts") (n unmerged "unmerged") (n changed "changed") (n uncontracted "without a contract"))}))

(defn problem-row
  "One problem row (seon.problems/report), :class (and its class's open count :n) assoc'd by
  its reader, as one line: what it is about (task, attempt, test, attribute), then why: the recorded message, the error's
  message, the failure's reason, the stuck cause, or a task conflict's two sides; a merge
  row's conflicts and unmerged keys; its outcome; and what answered it."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [r env]
  (let [ev (:evidence r)
        [k why] (some (fn [[k v]] (when v [k v]))
                      [[:message (:message r)] [[:error :seon.error/message] (get-in r [:error :seon.error/message])]
                       [:reason (:reason r)] [:cause (:cause r)]
                       [nil (when (= :task-conflict (:class r))
                              (:text (print/print-value (select-keys ev [:ours :theirs]) {:handle (read-of env :evidence) :budget (line-budget)})))]
                       [nil (some-> (:outcome r) ui/kname)]])
        what (remove nil? [(:task r) (:attempt r) (some-> (:test r) str) (some-> (:attr r) ui/kname)])]
    {:seon.render/ai
     (str (ui/kname (:class r)) (if-let [n (:n r)] (str " " n) (some->> (:count r) (str " ×"))) (when (seq what) (str " " (str/join " " what)))
          (when why (str ": " (if k (print/line why (line-budget) (some->> (:seon.render/handle env) (#(if (vector? k) (list 'get-in % k) (list k %))))) why)))
          (when (pos? (or (:conflicts r) 0)) (str " · " (:conflicts r) " conflicts"))
          (when (seq (:unmerged r)) (str " · unmerged " (str/join ", " (:unmerged r))))
          (when (seq (:blocks r)) (str " · blocks " (count (:blocks r))))
          (when-let [ts (seq (:answered-by r))] (str " (answered by " (str/join ", " ts) ")")))}))

(defn report
  "Every problem main recorded (seon.problems/report), by class: each class's count and its
  rows, open first, each by `problem-row`, a long class bounded with the form that reads the
  rest."
  {:malli/schema [:=> [:cat :seon.problems/report :seon.render/env] :seon.render/out]}
  [{:keys [t classes]} env]
  {:seon.render/ai
   (str/join "\n"
     (cons (str "problems recorded on main at t " t (when (every? (comp empty? :rows val) classes) ": none"))
           (for [[c {:keys [n rows]}] (sort-by (comp str key) classes) :when (seq rows)
                 :let [h (:seon.render/handle env)
                       [shown more] (print/bounded (sort-by #(if (false? (:open? (second %))) 1 0) (map-indexed vector rows)) :seon.config.render.list/rows
                                                   (form (some-> h (#(list 'get-in % [:classes c :rows])))))]]
             (str/join "\n" (concat [(str "# " (ui/kname c) " " n)]
                                     (for [[i r] shown] (str "- " (:seon.render/ai (problem-row (assoc r :class c) {:seon.render/handle (some-> h (#(list 'get-in % [:classes c :rows i])))}))
                                                             (when (false? (:open? r)) " (closed)")))
                                     (some-> more vector))))))})

(defn items
  "An evaluation's reply items as the REPL's own text, what an outside model reads from
  seon_eval: each form, what it wrote, and its answer (=> its printed value and its result
  handle, or x its error's render), never the items' map printed as data."
  {:malli/schema [:=> [:cat [:sequential :map]] :string]}
  [its]
  (str/join "\n\n" (for [{:keys [form out printed tag handle]} its]
                      (str form "\n" (when (seq out) (str out (when-not (str/ends-with? out "\n") "\n")))
                           (if (= :err tag) "x " "=> ") printed (some->> handle ui/kname (str "\n;; result/"))))))

(defn- counts {:malli/schema [:=> [:cat :map] :string]}
  [freq] (str/join " " (for [[k n] (sort-by (comp str key) freq)] (str (ui/kname k) " " n))))

(defn problems
  "What is wrong now, most urgent first: root's procs' defects (what flows: no proc is
  pinged), then the problems main recorded (seon.problems/report, by class): one producer,
  so the banner and root's context cannot disagree. An agent's death, red run or refused
  merge is recorded by root (seon.root/set-agent, end-agent), never re-derived here."
  {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [root-errors root-errors-total recorded]}]
  (let [n-errs (or root-errors-total (count root-errors))
        [shown more] (print/bounded (reverse (map-indexed vector root-errors)) :seon.config.render.list/top "(:root-errors (seon.root/state sys)) holds the newest 20" n-errs)
        errs (concat
               (for [[i e] shown]
                 (str "defect in " (ui/kname (:pid e)) ": " (print/line (:message e) (line-budget) (list :message (list 'nth '(:root-errors (seon.root/state sys)) i)))))
               (some-> more vector))
        ;; what main recorded (seon.problems/report): one line per class with open problems,
        ;; its count and its newest or commonest open row, by the row's render
        rec (for [[c {:keys [n rows]}] (sort-by (comp str key) (:classes recorded))
                  :let [i (first (keep-indexed (fn [i r] (when-not (false? (:open? r)) i)) rows))]
                  :when i
                  :let [open (count (remove #(false? (:open? %)) rows))
                        where (list 'get-in '(my.root/problems sys) [:classes c :rows])]]
              (str "recorded " (:seon.render/ai (problem-row (assoc (nth rows i) :class c :n (if (:signature (nth rows i)) n open))
                                                             {:seon.render/handle (list 'get-in '(my.root/problems sys) [:classes c :rows i])}))
                   (some->> (print/more open 1 (str "open, all in " (form where))) (str " · "))))]
    (vec (concat errs rec))))

(defn- section-work {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [tasks attempts task-rows]}]
  (if (empty? tasks)
    ["no tasks on main"]
    (let [running (sort-by :seon.attempt/id (filter #(= :running (:seon.attempt/status %)) (vals attempts)))
          ended-all (sort-by (comp - #(or (:seon.attempt/end-ms %) 0)) (filter :seon.attempt/outcome (vals attempts)))
          src '(seon.task/attempts (deref (:conn sys)))
          [running more-running] (print/bounded running :seon.config.render.list/rows (str "running, all in " (form src)))
          [ended more-ended] (print/bounded ended-all :seon.config.render.list/brief (str "ended, all in " (form src)))
          line #(:seon.render/ai (attempt % {:seon.render/handle (list 'get src (:seon.attempt/id %))}))]
      (vec (concat
             ;; what happened to each task (observed), not its desired execution
             [(str (count tasks) " tasks (" (counts (frequencies (map :state (vals task-rows)))) "); "
                   (count attempts) " attempts (" (counts (frequencies (map :seon.attempt/status (vals attempts)))) ")")]
             ;; blocked tasks by what blocks them (ui/waiting-on): each blocker once, with how
             ;; many it holds up (the root cause first), not one line per blocked task
             (when-let [w (seq (ui/waiting-on task-rows))]
               [(str (count (filter (comp seq :blocked-by val) task-rows)) " blocked, waiting on: "
                     (str/join ", " (for [[b n] w] (str b " (" n ")"))))])
             (map line running)
             (some-> more-running vector)
             (map line ended)
             (some-> more-ended vector))))))

(defn- section-load
  "The agents by how each is (the page's words: ui/shown-status, ui/ended-as), then the
  load tiles the page shows (ui/load-tiles), each as one line."
  {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [agents agent-attempt writer server main]}]
  (into [(str (count agents) " agents (" (counts (frequencies (for [[id a] agents] (ui/shown-status (ui/ended-as a id (or agent-attempt {})))))) ")")]
        (for [{:keys [kicker pill big unit rows]} (ui/load-tiles writer server main {})]
          (str kicker (when pill (str " " (second pill))) " " big " " unit (str/join (for [[dt dd] rows] (str ", " dt " " dd)))))))

(defn- section-costly {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [agents]}]
  (let [fns (for [[_ a] agents e (take-last (config/value :seon.config.render.list/top) (:evals a)) f (:fns e)] f)
        by (reduce (fn [m {:keys [var calls ms]}] (-> m (update-in [var :calls] (fnil + 0) calls) (update-in [var :ms] (fnil + 0.0) (or ms 0.0))))
                   {} fns)
        [shown more] (print/bounded (sort-by (comp - :ms val) by) :seon.config.render.list/brief "functions, each evaluation's on its agent's page")]
    (vec (concat (for [[v {:keys [calls ms]}] shown] (str v " " calls " calls " (ui/ms-str ms)))
                 (some-> more vector)))))

(defn- section-events {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [events events-seen]}]
  (let [[shown more] (print/bounded events :seon.config.render.list/rows "on the dashboard's event stream")]
   (vec (concat
         (for [e shown]
           (str (ui/clock (:at-ms e)) " " (ui/event-kind e) " " (if (:agent e) (ui/kname (:agent e)) "root") " " (ui/event-detail e)))
         (some-> more vector)
         (when (> (or events-seen 0) (count events))
           [(str (- events-seen (count events)) " older events since boot are not kept")])))))

(defn system
  "Root's context: the system as a budgeted text, problems first (see the namespace doc).
  env :seon.render/budget tokens (default :seon.config.render/root-context-budget)."
  {:malli/schema [:=> [:cat :seon.render/system :seon.render/env] :seon.render/out]}
  [sys env]
  (let [budget (or (:seon.render/budget env) (config/value :seon.config.render/root-context-budget))
        problems (problems sys)
        sections [["problems" (if (seq problems) problems [(str "none: no defect in root's " (count (:health sys)) " procs, no problem recorded on main")])]
                  ["work" (section-work sys)]
                  ["load" (section-load sys)]
                  ["costliest functions in the newest evaluations" (section-costly sys)]
                  ["newest events" (section-events sys)]]
        ;; no clock in the text: it would change every pass with nothing else changed (the
        ;; reader knows when it asked)
        head "seon root: the whole system, every number measured, problems first"
        [text left] (reduce (fn [[text left] [title lines]]
                              (let [part (str "\n# " title "\n" (str/join "\n" (map #(str "- " %) lines)))]
                                (if (and (empty? left) (<= (select/estimate (str text part)) budget))
                                  [(str text part) left]
                                  [text (conj left title)])))
                            [head []] (remove (comp empty? second) sections))]
    {:seon.render/ai (cond-> text (seq left) (str "\n(not shown, over the budget: " (str/join ", " left) ")"))}))
