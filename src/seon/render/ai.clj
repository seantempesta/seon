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

  `cost-report` puts a cost proof's red dimension first, with the sizes that broke it."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [seon.config :as config] [seon.print :as print] [seon.print.select :as select] [seon.render.ui :as ui]))

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
                 (str "defect in " (ui/kname (:pid e)) ": " (print/line (:message e) 50 (list :message (list 'nth '(:root-errors (seon.root/state sys)) i)))))
               (some-> more vector))
        ;; what main recorded (seon.problems/report): one line per class with open problems,
        ;; its count and its newest or commonest example
        rec (for [[c {:keys [n rows]}] (sort-by (comp str key) (:classes recorded))
                  :let [i (first (keep-indexed (fn [i r] (when-not (false? (:open? r)) i)) rows))]
                  :when i
                  :let [r (nth rows i) open (count (remove #(false? (:open? %)) rows))]]
              (str "recorded " (ui/kname c) " " (if (:signature r) n open) ": "
                   (if (:message r)
                     (print/line (:message r) 40 (list 'get-in '(my.root/problems sys) [:classes c :rows i :message]))
                     (print/line (or (some->> [(:attempt r) (:test r) (:reason r) (some-> (:outcome r) ui/kname)] (remove nil?) seq (str/join " "))
                               (pr-str (select-keys r [:producer :owner-ns])))
                           40))
                   (when-let [ts (seq (:answered-by r))] (str " (answered by " (str/join ", " ts) ")"))))]
    (vec (concat errs rec))))

(defn- section-work {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [tasks attempts task-rows]}]
  (if (empty? tasks)
    ["no tasks on main"]
    (let [running (sort-by :seon.attempt/id (filter #(= :running (:seon.attempt/status %)) (vals attempts)))
          ended-all (sort-by (comp - #(or (:seon.attempt/end-ms %) 0)) (filter :seon.attempt/outcome (vals attempts)))
          src '(seon.task/attempts (deref (:conn sys)))
          [running more-running] (print/bounded running :seon.config.render.list/rows (str "running, all in " (pr-str src)))
          [ended more-ended] (print/bounded ended-all :seon.config.render.list/brief (str "ended, all in " (pr-str src)))]
      (vec (concat
             ;; what happened to each task (observed), not its desired execution
             [(str (count tasks) " tasks (" (counts (frequencies (map :state (vals task-rows)))) "); "
                   (count attempts) " attempts (" (counts (frequencies (map :seon.attempt/status (vals attempts)))) ")")]
             ;; blocked tasks by what blocks them (ui/waiting-on): each blocker once, with how
             ;; many it holds up (the root cause first), not one line per blocked task
             (when-let [w (seq (ui/waiting-on task-rows))]
               [(str (count (filter (comp seq :blocked-by val) task-rows)) " blocked, waiting on: "
                     (str/join ", " (for [[b n] w] (str b " (" n ")"))))])
             (for [a running]
               (str "running " (:seon.attempt/id a) " on " (ui/kname (:seon.attempt/agent a)) ", turn " (or (:seon.attempt/turns a) 0)
                    ", " (or (:seon.attempt/tokens a) 0) " tokens"))
             (some-> more-running vector)
             (for [a ended]
               (str "ended " (:seon.attempt/id a) " " (ui/kname (:seon.attempt/outcome a))
                    (when (:seon.attempt/reason a)
                      (str ": " (print/line (:seon.attempt/reason a) 45 (list :seon.attempt/reason (list 'get src (:seon.attempt/id a))))))))
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

(defn cost-report
  "A cost proof's report, its red dimension first (ui/verdict-lines, as the page says it),
  then the rows."
  {:malli/schema [:=> [:cat :seon.render/cost-report :seon.render/env] :seon.render/out]}
  [{:keys [rows verdict]} _]
  {:seon.render/ai
   (str/join "\n"
     (concat (map second (ui/verdict-lines verdict))
             (for [{:keys [n measured expected]} rows]
               (str "n=" n " " (str/join " " (for [[d x] expected] (str (name d) " " (get measured d) "/" x)))))))})
