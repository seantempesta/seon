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
  become an evaluation result; anything arriving on `::flow/error` is a Throwable
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

  ONE CODEC, AND IT IS `seon.sci.admit/admit`. The meaningful source is
  projected through value admission before anything is printed. A flow
  report's disposable `::flow/state` is excluded at this boundary. The caller's
  declared admission caps remain authoritative. `(constantly nil)` is supplied
  as the interrupt-fn, which is sound
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
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [malli.error :as me]
            [seon.db :as db]
            [seon.id :as id]
            [seon.error.refusal :as error.refusal]
            [seon.print :as print]
            [seon.repl :as repl]
            [seon.render.route :as render.route]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.sci.admit :as admit])
  (:import [java.nio.charset StandardCharsets]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(def compiled-schema-generator
  "An actual Malli Schema object for the structured explanation boundary."
  (gen/return (m/schema :string)))

(def throwable-generator
  "An actual Throwable for cause-chain contract generation."
  (gen/fmap (fn [_] (ex-info "Generated cause-chain input" {})) (gen/return nil)))

(defn throwable?
  "Whether a candidate is a JVM Throwable."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                    :seon.schema.admission/reason "A total type predicate accepts any candidate and returns false for non-Throwables."
                    :gen/elements [nil false 0 "" [] {}]}]] :boolean]}
  [candidate]
  (instance? Throwable candidate))

(schema/register-core-predicate! 'seon.error/throwable? throwable?)

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
  {:malli/schema [:=> [:cat [:maybe :seon.error/throwable]] [:maybe :map]]}
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
                 (:seon.turn/rule source)
                 (:seon.turn/transition source))
        (str (:seon.turn/transition source) " was refused by "
             (:seon.turn/rule source) "."))
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
  (id/digest 64 [process class-name error-kind frame]))

;;; ---------------------------------------------------------------------------
;;; Flat diagnostics — one evidence-complete construction
;;; ---------------------------------------------------------------------------

(defn- known-or-unknown
  [value]
  (if (nil? value) ::unknown value))

(def ^:private diagnostic-request-keys
  #{:seon.error/kind :seon.error/message :seon.error/data
    :seon.error/diagnostic-layer :seon.error/diagnostic-operation
    :seon.error/diagnostic-member :seon.error/diagnostic-expected
    :seon.error/diagnostic-offending :seon.error/diagnostic-cause
    :seon.error/diagnostic-evidence})

(defn diagnostic
  "An evidence-complete flat diagnostic from one boundary observation.

  Every diagnostic carries the same layer, operation, member, expected value,
  offending value or identity, cause, and evidence fields. A boundary that
  cannot observe one of them gets the typed `:seon.error/unknown` value rather
  than omitting the field or inventing success, failure, or absence. Evidence
  availability is derived from the evidence supplied by the owning query:
  present evidence is `:seon.error/known`; absent evidence is
  `:seon.error/unknown`.

  Optional `:seon.error/data` is additional boundary context. It is merged
  first, so it cannot replace or drop the constructor-owned diagnostic fields."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.error/kind :seon.error/kind]
      [:seon.error/message :seon.error/message]
      [:seon.error/diagnostic-layer :seon.schema/value]
      [:seon.error/diagnostic-operation :seon.schema/value]
      [:seon.error/diagnostic-member :seon.schema/value]
      [:seon.error/diagnostic-expected :seon.schema/value]
      [:seon.error/diagnostic-offending :seon.schema/value]
      [:seon.error/diagnostic-cause :seon.schema/value]
      [:seon.error/diagnostic-evidence :seon.schema/value]
      [:seon.error/data {:optional true} :map]]]
    :seon.error/value]}
  [{:seon.error/keys [kind message data
                      diagnostic-layer diagnostic-operation diagnostic-member
                      diagnostic-expected diagnostic-offending diagnostic-cause
                      diagnostic-evidence]
    :as request}]
  (let [evidence-known? (and (contains? request ::diagnostic-evidence)
                             (some? diagnostic-evidence))]
    (merge
     (apply dissoc request diagnostic-request-keys)
     {:seon.error/kind (or kind unclassified)
      :seon.error/message
      (if (and (string? message) (not-empty message))
        message
        "A diagnostic was constructed without a message.")
      :seon.error/data
      (merge
       (or data {})
       {::diagnostic-layer (known-or-unknown diagnostic-layer)
        ::diagnostic-operation (known-or-unknown diagnostic-operation)
        ::diagnostic-member (known-or-unknown diagnostic-member)
        ::diagnostic-expected (known-or-unknown diagnostic-expected)
        ::diagnostic-offending (known-or-unknown diagnostic-offending)
        ::diagnostic-cause (known-or-unknown diagnostic-cause)
        ::diagnostic-evidence-availability (if evidence-known? ::known ::unknown)
        ::diagnostic-evidence (known-or-unknown diagnostic-evidence)})})))

;;; ---------------------------------------------------------------------------
;;; The normalizer
;;; ---------------------------------------------------------------------------

(defn- meaningful-source
  [source]
  (if (and (map? source) (instance? Throwable (::flow/ex source)))
    (dissoc source ::flow/state)
    source))

