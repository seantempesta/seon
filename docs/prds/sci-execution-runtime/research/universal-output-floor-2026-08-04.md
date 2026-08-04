---
type: research
status: complete
tags:
  - sci-execution-runtime
  - rendering
  - output-bounds
---

# Universal output floor — 2026-08-04

## 1. Question, answer, and method

The floor for the entry points that matter is intended to be the two existing
render projections, not a new output subsystem. `:seon.render/ai` is the print:
a value becomes bounded text for an agent, tool, log reader, test operator, or
terminal operator. `:seon.render/html` is the page handoff: a value becomes
Hiccup before the web transport serializes it. Those contracts already exist in
the schema registry (`resources/seon/schemas/seon.render.edn:5-21,44-50`) and
the selector already chooses an explicit producer, a matching declared
producer, or the generic value floor (`src/seon/render.clj:95-150`).

The answer to “why can’t we make it universal?” is: there is no genuine
consumer constraint preventing it. The current exceptions are historical
drift. Eval admission, the generic value renderer, the context walk, MCP,
errors, test reporting, operator commands, and logs each grew a locally useful
printer or serializer. Some are bounded, but bounded is not the same as using
the projection. Today there are multiple cap units, multiple elision grammars,
and several places where a value is printed before the declared producer can
see it. The web transport is the one genuine distinction: HTML bytes must not
go through the AI text projection, because the declared handoff is Hiccup and
HTML serialization is transport after that handoff
(`src/seon/render/web.clj:249-271,542-551`).

I read the three requested dogfood reports end to end and the ugly-output
section of all eight reports indexed by the session-curation PRD. I also read
`seon.sci.admit` end to end. The source inventory below is the map on which the
design rests; the known escapes are examples within it, not the inventory.
The eight-report roll-up records the independently observed 2 MiB transaction
reports, 3 MiB agent-creation results, 5 KiB config wall, bad exception/NPE
faces, REPL parity defects, unscoped runtime-status JSON, and missing render
constructor (`docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md:305-319`).
The edge dogfood report independently measured the same 262,147-character
scalar face and confirmed D6/D7
(`docs/prds/sci-execution-runtime/research/repl-dogfood-edges-2026-08-04.md:63-106,125-142`);
the data dogfood report verifies the repaired small transaction face
(`docs/prds/sci-execution-runtime/research/repl-dogfood-data-2026-08-04.md:230`),
and the code dogfood report records the fresh runtime-status contract wall
(`docs/prds/sci-execution-runtime/research/repl-dogfood-code-2026-08-04.md:101-108`).

### Dependency ledger

- SCI supplies the shared evaluation boundary and its `:interrupt-fn`; Seon’s
  output codec is first-party `seon.sci.admit`, not an SCI printer. The eval
  owner calls admission while the evaluation is still armed
  (`src/seon/sci/eval.clj:1702-1710`).
- Malli schema properties are the existing declared-producer registry. The
  render selector reads those declarations through the installed schema
  projection (`src/seon/render.clj:101-150`).
- `seon.print` is the existing two-sink value grammar. Its `TextSink` and
  `HiccupSink` share traversal and differ only at the sink
  (`src/seon/print.cljc:80-176,502-533`).
- Datahike supplies database values and transaction reports. Seon’s admitted
  `seon.db/transact!` face now removes `db-before` and `db-after` before values
  return to an agent; probe P2 below verifies the current face. A direct raw
  Datahike report remains a useful falsifier because its two database values
  expose structural-dispatch failure at depth (probe P3).

## 2. Inventory: every consumer-visible crossing

“Same eval print infrastructure?” means this exact question: does the crossing
keep its subject as a value until `:seon.render/ai`, or as a value until
`:seon.render/html`, with the generic value renderer as the fallback? Passing
through `seon.sci.admit`, `seon.print`, `pr-str`, JSON, or a local truncator by
itself is **not** a yes.

