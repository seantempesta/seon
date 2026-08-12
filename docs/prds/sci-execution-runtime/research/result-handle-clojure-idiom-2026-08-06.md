---
type: research
status: complete
tags: [research, sci, repl, database, rendering]
---

# Result-handle Clojure idiom

## Verdict

**RECOMMENDATION: use the readable symbol `result/eid-88`, resolve only the
literal executable handles in the current parsed form on `:io`, and print the
real admitted value followed by `; result/eid-<new-receipt-eid>`.** Generalize
the face as `<kind>/eid-<eid>`, including `message/eid-91` and `error/eid-94`.

This is the most Clojure-like design for the semantics the owner ruled. A
symbol is Clojure's form for resolving a name to an ordinary value in an
evaluation environment. A tagged literal is the right Clojure face for a
first-class reference value that remains a reference. It is not the right face
for a token that must disappear transparently into an arbitrary underlying
value in every evaluated position. The existing Probe C already demonstrates
the symbol contract in bare, collection, argument, and destructuring positions
and preserves ordinary quote behavior
(`docs/prds/sci-execution-runtime/research/result-symbol-resolution-2026-08-06.md:272-297`).

The spelling is a narrow amendment to the ruled `result/<eid>` surface, not a
new identity: `eid-` makes the numeric suffix readable Clojure and tells the
agent exactly what the number is. The receipt EID remains the only identity.
Datahike allocates it from the database's `:max-eid` and exposes tempid
resolution in the transaction report
(`reference-code/datahike/src/datahike/db/transaction.cljc:56-88,945-971,1288-1303`).

Do not make resolved values carry receipt identity in Clojure metadata, and do
not make a result record pretend to be every possible Clojure value. Both
approaches fail at ordinary value semantics before performance enters the
discussion.

## Required reading record and scope

I read both required foundation reports end to end, not by search:

- `docs/prds/sci-execution-runtime/research/result-identity-archaeology-2026-08-06.md`;
- `docs/prds/sci-execution-runtime/research/result-symbol-resolution-2026-08-06.md`.

I also read the applicable root and localized instructions, the active program
roadmap and current edge, the linked architecture documents, and the complete
`data-oriented-clojure`, `repl`, `datahike`, and `seon-flow-architecture`
skills before designing. Production source remained read-only. The only
authored paths are this report and these probes:

- `tmp/result_handle_reader_probe_2026_08_06.clj`;
- `tmp/result_handle_metadata_probe_2026_08_06.clj`.

The earlier mechanics probe
`tmp/result_symbol_resolution_2026_08_06.clj` was rerun against the current
tree but not edited. It again resolved `result/e3` in bare, vector, argument,
and destructuring positions, left quoted forms as symbols, returned a flat
missing-EID error, and measured an 84.8 ns median preparation increment in
this run. That timing is evidence about the probe, not an implementation
graduation number; the retained benchmark design is recorded at
`docs/prds/sci-execution-runtime/research/result-symbol-resolution-2026-08-06.md:272-297,367-393`.

## Dependency ledger

| Dependency or owner | Selected revision | Seam read |
|---|---|---|
| SCI | `2db3358cba91` | Edamame delegation and readers at `reference-code/sci/src/sci/impl/parser.cljc:142-168`; `intern` at `reference-code/sci/src/sci/core.cljc:259-270`; generation-aware fork at `reference-code/sci/src/sci/core.cljc:331-337` |
| Edamame | `38e627467daa3f6f1e5a8eb6421f702d2a940b7f` | qualified-symbol grammar at `reference-code/edamame/src/edamame/impl/parser.cljc:139-161`; tagged-reader invocation at `:592-606` |
| Datahike | `10540578248e` | EID allocation at `reference-code/datahike/src/datahike/db/transaction.cljc:56-88,945-971,1288-1303` |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `:io`/`:compute` contract already grounded by the foundation report at `docs/prds/sci-execution-runtime/research/result-symbol-resolution-2026-08-06.md:175-183,207-220` |
| Konserve | `07377c27c8288b7484f0aa7b82e8158b415985be` | synchronous blob read beneath the existing `seon.blob` owner, grounded at `docs/prds/sci-execution-runtime/research/result-symbol-resolution-2026-08-06.md:335-365` |
| Seon reader | current tree | accepted tag map and refusal at `src/seon/sci/reader.cljc:20-34`; one Edamame parse at `:498-525`; public tag input at `:569-596` |
| Seon evaluator | current tree | reader event at `src/seon/sci/eval.clj:511-543`; compute contract at `:1558-1576`; SCI evaluation and admission at `:1647-1680,1701-1755` |
| Admission and print | current tree | projection at `src/seon/sci/admit.clj:229-325`; metadata-free semantic reconstruction and EDN at `:407-467`; print dispatch at `src/seon/print.cljc:292-295,439-500,542-589` |
| Run loop and transcript | current tree | pinned pre-eval database value at `src/seon/cluster/loop.clj:1544-1588`; result window/blob split at `:529-552`; receipt EID and stored window at `src/seon/render/transcript.clj:339-373`; bounded print at `:435-502` |

