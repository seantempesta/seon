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
  nothing but `:seon.sci.admit/reason` and, for the bound, `:seon.sci.admit/bytes`.
  There is no window, no page, and no `capped?` flag: a caller never has to
  ask whether the thing it holds is the whole value.

  `admit-value` returns that answer without the serialized EDN key; `admit`
  adds `:seon.sci.admit/edn` for storage callers. Admission
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
            [seon.env :as env]
            [seon.error.refusal :as error]
            [seon.id :as id]
            [seon.print :as print]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

;;; LOAD-CYCLE BOUNDARY. `seon.sci.kernel` requires `seon.sci.admit`, so this
;;; namespace cannot require it back. One resolution, realized at first use,
;;; instead of a `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private sci-kernel-interrupted?
  (delay (requiring-resolve 'seon.sci.kernel/interrupted?)))

(defn interrupt-fn?
  "True for the zero-argument fn sci calls on every fn-body entrance.
  Admission is HANDED this fn; it never builds one, never owns the
  timer, and never decides when it fires — it only guarantees that a
  realization step cannot proceed without calling it."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
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
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The canonical EDN encoder accepts arbitrary Clojure data and represents unsupported host values through admission.", :gen/elements [nil false 0 "" :k [] {}]}]] :string]}
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

(defn- write!
  "Emit one fragment, or stop the walk because the storage bound is reached.

  The bound is checked BEFORE the fragment lands, so nothing past it is ever
  built. The size reported is the bytes REACHED — everything emitted plus the
  fragment that crossed the bound — because `:seon.sci.admit/bytes` is declared as a
  measurement, and a diagnostic that reads as a measurement and is a constant
  is the one thing a diagnostic may not be."
  [state ^String text]
  (let [bound (:max-bytes state)
        emitted (long @(:bytes state))
        total (+ emitted (utf8-length text))]
    (when (and bound (> total (long bound)))
      (throw (ex-info "value admission reached its storage bound"
                      {:seon.error/at (java.util.Date.)
                        :seon.error/layer :seon.sci.admit/projection
                        :seon.error/operation 'seon.sci.admit/write!
                        :seon.error/message "Value admission reached its byte bound; request a smaller value or a larger declared bound."
                        :seon.error/offending total
                        ::bound-bytes bound
                        ::bytes total
                        ::projection-observation {:seon.error.evidence/attribute ::bytes
                                                  :seon.error.evidence/value total}
                        :seon.error/member :seon.config.eval.result/max-bytes})))
    (vreset! (:bytes state) total)
    (.append ^StringBuilder (:builder state) text)
    nil))

(defn- over-bound?
  [failure]
  (contains? (ex-data failure) :seon.sci.admit/bound-bytes))

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

(defn record-name
  "The declared SCI or JVM name of a record value."
  {:malli/schema [:=> [:cat :map] :string]}
  [value]
  (or (sci-named value) (.getName (class value))))

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

(defn- leaf!
  "Emit one node that has no child node: its own canonical EDN, exactly."
  [state node]
  (write! state (canonical-edn node))
  node)

;;; The walk is ITERATIVE. Every container it opens becomes a frame on an
;;; explicit stack, and its children are handed back to the loop instead of
;;; being visited by a nested call. JVM recursion here made the CALL STACK the
;;; real depth bound: a value nested ~2000 deep threw `StackOverflowError` out
;;; of `admit` under the production dial, at the one boundary law 2.4 requires
;;; to answer with a value. Depth is now bounded by the storage bound alone.

(defn- items-frame
  "Emit `#:seon.print{:face F, :items [` and return the frame that finishes it."
  [state values face]
  (write! state (str "#:seon.print{:face " face ", :items ["))
  {::kind ::items ::face face ::remaining (seq values) ::acc []})