| Crossing | Current value-to-text path and bounds | Same projection infrastructure? | Why different today |
|---|---|---|---|
| SCI eval result | `evaluate` admits the result while armed (`src/seon/sci/eval.clj:1702-1710`). `admit-value` walks a bounded print node and `admit` also stores canonical `result-edn` (`src/seon/sci/admit.clj:450-521`). The four caps are depth 64 levels, collection width 8,192 retained entries, string length 262,144 characters, and 65,536 visited nodes (`config/default.edn:35-58`; `src/seon/config.clj:98-114`). Admission is bounded ordinary-data conversion, not a consumer projection. | **No.** It is the correct pre-print safety codec, but the result crosses later through transcript or MCP-specific printers. | Historical layering. Admission predates the declared render boundary and is also needed for durable `result-edn`, which is not itself consumer-visible text. |
| Admission semantic face | Width and node exhaustion become the bare keyword `:seon.sci.admit/elided`; a truncated string becomes a map carrying the retained prefix and original length (`src/seon/sci/admit.clj:390-426`). Canonical EDN is then printed separately (`src/seon/sci/admit.clj:428-448`). | **No.** These are internal values/serialization. They become a defect only when a consumer shows the bare marker without projection metadata, as runtime status did in probe P5. | Genuine internal need, followed by historical misuse as a face. Keep the codec; stop treating its markers as final rendering. |
| Generic value floor | `seon.render.value/prepare` admits a value or reads an admitted artifact, then emits text and Hiccup through the shared print grammar (`src/seon/render/value.clj:205-245`). It applies the second presentation profile: collection length 32 and nesting level 8 by default (`config/default.edn:65-75`; `src/seon/print.cljc:193-227`). | **Yes, but only at a root selected for rendering.** `render-ai` and `render-html` are the declared generic producers (`src/seon/render/value.clj:413-423`). | This is the floor to extend structurally. It currently walks print shapes, not nested declared-producer shapes. |
| Declared `:seon.render/ai` block | The selector resolves one explicit/schema producer or the generic floor and invokes it through the guarded SCI kernel with admission caps (`src/seon/render.clj:132-191`). `render-ai` accepts only nil, string, or error value (`src/seon/render.clj:193-203`). The database walk calls `render-call` once per pulled root entity (`src/seon/render/walk.clj:393-443`). | **Yes at each selected root. Not complete at depth.** A map nested inside an error, transaction report, operator report, or another render value is never reconsidered for its own producer because `schema-producer` only examines the current map (`src/seon/render.clj:101-130`). | A root-oriented block renderer grew before universal structural printing was required. This is the central incomplete floor, not a reason for a second mechanism. |
| Agent prompt handoff | The prompt owner acquires the retained render walk and returns its exact text plus token contribution (`src/seon/cluster/prompt.clj:66-94`). The loop commits those exact bytes before placing them in `:seon.ai/prompt` (`src/seon/cluster/loop.clj:1378-1421`). | **Yes for block roots.** The provider sees the AI walk’s output. **No for nested bypasses inside a producer:** walk prose still applies `pr-str` to a non-string output (`src/seon/render/walk.clj:541-591`). | Historical assembly inside an otherwise correct projection. The outer crossing is right; print-once is not yet true inside it. |
| Declared `:seon.render/html` block | The same selector invokes the HTML producer and validates Hiccup (`src/seon/render.clj:205-218`). The walk retains each unit’s Hiccup output (`src/seon/render/walk.clj:393-443`), and `surface-html` serializes the wrapper plus output (`src/seon/render/web.clj:249-271`). | **Yes for semantic block content.** | This is the genuine non-AI projection. It should remain separate because the consumer contract is Hiccup, not bounded agent text. |
| Web page shell, stream strip, and debug/data pages | The shell directly constructs and serializes document Hiccup (`src/seon/render/web.clj:180-226`). The stream strip is another direct Hiccup constructor (`src/seon/render/web.clj:273-285`). Page responses combine already serialized unit strings with shell Hiccup (`src/seon/render/web.clj:1272-1295`); `/data` correctly obtains semantic value Hiccup from `value/render-html` but still passes through the direct shell (`src/seon/render/web.clj:1385-1448`). | **Partial.** Blocks use `:seon.render/html`; page chrome does not. | Historical distinction between “renderer” and “scaffold.” The owner ruling says there is no static scaffold path, so page chrome must itself be an HTML-projected value. Final Hiccup-to-HTML/SSE byte serialization remains transport, not another projection. |
| MCP eval value, post `c683c7149` | The cluster projector recognizes eval print nodes, admits non-eval values, computes an artifact digest, windows oversized print nodes, and optionally stores the complete artifact (`src/seon/cluster.clj:254-304`). `mcp-valf` then canonical-EDN serializes the envelope (`src/seon/cluster.clj:306-324`). Commit `c683c7149` repaired exception summarization; current exception values are reduced to kind/message/class/frame (`src/seon/cluster.clj:226-252`). | **No.** It uses admission and `seon.print`, but never `render/render-ai`. | Historical MCP-specific presentation. The tool needed a compact value and retrieval identity, and implemented both beside the render projection. The implementation also contains the cap-layer bug in §3.3. |
| MCP JSON-RPC text envelope | `mcp-success`/`mcp-error` call `content-text`; non-strings go straight to `cheshire.core/generate-string` (`script/seon/dev/mcp.clj:35-39,60-72`). Eval responses assemble every prepl event and pass the map to that function (`script/seon/dev/mcp.clj:569-619`). | **No; floorless as a projection.** A nested eval value may be admitted, but envelope metadata and errors are independently JSON-printed with no AI cap profile. | Historical transport implementation. JSON encoding is a necessary wire codec, but the `content[].text` value is consumer-visible agent context and must be the AI projection of an envelope value before JSON encoding. |
| MCP `runtime_status` | The cluster observation now reduces problem families to counts and omits full problem rows (`src/seon/cluster.clj:354-376`). The MCP bridge combines discovery, observation, and session maps and sends them directly to `mcp-success` (`script/seon/dev/mcp.clj:659-707`). | **No; floorless.** Probe P5 returned direct JSON and exposed bare `seon.sci.admit/elided` buffer values. | Historical drift. The post-dogfood status fix reduced the source value, but did not route it through the AI projection. |
| MCP `get_value` | Stored artifacts are addressed by digest and drilled by path/offset; absent data returns a value-shaped error (`src/seon/cluster.clj:326-352`). The bridge still sends the returned map through `mcp-success` and JSON (`script/seon/dev/mcp.clj:734-803`). | **No.** Retrieval identity exists, but the selected value’s consumer face bypasses declared-producer dispatch. | Historical drift from implementing retrieval in the MCP owner. The retrieval operation should return a value; its tool cap profile should be an AI projection. |
| `doc` and `dir` | SCI installs the REPL macros (`src/seon/bootstrap.clj:120-128`; `src/seon/sci/eval.clj:219-233`). `doc` expands to `println` forms and returns nil (`src/seon/sci/eval.clj:958-979`). Eval captures `*out*`/`*err*` in a `StringWriter` with print length/level bindings (`src/seon/sci/eval.clj:1580-1646`) and truncates captured output to `max-string` characters (`src/seon/sci/eval.clj:256-265`). | **No. Bounded side channel.** The nil result is admitted, while the useful value has already become text. It has no blob identity, omitted count, or declared producer. | Historical inheritance from `clojure.repl`. There is no genuine need for early printing: doc/dir can return structured rows whose AI producer owns the familiar face. |
| Flat error value | Durable error facts and registered error-class schemas declare `seon.error/render-ai` and `render-html` (`resources/seon/schemas/seon.error.edn:37-49,155-168`; `src/seon/error.clj:912-950`). The default AI producer still directly `pr-str`s each evidence value (`src/seon/error.clj:888-903`). | **Partial.** Root error values use the declared projection; nested evidence bypasses structural producer dispatch. | Historical root-only producer design. Error evidence was treated as already printable. |
| `:seon.error/data` and durable error fact | Normalization admits the source, then stores `:seon.error/data-edn` as an already printed canonical string (`src/seon/error.clj:263-340`). Contract instrumentation separately constructs admitted faces with hard-coded caps and embeds serialized `:seon.print` trees and printed args inside error data (`src/seon/instrument.clj:123-154,247-269`). | **No at the embedded values.** The outer error later has an AI producer, but strings have erased the nested values’ shapes and producers. | Historical persistence convenience. This is the canonical print-early bypass to remove. Durable storage may retain canonical value data or a blob, not a pre-rendered consumer face. |
| Fault commit | The flow fault committer calls `error/commit-tx` with eval admission caps, then locally truncates message/content at the blob threshold before transaction (`src/seon/cluster.clj:1419-1465`). That threshold is serialized result characters, but is reused here as a direct string ceiling. | **No consumer crossing at commit itself.** It is durable value storage, but it prematurely damages values using a display/storage dial. | Historical safety backstop. Commit should preserve an admitted value/blob identity. Rendering belongs at later AI/HTML consumption. |
| Fault stderr and dropped-fault notice | `emit-core-fault!` constructs a single line with `str`, locally truncates messages at blob threshold, and calls `println` (`src/seon/cluster.clj:1486-1524`). The counted-dropping callback prints pid data with `pr-str` (`src/seon/cluster.clj:1676-1687`). | **No; floorless.** | Historical emergency output. Loudness is a genuine requirement; a second printer is not. The fault value can be synchronously AI-projected with a small stderr profile and identity before writing. |
| Log faces | `seon.error/log-line` is a direct string join; nested args and message use `pr-str` (`src/seon/error.clj:584-651`). `seon.problems/log-report` composes that face with more direct `str`/`pr-str` families (`src/seon/problems.clj:557-609`). | **No.** These are named producers in spirit but not `:seon.render/ai` calls or a bounded profile. | Historical separate “log grammar.” Logs are an AI-projection consumer profile: concise, single-line, with identity. The projection may select a log-oriented declared producer without becoming a separate pipeline. |
| Message entity | The message schema declares both producers (`resources/seon/schemas/seon.cluster.message.edn:47-73`). The message AI/HTML functions render sender/recipient/content (`src/seon/cluster/message.clj:427-470`). | **Yes at the message root.** | This is the desired boundary. |
| Transcript/session message and eval rendering | Transcript calls declared family producers and the generic floor (`src/seon/render/transcript.clj:421-459`). It nevertheless emits a saved print node directly, builds prompt/source strings, and uses `clojure.main/ex-str` for execution errors (`src/seon/render/transcript.clj:461-512`). The finished transcript itself is a declared AI/HTML producer (`src/seon/render/transcript.clj:700-718`). | **Partial.** Outer session and message roots are projections; nested receipt/result/error paths print early. | Historical composition inside the transcript owner. Structural dispatch lets the transcript remain one producer while eliminating its local printers. |
| Test-runner progress/report | Values become text through the runner’s own `bounded-text`, `pr-str`, Throwable face, progress `println`, and final report path (`src/seon/test/runner.clj:26-82,123-178,588-660`). `bounded-text` incorrectly uses blob threshold as a maximum character count (`src/seon/test/runner.clj:26-35`). | **No; floorless.** Probe P6 shows every suite/test transition emitted directly. | Historical CLI implementation. Runner events are already values internally; the terminal should consume their AI projection under a runner profile. Full diagnostic artifacts can remain blobs/files addressed by identity. |
| `operator/bin/seon` status and lifecycle faces | In-JVM operator calls correctly return maps or flat error values (`src/seon/operator.clj:23-42,71-119`). The process operator formats status rows and lifecycle values with `format`, `str`, `println`, and on failure `prn ex-data` (`script/seon/fresh_operator.clj:1761-1767,2078-2089,2123-2212,2667-2673`). `bin/seon` also has literal usage/error copy (`bin/seon:1-18`). | **No for dynamic faces; floorless.** Probe P7 shows the direct status table. | Historical shell/CLI presentation. Dynamic results should be values rendered through an operator AI cap profile. Literal command grammar is authored text, not a value-to-text conversion; it may remain literal, though declaring a help value would make even that face queryable. |
| Operator log tail | `logs` delegates to `tail -n 200` with inherited output (`script/seon/fresh_operator.clj:2590-2607`). The line count is bounded, but bytes per line and render provenance are not. | **No.** It exposes previously printed log text rather than projecting log values. | Historical file-tail convenience. Retain a log artifact as data/blob and project bounded selected entries with identities; raw artifact retrieval is an explicit requery, not the default face. |

