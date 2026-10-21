(ns seon.context
  "The agent's context, rendered live each turn from its task and what it references:
  the current-state block (task plan and instructions, the proofs with their red
  assertions and source, every var the task's text cites (seon.ref/references) resolved to its doc and
  source, every wiki section, file and namespace it cites shown at the depth its delimiter asks (seon.docs/view, seon.ref/file-view, seon.ref/namespace-view), the last evaluations), printed through the one value printer (seon.print). Nothing is stored:
  the next turn renders again from the branch's db."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [datahike.api :as d]
            [seon.print :as print] [seon.reader :as reader] [seon.ref :as ref] [seon.docs :as docs] [seon.config :as cfg]))

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
     "(my.schema/register! k form) changes a schema (resources/seon/schemas) on your branch, as defn changes a function: your next forms see it;"
     "(my.turn/wait) to stop and wait for root."
     "Your branch sees only its own changes. A merge task's branch has main merged in: its :unmerged entries"
     "each show :base, :ours and :theirs. Resolve one by re-defining the var with the content both sides need,"
     "or (my.branch/resolve! 'ns/var :theirs) / (my.branch/resolve! 'ns/var) to keep yours; (my.branch/unmerged) lists them."
     ":evidence, when present, is why an earlier attempt failed (:problem) and what it measured: conflicts, red proofs."
     "Every section is measured now, by the same judge that decides done:"
     ":refused lists what keeps you from done beyond your red proofs: :regression (green on main, red here: your change broke it),"
     ":stray-red (a red test that is no task's proof), :contract-gap (a function you defined without a :malli/schema),"
     ":unmerged, :no-proofs. :test-run is the test run itself failing (a timeout, a dead fork): no test result is real until it runs."
     ":your-last-reply says what happened to your reply: cut at the token limit, or delimiters the reader repaired."
     "A red proof shows each failure as :assert (as written) and :got (what it evaluated to), and :calls, the program"
     "functions it calls (arglists, contract, doc). A proof's :status :undefined is not written yet, :not-run means the test run"
     "failed (see :test-run), :no-assertions means it ran but asserted nothing."
     "Your last evaluations follow the block, identical ones folded (;; 24x); (my.turn/evals) holds them all in full,"
     "and every cut names the form that reads its rest."]))

(defn source-of
  "The recorded source of var sym on db (the text of the form that last defined it), or nil."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :string]]}
  [db sym] (d/q '[:find ?s . :in $ ?v :where [?e :var/name ?v] [?e :var/form ?f] [?f :form/src ?s]] db sym))

(defn recorded
  "The last context attempt's reader got, as recorded on db (seon.turn: a model turn's user
  message, or an outside read through the entrance): {:text :origin :turn}, or nil. The one
  source of what an agent read: the page shows it, nothing renders it a second time."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe [:map [:text :string] [:origin :keyword] [:turn :string]]]]}
  [db attempt]
  (when-let [[_ id text origin] (last (sort-by first (d/q '[:find ?e ?id ?c ?o :in $ ?a
                                                          :where [?e :seon.turn/attempt ?a] [?e :seon.turn/context ?c]
                                                          [?e :seon.turn/origin ?o] [?e :seon.turn/id ?id]]
                                                        db attempt)))]
    {:text text :origin origin :turn id}))

(defn resolve-ref
  "A reference resolved: its source (from the db) when it is program, else its doc (from
  the var); a source already holds its docstring."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] :map]}
  [db sym]
  (let [v (some-> (find-ns (symbol (namespace sym))) ns-interns (get (symbol (name sym))))
        src (source-of db sym)]
    (cond-> {:symbol sym}
      (nil? v) (assoc :unresolved true :problem (:unresolved ref/problem-messages))
      src (assoc :source src)
      (and (nil? src) (some-> v meta :doc)) (assoc :doc (:doc (meta v))))))

(defn- cited-view
  "What citation c asks to see, at its depth: a wiki section from main's sections, else a file of
  the checkout read now (never stored), else a namespace; a citation that reaches nothing says
  so, as an unresolved name does."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] :map]}
  [db {:seon.ref/keys [name path anchor depth]}]
  (let [a (or (some-> name str) (str path (when anchor (str "#" anchor))))]
    (or (some-> (if name
                  (ref/namespace-view db name depth)
                  (or (when anchor (docs/view db a depth)) (ref/file-view (cfg/checkout) path anchor depth)))
                (assoc :seon.ref/depth depth))
        {:seon.ref/address a :unresolved true :problem (:unresolved ref/problem-messages)})))

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
  "Tokens one turn's evaluations spend together, each folded item an equal share: the config's."
  (cfg/value :seon.config.render/evals-budget))

