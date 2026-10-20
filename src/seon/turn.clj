(ns seon.turn
  "The agent's turn loop, inside its own process (lanes R1 and M1's proto.agents.turn). A
  turn observes the branch (the proofs run in a fork of this agent, the done rule), and
  either ends the attempt or acts: the context goes to the model (one interop edge), the
  reply's forms go through the one REPL path (seon.repl/eval-string: read, evaluate,
  record), and the turn's facts are written on the branch. The loop repeats until the done
  rule has no refusal, a verb ends it (my.turn/wait, my.task/send-back!) or the turns run
  out. All green at the start ends it with no model call.

  A merge task's agent first merges main into the branch (no model call) and merges main
  again each time all is green, until a merge brings nothing new; it stops early when the
  merge is too hairy (seon.reconcile/hairy). Either way the live runtime is staged onto the
  branch at the end (seon.harvest/harvest!), so the next agent on the branch holds it.

  Root starts it by evaluating (seon.turn/work! '{...}) in the agent; the attempt's end is
  a fact on the branch (:seon.end/*), which root's judge reads."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.set :as set]
            [seon.agent :as agent] [seon.repl :as repl] [seon.task :as task] [seon.reconcile :as r]
            [seon.proofs :as proofs] [seon.context :as context] [seon.model :as model]
            [seon.harvest :as harvest] [my.branch :as branch]))

;; The turn loop's own facts live in the agent's one state map, under :turn: the task and
;; attempt it works, and a stop a verb requested.

(defn current-task
  "The id of the task this process works, or nil outside a turn loop."
  {:malli/schema [:=> [:cat] [:maybe :seon.task/id]]}
  [] (get-in @agent/state [:turn :task]))

(defn request-stop!
  "A verb ends the loop after this turn's forms: :waiting (my.turn/wait) or :sent-back."
  {:malli/schema [:=> [:cat [:enum :waiting :sent-back] [:maybe :string]] :map]}
  [outcome reason]
  (:turn (swap! agent/state assoc-in [:turn :stop] (cond-> {:outcome outcome} reason (assoc :reason reason)))))

(defn reply->source
  "The reply as Clojure source: the bodies of its fenced blocks, every other line a
  comment. A reply with no fence is taken as source whole."
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (if-not (str/includes? text "```")
    text
    (loop [[l & ls] (str/split-lines text) in? false out []]
      (cond
        (nil? l) (str/join "\n" out)
        (str/starts-with? (str/trim l) "```") (recur ls (not in?) out)
        in? (recur ls in? (conj out l))
        :else (recur ls in? (conj out (str ";; " l)))))))

(defn- observe
  "What a turn starts from, measured now: the proofs run in a fork of this agent, the
  branch's new contract gaps and unmerged entries, the done rule over them, and the task as
  the branch holds it (its plan may have improved)."
  {:malli/schema [:=> [:cat :seon.task/id [:set :qualified-symbol]] :map]}
  [id gaps-before]
  (let [run (agent/run-tests-forked 30000)
        results (proofs/results run)
        db (agent/db)
        unc (set/difference (harvest/uncontracted db) gaps-before)
        unmerged (branch/unmerged)]
    {:run run :results results :uncontracted unc :unmerged unmerged :task (task/task db id)
     :refusals (r/done-refusals (r/done-input (r/view db) id results unc (mapv #(pr-str (:key %)) unmerged) nil))}))

(defn- end!
  "The attempt's end: the live runtime staged onto the branch, then the end written there."
  {:malli/schema [:=> [:cat :map :seon.attempt/outcome [:maybe :string]] :seon.turn/end]}
  [{:keys [turn tokens t0 attempt]} outcome reason]
  (harvest/harvest!)
  (agent/transact! [(cond-> {:seon.end/attempt attempt :seon.end/outcome outcome :seon.end/turns turn :seon.end/tokens tokens}
                      reason (assoc :seon.end/reason reason))])
  (cond-> {:outcome outcome :turns turn :model-calls turn :tokens tokens :results {} :uncontracted #{}
           :ms (/ (- (System/nanoTime) t0) 1e6)}
    reason (assoc :reason reason)))

(defn- act!
  "One model turn: the context of what was observed to the model, the reply's forms through
  the one REPL path, the turn's facts on the branch. Answers the loop state after it, or the
  provider error."
  {:malli/schema [:=> [:cat :map :map :seon.model/provider] [:or :map :seon.model/provider-error]]}
  [{:keys [turn tokens last-items max-turns merge? attempt] :as st} {:keys [run results unmerged] :as obs} provider]
  (let [t (:task obs)
        proofs (:proofs (:seon.task/accumulated t))
        block (context/current-state (agent/db) t {:results results :turn turn :max-turns max-turns
                                                   :reds (proofs/red-reports run proofs) :last-items last-items
                                                   :unmerged (when merge? unmerged)})
        t1 (System/nanoTime)
        c (model/complete provider {:messages (context/messages block) :task (:seon.task/id t) :turn turn})]
    (if (:seon.error/type c)
      c
      (let [items (repl/eval-string (reply->source (:text c)))
            used (+ (:prompt-tokens c) (:completion-tokens c))]
        (agent/transact! [{:seon.turn/id (str attempt "/" turn) :seon.turn/attempt attempt :seon.turn/n turn
                           :seon.turn/reply (:text c) :seon.turn/forms (count items)
                           :seon.turn/errors (count (filter #(= :err (:tag %)) items))
                           :seon.turn/red (count (remove #(= :pass (val %)) (select-keys results proofs)))
                           :seon.turn/ms (long (/ (- (System/nanoTime) t1) 1e6)) :seon.turn/tokens used}])
        (cond-> (assoc st :turn (inc turn) :tokens (+ tokens used) :last-items items)
          merge? (update :merge-turns inc))))))

(defn work!
  "Work task `task` as attempt `attempt` with `provider`, at most max-turns model calls.
  Answers the end ({:outcome :turns :model-calls :tokens :ms}); the same end is written on
  the branch."
  {:malli/schema [:=> [:cat [:map [:task :seon.task/id] [:attempt :seon.attempt/id] [:provider :seon.model/provider]
                             [:max-turns pos-int?]]]
                  :seon.turn/end]}
  [{:keys [task attempt provider max-turns]}]
  (swap! agent/state assoc :turn {:task task :attempt attempt})
  (let [t0-task (task/task (agent/db) task)
        merge? (= :merge (:seon.task/kind t0-task))
        dials (r/dials t0-task)]
    (when merge? (branch/merge-main!))
    (loop [st {:turn 0 :tokens 0 :last-items [] :merge-turns 0 :t0 (System/nanoTime) :attempt attempt
               :max-turns max-turns :merge? merge? :gaps-before (harvest/uncontracted (agent/db))}]
      (let [{:keys [refusals unmerged] :as obs} (observe task (:gaps-before st))
            stop (get-in @agent/state [:turn :stop])
            hairy (when merge? (r/hairy (merge dials {:conflicts unmerged :task (:task obs) :merge-turns (:merge-turns st)})))]
        (cond
          ;; git's CI after the merge: green on the combined program; merge main again until
          ;; it brings nothing new
          (and (empty? refusals) merge?)
          (let [m (branch/merge-main!)]
            (if (and (empty? (:applied m)) (empty? (:conflicts m))) (end! st :green nil) (recur st)))
          (empty? refusals) (end! st :green nil)
          stop (end! st (:outcome stop) (:reason stop))
          hairy (end! st :merge-failed (:reason hairy))
          (>= (:turn st) max-turns) (end! st :cap (r/refusals-text refusals))
          :else (let [st' (act! st obs provider)]
                  (if (:seon.error/type st') (end! st :error (:seon.error/message st')) (recur st'))))))))
