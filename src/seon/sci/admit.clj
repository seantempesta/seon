(ns seon.sci.admit
  "Value admission: the ONE thing that leaves a sci evaluation.

  CONTRACT LAYER (drafted + ORCHESTRATOR-SEALED 2026-07-27 — the N3
  rung's one genuinely new mechanism, ruled a small package of its own
  landing BEFORE the run loop; grounded in
  research/n3-plan-2026-07-27.md §6.1, §10 row C7, §11 risk 1 and the
  2026-07-27 late ruling). The implementation lane fills the stub
  bodies until test/seon/sci/admit_test.clj is green and may not
  loosen a schema or a test. Seal dispositions on the drafted taste
  calls: the activation seam died at the gate itself
  (seon.schema.edn `requiring-resolve`s a predicate's owner — the
  computed rule, no boot require added); the four cap dials are wired
  into :seon.config/manifest and config/default.edn; caps stay
  CHARACTER counts (they are storage/projection bounds — the
  tokens-for-display rule governs rendering, which converts, and is
  untouched); the one projection (raw graph never escapes) and
  sci.impl.types naming stand as drafted; diagnostics register here as
  :seon.eval/* per the vocabulary table while receipt attributes stay
  :seon.cluster.eval/* — the prefix unification is a queued rename
  question, not this package's. OPEN OWNER QUESTION (reversible in one
  line, drafted choice stands meanwhile): a projection failure is a
  marker, never a dev-panic — hostile agent values are agent INPUT,
  not system degradation, so R41's dev-panic dial does not fire here.

  THE ONE OPERATION. `admit` runs INSIDE the armed boundary, before
  disarm, and is the only door a value uses to leave. It realizes and
  projects in ONE pass; `ordinary-wire-value?` from the quarry collapses
  into it and there is NO second predicate (n3-plan §6.1). What it fixes
  is a measured defect, not a theory: `terminal-receipt-data`
  (`src-old/seon/agent/driver.clj:160-173`) `pr-str`s the raw value
  unbounded and drops `fn-entries`/`allocated-bytes`, and lazy values
  escape the boundary unrealized.

  WHAT ADMISSION OWNS, AND WHAT IT DOES NOT. It forces and size-caps
  values leaving the sandbox — that is the whole scope (owner ruling,
  2026-07-27 late). Allocation is watched by the O4 heap watermark, NOT
  here; `:seon.eval/allocated-bytes` and `:seon.eval/fn-entries` ride
  through as RECORDED DIAGNOSTICS and are never limits. Time is the only
  limit and it belongs to the `:interrupt-fn`, which admission is
  handed rather than owning.

  REALIZATION MUST PARTICIPATE IN THE ARMED BOUNDARY, and this is the
  finding the design turns on (probe
  `tmp/n3-admission-probe/escape_probe.clj`). Sci calls the
  `:interrupt-fn` at every interpreted function-body entrance — but a
  lazy sequence built by NATIVE `clojure.core` enters no interpreted
  body at all. Measured: walking 200,000 elements of a native
  `(iterate inc 0)` fired the interrupt-fn ZERO times and could not be
  stopped; the identical walk with the realizer calling the
  interrupt-fn itself was interrupted at the first element. sci's own
  `sci.interrupt/clojure-core` overrides exist for exactly this reason
  (`reference-code/sci/src/sci/interrupt.cljc:1-20`) and also stop it —
  but only for sequences its own producers made, never for one a host
  capability returned. THEREFORE: the walk calls `interrupt-fn` at
  EVERY node it visits. An infinite sequence dies at the time limit, on
  the compute thread, inside the boundary — never in the receipt
  writer.

  THE CODEC IS TOTAL, and its totality is structural rather than
  enumerated:

  - ORDINARY VALUES pass through: nil, booleans, integers, floating
    point, ratios, characters, strings, keywords, symbols, and the two
    EDN-readable host scalars `#inst` and `#uuid` (probed: both read
    back through `clojure.edn/read-string`; a regex prints but does NOT
    read back, so it is opaque).
  - COLLECTIONS are walked and rebuilt bounded: maps, sets, vectors,
    and sequences (a sequence becomes a vector — a bounded projection
    of a possibly-infinite thing cannot be that thing).
  - RECORDS project to their field map, tagged with the type name sci
    itself reports through `sci.impl.types/-get-type` (probed:
    `user.Foo`). Sci records are `clojure.lang.IRecord` and map-like
    (`reference-code/sci/src/sci/impl/records.cljc:146-200`), so this
    is a projection, not an interpretation.
  - EVERYTHING ELSE becomes an explicit marker naming what it was: a
    sci var (`sci.lang.Var`), a sci fn (`sci.impl.fns`), a sci deftype
    instance (`sci.impl.deftype.SciType`, named through the same
    `-get-type`), a host object, an array, a reference type.
  - REFERENCE TYPES ARE NEVER DEREFERENCED. `clojure.lang.IDeref`
    covers atoms, delays, promises, futures, volatiles and vars, and
    forcing one is either a cycle or an uninterruptible park: a pending
    promise blocks the compute thread past the time limit and no
    interrupt can take it back (probed).
  - CYCLES ARE THEREFORE UNREPRESENTABLE, by construction rather than
    by detection. Persistent immutable collections cannot close a loop;
    every construct that can — reference types, arrays, mutable
    deftypes — projects opaquely without being entered. This matters
    because it is a live crash today: `pr-str` of a self-referential
    atom, and of an ordinary map holding an atom holding that map,
    raises `StackOverflowError` — an Error, which a `catch Exception`
    does not even see (probed).
  - DEPTH, WIDTH, STRING LENGTH, and TOTAL NODES are capped, and the
    caps are CONFIG FACTS the caller reads from the database and passes
    in — never literals here, never `(or x 512)`. The node budget is
    the real bound: depth and width alone still admit an astronomical
    product.

  Because the projection is bounded, deref-free and cycle-free, its
  `pr-str` is finite by construction and reads back as EDN. The old
  unbounded `pr-str` is not made safe; it is made unreachable.

  ONE PROJECTION, NOT TWO VALUES. `::value` is the projected value and
  `:seon.cluster.eval/result-edn` is that same value printed. The raw
  object graph does not escape admission at all — nothing unrealized,
  unbounded, or host-opaque crosses this line, which is L3 stated
  positively.

  ERRORS, AND THE ONE DIAL (owner ruling 2026-07-27, reversing the
  drafted marker-only choice): a node the total codec CANNOT project is
  a core degradation, not ordinary agent input, so R41 decides it. On
  `:panic` — development — admission throws hard and loud, because a
  value our codec cannot describe is a hole in the codec and must be
  found immediately. On `:record` — production — it degrades: the
  marker, plus a best-effort description when one can be taken safely
  (never a deref, never anything sequential, always truncated). The
  dial is a REQUIRED request key: a caller that has not decided has not
  thought about it. Agent-visible refusal shapes stay flat
  `:seon.error` values either way. Admission does NOT
  catch the interrupt — sci's interrupt is deliberately uncatchable by
  evaluated code (`reference-code/sci/src/sci/interrupt.cljc:32-41`),
  and host code that swallowed it would be forging the one guarantee the
  time limit rests on. It propagates to `evaluate`, which records the
  `:time` outcome. The question `is this that interrupt?` has ONE
  owner — `seon.sci.eval/interrupted?` — and this namespace asks it
  rather than keeping a copy (seal revision, 2026-07-27).

  Crash walk. Admission is PURE given the value and the caps: it opens
  nothing, writes nothing durable, and holds no lock.
  - killed before admission: the evaluation's receipt has no terminal;
    N2's resume marks the run `:interrupted` and the agent adapts (the
    2026-07-27 night ruling — no auto-retry, nothing re-executes);
  - killed DURING admission: identical durable state. A partial
    projection is a value on a dead thread, not a fact;
  - killed after admission, before the terminal receipt commits: the
    same again — the projection was never durable;
  - after the terminal receipt commits: ordinary committed state.
  There is no admission-specific recovery, and that is the design."
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
  defect this package exists to end (`driver.clj:160-173`).

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
    {::value projection
     ;; finite by construction: the projection is depth-bounded,
     ;; width-bounded, deref-free and cycle-free, so this print cannot
     ;; run away and cannot overflow the stack
     :seon.cluster.eval/result-edn (pr-str projection)
     ::capped? @(:capped? state)
     ::record record}))
