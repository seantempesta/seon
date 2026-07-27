(ns seon.sci.admit
  "Value admission: the ONE thing that leaves a sci evaluation.

  DRAFT CONTRACT LAYER FOR ORCHESTRATOR SEAL (drafted 2026-07-27 — the
  N3 rung's one genuinely new mechanism, ruled a small package of its
  own landing BEFORE the run loop; grounded in
  research/n3-plan-2026-07-27.md §6.1, §10 row C7, §11 risk 1 and the
  2026-07-27 late ruling). Nothing here is implemented: every body
  throws `awaits implementation`. Once sealed, the implementation lane
  fills the stubs until test/seon/sci/admit_test.clj is green and may
  not loosen a schema or a test.

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

  ERRORS. Admission never throws of its own accord: a node it cannot
  project becomes a marker carrying the throwable's class, and the
  refusal shapes agents see stay flat `:seon.error` values. It does NOT
  catch the interrupt — sci's interrupt is deliberately uncatchable by
  evaluated code (`reference-code/sci/src/sci/interrupt.cljc:32-41`),
  and host code that swallowed it would be forging the one guarantee the
  time limit rests on. It propagates to `evaluate`, which records the
  `:time` outcome.

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
  (:require [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [clojure.test.check.generators :as gen]))

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
  [request]
  (throw (ex-info "awaits implementation" {::fn `admit})))
