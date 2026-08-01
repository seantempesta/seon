---
type: prd
status: active
tags: [prd, render, sci, context]
---

# The print path — one emitter, two sinks (2026-08-01)

**RULED (owner, 2026-08-01): all three decisions taken as recommended.**
`result-edn` stays the data projection with REPL text emitted at render;
agent `print-method` stays refused (fork-local form recorded for later
if ever wanted); honest faces — true `sci.lang.Namespace` class, atoms
print without deref. The contract below is SEALED for implementation.


Ruling #24's open crux: *"how does a REPL print, and can we print
composable HTML the same way? Closer to the metal — mirror how Clojure's
printer actually works, don't invent a parallel one."*
(`plan/README.md:1703-1706`, design in
`plan/repl-session-context-2026-08-01.md:91-98`.)

This document is the contract that answers it, the complete dispatch
table, the admission-grammar change it requires, the owner decisions it
raises, and the acceptance evidence that closes the realism audit's
print divergences (`research/sci-repl-realism-audit-2026-08-01.md`, S1).

## The contract, in three sentences

`seon.sci.admit` stays the ONE bounded walk over a dangerous value and
now emits a **closed, wire-total node grammar** that preserves everything
a printer needs — list-vs-vector, record type, var name, class name,
object identity, and *why* a cut happened. `seon.print` is a
`print-method`-shaped multimethod over that finite grammar whose second
argument is a **sink** rather than a `Writer`: one dispatch table, one
traversal, and the sink decides whether a token becomes REPL text or a
hiccup node. Everything above — the transcript, the debug page, the
`/data` drill, per-entry compaction detail — is that emitter run with
different sinks and different bounded **options**, never a second printer.

## Dependency ledger

| Dependency | Read | What it establishes |
|---|---|---|
| Clojure 1.12.5 printer | `clojure-1.12.5.jar` `clojure/core.clj:3693-3704`, `clojure/core_print.clj:48-70,104-121,168-176,225-227,229-268,317-340,382-385,461-462` | the whole shape we mirror |
| `clojure.instant` / `clojure.uuid` | `clojure/instant.clj:175-186`, `clojure/uuid.clj:16-20` | `#inst` / `#uuid` faces |
| `clojure.main` triage | `clojure/main.clj:207,268,342` | the error face (S2's owner, not this doc's) |
| sci printing | `reference-code/sci/src/sci/lang.cljc:51-52,294-297`, `sci/impl/records.cljc:35-37,243-249,400-401`, `sci/impl/namespaces.cljc:1204-1216,1519-1520` | sci ALREADY prints vars/types/records/namespaces correctly; sci REFUSES agent `print-method` by design |
| our admission | `src/seon/sci/admit.clj:55-74,124-135,215-304,352-402` | the walk, the budget accounting, the marker vocabulary |
| our floor | `src/seon/render/value.cljc:229-254,260-406` | `prepare` + the twins + the HTML node builders that this design deletes |
| our options schema | `resources/seon/schema/render_value.edn:7-40` | `max-depth/max-collection/max-string/width` already declared with defaults |
| ruling #25 | `plan/README.md:1712-1727` | generous caps + `seon.blob` + `capped?` derived from `result-size` |
| the dynamic transcript | `plan/repl-session-context-2026-08-01.md:100-149` | per-entry options at compaction boundaries |

Probes: `tmp/print_path_probe.clj`, `tmp/print_path_probe2.clj`
(`clojure -M:dev -i …`). Every measured claim below is from those two
files, run 2026-08-01 on Clojure 1.12.5.

## How Clojure's printer actually works (the metal)

Five facts, all load-bearing for this design:

1. **`print-method` is a multimethod whose dispatch is `:type`-metadata
   first, class second** — `(fn [x writer] (let [t (get (meta x) :type)]
   (if (keyword? t) t (class x))))` (`core.clj:3693-3695`). Clojure's own
   printer already supports a *data-level* face tag. Probed: a map with
   `^{:type :seon.print/probe}` prints through a custom `defmethod`, and
   does so nested inside a vector (`[1 #'user/x 2]`).
