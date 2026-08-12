---
type: research
status: active
tags: [research, render, observability]
---

# Bad-output catalog and queryable quality design

## Directive and verdict

Owner directive, 2026-08-10: catalog and track every potentially bad output in
one well-designed system. The governing test is stricter than “we can grep the
log”: an occurrence that a Datalog query cannot find is missing a fact. The
quality surface is a derived namespace page over those facts, never a stored
report, issue queue, acknowledgement, counter, or notification stream.

This census found **23 output classes**. Five have a direct database anchor,
three are derivable by Datalog from existing facts, and **15 are invisible to
Datalog today**. The largest common gap is not a missing renderer: Seon retains
the inputs and sometimes the resulting string, but does not retain the
projection decision that produced the consumer-visible face.

I read the required authorities end to end before this design:

- [Datastar web UI skill](../../../../.agents/skills/datastar-web-ui/SKILL.md)
- the render, everything-queryable, error, transport, and data-oriented
  sections of [the shared repository authority](../../../../AGENTS.md)
- [UI architecture](../../../seon/architecture/ui.md)
- [observability architecture](../../../seon/architecture/observability.md)
- [universal output floor PRD](../plan/universal-output-floor-prd-2026-08-04.md)
- [universal output floor research](universal-output-floor-2026-08-04.md)

The data design also follows the repository's data-oriented Clojure,
data-modeling, and Datahike skills: attributes and connections rather than an
output-kind entity, absence rather than stored nil, transaction provenance
rather than copied timestamps, and query-derived counts rather than tallies.

## Classification rule

The status column uses these exact meanings:

- **DATABASE FACT** — Datalog can select the occurrence directly by attribute
  presence or a ref.
- **DERIVABLE** — every operand needed to identify the occurrence is already a
  fact and Datalog can compute the predicate; no EDN/string parsing or log read
  is required.
- **INVISIBLE** — the occurrence survives only in serialized EDN, rendered
  bytes, a transient render/package value, stderr/stdout, a browser console, or
  process-local state. A durable string containing the evidence still counts
  as invisible when Datalog cannot address its internal fields.

The census is a dated research snapshot, not a class roster for production.
The implemented query must discover rows by the existing family attributes and
connections described below; it must not copy these 23 labels into an enum or
case expression.

## Dependency and mechanism ledger

| Mechanism | Selected source | Existing owner used by the design |
|---|---|---|
| Structural printing and fit | `src/seon/print.cljc:141-160,277-301,380-552,600-791`; `resources/seon/schemas/seon.print.edn:214-263` | One print grammar; declared elision values; `seon.print/fit` |
| Render selection and guarded call | `src/seon/render.clj:110-221,238-265,294-353,374-447,479-519` | One explicit/contract/schema/floor selector and one render call |
| Walk and web delivery | `src/seon/render/walk.clj:280-305,318-462,568-671`; `src/seon/render/web.clj:249-424,680-776,925-1116` | One walk, render proc, package, and feed writer |
| Static crossing coverage | `src/seon/fn.clj:510-539,568-688`; `resources/seon/schemas/seon.fn.edn:34-39` | `:seon.fn/external-sink`, `:seon.fn/projection-boundary`, and `output-path-report` |
| Durable errors | `src/seon/error.clj:264-355,713-874`; `resources/seon/schemas/seon.error.edn:1-75` | One error fact, signature, recurrence query, routing transaction |
| Core fault committer | `src/seon/cluster.clj:1914-2029,2198-2236`; `src/seon/flow.clj:838-911` | One Flow error channel and one fault committer |
| Attempts and provider observations | `src/seon/ai.clj:701-799,873-918,1068-1127`; `src/seon/cluster/loop.clj:642-712,748-851`; `resources/seon/schemas/seon.ai.attempt.edn:1-14` | One attempt row, error ref, usage observation, and truncation ref |
| Prompt evidence and token fit | `src/seon/context.clj:140-188`; `src/seon/cluster/prompt.clj:71-110,148-225`; `src/seon/ai/tokens.cljc:46-155` | Exact context capture plus one token estimator/budget report |
| Boot/operator output | `src/seon/cluster.clj:86-95,582-613,1397-1413`; `src/seon/schema.clj:665-818`; `script/seon/fresh_operator.clj:2302-2310,2473-2489` | Existing boot logger/progress and operator terminal boundaries |

