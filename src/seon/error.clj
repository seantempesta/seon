(ns seon.error
  "Error observations, complete declared component reads, and writer-owned recurrence.

  D12: errors have one named schema over the base observation.
  Callers branch on their boundary's required members; no general error
  predicate or stored classification is used here. D13 identity derives from
  layer, operation, the producer-declared schema, Throwable class/frame, violated schema
  and location. Message, time, process and offending bytes do not identify a bug.

  Normalization receives its projection and evidence policy. Recording acquires
  them from its supplied database value, then returns transaction data; the
  database writer decides occurrence counts and the first notification. Readers
  acquire complete declared components before rendering."
  (:require [clojure.core.async.flow :as-alias flow]
            [clojure.edn :as edn]
            [clojure.stacktrace :as stacktrace]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [malli.error :as me]
            [malli.util :as mu]
            [seon.call-preparation :as call-preparation]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.env :as env]
            [seon.id :as id]
            [seon.error.refusal :as error.refusal]
            [seon.repl :as repl]
            [seon.render.route :as render.route]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.datahike :as schema.datahike]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]
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


(defn refusal
  "The deepest non-empty `ex-data` in a throwable's cause chain, or nil.
  Pure, and unit-testable with no database: a refusal is a value buried
  under wrappers, and finding it is a walk, not a guess. Returns nil for
  a throwable that carries no data anywhere in its chain — which is
  itself information, and the caller treats it as unclassifiable.

  The pure cause-chain owner is `seon.error.refusal`, below both this
  rendering-aware normalizer and `seon.db`; this public entry delegates
  so existing callers retain one behavior without a dependency cycle.
  This error-handling reader accepts errors from any producer under the bare base contract."
  {:malli/schema
   [:=> [:cat [:maybe :seon.error/throwable]]
    [:or :nil :map :seon.error/base]]}
  [throwable]
  (error.refusal/refusal throwable))

(defn- throwable
  "The Throwable in `source`, or nil.
  `::flow/ex` is the ONE key all three of flow's report shapes share
  (`impl.clj:106-110, 312-320`), which is what makes the family
  recognizable without enumerating shapes."
  {:malli/schema [:=> [:cat :seon.error/source]
                  [:or :nil :seon.error/throwable]]}
  [source]
  (cond
    (instance? Throwable source) source
    (and (map? source) (instance? Throwable (::flow/ex source))) (::flow/ex source)))


(defn- message
  "What a reader is told. Never absent, never blank.
  Taken from the ROOT CAUSE, not the outermost wrapper: measured on the
  first real projection, a Datahike-wrapped transition refusal produced
  the message \"wrapper\" while the observation came from the bottom of the
  chain, and an agent reading \"An error stopped work: wrapper\" has been
  told nothing. The chain is not recoverable from `data-edn` either —
  admission projects a Throwable to an opaque marker by design — so this
  string is the only place the real sentence can appear.
  A source nothing recognizes still says what arrived: `nil` is a
  perfectly possible thing to be handed, and \"an error we cannot
  describe\" has to describe that much."
  {:malli/schema [:=> [:cat :seon.error/source [:or :nil :seon.error/throwable]]
                  :seon.error/message]}
  [source failure]
  (or (when (map? source) (not-empty (:seon.error/message source)))
      (when (and (map? source)
                 (:seon.turn/rule source)
                 (:seon.turn/transition source))
        (str (:seon.turn/transition source) " was refused by "
             (:seon.turn/rule source) "."))
      (when failure
        (let [deepest (stacktrace/root-cause failure)]
          (or (not-empty (ex-message deepest))
              (not-empty (ex-message failure))
              (.getName (class deepest)))))
      (if (nil? source)
        "An unclassified nil arrived where an error was expected."
        (str "An unclassified " (.getName (class source)) " arrived where an "
             "error was expected."))))

(declare declared-schema-keys stored-observation observation-selector latest-fact)

(defn- signature
  "D13: identity of the observed site, producer-declared schema, violated schema and path.
  Incidental time, process, message and offending bytes never enter this tuple."
  {:malli/schema [:=> [:cat :seon.schema/projection :map :seon.error/declared-schema
                       [:or :nil :symbol] [:or :nil :seon.error/frame]]
                  :seon.error/signature]}
  [projection observation declared-schema throwable-class frame]
  (let [observation (stored-observation projection observation)
        location (:seon.error/location observation)
        path (mapv (fn [segment]
                     (let [key (:seon.error.location.segment/key segment)]
                       (if-let [scalar (find key :seon.error.key/scalar)]
                         (val scalar)
                         (:seon.error.key/projection key))))
                   (sort-by :seon.error.location.segment/ordinal
                            (:seon.error.location/segments location)))
        path (if-let [omission (:seon.error.location/omission location)]
               [path (into (sorted-map) (dissoc omission :db/id))]
               path)]
    (id/id [(:seon.error/layer observation)
            (:seon.error/operation observation)
            declared-schema
            throwable-class frame
            (into (sorted-map)
                  (select-keys observation [:seon.error/expected-key :seon.error/expected-shape]))
            path]
           64)))

(defn- stored-observation
  "Restore the declared stored collection/ref grammar of a complete acquired
  observation. Only declared attributes are transformed; owned children keep
  their complete values, peer refs keep their entity identity."
  {:malli/schema [:=> [:cat :seon.schema/projection :map] [:maybe :map]]}
  [projection observation]
  (letfn [(restore [value]
              (if-not (map? value)
                value
                (into {}
                      (keep (fn [[attribute observed]]
                              (when (not= :db/id attribute)
                                (let [declaration
                                      (schema/projection-cache-value
                                       projection [::observation-attribute attribute]
                                       #(when (and (qualified-keyword? attribute)
                                                   (schema.datahike/storable-attribute-in? projection attribute))
                                          (schema.datahike/malli->datahike-attr-in projection attribute)))
                                      convert (fn [member]
                                                (cond
                                                  (:db/isComponent declaration) (restore member)
                                                  (and (= :db.type/ref (:db/valueType declaration))
                                                       (map? member)) (:db/id member)
                                                  :else member))]
                                  [attribute (if (and (= :db.cardinality/many (:db/cardinality declaration))
                                                      (coll? observed))
                                               (into #{} (map convert) observed)
                                               (convert observed))]))))
                      value)))]
      (restore observation)))

;;; ---------------------------------------------------------------------------
;;; Flat diagnostics — one evidence-complete construction
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; The normalizer
;;; ---------------------------------------------------------------------------

(defn- meaningful-source
  {:malli/schema [:=> [:cat :seon.error/source]
                  :seon.error/source]}
  [source]
  (if (and (map? source) (instance? Throwable (::flow/ex source)))
    (dissoc source ::flow/state)
    source))

(defn- utf8-size
  {:malli/schema [:=> [:cat :string]
                  [:int {:min 0}]]}
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
  {:malli/schema [:=> [:cat :seon.sci.admit/caps [:int {:min 1}]]
                  :seon.sci.admit/caps]}
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
  {:malli/schema [:=> [:cat :seon.error/source :seon.sci.admit/caps [:maybe :seon.schema/projection]]
                  :seon.sci.admit/admitted]}
  [value caps projection]
  ;; The projection is what lets admission REFERENCE the world (a database,
  ;; connection, SCI ctx, environment or projection) instead of copying it.
  (let [request (cond-> {:seon.sci.admit/value value
                         :seon.sci.admit/interrupt-fn (constantly nil)
                         :seon.sci.admit/caps caps
                         :seon.config/on-core-error :record}
                  projection (assoc :seon.schema/projection projection))
        admitted (admit/admit request)]
    (if-some [marker (admit/missing-marker admitted)]
      (assoc (admit/admit (assoc request
                                 :seon.sci.admit/value marker
                                 :seon.sci.admit/unbounded? true))
             ::marker marker)
      admitted)))

(def ^:private classifying-error-keys
  ;; Dated 2026-09-18 against error-entities PRD §2.2. These are the base
  ;; observations whose values must remain readable when the remainder is
  ;; over-bound; their complete schemas are in the canonical population.
  [:seon.error/at
   :seon.error/layer
   :seon.error/operation
   :seon.error/message
   :seon.error/member
   :seon.error/expected-key
   :seon.error/expected-shape
   :seon.error/location
   :seon.error/offending-projection
   :seon.error/evidence-items
   :seon.error/evidence-unavailable
   :seon.error/cause
   :seon.error/fix
   :seon.error/basis])

(defn- classifying-error-data
  "Present current and proposed base observations, in declared priority order,
  then a Throwable's cause chain. Over the bound the chain keeps each link's
  class, message and the first-party frame that raised it; its `ex-data` and
  deeper frames are the remainder whose size the marker reports (a link's whole
  first-party stack is kilobytes under a deep caller, and a partition over the
  bound keeps nothing). Without it an over-bound fault kept no cause at all."
  {:malli/schema [:=> [:cat :seon.error/source [:or :nil :seon.error/throwable]]
                  [:or :map :seon.error/base]]}
  [source failure]
  (let [error-value (if failure (refusal failure) source)
        candidates (merge (when (map? source) source)
                          (when (map? error-value) error-value))]
    (cond-> (into (array-map)
                  (keep (fn [member]
                          (when-let [entry (find candidates member)] entry)))
                  classifying-error-keys)
      failure (assoc :seon.error/chain
                     (mapv (fn [link]
                             (cond-> (dissoc link :seon.error/data :seon.error/frames)
                               (:seon.error/frames link)
                               (assoc :seon.error/frames [(first (:seon.error/frames link))])))
                           (error.refusal/chain failure))))))

(defn- bounded-error-admission
  "Admit complete evidence, retaining classifying observations on overflow."
  {:malli/schema [:=> [:cat :seon.error/source [:or :nil :seon.error/throwable] :seon.sci.admit/caps [:maybe :seon.schema/projection]]
                  :seon.sci.admit/admitted]}
  [source failure caps projection]
  (let [request (cond-> {:seon.sci.admit/value source
                         :seon.sci.admit/interrupt-fn (constantly nil)
                         :seon.sci.admit/caps caps
                         :seon.config/on-core-error :record}
                  projection (assoc :seon.schema/projection projection))
        admitted (admit/admit-partitioned
                  request (classifying-error-data source failure))]
    (if (admit/missing-marker admitted)
      (bounded-admission source caps projection)
      admitted)))

(defn- bounded-text
  "One value as the text a fault field stores, under the evidence bound.

  A string is its own text; anything else is its admitted value as
  canonical EDN, so signatures and deduplication read deterministic bytes.
  This is storage, not presentation. Over the bound the field IS the missing marker —
  the same data every other surface reports an absent value with — and the
  whole value stays reachable in the fault's evidence content."
  {:malli/schema [:=> [:cat :seon.error/source :seon.sci.admit/caps [:maybe :seon.schema/projection]]
                  :string]}
  [value caps projection]
  (let [admitted (bounded-admission value caps projection)]
    (if-some [marker (::marker admitted)]
      (admit/canonical-edn marker)
      (let [projected (:seon.sci.admit/value admitted)]
        (if (string? projected)
          projected
          (admit/canonical-edn projected))))))

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

  Which frame names a function, and which frames are the machinery that
  caught the failure, is `seon.error.refusal/frame-function`'s one rule."
  {:malli/schema [:=> [:cat [:or :nil :seon.error/throwable]]
                  [:or :nil :qualified-symbol]]}
  [^Throwable failure]
  (when failure
    (some (fn [^StackTraceElement frame]
            (error.refusal/frame-function (symbol (.getClassName frame))))
          (.getStackTrace failure))))