### Inventory verdict

Only three crossings substantially use the intended boundary today: the
generic value floor, root block rendering into agent context, and semantic
block rendering into Hiccup. Message and session rendering use it at their
outer roots but contain local nested printers. Every MCP content string,
doc/dir output, test report, dynamic operator face, fault stderr line, and log
line is outside `:seon.render/ai`. Page blocks use `:seon.render/html`, but page
shell/stream chrome remains outside it. No listed exception is forced by its
consumer; JSON, terminal writes, log files, provider requests, HTML bytes, and
SSE are transport *after* projection, not alternative render owners.

## 3. Defect taxonomy and probes

### 3.1 Probe method

All live probes used the isolated operator root
`tmp/universal-output-floor-0804`, cluster `floor-probe`, published commit
`6a726a19-add0-5322-a1db-3986ef3e7e1e`, and its own prepl. The cluster was
started, probed, then stopped with that root’s `bin/seon down`. P1–P5 used
`eval_clj` or `runtime_status`; P6 used the repository’s only test runner; P7
used the isolated operator. These observations are dated 2026-08-04.

### 3.2 Embedded-value bypass

An embedded-value bypass occurs when an outer map reaches a bounded codec or
declared producer but a nested value has already been printed, or is walked as
an ordinary collection instead of receiving its own declared producer.