The selected dependency revisions are the repository pins at this basis:
Datahike `56f1c62105b7`, the vendored Malli source
under `reference-code/malli/`, and core.async Flow under
`reference-code/core.async/`. This design needs no new writer, event bus,
browser reporting service, or render registry.

## Census

| ID | Potentially bad output class | Production source | Query status today | Concrete recent example |
|---|---|---|---|---|
| P1 | A structural print face is present but visually unreadable or unlabeled. | `src/seon/print.cljc:141-160` | **INVISIBLE.** The final Hiccup/string is transient; no fact says which face reached which output. | The 2026-08-10 root page had 699 bare disclosure triangles because the summary label was not visible ([UI truth:30-33,60-101](ui-truth-2026-08-10.md)). Commit `977f3a033` fixed the current face, but historical occurrences remain unqueryable. |
| P2 | An anonymous structural cut loses count, path, offset, profile, or continuation (`...`, `#`, truncated string). | `src/seon/print.cljc:380-384,409-410,418-419,440-442,476-486,518-520,544-546` | **INVISIBLE.** The marker exists only in emitted bytes or a serialized print tree. | A root message map ended in bare `...`, unlike the declared elision value ([UI truth:163-166](ui-truth-2026-08-10.md)). |
| P3 | A declared elision or explicit requery refusal reaches a consumer. This is often honest, but it is still a quality boundary worth finding. | `src/seon/print.cljc:277-296,536-538,600-619,695-791`; `resources/seon/schemas/seon.print.edn:214-263` | **INVISIBLE.** The shape is ordinary data, but actual render occurrences are not facts; when nested in `result-edn`, Datalog sees only a string. | `/data` showed 8 of 525 schema keys and one configured-window elision after 7.86 seconds ([UI truth:55,140-150](ui-truth-2026-08-10.md)). |
| P4 | Projection/admission produces the `failed` face, the print grammar meets an unknown face, or the emergency floor refuses missing caps. | `src/seon/print.cljc:522-526,548-552`; `src/seon/render/value.clj:350-363` | **INVISIBLE.** A throw may later become an error fact, but the consumer-visible refusal occurrence and face do not. | The print-face switch recently threw bare `No matching clause: `, naming no missing member ([overnight report:341-349](../plan/overnight-report-2026-08-08.md)). |
| R1 | The selector falls through to the generic structural floor. The floor is correct totality, but repeated fallback identifies an important shape without a declared producer. | `src/seon/render.clj:173-221`; `src/seon/render/value.clj:365-377` | **INVISIBLE.** `:would-fall-to-floor?` exists only in a contract schema/test shape (`resources/seon/schemas/seon.render.edn:96`); no occurrence is transacted. | The missing-connection MCP error rendered its map as a vector of pairs, and generic error/result faces expanded dramatically ([UI truth:167-169](ui-truth-2026-08-10.md)). |
| R2 | Renderer selection is ambiguous, the selected producer fails, or its result violates the requested AI/HTML contract. | `src/seon/render.clj:139-148,238-265,294-399`; `src/seon/render/walk.clj:280-305,412-462` | **INVISIBLE.** Nested projection errors can leave the original node in place; root failures become transient flat values or opaque messages, not linked render observations. | Every prompt briefly collapsed into a 931-character `invalid-output` render-walk violation ([overnight report:503-524](../plan/overnight-report-2026-08-08.md)). |
| R3 | The web UI substitutes a `Renderer unavailable` box/message for the intended output. | `src/seon/render.clj:479-519`; `src/seon/render/web.clj:249-271,306-424` | **INVISIBLE.** A message may be committed, but it carries no structured render-call ref, requested projection, producer, or substituted-face fact. | The root page contained 33 renderer-unavailable boxes, consistently at one depth-2 neighbour position ([UI truth:43,160-162](ui-truth-2026-08-10.md)). |
| R4 | Semantically inappropriate content reaches an audience or projection even though its underlying source fact is valid. | `src/seon/ai.clj:94-120`; `src/seon/render/transcript.clj:1-80`; `src/seon/render/walk.clj:568-671` | **INVISIBLE.** The source reasoning may be a fact, but no fact connects it to the AI/HTML projection that exposed it. | Raw model scratch reasoning appeared verbatim as the primary visible summary text on the root page ([UI truth:152-159](ui-truth-2026-08-10.md)). |
| R5 | A rendered block/page/package is excessively large for the value delivered. | `src/seon/render/web.clj:306-424,680-776,925-968` | **INVISIBLE.** Package/keyframe/delta sizes are process-local values, and response bytes are not facts. | `/` was 903 KB for 348 units; `/ns/seon.db` was 925 KB ([UI truth:41-48](ui-truth-2026-08-10.md)). |
| R6 | Render computation or delivery latency is pathological despite an unchanged basis. | `src/seon/render.clj:401-447`; `src/seon/render/web.clj:680-776` | **INVISIBLE.** Retained evidence and timings are process-local; no render receipt carries elapsed work. | Core namespace pages took 12–18 seconds and `/data` took 7.86 seconds for 3,168 bytes ([UI truth:121-150](ui-truth-2026-08-10.md)). |
| R7 | An SSE patch targets no element in the receiving page, wasting compute, bytes, and console attention. | `src/seon/render/web.clj:944-968,1028-1116` | **INVISIBLE.** The server writes a package without target membership evidence; the only rejection is a browser console warning. | A debug-page load emitted more than 200 `PatchElementsNoTargetsFound` warnings ([UI truth:107-119](ui-truth-2026-08-10.md)). |
| G1 | A consumer-visible external-sink path bypasses or never reaches its required projection boundary. | `src/seon/fn.clj:510-688`; `resources/seon/schemas/seon.fn.edn:34-39` | **DERIVABLE.** `output-path-report` queries program rows and computes projected, bypass, and unresolved shortest paths. | The universal-output-floor census still names MCP, runner, log/fault/stderr, operator, and page-chrome crossings as conversion work ([universal output floor PRD](../plan/universal-output-floor-prd-2026-08-04.md)). |
| E1 | An agent mistake, refused transition, provider failure, or core problem reaches an agent/human as an error face. | `src/seon/error.clj:264-355,361-410`; `src/seon/cluster/loop.clj:500-528,556-587` | **DATABASE FACT.** `:seon.error/id`, `/kind`, `/message`, `/signature`, optional `/run` and `/agent`, plus receipt error facts are queryable. | The whole-system arc queried 60 errors, including 14 `:seon.ai/unparseable-body` and 12 contract violations ([observer:395-425](whole-system-arc-observer-2026-08-08.md)). |
| E2 | A terse error/result expands into a disproportionately large print face. | `src/seon/cluster/loop.clj:556-574`; `resources/seon/schemas/seon.cluster.eval.edn:28-39,55-58` | **DERIVABLE.** Datalog can join error presence to `:seon.cluster.eval/result-size` and compare the scalar. | Six words, `Unable to resolve symbol: my.web/fetch`, produced a 2,154-character result face (`tmp/tool-exercise/results/web-fetch.edn`; [overnight report:325-337](../plan/overnight-report-2026-08-08.md)). |
| E3 | Error evidence itself is explosively large or capped. | `src/seon/error.clj:264-341`; `resources/seon/schemas/seon.error.edn:1-75` | **DERIVABLE.** `:seon.error/data-edn` and `/capped?` are facts; Datalog can bind `(count ?data-edn)` without parsing its contents. | One error carried 4.25 million characters of `data-edn`; a later arc's largest was still 158,271 ([overnight report:253-255](../plan/overnight-report-2026-08-08.md); [observer:410-413](whole-system-arc-observer-2026-08-08.md)). |
| F1 | The first occurrence of a core Flow fault reaches durable forensics and possibly stderr. | `src/seon/cluster.clj:1922-2029`; `src/seon/flow.clj:838-911` | **DATABASE FACT.** The first signature is normalized and committed as `:seon.error/fact`. | A live injected loop throw produced six faults in 1.5 seconds; the first durable fact was the evidence used to bound escalation (`src/seon/error.clj:790-809`). |
| F2 | A repeated core fault with an already-seen signature disappears before the writer, so recurrence and volume cannot be queried. | `src/seon/cluster.clj:1914-1984`; `src/seon/flow.clj:862-884` | **INVISIBLE.** Both the process-local signature set and database signature query suppress the repeat transaction. | The same six-fault live loop is documented as requiring query-derived recurrence, yet the current committer can retain only one occurrence. This directly contradicts `src/seon/error.clj:790-845`, which says facts keep committing after notification becomes silent. |
| F3 | Fault-channel overflow drops a core fault and prints a warning instead. | `src/seon/cluster.clj:2198-2229`; `src/seon/flow.clj:820-836` | **INVISIBLE.** Only an atom increments and stderr receives `seon.error DROPPED a fault`; neither the dropped fault nor the count is a datom. | The current cluster graph has a 64-entry counted-dropping fault buffer and the only overflow evidence is that stderr line (`src/seon/cluster.clj:2203-2229`). |
| A1 | Prompt/context bytes grow excessively or one contribution dominates the prompt. | `src/seon/context.clj:140-188`; `resources/seon/schemas/seon.context.capture.edn:1-33` | **DATABASE FACT.** Exact prompt, character count, contribution positions, hashes, and token estimates are durable. | Root's debug capture held 61,190 characters; an earlier run reached 116,572 prompt characters ([UI truth:48](ui-truth-2026-08-10.md); [observer:313-320](whole-system-arc-observer-2026-08-08.md)). |
| A2 | Actual provider token budget, completion:prompt ratio, cached-token use, or reasoning share is pathological. | `src/seon/ai.clj:873-918`; `src/seon/cluster/prompt.clj:71-110,148-225`; `src/seon/cluster/loop.clj:684-712` | **INVISIBLE to Datalog.** The attempt stores `usage-edn` and `settings-edn` strings; normalized token fields and the budget decision are not addressable datoms. | 225 prompt tokens produced 10,502 completion tokens; 2,025 produced 66,591 (32.9:1); another attempt crossed the 32,768 budget at 35,827 tokens, with reasoning 90–99% of completions despite `thinking :disabled` ([overnight report:253-276](../plan/overnight-report-2026-08-08.md); [observer:268-306](whole-system-arc-observer-2026-08-08.md)). |
| A3 | A provider attempt fails, retries, or fails over and therefore yields no usable output. | `src/seon/cluster/loop.clj:642-712,748-851`; `resources/seon/schemas/seon.ai.attempt.edn:1-14` | **DATABASE FACT.** Attempt `/error` and `/failover-from` refs join to ordinary error facts; absence/presence derives the disposition. | Fourteen concurrent sibling calls recorded `:seon.ai/unparseable-body`, in retry pairs ([observer:431-458](whole-system-arc-observer-2026-08-08.md)). |
| A4 | A streamed provider response ends after partial text and the partial completion is retained with explicit truncation. | `src/seon/ai.clj:1068-1127`; `src/seon/cluster/loop.clj:684-712,748-851`; `resources/seon/schemas/seon.ai.attempt.edn:13` | **DATABASE FACT now.** `:seon.ai.attempt/truncation` refs the committed truncation error. | The same-day falsifier preserved `(+ 1 2)` after `IOException("closed")` ([claims sweep:259-303](claims-sweep-2026-08-10.md)); commit `a8a38313c` subsequently made that truncation queryable. |
| O1 | Boot, schema fallback, operator status, or terminal warning output becomes a warning wall that hides the useful signal. | `src/seon/cluster.clj:86-95,582-613,1397-1413`; `src/seon/schema.clj:665-818`; `script/seon/fresh_operator.clj:2302-2310,2473-2489` | **INVISIBLE.** Occurrences are logger/progress/terminal strings or process-local counters. Some underlying claims are EDN files, but no Datalog query can find the emitted warning. | Declaration-population fallback occupied 44 of the first 62 boot lines; `bin/seon status` separately repeated eight unreadable-root warnings ([observer:460-484](whole-system-arc-observer-2026-08-08.md); [claims sweep:330-352](claims-sweep-2026-08-10.md)). |