(defn- open-node
  "Project one value: emit a finished leaf, or a container's prefix.

  Returns `{::node n}` when the value is complete after this call, and
  `{::frame f}` when the loop must still visit its children — the frame
  carries what remains and how to close it."
  [value state]
  (let [identity-projection
        (delay (some-> (or (:seon.schema/projection (meta value))
                            (:projection state))
                       (schema/identity-only-projection-in value)))]
    (cond
      (nil? value) {::node (leaf! state (value-node ::print/nil nil))}
      (boolean? value) {::node (leaf! state (value-node ::print/boolean value))}
      (number? value) {::node (leaf! state (value-node ::print/number value))}
      (keyword? value) {::node (leaf! state (value-node ::print/keyword value))}
      (symbol? value) {::node (leaf! state (value-node ::print/symbol value))}
      (char? value) {::node (leaf! state (value-node ::print/char value))}
      (uuid? value) {::node (leaf! state (value-node ::print/uuid value))}

      ;; Date is the ordinary inst and must take the allocation-free path.
      ;; Instant is the other core implementation and normalizes to Date;
      ;; the protocol fallback below is reserved for genuinely exotic Inst
      ;; extensions instead of scanning every collection node.
      (instance? java.util.Date value)
      {::node (leaf! state (value-node ::print/inst value))}
      (instance? java.time.Instant value)
      {::node (leaf! state (value-node ::print/inst
                                       (java.util.Date. (inst-ms value))))}

      ;; A STRING IS ADMITTED WHOLE. The character cap that used to clip it
      ;; here was a display decision; the storage bound is what stops a
      ;; runaway string now, and it stops it while the bytes are emitted.
      (string? value)
      {::node (leaf! state {::print/face ::print/string ::print/value value})}

      ;; A registry predicate, not a class roster, decides which reference
      ;; values are identities in data. The identity itself re-enters this
      ;; walk; the reference's structural fields never do.
      @identity-projection
      (let [described (object-node value)]
        (write! state (str "#:seon.print{:face :seon.print/object, :class "
                           (canonical-edn (::print/class described))
                           (when-some [rep (::print/rep described)]
                             (str ", :rep " (canonical-edn rep)))
                           ", :value "))
        {::frame {::kind ::identity
                  ::described described
                  ::child (:seon.schema/identity-value @identity-projection)}})

      (instance? Throwable value)
      (do (write! state "#:seon.print{:face :seon.print/throwable, :value ")
          {::frame {::kind ::throwable ::child (Throwable->map value)}})

      ;; A VAR IS ITS NAME, in either world. sci's Vars and the host's are
      ;; the same fact to a reader, and admitting the host's as a bare
      ;; `#object[clojure.lang.Var]` threw away the one thing it carries —
      ;; which then read as an unserializable value rather than as `#'foo`.
      (or (instance? sci.lang.Var value) (instance? clojure.lang.Var value))
      {::node (leaf! state {::print/face ::print/var
                            ::print/name (subs (str value) 2)})}

      (instance? sci.lang.Type value)
      {::node (leaf! state {::print/face ::print/type
                            ::print/name (str value)})}

      (instance? Class value)
      {::node (leaf! state {::print/face ::print/class
                            ::print/name (.getName ^Class value)})}

      ;; reference types and arrays: named, never entered. This is what
      ;; makes a cycle unrepresentable rather than detected.
      (instance? clojure.lang.IDeref value)
      {::node (leaf! state (object-node value))}
      (some-> value class .isArray)
      {::node (leaf! state (object-node value))}

      ;; a record IS map-like; it keeps its fields and the name sci gives it.
      (record? value)
      (let [name* (record-name value)]
        (write! state (str "#:seon.print{:face :seon.print/record, :name "
                           (canonical-edn name*) ", :entries "))
        (write! state "[")
        {::frame {::kind ::entries ::face ::print/record ::name name*
                  ::remaining (seq value) ::acc [] ::phase ::entry}})

      (or (map? value) (instance? java.util.Map value))
      (do (write! state "#:seon.print{:face :seon.print/map, :entries ")
          (write! state "[")
          {::frame {::kind ::entries ::face ::print/map
                    ::remaining (seq value) ::acc [] ::phase ::entry}})

      (or (set? value) (instance? java.util.Set value))
      {::frame (items-frame state value ::print/set)}

      (or (vector? value)
          (instance? java.util.RandomAccess value)
          (instance? clojure.lang.MapEntry value))
      {::frame (items-frame state value ::print/vector)}

      ;; vectors, lists, lazy and infinite sequences, and host collections
      ;; all become vectors of nodes. NOTHING here counts the source —
      ;; `count` on an infinite sequence never returns, and the storage
      ;; bound is what ends the realization.
      (or (coll? value) (seq? value) (instance? java.util.Collection value))
      {::frame (items-frame state value ::print/list)}

      ;; A third party may extend clojure.core/Inst. This intentionally comes
      ;; after every ordinary scalar and collection classification so its
      ;; protocol lookup is paid only for an exotic leaf.
      (inst? value)
      {::node (leaf! state (value-node ::print/inst
                                       (java.util.Date. (inst-ms value))))}

      ;; a sci type instance that is neither map- nor collection-like
      ;; (a deftype) is named by sci, not by its host class
      :else {::node (leaf! state (object-node value))})))