(defn- contract-violation-data
  "The reporter's declared function/arm evidence, from an admitted source."
  {:malli/schema [:=> [:cat :seon.error/source]
                  [:or :nil :map]]}
  [source]
  (let [observation (if (map? (::flow/ex source)) (:data (::flow/ex source)) source)
        data observation]
    (when (and (map? data)
               (qualified-symbol? (:seon.instrument/fn data))
               (#{:input :output :guard} (:seon.instrument/arm data)))
      data)))

(defn- offending-entry
  "The map entry holding the value that actually broke the contract, if any.

  WHAT BROKE THE CONTRACT IS A QUERY, NOT A RECONSTRUCTION.
  `:seon.error/offending` is what the ARM checked — the caller's
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
  {:malli/schema [:=> [:cat :seon.error/source]
                  [:or :nil [:tuple :qualified-keyword :seon.error/source]]]}
  [error-value]
  (find (get-in (contract-violation-data error-value) [:seon.error/problems 0])
        :seon.error/offending))

(defn project-observation
  "Project one observed value under the supplied admission caps."
  {:malli/schema [:function [:=> [:cat :seon.sci.admit/caps :seon.schema/value] :map]
                  [:=> [:cat :seon.sci.admit/caps :seon.schema/value [:maybe :seon.schema/projection]] :map]]}
  ([caps value] (project-observation caps value nil))
  ([caps value projection]
  (let [admitted (bounded-admission value caps projection)]
    {:seon.error/capped? (boolean (or (::marker admitted)
                                    (:seon.sci.admit/capped? admitted)))
     :seon.error.projection/bound-bytes (:seon.config.eval.result/max-bytes caps)
     :seon.instrument/actual (:seon.sci.admit/edn admitted)})))

(defn- admitted-size
  "One value's own size, measured by the same admission that stores it.

  Over the bound the marker reports the bytes it refused, so the number is
  the value's and never the substitute's — the same rule `prepare` applies to
  `:seon.error/data-size`."
  {:malli/schema [:=> [:cat :seon.error/source :seon.sci.admit/caps [:maybe :seon.schema/projection]]
                  [:or :nil [:int {:min 0}]]]}
  [value caps projection]
  (let [admitted (bounded-admission value caps projection)]
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
  {:malli/schema [:=> [:cat :map :seon.error/source :string [:or :nil :map] [:or :nil [:tuple :qualified-keyword :seon.error/source]] :seon.sci.admit/caps [:int {:min 1}] [:maybe :seon.schema/projection]]
                  [:or :seon.error/fact :seon.error/base]]}
  [base-fact source message-value instrument-data actual caps inline-limit projection]
  (let [expected (or (:seon.instrument/schema instrument-data)
                     (:seon.error/expected instrument-data))
        arguments (or (:seon.instrument/args instrument-data)
                      (:seon.error/offending instrument-data))
        payload-count (+ 2 (if expected 1 0) (if arguments 1 0) (if actual 1 0))
        available (max 1 (- inline-limit (utf8-size (pr-str base-fact))))]
    (loop [field-limit (max 1 (quot available payload-count))]
      (let [field-caps (evidence-caps caps field-limit)
            evidence (bounded-error-admission source (throwable source)
                                              field-caps projection)
            fact
            (cond-> (assoc base-fact
                           :seon.error/message
                           (bounded-text message-value field-caps projection)
                           :seon.error/data-edn
                           (:seon.sci.admit/edn evidence))
              expected
              (assoc :seon.instrument/expected
                     (bounded-text expected field-caps projection))
              arguments
              (assoc :seon.instrument/args
                     (bounded-text arguments field-caps projection))
              actual
              (assoc :seon.instrument/actual
                     (bounded-text (val actual) field-caps projection)))]
        (if (or (<= (utf8-size (pr-str fact)) inline-limit)
                (= 1 field-limit))
          fact
          (recur (max 1 (quot field-limit 2))))))))

(defn prepare-result
  "Render one result's shown text, store the value ONCE, and retain its live binding.

  The blob is the value's admitted artifact (the record `get_value` reads),
  under the storage bound and with its world as references. It used to be the
  complete HTML face rendered unbounded: a second, larger copy of the value
  whose admission walked every object it reached. A supplied SCI context
  carries its owner's binding operation and, absent explicit caps, the bound."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                    [:seon.render/profile :seon.render.profile/profile]
                    [:seon.error/source :seon.error/source]
                    [:seon.sci.admit/caps {:optional true} :seon.sci.admit/caps]
                    [:seon.sci.eval/ctx {:optional true}
                     [:and :seon.sci.eval/ctx
                      [:map [:seon.sci.eval/bind-result! :seon.instrument/callable]]]]]]
    [:map [:seon.error/result-id :seon.error/result-id]
          [:seon.error/shown :seon.error/shown]
          [:seon.error/data-blob :seon.error/data-blob]]]}
  [{source :seon.error/source :as request}]
  (let [entry (or (offending-entry source)
                  (when (map? source) (find source :seon.error/offending))
                  (find (:seon.error/data source) :seon.error/offending))
        value (if entry (val entry) source)
        result-id (id/id)
        handle (admit/result-handle result-id)
        ctx (:seon.sci.eval/ctx request)
        unit (assoc (select-keys request [:seon.schema/projection :seon.render/profile])
                    :seon.render/value value :seon.repl/handle handle
                    :seon.render.call/id handle)
        caps (or (:seon.sci.admit/caps request)
                 (some-> ctx env/of :seon.sci.admit/caps)
                 (throw (ex-info "A stored result requires its storage bound."
                                 (seon.error.refusal/diagnostic (java.util.Date.) :seon.error/recording 'seon.error/prepare-result
                                  {:seon.error/message "Supply :seon.sci.admit/caps or an SCI context whose environment carries them."
                                   :seon.error/member :seon.sci.admit/caps
                                   :seon.error/expected :seon.sci.admit/caps}))))
        admitted (admit/admit-value
                  (cond-> {:seon.sci.admit/value value
                           :seon.sci.admit/interrupt-fn (constantly nil)
                           :seon.sci.admit/caps caps
                           :seon.config/on-core-error :record}
                    (:seon.schema/projection request)
                    (assoc :seon.schema/projection (:seon.schema/projection request))))
        shown (render.value/render-ai-data (render.value/prepare unit))
        digest (blob/put! (:seon.db/connection request)
                          (render.value/artifact-edn (render.value/artifact admitted)))]
    (when ctx
      ((:seon.sci.eval/bind-result! ctx) ctx handle value))
    {:seon.error/result-id result-id
     :seon.error/shown shown
     :seon.error/data-blob digest}))

(defn- validate-declaration!
  "Validate one producer-supplied name and its observation without inference."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.error/declared-schema :map :inst] :nil]}
  [projection declared-schema observation at]
  (when-not (and (not= declared-schema :seon.error/base)
                         (internal/extends-schema?
                          (mr/schema (:seon.schema.projection/registry projection) declared-schema)
                          :seon.error/base)
                         ((schema/projection-validator projection declared-schema) observation))
            (throw (ex-info "The supplied error declaration does not validate its observation."
                            (seon.error.refusal/diagnostic at :seon.error/recording 'seon.error/prepare
                             {:seon.error/member :seon.error/declared-schema
                             :seon.error/refused-schema declared-schema
                             :seon.error/expected declared-schema
                             :seon.error/offending observation})))))

