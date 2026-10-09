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
            [malli.generator :as mg]
            [malli.util :as mu]
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
            [seon.sci.admit :as admit]))

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

(defn- frame-site
  "The compile-stable part of a stack frame: its named function's class, method
  and file. The compiler numbers every anonymous class (`$fn__6604`) and every
  local fn (`own_write_BANG___16186`, `eval987622`) per compile, and a line moves
  with any edit above it, so neither may enter a fault's identity: on default
  one runner fault became four signatures and one turn backstop fault five
  (2026-09-26), each re-opened by a restart."
  {:malli/schema [:=> [:cat :seon.error/frame] [:tuple :string :string [:or :nil :string]]]}
  [[class-name method file]]
  (let [digits? (fn [text] (and (seq text) (every? #(Character/isDigit ^char %) text)))
        segment (fn [text]
                  (let [cut (str/last-index-of text "__")]
                    (cond (and cut (digits? (subs text (+ cut 2)))) (subs text 0 cut)
                          (and (str/starts-with? text "eval") (digits? (subs text 4))) "eval"
                          :else text)))
        segments (loop [text (str class-name) found []]
                   (if-let [cut (str/index-of text "$")]
                     (recur (subs text (inc cut)) (conj found (subs text 0 cut)))
                     (conj found text)))]
    [(str/join "$" (remove #{"fn"} (map segment segments))) (str method) (some-> file str)]))

(defn- signature
  "D13: identity of the observed site, producer-declared schema, violated
  schema and path, and the producing definition's name (never its digest).
  Incidental time, process, message, offending bytes and the compiler's class
  numbering and line numbers ([[frame-site]]) never enter this tuple."
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
                            (:seon.error.location/segments location)))]
    (id/id [(:seon.error/layer observation)
            (:seon.error/operation observation)
            declared-schema
            throwable-class (some-> frame frame-site)
            (into (sorted-map)
                  (select-keys observation [:seon.error/expected-key :seon.error/expected-shape]))
            path
            ;; who produced it (the blame rule): one refusal reached from two
            ;; callers is two problems with two fixes
            (:seon.error/producer observation)]
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
                                               (if (schema.datahike/vector-attribute-in? projection attribute)
                                                 (mapv convert observed)
                                                 (into #{} (map convert) observed))
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
                         :seon.sci.admit/caps caps}
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
   :seon.error/evidence-unavailable
   :seon.error/fix
   :seon.error/basis])

(defn- classifying-error-data
  "Present current and proposed base observations, in declared priority order,
  then a Throwable's cause chain. Over the bound the chain keeps each link's
  class, message and the first-party frame that raised it; its `ex-data` and
  deeper frames are the remainder whose size the marker reports (a link's whole
  first-party stack is kilobytes under a deep caller, and a partition over the
  bound keeps nothing). Without it an over-bound fault kept no cause at all."
  {:malli/schema [:=> [:cat :seon.error/source [:or :nil :seon.error/throwable]
                       [:or :nil :seon.error/chain]]
                  [:or :map :seon.error/base]]}
  [source failure links]
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
                           links)))))

(defn- evidence-source
  "The value a fault's evidence stores: `source` with its Throwable replaced by
  the one error shape's cause chain (`seon.error.refusal/chain`: per link its
  class, message, `ex-data` with worlds referenced, and first-party frames).
  A Throwable admitted as itself was `Throwable->map`, every frame of every
  link: fault 317111871b50 kept 107 KB of it and still read back as no value."
  {:malli/schema [:=> [:cat :seon.error/source [:or :nil :seon.error/throwable]
                       [:or :nil :seon.error/chain]]
                  :seon.error/source]}
  [source failure links]
  (cond
    (nil? failure) source
    (identical? source failure) {:seon.error/chain links}
    :else (assoc (dissoc source ::flow/ex) :seon.error/chain links)))

(defn- value-edn
  "The stored text of one admission: its semantic value as canonical EDN,
  which `clojure.edn` reads back as the value. The admission's own emitted
  text (`:seon.sci.admit/edn`) is its print-node tree, readable only as
  nodes: fault evidence stored it and never read back as a value."
  {:malli/schema [:=> [:cat :seon.sci.admit/admitted] :string]}
  [admitted]
  (admit/canonical-edn (:seon.sci.admit/value admitted)))

(defn- bounded-error-admission
  "Admit complete evidence, retaining classifying observations on overflow."
  {:malli/schema [:=> [:cat :seon.error/source [:or :nil :seon.error/throwable] :seon.sci.admit/caps [:maybe :seon.schema/projection]]
                  :seon.sci.admit/admitted]}
  [source failure caps projection]
  (let [links (when failure (error.refusal/chain failure projection))
        value (evidence-source source failure links)
        request (cond-> {:seon.sci.admit/value value
                         :seon.sci.admit/interrupt-fn (constantly nil)
                         :seon.sci.admit/caps caps}
                  projection (assoc :seon.schema/projection projection))
        admitted (admit/admit-partitioned
                  request (classifying-error-data source failure links))]
    (if (admit/missing-marker admitted)
      (bounded-admission value caps projection)
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

(defn- contract-violation-data
  "The reporter's declared function/arm evidence: the `ex-data` of the source's
  Throwable (bare or flow's `::flow/ex`), else the source itself when it is
  the reporter's map. It reads the live source, never its admission: since
  ab6b71e2b the admitted evidence replaces the Throwable with its cause chain,
  and a read of the admission lost every contract fault's function and arm."
  {:malli/schema [:=> [:cat :seon.error/source]
                  [:or :nil [:map [:seon.instrument/fn :seon.instrument/fn]
                             [:seon.instrument/arm [:enum :input :output :guard]]]]]}
  [source]
  (let [failure (throwable source)
        refusal? #(and (map? %)
                       (qualified-symbol? (:seon.instrument/fn %))
                       (#{:input :output :guard} (:seon.instrument/arm %)))]
    (cond
      ;; any link: an interpreted caller's refusal arrives inside SCI's
      ;; location copy, whose own data names no function
      failure (some #(let [data (ex-data %)] (when (refusal? data) data)) (error.refusal/cause-links failure))
      (map? (::flow/ex source)) (let [data (:data (::flow/ex source))] (when (refusal? data) data))
      (refusal? source) source)))

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
     :seon.instrument/actual (value-edn admitted)})))

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
      (blob/utf8-size (value-edn admitted)))))

(defn- fit-fact-payload
  "Bound every payload field of one fact so the WHOLE fact fits inline.

  Each field carries at most its share of what the base fact leaves, and a
  field over that share becomes the marker. The halving repeats only because
  a field's bytes are measured on the admitted value while the fact stores it
  as an escaped string; it terminates at one byte, where every field is the
  marker. The evidence is the whole value's EDN (`full-edn`) whenever that
  fits its share: admission bounds its print-node bytes, several times the
  value's, so an evidence of 1 KB was cut to the marker under a 1.6 KB share."
  {:malli/schema [:=> [:cat :map :seon.error/source :string :string [:or :nil :map] [:or :nil [:tuple :qualified-keyword :seon.error/source]] :seon.sci.admit/caps [:int {:min 1}] [:maybe :seon.schema/projection]]
                  [:or :seon.error/fact :seon.error/base]]}
  [base-fact source full-edn message-value instrument-data actual caps inline-limit projection]
  (let [expected (or (:seon.instrument/schema instrument-data)
                     (:seon.error/expected instrument-data))
        arguments (or (:seon.instrument/args instrument-data)
                      (:seon.error/offending instrument-data))
        payload-count (+ 2 (if expected 1 0) (if arguments 1 0) (if actual 1 0))
        available (max 1 (- inline-limit (blob/utf8-size (pr-str base-fact))))]
    (loop [field-limit (max 1 (quot available payload-count))]
      (let [field-caps (evidence-caps caps field-limit)
            fact
            (cond-> (assoc base-fact
                           :seon.error/message
                           (bounded-text message-value field-caps projection)
                           :seon.error/data-edn
                           (if (<= (blob/utf8-size full-edn) field-limit)
                             full-edn
                             (value-edn (bounded-error-admission
                                         source (throwable source) field-caps projection))))
              expected
              (assoc :seon.instrument/expected
                     (bounded-text expected field-caps projection))
              arguments
              (assoc :seon.instrument/args
                     (bounded-text arguments field-caps projection))
              actual
              (assoc :seon.instrument/actual
                     (bounded-text (val actual) field-caps projection)))]
        (if (or (<= (blob/utf8-size (pr-str fact)) inline-limit)
                (= 1 field-limit))
          fact
          (recur (max 1 (quot field-limit 2))))))))

(defn prepare-result
  "Render one result's shown text and retain its live binding.

  The live binding is the actual object under `:seon.error/result-id`; the
  stored form is the shown text under the profile, inline at or under the blob
  threshold, else its staged digest and size, with the staged writes for the
  caller to publish with its commit."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                    [:seon.render/profile :seon.render.profile/profile]
                    [:seon.error/source :seon.error/source]
                    [:seon.sci.eval/ctx {:optional true}
                     [:and :seon.sci.eval/ctx
                      [:map [:seon.sci.eval/bind-result! :seon.instrument/callable]]]]]]
    [:map [:seon.error/result-id :seon.error/result-id]
          [:seon.error/shown {:optional true} :seon.error/shown]
          [:seon.error/shown-blob {:optional true} :seon.error/shown-blob]
          [:seon.error/shown-size :seon.error/shown-size]
          [:seon.blob/staged-writes [:vector :seon.blob/staged-write]]]]}
  [{source :seon.error/source connection :seon.db/connection :as request}]
  (let [entry (or (offending-entry source)
                  (when (map? source) (find source :seon.error/offending))
                  (find (:seon.error/data source) :seon.error/offending))
        value (if entry (val entry) source)
        result-id (id/id)
        handle (admit/result-handle result-id)
        ctx (:seon.sci.eval/ctx request)
        ;; the world is passed, never taken from ambient custody: a core
        ;; fault recorded off an agent's thread has none
        unit (assoc (select-keys request [:seon.schema/projection :seon.render/profile])
                    :seon.db/db (db/db connection)
                    :seon.render/value value :seon.repl/handle handle
                    :seon.render.call/id handle)
        shown (render.value/render-ai-data (render.value/prepare unit))
        {fact :seon.blob/fact writes :seon.blob/staged-writes}
        (blob/inline-or-stage connection shown :seon.error/shown :seon.error/shown-size :seon.error/shown-blob)]
    (when ctx
      ((:seon.sci.eval/bind-result! ctx) ctx handle value))
    (assoc fact :seon.error/result-id result-id :seon.blob/staged-writes writes)))

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
                             :seon.error/offending (error.refusal/references projection observation)})))))

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
        full-edn (value-edn admitted)
        ;; ONE KEY, AND IT IS SUPPLIED. The fault family's own declared bound
        ;; decides how much evidence the FACT keeps; the blob threshold
        ;; decides where the complete evidence lives.
        ;; `:seon.error/inline-limit` was the same number under a second
        ;; spelling and is deleted. The bound is a REQUIRED member of
        ;; `:seon.error/normalize-request` — a fallback to the bootstrap
        ;; number when a caller omitted it was a silent fallback on the
        ;; ordinary path, which is a defect even while it is right.
        inline-limit evidence-bytes
        instrument-data (contract-violation-data source)
        flow? (map? source)
        error-value (error.refusal/references projection
                                              (if failure (refusal failure) source))
        ;; THE COMMON PART, SUPPLIED: the blame rule's producer and caller
        ;; with the digests that ran, and the failing world by reference
        ;; (`seon.error.refusal/attributed`), each stored on the occurrence
        ;; as a declared base member.
        attributed (error.refusal/attributed (if (map? error-value) error-value {}) failure)
        operation (:seon.error/operation attributed)
        ;; A failure's own declared operation wins; else the observing
        ;; boundary's, which is stable where a thrower's class name is not.
        function (or (when (qualified-symbol? operation) operation)
                     (:seon.instrument/fn instrument-data)
                     boundary-operation
                     (:seon.error/producer attributed))
        frame (error.refusal/root-frame failure)
        chain (when failure (error.refusal/chain failure projection))
        observation (merge (cond-> (seon.error.refusal/diagnostic at (or boundary-layer :seon.error/normalization)
                                                                  (or function 'seon.error/normalize)
                                    {})
                             class-name (assoc :seon.error/exception-class (symbol class-name))
                             frame (assoc :seon.error/frame frame)
                             chain (assoc :seon.error/chain chain))
                           attributed)
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
                    (blob/utf8-size full-edn))
        base-fact
        (cond-> (assoc (select-keys observation [:seon.error/layer :seon.error/operation
                                                 :seon.error/exception-class :seon.error/producer])
                       :seon.error/id signature
                       :seon.error/at at
                       :seon.error/process process
                       :seon.error/signature signature
                       :seon.error/declared-schema declared-schema
                       :seon.error/capped? true)
          (int? data-size) (assoc :seon.error/data-size (long data-size))
          class-name (assoc :seon.error/throwable-class class-name)
          frame (assoc :seon.error/frame frame)
          (and flow? (::flow/pid source))
          (assoc :seon.error/proc (::flow/pid source))
          (and flow? (::flow/op source)) (assoc :seon.error/op (::flow/op source))
          (and flow? (::flow/cid source))
          (assoc :seon.error/cid (::flow/cid source))
          ;; `:seon.instrument/fn` NAMES ONLY THE ARMED FUNCTION THAT
          ;; REFUSED. Who produced the failure is `:seon.error/producer`
          ;; (the blame rule); writing the frame that threw here named the
          ;; recorder itself (`seon.turn.world/record-fault`) on 41 of 47
          ;; faults, and resolution watched it (health plan §3).
          instrument-data (into (select-keys instrument-data [:seon.instrument/fn :seon.instrument/arm]))
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
              base-fact source full-edn
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
                              (or (:seon.error/shown source) (:seon.error/shown-blob source)))
                     (assoc (select-keys source [:seon.error/result-id :seon.error/shown :seon.error/shown-blob
                                                 :seon.error/shown-size])
                            :seon.blob/staged-writes []))
                   (when (and (:seon.db/connection request) (:seon.render/profile request))
                     (prepare-result (assoc request :seon.error/source error-value))))
        staged (:seon.blob/staged-writes result [])
        result (dissoc result :seon.blob/staged-writes)]
    {:seon.error/fact (merge fact result)
     ;; A digest the caller stated keeps: a cluster copy names the version that
     ;; ran on its origin branch (`seon.fault/record!`), which the throwable's
     ;; own observation cannot know.
     ;; The occurrence stores the source's declared members over the fact's
     ;; (`commit-tx`), so a data-edn the observation carried whole would
     ;; replace the fitted one: the source keeps what the fact kept.
     :seon.error/source (cond-> (merge (cond-> observation
                                         (contains? observation :seon.error/data-edn)
                                         (assoc :seon.error/data-edn (:seon.error/data-edn fact)))
                                       result)
                          (and (nil? (:seon.error/producer-digest observation))
                               (string? (:seon.error/producer-digest (:seon.error/source request))))
                          (assoc :seon.error/producer-digest
                                 (:seon.error/producer-digest (:seon.error/source request))))
     :seon.blob/staged-writes staged}))

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
                          :seon.error/result-id :seon.error/shown :seon.error/shown-blob
                          :seon.error/shown-size :seon.error/message :seon.error/signature])
         :seon.error/id (:seon.error/id fact)))

