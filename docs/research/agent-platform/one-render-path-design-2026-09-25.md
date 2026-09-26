---
type: design
status: "owner adopted shell-order floor 2026-09-25; §3 and §6 row 2 revised after Astra 6e9deea71 and re-review 98c563b34 (A–D folded, real-queue proof 2026-09-26); awaiting root on the row 2(i) file list"
created: 2026-09-25
scope: One schema dispatch and one shape-aware, budget-owning floor printer
---
# One render path

§3c, §3d, §3g, §3i–§3k and §6 row 2 were revised in place on 2026-09-26 to fold in the re-review
`98c563b34`'s corrections A–D. That revision is a real-queue REPL proof with an exact charge on the laid-out
text, and continuation as a function over the walk's nodes instead of a `units` entrance (root ruling).

Changes after [Opus review 8a9e946d7](one-render-path-review-2026-09-25.md), all findings accepted by root:
Declared identity ownership and producer error/return alternatives replace structural discovery without losing pairs.
The floor uses one character budget spent in shell order (owner, 2026-09-25), fipp layout for text and an explorable page; renderers keep merged units.
Problems returns data; prepare owns offset retrieval; overlong renderer text yields a named diagnostic.
The revised slices land replacements before dispatch removal, convert explicit owners atomically, and price prompt admission.
§3 and §6 row 2 were revised in place after [Astra review 6e9deea71](floor-shell-order-review-2026-09-25.md): one held-source entrance, a permitted-operation table, entry skeletons with absolute ordinals, B charged on the saved text, fipp's stages over an explicit-stack operation stream, and live-only continuations.

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

**Per face, the first match wins (owner, 2026-09-25; AGENTS.md a54b689ff, f3a14238e, 7fc304482).** (a) the value's own
`:seon.render/ai`/`:seon.render/html`, the master override: a renderer symbol, an inline function, or rendered data
(which reaches its face's emitter as data, never a pre-printed string); (b) a render function in the VIEWING agent's
namespace whose contract fits the value and returns the face, the agent's own default from its point of view (sorted
candidates, more than one refused); (c) the schema's declared pair, the shipped default; (d) the floor. Face keys are
render data, not entity attributes. A render function's output may be any properly declared shape and renders in turn.
**Inputs are the contract's, supplied like any call.** A render function declares what it wants in its Malli `:=>`
input, as map keys or positional `:cat` schemas. The render boundary passes its arguments; inputs the contract declares
and the caller omits (the database, the unit) are supplied as defaults by declared schema name through the one call
preparation mechanism (`seon.call-preparation`, the kernel invocation hook), and caller arguments always win. An inline
function with no schema gets just the value.
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

Keep the existing **one merged unit argument** (`producer-argument`), including value, original caller path, target `:seon.render/ai` or `:seon.render/html` and retrieval provenance. **The database is not a caller argument of the unit** (owner, 2026-09-25): `:seon.render/unit` declares `:seon.db/db` as an optional entry, and `seon.call-preparation` supplies it when a render function declares it (map entry, or a positional `:seon.db/database-value`), scoped to the request's own database; a caller's value wins. An inline function gets the same defaults once call preparation can plan a closure from its contract.
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

## 3. One floor: held source → safe structural selection → one tree → two faces → continuation over the walk's nodes

**What exists and what it becomes.** Today MCP admits a raw value (`admit/admit-value`, which runs
`Throwable->map` and Java collection admission, `sci/admit.clj:297`), turns it into an artifact, prepares the
artifact's reconstructed semantic value, rebuilds semantic data from the selected node and serializes that again
(`cluster.clj:388–494`). `get_value` reads the blob and applies its own string and collection windows
(`cluster.clj:555–597`, `render.value/window` `value.clj:311`). `/data` separately reads, reconstructs and renders
(`web.clj:3516`). The floor itself walks depth first under a node counter (`value-node*`, `value.clj:446`), then
`print/fit` re-emits and halves limits until the text fits (`print.cljc:1234`). `bounded-text`/`floor`
(`value.clj:65`, `:119`) are two more cut engines, and `repl/pretty-response` reads prepared text back and
reprints it (`repl.clj:457`).

The design is one data flow over the nodes of the graph walk (`seon.render.walk/graph`, one composer; the
building block's `seon.render/units` entrance is withdrawn, root ruling 2026-09-26):

1. **Held source.** A walk node: an entity at a database value, an evaluation's live result by its handle, or an
   MCP host value's stored selection tree by digest, resolved under explicit custody. Nothing touches the value
   before step 2.
2. **Safe structural selection** (`render.value/prepare`). A table of permitted operations (§3a) decides what may
   be done to each reached object. A shell-order queue (§3c) spends one character budget B, which is the saved
   AI text's length, charged exactly (§3d). It takes **no face target**.
3. **One tree.** Plain print nodes: retained literal bytes, entries with key and value roles, cuts with absolute
   offsets and the source reference. No unknown object reaches a face.
4. **Two faces.** AI: the tree as fipp's own operation stream, laid out by fipp's own stages (§3e); this text is
   clipped and is the saved text. HTML: the same tree as Hiccup, unclipped, each cut a lazy expander (§3f).
5. **Continuation over the walk's nodes.** `get_value` and `/data` decode coordinates and call
   `seon.render.walk/continue` with the cut's node designator, typed path and absolute offset (§3g). Neither
   transport navigates, windows or selects.

The principal Clojure approach: a `loop` over a `java.util.PriorityQueue` of plain maps, a data table of exact
classes and their permitted operations, and pure functions from nodes to fipp operations and to Hiccup. There is
no protocol, no dynamic Var, no retry and no second navigator. What disappears is listed in §6 row 2.

Owner requirements served here: one pipeline for AI through MCP and humans through the browser; a pretty-printed,
clipped AI face that is the saved text; an explorable, unclipped HTML face whose cuts load lazily; a side-by-side
debug view; no lazy realization and no user code; snippets of every branch under one budget; sub-second. The
[Astra review](floor-shell-order-review-2026-09-25.md) (`6e9deea71`) P1-1 to P1-10 are answered in place:
P1-1/P1-2 in this preamble and §3g; P1-3/P1-4 in §3a; P1-5/P1-6 in §3b–§3c; P1-7 in §3e; P1-8 in §3d;
P1-9/P1-10 in §3g. The three earlier Astra rounds
([slice-2 review](render-floor-slice2-review-2026-09-25.md)) stay requirements; §3h replays their traces.

**REPL evidence (2026-09-25, default PID 35446, JVM mode, read-only, scratch namespace `tmp.floor-revise`).**
Values: the live root agent row (`(seon.db/pull db '[*] [:seon.agent/id "root"])`, 7 keys, 155,340 flat
characters); the schema projection's forms map (3,435 keys, 626,476 characters); a stored error row (23 keys,
2,976); a real test run pulled with its members (16 keys, 4,072); and a three-link Throwable chain whose middle
`ex-data` is the real `seon.db/q` refusal value (2,970 characters as `Throwable->map`). The prototype is
`tmp/floor-revise/probe.clj`, disposable: complete-shell selection over the §3a table, the §3d charge and the §3e
layout. It is **not** the queue, so the partial shell is not measured. All timings are single warm runs.