(defn prepare
  "Prepare one bounded fact under the producer-supplied named schema.
  Normalization of arbitrary escaped data is explicitly requested with
  normalization-error; missing declaration evidence never selects it.
  The two declared transient validation refusals are normalized to this owner's
  explicit stored write/schema error members before identity is derived."
  {:malli/schema [:=> [:cat :seon.error/prepare-request]
                  :seon.error/prepared]}
  [{:seon.error/keys [source at process basis-t declared-schema]
    evidence-bytes :seon.config.error/max-evidence-bytes
    projection :seon.schema/projection
    :seon.sci.admit/keys [caps]
    run-id :seon.turn/id
    agent-id :seon.agent/id
    boundary-layer :seon.error/layer
    boundary-operation :seon.error/operation :as request}]
  (let [failure (throwable source)
        class-name (when failure (.getName (class failure)))
        source (meaningful-source source)
        admitted (bounded-error-admission source failure caps projection)
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
        operation (:seon.error/operation error-value)
        ;; A failure's own declared operation wins; else the observing
        ;; boundary's, which is stable where a thrower's class name is not.
        function (or (when (qualified-symbol? operation) operation)
                     (:seon.instrument/fn instrument-data)
                     boundary-operation
                     (stack-failing-function failure))
        frame (error.refusal/root-frame failure)
        chain (when failure (error.refusal/chain failure))
        observation (merge (cond-> (seon.error.refusal/diagnostic at (or boundary-layer :seon.error/normalization)
                                                                  (or function 'seon.error/normalize)
                                    {})
                             class-name (assoc :seon.error/exception-class (symbol class-name))
                             frame (assoc :seon.error/frame frame)
                             chain (assoc :seon.error/chain chain))
                           (when (map? error-value) error-value))
        observation (cond-> observation
                      (= :seon.error/normalization-error declared-schema)
                      (assoc :seon.error/data-edn full-edn))
        _ (validate-declaration! projection declared-schema observation at)
        observation
        (if (= declared-schema :seon.db.write/validation-refusal)
          (-> observation
              (assoc :seon.db.write/attempt
                     {:seon.db.write.attempt/request-id (:seon.db.write.attempt/request-id observation)
                      :seon.db.write.attempt/observed-at (:seon.error/at observation)
                      :seon.db.write.attempt/operations
                      (project-observation caps (get-in observation [:seon.error/data :seon.db.write.attempt/transaction]) projection)})
              (dissoc :seon.db.write.attempt/request-id)
              (update :seon.error/data dissoc :seon.db.write.attempt/transaction))
          observation)
        observation
        (if (= declared-schema :seon.schema/validation-refusal)
          (-> observation
              (assoc :seon.schema/error-declaration
                     (project-observation caps (:seon.schema/refused-value observation) projection)
                     :seon.schema/declaration-expectation
                     (project-observation caps (:seon.schema/expected-value observation) projection))
              (dissoc :seon.schema/refused-value :seon.schema/expected-value))
          observation)
        declared-schema (case declared-schema
                          :seon.db.write/validation-refusal :seon.db.write/error
                          :seon.schema/validation-refusal :seon.schema/error
                          declared-schema)
        _ (validate-declaration! projection declared-schema observation at)
        signature (signature projection observation declared-schema
                             (or (some-> class-name symbol)
                                 (:seon.error/exception-class observation))
                             (or frame (:seon.error/frame observation)))
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
        actual-size (when actual (admitted-size (val actual) caps projection))
        marker (or (::marker admitted)
                   (:seon.sci.admit/remainder admitted))
        data-size (if marker
                    (:seon.sci.admit/bytes marker)
                    (utf8-size full-edn))
        base-fact
        (cond-> {:seon.error/id signature
                 :seon.error/at at
                 :seon.error/process process
                 :seon.error/layer (:seon.error/layer observation)
                 :seon.error/operation (:seon.error/operation observation)
                 :seon.error/signature signature
                 :seon.error/declared-schema declared-schema
                 :seon.error/capped? true}
          (int? data-size) (assoc :seon.error/data-size (long data-size))
          class-name (assoc :seon.error/throwable-class class-name)
          (:seon.error/exception-class observation)
          (assoc :seon.error/exception-class (:seon.error/exception-class observation))
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
              (message source failure) instrument-data actual caps inline-limit projection)
        fact (assoc fact :seon.error/capped?
                    ;; HONEST WHEN EVERYTHING WAS DROPPED. Comparing the two
                    ;; EDN strings alone reported "nothing omitted" for the
                    ;; one case where nothing was kept: the FULL admission
                    ;; also answered with the marker, so both sides were the
                    ;; same handful of bytes (F1, 2026-09-07).
                    (boolean (or marker
                                 (not= full-edn
                                       (:seon.error/data-edn fact)))))
        result (or (when (and (:seon.error/result-id source)
                              (:seon.error/shown source)
                              (:seon.error/data-blob source))
                       (select-keys source [:seon.error/result-id :seon.error/shown :seon.error/data-blob]))
                     (when (and (:seon.db/connection request) (:seon.render/profile request))
                       (prepare-result (assoc request :seon.error/source error-value))))]
    {:seon.error/fact (merge fact result)
     :seon.error/source (merge observation result)
     :seon.error/data-content full-edn}))

(defn normalize
  "Normalize one observation with a supplied projection and bounded evidence.
  The signature is D13's stable tuple; complete declared source members remain on the
  owned occurrence, while this fact carries the root's site and evidence link."
  {:malli/schema [:=> [:cat :seon.error/normalize-request]
                  [:or :seon.error/fact :seon.error/base]]}
  [request]
  (:seon.error/fact (prepare request)))

(defn value
  "The recorded base observation with a link to its complete durable evidence."
  {:malli/schema [:=> [:cat :seon.error/fact] :seon.error/base]}
  [fact]
  (assoc (select-keys fact [:seon.error/at :seon.error/layer :seon.error/operation
                          :seon.error/result-id :seon.error/shown :seon.error/data-blob
                          :seon.error/message :seon.error/signature])
         :seon.error/id (:seon.error/id fact)))

;;; ---------------------------------------------------------------------------
;;; The routing unit and its projections
;;; ---------------------------------------------------------------------------

(defn notice
  "The agent-facing unit for one fact; :seon.error/notice declares its AI renderer.

  `:seon.error/reason` is the per-RECIPIENT why-clause and is optional
  because a log has no recipient: one fact is `:your-run` to the
  interrupted agent and `:recurring` to the escalation owner in the same
  transaction, which is why the reason is derived here and never stored
  on the entity."
  {:malli/schema [:=> [:cat :seon.error/notice-request] :seon.error/notice]}
  [{:seon.error/keys [fact reason occurrence occurrence-count notification-limit
                      notification]
    agent-id :seon.agent/id}]
  (cond-> {:seon.error/fact fact
           :seon.error/evidence [:seon.error/id (:seon.error/id fact)]}
    reason (assoc :seon.error/reason reason)
    occurrence (assoc :seon.error/occurrence occurrence)
    occurrence-count (assoc :seon.error/occurrence-count occurrence-count)
    notification-limit (assoc :seon.error/notification-limit notification-limit)
    notification (assoc :seon.error/notification notification)
    agent-id (assoc :seon.agent/id agent-id)))

(defn- fact-source
  {:malli/schema [:=> [:cat :map]
                  :seon.error/source]}
  [fact]
  (try
    (admit/semantic-value (edn/read-string (:seon.error/data-edn fact)))
    ;; Stored evidence that does not read is itself a reading error;
    ;; the diagnostic carries the failure's whole cause chain and frames.
    (catch Exception failure
      (seon.error.refusal/diagnostic (or (:seon.error/at fact) (java.util.Date.)) :seon.error/reading 'seon.error/fact-source
       {:seon.error/expected-key :seon.error/data-edn
        :seon.error/message (str "Stored error evidence could not be read: "
                                 (or (not-empty (ex-message failure))
                                     (.getName (class failure))))
        :seon.error/throwable failure}))))

(defn- flat-data
  {:malli/schema [:=> [:cat :map]
                  :seon.error/source]}
  [fact]
  (fact-source fact))

(defn- evidence-prose
  {:malli/schema [:=> [:cat :map]
                  :string]}
  [fact]
  (str "Evidence: error " (:seon.error/id fact)
       ", operation " (:seon.error/operation fact)
       ", signature " (:seon.error/signature fact) "."))

(defn- value-description
  {:malli/schema [:=> [:cat :seon.error/source]
                  :string]}
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
  {:malli/schema [:=> [:cat :map]
                  :string]}
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

(defn- collection-member-problem
  {:malli/schema [:=> [:cat :map]
                  [:or :nil :map]]}
  [problem]
  (let [schema (m/deref-all (:schema problem))
        schema-type (m/type schema)
        child (when (#{:set :vector :sequential} schema-type)
                (first (m/children schema)))
        value (:value problem)]
    (when (and child (coll? value))
      (when-let [[member]
                 (reduce (fn [_ member]
                           (when-not (m/validate child member)
                             (reduced [member])))
                         nil value)]
        {:schema child
         :value member
         ::collection-type schema-type}))))

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
        member-problem (collection-member-problem problem)
        problem (or member-problem problem)
        member? (boolean member-problem)
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
     (cond
       missing? (str "the required key " (pr-str (last path)) " with " expected)
       member? (str "a collection member satisfying " expected)
       :else expected)
     :seon.error/offending (if (and missing? (map? parent)) parent (:value problem))
     :seon.error/actual-description
     (cond
       missing? (str "a map missing " (pr-str (last path)))
       member? (str "a collection member that is " (value-description (:value problem)))
       :else (value-description (:value problem)))
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
  {:malli/schema [:=> [:cat :seon.error/source :seon.error/source [:or :nil :seon.render.data/path]]
                  :string]}
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
                     #(or % [:seon.error/offending])))
         (get unit :seon.render/output :seon.render/ai))]
    (if (string? (:seon.render.value/text projection))
      (:seon.render.value/text projection)
      (str "<value rendering unavailable: " (:seon.error/message projection) ">"))))

