(ns seon.cluster.prompt
  "The prompt is a projection of the database, not a stored artifact.

  This contract layer is fully implemented and live-proven.

  A pure function of a database value and an agent. Nothing is stored,
  nothing is cached, and the context-block machinery is a later rung —
  reaching for it here is how a rung overruns. The prompt is five
  derived pieces, each present exactly while the facts that cause it
  are:

  1. the trigger's content — what the agent was asked, and WHO asked
     when the sender is another agent (`:seon.cluster.message/from`;
     its absence means the human or the error recorder, and then the
     prompt names no sender rather than inventing one);
  2. the agent's namespace — where its `defn`s land, stated as a fact
     about where evaluation ALREADY happens rather than as something to
     arrange. The first live drive's model read `your namespace is X`
     and dutifully emitted `(in-ns X)`, which failed; the fix was to
     evaluate there by construction (`seon.sci.eval/agent-namespace`,
     the one derivation shared with the evaluator) and to say so;
  3. THE INTERRUPTED WARNING, when a prior run was cut;
  4. THE POPULATION — the other agents in this cluster, so delegation
     is possible at all. Two sentences, derived from a query, and the
     minimum that makes `my.message/send` usable; anything richer about
     each peer belongs to the context-block rung;
  5. THE PAUSE NOTE the agent left itself, when its previous run ended
     in `my.run/wait`. This is the continuity a delegating agent needs
     and the only continuity it has: a new run has a fresh sci ctx and
     a freshly derived prompt, so a reply that says only \"25\" is
     unanswerable without the note that says what 25 was for.

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
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
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

(defn- trigger-sender
  "The agent that sent the trigger, or nil when it came from outside.
  ABSENCE IS THE ANSWER, not a missing value to fill in: a message with
  no `from` is the human's or the error recorder's, and the prompt says
  \"you have been asked\" rather than naming a sender it would have to
  invent."
  [db message-id]
  (d/q '[:find ?agent-id .
         :in $ ?message-id
         :where
         [?message :seon.cluster.message/id ?message-id]
         [?message :seon.cluster.message/from ?agent]
         [?agent :seon.cluster.agent/id ?agent-id]]
       db message-id))

(defn- peers
  "Every OTHER agent in this cluster, by id, ordered.
  Derived on every prompt, so an agent created a minute ago is
  addressable a minute later and one that never existed is never named.
  This is the whole of \"who can I talk to\" for now: the context-block
  machinery that would say more about each of them is a later rung, and
  a prompt that reached for it here would be that rung overrunning."
  [db agent-id]
  (->> (d/q '[:find [?id ...]
              :where [?agent :seon.cluster.agent/id ?id]]
            db)
       (remove #{agent-id})
       sort
       vec))

(defn- previous-run
  "The agent's most recent run OTHER than the one being planned, or nil.
  The warning is about the LAST run, not any run: an agent cut once,
  recovered, and working for a week is not still interrupted.

  EXCLUDING THE RUN BEING PLANNED is the whole correction, and it
  was measured rather than reasoned: the loop CLAIMS BEFORE it calls the
  model, so by the time a prompt is derived the agent's newest run is
  the one this prompt is for. Taking the newest run therefore inspected
  a run with no receipts and no error and found nothing to warn about —
  the warning was derivable before the run opened and gone after it, so
  the live agent never saw it. The run being planned is exactly the one
  the agent POINTER names, so excluding that leaves the run the warning
  is actually about. When no run is open (a prompt derived before the
  claim, as a probe or a test does) nothing is excluded and the newest
  run is the previous one — the same answer by the same rule."
  [db agent-id]
  (let [current (d/q '[:find ?id .
                       :in $ ?agent-id
                       :where
                       [?agent :seon.cluster.agent/id ?agent-id]
                       [?agent :seon.cluster.agent/run ?run]
                       [?run :seon.cluster.run/id ?id]]
                     db agent-id)]
    (->> (d/q '[:find [(pull ?run [*]) ...]
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?run :seon.cluster.run/agent ?agent]]
              db agent-id)
         (remove #(= current (:seon.cluster.run/id %)))
         (sort-by #(inst-ms (:seon.cluster.run/opened-at %)))
         last)))

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
  (when-let [previous (previous-run db agent-id)]
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

(defn- paused-sentence
  "The note the agent left itself when it paused, or nil.
  CONTINUITY WITH NO MEMORY RUNG. A delegating agent's problem is that
  its next run is a different run: the sci ctx is gone, the prompt is
  derived fresh, and the reply that finally arrives says only \"25\".
  `my.run/wait` already promised the fix in its own docstring — \"the
  note is for the human and for the agent's own next prompt\" — and this
  is that promise kept. Nothing new is stored: the disposition IS the
  last form's admitted value, so the note is already durable in that
  form's `result-edn`, and this reads it back.

  Total by construction: unreadable EDN, a value that is not a wait,
  and a run with no receipts all answer nil, because a prompt that
  threw would take the turn down with it."
  [db agent-id]
  (when-let [previous (previous-run db agent-id)]
    (let [last-value
          (some->> (run-receipts db (:seon.cluster.run/id previous))
                   (sort-by :seon.cluster.eval/ordinal)
                   last
                   :seon.cluster.eval/result-edn
                   (#(try (edn/read-string %)
                          (catch #?(:clj Throwable :cljs :default) _ nil))))]
      (when (and (map? last-value)
                 (= :wait (:my.run/disposition last-value)))
        (str "You paused your previous run, leaving yourself this note: "
             (:my.run/note last-value))))))

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
                    (refuse! ::no-trigger request))
        sender (trigger-sender db message-id)
        others (peers db agent-id)]
    (->> [(str "You are agent " agent-id
               ". Your namespace is my.agents." agent-id
               " — defns you write land there.")
          ;; WHO ELSE EXISTS, and it is a fact rather than an
          ;; encouragement: an agent that is never told the population
          ;; cannot delegate, and one told about an agent that does not
          ;; exist writes a message the driver has to refuse. Omitted
          ;; entirely when it is alone — the sentence is present exactly
          ;; while the facts that cause it are.
          (when (seq others)
            (str "Other agents in this cluster: " (str/join ", " others)
                 ". Send one a message by returning "
                 "(my.message/send \"their-id\" \"what you want to say\") "
                 "from a form — that delivers it and wakes them, and "
                 "their reply comes back to you as a new request. "
                 "Return a vector of sends to message several."))
          (interrupted-sentence db agent-id)
          (paused-sentence db agent-id)
          (if sender
            (str "Agent " sender " sent you:\n\n" content)
            (str "You have been asked:\n\n" content))
          (str "Reply with Clojure forms to run, in order. "
               "Finish with (my.run/complete \"your reply\") when you are "
               "done, or (my.run/wait \"why\") to pause this run — pause "
               "when you are waiting on another agent, and put everything "
               "you will need to finish into the note, because your next "
               "run starts fresh and that note is what it reads.")]
         (remove nil?)
         (str/join "\n\n"))))
