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
  rendering dependencies. Callers commit through the one boundary:
  `seon.cluster/commit-fault!` for Throwables off flow's error channel,
  the turn loop for a refused transaction, and maintenance settlement.

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
  the canonically ordered identity attributes (kind, Throwable class, function, frame) — deliberately WITHOUT the
  message, because a message carrying an id, a path or a timestamp
  makes every occurrence unique and recurrence undetectable, which is
  exactly the count the escalation rule needs. One transaction function
  increments the occurrence count; recurrence sums those counts through
  occurrence process refs. The error identity contains no process identity.

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
            [seon.call-preparation :as call-preparation]
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

;;; LOAD-CYCLE BOUNDARY. `seon.sci.eval` requires `seon.error` transitively,
;;; so this namespace cannot require it back. One resolution, realized at
;;; first use, instead of a `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private sci-eval-docstring-parts
  (delay (requiring-resolve 'seon.sci.eval/docstring-parts)))

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
  "The Throwable's first complete stack frame as Clojure data."
  [failure]
  (when-let [^StackTraceElement frame (when failure (first (.getStackTrace ^Throwable failure)))]
    (when-let [file (.getFileName frame)]
      [(symbol (.getClassName frame)) (symbol (.getMethodName frame))
       file (long (.getLineNumber frame))])))

(defn- signature
  "Identity of what failed, independent of process, agent, turn and message."
  [error-kind class-name function frame]
  (id/id (into (sorted-map)
               (cond-> {:seon.error/kind error-kind}
                 class-name (assoc :seon.error/throwable-class (symbol class-name))
                 function (assoc :seon.instrument/fn (symbol function))
                 frame (assoc :seon.error/frame frame)))
         64))

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
                    (symbol frame-ns simple))))))
          (.getStackTrace failure))))

(defn- contract-violation-data
  "One contract violation's own data, from a source in any of its shapes."
  [source]
  (let [error-value (if (map? (::flow/ex source))
                      (:data (::flow/ex source))
                      source)]
    (when (= :seon.instrument/contract-violated
             (:seon.error/kind error-value))
      (:seon.error/data error-value))))

(defn- offending-entry
  "The map entry holding the value that actually broke the contract, if any.

  WHAT BROKE THE CONTRACT IS A QUERY, NOT A RECONSTRUCTION.
  `:seon.error/diagnostic-offending` is what the ARM checked — the caller's
  whole argument vector, or the whole returned value — so for a function whose
  argument carries an SCI context it is megabytes, becomes the over-bound
  marker, and the fault then names the violation's PATH with no copy of the
  value at it (measured 2026-09-17: fault `7710efbc…` on `default` recorded
  `:seon.instrument/args` as `#:seon.sci.admit{:reason :over-bound}` and no
  offending value at all,
  `docs/prds/steward-platform/research/over-bound-evaluation-contract-2026-09-17.md`).
  The first problem's leaf IS the value at the violation path, and it is
  bounded by what that value is rather than by the request it rode in. This
  reads the SOURCE, never its projection: the leaf sits four levels down and
  a depth cap would silently drop exactly the evidence being recorded.

  A map entry, not the value: an offending `nil` or `false` is still a value
  that broke a contract, and absence here means the violation carried no
  problems — two different answers."
  [error-value]
  (find (get-in (contract-violation-data error-value) [:seon.error/problems 0])
        :seon.error/offending))

(defn- admitted-size
  "One value's own size, measured by the same admission that stores it.

  Over the bound the marker reports the bytes it refused, so the number is
  the value's and never the substitute's — the same rule `prepare` applies to
  `:seon.error/data-size`."
  [value caps]
  (let [admitted (bounded-admission value caps)]
    (if-some [marker (::marker admitted)]
      (:seon.sci.admit/bytes marker)
      (utf8-size (:seon.sci.admit/edn admitted)))))

(defn- fit-fact-payload
  "Bound every payload field of one fact so the WHOLE fact fits inline.

  Each field carries at most its share of what the base fact leaves, and a
  field over that share becomes the marker. The halving repeats only because
  a field's bytes are measured on the admitted value while the fact stores it
  as an escaped string; it terminates at one byte, where every field is the
  marker."
  [base-fact source message-value instrument-data actual caps inline-limit]
  (let [expected (or (:seon.instrument/schema instrument-data)
                     (:seon.error/diagnostic-expected instrument-data))
        arguments (or (:seon.instrument/args instrument-data)
                      (:seon.error/diagnostic-offending instrument-data))
        payload-count (+ 2 (if expected 1 0) (if arguments 1 0) (if actual 1 0))
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
                     (bounded-text arguments field-caps))
              actual
              (assoc :seon.instrument/actual
                     (bounded-text (val actual) field-caps)))]
        (if (or (<= (utf8-size (pr-str fact)) inline-limit)
                (= 1 field-limit))
          fact
          (recur (max 1 (quot field-limit 2))))))))

