---
type: research
status: complete
tags: [research, render, sci, architecture]
---

# Render model change map

This is a decision document, not an implementation plan already approved. It
maps the current tree at `e79d6bf5d` against owner rulings #46–#48 and the
subsequent render-vocabulary refinements. No production source, test, or schema
was changed while producing it.

## Executive result

The change is larger than replacing `requiring-resolve`. The current program
graph cannot yet identify a single renderer by the new rule: it records **zero**
functions returning `:seon.render/ai` and **zero** returning
`:seon.render/html`. It instead records 48 functions accepting the umbrella
`:seon.render/unit` and 33 functions returning `:seon.render/hiccup`. The nine
schema families with defaults contribute 18 declarations, but none of those 18
currently satisfies the complete new contract without a vocabulary rewrite
(`src/seon/program.cljc:254-276`; `resources/seon/schema.edn:900-910,1114-1118,1409-1414,1558-1566,2093-2103,2442-2450,2492-2520`).

Four changes therefore form one atomic model boundary: replace the three old
vocabulary keys, rewrite renderer contracts, validate the 18 declarations
against the resulting program facts, and change resolution to declared
thing → declared schema default → floor. Turning validation on before the
contract rewrite would reject all current defaults.

## Grounding and method

The dependency facts used here are SCI at
`6de15683b7520cc973bc9c136aec7ad3f9b3788c`, Malli's arbitrary schema
properties (`reference-code/malli/src/malli/core.cljc:39`), and the parsed
contract facts produced in `src/seon/program.cljc:254-276`. The static source
manifest contained 2,738 program rows and 1,790 function rows. A second probe
used a fresh in-memory database and the same canonical row population; neither
probe opened or changed the default operator root.

Counts below are parsed schema/program facts where stated. Textual counts use
`rg` over current `resources/`, `src/`, `test/`, and `docs/`; archived documents
are counted separately so historical evidence is not silently rewritten into
current authority.

## Counted inventory

### Schema registrations and declarations

The packaged registry has 690 top-level registrations. The five registrations
at the center of the vocabulary change each exist once:

| Registration | Current state | Proposed state | Risk |
|---|---|---|---|
| `:seon.render/unit` | `[:map-of :qualified-keyword :any]`; three parsed references and 48 function arities with an input ref (`resources/seon/schema.edn:2340-2355`) | Delete. Each renderer names the actual input schema it accepts. Request/unit-building maps remain ordinary named shapes where they have real structure. | **Model change.** Forty-eight contracts and every generic-polymorphism assumption change. |
| `:seon.render/output` | `:any`; nine parsed references, used by rendered/surface/walk envelopes (`resources/seon/schema.edn:392-412,2351-2361,2916-2955`) | Delete. Result envelopes use the requested projection schema, or become a union explicitly parameterized by the two projection schemas. | **Model change.** Current generic envelopes cannot retain an unconstrained output slot. |
| `:seon.render/hiccup` | Predicate plus generator; four parsed references and 34 output arities on 33 functions (`resources/seon/schema.edn:370-381`) | Rename/collapse into the definition of `:seon.render/html`; preserve the same predicate and generator under that key. | Rename plus **behavior change**: every Hiccup-producing helper becomes discoverable as an HTML renderer if output alone is identity. |
| `:seon.render/ai` | Alias of symbol-only `:seon.render/projection`; 14 parsed references and zero program-graph input/output refs (`resources/seon/schema.edn:2305-2321,2363-2369`) | Union of admitted string content and a qualified-symbol producer whose declared output is `:seon.render/ai`. | **Model and persistence behavior change.** The bridge can encode a mixed union, but application reads do not decode it today. |
| `:seon.render/html` | Alias of symbol-only `:seon.render/projection`; 14 parsed references and zero program-graph input/output refs (`resources/seon/schema.edn:360-381`) | Hiccup grammar or a qualified-symbol producer whose declared output is `:seon.render/html`. | **Model and persistence behavior change.** This key becomes both output schema and declaration attribute. |

Nine map schemas carry 18 default properties, one AI and one HTML declaration
each: capture, error fact, instruction, message, namespace, agent, run, run
form, and receipt (`resources/seon/schema.edn:900-910,1114-1118,1409-1414,1558-1566,2093-2103,2442-2450,2492-2520`). All 18 currently accept
`:seon.render/unit`; the nine HTML functions return Hiccup and the nine AI
functions return string or maybe-string. These 18 contracts and properties
must migrate in the same boundary as validation.

Two other schema areas change even though their names are not being retired:

- `:seon.render.block/block`, `:seon.render/request`,
  `:seon.render/rendered`, and `:seon.render/surface` embed the old declaration
  or output shapes (`resources/seon/schema.edn:352-358,392-412,2353-2361`).
  They need projection-specific shapes or a documented generic wrapper that is
  no longer `:any`. **Model change.**
- `:seon.render.walk/request`, node, and flattened unit store namespace/floor,
  projection, and generic output fields (`resources/seon/schema.edn:2904-2955`).
  Namespace selection must disappear; result fields must adopt the two exact
  output schemas. **Model and behavior change.**