;;; ---------------------------------------------------------------------------
;;; The routing unit and its projections
;;; ---------------------------------------------------------------------------

(defn notice
  "The agent-facing unit for one fact; :seon.error/notice declares its AI renderer.

  `:seon.error/reason` is optional. A recorded fault's notification carries
  the error's value, not a notice (`message-tx`)."
  {:malli/schema [:=> [:cat :seon.error/notice-request] :seon.error/notice]}
  [{:seon.error/keys [fact] :as request}]
  (into {:seon.error/fact fact
         :seon.error/evidence [:seon.error/id (:seon.error/id fact)]}
        (filter val)
        (select-keys request [:seon.error/reason :seon.error/occurrence :seon.error/occurrence-count
                              :seon.error/notification-limit :seon.error/notification :seon.agent/id])))

(defn- fact-source
  {:malli/schema [:=> [:cat :map]
                  :seon.error/source]}
  [fact]
  (try
    (edn/read-string (:seon.error/data-edn fact))
    ;; Stored evidence that does not read is itself a reading error;
    ;; the diagnostic carries the failure's whole cause chain and frames.
    (catch Exception failure
      (seon.error.refusal/diagnostic (or (:seon.error/at fact) (java.util.Date.)) :seon.error/reading 'seon.error/fact-source
       {:seon.error/expected-key :seon.error/data-edn
        :seon.error/message (str "Stored error evidence could not be read: "
                                 (or (not-empty (ex-message failure))
                                     (.getName (class failure))))
        :seon.error/throwable failure}))))

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

(defn- bound-text
  "A schema's declared `:min`/`:max` properties as prose, or nil when it
  declares neither. Malli reads both as the value itself for numbers and as
  the count for strings and collections (`malli.core/-min-max-pred`)."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{lower :min upper :max}]
  (cond (and (some? lower) (= lower upper)) (str "exactly " lower)
        (and (some? lower) (some? upper)) (str "from " lower " to " upper)
        (some? lower) (str "at least " lower)
        (some? upper) (str "at most " upper)))

(defn- schema-expectation
  "Describe composed schemas from their children, not Malli's unknown fallback.
  A declared bound is part of the expectation: `[:int {:min 1}]` refusing 0
  read \"expected an integer, got an integer 0\"."
  {:malli/schema [:=> [:cat :map]
                  :string]}
  [problem]
  (let [check (m/deref-all (:schema problem))
        problem (-> problem (assoc :schema check) (dissoc :type))
        declared-message (me/error-message problem {:unknown false})
        children #(map (fn [child]
                         (schema-expectation (assoc problem :schema child)))
                       (m/children check))
        bound (bound-text (or (m/properties check) {}))
        bounded (fn [noun measure]
                  (cond (nil? bound) noun
                        measure (str noun " of " measure " " bound)
                        :else (str noun " " bound)))]
    (case (m/type check)
      :vector (bounded "a vector" "count") :sequential (bounded "a sequence" "count")
      :map (bounded "a map" "count") :set (bounded "a set" "count")
      :string (bounded "a string" "length") :int (bounded "an integer" nil)
      :double (bounded "a double" nil) :boolean "a boolean" :keyword "a keyword"
      :qualified-keyword "a namespaced keyword" :symbol "a symbol"
      :qualified-symbol "a namespaced symbol" :nil "nil" :uuid "a UUID"
      :tuple (str "a tuple with " (count (m/children check)) " entries")
      :enum (str "either " (str/join " or " (map pr-str (m/children check))))
      := (str "the value " (pr-str (first (m/children check))))
      :and (or declared-message (str/join " and " (children)))
      :or (or declared-message (str/join " or " (children)))
      :fn (or declared-message "the declared predicate")
      (let [schema-type (m/type check)]
        (str "a value satisfying " (or (:description (m/properties check))
                                       (if (symbol? schema-type) schema-type (str "the declared " (name schema-type) " schema"))))))))