2. **One combinator does every collection.** `print-sequential`
   (`core_print.clj:48-70`) takes `begin`, `print-one`, `sep`, `end` and
   is called with `"(" … " " ")"` for `ISeq` (`:174-176`), `"[" … " " "]"`
   for vectors (`:225-227`), `"#{" … " " "}"` for sets (`:338-340`), and
   a prefixed `"{"`/`", "`/`"}"` for maps through `print-prefix-map`
   (`:229-239`). *Faces differ only in four strings.*
3. **Elision has two distinct idioms.** Inside `print-sequential`,
   exceeding `*print-length*` writes `"..."`; exceeding `*print-level*`
   writes `"#"` for the whole node (`:49-60`). Probed: `(0 1 2 ...)` and
   `{:a {:b #}}`.
4. **Opaque values print through one shape.** `print-tagged-object`
   (`:104-115`) writes `#object[<class> 0x<identityHashCode> <rep>]`;
   `IDeref` supplies a `{:status :ready, :val …}` rep by dereferencing
   (`:461-462`) — the one thing we must NOT copy.
5. **Namespaced-map lifting is a printer decision, not a data one** —
   `lift-ns` under `*print-namespace-maps*` (`:247-268`).

And the sci side, probed live: sci ALREADY prints `#'user/x` for a var
(`lang.cljc:294-297`), `user.R2` for a record type (`lang.cljc:51-52`),
`#user.R{:a 1, :b 2}` for a record instance (`records.cljc:35-37`,
`:400-401`), `#object[sci.lang.Namespace 0x5e77a52d "foo.bar"]` for a
namespace, and `#object[clojure.lang.Atom 0x7a01b95 {:status :ready,
:val 1}]` for an atom. **Every one of the audit's D2/D8/D9/D15/D16
divergences is ours, not sci's**: admission replaces those values with
marker maps before anything prints them.

## The three pieces

```text
  raw value ──[ admission walk ]──▶ admitted tree ──[ emit walk ]──┬─▶ text sink  → REPL bytes
   (dangerous, unbounded)            (finite, EDN,                 └─▶ hiccup sink → collapsible HTML
                                      closed grammar)                 (or one tee sink → both in one pass)
```

Two walks, honestly named, because they have different jobs:

- the **admission walk** is the safety walk. It is the only code allowed
  near a raw value; it calls the `:interrupt-fn` at every node, never
  dereferences, never realizes an unbounded tail, and charges a node
  budget (`admit.clj:76-176`). It is not going away and it does not gain
  a second leaf.
- the **emit walk** is the printer. It runs over a value that is already
  finite, cycle-free, and pure data — so it needs no interrupt-fn, no
  budget, and no safety reasoning at all. *This* is the traversal with
  two leaves.

The crux's "one traversal" is satisfied exactly where it matters: from
the admitted tree there is ONE traversal and ONE dispatch table, and the
ai text and the html are guaranteed to agree because they are the same
token stream (see the tee property below).

### Piece 1 — the sink protocol (Clojure's `Writer`, generalized)

```clojure
(defprotocol Sink
  (-open  [sink node]  "Enter a structural node: its kind, path, delimiters, summary.")
  (-token [sink face text] "One lexeme with its face.")
  (-close [sink node]  "Leave the structural node."))
```

`node` is `{::kind ::list|::vector|::map|::set|::record|::table
            ::path [...] ::begin "(" ::end ")" ::sep " " ::name "user.R"
            ::count 189}` — everything both sinks need, computed once.

`face` is the closed lexeme vocabulary
`#{::delimiter ::separator ::keyword ::symbol ::string ::number ::nil
   ::boolean ::char ::tag ::object ::elision ::prune}` — the CSS class
list on the html side and, on the text side, ignored.

- **text sink** — a `StringBuilder`. `-open` appends `::begin`, `-close`
  appends `::end`, `-token` appends the text. Byte-for-byte a REPL.
- **hiccup sink** — a stack of children. `-open` pushes
  `[:details {:id (node-id unit path) :open …} [:summary summary]
   [:span {:class "seon-print-delim"} begin]`; `-close` appends the end
  delimiter and pops into the parent; `-token` appends
  `[:span {:class (face-class face)} text]`. Drill links, breadcrumbs and
  the pager stay exactly where they are today (`value.cljc:55-66,408-434`)
  because the sink is handed the same `unit` and the same `path`.
