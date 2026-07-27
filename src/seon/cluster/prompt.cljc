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
  2. the agent's namespace — where its `defn`s land, so the agent can
     name its own work;
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
    vanish.

  Nothing about this is stored, so nothing about it can go stale: the
  warning is present exactly while the facts that cause it are."
  (:require [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/prompt.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

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
  [db request]
  (throw (ex-info "awaits implementation" {::fn `prompt})))