The Datahike pin is newer than the pin recorded in the symbol-resolution
foundation report (`...result-symbol-resolution-2026-08-06.md:68-76`). I
therefore reread the current allocator source rather than carrying the older
SHA forward.

## What the reader and SCI actually support

`result/88` fails in Edamame before SCI analysis because the name segment of a
qualified symbol may not begin with a number
(`reference-code/edamame/src/edamame/impl/parser.cljc:139-161`). The reader
probe reproduced the flat `:seon.sci.reader/unreadable` value, while
`result/eid-88` parsed as an ordinary symbol
(`tmp/result_handle_reader_probe_2026_08_06.clj:8-24,48-68`). Altering this
grammar would produce text that host Clojure cannot read and is already
rejected by the foundation analysis
(`docs/prds/sci-execution-runtime/research/result-symbol-resolution-2026-08-06.md:63-66,85-99`).

Tagged literals do not require a second reader. Seon's reader accepts an
explicit `::tags` map and delegates its chosen function through SCI to Edamame
(`src/seon/sci/reader.cljc:20-34,102-120,498-525,569-596`). Edamame reads the
tag and its data, then invokes the selected reader function synchronously
(`reference-code/edamame/src/edamame/impl/parser.cljc:592-606`). The probe
confirmed all of these properties:

- `#seon/result 88` is accepted when `seon/result` is configured;
- an unknown tag returns Seon's flat refused-tag value;
- a returned `clojure.lang.TaggedLiteral` prints and reads as
  `#seon/result 88`; and
- tag functions run before evaluation, including beneath quote
  (`tmp/result_handle_reader_probe_2026_08_06.clj:40-91`).

The current evaluator does not provide a tag map: `reader-context` returns
namespace resolution state only, and `one-event` adds only source
(`src/seon/sci/eval.clj:511-543`). A tagged design would therefore require
wiring the declared reader function through this existing one-reader seam; it
does not justify another grammar or another parse.

One safety result is decisive. A data reader's return is an evaluation form,
not automatically inert data. In the probe, a reader returning the list
`(+ 40 2)` caused unquoted `#seon/result 88` to evaluate to `42`; quote returned
the list itself (`tmp/result_handle_reader_probe_2026_08_06.clj:54-55,81-83`).
A tagged reader therefore cannot fetch an arbitrary stored value and simply
return it: a stored list could execute. A safe transparent tagged design still
needs a pure marker followed by parsed-form preparation and a transient
binding. At that point the tagged face is extra machinery around the same
symbol mechanism.

## Laziness, precisely

### Probe C is already the useful laziness

Probe C is lazy at the form boundary: it reads the form once, detects only
literal executable result symbols, fetches only those receipt EIDs from the
form's pinned database value, loads blobs on `:io`, and transiently interns the
ordinary values before compute
(`docs/prds/sci-execution-runtime/research/result-symbol-resolution-2026-08-06.md:207-220,260-270,367-385`).
It does not populate all historical results and it performs no handle work for
a form without a handle.

This is the correct boundary because the run loop already captures
`db-before-evaluation` once before submission
(`src/seon/cluster/loop.clj:1544-1588`). The evaluator itself runs synchronously
on `:compute` and promises not to block (`src/seon/sci/eval.clj:1558-1576`). A
receipt pull or blob read later, on first lookup inside SCI, would either block
`:compute` or return a non-transparent future/proxy. Resolution must therefore
finish on `:io` before that submission, using exactly the captured immutable
database value. Explicit `seon.db/pull` already accepts that value and EID
(`src/seon/db.clj:632-670`).

