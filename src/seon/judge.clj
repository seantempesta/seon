(ns seon.judge
  "The first-move judge (DECISIONS, No live agent runs until the context has a format agents
  respond well to): a task's first-turn context sent to the model once, and
  the reply's first move scored from the compiler's facts, never by another model. A score
  is a :seon.judge fact on main keyed by the digest of the request it answers, so an
  unchanged context is never asked twice. my.root/first-moves! runs a pass; the reply is
  analyzed in a disposable agent (first-move), since analysis runs a reply's ns form."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.context :as context] [seon.source :as source]
            [seon.reader :as reader] [seon.docs :as docs] [seon.step :as step] [seon.ref :as ref] [seon.edn :as sedn] [seon.error :as err] [seon.config :as config]
            [datahike.api :as d]))

(defn request
  "Task t's first-turn model request on db, as seon.turn/act! sends it at turn 0: the
  messages seon.context/render makes for a fresh agent's run whose proofs have no result yet
  (the agent and its run written on a speculative db, d/with: nothing stored), and the model
  and thinking mode the task resolves to. A fresh run's status has no verdicts yet: a proof
  not written reads :undefined, so the judge's tasks are ones whose proofs are not written."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task] [:map [:messages [:vector :seon.model/message]] [:model :string] [:thinking :any]]]}
  [db t]
  (let [id (:seon.task/id t)
        k (str id "/first-move") run (str id "#first-move")
        db (:db-after (d/with db [{:seon.agent/key k} {:seon.run/id run :seon.run/task [:seon.task/id id] :seon.run/agent [:seon.agent/key k]}]))]
    {:messages (:messages (context/render db {:commit (d/commit-id db) :task id :run run :turn 0
                                              :max-turns (config/value :seon.config.agent/max-turns) :origin :model :agent k}))
     :model (:value (config/resolve :seon.config.ai/model {:task t}))
     :thinking (:value (config/resolve :seon.config.ai/thinking {:task t}))}))

(defn digest
  "The request's digest: the record's key, so the same context, model and mode are scored once."
  {:malli/schema [:=> [:cat :map] :string]}
  [req] (err/digest (sedn/encode req)))

(defn subject
  "What task t's first move is judged against, from its text on db: the vars its plan and
  instructions cite (:cited), those whose source the context shows (:shown, cited at code
  depth), its proofs, and the namespace a reply is read in (its first proof's)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task]
                  [:map [:cited [:set :qualified-symbol]] [:shown [:set :qualified-symbol]] [:proofs [:set :qualified-symbol]] [:ns :symbol]]]}
  [db t]
  (let [cs (ref/references db (str (:seon.task/plan t) "\n" (:seon.task/instructions t)))
        proofs (set (:seon.task/proofs t))]
    {:cited (set (:seon.task/references t))
     :shown (into #{} (comp (filter #(= :code (:seon.ref/depth %))) (map :seon.ref/name) (filter qualified-symbol?)) cs)
     :proofs proofs
     :ns (if-let [p (first (sort proofs))] (symbol (namespace p)) (:seon.task/ns t))}))

