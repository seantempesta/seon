(ns seon.context
  "The agent's context, rendered live each turn from its task and what it references:
  the current-state block (task plan and instructions, the proofs with their red
  assertions and source, every backticked fully qualified symbol resolved to its doc and
  source, the last evaluations), printed through the one value printer (seon.print). Nothing is stored:
  the next turn renders again from the branch's db."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [datahike.api :as d]
            [seon.print :as print] [seon.reconcile :as r]))

(def system-prompt
  "What every model is told once per turn: how to answer and which verbs exist."
  (str/join "\n"
    ["You are a Seon agent working one task in a Clojure REPL on your own branch."
     "Each turn you get the current-state block: your task, its proofs (deftests) and their results,"
     "the code the task references, and what your last forms evaluated to."
     "Answer with Clojure forms in ```clojure fenced blocks. Prose outside a fence becomes a comment, never code;"
     "an unbalanced delimiter is repaired and the repair is shown to you."
     "This is a real REPL: (ns my.new.ns (:require [other.ns :refer [f]])) creates a namespace and it becomes"
     "part of your branch's program, exactly as written; (in-ns 'the.ns) then (defn ...) changes an existing one;"
     "the user namespace is scratch and never merges. Every function you define needs a"
     "Malli contract in its metadata, e.g. (defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))."
     "Your task is done when every proof passes; the proofs run after each of your answers."
     "A cost proof (seon.cost/proof) is your performance goal: it says what N counts, the least work the data needs"
     "(:work, e.g. one pass with one lookup per row), how that grows and what it may cost. Read it before writing code and"
     "do no more work than it states. It is red when a size costs more than its estimate or grows faster than its :growth."
     "The judge: allocated bytes may reach 1.25x the estimate and contracted calls 1.0x (both are counted exactly), wall time 3x;"
     "a dimension whose growth exponent exceeds :growth's by more than 0.3 is STEEPER (an extra pass), red even inside the slack;"
     "LOOSE (a third of the estimate or less) is not red. Its failure message prints measured/estimate per size."
     "Verbs: (my.task/current) your task; (my.task/update! :seon.task/plan f & args) improve your plan;"
     "(my.task/send-back! reason counterexample) when the task is impossible;"
     "(my.turn/wait) to stop and wait for root."
     "Your branch sees only its own changes. A merge task's branch has main merged in: its :unmerged entries"
     "each show :base, :ours and :theirs. Resolve one by re-defining the var with the content both sides need,"
     "or (my.branch/resolve! 'ns/var :theirs) / (my.branch/resolve! 'ns/var) to keep yours; (my.branch/unmerged) lists them."
     ":evidence, when present, is why an earlier attempt failed (:problem) and what it measured: conflicts, red proofs."
     "Every section is measured now, by the same judge that decides done:"
     ":refused lists what keeps you from done beyond your red proofs: :regression (green on main, red here: your change broke it),"
     ":stray-red (a red test that is no task's proof), :contract-gap (a function you defined without a :malli/schema),"
     ":unmerged, :no-proofs. :test-run is the test run itself failing (a timeout, a dead fork): no test result is real until it runs."
     ":flagged are definitions on your branch with no recorded source: they will not merge until you evaluate their defn again."
     ":your-last-reply says what happened to your reply: cut at the token limit, or delimiters the reader repaired."]))