(defn- frame-deliver
  "Record one finished child on its frame."
  [frame child]
  (case (::kind frame)
    ::items (update frame ::acc conj child)
    ::entries (case (::phase frame)
                ::key (assoc frame ::pending-key child ::phase ::value)
                ::value (-> frame
                            (update ::acc conj [(::pending-key frame) child])
                            (dissoc ::pending-key)
                            (assoc ::phase ::close-entry)))
    (::identity ::throwable) (assoc frame ::acc child)))

(defn- frame-advance
  "Deliver one finished child to its frame, then open the next or close.

  Returns `[::open value frame]` while children remain and `[::done node]`
  when the container is complete. The source sequence is realized HERE, so a
  realization that throws or blocks belongs to the frame that asked for it —
  the same attribution the recursive walk had."
  [frame child state]
  (loop [frame (cond-> frame (some? child) (frame-deliver child))]
    (case (::kind frame)
      ::items
      (if-some [remaining (::remaining frame)]
        (do (when (pos? (count (::acc frame))) (write! state " "))
            [::open (first remaining)
             (assoc frame ::remaining (next remaining))])
        (do (write! state "]}")
            [::done {::print/face (::face frame)
                     ::print/items (::acc frame)}]))

      ::entries
      (condp = (::phase frame)
        ::entry
        (if-some [remaining (::remaining frame)]
          (let [entry (first remaining)]
            (when (pos? (count (::acc frame))) (write! state " "))
            (write! state "[")
            [::open (key entry)
             (assoc frame
                    ::remaining (next remaining)
                    ::entry-value (val entry)
                    ::phase ::key)])
          (do (write! state "]}")
              [::done (cond-> {::print/face (::face frame)
                               ::print/entries (::acc frame)}
                        (::name frame) (assoc ::print/name (::name frame)))]))

        ::value
        (do (write! state " ")
            [::open (::entry-value frame) frame])

        ::close-entry
        (do (write! state "]")
            (recur (-> frame (dissoc ::entry-value) (assoc ::phase ::entry)))))

      ::identity
      (if (contains? frame ::acc)
        (do (write! state "}")
            [::done (assoc (::described frame) ::print/value (::acc frame))])
        [::open (::child frame) frame])

      ::throwable
      (if (contains? frame ::acc)
        (do (write! state "}")
            [::done {::print/face ::print/throwable
                     ::print/value (::acc frame)}])
        [::open (::child frame) frame]))))

(defn- failed-node!
  "Replace one node's emitted prefix with the marker that names its failure."
  [state mark mark-bytes value failure]
  (let [^StringBuilder builder (:builder state)]
    (.setLength builder (long mark))
    (vreset! (:bytes state) mark-bytes)
    (leaf! state {::print/face ::print/failed
                  ::print/class (.getName (class value))
                  ::print/message (or (ex-message failure)
                                      (.getName (class failure)))})))