(defn fold-evals
  "items (one turn's evaluations, in order, as (my.turn/evals) holds them) with identical
  form and answer folded into the first: [{:k index-of-the-first :item it :times n}], in
  first-seen order. One pass."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector [:map [:k :int] [:item :map] [:times pos-int?]]]]}
  [items]
  (let [{:keys [order by]} (reduce (fn [{:keys [order by] :as acc} [k it]]
                                     (let [key [(:form it) (:tag it) (:printed it) (:out it)]]
                                       (if (by key)
                                         (update-in acc [:by key :times] inc)
                                         {:order (conj order key) :by (assoc by key {:k k :item it :times 1})})))
                                   {:order [] :by {}} (map-indexed vector items))]
    (mapv by order)))

(defn- shown-eval
  "One evaluation as the transcript shows it: its form, its live value (else its printed
  answer), its output; an error keeps :tag :err. A sub-map of the item, so every cut's path
  reads the item itself."
  {:malli/schema [:=> [:cat :map] :map]}
  [item]
  (cond-> (select-keys item [:form :tag :value :printed :out])
    (contains? item :value) (dissoc :printed)
    (= :ret (:tag item)) (dissoc :tag)))

(defn shown-evals
  "The last evaluations (folded) as the model reads them: each folded item printed by the
  one printer within an equal share of last-evals-budget, its cuts reading through
  (nth (my.turn/evals) k): [{:times n :text :cuts}] (the printer's answer)."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [folded]
  (let [share (max 32 (quot last-evals-budget (max 1 (count folded))))]
    (vec (for [{:keys [k item times]} folded
               :let [r (print/print-value (shown-eval item) {:handle (list 'nth '(my.turn/evals) k) :budget share})]]
           (assoc (if (:text r) r {:text (:seon.error/message r) :cuts []}) :times times)))))

(defn- transcript
  "The shown evaluations as one text, a repeated one headed by how many times it repeated."
  {:malli/schema [:=> [:cat [:vector :map]] :string]}
  [folded]
  (str/join "\n" (for [{:keys [times text]} (shown-evals folded)]
                   (str (when (> times 1) (str ";; " times "x, the same form and answer\n")) text))))

(defn- refused
  "The done rule's refusals as the agent reads them, beyond its own red proofs (shown with
  the proofs): a red test names its failures, a contract gap its var, a design step that
  has not improved its plan the call that does."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/refusal] [:vector :map]] [:vector :map]]}
  [refusals reds]
  (let [rs (remove #(= :proof-red (:refusal %)) refusals)
        gaps (vec (keep #(when (= :contract-gap (:refusal %)) (:symbol %)) rs))
        unmerged (vec (keep #(when (= :unmerged (:refusal %)) (:key %)) rs))]
    ;; a contract gap and an unmerged entry say one thing about many names: one entry each,
    ;; its names a list the printer budgets
    (vec (concat
           (for [{:keys [refusal symbol]} rs :when (not (#{:contract-gap :unmerged} refusal))]
             (case refusal
               (:regression :stray-red) (cond-> {:refusal refusal :test symbol}
                                          (seq (filter #(= symbol (:test %)) reds))
                                          (assoc :failures (mapv failure (filter #(= symbol (:test %)) reds))))
               :no-design {:refusal refusal :unchanged :seon.task/plan
                           :do '(my.task/update! :seon.task/plan str "\n\n" "<what the implement step must do>")}
               {:refusal refusal}))
           (when (seq gaps) [{:refusal :contract-gap :vars gaps}])
           (when (seq unmerged) [{:refusal :unmerged :keys unmerged}])))))

(defn- near
  "Where position at of text is: its line's number and its column (1-based), and the
  whole line (the printer budgets it with the block)."
  {:malli/schema [:=> [:cat :string :int] [:map [:line pos-int?] [:column pos-int?] [:text :string]]]}
  [text at]
  (let [at (min at (count text))
        start (if (pos? at) (inc (or (str/last-index-of text "\n" (dec at)) -1)) 0)
        end (or (str/index-of text "\n" at) (count text))]
    {:line (inc (count (filter #(= \newline %) (subs text 0 start)))) :column (inc (- at start))
     :text (subs text start end)}))

(defn last-reply
  "What happened to the agent's last reply: cut at the token limit, and each delimiter the
  reader repaired (what it dropped or inserted, and where: the line and column in source,
  the reply as the reader gave it to the REPL, and that line), or nil."
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
  from; the branch's unmerged entries (base, ours, theirs); what
  happened to the last reply; the last evaluations; turns left."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :map] :map]}
  [db t {:keys [results reds last-items turn max-turns unmerged truncated? refusals run-errors source repairs]}]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        cites (ref/references db (str plan "\n" (:seon.task/instructions t)))
        refs (into [] (comp (map :seon.ref/name) (filter qualified-symbol?)) cites)
        sections (into [] (comp (filter #(or (simple-symbol? (:seon.ref/name %)) (and (:seon.ref/path %) (not (:seon.ref/problem-kind %)))))
                                (map #(cited-view db %)))
                       cites)]
    (cond-> {:task (assoc (select-keys t [:seon.task/id :seon.task/kind :seon.task/instructions])
                          ;; the plan as it accumulated along :needs (prerequisites first)
                          :seon.task/plan plan)
             :turn (str (inc turn) " of " max-turns)
             ;; a passing proof is its status; a red or unrun one shows its source, its failures
             ;; and the program functions it calls; one not defined yet says so
             :proofs (vec (for [p (sort-by str proofs)
                                :let [src (source-of db p)
                                      ;; no result: not written yet, the run failed as a whole, or it asserted nothing
                                      st (get results p (cond (nil? src) :undefined (seq run-errors) :not-run :else :no-assertions))
                                      fs (filter #(= p (:test %)) reds)]]
                            (cond-> {:test p :status st}
                              (and src (not= :pass st)) (assoc :source src)
                              (seq fs) (assoc :failures (mapv failure fs))
                              (and src (not= :pass st) (seq (callees db p))) (assoc :calls (callees db p)))))
             ;; a reference already shown as a proof is not shown twice
             :references (mapv #(resolve-ref db %) (remove (set proofs) refs))}
      (seq sections) (assoc :sections sections)
      (seq (refused (vec refusals) (vec reds))) (assoc :refused (refused (vec refusals) (vec reds)))
      (seq run-errors) (assoc :test-run {:failed (vec run-errors)})
      (last-reply truncated? source (vec repairs)) (assoc :your-last-reply (last-reply truncated? source (vec repairs)))
      (:seon.task/evidence t) (assoc :evidence (:seon.task/evidence t))
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :kind :base :ours :theirs]) unmerged))
      (seq last-items) (assoc :last-evals (fold-evals last-items)))))

(defn evaluations-text
  "One turn's evaluations (as (my.turn/evals) holds them) as the model reads them: folded, each
  an equal share of the evals budget."
  {:malli/schema [:=> [:cat [:sequential :map]] :string]}
  [items] (str "Your evaluations:\n" (transcript (fold-evals items))))

(defn- kept-turns
  "The earlier turns the history budget keeps, whole, in order: the newest always, then older
  ones while they fit. A turn costs its reply's and its evaluations' tokens (4 characters a
  token, as seon.model/estimate-tokens)."
  {:malli/schema [:=> [:cat [:vector [:map [:reply :string] [:evals :string]]] :int] [:vector :map]]}
  [turns budget]
  (let [cost #(quot (+ (count (:reply %)) (count (:evals %))) 4)
        [newest & older] (rseq turns)]
    (->> older
         (reduce (fn [[kept spent] t] (let [s (+ spent (cost t))] (if (<= s budget) [(conj kept t) s] (reduced [kept spent]))))
                 [[newest] (cost newest)])
         first rseq vec)))

(defn messages
  "The model request's messages: the system prompt; then the conversation so far, as a REPL
  keeps its scrollback (the first turn's context, then each earlier turn's reply and what its
  forms evaluated to, whole turns within the history budget, the newest kept); then the current
  block printed by the one value printer under the context's budget, breadth first, each cut
  naming the form that reads its rest. turns: [{:context :reply :evals}] of this attempt, oldest
  first (:context only on the first)."
  {:malli/schema [:function [:=> [:cat :map] [:vector :seon.model/message]]
                  [:=> [:cat :map [:vector :map]] [:vector :seon.model/message]]]}
  ([block] (messages block []))
  ([block turns]
   (let [current (cond-> (print/text (dissoc block :last-evals) (:context print/budgets))
                   (and (empty? turns) (:last-evals block))
                   (str "\n\nYour last evaluations:\n" (transcript (:last-evals block))))
         kept (when (seq turns) (kept-turns (vec turns) (cfg/value :seon.config.render/history-budget)))
         dropped (- (count turns) (count kept))]
     (if (empty? turns)
       [{:role "system" :content system-prompt} {:role "user" :content current}]
       (into [{:role "system" :content system-prompt}
              {:role "user" :content (cond-> (:context (first turns))
                                       (pos? dropped) (str "\n\n;; " dropped " earlier turns are left out here (the history budget); (my.turn/evals) holds the last turn's"))}]
             (concat
               (mapcat (fn [[t nxt]] (cond-> [{:role "assistant" :content (:reply t)}]
                                       nxt (conj {:role "user" :content (:evals t)})))
                       (partition-all 2 1 kept))
               [{:role "user" :content (str (:evals (peek kept)) "\n\nThe current state:\n" current)}]))))))