The shape projection currently copies only qualified-symbol `seon.render/*`
properties (`src/seon/schema.clj:1276-1305`). A literal schema default would be
silently absent from render resolution. The property projection must admit the
settled declaration value schema and refuse invalid values rather than filter
them away. **Behavior change.**

Open-map behavior is already landed: ordinary matching validates the complete
open value at `src/seon/schema.clj:2394-2415`, and the regression proves an
extra render attribute does not disqualify the schema while an invalid declared
attribute still fails (`test/seon/schema_test.clj:88-120`). No render-specific
extra-attribute matcher remains.

### Function contracts

The program graph reports these exact migration sets:

- **48 symbols accept `:seon.render/unit`.** They occupy 17 source files and
  include the 18 schema defaults, generic floors, block/walk helpers, transcript
  helpers, and composition renderers. The source has 67 textual unit references
  in those 17 files. Every contract must name its real input; helpers that are
  not renderers should retain ordinary input/output schemas and cease to appear
  in candidate queries (`src/seon/program.cljc:254-276`).
- **33 symbols have 34 arities whose output refs include
  `:seon.render/hiccup`.** Twenty-four symbols have that key as a direct output
  ref. The set includes the nine HTML defaults but also
  `seon.print/emit-hiccup`, `seon.render.block/slot`,
  `seon.render.block/expand`, `seon.render.root/heading`, and
  `seon.render.transcript/reasoning-disclosure`. Collapsing the key without a
  further qualification rule classifies all 33 as HTML render functions.
- **Zero functions currently return `:seon.render/ai` or
  `:seon.render/html`.** Candidate and declaration-validation queries keyed on
  the ruled outputs therefore return nothing today.

The 33-symbol Hiccup-output inventory is:

```text
seon.cluster.instruction/instruction-html
seon.cluster.message/render-html
seon.cluster.run/render-form-html, render-html, render-receipt-html
seon.context/capture-html
seon.error/render-html
seon.oversight/block-html, html-table
seon.print/emit-hiccup
seon.problems/block, html-report
seon.render.agent/agent-header-html, agent-html, namespace-html, transcript-html
seon.render.block/data-panel, entity-slot, expand, slot
seon.render.ns/render-html
seon.render.root/agents-html, header-html, heading, messages-html,
  problems-html, text-html, tokens-html
seon.render.transcript/reasoning-disclosure, render-html
seon.render.value/render-html, render-html-data
seon.render.web/message-bar-html
```

The 48-symbol exact inventory is:

```text
seon.cluster.instruction/instruction-ai, instruction-html
seon.cluster.message/render-ai, render-html
seon.cluster.run/render-ai, render-html, render-form-ai, render-form-html,
  render-receipt-ai, render-receipt-html
seon.context/capture-ai, capture-html, execution-ai
seon.error/render-ai, render-html
seon.oversight/ai-story, block-ai, block-html, html-table, unit
seon.problems/block
seon.render.agent/agent-ai, agent-header-html, agent-html, namespace-ai,
  namespace-html, transcript-html
seon.render.block/data-panel, data-prose
seon.render.ns/render-ai, render-html
seon.render.root/agents-html, header-html, messages-html, problems-html,
  text-html, tokens-html
seon.render.transcript/minimum-token-budget, render-ai, render-html
seon.render.value/node-id, prepare, render-ai, render-html, result-window-edn
seon.render.walk/projection
seon.render.web/message-bar-html
seon.render/kinds
```

This is not a list to preserve in production. It is the counted migration
inventory produced from the current program facts. Representative contracts
are at `src/seon/context.clj:62-92`, `src/seon/error.clj:827-863`,
`src/seon/cluster/run.clj:1007-1150`, `src/seon/render/agent.clj:59-83`, and
`src/seon/render/block.clj:572-678`.

The 21 source files in the textual migration set are:

```text
src/seon/cluster/instruction.clj
src/seon/cluster/loop.clj
src/seon/cluster/message.clj
src/seon/cluster/prompt.clj
src/seon/cluster/run.clj
src/seon/context.clj
src/seon/error.clj
src/seon/oversight.clj
src/seon/print.cljc
src/seon/problems.clj
src/seon/render.clj
src/seon/render/agent.clj
src/seon/render/block.clj
src/seon/render/ns.clj
src/seon/render/root.clj
src/seon/render/transcript.clj
src/seon/render/value.clj
src/seon/render/walk.clj
src/seon/render/web.clj
src/seon/schema.clj
src/seon/schema/internal.cljc
```

`src/seon/sci/eval.clj`, `src/seon/sci/admit.clj`, `src/seon/cluster.clj`,
`src/seon/flow.clj`, `src/seon/eval/drive.clj`, and the run/message/wake/work
owners are additional execution or repair seams not found by the retiring-key
search. They are separately inventoried below.

### Router, execution, walk, and page pipeline

