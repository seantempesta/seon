(ns seon.error
  "THE ONE NORMALIZER. Anything that went wrong becomes one fact here,
  and nothing anywhere else formats an error.

  Implemented and boot-wired (2026-07-27, steps 1-2 of the error-wiring
  order), grounded in
  `docs/prds/sci-execution-runtime/research/error-handling-grounding-2026-07-27.md`
  in full, with §1.2, §3.2, §6.1-6.3 and §8 carrying the file:line
  evidence for every claim below. Slice 1 landed the entity as
  `:seon.fault/*`; the rename merged it into this one family, and
  `:seon.fault/*` no longer exists.

  THIS NAMESPACE DOES NOT TRANSACT. It normalizes, it projects, and it
  returns TRANSACTION DATA. Cause-chain reading lives in the lower
  `seon.error.refusal` leaf so `seon.db` does not acquire this namespace's
  rendering dependencies. Its two callers commit through the one boundary:
  `seon.cluster/commit-fault!` for Throwables off flow's error channel,
  and the run loop for a refused transaction.

  WHY ONE NORMALIZER. The quarry grew three independent bounding rules
  and two hand-maintained blame lists because every catch site formatted
  its own error (`src-old/seon/error.cljc:237-249, 519`;
  `error/instrument.cljc:98-106`). The measured consequence was that the
  SAME typo was classified `:core` on one path and `:agent` on another,
  depending on ambient scope (`error.cljc:598-613`, live datoms
  3689-3857). One function, no ambient state, and the classification
  falls out of the source's own shape.

  THE THREE INPUT FAMILIES, all total, detected structurally and never
  by a flag the caller sets:

  1. A `::flow/error` map — core.async.flow's error channel. THREE
     incompatible shapes ride it and they do not share a key set
     (`reference-code/core.async/.../flow/impl.clj:106-110, 312-320`):
     a transform throw carries `#::flow{:pid :status :state :count :cid
     :msg :op :step :ex}`, anything else in the proc loop carries the
     same WITHOUT `:cid`/`:msg`/`:op`, and a channel xform throw carries
     only `#::flow{:ex :pid :cid :xform}`. `::flow/ex` is the one key
     all three share, which is what makes the family recognizable
     without a shape list.
  2. A flat `:seon.error/value` — what `db/transact!`, `ai/complete`,
     `config/apply!` and `reconcile` return. Nothing throws into the run
     loop, so a system failure normally arrives as a value.
  3. A transition refusal's `ex-data` — the map `refusal` (below) digs
     out of a cause chain. It is family 2's shape wearing family 1's
     origin, and it normalizes as itself.

  Anything else is family 4 by exclusion — a bare Throwable, or a value
  no rule recognizes — and it normalizes FAIL-CLOSED rather than
  refusing: `:seon.error/unclassified`, with the source projected into
  `data-edn` like every other family. An error the error system will not
  record is the one outcome this design cannot have.

  CLASSIFICATION IS THE CHANNEL, NOT A PREDICATE, and that is why there
  is no `agent-vs-core` function here. `seon.sci.eval/evaluate` never
  throws, so an agent's mistake is by construction a VALUE and can only
  become a receipt; anything arriving on `::flow/error` is a Throwable
  that escaped our own code, which is definitionally ours
  (`flow_test.clj:474-477` already asserts the negative half). Naming
  the family is therefore free, and no lookup, list or ambient scope
  decides blame.

  KINDS ARE THE KEYWORDS THE SITES ALREADY CARRY. `:seon.error/kind` is
  taken from the deepest non-empty `ex-data` in the cause chain, or from
  a flat value's own `:seon.error/kind`, and it is never invented here.
  There is no enumeration in this namespace and none in the schema: the
  kind population is computed — today by reading the producers, and from
  N5 by querying the program graph, where every literal
  `:seon.error/kind` in the corpus is a fact. A `[:enum]` would be a
  hand-maintained copy of everyone else's vocabulary.

  ONE CODEC, AND IT IS `seon.sci.admit/admit`. The source is projected
  through value admission before anything is printed — with the config
  caps and `(constantly nil)` as the interrupt-fn, which is sound
  because admission is pure given the value and the caps
  (`admit.clj:128-129`). This is not a preference: two of flow's three
  shapes carry `::flow/state`, which for the run loop is the proc's
  whole init state holding a LIVE Datahike connection and executors
  (`loop.clj:226-229`), and `pr-str` of a value holding a reference
  cycle raises `StackOverflowError` — an Error, which `catch Exception`
  does not even see (`admit.clj:82-92`, probed). Reusing the one codec
  also deletes the possibility of the second bounded printer the quarry
  grew. `:seon.error/capped?` rides along so a reader never has to guess
  whether an elision marker was the original value.

  THE ERROR PATH MAY NOT PANIC. Admission is called in `:record` mode
  UNCONDITIONALLY, and `:seon.config/on-core-error` is deliberately not
  a request key. R41's dial makes development loud about a codec hole,
  but a panic here destroys the one record of the ORIGINAL failure and
  turns recording an error into a second error — the quarry's recursion
  fence (`error.cljc:738-745`), which is a measured failure mode rather
  than a hypothesis. The hole stays visible: `:seon.sci.admit/opaque`
  plus `::projection-error` markers in `data-edn`, and `capped?` true.
  (Orchestrator: this is the THIRD R41-vs-marker tension in the tree,
  after admission's projection failure and the router's totality. They
  want one ruling, not three local judgements.)

  THE SIGNATURE IS CONTENT, NOT A TALLY. `sha-256` over
  `[process, class, kind, top frame]` — deliberately WITHOUT the
  message, because a message carrying an id, a path or a timestamp
  makes every occurrence unique and recurrence undetectable, which is
  exactly the derived count the escalation rule needs
  (`(count faults with this signature since this process started)`).
  Nothing increments; recurrence is a query.

  ATTRIBUTION IS THE CALLER'S, and the reason is exactness rather than
  convenience. The flow error map does not carry the agent: the loop's
  state is `{cluster, turns}`. Deriving \"the run claimed by this
  process and not closed at the fault's basis\" is EXACT today only
  because turns are serial within a cluster (`loop.clj:36-40`), so the
  derivation belongs to the caller that knows the basis — and the day
  turns go concurrent it must move into the loop state. This namespace
  stays pure and takes the ids it is given; a run/agent id that is
  absent produces no ref rather than a nil one.

  PROJECTIONS, ONE PER CONSUMER (owner direction, 2026-07-27 night).
  A fact is not prose. `notice` derives the agent-facing unit and its explicit
  AI producer; log consumers call the ordinary log function directly:

  - `ai-prose` is the generic `:seon.render/ai` implementation: what
    happened, why the reader is being told, and what it can do. A fact
    with specialist evidence selects its specialist in `notice`, where
    the unit is built; consumers still ask only for `:seon.render/ai`.
    The result is STORED at commit time as the explanation message's
    content, and that is not a stored-derived slip: a message is a
    historical fact about what an agent WAS TOLD, and it must not
    silently change when the error's context does.
  - `log-line` is a structured single line, DERIVED
    and never stored. Nothing durable depends on it, so it may change
    shape freely. `seon.problems` COMPOSES it rather than reformatting
    an error its own way, so there is one place that decides what an
    error looks like in a log.

  Crash walk. Normalization is PURE: it opens nothing, writes nothing,
  and holds no lock. Killed before it, during it, or after it and before
  the commit, the durable state is identical — a normalized fact that
  was never transacted is a value on a dead thread. `commit-tx` is pure
  too: the transaction either committed or it did not, and an error
  that was never committed is an error the next boot never sees — which
  is the same crash row as the work it was reporting on."
  (:require [clojure.core.async.flow :as-alias flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.error.refusal :as error.refusal]
            [seon.render :as render]
            [seon.render.route :as render.route]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Reading the source — structure only, never a flag and never a scope
;;; ---------------------------------------------------------------------------

;;; The fail-closed kind. Not a classification: the honest statement that
;;; nothing recognized this source, which is still infinitely better than
;;; refusing to record it.
(def ^:private unclassified :seon.error/unclassified)

(defn refusal
  "The deepest non-empty `ex-data` in a throwable's cause chain, or nil.
  Pure, and unit-testable with no database: a refusal is a value buried
  under wrappers, and finding it is a walk, not a guess. Returns nil for
  a throwable that carries no data anywhere in its chain — which is
  itself information, and the caller treats it as unclassifiable.

  The pure cause-chain owner is `seon.error.refusal`, below both this
  rendering-aware normalizer and `seon.db`; this public entry delegates
  so existing callers retain one behavior without a dependency cycle."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [throwable]
  (error.refusal/refusal throwable))

(defn- throwable
  "The Throwable in `source`, or nil.
  `::flow/ex` is the ONE key all three of flow's report shapes share
  (`impl.clj:106-110, 312-320`), which is what makes the family
  recognizable without enumerating shapes."
  [source]
  (cond
    (instance? Throwable source) source
    (and (map? source) (instance? Throwable (::flow/ex source))) (::flow/ex source)))

(defn- kind
  "The namespaced rule that failed, never invented here.
  A flat value and a refusal's ex-data carry their own; a Throwable
  carries one at the deepest non-empty `ex-data` in its cause chain,
  which `refusal` above walks — one owner for that walk, and the store's
  own `transact!` calls the same one."
  [source failure]
  (or (when (map? source) (:seon.error/kind source))
      (when failure (:seon.error/kind (refusal failure)))
      unclassified))

(defn- root-cause
  "The deepest Throwable in the cause chain.
  A different question from `refusal`'s — that one digs out the deepest
  DATA, this one names the throwable the chain bottoms out in — so it is
  not a copy of that walk."
  [failure]
  (loop [candidate failure]
    (if-let [cause (ex-cause candidate)]
      (recur cause)
      candidate)))

(defn- message
  "What a reader is told. Never absent, never blank.
  Taken from the ROOT CAUSE, not the outermost wrapper: measured on the
  first real projection, a Datahike-wrapped transition refusal produced
  the message \"wrapper\" while the kind came from the bottom of the
  chain, and an agent reading \"An error stopped work: wrapper\" has been
  told nothing. The chain is not recoverable from `data-edn` either —
  admission projects a Throwable to an opaque marker by design — so this
  string is the only place the real sentence can appear.
  A source nothing recognizes still says what arrived: `nil` is a
  perfectly possible thing to be handed, and \"an error we cannot
  describe\" has to describe that much."
  [source failure]
  (or (when (map? source) (not-empty (:seon.error/message source)))
      (when (and (map? source)
                 (:seon.cluster.run/rule source)
                 (:seon.cluster.run/transition source))
        (str (:seon.cluster.run/transition source) " was refused by "
             (:seon.cluster.run/rule source) "."))
      (when failure
        (let [deepest (root-cause failure)]
          (or (not-empty (ex-message deepest))
              (not-empty (ex-message failure))
              (.getName (class deepest)))))
      (if (nil? source)
        "An unclassified nil arrived where an error was expected."
        (str "An unclassified " (.getName (class source)) " arrived where an "
             "error was expected."))))

(defn- top-frame
  "The Throwable's own top stack frame, or nil.
  Part of the signature because it is what separates two different bugs
  that happen to share a class and a kind."
  [failure]
  (when failure
    (some-> ^Throwable failure .getStackTrace first str)))

(defn- signature
  "SHA-256 over the error's own content: process, class, kind, top frame.
  The MESSAGE IS DELIBERATELY ABSENT — a message carrying a run id, a
  path or a timestamp would make every occurrence unique and the derived
  recurrence count (which is the escalation rule) always one."
  [process class-name error-kind frame]
  (schema/sha-256 [(.getBytes (pr-str [process class-name error-kind frame])
                              "UTF-8")]))

;;; ---------------------------------------------------------------------------
;;; The normalizer
;;; ---------------------------------------------------------------------------

(defn normalize
  "Any error into the one durable fact. Total, pure, and never throws.
  Recognizes the source structurally — a map carrying `::flow/ex` is a
  flow report (all three shapes), a map carrying `:seon.error/kind` is a
  flat value or a transition refusal, a `Throwable` is itself, and
  anything else is `:seon.error/unclassified` — then:

  - takes `kind` from the deepest non-empty `ex-data` in the cause
    chain, or from the flat value's own kind, and never invents one;
  - takes `class` and `message` from the Throwable when there is one,
    and from the value's own `message` when there is not. `class` is
    ABSENT for a source that was never a Throwable; `message` is never
    absent and never empty — for a source nothing recognizes it names
    what arrived, because \"an error we cannot describe\" still has to
    say that much;
  - projects the WHOLE source — including `::flow/state` and
    `::flow/msg` — through `seon.sci.admit/admit` in `:record` mode with
    the request's caps and `(constantly nil)` as the interrupt-fn, and
    keeps the printed projection as `data-edn` with admission's own
    `capped?` beside it;
  - lifts `::flow/pid`, `::flow/op` and `::flow/cid` into `proc`, `op`
    and `cid`, each present exactly when the arriving shape carried it;
  - computes `signature` as `sha-256` over
    `[process, class, kind, top frame]`;
  - emits `run` and `agent` as lookup refs (`[:seon.cluster.run/id id]`)
    exactly when the request supplied those ids.

  `id`, `at` and `process` are the caller's: identity and the clock are
  not this function's to invent, and a pure normalizer is a testable
  one. The result is transactable as-is — every key is a declared
  attribute of `:seon.error/fact` and nothing rides along."
  {:malli/schema [:=> [:cat :seon.error/normalize-request] :seon.error/fact]}
  [{:seon.error/keys [source id at process basis-t]
    :seon.sci.admit/keys [caps]
    run-id :seon.cluster.run/id
    agent-id :seon.cluster.agent/id}]
  (let [failure (throwable source)
        failure-data (when failure (refusal failure))
        instrument-data (when (= :seon.instrument/contract-violated
                                 (:seon.error/kind failure-data))
                          (:seon.error/data failure-data))
        class-name (when failure (.getName (class failure)))
        error-kind (kind source failure)
        ;; :record UNCONDITIONALLY. The dial governs the failing site;
        ;; the recorder may not panic, because a panic here destroys the
        ;; one record of the original failure.
        admitted (admit/admit {:seon.sci.admit/value source
                               :seon.sci.admit/interrupt-fn (constantly nil)
                               :seon.sci.admit/caps caps
                               :seon.config/on-core-error :record})
        flow? (map? source)]
    (cond-> {:seon.error/id id
             :seon.error/at at
             :seon.error/process process
             :seon.error/kind error-kind
             :seon.error/message (message source failure)
             :seon.error/signature (signature process class-name error-kind
                                              (top-frame failure))
             :seon.error/data-edn (:seon.cluster.eval/result-edn admitted)
             :seon.error/capped? (:seon.sci.admit/capped? admitted)}
      class-name (assoc :seon.error/throwable-class class-name)
      ;; each flow key rides exactly when the arriving shape carried it,
      ;; because absence is the state — two of the three shapes have no
      ;; op and one has no cid
      (and flow? (::flow/pid source)) (assoc :seon.error/proc (::flow/pid source))
      (and flow? (::flow/op source)) (assoc :seon.error/op (::flow/op source))
      (and flow? (::flow/cid source)) (assoc :seon.error/cid (::flow/cid source))
      (:seon.instrument/fn instrument-data)
      (assoc :seon.instrument/fn (:seon.instrument/fn instrument-data))
      (:seon.instrument/arm instrument-data)
      (assoc :seon.instrument/arm (:seon.instrument/arm instrument-data))
      (:seon.instrument/schema instrument-data)
      (assoc :seon.instrument/expected (:seon.instrument/schema instrument-data))
      (:seon.instrument/args instrument-data)
      (assoc :seon.instrument/args (:seon.instrument/args instrument-data))
      basis-t (assoc :seon.error/basis-t basis-t)
      run-id (assoc :seon.error/run [:seon.cluster.run/id run-id])
      agent-id (assoc :seon.error/agent [:seon.cluster.agent/id agent-id]))))

(defn value
  "The flat `:seon.error/value` a caller branches on, from a fact.
  Total by construction, which is the point: `kind` and `message` are
  required on the fact, so every normalization projects down to a valid
  flat value and the two shapes can never diverge. `data` carries the
  pointer to the durable evidence (`{:seon.error/id …}`) rather than a
  copy of it — the fact is one pull away and duplicating its projection
  into every value is how two renderings of one error start to drift."
  {:malli/schema [:=> [:cat :seon.error/fact] :seon.error/value]}
  [fact]
  {:seon.error/kind (:seon.error/kind fact)
   :seon.error/message (:seon.error/message fact)
   :seon.error/data {:seon.error/id (:seon.error/id fact)}})

;;; ---------------------------------------------------------------------------
;;; The routing unit and its projections
;;; ---------------------------------------------------------------------------

(defn notice
  "The agent-facing unit for one fact and its explicit AI producer.

  `:seon.error/reason` is the per-RECIPIENT why-clause and is optional
  because a log has no recipient: one fact is `:your-run` to the
  interrupted agent and `:recurring` to the escalation owner in the same
  transaction, which is why the reason is derived here and never stored
  on the entity."
  {:malli/schema [:=> [:cat :seon.error/notice-request] :seon.error/notice]}
  [{:seon.error/keys [fact reason occurrence occurrences notification-limit
                      notification]
    agent-id :seon.cluster.agent/id}]
  (let [presentation (if (= :seon.instrument/contract-violated
                            (:seon.error/kind fact))
                       `instrumentation-prose
                       `ai-prose)]
    (cond-> {:seon.error/fact fact
             :seon.error/kind (:seon.error/kind fact)
             :seon.error/evidence [:seon.error/id (:seon.error/id fact)]
             ;; The typed selector invokes this explicit producer through SCI.
             :seon.render/ai presentation}
      reason (assoc :seon.error/reason reason)
      occurrence (assoc :seon.error/occurrence occurrence)
      occurrences (assoc :seon.error/occurrences occurrences)
      notification-limit (assoc :seon.error/notification-limit notification-limit)
      notification (assoc :seon.error/notification notification)
      agent-id (assoc :seon.cluster.agent/id agent-id))))

(defn- fact-source
  [fact]
  (try
    (edn/read-string (:seon.error/data-edn fact))
    (catch Throwable _ {})))

(defn- flat-data
  [fact]
  (let [source (fact-source fact)]
    (if (map? (:seon.error/data source))
      (:seon.error/data source)
      source)))

(defn- evidence-prose
  [fact]
  (str "Evidence: error " (:seon.error/id fact)
       ", kind " (:seon.error/kind fact)
       ", signature " (:seon.error/signature fact) "."))

(defn refusal-prose
  "`:seon.render/ai` — a refused transition and its atomic outcome."
  {:malli/schema
   [:=> [:cat [:or :seon.error/value :seon.error/notice]]
    [:string {:min 1}]]}
  [error-value]
  (let [fact (or (:seon.error/fact error-value) error-value)
        source (if (:seon.error/data-edn fact)
                 (fact-source fact)
                 fact)
        request (:seon.cluster.run/request source)
        transition (:seon.cluster.run/transition source)
        operation (or (some-> transition name) "transition")
        run-id (or (:seon.cluster.run/id request)
                   (:seon.cluster.run/id source)
                   (second (:seon.error/run fact)))
        rule (:seon.cluster.run/rule source)]
    (str "The " operation (when run-id (str " of " run-id))
         " was refused atomically by " rule
         ". Nothing from this " operation " committed. Re-read the run before"
         " deciding whether a new transition is eligible."
         (when (:seon.error/id fact)
           (str " " (evidence-prose fact))))))

(defn instrumentation-prose
  "`:seon.render/ai` — detailed steering for a validation failure."
  {:malli/schema
   [:=> [:cat [:or :seon.error/value :seon.error/notice]]
    [:string {:min 1}]]}
  [error-value]
  (let [fact (or (:seon.error/fact error-value) error-value)
        {instrument-fn :seon.instrument/fn
         arm :seon.instrument/arm
         expected :seon.instrument/expected
         args :seon.instrument/args} fact
        admitted-value (some-> args edn/read-string)
        received (some-> (if (= :input arm)
                           (first admitted-value)
                           admitted-value)
                         pr-str)]
    (str "Contract violation"
         (when instrument-fn (str " in " instrument-fn))
         (when arm (str " " (name arm)))
         ": expected " expected
         (when received (str ", received " received))
         (if (= :input arm)
           ". The call was stopped before the function ran. "
           ". The function returned an invalid value. ")
         (when (:seon.error/id fact)
           (evidence-prose fact)))))

(defn- notice-ai-prose
  "`:seon.render/ai` — the steering prose an agent is told, from a notice.
  Answers the four questions in order: WHAT happened, WHY it is being
  told, WHAT that means for its work, and WHERE the evidence is. Every
  clause is derived from a present fact and OMITTED when the fact is
  absent — never a stored nil, never the word \"unknown\", and never
  boilerplate.

  Two rules the prose may not break:

  - the why-clause is derived from `:seon.error/reason` and is the one
    sentence an agent will act on: its own run was interrupted, or it is
    the escalation owner and the error had no attributable agent, or the
    same signature has now recurred. With no reason the clause is absent
    entirely, because nobody is being contacted;
  - it says \"may have\" wherever the committed facts do not establish
    whether the interrupted operation completed. Claiming certainty is a
    lie the agent then reasons from.

  Sizes shown to anyone are estimated tokens, never characters — this
  prose prints no character count.

  Called by the router, by the explanation message's content at commit
  time (where the string becomes a historical fact), by the `problems`
  block, and by the failover notice. One derivation, four consumers."
  [notice]
  (let [{:seon.error/keys [fact reason]} notice
        {:seon.error/keys [id kind message proc op run process signature]} fact
        run-id (second run)
        data (flat-data fact)
        error-class (:seon.ai/error-class data)]
    (cond
      (= reason :failover)
      (str "The primary model was not called: its connection failed before"
           " send, so no output exists and this failover is safe. You are the"
           " one backup attempt. Answer the unchanged user request below; do"
           " not wait for or reconstruct a primary response.")

      (= kind :seon.cluster.run/refused)
      (refusal-prose fact)

      (= reason :recurring)
      (if-let [occurrence (:seon.error/occurrence notice)]
        (str "Core fault " kind " reached " occurrence " occurrences in process "
             process " (notification limit "
             (:seon.error/notification-limit notice)
             "). Further occurrences remain in seon.problems but will not"
             " message you. Latest error: " id ". Signature: " signature ".")
        (str "Core fault " kind " reached its final notification for signature "
             signature ". Later occurrences remain in seon.problems. Latest"
             " error: " id "."))

      (= kind :seon.ai/no-credential)
      (str "The model was not called: " message
           " Configure the credential before retrying. "
           (evidence-prose fact))

      (= error-class :transport-before-send)
      (str "The primary model was not called: the connection failed before"
           " send. This attempt cost nothing; a configured backup may run"
           " immediately. " (evidence-prose fact))

      (= kind :seon.ai/unparseable-body)
      (str "The model returned a response but no assistant text. Do not retry"
           " automatically; inspect the response evidence first. "
           (evidence-prose fact))

      :else
      (str/join
       " "
       (remove
        nil?
        [(str (if proc
                (str "The " (or (some-> proc name) "proc") " " op
                     " failed with " kind ".")
                (str message " (" kind ").")))
         (case reason
           :your-run (str "It interrupted run " run-id ".")
           :no-attributable-agent "No agent or run could be attributed."
           nil)
         (str "Inspect error " id "; "
              (if proc
                "the proc survived and no work was re-executed."
                "nothing was retried.")
              " Signature: " signature ".")])))))

(defn ai-prose
  "`:seon.render/ai` — AI-attempt evidence or a legacy error notice.

  Class schemas for every `:seon.ai/*` attempt failure declare this producer.
  The notice arm remains through slice 1 so existing committed facts and the
  failover context retain their current face until their emission sweep."
  {:malli/schema
   [:=> [:cat [:or :seon.error/value :seon.error/notice]]
    [:string {:min 1}]]}
  [error-value]
  (if (:seon.error/fact error-value)
    (notice-ai-prose error-value)
    (let [{:seon.ai/keys [request-transmitted? response-started?
                          output-observed? http-status]} error-value]
      (str/join
       " "
       (remove
        nil?
        [(:seon.error/message error-value)
         (not-empty
          (str/join
           ", "
           (remove nil?
                   [(when (contains? error-value :seon.ai/request-transmitted?)
                      (str "request transmitted: " request-transmitted?))
                    (when (contains? error-value :seon.ai/response-started?)
                      (str "response started: " response-started?))
                    (when (contains? error-value :seon.ai/output-observed?)
                      (str "output observed: " output-observed?))
                    (when http-status (str "HTTP status: " http-status))])))
         (cond
           (false? request-transmitted?)
           "No request was transmitted; a configured failover may be safe."

           output-observed?
           "Output may have been observed; do not retry automatically."

           :else
           "Inspect the attempt evidence before deciding what to do next.")])))))

(defn log-line
  "One structured line for a human reading stderr.
  DERIVED, never stored: nothing durable may depend on this shape, so it
  stays free to change. Single line by construction — a log line that
  wraps is two log lines to every tool that reads them.

  ITS READER IS SOMEBODY DIGGING (owner ruling, 2026-07-27: failing loud
  means the operation halts and the system stays up precisely so the
  error can be dug into). So it carries what a REPL needs to pull the
  whole story: the `id` to pull the fact, the `signature` to count
  recurrence, the `kind` to find the rule, and the run, process, proc,
  op, cid and basis-t refs to find everything around it. Each is omitted
  when absent."
  {:malli/schema [:=> [:cat :seon.error/notice] [:string {:min 1}]]
   :seon.fn/external-sink :ai-visible-text
   :seon.fn/projection-boundary :none}
  [notice]
  (let [fact (:seon.error/fact notice)
        {:seon.error/keys [id at kind message process signature
                           throwable-class proc op cid run basis-t]} fact
        source (fact-source fact)
        data (flat-data fact)
        aggregate? (:seon.error/occurrences notice)]
    (str/join
     " "
     (remove
      nil?
      ["seon.error"
       (str "kind=" kind)
       (when aggregate? (str "sig=" signature))
       (when aggregate? (str "occurrences=" aggregate?))
       (when proc (str "proc=" proc))
       (when op (str "op=" op))
       (when cid (str "cid=" cid))
       (str "run=" (or (second run)
                       (get-in source [:seon.cluster.run/request
                                       :seon.cluster.run/id])
                       "-"))
       (when-let [rule (:seon.cluster.run/rule source)] (str "rule=" rule))
       (when-let [transition (:seon.cluster.run/transition source)]
         (str "transition=" transition))
       (when (= kind :seon.cluster.run/refused) "committed=false")
       (when-let [phase (:seon.ai/error-class data)] (str "phase=" phase))
       (when (contains? data :seon.ai/request-transmitted?)
         (str "transmitted=" (:seon.ai/request-transmitted? data)))
       (when (contains? data :seon.ai/response-started?)
         (str "response-started=" (:seon.ai/response-started? data)))
       (when (contains? data :seon.ai/output-observed?)
         (str "output=" (:seon.ai/output-observed? data)))
       (when (= :transport-before-send (:seon.ai/error-class data))
         "disposition=failover-now")
       (when-let [instrument-fn (:seon.instrument/fn fact)]
         (str "fn=" instrument-fn))
       (when-let [arm (:seon.instrument/arm fact)] (str "arm=" (name arm)))
       (when-let [expected (:seon.instrument/expected fact)]
         (str "expected=" expected))
       (when-let [args (:seon.instrument/args fact)] (str "args=" (pr-str args)))
       (str "id=" id)
       (str "message=" (pr-str (str/replace message #"\s+" " ")))
       (str "process=" process)
       (when basis-t (str "basis-t=" basis-t))
       (str "at=" (pr-str at))
       (when-not aggregate? (str "sig=" signature))
       (when throwable-class (str "class=" throwable-class))
       (when-let [occurrence (:seon.error/occurrence notice)]
         (str "occurrence=" occurrence))
       (when-let [limit (:seon.error/notification-limit notice)]
         (str "limit=" limit))
       (when-let [notification (:seon.error/notification notice)]
         (str "notification=" (name notification)))]))))

;;; ---------------------------------------------------------------------------
;;; The commit — PURE transaction data, so this namespace stays store-free
;;; ---------------------------------------------------------------------------

;;; One string tempid, so the fact and the messages that explain it land
;;; in ONE transaction with the refs already resolved. A lookup ref to an
;;; entity created by the same transaction is not something to bet on.
;;;
;;; DERIVED FROM THE ERROR'S OWN ID, never a constant. A constant made
;;; `commit-tx` uncomposable with itself: two calls in one transaction
;;; would put two different `:seon.error/id`s on ONE entity, silently,
;;; because a shared tempid IS a shared entity. That is not hypothetical
;;; — the messaging rung records one refusal per undeliverable message
;;; and a form may hold several. The id is already unique per fact, so
;;; deriving from it costs nothing and makes the function compose.
(defn- fact-tempid
  [id]
  (str "seon.error/fact-" id))

(defn- agent-exists?
  "True when this cluster really has that agent.
  A message addressed to an id nothing declares would fail the WHOLE
  transaction, taking the error fact down with it — the recorder losing
  the record because the recipient was a typo is precisely the failure
  mode the fault path may not have."
  [db agent-id]
  (some? (db/q '[:find ?agent .
                :in $ ?id
                :where [?agent :seon.cluster.agent/id ?id]]
              db agent-id)))

(defn- entity-exists?
  "True when `db` really has an entity with that identity attribute.
  Attribution is a lookup ref, and a lookup ref to something that does
  not exist fails the WHOLE transaction — the same way an unknown
  recipient would. The recorder may not be destroyed by the pointer it
  was handed: a run that vanished costs the REF, never the record."
  [db attribute value]
  (some? (db/q '[:find ?entity .
                :in $ ?attribute ?value
                :where [?entity ?attribute ?value]]
              db attribute value)))

(defn- recurrence
  "How many errors of this signature this process has already committed.
  DERIVED, never a stored tally — the count is the query. Scoped to the
  process because a process identity is unique per start, which is
  exactly the \"since this process started\" window the escalation rule
  wants, with no clock in it."
  [db signature process]
  (count (db/q '[:find ?error
                :in $ ?signature ?process
                :where
                [?error :seon.error/signature ?signature]
                [?error :seon.error/process ?process]]
              db signature process)))

(defn- message-tx
  "One explanation message: the notice's ai projection, STORED.
  The id is DERIVED from the error and the reason, which makes delivery
  idempotent by construction — re-committing the same error upserts the
  same message instead of double-sending it, and the double-send
  question the plan has been carrying since 2026-07-26 does not arise on
  this path. `about` points at the fact through the shared tempid, and
  its ABSENCE on an ordinary user message is what makes the storm fence
  computable without a flag."
  [fact recipient reason notification]
  {:seon.cluster.message/id (str (:seon.error/id fact) "-" (name reason))
   :seon.cluster.message/to [:seon.cluster.agent/id recipient]
   :seon.cluster.message/content
   (ai-prose
    (notice (merge {:seon.error/fact fact
                    :seon.error/reason reason
                    :seon.cluster.agent/id recipient}
                   notification)))
   :seon.cluster.message/at (:seon.error/at fact)
   :seon.cluster.message/about (fact-tempid (:seon.error/id fact))})

(defn commit-tx
  "Transaction data committing one error and everything it must say.
  PURE over a database value: the fact, and zero to two explanation
  messages, in ONE vector so `db/transact!` commits them together and
  there is no torn window where an error exists that nobody was told
  about. Returning data rather than transacting is what keeps this
  namespace free of the store — the dependency runs `store -> error`,
  never both ways — and it is why the whole escalation rule is testable
  against an in-memory database value with no cluster at all.

  DELIVERY IS THE EXISTING WAKE. `:seon.cluster.message/to` is the wake
  attribute, so committing an explanation message wakes that agent's
  loop by construction: no notification queue, no acknowledgement flag,
  no second channel.

  WHO IS TOLD, computed from THE FACT ITSELF and never from a flag the
  caller sets — the same rule as everywhere else in this family, that
  the shape of the thing decides:

  ATTRIBUTION IS DROPPED, NEVER FATAL. A `run` or `agent` the caller
  named that this database does not have contributes no ref: a lookup
  ref to a missing entity fails the whole transaction, and an error
  destroyed by its own attribution is the recorder failing at the one
  thing it exists for. Who is told:

  - the ATTRIBUTED agent, when the caller could name one AND the error
    was a THROWABLE that escaped our code (the fact carries a `class`).
    That is the case where the agent's run was interrupted by our bug
    and it cannot know unless told. A returned VALUE — a refused
    transition, a model failure — is NOT told: the run's own facts
    already say what happened, the agent reads them in its next prompt,
    and mailing it a message would open a fresh run to explain a run
    that already explains itself. Measured, not theorised: wiring the
    message to every refusal turned a bounded test drive into new runs
    opening to discuss refusals;
  - the ESCALATION recipient (`:seon.config.error/escalate-to`), with
    `:no-attributable-agent` when there was nobody to tell, and with
    `:recurring` once this signature reaches
    `:seon.config.error/recurrence-limit` occurrences in this process;
  - NOBODY, when the dial is absent or names an agent this cluster does
    not have. Absence is the state: the fact is still committed.

  THE STORM FENCE is that same recurrence count, and it is why the
  fence needs no flag: AT the limit one `:recurring` escalation goes
  out, and PAST it nothing is said at all — the facts keep committing,
  because they are the evidence a query counts, but nobody is mailed
  again. That bound is load-bearing rather than tidy. Measured on a live
  cluster: one injected throw in the loop's transform produced six
  faults in 1.5 s, because committing the explanation message is a
  commit, a commit wakes the loop through
  `:seon.cluster.message/to`, and the woken loop hit the same broken
  code. Delivery being the wake attribute is exactly what makes error
  delivery free — and exactly what makes an unbounded error path a
  self-feeding fire. An error fact ALONE wakes nobody
  (`wake-attributes` is `#{:seon.cluster.message/to}`), so silence is
  what breaks the cycle. error -> message -> wake -> turn -> error is a real cycle,
  and a bounded number of messages per signature per process is what
  makes it terminate. A recurrence escalation to the attributed agent
  itself is skipped rather than sent twice. Together with the
  throwable-only rule above, the number of runs an error can cause is
  bounded by the number of DISTINCT signatures, not by the number of
  errors."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.error/commit-tx-request]
                  :seon.store/transaction-data]}
  [db {:seon.error/keys [source id at process basis-t]
       :seon.sci.admit/keys [caps]
       run-id :seon.cluster.run/id
       agent-id :seon.cluster.agent/id
       escalate-to :seon.config.error/escalate-to
       limit :seon.config.error/recurrence-limit}]
  (let [fact (normalize (cond-> {:seon.error/source source
                                 :seon.error/id id
                                 :seon.error/at at
                                 :seon.error/process process
                                 :seon.sci.admit/caps caps}
                          basis-t (assoc :seon.error/basis-t basis-t)
                          (and run-id (entity-exists? db :seon.cluster.run/id run-id))
                          (assoc :seon.cluster.run/id run-id)
                          (and agent-id
                               (entity-exists? db :seon.cluster.agent/id agent-id))
                          (assoc :seon.cluster.agent/id agent-id)))
        occurrence (inc (recurrence db (:seon.error/signature fact) process))
        ;; A MISASSEMBLED CALLER MUST NOT BREAK THE RECORDER. The limit
        ;; is a required request key, but requiredness is a contract and
        ;; contracts are not enforced until instrumentation is on — and
        ;; `(> 1 nil)` throws, out of the one function whose whole job
        ;; is that recording an error cannot fail. The recursion fence
        ;; covers OUR bugs too. No invented number: with no honest limit
        ;; the fact is committed and nothing is mailed, which is the
        ;; conservative half of the storm fence rather than a guess at
        ;; what the caller meant.
        bounded? (pos-int? limit)
        recurring? (and bounded? (= occurrence limit))
        ;; PAST the limit nothing is said at all. The facts keep
        ;; committing — they are the evidence, and a query counts them —
        ;; but the escalation has already been sent once and repeating
        ;; it is the storm rather than the warning.
        silent? (or (not bounded?) (> occurrence limit))
        ;; the fact says whether this was a Throwable; nothing else has
        ;; to be asked, and no caller gets to have an opinion about it
        interrupted-a-run? (some? (:seon.error/throwable-class fact))
        ;; ATTRIBUTION IS READ BACK OFF THE FACT, never off the request.
        ;; The two differ exactly when the caller named an agent this
        ;; database does not have: attribution is dropped, and asking
        ;; the request instead would take the `:your-run` branch (which
        ;; then addresses nobody) while suppressing the
        ;; `:no-attributable-agent` escalation — an interrupting error
        ;; recorded and told to NOBODY. Review-caught; the general rule
        ;; is that a decision about the fact is made from the fact.
        attributed (second (:seon.error/agent fact))
        final-notification {:seon.error/occurrence occurrence
                            :seon.error/notification-limit limit
                            :seon.error/notification :final}
        tell (fn [recipient reason notification]
               (when (and recipient (agent-exists? db recipient))
                 (message-tx fact recipient reason notification)))]
    (into [(assoc fact :db/id (fact-tempid id))]
          (remove nil?)
          [(when (and attributed interrupted-a-run?
                      (not recurring?) (not silent?))
             (tell attributed :your-run nil))
           (when (and interrupted-a-run? (not attributed)
                      (not recurring?) (not silent?))
             (tell escalate-to :no-attributable-agent nil))
           (when (and recurring? (not= escalate-to attributed))
             (tell escalate-to :recurring final-notification))])))