(defn prepare
  "Prepare one bounded fact and its full meaningful admitted evidence."
  {:malli/schema [:=> [:cat :seon.error/prepare-request]
                  :seon.error/prepared]}
  [{:seon.error/keys [source at process basis-t]
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
        instrument-data (contract-violation-data projected-source)
        flow? (map? source)
        error-value (if failure (refusal failure) source)
        operation (get-in error-value
                          [:seon.error/data :seon.error/diagnostic-operation])
        function (or (when (qualified-symbol? operation) operation)
                     (:seon.instrument/fn instrument-data)
                     (stack-failing-function failure))
        frame (top-frame failure)
        signature (signature error-kind class-name function frame)
        ;; THE SIZE IS THE SOURCE'S, NOT THE SUBSTITUTE'S. When the whole
        ;; evidence went over the storage bound the marker is a few dozen
        ;; bytes, and reporting those as `data-size` said the evidence was
        ;; small precisely when it was too large to keep. An
        ;; `:unserializable` marker measured NOTHING — there is no size to
        ;; report — so the fact carries no `data-size` at all rather than the
        ;; substitute's, and the marker's own reason is what says why.
        ;; THE OFFENDING VALUE IS READ FROM THE SOURCE, NOT ITS PROJECTION:
        ;; the leaf sits four levels down and a depth cap would drop exactly
        ;; the evidence being recorded.
        actual (offending-entry error-value)
        actual-size (when actual (admitted-size (val actual) caps))
        marker (::marker admitted)
        data-size (if marker
                    (:seon.sci.admit/bytes marker)
                    (utf8-size full-edn))
        base-fact
        (cond-> {:seon.error/id signature
                 :seon.error/at at
                 :seon.error/process process
                 :seon.error/kind error-kind
                 :seon.error/signature signature
                 :seon.error/capped? true}
          (int? data-size) (assoc :seon.error/data-size (long data-size))
          class-name (assoc :seon.error/throwable-class class-name
                            :seon.error/exception-class (symbol class-name))
          frame (assoc :seon.error/frame frame)
          function (assoc :seon.instrument/fn function)
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
          ;; MEASURE THE OFFENDING VALUE, NOT THE REQUEST IT RODE IN. The
          ;; fact already reports the whole source's size; this one answers
          ;; how much of the value that broke the contract the inline field
          ;; kept.
          (int? actual-size)
          (assoc :seon.instrument/actual-size (long actual-size))
          basis-t (assoc :seon.error/basis-t basis-t)
          run-id (assoc :seon.error/run [:seon.turn/id run-id])
          agent-id (assoc :seon.error/agent
                          [:seon.agent/id agent-id]))
        fact (fit-fact-payload
              base-fact source
              (message source failure) instrument-data actual caps inline-limit)
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
    the canonically ordered identity attributes (kind, Throwable class, function, frame);
  - emits `run` and `agent` as lookup refs (`[:seon.turn/id id]`)
    exactly when the request supplied those ids.

  The fact's `id` IS its `signature`: one failure class is one error
  identity, derived from the canonical failure attributes rather than
  minted per occurrence. The request's own `:seon.error/id` names the
  NOTIFICATION, not the fact, so a caller that wants a ref to the fact
  reads `(:seon.error/id result)` instead of reusing the name it supplied.
  `at` and `process` are the caller's: the clock is not this function's to
  invent, and a pure normalizer is a testable one. The result is
  transactable as-is — every key is a declared attribute of
  `:seon.error/fact` and nothing rides along."
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
  [{:seon.error/keys [fact reason occurrence occurrence-count notification-limit
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
      occurrence-count (assoc :seon.error/occurrence-count occurrence-count)
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

(defn- schema-expectation
  "Describe composed schemas from their children, not Malli's unknown fallback."
  [problem]
  (let [check (m/deref-all (:schema problem))
        problem (-> problem (assoc :schema check) (dissoc :type))
        declared-message (me/error-message problem {:unknown false})
        children #(map (fn [child]
                         (schema-expectation (assoc problem :schema child)))
                       (m/children check))]
    (case (m/type check)
      :vector "a vector" :sequential "a sequence" :map "a map"
      :set "a set" :string "a string" :int "an integer"
      :double "a double" :boolean "a boolean" :keyword "a keyword"
      :qualified-keyword "a namespaced keyword" :symbol "a symbol"
      :qualified-symbol "a namespaced symbol" :nil "nil"
      :tuple (str "a tuple with " (count (m/children check)) " entries")
      :enum (str "either " (str/join " or " (map pr-str (m/children check))))
      :and (or declared-message (str/join " and " (children)))
      :or (or declared-message (str/join " or " (children)))
      :fn (or declared-message "the declared predicate")
      (str "a value satisfying " (or declared-message "the declared schema")))))

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
        described-problem (cond-> problem missing? (dissoc :type))
        message (or (me/error-message described-problem {:unknown false})
                    "the declared schema")
        expected (schema-expectation described-problem)]
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

(defn problem-sentence
  "THE ONE refusal sentence for one problem: who refused what, where, the
  expectation, the offending value, and the fix.

  `expected-text` and `offending-text` are the CALLER'S rendered values — the
  render pair prints them under its profile, a flat message prints a scalar
  directly, and nil omits that part. THE DESCRIPTIONS ARE PROSE AND THE VALUE
  FOLLOWS THEM (`value-description` never prints a value), so a description
  that names its own value renders it twice: `3e41a5d22` put the count into
  the arity description to repair the flat message, which had no value to
  print, and every rendered arity refusal then read \"got an argument count of
  0 0\" (measured 2026-09-17). One sentence, one composer, one place each
  value is printed."
  {:malli/schema
   [:=> [:cat [:or :symbol :string] :map [:maybe :string] [:maybe :string]]
    [:string {:min 1}]]}
  [operation
   {:seon.error/keys [path argument expected-description actual-description fix]}
   expected-text offending-text]
  (str operation " refused " argument " at " (pr-str path)
       ": expected " expected-description
       (when expected-text (str " (" expected-text ")"))
       ", got " actual-description
       (when offending-text (str " " offending-text))
       ". Fix: " fix))

(defn scalar-text
  "The printed form of a value that prints itself, or nil.

  A flat `:seon.error/message` carries no render profile, so it can only
  print a value whose printed form is bounded by what the value IS: a
  number, keyword, symbol or boolean. Anything else stays with its prose
  description until a renderer with a profile prints it."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total printer accepts any offending value and answers nil for the ones it cannot bound."}]]
    [:maybe [:string {:min 1}]]]}
  [value]
  (when (or (number? value) (keyword? value) (symbol? value) (boolean? value))
    (pr-str value)))

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
                      database operation)
            position (:seon.sci.reader/argument-index evidence)
            supplied (call-preparation/supplied-map-entries
                      database operation)
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
                                      (:seon.db/db unit) operation)]
                        (when (string? doc)
                          (not-empty
                           (:example
                            (@sci-eval-docstring-parts doc)))))))]
    (when (and operation (seq problems))
      (str/join
       "\n"
       (map-indexed
        (fn [index {:seon.error/keys [expected offending input result-contract]
                    :as problem}]
          (let [location (when stored-problems?
                           [:seon.error/data :seon.error/problems index])]
          (str (problem-sentence
                operation problem
                (refusal-value-text unit expected (when location (conj location :seon.error/expected)))
                (refusal-value-text unit offending (when location (conj location :seon.error/offending))))
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
        aggregate? (:seon.error/occurrence-count notice)]
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

(defn steward
  "The steward of the currently defined function named by a historical fault."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] [:maybe :seon.agent/id]]}
  [database fact]
  (when-let [function (:seon.instrument/fn fact)]
    (db/q '[:find ?id . :in $ ?symbol
            :where [?function :seon.fn/sym ?symbol]
                   [?function :seon.fn/ns ?namespace]
                   [?namespace :seon.ns/steward ?agent]
                   [?agent :seon.agent/id ?id]]
          database function)))

(defn- recurrence
  [database signature process]
  (reduce + 0
          (map second
               (db/q '[:find ?occurrence ?count :in $ ?signature ?process
                       :where [?error :seon.error/signature ?signature]
                              [?error :seon.error/occurrences ?occurrence]
                              [?occurrence :seon.error.occurrence/process ?p]
                              [?p :seon.db.process/id ?process]
                              [?occurrence :seon.error.occurrence/count ?count]]
                     database signature process))))

