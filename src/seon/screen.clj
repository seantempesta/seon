(ns seon.screen
  "The agent's screen: what the user's screen shows of it, as forms the system evaluates in the
  agent's REPL just before each model request (`forms`). Each is an eval like any other,
  recorded by the screen (:seon.eval/by \"screen\"), and sent as the request's last user
  messages, replaced by the next request's (seon.turn, seon.context/request), so they
  never repeat in the conversation. `show` answers the agent's page without its chat column as
  plain hiccup: an ordinary value, printed by the one printer like any other."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.task :as task] [seon.program :as program] [seon.context :as context]
            [seon.ref :as ref] [seon.docs :as docs]))

(def forms
  "The screen's forms, evaluated in order before each model request: the agent's page, then
  the live state (its task's proofs and what refuses done; what its branch's merge carries).
  An agent changes what it is shown by redefining this var: rewriting its own interface is an
  eval."
  ["(seon.screen/show)" "(my.task/status)" "(my.branch/changes)"])

(defn input
  "What this turn's screen forms read (my.task/status, `show`): {:task :input :last}, the task,
  the turn sections' input as this turn's observe measured it (seon.context/turn-input) and
  the last model turn's calls, as seon.turn/act! holds them; nil before any turn of this
  process observed its task."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  [] (get-in @agent/state [:turn :screen]))

(defn cited
  "What prose cites, by the one reference parser (docs/references.md), each looked up on db: a
  var its source and docstring (seon.docs/resolve-ref at :code), anything else a view (a task its
  plan and proofs, a section, a file, a namespace: seon.docs/cited-view, at least at :doc)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :string]] [:vector :map]]}
  [db prose]
  (if (empty? prose)
    []
    (vec (for [c (ref/references db prose)
               :let [k (docs/citation-kind db c)]
               :when k]
           (if (= :var k)
             (docs/resolve-ref db (:seon.ref/name c) :code)
             (docs/cited-view db (cond-> c (= :summary (:seon.ref/depth c)) (assoc :seon.ref/depth :doc))))))))

(defn show
  "The agent's page without its chat column, as plain hiccup with no presentation attributes:
  its task, each proof's status as this turn's observe measured it, how many vars and schemas
  its branch's merge carries, its latest eval's handle, and what its latest prose cites, looked
  up (`cited`)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [{:keys [task input last]} (input)
        t (when task (task/task (agent/db) task))
        changes (program/divergence (:base @agent/state) (agent/db))
        r (peek (vec (get-in @agent/state [:turn :evals])))]
    [:div
     [:h1 (str task) (when-let [k (:seon.task/kind t)] [:span " " (name k)])]
     [:section [:h2 "proofs"]
      ;; each status as my.task/status says it (seon.context/turn-state)
      (into [:ul] (for [{:keys [test status]} (:proofs (context/turn-state (agent/db) t input))]
                    [:li [:code (str test)] " " (name status)]))]
     [:section [:h2 "changes"]
      (if (seq changes)
        (into [:ul] (for [[k vs] (sort-by key changes)] [:li (name k) " " (count vs)]))
        [:p "none"])]
     (when r
       ;; its handle, never its code again: the code at the request's end was repeated as the
       ;; next call (step 2's measured runs: 4 of 5 ended :no-progress)
       [:section [:h2 "latest eval"] (cond-> [:p [:code (str (:handle r))]] (:ex r) (conj " threw"))])
     ;; what the latest prose cites, looked up (the user's screen shows it beside the chat)
     (when-let [cs (seq (cited (agent/db) (:prose last)))]
       [:section [:h2 "references"] (vec cs)])]))
