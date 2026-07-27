(ns seon.error
  "THE ONE NORMALIZER. Anything that went wrong becomes one fact here,
  and nothing anywhere else formats an error.

  CONTRACT LAYER (drafted 2026-07-27 for ORCHESTRATOR SEAL — step 1 of
  the error-wiring order, grounded in
  `docs/prds/sci-execution-runtime/research/error-handling-grounding-2026-07-27.md`
  in full, with §1.2, §3.2, §6.1-6.3 and §8 carrying the file:line
  evidence for every claim below). Nothing here is implemented: every
  body throws `awaits implementation`. Slice 1 (`3bd147643`) landed the
  entity and the two dials as `:seon.fault/*`; the orchestrator ruled
  the rename, so this file and `src/seon/schema/error.edn` are the one
  owner and `:seon.fault/*` no longer exists.

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
  2. A flat `:seon.error/value` — what `store/transact!`, `ai/complete`,
     `config/apply!` and `reconcile` return. Nothing throws into the run
     loop, so a system failure normally arrives as a value.
  3. A transition refusal's `ex-data` — the map
     `seon.cluster.store/refusal` digs out of a cause chain
     (`store.clj:398-412`). It is family 2's shape wearing family 1's
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
  (`loop.cljc:226-229`), and `pr-str` of a value holding a reference
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
  because turns are serial within a cluster (`loop.cljc:36-40`), so the
  derivation belongs to the caller that knows the basis — and the day
  turns go concurrent it must move into the loop state. This namespace
  stays pure and takes the ids it is given; a run/agent id that is
  absent produces no ref rather than a nil one.

  PROJECTIONS, ONE PER CONSUMER (owner direction, 2026-07-27 night).
  A fact is not prose. `notice` derives the routing unit — the fact plus
  the two projection declarations — and the ONE generic router
  (`seon.render`) resolves and applies them:

  - `ai-prose` (`:seon.render/ai`) is steering prose for an agent: what
    happened, why it is being told, what it can do. It is STORED at
    commit time as the explanation message's content, and that is not a
    stored-derived slip: a message is a historical fact about what an
    agent WAS TOLD, and it must not silently change when the error's
    context does. The same function feeds the derived `problems` block
    (step 3) and the failover \"you are the backup, and why\" notice
    (step 5) — one derivation, three consumers, never three renderers.
  - `log-line` (`:seon.render/log`) is a structured single line, DERIVED
    and never stored. Nothing durable depends on it, so it may change
    shape freely.

  Crash walk. Normalization is PURE: it opens nothing, writes nothing,
  and holds no lock. Killed before it, during it, or after it and before
  the commit, the durable state is identical — a normalized fact that
  was never transacted is a value on a dead thread. `commit!` (step 2)
  owns the durability and its own crash walk."
  (:require [clojure.core.async.flow :as-alias flow]
            [clojure.string :as str]
            [seon.cluster.store :as store]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/error.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Reading the source — structure only, never a flag and never a scope
;;; ---------------------------------------------------------------------------

;;; The fail-closed kind. Not a classification: the honest statement that
;;; nothing recognized this source, which is still infinitely better than
;;; refusing to record it.
(def ^:private unclassified :seon.error/unclassified)

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
  which `seon.cluster.store/refusal` already walks — one owner for that
  walk, not a copy of it here."
  [source failure]
  (or (when (map? source) (:seon.error/kind source))
      (when failure (:seon.error/kind (store/refusal failure)))
      unclassified))

(defn- root-cause
  "The deepest Throwable in the cause chain.
  A different question from `store/refusal`'s — that one digs out the
  deepest DATA, this one names the throwable the chain bottoms out in —
  so it is not a copy of that walk."
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
      class-name (assoc :seon.error/class class-name)
      ;; each flow key rides exactly when the arriving shape carried it,
      ;; because absence is the state — two of the three shapes have no
      ;; op and one has no cid
      (and flow? (::flow/pid source)) (assoc :seon.error/proc (::flow/pid source))
      (and flow? (::flow/op source)) (assoc :seon.error/op (::flow/op source))
      (and flow? (::flow/cid source)) (assoc :seon.error/cid (::flow/cid source))
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
  "The routing unit for one fact: the fact plus its projection keys.
  Declares `:seon.render/ai` → `seon.error/ai-prose` and
  `:seon.render/log` → `seon.error/log-line`, so the unit routes through
  the one generic router (`seon.render/render`) and nothing dispatches
  on the error family by name.

  `:seon.error/reason` is the per-RECIPIENT why-clause and is optional
  because a log has no recipient: one fact is `:your-run` to the
  interrupted agent and `:recurring` to the escalation owner in the same
  transaction, which is why the reason is derived here and never stored
  on the entity."
  {:malli/schema [:=> [:cat :seon.error/notice-request] :seon.error/notice]}
  [{:seon.error/keys [fact reason] agent-id :seon.cluster.agent/id}]
  (cond-> {:seon.error/fact fact
           :seon.render/ai `ai-prose
           :seon.render/log `log-line}
    reason (assoc :seon.error/reason reason)
    agent-id (assoc :seon.cluster.agent/id agent-id)))

(defn ai-prose
  "`:seon.render/ai` — the steering prose an agent is told, from a notice.
  Answers the four questions in order: WHAT happened, WHY it is being
  told, WHAT that means for its work, and WHERE the evidence is. Every
  clause is derived from a present fact and OMITTED when the fact is
  absent — never a stored nil, never the word \"unknown\", and never
  boilerplate.

  Three rules the prose may not break:

  - the why-clause is derived from `:seon.error/reason` and is the one
    sentence an agent will act on: its own run was interrupted, or it is
    the escalation owner and the error had no attributable agent, or the
    same signature has now recurred. With no reason the clause is absent
    entirely, because nobody is being contacted;
  - it says \"may have\" wherever it may have. A fault mid-transform has
    the same ambiguity `prompt.cljc:130-134` already refuses to paper
    over for interruptions: claiming certainty is a lie the agent then
    reasons from;
  - it never restates the interrupted-run warning the prompt already
    derives (`prompt.cljc:122-147`), or the agent reads one event twice
    and infers two.

  Sizes shown to anyone are estimated tokens, never characters — this
  prose prints no character count.

  Called by the router, by the explanation message's content at commit
  time (where the string becomes a historical fact), by the `problems`
  block, and by the failover notice. One derivation, four consumers."
  {:malli/schema [:=> [:cat :seon.error/notice] [:string {:min 1}]]}
  [notice]
  (let [{:seon.error/keys [fact reason]} notice
        {:seon.error/keys [id kind message proc op cid class basis-t run]} fact
        run-id (second run)]
    (str/join
     " "
     (remove
      nil?
      [(str "An error stopped work" (when proc (str " in " proc)) ": "
            message " (" kind ").")
       (when (and op cid)
         (str "It happened while handling " cid " at " op "."))
       (when run-id (str "It interrupted run " run-id "."))
       ;; the honesty rule: a fault mid-transform has the same ambiguity
       ;; the interrupted-run warning already refuses to paper over
       (str "Work already under way may or may not have completed;"
            " nothing was retried and nothing re-executed.")
       (case reason
         :your-run "You are being told because this interrupted your own run."
         :no-attributable-agent
         (str "You are being told because you are this cluster's escalation"
              " owner and this error could not be attributed to one agent.")
         :recurring
         (str "You are being told because this same failure has now recurred"
              " often enough to be a pattern rather than bad luck.")
         nil)
       (str "Evidence: error " id
            (when class (str ", " class))
            (when basis-t (str ", basis-t " basis-t))
            ".")
       ;; what you can do next — present exactly when somebody is being
       ;; contacted, because it is advice to a reader and a log has none
       (when reason
         (str "Nothing will retry this for you: read error " id
              " and decide from the current facts."))]))))

(defn log-line
  "`:seon.render/log` — one structured line for a human reading stderr.
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
  {:malli/schema [:=> [:cat :seon.error/notice] [:string {:min 1}]]}
  [notice]
  (let [{:seon.error/keys [id at kind message process signature
                           class proc op cid run basis-t]}
        (:seon.error/fact notice)]
    (str/join
     " "
     (remove
      nil?
      ["seon.error"
       (str "id=" id)
       (str "at=" (pr-str at))
       (str "kind=" kind)
       (when class (str "class=" class))
       (when proc (str "proc=" proc))
       (when op (str "op=" op))
       (when cid (str "cid=" cid))
       (when-let [run-id (second run)] (str "run=" run-id))
       (when basis-t (str "basis-t=" basis-t))
       (str "process=" process)
       (str "signature=" signature)
       ;; one line by construction: a message with a newline in it would
       ;; otherwise be two log lines to every tool that reads them
       (str "message=" (pr-str (str/replace message #"\s+" " ")))]))))