(defn- rethrow-or-degrade!
  "The two throwables admission must never swallow, then R41's one dial."
  [state value failure]
  ;; the interrupt is the one throwable admission must not swallow
  ;; resolved at call time because the guarded kernel requires this
  ;; namespace: the owner of "is this sci's interrupt?" sits above
  ;; admission, and admission must not swallow its one throwable
  (when (@sci-kernel-interrupted? failure)
    (throw failure))
  ;; nor the storage bound: reaching it ends the whole admission
  (when (over-bound? failure)
    (throw failure))
  ;; R41 DECIDES THIS, not local judgement (owner ruling reversing
  ;; the drafted marker-only choice): a value the total codec cannot
  ;; project is a core degradation, so development panics on it
  ;; immediately and production degrades.
  (when (= :panic (:on-core-error state))
    (throw (ex-info "Value admission could not project the supplied value."
                    {:seon.error/at (java.util.Date.)
                      :seon.error/layer :seon.sci.admit/projection
                      :seon.error/operation 'seon.sci.admit/rethrow-or-degrade!
                      :seon.error/message "Value admission could not project this value; inspect the codec failure evidence."
                      :seon.error/offending value
                      ::failed-class (symbol (.getName (class value)))
                      :seon.error/data {:seon.error/exception-class (symbol (.getName (class failure)))
                                        :seon.error/throw-site-message (or (ex-message failure) "Projection failed.")}
                      :seon.error/member :seon.sci.admit/value
                      :seon.error/expected :seon.print/node}
                    failure))))

(defn- project
  "Project and emit one value's whole tree, iteratively.

  A node that fails part-way has already emitted a prefix, so the emitted
  text is rewound to where that node began before the marker replaces it:
  the bytes stored are always the bytes of the node returned."
  [root state]
  (let [^StringBuilder builder (:builder state)]
    (loop [stack []
           step [::open root]]
      (case (nth step 0)
        ::open
        (let [value (nth step 1)]
          ;; EVERY node, because a native lazy sequence enters no interpreted
          ;; fn body and would otherwise realize forever (probed: 200k
          ;; elements, zero interrupt-fn calls)
          ((:interrupt-fn state))
          (let [mark (.length builder)
                mark-bytes @(:bytes state)
                outcome (try
                          (open-node value state)
                          (catch Throwable failure
                            (rethrow-or-degrade! state value failure)
                            {::node (failed-node! state mark mark-bytes
                                                  value failure)}))]
            (if-some [node (::node outcome)]
              (recur stack [::close node])
              (recur (conj stack (assoc (::frame outcome)
                                        ::mark mark
                                        ::mark-bytes mark-bytes
                                        ::source value))
                     [::advance nil]))))

        ::advance
        (let [frame (peek stack)
              outcome (try
                        (frame-advance frame (nth step 1) state)
                        (catch Throwable failure
                          (rethrow-or-degrade! state (::source frame) failure)
                          [::done (failed-node! state (::mark frame)
                                                (::mark-bytes frame)
                                                (::source frame) failure)]))]
          (if (= ::open (nth outcome 0))
            (recur (conj (pop stack) (nth outcome 2)) [::open (nth outcome 1)])
            (recur (pop stack) [::close (nth outcome 1)])))

        ::close
        (let [node (nth step 1)]
          (if (empty? stack)
            node
            (recur stack [::advance node])))))))
;;; ---------------------------------------------------------------------------
;;; The one operation
;;; ---------------------------------------------------------------------------

(defn- semantic-leaf
  "The runtime value of one print node that has no child node."
  [print-node]
  (case (::print/face print-node)
    (::print/nil ::print/boolean ::print/number ::print/keyword
     ::print/symbol ::print/char ::print/string ::print/inst ::print/uuid)
    (::print/value print-node)

    ::print/var {::reference "sci.lang.Var"
                 ::name (str "#'" (::print/name print-node))}
    ::print/type {::opaque "sci.lang.Type" ::name (::print/name print-node)}
    ::print/class {::opaque "java.lang.Class" ::name (::print/name print-node)}
    ::print/object {::opaque (or (::print/class print-node)
                                 (::print/name print-node))}
    ::print/truncated-string {::truncated-string (::print/value print-node)
                              ::elided true}
    ::print/failed {::opaque (::print/class print-node)
                    ::projection-error (::print/message print-node)}
    (::print/elided ::print/pruned) print-node))