(defn- collection-member-problem
  {:malli/schema [:=> [:cat :map]
                  [:or :nil :map]]}
  [problem]
  (let [schema (m/deref-all (:schema problem))
        schema-type (m/type schema)
        child (when (#{:set :vector :sequential} schema-type)
                (first (m/children schema)))
        value (:value problem)
        matches-kind? (case schema-type
                        :sequential (sequential? value)
                        :vector (vector? value)
                        :set (set? value)
                        false)]
    (when (and child matches-kind? (coll? value))
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
        expected-form (if (#{:= :enum} schema-type)
                        (into [schema-type] (m/children (m/deref-all (:schema problem))))
                        (m/form (:schema problem)))
        described-problem (cond-> problem missing? (dissoc :type))
        message (or (me/error-message described-problem {:unknown false})
                    "the declared schema")
        expected (schema-expectation described-problem)
        missing-key (last path)
        ;; the same name the caller's map holds under another namespace
        held-key (when (and missing? (qualified-keyword? missing-key) (map? parent))
                   (some #(when (and (qualified-keyword? %) (= (name %) (name missing-key))) %)
                         (keys parent)))]
    (cond-> {:seon.error/path (vec path)
     :seon.error/argument argument
     :seon.error/expected expected-form
     :seon.error/expected-description
     (cond
       missing? (str "the required key " (pr-str missing-key) " with " expected)
       member? (str "a collection member satisfying " expected)
       :else expected)
     :seon.error/offending (if (and missing? (map? parent)) parent (:value problem))
     :seon.error/actual-description
     (cond
       missing? (str "a map missing " (pr-str missing-key))
       member? (str "a collection member that is " (value-description (:value problem)))
       :else (value-description (:value problem)))
     :seon.error/fix
     (cond
       missing? (str "Supply " (pr-str missing-key) " with " expected "."
                     (when held-key
                       (str " The map holds " held-key " with the same name under another namespace.")))
       (and (= :vector schema-type) (sequential? (:value problem))
            (not (vector? (:value problem))))
       "Convert the sequence with vec before calling the function."
       (= :fn schema-type) message
       checked-output "Return a value satisfying the declared result contract for the shown input."
       :else (str "Supply " expected
                  (when (seq path) (str " at " (pr-str (vec path)))) "."))}
      check (assoc :seon.error/input (first (:smallest check))))))

(defn- problem-parts
  "The refusal sentence's parts in order: prose strings, and the expected and
  offending values as the caller rendered them (text for AI, hiccup nodes for
  HTML), nil omitting that part. `problem-sentence` joins them as text; the
  HTML face places the same parts as nodes, so a value's cut stays structure."
  {:malli/schema
   [:=> [:cat [:or :symbol :string] :map :seon.render/hiccup :seon.render/hiccup]
    [:vector :seon.render/hiccup]]}
  [operation
   {:seon.error/keys [path argument expected-description actual-description fix]}
   expected offending]
  ;; An empty path is the whole argument, which `argument` already names:
  ;; " at []" told the reader nothing (instrumentation-opus, 2026-09-27).
  (cond-> [(str operation " refused " argument
                (when (seq path) (str " at " (pr-str path)))
                ": expected " expected-description)]
    (some? expected) (conj " (" expected ")")
    true (conj (str ", got " actual-description))
    (some? offending) (conj " " offending)
    true (conj (str ". Fix: " fix))))

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
  [operation problem expected-text offending-text]
  (apply str (problem-parts operation problem expected-text offending-text)))

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

(defn- notice-ai-prose
  "Describe recorded evidence and why this recipient receives its first notification."
  {:malli/schema [:=> [:cat :seon.error/notice]
                  :string]}
  [notice]
  (let [{:seon.error/keys [id message signature]} (:seon.error/fact notice)]
    (str message " Inspect error " id ". Signature: " signature ".")))

(defn ai-prose
  "`:seon.render/ai` — AI-attempt evidence or a legacy error notice.

  Class schemas for every `:seon.ai/*` attempt failure declare this producer.
  The notice arm remains through slice 1 so existing committed facts retain
  their current face until their emission sweep."
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
           "No request was transmitted."

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
(defn- observation?
  "Whether `candidate` is a recorded error observation: a map naming when, in
  which layer and at which operation it happened."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                                   :seon.schema.admission/reason "A total predicate accepts any read result, an entity or a refusal, and returns false for what is not an observation."
                                   :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [candidate]
  (and (map? candidate)
       (inst? (:seon.error/at candidate))
       (qualified-keyword? (:seon.error/layer candidate))
       (qualified-symbol? (:seon.error/operation candidate))))

(defn- fact-tempid
  {:malli/schema [:=> [:cat :seon.error/id]
                  :string]}
  [id]
  (str "seon.error/fact-" id))

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
  "The steward of the namespace of the function that produced a fault (its
  `:seon.error/producer`), else of the function that refused it."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] [:or :nil :seon.agent/id :seon.db/error-result]]}
  [database fact]
  (when-let [function (or (:seon.error/producer fact) (:seon.instrument/fn fact))]
    (db/q '[:find ?id . :in $ ?symbol
            :where [?function :seon.fn/sym ?symbol]
                   [?function :seon.fn/ns ?namespace]
                   [?namespace :seon.ns/steward ?agent]
                   [?agent :seon.agent/id ?id]]
          database function)))

(defn- proof
  "Whether task `handle`'s proof holds in `database`: at least one test names its
  symbol (`:seon.task/tasks`) and each such test's latest stored run is green (passes,
  no failure or error, terminated) and completed after the transaction that
  wrote `producer`'s current definition digest. A task no test names has no
  proof. The answer is derived, never stored."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle :seon.fn/sym]
                  [:or :boolean :seon.db/error-result]]}
  [database handle producer]
  (let [fixed (db/q '[:find (max ?tx) . :in $ ?producer
                      :where [?f :seon.fn/sym ?producer] [?f :seon.program/definition-digest _ ?tx]]
                    database producer)
        runs (db/q '[:find ?test ?done ?pass ?fail ?error :in $ ?handle
                     :where [?task :seon.task/id ?handle] [?task :seon.task/sym ?s]
                            [?t :seon.task/tasks ?s] [?t :seon.test/sym ?test]
                            [?m :seon.test.member/symbol ?test]
                            [?m :seon.test.member/completed-tx ?done]
                            [?m :seon.test.member/terminated-tx]
                            [?m :seon.test.member/pass-count ?pass]
                            [?m :seon.test.member/fail-count ?fail]
                            [?m :seon.test.member/error-count ?error]]
                   database handle)
        named (db/q '[:find [?test ...] :in $ ?handle
                      :where [?task :seon.task/id ?handle] [?task :seon.task/sym ?s]
                             [?t :seon.task/tasks ?s] [?t :seon.test/sym ?test]]
                    database handle)
        latest (when (set? runs) (into {} (map (juxt first identity)) (sort-by second runs)))]
    (cond
      (map? fixed) fixed
      (map? runs) runs
      (map? named) named
      (or (empty? named) (nil? fixed)) false
      :else (every? (fn [test]
                      (let [[_ done pass fail error] (get latest test)]
                        (and done (> done fixed) (pos? pass) (zero? fail) (zero? error))))
                    named))))

(defn resolution
  "Why the fault `signature` names is resolved, or nil while it is open.

  By its producer (`:seon.error/producer`, the blame rule): when the
  producer's current definition digest differs from the one its latest
  occurrence ran (`:seon.error/producer-digest`), and, when a task answers
  the fault (`:seon.task/faults`), that task's proof holds: a test naming it
  has a green stored run completed after the change (`:seon.task/proof`). A
  producer with no program entity any more (deleted) resolves it, task or
  not. Otherwise by a `:seon.error/resolved-tx`
  (`seon.problems/resolve!`) at or after its latest occurrence. A fault with
  no producer resolves only so. A recurrence reopens it.

  ## Costs

  - Three indexed queries and two pulls, plus three queries per answering
    task over its tests' members and the producer's digest datoms."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/signature]
                  [:or :nil :seon.error/resolution :seon.db/error-result]]}
  [database signature]
  (let [latest (db/q '[:find (max ?tx) . :in $ ?signature
                       :where [?error :seon.error/signature ?signature]
                              [?error :seon.error/occurrences ?occurrence]
                              [?occurrence :seon.error.occurrence/last-at _ ?tx]]
                     database signature)
        fault (db/pull database [:seon.error/producer
                                 {:seon.error/occurrences [:seon.error.occurrence/last-at :seon.error/producer-digest]}
                                 {:seon.task/_faults [:seon.task/id]}]
                       [:seon.error/signature signature])
        producer (:seon.error/producer fault)
        then (:seon.error/producer-digest (last (sort-by #(inst-ms (:seon.error.occurrence/last-at %))
                                                         (:seon.error/occurrences fault))))
        now (when producer
              (db/pull database [:db/id :seon.program/definition-digest] [:seon.fn/sym producer]))
        tasks (keep :seon.task/id (:seon.task/_faults fault))
        proven (when (and then (not= then (:seon.program/definition-digest now)))
                 (reduce (fn [_ handle] (let [held (proof database handle producer)]
                                          (cond (map? held) (reduced held) held (reduced handle))))
                         nil tasks))
        resolved (when (int? latest)
                   (db/q '[:find (max ?resolution) . :in $ ?signature
                           :where [?error :seon.error/signature ?signature]
                                  [?error :seon.error/resolved-tx ?resolution]]
                         database signature))
        changed (cond-> {:seon.error/producer producer}
                  then (assoc :seon.error/digest-then then)
                  (:seon.program/definition-digest now)
                  (assoc :seon.error/digest-now (:seon.program/definition-digest now)))]
    (cond
      (some #(and (map? %) (:seon.error/at %)) [latest fault now proven resolved])
      (some #(when (and (map? %) (:seon.error/at %)) %) [latest fault now proven resolved])
      (and producer (nil? (:db/id now)))
      (assoc changed :seon.error/resolved-by :seon.program/definition-digest)
      (string? proven) (assoc changed :seon.error/resolved-by :seon.task/proof :seon.task/id proven)
      (and then (not= then (:seon.program/definition-digest now)) (empty? tasks))
      (assoc changed :seon.error/resolved-by :seon.program/definition-digest)
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
      resolved (or (:seon.error/digest-now resolved) (:seon.error/resolved-tx resolved)
                   (:seon.error/digest-then resolved))
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
  "One notification per opening. Its content is the error's value, the flat
  data and references `value` keeps, printed readably; its subject is the
  signature, so the recipient's context reaches the whole record."
  {:malli/schema [:=> [:cat :seon.error/fact :seon.agent/id :seon.agent/id
                       [:or [:int {:min 0}] :seon.program/definition-digest]]
                  :map]}
  [fact sender recipient opening]
  ;; one message per opening: a re-opened signature is a new message, so it wakes again
  {:db/id (str "seon.error/notified-" recipient)
   :seon.message/id (notification-id (:seon.error/signature fact) recipient opening)
   :seon.message/to [:seon.agent/id recipient]
   :seon.message/from [:seon.agent/id sender]
   :seon.message/content (render.value/core-text (value fact))
   :seon.message/about (:seon.error/signature fact)})


(defn- occurrence-slot
  "The occurrence a failure lands on: the first of the request's slots
  (`recording`: at most `:seon.config.error/recurrence-limit` per signature
  and producer digest) not yet written, else the last, whose noHistory count a
  repeat bumps. An obligation names its one occurrence."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] [:or :seon.error.occurrence/id :seon.db/error-result]]}
  [database {id :seon.error.occurrence/id slots :seon.error.occurrence/slots}]
  (loop [[slot & more] slots]
    (if (nil? slot)
      (or (peek slots) id)
      (let [held (db/pull database [:db/id] [:seon.error.occurrence/id slot])]
        (cond (:seon.error/at held) held
              (nil? held) slot
              :else (recur more))))))

(defn repeat-of
  "The occurrence `recording` would only count, read on `database` (the
  target branch's value) by the writer's own rule (`commit-call`): its last
  capped slot is written and the signature is open, so the write would add
  one to a noHistory count and nothing else. Nil when it would write more (a
  free slot, a reopening, an obligation), or the read's refusal.

  ## Costs

  - One pull per slot and the signature's `opening` (its `resolution`
    queries); failure path only."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/recording]
                  [:or :nil :seon.error.occurrence/id :seon.db/error-result]]}
  [database recording]
  (let [[_ _ request] (some #(when (and (vector? %) (= :db.fn/call (first %))) %) (:seon.db/tx-data recording))
        slots (:seon.error.occurrence/slots request)]
    (when (and (seq slots) (not (:seon.error/obligation request)))
      (let [slot (occurrence-slot database request)
            held (when (= slot (peek slots)) (db/pull database [:db/id] [:seon.error.occurrence/id slot]))]
        (cond
          (map? slot) slot
          (:seon.error/at held) held
          (:db/id held) (let [opened (opening database (:seon.error/signature (:seon.error/fact request)))]
                          (cond (map? opened) opened
                                (nil? opened) slot)))))))

