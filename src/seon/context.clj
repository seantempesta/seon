(ns seon.context
  "The agent's context, rendered live each turn from its task and what it references:
  the current-state block (task plan and instructions, the proofs with their red
  assertions and source, every backticked fully qualified symbol resolved to its doc and
  source, the last evaluations), printed through the value printer. Nothing is stored:
  the next turn renders again from the branch's db."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [datahike.api :as d]
            [seon.agent :as agent] [seon.reconcile :as r]))

(def system-prompt
  "What every model is told once per turn: how to answer and which verbs exist."
  (str/join "\n"
    ["You are a Seon agent working one task in a Clojure REPL on your own branch."
     "Each turn you get the current-state block: your task, its proofs (deftests) and their results,"
     "the code the task references, and what your last forms evaluated to."
     "Answer with Clojure forms in ```clojure fenced blocks; prose outside fences is ignored."
     "Change code in REPL terms: (in-ns 'the.ns) then (defn ...). Every function you define needs a"
     "Malli contract in its metadata, e.g. (defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))."
     "Your task is done when every proof passes; the proofs run after each of your answers."
     "Verbs: (my.task/current) your task; (my.task/update! :seon.task/plan f & args) improve your plan;"
     "(my.task/send-back! reason counterexample) when the task is impossible;"
     "(my.turn/wait) to stop and wait for root."
     "Your branch sees only its own changes. A merge task's branch has main merged in: its :unmerged entries"
     "each show :base, :ours and :theirs. Resolve one by re-defining the var with the content both sides need,"
     "or (my.branch/resolve! 'ns/var :theirs) / (my.branch/resolve! 'ns/var) to keep yours; (my.branch/unmerged) lists them."
     ":evidence, when present, is what a failed merge measured: its conflicts and red proofs."]))

(defn source-of
  "The recorded source of var sym on db (:var/src), or nil."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :string]]}
  [db sym] (d/q '[:find ?s . :in $ ?v :where [?e :var/name ?v] [?e :var/src ?s]] db sym))

(defn resolve-ref
  "A reference resolved: its doc (from the var) and source (from the db), as data."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] :map]}
  [db sym]
  (let [v (some-> (find-ns (symbol (namespace sym))) ns-interns (get (symbol (name sym))))]
    (cond-> {:symbol sym}
      (nil? v) (assoc :unresolved true)
      (some-> v meta :doc) (assoc :doc (:doc (meta v)))
      (source-of db sym) (assoc :source (source-of db sym)))))

(defn current-state
  "The current-state block of task t as data: proofs with their status, red assertions and
  source; resolved references; the measured evidence it starts from; the branch's
  unmerged entries (base, ours, theirs); the last evaluations; turns left."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :map] :map]}
  [db t {:keys [results reds last-items turn max-turns unmerged]}]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        refs (r/references (str plan "\n" (:seon.task/instructions t)))]
    (cond-> {:task (assoc (select-keys t [:seon.task/id :seon.task/kind :seon.task/instructions])
                          ;; the plan as it accumulated along :needs (prerequisites first)
                          :seon.task/plan plan)
             :turn (str (inc turn) " of " max-turns)
             :proofs (vec (for [p (sort-by str proofs)]
                            (cond-> {:test p :status (get results p :none) :source (source-of db p)}
                              (seq (filter #(= p (:test %)) reds))
                              (assoc :failures (mapv #(dissoc % :test) (filter #(= p (:test %)) reds))))))
             :references (mapv #(resolve-ref db %) refs)}
      (:seon.task/evidence t) (assoc :evidence (:seon.task/evidence t))
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :kind :base :ours :theirs]) unmerged))
      (seq last-items) (assoc :last-evals (mapv (fn [it] (cond-> {:form (:form it)}
                                                           (= :ret (:tag it)) (assoc :value (:printed it))
                                                           (= :err (:tag it)) (assoc :error (:printed it))
                                                           (:out it) (assoc :out (:out it))))
                                                last-items)))))

(defn messages
  "The model request's messages for the block: the system prompt, then the block printed
  by the value printer (under a context's budget: the builtin printer's default cuts at
  depth 4 and 20 items, which would cut a proof's failures)."
  {:malli/schema [:=> [:cat :map] [:vector :seon.model/message]]}
  [block]
  [{:role "system" :content system-prompt}
   {:role "user" :content (binding [agent/*print-limits* {:length 64 :level 8 :chars 48000}] (agent/printer block))}])