The directly affected production surface is 21 source files plus the schema
resource by textual dependency. The change-bearing owners are:

| Area | Current state | Proposed change | Risk |
|---|---|---|---|
| `seon.render` | Resolution is thing → constructed namespace name → schema → floor; resolution catches failures; invocation uses JVM `requiring-resolve` and direct Var call (`src/seon/render.clj:271-382`). | Delete `namespace-declaration`; make declaration/genuine absence/structured failure distinct; resolve a declared symbol from the request database's program row and the cluster's live SCI `ctx`; invoke the live SCI Var through the one guarded kernel; admit and validate by projection before returning a value. | **Behavior change, central.** Resolution, failure, and execution semantics change together. |
| SCI kernel seam | Full evaluation owns arm/interrupt/admission and cluster acquisition installs definitions once (`src/seon/sci/eval.clj:939-1012,1200-1255,1380-1394`). | Extract only the already-ruled small live-Var invocation seam; do not acquire a context or evaluate source during rendering. Return admitted semantic value and diagnostics, with no receipt EDN sink. | **Behavior change.** Incorrect factoring creates a second evaluator or loses interruption/admission. An uncommitted working-tree prototype exists and is not treated as landed evidence. |
| Block owner | Builds one polymorphic unit, invokes router, validates Hiccup after invocation, and converts failures to internal error cards (`src/seon/render/block.clj:166-274,309-324`). | Thread `ctx`, database value, caps, time limit, owner agent and failure identity to the kernel; move kind validation into the common admission boundary; project HTML failure to loading/unavailable instead of an internal error card. | **Behavior change, user-visible.** |
| Walk owner | Resolves each node, invokes the router, and directly invokes transcript renderers on a special branch (`src/seon/render/walk.clj:634-666`; transcript special case at `:544-557`). | Use one router call for every renderer; delete the direct transcript bypass; retain ordinary tree composition only. | **Behavior change.** This is required by “one execution path.” |
| Prompt/turn callers | Prompt capture and turn context call AI rendering synchronously (`src/seon/cluster/loop.clj:1347-1352`; `src/seon/render/transcript.clj:334-372`). | Supply the live cluster `ctx` and render invocation facts; consume the loud AI failure value without allowing a throw into the proc. | **Behavior change.** Agent context deliberately diverges from HTML. |
| Web render proc | Render pass has the cluster handle but page requests carry database/caps/connection, not `ctx` or render time limit; suppression is whole produced-page equality (`src/seon/render/web.clj:528-583`). Initial paint repeats the reduced request (`:785-794,1051-1062`). | Thread the existing handle's `ctx` and config-derived invocation limit into both live passes and initial paints. Add the ruled per-function-call cache before claiming “per cache miss.” | **Behavior and performance change.** |
| Feed writer | Broad catch closes the feed without a fault (`src/seon/render/web.clj:807-820`; continuation requires reinspection at implementation). | Commit writer faults through the existing fault owner with page/tab/projection evidence before close. | **Behavior change.** Separate from renderer mistakes but required to close the filed issue. |
| Hiccup `raw` | Public `Raw`; documentation claims agent code cannot construct it; grammar accepts it (`src/seon/render/hiccup.clj:61-95,138-139`). | Remove the false reachability claim. Admission turns a record into ordinary data and projection validation refuses it; retain `raw` only for trusted post-admission serializer composition at `src/seon/render/web.clj:1051-1110`. | Safety clarification plus **behavior regression** for guarded returns. |

Seven production namespaces contain **nine** direct `render/render` call sites:
`cluster.loop`, `error`, `oversight`, `problems`, `render.block`, and
`render.transcript`; `render.walk` calls both `resolve-unit` and `render`
(`src/seon/cluster/loop.clj:1347-1352`; `src/seon/error.clj:454-456`;
`src/seon/oversight.clj:275-279`; `src/seon/problems.clj:463-466`;
`src/seon/render/block.clj:236-274,507-512`;
`src/seon/render/transcript.clj:334-372`;
`src/seon/render/walk.clj:634-666`). All must receive the same request facts or
delegate through a caller that does. No direct call is allowed to reconstruct a
smaller JVM-only request.

Three direct projection paths need disposition beyond those nine calls:

- the walk transcript branch directly invokes `transcript/render-ai` or
  `render-html` (`src/seon/render/walk.clj:523-558`); this is an agent-driven
  render bypass and must be deleted;
- evaluation grading directly calls `transcript/render-ai`
  (`src/seon/eval/drive.clj:239-245`); whether grading is “agent-driven” under
  ruling #46 is **unverified** and needs an owner answer; and
- `/data` directly calls the HTML floor (`src/seon/render/web.clj:1198-1212`).
  It is a web render but not declaration resolution; whether trusted inspection
  composition must enter the kernel is **unverified**.