### 3a. Classification is a table of permitted operations

`classify` answers first for every reached object: a value, a map key, a set member, a seq cell, a held
component, a carried coordinate. Nothing else, including ordering, pricing, hashing, lookup, path construction,
printing or admission, receives an object `classify` has not answered for. The answer is **a row of the table
below, matched by `(identical? (class x) C)`**, never `instance?` and never a package name. A row lists the only
operations the floor may perform and the held objects those operations return, which are classified in turn.
Every class not in the table is **opaque**: its face is `#object[<Class/getName> 0x<System/identityHashCode>]`,
and nothing else is called on it. Approval of an outer class never approves its contents.

| Exact class | Permitted operations (source) | Held objects, classified before use |
|---|---|---|
| `nil`, `Boolean`, `Long`, `Integer`, `Short`, `Byte` | static `toString`; bound 20 | — |
| `Double`, `Float` | `##NaN`/`##Inf` table, else `Double/toString`; bound 24 | — |
| `Character` | `char-name-string`/`char-escape-string` lookup, else `\c` | — |
| `String` | `.length`, `subs` of one chunk, escaped by the same char table | — |
| `Keyword` | `.sym` (field), then as Symbol | its `Symbol` (exact final class) |
| `Symbol` | `.getNamespace`, `.getName` (final fields). **Metadata is never read** | — |
| `java.util.UUID` | `toString`; 36 | — |
| `java.util.Date` | `.toInstant`, `Instant/toString` (final); ≤ 40 | — |
| `clojure.lang.BigInt` | public fields `lpart`, `bipart` (`BigInt.java:23–24`) | `bipart` must be exactly `BigInteger` |
| `BigInteger` | `.bitLength`; `toString` only after the bound fits | — |
| `clojure.lang.Ratio` | public fields `numerator`, `denominator` (`Ratio.java:23–24`) | both exactly `BigInteger` |
| `BigDecimal` | `.unscaledValue().bitLength`, `.scale`; `toString` only after the bound fits | unscaled value exactly `BigInteger` |
| `TaggedLiteral` | public fields `tag`, `form` (artifact faces, §3g) | `tag` Symbol, `form` |
| `PersistentArrayMap`, `PersistentHashMap` | `count` (field: `PersistentArrayMap.java:212`, `PersistentHashMap.java:263`); `.iterator` | each entry's key and value |
| `PersistentTreeMap` | `count` (`PersistentTreeMap.java:309`); `.iterator`. **Never** a lookup: lookup calls its comparator (`:313–330`) | key, value |
| `PersistentVector`, `MapEntry` | `count` (`PersistentVector.java:209`), `nth` | members |
| `APersistentVector$SubVector` | public fields `v`, `start`, `end` (`APersistentVector.java:610–613`) | `v` must be exactly `PersistentVector` |
| `PersistentHashSet`, `PersistentTreeSet` | `count` via package-constructed `impl` (`APersistentSet.java:44`; constructors package-private, `PersistentHashSet.java:87`, `PersistentTreeSet.java:40`); `.iterator` | members |
| `PersistentList`, `PersistentList$EmptyList` | `count` (`PersistentList.java:115`), `first`, `next` (fields) | members, tail |
| `Cons` | `.first`, `.more` (fields, `Cons.java:36–47`). **Not** `count`, which walks the tail (`:50`) | first, tail |
| `LazySeq` | `.isRealized` (`lock == null`, `LazySeq.java:287`); when realized, `.seq` returns the field `s` (`:102–105`) | the cached seq. Unrealized: the pending marker |
| `clojure.lang.Var`, `sci.lang.Var` | JVM: `.ns`/`.sym` (final fields). SCI: final fields `sym` and `ns` (`sci/lang.cljc` `Var`); a `sci.lang.Namespace`'s final `name`. **Metadata is never read**; both print `#'ns/name` | `sym` exactly `Symbol`, `ns` exactly `sci.lang.Namespace` |
| `PersistentVector$ChunkedSeq` | `count` (`PersistentVector.java:512`), `first`, `next` over its own vector | members |
| `Throwable` subclass *C* | Only when `getMessage`, `getCause`, `getStackTrace` and `getSuppressed` all have declaring class `java.lang.Throwable` (`Class/getMethod` + `getDeclaringClass`, memoized per Class). `getData` only for exactly `ExceptionInfo` (`ExceptionInfo.java:34`) | message, data, cause, frames |

Consequences, each a closed trace from the review:

- **Seq cells** (root rulings 2026-09-26, superseding P1-3's closed class list and "never realize"). Every
  `clojure.lang.ISeq` and every other `Sequential` prints as a list, as Clojure's printer prints it (an
  `IPersistentVector` as a vector); the class is the interface, not a case list. `seq-cells` steps `first`/`next`
  up to the node's member limit (offset plus max-children; admission's max-collection for the complete HTML face),
  realizing a lazy seq as far as it prints and no further, as Clojure's printer does under `*print-length*`:
  `(map inc [1 2])` is `(2 3)`; `(range)`, `(iterate f x)` and `(cycle xs)` show their first members then `…?`. A seq
  longer than the limit shows its total when it is `Counted`, else the pending tail.
- **Row 9.5** (superseded by root 2026-09-26, dogfood cycle 1): the printer realizes a lazy result as far as it
  prints, bounded by the member limit; the pending tail `…?` marks only the members past that bound of a seq that is
  not `Counted`.
- **Scalars never meet `print-method`** (P1-4). A Symbol with `{:type :user/hostile}` metadata is encoded from its
  final fields; `print-method`'s `:type` dispatch (`core.clj:3693–3695`) is never reached. Numbers whose text is
  unbounded are **bounded first**: ⌈bitLength · log₁₀2⌉ + 1 digits for `BigInteger`, the sum for `Ratio`, and
  unscaled digits plus the scale's digits for `BigDecimal`. Decimal text is made only when that bound fits the
  room. Otherwise the node is a charged descriptor: `#bigint[≈301030 digits]` or `#ratio[≈2000001 digits]`.
  Keywords, symbols and strings are names: an oversized one shows a chunk prefix like a string.