(defn- message-tx
  [fact sender recipient reason notification]
  {:seon.message/id (id/id [(:seon.error/notification-id notification) recipient reason])
   :seon.message/to [:seon.agent/id recipient]
   :seon.message/from [:seon.agent/id sender]
   :seon.message/content (ai-prose (notice (merge {:seon.error/fact fact
                                                 :seon.error/reason reason
                                                 :seon.agent/id recipient}
                                                notification)))
   :seon.message/about (:seon.error/signature fact)})

(defn commit-call
  "Upsert one error occurrence and its bounded notifications at the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/commit-tx-request]
                  :seon.store/transaction-data]}
  [database request]
  (let [fact (:seon.error/fact request)
        signature (:seon.error/signature fact)
        occurrence-id (:seon.error.occurrence/id request)
        occurrence-ref [:seon.error.occurrence/id occurrence-id]
        old (db/pull database '[*] occurrence-ref)
        at (:seon.error/at fact)
        process (:seon.error/process fact)
        agent-id (second (:seon.error/agent fact))
        turn-id (second (:seon.error/run fact))
        agent-id (when (and agent-id (entity-exists? database :seon.agent/id agent-id)) agent-id)
        turn-id (when (and turn-id (entity-exists? database :seon.turn/id turn-id)) turn-id)
        fact (cond-> fact (nil? agent-id) (dissoc :seon.error/agent)
                         (nil? turn-id) (dissoc :seon.error/run))
        count (inc (or (:seon.error.occurrence/count old) 0))
        process-count (inc (recurrence database signature process))
        limit (:seon.config.error/recurrence-limit request)
        recurring? (= process-count limit)
        silent? (> process-count limit)
        interrupted? (some? (:seon.error/exception-class fact))
        escalate-to (:seon.config.error/escalate-to request)
        steward-id (steward database fact)
        evidence (select-keys fact [:seon.error/process :seon.error/proc :seon.error/op
                                   :seon.error/cid :seon.error/throwable-class
                                   :seon.error/data-edn :seon.error/data-size :seon.error/capped?
                                   :seon.error/dropped-fault-count :seon.error/dropped-fault-digest
                                   :seon.instrument/fn :seon.instrument/arm
                                   :seon.instrument/expected :seon.instrument/args
                                   :seon.instrument/actual
                                   :seon.instrument/actual-size])
        digest (:seon.error/data-blob fact)
        occurrence (cond-> (merge evidence
                                 {:seon.error.occurrence/id occurrence-id
                                  :seon.error.occurrence/count count
                                  :seon.error.occurrence/first-at (or (:seon.error.occurrence/first-at old) at)
                                  :seon.error.occurrence/last-at at
                                  :seon.error.occurrence/process [:seon.db.process/id process]
                                  :seon.error.occurrence/message (:seon.error/message fact)})
                     (:seon.error/dropped-fault-count fact)
                     (assoc :seon.error/dropped-fault-count
                            (+ (or (:seon.error/dropped-fault-count old) 0)
                               (:seon.error/dropped-fault-count fact)))
                     agent-id (assoc :seon.error.occurrence/agent [:seon.agent/id agent-id])
                     turn-id (assoc :seon.error.occurrence/turn [:seon.turn/id turn-id])
                     digest (assoc :seon.error.occurrence/data-blob
                                   [:seon.error.occurrence/blob-digest digest]))
        error-row (assoc (select-keys fact [:seon.error/signature :seon.error/id
                                          :seon.error/kind :seon.instrument/fn :seon.error/frame
                                          :seon.error/exception-class])
                         :seon.error/occurrences #{occurrence})
        notification (cond-> {:seon.error/notification-id (:seon.error/id request)}
                       recurring? (assoc :seon.error/occurrence process-count
                                         :seon.error/notification-limit limit
                                         :seon.error/notification :final))
        recipients (cond-> {}
                     (and interrupted? agent-id (not recurring?) (not silent?))
                     (assoc agent-id :your-run)
                     (and interrupted? (nil? agent-id) (not recurring?) (not silent?) escalate-to)
                     (assoc escalate-to :no-attributable-agent)
                     (and recurring? escalate-to (not= escalate-to agent-id))
                     (assoc escalate-to :recurring)
                     (and steward-id (not silent?)) (assoc steward-id :recurring))]
    (into (cond-> [{:seon.db.process/id process}]
            digest (conj {:seon.error.occurrence/blob-digest digest :seon.error/data-blob digest})
            (and (nil? digest) (:seon.error.occurrence/data-blob old))
            (conj [:db/retract occurrence-ref :seon.error.occurrence/data-blob
                   (:db/id (:seon.error.occurrence/data-blob old))])
            true (conj error-row))
          (keep (fn [[recipient reason]]
                  (when (agent-exists? database recipient)
                    (message-tx fact (or agent-id
                                         (when (and escalate-to (agent-exists? database escalate-to)) escalate-to)
                                         steward-id) recipient reason notification))))
          recipients)))

(defn recording
  "Prepared identities, flat value and transaction data for one error.

  The leading identity row preserves existing transaction composition; it
  contains no occurrence state. Counts and notification decisions belong
  exclusively to commit-call's mid-transaction database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.error/commit-tx-request] :seon.error/recording]
    [:=> [:cat :map :seon.db/database-value :seon.error/source :inst :map] :seon.error/recording]]}
  ([_database request]
   (let [fact (or (:seon.error/fact request) (normalize request))
         signature (:seon.error/signature fact)
         agent-id (second (:seon.error/agent fact))
         turn-id (second (:seon.error/run fact))
         occurrence-id (id/id (into (sorted-map)
                                   (cond-> {:seon.error/signature signature}
                                     agent-id (assoc :seon.agent/id agent-id)
                                     turn-id (assoc :seon.turn/id turn-id)
                                     (nil? turn-id) (assoc :seon.db.process/id (:seon.error/process fact)))))
         rows [{:db/id (fact-tempid (:seon.error/id request))
                :seon.error/id signature :seon.error/signature signature
                :seon.error/kind (:seon.error/kind fact)}]
         tx (conj rows [:db.fn/call #'commit-call
                        (assoc request :seon.error/fact fact :seon.error.occurrence/id occurrence-id)])]
     {:seon.error/fact fact
      :seon.error/ref [:seon.error/signature signature]
      :seon.error.occurrence/ref [:seon.error.occurrence/id occurrence-id]
      :seon.error/value (value fact)
      :seon.db/tx-data tx}))
  ([cluster database source at attribution]
   (recording database
              (merge (select-keys cluster [:seon.sci.admit/caps :seon.config.error/recurrence-limit
                                          :seon.config.error/max-evidence-bytes :seon.config.error/escalate-to])
                     {:seon.error/source source :seon.error/id (id/id)
                      :seon.error/at at :seon.error/process (:seon.db.process/id cluster)
                      :seon.error/basis-t (db/basis-t database)}
                     attribution))))

