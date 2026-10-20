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
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [seon.render.ui :as ui]))

(defn tokens
  "A text's tokens, estimated as the printer does (4 characters a token)."
  {:malli/schema [:=> [:cat :string] nat-int?]}
  [s] (quot (+ 3 (count s)) 4))

(defn- kn {:malli/schema [:=> [:cat :any] :string]} [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- counts {:malli/schema [:=> [:cat :map] :string]}
  [freq] (str/join " " (for [[k n] (sort-by (comp str key) freq)] (str (kn k) " " n))))

(defn problems
  "What is wrong now, most urgent first: procs not running (or not answering), root's
  defects, the problems main recorded (seon.problems/report, by class), live agents with
  red tests or a refused merge, agents whose process died. An
  agent root stopped after its attempt finished is not a problem."
  {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [health root-errors agents agent-attempt recorded]}]
  (let [bad (for [[pid h] (sort-by (comp str key) health)
                  :when (or (= :unknown h) (not= :running (:status h)))]
              (str "proc " (kn pid) " " (if (= :unknown h) "UNKNOWN (no answer to its ping)" (kn (:status h)))))
        errs (for [e (take-last 3 root-errors)] (str "defect in " (kn (:pid e)) ": " (ui/clip (:message e) 160)))
        ;; a live agent's red run or refused merge, or an agent whose process died; an agent
        ;; root stopped (an attempt that ended) is not a problem, whatever its last run said
        ;; one pass over the agents; only the few with a problem are sorted
        ags (for [[id a] (sort-by (comp str key) (filter (fn [[_ a]] (or (:error a) (:tests a) (:merged a) (= :dead (:status a)))) agents))
                  :let [t (:tests a) m (:merged a) live? (not (#{:killed :dead} (:status a)))
                        finished? (#{:done :merged} (:seon.attempt/status (get agent-attempt id)))]
                  :when (or (and (= :dead (:status a)) (not finished?)) (:error a)
                            (and live? (or (pos? (+ (or (:fail t) 0) (or (:error t) 0))) (and m (false? (:merged? m))))))]
              (str (kn id) " " (kn (:status a)) ": "
                   (str/join "; " (remove nil? [(when (= :dead (:status a)) "its process died")
                                                (when (:error a) (ui/clip (:error a) 100))
                                                (when (pos? (+ (or (:fail t) 0) (or (:error t) 0)))
                                                  (str "tests " (or (:pass t) 0) " pass " (or (:fail t) 0) " fail " (or (:error t) 0) " error"
                                                       (when-let [r (first (remove #(= :pass (:type %)) (:reports t)))]
                                                         (str " (" (:var r) ")"))))
                                                (when (and m (false? (:merged? m)))
                                                  (str "merge refused" (when (seq (:conflicts m)) (str ", " (count (:conflicts m)) " conflicts"))))]))))
        ;; what main recorded (seon.problems/report): one line per class with open problems,
        ;; its count and its newest or commonest example
        rec (for [[c {:keys [n rows]}] (sort-by (comp str key) (:classes recorded))
                  :let [rows (remove #(false? (:open? %)) rows)]
                  :when (seq rows)
                  :let [r (first rows)]]
              (str "recorded " (kn c) " " (if (:signature r) n (count rows)) ": "
                   (ui/clip (or (:message r)
                                (some->> [(:attempt r) (:test r) (:reason r) (some-> (:outcome r) kn)] (remove nil?) seq (str/join " "))
                                (pr-str (select-keys r [:producer :owner-ns])))
                            140)
                   (when-let [ts (seq (:answered-by r))] (str " (answered by " (str/join ", " ts) ")"))))]
    (vec (concat bad errs rec ags))))

(defn- section-work {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [tasks attempts task-rows]}]
  (if (empty? tasks)
    ["no tasks on main"]
    (let [running (filter #(= :running (:seon.attempt/status %)) (vals attempts))
          ended (take 5 (sort-by (comp - #(or (:seon.attempt/end-ms %) 0)) (filter :seon.attempt/outcome (vals attempts))))]
      (vec (concat
             ;; what happened to each task (observed), not its desired execution
             [(str (count tasks) " tasks (" (counts (frequencies (map :state (vals task-rows)))) "); "
                   (count attempts) " attempts (" (counts (frequencies (map :seon.attempt/status (vals attempts)))) ")")]
             (for [[id r] (sort-by key task-rows) :when (seq (:blocked-by r))]
               (str id " blocked by " (str/join ", " (sort (:blocked-by r)))))
             (for [a (take 8 running)]
               (str "running " (:seon.attempt/id a) " on " (kn (:seon.attempt/agent a)) ", turn " (or (:seon.attempt/turns a) 0)
                    ", " (or (:seon.attempt/tokens a) 0) " tokens"))
             (for [a ended]
               (str "ended " (:seon.attempt/id a) " " (kn (:seon.attempt/outcome a))
                    (when (:seon.attempt/reason a) (str ": " (ui/clip (:seon.attempt/reason a) 80))))))))))

(defn- section-load {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [agents writer server main]}]
  [(str (count agents) " agents (" (counts (frequencies (map :status (vals agents)))) ")")
   (str "writer " (ui/fmt1 (:tx-per-s writer)) " tx/s, " (:tx-count writer 0) " txs, batch p50 " (ui/ms-str (:batch-p50-ms writer)))
   (str "fork server " (kn (:status server)) " pid " (:pid server) ", fork p50 " (ui/ms-str (:fork-ms-p50 server))
        ", " (:agents-forked server 0) " forked, " (:restarts server 0) " restarts")
   (str "main t " (:t main) ", " (:datoms main "?") " datoms, " (:merges main 0) " merges")])

(defn- section-costly {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [agents]}]
  (let [fns (for [[_ a] agents e (take-last 3 (:evals a)) f (:fns e)] f)
        by (reduce (fn [m {:keys [var calls ms]}] (-> m (update-in [var :calls] (fnil + 0) calls) (update-in [var :ms] (fnil + 0.0) (or ms 0.0))))
                   {} fns)]
    (vec (for [[v {:keys [calls ms]}] (take 5 (sort-by (comp - :ms val) by))]
           (str v " " calls " calls " (ui/ms-str ms))))))

(defn- section-events {:malli/schema [:=> [:cat :seon.render/system] [:vector :string]]}
  [{:keys [events]}]
  (vec (for [e (take 8 events)]
         (str (ui/clock (:at-ms e)) " " (kn (:type e)) " " (if (:agent e) (kn (:agent e)) "root")
              (case (:type e)
                :reply (let [r (last (:items e))] (str " " (ui/line (:form r) 40) " => " (ui/line (:printed r) 40)))
                :tests (str " " (:pass e 0) "/" (+ (:pass e 0) (:fail e 0) (:error e 0)))
                :merged (if (get-in e [:result :merged?]) " into main" " refused")
                "")))))

(defn system
  "Root's context: the system as a budgeted text, problems first (see the namespace doc).
  env :seon.render/budget tokens (default 700)."
  {:malli/schema [:=> [:cat :seon.render/system :seon.render/env] :seon.render/out]}
  [sys env]
  (let [budget (* 4 (or (:seon.render/budget env) 700))
        problems (problems sys)
        sections [["problems" (if (seq problems) problems [(str "none: " (count (:health sys)) " procs running, no defects, no red agents")])]
                  ["work" (section-work sys)]
                  ["load" (section-load sys)]
                  ["costliest functions in the newest evaluations" (section-costly sys)]
                  ["newest events" (section-events sys)]]
        ;; no clock in the text: it would change every pass with nothing else changed (the
        ;; reader knows when it asked)
        head "seon root: the whole system, every number measured, problems first"
        [text left] (reduce (fn [[text left] [title lines]]
                              (let [part (str "\n# " title "\n" (str/join "\n" (map #(str "- " %) lines)))]
                                (if (and (empty? left) (<= (+ (count text) (count part)) budget))
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