- **P1 — pre-printed error tree.** Door form `(my.fs/read 42)` returned an
  uncapped error, but `:seon.instrument/problems` was a 381-character string
  beginning `#:seon.print{:face :seon.print/vector ...}` and
  `:seon.instrument/args` was the string `"[42]"`. The full MCP text was 1,917
  characters. This exactly follows the two serialized assignments in
  `src/seon/instrument.clj:247-269`; outer error rendering cannot recover the
  values’ identities or producers.
- **P2 — current admitted transaction face.** Door form
  `{:probe/report (seon.db/transact! [])}` returned 291 inline characters,
  with transaction id, commit ID, datom count, tx-data, and tempids, and no
  `db-before`/`db-after`. It was neither capped nor windowed. This verifies
  that the current `seon.db` entry point repairs the known top-level report
  symptom before output.
- **P3 — raw report nested in a map.** A JVM probe transacted `[]` through
  Datahike and returned `{:probe/report report}`. The admitted artifact was
  1,988,399 characters, capped, windowed, and stored under digest
  `bbbac9dc5e9a685089463a530d4cad37c14e30496206abf00153f269f766cc97`.
  The inline window still contained both `db-before` and `db-after`, each as a
  generic `datahike.db.DB` map face. That proves the defect is structural, not
  “transaction reports need one special top-level case”: a known monster at
  depth bypasses declared-producer dispatch.