- **tee sink** — forwards to both. The two-pane debug page (exact AI
  context left, walked units right) is then literally one traversal.

Why a protocol and not a `Writer`: a `Writer` can only accept characters,
so the html leaf would have to re-parse them. `-open`/`-close` is the
minimum addition that lets a structural sink exist, and it is the same
information `print-sequential` already threads (`begin`, `sep`, `end`).

### Piece 2 — the emitter (mirrors `print-method`, does not touch it)

```clojure
(defmulti emit
  (fn [node _sink _options]
    (or (face-key node) (class node))))
```

`face-key` reads the node's discriminating marker key (below); when there
is none it falls through to `(class node)` — structurally identical to
`core.clj:3693-3695`, with the face carried **in data instead of
metadata**.

**Why data, not `^{:type …}` metadata** — even though metadata is the
metal-exact hook and was probed working: the admitted tree is stored as a
STRING (`:seon.cluster.eval/result-edn`, `admit.clj:399`) and read back at
render time (`transcript.clj:257-268`). Metadata does not survive that
round trip, so a metadata-carried face would make the transcript
unrenderable from stored facts. Data-carried faces are wire-total.

**Why our own multimethod and not `defmethod print-method`** — a
`^{:type ::var}` defmethod on `clojure.core/print-method` would hijack
`pr-str` process-wide: the DATA projection could then never be printed as
data. Same structure, private multimethod, zero global mutation.

One shared combinator does every collection, exactly as
`print-sequential` does:

```clojure
(defn- emit-sequential [node children sink options] …)
```

It owns the width bound (`::max-collection` → `...`), the depth bound
(`::max-depth` → `#`), the separator, and the optional line breaking.

### Piece 3 — the admitted grammar (the schema change)

The current marker vocabulary — `::opaque ::reference ::type
::truncated-string ::elided ::projection-error ::name`
(`admit.clj:55-74,124-135,240-304`) — is *nearly* sufficient; it fails on
five points, all of which are admission-side information loss that no
printer can recover:

| Lost today | Where | Consequence |
|---|---|---|
| seq-vs-vector | `admit.clj:286-288` folds coll/seq/`java.util.Collection` into vectors | D1 — `(map inc [1 2])` prints `[2 3]` |
| record identity as a tag | `admit.clj:267-272` puts `::type` inside the field map | D9 — `{:a 1, :b 2, :seon.sci.admit/type "user.R3"}`, and a field collision is representable |
| the class NAME of a `Class` value | `admit.clj:298-304` markers it as plain opaque | D16 — `(class "s")` prints `#:seon.sci.admit{:opaque "java.lang.Class"}` |
| object identity | never captured | `#object[…]` cannot show `0x…` |
| WHY a cut happened | one `::elided` for width, depth, and budget | D5 — cannot choose between `...` and `#` |

The replacement is a **closed grammar**: an admitted node is an ordinary
scalar, an ordinary collection (now including LISTS), or a marker map
with exactly one discriminating key.

```clojure
;; ordinary, unchanged: nil boolean number keyword symbol char string
;;                      uuid java.util.Date
;; ordinary collections: {} #{} [] and — new — ()
;; cut scalars:
::elided     ; a width/budget cut inside a collection      → "..."
::pruned     ; a depth cut replacing a whole node          → "#"
;; markers (exactly one discriminating key each):
{::var    "user/x"}
{::type   "user.R"}                              ; a type/class-like VALUE from sci
{::class  "java.lang.String"}                    ; a java.lang.Class value
{::record "user.R"  ::fields {:a 1 :b 2}}
{::object "clojure.lang.Atom" ::address "0x7a01b95" ::rep "…"}   ; ::rep optional
{::string "the first 262144 chars…" ::length 900000}
{::failed "java.lang.Class" ::message "…"}       ; the codec could not project this node
```

Notes on the grammar:

- **`::elided` stays a SCALAR.** That invariant is load-bearing
  (`admit.clj:96-102`: an elision must never be a structure, or the thing
  replacing an over-deep value would itself be over-deep) and the new
  `::pruned` scalar is added under the same rule.