The honest limitation is ordinary Clojure quote behavior. Literal executable
positions resolve; quoted and syntax-quoted symbols remain symbols. Handles
constructed dynamically by `symbol` or generated later by a macro are not in
the parsed event and do not resolve. The existing probe demonstrates those
limits rather than claiming magical transparency
(`docs/prds/sci-execution-runtime/research/result-symbol-resolution-2026-08-06.md:285-297,389-393`).

### A lazy reference object is not transparent

The first-class proxy probe implemented one object with `IDeref` and `ILookup`.
`@proxy` and `(:answer proxy)` worked, but it was not a map, was not equal to
the underlying value, could not be sequenced, invoked, or used as a number
(`tmp/result_handle_metadata_probe_2026_08_06.clj:8-14,75-92`). Implementing
more interfaces only creates an open-ended imitation of Clojure's value
universe; no record can be simultaneously a string, number, function, list,
map, and every application record.

It also cannot supply real laziness under the workload law. Fetching on its
first `deref` or lookup blocks `:compute`; fetching before compute reduces it
to Probe C with a wrapper left over. Admission checks `IDeref` before records
and deliberately turns references into object nodes
(`src/seon/sci/admit.clj:295-310`). The probe therefore admitted the proxy as
an address-bearing opaque object instead of its value
(`tmp/result_handle_metadata_probe_2026_08_06.clj:31-38,81-92`). This is an
object-shaped hack, not transparent Clojure data.

### Metadata survives SCI but not the boundaries that matter

Raw SCI preserved `{:seon.result/eid 88}` metadata on the probed map through
direct access, `assoc`, `select-keys`, and nesting
(`tmp/result_handle_metadata_probe_2026_08_06.clj:40-52`). That is the best
case, not a system contract:

- strings, numbers, and nil cannot carry Clojure metadata; the probe produced
  `ClassCastException` for the first two and `NullPointerException` for nil
  (`tmp/result_handle_metadata_probe_2026_08_06.clj:76-79`);
- admission reconstructs maps, vectors, lists, sets, and records as new values
  without copying metadata (`src/seon/sci/admit.clj:300-344,407-445`), and its
  canonical EDN explicitly binds `*print-meta*` false (`:447-467`);
- both direct admission and the complete `seon.sci.eval/evaluate` probe returned
  the right value with nil metadata
  (`tmp/result_handle_metadata_probe_2026_08_06.clj:24-38,54-67,72-95`); and
- the current message surface is still the superseded string-only contract: it
  rejects the marked map with flat `:my.message/no-content`
  (`src/my/message.clj:17-59`; probe at
  `tmp/result_handle_metadata_probe_2026_08_06.clj:93-95`). The ruled one-value
  message design will receive an admitted value, so metadata has already been
  stripped before that boundary (`src/seon/sci/eval.clj:1701-1755`).

Desk persistence is a deliberately different contract: it prints with
`*print-meta*` true and accepts the value only if value, class, and metadata all
round-trip (`src/seon/sci/eval.clj:329-340`). That proves metadata can be
preserved when metadata itself is the contract. It does not make metadata a
uniform carrier for arbitrary eval results.

## Whole-design options

### Option 1 — readable symbol, per-form preparation, real print

**Face:** `result/eid-88`.

**Resolution and laziness:** the one reader produces the ordinary symbol. On
`:io`, parsed-form preparation collects literal executable result symbols,
queries each distinct receipt from `db-before-evaluation`, reads and parses an
inline result or blob, and interns the admitted semantic value into the fresh
turn fork. Missing receipt, result, or blob becomes a flat value under a
specific `:seon.result/*` kind. The same parsed event then enters compute. This
is exactly the Probe C boundary, with the final `eid-` spelling
(`docs/prds/sci-execution-runtime/research/result-symbol-resolution-2026-08-06.md:299-333,367-385`).

**Print round trip:** `seon.print` emits the actual admitted value or honest
elision. `seon.render.transcript` appends one suffix derived from the receipt
entity in the same transcript query:

```clojure
user=> result/eid-88
{:answer 42} ; result/eid-103
```

The source names receipt 88; the suffix names the new evaluation receipt 103.
That distinction is useful and does not require hidden provenance on the value.

**Pros:** ordinary Var semantics in every probed evaluated position; quote is
ordinary quote; no SCI fork change; no new reader tag; no wrapper crosses
admission; explicit EID; retains the ruled kind/EID family.