(defn source-of
  "The recorded source of var sym on db (the text of the form that last defined it), or nil."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :string]]}
  [db sym] (d/q '[:find ?s . :in $ ?v :where [?e :var/name ?v] [?e :var/form ?f] [?f :form/src ?s]] db sym))

(defn flagged
  "The definitions on db with no recorded source (:var/flagged): they do not merge."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :qualified-symbol]]}
  [db] (vec (sort-by str (d/q '[:find [?n ...] :where [?e :var/flagged true] [?e :var/name ?n]] db))))

(defn resolve-ref
  "A reference resolved: its source (from the db) when it is program, else its doc (from
  the var); a source already holds its docstring."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] :map]}
  [db sym]
  (let [v (some-> (find-ns (symbol (namespace sym))) ns-interns (get (symbol (name sym))))
        src (source-of db sym)]
    (cond-> {:symbol sym}
      (nil? v) (assoc :unresolved true)
      src (assoc :source src)
      (and (nil? src) (some-> v meta :doc)) (assoc :doc (:doc (meta v))))))

(defn- failure
  "A failing assertion as the agent reads it: what was expected and what happened (the
  message only when it says something)."
  {:malli/schema [:=> [:cat :map] :map]}
  [r] (cond-> (select-keys r [:expected :actual]) (:message r) (assoc :message (:message r))))

(defn- refused
  "The done rule's refusals as the agent reads them, beyond its own red proofs (shown with
  the proofs): a red test names its failures, a contract gap its var."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/refusal] [:vector :map]] [:vector :map]]}
  [refusals reds]
  (vec (for [{:keys [refusal symbol key]} refusals :when (not= :proof-red refusal)]
         (case refusal
           (:regression :stray-red) (cond-> {:refusal refusal :test symbol}
                                      (seq (filter #(= symbol (:test %)) reds))
                                      (assoc :failures (mapv failure (filter #(= symbol (:test %)) reds))))
           :contract-gap {:refusal refusal :var symbol}
           :unmerged {:refusal refusal :key key}
           {:refusal refusal}))))

(defn- near
  "The code around position at of text: its line, at most 30 characters either side."
  {:malli/schema [:=> [:cat :string :int] :string]}
  [text at]
  (let [at (min at (count text))
        start (if (pos? at) (inc (or (str/last-index-of text "\n" (dec at)) -1)) 0)
        end (or (str/index-of text "\n" at) (count text))]
    (str/trim (subs text (max start (- at 30)) (min end (+ at 30))))))

(defn last-reply
  "What happened to the agent's last reply: cut at the token limit, and each delimiter the
  reader repaired (what it dropped or inserted, and the code near it in source, the reply
  as the reader gave it to the REPL), or nil."
  {:malli/schema [:=> [:cat [:maybe :boolean] [:maybe :string] [:vector :map]] [:maybe :map]]}
  [truncated? source repairs]
  (let [rs (for [{:keys [at dropped inserted]} repairs :when (not= ";; " inserted)]
             (cond-> {:near (near (or source "") at)}
               (seq dropped) (assoc :dropped dropped)
               (seq inserted) (assoc :inserted inserted)))]
    (cond-> nil
      truncated? (assoc :cut "at the model's token limit: what followed the cut was lost. Answer with less: one change at a time.")
      (seq rs) (assoc :repaired (vec rs)))))

(defn current-state
  "The current-state block of task t as data: proofs with their status, red assertions and
  source; what else the done rule refuses (regressions, stray reds, contract gaps), and a
  test run that failed as a whole; resolved references; the measured evidence it starts
  from; the branch's unmerged entries (base, ours, theirs) and flagged definitions; what
  happened to the last reply; the last evaluations; turns left."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :map] :map]}
  [db t {:keys [results reds last-items turn max-turns unmerged truncated? refusals run-errors source repairs flagged]}]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        refs (r/references (str plan "\n" (:seon.task/instructions t)))]
    (cond-> {:task (assoc (select-keys t [:seon.task/id :seon.task/kind :seon.task/instructions])
                          ;; the plan as it accumulated along :needs (prerequisites first)
                          :seon.task/plan plan)
             :turn (str (inc turn) " of " max-turns)
             ;; a passing proof is its status; a red or unrun one shows its source and failures
             :proofs (vec (for [p (sort-by str proofs) :let [st (get results p :none)]]
                            (cond-> {:test p :status st}
                              (not= :pass st) (assoc :source (source-of db p))
                              (seq (filter #(= p (:test %)) reds))
                              (assoc :failures (mapv failure (filter #(= p (:test %)) reds))))))
             ;; a reference already shown as a proof is not shown twice
             :references (mapv #(resolve-ref db %) (remove (set proofs) refs))}
      (seq (refused (vec refusals) (vec reds))) (assoc :refused (refused (vec refusals) (vec reds)))
      (seq run-errors) (assoc :test-run {:failed (vec run-errors)})
      (last-reply truncated? source (vec repairs)) (assoc :your-last-reply (last-reply truncated? source (vec repairs)))
      (seq flagged) (assoc :flagged (vec (sort-by str flagged)))
      (:seon.task/evidence t) (assoc :evidence (:seon.task/evidence t))
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :kind :base :ours :theirs]) unmerged))
      (seq last-items) (assoc :last-evals (mapv (fn [it] (cond-> {:form (:form it)}
                                                           (= :ret (:tag it)) (assoc :value (:printed it))
                                                           (= :err (:tag it)) (assoc :error (:printed it))
                                                           (:out it) (assoc :out (:out it))))
                                                last-items)))))

(defn messages
  "The model request's messages for the block: the system prompt, then the block printed
  by the one value printer under the context's budget, breadth first: every section shows
  before any one deepens, and each cut names the form that reads its rest."
  {:malli/schema [:=> [:cat :map] [:vector :seon.model/message]]}
  [block]
  [{:role "system" :content system-prompt}
   {:role "user" :content (print/text block (:context print/budgets))}])