### Count check

| Status | Classes |
|---|---:|
| DATABASE FACT | 5 |
| DERIVABLE | 3 |
| INVISIBLE | 15 |
| **Total** | **23** |

## What the census proves

### Facts about sources are not facts about outputs

The database often retains the source value—an attempt, an error, a capture, or
an eval receipt—while losing the consumer-facing decision. Without the selected
producer, requested projection, floor/refusal evidence, and fitted size, a query
cannot distinguish “the fact existed” from “a human or agent was shown this
face.” Recording rendered bytes would violate the UI architecture; recording
the semantic decision does not.

### Opaque EDN is a database fact but not a queryable model

`usage-edn`, `settings-edn`, and nested print trees preserve bytes for
forensics, but Datalog cannot join their members. The attempt family already
normalizes provider usage in memory (`src/seon/ai.clj:873-892`); keeping that
normalization only outside the database is the reason token budgets and C:P
ratios are invisible.

### Error notification suppression and fact suppression are different laws

`seon.error/commit-tx` correctly derives recurrence by counting facts, sends a
final notice at the configured occurrence, and becomes silent after it
(`src/seon/error.clj:713-725,790-874`). The cluster fault committer then defeats
that design by refusing to transact the second matching signature
(`src/seon/cluster.clj:1970-1984`). The design must suppress messages and stderr,
not evidence.

