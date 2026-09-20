(ns seon.error
  "Error observations, complete declared component reads, and writer-owned recurrence.

  D12: errors are structural base values with composable declared facets.
  Callers branch on their boundary's required members; no general error
  predicate or stored classification is used here. D13 identity derives from
  layer, operation, satisfied facets, Throwable class/frame, violated schema
  and location. Message, time, process and offending bytes do not identify a bug.

  Normalization receives its projection and evidence policy. Recording acquires
  them from its supplied database value, then returns transaction data; the
  database writer decides occurrence counts and the first notification. Readers
  acquire complete declared components before rendering."
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
            [seon.schema.datahike :as schema.datahike]
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


(defn refusal
  "The deepest non-empty `ex-data` in a throwable's cause chain, or nil.
  Pure, and unit-testable with no database: a refusal is a value buried
  under wrappers, and finding it is a walk, not a guess. Returns nil for
  a throwable that carries no data anywhere in its chain — which is
  itself information, and the caller treats it as unclassifiable.

  The pure cause-chain owner is `seon.error.refusal`, below both this
  rendering-aware normalizer and `seon.db`; this public entry delegates
  so existing callers retain one behavior without a dependency cycle."
  {:malli/schema
   [:=> [:cat [:maybe :seon.error/throwable]]
    [:or
     :nil
     :map
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.test/admission-error :seon.test/execution-error :seon.test/expired
     :seon.test/not-runnable-error :seon.test/resolution-error
     :seon.test/selection-error :seon.test/unknown-error
     :seon.test.run/immutable-error :seon.test.run/unavailable-error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
     :seon.turn.loop/error]]}
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



(defn- root-cause
  "The deepest Throwable in the cause chain.
  A different question from `refusal`'s — that one digs out the deepest
  DATA, this one names the throwable the chain bottoms out in — so it is
  not a copy of that walk."
  {:malli/schema [:=> [:cat :seon.error/throwable]
                  :seon.error/throwable]}
  [failure]
  (loop [candidate failure]
    (if-let [cause (ex-cause candidate)]
      (recur cause)
      candidate)))

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
  {:malli/schema [:=> [:cat [:or :nil :seon.error/throwable]]
                  [:or :nil :seon.error/frame]]}
  [failure]
  (when-let [^StackTraceElement frame (when failure (first (.getStackTrace ^Throwable failure)))]
    (when-let [file (.getFileName frame)]
      [(symbol (.getClassName frame)) (symbol (.getMethodName frame))
       file (long (.getLineNumber frame))])))

(declare facets facet-keys stored-observation observation-selector latest-fact)

(defn- signature
  "D13: identity of the observed site, satisfied facets, violated schema and path.
  Incidental time, process, message and offending bytes never enter this tuple."
  {:malli/schema [:=> [:cat :seon.schema/projection :map
                       [:or :nil :symbol] [:or :nil :seon.error/frame]]
                  :seon.error/signature]}
  [projection observation throwable-class frame]
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
            (into (sorted-set) (facets projection observation))
            throwable-class frame
            (into (sorted-map)
                  (select-keys observation [:seon.error/expected-key :seon.error/expected-shape]))
            path]
           64)))