(defn commit-tx
  "Transaction data for one error; occurrence decisions execute only at the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/commit-tx-request]
                  :seon.store/transaction-data]}
  [database request]
  (:seon.db/tx-data (recording database request)))

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

(defn latest-fact
  "Project an error's latest occurrence for the existing diagnostic renderers."
  {:malli/schema [:=> [:cat :map] :map]}
  [error]
  (if (some #(not (map? %)) (:seon.error/occurrences error))
    (assoc (dissoc error :seon.error/occurrences) :seon.error/message "Occurrence evidence was not acquired.")
    (if-let [occurrence (last (sort-by :seon.error.occurrence/last-at
                                    (:seon.error/occurrences error)))]
    (cond-> (merge (dissoc error :seon.error/occurrences)
                   (select-keys occurrence [:seon.error/process :seon.error/data-edn
                                            :seon.error/data-size :seon.error/capped?
                                            :seon.error/throwable-class :seon.error/proc
                                            :seon.error/op :seon.error/cid
                                            :seon.instrument/fn :seon.instrument/arm
                                            :seon.instrument/expected :seon.instrument/args
                                            :seon.instrument/actual
                                            :seon.instrument/actual-size])
                   {:seon.error/at (:seon.error.occurrence/last-at occurrence)
                    :seon.error/message (:seon.error.occurrence/message occurrence)
                    :seon.error/occurrence-count
                    (reduce + 0 (map :seon.error.occurrence/count (:seon.error/occurrences error)))})
      (:seon.error.occurrence/agent occurrence)
      (assoc :seon.error/agent (:seon.error.occurrence/agent occurrence))
      (:seon.error.occurrence/turn occurrence)
      (assoc :seon.error/run (:seon.error.occurrence/turn occurrence)))
    error)))

(defn- rendered-error-value
  [unit]
  (let [value (if (map? (:seon.render/value unit))
                (:seon.render/value unit)
                unit)]
    (render.value/transacted (if (map? value) (latest-fact value) value))))

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
    (str (or (refusal-text unit (or source value) (:seon.error/data (or source value)))
        (:seon.error/message (or source value))
        "Error evidence is unavailable.")
         (when-let [n (:seon.error/occurrence-count value)]
           (str " Occurrences: " n ".")))))