- **Host collections.** A class outside the table that implements `Counted`, `IPersistentMap`, `java.util.Map` or
  `Collection` is opaque. `count`, `seq`, `iterator` and `get` are never called on it.
- **Records** are opaque unless a declared renderer claims them (§1). The review showed that `IRecord` plus a
  default `print-method` does not prove generated `seq`/`count` code. The Datahike DB and connection are opaque
  as well.
- **Throwables** (review "Throwable and lazy-result qualifications"). The review's "never run user code" wins
  over the old per-accessor `try`. A class that overrides any accessor, including `CompilerException`'s
  formatting (`Compiler.java:7403`, `:7474–7480`), is opaque with its class name. Links are **projected lazily**:
  `:via` is a seq of links that selection steps one `getCause` at a time, with an **identity** set
  (`System/identityHashCode` plus `identical?`) bounded by the visited links (≤ B). `getStackTrace` is not
  called (its allocation bound is unproven). `Throwable->map` is never called
  (`core_print.clj:494–501`). Stored flat `:seon.error/*` maps are plain data and are preferred wherever they
  exist. The projection is `#error {:via [{:type C, :message M, :data D} …]}` in that declared order; frames
  are not acquired, because `getStackTrace`'s allocation bound is unproven (re-review B).
- **Map lookup.** The floor never calls `get`, `find` or `contains?` on a map it did not prove all-scalar
  (§3b). `Util/equiv` sends a probe key into a stored key's `equiv` when either is a persistent collection
  (`Util.java:24–36`), so a lookup by keyword into a map holding a user collection key can run user code.
- **Carried coordinates** (P1-9 and review trace "node-id"). A cursor path, root address, identity value, source
  reference and agent id are **typed data validated before use** (§3g). They are never hashed, printed or looked
  up raw. `requery` forms are derived from a validated source reference at emission, never carried from input.
  Node identity is the occurrence id plus the cut's ordinal (§3f); no path is digested per node.

### 3b. Structure: entries with roles, held order, absolute ordinals

- **A map is a sequence of entries** in one order. Each entry is a node with two child roles, `:key` and `:val`.
  A composite key is a value like any other: it is shown through the same selection, so no entry is ever skipped
  (P1-5, the `(array-map :a 1 [2] :hidden :z 3)` counterexample).
- **Order is the held order,** except for one bounded case. An array map keeps the producer's order (pulls and
  `array-map` literals). A tree map keeps its comparator's order, obtained by iteration only. A hash map with at
  most 64 entries whose keys are all exactly `Keyword` is sorted: declared identity attributes first
  (`db/identity-attributes`, compared with `identical?`), then by namespace and name through a 64-character
  window. Every other hash map keeps its iteration order. That check reads at most 64 keys, and the sort costs
  O(64 log 64). There is **no global sort** and no `ordered-head` scan (P1-6). A hash map's iteration order is
  arbitrary but stable for the held value, so it is a valid coordinate. Readability costs are real: a 3,435-key
  map shows keys in hash order.
- **Path steps are typed:** `[:seon.render.data/entry i]` then `:seon.render.data/key` or `:seon.render.data/val`;
  `[:seon.render.data/nth i]` for vectors, lists, seqs and sets (held iteration order); `[:seon.render.data/char i]`
  for strings. A map proved all-scalar (the ≤ 64 sorted case) may use `[:seon.render.data/key k]` with k
  its shown key: lookup is then safe, because every stored key is an exact `Keyword`. Steps are tagged vectors, not
  shape guesses, so they cannot collide with a literal map key. There is exactly one navigator, `render.data/at`,
  rewritten over these steps. It keeps its present-versus-nil result (`data.clj:46`) and drops raw
  `contains?`/`get`/`nth` on unclassified values (`:54–68`). `print/value-at` (`print.cljc:403`) is deleted.
- **Offsets are absolute source ordinals.** Selection never skips a member (oversized members become descriptors,
  §3c), so the shown members of every collection are a prefix. **One tail cut per collection is exact:**
  `next-offset` = the ordinal of the first unshown member, `omitted` = total − next-offset, and the total is typed
  unknown for pending seqs.
- **Navigation cost.** A vector step is O(1) through `nth`. A map, set or seq step at ordinal i iterates i
  members, through the table and without realization. That is **query work,** charged to the request's work bound
  and deadline, not to B. A continuation at offset i in a 10⁶-key map costs O(i + shown), never a full scan.
- **Paths are built incrementally:** a node's path is its parent's path `conj` one step, O(1) with structural
  sharing. Only cuts and HTML expanders print a path, O(depth) each (§3f).

### 3c. Selection: skeletons first, then shell order

**Admission unit.** A member is first admitted as its **skeleton**, a bounded face that is always followed by
a value face:

- a scalar: its literal when its spelling is ≤ S = 32 characters, else its descriptor;
- a string, or a keyword or symbol longer than S: its first chunk of w = 16 characters plus the cut `…+N`;
- a collection: `open …N close` (for example `{…7}` or `[…100000]`);
- a map entry: key skeleton, a space, value skeleton. Key and value are each refined later in their own right;
- an opaque object: its `#object[…]` face.