(defn- stored-observation
  "Restore the declared stored collection/ref grammar of a complete acquired
  observation. Only declared attributes are transformed; owned children keep
  their complete values, peer refs keep their entity identity."
  {:malli/schema [:=> [:cat :seon.schema/projection :map] [:or
     :nil
     :map
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.test/admission-error :seon.test/execution-error :seon.test/expired
     :seon.test/not-runnable-error :seon.test/resolution-error
     :seon.test/selection-error :seon.test/unknown-error
     :seon.test.run/immutable-error :seon.test.run/unavailable-error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
     :seon.turn.loop/error]]}
  [projection observation]
  (let [attributes
        (schema/projection-cache-value
         projection ::observation-attributes
         (fn []
           (let [forms (:seon.schema.projection/forms projection)
                 stored-attributes (set (schema.form/database-attributes forms))
                 attributes
                 (loop [pending (vec (conj (facet-keys projection) :seon.error/base))
                        seen #{} result #{}]
                   (if-let [entity (peek pending)]
                     (if (seen entity)
                       (recur (pop pending) seen result)
                       (let [members (map first (schema.form/map-entries forms (get forms entity)))
                             children (keep #(-> (get forms %) schema.form/attr-form-properties
                                                  :seon.db/component-schema) members)]
                         (recur (into (pop pending) children) (conj seen entity)
                                (into result members))))
                     result))]
             (into {}
                   (comp (filter stored-attributes)
                         (map (fn [attribute]
                                [attribute (schema.datahike/malli->datahike-attr-in projection attribute)])))
                   attributes))))]
    (letfn [(restore [value]
              (if-not (map? value)
                value
                (into {}
                      (keep (fn [[attribute observed]]
                              (when (not= :db/id attribute)
                                (let [declaration (get attributes attribute)
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
      (restore observation))))

;;; ---------------------------------------------------------------------------
;;; Flat diagnostics — one evidence-complete construction
;;; ---------------------------------------------------------------------------

(defn diagnostic
  "Construct the declared base observation and its diagnostic evidence.
  Delegate to the leaf constructor, preserving supplied domain members."
  {:malli/schema
   [:=> [:cat [:map
                [:seon.error/at :seon.error/at]
                [:seon.error/layer :seon.error/layer]
                [:seon.error/operation :seon.error/operation]
                [:seon.error/message :seon.error/message]
                [:seon.error/diagnostic-layer :seon.schema/value]
                [:seon.error/diagnostic-operation :seon.schema/value]
                [:seon.error/diagnostic-member :seon.schema/value]
                [:seon.error/diagnostic-expected :seon.schema/value]
                [:seon.error/diagnostic-offending :seon.schema/value]
                [:seon.error/diagnostic-cause :seon.schema/value]
                [:seon.error/diagnostic-evidence :seon.schema/value]
                [:seon.error/data {:optional true} :map]]]
    :seon.error/base]}
  [observation]
  (error.refusal/diagnostic observation))

;;; ---------------------------------------------------------------------------
;;; The normalizer
;;; ---------------------------------------------------------------------------

(defn- meaningful-source
  {:malli/schema [:=> [:cat :seon.error/source]
                  [:or :seon.error/source
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
     :seon.turn.loop/error]]}
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
  {:malli/schema [:=> [:cat :seon.error/source :seon.sci.admit/caps]
                  :seon.sci.admit/admitted]}
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
  "Present current and proposed base observations, in declared priority order."
  {:malli/schema [:=> [:cat :seon.error/source [:or :nil :seon.error/throwable]]
                  [:or :map :seon.error/base]]}
  [source failure]
  (let [error-value (if failure (refusal failure) source)
        candidates (merge (when (map? source) source)
                          (when (map? error-value) error-value))]
    (into (array-map)
          (keep (fn [member]
                  (when-let [entry (find candidates member)] entry)))
          classifying-error-keys)))

(defn- bounded-error-admission
  "Admit complete evidence, retaining classifying observations on overflow."
  {:malli/schema [:=> [:cat :seon.error/source [:or :nil :seon.error/throwable] :seon.sci.admit/caps]
                  :seon.sci.admit/admitted]}
  [source failure caps]
  (let [request {:seon.sci.admit/value source
                 :seon.sci.admit/interrupt-fn (constantly nil)
                 :seon.sci.admit/caps caps
                 :seon.config/on-core-error :record}
        admitted (admit/admit-partitioned
                  request (classifying-error-data source failure))]
    (if (admit/missing-marker admitted)
      (bounded-admission source caps)
      admitted)))

(defn- bounded-text
  "One value as the text a fault field stores, under the evidence bound.

  A string is its own text; anything else is its admitted node emitted
  through the one printer. Over the bound the field IS the missing marker —
  the same data every other surface reports an absent value with — and the
  whole value stays reachable in the fault's evidence content."
  {:malli/schema [:=> [:cat :seon.error/source :seon.sci.admit/caps]
                  :string]}
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
  {:malli/schema [:=> [:cat [:or :nil :seon.error/throwable]]
                  [:or :nil :qualified-symbol]]}
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
  "The reporter's declared function/arm evidence, from an admitted source."
  {:malli/schema [:=> [:cat :seon.error/source]
                  [:or :nil :map]]}
  [source]
  (let [observation (if (map? (::flow/ex source)) (:data (::flow/ex source)) source)
        data (:seon.error/data observation)]
    (when (and (map? data)
               (qualified-symbol? (:seon.instrument/fn data))
               (#{:input :output :guard} (:seon.instrument/arm data)))
      data)))

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
  {:malli/schema [:=> [:cat :seon.error/source]
                  [:or :nil [:tuple :qualified-keyword :seon.error/source]]]}
  [error-value]
  (find (get-in (contract-violation-data error-value) [:seon.error/problems 0])
        :seon.error/offending))

(defn project-observation
  "Project one observed value under the supplied admission caps."
  {:malli/schema [:=> [:cat :seon.sci.admit/caps :seon.schema/value] :map]}
  [caps value]
  (let [admitted (bounded-admission value caps)]
    {:seon.error/capped? (boolean (or (::marker admitted)
                                    (:seon.sci.admit/capped? admitted)))
     :seon.error.projection/bound-bytes (:seon.config.eval.result/max-bytes caps)
     :seon.instrument/actual (:seon.sci.admit/edn admitted)}))

(defn- admitted-size
  "One value's own size, measured by the same admission that stores it.

  Over the bound the marker reports the bytes it refused, so the number is
  the value's and never the substitute's — the same rule `prepare` applies to
  `:seon.error/data-size`."
  {:malli/schema [:=> [:cat :seon.error/source :seon.sci.admit/caps]
                  [:or :nil [:int {:min 0}]]]}
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
  {:malli/schema [:=> [:cat :map :seon.error/source :string [:or :nil :map] [:or :nil [:tuple :qualified-keyword :seon.error/source]] :seon.sci.admit/caps [:int {:min 1}]]
                  [:or :seon.error/fact :seon.error/base]]}
  [base-fact source message-value instrument-data actual caps inline-limit]
  (let [expected (or (:seon.instrument/schema instrument-data)
                     (:seon.error/diagnostic-expected instrument-data))
        arguments (or (:seon.instrument/args instrument-data)
                      (:seon.error/diagnostic-offending instrument-data))
        payload-count (+ 2 (if expected 1 0) (if arguments 1 0) (if actual 1 0))
        available (max 1 (- inline-limit (utf8-size (pr-str base-fact))))]
    (loop [field-limit (max 1 (quot available payload-count))]
      (let [field-caps (evidence-caps caps field-limit)
            evidence (bounded-error-admission source (throwable source)
                                              field-caps)
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
    projection :seon.schema/projection
    :seon.sci.admit/keys [caps]
    run-id :seon.turn/id
    agent-id :seon.agent/id}]
  (let [failure (throwable source)
        class-name (when failure (.getName (class failure)))
        source (meaningful-source source)
        admitted (bounded-error-admission source failure caps)
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
        operation (or (:seon.error/operation error-value)
                      (get-in error-value [:seon.error/data :seon.error/diagnostic-operation]))
        function (or (when (qualified-symbol? operation) operation)
                     (:seon.instrument/fn instrument-data)
                     (stack-failing-function failure))
        frame (top-frame failure)
        observation (merge {:seon.error/at at
                            :seon.error/layer :seon.error/normalization
                            :seon.error/operation (or function 'seon.error/normalize)}
                           (when (map? error-value) error-value))
        observation
        (if ((schema/projection-validator projection :seon.db.write/validation-refusal) observation)
          (-> observation
              (assoc :seon.db.write/attempt
                     {:seon.db.write.attempt/request-id (:seon.db.write.attempt/request-id observation)
                      :seon.db.write.attempt/observed-at (:seon.error/at observation)
                      :seon.db.write.attempt/operations
                      (project-observation caps (get-in observation [:seon.error/data :seon.db.write.attempt/transaction]))})
              (dissoc :seon.db.write.attempt/request-id)
              (update :seon.error/data dissoc :seon.db.write.attempt/transaction))
          observation)
        observation
        (if ((schema/projection-validator projection :seon.schema/validation-refusal) observation)
          (-> observation
              (assoc :seon.schema/error-declaration
                     (project-observation caps (:seon.schema/refused-value observation))
                     :seon.schema/declaration-expectation
                     (project-observation caps (:seon.schema/expected-value observation)))
              (dissoc :seon.schema/refused-value :seon.schema/expected-value))
          observation)
        signature (signature projection observation
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
        actual-size (when actual (admitted-size (val actual) caps))
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
     :seon.error/source observation
     :seon.error/data-content full-edn}))

(defn normalize
  "Normalize one observation with a supplied projection and bounded evidence.
  The signature is D13's stable tuple; complete source facets remain on the
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
                          :seon.error/message :seon.error/signature])
         :seon.error/data {:seon.error/id (:seon.error/id fact)}))

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
  (let [presentation (if (and (qualified-symbol? (:seon.instrument/fn fact))
                                    (#{:input :output :guard} (:seon.instrument/arm fact)))
                       `instrumentation-prose
                       `ai-prose)]
    (cond-> {:seon.error/fact fact
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
  {:malli/schema [:=> [:cat :map]
                  [:or :seon.error/source
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
     :seon.turn.loop/error]]}
  [fact]
  (try
    (admit/semantic-value (edn/read-string (:seon.error/data-edn fact)))
    (catch Throwable failure
      {:seon.error/at (or (:seon.error/at fact) (java.util.Date.))
       :seon.error/layer :seon.error/reading
       :seon.error/operation 'seon.error/fact-source
       :seon.error/expected-key :seon.error/data-edn
       :seon.error/message (str "Stored error evidence could not be read: " (ex-message failure))})))

(defn- flat-data
  {:malli/schema [:=> [:cat :map]
                  [:or :seon.error/source
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
     :seon.turn.loop/error]]}
  [fact]
  (let [source (fact-source fact)]
    (if (map? (:seon.error/data source))
      (:seon.error/data source)
      source)))

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
                     #(or % [:seon.error/diagnostic-offending])))
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
          {:seon.error/at (java.util.Date.)
           :seon.error/layer :seon.error/reading
           :seon.error/operation 'seon.error/reader-correction
           :seon.error/message "The supplied-entry owner has not declared its error output."
           :seon.error/data {:seon.error/cause supplied}}
          (and (string? spec) (nat-int? position)
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
  {:malli/schema [:=> [:cat :seon.error/source :seon.error/source [:or :nil :map]]
                  [:or :nil :map :seon.db/error-result :seon.error/base]]}
  [unit fact data]
  (let [evidence (merge (when (map? fact) fact) data)]
    (cond
      (seq (:seon.error/problems data)) data

      (find evidence :seon.sci.reader/text)
      (let [correction (reader-correction unit evidence)]
       (if (map? correction)
         correction
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
           :else (str "Correct the reader error: " (:seon.error/message fact)))}]}))

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
  {:malli/schema [:=> [:cat :seon.error/source :seon.error/source :seon.error/source]
                  [:or :nil :string]]}
  [unit fact data]
  (let [stored-problems? (seq (:seon.error/problems data))
        data (refusal-data unit fact (when (map? data) data))
        operation (:seon.error/diagnostic-operation data)
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
    (if (and (inst? (:seon.error/at data))
             (qualified-keyword? (:seon.error/layer data))
             (qualified-symbol? (:seon.error/operation data)))
      (:seon.error/message data)
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
               (when (and (zero? index) (:seon.instrument/caller data))
                 (str " Called from " (:seon.instrument/caller data) "."))
               " Example: " (or example "No docstring example is available."))))
        problems))))))

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
                         "Further occurrences share this record and do not send another notification."])))))

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
  recurrence, the operation to find the observed boundary, and the run, process, proc,
  op, cid and basis-t refs to find everything around it. Each is omitted
  when absent."
  {:malli/schema [:=> [:cat :seon.error/notice] [:string {:min 1}]]}
  [notice]
  (let [fact (:seon.error/fact notice)
        {:seon.error/keys [id at layer operation message process signature
                           throwable-class proc op cid run basis-t]} fact
        source (fact-source fact)
        data (flat-data fact)
        aggregate? (:seon.error/occurrence-count notice)]
    (str/join
     " "
     (remove
      nil?
      ["seon.error"
       (str "layer=" layer)
       (str "operation=" operation)
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
       (when (and (:seon.turn/rule source) (:seon.turn/transition source)) "committed=false")
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
  {:malli/schema [:=> [:cat :seon.error/id]
                  :string]}
  [id]
  (str "seon.error/fact-" id))

(defn- agent-exists?
  "Return presence or the database's declared read refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :boolean :seon.db/error-result]]}
  [database agent-id]
  (let [result (db/q '[:find ?agent . :in $ ?id
                       :where [?agent :seon.agent/id ?id]] database agent-id)]
    (if (or (nil? result) (integer? result)) (some? result) result)))

(defn- entity-exists?
  "Return presence or the database's declared read refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value]
                  [:or :boolean :seon.db/error-result]]}
  [database attribute value]
  (let [result (db/q '[:find ?entity . :in $ ?attribute ?value
                       :where [?entity ?attribute ?value]] database attribute value)]
    (if (or (nil? result) (integer? result)) (some? result) result)))

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

(defn- recurrence
  "Sum the root's occurrences or preserve the database's declared refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/signature]
                  [:or [:int {:min 0}] :seon.db/error-result]]}
  [database signature]
  (let [rows (db/q '[:find ?occurrence ?count :in $ ?signature
                     :where [?error :seon.error/signature ?signature]
                            [?error :seon.error/occurrences ?occurrence]
                            [?occurrence :seon.error.occurrence/count ?count]]
                   database signature)]
    (if (map? rows) rows (reduce + 0 (map second rows)))))