**Cons:** adds four characters to the idealized invalid face; only literal
executable occurrences are prepared; the `result` namespace is transient
resolution state rather than a durable program namespace.

**Verdict:** recommended.

### Option 2 — tagged literal marker, per-form preparation, tag print

**Face:** `#seon/result 88`.

**Resolution and laziness:** the configured data reader returns a pure
self-evaluating `ResultRef` marker, never performs database I/O. Still on
`:io`, a preparation walk finds the markers, reads each receipt at the pinned
database value, interns the values under private generated names, and rewrites
executable markers to those names before submitting the same parsed event.
Quoted markers would have to remain explicit references or receive a separate
context-aware rewrite; silently turning a tagged literal into a symbol changes
the result of quote, as the probe demonstrates
(`tmp/result_handle_reader_probe_2026_08_06.clj:69-77`).

**Print round trip:** an explicit reference marker can print as
`#seon/result 88`; the probe verified that a `TaggedLiteral` has exactly that
round trip (`tmp/result_handle_reader_probe_2026_08_06.clj:76-80`). The
underlying arbitrary value cannot safely inherit this face after the marker is
resolved, because it has no uniform identity carrier.

**Pros:** numeric EID is directly readable; the syntax announces that this is
a special data reference; an explicit unresolved reference has a natural EDN
round trip. It generalizes cleanly as `#seon/message 91` and
`#seon/error 94`.

**Cons:** it changes the settled `<kind>/<eid>` face; it needs a declared data
reader plus the same preparation mechanism as Option 1; it creates difficult
quote/macro semantics; and printing the tag after transparent resolution
requires the rejected wrapper or metadata mechanism. If it remains a reference
value, callers must explicitly resolve/deref it, contradicting the ruled
ordinary-value behavior.

**Verdict:** elegant for an explicit reference API, not for the ruled
transparent value API.

### Option 3 — tagged lazy proxy

**Face:** `#seon/result 88`, read as a record or type implementing `IDeref` and
possibly `ILookup`.

**Resolution and laziness:** retain only EID and pinned database value until
first access, then fetch. This is genuinely later than Probe C, but first access
happens on `:compute`; moving the fetch to `:io` makes it eager per form again.

**Print round trip:** print the proxy as its tag. Admission currently emits an
opaque class/address node, so the tag would require a new special admitted
face.

**Pros:** no bytes are fetched when a proxy is merely carried unchanged;
explicit deref can be useful in a deliberately reference-oriented API.

**Cons:** not transparent across Clojure operations, violates the compute
workload boundary when truly lazy, and becomes an opaque object at admission.

**Verdict:** reject.

### Option 4 — resolved value with receipt metadata

**Face:** evaluate to the real value carrying
`{:seon.result/eid 88}` metadata; teach the printer to emit
`#seon/result 88` or `result/eid-88` instead of bytes.

**Resolution and laziness:** resolution still happens on `:io` before compute,
so this is not lazier than Option 1. Metadata merely attempts to retain origin
after resolution.

**Print round trip:** a metadata-aware admission pass would need to turn the
metadata into a new first-class print-node face. Current `seon.print` dispatches
only on the admitted node's `:seon.print/face`, and an unknown face throws
(`src/seon/print.cljc:439-458,542-566`). Raw value metadata is no longer present
by that point.

**Pros:** metadata is non-semantic for supported Clojure collections and raw
SCI preserves it in several common transformations.

**Cons:** impossible for scalar and nil results; stripped at the one admission
boundary; inconsistently preserved by arbitrary transformations; ambiguous
when equal values came from different receipts; and printing only the origin
would hide the actual computed value in violation of decision 11
(`docs/prds/sci-execution-runtime/plan/README.md:2200-2205`).

**Verdict:** reject. `#inst` works because the tagged value has one actual value
type with a reader/printer contract. An arbitrary result is not one type, and
receipt provenance is not its value semantics.

## Print ownership and large results

The short handle belongs in the sanctioned transcript suffix, not in
`seon.print`'s representation of an ordinary resolved value. The archaeology
already settled the ownership split: `seon.print` owns actual value/elision and
`seon.render.transcript` owns the receipt-derived trailing comment
(`docs/prds/sci-execution-runtime/research/result-identity-archaeology-2026-08-06.md:157-169,296-316`).

