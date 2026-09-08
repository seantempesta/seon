(ns seon.sci.admit
  "Projects values leaving SCI into printable ordinary data under ONE bound.

  `admit` walks the supplied value once and calls the supplied
  `:interrupt-fn` before projecting every node. Every result is an
  unambiguous `:seon.print/face` envelope in the closed print grammar;
  authored print keywords remain ordinary child data. Maps, sets,
  records, sequences, and host collections are rebuilt WHOLE — there are no
  depth, width, string or node caps here any more, because elision happens
  only where AI context is generated. Reference values with a
  registry-declared identity projection admit only that identity; other
  reference types and arrays are never entered. That registry question is
  asked against ONE declaration projection handed with the admission request
  or its enclosing operation, never reached process-sideways and never once
  per node.

  THE ONE BOUND IS STORAGE, AND IT IS STREAMING. The walk serializes as it
  projects, into a bounded appendable counting UTF-8 bytes, under the
  evaluation's own SCI interrupt. A byte count taken on a finished string is
  not an execution bound — a lazy sequence can block before its first byte —
  so the count is taken as the bytes are emitted, and emission stops at
  `:seon.config.eval.result/max-bytes`. What the walk emitted IS what is
  stored: one serializer, nothing downstream to agree with.

  A value is therefore either stored FAITHFULLY or MISSING. Under the bound,
  `admit` returns the print node, the derived semantic value, and the exact
  EDN it emitted. Over the bound — or when the walk could project no data at
  all, an opaque host reference or a projection that failed — it returns
  nothing but `:seon.eval/missing` and, for the bound, `:seon.eval/size`.
  There is no window, no page, and no `capped?` flag: a caller never has to
  ask whether the thing it holds is the whole value.

  `admit-value` returns that answer without the serialized EDN key; `admit`
  adds `:seon.cluster.eval/result-edn` for storage callers. Admission
  preserves supplied evaluation diagnostics. SCI interrupts propagate. Other
  projection failures panic or degrade to markers according to
  `:seon.config/on-core-error`. Admission opens no resources and writes no
  durable state."
  (:require [clojure.edn :as edn]
            [clojure.test.check.generators :as gen]
            ;; sci.lang and sci.impl.types are loaded for their deftypes:
            ;; the class literals below do not exist until their defining
            ;; namespace has loaded, and a require is how that is stated.
            [sci.impl.namespaces :as sci.namespaces]
            [sci.impl.types :as sci.types]
            [sci.lang]
            [seon.print :as print]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(defn interrupt-fn?
  "True for the zero-argument fn sci calls on every fn-body entrance.
  Admission is HANDED this fn; it never builds one, never owns the
  timer, and never decides when it fires — it only guarantees that a
  realization step cannot proceed without calling it."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (ifn? value))

(schema/register-core-predicate! 'seon.sci.admit/interrupt-fn?
                                 interrupt-fn?)

(def interrupt-fn-generator
  "A real interrupt-fn — honest by constructing an instance."
  (gen/return (fn [] nil)))

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The bounded appendable — the storage bound, measured as bytes are emitted
;;; ---------------------------------------------------------------------------

(defn canonical-edn
  "Return canonical readable EDN independent of ambient print bindings."
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (binding [*print-length* nil
            *print-level* nil
            *print-meta* false
            *print-readably* true
            *print-dup* false
            *print-namespace-maps* true]
    (pr-str value)))

(defn- utf8-length
  "UTF-8 bytes of one string, without allocating the bytes to count them."
  ^long [^String text]
  (let [length (.length text)]
    (loop [index 0
           total 0]
      (if (< index length)
        (let [point (int (.charAt text index))]
          (recur (inc index)
                 (long (+ total
                          (cond
                            (< point 0x80) 1
                            (< point 0x800) 2
                            ;; a surrogate pair is four bytes for two chars,
                            ;; so each char of it carries two
                            (Character/isSurrogate (char point)) 2
                            :else 3)))))
        total))))

(def ^:private over-bound-marker ::over-bound)

(defn- write!
  "Emit one fragment, or stop the walk because the storage bound is reached.

  The bound is checked BEFORE the fragment lands, so nothing past it is ever
  built, and the size reported is the bound itself: the walk stopped there."
  [state ^String text]
  (let [bound (:max-bytes state)
        emitted (long @(:bytes state))
        total (+ emitted (utf8-length text))]
    (when (and bound (> total (long bound)))
      (throw (ex-info "value admission reached its storage bound"
                      {:seon.error/kind over-bound-marker
                       over-bound-marker true
                       :seon.eval/size (long bound)})))
    (vreset! (:bytes state) total)
    (.append ^StringBuilder (:builder state) text)
    nil))

(defn- over-bound?
  [failure]
  (true? (over-bound-marker (ex-data failure))))

;;; ---------------------------------------------------------------------------
;;; The walk — one pass, inside the armed boundary
;;; ---------------------------------------------------------------------------

(defn- value-node
  [face value]
  {::print/face face
   ::print/value value})

(defn- sci-named
  "The name sci itself reports for a value it defined, or nil."
  [value]
  (when (instance? sci.impl.types.SciTypeInstance value)
    (str (sci.types/-get-type value))))

(defn- class-name
  [value]
  (let [class-name* (.getName (class value))]
    (if (ifn? value)
      (sci.namespaces/demunge class-name*)
      class-name*)))

(defn- object-node
  [value]
  (cond-> {::print/face ::print/object
           ::print/class (class-name value)}
    ;; SCI's maintained Namespace type owns a stable symbolic name. Generic
    ;; host `toString` values commonly include the same process identity that
    ;; this projection exists to exclude, so no other object description is
    ;; admitted into an agent-facing print node.
    (instance? sci.lang.Namespace value)
    (assoc ::print/rep (pr-str (str (sci.types/getName value))))))

(declare project)

(defn- leaf!
  "Emit one node that has no child node: its own canonical EDN, exactly."
  [state node]
  (write! state (canonical-edn node))
  node)

(defn- items!
  "Emit `#:seon.print{:face F, :items [ … ]}` while projecting the children."
  [state values face]
  (write! state (str "#:seon.print{:face " face ", :items ["))
  (let [items (loop [remaining (seq values)
                     taken 0
                     accumulated []]
                (if (nil? remaining)
                  accumulated
                  (do
                    (when (pos? taken) (write! state " "))
                    (let [child (project (first remaining) state)]
                      (recur (next remaining) (inc taken)
                             (conj accumulated child))))))]
    (write! state "]}")
    {::print/face face ::print/items items}))

(defn- entries!
  "Emit a map's or record's `:entries` while projecting both sides of each."
  [state entries]
  (write! state "[")
  (let [projected (loop [remaining (seq entries)
                         taken 0
                         accumulated []]
                    (if (nil? remaining)
                      accumulated
                      (let [[entry-key entry-value] (first remaining)]
                        (when (pos? taken) (write! state " "))
                        (write! state "[")
                        (let [projected-key (project entry-key state)]
                          (write! state " ")
                          (let [projected-value (project entry-value state)]
                            (write! state "]")
                            (recur (next remaining) (inc taken)
                                   (conj accumulated
                                         [projected-key
                                          projected-value])))))))]
    (write! state "]}")
    projected))

(defn- map-node!
  [state entries]
  (write! state "#:seon.print{:face :seon.print/map, :entries ")
  {::print/face ::print/map ::print/entries (entries! state entries)})

(defn- record-node!
  [state value]
  (let [name* (or (sci-named value) (.getName (class value)))]
    (write! state (str "#:seon.print{:face :seon.print/record, :name "
                       (canonical-edn name*) ", :entries "))
    {::print/face ::print/record
     ::print/name name*
     ::print/entries (entries! state value)}))

(defn- identity-only-node!
  "A registry-declared reference admits its identity, and nothing else."
  [state value]
  (when-let [projection (some-> (:projection state)
                                (schema/identity-only-projection-in value))]
    (let [described (object-node value)]
      (write! state (str "#:seon.print{:face :seon.print/object, :class "
                         (canonical-edn (::print/class described))
                         (when-some [rep (::print/rep described)]
                           (str ", :rep " (canonical-edn rep)))
                         ", :value "))
      (let [child (project (:seon.schema/identity-value projection) state)]
        (write! state "}")
        (assoc described ::print/value child)))))

(defn- throwable-node!
  [state value]
  (write! state "#:seon.print{:face :seon.print/throwable, :value ")
  (let [child (project (Throwable->map value) state)]
    (write! state "}")
    {::print/face ::print/throwable ::print/value child}))

(defn- project-node
  [value state]
  (let [identity-node (delay (identity-only-node! state value))]
    (cond
      (nil? value) (leaf! state (value-node ::print/nil nil))
      (boolean? value) (leaf! state (value-node ::print/boolean value))
      (number? value) (leaf! state (value-node ::print/number value))
      (keyword? value) (leaf! state (value-node ::print/keyword value))
      (symbol? value) (leaf! state (value-node ::print/symbol value))
      (char? value) (leaf! state (value-node ::print/char value))
      (uuid? value) (leaf! state (value-node ::print/uuid value))

      ;; Date is the ordinary inst and must take the allocation-free path.
      ;; Instant is the other core implementation and normalizes to Date;
      ;; the protocol fallback below is reserved for genuinely exotic Inst
      ;; extensions instead of scanning every collection node.
      (instance? java.util.Date value)
      (leaf! state (value-node ::print/inst value))
      (instance? java.time.Instant value)
      (leaf! state (value-node ::print/inst (java.util.Date. (inst-ms value))))

      ;; A STRING IS ADMITTED WHOLE. The character cap that used to clip it
      ;; here was a display decision; the storage bound is what stops a
      ;; runaway string now, and it stops it while the bytes are emitted.
      (string? value)
      (leaf! state {::print/face ::print/string ::print/value value})

      ;; A registry predicate, not a class roster, decides which reference
      ;; values are identities in data. The identity itself re-enters this
      ;; walk; the reference's structural fields never do.
      @identity-node @identity-node

      (instance? Throwable value) (throwable-node! state value)

      (instance? sci.lang.Var value)
      (leaf! state {::print/face ::print/var
                    ::print/name (subs (str value) 2)})

      (instance? sci.lang.Type value)
      (leaf! state {::print/face ::print/type ::print/name (str value)})

      (instance? Class value)
      (leaf! state {::print/face ::print/class
                    ::print/name (.getName ^Class value)})

      ;; reference types and arrays: named, never entered. This is what
      ;; makes a cycle unrepresentable rather than detected.
      (instance? clojure.lang.IDeref value) (leaf! state (object-node value))
      (some-> value class .isArray) (leaf! state (object-node value))

      ;; a record IS map-like; it keeps its fields and the name sci gives it.
      (record? value) (record-node! state value)

      (or (map? value) (instance? java.util.Map value))
      (map-node! state value)

      (or (set? value) (instance? java.util.Set value))
      (items! state value ::print/set)

      (or (vector? value)
          (instance? java.util.RandomAccess value)
          (instance? clojure.lang.MapEntry value))
      (items! state value ::print/vector)

      ;; vectors, lists, lazy and infinite sequences, and host collections
      ;; all become vectors of nodes. NOTHING here counts the source —
      ;; `count` on an infinite sequence never returns, and the storage
      ;; bound is what ends the realization.
      (or (coll? value) (seq? value) (instance? java.util.Collection value))
      (items! state value ::print/list)

      ;; A third party may extend clojure.core/Inst. This intentionally comes
      ;; after every ordinary scalar and collection classification so its
      ;; protocol lookup is paid only for an exotic leaf.
      (inst? value)
      (leaf! state (value-node ::print/inst (java.util.Date. (inst-ms value))))

      ;; a sci type instance that is neither map- nor collection-like
      ;; (a deftype) is named by sci, not by its host class
      :else (leaf! state (object-node value)))))

(defn- project
  "One node: call the interrupt-fn, then project and emit — or mark it.

  A node that fails part-way has already emitted a prefix, so the emitted
  text is rewound to where this node began before the marker replaces it:
  the bytes stored are always the bytes of the node returned."
  [value state]
  ;; EVERY node, because a native lazy sequence enters no interpreted fn
  ;; body and would otherwise realize forever (probed: 200k elements, zero
  ;; interrupt-fn calls)
  ((:interrupt-fn state))
  (let [^StringBuilder builder (:builder state)
        mark (.length builder)
        mark-bytes @(:bytes state)]
    (try
      (project-node value state)
      (catch Throwable failure
        ;; the interrupt is the one throwable admission must not swallow
        ;; resolved at call time because the guarded kernel requires this
        ;; namespace: the owner of "is this sci's interrupt?" sits above
        ;; admission, and admission must not swallow its one throwable
        (when ((requiring-resolve 'seon.sci.kernel/interrupted?) failure)
          (throw failure))
        ;; nor the storage bound: reaching it ends the whole admission
        (when (over-bound? failure)
          (throw failure))
        ;; R41 DECIDES THIS, not local judgement (owner ruling reversing
        ;; the drafted marker-only choice): a value the total codec cannot
        ;; project is a core degradation, so development panics on it
        ;; immediately and production degrades.
        (when (= :panic (:on-core-error state))
          (throw (ex-info (str "value admission could not project a "
                               (.getName (class value)))
                          {:seon.error/kind ::projection-failed
                           ::class (.getName (class value))
                           :seon.sci.admit/projection-failed true}
                          failure)))
        (.setLength builder mark)
        (vreset! (:bytes state) mark-bytes)
        (leaf! state {::print/face ::print/failed
                      ::print/class (.getName (class value))
                      ::print/message (or (ex-message failure)
                                          (.getName (class failure)))})))))

;;; ---------------------------------------------------------------------------
;;; The one operation
;;; ---------------------------------------------------------------------------

(declare semantic-value)

(defn- semantic-entry
  [entry]
  (when (vector? entry)
    [(semantic-value (first entry))
     (semantic-value (second entry))]))

(defn semantic-value
  "Derive the bounded runtime value from one finite print node.

  This never touches the dangerous source a second time."
  {:malli/schema [:=> [:cat :seon.print/node] :any]}
  [print-node]
  (case (::print/face print-node)
    (::print/nil ::print/boolean ::print/number ::print/keyword
     ::print/symbol ::print/char ::print/string ::print/inst ::print/uuid)
    (::print/value print-node)

    ::print/vector (mapv semantic-value (::print/items print-node))
    ::print/list (apply list (map semantic-value (::print/items print-node)))
    ::print/set (set (map semantic-value (::print/items print-node)))

    ::print/map
    (let [entries (::print/entries print-node)
          projected (into {} (keep semantic-entry) entries)]
      (if (some #(not (vector? %)) entries)
        (assoc projected ::elided true)
        projected))

    ::print/record
    (assoc (into {} (keep semantic-entry) (::print/entries print-node))
           ::type (::print/name print-node))

    ::print/var {::reference "sci.lang.Var"
                 ::name (str "#'" (::print/name print-node))}
    ::print/type {::opaque "sci.lang.Type" ::name (::print/name print-node)}
    ::print/class {::opaque "java.lang.Class" ::name (::print/name print-node)}
    ::print/object (if-let [identity-node (::print/value print-node)]
                     (semantic-value identity-node)
                     {::opaque (or (::print/class print-node)
                                   (::print/name print-node))})
    ::print/truncated-string {::truncated-string (::print/value print-node)
                              ::elided true}
    ::print/failed {::opaque (::print/class print-node)
                    ::projection-error (::print/message print-node)}
    ::print/throwable (semantic-value (::print/value print-node))
    (::print/elided ::print/pruned) ::elided))

(def ^:private opaque-result-faces
  ;; A node whose face kept only a name or a class NEVER held the value.
  ;; Ruling 59c: a handle that resolves to a description of a value the agent
  ;; cannot use is worse than no handle, because it answers `(count result/e7)`
  ;; with a lie instead of an unresolved symbol.
  #{::print/var ::print/type ::print/class ::print/object
    ::print/failed ::print/throwable ::print/truncated-string
    ::print/elided ::print/projected ::print/pruned})

(defn- unserializable-root?
  "True when the walk produced no data for the value, only a description.

  A bare object node named a host reference it could not enter, and a failed
  node names a projection that threw. Neither IS the value, so neither is
  stored as one: the evaluation records `:seon.eval/missing :unserializable`
  and the handle is ablated. A reference WITH a registry identity projection
  carries `:seon.print/value` and is faithful, so it is not this."
  [node]
  (let [face (::print/face node)]
    (or (= ::print/failed face)
        (and (= ::print/object face)
             (nil? (::print/value node))))))

(defn restorable-node
  "One settled evaluation's print node, when its value survives the node.

  Nil for a node that kept only a name, for an unreadable node, and for an
  evaluation that stored none. The question `is this value reachable again?`
  is asked HERE, of the node itself, so nobody has to remember the answer in
  a flag beside it. There is no second question about windows any more: a
  value is stored faithfully or it is missing, and a missing evaluation
  stores no node at all."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :map]]}
  [serialized]
  (when (string? serialized)
    (let [node (try (edn/read-string serialized) (catch Throwable _ nil))]
      (when (and (map? node)
                 (::print/face node)
                 (not (contains? opaque-result-faces (::print/face node))))
        node))))

(defn result-handle
  "The symbol naming one stored evaluation's value: `result/e<entity id>`.

  The handle is derived from the evaluation's OWN identity, so two runs of one
  agent can never mint the same name for two values (ruling 69 as amended
  2026-09-07). An evaluation that never persisted has no entity id and
  therefore no handle at all."
  {:malli/schema [:=> [:cat :int] :qualified-symbol]}
  [entity-id]
  (symbol "result" (str "e" entity-id)))

(defn print-node-edn
  "Return canonical readable EDN for one admitted print node.

  The result is independent of ambient REPL print bindings. The print node is
  already finite, so no print cap is needed at this sink. Admission itself
  does NOT come through here — it emits its EDN while it walks, which is how
  the storage bound stops an unbounded source — so this is for callers that
  hold a finished node and want its bytes."
  {:malli/schema [:=> [:cat :seon.print/node]
                  :seon.cluster.eval/result-edn]}
  [print-node]
  (canonical-edn print-node))

(declare admit-walk)

(defn- missing-bound-refusal
  "Absence of the one bound is a refusal that NAMES it, never a cast of nil.

  The class this ends: `admit*` read a cap straight into a `long`, so a caller
  that handed an incomplete caps map got `RT.longCast` on nil instead of a
  diagnostic naming the absent key
  (docs/seon/issues/absent-admission-cap-crashes-the-print-walk.md)."
  [caps]
  {:seon.error/kind ::missing-bound
   :seon.error/message
   (str "Value admission requires the storage bound "
        :seon.config.eval.result/max-bytes
        ", and the supplied caps map does not carry it. Nothing was walked.")
   :seon.error/diagnostic-member :seon.config.eval.result/max-bytes
   :seon.error/data {:seon.sci.admit/caps (vec (sort (keys caps)))}})

(defn- admit*
  [{::keys [value interrupt-fn caps record unbounded?]
    supplied-projection :seon.schema/projection
    on-core-error :seon.config/on-core-error}]
  (if (and (not (true? unbounded?))
           (not (int? (:seon.config.eval.result/max-bytes caps))))
    (missing-bound-refusal caps)
    (admit-walk value interrupt-fn caps record unbounded?
                supplied-projection on-core-error)))

(defn- admit-walk
  [value interrupt-fn caps record unbounded?
   supplied-projection on-core-error]
  (let [projection (or supplied-projection (schema/handed-projection))
        builder (StringBuilder.)
        state {:interrupt-fn interrupt-fn
               :on-core-error on-core-error
               ;; Identity projection is handed by the operation boundary.
               ;; Ordinary scalar/collection admissions never require it.
               :projection projection
               :builder builder
               :bytes (volatile! 0)
               ;; THE ONE BOUND. `unbounded?` is the caller that is not
               ;; storing anything and has already bounded its own source.
               :max-bytes (when-not (true? unbounded?)
                            (long (:seon.config.eval.result/max-bytes caps)))}
        answer
        (try
          (let [print-node (project value state)]
            (if (unserializable-root? print-node)
              {:seon.eval/missing :unserializable}
              {::print-node print-node
               ::value (semantic-value print-node)
               :seon.cluster.eval/result-edn (str builder)}))
          (catch clojure.lang.ExceptionInfo failure
            (if (over-bound? failure)
              {:seon.eval/missing :over-bound
               :seon.eval/size (:seon.eval/size (ex-data failure))}
              (throw failure))))]
    (cond-> answer
      ;; absent in, absent out — never a stored nil
      record (assoc ::record record))))

(defn admit-value
  "Realize and bound one value without constructing storage EDN.

  This is the shared admission operation for guarded invocations and literal
  render declarations. It walks the source exactly once and returns the same
  print node, semantic value, and optional diagnostics as `admit` — or, when
  the value went over the storage bound or could not be projected into data
  at all, nothing but `:seon.eval/missing` and the size that names why."
  {:malli/schema
   [:=> [:cat :seon.sci.admit/request] :seon.sci.admit/admitted-value]}
  [request]
  (dissoc (admit* request) :seon.cluster.eval/result-edn))

(defn admit
  "Realize and bound one value leaving a sci evaluation. ONE pass.
  Call this INSIDE the armed boundary, before disarm — that placement
  is the contract, not a convention: after disarm there is no time
  limit left to stop an infinite realization.

  Walks `::value` once, calling `::interrupt-fn` at EVERY node and emitting
  the value's EDN as it goes, and returns either

      {::value        <the projection>
       ::print-node   <its print node>
       :seon.cluster.eval/result-edn <the bytes it emitted>
       ::record       <the diagnostics, unchanged>}

  or, for a value that reached `:seon.config.eval.result/max-bytes` or that
  the walk could only describe,

      {:seon.eval/missing :over-bound|:unserializable
       :seon.eval/size    <bytes reached, for the bound>
       ::record           <the diagnostics, unchanged>}

  The projection's grammar is the namespace docstring's total codec. There is
  no `capped?` signal, because there is nothing between whole and missing: a
  reader never has to guess whether an elision marker was the agent's own
  data.

  `::record` is returned IDENTICAL to the one supplied: admission
  carries `:seon.eval/fn-entries` and `:seon.eval/allocated-bytes`
  through untouched, because dropping them is precisely the quarry
  defect this package exists to end (`driver.clj:160-173`). It is
  OPTIONAL (seal revision, 2026-07-27): the diagnostics are eval-shaped
  and admission now has a caller that is not an eval —
  `seon.error/normalize` runs an arbitrary error source through this
  same codec — so an absent record stays absent rather than becoming a
  zeroed measurement nobody took.

  Never throws for a value it cannot project — that node becomes a
  marker. The one throwable it deliberately does NOT catch is sci's
  uncatchable interrupt, which must reach `evaluate`."
  {:malli/schema [:=> [:cat :seon.sci.admit/request] :seon.sci.admit/admitted]}
  [request]
  (admit* request))