- **The loud "N of M shown" line is a RESULT-level fact, not a node
  attribute.** Ruling #25 already derives `capped?` from `result-size`
  (`README.md:1724-1725`), so per-node counts are not needed to satisfy
  the audit's honesty question 2, and the walk still never calls `count`
  on a possibly-infinite source (`admit.clj:281-285`). When a source is
  `counted?` the collection node MAY carry `::count`; that is an
  optimization, not a requirement.
- **`::record` nests its fields** instead of injecting a key into them.
  This costs one depth level; under ruling #25's `max-depth 64` that is
  free, and it removes an ambiguity that is representable today.
- **`::address` is captured at admission**, not at print. Probed:
  `System/identityHashCode` is O(1) and stable in-process. Capturing it at
  admission makes the receipt's bytes deterministic forever — the same
  transcript re-renders identically after a restart, which is exactly what
  a stable prompt-cache prefix requires.
- **`::rep` is optional and never a deref.** Clojure's `IDeref` face
  dereferences (`core_print.clj:438-462`); we must not (`admit.clj:67-74`
  states why: deref is a cycle or a park). `::rep` is only ever the
  existing `safe-description` (`admit.clj:104-122`), so an atom prints
  `#object[clojure.lang.Atom 0x7a01b95]` — honest, and the divergence from
  stock is a *missing* rep, never a wrong one. (Deref-under-budget is a
  real alternative; see owner decision 3.)