(defn- semantic-parts
  "One node's child nodes and how they rebuild it, or nil when it is a leaf.

  Non-pair entries retain the complete elision data alongside map members."
  [node]
  (case (::print/face node)
    (::print/vector ::print/list ::print/set)
    [(vec (::print/items node)) nil]

    (::print/map ::print/record)
    (let [entries (::print/entries node)
          pairs (filterv vector? entries)]
      [(into [] (mapcat (fn [entry] [(first entry) (second entry)])) pairs)
       {::elisions (filterv (complement vector?) entries)}])

    ::print/throwable [[(::print/value node)] nil]

    ;; a reference WITH a registry identity projection carries that identity
    ;; as its one child; a bare object node is a leaf naming a class
    ::print/object (when-some [identity-node (::print/value node)]
                     [[identity-node] nil])

    nil))

(defn- semantic-combine
  [node children shape]
  (case (::print/face node)
    ::print/vector (vec children)
    ::print/list (apply list children)
    ::print/set (set children)
    (::print/map ::print/record)
    (cond-> (into {} (map vec) (partition 2 children))
      (seq (::elisions shape)) (assoc ::elided true
                                     ::print/elisions (::elisions shape))
      (= ::print/record (::print/face node)) (assoc ::type (::print/name node)))
    (::print/throwable ::print/object) (first children)))

(defn semantic-value
  "Derive the bounded runtime value from one finite print node.

  This never touches the dangerous source a second time, and it walks the
  node ITERATIVELY for the same reason `project` does: a value admission
  accepted must be a value this can rebuild, and a node deep enough to
  exhaust the JVM stack would otherwise turn a stored result into an
  `Error` thrown out of a total operation."
  {:malli/schema
   [:=> [:cat :seon.print/node]
    [:or
     [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary,
            :seon.schema.admission/reason "A print node represents an arbitrary original Clojure value; semantic decoding preserves its scalar or collection shape.",
            :gen/elements [nil false 0 "" :k [] {}]}]
     :seon.error/base
     :seon.await/timeout-error :seon.await/closed-error
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
     :seon.problems/error :seon.program/error :seon.program/declaration-refused-error :seon.program/no-declaration-at-error :seon.program/binding-error :seon.program/signature-error :seon.reconcile/error
     :seon.render/request-error :seon.render.transcript/request-error
     :seon.render.walk/elided-error :seon.render.value/window-failed-error
     :seon.render/invalid-output-error :seon.render.hiccup/unparseable-tag-error
     :seon.render/ambiguous-error :seon.render.data/no-such-path-error
     :seon.cluster.process/start-instant-unavailable-error
     :seon.render.web/value-unreadable-error :seon.render.web/missing-port-error
     :seon.render.walk/no-such-entity-error :seon.dev.mcp/projection-failed-error
     :seon.render/walk-failed-error :seon.dev.mcp/jvm-exception-error
     :seon.render.web/value-not-found-error :seon.render.value/missing-root-identity-error
     :seon.render/unknown :seon.render.web/function-unavailable-error
     :seon.render.data/observation-error :seon.render.value/window-realization-failed-error
     :seon.render.web/request-error :seon.render.lint/absent-element-error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.admit/projection-failed-error :seon.sci.eval/acquisition-error :seon.sci.eval/row-acquisition-error :seon.sci.eval/reader-event-count-error :seon.sci.eval/missing-function-row-error :seon.sci.eval/schema-refused-error :seon.sci.eval/documentation-unavailable-error :seon.sci.eval/namespace-binding-cycle-error :seon.sci.eval/declaration-absent-error :seon.sci.eval/install-mismatch-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error :seon.sci.reader/unreadable-error :seon.sci.reader/refused-tag-error :seon.sci.reader/oversize-error :seon.sci.reader/fabricated-response-error
     :seon.test/admission-error :seon.test/execution-error :seon.test/expired
     :seon.test/not-runnable-error :seon.test/resolution-error
     :seon.test/selection-error :seon.test/unknown-error
     :seon.test.run/immutable-error :seon.test.run/unavailable-error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error :seon.turn/refused-error
     :seon.turn.loop/error]]}
  [print-node]
  (loop [stack []
         step [::open print-node]]
    (case (nth step 0)
      ::open
      (let [node (nth step 1)]
        (if-some [[children shape] (semantic-parts node)]
          (recur (conj stack {::node node ::shape shape
                              ::remaining (seq children) ::acc []})
                 [::advance])
          (recur stack [::close (semantic-leaf node)])))

      ::advance
      (let [frame (peek stack)
            frame (cond-> frame
                    (= 2 (count step)) (update ::acc conj (nth step 1)))]
        (if-some [remaining (::remaining frame)]
          (recur (conj (pop stack) (assoc frame ::remaining (next remaining)))
                 [::open (first remaining)])
          (recur (pop stack)
                 [::close (semantic-combine (::node frame) (::acc frame)
                                            (::shape frame))])))

      ::close
      (let [value (nth step 1)]
        (if (empty? stack)
          value
          (recur stack [::advance value]))))))

