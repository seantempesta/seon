(ns seon.cluster.prompt
  "The prompt is a projection of the database, not a stored artifact.

  CONTRACT LAYER (drafted + ORCHESTRATOR-SEALED 2026-07-27 — N3,
  package 1, from n3-plan §7.2). Nothing here is implemented: every
  body throws `awaits implementation`.

  A pure function of a database value and an agent. Nothing is stored,
  nothing is cached, and the context-block machinery is a later rung —
  reaching for it here is how a rung overruns. For N3 the prompt is
  three things:

  1. the trigger's content — what the agent was asked;
  2. the agent's namespace — where its `defn`s land, stated as a fact
     about where evaluation ALREADY happens rather than as something to
     arrange. The first live drive's model read `your namespace is X`
     and dutifully emitted `(in-ns X)`, which failed; the fix was to
     evaluate there by construction (`seon.sci.eval/agent-namespace`,
     the one derivation shared with the evaluator) and to say so;
  3. THE INTERRUPTED WARNING, when a prior run was cut.

  THE WARNING IS THE WHOLE RESUME PRESENTATION. One derived sentence,
  never per-eval markers (the s3 crash model), and it has two sources
  because a crash has two shapes:

  - a run whose fold was cut mid-plan: N2's `interrupted-warning`
    derives the ordinal and how many results are missing
    (`src/seon/cluster/run.cljc:102-138`). The honest wording is that
    the interrupted form's effect MAY have happened — rows 6 and 7 of
    the crash walk are indistinguishable from the facts, and claiming
    otherwise would be a lie the agent then reasons from;
  - a run cut BEFORE its plan existed: the paid model call was lost and
    nothing re-called it (the night ruling). There are no receipts to
    derive from, so the warning comes from the settled run itself. An
    agent that is never told about this case simply sees its request
    vanish;
  - a run that ENDED before its plan existed because the turn failed —
    a lost model call, an unreadable reply. `:seon.cluster.run/error`
    carries why, and the next prompt says it. The live drive is the
    argument: an error value that only existed in a returned map left
    an operator reproducing the call by hand to find out what happened.

  Nothing about this is stored, so nothing about it can go stale: the
  warning is present exactly while the facts that cause it are."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster.run :as run]
            [seon.sci.eval :as sci.eval]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/prompt.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The pieces, each derived
;;; ---------------------------------------------------------------------------

(defn- trigger-content
  [db message-id]
  (d/q '[:find ?content .
         :in $ ?message-id
         :where
         [?message :seon.cluster.message/id ?message-id]
         [?message :seon.cluster.message/content ?content]]
       db message-id))

(defn- latest-run
  "The agent's most recently opened run, pulled whole, or nil.
  The warning is about the LAST run, not any run: an agent that was cut
  once, recovered, and worked for a week is not still interrupted."
  [db agent-id]
  (->> (d/q '[:find [(pull ?run [*]) ...]
              :in $ ?agent-id
              :where
              [?agent :seon.cluster.agent/id ?agent-id]
              [?run :seon.cluster.run/agent ?agent]]
            db agent-id)
       (sort-by #(inst-ms (:seon.cluster.run/opened-at %)))
       last))

(defn- run-receipts
  [db run-id]
  (d/q '[:find [(pull ?receipt [*]) ...]
         :in $ ?run-id
         :where
         [?run :seon.cluster.run/id ?run-id]
         [?receipt :seon.cluster.eval/run ?run]]
       db run-id))

(defn- run-forms
  [db run-id]
  (d/q '[:find [(pull ?form [*]) ...]
         :in $ ?run-id
         :where
         [?run :seon.cluster.run/id ?run-id]
         [?form :seon.cluster.run.form/run ?run]]
       db run-id))

(defn- interrupted-sentence
  "The ONE warning sentence for `agent-id`, or nil when nothing was cut.
  Two shapes, one sentence — and both say MAY, because rows 6 and 7 of
  the crash walk are indistinguishable from the facts and a confident
  claim would be a lie the agent then reasons from."
  [db agent-id]
  (when-let [previous (latest-run db agent-id)]
    (let [run-id (:seon.cluster.run/id previous)]
      (if-let [cut (run/interrupted-warning (run-forms db run-id)
                                            (run-receipts db run-id))]
        (str "Your previous run was interrupted at form "
             (:seon.cluster.eval/ordinal cut)
             ". That form's effect may have happened; "
             (:seon.cluster.run/missing-results cut)
             " result(s) are missing. Nothing was retried — adapt from here.")
        ;; no receipts to derive from: the run was cut before its plan
        ;; existed, so the model call was lost and nothing re-called it
        (cond
          ;; the turn failed for a reason we recorded — say the reason
          (:seon.cluster.run/error previous)
          (str "Your previous request did not run: "
               (:seon.cluster.run/error previous)
               " Nothing was retried, and nothing you asked for ran.")

          (and (nil? (:seon.cluster.run/plan-digest previous))
               (some? (:seon.cluster.run/closed-at previous)))
          (str "Your previous request was interrupted before you replied, "
               "and nothing was retried. Nothing you asked for ran."))))))

(defn- refuse!
  [rule data]
  (throw (ex-info (str "prompt refused: " (name rule))
                  (assoc data :seon.error/kind ::refused ::rule rule))))

;;; ---------------------------------------------------------------------------
;;; Contract
;;; ---------------------------------------------------------------------------

(defn prompt
  "The prompt for one agent answering one trigger, derived from `db`.
  Pure and total: an agent with no history and a clean trigger gets the
  trigger's content and its namespace; an agent whose last run was cut
  gets exactly one additional warning sentence. Refuses `::no-trigger`
  when the named message does not exist — a prompt with nothing to
  answer is a caller bug, not an agent outcome."
  {:malli/schema [:=> [:cat :any :seon.cluster.prompt/request]
                  :seon.cluster.prompt/text]}
  [db {:keys [:seon.cluster.agent/id :seon.cluster.message/id]
       :as request}]
  (let [agent-id (:seon.cluster.agent/id request)
        message-id (:seon.cluster.message/id request)
        content (or (trigger-content db message-id)
                    (refuse! ::no-trigger request))]
    (->> [(str "You are agent " agent-id
               ". Your namespace is my.agents." agent-id
               " — defns you write land there.")
          (interrupted-sentence db agent-id)
          (str "You have been asked:\n\n" content)
          (str "Reply with Clojure forms to run, in order. "
               "Finish with (my.run/complete \"your reply\") when you are "
               "done, or (my.run/wait \"why\") to pause this run.")]
         (remove nil?)
         (str/join "\n\n"))))
