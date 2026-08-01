(ns seon.sci.admit
  "Projects values leaving SCI into bounded, printable ordinary data.

  `admit` walks the supplied value once and calls the supplied
  `:interrupt-fn` before projecting every node. Every result is an
  unambiguous `:seon.print/face` envelope in the closed print grammar;
  authored print keywords remain ordinary child data. Maps, sets,
  records, sequences, and host collections are rebuilt within the
  configured depth, width, string, and node caps. Reference types and
  arrays are never entered.

  The returned value and its `:seon.cluster.eval/result-edn` string are
  projections of the same bounded data. Admission preserves supplied
  evaluation diagnostics and reports whether anything was capped. SCI
  interrupts propagate. Other projection failures panic or degrade to
  markers according to `:seon.config/on-core-error`. Admission opens no
  resources and writes no durable state."
  (:require [clojure.test.check.generators :as gen]
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
;;; Schemas — src/seon/schema/admit.edn
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
;;; The walk — one pass, inside the armed boundary
;;; ---------------------------------------------------------------------------

(defn- node
  [face]
  {::print/face face})

(defn- value-node
  [face value]
  {::print/face face
   ::print/value value})

(defn- sci-named
  "The name sci itself reports for a value it defined, or nil."
  [value]
  (when (instance? sci.impl.types.SciTypeInstance value)
    (str (sci.types/-get-type value))))

(declare safe-description)

(defn- class-name
  [value]
  (let [class-name* (.getName (class value))]
    (if (ifn? value)
      (sci.namespaces/demunge class-name*)
      class-name*)))

(defn- object-node
  [value caps]
  (let [description (safe-description value caps)
        description (if (and description (ifn? value))
                      (sci.namespaces/demunge description)
                      description)]
    (cond-> {::print/face ::print/object
             ::print/class (class-name value)
             ::print/address (format "0x%x" (System/identityHashCode value))}
      description (assoc ::print/rep (pr-str description)))))

(defn- take-node!
  "Consume one node from the budget; false when it is exhausted."
  [state]
  (if (pos? @(:nodes state))
    (do (vswap! (:nodes state) dec) true)
    (do (vreset! (:capped? state) true) false)))

(defn- flag!
  "Record that something was elided or truncated."
  [state]
  (vreset! (:capped? state) true)
  nil)

(defn- elide!
  "Mark this node elided. A scalar, deliberately: an elision must never
  be a structure, or the thing that replaces an over-deep value would
  itself be over-deep."
  [state]
  (vreset! (:capped? state) true)
  (node ::print/elided))

(defn- prune!
  [state]
  (vreset! (:capped? state) true)
  (node ::print/pruned))

(defn- safe-description
  "A bounded `str` of `value`, when taking one cannot hurt.
  Reference types are never dereferenced and sequential things are
  never realized — `str` on a lazy sequence walks it, and a host call
  cannot be interrupted. Anything else gets one truncated toString,
  guarded, because a description that throws is not a description."
  [value caps]
  (when-not (or (instance? clojure.lang.IDeref value)
                (instance? clojure.lang.Seqable value)
                (instance? java.util.Collection value)
                (instance? clojure.lang.IPending value)
                (some-> value class .isArray))
    (try
      (let [described (str value)
            limit (:seon.config.eval.result/max-string caps)]
        (if (<= (count described) limit)
          described
          nil))
      (catch Throwable _ nil))))

(declare project)

(defn- append-elision!
  "Append the scalar cut marker, charging its one node."
  [state accumulated emit]
  (flag! state)
  (if (take-node! state)
    (emit accumulated (node ::print/elided))
    ;; No child can fit. Replacing this collection node with the scalar
    ;; is the only honest projection that still obeys the node cap.
    (elide! state)))

(defn- project-entries
  "Project up to `width` children, stopping when the node budget runs out.
  `emit` receives each child's projection. Truncation — by width or by
  budget — is capping, and says so."
  [values width depth state emit]
  (loop [remaining (seq values)
         taken 0
         accumulated (emit)]
    (cond
      (nil? remaining) accumulated
      (zero? @(:nodes state)) (elide! state)
      :else
      (let [after (next remaining)
            cut-for-width? (and after (>= (inc taken) width))
            cut-for-nodes? (and after (= 1 @(:nodes state)))]
        (if (or cut-for-width? cut-for-nodes?)
          (append-elision! state accumulated emit)
          ;; When siblings remain, hold one node aside for their cut marker.
          ;; A nested child may consume everything else, but it can never
          ;; silently erase the parent's remaining siblings.
          (let [reserved? (some? after)]
            (when reserved? (vswap! (:nodes state) dec))
            (take-node! state)
            (let [child (project (first remaining) depth state)]
              (when reserved? (vswap! (:nodes state) inc))
              (recur after
                     (inc taken)
                     (emit accumulated child)))))))))

(defn- mark-map-cut!
  [state accumulated]
  (flag! state)
  (if (take-node! state)
    (conj accumulated (node ::print/elided))
    (elide! state)))

(defn- project-map
  [entries width depth state]
  (loop [remaining (seq entries)
         taken 0
         accumulated []]
    (cond
      (nil? remaining) accumulated
      (< @(:nodes state) 2) (elide! state)
      :else
      (let [after (next remaining)
            cut-for-width? (or (>= taken width)
                               (and after (>= (inc taken) width)))
            cut-for-nodes? (and after (< @(:nodes state) 3))]
        (if (or cut-for-width? cut-for-nodes?)
          (mark-map-cut! state accumulated)
          (let [[entry-key entry-value] (first remaining)
                reserved? (some? after)]
            (when reserved? (vswap! (:nodes state) dec))
            ;; a map entry is TWO nodes, and a half-projected entry is not an
            ;; entry: take both or neither
            (take-node! state)
            (take-node! state)
            (let [projected-key (project entry-key depth state)
                  projected-value (project entry-value depth state)]
              (when reserved? (vswap! (:nodes state) inc))
              (recur after
                     (inc taken)
                     (conj accumulated [projected-key projected-value])))))))))

(defn- project-node
  [value depth state]
  (let [{:keys [:seon.config.eval.result/max-depth
                :seon.config.eval.result/max-collection
                :seon.config.eval.result/max-string]}
        (:caps state)
        deep? (>= depth max-depth)
        child-depth (inc depth)]
    (cond
      (nil? value) (value-node ::print/nil nil)
      (boolean? value) (value-node ::print/boolean value)
      (number? value) (value-node ::print/number value)
      (keyword? value) (value-node ::print/keyword value)
      (symbol? value) (value-node ::print/symbol value)
      (char? value) (value-node ::print/char value)
      (uuid? value) (value-node ::print/uuid value)

      ;; Date is the ordinary inst and must take the allocation-free path.
      ;; Instant is the other core implementation and normalizes to Date;
      ;; the protocol fallback below is reserved for genuinely exotic Inst
      ;; extensions instead of scanning every collection node.
      (instance? java.util.Date value) (value-node ::print/inst value)
      (instance? java.time.Instant value)
      (value-node ::print/inst (java.util.Date. (inst-ms value)))

      (string? value)
      (if (<= (count value) max-string)
        (value-node ::print/string value)
        (do
          (flag! state)
          {::print/face ::print/truncated-string
           ::print/value (subs value 0 max-string)
           ::print/length (count value)}))

      ;; EVERYTHING below this line projects to a structure — a
      ;; collection, or a marker map — and a structure emitted AT the
      ;; depth cap would place its own entries one past it. So the cap
      ;; is checked once, here, for markers and collections alike.
      ;; (Falsified the other way first: markers-as-maps at the cap
      ;; produced depth 7 under a cap of 6.)
      deep? (prune! state)

      (instance? Throwable value)
      (if (take-node! state)
        {::print/face ::print/throwable
         ::print/value (project (Throwable->map value) child-depth state)}
        (elide! state))

      (instance? sci.lang.Var value)
      {::print/face ::print/var
       ::print/name (subs (str value) 2)}

      (instance? sci.lang.Type value)
      {::print/face ::print/type
       ::print/name (str value)}

      (instance? Class value)
      {::print/face ::print/class
       ::print/name (.getName ^Class value)}

      ;; reference types and arrays: named, never entered. This is what
      ;; makes a cycle unrepresentable rather than detected.
      (instance? clojure.lang.IDeref value) (object-node value (:caps state))
      (some-> value class .isArray) (object-node value (:caps state))

      ;; a record IS map-like; it keeps its fields and the name sci gives
      ;; it. The tag rides at the same level as the fields so the
      ;; projection's depth is a record's own depth, and the field width
      ;; leaves room for it.
      (record? value)
      (let [fields (project-map value max-collection child-depth state)]
        (if (vector? fields)
          {::print/face ::print/record
           ::print/name (or (sci-named value) (.getName (class value)))
           ::print/entries fields}
          fields))

      (or (map? value) (instance? java.util.Map value))
      (let [entries (project-map value max-collection child-depth state)]
        (if (vector? entries)
          {::print/face ::print/map ::print/entries entries}
          entries))

      (or (set? value) (instance? java.util.Set value))
      (let [items (project-entries value max-collection child-depth state
                                   (fn ([] []) ([acc child] (conj acc child))))]
        (if (vector? items)
          {::print/face ::print/set ::print/items items}
          items))

      (or (vector? value)
          (instance? java.util.RandomAccess value)
          (instance? clojure.lang.MapEntry value))
      (let [items (project-entries value max-collection child-depth state
                                   (fn ([] []) ([acc child] (conj acc child))))]
        (if (vector? items)
          {::print/face ::print/vector ::print/items items}
          items))

      ;; vectors, lists, lazy and infinite sequences, and host
      ;; collections all become bounded vectors: a bounded projection of
      ;; a possibly-infinite thing cannot be that thing. NOTHING here
      ;; counts the source — `count` on an infinite sequence never
      ;; returns.
      (or (coll? value) (seq? value) (instance? java.util.Collection value))
      (let [items (project-entries value max-collection child-depth state
                                   (fn ([] []) ([acc child] (conj acc child))))]
        (if (vector? items)
          {::print/face ::print/list ::print/items items}
          items))

      ;; A third party may extend clojure.core/Inst. This intentionally comes
      ;; after every ordinary scalar and collection classification so its
      ;; protocol lookup is paid only for an exotic leaf.
      (inst? value)
      (value-node ::print/inst (java.util.Date. (inst-ms value)))

      ;; a sci type instance that is neither map- nor collection-like
      ;; (a deftype) is named by sci, not by its host class
      :else
      (object-node value (:caps state)))))

(defn- project
  "One node: call the interrupt-fn, then project — or mark and move on."
  [value depth state]
  ;; EVERY node, because a native lazy sequence enters no interpreted fn
  ;; body and would otherwise realize forever (probed: 200k elements, zero
  ;; interrupt-fn calls)
  ((:interrupt-fn state))
  (try
    (project-node value depth state)
    (catch Throwable failure
      ;; the interrupt is the one throwable admission must not swallow
      (when ((requiring-resolve 'seon.sci.eval/interrupted?) failure)
        (throw failure))
      ;; R41 DECIDES THIS, not local judgement (owner ruling reversing
      ;; the drafted marker-only choice): a value the total codec cannot
      ;; project is a core degradation, so development panics on it
      ;; immediately and production degrades.
      (when (= :panic (:on-core-error state))
        (throw (ex-info (str "value admission could not project a "
                             (.getName (class value)))
                        {:seon.error/kind ::projection-failed
                         ::class (.getName (class value))}
                        failure)))
      ;; a marker is a structure, so at the depth cap the elision scalar
      ;; is the only thing that fits — the same rule the walk itself
      ;; follows, applied to the failure path
      (if (>= depth (:seon.config.eval.result/max-depth (:caps state)))
        (elide! state)
        (do
          (vreset! (:capped? state) true)
          {::print/face ::print/failed
           ::print/class (.getName (class value))
           ::print/message (or (ex-message failure)
                               (.getName (class failure)))})))))

;;; ---------------------------------------------------------------------------
;;; The one operation
;;; ---------------------------------------------------------------------------

(defn admit
  "Realize and bound one value leaving a sci evaluation. ONE pass.
  Call this INSIDE the armed boundary, before disarm — that placement
  is the contract, not a convention: after disarm there is no time
  limit left to stop an infinite realization.

  Walks `::value` once, calling `::interrupt-fn` at EVERY node, and
  returns

      {::value        <the bounded projection>
       :seon.cluster.eval/result-edn <that projection, printed>
       ::capped?      <true when anything was elided>
       ::record       <the diagnostics, unchanged>}

  The projection's grammar is the namespace docstring's total codec.
  `::capped?` is the honest signal that the printed result is not the
  whole value — a reader must never have to guess whether an elision
  marker was the agent's own data.

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
  [{::keys [value interrupt-fn caps record]
    on-core-error :seon.config/on-core-error}]
  (let [state {:interrupt-fn interrupt-fn
               :caps caps
               :on-core-error on-core-error
               ;; the root is a node like any other
               :nodes (volatile! (dec (long (:seon.config.eval.result/max-nodes
                                             caps))))
               :capped? (volatile! false)}
        projection (project value 0 state)]
    (cond-> {::value projection
             ;; finite by construction: the projection is depth-bounded,
             ;; width-bounded, deref-free and cycle-free, so this print
             ;; cannot run away and cannot overflow the stack
             :seon.cluster.eval/result-edn (pr-str projection)
             ::capped? @(:capped? state)}
      ;; absent in, absent out — never a stored nil
      record (assoc ::record record))))