(defn- reader-correction
  "Select a missing declared argument key; no spelling heuristic is used."
  {:malli/schema [:=> [:cat :seon.error/source :map]
                  [:or :nil :qualified-keyword :seon.db/error-result :seon.error/base]]}
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
        (cond
          (map? spec) spec
          (not (vector? supplied))
          supplied
          (and (string? spec) (nat-int? position)
               (= "{" (:edamame/opened-delimiter container)))
          (let [projection (or (db/carried-projection database)
                               (schema/projection-from-database database))
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
  {:malli/schema [:=> [:cat :seon.error/source :seon.error/source [:or :nil :map]]
                  [:or :nil :map :seon.db/error-result :seon.error/base]]}
  [unit fact data]
  (let [evidence (merge (when (map? fact) fact) data)]
    (cond
      (seq (:seon.error/problems evidence)) evidence

      (find evidence :seon.sci.reader/text)
      (let [correction (reader-correction unit evidence)]
       (if (map? correction)
         correction
         {:seon.error/problems [{:seon.error/argument "source"
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
           :else (str "Correct the reader error: " (:seon.error/message fact)))}]
          :seon.error/operation (or (:seon.sci.reader/call evidence) 'seon.sci.reader/read)}))

      (and (:seon.schema/definition evidence) (:seon.schema/error evidence))
      {:seon.error/problems [{:seon.error/argument (str (:seon.schema/identity evidence))
         :seon.error/path (get evidence :seon.schema/path [])
         :seon.error/expected :seon.schema/definition
         :seon.error/expected-description "a complete authored schema"
         :seon.error/offending (:seon.schema/definition evidence)
         :seon.error/actual-description "an incomplete schema"
         :seon.error/fix (:seon.error/message fact)}]
       :seon.error/operation 'seon.schema/register!}

      (:seon.sci.eval/symbol evidence)
      {:seon.error/problems [{:seon.error/argument "source"
         :seon.error/path []
         :seon.error/expected :symbol
         :seon.error/expected-description "a resolvable symbol"
         :seon.error/offending (:seon.sci.eval/symbol evidence)
         :seon.error/actual-description "an unresolved symbol"
         :seon.error/fix "Define or require this symbol."}]
       :seon.error/operation 'seon.sci.eval/evaluate}

      (and (:seon.error/operation evidence)
           (or (find evidence :seon.error/expected) (find evidence :seon.error/offending)))
      (let [expected (:seon.error/expected evidence)
            offending (:seon.error/offending evidence)
            member (:seon.error/member evidence)
            compiled (try
                       (m/schema expected
                                 (when-let [database (:seon.db/db unit)]
                                   {:registry (:seon.schema.projection/registry
                                               (or (db/carried-projection database)
                                                   (:seon.schema/projection unit)
                                                   (schema/projection-from-database database)))}))
                       ;; DECLARED CASE: `expected` is often a description,
                       ;; not a schema. Malli declares its schema-construction
                       ;; refusals as `-exception` ex-info whose `:type` is a
                       ;; `malli.core` keyword (malli core.cljc:203); that
                       ;; answer is kept as data. Every other failure is not
                       ;; "not a schema" and propagates whole.
                       (catch clojure.lang.ExceptionInfo failure
                         (let [data (ex-data failure)]
                           (if (and (qualified-keyword? (:type data))
                                    (= "malli.core" (namespace (:type data))))
                             data
                             (throw failure)))))
            problem (when (m/schema? compiled)
                      (explain-problem
                       {:seon.error/problem {:schema compiled :value offending}
                        :seon.error/path [] :seon.error/argument (str member)}))]
        ;; A SCHEMA PROBLEM ONLY FROM MALLI: when `expected` is no schema, or
        ;; the offending value satisfies it, the refusal is the producer's
        ;; own declared message and members, never a fabricated
        ;; "expected the declared requirement (nil)" sentence.
        (if problem
          {:seon.error/problems [problem]
           :seon.error/operation (:seon.error/operation evidence)}
          data))

      :else data)))

(defn- refusal-text
  {:malli/schema [:=> [:cat :seon.error/source :seon.error/source :seon.error/source]
                  [:or :nil :string]]}
  [unit fact data]
  (let [stored-problems? (seq (:seon.error/problems fact))
        data (refusal-data unit fact (when (map? data) data))
        operation (:seon.error/operation data)
        problems (:seon.error/problems data)
        example (or (not-empty (get-in fact [:seon.error/doc :example]))
                    (when (and (:seon.db/db unit) (qualified-symbol? operation))
                      (let [doc (db/q '[:find ?doc . :in $ ?sym
                                        :where [?f :seon.fn/sym ?sym]
                                               [?f :seon.fn/doc ?doc]]
                                      (:seon.db/db unit) operation)]
                        (if (map? doc)
                          (str "Documentation lookup unavailable: " (:seon.error/message doc))
                          (when (string? doc)
                            (not-empty (:example (@sci-eval-docstring-parts doc))))))))]
    (if (and operation (seq problems))
      (str/join
       "\n"
       (map-indexed
        (fn [index {:seon.error/keys [expected offending input result-contract]
                    :as problem}]
          (let [location (when stored-problems?
                           [:seon.error/problems index])]
          (str (problem-sentence
                operation problem
                (refusal-value-text unit expected (when location (conj location :seon.error/expected)))
                (refusal-value-text unit offending (when location (conj location :seon.error/offending))))
               (when (find problem :seon.error/input)
                 (str " Input: " (refusal-value-text unit input nil) "."))
               (when result-contract
                 (str " Result contract: " (refusal-value-text unit result-contract nil) "."))
               (when (and (zero? index) (:seon.instrument/caller data))
                 (str " Called from " (:seon.instrument/caller data) "."))
               (when example (str " Example: " example)))))
        problems))
      nil)))

(defn instrumentation-prose
  "`:seon.render/ai` — detailed steering for a validation failure."
  {:malli/schema
  [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [error-value]
  (let [fact (or (:seon.error/fact error-value) error-value)
        data (if (:seon.error/data-edn fact)
               (flat-data fact)
               fact)
        data (when (map? data) data)
        ;; A stored fact's instrument payloads are already bounded text; a
        ;; live value's members are values the floor printer bounds here.
        member-text (fn [data-key fact-key]
                      (if-some [entry (find data data-key)]
                        (refusal-value-text error-value (val entry) nil)
                        (get fact fact-key)))]
    (or (refusal-text error-value fact data)
        ;; A declared refusal prints its own message and members.
        (let [expected (member-text :seon.error/expected :seon.instrument/expected)
              received (member-text :seon.error/offending :seon.instrument/args)]
          (str/join "\n"
                    (remove nil?
                            [(or (not-empty (:seon.error/message fact))
                                 (not-empty (:seon.error/message data))
                                 (str (or (:seon.error/operation data) (:seon.instrument/fn fact)
                                          "An operation")
                                      " refused without a message."))
                             (when expected (str "Expected: " expected))
                             (when received (str "Got: " received))
                             (when (:seon.error/id fact) (evidence-prose fact))]))))))

(defn- notice-ai-prose
  "Describe recorded evidence and why this recipient receives its first notification."
  {:malli/schema [:=> [:cat :seon.error/notice]
                  :string]}
  [notice]
  (let [{:seon.error/keys [fact reason]} notice
        {:seon.error/keys [id message run signature]} fact]
    (if (= reason :failover)
      "The primary model was not called: its connection failed before send, so no output exists. Answer the unchanged request as the backup attempt."
      (str/join " "
                (remove nil?
                        [message
                         (case reason
                           :your-run (when run (str "It interrupted run " (second run) "."))
                           :no-attributable-agent "No agent or run could be attributed."
                           :recurring "This fault belongs to a namespace assigned to you."
                           nil)
                         (str "Inspect error " id ". Signature: " signature ".")
                         "Further occurrences share this record and send no notification until it is resolved."])))))

(declare ai-prose)

(defn notice-prose
  "`:seon.render/ai` for one notice: steering for a contract arm's fact, else its evidence."
  {:malli/schema [:=> [:cat :seon.render/unit] [:string {:min 1}]]}
  [notice]
  (let [fact (:seon.error/fact notice)]
    (if (and (qualified-symbol? (:seon.instrument/fn fact))
             (#{:input :output :guard} (:seon.instrument/arm fact)))
      (instrumentation-prose notice)
      (ai-prose notice))))

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
  {:malli/schema [:=> [:cat :seon.error/id]
                  :string]}
  [id]
  (str "seon.error/fact-" id))

(defn- agent-exists?
  "Return presence or the database's declared read refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :boolean :seon.db/error-result]]}
  [database agent-id]
  (let [result (db/pull database [:db/id] [:seon.agent/id agent-id])]
    (cond (nil? result) false
          (:db/id result) true
          :else result)))

(defn- entity-exists?
  "Return presence or the database's declared read refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value]
                  [:or :boolean :seon.db/error-result]]}
  [database attribute value]
  (let [result (db/pull database [:db/id] [attribute value])]
    (cond (nil? result) false
          (:db/id result) true
          :else result)))

(defn steward
  "The steward of the currently defined function named by a historical fault."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] [:or :nil :seon.agent/id :seon.db/error-result]]}
  [database fact]
  (when-let [function (:seon.instrument/fn fact)]
    (db/q '[:find ?id . :in $ ?symbol
            :where [?function :seon.fn/sym ?symbol]
                   [?function :seon.fn/ns ?namespace]
                   [?namespace :seon.ns/steward ?agent]
                   [?agent :seon.agent/id ?id]]
          database function)))

(defn resolution
  "Why the fault `signature` names is resolved, or nil while it is open.
  Resolved by program change when its `:seon.instrument/fn` row's definition
  digest now differs from the digest current at its latest occurrence's
  transaction, so a recurrence under the changed code reopens it; otherwise by
  a `:seon.error/resolved-tx` (`seon.problems/resolve!`) at or after that
  transaction. No function row or digest leaves the rule unknown: open. The
  digest then is read from one `history` view, because each `as-of` view
  derives its own projection (~100 ms per signature on default, 2026-09-24)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/signature]
                  [:or :nil :seon.error/resolution :seon.db/error-result]]}
  [database signature]
  (let [latest (db/q '[:find (max ?tx) . :in $ ?signature
                       :where [?error :seon.error/signature ?signature]
                              [?error :seon.error/occurrences ?occurrence]
                              [?occurrence :seon.error.occurrence/last-at _ ?tx]]
                     database signature)
        current (db/q '[:find [?function ?now ?changed] :in $ ?signature
                        :where [?error :seon.error/signature ?signature]
                               [?error :seon.instrument/fn ?function]
                               [?row :seon.fn/sym ?function]
                               [?row :seon.program/definition-digest ?now ?changed]]
                      database signature)
        [function now changed] (when (vector? current) current)
        ;; the latest digest datom at or before `latest`; an assertion wins its tx
        then (when (and (int? latest) changed (> changed latest))
               (let [datoms (db/q '[:find ?tx ?digest ?added :in $ ?function ?latest
                                    :where [?row :seon.fn/sym ?function]
                                           [?row :seon.program/definition-digest ?digest ?tx ?added]
                                           [(<= ?tx ?latest)]]
                                  (db/history database) function latest)]
                 (if (map? datoms) datoms
                     (let [[_ digest added] (last (sort-by (juxt first #(if (peek %) 1 0)) datoms))]
                       (when added digest)))))
        resolved (when (int? latest)
                   (db/q '[:find (max ?resolution) . :in $ ?signature
                           :where [?error :seon.error/signature ?signature]
                                  [?error :seon.error/resolved-tx ?resolution]]
                         database signature))]
    (cond
      (some map? [latest current then resolved]) (some #(when (map? %) %) [latest current then resolved])
      (and (string? then) (not= then now))
      {:seon.error/resolved-by :seon.program/definition-digest :seon.instrument/fn function
       :seon.error/digest-then then :seon.error/digest-now now}
      (and resolved (>= resolved latest))
      {:seon.error/resolved-by :seon.error/resolved-tx :seon.error/resolved-tx resolved})))

(defn- opening
  "The opening this occurrence starts, or nil while its signature stays open:
  0 for the first occurrence; for a recurrence after its `resolution`, the
  digest current at the recurrence or the resolution's transaction id.
  Delivery is once per opening; a refusal is preserved."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/signature]
                  [:or :nil [:int {:min 0}] :seon.program/definition-digest :seon.db/error-result]]}
  [database signature]
  (let [resolved (resolution database signature)
        recorded (when-not resolved
                   (db/q '[:find ?occurrence . :in $ ?signature
                           :where [?error :seon.error/signature ?signature]
                                  [?error :seon.error/occurrences ?occurrence]]
                         database signature))]
    (cond
      (:seon.error/at resolved) resolved
      resolved (or (:seon.error/digest-now resolved) (:seon.error/resolved-tx resolved))
      (map? recorded) recorded
      (nil? recorded) 0)))

(defn- notification-id
  "The one message identity for `recipient` in a signature's `opening`: the
  first opening (0) is `[signature recipient]`, a later one appends its token."
  {:malli/schema [:=> [:cat :seon.error/signature :seon.agent/id
                       [:or [:int {:min 0}] :seon.program/definition-digest]]
                  :seon.message/id]}
  [error-signature recipient opening-token]
  (id/id (cond-> [error-signature recipient] (not= 0 opening-token) (conj opening-token))))

(defn- message-tx
  {:malli/schema [:=> [:cat :seon.error/fact :seon.agent/id :seon.agent/id :seon.error/reason :map
                       [:or [:int {:min 0}] :seon.program/definition-digest]]
                  :map]}
  [fact sender recipient reason notification opening]
  ;; one message per opening: a re-opened signature is a new message, so it wakes again
  {:db/id (str "seon.error/notified-" recipient)
   :seon.message/id (notification-id (:seon.error/signature fact) recipient opening)
   :seon.message/to [:seon.agent/id recipient]
   :seon.message/from [:seon.agent/id sender]
   :seon.message/content (ai-prose (notice (merge {:seon.error/fact fact
                                                 :seon.error/reason reason
                                                 :seon.agent/id recipient}
                                                notification)))
   :seon.message/about (:seon.error/signature fact)})


