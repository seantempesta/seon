(ns seon.render.ai
  "The AI renders that earn their place (DECISIONS: an AI render exists only when it
  focuses attention, adds context or filters excess; W1 §1.4).

  `system` is root's context: the whole system (:seon.render/system) as a compact text
  root reads before it acts. The printer of the same value would spend its budget on the
  first agents' maps; this one focuses: what is wrong first (procs not running, root's
  defects, dead agents, red tests, refused merges), then the work (tasks and attempts),
  then the load (writer, fork server, main), then what is costly (the functions the
  newest evaluations spent most in), then the newest events. Sections are added in that
  order until the token budget is spent; what did not fit is named, never silently cut.

  `cost-report` puts a cost proof's red dimension first, with the sizes that broke it."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [seon.print :as print] [seon.print.select :as select] [seon.render.ui :as ui]))

(defn- line
  "Text in one line of root's context: the printer's prose under budget tokens, whitespace
  runs as one space; its cut says what it omitted and, with source (a form root evaluates
  that answers the whole text), the form that reads the rest."
  {:malli/schema [:function [:=> [:cat :any pos-int?] :string] [:=> [:cat :any pos-int? :any] :string]]}
  ([s budget] (line s budget nil))
  ([s budget source] (:text (print/prose (str s) budget (cond-> {:one-line? true} source (assoc :source source))))))

(defn- more-line
  "The line a bounded list ends with when it left some: how many more, and the form that
  reads them all; nil when it left none."
  {:malli/schema [:=> [:cat nat-int? nat-int? :string] [:maybe :string]]}
  [total shown where] (print/more total shown where))

(defn tokens
  "A text's tokens, by the printer's own sizer (seon.print.select/estimate)."
  {:malli/schema [:=> [:cat :string] nat-int?]}
  [s] (select/estimate s))

