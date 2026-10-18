(ns seon.mine
  "The miner: one situation per agent step of a kept attempt, read-only from its branch, and the wasted next forms
  charged to the producer of what the agent was shown (self-improvement-loop.md, phases M1 and M2).

  A step is one call of the attempt's agent (a model call, or an outside agent's eval request); its evaluations are what the agent was shown, and the
  forms of the next call are what it did next. `situations` reads one branch, `kept-situations` every kept chain
  branch, and `rank` sums the charged waste per cause."
  (:require [clojure.string :as str]
            [edamame.core :as edamame]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.sci.admit :as admit]))

;;; CLASSES: what each model form did, read from its stored source and error (context-patterns.md §3).

(def ^:private read-heads
  '#{source doc find-doc clojure.repl/source clojure.repl/doc my.program/source my.program/doc my.task/current
     seon.context/current-state})

(def ^:private doc-heads '#{doc clojure.repl/doc my.program/doc})

(def ^:private search-heads
  '#{dir apropos clojure.repl/dir clojure.repl/apropos my.program/search my.program/callers my.program/ns-decls
     ns-publics ns-interns all-ns seon.db/q seon.db/pull})

(def ^:private progress-heads
  '#{def defn defn- defmacro defmulti defmethod deftest clojure.test/deftest seon.schema/register!
     my.program/ns-unmap! my.program/remove-ns! my.program/write-branch! my.task/update! my.edit/text})

(def ^:private wait-heads '#{my.turn/wait my.task/send-back! my.message/send})

(def productive
  "The form classes that move a run forward; a call holding none of them is a wasted call."
  #{:read :probe :progress :wait})

(def ^:private reader-options
  {:all true :read-cond :allow :features #{:clj}
   :auto-resolve (fn [ns-alias] (if (= :current ns-alias) 'user ns-alias))
   :readers (fn [_tag] identity)})

(defn- read-first
  "The first form `text` holds, read with edamame, or nil when it reads as none."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [text]
  (try (edamame/parse-string text reader-options) (catch Exception _ nil)))

(defn- read-all
  "Every form `text` holds, read with edamame, or nil when it does not read."
  {:malli/schema [:=> [:cat :string] [:maybe [:vector :seon.schema/value]]]}
  [text]
  (try (vec (edamame/parse-string-all text reader-options)) (catch Exception _ nil)))

(defn- form-head
  "The operator symbol of `form` and whether it is a bare `(ns x)`; nil when it is no list."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe [:tuple :symbol :boolean]]]}
  [form]
  (when (and (seq? form) (symbol? (first form)))
    [(first form) (= 2 (count form))]))

(defn- error-class
  "The class of a form's stored error text: an unresolved name, a contract refusal of its call shape, or another
  error. The evaluation stores its error as text only, so the text is all there is to read."
  {:malli/schema [:=> [:cat :string] [:enum :unresolved :contract-refusal :error]]}
  [error]
  (cond (some #(str/includes? error %) ["Unable to resolve symbol" "Could not find namespace" "No such namespace"
                                        "No such var"]) :unresolved
        (str/includes? error " refused ") :contract-refusal
        :else :error))