(defn render-html
  "Render one fault's kind, message, time, function, turn, and evidence link."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.render/hiccup]}
  [unit]
  (let [value (if (map? (:seon.render/value unit)) (:seon.render/value unit) unit)
        value (if (map? value) (latest-fact value) value)
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
        (let [reference [:seon.fn/sym function]]
          [[:p {:class "seon-error-function"}
            [:a {:href (render.route/path :seon.render.route/data {}
                                           {:entity (pr-str reference)})}
             (str "Function: " (if (vector? reference) (second reference) reference))]]]))
      (when-let [n (:seon.error/occurrence-count value)]
        [[:p {:class "seon-error-occurrences"} (str "Occurrences: " n)]])
      (when (:seon.error/signature value)
        [[:p {:class "seon-error-resolution"}
          (if (:seon.error/resolved-tx value) "Resolved" "Open")]])
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
  [(if-let [at (:seon.error/at (latest-fact fault))] (- (.getTime ^java.util.Date at)) 0)
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

(def ^:private agent-faults-query
  "Errors this agent must be able to read, by the two refs that name it.

  A fault in a namespace it stewards names its current function by symbol. A
  fault recorded WHILE IT WAS WORKING — a lost model call is the founding
  case — carries no `:seon.instrument/fn` at all and names the agent only through
  its occurrence. Selecting only the first read the absence of the second as
  health: the turn closed, the reason was durable, and the agent's next
  prompt said nothing about it."
  '[:find [(pull ?error [*
                          {:seon.error/occurrences [*]}]) ...]
    :in $ ?id
    :where [?agent :seon.agent/id ?id]
           [?error :seon.error/signature]
           (or-join [?error ?agent]
                    (and [?namespace :seon.ns/steward ?agent]
                         [?function :seon.fn/ns ?namespace]
                         [?function :seon.fn/sym ?function-symbol]
                         [?error :seon.instrument/fn ?function-symbol])
                    (and [?occurrence :seon.error.occurrence/agent ?agent]
                         [?error :seon.error/occurrences ?occurrence]))])