- **Lists are safe.** Probed: `(pr-str (list 1 2 3))` → `"(1 2 3)"`, and
  `clojure.edn/read-string` returns a `seq?`/`list?` value; a hand-built
  projection `(1 2 3 :seon.sci.admit/elided)` round-trips exactly. A
  bounded projection of an infinite seq is a *finite list*, which is not a
  lie about finiteness — the `...` says what was cut. The
  `admit.clj:281-285` reasoning ("a bounded projection of a
  possibly-infinite thing cannot be that thing") argued against *claiming
  laziness*, and a list claims none.

Classification at admission (replacing `admit.clj:277-288`):

- `(vector? v)` or `(instance? java.util.RandomAccess v)` or a MapEntry →
  vector node. (MapEntry printing as `[:a 1]` is already stock — audit D1
  row, last case.)
- `(set? v)` or `(instance? java.util.Set v)` → set node.
- `(map? v)` (non-record) or `(instance? java.util.Map v)` → map node.
- `(record? v)` → `::record` node.
- `(seq? v)`, `(list? v)`, or any other `java.util.Collection` → **list
  node**.

This mirrors `core_print.clj:282-313`'s own `prefer-method` ordering, for
the same reason Clojure needs it: a `java.util.List` is both.

## The complete dispatch table

Every admitted node kind, its REPL text face, and its html node. "Same as
stock" means byte-identical to `clj` 1.12.5 for the values probed.

| Admitted node | Text face | Html node | Stock? |
|---|---|---|---|
| `nil` | `nil` | `span.seon-print-nil` | yes |
| `true` / `false` | `true` / `false` | `span.seon-print-boolean` | yes |
| number (long/double/ratio/bigint/bigdec) | `str` of it, with `##Inf`/`##NaN`/`N`/`M` suffixes (`core_print.clj:128-149,401-407`) | `span.seon-print-number` | yes |
| keyword / symbol | `str` of it | `span.seon-print-keyword` / `-symbol` | yes |
| char | `\a`, `\newline` (`core_print.clj:342-359`) | `span.seon-print-char` | yes |
| string | `"…"` with `char-escape-string` (`:200-221`) | `span.seon-print-string` | yes |
| `java.util.Date` | `#inst "…"` (`instant.clj:175-186`) | `span.seon-print-tag` + value | yes |
| `java.util.UUID` | `#uuid "…"` (`uuid.clj:16-17`) | `span.seon-print-tag` + value | yes |
| vector node | `[` children `]`, sep `" "` | `details` > `summary` "[] N items" > items | yes |
| **list node** | `(` children `)`, sep `" "` | `details` > `summary` "() N items" | **yes — D1 closed** |
| set node | `#{` children `}` | `details` > `summary` "#{} N members" | yes |
| map node | `{k v, k v}`; `#:ns{…}` when `::namespace-maps?` and liftable (`core_print.clj:247-268`) | `details` > `dl`/`dt`/`dd` (today's shape, `value.cljc:328-352`) | yes |
| `{::record "user.R" ::fields …}` | `#user.R{:a 1, :b 2}` | `details` > `summary` `#user.R` > the field `dl` | **yes — D9** |
| `{::var "user/x"}` | `#'user/x` | `span.seon-print-var` (linked to the fn's namespace page when the corpus knows it) | **yes — D2** |
| `{::type "user.R"}` | `user.R` | `span.seon-print-type` | **yes — D9 (the type value)** |
| `{::class "java.lang.String"}` | `java.lang.String` | `span.seon-print-type` | **yes — D16** |
| `{::object cls ::address a ::rep r}` | `#object[cls 0xa r]` / `#object[cls 0xa]` | `span.seon-print-object`, drill link when a rep exists | **near — D8/D15/D19** (see decision 3) |
| `{::string s ::length n}` | `"s…"` then, at the result level, the loud line | `span.seon-print-string` + `‹n chars›` + inspect link (today's shape, `value.cljc:301-307`) | **near — D5** |
| `::elided` (in a collection) | `...` | `span.seon-print-elision` + inspect link | **yes — D5** |
| `::pruned` (replacing a node) | `#` | `span.seon-print-elision` + inspect link | **yes — D5** |
| `{::failed cls ::message m}` | `#object[cls 0x0 "projection failed: m"]` | `span.seon-print-object.seon-print-failed` | honest |
| table face (derived, see below) | `print-table` pipe rows | `table`/`thead`/`tbody` | yes |

Faces the emitter does NOT own: the error report line (`Execution error
(IndexOutOfBoundsException) at user/eval168 (REPL:1).`) belongs to the
audit's S2 at `seon.sci.eval/diagnosis`, and the prompt/echo lines belong
to the transcript renderer. The emitter prints VALUES.

## Options, not thread bindings

`emit` takes an explicit options map — the already-declared
`:seon.render.value/options` (`render_value.edn:7-40`,
`max-depth 3`, `max-collection 8`, `max-string 80`, `width 72`) plus the
two print-var faces:

```clojure
{:seon.render.value/max-depth 3
 :seon.render.value/max-collection 8
 :seon.render.value/max-string 80
 :seon.render.value/width 72                 ; 0 disables line breaking
 :seon.render.value/namespace-maps? true
 :seon.render.value/table? :derived}         ; :derived | true | false
```

This one change discharges three separate obligations with one mechanism:

1. **D18/S6** — today `result-edn` is `(pr-str projection)` under whatever
   the HOST thread's `*print-*` bindings are (`admit.clj:399`). The
   agent's own `*print-length*` / `*print-level*` /
   `*print-namespace-maps*` (sci ships them —
   `sci/impl/namespaces.cljc:1502-1506`, exposed at
   `sci/core.cljc:166-171`) are read at admission and become the receipt's
   **default options**. Print vars shape the TEXT; caps bound the WALK;
   the two never mix.
2. **The dynamic transcript** — re-rendering an old entry small is the
   same emitter with `max-collection 2, max-depth 1`. Nothing is stored
   per detail level and nothing is destroyed at commit, which is exactly
   ruling #25's premise (`repl-session-context…:108-112`). The
   age/relevance policy that derives options at a compaction boundary
   stays where it already lives, in the context-budget layer.
3. **The unwired-options issue** —
   `docs/seon/issues/render-value-options-declared-but-unwired.md` closes
   here for `max-depth` / `max-string` / `width`, because the emitter is
   the first code that has ever had a reason to read them.

Bounds are applied at BOTH walks and they are not the same bound:
admission caps are the outer safety maxima (ruling #25's 65,536 nodes /
8,192 wide / 64 deep / 262,144 chars); options are the presentation bound
inside them. The emitter clamps its options to what the tree actually
contains and never invents detail the tree does not hold.

## Line breaking and `print-table`

**Measured, this run:** `pr-str` of a 65,536-node admitted tree takes
**3.06 ms** and produces 87,781 chars; `clojure.pprint/pprint` of the
same tree takes **327.6 ms** and produces 89,781 chars — **107× the time
for +2.3% bytes** (`tmp/print_path_probe2.clj`). That, plus the earlier
+6.7% token measurement, settles it:

- **The text sink never calls `clojure.pprint`.** Line breaking is a sink
  concern: the sink tracks its column, and `emit-sequential` breaks
  between children and indents only when a node's flat rendering would
  exceed `::width`. `width 0` (or a node that fits) means no work at all,
  so the common case pays nothing.
- **The html sink ignores `::width` entirely** — the browser wraps, and
  `<details>` collapse is the layout mechanism.

**`print-table` is a FACE in the dispatch table, not a separate path**
(ruling: print-table IS the table face,
`repl-session-context…:151-160`). It is selected by a DERIVED predicate,
never a hand list: a list/vector node whose children are ≥2 maps with
identical key sets, all of whose values print as single-line scalars. The
text sink emits the pipe/dash rows (`clojure.pprint/print-table`'s exact
bytes — probed: `"\n| :a | :b |\n|----+----|\n|  1 |  x |\n| 22 | yy |\n"`);
the html sink emits a real `<table>` from the same traversal. `::table?
:derived` is the default; `true`/`false` override per entry. Making
`clojure.pprint/print-table` *resolvable inside an agent eval* is the
audit's S3 and a different seam (`seon.sci.eval` base ctx) — when an agent
calls it, its output is captured output, not a result face.

## Owner decisions

### Decision 1 — what `:seon.cluster.eval/result-edn` means (S1's one ruling)

| | Option | Consequence |
|---|---|---|
| (a) | result-edn BECOMES REPL text | receipt loses its machine-readable projection; aging an entry smaller requires a blob read every time; `#'user/x` and `#object[…]` do not read through `clojure.edn` (probed) |
| (b) | two attributes — data + text | both derived once, but ~2× receipt bytes and two things that can disagree; a second mechanism for one fact |
| (c) | **result-edn stays DATA; text is emitted at render** | one stored projection, no duplication, aging is a re-emit with different options, and the transcript's existing `edn/read` (`transcript.clj:257-268`) is already the reader |

**Recommend (c).** The audit rejected (c) because "the printer must invert
markers, which is lossy for D1" (`audit:108-110`) — that objection dies
with this design: markers ARE the grammar, list-ness is preserved, and the
emitter never inverts anything. (c) is also the only option under which
"one traversal, two leaves" is literally true at render time: from one
stored fact, one walk, either sink, or a tee for both.

Cost of (c) is one `edn/read-string` per rendered entry. At the sizes that
actually appear in a transcript entry this is microseconds; the 65k-node
worst case is ~3 ms of print, and reading is the same order. If that ever
shows up in a render profile, the answer is a per-receipt memo of the read
tree, not a second stored attribute.

### Decision 2 — agent-defined `print-method`

**Falsified live, and the result is decisive.** Probe 5:

- Default sci: `(defmethod print-method String …)` throws
  *"Print-method is not allowed by default since it mutates the global
  runtime"* — sci defines its own poisoned multimethod for exactly this
  reason (`sci/impl/namespaces.cljc:1204-1216`).
- With the host's `print-method` mapped into the ctx
  (`{:namespaces {'clojure.core {'print-method print-method}}}`), the
  agent's defmethod **poisoned the host JVM**: the probe process then died
  inside `clojure.main`'s own error reporter, because every `String` in
  the process now printed through interpreted agent code. The stack trace
  is in the probe output.

**Recommend: agents do not define `print-method`; sci's existing refusal
stands and we never map the host var in.** The agent-facing way to control
how its data appears is the ONE render contract — a `:seon.render/ai`
renderer over its own facts — not a global printer mutation. This is not a
limitation to apologize for; it is the same rule the vocabulary table
already states for the render unit.

If it is ever asked for, the safe form is recorded here so nobody invents
a worse one: a **fork-local** multimethod (sci ctx-scoped, never the host
var), invoked from inside the admission walk (which is already inside the
armed `:interrupt-fn` boundary, so a runaway face is stopped by the time
limit like any other agent code), whose output is a STRING truncated by
`max-string`, and whose throw degrades to the ordinary `#object[…]` face.
Never on the render path, which runs outside any armed boundary.

### Decision 3 — two small honesty calls

**3a. The `#object[…]` class name.** Stock prints
`#object[clojure.lang.Namespace 0x… "foo.bar"]`; ours is a
`sci.lang.Namespace`. **Recommend telling the truth** (`sci.lang.Namespace`).
Renaming it would be the exact "faking" ruling #24 forbids, and the audit's
honesty question 3 already settles the analogous case.

**3b. Atoms: rep or no rep.** Stock shows
`#object[clojure.lang.Atom 0x… {:status :ready, :val 1}]` by
dereferencing (`core_print.clj:438-462`).
**Recommend no rep for now** — `#object[clojure.lang.Atom 0x7a01b95]` —
because `admit.clj:67-74`'s no-deref invariant is what makes a cycle
*unrepresentable rather than detected*. The alternative is real and worth
naming: deref only `clojure.lang.IAtom` (never `IPending`/`IBlockingDeref`,
which park) and project the value as an ordinary child under the same node
budget, which terminates on a self-referential atom because depth and node
caps bound it. That would close D15 byte-exactly. It is a one-line
classification change if the owner wants stock parity more than the
invariant.

## Migration — what changes, what dies

**New:** `src/seon/print.cljc` — `Sink` protocol, `text-sink`,
`hiccup-sink`, `tee-sink`, the `emit` multimethod, `emit-sequential`, the
face table. Portable `.cljc`; nothing in it is platform-specific except
`node-id`'s digest, which already has its reader conditional
(`value.cljc:43-46`). New schema file
`resources/seon/schema/print.edn` for the node grammar and the face
vocabulary; `render_value.edn` gains `namespace-maps?` and `table?`.

**Changes in `src/seon/sci/admit.clj`:**

- `project-node` (`:215-304`) — the collection branch splits vector / list
  / set / map; the record branch emits `::record`+`::fields` instead of
  injecting `::type`; `Class` values get `::class`; opaque and reference
  values converge on ONE `::object` marker with `::address`; the
  `IDeref` and array branches feed the same marker.
- `elide!` (`:96-102`) — gains its `::pruned` sibling for depth cuts,
  keeping `::elided` for width/budget cuts.
- `marker!`'s budget charge (`:124-135`) — unchanged in mechanism, but the
  new markers have different key counts, so the accounting test that
  falsified "257 nodes under a budget of 256" must be re-run against each
  new marker shape.
- `admit`'s return (`:352-402`) — unchanged. `result-edn` remains
  `(pr-str projection)` under decision 1(c). The docstring's stated
  grammar is replaced by the closed grammar above.

**Dies in `src/seon/render/value.cljc`:** `marker-map?` (`:260-267`),
`marker-text` (`:269-282`) — the `‹elided›` / `#‹type name›` faces are a
SECOND vocabulary and are deleted, not translated — `leaf` (`:295-326`),
`map-node` (`:328-352`), `sequential-node` (`:354-370`), `set-node`
(`:372-389`), `node-content` (`:391-399`), `html-node` (`:401-406`), and
`render-ai-data`'s `(pr-str tree)` + prose suffix (`:248-254`).
What survives is the unit-level adapter: `node-id`, `path-link`,
`opened-window`, `display-value`, `prepare`, `breadcrumbs`, `pager`, and
the two `render-ai` / `render-html` entry points, each now three lines
over `seon.print`.

**Changes in `src/seon/render/transcript.clj`:** `floor-text`,
`floor-value`, `bounded-scalar`, `bounded-result` (`:276-350`) call the
emitter with the entry's derived options instead of round-tripping
through the floor renderer. (The `;; transcript/entry` headers and
`(comment …)` sentences are dead under ruling #24 regardless; that is the
transcript wave's cut, not this one's.)

**Ordering.** This lands AFTER the caps/blob wave (ruling #25) — it reads
`result-size` for the loud line and assumes generous caps — and BEFORE or
alongside the audit's S2 (error triage) and S3 (`*1`/`*e`, `source`,
`clojure.pprint`), which touch `seon.sci.eval` and do not overlap this
diff. The admission grammar change and the emitter should land in ONE
commit: a half-migrated grammar means two vocabularies live at once,
which is precisely the failure this design deletes.

## Acceptance evidence

Every claim below is a named piece, and each is a recurring test — a
proof that ran once in a lane counts as NOT COVERED.

| Divergence | Closed by | Falsifier |
|---|---|---|
| **D1** seq→vector | admission list node + the list face | `(emit-text (admit (map inc [1 2])))` = `"(2 3)"`; also `'(1 2 3)`, `(keys m)`, `(sort …)`, `(seq "ab")`; `(first {:a 1})` = `"[:a 1]"` |
| **D2** var as marker | `::var` face | `(def x 41)` receipt text = `"#'user/x"` |
| **D5** elision face | `::elided`→`...`, `::pruned`→`#`, result-level loud line | `(range 200)` under `max-collection 64` ends `"… 63 ...)"` and the entry carries one `;; 64 of 189 shown`-shaped line derived from `result-size` |
| **D8** namespace | `::object` + name | `(in-ns 'foo.bar)` text = `#object[sci.lang.Namespace 0x… "foo.bar"]` (decision 3a) |
| **D9** record | `::record`/`::fields` + `::type` | `(->R 1 2)` = `"#user.R{:a 1, :b 2}"`; `(defrecord R [a b])` = `"user.R"` |
| **D15** atom/fn | `::object` + `::address` | `(atom 1)` = `#object[clojure.lang.Atom 0x…]`; `(fn [] 1)` = `#object[sci.impl.fns$… 0x…]` — no `seon.sci.admit` word anywhere (closes W5) |
| **D16** class | `::class` face | `(class "s")` = `"java.lang.String"` |
| **D18** host print vars | options derived from the agent's sci vars | `(set! *print-length* 3)` then `(range 10)` → `"(0 1 2 ...)"` in the RESULT line; the same receipt renders identically from two different host threads |
| **D19** meta `:ns` | falls out of D8 | `(meta #'f)` shows `#object[sci.lang.Namespace …]`, not a marker map |

Two structural properties that are worth more than the row-by-row tests,
because they make whole failure classes unrepresentable:

- **P-TEE — the twins cannot disagree.** For any admitted tree and any
  options, stripping tags and whitespace-normalizing the hiccup sink's
  output equals the text sink's output. One traversal, one token stream;
  a divergence is a bug in a sink, never in a face.
- **P-TOTAL — the grammar is closed.** Generatively, from the
  `:seon.print` node schema: every generated tree emits without throwing
  in both sinks, and every emitted text whose tree contains no `::object`
  / `::var` / `::type` / `::class` / `::elided` / `::pruned` node reads
  back through `clojure.edn` to an equal value. (The excluded faces are
  exactly the ones stock Clojure also cannot read back, which is the
  point: we are as readable as a REPL, not more.)

And one live proof, not a fixture: after the change, a real agent turn on
a freshly forked cluster whose reply contains `(def x 41)`, `(map inc
[1 2 3])`, `(defrecord R [a b])`, `(->R 1 2)` and `(atom 1)` renders a
transcript whose result lines are byte-identical to the same five forms
typed into `clojure -M -r`, except for the honest `sci.lang.*` class names
and the absent atom rep.

## Known follow-ons this design does NOT cover

- The error face (D3/D4/D10/D20) is the audit's S2 at
  `seon.sci.eval/diagnosis`; it prints a THROWABLE, not a value.
- `*1`/`*2`/`*3`/`*e`, `source` over corpus facts, and admitting
  `clojure.pprint` into the base ctx are S3.
- `#inst`/`#uuid` reader tags being refused on INPUT (D6) is S4 in
  `seon.sci.reader`; this document only fixes their OUTPUT faces.
- `(require …)` returning the namespace name (D14) is S5.
- W1 (`seon.sci.eval` is not hot-reloadable, the `default` cluster's door
  is broken) has its own issue and blocks live proof of any of this on
  that cluster.