(defn bump-call
  "Add `:seon.error.occurrence/count` folded repeats to the stored occurrence
  at the writer, keeping the later last instant: how repeats a recorder held
  in memory (`seon.fault/folded-tx`) ride a commit that happens anyway. An
  occurrence no longer stored takes nothing."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.error.occurrence/id :seon.error.occurrence/id]
                        [:seon.error.occurrence/count :seon.error.occurrence/count]
                        [:seon.error.occurrence/last-at :seon.error.occurrence/last-at]]]
                  [:or :seon.store/transaction-data :seon.db/error-result]]}
  [database {id :seon.error.occurrence/id n :seon.error.occurrence/count at :seon.error.occurrence/last-at}]
  (let [old (db/pull database [:seon.error.occurrence/count :seon.error.occurrence/last-at] [:seon.error.occurrence/id id])]
    (cond
      (:seon.error/at old) old
      (nil? (:seon.error.occurrence/count old)) []
      :else [{:seon.error.occurrence/id id
              :seon.error.occurrence/count (+ (:seon.error.occurrence/count old) n)
              :seon.error.occurrence/last-at (let [stored (:seon.error.occurrence/last-at old)]
                                               (if (and stored (.after ^java.util.Date stored at)) stored at))}])))

(defn commit-call
  "Upsert one error occurrence and its bounded notifications at the writer.

  A repeat landing on an occurrence already written, in the same opening,
  bumps its noHistory count and last instant and writes nothing else: the
  first occurrences of each producer digest keep their world, the rest are a
  count."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:and :seon.error/commit-tx-request
                        [:map [:seon.error/fact :seon.error/fact]
                         [:seon.error.occurrence/id :seon.error.occurrence/id]
                         [:seon.error.occurrence/slots {:optional true} [:vector :seon.error.occurrence/id]]]]]
                  [:or :seon.store/transaction-data :seon.db/error-result]]}
  [database request]
  (let [fact (:seon.error/fact request)
        signature (:seon.error/signature fact)
        occurrence-id (occurrence-slot database request)
        occurrence-ref [:seon.error.occurrence/id occurrence-id]
        old (if (string? occurrence-id) (db/pull database '[*] occurrence-ref) occurrence-id)
        world (:seon.error/world (:seon.error/source request))
        ;; the messages the writer selected for the signature's current opening
        notified (db/q '[:find [?message ...] :in $ ?signature
                         :where [?error :seon.error/signature ?signature]
                                [?error :seon.error/notified ?message]]
                       database signature)
        agent-id (or (second (:seon.error/agent fact)) (:seon.agent/id world))
        turn-id (or (second (:seon.error/run fact)) (:seon.turn/id world))
        escalate-to (:seon.config.error/escalate-to request)
        agent-present (when agent-id (entity-exists? database :seon.agent/id agent-id))
        turn-present (when turn-id (entity-exists? database :seon.turn/id turn-id))
        escalation-present (when escalate-to (entity-exists? database :seon.agent/id escalate-to))
        steward-id (steward database fact)
        steward-present (when (string? steward-id) (entity-exists? database :seon.agent/id steward-id))
        opened (opening database signature)
        read-refusal (some #(when (observation? %) %)
                           [(when-not (:seon.error.occurrence/id old) old)
                            (when (map? notified) notified)
                            agent-present turn-present escalation-present
                            steward-id steward-present opened])]
    (cond
      read-refusal read-refusal
      ;; A REPEAT IS A COUNT. Re-asserting the same diagnostics wrote 18
      ;; history datoms per repeat (2026-09-28); the stored first
      ;; occurrences already describe it.
      (and (:seon.error.occurrence/id old) (nil? opened) (not (:seon.error/obligation request)))
      [{:seon.error.occurrence/id occurrence-id
        :seon.error.occurrence/count (inc (:seon.error.occurrence/count old))
        :seon.error.occurrence/last-at (:seon.error/at fact)}]
      :else
      (let [projection (db/carried-projection database)
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
        evidence (select-keys fact [:seon.error/process :seon.error/proc :seon.error/op
                                   :seon.error/result-id :seon.error/shown
                                   :seon.error/shown-blob :seon.error/shown-size
                                   :seon.error/cid :seon.error/throwable-class
                                   :seon.error/data-edn :seon.error/data-size :seon.error/capped?
                                   :seon.instrument/fn :seon.instrument/arm
                                   :seon.instrument/expected :seon.instrument/args
                                   :seon.instrument/actual
                                   :seon.instrument/actual-size])
        ;; the version that ran: the armed Var's digest the producer stated,
        ;; else (an interpreted definition) the recording branch's entity
        producer-digest (or (:seon.error/producer-digest (:seon.error/source request))
                            (when-let [producer (:seon.error/producer fact)]
                              (:seon.program/definition-digest
                               (db/pull database [:seon.program/definition-digest] [:seon.fn/sym producer]))))
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
                     ;; the evaluation and task by identity, joined at read time:
                     ;; a failing evaluation settles after its fault
                     (:seon.cluster.eval/id world)
                     (assoc :seon.error.occurrence/evaluation (:seon.cluster.eval/id world))
                     (:seon.task/id world) (assoc :seon.error.occurrence/task (:seon.task/id world))
                     (string? producer-digest) (assoc :seon.error/producer-digest producer-digest)
                     ;; the world by reference: the failing request's effect and
                     ;; the occurrence this one follows
                     (:seon.effect/id request)
                     (assoc :seon.error.occurrence/effect [:seon.effect/id (:seon.effect/id request)])
                     (:seon.error/related-occurrence request)
                     (assoc :seon.error.occurrence/related
                            [:seon.error.occurrence/id (:seon.error/related-occurrence request)]))
        error-entity (assoc (select-keys fact [:seon.error/signature :seon.error/id :seon.error/declared-schema
                                          :seon.error/layer :seon.error/operation :seon.instrument/fn :seon.error/frame
                                          :seon.error/exception-class :seon.error/producer])
                            :db/id (fact-tempid (:seon.error/id request))
                            :seon.error/occurrences #{occurrence})
        ;; WHO ACTS ON A RECORDED FAULT is the writer's reading of held
        ;; relations, never of the evidence's shape (a Throwable's presence
        ;; says how it escaped, not who fixes it): the steward of its function,
        ;; else the escalation owner the producer names. A producer recording
        ;; an agent's own mistake, already returned to that agent, names none.
        ;; The agent a fault happened to reads it through `for-agent`, so it
        ;; is never a recipient: a message is a wake, and mailing the failing
        ;; agent woke the same unfixed cause (2026-08-08: nine paid calls in
        ;; twenty minutes when escalate-to named the only agent).
        recipients (when (some? opened)
                     (disj (cond steward-present #{steward-id}
                                 escalation-present #{escalate-to}
                                 :else #{})
                           agent-id))
        messages (mapv #(message-tx fact (or agent-id steward-id %) % opened) recipients)]
    (if (and (:seon.error/obligation request) (:seon.error.occurrence/id old))
      ;; ONE OBLIGATION, ONE DISPOSITION: this occurrence already answers it
      [{:seon.db.process/id process}]
      (into (cond-> (into [{:seon.db.process/id process}] replacements)
              ;; a new opening replaces the selection; a recurrence keeps it
              (some? opened) (into (map #(vector :db/retract [:seon.error/signature signature]
                                                  :seon.error/notified %))
                                   notified)
              true (conj (cond-> error-entity
                           (seq messages) (assoc :seon.error/notified (set (map :db/id messages))))))
            messages))))))

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
  exclusively to commit-call's mid-transaction database. The producer
  declares the schema; the positional form carries it as its third argument,
  before the source observation.

  ## Edge cases

  - The positional form is total over a missing declaration: nil records the
    source as `:seon.error/normalization-error`, so the fault is kept and its
    undeclared producer is visible, never refused and lost."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.error/commit-tx-request] [:or :seon.error/recording :seon.db/error-result :seon.error/base]]
    [:=> [:cat :map :seon.db/database-value [:maybe :seon.error/declared-schema] :seon.error/source :inst :map] [:or :seon.error/recording :seon.db/error-result :seon.error/base]]]}
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
         read-refusal (when (and acquiring? (observation? acquired)) acquired)]
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
         request (dissoc request :seon.db/connection)
         ;; a fact the caller supplied was prepared, and its blobs staged, by that caller
         fact (or (:seon.error/fact request) (:seon.error/fact prepared))
         staged (if (:seon.error/fact request) [] (:seon.blob/staged-writes prepared))
         signature (:seon.error/signature fact)
         agent-id (second (:seon.error/agent fact))
         origin (:seon.error.occurrence/branch request)
         obligation (:seon.error/obligation request)
         ;; OCCURRENCES ARE BOUNDED: at most `recurrence-limit` per signature
         ;; and producer digest, each keeping its world; a further repeat is
         ;; the last one's count (`commit-call`). An obligation is one
         ;; identity across turns and processes.
         identity-of (fn [members]
                       (id/id (into (sorted-map)
                                    (cond-> (assoc members :seon.error/signature signature)
                                      ;; a copy from another branch never merges with this branch's own
                                      (and origin (not= origin (get-in database [:config :branch])))
                                      (assoc :seon.store/branch origin)))))
         slots (if obligation
                 [(identity-of {:seon.agent/id agent-id :seon.error/obligation obligation})]
                 (mapv #(identity-of (cond-> {:seon.error.occurrence/slot %}
                                       (:seon.error/producer-digest (:seon.error/source request))
                                       (assoc :seon.error/producer-digest
                                              (:seon.error/producer-digest (:seon.error/source request)))))
                       (range (max 1 (long (:seon.config.error/recurrence-limit request 1))))))
         occurrence-id (first slots)
         entities [{:db/id (fact-tempid (:seon.error/id request))
                :seon.error/id signature :seon.error/signature signature
                :seon.error/declared-schema (:seon.error/declared-schema fact)
                :seon.error/layer (:seon.error/layer fact)
                :seon.error/operation (:seon.error/operation fact)}]
         ;; the writer's call holds data and references, never a world: the
         ;; projection is the writer database's own (`db/carried-projection`),
         ;; and a refused write copies this call into its refusal
         tx (conj entities [:db.fn/call #'commit-call
                        (-> (dissoc request :seon.schema/projection)
                            (assoc :seon.error/fact fact :seon.error.occurrence/id occurrence-id
                                   :seon.error.occurrence/slots slots))])]
     {:seon.error/fact fact
      :seon.error/ref [:seon.error/signature signature]
      :seon.error.occurrence/ref [:seon.error.occurrence/id occurrence-id]
      ;; the declared schema rides as the value's `:seon.error/type`, the
      ;; key render selection reads (`seon.render`'s declared stage)
      :seon.error/value (assoc (merge (value fact)
                                      (distinguishing-members (:seon.schema/projection request)
                                                              (:seon.error/declared-schema fact)
                                                              (:seon.error/source request)))
                               :seon.error/type (:seon.error/declared-schema fact))
      :seon.blob/staged-writes staged
      :seon.db/tx-data tx}))))
  ([cluster database declared-schema source at attribution]
   (recording database
              (merge (select-keys cluster [:seon.sci.admit/caps :seon.config.error/recurrence-limit
                                          :seon.config.error/max-evidence-bytes :seon.config.error/escalate-to])
                     (select-keys cluster [:seon.db/connection])
                     ;; RECORDING IS TOTAL. A value whose producer declared no
                     ;; schema (it carries no `:seon.error/type`, or a fixture built it by
                     ;; hand) is recorded as normalized escaped data, the
                     ;; declared schema for exactly that case, never refused:
                     ;; a refused recording loses the fault.
                     {:seon.error/source source
                      :seon.error/declared-schema (or declared-schema :seon.error/normalization-error)
                      :seon.error/id (id/id)
                      :seon.error/at at :seon.error/process (:seon.db.process/id cluster)
                      :seon.error/basis-t (db/basis-t database)}
                     attribution))))