(defn- kn {:malli/schema [:=> [:cat :any] :string]} [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- counts {:malli/schema [:=> [:cat :map] :string]}
  [freq] (str/join " " (for [[k n] (sort-by (comp str key) freq)] (str (kn k) " " n))))

(defn problems
  "What is wrong now, most urgent first: root's procs' defects (what flows: no proc is
  pinged), the problems main recorded (seon.problems/report, by class), live agents with
  red tests or a refused merge, agents whose process died. An
  agent root stopped after its attempt finished is not a problem."
  {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [root-errors root-errors-total agents agent-attempt recorded]}]
  (let [n-errs (or root-errors-total (count root-errors))
        errs (concat
               (for [[i e] (reverse (take-last 3 (map-indexed vector root-errors)))]
                 (str "defect in " (kn (:pid e)) ": " (line (:message e) 50 (list :message (list 'nth '(:root-errors (seon.root/state sys)) i)))))
               (some-> (more-line n-errs (min 3 (count root-errors)) "(:root-errors (seon.root/state sys)) holds the newest 20") vector))
        ;; a live agent's red run or refused merge, or an agent whose process died; an agent
        ;; root stopped (an attempt that ended) is not a problem, whatever its last run said
        ;; one pass over the agents; only the few with a problem are sorted
        ags (for [[id a] (sort-by (comp str key) (filter (fn [[_ a]] (or (:error a) (:tests a) (:merged a) (= :dead (:status a)))) agents))
                  :let [t (:tests a) m (:merged a) live? (not (#{:killed :ended :dead} (:status a)))
                        finished? (#{:done :merged} (:seon.attempt/status (get agent-attempt id)))]
                  :when (or (and (= :dead (:status a)) (not finished?)) (:error a)
                            (and live? (or (pos? (+ (or (:fail t) 0) (or (:error t) 0))) (and m (false? (:merged? m))))))]
              (str (kn id) " " (kn (:status a)) ": "
                   (str/join "; " (remove nil? [(when (= :dead (:status a)) "its process died")
                                                (when (:error a) (line (:error a) 35 (list :error (list 'get '(:agents (seon.root/state sys)) id))))
                                                (when (pos? (+ (or (:fail t) 0) (or (:error t) 0)))
                                                  (str "tests " (or (:pass t) 0) " pass " (or (:fail t) 0) " fail " (or (:error t) 0) " error"
                                                       (when-let [r (first (remove #(= :pass (:type %)) (:reports t)))]
                                                         (str " (" (:var r) ")"))))
                                                (when (and m (false? (:merged? m)))
                                                  (str "merge refused" (when (seq (:conflicts m)) (str ", " (count (:conflicts m)) " conflicts"))))]))))
        ;; what main recorded (seon.problems/report): one line per class with open problems,
        ;; its count and its newest or commonest example
        rec (for [[c {:keys [n rows]}] (sort-by (comp str key) (:classes recorded))
                  :let [i (first (keep-indexed (fn [i r] (when-not (false? (:open? r)) i)) rows))]
                  :when i
                  :let [r (nth rows i) open (count (remove #(false? (:open? %)) rows))]]
              (str "recorded " (kn c) " " (if (:signature r) n open) ": "
                   (if (:message r)
                     (line (:message r) 40 (list 'get-in '(my.root/problems sys) [:classes c :rows i :message]))
                     (line (or (some->> [(:attempt r) (:test r) (:reason r) (some-> (:outcome r) kn)] (remove nil?) seq (str/join " "))
                               (pr-str (select-keys r [:producer :owner-ns])))
                           40))
                   (when-let [ts (seq (:answered-by r))] (str " (answered by " (str/join ", " ts) ")"))))]
    (vec (concat errs rec ags))))

(defn- section-work {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [tasks attempts task-rows]}]
  (if (empty? tasks)
    ["no tasks on main"]
    (let [running (sort-by :seon.attempt/id (filter #(= :running (:seon.attempt/status %)) (vals attempts)))
          ended-all (sort-by (comp - #(or (:seon.attempt/end-ms %) 0)) (filter :seon.attempt/outcome (vals attempts)))
          ended (take 5 ended-all)
          src '(seon.task/attempts (deref (:conn sys)))]
      (vec (concat
             ;; what happened to each task (observed), not its desired execution
             [(str (count tasks) " tasks (" (counts (frequencies (map :state (vals task-rows)))) "); "
                   (count attempts) " attempts (" (counts (frequencies (map :seon.attempt/status (vals attempts)))) ")")]
             ;; blocked tasks by what blocks them: each blocker once, with how many it holds
             ;; up (the root cause first), not one line per blocked task
             (let [by (frequencies (for [[_ r] task-rows b (:blocked-by r)] b))]
               (when (seq by)
                 [(str (count (filter (comp seq :blocked-by val) task-rows)) " blocked, waiting on: "
                       (str/join ", " (for [[b n] (sort-by (fn [[b n]] [(- n) b]) by)] (str b " (" n ")"))))]))
             (for [a (take 8 running)]
               (str "running " (:seon.attempt/id a) " on " (kn (:seon.attempt/agent a)) ", turn " (or (:seon.attempt/turns a) 0)
                    ", " (or (:seon.attempt/tokens a) 0) " tokens"))
             (remove nil? [(more-line (count running) (min 8 (count running)) (str "running, all in " (pr-str src)))])
             (for [a ended]
               (str "ended " (:seon.attempt/id a) " " (kn (:seon.attempt/outcome a))
                    (when (:seon.attempt/reason a)
                      (str ": " (line (:seon.attempt/reason a) 45 (list :seon.attempt/reason (list 'get src (:seon.attempt/id a))))))))
             (remove nil? [(more-line (count ended-all) (count ended) (str "ended, all in " (pr-str src)))]))))))

(defn- section-load {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [agents agent-attempt writer server main]}]
  ;; how each agent is in the page's words: an agent root stopped after its attempt
  ;; finished ended, it was not killed
  [(str (count agents) " agents (" (counts (frequencies (for [[id a] agents] (ui/shown-status (ui/ended-as a id (or agent-attempt {})))))) ")")
   (str "writer " (:tx-count writer 0) " txs, " (ui/fmt2 (:tx-per-commit writer)) " per commit, " (:flushes writer 0) " flushes, batch p50 " (ui/ms-str (:batch-p50-ms writer)))
   (str "fork server " (kn (:status server)) " pid " (:pid server) ", fork p50 " (ui/ms-str (:fork-ms-p50 server))
        ", " (:agents-forked server 0) " forked, " (:restarts server 0) " restarts")
   (str "main t " (:t main) ", " (:datoms main "?") " datoms, " (:merges main 0) " merges")])

(defn- section-costly {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [agents]}]
  (let [fns (for [[_ a] agents e (take-last 3 (:evals a)) f (:fns e)] f)
        by (reduce (fn [m {:keys [var calls ms]}] (-> m (update-in [var :calls] (fnil + 0) calls) (update-in [var :ms] (fnil + 0.0) (or ms 0.0))))
                   {} fns)]
    (vec (concat (for [[v {:keys [calls ms]}] (take 5 (sort-by (comp - :ms val) by))]
                   (str v " " calls " calls " (ui/ms-str ms)))
                 (remove nil? [(more-line (count by) (min 5 (count by)) "functions, each evaluation's on its agent's page")])))))

(defn- section-events {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [events events-seen]}]
  (vec (concat
         (for [e (take 8 events)]
           (str (ui/clock (:at-ms e)) " " (if (:ended-as e) (str "ended " (kn (:ended-as e))) (kn (:type e))) " " (if (:agent e) (kn (:agent e)) "root")
                (case (:type e)
                  :reply (let [r (last (:items e))] (str " " (line (:form r) 20) " => " (line (:printed r) 20)))
                  :tests (str " " (:pass e 0) "/" (+ (:pass e 0) (:fail e 0) (:error e 0)))
                  :merged (if (get-in e [:result :merged?]) " into main" " refused")
                  "")))
         (remove nil? [(more-line (count events) (min 8 (count events)) "on the dashboard's event stream")])
         (when (> (or events-seen 0) (count events))
           [(str (- events-seen (count events)) " older events since boot are not kept")]))))

(defn system
  "Root's context: the system as a budgeted text, problems first (see the namespace doc).
  env :seon.render/budget tokens (default 700)."
  {:malli/schema [:=> [:cat :seon.render/system :seon.render/env] :seon.render/out]}
  [sys env]
  (let [budget (or (:seon.render/budget env) 700)
        problems (problems sys)
        sections [["problems" (if (seq problems) problems [(str "none: no defect in root's " (count (:health sys)) " procs, no problem recorded on main, no red live agent")])]
                  ["work" (section-work sys)]
                  ["load" (section-load sys)]
                  ["costliest functions in the newest evaluations" (section-costly sys)]
                  ["newest events" (section-events sys)]]
        ;; no clock in the text: it would change every pass with nothing else changed (the
        ;; reader knows when it asked)
        head "seon root: the whole system, every number measured, problems first"
        [text left] (reduce (fn [[text left] [title lines]]
                              (let [part (str "\n# " title "\n" (str/join "\n" (map #(str "- " %) lines)))]
                                (if (and (empty? left) (<= (tokens (str text part)) budget))
                                  [(str text part) left]
                                  [text (conj left title)])))
                            [head []] (remove (comp empty? second) sections))]
    {:seon.render/ai (cond-> text (seq left) (str "\n(not shown, over the budget: " (str/join ", " left) ")"))}))

(defn cost-report
  "A cost proof's report, its red dimension first: over or steeper, at which sizes, then
  the rows."
  {:malli/schema [:=> [:cat :seon.render/cost-report :seon.render/env] :seon.render/out]}
  [{:keys [rows verdict]} _]
  {:seon.render/ai
   (str/join "\n"
     (concat [(if (:ok? verdict) "within estimate" "OVER ESTIMATE")]
             (for [{:keys [dim n ratio]} (:over verdict)] (str "over: " (name dim) " at n=" n " is " (ui/fmt2 ratio) "x its estimate"))
             (for [{:keys [dim exponent allowed]} (:steeper verdict)] (str "steeper: " (name dim) " grew as n^" (ui/fmt2 exponent) ", allowed n^" (ui/fmt2 allowed)))
             (for [{:keys [n measured expected]} rows]
               (str "n=" n " " (str/join " " (for [[d x] expected] (str (name d) " " (get measured d) "/" x)))))))})