(defn- utf8-size
  [value]
  (alength (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- evidence-caps
  "The caps one fault's INLINE evidence is admitted under.

  ONE MECHANISM, TWO BOUNDS. A fault's evidence is a stored value, so it goes
  through the same streaming admission every other stored value does — with
  the fault family's own declared byte bound in place of the storage bound.
  Before this the inline fitting was a token-budget search over a render
  profile, so when presentation limits were disabled a single fault fact
  reached 915,655 bytes against its own declared 4,096 (measured 2026-09-07,
  research/verify-storage-bound-2026-09-07.md B1)."
  [caps evidence-bytes]
  (assoc caps :seon.config.eval.result/max-bytes (max 1 (long evidence-bytes))))

(defn- bounded-admission
  "One admission under a declared bound, plus the marker when it kept nothing.

  A FAULT MAY NEVER FAIL TO BE RECORDED. An admission that answers with
  `:seon.sci.admit/reason` carries no print node and no bytes, and reading that
  absence as content crashed the fault committer itself (observed live,
  2026-09-07: `String.getBytes` on a null `result-edn`). The marker is a
  handful of bytes and always admits, so the durable fact says why instead of
  the committer dying — and it rides beside the admission as `::marker`, so a
  caller can report the absence rather than merely showing its substitute."
  [value caps]
  (let [request {:seon.sci.admit/value value
                 :seon.sci.admit/interrupt-fn (constantly nil)
                 :seon.sci.admit/caps caps
                 :seon.config/on-core-error :record}
        admitted (admit/admit request)]
    (if-some [marker (admit/missing-marker admitted)]
      (assoc (admit/admit (assoc request
                                 :seon.sci.admit/value marker
                                 :seon.sci.admit/unbounded? true))
             ::marker marker)
      admitted)))

(defn- bounded-text
  "One value as the text a fault field stores, under the evidence bound.

  A string is its own text; anything else is its admitted node emitted
  through the one printer. Over the bound the field IS the missing marker —
  the same data every other surface reports an absent value with — and the
  whole value stays reachable in the fault's evidence content."
  [value caps]
  (let [admitted (bounded-admission value caps)]
    (if-some [marker (::marker admitted)]
      (admit/canonical-edn marker)
      (let [projected (:seon.sci.admit/value admitted)]
        (if (string? projected)
          projected
          (print/emit-text (:seon.sci.admit/print-node admitted)
                           (print/default-options)))))))

(def ^:private machinery-namespace-prefixes
  ;; DERIVED FROM WHAT THESE FRAMES ARE, exactly as
  ;; `seon.instrument/caller-frame` derives its own: the host, the
  ;; language, the contract library, core.async's dispatch, and the fault
  ;; machinery are what CAUGHT the failure. None of them is a place to go
  ;; and edit, and naming one routes the fault to the steward of the
  ;; checker instead of the steward of the code that broke.
  ["clojure." "java." "jdk." "sun." "malli." "seon.error" "seon.instrument"])

(defn- stack-failing-function
  "The first first-party function on the Throwable's stack, as `ns/name`.

  THE ONE SEAM WHERE THE FRAME IS KNOWN. Only
  `:seon.instrument/contract-violated` faults arrived carrying
  `:seon.instrument/fn`, so `:seon.error/steward` — which routes
  fn -> `:seon.fn/ns` -> `:seon.ns/steward` — routed exactly nothing in
  production: 23 of 23 faults on a live cluster carried no failing
  function (verify-listened-attributes-2026-09-08 §4b). The Throwable
  itself knows; every other fault class arrives with a proc name, which
  is not a function and resolves no steward.

  Demunged Clojure frames read `ns/fn`, `ns/fn--1234` for a compiled
  arity and `ns/outer/fn` for a closure, so the failing function is the
  first two segments with the compiler's suffix dropped. A frame that
  demunges to no `/` is a host class and is not a function at all."
  [^Throwable failure]
  (when failure
    (some (fn [^StackTraceElement frame]
            (let [demunged (clojure.lang.Compiler/demunge
                            (.getClassName frame))
                  separator (.indexOf demunged "/")]
              (when (pos? separator)
                (let [frame-ns (subs demunged 0 separator)
                      simple (subs demunged (inc separator))
                      simple (if-let [nested (.indexOf simple "/")]
                               (if (neg? nested) simple (subs simple 0 nested))
                               simple)
                      simple (let [suffix (.indexOf simple "--")]
                               (if (neg? suffix) simple (subs simple 0 suffix)))]
                  (when (and (seq simple)
                             (not (some #(.startsWith ^String frame-ns
                                                      ^String %)
                                        machinery-namespace-prefixes)))
                    (str frame-ns "/" simple))))))
          (.getStackTrace failure))))

(defn- projected-instrument-data
  [source]
  (let [projected-error (if (map? (::flow/ex source))
                          (:data (::flow/ex source))
                          source)]
    (when (= :seon.instrument/contract-violated
             (:seon.error/kind projected-error))
      (:seon.error/data projected-error))))

(defn- fit-fact-payload
  "Bound every payload field of one fact so the WHOLE fact fits inline.

  Each field carries at most its share of what the base fact leaves, and a
  field over that share becomes the marker. The halving repeats only because
  a field's bytes are measured on the admitted value while the fact stores it
  as an escaped string; it terminates at one byte, where every field is the
  marker."
  [base-fact source message-value instrument-data caps inline-limit]
  (let [expected (or (:seon.instrument/schema instrument-data)
                     (:seon.error/diagnostic-expected instrument-data))
        arguments (or (:seon.instrument/args instrument-data)
                      (:seon.error/diagnostic-offending instrument-data))
        payload-count (+ 2 (if expected 1 0) (if arguments 1 0))
        available (max 1 (- inline-limit (utf8-size (pr-str base-fact))))]
    (loop [field-limit (max 1 (quot available payload-count))]
      (let [field-caps (evidence-caps caps field-limit)
            evidence (bounded-admission source field-caps)
            fact
            (cond-> (assoc base-fact
                           :seon.error/message
                           (bounded-text message-value field-caps)
                           :seon.error/data-edn
                           (:seon.sci.admit/edn evidence))
              expected
              (assoc :seon.instrument/expected
                     (bounded-text expected field-caps))
              arguments
              (assoc :seon.instrument/args
                     (bounded-text arguments field-caps)))]
        (if (or (<= (utf8-size (pr-str fact)) inline-limit)
                (= 1 field-limit))
          fact
          (recur (max 1 (quot field-limit 2))))))))

(defn prepare
  "Prepare one bounded fact and its full meaningful admitted evidence."
  {:malli/schema [:=> [:cat :seon.error/prepare-request]
                  :seon.error/prepared]}
  [{:seon.error/keys [source id at process basis-t]
    evidence-bytes :seon.config.error/max-evidence-bytes
    :seon.sci.admit/keys [caps]
    run-id :seon.turn/id
    agent-id :seon.agent/id}]
  (let [failure (throwable source)
        class-name (when failure (.getName (class failure)))
        error-kind (kind source failure)
        source (meaningful-source source)
        admitted (bounded-admission source caps)
        full-edn (:seon.sci.admit/edn admitted)
        ;; ONE KEY, AND IT IS SUPPLIED. The fault family's own declared bound
        ;; decides how much evidence the FACT keeps; the blob threshold
        ;; decides where the complete evidence lives.
        ;; `:seon.error/inline-limit` was the same number under a second
        ;; spelling and is deleted. The bound is a REQUIRED member of
        ;; `:seon.error/normalize-request` — a fallback to the bootstrap
        ;; number when a caller omitted it was a silent fallback on the
        ;; ordinary path, which is a defect even while it is right.
        inline-limit evidence-bytes
        projected-source (:seon.sci.admit/value admitted)
        instrument-data (projected-instrument-data projected-source)
        flow? (map? source)
        ;; THE SIZE IS THE SOURCE'S, NOT THE SUBSTITUTE'S. When the whole
        ;; evidence went over the storage bound the marker is a few dozen
        ;; bytes, and reporting those as `data-size` said the evidence was
        ;; small precisely when it was too large to keep. An
        ;; `:unserializable` marker measured NOTHING — there is no size to
        ;; report — so the fact carries no `data-size` at all rather than the
        ;; substitute's, and the marker's own reason is what says why.
        marker (::marker admitted)
        data-size (if marker
                    (:seon.sci.admit/bytes marker)
                    (utf8-size full-edn))
        base-fact
        (cond-> {:seon.error/id id
                 :seon.error/at at
                 :seon.error/process process
                 :seon.error/kind error-kind
                 :seon.error/signature (signature process class-name error-kind
                                                  (top-frame failure))
                 :seon.error/capped? true}
          (int? data-size) (assoc :seon.error/data-size (long data-size))
          class-name (assoc :seon.error/throwable-class class-name)
          (and flow? (::flow/pid source))
          (assoc :seon.error/proc (::flow/pid source))
          (and flow? (::flow/op source)) (assoc :seon.error/op (::flow/op source))
          (and flow? (::flow/cid source))
          (assoc :seon.error/cid (::flow/cid source))
          ;; THE FAILING FUNCTION IS RECORDED FOR EVERY CLASS. The
          ;; contract reporter knows it by name; every other class knows
          ;; it by the frame that threw, and a fault carrying neither
          ;; routes to no steward at all.
          (or (:seon.instrument/fn instrument-data)
              (stack-failing-function failure))
          (assoc :seon.instrument/fn
                 (or (:seon.instrument/fn instrument-data)
                     (stack-failing-function failure)))
          (:seon.instrument/arm instrument-data)
          (assoc :seon.instrument/arm (:seon.instrument/arm instrument-data))
          basis-t (assoc :seon.error/basis-t basis-t)
          run-id (assoc :seon.error/run [:seon.turn/id run-id])
          agent-id (assoc :seon.error/agent
                          [:seon.agent/id agent-id]))
        fact (fit-fact-payload
              base-fact source
              (message source failure) instrument-data caps inline-limit)
        fact (assoc fact :seon.error/capped?
                    ;; HONEST WHEN EVERYTHING WAS DROPPED. Comparing the two
                    ;; EDN strings alone reported "nothing omitted" for the
                    ;; one case where nothing was kept: the FULL admission
                    ;; also answered with the marker, so both sides were the
                    ;; same handful of bytes (F1, 2026-09-07).
                    (boolean (or marker
                                 (not= full-edn
                                       (:seon.error/data-edn fact)))))]
    {:seon.error/fact fact
     :seon.error/data-content full-edn}))

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
  - projects the meaningful source — retaining `::flow/msg` while excluding
    disposable `::flow/state` — through `seon.sci.admit/admit` in `:record`
    mode with the request's declared caps and `(constantly nil)` as the
    interrupt-fn;
  - stores a fitted projection in `data-edn`, records the full meaningful
    projection's UTF-8 byte size in `data-size`, and marks `capped?` whenever
    admission or inline fitting omitted evidence;
  - lifts `::flow/pid`, `::flow/op` and `::flow/cid` into `proc`, `op`
    and `cid`, each present exactly when the arriving shape carried it;
  - computes `signature` as `sha-256` over
    `[process, class, kind, top frame]`;
  - emits `run` and `agent` as lookup refs (`[:seon.turn/id id]`)
    exactly when the request supplied those ids.

  `id`, `at` and `process` are the caller's: identity and the clock are
  not this function's to invent, and a pure normalizer is a testable
  one. The result is transactable as-is — every key is a declared
  attribute of `:seon.error/fact` and nothing rides along."
  {:malli/schema [:=> [:cat :seon.error/normalize-request] :seon.error/fact]}
  [request]
  (:seon.error/fact (prepare request)))

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
    agent-id :seon.agent/id}]
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
      agent-id (assoc :seon.agent/id agent-id))))