- **P4 — database value at depth.** Door form
  `{:probe/database (seon.db/db)}` produced a 993,627-character artifact and a
  588-character inline generic map ending in bare `...`. The outer digest was
  retrievable, but the elision itself carried neither omitted count nor a path
  identity. This is the same class without a transaction report wrapper.

### 3.3 Cap-unit and cap-layer mismatch

The current layers use three unrelated units:

1. admission caps a string at 262,144 **characters** and collections by
   retained entries, depth levels, and visited nodes
   (`config/default.edn:35-58`);
2. the blob threshold is 4,096 serialized `result-edn` **characters**
   (`config/default.edn:56-58`); and
3. `seon.print` presents only 32 collection entries and 8 nesting levels, but
   does not shorten a scalar string (`config/default.edn:65-75`;
   `src/seon/print.cljc:217-227`).

The exact layering bug is in `mcp-project`. It correctly computes
`projected-node` for an oversized artifact (`src/seon/cluster.clj:277-287`),
but the eval-result branch ignores that variable and calls `evaluation-face`
with the original `evaluation-print-node` (`src/seon/cluster.clj:290-293`).
`evaluation-face` calls `print/emit-text`; length/level do not affect a scalar
string (`src/seon/cluster.clj:200-224`).

**P5 — 1 MiB scalar.** Door form
`(apply str (repeat 1048576 "x"))` returned a 263,072-character MCP content
string. Its envelope said `windowed? true`, `capped? true`, blob size 262,265,
and retrievable digest
`cfbbec8053dd361e864119a55d5c887b55261f4728a70153fe510415158ad261`,
yet `:seon.dev.mcp/text` alone was 262,147 characters. The extra three
characters are the quoted/truncated print face around the admitted
262,144-character prefix. This verifies both the units and the wrong-node
branch; it is not a JSON overhead inference.

Human-visible budgets add a fourth required unit: estimated tokens, never raw
characters. The current transcript converts `max-string` characters to an AI
token budget by a fixed ratio (`src/seon/render/transcript.clj:720-725`), while
the generic value and MCP faces use character/entry profiles. The universal AI
projection must make token budget the consumer-visible cap fact; character and
byte thresholds remain storage/transport implementation facts reported beside
it, not substitutes for it.

### 3.4 Floorless surface

A floorless surface may have a local bound; the defect is that it converts a
value into consumer-visible text without the AI projection, so declared
producers, profile facts, elision identity, and standing completeness proof do
not apply.

- **P5-status — MCP status.** `runtime_status` for the scratch cluster returned
  1,766 JSON text characters directly. It included buffer values printed as
  `seon.sci.admit/elided`, with no count or requery identity. Source confirms
  the reduced runtime value goes directly to `mcp-success`
  (`script/seon/dev/mcp.clj:681-707`) and then JSON
  (`script/seon/dev/mcp.clj:35-39,60-72`). The post-fix status value is much
  smaller than the dogfood 19 KiB face, but the crossing is still floorless.
- **P6 — test report.** `bin/test seon.print-test` passed 7 tests and 32
  assertions. Its terminal emitted runner-owned START/LOAD/BEGIN/END lines,
  Clojure’s `Testing`/summary text, and wrapper-owned setup/cleanup lines. The
  runner’s direct report path is visible at
  `src/seon/test/runner.clj:123-178,588-660`; no line was an AI projection of a
  runner event value.