(defn faults-form
  "Read errors assigned to this agent: its stewarded namespaces, and its turns.

  A UNIT WITH NO FAULT VALUE CARRIES NO ENTITY TO PULL. A brand-new agent
  has no `:seon.error/of-steward` datom at all, so the walk hands this
  function nil; pulling on nil violated `seon.db/pull`'s declared contract,
  the throw escaped the renderer, and the fault committer interrupted the
  turn that was generating the opening. Measured 2026-09-16 on a scratch
  cluster: every worker `seon.issue/start!` created closed its opening turn
  with zero evaluations and one `:seon.render/unknown` fault. Absence of a
  fault is ordinary — it emits no form, exactly as an agent with faults but
  no readable identity already did."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/form]]}
  [unit]
  (when-let [entity (faults-input unit)]
   (let [row (db/pull (:seon.db/db unit) [:seon.agent/id] entity)]
    (when-let [agent-id (:seon.agent/id row)]
      {:seon.repl/comment
       "Inspect errors in the namespaces assigned to me and in my own turns."
       :seon.repl/form
       (list 'seon.db/q (list 'quote agent-faults-query) agent-id)}))))

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
              (db/q '[:find [(pull ?error [*
                                             {:seon.error/occurrences [* {:seon.error.occurrence/turn [:seon.turn/id]}]}]) ...]
                      :in $ ?agent
                      :where
                      [?error :seon.error/signature]
                      (or-join [?error ?agent]
                               (and [?namespace :seon.ns/steward ?agent]
                                    [?function :seon.fn/ns ?namespace]
                                    [?function :seon.fn/sym ?function-symbol]
                         [?error :seon.instrument/fn ?function-symbol])
                               (and [?occurrence :seon.error.occurrence/agent ?agent]
                                    [?error :seon.error/occurrences ?occurrence]))]
                    database faults))
        references (cond acquired? faults
                         (:seon.error/kind row) [row]
                         :else row)
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