(defn- fact-source
  [fact]
  (try
    (admit/semantic-value (edn/read-string (:seon.error/data-edn fact)))
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

(defn- value-description
  [value]
  (cond
    (nil? value) "nil"
    (instance? clojure.lang.LazySeq value) "a lazy sequence"
    (vector? value) "a vector"
    (map? value) "a map"
    (set? value) "a set"
    (sequential? value) "a sequence"
    (string? value) "a string"
    (keyword? value) "a keyword"
    (symbol? value) "a symbol"
    (boolean? value) "a boolean"
    (integer? value) "an integer"
    (number? value) "a number"
    :else (str "an instance of " (.getName (class value)))))

(defn explain-problem
  "Translate Malli's structured problem into semantic refusal evidence.
   No message parsing or value printing occurs at this seam."
  {:malli/schema [:=> [:cat :seon.error/explain-request] :seon.error/problem-description]}
  [{:seon.error/keys [problem path argument parent]}]
  (let [check (let [check (:check problem)] (if (sequential? check) (first check) check))
        checked-output (when check
                         (or (:malli.core/explain-output check)
                             (when-let [output (:output (m/-function-info (:schema problem)))]
                               {:schema output :value (:malli.core/result check)})))
        problem (if checked-output
                  {:schema (:schema checked-output) :value (:value checked-output)}
                  problem)
        missing? (= :malli.core/missing-key (:type problem))
        entry-schema (when (and missing?
                                (= :map (m/type (m/deref-all (:schema problem)))))
                       (some (fn [[entry-key _ entry]]
                               (when (= entry-key (last path)) entry))
                             (m/children (m/deref-all (:schema problem)))))
        problem (cond-> problem entry-schema (assoc :schema entry-schema))
        schema-type (m/type (m/deref-all (:schema problem)))
        message (or (me/error-message (cond-> problem missing? (dissoc :type)))
                    "the declared schema")
        expected (case schema-type
                   :vector "a vector" :sequential "a sequence" :map "a map"
                   :set "a set" :string "a string" :int "an integer"
                   :double "a double" :boolean "a boolean" :keyword "a keyword"
                   :qualified-keyword "a namespaced keyword" :symbol "a symbol"
                   :qualified-symbol "a namespaced symbol" :nil "nil"
                   :enum (str "either "
                              (str/join " or "
                                        (map pr-str (m/children (m/deref-all (:schema problem))))))
                   :fn message
                   :or (or (:error/message (m/properties (:schema problem)))
                           (str "a value satisfying " message))
                   (str "a value satisfying " message))]
    (cond-> {:seon.error/path (vec path)
     :seon.error/argument argument
     :seon.error/expected (m/form (:schema problem))
     :seon.error/expected-description
     (if missing? (str "the required key " (pr-str (last path)) " with " expected) expected)
     :seon.error/offending (if (and missing? (map? parent)) parent (:value problem))
     :seon.error/actual-description
     (if missing? (str "a map missing " (pr-str (last path))) (value-description (:value problem)))
     :seon.error/fix
     (cond
       missing? (str "Supply " (pr-str (last path)) " with " expected ".")
       (and (= :vector schema-type) (sequential? (:value problem)))
       "Convert the sequence with vec before calling the function."
       (= :fn schema-type) message
       checked-output "Return a value satisfying the declared result contract for the shown input."
       :else (str "Supply " expected " at " (pr-str (vec path)) "."))}
      check (assoc :seon.error/input (first (:smallest check))))))

(defn- refusal-value-text
  [unit value path]
  (let [root (or (:seon.repl/handle unit) (:seon.render.value/root unit))
        profile (:seon.render/profile unit)
        unit (cond-> (dissoc unit :seon.repl/handle :seon.render.value/root)
               (and path (qualified-symbol? root) profile)
               (assoc :seon.render/profile
                      (assoc profile :seon.print/requery-id (list 'get-in root path))))
        projection
        (render.value/prepare
         (-> unit
             (assoc :seon.render/value value
                    :seon.render.value/options {:seon.render.value/structural? true})
             (update :seon.render.call/id
                     #(or % [:seon.error/diagnostic-offending])))
         (get unit :seon.render/output :seon.render/ai))]
    (if (string? (:seon.render.value/text projection))
      (:seon.render.value/text projection)
      (str "<value rendering unavailable: " (:seon.error/message projection) ">"))))

(defn- reader-correction
  "Select a missing declared argument key; no spelling heuristic is used."
  [unit evidence]
  (when-let [database (:seon.db/db unit)]
    (when-let [operation (:seon.sci.reader/call evidence)]
      (let [spec (db/q '[:find ?spec . :in $ ?sym
                        :where [?f :seon.fn/sym ?sym] [?f :seon.fn/spec ?spec]]
                      database (str operation))
            position (:seon.sci.reader/argument-index evidence)
            supplied ((requiring-resolve 'seon.call-preparation/supplied-map-entries)
                      database (str operation))
            supplied-keys (into #{} (map #(nth % 2)) (when (vector? supplied) supplied))
            container (first (:seon.sci.reader/containers evidence))
            present (set (take-nth 2 (:edamame/elements container)))]
        (when (and (string? spec) (nat-int? position)
                   (= "{" (:edamame/opened-delimiter container)))
          (let [projection (schema/projection-from-database database)
                compiled (m/function-schema (edn/read-string spec)
                          {:registry (:seon.schema.projection/registry projection)})
                candidates
                (into #{}
                      (mapcat
                       (fn [arity]
                         (let [input (:input (m/-function-info arity))
                               argument (when (= :cat (m/type input))
                                          (nth (m/children input) position nil))
                               argument (when argument (m/deref-all argument))]
                           (when (and argument (= :map (m/type argument)))
                             (for [[key properties _] (m/children argument)
                                   :when (and (not (:optional properties))
                                              (not (contains? supplied-keys key))
                                              (not (contains? present key)))]
                               key)))))
                      (m/-function-schema-arities compiled))]
            (when (= 1 (count candidates)) (first candidates))))))))

(defn- refusal-data
  [unit fact data]
  (let [evidence (merge fact data)]
    (cond
      (seq (:seon.error/problems data)) data

      (find evidence :seon.sci.reader/text)
      (let [correction (reader-correction unit evidence)]
      {:seon.error/diagnostic-operation (or (:seon.sci.reader/call evidence) 'seon.sci.reader/read)
       :seon.error/problems
       [{:seon.error/argument "source"
         :seon.error/path (into [] (keep evidence) [:seon.sci.reader/line :seon.sci.reader/column])
         :seon.error/expected :seon.cluster.eval/source
         :seon.error/expected-description "readable Clojure source"
         :seon.error/offending (or (:seon.sci.reader/token evidence) (:seon.sci.reader/text evidence))
         :seon.error/actual-description "unreadable source"
         :seon.error/fix
         (cond
           correction (str "Use " correction ".")
           (:seon.sci.reader/prose-span? evidence) "Prose must start with ; on every line."
           (= :stray-closer (:seon.sci.reader/error-kind evidence))
           "Balance the delimiters in this reply; every reply is read from scratch."
           :else (str "Correct the reader error: " (:seon.error/message fact)))}]})

      (and (:seon.schema/definition evidence) (:seon.schema/error evidence))
      {:seon.error/diagnostic-operation 'seon.schema/register!
       :seon.error/problems
       [{:seon.error/argument (str (:seon.schema/identity evidence))
         :seon.error/path (get evidence :seon.schema/path [])
         :seon.error/expected :seon.schema/definition
         :seon.error/expected-description "a complete authored schema"
         :seon.error/offending (:seon.schema/definition evidence)
         :seon.error/actual-description "an incomplete schema"
         :seon.error/fix (:seon.error/message fact)}]}

      (:seon.sci.eval/symbol evidence)
      {:seon.error/diagnostic-operation 'seon.sci.eval/evaluate
       :seon.error/problems
       [{:seon.error/argument "source"
         :seon.error/path []
         :seon.error/expected :symbol
         :seon.error/expected-description "a resolvable symbol"
         :seon.error/offending (:seon.sci.eval/symbol evidence)
         :seon.error/actual-description "an unresolved symbol"
         :seon.error/fix "Define or require this symbol."}]}

      (:seon.error/diagnostic-operation evidence)
      (let [expected (:seon.error/diagnostic-expected evidence)
            offending (:seon.error/diagnostic-offending evidence)
            member (:seon.error/diagnostic-member evidence)
            compiled (try
                       (m/schema expected
                                 (when-let [database (:seon.db/db unit)]
                                   {:registry (:seon.schema.projection/registry
                                               (schema/projection-from-database database))}))
                       (catch Exception _ nil))
            problem (when compiled
                      (explain-problem
                       {:seon.error/problem {:schema compiled :value offending}
                        :seon.error/path [] :seon.error/argument (str member)}))]
        {:seon.error/diagnostic-operation (:seon.error/diagnostic-operation evidence)
         :seon.error/problems
         [(or problem
              {:seon.error/argument (str member)
               :seon.error/path (get evidence :seon.db/path [])
               :seon.error/expected expected
               :seon.error/expected-description "the declared requirement"
               :seon.error/offending offending
               :seon.error/actual-description (value-description offending)
               :seon.error/fix (str (:seon.error/message fact)
                                    " Inspect the named requirement before retrying.")})]})

      :else data)))

(defn- refusal-text
  [unit fact data]
  (let [stored-problems? (seq (:seon.error/problems data))
        data (refusal-data unit fact data)
        operation (:seon.error/diagnostic-operation data)
        problems (:seon.error/problems data)
        example (or (not-empty (get-in fact [:seon.error/doc :example]))
                    (when (and (:seon.db/db unit) (qualified-symbol? operation))
                      (let [doc (db/q '[:find ?doc . :in $ ?sym
                                        :where [?f :seon.fn/sym ?sym]
                                               [?f :seon.fn/doc ?doc]]
                                      (:seon.db/db unit) (str operation))]
                        (when (string? doc)
                          (not-empty
                           (:example
                            ((requiring-resolve 'seon.sci.eval/docstring-parts) doc)))))))]
    (when (and operation (seq problems))
      (str/join
       "\n"
       (map-indexed
        (fn [index {:seon.error/keys [path argument expected expected-description
                               offending actual-description fix input result-contract] :as problem}]
          (let [location (when stored-problems?
                           [:seon.error/data :seon.error/problems index])]
          (str operation " refused " argument " at " (pr-str path)
               ": expected " expected-description " ("
               (refusal-value-text unit expected (when location (conj location :seon.error/expected)))
               "), got " actual-description
               " " (refusal-value-text unit offending (when location (conj location :seon.error/offending)))
               ". Fix: " fix
               (when (find problem :seon.error/input)
                 (str " Input: " (refusal-value-text unit input nil) "."))
               (when result-contract
                 (str " Result contract: " (refusal-value-text unit result-contract nil) "."))
               " Example: " (or example "No docstring example is available."))))
        problems)))))

(defn refusal-prose
  "`:seon.render/ai` — a refused transition and its atomic outcome."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [error-value]
  (let [fact (or (:seon.error/fact error-value) error-value)
        source (if (:seon.error/data-edn fact)
                 (fact-source fact)
                 fact)
        request (:seon.turn/request source)
        transition (:seon.turn/transition source)
        operation (or (some-> transition name) "transition")
        run-id (or (:seon.turn/id request)
                   (:seon.turn/id source)
                   (second (:seon.error/run fact)))
        rule (:seon.turn/rule source)]
    (str "The " operation (when run-id (str " of " run-id))
         " was refused atomically by " rule
         ". Nothing from this " operation " committed. Re-read the run before"
         " deciding whether a new transition is eligible."
         (when (:seon.error/id fact)
           (str " " (evidence-prose fact))))))

(defn instrumentation-prose
  "`:seon.render/ai` — detailed steering for a validation failure."
  {:malli/schema
  [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [error-value]
  (let [fact (or (:seon.error/fact error-value) error-value)
        data (if (:seon.error/data-edn fact)
               (flat-data fact)
               (:seon.error/data fact))
        operation (or (:seon.error/diagnostic-operation data)
                      (:seon.instrument/fn fact))
        member (or (:seon.error/diagnostic-member data)
                   (:seon.instrument/arm fact))
        expected (or (:seon.error/diagnostic-expected data)
                     (:seon.instrument/expected fact))
        received (or (:seon.error/diagnostic-offending data)
                     (:seon.instrument/args fact))]
    (if-let [prose (refusal-text error-value fact data)]
      prose
      (if operation
      (str (when-let [message (:seon.error/message fact)] (str message "\n"))
           "Contract violation in " operation " " (name member)
           ": expected " (pr-str expected)
           ", received " (pr-str received)
           (if (= :arguments member)
             ". The call was stopped before the function ran. "
             ". The function returned an invalid value. ")
           (when (:seon.error/id fact)
             (evidence-prose fact))
           (when-let [documentation (:seon.error/doc fact)]
             (str "\n" (pr-str {:seon.error/doc documentation}))))
      (str (:seon.error/message fact)
           (when (:seon.error/id fact)
             (str " " (evidence-prose fact))))))))

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

      (= kind :seon.turn/refused)
      (refusal-prose fact)

      ;; The latest occurrence's OWN message and run ride this clause. An
      ;; escalation that named only a kind and an id made the reader look
      ;; the failure up before it could act — the detail the deleted
      ;; hand-rolled run-phase escalation carried, folded into the one
      ;; owner. Both are declared facts on the entity, omitted when absent.
      (= reason :recurring)
      (str/join
       " "
       (remove
        nil?
        [(if-let [occurrence (:seon.error/occurrence notice)]
           (str "Core fault " kind " reached " occurrence
                " occurrences in process " process " (notification limit "
                (:seon.error/notification-limit notice) ").")
           (str "Core fault " kind
                " reached its final notification for signature "
                signature "."))
         (when message (str "Latest: " message))
         (when run-id (str "It interrupted run " run-id "."))
         (str "Further occurrences remain in seon.problems but will not"
              " message you. Latest error: " id ". Signature: "
              signature ".")]))

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
         ;; The run clause rides the RUN, not the reason. An agent
         ;; attributed with no run took this branch and read "It
         ;; interrupted run ." on a live cluster (2026-08-08 probe) —
         ;; the omit-when-absent rule this docstring states, broken by
         ;; the one clause that assumed its fact was always there.
         (case reason
           :your-run (when run-id (str "It interrupted run " run-id "."))
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
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
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
  {:malli/schema [:=> [:cat :seon.error/notice] [:string {:min 1}]]}
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
                       (get-in source [:seon.turn/request
                                       :seon.turn/id])
                       "-"))
       (when-let [rule (:seon.turn/rule source)] (str "rule=" rule))
       (when-let [transition (:seon.turn/transition source)]
         (str "transition=" transition))
       (when (= kind :seon.turn/refused) "committed=false")
       (when-let [phase (:seon.ai/error-class data)] (str "phase=" phase))
       (when (contains? data :seon.ai/request-transmitted?)
         (str "transmitted=" (:seon.ai/request-transmitted? data)))
       (when (contains? data :seon.ai/response-started?)
         (str "response-started=" (:seon.ai/response-started? data)))
       (when (contains? data :seon.ai/output-observed?)
         (str "output=" (:seon.ai/output-observed? data)))
       (when (= :transport-before-send (:seon.ai/error-class data))
         "disposition=failover-now")
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
                :where [?agent :seon.agent/id ?id]]
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
  The id is DERIVED from error, recipient, and reason, which makes delivery
  idempotent by construction — re-committing the same error upserts the
  same message instead of double-sending it, and the double-send
  question the plan has been carrying since 2026-07-26 does not arise on
  this path. `about` points at the fact through the shared tempid, and
  its ABSENCE on an ordinary user message is what makes the storm fence
  computable without a flag."
  [fact recipient reason notification]
  {:seon.message/id (id/id (random-uuid) 8) :seon.message/to [:seon.agent/id recipient] :seon.message/content (ai-prose
    (notice (merge {:seon.error/fact fact
                    :seon.error/reason reason
                    :seon.agent/id recipient}
                   notification))) :seon.message/about (fact-tempid (:seon.error/id fact)) :seon.message/inbox [:seon.agent/id recipient]})

(defn steward-call
  "Route one fault to the steward of the failing function's namespace.

  Decided INSIDE the committing transaction against the mid-transaction
  database value, and merged onto the fact through the same string
  tempid the fact carries, so the routing and the fact are one commit
  and no caller pre-read can be stale by the time it lands.

  The chain is `:seon.instrument/fn` -> `:seon.fn/ns` ->
  `:seon.ns/steward`, all declared facts. No steward, no datom: absence
  is the state, the fact is still committed, and nothing about the fault
  record depends on someone being on the hook for it. `:seon.error/agent`
  is a different question — whom it happened TO — and both relations
  stand on the same fact.

  ASSERTING IT WAKES THE STEWARD, including when the steward is the
  agent whose own code failed. There is no self-exclusion: the turn
  bound is what stops that loop, because a fault is a declared INSIDE
  wake and never refills the bound it spends."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/steward-request]
                  :seon.store/transaction-data]}
  [db {fact-id :seon.error/id failing-fn :seon.instrument/fn}]
  (if-let [steward (and failing-fn
                        (db/q '[:find ?agent .
                                :in $ ?sym
                                :where
                                [?function :seon.fn/sym ?sym]
                                [?function :seon.fn/ns ?namespace]
                                [?namespace :seon.ns/steward ?agent]]
                              db failing-fn))]
    [{:db/id (fact-tempid fact-id)
      :seon.error/steward steward}]
    []))

(defn commit-tx
  "Transaction data committing one error and everything it must say.
  PURE over a database value: the fact, and zero to two explanation
  messages, in ONE vector so `db/transact!` commits them together and
  there is no torn window where an error exists that nobody was told
  about. Returning data rather than transacting is what keeps this
  namespace free of the store — the dependency runs `store -> error`,
  never both ways — and it is why the whole escalation rule is testable
  against an in-memory database value with no cluster at all.

  DELIVERY IS THE EXISTING WAKE. `:seon.message/to` is the wake
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
  `:seon.message/to`, and the woken loop hit the same broken
  code. Delivery being the wake attribute is exactly what makes error
  delivery free — and exactly what makes an unbounded error path a
  self-feeding fire. A fault now ALSO routes to the steward of the
  failing function's namespace through `:seon.error/steward`
  (`steward-call`), which is a listened attribute of its own — so the
  cycle no longer needs a message to exist, and the two bounds that stop
  it are this recurrence limit and the agent's turn bound (both the
  steward ref and the notification message are declared INSIDE wakes,
  so neither refills what it spends).
  error -> message -> wake -> turn -> error is a real cycle,
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
       evidence-bytes :seon.config.error/max-evidence-bytes
       supplied-fact :seon.error/fact
       :seon.sci.admit/keys [caps]
       run-id :seon.turn/id
       agent-id :seon.agent/id
       escalate-to :seon.config.error/escalate-to
       limit :seon.config.error/recurrence-limit}]
  (let [fact (or supplied-fact
                 (normalize
                  (cond-> {:seon.error/source source
                           :seon.error/id id
                           :seon.error/at at
                           :seon.error/process process
                           :seon.sci.admit/caps caps
                           ;; THE BOUND TRAVELS WITH THE REQUEST. It is a
                           ;; declared member of this request, so a caller
                           ;; that has dials hands it and one that does not
                           ;; is refused by the contract — never a fallback
                           ;; to a bootstrap number nobody chose here.
                           :seon.config.error/max-evidence-bytes
                           evidence-bytes}
                    basis-t (assoc :seon.error/basis-t basis-t)
                    (and run-id
                         (entity-exists? db :seon.turn/id run-id))
                    (assoc :seon.turn/id run-id)
                    (and agent-id
                         (entity-exists? db :seon.agent/id agent-id))
                    (assoc :seon.agent/id agent-id))))
        fact (cond-> fact
               (and (:seon.error/run fact)
                    (not (entity-exists?
                          db :seon.turn/id
                          (second (:seon.error/run fact)))))
               (dissoc :seon.error/run)

               (and (:seon.error/agent fact)
                    (not (entity-exists?
                          db :seon.agent/id
                          (second (:seon.error/agent fact)))))
               (dissoc :seon.error/agent))
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
    (into [(assoc fact :db/id (fact-tempid id))
           ;; The steward is decided inside the commit, not here: the
           ;; call merges its ref onto this same tempid.
           [:db.fn/call #'steward-call
            (cond-> {:seon.error/id id}
              (:seon.instrument/fn fact)
              (assoc :seon.instrument/fn (:seon.instrument/fn fact)))]]
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
    (render.value/transacted value)))

(defn- class-properties
  [forms schema-key]
  (some-> (get forms schema-key)
          schema.form/namespaced-properties))

(defn- matched-error-classes
  [value]
  (when-let [projection (schema/current-projection)]
    (let [forms (:seon.schema.projection/forms projection)]
      (->> (schema/matching-shapes-in projection value)
           (filter (fn [{schema-key :seon.schema/key}]
                     (true? (:seon.error/class
                             (class-properties forms schema-key)))))
           vec))))

(defn error?
  "True when `value` matches at least one declared error-class schema.

  Registry-free leaves use the structural fallback: a map containing
  `:seon.error/message`. Once a projection is active, its declared classes are
  the complete authority and a message alone is not an error class."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (if (schema/current-projection)
    (boolean (seq (matched-error-classes value)))
    (and (map? value)
         (contains? value :seon.error/message))))

(defn- error-marker
  [value]
  (or
   (when-let [class-row (first (matched-error-classes value))]
     (let [marker-attributes
           (disj (:seon.schema/required-attrs class-row)
                 :seon.error/message)]
       (first
        (sort-by (comp str first)
                 (select-keys value marker-attributes)))))
   (when-let [kind (:seon.error/kind value)]
     [:seon.error/kind kind])))

(defn- error-evidence
  [value marker]
  (->> value
       (remove (fn [[attribute _]]
                 (or (= :seon.error/message attribute)
                     (= (some-> marker first) attribute)
                     (= :seon.error/id attribute)
                     (contains? render-context-attributes attribute))))
       (sort-by (comp str first))))

(defn- evidence-text
  [evidence]
  (when (seq evidence)
    (str "Evidence: "
         (str/join ", "
                   (map (fn [[attribute evidence-value]]
                          (str attribute "=" (pr-str evidence-value)))
                        evidence))
         ".")))

(defn- default-ai-prose
  [value]
  (let [marker (error-marker value)
        evidence (error-evidence value marker)]
    (str/join
     "\n"
     (remove
      nil?
      [(:seon.error/message value)
       (when marker
         (str "Failed: " (first marker) "=" (pr-str (second marker)) "."))
       (evidence-text evidence)
       "Re-read the current facts before retrying or changing state."]))))

(defn- evidence-path
  [id]
  (render.route/path :seon.render.route/data
                     {}
                     {:entity (pr-str [:seon.error/id id])
                      :offset "0"}))

(defn render-ai
  "Render the flat error value, preserving its recorded diagnostic data."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        source (when (:seon.error/data-edn value) (fact-source value))]
    (or (refusal-text unit (or source value) (:seon.error/data (or source value)))
        (:seon.error/message (or source value))
        "Error evidence is unavailable.")))

(defn render-html
  "Render one fault's kind, message, time, function, turn, and evidence link."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.render/hiccup]}
  [unit]
  (let [value (if (map? (:seon.render/value unit))
                (:seon.render/value unit) unit)
        turn (:seon.error/run value)
        turn-ref (if (map? turn)
                   (if-let [id (:seon.turn/id turn)]
                     [:seon.turn/id id] (:db/id turn))
                   turn)]
    (into
     [:article {:class "seon-family-entry seon-error-entry"}
      [:p {:class "seon-kicker"} (some-> (:seon.error/kind value) name)]
      [:h3 {:class "seon-error-message"}
       (or (refusal-text (assoc unit :seon.render/output :seon.render/html)
                         value (:seon.error/data value))
           (:seon.error/message value))]]
     (concat
      (when-let [at (:seon.error/at value)]
        (let [instant (str (if (instance? java.util.Date at)
                             (.toInstant ^java.util.Date at) at))]
          [[:time {:class "seon-error-at" :datetime instant :title instant
                   :data-text (str "new Date('" instant "').toLocaleString()")}
            (if (inst? at) (.format (java.text.SimpleDateFormat. "MMM d, HH:mm:ss") at) instant)]]))
      (when-let [function (:seon.instrument/fn value)]
        [[:p {:class "seon-error-function"} "Function: " (str function)]])
      (when turn-ref
        [[:p {:class "seon-error-run"}
          [:a {:href (render.route/path :seon.render.route/data {}
                                        {:entity (pr-str turn-ref)})}
           (str "Turn: " (if (vector? turn-ref) (second turn-ref) "identity unavailable"))]]])
      (when-let [id (:seon.error/id value)]
        [[:p {:class "seon-error-link"}
           [:a {:href (evidence-path id)} "Inspect evidence"]]])))))

(defn- fault-order
  [fault]
  [(if-let [at (:seon.error/at fault)] (- (.getTime ^java.util.Date at)) 0)
   (str (:seon.error/id fault))])

(defn- run-identity
  [database reference]
  (let [eid (cond
              (map? reference) (:db/id reference)
              (integer? reference) reference)]
    (or (:seon.turn/id reference)
        (when eid
          (let [row (db/pull database [:seon.turn/id] eid)]
            (when-not (:seon.error/kind row)
              (:seon.turn/id row)))))))

(defn- fault-entities
  [faults]
  (->> (if (coll? faults) faults [])
       (map (fn [fault]
              (if (map? fault) fault
                  {:seon.error/kind :seon.render/unavailable
                   :seon.error/message (str "Fault entity was not acquired: " (pr-str fault))})))
       (sort-by fault-order)))

(defn- faults-input
  [unit]
  (let [value (:seon.render/value unit)]
    (get value (:seon.render.walk/attribute unit) value)))

(defn faults-form
  "Read repair work for root, or for an agent with a routed fault."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/form]]}
  [unit]
  (let [agent (faults-input unit)
        row (db/pull (:seon.db/db unit)
                     '[:seon.agent/id {:seon.error/_steward [:seon.error/id]}]
                     agent)]
    (if (or (= "root" (:seon.agent/id row))
            (seq (:seon.error/_steward row))
            (:seon.error/kind row))
      {:seon.repl/comment ";; I should inspect faults routed to me as steward; fixing them is my job."
       :seon.repl/form
       (list 'seon.db/q
             (list 'quote
                   [:find [(list 'pull '?f [:seon.error/kind :seon.error/message :seon.instrument/fn]) '...]
                    :where ['?f :seon.error/steward agent]]))}
      nil)))

(defn render-faults-ai
  "Emit the steward's read, or render already acquired fault entities."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/source]]}
  [unit]
  (let [faults (faults-input unit)]
    (if (and (sequential? faults) (every? map? faults))
      (when (seq faults) (str/join "\n" (map render-ai (fault-entities faults))))
      (let [entry (faults-form unit)]
        (when-let [form (:seon.repl/form entry)]
          (str (:seon.repl/comment entry) "\n" (repl/source-text form)))))))

(defn render-faults-html
  "Render faults routed to a steward, or already acquired faults, newest first."
  {:malli/schema [:function [:=> [:cat :seon.render/unit] :seon.render/hiccup] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.db/database-value] :seon.render/hiccup]]}
  ([unit] (render-faults-html (faults-input unit) (:seon.db/db unit)))
  ([faults database]
  (let [acquired? (and (sequential? faults) (not (keyword? (first faults))))
        row (when-not acquired?
              (db/pull database '[{:seon.error/_steward [* {:seon.error/run [:seon.turn/id]}]}]
                       faults))
        references (cond acquired? faults
                         (:seon.error/kind row) [row]
                         :else (:seon.error/_steward row))
        entities (fault-entities
                  (mapv #(if (map? %) %
                             (db/pull database '[* {:seon.error/run [:db/id :seon.turn/id]}] %))
                        references))]
    (into [:section {:class "seon-family-entry seon-error-faults"}
           [:h2 (str "Faults (" (count entities) ")")]]
          (if (seq entities)
            (map render-html entities)
            [[:p {:class "seon-error-faults-empty"}
              (if acquired? "No fault is recorded against this agent."
                  "No fault is routed to this agent.")]])))))

(defn time-limit-prose
  "`:seon.render/ai` — evaluation time-limit evidence without guessing cause."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        entries (or (:seon.eval/fn-entries value)
                    (:seon.sci.eval/time-limit value))]
    (str/join
     " "
     (remove nil?
             [(:seon.error/message value)
              (when (some? entries)
                (str "Recorded function-body entries: " entries "."))
              "Many entries indicate a spin; few indicate time spent inside a host call. Inspect the called function before retrying."]))))

(defn edit-prose
  "`:seon.render/ai` — selection evidence for an edit that did not apply."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        marker (error-marker value)
        evidence (error-evidence value marker)]
    (str/join
     "\n"
     (remove nil?
             [(:seon.error/message value)
              (when marker
                (str "Selection: " (first marker) "="
                     (pr-str (second marker)) "."))
              (evidence-text evidence)
              "Re-read the exact source and narrow the edit selection before applying it again."]))))

(defn elision-prose
  "`:seon.render/ai` — a neutral account of bounded render-walk elision."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        marker (error-marker value)
        evidence (error-evidence value marker)]
    (str/join
     " "
     (remove nil?
             ["Additional render-walk content was elided by the active render profile."
              (evidence-text evidence)
              "Request the next offset when more detail is needed."]))))

(defn elision-html
  "`:seon.render/html` — a neutral elision notice, never an error card."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.render/hiccup]}
  [unit]
  (let [value (rendered-error-value unit)
        marker (error-marker value)
        evidence (error-evidence value marker)]
    (into
     [:aside {:class "seon-family-entry seon-render-elision"}
      [:p "Additional render-walk content was elided by the active render profile."]]
     (when (seq evidence)
       [(into [:dl {:class "seon-render-elision-evidence"}]
              (map (fn [[attribute evidence-value]]
                     [:div
                      [:dt (str attribute)]
                      [:dd (pr-str evidence-value)]]))
              evidence)]))))