The shared-context wiring break is concrete: cluster construction puts one
`ctx` in the loop handle and hands that handle to the render proc, but the web
view/service schemas and `serve!` projection omit it
(`src/seon/cluster.clj:1034-1086,1123-1137,1211-1220`;
`resources/seon/schema.edn:3010-3051`). Page construction, initial feed paint,
and live render passes consequently cannot call the ruled kernel
(`src/seon/render/web.clj:306-353,528-583,785-794`).

### Cache and invalidation

The architecture requires per-call `(renderer fn × explicit args) → bytes`,
dependency commit IDs, conservative database revision, and code revision
(`docs/seon/architecture/context.md:40-47`; `docs/seon/architecture/ui.md:390-447`).
The current render proc has only `::produced` whole-page equality suppression
(`src/seon/render/web.clj:528-583`). Database reads already capture read
evidence through the database owner, but no render-call cache consumes it. The
phrase “invoke per cache miss” therefore depends on an **unbuilt mechanism**,
not merely new kernel wiring. This was not resolved in the renderer ruling and
must be decided before performance acceptance can be honest.

### Failure, repair, and persistence

Renderer mistakes must become one flat failure value carrying renderer symbol,
projection, block, agent, database basis, throwable class/message/ex-data or
admission refusal, and invocation diagnostics. The existing router discards
resolution throwable evidence and the existing block error card exposes the
message to HTML (`src/seon/render.clj:294-305,364-382`;
`src/seon/render/block.clj:309-324`). Both behaviors change.

The persistent-failure state machine should be event-driven:

1. a new failure signature commits one fault fact and one idempotently derived
   message to the owning agent;
2. an open repair run causally linked to that message makes HTML loading honest;
3. terminal repair settlement changes HTML to success or honest unavailable;
4. an unchanged signature neither creates another message nor another repair
   episode; a changed signature starts one new episode; and
5. AI remains loud for the full lifetime of the unresolved fault.

The existing error owner already derives signatures, bounded recurrence, agent
attribution, and idempotent messages (`src/seon/error.clj:251-341,672-820`;
recurring proof at `test/seon/error_test.clj:339-354,492-605`), but renderer
failures currently are process-local return values rather than durable error
facts. Its signature hashes process, class, kind, and top frame, deliberately
excluding message/data (`src/seon/error.clj:251-258,315-323`). Different
non-Hiccup renderer/block failures can therefore collide unless renderer,
projection, and stable call identity become explicit signature facts. The
missing integration is causality:
the renderer fault/message/repair run must be queryably connected so “loading”
is derived from an actually open run, not a timer or a stored spinner flag.
Immediately after the fault/message commit and before the owner claims the
message, the honest HTML state is unavailable. The run-opening transaction
changes it to loading; the terminal transaction changes it back to success or
unavailable. This necessarily touches `src/seon/cluster.clj` or the run-opening/message
transaction owner, which was protected by the `created-at-deletion` lane when
implementation was stopped.

### Tests

Eight existing test files directly depend on the retiring vocabulary or router
envelopes: `test/seon/cluster/turn_test.clj`,
`test/seon/context_capture_test.clj`, `test/seon/error_test.clj`,
`test/seon/oversight_test.clj`, `test/seon/problems_test.clj`,
`test/seon/render/block_test.clj`, `test/seon/render/walk_test.clj`, and
`test/seon/render_test.clj`. Five more render/schema test files exercise nearby
HTML grammar, family renderers, or schema properties and need review:
`test/seon/render/agent_test.clj`, `hiccup_test.clj`, `ns_test.clj`,
`transcript_test.clj`, and `test/seon/schema_test.clj`. Four additional
execution/failure owners make **17 direct proof files** in the complete change:
`test/seon/render/web_test.clj`, `test/seon/cluster/prompt_test.clj`,
`test/seon/sci/admit_test.clj`, and `test/seon/sci/eval_test.clj`. Program-fact
validation also extends `test/seon/program_test.clj`; this is an eighteenth
review-required file because its exact change depends on the validation seam.

The recurring proof must cover these behavior classes, not preserve old exact
envelopes:

- declared thing override beats schema default; schema default beats floor;
- absent declaration with zero, one, and multiple qualifying candidates;
- invalid declared symbol is refused at declaration time;
- literal AI and HTML declarations at each supported persistence boundary;
- agent-authored definition appears on a page through the shared cluster `ctx`;
- first-party renderer uses the identical guarded kernel;
- throw, non-Hiccup, admitted `raw`, oversized/lazy output, and an interpreted
  infinite loop all return values through the real render path;
- HTML loading exists only while the causally linked repair run is open, then
  becomes unavailable; AI stays loud; unchanged signatures do not remessage;
- one failed block does not prevent sibling blocks or the feed from rendering;
- cache hit avoids invocation, database dependency change and code revision
  invalidate, and equal bytes suppress delivery; and
- SSE writer failure commits a core fault before close.

No existing test injects a feed-writer failure. The real-socket fixture drains
the graph error channel without asserting a durable fault
(`test/seon/render/web_test.clj:62-150`).

The final live proof must use an isolated operator root and a fresh cluster
forked from the newly published source. It must record the page bytes, agent
context bytes, fault/message/run facts, loop interruption, cache measurement,
and clean `bin/seon down`; it must never use the default root.