(defn missing-marker
  "The `:seon.sci.admit/reason` answer one admission gave, alone, or nil.

  ONE CONSTRUCTOR for the marker every surface reports an absent value with:
  the REPL response, a fault fact's evidence, and any caller that has to say
  why it is holding nothing. Nil when the admission kept the value."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [admitted]
  (when (keyword? (:seon.sci.admit/reason admitted))
    (select-keys admitted [:seon.sci.admit/reason :seon.sci.admit/bytes])))

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
  stored as one: the evaluation records `:seon.sci.admit/reason :unserializable`
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
  "The symbol naming one evaluation's value: `result/e<evaluation id>`.

  The handle is derived from the evaluation's OWN identity, so two runs of one
  agent can never mint the same name for two values (ruling 69 as amended
  2026-09-07). An evaluation that never persisted has no identity and
  therefore no handle at all."
  {:malli/schema [:=> [:cat :seon.cluster.eval/id] :qualified-symbol]}
  [evaluation-id]
  (id/symbol-in "result" \e evaluation-id))

(defn print-node-edn
  "Return canonical readable EDN for one admitted print node.

  The result is independent of ambient REPL print bindings. The print node is
  already finite, so no print cap is needed at this sink. Admission itself
  does NOT come through here — it emits its EDN while it walks, which is how
  the storage bound stops an unbounded source — so this is for callers that
  hold a finished node and want its bytes."
  {:malli/schema [:=> [:cat :seon.print/node]
                  :seon.sci.admit/edn]}
  [print-node]
  (canonical-edn print-node))

(declare admit-walk)

(defn- missing-bound-refusal
  "Absence of the one bound is a refusal that NAMES it, never a cast of nil.

  The class this ends: `admit*` read a cap straight into a `long`, so a caller
  that handed an incomplete caps map got `RT.longCast` on nil instead of a
  diagnostic naming the absent key
  (docs/seon/issues/absent-admission-cap-crashes-the-print-walk.md)."
  {:malli/schema [:=> [:cat :map] :seon.config/error]}
  [caps]
  {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.sci.admit/projection
    :seon.error/operation 'seon.sci.admit/missing-bound-refusal
    :seon.error/message "Value admission requires a byte bound; supply the declared max-bytes attribute."
    :seon.error/offending caps
    :seon.config/error-key :seon.config.eval.result/max-bytes
    :seon.error/expected :seon.sci.admit/bound-bytes})

(defn required-cap
  "One declared cap as a long, or a core fault NAMING the key that is absent.

  Every bound in this system is a declared fact, and a bound that reads as
  absent must say so: the five bare `(long (:some-cap caps))` sites this
  replaces answered `RT.longCast` on nil — a `NullPointerException` naming
  nothing, at seams whose whole job is to bound work. Admission's own storage
  bound stays a flat refusal because `admit` is a value-returning operation;
  these callers are private core walk code inside a total render boundary, so
  an absent declared cap is a core fault with provenance rather than a value
  every one of them would have to branch on."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :int]}
  ^long [caps cap-key]
  (let [declared (get caps cap-key)]
    (if (int? declared)
      (long declared)
      (throw (ex-info
              "A declared bound is missing; supply the required cap."
              {:seon.error/at (java.util.Date.)
                :seon.error/layer :seon.sci.admit/projection
                :seon.error/operation 'seon.sci.admit/required-cap
                :seon.error/message "A declared bound is missing; supply the required cap."
                :seon.error/offending caps
                :seon.config/error-key cap-key
                :seon.error/expected :int})))))