### Static coverage and dynamic quality answer different questions

`seon.fn/output-path-report` already answers “which code can reach a sink
without the required projection?” It cannot answer “which floor, refusal,
oversized face, or failed patch did a reader actually encounter?” The quality
page should render both query results as two sections, not replace either with
the other.

## Visibility closure for the 15 invisible classes

These are source observations, not a generic quality-event entity. Each row
names the **one existing family fact** that makes the occurrence discoverable
and the current choke point that owns it. The output-quality query discovers
them by attribute presence and joins; it never enumerates the census IDs.

| Invisible IDs | One fact to declare or retain | Existing choke point that commits it |
|---|---|---|
| P1 | `:seon.render.call/print-face`, the qualified face selected for the call | The print sink returns face evidence with its value; the render proc includes it in the render-call transaction data. |
| P2, P3 | `:seon.render.call/elisions`, component refs to the already-declared elision values; anonymous cuts first become that one shape | `seon.print/fit`/the print floor returns its elisions; the render proc commits the refs, not rendered text. |
| P4 | `:seon.render.call/error`, a ref to the ordinary `:seon.error` fact | The print floor returns the flat refusal; the existing fault/error transaction and render proc commit fact plus ref together. |
| R1 | `:seon.render.call/floor?` on the call whose normal selector chose the structural floor | `seon.render/select-producer`, carried through the existing retained render call and committed by the render proc. |
| R2, R3 | `:seon.render.call/error`; error presence plus requested output derives contract failure and unavailable substitution | `seon.render/render-call` and `renderer-failure`; the current message transaction gains the error fact/ref rather than a second diagnostic path. |
| R4 | `:seon.render.call/source`, a ref to the fact/value entity whose projection was emitted | The walk already knows the discovered unit and requested output; the render proc commits that connection. |
| R5 | `:seon.render.call/serialized-size`, the byte count of the already-serialized block/package | The one serialization owner in the render proc, before package fan-out. |
| R6 | `:seon.render.call/duration-ms`, measured around the existing retained render call | The render proc, in the same render-call transaction data. It is an observation, not a performance report or threshold verdict. |
| R7 | An ordinary `:seon.error/fact` with producer-owned kind and the missing stable element id in bounded data | The web package builder validates package targets against the registered page layout before `write-package!`; the failure enters the existing Flow fault committer. No browser telemetry path is added. |
| F2 | Another ordinary `:seon.error/fact` for every occurrence, sharing the same signature | The existing fault committer stops deduplicating transactions; only message/stderr emission remains signature-suppressed. |
| F3 | An ordinary `:seon.error/fact` describing fault-buffer overflow | The existing fault fanout reserves delivery of this bounded overflow fact to the same committer instead of printing from the producer thread. |
| A2 | Queryable normalized provider-usage attributes on the attempt, replacing opaque `usage-edn` as the usage authority for new rows | `record-attempt!`, from the already-computed `ai/normalize-usage` value, in the same attempt transaction. Raw provider evidence may remain a blob; it is not a second normalized authority. |
| O1 | An ordinary `:seon.error/fact` for each structured post-database boot/operator warning; pre-database sink potential remains visible through `output-path-report` | The existing boot logger/progress or operator result boundary hands structured data to the fault committer once a branch connection exists. Genuinely pre-database failures have no occurrence history under Option 1 below. |