(defn unclassified-prose
  "`:seon.render/ai` — an honest failure projection with no class match."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        marker (error-marker value)
        evidence (error-evidence value marker)]
    (str/join
     "\n"
     (remove nil?
             [(:seon.error/message value)
              "No registered error class recognized the original failure."
              (evidence-text evidence)
              "Inspect the admitted projection and declare the missing class before retrying."]))))

(defn mcp-prose
  "`:seon.render/ai` — retrieval evidence for a failed MCP value lookup."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        marker (error-marker value)
        evidence (error-evidence value marker)]
    (str/join
     "\n"
     (remove nil?
             [(:seon.error/message value)
              (when marker
                (str "Lookup: " (first marker) "="
                     (pr-str (second marker)) "."))
              (evidence-text evidence)
              "Re-read the current cluster status or value identity before requesting the data again."]))))

(defn index-refusal-prose
  "`:seon.render/ai` — the precise evidence that stopped program indexing."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        marker (error-marker value)
        evidence (error-evidence value marker)]
    (str/join
     "\n"
     (remove nil?
             [(:seon.error/message value)
              (when marker
                (str "Indexing stopped at " (first marker) "="
                     (pr-str (second marker)) "."))
              (evidence-text evidence)
              "Repair the named source or declaration evidence, then rerun initialization."]))))