(defn commit-call
  "Upsert one error occurrence and its bounded notifications at the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:and :seon.error/commit-tx-request
                        [:map [:seon.error/fact :seon.error/fact]
                         [:seon.error.occurrence/id :seon.error.occurrence/id]]]]
                  [:or :seon.store/transaction-data :seon.db/error-result]]}
  [database request]
  (let [fact (:seon.error/fact request)
        signature (:seon.error/signature fact)
        occurrence-id (:seon.error.occurrence/id request)
        occurrence-ref [:seon.error.occurrence/id occurrence-id]
        old (db/pull database '[*] occurrence-ref)
        ;; the messages the writer selected for the signature's current opening
        notified (db/q '[:find [?message ...] :in $ ?signature
                         :where [?error :seon.error/signature ?signature]
                                [?error :seon.error/notified ?message]]
                       database signature)
        agent-id (second (:seon.error/agent fact))
        turn-id (second (:seon.error/run fact))
        escalate-to (:seon.config.error/escalate-to request)
        agent-present (when agent-id (agent-exists? database agent-id))
        turn-present (when turn-id (entity-exists? database :seon.turn/id turn-id))
        escalation-present (when escalate-to (agent-exists? database escalate-to))
        steward-id (steward database fact)
        steward-present (when (string? steward-id) (agent-exists? database steward-id))
        opened (opening database signature)
        read-refusal (some (fn [observation]
                             (when (and (map? observation)
                                        (inst? (:seon.error/at observation))
                                        (qualified-keyword? (:seon.error/layer observation))
                                        (qualified-symbol? (:seon.error/operation observation)))
                               observation))
                           [(when-not (:seon.error.occurrence/id old) old)
                            (when (map? notified) notified)
                            agent-present turn-present escalation-present
                            steward-id steward-present opened])]
    (if read-refusal
      read-refusal
      (let [projection (or (db/carried-projection database)
                            (:seon.schema/projection request)
                            (schema/projection-from-database database))
        ;; THE FAULT'S OWN SCHEMA, NOT THE POPULATION. The stored diagnostic
        ;; attributes are its declared schema's and the base's own entries,
        ;; each where that schema declares `:seon.db/attributes`. An earlier
        ;; occurrence shares the signature, hence the declared schema, so the
        ;; same set names what it stored. Walking every declared error schema
        ;; (294 schemas, 5,585 armed storability checks, ~120 ms inside the
        ;; writer on each new projection) is gone; one schema is ~1 ms.
        diagnostic-attributes
        (schema/projection-cache-value
         projection [::diagnostic-attributes (:seon.error/declared-schema fact)]
         (fn []
           (into #{}
                 (comp (mapcat (fn [schema-key]
                                 (let [compiled (mr/schema (:seon.schema.projection/registry projection) schema-key)]
                                   (when (:seon.db/attributes (internal/entity-properties compiled))
                                     (map first (internal/entity-entries compiled))))))
                       (filter #(schema.datahike/storable-attribute-in? projection %)))
                 [(:seon.error/declared-schema fact) :seon.error/base])))
        replacements (mapv (fn [attribute]
                             [:db.fn/retractAttribute occurrence-ref attribute])
                           (filter diagnostic-attributes (keys old)))
        at (:seon.error/at fact)
        process (:seon.error/process fact)
        agent-id (when agent-present agent-id)
        turn-id (when turn-present turn-id)
        fact (cond-> fact (nil? agent-id) (dissoc :seon.error/agent)
                         (nil? turn-id) (dissoc :seon.error/run))
        count (inc (or (:seon.error.occurrence/count old) 0))
        interrupted? (some? (:seon.error/exception-class fact))
        evidence (select-keys fact [:seon.error/process :seon.error/proc :seon.error/op
                                   :seon.error/result-id :seon.error/shown
                                   :seon.error/cid :seon.error/throwable-class
                                   :seon.error/data-edn :seon.error/data-size :seon.error/capped?
                                   :seon.instrument/fn :seon.instrument/arm
                                   :seon.instrument/expected :seon.instrument/args
                                   :seon.instrument/actual
                                   :seon.instrument/actual-size])
        digest (:seon.error/data-blob fact)
        occurrence (cond-> (merge evidence
                                 (select-keys (:seon.error/source request) diagnostic-attributes)
                                 {:seon.error.occurrence/id occurrence-id
                                  :seon.error.occurrence/count count
                                  :seon.error.occurrence/first-at (or (:seon.error.occurrence/first-at old) at)
                                  :seon.error.occurrence/last-at at
                                  :seon.error.occurrence/process [:seon.db.process/id process]
                                  :seon.error.occurrence/message (:seon.error/message fact)
                                  ;; where it happened: this transaction's own branch,
                                  ;; unless a core fault's cluster copy names its origin
                                  :seon.error.occurrence/branch
                                  (or (:seon.error.occurrence/branch request)
                                      (get-in database [:config :branch]))})
                     agent-id (assoc :seon.error.occurrence/agent [:seon.agent/id agent-id])
                     turn-id (assoc :seon.error.occurrence/turn [:seon.turn/id turn-id])
                     digest (assoc :seon.error.occurrence/data-blob
                                   [:seon.error.occurrence/blob-digest digest])
                     ;; the world by reference: the failing request's effect and
                     ;; the occurrence this one follows
                     (:seon.effect/id request)
                     (assoc :seon.error.occurrence/effect [:seon.effect/id (:seon.effect/id request)])
                     (:seon.error/related-occurrence request)
                     (assoc :seon.error.occurrence/related
                            [:seon.error.occurrence/id (:seon.error/related-occurrence request)]))
        error-row (assoc (select-keys fact [:seon.error/signature :seon.error/id :seon.error/declared-schema
                                          :seon.error/layer :seon.error/operation :seon.instrument/fn :seon.error/frame
                                          :seon.error/exception-class])
                         :seon.error/occurrences #{occurrence})
        notification {:seon.error/notification-id signature}
        recipients (when (some? opened)
                     (cond
                       steward-present {steward-id :recurring}
                       (and interrupted? agent-id) {agent-id :your-run}
                       ;; a fault no agent answers for reaches the configured
                       ;; escalation owner, with or without a throwable. The
                       ;; shipped default leaves `escalate-to` absent (owner
                       ;; ruling 2026-09-24): the fault stays recorded and shown,
                       ;; and wakes no paid root turn. An attributed fault without
                       ;; a throwable is its agent's mistake, already returned to
                       ;; that agent in its turn.
                       (and escalation-present (nil? agent-id)) {escalate-to :no-attributable-agent}
                       :else {}))
        messages (mapv (fn [[recipient reason]]
                         (message-tx fact (or agent-id
                                             (when escalation-present escalate-to)
                                             steward-id) recipient reason notification opened))
                       recipients)]
    (if (and (:seon.error/obligation request) (:seon.error.occurrence/id old))
      ;; ONE OBLIGATION, ONE DISPOSITION: this occurrence already answers it
      [{:seon.db.process/id process}]
      (into (cond-> (into [{:seon.db.process/id process}] replacements)
              digest (conj {:seon.error.occurrence/blob-digest digest :seon.error/data-blob digest})
              (and (nil? digest) (:seon.error.occurrence/data-blob old))
              (conj [:db/retract occurrence-ref :seon.error.occurrence/data-blob
                     (:db/id (:seon.error.occurrence/data-blob old))])
              ;; a new opening replaces the selection; a recurrence keeps it
              (some? opened) (into (map #(vector :db/retract [:seon.error/signature signature]
                                                  :seon.error/notified %))
                                   notified)
              true (conj (cond-> error-row
                           (seq messages) (assoc :seon.error/notified (set (map :db/id messages))))))
            messages))))))

(defn evidence-stage
  "One prepared fact with its WHOLE evidence staged when the fact kept less.

  A capped fact's `data-edn` is only a presentation; the complete admitted
  evidence (`:seon.error/data-content`) is staged here and its digest recorded
  as `:seon.error/data-blob`, so the cause outlives the cap. The caller
  publishes the staged write with its commit through `blob/with-publication!`.
  Without a connection nothing can be published, so nothing is staged; an
  uncapped fact, or one already carrying a blob, stages nothing."
  {:malli/schema [:=> [:cat [:or :nil :seon.db/connection] :seon.error/prepared]
                  [:map [:seon.error/fact :seon.error/fact]
                   [:seon.blob/staged-writes [:vector :seon.blob/staged-write]]]]}
  [connection {fact :seon.error/fact content :seon.error/data-content}]
  (if (and connection (:seon.error/capped? fact) (nil? (:seon.error/data-blob fact)))
    (let [staged (blob/stage! connection content)]
      {:seon.error/fact (assoc fact :seon.error/data-blob (:seon.blob/digest staged))
       :seon.blob/staged-writes [staged]})
    {:seon.error/fact fact :seon.blob/staged-writes []}))

(defn- distinguishing-members
  "The members the declared schema adds to :seon.error/base, as observed.
  The fact keeps the base site; the returned value keeps what makes this
  error the declared one (for example :seon.turn.loop/phase-failed)."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.error/declared-schema :map] :map]}
  [projection declared-schema observation]
  (let [registry (:seon.schema.projection/registry projection)
        entries #(into #{} (map first) (some-> (mr/schema registry %) internal/entity-entries))]
    (select-keys observation (remove (entries :seon.error/base) (entries declared-schema)))))

(defn recording
  "Prepared identities, flat value and transaction data for one error.

  The leading identity row preserves existing transaction composition; it
  contains no occurrence state. Counts and notification decisions belong
  exclusively to commit-call's mid-transaction database. Both entry points
  require the producer's declared schema; the positional form carries it as
  its third argument, before the source observation."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.error/commit-tx-request] [:or :seon.error/recording :seon.db/error-result :seon.error/base]]
    [:=> [:cat :map :seon.db/database-value :seon.error/declared-schema :seon.error/source :inst :map] [:or :seon.error/recording :seon.db/error-result :seon.error/base]]]}
  ([database request]
   (let [request (assoc request :seon.schema/projection
                        (or (db/carried-projection database)
                            (:seon.schema/projection request)
                            (schema/projection-from-database database)))
         source (:seon.error/source request)
         acquiring? (and (map? source) (:seon.error/signature source)
                         (not (:seon.error.occurrence/id source)))
         acquired (when acquiring?
                    (db/pull database
                             (observation-selector (:seon.schema/projection request))
                             [:seon.error/signature (:seon.error/signature source)]))
         read-refusal (when (and acquiring? (map? acquired)
                                (inst? (:seon.error/at acquired))
                                (qualified-keyword? (:seon.error/layer acquired))
                                (qualified-symbol? (:seon.error/operation acquired))) acquired)]
     (cond
       read-refusal read-refusal
       (and acquiring? (nil? acquired))
       {:seon.error/at (:seon.error/at request)
        :seon.error/layer :seon.error/reading
        :seon.error/operation 'seon.error/recording
        :seon.error/message "The referenced error observation is unavailable."
        :seon.error/expected-key :seon.error/error}
       :else
       (let [source (if acquiring? (latest-fact acquired) source)
         request (cond-> request (map? source)
                   (assoc :seon.error/source
                          (stored-observation (:seon.schema/projection request) source)))
         prepared (prepare request)
         request (assoc request :seon.error/source (:seon.error/source prepared))
         connection (:seon.db/connection request)
         request (dissoc request :seon.db/connection)
         {fact :seon.error/fact staged :seon.blob/staged-writes}
         (evidence-stage connection
                         (assoc prepared :seon.error/fact
                                (or (:seon.error/fact request) (:seon.error/fact prepared))))
         signature (:seon.error/signature fact)
         agent-id (second (:seon.error/agent fact))
         turn-id (second (:seon.error/run fact))
         origin (:seon.error.occurrence/branch request)
         obligation (:seon.error/obligation request)
         occurrence-id (id/id (into (sorted-map)
                                   (cond-> {:seon.error/signature signature}
                                     ;; a copy from another branch never merges with this branch's own
                                     (and origin (not= origin (get-in database [:config :branch])))
                                     (assoc :seon.store/branch origin)
                                     agent-id (assoc :seon.agent/id agent-id)
                                     ;; an obligation is one identity across turns and processes
                                     obligation (assoc :seon.error/obligation obligation)
                                     (and turn-id (not obligation)) (assoc :seon.turn/id turn-id)
                                     (and (nil? turn-id) (not obligation))
                                     (assoc :seon.db.process/id (:seon.error/process fact)))))
         rows [{:db/id (fact-tempid (:seon.error/id request))
                :seon.error/id signature :seon.error/signature signature
                :seon.error/declared-schema (:seon.error/declared-schema fact)
                :seon.error/layer (:seon.error/layer fact)
                :seon.error/operation (:seon.error/operation fact)}]
         tx (conj rows [:db.fn/call #'commit-call
                        (assoc request :seon.error/fact fact :seon.error.occurrence/id occurrence-id)])]
     {:seon.error/fact fact
      :seon.error/ref [:seon.error/signature signature]
      :seon.error.occurrence/ref [:seon.error.occurrence/id occurrence-id]
      :seon.error/value (merge (value fact)
                               (distinguishing-members (:seon.schema/projection request)
                                                       (:seon.error/declared-schema fact)
                                                       (:seon.error/source request)))
      :seon.blob/staged-writes staged
      :seon.db/tx-data tx}))))
  ([cluster database declared-schema source at attribution]
   (recording database
              (merge (select-keys cluster [:seon.sci.admit/caps :seon.config.error/recurrence-limit
                                          :seon.config.error/max-evidence-bytes :seon.config.error/escalate-to])
                     (select-keys cluster [:seon.db/connection])
                     {:seon.error/source source :seon.error/declared-schema declared-schema :seon.error/id (id/id)
                      :seon.error/at at :seon.error/process (:seon.db.process/id cluster)
                      :seon.error/basis-t (db/basis-t database)}
                     attribution))))