- **P7 — operator status.** The isolated
  `bin/seon --root ... status` printed a table, cluster count, roster warning,
  process identity, and orphan census directly. The formatting path is
  `script/seon/fresh_operator.clj:2123-2212`. It is useful output, but it has no
  cap profile or requery contract and bypasses declared producers.

### 3.5 Ugly output encountered

- P1 exposed implementation-shaped `#:seon.print` data as a quoted string
  inside an otherwise readable error. This is the requested D6 example.
- P3/P4 showed bare generic database maps and `...` instead of a database
  value identity, basis transaction/commit ID, omitted count, and drill path.
- P5 labelled a quarter-megabyte inline scalar as “windowed.” The metadata was
  truthful about artifact storage but false as a description of the visible
  face.
- P5-status exposed `seon.sci.admit/elided` as if it were a meaningful Flow
  buffer observation. It states neither what was omitted nor how to retrieve
  it.
- P6’s successful seven-test run emitted 24 lifecycle/progress lines before
  the two-line result, plus wrapper lines. The runner has no concise/default
  versus verbose/requery profile.
- P7’s status said both “1/1 clusters alive” and “roster unreadable” because it
  mixed process observation and an intentionally refused offline flock read
  without a projected explanation of their scopes. The facts are compatible;
  the direct table makes them look contradictory.

## 4. Design: completeness of the existing projections

### 4.1 One claim, two existing boundaries

The design adds no third door and no new renderer:

> No consumer-visible text exists except as `:seon.render/ai` applied to a
> value, through a declared producer or the generic value floor. No semantic
> page content exists except as `:seon.render/html` applied to a value.

`seon.sci.admit` remains the bounded ordinary-data codec for evaluation and
durability. `seon.print` remains the shared text/Hiccup grammar used by the
generic value producers. Neither is independently consumer-facing. MCP JSON,
stdout/stderr writes, log append, provider prompt placement, Hiccup-to-HTML,
and SSE framing are transport sinks that accept already projected output.

### 4.2 Print late, once, and structurally

Values remain values until projection. In particular:

- error data stores admitted values or blob identities, never `data-edn`, an
  args string, or a serialized print tree;
- doc/dir return structured documentation values rather than writing into
  eval output;
- transaction reports retain their value shape until projection; `seon.db`
  may provide the canonical compact semantic report, but the AI floor must
  still recognize database values wherever they occur;
- runner/operator/MCP/log owners return event/report/error values; only their
  consumer sink selects an AI cap profile and renders;
- transcript assembly holds source, result, error, message, and receipt as
  values until its one AI or HTML projection.

The existing producer rule becomes recursive. At each value node, the
projection queries the same schema-property declarations now used at a block
root (`src/seon/render.clj:101-150`). A declared producer owns that node’s
semantic face; otherwise the generic floor descends. This is producer
dispatch, not a class-name check or monster list. A database value inside
`:db-before`, an error’s evidence map, a vector, or an MCP envelope therefore
gets the same declared database face it would get at the root. Producer output
re-enters only as projection output, never as a new arbitrary input walk; that
prevents recursive producers from repeatedly selecting themselves.

“Print once” means composition uses structured render fragments until the
outer projection sink emits text or Hiccup. A nested AI producer can contribute
a text fragment, but callers cannot `pr-str` it, quote it into error data, then
send it through another floor. The current shared `Sink` traversal is the
natural implementation seam because text and Hiccup already share one walk
(`src/seon/print.cljc:9-12,159-176,502-533`).

### 4.3 Caps, blobs, and requery are projection facts

Cap profiles are configuration facts selected at `:seon.render/ai`, not
separate pipelines. A profile contains:

- estimated-token budget for the complete visible face;
- structural depth and retained-child budgets used during the projection;
- storage character/byte threshold for moving the complete admitted value to
  a blob; and
- the consumer policy for single-line, multiline, or tabular composition.

The generic algorithm and producer dispatch are identical for agent context,
MCP, operator, runner, and logs. Only the profile facts differ. The current
config already proves caps can be database-derived (`src/seon/config.clj:98-114`);
the defect is that render length/level and several local ceilings are not one
projection profile.

Every elision is a value with all of:

- omitted count in the unit appropriate to the shape (children, characters,
  rows, frames, or estimated tokens);
- total count when knowable without consuming an unbounded/lazy source;
- stable requery identity: blob digest for admitted values/artifacts, entity
  lookup for database facts, report/run/test identity for durable events;
