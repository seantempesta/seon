(ns seon.context
  "The agent's context, rendered live each turn from its task and what it references:
  the current-state block (task plan and instructions, the proofs with their red
  assertions and source, every backticked fully qualified symbol resolved to its doc and
  source, the last evaluations), printed through the one value printer (seon.print). Nothing is stored:
  the next turn renders again from the branch's db."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [datahike.api :as d]
            [seon.print :as print] [seon.reconcile :as r] [seon.reader :as reader]))

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
     ":your-last-reply says what happened to your reply: cut at the token limit, or delimiters the reader repaired."
     "A red proof shows each failure as :assert (as written) and :got (what it evaluated to), and :calls, the program"
     "functions it calls (arglists, contract, doc). :status :undefined is a proof not written yet."
     "Your last evaluations follow the block as a REPL transcript; a value cut short names the handle that reads it whole,"
     "and (my.turn/evals) holds the last turn's forms, values and output in full."]))

(defn source-of
  "The recorded source of var sym on db (:var/src), or nil."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :string]]}
  [db sym] (d/q '[:find ?s . :in $ ?v :where [?e :var/name ?v] [?e :var/src ?s]] db sym))

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
  "A failing assertion as the agent reads it (the test AI render): the assertion as written
  and what it evaluated to, (= 9 (square 3)) got (= 9 6), never the assertion twice; the
  message only when it says something."
  {:malli/schema [:=> [:cat :map] :map]}
  [r]
  (cond-> {:assert (:expected r) :got (or (:got r) (:actual r))}
    (:message r) (assoc :message (:message r))))

(defn callees
  "The program functions proof p calls directly (one level), each at summary level: its
  arglists, contract and doc, never its body. Read from p's source by the reader and
  resolved in p's namespace; a var is program when db holds it."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:vector :map]]}
  [db p]
  (let [nsx (find-ns (symbol (namespace p)))
        src (source-of db p)]
    (if-not (and nsx src)
      []
      (vec (for [sym (distinct (filter symbol? (tree-seq coll? seq (reader/read-text src (ns-name nsx)))))
                 :let [v (ns-resolve nsx sym)]
                 :when (and (var? v) (not (:test (meta v))))
                 :let [q (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v))))]
                 :when (and (not= q p) (d/q '[:find ?e . :in $ ?n :where [?e :var/name ?n]] db q))]
             (cond-> {:var q}
               (:arglists (meta v)) (assoc :arglists (:arglists (meta v)))
               (:malli/schema (meta v)) (assoc :contract (:malli/schema (meta v)))
               (:doc (meta v)) (assoc :doc (:doc (meta v)))))))))

;; ---- the last evaluations: folded, each an equal share --------------------------------

(def last-evals-budget
  "Tokens the last evaluations spend together: each folded item gets an equal share."
  6000)

(defn fold-evals
  "items (one turn's evaluations, in order) with identical form and answer folded into the
  first: [{:k index-of-the-first :item it :times n}], in first-seen order. One pass."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector [:map [:k :int] [:item :map] [:times pos-int?]]]]}
  [items]
  (let [{:keys [order by]} (reduce (fn [{:keys [order by] :as acc} [k it]]
                                     (let [key [(:form it) (:tag it) (:printed it) (:out it)]]
                                       (if (by key)
                                         (update-in acc [:by key :times] inc)
                                         {:order (conj order key) :by (assoc by key {:k k :item it :times 1})})))
                                   {:order [] :by {}} (map-indexed vector items))]
    (mapv by order)))

(defn- clip
  "Text s within n characters; past them it says how many more and the form that reads them."
  {:malli/schema [:=> [:cat :string :int :string] :string]}
  [s n reader]
  (if (<= (count s) n) s (str (subs s 0 (max 0 n)) " …" (- (count s) n) " more chars: " reader)))