(defn occurred-since
  "The unresolved errors written on `database`'s branch after basis `since`, one
  entry per signature: the window (since, basis of `database`]. Shown once per
  window, no seen-flag; a recurrence writes its occurrence again and re-enters.
  The count sums the touched occurrences' cumulative counts. A branch other than
  `database`'s own marks a core fault's cluster-branch copy."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/basis-t]
                  [:or [:vector [:map [:seon.error/signature :seon.error/signature]
                                 [:seon.error/operation :seon.error/operation]
                                 [:seon.error.occurrence/message :seon.error.occurrence/message]
                                 [:seon.error.occurrence/count :seon.error.occurrence/count]
                                 [:seon.error.occurrence/branch {:optional true} [:set :seon.store/branch]]]]
                   :seon.db/error-result]]}
  [database since]
  (let [rows (db/q '[:find ?signature ?operation ?message ?count ?tx ?occurrence
                     :in $ ?since
                     :where [?occurrence :seon.error.occurrence/last-at _ ?tx]
                            [(> ?tx ?since)]
                            [?error :seon.error/occurrences ?occurrence]
                            [?error :seon.error/signature ?signature]
                            [?error :seon.error/operation ?operation]
                            [?occurrence :seon.error.occurrence/message ?message]
                            [?occurrence :seon.error.occurrence/count ?count]
                            ;; not-join: Datahike's `not` drops ?tx from the predicate
                            (not-join [?error ?tx]
                                      [?error :seon.error/resolved-tx ?resolution]
                                      [(>= ?resolution ?tx)])]
                   database since)
        branches (when-not (map? rows)
                   (db/q '[:find ?occurrence ?branch :in $ [?occurrence ...]
                           :where [?occurrence :seon.error.occurrence/branch ?branch]]
                         database (mapv peek rows)))]
    (cond
      (map? rows) rows
      (map? branches) branches
      :else
      (let [branch (into {} branches)]
        (->> (group-by first rows)
             (sort-by (fn [[_ group]] (- (reduce max (map #(nth % 4) group)))))
             (mapv (fn [[signature group]]
                     (let [[_ operation message] (apply max-key #(nth % 4) group)
                           branches (into #{} (keep (comp branch peek)) group)]
                       (cond-> {:seon.error/signature signature :seon.error/operation operation
                                :seon.error.occurrence/message message
                                :seon.error.occurrence/count (reduce + (map #(nth % 3) group))}
                         (seq branches) (assoc :seon.error.occurrence/branch branches))))))))))

(defn commit-tx
  "Transaction data for one observation, or its acquisition refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/commit-tx-request]
                  [:or :seon.store/transaction-data :seon.db/error-result :seon.error/base]]}
  [database request]
  (let [result (recording database request)]
    (if-let [transaction (:seon.db/tx-data result)] transaction result)))

;;; ---------------------------------------------------------------------------
;;; The family default render
;;; ---------------------------------------------------------------------------

(defn observation-selector
  "Read every declared observation member and owned child without pull's
  implicit cardinality limit. Peer references remain references."
  {:malli/schema [:=> [:cat :seon.schema/projection] :seon.db/pull-selector]}
  [projection]
  (schema/projection-cache-value
   projection ::observation-selector
   (fn []
     (let [forms (:seon.schema.projection/forms projection)
           storable? #(schema.datahike/storable-attribute-in? projection %)
           observation-keys (conj (declared-schema-keys projection)
                                  :seon.error/base :seon.error.occurrence/occurrence)]
       (letfn [(members [schemas]
                 (sort (into #{} (comp (mapcat #(let [compiled (mr/schema (:seon.schema.projection/registry projection) %)]
                                                                (when (:seon.db/attributes (internal/entity-properties compiled))
                                                                  (map first (internal/entity-entries compiled)))))
                                       (filter storable?)) schemas)))
               (selector [schemas active]
                 (into [:db/id]
                       (map (fn [attribute]
                              (let [child (:seon.db/component-schema
                                           (m/properties (mr/schema (:seon.schema.projection/registry projection) attribute)))]
                                (if (and child (not (contains? active child)))
                                  {[attribute :limit nil]
                                   (selector (if (= child :seon.error.occurrence/occurrence)
                                               observation-keys #{child})
                                             (conj active child))}
                                  [attribute :limit nil]))))
                       (members schemas)))]
         (selector #{:seon.error/error} #{:seon.error/error}))))))

(defn latest-fact
  "Project an error's latest occurrence for the existing diagnostic renderers.
  This error-handling reader reconstructs any recorded error under the bare base contract; stored entity maps remain data."
  {:malli/schema
   [:=> [:cat :map]
    [:or :map :seon.error/base]]}
  [error]
  (if (some #(not (map? %)) (:seon.error/occurrences error))
    (seon.error.refusal/diagnostic (java.util.Date.) :seon.error/reading 'seon.error/latest-fact
     {:seon.error/expected-key :seon.error.occurrence/occurrence
     :seon.error/message "Occurrence evidence was not acquired."})
    (if-let [occurrence (last (sort-by :seon.error.occurrence/last-at
                                    (:seon.error/occurrences error)))]
    (cond-> (merge (dissoc error :seon.error/occurrences)
                   (dissoc occurrence :db/id)
                   {:seon.error/at (or (:seon.error/at occurrence)
                                        (:seon.error.occurrence/last-at occurrence))
                    :seon.error/message (or (:seon.error/message occurrence)
                                             (:seon.error.occurrence/message occurrence))
                    :seon.error/occurrence-count
                    (reduce + 0 (map :seon.error.occurrence/count (:seon.error/occurrences error)))})
      (:seon.error.occurrence/agent occurrence)
      (assoc :seon.error/agent (:seon.error.occurrence/agent occurrence))
      (:seon.error.occurrence/turn occurrence)
      (assoc :seon.error/run (:seon.error.occurrence/turn occurrence)))
    error)))

(defn- rendered-error-value
  {:malli/schema [:=> [:cat :seon.error/source]
    :seon.error/source]}
  [unit]
  (let [value (if (map? (:seon.render/value unit))
                (:seon.render/value unit)
                unit)
        database (:seon.db/db unit)
        ;; The stored row, when this database holds one; an error it never
        ;; stored (a refusal returned before, or instead of, its recording)
        ;; is shown as the value itself.
        value (or (when (and database (:seon.error/signature value))
                    (db/pull database
                             (observation-selector (or (db/carried-projection database)
                                                       (:seon.schema/projection unit)
                                                       (schema/projection-from-database database)))
                             [:seon.error/signature (:seon.error/signature value)]))
                  value)]
    (if (map? value) (latest-fact value) value)))





(defn declared-output-validators
  "Compile the named error alternatives of one declared arity.
  This inspects the supplied contract, never an error value or the registry population."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value [:int {:min 0}]]
                  [:vector [:tuple :qualified-keyword :seon.schema/compiled-validator]]]}
  [projection authored argument-count]
  (schema/projection-cache-value
   projection [::declared-output-validators authored argument-count]
   (fn []
     (let [contract (m/schema
                     (schema/compilable-form authored (schema/predicate-functions-in projection))
                     {:registry (:seon.schema.projection/registry projection)})]
       (into []
             (comp (map m/-function-info)
                   (filter #(and (<= (:min %) argument-count)
                                 (or (nil? (:max %)) (<= argument-count (:max %)))))
                   (mapcat #(mu/subschemas (:output %)))
                   (filter (fn [{:keys [schema in]}]
                             (and (empty? in)
                                  (qualified-keyword? (m/form schema))
                                  (not= :seon.error/base (m/form schema))
                                  (internal/extends-schema? schema :seon.error/base))))
                   (map (fn [{:keys [schema]}] [(m/form schema) (m/validator schema)])))
             (m/-function-schema-arities contract))))))

(defn declared-schema-keys
  "Canonical base-extension declarations in this projection, excluding aliases.
  The immutable projection retains the derived population, never error values."
  {:malli/schema [:=> [:cat :map] [:set :qualified-keyword]]}
  [projection]
  (schema/projection-cache-value
   projection ::declared-schema-keys
   (fn []
     (let [forms (:seon.schema.projection/forms projection)]
       (into #{}
             (keep (fn [[k definition]]
                     (when (and (not= k :seon.error/base)
                                (= :and (m/type (mr/schema (:seon.schema.projection/registry projection) k)))
                                (internal/extends-schema?
                                 (mr/schema (:seon.schema.projection/registry projection) k) :seon.error/base))
                       k)))
             forms)))))













(defn- evidence-path
  {:malli/schema [:=> [:cat :seon.error/id]
                  :string]}
  [id]
  (render.route/path :seon.render.route/data
                     {}
                     {:entity (pr-str [:seon.error/id id])
                      :offset "0"}))

(defn render-ai
  "Render the flat error value, preserving its recorded diagnostic data."
  {:malli/schema [:=> [:cat :seon.render/unit] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        source (when (:seon.error/data-edn value) (fact-source value))]
    (str (or (refusal-text unit (or source value) (:seon.error/data (or source value)))
        (:seon.error/message source)
        (:seon.error/message value)
        "Error evidence is unavailable.")
         (when-let [shown (:seon.error/shown value)]
           (str "\n" shown))
         (when-let [n (:seon.error/occurrence-count value)]
           (str " Occurrences: " n ".")))))

(defn render-html
  "Render one fault's operation, message, time, function, turn, and evidence link."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [value (rendered-error-value unit)
        turn (:seon.error/run value)
        ;; the observation selector keeps peer references as references, so a
        ;; re-pulled turn is `{:db/id n}`: its identity is read from the unit's
        ;; database, the declared render input
        turn-id (when (map? turn)
                  (or (:seon.turn/id turn)
                      (when-let [database (:seon.db/db unit)]
                        (let [pulled (db/pull database [:seon.turn/id] (:db/id turn))]
                          (when (string? (:seon.turn/id pulled)) (:seon.turn/id pulled))))))
        turn-ref (cond turn-id [:seon.turn/id turn-id]
                       (map? turn) (:db/id turn)
                       :else turn)]
    (into
     [:article {:class "seon-family-entry seon-error-entry"}
      [:p {:class "seon-kicker"} (some-> (:seon.error/operation value) str)]
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
          (let [database (:seon.db/db unit)
                resolved (when database (resolution database (:seon.error/signature value)))]
            (cond (nil? database) "Resolution unknown"
                  (:seon.error/at resolved) "Resolution unknown"
                  (:seon.error/digest-now resolved) (str "Resolved by change to " (:seon.instrument/fn resolved))
                  resolved "Resolved"
                  (:seon.error/resolved-tx value) "Open: recurred after its resolution"
                  :else "Open"))]])
      (when turn-ref
        [[:p {:class "seon-error-run"}
          [:a {:href (render.route/path :seon.render.route/data {}
                                        {:entity (pr-str turn-ref)})}
           (str "Turn: " (if (vector? turn-ref) (second turn-ref) "identity unavailable"))]]])
      (when-let [id (:seon.error/id value)]
        [[:p {:class "seon-error-link"}
           [:a {:href (evidence-path id)} "Inspect evidence"]]])))))



(defn- fault-order
  {:malli/schema [:=> [:cat :map]
                  [:tuple :int :string]]}
  [fault]
  [(if-let [at (:seon.error/at (latest-fact fault))] (- (.getTime ^java.util.Date at)) 0)
   (str (:seon.error/id fault))])



(defn- fault-entities
  {:malli/schema [:=> [:cat :seon.error/source]
                  [:sequential :map]]}
  [faults]
  (->> (if (coll? faults) faults [])
       (map (fn [fault]
              (if (map? fault) fault
                  {:seon.error/at (java.util.Date.)
                   :seon.error/layer :seon.error/reading
                   :seon.error/operation 'seon.error/fault-entities
                   :seon.error/message (str "Fault entity was not acquired: " (pr-str fault))})))
       (sort-by fault-order)))

(defn- faults-input
  {:malli/schema [:=> [:cat :seon.error/source]
                  :seon.error/source]}
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
  '[:find [?error ...]
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

(defn for-agent
  "Return the errors assigned to an agent, newest first, each with every
  declared observation member and occurrence.

  An error is assigned when it names a function in a namespace the agent
  stewards, or when one of its occurrences happened in the agent's work.
  Example: (seon.error/for-agent (seon.db/db) \"root\")"
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or [:vector :seon.db/pulled-entity] :seon.db/error-result]]}
  [database agent-id]
  ;; Pulling inside the query costs a quarter of a separate pull-many
  ;; (1.4 ms / 4 MB against 6.3 ms / 17 MB on root, 2026-09-24).
  (let [selector (observation-selector (or (db/carried-projection database)
                                           (schema/projection-from-database database)))
        errors (db/q (assoc-in agent-faults-query [1 0] (list 'pull '?error selector))
                     database agent-id)]
    (if (sequential? errors) (vec (sort-by fault-order errors)) errors)))

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
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :nil :seon.render/form :seon.db/error-result]]}
  [unit]
  (when-let [entity (faults-input unit)]
   (let [row (db/pull (:seon.db/db unit) [:seon.agent/id] entity)]
    (if (and (map? row) (inst? (:seon.error/at row))
             (qualified-keyword? (:seon.error/layer row))
             (qualified-symbol? (:seon.error/operation row)))
      row
      (when-let [agent-id (:seon.agent/id row)]
      {:seon.repl/comment
       ";; Inspect errors in the namespaces assigned to me and in my own turns."
       :seon.repl/form (list 'seon.error/for-agent '(seon.db/db) agent-id)})))))

(defn render-faults-ai
  "Emit the steward's read, or render already acquired fault entities."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/source]]}
  [unit]
  (let [faults (faults-input unit)]
    (if (and (sequential? faults) (every? map? faults))
      (when (seq faults) (str/join "\n" (map render-ai (fault-entities faults))))
      (let [entry (faults-form unit)]
        (if-let [form (:seon.repl/form entry)]
          (str (:seon.repl/comment entry) "\n" (repl/source-text form))
          (when entry (render-ai entry)))))))