(defn form-facts
  "What the compiler says each form of a reply's eval code does, read and analyzed in
  namespace nsym as loading would (seon.source/analyze-form: its ns form runs, so call this
  only in a disposable process, first-move's agent), with *read-eval* off: per form its
  :defs (the vars it defines) and :refs (the vars it references, its quoted data's and its
  macros' included), or :error, :seon.judge/uncompiled, for a form that does not compile
  (an unresolved name) or code that does not read: the model's text is the outside world
  (docs/failure-points.md)."
  {:malli/schema [:=> [:cat :symbol :string] [:vector :map]]}
  [nsym code]
  (let [uncompiled (fn [e] {:error (contract/error :seon.judge/uncompiled #'seon.judge/form-facts (step/error-msg e) {})})]
    (try
      (vec (for [{:keys [form ns]} (:forms (binding [*read-eval* false] (source/read-forms nsym (:text (reader/forgive code)))))]
             (try (let [{:keys [units]} (source/analyze-form (if (and (seq? form) (= 'ns (first form))) nsym (or ns nsym)) form)]
                    {:defs (into #{} (comp (mapcat :defs) (map :var)) units) :refs (into #{} (mapcat :refs) units)})
                  (catch Exception e (uncompiled e)))))
      (catch Exception e [(uncompiled e)]))))

(def ^:private reads
  "The vars a read of a definition calls: its source (an agent's clojure.repl/source expands
  to my.agent/source-fn, a plain REPL's to clojure.repl/source-fn), its doc."
  '#{clojure.repl/source clojure.repl/source-fn my.agent/source-fn clojure.repl/print-doc})

(defn score
  "First move facts (form-facts of each eval call of the first reply) against the task's
  subject: :acted (a form defines a var, or rewrites the plan, rather than only reading),
  :right-var (a form that is no read names a cited var), :right-test (a form defines a
  proof, or one that is no read names one), :waste (the shown vars it reads again: their
  source stands in the context), :uncompiled (its forms that do not compile), and :green
  (it defines a cited var or a proof, or rewrites the plan: the act is on the task)."
  {:malli/schema [:=> [:cat [:map [:cited [:set :qualified-symbol]] [:shown [:set :qualified-symbol]] [:proofs [:set :qualified-symbol]]]
                       [:vector :map]]
                  [:map [:acted :boolean] [:right-var :boolean] [:right-test :boolean] [:waste :int] [:uncompiled :int] [:green :boolean]]]}
  [{:keys [cited shown proofs]} facts]
  (let [ok (remove :error facts)
        read? (fn [f] (boolean (some reads (:refs f))))
        ;; a form that defines is an act whatever else it reads
        acts (filter #(or (seq (:defs %)) (not (read? %))) ok)
        plan? (fn [f] (contains? (:refs f) 'my.task/update!))
        names? (fn [s f] (boolean (some s (concat (:refs f) (:defs f)))))]
    {:acted (boolean (some #(or (seq (:defs %)) (plan? %)) acts))
     :right-var (boolean (some #(names? cited %) acts))
     :right-test (boolean (or (some #(some proofs (:defs %)) ok) (some #(names? proofs %) acts)))
     :waste (count (into #{} (comp (filter read?) (mapcat :refs) (filter shown)) ok))
     :uncompiled (count (filter :error facts))
     :green (boolean (some #(or (some (into cited proofs) (:defs %)) (plan? %)) acts))}))

(defn first-move
  "The score of a first reply's eval calls (their code) against subject, each call read in
  subject's namespace. Runs in the disposable agent my.root/first-moves! spawns."
  {:malli/schema [:=> [:cat :map [:vector :string]] :map]}
  [subject calls]
  (score subject (into [] (mapcat #(form-facts (:ns subject) %)) calls)))

(defn scorer
  "The digest of the scoring as main's program records it: the recorded sources of subject,
  form-facts, reads, score and first-move (a var with no recorded source digests as nil). A
  score made by other scoring is made again from its fact's calls, with no model call. It
  covers these definitions, not what they call (seon.source's reader and analysis), and the
  program as main records it, which is what the scoring agent forked from main runs."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (err/digest (sedn/encode (mapv #(docs/source-of db %) '[seon.judge/subject seon.judge/form-facts seon.judge/reads seon.judge/score seon.judge/first-move]))))

(defn fact
  "The :seon.judge fact of task id's first move: its request's digest, the version that
  served the context, the reply's calls, the score and the usage as the provider reported it."
  {:malli/schema [:=> [:cat :string :seon.task/id :map [:vector :string] :map :map [:maybe number?]] :map]}
  [dg id version calls sc usage usd]
  (merge usage
         {:seon.judge/digest dg :seon.judge/task id
          :seon.judge/score (cond-> (assoc sc :version version :calls calls) usd (assoc :usd usd))}))