(defn last-evals
  "One turn's evaluations as the agent reads them: identical form and answer folded (:times
  n), each folded item an equal share of last-evals-budget (a quarter for its form, half for
  its value, a quarter for its output). A value with a live result is printed again within
  its share, its cuts reading through its handle; output past its share says how much more
  and the form that reads it whole ((my.turn/evals) holds the turn's items)."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [items]
  (let [folded (fold-evals items)
        share (max 32 (quot last-evals-budget (max 1 (count folded))))
        chars #(long (* 3.2 %))]
    (vec (for [{:keys [k item times]} folded
               :let [{:keys [form tag printed out handle]} item
                     rd (fn [field] (str "(" field " (nth (my.turn/evals) " k "))"))
                     h (when handle (symbol "result" (name handle)))
                     vshare (if (seq out) (quot share 2) (quot (* 3 share) 4))]]
           (cond-> {:form (clip (or form "") (chars (quot share 4)) (rd :form))}
             (> times 1) (assoc :times times)
             (= :ret tag) (assoc :value (if (contains? item :value)
                                          (let [pv (print/print-value (:value item) {:handle h :budget (max 16 vshare)})]
                                            (or (:text pv) (clip (or printed "") (chars vshare) (rd :printed))))
                                          (clip (or printed "") (chars vshare) (rd :printed))))
             h (assoc :handle h)
             (= :err tag) (assoc :error (clip (or printed "") (chars vshare) (rd :printed)))
             (seq out) (assoc :out (clip out (chars (quot share 4)) (rd :out))))))))

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
             ;; a passing proof is its status; a red or unrun one shows its source, its failures
             ;; and the program functions it calls; one not defined yet says so
             :proofs (vec (for [p (sort-by str proofs)
                                :let [src (source-of db p)
                                      st (get results p (if src :none :undefined))
                                      fs (filter #(= p (:test %)) reds)]]
                            (cond-> {:test p :status st}
                              (and src (not= :pass st)) (assoc :source src)
                              (seq fs) (assoc :failures (mapv failure fs))
                              (and src (not= :pass st) (seq (callees db p))) (assoc :calls (callees db p)))))
             ;; a reference already shown as a proof is not shown twice
             :references (mapv #(resolve-ref db %) (remove (set proofs) refs))}
      (seq (refused (vec refusals) (vec reds))) (assoc :refused (refused (vec refusals) (vec reds)))
      (seq run-errors) (assoc :test-run {:failed (vec run-errors)})
      (last-reply truncated? source (vec repairs)) (assoc :your-last-reply (last-reply truncated? source (vec repairs)))
      (seq flagged) (assoc :flagged (vec (sort-by str flagged)))
      (:seon.task/evidence t) (assoc :evidence (:seon.task/evidence t))
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :kind :base :ours :theirs]) unmerged))
      (seq last-items) (assoc :last-evals (last-evals last-items)))))

(defn- transcript
  "The last evaluations as a REPL transcript: each form, its value (=> ...) or error (!! ...),
  its output, how many times it repeated, and the handle that reads its value whole."
  {:malli/schema [:=> [:cat [:vector :map]] :string]}
  [evals]
  (str/join "\n" (for [{:keys [form times value handle error out]} evals]
                   (str (when times (str ";; " times "x\n")) form
                        (when out (str "\n;; out: " (str/replace out "\n" "\n;; ")))
                        (when value (str "\n=> " value (when handle (str "    ;; whole: " handle))))
                        (when error (str "\n!! " error))))))

(defn messages
  "The model request's messages for the block: the system prompt, then the block printed
  by the one value printer under the context's budget, breadth first: every section shows
  before any one deepens, and each cut names the form that reads its rest."
  {:malli/schema [:=> [:cat :map] [:vector :seon.model/message]]}
  [block]
  [{:role "system" :content system-prompt}
   {:role "user" :content (cond-> (print/text (dissoc block :last-evals) (- (:context print/budgets) last-evals-budget))
                            (:last-evals block) (str "\n\nYour last evaluations (folded; each within its share):\n"
                                                     (transcript (:last-evals block))))}])
