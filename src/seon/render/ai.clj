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
  few lines that cite what they show and the form that reads the rest (opts
  :handle, the value's result handle or a form that reads it): an error map
  (`error`), a task (`task`), an attempt (`attempt`) and a problem row (`problem-row`, the
  line root's context and the page's banner lead with)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [seon.config :as config] [seon.print :as print] [seon.print.select :as select] [seon.render.ui :as ui]))

(defn- line-budget {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/line-budget))
(defn- part-budget {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/part-budget))

(defn- read-of
  "The form that reads part k of the value opts shows (k applied to its handle, or get-in with
  a path), nil without one."
  {:malli/schema [:=> [:cat :seon.render/opts :any] :any]}
  [opts k] (when-let [h (:handle opts)] (if (vector? k) (list 'get-in h k) (list k h))))

(defn- form
  "A form the reader evaluates to read more, printed by the one printer."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [f] (some-> f (print/text (config/value :seon.config.render/form-budget))))

;; A render's parts are [text cut?]: the text, and whether it cut (its cut names the form that
;; reads the rest when the render has a handle). `out` joins them and says whether any cut.

(defn- prose-part
  "Text s as prose (one line when one-line?) under budget, its cut reading through source."
  {:malli/schema [:=> [:cat [:maybe :string] pos-int? :any :boolean] [:tuple :string :boolean]]}
  [s budget source one-line?]
  (let [r (print/prose s budget (cond-> {:one-line? one-line?} source (assoc :source source)))] [(:text r) (:truncated? r)]))

(defn- value-part
  "Value v by the one printer under budget, its cuts reading through handle (a form that reads v)."
  {:malli/schema [:=> [:cat :any pos-int? :any] [:tuple :string :boolean]]}
  [v budget handle]
  (let [r (print/print-value v {:handle handle :budget budget})] [(or (:text r) (:seon.error/message r)) (boolean (:truncated? r))]))

(defn- whole
  "The closing part, when opts names a handle: the form that shows the whole value as data
  (without the metadata that names its render, the printer prints it)."
  {:malli/schema [:=> [:cat :seon.render/opts] [:maybe [:tuple :string :boolean]]]}
  [opts] (when-let [h (:handle opts)] [(str "the whole value as data: " (form (list 'with-meta h nil))) false]))

(defn- out
  "The render's parts (nil ones dropped) joined by sep, and whether any cut."
  {:malli/schema [:=> [:cat :string [:sequential :any]] :seon.render/text]}
  [sep parts]
  (let [ps (remove nil? parts)] {:text (str/join sep (map first ps)) :truncated? (boolean (some second ps))}))

(defn- cat-parts
  "Parts as one part: their texts joined, cut when any cut."
  {:malli/schema [:=> [:cat [:sequential :any]] [:tuple :string :boolean]]}
  [parts] (let [ps (remove nil? parts)] [(str/join (map first ps)) (boolean (some second ps))]))

(defn- names-part
  "A list of names shown in part (seon.print/bounded under bound k), the rest named with the
  form that holds them all."
  {:malli/schema [:=> [:cat [:maybe [:sequential :any]] :keyword :any] [:tuple :string :boolean]]}
  [xs k where]
  (let [[shown more] (print/bounded (vec xs) k (form where))] [(str (str/join ", " shown) (some->> more (str " · "))) (some? more)]))

(defn error
  "An error map (:seon.error/base and every variant): its message first, as a REPL answers,
  the fix when it carries one, each refused part (malli's path, what it expected, the
  value), then its type, where it was raised and every other key it carries, and the form
  that reads the whole."
  {:malli/schema [:=> [:cat :seon.error/base :seon.render/opts] :seon.render/text]}
  [e opts]
  (let [h (:handle opts)
        [ps more] (print/bounded (:seon.error/problems e) :seon.config.render.list/top (form (read-of opts :seon.error/problems)))
        own (apply dissoc e ui/error-shown)]
    (out "\n"
      (concat [(prose-part (:seon.error/message e) (part-budget) (read-of opts :seon.error/message) false)]
              (when (:seon.error/fix e) [[(str "fix: " (:seon.error/fix e)) false]])
              (for [[i {:keys [in expected value]}] (map-indexed vector ps)]
                (cat-parts [["at " false] (value-part in (part-budget) nil) [" expected " false]
                            (value-part expected (part-budget) (read-of opts [:seon.error/problems i :expected])) [(str ", got " value) false]]))
              (when more [[more true]])
              [(cat-parts [[(str "(" (ui/kname (:seon.error/type e)) " in " (:seon.error/operation e)) false]
                           (when (seq own) (cat-parts [[" " false] (value-part own (part-budget) h)]))
                           [")" false]])]
              [(whole opts)]))))

(def ^:private task-shown
  "The keys `task`'s lines show; every other key of the task is printed after them."
  [:seon.task/id :seon.task/kind :seon.task/execution :seon.task/needs :seon.task/branch :seon.task/model :seon.task/problem
   :seon.task/failure :seon.task/stuck :seon.task/accumulated :seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/evidence])

(defn task
  "A task as an agent or root reads it: its id, kind, desired execution, needs, branch and
  model, what is wrong with it (its problem, failure and budget-spent fact), its plan
  (accumulated along its needs) as prose whose cut names the form that reads the rest, its
  instructions, proofs and evidence, every other key it carries, and the form that reads
  the whole."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :seon.render/text]}
  [t opts]
  (let [h (:handle opts)
        acc (:seon.task/accumulated t)
        plan (or (:plan acc) (:seon.task/plan t))
        proofs (sort-by str (or (:proofs acc) (:seon.task/proofs t)))
        {:keys [reason counterexample]} (:seon.task/failure t)
        {:keys [cause blocks]} (:seon.task/stuck t)
        own (apply dissoc t task-shown)
        line #(prose-part %1 (line-budget) %2 true)]
    (out "\n"
      [[(str "task " (:seon.task/id t) " · " (ui/kname (:seon.task/kind t)) " · " (ui/kname (:seon.task/execution t))
             (when (seq (:seon.task/needs t)) (str " · needs " (str/join ", " (sort (:seon.task/needs t)))))
             (some->> (:seon.task/branch t) ui/kname (str " · branch ")) (some->> (:seon.task/model t) (str " · model "))) false]
       (when (:seon.task/problem t) (cat-parts [["problem: " false] (line (:seon.task/problem t) (read-of opts :seon.task/problem))]))
       (when reason (cat-parts [["failed: " false] (line reason (read-of opts [:seon.task/failure :reason]))
                                (when counterexample (cat-parts [[" · counterexample " false] (line counterexample (read-of opts [:seon.task/failure :counterexample]))]))]))
       (when cause (cat-parts [["budget spent: " false] (line cause (read-of opts [:seon.task/stuck :cause]))
                               (when (seq blocks) (cat-parts [[" · blocks " false] (names-part (sort blocks) :seon.config.render.list/rows (read-of opts [:seon.task/stuck :blocks]))]))]))
       (when plan (cat-parts [["plan: " false] (prose-part plan (or (:budget opts) (part-budget))
                                                           (read-of opts (if acc [:seon.task/accumulated :plan] :seon.task/plan)) false)]))
       (when (:seon.task/instructions t) (cat-parts [["instructions: " false] (line (:seon.task/instructions t) (read-of opts :seon.task/instructions))]))
       (when (seq proofs) (cat-parts [[(str (count proofs) (if (= 1 (count proofs)) " proof: " " proofs: ")) false]
                                      (names-part proofs :seon.config.render.list/rows (read-of opts (if acc [:seon.task/accumulated :proofs] :seon.task/proofs)))]))
       (when (:seon.task/evidence t) (cat-parts [["evidence: " false] (value-part (:seon.task/evidence t) (part-budget) (read-of opts :seon.task/evidence))]))
       (when (seq own) (cat-parts [["also: " false] (value-part own (part-budget) h)]))
       (whole opts)])))

(defn attempt
  "An attempt in one line: running (its agent, turn and tokens) or ended (its outcome and
  reason, whose cut names the form that reads it), and how many of each its end carried
  (conflicts, unmerged entries, what it changed, contract gaps) with the form that reads
  them. Root's context's work lines and the REPL."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :seon.render/text]}
  [a opts]
  (let [{:seon.attempt/keys [id agent status outcome reason turns tokens]} a
        n (fn [k word] (when-let [xs (seq (get a k))]
                         [(str " · " (count xs) " " word (some->> (read-of opts k) form (str " in "))) true]))]
    (out ""
      [(if outcome
         (cat-parts [[(str "ended " id " " (ui/kname outcome)) false]
                     (when reason (cat-parts [[": " false] (prose-part reason (line-budget) (read-of opts :seon.attempt/reason) true)]))])
         [(str (ui/kname status) " " id (some->> agent ui/kname (str " on ")) (some->> turns (str ", turn ")) (some-> tokens (str " tokens") (->> (str ", ")))) false])
       (n :seon.attempt/conflicts "conflicts") (n :seon.attempt/unmerged "unmerged") (n :seon.attempt/changed "changed")
       (n :seon.attempt/uncontracted "without a contract")])))

(defn- class-word
  "A problem class as its line says it: the stored class :stuck reads as budget spent (owner's
  ruling, 2026-10-22; :blocked is a task waiting on its needs)."
  {:malli/schema [:=> [:cat [:maybe :keyword]] :string]}
  [c] (if (= :stuck c) "budget spent" (ui/kname c)))

(defn problem-row
  "One problem row (seon.problems/report), :class (and its class's open count :n) assoc'd by
  its reader, as one line: what it is about (task, attempt, test, attribute), then why: the
  recorded message, the error's message, the failure's reason, the blocked cause, or a task
  conflict's three sides; a merge row's conflicts and unmerged keys; the tasks it blocks;
  its outcome; and what answered it. Each cut reads its rest through opts's handle."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :seon.render/text]}
  [r opts]
  (let [ev (:evidence r)
        why (some (fn [[k v]] (when v (prose-part v (line-budget) (read-of opts k) true)))
                  [[:message (:message r)] [[:error :seon.error/message] (get-in r [:error :seon.error/message])]
                   [:reason (:reason r)] [:cause (:cause r)]])
        why (or why
                (when (= :task-conflict (:class r)) (value-part (select-keys ev [:base :ours :theirs]) (line-budget) (read-of opts :evidence)))
                (some-> (:outcome r) ui/kname (vector false)))
        what (remove nil? [(:task r) (:attempt r) (some-> (:test r) str) (some-> (:attr r) ui/kname)])]
    (out ""
      [[(str (class-word (:class r)) (if-let [n (:n r)] (str " " n) (some->> (:count r) (str " ×"))) (when (seq what) (str " " (str/join " " what)))) false]
       (when why (cat-parts [[": " false] why]))
       (when (some-> (:conflicts r) pos?) [(str " · " (:conflicts r) " conflicts" (some->> (read-of opts [:evidence :conflicts]) form (str " in "))) true])
       (when (seq (:unmerged r)) [(str " · unmerged " (str/join ", " (:unmerged r))) false])
       (when (seq (:blocks r)) (cat-parts [[" · blocks " false] (names-part (sort (:blocks r)) :seon.config.render.list/top (read-of opts :blocks))]))
       (when-let [ts (seq (:answered-by r))] [(str " (answered by " (str/join ", " ts) ")") false])])))

(defn report
  "Every problem main recorded (seon.problems/report), by class: each class's count and its
  rows, open first, each by `problem-row`, a long class bounded with the form that reads the
  rest, and the form that reads the whole."
  {:malli/schema [:=> [:cat :seon.problems/report :seon.render/opts] :seon.render/text]}
  [{:keys [t classes]} opts]
  (out "\n"
    (concat
      [[(str "problems recorded on main at t " t (when (every? (comp empty? :rows val) classes) ": none")) false]]
      (for [[c {:keys [n rows]}] (sort-by (comp str key) classes) :when (seq rows)
            :let [[shown more] (print/bounded (sort-by #(if (false? (:open? (second %))) 1 0) (map-indexed vector rows)) :seon.config.render.list/rows
                                              (form (read-of opts [:classes c :rows])))
                  lines (for [[i r] shown]
                          (let [o (problem-row (assoc r :class c) {:handle (read-of opts [:classes c :rows i])})]
                            [(str "- " (:text o) (when (false? (:open? r)) " (closed)")) (:truncated? o)]))]]
        [(str/join "\n" (concat [(str "# " (class-word c) " " n)] (map first lines) (some-> more vector)))
         (boolean (or more (some second lines)))])
      [(whole opts)])))

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
              (str "recorded " (:text (problem-row (assoc (nth rows i) :class c :n (if (:signature (nth rows i)) n open))
                                                             {:handle (list 'get-in '(my.root/problems sys) [:classes c :rows i])}))
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
          line #(:text (attempt % {:handle (list 'get src (:seon.attempt/id %))}))]
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
  [{:keys [agents agent-attempt writer fork-server main]}]
  (into [(str (count agents) " agents (" (counts (frequencies (for [[id a] agents] (ui/shown-status (ui/ended-as a id (or agent-attempt {})))))) ")")]
        (for [{:keys [kicker pill big unit rows]} (ui/load-tiles writer fork-server main {})]
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
  opts :budget tokens (default :seon.config.render/root-context-budget)."
  {:malli/schema [:=> [:cat :seon.render/system :seon.render/opts] :seon.render/text]}
  [sys opts]
  (let [budget (or (:budget opts) (config/value :seon.config.render/root-context-budget))
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
    {:text (cond-> text (seq left) (str "\n(not shown, over the budget: " (str/join ", " left) ")"))}))