(defn- message-tx
  {:malli/schema [:=> [:cat :seon.error/fact :seon.agent/id :seon.agent/id :seon.error/reason :map]
                  :map]}
  [fact sender recipient reason notification]
  {:seon.message/id (id/id [(:seon.error/signature fact) recipient])
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
        agent-id (second (:seon.error/agent fact))
        turn-id (second (:seon.error/run fact))
        escalate-to (:seon.config.error/escalate-to request)
        agent-present (when agent-id (agent-exists? database agent-id))
        turn-present (when turn-id (entity-exists? database :seon.turn/id turn-id))
        escalation-present (when escalate-to (agent-exists? database escalate-to))
        steward-id (steward database fact)
        steward-present (when (string? steward-id) (agent-exists? database steward-id))
        occurrences (recurrence database signature)
        read-refusal (some (fn [observation]
                             (when (and (map? observation)
                                        (inst? (:seon.error/at observation))
                                        (qualified-keyword? (:seon.error/layer observation))
                                        (qualified-symbol? (:seon.error/operation observation)))
                               observation))
                           [(when-not (:seon.error.occurrence/id old) old)
                            agent-present turn-present escalation-present
                            steward-id steward-present occurrences])]
    (if read-refusal
      read-refusal
      (let [projection (schema/projection-from-database database)
        forms (:seon.schema.projection/forms projection)
        diagnostic-attributes
        (schema/projection-cache-value
         projection ::facet-attributes
         #(into #{} (mapcat (fn [facet]
                              (map first (schema.form/map-entries forms (get forms facet)))))
                (conj (facet-keys projection) :seon.error/base)))
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
        first-occurrence? (zero? occurrences)
        interrupted? (some? (:seon.error/exception-class fact))
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
                                 (let [source (:seon.error/source request)
                                       base? (schema/projection-cache-value
                                              projection ::base-validator
                                              #(schema/projection-validator projection :seon.error/base))]
                                   (when (and (map? source) (base? source))
                                     (let [attributes (into #{}
                                                            (mapcat #(map first (schema.form/map-entries forms (get forms %))))
                                                            (conj (facets projection source) :seon.error/base))]
                                       (select-keys source attributes))))
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
                                          :seon.error/layer :seon.error/operation :seon.instrument/fn :seon.error/frame
                                          :seon.error/exception-class])
                         :seon.error/occurrences #{occurrence})
        notification {:seon.error/notification-id signature}
        recipients (when first-occurrence?
                     (cond
                       steward-present {steward-id :recurring}
                       (and interrupted? agent-id) {agent-id :your-run}
                       (and interrupted? escalation-present) {escalate-to :no-attributable-agent}
                       :else {}))]
    (into (cond-> (into [{:seon.db.process/id process}] replacements)
            digest (conj {:seon.error.occurrence/blob-digest digest :seon.error/data-blob digest})
            (and (nil? digest) (:seon.error.occurrence/data-blob old))
            (conj [:db/retract occurrence-ref :seon.error.occurrence/data-blob
                   (:db/id (:seon.error.occurrence/data-blob old))])
            true (conj error-row))
          (keep (fn [[recipient reason]]
                  (message-tx fact (or agent-id
                                      (when escalation-present escalate-to)
                                      steward-id) recipient reason notification)))
          recipients)))))

