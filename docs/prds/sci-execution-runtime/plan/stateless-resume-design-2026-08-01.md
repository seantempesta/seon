---
type: prd
status: active
tags: [prd, sci, agent, database]
---

# Stateless resume — an agent session restorable from the database

Design lane, 2026-08-01, on the owner's directive: a hot parked sci ctx is
acceptable as an optimization, but an agent's full session state must be
**restorable from the database or disk**. "We own the SCI code and the
konserve and datahike code and it's all running in the same JVM. This
should be possible."

**It is possible, it is built out of pieces that already exist, and it is
cheap.** A fresh JVM restored a session whose turn-1 `def` is a
200 000-element vector and used that value in turn 10 — measured below,
reproducible from `tmp/stateless-resume-2026-08-01/`. The session image's
own cost is **22 ms for 200 names**, on top of the `acquire!` a turn
already pays.

Every number here comes from scripts committed under
`tmp/stateless-resume-2026-08-01/` (`p1.clj`, `p2.clj`, `p3.clj`,
`p4_resume.clj`, `p5_scale.clj`), each run in a fresh `clojure -M:dev` JVM
against its own store root (`tmp/stateless-resume-2026-08-01/clusters`) —
no shared cluster was touched. Machine: this laptop, JDK 26, `:dev`
(`-Xmx512m`).

Builds on, and in two places CORRECTS,
`research/sci-session-persistence-2026-08-01.md` (ctx anatomy, parked-ctx
cost) and `research/sci-precomputed-analysis-2026-07-31.md` (the base-ctx
holder, the per-fork guard blocker). Ruling #25 supplies the blob tier;
ruling #26 supplies the print grammar; ruling 2026-07-31 (shared Vars
across forks are INTENDED) means no isolation is designed here.

## 0. Headline numbers

| Measurement | Value | Probe |
|---|---|---|
| **fresh JVM → usable session (200k-element def in use)** | **1 299–1 536 ms total** | p4 |
| of which: open store + start cluster | 786–861 ms | p4 |
| of which: `acquire!` (cold JVM) | 441–578 ms (**202 ms warm**, p1) | p4/p1 |
| of which: **the session image itself** | **60–81 ms** (one 1.3 MB blob entry + 4 small) | p4 |
| session image, 200 names (100 values + 100 forms) | **21.9 ms — 110 µs/name** | p5 |
| pass 1, intern 200 names unbound | 0.27 ms | p5 |
| pass 2a, bind 100 inline values | 0.47 ms | p5 |
| pass 2c, evaluate 100 defining forms | 17.4 ms (**0.174 ms/form**) | p5 |
| blob read of a 1.29 MB value (`bget` + `read-string`) | 7.0 + 38.0 … 14.7 + 45.3 ms | p4 |
| **recompute** the same 200k vector through the door | **9.5 ms** | p1/p4 |
| interning an already-materialized value | 0.044 ms | p1 |
| `pr-str` of a 200k vector | 9.5 ms, 1 288 891 chars | p1/p2 |
| uncapped admission walk of the same value | 50–80 ms, **byte-identical text** | p2 |
| install order with a two-pass intern | **irrelevant** (proven both ways) | p1 |
| `acquire!` restores agent aliases from `:seon.ns` rows | **yes** | p1/p4 |
| what a `def` records today | **the Var, never the value** | p1 |

## 1. The decomposition — what must be persisted, by kind

The ctx env is `namespaces → names → Vars → values`
(`sci-session-persistence-2026-08-01.md` §1, `sci/core.cljc:304-311`).
Persisting "the session" therefore means persisting, for every live name,
enough to reproduce its Var's value. There are exactly four kinds.

### 1.1 Contracted functions — already stateless, already done

An agent `defn` carrying a complete `:malli/schema` becomes a `:seon.fn`
row (`seon.sci.reader` §`function-declaration`, `seon.program/declaration-row`
with the `:contracted` policy), and `acquire!`
(`src/seon/sci/eval.clj:726-890`) reinstalls it interpreted into any fresh
fork, in dependency order, from database rows alone.

**That is already stateless resume for contracted functions**, and p4
re-proved it in a fresh JVM. Nothing is missing for this kind. Note the
closure problem does not arise: we never serialize the closure, we
re-create it by evaluating stored source, which is the only faithful way
(`sci-session-persistence-2026-08-01.md` §1).

