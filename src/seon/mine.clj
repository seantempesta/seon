(ns seon.mine
  "The miner: one situation per agent step of a kept attempt, read-only from its branch, and the wasted next forms
  charged to the producer of what the agent was shown (self-improvement-loop.md, phases M1 and M2).

  A step is one model call, the eval request it answered; its evaluations are what the agent was shown, and the
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
  "The form classes that move a run forward; a model call holding none of them is a wasted call."
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
                                        (and (symbol? read-subject) (doc-heads (first form)))
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
   :seon.cluster.eval/diagnostics {:seon.cluster.eval/ns [:seon.ns/name]} {:seon.cluster.eval/run [:db/id]}])

(defn- attempts
  "The attempts `database` (a chain branch) holds: assignment, task symbol and references, and end."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or [:vector :map] :seon.error/value]]}
  [database]
  (let [rows (db/q '[:find [(pull ?a [:seon.task/assignment :seon.task.attempt/end
                                      {:seon.task/attempt-of [:seon.task/sym :seon.task/references]}]) ...]
                     :where [?a :seon.task/assignment _] [?a :seon.task/attempt-of _]]
                   database)]
    (if (refusal/error? rows) rows (vec (sort-by :seon.task/assignment rows)))))

(defn- model-calls
  "The model calls of agent `agent-id`, in opening order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or [:vector :map] :seon.error/value]]}
  [database agent-id]
  (let [rows (db/q '[:find [(pull ?call [:db/id :seon.turn/id {:seon.turn/opened-tx [:db/id]}]) ...] :in $ ?agent-id
                     :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent] [?call :seon.turn/attempts _]]
                   database agent-id)]
    (if (refusal/error? rows) rows (vec (sort-by #(get-in % [:seon.turn/opened-tx :db/id]) rows)))))

(defn- model-evaluations
  "The forms the model calls of agent `agent-id` evaluated, in writer order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or [:vector :map] :seon.error/value]]}
  [database agent-id]
  (let [rows (db/q '[:find [(pull ?e selector) ...] :in $ ?agent-id selector
                     :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent]
                     [?e :seon.cluster.eval/run ?call] [?e :seon.cluster.eval/origin :model]]
                   database agent-id evaluation-selector)]
    (if (refusal/error? rows) rows (vec (sort-by :db/id rows)))))

(defn steps
  "One situation per model call of `calls` (pulled, in opening order: `:db/id`, `:seon.turn/id`) of `attempt` (its
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
  "One situation per model call of `attempt` on `database`, its branch `branch`; `merged?` when main records it
  merged."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch :map :boolean]
                  [:or :seon.mine/situations :seon.error/value]]}
  [database branch {assignment :seon.task/assignment :as attempt} merged?]
  (let [agent-id (str "attempt/" assignment)
        calls (model-calls database agent-id)
        evaluations (model-evaluations database agent-id)]
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