- structural path and next offset for paging; and
- profile identity so the reader knows which cap produced the face.

If no honest requery identity exists, the producer must say that explicitly
and must not promise retrievability. This folds D7 into the floor itself rather
than teaching every consumer a different “more” marker. The existing MCP blob
digest/size/retrievable fields demonstrate the right facts
(`src/seon/cluster.clj:274-304`); the recursive bare `...` and
`:seon.sci.admit/elided` markers are what must disappear from visible output.

### 4.4 Conversion seams, ordered by blast radius

1. **Make the existing AI/HTML selector structurally total.** Extend the
   generic projection walk to query producer declarations at every depth and
   make cap/elision/requery records part of that one walk. Keep the current
   explicit/schema/floor precedence (`src/seon/render.clj:95-150`). This is the
   highest-blast-radius seam and must settle before consumers convert. Gain:
   nested database/error/message values get their declared faces everywhere.
   Loss: incidental EDN fidelity inside generic outer maps; full admitted data
   remains available by identity.
2. **Eliminate pre-printed error data.** Replace `data-edn`, instrument args,
   and serialized problem trees with admitted values/blob refs at
   `src/seon/error.clj:263-340` and `src/seon/instrument.clj:247-269`. Gain:
   structural producer dispatch and useful error faces. Loss: callers that
   treated internal EDN strings as an interface; no consumer contract should.
3. **Route MCP value and envelope values through AI projection.** `mcp-project`
   returns the admitted value/artifact identity; an MCP cap profile projects
   the complete envelope value before `content[].text`; JSON remains only the
   protocol encoding (`src/seon/cluster.clj:254-324`;
   `script/seon/dev/mcp.clj:35-72`). Convert `runtime_status` and `get_value` at
   the same seam. Gain: declared nested faces, token cap, counted/retrievable
   elision, and removal of the 262,147-character bug by construction. Loss:
   raw JSON-as-display; structured machine fields can remain alongside the
   projected text if MCP consumers require them, but cannot be the visible
   face.
4. **Convert transcript, doc, and dir.** Make documentation macros return
   values; remove transcript’s direct `print/emit-text`, `ex-str`, and local
   scalar printer (`src/seon/sci/eval.clj:958-979`;
   `src/seon/render/transcript.clj:421-512`). Gain: one faithful REPL face with
   nested producers and retrieval. Loss: arbitrary println side output being
   confused with a return value. User-authored stdout remains an explicit
   output value and is projected as such.
5. **Convert message/error/log/fault consumers.** Message roots already use
   projections; remove nested preformatting. Declare log and stderr profiles
   on the same AI pipeline, and have fault callbacks project fault values
   before writing (`src/seon/error.clj:584-651`;
   `src/seon/cluster.clj:1486-1524,1676-1687`). Gain: concise identity-bearing
   loud output. Loss: byte-identical legacy log grammar and silent arbitrary
   line growth.
6. **Convert the test runner.** Keep runner events as values through the final
   terminal sink and select concise or verbose AI profiles
   (`src/seon/test/runner.clj:123-178,588-660`). Persist full liveness/thread
   diagnostics as addressed artifacts, not default terminal text. Gain:
   bounded reports and one summary/requery model. Loss: unconditional
   per-test chatter in the default profile.
7. **Convert operator faces.** In-JVM operator functions already return values;
   replace the process script’s dynamic `format`/`println` layer with the
   operator AI profile (`src/seon/operator.clj:71-119`;
   `script/seon/fresh_operator.clj:2123-2212`). Gain: bounded, queryable
   status/lifecycle/error faces. Loss: unbounded tables and `prn ex-data`.
8. **Complete HTML projection ownership.** Declare page shell, message bar,
   stream strip, debug composition, and data page as HTML producers; retain
   Hiccup serialization and Datastar frame construction strictly after that
   boundary (`src/seon/render/web.clj:180-226,273-285,542-551`). Gain: the “no
   scaffold path” rule becomes true. Loss: none semantically; static page
   chrome becomes declared/queryable.

### 4.5 Crossings that should not share the AI floor

- **HTML does not share the AI text floor.** It shares producer discovery and
  structural value semantics, but its declared output is Hiccup. Browser byte
  budgets, escaping, morph identity, and SSE backpressure apply after
  `:seon.render/html`; forcing Hiccup through AI text would destroy structure
  (`resources/seon/schemas/seon.render.edn:44-50`;
  `src/seon/render/hiccup.clj:474-513`).