The render-call evidence above is one open entity shape identified by the
existing stable render-call identity and connected to its source. It does not
store HTML, prompt text, a copied timestamp, a quality verdict, or a class
keyword. A call is found by identity/source presence; floor, error, elision,
size, duration, producer, and requested output are facts about that call.

## Owner design gate — exactly three options

### Option 1 — exceptional render receipts in existing families **(RECOMMENDED)**

**Guarantee.** Every post-database bad-output occurrence in this census is a
Datalog row in its existing render, error, attempt, capture, or eval family.
The render proc commits only exceptional call evidence (floor, elision,
refusal/error, or configured size/duration observation); the fault committer
commits every occurrence but derives notification suppression by signature;
the attempt row exposes normalized usage and truncation. Static
`output-path-report` keeps genuinely pre-database terminal sink classes
queryable even though their individual occurrences have no database yet.

**Implementation cost/risk.** Medium. It adds one open render-call evidence
shape and transaction data at the render proc, removes the fault fact dedup,
normalizes attempt usage into queryable attributes, and routes structured
post-database warnings through the existing fault owner. The main risk is a
render-listener feedback loop; render evidence must be excluded from its own
interest plan or committed only with the causal source transaction so the page
does not repaint because it observed itself.

**Operational trade-off.** Write volume follows exceptional outputs, not all
successful renders. Queries can count actual exceptions and inspect their
source/basis, while normal render bytes and transient packages remain free to
discard.

