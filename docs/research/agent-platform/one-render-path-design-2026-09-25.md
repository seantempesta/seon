---
type: design
status: "owner adopted shell-order floor 2026-09-25; Opus first pass, awaiting Astra review"
created: 2026-09-25
scope: One schema dispatch and one shape-aware, budget-owning floor printer
---
# One render path

Changes after [Opus review 8a9e946d7](one-render-path-review-2026-09-25.md), all findings accepted by root:
Declared identity ownership and producer error/return alternatives replace structural discovery without losing pairs.
The floor uses one character budget spent in shell order (owner, 2026-09-25), fipp layout for text and an explorable page; renderers keep merged units.
Problems returns data; prepare owns offset retrieval; overlong renderer text yields a named diagnostic.
The revised slices land replacements before dispatch removal, convert explicit owners atomically, and price prompt admission.

Seon has declared schema pairs, a structural floor, and several competing selection and clipping paths. Compose the existing schema registry, guarded invocation and print nodes: **value + declared identity → schema renderer, else floor**.

## Authority and evidence

**The owner's correction and current CLAUDE presentation law govern this revision:**
“the floor printer was supposed to be smart and show snippets of each part of shapes so deeply nested large values still show part of the values while staying under the budget. This has a budget.”
There is **one presentation budget, inside the floor**. Render functions choose what to show; callers do not clip. Do not replace that budget with fixed depth/children/string limits. Those would preserve the wrong behavior.
The “Render ordinary values totally” and “Apply presentation limits once” laws remain, interpreted by this correction. Only this document is authorized for editing. The review was read end to end; accepted findings are integrated below.
Read end to end: CLAUDE.md and [clipping inventory](clipping-sites-2026-09-25.md) (`c6e75e57c`), [prompt pipeline](agent-context-render-pipeline-2026-09-24.md), [context/query design](context-as-query-and-render-2026-09-23.md), and [rendering census](one-happy-path/rendering-2026-09-24.md). Their proposals are evidence, not cumulative requirements.
Original source/probe anchors are at `ddecb2ba4011bedb779cf9aa46efec48bb37d54f`; review anchors are at `4f509ca3b`.
The assignment's MCP-lane finding—declared renderers rarely apply, almost everything falls to `render-ai`—is reported evidence, not a measured frequency here.
**Historical live evidence from the initial design (not rerun for this revision):** MCP only, explicit root `/Users/sean/src/seon`, cluster `default`, JVM mode, `read_only=true`, 5,000-ms deadline.
`runtime_status` found PID 67823, start `2026-09-25T00:50:43Z`, alive but **faulted**, 15 open signatures, 365 failed tests and 18 unknown tests.
Its top-ten fault face is itself evidence of audit site 9; the counts exceed the displayed list. No JVM start, reload, adoption, test or provider call ran.
The first selection probe was refused because my request omitted required `:seon.db/db`; that is a malformed probe, not a product defect.
I then called the smaller installed selection owner with the database supplied:

```clojure
(let [start (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      result (mapv (fn [value] [value (#'seon.render/schema-producers projection
                                       {:seon.db/db database} value :seon.render/ai)])
                   [{:example/x 1} {} {:seon.problems/failed-tests []}])]
  {:design/schema-producers result
   :design/declared-problems (get-in projection [:seon.schema.projection/forms :seon.problems/problems])
   :design/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)})
```

Result: `[[#:example{:x 1} []] [{} []] [#:seon.problems{:failed-tests []} [seon.problems/ai-prose]]]`, **0.391666 ms**, prepl **2 ms**.
The returned installed problems declaration contains its AI/HTML pair; MCP retained the response artifact at digest `b10a6f2b755655f366fa61b977c9220debd20b171dd2e4bf2afac9e794a2dd5a` (5,234 bytes).
At that observation, named-attribute matching worked; an empty all-optional return lost its renderer.

```clojure
(let [start (System/nanoTime)
      profile (assoc (seon.render/agent-render-profile seon.config/defaults) :seon.render.profile/token-budget 128
                     :seon.render.profile/max-depth 8 :seon.render.profile/max-children 8)
      value {:sample/a {:sample/deep {:sample/text (apply str (repeat 500 "a"))}}
             :sample/b {:sample/deep {:sample/text (apply str (repeat 500 "b"))}}
             :sample/c {:sample/deep {:sample/text (apply str (repeat 500 "c"))}}}
      prepared (seon.render.value/prepare {:seon.render/value value :seon.render/profile profile
                                          :seon.render.value/root [:design/probe 1]})]
  {:design/text (:seon.render.value/text prepared)
   :design/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)})
```

Result: **2.136833 ms**, prepl **4 ms**. Text retained only `:sample/a`, replaced its subtree with an elision (`omitted 1`, path `[:sample/a]`), then an elision for the other two children (`omitted 2`, path `[]`, next-offset 1).
Both named the token budget and “the value has no result handle”; **no a/b/c leaf snippet survived**.
This was observed floor behavior, not a target proof. Memory/allocation and end-to-end rendering frequency were not measured.

## 1. One selection, based on declarations

Keep `seon.render/selection` and guarded SCI `invoke-selected`. Replace structural matching only when these lookups and their caller conversions land together. No new registry, ambient last-return state or `vary-meta` provenance stamp.
The review's live census found 326 paired schemas: 28 identity-bearing maps, 165 identity-less maps and 133 `:and` forms; **298 error/report pairs need producer alternatives**. Of 55 identity attributes, 29 occur in multiple maps.
These are review measurements (2.7-ms census, prepl 6 ms; ownership prepl 7 ms), not fresh measurements here.

1. **Errors at any depth:** resolve the value's own `:seon.error/operation` to that function's declared error output
   alternatives in the held projection's `:seon.schema.projection/function-contracts`. Validate against those only,
   using cached `malli.core/validator`: exactly one satisfied alternative supplies the schema; multiple matches name
   an ambiguity diagnostic. Compose its declared renderer with the error base. An uncontracted operation uses the
   base renderer; a contracted operation with no satisfied error alternative yields a named contract diagnostic.
   This applies inside faults/test results too; no top-level return provenance is needed for nested errors.
2. **Direct contracted calls/reports:** for a top-level `(f args…)`, resolve its head to the actual contracted Var
   under the evaluation's held namespace/program. Reuse `schema/function-matching-outputs-in` (`schema.clj:3888`)
   when evaluated arity/arguments are available; otherwise inspect that function's declared output alternatives.
   Validate the result against those alternatives only, preserving named schema references: one match selects,
   multiple matches diagnose, no match to a contracted return diagnoses. Do not re-evaluate argument forms.
   `let`, threading, `map`/`for`, literals and interop supply no inferred final-producer identity and go to the floor,
   where nested errors and entity maps still receive rules 1 and 3. No tracing of whichever function returned last.
3. **Entity maps:** declare the owner once on each identity attribute's schema using existing
   `:seon.program/row-schema` (`program.cljc:170–205`). Every identity attribute present must name the same schema.
   Select it only if **every present map key belongs to that schema, plus `:db/id`**. Missing/conflicting ownership
   or foreign envelope keys means floor, not agent/entity rendering. Partial pulls need not contain every required
   entity field; this is a closed-key ownership check, not a claim of complete validation. `:db/id` alone names no owner.
   Never search all schemas containing the attribute. Adding this property does not turn runtime rows into program rows.