(defn- admit*
  {:malli/schema [:=> [:cat :seon.sci.admit/request] [:or :seon.sci.admit/admitted :seon.config/error]]}
  [{::keys [value interrupt-fn caps record unbounded?]
    supplied-projection :seon.schema/projection
    on-core-error :seon.config/on-core-error
    :as request}]
  (let [supplied-projection
        (or (:seon.schema/projection (meta value))
            supplied-projection
            (:seon.schema/projection (env/of request))
            (:seon.schema/projection (env/of (:seon.sci.eval/ctx request)))
            (:seon.schema/projection (meta (:seon.db/db request))))]
    (if (and (not (true? unbounded?))
           (not (int? (:seon.config.eval.result/max-bytes caps))))
    (missing-bound-refusal caps)
    (admit-walk value interrupt-fn caps record unbounded?
                supplied-projection on-core-error))))

(defn- admit-walk
  [value interrupt-fn caps record unbounded?
   supplied-projection on-core-error]
  (let [projection supplied-projection
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
              {:seon.sci.admit/reason :unserializable}
              {::print-node print-node
               ::value (semantic-value print-node)
               :seon.sci.admit/edn (str builder)}))
          (catch clojure.lang.ExceptionInfo failure
            (if (over-bound? failure)
              {:seon.sci.admit/reason :over-bound
               :seon.sci.admit/bytes (:seon.sci.admit/bytes (ex-data failure))}
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
  at all, nothing but `:seon.sci.admit/reason` and the size that names why."
  {:malli/schema
   [:=> [:cat :seon.sci.admit/request] [:or :seon.sci.admit/admitted-value :seon.config/error]]}
  [request]
  (dissoc (admit* request) :seon.sci.admit/edn))

(defn admit
  "Realize and bound one value leaving a sci evaluation. ONE pass.
  Call this INSIDE the armed boundary, before disarm — that placement
  is the contract, not a convention: after disarm there is no time
  limit left to stop an infinite realization.

  Walks `::value` once, calling `::interrupt-fn` at EVERY node and emitting
  the value's EDN as it goes, and returns either

      {::value        <the projection>
       ::print-node   <its print node>
       :seon.sci.admit/edn <the bytes it emitted>
       ::record       <the diagnostics, unchanged>}

  or, for a value that reached `:seon.config.eval.result/max-bytes` or that
  the walk could only describe,

      {:seon.sci.admit/reason :over-bound|:unserializable
       :seon.sci.admit/bytes    <bytes reached, for the bound>
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
  {:malli/schema [:=> [:cat :seon.sci.admit/request] [:or :seon.sci.admit/admitted :seon.config/error]]}
  [request]
  (admit* request))

(defn admit-partitioned
  "Admit a value whole, or retain a supplied priority map before its remainder.

  This is the fault-evidence boundary: ordinary stored values remain whole or
  missing. When the complete value is missing under its byte bound, the caller
  may supply the small classifying facts that must remain queryable. They are
  admitted first as ordinary data beside the original missing marker. If that
  partition itself cannot fit, the honest missing answer is returned."
  {:malli/schema [:=> [:cat :seon.sci.admit/request :map]
                  [:or :seon.sci.admit/admitted :seon.config/error]]}
  [request priority]
  (let [admitted (admit request)]
    (if-let [marker (missing-marker admitted)]
      (let [partitioned (admit (assoc request
                                      ::value
                                      (assoc priority ::remainder marker)))]
        (if (missing-marker partitioned)
          admitted
          (assoc partitioned ::remainder marker)))
      admitted)))