;;; ---------------------------------------------------------------------------
;;; The family default render
;;; ---------------------------------------------------------------------------

(def ^:private render-context-attributes
  #{:seon.db/db
    :seon.sci.eval/ctx
    :seon.sci.admit/caps
    :seon.sci.eval/time-limit-ms
    :seon.config/on-core-error
    :seon.db/connection
    :seon.render/distance
    :seon.render/value})

(defn- rendered-error-value
  [unit]
  (let [value (if (map? (:seon.render/value unit))
                (:seon.render/value unit)
                unit)]
    (render/transacted value)))

(defn- error-evidence
  [value]
  (->> value
       (remove (fn [[attribute _]]
                 (or (= :seon.error/message attribute)
                     (contains? render-context-attributes attribute))))
       (sort-by (comp str key))))

(defn- default-ai-prose
  [value]
  (let [evidence (error-evidence value)]
    (str/join
     "\n"
     (remove
      nil?
      [(:seon.error/message value)
       (when (seq evidence)
         (str "Evidence: "
              (str/join ", "
                        (map (fn [[attribute evidence-value]]
                               (str attribute "=" (pr-str evidence-value)))
                             evidence))
              "."))
       "Re-read the current facts before retrying or changing state."]))))

(defn- evidence-path
  [id]
  (render.route/path :seon.render.route/data
                     {}
                     {:entity (pr-str [:seon.error/id id])
                      :offset "0"}))

