(ns seon.render.ai
  "The AI renders that earn their place (DECISIONS: an AI render exists only when it
  focuses attention, adds context or filters excess; W1 §1.4).

  `system` is root's context: the whole system (:seon.render/system) as a compact text
  root reads before it acts. The printer of the same value would spend its budget on the
  first agents' maps; this one focuses: what is wrong first (root's defects, then what
  main recorded: dead agents, red tests, refused merges among them), then the work (tasks and runs),
  then the load (writer, fork server, main), then what is costly (the functions the
  newest evaluations spent most in), then the newest events. Sections are added in that
  order until the token budget is spent; what did not fit is named, never silently cut.

  The values agents and root read at the REPL (seon.render/printed) render here too: an error
  map (`error`), a task (`task`), a run (`run`), a problem row (`problem-row`) and main's
  problems (`report`). Each render chooses and orders what to show as a value (its `-data`
  function: an array map, what matters first), and the one printer prints that value once at
  the reader's budget, so nothing is cut before the printer (the owner's rule): a cut is the printer's, and reads its rest through the form that makes the value
  again from opts :handle ((seon.render.ai/task-data result/h3)), the value's result handle or a
  form that reads it."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [seon.config :as config] [seon.print :as print] [seon.render.ui :as ui]))

;; ---- a render is a value chosen and ordered, printed once ---------------------------------

(defn- ordered
  "An array map of kvs (k v ...) in their order, the pairs whose value is nil or an empty
  collection left out (the printer shows an array map in its own order)."
  {:malli/schema [:=> [:cat [:* :any]] :map]}
  [& kvs] (apply array-map (mapcat identity (remove (fn [[_ v]] (or (nil? v) (and (coll? v) (empty? v)))) (partition 2 kvs)))))