Every skeleton is at most S plus a small cut face. So **an unaffordable member closes its collection only when
the room left is under one bounded skeleton.** A huge first key does not starve a later `:ok`: the key is a
chunked name at its position.

**Order.** The root has depth 0. A member has depth d and ordinal o, both 1-based. Its **shell** is
`s = max(d, o₁, …, o_d)` over the ordinals along its path. A string's k-th chunk is at shell max(s, k); a
collection's member o is at shell max(s, d+1, o). The queue (`java.util.PriorityQueue`) pops by `[s d o seq]`,
where `seq` is the insertion counter. It holds at most one pending member per open collection and one pending
chunk per open string: a k-way merge. The measured maximum queue size is 41 on the 3,435-key projection forms at
45,000 characters.

**Loop.** Pop the least entry and admit it tentatively. Charge it with the §3d charge. If the charge still fits
B, keep it, and push the parent's next member and the new node's first refinement. If it does not fit, the
state before the entry is kept, which closes the parent at that ordinal (one tail cut). Then continue with the
other branches. State is a value, `{nodes extras}`, and a refusal simply keeps the previous value. A declared
renderer for a reached value runs when its node is reached (§2, provenance, §3d). A declared renderer is always
consulted; only the order of admission is chosen here.

**What is guaranteed, conditionally.** Shells are popped in nondecreasing order. If the whole of complete shell
k fits B at its exact charge, the output contains all of it. The charge includes every skeleton, separator and
line break that fipp will emit, and the legend. After that, shell k+1 is spent in queue order until B runs out.
Nothing stronger is claimed. A deep singleton chain costs one skeleton per level. No finite B shows every part
of an arbitrary value; cuts say what was omitted and where.

**Root and minimum.** If the root's skeleton plus the legend exceeds B, selection returns the declared
`:seon.print/insufficient-floor-budget-error` (over `:seon.error/base`, naming B and the needed charge). A
continuation that cannot admit even its first member returns that same refusal instead of an unchanged cut, so
every successful expansion shows at least one new member (strict progress).

### 3d. B is the length of the saved AI text, charged exactly