### Documentation

The focused search finds 100 Markdown files mentioning the retiring terms or
superseded resolution/execution semantics: 49 are under archive paths and must
remain historical; 51 are current-plan, research, issue, architecture, or
vision documents. Most dated research is evidence and should receive a
supersession link rather than a historical rewrite.

Current authorities that require semantic edits in the implementation landing:

- `docs/seon/architecture/ui.md:86-99,386-447` currently specifies namespace
  candidate selection and a first-party/agent-authored execution split. Both
  directly contradict the settled declared-selection and one-kernel model.
- `docs/seon/architecture/context.md:40-47` correctly specifies the missing
  call cache, but must use the collapsed output vocabulary.
- `docs/seon/architecture/architecture.md`,
  `docs/seon/vision/m1-reliable-runtime.md`, and
  `docs/seon/vision/m3-convention-uniformity.md` mention the render contract and
  need a terminology audit.
- `docs/prds/sci-execution-runtime/plan/README.md:1999-2092` owns rulings
  #46–#48; its earlier render sections remain historical inside the same ledger
  and should be explicitly superseded, not silently normalized.
- `docs/prds/sci-execution-runtime/research/agent-renderer-design-2026-08-02.md:84-179`
  uses the now-retired unit/hiccup request and predates declared-selection and
  repair messaging. It remains measurement evidence but needs a superseded-by
  link to this decision map.
- The three issue notes named in this lane remain open until implementation and
  live proof. This document is their current design inventory.

The remaining 43 non-archive matches are dated research, bounded plans,
current issue/index material, and one MCP PRD. They should be mechanically
classified during the documentation landing as either current authority to
edit or historical evidence to annotate. This count is intentionally marked
**review-required** rather than claiming every textual mention is stale.

For completeness, the 51 non-archive files in that pre-report search snapshot
were:

```text
docs/prds/mcp-surface/README.md
docs/prds/sci-execution-runtime/conversion-wiki.md
docs/prds/sci-execution-runtime/plan/README.md
docs/prds/sci-execution-runtime/plan/cache-invalidation-plan-2026-07-31.md
docs/prds/sci-execution-runtime/plan/context-blocks-contracts-2026-07-28.md
docs/prds/sci-execution-runtime/plan/context-render-data-model-spec.md
docs/prds/sci-execution-runtime/plan/f2-transport-contracts-2026-07-28.md
docs/prds/sci-execution-runtime/plan/reference/conversion-wiki.md
docs/prds/sci-execution-runtime/plan/runtime-impacted-tests-2026-08-02.md
docs/prds/sci-execution-runtime/plan/turn-evaluate-refactor-prd-2026-08-02.md
docs/prds/sci-execution-runtime/plan/unsettled.md
docs/prds/sci-execution-runtime/plan/w4-html-plan-2026-07-31.md
docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md
docs/prds/sci-execution-runtime/research/agent-renderer-design-2026-08-02.md
docs/prds/sci-execution-runtime/research/agent-startup-audit-2026-07-31.md
docs/prds/sci-execution-runtime/research/context-blocks-plan-2026-07-28.md
docs/prds/sci-execution-runtime/research/context-mvp-notes-2026-07-31.md
docs/prds/sci-execution-runtime/research/context-render-retirement-2026-07-31.md
docs/prds/sci-execution-runtime/research/context-walk-synthesis-2026-07-31.md
docs/prds/sci-execution-runtime/research/doc-reconciliation-2026-07-31.md
docs/prds/sci-execution-runtime/research/error-handling-grounding-2026-07-27.md
docs/prds/sci-execution-runtime/research/full-codebase-audit-2026-08-02.md
docs/prds/sci-execution-runtime/research/grader-mechanics-grounding-2026-08-01.md
docs/prds/sci-execution-runtime/research/malli-schema-parsing-2026-08-01.md
docs/prds/sci-execution-runtime/research/n4-contracts-2026-07-27.md
docs/prds/sci-execution-runtime/research/n4-plan-2026-07-27.md
docs/prds/sci-execution-runtime/research/n5-plan-2026-07-27.md
docs/prds/sci-execution-runtime/research/old-context-assembly-2026-07-29.md
docs/prds/sci-execution-runtime/research/old-namespace-schema-lookup-quarry-2026-07-31.md
docs/prds/sci-execution-runtime/research/render-current-state-2026-07-31.md
docs/prds/sci-execution-runtime/research/render-invalidation-falsification-2026-07-31.md
docs/prds/sci-execution-runtime/research/render-scheduling-design-2026-07-31.md
docs/prds/sci-execution-runtime/research/renderable-corpus-falsification-2026-07-28.md
docs/prds/sci-execution-runtime/research/renderable-corpus-plan-2026-07-28.md
docs/prds/sci-execution-runtime/research/sci-containment-surface-audit-2026-07-23.md
docs/prds/sci-execution-runtime/research/sci-door-ctx-sharing-2026-07-31.md
docs/prds/sci-execution-runtime/research/simplification-catalog-2026-07-28.md
docs/prds/sci-execution-runtime/research/test-selection-spec-2026-07-27.md
docs/prds/sci-execution-runtime/research/w2b-transcript-notes-2026-07-31.md
docs/prds/sci-execution-runtime/research/w4-html-impl-notes-2026-07-31.md
docs/prds/source-cleanup/research/envelope-symbol-conformance-2026-07-20.md
docs/prds/source-cleanup/research/universal-data-browser-design-2026-07-20.md
docs/seon/architecture/architecture.md
docs/seon/architecture/context.md
docs/seon/architecture/ui.md
docs/seon/issues/agent-renderers-never-enter-the-sci-program-context.md
docs/seon/issues/index.md
docs/seon/issues/live-publication-has-a-hand-maintained-predicate-owner-reload.md
docs/seon/issues/render-resolution-and-feed-swallow-failures.md
docs/seon/vision/m1-reliable-runtime.md
docs/seon/vision/m3-convention-uniformity.md
```