What is NOT covered, and is the gap: an **uncontracted** `defn`
(`:contracted` refuses it, `program.cljc:97-129`), a `def` whose value is a
function, a function defined inside a `let`, and functions stored inside a
data structure. All of those are ordinary interns with no row. Section 1.2
covers them by the same means: their **defining form**.

### 1.2 Everything else the session interned — the session image

The unit is one **entry per live `[namespace name]`**, carrying exactly one
of four things. Presence is the state, as everywhere else.

| Entry carries | When | Restore |
|---|---|---|
| `value-edn` (inline) | the value serializes faithfully and is small | `read-string` + intern |
| `blob` + `size` (digest) | the value serializes faithfully and is large | `bget` + `read-string` + intern |
| `source` (the exact top-level form that defined it) | the form is pure | evaluate that ONE form |
| `unrestorable` (a reason string) | neither of the above is possible | nothing — the session states the absence |

**Ordinal ordering is enough, and dependency analysis is never needed** —
p1 §B. Naively evaluating `(def f (fn [v] (+ (g v) n)))` before `g` and `n`
exist fails at analysis (`Unable to resolve symbol: g`) and then at call
(`Attempting to call unbound fn`). Interning every name of the image as an
**unbound Var first** and only then binding them makes the same
wrong-order install succeed: `(f 5) => 110`. Sci's analyzer captures the
Var object at analysis time (`analyzer.cljc:2276-2298`), so a later
`bindRoot` is seen. Install form entries in their original ordinal order
anyway, for the case where a form CALLS another entry at definition time;
the two-pass intern is what makes mutual reference and any residual
mis-ordering harmless.

### 1.3 Namespace, alias, refer, import state — already done

`:seon.ns` rows already carry `requires`/`aliases`/`imports`/`refers`
(`seon.sci.eval/binding-rows`, `namespace-context-row`), and `acquire!`
calls `sci/install-namespace-bindings!` for every agent-authored namespace.

**Probed, both in-process and in a fresh JVM** (p1 §E, p4): after
committing a `:seon.ns` row with the alias `str → clojure.string` and
running `acquire!` on a fresh fork, `(str/join ", " names)` evaluates and
`sci/namespace-bindings` reports `{:aliases {str clojure.string} …}`.
Nothing to build.

**One residual gap: the in-`ns` POSITION.** Within a run the loop carries
`namespace-name` across forms; at the start of a run it falls back to
`(sci.eval/agent-namespace agent-id)` (`loop.cljc:1050-1052`). So a session
that did `(in-ns 'other)` in turn 1 is back in its own namespace in turn 2.
This does not need a new fact — it is **derivable**: the
`:seon.cluster.eval/ns` of the highest-ordinal settled receipt of the
agent's most recent run. Derive it; do not store it. (The related defect
that `agent-namespace` hardcodes `my.agents.<id>` against the 2026-07-31
ruling is already filed as
`docs/seon/issues/evals-ignore-the-agents-assigned-namespace.md`.)

### 1.4 Non-restorable values — say so, do not fake it

Probed faces from the current admission walk (p1, p3):

```
(def counter (atom 0))     value → #:seon.sci.admit{:reference "clojure.lang.Atom"}
(def handler (fn [v] …))   value → #:seon.sci.admit{:opaque "sci.impl.fns$fun$arity_1…"}
(def bs (byte-array 3))    value → #:seon.sci.admit{:reference "array"}
(def f (java.io.File. "x"))value → #:seon.sci.admit{:opaque "java.io.File"}
```

An atom, an open connection, a byte array, a Java object: the value is
process-local by construction. But note the ladder — a `(def counter (atom
0))` is **restorable by its FORM** (evaluating it produces a fresh atom at
0), just not by its value. Only two things are genuinely unrestorable:

1. a value whose defining form is not re-runnable (it crossed the
   capability door) AND whose value does not serialize; and
2. a value bound by interning from outside the session (none today).

For those the entry carries `unrestorable` with the honest reason, the name
is **left absent** (so `(count counter)` fails with an ordinary
`Unable to resolve symbol`, not a lie), and the session render prints one
line naming the dropped names and why. That line is derived from the
entries, not stored as a flag.

## 2. Value fidelity — falsified, and the rule that survives it

The tempting rule — "store the value when the admitted projection has no
opaque marker" — **is wrong**, and p3 falsified it against ground truth
(`=` AND same class AND same metadata after `pr-str`/`read-string`):