(defn recording
  "Prepared identities, flat value and transaction data for one error.

  The leading identity row preserves existing transaction composition; it
  contains no occurrence state. Counts and notification decisions belong
  exclusively to commit-call's mid-transaction database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.error/commit-tx-request] [:or :seon.error/recording :seon.db/error-result :seon.error/base]]
    [:=> [:cat :map :seon.db/database-value :seon.error/source :inst :map] [:or :seon.error/recording :seon.db/error-result :seon.error/base]]]}
  ([database request]
   (let [request (assoc request :seon.schema/projection
                        (schema/projection-from-database database))
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
         fact (or (:seon.error/fact request) (:seon.error/fact prepared))
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
                :seon.error/layer (:seon.error/layer fact)
                :seon.error/operation (:seon.error/operation fact)}]
         tx (conj rows [:db.fn/call #'commit-call
                        (assoc request :seon.error/fact fact :seon.error.occurrence/id occurrence-id)])]
     {:seon.error/fact fact
      :seon.error/ref [:seon.error/signature signature]
      :seon.error.occurrence/ref [:seon.error.occurrence/id occurrence-id]
      :seon.error/value (value fact)
      :seon.db/tx-data tx}))))
  ([cluster database source at attribution]
   (recording database
              (merge (select-keys cluster [:seon.sci.admit/caps :seon.config.error/recurrence-limit
                                          :seon.config.error/max-evidence-bytes :seon.config.error/escalate-to])
                     {:seon.error/source source :seon.error/id (id/id)
                      :seon.error/at at :seon.error/process (:seon.db.process/id cluster)
                      :seon.error/basis-t (db/basis-t database)}
                     attribution))))

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
           stored-attributes (set (schema.form/database-attributes forms))
           observation-keys (conj (facet-keys projection)
                                  :seon.error/base :seon.error.occurrence/occurrence)]
       (letfn [(members [schemas]
                 (sort (into #{} (comp (mapcat #(map first (schema.form/map-entries forms (get forms %))))
                                       (filter stored-attributes)) schemas)))
               (selector [schemas active]
                 (into [:db/id]
                       (map (fn [attribute]
                              (let [child (:seon.db/component-schema
                                           (schema.form/attr-form-properties (get forms attribute)))]
                                (if (and child (not (contains? active child)))
                                  {[attribute :limit nil]
                                   (selector (if (= child :seon.error.occurrence/occurrence)
                                               observation-keys #{child})
                                             (conj active child))}
                                  [attribute :limit nil]))))
                       (members schemas)))]
         (selector #{:seon.error/error} #{:seon.error/error}))))))

(defn latest-fact
  "Project an error's latest occurrence for the existing diagnostic renderers."
  {:malli/schema
   [:=> [:cat :map]
    [:or :map
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.test/admission-error :seon.test/execution-error :seon.test/expired
     :seon.test/not-runnable-error :seon.test/resolution-error
     :seon.test/selection-error :seon.test/unknown-error
     :seon.test.run/immutable-error :seon.test.run/unavailable-error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
     :seon.turn.loop/error]]}
  [error]
  (if (some #(not (map? %)) (:seon.error/occurrences error))
    {:seon.error/at (java.util.Date.)
     :seon.error/layer :seon.error/reading
     :seon.error/operation 'seon.error/latest-fact
     :seon.error/expected-key :seon.error.occurrence/occurrence
     :seon.error/message "Occurrence evidence was not acquired."}
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
    [:or :seon.error/source
     :nil
     :map
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
     :seon.turn.loop/error]]}
  [unit]
  (let [value (if (map? (:seon.render/value unit))
                (:seon.render/value unit)
                unit)
        database (:seon.db/db unit)
        value (if (and database (:seon.error/signature value))
                (db/pull database
                         (observation-selector (schema/projection-from-database database))
                         [:seon.error/signature (:seon.error/signature value)])
                value)]
    (if (map? value) (latest-fact value) value)))





(defn facet-keys
  "Canonical base-extension declarations in this projection, excluding aliases.
  The immutable projection retains the derived population, never error values."
  {:malli/schema [:=> [:cat :map] [:set :qualified-keyword]]}
  [projection]
  (schema/projection-cache-value
   projection ::facet-keys
   (fn []
     (let [forms (:seon.schema.projection/forms projection)]
       (into #{}
             (keep (fn [[k definition]]
                     (when (and (not= k :seon.error/base)
                                (vector? definition)
                                (= :and (first definition))
                                (schema.form/extends-schema?
                                 forms definition :seon.error/base))
                       k)))
             forms)))))

(defn facets
  "All canonical error facets satisfied by a complete value in projection.
  Validators derive once from the supplied declarations, including at boot
  before a program-graph shape catalog exists. Every facet predicate runs."
  {:malli/schema [:=> [:cat :map :seon.schema/value] [:set :qualified-keyword]]}
  [projection value]
  (let [validators
        (schema/projection-cache-value
         projection ::facet-validators
         (fn []
           (mapv (fn [facet]
                   [facet (schema/projection-validator projection facet)])
                 (sort (facet-keys projection)))))]
    (into #{}
          (keep (fn [[facet valid?]] (when (valid? value) facet)))
          validators)))











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
  "Render one fault's operation, message, time, function, turn, and evidence link."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.render/hiccup]}
  [unit]
  (let [value (rendered-error-value unit)
        turn (:seon.error/run value)
        turn-ref (if (map? turn)
                   (if-let [id (:seon.turn/id turn)]
                     [:seon.turn/id id] (:db/id turn))
                   turn)]
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
                  [:or :seon.error/source
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
     :seon.turn.loop/error]]}
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
       "Inspect errors in the namespaces assigned to me and in my own turns."
       :seon.repl/form
       (list 'seon.db/q
             (list 'quote
                   (assoc-in agent-faults-query [1 0]
                             (list 'pull '?error
                                   (observation-selector
                                    (schema/projection-from-database (:seon.db/db unit))))))
             agent-id)})))))

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
              (db/q '[:find [?error ...]
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
                         (and (map? row) (inst? (:seon.error/at row))
                              (qualified-keyword? (:seon.error/layer row))
                              (qualified-symbol? (:seon.error/operation row))) [row]
                         :else row)
        entities (fault-entities
                  (mapv #(if (map? %) %
                             (db/pull database
                                      (observation-selector (schema/projection-from-database database)) %))
                        references))]
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
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
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
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.render/hiccup]}
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
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
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
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
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