B is converted once at entry: `(tokens/estimate-chars token-budget)`. Units are string characters
(`String/length`, fipp's widths), not UTF-8 bytes. The charge is **the laid-out length itself**, maintained
incrementally (review C). It is the sum of two terms.

- **The flat width of every admitted node.** That is its literal or chunk text, quotes, delimiters, the `, ` or
  ` ` separators, and its cut face (whose digits shrink as members arrive; the change is re-derived).
- **The added width of every group fipp will break.** fipp breaks a group iff it does not fit from its start
  column (`format-nodes`' fits test, `engine.cljc:203–208`). The selection derives that same decision: a group
  breaks iff its parent broke and its flat width exceeds `width − start column`. A member of a broken
  collection starts at its indentation. A map entry's value on a broken entry starts at the entry's indentation
  + 2. A broken collection adds its child indentation per line break. A broken entry adds its indentation + 2.
  Nesting stops at depth 6, so indentation is ≤ 18 columns.
- **The legend**, reserved at entry and refunded when there is no cut.

Admitting a member re-derives flat widths along its chain to the root: each parent's child sum is adjusted by
the child's delta, O(depth). Broken flags are then re-derived top-down along that chain, and a node that newly
breaks cascades into its children, each node breaking at most once, O(n) overall. The emitter's own choices
only shorten the text: fill for all-scalar collections, and `#:ns{}` lifting when every key shares one
namespace. So the charge is an upper bound on the emitted length. After layout, `(<= (count text) charge B)` is
asserted once. A violation is the declared `:seon.print/allocator-charge-error` (path, B, charge, length): a
defect, never a second attempt.

**Measured, REPL proof, real queue** (2026-09-26, default PID 87270, `tmp/impl-floor-on-fipp/queue_probe.txt`,
unarmed scratch namespace). Values: the root agent row, the schema projection's forms map (3,435 keys), a stored
error row, a stored test run, a three-link Throwable chain, and the owner's three-branch probe. Text was ≤ charge
≤ B in **all 28 cases at B = 384 / 450 / 1,800 / 45,000**. With a cut, the charge exceeds the text by 4
characters, the difference between the reserved legend and the real one; with no cut, the legend is refunded.
The design's first per-member reservation over-charged by 4–40% and **failed the owner's 128-token probe**:
only `:sample/a` reached its text. The exact charge passes it, with every branch showing a 32–48-character
prefix at 384 characters.

**Provenance** (owner 2026-09-26). Text that a declared AI render function produced is not charged to B. It is
laid out whole and bounded only by `:seon.print/rendered-token-ceiling`, a config dial, 50,000 tokens. Beyond
that it becomes a character cut with its requery. The saved text is therefore ≤ B plus the declared renderer
text it includes. Raw values keep B.

**Legend.** It appears once, after the value and only when a cut exists, and it is charged:
`;; …N = N more · continue: (seon.render.walk/continue <node> {:path P :offset N})`, where `<node>` is the cut
subject's node designator (§3g). For example, `result/e12` for a live result, `[:seon.agent/id "root"]` for an
entity, or a blob digest for an MCP host value.

### 3e. The AI face: fipp's own stages over an explicit-stack operation stream

`tree->ops` walks the tree with an explicit stack and emits fipp's own operation maps: `{:op :text}`,
`{:op :line :inline " " :terminate ""}`, `:begin`, `:end`, `{:op :nest :offset n}` and `:outdent`
(`reference-code/fipp/src/fipp/engine.cljc:12–65`). Those maps go straight into
`(eduction fe/annotate-rights (fe/annotate-begins opts) (fe/format-nodes opts) ops)`, appended to one
`StringBuilder` (`engine.cljc:68–223`). There is no `serialize`, no `pprint-document` (which writes to `*out*` and
appends a newline, `:247–259`), no EDN visitor (`ednize.clj:45–61`) and no new layout algorithm (P1-7).

**Measured (P1-7):**

- The op-stream layout of the depth-7 agent selection is byte-identical to `pprint-document` of the equivalent
  document.
- `fipp.engine/serialize` overflows the stack at depth 1,000. That happens both for a recursive `node->doc`
  and for a document built iteratively, because `serialize` recurses through `mapcat` (`engine.cljc:12–19`).
- The explicit-stack op stream lays out depth 1,000 / 5,000 / 20,000 in 13 / 61 / 156 ms with no overflow.
- Indentation must be capped at every nest, entry nests included. Uncapped entry nesting at depth 100 gave
  11,187 characters from 501 flat (quadratic). Capped at depth 6 it gave 1,969. At depth 20,000 the text is
  459,668 characters, about 23 per level: linear.
- **Memory:** tree O(V), op vector O(V), `format-nodes` tab-stop stack O(depth) (`engine.cljc:171`, `:210–212`),
  output O(text) ≤ B. This is not O(width); the earlier claim is withdrawn.

**Readability rules** (the owner's pretty-printed, clipped face):

- Width 100, a soft width: an indivisible literal longer than the line stays whole.
- A collection is begin, open, nest 1, members, outdent, close, end. A map entry is begin, key, nest 2, line,
  value, outdent, end: a value that does not fit flows to the next line two columns under its key. Nesting stops
  at depth 6.
- Members of an all-scalar collection are each wrapped in their own group (`, [begin line end]`), which is
  Wadler's `fill`. Other members are separated by `, line`, one per line when broken. There is no column
  alignment.
- Namespaced maps: `#:ns{…}` only in the ≤ 64 all-keyword case when every key shares one namespace and there are
  at least 2 keys, decided when the map opens and charged exactly.
- **One cut face everywhere:** `…N` after the shown members of a vector, set, list or map (`{:a 1, …3}`),
  `"shown chars"…+N` after a string (outside the quotes, so a real string ending in `…+N` cannot read as a cut),
  `…?` for a pending tail. The AI text is display, not EDN to read back; `repl/pretty-response`'s read-back is
  deleted (review P1-1). The earlier `:…` map key could collide with a real key (review P2) and is withdrawn.
- Errors read `#error {:via [{:type …, :message …, :data …} …]}`. The generic floor acquires no frames (§3g);
  a stored `:seon.error/*` row's pair shows them.

### 3f. The HTML face and the side-by-side debug view

`node->hiccup` renders **the same tree**: a `<details>` per collection with summary `{} 7 keys`, strings in
`white-space: pre-wrap`, and each cut as
`[:button {:id <occurrence-id>-c<n> :class "seon-print-cut" :data-on:click "@get('/data?…')"} "…38"]`.
The id is the occurrence id (building block §3a) plus the cut's ordinal in selection order. So two occurrences of
one source never share an id, and no path is hashed.

- **Unclipped means progressively reachable.** All safely available structure is reachable by expansion; nothing
  is substring-clipped. A pending tail's expander is labelled as running code and goes through §3g's realization
  request; an opaque object has no expander.
- **Depth fragments (measured).** `seon.render.hiccup/->string` serializes 5,000 nested elements and overflows by
  10,000. The HTML face therefore closes each fragment at relative depth F = 1,000 with a **deeper** expander, a
  continuation at that path, never a clip. `node->hiccup` uses the explicit stack of §3e.
- **Coordinate cost.** Each expander's URL carries the printed source reference, typed path and offset:
  O(fragment root depth + 1,000) characters per cut. This is page bytes, proportional to what the user explored,
  and it is stated rather than hidden.
- Delivery reuses Datastar replacement (`web.clj:3286`): the response patches exactly the target id.

**Debug view.** The outline unit (`transcript.clj:1574–1612`) toggles today. It becomes one two-column body. The
left column is the **saved AI bytes**, unchanged (`web_debug_test.clj:1007`), never regenerated. The right column
is the HTML of the same source **if it is still retained**, labelled with its source and basis (`live result/e12`,
`artifact <digest>`, `entity at t`). Otherwise it shows the typed "live source gone" result. It is never presented
as the original tree or the original B (review P1-10). `web/debug-value-html`'s raw `pr-str` (`web.clj:795`) and
the toggle are deleted.

### 3g. Continuation is a function over the walk's nodes and the floor's cut paths

There is **no second entrance** (root ruling 2026-09-26): `:seon.render/units` is being deleted, because the
graph walk (`seon.render.walk/graph`, one composer, nodes cached by content digest) replaces it.
A cut's coordinates are **a node designator + typed path + absolute offset**. `seon.render.walk/continue` takes
exactly those and the caller's custody (database value, SCI context). It resolves the node, navigates the path,
and runs the same selection with the root collection starting at the offset. `get_value` and `/data` are
adapters: they validate wire input, call `continue`, and encode its prepared projection. The only failure value
is `:seon.render.walk/continuation-error`, with reason `[:enum :stale :gone :unavailable :malformed]`.

**Node designators.** There are three sources, each a node the walk already knows:

| Designator | Resolution | Basis | After restart |
|---|---|---|---|
| lookup ref `[:seon.agent/id "root"]` or eid | `seon.db/pull` at the caller's database value | that value's commit | current entity, labelled "current" |
| result handle `result/e12` (an evaluation receipt node) | the owning SCI context's `result-objects` (`sci/eval.clj:620`) | the live object | `:gone` |
| blob digest (an MCP host value; host values have no agent node) | the stored **selection tree** (below) | the digest | retrievable |

**Typed steps.** A path is a vector of typed steps: `[:seon.render.data/entry i :key|:val]`,
`[:seon.render.data/nth i]` and `[:seon.render.data/char i]`, plus `[:seon.render.data/key k]` only in a map
proved all-keyword (≤ 64 entries). There is one navigator, `render.data/at`, rewritten over these steps. Each
object it reaches passes through `classify` first. It keeps present-versus-nil (`data.clj:46`), and
`parse-cursor`'s default-to-root and default-to-zero branches are deleted. A map, set or seq step at ordinal i
iterates i members through the table. That is query work, charged to the request's deadline, not to B.

**Wire bound** (review D, narrowed explicitly). The `path` parameter is bounded to 4,096 bytes before
`edn/read-string` and then validated against `:seon.render.data/step`. A 1,000-deep chain needs about 20,000
characters of path, so browser expanders reach every cut up to that bound. Deeper than that, the expander is
the REPL form itself: `(seon.render.walk/continue result/e12 {:path … :offset …})`, evaluated by the agent or
on the debug page's REPL through the one REPL path. Its result is a new live result with its own handle, so
paths re-root and stay short. No URL carries an unbounded path, and no continuation is advertised that the
wire refuses.

**MCP host values keep the representation that was selected** (review D). When the B selection has a cut and a
connection exists, MCP stores **the selection tree** computed at the admission allowance A
(`:seon.config.eval.result/blob-threshold` characters). That is plain data: node kinds, literal texts, ordered
children and cut totals, as canonical EDN. It is never a re-read of the value. Continuing it means selecting
over that tree, whose order and entry roles are preserved in vectors. So original ordinals stay valid and no
map or set is reconstructed. Its own cuts are terminal `:unavailable` ("beyond admission"). The reader is plain
`edn/read-string`; no tags are introduced. `admit/admit-value`, `admit/semantic-value` and the print-node
artifact leave the MCP path.

**Realization is an explicit, bounded evaluation.** A pending cut's continuation returns the form
`(seon.render.walk/page result/e12 {:path P :offset N :count K})`. `page` navigates with `at` and realizes exactly
K members, `(into [] (comp (drop N) (take K)) …)`, inside the evaluation that runs it, under that evaluation's
time limit. Only an explicit request runs it: the expander button or the agent's own REPL call. The printer
never realizes.

**Historical cuts:** option 1, the live object plus the exact saved text, with no new durable write. After the
live subject is gone, a historical cut answers `:gone`.

**Terminal consumers in the same activation** (review A):

- **Held order.** Selection order is the printed order. The emitter's canonical re-sort (`print/ordered-children`)
  is deleted with the switch, and so is `fit`'s.
- **Scalars.** They are printed from `classify`'s literal text, never `pr-str`. A metadata-dispatched Symbol
  prints from its fields.
- **The display is not EDN.** `…N` is not read back anywhere; each consumer that parses AI text is converted in
  the same cut (the tests listed in §6 row 2).
- **Retrieval stays usable.** `get_value`, `/data` and the HTML cut expanders all work through `continue` at
  activation. Deleting the pager and the MCP window does not wait for a later slice.

**Pre-floor reads closed** (review B):

- `shown-result`'s `(:seon.instrument/check value)` reads (`sci/eval.clj:3684`, `:3698`) move after
  `classify`. They become a bounded entry scan (≤ 64 entries, keys compared with `identical?` to the interned
  keyword) over an exact `PersistentArrayMap` or `PersistentHashMap`. They never use `get`, which calls a tree
  map's comparator or a host Map's `.get`.
- `attribute-value`'s reshaping (`value.clj:391–427`) leaves the generic floor. That covers its
  `:my.plan.item/needs` id sort, its component position sort and its per-ref identity pulls. An owning schema
  that wants component order declares it in its pair.
- Throwable frames are **not acquired** by the generic floor. `getStackTrace`'s allocation bound is unproven.
  Stored `:seon.error/*` rows carry frames, and their pair shows them.
- BigDecimal is inspected by its unscaled component's exact class before any method. Scalar bounds count the
  actual spelling (sign, suffix, quotes, escapes).

### 3h. Regressions

**Ordinary** (focused, on a branch, real SCI, armed contracts; root runs them through `bin/test-check`):

1. The owner's three-branch probe at 128 tokens: every branch shows `:sample/text` with a prefix, and
   text ≤ charge ≤ B.
2. A huge first sibling, `{:a (vec (range 100000)) :b {:c 1} :d "x"}`, at B = 300. `:b`, `:c 1` and `"x"`
   survive, and `:a`'s cut is at `[:a]` with an absolute offset and total 100000.
3. An oversized first key (`(keyword (apply str (repeat 5000 "k")))`) followed by `:ok 1`: `:ok 1` is shown after
   the key's chunk.
4. Composite keys: in `(array-map :a 1 [2] :hidden :z 3)`, all three entries show, and continuing at entry 1
   reaches `[2]`.
5. Deep-first `{:a {:b {:c {:d {:e 1}}}} :z 2}` keeps `:z 2`. Fifty 1,000-character strings each show a prefix
   at B = 1,800 (at 450 only five fit: 50 prefixes alone need about 1,000 characters).
6. Exact charge on every §3d value: text ≤ charge ≤ B, and the legend appears once, only with cuts.
7. Typed navigation: nil versus missing; `entry`/`key`/`val`/`nth`/`char` steps; empty and beyond-end are
   distinct; strict progress (every successful expansion shows a new member).
8. One tree across faces: every AI-visible leaf is in the HTML, and every AI cut is exactly one expander whose
   fetched fragment equals `seon.render.walk/continue` at its coordinates, identically through MCP `get_value`
   and `/data`.
9. The MCP value text equals the shared AI text, keeping the exact-shown bypass. A one-key namespaced map
   prints `{:seon.probe/member 1}` (`#:ns{}` lifts only two or more keys), which is what
   `cluster/mcp_test.clj` `jvm-exceptions-keep-the-whole-cause` searches for. Its artifact fixture uses the
   canonical running instance, never a hand-built map (`retrievable-artifacts-commit-no-transaction`).
10. Retained: `background-poll-keeps-identity-while-payloads-grow` and `fit-preserves-breadth-and-long-strings`,
    converted to `prepare`.
11. Debug side by side: exact saved bytes on the left; a labelled live source or the typed gone result on the
    right.
12. Layout: width-100 text, a scalar vector fills, depth 20,000 lays out, and indentation is capped.

**Hostile** (review "Required implementation evidence"). These run **only** as isolated fixture-observation
members (`^{:seon.test/fixture-observation "…"}`, `seon.test.edn:12`), never in default's JVM. Each has a declared
1 s bound and asserts a finite, cut or opaque face with **no call** into the hostile object, proved by a counter
the object increments:

- a user `IChunk` in a `ChunkedCons`; a `StringSeq` over a hostile `CharSequence`; a `KeySeq` over a hostile seq;
  an unrealized `LazySeq`; `(range)`, `(iterate …)` and `(cycle …)` at root, as a member, a key, a set member and a
  path step;
- a metadata-dispatched Symbol; a Ratio and a BigInt with million-digit components;
- a two-link cause cycle; a Throwable overriding `getMessage` with a blocking body (never called); a
  `CompilerException` (opaque);
- a host `Counted`, a `java.util.Map` at `shown-result`, and a `deftype` implementing `IPersistentMap`;
- a sorted map with a counting comparator, never called after construction;
- carried path, root, agent id and source reference: malformed, oversized, a list form and a lazy seq, each
  refused before any digest or refusal text touches it;
- depth-5,000 text **and** HTML; actual emitted bytes ≤ B; a 10⁶-key map at root and at offset 900,000.

### 3i. Cost, measured

**Real queue, exact charge** (single warm runs, unarmed scratch namespace, default PID 87270, 2026-09-26),
selection / layout in milliseconds:

| value | B = 384 | 450 | 1,800 | 45,000 |
|---|---|---|---|---|
| root agent row | 0.6 / 0.3 | 3.4 / 0.3 | 1.8 / 0.6 | 11.4 / 5.2 |
| projection forms (3,435 keys) | 0.8 / 0.2 | 0.4 / 0.1 | 1.6 / 0.5 | 33.3 / 11.3 |
| stored error row | 0.2 / 0.1 | 0.2 / 0.1 | 1.1 / 0.3 | 22.9 / 1.2 |
| test run | 0.3 / 0.1 | 0.2 / 0.1 | 1.0 / 0.5 | 7.7 / 3.6 |
| `{:a (vec (range 100000)) …}` | 0.5 / 0.1 | 0.4 / 0.1 | 1.4 / 0.4 | 32.5 / 10.5 |

Allocation at 45,000 characters: 70 MB for the forms map and 21 MB for the agent row. That is mostly the
persistent node map updated per admission, and it is the first thing to cut when implementing. A first version
recomputed each parent's child sum per admission. It took **9.7 s** on the 100,000-member vector, O(n²). That
version is gone; the incremental sum is what the table measures. Targets: selection O(V log V + V·depth) over
admitted nodes, plus O(offset) navigation as query work; layout O(V + text); both sub-second at the default B.
Clock rows on parent and change are recorded through MCP and `/data` at implementation.

### 3j. Live values: today and the proof

At B = 450 (150 tokens), today's floor spends the budget depth first and prints one EDN elision map per cut.
The proof's faces are shown below (the legend line is omitted).

```clojure
{:db/id 33229,
 :seon.agent/branch :cluster-default,
 :seon.agent/id "root",
 :seon.agent/namespace {:db/id 33223},
 :seon.agent/plan {:db/id 33230, :my.plan/agent {:db/id 33229}},
 :seon.agent/runtime
   {:db/id 33232,
    :seon.runtime/agent {:db/id 33229},
    :seon.runtime/trigger {:db/id 33754},
    :seon.runtime/turns [{…8} {…11} …33]},
 …1}
```

```clojure
{:seon.sci.eval/evaluation
   [:map [:seon.test.accretion/candidate-ctx {…1} …1] [:seon.sci.admit/value {…1} […2]] […3] …13],
 :seon.issue.citation/citation
   [:map
    {:seon.db/attributes true, :seon.program/partition :seon.data}
    [:seon.issue.citation/id :seon.issue.citation/id]
    […2]
    …2],
 :seon.render.walk/graph-root :int,
 …3475}
```

- The owner's three-branch probe at 384 characters shows every `:sample/text` with a prefix.
- `{:a (vec (range 100000)) :b {:c 1} :d "x"}` at 300 keeps `:b {:c 1}` and `"x"`, with `:a`'s cut `…99967`.
- An oversized first key is chunked, and `:ok 1` follows it.
- `(array-map :a 1 [2] :hidden :z 3)` shows all three entries.
- A two-link cause cycle prints two links.
- A metadata-dispatched Symbol prints from its fields.
- A 3,000-digit Ratio prints `#ratio[≈3006 digits]`.
- An unrealized `(map inc [1 2 3])` prints `(…?)`, and `(range)`, whose class is `Iterate`, is opaque.
- A 20-entry sorted map with a counting comparator made **0** comparator calls.

### 3k. Unproven parts

1. Armed cost. The proof ran unarmed, and the selection and emitter contracts add per-call validation.
2. Allocation reduction below the measured 70 MB at 45,000 characters.
3. The constants S = 32, w = 16, the 64-entry sort bound and nest depth 6 are chosen, not measured optima.
4. Coverage of the JDK exception classes Seon actually raises by the per-class Throwable check. A class that
   fails it shows only its class.
5. The hostile fixture list in §3h, as isolated fixture-observation members run by root.
6. The side-by-side debug view and deeper-fragment HTML expanders. Those are row 2(iii), after activation.

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
| 2 floor/print/MCP/web (folds old 2, 3, 5; owner 2026-09-25; revised after Astra `6e9deea71`, re-review `98c563b34` A–D, 2026-09-26) | §3, never two live pipelines. **(ii) Layout landed first** (`63eb0dbcb`, `890a3de11`, lane impl-floor-on-fipp): fipp's own stages over an explicit-stack op stream, hiccup folded from the same stream; sinks, the emit family, tables and the `unknown-face-error` schema deleted (+329/−621); the provenance budget and held canonical order followed. **(i) Safe shared selection and continuation, one activation**, proved on real values with the real queue (§3c–§3d, §3i). **Source:** `src/seon/render/value.clj` (classify table, skeletons, shell queue, exact charge, `prepare` over them; `bounded-text`/`floor` stay as entry names over the same selection with the Throwable row), `src/seon/print.cljc` (emits the selected tree in held order; the `…N`/`…+N`/`…?` cut face and one legend; `render-elision-ai` stays for §5 prompt units), `src/seon/render/walk.clj` (`continue`, `page`), `src/seon/render/data.clj` (typed steps in `at`), `src/seon/render.clj` (nested values reach declared pairs through ordinary selection on the raw value, not through print nodes), `src/seon/cluster.clj` (`mcp-project` returns the shared AI text plus cut coordinates and stores the selection tree; `mcp-get-value` is an adapter), `src/seon/render/web.clj` (`data-response` an adapter; `debug-value-html` renders through the floor), `src/seon/sci/eval.clj` (`shown-result`'s reads after classify), `src/seon/db.clj:3046–3059` (`print/value-at` → `render.data/at`). **Declarations:** `seon.print.edn`, `seon.render.value.edn`, `seon.render.data.edn` (`:seon.render.data/step`, the selection-tree node), `seon.render.walk.edn` (`continuation-error`, `insufficient-floor-budget-error`, `allocator-charge-error`). **Delete, with current line counts:** `value.clj` `text-limits`…`floor` bodies (`:38–132`, 95; about 10 kept as entry names), `stable-entries`/`counted-size`/`window`/`display-value` (`:300–370`, 71), `reference-identity`/`attribute-value` (`:371–427`, 57), `ordered-head` (`:428–448`, 21), `value-node*`/`value-node` (`:449–599`, 151), `pager` (`:610–623`, 14), the print-node `artifact`/`artifact-edn`/`read-artifact`/`artifact-value` (`:686–732`, 47; about 15 back as the tree artifact); `print.cljc` `value-at` (`:212–221`, 10), `valued-kinds`/`node-order`/`ordered-children` (`:398–432`, 35), `requery-fields` and `elision-node` through `fit` (`:715–722`, `:754–1072`, 327); `render.clj` `project-entry`…`project-node` (`:1156–1307`, 152); `render.data` `parse-cursor` defaults and `at`'s raw `get`/`nth` (`:27–90`, 64; about 45 back); `cluster.clj` `mcp-project`'s admission/reconstruction body (`:334–434`, about 70 net) and `mcp-get-value`'s windows (`:493–549`, about 42 net); `web.clj` `data-response`'s navigation/window (`:3611–3717`, about 72 net) and `debug-value-html`'s `pr-str` (5). About **−1,230 / +350** source, net about −880. **Tests converted in the cut** (each red classified: deleted machinery, retired assumption, or a surviving owner's defect): `test/seon/print_test.clj` (fit tests go), `test/seon/render/value_test.clj`, `test/seon/render/data_test.clj`, `test/seon/mcp_test.clj` (`:80` redefines `value-node` — banned — becomes an assertion on the prepared projection), `test/seon/cluster/mcp_test.clj` (the two reds in §3h item 9), `test/seon/render/web_test.clj`, `test/seon/render/transcript_test.clj`, `test/seon/problems_test.clj`, `test/seon/cluster/prompt_test.clj`, `test/seon/error_test.clj`, `test/seon/db_test.clj`, `test/seon/loop_proof_test.clj`, `test/seon/render_simplification_test.clj`, and the baseline `test/seon/schema/datahike_parity.edn`. **(iii) Page, after activation:** deeper-fragment expanders, the realization offer, the side-by-side outline unit (delete the toggle). **Proof:** §3h ordinary 1–12 by root via `bin/test-check`; the hostile list as fixture-observation members only; clock rows on parent and change through MCP and `/data`. **Unproven:** §3k. Size is within the law: the additions are justified by the named deletions in the same cut (root ruling 2026-09-26). | 350 / 1,230 |
| 4 render/problems | Retire `project-node*` redispatch; accept smaller data/final text using merged units without profile. Convert `ai-prose` to data. Probe nested problems uses one share, text-overage diagnostic, no defaults fallback and no repeated producer. | 30 / 140 |
| 6 cluster/operator | Shared runtime/branch-error renderers (9,10,20). Probe >20 faults: complete counts, identities/requery, same terminal/MCP face. | 40 / 75 |
| 7 transcript/walk/web | Render declared history/connection query pages; remove walk presentation min (5,12,14). Probe membership, count/link and exact historical AI/HTML bytes. | 30 / 70 |
| 8 namespace/bootstrap | Referenced names/opening subjects; remove dead omission (15–17). Probe >40 names and small forms; no display token spending. | 35 / 90 |
| 9 test-report | Native refusal/drift data and one message path (7,8,18,19). Probe complete offender/drift retrieval and no caller Throwable branch. | 25 / 65 |
| 10 scripts/config | Remove dev clipping and max-depth/max-children/max-string-length from profile schema/config and all readers; keep only floor B and real query/storage bounds (3,21). Audit all 21 sites. | 20 / 80 |
| 11 prompt | Own §5 capacity behavior: include frame/separators/notice, retain whole units, diagnose an oversized newest unit or explicitly invoke existing compaction. Probe exact saved bytes and no silent overrun. | 20 / 35 |

Repriced estimate: the rows sum to **725 added, 2,065 deleted, net ~1,340 lines deleted**. Row 2 is source only, with its declarations (+35) and tests (+190) priced beside it; the other rows keep their earlier mixed estimates, which include their own declarations and regressions. The first pass's stated 600 / 1,865 did not match its own rows (700 / 1,965; Astra `6e9deea71` P2). None of this is measured implementation. Compared with 670/1,150, this removes migration/frontier/path/leaf-face machinery and
prices identity declarations, emitter charging, offset consolidation and prompt admission explicitly. Slice 1's atomic
caller sweep is the main sizing uncertainty; no claim that 89 producers change within 75 lines.
Must-pass retained regressions: `value-test/background-poll-keeps-identity-while-payloads-grow`, `fit-preserves-breadth-and-long-strings`, carried string/children totals and `preserve-requery`. Add producer overage,
envelope ownership and nested operation-error cases; no green inferred from code or the review's hand calculations.
Root proves publication/load/arming/adoption, runs focused real-SCI probes, then affected/platform/bulk proof once
and browser paint. Record elapsed/allocation, visited nodes, order/emit counts and floor hits on parent/change:
ordinary <1 s, one final emission/comparison, no >20% or 50-ms regression; name proportional large-input cost.
This revision changes zero source/test lines and runs no JVM/tests or new live probes. Historical evidence above is retained; current health, memory and implementation performance are unverified. The foreign http-kit submodule remains untouched.