Two semantically affected current documents did not match that term search and
must be added to the review set: `docs/seon/architecture/agent-runtime.md:161-176`
for ordinary message/run repair facts, and
`docs/seon/issues/error-render-puts-its-own-failure-in-agent-context.md:90-97`
because its “says nothing” acceptance now contradicts loud owner-facing AI.
Thus the current-document review inventory is **53**, of which eight have known
semantic edits and 45 are review-required/supersession candidates. Concurrent
files created after the snapshot are not asserted here.

## Behavior changes requiring explicit owner acceptance

These are not renames:

1. **Output-only identity classifies helpers as renderers.** Collapsing Hiccup
   into HTML makes all 33 current Hiccup producers candidate HTML renderers,
   including composition helpers. If that is intended, candidate queries must
   additionally ask whether an arity accepts the schema being rendered; output
   identity alone is still the definition, but input compatibility bounds use.
2. **Omission is unsettled.** Most family renderers return `[:maybe :string]`
   or `[:maybe :seon.render/hiccup]`. Exact direct-output validation rejects
   them; membership in `output-refs` accepts them. The new definition says
   string/Hiccup but does not say whether `nil` omission remains a legal render.
3. **Literal declarations are not logically round-trippable today.** Both
   stored attributes are currently symbol-only. The maintained bridge already
   derives `:db.type/string` for heterogeneous unions and encodes logical EDN
   (`src/seon/schema/datahike.clj:135-150,268-378`), but its decoder has no
   application-read integration (`src/seon/schema/datahike.clj:432-468`;
   `src/seon/db.clj:216-314`). Without that integration a stored producer symbol
   returns as text and is mistaken for literal content. Supporting the union is
   a database-read behavior change, not only a schema edit.
4. **Declaration errors move earlier.** Invalid schema defaults and thing
   overrides become transaction/publication refusals instead of page failures.
   This is desirable, but it changes which transaction succeeds.
5. **HTML stops exposing internal error cards.** It shows loading only for a
   causally open repair run, then unavailable. AI and the agent message retain
   full detail. This deliberately changes page bytes and fault transaction
   volume.
6. **Unchanged renderer faults create no repeated work.** Signature identity
   gates both durable fault/message creation and repair episodes. The existing
   random error id and per-error message id are insufficient across cache or JVM
   loss (`src/seon/error.clj:672-692`; `src/seon/cluster.clj:1013-1016`). A
   changed signature starts one new episode.
7. **Every first-party call pays SCI admission.** The compiled bypass is gone;
   latency/allocation shift from direct Var calls to the measured guarded
   kernel. The 250-event allocation remains a hot-path risk.
8. **`raw` returned by any renderer is refused.** Trusted web composition may
   still use it only after admitted fragments have become serializer bytes.
9. **The floor and “show everything” behavior may change.** The current HTML
   floor always exists but is hidden by default on curated pages
   (`docs/seon/architecture/ui.md:111-118`). The new floor must be chosen
   explicitly below.
10. **A real call cache is introduced.** This changes invocation counts and
    invalidation behavior even when rendered bytes remain equal.
11. **AI failure visibility becomes owner-specific.** Current walk prose prints
    a node failure to every viewing agent (`src/seon/render/walk.clj:844-857`).
    The ruling names the owning agent; non-owner behavior needs the decision
    below.

## Open owner decisions

### 1. No schema default and candidate renderers exist

- **Option A — recommended:** selection is never inferred. Zero candidates
  reaches the floor. One or more candidates with no declaration returns a loud
  `:seon.render/missing-declaration` diagnostic naming the qualifying symbols;
  HTML follows the repair-state projection and AI is loud. This makes a missing
  declaration visible even before it becomes ambiguous.
- Option B: one candidate is selected automatically; multiple candidates fail
  loudly. This is convenient but contradicts “selection is declared, not
  inferred,” and silently makes the first qualifying function permanent.