That split already prevents a large stored result from being pulled into
context merely to render history. Settlement stores the complete admitted node
as a blob only when that is smaller and leaves a bounded window in
`:seon.cluster.eval/result-edn` (`src/seon/cluster/loop.clj:529-552`). The
transcript entry carries receipt EID, window, blob digest, and size, but
`bounded-result` reads and prints only the stored `result-edn`; it does not read
the blob (`src/seon/render/transcript.clj:339-373,435-502`). Appending the suffix
therefore does not serialize full result bytes into agent context.

The exact composition is:

1. emit stdout, if any;
2. emit the actual fitted result or flat error through the existing print path;
3. append exactly ` ; result/eid-<receipt-eid>` after the final result bytes;
4. never put that suffix inside the admitted print node, message value, or
   authored source.

Current `receipt-text` renders prompted source plus the receipt family output
but appends no handle yet (`src/seon/render/transcript.clj:519-539`). The entry
already retains `:db/id` at `:349-373`, so implementation needs no identity
lookup or process cache at the print seam.

If a later form resolves a blob-backed handle, the blob must be loaded on
`:io` so SCI receives the real value. If that form returns the value again, the
new evaluation admits and records its real result normally. Avoiding that
admission would require a special top-level pass-through and would cease to
work when the value is nested, transformed, or passed through a function. The
bounded transcript window solves context size without compromising ordinary
value semantics.

## General identity face

The recommended spelling preserves the ruled family while making its grammar
valid and explanatory:

| Durable row | Agent face |
|---|---|
| eval or effect receipt | `result/eid-88` |
| message | `message/eid-91` |
| error fact | `error/eid-94` |

The namespace segment says what may be looked up; `eid-` says the suffix is a
Datahike entity ID; the number is the database-native identity. This matches
the archaeology's system-wide conclusion that messages and errors need no
pre-commit generated identity and can expose their committed EID
(`docs/prds/sci-execution-runtime/research/result-identity-archaeology-2026-08-06.md:394-417,426-449`).
Each namespace may resolve to a different ordinary projection, but identity and
grammar remain one policy. No alias spelling such as both `#seon/result 88` and
`result/eid-88` should be accepted; two canonical faces would create avoidable
surface drift.

## Acceptance evidence for implementation

The implementation wave should retain one end-to-end matrix:

- reader: invalid `result/88`, valid `result/eid-88`, no raw-text repair, one
  parsed event;
- positions: bare, operator, vector/map/set/list member, argument,
  destructuring initializer, nested message value, and run-completion value;
- non-positions: quote and syntax quote remain symbol data; dynamically
  constructed handles fail as ordinary unresolved symbols;
- basis: same EID at an older database value returns a flat absent-at-basis
  value, while the later value resolves; a fresh JVM gives the same answers;
- storage: inline and blob-backed receipts, missing blob, malformed admitted
  node, and reclaimed receipt all return specific flat values;
- workload: all pulls, blob reads, and EDN reads finish on `:io` before compute
  submission; compute observes an already interned ordinary value;
- print: actual result/elision followed by one receipt-derived suffix, including
  multiline and error faces; transcript rendering never reads the full blob;
- performance: retain the no-handle benchmark and prove no historical-result
  preload.

These are extensions of the grounded Probe C matrix, not a second result
mechanism.

## Drift and ugly output encountered

Two current architecture statements still describe the deleted process-local
result model: restart allegedly loses live result values at
`docs/seon/architecture/context.md:85-90`, a transcript result may be a
process-local handle at `:138-147`, and toolkit lifecycle facts allegedly wipe
handles on restart at `docs/seon/architecture/toolkit.md:245-252`. The same
toolkit also still names `seon.db.id/allocate!` as the general generated-ID
owner at `:35-36`. These contradict the 2026-08-06 receipt-EID, database-basis
ruling. They are outside this report-only lane and should be repaired in the
implementation/documentation wave.

The proxy probe also exposed ugly rendered output worth recording. Admission
represented the `IDeref` result as
`#:seon.print{:face :seon.print/object, :class "...LazyResult", :address
"0x..."}`, and its semantic face became
`#:seon.sci.admit{:opaque "...LazyResult"}`
(`tmp/result_handle_metadata_probe_2026_08_06.clj:31-38,81-92`). The address is
process-specific and the face is cryptic. This is further evidence against the
proxy, while also confirming that important reference shapes need an explicit
declared projection rather than falling through the generic object floor.
