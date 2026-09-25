---
type: design
status: "owner adopted shell-order floor 2026-09-25; §3 and §6 row 2 revised by Opus after Astra review 6e9deea71; awaiting Astra re-review"
created: 2026-09-25
scope: One schema dispatch and one shape-aware, budget-owning floor printer
---
# One render path

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

## 3. One floor: held source → safe structural selection → one tree → two faces → one continuation entrance

**What exists and what it becomes.** Today MCP admits a raw value (`admit/admit-value`, which runs
`Throwable->map` and Java collection admission, `sci/admit.clj:297`), turns it into an artifact, prepares the
artifact's reconstructed semantic value, rebuilds semantic data from the selected node and serializes that again
(`cluster.clj:388–494`). `get_value` reads the blob and applies its own string and collection windows
(`cluster.clj:555–597`, `render.value/window` `value.clj:311`). `/data` separately reads, reconstructs and renders
(`web.clj:3516`). The floor itself walks depth first under a node counter (`value-node*`, `value.clj:446`), then
`print/fit` re-emits and halves limits until the text fits (`print.cljc:1234`). `bounded-text`/`floor`
(`value.clj:65`, `:119`) are two more cut engines, and `repl/pretty-response` reads prepared text back and
reprints it (`repl.clj:457`).

The design is one data flow, owned by the entrance of the
[query + render building block](query-render-building-block-2026-09-25.md) (`seon.render/units`, its §3e–§3f):

1. **Held source.** A live value, a live result handle, an admitted artifact digest or a query at a branch basis,
   resolved under explicit custody. Nothing touches the value before step 2.
2. **Safe structural selection** (`render.value/prepare`). A table of permitted operations (§3a) decides what may
   be done to each reached object. A shell-order queue (§3c) spends one character budget B, which is the saved
   AI text's length (§3d). It takes **no face target**.
3. **One tree.** Plain print nodes: retained literal bytes, entries with key and value roles, cuts with absolute
   offsets and the source reference. No unknown object reaches a face.
4. **Two faces.** AI: the tree as fipp's own operation stream, laid out by fipp's own stages (§3e); this text is
   clipped and is the saved text. HTML: the same tree as Hiccup, unclipped, each cut a lazy expander (§3f).
5. **One continuation entrance.** `get_value` and `/data` decode coordinates and call `units` with the cut's
   source reference, typed path and absolute offset (§3g). Neither transport navigates, windows or selects.

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
| `ChunkedCons` | `.chunkedFirst` (field, `ChunkedCons.java:56`), `.chunkedMore` (field, `:64`) | chunk must be exactly `ArrayChunk` (`nth`/`count` read an array, `ArrayChunk.java:39–51`); tail |
| `PersistentVector$ChunkedSeq` | `count` (`PersistentVector.java:512`), `first`, `next` over its own vector | members |
| `Throwable` subclass *C* | Only when `getMessage`, `getCause`, `getStackTrace` and `getSuppressed` all have declaring class `java.lang.Throwable` (`Class/getMethod` + `getDeclaringClass`, memoized per Class). `getData` only for exactly `ExceptionInfo` (`ExceptionInfo.java:34`) | message, data, cause, frames |

Consequences, each a closed trace from the review:

- **Seq cells** (P1-3). `ChunkedCons` is safe only through an exact `ArrayChunk`; a user `IChunk` is opaque. A
  realized `LazySeq` is unwrapped to its cached seq, which is then classified. `StringSeq`, `APersistentMap$KeySeq`,
  `ASeq` subclasses, `Cycle`, `Iterate`, `Repeat`, `Range`, `IteratorSeq` and `EnumerationSeq` are not rows, so a
  cell of one of those classes ends the prefix. The first unrealized or unlisted cell becomes the seq's tail cut with
  an unknown total: `(1 2 3 …?)`. `(range)`, `(iterate f x)` and `(cycle xs)` show `(…?)` or at most their
  already-held first cell. **Measured:** the probe's `seq-cells` steps `Cons`, realized `LazySeq` and
  `ChunkedCons`/`ArrayChunk` through fields only.