- **Internal canonical EDN does not share the consumer floor.** `result-edn`,
  blob artifacts, database transaction data, and protocol JSON are durable or
  transport codecs, not visible faces. They retain their own totality and
  storage bounds. The rule is that a consumer never sees those bytes directly;
  it sees an AI or HTML projection of the underlying value.
- **Literal authored copy is not a value conversion.** A fixed usage string,
  CSS, JavaScript asset, or protocol delimiter need not be projected. Dynamic
  values interpolated into that copy do. Keeping this distinction avoids
  pretending that an output projection owns source assets.

There is no genuine reason for MCP, operator, runner, logs, faults, doc/dir, or
agent context to have separate value-to-text mechanisms.

## 5. Standing falsifier: prove no bypass exists

A hand-maintained crossing list would make this report the next source of
drift. The standing proof therefore derives the set from the program graph.

### 5.1 Missing facts to declare

The program graph already records functions and transitive call edges; tests
use those edges for computed discovery rather than naming conventions. Output
completeness needs two additional facts in the same registry:

- a function’s external sink effect: AI-visible text sink, HTML response sink,
  or non-visible codec/storage sink; and
- the projection boundary it implements or requires: `:seon.render/ai`,
  `:seon.render/html`, or none for non-visible bytes.

These are declarations on the platform/dependency leaves, not a list of
first-party callers. Stdout/stderr writers, MCP `content[].text`, provider
prompt placement, log append, HTTP HTML response, and SSE HTML fragments are
sink facts. A newly indexed first-party caller reaches them automatically
through `:seon.fn/calls`; unresolved/dynamic reachability fails the proof
closed until its call edge or sink declaration is made explicit. If the query
cannot classify a sink, the missing fact is the defect.

### 5.2 Computed graph property

For every indexed first-party function, compute all paths to declared external
sinks. The test fails with the shortest counterexample path when:

1. an AI-visible sink path has no dominating `:seon.render/ai` boundary;
2. an HTML sink path has no dominating `:seon.render/html` boundary;
3. a value-to-text operation (`pr-str`, `print`, `println`, `format`, JSON text
   creation, Hiccup serialization, Writer append, or equivalent dependency
   call) occurs before the required projection on a sink-reaching path;
4. an AI projection path uses a cap/threshold constant rather than a config
   profile fact; or
5. a projected elision lacks count plus requery identity/refusal.

The parenthesized examples are query results from declared dependency-leaf
facts, never the test’s roster. Adding a new sink declaration or a new call
edge changes the discovered set without editing the test. Any unresolved call
on a sink-reaching path is a failure, not an exemption.

The test should report totals: indexed external sinks, AI paths, HTML paths,
non-visible codec paths, unresolved paths, and bypass paths. Zero bypasses and
zero unresolved paths is the completeness claim.

### 5.3 Runtime construction proof

Static call graphs can miss reflection or host interop, so the construction
also makes the legal route observable:

- projection returns an identified rendered value carrying projection,
  profile, source identity, and elision records;
- first-party external sink leaves accept that rendered value, not arbitrary
  strings/Hiccup; and
- the standing generative test supplies deeply nested schema-declared monsters
  and errors, then instruments every declared sink and verifies that each
  observed write carries the projection evidence and stays within its profile.

Generators come from the schema registry, including newly declared producer
shapes, so nested coverage is computed rather than a hand list. Mutations place
generated values at arbitrary map/vector/set/list depths and include a
producer whose output itself approaches the cap. The properties are:

- same nested value and profile implies the same bounded face on every AI
  consumer;
- HTML consumers receive Hiccup only;
- every elision reports count and requery identity/refusal;
- retrieving by identity and projecting with a larger profile reveals the
  omitted region; and
- no sink observation exists without projection evidence.

The current MCP 1 MiB scalar, nested raw transaction report, pre-printed error
tree, runtime status, runner report, and operator table become generated
counterexample *shapes*, not permanently enumerated regression cases. One
example test may preserve their historical evidence, but the graduation gate
is the computed graph plus generative sink proof.

## 6. Graduation statement

The universal floor is complete when the graph query discovers no
consumer-visible crossing outside `:seon.render/ai` or `:seon.render/html`, the
runtime construction observes no unprojected sink write, every AI profile is a
database-derived config fact, and every elision carries count plus honest
requery identity. At that point “same eval print infrastructure?” has one
answer for every agent/tool/operator crossing: **yes—the value is admitted as
data when necessary, and it is printed exactly once by its AI projection.**
The web answer is correspondingly precise: **the value becomes page semantics
only through its HTML projection, then transport serializes the Hiccup.**