```
value            derived  faithful  verdict
list             true     false     DERIVED SAYS YES, TRUTH SAYS NO
lazy-seq         true     false     DERIVED SAYS YES, TRUTH SAYS NO
sorted-set       true     false     DERIVED SAYS YES, TRUTH SAYS NO
sorted-map       true     false     DERIVED SAYS YES, TRUTH SAYS NO
meta-carrying    true     false     DERIVED SAYS YES, TRUTH SAYS NO
queue            true     false     DERIVED SAYS YES, TRUTH SAYS NO
array list       true     false     DERIVED SAYS YES, TRUTH SAYS NO
atom / fn / File / byte array  false  false   agree
record           true     true      agree
```

Today's admission projection is a **presentation** projection: it folds
lists, lazy-seqs, queues and `java.util.Collection` into vectors
(`admit.clj:286-288`), drops sortedness, and drops metadata. Ruling #26's
sealed grammar fixes list-vs-vector, records and classes
(`plan/print-path-design-2026-08-01.md` §Piece 3) but **cannot** fix
sortedness, queue-ness or metadata: those are not expressible in the text.

The rule that survives:

> **The defining FORM is the source of truth. The stored VALUE is a
> cache.**

Re-evaluating `(def s (sorted-set 3 1 2))` reconstructs a sorted set
exactly. Serializing it does not. So correctness never rests on
serialization fidelity, and the value path is used only where it is both
faithful and worth it. Faithfulness is checked, not assumed, by the one
total check with no hand list of types:

```clojure
(let [text (pr-str value)
      back (read-string text)]
  (and (= value back) (= (class value) (class back)) (= (meta value) (meta back))))
```

Cheap to state, and the result of the check is the fact we store (the entry
gets `value-edn`/`blob`, or it does not). *Sortedness is invisible to this
check at nested levels* — `(= (sorted-set 1) #{1})` is true — which is
exactly why the form remains the truth and the value remains a cache: a
cache that is `=` to the original is a good cache, and where exactness of
class matters the form path already ran.

**When is the value cache worth writing at all?** Derived, from a fact the
receipt already carries — the form's measured evaluation duration
(`:seon.eval/duration-ms` in the admission record) — against the measured
rehydrate cost of its own serialization. Both numbers are known at the
settle seam; no tuned constant:

```
blob the value  ⟺  recorded eval duration  >  read-string cost of its text
                   (measured: ≈ 34 µs per 1 000 chars, p4: 1 288 891 chars → 38–45 ms)
```

This immediately corrects the prior lane's recommendation.
`sci-session-persistence-2026-08-01.md` §4 concluded "rehydrate by interning
is 0.027 ms (220×), rehydration beats recomputation" — but 0.027 ms is
interning a value **already materialized in the heap**. End to end from the
store it is `bget` 7–15 ms + `read-string` 38–45 ms = **45–60 ms**, while
recomputing that same vector through the door is **9.5 ms**. For that value
the blob is 5× SLOWER. The blob wins for genuinely expensive computations
(a minute of work), which is precisely what the derived comparison
expresses. Rehydration is not a free win; it is a trade the recorded
duration decides.

## 3. The fact model

Three lines, plus the honesty attribute:

```clojure
:seon.code.def/id     [:string {:seon.db/identity true}]  ; "my.agents.ada/index"
:seon.code.def/ns     :seon.db/ref                        ; → :seon.ns/name
:seon.code.def/name   :symbol
;; exactly one of: (presence IS the state)
:seon.code.def/value-edn     :string          ; small faithful value, inline
:seon.code.def/blob          :seon.blob/digest ; large faithful value
:seon.code.def/size          [:int {:min 0}]   ; the full serialized length
:seon.code.def/source        [:string {:min 1}] ; the pure top-level defining form
:seon.code.def/unrestorable  [:string {:min 1}] ; the honest reason
;; ordering only, never a dependency graph
:seon.code.def/ordinal       [:int {:min 0}]
```

It is deliberately the **`:seon.fn`/`:seon.ns`/`:seon.schema` family shape**
— identity attribute, a ref to its namespace, a source attribute — so it
joins `seon.program/shapes` as a fifth shape rather than becoming a second
mechanism. A name that has a current `:seon.fn/sym` row is excluded from
the image by one query: contracted functions are already installed by
`acquire!` and must not be installed twice.