(defn facet-counts-agree?
  "Declared and actual facet counts each describe their own optional sets."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (every? (fn [[count-key set-key]]
                 (let [n (get value count-key)]
                   (and (nat-int? n)
                        (if (zero? n) (not (contains? value set-key))
                            (and (set? (get value set-key))
                                 (= n (count (get value set-key))))))))
               [[:seon.instrument/declared-facet-count :seon.instrument/declared-facets]
                [:seon.instrument/actual-facet-count :seon.instrument/actual-facets]])))

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

(def facet-counts-agree-generator
  (gen/let [base error-base-generator projection projection-complete-generator
            declared (gen/set (gen/elements [:seon.agent/error :seon.turn/error]))
            actual (gen/set (gen/elements [:seon.db.read/error :seon.config/error]))]
    (cond-> (assoc base :seon.instrument/fn 'seon.id/valid? :seon.instrument/arity 2
                   :seon.instrument/returned-error projection
                   :seon.instrument/declared-facet-digest (apply str (repeat 64 "0"))
                   :seon.instrument/declared-facet-count (count declared)
                   :seon.instrument/actual-facet-count (count actual))
      (seq declared) (assoc :seon.instrument/declared-facets declared)
      (seq actual) (assoc :seon.instrument/actual-facets actual))))

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