(defn- shown
  "Value data (made by data-fn from the value opts :handle reads) printed once by the one
  printer at opts :budget (default the reply's), with, last, the form that shows the whole value
  as data ((with-meta h nil), the printer prints it without its render); a cut reads its rest
  through (data-fn h)."
  {:malli/schema [:=> [:cat :map :symbol :seon.render/opts] :seon.render/text]}
  [data data-fn opts]
  (let [h (:handle opts)
        v (cond-> data h (as-> m (apply array-map (concat (mapcat identity m) [:as-data (list 'with-meta h nil)]))))
        r (print/print-value v {:handle (when h (list data-fn h)) :budget (or (:budget opts) print/default-budget)})]
    {:text (or (:text r) (:seon.error/message r)) :truncated? (boolean (:truncated? r))}))

(defn error-data
  "An error map as it reads: its message first, as a REPL answers, the fix when it carries one,
  where, each refused part (malli's), then its type, where it was raised and every other key."
  {:malli/schema [:=> [:cat :seon.error/base] :map]}
  [e]
  (ordered :message (:seon.error/message e) :fix (:seon.error/fix e) :at (:seon.error/at e) :in (:seon.error/in e)
           :problems (:seon.error/problems e) :type (:seon.error/type e) :operation (:seon.error/operation e)
           :also (not-empty (apply dissoc e (ui/error-hidden e)))))

(defn error
  "An error map (:seon.error/base and every variant) by `error-data`, printed once."
  {:malli/schema [:=> [:cat :seon.error/base :seon.render/opts] :seon.render/text]}
  [e opts] (shown (error-data e) 'seon.render.ai/error-data opts))

(def ^:private task-shown
  "The keys `task-data` orders; every other key of the task follows them."
  [:seon.task/id :seon.task/kind :seon.task/execution :seon.task/needs :seon.task/branch :seon.task/model
   :seon.task/failure :seon.task/accumulated :seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/evidence])

(defn task-data
  "A task as an agent or root reads it: its id, kind, desired execution, needs, branch and
  model, what is wrong with it (its failure, held for root), its plan (accumulated along its
  needs), its instructions, proofs and evidence, then every other key it carries."
  {:malli/schema [:=> [:cat :map] :map]}
  [t]
  (let [acc (:seon.task/accumulated t)]
    (ordered :task (:seon.task/id t) :kind (:seon.task/kind t) :execution (:seon.task/execution t)
             :needs (some-> (:seon.task/needs t) sort vec) :branch (:seon.task/branch t) :model (:seon.task/model t)
             :failure (:seon.task/failure t) :plan (or (:plan acc) (:seon.task/plan t)) :instructions (:seon.task/instructions t)
             :proofs (vec (sort-by str (or (:proofs acc) (:seon.task/proofs t)))) :evidence (:seon.task/evidence t)
             :also (not-empty (apply dissoc t task-shown)))))

(defn task
  "A task by `task-data`, printed once."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :seon.render/text]}
  [t opts] (shown (task-data t) 'seon.render.ai/task-data opts))

(defn run-data
  "A run as it reads: running (its agent, turn and tokens) or ended (its outcome and reason), and
  what its end carried (conflicts, unmerged entries, what it changed, contract gaps)."
  {:malli/schema [:=> [:cat :map] :map]}
  [a]
  (let [{:seon.run/keys [id agent status outcome reason turns tokens conflicts unmerged changed uncontracted]} a]
    (ordered :run id :status status :agent agent :turn turns :tokens tokens :outcome outcome :reason reason
             :conflicts conflicts :unmerged unmerged :changed changed :without-a-contract uncontracted)))

(defn run
  "A run by `run-data`, printed once: root's context's work and the REPL."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :seon.render/text]}
  [a opts] (shown (run-data a) 'seon.render.ai/run-data opts))

(defn problem-data
  "One problem row (seon.problems/report), :class (and its class's open count :n) assoc'd by its
  reader: what it is about (task, run, test, attribute), why (the recorded message, the error's
  message, the failure's reason, a task conflict's three sides, else its outcome), a merge row's
  conflicts and unmerged keys, whether it is closed, and what answered it."
  {:malli/schema [:=> [:cat :map] :map]}
  [r]
  (let [ev (:evidence r)]
    (ordered :class (:class r) :n (:n r) :count (when-not (:n r) (:count r)) :about (vec (ui/problem-about r))
             :why (or (:message r) (get-in r [:error :seon.error/message]) (:reason r)
                      (when (= :task-conflict (:class r)) (select-keys ev [:base :ours :theirs])) (:outcome r))
             :conflicts (when (some-> (:conflicts r) pos?) (:conflicts ev (:conflicts r))) :unmerged (:unmerged r)
             :closed (when (false? (:open? r)) true) :answered-by (:answered-by r))))

(defn problem-row
  "One problem row by `problem-data`, printed once."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :seon.render/text]}
  [r opts] (shown (problem-data r) 'seon.render.ai/problem-data opts))

(defn report-data
  "Every problem main recorded (seon.problems/report): its t, then by class each class's count
  and its rows, open first, each by `problem-data` without its class (the key it is under)."
  {:malli/schema [:=> [:cat :seon.problems/report] :map]}
  [{:keys [t classes]}]
  (ordered :problems-on-main-at-t t
           ;; a row under its class does not say its class again
           :classes (into (sorted-map) (for [[c {:keys [n rows]}] classes :when (seq rows)]
                                         [c {:n n :rows (mapv #(dissoc (problem-data (assoc % :class c)) :class) (sort-by #(if (false? (:open? %)) 1 0) rows))}]))
           :none (when (every? (comp empty? :rows val) classes) true)))

(defn report
  "Main's problems by `report-data`, printed once."
  {:malli/schema [:=> [:cat :seon.problems/report :seon.render/opts] :seon.render/text]}
  [r opts] (shown (report-data r) 'seon.render.ai/report-data opts))

;; ---- root's context: the system as one value --------------------------------------------

(defn- counts {:malli/schema [:=> [:cat :map] :map]}
  [freq] (into (sorted-map-by #(compare (str %1) (str %2))) freq))

(def ^:private derived
  "The classes main's facts make true when read, never recorded (:seon.problems/class declares them)."
  (:seon.problems/derived (contract/properties :seon.problems/class)))

(def ^:private areas
  "Each problem class's area, in the order shown (:seon.problems/class declares them)."
  (:seon.problems/areas (contract/properties :seon.problems/class)))

(defn problems
  "What is wrong now, most urgent first, as root's context leads with it: root's procs' defects
  (what flows, no proc is pinged), newest first, then main's open problems (seon.problems/health,
  recorded or derived) by the area :seon.problems/class declares for each class: per class with
  open problems, how many and its newest or commonest open row. An agent's death, red run or
  refused merge is recorded by root (seon.root/set-agent, end-agent), never re-derived here.
  Answers [[area [entry]]], areas with nothing left out."
  {:malli/schema [:=> [:cat :seon.render/system] [:vector [:tuple :keyword [:vector :map]]]]}
  [{:keys [root-errors root-errors-total recorded]}]
  (let [errs (vec (for [e (reverse root-errors)] (ordered :defect-in (:pid e) :message (:message e))))
        errs (cond-> errs (> (or root-errors-total 0) (count errs))
               (conj {:older (- root-errors-total (count errs)) :root-holds-the-newest (config/value :seon.config.root/view-window)
                      :all-in '(:root-errors (seon.root/state sys))}))
        rec (for [[c {:keys [n rows]}] (sort-by (comp str key) (:classes recorded))
                  :let [i (first (keep-indexed (fn [i r] (when-not (false? (:open? r)) i)) rows))]
                  :when i
                  :let [open (count (remove #(false? (:open? %)) rows))]]
              [c (ordered :problem (problem-data (assoc (nth rows i) :class c :n (if (:signature (nth rows i)) n open)))
                          :how (if (derived c) :derived :recorded) :open open
                          :all-in (list 'get-in '(my.root/problems sys) [:classes c :rows]))])
        in (fn [cs] (vec (keep (fn [[c l]] (when (contains? cs c) l)) rec)))]
    (vec (remove (comp empty? second) (into [[:root errs]] (for [[area cs] areas] [area (in cs)]))))))

(defn- section-work {:malli/schema [:=> [:cat :seon.render/system] :map]}
  [{:keys [tasks runs task-rows]}]
  (if (empty? tasks)
    {:tasks 0}
    (ordered :tasks (count tasks) :by-state (counts (frequencies (map :state (vals task-rows))))
             :runs (count runs) :by-status (counts (frequencies (map :seon.run/status (vals runs))))
             ;; blocked tasks by what blocks them (ui/waiting-on): each blocker once, with how many
             ;; it holds up (the root cause first), not one entry per blocked task
             :waiting-on (vec (ui/waiting-on task-rows))
             :running (mapv run-data (sort-by :seon.run/id (filter #(= :running (:seon.run/status %)) (vals runs))))
             :ended (mapv run-data (sort-by (comp - #(or (:seon.run/end-ms %) 0)) (filter :seon.run/outcome (vals runs))))
             :all-in '(seon.task/runs (deref (:conn sys))))))

(defn- section-load
  "The agents by how each is (the page's words: ui/shown-status, ui/ended-as), then the load
  tiles the page shows (ui/load-tiles)."
  {:malli/schema [:=> [:cat :seon.render/system] :map]}
  [{:keys [agents agent-run writer fork-server main]}]
  (ordered :agents (count agents) :by-status (counts (frequencies (for [[id a] agents] (ui/shown-status (ui/ended-as a id (or agent-run {}))))))
           :tiles (vec (for [{:keys [kicker pill big unit rows]} (ui/load-tiles writer fork-server main)]
                         (str kicker (when pill (str " " (second pill))) " " big " " unit (str/join (for [[dt dd] rows] (str ", " dt " " dd))))))))

(defn- section-costly
  "The functions the evaluations root's view holds (its newest of each agent, :seon.config.root/
  view-window) spent most in, costliest first."
  {:malli/schema [:=> [:cat :seon.render/system] [:vector :map]]}
  [{:keys [agents]}]
  (let [by (reduce (fn [m {:keys [var calls ms]}] (-> m (update-in [var :calls] (fnil + 0) calls) (update-in [var :ms] (fnil + 0.0) (or ms 0.0))))
                   {} (for [[_ a] agents e (:evals a) f (:fns e)] f))]
    (vec (for [[v {:keys [calls ms]}] (sort-by (comp - :ms val) by)] {:var v :calls calls :ms (ui/ms-str ms)}))))

(defn- section-events
  "The events root's view keeps, newest first, and how many older since boot it did not keep."
  {:malli/schema [:=> [:cat :seon.render/system] [:vector :any]]}
  [{:keys [events events-seen]}]
  (cond-> (vec (for [e events] (ordered :at (ui/clock (:at-ms e)) :kind (ui/event-kind e) :agent (or (:agent e) :root)
                                        :event (not-empty (dissoc e :at-ms :type :agent :seq)))))
    (> (or events-seen 0) (count events)) (conj {:older-not-kept (- events-seen (count events))})))

(defn system-data
  "Root's context as one value, what is wrong first: the problems, the work, the load, the
  costliest functions, the newest events (no clock: it would change with nothing else changed;
  the reader knows when it asked)."
  {:malli/schema [:=> [:cat :seon.render/system] :map]}
  [sys]
  (let [ps (problems sys)]
    (ordered :seon-root "the whole system, every number measured, problems first"
             :problems (if (seq ps) (apply array-map (mapcat identity ps)) (str "none: no defect in root's " (count (:health sys)) " procs, no problem recorded on main"))
             :work (section-work sys) :load (section-load sys)
             :costliest-functions (section-costly sys) :newest-events (section-events sys))))

(defn system
  "Root's context: `system-data` printed once by the one printer, breadth first, at opts :budget
  (default :seon.config.render/root-context-budget); a cut is the printer's and says what it left."
  {:malli/schema [:=> [:cat :seon.render/system :seon.render/opts] :seon.render/text]}
  [sys opts]
  (let [r (print/print-value (system-data sys) {:budget (or (:budget opts) (config/value :seon.config.render/root-context-budget))})]
    {:text (or (:text r) (:seon.error/message r)) :truncated? (boolean (:truncated? r))}))