- Option C: any absent default reaches the floor, while candidate discovery is
  diagnostic-only elsewhere. This preserves page totality but hides the exact
  missing declaration the owner asked to surface.

### 2. Durable symbol and in-process function arms

- **Option A — recommended:** there is one producer representation everywhere:
  a qualified symbol resolved to the live SCI Var. Do not admit a process-local
  function object as a declaration. Content remains the other arm (string for
  AI, Hiccup for HTML). This preserves one invocation/caching identity and
  avoids separate durable/runtime producer declarations. The existing
  Malli→Datahike EDN fallback must be completed by decoding every application
  read before logical validation (`src/seon/schema/datahike.clj:268-378,432-468`;
  `src/seon/db.clj:216-314`).
- Option B: define separate named durable and in-process declaration schemas
  under the same AI/HTML keys; the runtime schema additionally admits `ifn?`.
  This supports anonymous closures but creates a second invocation identity,
  cannot survive restart, and complicates declaration validation and caching.
- Option C: separate producer and content attributes. It maps cleanly to
  Datahike scalar types, but creates more than the ruled two declarations and
  requires a new precedence rule.

Current code admits symbol/string/vector, not function values
(`src/seon/render.clj:232-245`), so an in-process function arm would itself be
a new capability rather than preservation.

### 3. What the floor is

- **Option A — recommended:** two ordinary guarded renderer functions with a
  genuinely polymorphic named input predicate, one returning AI and one HTML.
  They are explicitly selected only by the router's declared floor branch;
  zero candidates reach them, while missing declarations with candidates stay
  loud. HTML remains hidden by default unless “show everything” is enabled.
- Option B: no content (`nil`) is the floor. Simpler and safer, but gives up the
  existing total structural inspection behavior.
- Option C: keep the current `data-prose`/`data-panel` behavior outside SCI.
  This violates the one-kernel ruling.

### 4. Declaration-time validation

- **Option A — recommended:** one pure declaration validator consumes the
  proposed complete program rows and schema rows. Static initialization calls
  it before `seon.fn/index!` transacts (`src/seon/fn.clj:687-730`); runtime
  schema/function publication calls it against the candidate projection before
  the terminal transaction (`src/seon/sci/eval.clj:1396-1442`); durable thing
  overrides enter through one transaction function that validates the named
  function at that database value. Qualification requires a public function,
  a compatible input arity, and the exact requested output fact.
- Option B: store a ref to the `:seon.fn` entity rather than a symbol. Datahike
  gives referential integrity, but it changes the owner-ruled representation
  and does not by itself validate arity/output.
- Option C: validate only at render time. This is explicitly rejected by the
  owner requirement.

The static and runtime schema/function populations are separate today, so
schema admission alone cannot validate first-party defaults against function
facts. The validator must see the combined candidate population, not query a
half-published database.

### 5. Does `nil` remain valid omission?

- **Option A — recommended:** yes. A renderer qualifies when its declared
  output is the projection schema or an explicitly named optional projection
  schema, and `nil` means omission, never failure. Preserve existing behavior
  while making optionality explicit in the output fact.
- Option B: no. Every renderer must return content, and conditional blocks move
  their condition outside rendering. Stronger totality, but a broad behavior
  rewrite across most current family renderers.

### 6. Where the per-call cache lands

- **Option A — recommended:** implement the architecture's process-local cache
  in the render-flow owner before switching all first-party calls to the
  guarded kernel. Key it by live Var identity/symbol plus admitted explicit
  arguments; retain dependency commit IDs, conservative revision, and code
  revision. Cache both success and failure signatures.
- Option B: ship the kernel first with no cache, measure it, then add caching.
  This gives a simpler intermediate diff but knowingly moves the hot path to
  guarded execution on every pass and cannot satisfy “per cache miss.”

### 7. Repair-run ownership and causality

- **Option A — recommended:** the committed renderer fault causes one ordinary
  message whose `about` ref points to the fault; the existing message-triggered
  run is the repair episode. HTML loading is the query “that message has an
  open held run,” and terminal settlement produces success or unavailable.
- Option B: introduce a dedicated repair entity/run path. It is more explicit
  but creates a second scheduling mechanism and is not justified by current
  requirements.

### 8. What a non-owning agent sees

- **Option A — recommended:** the renderer-owning agent receives the complete
  loud error and repair message. Other agents receive a short ordinary
  unavailable contribution naming neither exception internals nor repair
  instructions. Humans receive loading/unavailable only.
- Option B: every agent whose walk reaches the block receives the loud error.
  Easier and closest to current `walk/prose`, but it asks agents who cannot own
  the namespace to repair it and may multiply context noise.
- Option C: non-owners see omission. Quiet, but hides a material hole in their
  rendered context.

## Landing order and concurrency boundaries

1. **Vocabulary atomic group:** coordinate with `render-vocabulary`; change the
   five registrations, all 48 affected contracts, the 18 default properties,
   projection extraction, and declaration validator together. `resources/seon/schema.edn`
   is also the schema-writer/open-map owner's former protected path; begin only
   from its landed commit. No intermediate state may enable validation while
   the graph still reports zero AI/HTML renderers.