(defn occurred-since
  "The errors written on `database`'s branch after basis `since`, the window
  (since, basis of `database`], newest first: per signature its recorded
  observation as `latest-fact` reads it (flat members, stored evidence and
  references), with `:seon.error.occurrence/branch` the branches of the
  occurrences this window touched (another branch marks a core fault's
  cluster-branch copy) and `:seon.error/resolution` when it is resolved.
  A resolved error stays in its window: resolution is not delivery.
  `:seon.error/occurrence-count` is cumulative, never a count since `since`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/basis-t]
                  [:or [:vector [:map [:seon.error/signature :seon.error/signature]
                                 [:seon.error/operation :seon.error/operation]
                                 [:seon.error.occurrence/message :seon.error.occurrence/message]
                                 [:seon.error.occurrence/count :seon.error.occurrence/count]
                                 [:seon.error.occurrence/branch {:optional true} [:set :seon.store/branch]]
                                 [:seon.error/resolution {:optional true} :seon.error/resolution]]]
                   :seon.db/error-result]]}
  [database since]
  (let [entities (db/q '[:find ?signature ?tx ?branch
                     :in $ ?since
                     :where [?occurrence :seon.error.occurrence/last-at _ ?tx]
                            [(> ?tx ?since)]
                            [?error :seon.error/occurrences ?occurrence]
                            [?error :seon.error/signature ?signature]
                            [(get-else $ ?occurrence :seon.error.occurrence/branch false) ?branch]]
                   database since)
        selector (when-not (map? entities)
                   (observation-selector (db/carried-projection database)))]
    (if (map? entities)
      entities
      (reduce (fn [found [signature group]]
                (let [error (db/pull database selector [:seon.error/signature signature])
                      resolved (resolution database signature)
                      fact (when (map? error) (latest-fact error))]
                  (if-some [refusal (some #(when (db/read-refusal? %) %) [error resolved fact])]
                    (reduced refusal)
                    (conj found (cond-> (assoc fact :seon.error.occurrence/branch
                                                       (into #{} (comp (map peek) (filter keyword?)) group))
                                  resolved (assoc :seon.error/resolution resolved))))))
              []
              (sort-by (fn [[_ group]] (- (reduce max (map second group))))
                       (group-by first entities))))))

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
        ;; The stored entity, when this database holds one; an error it never
        ;; stored (a refusal returned before, or instead of, its recording)
        ;; is shown as the value itself.
        ;; A held observation is rendered as held; only a bare identity
        ;; (a signature without its occurrences or message) is read.
        value (or (when (and database (:seon.error/signature value)
                             (not (find value :seon.error/occurrences))
                             (not (find value :seon.error/message)))
                    (db/pull database
                             (observation-selector (or (db/carried-projection database)
                                                       (:seon.schema/projection unit)
                                                       (schema/projection-from-database database)))
                             [:seon.error/signature (:seon.error/signature value)]))
                  value)]
    (if (map? value) (latest-fact value) value)))





(defn declared-output-validators
  "Compile the named error alternatives of one declared arity.
  This inspects the supplied contract, never an error value or the registry population.

  ## Edge cases

  - A function with no contract (`authored` nil) declares no alternatives: `[]`.
    Compiling nil threw `::m/invalid-schema`, which `seon.schedule/invoke-handler`
    caught as the handler's own failure, so every uncontracted handler's result
    was recorded as a failure of `seon.schedule/invoke-handler`."
  {:malli/schema [:=> [:cat :seon.schema/projection [:maybe :seon.schema/value] [:int {:min 0}]]
                  [:vector [:tuple :qualified-keyword :seon.schema/compiled-validator]]]}
  [projection authored argument-count]
  (if (nil? authored)
    []
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
               (m/-function-schema-arities contract)))))))

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

