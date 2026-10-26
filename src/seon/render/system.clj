(ns seon.render.system
  "Root's context: the whole system (:seon.render/system) as one value root reads before it acts,
  printed by the one printer (seon.render.proc). What is wrong comes first (root's defects, then
  what main recorded: dead agents, red tests, refused merges among them), then the work (tasks
  and runs), then the load (writer, fork server, main), then what is costly (the functions the
  newest evaluations spent most in), then the newest events. The value is ordered (array maps,
  what matters first) and the printer cuts it once at the reader's budget, saying what it left."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [seon.config :as config] [seon.render.ui :as ui]))

;; ---- a render is a value chosen and ordered, printed once ---------------------------------

(defn- ordered
  "An array map of kvs (k v ...) in their order, the pairs whose value is nil or an empty
  collection left out (the printer shows an array map in its own order)."
  {:malli/schema [:=> [:cat [:* :any]] :map]}
  [& kvs] (apply array-map (mapcat identity (remove (fn [[_ v]] (or (nil? v) (and (coll? v) (empty? v)))) (partition 2 kvs)))))

(defn run-data
  "A run as it reads: running (its agent, turn and tokens) or ended (its outcome and reason), and
  what its end carried (conflicts, unmerged entries, what it changed, contract gaps)."
  {:malli/schema [:=> [:cat :map] :map]}
  [a]
  (let [{:seon.run/keys [id agent status outcome reason turns tokens conflicts unmerged changed uncontracted]} a]
    (ordered :run id :status status :agent agent :turn turns :tokens tokens :outcome outcome :reason reason
             :conflicts conflicts :unmerged unmerged :changed changed :without-a-contract uncontracted)))

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