2. **Router/kernel/cache atomic group:** delete namespace selection, add the one
   SCI invocation kernel, thread the shared `ctx` through every direct caller,
   add per-call cache/invalidation, dissolve `raw`, and remove direct transcript
   invocation. `src/seon/sci/eval.clj` currently has unrelated uncommitted work;
   take an explicit handoff before editing its minimal seam.
3. **Failure/repair atomic group:** commit renderer failures, derive the one
   message/repair episode, and implement HTML loading→unavailable plus loud AI.
   This crosses `src/seon/cluster.clj`, the run/message/error owners, and render
   flow. It must sequence after `created-at-deletion` releases
   `src/seon/cluster.clj`; it cannot be truthfully completed only inside the
   owned render files.
4. **Focused proof:** vocabulary/program queries; router resolution; guarded
   throw/non-Hiccup/raw/loop; cache invalidation; repair state; feed fault.
5. **Documentation landing:** correct active architecture, mark dated design
   research superseded, update and close the three issues only with recurring
   and live evidence.
6. **Frozen live gate:** publish current source, fork an isolated cluster, run
   the two agent-authored renderer proofs and measurements, tear down the root,
   then run `bin/test` and record exact counts. Concurrent source lanes must be
   paused for the artifact digest and full suite.

## Findings not covered by the rulings

1. **The output-only definition is broader than the apparent intent.** Thirty-
   three current Hiccup producers include generic composition helpers. The
   input side of the contract is therefore essential for “what could render
   this”; otherwise `slot` and `expand` are renderers for unrelated schemas.
2. **The current graph makes the approved query return zero.** The output-key
   migration is not cosmetic. It is a prerequisite for selection validation
   and should be proven with the exact Datalog query before router work begins.
3. **The cache described by the rulings and architecture is not implemented.**
   Whole-page equality suppression is downstream and cannot prevent invocation.
4. **Schema literal defaults are silently dropped.** Shape projection filters
   properties to qualified symbols instead of rejecting unsupported declaration
   values (`src/seon/schema.clj:1295-1303`).
5. **The active architecture contradicts the owner twice.** It still selects
   same-schema functions by namespace and guards only agent-authored calls
   (`docs/seon/architecture/ui.md:86-99,390-397`).
6. **The old “open kind set” is incompatible with the new two-output identity.**
   `:seon.render/log`, `kinds`, and any future SMS/metric kind currently share
   the generic router (`src/seon/render.clj:20-28,51-66`;
   `resources/seon/schema.edn:2363-2369`). The owner model names exactly AI and
   HTML outputs. Either log exits this router or the new definition must say how
   non-agent-driven kinds are identified and executed.
7. **Failure signatures collapse distinct value failures today.** The existing
   signature excludes renderer symbol, projection, block identity, message, and
   error data (`src/seon/error.clj:251-258,315-323`). Returned non-Hiccup errors
   in one process can therefore share class/frame absence and collide. The
   repair identity must include renderer, projection, and stable call identity.
8. **Compiled host calls remain a named interruption limit.** SCI can interrupt
   only on interpreted function-body entrances; a compiled host call that never
   returns cannot be cancelled (`reference-code/sci/doc/interrupt.md:50-65`).
   The end-to-end loop proof must use an interpreted renderer and must not claim
   stronger cancellation.
9. **Declaration-time override validation has no universal write seam yet.** A
   Datahike attribute schema proves only scalar shape. If first-party code can
   transact the symbol without the validator, “refuse at declaration time” is
   false. This is a cross-cutting database-write invariant, not a page check.
10. **The current returned-error notification policy excludes this feature.**
    `commit-tx` messages an attributed agent only for a `Throwable`; returned
    errors message nobody (`src/seon/error.clj:719-728,793-820`, pinned by
    `test/seon/error_test.clj:507-528`). Renderer repair needs an explicit
    reason/policy, not Throwable wrapping.
11. **A production regex exists in the adjacent error presentation owner.**
    `src/seon/error.clj:602` uses one in `log-line`, which conflicts with the new
    regex law. It was not changed or separately filed because this research
    lane owns only this report and the three render issue notes.
12. **The mixed-union codec is half-wired.** Transaction encoding exists at the
    one database write seam (`src/seon/db.clj:483-490`), and the schema bridge
    has a validating decoder (`src/seon/schema/datahike.clj:432-468`), but query,
    pull, entity, and datom reads do not call it (`src/seon/db.clj:216-314`). A
    symbol producer would therefore become a literal string after storage. This
    is a prerequisite for choosing the ruled content-or-producer union.

## Decision gate

Implementation should remain stopped until the owner rules on the seven open
questions, especially literal persistence, omission, floor semantics, and
whether output-only identity intentionally includes all Hiccup helpers. Once
ruled, the first safe production change is the vocabulary/contract/validation
atomic group—not the router kernel in isolation.
