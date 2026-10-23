(ns seon.judge
  "The first-move judge (owner, 2026-10-23: no live agent runs until the context has a
  format agents respond well to): a task's first-turn context sent to the model once, and
  the reply's first move scored from the compiler's facts, never by another model. A score
  is a :seon.judge fact on main keyed by the digest of the request it answers, so an
  unchanged context is never asked twice. my.root/first-moves! runs a pass."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.context :as context] [seon.source :as source]
            [seon.program :as program] [seon.reader :as reader] [seon.task :as task] [seon.edn :as sedn]
            [seon.error :as err] [seon.config :as config] [datahike.api :as d]))

(defn request
  "Task id's first-turn model request on db, as seon.turn/act! sends it: the messages
  seon.context/render makes for turn 0 of a fresh attempt whose proofs have no result yet
  (the turn loop runs them first; an unwritten proof reads :undefined either way), and the
  model and thinking mode in force."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] [:map [:messages [:vector :seon.model/message]] [:model :string]]]}
  [db id]
  {:messages (:messages (context/render db {:commit (d/commit-id db) :task id :attempt (str id "#first-move") :turn 0
                                            :max-turns (config/value :seon.config.agent/max-turns) :origin :model
                                            :observed {:results {} :reds []}}))
   :model (config/value :seon.config.ai/model)
   :thinking (config/value :seon.config.ai/thinking)})

(defn digest
  "The request's digest: the record's key, so the same context, model and mode are scored once."
  {:malli/schema [:=> [:cat :map] :string]}
  [req] (err/digest (sedn/encode req)))

(defn form-facts
  "What the compiler says each form of a reply's eval code does, analyzed in namespace nsym
  (the task's proof namespace, whose aliases a reply uses) without running it: per form its
  :defs (the vars it defines) and :refs (the vars it references, the ones its quoted data
  names included), or :error, :seon.judge/uncompiled, for a form that does not compile here
  (an unresolved name) or code that does not read: the model's text is the outside world
  (docs/failure-points.md)."
  {:malli/schema [:=> [:cat :symbol :string] [:vector :map]]}
  [nsym code]
  (let [uncompiled (fn [e] {:error (contract/error :seon.judge/uncompiled 'seon.judge/form-facts (or (ex-message e) (str (type e))) {})})]
    (try
      (vec (for [{:keys [form ns]} (:forms (source/read-forms nsym (:text (reader/forgive code))))]
             (try (let [a (program/unit-account (source/analyze (or ns nsym) form))]
                    {:defs (set (map :var (:defs a))) :refs (:refs a)})
                  (catch Exception e (uncompiled e)))))
      (catch Exception e [(uncompiled e)]))))

(def ^:private reads
  "The vars a read of a definition calls: its source, its doc."
  '#{clojure.repl/source clojure.repl/source-fn clojure.repl/print-doc})

(defn score
  "First move facts (form-facts of each eval call of the first reply) against task t: :acted
  (some form defines a var or rewrites the plan, not only reads), :right-var (a form names
  a var the plan cites), :right-test (a form defines or names a proof), :waste (the cited
  vars it reads again: the context shows them already), :uncompiled (its forms that do not
  compile), and :green (acted, on the right var or test)."
  {:malli/schema [:=> [:cat [:map [:seon.task/references {:optional true} :seon.task/references] [:seon.task/proofs {:optional true} :seon.task/proofs]]
                       [:vector :map]]
                  [:map [:acted :boolean] [:right-var :boolean] [:right-test :boolean] [:waste :int] [:uncompiled :int] [:green :boolean]]]}
  [t facts]
  (let [refs (into #{} (mapcat :refs) facts)
        defs (into #{} (mapcat :defs) facts)
        cited (set (:seon.task/references t))
        proofs (set (:seon.task/proofs t))
        acted (boolean (or (seq defs) (refs 'my.task/update!)))
        right-var (boolean (some cited (concat refs defs)))
        right-test (boolean (or (some proofs (concat refs defs)) (some (set (map name proofs)) (map name defs))))
        waste (count (into #{} (comp (filter #(some reads (:refs %))) (mapcat :refs) (filter cited)) facts))]
    {:acted acted :right-var right-var :right-test right-test :waste waste :uncompiled (count (filter :error facts))
     :green (and acted (or right-var right-test))}))

(defn fact
  "The :seon.judge fact of task id's first move: its request's digest, the version that
  served the context, the reply's calls, the score and the usage as the provider reported it."
  {:malli/schema [:=> [:cat :string :seon.task/id :map [:vector :string] :map :map [:maybe number?]] :map]}
  [dg id version calls sc usage usd]
  (merge usage
         {:seon.judge/digest dg :seon.judge/task id
          :seon.judge/score (cond-> (assoc sc :version version :calls calls) usd (assoc :usd usd))}))