(defn render-faults-html
  "Render faults routed to a steward, or already acquired faults, newest first."
  {:malli/schema [:function [:=> [:cat :seon.render/unit] :seon.render/hiccup] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.db/database-value] :seon.render/hiccup]]}
  ([unit] (render-faults-html (faults-input unit) (:seon.db/db unit)))
  ([faults database]
  (let [acquired? (and (sequential? faults) (not (keyword? (first faults))))
        row (when-not acquired?
              (let [identity-row (db/pull database [:seon.agent/id] faults)]
                (if-let [agent-id (:seon.agent/id identity-row)]
                  (for-agent database agent-id)
                  identity-row)))
        references (cond acquired? faults
                         (and (map? row) (inst? (:seon.error/at row))
                              (qualified-keyword? (:seon.error/layer row))
                              (qualified-symbol? (:seon.error/operation row))) [row]
                         :else row)
        entities (fault-entities references)]
    (into [:section {:class "seon-family-entry seon-error-faults"}
           [:h2 (str "Faults (" (count entities) ")")]]
          (if (seq entities)
            (map #(render-html {:seon.db/db database :seon.render/value %}) entities)
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

(defn- evidence-text
  "Present the supplied diagnostic evidence without a classification lookup."
  {:malli/schema [:=> [:cat [:or :nil :map]] [:or :nil :string]]}
  [evidence]
  (when (seq evidence)
    (str/join "\n" (map (fn [[attribute value]] (str attribute "=" (pr-str value))) evidence))))

(defn edit-prose
  "`:seon.render/ai` — selection evidence for an edit that did not apply."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (merge (:seon.error/data value)
                        (select-keys value [:my.edit/error-path :my.edit/edit-observation]))]
    (str/join
     "\n"
     (remove nil?
             [(:seon.error/message value)
              (evidence-text evidence)
              "Re-read the exact source and narrow the edit selection before applying it again."]))))

(defn elision-prose
  "`:seon.render/ai` — a neutral account of bounded render-walk elision."
  {:malli/schema [:=> [:cat :seon.render/unit] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (:seon.error/data value)]
    (str/join
     " "
     (remove nil?
             ["Additional render-walk content was elided by the active render profile."
              (evidence-text evidence)
              "Request the next offset when more detail is needed."]))))

(defn elision-html
  "`:seon.render/html` — a neutral elision notice, never an error card."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (:seon.error/data value)]
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
  "`:seon.render/ai` — evidence from an observation whose domain is unavailable."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (:seon.error/data value)]
    (str/join
     "\n"
     (remove nil?
             [(:seon.error/message value)
              "The original boundary did not supply complete domain evidence."
              (evidence-text evidence)
              "Inspect the observation and its boundary contract before retrying."]))))

(defn mcp-prose
  "`:seon.render/ai` — retrieval evidence for a failed MCP value lookup."
  {:malli/schema [:=> [:cat :seon.render/unit] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (merge (:seon.error/data value)
                        (select-keys value [:seon.dev.mcp/error-cluster :seon.dev.mcp/request-observation]))]
    (str/join
     "\n"
     (remove nil?
             [(:seon.error/message value)
              (evidence-text evidence)
              "Re-read the current cluster status or value identity before requesting the data again."]))))

(defn index-refusal-prose
  "`:seon.render/ai` — the precise evidence that stopped program indexing."
  {:malli/schema [:=> [:cat :seon.render/unit] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (merge (:seon.error/data value)
                        (select-keys value [:seon.fn/error-subject :seon.fn/analysis-phase]))]
    (str/join
     "\n"
     (remove nil?
             [(:seon.error/message value)
              (evidence-text evidence)
              "Repair the named source or declaration evidence, then rerun initialization."]))))

;;; Complete owned error observations.

(defn- exclusive-members?
  "Exactly one of the supplied attributes is present on a candidate map."
  {:malli/schema [:=> [:cat :seon.schema/value [:vector :qualified-keyword]] :boolean]}
  [value attributes]
  (and (map? value) (= 1 (count (filter #(contains? value %) attributes)))))

(defn omission-complete?
  "An omission reports a positive omitted count or why that count is unavailable."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (exclusive-members? value [:seon.error.omission/omitted-count
                                  :seon.error.omission/unavailable-count-reason])
       (if-let [entry (find value :seon.error.omission/omitted-count)]
         (pos-int? (val entry))
         (let [reason (:seon.error.omission/unavailable-count-reason value)]
           (and (string? reason) (not (empty? reason)))))))

(defn- ordered-members?
  "Counts describe retained children, with contiguous unique ordinals and honest omissions."
  {:malli/schema [:=> [:cat :seon.schema/value :qualified-keyword :qualified-keyword
                       :qualified-keyword [:or :nil :qualified-keyword]] :boolean]}
  [value count-key children-key ordinal-key omission-key]
  (and (map? value)
       (let [n (get value count-key)
             children (get value children-key)
             omission (get value omission-key)]
         (and (nat-int? n)
              (if (zero? n)
                (not (contains? value children-key))
                (and (coll? children) (= n (count children))
                     (every? map? children)
                     (= (set (range n)) (set (map ordinal-key children)))))
              (or (not (contains? value omission-key))
                  (and (map? omission) (omission-complete? omission)
                       (= n (:seon.error.omission/retained-count omission))))))))

(defn ordered-location?
  "The retained path has precisely its declared ordered segments."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (ordered-members? value :seon.error.location/length :seon.error.location/segments
                    :seon.error.location.segment/ordinal :seon.error.location/omission))

(defn ordered-messages?
  "The retained humanization has precisely its declared ordered messages."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (ordered-members? value :seon.instrument.humanized/message-count
                    :seon.instrument.humanized/messages
                    :seon.instrument.humanized.message/ordinal
                    :seon.instrument.humanized/omission))

(defn ordered-explanations?
  "An explanation collection retains a positive, ordered number of problems."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value) (pos-int? (:seon.instrument.explanations/count value))
       (ordered-members? value :seon.instrument.explanations/count
                         :seon.instrument.explanations/items
                         :seon.instrument.explanation/ordinal
                         :seon.instrument.explanations/omission)))

(defn ordered-failures?
  "An accretion group retains exactly its counted failures in ordinal order."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (ordered-members? value :seon.test.accretion.group/count
                    :seon.test.accretion.group/failures
                    :seon.test.accretion.failure/ordinal nil))

(defn ordered-groups?
  "Optional accretion groups and their count are supplied together."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (if (contains? value :seon.test.accretion/error-group-count)
         (ordered-members? value :seon.test.accretion/error-group-count
                           :seon.test.accretion/error-groups
                           :seon.test.accretion.group/ordinal nil)
         (not (contains? value :seon.test.accretion/error-groups)))))

(defn projection-complete?
  "A leaf observation contains text or an observed missing key, never both."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (exclusive-members? value [:seon.instrument/actual :seon.error.projection/missing-member]))

(defn evidence-complete?
  "Evidence contains one exact scalar or one admitted projection."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (exclusive-members? value [:seon.error.evidence/value :seon.error.evidence/projection]))

(defn explanation-complete?
  "A problem has humanized evidence or an explicit failed humanization observation."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (exclusive-members? value [:seon.instrument.explanation/humanized
                             :seon.instrument.explanation/humanization-unavailable]))

(defn arity-bounds-valid?
  "A declared invocation interval has nonnegative, ordered endpoints."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (nat-int? (:seon.instrument.arity/min value))
       (or (not (contains? value :seon.instrument.arity/max))
           (and (nat-int? (:seon.instrument.arity/max value))
                (<= (:seon.instrument.arity/min value) (:seon.instrument.arity/max value))))))