(defn classify
  "Each of `evaluations` (stored model forms in writer order, `:seon.cluster.eval/source` and maybe `/error`) with its
  `:seon.mine/class`: an error's class; `in-ns` or a bare `ns` is navigation; a read of source or doc is a read,
  or a re-read when the same form ran before; a search (a hand query of the database included); a definition is
  progress; a wait or send-back ends the turn; any other form repeated exactly is a repeat, else a probe."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [evaluations]
  (first
   (reduce (fn [[out seen] {source :seon.cluster.eval/source error :seon.cluster.eval/error :as evaluation}]
             (let [text (str/trim (or source ""))
                   [head bare?] (form-head (read-first text))
                   again? (contains? seen text)
                   form-class (cond (string? error) (error-class error)
                               (or (= 'in-ns head) (and (= 'ns head) bare?)) :navigation
                               (read-heads head) (if again? :re-read :read)
                               (search-heads head) (if again? :repeat :search)
                               (or (progress-heads head) (= 'ns head)) :progress
                               (wait-heads head) :wait
                               again? :repeat
                               :else :probe)]
               [(conj out (assoc evaluation :seon.mine/class form-class)) (conj seen text)]))
           [[] #{}]
           evaluations)))

;;; SHOWN: what one evaluation showed the agent, read from its stored facts.

(defn- quoted
  "`x` without its `quote`."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [x]
  (if (and (seq? x) (= 'quote (first x))) (second x) x))

(defn subject
  "What the read `form` names: the quoted symbol or keyword of a doc or source read; nil for any other form."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe :seon.mine/subject]]}
  [form]
  (when (and (seq? form) (read-heads (first form)))
    (let [x (quoted (second form))]
      (when (or (symbol? x) (keyword? x)) x))))

(defn- handles-in
  "The value handles (`result/...` symbols) `form` names, in reading order."
  {:malli/schema [:=> [:cat :seon.schema/value] [:vector :qualified-symbol]]}
  [form]
  (into [] (comp (filter #(and (qualified-symbol? %) (= "result" (namespace %)))) (distinct))
        (tree-seq coll? seq form)))

(defn- marker?
  "Is `x` a printed cut's omitted-count marker, a symbol `…N`, `…+N` or `…?` (qualified when it sits in a
  namespaced map, whose reader qualifies its keys)."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [x]
  (and (symbol? x) (str/starts-with? (name x) "…")))

(defn- cut
  "One cut from its `marker` and the continuation `form` the printer offered after it."
  {:malli/schema [:=> [:cat :symbol :seon.schema/value] :seon.mine/cut]}
  [marker form]
  (let [handle (first (handles-in form))
        path (some #(when (and (seq? %) (= 'get-in (first %))) (second (rest %))) (tree-seq coll? seq form))]
    (cond-> {:seon.mine/marker (name marker) :seon.mine/form form}
      handle (assoc :seon.mine/handle handle :seon.mine/path (if (vector? path) path [])))))

(defn cuts
  "The cuts the value printer made in the printed value `text`: each omitted-count marker followed by the continuation
  form that reads the rest, wherever it sits (after a string, inside a collection, or as a map entry), read back
  with edamame; :unreadable when the text does not read as forms."
  {:malli/schema [:=> [:cat :string] :seon.mine/cuts]}
  [text]
  (if-let [forms (read-all text)]
    (into [] (comp (filter sequential?)
                   (mapcat #(partition 2 1 %))
                   (keep (fn [[a b]] (when (and (marker? a) (seq? b)) (cut a b)))))
          (tree-seq coll? seq forms))
    :unreadable))

(defn- error-producer
  "Who made an evaluation's error: the `:seon.error/type` of its printed error map when its printed value reads as
  one, else of its first recorded diagnostic, else that diagnostic's `:seon.error/operation`, else its class."
  {:malli/schema [:=> [:cat :map] :seon.mine/error-producer]}
  [{shown :seon.eval/shown diagnostics :seon.cluster.eval/diagnostics form-class :seon.mine/class}]
  (let [value (when (string? shown) (read-first shown))
        attached (when (string? diagnostics) (read-first diagnostics))
        first-error (when (vector? attached) (first (filter map? attached)))
        named (or (when (map? value) (:seon.error/type value))
                  (:seon.error/type first-error)
                  (:seon.error/operation first-error))]
    (if (or (keyword? named) (qualified-symbol? named)) named form-class)))

(def ^:private printer
  "The value printer, the producer of every shown value without a declared render and of every cut."
  {:seon.mine/kind :printer :seon.mine/producer 'seon.render.value/show})

(def ^:private namespace-shown
  "The current-state block's `:seon.context/namespace`, which tells the agent the namespace it is in."
  {:seon.mine/kind :context :seon.mine/producer 'seon.context/current-state})

(defn- producer
  "The producer of what `entry` showed: its declared render, else the value printer."
  {:malli/schema [:=> [:cat :map] :seon.mine/cause]}
  [{renderer :seon.eval/renderer}]
  (if renderer {:seon.mine/kind :render :seon.mine/producer renderer} printer))

(defn- shown-entry
  "`evaluation` (a classed stored model form) as the agent was shown it, with the task `references` the current-state
  block resolves, and every producer of it as a cause candidate."
  {:malli/schema [:=> [:cat [:set :seon.schema/value] :map] :seon.mine/shown]}
  [references {id :seon.cluster.eval/id source :seon.cluster.eval/source error :seon.cluster.eval/error
               elisions :seon.eval/elisions shown :seon.eval/shown :as evaluation}]
  (let [form (when source (read-first source))
        read-subject (subject form)
        cut? (and (pos-int? elisions) (string? shown))
        typed (when error (error-producer evaluation))]
    (cond-> (merge (select-keys evaluation [:seon.cluster.eval/id :seon.cluster.eval/source :seon.eval/renderer
                                            :seon.eval/value-class :seon.eval/shown-tokens :seon.eval/value-tokens
                                            :seon.eval/elisions :seon.cluster.eval/error :seon.mine/class])
                   {:seon.mine/handle (admit/result-handle id)
                    :seon.mine/causes (cond-> [(producer evaluation)]
                                        (and cut? (:seon.eval/renderer evaluation)) (conj printer)
                                        typed (conj {:seon.mine/kind :error :seon.mine/producer typed})
                                        ;; a bare name is the agent's guess, resolved nowhere the miner can read
                                        (and (qualified-symbol? read-subject) (doc-heads (first form)))
                                        (conj {:seon.mine/kind :docstring :seon.mine/producer read-subject}))})
      cut? (assoc :seon.mine/cuts (cuts shown))
      typed (assoc :seon.mine/error-producer typed)
      read-subject (assoc :seon.mine/subject read-subject
                          :seon.mine/referenced? (contains? references read-subject)))))

;;; WASTE: a next form charged to the producer of what it repeats or digs into.

(defn- charge
  "The waste `entry` (one shown form, read as `form`, evaluated starting in namespace `start-ns`) is, judged against `seen`
  (what earlier forms showed, indexed by erring text, handle, read subject and text); nil when it is none."
  {:malli/schema [:=> [:cat :map :map :seon.schema/value [:maybe :symbol]] [:maybe :seon.mine/waste]]}
  [{:keys [errored by-handle by-subject by-text]} entry form start-ns]
  (let [text (str/trim (or (:seon.cluster.eval/source entry) ""))
        target (when (and (seq? form) (= 'in-ns (first form))) (quoted (second form)))
        failed (get errored text)
        dug (some by-handle (handles-in form))
        reread (or (some->> (:seon.mine/subject entry) (get by-subject))
                   (when (= :re-read (:seon.mine/class entry)) (get by-text text)))
        [rule earlier cause] (cond failed [:repeats-error failed {:seon.mine/kind :error
                                                                  :seon.mine/producer (:seon.mine/error-producer failed)}]
                                   (and target (= target start-ns)) [:redundant-in-ns nil namespace-shown]
                                   (:seon.mine/cuts dug) [:follows-cut dug printer]
                                   dug [:reads-handle dug (producer dug)]
                                   reread [:re-read reread (producer reread)])]
    (when rule
      (cond-> {:seon.mine/rule rule :seon.cluster.eval/id (:seon.cluster.eval/id entry) :seon.mine/cause cause}
        (:seon.cluster.eval/source entry) (assoc :seon.cluster.eval/source (:seon.cluster.eval/source entry))
        earlier (assoc :seon.mine/shown-by (:seon.cluster.eval/id earlier))))))

(defn- first-under
  "`index` with `entry` under `k`, unless `k` is nil or already held."
  {:malli/schema [:=> [:cat :map :seon.schema/value :map] :map]}
  [index k entry]
  (if (or (nil? k) (contains? index k)) index (assoc index k entry)))

(defn- seen-after
  "`seen` with `entry` added: by handle, by text when it first ran and when it first erred, and by read subject."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [seen entry]
  (let [text (str/trim (or (:seon.cluster.eval/source entry) ""))]
    (-> seen
        (update :by-handle assoc (:seon.mine/handle entry) entry)
        (update :by-text first-under text entry)
        (update :errored first-under (when (:seon.cluster.eval/error entry) text) entry)
        (update :by-subject first-under (:seon.mine/subject entry) entry))))

(defn- charged
  "Each of `entries` (an attempt's shown forms in order, its stored evaluations `evaluations` aligned) with its
  waste under `::waste`, judged against every form before it."
  {:malli/schema [:=> [:cat [:vector :map] [:vector :map]] [:vector :map]]}
  [entries evaluations]
  (first
   (reduce (fn [[out seen] [entry evaluation]]
             (let [source (:seon.cluster.eval/source entry)
                   waste (charge seen entry (when source (read-first source))
                                 (get-in evaluation [:seon.cluster.eval/ns :seon.ns/name]))]
               [(conj out (cond-> entry waste (assoc ::waste waste))) (seen-after seen entry)]))
           [[] {:errored {} :by-handle {} :by-subject {} :by-text {}}]
           (map vector entries evaluations))))

;;; SITUATIONS: one per step of each attempt a branch holds.

(def ^:private evaluation-selector
  [:db/id :seon.cluster.eval/id :seon.cluster.eval/source :seon.cluster.eval/error :seon.eval/renderer
   :seon.eval/value-class :seon.eval/shown-tokens :seon.eval/value-tokens :seon.eval/elisions :seon.eval/shown
   :seon.eval/duration-ms :seon.eval/allocated-bytes
   :seon.cluster.eval/diagnostics {:seon.cluster.eval/ns [:seon.ns/name]} {:seon.cluster.eval/run [:db/id]}])

(def ^:private call-selector
  [:db/id :seon.turn/id {:seon.turn/opened-tx [:db/id :db/txInstant]} {:seon.turn/closed-tx [:db/id :db/txInstant]}
   {:seon.turn/attempts [:seon.ai.usage/prompt-tokens :seon.ai.usage/completion-tokens :seon.ai.usage/cached-tokens
                         :seon.ai.attempt/error]}])

(defn- attempts
  "The attempts `database` (a chain branch) holds: assignment, task symbol and references, and end."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or [:vector :map] :seon.error/value]]}
  [database]
  (let [rows (db/q '[:find [(pull ?a [:seon.task/assignment :seon.task.attempt/end
                                      {:seon.task/attempt-of [:seon.task/sym :seon.task/references]}]) ...]
                     :where [?a :seon.task/assignment _] [?a :seon.task/attempt-of _]]
                   database)]
    (if (refusal/error? rows) rows (vec (sort-by :seon.task/assignment rows)))))

(defn- agent-calls
  "Every call of agent `agent-id`, in opening order: a model agent's model calls and an outside agent's eval requests
  alike, each with its provider attempts' usage when it made any."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or [:vector :map] :seon.error/value]]}
  [database agent-id]
  (let [rows (db/q '[:find [(pull ?call selector) ...] :in $ ?agent-id selector
                     :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent]]
                   database agent-id call-selector)]
    (if (refusal/error? rows) rows (vec (sort-by #(get-in % [:seon.turn/opened-tx :db/id]) rows)))))

(defn- agent-evaluations
  "The forms the calls of agent `agent-id` evaluated, whoever wrote them (the model or an outside agent), in writer
  order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or [:vector :map] :seon.error/value]]}
  [database agent-id]
  (let [rows (db/q '[:find [(pull ?e selector) ...] :in $ ?agent-id selector
                     :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent]
                     [?e :seon.cluster.eval/run ?call]]
                   database agent-id evaluation-selector)]
    (if (refusal/error? rows) rows (vec (sort-by :db/id rows)))))

(defn steps
  "One situation per call of `calls` (pulled, in opening order: `:db/id`, `:seon.turn/id`) of `attempt` (its
  assignment, end and task with its references) on `branch`, from the model `evaluations` those calls made (pulled
  with their source, error, printed value and its facts, starting namespace and call, in writer order); `merged?`
  when main records the attempt merged."
  {:malli/schema [:=> [:cat :seon.store/branch :map :boolean [:vector :map] [:vector :map]] :seon.mine/situations]}
  [branch {assignment :seon.task/assignment end :seon.task.attempt/end task :seon.task/attempt-of} merged? calls evaluations]
  (let [evaluations (classify evaluations)
        references (set (:seon.task/references task))
        entries (charged (mapv #(shown-entry references %) evaluations) evaluations)
        by-call (group-by ::call (map #(assoc %2 ::call (get-in %1 [:seon.cluster.eval/run :db/id])) evaluations entries))
        calls (vec calls)]
    (into []
          (map-indexed
           (fn [step call]
             (let [next-entries (get by-call (:db/id (get calls (inc step))))
                   waste (into [] (keep ::waste) next-entries)
                   shown (mapv #(dissoc % ::waste ::call) (get by-call (:db/id call)))]
               (cond-> {:seon.store/branch branch :seon.task/assignment assignment :seon.mine/merged? merged?
                        :seon.mine/step step :seon.turn/id (:seon.turn/id call)
                        :seon.mine/shown shown
                        :seon.mine/next (into [] (keep :seon.cluster.eval/source) next-entries)
                        :seon.mine/waste waste
                        :seon.mine/causes (into [] (distinct) (concat (mapcat :seon.mine/causes shown)
                                                                      (map :seon.mine/cause waste)))}
                 (:seon.task/sym task) (assoc :seon.task/sym (:seon.task/sym task))
                 end (assoc :seon.task.attempt/end end)))))
          calls)))

(defn- attempt-situations
  "One situation per call of `attempt`'s agent on `database`, its branch `branch`; `merged?` when main records it
  merged."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch :map :boolean]
                  [:or :seon.mine/situations :seon.error/value]]}
  [database branch {assignment :seon.task/assignment :as attempt} merged?]
  (let [agent-id (str "attempt/" assignment)
        calls (agent-calls database agent-id)
        evaluations (agent-evaluations database agent-id)]
    (cond (refusal/error? calls) calls
          (refusal/error? evaluations) evaluations
          :else (steps branch attempt merged? calls evaluations))))

(defn- merged?
  "Whether `main` records attempt `assignment` merged."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment] :boolean]}
  [main assignment]
  (let [row (db/pull main [:seon.task/outcome] [:seon.task/assignment assignment])]
    (= :merged (when (map? row) (:seon.task/outcome row)))))

(defn situations
  "One situation per agent step of every attempt chain branch `branch` holds, read on its head from `main` (main's
  database value, which says whether an attempt merged): what each step's eval request showed, the forms the agent
  wrote next, and those charged as waste with their causes. Empty when the store holds no such branch; a read's
  refusal when one refuses.

  ## Costs

  Per attempt one assignment query, one call query and one evaluation query; each form read once with edamame, and
  a printed value read only when it was cut or erred: proportional to the attempt's evaluations."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch] [:or :seon.mine/situations :seon.error/value]]}
  [main branch]
  (db/read-at main branch
              (fn [head]
                (let [held (if head (attempts head) [])]
                  (if (refusal/error? held)
                    held
                    (reduce (fn [out attempt]
                              (let [found (attempt-situations head branch attempt
                                                              (merged? main (:seon.task/assignment attempt)))]
                                (if (refusal/error? found) (reduced found) (into out found))))
                            []
                            held))))))

(defn kept-branches
  "The chain branches main `database` records that the store still holds, sorted: each open chain's
  `:seon.chain/branch` and each merged or closed attempt's `:seon.task/branch`."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or [:vector :seon.store/branch] :seon.error/value]]}
  [database]
  (let [chains (db/q '[:find [?b ...] :where [_ :seon.chain/branch ?b]] database)
        attempted (db/q '[:find [?b ...] :where [?a :seon.task/assignment _] [?a :seon.task/branch ?b]] database)
        held (db/branches database)]
    (cond (refusal/error? chains) chains
          (refusal/error? attempted) attempted
          :else (into [] (comp (filter held) (distinct)) (sort (concat chains attempted))))))

(defn kept-situations
  "The situations of every kept chain branch main `database` records (`kept-branches`, `situations`), or the first
  read's refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :seon.mine/situations :seon.error/value]]}
  [database]
  (let [branches (kept-branches database)]
    (if (refusal/error? branches)
      branches
      (reduce (fn [out branch]
                (let [found (situations database branch)]
                  (if (refusal/error? found) (reduced found) (into out found))))
              []
              branches))))

(defn rank
  "Each cause charged with waste in the situations `mined`, by wasted next forms and the distinct steps they came from, most
  first, with its count under each rule."
  {:malli/schema [:=> [:cat :seon.mine/situations] [:vector :seon.mine/rank-row]]}
  [mined]
  (let [charges (for [{assignment :seon.task/assignment step :seon.mine/step :as situation} mined
                      {cause :seon.mine/cause rule :seon.mine/rule} (:seon.mine/waste situation)]
                  {::cause cause ::rule rule ::step [assignment step]})]
    (->> (group-by ::cause charges)
         (map (fn [[cause rows]]
                {:seon.mine/cause cause
                 :seon.mine/wasted-forms (count rows)
                 :seon.mine/wasted-turns (count (into #{} (map ::step) rows))
                 :seon.mine/rules (frequencies (map ::rule rows))}))
         (sort-by (juxt (comp - :seon.mine/wasted-forms) (comp str :seon.mine/cause)))
         vec)))

;;; GRADE: one ended attempt, read on the branch its agent worked on, written once on main as its grade.

(defn- total
  "The sum of `k` over `xs`, absent values and negative (unmeasured) ones counting zero."
  {:malli/schema [:=> [:cat [:sequential :map] :keyword] :int]}
  [xs k]
  (transduce (keep #(let [x (get % k)] (when (and (int? x) (not (neg? x))) x))) + 0 xs))

(defn cached-share
  "The percent of `prompt` tokens the provider served from its prompt cache (`cached`), rounded; absent when no prompt
  token was counted."
  {:malli/schema [:=> [:cat nat-int? nat-int?] [:maybe nat-int?]]}
  [prompt cached]
  (when (pos? prompt) (Math/round (/ (* 100.0 cached) prompt))))

(defn grade-of
  "The grade of an attempt from its reads: `row` (its end, number and task), `ended` (its agent's end facts),
  `calls` (every call of its agent, in opening order) and `evaluations` (their forms, in writer order): how it ended,
  green when :done (the done rule passed), its calls (calls to green when green), its forms by class, calls holding no
  productive form, errors, tokens, wall time from the first call's opening to the last's close, the share of prompt
  tokens the provider's cache served, and the evaluations' summed time and allocation."
  {:malli/schema [:=> [:cat :seon.task/assignment [:maybe :map] [:maybe :map] [:vector :map] [:vector :map]] :map]}
  [assignment row ended calls evaluations]
  (let [evaluations (classify evaluations)
        by-call (group-by #(get-in % [:seon.cluster.eval/run :db/id]) evaluations)
        end (:seon.task.attempt/end row)
        green? (= :done end)
        opened (get-in (first calls) [:seon.turn/opened-tx :db/txInstant])
        closed (get-in (peek calls) [:seon.turn/closed-tx :db/txInstant])
        usage #(total (mapcat :seon.turn/attempts calls) %)
        share (cached-share (usage :seon.ai.usage/prompt-tokens) (usage :seon.ai.usage/cached-tokens))]
    (cond-> {:seon.competency/assignment assignment
             :seon.competency/agent (str "attempt/" assignment)
             :seon.competency/task (get-in row [:seon.task/attempt-of :seon.task/id])
             :seon.competency/end (or end :running)
             :seon.competency/green? green?
             :seon.competency/proofs (into {} (keep (fn [[k proof]] (when-let [n (get ended k)] [proof n])))
                                           {:seon.agent/end-green :green :seon.agent/end-red :red
                                            :seon.agent/end-pending :pending})
             :seon.competency/model-calls (count calls)
             :seon.competency/forms (count evaluations)
             :seon.competency/classes (frequencies (map :seon.mine/class evaluations))
             :seon.competency/wasted-calls (count (remove #(some (comp productive :seon.mine/class) (by-call (:db/id %)))
                                                          calls))
             :seon.competency/no-form-calls (count (remove #(by-call (:db/id %)) calls))
             :seon.competency/agent-errors (count (filter :seon.cluster.eval/error evaluations))
             :seon.competency/platform-errors (count (keep :seon.ai.attempt/error (mapcat :seon.turn/attempts calls)))
             :seon.competency/prompt-tokens (usage :seon.ai.usage/prompt-tokens)
             :seon.competency/cached-tokens (usage :seon.ai.usage/cached-tokens)
             :seon.competency/completion-tokens (usage :seon.ai.usage/completion-tokens)
             :seon.competency/eval-ms (total evaluations :seon.eval/duration-ms)
             :seon.competency/alloc-mb (/ (Math/round (/ (total evaluations :seon.eval/allocated-bytes) 1e5)) 10.0)}
      green? (assoc :seon.competency/calls-to-green (count calls))
      share (assoc :seon.competency/cached-share share)
      (and opened closed) (assoc :seon.competency/wall-ms (- (inst-ms closed) (inst-ms opened))))))

(def ^:private attempt-selector
  [:db/id :seon.task/assignment :seon.task.attempt/end :seon.task.attempt/number
   {:seon.task/attempt-of [:seon.task/id :seon.task/sym :seon.task/references]}])

(defn- attempt-reads
  "What grading attempt `assignment` reads on `database`, the branch its agent worked on: its row, its agent's end
  facts, its agent's calls and their evaluations, and when its end was written; or the first read's refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment] [:or :map :seon.error/value]]}
  [database assignment]
  (let [agent-id (str "attempt/" assignment)
        row (db/pull database attempt-selector [:seon.task/assignment assignment])
        ended (db/pull database [:seon.agent/end-green :seon.agent/end-red :seon.agent/end-pending] [:seon.agent/id agent-id])
        at (db/q '[:find ?at . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end _ ?tx]
                   [?tx :db/txInstant ?at]]
                 database assignment)
        calls (agent-calls database agent-id)
        evaluations (agent-evaluations database agent-id)]
    (or (some #(when (refusal/error? %) %) [row ended at calls evaluations])
        {::row row ::ended ended ::at at ::calls calls ::evaluations evaluations})))

(defn grade
  "Attempt `assignment`'s grade (`grade-of`), read on `database`, the branch its agent worked on; or a read's refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment] [:or :map :seon.error/value]]}
  [database assignment]
  (let [reads (attempt-reads database assignment)]
    (if (refusal/error? reads)
      reads
      (grade-of assignment (::row reads) (::ended reads) (::calls reads) (::evaluations reads)))))

(defn grade-entity
  "The grade fact of ended attempt `assignment`, read on `database`, the head of `branch` its agent worked on, with
  the program's Git commit `git-sha` when known: its turns, turns to green when done, wasted turns, form classes,
  waste by rule and by cause (`steps`, `rank`), tokens, cached share, wall time and when it ended; or a read's
  refusal.

  ## Costs

  The attempt's four reads, then each form read once: proportional to the attempt's evaluations."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch :seon.task/assignment [:maybe :seon.test.run/git-sha]]
                  [:or :seon.grade/grade :seon.error/value]]}
  [database branch assignment git-sha]
  (let [{row ::row calls ::calls evaluations ::evaluations at ::at :as reads} (attempt-reads database assignment)]
    (if (refusal/error? reads)
      reads
      (let [graded (grade-of assignment row (::ended reads) calls evaluations)
            mined (steps branch row false calls evaluations)
            ranked (rank mined)
            rules (frequencies (for [situation mined waste (:seon.mine/waste situation)] (:seon.mine/rule waste)))
            classes (:seon.competency/classes graded)]
        (cond-> {:seon.grade/assignment assignment :seon.grade/branch branch
                 :seon.grade/end (:seon.task.attempt/end row) :seon.grade/at at
                 :seon.grade/turns (:seon.competency/model-calls graded)
                 :seon.grade/wasted-turns (:seon.competency/wasted-calls graded)
                 :seon.grade/prompt-tokens (:seon.competency/prompt-tokens graded)
                 :seon.grade/cached-tokens (:seon.competency/cached-tokens graded)
                 :seon.grade/completion-tokens (:seon.competency/completion-tokens graded)}
          (:seon.competency/task graded) (assoc :seon.grade/task (:seon.competency/task graded))
          git-sha (assoc :seon.grade/git-sha git-sha)
          (:seon.competency/calls-to-green graded) (assoc :seon.grade/turns-to-green (:seon.competency/calls-to-green graded))
          (seq classes) (assoc :seon.grade/classes (set classes))
          (seq rules) (assoc :seon.grade/rules (set rules))
          (seq ranked) (assoc :seon.grade/waste (into #{} (map (fn [{cause :seon.mine/cause :as row}]
                                                                 {:seon.grade.waste/kind (:seon.mine/kind cause)
                                                                  :seon.grade.waste/producer (:seon.mine/producer cause)
                                                                  :seon.grade.waste/forms (:seon.mine/wasted-forms row)
                                                                  :seon.grade.waste/turns (:seon.mine/wasted-turns row)}))
                                                      ranked))
          (:seon.competency/cached-share graded) (assoc :seon.grade/cached-share (:seon.competency/cached-share graded))
          (:seon.competency/wall-ms graded) (assoc :seon.grade/wall-ms (:seon.competency/wall-ms graded)))))))

(defn ungraded
  "Every ended attempt main `database` holds no grade for, with the branch its agent worked on that `branches` (the
  store's) still holds: each of `chain-attempts` (`seon.task/chain-attempts`, the open chains' attempts) that has an
  end, read where its row names it worked, and each attempt main records ended (a merged chain's, or one main ran),
  on its `:seon.task/branch`; or a read's refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.registry/branches [:vector :map]]
                  [:or :seon.grade/ungraded :seon.error/value]]}
  [database branches chain-attempts]
  (let [graded (db/q '[:find [?k ...] :where [_ :seon.grade/assignment ?k]] database)
        on-main (db/q '[:find ?k ?b :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end _] [?a :seon.task/branch ?b]]
                      database)]
    (or (some #(when (refusal/error? %) %) [graded on-main])
        (let [graded (set graded)
              from-chains (for [{k :seon.task/assignment branch :seon.chain/branch end :seon.task.attempt/end} chain-attempts
                                :when (and end (not (graded k)))]
                            [k (or (db/read-at database branch
                                               #(when % (:seon.task/branch (db/pull % [:seon.task/branch] [:seon.task/assignment k]))))
                                   branch)])]
          ;; a chain attempt's own row names where it worked, so it wins over main's copy
          (into [] (keep (fn [[k b]] (when (and (not (graded k)) (contains? branches b))
                                       {:seon.task/assignment k :seon.store/branch b})))
                (into (sorted-map) (concat on-main from-chains)))))))

(defn running-sha
  "The Git commit of the published archive this JVM loaded (`data/source/<sha>`, the `seon.repository.root`
  property), or nil when it was not launched from one."
  {:malli/schema [:=> [:cat] [:maybe :seon.test.run/git-sha]]}
  []
  (let [sha (some-> (System/getProperty "seon.repository.root") java.io.File. .getName)]
    (when (and sha (re-matches #"[0-9a-f]{40}" sha)) sha)))

(defn grade-tx
  "The transaction data grading each of `ended` (`ungraded`) on main `database` under `git-sha`: one grade entity per
  attempt, read on its branch's head; or the first read's refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.grade/ungraded [:maybe :seon.test.run/git-sha]]
                  [:or [:vector :seon.grade/grade] :seon.error/value]]}
  [database ended git-sha]
  (reduce (fn [out {k :seon.task/assignment branch :seon.store/branch}]
            (let [entity (db/read-at database branch #(grade-entity % branch k git-sha))]
              (if (refusal/error? entity) (reduced entity) (conj out entity))))
          []
          ended))

;;; REPORT: the turns metric over every grade main holds.

(defn- quantile
  "The `q` quantile of `xs` by nearest rank, or nil when empty."
  {:malli/schema [:=> [:cat [:sequential :int] :double] [:maybe :int]]}
  [xs q]
  (when (seq xs)
    (let [sorted (vec (sort xs))]
      (nth sorted (max 0 (dec (long (Math/ceil (* q (count sorted))))))))))

(defn- quantiles
  "The median and p90 of the turns to green of `grades`."
  {:malli/schema [:=> [:cat [:sequential :map]] :seon.grade/quantiles]}
  [grades]
  (let [to-green (keep :seon.grade/turns-to-green grades)]
    (cond-> {}
      (seq to-green) (assoc :seon.grade/median (quantile to-green 0.5) :seon.grade/p90 (quantile to-green 0.9)))))

(defn- top
  "The `n` names of `pairs` ([name forms] ...) with the most forms summed, most first."
  {:malli/schema [:=> [:cat [:sequential [:tuple :seon.schema/value :int]] pos-int?] [:vector :seon.grade/top]]}
  [pairs n]
  (->> (reduce (fn [m [k forms]] (update m k (fnil + 0) forms)) {} pairs)
       (sort-by (juxt (comp - val) (comp str key)))
       (take n)
       (mapv (fn [[k forms]] {:seon.grade/name k :seon.grade.waste/forms forms}))))

(defn report
  "The turns metric over `grades` (pulled grade entities with their waste): the attempts and how many ended done, the
  median and p90 of turns to green, the same per program commit in the order each commit's first graded attempt
  ended (a grade naming no commit counts under :unknown), and the ten waste rules and producers that cost most wasted
  forms."
  {:malli/schema [:=> [:cat [:sequential :map]] :seon.grade/report]}
  [grades]
  (let [windows (->> (group-by #(:seon.grade/git-sha % :unknown) grades)
                     (map (fn [[commit in]]
                            {:seon.grade/commit commit
                             :seon.grade/from (first (sort (map :seon.grade/at in)))
                             :seon.grade/attempts (count in)
                             :seon.grade/green (count (filter :seon.grade/turns-to-green in))
                             :seon.grade/quantiles (quantiles in)
                             :seon.grade/median-turns (quantile (map :seon.grade/turns in) 0.5)}))
                     (sort-by (comp inst-ms :seon.grade/from))
                     vec)]
    {:seon.grade/attempts (count grades)
     :seon.grade/green (count (filter :seon.grade/turns-to-green grades))
     :seon.grade/quantiles (quantiles grades)
     :seon.grade/windows windows
     :seon.grade/top-rules (top (mapcat :seon.grade/rules grades) 10)
     :seon.grade/top-producers (top (for [g grades w (:seon.grade/waste g)] [(:seon.grade.waste/producer w) (:seon.grade.waste/forms w)]) 10)}))

(defn grades
  "Every grade main `database` holds, pulled with its waste; or the read's refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or [:vector :map] :seon.error/value]]}
  [database]
  (let [rows (db/q '[:find [(pull ?g [* {:seon.grade/waste [*]}]) ...] :where [?g :seon.grade/assignment _]] database)]
    (if (refusal/error? rows) rows (vec (sort-by :seon.grade/assignment rows)))))

(defn report-html
  "`:seon.render/html` of :seon.grade/report: the headline median and p90 of turns to green, a row per commit window,
  and the costliest waste rules and producers."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [{value :seon.render/value}]
  (let [q (:seon.grade/quantiles value)
        commit #(let [c (:seon.grade/commit %)] (if (string? c) (subs c 0 9) (name c)))]
    [:article {:class "seon-turns-report"}
     [:header
      [:h2 "Turns to green"]
      [:span (str "median " (:seon.grade/median q "-") " · p90 " (:seon.grade/p90 q "-") " · "
                  (:seon.grade/green value) " of " (:seon.grade/attempts value) " attempts green")]]
     (into [:table {:class "seon-turns-windows"}
            [:tr [:th "commit"] [:th "attempts"] [:th "green"] [:th "median to green"] [:th "p90"] [:th "median turns"]]]
           (map (fn [w] [:tr [:td (commit w)] [:td (str (:seon.grade/attempts w))] [:td (str (:seon.grade/green w))]
                         [:td (str (get-in w [:seon.grade/quantiles :seon.grade/median] "-"))]
                         [:td (str (get-in w [:seon.grade/quantiles :seon.grade/p90] "-"))]
                         [:td (str (:seon.grade/median-turns w))]]))
           (:seon.grade/windows value))
     (into [:ol {:class "seon-turns-waste"}]
           (map (fn [{n :seon.grade/name forms :seon.grade.waste/forms}] [:li (str n " · " forms " wasted forms")]))
           (concat (:seon.grade/top-rules value) (:seon.grade/top-producers value)))]))