**Where entries are written.** At the settle seam, on `:io`, beside ruling
#25's blob write (`terminal-tx`'s caller in `src/seon/cluster/loop.cljc`).
Which names a form interned is derived by diffing `sci/namespace-interns`
before and after the form — the SAME before/after mechanism
`removed-program-identities` (`eval.clj:425-439`) already uses for
`ns-unmap`, extended, not duplicated. This catches `(let [n 5] (def f …))`
and every other shape a reader-side `def` detector would miss.

**Branch and dedup come free.** Entries are ordinary datoms on the
cluster's branch, so a Datahike branch fork (~17 ms) carries the whole
session image with it — this is what makes
`plan/grader-in-fact-space-2026-08-01.md`'s "fork the agent's ENDING state"
possible, and it is a fork of FACTS, which is safe, where forking a live
ctx is not (`sci-session-persistence-2026-08-01.md` §1: a fork's `def` of an
existing name writes through into the parent). Blobs are content-addressed
SHA-256 keys in the store `seon.blob` already writes to
(`src/seon/blob.clj:19-30`), so two agents that `def` the same 1.3 MB value
share one blob, and `put!` already short-circuits on `k/exists?`.

**GC.** `seon.cluster.registry/collect!` (`registry.clj:305-331`) already
extends Datahike's reachable set by one fact-derived hop before
`konserve.gc/sweep!`, because blobs live in the SAME konserve store as the
Datahike index and would otherwise be swept. Def blobs must join that
union. Two constraints found while reading it:

1. `branch-result-blobs` (`registry.clj:284-297`) names
   `:seon.cluster.eval/result-blob` **by hand**. Adding a second digest
   attribute must not mean editing a list — derive the digest attributes
   from the schema (attributes whose declared form is `:seon.blob/digest`)
   and query them all. Issue filed:
   `docs/seon/issues/blob-reachability-names-one-attribute-by-hand.md`.