(defn arity-refusal-valid?
  "Every recorded declared interval excludes the actual invocation count."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value) (nat-int? (:seon.instrument/arity value))
       (pos-int? (:seon.instrument/declared-arity-count value))
       (ordered-members? value :seon.instrument/declared-arity-count
                         :seon.instrument/declared-arities :seon.instrument.arity/ordinal nil)
       (every? (fn [bounds]
                 (and (arity-bounds-valid? bounds)
                      (let [n (:seon.instrument/arity value)]
                        (or (< n (:seon.instrument.arity/min bounds))
                            (when-let [maximum (:seon.instrument.arity/max bounds)]
                              (> n maximum))))))
               (:seon.instrument/declared-arities value))))



(defn config-expectation-present?
  "A config refusal identifies at least one actual expected constraint."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (boolean (or (find value :seon.error/expected-key)
                    (find value :seon.error/expected-shape)))))

(defn stop-target-present?
  "A stop observation names the graph it asks to stop."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (or (not= :stop-graph (:seon.error.disposition/action value))
           (let [graph (:seon.error.disposition/graph-id value)]
             (and (string? graph) (not (empty? graph)))))))

(defn- read-target-group
  "Derive the read grammar from exactly the observed target members."
  {:malli/schema [:=> [:cat :seon.schema/value] [:or :nil :keyword]]}
  [value]
  (when (map? value)
    (let [members (set (filter #(contains? value %)
                              [:seon.db.read.target/query :seon.db.read.target/arguments
                               :seon.db.read.target/selector :seon.db.read.target/entity-projection
                               :seon.db.read.target/index :seon.db.read.target/index-request]))]
      (cond
        (= members #{:seon.db.read.target/query :seon.db.read.target/arguments})
        (when-not (contains? value :seon.db.read.target/attribute) :q)
        (= members #{:seon.db.read.target/selector :seon.db.read.target/entity-projection})
        (when-not (contains? value :seon.db.read.target/attribute) :pull)
        (= members #{:seon.db.read.target/index :seon.db.read.target/index-request}) :index-page))))

(defn read-target-complete?
  "A read target supplies exactly one complete query, pull or index request."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (boolean (read-target-group value)))

(defn read-operation-agrees?
  "The read operation agrees with the complete owned target's request grammar."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (let [operation (:seon.db/read-operation value)
             group (read-target-group (:seon.db.read/target value))]
         (and (some? group)
              (= group (if (= :pull-many operation) :pull operation))))))

(def ^:private error-base-generator
  (gen/fmap (fn [millis]
              {:seon.error/at (java.util.Date. (long millis))
               :seon.error/layer :seon.instrument/invocation
               :seon.error/operation 'seon.id/valid?})
            gen/nat))

(def ^:private observed-key-generator
  (gen/fmap (fn [scalar]
              (cond-> {:seon.error.key/projection (pr-str scalar)
                       :seon.error.key/capped? false :seon.error.key/bound-bytes 256}
                (some? scalar) (assoc :seon.error.key/scalar scalar)))
            (gen/elements [nil false :plain :my.example/key 'plain 'my.example/fn "key" 0])))

(def omission-complete-generator
  (gen/let [retained gen/nat omitted gen/s-pos-int known? gen/boolean]
    (cond-> {:seon.error.omission/bound-key :seon.config.error/max-evidence-bytes
             :seon.error.omission/bound 256 :seon.error.omission/retained-count retained}
      known? (assoc :seon.error.omission/omitted-count omitted)
      (not known?) (assoc :seon.error.omission/unavailable-count-reason "Dependency did not count omitted members."))))

(def projection-complete-generator
  (gen/one-of
   [(gen/fmap (fn [text]
                {:seon.error/capped? false :seon.error.projection/bound-bytes 256
                 :seon.instrument/actual text}) gen/string-alphanumeric)
    (gen/fmap (fn [key]
                {:seon.error/capped? false :seon.error.projection/bound-bytes 256
                 :seon.error.projection/missing-member key}) observed-key-generator)]))

(def evidence-complete-generator
  (gen/one-of
   [(gen/fmap (fn [scalar] {:seon.error.evidence/attribute :seon.instrument/arity
                            :seon.error.evidence/value scalar})
              (gen/elements [false 0 2 "observed" :my.example/member 'my.example/fn]))
    (gen/fmap (fn [projection] {:seon.error.evidence/attribute :seon.instrument/actual
                                :seon.error.evidence/projection projection})
              projection-complete-generator)]))

(def ordered-location-generator
  (gen/fmap (fn [keys]
              (cond-> {:seon.error.location/length (count keys)}
                (seq keys) (assoc :seon.error.location/segments
                                 (set (map-indexed (fn [n key]
                                                     {:seon.error.location.segment/ordinal n
                                                      :seon.error.location.segment/key key}) keys)))))
            (gen/vector observed-key-generator 0 4)))

(def ordered-messages-generator
  (gen/fmap (fn [messages]
              (cond-> {:seon.instrument.humanized/message-count (count messages)}
                (seq messages)
                (assoc :seon.instrument.humanized/messages
                       (set (map-indexed (fn [n [text location]]
                                           {:seon.instrument.humanized.message/ordinal n
                                            :seon.instrument.humanized.message/text text
                                            :seon.instrument.humanized.message/location location}) messages)))))
            (gen/vector (gen/tuple gen/string-alphanumeric ordered-location-generator) 0 4)))

(def explanation-complete-generator
  (gen/let [ordinal gen/nat schema-location ordered-location-generator
            value-location ordered-location-generator humanized ordered-messages-generator
            known? gen/boolean]
    (cond-> {:seon.instrument.explanation/ordinal ordinal
             :seon.instrument.explanation/schema-location schema-location
             :seon.instrument.explanation/value-location value-location
             :seon.instrument.explanation/expected-shape (apply str (repeat 64 "0"))}
      known? (assoc :seon.instrument.explanation/humanized humanized)
      (not known?) (assoc :seon.instrument.explanation/humanization-unavailable "No dependency message."))))

(def ordered-explanations-generator
  (gen/fmap (fn [items]
              {:seon.instrument.explanations/count (count items)
               :seon.instrument.explanations/items
               (set (map-indexed #(assoc %2 :seon.instrument.explanation/ordinal %1) items))})
            (gen/vector explanation-complete-generator 1 4)))

(def arity-bounds-valid-generator
  (gen/let [minimum gen/nat additional gen/nat bounded? gen/boolean ordinal gen/nat]
    (cond-> {:seon.instrument.arity/ordinal ordinal :seon.instrument.arity/min minimum}
      bounded? (assoc :seon.instrument.arity/max (+ minimum additional)))))

(def arity-refusal-valid-generator
  (gen/let [base error-base-generator actual gen/nat intervals (gen/vector arity-bounds-valid-generator 1 4)]
    (assoc base :seon.instrument/fn 'seon.id/valid? :seon.instrument/arity actual
           :seon.instrument/declared-arity-count (count intervals)
           :seon.instrument/declared-arities
           (set (map-indexed
                 (fn [ordinal bounds]
                   (cond-> (assoc bounds :seon.instrument.arity/ordinal ordinal
                                  :seon.instrument.arity/min (+ actual 1 (:seon.instrument.arity/min bounds)))
                     (:seon.instrument.arity/max bounds)
                     (update :seon.instrument.arity/max + actual 1))) intervals)))))

(def config-expectation-present-generator
  (gen/let [base error-base-generator key? gen/boolean]
    (assoc base :seon.config/error-key :seon.config/on-core-error
           (if key? :seon.error/expected-key :seon.error/expected-shape)
           (if key? :seon.config/on-core-error (apply str (repeat 64 "0"))))))

(def stop-target-present-generator
  (gen/let [action (gen/elements [:return :abort-operation :stop-graph]) evidence evidence-complete-generator]
    (cond-> {:seon.error.disposition/action action :seon.error.disposition/observer 'seon.id/valid?
             :seon.error.disposition/evidence evidence}
      (= :stop-graph action) (assoc :seon.error.disposition/graph-id "generated-graph"))))

(def read-target-complete-generator
  (gen/let [operation (gen/elements [:q :pull :index-page])
            a projection-complete-generator b projection-complete-generator]
    (case operation
      :q {:seon.db.read.target/query a :seon.db.read.target/arguments b}
      :pull {:seon.db.read.target/selector a :seon.db.read.target/entity-projection b}
      :index-page {:seon.db.read.target/index :eavt :seon.db.read.target/index-request a})))

(def read-operation-agrees-generator
  (gen/let [base error-base-generator target read-target-complete-generator many? gen/boolean]
    (let [group (read-target-group target)]
      (assoc base :seon.db/read-operation (if (and (= :pull group) many?) :pull-many group)
             :seon.db.read/target target
             :seon.error/basis {:seon.error.basis/store #uuid "00000000-0000-0000-0000-000000000001"
                                :seon.error.basis/branch :generated
                                :seon.error.basis/commit #uuid "00000000-0000-0000-0000-000000000002"
                                :seon.error.basis/t 1}))))



(def ordered-failures-generator
  (gen/fmap (fn [items]
              (cond-> {:seon.test.accretion.group/ordinal 0 :seon.test.accretion.group/shape "observed"
                       :seon.test.accretion.group/count (count items)}
                (seq items) (assoc :seon.test.accretion.group/failures
                                  (set (map-indexed (fn [n evidence]
                                                      {:seon.test.accretion.failure/ordinal n
                                                       :seon.test.accretion.failure/source :test
                                                       :seon.test.accretion.failure/evidence evidence}) items)))))
            (gen/vector projection-complete-generator 0 4)))

(def ordered-groups-generator
  (gen/let [base error-base-generator projection projection-complete-generator
            groups (gen/vector ordered-failures-generator 0 4)]
    (cond-> (assoc base :seon.instrument/fn 'seon.id/valid?
                   :seon.error/expected-shape (apply str (repeat 64 "0"))
                   :seon.error/offending-projection projection
                   :seon.test.accretion/error-group-count (count groups))
      (seq groups) (assoc :seon.test.accretion/error-groups
                          (set (map-indexed #(assoc %2 :seon.test.accretion.group/ordinal %1) groups))))))