- **Row 9.5** (ruled): the pending tail is the declared "unrealized lazy seq" marker naming the cell's class, never
  nil. Realizing a page is an explicit, bounded evaluation (§3g), never the printer. The old claim that an agent's
  top-level lazy result is realized before `shown-result` renders it is withdrawn: the review traced the evaluation
  path (`sci/eval.clj:3264`, `bind-result!` `:620`) and found no such step. An unrealized result shows pending.
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
  (`System/identityHashCode` plus `identical?`) bounded by the visited links (≤ B). `getStackTrace` runs only when
  a link's `:trace` node is reached. It copies at most the JVM's recorded frames (`-XX:MaxJavaStackTraceDepth`,
  1,024 by default), which is JDK code over already-captured data. `Throwable->map` is never called
  (`core_print.clj:494–501`). Stored flat `:seon.error/*` maps are plain data and are preferred wherever they
  exist. The projection is `#error {:via [{:type C, :message M, :data D} …], :trace [F …]}` in that declared
  order.
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

**Admission unit** (P1-5 fix). A member is first admitted as its **skeleton**, a bounded face that is always
followed by a value face:

- a scalar: its literal when its bound is ≤ S = 32 characters, else its descriptor;
- a string, keyword or symbol: its first chunk of w = 16 characters plus the cut `…+N` when longer;
- a collection: `open …N close` (for example `{…7}` or `[…100000]`);
- a map entry: key skeleton, a space, value skeleton;
- an opaque object: its `#object[…]` face, which is at most the class name plus 12 characters. A class name over
  S characters is chunked like a string.

Every skeleton is at most S plus a small cut face, so **an unaffordable member closes its collection only when the
room left is under one bounded skeleton.** A huge first key no longer starves a later `:ok`: the huge key is a
descriptor at its position.

**Order.** The root has depth 0. A member has depth d and ordinal o, both 1-based. Its **shell** is
`s = max(d, o₁, …, o_d)` over the ordinals along its path. A string's k-th chunk is at shell max(s, k), and a
collection's refinement to member o is at shell max(s, o). The queue pops by `[s d o seq]`, where `seq` is the
insertion counter. It holds at most one pending member per open collection or string: a k-way merge, with queue
size ≤ open nodes + 1.

**Loop.** Pop the least entry, price its skeleton with the §3d charge, and admit it if it fits the room. Then push
its parent's next member and its own first refinement. If it does not fit, close its parent at that ordinal (one
tail cut) and continue with the other branches. Stop when the queue empties or the room is under the smallest
skeleton. Declared renderers run when their node is reached (§2): a smaller returned value takes that node's
place at the same key, and final text is one leaf charged whole or §2's overage diagnostic.