2. The mark reads each branch's CURRENT db. A superseded def entry's blob
   is still reachable **through history**, and history stays on (ruling
   #23, "I really want time travel features"). Either the mark reads the
   history value, or time travel into a superseded session silently loses
   its big values. Named on the same issue.

## 4. The resume procedure, with measured cost

Given a cluster connection and an agent id, in a process that has never
seen this session:

| Step | What | Measured |
|---|---|---|
| 1 | `sci.eval/fork` the process base ctx | 1.4–2.7 ms |
| 2 | `acquire!` — first-party host Vars, agent `:seon.ns` rows, contracted `:seon.fn`/`:seon.test` rows | 202 ms warm, 441–578 ms cold |
| 3 | query the image: entries for this agent's namespaces, ordered by ordinal | one query |
| 4 | **pass 1** — intern EVERY entry name as an unbound Var | 0.27 ms / 200 names |
| 5 | **pass 2a** — bind inline `value-edn` entries | 0.47 ms / 100 names |
| 6 | **pass 2b** — `bget` + `read-string` + bind blob entries | 45–60 ms per 1.3 MB |
| 7 | **pass 2c** — evaluate `source` entries through the door, in ordinal order | 0.174 ms/form |
| 8 | restore the in-`ns` position (derived, §1.3) | one query |
| 9 | render the session header line for `unrestorable` entries | derived |

Steps 4–7 are the whole new mechanism: **21.9 ms for a 200-name session**,
plus the blob reads for whatever the agent chose to keep. Against the
202 ms `acquire!` a turn already pays, and against a model round trip
measured in seconds, that is not a cost that needs an argument.

The full fresh-JVM proof (p4), verbatim:

```
=== resume stages
open store + start cluster                      786.214 ms
sci/fork                                           1.430 ms
acquire! (program rows + ns rows)                441.495 ms
pass 1: intern all names unbound                   0.311 ms
pass 2a: inline value entries                      0.126 ms
    blob get big (1288891 chars)                   7.012 ms
    read-string big                               38.007 ms
pass 2b: blob-backed value entries                45.331 ms
pass 2c: pure defining forms (reverse order)      13.313 ms
TOTAL from JVM entry to a usable session        1299.058 ms

=== turn 10: use the state defined in turn 1
  limit                                    => 25
  (count big)                              => 200000
  (reduce + (subvec big 199990 200000))    => 1999945
  (scale 4)                                => 100
  (total)                                  => 1125
  (str/join ", " names)                    => "ada, grace, alan, edsger"
  (count (filter even? big))               => 100000
```

`scale` and `total` are form entries installed in REVERSE dependency order;
`big` is the blob-backed 200 000-element vector; `str` is an alias restored
from a `:seon.ns` row. Nothing from the writing process survived — a
different JVM wrote them.

**Is it fast enough to be THE mechanism rather than an optimization? Yes.**
The image is ~10 % on top of a warm `acquire!`, and both are dwarfed by
opening the store. There is no performance argument for making a live ctx
the source of truth.

## 5. Is this "replay"? — the honest statement

It is not replay of history, and the difference is structural, not
rhetorical:

| Replay-the-transcript | The session image |
|---|---|
| N = number of forms ever evaluated | N = number of names still live |
| a name redefined 40 times runs 40 times | it is installed once |
| forms that sent messages, queried the database, printed, errored, or were interrupted all re-run and must each be proven safe | they are **never re-run** — they interned nothing that survives |
| safety is a per-form analysis over the whole history | safety is a per-entry question about ONE form |
| grows without bound with session length | bounded by the session's live vocabulary |

What DOES re-execute is one pure form per surviving code-valued name. That
is the same act `acquire!` already performs for every contracted `defn`,
and it is what creates the closure — there is no other way, because a
`sci.impl.fns$fun` closes over its creating ctx and its analyzed nodes
(`sci-session-persistence-2026-08-01.md` §1). The architecture line stays
exactly where it was: **the driver never re-executes; the agent's session
is re-derived.**

The capability door does not exist yet, so today every stored form is pure
by construction (`sci-session-persistence-2026-08-01.md` §6) and the
`unrestorable` case is empty. When the door lands, a crossing is recorded
on the receipt, and an entry whose defining form crossed takes the value
path or becomes `unrestorable`. No decoration, no hand list — the same
two-seam shape as workload classification.

## 6. The hot parked ctx — an optimization layer, and nothing more

Keep `sci-session-persistence-2026-08-01.md`'s route A, with its status
demoted to what it actually is: a cache of a derivable view, keyed by
`[agent-id corpus-basis]`, held beside the agent's parked flow procs.

- It saves the 202 ms `acquire!` and the 22 ms image install per turn.
- Its marginal memory is the agent's own values and essentially nothing
  else (measured: a `(def big …)` in a ctx retains 2 872 KB vs 2 876 KB for
  the bare value — ctx overhead ≈ 0).
- **It may be dropped at any moment for any reason**, because the image
  rebuilds it: on agent pause, heap pressure, corpus-basis change, a
  `seon.sci.eval` reload
  (`docs/seon/issues/sci-eval-namespace-is-not-hot-reloadable.md`), or a
  crash.
- **It is never forked and never branched.** A grader that wants an agent's
  ending state forks the BRANCH and resumes the image on its own fresh ctx
  — which is now a supported operation rather than the impossibility §1 of
  the prior report described.

The two are one design under the usual Seon shape: the ctx env is a
materialized view of (base ctx + program rows + this agent's session
image); the parked ctx caches the view, the image derives it, and there is
one truth.

## 7. Slice 1

**Goal: an agent's `def`s survive a JVM restart, proven in a fresh JVM.**

| Piece | Where | Size |
|---|---|---|
| `:seon.code.def` attributes (§3) as a fifth `seon.program/shapes` entry | `resources/seon/schema/program.edn`, `src/seon/program.cljc` | ~15 lines |
| intern-diff at the settle seam + the faithfulness check + the duration-vs-rehydrate comparison; write one entry per interned name | `src/seon/cluster/loop.cljc` (terminal seam), beside the ruling-#25 blob write | ~60 lines |
| `install-session-image!` — the two-pass install of §4 steps 4–7 | `src/seon/sci/eval.clj`, next to `acquire!` | ~50 lines |
| call it from the run loop's `:resume` branch, right after `acquire!` | `src/seon/cluster/loop.cljc:995` | 1 line |
| def-blob reachability + the derived digest-attribute set | `src/seon/cluster/registry.clj` | ~10 lines |

**Acceptance evidence** (a recurring test, not a lane run — a proof that
ran once in a lane counts as NOT COVERED):

1. **The owner's case, in a fresh JVM.** Turn 1 evaluates
   `(def big (vec (range 200000)))`, `(def names [...])`, and
   `(def scale (fn [v] (* v limit)))`. The process exits. A new process
   opens the store, resumes, and turn 10 evaluates `(count big)` → `200000`,
   `(scale 4)`, and `(str/join ", " names)` — all correct. p4 is that
   proof's shape; the test makes it recurring.
2. **Order independence.** The same image installed in reverse ordinal
   order restores identically (p1 §B is the falsifier that makes this a
   real assertion rather than an accident).
3. **Fidelity refusal.** A `(def q (into clojure.lang.PersistentQueue/EMPTY
   [1 2]))` is stored as its FORM, not its value, and after resume
   `(class q)` is still a queue. The faithfulness check is what makes this
   pass; p3 is the falsifier that motivated it.
4. **Honest absence.** A `(def c (atom 0))` restores by form to a fresh
   atom; a hypothetical entry marked `unrestorable` leaves the name absent
   and appears in the session header line. No name is ever bound to a
   marker map.
5. **Dedup and GC.** Two agents `def`ing the same large value produce ONE
   blob key; `collect!` after a session's blob is superseded does not
   delete a blob still reachable from history.
6. **Cost.** The image install for a 200-name session stays under 50 ms,
   asserted, so a regression in the install path is visible.

Explicitly NOT in slice 1: the parked ctx (§6 — independent, and it is the
optimization, not the mechanism), capability-crossing facts (no door
exists), and the grader's branch-fork resume (it composes for free once
slice 1 lands).