;;; THE REPLY: what an agent needs to fix the failure from this reply alone
;;; (owner 2026-09-27: "really good and helpful errors pulling in more
;;; relevant context"). One selection, `reply-value`, shown by both consumers:
;;; the floor prints it for AI (a thrown failure's `:error`, a returned error
;;; through `render-ai`), `render-html` lays the same keys out as hiccup.

(defn- leaf-expected
  "The schema at `path` within a problem's expectation, when Malli finds one
  there (`malli.util/get-in`): a problem whose expectation is the argument's
  whole schema (an `:or` of maps) names its failing place by path. Else the
  expectation itself."
  {:malli/schema [:=> [:cat [:maybe :map] :seon.schema/value :seon.render.data/path] :seon.schema/value]}
  [options expected path]
  (or (when (seq path)
        (try (some-> (mu/get-in (m/deref-all (m/schema expected options)) path) m/form)
             (catch clojure.lang.ExceptionInfo _ nil)))
      expected))

(def ^:private shown-properties
  "The schema properties a reader acts on; storage and registry properties
  (`:seon.db/identity`, `:seon.program/row-schema`) are not shown."
  #{:description :min :max :error/message})

(defn- expected-shown
  "An expected schema form with only the properties a reader acts on."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [form]
  (if (and (vector? form) (map? (second form)))
    (let [properties (select-keys (second form) shown-properties)]
      (into (if (seq properties) [(first form) properties] [(first form)]) (drop 2 form)))
    form))

(defn- example
  "One value satisfying `expected`, labelled in the reply as an example and
  never as the caller's intended value: `malli.generator/generate` at size 1,
  seed 0. Nil when Malli cannot compile or generate it (both are ex-info:
  `::m/invalid-schema`, `::mg/no-generator`, such-that exhaustion)."
  {:malli/schema [:=> [:cat [:maybe :map] :seon.schema/value] [:maybe :seon.error/source]]}
  [options expected]
  (try (mg/generate (m/schema expected options) {:size 1 :seed 0})
       (catch clojure.lang.ExceptionInfo _ nil)))

(defn- problem-shown
  "One problem as the reply shows it: its path, the expectation at that
  path (a named one read through to its form), the value there, one
  generated example of the expectation, and for a result refusal the input
  and the result contract."
  {:malli/schema [:=> [:cat [:maybe :map] :map] :map]}
  [options {:seon.error/keys [path expected] :as problem}]
  (let [leaf (when (some? expected) (leaf-expected options expected path))
        shown (when (some? leaf)
                (expected-shown
                 (if (qualified-keyword? leaf)
                   (try (m/form (m/deref-all (m/schema leaf options)))
                        (catch clojure.lang.ExceptionInfo _ leaf))
                   leaf)))
        generated (when (some? leaf) (example options leaf))]
    (cond-> (select-keys problem [:seon.error/path :seon.error/offending :seon.error/input
                                   :seon.error/result-contract])
      (some? shown) (assoc :seon.error/expected shown)
      (some? generated) (assoc :seon.error/example generated))))

(defn- missing-symbol
  "The name the failure could not find, as its producer declared it.

  SCI's analysis refusal carries it as `:sci.impl/symbol` in the cause's
  ex-data; a program read as `:seon.program/not-found`."
  {:malli/schema [:=> [:cat :map] [:maybe :symbol]]}
  [error]
  (let [found (or (some #(get-in % [:seon.error/data :sci.impl/symbol]) (:seon.error/chain error))
                  (:seon.program/not-found error))]
    (when (symbol? found) found)))

(def ^:private names-shown
  "Declared names shown beside a name not found: the nearest by spelling."
  8)

(defn- names-in
  "The names `namespace-name` declares nearest to `missing` by spelling, at
  most eight: its public names when `missing` is qualified (another
  namespace's callers see only those), all of them otherwise. A refused
  read is returned as the refusal it is, never as names or as none."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol :symbol]
                  [:or [:vector :symbol] :seon.db/error-result]]}
  [database namespace-name missing]
  (let [found (db/q '[:find ?sym ?private :in $ ?ns
                      :where [?n :seon.ns/name ?ns] [?f :seon.fn/ns ?n] [?f :seon.fn/sym ?sym]
                      [(get-else $ ?f :seon.fn/private? false) ?private]]
                    database namespace-name)
        wanted (name missing)]
    (if (db/read-refusal? found)
      found
      (into []
            (comp (remove (fn [[_ private?]] (and private? (qualified-symbol? missing))))
                  (map (comp symbol name first))
                  (take names-shown))
            (sort-by (fn [[sym]] [(me/levenshtein wanted (name sym)) (name sym)]) found)))))

(defn- call-lines
  "For each program function named (at most three), the lines of its stored
  source that name `subject`, each headed by its line number within the
  definition: where a caller must change before `subject` can go. A
  refused read is that function's value, never an omission."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :qualified-symbol] :qualified-symbol]
                  [:map-of :qualified-symbol [:or [:vector :string] :seon.db/error-result]]]}
  [database symbols subject]
  (let [wanted (name subject)]
    (into {}
          (keep (fn [sym]
                  (let [source (db/q '[:find ?source . :in $ ?sym
                                       :where [?f :seon.fn/sym ?sym] [?f :seon.fn/source ?source]]
                                     database sym)]
                    (cond
                      (db/read-refusal? source) [sym source]
                      (string? source)
                      [sym (into [] (comp (map-indexed (fn [i line] [(inc i) line]))
                                          (filter (fn [[_ line]] (str/includes? line wanted)))
                                          (take 3)
                                          (map (fn [[n line]] (str n ": " (str/trim line)))))
                                 (str/split-lines source))]))))
          (take 3 (sort symbols)))))