**What is guaranteed, conditionally** (the review's "What can be proved"). Shells are popped in nondecreasing
order. If the whole of complete shell k (every node with depth ≤ k and every ordinal ≤ k, at least as a skeleton,
and every string's first k chunks) costs ≤ B, the output contains all of it. After that, shell k+1 is spent in
queue order until B runs out. Nothing stronger is claimed. There is no equal per-branch allocation: an early bushy
branch spends its share of shell k+1 before a later sibling's. A deep singleton chain costs one skeleton per level.
No finite B shows every part of an arbitrary value; cuts say what was omitted and where.

**Measured complete shells** (prototype, charge ≤ B, §3d):

| Value | B = 450 | B = 1,800 | B = 45,000 (15,000 tokens) |
|---|---|---|---|
| agent row | shell 5 | 7 | 29 |
| projection forms (3,435 keys) | 3 | 5 | ≥ 200 (probe cap) |
| stored error row | 4 | 25 | whole value |
| Throwable chain | 5 | 12 | whole value |
| test run | 7 | 11 | whole value |

**Root and minimum.** If the root's own skeleton plus the legend (§3d) exceeds B, selection returns the declared
`:seon.print/insufficient-floor-budget-error` (new, over `:seon.error/base`, naming B and the needed charge). It
never claims a snippet fits. A continuation that cannot admit even its first member's skeleton returns that same
refusal instead of an unchanged cut, so every successful expansion shows at least one new member (strict
progress).

### 3d. B is the length of the saved AI text

B is converted once at entry: `(tokens/estimate-chars token-budget calibration)` (3 characters per token live). It
is charged against the **complete saved AI text**: content, delimiters, separators, descriptors, cuts, the legend,
newlines and indentation (P1-8). Selection charges every node its **reserved** cost:

- its literal bytes (strings: one pair of quotes per string plus escaped chunk content; not `pr-str` per chunk);
- its delimiters and cut face;
- per member, its separator at the **broken** price: `,` + newline + the capped indentation I(d) at that depth.
  I(d) is the sum of the §3e nests above it, where nesting stops at depth 6, so I(d) ≤ 18 columns;
- per entry, its key/value break: newline + I(d) + 2;
- the legend L, reserved at entry and refunded if the selection has no cut.

fipp emits exactly the charged text operations. It emits each `:line` either as its inline space (at most the
reserved break) or as a newline plus an indentation of at most I(d). Therefore **emitted length ≤ charge ≤ B** by
construction. After layout, `(<= (count text) charge)` is checked once. A violation is the declared
`:seon.print/allocator-charge-error` (new, over `:seon.error/base`, naming path, B, charge and length): a defect,
never a second attempt. `:seon.render.value/text-chars` and `:seon.render.value/charge` are recorded with the
saved text. Whole-unit prompt admission (§5) counts those saved bytes.

Measured on the prototype, with the flat length (a single line), the laid-out text (width 100) and the reserved
charge, in characters:

| Value | B = 450: flat / text / charge | B = 1,800 | B = 45,000 |
|---|---|---|---|
| agent row | 182 / 188 / 244 | 1,072 / 1,295 / 1,778 | 32,410 / 37,867 / 43,051 |
| projection forms | 220 / 224 / 309 | 901 / 974 / 1,220 | 35,702 / 38,367 / 44,184 |
| stored error | 250 / 270 / 297 | 1,626 / 1,671 / 1,793 | 2,957 / 3,002 / 3,124 |
| Throwable chain | 233 / 245 / 342 | 1,197 / 1,416 / 1,741 | 1,402 / 1,647 / 2,007 |
| test run | 261 / 269 / 303 | 646 / 671 / 740 | 4,064 / 4,472 / 5,114 |

Text was ≤ charge ≤ B in all 15 cases. Layout adds 2–21% over flat, against 2.4× for `fipp.edn`'s bracket
alignment in the first pass. The reservation over-charges the laid-out text by 4–40% (median 15%). That
unused room is the stated price of never re-fitting. The alternative, B over flat content only, weakens the
owner's one budget and is not taken.

**Legend.** It appears once, after the value and only when a cut exists, and it is charged:
`;; …N = N more · continue: (seon.render/units {:seon.render/source-ref result/e12 :seon.render.data/path P :seon.render.data/offset N})`,
or `;; …N = N more · not retrievable: <typed reason>` (§3g). About 100 or 50 characters.

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
- Errors read `#error {:via [{:type …, :message …, :data …} …], :trace ["seon.db/q (db.clj:2365)" …]}`.

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

### 3g. One continuation entrance, three sources

A cut's coordinates are **source reference + typed path + absolute offset** into the represented value, plus the
producer chain when a declared renderer returned the represented value. `get_value` and `/data` are adapters:
they validate wire input, call `(seon.render/units {:seon.render/source-ref r :seon.render.data/path p
:seon.render.data/offset n …custody})`, and encode the common result. The source table, the stale-producer rule
and `:seon.render/continuation-error` with reason `[:enum :stale :gone :unavailable :malformed]` are the building
block's (§3e there). This section only fixes what the floor contributes.

- **Wire validation before parsing** (P1-9). The `path` parameter is bounded to 4,096 bytes before
  `edn/read-string`. The parse is then checked against the Malli schema of typed steps
  (`:seon.render.data/step`, new). A malformed, oversized or untyped cursor is a `:malformed` continuation error.
  `parse-cursor`'s default-to-root and default-to-zero branches (`data.clj:27–44`) are deleted. A browser never
  supplies a form: custody (connection, database value at the basis, SCI context) is the server's.
- **Live results** are the agent's `result-objects` (`sci/eval.clj:620`, `repl/live-response` `repl.clj:465`).
  They are reselected under the current program; `:gone` after eviction or restart.
- **Artifacts** stay only where MCP writes them today. MCP no longer calls `admit/admit-value` before the floor
  (P1-1: that call runs `Throwable->map` and Java collection admission ahead of any safe step). When the B-sized
  selection has a cut and a connection exists, MCP writes an artifact made by **a second safe selection of the same
  value at the admission allowance** A (the existing `:seon.config.eval.result/blob-threshold`). It is written as
  EDN, with opaque faces and cuts as tagged literals (`#seon/object "…"`, `#seon/cut {:total …}`). Reading it back
  gives plain data plus exact `TaggedLiteral`s, both rows of §3a. Continuing it is ordinary selection, and its
  own cuts are terminal `:unavailable` ("beyond admission"). `admit/semantic-value` reconstruction (`admit.clj:584`)
  leaves the MCP path. Cost per expansion: one O(artifact bytes) parse plus selection, measured per slice; no
  cache is claimed.
- **Entity queries** at a branch and basis follow the building block's `as-of` rule; current-entity pages say
  "current".
- **Realization is an explicit, bounded evaluation** (row 9.5). A pending cut's continuation returns
  `:seon.render.continuation/realize`, carrying the derived form `(take n (drop offset (seon.render.data/at
  result/e12 path)))`. Only an explicit request runs it: the expander button, or the agent's own REPL call. It runs
  as an ordinary evaluation in the owning SCI context, under that evaluation's time limit and admission caps, and
  records its own evaluation and saved text. The printer never realizes.
- **Historical cuts** (review P1-10): **option 1, live object plus exact saved text,** with no new durable
  write. After the live subject disappears, a historical cut answers `:gone`. The first pass's per-evaluation
  artifact blob is withdrawn. Options 2 (a durable bounded snapshot) and 3 (indexed durable results) are the
  review's, priced there, and they are the owner's to choose if post-restart exploration is wanted.
- **Pre-floor reads found while probing.** `shown-result` reads `(:seon.instrument/check value)` on the raw result
  (`sci/eval.clj:3274`). `RT/get` sends a `java.util.Map` to its own `.get` (`RT.java:778–789`), so a
  hostile Map runs code before the floor. Slice 2(i) moves that check after `classify` (exact persistent maps
  only).

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
5. Deep-first `{:a {:b {:c {:d {:e 1}}}} :z 2}` keeps `:z 2`. Fifty 1,000-character strings each show a prefix.
6. Exact charge on every §3d value: text ≤ charge ≤ B, and the legend appears once, only with cuts.
7. Typed navigation: nil versus missing; `entry`/`key`/`val`/`nth`/`char` steps; empty and beyond-end are
   distinct; strict progress (every successful expansion shows a new member).
8. One tree across faces: every AI-visible leaf is in the HTML, and every AI cut is exactly one expander whose
   fetched fragment equals `units` at its coordinates, identically through MCP `get_value` and `/data`.
9. The MCP value text equals the shared AI text, keeping the exact-shown bypass (`cluster.clj:414–422`).
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

### 3i. Cost, measured and to measure

**Today's floor** (`prepare` at 150 / 600 / 15,000 tokens, same JVM, milliseconds): agent row 8.0 / 20.7 / 29.4;
projection forms 5.7 / 4.6 / 5.6; stored error 2.2 / 1.8 / 1.3; Throwable chain 4.6 / 3.8 / 3.2; test run
3.6 / 3.4 / 3.5. Every text was a single line. The profile's `max-children 32` caps the forms map at 4,116
characters even at 45,000 (9% of B), and the agent row at 36,134.

**Prototype** (complete-shell search, which reruns selection once per shell, so an upper bound on the queue's
work). First run: selection 0–43 ms and layout 0–31 ms for every value at every budget, except the forms map at
45,000, which took 123 ms of selection over 200 shell passes. Second run: selection ≤ 13 ms and layout ≤ 10 ms,
with the forms map at 68 ms. The queue does one pass.

**Targets:** selection O(V log V) over admitted nodes, plus O(offset) navigation charged as query work; layout
O(V + text); both sub-second at the default B. The slices' clock rows record, on parent and change: elapsed time,
allocation, visited nodes, queue maximum, navigation steps, floor hits, and flat, text and charge characters,
through MCP and `/data`. A regression over 20% or 50 ms is fixed first. Any case over 1 s names its collection.

### 3j. Four live values: today and the prototype

Both columns are **measured** at B = 450 characters (150 tokens). The prototype shows complete shells only; the
queue's partial shell adds members up to B. The probe printed map cuts with the withdrawn `:… N` face; they are
shown here in §3e's `…N` face, one character shorter each.

1. **Root agent row.** Today (399 characters, one line): `{:db/id 42410, :seon.agent/branch :cluster-default,
   :seon.agent/id "root", :seon.agent/namespace #:db{:id 42404}, :seon.print/elision {…:omitted 3…}}`. The
   plan, runtime and settings are gone. Prototype (188 characters, shell 5, pull order kept):
   ```clojure
   {:db/id 42410,
    :seon.agent/branch :cluster-default,
    :seon.agent/id "root",
    :seon.agent/namespace {:db/id 42404},
    :seon.agent/plan {:db/id 42411, :my.plan/agent {:db/id 42410}},
    …2}
   ```
2. **Projection forms, 3,435 keys.** Today (323 characters): one key, then a 290-character elision map. Prototype
   (224 characters):
   ```clojure
   {:seon.sci.eval/evaluation [:map, [:seon.test.accretion/candidate-ctx, …2], …14],
    :seon.issue.citation/citation [:map, {:seon.db/attributes true, …1}, …4],
    :seon.error.occurrence/id [:string, {:max 12, …3}],
    …3432}
   ```
3. **Throwable chain.** Today: `#error {<elision of the whole throwable>}` (291 characters); at 600 tokens it opens
   with REPL frames before `:via`. Prototype (245 characters, shell 5, declared type/message/data order). At
   1,800 characters (shell 12) every `:data` map shows, including the real `seon.db/q` refusal's keys:
   ```clojure
   #error {:via
   [{:type clojure.lang.ExceptionInfo, :message "Agent run failed", …1},
    {:type clojure.lang.ExceptionInfo, :message "Datahike refused"…+19, …1},
    {:type java.lang.IllegalStateException, :message "connection relea"…+3}]}
   ```
4. **Test run.** Today (434 characters): four of 16 keys, then an elision map. Prototype (269 characters, shell 7):
   `:db/id` and `:seon.test.run/id` first, then `at`, `basis-t`, `branch`, `change-basis-t` and `cluster`, then
   `…9`.

The legend (§3d) follows each of these once. It is omitted above because the probe had no source reference.

### 3k. Unproven parts

1. The queue's partial-shell behaviour and its single-pass cost; only complete shells were measured.
2. The allocation and memory of selection and layout; only elapsed time was measured.
3. The constants S = 32, w = 16, the 64-entry sort bound, nest depth 6, F = 1,000 and the 4,096-byte cursor bound
   were chosen here. F and nest depth 6 have the measurements above; the others do not.
4. The per-class Throwable check has not been run against the JDK exception classes Seon actually raises. Classes
   that fail it lose their message and show only their class.
5. The artifact second selection's cost at the admission allowance, and the realization request's route in the
   browser (it needs the one-REPL evaluation entrance).

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
| 2 floor/print/MCP/web (folds old 2, 3, 5; owner 2026-09-25; revised after Astra `6e9deea71`) | §3, split by proof boundary, never two live pipelines. **(i) Safe shared selection and continuation, one activation:** the §3a table (scalar encoder, seq cells, lazy Throwable links), entry skeletons, the shell-order queue, the §3d charge on the existing emitter, the typed-step navigator in `render.data/at`, and the floor's share of `units` continuations (the building block's S3 books the entrance itself, +38 there). In the same cut convert **every** caller: `mcp-project` stops calling `admit/admit-value`, `artifact`/`artifact-edn` on the raw value and `admit/semantic-value`, returns the shared AI text plus cut coordinates, and writes its artifact from the second safe selection (§3g); `mcp-get-value` and `/data` `data-response` become adapters; `shown-result`'s raw key read moves after `classify`. **Delete** in (i): `value.clj` `text-limits`, `text-full`, `datum`, `causes`, `link-lines` and the bodies of `bounded-text`/`floor` (`:38–131`, entry names kept as calls into `prepare` for boot/flow/store/test callers); `stable-entries`, `counted-size`, `window`, `display-value` (`:297–366`); `attribute-value`'s `pr-str` tie-break; `ordered-head` (`:425–445`); `value-node*`, `value-node` (`:446–596`); `pager` (`:607–620`); `print.cljc` `value-at` (`:403`), `requery-fields`, `elision-node` (`:974–1052`), `preserve-requery` and the fit family (`:1054–1299`); `render.data/parse-cursor`'s root/zero defaults; the `mcp-get-value` string and collection windows (`cluster.clj:575–594`); the `mcp-project` admission/reconstruction body (`:423–494`). **(ii) Layout, after the deep and byte proofs:** `tree->ops` over fipp's own stages, `node->hiccup` with F-deep fragments; delete `Sink`, `TextSink`, `HiccupSink`, `TeeSink` and the sink helpers (`print.cljc:18–30`, `:192–338`), the `emit` multimethod family and `emit-node` re-sort (`:559–871`), table emission (`:496–557`), `render-elision-ai`'s EDN-map face, `visible-items`/`structural-cut?`, `repl/pretty-response`'s read-back and `clojure.pprint` (`repl.clj:457–463`, `:493–501`) and `web/debug-value-html`'s `pr-str` (`web.clj:795–798`). The selection contract does not change in (ii). **(iii) Page:** cut expanders and deeper-fragment expanders through the one entrance, the realization offer (§3g, row 9.5), and the side-by-side outline unit (delete the toggle). No durable historical capture (option 1); options 2–3 wait for the owner. **Convert:** `eval/drive.clj:135` (reads emitted text back with `::length 32`), `instrument_test.clj:1418` (`text-sink`/`sink?` as reload subject), `cluster/prompt.clj:280`, and the four `print/fit` tests. **Proof:** §3h ordinary 1–12 by root via `bin/test-check`; the hostile list as fixture-observation members only; §3i clock rows on parent and change through MCP and `/data`. **Unproven:** §3k. **Estimate** (planning, not measured; source lines only): (i) **+200 / −800**, (ii) **+90 / −420**, (iii) **+45 / −30**, total **+335 / −1,250**, net about −915. Separately: declarations about +35 (`:seon.render.data/step`, the tree node schema, `insufficient-floor-budget-error`, `allocator-charge-error`; `continuation-error` is the building block's); tests about +190 (i 120, ii 40, iii 30), with the deleted fit tests' lines counted when the conversion lands. `print.cljc` 1,307 → about 560 lines, `value.clj` 751 → about 480. Every sub-slice exceeds or approaches the 100-line law: (i) needs the owner's waiver extended to this activation, and there is no smaller cut that converts every caller at once. | 335 / 1,250 |
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