## 7b. SEALED AMENDMENT — the restore rule (owner ruling #32, 2026-08-01 night)

Supersedes the open decisions below where they conflict. The decision
order per def is VALUE-FIRST (owner clarification, same night): restore
from the stored value wherever the value is store-faithful — including
effectful-but-data defs, which never run again; re-evaluation is the
FORCED path only for values that cannot be stored at all (every `defn`:
a closure has no storable representation, only its defining form), and
purity is the safety condition that permits that forced path. On
restore:

- **Re-evaluate ONLY forms provably pure.** Derived three ways, never
  tagged: no host touch at sci analysis (sci resolves every interop
  call at `:phase analysis` — live-proven), no capability-leaf
  reachability over `:seon.fn/calls` (leaves annotated at their own
  definition site, chains derived — the workload precedent), and no
  effect-door receipt once `seon.effect` exists (effectfulness is a
  recorded fact, not an inference). Fail-closed: unproven ⇒ not
  re-evaluated.
- **Everything unproven restores from its stored value**, gated by the
  COMPUTED `store-faithful?` round trip — serialize, read back, compare
  class + metadata + value. No type enumeration anywhere; unknown types
  fail the trip automatically. (This replaces closure-class detection:
  `research/durable-env-structural-sharing-2026-08-01.md` proved class
  predicates wrong and insufficient.)
- **Order: intern every name unbound → bind every value-restored def →
  re-evaluate pure forms in ordinal order.** Restore never executes an
  effect; nothing ever re-fires.
- **Unrestorable defs** (runtime-only value AND unproven form) are
  interned and STATED in the session, never faked.
- **Sequencing:** forms slice first, the value/blob tier immediately
  after in the same lane — the value tier is REQUIRED by this rule
  (effectful-but-data defs), not an optional cache. Both queue behind
  Lane 1A (shared `seon.sci.eval` ownership). Interop expansion is
  COUPLED to the per-eval interop-touch fact
  (`issues/unlogged-findings-2026-08-01.md` §1).

## 8. Open owner decisions

1. **Does the image belong to the agent, or to the namespace?** The entries
   are keyed by `[namespace name]`, and the 2026-07-31 ruling says a
   namespace has at most one assigned agent — so the two coincide today.
   Stating it as a namespace fact (rather than an agent fact) is what makes
   a grader's fork and a namespace page work without a second lookup.
   Recommended, but it is a modelling call.
2. **Retention.** Every superseded entry keeps its blob reachable through
   history (§3). Either that is intended (time travel into an old session
   works completely), or superseded def blobs need an explicit
   `:db/noHistory`-style exemption. Recommend intended, and measure the
   growth in the storage census ruling #23 already asks for.
3. **Should the value cache exist at all in slice 1?** Everything except an
   expensive computation restores correctly from the form alone, and the
   form path is exact where the value path is merely `=`. A slice-1 that
   ships FORMS ONLY is smaller, strictly more faithful, and still passes
   acceptance 1 (the 200k vector recomputes in 9.5 ms — faster than reading
   its blob). Recommended if the slice needs to be smaller; the blob path
   is then an accretion driven by the first measured expensive `def`.