(defn render-ai
  "`:seon.render/ai` — one error value as honest steering prose.

  Every class schema declares this producer unless it earns a specialist.
  New class values render their required message, then every present evidence
  attribute, then one conservative next step. A legacy committed fact retains
  its existing notice prose until slice 2 converts the fact emission path.

  `d/pull` wraps refs as `{:db/id N}` and adds `:db/id`, neither of
  which the fact schema admits — `seon.render/transacted` is the
  one place that unwrapping is written."
  {:malli/schema [:=> [:cat :seon.error/value] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)]
    (if (and (:seon.error/kind value) (:seon.error/id value))
      (try
        (ai-prose (notice {:seon.error/fact value}))
        (catch Throwable _
          ;; TOTAL, because this runs on the error path: a fact the
          ;; notice builder cannot accept still says what it is, rather
          ;; than faulting the render that was reporting a fault.
          (str (:seon.error/kind value) ": " (:seon.error/message value))))
      (default-ai-prose value))))

(defn render-html
  "`:seon.render/html` — one readable error card for debug surfaces."
  {:malli/schema [:=> [:cat :seon.error/value] :seon.render/hiccup]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (error-evidence value)]
    (into
     [:article {:class "seon-family-entry seon-error-entry"}
      [:h3 {:class "seon-error-message"} (:seon.error/message value)]]
     (concat
      (when (seq evidence)
        [(into [:dl {:class "seon-error-evidence"}]
               (map (fn [[attribute evidence-value]]
                      [:div {:class "seon-error-evidence-row"}
                       [:dt (str attribute)]
                       [:dd (pr-str evidence-value)]]))
               evidence)])
      (when-let [id (:seon.error/id value)]
        [[:p {:class "seon-error-link"}
          [:a {:href (evidence-path id)} "Inspect durable evidence"]]])))))
