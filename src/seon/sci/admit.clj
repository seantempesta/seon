(ns seon.sci.admit
  "Projects values leaving SCI into bounded, printable ordinary data.

  `admit` walks the supplied value once and calls the supplied
  `:interrupt-fn` before projecting every node. Scalars pass through;
  maps, sets, records, sequences, and host collections are rebuilt
  within the configured depth, width, string, and node caps. Sequences
  become vectors. Reference types and arrays are never entered, and
  other opaque values become descriptive data markers.

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
            [sci.impl.types :as sci.types]
            [sci.lang]
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

;;; A marker names what a value WAS. It is ordinary data — a small map of
;;; strings and keywords — so it prints, reads back, and can never hide a
;;; reference, a cycle, or an unrealized tail.
(defn- opaque
  ([value] (opaque value nil))
  ([value named]
   (cond-> {::opaque (.getName (class value))}
     named (assoc ::name named))))

(defn- sci-named
  "The name sci itself reports for a value it defined, or nil."
  [value]
  (when (instance? sci.impl.types.SciTypeInstance value)
    (str (sci.types/-get-type value))))

(defn- reference
  "A reference type, NEVER dereferenced: deref is a cycle or a park.
  A sci var is a reference like any other — but it can say WHICH var it
  is without being entered, and `#'user/f` is worth more to an agent
  than `sci.lang.Var`."
  [value]
  (cond-> {::reference (.getName (class value))}
    (instance? sci.lang.Var value) (assoc ::name (pr-str value))))

(defn- take-node!
  "Consume one node from the budget; false when it is exhausted."
  [state]
  (if (pos? @(:nodes state))
    (do (vswap! (:nodes state) dec) true)
    (do (vreset! (:capped? state) true) false)))

(defn- afford!
  "Consume `n` FURTHER nodes; false when the budget cannot pay."
  [state n]
  (if (<= n @(:nodes state))
    (do (vswap! (:nodes state) - n) true)
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
  ::elided)

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
          (subs described 0 limit)))
      (catch Throwable _ nil))))

(defn- marker!
  "Emit a marker, charging what it REALLY costs.
  A marker is a small map, so it is worth more than the one node the
  value it replaces consumed: the map is that node, and every entry
  adds a key and a value. Charging one node for a three-node marker is
  how the budget was overrun by exactly the marker count (falsified:
  257 nodes under a budget of 256). When the budget cannot pay for the
  whole marker, the elision scalar is what fits."
  [state marker]
  (if (afford! state (* 2 (count marker)))
    marker
    (elide! state)))

(declare project)

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
      (>= taken width) (do (flag! state) accumulated)
      (not (take-node! state)) accumulated
      :else (recur (next remaining)
                   (inc taken)
                   (emit accumulated (project (first remaining) depth state))))))

(defn- project-map
  [entries width depth state]
  (loop [remaining (seq entries)
         taken 0
         accumulated {}]
    (cond
      (nil? remaining) accumulated
      (>= taken width) (do (flag! state) accumulated)
      ;; a map entry is TWO nodes, and a half-projected entry is not an
      ;; entry: take both or neither
      (not (and (pos? (dec @(:nodes state)))
                (take-node! state)
                (take-node! state)))
      (do (flag! state) accumulated)
      :else
      (let [[key value] (first remaining)]
        (recur (next remaining)
               (inc taken)
               (assoc accumulated
                      (project key depth state)
                      (project value depth state)))))))

(defn- project-node
  [value depth state]
  (let [{:keys [:seon.config.eval.result/max-depth
                :seon.config.eval.result/max-collection
                :seon.config.eval.result/max-string]}
        (:caps state)
        deep? (>= depth max-depth)
        child-depth (inc depth)]
    (cond
      ;; ordinary values, straight through
      (nil? value) nil
      (boolean? value) value
      (number? value) value
      (keyword? value) value
      (symbol? value) value
      (char? value) value
      (uuid? value) value

      ;; every inst is EDN-readable as a Date; java.time.Instant is NOT
      ;; (it prints as #object and refuses to read back — probed), so the
      ;; projection normalizes to the readable one
      (inst? value)
      (if (instance? java.util.Date value)
        value
        (java.util.Date. (long (inst-ms value))))

      (string? value)
      (if (<= (count value) max-string)
        value
        (do (flag! state) (subs value 0 max-string)))

      ;; EVERYTHING below this line projects to a structure — a
      ;; collection, or a marker map — and a structure emitted AT the
      ;; depth cap would place its own entries one past it. So the cap
      ;; is checked once, here, for markers and collections alike.
      ;; (Falsified the other way first: markers-as-maps at the cap
      ;; produced depth 7 under a cap of 6.)
      deep? (elide! state)

      ;; reference types and arrays: named, never entered. This is what
      ;; makes a cycle unrepresentable rather than detected.
      (instance? clojure.lang.IDeref value) (marker! state (reference value))
      (some-> value class .isArray) (marker! state {::reference "array"})

      ;; a record IS map-like; it keeps its fields and the name sci gives
      ;; it. The tag rides at the same level as the fields so the
      ;; projection's depth is a record's own depth, and the field width
      ;; leaves room for it.
      (record? value)
      (let [fields (project-map value (dec max-collection) child-depth state)]
        ;; the type tag is an entry like any other and is charged like one
        (if (afford! state 2)
          (assoc fields ::type (or (sci-named value) (.getName (class value))))
          (do (flag! state) fields)))

      (map? value)
      (project-map value max-collection child-depth state)

      (set? value)
      (project-entries value max-collection child-depth state
                       (fn ([] #{}) ([accumulated child] (conj accumulated child))))

      ;; vectors, lists, lazy and infinite sequences, and host
      ;; collections all become bounded vectors: a bounded projection of
      ;; a possibly-infinite thing cannot be that thing. NOTHING here
      ;; counts the source — `count` on an infinite sequence never
      ;; returns.
      (or (coll? value) (seq? value) (instance? java.util.Collection value))
      (project-entries value max-collection child-depth state
                       (fn ([] []) ([accumulated child] (conj accumulated child))))

      ;; a sci type instance that is neither map- nor collection-like
      ;; (a deftype) is named by sci, not by its host class
      :else
      (marker!
       state
       (opaque value (or (sci-named value)
                         (when (instance? sci.lang.Var value) (pr-str value))
                         (when (instance? sci.lang.Namespace value)
                           (str value))))))))

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
          (marker! state
                   (cond-> {::opaque (.getName (class value))
                            ::projection-error (.getName (class failure))}
                     ;; a best-effort description, when one can be taken
                     ;; SAFELY: never a deref, and never anything
                     ;; sequential — `str` on a lazy sequence realizes it,
                     ;; which is the hang this whole namespace exists to
                     ;; prevent
                     (safe-description value (:caps state))
                     (assoc ::description
                            (safe-description value (:caps state))))))))))

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