**Inline faces come first (owner, 2026-09-25; AGENTS.md a54b689ff, f3a14238e).** Selection is `:explicit-value`, then
`:declared` (the schema default), then `:floor`. A map's own `:seon.render/ai` or `:seon.render/html` overrides the
default for that face only; a value may override one face or both. The face value is a renderer symbol, rendered data
(which reaches its face's emitter as data at the display edge, never a pre-printed string), or an inline function: an
ordinary agent function through the same SCI entrance, admission and instrumentation, receiving the one merged unit with
the database and other render inputs only when its declared input keys ask for them. Face keys are render data, not
entity attributes, so they do not void rule 3's ownership.
Within `:declared`, order: the error's own operation, then a known direct-call return, then eligible entity ownership,
then the attribute's named schema, else floor. A declared report result may legitimately contain an entity identity; rule 3
must not capture that report envelope.
For attribute/child identity, look up the registered attribute key and its named `:vector`/`:set`/`:maybe` element
reference in the registry; no Malli path propagation. Anonymous nested schemas convey no new named identity.
An attribute request stays about that value, never its parent. Missing declared pair means floor, not a wider search.
Plain Clojure values, including `{}`, `nil` and scalars, render without DB/SCI custody when no declaration is available.

Convert explicit and namespace owners in the **same dispatch-switch slice**: `oversight/unit` (`oversight.clj:211`)
declares its pair on `:seon.oversight/unit` and supplies that declared shape; make `seon.run/render-namespace-ai`
reachable by declaration, convert its four fixtures, and change walk/bootstrap/web owners (`walk.clj:774`,
`bootstrap.clj:324`, `web.clj:1463`). Wire SCI `shown-result` (`sci/eval.clj:3181`) and MCP (`cluster.clj:392`) there too.
Then remove explicit-value/request/namespace stages, specificity ranking and pulled/transacted matching from dispatch.
Retain `matching-shapes-in` and `14a9291ad` for independent schema diagnostics; do not delete the library function.
Malli seam: `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, `core.cljc:2561,2592,2637`
(`schema`, `properties`, cached `validator`). Cache declared alternatives/owned-key sets in the existing projection
by their declaration inputs; recompute when those change. Ownership is O(present keys), alternative selection is
O(number of that function's alternatives × validation cost). No registry scan or per-node contract compilation.

## 2. Render functions choose content

Keep the existing **one merged unit argument** (`producer-argument`, `render.clj:251–267`), including value, original caller path, target `:seon.render/ai` or `:seon.render/html`, retrieval provenance and needed DB/SCI custody.
Remove `:seon.render/profile` from producer units; only the floor receives B. No two-argument migration of 89 producers. A renderer returns a **smaller ordinary value or final text** (existing HTML Hiccup remains final target output).
Adjust output contracts at these boundaries; authors need no print-node grammar. Renderers choose counts, identities,
relevant fields and queries; they may hand sub-values to the same path, preserving original paths and declarations.
The floor allocates a returned value within the current share with that producer excluded at that path; it neither
redispatches into the same producer nor starts a fresh B. Nested calls share this invocation, never serialize early.

Final renderer text is charged in full against its share using the same emitter. If it overruns, replace it with a
**named renderer-overage diagnostic** identifying producer/path/allowance/actual cost, never trim the text or rerun
with a smaller dial. Diagnose at the parent if even that diagnostic cannot fit locally. The same rule covers failures
and invalid output. Final HTML is not substring-clipped; its visible text charge comes from its owning emitter,
not a second pretty-print cost model. Saved historical shown text remains terminal and is selected only as whole units.
`problems/ai-prose` returns its family-lines map: remove its nested `render-ai` call and `config/defaults` fallback.
Missing floor policy is derived once at the entrance from supplied custody or explicitly refused, never defaulted inside a renderer.

## 3. One floor: shell-order selection, then library layout

**What exists and what it becomes.** Today the floor walks the value depth-first while it spends a node counter
named after the token budget (`value-node*`, `value.clj:446–566`). `print/fit` then re-emits, estimates tokens and
halves limits until the text fits (`print.cljc:1234–1299`). `bounded-text`/`floor` (`value.clj:38–131`) and `window`
(`value.clj:297–366`, called from `cluster.clj:594`) are two more cut engines. The design replaces all four with one
data flow: **classify → shell-order selection under one character budget → pruned node tree → layout.**
Selection is ours. Layout is `fipp.engine` for the AI text, and our own Hiccup emitter for the explorable page.
The principal Clojure approach is a `loop` over a `java.util.PriorityQueue` of plain maps, the existing
`ordered-head`, and a pure function from node to document. There is no protocol, no dynamic Var and no retry.

Owner's words, 2026-09-25: the floor shows “snippets of each part of shapes so deeply nested large values still
show part of the values while staying under the budget.” The research recommendation
([prior art](value-printer-prior-art-2026-09-25.md) §3) is adopted. The three Astra review rounds
([slice-2 review](render-floor-slice2-review-2026-09-25.md)) are requirements. Each numbered safety trace
there has an answer in §3a–§3c, and the hostile regression list in §3g replays each trace.
The parked attempt (`tmp/render-floor-opus/slice2c-worktree-reference.patch`) is a lesson, not a base. Its
mistake was to classify *after* normalization, ordering and pricing had touched the value. Here, classification
comes first and is the only thing that touches an unclassified value.

### 3a. The one classification rule, applied before anything touches a value

`classify` is the floor's first question about any reached object. That includes a value, a map key, a set
member, a seq cell, a node payload, a carried coordinate, a requery identity and a `node-id` input. Nothing
else — ordering, pricing, hashing, schema lookup, path construction, printing, admission — may receive an
object that `classify` has not answered for. The answer is one of four kinds:

| Kind | Exactly which objects | What the floor may do |
|---|---|---|
| **traversable** | An instance of a `clojure.lang` persistent collection class that is `counted?`: array/hash/tree maps, vectors (including `MapEntry` and subvec), hash/tree sets, `PersistentList`, `PersistentQueue`. Also a record whose `print-method` entry is `identical?` to the `IRecord` default (`get-method` is a hierarchy lookup and runs no user code). | `count`, iterate, `nth`/`get` by a shown key. Members are classified one by one when reached. |
| **string** | `java.lang.String`, a final class. | `.length` and `subs` into fixed-width chunks of w = 16 characters, split at a code-point boundary. A chunk's charge is `(count (pr-str chunk))`, so escapes are counted by clojure.core itself. |
| **scalar** | nil, or exactly these classes, matched with `(identical? (class x) C)`, never `instance?` (`BigInteger`, `BigDecimal` and `Date` are not final): `Boolean`, `Long`, `Integer`, `Short`, `Byte`, `Double`, `Float`, `clojure.lang.BigInt`, `BigInteger`, `BigDecimal`, `clojure.lang.Ratio`, `Character`, `clojure.lang.Keyword`, `clojure.lang.Symbol`, `java.util.UUID`, `java.util.Date`. | First the **literal length is estimated from O(1) accessors**: name and namespace `.length` for idents; `.bitLength` × log₁₀2 for integers; `.precision` and `.scale` for decimals; a constant for the rest. Only when that estimate fits the room is `pr-str` called. The printed bytes are kept in the node, so emission never prints the scalar again. A scalar that cannot fit is unaffordable (§3b); nothing prints it. |
| **opaque** | Everything else. | Shown as `#object[Class 0xidentityhash]` from `.getName` of its class and `System/identityHashCode`. It is **never** seq'd, counted, hashed, compared, printed, admitted, dereferenced or `toString`'d. |

Five classes of object get a named rule.

- **Host `Counted`.** A class outside `clojure.lang` that implements `clojure.lang.Counted` or `IPersistentMap` (a
  `deftype`, a Java collection wrapper) is **opaque by class**. `count` is never called on it. Its face is
  finite: class name plus hash. This closes review re-check 2, item 6 (`display-value`'s `counted-size`), because
  `display-value` is deleted and totals come only from traversable collections.
- **Lazy seq: show the realized prefix, never realize.** A seq cell is stepped only if its class is a `clojure.lang` seq
  class other than `IteratorSeq`/`EnumerationSeq`, which call host iterators, and it is not an unrealized
  `clojure.lang.IPending`. The step uses `.more`, never `.next`/`seq` on an unclassified object. `Cons.more`
  and `ChunkedCons.more` return fields. A realized `LazySeq` returns its cached seq. Whatever `.more` returns is
  classified before it is touched. The first unrealized or opaque cell becomes the seq's tail cut with an
  unknown total: `(1 2 3 …?)`. So `(range)`, `(filter (constantly false) (range))`, `(cycle …)` and `(iterate …)`
  are finite: one realized cell at most, or `(…?)`.
  *Justification.* Realization runs arbitrary host code that no interrupt stops (AGENTS "Bound admission and
  await actual exit"). The printer runs on shared threads: MCP, web and error recording. The realized prefix is free and safe,
  and it is often all there is, for example after `doall` or when a result has been partly consumed.
  An agent that wants more asks inside its own evaluation, where its own bound applies:
  `(take 50 (value-at result/e12 path))`. An agent's top-level lazy result is realized by the evaluation that
  produced it, before `shown-result` renders it (§3f, unproven item 4). Realization is never done by the printer.
- **Record with a custom printer** (a Datahike DB, for example) is opaque unless a declared renderer claims it
  first (§1). The floor never calls the custom `print-method`. A default-printed record is traversable as
  `#Name{…}`; its generated `count`/`seq` are `defrecord`'s own code.
- **Throwable → a safe projection, not `Throwable->map`.** `Throwable->map` walks `.getCause` without a visited
  set (`reference-code/clojure/src/clj/clojure/core_print.clj:494–497`). A two-link cycle never ends there, and it
  materializes every frame before any budget applies. The floor instead builds one small persistent value:
  `#error {:via [{:type C :message M :data D} …] :trace [F …] :suppressed [...]}`.
  - `:via` is flat, outermost first, and the chain walk keeps an identity set. A repeated link ends `:via` with
    `{:cycle-to [:via i]}`, a reference and not a repeat.
  - Each accessor (`getMessage`, `ex-data`, `getStackTrace`, `getSuppressed`, `getCause`) runs in its own
    `try`. A throwing accessor becomes `{:accessor-failed Class}` in that slot and does not end the projection.
  - `:trace` is the root link's first-party frames (`refusal/first-party-frame?`), as `"ns/fn (file:line)"`
    strings after `Compiler/demunge`, as the current `link-lines` prints them.
  - The keys stay in this declared order: the projection is a node whose entries are not re-sorted (§3c).
  - `:message` and `:data` values are ordinary strings and maps, so shell order spreads B across every link's
    class, message and data before any trace grows.
  - **Limitation stated:** accessors are virtual methods, and a subclass override that never returns cannot be
    interrupted. The same is true of any host call. Only JDK and `ExceptionInfo` implementations are known inert.
- **Carried coordinates, requery forms and `node-id` inputs** get one rule. A cursor path, a root address
  (`:seon.render.value/root`, `:seon.render.call/id`), an identity value read by `identity-address`, a
  carried `:seon.render.data/path` and a carried `:seon.print/requery-id` are all checked the same way: each is run
  through this same selection with a small **coordinate allowance** (C = 256 characters), and it is valid only
  if it is shown **whole** and contains no opaque or cut part. This check comes before `node-id` hashes it
  (`id/digest` prints with unlimited `pr-str`, `id.clj:29–38`) and before `path-url` prints it.
  - An invalid entrance coordinate refuses by name with a declared `:seon.render.value/coordinate-error` over
    `:seon.error/base`, naming member, expected shape and the value's class.
  - An invalid carried coordinate is dropped at that node, and the node gets the refusal "carried coordinate
    is not a finite value".
  - `:seon.print/requery-form` is **never carried or printed from input**. It is always derived at emission
    from a valid identity and path (`requery-form`, `print.cljc:444–455`).
  - Paths that selection itself constructs are finite by construction. Their steps are scalar keys already
    shown whole (so ≤ B characters), ordinals, and chunk offsets.

  This closes re-check 2 item 3 and review-2 P1-C/P1-D.

### 3b. Selection: shell order under one budget

**Budget.** B is converted once at entry: `B = (tokens/estimate-chars token-budget calibration)`
(`tokens.cljc:159–169`; the live calibration is 3 characters per token). Only characters are charged.

**Order.** Give the root depth 0. A member has depth d and a 1-based ordinal o in its parent's canonical order
(§3c). Its **shell** is `s = max(d, o₁, …, o_d)`, where o₁…o_d are the ordinals along its path. The shell is
monotone down a path, so a child never pops before its parent. The queue pops by
`[s d o seq]`, where `seq` is the insertion counter. That makes ties round-robin across parents, not
first-parent-wins.

**Loop.**
1. Pop the least entry. Price its face exactly as the single-line emitter will print it:
   - a scalar: its literal;
   - a string: its quotes when opened, then one chunk per pop;
   - a map entry: key literal plus `" "`;
   - a collection: its brackets (`#:ns{` when factored, §3c);
   - an opaque object: its `#object[…]` face;
   - the separator before it.
2. When a collection or string opens, **reserve its tail-cut face** (`…N`, with digits(total)). The reserve is
   refunded if every member is shown.
3. If the price fits the room, admit the node and charge it. Then push its parent's **next** member (ordinal
   o + 1), and push the node's own first member if it is a collection. Only one pending member per open
   collection is ever queued. This is a k-way merge: queue size ≤ open collections + 1.
4. If the price does not fit, **close that node's parent.** The node and every later sibling become the parent's
   one tail cut. Selection then continues with the other branches, so one unaffordable scalar ends only its own
   collection, not the whole walk. It stops when the queue empties.

Every admitted node costs at least 1 character, so visited nodes V ≤ B. Work is O(V log V) queue operations.
On top of that, each opened unordered collection costs one O(N log n) `ordered-head` pass (§3c), with
n ≤ ⌊room/2⌋ + 1 because every shown member costs at least 2 characters. That pass is the one
proportional-to-input cost: a 10⁶-key map pays one iteration of its entries when it is opened, and it is
reported beside the number (§3h). There is no hidden subtree scan, no refit, no second emission and no search.

**Cuts** use Clerk's shape (`viewer.cljc:1668–1705`). Every closed collection or string yields one elision node
with `:seon.render.data/path`, `:seon.render.data/next-offset` (= shown count, or characters for a string),
`:seon.print/omitted` and `:seon.render.data/total`. An unknown total is typed `:seon.render.data/total-unknown`
and never guessed. The node also carries the requery identity or refusal. Visited members of any collection
are always a prefix, because a lower ordinal always has a lower-or-equal key. Therefore **one tail cut per
collection is complete evidence**, and `next-offset` is a real coordinate. A collection that opened but showed
no member is the same cut at offset 0, which retires `:subtree` as a separate unit.

**Equivalence.** When shell k is complete, the visible set is exactly the nodes with depth ≤ k whose every path
ordinal is ≤ k. That is what clojure.core prints under `*print-length* = k` and `*print-level* = k`
(`core_print.clj:48–70`), with our cut faces standing in for `...` and `#`. Shell k+1 is then spent in key order
until B runs out. This is Erlang `io_lib_pretty`'s iterative deepening (`io_lib_pretty.erl:839–893`), made
two-dimensional and computed in one pass instead of a binary search.

**Fairness argument.**
- (i) No node of shell k+1 is shown while any node of shell k is missing, except in a closed collection. So
  every branch gets a k-member, k-deep snippet before anything goes wider or deeper. That is the owner's
  sentence.
- (ii) A huge sibling cannot starve its neighbours. A collection's face costs its brackets, and it grows by one
  member per shell like everything else. A long string grows by one 16-character chunk per shell.
- (iii) Inside the partial shell, shallower nodes come first (structure before detail), then earlier ordinals,
  then round-robin.
- (iv) The only unfairness left is stated plainly: a scalar whose own literal exceeds the room closes its
  parent early.

Worked example: the live agent row below (§3i). At 600 tokens today's floor drops `:seon.agent/plan`,
`:seon.agent/runtime` and `:seon.agent/settings` whole, because one 155,067-character sibling forced the global
halving. Shell order shows all seven keys and the first seven turns.

**The huge-first-sibling regression.** Take `{:a (vec (range 100000)) :b {:c 1} :d "x"}` at B = 100 characters.
The draft probe in the old §3 showed that stopping at the first failure collapses this value into one elision.
Shell order prints `{:a [0 1 2 …99997], :b {:c 1}, :d "x"}` (38 characters) by shell 3. It then spends the rest
of B widening `:a` one member per shell, and the legend is reserved first. The regression asserts that `:b`,
`:c 1` and `"x"` survive, that `:a`'s cut has path `[:a]` with offset = shown and total 100000, and that the
flat length is ≤ B.

**Declared renderers inside the floor** (§1/§2) run when their node is reached, not before, and never during
pricing of another node. A returned smaller value takes that node's place in the queue at the same key. Final
renderer text is one leaf, charged whole. If it cannot be paid, it becomes §2's named renderer-overage
diagnostic in place. If that diagnostic cannot be paid either, the parent closes. §2's “share” means the room
at the reached node; no fresh B is ever started.

**Legend and minimum.** Reserve the legend's exact text L once at entry (about 90 characters). It is emitted
only when a cut exists. A value within L of B therefore loses one partial shell, which is the stated price of
never refitting. If B < L plus the root's cheapest face, selection returns the declared
`:seon.print/insufficient-floor-budget-error` (new, over `:seon.error/base`; `seon.print.edn` declares none today). It never claims a snippet fits.

### 3c. Ordering and path steps

- Vectors, lists, seqs, strings, sorted collections, records and print nodes keep their own order.
- A counted map or set is sorted **only when every key or member is a scalar or string**. The existing
  `ordered-head` (`value.clj:425–444`) takes its first n members in one pass. The comparator orders declared
  identity attributes first (`db/identity-attributes`, checked with `contains?` only for keyword keys, whose
  hash is cached). Then it orders by `[category literal-prefix]`. `literal-prefix` compares at most the first 64 characters,
  which is a fixed comparison window and not a display bound. Ties go to the iteration ordinal. So ordering
  never compares whole huge strings or composite values, and never calls `pr-str` or `compare-values`. That
  closes re-check 2 item 4 and the OOM path in open-items 2026-09-25 03:09Z. `print/compare-values` stays for
  `db.clj:2998` only.
- **Composite map keys:** a map with any non-scalar key keeps iteration order, and every entry whose key is
  composite is **elided whole**. It counts in the map's cut but is never shown or used as a path step.
  **Limitation:** `{[1 2] :x}`-shaped maps show only their scalar-keyed entries and a count. The legend's requery
  `(seq (value-at h path))` reaches the rest by position in the agent's own evaluation. Group-by results and
  tuple-keyed indexes are the known victims.
- **Sets with composite members** (Datalog results, for example) are not elided. Members are *values*, so each
  is shown through the same selection at an ordinal step `[:seon.render.data/nth i]` into the held value's
  iteration order. `value-at` gains that one step kind (`print.cljc:403–411`, +2 lines). The step is valid for
  the same held value, which is exactly what a result handle or digest names.
- The attribute projection (`attribute-value`, `value.clj:388–423`) runs only on a classified traversable
  component collection. Its `pr-str` tie-break (`:413`) becomes the iteration ordinal, and its
  `(sort-by str (keys …))` becomes the scalar-key rule. That closes re-check 2 item 2.

Every path step is therefore finite: a scalar already shown whole, an ordinal, `[:seon.render.data/nth i]`, or
a character offset.

### 3d. Emission: selection hands a pruned tree to layout

The pruned tree is plain print nodes. Each leaf is a known scalar with its bytes retained, a string chunk run,
a cut, or an opaque face. Each branch is a traversable face. **No unknown object reaches emission.** So no
`print-method`, `toString` or `deref` can ever run in layout, whichever library does it.

**Algorithm lineage.** Wadler-style pretty-printing has one document algebra: text, line, nest/align and group.
A group prints flat if it fits the remaining width, else its lines break.
- Oppen (1980) gave the first linear-time, bounded-space version: a scan buffer only as wide as the line.
- Wadler's "A prettier printer" (2003) made it an algebra with `group x = flatten x <|> x`.
- Leijen's wl-pprint added `align`, `hang` and `fill`.
- Swierstra and Chitil's "Linear, bounded, functional pretty-printing" (2009) pruned the lookahead to the
  width.
- Kiselyov, Peyton Jones and Sabry's "Lazy v. Yield" (2012) gave the strict-generator form that **fipp ports**
  (`reference-code/fipp/src/fipp/engine.cljc:1–3`; README "Fast!").
- Prettier's doc printer is the same algebra with a `fits` check to the line end.
- zprint searches rule-based alternatives (hang, flow, wrap) per form. It gives better code layout, at a cost
  that is not linear-bounded.
- gofmt deliberately does not break by width. It keeps author line breaks and aligns with elastic tabstops.

We take the Wadler/Oppen family through fipp, and we inherit its properties:
- A group stays on one line when it fits.
- A group that does not fit breaks its own lines, and each inner group gets its own chance
  (`doc/primitives.md`, `:group`).
- Time is linear in document size.
- Lookahead is bounded by the width (`annotate-begins`, `engine.cljc:92–162`), so memory is O(width).

**Library evaluation** (both read from source; both are on the development JVM's classpath):

| Choice | What it gives | Why / why not | `print.cljc` kept |
|---|---|---|---:|
| **A.** Keep Seon's emitter (`Sink` protocol, `TextSink`, greedy `soft-separator` wrap at a column) | Nothing new | Its wrap is column-greedy, not group-based, so it cannot keep a small map on one line and break a large one. It keeps about 400 lines of sinks and multimethods. | ≈ 915 |
| **B. Recommended.** `fipp.engine` 0.6.29 for text: we build the fipp document from nodes ourselves; our own `node->hiccup` for HTML | Group layout, `:align`/`:nest`, `:pass` for zero-width markup (`engine.cljc:24–65`). `annotate-rights` computes the **flat single-line width** of the document as a by-product (`:68–86`). | fipp is on **every** classpath: it is `metosin/malli`'s dependency in all four `.cpcache` bases. We do not use `fipp.edn`'s visitor, whose `visit-unknown` → `ednize` falls back to `(str x)` (`ednize.clj:45–49`), and nodes are not values anyway. We call `serialize` + `annotate-rights` + `annotate-begins` + `format-nodes` into a `StringBuilder`, not `pprint-document`, which writes to `*out*` and appends `println` (`:247–259`). | ≈ 635 |
| **C.** puget 1.1.2 | ANSI color schemes, `:print-handlers`, `:print-fallback`, `:namespace-maps` (`printer.clj:1–130`) | Its visitor works on **values**, so we would convert nodes back into values with tagged literals. It sorts collections of up to 80 elements with its own order (`:sort-keys`, `order-collection`, `:185–195`). Its namespace rule differs from ours. It emits no Hiccup structure. Decisively, it is **only on the development classpath**, reached through `lambdaisland/deep-diff` and flow-monitor, and absent from the 129-entry runtime classpath. We would keep our Hiccup emitter anyway. | ≈ 900 + a dev-only dependency |

**Recommendation: B.** It deletes the most. The Sink protocol, the three sinks, the `emit` multimethod family,
table emission and the fit family go, and two pure functions replace them: `node->doc`, about 55 lines, and
`node->hiccup`, about 45. **HTML needs our own emitter anyway**, said plainly. The page needs
structure (`<details>` per collection, expanders at cuts, path attributes) that a laid-out character stream
cannot carry. So the one decision is the pruned tree, laid out twice: fipp for text, and the browser for HTML
from our Hiccup.

**Keeping the charge honest.**
- Selection charges the exact flat single-line characters: retained scalar bytes, chunk `pr-str` lengths, the
  emitter's own delimiter, separator and cut-face strings, and the legend. Namespace factoring is decided when
  the map opens (all keys share one namespace and there are at least 2 keys), and is then charged exactly:
  `#:ns{` once, keys by name.
- After layout, fipp's final `annotate-rights` position **is** the flat width. It is compared with B **once**. A
  larger value is a declared `:seon.print/allocator-charge-error` (new, over `:seon.error/base`) naming path, B, charge and width. It is never a second attempt.
- The layout's newlines and indentation are **excluded from B and measured once after layout**. The prepared
  projection records `:seon.render.value/flat-chars` and `:seon.render.value/text-chars`. The saved text is exactly
  what the AI sees, and whole-unit prompt admission (§5) counts those saved bytes. Layout never clips.
  **Measured (probe below):** `fipp.edn`'s bracket-`:align` style turned a 1,573-character flat agent selection
  into 3,783 characters, 2.4× more. So the AI face does **not** align to the bracket column.

### 3e. Readability rules for the AI text (the one clipped face)

The owner's ruling: the AI text is pretty-printed and clipped. Its exact text is what the AI sees and what is
saved. The readability rules are these.

- **Width 100, nest by level, not by column.**
  - A collection is `[:group open [:nest 1 …] close]`.
  - A map entry is `[:group key [:nest 2 :line value]]`. When an entry does not fit, its value flows to the
    next line indented by 2 under the key (zprint's "flow"), instead of hanging at the key's end column.
  - Indentation grows 1–2 columns per level. Measure it against the 2.4× bracket-align figure (§3h).
- **Collections of scalars fill; collections of collections go one per line.** fipp has no `fill` combinator.
  Wrapping each separator and its following member in its own group, `[:group :line member]`, gives Wadler's
  `fill`: the member goes on the current line only if it fits there. Without this, fipp prints a broken
  500-number vector as 500 lines.
- **No key/value column alignment.** gofmt-style tabstops cost whitespace, and it lands where the budget is
  tightest. Entries are separated by `,` then `:line`, as in `fipp.edn` and the current emitter.
- **Namespaced maps.** `#:ns{…}` only when all keys share one namespace, there are at least 2 keys, and it is
  shorter. This is today's `same-key-namespace` (`print.cljc:581–591`), plus the 2-key rule. puget's majority
  rule is rejected because it mixes qualified and unqualified keys in one literal.
- **Order.** Identity attributes first, then scalar keys (§3c). The order is decided once in selection and
  never re-sorted by the emitter; the `emit-node` re-sort at `print.cljc:856–871` is deleted.
- **Cuts read inline.**
  - `[0 1 2 …99997]` and `#{:a :b …12}` in sequences and sets.
  - `{:a 1, :… 3}` in a map. The cut is a `:…` entry, so the literal still reads as a map.
  - `"first chars…+452"` for a string.
  - `(1 2 3 …?)` for an unknown total.
  - A collection that showed nothing is `{:… 7}`.
  - The **one legend** follows the value on its own line, only when a cut exists:
    `{:seon.print/cuts "…N = N more", :seon.print/requery (seon.print/value-at result/e12 path)}`, or
    `:seon.print/requery-refusal "the value has no result handle"`.
  - Per-cut paths are not printed. The reader sees where a cut sits, and path, offset and total stay in the node
    for the page and `get_value`.
  - **Limitation:** a real string ending in `…+N` reads the same as a cut. The node and the page keep the
    difference.
- **Long strings** are never broken inside the literal: a fipp `:text` is atomic. A string that flows sits on its
  own line under its key. Newlines stay escaped (`\n`), which is EDN-faithful; the page shows them as real line
  breaks.
- **Errors** read as `#error {:via [{:type … :message … :data …} …] :trace ["seon.db/q (db.clj:2292)" …]}`.
  The cause chain is flat and outermost first, as the Clojure REPL's `Throwable->map` presents it, and the trace
  comes last.
- **A very wide value** fills lines of scalars to width 100 and ends in `…N`. **A very deep value** is shown to
  depth k only, where k is the shell reached, with each level indented one or two columns more.

### 3f. The explorable page, `get_value`, and the side-by-side debug view

There are exactly two faces from **one selection**. The AI text is clipped. The HTML is explorable, not clipped.
The page's initial selection is the same pruned tree as the AI's, so the user always has the same context and more.

**HTML face.** `node->hiccup` renders the same tree:
- a `<details>` per collection, summary `{} 7 keys`, as `close-frame` does today (`print.cljc:257–276`);
- strings in `white-space: pre-wrap`;
- **each cut as a lazy expander**:
  `[:button {:id cut-id :class "seon-print-cut" :data-on:click "@get('/data?…')"} "…38"]`.

Opening an expander fetches that cut's coordinates from the server. The server answers with the **next
shell-order selection at that path and offset**, which is `prepare` at (path, offset) under the same B, and a
Datastar patch replaces the button by id. This is Clerk's fetch-more: `present-elision*` re-presents at
`path` from `offset` with a fresh budget (`viewer.cljc:1731–1734`), served by `serve-blob`
(`webserver.clj:103–111`). It is also Orchard's paging, where each page is the next `page-size` members from
`start-idx` (`inspect.clj:44, :113–152, :161–216`). Each fetched fragment's own cuts are expanders again, so
nothing is ever lost, and HTML is never substring-clipped.

**The expansion endpoint** reuses the existing `/data` route (`route.clj:25`, `web.clj:3511` `data-response`,
which already reads `value`, `entity`, `path` and `offset` through `data/parse-cursor`, `data.clj:27–44`).
- Request: `GET /data?value=<blob digest>|entity=<lookup>&path=<EDN vector>&offset=<n>&cut=<element id>`, with
  Datastar's `datastar-request: true` header.
- Response: when `cut` is present, `data-response` returns only the fragment, as the debug responses already do
  (`debug-response`, `web.clj:3281–3302`, `datastar-mode replace`).
- **Custody is the server's:** the service's connection and database value, and its SCI context for declared
  renderers. The browser supplies only coordinates, never a form.
- The path goes through `parse-cursor`, whose EDN read is finite, and then through the §3a coordinate rule.
- A live, unsaved value has no expander. Its root says “not retrievable: no stored artifact”.

**Historical validity.** A cut's requery stays valid because it names a **content digest**. A blob is
immutable, so the same digest is the same value forever. Two sources exist today:
- MCP values already store `artifact-edn` blobs (`cluster.clj:445–479`).
- Agent evaluations do not. `shown-result` (`sci/eval.clj:3186–3232`) keeps only `:seon.eval/shown`.

The cut therefore adds one durable write, which is a **data decision for root/owner review**: store the admitted
value's artifact through that same `artifact-edn` + `blob/put!` pair at settlement, and record `:seon.blob/digest`
(an existing attribute) on the evaluation. When a blob has been swept, the expander shows the existing
value-not-found diagnostic (`web.clj:3530–3538`) in its place: missing-artifact evidence, not silence.
Entity cuts (`entity=`) show the **current** entity, and they say so. Only a digest pins history.

**Bounds per expansion** are query-work bounds, not clips:
- One `prepare` under B: O(V log V + opened N log n).
- One blob read: `read-artifact` parses the whole blob, O(blob size) per expansion. This is measured (§3h);
  if it is over 50 ms, key the parsed artifact by digest in an existing cache, never a new one.
- The existing request deadline.

The fragment's own cuts carry the rest.

**`get_value` is `prepare` at a path and offset.** `mcp-get-value` (`cluster.clj:555–597`) keeps its blob read
and `render.data/at`. Its string-window branch and `render.value/window` call (`:575–594`) become one `prepare`
with the cursor path and offset. Its reply is the prepared text plus each cut's node coordinates.
Preserved evidence: total, next offset, beyond-end (offset ≥ total yields a declared beyond-end result), and
the missing-digest errors (`:599–614`).
Deleted: `window`, `stable-entries` and its `pr-str` sort, `counted-size`, `display-value`, `pager`, and the
page-size cut from `:seon.render.value/max-collection`.

**Debug view: side by side.** The outline unit (`transcript.clj:1574–1612`) today *toggles* between “As the
model saw it” (`pre.seon-outline-ai`, the exact `:seon.render.history/bytes`) and “Rendered”. It becomes one
two-column body:
- left: the **saved AI bytes**, unchanged, still byte for byte (`web_debug_test.clj:1007`);
- right: the **explorable HTML** of the same evaluation's stored value, from its digest, at the same B.

The toggle signal and button are deleted. Historical units use the saved shown text and the stored digest.
Expansion uses live retrieval through `/data`. The agent debug page and the namespace debug page
(`/ns/{ns}/debug`, `debug-found-value`, `web.clj:1278`) use the same pair wherever they show a value.

### 3g. Regressions

**Ordinary regressions** are finite, focused, and run on a branch with real SCI and armed contracts:
1. The owner's three-branch probe at B = 128 tokens: every branch shows its `:sample/deep`/`:sample/text` and a
   prefix of its string, flat ≤ B.
2. Huge first sibling (§3b).
3. Deep-first: `{:a {:b {:c {:d {:e 1}}}} :z 2}` at small B keeps `:z 2`.
4. Fifty 1,000-character strings: every string shows a prefix.
5. The retained `background-poll-keeps-identity-while-payloads-grow` and `fit-preserves-breadth-and-long-strings`,
   converted to `prepare`.
6. Carried string/children totals and carried custody (the `preserve-requery` intent).
7. Exact charge: flat width = Σ charges ≤ B.
8. Identity-first order; scalar-key ordered head; composite-key entries elided with their count; a composite set
   shown by `nth` steps that `value-at` reads back.
9. Throwable: a three-link ex-data chain shares B; a throwing `getMessage` is contained to its slot.
10. `get_value` paging reconstructs a vector, a map and a string exactly from successive (path, offset)
    fragments; beyond-end; missing digest.
11. The legend appears exactly once and only with cuts; the refusal text when there is no handle.
12. Page parity: every AI-visible leaf is in the HTML, and every AI cut is exactly one expander whose fetched
    fragment equals `prepare` at its coordinates.
13. Debug side by side: the saved bytes and the explorable HTML render together for one unit.
14. Layout: width-100 text; a scalar vector fills; flat and text lengths are recorded.

**Hostile shapes** run **only** as tests carrying `^{:seon.test/fixture-observation "…"}`. That makes them
isolated platform-host members (`seon.test.edn:12`, clojure-testing skill). Root runs them; they never run in
default's JVM (PROBE SAFETY). Each has a declared bound of 1 s and asserts a finite, cut or opaque face.
- `(range)` and `(filter (constantly false) (range))`, placed as: root, vector member, map value, `array-map`
  key, set member, path step, requery id, cursor path, root address and identity value.
- A host `Counted` whose `count` throws or never returns; a `java.util.Map` and a `Collection` whose iterator
  never ends.
- A custom-`print-method` record whose printing throws; a `deftype` implementing `IPersistentMap`.
- A two-Throwable cause cycle; a Throwable whose `getMessage` throws.
- A 5 MB string key and value; a 1 MB keyword; `BigInteger` 10¹⁰⁰⁰⁰⁰.
- A singleton-vector key chain 10⁵ deep; a singleton-map value chain 5,000 deep at default B.
- A 10⁶-key map; a `Number` subclass and a `Date` subclass with throwing `toString`.

### 3h. Cost, measured and to measure

Current floor, measured 2026-09-25 on default (pid 35446) in MCP JVM mode, read-only, on the values in §3i:
- agent row (155,340 flat characters): 6.7 ms at 150 tokens, 18.9 ms at 600;
- fault: 5.2 / 3.1 ms;
- owner probe: 1.6 / 0.4 ms;
- test report: 0.7 ms.

`fipp.edn/pprint` of the 1,573-character pruned agent value took 6.1 ms including JIT warm-up. That was fipp
running on finite data, not new code.

Target: selection O(V log V) plus opened-collection passes, layout O(output), both sub-second at default B
(15,000 tokens = 45,000 characters). The folded cut records on parent and change:
- elapsed time and allocation;
- visited nodes, queue maximum, opened-collection iterations and floor hits;
- flat and text characters.

Rules: fix a regression over 20% or 50 ms first. Any case over 1 s names its opened collection.

### 3i. Four live values: today and under this design

Today's text below is **measured**; values are abbreviated with `…`. The design's text is **derived by hand**
from §3b–§3e, and its layout was checked with `fipp.edn` at width 100 except for the nest/flow indentation. It
is illustrative until the regression runs.

1. **Owner probe** (`{:sample/a {:sample/deep {:sample/text <500 a>}} …b …c}`).
   - Today, 128 tokens: `{:sample/a {<250-char elision map>}, :seon.print/elision {<elision>…omitted 2}}`
     (570 characters, over budget). No leaf survives.
   - Design, 128 tokens:
     ```clojure
     #:sample{:a {:sample/deep {:sample/text "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa…+452"}},
              :b {:sample/deep {:sample/text "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb…+468"}},
              :c {:sample/deep {:sample/text "cccccccccccccccccccccccccccccccc…+468"}}}
     {:seon.print/cuts "…N = N more", :seon.print/requery-refusal "the value has no result handle"}
     ```
   - At 600 tokens both floors show the whole value (1,632 flat characters). The design wraps each string onto
     its own line.
2. **Root agent row** (a live `(db/pull db '[*] [:seon.agent/id "root"])`: 7 keys, 155,340 characters, of which
   `:seon.agent/runtime` holds 42 turns).
   - Today: the same 399 characters at **both** 150 and 600 tokens:
     `{:db/id 42410, :seon.agent/branch :cluster-default, :seon.agent/id "root", :seon.agent/namespace #:db{:id 42404}, :seon.print/elision {…:omitted 3…}}`.
     Plan, runtime and settings are gone.
   - Design, 150 tokens: `:seon.agent/id "root"` first, then `:db/id`, `:branch`, `:namespace {:db/id 42404}`,
     `:plan {:db/id 42411, :my.plan/agent {:… 1}}`, and
     `:runtime {:db/id 42413, :seon.runtime/agent {…}, :seon.runtime/trigger {…}, :seon.runtime/turns [{:… 8} ×6 …36]}`.
     `:settings` is shell 7, so it is the map's `:… 1`.
     **Observed trade-off, for review:** at this budget shell order prefers six empty turn shells to the seventh
     root key.
   - Design, 600 tokens: all seven keys; runtime's four keys; the first seven turns with seven of their eight keys
     each, `:… 1` apiece; turns closed by `…35`. That is about 1,573 flat characters.
3. **A fault with an ex-data chain** (`ex-info` → `ex-info` → `IllegalStateException`).
   - Today, 150 tokens: `#error {<elision of the whole throwable>}`. Nothing is shown.
   - Today, 600 tokens: 1,772 characters that open with **16 REPL frames** (`[user$eval1359338 invokeStatic …]`)
     before `:via`.
   - Design, 150 tokens:
     ```clojure
     #error {:via [{:type clojure.lang.ExceptionInfo,
                    :message "Agent run failed",
                    :data {:seon.error/operation seon.turn/call-turn, :… 3}}
                   {:type clojure.lang.ExceptionInfo, :message "Datahike refused the…+15", :data {:… 3}}
                   {:type java.lang.IllegalStateException, :message "connection released"}]}
     ```
   - Design, 600 tokens: all three messages and all ex-data. `:trace` is absent because there are no
     first-party frames.
4. **A test report** (`#:seon.test{:run-id … :failures [2 maps] …}`, 370 characters).
   - Today, 40 tokens: `{:seon.test/elapsed-ms 3812.4, :seon.print/elision {…omitted 5…}}`.
   - Design, 60 tokens:
     `#:seon.test{:elapsed-ms 3812.4, :failed 2, :failures [#:seon.test{:… 4} #:seon.test{:… 5}], :passed 41, :… 2}`
     plus the legend.
   - Design, 200 tokens: the whole report laid out, with no legend.

### 3j. Unproven parts

1. Shell fairness has no shipped precedent (research §4.2). The regressions above are its only proof.
2. fipp nesting depth. `serialize` recurses through lazy `mapcat` once per document level, and a 5,000-deep
   selected chain may overflow the stack in layout. The hostile member measures it; if it overflows, the depth
   limit belongs to the document builder's iteration, never to a clip.
3. The nest/flow layout's whitespace overhead is not yet measured; only bracket-align's 2.4× is.
4. That an agent's top-level lazy result is realized by its evaluation's admission before `shown-result`
   renders it. If that is not the current order, agents will see `(…?)` for `(map inc xs)` until the evaluation
   owner realizes the value under its own bound. This needs a probe before integration.
5. The per-evaluation artifact blob (§3f) is a new durable write whose storage cost is unmeasured, and it needs a
   root/owner ruling.
6. The 16-character chunk, the 64-character order window and C = 256 are constants chosen here without
   measurement.

## 4. Re-adjudication of all 21 audit sites

Numbers/anchors refer to the linked inventory; **D** marks one of its original nine DELETE sites. Proposed renderer names below live in the existing namespace and get the existing owning response/error schema pair.

| # | Revised verdict | Reason and replacement |
|---|---|---|
| 1 | KEEP / simplify | `value-node*`/`prepare` is the floor; replace depth-first spending with §3 allocation, not another clipper. |
| 2 | CONVERT inside floor | Retain one B; replace `print/fit` search and second traversal with §3. Delete fit-only helpers after moving whole-key/elision semantics. |
| 3 D | KEEP carrier | `agent-render-profile` supplies the floor's B. Delete reads/spending elsewhere, not the key itself. |
| 4 | CONVERT inside floor | `bounded-text`/Throwable `floor` become wrappers over §3; remove caller-limit arity and independent final `subs`. Drop duplicate raw panic message printing. |
| 5 D | DELETE presentation min | Walk `min width query-width` is a second presentation cut. Keep declared pull/query-width acquisition, with incomplete-query evidence. |
| 6 | KEEP selection | Whole prompt units, not portions of values; see §5. |
| 7 D | DELETE comparison | Assertion `message-text` always delegates to the one path; keep complete stored failure message. |
| 8 | KEEP delegation | `report-value` routes to the one path; remove the caller's Throwable special branch. |
| 9 D | CONVERT | Delete `bounded-face`, `bounded-summary`, top-10. `seon.cluster/render-runtime-observation` shows health/counts and fault identity/operation/class/last-at; preserves complete observation as input. |
| 10 | CONVERT | `seon.cluster/render-runtime-observation` also supplies terminal status; operator emits its text. Counts and fault identities replace message bodies; no status-top/short-message. |
| 11 | CONVERT | `problems/ai-prose` returns its family-lines map; no nested `render-ai`, fresh B or `config/defaults` fallback. Its data uses the current floor share. |
| 12 | CONVERT | `seon.render.transcript/render-history` shows an explicitly queried recent turn page, total and older-page link; reuse saved evaluation units. Page N is a constant of this renderer/query, never a configuration dial. Delete presentation max-children/max-nodes; keep honest query bounds. |
| 13 D | DELETE window; KEEP retrieval | `mcp-get-value` passes drilled path/offset to `prepare`; its one floor allocation chooses the page and next offset. Delete `window`, separate page-size cut and `pr-str` sort; preserve total/steps/beyond-end/missing-artifact evidence. |
| 14 D | CONVERT | `seon.render.web/render-connections` shows count and the requested page with existing pager. Delete post-query `take width`; explicit page acquisition remains, never HTML substring clipping. |
| 15 D | DELETE dead code | Namespace omission/included-count always receives the full count; remove dead parameter and elision. |
| 16 | CONVERT | `seon.render.ns/render-referenced-schemas` shows complete referenced schema names/count and definition queries, not first 40 full definitions. Names go through the one path. |
| 17 | CONVERT | `seon.bootstrap/render-opening` renders selected subjects as identities and small read forms; no spent-token candidate loop. Subject membership belongs to its query. |
| 18 | CONVERT | `seon.test/render-world-refusal` shows operation/expected/path plus the native offending value via one path. Store no 400-character replacement in ex-data. |
| 19 | CONVERT | `seon.test.runner/render-drift` shows drift/reach counts and whole native name sets via one path; delete take-10 and “first 10” message assembly. |
| 20 D | CONVERT / DELETE | `seon.cluster/render-branch-errors` shows counts and error identities plus branch/basis; bridge emits it. Keep an explicit since/cursor query window, remove hidden max-20 display loss; delete form-preview echo, caller already has the form. |
| 21 D | DELETE local clipping | Docstring/markdown/changed-test/citations script report truncations: print complete reports or send report data to the same floor. An explicitly requested ranked query is still lawful. |

The audit's six domain conversions are 10,12,16,17,18,19, each named above; 9 and 10 deliberately share one renderer. Admission byte limits, blob thresholds, agent-requested file/search windows, deadlines and legitimate ranking queries
stay. They must expose missing/partial acquisition and cannot be used as covert display limits. This design does not
delete admission or transcript subsystems wholesale; the census's larger deletion estimates require separate caller proof.

## 5. Prompt units

**Keep whole-unit selection**, following root acceptance of the review; this is provider admission, not value clipping.
`:seon.config.ai/prompt-token-budget` belongs to provider-context admission, outside the value render system; it may
select whole historical evaluations, never fit their text. “Apply presentation limits once” explicitly permits
“select whole prompt units”; “Recover facts, not execution” preserves exact shown bytes. Record which units were omitted.
Count the complete prompt (frame, separators, retrieval notice and required opening included) against model capacity.
A newest unit that cannot fit must produce an explicit capacity refusal or request the existing compaction operation,
not silently overrun capacity as `retained-count` currently allows (`prompt.clj:232`). Compaction clears evaluations
and regenerates opening under “Derive wakes and completion”; it is a distinct state transition, not a printing fallback.

## 6. Ordered implementation slices and proof

Independent review `8a9e946d7` is complete and root accepts all findings; **owner go given 2026-09-24 for all slices**. Owners below are responsibilities, not file assignments; root checks the ledger. Every slice is ≤100 added physical
lines including declarations/contracts/regressions. Preparatory accretion leaves current dispatch active; the switch
is atomic with every named caller conversion. If that cut exceeds 100 additions, return a smaller reviewed cut before
writing, not a partial switch. Shared `value.clj` slices run sequentially; there is no 89-renderer API migration.

| Slice / owner | Change, exit criterion and installed probe | Est. + / − |
|---|---|---|
| 0a schema | Declare missing identity owners with `:seon.program/row-schema`; prepare owned-key sets without changing dispatch. Probe agent-id envelope, two identities agreeing/conflicting, partial entity and non-program row classification. | 55 / 0 |
| 0b schema/render | Add cached error-operation and contracted head-Var alternative lookup without switching dispatch. Probe nested specific error, uncontracted base, ambiguous/no-match alternatives, direct report and `let` floor. | 60 / 0 |
| 1 render/SCI/MCP + explicit owners | Activate all three lookups; carry head Var into shown-result/MCP. In this same cut convert `oversight/unit`, `seon.run/render-namespace-ai`, four fixtures and walk/bootstrap/web owners. Only then remove old stages/specificity matching. Probe every converted entrance and no paired-error/report loss. | 75 / 260 |
| 2 floor/print/MCP/web (folds old 2, 3, 5; owner 2026-09-25) | §3 in three loadable commits. **(i) Selection:** `classify`, shell-order loop, Throwable projection, coordinate rule, compact cuts + one legend on the existing emitter; `get_value` = `prepare` at path/offset (convert `cluster.clj:575–594`). **(ii) Layout:** `node->doc` over `fipp.engine`, `node->hiccup`; delete the Sink family. **(iii) Page:** cut expanders on `/data`, side-by-side outline unit, evaluation artifact digest (root ruling first). **Delete** — `print.cljc`: `preserve-requery`, `fit-entry`, `fit-children`, `projected-text`, `fit-text`, `structural-elision`, `fit-node`, `fit` (`:1054–1299`); `requery-fields`, `elision-node` (`:974–980`, `:1013–1052`); `emit-node` re-sort (`:856–871`); `structural-cut?`, `visible-items` and the `::length`/`::level` `...` cuts; `Sink`, `TextSink`, `HiccupSink`, `TeeSink`, `append-chunk!`, `soft-separator`, `append-hiccup!`, `sink-result`, `text-sink`, `hiccup-sink`, `tee-sink`, `sink?`, `sink-generator` and the `emit` multimethod family (`:18–30`, `:192–338`, `:559–790`); table emission (`:496–557`); `render-elision-ai`'s EDN-map face; the `seon.ai.tokens` require. `value.clj`: `text-limits`, `text-full`, `datum`, `causes`, `link-lines` and the bodies of `bounded-text`/`floor` (`:38–131`, entries kept for boot/flow/store/test callers); `stable-entries`, `counted-size`, `window`, `display-value` (`:297–366`); `value-node*`, `value-node` (`:446–585`); `pager` (`:597–609`); `attribute-value`'s `pr-str` tie-break. `cluster.clj`: the string window branch. `transcript.clj`: the outline toggle. **Convert:** `eval/drive.clj:135` (reads emitted text back with default `::length 32`, a hidden cut), `instrument_test.clj:1418` (uses `text-sink`/`sink?` as its reload subject), `cluster/prompt.clj:280` and the four `print/fit` tests. **Probes:** §3g ordinary 1–14 by root via `bin/test-check`; hostile list as fixture-observation members only; §3h clock rows on parent and change; the lazy-result realization order (§3j.4) before (i) integrates. **Unproven:** §3j. **Estimate vs research +100/−480:** research priced the allocator alone. This row also moves Throwables, the emitter and the page: source **+310 / −1,150** (planning range +260–400 / −950–1,200), net about −840; plus about 10 schema lines and about +150 test lines. `print.cljc` 1,307 → ~635 lines, `value.clj` 741 → ~560. Over the 100-line law: needs the owner's waiver extended from old slice 2 to this fold; the smallest alternative is commit (i) alone, about +190 / −560. | 310 / 1,150 |
| 4 render/problems | Retire `project-node*` redispatch; accept smaller data/final text using merged units without profile. Convert `ai-prose` to data. Probe nested problems uses one share, text-overage diagnostic, no defaults fallback and no repeated producer. | 30 / 140 |
| 6 cluster/operator | Shared runtime/branch-error renderers (9,10,20). Probe >20 faults: complete counts, identities/requery, same terminal/MCP face. | 40 / 75 |
| 7 transcript/walk/web | Render declared history/connection query pages; remove walk presentation min (5,12,14). Probe membership, count/link and exact historical AI/HTML bytes. | 30 / 70 |
| 8 namespace/bootstrap | Referenced names/opening subjects; remove dead omission (15–17). Probe >40 names and small forms; no display token spending. | 35 / 90 |
| 9 test-report | Native refusal/drift data and one message path (7,8,18,19). Probe complete offender/drift retrieval and no caller Throwable branch. | 25 / 65 |
| 10 scripts/config | Remove dev clipping and max-depth/max-children/max-string-length from profile schema/config and all readers; keep only floor B and real query/storage bounds (3,21). Audit all 21 sites. | 20 / 80 |
| 11 prompt | Own §5 capacity behavior: include frame/separators/notice, retain whole units, diagnose an oversized newest unit or explicitly invoke existing compaction. Probe exact saved bytes and no silent overrun. | 20 / 35 |

Repriced estimate: **600 added, 1,865 deleted, net ~1,265 lines deleted** (the folded row 2 replaces old rows 2, 3 and 5 at 250 / 550), including schema/contract/regression edits; not measured implementation. Compared with 670/1,150, this removes migration/frontier/path/leaf-face machinery and
prices identity declarations, emitter charging, offset consolidation and prompt admission explicitly. Slice 1's atomic
caller sweep is the main sizing uncertainty; no claim that 89 producers change within 75 lines.
Must-pass retained regressions: `value-test/background-poll-keeps-identity-while-payloads-grow`, `fit-preserves-breadth-and-long-strings`, carried string/children totals and `preserve-requery`. Add producer overage,
envelope ownership and nested operation-error cases; no green inferred from code or the review's hand calculations.
Root proves publication/load/arming/adoption, runs focused real-SCI probes, then affected/platform/bulk proof once
and browser paint. Record elapsed/allocation, visited nodes, order/emit counts and floor hits on parent/change:
ordinary <1 s, one final emission/comparison, no >20% or 50-ms regression; name proportional large-input cost.
This revision changes zero source/test lines and runs no JVM/tests or new live probes. Historical evidence above is retained; current health, memory and implementation performance are unverified. The foreign http-kit submodule remains untouched.