**What we give up.** We do not retain a receipt for a normal successful render,
and a failure before any database branch exists has only static sink coverage
plus its bounded terminal face, not a durable occurrence count.

### Option 2 — a receipt for every render call

**Guarantee.** Every render call—successful or exceptional—commits source,
requested output, selected producer, floor/error/elision connections,
serialized size, duration, and basis. Quality queries compare the complete
population without sampling, and the absence of a bad attribute is meaningful
because the successful denominator is durable. Errors and attempts continue
to use their existing fact families; rendered bytes are still never stored.

**Implementation cost/risk.** High. A page with 348 units writes at least 348
receipt entities per rendered basis before fan-out; `/data`, debug pages, hot
stream blocks, and equality-suppressed rerenders need exact identity and
idempotency rules. The render feedback-loop and Datahike write-amplification
risks are material and require a measured proof before implementation.

**Operational trade-off.** This gives the strongest rates, denominators, and
latency distributions, but turns an otherwise pure derived read into a write
for every projection. Database and render cost become coupled.

**What we give up.** We give up the architecture's cheap zero-write normal
render path and accept substantially larger history in exchange for complete
projection telemetry.

### Option 3 — fail closed at every declared external sink

**Guarantee.** Program-graph sink declarations install structural contracts:
an external sink accepts only a value carrying the required AI/HTML projection
evidence and consumer profile result. Missing producer, anonymous cut,
oversized output, unavailable target, or unqueryable usage refuses before
bytes leave the process; the refusal is an ordinary error fact through the
existing fault committer. Bad output becomes unconstructable rather than only
observable, and the class roster remains the queried sink graph.

**Implementation cost/risk.** Very high. This changes every sink interface and
requires the output-floor conversion to graduate first. Logs, operator output,
MCP, runner, HTTP/SSE, and provider calls must all carry typed projection
evidence, and a mistake can suppress the only diagnostic during boot or a core
fault.

**Operational trade-off.** Consumers never receive degraded or emergency
faces; they receive a loud typed refusal. This is simpler to reason about after
conversion but much harsher while the program still has unresolved/bypass
paths.

**What we give up.** We give up graceful structural fallback and bounded
best-effort output. Until every sink is converted, useful output is refused
instead of shown with a queryable quality defect.

## Recommended namespace page

Under Option 1, the page is the namespace page for a real owner namespace such
as `seon.output.quality`, not a bespoke dashboard route. Its unit is a normal
render function over the current immutable database value with both declared
producers:

- `:seon.render/ai` gives agents the same counts, newest occurrences, source
  joins, and unresolved sink paths;
- `:seon.render/html` gives the owner the same query grouped for scanning and
  drill navigation.

The function queries four open families by their existing identity/connection
attributes: render calls, error facts, attempts/captures/evals, and program
graph sink paths. It derives counts, ratios, recurrence, thresholds, and
current unresolved paths at read time. Adding a new error kind, print face,
renderer producer, attempt observation, or external sink automatically appears
through those family queries; there is no quality-class enum, threshold row,
notification queue, stored report, or per-class renderer.

## Decision requested

Choose Option 1, 2, or 3 before production edits. Option 1 is the smallest
design that closes all post-database visibility gaps while preserving the
zero-write successful render path; Option 2 buys a denominator for every
quality rate; Option 3 makes bad crossings unconstructable but deliberately
refuses graceful fallback. A separate ruling is needed only if individual
pre-database operator occurrences must be retained: that requires naming a
database branch authority outside any cluster that may have failed to open,
which this research does not invent implicitly.