(defn- custody-database
  "The database in custody on this thread, or nil outside custody.

  Custody is an evaluation's read basis or its connection's value."
  {:malli/schema [:=> [:cat] [:maybe :seon.db/database-value]]}
  []
  (when (or db/*read-database* db/*conn*)
    (let [database (db/db)]
      (when (db/database-value? database) database))))

(defn- schema-keys
  "The map keys `schema` declares, through its `:and`/`:merge` children and
  registered references."
  {:malli/schema [:=> [:cat :seon.schema/value] [:sequential :qualified-keyword]]}
  [schema]
  (let [schema (m/deref-all schema)]
    (case (m/type schema)
      :map (mapv first (m/entries schema))
      (:and :merge) (into [] (mapcat schema-keys) (m/children schema))
      [])))

(defn- variant-members
  "The members `error`'s variant (`:seon.error/type`) declares beyond
  `:seon.error/base`, as `error` holds them: what that kind of failure says
  besides the base's message, fix and problems. Empty when the type is not
  registered in `options`' registry."
  {:malli/schema [:=> [:cat [:map [:registry :any]] :map] :map]}
  [options error]
  (let [variant (:seon.error/type error)]
    (if (and (qualified-keyword? variant) (mr/schema (mr/registry (:registry options)) variant))
      (select-keys error (remove (set (schema-keys (m/schema :seon.error/base options)))
                                 (schema-keys (m/schema variant options))))
      {})))

(defn reply-value
  "An error as the agent acts on it: one small map to fix the failure from.

  The whole error stays one read away through the evaluation's result
  handle. The keys, each present only when it has something to say:

  - `:seon.error/type`: the error's variant, when it names one.
  - `:seon.error/message`: the producer's message (its first cause link's).
  - `:seon.error/problems`: each failing path with the expected schema, the
    offending value there and one generated `:seon.error/example` of the
    expectation (an example, never a guess at the caller's intent).
  - `:seon.error/offending`, `:seon.error/expected`, `:seon.error/member`:
    for any other refusal, what was refused and what was expected (the
    referrers blocking a removal, the name not found).
  - `:seon.instrument/arglists` for an arity refusal (its `declared-arities`
    only when it has no arglists), and
    `:seon.instrument/caller`, the call's place in the agent's code.
  - `:seon.error/in-namespace`: when a name was not found, the names its
    namespace declares nearest to it by spelling, at most eight; absent
    when it declares none.
  - `:seon.error/sources`: where each function the refusal names (a
    removal's callers, at most three) mentions the blocked subject.
  - `:seon.error/chain`: for a thrown failure that is no structural refusal
    (no problems, nothing offending), every cause link's class, message and
    `ex-data` without frames, when there is more than the message to say; a
    link's message equal to the shown message is not repeated.
  - `:seon.sci.eval/stacktrace`: the agent's own frames.
  - every member the error's variant (`:seon.error/type`) declares beyond
    `:seon.error/base` and holds (`variant-members`), e.g. a write-back
    refusal's reason, path and conflict digests.

  The one-argument arity reads the database in custody, if any.

  ## Examples

  (reply-value database contract-refusal)

  For `(total-area [{:w 2 :h 3} {:w 1 :h \"4\"}])` against a vector of
  maps of non-negative integers, the one problem is path `[1 :scratch.fbr/h]`,
  expected `[:int {:min 0}]`, offending `\"4\"`, example `0`.

  ## Costs

  Unarmed, measured at default 2026-09-27 (tmp/feedback-renders/proof.clj):
  0-3 ms; 49 ms for a removal refusal reading its callers' sources on a
  fresh head."
  {:malli/schema [:function
                  [:=> [:cat :map] :seon.error/reply]
                  [:=> [:cat [:maybe :seon.db/database-value] :map] :seon.error/reply]]}
  ([error] (reply-value (custody-database) error))
  ([database error]
   (let [cause (first (:seon.error/chain error))
         problems (or (seq (:seon.error/problems error))
                      (let [nested (:seon.error/problems (:seon.error/data error))]
                        (when (sequential? nested) (seq nested))))
         operation (or (:seon.instrument/fn error) (:seon.error/operation error))
         ;; the producer's own message, never a sentence composed here
         message (or (when-not problems (:seon.error/message cause)) (:seon.error/message error))
         ;; the projection is read only for problems: expectations and examples
         options (when-let [projection (when (or problems (:seon.error/type error))
                                         (some-> database db/carried-projection))]
                   {:registry (:seon.schema.projection/registry projection)})
         missing (missing-symbol error)
         ;; a stored contract fault keeps its expected and received values
         ;; as bounded text under :seon.instrument/* (`normalize`)
         offending (when-not problems
                     (cond missing missing
                           (find error :seon.error/offending) (:seon.error/offending error)
                           :else (:seon.instrument/args error)))
         expected (if (find error :seon.error/expected)
                    (:seon.error/expected error)
                    (:seon.instrument/expected error))
         namespace-name (when missing
                          (or (some-> (namespace missing) symbol)
                              (let [frame (first (peek (:seon.sci.eval/stacktrace error)))]
                                (when (simple-symbol? frame) frame))))
         referenced (when (map? offending)
                      (into #{} (comp (mapcat #(if (coll? %) % [%])) (filter qualified-symbol?))
                            (vals offending)))
         class (:seon.error/exception-class error)
         member (or (:seon.error/member error) (:seon.error/member (:seon.error/data error)))
         ;; A thrown failure with no structural refusal says what went wrong
         ;; in its cause chain: each link's class, message and ex-data. The
         ;; agent's frames are shown once, as its stacktrace; a link whose
         ;; data IS the structural refusal is already shown above.
         chain (when-not (or problems (some? offending))
                 (let [links (mapv (fn [link]
                                     (cond-> (dissoc link :seon.error/frames)
                                       (let [data (:seon.error/data link)]
                                         (or (:seon.error/at data) (and (map? data) (empty? data))))
                                       (dissoc :seon.error/data)
                                       ;; the reply's message is already shown once
                                       (= message (:seon.error/message link)) (dissoc :seon.error/message)))
                                   (:seon.error/chain error))]
                   (when (or (< 1 (count links)) (some :seon.error/data links))
                     links)))
         arity? (contains? error :seon.instrument/declared-arities)
         ;; an empty name list is no key; a refused read is shown as it is
         in-namespace (when (and database namespace-name) (names-in database namespace-name missing))]
     (cond-> (if options (variant-members options error) {})
       (:seon.error/type error) (assoc :seon.error/type (:seon.error/type error))
       message (assoc :seon.error/message message)
       (and class (not= "clojure.lang.ExceptionInfo" (str class)))
       (assoc :seon.error/exception-class class)
       problems (assoc :seon.error/problems (into [] (comp (map #(problem-shown options %)) (distinct)) problems))
       (some? offending) (assoc :seon.error/offending offending)
       (and (not problems) (not missing) (some? expected))
       (assoc :seon.error/expected (expected-shown expected))
       (and (not problems) (some? member)) (assoc :seon.error/member member)
       (and (not problems) (string? (:seon.error/fix error)))
       (assoc :seon.error/fix (:seon.error/fix error))
       (and arity? (seq (:seon.instrument/arglists error)))
       (assoc :seon.instrument/arglists (:seon.instrument/arglists error))
       ;; the arglists already say which counts are declared
       (and arity? (empty? (:seon.instrument/arglists error)) (seq (:seon.instrument/declared-arities error)))
       (assoc :seon.instrument/declared-arities (:seon.instrument/declared-arities error))
       (string? (:seon.instrument/caller error))
       (assoc :seon.instrument/caller (:seon.instrument/caller error))
       (seq in-namespace)
       (assoc :seon.error/in-namespace {namespace-name in-namespace})
       (and database (seq referenced) (qualified-symbol? (:seon.program/blocked-subject error)))
       (assoc :seon.error/sources (call-lines database referenced (:seon.program/blocked-subject error)))
       chain (assoc :seon.error/chain chain)
       (:seon.sci.eval/stacktrace error)
       (assoc :seon.sci.eval/stacktrace (:seon.sci.eval/stacktrace error))))))

(defn- rendered-reply
  "The rendered error's reply: `reply-value` over its recorded source (a
  stored fact's data, else the value itself), with a stored fault's
  recurrence and shown offending value. Nil when the unit holds no error map."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.error/reply]]}
  [unit]
  (let [value (rendered-error-value unit)
        source (when (and (map? value) (:seon.error/data-edn value)) (fact-source value))
        database (:seon.db/db unit)]
    (when (map? value)
      (cond-> (reply-value (when (db/database-value? database) database)
                           (if (map? source) (merge value source) value))
        (:seon.error/occurrence-count value)
        (assoc :seon.error/occurrence-count (:seon.error/occurrence-count value))
        ;; a stored fault's offending value as it was shown when recorded
        (string? (:seon.error/shown value))
        (assoc :seon.error/shown (:seon.error/shown value))))))

(defn render-ai
  "An error's AI face: its reply (`reply-value`) as data, which the value
  printer prints. A reply holding only a message is that message."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or [:string {:min 1}] :seon.error/reply]]}
  [unit]
  (let [reply (rendered-reply unit)]
    (cond
      (empty? reply) "Error evidence is unavailable."
      (= [:seon.error/message] (keys reply)) (:seon.error/message reply)
      :else reply)))

(defn finding
  "The finding a stored fault is, as data: the one selection both faces of
  its signature render (health plan §4). What is wrong (the named error it
  satisfies, the producer at the digest its latest occurrence ran, the
  refusing function, the failed schema and path), how often and where
  (occurrence count, first and last instant, the agents and branches), the
  reproduction (its latest evaluation), whether it is resolved and which
  task answers it; beyond distance zero the producer's source and contract.
  Every dig is an operation offered as data, never run here.

  ## Examples

  (seon.error/finding (seon.db/db) \"69ab7a53…\" 1)

  ## Costs

  - One pull of the signature with its bounded occurrences, its resolution
    (`resolution`), and at distance one a pull of the producer's source."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/signature [:int {:min 0}]]
                  [:or :seon.error/finding :seon.db/error-result]]}
  [database fault-signature distance]
  (let [fault (db/pull database
                       [:seon.error/declared-schema :seon.error/operation :seon.error/producer :seon.instrument/fn
                        {:seon.task/_faults [:seon.task/id]}
                        {:seon.error/occurrences
                         [:seon.error.occurrence/count :seon.error.occurrence/first-at :seon.error.occurrence/last-at
                          :seon.error.occurrence/message :seon.error.occurrence/branch :seon.error.occurrence/evaluation
                          :seon.error/producer-digest :seon.error/caller :seon.error/expected-key
                          {:seon.error.occurrence/agent [:seon.agent/id]}
                          :seon.error/shown-blob
                          {:seon.error/location [{:seon.error.location/segments
                                                  [:seon.error.location.segment/ordinal
                                                   {:seon.error.location.segment/key [:seon.error.key/scalar]}]}]}]}]
                       [:seon.error/signature fault-signature])
        occurrences (sort-by #(- (inst-ms (:seon.error.occurrence/last-at %))) (:seon.error/occurrences fault))
        latest (first occurrences)
        producer (:seon.error/producer fault)
        task (some :seon.task/id (:seon.task/_faults fault))
        resolved (resolution database fault-signature)
        path (some->> (get-in latest [:seon.error/location :seon.error.location/segments])
                      (sort-by :seon.error.location.segment/ordinal)
                      (mapv #(get-in % [:seon.error.location.segment/key :seon.error.key/scalar])))
        definition (when (and producer (pos? distance))
                     (db/pull database [:seon.fn/source :seon.fn/spec] [:seon.fn/sym producer]))
        blob (:seon.error/shown-blob latest)
        evaluation (:seon.error.occurrence/evaluation latest)]
    (cond
      (some :seon.error/at [fault resolved definition]) (some #(when (:seon.error/at %) %) [fault resolved definition])
      (nil? latest) {:seon.error/signature fault-signature :seon.error.occurrence/count 0}
      :else
      (cond-> {:seon.error/signature fault-signature
               :seon.error/declared-schema (:seon.error/declared-schema fault)
               :seon.error/message (:seon.error.occurrence/message latest)
               :seon.error.occurrence/count (reduce + (map :seon.error.occurrence/count occurrences))
               :seon.error.occurrence/first-at (:seon.error.occurrence/first-at (last occurrences))
               :seon.error.occurrence/last-at (:seon.error.occurrence/last-at latest)
               :seon.error/agents (into (sorted-set) (keep #(get-in % [:seon.error.occurrence/agent :seon.agent/id])) occurrences)
               :seon.error/branches (into (sorted-set) (keep :seon.error.occurrence/branch) occurrences)
               :seon.render/operations
               (cond-> [(list 'seon.error/resolution '(seon.db/db) fault-signature)]
                 producer (into [(list 'my.program/callers {:seon.program/subject (list 'quote producer)})
                                 (list 'my.program/tests-reaching {:seon.program/subject (list 'quote producer)})])
                 evaluation (conj (list 'seon.db/pull ''[*] [:seon.cluster.eval/id evaluation]))
                 blob (conj (list 'seon.blob/get blob))
                 task (conj (list 'my.task/claim! {:seon.task/assigned task})))}
        true (into (concat (select-keys fault [:seon.error/producer :seon.instrument/fn])
                           (select-keys latest [:seon.error/producer-digest :seon.error/caller
                                                :seon.error/expected-key])))
        (seq path) (assoc :seon.error/path path)
        evaluation (assoc :seon.error.occurrence/evaluation evaluation)
        task (assoc :seon.task/id task)
        resolved (assoc :seon.error/resolution resolved)
        (:seon.fn/source definition) (assoc :seon.render.ns/source-lines (str/split-lines (:seon.fn/source definition)))
        (:seon.fn/spec definition) (assoc :seon.fn/spec (:seon.fn/spec definition))))))

(defn finding-ai
  "A stored fault's AI face: its [[finding]] at the unit's distance, the
  briefing an agent assigned its task reads."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.error/finding :seon.db/error-result]]}
  [unit]
  (let [shown (or (:seon.render/value unit) unit)]
    (finding (:seon.db/db unit) (:seon.error/signature shown)
             (max 0 (long (get unit :seon.render/distance 1))))))

(defn- reply-html
  "The reply's keys as hiccup: the same selection `render-ai` returns, laid
  out with the problems as a table and the sources as code. Values are
  shown through the value printer's bounded text."
  {:malli/schema [:=> [:cat :seon.error/reply] :seon.render/hiccup]}
  [reply]
  (let [shown (fn [value] [:code (render.value/core-text value)])
        {:seon.error/keys [message problems offending fix in-namespace sources]} reply]
    (into
     [:div {:class "seon-error-reply"}
      [:h3 {:class "seon-error-message"} (or message "Error evidence is unavailable.")]]
     (concat
      (when (seq problems)
        [[:table {:class "seon-error-problems"}
          [:thead [:tr [:th "Path"] [:th "Expected"] [:th "Offending"] [:th "Example"]]]
          (into [:tbody]
                (map (fn [{:seon.error/keys [path expected offending example] :as problem}]
                       [:tr [:td (shown path)] [:td (shown expected)] [:td (shown offending)]
                        [:td (when (find problem :seon.error/example) (shown example))]]))
                problems)]])
      (when (some? offending)
        [[:div {:class "seon-error-offending"} (shown offending)]])
      (when fix [[:p {:class "seon-error-fix"} fix]])
      (for [[namespace-name names] in-namespace]
        [:p {:class "seon-error-in-namespace"}
         (str namespace-name " declares: ")
         (if (db/read-refusal? names)
           (shown names)
           (into [:span] (interpose " " (map #(vector :code (str %)) names))))])
      (for [[sym source] sources]
        [:details {:class "seon-error-source" :open true}
         [:summary [:code (str sym)]]
         (if (db/read-refusal? source) (shown source) [:pre (str/join "\n" source)])])
      (when-let [frames (seq (:seon.sci.eval/stacktrace reply))]
        [(into [:ol {:class "seon-error-frames"}]
               (map (fn [frame] [:li [:code (render.value/core-text frame)]]))
               frames)])))))

;;; SCHEMA PAIRS AS REPL ENTRIES (owner 2026-09-26, "Three points"). An
;;; error value stays whole; its schema's pair selects what the agent needs
;;; as runnable reads over the held value (its result handle), whose results
;;; the floor prints. Without a handle the pair says only bounded scalars.

(defn held-reference
  "The form naming the rendered value in the viewer's world, or nil: its
  result handle (`seon.sci.admit/result-handle`), or for a value nested in a
  held result `(seon.print/value-at result/eN path)` (`seon.render/declared-projection`)."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe [:or :qualified-symbol :seon.repl/expression]]]}
  [unit]
  (let [root (or (:seon.repl/handle unit) (:seon.render.value/root unit))
        held? #(and (qualified-symbol? %) (= "result" (namespace %)))]
    (cond
      (held? root) root
      (and (seq? root) (= 'seon.print/value-at (first root)) (held? (second root))) root)))

(defn render-form
  "The form that finds this data again: the reply over the held error, when
  the viewer holds it. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/read-form]]}
  [unit]
  (when-let [held (held-reference unit)]
    (list 'seon.error/reply-value held)))

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
      (if-let [reply (rendered-reply unit)]
        (reply-html reply)
        [:h3 {:class "seon-error-message"} "Error evidence is unavailable."])]
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
                  (cond-> {:seon.error/at (java.util.Date.)
                           :seon.error/layer :seon.error/reading
                           :seon.error/operation 'seon.error/fault-entities
                           :seon.error/message "A fault entity was not acquired."}
                    (some? fault) (assoc :seon.error/offending fault)))))
       (sort-by fault-order)))

(defn- faults-input
  {:malli/schema [:=> [:cat :seon.error/source]
                  :seon.error/source]}
  [unit]
  (let [value (:seon.render/value unit)]
    (get value (:seon.render.walk/attribute unit) value)))

(def ^:private agent-faults-query
  "Errors this agent must be able to read, by the two refs that name it.

  A fault in a namespace it stewards names its current function by symbol; a
  fault no steward answers for reaches the escalation owner the writer
  notified, whose opening message is about its signature. A
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
                         [?error :seon.error/occurrences ?occurrence])
                    ;; the writer notified it: the escalation owner of a
                    ;; fault no steward answers for (`commit-call`)
                    (and [?message :seon.message/to ?agent]
                         [?message :seon.message/about ?signature]
                         [?error :seon.error/signature ?signature]))])

(defn for-agent
  "Return the errors assigned to an agent, newest first, each with every
  declared observation member and occurrence.

  An error is assigned when it names a function in a namespace the agent
  stewards, when one of its occurrences happened in the agent's work, or
  when the writer notified the agent of it. Resolution removes nothing.
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
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :nil :seon.render/read-form :seon.db/error-result]]}
  [unit]
  ;; only an entity id or lookup ref names an agent; any other value (an SCI Var
  ;; walked as the attribute's owner) carries none, so it shows no read
  (when-let [entity (let [input (faults-input unit)]
                      (when (or (integer? input) (vector? input)) input))]
   (let [entity-map (db/pull (:seon.db/db unit) [:seon.agent/id] entity)]
    (if (observation? entity-map)
      entity-map
      (when-let [agent-id (:seon.agent/id entity-map)]
      {:seon.repl/comment
       ";; Inspect errors in the namespaces assigned to me and in my own turns."
       :seon.repl/form (list 'seon.error/for-agent '(seon.db/db) agent-id)})))))

(defn render-faults-ai
  "Emit the steward's read, or the AI faces of already acquired fault
  entities, newest first, as data."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe [:or :seon.render/source [:vector [:or [:string {:min 1}] :seon.error/reply]]]]]}
  [unit]
  (let [faults (faults-input unit)]
    (if (and (sequential? faults) (every? map? faults))
      (when (seq faults) (mapv render-ai (fault-entities faults)))
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
        entity (when-not acquired?
              (let [identity-entity (db/pull database [:seon.agent/id] faults)]
                (if-let [agent-id (:seon.agent/id identity-entity)]
                  (for-agent database agent-id)
                  identity-entity)))
        references (cond acquired? faults
                         (observation? entity) [entity]
                         :else entity)
        entities (fault-entities references)]
    (into [:section {:class "seon-family-entry seon-error-faults"}
           [:h2 (str "Faults (" (count entities) ")")]]
          (if (seq entities)
            (map #(render-html {:seon.db/db database :seon.render/value %}) entities)
            [[:p {:class "seon-error-faults-empty"}
              (if acquired? "No fault is recorded against this agent."
                  "No fault is routed to this agent.")]])))))

(defn- prose
  "The lines of one error's AI prose: the parts present, joined by `separator`."
  {:malli/schema [:=> [:cat :string [:vector [:maybe :string]]] :string]}
  [separator parts]
  (str/join separator (remove nil? parts)))

(defn- evidence-of
  "The diagnostic evidence one prose shows: the error's `:seon.error/data` and
  the producer's own `extra-keys` members."
  {:malli/schema [:=> [:cat :map [:vector :qualified-keyword]] :map]}
  [observed extra-keys]
  (merge (:seon.error/data observed) (select-keys observed extra-keys)))

(defn time-limit-prose
  "`:seon.render/ai` — evaluation time-limit evidence without guessing cause."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        entries (or (:seon.eval/fn-entries value)
                    (:seon.sci.eval/time-limit value))]
    (prose " " [(:seon.error/message value)
           (when (some? entries)
             (str "Recorded function-body entries: " entries "."))
           "Many entries indicate a spin; few indicate time spent inside a host call. Inspect the called function before retrying."])))

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
        evidence (evidence-of value [:my.edit/error-path :my.edit/edit-observation])]
    (prose "\n" [(:seon.error/message value)
           (evidence-text evidence)
           "Re-read the exact source and narrow the edit selection before applying it again."])))

(defn elision-prose
  "`:seon.render/ai` — a neutral account of bounded render-walk elision."
  {:malli/schema [:=> [:cat :seon.render/unit] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (:seon.error/data value)]
    (prose " " ["Additional render-walk content was elided by the active render profile."
           (evidence-text evidence)
           "Request the next offset when more detail is needed."])))

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
                      [:dd (render.value/html {:seon.render/value evidence-value :seon.db/db (:seon.db/db unit)
                                               :seon.render.value/root [::elision attribute]})]]))
              evidence)]))))

(defn unclassified-prose
  "`:seon.render/ai` — evidence from an observation whose domain is unavailable."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (:seon.error/data value)]
    (prose "\n" [(:seon.error/message value)
           "The original boundary did not supply complete domain evidence."
           (evidence-text evidence)
           "Inspect the observation and its boundary contract before retrying."])))

(defn mcp-prose
  "`:seon.render/ai` — retrieval evidence for a failed MCP value lookup."
  {:malli/schema [:=> [:cat :seon.render/unit] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (evidence-of value [:seon.dev.mcp/error-cluster :seon.dev.mcp/request-observation])]
    (prose "\n" [(:seon.error/message value)
           (evidence-text evidence)
           "Re-read the current cluster status or value identity before requesting the data again."])))

(defn index-refusal-prose
  "`:seon.render/ai` — the precise evidence that stopped program indexing."
  {:malli/schema [:=> [:cat :seon.render/unit] [:string {:min 1}]]}
  [unit]
  (let [value (rendered-error-value unit)
        evidence (evidence-of value [:seon.fn/error-subject :seon.fn/analysis-phase])]
    (prose "\n" [(:seon.error/message value)
           (evidence-text evidence)
           "Repair the named source or declaration evidence, then rerun initialization."])))

;;; Complete owned error observations.

(defn- exclusive-members?
  "Exactly one of the supplied attributes is present on a candidate map."
  {:malli/schema [:=> [:cat :seon.schema/value [:vector :qualified-keyword]] :boolean]}
  [value attributes]
  (and (map? value) (= 1 (count (filter #(contains? value %) attributes)))))

(defn- ordered-members?
  "Counts describe retained children, with contiguous unique ordinals."
  {:malli/schema [:=> [:cat :seon.schema/value :qualified-keyword :qualified-keyword
                       :qualified-keyword] :boolean]}
  [value count-key children-key ordinal-key]
  (and (map? value)
       (let [n (get value count-key)
             children (get value children-key)]
         (and (nat-int? n)
              (if (zero? n)
                (not (contains? value children-key))
                (and (coll? children) (= n (count children))
                     (every? map? children)
                     (= (set (range n)) (set (map ordinal-key children)))))))))

(defn ordered-location?
  "The retained path has precisely its declared ordered segments."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (ordered-members? value :seon.error.location/length :seon.error.location/segments
                    :seon.error.location.segment/ordinal))

(defn ordered-failures?
  "An accretion group retains exactly its counted failures in ordinal order."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (ordered-members? value :seon.test.accretion.group/count
                    :seon.test.accretion.group/failures
                    :seon.test.accretion.failure/ordinal))

(defn ordered-groups?
  "Optional accretion groups and their count are supplied together."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (if (contains? value :seon.test.accretion/error-group-count)
         (ordered-members? value :seon.test.accretion/error-group-count
                           :seon.test.accretion/error-groups
                           :seon.test.accretion.group/ordinal)
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
                         :seon.instrument/declared-arities :seon.instrument.arity/ordinal)
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
    (assoc base :seon.config/error-key :seon.config.eval/time-limit-ms
           (if key? :seon.error/expected-key :seon.error/expected-shape)
           (if key? :seon.config.eval/time-limit-ms (apply str (repeat 64 "0"))))))

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
